package com.example.navigation

import com.example.data.local.UserReportEntity
import com.example.data.preferences.UserSettings
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import kotlin.math.max

/**
 * Pure turn-by-turn navigation session calculator (map matching, off-route, voice timing,
 * lane & junction preview, speed limit, road alerts, and simulation delegation).
 */
class NavigationSessionController {

    private var offRouteCount = 0
    private var lastRerouteRequestMs = 0L
    private val lastSpokenAlertIds = mutableSetOf<String>()
    private val spokenFreeDriveCameraIds = mutableSetOf<String>()
    private val spokenStagesByManeuver = mutableMapOf<Int, MutableSet<String>>()
    private var trackedRouteId: String? = null
    private var lastSpokenManeuverIndex: Int = -1
    private var lastSpokenManeuverTimeMs: Long = 0L
    private val travelModeEtaAdjuster = TravelModeEtaAdjuster()

    fun reset() {
        offRouteCount = 0
        lastRerouteRequestMs = 0L
        lastSpokenAlertIds.clear()
        spokenFreeDriveCameraIds.clear()
        spokenStagesByManeuver.clear()
        trackedRouteId = null
        lastSpokenManeuverIndex = -1
        lastSpokenManeuverTimeMs = 0L
        travelModeEtaAdjuster.reset()
    }

    /**
     * Records that an external navigation prompt (such as initial route departure or reroute
     * announcement) was just spoken so an immediate follow-up maneuver prompt waits briefly
     * instead of interrupting it at the exact same moment.
     */
    fun markExternalPromptSpoken(timestampMs: Long = System.currentTimeMillis()) {
        lastSpokenManeuverIndex = EXTERNAL_PROMPT_MANEUVER_INDEX
        lastSpokenManeuverTimeMs = timestampMs
    }

    data class UpdateResult(
        val state: ActiveNavigationState,
        val shouldReroute: Boolean,
        val maneuverToSpeak: Pair<RouteManeuver, String>? = null,
        val alertToSpeak: AheadAlert? = null,
        val triggerOverspeedChime: Boolean = false
    )

    data class SnapProjection(
        val point: LatLon,
        val segmentIndex: Int,
        val distanceMeters: Double,
        val roadBearing: Float
    )

    data class SimulationStepBatchResult(
        val state: ActiveNavigationState,
        val shouldReroute: Boolean,
        val latestManeuverToSpeak: Pair<RouteManeuver, String>? = null,
        val latestAlertToSpeak: AheadAlert? = null,
        val triggerOverspeedChime: Boolean = false,
        val finalSimClockMs: Long = 0L,
        val allTriggeredManeuvers: List<Pair<RouteManeuver, String>> = emptyList(),
        val allTriggeredAlerts: List<AheadAlert> = emptyList()
    )

