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
    val shouldBeep: Boolean = false,
    val justEnded: Boolean = false
)

/**
 * F1: Pure Kotlin Turn Focus mode planner & stateful per-session tracker.
 *
 * Rules:
 * - Focus maneuvers: LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, SLIGHT_LEFT/SLIGHT_RIGHT (when road
 *   changes), U_TURN_LEFT/RIGHT, RAMP_LEFT/RIGHT, EXIT_LEFT/RIGHT, STAY_LEFT/RIGHT (highway
 *   fork/keep), ROUNDABOUT. NOT: STRAIGHT, MERGE, DEPART, ARRIVE/ARRIVE_LEFT/ARRIVE_RIGHT, FERRY.
 * - Trigger distance: city default 500 m, highway default 1000 m.
 * - Wide-highway safety rule (when ON): on highways, if [LaneChangeCuePlanner] computes
 *   `lanesToCross >= 3`, trigger distance is `max(highwayDistance, cueDistance).coerceAtMost(1600.0)`.
 * - Previous-maneuver rule: if distance from previous maneuver < triggerDistance, wait until
 *   the car is at least 30 m past the previous maneuver before triggering.
 * - Too-late rule: if a maneuver first becomes the next maneuver when already < 150 m (city)
 *   or < 300 m (highway) away (e.g. after a reroute), enter focus visually with `shouldBeep = false`.
 * - End: 50 m past maneuver in city, 150 m past maneuver on highway exits, or immediately on
 *   off-route, reroute, arrival, stop, or non-DRIVE mode.
 * - Triggers at most once per maneuver index.
 */
object TurnFocusPlanner {
    const val DEFAULT_CITY_DISTANCE_METERS = 500.0
    const val DEFAULT_HIGHWAY_DISTANCE_METERS = 1000.0
    const val MAX_WIDE_HIGHWAY_DISTANCE_METERS = 1600.0
    const val PREVIOUS_MANEUVER_CLEARANCE_METERS = 30.0
    const val TOO_LATE_CITY_METERS = 150.0
    const val TOO_LATE_HIGHWAY_METERS = 300.0
    const val END_PAST_CITY_METERS = 50.0
    const val END_PAST_HIGHWAY_METERS = 150.0
    const val FOCUS_ZOOM_HIGHWAY = 17.3
    const val FOCUS_ZOOM_CITY = 18.0
    const val FOCUS_TILT_3D = 55.0

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
            ManeuverType.LEFT,
            ManeuverType.RIGHT,
            ManeuverType.SHARP_LEFT,
            ManeuverType.SHARP_RIGHT,
            ManeuverType.U_TURN_LEFT,
            ManeuverType.U_TURN_RIGHT,
            ManeuverType.RAMP_LEFT,
            ManeuverType.RAMP_RIGHT,
            ManeuverType.EXIT_LEFT,
            ManeuverType.EXIT_RIGHT,
            ManeuverType.ROUNDABOUT -> true

            ManeuverType.SLIGHT_LEFT,
            ManeuverType.SLIGHT_RIGHT -> isRoadChangeForSlightTurn(maneuver, previousManeuver, highway)

            ManeuverType.STAY_LEFT,
            ManeuverType.STAY_RIGHT -> highway

            ManeuverType.STRAIGHT,
            ManeuverType.MERGE,
            ManeuverType.DEPART,
            ManeuverType.ARRIVE,
            ManeuverType.ARRIVE_LEFT,
            ManeuverType.ARRIVE_RIGHT,
            ManeuverType.FERRY -> false
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
            return config.cityDistanceMeters.coerceIn(200.0, 1000.0)
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
        if (curName.isNotEmpty() && prevName.isNotEmpty()) {
            return curName != prevName
        }
        return true
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
        ManeuverType.RAMP_LEFT,
        ManeuverType.RAMP_RIGHT,
        ManeuverType.EXIT_LEFT,
        ManeuverType.EXIT_RIGHT
    )
}

/**
 * Stateful per-navigation-session tracker for Turn Focus mode (F1).
 */
class TurnFocusSessionTracker {
    private val triggeredManeuverIndices = HashSet<Int>()
    private val firstObservedDistanceByManeuver = HashMap<Int, Double>()

    private var activeManeuverIndex: Int = -1
    private var activeIsHighway: Boolean = false
    private var activeTriggerDistance: Double = 0.0
    private var activeBeginShapeIndex: Int = -1

    fun reset() {
        triggeredManeuverIndices.clear()
        firstObservedDistanceByManeuver.clear()
        clearActive()
    }

