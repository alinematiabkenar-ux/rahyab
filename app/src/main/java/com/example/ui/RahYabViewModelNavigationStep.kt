package com.example.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.example.i18n.BilingualFormatter
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.LocationTracker
import com.example.navigation.ManeuverType
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.TravelMode
import com.example.service.NavigationForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun RahYabViewModel.handleNavigationGpsFixImpl(fix: GpsFix) {
    val s = settings.value
    val result = navController.processGpsFix(
        currentState = _navigationState.value,
        fix = fix,
        dbHazards = cachedDbHazards,
        userReports = activeUserReports.value,
        unit = s.distanceUnit,
        language = s.language,
        speedAudioEnabled = s.speedWarningAudioEnabled,
        settings = s,
        fraCrossings = cachedFraCrossings,
        hpmsFallbackSpeedMph = hpmsFallbackCache.cachedSpeedMph
    )
    applyNavigationStepResultImpl(
        state = result.state,
        shouldReroute = result.shouldReroute,
        maneuverToSpeak = result.maneuverToSpeak,
        alertToSpeak = result.alertToSpeak,
        triggerOverspeedChime = result.triggerOverspeedChime,
        fallbackLoc = LatLon(fix.lat, fix.lon),
        speedMps = fix.speedMps,
        cameraAnimMs = 450,
        rerouteSourceFix = fix
    )
}

