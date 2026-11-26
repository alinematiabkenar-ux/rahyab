package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.i18n.AppLanguage
import com.example.i18n.stringsFor
import com.example.navigation.LatLon
import com.example.navigation.RoutePreference
import kotlinx.coroutines.launch

internal fun RahYabViewModel.setLanguageImpl(language: AppLanguage) {
    viewModelScope.launch {
        prefs.setLanguage(language)
        val available = voiceManager.checkPersianAvailability()
        if (language == AppLanguage.PERSIAN && !available && !settings.value.persianTtsNoticeShown) {
            _showPersianTtsDialog.value = true
        }
    }
}

internal fun RahYabViewModel.dismissPersianTtsNoticeImpl() {
    _showPersianTtsDialog.value = false
    viewModelScope.launch { prefs.markPersianTtsNoticeShown() }
}

internal fun RahYabViewModel.setShowTrafficLightsImpl(enabled: Boolean) {
    viewModelScope.launch {
        prefs.setShowTrafficLights(enabled)
        if (!enabled) {
            _aheadTrafficLightMeters.value = null
            if (!settings.value.alertTrafficLightEnabled) {
                _visibleTrafficLights.value = emptyList()
            }
        } else {
            val ref = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
            refreshNearbyTrafficLightsImpl(ref.lat, ref.lon, force = true)
        }
    }
}

internal fun RahYabViewModel.setAlertTrafficLightEnabledImpl(enabled: Boolean) {
    viewModelScope.launch {
        prefs.setAlertTrafficLightEnabled(enabled)
        if (!enabled && !settings.value.showTrafficLights) {
            _visibleTrafficLights.value = emptyList()
        } else if (enabled) {
            val ref = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
            refreshNearbyTrafficLightsImpl(ref.lat, ref.lon, force = true)
        }
    }
}

internal fun RahYabViewModel.evaluateTrafficLightVoiceImpl(
    lat: Double,
    lon: Double,
    headingDegrees: Float,
    isDriveMode: Boolean
) {
    val s = settings.value
    if (!isDriveMode || !s.alertTrafficLightEnabled) return
    val alert = trafficLightVoiceEvaluator.evaluate(
        userLat = lat,
        userLon = lon,
        headingDegrees = headingDegrees,
        lights = _visibleTrafficLights.value,
        isDriveMode = isDriveMode,
        enabled = s.alertTrafficLightEnabled,
        isVoiceBusy = voiceManager.isSpeakingOrQueued()
    ) ?: return
    voiceManager.speakAlert(alert, s.language, s.distanceUnit)
}

internal fun RahYabViewModel.evaluateLaneChangeCueImpl(
    state: com.example.navigation.ActiveNavigationState,
    speedMps: Float,
    maneuverToSpeak: Pair<com.example.navigation.RouteManeuver, String>?
): Boolean {
    val s = settings.value
    // F3: Single beep source — suppress old lane-change beeps, "Change lanes now", and last-chance beep when Turn Focus is ON
    if (s.turnFocusModeEnabled) return false
    val maneuver = state.currentManeuver ?: return false
    val event = laneChangeCueTracker.evaluate(
        maneuver = maneuver,
        distanceToManeuverMeters = state.distanceToNextManeuverMeters,
        speedMps = speedMps,
        travelMode = state.effectiveTravelMode,
        timing = s.laneChangeTiming,
        beepsEnabled = s.laneChangeBeepsEnabled,
        lastChanceEnabled = s.lastChanceBeepEnabled
    ) ?: return false

    val isHalfMileStage = maneuverToSpeak != null && (
        maneuverToSpeak.second == com.example.navigation.NavigationSessionController.STAGE_HW_HALF_MI ||
            maneuverToSpeak.second == com.example.navigation.NavigationSessionController.STAGE_CITY_HALF_MI
        )
    val effectiveEvent = if (isHalfMileStage && event.kind == com.example.navigation.LaneChangeCueKind.PRIMARY) {
        event.copy(mergeWithHalfMile = true)
    } else {
        event
    }

    val spoken = voiceManager.playLaneChangeCue(
        event = effectiveEvent,
        maneuver = maneuver,
        distanceMeters = state.distanceToNextManeuverMeters,
        unit = s.distanceUnit,
        language = s.language
    )
    _navigationState.value = _navigationState.value.copy(
        laneChangeCueManeuverIndex = effectiveEvent.maneuverIndex,
        laneChangeCuePulseAtMs = System.currentTimeMillis(),
        lastSpokenInstruction = spoken.ifBlank { _navigationState.value.lastSpokenInstruction }
    )
    return effectiveEvent.mergeWithHalfMile && isHalfMileStage
}

