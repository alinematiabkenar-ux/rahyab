package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.stringsFor
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.NavigationSessionController
import com.example.navigation.ValhallaResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun RahYabViewModel.startSimulatedDriveLoopImpl() {
    simulationJob?.cancel()
    simulatedClockMs = System.currentTimeMillis()
    simulationJob = viewModelScope.launch {
        while (_navigationState.value.isNavigating &&
            _navigationState.value.isSimulatingDrive &&
            !_navigationState.value.hasArrived
        ) {
            val multiplier = _simulationSpeedMultiplier.value.coerceIn(1, 32)
            // Adapt map frame rate at high speeds so MapLibre never freezes
            val wallTickMs = when {
                multiplier >= 32 -> 350L
                multiplier >= 8 -> 300L
                else -> 250L
            }
            delay(wallTickMs)
            if (!_navigationState.value.isNavigating || !_navigationState.value.isSimulatingDrive) break

            val wrongTurnOffset = if (simulatedWrongTurnTicksRemaining > 0) {
                simulatedWrongTurnTicksRemaining--
                120.0
            } else {
                0.0
            }
            val currentState = _navigationState.value
            val s = settings.value
            val reports = activeUserReports.value
            val hazards = cachedDbHazards
            val totalSimSec = (wallTickMs / 1000.0) * multiplier

            val batch = withContext(Dispatchers.Default) {
                navController.advanceSimulationStep(
                    currentState = currentState,
                    totalSimulatedSeconds = totalSimSec,
                    startingSimClockMs = simulatedClockMs,
                    wrongTurnOffsetMeters = wrongTurnOffset,
                    dbHazards = hazards,
                    userReports = reports,
                    unit = s.distanceUnit,
                    language = s.language,
                    speedAudioEnabled = s.speedWarningAudioEnabled,
                    settings = s
                )
            }
            simulatedClockMs = batch.finalSimClockMs
            applyNavigationStepResultImpl(
                state = batch.state,
                shouldReroute = batch.shouldReroute,
                maneuverToSpeak = batch.latestManeuverToSpeak,
                alertToSpeak = batch.latestAlertToSpeak,
                triggerOverspeedChime = batch.triggerOverspeedChime,
                fallbackLoc = batch.state.snappedLocation,
                speedMps = batch.state.currentSpeedMps,
                cameraAnimMs = when {
                    multiplier >= 16 -> 0
                    multiplier >= 8 -> 140
                    else -> 220
                }
            )
        }
    }
}

internal fun RahYabViewModel.toggleSimulationFromMapImpl() {
    val isCurrentlySimulating = _navigationState.value.isSimulatingDrive || settings.value.simulateDriveEnabled
    if (isCurrentlySimulating) {
        stopSimulationAndReturnToRealGpsImpl()
        return
    }

    val hasActiveRoute = _navigationState.value.isNavigating && _navigationState.value.route != null
    val hasPreviewRoute = _candidateRoutes.value.isNotEmpty() && _selectedPlace.value != null
    if (!hasActiveRoute && !hasPreviewRoute) {
        _snackbarMessage.value = stringsFor(settings.value.language).simulationNoRouteMessage
        return
    }

    _simulationSpeedMultiplier.value = 1
    viewModelScope.launch { prefs.setSimulateDriveEnabled(true) }
    if (hasActiveRoute) {
        startSimulationOnActiveRouteImpl()
    } else {
        startNavigationImpl(forceSimulate = true)
    }
}

internal fun RahYabViewModel.startSimulationOnActiveRouteImpl() {
    val currentNav = _navigationState.value
    val route = currentNav.route ?: return
    val s = settings.value
    gpsWatchdogJob?.cancel()
    gpsWatchdogJob = null
    navController.reset()
    speedBumpEvaluator.reset()
    seedSimulationSpeedBumpsImpl(route.shape)
    voiceManager.resetDeduplication()
    simulatedWrongTurnTicksRemaining = 0
    val startLoc = route.shape.firstOrNull() ?: getEffectiveReferenceLocation()
    val initialBearing = if (route.shape.size >= 2) {
        ValhallaResponseParser.bearingDegrees(route.shape[0], route.shape[1]).toFloat()
    } else {
        currentNav.snappedBearing
    }
    val firstManeuver = route.maneuvers.firstOrNull()
    val initialDist = firstManeuver?.lengthMeters ?: route.distanceMeters
    _navigationState.value = currentNav.copy(
        isSimulatingDrive = true,
        currentManeuverIndex = 0,
        snappedShapeIndex = 0,
        snappedLocation = startLoc,
        snappedBearing = initialBearing,
        distanceToNextManeuverMeters = initialDist,
        timeToNextManeuverSeconds = firstManeuver?.timeSeconds ?: route.durationSeconds,
        remainingDistanceMeters = route.distanceMeters,
        remainingTimeSeconds = route.durationSeconds,
        currentSpeedLimitMph = route.speedLimitsByShapeIndex[0] ?: firstManeuver?.speedLimitMph,
        isOffRoute = false,
        isRerouting = false,
        isGpsSignalLost = false,
        hasArrived = false,
        showLaneStrip = firstManeuver?.lanes?.isNotEmpty() == true &&
            initialDist <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit)
    )
    _isCameraFollowing.value = true
    _cameraCommand.value = CameraTargetCommand(
        lat = startLoc.lat,
        lon = startLoc.lon,
        zoom = 16.8,
        tilt = if (s.is3DView) 55.0 else 0.0,
        bearing = if (_isHeadingUpMode.value) initialBearing.toDouble() else 0.0,
        animationDurationMs = 300
    )
    startSimulatedDriveLoopImpl()
}

