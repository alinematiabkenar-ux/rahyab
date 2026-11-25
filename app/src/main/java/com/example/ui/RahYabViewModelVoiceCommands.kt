package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.local.ReportType
import com.example.data.local.SavedPlaceType
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PlaceSearchResult
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.stringsFor
import com.example.navigation.TravelMode
import com.example.navigation.VoiceCommand
import com.example.navigation.VoiceCommandParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * VC3: Executes parsed offline English voice commands on the phone app.
 *
 * - Runs searches strictly offline against installed state places databases.
 * - Single strong match opens route preview with a cancellable 5-second auto-start countdown.
 * - Multiple ambiguous matches open the search results list.
 * - Spoken answers and confirmations use the user's selected app language (EN or FA).
 */
internal fun RahYabViewModel.startVoiceCommandListeningImpl() {
    cancelVoiceAutoStartCountdownImpl()
    voiceCommandManager.startListening(
        onBeforeStart = { voiceManager.stopSpeaking() },
        onTranscript = { transcript -> handleVoiceTranscriptImpl(transcript) },
        onNoSpeechOrNoMatch = {
            val s = settings.value
            val msg = stringsFor(s.language).voiceNotUnderstood
            _snackbarMessage.value = msg
            voiceManager.speakCustomText(msg, s.language)
        }
    )
}

internal fun RahYabViewModel.cancelVoiceCommandListeningImpl() {
    voiceCommandManager.cancelListening()
}

internal fun RahYabViewModel.downloadOfflineEnglishModelImpl() {
    voiceCommandManager.triggerOfflineEnglishDownload()
}

internal fun RahYabViewModel.cancelVoiceAutoStartCountdownImpl() {
    voiceAutoStartJob?.cancel()
    voiceAutoStartJob = null
    _voiceAutoStartSecondsLeft.value = 0
}

internal fun RahYabViewModel.handleVoiceTranscriptImpl(transcript: String) {
    val s = settings.value
    val str = stringsFor(s.language)
    when (val cmd = VoiceCommandParser.parse(transcript)) {
        is VoiceCommand.NavigateTo -> runVoiceDestinationSearchImpl(cmd.query, pickNearest = false)
        is VoiceCommand.FindNearest -> runVoiceDestinationSearchImpl(cmd.categoryQuery, pickNearest = true)
        VoiceCommand.GoHome -> navigateSavedByTypeImpl(SavedPlaceType.HOME, str.voiceNoSavedHome)
        VoiceCommand.GoWork -> navigateSavedByTypeImpl(SavedPlaceType.WORK, str.voiceNoSavedWork)
        VoiceCommand.WhereIsMyCar -> speakParkedCarLocationImpl()
        VoiceCommand.WalkToMyCar -> {
            if (parkedCar.value == null) {
                _snackbarMessage.value = str.voiceNoParkedCar
                voiceManager.speakCustomText(str.voiceNoParkedCar, s.language)
            } else {
                startWalkToCarImpl()
            }
        }
        VoiceCommand.StopNavigation -> {
            if (_navigationState.value.isNavigating) {
                stopNavigationImpl()
                _snackbarMessage.value = str.voiceStoppedConfirm
                voiceManager.speakCustomText(str.voiceStoppedConfirm, s.language)
            } else {
                _snackbarMessage.value = str.voiceNotNavigating
                voiceManager.speakCustomText(str.voiceNotNavigating, s.language)
            }
        }
        VoiceCommand.Mute -> {
            setVoiceEnabled(false)
            _snackbarMessage.value = str.voiceMutedConfirm
        }
        VoiceCommand.Unmute -> {
            voiceManager.setVoiceEnabled(true)
            setVoiceEnabled(true)
            _snackbarMessage.value = str.voiceUnmutedConfirm
            voiceManager.speakCustomText(str.voiceUnmutedConfirm, s.language)
        }
        VoiceCommand.AskEta -> speakCurrentEtaImpl()
        VoiceCommand.RepeatInstruction -> {
            if (voiceManager.lastSpokenText.value.isNotBlank()) {
                repeatLastVoiceInstruction()
            } else {
                _snackbarMessage.value = str.voiceNotNavigating
                voiceManager.speakCustomText(str.voiceNotNavigating, s.language)
            }
        }
        VoiceCommand.ReportRoadClosed -> {
            submitUserReportImpl(ReportType.ROAD_CLOSURE)
            voiceManager.speakCustomText(str.voiceRoadClosedConfirm, s.language)
        }
        VoiceCommand.ZoomIn -> applyVoiceZoomDeltaImpl(+1.5)
        VoiceCommand.ZoomOut -> applyVoiceZoomDeltaImpl(-1.5)
        is VoiceCommand.Unknown -> {
            _snackbarMessage.value = str.voiceNotUnderstood
            voiceManager.speakCustomText(str.voiceNotUnderstood, s.language)
        }
    }
}

