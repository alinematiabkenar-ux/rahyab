package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.i18n.stringsFor
import com.example.navigation.LatLon
import com.example.navigation.LocationTracker
import com.example.navigation.MissingStatesException
import com.example.navigation.TravelMode
import com.example.ui.screens.ModePreviewSummary
import kotlinx.coroutines.launch

/**
 * Phase M3: Computes offline routes for DRIVE, WALK, and BIKE in the background when a destination
 * is chosen, applies "Too far" limits (>50 km walk, >150 km bike), remembers the last chosen mode,
 * and switches displayed map routes immediately when the user taps a mode chip.
 */
internal fun RahYabViewModel.calculateAllTravelModePreviewsImpl() {
    val dest = _selectedPlace.value ?: return
    val origin = getEffectiveReferenceLocation()
    val s = settings.value
    val activeMode = _selectedTravelMode.value
    val routingHeading = LocationTracker.resolveRoutingHeadingDegrees(currentGpsFix.value)
    val straightLineMeters = OfflinePlaceSearchEngine.haversineMeters(origin.lat, origin.lon, dest.lat, dest.lon)

    multiModePreviewJob?.cancel()
    multiModePreviewJob = viewModelScope.launch {
        _isCalculatingRoute.value = true
        _routeError.value = null
        _missingStatesForRoute.value = emptyList()

        val initialSummaries = TravelMode.entries.associateWith { mode ->
            val tooFarStraight = TravelMode.isTooFar(mode, straightLineMeters)
            ModePreviewSummary(
                mode = mode,
                isLoading = !tooFarStraight,
                isTooFar = tooFarStraight
            )
        }
        _modePreviewSummaries.value = initialSummaries

        // Calculate the currently selected mode first so the user sees its route immediately,
        // then compute the remaining two modes in the background.
        val orderedModes = listOf(activeMode) + TravelMode.entries.filter { it != activeMode }

        for (mode in orderedModes) {
            if (_selectedPlace.value?.id != dest.id) return@launch
            if (TravelMode.isTooFar(mode, straightLineMeters)) {
                if (mode == _selectedTravelMode.value) {
                    applyModePreviewToActiveCandidatesImpl(mode)
                    _isCalculatingRoute.value = false
                }
                continue
            }

            val options = buildCurrentRouteOptionsImpl(s, mode)
            val result = routingEngine.calculateRoutes(
                origin = origin,
                destination = LatLon(dest.lat, dest.lon),
                options = options,
                headingDegrees = if (mode == TravelMode.WALK) null else routingHeading,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )

            if (_selectedPlace.value?.id != dest.id) return@launch

            val updatedSummary = result.fold(
                onSuccess = { routes ->
                    val primary = routes.firstOrNull()
                    if (primary != null && TravelMode.isTooFar(mode, primary.distanceMeters)) {
                        ModePreviewSummary(mode = mode, routes = emptyList(), isLoading = false, isTooFar = true)
                    } else {
                        ModePreviewSummary(mode = mode, routes = routes, isLoading = false, isTooFar = false)
                    }
                },
                onFailure = { err ->
                    if (err is MissingStatesException && mode == _selectedTravelMode.value) {
                        _missingStatesForRoute.value = err.missingStates
                    }
                    ModePreviewSummary(
                        mode = mode,
                        routes = emptyList(),
                        isLoading = false,
                        isTooFar = false,
                        errorMessage = err.message
                    )
                }
            )
            _modePreviewSummaries.value = _modePreviewSummaries.value + (mode to updatedSummary)

            if (mode == _selectedTravelMode.value) {
                applyModePreviewToActiveCandidatesImpl(mode)
                _isCalculatingRoute.value = false
            }
        }
        _isCalculatingRoute.value = false
    }
}

internal fun RahYabViewModel.selectTravelModeImpl(mode: TravelMode) {
    _selectedTravelMode.value = mode
    viewModelScope.launch { prefs.setLastTravelMode(mode) }
    val summary = _modePreviewSummaries.value[mode]
    if (summary != null) {
        applyModePreviewToActiveCandidatesImpl(mode)
    } else if (_selectedPlace.value != null) {
        calculateAllTravelModePreviewsImpl()
    }
}

private fun RahYabViewModel.applyModePreviewToActiveCandidatesImpl(mode: TravelMode) {
    val summary = _modePreviewSummaries.value[mode] ?: return
    val s = settings.value
    when {
        summary.isTooFar -> {
            _candidateRoutes.value = emptyList()
            _selectedRouteIndex.value = 0
            _routeError.value = stringsFor(s.language).travelModeTooFar
        }
        summary.routes.isNotEmpty() -> {
            _routeError.value = null
            _missingStatesForRoute.value = emptyList()
            _candidateRoutes.value = summary.routes
            _selectedRouteIndex.value = 0
            autoRecenterJob?.cancel()
            _isCameraFollowing.value = false
            summary.routes.firstOrNull()?.shape?.let { pts ->
                if (pts.isNotEmpty()) {
                    val mid = pts[pts.size / 2]
                    _cameraCommand.value = CameraTargetCommand(mid.lat, mid.lon, zoom = 12.0, anchorLowerThird = false)
                }
            }
            refreshWeatherAlertsIfEligibleImpl(explicitRoute = summary.routes.firstOrNull())
        }
        !summary.isLoading -> {
            _candidateRoutes.value = emptyList()
            _selectedRouteIndex.value = 0
            _routeError.value = summary.errorMessage ?: stringsFor(s.language).missingRoutingTiles
        }
    }
}
