package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RahYabApplication
import com.example.data.local.PackInstallStatus
import com.example.data.local.ReportType
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.local.UserReportEntity
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
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteOptions
import com.example.navigation.SolarNightCalculator
import com.example.service.NavigationForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        packManager.manifest
    ) { installedList, manifest ->
        val installedByCode = installedList.associateBy { it.stateCode.lowercase() }
        val manifestByCode = manifest?.states?.associateBy { it.stateCode.lowercase() }.orEmpty()

        UsStatesCatalog.allStates.map { state ->
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
                status = status,
                installedVersion = inst?.installedVersion.orEmpty(),
                availableVersion = remote?.version ?: inst?.availableVersion.orEmpty(),
                totalSizeBytes = totalBytes,
                downloadedBytes = inst?.downloadedBytes ?: 0L,
                installedBytesOnDisk = packManager.getStateDiskBytes(state.code),
                hasPmtiles = inst?.hasPmtiles ?: false,
                hasValhalla = inst?.hasValhalla ?: false,
                hasPlacesDb = inst?.hasPlacesDb ?: false,
                hasManifestEntry = remote != null,
                errorMessage = inst?.errorMessage
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Search UI state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _selectedPlace = MutableStateFlow<PlaceSearchResult?>(null)
    val selectedPlace: StateFlow<PlaceSearchResult?> = _selectedPlace.asStateFlow()

    // Route preview state (up to 3 alternatives)
    private val _candidateRoutes = MutableStateFlow<List<OfflineRoute>>(emptyList())
    val candidateRoutes: StateFlow<List<OfflineRoute>> = _candidateRoutes.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    private val _isCalculatingRoute = MutableStateFlow(false)
    val isCalculatingRoute: StateFlow<Boolean> = _isCalculatingRoute.asStateFlow()

    private val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()

    // Active turn-by-turn navigation state
    private val _navigationState = MutableStateFlow(ActiveNavigationState())
    val navigationState: StateFlow<ActiveNavigationState> = _navigationState.asStateFlow()

    // Camera command stream for MapLibreMapContainer
    private val _cameraCommand = MutableStateFlow<CameraTargetCommand?>(null)
    val cameraCommand: StateFlow<CameraTargetCommand?> = _cameraCommand.asStateFlow()

    // Transient UI feedback message
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Low-storage confirmation pending state code
    private val _lowStoragePromptStateCode = MutableStateFlow<String?>(null)
    val lowStoragePromptStateCode: StateFlow<String?> = _lowStoragePromptStateCode.asStateFlow()

    private var searchJob: Job? = null
    private var cachedDbHazards: List<RoadHazardPoint> = emptyList()

    init {
        viewModelScope.launch {
            packManager.auditInstalledPacks()
        }

        // Sync voice language & enabled state with settings
        viewModelScope.launch {
            settings.collect { s ->
                voiceManager.setLanguage(s.language)
                voiceManager.setVoiceEnabled(s.voiceGuidanceEnabled)
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

        // Process live GPS updates during active navigation
        viewModelScope.launch {
            currentGpsFix.collect { fix ->
                if (fix != null && _navigationState.value.isNavigating) {
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

    /**
     * Reference location used for search ranking and route origin when GPS fix isn't acquired yet
     * (defaults to Eugene, Oregon when Oregon is installed or Dev Oregon Online Mode is active).
     */
    fun getEffectiveReferenceLocation(): LatLon {
        val fix = currentGpsFix.value
        if (fix != null) return LatLon(fix.lat, fix.lon)
        return LatLon(EUGENE_OR_LAT, EUGENE_OR_LON)
    }

    // --- SEARCH ---
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

    fun selectPlace(place: PlaceSearchResult) {
        _selectedPlace.value = place
        _candidateRoutes.value = emptyList()
        _routeError.value = null
        _cameraCommand.value = CameraTargetCommand(
            lat = place.lat,
            lon = place.lon,
            zoom = 15.0
        )
        // Save into Recent places
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

    // --- ROUTING & TURN-BY-TURN NAVIGATION ---
    fun calculateRoutesToSelectedPlace() {
        val dest = _selectedPlace.value ?: return
        val origin = getEffectiveReferenceLocation()
        val s = settings.value
        val options = RouteOptions(
            avoidTolls = s.avoidTolls,
            avoidHighways = s.avoidHighways,
            avoidFerries = s.avoidFerries
        )

        viewModelScope.launch {
            _isCalculatingRoute.value = true
            _routeError.value = null
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
                _routeError.value = err.message ?: stringsFor(s.language).missingRoutingTiles
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

        navController.reset()
        voiceManager.resetDeduplication()

        val initialManeuver = route.maneuvers.firstOrNull()
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
            currentSpeedLimitMph = initialManeuver?.speedLimitMph
        )

        // Pre-fetch road hazards (railroad crossings, school zones, stop signs) along route bounding box
        viewModelScope.launch {
            if (route.shape.isNotEmpty()) {
                val minLat = route.shape.minOf { it.lat } - 0.05
                val maxLat = route.shape.maxOf { it.lat } + 0.05
                val minLon = route.shape.minOf { it.lon } - 0.05
                val maxLon = route.shape.maxOf { it.lon } + 0.05
                cachedDbHazards = offlineSearch.queryRoadHazardsInBounds(minLat, maxLat, minLon, maxLon)
            }
        }

        // Start foreground service notification for background turn-by-turn navigation
        val firstInstruction = initialManeuver?.instructionEn ?: dest.name
        val etaText = BilingualFormatter.formatDuration(route.durationSeconds, s.language)
        NavigationForegroundService.startOrUpdate(
            context = app,
            title = "${dest.name} • $etaText",
            body = firstInstruction
        )

        // Announce first instruction
        initialManeuver?.let {
            voiceManager.speakManeuver(it, it.lengthMeters, s.distanceUnit, s.language, "start")
        }

        // Switch camera to 3D heading-up driving perspective
        val ref = getEffectiveReferenceLocation()
        _cameraCommand.value = CameraTargetCommand(
            lat = ref.lat,
            lon = ref.lon,
            zoom = 16.8,
            tilt = if (s.is3DView) 55.0 else 0.0,
            bearing = currentGpsFix.value?.bearing?.toDouble() ?: 0.0
        )
    }

    fun stopNavigation() {
        _navigationState.value = ActiveNavigationState()
        navController.reset()
        NavigationForegroundService.stop(app)
        val ref = getEffectiveReferenceLocation()
        _cameraCommand.value = CameraTargetCommand(lat = ref.lat, lon = ref.lon, zoom = 14.0, tilt = 0.0, bearing = 0.0)
    }

    private fun handleNavigationGpsFix(fix: GpsFix) {
        val s = settings.value
        val result = navController.processGpsFix(
            currentState = _navigationState.value,
            fix = fix,
            dbHazards = cachedDbHazards,
            userReports = activeUserReports.value,
            unit = s.distanceUnit,
            language = s.language,
            speedAudioEnabled = s.speedWarningAudioEnabled
        )
        _navigationState.value = result.state

        if (result.triggerOverspeedChime) {
            voiceManager.playOverspeedChime()
        }
        result.maneuverToSpeak?.let { (maneuver, stage) ->
            voiceManager.speakManeuver(
                maneuver = maneuver,
                distanceMeters = result.state.distanceToNextManeuverMeters,
                unit = s.distanceUnit,
                language = s.language,
                announcementStage = stage
            )
        }
        result.alertToSpeak?.let { alert ->
            voiceManager.speakAlert(alert, s.language)
        }

        // Update foreground service notification
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

        // Follow driving camera
        _cameraCommand.value = CameraTargetCommand(
            lat = fix.lat,
            lon = fix.lon,
            zoom = if (fix.speedMps > 22f) 15.8 else 16.8,
            tilt = if (s.is3DView) 55.0 else 0.0,
            bearing = if (s.is3DView) fix.bearing.toDouble() else 0.0
        )

        if (result.shouldReroute) {
            triggerAutomaticReroute(fix)
        }
    }

    private fun triggerAutomaticReroute(fix: GpsFix) {
        val dest = _selectedPlace.value ?: return
        val s = settings.value
        viewModelScope.launch {
            val res = routingEngine.calculateRoutes(
                origin = LatLon(fix.lat, fix.lon),
                destination = LatLon(dest.lat, dest.lon),
                options = RouteOptions(s.avoidTolls, s.avoidHighways, s.avoidFerries),
                headingDegrees = fix.bearing,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )
            res.onSuccess { routes ->
                val newRoute = routes.firstOrNull() ?: return@onSuccess
                navController.reset()
                voiceManager.resetDeduplication()
                _navigationState.value = _navigationState.value.copy(
                    route = newRoute,
                    currentManeuverIndex = 0,
                    isOffRoute = false,
                    isRerouting = false,
                    remainingDistanceMeters = newRoute.distanceMeters,
                    remainingTimeSeconds = newRoute.durationSeconds
                )
            }.onFailure {
                _navigationState.value = _navigationState.value.copy(isRerouting = false)
            }
        }
    }

    // --- WAZE-STYLE LOCAL DRIVING REPORTS ---
    fun submitUserReport(type: ReportType) {
        val loc = getEffectiveReferenceLocation()
        val roadName = _navigationState.value.currentManeuver?.streetNames?.firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val expiresAt = now + type.defaultTtlMinutes * 60_000L

        viewModelScope.launch {
            userReportDao.insert(
                UserReportEntity(
                    type = type.name,
                    lat = loc.lat,
                    lon = loc.lon,
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
            val newExpiry = System.currentTimeMillis() + 45 * 60_000L
            userReportDao.confirmReport(reportId, newExpiry)
        }
    }

    fun dismissUserReport(reportId: Long) {
        viewModelScope.launch {
            userReportDao.deleteById(reportId)
            _nowTick.value = System.currentTimeMillis()
        }
    }

    // --- STATE MAP PACKS ---
    fun refreshManifest() {
        val url = settings.value.packBaseUrl
        viewModelScope.launch {
            packManager.fetchManifest(url)
        }
    }

    fun requestDownloadStatePack(stateCode: String) {
        val pack = statePacks.value.find { it.stateCode == stateCode } ?: return
        if (pack.totalSizeBytes > 0L && packManager.isLowStorageForPack(pack.totalSizeBytes)) {
            _lowStoragePromptStateCode.value = stateCode
        } else {
            packManager.startOrResumeDownload(stateCode, settings.value.packBaseUrl)
        }
    }

    fun confirmLowStorageDownload() {
        val code = _lowStoragePromptStateCode.value ?: return
        _lowStoragePromptStateCode.value = null
        packManager.startOrResumeDownload(code, settings.value.packBaseUrl)
    }

    fun dismissLowStoragePrompt() {
        _lowStoragePromptStateCode.value = null
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

    // --- SETTINGS ---
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            prefs.setLanguage(language)
            voiceManager.checkPersianAvailability()
        }
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

    fun toggle3DView() {
        val next = !settings.value.is3DView
        viewModelScope.launch { prefs.set3DView(next) }
        val ref = getEffectiveReferenceLocation()
        _cameraCommand.value = CameraTargetCommand(
            lat = ref.lat,
            lon = ref.lon,
            zoom = if (_navigationState.value.isNavigating) 16.5 else 14.5,
            tilt = if (next) 55.0 else 0.0,
            bearing = if (next) (currentGpsFix.value?.bearing?.toDouble() ?: 0.0) else 0.0
        )
    }

    fun recenterCamera() {
        val ref = getEffectiveReferenceLocation()
        val s = settings.value
        _cameraCommand.value = CameraTargetCommand(
            lat = ref.lat,
            lon = ref.lon,
            zoom = if (_navigationState.value.isNavigating) 16.8 else 14.5,
            tilt = if (s.is3DView && _navigationState.value.isNavigating) 55.0 else 0.0,
            bearing = if (s.is3DView && _navigationState.value.isNavigating) (currentGpsFix.value?.bearing?.toDouble() ?: 0.0) else 0.0
        )
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

    fun savePackBaseUrl(url: String) {
        viewModelScope.launch {
            prefs.setPackBaseUrl(url)
            if (url.isNotBlank()) {
                packManager.fetchManifest(url)
            }
        }
    }

    fun setDevOnlineOregonMode(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDevOnlineOregonMode(enabled)
            if (enabled) {
                // Center map on Eugene, Oregon so the user immediately sees Oregon roads & can test Best Buy search!
                _cameraCommand.value = CameraTargetCommand(
                    lat = EUGENE_OR_LAT,
                    lon = EUGENE_OR_LON,
                    zoom = 12.8
                )
            }
        }
    }

    fun testVoiceGuidance() {
        voiceManager.checkPersianAvailability()
        voiceManager.speakTestSample(settings.value.language)
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    companion object {
        // Eugene, Oregon coordinates (used as initial Oregon view / emulator reference when GPS is unavailable)
        const val EUGENE_OR_LAT = 44.0521
        const val EUGENE_OR_LON = -123.0868
    }
}