    fun processGpsFix(
        currentState: ActiveNavigationState,
        fix: GpsFix,
        dbHazards: List<RoadHazardPoint>,
        userReports: List<UserReportEntity>,
        unit: DistanceUnit,
        language: AppLanguage,
        speedAudioEnabled: Boolean,
        settings: UserSettings = UserSettings(
            language = language,
            distanceUnit = unit,
            speedWarningAudioEnabled = speedAudioEnabled
        )
    ): UpdateResult {
        val route = currentState.route ?: return UpdateResult(currentState, shouldReroute = false)
        if (route.shape.isEmpty() || route.maneuvers.isEmpty()) {
            return UpdateResult(currentState, shouldReroute = false)
        }

        if (trackedRouteId != route.id) {
            spokenStagesByManeuver.clear()
            trackedRouteId = route.id
            lastSpokenManeuverIndex = -1
            lastSpokenManeuverTimeMs = 0L
            travelModeEtaAdjuster.reset()
        }

        // 1. Orthogonal projection map matching (snap-to-road), using previous shape index as fast hint
        val snap = snapToRoute(fix, route.shape, hintIndex = currentState.snappedShapeIndex)
        val bestIdx = snap.segmentIndex
        val bestDist = snap.distanceMeters

        // 2. Off-route check (30 m for WALK/BIKE, 48 m for DRIVE, for 3 consecutive updates)
        val mode = currentState.effectiveTravelMode
        val isDrive = mode == TravelMode.DRIVE
        val isWalk = mode == TravelMode.WALK
        val isBike = mode == TravelMode.BIKE
        val offRouteThresholdMeters = if (isWalk || isBike) 30.0 else 48.0
        val minRerouteSpeedMps = when (mode) {
            TravelMode.WALK -> 0.5f
            TravelMode.BIKE -> 1.0f
            TravelMode.DRIVE -> MIN_REROUTE_SPEED_MPS
        }
        val isMovingFastEnoughToReroute = fix.speedMps >= minRerouteSpeedMps
        val isOffRouteNow = bestDist > offRouteThresholdMeters && isMovingFastEnoughToReroute
        if (isOffRouteNow) {
            offRouteCount++
        } else {
            offRouteCount = 0
        }
        val now = System.currentTimeMillis()
        val shouldReroute = isMovingFastEnoughToReroute &&
            offRouteCount >= 3 &&
            (now - lastRerouteRequestMs > 12_000L)
        if (shouldReroute) {
            lastRerouteRequestMs = now
            offRouteCount = 0
        }

        // Only snap position/bearing to the route when within 30 meters; otherwise show real GPS position
        val shouldSnapToRoute = bestDist <= 30.0
        val snappedLoc = if (shouldSnapToRoute) snap.point else LatLon(fix.lat, fix.lon)
        val snappedBearing = if (shouldSnapToRoute) snap.roadBearing else fix.bearing

        // 3. Determine upcoming maneuver
        var upcomingManeuverIdx = route.maneuvers.indexOfFirst { it.beginShapeIndex > bestIdx }
        if (upcomingManeuverIdx == -1) {
            upcomingManeuverIdx = route.maneuvers.lastIndex
        }
        val upcomingManeuver = route.maneuvers[upcomingManeuverIdx]

        // 4. Distance & live speed-blended ETA
        val distToNextManeuver = NavigationGeometryHelper
            .polylineDistanceFromPoint(route.shape, snappedLoc, bestIdx, upcomingManeuver.beginShapeIndex)
            .coerceAtLeast(
                OfflinePlaceSearchEngine.haversineMeters(
                    snappedLoc.lat, snappedLoc.lon, upcomingManeuver.lat, upcomingManeuver.lon
                )
            )
        val remainingDistance = NavigationGeometryHelper
            .polylineDistanceFromPoint(route.shape, snappedLoc, bestIdx, route.shape.lastIndex)
        val fractionRemaining = if (route.distanceMeters > 1.0) {
            (remainingDistance / route.distanceMeters).coerceIn(0.0, 1.0)
        } else 0.0
        val baselineRemainingTime = route.durationSeconds * fractionRemaining
        val eventTimeMs = if (fix.timestampMs > 0L) fix.timestampMs else now
        val remainingTime = if (!isDrive) {
            max(10.0, travelModeEtaAdjuster.adjustRemainingTimeSeconds(
                mode, baselineRemainingTime, route.distanceMeters, route.durationSeconds, fix.speedMps, eventTimeMs
            ))
        } else if (fix.speedMps > 3.5f && remainingDistance > 100.0) {
            val liveEstimate = remainingDistance / fix.speedMps
            max(10.0, baselineRemainingTime * 0.65 + liveEstimate * 0.35)
        } else {
            max(10.0, baselineRemainingTime)
        }
        val timeToNext = if (fix.speedMps > 2.5f) {
            distToNextManeuver / fix.speedMps
        } else {
            upcomingManeuver.timeSeconds * (distToNextManeuver / max(1.0, upcomingManeuver.lengthMeters)).coerceIn(0.0, 1.0)
        }

        // 5. Arrival check (<=15 m WALK, <=20 m BIKE, <35 m DRIVE)
        val destPoint = route.shape.last()
        val distToDest = OfflinePlaceSearchEngine.haversineMeters(
            snappedLoc.lat, snappedLoc.lon, destPoint.lat, destPoint.lon
        )
        val hasArrived = when (mode) {
            TravelMode.WALK -> distToDest <= 15.0
            TravelMode.BIKE -> distToDest <= 20.0
            TravelMode.DRIVE -> distToDest < 35.0 && remainingDistance < 60.0
        }

        // 6. Speed-aware & road-class-aware voice timing with early lane-change warnings
        val isHighway = isDrive && (upcomingManeuver.isHighwayRoad || fix.speedMps >= 20f)
        val approachManeuver = route.maneuvers.getOrNull(upcomingManeuverIdx - 1)
        val spokenForManeuver = spokenStagesByManeuver.getOrPut(upcomingManeuver.index) { mutableSetOf() }
        val voiceTrigger = NavigationVoiceTriggerPolicy.evaluateVoiceTrigger(
            hasArrived = hasArrived,
            isWalkingMode = isWalk,
            speedMps = fix.speedMps,
            isHighway = isHighway,
            upcomingManeuver = upcomingManeuver,
            approachManeuver = approachManeuver,
            distToNextManeuver = distToNextManeuver,
            unit = unit,
            spokenForManeuver = spokenForManeuver,
            eventTimeMs = eventTimeMs,
            lastSpokenManeuverIndex = lastSpokenManeuverIndex,
            lastSpokenManeuverTimeMs = lastSpokenManeuverTimeMs,
            travelMode = mode
        )
        lastSpokenManeuverIndex = voiceTrigger.lastSpokenManeuverIndex
        lastSpokenManeuverTimeMs = voiceTrigger.lastSpokenManeuverTimeMs
        val maneuverToSpeak = voiceTrigger.maneuverToSpeak

        // 7. Lane guidance visibility (DRIVE only)
        val laneWindowMeters = laneStripWindowMeters(unit)
        val showLaneStrip = isDrive &&
            !hasArrived &&
            upcomingManeuver.lanes.isNotEmpty() &&
            distToNextManeuver <= laneWindowMeters

        val triggeredHalfMilePulse = isDrive &&
            maneuverToSpeak != null &&
            upcomingManeuver.lanes.isNotEmpty() &&
            (maneuverToSpeak.second == STAGE_HW_HALF_MI || maneuverToSpeak.second == STAGE_CITY_HALF_MI)
        val nextLanePulseManeuverIndex = if (triggeredHalfMilePulse) {
            upcomingManeuver.index
        } else {
            currentState.lanePulseManeuverIndex
        }
        val nextLanePulseTriggerCount = if (triggeredHalfMilePulse) {
            currentState.lanePulseTriggerCount + 1
        } else {
            currentState.lanePulseTriggerCount
        }

        // 8. Highway Junction View (DRIVE only, 800 m before to 50 m after, hidden off-route)
        val activeJunctionManeuver = JunctionSceneBuilder.resolveActiveJunctionManeuver(
            route, upcomingManeuverIdx, distToNextManeuver, snappedLoc,
            isDrive, hasArrived, isOffRouteNow, settings.junctionPreviewEnabled, currentState.dismissedJunctionManeuverIndex
        )
        val showJunctionPreview = activeJunctionManeuver != null

        // 9. Speed limit (DRIVE only — strictly null in WALK and BIKE)
        val speedLimitMph = if (!isDrive) {
            null
        } else if (route.speedLimitsByShapeIndex.isNotEmpty()) {
            route.speedLimitsByShapeIndex[bestIdx]
        } else {
            val activeManeuver = route.maneuvers.getOrNull((upcomingManeuverIdx - 1).coerceAtLeast(0))
            activeManeuver?.speedLimitMph
        }
        val currentMph = fix.speedMps * 2.23694f
        val isOverspeed = isDrive &&
            speedLimitMph != null &&
            currentMph > (speedLimitMph + settings.speedWarningThresholdMph)

        // 10. Ahead Alerts (WALK: none; BIKE: railroad & steep grade only; DRIVE: all enabled)
        val activeAheadAlert = if (isWalk) {
            null
        } else {
            NavigationAlertEvaluator.findNearestAheadAlert(
                fix = fix.copy(lat = snappedLoc.lat, lon = snappedLoc.lon, bearing = snappedBearing),
                routeAlerts = route.roadAlerts,
                dbHazards = dbHazards,
                userReports = userReports,
                settings = settings,
                isHighway = isHighway,
                travelMode = mode
            )
        }
        val speakDistanceMax = if (activeAheadAlert?.kind == RoadAlertKind.STEEP_GRADE) 1_700.0 else 550.0
        val alertToSpeak = if (!isWalk &&
            activeAheadAlert != null &&
            activeAheadAlert.distanceMeters <= speakDistanceMax &&
            lastSpokenAlertIds.add(activeAheadAlert.id)
        ) {
            activeAheadAlert
        } else null

        val nextState = currentState.copy(
            currentManeuverIndex = upcomingManeuverIdx,
            distanceToNextManeuverMeters = distToNextManeuver,
            timeToNextManeuverSeconds = timeToNext,
            remainingDistanceMeters = remainingDistance,
            remainingTimeSeconds = remainingTime,
            currentSpeedMps = fix.speedMps,
            snappedLocation = snappedLoc,
            snappedBearing = snappedBearing,
            snappedShapeIndex = bestIdx,
            currentSpeedLimitMph = speedLimitMph,
            isOverspeed = isOverspeed,
            isOffRoute = isOffRouteNow,
            isRerouting = shouldReroute || currentState.isRerouting,
            isGpsSignalLost = false,
            hasArrived = hasArrived,
            activeAheadAlert = activeAheadAlert,
            showLaneStrip = showLaneStrip,
            lanePulseManeuverIndex = nextLanePulseManeuverIndex,
            lanePulseTriggerCount = nextLanePulseTriggerCount,
            showJunctionPreview = showJunctionPreview,
            activeJunctionManeuverIndex = activeJunctionManeuver?.index ?: -1
        )

        return UpdateResult(
            state = nextState,
            shouldReroute = shouldReroute,
            maneuverToSpeak = maneuverToSpeak,
            alertToSpeak = alertToSpeak,
            triggerOverspeedChime = isOverspeed && settings.speedWarningAudioEnabled
        )
    }

