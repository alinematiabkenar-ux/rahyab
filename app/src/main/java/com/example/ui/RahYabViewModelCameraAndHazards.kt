package com.example.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.UserSettings
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.MapThemeMode
import com.example.navigation.CameraFollowController
import com.example.navigation.CompassTracker
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.LocationTracker
import com.example.navigation.SolarNightCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

data class CameraTargetCommand(
    val lat: Double,
    val lon: Double,
    val zoom: Double = 14.5,
    val tilt: Double = 0.0,
    val bearing: Double = 0.0,
    val animationDurationMs: Int = 500,
    val anchorLowerThird: Boolean = false,
    val nonce: Long = System.currentTimeMillis()
)

internal fun RahYabViewModel.initializeViewModel() {
    viewModelScope.launch {
        packManager.auditInstalledPacks()
    }

    // Track build version alignment across installed packs and refresh nearby cameras
    viewModelScope.launch {
        packsChangedTick.collect {
            _mismatchedBuildVersions.value = packManager.getMismatchedBuildVersions()
            val ref = getEffectiveReferenceLocation()
            refreshNearbyCamerasImpl(ref.lat, ref.lon, force = true)
            refreshNearbyTrafficLightsImpl(ref.lat, ref.lon, force = true)
            refreshNearbySpeedBumpsImpl(ref.lat, ref.lon, force = true)
        }
    }

    // Poll NWS severe driving weather alerts every 15 minutes when online and enabled
    viewModelScope.launch {
        while (true) {
            refreshWeatherAlertsIfEligibleImpl()
            delay(15 * 60_000L)
        }
    }
    viewModelScope.launch {
        networkState.collect { net ->
            if (net.isOnline) {
                refreshWeatherAlertsIfEligibleImpl()
                checkDailyMapUpdatesIfDueImpl()
            }
        }
    }

    // Sync voice language & enabled state with settings + trigger one-time Persian TTS notice
    viewModelScope.launch {
        settings.collect { s ->
            voiceManager.setUseEnglishVoiceInPersianMode(s.useEnglishVoiceInPersianMode)
            voiceManager.setLanguage(s.language)
            voiceManager.setVoiceEnabled(s.voiceGuidanceEnabled)
            if (s.language == AppLanguage.PERSIAN && !s.persianTtsNoticeShown && !s.useEnglishVoiceInPersianMode) {
                val available = voiceManager.checkPersianAvailability()
                if (!available) {
                    _showPersianTtsDialog.value = true
                }
            }
        }
    }

    // Auto-purge expired user driving reports every 60 seconds
    viewModelScope.launch {
        while (true) {
            val now = System.currentTimeMillis()
            _nowTick.value = now
            runCatching { userReportDao.purgeExpired(now) }
            delay(60_000L)
        }
    }

    // Start hardware compass sensor (automatically stops in background when not navigating)
    compassTracker.startIfActive()

    // Smoothly combine compass (< 3 mph) and GPS bearing (> 3 mph) for position arrow & heading-up map
    viewModelScope.launch {
        combine(
            currentGpsFix,
            compassTracker.headingDegrees,
            _navigationState
        ) { fix, compassHeading, nav ->
            when {
                nav.isNavigating && nav.isSimulatingDrive -> nav.snappedBearing
                fix != null && fix.speedMps > LocationTracker.MIN_BEARING_SPEED_MPS && fix.hasBearing -> {
                    if (nav.isNavigating && !nav.isOffRoute) nav.snappedBearing else fix.bearing
                }
                compassHeading != null -> compassHeading
                fix != null && fix.hasBearing -> fix.bearing
                else -> null
            }
        }.collect { targetHeading ->
            if (targetHeading != null) {
                val normalized = CompassTracker.normalizeAngle(targetHeading)
                val nowMs = SystemClock.elapsedRealtime()
                if (!hasHeadingSample) {
                    hasHeadingSample = true
                    lastHeadingEmitElapsedMs = nowMs
                    _userHeadingDegrees.value = normalized
                } else if (nowMs - lastHeadingEmitElapsedMs >= 100L) {
                    val blended = CompassTracker.lerpAngle(
                        _userHeadingDegrees.value,
                        normalized,
                        0.45f
                    )
                    val delta = abs(
                        CompassTracker.shortestAngleDelta(_userHeadingDegrees.value, blended)
                    )
                    if (delta > 2.0f) {
                        lastHeadingEmitElapsedMs = nowMs
                        _userHeadingDegrees.value = blended
                    }
                }
            }
        }
    }

    // Process live GPS updates during active navigation AND free-drive (completely ignored while simulating)
    viewModelScope.launch {
        currentGpsFix.collect { fix ->
            if (_navigationState.value.isSimulatingDrive) return@collect
            if (fix != null) {
                compassTracker.updateLocationForDeclination(fix.lat, fix.lon)
            }
            if (fix != null && _navigationState.value.isNavigating) {
                lastGpsReceivedMs = System.currentTimeMillis()
                handleNavigationGpsFixImpl(fix)
            } else if (fix != null) {
                handleFreeDriveGpsFixImpl(fix)
            }
        }
    }
}

