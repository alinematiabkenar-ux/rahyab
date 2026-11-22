package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.SpeedBumpAlertEvaluator
import kotlinx.coroutines.launch

/**
 * Speed bump map-query, simulation seeding, and alert evaluation extensions for [RahYabViewModel].
 */
internal fun RahYabViewModel.refreshNearbySpeedBumpsImpl(
    lat: Double,
    lon: Double,
    force: Boolean = false
) {
    val last = lastSpeedBumpFetchLoc
    if (!force && last != null &&
        OfflinePlaceSearchEngine.haversineMeters(last.lat, last.lon, lat, lon) < 1_200.0
    ) {
        return
    }
    lastSpeedBumpFetchLoc = LatLon(lat, lon)
    speedBumpFetchJob?.cancel()
    speedBumpFetchJob = viewModelScope.launch {
        val dbBumps = offlineSearch.querySpeedBumpsInBounds(
            minLat = lat - 0.045,
            maxLat = lat + 0.045,
            minLon = lon - 0.055,
            maxLon = lon + 0.055
        )
        _visibleSpeedBumps.value = mergeUniqueSpeedBumps(dbBumps, syntheticSimSpeedBumps)
    }
}

internal fun RahYabViewModel.seedSimulationSpeedBumpsImpl(shape: List<LatLon>) {
    syntheticSimSpeedBumps = SpeedBumpAlertEvaluator.generateSyntheticSimulationBumps(shape)
    _visibleSpeedBumps.value = mergeUniqueSpeedBumps(_visibleSpeedBumps.value, syntheticSimSpeedBumps)
}

internal fun RahYabViewModel.clearSimulationSpeedBumpsImpl() {
    if (syntheticSimSpeedBumps.isNotEmpty()) {
        val simIds = syntheticSimSpeedBumps.mapNotNull { it.osmId }.toSet()
        syntheticSimSpeedBumps = emptyList()
        _visibleSpeedBumps.value = _visibleSpeedBumps.value.filterNot { it.osmId in simIds }
    }
}

internal fun RahYabViewModel.evaluateSpeedBumpForNavigationImpl(
    state: ActiveNavigationState,
    fallbackLoc: LatLon?,
    speedMps: Float
) {
    val s = settings.value
    val navPos = state.snappedLocation ?: fallbackLoc ?: return
    if (!state.isNavigating || state.hasArrived || !s.alertSpeedBumpEnabled) return
    refreshNearbySpeedBumpsImpl(navPos.lat, navPos.lon)

    val allBumps = mergeUniqueSpeedBumps(_visibleSpeedBumps.value, syntheticSimSpeedBumps)
    val (bannerAlert, speakAlert) = speedBumpEvaluator.evaluate(
        userLat = navPos.lat,
        userLon = navPos.lon,
        headingDegrees = state.snappedBearing,
        speedMps = speedMps,
        bumps = allBumps,
        enabled = s.alertSpeedBumpEnabled,
        isWalkingMode = false,
        routeShape = state.route?.shape,
        snappedShapeIndex = state.snappedShapeIndex
    )
    if (bannerAlert != null && _navigationState.value.activeAheadAlert == null) {
        _navigationState.value = _navigationState.value.copy(activeAheadAlert = bannerAlert)
    }
    if (speakAlert != null) {
        voiceManager.speakQueuedAlert(speakAlert, s.language, s.distanceUnit)
    }
}

internal fun RahYabViewModel.evaluateSpeedBumpForFreeDriveImpl(fix: GpsFix) {
    val s = settings.value
    refreshNearbySpeedBumpsImpl(fix.lat, fix.lon)
    if (!s.alertSpeedBumpEnabled) return
    val heading = if (fix.hasBearing && fix.speedMps > 1.3f) fix.bearing else _userHeadingDegrees.value
    val (bannerAlert, speakAlert) = speedBumpEvaluator.evaluate(
        userLat = fix.lat,
        userLon = fix.lon,
        headingDegrees = heading,
        speedMps = fix.speedMps,
        bumps = _visibleSpeedBumps.value,
        enabled = s.alertSpeedBumpEnabled,
        isWalkingMode = false
    )
    if (bannerAlert != null && _freeDriveAheadAlert.value == null) {
        _freeDriveAheadAlert.value = bannerAlert
    }
    if (speakAlert != null) {
        voiceManager.speakQueuedAlert(speakAlert, s.language, s.distanceUnit)
    }
}

internal fun mergeUniqueSpeedBumps(
    primary: List<RoadHazardPoint>,
    secondary: List<RoadHazardPoint>
): List<RoadHazardPoint> {
    if (secondary.isEmpty()) return primary
    if (primary.isEmpty()) return secondary
    val seen = HashSet<String>(primary.size + secondary.size)
    val out = ArrayList<RoadHazardPoint>(primary.size + secondary.size)
    for (p in primary) {
        val k = p.osmId?.takeIf { it.isNotBlank() } ?: "${p.lat}_${p.lon}"
        if (seen.add(k)) out.add(p)
    }
    for (p in secondary) {
        val k = p.osmId?.takeIf { it.isNotBlank() } ?: "${p.lat}_${p.lon}"
        if (seen.add(k)) out.add(p)
    }
    return out
}
