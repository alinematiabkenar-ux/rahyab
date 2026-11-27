package com.example.navigation

import com.example.data.search.RoadHazardPoint
import com.example.i18n.DistanceUnit

/**
 * Pure threshold-crossing and signal-aware voice schedule policy for:
 * - Highway exits & forks: 2 miles (3,218.688 m), 1 mile (1,609.344 m), 500 meters, and final exit cue.
 * - City turns & urban exits: 1 kilometer (1,000 m), final mapped traffic signal cue (post-signal
 *   or pre-stop-line when the signal controls the target intersection), and final turn cue.
 * - Crossing detection between consecutive GPS updates, late-start/GPS-gap single-cue catchup,
 *   combined simultaneous cues, and suppression of already-passed maneuvers.
 */
internal object ApproachVoiceSchedulePolicy {

    const val HW_2MI_METERS = 3_218.688
    const val HW_1MI_METERS = 1_609.344
    const val HW_500M_METERS = 500.0
    const val CITY_1KM_METERS = 1_000.0

    /** Maximum distance after passing the final mapped signal to phrase as "Take the next right/left". */
    const val NEXT_TURN_MAX_REMAINING_METERS = 280.0
    const val NEXT_TURN_MAX_SIGNAL_GAP_METERS = 320.0

    /** Distance before the stop line at which a target-intersection signal gives lane + prep guidance. */
    const val TARGET_SIGNAL_PRE_STOP_METERS = 260.0

    /** Wide search window so a final traffic signal slightly past 1 km is still detected in order. */
    const val SIGNAL_SEARCH_MIN_WINDOW_METERS = 1_650.0

    private val HIGHWAY_DECISION_TYPES = setOf(
        ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT,
        ManeuverType.RAMP_RIGHT, ManeuverType.RAMP_LEFT,
        ManeuverType.STAY_RIGHT, ManeuverType.STAY_LEFT
    )

    private val CITY_SCHEDULE_TYPES = setOf(
        ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT,
        ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT,
        ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT,
        ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT,
        ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT,
        ManeuverType.STAY_LEFT, ManeuverType.STAY_RIGHT,
        ManeuverType.ROUNDABOUT
    )

    data class ScheduleSelection(
        val stage: String?,
        val matchedSignal: MatchedApproachSignal = MatchedApproachSignal(),
        val extraStagesToMarkSpoken: Set<String> = emptySet()
    )

    fun isHighwayDecisionManeuver(maneuver: RouteManeuver, isHighway: Boolean): Boolean =
        isHighway && (maneuver.type in HIGHWAY_DECISION_TYPES || maneuver.isHighwayExitOrSplit)

    fun isCityScheduledManeuver(maneuver: RouteManeuver, isHighway: Boolean): Boolean =
        !isHighway && maneuver.type in CITY_SCHEDULE_TYPES

    fun selectScheduleStage(
        route: OfflineRoute,
        upcomingManeuverIdx: Int,
        upcomingManeuver: RouteManeuver,
        approachManeuver: RouteManeuver?,
        vehicleProgressMeters: Double,
        isUpcomingAlreadyPassed: Boolean,
        previousDistMeters: Double?,
        currentDistMeters: Double,
        previousSignalPassed: Boolean?,
        speedMps: Float,
        isHighway: Boolean,
        unit: DistanceUnit,
        spokenForManeuver: Set<String>,
        trafficSignals: List<RoadHazardPoint>
    ): ScheduleSelection? {
        if (isUpcomingAlreadyPassed) {
            return ScheduleSelection(stage = null)
        }
        if (speedMps < NavigationSessionController.MIN_VOICE_SPEED_MPS) {
            return ScheduleSelection(stage = null)
        }

        if (isHighwayDecisionManeuver(upcomingManeuver, isHighway)) {
            return selectHighwayDecisionStage(
                previousDistMeters = previousDistMeters,
                currentDistMeters = currentDistMeters,
                speedMps = speedMps,
                spokenForManeuver = spokenForManeuver
            )
        }

        if (isCityScheduledManeuver(upcomingManeuver, isHighway)) {
            return selectCityScheduledStage(
                route = route,
                upcomingManeuver = upcomingManeuver,
                approachManeuver = approachManeuver,
                vehicleProgressMeters = vehicleProgressMeters,
                previousDistMeters = previousDistMeters,
                currentDistMeters = currentDistMeters,
                previousSignalPassed = previousSignalPassed,
                speedMps = speedMps,
                unit = unit,
                spokenForManeuver = spokenForManeuver,
                trafficSignals = trafficSignals
            )
        }

        return null
    }

