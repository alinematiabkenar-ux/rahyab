package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import kotlin.math.abs

enum class RouteProgressEventKind(val priority: Int) {
    SPEED_CAMERA(6),
    RED_LIGHT_CAMERA(6),
    RAILROAD_CROSSING(5),
    SCHOOL_ZONE(4),
    STEEP_GRADE(3),
    HIGHWAY_EXIT(2),
    DESTINATION(10)
}

data class RouteProgressEvent(
    val id: String,
    val kind: RouteProgressEventKind,
    val fractionAlongRemaining: Float,
    val distanceMeters: Double,
    val labelEn: String = "",
    val labelFa: String = "",
    val clusterCount: Int = 1
)

/**
 * V2: Deterministic, offline planner for the Sygic-style right-edge route progress bar.
 * Maps upcoming cameras, railroad crossings, steep grades, school zones, highway exits, and
 * destination along `[0f..1f]` of the remaining route, clusters icons closer than 4% (`0.04f`),
 * and caps the total displayed icons to at most [MAX_ICONS] (20).
 */
object RouteProgressBarPlanner {

    const val CLUSTER_FRACTION_THRESHOLD = 0.04f
    const val MAX_ICONS = 20
    private const val ROUTE_MATCH_TOLERANCE_METERS = 65.0
    private const val CAMERA_DEDUP_METERS = 45.0

    fun buildEvents(
        route: OfflineRoute?,
        snappedShapeIndex: Int,
        remainingDistanceMeters: Double,
        travelMode: TravelMode,
        extraCameras: List<RoadHazardPoint> = emptyList()
    ): List<RouteProgressEvent> {
        if (travelMode == TravelMode.WALK) return emptyList()
        if (route == null || route.shape.size < 2) return emptyList()

        val startIdx = snappedShapeIndex.coerceIn(0, route.shape.lastIndex)
        val cumDist = computeRemainingCumulativeDistances(route.shape, startIdx)
        val geomRemaining = cumDist.lastOrNull() ?: 0.0
        val totalRemaining = if (geomRemaining > 1.0) geomRemaining else remainingDistanceMeters
        if (totalRemaining <= 1.0) return emptyList()

        val rawEvents = mutableListOf<RouteProgressEvent>()

        // 1. Pre-built route alerts (cameras, railroad crossings, steep grades, school zones)
        for (alert in route.roadAlerts) {
            val kind = mapAlertKind(alert.kind) ?: continue
            val matchIdx = findClosestShapeIndex(route.shape, startIdx, alert.lat, alert.lon) ?: continue
            if (matchIdx <= startIdx) continue
            val distAhead = cumDist[matchIdx - startIdx]
            if (distAhead <= 5.0 || distAhead >= totalRemaining - 5.0) continue
            val frac = (distAhead / totalRemaining).toFloat().coerceIn(0f, 0.98f)
            rawEvents.add(
                RouteProgressEvent(
                    id = "alert_${alert.id}",
                    kind = kind,
                    fractionAlongRemaining = frac,
                    distanceMeters = distAhead,
                    labelEn = alert.titleEn,
                    labelFa = alert.titleFa
                )
            )
        }

        // 2. Extra map cameras (e.g. from offline places/extras DB) not already represented
        for (cam in extraCameras) {
            val kind = when (cam.type.lowercase()) {
                "red_light_camera", "traffic_signals_camera" -> RouteProgressEventKind.RED_LIGHT_CAMERA
                "speed_camera", "enforcement" -> RouteProgressEventKind.SPEED_CAMERA
                else -> continue
            }
            val matchIdx = findClosestShapeIndex(route.shape, startIdx, cam.lat, cam.lon) ?: continue
            if (matchIdx <= startIdx) continue
            val distAhead = cumDist[matchIdx - startIdx]
            if (distAhead <= 5.0 || distAhead >= totalRemaining - 5.0) continue
            val duplicate = rawEvents.any {
                (it.kind == RouteProgressEventKind.SPEED_CAMERA || it.kind == RouteProgressEventKind.RED_LIGHT_CAMERA) &&
                    abs(it.distanceMeters - distAhead) < CAMERA_DEDUP_METERS
            }
            if (duplicate) continue
            val frac = (distAhead / totalRemaining).toFloat().coerceIn(0f, 0.98f)
            rawEvents.add(
                RouteProgressEvent(
                    id = "cam_${cam.id}",
                    kind = kind,
                    fractionAlongRemaining = frac,
                    distanceMeters = distAhead
                )
            )
        }

        // 3. Upcoming highway exits along the remaining route
        for (m in route.maneuvers) {
            if (!isHighwayExitManeuver(m)) continue
            if (m.beginShapeIndex <= startIdx || m.beginShapeIndex > route.shape.lastIndex) continue
            val distAhead = cumDist[m.beginShapeIndex - startIdx]
            if (distAhead <= 5.0 || distAhead >= totalRemaining - 5.0) continue
            val exitNum = m.signpost?.primaryExitNumber
            val label = when {
                !exitNum.isNullOrBlank() -> "Exit $exitNum"
                m.streetNames.isNotEmpty() -> m.streetNames.first()
                else -> ""
            }
            val frac = (distAhead / totalRemaining).toFloat().coerceIn(0f, 0.98f)
            rawEvents.add(
                RouteProgressEvent(
                    id = "exit_${m.index}",
                    kind = RouteProgressEventKind.HIGHWAY_EXIT,
                    fractionAlongRemaining = frac,
                    distanceMeters = distAhead,
                    labelEn = label,
                    labelFa = label
                )
            )
        }

        // 4. Cluster icons closer than 4% of the bar length
        val clustered = clusterEvents(rawEvents, CLUSTER_FRACTION_THRESHOLD)

        // 5. Enforce max 20 icons total (always preserving Destination at fraction = 1.0f)
        val limitedNonDest = if (clustered.size > MAX_ICONS - 1) {
            clustered
                .sortedWith(compareByDescending<RouteProgressEvent> { it.kind.priority }.thenBy { it.distanceMeters })
                .take(MAX_ICONS - 1)
                .sortedBy { it.fractionAlongRemaining }
        } else {
            clustered
        }

        val destination = RouteProgressEvent(
            id = "destination",
            kind = RouteProgressEventKind.DESTINATION,
            fractionAlongRemaining = 1.0f,
            distanceMeters = totalRemaining
        )
        return limitedNonDest + destination
    }

