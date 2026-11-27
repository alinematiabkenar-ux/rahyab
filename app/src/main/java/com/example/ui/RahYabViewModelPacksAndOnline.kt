package com.example.ui

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.local.PackInstallStatus
import com.example.data.model.UsStatesCatalog
import com.example.data.online.OnlineSourceState
import com.example.data.online.OnlineSourceStatus
import com.example.data.online.OnlineSourcesSummary
import com.example.data.packs.StatePackUiItem
import com.example.i18n.BilingualFormatter
import com.example.i18n.stringsFor
import com.example.navigation.OfflineRoute
import com.example.util.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun RahYabViewModel.buildOnlineSourcesSummaryFlow(): StateFlow<OnlineSourcesSummary> = combine(
    networkState,
    settings,
    _rawOnlineSources
) { net, s, raw ->
    fun resolve(enabled: Boolean, status: OnlineSourceStatus): OnlineSourceStatus {
        return when {
            !s.onlineFeaturesEnabled || !enabled -> status.copy(state = OnlineSourceState.DISABLED)
            !net.isOnline -> status.copy(state = OnlineSourceState.OFFLINE)
            else -> status
        }
    }
    OnlineSourcesSummary(
        weatherAlerts = resolve(s.onlineWeatherAlertsEnabled, raw.weatherAlerts),
        onlineSearch = resolve(s.onlineSearchFallbackEnabled, raw.onlineSearch),
        mapUpdates = resolve(s.autoCheckMapUpdatesEnabled, raw.mapUpdates)
    )
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = OnlineSourcesSummary()
)

internal fun RahYabViewModel.buildStatePacksFlow(): StateFlow<List<StatePackUiItem>> = combine(
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

        val smartBytes = if (remote != null && status == PackInstallStatus.UPDATE_AVAILABLE && inst != null) {
            val hashes = com.example.data.packs.InstalledComponentHashes(
                pmtilesSha256 = inst.pmtilesSha256,
                valhallaSha256 = inst.valhallaSha256,
                placesSha256 = inst.placesSha256
            )
            com.example.data.packs.MapUpdateSmartPlanner.planSmartUpdate(
                localHashes = hashes,
                remote = remote,
                hasLocalPmtiles = inst.hasPmtiles,
                hasLocalValhalla = inst.hasValhalla,
                hasLocalPlaces = inst.hasPlacesDb
            ).downloadBytes.takeIf { it > 0L } ?: totalBytes
        } else {
            0L
        }

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
            hasUsableRoutingTiles = (inst?.hasValhalla == true) && packManager.hasUsableRoutingForState(state.code),
            hasAddressIndex = (inst?.hasPlacesDb == true) && packManager.hasUsableAddressIndex(state.code),
            hasManifestEntry = remote != null,
            isCurrentLocationState = (state.code == locStateCode),
            isQueued = (status == PackInstallStatus.QUEUED),
            placesSource = inst?.placesSource?.takeIf { it.isNotBlank() } ?: remote?.placesSource ?: "overture+osm",
            smartUpdateDownloadBytes = smartBytes,
            hasPendingDeferredSwap = com.example.data.packs.MapUpdateSmartPlanner.hasPendingSwapForState(
                packManager.packsRootDir,
                state.code
            ),
            errorMessage = inst?.errorMessage
        )
    }
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = emptyList()
)

