package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.car.CarCameraProfile
import com.example.map.LaneOverlayBuilder
import com.example.navigation.ActiveNavigationState
import com.example.navigation.LatLon
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusAudioPlayer
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusPlanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * F2 & F4: Orchestrates Turn Focus mode evaluation, 3-beep cue + 300 ms post-beep delay,
 * focus zoom/tilt/lower-quarter camera animation (1200 ms enter / 1500 ms exit),
 * suppression of speed zoom-out during focus, and honoring user pan/pinch until THIS focus ends.
 */
internal fun RahYabViewModel.resetTurnFocusImpl() {
    turnFocusZoomDelayJob?.cancel()
    turnFocusZoomDelayJob = null
    voiceManager.cancelTurnFocusBeeps()
    turnFocusTracker.reset()
    turnFocusUserPannedDuringActive = false
    turnFocusPendingEnterAnim = false
    turnFocusPendingExitAnim = false
    val cur = _navigationState.value
    if (cur.turnFocusActive || cur.turnFocusCameraActive) {
        _navigationState.value = cur.copy(
            turnFocusActive = false,
            turnFocusCameraActive = false,
            turnFocusIsHighway = false,
            turnFocusManeuverIndex = -1,
            turnFocusTriggerDistanceMeters = 0.0
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
        config = config
    )

    if (decision.justEnded && !decision.isActive) {
        turnFocusZoomDelayJob?.cancel()
        turnFocusZoomDelayJob = null
        voiceManager.cancelTurnFocusBeeps()
        turnFocusUserPannedDuringActive = false
        turnFocusPendingEnterAnim = false
        turnFocusPendingExitAnim = true
        val endedState = state.copy(
            turnFocusActive = false,
            turnFocusCameraActive = false,
            turnFocusIsHighway = false,
            turnFocusManeuverIndex = -1,
            turnFocusTriggerDistanceMeters = 0.0
        )
        _navigationState.value = endedState
        return endedState
    }

    if (decision.justEntered) {
        turnFocusUserPannedDuringActive = false
        turnFocusPendingExitAnim = false
        turnFocusZoomDelayJob?.cancel()
        val enteredState = state.copy(
            turnFocusActive = true,
            turnFocusCameraActive = !decision.shouldBeep,
            turnFocusIsHighway = decision.isHighway,
            turnFocusManeuverIndex = decision.maneuverIndex,
            turnFocusTriggerDistanceMeters = decision.triggerDistanceMeters
        )
        _navigationState.value = enteredState
        if (decision.shouldBeep) {
            voiceManager.playTurnFocusBeeps(
                language = s.language,
                speakShortPhrase = s.turnFocusShortPhraseEnabled,
                onAllBeepsFinished = {
                    turnFocusZoomDelayJob?.cancel()
                    turnFocusZoomDelayJob = viewModelScope.launch {
                        delay(TurnFocusAudioPlayer.POST_BEEP_ZOOM_DELAY_MS)
                        activateTurnFocusCameraImpl(decision.maneuverIndex, decision.isHighway)
                    }
                }
            )
        } else {
            turnFocusPendingEnterAnim = true
        }
        return enteredState
    }

    val updated = state.copy(
        turnFocusActive = decision.isActive,
        turnFocusCameraActive = decision.isActive && state.turnFocusCameraActive,
        turnFocusIsHighway = if (decision.isActive) decision.isHighway else false,
        turnFocusManeuverIndex = if (decision.isActive) decision.maneuverIndex else -1,
        turnFocusTriggerDistanceMeters = if (decision.isActive) decision.triggerDistanceMeters else 0.0
    )
    _navigationState.value = updated
    return updated
}

internal fun RahYabViewModel.activateTurnFocusCameraImpl(
    expectedManeuverIndex: Int,
    isHighway: Boolean
) {
    val cur = _navigationState.value
    if (!cur.isNavigating || !cur.turnFocusActive || cur.turnFocusManeuverIndex != expectedManeuverIndex) {
        return
    }
    turnFocusPendingEnterAnim = true
    _navigationState.value = cur.copy(turnFocusCameraActive = true, turnFocusIsHighway = isHighway)

    if (_isCameraFollowing.value && !turnFocusUserPannedDuringActive) {
        val loc = cur.snappedLocation ?: return
        val s = settings.value
        turnFocusPendingEnterAnim = false
        _cameraCommand.value = CameraTargetCommand(
            lat = loc.lat,
            lon = loc.lon,
            zoom = if (isHighway) TurnFocusPlanner.FOCUS_ZOOM_HIGHWAY else TurnFocusPlanner.FOCUS_ZOOM_CITY,
            tilt = if (s.is3DView) TurnFocusPlanner.FOCUS_TILT_3D else 0.0,
            bearing = cur.snappedBearing.toDouble(),
            animationDurationMs = CarCameraProfile.FOCUS_ENTER_ANIM_MS,
            anchorLowerThird = false,
            anchorLowerQuarter = true
        )
    }
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

    // F4: Active Turn Focus camera override — suppresses speed zoom-out & keeps car in lower quarter
    if (state.turnFocusCameraActive && !turnFocusUserPannedDuringActive && mode == TravelMode.DRIVE) {
        val enterAnim = turnFocusPendingEnterAnim
        turnFocusPendingEnterAnim = false
        val animMs = when {
            enterAnim -> CarCameraProfile.FOCUS_ENTER_ANIM_MS
            state.isSimulatingDrive -> cameraAnimMs
            else -> 650
        }
        return CameraTargetCommand(
            lat = targetLoc.lat,
            lon = targetLoc.lon,
            zoom = if (state.turnFocusIsHighway) TurnFocusPlanner.FOCUS_ZOOM_HIGHWAY else TurnFocusPlanner.FOCUS_ZOOM_CITY,
            tilt = if (s.is3DView) TurnFocusPlanner.FOCUS_TILT_3D else 0.0,
            bearing = heading.toDouble(),
            animationDurationMs = animMs,
            anchorLowerThird = false,
            anchorLowerQuarter = true
        )
    }

    val exitAnim = turnFocusPendingExitAnim
    if (exitAnim) turnFocusPendingExitAnim = false

    val isLaneOverlayActive = !state.hasArrived && LaneOverlayBuilder.build(
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
        anchorLowerQuarter = false
    )
}
