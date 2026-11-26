package com.example.navigation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin two-stage maneuver timing & back-to-back intersection planner.
 *
 * Computes preparation and final turn trigger distances from live vehicle speed, remaining route
 * distance, road complexity (lanes to cross / highway / multilane), and a low-speed / post-stop
 * distance fallback so drivers always receive advance preparation notice before acting.
 */
object ManeuverTimingPlanner {

    // Preparation cue lead times (seconds before maneuver)
    const val URBAN_SIMPLE_PREP_SECONDS = 18.0
    const val URBAN_COMPLEX_BASE_PREP_SECONDS = 22.0
    const val URBAN_PER_LANE_PREP_SECONDS = 4.0
    const val HIGHWAY_BASE_PREP_SECONDS = 26.0
    const val HIGHWAY_PER_LANE_PREP_SECONDS = 5.0

    // Low-speed / post-stop distance fallbacks (meters)
    const val URBAN_SIMPLE_MIN_PREP_METERS = 160.0
    const val URBAN_SIMPLE_MAX_PREP_METERS = 365.0
    const val URBAN_COMPLEX_MIN_PREP_METERS = 260.0
    const val URBAN_COMPLEX_MAX_PREP_METERS = 1_100.0
    const val HIGHWAY_MIN_PREP_METERS = 450.0
    const val HIGHWAY_MAX_PREP_METERS = 1_780.0

    // Final short turn cue timing
    const val URBAN_FINAL_TURN_SECONDS = 5.5
    const val URBAN_FINAL_TURN_MIN_METERS = 65.0
    const val HIGHWAY_FINAL_TURN_SECONDS = 6.5
    const val HIGHWAY_FINAL_TURN_MIN_METERS = 140.0

    // Back-to-back intersection preview thresholds
    const val BACK_TO_BACK_MIN_SPACING_METERS = 220.0
    const val BACK_TO_BACK_MAX_SPACING_METERS = 380.0
    const val BACK_TO_BACK_LEAD_SECONDS = 16.0
    const val IMMINENT_NO_COOLDOWN_SECONDS = 9.0

    /**
     * Worst-case number of lane changes required to reach a recommended lane (0 when unknown or all valid).
     */
    fun estimateLanesToCross(maneuver: RouteManeuver): Int {
        val lanes = maneuver.lanes
        if (lanes.size < 2) return 0
        val targets = lanes.mapIndexedNotNull { idx, lane ->
            val isTarget = lane.highlightTier != LaneHighlightTier.INACTIVE ||
                lane.isActive || lane.isRecommended || lane.isValid
            if (isTarget) idx else null
        }
        if (targets.isEmpty() || targets.size == lanes.size) return 0
        return lanes.indices.maxOf { i -> targets.minOf { t -> abs(i - t) } }
    }

    /**
     * Computes the distance (meters before the maneuver) at which the preparation cue should fire.
     * Uses `speed * leadSeconds` scaled by road complexity, clamped with a low-speed distance fallback.
     */
    fun computePreparationDistanceMeters(
        maneuver: RouteManeuver,
        approachManeuver: RouteManeuver?,
        speedMps: Float,
        isHighway: Boolean
    ): Double {
        val safeSpeed = speedMps.coerceAtLeast(0f).toDouble()
        val lanesToCross = estimateLanesToCross(maneuver)
        val hasLanes = maneuver.lanes.size >= 2
        val isMultiLane = hasLanes ||
            maneuver.laneCount >= 2 ||
            maneuver.isMultiLaneRoad ||
            (approachManeuver?.laneCount ?: 0) >= 2 ||
            (approachManeuver?.isMultiLaneRoad == true) ||
            ((approachManeuver?.speedLimitMph ?: maneuver.speedLimitMph ?: 0) >= 35)

        return when {
            isHighway -> {
                val leadSec = HIGHWAY_BASE_PREP_SECONDS + lanesToCross.coerceAtMost(5) * HIGHWAY_PER_LANE_PREP_SECONDS
                max(HIGHWAY_MIN_PREP_METERS, safeSpeed * leadSec).coerceIn(HIGHWAY_MIN_PREP_METERS, HIGHWAY_MAX_PREP_METERS)
            }
            hasLanes || isMultiLane -> {
                val leadSec = URBAN_COMPLEX_BASE_PREP_SECONDS + lanesToCross.coerceAtMost(4) * URBAN_PER_LANE_PREP_SECONDS
                max(URBAN_COMPLEX_MIN_PREP_METERS, safeSpeed * leadSec).coerceIn(URBAN_COMPLEX_MIN_PREP_METERS, URBAN_COMPLEX_MAX_PREP_METERS)
            }
            else -> {
                max(URBAN_SIMPLE_MIN_PREP_METERS, safeSpeed * URBAN_SIMPLE_PREP_SECONDS)
                    .coerceIn(URBAN_SIMPLE_MIN_PREP_METERS, URBAN_SIMPLE_MAX_PREP_METERS)
            }
        }
    }

