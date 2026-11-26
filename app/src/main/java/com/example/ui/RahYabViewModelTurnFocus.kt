package com.example.ui

import com.example.car.CarCameraProfile
import com.example.map.LaneOverlayBuilder
import com.example.navigation.ActiveNavigationState
import com.example.navigation.LatLon
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusPlanner

/**
 * F2 & F4: Orchestrates Turn Focus mode evaluation, independent optional audio cue playback,
 * immediate focus zoom/tilt/lower-quarter camera animation (adaptive duration, never gated on
 * speech or beeps), suppression of speed zoom-out during focus, and honoring user pan/pinch.
 */
internal fun RahYabViewModel.resetTurnFocusImpl() {
    voiceManager.cancelTurnFocusBeeps()
    turnFocusTracker.reset()
    turnFocusUserPannedDuringActive = false
    turnFocusPendingEnterAnim = false
    turnFocusPendingEnterAnimMs = TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS
    turnFocusPendingExitAnim = false
    val cur = _navigationState.value
    if (cur.turnFocusActive || cur.turnFocusCameraActive) {
        _navigationState.value = cur.copy(
            turnFocusActive = false,
            turnFocusCameraActive = false,
            turnFocusIsHighway = false,
            turnFocusManeuverIndex = -1,
            turnFocusTriggerDistanceMeters = 0.0,
            turnFocusEnterAnimMs = TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS,
            turnFocusClosePair = false
        )
    }
}

internal fun RahYabViewModel.evaluateTurnFocusImpl(
    state: ActiveNavigationState,
    speedMps: Float
): ActiveNavigationState {
    val s = settings.value
    val config = TurnFocusConfig(
        enabled = s.turnFocusModeEnabled,
        beepsEnabled = s.turnFocusBeepsEnabled,
        cityDistanceMeters = s.turnFocusCityDistanceMeters.toDouble(),
        highwayDistanceMeters = s.turnFocusHighwayDistanceMeters.toDouble(),
        wideHighwayEarlierEnabled = s.turnFocusWideHighwayEarlierEnabled
    )
    val decision = turnFocusTracker.evaluate(
        route = state.route,
        currentManeuverIndex = state.currentManeuverIndex,
        snappedShapeIndex = state.snappedShapeIndex,
        distanceToNextManeuverMeters = state.distanceToNextManeuverMeters,
        speedMps = speedMps,
        travelMode = state.effectiveTravelMode,
        isNavigating = state.isNavigating,
        isOffRoute = state.isOffRoute,
        isRerouting = state.isRerouting,
        hasArrived = state.hasArrived,
        config = config,
        snappedLocation = state.snappedLocation,
        gpsAgeMs = state.lastGpsFixAgeMs
    )

    if (decision.justEnded && !decision.isActive) {
        voiceManager.cancelTurnFocusBeeps()
        turnFocusUserPannedDuringActive = false
        turnFocusPendingEnterAnim = false
        turnFocusPendingEnterAnimMs = TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS
        turnFocusPendingExitAnim = true
        val endedState = state.copy(
            turnFocusActive = false,
            turnFocusCameraActive = false,
            turnFocusIsHighway = false,
            turnFocusManeuverIndex = -1,
            turnFocusTriggerDistanceMeters = 0.0,
            turnFocusEnterAnimMs = TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS,
            turnFocusClosePair = false
        )
        _navigationState.value = endedState
        return endedState
    }

    if (decision.justEntered) {
        turnFocusUserPannedDuringActive = false
        turnFocusPendingExitAnim = false
        turnFocusPendingEnterAnim = true
        turnFocusPendingEnterAnimMs = decision.recommendedEnterAnimMs
        val showPanelNow = state.showLaneStrip || (state.laneDecision?.shouldShowFullDiagram == true)
        val isClosePair = decision.closeFollowingTurnDistanceMeters != null
        // Visual guidance starts on THIS navigation update — never delayed by speech or optional beeps
        val enteredState = state.copy(
            showLaneStrip = showPanelNow,
            turnFocusActive = true,
            turnFocusCameraActive = true,
            turnFocusIsHighway = decision.isHighway,
            turnFocusManeuverIndex = decision.maneuverIndex,
            turnFocusTriggerDistanceMeters = decision.triggerDistanceMeters,
            turnFocusEnterAnimMs = decision.recommendedEnterAnimMs,
            turnFocusClosePair = isClosePair
        )
        _navigationState.value = enteredState

        // Optional audio cues proceed completely independently of visual camera transition
        val expectedRouteId = enteredState.route?.id
        val expectedManeuverIndex = decision.maneuverIndex
        if (decision.shouldBeep) {
            val isStillValid: () -> Boolean = {
                val cur = _navigationState.value
                cur.isNavigating &&
                    !cur.isRerouting &&
                    !cur.isOffRoute &&
                    !cur.hasArrived &&
                    cur.route?.id == expectedRouteId &&
                    cur.currentManeuver?.index == expectedManeuverIndex
            }
            val phraseProvider: () -> String? = {
                val cur = _navigationState.value
                val curSettings = settings.value
                val maneuver = cur.currentManeuver
                if (!isStillValid() || maneuver == null) {
                    null
                } else {
                    val activeDecision = cur.laneDecision
                        ?.takeIf { it.maneuverIndex == maneuver.index }
                        ?: com.example.navigation.LaneGuidanceDecision.evaluate(maneuver)
                    val surface = navigationHub.resolveActiveLaneDisplaySurface()
                    val isDiagramVisibleOnPhone = surface == com.example.navigation.LaneDisplaySurface.PHONE_SCREEN &&
                        cur.showLaneStrip &&
                        activeDecision.shouldShowFullDiagram
                    activeDecision.resolveVoiceCue(
                        surface = surface,
                        isDiagramVisibleOnActiveDisplay = isDiagramVisibleOnPhone,
                        highlightColor = curSettings.laneHighlightColor,
                        isCarHostColorControlled = navigationHub.isCarSessionActive,
                        language = curSettings.language
                    )
                }
            }
            voiceManager.playTurnFocusBeeps(
                language = s.language,
                speakShortPhrase = s.turnFocusShortPhraseEnabled,
                context = com.example.navigation.SpeechContextTag(
                    routeId = expectedRouteId,
                    maneuverIndex = expectedManeuverIndex,
                    stage = "turn_focus"
                ),
                isStillValid = isStillValid,
                phraseProvider = phraseProvider,
                onAllBeepsFinished = {}
            )
        }
        return enteredState
    }

    val updated = state.copy(
        turnFocusActive = decision.isActive,
        turnFocusCameraActive = decision.isActive && state.turnFocusCameraActive,
        turnFocusIsHighway = if (decision.isActive) decision.isHighway else false,
        turnFocusManeuverIndex = if (decision.isActive) decision.maneuverIndex else -1,
        turnFocusTriggerDistanceMeters = if (decision.isActive) decision.triggerDistanceMeters else 0.0,
        turnFocusEnterAnimMs = if (decision.isActive) state.turnFocusEnterAnimMs else TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS,
        turnFocusClosePair = decision.isActive && decision.closeFollowingTurnDistanceMeters != null
    )
    _navigationState.value = updated
    return updated
}

