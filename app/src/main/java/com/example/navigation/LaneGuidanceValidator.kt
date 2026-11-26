package com.example.navigation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LaneValidationResult(
    val confidence: LaneConfidence,
    val reasons: List<String>,
    val targetIndices: List<Int>,
    val suggestedSide: LaneTargetSide?,
    val hasAdvice: Boolean
)

data class WrongLaneSuppressionRecord(
    val lat: Double,
    val lon: Double,
    val bearingDegrees: Float,
    val mapVersion: String
)

/**
 * Pure Kotlin, unit-testable validator for Valhalla/OSM turn-lane guidance.
 * Evaluates arrow compatibility, lane side & contiguity, source reliability,
 * edge/HPMS lane-count consistency, and user wrong-lane reports.
 */
object LaneGuidanceValidator {

    const val MAX_CARRIED_LANE_DISTANCE_METERS = 300.0
    const val SUPPRESSION_RADIUS_METERS = 30.0
    const val SUPPRESSION_BEARING_TOLERANCE_DEG = 30.0
    const val FALLBACK_CITY_APPROACH_METERS = 300.0
    const val FALLBACK_HIGHWAY_APPROACH_METERS = 600.0

    /**
     * L3: Computes how far back from [pivotIdx] the lane overlay should be drawn.
     * - When [edgeLaneCountsByShapeIndex] has entries on the approach, walks backward while
     *   `|edgeCount - maneuverLaneCount| <= 1` (allowing ±1 for a turn bay) and stops immediately
     *   when an edge differs by more than 1.
     * - When edge lane counts are missing on the approach, falls back to max 300 m in city
     *   or 600 m on highways.
     */
    fun computeApproachDrawDistanceMeters(
        shape: List<LatLon>,
        edgeLaneCountsByShapeIndex: Map<Int, Int>,
        pivotIdx: Int,
        maneuverLaneCount: Int,
        isHighway: Boolean,
        maxStartDistanceMeters: Double
    ): Double {
        if (shape.size < 2 || pivotIdx <= 0) return 0.0
        val safePivot = pivotIdx.coerceIn(0, shape.lastIndex)
        val fallbackCap = if (isHighway) FALLBACK_HIGHWAY_APPROACH_METERS else FALLBACK_CITY_APPROACH_METERS
        var hasAnyEdgeCount = false
        var acc = 0.0
        var i = safePivot
        while (i > 0 && acc < maxStartDistanceMeters) {
            val edgeCount = edgeLaneCountsByShapeIndex[i - 1]
            if (edgeCount != null && edgeCount > 0) {
                hasAnyEdgeCount = true
                if (abs(edgeCount - maneuverLaneCount) > 1) {
                    return acc.coerceAtMost(maxStartDistanceMeters)
                }
            }
            acc += haversineMeters(shape[i].lat, shape[i].lon, shape[i - 1].lat, shape[i - 1].lon)
            i--
        }
        return if (hasAnyEdgeCount) {
            acc.coerceAtMost(maxStartDistanceMeters)
        } else {
            minOf(maxStartDistanceMeters, fallbackCap)
        }
    }

