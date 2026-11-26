package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine

/**
 * Two-stage framing for Turn Focus guidance:
 * - [NONE]: Normal follow camera outside approach window.
 * - [APPROACH]: Entered at 1,000 m (or wide-highway distance) before the turn/exit; frames both the
 *   vehicle and the upcoming turn or ramp split so the maneuver is visible on screen.
 * - [LANE_LEVEL]: Tightened zoomed-in view showing the recommended blue lane clearly; entered on
 *   passing the last mapped traffic signal, approaching a signalized target stop line, or reaching
 *   the distance/time tighten threshold when no signal applies.
 */
enum class TurnFocusStage {
    NONE,
    APPROACH,
    LANE_LEVEL
}

/**
 * Pure stage-transition, adaptive approach-fit zoom, and completion-based release rules
 * for [TurnFocusSessionTracker].
 */
object TurnFocusStageHelper {

    /** Minimum zoom during approach framing so the on-road blue lane layer stays visible (>= 15.5). */
    const val APPROACH_MIN_ZOOM_1KM = 15.55
    const val APPROACH_MIN_ZOOM_WIDE_HIGHWAY = 15.25
    const val APPROACH_MAX_ZOOM_BEFORE_TIGHTEN = 17.0

    /** Distance/time thresholds for tightening into [TurnFocusStage.LANE_LEVEL]. */
    const val CITY_AT_SIGNAL_TIGHTEN_METERS = 260.0
    const val CITY_AT_SIGNAL_TIGHTEN_SECONDS = 18.0
    const val CITY_FALLBACK_TIGHTEN_METERS = 260.0
    const val CITY_FALLBACK_TIGHTEN_SECONDS = 15.0
    const val CITY_SAFETY_FINAL_TIGHTEN_METERS = 120.0
    const val HIGHWAY_FALLBACK_TIGHTEN_METERS = 450.0
    const val HIGHWAY_FALLBACK_TIGHTEN_SECONDS = 16.0
    const val HIGHWAY_FALLBACK_MAX_TIGHTEN_METERS = 560.0

    /** Completion-based release thresholds (vehicle established on outgoing road/ramp). */
    const val CITY_MIN_ESTABLISHED_METERS = 40.0
    const val CITY_UNALIGNED_FALLBACK_END_METERS = 85.0
    const val HIGHWAY_MIN_ESTABLISHED_METERS = 135.0
    const val HIGHWAY_UNALIGNED_FALLBACK_END_METERS = 210.0
    const val OUTGOING_HEADING_ALIGNMENT_TOLERANCE_DEG = 32.0

    /** Duration for the smooth transition from [TurnFocusStage.APPROACH] to [TurnFocusStage.LANE_LEVEL]. */
    const val TIGHTEN_ANIM_MS = 650

    /**
     * Computes target camera zoom for the active [stage].
     * In [TurnFocusStage.APPROACH], fits the upcoming turn or exit ramp split inside the viewport
     * at 1 km and tightens smoothly as remaining distance decreases.
     */
    fun computeTargetZoom(
        stage: TurnFocusStage,
        remainingMeters: Double,
        isHighway: Boolean,
        isClosePair: Boolean
    ): Double {
        if (stage == TurnFocusStage.NONE) return 0.0
        val laneLevelZoom = when {
            isHighway -> TurnFocusPlanner.FOCUS_ZOOM_HIGHWAY
            isClosePair -> TurnFocusPlanner.FOCUS_ZOOM_CLOSE_PAIR
            else -> TurnFocusPlanner.FOCUS_ZOOM_CITY
        }
        if (stage == TurnFocusStage.LANE_LEVEL) return laneLevelZoom

        val d = remainingMeters.coerceIn(180.0, TurnFocusPlanner.MAX_WIDE_HIGHWAY_DISTANCE_METERS)
        val approachZoom = if (d > 1000.0) {
            val extraFrac = ((d - 1000.0) / 600.0).coerceIn(0.0, 1.0)
            APPROACH_MIN_ZOOM_1KM - extraFrac * (APPROACH_MIN_ZOOM_1KM - APPROACH_MIN_ZOOM_WIDE_HIGHWAY)
        } else {
            val closeFrac = ((1000.0 - d) / 750.0).coerceIn(0.0, 1.0)
            APPROACH_MIN_ZOOM_1KM + closeFrac * (APPROACH_MAX_ZOOM_BEFORE_TIGHTEN - APPROACH_MIN_ZOOM_1KM)
        }
        val maxApproach = (laneLevelZoom - 0.35).coerceAtLeast(APPROACH_MIN_ZOOM_1KM)
        return approachZoom.coerceIn(APPROACH_MIN_ZOOM_WIDE_HIGHWAY, maxApproach)
    }