internal fun RahYabViewModel.getInstalledDataBuildVersionImpl(): String {
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

internal fun RahYabViewModel.refreshManifestImpl() {
    val url = settings.value.effectivePackBaseUrl
    viewModelScope.launch {
        val nowMs = System.currentTimeMillis()
        val res = packManager.fetchManifest(url)
        res.onSuccess {
            val outdated = withContext(Dispatchers.IO) {
                installedPackDao.getAllOnce().filter { it.status == PackInstallStatus.UPDATE_AVAILABLE.name }
            }
            val summary = if (outdated.isEmpty()) "Up to date" else "${outdated.size} update(s) available"
            prefs.setLastMapUpdateCheck(nowMs, summary)
            updateMapUpdatesSourceStatus(OnlineSourceState.ACTIVE)
        }.onFailure { err ->
            prefs.setLastMapUpdateCheck(nowMs, "Failed: ${err.message.orEmpty().take(60)}")
            updateMapUpdatesSourceStatus(OnlineSourceState.ERROR, err.message.orEmpty().take(60))
        }
    }
}

internal fun RahYabViewModel.checkDailyMapUpdatesIfDueImpl() {
    val s = settings.value
    val net = networkState.value
    if (!s.onlineFeaturesEnabled || !s.autoCheckMapUpdatesEnabled || !net.isOnline) return
    val baseUrl = s.effectivePackBaseUrl
    if (baseUrl.isBlank()) return
    val now = System.currentTimeMillis()
    val oneDayMs = 24L * 60L * 60_000L
    if (now - s.lastManifestCheckEpochMs < oneDayMs) return
    if (dailyManifestCheckJob?.isActive == true) return

    dailyManifestCheckJob = viewModelScope.launch {
        val checkMs = System.currentTimeMillis()
        val res = packManager.fetchManifest(baseUrl)
        res.onSuccess {
            val outdated = withContext(Dispatchers.IO) {
                installedPackDao.getAllOnce().filter {
                    it.status == PackInstallStatus.UPDATE_AVAILABLE.name
                }
            }
            val summary = if (outdated.isEmpty()) {
                "Up to date"
            } else {
                val codes = outdated.joinToString(", ") { it.stateCode.uppercase() }
                "${outdated.size} update(s) available ($codes)"
            }
            prefs.setLastMapUpdateCheck(checkMs, summary)
            val str = stringsFor(settings.value.language)
            updateMapUpdatesSourceStatus(
                OnlineSourceState.ACTIVE,
                if (outdated.isNotEmpty()) "${outdated.size} — ${str.mapUpdateNotificationTitle}" else ""
            )
            if (outdated.isNotEmpty()) {
                val lang = settings.value.language
                val names = outdated.map { pack ->
                    val info = UsStatesCatalog.byCode[pack.stateCode.lowercase()]
                    if (lang == com.example.i18n.AppLanguage.PERSIAN) {
                        info?.nameFa ?: pack.stateCode.uppercase()
                    } else {
                        info?.nameEn ?: pack.stateCode.uppercase()
                    }
                }
                com.example.data.packs.MapUpdateCheckWorker.postMapUpdateNotification(
                    getApplication<Application>(),
                    names,
                    lang
                )
                val latestSettings = settings.value
                // Only auto-download when ON AND on unmetered Wi-Fi while charging
                if (latestSettings.autoUpdateMapsOnWifi) {
                    val appContext = getApplication<Application>()
                    if (com.example.data.packs.MapUpdateCheckWorker.isOnUnmeteredWifiAndCharging(appContext)) {
                        packManager.updateAllOutdatedPacks(latestSettings.effectivePackBaseUrl)
                    } else {
                        com.example.data.packs.MapUpdateCheckWorker.enqueueAutoDownloadWhenUnmeteredCharging(appContext)
                    }
                }
            }
        }.onFailure { err ->
            prefs.setLastMapUpdateCheck(checkMs, "Failed: ${err.message.orEmpty().take(60)}")
            updateMapUpdatesSourceStatus(OnlineSourceState.ERROR, err.message.orEmpty().take(60))
        }
    }
}

internal fun RahYabViewModel.requestDownloadStatePackImpl(stateCode: String) {
    val pack = statePacks.value.find { it.stateCode == stateCode } ?: return
    val s = settings.value
    val net = networkState.value
    if (s.wifiOnlyLargeDownloads && net.isMobileData && !net.isWifi) {
        _snackbarMessage.value = stringsFor(s.language).mapUpdateWifiOnlyNotice
        return
    }
    val baseUrl = s.effectivePackBaseUrl
    val requiredSize = if (pack.status == PackInstallStatus.UPDATE_AVAILABLE && pack.smartUpdateDownloadBytes > 0L) {
        pack.smartUpdateDownloadBytes
    } else {
        pack.totalSizeBytes
    }
    if (requiredSize > 0L && packManager.isLowStorageForPack(requiredSize)) {
        _lowStoragePromptStateCode.value = stateCode
    } else {
        packManager.startOrResumeDownload(stateCode, baseUrl)
    }
}

internal fun RahYabViewModel.confirmLowStorageDownloadImpl() {
    val code = _lowStoragePromptStateCode.value ?: return
    _lowStoragePromptStateCode.value = null
    packManager.startOrResumeDownload(code, settings.value.effectivePackBaseUrl)
}

internal fun RahYabViewModel.updateAllOutdatedPacksImpl() {
    val s = settings.value
    val net = networkState.value
    if (s.wifiOnlyLargeDownloads && net.isMobileData && !net.isWifi) {
        _snackbarMessage.value = stringsFor(s.language).mapUpdateWifiOnlyNotice
        return
    }
    packManager.updateAllOutdatedPacks(s.effectivePackBaseUrl)
}

internal fun RahYabViewModel.importLocalPackFileImpl(stateCode: String, uri: Uri, displayName: String?) {
    viewModelScope.launch {
        val str = stringsFor(settings.value.language)
        val res = packManager.importLocalUri(stateCode, uri, displayName)
        _snackbarMessage.value = res.fold(
            onSuccess = { "${str.importSuccess}: $it" },
            onFailure = { "${str.importFailed}: ${it.message.orEmpty()}" }
        )
    }
}

internal fun RahYabViewModel.refreshWeatherAlertsIfEligibleImpl(explicitRoute: OfflineRoute? = null) {
    val s = settings.value
    if (!s.onlineFeaturesEnabled || !s.onlineWeatherAlertsEnabled) return
    if (!networkState.value.isOnline) return
    weatherCheckJob?.cancel()
    weatherCheckJob = viewModelScope.launch {
        val pos = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
        val route = explicitRoute
            ?: _navigationState.value.route
            ?: _candidateRoutes.value.getOrNull(_selectedRouteIndex.value)
        val result = weatherAlertsClient.fetchAlertsForPositionAndRoute(
            userLat = pos.lat,
            userLon = pos.lon,
            activeRoute = route
        )
        result.onSuccess { alerts ->
            _cachedWeatherAlerts.value = alerts
            val detail = if (alerts.isEmpty()) "" else alerts.first().title
            updateWeatherSourceStatus(OnlineSourceState.ACTIVE, detail)
            val firstNew = alerts.firstOrNull { it.id !in spokenWeatherAlertIds }
            if (firstNew != null) {
                alerts.forEach { spokenWeatherAlertIds.add(it.id) }
                if (s.voiceGuidanceEnabled) {
                    voiceManager.speakWeatherAlert(firstNew.title, s.language)
                }
            }
        }.onFailure { err ->
            updateWeatherSourceStatus(OnlineSourceState.ERROR, err.message.orEmpty().take(60))
        }
    }
}

internal fun RahYabViewModel.updateWeatherSourceStatusImpl(state: OnlineSourceState, detail: String = "") {
    val current = _rawOnlineSources.value
    val prev = current.weatherAlerts
    _rawOnlineSources.value = current.copy(
        weatherAlerts = OnlineSourceStatus(
            state = state,
            lastSuccessEpochMs = if (state == OnlineSourceState.ACTIVE) System.currentTimeMillis() else prev.lastSuccessEpochMs,
            detail = detail
        )
    )
}

internal fun RahYabViewModel.updateOnlineSearchSourceStatusImpl(state: OnlineSourceState, detail: String = "") {
    val current = _rawOnlineSources.value
    val prev = current.onlineSearch
    _rawOnlineSources.value = current.copy(
        onlineSearch = OnlineSourceStatus(
            state = state,
            lastSuccessEpochMs = if (state == OnlineSourceState.ACTIVE) System.currentTimeMillis() else prev.lastSuccessEpochMs,
            detail = detail
        )
    )
}

internal fun RahYabViewModel.updateMapUpdatesSourceStatusImpl(state: OnlineSourceState, detail: String = "") {
    val current = _rawOnlineSources.value
    val prev = current.mapUpdates
    _rawOnlineSources.value = current.copy(
        mapUpdates = OnlineSourceStatus(
            state = state,
            lastSuccessEpochMs = if (state == OnlineSourceState.ACTIVE) System.currentTimeMillis() else prev.lastSuccessEpochMs,
            detail = detail
        )
    )
}
