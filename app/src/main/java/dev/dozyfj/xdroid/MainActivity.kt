package dev.dozyfj.xdroid

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Date

private data class LauncherApp(
    val component: ComponentName,
    val label: String,
    val icon: ImageBitmap,
) {
    val key: String get() = component.flattenToString()
}

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private var apps by mutableStateOf<List<LauncherApp>>(emptyList())
    private var pinned by mutableStateOf<Set<String>>(emptySet())
    private var drawerOpen by mutableStateOf(false)
    private var isDefaultHome by mutableStateOf(false)
    private lateinit var widgets: HomeWidgets

    private val updates by lazy { LauncherUpdates(this) }
    private var availableUpdate by mutableStateOf<UpdateRelease?>(null)
    private var preparedUpdate by mutableStateOf<android.net.Uri?>(null)
    private var updateMessage by mutableStateOf<Int?>(null)
    private var updateJob: Job? = null

    private var refreshJob: Job? = null
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateHomeStatus()
    }
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgets = HomeWidgets(this, savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        hideStatusBar()
        pinned = preferences.getStringSet("pinned", emptySet()).orEmpty().toSet()
        ContextCompat.registerReceiver(
            this,
            packageChanges,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        checkUpdates(showResult = false)
        setContent {
            MaterialTheme(colorScheme = launcherColors) {
                Launcher(
                    widgets = widgets,
                    apps = apps,
                    pinned = pinned,
                    drawerOpen = drawerOpen,
                    isDefaultHome = isDefaultHome,
                    setDrawer = { drawerOpen = it },
                    openApp = ::openApp,
                    togglePin = ::togglePin,
                    selectHome = ::selectHome,
                    updateAction = ::updateAction,
                    updateLabel = when {
                        preparedUpdate != null -> stringResource(R.string.update_install)
                        else -> availableUpdate?.let { stringResource(R.string.update_available, it.versionName) }
                            ?: stringResource(R.string.updates)
                    },
                    updateBusy = updateMessage == R.string.update_checking || updateMessage == R.string.update_downloading,
                    updateMessage = updateMessage,

                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        widgets.start()
    }

    override fun onStop() {
        widgets.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        widgets.save(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("AppWidgetHost requires a request code for provider configuration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        widgets.configurationResult(requestCode, resultCode)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        drawerOpen = false
    }

    override fun onResume() {
        super.onResume()
        updateHomeStatus()
        refreshApps()
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        super.onDestroy()
    }

    private fun checkUpdates(showResult: Boolean) {
        if (updateJob?.isActive == true) return
        if (showResult) updateMessage = R.string.update_checking
        updateJob = lifecycleScope.launch {
            try {
                availableUpdate = updates.check()
                updateMessage = if (showResult && availableUpdate == null) R.string.update_current else null
            } catch (_: java.io.IOException) {
                updateMessage = if (showResult) R.string.update_failed else null
            }
        }
    }

    private fun updateAction() {
        preparedUpdate?.let {
            installUpdate(it)
            return
        }
        val release = availableUpdate
        if (release == null) {
            checkUpdates(showResult = true)
            return
        }
        if (updateJob?.isActive == true) return
        updateMessage = R.string.update_downloading
        updateJob = lifecycleScope.launch {
            try {
                val uri = updates.download(release)
                preparedUpdate = uri
                installUpdate(uri)
            } catch (_: java.io.IOException) {
                updateMessage = R.string.update_failed
            }
        }
    }

    private fun installUpdate(uri: android.net.Uri) {
        updateMessage = null
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                updateMessage = R.string.update_allow
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            updateMessage = R.string.update_failed
        } catch (_: SecurityException) {
            updateMessage = R.string.update_failed
        }
    }

    private fun refreshApps() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) {
                val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val collator = Collator.getInstance()
                packageManager.queryIntentActivities(query, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map { item ->
                        LauncherApp(
                            ComponentName(item.activityInfo.packageName, item.activityInfo.name),
                            item.loadLabel(packageManager).toString(),
                            item.loadIcon(packageManager).toBitmap(128, 128).asImageBitmap(),
                        )
                    }
                    .distinctBy { it.key }
                    .sortedWith { first, second -> collator.compare(first.label, second.label) }
            }
        }
    }

    private fun togglePin(app: LauncherApp) {
        pinned = if (app.key in pinned) pinned - app.key else pinned + app.key
        preferences.edit().putStringSet("pinned", pinned).apply()
    }

    private fun openApp(app: LauncherApp) {
        try {
            startActivity(Intent.makeMainActivity(app.component))
            drawerOpen = false
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
            refreshApps()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHomeStatus() {
        if (Build.VERSION.SDK_INT >= 29) {
            isDefaultHome = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
            return
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        isDefaultHome = packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    }

    private fun selectHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }
}

private val launcherColors = darkColorScheme(
    primary = Color(0xFF8BF3F2),
    onPrimary = Color(0xFF04181A),
    secondaryContainer = Color(0xFF20434A),
    onSecondaryContainer = Color(0xFFD4F7F5),
    background = Color(0xFF0E1114),
    surface = Color(0xFF161A1F),
    onSurface = Color(0xFFE8ECF0),
    outline = Color(0xFF8095A2),
)

private val glassShape = RoundedCornerShape(24.dp)

internal fun Modifier.glass(): Modifier = clip(glassShape)
    .background(Brush.linearGradient(listOf(Color(0x385FE4DF), Color(0x1663A5CE), Color(0x30599DD0))))
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x70D8FFFF), Color(0x224CBCDF))), glassShape)