    fun advanceDeadReckoning(currentState: ActiveNavigationState, elapsedSeconds: Double): ActiveNavigationState =
        NavigationSimulationEngine.advanceDeadReckoning(currentState, elapsedSeconds)

    fun computeRealistic1xSpeedMps(currentState: ActiveNavigationState): Float =
        NavigationSimulationEngine.computeRealistic1xSpeedMps(currentState)

    fun computeSimulatedFix(
        currentState: ActiveNavigationState,
        elapsedSeconds: Double,
        wrongTurnOffsetMeters: Double = 0.0,
        timestampMs: Long = System.currentTimeMillis()
    ): GpsFix? = NavigationSimulationEngine.computeSimulatedFix(
        currentState, elapsedSeconds, wrongTurnOffsetMeters, timestampMs
    )

    fun advanceSimulationStep(
        currentState: ActiveNavigationState,
        totalSimulatedSeconds: Double,
        startingSimClockMs: Long,
        wrongTurnOffsetMeters: Double,
        dbHazards: List<RoadHazardPoint>,
        userReports: List<UserReportEntity>,
        unit: DistanceUnit,
        language: AppLanguage,
        speedAudioEnabled: Boolean,
        settings: UserSettings
    ): SimulationStepBatchResult = NavigationSimulationEngine.advanceSimulationStep(
        this, currentState, totalSimulatedSeconds, startingSimClockMs, wrongTurnOffsetMeters,
        dbHazards, userReports, unit, language, speedAudioEnabled, settings
    )