internal fun RahYabViewModel.applyNavigationStepResultImpl(
    state: ActiveNavigationState,
    shouldReroute: Boolean,
    maneuverToSpeak: Pair<RouteManeuver, String>?,
    alertToSpeak: AheadAlert?,
    triggerOverspeedChime: Boolean,
    fallbackLoc: LatLon?,
    speedMps: Float,
    cameraAnimMs: Int,
    rerouteSourceFix: GpsFix? = null
) {
    val s = settings.value
    val wasArrived = _navigationState.value.hasArrived
    val mode = state.effectiveTravelMode
    val isDriveMode = mode == TravelMode.DRIVE
    val speedDropAhead = if (isDriveMode && state.isNavigating && !state.hasArrived) {
        com.example.navigation.SpeedLimitChangeAhead.findNextDrop(
            route = state.route,
            snappedShapeIndex = state.snappedShapeIndex,
            currentSpeedLimitMph = state.currentSpeedLimitMph
        )
    } else null
    val visualDrop = speedDropAhead?.takeIf {
        it.distanceMeters <= com.example.navigation.SpeedLimitChangeAhead.VISUAL_SIGN_WINDOW_METERS
    }
    _navigationState.value = state.copy(
        upcomingSpeedLimitMph = visualDrop?.newLimitMph,
        upcomingSpeedLimitDistanceMeters = visualDrop?.distanceMeters
    )

    // Refresh nearby traffic lights & check visual-only ahead indicator (~300 ft, no voice)
    val navPos = state.snappedLocation ?: fallbackLoc
    if (isDriveMode && navPos != null && state.isNavigating && !state.hasArrived &&
        state.speedLimitSource != com.example.navigation.HpmsSpeedFallbackCache.SOURCE_OSM
    ) {
        val heading = state.snappedBearing.takeIf { it.isFinite() }
        hpmsFallbackCache.maybeRefreshAsync(viewModelScope, System.currentTimeMillis(), navPos.lat, navPos.lon) {
            app.extrasDbReader.lookupHpmsSpeedLimitMph(navPos.lat, navPos.lon, heading)
        }
    }
    if (navPos != null && state.isNavigating && !state.hasArrived && s.showStopSignsOnMap) {
        refreshNearbyStopSignsImpl(navPos.lat, navPos.lon)
    }
    if (isDriveMode && navPos != null && (s.showTrafficLights || s.alertTrafficLightEnabled) && state.isNavigating && !state.hasArrived) {
        refreshNearbyTrafficLightsImpl(navPos.lat, navPos.lon)
        if (s.showTrafficLights) {
            val evalFix = GpsFix(
                lat = navPos.lat,
                lon = navPos.lon,
                bearing = state.snappedBearing,
                hasBearing = true,
                speedMps = speedMps,
                accuracyMeters = 5f,
                timestampMs = System.currentTimeMillis()
            )
            _aheadTrafficLightMeters.value = navController.findAheadTrafficLightMeters(
                evalFix,
                _visibleTrafficLights.value
            )
        } else {
            _aheadTrafficLightMeters.value = null
        }
    } else {
        _aheadTrafficLightMeters.value = null
    }

    if (triggerOverspeedChime && isDriveMode) {
        voiceManager.playOverspeedChime()
    }

    if (state.hasArrived && !wasArrived) {
        onNavigationArrivedForParkingImpl(isWalkingMode = !isDriveMode)
        val spoken = if (mode == TravelMode.WALK) {
            triggerWalkingArrivalVibrationImpl()
            if (_selectedPlace.value?.id == "parked_car") {
                voiceManager.speakCustomText(
                    com.example.i18n.stringsFor(s.language).parkedCarArrivedVoice,
                    s.language
                )
            } else {
                val arrivalManeuverType = state.route?.maneuvers?.lastOrNull()?.type ?: ManeuverType.ARRIVE
                voiceManager.speakArrival(arrivalManeuverType, state.destinationName, s.language)
            }
        } else {
            val arrivalManeuverType = state.route?.maneuvers?.lastOrNull()?.type ?: ManeuverType.ARRIVE
            voiceManager.speakArrival(arrivalManeuverType, state.destinationName, s.language)
        }
        _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
        if (isDriveMode && _originalFinalDestination.value != null) {
            onArrivedAtChosenParkingImpl()
        } else {
            arrivalEndJob?.cancel()
            arrivalEndJob = viewModelScope.launch {
                delay(6000L)
                if (_navigationState.value.hasArrived) {
                    stopNavigation()
                }
            }
        }
    } else {
        val mergedWithLaneCue = if (isDriveMode) {
            evaluateLaneChangeCueImpl(state, speedMps, maneuverToSpeak)
        } else {
            false
        }
        if (!mergedWithLaneCue) {
            maneuverToSpeak?.let { (maneuver, stage) ->
                val spoken = voiceManager.speakManeuver(
                    maneuver = maneuver,
                    distanceMeters = state.distanceToNextManeuverMeters,
                    unit = s.distanceUnit,
                    language = s.language,
                    announcementStage = stage,
                    includeLaneGuidance = isDriveMode && maneuver.lanes.isNotEmpty()
                )
                _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
            }
        }
        if (mode != TravelMode.WALK) {
            alertToSpeak?.let { alert ->
                if (alert.kind == RoadAlertKind.SPEED_LIMIT_DROP) return@let
                if (isDriveMode && (alert.kind == RoadAlertKind.SPEED_CAMERA || alert.kind == RoadAlertKind.RED_LIGHT_CAMERA)) {
                    voiceManager.playCameraAlertSound()
                }
                voiceManager.speakAlert(alert, s.language, s.distanceUnit)
            }
        }
        if (isDriveMode) {
            speedLimitDropTracker.checkVoiceTrigger(
                drop = speedDropAhead,
                speedMps = speedMps,
                travelMode = mode,
                enabled = s.alertSpeedDropEnabled
            )?.let { triggered ->
                val phrase = com.example.navigation.SpeedLimitDropVoiceTracker.formatVoicePhrase(
                    triggered.newLimitMph, s.distanceUnit, s.language
                )
                voiceManager.speakAfterCurrentSpeech(phrase, s.language)
            }
            evaluateSpeedBumpForNavigationImpl(state, fallbackLoc, speedMps)
            maybeSearchParkingNearActiveDestinationImpl(state)
            if (navPos != null && maneuverToSpeak == null && alertToSpeak == null) {
                evaluateTrafficLightVoiceImpl(navPos.lat, navPos.lon, state.snappedBearing, isDriveMode = true)
            }
        }
    }

    // Update persistent foreground notification directly via NotificationManagerCompat.notify()
    val currentManeuver = state.currentManeuver
    if (currentManeuver != null && state.isNavigating) {
        val distStr = BilingualFormatter.formatDistance(
            state.distanceToNextManeuverMeters,
            s.distanceUnit,
            s.language
        )
        val etaStr = BilingualFormatter.formatDuration(state.remainingTimeSeconds, s.language)
        NavigationForegroundService.updateNotification(
            context = app,
            title = formatModeNotificationTitle(mode, distStr, etaStr, s.language),
            body = currentManeuver.streetNames.firstOrNull()?.ifBlank { currentManeuver.instructionEn }
                ?: currentManeuver.instructionEn
        )
    }

    // Follow driving camera unless user manually panned/zoomed away (or auto-recenter triggers after 10s > 10 mph)
    val nowElapsed = SystemClock.elapsedRealtime()
    var forceRecenterNow = false
    if (cameraFollowController.shouldAutoRecenter(
            isCameraFollowing = _isCameraFollowing.value,
            isPlaceOrPreviewOpen = false,
            speedMps = speedMps,
            nowMs = nowElapsed
        )
    ) {
        _isCameraFollowing.value = true
        forceRecenterNow = true
    }
    if (_isCameraFollowing.value) {
        val targetLoc = state.snappedLocation ?: fallbackLoc
        if (targetLoc != null) {
            val heading = if (speedMps > LocationTracker.MIN_BEARING_SPEED_MPS || state.isSimulatingDrive) {
                state.snappedBearing
            } else {
                _userHeadingDegrees.value
            }
            val simMultiplier = if (state.isSimulatingDrive) _simulationSpeedMultiplier.value else 1
            val isLaneOverlayActive = !state.hasArrived && com.example.map.LaneOverlayBuilder.build(
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
                forceImmediate = forceRecenterNow,
                isWalkingMode = state.isWalkingMode,
                travelMode = mode,
                minZoomFloor = if (isLaneOverlayActive) 16.5 else null
            )
            if (update != null) {
                _cameraCommand.value = CameraTargetCommand(
                    lat = update.lat,
                    lon = update.lon,
                    zoom = update.zoom,
                    tilt = update.tilt,
                    bearing = update.bearing,
                    animationDurationMs = if (state.isSimulatingDrive) cameraAnimMs else update.animationDurationMs,
                    anchorLowerThird = update.anchorLowerThird
                )
            }
        }
    }

    if (shouldReroute) {
        val fixForReroute = rerouteSourceFix ?: state.snappedLocation?.let { loc ->
            GpsFix(
                lat = loc.lat,
                lon = loc.lon,
                bearing = state.snappedBearing,
                speedMps = speedMps,
                accuracyMeters = 5f,
                timestampMs = System.currentTimeMillis()
            )
        }
        if (fixForReroute != null) {
            triggerAutomaticRerouteImpl(fixForReroute)
        }
    }
}

