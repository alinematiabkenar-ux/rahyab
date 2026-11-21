package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RahYabApplication
import com.example.data.local.BackupAndTransferManager
import com.example.data.local.PackInstallStatus
import com.example.data.local.ReportType
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.local.UserReportEntity
import com.example.data.model.UsStateInfo
import com.example.data.model.UsStatesCatalog
import com.example.data.packs.StatePackUiItem
import com.example.data.preferences.UserSettings
import com.example.data.search.PlaceSearchResult
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.MapThemeMode
import com.example.i18n.stringsFor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.MissingStatesException
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteOptions
import com.example.navigation.SolarNightCalculator
import com.example.service.NavigationForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CameraTargetCommand(
    val lat: Double,
    val lon: Double,
    val zoom: Double = 14.5,
    val tilt: Double = 0.0,
    val bearing: Double = 0.0,
    val nonce: Long = System.currentTimeMillis()
)

class RahYabViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RahYabApplication
    private val prefs = app.preferencesRepository
    private val packManager = app.packManager
    private val offlineSearch = app.offlineSearchEngine
    private val devOnlineSearch = app.onlineOregonDevSearchClient
    private val routingEngine = app.routingEngine
    private val locationTracker = app.locationTracker
    private val voiceManager = app.voiceGuidanceManager
    private val savedPlaceDao = app.database.savedPlaceDao()
    private val userReportDao = app.database.userReportDao()
    private val installedPackDao = app.database.installedPackDao()
    private val navController = NavigationSessionController()

    val settings: StateFlow<UserSettings> = prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings()
    )

    val currentGpsFix: StateFlow<GpsFix?> = locationTracker.currentFix
    val isPersianTtsAvailable: StateFlow<Boolean> = voiceManager.isPersianVoiceAvailable
    val isFetchingManifest: StateFlow<Boolean> = packManager.isFetchingManifest
    val manifestError: StateFlow<String?> = packManager.manifestError
    val packsChangedTick: StateFlow<Long> = packManager.packsChangedTick

    val currentLocationStateCode: StateFlow<String?> = currentGpsFix.map { fix ->
        val lat = fix?.lat ?: EUGENE_OR_LAT
        val lon = fix?.lon ?: EUGENE_OR_LON
        UsStatesCatalog.findBestStateForLocation(lat, lon)?.code
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "or"
    )

    val savedPlaces: StateFlow<List<SavedPlaceEntity>> = savedPlaceDao.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _nowTick = MutableStateFlow(System.currentTimeMillis())

    val activeUserReports: StateFlow<List<UserReportEntity>> = combine(
        userReportDao.observeActive(0L),
        _nowTick
    ) { reports, now ->
        reports.filter { it.expiresAt > now }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val statePacks: StateFlow<List<StatePackUiItem>> = combine(
        installedPackDao.observeAll(),
        packManager.manifest,
        currentLocationStateCode
    ) { installedList, manifest, locStateCode ->
        val installedByCode = installedList.associateBy { it.stateCode.lowercase() }
        val manifestByCode = manifest?.states?.associateBy { it.stateCode.lowercase() }.orEmpty()

        UsStatesCatalog.allPackEntries.map { state ->
            val inst = installedByCode[state.code]
            val remote = manifestByCode[state.code]
            val status = inst?.status?.let {
                runCatching { PackInstallStatus.valueOf(it) }.getOrNull()
            } ?: PackInstallStatus.NOT_DOWNLOADED

            val totalBytes = remote?.totalSizeBytes
                ?: inst?.totalBytes
                ?: 0L

            StatePackUiItem(
                stateCode = state.code,
                abbr = state.abbr,
                nameEn = state.nameEn,
                nameFa = state.nameFa,
                parentStateCode = state.parentStateCode ?: remote?.parentStateCode,
                status = status,
                installedVersion = inst?.installedVersion.orEmpty(),
                installedBuildVersion = inst?.buildVersion.orEmpty(),
                availableVersion = remote?.version ?: inst?.availableVersion.orEmpty(),
                totalSizeBytes = totalBytes,
                downloadedBytes = inst?.downloadedBytes ?: 0L,
                installedBytesOnDisk = packManager.getStateDiskBytes(state.code),
                hasPmtiles = inst?.hasPmtiles ?: false,
                hasValhalla = inst?.hasValhalla ?: false,
                hasPlacesDb = inst?.hasPlacesDb ?: false,
                hasManifestEntry = remote != null,
                isCurrentLocationState = (state.code == locStateCode),
                isQueued = (status == PackInstallStatus.QUEUED),
                errorMessage = inst?.errorMessage
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _mismatchedBuildVersions = MutableStateFlow<List<String>>(emptyList())
    val mismatchedBuildVersions: StateFlow<List<String>> = _mismatchedBuildVersions.asStateFlow()

    // Search UI state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _selectedPlace = MutableStateFlow<PlaceSearchResult?>(null)
    val selectedPlace: StateFlow<PlaceSearchResult?> = _selectedPlace.asStateFlow()

    // Route preview state (up to 3 color-coded alternatives)
    private val _candidateRoutes = MutableStateFlow<List<OfflineRoute>>(emptyList())
    val candidateRoutes: StateFlow<List<OfflineRoute>> = _candidateRoutes.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    private val _isCalculatingRoute = MutableStateFlow(false)
    val isCalculatingRoute: StateFlow<Boolean> = _isCalculatingRoute.asStateFlow()

    private val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()

    private val _missingStatesForRoute = MutableStateFlow<List<UsStateInfo>>(emptyList())
    val missingStatesForRoute: StateFlow<List<UsStateInfo>> = _missingStatesForRoute.asStateFlow()

    // Active turn-by-turn navigation state
    private val _navigationState = MutableStateFlow(ActiveNavigationState())
    val navigationState: StateFlow<ActiveNavigationState> = _navigationState.asStateFlow()

    // Camera command & auto-follow state
    private val _cameraCommand = MutableStateFlow<CameraTargetCommand?>(null)
    val cameraCommand: StateFlow<CameraTargetCommand?> = _cameraCommand.asStateFlow()

    private val _isCameraFollowing = MutableStateFlow(true)
    val isCameraFollowing: StateFlow<Boolean> = _isCameraFollowing.asStateFlow()

    // Transient UI feedback message
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Low-storage confirmation pending state code
    private val _lowStoragePromptStateCode = MutableStateFlow<String?>(null)
    val lowStoragePromptStateCode: StateFlow<String?> = _lowStoragePromptStateCode.asStateFlow()

    // One-time Persian TTS notice dialog trigger
    private val _showPersianTtsDialog = MutableStateFlow(false)
    val showPersianTtsDialog: StateFlow<Boolean> = _showPersianTtsDialog.asStateFlow()

    private var searchJob: Job? = null
    private var gpsWatchdogJob: Job? = null
    private var simulationJob: Job? = null
    private var arrivalEndJob: Job? = null
    private var lastGpsReceivedMs: Long = 0L
    private var simulatedWrongTurnTicksRemaining: Int = 0
    private var cachedDbHazards: List<RoadHazardPoint> = emptyList()

    init {
        viewModelScope.launch {
            packManager.auditInstalledPacks()
        }

        // Track build version alignment across installed packs
        viewModelScope.launch {
            packsChangedTick.collect {
                _mismatchedBuildVersions.value = packManager.getMismatchedBuildVersions()
            }
        }

        // Sync voice language & enabled state with settings + trigger one-time Persian TTS notice
        viewModelScope.launch {
            settings.collect { s ->
                voiceManager.setLanguage(s.language)
                voiceManager.setVoiceEnabled(s.voiceGuidanceEnabled)
                if (s.language == AppLanguage.PERSIAN && !s.persianTtsNoticeShown) {
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

        // Process live GPS updates during active navigation (ignored when Simulate Drive is driving)
        viewModelScope.launch {
            currentGpsFix.collect { fix ->
                if (fix != null && _navigationState.value.isNavigating && !_navigationState.value.isSimulatingDrive) {
                    lastGpsReceivedMs = System.currentTimeMillis()
                    handleNavigationGpsFix(fix)
                }
            }
        }
    }

    fun startLocationTracking() {
        locationTracker.startTracking()
    }

    fun getInstalledPmtilesFiles() = packManager.getInstalledPmtilesFiles()

    fun getInstalledPlacesDbCount() = packManager.getInstalledPlacesDatabases().size

    fun getStorageStats(): Pair<Long, Long> =
        packManager.getTotalMapStorageUsedBytes() to packManager.getAvailableStorageBytes()

    fun getInstalledDataBuildVersion(): String {
        val versions = statePacks.value
            .filter { it.status == PackInstallStatus.INSTALLED || it.status == PackInstallStatus.UPDATE_AVAILABLE }
            .map { it.installedBuildVersion.ifBlank { it.installedVersion } }
            .filter { it.isNotBlank() }
            .distinct()
        return when {
            versions.isEmpty() -> "—"
            versions.size == 1 -> versions.first()
            else -> versions.joinToString(" / ")
        }
    }

    fun resolveEffectiveNightMode(s: UserSettings, fix: GpsFix?): Boolean {
        return when (s.themeMode) {
            MapThemeMode.DAY -> false
            MapThemeMode.NIGHT -> true
            MapThemeMode.AUTO -> {
                val lat = fix?.lat ?: EUGENE_OR_LAT
                val lon = fix?.lon ?: EUGENE_OR_LON
                SolarNightCalculator.isNightTime(lat, lon)
            }
        }
    }

    fun getEffectiveReferenceLocation(): LatLon {
        val fix = currentGpsFix.value
        if (fix != null) return LatLon(fix.lat, fix.lon)
        return LatLon(EUGENE_OR_LAT, EUGENE_OR_LON)
    }

    // --- SEARCH & MAP PIN ---
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(180L)
            executeSearch(query)
        }
    }

    fun executeSearchNow(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            executeSearch(query)
        }
    }

    private suspend fun executeSearch(query: String) {
        _isSearching.value = true
        try {
            val ref = getEffectiveReferenceLocation()
            val offlineResults = offlineSearch.search(query, ref.lat, ref.lon, limit = 30)
            val useDevOnline = settings.value.devOnlineOregonMode && packManager.getInstalledPlacesDatabases().isEmpty()

            if (offlineResults.isNotEmpty() || !useDevOnline) {
                _searchResults.value = offlineResults
            } else {
                val onlineResults = devOnlineSearch.searchOregonOnline(query, ref.lat, ref.lon, limit = 25)
                _searchResults.value = onlineResults
            }
        } finally {
            _isSearching.value = false
        }
    }

    fun dropPinOnMap(lat: Double, lon: Double) {
        val str = stringsFor(settings.value.language)
        val stateCode = UsStatesCatalog.findBestStateForLocation(lat, lon)?.code.orEmpty()
        val coordsText = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
        val pin = PlaceSearchResult(
            id = "pin_${System.currentTimeMillis()}",
            name = str.droppedPin,
            brand = "",
            category = "pin",
            address = coordsText,
            city = "",
            stateCode = stateCode,
            postcode = "",
            lat = lat,
            lon = lon,
            distanceMeters = 0.0,
            relevanceScore = 100.0,
            source = "pin"
        )
        _selectedPlace.value = pin
        _candidateRoutes.value = emptyList()
        _routeError.value = null
        _missingStatesForRoute.value = emptyList()
        _cameraCommand.value = CameraTargetCommand(lat = lat, lon = lon, zoom = 15.0)
    }

    fun selectPlace(place: PlaceSearchResult) {
        _selectedPlace.value = place
        _candidateRoutes.value = emptyList()
        _routeError.value = null
        _missingStatesForRoute.value = emptyList()
        _cameraCommand.value = CameraTargetCommand(
            lat = place.lat,
            lon = place.lon,
            zoom = 15.0
        )
        viewModelScope.launch {
            savedPlaceDao.insert(
                SavedPlaceEntity(
                    type = SavedPlaceType.RECENT.name,
                    name = place.name,
                    address = place.formattedAddress,
                    category = place.category,
                    lat = place.lat,
                    lon = place.lon,
                    stateCode = place.stateCode
                )
            )
            savedPlaceDao.trimRecent()
        }
    }

    fun clearSelectedPlace() {
        _selectedPlace.value = null
        _candidateRoutes.value = emptyList()
        _routeError.value = null
        _missingStatesForRoute.value = emptyList()
    }

    fun savePlaceAs(place: PlaceSearchResult, type: SavedPlaceType) {
        viewModelScope.launch {
            if (type == SavedPlaceType.HOME || type == SavedPlaceType.WORK) {
                savedPlaceDao.deleteByType(type.name)
            }
            savedPlaceDao.insert(
                SavedPlaceEntity(
                    type = type.name,
                    name = place.name,
                    address = place.formattedAddress,
                    category = place.category,
                    lat = place.lat,
                    lon = place.lon,
                    stateCode = place.stateCode
                )
            )
            val str = stringsFor(settings.value.language)
            val label = when (type) {
                SavedPlaceType.HOME -> str.savedHome
                SavedPlaceType.WORK -> str.savedWork
                SavedPlaceType.FAVORITE -> str.savedFavorites
                SavedPlaceType.RECENT -> str.savedRecent
            }
            _snackbarMessage.value = "$label: ${place.name}"
        }
    }

    fun deleteSavedPlace(id: Long) {
        viewModelScope.launch {
            savedPlaceDao.deleteById(id)
        }
    }

    private fun buildCurrentRouteOptions(s: UserSettings): RouteOptions {
        val now = System.currentTimeMillis()
        // Phase 4: automatically avoid active user-reported Road Closures in Valhalla routing!
        val closedLocations = activeUserReports.value
            .filter { it.type == ReportType.ROAD_CLOSURE.name && it.expiresAt > now }
            .map { LatLon(it.lat, it.lon) }

        return RouteOptions(
            avoidTolls = s.avoidTolls,
            avoidHighways = s.avoidHighways,
            avoidFerries = s.avoidFerries,
            avoidLocations = closedLocations
        )
    }

    // --- ROUTING & TURN-BY-TURN NAVIGATION ---
    fun calculateRoutesToSelectedPlace() {
        val dest = _selectedPlace.value ?: return
        val origin = getEffectiveReferenceLocation()
        val s = settings.value
        val options = buildCurrentRouteOptions(s)

        viewModelScope.launch {
            _isCalculatingRoute.value = true
            _routeError.value = null
            _missingStatesForRoute.value = emptyList()
            val result = routingEngine.calculateRoutes(
                origin = origin,
                destination = LatLon(dest.lat, dest.lon),
                options = options,
                headingDegrees = currentGpsFix.value?.bearing,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )
            _isCalculatingRoute.value = false
            result.onSuccess { routes ->
                _candidateRoutes.value = routes
                _selectedRouteIndex.value = 0
                routes.firstOrNull()?.shape?.let { pts ->
                    if (pts.isNotEmpty()) {
                        val mid = pts[pts.size / 2]
                        _cameraCommand.value = CameraTargetCommand(mid.lat, mid.lon, zoom = 12.0)
                    }
                }
            }.onFailure { err ->
                if (err is MissingStatesException) {
                    _missingStatesForRoute.value = err.missingStates
                    _routeError.value = err.message
                } else {
                    _routeError.value = err.message ?: stringsFor(s.language).missingRoutingTiles
                }
            }
        }
    }

    fun selectCandidateRoute(index: Int) {
        if (index in _candidateRoutes.value.indices) {
            _selectedRouteIndex.value = index
        }
    }

    fun startNavigation() {
        val routes = _candidateRoutes.value
        val route = routes.getOrNull(_selectedRouteIndex.value) ?: return
        val dest = _selectedPlace.value ?: return
        val s = settings.value

        stopInternalNavJobs()
        navController.reset()
        voiceManager.resetDeduplication()
        locationTracker.setNavigationMode(true)
        _isCameraFollowing.value = true
        lastGpsReceivedMs = System.currentTimeMillis()
        simulatedWrongTurnTicksRemaining = 0

        val initialManeuver = route.maneuvers.firstOrNull()
        val startLoc = route.shape.firstOrNull() ?: getEffectiveReferenceLocation()
        _navigationState.value = ActiveNavigationState(
            isNavigating = true,
            route = route,
            destinationName = dest.name,
            destinationAddress = dest.formattedAddress,
            currentManeuverIndex = 0,
            distanceToNextManeuverMeters = initialManeuver?.lengthMeters ?: route.distanceMeters,
            timeToNextManeuverSeconds = initialManeuver?.timeSeconds ?: route.durationSeconds,
            remainingDistanceMeters = route.distanceMeters,
            remainingTimeSeconds = route.durationSeconds,
            snappedLocation = startLoc,
            currentSpeedLimitMph = initialManeuver?.speedLimitMph,
            isSimulatingDrive = s.simulateDriveEnabled,
            showLaneStrip = initialManeuver?.lanes?.isNotEmpty() == true
        )

        // Pre-fetch road hazards (railroad crossings, school zones, stop signs, traffic signals) along route
        viewModelScope.launch {
            if (route.shape.isNotEmpty()) {
                val minLat = route.shape.minOf { it.lat } - 0.05
                val maxLat = route.shape.maxOf { it.lat } + 0.05
                val minLon = route.shape.minOf { it.lon } - 0.05
                val maxLon = route.shape.maxOf { it.lon } + 0.05
                cachedDbHazards = offlineSearch.queryRoadHazardsInBounds(minLat, maxLat, minLon, maxLon)
            }
        }

        // Start foreground service notification (foregroundServiceType="location", no ACCESS_BACKGROUND_LOCATION)
        val firstInstruction = initialManeuver?.instructionEn ?: dest.name
        val etaText = BilingualFormatter.formatDuration(route.durationSeconds, s.language)
        NavigationForegroundService.startOrUpdate(
            context = app,
            title = "${dest.name} • $etaText",
            body = firstInstruction
        )

        // Announce first instruction
        initialManeuver?.let {
            val spoken = voiceManager.speakManeuver(it, it.lengthMeters, s.distanceUnit, s.language, "start")
            _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
        }

        // Switch camera to driving view
        _cameraCommand.value = CameraTargetCommand(
            lat = startLoc.lat,
            lon = startLoc.lon,
            zoom = 16.8,
            tilt = if (s.is3DView) 55.0 else 0.0,
            bearing = currentGpsFix.value?.bearing?.toDouble() ?: 0.0
        )

        if (s.simulateDriveEnabled) {
            startSimulatedDriveLoop()
        } else {
            startGpsLossWatchdog()
        }
    }

    private fun startGpsLossWatchdog() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = viewModelScope.launch {
            while (_navigationState.value.isNavigating && !_navigationState.value.hasArrived) {
                delay(1500L)
                val elapsed = System.currentTimeMillis() - lastGpsReceivedMs
                if (elapsed >= 4500L && !_navigationState.value.isSimulatingDrive) {
                    val deadReckoned = navController.advanceDeadReckoning(_navigationState.value, 1.5)
                    _navigationState.value = deadReckoned
                    if (_isCameraFollowing.value) {
                        deadReckoned.snappedLocation?.let { loc ->
                            val s = settings.value
                            _cameraCommand.value = CameraTargetCommand(
                                lat = loc.lat,
                                lon = loc.lon,
                                zoom = 16.5,
                                tilt = if (s.is3DView) 55.0 else 0.0,
                                bearing = if (s.is3DView) deadReckoned.snappedBearing.toDouble() else 0.0
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startSimulatedDriveLoop() {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            while (_navigationState.value.isNavigating && !_navigationState.value.hasArrived) {
                delay(1000L)
                val wrongTurnOffset = if (simulatedWrongTurnTicksRemaining > 0) {
                    simulatedWrongTurnTicksRemaining--
                    120.0
                } else {
                    0.0
                }
                val simFix = navController.computeSimulatedFix(
                    currentState = _navigationState.value,
                    elapsedSeconds = 2.0,
                    wrongTurnOffsetMeters = wrongTurnOffset
                )
                if (simFix != null) {
                    handleNavigationGpsFix(simFix)
                }
            }
        }
    }

    fun triggerSimulatedWrongTurn() {
        if (_navigationState.value.isNavigating && _navigationState.value.isSimulatingDrive) {
            simulatedWrongTurnTicksRemaining = 4
        }
    }

    fun dismissJunctionPreview() {
        val currentIdx = _navigationState.value.currentManeuver?.index ?: return
        _navigationState.value = _navigationState.value.copy(
            showJunctionPreview = false,
            dismissedJunctionManeuverIndex = currentIdx
        )
    }

    fun repeatLastVoiceInstruction() {
        voiceManager.repeatLastInstruction(settings.value.language)
    }

    fun stopNavigation() {
        stopInternalNavJobs()
        locationTracker.setNavigationMode(false)
        _navigationState.value = ActiveNavigationState()
        navController.reset()
        NavigationForegroundService.stop(app)
        _isCameraFollowing.value = true
        val ref = getEffectiveReferenceLocation()
        _cameraCommand.value = CameraTargetCommand(lat = ref.lat, lon = ref.lon, zoom = 14.0, tilt = 0.0, bearing = 0.0)
    }

    private fun stopInternalNavJobs() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = null
        simulationJob?.cancel()
        simulationJob = null
        arrivalEndJob?.cancel()
        arrivalEndJob = null
    }

    private fun handleNavigationGpsFix(fix: GpsFix) {
        val s = settings.value
        val wasArrived = _navigationState.value.hasArrived
        val result = navController.processGpsFix(
            currentState = _navigationState.value,
            fix = fix,
            dbHazards = cachedDbHazards,
            userReports = activeUserReports.value,
            unit = s.distanceUnit,
            language = s.language,
            speedAudioEnabled = s.speedWarningAudioEnabled,
            settings = s
        )
        _navigationState.value = result.state

        if (result.triggerOverspeedChime) {
            voiceManager.playOverspeedChime()
        }

        if (result.state.hasArrived && !wasArrived) {
            val arrivalManeuverType = result.state.route?.maneuvers?.lastOrNull()?.type
                ?: com.example.navigation.ManeuverType.ARRIVE
            val spoken = voiceManager.speakArrival(arrivalManeuverType, result.state.destinationName, s.language)
            _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
            arrivalEndJob?.cancel()
            arrivalEndJob = viewModelScope.launch {
                delay(6000L)
                if (_navigationState.value.hasArrived) {
                    stopNavigation()
                }
            }
        } else {
            result.maneuverToSpeak?.let { (maneuver, stage) ->
                val spoken = voiceManager.speakManeuver(
                    maneuver = maneuver,
                    distanceMeters = result.state.distanceToNextManeuverMeters,
                    unit = s.distanceUnit,
                    language = s.language,
                    announcementStage = stage,
                    includeLaneGuidance = maneuver.lanes.isNotEmpty()
                )
                _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
            }
            result.alertToSpeak?.let { alert ->
                voiceManager.speakAlert(alert, s.language)
            }
        }

        // Update persistent foreground notification
        val currentManeuver = result.state.currentManeuver
        if (currentManeuver != null && result.state.isNavigating) {
            val distStr = BilingualFormatter.formatDistance(
                result.state.distanceToNextManeuverMeters,
                s.distanceUnit,
                s.language
            )
            val etaStr = BilingualFormatter.formatDuration(result.state.remainingTimeSeconds, s.language)
            NavigationForegroundService.startOrUpdate(
                context = app,
                title = "$distStr • $etaStr",
                body = currentManeuver.streetNames.firstOrNull()?.ifBlank { currentManeuver.instructionEn }
                    ?: currentManeuver.instructionEn
            )
        }

        // Follow driving camera unless user manually panned away
        if (_isCameraFollowing.value) {
            val targetLoc = result.state.snappedLocation ?: LatLon(fix.lat, fix.lon)
            val targetBearing = if (s.is3DView) result.state.snappedBearing.toDouble() else 0.0
            _cameraCommand.value = CameraTargetCommand(
                lat = targetLoc.lat,
                lon = targetLoc.lon,
                zoom = if (fix.speedMps > 22f) 15.8 else 16.8,
                tilt = if (s.is3DView) 55.0 else 0.0,
                bearing = targetBearing
            )
        }

        if (result.shouldReroute) {
            triggerAutomaticReroute(fix)
        }
    }

    private fun triggerAutomaticReroute(fix: GpsFix) {
        val dest = _selectedPlace.value ?: return
        val s = settings.value
        voiceManager.speakRerouting(s.language)
        viewModelScope.launch {
            val res = routingEngine.calculateRoutes(
                origin = LatLon(fix.lat, fix.lon),
                destination = LatLon(dest.lat, dest.lon),
                options = buildCurrentRouteOptions(s),
                headingDegrees = fix.bearing,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )
            res.onSuccess { routes ->
                val newRoute = routes.firstOrNull() ?: return@onSuccess
                navController.reset()
                voiceManager.resetDeduplication()
                val firstManeuver = newRoute.maneuvers.firstOrNull()
                _navigationState.value = _navigationState.value.copy(
                    route = newRoute,
                    currentManeuverIndex = 0,
                    snappedShapeIndex = 0,
                    isOffRoute = false,
                    isRerouting = false,
                    remainingDistanceMeters = newRoute.distanceMeters,
                    remainingTimeSeconds = newRoute.durationSeconds,
                    dismissedJunctionManeuverIndex = -1
                )
                firstManeuver?.let {
                    voiceManager.speakManeuver(it, it.lengthMeters, s.distanceUnit, s.language, "reroute_start")
                }
            }.onFailure {
                _navigationState.value = _navigationState.value.copy(isRerouting = false)
            }
        }
    }

    // --- WAZE-STYLE LOCAL DRIVING REPORTS (8 TYPES + NOTES + EXPORT/IMPORT) ---
    fun submitUserReport(type: ReportType) {
        val loc = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
        val bearing = if (_navigationState.value.isNavigating) {
            _navigationState.value.snappedBearing
        } else {
            currentGpsFix.value?.bearing ?: -1f
        }
        val roadName = _navigationState.value.currentManeuver?.streetNames?.firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val expiresAt = now + type.defaultTtlMinutes * 60_000L

        viewModelScope.launch {
            userReportDao.insert(
                UserReportEntity(
                    type = type.name,
                    lat = loc.lat,
                    lon = loc.lon,
                    bearing = bearing,
                    roadName = roadName,
                    createdAt = now,
                    expiresAt = expiresAt
                )
            )
            _nowTick.value = System.currentTimeMillis()
            _snackbarMessage.value = stringsFor(settings.value.language).reportSavedToast
        }
    }

    fun confirmUserReport(reportId: Long) {
        viewModelScope.launch {
            val newExpiry = System.currentTimeMillis() + 60 * 60_000L
            userReportDao.confirmReport(reportId, newExpiry)
        }
    }

    fun updateReportNote(reportId: Long, note: String) {
        viewModelScope.launch {
            userReportDao.updateNote(reportId, note.trim())
            _nowTick.value = System.currentTimeMillis()
        }
    }

    fun dismissUserReport(reportId: Long) {
        viewModelScope.launch {
            userReportDao.deleteById(reportId)
            _nowTick.value = System.currentTimeMillis()
        }
    }

    fun exportReportsToUri(uri: Uri) {
        viewModelScope.launch {
            val str = stringsFor(settings.value.language)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val reports = userReportDao.getActiveOnce(System.currentTimeMillis())
                    val json = BackupAndTransferManager.exportReportsJson(reports)
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open output stream")
                }
            }
            _snackbarMessage.value = if (result.isSuccess) str.backupExportSuccess else str.backupTransferFailed
        }
    }

    fun importReportsFromUri(uri: Uri) {
        viewModelScope.launch {
            val str = stringsFor(settings.value.language)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                        ?: error("Cannot read file")
                    val imported = BackupAndTransferManager.importReportsJson(json)
                    for (r in imported) {
                        if (userReportDao.getByUuid(r.uuid) == null) {
                            userReportDao.insert(r)
                        }
                    }
                    imported.size
                }
            }
            _nowTick.value = System.currentTimeMillis()
            _snackbarMessage.value = if (result.isSuccess) {
                "${str.backupImportSuccess} (${result.getOrDefault(0)})"
            } else {
                str.backupTransferFailed
            }
        }
    }

    // --- FINAL STEP: FULL SETTINGS & SAVED PLACES EXPORT / IMPORT FOR NEW PHONE ---
    fun exportFullBackupToUri(uri: Uri) {
        viewModelScope.launch {
            val str = stringsFor(settings.value.language)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val s = settings.value
                    val places = savedPlaceDao.getAllOnce()
                    val reports = userReportDao.getActiveOnce(System.currentTimeMillis())
                    val json = BackupAndTransferManager.exportFullBackupJson(s, places, reports)
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open output stream")
                }
            }
            _snackbarMessage.value = if (result.isSuccess) str.backupExportSuccess else str.backupTransferFailed
        }
    }

    fun importFullBackupFromUri(uri: Uri) {
        viewModelScope.launch {
            val str = stringsFor(settings.value.language)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                        ?: error("Cannot read backup file")
                    val parsed = BackupAndTransferManager.importFullBackupJson(json)
                    parsed.settings?.let { s ->
                        prefs.setLanguage(s.language)
                        prefs.setDistanceUnit(s.distanceUnit)
                        prefs.setThemeMode(s.themeMode)
                        prefs.setVoiceGuidanceEnabled(s.voiceGuidanceEnabled)
                        prefs.setSpeedWarningAudioEnabled(s.speedWarningAudioEnabled)
                        prefs.setSpeedWarningThresholdMph(s.speedWarningThresholdMph)
                        prefs.setJunctionPreviewEnabled(s.junctionPreviewEnabled)
                        prefs.setAlertSpeedDropEnabled(s.alertSpeedDropEnabled)
                        prefs.setAlertSharpCurveEnabled(s.alertSharpCurveEnabled)
                        prefs.setAlertTollBoothEnabled(s.alertTollBoothEnabled)
                        prefs.setAlertRailroadEnabled(s.alertRailroadEnabled)
                        prefs.setAlertSchoolZoneEnabled(s.alertSchoolZoneEnabled)
                        prefs.setAlertStopSignEnabled(s.alertStopSignEnabled)
                        prefs.set3DView(s.is3DView)
                        prefs.setAvoidTolls(s.avoidTolls)
                        prefs.setAvoidHighways(s.avoidHighways)
                        prefs.setAvoidFerries(s.avoidFerries)
                        prefs.setGitHubRepository(s.githubOwner, s.githubRepo)
                        prefs.setPackBaseUrl(s.packBaseUrl)
                        prefs.setOnboardingCompleted(true)
                    }
                    for (place in parsed.savedPlaces) {
                        if (place.type == SavedPlaceType.HOME.name || place.type == SavedPlaceType.WORK.name) {
                            savedPlaceDao.deleteByType(place.type)
                        }
                        savedPlaceDao.insert(place)
                    }
                    for (report in parsed.reports) {
                        if (userReportDao.getByUuid(report.uuid) == null) {
                            userReportDao.insert(report)
                        }
                    }
                }
            }
            _nowTick.value = System.currentTimeMillis()
            _snackbarMessage.value = if (result.isSuccess) str.backupImportSuccess else str.backupTransferFailed
        }
    }

    // --- STATE MAP PACKS ---
    fun refreshManifest() {
        val url = settings.value.effectivePackBaseUrl
        viewModelScope.launch {
            packManager.fetchManifest(url)
        }
    }

    fun requestDownloadStatePack(stateCode: String) {
        val pack = statePacks.value.find { it.stateCode == stateCode } ?: return
        val baseUrl = settings.value.effectivePackBaseUrl
        if (pack.totalSizeBytes > 0L && packManager.isLowStorageForPack(pack.totalSizeBytes)) {
            _lowStoragePromptStateCode.value = stateCode
        } else {
            packManager.startOrResumeDownload(stateCode, baseUrl)
        }
    }

    fun confirmLowStorageDownload() {
        val code = _lowStoragePromptStateCode.value ?: return
        _lowStoragePromptStateCode.value = null
        packManager.startOrResumeDownload(code, settings.value.effectivePackBaseUrl)
    }

    fun dismissLowStoragePrompt() {
        _lowStoragePromptStateCode.value = null
    }

    fun updateAllOutdatedPacks() {
        packManager.updateAllOutdatedPacks(settings.value.effectivePackBaseUrl)
    }

    fun pauseStatePackDownload(stateCode: String) {
        packManager.pauseDownload(stateCode)
    }

    fun deleteStatePack(stateCode: String) {
        viewModelScope.launch {
            packManager.deletePack(stateCode)
        }
    }

    fun importLocalPackFile(stateCode: String, uri: Uri, displayName: String?) {
        viewModelScope.launch {
            val str = stringsFor(settings.value.language)
            val res = packManager.importLocalUri(stateCode, uri, displayName)
            _snackbarMessage.value = res.fold(
                onSuccess = { "${str.importSuccess}: $it" },
                onFailure = { "${str.importFailed}: ${it.message.orEmpty()}" }
            )
        }
    }

    // --- CAMERA ---
    fun pauseCameraFollow() {
        if (_navigationState.value.isNavigating) {
            _isCameraFollowing.value = false
        }
    }

    fun toggle3DView() {
        val next = !settings.value.is3DView
        viewModelScope.launch { prefs.set3DView(next) }
        val ref = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
        _cameraCommand.value = CameraTargetCommand(
            lat = ref.lat,
            lon = ref.lon,
            zoom = if (_navigationState.value.isNavigating) 16.5 else 14.5,
            tilt = if (next) 55.0 else 0.0,
            bearing = if (next) (currentGpsFix.value?.bearing?.toDouble() ?: 0.0) else 0.0
        )
    }

    fun recenterCamera() {
        _isCameraFollowing.value = true
        val ref = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
        val s = settings.value
        _cameraCommand.value = CameraTargetCommand(
            lat = ref.lat,
            lon = ref.lon,
            zoom = if (_navigationState.value.isNavigating) 16.8 else 14.5,
            tilt = if (s.is3DView && _navigationState.value.isNavigating) 55.0 else 0.0,
            bearing = if (s.is3DView && _navigationState.value.isNavigating) {
                _navigationState.value.snappedBearing.toDouble()
            } else 0.0
        )
    }

    // --- SETTINGS ---
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            prefs.setLanguage(language)
            val available = voiceManager.checkPersianAvailability()
            if (language == AppLanguage.PERSIAN && !available && !settings.value.persianTtsNoticeShown) {
                _showPersianTtsDialog.value = true
            }
        }
    }

    fun dismissPersianTtsNotice() {
        _showPersianTtsDialog.value = false
        viewModelScope.launch { prefs.markPersianTtsNoticeShown() }
    }

    fun setDistanceUnit(unit: DistanceUnit) {
        viewModelScope.launch { prefs.setDistanceUnit(unit) }
    }

    fun setThemeMode(mode: MapThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setVoiceGuidanceEnabled(enabled) }
    }

    fun setSpeedAudioEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSpeedWarningAudioEnabled(enabled) }
    }

    fun setSpeedWarningThresholdMph(thresholdMph: Int) {
        viewModelScope.launch { prefs.setSpeedWarningThresholdMph(thresholdMph) }
    }

    fun setJunctionPreviewEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setJunctionPreviewEnabled(enabled) }
    }

    fun setAlertSpeedDropEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertSpeedDropEnabled(enabled) }
    }

    fun setAlertSharpCurveEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertSharpCurveEnabled(enabled) }
    }

    fun setAlertTollBoothEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertTollBoothEnabled(enabled) }
    }

    fun setAlertRailroadEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertRailroadEnabled(enabled) }
    }

    fun setAlertSchoolZoneEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertSchoolZoneEnabled(enabled) }
    }

    fun setAlertStopSignEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertStopSignEnabled(enabled) }
    }

    fun setAvoidTolls(avoid: Boolean) {
        viewModelScope.launch {
            prefs.setAvoidTolls(avoid)
            if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlace()
        }
    }

    fun setAvoidHighways(avoid: Boolean) {
        viewModelScope.launch {
            prefs.setAvoidHighways(avoid)
            if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlace()
        }
    }

    fun setAvoidFerries(avoid: Boolean) {
        viewModelScope.launch {
            prefs.setAvoidFerries(avoid)
            if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlace()
        }
    }

    fun saveGitHubRepository(owner: String, repo: String) {
        viewModelScope.launch {
            prefs.setGitHubRepository(owner, repo)
            val cleanedOwner = owner.trim()
            val cleanedRepo = repo.trim()
            if (cleanedOwner.isNotEmpty() && cleanedRepo.isNotEmpty()) {
                packManager.fetchManifest("https://github.com/$cleanedOwner/$cleanedRepo/releases/latest/download")
            }
        }
    }

    fun savePackBaseUrl(url: String) {
        viewModelScope.launch {
            prefs.setPackBaseUrl(url)
            val effective = url.trim().trimEnd('/').ifEmpty { settings.value.effectivePackBaseUrl }
            if (effective.isNotBlank()) {
                packManager.fetchManifest(effective)
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setOnboardingCompleted(true) }
    }

    fun setDevOnlineOregonMode(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDevOnlineOregonMode(enabled)
            if (enabled) {
                _cameraCommand.value = CameraTargetCommand(
                    lat = EUGENE_OR_LAT,
                    lon = EUGENE_OR_LON,
                    zoom = 12.8
                )
            }
        }
    }

    fun setSimulateDriveEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSimulateDriveEnabled(enabled) }
    }

    fun testVoiceGuidance() {
        voiceManager.checkPersianAvailability()
        voiceManager.speakTestSample(settings.value.language)
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    companion object {
        const val EUGENE_OR_LAT = 44.0521
        const val EUGENE_OR_LON = -123.0868
    }
}
