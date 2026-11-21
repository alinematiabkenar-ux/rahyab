package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import com.example.ui.screens.DownloadMapsScreen
import com.example.ui.screens.DriveScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.RahYabTheme

object RahYabRoutes {
    const val ROUTE_DRIVE = "drive"
    const val ROUTE_MAPS = "maps"
    const val ROUTE_SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private val viewModel: RahYabViewModel by viewModels()

    private val navStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NavigationForegroundService.BROADCAST_NAVIGATION_STOPPED) {
                viewModel.stopNavigation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContextCompat.registerReceiver(
            this,
            navStopReceiver,
            IntentFilter(NavigationForegroundService.BROADCAST_NAVIGATION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            RahYabAppRoot(viewModel = viewModel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(navStopReceiver) }
    }
}

@Composable
fun RahYabAppRoot(viewModel: RahYabViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val gpsFix by viewModel.currentGpsFix.collectAsStateWithLifecycle()
    val navState by viewModel.navigationState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    val isNightMode = viewModel.resolveEffectiveNightMode(settings, gpsFix)
    val strings = remember(settings.language) { stringsFor(settings.language) }
    val layoutDirection = if (settings.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val locGranted = (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
            (grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        hasLocationPermission = locGranted
        if (locGranted) {
            viewModel.startLocationTracking()
        }
    }

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
        } else {
            requestPermissions()
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

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalAppStrings provides strings,
        LocalAppLanguage provides settings.language
    ) {
        RahYabTheme(
            darkTheme = isNightMode,
            isPersian = settings.language.isRtl
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 700.dp

                val navDestinations = listOf(
                    Triple(RahYabRoutes.ROUTE_DRIVE, strings.tabDrive, Icons.Filled.Navigation),
                    Triple(RahYabRoutes.ROUTE_MAPS, strings.tabMaps, Icons.Filled.Download),
                    Triple(RahYabRoutes.ROUTE_SETTINGS, strings.tabSettings, Icons.Filled.Settings)
                )

                val navigateTab: (String) -> Unit = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
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
                        // Hide bottom navigation bar during active turn-by-turn driving for maximum map visibility
                        if (!useNavigationRail && !navState.isNavigating) {
                            NavigationBar {
                                navDestinations.forEach { (route, label, icon) ->
                                    NavigationBarItem(
                                        selected = currentRoute == route,
                                        onClick = { navigateTab(route) },
                                        icon = { Icon(icon, contentDescription = label) },
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
                                    NavigationRailItem(
                                        selected = currentRoute == route,
                                        onClick = { navigateTab(route) },
                                        icon = { Icon(icon, contentDescription = label) },
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
                                    onRequestLocationPermission = requestPermissions,
                                    onNavigateToMaps = { navigateTab(RahYabRoutes.ROUTE_MAPS) }
                                )
                            }
                            composable(RahYabRoutes.ROUTE_MAPS) {
                                DownloadMapsScreen(viewModel = viewModel)
                            }
                            composable(RahYabRoutes.ROUTE_SETTINGS) {
                                SettingsScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}
