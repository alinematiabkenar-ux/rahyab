package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.local.ParkedCarEntity
import com.example.data.search.PlaceSearchResult
import com.example.i18n.stringsFor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.DestinationParkingOption
import com.example.navigation.LatLon
import com.example.navigation.LocationTracker
import com.example.navigation.ParkingNearDestinationFinder
import com.example.navigation.RouteOptions
import com.example.navigation.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * PK2 & PK3: ViewModel integration for finding offline parking near the destination, switching
 * driving navigation to a chosen parking lot while remembering the original final destination,
 * auto-saving parked car location upon arrival at parking, and offering a one-tap offline walk
 * route to the original final destination.
 */
internal fun RahYabViewModel.maybeSearchParkingNearActiveDestinationImpl(state: ActiveNavigationState) {
    if (!state.isNavigating || state.hasArrived || state.effectiveTravelMode != TravelMode.DRIVE) return
    if (state.remainingDistanceMeters <= 0.0 || state.remainingDistanceMeters >= 800.0) return
    if (_originalFinalDestination.value != null) return
    val route = state.route ?: return
    if (lastAutoParkingRouteId == route.id) return
    lastAutoParkingRouteId = route.id

    val dest = _selectedPlace.value
    val destLat = dest?.lat ?: route.shape.lastOrNull()?.lat ?: return
    val destLon = dest?.lon ?: route.shape.lastOrNull()?.lon ?: return
    runDestinationParkingLookupImpl(destLat, destLon, showCardWhenFound = true)
}

internal fun RahYabViewModel.searchParkingNearPreviewDestinationImpl() {
    val place = _selectedPlace.value ?: return
    runDestinationParkingLookupImpl(place.lat, place.lon, showCardWhenFound = true)
}

private fun RahYabViewModel.runDestinationParkingLookupImpl(
    destLat: Double,
    destLon: Double,
    showCardWhenFound: Boolean
) {
    destinationParkingJob?.cancel()
    destinationParkingJob = viewModelScope.launch {
        val options = ParkingNearDestinationFinder.findNearDestination(
            searchEngine = offlineSearch,
            routingEngine = routingEngine,
            destLat = destLat,
            destLon = destLon,
            allowDevOnlineFallback = settings.value.devOnlineOregonMode
        )
        if (options.isNotEmpty()) {
            _destinationParkingOptions.value = options
            if (showCardWhenFound) {
                _showDestinationParkingCard.value = true
            }
        } else {
            // PK3: If no parking is found, show nothing.
            _destinationParkingOptions.value = emptyList()
            _showDestinationParkingCard.value = false
        }
    }
}

internal fun RahYabViewModel.selectDestinationParkingImpl(option: DestinationParkingOption) {
    val s = settings.value
    val str = stringsFor(s.language)
    val currentPlace = _selectedPlace.value
    val activeNav = _navigationState.value

    // Preserve the original final destination so we can walk there after parking
    if (_originalFinalDestination.value == null) {
        _originalFinalDestination.value = currentPlace ?: activeNav.route?.shape?.lastOrNull()?.let { end ->
            PlaceSearchResult(
                id = "orig_dest_${System.currentTimeMillis()}",
                name = activeNav.destinationName,
                brand = "",
                category = "",
                address = activeNav.destinationAddress,
                city = "",
                stateCode = currentLocationStateCode.value.orEmpty(),
                postcode = "",
                lat = end.lat,
                lon = end.lon,
                distanceMeters = 0.0,
                relevanceScore = 100.0,
                source = "original_destination"
            )
        }
    }

    _showDestinationParkingCard.value = false
    _destinationParkingOptions.value = emptyList()

    val parkingPlace = PlaceSearchResult(
        id = option.id,
        name = option.name.ifBlank { str.parkingDefaultLotName },
        brand = "",
        category = "parking",
        address = "",
        city = "",
        stateCode = currentLocationStateCode.value.orEmpty(),
        postcode = "",
        lat = option.lat,
        lon = option.lon,
        distanceMeters = option.straightLineMeters,
        relevanceScore = 100.0,
        source = "destination_parking"
    )
    _selectedPlace.value = parkingPlace
    _selectedTravelMode.value = TravelMode.DRIVE

    if (activeNav.isNavigating) {
        val origin = activeNav.snappedLocation ?: getEffectiveReferenceLocation()
        val wasSimulating = activeNav.isSimulatingDrive
        val heading = if (wasSimulating) {
            activeNav.snappedBearing
        } else {
            LocationTracker.resolveRoutingHeadingDegrees(currentGpsFix.value)
        }
        viewModelScope.launch {
            val res = routingEngine.calculateRoutes(
                origin = origin,
                destination = LatLon(option.lat, option.lon),
                options = buildCurrentRouteOptionsImpl(s, TravelMode.DRIVE),
                headingDegrees = heading,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )
            res.onSuccess { routes ->
                if (routes.isNotEmpty()) {
                    _candidateRoutes.value = routes
                    _selectedRouteIndex.value = 0
                    startNavigationImpl(forceSimulate = wasSimulating)
                }
            }
        }
    } else {
        calculateAllTravelModePreviewsImpl()
    }
}

