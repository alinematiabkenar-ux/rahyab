package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.data.local.PackInstallStatus
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.model.UsStateInfo
import com.example.data.model.UsStatesCatalog
import com.example.data.online.OnlineSourceState
import com.example.data.search.PlaceSearchResult
import com.example.i18n.stringsFor
import com.example.navigation.ExternalMapIntentParser
import com.example.navigation.ParsedExternalMapTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

internal fun RahYabViewModel.onSearchQueryChangedImpl(query: String) {
    _searchQuery.value = query
    searchJob?.cancel()
    photonFallbackJob?.cancel()
    if (query.isBlank()) {
        _searchResults.value = emptyList()
        _isSearching.value = false
        return
    }
    searchJob = viewModelScope.launch {
        delay(180L)
        executeSearchImpl(query, immediateOnlineFallback = false)
    }
}

internal fun RahYabViewModel.executeSearchNowImpl(query: String) {
    _searchQuery.value = query
    searchJob?.cancel()
    photonFallbackJob?.cancel()
    searchJob = viewModelScope.launch {
        executeSearchImpl(query, immediateOnlineFallback = true)
    }
}

private suspend fun RahYabViewModel.executeSearchImpl(query: String, immediateOnlineFallback: Boolean = false) {
    _isSearching.value = true
    val ref = getEffectiveReferenceLocation()
    var offlineResults: List<PlaceSearchResult> = emptyList()
    try {
        offlineResults = offlineSearch.search(query, ref.lat, ref.lon, limit = 30)
        val useDevOnline = settings.value.devOnlineOregonMode && packManager.getInstalledPlacesDatabases().isEmpty()

        if (offlineResults.isNotEmpty() || !useDevOnline) {
            _searchResults.value = offlineResults
        } else {
            val onlineResults = devOnlineSearch.searchOregonOnline(query, ref.lat, ref.lon, limit = 25)
            _searchResults.value = onlineResults
            return
        }
    } finally {
        _isSearching.value = false
    }

    // Optional Komoot Photon online fallback when offline search has no good result
    val s = settings.value
    val hasGoodOfflineMatch = offlineResults.isNotEmpty() &&
        (offlineResults.first().relevanceScore >= 55.0 || offlineResults.size >= 3)
    if (!hasGoodOfflineMatch &&
        query.trim().length >= 3 &&
        s.onlineFeaturesEnabled &&
        s.onlineSearchFallbackEnabled &&
        networkState.value.isOnline
    ) {
        val capturedQuery = query
        val capturedOffline = offlineResults
        photonFallbackJob?.cancel()
        photonFallbackJob = viewModelScope.launch {
            if (!immediateOnlineFallback) {
                delay(1_000L) // Wait 1 second after typing stops
            }
            if (_searchQuery.value != capturedQuery) return@launch
            if (!networkState.value.isOnline) return@launch
            val res = photonSearchClient.search(
                query = capturedQuery,
                biasLat = ref.lat,
                biasLon = ref.lon,
                limit = 10
            )
            if (_searchQuery.value != capturedQuery) return@launch
            res.onSuccess { onlineList ->
                updateOnlineSearchSourceStatus(
                    OnlineSourceState.ACTIVE,
                    if (onlineList.isEmpty()) "" else "${onlineList.size} results"
                )
                if (onlineList.isNotEmpty()) {
                    val offlineNames = capturedOffline.map {
                        it.name.lowercase(Locale.US)
                    }.toSet()
                    val filteredOnline = onlineList.filterNot {
                        it.name.lowercase(Locale.US) in offlineNames
                    }
                    if (filteredOnline.isNotEmpty()) {
                        _searchResults.value = capturedOffline + filteredOnline
                    }
                }
            }.onFailure { err ->
                updateOnlineSearchSourceStatus(OnlineSourceState.ERROR, err.message.orEmpty().take(60))
            }
        }
    }
}

