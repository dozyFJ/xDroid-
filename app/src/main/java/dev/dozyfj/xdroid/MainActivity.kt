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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var refreshJob: Job? = null
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateHomeStatus()
    }
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
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
        setContent {
            MaterialTheme(colorScheme = launcherColors) {
                Launcher(
                    apps = apps,
                    pinned = pinned,
                    drawerOpen = drawerOpen,
                    isDefaultHome = isDefaultHome,
                    setDrawer = { drawerOpen = it },
                    openApp = ::openApp,
                    togglePin = ::togglePin,
                    selectHome = ::selectHome,
                )
            }
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

private fun Modifier.glass(): Modifier = clip(glassShape)
    .background(Brush.linearGradient(listOf(Color(0x385FE4DF), Color(0x1663A5CE), Color(0x30599DD0))))
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x70D8FFFF), Color(0x224CBCDF))), glassShape)

@Composable
private fun Launcher(
    apps: List<LauncherApp>,
    pinned: Set<String>,
    drawerOpen: Boolean,
    isDefaultHome: Boolean,
    setDrawer: (Boolean) -> Unit,
    openApp: (LauncherApp) -> Unit,
    togglePin: (LauncherApp) -> Unit,
    selectHome: () -> Unit,
) {
    var query by remember(drawerOpen) { mutableStateOf("") }
    var selected by remember(drawerOpen) { mutableStateOf<LauncherApp?>(null) }
    BackHandler(drawerOpen) { setDrawer(false) }

    val visibleApps = remember(apps, pinned, drawerOpen, query) {
        if (drawerOpen) apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        else apps.filter { it.key in pinned }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF264C59), launcherColors.background, Color(0xFF203A57))))
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (drawerOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search)) },
                singleLine = true,
                shape = glassShape,
                modifier = Modifier.fillMaxWidth().glass(),
            )
        } else {
            ClockPanel()
            if (!isDefaultHome) {
                OutlinedButton(onClick = selectHome, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.default_home))
                }
            }
        }

        if (visibleApps.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (drawerOpen) R.string.no_results else R.string.pin_hint),
                    textAlign = TextAlign.Center,
                    color = Color(0xFFBDCCD5),
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(76.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visibleApps, key = { it.key }) { app ->
                    AppTile(app, { openApp(app) }, { selected = app })
                }
            }
        }

        Box(Modifier.fillMaxWidth().glass().padding(8.dp)) {
            FilledTonalButton(
                onClick = { setDrawer(!drawerOpen) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (drawerOpen) R.string.home else R.string.apps))
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
private fun ClockPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now by produceState(Date()) {
        while (true) {
            val timestamp = System.currentTimeMillis()
            value = Date(timestamp)
            delay(60_000L - timestamp % 60_000L)
        }
    }

    Column(
        Modifier.fillMaxWidth().glass().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("xDroid", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
        Text(
            DateFormat.getTimeFormat(context).format(now),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 42.sp,
            lineHeight = 48.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(DateFormat.getLongDateFormat(context).format(now), color = Color(0xFFC1D8DF))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: LauncherApp, open: () -> Unit, pin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = open, onLongClick = pin)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(58.dp).glass(), contentAlignment = Alignment.Center) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(44.dp))
        }
        Text(
            app.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