    private fun selectHighwayDecisionStage(
        previousDistMeters: Double?,
        currentDistMeters: Double,
        speedMps: Float,
        spokenForManeuver: Set<String>
    ): ScheduleSelection {
        val hwFinalDist = ManeuverTimingPlanner.computeFinalTurnDistanceMeters(speedMps, isHighway = true)

        // 1. Final exit cue reserved for actual exit distance
        if (currentDistMeters <= hwFinalDist) {
            return ScheduleSelection(stage = NavigationSessionController.STAGE_HW_NOW)
        }

        // 2. 500 m short preparation cue (crossing detection + entry band + late-start catchup)
        val crossed500 = crossedThreshold(previousDistMeters, currentDistMeters, HW_500M_METERS)
        val in500Band = currentDistMeters in 340.0..500.0
        val lateBelow500 = currentDistMeters > hwFinalDist + 15.0 &&
            currentDistMeters < 340.0 &&
            NavigationSessionController.STAGE_HW_500M !in spokenForManeuver
        if (crossed500 || in500Band || lateBelow500) {
            return ScheduleSelection(stage = NavigationSessionController.STAGE_HW_500M)
        }

        // 3. 1 mile exit + lane recommendation cue
        val crossed1Mi = crossedThreshold(previousDistMeters, currentDistMeters, HW_1MI_METERS)
        val in1MiBand = currentDistMeters in 1_450.0..1_620.0
        val lateBelow1Mi = currentDistMeters > 500.0 &&
            currentDistMeters < 1_450.0 &&
            NavigationSessionController.STAGE_HW_1MI !in spokenForManeuver
        if (crossed1Mi || in1MiBand || lateBelow1Mi) {
            return ScheduleSelection(stage = NavigationSessionController.STAGE_HW_1MI)
        }

        // 4. 2 mile early exit + side cue
        val crossed2Mi = crossedThreshold(previousDistMeters, currentDistMeters, HW_2MI_METERS)
        val in2MiBand = currentDistMeters in 2_950.0..3_230.0
        if (crossed2Mi || in2MiBand) {
            return ScheduleSelection(stage = NavigationSessionController.STAGE_HW_2MI)
        }

        return ScheduleSelection(stage = null)
    }