    /**
     * Determines whether the camera should tighten into [TurnFocusStage.LANE_LEVEL].
     */
    fun shouldEnterLaneLevel(
        remainingMeters: Double,
        speedMps: Float,
        isHighway: Boolean,
        isCloseSecondTurn: Boolean,
        matchedSignal: MatchedApproachSignal
    ): Boolean {
        if (isCloseSecondTurn) return true
        val safeSpeed = speedMps.coerceAtLeast(0f).toDouble()
        val timeToManeuverSec = if (safeSpeed > 1.0) remainingMeters / safeSpeed else Double.MAX_VALUE

        return when (matchedSignal.kind) {
            ApproachSignalKind.BEFORE_TARGET_IN_WINDOW -> {
                // Tighten immediately once the vehicle passes the last traffic light before the turn
                matchedSignal.hasPassedSignal || remainingMeters <= CITY_SAFETY_FINAL_TIGHTEN_METERS
            }
            ApproachSignalKind.AT_TARGET_INTERSECTION -> {
                // Signal is AT the target intersection: tighten before reaching the stop line
                // so the driver can choose the blue turn lane before stopping at a red light
                remainingMeters <= CITY_AT_SIGNAL_TIGHTEN_METERS ||
                    timeToManeuverSec <= CITY_AT_SIGNAL_TIGHTEN_SECONDS
            }
            ApproachSignalKind.NONE -> {
                if (isHighway) {
                    val dynamicDist = if (safeSpeed >= 18.0) {
                        (safeSpeed * HIGHWAY_FALLBACK_TIGHTEN_SECONDS)
                            .coerceIn(HIGHWAY_FALLBACK_TIGHTEN_METERS, HIGHWAY_FALLBACK_MAX_TIGHTEN_METERS)
                    } else {
                        HIGHWAY_FALLBACK_TIGHTEN_METERS
                    }
                    remainingMeters <= dynamicDist
                } else {
                    val dynamicDist = if (safeSpeed >= 8.0) {
                        (safeSpeed * CITY_FALLBACK_TIGHTEN_SECONDS)
                            .coerceIn(CITY_FALLBACK_TIGHTEN_METERS, 320.0)
                    } else {
                        CITY_FALLBACK_TIGHTEN_METERS
                    }
                    remainingMeters <= dynamicDist
                }
            }
        }
    }

    /**
     * Completion-based release check: returns true only when reliable route progress places the
     * vehicle established on the intended outgoing road or exit ramp.
     *
     * Never releases while stopped at or before the intersection, or merely upon crossing the pivot.
     */
    fun isManeuverCompleted(
        route: OfflineRoute,
        activeBeginShapeIndex: Int,
        activeEndShapeIndex: Int,
        activeIsHighway: Boolean,
        vehicleProgressMeters: Double,
        snappedBearing: Float?,
        speedMps: Float
    ): Boolean {
        val shape = route.shape
        if (shape.size < 2) return true
        val pivotIdx = activeBeginShapeIndex.coerceIn(0, shape.lastIndex)
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(shape, pivotIdx)
        val distPastPivot = (vehicleProgressMeters - pivotProgress).coerceAtLeast(0.0)

        // Stopped at or near the intersection / red light: never release guidance
        if (speedMps < 0.5f && distPastPivot < 25.0) return false

        val standardEndDist = TurnFocusPlanner.endPastDistanceMeters(activeIsHighway)
        val minEstablishedDist = if (activeIsHighway) HIGHWAY_MIN_ESTABLISHED_METERS else CITY_MIN_ESTABLISHED_METERS
        if (distPastPivot < minEstablishedDist) return false

        // Legacy callers that omit snappedBearing fall back to standard distance past pivot
        if (snappedBearing == null || !snappedBearing.isFinite()) {
            return distPastPivot >= standardEndDist
        }

        val outgoingBearing = computeOutgoingLegBearing(
            shape = shape,
            pivotIdx = pivotIdx,
            endShapeIdx = activeEndShapeIndex,
            sampleStartMeters = if (activeIsHighway) 40.0 else 15.0,
            sampleEndMeters = if (activeIsHighway) 120.0 else 55.0
        ) ?: return distPastPivot >= standardEndDist

        val headingDiff = ValhallaResponseParser.angleDiffDegrees(snappedBearing.toDouble(), outgoingBearing)
        val aligned = headingDiff <= OUTGOING_HEADING_ALIGNMENT_TOLERANCE_DEG
        val requiredDist = if (activeIsHighway) {
            TurnFocusPlanner.END_PAST_HIGHWAY_METERS
        } else {
            TurnFocusPlanner.END_PAST_CITY_METERS
        }
        val unalignedCap = if (activeIsHighway) {
            HIGHWAY_UNALIGNED_FALLBACK_END_METERS
        } else {
            CITY_UNALIGNED_FALLBACK_END_METERS
        }
        return (aligned && distPastPivot >= requiredDist) || distPastPivot >= unalignedCap
    }