@Composable
private fun Launcher(
    widgets: HomeWidgets,
    apps: List<LauncherApp>,
    pinned: Set<String>,
    drawerOpen: Boolean,
    isDefaultHome: Boolean,
    setDrawer: (Boolean) -> Unit,
    openApp: (LauncherApp) -> Unit,
    togglePin: (LauncherApp) -> Unit,
    selectHome: () -> Unit,
    updateAction: () -> Unit,
    updateLabel: String,
    updateBusy: Boolean,
    updateMessage: Int?,
) {
    var query by remember(drawerOpen) { mutableStateOf("") }
    var selected by remember(drawerOpen) { mutableStateOf<LauncherApp?>(null) }
    var centerOpen by remember(drawerOpen) { mutableStateOf(false) }
    val pinnedApps = remember(apps, pinned) { apps.filter { it.key in pinned } }
    val dockApps = pinnedApps.take(3)
    val pages = remember(pinnedApps) { pinnedApps.drop(3).chunked(12) }
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    val matches = remember(apps, query) { apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    BackHandler(drawerOpen || centerOpen) {
        if (centerOpen) centerOpen = false else setDrawer(false)
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF264C59), launcherColors.background, Color(0xFF203A57))))
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            centerOpen -> XDroidCenter(
                widgets = widgets,
                isDefaultHome = isDefaultHome,
                selectHome = selectHome,
                updateAction = updateAction,
                updateLabel = updateLabel,
                updateBusy = updateBusy,
                updateMessage = updateMessage,
                modifier = Modifier.weight(1f),
            )
            drawerOpen -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    shape = glassShape,
                    modifier = Modifier.fillMaxWidth().glass(),
                )
                AppGrid(
                    matches, openApp, { selected = it }, Modifier.weight(1f),
                    emptyMessage = R.string.no_results,
                )
            }
            else -> {
                ClockPanel()
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                    AppGrid(
                        pages.getOrNull(page).orEmpty(), openApp, { selected = it }, Modifier.fillMaxSize(),
                        emptyMessage = R.string.pin_hint.takeIf { pinnedApps.isEmpty() },
                    )
                }
                HomeWidget(widgets)
            }
        }

        LauncherStatusBar()
        Column(
            Modifier.fillMaxWidth().glass().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!drawerOpen && !centerOpen) {
                val current = pager.currentPage.coerceIn(0, pager.pageCount - 1) + 1
                val description = stringResource(R.string.page_description, current, pager.pageCount)
                Text(
                    stringResource(R.string.page_count, current, pager.pageCount),
                    modifier = Modifier.semantics { contentDescription = description },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!drawerOpen && !centerOpen) {
                    dockApps.forEach { app ->
                        AppTile(app, { openApp(app) }, { selected = app }, Modifier.weight(1f), compact = true)
                    }
                }
                Spacer(Modifier.weight(1f))
                DockButton(
                    label = stringResource(if (drawerOpen) R.string.home else R.string.apps),
                    symbol = if (drawerOpen) DockSymbol.Home else DockSymbol.Apps,
                    onClick = {
                        setDrawer(!drawerOpen)
                        centerOpen = false
                    },
                )
                DockButton(
                    label = stringResource(R.string.center),
                    symbol = DockSymbol.Settings,
                    onClick = { centerOpen = !centerOpen },
                )
            }
        }
    }

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(app.label) },
            confirmButton = {
                TextButton(onClick = {
                    togglePin(app)
                    selected = null
                }) {
                    Text(stringResource(if (app.key in pinned) R.string.unpin else R.string.pin))
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun AppGrid(
    apps: List<LauncherApp>,
    openApp: (LauncherApp) -> Unit,
    selectApp: (LauncherApp) -> Unit,
    modifier: Modifier,
    emptyMessage: Int?,
) {
    if (apps.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            emptyMessage?.let {
                Text(
                    stringResource(it),
                    textAlign = TextAlign.Center,
                    color = Color(0xFFBDCCD5),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(apps, key = { it.key }) { app ->
                AppTile(app, { openApp(app) }, { selectApp(app) })
            }
        }
    }
}

private enum class DockSymbol { Apps, Home, Settings }

@Composable
private fun DockButton(label: String, symbol: DockSymbol, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        Canvas(Modifier.size(24.dp)) {
            val unit = size.width / 24
            when (symbol) {
                DockSymbol.Apps -> repeat(9) { index ->
                    drawCircle(color, 1.65f * unit, Offset((5 + index % 3 * 7) * unit, (5 + index / 3 * 7) * unit))
                }
                DockSymbol.Home -> {
                    drawLine(color, Offset(3 * unit, 11 * unit), Offset(12 * unit, 4 * unit), 2 * unit)
                    drawLine(color, Offset(12 * unit, 4 * unit), Offset(21 * unit, 11 * unit), 2 * unit)
                    drawRect(color, Offset(6 * unit, 11 * unit), androidx.compose.ui.geometry.Size(12 * unit, 9 * unit), style = Stroke(2 * unit))
                }
                DockSymbol.Settings -> {
                    drawCircle(color, 7 * unit, style = Stroke(3 * unit))
                    drawCircle(color, 2 * unit)
                    repeat(8) { spoke ->
                        rotate(spoke * 45f) {
                            drawLine(color, Offset(center.x, 2 * unit), Offset(center.x, 6 * unit), 3 * unit)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxWidth().glass().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("xDroid", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
        Text(DateFormat.getLongDateFormat(context).format(Date()), color = Color(0xFFC1D8DF))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: LauncherApp,
    open: () -> Unit,
    pin: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = open, onLongClick = pin)
            .padding(vertical = if (compact) 4.dp else 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(if (compact) 44.dp else 58.dp).glass(), contentAlignment = Alignment.Center) {
            Image(
                app.icon,
                contentDescription = if (compact) app.label else null,
                modifier = Modifier.size(if (compact) 32.dp else 44.dp),
            )
        }
        if (!compact) Text(
            app.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
