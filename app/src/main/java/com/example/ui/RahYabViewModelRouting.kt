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
import com.example.navigation.ValhallaResponseParser
import com.example.service.NavigationForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun RahYabViewModel.buildCurrentRouteOptionsImpl(s: UserSettings): RouteOptions {
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
        avoidLocations = closedLocations
    )
}

internal fun RahYabViewModel.calculateRoutesToSelectedPlaceImpl() {
    val dest = _selectedPlace.value ?: return
    val origin = getEffectiveReferenceLocation()
    val s = settings.value
    val options = buildCurrentRouteOptionsImpl(s)
    // Only send GPS bearing when moving > 3 mph with a fresh (< 10 s) bearing; never send compass heading
    val routingHeading = LocationTracker.resolveRoutingHeadingDegrees(currentGpsFix.value)

    viewModelScope.launch {
        _isCalculatingRoute.value = true
        _routeError.value = null
        _missingStatesForRoute.value = emptyList()
        val result = routingEngine.calculateRoutes(
            origin = origin,
            destination = LatLon(dest.lat, dest.lon),
            options = options,
            headingDegrees = routingHeading,
            allowDevOnlineFallback = s.devOnlineOregonMode
        )
        _isCalculatingRoute.value = false
        result.onSuccess { routes ->
            _candidateRoutes.value = routes
            _selectedRouteIndex.value = 0
            autoRecenterJob?.cancel()
            _isCameraFollowing.value = false
            routes.firstOrNull()?.shape?.let { pts ->
                if (pts.isNotEmpty()) {
                    val mid = pts[pts.size / 2]
                    _cameraCommand.value = CameraTargetCommand(mid.lat, mid.lon, zoom = 12.0, anchorLowerThird = false)
                }
            }
            refreshWeatherAlertsIfEligibleImpl(explicitRoute = routes.firstOrNull())
        }.onFailure { err ->
            if (err is MissingStatesException) {
                _missingStatesForRoute.value = err.missingStates
                _routeError.value = err.message
            } else {
                _routeError.value = err.message ?: stringsFor(s.language).missingRoutingTiles
            }
        }
    }
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
    voiceManager.resetDeduplication()
    val initialManeuver = newRoute.maneuvers.firstOrNull()
    val startLoc = _navigationState.value.snappedLocation
        ?: newRoute.shape.firstOrNull()
        ?: getEffectiveReferenceLocation()

    _navigationState.value = _navigationState.value.copy(
        route = newRoute,
        currentManeuverIndex = 0,
        snappedShapeIndex = 0,
        isOffRoute = false,
        isRerouting = false,
        distanceToNextManeuverMeters = initialManeuver?.lengthMeters ?: newRoute.distanceMeters,
        timeToNextManeuverSeconds = initialManeuver?.timeSeconds ?: newRoute.durationSeconds,
        remainingDistanceMeters = newRoute.distanceMeters,
        remainingTimeSeconds = newRoute.durationSeconds,
        snappedLocation = startLoc,
        currentSpeedLimitMph = initialManeuver?.speedLimitMph,
        showLaneStrip = initialManeuver?.lanes?.isNotEmpty() == true &&
            (initialManeuver.lengthMeters <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit)),
        lanePulseManeuverIndex = -1,
        dismissedJunctionManeuverIndex = -1
    )

    val firstInstruction = initialManeuver?.streetNames?.firstOrNull()?.ifBlank { initialManeuver.instructionEn }
        ?: initialManeuver?.instructionEn
        ?: dest.name
    val etaText = BilingualFormatter.formatDuration(newRoute.durationSeconds, s.language)
    NavigationForegroundService.updateNotification(
        context = app,
        title = "${dest.name} • $etaText",
        body = firstInstruction,
        force = true
    )
}

internal fun RahYabViewModel.startNavigationImpl(forceSimulate: Boolean? = null) {
    val routes = _candidateRoutes.value
    if (routes.isEmpty()) return
    val safeIdx = _selectedRouteIndex.value.coerceIn(0, routes.lastIndex)
    val route = routes[safeIdx]
    val dest = _selectedPlace.value ?: return
    val s = settings.value
    val shouldSimulate = forceSimulate ?: s.simulateDriveEnabled
    val wasAlreadyNavigating = _navigationState.value.isNavigating

    stopInternalNavJobsImpl()
    navController.reset()
    voiceManager.resetDeduplication()
    locationTracker.setNavigationMode(true)
    compassTracker.setNavigationMode(true)
    _isCameraFollowing.value = true
    lastGpsReceivedMs = System.currentTimeMillis()
    simulatedWrongTurnTicksRemaining = 0
    if (!shouldSimulate) {
        _simulationSpeedMultiplier.value = 1
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
        currentSpeedLimitMph = route.speedLimitsByShapeIndex[0] ?: initialManeuver?.speedLimitMph,
        isSimulatingDrive = shouldSimulate,
        showLaneStrip = initialManeuver?.lanes?.isNotEmpty() == true &&
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
        }
    }

    // Start foreground service ONLY once when navigation begins; if already running, only update notification
    val firstInstruction = initialManeuver?.instructionEn ?: dest.name
    val etaText = BilingualFormatter.formatDuration(route.durationSeconds, s.language)
    if (wasAlreadyNavigating) {
        NavigationForegroundService.updateNotification(
            context = app,
            title = "${dest.name} • $etaText",
            body = firstInstruction,
            force = true
        )
    } else {
        NavigationForegroundService.startOrUpdate(
            context = app,
            title = "${dest.name} • $etaText",
            body = firstInstruction
        )
    }

    // Announce clear departure instruction with compass direction, starting street, and next turn
    val spoken = voiceManager.speakStartInstruction(route, s.language)
    if (spoken.isNotBlank()) {
        navController.markExternalPromptSpoken()
        _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
    }

    // Switch camera to driving view
    _cameraCommand.value = CameraTargetCommand(
        lat = startLoc.lat,
        lon = startLoc.lon,
        zoom = 16.8,
        tilt = if (s.is3DView) 55.0 else 0.0,
        bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0
    )

    if (shouldSimulate) {
        startSimulatedDriveLoopImpl()
    } else {
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
                        val s = settings.value
                        _cameraCommand.value = CameraTargetCommand(
                            lat = loc.lat,
                            lon = loc.lon,
                            zoom = 16.5,
                            tilt = if (s.is3DView) 55.0 else 0.0,
                            bearing = if (_isHeadingUpMode.value) deadReckoned.snappedBearing.toDouble() else 0.0
                        )
                    }
                }
            }
        }
    }
}

internal fun RahYabViewModel.dismissJunctionPreviewImpl() {
    val currentIdx = _navigationState.value.currentManeuver?.index ?: return
    _navigationState.value = _navigationState.value.copy(
        showJunctionPreview = false,
        dismissedJunctionManeuverIndex = currentIdx
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
    NavigationForegroundService.stop(app)
    _isCameraFollowing.value = true
    val ref = getEffectiveReferenceLocation()
    _cameraCommand.value = CameraTargetCommand(
        lat = ref.lat,
        lon = ref.lon,
        zoom = 14.0,
        tilt = 0.0,
        bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0
    )
}

internal fun RahYabViewModel.stopInternalNavJobsImpl() {
    gpsWatchdogJob?.cancel()
    gpsWatchdogJob = null
    simulationJob?.cancel()
    simulationJob = null
    arrivalEndJob?.cancel()
    arrivalEndJob = null
}