    internal fun clusterEvents(
        events: List<RouteProgressEvent>,
        threshold: Float = CLUSTER_FRACTION_THRESHOLD
    ): List<RouteProgressEvent> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.fractionAlongRemaining }
        val result = mutableListOf<RouteProgressEvent>()

        var group = mutableListOf(sorted.first())
        var anchorFraction = sorted.first().fractionAlongRemaining

        fun flushGroup() {
            if (group.size == 1) {
                result.add(group.first())
            } else {
                val rep = group.maxWithOrNull(
                    compareBy<RouteProgressEvent> { it.kind.priority }.thenByDescending { -it.distanceMeters }
                ) ?: group.first()
                val avgFrac = group.map { it.fractionAlongRemaining }.average().toFloat().coerceIn(0f, 0.98f)
                val nearestDist = group.minOf { it.distanceMeters }
                result.add(
                    rep.copy(
                        id = "cluster_${group.first().id}_${group.size}",
                        fractionAlongRemaining = avgFrac,
                        distanceMeters = nearestDist,
                        clusterCount = group.size
                    )
                )
            }
        }

        for (i in 1 until sorted.size) {
            val ev = sorted[i]
            if (ev.fractionAlongRemaining - anchorFraction < threshold) {
                group.add(ev)
            } else {
                flushGroup()
                group = mutableListOf(ev)
                anchorFraction = ev.fractionAlongRemaining
            }
        }
        flushGroup()
        return result
    }

    private fun mapAlertKind(kind: RoadAlertKind): RouteProgressEventKind? = when (kind) {
        RoadAlertKind.SPEED_CAMERA -> RouteProgressEventKind.SPEED_CAMERA
        RoadAlertKind.RED_LIGHT_CAMERA -> RouteProgressEventKind.RED_LIGHT_CAMERA
        RoadAlertKind.RAILROAD_CROSSING -> RouteProgressEventKind.RAILROAD_CROSSING
        RoadAlertKind.STEEP_GRADE -> RouteProgressEventKind.STEEP_GRADE
        RoadAlertKind.SCHOOL_ZONE -> RouteProgressEventKind.SCHOOL_ZONE
        else -> null
    }

    private fun isHighwayExitManeuver(m: RouteManeuver): Boolean {
        return m.isHighwayExitOrSplit ||
            m.type == ManeuverType.EXIT_RIGHT ||
            m.type == ManeuverType.EXIT_LEFT ||
            m.type == ManeuverType.RAMP_RIGHT ||
            m.type == ManeuverType.RAMP_LEFT
    }

    private fun computeRemainingCumulativeDistances(shape: List<LatLon>, startIdx: Int): DoubleArray {
        val count = shape.size - startIdx
        val cum = DoubleArray(count)
        var acc = 0.0
        for (i in 1 until count) {
            val prev = shape[startIdx + i - 1]
            val cur = shape[startIdx + i]
            acc += OfflinePlaceSearchEngine.haversineMeters(prev.lat, prev.lon, cur.lat, cur.lon)
            cum[i] = acc
        }
        return cum
    }

    private fun findClosestShapeIndex(
        shape: List<LatLon>,
        startIdx: Int,
        lat: Double,
        lon: Double
    ): Int? {
        var bestIdx = -1
        var bestDist = ROUTE_MATCH_TOLERANCE_METERS
        // Quick bounding pre-filter (~0.001 deg ~ 111 m) to keep long routes super fast
        for (i in startIdx until shape.size) {
            val pt = shape[i]
            if (abs(pt.lat - lat) > 0.0015 || abs(pt.lon - lon) > 0.002) continue
            val d = OfflinePlaceSearchEngine.haversineMeters(lat, lon, pt.lat, pt.lon)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return if (bestIdx >= 0) bestIdx else null
    }
}
