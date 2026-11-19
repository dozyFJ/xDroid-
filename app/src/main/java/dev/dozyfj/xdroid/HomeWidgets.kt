package dev.dozyfj.xdroid

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val hostId = 1024
private const val configureWidget = 170
private const val invalidId = AppWidgetManager.INVALID_APPWIDGET_ID

internal class HomeWidgets(private val activity: ComponentActivity, state: Bundle?) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, hostId)
    private val preferences = activity.getSharedPreferences("launcher", Activity.MODE_PRIVATE)
    private var pendingId = state?.getInt("pending_widget", invalidId) ?: invalidId

    var widgetId by mutableIntStateOf(preferences.getInt("home_widget", invalidId))
        private set

    private val bind = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) configureOrSave() else discardPending()
    }

    fun start() {
        host.startListening()
    }

    fun stop() = host.stopListening()

    fun save(state: Bundle) = state.putInt("pending_widget", pendingId)

    fun providers(): List<AppWidgetProviderInfo> = manager.installedProviders
        .sortedBy { it.loadLabel(activity.packageManager).lowercase() }

    fun label(info: AppWidgetProviderInfo): String = info.loadLabel(activity.packageManager)

    fun info(): AppWidgetProviderInfo? = manager.getAppWidgetInfo(widgetId)

    fun add(info: AppWidgetProviderInfo) {
        if (pendingId != invalidId) return
        pendingId = host.allocateAppWidgetId()
        try {
            if (manager.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, null)) {
                configureOrSave()
            } else {
                bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                })
            }
        } catch (_: ActivityNotFoundException) {
            fail()
        } catch (_: SecurityException) {
            fail()
        }
    }

    private fun configureOrSave() {
        if (pendingId == invalidId) return
        val info = manager.getAppWidgetInfo(pendingId) ?: return fail()
        val optional = Build.VERSION.SDK_INT >= 31 &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure == null || optional) {
            commitPending()
            return
        }
        try {
            host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, configureWidget, null)
        } catch (_: ActivityNotFoundException) {
            fail()
        } catch (_: SecurityException) {
            fail()
        }
    }

    fun configurationResult(requestCode: Int, resultCode: Int) {
        if (requestCode != configureWidget) return
        if (resultCode == Activity.RESULT_OK) commitPending() else discardPending()
    }

    private fun commitPending() {
        if (pendingId == invalidId) return
        val previousId = widgetId
        widgetId = pendingId
        pendingId = invalidId
        preferences.edit().putInt("home_widget", widgetId).apply()
        if (previousId != invalidId) host.deleteAppWidgetId(previousId)
    }

    private fun discardPending() {
        if (pendingId == invalidId) return
        host.deleteAppWidgetId(pendingId)
        pendingId = invalidId
    }

    private fun fail() {
        discardPending()
        Toast.makeText(activity, R.string.widget_failed, Toast.LENGTH_SHORT).show()
    }

    fun remove() {
        if (widgetId == invalidId) return
        host.deleteAppWidgetId(widgetId)
        widgetId = invalidId
        preferences.edit().remove("home_widget").apply()
    }

    fun view(info: AppWidgetProviderInfo) = host.createView(activity, widgetId, info)
}

@Composable
internal fun HomeWidget(widgets: HomeWidgets) {
    val id = widgets.widgetId
    val info = widgets.info() ?: return
    key(id) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(128.dp).glass()) {
            val width = maxWidth.value.toInt()
            val height = maxHeight.value.toInt()
            AndroidView(
                factory = { widgets.view(info) },
                modifier = Modifier.fillMaxSize(),
                update = { view -> view.updateAppWidgetSize(null, width, height, width, height) },
            )
        }
    }
}

@Composable
internal fun XDroidCenter(
    widgets: HomeWidgets,
    isDefaultHome: Boolean,
    selectHome: () -> Unit,
    updateAction: () -> Unit,
    updateLabel: String,
    updateBusy: Boolean,
    updateMessage: Int?,
    modifier: Modifier = Modifier,
) {
    val providers = remember(widgets) { widgets.providers() }
    val current = widgets.info()
    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.center),
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Column(Modifier.fillMaxWidth().glass().padding(12.dp)) {
                FilledTonalButton(onClick = updateAction, enabled = !updateBusy) { Text(updateLabel) }
                updateMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                if (!isDefaultHome) {
                    TextButton(onClick = selectHome) { Text(stringResource(R.string.default_home)) }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.widget_catalog),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        current?.let { info ->
            item {
                Row(Modifier.fillMaxWidth().glass().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(widgets.label(info), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = widgets::remove) { Text(stringResource(R.string.widget_remove)) }
                }
            }
        }
        if (providers.isEmpty()) {
            item { Text(stringResource(R.string.widget_empty), color = MaterialTheme.colorScheme.onSurface) }
        }
        items(providers) { provider ->
            Row(Modifier.fillMaxWidth().glass().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(widgets.label(provider), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { widgets.add(provider) }) {
                    Text(stringResource(if (current == null) R.string.widget_add else R.string.widget_change))
                }
            }
        }
    }
}
