package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RahYabApplication
import com.example.data.local.PackInstallStatus
import com.example.data.local.ReportType
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.local.UserReportEntity
import com.example.data.model.UsStateInfo
import com.example.data.model.UsStatesCatalog
import com.example.data.online.NetworkState
import com.example.data.online.OnlineSourceState
import com.example.data.online.OnlineSourcesSummary
import com.example.data.online.PhotonSearchClient
import com.example.data.online.WeatherAlertItem
import com.example.data.online.WeatherAlertsClient
import com.example.data.packs.StatePackUiItem
import com.example.data.preferences.UserSettings
import com.example.data.search.PlaceSearchResult
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.MapThemeMode
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.CameraFollowController
import com.example.navigation.GpsFix
import com.example.navigation.LastRouteDiagnostics
import com.example.navigation.LatLon
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RoutePreference
import com.example.navigation.VoiceGuidanceManager
import com.example.util.CrashLogManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RahYabViewModel(application: Application) : AndroidViewModel(application) {

    internal val app = application as RahYabApplication
    internal val prefs = app.preferencesRepository
    internal val packManager = app.packManager
    internal val offlineSearch = app.offlineSearchEngine
    internal val devOnlineSearch = app.onlineOregonDevSearchClient
    internal val routingEngine = app.routingEngine
    internal val locationTracker = app.locationTracker
    internal val compassTracker = app.compassTracker
    internal val voiceManager = app.voiceGuidanceManager
    internal val networkStatusMonitor = app.networkStatusMonitor
    internal val weatherAlertsClient = WeatherAlertsClient(app)
    internal val photonSearchClient = PhotonSearchClient(app)
    internal val savedPlaceDao = app.database.savedPlaceDao()
    internal val userReportDao = app.database.userReportDao()
    internal val installedPackDao = app.database.installedPackDao()
    internal val navController = NavigationSessionController()
    internal val cameraFollowController = CameraFollowController()

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
    val networkState: StateFlow<NetworkState> = networkStatusMonitor.networkState

    internal val _rawOnlineSources = MutableStateFlow(OnlineSourcesSummary())
    val onlineSourcesSummary: StateFlow<OnlineSourcesSummary> = buildOnlineSourcesSummaryFlow()

    val isFetchingManifest: StateFlow<Boolean> = packManager.isFetchingManifest
    val manifestError: StateFlow<String?> = packManager.manifestError
    val packsChangedTick: StateFlow<Long> = packManager.packsChangedTick
    val indexingStatusMessage: StateFlow<String?> = packManager.indexingStatusMessage

    val currentLocationStateCode: StateFlow<String?> = currentGpsFix.map { fix ->
        UsStatesCatalog.findBestStateForLocation(fix?.lat ?: EUGENE_OR_LAT, fix?.lon ?: EUGENE_OR_LON)?.code
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "or")

    val savedPlaces: StateFlow<List<SavedPlaceEntity>> = savedPlaceDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    internal val _nowTick = MutableStateFlow(System.currentTimeMillis())
    val activeUserReports: StateFlow<List<UserReportEntity>> = combine(
        userReportDao.observeActive(0L),
        _nowTick
    ) { reports, now -> reports.filter { it.expiresAt > now } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val statePacks: StateFlow<List<StatePackUiItem>> = buildStatePacksFlow()
    val mapPackUpdatesAvailableCount: StateFlow<Int> = statePacks
        .map { packs -> packs.count { it.status == PackInstallStatus.UPDATE_AVAILABLE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    internal val _mismatchedBuildVersions = MutableStateFlow<List<String>>(emptyList())
    val mismatchedBuildVersions: StateFlow<List<String>> = _mismatchedBuildVersions.asStateFlow()

    internal val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    internal val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults.asStateFlow()
    internal val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    internal val _selectedPlace = MutableStateFlow<PlaceSearchResult?>(null)
    val selectedPlace: StateFlow<PlaceSearchResult?> = _selectedPlace.asStateFlow()

    internal val _candidateRoutes = MutableStateFlow<List<OfflineRoute>>(emptyList())
    val candidateRoutes: StateFlow<List<OfflineRoute>> = _candidateRoutes.asStateFlow()
    internal val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()
    internal val _isCalculatingRoute = MutableStateFlow(false)
    val isCalculatingRoute: StateFlow<Boolean> = _isCalculatingRoute.asStateFlow()
    internal val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()
    internal val _missingStatesForRoute = MutableStateFlow<List<UsStateInfo>>(emptyList())
    val missingStatesForRoute: StateFlow<List<UsStateInfo>> = _missingStatesForRoute.asStateFlow()

    internal val _navigationState = MutableStateFlow(ActiveNavigationState())
    val navigationState: StateFlow<ActiveNavigationState> = _navigationState.asStateFlow()

    internal val _cameraCommand = MutableStateFlow<CameraTargetCommand?>(null)
    val cameraCommand: StateFlow<CameraTargetCommand?> = _cameraCommand.asStateFlow()
    internal val _isCameraFollowing = MutableStateFlow(true)
    val isCameraFollowing: StateFlow<Boolean> = _isCameraFollowing.asStateFlow()
    internal val _isHeadingUpMode = MutableStateFlow(true)
    val isHeadingUpMode: StateFlow<Boolean> = _isHeadingUpMode.asStateFlow()
    internal val _userHeadingDegrees = MutableStateFlow(0f)
    val userHeadingDegrees: StateFlow<Float> = _userHeadingDegrees.asStateFlow()
    internal var hasHeadingSample = false
    internal var lastHeadingEmitElapsedMs = 0L

    internal val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    internal val _lowStoragePromptStateCode = MutableStateFlow<String?>(null)
    val lowStoragePromptStateCode: StateFlow<String?> = _lowStoragePromptStateCode.asStateFlow()
    internal val _showPersianTtsDialog = MutableStateFlow(false)
    val showPersianTtsDialog: StateFlow<Boolean> = _showPersianTtsDialog.asStateFlow()
    internal val _simulationSpeedMultiplier = MutableStateFlow(1)
    val simulationSpeedMultiplier: StateFlow<Int> = _simulationSpeedMultiplier.asStateFlow()

    internal val _visibleCameras = MutableStateFlow<List<RoadHazardPoint>>(emptyList())
    val visibleCameras: StateFlow<List<RoadHazardPoint>> = _visibleCameras.asStateFlow()
    internal val _visibleTrafficLights = MutableStateFlow<List<RoadHazardPoint>>(emptyList())
    val visibleTrafficLights: StateFlow<List<RoadHazardPoint>> = _visibleTrafficLights.asStateFlow()
    internal val _aheadTrafficLightMeters = MutableStateFlow<Double?>(null)
    val aheadTrafficLightMeters: StateFlow<Double?> = _aheadTrafficLightMeters.asStateFlow()
    internal val _freeDriveAheadAlert = MutableStateFlow<AheadAlert?>(null)
    val freeDriveAheadAlert: StateFlow<AheadAlert?> = _freeDriveAheadAlert.asStateFlow()

    internal val _externalLinkMatchChoices = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val externalLinkMatchChoices: StateFlow<List<PlaceSearchResult>> = _externalLinkMatchChoices.asStateFlow()
    internal val _openDriveTabEvent = MutableStateFlow(0L)
    val openDriveTabEvent: StateFlow<Long> = _openDriveTabEvent.asStateFlow()

    internal val _cachedWeatherAlerts = MutableStateFlow<List<WeatherAlertItem>>(emptyList())
    internal val _dismissedWeatherAlertIds = MutableStateFlow<Set<String>>(emptySet())
    val activeWeatherAlerts: StateFlow<List<WeatherAlertItem>> = combine(
        _cachedWeatherAlerts,
        _dismissedWeatherAlertIds
    ) { alerts, dismissed -> alerts.filterNot { it.id in dismissed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    internal val spokenWeatherAlertIds = mutableSetOf<String>()
    internal var weatherCheckJob: Job? = null
    internal var dailyManifestCheckJob: Job? = null
    internal var externalIntentJob: Job? = null
    internal var searchJob: Job? = null
    internal var photonFallbackJob: Job? = null
    internal var gpsWatchdogJob: Job? = null
    internal var simulationJob: Job? = null
    internal var arrivalEndJob: Job? = null
    internal var autoRecenterJob: Job? = null
    internal var cameraFetchJob: Job? = null
    internal var trafficLightFetchJob: Job? = null
    internal var lastCameraFetchLoc: LatLon? = null
    internal var lastTrafficLightFetchLoc: LatLon? = null
    internal var lastGpsReceivedMs: Long = 0L
    internal var simulatedWrongTurnTicksRemaining: Int = 0
    internal var simulatedClockMs: Long = 0L
    internal var cachedDbHazards: List<RoadHazardPoint> = emptyList()

    init {
        initializeViewModel()
    }

    // --- LOCATION, CAMERA & PACK INFO ---
    fun startLocationTracking() {
        locationTracker.startTracking()
        compassTracker.startIfActive()
    }

    fun setAppInForeground(inForeground: Boolean) {
        locationTracker.setAppInForeground(inForeground)
        compassTracker.setAppInForeground(inForeground)
    }

    fun refreshLocationTracking() = locationTracker.refreshTrackingProfile()
    fun getInstalledPmtilesFiles() = packManager.getInstalledPmtilesFiles()
    fun getInstalledPlacesDbCount() = packManager.getInstalledPlacesDatabases().size
    fun getStorageStats(): Pair<Long, Long> =
        packManager.getTotalMapStorageUsedBytes() to packManager.getAvailableStorageBytes()
    fun getInstalledDataBuildVersion(): String = getInstalledDataBuildVersionImpl()
    fun resolveEffectiveNightMode(s: UserSettings, fix: GpsFix?): Boolean = resolveEffectiveNightModeImpl(s, fix)
    fun getEffectiveReferenceLocation(): LatLon = getEffectiveReferenceLocationImpl()
    fun pauseCameraFollow() = pauseCameraFollowImpl()
    fun toggle3DView() = toggle3DViewImpl()
    fun toggleCompassHeadingMode() = toggleCompassHeadingModeImpl()
    fun recenterCamera() = recenterCameraImpl()

    // --- SEARCH, SAVED PLACES & EXTERNAL LINKS ---
    fun onSearchQueryChanged(query: String) = onSearchQueryChangedImpl(query)
    fun executeSearchNow(query: String) = executeSearchNowImpl(query)
    fun dropPinOnMap(lat: Double, lon: Double) = dropPinOnMapImpl(lat, lon)
    fun selectPlace(place: PlaceSearchResult) = selectPlaceImpl(place)
    fun clearSelectedPlace() = clearSelectedPlaceImpl()
    fun handleExternalMapIntent(intent: Intent?) = handleExternalMapIntentImpl(intent)
    fun selectExternalMatchChoice(place: PlaceSearchResult) = selectExternalMatchChoiceImpl(place)
    fun dismissExternalMatchChoices() = dismissExternalMatchChoicesImpl()
    fun savePlaceAs(place: PlaceSearchResult, type: SavedPlaceType) = savePlaceAsImpl(place, type)
    fun deleteSavedPlace(id: Long) = deleteSavedPlaceImpl(id)

    // --- ROUTING, NAVIGATION & SIMULATION ---
    fun calculateRoutesToSelectedPlace() = calculateRoutesToSelectedPlaceImpl()
    fun selectCandidateRoute(index: Int) = selectCandidateRouteImpl(index)
    fun startNavigation(forceSimulate: Boolean? = null) = startNavigationImpl(forceSimulate)
    fun toggleSimulationFromMap() = toggleSimulationFromMapImpl()
    fun stopSimulationAndReturnToRealGps() = stopSimulationAndReturnToRealGpsImpl()
    fun cycleSimulationSpeed() = cycleSimulationSpeedImpl()
    fun resetSimulationSpeedTo1x() = resetSimulationSpeedTo1xImpl()
    fun triggerSimulatedWrongTurn() = triggerSimulatedWrongTurnImpl()
    fun dismissJunctionPreview() = dismissJunctionPreviewImpl()
    fun repeatLastVoiceInstruction() = voiceManager.repeatLastInstruction(settings.value.language)
    fun stopNavigation() = stopNavigationImpl()

    // --- DRIVING REPORTS & BACKUP ---
    fun submitUserReport(type: ReportType) = submitUserReportImpl(type)
    fun confirmUserReport(reportId: Long) = confirmUserReportImpl(reportId)
    fun updateReportNote(reportId: Long, note: String) = updateReportNoteImpl(reportId, note)
    fun dismissUserReport(reportId: Long) = dismissUserReportImpl(reportId)
    fun exportReportsToUri(uri: Uri) = exportReportsToUriImpl(uri)
    fun importReportsFromUri(uri: Uri) = importReportsFromUriImpl(uri)
    fun exportFullBackupToUri(uri: Uri) = exportFullBackupToUriImpl(uri)
    fun importFullBackupFromUri(uri: Uri) = importFullBackupFromUriImpl(uri)

    // --- MAP PACKS & ONLINE SERVICES ---
    fun refreshManifest() = refreshManifestImpl()
    fun checkDailyMapUpdatesIfDue() = checkDailyMapUpdatesIfDueImpl()
    fun requestDownloadStatePack(stateCode: String) = requestDownloadStatePackImpl(stateCode)
    fun confirmLowStorageDownload() = confirmLowStorageDownloadImpl()
    fun dismissLowStoragePrompt() { _lowStoragePromptStateCode.value = null }
    fun updateAllOutdatedPacks() = updateAllOutdatedPacksImpl()
    fun pauseStatePackDownload(stateCode: String) = packManager.pauseDownload(stateCode)
    fun deleteStatePack(stateCode: String) { viewModelScope.launch { packManager.deletePack(stateCode) } }
    fun importLocalPackFile(stateCode: String, uri: Uri, displayName: String?) =
        importLocalPackFileImpl(stateCode, uri, displayName)
    fun dismissWeatherAlert(alertId: String) { _dismissedWeatherAlertIds.value = _dismissedWeatherAlertIds.value + alertId }
    fun refreshWeatherAlertsIfEligible(explicitRoute: OfflineRoute? = null) =
        refreshWeatherAlertsIfEligibleImpl(explicitRoute)
    internal fun updateWeatherSourceStatus(state: OnlineSourceState, detail: String = "") =
        updateWeatherSourceStatusImpl(state, detail)
    internal fun updateOnlineSearchSourceStatus(state: OnlineSourceState, detail: String = "") =
        updateOnlineSearchSourceStatusImpl(state, detail)
    internal fun updateMapUpdatesSourceStatus(state: OnlineSourceState, detail: String = "") =
        updateMapUpdatesSourceStatusImpl(state, detail)

    // --- SETTINGS & DIAGNOSTICS ---
    fun setLanguage(language: AppLanguage) = setLanguageImpl(language)
    fun dismissPersianTtsNotice() = dismissPersianTtsNoticeImpl()
    fun setDistanceUnit(unit: DistanceUnit) { viewModelScope.launch { prefs.setDistanceUnit(unit) } }
    fun setThemeMode(mode: MapThemeMode) { viewModelScope.launch { prefs.setThemeMode(mode) } }
    fun setVoiceEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setVoiceGuidanceEnabled(enabled) } }
    fun setSpeedAudioEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setSpeedWarningAudioEnabled(enabled) } }
    fun setSpeedWarningThresholdMph(thresholdMph: Int) { viewModelScope.launch { prefs.setSpeedWarningThresholdMph(thresholdMph) } }
    fun setJunctionPreviewEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setJunctionPreviewEnabled(enabled) } }
    fun setAlertSpeedDropEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSpeedDropEnabled(enabled) } }
    fun setAlertSharpCurveEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSharpCurveEnabled(enabled) } }
    fun setAlertTollBoothEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertTollBoothEnabled(enabled) } }
    fun setAlertRailroadEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertRailroadEnabled(enabled) } }
    fun setAlertSchoolZoneEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSchoolZoneEnabled(enabled) } }
    fun setAlertStopSignEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertStopSignEnabled(enabled) } }
    fun setAlertSpeedCameraEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSpeedCameraEnabled(enabled) } }
    fun setAlertRedLightCameraEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertRedLightCameraEnabled(enabled) } }
    fun setShowTrafficLights(enabled: Boolean) = setShowTrafficLightsImpl(enabled)
    fun setShow3DBuildings(enabled: Boolean) { viewModelScope.launch { prefs.setShow3DBuildings(enabled) } }
    fun setPositionMarkerStyle(style: String) { viewModelScope.launch { prefs.setPositionMarkerStyle(style) } }
    fun setOnlineFeaturesEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setOnlineFeaturesEnabled(enabled) } }
    fun setOnlineWeatherAlertsEnabled(enabled: Boolean) = setOnlineWeatherAlertsEnabledImpl(enabled)
    fun setOnlineSearchFallbackEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setOnlineSearchFallbackEnabled(enabled) } }
    fun setAutoCheckMapUpdatesEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAutoCheckMapUpdatesEnabled(enabled) } }
    fun setWifiOnlyLargeDownloads(enabled: Boolean) { viewModelScope.launch { prefs.setWifiOnlyLargeDownloads(enabled) } }
    fun setAutoUpdateMapsOnWifi(enabled: Boolean) { viewModelScope.launch { prefs.setAutoUpdateMapsOnWifi(enabled) } }
    fun setAvoidTolls(avoid: Boolean) = setAvoidTollsImpl(avoid)
    fun setAvoidHighways(avoid: Boolean) = setAvoidHighwaysImpl(avoid)
    fun setAvoidFerries(avoid: Boolean) = setAvoidFerriesImpl(avoid)
    fun setRoutePreference(preference: RoutePreference) = setRoutePreferenceImpl(preference)
    fun saveGitHubRepository(owner: String, repo: String) = saveGitHubRepositoryImpl(owner, repo)
    fun savePackBaseUrl(url: String) = savePackBaseUrlImpl(url)
    fun completeOnboarding() { viewModelScope.launch { prefs.setOnboardingCompleted(true) } }
    fun setDevOnlineOregonMode(enabled: Boolean) = setDevOnlineOregonModeImpl(enabled)
    fun setSimulateDriveEnabled(enabled: Boolean) = setSimulateDriveEnabledImpl(enabled)
    fun setUseEnglishVoiceInPersianMode(enabled: Boolean) { viewModelScope.launch { prefs.setUseEnglishVoiceInPersianMode(enabled) } }
    fun testVoiceGuidance() = testVoiceGuidanceImpl()
    fun refreshTtsDiagnostics() = voiceManager.refreshTtsDiagnostics()
    fun testPersianVoice() = testPersianVoiceImpl()
    fun testEnglishVoice() { voiceManager.speakExplicitLanguageTest(AppLanguage.ENGLISH) }
    fun getPackFileSizes(stateCode: String) = packManager.getPackFileSizes(stateCode)
    fun readLastErrorLogLines(maxLines: Int = 20): List<String> = CrashLogManager.readLastErrorLines(app, maxLines)
    fun showSnackbar(message: String) { _snackbarMessage.value = message }
    fun consumeSnackbar() { _snackbarMessage.value = null }

    companion object {
        const val EUGENE_OR_LAT = 44.0521
        const val EUGENE_OR_LON = -123.0868
        val SIMULATION_SPEED_STEPS = listOf(1, 2, 4, 8, 16, 32)
    }
}