private fun RahYabViewModel.runVoiceDestinationSearchImpl(query: String, pickNearest: Boolean) {
    val s = settings.value
    val str = stringsFor(s.language)
    val ref = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()

    viewModelScope.launch {
        _isSearching.value = true
        val results = offlineSearch.search(query, ref.lat, ref.lon, limit = 12)
        _isSearching.value = false

        if (results.isEmpty()) {
            _snackbarMessage.value = str.voiceNoSearchMatches
            voiceManager.speakCustomText(str.voiceNoSearchMatches, s.language)
            return@launch
        }

        val strongBest: PlaceSearchResult? = when {
            pickNearest -> results.minByOrNull { it.distanceMeters }
            results.size == 1 -> results.first()
            results.first().name.equals(query.trim(), ignoreCase = true) -> results.first()
            (results[0].relevanceScore - results[1].relevanceScore) >= 25.0 -> results.first()
            else -> null
        }

        if (strongBest != null) {
            selectPlaceAndStartVoiceCountdownImpl(strongBest)
        } else {
            _searchQuery.value = query
            _searchResults.value = results
            _openSearchResultsEvent.value = System.currentTimeMillis()
        }
    }
}

private fun RahYabViewModel.navigateSavedByTypeImpl(type: SavedPlaceType, missingMsg: String) {
    val s = settings.value
    val saved = savedPlaces.value.firstOrNull { it.type.equals(type.name, ignoreCase = true) }
    if (saved == null) {
        _snackbarMessage.value = missingMsg
        voiceManager.speakCustomText(missingMsg, s.language)
        return
    }
    val place = PlaceSearchResult(
        id = "saved_${saved.id}",
        name = saved.name,
        brand = "",
        category = saved.category,
        address = saved.address,
        city = "",
        stateCode = saved.stateCode,
        postcode = "",
        lat = saved.lat,
        lon = saved.lon,
        distanceMeters = 0.0,
        relevanceScore = 100.0,
        source = "saved_${saved.type.lowercase()}",
        savedId = saved.id
    )
    selectPlaceAndStartVoiceCountdownImpl(place)
}

private fun RahYabViewModel.selectPlaceAndStartVoiceCountdownImpl(place: PlaceSearchResult) {
    if (_navigationState.value.isNavigating) {
        stopNavigationImpl()
    }
    _selectedTravelMode.value = TravelMode.DRIVE
    selectPlaceImpl(place)

    cancelVoiceAutoStartCountdownImpl()
    voiceAutoStartJob = viewModelScope.launch {
        for (sec in 5 downTo 1) {
            if (_selectedPlace.value?.id != place.id || _navigationState.value.isNavigating) {
                _voiceAutoStartSecondsLeft.value = 0
                return@launch
            }
            _voiceAutoStartSecondsLeft.value = sec
            delay(1_000L)
        }
        _voiceAutoStartSecondsLeft.value = 0
        var waitTicks = 0
        while (_isCalculatingRoute.value && waitTicks < 10) {
            delay(250L)
            waitTicks++
        }
        if (_selectedPlace.value?.id == place.id &&
            !_navigationState.value.isNavigating &&
            _candidateRoutes.value.isNotEmpty()
        ) {
            startNavigationImpl()
        }
    }
}

private fun RahYabViewModel.speakParkedCarLocationImpl() {
    val s = settings.value
    val str = stringsFor(s.language)
    val car = parkedCar.value
    if (car == null) {
        _snackbarMessage.value = str.voiceNoParkedCar
        voiceManager.speakCustomText(str.voiceNoParkedCar, s.language)
        return
    }
    val ref = getEffectiveReferenceLocation()
    val dist = OfflinePlaceSearchEngine.haversineMeters(ref.lat, ref.lon, car.lat, car.lon)
    val distStr = BilingualFormatter.formatDistance(dist, s.distanceUnit, s.language)
    val msg = if (s.language == AppLanguage.PERSIAN) {
        "خودروی شما در فاصله $distStr پارک شده است."
    } else {
        "Your car is parked $distStr away."
    }
    _snackbarMessage.value = msg
    voiceManager.speakCustomText(msg, s.language)
}

private fun RahYabViewModel.speakCurrentEtaImpl() {
    val s = settings.value
    val str = stringsFor(s.language)
    val nav = _navigationState.value
    if (!nav.isNavigating) {
        _snackbarMessage.value = str.voiceNotNavigating
        voiceManager.speakCustomText(str.voiceNotNavigating, s.language)
        return
    }
    val arrivalTime = BilingualFormatter.formatArrivalTime(nav.remainingTimeSeconds, s.language)
    val duration = BilingualFormatter.formatDuration(nav.remainingTimeSeconds, s.language)
    val msg = if (s.language == AppLanguage.PERSIAN) {
        "${str.voiceEtaPrefix} $arrivalTime، حدود $duration باقی مانده است."
    } else {
        "${str.voiceEtaPrefix} $arrivalTime, about $duration remaining."
    }
    _snackbarMessage.value = msg
    voiceManager.speakCustomText(msg, s.language)
}

private fun RahYabViewModel.applyVoiceZoomDeltaImpl(delta: Double) {
    val nav = _navigationState.value
    val ref = nav.snappedLocation ?: getEffectiveReferenceLocation()
    val baseZoom = _cameraCommand.value?.zoom ?: if (nav.isNavigating) 16.5 else 14.5
    val nextZoom = (baseZoom + delta).coerceIn(4.0, 19.0)
    _cameraCommand.value = CameraTargetCommand(
        lat = ref.lat,
        lon = ref.lon,
        zoom = nextZoom,
        tilt = if (settings.value.is3DView && nav.isNavigating) 45.0 else 0.0,
        bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0,
        animationDurationMs = 350,
        anchorLowerThird = nav.isNavigating
    )
}
