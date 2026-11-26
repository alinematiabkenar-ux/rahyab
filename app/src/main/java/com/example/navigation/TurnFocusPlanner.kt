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
    val justEnded: Boolean = false,
    val recommendedEnterAnimMs: Int = TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS,
    val closeFollowingTurnDistanceMeters: Double? = null
)

/**
 * F1: Pure Kotlin Turn Focus planner & stateful per-session tracker.
 * Uses exact matched-route progress, immediate handoff for close consecutive turns (<= 60 m),
 * GPS fix-age projection, adaptive enter animation timing, and per-route invalidation.
 */
object TurnFocusPlanner {
    const val DEFAULT_CITY_DISTANCE_METERS = 500.0
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

    fun effectivePreviousClearanceMeters(spacingMeters: Double): Double =
        if (spacingMeters <= CLOSE_TURN_SPACING_METERS) {
            CLOSE_TURN_CLEARANCE_METERS
        } else {
            PREVIOUS_MANEUVER_CLEARANCE_METERS
        }

    fun computeRecommendedEnterAnimMs(
        effectiveRemainingMeters: Double,
        speedMps: Float,
        isCloseSecondTurn: Boolean
    ): Int {
        if (speedMps <= 1.0f) {
            return if (isCloseSecondTurn) 350 else DEFAULT_ENTER_ANIM_MS
        }
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

    private var trackedRouteId: String? = null
    private var activeManeuverIndex: Int = -1
    private var activeListIndex: Int = -1
    private var activeIsHighway: Boolean = false
    private var activeTriggerDistance: Double = 0.0
    private var activeBeginShapeIndex: Int = -1
    private var activeCloseFollowDist: Double? = null

    fun reset() {
        triggeredManeuverIndices.clear()
        firstObservedDistanceByManeuver.clear()
        trackedRouteId = null
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
        config: TurnFocusConfig,
        snappedLocation: LatLon? = null,
        gpsAgeMs: Long = 0L
    ): TurnFocusDecision {
        val wasActive = activeManeuverIndex >= 0
        if (!config.enabled || !isNavigating || isOffRoute || isRerouting || hasArrived ||
            travelMode != TravelMode.DRIVE || route == null || route.shape.size < 2
        ) {
            clearActive()
            return TurnFocusDecision(isActive = false, justEnded = wasActive)
        }

        // Route replacement / reroute invalidates prior maneuver history even if indices repeat
        var routeChanged = false
        if (trackedRouteId != route.id) {
            triggeredManeuverIndices.clear()
            firstObservedDistanceByManeuver.clear()
            clearActive()
            trackedRouteId = route.id
            routeChanged = wasActive
        }

        val vehicleProgress = if (snappedLocation != null) {
            NavigationGeometryHelper.progressAlongRouteMeters(route.shape, snappedLocation, snappedShapeIndex)
        } else {
            NavigationGeometryHelper.vertexProgressMeters(route.shape, snappedShapeIndex)
        }

        // 1. If currently in focus on this route, check whether we passed the active maneuver
        if (activeManeuverIndex >= 0) {
            val activeProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, activeBeginShapeIndex)
            val distPast = (vehicleProgress - activeProgress).coerceAtLeast(0.0)
            val pastActive = vehicleProgress > activeProgress + 0.5 ||
                snappedShapeIndex > activeBeginShapeIndex ||
                currentManeuverIndex > activeListIndex
            if (pastActive) {
                val endDist = TurnFocusPlanner.endPastDistanceMeters(activeIsHighway)
                if (distPast >= endDist) {
                    clearActive()
                    // Fall through to check if the new current maneuver should immediately enter focus
                } else {
                    // Immediately hand off if the next maneuver (e.g. a close turn 25–40 m away) is ready
                    val nextCandidate = tryStartForCurrentManeuver(
                        route = route,
                        currentManeuverIndex = currentManeuverIndex,
                        snappedShapeIndex = snappedShapeIndex,
                        vehicleProgressMeters = vehicleProgress,
                        distanceToNextManeuverMeters = distanceToNextManeuverMeters,
                        speedMps = speedMps,
                        gpsAgeMs = gpsAgeMs,
                        config = config
                    )
                    if (nextCandidate != null) return nextCandidate
                    return TurnFocusDecision(
                        isActive = true,
                        maneuverIndex = activeManeuverIndex,
                        isHighway = activeIsHighway,
                        triggerDistanceMeters = activeTriggerDistance,
                        closeFollowingTurnDistanceMeters = activeCloseFollowDist
                    )
                }
            } else {
                return TurnFocusDecision(
                    isActive = true,
                    maneuverIndex = activeManeuverIndex,
                    isHighway = activeIsHighway,
                    triggerDistanceMeters = activeTriggerDistance,
                    closeFollowingTurnDistanceMeters = activeCloseFollowDist
                )
            }
        }

        // 2. Not currently active (or just ended above): check if current maneuver triggers focus
        val started = tryStartForCurrentManeuver(
            route = route,
            currentManeuverIndex = currentManeuverIndex,
            snappedShapeIndex = snappedShapeIndex,
            vehicleProgressMeters = vehicleProgress,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            speedMps = speedMps,
            gpsAgeMs = gpsAgeMs,
            config = config
        )
        if (started != null) return started

        return TurnFocusDecision(isActive = false, justEnded = wasActive || routeChanged)
    }

