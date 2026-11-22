package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.i18n.AppLanguage
import com.example.i18n.stringsFor
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
            _visibleTrafficLights.value = emptyList()
            _aheadTrafficLightMeters.value = null
        } else {
            val ref = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
            refreshNearbyTrafficLightsImpl(ref.lat, ref.lon, force = true)
        }
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