    fun validate(
        maneuverType: ManeuverType,
        lanes: List<LaneInfo>,
        laneSource: LaneSource,
        carriedDistanceMeters: Double = 0.0,
        carriedHadInterveningIntersection: Boolean = false,
        edgeLaneCountBeforeManeuver: Int? = null,
        isHighway: Boolean = false,
        hpmsThroughLanes: Int? = null,
        isReportedWrongByUser: Boolean = false
    ): LaneValidationResult {
        val reasons = mutableListOf<String>()
        val targetIndices = resolveTargetIndices(lanes)
        val suggestedSide = resolveSuggestedSide(maneuverType, targetIndices, lanes.size)

        if (lanes.size < 2) {
            reasons.add("too_few_lanes")
            return LaneValidationResult(
                confidence = LaneConfidence.LOW,
                reasons = reasons,
                targetIndices = emptyList(),
                suggestedSide = suggestedSide,
                hasAdvice = false
            )
        }
        if (targetIndices.isEmpty()) {
            reasons.add("no_target_lanes")
            return LaneValidationResult(
                confidence = LaneConfidence.LOW,
                reasons = reasons,
                targetIndices = emptyList(),
                suggestedSide = suggestedSide,
                hasAdvice = false
            )
        }
        if (targetIndices.size >= lanes.size) {
            reasons.add("all_lanes_valid")
            return LaneValidationResult(
                confidence = LaneConfidence.LOW,
                reasons = reasons,
                targetIndices = targetIndices,
                suggestedSide = suggestedSide,
                hasAdvice = false
            )
        }

        // 1. Source rule
        var confidence = when (laneSource) {
            LaneSource.DIRECT, LaneSource.EDGE_APPROACH -> LaneConfidence.HIGH
            LaneSource.CARRIED_ALIGNED -> {
                reasons.add("carried_aligned_source")
                LaneConfidence.MEDIUM
            }
            LaneSource.NONE -> {
                reasons.add("no_lane_source")
                LaneConfidence.LOW
            }
        }
        if (laneSource == LaneSource.CARRIED_ALIGNED) {
            if (carriedHadInterveningIntersection) {
                reasons.add("carried_across_intersection")
                confidence = LaneConfidence.LOW
            }
            if (carriedDistanceMeters > MAX_CARRIED_LANE_DISTANCE_METERS) {
                reasons.add("carried_distance_gt_300m")
                confidence = LaneConfidence.LOW
            }
        }

        // 2. Personal wrong-lane report rule
        if (isReportedWrongByUser) {
            reasons.add("reported_wrong_by_user")
            confidence = LaneConfidence.LOW
        }

        // 3. Arrow compatibility rule
        val compatibleDirections = compatibleDirectionsFor(maneuverType)
        val allTargetsHaveCompatibleArrow = targetIndices.all { idx ->
            val lane = lanes[idx]
            val dirs = (lane.activeDirections + lane.directions)
            dirs.any { it in compatibleDirections }
        }
        if (!allTargetsHaveCompatibleArrow) {
            reasons.add("incompatible_target_arrow")
            confidence = LaneConfidence.LOW
        }

        // 4. Side & contiguity rule
        if (isDirectionalTurnOrExit(maneuverType)) {
            val isContiguous = (targetIndices.last() - targetIndices.first() + 1) == targetIndices.size
            if (!isContiguous) {
                reasons.add("non_contiguous_targets")
                confidence = LaneConfidence.LOW
            } else if (isRightTurnOrExit(maneuverType) && lanes.lastIndex !in targetIndices) {
                val allowedNonEdge = allowsNonRightmostTurn(lanes, targetIndices)
                if (!allowedNonEdge) {
                    reasons.add("right_turn_not_rightmost")
                    confidence = LaneConfidence.LOW
                }
            } else if (isLeftTurnOrExit(maneuverType) && 0 !in targetIndices) {
                val allowedNonEdge = allowsNonLeftmostTurn(lanes, targetIndices)
                if (!allowedNonEdge) {
                    reasons.add("left_turn_not_leftmost")
                    confidence = LaneConfidence.LOW
                }
            }
        }

        // 5. Count rule — Valhalla edge.lane_count before maneuver
        if (edgeLaneCountBeforeManeuver != null && edgeLaneCountBeforeManeuver > 0) {
            if (abs(edgeLaneCountBeforeManeuver - lanes.size) > 2) {
                reasons.add("edge_lane_count_mismatch")
                confidence = lowerOneLevel(confidence)
            }
        }

        // 6. Count rule — HPMS through-lanes on highways
        if (isHighway && hpmsThroughLanes != null && hpmsThroughLanes > 0) {
            val nonExitOnlyCount = lanes.count { lane ->
                lane.directions.isEmpty() || LaneDirection.THROUGH in lane.directions
            }
            if (abs(hpmsThroughLanes - nonExitOnlyCount) > 1) {
                reasons.add("hpms_lane_count_mismatch")
                confidence = lowerOneLevel(confidence)
            }
        }

        return LaneValidationResult(
            confidence = confidence,
            reasons = reasons,
            targetIndices = targetIndices,
            suggestedSide = suggestedSide,
            hasAdvice = true
        )
    }

    fun resolveTargetIndices(lanes: List<LaneInfo>): List<Int> {
        if (lanes.isEmpty()) return emptyList()
        val active = lanes.mapIndexedNotNull { idx, lane ->
            if (lane.isActive || lane.highlightTier == LaneHighlightTier.ACTIVE) idx else null
        }
        if (active.isNotEmpty()) return active
        return lanes.mapIndexedNotNull { idx, lane ->
            if (lane.isValid || lane.isRecommended || lane.highlightTier == LaneHighlightTier.VALID) idx else null
        }
    }

    fun matchesWrongLaneSuppression(
        lat: Double,
        lon: Double,
        bearingDegrees: Float,
        currentMapVersion: String,
        reports: List<WrongLaneSuppressionRecord>
    ): Boolean {
        if (reports.isEmpty()) return false
        return reports.any { report ->
            report.mapVersion == currentMapVersion &&
                haversineMeters(lat, lon, report.lat, report.lon) <= SUPPRESSION_RADIUS_METERS &&
                angleDiffDegrees(bearingDegrees.toDouble(), report.bearingDegrees.toDouble()) <= SUPPRESSION_BEARING_TOLERANCE_DEG
        }
    }