    private fun tryStartForCurrentManeuver(
        route: OfflineRoute,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        vehicleProgressMeters: Double,
        distanceToNextManeuverMeters: Double,
        speedMps: Float,
        gpsAgeMs: Long,
        config: TurnFocusConfig
    ): TurnFocusDecision? {
        val cur = route.maneuvers.getOrNull(currentManeuverIndex) ?: return null
        val prev = route.maneuvers.getOrNull(currentManeuverIndex - 1)
        val idx = cur.index

        // Never start focus for an already-passed maneuver (including via sparse GPS gap or stale fix)
        val curProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, cur.beginShapeIndex)
        val gpsAgeSec = gpsAgeMs.coerceAtLeast(0L) / 1000.0
        val effectiveDist = (distanceToNextManeuverMeters - speedMps.coerceAtLeast(0f) * gpsAgeSec).coerceAtLeast(0.0)
        if (vehicleProgressMeters >= curProgress - 0.5 || distanceToNextManeuverMeters <= 0.5 ||
            (gpsAgeMs > 0L && effectiveDist <= 0.5)
        ) {
            return null
        }

        val firstDist = firstObservedDistanceByManeuver.getOrPut(idx) { effectiveDist }

        if (idx in triggeredManeuverIndices) return null
        if (!TurnFocusPlanner.isFocusManeuver(cur, prev)) return null

        val isHighway = TurnFocusPlanner.isHighwayContext(cur, prev)
        val triggerDist = TurnFocusPlanner.computeTriggerDistanceMeters(cur, prev, speedMps, config)
        if (effectiveDist > triggerDist) return null