internal fun RahYabViewModel.stopSimulationAndReturnToRealGpsImpl() {
    simulationJob?.cancel()
    simulationJob = null
    _simulationSpeedMultiplier.value = 1
    simulatedWrongTurnTicksRemaining = 0
    clearSimulationSpeedBumpsImpl()
    viewModelScope.launch { prefs.setSimulateDriveEnabled(false) }

    val realFix = currentGpsFix.value
    val s = settings.value
    val wasNavigating = _navigationState.value.isNavigating
    if (wasNavigating) {
        val realLoc = realFix?.let { LatLon(it.lat, it.lon) } ?: getEffectiveReferenceLocation()
        _navigationState.value = _navigationState.value.copy(
            isSimulatingDrive = false,
            snappedLocation = realLoc,
            currentSpeedMps = realFix?.speedMps ?: 0f,
            isGpsSignalLost = false
        )
        lastGpsReceivedMs = System.currentTimeMillis()
        startGpsLossWatchdogImpl()
        _isCameraFollowing.value = true
        _cameraCommand.value = CameraTargetCommand(
            lat = realLoc.lat,
            lon = realLoc.lon,
            zoom = 16.5,
            tilt = if (s.is3DView) 55.0 else 0.0,
            bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0,
            animationDurationMs = 400
        )
        // Immediately recalculate active route from the user's real GPS position
        val rerouteFix = realFix ?: GpsFix(
            lat = realLoc.lat,
            lon = realLoc.lon,
            bearing = 0f,
            speedMps = 0f,
            accuracyMeters = 10f,
            timestampMs = System.currentTimeMillis()
        )
        triggerAutomaticRerouteImpl(rerouteFix)
    } else {
        val realLoc = realFix?.let { LatLon(it.lat, it.lon) } ?: getEffectiveReferenceLocation()
        _cameraCommand.value = CameraTargetCommand(
            lat = realLoc.lat,
            lon = realLoc.lon,
            zoom = 14.5,
            tilt = 0.0,
            bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0,
            animationDurationMs = 400
        )
    }
}

internal fun RahYabViewModel.cycleSimulationSpeedImpl() {
    val steps = RahYabViewModel.SIMULATION_SPEED_STEPS
    val currentIdx = steps.indexOf(_simulationSpeedMultiplier.value)
    val nextSpeed = steps[(currentIdx + 1) % steps.size]
    _simulationSpeedMultiplier.value = nextSpeed
    val s = settings.value
    val str = stringsFor(s.language)
    val speedLabel = if (s.language == AppLanguage.PERSIAN) {
        "${BilingualFormatter.formatNumber(nextSpeed, AppLanguage.PERSIAN)}x"
    } else {
        "${nextSpeed}x"
    }
    _snackbarMessage.value = "${str.simulationSpeedChangedPrefix}: $speedLabel"
}

internal fun RahYabViewModel.resetSimulationSpeedTo1xImpl() {
    _simulationSpeedMultiplier.value = 1
    val s = settings.value
    val str = stringsFor(s.language)
    val speedLabel = if (s.language == AppLanguage.PERSIAN) "۱x" else "1x"
    _snackbarMessage.value = "${str.simulationSpeedChangedPrefix}: $speedLabel"
}

internal fun RahYabViewModel.triggerSimulatedWrongTurnImpl() {
    if (_navigationState.value.isNavigating && _navigationState.value.isSimulatingDrive) {
        simulatedWrongTurnTicksRemaining = 4
    }
}

internal fun RahYabViewModel.setSimulateDriveEnabledImpl(enabled: Boolean) {
    if (!enabled) {
        stopSimulationAndReturnToRealGpsImpl()
        return
    }
    viewModelScope.launch { prefs.setSimulateDriveEnabled(true) }
    if (_navigationState.value.isNavigating && !_navigationState.value.isSimulatingDrive) {
        startSimulationOnActiveRouteImpl()
    } else if (!_navigationState.value.isNavigating && _candidateRoutes.value.isNotEmpty() && _selectedPlace.value != null) {
        startNavigationImpl(forceSimulate = true)
    }
}
