package com.example.navigation

import com.example.data.search.RoadHazardPoint

/**
 * Stateful per-navigation-session tracker for two-stage Turn Focus mode.
 * Manages 1 km approach entry, offline traffic-signal lane-level tightening, close-turn
 * handoff without zooming out, and completion-based release on outgoing road/ramp establishment.
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
    private var activeEndShapeIndex: Int = -1
    private var activeCloseFollowDist: Double? = null
    private var activeStage: TurnFocusStage = TurnFocusStage.NONE
    private var activeTargetZoom: Double = 0.0
    private var activeMatchedSignal: MatchedApproachSignal = MatchedApproachSignal()

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
        gpsAgeMs: Long = 0L,
        snappedBearing: Float? = null,
        trafficSignals: List<RoadHazardPoint> = emptyList()
    ): TurnFocusDecision {
        val wasActive = activeManeuverIndex >= 0
        if (!config.enabled || !isNavigating || isOffRoute || isRerouting || hasArrived ||
            travelMode != TravelMode.DRIVE || route == null || route.shape.size < 2
        ) {
            clearActive()
            return TurnFocusDecision(isActive = false, justEnded = wasActive)
        }

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

        // 1. Currently in focus on this route: update stage, handoff, or completion release
        if (activeManeuverIndex >= 0) {
            val activeProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, activeBeginShapeIndex)
            val pastActive = vehicleProgress > activeProgress + 0.5 ||
                snappedShapeIndex > activeBeginShapeIndex ||
                currentManeuverIndex > activeListIndex
            if (pastActive) {
                val completed = TurnFocusStageHelper.isManeuverCompleted(
                    route = route,
                    activeBeginShapeIndex = activeBeginShapeIndex,
                    activeEndShapeIndex = activeEndShapeIndex,
                    activeIsHighway = activeIsHighway,
                    vehicleProgressMeters = vehicleProgress,
                    snappedBearing = snappedBearing,
                    speedMps = speedMps
                )
                if (completed) {
                    clearActive()
                } else {
                    // Direct close-turn handoff without zooming out and back in
                    val nextCandidate = tryStartForCurrentManeuver(
                        route = route,
                        currentManeuverIndex = currentManeuverIndex,
                        snappedShapeIndex = snappedShapeIndex,
                        vehicleProgressMeters = vehicleProgress,
                        distanceToNextManeuverMeters = distanceToNextManeuverMeters,
                        speedMps = speedMps,
                        gpsAgeMs = gpsAgeMs,
                        config = config,
                        trafficSignals = trafficSignals,
                        preserveTightHandoff = activeStage == TurnFocusStage.LANE_LEVEL
                    )
                    if (nextCandidate != null) return nextCandidate

                    // Executing the turn/exit split: lock at LANE_LEVEL until established on outgoing leg
                    val justTightened = activeStage != TurnFocusStage.LANE_LEVEL
                    activeStage = TurnFocusStage.LANE_LEVEL
                    activeTargetZoom = maxOf(
                        activeTargetZoom,
                        TurnFocusStageHelper.computeTargetZoom(
                            TurnFocusStage.LANE_LEVEL, 0.0, activeIsHighway, activeCloseFollowDist != null
                        )
                    )
                    return buildActiveDecision(justTightened = justTightened)
                }
            } else {
                val activeManeuver = route.maneuvers.getOrNull(activeListIndex)
                val prevManeuver = route.maneuvers.getOrNull(activeListIndex - 1)
                if (activeManeuver != null) {
                    activeMatchedSignal = RouteTrafficSignalMatcher.findFinalSignalBeforeManeuver(
                        route = route,
                        maneuver = activeManeuver,
                        previousManeuver = prevManeuver,
                        vehicleProgressMeters = vehicleProgress,
                        approachWindowMeters = activeTriggerDistance,
                        signals = trafficSignals
                    )
                }
                val remainingToActive = (activeProgress - vehicleProgress).coerceAtLeast(0.0)
                val shouldTighten = activeStage == TurnFocusStage.LANE_LEVEL ||
                    TurnFocusStageHelper.shouldEnterLaneLevel(
                        remainingMeters = remainingToActive,
                        speedMps = speedMps,
                        isHighway = activeIsHighway,
                        isCloseSecondTurn = false,
                        matchedSignal = activeMatchedSignal
                    )
                val justTightened = activeStage != TurnFocusStage.LANE_LEVEL && shouldTighten
                if (shouldTighten) activeStage = TurnFocusStage.LANE_LEVEL
                val desiredZoom = TurnFocusStageHelper.computeTargetZoom(
                    stage = activeStage,
                    remainingMeters = remainingToActive,
                    isHighway = activeIsHighway,
                    isClosePair = activeCloseFollowDist != null
                )
                activeTargetZoom = maxOf(activeTargetZoom, desiredZoom)
                return buildActiveDecision(justTightened = justTightened)
            }
        }

        // 2. Not currently active (or just completed above): check current maneuver
        val started = tryStartForCurrentManeuver(
            route = route,
            currentManeuverIndex = currentManeuverIndex,
            snappedShapeIndex = snappedShapeIndex,
            vehicleProgressMeters = vehicleProgress,
            distanceToNextManeuverMeters = distanceToNextManeuverMeters,
            speedMps = speedMps,
            gpsAgeMs = gpsAgeMs,
            config = config,
            trafficSignals = trafficSignals,
            preserveTightHandoff = false
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
        config: TurnFocusConfig,
        trafficSignals: List<RoadHazardPoint>,
        preserveTightHandoff: Boolean
    ): TurnFocusDecision? {
        val cur = route.maneuvers.getOrNull(currentManeuverIndex) ?: return null
        val prev = route.maneuvers.getOrNull(currentManeuverIndex - 1)
        val idx = cur.index

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

        var isCloseSecondTurn = false
        if (prev != null && prev.type != ManeuverType.DEPART) {
            val prevProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, prev.beginShapeIndex)
            val spacing = (curProgress - prevProgress).coerceAtLeast(0.0)
            if (spacing < triggerDist) {
                val clearance = TurnFocusPlanner.effectivePreviousClearanceMeters(spacing)
                val distPastPrev = (vehicleProgressMeters - prevProgress).coerceAtLeast(0.0)
                val passedPrevVertex = vehicleProgressMeters > prevProgress + 0.5 || snappedShapeIndex > prev.beginShapeIndex
                if (!passedPrevVertex || distPastPrev < clearance) return null
                isCloseSecondTurn = spacing <= TurnFocusPlanner.CLOSE_TURN_SPACING_METERS
            }
        }

        val nextFollowing = route.maneuvers.getOrNull(currentManeuverIndex + 1)
        val closeFollowDist = if (nextFollowing != null && TurnFocusPlanner.isFocusManeuver(nextFollowing, cur)) {
            val nextProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, nextFollowing.beginShapeIndex)
            val gap = (nextProgress - curProgress).coerceAtLeast(0.0)
            if (gap in 1.0..TurnFocusPlanner.CLOSE_TURN_SPACING_METERS) gap else null
        } else null

        val matchedSignal = RouteTrafficSignalMatcher.findFinalSignalBeforeManeuver(
            route = route,
            maneuver = cur,
            previousManeuver = prev,
            vehicleProgressMeters = vehicleProgressMeters,
            approachWindowMeters = triggerDist,
            signals = trafficSignals
        )
        val startTight = TurnFocusStageHelper.shouldEnterLaneLevel(
            remainingMeters = effectiveDist,
            speedMps = speedMps,
            isHighway = isHighway,
            isCloseSecondTurn = isCloseSecondTurn || preserveTightHandoff,
            matchedSignal = matchedSignal
        )
        val initialStage = if (startTight) TurnFocusStage.LANE_LEVEL else TurnFocusStage.APPROACH
        val initialZoom = TurnFocusStageHelper.computeTargetZoom(
            stage = initialStage,
            remainingMeters = effectiveDist,
            isHighway = isHighway,
            isClosePair = closeFollowDist != null
        )
        val enterAnimMs = TurnFocusPlanner.computeRecommendedEnterAnimMs(
            effectiveRemainingMeters = effectiveDist,
            speedMps = speedMps,
            isCloseSecondTurn = isCloseSecondTurn
        )

        triggeredManeuverIndices.add(idx)
        activeManeuverIndex = idx
        activeListIndex = currentManeuverIndex
        activeIsHighway = isHighway
        activeTriggerDistance = triggerDist
        activeBeginShapeIndex = cur.beginShapeIndex
        activeEndShapeIndex = cur.endShapeIndex
        activeCloseFollowDist = closeFollowDist
        activeStage = initialStage
        activeTargetZoom = initialZoom
        activeMatchedSignal = matchedSignal

        val tooLateThreshold = TurnFocusPlanner.tooLateThresholdMeters(isHighway)
        val shouldBeep = config.beepsEnabled && firstDist >= tooLateThreshold

        return TurnFocusDecision(
            isActive = true,
            maneuverIndex = idx,
            isHighway = isHighway,
            triggerDistanceMeters = triggerDist,
            justEntered = true,
            justTightenedToLaneLevel = initialStage == TurnFocusStage.LANE_LEVEL,
            shouldBeep = shouldBeep,
            recommendedEnterAnimMs = enterAnimMs,
            closeFollowingTurnDistanceMeters = closeFollowDist,
            stage = initialStage,
            targetZoom = initialZoom,
            matchedSignal = matchedSignal
        )
    }

    fun applyToState(
        state: ActiveNavigationState,
        speedMps: Float,
        settings: com.example.data.preferences.UserSettings,
        trafficSignals: List<RoadHazardPoint> = emptyList()
    ): ActiveNavigationState {
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
            config = cfg, snappedLocation = state.snappedLocation, gpsAgeMs = state.lastGpsFixAgeMs,
            snappedBearing = state.snappedBearing, trafficSignals = trafficSignals
        )
        val showPanel = state.showLaneStrip || (decision.isActive && state.laneDecision?.shouldShowFullDiagram == true)
        return state.copy(
            showLaneStrip = showPanel,
            turnFocusActive = decision.isActive,
            turnFocusCameraActive = decision.isActive,
            turnFocusIsHighway = if (decision.isActive) decision.isHighway else false,
            turnFocusManeuverIndex = if (decision.isActive) decision.maneuverIndex else -1,
            turnFocusTriggerDistanceMeters = if (decision.isActive) decision.triggerDistanceMeters else 0.0,
            turnFocusEnterAnimMs = if (decision.justEntered) decision.recommendedEnterAnimMs else state.turnFocusEnterAnimMs,
            turnFocusClosePair = decision.isActive && decision.closeFollowingTurnDistanceMeters != null,
            turnFocusStage = if (decision.isActive) decision.stage else TurnFocusStage.NONE,
            turnFocusTargetZoom = if (decision.isActive) decision.targetZoom else 0.0,
            turnFocusFinalSignalDistanceBeforeManeuverMeters = if (decision.isActive && decision.matchedSignal.kind != ApproachSignalKind.NONE) {
                decision.matchedSignal.distanceBeforeManeuverMeters
            } else null,
            turnFocusPassedFinalSignal = decision.isActive && decision.matchedSignal.hasPassedSignal
        )
    }

    private fun buildActiveDecision(justTightened: Boolean): TurnFocusDecision = TurnFocusDecision(
        isActive = true,
        maneuverIndex = activeManeuverIndex,
        isHighway = activeIsHighway,
        triggerDistanceMeters = activeTriggerDistance,
        justTightenedToLaneLevel = justTightened,
        closeFollowingTurnDistanceMeters = activeCloseFollowDist,
        stage = activeStage,
        targetZoom = activeTargetZoom,
        matchedSignal = activeMatchedSignal
    )

    private fun clearActive() {
        activeManeuverIndex = -1
        activeListIndex = -1
        activeIsHighway = false
        activeTriggerDistance = 0.0
        activeBeginShapeIndex = -1
        activeEndShapeIndex = -1
        activeCloseFollowDist = null
        activeStage = TurnFocusStage.NONE
        activeTargetZoom = 0.0
        activeMatchedSignal = MatchedApproachSignal()
    }
}
