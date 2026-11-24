package com.example.navigation

import com.example.RahYabApplication
import com.example.data.local.ReportType
import com.example.data.local.UserReportEntity
import com.example.data.model.UsStateInfo
import com.example.data.preferences.UserSettings
import com.example.data.search.PlaceSearchResult
import com.example.data.search.RoadHazardPoint
import com.example.i18n.BilingualFormatter
import com.example.i18n.stringsFor
import com.example.service.NavigationForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-scoped navigation hub shared between [com.example.ui.RahYabViewModel] (phone UI)
 * and `com.example.car.*` (Android Auto session).
 *
 * Guarantees single-source-of-truth StateFlows so starting, updating, rerouting, or stopping
 * navigation on either screen immediately reflects on the other — and supports headless operation
 * on Android Auto even when MainActivity is not open.
 */
class RahYabNavigationHub(private val app: RahYabApplication) {

    val stateStore = SharedNavigationStateStore()
    val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val selectedPlace: StateFlow<PlaceSearchResult?> get() = stateStore.selectedPlace
    val candidateRoutes: StateFlow<List<OfflineRoute>> get() = stateStore.candidateRoutes
    val selectedRouteIndex: StateFlow<Int> get() = stateStore.selectedRouteIndex
    val isCalculatingRoute: StateFlow<Boolean> get() = stateStore.isCalculatingRoute
    val routeError: StateFlow<String?> get() = stateStore.routeError
    val missingStatesForRoute: StateFlow<List<UsStateInfo>> get() = stateStore.missingStatesForRoute
    val navigationState: StateFlow<ActiveNavigationState> get() = stateStore.navigationState
    val visibleCameras: StateFlow<List<RoadHazardPoint>> get() = stateStore.visibleCameras
    val visibleTrafficLights: StateFlow<List<RoadHazardPoint>> get() = stateStore.visibleTrafficLights
    val visibleSpeedBumps: StateFlow<List<RoadHazardPoint>> get() = stateStore.visibleSpeedBumps
    val freeDriveAheadAlert: StateFlow<AheadAlert?> get() = stateStore.freeDriveAheadAlert
    val userHeadingDegrees: StateFlow<Float> get() = stateStore.userHeadingDegrees
    val isHeadingUpMode: StateFlow<Boolean> get() = stateStore.isHeadingUpMode

    @Volatile
    private var phoneDelegate: PhoneNavigationDelegate? = null
    private val headlessController = NavigationSessionController()
    private var headlessGpsJob: Job? = null
    private var headlessSimJob: Job? = null
    private var headlessArrivalJob: Job? = null
    private var headlessCachedHazards: List<RoadHazardPoint> = emptyList()
    private var isCarSessionConnected: Boolean = false

    fun attachPhoneDelegate(delegate: PhoneNavigationDelegate) {
        phoneDelegate = delegate
        stopHeadlessLoops()
    }

    fun detachPhoneDelegate(delegate: PhoneNavigationDelegate) {
        if (phoneDelegate === delegate) {
            phoneDelegate = null
            if (isCarSessionConnected && navigationState.value.isNavigating) {
                startHeadlessDriverIfNeeded()
            }
        }
    }

    fun setCarSessionConnected(connected: Boolean) {
        isCarSessionConnected = connected
        if (connected) {
            app.locationTracker.startTracking()
            app.locationTracker.setAppInForeground(true)
            if (phoneDelegate == null && navigationState.value.isNavigating) {
                startHeadlessDriverIfNeeded()
            }
        } else if (phoneDelegate == null && !navigationState.value.isNavigating) {
            stopHeadlessLoops()
            app.locationTracker.setAppInForeground(false)
        }
    }

    fun getCurrentReferenceLocation(): LatLon {
        val fix = app.locationTracker.currentFix.value
        if (fix != null) return LatLon(fix.lat, fix.lon)
        return navigationState.value.snappedLocation ?: LatLon(44.0521, -123.0868)
    }

