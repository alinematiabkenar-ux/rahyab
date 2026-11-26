package com.example.ui

import androidx.lifecycle.viewModelScope
import com.example.data.local.WrongLaneReportEntity
import com.example.data.model.UsStatesCatalog
import com.example.i18n.stringsFor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneGuidanceValidator
import com.example.navigation.TravelMode
import com.example.navigation.WrongLaneSuppressionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * L4/L5: Evaluates live lane guidance confidence for the active maneuver and manages
 * user-reported "Wrong lane" flags persisted in Room.
 */
internal fun RahYabViewModel.evaluateLaneConfidenceImpl(
    state: ActiveNavigationState
): ActiveNavigationState {
    if (!state.isNavigating || state.effectiveTravelMode != TravelMode.DRIVE) {
        return state.copy(
            laneConfidence = LaneConfidence.HIGH,
            laneConfidenceReasons = emptyList(),
            laneSideHint = null,
            laneDecision = null
        )
    }
    val route = state.route ?: return state
    val maneuver = state.currentManeuver ?: return state
    if (maneuver.lanes.isEmpty()) {
        return state.copy(
            laneConfidence = LaneConfidence.HIGH,
            laneConfidenceReasons = emptyList(),
            laneSideHint = null,
            laneDecision = null
        )
    }

    val currentMapVersion = resolveCurrentMapVersionImpl(maneuver.lat, maneuver.lon)
    val reports = wrongLaneReports.value.map { r ->
        WrongLaneSuppressionRecord(
            lat = r.lat,
            lon = r.lon,
            bearingDegrees = r.bearingDegrees,
            mapVersion = r.mapVersion
        )
    }
    val edgeLaneCount = LaneGuidanceValidator.findApproachEdgeLaneCount(route, maneuver)
    val isReportedWrong = LaneGuidanceValidator.matchesWrongLaneSuppression(
        lat = maneuver.lat,
        lon = maneuver.lon,
        bearingDegrees = state.snappedBearing,
        currentMapVersion = currentMapVersion,
        reports = reports
    )

    val decision = com.example.navigation.LaneGuidanceDecision.evaluate(
        maneuver = maneuver,
        edgeLaneCountBeforeManeuver = edgeLaneCount,
        hpmsThroughLanes = cachedHpmsThroughLanes,
        isReportedWrongByUser = isReportedWrong
    )

    return state.copy(
        laneConfidence = decision.confidence,
        laneConfidenceReasons = decision.reasons,
        laneSideHint = decision.suggestedSide,
        laneDecision = decision
    )
}

internal fun RahYabViewModel.resolveCurrentMapVersionImpl(lat: Double, lon: Double): String {
    val stateCode = UsStatesCatalog.findBestStateForLocation(lat, lon)?.code
    val matchingPack = statePacks.value.firstOrNull {
        stateCode != null && it.stateCode.equals(stateCode, ignoreCase = true)
    } ?: statePacks.value.firstOrNull { it.installedBuildVersion.isNotBlank() || it.installedVersion.isNotBlank() }
    return matchingPack?.installedBuildVersion?.ifBlank { matchingPack.installedVersion }
        ?.ifBlank { "unknown" }
        ?: "unknown"
}

internal fun RahYabViewModel.reportWrongLaneImpl() {
    val cur = _navigationState.value
    val route = cur.route ?: return
    val maneuver = cur.currentManeuver ?: return
    val mapVersion = resolveCurrentMapVersionImpl(maneuver.lat, maneuver.lon)
    val edgeLaneCount = LaneGuidanceValidator.findApproachEdgeLaneCount(route, maneuver) ?: 0
    val wayId = LaneGuidanceValidator.findApproachEdgeWayId(route, maneuver) ?: 0L
    val targets = LaneGuidanceValidator.resolveTargetIndices(maneuver.lanes).joinToString(",")
    val report = WrongLaneReportEntity(
        timestampMs = System.currentTimeMillis(),
        lat = maneuver.lat,
        lon = maneuver.lon,
        bearingDegrees = cur.snappedBearing,
        mapVersion = mapVersion,
        maneuverType = maneuver.type.name,
        instructionText = maneuver.instructionEn,
        roadNames = maneuver.streetNames.joinToString(" / "),
        laneSource = maneuver.laneSource.name,
        confidence = cur.laneConfidence.name,
        confidenceReasons = cur.laneConfidenceReasons.joinToString(","),
        rawLaneJson = maneuver.rawLaneJson,
        computedTargets = targets,
        edgeLaneCount = edgeLaneCount,
        approachWayId = wayId
    )
    viewModelScope.launch(Dispatchers.IO) {
        wrongLaneReportDao.insert(report)
    }
    val suppressedDecision = com.example.navigation.LaneGuidanceDecision.evaluate(
        maneuver = maneuver,
        edgeLaneCountBeforeManeuver = edgeLaneCount.takeIf { it > 0 },
        hpmsThroughLanes = cachedHpmsThroughLanes,
        isReportedWrongByUser = true
    )
    _navigationState.value = cur.copy(
        laneConfidence = LaneConfidence.LOW,
        laneConfidenceReasons = suppressedDecision.reasons,
        laneSideHint = suppressedDecision.suggestedSide,
        laneDecision = suppressedDecision
    )
    _snackbarMessage.value = stringsFor(settings.value.language).wrongLaneSavedMessage
}

internal fun RahYabViewModel.clearWrongLaneReportsImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        wrongLaneReportDao.clearAll()
    }
    _snackbarMessage.value = stringsFor(settings.value.language).diagLaneReportsCleared
}

internal fun RahYabViewModel.refreshHpmsThroughLanesImpl(
    lat: Double,
    lon: Double,
    headingDegrees: Float?,
    isHighway: Boolean
) {
    if (!isHighway) {
        cachedHpmsThroughLanes = null
        return
    }
    val now = System.currentTimeMillis()
    val elapsed = now - lastHpmsLanesLookupMs
    val moved = if (lastHpmsLanesLat != null && lastHpmsLanesLon != null) {
        com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(
            lastHpmsLanesLat!!, lastHpmsLanesLon!!, lat, lon
        )
    } else Double.MAX_VALUE
    if (elapsed < 6_000L && moved < 100.0) return
    lastHpmsLanesLookupMs = now
    lastHpmsLanesLat = lat
    lastHpmsLanesLon = lon
    viewModelScope.launch(Dispatchers.IO) {
        cachedHpmsThroughLanes = runCatching {
            app.extrasDbReader.lookupHpmsThroughLanes(lat, lon, headingDegrees)
        }.getOrNull()
    }
}
