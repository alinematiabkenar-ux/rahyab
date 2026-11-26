package com.example.map

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.navigation.LaneDirection
import com.example.navigation.LaneGuidanceValidator
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.TravelMode
import com.example.navigation.ValhallaResponseParser

enum class LaneHighlightColor(val code: String, val hex: String) {
    RED("red", "#E53935"),
    BLUE("blue", "#1E88E5"),
    GREEN("green", "#43A047");

    companion object {
        fun fromCode(code: String?): LaneHighlightColor =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: BLUE
    }
}

data class LaneStripFeature(
    val laneIndex: Int,
    val isTarget: Boolean,
    val arrowDirection: LaneDirection,
    val points: List<LatLon>,
    val offsetMeters: Double
)

data class LaneSeparatorFeature(
    val points: List<LatLon>,
    val offsetMeters: Double
)

data class LaneArrowMarker(
    val laneIndex: Int,
    val position: LatLon,
    val bearingDegrees: Float,
    val direction: LaneDirection,
    val isLargeArrow: Boolean = false
)

data class LaneOverlayData(
    val maneuverIndex: Int,
    val isOneWay: Boolean,
    val lanes: List<LaneStripFeature>,
    val separators: List<LaneSeparatorFeature>,
    val arrowMarkers: List<LaneArrowMarker>,
    val outgoingRampPath: List<LatLon> = emptyList()
)

/**
 * LN1: Pure Kotlin geometric builder for highlighting the correct road lanes directly on the map.
 *
 * Rules:
 * - Active ONLY in [TravelMode.DRIVE] when not off-route and lane data has >= 2 lanes with at
 *   least one non-valid lane (if all lanes are valid or fewer than 2 lanes exist, returns null).
 * - Extracts route geometry from 500 m before the maneuver (or route start if closer) to 30 m after.
 * - Offsets each lane in local east/north meters (3.6 m lane width, left to right):
 *   - One-way roads (`forward`/`backward`, or highway fallback): `offset_i = (i - (N - 1) / 2.0) * 3.6`
 *   - Two-way roads (`both`, or non-highway fallback): `offset_i = (i + 0.5) * 3.6`
 * - Builds white dashed separator polylines between adjacent lanes and places a lane arrow marker
 *   inside each target lane ~40 m before the maneuver.
 */
object LaneOverlayBuilder {

    const val LANE_WIDTH_METERS = 3.6
    const val BEFORE_MANEUVER_METERS = 500.0
    const val MAX_FOCUS_START_METERS = 1600.0
    const val AFTER_MANEUVER_METERS = 30.0
    const val HIGHWAY_SPLIT_AFTER_METERS = 12.0
    const val ARROW_BEFORE_MANEUVER_METERS = 40.0
    const val FOCUS_ARROW_INTERVAL_METERS = 130.0

    fun computeLaneOffsetsMeters(laneCount: Int, isOneWay: Boolean): List<Double> {
        if (laneCount < 2) return emptyList()
        return List(laneCount) { i ->
            if (isOneWay) {
                (i - (laneCount - 1) / 2.0) * LANE_WIDTH_METERS
            } else {
                (i + 0.5) * LANE_WIDTH_METERS
            }
        }
    }

    fun computeSeparatorOffsetsMeters(laneCount: Int, isOneWay: Boolean): List<Double> {
        val offsets = computeLaneOffsetsMeters(laneCount, isOneWay)
        if (offsets.size < 2) return emptyList()
        return List(offsets.size - 1) { i -> (offsets[i] + offsets[i + 1]) * 0.5 }
    }

    fun isOneWayForManeuver(route: OfflineRoute, maneuver: RouteManeuver): Boolean {
        val pivot = maneuver.beginShapeIndex.coerceIn(0, (route.shape.size - 1).coerceAtLeast(0))
        // Check approach edge just before pivot first, then pivot and small neighborhood
        val probeOrder = listOf(pivot - 1, pivot, pivot - 2, pivot + 1, pivot - 3)
        for (idx in probeOrder) {
            route.oneWayByShapeIndex[idx]?.let { return it }
        }
        return maneuver.isHighwayExitOrSplit || maneuver.isHighwayRoad
    }

