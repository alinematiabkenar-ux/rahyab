package com.example.navigation

import com.example.data.model.UsStateInfo
import com.example.data.online.WeatherAlertItem
import com.example.data.search.PlaceSearchResult
import com.example.data.search.RoadHazardPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure JVM state holder for navigation state shared across Phone UI and Android Auto.
 * Kept separate from Android Context so unit tests can verify live state sync cleanly.
 */
class SharedNavigationStateStore {
    val _selectedPlace = MutableStateFlow<PlaceSearchResult?>(null)
    val selectedPlace: StateFlow<PlaceSearchResult?> = _selectedPlace.asStateFlow()

    val _candidateRoutes = MutableStateFlow<List<OfflineRoute>>(emptyList())
    val candidateRoutes: StateFlow<List<OfflineRoute>> = _candidateRoutes.asStateFlow()

    val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    val _isCalculatingRoute = MutableStateFlow(false)
    val isCalculatingRoute: StateFlow<Boolean> = _isCalculatingRoute.asStateFlow()

    val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()

    val _missingStatesForRoute = MutableStateFlow<List<UsStateInfo>>(emptyList())
    val missingStatesForRoute: StateFlow<List<UsStateInfo>> = _missingStatesForRoute.asStateFlow()

    val _navigationState = MutableStateFlow(ActiveNavigationState())
    val navigationState: StateFlow<ActiveNavigationState> = _navigationState.asStateFlow()

    val _visibleCameras = MutableStateFlow<List<RoadHazardPoint>>(emptyList())
    val visibleCameras: StateFlow<List<RoadHazardPoint>> = _visibleCameras.asStateFlow()

    val _visibleTrafficLights = MutableStateFlow<List<RoadHazardPoint>>(emptyList())
    val visibleTrafficLights: StateFlow<List<RoadHazardPoint>> = _visibleTrafficLights.asStateFlow()

    val _visibleSpeedBumps = MutableStateFlow<List<RoadHazardPoint>>(emptyList())
    val visibleSpeedBumps: StateFlow<List<RoadHazardPoint>> = _visibleSpeedBumps.asStateFlow()

    val _visibleStopSigns = MutableStateFlow<List<RoadHazardPoint>>(emptyList())
    val visibleStopSigns: StateFlow<List<RoadHazardPoint>> = _visibleStopSigns.asStateFlow()

    val _aheadTrafficLightMeters = MutableStateFlow<Double?>(null)
    val aheadTrafficLightMeters: StateFlow<Double?> = _aheadTrafficLightMeters.asStateFlow()

    val _freeDriveAheadAlert = MutableStateFlow<AheadAlert?>(null)
    val freeDriveAheadAlert: StateFlow<AheadAlert?> = _freeDriveAheadAlert.asStateFlow()

    val _userHeadingDegrees = MutableStateFlow(0f)
    val userHeadingDegrees: StateFlow<Float> = _userHeadingDegrees.asStateFlow()

    val _isHeadingUpMode = MutableStateFlow(true)
    val isHeadingUpMode: StateFlow<Boolean> = _isHeadingUpMode.asStateFlow()

    val _simulationSpeedMultiplier = MutableStateFlow(1)
    val simulationSpeedMultiplier: StateFlow<Int> = _simulationSpeedMultiplier.asStateFlow()

    val _cachedWeatherAlerts = MutableStateFlow<List<WeatherAlertItem>>(emptyList())
    val _dismissedWeatherAlertIds = MutableStateFlow<Set<String>>(emptySet())

    fun startSessionSnapshot(
        route: OfflineRoute,
        destinationName: String,
        destinationAddress: String = "",
        isSimulating: Boolean = false
    ) {
        val first = route.maneuvers.firstOrNull()
        _navigationState.value = ActiveNavigationState(
            isNavigating = true,
            route = route,
            destinationName = destinationName,
            destinationAddress = destinationAddress,
            currentManeuverIndex = 0,
            distanceToNextManeuverMeters = first?.lengthMeters ?: route.distanceMeters,
            timeToNextManeuverSeconds = first?.timeSeconds ?: route.durationSeconds,
            remainingDistanceMeters = route.distanceMeters,
            remainingTimeSeconds = route.durationSeconds,
            snappedLocation = route.shape.firstOrNull(),
            isSimulatingDrive = isSimulating
        )
    }

    fun updateNavigationState(state: ActiveNavigationState) {
        _navigationState.value = state
    }

    fun clearSession() {
        _navigationState.value = ActiveNavigationState()
        _freeDriveAheadAlert.value = null
        _aheadTrafficLightMeters.value = null
    }
}

/**
 * Optional delegate implemented by the active Phone ViewModel so actions triggered from Android Auto
 * seamlessly run through the full phone pipeline whenever MainActivity is alive.
 */
interface PhoneNavigationDelegate {
    fun onCalculateRoutesForPlace(place: PlaceSearchResult)
    fun onSelectCandidateRoute(index: Int)
    fun onStartNavigation(forceSimulate: Boolean?)
    fun onStopNavigation()
    fun onToggleSimulationForAutoDrive()
    fun onReportRoadClosure()
}