        // Previous-maneuver clearance: 30 m for normal spacing, immediate handoff for tight 25–40 m pairs
        var isCloseSecondTurn = false
        if (prev != null && prev.type != ManeuverType.DEPART) {
            val prevProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, prev.beginShapeIndex)
            val spacing = (curProgress - prevProgress).coerceAtLeast(0.0)
            if (spacing < triggerDist) {
                val clearance = TurnFocusPlanner.effectivePreviousClearanceMeters(spacing)
                val distPastPrev = (vehicleProgressMeters - prevProgress).coerceAtLeast(0.0)
                val passedPrevVertex = vehicleProgressMeters > prevProgress + 0.5 || snappedShapeIndex > prev.beginShapeIndex
                if (!passedPrevVertex || distPastPrev < clearance) {
                    return null
                }
                isCloseSecondTurn = spacing <= TurnFocusPlanner.CLOSE_TURN_SPACING_METERS
            }
        }

        // Check if another focus turn lies within 25–60 m beyond this turn so both stay framed together
        val nextFollowing = route.maneuvers.getOrNull(currentManeuverIndex + 1)
        val closeFollowDist = if (nextFollowing != null && TurnFocusPlanner.isFocusManeuver(nextFollowing, cur)) {
            val nextProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, nextFollowing.beginShapeIndex)
            val gap = (nextProgress - curProgress).coerceAtLeast(0.0)
            if (gap in 1.0..TurnFocusPlanner.CLOSE_TURN_SPACING_METERS) gap else null
        } else null

        val enterAnimMs = TurnFocusPlanner.computeRecommendedEnterAnimMs(
            effectiveRemainingMeters = effectiveDist,
            speedMps = speedMps,
            isCloseSecondTurn = isCloseSecondTurn
        )

        // Trigger once per maneuver
        triggeredManeuverIndices.add(idx)
        activeManeuverIndex = idx
        activeListIndex = currentManeuverIndex
        activeIsHighway = isHighway
        activeTriggerDistance = triggerDist
        activeBeginShapeIndex = cur.beginShapeIndex
        activeCloseFollowDist = closeFollowDist

        val tooLateThreshold = TurnFocusPlanner.tooLateThresholdMeters(isHighway)
        val isTooLate = firstDist < tooLateThreshold
        val shouldBeep = config.beepsEnabled && !isTooLate

        return TurnFocusDecision(
            isActive = true,
            maneuverIndex = idx,
            isHighway = isHighway,
            triggerDistanceMeters = triggerDist,
            justEntered = true,
            shouldBeep = shouldBeep,
            recommendedEnterAnimMs = enterAnimMs,
            closeFollowingTurnDistanceMeters = closeFollowDist
        )
    }

    fun applyToState(state: ActiveNavigationState, speedMps: Float, settings: com.example.data.preferences.UserSettings): ActiveNavigationState {
        val cfg = TurnFocusConfig(
            enabled = settings.turnFocusModeEnabled,
            beepsEnabled = settings.turnFocusBeepsEnabled,
            cityDistanceMeters = settings.turnFocusCityDistanceMeters.toDouble(),
            highwayDistanceMeters = settings.turnFocusHighwayDistanceMeters.toDouble(),
            wideHighwayEarlierEnabled = settings.turnFocusWideHighwayEarlierEnabled
        )
        val decision = evaluate(
            route = state.route, currentManeuverIndex = state.currentManeuverIndex, snappedShapeIndex = state.snappedShapeIndex,
            distanceToNextManeuverMeters = state.distanceToNextManeuverMeters, speedMps = speedMps, travelMode = state.effectiveTravelMode,
            isNavigating = state.isNavigating, isOffRoute = state.isOffRoute, isRerouting = state.isRerouting, hasArrived = state.hasArrived,
            config = cfg, snappedLocation = state.snappedLocation, gpsAgeMs = state.lastGpsFixAgeMs
        )
        return state.copy(
            turnFocusActive = decision.isActive,
            turnFocusCameraActive = decision.isActive,
            turnFocusIsHighway = if (decision.isActive) decision.isHighway else false,
            turnFocusManeuverIndex = if (decision.isActive) decision.maneuverIndex else -1,
            turnFocusTriggerDistanceMeters = if (decision.isActive) decision.triggerDistanceMeters else 0.0,
            turnFocusEnterAnimMs = if (decision.justEntered) decision.recommendedEnterAnimMs else state.turnFocusEnterAnimMs,
            turnFocusClosePair = decision.isActive && decision.closeFollowingTurnDistanceMeters != null
        )
    }

    private fun clearActive() {
        activeManeuverIndex = -1
        activeListIndex = -1
        activeIsHighway = false
        activeTriggerDistance = 0.0
        activeBeginShapeIndex = -1
        activeCloseFollowDist = null
    }
}
