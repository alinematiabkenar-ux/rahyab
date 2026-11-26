package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.local.ReportType
import com.example.data.preferences.UserSettings
import com.example.i18n.BilingualFormatter
import com.example.i18n.stringsFor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.LatLon
import com.example.navigation.LocationTracker
import com.example.navigation.MissingStatesException
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteOptions
import com.example.navigation.RoutePreference
import com.example.navigation.TravelMode
import com.example.navigation.ValhallaResponseParser
import com.example.service.NavigationForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun formatModeNotificationTitle(
    mode: TravelMode,
    primaryText: String,
    etaText: String,
    language: com.example.i18n.AppLanguage
): String {
    val s = stringsFor(language)
    val modeLabel = when (mode) {
        TravelMode.DRIVE -> s.travelModeDrive
        TravelMode.WALK -> s.travelModeWalk
        TravelMode.BIKE -> s.travelModeBike
    }
    return "$modeLabel • $primaryText • $etaText"
}

internal fun RahYabViewModel.buildCurrentRouteOptionsImpl(
    s: UserSettings,
    travelMode: com.example.navigation.TravelMode = _selectedTravelMode.value
): RouteOptions {
    val now = System.currentTimeMillis()
    // Automatically avoid active user-reported Road Closures in Valhalla routing
    val closedLocations = activeUserReports.value
        .filter { it.type == ReportType.ROAD_CLOSURE.name && it.expiresAt > now }
        .map { LatLon(it.lat, it.lon) }

    return RouteOptions(
        avoidTolls = s.avoidTolls,
        avoidHighways = s.avoidHighways,
        avoidFerries = s.avoidFerries,
        preferShortest = (s.routePreference == RoutePreference.SHORTEST),
        avoidLocations = closedLocations,
        travelMode = travelMode,
        walkingSpeedKmh = s.walkingSpeedPreset.speedKmh,
        cyclingSpeedKmh = s.cyclingSpeedPreset.speedKmh,
        avoidHillsWhenCycling = s.avoidHillsWhenCycling
    )
}

internal fun RahYabViewModel.calculateRoutesToSelectedPlaceImpl() {
    calculateAllTravelModePreviewsImpl()
}

internal fun RahYabViewModel.selectCandidateRouteImpl(index: Int) {
    val routes = _candidateRoutes.value
    if (routes.isEmpty()) return
    val safeIndex = index.coerceIn(0, routes.lastIndex)
    if (_selectedRouteIndex.value != safeIndex) {
        _selectedRouteIndex.value = safeIndex
        // If actively navigating and switching to an alternative route, keep the same
        // running foreground service and only refresh the route & notification.
        if (_navigationState.value.isNavigating) {
            switchActiveNavigationRouteImpl(routes[safeIndex])
        }
    }
}

private fun RahYabViewModel.switchActiveNavigationRouteImpl(newRoute: OfflineRoute) {
    val dest = _selectedPlace.value ?: return
    val s = settings.value
    navController.reset()
    hpmsFallbackCache.reset()
    resetTurnFocusImpl()
    invalidatePendingCameraCommandsImpl()
    speedLimitDropTracker.reset()
    voiceManager.resetDeduplication()
    val initialManeuver = newRoute.maneuvers.firstOrNull()
    val startLoc = _navigationState.value.snappedLocation
        ?: newRoute.shape.firstOrNull()
        ?: getEffectiveReferenceLocation()

    val mode = newRoute.travelMode
    val isDriveMode = mode == TravelMode.DRIVE
    _navigationState.value = _navigationState.value.copy(
        route = newRoute,
        travelMode = mode,
        isWalkingMode = (mode == TravelMode.WALK),
        currentManeuverIndex = 0,
        snappedShapeIndex = 0,
        isOffRoute = false,
        isRerouting = false,
        distanceToNextManeuverMeters = initialManeuver?.lengthMeters ?: newRoute.distanceMeters,
        timeToNextManeuverSeconds = initialManeuver?.timeSeconds ?: newRoute.durationSeconds,
        remainingDistanceMeters = newRoute.distanceMeters,
        remainingTimeSeconds = newRoute.durationSeconds,
        snappedLocation = startLoc,
        currentSpeedLimitMph = if (isDriveMode) initialManeuver?.speedLimitMph else null,
        showLaneStrip = isDriveMode && initialManeuver?.lanes?.isNotEmpty() == true &&
            (initialManeuver.lengthMeters <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit)),
        showJunctionPreview = false,
        lanePulseManeuverIndex = -1,
        dismissedJunctionManeuverIndex = -1
    )

    val firstInstruction = initialManeuver?.streetNames?.firstOrNull()?.ifBlank { initialManeuver.instructionEn }
        ?: initialManeuver?.instructionEn
        ?: dest.name
    val etaText = BilingualFormatter.formatDuration(newRoute.durationSeconds, s.language)
    NavigationForegroundService.updateNotification(
        context = app,
        title = formatModeNotificationTitle(mode, dest.name, etaText, s.language),
        body = firstInstruction,
        force = true
    )
    loadFraCrossingsForRouteImpl(newRoute, cachedDbHazards)
}