    fun hasSupportedRoadLaneGeometry(route: OfflineRoute, maneuver: RouteManeuver): Boolean {
        if (!route.hasRoadLaneGeometry || !maneuver.hasRoadLaneGeometry) return false
        if (maneuver.laneSource == com.example.navigation.LaneSource.CARRIED_ALIGNED &&
            maneuver.carriedLaneHadInterveningIntersection
        ) {
            return false
        }
        val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, route.shape.lastIndex)
        if (pivotIdx <= 0) return false
        val approachLen = LaneOverlayGeometryHelper.distanceAlongShape(route.shape, 0, pivotIdx)
        return approachLen >= 10.0
    }

    fun build(
        route: OfflineRoute?,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        distanceToNextManeuverMeters: Double,
        travelMode: TravelMode,
        isOffRoute: Boolean = false,
        enabled: Boolean = true,
        startDistanceMeters: Double = BEFORE_MANEUVER_METERS,
        isFocusMode: Boolean = false,
        focusManeuverIndex: Int = -1
    ): LaneOverlayData? {
        if (!enabled || isOffRoute || travelMode != TravelMode.DRIVE) return null
        if (route == null || route.shape.size < 2) return null

        val effectiveStart = startDistanceMeters.coerceIn(100.0, MAX_FOCUS_START_METERS)
        val maneuver = selectEligibleManeuver(
            route = route,
            currentManeuverIndex = currentManeuverIndex,
            snappedShapeIndex = snappedShapeIndex,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            startDistanceMeters = effectiveStart,
            isFocusMode = isFocusMode,
            focusManeuverIndex = focusManeuverIndex
        ) ?: return null

        val lanes = maneuver.lanes
        if (lanes.size < 2) return null
        if (!hasSupportedRoadLaneGeometry(route, maneuver)) return null

        // LN4: If every lane is valid, show no overlay
        val allValid = lanes.all { it.isValid || it.isRecommended || it.isActive || it.highlightTier != LaneHighlightTier.INACTIVE }
        if (allValid) return null

        // Match LaneGuidanceDecision.primaryTargetIndices: highlight ONLY preferred (ACTIVE) lanes
        // when present; never paint merely VALID lanes primary blue alongside an ACTIVE lane.
        val hasPreferredActive = lanes.any { it.isActive || it.highlightTier == LaneHighlightTier.ACTIVE }
        val targetFlags = lanes.map { lane ->
            if (hasPreferredActive) {
                lane.isActive || lane.highlightTier == LaneHighlightTier.ACTIVE
            } else {
                lane.isValid || lane.isRecommended || lane.highlightTier == LaneHighlightTier.VALID
            }
        }
        if (targetFlags.none { it } || targetFlags.all { it }) return null

        val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, route.shape.lastIndex)
        val isHighwaySplit = maneuver.isHighwayExitOrSplit || maneuver.isHighwayRoad
        val approachDrawMeters = LaneGuidanceValidator.computeApproachDrawDistanceMeters(
            shape = route.shape,
            edgeLaneCountsByShapeIndex = route.edgeLaneCountsByShapeIndex,
            pivotIdx = pivotIdx,
            maneuverLaneCount = lanes.size,
            isHighway = isHighwaySplit,
            maxStartDistanceMeters = effectiveStart
        )
        if (approachDrawMeters < 5.0) return null
        val beforePts = LaneOverlayGeometryHelper.collectBackward(route.shape, pivotIdx, approachDrawMeters)
        // End incoming multi-lane strips cleanly at/near highway splits so unverified incoming
        // lane stripes do not bleed onto the outgoing ramp; verified ramp guidance continues via outgoingRampPath.
        val afterMeters = if (isHighwaySplit) HIGHWAY_SPLIT_AFTER_METERS else AFTER_MANEUVER_METERS
        val afterPts = LaneOverlayGeometryHelper.collectForward(route.shape, pivotIdx, afterMeters)
        val centerLine = ArrayList<LatLon>(beforePts.size + afterPts.size + 1).apply {
            addAll(beforePts)
            add(route.shape[pivotIdx])
            addAll(afterPts)
        }
        if (centerLine.size < 2) return null

        val pivotLocalIndex = beforePts.size
        val isOneWay = isOneWayForManeuver(route, maneuver)
        val laneOffsets = computeLaneOffsetsMeters(lanes.size, isOneWay)
        val sepOffsets = computeSeparatorOffsetsMeters(lanes.size, isOneWay)
        val origin = route.shape[pivotIdx]

        val laneFeatures = lanes.mapIndexed { i, info ->
            val offsetM = laneOffsets[i]
            val pts = LaneOverlayGeometryHelper.offsetPolylineMeters(centerLine, origin, offsetM)
            LaneStripFeature(
                laneIndex = i,
                isTarget = targetFlags[i],
                arrowDirection = primaryArrowDirection(info),
                points = pts,
                offsetMeters = offsetM
            )
        }

        val separators = sepOffsets.map { sepOffset ->
            LaneSeparatorFeature(
                points = LaneOverlayGeometryHelper.offsetPolylineMeters(centerLine, origin, sepOffset),
                offsetMeters = sepOffset
            )
        }

        val arrowMarkers = laneFeatures.filter { it.isTarget }.flatMap { strip ->
            buildArrowMarkersForStrip(strip, pivotLocalIndex, isFocusMode)
        }

        val outgoingRampPath = if (isFocusMode && isHighwaySplit) {
            LaneOverlayGeometryHelper.buildOutgoingRampPath(
                shape = route.shape,
                pivotIdx = pivotIdx,
                snappedShapeIndex = snappedShapeIndex
            )
        } else {
            emptyList()
        }

        return LaneOverlayData(
            maneuverIndex = maneuver.index,
            isOneWay = isOneWay,
            lanes = laneFeatures,
            separators = separators,
            arrowMarkers = arrowMarkers,
            outgoingRampPath = outgoingRampPath
        )
    }

    private fun selectEligibleManeuver(
        route: OfflineRoute,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        distanceToNextManeuverMeters: Double,
        startDistanceMeters: Double,
        isFocusMode: Boolean,
        focusManeuverIndex: Int
    ): RouteManeuver? {
        // 0. While Turn Focus is actively tracking a specific maneuver, stay synchronized to it
        //    through red-light stops and until Turn Focus releases on completion
        if (isFocusMode && focusManeuverIndex >= 0) {
            val focusM = route.maneuvers.firstOrNull { it.index == focusManeuverIndex }
            if (focusM != null) {
                return if (focusM.lanes.size >= 2) focusM else null
            }
        }
        // 1. Check if we are still within 30 m past the previous maneuver (keep overlay until 30 m after)
        if (currentManeuverIndex > 0) {
            val prev = route.maneuvers.getOrNull(currentManeuverIndex - 1)
            if (prev != null && prev.lanes.size >= 2 && snappedShapeIndex >= prev.beginShapeIndex) {
                val distPast = LaneOverlayGeometryHelper.distanceAlongShape(route.shape, prev.beginShapeIndex, snappedShapeIndex)
                if (distPast <= AFTER_MANEUVER_METERS) {
                    return prev
                }
            }
        }
        // 2. Active upcoming maneuver within startDistanceMeters before (or still within 30 m after its pivot)
        val cur = route.maneuvers.getOrNull(currentManeuverIndex) ?: return null
        if (cur.lanes.size < 2) return null
        if (snappedShapeIndex > cur.beginShapeIndex) {
            val distPast = LaneOverlayGeometryHelper.distanceAlongShape(route.shape, cur.beginShapeIndex, snappedShapeIndex)
            if (distPast > AFTER_MANEUVER_METERS) return null
        } else if (distanceToNextManeuverMeters > startDistanceMeters) {
            return null
        }
        return cur
    }

    private fun primaryArrowDirection(info: LaneInfo): LaneDirection =
        info.activeDirections.firstOrNull()
            ?: info.directions.firstOrNull()
            ?: LaneDirection.THROUGH

    private fun buildArrowMarkersForStrip(
        strip: LaneStripFeature,
        pivotLocalIndex: Int,
        isFocusMode: Boolean
    ): List<LaneArrowMarker> {
        val primary = buildArrowMarkerAtDistanceBeforePivot(
            strip = strip,
            pivotLocalIndex = pivotLocalIndex,
            distanceBeforePivotMeters = ARROW_BEFORE_MANEUVER_METERS,
            isLargeArrow = isFocusMode,
            fallbackToMidpoint = true
        ) ?: return emptyList()
        if (!isFocusMode) return listOf(primary)
        val out = ArrayList<LaneArrowMarker>()
        out.add(primary)
        var d = ARROW_BEFORE_MANEUVER_METERS + FOCUS_ARROW_INTERVAL_METERS
        while (d <= MAX_FOCUS_START_METERS) {
            val marker = buildArrowMarkerAtDistanceBeforePivot(
                strip = strip,
                pivotLocalIndex = pivotLocalIndex,
                distanceBeforePivotMeters = d,
                isLargeArrow = false,
                fallbackToMidpoint = false
            ) ?: break
            out.add(marker)
            d += FOCUS_ARROW_INTERVAL_METERS
        }
        return out
    }

    private fun buildArrowMarkerAtDistanceBeforePivot(
        strip: LaneStripFeature,
        pivotLocalIndex: Int,
        distanceBeforePivotMeters: Double,
        isLargeArrow: Boolean,
        fallbackToMidpoint: Boolean
    ): LaneArrowMarker? {
        val pts = strip.points
        if (pts.size < 2) return null
        val safePivot = pivotLocalIndex.coerceIn(1, pts.lastIndex)
        var acc = 0.0
        var i = safePivot
        while (i > 0) {
            val cur = pts[i]
            val prev = pts[i - 1]
            val seg = OfflinePlaceSearchEngine.haversineMeters(cur.lat, cur.lon, prev.lat, prev.lon)
            if (seg > 0.05) {
                if (acc + seg >= distanceBeforePivotMeters) {
                    val frac = ((distanceBeforePivotMeters - acc) / seg).coerceIn(0.0, 1.0)
                    val pos = LaneOverlayGeometryHelper.interpolate(cur, prev, frac)
                    val bearing = ValhallaResponseParser.bearingDegrees(prev, cur).toFloat()
                    return LaneArrowMarker(strip.laneIndex, pos, bearing, strip.arrowDirection, isLargeArrow)
                }
                acc += seg
            }
            i--
        }
        if (!fallbackToMidpoint) return null
        val pos = LaneOverlayGeometryHelper.interpolate(pts[0], pts[safePivot], 0.5)
        val bearing = ValhallaResponseParser.bearingDegrees(pts[0], pts[safePivot]).toFloat()
        return LaneArrowMarker(strip.laneIndex, pos, bearing, strip.arrowDirection, isLargeArrow)
    }

    internal fun offsetPolylineMeters(
        points: List<LatLon>,
        origin: LatLon,
        rightOffsetMeters: Double
    ): List<LatLon> = LaneOverlayGeometryHelper.offsetPolylineMeters(points, origin, rightOffsetMeters)
}
