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
import com.example.data.online.GoogleMapsPlacesClient
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
    internal val googleMapsPlacesClient: GoogleMapsPlacesClient? =
        if (GoogleMapsPlacesClient.isOptionalKeyConfigured()) GoogleMapsPlacesClient(app) else null
    internal val savedPlaceDao = app.database.savedPlaceDao()
    internal val userReportDao = app.database.userReportDao()
    internal val installedPackDao = app.database.installedPackDao()
    internal val parkedCarDao = app.database.parkedCarDao()
    internal val wrongLaneReportDao = app.database.wrongLaneReportDao()
    internal val parkedCarManager = com.example.navigation.ParkedCarManager()
    internal val carBluetoothReceiver = com.example.navigation.CarBluetoothReceiver(
        selectedAddressProvider = { settings.value.carBluetoothDeviceAddress },
        onSelectedCarDisconnected = { onCarBluetoothDisconnected() }
    )
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

    val parkedCar: StateFlow<com.example.data.local.ParkedCarEntity?> = parkedCarDao.observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val wrongLaneReports: StateFlow<List<com.example.data.local.WrongLaneReportEntity>> =
        wrongLaneReportDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    internal val navigationHub = app.navigationHub
    private val navStore = navigationHub.stateStore

    internal val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    internal val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults.asStateFlow()
    internal val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    internal val _googleMapsSearchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val googleMapsSearchResults: StateFlow<List<PlaceSearchResult>> = _googleMapsSearchResults.asStateFlow()
    internal val _isSearchingGoogleMaps = MutableStateFlow(false)
    val isSearchingGoogleMaps: StateFlow<Boolean> = _isSearchingGoogleMaps.asStateFlow()
    internal val _googleMapsSearchStatus = MutableStateFlow<String?>(null)
    val googleMapsSearchStatus: StateFlow<String?> = _googleMapsSearchStatus.asStateFlow()
    internal val _selectedPlace: MutableStateFlow<PlaceSearchResult?> = navStore._selectedPlace
    val selectedPlace: StateFlow<PlaceSearchResult?> = navStore.selectedPlace

    internal val _candidateRoutes: MutableStateFlow<List<OfflineRoute>> = navStore._candidateRoutes
    val candidateRoutes: StateFlow<List<OfflineRoute>> = navStore.candidateRoutes
    internal val _selectedRouteIndex: MutableStateFlow<Int> = navStore._selectedRouteIndex
    val selectedRouteIndex: StateFlow<Int> = navStore.selectedRouteIndex
    internal val _isCalculatingRoute: MutableStateFlow<Boolean> = navStore._isCalculatingRoute
    val isCalculatingRoute: StateFlow<Boolean> = navStore.isCalculatingRoute
    internal val _routeError: MutableStateFlow<String?> = navStore._routeError
    val routeError: StateFlow<String?> = navStore.routeError
    internal val _missingStatesForRoute: MutableStateFlow<List<UsStateInfo>> = navStore._missingStatesForRoute
    val missingStatesForRoute: StateFlow<List<UsStateInfo>> = navStore.missingStatesForRoute
    internal val _selectedTravelMode = MutableStateFlow(com.example.navigation.TravelMode.DRIVE)
    val selectedTravelMode: StateFlow<com.example.navigation.TravelMode> = _selectedTravelMode.asStateFlow()
    internal val _modePreviewSummaries = MutableStateFlow<Map<com.example.navigation.TravelMode, com.example.ui.screens.ModePreviewSummary>>(emptyMap())
    val modePreviewSummaries: StateFlow<Map<com.example.navigation.TravelMode, com.example.ui.screens.ModePreviewSummary>> = _modePreviewSummaries.asStateFlow()

    internal val _navigationState: MutableStateFlow<ActiveNavigationState> = navStore._navigationState
    val navigationState: StateFlow<ActiveNavigationState> = navStore.navigationState

    internal val _cameraCommand = MutableStateFlow<CameraTargetCommand?>(null)
    val cameraCommand: StateFlow<CameraTargetCommand?> = _cameraCommand.asStateFlow()
    internal val _cameraGeneration = MutableStateFlow(0L)
    val cameraGeneration: StateFlow<Long> = _cameraGeneration.asStateFlow()
    internal val _isCameraFollowing = MutableStateFlow(true)
    val isCameraFollowing: StateFlow<Boolean> = _isCameraFollowing.asStateFlow()
    internal val _isHeadingUpMode: MutableStateFlow<Boolean> = navStore._isHeadingUpMode
    val isHeadingUpMode: StateFlow<Boolean> = navStore.isHeadingUpMode
    internal val _userHeadingDegrees: MutableStateFlow<Float> = navStore._userHeadingDegrees
    val userHeadingDegrees: StateFlow<Float> = navStore.userHeadingDegrees
    internal var hasHeadingSample = false
    internal var lastHeadingEmitElapsedMs = 0L

    internal val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    internal val _lowStoragePromptStateCode = MutableStateFlow<String?>(null)
    val lowStoragePromptStateCode: StateFlow<String?> = _lowStoragePromptStateCode.asStateFlow()
    internal val _showPersianTtsDialog = MutableStateFlow(false)
    val showPersianTtsDialog: StateFlow<Boolean> = _showPersianTtsDialog.asStateFlow()
    internal val _simulationSpeedMultiplier: MutableStateFlow<Int> = navStore._simulationSpeedMultiplier
    val simulationSpeedMultiplier: StateFlow<Int> = navStore.simulationSpeedMultiplier

    internal val _visibleCameras: MutableStateFlow<List<RoadHazardPoint>> = navStore._visibleCameras
    val visibleCameras: StateFlow<List<RoadHazardPoint>> = navStore.visibleCameras
    internal val _visibleTrafficLights: MutableStateFlow<List<RoadHazardPoint>> = navStore._visibleTrafficLights
    val visibleTrafficLights: StateFlow<List<RoadHazardPoint>> = navStore.visibleTrafficLights
    internal val _visibleSpeedBumps: MutableStateFlow<List<RoadHazardPoint>> = navStore._visibleSpeedBumps
    val visibleSpeedBumps: StateFlow<List<RoadHazardPoint>> = navStore.visibleSpeedBumps
    internal val _visibleStopSigns: MutableStateFlow<List<RoadHazardPoint>> = navStore._visibleStopSigns
    val visibleStopSigns: StateFlow<List<RoadHazardPoint>> = navStore.visibleStopSigns
    internal val _aheadTrafficLightMeters: MutableStateFlow<Double?> = navStore._aheadTrafficLightMeters
    val aheadTrafficLightMeters: StateFlow<Double?> = navStore.aheadTrafficLightMeters
    internal val _freeDriveAheadAlert: MutableStateFlow<AheadAlert?> = navStore._freeDriveAheadAlert
    val freeDriveAheadAlert: StateFlow<AheadAlert?> = navStore.freeDriveAheadAlert

    internal val _externalLinkMatchChoices = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val externalLinkMatchChoices: StateFlow<List<PlaceSearchResult>> = _externalLinkMatchChoices.asStateFlow()
    internal val _openDriveTabEvent = MutableStateFlow(0L)
    val openDriveTabEvent: StateFlow<Long> = _openDriveTabEvent.asStateFlow()
    internal val _openMapsTabEvent = MutableStateFlow(0L)
    val openMapsTabEvent: StateFlow<Long> = _openMapsTabEvent.asStateFlow()

    fun requestOpenMapsTab() {
        _openMapsTabEvent.value = System.currentTimeMillis()
    }

    internal val _cachedWeatherAlerts: MutableStateFlow<List<WeatherAlertItem>> = navStore._cachedWeatherAlerts
    internal val _dismissedWeatherAlertIds: MutableStateFlow<Set<String>> = navStore._dismissedWeatherAlertIds
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
    internal var googleMapsSearchJob: Job? = null
    internal var gpsWatchdogJob: Job? = null
    internal var simulationJob: Job? = null
    internal var arrivalEndJob: Job? = null
    internal var multiModePreviewJob: Job? = null
    internal var autoRecenterJob: Job? = null
    internal var cameraFetchJob: Job? = null
    internal var trafficLightFetchJob: Job? = null
    internal var speedBumpFetchJob: Job? = null
    internal var stopSignFetchJob: Job? = null
    internal var lastCameraFetchLoc: LatLon? = null
    internal var lastTrafficLightFetchLoc: LatLon? = null
    internal var lastSpeedBumpFetchLoc: LatLon? = null
    internal var lastStopSignFetchLoc: LatLon? = null
    internal var lastGpsReceivedMs: Long = 0L
    internal var simulatedWrongTurnTicksRemaining: Int = 0
    internal var simulatedClockMs: Long = 0L
    internal var cachedDbHazards: List<RoadHazardPoint> = emptyList()
    internal var cachedFraCrossings: List<com.example.data.packs.FraCrossingRecord> = emptyList()
    internal var syntheticSimSpeedBumps: List<RoadHazardPoint> = emptyList()
    internal val _destinationParkingOptions = MutableStateFlow<List<com.example.navigation.DestinationParkingOption>>(emptyList())
    val destinationParkingOptions: StateFlow<List<com.example.navigation.DestinationParkingOption>> = _destinationParkingOptions.asStateFlow()
    internal val _showDestinationParkingCard = MutableStateFlow(false)
    val showDestinationParkingCard: StateFlow<Boolean> = _showDestinationParkingCard.asStateFlow()
    internal val _originalFinalDestination = MutableStateFlow<PlaceSearchResult?>(null)
    val originalFinalDestination: StateFlow<PlaceSearchResult?> = _originalFinalDestination.asStateFlow()
    internal val _showWalkToOriginalDestinationPrompt = MutableStateFlow(false)
    val showWalkToOriginalDestinationPrompt: StateFlow<Boolean> = _showWalkToOriginalDestinationPrompt.asStateFlow()
    internal var destinationParkingJob: Job? = null
    internal var lastAutoParkingRouteId: String? = null
    internal val speedBumpEvaluator = com.example.navigation.SpeedBumpAlertEvaluator()
    internal val trafficLightVoiceEvaluator = com.example.navigation.TrafficLightVoiceEvaluator()
    internal val laneChangeCueTracker = com.example.navigation.LaneChangeCueTracker()
    internal val turnFocusTracker = com.example.navigation.TurnFocusSessionTracker()
    internal var turnFocusUserPannedDuringActive: Boolean = false
    internal var turnFocusPendingEnterAnim: Boolean = false
    internal var turnFocusPendingTightenAnim: Boolean = false
    internal var turnFocusPendingEnterAnimMs: Int = com.example.navigation.TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS
    internal var turnFocusPendingExitAnim: Boolean = false
    internal var lastFocusCameraTarget: CameraTargetCommand? = null
    internal val speedLimitDropTracker = com.example.navigation.SpeedLimitDropVoiceTracker()
    internal val hpmsFallbackCache = com.example.navigation.HpmsSpeedFallbackCache()
    internal var cachedHpmsThroughLanes: Int? = null
    internal var lastHpmsLanesLookupMs: Long = 0L
    internal var lastHpmsLanesLat: Double? = null
    internal var lastHpmsLanesLon: Double? = null
    internal var lastHpmsLanesFetchKey: String = ""
    internal val voiceCommandManager = com.example.navigation.OfflineVoiceCommandManager(app)
    val voiceListenState: StateFlow<com.example.navigation.VoiceListenUiState> = voiceCommandManager.uiState
    internal val _voiceAutoStartSecondsLeft = MutableStateFlow(0)
    val voiceAutoStartSecondsLeft: StateFlow<Int> = _voiceAutoStartSecondsLeft.asStateFlow()
    internal val _openSearchResultsEvent = MutableStateFlow(0L)
    val openSearchResultsEvent: StateFlow<Long> = _openSearchResultsEvent.asStateFlow()
    internal var voiceAutoStartJob: Job? = null

    private val hubDelegate = object : com.example.navigation.PhoneNavigationDelegate {
        override fun onCalculateRoutesForPlace(place: PlaceSearchResult) {
            _selectedTravelMode.value = com.example.navigation.TravelMode.DRIVE
            selectPlace(place)
        }
        override fun onSelectCandidateRoute(index: Int) {
            selectCandidateRoute(index)
        }
        override fun onStartNavigation(forceSimulate: Boolean?) {
            if (_selectedTravelMode.value != com.example.navigation.TravelMode.DRIVE) {
                selectTravelMode(com.example.navigation.TravelMode.DRIVE)
            }
            startNavigation(forceSimulate)
        }
        override fun onStopNavigation() {
            stopNavigation()
        }
        override fun onToggleSimulationForAutoDrive() {
            if (_navigationState.value.isNavigating) {
                startSimulationOnActiveRouteImpl()
            } else if (_candidateRoutes.value.isNotEmpty() && _selectedPlace.value != null) {
                startNavigation(forceSimulate = true)
            }
        }
        override fun onReportRoadClosure() {
            submitUserReport(ReportType.ROAD_CLOSURE)
        }
    }

    init {
        navigationHub.attachPhoneDelegate(hubDelegate)
        initializeViewModel()
    }

    // --- LOCATION, CAMERA & PACK INFO ---
    fun startLocationTracking() {
        locationTracker.startTracking()
        compassTracker.startIfActive()
    }

    fun setAppInForeground(inForeground: Boolean) {
        navigationHub.setPhoneUiInForeground(inForeground)
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
    fun searchGoogleMapsOnline(query: String = _searchQuery.value) = searchGoogleMapsOnlineImpl(query)
    fun isGoogleMapsKeyConfigured(): Boolean = googleMapsPlacesClient?.isConfigured == true
    fun dropPinOnMap(lat: Double, lon: Double) = dropPinOnMapImpl(lat, lon)
    fun selectPlace(place: PlaceSearchResult) = selectPlaceImpl(place)
    fun clearSelectedPlace() { cancelVoiceAutoStartCountdownImpl(); clearSelectedPlaceImpl() }
    fun handleExternalMapIntent(intent: Intent?) = handleExternalMapIntentImpl(intent)
    fun selectExternalMatchChoice(place: PlaceSearchResult) = selectExternalMatchChoiceImpl(place)
    fun dismissExternalMatchChoices() = dismissExternalMatchChoicesImpl()
    fun savePlaceAs(place: PlaceSearchResult, type: SavedPlaceType) = savePlaceAsImpl(place, type)
    fun deleteSavedPlace(id: Long) = deleteSavedPlaceImpl(id)

    // --- ROUTING, NAVIGATION & SIMULATION ---
    fun calculateRoutesToSelectedPlace() = calculateRoutesToSelectedPlaceImpl()
    fun selectTravelMode(mode: com.example.navigation.TravelMode) = selectTravelModeImpl(mode)
    fun selectCandidateRoute(index: Int) = selectCandidateRouteImpl(index)
    fun startNavigation(forceSimulate: Boolean? = null) { cancelVoiceAutoStartCountdownImpl(); startNavigationImpl(forceSimulate) }
    fun toggleSimulationFromMap() = toggleSimulationFromMapImpl()
    fun stopSimulationAndReturnToRealGps() = stopSimulationAndReturnToRealGpsImpl()
    fun cycleSimulationSpeed() = cycleSimulationSpeedImpl()
    fun resetSimulationSpeedTo1x() = resetSimulationSpeedTo1xImpl()
    fun stepSimulationSpeedUp() = stepSimulationSpeedUpImpl()
    fun stepSimulationSpeedDown() = stepSimulationSpeedDownImpl()
    fun triggerSimulatedWrongTurn() = triggerSimulatedWrongTurnImpl()
    fun dismissJunctionPreview() = dismissJunctionPreviewImpl()
    fun repeatLastVoiceInstruction() = voiceManager.repeatLastInstruction(settings.value.language)
    fun stopNavigation() = stopNavigationImpl()

    // --- OFFLINE ENGLISH VOICE COMMANDS (VC1-VC3, phone only) ---
    fun startVoiceCommandListening() = startVoiceCommandListeningImpl()
    fun cancelVoiceCommandListening() = cancelVoiceCommandListeningImpl()
    fun downloadOfflineEnglishModel() = downloadOfflineEnglishModelImpl()
    fun cancelVoiceAutoStartCountdown() = cancelVoiceAutoStartCountdownImpl()

    // --- PARKED CAR & WALK TO CAR (F4) ---
    fun parkCarHereManually() = parkCarHereManuallyImpl()
    fun updateParkedCarNote(note: String) = updateParkedCarNoteImpl(note)
    fun clearParkedCar() = clearParkedCarImpl()
    fun startWalkToCar() = startWalkToCarImpl()
    fun onCarBluetoothDisconnected() = onCarBluetoothDisconnectedImpl()
    fun searchParkingNearPreviewDestination() = searchParkingNearPreviewDestinationImpl()
    fun selectDestinationParking(option: com.example.navigation.DestinationParkingOption) = selectDestinationParkingImpl(option)
    fun dismissDestinationParkingCard() { _showDestinationParkingCard.value = false; _destinationParkingOptions.value = emptyList() }
    fun startWalkFromParkingToOriginalDestination() = startWalkFromParkingToOriginalDestinationImpl()
    fun dismissWalkToOriginalDestinationPrompt() = dismissWalkToOriginalDestinationPromptImpl()
    fun setSkipParkingAtHome(enabled: Boolean) { viewModelScope.launch { prefs.setSkipParkingAtHome(enabled) } }
    fun setCarBluetoothDevice(address: String, name: String) {
        viewModelScope.launch { prefs.setCarBluetoothDevice(address, name) }
    }

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
    fun setCarMapView(view: com.example.car.CarMapView) { viewModelScope.launch { prefs.setCarMapView(view) } }
    fun setVoiceEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setVoiceGuidanceEnabled(enabled) } }
    fun setSpeedAudioEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setSpeedWarningAudioEnabled(enabled) } }
    fun setSpeedWarningThresholdMph(thresholdMph: Int) { viewModelScope.launch { prefs.setSpeedWarningThresholdMph(thresholdMph) } }
    fun setJunctionPreviewEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setJunctionPreviewEnabled(enabled) } }
    fun setLaneHighlightOnMapEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setLaneHighlightOnMapEnabled(enabled) } }
    fun setLaneHighlightColor(color: com.example.map.LaneHighlightColor) { viewModelScope.launch { prefs.setLaneHighlightColor(color) } }
    fun setTurnFocusModeEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setTurnFocusModeEnabled(enabled) } }
    fun setTurnFocusBeepsEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setTurnFocusBeepsEnabled(enabled) } }
    fun setTurnFocusCityDistanceMeters(meters: Int) { viewModelScope.launch { prefs.setTurnFocusCityDistanceMeters(meters) } }
    fun setTurnFocusHighwayDistanceMeters(meters: Int) { viewModelScope.launch { prefs.setTurnFocusHighwayDistanceMeters(meters) } }
    fun setTurnFocusWideHighwayEarlierEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setTurnFocusWideHighwayEarlierEnabled(enabled) } }
    fun setTurnFocusShortPhraseEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setTurnFocusShortPhraseEnabled(enabled) } }
    fun setLaneChangeBeepsEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setLaneChangeBeepsEnabled(enabled) } }
    fun setLaneChangeTiming(timing: com.example.navigation.LaneChangeTiming) { viewModelScope.launch { prefs.setLaneChangeTiming(timing) } }
    fun setLastChanceBeepEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setLastChanceBeepEnabled(enabled) } }
    fun setAlertSpeedDropEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSpeedDropEnabled(enabled) } }
    fun setAlertSharpCurveEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSharpCurveEnabled(enabled) } }
    fun setAlertTollBoothEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertTollBoothEnabled(enabled) } }
    fun setAlertRailroadEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertRailroadEnabled(enabled) } }
    fun setAlertSchoolZoneEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSchoolZoneEnabled(enabled) } }
    fun setAlertStopSignEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertStopSignEnabled(enabled) } }
    fun setAlertTrafficLightEnabled(enabled: Boolean) = setAlertTrafficLightEnabledImpl(enabled)
    fun setAlertSpeedCameraEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSpeedCameraEnabled(enabled) } }
    fun setAlertRedLightCameraEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertRedLightCameraEnabled(enabled) } }
    fun setAlertSpeedBumpEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSpeedBumpEnabled(enabled) } }
    fun setAlertSteepGradeEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setAlertSteepGradeEnabled(enabled) } }
    fun setDefaultTravelMode(mode: com.example.navigation.TravelMode) {
        _selectedTravelMode.value = mode
        viewModelScope.launch { prefs.setDefaultTravelMode(mode); prefs.setLastTravelMode(mode) }
    }
    fun setWalkingSpeedPreset(preset: com.example.navigation.WalkingSpeedPreset) { viewModelScope.launch { prefs.setWalkingSpeedPreset(preset) } }
    fun setCyclingSpeedPreset(preset: com.example.navigation.CyclingSpeedPreset) { viewModelScope.launch { prefs.setCyclingSpeedPreset(preset) } }
    fun setAvoidHillsWhenCycling(avoid: Boolean) { viewModelScope.launch { prefs.setAvoidHillsWhenCycling(avoid) } }
    fun setShowTrafficLights(enabled: Boolean) = setShowTrafficLightsImpl(enabled)
    fun setShowStopSignsOnMap(enabled: Boolean) = setShowStopSignsOnMapImpl(enabled)
    fun setShow3DBuildings(enabled: Boolean) { viewModelScope.launch { prefs.setShow3DBuildings(enabled) } }
    fun setPositionMarkerStyle(style: String) { viewModelScope.launch { prefs.setPositionMarkerStyle(style) } }
    fun setOnlineFeaturesEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setOnlineFeaturesEnabled(enabled) } }
    fun setOnlineWeatherAlertsEnabled(enabled: Boolean) = setOnlineWeatherAlertsEnabledImpl(enabled)
    fun setOnlineSearchFallbackEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setOnlineSearchFallbackEnabled(enabled) } }
    fun setGoogleMapsOnlineSearchEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setGoogleMapsOnlineSearchEnabled(enabled) } }
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
    fun setPersianVoiceMode(mode: com.example.navigation.PersianVoiceMode) { viewModelScope.launch { prefs.setPersianVoiceMode(mode) } }
    fun selectBuiltInPersianVoice() = selectBuiltInPersianVoiceImpl()
    fun playBuiltInPersianSample() = playBuiltInPersianSampleImpl()
    fun setUseEnglishVoiceInPersianMode(enabled: Boolean) { viewModelScope.launch { prefs.setUseEnglishVoiceInPersianMode(enabled) } }
    fun testVoiceGuidance() = testVoiceGuidanceImpl()
    fun refreshTtsDiagnostics() = voiceManager.refreshTtsDiagnostics()
    fun testPersianVoice() = testPersianVoiceImpl()
    fun testEnglishVoice() { voiceManager.speakExplicitLanguageTest(AppLanguage.ENGLISH) }
    fun getPackFileSizes(stateCode: String) = packManager.getPackFileSizes(stateCode)
    fun readLastErrorLogLines(maxLines: Int = 20): List<String> = CrashLogManager.readLastErrorLines(app, maxLines)
    fun reportWrongLane() = reportWrongLaneImpl()
    fun clearWrongLaneReports() = clearWrongLaneReportsImpl()
    fun showSnackbar(message: String) { _snackbarMessage.value = message }
    fun consumeSnackbar() { _snackbarMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        voiceCommandManager.destroy()
        navigationHub.detachPhoneDelegate(hubDelegate)
        carBluetoothReceiver.unregister(app)
    }

    companion object {
        const val EUGENE_OR_LAT = 44.0521
        const val EUGENE_OR_LON = -123.0868
        val SIMULATION_SPEED_STEPS = listOf(1, 2, 4, 8, 16, 32)
    }
}