internal fun RahYabViewModel.startNavigationImpl(forceSimulate: Boolean? = null) {
    val routes = _candidateRoutes.value
    if (routes.isEmpty()) return
    val safeIdx = _selectedRouteIndex.value.coerceIn(0, routes.lastIndex)
    val rawRoute = routes[safeIdx]
    val dest = _selectedPlace.value ?: return
    val s = settings.value
    val shouldSimulate = forceSimulate ?: s.simulateDriveEnabled
    val route = if (shouldSimulate && rawRoute.travelMode == TravelMode.DRIVE) {
        enrichHighwayExitLanesForSimulation(rawRoute)
    } else {
        rawRoute
    }
    val wasAlreadyNavigating = _navigationState.value.isNavigating

    stopInternalNavJobsImpl()
    navController.reset()
    hpmsFallbackCache.reset()
    speedBumpEvaluator.reset()
    trafficLightVoiceEvaluator.reset()
    laneChangeCueTracker.reset()
    resetTurnFocusImpl()
    invalidatePendingCameraCommandsImpl()
    speedLimitDropTracker.reset()
    voiceManager.resetDeduplication()
    locationTracker.setNavigationMode(true)
    compassTracker.setNavigationMode(true)
    _isCameraFollowing.value = true
    lastGpsReceivedMs = System.currentTimeMillis()
    simulatedWrongTurnTicksRemaining = 0
    if (!shouldSimulate) {
        _simulationSpeedMultiplier.value = 1
    }

    val mode = route.travelMode
    val isDriveMode = mode == TravelMode.DRIVE
    if (mode == TravelMode.BIKE) {
        _isHeadingUpMode.value = true
    }
    val initialManeuver = route.maneuvers.firstOrNull()
    val initialDist = initialManeuver?.lengthMeters ?: route.distanceMeters
    val startLoc = route.shape.firstOrNull() ?: getEffectiveReferenceLocation()
    val initialBearing = if (route.shape.size >= 2) {
        ValhallaResponseParser.bearingDegrees(route.shape[0], route.shape[1]).toFloat()
    } else {
        _userHeadingDegrees.value
    }
    _navigationState.value = ActiveNavigationState(
        isNavigating = true,
        isWalkingMode = (mode == TravelMode.WALK),
        travelMode = mode,
        route = route,
        destinationName = dest.name,
        destinationAddress = dest.formattedAddress,
        currentManeuverIndex = 0,
        distanceToNextManeuverMeters = initialDist,
        timeToNextManeuverSeconds = initialManeuver?.timeSeconds ?: route.durationSeconds,
        remainingDistanceMeters = route.distanceMeters,
        remainingTimeSeconds = route.durationSeconds,
        snappedLocation = startLoc,
        snappedBearing = initialBearing,
        currentSpeedLimitMph = if (isDriveMode) (route.speedLimitsByShapeIndex[0] ?: initialManeuver?.speedLimitMph) else null,
        isSimulatingDrive = shouldSimulate,
        showLaneStrip = isDriveMode && initialManeuver?.lanes?.isNotEmpty() == true &&
            initialDist <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit)
    )

    _freeDriveAheadAlert.value = null

    // Pre-fetch road hazards (cameras, railroad crossings, school zones, stop signs, traffic signals) along route
    viewModelScope.launch {
        if (route.shape.isNotEmpty()) {
            val minLat = route.shape.minOf { it.lat } - 0.05
            val maxLat = route.shape.maxOf { it.lat } + 0.05
            val minLon = route.shape.minOf { it.lon } - 0.05
            val maxLon = route.shape.maxOf { it.lon } + 0.05
            val hazards = offlineSearch.queryRoadHazardsInBounds(minLat, maxLat, minLon, maxLon)
            cachedDbHazards = hazards
            val routeCams = hazards.filter {
                it.type.equals("speed_camera", ignoreCase = true) ||
                    it.type.equals("red_light_camera", ignoreCase = true)
            }
            if (routeCams.isNotEmpty()) {
                _visibleCameras.value = mergeUniqueCamerasImpl(_visibleCameras.value, routeCams)
            }
            loadFraCrossingsForRouteImpl(route, hazards)
        }
    }

    // Start foreground service ONLY once when navigation begins; if already running, only update notification
    val firstInstruction = initialManeuver?.instructionEn ?: dest.name
    val etaText = BilingualFormatter.formatDuration(route.durationSeconds, s.language)
    val notifTitle = formatModeNotificationTitle(mode, dest.name, etaText, s.language)
    if (wasAlreadyNavigating) {
        NavigationForegroundService.updateNotification(
            context = app,
            title = notifTitle,
            body = firstInstruction,
            force = true
        )
    } else {
        NavigationForegroundService.startOrUpdate(
            context = app,
            title = notifTitle,
            body = firstInstruction
        )
    }

    // Announce clear departure instruction with compass direction, starting street, and next turn
    val spoken = voiceManager.speakStartInstruction(route, s.language)
    if (spoken.isNotBlank()) {
        navController.markExternalPromptSpoken()
        _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
    }

    // Switch camera to mode-appropriate view
    val initialZoom = when (mode) {
        TravelMode.WALK -> 17.0
        TravelMode.BIKE -> 16.0
        TravelMode.DRIVE -> 16.8
    }
    val initialTilt = if (isDriveMode && s.is3DView) 55.0 else 0.0
    _cameraCommand.value = CameraTargetCommand(
        lat = startLoc.lat,
        lon = startLoc.lon,
        zoom = initialZoom,
        tilt = initialTilt,
        bearing = if (mode == TravelMode.BIKE || _isHeadingUpMode.value) initialBearing.toDouble() else 0.0,
        routeId = route.id,
        maneuverIndex = 0,
        generation = _cameraGeneration.value
    )

    if (shouldSimulate) {
        seedSimulationSpeedBumpsImpl(route.shape)
        startSimulatedDriveLoopImpl()
    } else {
        clearSimulationSpeedBumpsImpl()
        startGpsLossWatchdogImpl()
    }
}

