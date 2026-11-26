package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Classification of the last mapped traffic signal on the active route approach
 * to a target turn or exit maneuver (independent of live signal phase).
 */
enum class ApproachSignalKind {
    /** No valid route-matched traffic signal lies in the approach window. */
    NONE,

    /** A mapped traffic signal lies strictly before the target maneuver inside the approach window. */
    BEFORE_TARGET_IN_WINDOW,

    /** A mapped traffic signal controls the target intersection itself (within stop-line radius). */
    AT_TARGET_INTERSECTION
}

/**
 * Result of matching offline mapped traffic signals to the active route approach.
 */
data class MatchedApproachSignal(
    val kind: ApproachSignalKind = ApproachSignalKind.NONE,
    val signalId: Long = -1L,
    val signalLocation: LatLon? = null,
    val signalProgressMeters: Double = 0.0,
    val distanceBeforeManeuverMeters: Double = 0.0,
    val lateralOffsetMeters: Double = 0.0,
    val hasPassedSignal: Boolean = false
)

/**
 * Matches offline mapped traffic signals ([RoadHazardPoint] with type `"traffic_signals"`)
 * to the active route and direction of travel strictly along the approach to a target maneuver.
 *
 * Rejects signals on parallel roads, cross streets, opposite carriageways, or unrelated legs.
 */
object RouteTrafficSignalMatcher {

    /** Maximum orthogonal distance from the matched route centerline to accept a signal. */
    const val MAX_LATERAL_OFFSET_METERS = 16.0

    /** Maximum left-side offset on divided/one-way carriageways (right-hand traffic). */
    const val MAX_ONEWAY_LEFT_OFFSET_METERS = 10.0

    /** Radius around the maneuver pivot that identifies a signal controlling the target intersection. */
    const val AT_TARGET_INTERSECTION_RADIUS_METERS = 30.0

    /** Allow a small tolerance past the maneuver pivot node for OSM intersection node placement. */
    const val PAST_PIVOT_NODE_TOLERANCE_METERS = 18.0

    /** Distance past the matched signal node required to confirm the vehicle has crossed it. */
    const val PASSED_SIGNAL_HYSTERESIS_METERS = 5.0

    /** Maximum heading mismatch when a signal carries an explicit direction tag. */
    const val MAX_SIGNAL_DIRECTION_DIFF_DEG = 60.0

    fun findFinalSignalBeforeManeuver(
        route: OfflineRoute,
        maneuver: RouteManeuver,
        previousManeuver: RouteManeuver?,
        vehicleProgressMeters: Double,
        approachWindowMeters: Double,
        signals: List<RoadHazardPoint>
    ): MatchedApproachSignal {
        if (route.shape.size < 2 || signals.isEmpty()) return MatchedApproachSignal()
        val trafficSignals = signals.filter { it.type.equals("traffic_signals", ignoreCase = true) }
        if (trafficSignals.isEmpty()) return MatchedApproachSignal()

        val shape = route.shape
        val cumDist = buildCumulativeDistances(shape)
        val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, shape.lastIndex)
        val maneuverProgress = cumDist[pivotIdx]
        val prevProgress = if (previousManeuver != null && previousManeuver.type != ManeuverType.DEPART) {
            cumDist[previousManeuver.beginShapeIndex.coerceIn(0, shape.lastIndex)] + 15.0
        } else {
            0.0
        }
        val windowStart = maxOf(0.0, maneuverProgress - approachWindowMeters, prevProgress)
        val windowEnd = maneuverProgress + PAST_PIVOT_NODE_TOLERANCE_METERS
        if (windowEnd <= windowStart) return MatchedApproachSignal()

        // Approach corridor segment range: never accept projections past the target intersection onto a cross leg
        val maxApproachSegIdx = (pivotIdx - 1).coerceAtLeast(0)
        val minApproachSegIdx = findFirstSegmentAtOrBefore(cumDist, (windowStart - 25.0).coerceAtLeast(0.0))
        val endSegExclusive = (pivotIdx + 1).coerceAtMost(shape.lastIndex)
        if (minApproachSegIdx >= endSegExclusive) return MatchedApproachSignal()

        var bestMatch: SignalProjection? = null
        for (signal in trafficSignals) {
            val proj = projectSignalOntoApproach(
                route = route,
                cumDist = cumDist,
                signal = signal,
                startSeg = minApproachSegIdx,
                endSegExclusive = endSegExclusive,
                pivotIdx = pivotIdx,
                maxApproachSegIdx = maxApproachSegIdx
            ) ?: continue

            if (proj.lateralDistanceMeters > MAX_LATERAL_OFFSET_METERS) continue
            if (proj.progressMeters < windowStart || proj.progressMeters > windowEnd) continue

            // Reject signals that carry an explicit opposite/perpendicular direction tag
            val dirDeg = signal.directionDegrees
            if (dirDeg != null && dirDeg.isFinite()) {
                val diff = ValhallaResponseParser.angleDiffDegrees(dirDeg.toDouble(), proj.segmentBearingDeg)
                if (diff > MAX_SIGNAL_DIRECTION_DIFF_DEG) continue
            }

            // Reject signals sitting across the median on an opposing one-way/divided carriageway
            val isOneWay = route.oneWayByShapeIndex[proj.segmentIndex] == true ||
                maneuver.isHighwayRoad ||
                previousManeuver?.isHighwayRoad == true
            if (isOneWay && proj.signedRightOffsetMeters < -MAX_ONEWAY_LEFT_OFFSET_METERS) {
                continue
            }

            // Keep the LAST mapped signal along the approach (largest route progress <= maneuver)
            if (bestMatch == null ||
                proj.progressMeters > bestMatch.progressMeters + 1.0 ||
                (abs(proj.progressMeters - bestMatch.progressMeters) <= 1.0 &&
                    proj.lateralDistanceMeters < bestMatch.lateralDistanceMeters)
            ) {
                bestMatch = proj
            }
        }