internal fun RahYabViewModel.refreshNearbyCamerasImpl(lat: Double, lon: Double, force: Boolean = false) {
    val last = lastCameraFetchLoc
    if (!force && last != null &&
        OfflinePlaceSearchEngine.haversineMeters(last.lat, last.lon, lat, lon) < 2_000.0
    ) {
        return
    }
    lastCameraFetchLoc = LatLon(lat, lon)
    cameraFetchJob?.cancel()
    cameraFetchJob = viewModelScope.launch {
        val nearby = offlineSearch.queryCamerasInBounds(
            minLat = lat - 0.12,
            maxLat = lat + 0.12,
            minLon = lon - 0.14,
            maxLon = lon + 0.14
        )
        val routeCams = if (_navigationState.value.isNavigating) {
            cachedDbHazards.filter {
                it.type.equals("speed_camera", ignoreCase = true) ||
                    it.type.equals("red_light_camera", ignoreCase = true)
            }
        } else {
            emptyList()
        }
        _visibleCameras.value = mergeUniqueCamerasImpl(nearby, routeCams)
    }
}

internal fun mergeUniqueCamerasImpl(
    primary: List<RoadHazardPoint>,
    secondary: List<RoadHazardPoint>
): List<RoadHazardPoint> {
    if (secondary.isEmpty()) return primary
    if (primary.isEmpty()) return secondary
    val seen = HashSet<String>(primary.size + secondary.size)
    val merged = ArrayList<RoadHazardPoint>(primary.size + secondary.size)
    for (cam in primary) {
        val key = cam.osmId?.takeIf { it.isNotBlank() } ?: "${cam.type}_${cam.lat}_${cam.lon}"
        if (seen.add(key)) merged.add(cam)
    }
    for (cam in secondary) {
        val key = cam.osmId?.takeIf { it.isNotBlank() } ?: "${cam.type}_${cam.lat}_${cam.lon}"
        if (seen.add(key)) merged.add(cam)
    }
    return merged
}

internal fun RahYabViewModel.refreshNearbyTrafficLightsImpl(lat: Double, lon: Double, force: Boolean = false) {
    if (!settings.value.showTrafficLights) {
        if (_visibleTrafficLights.value.isNotEmpty()) _visibleTrafficLights.value = emptyList()
        _aheadTrafficLightMeters.value = null
        return
    }
    val last = lastTrafficLightFetchLoc
    if (!force && last != null &&
        OfflinePlaceSearchEngine.haversineMeters(last.lat, last.lon, lat, lon) < 1_200.0
    ) {
        return
    }
    lastTrafficLightFetchLoc = LatLon(lat, lon)
    trafficLightFetchJob?.cancel()
    trafficLightFetchJob = viewModelScope.launch {
        val lights = offlineSearch.queryTrafficSignalsInBounds(
            minLat = lat - 0.045,
            maxLat = lat + 0.045,
            minLon = lon - 0.055,
            maxLon = lon + 0.055
        )
        _visibleTrafficLights.value = lights
    }
}

internal fun RahYabViewModel.handleFreeDriveGpsFixImpl(fix: GpsFix) {
    val s = settings.value
    refreshNearbyCamerasImpl(fix.lat, fix.lon)
    refreshNearbyTrafficLightsImpl(fix.lat, fix.lon)

    // Visual-only approaching traffic light within ~300 ft (no voice)
    _aheadTrafficLightMeters.value = if (s.showTrafficLights) {
        navController.findAheadTrafficLightMeters(fix, _visibleTrafficLights.value)
    } else {
        null
    }

    // Evaluate speed cameras & red light cameras ahead during free driving
    val (freeDriveAlert, alertToSpeak) = navController.evaluateFreeDriveCameraAlert(
        fix = fix,
        cameras = _visibleCameras.value,
        settings = s
    )
    _freeDriveAheadAlert.value = freeDriveAlert
    if (alertToSpeak != null) {
        voiceManager.playCameraAlertSound()
        voiceManager.speakAlert(alertToSpeak, s.language, s.distanceUnit)
    }
    evaluateSpeedBumpForFreeDriveImpl(fix)

    val isPlaceOrPreviewOpen = _selectedPlace.value != null || _candidateRoutes.value.isNotEmpty()
    val nowElapsed = SystemClock.elapsedRealtime()
    var forceImmediate = false
    if (cameraFollowController.shouldAutoRecenter(
            isCameraFollowing = _isCameraFollowing.value,
            isPlaceOrPreviewOpen = isPlaceOrPreviewOpen,
            speedMps = fix.speedMps,
            nowMs = nowElapsed
        )
    ) {
        _isCameraFollowing.value = true
        forceImmediate = true
    }
    if (!_isCameraFollowing.value || isPlaceOrPreviewOpen) return

    val heading = if (fix.speedMps > LocationTracker.MIN_BEARING_SPEED_MPS && fix.hasBearing) {
        fix.bearing
    } else {
        _userHeadingDegrees.value
    }
    val update = cameraFollowController.computeFollowCameraUpdate(
        lat = fix.lat,
        lon = fix.lon,
        speedMps = fix.speedMps,
        headingDegrees = heading,
        isHeadingUpMode = _isHeadingUpMode.value,
        is3DView = s.is3DView,
        isNavigating = false,
        simulationSpeedMultiplier = 1,
        forceImmediate = forceImmediate
    ) ?: return

    _cameraCommand.value = CameraTargetCommand(
        lat = update.lat,
        lon = update.lon,
        zoom = update.zoom,
        tilt = update.tilt,
        bearing = update.bearing,
        animationDurationMs = update.animationDurationMs,
        anchorLowerThird = update.anchorLowerThird
    )
}