internal fun RahYabViewModel.setShowStopSignsOnMapImpl(enabled: Boolean) {
    viewModelScope.launch {
        prefs.setShowStopSignsOnMap(enabled)
        if (!enabled) {
            _visibleStopSigns.value = emptyList()
        } else {
            val ref = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
            refreshNearbyStopSignsImpl(ref.lat, ref.lon, force = true)
        }
    }
}

internal fun RahYabViewModel.refreshNearbyStopSignsImpl(lat: Double, lon: Double, force: Boolean = false) {
    if (!settings.value.showStopSignsOnMap) {
        if (_visibleStopSigns.value.isNotEmpty()) _visibleStopSigns.value = emptyList()
        return
    }
    val last = lastStopSignFetchLoc
    if (!force && last != null &&
        OfflinePlaceSearchEngine.haversineMeters(last.lat, last.lon, lat, lon) < 1_000.0
    ) {
        return
    }
    lastStopSignFetchLoc = LatLon(lat, lon)
    stopSignFetchJob?.cancel()
    stopSignFetchJob = viewModelScope.launch {
        val signs = offlineSearch.queryStopSignsInBounds(
            minLat = lat - 0.035,
            maxLat = lat + 0.035,
            minLon = lon - 0.045,
            maxLon = lon + 0.045
        )
        _visibleStopSigns.value = signs
    }
}

internal fun RahYabViewModel.setOnlineWeatherAlertsEnabledImpl(enabled: Boolean) {
    viewModelScope.launch {
        prefs.setOnlineWeatherAlertsEnabled(enabled)
        if (!enabled) {
            _cachedWeatherAlerts.value = emptyList()
        } else {
            refreshWeatherAlertsIfEligibleImpl()
        }
    }
}

internal fun RahYabViewModel.setAvoidTollsImpl(avoid: Boolean) {
    viewModelScope.launch {
        prefs.setAvoidTolls(avoid)
        if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlaceImpl()
    }
}

internal fun RahYabViewModel.setAvoidHighwaysImpl(avoid: Boolean) {
    viewModelScope.launch {
        prefs.setAvoidHighways(avoid)
        if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlaceImpl()
    }
}

internal fun RahYabViewModel.setAvoidFerriesImpl(avoid: Boolean) {
    viewModelScope.launch {
        prefs.setAvoidFerries(avoid)
        if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlaceImpl()
    }
}

internal fun RahYabViewModel.setRoutePreferenceImpl(preference: RoutePreference) {
    viewModelScope.launch {
        prefs.setRoutePreference(preference)
        if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlaceImpl()
    }
}

internal fun RahYabViewModel.saveGitHubRepositoryImpl(owner: String, repo: String) {
    viewModelScope.launch {
        val cleanedOwner = owner.trim()
        val cleanedRepo = repo.trim()
        prefs.setGitHubRepository(cleanedOwner, cleanedRepo)
        if (cleanedOwner.isNotEmpty() && cleanedRepo.isNotEmpty()) {
            val url = "https://github.com/$cleanedOwner/$cleanedRepo/releases/latest/download"
            _snackbarMessage.value = if (settings.value.language == AppLanguage.PERSIAN) {
                "مخزن گیت‌هاب ذخیره شد: $cleanedOwner/$cleanedRepo"
            } else {
                "Saved GitHub repo: $cleanedOwner/$cleanedRepo"
            }
            packManager.fetchManifest(url)
        }
    }
}

internal fun RahYabViewModel.savePackBaseUrlImpl(url: String) {
    viewModelScope.launch {
        prefs.setPackBaseUrl(url)
        val effective = url.trim().trimEnd('/').ifEmpty { settings.value.effectivePackBaseUrl }
        if (effective.isNotBlank()) {
            packManager.fetchManifest(effective)
        }
    }
}

internal fun RahYabViewModel.setDevOnlineOregonModeImpl(enabled: Boolean) {
    viewModelScope.launch {
        prefs.setDevOnlineOregonMode(enabled)
        if (enabled) {
            _cameraCommand.value = CameraTargetCommand(
                lat = RahYabViewModel.EUGENE_OR_LAT,
                lon = RahYabViewModel.EUGENE_OR_LON,
                zoom = 12.8
            )
        }
    }
}

internal fun RahYabViewModel.testVoiceGuidanceImpl() {
    voiceManager.checkPersianAvailability()
    voiceManager.speakTestSample(settings.value.language)
}

internal fun RahYabViewModel.testPersianVoiceImpl() {
    val ok = voiceManager.speakExplicitLanguageTest(AppLanguage.PERSIAN)
    if (!ok) {
        _snackbarMessage.value = stringsFor(settings.value.language).diagPersianVoiceUnavailableToast
    }
}
