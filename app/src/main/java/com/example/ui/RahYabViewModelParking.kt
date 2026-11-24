package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.local.ParkedCarEntity
import com.example.data.local.SavedPlaceType
import com.example.i18n.stringsFor
import com.example.navigation.ParkedSpotCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * F4.1–F4.2 Parked Car ViewModel integration:
 * Observes live GPS fixes (non-simulated) for auto-save rules, handles navigation arrival,
 * Bluetooth ACL disconnect, manual "I parked here", note editing, and clearing.
 */
internal fun RahYabViewModel.initializeParkingSupportImpl() {
    viewModelScope.launch {
        settings.collect { s ->
            if (s.carBluetoothDeviceAddress.isNotBlank()) {
                carBluetoothReceiver.register(app)
            } else {
                carBluetoothReceiver.unregister(app)
            }
        }
    }
    viewModelScope.launch {
        currentGpsFix.collect { fix ->
            if (fix == null || _navigationState.value.isSimulatingDrive) return@collect
            val nav = _navigationState.value
            val home = findHomeSavedPlace()
            val candidate = parkedCarManager.onGpsFix(
                fix = fix,
                isNavigating = nav.isNavigating,
                isWalkingMode = nav.effectiveTravelMode != com.example.navigation.TravelMode.DRIVE,
                homeLat = home?.lat,
                homeLon = home?.lon,
                skipAtHomeEnabled = settings.value.skipParkingAtHome
            )
            if (candidate != null) {
                persistParkedCandidateImpl(candidate, showToast = false)
            }
        }
    }
}

internal fun RahYabViewModel.onNavigationArrivedForParkingImpl(isWalkingMode: Boolean) {
    if (isWalkingMode || _navigationState.value.effectiveTravelMode != com.example.navigation.TravelMode.DRIVE || _navigationState.value.isSimulatingDrive) return
    val home = findHomeSavedPlace()
    val candidate = parkedCarManager.onNavigationArrived(
        currentFix = currentGpsFix.value,
        homeLat = home?.lat,
        homeLon = home?.lon,
        skipAtHomeEnabled = settings.value.skipParkingAtHome,
        isWalkingMode = isWalkingMode
    )
    if (candidate != null) {
        persistParkedCandidateImpl(candidate, showToast = false)
    }
}

internal fun RahYabViewModel.onCarBluetoothDisconnectedImpl() {
    val home = findHomeSavedPlace()
    val candidate = parkedCarManager.onCarBluetoothDisconnected(
        nowMs = System.currentTimeMillis(),
        homeLat = home?.lat,
        homeLon = home?.lon,
        skipAtHomeEnabled = settings.value.skipParkingAtHome
    )
    if (candidate != null) {
        persistParkedCandidateImpl(candidate, showToast = false)
    }
}

internal fun RahYabViewModel.parkCarHereManuallyImpl() {
    val candidate = parkedCarManager.buildManualParkedSpot(
        currentFix = currentGpsFix.value,
        fallbackLocation = getEffectiveReferenceLocation()
    )
    persistParkedCandidateImpl(candidate, showToast = true)
}

internal fun RahYabViewModel.updateParkedCarNoteImpl(note: String) {
    viewModelScope.launch(Dispatchers.IO) {
        parkedCarDao.updateNote(note.trim().take(120))
    }
}

internal fun RahYabViewModel.clearParkedCarImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        parkedCarDao.clear()
    }
    _snackbarMessage.value = stringsFor(settings.value.language).parkedCarClearedToast
}

private fun RahYabViewModel.findHomeSavedPlace() =
    savedPlaces.value.firstOrNull { it.type == SavedPlaceType.HOME.name }

private fun RahYabViewModel.persistParkedCandidateImpl(
    candidate: ParkedSpotCandidate,
    showToast: Boolean
) {
    viewModelScope.launch(Dispatchers.IO) {
        parkedCarDao.upsert(
            ParkedCarEntity(
                id = 1,
                lat = candidate.lat,
                lon = candidate.lon,
                accuracyMeters = candidate.accuracyMeters,
                parkedAtEpochMs = candidate.timestampMs,
                note = "",
                source = candidate.source
            )
        )
    }
    if (showToast) {
        _snackbarMessage.value = stringsFor(settings.value.language).parkedCarSavedToast
    }
}