    fun snapToRoute(fix: GpsFix, shape: List<LatLon>, hintIndex: Int = -1): SnapProjection =
        NavigationGeometryHelper.snapToRoute(fix, shape, hintIndex)

    fun evaluateFreeDriveCameraAlert(
        fix: GpsFix,
        cameras: List<RoadHazardPoint>,
        settings: UserSettings
    ): Pair<AheadAlert?, AheadAlert?> = NavigationAlertEvaluator.evaluateFreeDriveCameraAlert(
        fix, cameras, settings, spokenFreeDriveCameraIds
    )

    fun findAheadTrafficLightMeters(fix: GpsFix, trafficLights: List<RoadHazardPoint>): Double? =
        NavigationAlertEvaluator.findAheadTrafficLightMeters(fix, trafficLights)

    companion object {
        const val MIN_VOICE_SPEED_MPS = 0.89408f
        const val MIN_REROUTE_SPEED_MPS = 1.34112f
        const val LANE_STRIP_WINDOW_METERS = 1780.0
        const val LANE_STRIP_WINDOW_METERS_METRIC = 1650.0
        fun laneStripWindowMeters(unit: DistanceUnit): Double =
            if (unit == DistanceUnit.METRIC) LANE_STRIP_WINDOW_METERS_METRIC else LANE_STRIP_WINDOW_METERS

        const val INTER_MANEUVER_COOLDOWN_MS = 3_000L
        const val IMMEDIATE_INTER_MANEUVER_COOLDOWN_MS = 1_500L
        private const val EXTERNAL_PROMPT_MANEUVER_INDEX = -2

        const val CAMERA_WARN_CITY_METERS = 315.0
        const val CAMERA_WARN_HIGHWAY_METERS = 420.0
        const val TRAFFIC_LIGHT_AHEAD_METERS = 95.0

        const val STAGE_ARRIVED = "arrived"
        const val STAGE_HW_2MI = "hw_2mi"
        const val STAGE_HW_1MI = "hw_1mi"
        const val STAGE_HW_HALF_MI = "hw_half_mi"
        const val STAGE_HW_QUARTER_MI = "hw_quarter_mi"
        const val STAGE_HW_NOW = "hw_now"
        const val STAGE_CITY_1MI = "city_1mi"
        const val STAGE_CITY_HALF_MI = "city_half_mi"
        const val STAGE_CITY_1000FT = "city_1000ft"
        const val STAGE_CITY_NOW = "city_now"
        const val STAGE_BIKE_100M = "bike_100m"
        const val STAGE_BIKE_30M = "bike_30m"

        private val EARLY_WARNING_MANEUVER_TYPES = setOf(
            ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT,
            ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT,
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT,
            ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT,
            ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT,
            ManeuverType.STAY_LEFT, ManeuverType.STAY_RIGHT
        )

        fun isEarlyWarningManeuverType(type: ManeuverType): Boolean = type in EARLY_WARNING_MANEUVER_TYPES
    }
}