internal fun RahYabViewModel.startGpsLossWatchdogImpl() {
    gpsWatchdogJob?.cancel()
    gpsWatchdogJob = viewModelScope.launch {
        while (_navigationState.value.isNavigating && !_navigationState.value.hasArrived) {
            delay(1500L)
            val elapsed = System.currentTimeMillis() - lastGpsReceivedMs
            if (elapsed >= 4500L && !_navigationState.value.isSimulatingDrive) {
                val deadReckoned = navController.advanceDeadReckoning(_navigationState.value, 1.5)
                _navigationState.value = deadReckoned
                if (_isCameraFollowing.value) {
                    deadReckoned.snappedLocation?.let { loc ->
                        val heading = if (_isHeadingUpMode.value) deadReckoned.snappedBearing else 0f
                        computeFocusOrFollowCameraCommandImpl(
                            state = deadReckoned,
                            targetLoc = loc,
                            speedMps = deadReckoned.currentSpeedMps,
                            heading = heading,
                            simMultiplier = 1,
                            cameraAnimMs = 450,
                            forceRecenterNow = false
                        )?.let { cmd -> _cameraCommand.value = cmd }
                    }
                }
            }
        }
    }
}

internal fun RahYabViewModel.dismissJunctionPreviewImpl() {
    val nav = _navigationState.value
    val targetIdx = nav.activeJunctionManeuverIndex.takeIf { it >= 0 } ?: nav.currentManeuver?.index ?: return
    _navigationState.value = nav.copy(
        showJunctionPreview = false,
        activeJunctionManeuverIndex = -1,
        dismissedJunctionManeuverIndex = targetIdx
    )
}

