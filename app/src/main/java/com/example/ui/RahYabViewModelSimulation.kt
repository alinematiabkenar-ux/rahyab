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
                    settings = s,
                    fraCrossings = cachedFraCrossings,
                    hpmsFallbackSpeedMph = hpmsFallbackCache.cachedSpeedMph
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
    val rawRoute = currentNav.route ?: return
    val route = if (rawRoute.travelMode == com.example.navigation.TravelMode.DRIVE) {
        enrichHighwayExitLanesForSimulation(rawRoute)
    } else {
        rawRoute
    }
    val s = settings.value
    gpsWatchdogJob?.cancel()
    gpsWatchdogJob = null
    navController.reset()
    speedBumpEvaluator.reset()
    trafficLightVoiceEvaluator.reset()
    laneChangeCueTracker.reset()
    speedLimitDropTracker.reset()
    resetTurnFocusImpl()
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
        route = route,
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
        showJunctionPreview = false,
        activeJunctionManeuverIndex = -1,
        dismissedJunctionManeuverIndex = -1,
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

internal fun RahYabViewModel.stepSimulationSpeedUpImpl() {
    val cur = _simulationSpeedMultiplier.value
    val next = com.example.ui.components.SimulationSpeedStepper.stepUp(cur)
    if (next != cur) _simulationSpeedMultiplier.value = next
}

internal fun RahYabViewModel.stepSimulationSpeedDownImpl() {
    val cur = _simulationSpeedMultiplier.value
    val next = com.example.ui.components.SimulationSpeedStepper.stepDown(cur)
    if (next != cur) _simulationSpeedMultiplier.value = next
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

/**
 * L6 & F9: Simulation-only lane enrichment for BOTH highway exits/ramps (e.g., I-5 Eugene/Springfield)
 * AND city left/right turns (e.g., Eugene city streets) when a maneuver lacks OSM turn:lanes metadata,
 * ensuring Drive Simulation reliably exercises Turn Focus mode (3 beeps, focus zoom, blue lanes)
 * without affecting real GPS navigation.
 */
internal fun enrichHighwayExitLanesForSimulation(
    route: com.example.navigation.OfflineRoute
): com.example.navigation.OfflineRoute {
    val updatedOneWay = route.oneWayByShapeIndex.toMutableMap()
    val updated = route.maneuvers.map { m ->
        val isExitOrSplit = m.isHighwayExitOrSplit ||
            m.type == com.example.navigation.ManeuverType.EXIT_RIGHT ||
            m.type == com.example.navigation.ManeuverType.EXIT_LEFT ||
            m.type == com.example.navigation.ManeuverType.RAMP_RIGHT ||
            m.type == com.example.navigation.ManeuverType.RAMP_LEFT ||
            m.type == com.example.navigation.ManeuverType.STAY_RIGHT ||
            m.type == com.example.navigation.ManeuverType.STAY_LEFT
        val isCityTurn = m.type == com.example.navigation.ManeuverType.LEFT ||
            m.type == com.example.navigation.ManeuverType.RIGHT ||
            m.type == com.example.navigation.ManeuverType.SHARP_LEFT ||
            m.type == com.example.navigation.ManeuverType.SHARP_RIGHT

        if (isExitOrSplit) {
            val startIdx = (m.beginShapeIndex - 20).coerceAtLeast(0)
            val endIdx = (m.beginShapeIndex + 10).coerceAtMost((route.shape.size - 1).coerceAtLeast(0))
            for (idx in startIdx..endIdx) {
                updatedOneWay.putIfAbsent(idx, true)
            }
        }
        if (m.lanes.size >= 2) return@map m

        val thruLane = com.example.navigation.LaneInfo(
            directions = listOf(com.example.navigation.LaneDirection.THROUGH),
            activeDirections = emptyList(),
            isRecommended = false,
            isActive = false,
            isValid = false
        )

        if (isExitOrSplit) {
            val isLeft = m.type == com.example.navigation.ManeuverType.EXIT_LEFT ||
                m.type == com.example.navigation.ManeuverType.RAMP_LEFT ||
                m.type == com.example.navigation.ManeuverType.STAY_LEFT
            val exitDir = if (isLeft) com.example.navigation.LaneDirection.SLIGHT_LEFT else com.example.navigation.LaneDirection.SLIGHT_RIGHT
            val exitLane = com.example.navigation.LaneInfo(
                directions = listOf(exitDir),
                activeDirections = listOf(exitDir),
                isRecommended = true,
                isActive = true,
                isValid = true
            )
            val lanes = if (isLeft) listOf(exitLane, thruLane, thruLane) else listOf(thruLane, thruLane, exitLane)
            return@map m.copy(
                lanes = lanes,
                laneCount = lanes.size,
                isMultiLaneRoad = true,
                isHighwayExitOrSplit = true,
                hasRoadLaneGeometry = true
            )
        }

        if (isCityTurn) {
            val isLeft = m.type == com.example.navigation.ManeuverType.LEFT ||
                m.type == com.example.navigation.ManeuverType.SHARP_LEFT
            val turnDir = if (isLeft) com.example.navigation.LaneDirection.LEFT else com.example.navigation.LaneDirection.RIGHT
            val turnLane = com.example.navigation.LaneInfo(
                directions = listOf(turnDir),
                activeDirections = listOf(turnDir),
                isRecommended = true,
                isActive = true,
                isValid = true
            )
            val lanes = if (isLeft) listOf(turnLane, thruLane) else listOf(thruLane, turnLane)
            return@map m.copy(
                lanes = lanes,
                laneCount = lanes.size,
                isMultiLaneRoad = true,
                hasRoadLaneGeometry = true
            )
        }

        m
    }
    return route.copy(
        maneuvers = updated,
        oneWayByShapeIndex = updatedOneWay,
        hasRoadLaneGeometry = route.hasRoadLaneGeometry || updated.any { it.hasRoadLaneGeometry }
    )
}