    /**
     * Computes the distance (meters before the maneuver) at which the short final turn cue fires.
     */
    fun computeFinalTurnDistanceMeters(
        speedMps: Float,
        isHighway: Boolean
    ): Double {
        val safeSpeed = speedMps.coerceAtLeast(0f).toDouble()
        return if (isHighway) {
            max(HIGHWAY_FINAL_TURN_MIN_METERS, safeSpeed * HIGHWAY_FINAL_TURN_SECONDS)
        } else {
            max(URBAN_FINAL_TURN_MIN_METERS, safeSpeed * URBAN_FINAL_TURN_SECONDS)
        }
    }

    /**
     * Ensures the dedicated lane panel appears well before any voice preparation or Turn Focus cue begins.
     */
    fun lanePanelLeadDistanceMeters(
        maneuver: RouteManeuver,
        approachManeuver: RouteManeuver?,
        speedMps: Float,
        isHighway: Boolean,
        baseLaneWindowMeters: Double
    ): Double {
        val prepDist = computePreparationDistanceMeters(maneuver, approachManeuver, speedMps, isHighway)
        val turnFocusFloor = if (isHighway) {
            TurnFocusPlanner.MAX_WIDE_HIGHWAY_DISTANCE_METERS
        } else {
            TurnFocusPlanner.DEFAULT_CITY_DISTANCE_METERS
        }
        return maxOf(baseLaneWindowMeters, prepDist, turnFocusFloor)
    }

    /**
     * Returns the immediate follow-up maneuver when two turns are closely spaced back-to-back
     * so the voice instruction can preview it during the first maneuver (e.g., "Turn left, then
     * take the next right.").
     */
    fun findBackToBackFollowUp(
        route: OfflineRoute?,
        upcomingManeuverIdx: Int,
        speedMps: Float
    ): RouteManeuver? {
        if (route == null) return null
        val upcoming = route.maneuvers.getOrNull(upcomingManeuverIdx) ?: return null
        val next = route.maneuvers.getOrNull(upcomingManeuverIdx + 1) ?: return null
        if (!isPreviewableFollowUpType(next.type)) return null

        val spacingMeters = computeSpacingBetweenManeuversMeters(route, upcoming, next)
        val dynamicThreshold = max(
            BACK_TO_BACK_MIN_SPACING_METERS,
            min(BACK_TO_BACK_MAX_SPACING_METERS, speedMps.coerceAtLeast(0f).toDouble() * BACK_TO_BACK_LEAD_SECONDS)
        )
        return if (spacingMeters in 1.0..dynamicThreshold) next else null
    }

    fun computeSpacingBetweenManeuversMeters(
        route: OfflineRoute,
        first: RouteManeuver,
        second: RouteManeuver
    ): Double {
        val shapeDist = if (route.shape.size >= 2 && second.beginShapeIndex > first.beginShapeIndex) {
            TurnFocusPlanner.distanceAlongShape(route.shape, first.beginShapeIndex, second.beginShapeIndex)
        } else 0.0
        return if (shapeDist > 1.0) shapeDist else first.lengthMeters
    }

    /**
     * Returns true when an upcoming maneuver is imminent enough (or closely spaced after the
     * previous maneuver) that inter-maneuver cooldown must NOT delay its announcement.
     */
    fun shouldBypassInterManeuverCooldown(
        stage: String,
        distToNextManeuver: Double,
        speedMps: Float,
        isHighway: Boolean,
        approachManeuver: RouteManeuver?
    ): Boolean {
        if (stage == NavigationSessionController.STAGE_ARRIVED) return true
        val finalDist = computeFinalTurnDistanceMeters(speedMps, isHighway)
        if ((stage == NavigationSessionController.STAGE_CITY_NOW ||
                stage == NavigationSessionController.STAGE_HW_NOW ||
                stage == NavigationSessionController.STAGE_BIKE_30M) &&
            distToNextManeuver <= finalDist + 20.0
        ) {
            return true
        }
        val effectiveSpeed = speedMps.coerceAtLeast(4.0f).toDouble()
        val timeRemainingSec = distToNextManeuver / effectiveSpeed
        if (timeRemainingSec <= IMMINENT_NO_COOLDOWN_SECONDS) return true
        val prevSpacing = approachManeuver?.lengthMeters ?: Double.MAX_VALUE
        return prevSpacing in 1.0..BACK_TO_BACK_MIN_SPACING_METERS &&
            distToNextManeuver <= BACK_TO_BACK_MIN_SPACING_METERS
    }

    private fun isPreviewableFollowUpType(type: ManeuverType): Boolean = when (type) {
        ManeuverType.LEFT,
        ManeuverType.SLIGHT_LEFT,
        ManeuverType.SHARP_LEFT,
        ManeuverType.RIGHT,
        ManeuverType.SLIGHT_RIGHT,
        ManeuverType.SHARP_RIGHT,
        ManeuverType.U_TURN_LEFT,
        ManeuverType.U_TURN_RIGHT,
        ManeuverType.EXIT_LEFT,
        ManeuverType.EXIT_RIGHT,
        ManeuverType.RAMP_LEFT,
        ManeuverType.RAMP_RIGHT,
        ManeuverType.STAY_LEFT,
        ManeuverType.STAY_RIGHT,
        ManeuverType.ROUNDABOUT -> true
        else -> false
    }
}