    fun findApproachEdgeLaneCount(route: OfflineRoute, maneuver: RouteManeuver): Int? {
        if (route.edgeLaneCountsByShapeIndex.isEmpty()) return null
        val startIdx = (maneuver.beginShapeIndex - 1).coerceAtLeast(0)
        for (i in startIdx downTo maxOf(0, startIdx - 12)) {
            val c = route.edgeLaneCountsByShapeIndex[i]
            if (c != null && c > 0) return c
        }
        return route.edgeLaneCountsByShapeIndex[maneuver.beginShapeIndex]?.takeIf { it > 0 }
    }

    fun findApproachEdgeWayId(route: OfflineRoute, maneuver: RouteManeuver): Long? {
        if (route.edgeWayIdsByShapeIndex.isEmpty()) return null
        val startIdx = (maneuver.beginShapeIndex - 1).coerceAtLeast(0)
        for (i in startIdx downTo maxOf(0, startIdx - 12)) {
            val w = route.edgeWayIdsByShapeIndex[i]
            if (w != null && w > 0L) return w
        }
        return route.edgeWayIdsByShapeIndex[maneuver.beginShapeIndex]?.takeIf { it > 0L }
    }

    fun resolveSuggestedSide(
        maneuverType: ManeuverType,
        targetIndices: List<Int>,
        laneCount: Int
    ): LaneTargetSide = when (maneuverType) {
        ManeuverType.RIGHT,
        ManeuverType.SHARP_RIGHT,
        ManeuverType.SLIGHT_RIGHT,
        ManeuverType.EXIT_RIGHT,
        ManeuverType.RAMP_RIGHT,
        ManeuverType.STAY_RIGHT,
        ManeuverType.U_TURN_RIGHT,
        ManeuverType.ARRIVE_RIGHT -> LaneTargetSide.RIGHT

        ManeuverType.LEFT,
        ManeuverType.SHARP_LEFT,
        ManeuverType.SLIGHT_LEFT,
        ManeuverType.EXIT_LEFT,
        ManeuverType.RAMP_LEFT,
        ManeuverType.STAY_LEFT,
        ManeuverType.U_TURN_LEFT,
        ManeuverType.ARRIVE_LEFT -> LaneTargetSide.LEFT

        else -> {
            if (targetIndices.isEmpty() || laneCount < 2) {
                LaneTargetSide.MIDDLE
            } else {
                val lastIdx = laneCount - 1
                val minT = targetIndices.min()
                val maxT = targetIndices.max()
                when {
                    minT == 0 && maxT < lastIdx -> LaneTargetSide.LEFT
                    maxT == lastIdx && minT > 0 -> LaneTargetSide.RIGHT
                    else -> LaneTargetSide.MIDDLE
                }
            }
        }
    }

    private fun lowerOneLevel(current: LaneConfidence): LaneConfidence = when (current) {
        LaneConfidence.HIGH -> LaneConfidence.MEDIUM
        LaneConfidence.MEDIUM -> LaneConfidence.LOW
        LaneConfidence.LOW -> LaneConfidence.LOW
    }

    private fun isRightTurnOrExit(type: ManeuverType): Boolean = type in setOf(
        ManeuverType.RIGHT,
        ManeuverType.SHARP_RIGHT,
        ManeuverType.EXIT_RIGHT,
        ManeuverType.RAMP_RIGHT
    )

    private fun isLeftTurnOrExit(type: ManeuverType): Boolean = type in setOf(
        ManeuverType.LEFT,
        ManeuverType.SHARP_LEFT,
        ManeuverType.EXIT_LEFT,
        ManeuverType.RAMP_LEFT
    )

    private fun isDirectionalTurnOrExit(type: ManeuverType): Boolean =
        isRightTurnOrExit(type) || isLeftTurnOrExit(type) ||
            type == ManeuverType.STAY_RIGHT || type == ManeuverType.STAY_LEFT