    private fun selectCityScheduledStage(
        route: OfflineRoute,
        upcomingManeuver: RouteManeuver,
        approachManeuver: RouteManeuver?,
        vehicleProgressMeters: Double,
        previousDistMeters: Double?,
        currentDistMeters: Double,
        previousSignalPassed: Boolean?,
        speedMps: Float,
        unit: DistanceUnit,
        spokenForManeuver: Set<String>,
        trafficSignals: List<RoadHazardPoint>
    ): ScheduleSelection {
        val cityFinalDist = ManeuverTimingPlanner.computeFinalTurnDistanceMeters(speedMps, isHighway = false)

        val searchWindow = maxOf(
            SIGNAL_SEARCH_MIN_WINDOW_METERS,
            (approachManeuver?.lengthMeters ?: 0.0).coerceAtMost(2_200.0)
        )
        val matchedSignal = RouteTrafficSignalMatcher.findFinalSignalBeforeManeuver(
            route = route,
            maneuver = upcomingManeuver,
            previousManeuver = approachManeuver,
            vehicleProgressMeters = vehicleProgressMeters,
            approachWindowMeters = searchWindow,
            signals = trafficSignals
        )

        // 1. Final turn cue takes priority when within imminent turn threshold
        if (currentDistMeters <= cityFinalDist) {
            return ScheduleSelection(
                stage = NavigationSessionController.STAGE_CITY_NOW,
                matchedSignal = matchedSignal
            )
        }

        val signalAlreadySpoken = NavigationSessionController.STAGE_CITY_POST_SIGNAL in spokenForManeuver ||
            NavigationSessionController.STAGE_CITY_POST_SIGNAL_DIST in spokenForManeuver ||
            NavigationSessionController.STAGE_CITY_PRE_STOP_LINE in spokenForManeuver ||
            NavigationSessionController.STAGE_CITY_1KM_POST_SIGNAL in spokenForManeuver

        val oneKmAlreadySpoken = NavigationSessionController.STAGE_CITY_1KM in spokenForManeuver ||
            NavigationSessionController.STAGE_CITY_1KM_POST_SIGNAL in spokenForManeuver

        val crossed1Km = crossedThreshold(previousDistMeters, currentDistMeters, CITY_1KM_METERS)
        val in1KmBand = currentDistMeters in 920.0..1_000.0
        val oneKmDue = !oneKmAlreadySpoken && (crossed1Km || in1KmBand)

        // Check signal-triggered cue
        val signalJustPassed = matchedSignal.kind == ApproachSignalKind.BEFORE_TARGET_IN_WINDOW &&
            matchedSignal.hasPassedSignal &&
            (previousSignalPassed == false || previousSignalPassed == null)

        val preStopLineDue = matchedSignal.kind == ApproachSignalKind.AT_TARGET_INTERSECTION &&
            !signalAlreadySpoken &&
            currentDistMeters <= TARGET_SIGNAL_PRE_STOP_METERS &&
            currentDistMeters > cityFinalDist

        val postSignalDue = !signalAlreadySpoken && signalJustPassed && currentDistMeters > cityFinalDist

        // 2. Combined cue if crossing 1 km and passing the final signal coincide on the same update
        if (oneKmDue && postSignalDue) {
            return ScheduleSelection(
                stage = NavigationSessionController.STAGE_CITY_1KM_POST_SIGNAL,
                matchedSignal = matchedSignal,
                extraStagesToMarkSpoken = setOf(
                    NavigationSessionController.STAGE_CITY_1KM,
                    NavigationSessionController.STAGE_CITY_POST_SIGNAL,
                    NavigationSessionController.STAGE_CITY_POST_SIGNAL_DIST
                )
            )
        }

        // 3. Final mapped traffic signal cue (either post-signal or pre-stop-line at target intersection)
        if (postSignalDue) {
            val isNextTurn = isActuallyNextTurnAfterSignal(upcomingManeuver, matchedSignal, currentDistMeters)
            val stage = if (isNextTurn) {
                NavigationSessionController.STAGE_CITY_POST_SIGNAL
            } else {
                NavigationSessionController.STAGE_CITY_POST_SIGNAL_DIST
            }
            return ScheduleSelection(
                stage = stage,
                matchedSignal = matchedSignal
            )
        }

        if (preStopLineDue) {
            return ScheduleSelection(
                stage = NavigationSessionController.STAGE_CITY_PRE_STOP_LINE,
                matchedSignal = matchedSignal
            )
        }

        // 4. 1 kilometer city announcement (independent of 500 m vs 1,000 m camera zoom setting)
        if (oneKmDue) {
            return ScheduleSelection(
                stage = NavigationSessionController.STAGE_CITY_1KM,
                matchedSignal = matchedSignal
            )
        }

        // 5. Fallback when no reliable mapped signal exists (or late-start inside final preparation band)
        val hasMappedSignalAheadBeforeTarget = matchedSignal.kind == ApproachSignalKind.BEFORE_TARGET_IN_WINDOW &&
            !matchedSignal.hasPassedSignal &&
            matchedSignal.distanceBeforeManeuverMeters <= 400.0
        val hasTargetSignalAhead = matchedSignal.kind == ApproachSignalKind.AT_TARGET_INTERSECTION

        val signalSpokenNearTurn = NavigationSessionController.STAGE_CITY_POST_SIGNAL in spokenForManeuver ||
            NavigationSessionController.STAGE_CITY_PRE_STOP_LINE in spokenForManeuver ||
            (NavigationSessionController.STAGE_CITY_POST_SIGNAL_DIST in spokenForManeuver &&
                matchedSignal.distanceBeforeManeuverMeters <= 550.0)

        if (!signalSpokenNearTurn && !hasMappedSignalAheadBeforeTarget && !hasTargetSignalAhead) {
            val isMetric = unit == DistanceUnit.METRIC
            val cityNearRange = if (isMetric) 250.0..350.0 else 260.0..365.0
            val dynamicPrepDist = ManeuverTimingPlanner.computePreparationDistanceMeters(
                maneuver = upcomingManeuver,
                approachManeuver = approachManeuver,
                speedMps = speedMps,
                isHighway = false
            )
            val crossedNear = crossedThreshold(previousDistMeters, currentDistMeters, cityNearRange.endInclusive)
            val inNear = currentDistMeters in cityNearRange
            val shortBlockCatchup = currentDistMeters > cityFinalDist + 20.0 &&
                currentDistMeters < cityNearRange.start &&
                currentDistMeters <= maxOf(cityNearRange.start, dynamicPrepDist) &&
                NavigationSessionController.STAGE_CITY_1000FT !in spokenForManeuver
            if (crossedNear || inNear || shortBlockCatchup) {
                return ScheduleSelection(
                    stage = NavigationSessionController.STAGE_CITY_1000FT,
                    matchedSignal = matchedSignal
                )
            }
        }

        return ScheduleSelection(stage = null, matchedSignal = matchedSignal)
    }

    fun isActuallyNextTurnAfterSignal(
        maneuver: RouteManeuver,
        matchedSignal: MatchedApproachSignal,
        currentDistMeters: Double
    ): Boolean {
        if (matchedSignal.kind != ApproachSignalKind.BEFORE_TARGET_IN_WINDOW) return false
        if (maneuver.carriedLaneHadInterveningIntersection) return false
        if (matchedSignal.distanceBeforeManeuverMeters > NEXT_TURN_MAX_SIGNAL_GAP_METERS) return false
        if (currentDistMeters > NEXT_TURN_MAX_REMAINING_METERS) return false
        return maneuver.type in setOf(
            ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.SLIGHT_RIGHT,
            ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.SLIGHT_LEFT
        )
    }

    fun crossedThreshold(previousDistMeters: Double?, currentDistMeters: Double, thresholdMeters: Double): Boolean =
        previousDistMeters != null && previousDistMeters > thresholdMeters && currentDistMeters <= thresholdMeters
}