internal fun RahYabViewModel.startWalkToCarImpl() {
    val spot = parkedCar.value ?: return
    val s = settings.value
    val str = stringsFor(s.language)
    val origin = getEffectiveReferenceLocation()
    val dist = com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(
        origin.lat,
        origin.lon,
        spot.lat,
        spot.lon
    )
    val parkedPlace = com.example.data.search.PlaceSearchResult(
        id = "parked_car",
        name = str.parkedCarLabel,
        brand = "",
        category = "parking",
        address = spot.note,
        city = "",
        stateCode = currentLocationStateCode.value.orEmpty(),
        postcode = "",
        lat = spot.lat,
        lon = spot.lon,
        distanceMeters = dist,
        relevanceScore = 1.0,
        source = "parked_car"
    )
    _selectedPlace.value = parkedPlace

    viewModelScope.launch {
        _isCalculatingRoute.value = true
        _routeError.value = null
        val res = routingEngine.calculateRoutes(
            origin = origin,
            destination = com.example.navigation.LatLon(spot.lat, spot.lon),
            options = com.example.navigation.RouteOptions(isPedestrian = true),
            headingDegrees = null,
            allowDevOnlineFallback = s.devOnlineOregonMode
        )
        _isCalculatingRoute.value = false
        res.onSuccess { routes ->
            val route = routes.firstOrNull() ?: return@onSuccess
            _candidateRoutes.value = listOf(route)
            _selectedRouteIndex.value = 0
            startWalkingNavigationSessionImpl(route, parkedPlace)
        }.onFailure { err ->
            _snackbarMessage.value = err.message ?: str.missingRoutingTiles
        }
    }
}

private fun RahYabViewModel.startWalkingNavigationSessionImpl(
    route: com.example.navigation.OfflineRoute,
    dest: com.example.data.search.PlaceSearchResult
) {
    val s = settings.value
    stopInternalNavJobsImpl()
    navController.reset()
    speedBumpEvaluator.reset()
    voiceManager.resetDeduplication()
    locationTracker.setNavigationMode(true)
    compassTracker.setNavigationMode(true)
    _isCameraFollowing.value = true
    lastGpsReceivedMs = System.currentTimeMillis()
    _freeDriveAheadAlert.value = null
    _aheadTrafficLightMeters.value = null

    val initialManeuver = route.maneuvers.firstOrNull()
    val startLoc = route.shape.firstOrNull() ?: getEffectiveReferenceLocation()
    val initialBearing = if (route.shape.size >= 2) {
        com.example.navigation.ValhallaResponseParser.bearingDegrees(route.shape[0], route.shape[1]).toFloat()
    } else {
        _userHeadingDegrees.value
    }

    _navigationState.value = com.example.navigation.ActiveNavigationState(
        isNavigating = true,
        isWalkingMode = true,
        travelMode = com.example.navigation.TravelMode.WALK,
        route = route,
        destinationName = dest.name,
        destinationAddress = dest.address,
        currentManeuverIndex = 0,
        distanceToNextManeuverMeters = initialManeuver?.lengthMeters ?: route.distanceMeters,
        timeToNextManeuverSeconds = initialManeuver?.timeSeconds ?: route.durationSeconds,
        remainingDistanceMeters = route.distanceMeters,
        remainingTimeSeconds = route.durationSeconds,
        snappedLocation = startLoc,
        snappedBearing = initialBearing,
        currentSpeedLimitMph = null,
        isSimulatingDrive = false,
        showLaneStrip = false,
        showJunctionPreview = false
    )

    val etaText = com.example.i18n.BilingualFormatter.formatDuration(route.durationSeconds, s.language)
    com.example.service.NavigationForegroundService.startOrUpdate(
        context = app,
        title = formatModeNotificationTitle(
            com.example.navigation.TravelMode.WALK,
            dest.name,
            etaText,
            s.language
        ),
        body = initialManeuver?.instructionEn ?: dest.name
    )

    val spoken = voiceManager.speakStartInstruction(route, s.language)
    if (spoken.isNotBlank()) {
        navController.markExternalPromptSpoken()
        _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
    }

    _cameraCommand.value = CameraTargetCommand(
        lat = startLoc.lat,
        lon = startLoc.lon,
        zoom = 17.0,
        tilt = 0.0,
        bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0
    )
    startGpsLossWatchdogImpl()
}

internal fun RahYabViewModel.triggerWalkingArrivalVibrationImpl() {
    runCatching {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = app.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(300L, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300L)
        }
    }
}