        val winner = bestMatch ?: return MatchedApproachSignal()
        val distBefore = (maneuverProgress - winner.progressMeters).coerceAtLeast(0.0)
        val isAtTarget = abs(maneuverProgress - winner.progressMeters) <= AT_TARGET_INTERSECTION_RADIUS_METERS
        val kind = if (isAtTarget) {
            ApproachSignalKind.AT_TARGET_INTERSECTION
        } else {
            ApproachSignalKind.BEFORE_TARGET_IN_WINDOW
        }
        val passed = vehicleProgressMeters >= winner.progressMeters + PASSED_SIGNAL_HYSTERESIS_METERS

        return MatchedApproachSignal(
            kind = kind,
            signalId = winner.signal.id,
            signalLocation = LatLon(winner.signal.lat, winner.signal.lon),
            signalProgressMeters = winner.progressMeters,
            distanceBeforeManeuverMeters = distBefore,
            lateralOffsetMeters = winner.lateralDistanceMeters,
            hasPassedSignal = passed
        )
    }

    private data class SignalProjection(
        val signal: RoadHazardPoint,
        val segmentIndex: Int,
        val progressMeters: Double,
        val lateralDistanceMeters: Double,
        val signedRightOffsetMeters: Double,
        val segmentBearingDeg: Double
    )

    private fun projectSignalOntoApproach(
        route: OfflineRoute,
        cumDist: DoubleArray,
        signal: RoadHazardPoint,
        startSeg: Int,
        endSegExclusive: Int,
        pivotIdx: Int,
        maxApproachSegIdx: Int
    ): SignalProjection? {
        val shape = route.shape
        val cosLat = cos(Math.toRadians(signal.lat)).coerceAtLeast(0.1)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * cosLat

        var best: SignalProjection? = null
        for (i in startSeg until endSegExclusive) {
            val a = shape[i]
            val b = shape[i + 1]
            val segLen = cumDist[i + 1] - cumDist[i]
            if (segLen <= 0.1) continue

            val dx = (b.lon - a.lon) * metersPerDegLon
            val dy = (b.lat - a.lat) * metersPerDegLat
            val lenSq = dx * dx + dy * dy
            if (lenSq <= 1e-6) continue

            val px = (signal.lon - a.lon) * metersPerDegLon
            val py = (signal.lat - a.lat) * metersPerDegLat
            val rawT = (px * dx + py * dy) / lenSq
            val t = rawT.coerceIn(0.0, 1.0)

            // Reject signals projected strictly onto the outgoing leg past the intersection node
            if (i >= pivotIdx && t * segLen > PAST_PIVOT_NODE_TOLERANCE_METERS) continue

            val projX = t * dx
            val projY = t * dy
            val errX = px - projX
            val errY = py - projY
            val lateralDist = hypot(errX, errY)

            // Signed right-hand perpendicular offset in meters (positive = right of travel, negative = left)
            val segLenXY = hypot(dx, dy).coerceAtLeast(1e-6)
            val rightNormalX = dy / segLenXY
            val rightNormalY = -dx / segLenXY
            val signedRight = errX * rightNormalX + errY * rightNormalY

            val progress = cumDist[i] + segLen * t
            val bearing = ValhallaResponseParser.bearingDegrees(a, b)

            // Prefer approach segment over outgoing segment when tied right at the pivot
            val preferOverBest = best == null ||
                lateralDist < best.lateralDistanceMeters - 0.5 ||
                (abs(lateralDist - best.lateralDistanceMeters) <= 0.5 &&
                    i <= maxApproachSegIdx && best.segmentIndex > maxApproachSegIdx)

            if (preferOverBest) {
                best = SignalProjection(
                    signal = signal,
                    segmentIndex = i,
                    progressMeters = progress,
                    lateralDistanceMeters = lateralDist,
                    signedRightOffsetMeters = signedRight,
                    segmentBearingDeg = bearing
                )
            }
        }
        return best
    }

    private fun buildCumulativeDistances(shape: List<LatLon>): DoubleArray {
        val out = DoubleArray(shape.size)
        var acc = 0.0
        for (i in 1 until shape.size) {
            acc += OfflinePlaceSearchEngine.haversineMeters(
                shape[i - 1].lat, shape[i - 1].lon, shape[i].lat, shape[i].lon
            )
            out[i] = acc
        }
        return out
    }

    private fun findFirstSegmentAtOrBefore(cumDist: DoubleArray, targetMeters: Double): Int {
        var idx = 0
        while (idx + 1 < cumDist.size && cumDist[idx + 1] < targetMeters) {
            idx++
        }
        return idx
    }
}
