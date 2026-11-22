package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.i18n.stringsFor
import com.example.service.NavigationForegroundService
import com.example.ui.RahYabViewModel
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.DownloadMapsScreen
import com.example.ui.screens.DriveScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WelcomeOnboardingScreen
import com.example.ui.theme.RahYabTheme
import com.example.util.CrashLogManager

object RahYabRoutes {
    const val ROUTE_DRIVE = "drive"
    const val ROUTE_MAPS = "maps"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_DIAGNOSTICS = "diagnostics"
}

class MainActivity : ComponentActivity() {

    private val viewModel: RahYabViewModel by viewModels()
    @Volatile
    private var isViewModelInitialized = false
    private var pendingExternalIntent: Intent? = null

    private val navStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NavigationForegroundService.BROADCAST_NAVIGATION_STOPPED && isViewModelInitialized) {
                viewModel.stopNavigation()
            }
        }
    }

    private fun isExternalMapIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return (action == Intent.ACTION_VIEW && intent.data != null) ||
            (action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (isExternalMapIntent(intent)) {
            pendingExternalIntent = intent
        }

        ContextCompat.registerReceiver(
            this,
            navStopReceiver,
            IntentFilter(NavigationForegroundService.BROADCAST_NAVIGATION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val initialCrashLog = CrashLogManager.readPendingCrashLog(this)

        setContent {
            var pendingCrashLog by remember { mutableStateOf(initialCrashLog) }
            if (pendingCrashLog != null) {
                // Minimal safe screen: does NOT create ViewModel, MapLibre, TTS, Compass, or PackManager
                SafeCrashRecoveryScreen(
                    crashText = pendingCrashLog!!,
                    onCopyDetails = { text ->
                        runCatching {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("RahYab Crash Log", text))
                        }
                    },
                    onContinue = {
                        CrashLogManager.clearPendingCrashLog(this@MainActivity)
                        pendingCrashLog = null
                    }
                )
            } else {
                (application as? RahYabApplication)?.ensureMapLibreInitialized()
                val vm = viewModel
                isViewModelInitialized = true
                LaunchedEffect(vm) {
                    pendingExternalIntent?.let { extIntent ->
                        pendingExternalIntent = null
                        vm.handleExternalMapIntent(extIntent)
                    }
                }
                RahYabAppRoot(viewModel = vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isExternalMapIntent(intent)) {
            if (isViewModelInitialized) {
                viewModel.handleExternalMapIntent(intent)
            } else {
                pendingExternalIntent = intent
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(navStopReceiver) }
    }
}

/**
 * Minimal standalone crash recovery screen rendered BEFORE creating ViewModel, MapLibre,
 * TextToSpeech, CompassTracker, or StatePackManager so startup crashes are always inspectable.
 */
@Composable
fun SafeCrashRecoveryScreen(
    crashText: String,
    onCopyDetails: (String) -> Unit,
    onContinue: () -> Unit
) {
    var copiedFeedback by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("safe_crash_recovery_screen"),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 40.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "The app closed unexpectedly",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "An error occurred during your previous launch. Copy the details below or tap Continue to open the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = crashText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("safe_crash_log_text")
                    )
                }
                if (copiedFeedback) {
                    Text(
                        text = "Error details copied to clipboard.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            onCopyDetails(crashText)
                            copiedFeedback = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("copy_crash_details_button")
                    ) {
                        Text("Copy error details")
                    }
                    TextButton(
                        onClick = onContinue,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("continue_after_crash_button")
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}

@Composable
fun RahYabAppRoot(viewModel: RahYabViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val gpsFix by viewModel.currentGpsFix.collectAsStateWithLifecycle()
    val navState by viewModel.navigationState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val showPersianTtsDialog by viewModel.showPersianTtsDialog.collectAsStateWithLifecycle()
    val openDriveTabEvent by viewModel.openDriveTabEvent.collectAsStateWithLifecycle()
    val mapUpdatesCount by viewModel.mapPackUpdatesAvailableCount.collectAsStateWithLifecycle()

    val isNightMode = viewModel.resolveEffectiveNightMode(settings, gpsFix)
    val strings = remember(settings.language) { stringsFor(settings.language) }
    val layoutDirection = if (settings.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val lifecycleOwner = LocalLifecycleOwner.current

    var hasFineLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCoarseLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val hasLocationPermission = hasFineLocationPermission || hasCoarseLocationPermission
    val isApproximateLocationOnly = hasCoarseLocationPermission && !hasFineLocationPermission

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = (grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasFineLocationPermission = fineGranted
        hasCoarseLocationPermission = coarseGranted
        if (fineGranted || coarseGranted) {
            viewModel.refreshLocationTracking()
        }
    }

    // Track foreground/background state for GPS priority & re-check precise location permission on resume
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setAppInForeground(true)
                Lifecycle.Event.ON_RESUME -> {
                    val fineNow = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val coarseNow = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val upgradedToFine = !hasFineLocationPermission && fineNow
                    val newlyGrantedAny = !hasLocationPermission && (fineNow || coarseNow)
                    hasFineLocationPermission = fineNow
                    hasCoarseLocationPermission = coarseNow
                    if (upgradedToFine || newlyGrantedAny) {
                        viewModel.refreshLocationTracking()
                    }
                }
                Lifecycle.Event.ON_STOP -> viewModel.setAppInForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openAppSettings: () -> Unit = {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    // Never request ACCESS_BACKGROUND_LOCATION — foreground service with type="location" handles background nav!
    val requestPermissions = {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            viewModel.startLocationTracking()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: RahYabRoutes.ROUTE_DRIVE

    var startOnMapsAfterOnboarding by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalAppStrings provides strings,
        LocalAppLanguage provides settings.language
    ) {
        RahYabTheme(
            darkTheme = isNightMode,
            isPersian = settings.language.isRtl
        ) {
            if (!settings.onboardingCompleted) {
                WelcomeOnboardingScreen(
                    settings = settings,
                    hasLocationPermission = hasLocationPermission,
                    onSelectLanguage = { viewModel.setLanguage(it) },
                    onSelectUnits = { viewModel.setDistanceUnit(it) },
                    onRequestPermissions = requestPermissions,
                    onContinueToMaps = {
                        startOnMapsAfterOnboarding = true
                        viewModel.completeOnboarding()
                    }
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useNavigationRail = maxWidth >= 700.dp

                    val navDestinations = listOf(
                        Triple(RahYabRoutes.ROUTE_DRIVE, strings.tabDrive, Icons.Filled.Navigation),
                        Triple(RahYabRoutes.ROUTE_MAPS, strings.tabMaps, Icons.Filled.Download),
                        Triple(RahYabRoutes.ROUTE_SETTINGS, strings.tabSettings, Icons.Filled.Settings)
                    )

                    val navigateTab: (String) -> Unit = { route ->
                        if (route == RahYabRoutes.ROUTE_SETTINGS && currentRoute == RahYabRoutes.ROUTE_DIAGNOSTICS) {
                            navController.popBackStack(RahYabRoutes.ROUTE_SETTINGS, inclusive = false)
                        } else {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }

                    LaunchedEffect(startOnMapsAfterOnboarding) {
                        if (startOnMapsAfterOnboarding) {
                            startOnMapsAfterOnboarding = false
                            navigateTab(RahYabRoutes.ROUTE_MAPS)
                        }
                    }

                    LaunchedEffect(openDriveTabEvent) {
                        if (openDriveTabEvent > 0L && currentRoute != RahYabRoutes.ROUTE_DRIVE) {
                            navigateTab(RahYabRoutes.ROUTE_DRIVE)
                        }
                    }

                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = {
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier.navigationBarsPadding()
                            )
                        },
                        bottomBar = {
                            if (!useNavigationRail && !navState.isNavigating) {
                                NavigationBar {
                                    navDestinations.forEach { (route, label, icon) ->
                                        val isSelected = currentRoute == route ||
                                            (route == RahYabRoutes.ROUTE_SETTINGS && currentRoute == RahYabRoutes.ROUTE_DIAGNOSTICS)
                                        NavigationBarItem(
                                            selected = isSelected,
                                            onClick = { navigateTab(route) },
                                            icon = {
                                                if (route == RahYabRoutes.ROUTE_MAPS && mapUpdatesCount > 0) {
                                                    BadgedBox(
                                                        badge = {
                                                            Badge {
                                                                Text(
                                                                    com.example.i18n.BilingualFormatter.formatNumber(
                                                                        mapUpdatesCount,
                                                                        settings.language
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    ) {
                                                        Icon(icon, contentDescription = label)
                                                    }
                                                } else {
                                                    Icon(icon, contentDescription = label)
                                                }
                                            },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("nav_tab_$route")
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (useNavigationRail && !navState.isNavigating) {
                                NavigationRail {
                                    navDestinations.forEach { (route, label, icon) ->
                                        val isSelected = currentRoute == route ||
                                            (route == RahYabRoutes.ROUTE_SETTINGS && currentRoute == RahYabRoutes.ROUTE_DIAGNOSTICS)
                                        NavigationRailItem(
                                            selected = isSelected,
                                            onClick = { navigateTab(route) },
                                            icon = {
                                                if (route == RahYabRoutes.ROUTE_MAPS && mapUpdatesCount > 0) {
                                                    BadgedBox(
                                                        badge = {
                                                            Badge {
                                                                Text(
                                                                    com.example.i18n.BilingualFormatter.formatNumber(
                                                                        mapUpdatesCount,
                                                                        settings.language
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    ) {
                                                        Icon(icon, contentDescription = label)
                                                    }
                                                } else {
                                                    Icon(icon, contentDescription = label)
                                                }
                                            },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("nav_rail_$route")
                                        )
                                    }
                                }
                            }

                            NavHost(
                                navController = navController,
                                startDestination = RahYabRoutes.ROUTE_DRIVE,
                                modifier = Modifier.weight(1f)
                            ) {
                                composable(RahYabRoutes.ROUTE_DRIVE) {
                                    DriveScreen(
                                        viewModel = viewModel,
                                        hasLocationPermission = hasLocationPermission,
                                        isApproximateLocationOnly = isApproximateLocationOnly,
                                        onRequestLocationPermission = requestPermissions,
                                        onOpenAppSettings = openAppSettings,
                                        onNavigateToMaps = { navigateTab(RahYabRoutes.ROUTE_MAPS) }
                                    )
                                }
                                composable(RahYabRoutes.ROUTE_MAPS) {
                                    DownloadMapsScreen(viewModel = viewModel)
                                }
                                composable(RahYabRoutes.ROUTE_SETTINGS) {
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onOpenDiagnostics = {
                                            navController.navigate(RahYabRoutes.ROUTE_DIAGNOSTICS) {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                }
                                composable(RahYabRoutes.ROUTE_DIAGNOSTICS) {
                                    DiagnosticsScreen(
                                        viewModel = viewModel,
                                        hasFineLocationPermission = hasFineLocationPermission,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // One-time Persian TTS Missing Notice Dialog
            if (showPersianTtsDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissPersianTtsNotice() },
                    title = { Text(strings.persianTtsMissingTitle) },
                    text = { Text(strings.persianTtsMissingBody) },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.dismissPersianTtsNotice()
                                runCatching {
                                    context.startActivity(
                                        Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        ) {
                            Text(strings.openTtsSettings)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissPersianTtsNotice() }) {
                            Text(strings.close)
                        }
                    }
                )
            }
        }
    }
}
