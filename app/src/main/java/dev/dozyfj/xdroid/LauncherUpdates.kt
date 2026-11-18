package dev.dozyfj.xdroid

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

private const val feedRoot = "https://raw.githubusercontent.com/dozyFJ/xDroid-/ota"
private const val maxManifestBytes = 16 * 1024
private const val maxApkBytes = 100L * 1024 * 1024
private val apkUrlPattern = Regex(
    """https://raw\.githubusercontent\.com/dozyFJ/xDroid-/ota/releases/xDroid-[0-9.]+\.apk"""
)

internal data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
)

internal class LauncherUpdates(private val context: Context) {
    suspend fun check(): UpdateRelease? = withContext(Dispatchers.IO) {
        val manifest = fetch("$feedRoot/latest.json") { connection ->
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(maxManifestBytes + 1)
                if (bytes.size > maxManifestBytes) throw IOException("Update manifest is too large")
                bytes.toString(Charsets.UTF_8)
            }
        }
        val release = try {
            val json = JSONObject(manifest)
            UpdateRelease(
                json.getLong("versionCode"),
                json.getString("versionName"),
                json.getString("url"),
                json.getString("sha256").lowercase(),
            )
        } catch (error: JSONException) {
            throw IOException("Invalid update manifest", error)
        }

        if (release.versionCode <= 0 || release.versionName.isBlank() ||
            !apkUrlPattern.matches(release.url) || !release.sha256.matches(Regex("[0-9a-f]{64}"))
        ) throw IOException("Invalid update metadata")

        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        release.takeIf { it.versionCode > installedCode }
    }

    suspend fun download(release: UpdateRelease): android.net.Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        val temporary = File(directory, "download.part.apk")
        val apk = File(directory, "update.apk")
        directory.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            fetch(release.url) { connection ->
                temporary.outputStream().buffered().use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxApkBytes) throw IOException("Update APK is too large")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) {
                throw IOException("Update checksum mismatch")
            }
            @Suppress("DEPRECATION")
            val archive = context.packageManager.getPackageArchiveInfo(temporary.path, 0)
                ?: throw IOException("Invalid update APK")
            @Suppress("DEPRECATION")
            val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            if (archive.packageName != context.packageName || archiveCode != release.versionCode) {
                throw IOException("Update APK metadata mismatch")
            }
            if (!temporary.renameTo(apk)) throw IOException("Could not save update APK")
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        } finally {
            temporary.delete()
        }
    }

    private fun <T> fetch(address: String, read: (HttpsURLConnection) -> T): T {
        val connection = URL(address).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != 200) throw IOException("Update server returned HTTP ${connection.responseCode}")
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val read = read(buffer, offset, limit - offset)
        if (read < 0) break
        offset += read
    }
    return buffer.copyOf(offset)
}