internal fun RahYabViewModel.onArrivedAtChosenParkingImpl() {
    val nav = _navigationState.value
    val spotLoc = nav.snappedLocation
        ?: _selectedPlace.value?.let { LatLon(it.lat, it.lon) }
        ?: getEffectiveReferenceLocation()

    // PK3: Auto-save parked car location upon arriving at chosen parking
    viewModelScope.launch(Dispatchers.IO) {
        parkedCarDao.upsert(
            ParkedCarEntity(
                id = 1,
                lat = spotLoc.lat,
                lon = spotLoc.lon,
                accuracyMeters = currentGpsFix.value?.accuracyMeters ?: 5f,
                parkedAtEpochMs = System.currentTimeMillis(),
                note = nav.destinationName.take(120),
                source = "destination_parking_arrival"
            )
        )
    }
    arrivalEndJob?.cancel()
    arrivalEndJob = null
    _showWalkToOriginalDestinationPrompt.value = true
}

internal fun RahYabViewModel.startWalkFromParkingToOriginalDestinationImpl() {
    val finalDest = _originalFinalDestination.value ?: return
    val s = settings.value
    val str = stringsFor(s.language)
    val origin = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()

    _showWalkToOriginalDestinationPrompt.value = false
    _originalFinalDestination.value = null
    _selectedPlace.value = finalDest
    _selectedTravelMode.value = TravelMode.WALK

    viewModelScope.launch {
        _isCalculatingRoute.value = true
        _routeError.value = null
        val res = routingEngine.calculateRoutes(
            origin = origin,
            destination = LatLon(finalDest.lat, finalDest.lon),
            options = RouteOptions(
                travelMode = TravelMode.WALK,
                walkingSpeedKmh = s.walkingSpeedPreset.speedKmh
            ),
            headingDegrees = null,
            allowDevOnlineFallback = s.devOnlineOregonMode
        )
        _isCalculatingRoute.value = false
        res.onSuccess { routes ->
            val walkRoute = routes.firstOrNull() ?: return@onSuccess
            _candidateRoutes.value = listOf(walkRoute)
            _selectedRouteIndex.value = 0
            startWalkingNavigationSessionImpl(walkRoute, finalDest)
        }.onFailure { err ->
            _snackbarMessage.value = err.message ?: str.missingRoutingTiles
        }
    }
}

internal fun RahYabViewModel.dismissWalkToOriginalDestinationPromptImpl() {
    _showWalkToOriginalDestinationPrompt.value = false
    _originalFinalDestination.value = null
    if (_navigationState.value.hasArrived) {
        stopNavigationImpl()
    }
}

internal fun RahYabViewModel.clearDestinationParkingStateImpl() {
    destinationParkingJob?.cancel()
    destinationParkingJob = null
    lastAutoParkingRouteId = null
    _destinationParkingOptions.value = emptyList()
    _showDestinationParkingCard.value = false
    _showWalkToOriginalDestinationPrompt.value = false
    _originalFinalDestination.value = null
}