    /**
     * Computes the true bearing of the outgoing road or ramp segment after [pivotIdx].
     */
    fun computeOutgoingLegBearing(
        shape: List<LatLon>,
        pivotIdx: Int,
        endShapeIdx: Int,
        sampleStartMeters: Double = 15.0,
        sampleEndMeters: Double = 55.0
    ): Double? {
        if (shape.size < 2 || pivotIdx >= shape.lastIndex) return null
        val maxIdx = endShapeIdx.coerceIn((pivotIdx + 1).coerceAtMost(shape.lastIndex), shape.lastIndex)
        var acc = 0.0
        var startPt: LatLon? = null
        var endPt: LatLon? = null
        var i = pivotIdx
        while (i < maxIdx) {
            val a = shape[i]
            val b = shape[i + 1]
            val seg = OfflinePlaceSearchEngine.haversineMeters(a.lat, a.lon, b.lat, b.lon)
            if (seg > 0.1) {
                if (startPt == null && acc + seg >= sampleStartMeters) {
                    val f = ((sampleStartMeters - acc) / seg).coerceIn(0.0, 1.0)
                    startPt = LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                }
                if (acc + seg >= sampleEndMeters) {
                    val f = ((sampleEndMeters - acc) / seg).coerceIn(0.0, 1.0)
                    endPt = LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                    break
                }
                acc += seg
            }
            i++
        }
        val from = startPt ?: shape[pivotIdx]
        val to = endPt ?: shape[maxIdx]
        if (OfflinePlaceSearchEngine.haversineMeters(from.lat, from.lon, to.lat, to.lon) < 2.0) {
            return if (pivotIdx < shape.lastIndex) {
                ValhallaResponseParser.bearingDegrees(shape[pivotIdx], shape[pivotIdx + 1])
            } else null
        }
        return ValhallaResponseParser.bearingDegrees(from, to)
    }

    /**
     * Computes the framed camera target for Turn Focus mode so the first update inside the
     * approach threshold (e.g. 1,000 m or 500 m) visibly shifts the viewport to frame the vehicle,
     * the upcoming junction, and the outgoing road/ramp below the top HUD and lane panel.
     */
    fun computeApproachCameraTarget(
        route: OfflineRoute?,
        maneuver: RouteManeuver?,
        vehicleLoc: LatLon,
        remainingMeters: Double,
        stage: TurnFocusStage
    ): LatLon {
        if (route == null || maneuver == null || route.shape.size < 2 || stage == TurnFocusStage.NONE) {
            return vehicleLoc
        }
        val shape = route.shape
        val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, shape.lastIndex)
        val pivotPt = shape[pivotIdx]
        val outgoingPt = sampleForwardPoint(shape, pivotIdx, distanceMeters = 45.0) ?: pivotPt
        // Anchor slightly into the outgoing road so the exit/turn direction is clearly framed
        val junctionAnchor = LatLon(
            lat = pivotPt.lat * 0.82 + outgoingPt.lat * 0.18,
            lon = pivotPt.lon * 0.82 + outgoingPt.lon * 0.18
        )
        val distToAnchor = OfflinePlaceSearchEngine.haversineMeters(
            vehicleLoc.lat, vehicleLoc.lon, junctionAnchor.lat, junctionAnchor.lon
        )
        if (distToAnchor < 6.0) return vehicleLoc

        val safeRemaining = remainingMeters.coerceAtLeast(0.0)
        val desiredShiftMeters = when (stage) {
            TurnFocusStage.APPROACH -> minOf(320.0, maxOf(35.0, safeRemaining * 0.36))
            TurnFocusStage.LANE_LEVEL -> minOf(24.0, safeRemaining * 0.22)
            TurnFocusStage.NONE -> 0.0
        }
        val frac = (desiredShiftMeters / distToAnchor).coerceIn(0.0, 0.55)
        return LatLon(
            lat = vehicleLoc.lat + (junctionAnchor.lat - vehicleLoc.lat) * frac,
            lon = vehicleLoc.lon + (junctionAnchor.lon - vehicleLoc.lon) * frac
        )
    }

    private fun sampleForwardPoint(shape: List<LatLon>, startIdx: Int, distanceMeters: Double): LatLon? {
        if (shape.size < 2 || startIdx >= shape.lastIndex) return null
        var acc = 0.0
        var i = startIdx
        while (i < shape.lastIndex) {
            val a = shape[i]
            val b = shape[i + 1]
            val seg = OfflinePlaceSearchEngine.haversineMeters(a.lat, a.lon, b.lat, b.lon)
            if (seg > 0.1) {
                if (acc + seg >= distanceMeters) {
                    val f = ((distanceMeters - acc) / seg).coerceIn(0.0, 1.0)
                    return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                }
                acc += seg
            }
            i++
        }
        return shape.last()
    }
}
