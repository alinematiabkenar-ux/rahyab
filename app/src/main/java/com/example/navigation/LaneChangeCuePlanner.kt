package com.example.navigation

import kotlin.math.abs
import kotlin.math.max

enum class LaneChangeTiming(val code: String, val multiplier: Double) {
    EARLY("early", 1.3),
    NORMAL("normal", 1.0),
    LATE("late", 0.8);

    companion object {
        fun fromCode(code: String?): LaneChangeTiming =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: NORMAL
    }
}

enum class LaneTargetSide {
    LEFT,
    RIGHT,
    MIDDLE
}

enum class LaneChangeCueKind {
    PRIMARY,
    LAST_CHANCE
}

data class LaneChangeCuePlan(
    val cueDistanceMeters: Double,
    val lastChanceDistanceMeters: Double?,
    val targetSide: LaneTargetSide,
    val lanesToCross: Int,
    val isHighway: Boolean
)

data class LaneChangeCueEvent(
    val kind: LaneChangeCueKind,
    val maneuverIndex: Int,
    val targetSide: LaneTargetSide,
    val plan: LaneChangeCuePlan,
    val mergeWithHalfMile: Boolean
)

/**
 * Deterministic, offline lane-change cue distance & side planner (L1).
 *
 * Computes when a driver needs to start moving toward target lanes based on:
 * - Worst-case lane count to cross (`lanesToCross`)
 * - Current road type (highway vs city) and vehicle speed
 * - User timing preference ([LaneChangeTiming]: EARLY 1.3x, NORMAL 1.0x, LATE 0.8x)
 * - Strictly active in [TravelMode.DRIVE] only (never WALK or BIKE).
 */
object LaneChangeCuePlanner {
    const val HIGHWAY_SPEED_THRESHOLD_MPS = 20.0f
    const val HIGHWAY_MIN_PER_LANE_METERS = 120.0
    const val HIGHWAY_SECONDS_PER_LANE = 5.0
    const val HIGHWAY_BUFFER_METERS = 300.0
    const val HIGHWAY_MIN_CUE_METERS = 400.0
    const val HIGHWAY_MAX_CUE_METERS = 3200.0

    const val CITY_MIN_PER_LANE_METERS = 35.0
    const val CITY_SECONDS_PER_LANE = 4.0
    const val CITY_BUFFER_METERS = 50.0
    const val CITY_MIN_CUE_METERS = 60.0
    const val CITY_MAX_CUE_METERS = 400.0

    const val HALF_MILE_METERS = 804.672
    const val HALF_MILE_MERGE_WINDOW_METERS = 150.0

    fun plan(
        maneuver: RouteManeuver,
        speedMps: Float,
        travelMode: TravelMode,
        timing: LaneChangeTiming = LaneChangeTiming.NORMAL
    ): LaneChangeCuePlan? {
        if (travelMode != TravelMode.DRIVE) return null
        val lanes = maneuver.lanes
        if (lanes.size < 2) return null

        val targetIndices = lanes.mapIndexedNotNull { idx, lane ->
            val isTarget = lane.isActive || lane.isRecommended || lane.isValid ||
                lane.highlightTier != LaneHighlightTier.INACTIVE
            if (isTarget) idx else null
        }
        if (targetIndices.isEmpty() || targetIndices.size == lanes.size) return null

        val lanesToCross = lanes.indices.maxOf { i ->
            targetIndices.minOf { t -> abs(i - t) }
        }
        if (lanesToCross <= 0) return null

        val safeSpeed = speedMps.coerceAtLeast(0f).toDouble()
        val isHighway = maneuver.isHighwayExitOrSplit ||
            maneuver.isHighwayRoad ||
            speedMps >= HIGHWAY_SPEED_THRESHOLD_MPS

        val perLane = if (isHighway) {
            max(HIGHWAY_MIN_PER_LANE_METERS, safeSpeed * HIGHWAY_SECONDS_PER_LANE)
        } else {
            max(CITY_MIN_PER_LANE_METERS, safeSpeed * CITY_SECONDS_PER_LANE)
        }
        val buffer = if (isHighway) HIGHWAY_BUFFER_METERS else CITY_BUFFER_METERS
        val rawCue = (lanesToCross * perLane + buffer) * timing.multiplier
        val cueDistance = if (isHighway) {
            rawCue.coerceIn(HIGHWAY_MIN_CUE_METERS, HIGHWAY_MAX_CUE_METERS)
        } else {
            rawCue.coerceIn(CITY_MIN_CUE_METERS, CITY_MAX_CUE_METERS)
        }
        val lastChanceDistance = if (lanesToCross >= 1) buffer + perLane else null

        val targetSide = resolveTargetSide(targetIndices, lanes.lastIndex)

        return LaneChangeCuePlan(
            cueDistanceMeters = cueDistance,
            lastChanceDistanceMeters = lastChanceDistance,
            targetSide = targetSide,
            lanesToCross = lanesToCross,
            isHighway = isHighway
        )
    }