internal fun RahYabViewModel.computeFocusOrFollowCameraCommandImpl(
    state: ActiveNavigationState,
    targetLoc: LatLon,
    speedMps: Float,
    heading: Float,
    simMultiplier: Int,
    cameraAnimMs: Int,
    forceRecenterNow: Boolean
): CameraTargetCommand? {
    val s = settings.value
    val mode = state.effectiveTravelMode
    val activeRouteId = state.route?.id
    val currentGeneration = _cameraGeneration.value

    // F4: Active Turn Focus camera override — suppresses speed zoom-out & keeps car in lower quarter
    if (state.turnFocusCameraActive && !turnFocusUserPannedDuringActive && mode == TravelMode.DRIVE) {
        val enterAnim = turnFocusPendingEnterAnim
        turnFocusPendingEnterAnim = false
        val animMs = when {
            enterAnim -> turnFocusPendingEnterAnimMs.coerceIn(
                TurnFocusPlanner.MIN_ENTER_ANIM_MS,
                CarCameraProfile.FOCUS_ENTER_ANIM_MS
            )
            state.isSimulatingDrive -> cameraAnimMs
            else -> minOf(650, state.turnFocusEnterAnimMs.coerceAtLeast(TurnFocusPlanner.MIN_ENTER_ANIM_MS))
        }
        val focusZoom = when {
            state.turnFocusIsHighway -> TurnFocusPlanner.FOCUS_ZOOM_HIGHWAY
            state.turnFocusClosePair -> TurnFocusPlanner.FOCUS_ZOOM_CLOSE_PAIR
            else -> TurnFocusPlanner.FOCUS_ZOOM_CITY
        }
        return CameraTargetCommand(
            lat = targetLoc.lat,
            lon = targetLoc.lon,
            zoom = focusZoom,
            tilt = if (s.is3DView) TurnFocusPlanner.FOCUS_TILT_3D else 0.0,
            bearing = heading.toDouble(),
            animationDurationMs = animMs,
            anchorLowerThird = false,
            anchorLowerQuarter = true,
            routeId = activeRouteId,
            maneuverIndex = state.turnFocusManeuverIndex,
            generation = currentGeneration,
            isFocusCommand = true
        )
    }

    val exitAnim = turnFocusPendingExitAnim
    if (exitAnim) turnFocusPendingExitAnim = false

    val isLaneOverlayActive = !state.hasArrived &&
        state.laneConfidence != com.example.navigation.LaneConfidence.LOW &&
        LaneOverlayBuilder.build(
            route = state.route,
            currentManeuverIndex = state.currentManeuverIndex,
            snappedShapeIndex = state.snappedShapeIndex,
            distanceToNextManeuverMeters = state.distanceToNextManeuverMeters,
            travelMode = mode,
            isOffRoute = state.isOffRoute,
            enabled = s.laneHighlightOnMapEnabled
        ) != null

    val update = cameraFollowController.computeFollowCameraUpdate(
        lat = targetLoc.lat,
        lon = targetLoc.lon,
        speedMps = speedMps,
        headingDegrees = heading,
        isHeadingUpMode = _isHeadingUpMode.value,
        is3DView = s.is3DView,
        isNavigating = true,
        simulationSpeedMultiplier = simMultiplier,
        forceImmediate = forceRecenterNow || exitAnim,
        isWalkingMode = state.isWalkingMode,
        travelMode = mode,
        minZoomFloor = if (isLaneOverlayActive) 16.5 else null
    ) ?: return null

    val effectiveAnimMs = when {
        exitAnim -> CarCameraProfile.FOCUS_EXIT_ANIM_MS
        state.isSimulatingDrive -> cameraAnimMs
        else -> update.animationDurationMs
    }
    return CameraTargetCommand(
        lat = update.lat,
        lon = update.lon,
        zoom = update.zoom,
        tilt = update.tilt,
        bearing = update.bearing,
        animationDurationMs = effectiveAnimMs,
        anchorLowerThird = update.anchorLowerThird,
        anchorLowerQuarter = false,
        routeId = activeRouteId,
        maneuverIndex = state.currentManeuver?.index ?: -1,
        generation = currentGeneration,
        isFocusCommand = false
    )
}
