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
                isWalkingMode = false,
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
    if (isWalkingMode || _navigationState.value.isSimulatingDrive) return
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
    val str = stringsFor(settings.value.language)
    val ref = getEffectiveReferenceLocation()
    val dist = com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(
        ref.lat,
        ref.lon,
        spot.lat,
        spot.lon
    )
    selectPlace(
        com.example.data.search.PlaceSearchResult(
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
    )
}