    fun shouldMergeWithHalfMilePrompt(cueDistanceMeters: Double): Boolean =
        abs(cueDistanceMeters - HALF_MILE_METERS) <= HALF_MILE_MERGE_WINDOW_METERS

    private fun resolveTargetSide(targetIndices: List<Int>, lastIndex: Int): LaneTargetSide {
        val minT = targetIndices.first()
        val maxT = targetIndices.last()
        if (minT == 0 && maxT < lastIndex) return LaneTargetSide.LEFT
        if (maxT == lastIndex && minT > 0) return LaneTargetSide.RIGHT
        val center = lastIndex / 2.0
        val avg = targetIndices.average()
        return when {
            avg < center - 0.25 -> LaneTargetSide.LEFT
            avg > center + 0.25 -> LaneTargetSide.RIGHT
            else -> LaneTargetSide.MIDDLE
        }
    }
}

/**
 * Per-session stateful tracker ensuring each maneuver fires at most one primary lane-change cue
 * and at most one optional last-chance beep.
 */
class LaneChangeCueTracker {
    private val primaryFiredManeuvers = HashSet<Int>()
    private val lastChanceFiredManeuvers = HashSet<Int>()

    fun reset() {
        primaryFiredManeuvers.clear()
        lastChanceFiredManeuvers.clear()
    }

    fun evaluate(
        maneuver: RouteManeuver?,
        distanceToManeuverMeters: Double,
        speedMps: Float,
        travelMode: TravelMode,
        timing: LaneChangeTiming,
        beepsEnabled: Boolean,
        lastChanceEnabled: Boolean
    ): LaneChangeCueEvent? {
        if (!beepsEnabled || maneuver == null || distanceToManeuverMeters <= 20.0) return null
        val plan = LaneChangeCuePlanner.plan(maneuver, speedMps, travelMode, timing) ?: return null
        val idx = maneuver.index

        if (idx !in primaryFiredManeuvers && distanceToManeuverMeters <= plan.cueDistanceMeters) {
            primaryFiredManeuvers.add(idx)
            val mergeHalfMile = LaneChangeCuePlanner.shouldMergeWithHalfMilePrompt(plan.cueDistanceMeters) &&
                abs(distanceToManeuverMeters - LaneChangeCuePlanner.HALF_MILE_METERS) <=
                LaneChangeCuePlanner.HALF_MILE_MERGE_WINDOW_METERS
            return LaneChangeCueEvent(
                kind = LaneChangeCueKind.PRIMARY,
                maneuverIndex = idx,
                targetSide = plan.targetSide,
                plan = plan,
                mergeWithHalfMile = mergeHalfMile
            )
        }

        val lastChanceDist = plan.lastChanceDistanceMeters
        if (lastChanceEnabled &&
            lastChanceDist != null &&
            idx in primaryFiredManeuvers &&
            idx !in lastChanceFiredManeuvers &&
            lastChanceDist < plan.cueDistanceMeters - 25.0 &&
            distanceToManeuverMeters <= lastChanceDist
        ) {
            lastChanceFiredManeuvers.add(idx)
            return LaneChangeCueEvent(
                kind = LaneChangeCueKind.LAST_CHANCE,
                maneuverIndex = idx,
                targetSide = plan.targetSide,
                plan = plan,
                mergeWithHalfMile = false
            )
        }

        return null
    }
}