    fun evaluate(
        route: OfflineRoute?,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        distanceToNextManeuverMeters: Double,
        speedMps: Float,
        travelMode: TravelMode,
        isNavigating: Boolean,
        isOffRoute: Boolean,
        isRerouting: Boolean,
        hasArrived: Boolean,
        config: TurnFocusConfig
    ): TurnFocusDecision {
        val wasActive = activeManeuverIndex >= 0
        if (!config.enabled || !isNavigating || isOffRoute || isRerouting || hasArrived ||
            travelMode != TravelMode.DRIVE || route == null || route.shape.size < 2
        ) {
            clearActive()
            return TurnFocusDecision(isActive = false, justEnded = wasActive)
        }

        // 1. If currently in focus, check whether we passed the active maneuver beyond its end distance
        if (wasActive) {
            val pastActive = snappedShapeIndex > activeBeginShapeIndex || currentManeuverIndex > activeManeuverIndex
            if (pastActive) {
                val distPast = TurnFocusPlanner.distanceAlongShape(route.shape, activeBeginShapeIndex, snappedShapeIndex)
                val endDist = TurnFocusPlanner.endPastDistanceMeters(activeIsHighway)
                if (distPast >= endDist) {
                    clearActive()
                    // Fall through to see if the new current maneuver should immediately enter focus
                } else {
                    // Check if we already moved on to another maneuver that is ready to take over
                    val nextCandidate = tryStartForCurrentManeuver(
                        route = route,
                        currentManeuverIndex = currentManeuverIndex,
                        snappedShapeIndex = snappedShapeIndex,
                        distanceToNextManeuverMeters = distanceToNextManeuverMeters,
                        speedMps = speedMps,
                        config = config
                    )
                    if (nextCandidate != null) return nextCandidate
                    return TurnFocusDecision(
                        isActive = true,
                        maneuverIndex = activeManeuverIndex,
                        isHighway = activeIsHighway,
                        triggerDistanceMeters = activeTriggerDistance
                    )
                }
            } else {
                return TurnFocusDecision(
                    isActive = true,
                    maneuverIndex = activeManeuverIndex,
                    isHighway = activeIsHighway,
                    triggerDistanceMeters = activeTriggerDistance
                )
            }
        }

        // 2. Not currently active (or just ended above): check if current maneuver triggers focus
        val started = tryStartForCurrentManeuver(
            route = route,
            currentManeuverIndex = currentManeuverIndex,
            snappedShapeIndex = snappedShapeIndex,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            speedMps = speedMps,
            config = config
        )
        if (started != null) return started

        return TurnFocusDecision(isActive = false, justEnded = wasActive)
    }

    private fun tryStartForCurrentManeuver(
        route: OfflineRoute,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        distanceToNextManeuverMeters: Double,
        speedMps: Float,
        config: TurnFocusConfig
    ): TurnFocusDecision? {
        val cur = route.maneuvers.getOrNull(currentManeuverIndex) ?: return null
        val prev = route.maneuvers.getOrNull(currentManeuverIndex - 1)
        val idx = cur.index

        val firstDist = firstObservedDistanceByManeuver.getOrPut(idx) { distanceToNextManeuverMeters }

        if (idx in triggeredManeuverIndices) return null
        if (!TurnFocusPlanner.isFocusManeuver(cur, prev)) return null

        val isHighway = TurnFocusPlanner.isHighwayContext(cur, prev)
        val triggerDist = TurnFocusPlanner.computeTriggerDistanceMeters(cur, prev, speedMps, config)
        if (distanceToNextManeuverMeters > triggerDist) return null

        // Previous-maneuver rule: if previous maneuver is closer than triggerDist, wait until >= 30 m past it
        if (prev != null && prev.type != ManeuverType.DEPART) {
            val spacing = TurnFocusPlanner.distanceAlongShape(route.shape, prev.beginShapeIndex, cur.beginShapeIndex)
            if (spacing < triggerDist) {
                val distPastPrev = TurnFocusPlanner.distanceAlongShape(route.shape, prev.beginShapeIndex, snappedShapeIndex)
                if (snappedShapeIndex <= prev.beginShapeIndex ||
                    distPastPrev < TurnFocusPlanner.PREVIOUS_MANEUVER_CLEARANCE_METERS
                ) {
                    return null
                }
            }
        }

        // Trigger once per maneuver
        triggeredManeuverIndices.add(idx)
        activeManeuverIndex = idx
        activeIsHighway = isHighway
        activeTriggerDistance = triggerDist
        activeBeginShapeIndex = cur.beginShapeIndex

        val tooLateThreshold = TurnFocusPlanner.tooLateThresholdMeters(isHighway)
        val isTooLate = firstDist < tooLateThreshold
        val shouldBeep = config.beepsEnabled && !isTooLate

        return TurnFocusDecision(
            isActive = true,
            maneuverIndex = idx,
            isHighway = isHighway,
            triggerDistanceMeters = triggerDist,
            justEntered = true,
            shouldBeep = shouldBeep
        )
    }

    private fun clearActive() {
        activeManeuverIndex = -1
        activeIsHighway = false
        activeTriggerDistance = 0.0
        activeBeginShapeIndex = -1
    }
}
