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
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PlaceSearchResult
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.MapThemeMode
import com.example.i18n.stringsFor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.CameraFollowController
import com.example.navigation.CompassTracker
import com.example.navigation.GpsFix
import com.example.navigation.LastRouteDiagnostics
import com.example.navigation.LatLon
import com.example.navigation.LocationTracker
import com.example.navigation.MissingStatesException
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteOptions
import com.example.navigation.RoutePreference
import com.example.navigation.SolarNightCalculator
import com.example.navigation.VoiceGuidanceManager
import com.example.service.NavigationForegroundService
import com.example.util.CrashLogManager
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
    val animationDurationMs: Int = 500,
    val anchorLowerThird: Boolean = false,
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
    private val compassTracker = app.compassTracker
    private val voiceManager = app.voiceGuidanceManager
    private val savedPlaceDao = app.database.savedPlaceDao()
    private val userReportDao = app.database.userReportDao()
    private val installedPackDao = app.database.installedPackDao()
    private val navController = NavigationSessionController()
    private val cameraFollowController = CameraFollowController()

    val settings: StateFlow<UserSettings> = prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings()
    )

    val currentGpsFix: StateFlow<GpsFix?> = locationTracker.currentFix
    val compassHeadingDegrees: StateFlow<Float?> = compassTracker.headingDegrees
    val compassSensorAccuracy: StateFlow<Int> = compassTracker.sensorAccuracy
    val isCompassLowAccuracy: StateFlow<Boolean> = compassTracker.isLowAccuracy
    val isPersianTtsAvailable: StateFlow<Boolean> = voiceManager.isPersianVoiceAvailable
    val ttsDiagnostics: StateFlow<VoiceGuidanceManager.TtsDiagnosticsInfo> = voiceManager.ttsDiagnostics
    val lastRouteDiagnostics: StateFlow<LastRouteDiagnostics?> = routingEngine.lastRouteDiagnostics
    val isFetchingManifest: StateFlow<Boolean> = packManager.isFetchingManifest
    val manifestError: StateFlow<String?> = packManager.manifestError
    val packsChangedTick: StateFlow<Long> = packManager.packsChangedTick
    val indexingStatusMessage: StateFlow<String?> = packManager.indexingStatusMessage

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
                placesSource = inst?.placesSource?.takeIf { it.isNotBlank() } ?: remote?.placesSource ?: "overture+osm",
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

    // Compass / map orientation mode (true = Heading-Up, false = North-Up)
    private val _isHeadingUpMode = MutableStateFlow(true)
    val isHeadingUpMode: StateFlow<Boolean> = _isHeadingUpMode.asStateFlow()

    // Smoothly blended user arrow & heading-up direction (compass < 3 mph, GPS bearing > 3 mph)
    private val _userHeadingDegrees = MutableStateFlow(0f)
    val userHeadingDegrees: StateFlow<Float> = _userHeadingDegrees.asStateFlow()
    private var hasHeadingSample = false
    private var lastHeadingEmitElapsedMs = 0L

    // Transient UI feedback message
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Low-storage confirmation pending state code
    private val _lowStoragePromptStateCode = MutableStateFlow<String?>(null)
    val lowStoragePromptStateCode: StateFlow<String?> = _lowStoragePromptStateCode.asStateFlow()

    // One-time Persian TTS notice dialog trigger
    private val _showPersianTtsDialog = MutableStateFlow(false)
    val showPersianTtsDialog: StateFlow<Boolean> = _showPersianTtsDialog.asStateFlow()

    // Simulation speed multiplier (1x, 2x, 4x, 8x, 16x, 32x) — always resets to 1x when simulation stops
    private val _simulationSpeedMultiplier = MutableStateFlow(1)
    val simulationSpeedMultiplier: StateFlow<Int> = _simulationSpeedMultiplier.asStateFlow()

    // Visible speed cameras & red light cameras (shown on map from zoom 13 & used for free-drive warnings)
    private val _visibleCameras = MutableStateFlow<List<RoadHazardPoint>>(emptyList())
    val visibleCameras: StateFlow<List<RoadHazardPoint>> = _visibleCameras.asStateFlow()

    // Approaching camera alert during free driving (no active route)
    private val _freeDriveAheadAlert = MutableStateFlow<AheadAlert?>(null)
    val freeDriveAheadAlert: StateFlow<AheadAlert?> = _freeDriveAheadAlert.asStateFlow()

    private var searchJob: Job? = null
    private var gpsWatchdogJob: Job? = null
    private var simulationJob: Job? = null
    private var arrivalEndJob: Job? = null
    private var autoRecenterJob: Job? = null
    private var cameraFetchJob: Job? = null
    private var lastCameraFetchLoc: LatLon? = null
    private var lastGpsReceivedMs: Long = 0L
    private var simulatedWrongTurnTicksRemaining: Int = 0
    private var simulatedClockMs: Long = 0L
    private var cachedDbHazards: List<RoadHazardPoint> = emptyList()

    init {
        viewModelScope.launch {
            packManager.auditInstalledPacks()
        }

        // Track build version alignment across installed packs and refresh nearby cameras
        viewModelScope.launch {
            packsChangedTick.collect {
                _mismatchedBuildVersions.value = packManager.getMismatchedBuildVersions()
                val ref = getEffectiveReferenceLocation()
                refreshNearbyCameras(ref.lat, ref.lon, force = true)
            }
        }

        // Sync voice language & enabled state with settings + trigger one-time Persian TTS notice
        viewModelScope.launch {
            settings.collect { s ->
                voiceManager.setUseEnglishVoiceInPersianMode(s.useEnglishVoiceInPersianMode)
                voiceManager.setLanguage(s.language)
                voiceManager.setVoiceEnabled(s.voiceGuidanceEnabled)
                if (s.language == AppLanguage.PERSIAN && !s.persianTtsNoticeShown && !s.useEnglishVoiceInPersianMode) {
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

        // Start hardware compass sensor (automatically stops in background when not navigating)
        compassTracker.startIfActive()

        // Smoothly combine compass (< 3 mph) and GPS bearing (> 3 mph) for position arrow & heading-up map
        viewModelScope.launch {
            combine(
                currentGpsFix,
                compassTracker.headingDegrees,
                _navigationState
            ) { fix, compassHeading, nav ->
                when {
                    nav.isNavigating && nav.isSimulatingDrive -> nav.snappedBearing
                    fix != null && fix.speedMps > LocationTracker.MIN_BEARING_SPEED_MPS && fix.hasBearing -> {
                        if (nav.isNavigating && !nav.isOffRoute) nav.snappedBearing else fix.bearing
                    }
                    compassHeading != null -> compassHeading
                    fix != null && fix.hasBearing -> fix.bearing
                    else -> null
                }
            }.collect { targetHeading ->
                if (targetHeading != null) {
                    val normalized = CompassTracker.normalizeAngle(targetHeading)
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (!hasHeadingSample) {
                        hasHeadingSample = true
                        lastHeadingEmitElapsedMs = nowMs
                        _userHeadingDegrees.value = normalized
                    } else if (nowMs - lastHeadingEmitElapsedMs >= 100L) {
                        val blended = CompassTracker.lerpAngle(
                            _userHeadingDegrees.value,
                            normalized,
                            0.45f
                        )
                        val delta = kotlin.math.abs(
                            CompassTracker.shortestAngleDelta(_userHeadingDegrees.value, blended)
                        )
                        if (delta > 2.0f) {
                            lastHeadingEmitElapsedMs = nowMs
                            _userHeadingDegrees.value = blended
                        }
                    }
                }
            }
        }

        // Process live GPS updates during active navigation AND free-drive (completely ignored while simulating)
        viewModelScope.launch {
            currentGpsFix.collect { fix ->
                if (_navigationState.value.isSimulatingDrive) return@collect
                if (fix != null) {
                    compassTracker.updateLocationForDeclination(fix.lat, fix.lon)
                }
                if (fix != null && _navigationState.value.isNavigating) {
                    lastGpsReceivedMs = System.currentTimeMillis()
                    handleNavigationGpsFix(fix)
                } else if (fix != null) {
                    handleFreeDriveGpsFix(fix)
                }
            }
        }
    }

    private fun refreshNearbyCameras(lat: Double, lon: Double, force: Boolean = false) {
        val last = lastCameraFetchLoc
        if (!force && last != null &&
            OfflinePlaceSearchEngine.haversineMeters(last.lat, last.lon, lat, lon) < 2_000.0
        ) {
            return
        }
        lastCameraFetchLoc = LatLon(lat, lon)
        cameraFetchJob?.cancel()
        cameraFetchJob = viewModelScope.launch {
            val nearby = offlineSearch.queryCamerasInBounds(
                minLat = lat - 0.12,
                maxLat = lat + 0.12,
                minLon = lon - 0.14,
                maxLon = lon + 0.14
            )
            val routeCams = if (_navigationState.value.isNavigating) {
                cachedDbHazards.filter {
                    it.type.equals("speed_camera", ignoreCase = true) ||
                        it.type.equals("red_light_camera", ignoreCase = true)
                }
            } else {
                emptyList()
            }
            _visibleCameras.value = mergeUniqueCameras(nearby, routeCams)
        }
    }

    private fun mergeUniqueCameras(
        primary: List<RoadHazardPoint>,
        secondary: List<RoadHazardPoint>
    ): List<RoadHazardPoint> {
        if (secondary.isEmpty()) return primary
        if (primary.isEmpty()) return secondary
        val seen = HashSet<String>(primary.size + secondary.size)
        val merged = ArrayList<RoadHazardPoint>(primary.size + secondary.size)
        for (cam in primary) {
            val key = cam.osmId?.takeIf { it.isNotBlank() } ?: "${cam.type}_${cam.lat}_${cam.lon}"
            if (seen.add(key)) merged.add(cam)
        }
        for (cam in secondary) {
            val key = cam.osmId?.takeIf { it.isNotBlank() } ?: "${cam.type}_${cam.lat}_${cam.lon}"
            if (seen.add(key)) merged.add(cam)
        }
        return merged
    }

    private fun handleFreeDriveGpsFix(fix: GpsFix) {
        val s = settings.value
        refreshNearbyCameras(fix.lat, fix.lon)

        // Evaluate speed cameras & red light cameras ahead during free driving
        val (freeDriveAlert, alertToSpeak) = navController.evaluateFreeDriveCameraAlert(
            fix = fix,
            cameras = _visibleCameras.value,
            settings = s
        )
        _freeDriveAheadAlert.value = freeDriveAlert
        if (alertToSpeak != null) {
            voiceManager.playCameraAlertSound()
            voiceManager.speakAlert(alertToSpeak, s.language, s.distanceUnit)
        }

        val isPlaceOrPreviewOpen = _selectedPlace.value != null || _candidateRoutes.value.isNotEmpty()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        var forceImmediate = false
        if (cameraFollowController.shouldAutoRecenter(
                isCameraFollowing = _isCameraFollowing.value,
                isPlaceOrPreviewOpen = isPlaceOrPreviewOpen,
                speedMps = fix.speedMps,
                nowMs = nowElapsed
            )
        ) {
            _isCameraFollowing.value = true
            forceImmediate = true
        }
        if (!_isCameraFollowing.value || isPlaceOrPreviewOpen) return

        val heading = if (fix.speedMps > LocationTracker.MIN_BEARING_SPEED_MPS && fix.hasBearing) {
            fix.bearing
        } else {
            _userHeadingDegrees.value
        }
        val update = cameraFollowController.computeFollowCameraUpdate(
            lat = fix.lat,
            lon = fix.lon,
            speedMps = fix.speedMps,
            headingDegrees = heading,
            isHeadingUpMode = _isHeadingUpMode.value,
            is3DView = s.is3DView,
            isNavigating = false,
            simulationSpeedMultiplier = 1,
            forceImmediate = forceImmediate
        ) ?: return

        _cameraCommand.value = CameraTargetCommand(
            lat = update.lat,
            lon = update.lon,
            zoom = update.zoom,
            tilt = update.tilt,
            bearing = update.bearing,
            animationDurationMs = update.animationDurationMs,
            anchorLowerThird = update.anchorLowerThird
        )
    }

    fun startLocationTracking() {
        locationTracker.startTracking()
        compassTracker.startIfActive()
    }

    fun setAppInForeground(inForeground: Boolean) {
        locationTracker.setAppInForeground(inForeground)
        compassTracker.setAppInForeground(inForeground)
    }

    fun refreshLocationTracking() {
        locationTracker.refreshTrackingProfile()
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
        autoRecenterJob?.cancel()
        _isCameraFollowing.value = false
        _cameraCommand.value = CameraTargetCommand(lat = lat, lon = lon, zoom = 15.0, anchorLowerThird = false)
    }

    fun selectPlace(place: PlaceSearchResult) {
        _selectedPlace.value = place
        _candidateRoutes.value = emptyList()
        _routeError.value = null
        _missingStatesForRoute.value = emptyList()
        autoRecenterJob?.cancel()
        _isCameraFollowing.value = false
        _cameraCommand.value = CameraTargetCommand(
            lat = place.lat,
            lon = place.lon,
            zoom = 15.0,
            anchorLowerThird = false
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
        if (!_navigationState.value.isNavigating) {
            recenterCamera()
        }
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
            preferShortest = (s.routePreference == RoutePreference.SHORTEST),
            avoidLocations = closedLocations
        )
    }

    // --- ROUTING & TURN-BY-TURN NAVIGATION ---
    fun calculateRoutesToSelectedPlace() {
        val dest = _selectedPlace.value ?: return
        val origin = getEffectiveReferenceLocation()
        val s = settings.value
        val options = buildCurrentRouteOptions(s)
        // Only send GPS bearing when moving > 3 mph with a fresh (< 10 s) bearing; never send compass heading
        val routingHeading = LocationTracker.resolveRoutingHeadingDegrees(currentGpsFix.value)

        viewModelScope.launch {
            _isCalculatingRoute.value = true
            _routeError.value = null
            _missingStatesForRoute.value = emptyList()
            val result = routingEngine.calculateRoutes(
                origin = origin,
                destination = LatLon(dest.lat, dest.lon),
                options = options,
                headingDegrees = routingHeading,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )
            _isCalculatingRoute.value = false
            result.onSuccess { routes ->
                _candidateRoutes.value = routes
                _selectedRouteIndex.value = 0
                autoRecenterJob?.cancel()
                _isCameraFollowing.value = false
                routes.firstOrNull()?.shape?.let { pts ->
                    if (pts.isNotEmpty()) {
                        val mid = pts[pts.size / 2]
                        _cameraCommand.value = CameraTargetCommand(mid.lat, mid.lon, zoom = 12.0, anchorLowerThird = false)
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
        val routes = _candidateRoutes.value
        if (routes.isEmpty()) return
        val safeIndex = index.coerceIn(0, routes.lastIndex)
        if (_selectedRouteIndex.value != safeIndex) {
            _selectedRouteIndex.value = safeIndex
            // If actively navigating and switching to an alternative route, keep the same
            // running foreground service and only refresh the route & notification.
            if (_navigationState.value.isNavigating) {
                switchActiveNavigationRoute(routes[safeIndex])
            }
        }
    }

    private fun switchActiveNavigationRoute(newRoute: OfflineRoute) {
        val dest = _selectedPlace.value ?: return
        val s = settings.value
        navController.reset()
        voiceManager.resetDeduplication()
        val initialManeuver = newRoute.maneuvers.firstOrNull()
        val startLoc = _navigationState.value.snappedLocation
            ?: newRoute.shape.firstOrNull()
            ?: getEffectiveReferenceLocation()

        _navigationState.value = _navigationState.value.copy(
            route = newRoute,
            currentManeuverIndex = 0,
            snappedShapeIndex = 0,
            isOffRoute = false,
            isRerouting = false,
            distanceToNextManeuverMeters = initialManeuver?.lengthMeters ?: newRoute.distanceMeters,
            timeToNextManeuverSeconds = initialManeuver?.timeSeconds ?: newRoute.durationSeconds,
            remainingDistanceMeters = newRoute.distanceMeters,
            remainingTimeSeconds = newRoute.durationSeconds,
            snappedLocation = startLoc,
            currentSpeedLimitMph = initialManeuver?.speedLimitMph,
            showLaneStrip = initialManeuver?.lanes?.isNotEmpty() == true &&
                (initialManeuver.lengthMeters <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit)),
            lanePulseManeuverIndex = -1,
            dismissedJunctionManeuverIndex = -1
        )

        val firstInstruction = initialManeuver?.streetNames?.firstOrNull()?.ifBlank { initialManeuver.instructionEn }
            ?: initialManeuver?.instructionEn
            ?: dest.name
        val etaText = BilingualFormatter.formatDuration(newRoute.durationSeconds, s.language)
        NavigationForegroundService.updateNotification(
            context = app,
            title = "${dest.name} • $etaText",
            body = firstInstruction,
            force = true
        )
    }

    fun startNavigation(forceSimulate: Boolean? = null) {
        val routes = _candidateRoutes.value
        if (routes.isEmpty()) return
        val safeIdx = _selectedRouteIndex.value.coerceIn(0, routes.lastIndex)
        val route = routes[safeIdx]
        val dest = _selectedPlace.value ?: return
        val s = settings.value
        val shouldSimulate = forceSimulate ?: s.simulateDriveEnabled
        val wasAlreadyNavigating = _navigationState.value.isNavigating

        stopInternalNavJobs()
        navController.reset()
        voiceManager.resetDeduplication()
        locationTracker.setNavigationMode(true)
        compassTracker.setNavigationMode(true)
        _isCameraFollowing.value = true
        lastGpsReceivedMs = System.currentTimeMillis()
        simulatedWrongTurnTicksRemaining = 0
        if (!shouldSimulate) {
            _simulationSpeedMultiplier.value = 1
        }

        val initialManeuver = route.maneuvers.firstOrNull()
        val initialDist = initialManeuver?.lengthMeters ?: route.distanceMeters
        val startLoc = route.shape.firstOrNull() ?: getEffectiveReferenceLocation()
        val initialBearing = if (route.shape.size >= 2) {
            com.example.navigation.ValhallaResponseParser.bearingDegrees(route.shape[0], route.shape[1]).toFloat()
        } else {
            _userHeadingDegrees.value
        }
        _navigationState.value = ActiveNavigationState(
            isNavigating = true,
            route = route,
            destinationName = dest.name,
            destinationAddress = dest.formattedAddress,
            currentManeuverIndex = 0,
            distanceToNextManeuverMeters = initialDist,
            timeToNextManeuverSeconds = initialManeuver?.timeSeconds ?: route.durationSeconds,
            remainingDistanceMeters = route.distanceMeters,
            remainingTimeSeconds = route.durationSeconds,
            snappedLocation = startLoc,
            snappedBearing = initialBearing,
            currentSpeedLimitMph = route.speedLimitsByShapeIndex[0] ?: initialManeuver?.speedLimitMph,
            isSimulatingDrive = shouldSimulate,
            showLaneStrip = initialManeuver?.lanes?.isNotEmpty() == true &&
                initialDist <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit)
        )

        _freeDriveAheadAlert.value = null

        // Pre-fetch road hazards (cameras, railroad crossings, school zones, stop signs, traffic signals) along route
        viewModelScope.launch {
            if (route.shape.isNotEmpty()) {
                val minLat = route.shape.minOf { it.lat } - 0.05
                val maxLat = route.shape.maxOf { it.lat } + 0.05
                val minLon = route.shape.minOf { it.lon } - 0.05
                val maxLon = route.shape.maxOf { it.lon } + 0.05
                val hazards = offlineSearch.queryRoadHazardsInBounds(minLat, maxLat, minLon, maxLon)
                cachedDbHazards = hazards
                val routeCams = hazards.filter {
                    it.type.equals("speed_camera", ignoreCase = true) ||
                        it.type.equals("red_light_camera", ignoreCase = true)
                }
                if (routeCams.isNotEmpty()) {
                    _visibleCameras.value = mergeUniqueCameras(_visibleCameras.value, routeCams)
                }
            }
        }

        // Start foreground service ONLY once when navigation begins; if already running, only update notification
        val firstInstruction = initialManeuver?.instructionEn ?: dest.name
        val etaText = BilingualFormatter.formatDuration(route.durationSeconds, s.language)
        if (wasAlreadyNavigating) {
            NavigationForegroundService.updateNotification(
                context = app,
                title = "${dest.name} • $etaText",
                body = firstInstruction,
                force = true
            )
        } else {
            NavigationForegroundService.startOrUpdate(
                context = app,
                title = "${dest.name} • $etaText",
                body = firstInstruction
            )
        }

        // Announce clear departure instruction with compass direction, starting street, and next turn
        val spoken = voiceManager.speakStartInstruction(route, s.language)
        if (spoken.isNotBlank()) {
            navController.markExternalPromptSpoken()
            _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
        }

        // Switch camera to driving view
        _cameraCommand.value = CameraTargetCommand(
            lat = startLoc.lat,
            lon = startLoc.lon,
            zoom = 16.8,
            tilt = if (s.is3DView) 55.0 else 0.0,
            bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0
        )

        if (shouldSimulate) {
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
                                bearing = if (_isHeadingUpMode.value) deadReckoned.snappedBearing.toDouble() else 0.0
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startSimulatedDriveLoop() {
        simulationJob?.cancel()
        simulatedClockMs = System.currentTimeMillis()
        simulationJob = viewModelScope.launch {
            while (_navigationState.value.isNavigating &&
                _navigationState.value.isSimulatingDrive &&
                !_navigationState.value.hasArrived
            ) {
                val multiplier = _simulationSpeedMultiplier.value.coerceIn(1, 32)
                // Adapt map frame rate at high speeds so MapLibre never freezes
                val wallTickMs = when {
                    multiplier >= 32 -> 350L
                    multiplier >= 8 -> 300L
                    else -> 250L
                }
                delay(wallTickMs)
                if (!_navigationState.value.isNavigating || !_navigationState.value.isSimulatingDrive) break

                val wrongTurnOffset = if (simulatedWrongTurnTicksRemaining > 0) {
                    simulatedWrongTurnTicksRemaining--
                    120.0
                } else {
                    0.0
                }
                val currentState = _navigationState.value
                val s = settings.value
                val reports = activeUserReports.value
                val hazards = cachedDbHazards
                val totalSimSec = (wallTickMs / 1000.0) * multiplier

                val batch = withContext(Dispatchers.Default) {
                    navController.advanceSimulationStep(
                        currentState = currentState,
                        totalSimulatedSeconds = totalSimSec,
                        startingSimClockMs = simulatedClockMs,
                        wrongTurnOffsetMeters = wrongTurnOffset,
                        dbHazards = hazards,
                        userReports = reports,
                        unit = s.distanceUnit,
                        language = s.language,
                        speedAudioEnabled = s.speedWarningAudioEnabled,
                        settings = s
                    )
                }
                simulatedClockMs = batch.finalSimClockMs
                applyNavigationStepResult(
                    state = batch.state,
                    shouldReroute = batch.shouldReroute,
                    maneuverToSpeak = batch.latestManeuverToSpeak,
                    alertToSpeak = batch.latestAlertToSpeak,
                    triggerOverspeedChime = batch.triggerOverspeedChime,
                    fallbackLoc = batch.state.snappedLocation,
                    speedMps = batch.state.currentSpeedMps,
                    cameraAnimMs = when {
                        multiplier >= 16 -> 0
                        multiplier >= 8 -> 140
                        else -> 220
                    }
                )
            }
        }
    }

    /**
     * One-tap Simulate button on the main map screen:
     * - If simulating -> turns simulation off, resets speed to 1x, returns immediately to real GPS,
     *   and recalculates the active route from real GPS.
     * - If not simulating and an active route or selected preview route exists -> starts simulating
     *   from the start of that route and syncs the Settings switch.
     * - If no route exists -> shows "Choose a destination first to start simulation" in the active language.
     */
    fun toggleSimulationFromMap() {
        val isCurrentlySimulating = _navigationState.value.isSimulatingDrive || settings.value.simulateDriveEnabled
        if (isCurrentlySimulating) {
            stopSimulationAndReturnToRealGps()
            return
        }

        val hasActiveRoute = _navigationState.value.isNavigating && _navigationState.value.route != null
        val hasPreviewRoute = _candidateRoutes.value.isNotEmpty() && _selectedPlace.value != null
        if (!hasActiveRoute && !hasPreviewRoute) {
            _snackbarMessage.value = stringsFor(settings.value.language).simulationNoRouteMessage
            return
        }

        _simulationSpeedMultiplier.value = 1
        viewModelScope.launch { prefs.setSimulateDriveEnabled(true) }
        if (hasActiveRoute) {
            startSimulationOnActiveRoute()
        } else {
            startNavigation(forceSimulate = true)
        }
    }

    private fun startSimulationOnActiveRoute() {
        val currentNav = _navigationState.value
        val route = currentNav.route ?: return
        val s = settings.value
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = null
        navController.reset()
        voiceManager.resetDeduplication()
        simulatedWrongTurnTicksRemaining = 0
        val startLoc = route.shape.firstOrNull() ?: getEffectiveReferenceLocation()
        val initialBearing = if (route.shape.size >= 2) {
            com.example.navigation.ValhallaResponseParser.bearingDegrees(route.shape[0], route.shape[1]).toFloat()
        } else {
            currentNav.snappedBearing
        }
        val firstManeuver = route.maneuvers.firstOrNull()
        val initialDist = firstManeuver?.lengthMeters ?: route.distanceMeters
        _navigationState.value = currentNav.copy(
            isSimulatingDrive = true,
            currentManeuverIndex = 0,
            snappedShapeIndex = 0,
            snappedLocation = startLoc,
            snappedBearing = initialBearing,
            distanceToNextManeuverMeters = initialDist,
            timeToNextManeuverSeconds = firstManeuver?.timeSeconds ?: route.durationSeconds,
            remainingDistanceMeters = route.distanceMeters,
            remainingTimeSeconds = route.durationSeconds,
            currentSpeedLimitMph = route.speedLimitsByShapeIndex[0] ?: firstManeuver?.speedLimitMph,
            isOffRoute = false,
            isRerouting = false,
            isGpsSignalLost = false,
            hasArrived = false,
            showLaneStrip = firstManeuver?.lanes?.isNotEmpty() == true &&
                initialDist <= NavigationSessionController.laneStripWindowMeters(s.distanceUnit)
        )
        _isCameraFollowing.value = true
        _cameraCommand.value = CameraTargetCommand(
            lat = startLoc.lat,
            lon = startLoc.lon,
            zoom = 16.8,
            tilt = if (s.is3DView) 55.0 else 0.0,
            bearing = if (_isHeadingUpMode.value) initialBearing.toDouble() else 0.0,
            animationDurationMs = 300
        )
        startSimulatedDriveLoop()
    }

    fun stopSimulationAndReturnToRealGps() {
        simulationJob?.cancel()
        simulationJob = null
        _simulationSpeedMultiplier.value = 1
        simulatedWrongTurnTicksRemaining = 0
        viewModelScope.launch { prefs.setSimulateDriveEnabled(false) }

        val realFix = currentGpsFix.value
        val s = settings.value
        val wasNavigating = _navigationState.value.isNavigating
        if (wasNavigating) {
            val realLoc = realFix?.let { LatLon(it.lat, it.lon) } ?: getEffectiveReferenceLocation()
            _navigationState.value = _navigationState.value.copy(
                isSimulatingDrive = false,
                snappedLocation = realLoc,
                currentSpeedMps = realFix?.speedMps ?: 0f,
                isGpsSignalLost = false
            )
            lastGpsReceivedMs = System.currentTimeMillis()
            startGpsLossWatchdog()
            _isCameraFollowing.value = true
            _cameraCommand.value = CameraTargetCommand(
                lat = realLoc.lat,
                lon = realLoc.lon,
                zoom = 16.5,
                tilt = if (s.is3DView) 55.0 else 0.0,
                bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0,
                animationDurationMs = 400
            )
            // Immediately recalculate active route from the user's real GPS position
            val rerouteFix = realFix ?: GpsFix(
                lat = realLoc.lat,
                lon = realLoc.lon,
                bearing = 0f,
                speedMps = 0f,
                accuracyMeters = 10f,
                timestampMs = System.currentTimeMillis()
            )
            triggerAutomaticReroute(rerouteFix)
        } else {
            val realLoc = realFix?.let { LatLon(it.lat, it.lon) } ?: getEffectiveReferenceLocation()
            _cameraCommand.value = CameraTargetCommand(
                lat = realLoc.lat,
                lon = realLoc.lon,
                zoom = 14.5,
                tilt = 0.0,
                bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0,
                animationDurationMs = 400
            )
        }
    }

    fun cycleSimulationSpeed() {
        val currentIdx = SIMULATION_SPEED_STEPS.indexOf(_simulationSpeedMultiplier.value)
        val nextSpeed = SIMULATION_SPEED_STEPS[(currentIdx + 1) % SIMULATION_SPEED_STEPS.size]
        _simulationSpeedMultiplier.value = nextSpeed
        val s = settings.value
        val str = stringsFor(s.language)
        val speedLabel = if (s.language == AppLanguage.PERSIAN) {
            "${BilingualFormatter.formatNumber(nextSpeed, AppLanguage.PERSIAN)}x"
        } else {
            "${nextSpeed}x"
        }
        _snackbarMessage.value = "${str.simulationSpeedChangedPrefix}: $speedLabel"
    }

    fun resetSimulationSpeedTo1x() {
        _simulationSpeedMultiplier.value = 1
        val s = settings.value
        val str = stringsFor(s.language)
        val speedLabel = if (s.language == AppLanguage.PERSIAN) "۱x" else "1x"
        _snackbarMessage.value = "${str.simulationSpeedChangedPrefix}: $speedLabel"
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
        val wasSimulating = _navigationState.value.isSimulatingDrive
        stopInternalNavJobs()
        _simulationSpeedMultiplier.value = 1
        if (wasSimulating) {
            viewModelScope.launch { prefs.setSimulateDriveEnabled(false) }
        }
        locationTracker.setNavigationMode(false)
        compassTracker.setNavigationMode(false)
        _navigationState.value = ActiveNavigationState()
        navController.reset()
        NavigationForegroundService.stop(app)
        _isCameraFollowing.value = true
        val ref = getEffectiveReferenceLocation()
        _cameraCommand.value = CameraTargetCommand(
            lat = ref.lat,
            lon = ref.lon,
            zoom = 14.0,
            tilt = 0.0,
            bearing = if (_isHeadingUpMode.value) _userHeadingDegrees.value.toDouble() else 0.0
        )
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
        applyNavigationStepResult(
            state = result.state,
            shouldReroute = result.shouldReroute,
            maneuverToSpeak = result.maneuverToSpeak,
            alertToSpeak = result.alertToSpeak,
            triggerOverspeedChime = result.triggerOverspeedChime,
            fallbackLoc = LatLon(fix.lat, fix.lon),
            speedMps = fix.speedMps,
            cameraAnimMs = 450,
            rerouteSourceFix = fix
        )
    }

    private fun applyNavigationStepResult(
        state: ActiveNavigationState,
        shouldReroute: Boolean,
        maneuverToSpeak: Pair<com.example.navigation.RouteManeuver, String>?,
        alertToSpeak: com.example.navigation.AheadAlert?,
        triggerOverspeedChime: Boolean,
        fallbackLoc: LatLon?,
        speedMps: Float,
        cameraAnimMs: Int,
        rerouteSourceFix: GpsFix? = null
    ) {
        val s = settings.value
        val wasArrived = _navigationState.value.hasArrived
        _navigationState.value = state

        if (triggerOverspeedChime) {
            voiceManager.playOverspeedChime()
        }

        if (state.hasArrived && !wasArrived) {
            val arrivalManeuverType = state.route?.maneuvers?.lastOrNull()?.type
                ?: com.example.navigation.ManeuverType.ARRIVE
            val spoken = voiceManager.speakArrival(arrivalManeuverType, state.destinationName, s.language)
            _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
            arrivalEndJob?.cancel()
            arrivalEndJob = viewModelScope.launch {
                delay(6000L)
                if (_navigationState.value.hasArrived) {
                    stopNavigation()
                }
            }
        } else {
            maneuverToSpeak?.let { (maneuver, stage) ->
                val spoken = voiceManager.speakManeuver(
                    maneuver = maneuver,
                    distanceMeters = state.distanceToNextManeuverMeters,
                    unit = s.distanceUnit,
                    language = s.language,
                    announcementStage = stage,
                    includeLaneGuidance = maneuver.lanes.isNotEmpty()
                )
                _navigationState.value = _navigationState.value.copy(lastSpokenInstruction = spoken)
            }
            alertToSpeak?.let { alert ->
                if (alert.kind == RoadAlertKind.SPEED_CAMERA || alert.kind == RoadAlertKind.RED_LIGHT_CAMERA) {
                    voiceManager.playCameraAlertSound()
                }
                voiceManager.speakAlert(alert, s.language, s.distanceUnit)
            }
        }

        // Update persistent foreground notification directly via NotificationManagerCompat.notify()
        val currentManeuver = state.currentManeuver
        if (currentManeuver != null && state.isNavigating) {
            val distStr = BilingualFormatter.formatDistance(
                state.distanceToNextManeuverMeters,
                s.distanceUnit,
                s.language
            )
            val etaStr = BilingualFormatter.formatDuration(state.remainingTimeSeconds, s.language)
            NavigationForegroundService.updateNotification(
                context = app,
                title = "$distStr • $etaStr",
                body = currentManeuver.streetNames.firstOrNull()?.ifBlank { currentManeuver.instructionEn }
                    ?: currentManeuver.instructionEn
            )
        }

        // Follow driving camera unless user manually panned/zoomed away (or auto-recenter triggers after 10s > 10 mph)
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        var forceRecenterNow = false
        if (cameraFollowController.shouldAutoRecenter(
                isCameraFollowing = _isCameraFollowing.value,
                isPlaceOrPreviewOpen = false,
                speedMps = speedMps,
                nowMs = nowElapsed
            )
        ) {
            _isCameraFollowing.value = true
            forceRecenterNow = true
        }
        if (_isCameraFollowing.value) {
            val targetLoc = state.snappedLocation ?: fallbackLoc
            if (targetLoc != null) {
                val heading = if (speedMps > LocationTracker.MIN_BEARING_SPEED_MPS || state.isSimulatingDrive) {
                    state.snappedBearing
                } else {
                    _userHeadingDegrees.value
                }
                val simMultiplier = if (state.isSimulatingDrive) _simulationSpeedMultiplier.value else 1
                val update = cameraFollowController.computeFollowCameraUpdate(
                    lat = targetLoc.lat,
                    lon = targetLoc.lon,
                    speedMps = speedMps,
                    headingDegrees = heading,
                    isHeadingUpMode = _isHeadingUpMode.value,
                    is3DView = s.is3DView,
                    isNavigating = true,
                    simulationSpeedMultiplier = simMultiplier,
                    forceImmediate = forceRecenterNow
                )
                if (update != null) {
                    _cameraCommand.value = CameraTargetCommand(
                        lat = update.lat,
                        lon = update.lon,
                        zoom = update.zoom,
                        tilt = update.tilt,
                        bearing = update.bearing,
                        animationDurationMs = if (state.isSimulatingDrive) cameraAnimMs else update.animationDurationMs,
                        anchorLowerThird = update.anchorLowerThird
                    )
                }
            }
        }

        if (shouldReroute) {
            val fixForReroute = rerouteSourceFix ?: state.snappedLocation?.let { loc ->
                GpsFix(
                    lat = loc.lat,
                    lon = loc.lon,
                    bearing = state.snappedBearing,
                    speedMps = speedMps,
                    accuracyMeters = 5f,
                    timestampMs = System.currentTimeMillis()
                )
            }
            if (fixForReroute != null) {
                triggerAutomaticReroute(fixForReroute)
            }
        }
    }

    private fun triggerAutomaticReroute(fix: GpsFix) {
        val dest = _selectedPlace.value ?: return
        val s = settings.value
        val routingHeading = LocationTracker.resolveRoutingHeadingDegrees(fix)
        voiceManager.speakRerouting(s.language)
        viewModelScope.launch {
            val res = routingEngine.calculateRoutes(
                origin = LatLon(fix.lat, fix.lon),
                destination = LatLon(dest.lat, dest.lon),
                options = buildCurrentRouteOptions(s),
                headingDegrees = routingHeading,
                allowDevOnlineFallback = s.devOnlineOregonMode
            )
            res.onSuccess { routes ->
                val newRoute = routes.firstOrNull() ?: return@onSuccess
                navController.reset()
                voiceManager.resetDeduplication()
                val spoken = voiceManager.speakStartInstruction(newRoute, s.language)
                if (spoken.isNotBlank()) {
                    navController.markExternalPromptSpoken()
                }
                _navigationState.value = _navigationState.value.copy(
                    route = newRoute,
                    currentManeuverIndex = 0,
                    snappedShapeIndex = 0,
                    isOffRoute = false,
                    isRerouting = false,
                    remainingDistanceMeters = newRoute.distanceMeters,
                    remainingTimeSeconds = newRoute.durationSeconds,
                    dismissedJunctionManeuverIndex = -1,
                    lastSpokenInstruction = spoken.ifBlank { _navigationState.value.lastSpokenInstruction }
                )
                // Keep the same running foreground service alive during reroute — only update notification
                val firstManeuver = newRoute.maneuvers.firstOrNull()
                val etaStr = BilingualFormatter.formatDuration(newRoute.durationSeconds, s.language)
                NavigationForegroundService.updateNotification(
                    context = app,
                    title = "${dest.name} • $etaStr",
                    body = firstManeuver?.streetNames?.firstOrNull()?.ifBlank { firstManeuver.instructionEn }
                        ?: firstManeuver?.instructionEn
                        ?: dest.name,
                    force = true
                )
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
                        prefs.setAlertSpeedCameraEnabled(s.alertSpeedCameraEnabled)
                        prefs.setAlertRedLightCameraEnabled(s.alertRedLightCameraEnabled)
                        prefs.set3DView(s.is3DView)
                        prefs.setAvoidTolls(s.avoidTolls)
                        prefs.setAvoidHighways(s.avoidHighways)
                        prefs.setAvoidFerries(s.avoidFerries)
                        prefs.setRoutePreference(s.routePreference)
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
        _isCameraFollowing.value = false
        val touchMs = android.os.SystemClock.elapsedRealtime()
        cameraFollowController.recordUserMapGesture(touchMs)
        scheduleAutoRecenterCheck()
    }

    private fun scheduleAutoRecenterCheck() {
        autoRecenterJob?.cancel()
        autoRecenterJob = viewModelScope.launch {
            delay(CameraFollowController.AUTO_RECENTER_DELAY_MS)
            val nav = _navigationState.value
            val isPlaceOrPreviewOpen = !nav.isNavigating &&
                (_selectedPlace.value != null || _candidateRoutes.value.isNotEmpty())
            val speedMps = if (nav.isNavigating) nav.currentSpeedMps else (currentGpsFix.value?.speedMps ?: 0f)
            if (cameraFollowController.shouldAutoRecenter(
                    isCameraFollowing = _isCameraFollowing.value,
                    isPlaceOrPreviewOpen = isPlaceOrPreviewOpen,
                    speedMps = speedMps,
                    nowMs = android.os.SystemClock.elapsedRealtime()
                )
            ) {
                recenterCamera()
            }
        }
    }

    fun toggle3DView() {
        val next = !settings.value.is3DView
        viewModelScope.launch { prefs.set3DView(next) }
        emitImmediateFollowCamera(is3DViewOverride = next)
    }

    fun toggleCompassHeadingMode() {
        val nextHeadingUp = !_isHeadingUpMode.value
        _isHeadingUpMode.value = nextHeadingUp
        if (nextHeadingUp) {
            autoRecenterJob?.cancel()
            _isCameraFollowing.value = true
        }
        emitImmediateFollowCamera()
    }

    fun recenterCamera() {
        autoRecenterJob?.cancel()
        _isCameraFollowing.value = true
        emitImmediateFollowCamera()
    }

    private fun emitImmediateFollowCamera(is3DViewOverride: Boolean? = null) {
        val nav = _navigationState.value
        val fix = currentGpsFix.value
        val ref = nav.snappedLocation ?: getEffectiveReferenceLocation()
        val s = settings.value
        val speedMps = if (nav.isNavigating) nav.currentSpeedMps else (fix?.speedMps ?: 0f)
        val heading = if (nav.isNavigating && (nav.isSimulatingDrive || speedMps > LocationTracker.MIN_BEARING_SPEED_MPS)) {
            nav.snappedBearing
        } else if (!nav.isNavigating && fix != null && fix.speedMps > LocationTracker.MIN_BEARING_SPEED_MPS && fix.hasBearing) {
            fix.bearing
        } else {
            _userHeadingDegrees.value
        }
        val update = cameraFollowController.computeFollowCameraUpdate(
            lat = ref.lat,
            lon = ref.lon,
            speedMps = speedMps,
            headingDegrees = heading,
            isHeadingUpMode = _isHeadingUpMode.value,
            is3DView = is3DViewOverride ?: s.is3DView,
            isNavigating = nav.isNavigating,
            simulationSpeedMultiplier = if (nav.isSimulatingDrive) _simulationSpeedMultiplier.value else 1,
            forceImmediate = true
        ) ?: return
        _cameraCommand.value = CameraTargetCommand(
            lat = update.lat,
            lon = update.lon,
            zoom = update.zoom,
            tilt = update.tilt,
            bearing = update.bearing,
            animationDurationMs = update.animationDurationMs,
            anchorLowerThird = update.anchorLowerThird
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

    fun setAlertSpeedCameraEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertSpeedCameraEnabled(enabled) }
    }

    fun setAlertRedLightCameraEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAlertRedLightCameraEnabled(enabled) }
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

    fun setRoutePreference(preference: RoutePreference) {
        viewModelScope.launch {
            prefs.setRoutePreference(preference)
            if (_candidateRoutes.value.isNotEmpty()) calculateRoutesToSelectedPlace()
        }
    }

    fun saveGitHubRepository(owner: String, repo: String) {
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
        if (!enabled) {
            stopSimulationAndReturnToRealGps()
            return
        }
        viewModelScope.launch { prefs.setSimulateDriveEnabled(true) }
        if (_navigationState.value.isNavigating && !_navigationState.value.isSimulatingDrive) {
            startSimulationOnActiveRoute()
        } else if (!_navigationState.value.isNavigating && _candidateRoutes.value.isNotEmpty() && _selectedPlace.value != null) {
            startNavigation(forceSimulate = true)
        }
    }

    fun setUseEnglishVoiceInPersianMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setUseEnglishVoiceInPersianMode(enabled) }
    }

    fun testVoiceGuidance() {
        voiceManager.checkPersianAvailability()
        voiceManager.speakTestSample(settings.value.language)
    }

    fun refreshTtsDiagnostics() {
        voiceManager.refreshTtsDiagnostics()
    }

    fun testPersianVoice() {
        val ok = voiceManager.speakExplicitLanguageTest(AppLanguage.PERSIAN)
        if (!ok) {
            _snackbarMessage.value = stringsFor(settings.value.language).diagPersianVoiceUnavailableToast
        }
    }

    fun testEnglishVoice() {
        voiceManager.speakExplicitLanguageTest(AppLanguage.ENGLISH)
    }

    fun getPackFileSizes(stateCode: String) = packManager.getPackFileSizes(stateCode)

    fun readLastErrorLogLines(maxLines: Int = 20): List<String> =
        CrashLogManager.readLastErrorLines(app, maxLines)

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    companion object {
        const val EUGENE_OR_LAT = 44.0521
        const val EUGENE_OR_LON = -123.0868
        val SIMULATION_SPEED_STEPS = listOf(1, 2, 4, 8, 16, 32)
    }
}