    fun calculateRoutesForPlace(place: PlaceSearchResult) {
        val delegate = phoneDelegate
        if (delegate != null) {
            delegate.onCalculateRoutesForPlace(place)
            return
        }
        stateStore._selectedPlace.value = place
        stateStore._candidateRoutes.value = emptyList()
        stateStore._selectedRouteIndex.value = 0
        stateStore._routeError.value = null
        stateStore._missingStatesForRoute.value = emptyList()
        hubScope.launch {
            val s = currentSettings()
            stateStore._isCalculatingRoute.value = true
            val origin = getCurrentReferenceLocation()
            val heading = LocationTracker.resolveRoutingHeadingDegrees(app.locationTracker.currentFix.value)
            val result = app.routingEngine.calculateRoutes(
                origin = origin,
                destination = LatLon(place.lat, place.lon),
                options = buildHeadlessRouteOptions(s),
                headingDegrees = heading,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )
            stateStore._isCalculatingRoute.value = false
            result.onSuccess { routes ->
                stateStore._candidateRoutes.value = routes
                stateStore._selectedRouteIndex.value = 0
            }.onFailure { err ->
                if (err is MissingStatesException) {
                    stateStore._missingStatesForRoute.value = err.missingStates
                }
                stateStore._routeError.value = err.message ?: stringsFor(s.language).missingRoutingTiles
            }
        }
    }

    fun selectCandidateRoute(index: Int) {
        val delegate = phoneDelegate
        if (delegate != null) {
            delegate.onSelectCandidateRoute(index)
            return
        }
        val routes = stateStore._candidateRoutes.value
        if (routes.isEmpty()) return
        stateStore._selectedRouteIndex.value = index.coerceIn(0, routes.lastIndex)
    }