internal fun RahYabViewModel.dropPinOnMapImpl(lat: Double, lon: Double) {
    val str = stringsFor(settings.value.language)
    val stateCode = UsStatesCatalog.findBestStateForLocation(lat, lon)?.code.orEmpty()
    val coordsText = String.format(Locale.US, "%.5f, %.5f", lat, lon)
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

internal fun RahYabViewModel.selectPlaceImpl(place: PlaceSearchResult) {
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
    _selectedTravelMode.value = settings.value.lastTravelMode
    calculateAllTravelModePreviewsImpl()
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

internal fun RahYabViewModel.clearSelectedPlaceImpl() {
    multiModePreviewJob?.cancel()
    _modePreviewSummaries.value = emptyMap()
    _selectedPlace.value = null
    _candidateRoutes.value = emptyList()
    _routeError.value = null
    _missingStatesForRoute.value = emptyList()
    if (!_navigationState.value.isNavigating) {
        recenterCamera()
    }
}

internal fun RahYabViewModel.handleExternalMapIntentImpl(intent: Intent?) {
    val target = ExternalMapIntentParser.parseIntent(intent)
    if (target is ParsedExternalMapTarget.None) return

    _openDriveTabEvent.value = System.currentTimeMillis()
    externalIntentJob?.cancel()
    externalIntentJob = viewModelScope.launch {
        resolveParsedExternalTarget(target)
    }
}

internal fun RahYabViewModel.selectExternalMatchChoiceImpl(place: PlaceSearchResult) {
    _externalLinkMatchChoices.value = emptyList()
    viewModelScope.launch {
        selectPlaceWithCoverageCheck(place)
    }
}

internal fun RahYabViewModel.dismissExternalMatchChoicesImpl() {
    _externalLinkMatchChoices.value = emptyList()
}

private suspend fun RahYabViewModel.resolveParsedExternalTarget(target: ParsedExternalMapTarget) {
    val str = stringsFor(settings.value.language)
    when (target) {
        is ParsedExternalMapTarget.None -> Unit
        is ParsedExternalMapTarget.Coordinates -> {
            showExternalCoordinates(target.lat, target.lon, target.label)
        }
        is ParsedExternalMapTarget.SearchQuery -> {
            searchExternalQueryOffline(target.query)
        }
        is ParsedExternalMapTarget.ShortLink -> {
            val isOnline = networkStatusMonitor.networkState.value.isOnline
            if (!isOnline) {
                val fallback = target.fallbackText?.trim().orEmpty()
                if (fallback.isNotBlank()) {
                    searchExternalQueryOffline(fallback, offlineShortLinkHint = true)
                } else {
                    _snackbarMessage.value = str.externalLinkOfflineShortUrl
                }
                return
            }
            _snackbarMessage.value = str.externalLinkExpanding
            val expandedUrl = ExternalMapIntentParser.expandShortMapLink(target.url)
            if (!expandedUrl.isNullOrBlank()) {
                val expandedUri = runCatching { Uri.parse(expandedUrl) }.getOrNull()
                val parsedExpanded = expandedUri?.let {
                    ExternalMapIntentParser.parseUri(it, fallbackText = target.fallbackText)
                } ?: ParsedExternalMapTarget.None
                if (parsedExpanded !is ParsedExternalMapTarget.None &&
                    parsedExpanded !is ParsedExternalMapTarget.ShortLink
                ) {
                    resolveParsedExternalTarget(parsedExpanded)
                    return
                }
            }
            val fallback = target.fallbackText?.trim().orEmpty()
            if (fallback.isNotBlank()) {
                searchExternalQueryOffline(fallback)
            } else {
                _snackbarMessage.value = str.externalLinkOfflineShortUrl
            }
        }
    }
}

private suspend fun RahYabViewModel.showExternalCoordinates(lat: Double, lon: Double, label: String?) {
    val str = stringsFor(settings.value.language)
    val bestState = UsStatesCatalog.findBestStateForLocation(lat, lon)
    val coordsText = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    val place = PlaceSearchResult(
        id = "ext_${System.currentTimeMillis()}",
        name = label?.takeIf { it.isNotBlank() } ?: str.externalLinkLabelDefault,
        brand = "",
        category = "shared_link",
        address = coordsText,
        city = "",
        stateCode = bestState?.code.orEmpty(),
        postcode = "",
        lat = lat,
        lon = lon,
        distanceMeters = 0.0,
        relevanceScore = 100.0,
        source = "external_link"
    )
    selectPlaceWithCoverageCheck(place)
}

private suspend fun RahYabViewModel.searchExternalQueryOffline(
    query: String,
    offlineShortLinkHint: Boolean = false
) {
    val str = stringsFor(settings.value.language)
    val ref = getEffectiveReferenceLocation()
    val results = offlineSearch.search(query, ref.lat, ref.lon, limit = 8)

    if (results.isEmpty()) {
        val mentionedState = ExternalMapIntentParser.detectMentionedUsState(query)
        val installedCodes = getInstalledStateCodeSet()
        if (mentionedState != null && mentionedState.code !in installedCodes &&
            !UsStatesCatalog.isPointCoveredByInstalledPacks(mentionedState.centerLat, mentionedState.centerLon, installedCodes)
        ) {
            val placeholder = PlaceSearchResult(
                id = "ext_missing_${mentionedState.code}",
                name = query,
                brand = "",
                category = "shared_link",
                address = mentionedState.nameEn,
                city = "",
                stateCode = mentionedState.code,
                postcode = "",
                lat = mentionedState.centerLat,
                lon = mentionedState.centerLon,
                distanceMeters = 0.0,
                relevanceScore = 0.0,
                source = "external_link"
            )
            selectPlace(placeholder)
            _missingStatesForRoute.value = listOf(mentionedState)
            _snackbarMessage.value = str.externalLinkOutsideInstalledStates
        } else {
            _searchQuery.value = query
            _snackbarMessage.value = if (offlineShortLinkHint) {
                str.externalLinkOfflineShortUrl
            } else {
                "${str.externalLinkNoMatch}: $query"
            }
        }
        return
    }

    val isSingleBestMatch = results.size == 1 ||
        (results[0].relevanceScore - results[1].relevanceScore >= 18.0)
    if (isSingleBestMatch) {
        _externalLinkMatchChoices.value = emptyList()
        selectPlaceWithCoverageCheck(results.first())
    } else {
        _externalLinkMatchChoices.value = results.take(6)
    }
}

private suspend fun RahYabViewModel.selectPlaceWithCoverageCheck(place: PlaceSearchResult) {
    val str = stringsFor(settings.value.language)
    if (_navigationState.value.isNavigating) {
        stopNavigation()
    }
    selectPlace(place)
    val installedCodes = getInstalledStateCodeSet()
    if (!UsStatesCatalog.isPointCoveredByInstalledPacks(place.lat, place.lon, installedCodes)) {
        val missing = findMissingStatePackForPoint(place.lat, place.lon, installedCodes)
        if (missing != null) {
            _missingStatesForRoute.value = listOf(missing)
        }
        _snackbarMessage.value = str.externalLinkOutsideInstalledStates
    }
}

private suspend fun RahYabViewModel.getInstalledStateCodeSet(): Set<String> {
    val dbInstalled = runCatching {
        installedPackDao.getAllOnce()
            .filter { it.status == PackInstallStatus.INSTALLED.name }
            .map { it.stateCode }
    }.getOrDefault(emptyList())
    val pmtilesInstalled = packManager.getInstalledPmtilesFiles().map { it.first }
    return (dbInstalled + pmtilesInstalled).toSet()
}

private fun findMissingStatePackForPoint(
    lat: Double,
    lon: Double,
    installedCodes: Set<String>
): UsStateInfo? {
    val state = UsStatesCatalog.findBestStateForLocation(lat, lon) ?: return null
    val regional = UsStatesCatalog.regionsByParent[state.code]
    if (regional != null) {
        val matchingRegion = regional.firstOrNull {
            lat in it.minLat..it.maxLat && lon in it.minLon..it.maxLon
        }
        if (matchingRegion != null && matchingRegion.code !in installedCodes) {
            return matchingRegion
        }
    }
    return state.takeIf { it.code !in installedCodes }
}

internal fun RahYabViewModel.savePlaceAsImpl(place: PlaceSearchResult, type: SavedPlaceType) {
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

internal fun RahYabViewModel.deleteSavedPlaceImpl(id: Long) {
    viewModelScope.launch {
        savedPlaceDao.deleteById(id)
    }
}
