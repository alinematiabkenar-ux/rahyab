package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine

/**
 * Configuration snapshot for [TurnFocusPlanner] and [TurnFocusSessionTracker].
 */
data class TurnFocusConfig(
    val enabled: Boolean = true,
    val beepsEnabled: Boolean = true,
    val cityDistanceMeters: Double = TurnFocusPlanner.DEFAULT_CITY_DISTANCE_METERS,
    val highwayDistanceMeters: Double = TurnFocusPlanner.DEFAULT_HIGHWAY_DISTANCE_METERS,
    val wideHighwayEarlierEnabled: Boolean = true
)

/**
 * Pure decision result produced by [TurnFocusSessionTracker.evaluate] on each navigation step.
 */
data class TurnFocusDecision(
    val isActive: Boolean = false,
    val maneuverIndex: Int = -1,
    val isHighway: Boolean = false,
    val triggerDistanceMeters: Double = 0.0,
    val justEntered: Boolean = false,
    val justTightenedToLaneLevel: Boolean = false,
    val shouldBeep: Boolean = false,
    val justEnded: Boolean = false,
    val recommendedEnterAnimMs: Int = TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS,
    val closeFollowingTurnDistanceMeters: Double? = null,
    val stage: TurnFocusStage = TurnFocusStage.NONE,
    val targetZoom: Double = 0.0,
    val matchedSignal: MatchedApproachSignal = MatchedApproachSignal()
)

/**
 * F1: Pure Kotlin Turn Focus planner constants and maneuver classification helpers.
 * Stateful per-session tracking lives in [TurnFocusSessionTracker].
 */
object TurnFocusPlanner {
    const val DEFAULT_CITY_DISTANCE_METERS = 1000.0
    const val DEFAULT_HIGHWAY_DISTANCE_METERS = 1000.0
    const val MAX_WIDE_HIGHWAY_DISTANCE_METERS = 1600.0
    const val PREVIOUS_MANEUVER_CLEARANCE_METERS = 30.0
    const val CLOSE_TURN_SPACING_METERS = 60.0
    const val CLOSE_TURN_CLEARANCE_METERS = 0.5
    const val TOO_LATE_CITY_METERS = 150.0
    const val TOO_LATE_HIGHWAY_METERS = 300.0
    const val END_PAST_CITY_METERS = 50.0
    const val END_PAST_HIGHWAY_METERS = 150.0
    const val FOCUS_ZOOM_HIGHWAY = 17.3
    const val FOCUS_ZOOM_CITY = 18.0
    const val FOCUS_ZOOM_CLOSE_PAIR = 17.45
    const val FOCUS_TILT_3D = 55.0
    const val DEFAULT_ENTER_ANIM_MS = 1200
    const val MIN_ENTER_ANIM_MS = 200
    const val CLOSE_TURN_MAX_ANIM_MS = 420

    fun isHighwayContext(maneuver: RouteManeuver, previousManeuver: RouteManeuver? = null): Boolean {
        return maneuver.isHighwayRoad ||
            maneuver.isHighwayExitOrSplit ||
            maneuver.type in HIGHWAY_TYPES ||
            previousManeuver?.isHighwayRoad == true ||
            previousManeuver?.isHighwayExitOrSplit == true
    }

    fun isFocusManeuver(maneuver: RouteManeuver, previousManeuver: RouteManeuver? = null): Boolean {
        val highway = isHighwayContext(maneuver, previousManeuver)
        return when (maneuver.type) {
            ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT,
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT, ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT,
            ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT, ManeuverType.ROUNDABOUT -> true
            ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT -> isRoadChangeForSlightTurn(maneuver, previousManeuver, highway)
            ManeuverType.STAY_LEFT, ManeuverType.STAY_RIGHT -> highway
            ManeuverType.STRAIGHT, ManeuverType.MERGE, ManeuverType.DEPART,
            ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT, ManeuverType.FERRY -> false
        }
    }

    fun computeTriggerDistanceMeters(
        maneuver: RouteManeuver,
        previousManeuver: RouteManeuver?,
        speedMps: Float,
        config: TurnFocusConfig
    ): Double {
        val isHighway = isHighwayContext(maneuver, previousManeuver)
        if (!isHighway) {
            return config.cityDistanceMeters.coerceIn(200.0, 1500.0)
        }
        val baseHighway = config.highwayDistanceMeters.coerceIn(500.0, MAX_WIDE_HIGHWAY_DISTANCE_METERS)
        if (!config.wideHighwayEarlierEnabled) return baseHighway
        val lanePlan = LaneChangeCuePlanner.plan(
            maneuver = maneuver,
            speedMps = speedMps,
            travelMode = TravelMode.DRIVE,
            timing = LaneChangeTiming.NORMAL
        )
        return if (lanePlan != null && lanePlan.lanesToCross >= 3) {
            maxOf(baseHighway, lanePlan.cueDistanceMeters).coerceAtMost(MAX_WIDE_HIGHWAY_DISTANCE_METERS)
        } else {
            baseHighway
        }
    }

    fun effectivePreviousClearanceMeters(spacingMeters: Double): Double =
        if (spacingMeters <= CLOSE_TURN_SPACING_METERS) CLOSE_TURN_CLEARANCE_METERS else PREVIOUS_MANEUVER_CLEARANCE_METERS

    fun computeRecommendedEnterAnimMs(
        effectiveRemainingMeters: Double,
        speedMps: Float,
        isCloseSecondTurn: Boolean
    ): Int {
        if (speedMps <= 1.0f) return if (isCloseSecondTurn) 350 else DEFAULT_ENTER_ANIM_MS
        val timeToManeuverSec = (effectiveRemainingMeters / speedMps.toDouble()).coerceAtLeast(0.0)
        if (isCloseSecondTurn) {
            return (timeToManeuverSec * 1000.0 * 0.22).toInt().coerceIn(MIN_ENTER_ANIM_MS, CLOSE_TURN_MAX_ANIM_MS)
        }
        return if (timeToManeuverSec < 6.0) {
            (timeToManeuverSec * 1000.0 * 0.22).toInt().coerceIn(MIN_ENTER_ANIM_MS, DEFAULT_ENTER_ANIM_MS)
        } else {
            DEFAULT_ENTER_ANIM_MS
        }
    }

    fun endPastDistanceMeters(isHighway: Boolean): Double =
        if (isHighway) END_PAST_HIGHWAY_METERS else END_PAST_CITY_METERS

    fun tooLateThresholdMeters(isHighway: Boolean): Double =
        if (isHighway) TOO_LATE_HIGHWAY_METERS else TOO_LATE_CITY_METERS

    private fun isRoadChangeForSlightTurn(
        maneuver: RouteManeuver,
        previousManeuver: RouteManeuver?,
        isHighway: Boolean
    ): Boolean {
        if (isHighway || maneuver.signpost?.hasContent == true) return true
        val curName = maneuver.streetNames.firstOrNull()?.trim()?.lowercase().orEmpty()
        val prevName = previousManeuver?.streetNames?.firstOrNull()?.trim()?.lowercase().orEmpty()
        return if (curName.isNotEmpty() && prevName.isNotEmpty()) curName != prevName else true
    }

    internal fun distanceAlongShape(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
        if (shape.size < 2) return 0.0
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

    private val HIGHWAY_TYPES = setOf(
        ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT
    )
}