    private fun allowsNonRightmostTurn(lanes: List<LaneInfo>, targetIndices: List<Int>): Boolean {
        // Leftmost lane on a right turn is never an acceptable non-edge exception
        if (targetIndices.first() == 0) return false
        val rightArrows = setOf(LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT, LaneDirection.SLIGHT_RIGHT)
        val allTargetsHaveExplicitRight = targetIndices.all { idx ->
            lanes[idx].directions.any { it in rightArrows }
        }
        if (!allTargetsHaveExplicitRight) return false
        val lanesToRight = ((targetIndices.last() + 1)..lanes.lastIndex).map { lanes[it] }
        val targetsAreSlightRight = targetIndices.all { idx ->
            LaneDirection.SLIGHT_RIGHT in lanes[idx].directions && LaneDirection.RIGHT !in lanes[idx].directions
        }
        return lanesToRight.all { lane ->
            lane.directions.isNotEmpty() &&
                LaneDirection.THROUGH !in lane.directions &&
                (LaneDirection.RIGHT !in lane.directions || (targetsAreSlightRight && LaneDirection.SHARP_RIGHT in lane.directions || targetsAreSlightRight && lane.directions == listOf(LaneDirection.RIGHT)))
        }
    }

    private fun allowsNonLeftmostTurn(lanes: List<LaneInfo>, targetIndices: List<Int>): Boolean {
        if (targetIndices.last() == lanes.lastIndex) return false
        val leftArrows = setOf(LaneDirection.LEFT, LaneDirection.SHARP_LEFT, LaneDirection.SLIGHT_LEFT)
        val allTargetsHaveExplicitLeft = targetIndices.all { idx ->
            lanes[idx].directions.any { it in leftArrows }
        }
        if (!allTargetsHaveExplicitLeft) return false
        val lanesToLeft = (0 until targetIndices.first()).map { lanes[it] }
        return lanesToLeft.all { lane ->
            lane.directions.isNotEmpty() &&
                LaneDirection.THROUGH !in lane.directions &&
                LaneDirection.LEFT !in lane.directions
        }
    }

    private fun compatibleDirectionsFor(type: ManeuverType): Set<LaneDirection> = when (type) {
        ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT ->
            setOf(LaneDirection.LEFT, LaneDirection.SLIGHT_LEFT, LaneDirection.SHARP_LEFT)
        ManeuverType.SHARP_LEFT ->
            setOf(LaneDirection.SHARP_LEFT, LaneDirection.LEFT, LaneDirection.SLIGHT_LEFT, LaneDirection.U_TURN_LEFT)
        ManeuverType.U_TURN_LEFT ->
            setOf(LaneDirection.U_TURN_LEFT, LaneDirection.SHARP_LEFT, LaneDirection.LEFT)
        ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT ->
            setOf(LaneDirection.LEFT, LaneDirection.SLIGHT_LEFT, LaneDirection.SHARP_LEFT, LaneDirection.MERGE_LEFT)

        ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT ->
            setOf(LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT, LaneDirection.SHARP_RIGHT)
        ManeuverType.SHARP_RIGHT ->
            setOf(LaneDirection.SHARP_RIGHT, LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT, LaneDirection.U_TURN_RIGHT)
        ManeuverType.U_TURN_RIGHT ->
            setOf(LaneDirection.U_TURN_RIGHT, LaneDirection.SHARP_RIGHT, LaneDirection.RIGHT)
        ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT ->
            setOf(LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT, LaneDirection.SHARP_RIGHT, LaneDirection.MERGE_RIGHT)

        ManeuverType.STAY_LEFT ->
            setOf(LaneDirection.THROUGH, LaneDirection.SLIGHT_LEFT, LaneDirection.LEFT, LaneDirection.MERGE_LEFT)
        ManeuverType.STAY_RIGHT ->
            setOf(LaneDirection.THROUGH, LaneDirection.SLIGHT_RIGHT, LaneDirection.RIGHT, LaneDirection.MERGE_RIGHT)

        ManeuverType.ARRIVE_LEFT ->
            setOf(LaneDirection.THROUGH, LaneDirection.SLIGHT_LEFT, LaneDirection.LEFT)
        ManeuverType.ARRIVE_RIGHT ->
            setOf(LaneDirection.THROUGH, LaneDirection.SLIGHT_RIGHT, LaneDirection.RIGHT)

        ManeuverType.STRAIGHT,
        ManeuverType.MERGE,
        ManeuverType.DEPART,
        ManeuverType.ROUNDABOUT,
        ManeuverType.FERRY,
        ManeuverType.ARRIVE ->
            setOf(
                LaneDirection.THROUGH,
                LaneDirection.SLIGHT_LEFT,
                LaneDirection.SLIGHT_RIGHT,
                LaneDirection.MERGE_LEFT,
                LaneDirection.MERGE_RIGHT
            )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return 2.0 * r * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
    }

    private fun angleDiffDegrees(b1: Double, b2: Double): Double {
        val diff = abs((b1 - b2) % 360.0)
        return if (diff > 180.0) 360.0 - diff else diff
    }
}
