package dev.dozyfj.xdroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Date

private data class Status(val time: String, val network: Int, val battery: Int?)

@Composable
internal fun LauncherStatusBar() {
    val context = LocalContext.current
    val status by produceState(readStatus(context), context) {
        while (true) {
            value = readStatus(context)
            delay(15_000L - System.currentTimeMillis() % 15_000L)
        }
    }
    Row(
        Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val style = MaterialTheme.typography.labelLarge.copy(
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(status.time, modifier = Modifier.weight(1f), style = style)
        Text(stringResource(status.network), style = style)
        status.battery?.let { Text(stringResource(R.string.status_battery, it), style = style) }
    }
}

private fun readStatus(context: Context): Status {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    val network = when {
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> R.string.status_wifi
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> R.string.status_cellular
        capabilities != null -> R.string.status_connected
        else -> R.string.status_offline
    }
    val battery = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    return Status(DateFormat.getTimeFormat(context).format(Date()), network, battery)
}