    fun startNavigation(forceSimulate: Boolean? = null) {
        val delegate = phoneDelegate
        if (delegate != null) {
            delegate.onStartNavigation(forceSimulate)
            return
        }
        val routes = stateStore._candidateRoutes.value
        val dest = stateStore._selectedPlace.value ?: return
        val route = routes.getOrNull(stateStore._selectedRouteIndex.value.coerceIn(0, routes.lastIndex.coerceAtLeast(0)))
            ?: return

        hubScope.launch {
            val s = currentSettings()
            val shouldSim = forceSimulate ?: s.simulateDriveEnabled
            stopHeadlessLoops()
            headlessController.reset()
            app.voiceGuidanceManager.setUseEnglishVoiceInPersianMode(s.useEnglishVoiceInPersianMode)
            app.voiceGuidanceManager.setLanguage(s.language)
            app.voiceGuidanceManager.setVoiceEnabled(s.voiceGuidanceEnabled)
            app.voiceGuidanceManager.resetDeduplication()
            app.locationTracker.setNavigationMode(true)
            app.locationTracker.startTracking()

            val firstManeuver = route.maneuvers.firstOrNull()
            val startLoc = route.shape.firstOrNull() ?: getCurrentReferenceLocation()
            val bearing = if (route.shape.size >= 2) {
                ValhallaResponseParser.bearingDegrees(route.shape[0], route.shape[1]).toFloat()
            } else 0f

            stateStore._navigationState.value = ActiveNavigationState(
                isNavigating = true,
                route = route,
                destinationName = dest.name,
                destinationAddress = dest.formattedAddress,
                currentManeuverIndex = 0,
                distanceToNextManeuverMeters = firstManeuver?.lengthMeters ?: route.distanceMeters,
                timeToNextManeuverSeconds = firstManeuver?.timeSeconds ?: route.durationSeconds,
                remainingDistanceMeters = route.distanceMeters,
                remainingTimeSeconds = route.durationSeconds,
                snappedLocation = startLoc,
                snappedBearing = bearing,
                currentSpeedLimitMph = route.speedLimitsByShapeIndex[0] ?: firstManeuver?.speedLimitMph,
                isSimulatingDrive = shouldSim,
                showLaneStrip = firstManeuver?.lanes?.isNotEmpty() == true &&
                    (firstManeuver.lengthMeters <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit))
            )

            if (route.shape.isNotEmpty()) {
                val minLat = route.shape.minOf { it.lat } - 0.05
                val maxLat = route.shape.maxOf { it.lat } + 0.05
                val minLon = route.shape.minOf { it.lon } - 0.05
                val maxLon = route.shape.maxOf { it.lon } + 0.05
                headlessCachedHazards = app.offlineSearchEngine.queryRoadHazardsInBounds(minLat, maxLat, minLon, maxLon)
            }

            val etaText = BilingualFormatter.formatDuration(route.durationSeconds, s.language)
            NavigationForegroundService.startOrUpdate(
                context = app,
                title = "${dest.name} • $etaText",
                body = firstManeuver?.instructionEn ?: dest.name
            )
            val spoken = app.voiceGuidanceManager.speakStartInstruction(route, s.language)
            if (spoken.isNotBlank()) {
                headlessController.markExternalPromptSpoken()
                stateStore._navigationState.value = stateStore._navigationState.value.copy(lastSpokenInstruction = spoken)
            }
            startHeadlessDriverIfNeeded()
        }
    }

    fun stopNavigation() {
        stopHeadlessLoops()
        val delegate = phoneDelegate
        if (delegate != null) {
            delegate.onStopNavigation()
            return
        }
        app.locationTracker.setNavigationMode(false)
        headlessController.reset()
        stateStore.clearSession()
        NavigationForegroundService.stop(app)
        hubScope.launch { app.packManager.applyPendingSwapsIfIdle() }
    }

    fun triggerAutoDriveSimulation() {
        val delegate = phoneDelegate
        if (delegate != null) {
            delegate.onToggleSimulationForAutoDrive()
            return
        }
        if (navigationState.value.isNavigating) {
            stateStore._navigationState.value = navigationState.value.copy(isSimulatingDrive = true)
            startHeadlessDriverIfNeeded()
        } else if (candidateRoutes.value.isNotEmpty() && selectedPlace.value != null) {
            startNavigation(forceSimulate = true)
        }
    }

    fun toggleVoiceMuted() {
        hubScope.launch {
            val s = currentSettings()
            val next = !s.voiceGuidanceEnabled
            app.preferencesRepository.setVoiceGuidanceEnabled(next)
            app.voiceGuidanceManager.setVoiceEnabled(next)
        }
    }

    fun reportRoadClosureAtCurrentLocation() {
        val delegate = phoneDelegate
        if (delegate != null) {
            delegate.onReportRoadClosure()
            return
        }
        hubScope.launch(Dispatchers.IO) {
            val loc = getCurrentReferenceLocation()
            val now = System.currentTimeMillis()
            app.database.userReportDao().insert(
                UserReportEntity(
                    type = ReportType.ROAD_CLOSURE.name,
                    lat = loc.lat,
                    lon = loc.lon,
                    bearing = navigationState.value.snappedBearing,
                    createdAt = now,
                    expiresAt = now + 24 * 60 * 60_000L,
                    note = ""
                )
            )
        }
    }

    private fun startHeadlessDriverIfNeeded() {
        if (phoneDelegate != null || !navigationState.value.isNavigating) return
        stopHeadlessLoops()
        if (navigationState.value.isSimulatingDrive) {
            headlessSimJob = hubScope.launch {
                var simClockMs = System.currentTimeMillis()
                while (phoneDelegate == null && navigationState.value.isNavigating &&
                    navigationState.value.isSimulatingDrive && !navigationState.value.hasArrived
                ) {
                    delay(300L)
                    val s = currentSettings()
                    val cur = navigationState.value
                    val batch = withContext(Dispatchers.Default) {
                        headlessController.advanceSimulationStep(
                            currentState = cur,
                            totalSimulatedSeconds = 0.6,
                            startingSimClockMs = simClockMs,
                            wrongTurnOffsetMeters = 0.0,
                            dbHazards = headlessCachedHazards,
                            userReports = emptyList(),
                            unit = s.distanceUnit,
                            language = s.language,
                            speedAudioEnabled = s.speedWarningAudioEnabled,
                            settings = s
                        )
                    }
                    simClockMs = batch.finalSimClockMs
                    applyHeadlessStep(batch.state, batch.latestManeuverToSpeak, batch.latestAlertToSpeak, batch.triggerOverspeedChime, s)
                }
            }
        } else {
            headlessGpsJob = hubScope.launch {
                app.locationTracker.currentFix.collect { fix ->
                    if (fix == null || phoneDelegate != null || !navigationState.value.isNavigating) return@collect
                    val s = currentSettings()
                    val res = headlessController.processGpsFix(
                        currentState = navigationState.value,
                        fix = fix,
                        dbHazards = headlessCachedHazards,
                        userReports = emptyList(),
                        unit = s.distanceUnit,
                        language = s.language,
                        speedAudioEnabled = s.speedWarningAudioEnabled,
                        settings = s
                    )
                    applyHeadlessStep(res.state, res.maneuverToSpeak, res.alertToSpeak, res.triggerOverspeedChime, s)
                }
            }
        }
    }

    private fun applyHeadlessStep(
        state: ActiveNavigationState,
        maneuverToSpeak: Pair<RouteManeuver, String>?,
        alertToSpeak: AheadAlert?,
        chime: Boolean,
        s: UserSettings
    ) {
        val wasArrived = navigationState.value.hasArrived
        stateStore._navigationState.value = state
        if (chime) app.voiceGuidanceManager.playOverspeedChime()
        if (state.hasArrived && !wasArrived) {
            val arrivalType = state.route?.maneuvers?.lastOrNull()?.type ?: ManeuverType.ARRIVE
            app.voiceGuidanceManager.speakArrival(arrivalType, state.destinationName, s.language)
            headlessArrivalJob?.cancel()
            headlessArrivalJob = hubScope.launch {
                delay(6000L)
                if (navigationState.value.hasArrived) stopNavigation()
            }
        } else {
            maneuverToSpeak?.let { (m, stage) ->
                app.voiceGuidanceManager.speakManeuver(m, state.distanceToNextManeuverMeters, s.distanceUnit, s.language, stage, m.lanes.isNotEmpty())
            }
            alertToSpeak?.let { alert ->
                if (alert.kind == RoadAlertKind.SPEED_CAMERA || alert.kind == RoadAlertKind.RED_LIGHT_CAMERA) {
                    app.voiceGuidanceManager.playCameraAlertSound()
                }
                app.voiceGuidanceManager.speakAlert(alert, s.language, s.distanceUnit)
            }
        }
    }

    private fun stopHeadlessLoops() {
        headlessGpsJob?.cancel()
        headlessGpsJob = null
        headlessSimJob?.cancel()
        headlessSimJob = null
        headlessArrivalJob?.cancel()
        headlessArrivalJob = null
    }

    private suspend fun currentSettings(): UserSettings =
        runCatching { app.preferencesRepository.settingsFlow.first() }.getOrDefault(UserSettings())

    private suspend fun buildHeadlessRouteOptions(s: UserSettings): RouteOptions {
        val now = System.currentTimeMillis()
        val closures = runCatching {
            app.database.userReportDao().observeActive(now).first()
                .filter { it.type == ReportType.ROAD_CLOSURE.name && it.expiresAt > now }
                .map { LatLon(it.lat, it.lon) }
        }.getOrDefault(emptyList())
        return RouteOptions(
            avoidTolls = s.avoidTolls,
            avoidHighways = s.avoidHighways,
            avoidFerries = s.avoidFerries,
            preferShortest = s.routePreference == RoutePreference.SHORTEST,
            avoidLocations = closures,
            travelMode = TravelMode.DRIVE
        )
    }
}
