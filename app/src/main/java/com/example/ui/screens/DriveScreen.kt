package com.example.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.ui.RahYabViewModel
import com.example.ui.components.MapLibreMapContainer
import com.example.ui.components.RouteProgressBar
import com.example.ui.components.SpeedometerAndLimitCluster

@Composable
fun DriveScreen(
    viewModel: RahYabViewModel,
    hasLocationPermission: Boolean,
    isApproximateLocationOnly: Boolean,
    onRequestLocationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNavigateToMaps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val gpsFix by viewModel.currentGpsFix.collectAsStateWithLifecycle()
    val packsTick by viewModel.packsChangedTick.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val selectedPlace by viewModel.selectedPlace.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val candidateRoutes by viewModel.candidateRoutes.collectAsStateWithLifecycle()
    val selectedRouteIdx by viewModel.selectedRouteIndex.collectAsStateWithLifecycle()
    val isCalculatingRoute by viewModel.isCalculatingRoute.collectAsStateWithLifecycle()
    val routeError by viewModel.routeError.collectAsStateWithLifecycle()
    val missingStatesForRoute by viewModel.missingStatesForRoute.collectAsStateWithLifecycle()
    val selectedTravelMode by viewModel.selectedTravelMode.collectAsStateWithLifecycle()
    val modePreviewSummaries by viewModel.modePreviewSummaries.collectAsStateWithLifecycle()
    val navState by viewModel.navigationState.collectAsStateWithLifecycle()
    val activeReports by viewModel.activeUserReports.collectAsStateWithLifecycle()
    val cameraCmd by viewModel.cameraCommand.collectAsStateWithLifecycle()
    val isCameraFollowing by viewModel.isCameraFollowing.collectAsStateWithLifecycle()
    val userHeadingDegrees by viewModel.userHeadingDegrees.collectAsStateWithLifecycle()
    val isHeadingUpMode by viewModel.isHeadingUpMode.collectAsStateWithLifecycle()
    val isCompassLowAccuracy by viewModel.isCompassLowAccuracy.collectAsStateWithLifecycle()
    val simulationSpeed by viewModel.simulationSpeedMultiplier.collectAsStateWithLifecycle()
    val visibleCameras by viewModel.visibleCameras.collectAsStateWithLifecycle()
    val visibleTrafficLights by viewModel.visibleTrafficLights.collectAsStateWithLifecycle()
    val visibleSpeedBumps by viewModel.visibleSpeedBumps.collectAsStateWithLifecycle()
    val visibleStopSigns by viewModel.visibleStopSigns.collectAsStateWithLifecycle()
    val aheadTrafficLightMeters by viewModel.aheadTrafficLightMeters.collectAsStateWithLifecycle()
    val freeDriveAheadAlert by viewModel.freeDriveAheadAlert.collectAsStateWithLifecycle()
    val externalMatchChoices by viewModel.externalLinkMatchChoices.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val onlineSources by viewModel.onlineSourcesSummary.collectAsStateWithLifecycle()
    val weatherAlerts by viewModel.activeWeatherAlerts.collectAsStateWithLifecycle()
    val parkedCar by viewModel.parkedCar.collectAsStateWithLifecycle()
    val isSimulationActive = navState.isSimulatingDrive || settings.simulateDriveEnabled

    val isNightMode = viewModel.resolveEffectiveNightMode(settings, gpsFix)
    val installedPmtiles = remember(packsTick) { viewModel.getInstalledPmtilesFiles() }
    val installedPlacesCount = remember(packsTick) { viewModel.getInstalledPlacesDbCount() }

    var isSearchExpanded by remember { mutableStateOf(false) }
    var showReportSheet by remember { mutableStateOf(false) }
    var showAttributionDialog by remember { mutableStateOf(false) }
    var showCloudStatusDialog by remember { mutableStateOf(false) }
    var mapCameraBearingDegrees by remember { mutableFloatStateOf(0f) }
    var compassCalibDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(isCompassLowAccuracy) {
        if (isCompassLowAccuracy) {
            compassCalibDismissed = false
        }
    }

    // Keep screen awake strictly while actively navigating
    DisposableEffect(navState.isNavigating, context) {
        val activity = context as? Activity
        if (navState.isNavigating) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Android 15 Predictive Back handling
    BackHandler(enabled = isSearchExpanded || selectedPlace != null) {
        when {
            isSearchExpanded -> isSearchExpanded = false
            selectedPlace != null && !navState.isNavigating -> viewModel.clearSelectedPlace()
        }
    }

    val bottomRailPadding = if (navState.isNavigating) 118.dp else if (selectedPlace != null) 275.dp else 44.dp

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Full-bleed MapLibre Native Vector Map
        MapLibreMapContainer(
            installedPmtiles = installedPmtiles,
            isNightMode = isNightMode,
            language = language,
            useDevOnlineBasemap = settings.devOnlineOregonMode,
            packsChangedTick = packsTick,
            gpsFix = if (navState.isSimulatingDrive) null else gpsFix,
            snappedNavLocation = if (navState.isNavigating) navState.snappedLocation else null,
            userHeadingDegrees = userHeadingDegrees,
            isHeadingUpMode = isHeadingUpMode,
            isCameraFollowing = isCameraFollowing,
            candidateRoutes = if (!navState.isNavigating) candidateRoutes else emptyList(),
            selectedRouteIndex = selectedRouteIdx,
            activeNavRoute = if (navState.isNavigating) navState.route else null,
            snappedShapeIndex = if (navState.isNavigating) navState.snappedShapeIndex else 0,
            selectedPlace = selectedPlace,
            userReports = activeReports,
            cameras = visibleCameras,
            trafficLights = if (settings.showTrafficLights) visibleTrafficLights else emptyList(),
            speedBumps = visibleSpeedBumps,
            stopSigns = if (settings.showStopSignsOnMap) visibleStopSigns else emptyList(),
            parkedCarLocation = parkedCar?.let { com.example.navigation.LatLon(it.lat, it.lon) },
            maneuverArrow = if (navState.isNavigating && !navState.hasArrived) {
                remember(navState.route, navState.currentManeuverIndex, navState.snappedShapeIndex, (navState.distanceToNextManeuverMeters / 10.0).toInt(), navState.effectiveTravelMode) {
                    com.example.map.ManeuverArrowGeometry.build(navState.route, navState.currentManeuverIndex, navState.snappedShapeIndex, navState.distanceToNextManeuverMeters, navState.effectiveTravelMode)
                }
            } else null,
            show3DBuildings = settings.show3DBuildings,
            positionMarkerStyle = settings.positionMarkerStyle,
            isGpsPoor = !navState.isSimulatingDrive && (navState.isGpsSignalLost || (gpsFix?.accuracyMeters ?: 0f) > 35f || gpsFix == null),
            cameraCommand = cameraCmd,
            onMapLongClick = { lat, lon -> viewModel.dropPinOnMap(lat, lon) },
            onRouteTapped = { idx -> viewModel.selectCandidateRoute(idx) },
            onUserMovedMap = { viewModel.pauseCameraFollow() },
            onMapBearingChanged = { bearing -> mapCameraBearingDegrees = bearing },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Overlay: Active Turn-by-Turn HUD OR Floating Search & Status Bar
        DriveTopOverlay(
            navState = navState,
            settings = settings,
            isNightMode = isNightMode,
            gpsFix = gpsFix,
            hasLocationPermission = hasLocationPermission,
            isApproximateLocationOnly = isApproximateLocationOnly,
            isCompassLowAccuracy = isCompassLowAccuracy,
            compassCalibDismissed = compassCalibDismissed,
            aheadTrafficLightMeters = aheadTrafficLightMeters,
            freeDriveAheadAlert = freeDriveAheadAlert,
            weatherAlerts = weatherAlerts,
            installedPmtilesCount = installedPmtiles.size,
            installedPlacesCount = installedPlacesCount,
            searchQuery = searchQuery,
            searchResults = searchResults,
            savedPlaces = savedPlaces,
            isSearching = isSearching,
            isSearchExpanded = isSearchExpanded,
            keyboard = keyboard,
            onSetSearchExpanded = { isSearchExpanded = it },
            onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
            onExecuteSearchNow = { viewModel.executeSearchNow(it) },
            onSelectPlace = { viewModel.selectPlace(it) },
            onDismissJunctionPreview = { viewModel.dismissJunctionPreview() },
            onDismissCompassCalibration = { compassCalibDismissed = true },
            onConfirmReport = { viewModel.confirmUserReport(it) },
            onDismissReport = { viewModel.dismissUserReport(it) },
            onDismissWeatherAlert = { viewModel.dismissWeatherAlert(it) },
            onRequestLocationPermission = onRequestLocationPermission,
            onOpenAppSettings = onOpenAppSettings,
            onNavigateToMaps = onNavigateToMaps,
            onEnableDevOregon = { viewModel.setDevOnlineOregonMode(true) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )

        // 3. Left / Start side: Speed Limit & Live Speedometer Cluster (hidden in walking mode)
        if (!navState.isWalkingMode && (navState.isNavigating || gpsFix != null)) {
            SpeedometerAndLimitCluster(
                currentSpeedMps = navState.currentSpeedMps.takeIf { navState.isNavigating } ?: (gpsFix?.speedMps ?: 0f),
                speedLimitMph = navState.currentSpeedLimitMph,
                isOverspeed = navState.isOverspeed,
                unit = settings.distanceUnit,
                overspeedToleranceMph = settings.speedWarningThresholdMph,
                speedLimitSource = navState.speedLimitSource,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = bottomRailPadding)
            )
        }

        val routeProgressEvents = if (navState.isNavigating && !navState.hasArrived && !navState.isWalkingMode) {
            remember(navState.route, navState.snappedShapeIndex, (navState.remainingDistanceMeters / 25.0).toInt(), navState.effectiveTravelMode, visibleCameras) {
                com.example.navigation.RouteProgressBarPlanner.buildEvents(
                    navState.route, navState.snappedShapeIndex, navState.remainingDistanceMeters, navState.effectiveTravelMode, visibleCameras
                )
            }
        } else emptyList()
        val showRouteProgressBar = routeProgressEvents.isNotEmpty()

        // 4. Right / End side: Driving Control Rail (shifted left of RouteProgressBar when active)
        DriveControlRail(
            navState = navState,
            settings = settings,
            networkState = networkState,
            isSimulationActive = isSimulationActive,
            simulationSpeed = simulationSpeed,
            mapCameraBearingDegrees = mapCameraBearingDegrees,
            isHeadingUpMode = isHeadingUpMode,
            isCameraFollowing = isCameraFollowing,
            onTriggerSimulatedWrongTurn = { viewModel.triggerSimulatedWrongTurn() },
            onOpenCloudStatus = { showCloudStatusDialog = true },
            onToggleSimulation = { viewModel.toggleSimulationFromMap() },
            onCycleSimulationSpeed = { viewModel.cycleSimulationSpeed() },
            onResetSimulationSpeed = { viewModel.resetSimulationSpeedTo1x() },
            onRepeatVoice = { viewModel.repeatLastVoiceInstruction() },
            onToggleVoice = { viewModel.setVoiceEnabled(!settings.voiceGuidanceEnabled) },
            onToggle3DView = { viewModel.toggle3DView() },
            onToggleCompassMode = { viewModel.toggleCompassHeadingMode() },
            onRecenter = { viewModel.recenterCamera() },
            onOpenReportSheet = { showReportSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = if (showRouteProgressBar) 44.dp else 12.dp, bottom = bottomRailPadding)
        )

        // 4b. Sygic-style Route Progress Bar on the right edge (DRIVE & BIKE only)
        if (showRouteProgressBar) {
            RouteProgressBar(
                events = routeProgressEvents,
                unit = settings.distanceUnit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 6.dp, top = if (navState.showJunctionPreview) 316.dp else 172.dp, bottom = 124.dp)
            )
        }

        // 5. Required OpenStreetMap + Overture Maps attribution badge & Manual "I parked here" chip
        val parkedCarDistanceMeters = remember(parkedCar, gpsFix) {
            val spot = parkedCar ?: return@remember null
            val ref = gpsFix?.let { com.example.navigation.LatLon(it.lat, it.lon) }
                ?: viewModel.getEffectiveReferenceLocation()
            com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(
                ref.lat,
                ref.lon,
                spot.lat,
                spot.lon
            )
        }
        val showParkedCarCard = !navState.isNavigating &&
            selectedPlace == null &&
            parkedCar != null &&
            (parkedCarDistanceMeters ?: 0.0) > 50.0

        if (!navState.isNavigating && selectedPlace == null && !showParkedCarCard) {
            ManualParkHereChip(
                onClick = { viewModel.parkCarHereManually() },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 42.dp)
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 10.dp)
                    .clickable { showAttributionDialog = true }
                    .testTag("map_attribution_badge")
            ) {
                Text(
                    text = strings.mapAttributionShort,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 6. Bottom Cockpit Panel: Active Navigation ETA Bar OR Selected Place / Route Preview Card
        if (navState.isNavigating) {
            ActiveNavigationBottomBar(
                destinationName = navState.destinationName,
                destinationAddress = navState.destinationAddress,
                remainingDistanceMeters = navState.remainingDistanceMeters,
                remainingTimeSeconds = navState.remainingTimeSeconds,
                hasArrived = navState.hasArrived,
                unit = settings.distanceUnit,
                travelMode = navState.effectiveTravelMode,
                onEndNavigation = { viewModel.stopNavigation() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        } else if (selectedPlace != null) {
            PlaceAndRoutePreviewCard(
                place = selectedPlace!!,
                routes = candidateRoutes,
                selectedRouteIndex = selectedRouteIdx,
                isCalculatingRoute = isCalculatingRoute,
                routeError = routeError,
                missingStates = missingStatesForRoute,
                avoidTolls = settings.avoidTolls,
                avoidHighways = settings.avoidHighways,
                avoidFerries = settings.avoidFerries,
                unit = settings.distanceUnit,
                onClose = { viewModel.clearSelectedPlace() },
                onCalculateRoutes = { viewModel.calculateRoutesToSelectedPlace() },
                onSelectRoute = { viewModel.selectCandidateRoute(it) },
                onStartNavigation = { viewModel.startNavigation() },
                onToggleAvoidTolls = { viewModel.setAvoidTolls(it) },
                onToggleAvoidHighways = { viewModel.setAvoidHighways(it) },
                onToggleAvoidFerries = { viewModel.setAvoidFerries(it) },
                onSaveAs = { type -> viewModel.savePlaceAs(selectedPlace!!, type) },
                onDownloadMissingState = { stateCode ->
                    viewModel.requestDownloadStatePack(stateCode)
                    onNavigateToMaps()
                },
                selectedTravelMode = selectedTravelMode,
                modePreviewSummaries = modePreviewSummaries,
                onSelectTravelMode = { viewModel.selectTravelMode(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        } else if (showParkedCarCard && parkedCar != null && parkedCarDistanceMeters != null) {
            ParkedCarCard(
                spot = parkedCar!!,
                distanceMeters = parkedCarDistanceMeters,
                unit = settings.distanceUnit,
                onWalkToCar = { viewModel.startWalkToCar() },
                onUpdateNote = { viewModel.updateParkedCarNote(it) },
                onClear = { viewModel.clearParkedCar() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        }
    }

    DriveModalsAndDialogs(
        showReportSheet = showReportSheet,
        onDismissReportSheet = { showReportSheet = false },
        onSubmitReport = { viewModel.submitUserReport(it) },
        externalMatchChoices = externalMatchChoices,
        onSelectExternalMatch = { viewModel.selectExternalMatchChoice(it) },
        onDismissExternalMatches = { viewModel.dismissExternalMatchChoices() },
        showAttributionDialog = showAttributionDialog,
        onDismissAttributionDialog = { showAttributionDialog = false },
        showCloudStatusDialog = showCloudStatusDialog,
        onDismissCloudStatusDialog = { showCloudStatusDialog = false },
        settings = settings,
        networkState = networkState,
        onlineSources = onlineSources
    )
}