internal fun resolveEffectiveNightModeImpl(s: UserSettings, fix: GpsFix?): Boolean {
    return when (s.themeMode) {
        MapThemeMode.DAY -> false
        MapThemeMode.NIGHT -> true
        MapThemeMode.AUTO -> {
            val lat = fix?.lat ?: RahYabViewModel.EUGENE_OR_LAT
            val lon = fix?.lon ?: RahYabViewModel.EUGENE_OR_LON
            SolarNightCalculator.isNightTime(lat, lon)
        }
    }
}

internal fun RahYabViewModel.getEffectiveReferenceLocationImpl(): LatLon {
    val fix = currentGpsFix.value
    if (fix != null) return LatLon(fix.lat, fix.lon)
    return LatLon(RahYabViewModel.EUGENE_OR_LAT, RahYabViewModel.EUGENE_OR_LON)
}

internal fun RahYabViewModel.pauseCameraFollowImpl() {
    _isCameraFollowing.value = false
    val touchMs = SystemClock.elapsedRealtime()
    cameraFollowController.recordUserMapGesture(touchMs)
    scheduleAutoRecenterCheckImpl()
}

internal fun RahYabViewModel.scheduleAutoRecenterCheckImpl() {
    autoRecenterJob?.cancel()
    autoRecenterJob = viewModelScope.launch {
        delay(CameraFollowController.AUTO_RECENTER_DELAY_MS)
        val nav = _navigationState.value
        val isPlaceOrPreviewOpen = !nav.isNavigating &&
            (_selectedPlace.value != null || _candidateRoutes.value.isNotEmpty())
        val speedMps = if (nav.isNavigating) nav.currentSpeedMps else (currentGpsFix.value?.speedMps ?: 0f)
        if (cameraFollowController.shouldAutoRecenter(
                isCameraFollowing = _isCameraFollowing.value,
                isPlaceOrPreviewOpen = isPlaceOrPreviewOpen,
                speedMps = speedMps,
                nowMs = SystemClock.elapsedRealtime()
            )
        ) {
            recenterCameraImpl()
        }
    }
}

internal fun RahYabViewModel.toggle3DViewImpl() {
    val next = !settings.value.is3DView
    viewModelScope.launch { prefs.set3DView(next) }
    emitImmediateFollowCameraImpl(is3DViewOverride = next)
}

internal fun RahYabViewModel.toggleCompassHeadingModeImpl() {
    val nextHeadingUp = !_isHeadingUpMode.value
    _isHeadingUpMode.value = nextHeadingUp
    if (nextHeadingUp) {
        autoRecenterJob?.cancel()
        _isCameraFollowing.value = true
    }
    emitImmediateFollowCameraImpl()
}

internal fun RahYabViewModel.recenterCameraImpl() {
    autoRecenterJob?.cancel()
    _isCameraFollowing.value = true
    emitImmediateFollowCameraImpl()
}

internal fun RahYabViewModel.emitImmediateFollowCameraImpl(is3DViewOverride: Boolean? = null) {
    val nav = _navigationState.value
    val fix = currentGpsFix.value
    val ref = nav.snappedLocation ?: getEffectiveReferenceLocation()
    val s = settings.value
    val speedMps = if (nav.isNavigating) nav.currentSpeedMps else (fix?.speedMps ?: 0f)
    val heading = if (nav.isNavigating && (nav.isSimulatingDrive || speedMps > LocationTracker.MIN_BEARING_SPEED_MPS)) {
        nav.snappedBearing
    } else if (!nav.isNavigating && fix != null && fix.speedMps > LocationTracker.MIN_BEARING_SPEED_MPS && fix.hasBearing) {
        fix.bearing
    } else {
        _userHeadingDegrees.value
    }
    val update = cameraFollowController.computeFollowCameraUpdate(
        lat = ref.lat,
        lon = ref.lon,
        speedMps = speedMps,
        headingDegrees = heading,
        isHeadingUpMode = _isHeadingUpMode.value,
        is3DView = is3DViewOverride ?: s.is3DView,
        isNavigating = nav.isNavigating,
        simulationSpeedMultiplier = if (nav.isSimulatingDrive) _simulationSpeedMultiplier.value else 1,
        forceImmediate = true
    ) ?: return
    _cameraCommand.value = CameraTargetCommand(
        lat = update.lat,
        lon = update.lon,
        zoom = update.zoom,
        tilt = update.tilt,
        bearing = update.bearing,
        animationDurationMs = update.animationDurationMs,
        anchorLowerThird = update.anchorLowerThird
    )
}
