package com.example.map

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.navigation.LaneDirection
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.TravelMode
import com.example.navigation.ValhallaResponseParser
import kotlin.math.cos
import kotlin.math.hypot

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
    val arrowMarkers: List<LaneArrowMarker>
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
    const val ARROW_BEFORE_MANEUVER_METERS = 40.0
    const val FOCUS_ARROW_INTERVAL_METERS = 150.0
    private const val METERS_PER_DEG_LAT = 111_320.0

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

    fun build(
        route: OfflineRoute?,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        distanceToNextManeuverMeters: Double,
        travelMode: TravelMode,
        isOffRoute: Boolean = false,
        enabled: Boolean = true,
        startDistanceMeters: Double = BEFORE_MANEUVER_METERS,
        isFocusMode: Boolean = false
    ): LaneOverlayData? {
        if (!enabled || isOffRoute || travelMode != TravelMode.DRIVE) return null
        if (route == null || route.shape.size < 2) return null

        val effectiveStart = startDistanceMeters.coerceIn(100.0, MAX_FOCUS_START_METERS)
        val maneuver = selectEligibleManeuver(
            route = route,
            currentManeuverIndex = currentManeuverIndex,
            snappedShapeIndex = snappedShapeIndex,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            startDistanceMeters = effectiveStart
        ) ?: return null

        val lanes = maneuver.lanes
        if (lanes.size < 2) return null

        // LN4: If every lane is valid, show no overlay
        val allValid = lanes.all { it.isValid || it.isRecommended || it.isActive || it.highlightTier != LaneHighlightTier.INACTIVE }
        if (allValid) return null

        val hasRecommended = lanes.any { it.isRecommended || it.isActive || it.highlightTier == LaneHighlightTier.ACTIVE }
        val targetFlags = lanes.map { lane ->
            if (hasRecommended) {
                lane.isRecommended || lane.isActive || lane.highlightTier == LaneHighlightTier.ACTIVE
            } else {
                lane.isValid || lane.highlightTier == LaneHighlightTier.VALID
            }
        }
        if (targetFlags.none { it } || targetFlags.all { it }) return null

        val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, route.shape.lastIndex)
        val beforePts = collectBackward(route.shape, pivotIdx, effectiveStart)
        val afterPts = collectForward(route.shape, pivotIdx, AFTER_MANEUVER_METERS)
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
            val pts = offsetPolylineMeters(centerLine, origin, offsetM)
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
                points = offsetPolylineMeters(centerLine, origin, sepOffset),
                offsetMeters = sepOffset
            )
        }

        val arrowMarkers = laneFeatures.filter { it.isTarget }.flatMap { strip ->
            buildArrowMarkersForStrip(strip, pivotLocalIndex, isFocusMode)
        }

        return LaneOverlayData(
            maneuverIndex = maneuver.index,
            isOneWay = isOneWay,
            lanes = laneFeatures,
            separators = separators,
            arrowMarkers = arrowMarkers
        )
    }

    private fun selectEligibleManeuver(
        route: OfflineRoute,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        distanceToNextManeuverMeters: Double,
        startDistanceMeters: Double
    ): RouteManeuver? {
        // 1. Check if we are still within 30 m past the previous maneuver (keep overlay until 30 m after)
        if (currentManeuverIndex > 0) {
            val prev = route.maneuvers.getOrNull(currentManeuverIndex - 1)
            if (prev != null && prev.lanes.size >= 2 && snappedShapeIndex >= prev.beginShapeIndex) {
                val distPast = distanceAlongShape(route.shape, prev.beginShapeIndex, snappedShapeIndex)
                if (distPast <= AFTER_MANEUVER_METERS) {
                    return prev
                }
            }
        }
        // 2. Active upcoming maneuver within startDistanceMeters before (or still within 30 m after its pivot)
        val cur = route.maneuvers.getOrNull(currentManeuverIndex) ?: return null
        if (cur.lanes.size < 2) return null
        if (snappedShapeIndex > cur.beginShapeIndex) {
            val distPast = distanceAlongShape(route.shape, cur.beginShapeIndex, snappedShapeIndex)
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
                    val pos = interpolate(cur, prev, frac)
                    val bearing = ValhallaResponseParser.bearingDegrees(prev, cur).toFloat()
                    return LaneArrowMarker(strip.laneIndex, pos, bearing, strip.arrowDirection, isLargeArrow)
                }
                acc += seg
            }
            i--
        }
        if (!fallbackToMidpoint) return null
        val pos = interpolate(pts[0], pts[safePivot], 0.5)
        val bearing = ValhallaResponseParser.bearingDegrees(pts[0], pts[safePivot]).toFloat()
        return LaneArrowMarker(strip.laneIndex, pos, bearing, strip.arrowDirection, isLargeArrow)
    }

    internal fun offsetPolylineMeters(
        points: List<LatLon>,
        origin: LatLon,
        rightOffsetMeters: Double
    ): List<LatLon> {
        if (points.size < 2) return points
        val cosLat = cos(Math.toRadians(origin.lat)).coerceAtLeast(0.1)
        val metersPerDegLon = METERS_PER_DEG_LAT * cosLat

        // Convert to local East (x) / North (y) meters
        val xs = DoubleArray(points.size) { i -> (points[i].lon - origin.lon) * metersPerDegLon }
        val ys = DoubleArray(points.size) { i -> (points[i].lat - origin.lat) * METERS_PER_DEG_LAT }

        val out = ArrayList<LatLon>(points.size)
        for (i in points.indices) {
            var tx = 0.0
            var ty = 0.0
            if (i > 0) {
                val dx = xs[i] - xs[i - 1]
                val dy = ys[i] - ys[i - 1]
                val len = hypot(dx, dy)
                if (len > 1e-4) {
                    tx += dx / len
                    ty += dy / len
                }
            }
            if (i < points.lastIndex) {
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val len = hypot(dx, dy)
                if (len > 1e-4) {
                    tx += dx / len
                    ty += dy / len
                }
            }
            val tLen = hypot(tx, ty)
            val nx: Double
            val ny: Double
            if (tLen > 1e-4) {
                val ux = tx / tLen
                val uy = ty / tLen
                // Right-hand perpendicular normal in (East, North)
                nx = uy
                ny = -ux
            } else {
                nx = 0.0
                ny = 0.0
            }
            val ox = xs[i] + nx * rightOffsetMeters
            val oy = ys[i] + ny * rightOffsetMeters
            out.add(
                LatLon(
                    lat = origin.lat + oy / METERS_PER_DEG_LAT,
                    lon = origin.lon + ox / metersPerDegLon
                )
            )
        }
        return out
    }

    private fun distanceAlongShape(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
        val a = fromIdx.coerceIn(0, shape.lastIndex)
        val b = toIdx.coerceIn(0, shape.lastIndex)
        if (b <= a) return 0.0
        var sum = 0.0
        for (i in a until b) {
            sum += OfflinePlaceSearchEngine.haversineMeters(
                shape[i].lat, shape[i].lon, shape[i + 1].lat, shape[i + 1].lon
            )
        }
        return sum
    }

    private fun collectBackward(shape: List<LatLon>, pivotIdx: Int, targetMeters: Double): List<LatLon> {
        val rev = ArrayList<LatLon>()
        var acc = 0.0
        var i = pivotIdx
        while (i > 0 && acc < targetMeters) {
            val cur = shape[i]
            val prev = shape[i - 1]
            val seg = OfflinePlaceSearchEngine.haversineMeters(cur.lat, cur.lon, prev.lat, prev.lon)
            if (seg <= 0.05) {
                i--
                continue
            }
            if (acc + seg >= targetMeters) {
                val frac = ((targetMeters - acc) / seg).coerceIn(0.0, 1.0)
                rev.add(interpolate(cur, prev, frac))
                break
            } else {
                rev.add(prev)
                acc += seg
                i--
            }
        }
        return rev.asReversed()
    }

    private fun collectForward(shape: List<LatLon>, pivotIdx: Int, targetMeters: Double): List<LatLon> {
        val out = ArrayList<LatLon>()
        var acc = 0.0
        var i = pivotIdx
        while (i < shape.lastIndex && acc < targetMeters) {
            val cur = shape[i]
            val next = shape[i + 1]
            val seg = OfflinePlaceSearchEngine.haversineMeters(cur.lat, cur.lon, next.lat, next.lon)
            if (seg <= 0.05) {
                i++
                continue
            }
            if (acc + seg >= targetMeters) {
                val frac = ((targetMeters - acc) / seg).coerceIn(0.0, 1.0)
                out.add(interpolate(cur, next, frac))
                break
            } else {
                out.add(next)
                acc += seg
                i++
            }
        }
        return out
    }

    private fun interpolate(a: LatLon, b: LatLon, frac: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * frac, a.lon + (b.lon - a.lon) * frac)
}