internal fun RahYabViewModel.stopNavigationImpl() {
    val wasSimulating = _navigationState.value.isSimulatingDrive
    stopInternalNavJobsImpl()
    _simulationSpeedMultiplier.value = 1
    if (wasSimulating) {
        viewModelScope.launch { prefs.setSimulateDriveEnabled(false) }
    }
    locationTracker.setNavigationMode(false)
    compassTracker.setNavigationMode(false)
    _navigationState.value = ActiveNavigationState()
    navController.reset()
    hpmsFallbackCache.reset()
    resetTurnFocusImpl()
    invalidatePendingCameraCommandsImpl()
    speedLimitDropTracker.reset()
    clearDestinationParkingStateImpl()
    NavigationForegroundService.stop(app)
    _isCameraFollowing.value = true
    val ref = getEffectiveReferenceLocation()
    _cameraCommand.value = CameraTargetCommand(
        lat = ref.lat,
        lon = ref.lon,
        zoom = 14.0,
        tilt = 0.0,
        bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0,
        generation = _cameraGeneration.value
    )
    viewModelScope.launch {
        val applied = packManager.applyPendingSwapsIfIdle()
        if (applied > 0) {
            _snackbarMessage.value = com.example.i18n.stringsFor(settings.value.language).mapUpdateReadySwapApplied
        }
    }
}

internal fun RahYabViewModel.stopInternalNavJobsImpl() {
    gpsWatchdogJob?.cancel()
    gpsWatchdogJob = null
    simulationJob?.cancel()
    simulationJob = null
    arrivalEndJob?.cancel()
    arrivalEndJob = null
}

/** X6: Queries optional FRA rail crossings along DRIVE/BIKE routes strictly on Dispatchers.IO. */
internal fun RahYabViewModel.loadFraCrossingsForRouteImpl(
    route: OfflineRoute,
    osmHazards: List<com.example.data.search.RoadHazardPoint> = cachedDbHazards
) {
    if (route.travelMode == TravelMode.WALK || route.shape.size < 2) {
        cachedFraCrossings = emptyList()
        return
    }
    viewModelScope.launch {
        val minLat = route.shape.minOf { it.lat } - 0.001
        val maxLat = route.shape.maxOf { it.lat } + 0.001
        val minLon = route.shape.minOf { it.lon } - 0.0015
        val maxLon = route.shape.maxOf { it.lon } + 0.0015
        val crossings = withContext(Dispatchers.IO) {
            app.extrasDbReader.queryFraCrossingsInBounds(minLat, maxLat, minLon, maxLon)
        }
        cachedFraCrossings = crossings
        if (crossings.isEmpty()) return@launch
        val fraOnly = withContext(Dispatchers.Default) {
            com.example.data.packs.ExtrasMatchingHelper.buildFraOnlyRouteAlerts(route.shape, osmHazards, crossings)
        }
        if (fraOnly.isEmpty()) return@launch
        val nav = _navigationState.value
        val activeRoute = nav.route
        if (nav.isNavigating && activeRoute != null && activeRoute.id == route.id) {
            val existingIds = activeRoute.roadAlerts.mapTo(mutableSetOf()) { it.id }
            val toAdd = fraOnly.filterNot { it.id in existingIds }
            if (toAdd.isNotEmpty()) {
                _navigationState.value = nav.copy(route = activeRoute.copy(roadAlerts = activeRoute.roadAlerts + toAdd))
            }
        }
    }
}