internal fun RahYabViewModel.triggerAutomaticRerouteImpl(fix: GpsFix) {
    val dest = _selectedPlace.value ?: return
    val s = settings.value
    val activeMode = _navigationState.value.effectiveTravelMode
    val routingHeading = if (activeMode == TravelMode.DRIVE) {
        LocationTracker.resolveRoutingHeadingDegrees(fix)
    } else {
        null
    }
    voiceManager.speakRerouting(s.language)
    viewModelScope.launch {
        val res = routingEngine.calculateRoutes(
            origin = LatLon(fix.lat, fix.lon),
            destination = LatLon(dest.lat, dest.lon),
            options = buildCurrentRouteOptionsImpl(s, activeMode),
            headingDegrees = routingHeading,
            allowDevOnlineFallback = s.devOnlineOregonMode
        )
        res.onSuccess { routes ->
            val newRoute = routes.firstOrNull() ?: return@onSuccess
            navController.reset()
            trafficLightVoiceEvaluator.reset()
            laneChangeCueTracker.reset()
            speedLimitDropTracker.reset()
            voiceManager.resetDeduplication()
            val spoken = voiceManager.speakStartInstruction(newRoute, s.language)
            if (spoken.isNotBlank()) {
                navController.markExternalPromptSpoken()
            }
            _navigationState.value = _navigationState.value.copy(
                route = newRoute,
                travelMode = activeMode,
                isWalkingMode = (activeMode == TravelMode.WALK),
                currentManeuverIndex = 0,
                snappedShapeIndex = 0,
                isOffRoute = false,
                isRerouting = false,
                remainingDistanceMeters = newRoute.distanceMeters,
                remainingTimeSeconds = newRoute.durationSeconds,
                dismissedJunctionManeuverIndex = -1,
                lastSpokenInstruction = spoken.ifBlank { _navigationState.value.lastSpokenInstruction }
            )
            loadFraCrossingsForRouteImpl(newRoute, cachedDbHazards)
            // Keep the same running foreground service alive during reroute — only update notification
            val firstManeuver = newRoute.maneuvers.firstOrNull()
            val etaStr = BilingualFormatter.formatDuration(newRoute.durationSeconds, s.language)
            NavigationForegroundService.updateNotification(
                context = app,
                title = formatModeNotificationTitle(activeMode, dest.name, etaStr, s.language),
                body = firstManeuver?.streetNames?.firstOrNull()?.ifBlank { firstManeuver.instructionEn }
                    ?: firstManeuver?.instructionEn
                    ?: dest.name,
                force = true
            )
        }.onFailure {
            _navigationState.value = _navigationState.value.copy(isRerouting = false)
        }
    }
}
