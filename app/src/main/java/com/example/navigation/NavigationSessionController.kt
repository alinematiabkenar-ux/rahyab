package com.example.navigation

import com.example.data.local.ReportType
import com.example.data.local.UserReportEntity
import com.example.data.preferences.UserSettings
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure turn-by-turn navigation session calculator covering Phases 2, 3, and 4:
 *  - Orthogonal segment projection map matching (snap-to-road within 30 m)
 *  - Off-route detection (> 48 m off route for 3 consecutive updates)
 *  - Speed-aware & road-class-aware voice timing:
 *      * Highways: ~2 mi, ~1 mi (~1.5 km), ~0.5 mi (~800 m), ~0.25 mi (~400 m), and at maneuver
 *      * Multi-lane / lane-data city turns, exits & forks: ~1 mi (~1.5 km), ~0.5 mi (~800 m), ~1,000 ft (~300 m), and just before turn
 *      * Small residential streets: ~1,000 ft (~300 m) and just before turn
 *  - Early Sygic-style lane strip visibility from ~1 mile on both city streets and highways — strictly hidden if no lane data
 *  - Real-geometry enlarged Junction Preview (~0.5 mi before highway exits/splits), respecting Settings & tap-dismiss
 *  - Speed limit tracking (strictly null when OSM maxspeed is absent!) & configurable warning threshold (0 / 5 / 10 mph)
 *  - Road alerts ahead (speed drops, speed-aware sharp curves, tolls, railroad, school zones, optional stop signs/signals)
 *    and all 8 Waze-style user report types (respecting direction of travel)
 *  - Dead-reckoning tunnel mode when GPS signal is lost + Simulate Drive generator for emulator testing
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

    fun reset() {
        offRouteCount = 0
        lastRerouteRequestMs = 0L
        lastSpokenAlertIds.clear()
        spokenFreeDriveCameraIds.clear()
        spokenStagesByManeuver.clear()
        trackedRouteId = null
        lastSpokenManeuverIndex = -1
        lastSpokenManeuverTimeMs = 0L
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
        }

        // 1. Orthogonal projection map matching (snap-to-road), using previous shape index as fast hint
        val snap = snapToRoute(fix, route.shape, hintIndex = currentState.snappedShapeIndex)
        val bestIdx = snap.segmentIndex
        val bestDist = snap.distanceMeters

        // 2. Off-route check (> 48 m away for 3 consecutive updates) — disabled below 3 mph to prevent GPS drift reroutes
        val isMovingFastEnoughToReroute = fix.speedMps >= MIN_REROUTE_SPEED_MPS
        val isOffRouteNow = bestDist > 48.0 && isMovingFastEnoughToReroute
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
        val distToNextManeuver = polylineDistanceFromPoint(route.shape, snappedLoc, bestIdx, upcomingManeuver.beginShapeIndex)
            .coerceAtLeast(
                OfflinePlaceSearchEngine.haversineMeters(snappedLoc.lat, snappedLoc.lon, upcomingManeuver.lat, upcomingManeuver.lon)
            )
        val remainingDistance = polylineDistanceFromPoint(route.shape, snappedLoc, bestIdx, route.shape.lastIndex)
        val fractionRemaining = if (route.distanceMeters > 1.0) {
            (remainingDistance / route.distanceMeters).coerceIn(0.0, 1.0)
        } else 0.0
        val baselineRemainingTime = route.durationSeconds * fractionRemaining
        val remainingTime = if (fix.speedMps > 3.5f && remainingDistance > 100.0) {
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

        // 5. Arrival check
        val destPoint = route.shape.last()
        val distToDest = OfflinePlaceSearchEngine.haversineMeters(snappedLoc.lat, snappedLoc.lon, destPoint.lat, destPoint.lon)
        val hasArrived = distToDest < 35.0 && remainingDistance < 60.0

        // 6. Speed-aware & road-class-aware voice timing with early lane-change warnings
        // Never speak maneuver prompts while moving under 2 mph, speak each stage only once per
        // maneuver, and never announce two different maneuvers at the same moment.
        val isHighway = upcomingManeuver.isHighwayRoad || fix.speedMps >= 20f
        val approachManeuver = route.maneuvers.getOrNull(upcomingManeuverIdx - 1)
        val hasLaneData = upcomingManeuver.lanes.isNotEmpty()
        val isMultiLaneCityRoad = upcomingManeuver.laneCount >= 2 ||
            upcomingManeuver.isMultiLaneRoad ||
            (approachManeuver?.laneCount ?: 0) >= 2 ||
            (approachManeuver?.isMultiLaneRoad == true) ||
            ((approachManeuver?.speedLimitMph ?: upcomingManeuver.speedLimitMph ?: 0) >= 35)
        val isEligibleForEarlyCityWarning = !isHighway &&
            isEarlyWarningManeuverType(upcomingManeuver.type) &&
            (hasLaneData || isMultiLaneCityRoad)

        val isMetric = unit == DistanceUnit.METRIC
        val earlyFarRange = if (isMetric) 1380.0..1650.0 else 1450.0..1780.0
        val earlyMidRange = if (isMetric) 720.0..880.0 else 720.0..920.0
        val hwQuarterRange = if (isMetric) 340.0..470.0 else 340.0..490.0
        val cityNearRange = if (isMetric) 250.0..350.0 else 260.0..365.0
        val speedLeadMeters = max(0.0, fix.speedMps * 4.5)
        val spokenForManeuver = spokenStagesByManeuver.getOrPut(upcomingManeuver.index) { mutableSetOf() }

        val rawStageKey: String? = when {
            hasArrived -> STAGE_ARRIVED
            fix.speedMps < MIN_VOICE_SPEED_MPS -> null
            isHighway -> when {
                distToNextManeuver <= max(110.0, speedLeadMeters) -> STAGE_HW_NOW
                distToNextManeuver in hwQuarterRange -> STAGE_HW_QUARTER_MI
                distToNextManeuver in earlyMidRange -> STAGE_HW_HALF_MI
                distToNextManeuver in earlyFarRange -> STAGE_HW_1MI
                distToNextManeuver in (2950.0..3450.0) -> STAGE_HW_2MI
                // Short spacing after previous maneuver: never skip early warning; speak with real distance
                distToNextManeuver > earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyFarRange.start &&
                    STAGE_HW_1MI !in spokenForManeuver -> STAGE_HW_1MI
                distToNextManeuver > earlyMidRange.endInclusive &&
                    distToNextManeuver <= earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    STAGE_HW_1MI !in spokenForManeuver &&
                    STAGE_HW_HALF_MI !in spokenForManeuver -> STAGE_HW_HALF_MI
                distToNextManeuver > hwQuarterRange.endInclusive + NEAR_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyMidRange.start &&
                    STAGE_HW_HALF_MI !in spokenForManeuver -> STAGE_HW_HALF_MI
                else -> null
            }
            isEligibleForEarlyCityWarning -> when {
                distToNextManeuver <= max(55.0, fix.speedMps * 4.0) -> STAGE_CITY_NOW
                distToNextManeuver in cityNearRange -> STAGE_CITY_1000FT
                distToNextManeuver in earlyMidRange -> STAGE_CITY_HALF_MI
                distToNextManeuver in earlyFarRange -> STAGE_CITY_1MI
                // Short spacing after previous maneuver: speak early warning immediately with real distance
                distToNextManeuver > earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyFarRange.start &&
                    STAGE_CITY_1MI !in spokenForManeuver -> STAGE_CITY_1MI
                distToNextManeuver > earlyMidRange.endInclusive &&
                    distToNextManeuver <= earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    STAGE_CITY_1MI !in spokenForManeuver &&
                    STAGE_CITY_HALF_MI !in spokenForManeuver -> STAGE_CITY_HALF_MI
                distToNextManeuver > cityNearRange.endInclusive + NEAR_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyMidRange.start &&
                    STAGE_CITY_HALF_MI !in spokenForManeuver -> STAGE_CITY_HALF_MI
                else -> null
            }
            else -> when {
                distToNextManeuver <= max(55.0, fix.speedMps * 4.0) -> STAGE_CITY_NOW
                distToNextManeuver in cityNearRange -> STAGE_CITY_1000FT
                else -> null
            }
        }

        val eventTimeMs = if (fix.timestampMs > 0L) fix.timestampMs else now
        val isDifferentManeuver = lastSpokenManeuverIndex != -1 && lastSpokenManeuverIndex != upcomingManeuver.index
        val elapsedSinceLastSpokenMs = eventTimeMs - lastSpokenManeuverTimeMs
        val requiredCooldownMs = when (rawStageKey) {
            STAGE_ARRIVED -> 0L
            STAGE_CITY_NOW, STAGE_HW_NOW -> IMMEDIATE_INTER_MANEUVER_COOLDOWN_MS
            else -> INTER_MANEUVER_COOLDOWN_MS
        }
        val isBlockedByOverlapGuard = rawStageKey != null &&
            isDifferentManeuver &&
            requiredCooldownMs > 0L &&
            elapsedSinceLastSpokenMs in 0L until requiredCooldownMs

        val maneuverToSpeak = if (rawStageKey != null && !isBlockedByOverlapGuard) {
            if (spokenForManeuver.add(rawStageKey)) {
                markPassedEarlierStages(spokenForManeuver, rawStageKey)
                lastSpokenManeuverIndex = upcomingManeuver.index
                lastSpokenManeuverTimeMs = eventTimeMs
                upcomingManeuver to rawStageKey
            } else {
                null
            }
        } else {
            null
        }

        // 7. Lane guidance visibility from ~1 mile (~1.5 km in metric) on BOTH city streets and highways
        // Strictly hidden if `upcomingManeuver.lanes` is empty — NEVER invented!
        val laneWindowMeters = laneStripWindowMeters(unit)
        val showLaneStrip = !hasArrived &&
            upcomingManeuver.lanes.isNotEmpty() &&
            distToNextManeuver <= laneWindowMeters

        // Gently pulse highlighted lanes ONCE, synchronized with the half-mile (800 m) voice announcement
        val triggeredHalfMilePulse = maneuverToSpeak != null &&
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

        // 8. Phase 3: Enlarged Junction Preview (~0.5 mi / 850 m before highway exits & complex splits)
        val isDismissedForThisManeuver = currentState.dismissedJunctionManeuverIndex == upcomingManeuver.index
        val showJunctionPreview = !hasArrived &&
            settings.junctionPreviewEnabled &&
            !isDismissedForThisManeuver &&
            upcomingManeuver.isHighwayExitOrSplit &&
            distToNextManeuver <= 850.0

        // 9. Phase 3: Speed limit (strictly null if OSM maxspeed is absent — NEVER guessed!)
        val speedLimitMph = if (route.speedLimitsByShapeIndex.isNotEmpty()) {
            route.speedLimitsByShapeIndex[bestIdx]
        } else {
            val activeManeuver = route.maneuvers.getOrNull((upcomingManeuverIdx - 1).coerceAtLeast(0))
            activeManeuver?.speedLimitMph
        }
        val currentMph = fix.speedMps * 2.23694f
        val isOverspeed = speedLimitMph != null && currentMph > (speedLimitMph + settings.speedWarningThresholdMph)

        // 10. Phase 3 & 4: Ahead Alerts (filtered by user's individual alert switches)
        val activeAheadAlert = findNearestAheadAlert(
            fix = fix.copy(lat = snappedLoc.lat, lon = snappedLoc.lon, bearing = snappedBearing),
            routeAlerts = route.roadAlerts,
            dbHazards = dbHazards,
            userReports = userReports,
            settings = settings,
            isHighway = isHighway
        )
        val alertToSpeak = if (activeAheadAlert != null &&
            activeAheadAlert.distanceMeters <= 550.0 &&
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
            showJunctionPreview = showJunctionPreview
        )

        return UpdateResult(
            state = nextState,
            shouldReroute = shouldReroute,
            maneuverToSpeak = maneuverToSpeak,
            alertToSpeak = alertToSpeak,
            triggerOverspeedChime = isOverspeed && settings.speedWarningAudioEnabled
        )
    }

    /**
     * Dead-reckoning mode when GPS signal drops (e.g. inside a tunnel):
     * advances the snapped marker along the route polyline at the last known speed.
     */
    fun advanceDeadReckoning(
        currentState: ActiveNavigationState,
        elapsedSeconds: Double
    ): ActiveNavigationState {
        val route = currentState.route ?: return currentState.copy(isGpsSignalLost = true)
        if (route.shape.size < 2) return currentState.copy(isGpsSignalLost = true)

        val speedMps = currentState.currentSpeedMps.coerceAtLeast(8f)
        val advanceMeters = speedMps * elapsedSeconds
        val (newIdx, newLoc, newBearing) = advanceAlongShape(
            shape = route.shape,
            startIndex = currentState.snappedShapeIndex,
            distanceMeters = advanceMeters,
            currentLocation = currentState.snappedLocation
        )
        val remainingDist = polylineDistanceFromPoint(route.shape, newLoc, newIdx, route.shape.lastIndex)
        val upcoming = route.maneuvers.getOrNull(currentState.currentManeuverIndex)
        val distToNext = if (upcoming != null) {
            polylineDistanceFromPoint(route.shape, newLoc, newIdx, upcoming.beginShapeIndex)
        } else 0.0

        return currentState.copy(
            snappedLocation = newLoc,
            snappedBearing = newBearing,
            snappedShapeIndex = newIdx,
            remainingDistanceMeters = remainingDist,
            distanceToNextManeuverMeters = distToNext,
            isGpsSignalLost = true
        )
    }

    /**
     * Computes a realistic 1x driving speed (in m/s) for the current road segment:
     * - Follows the road's real OSM speed limit when present (capped safely at speed limit so
     *   simulation never triggers false speeding warnings).
     * - Otherwise uses a realistic default for the road class (65 mph highway, 35 mph multi-lane
     *   city road, 25 mph residential street).
     * - Smoothly slows down before upcoming non-straight turns or exits.
     */
    fun computeRealistic1xSpeedMps(currentState: ActiveNavigationState): Float {
        val route = currentState.route ?: return 15.6f
        val upcoming = currentState.currentManeuver
        val activeManeuver = route.maneuvers.getOrNull((currentState.currentManeuverIndex - 1).coerceAtLeast(0))
        val roadLimitMph = route.speedLimitsByShapeIndex[currentState.snappedShapeIndex]
            ?: activeManeuver?.speedLimitMph
            ?: upcoming?.speedLimitMph

        val baseSpeedMps = when {
            roadLimitMph != null -> (roadLimitMph * 0.44704f).coerceIn(8.0f, 33.5f)
            activeManeuver?.isHighwayRoad == true || upcoming?.isHighwayRoad == true -> 29.05f // ~65 mph
            (activeManeuver?.laneCount ?: 0) >= 2 || activeManeuver?.isMultiLaneRoad == true ||
                (upcoming?.laneCount ?: 0) >= 2 || upcoming?.isMultiLaneRoad == true -> 15.65f // ~35 mph
            else -> 11.18f // ~25 mph residential street
        }

        // Smooth deceleration approaching turns, exits, or arrival
        val distToTurn = currentState.distanceToNextManeuverMeters
        val needsSlowdown = upcoming != null && (
            isEarlyWarningManeuverType(upcoming.type) ||
                upcoming.type == ManeuverType.ROUNDABOUT ||
                upcoming.type == ManeuverType.ARRIVE ||
                upcoming.type == ManeuverType.ARRIVE_LEFT ||
                upcoming.type == ManeuverType.ARRIVE_RIGHT
            )
        if (needsSlowdown && distToTurn in 0.0..140.0) {
            val turnTargetMps = when {
                upcoming!!.type == ManeuverType.ARRIVE ||
                    upcoming.type == ManeuverType.ARRIVE_LEFT ||
                    upcoming.type == ManeuverType.ARRIVE_RIGHT -> 5.5f
                upcoming.isHighwayExitOrSplit -> 17.5f // ~39 mph ramp
                else -> 7.0f // ~16 mph city turn
            }
            val factor = (distToTurn / 140.0).coerceIn(0.0, 1.0).toFloat()
            return (turnTargetMps + (baseSpeedMps - turnTargetMps) * factor).coerceAtMost(baseSpeedMps)
        }
        return baseSpeedMps
    }

    /**
     * Produces realistic synthetic GPS fixes along the active route for "Simulate drive" mode.
     * The emitted `GpsFix.speedMps` is ALWAYS the realistic 1x speed (never multiplied), so the
     * speedometer and speed-limit warnings stay realistic even when time is accelerated up to 32x.
     */
    fun computeSimulatedFix(
        currentState: ActiveNavigationState,
        elapsedSeconds: Double,
        wrongTurnOffsetMeters: Double = 0.0,
        timestampMs: Long = System.currentTimeMillis()
    ): GpsFix? {
        val route = currentState.route ?: return null
        if (route.shape.size < 2) return null

        val realisticSpeedMps = computeRealistic1xSpeedMps(currentState)

        val (_, baseLoc, bearing) = advanceAlongShape(
            shape = route.shape,
            startIndex = currentState.snappedShapeIndex,
            distanceMeters = realisticSpeedMps * elapsedSeconds,
            currentLocation = currentState.snappedLocation
        )

        val finalLoc = if (wrongTurnOffsetMeters > 0.0) {
            val dLat = (wrongTurnOffsetMeters / 111_320.0)
            val dLon = (wrongTurnOffsetMeters / (111_320.0 * cos(Math.toRadians(baseLoc.lat)).coerceAtLeast(0.1)))
            LatLon(baseLoc.lat + dLat * 0.6, baseLoc.lon + dLon * 0.8)
        } else {
            baseLoc
        }

        return GpsFix(
            lat = finalLoc.lat,
            lon = finalLoc.lon,
            bearing = bearing,
            speedMps = realisticSpeedMps,
            accuracyMeters = 4f,
            timestampMs = timestampMs,
            provider = "simulation"
        )
    }

    /**
     * Advances simulation by [totalSimulatedSeconds] using small virtual sub-steps (<= 1.0 sim sec
     * each, max ~29 m per step even at 65 mph) so every announcement distance (2 mi, 1 mi, 0.5 mi,
     * 0.25 mi, 1,000 ft, NOW, road alerts, and speed/red-light cameras) triggers at the exact right
     * distance even at 16x or 32x! Only the newest maneuver/alert from this tick is surfaced for
     * speech so stale prompts never queue up.
     */
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
    ): SimulationStepBatchResult {
        if (!currentState.isNavigating || currentState.hasArrived || currentState.route == null) {
            return SimulationStepBatchResult(
                state = currentState,
                shouldReroute = false,
                finalSimClockMs = startingSimClockMs
            )
        }

        val maxSubStepSimSec = 1.0
        var remainingSimSec = totalSimulatedSeconds.coerceAtLeast(0.05)
        var simClockMs = startingSimClockMs
        var state = currentState
        var shouldReroute = false
        var triggerChime = false
        val triggeredManeuvers = mutableListOf<Pair<RouteManeuver, String>>()
        val triggeredAlerts = mutableListOf<AheadAlert>()

        var safetyCounter = 0
        while (remainingSimSec > 1e-4 && !state.hasArrived && safetyCounter < 120) {
            safetyCounter++
            val stepSec = remainingSimSec.coerceAtMost(maxSubStepSimSec)
            remainingSimSec -= stepSec
            simClockMs += (stepSec * 1000.0).toLong().coerceAtLeast(1L)

            val simFix = computeSimulatedFix(
                currentState = state,
                elapsedSeconds = stepSec,
                wrongTurnOffsetMeters = wrongTurnOffsetMeters,
                timestampMs = simClockMs
            ) ?: break

            val res = processGpsFix(
                currentState = state,
                fix = simFix,
                dbHazards = dbHazards,
                userReports = userReports,
                unit = unit,
                language = language,
                speedAudioEnabled = speedAudioEnabled,
                settings = settings
            )
            state = res.state
            if (res.shouldReroute) shouldReroute = true
            if (res.triggerOverspeedChime) triggerChime = true
            res.maneuverToSpeak?.let { triggeredManeuvers.add(it) }
            res.alertToSpeak?.let { triggeredAlerts.add(it) }
            if (shouldReroute) break
        }

        val prioritizedAlert = triggeredAlerts.lastOrNull {
            it.kind == RoadAlertKind.SPEED_CAMERA || it.kind == RoadAlertKind.RED_LIGHT_CAMERA
        } ?: triggeredAlerts.lastOrNull()

        return SimulationStepBatchResult(
            state = state,
            shouldReroute = shouldReroute,
            latestManeuverToSpeak = triggeredManeuvers.lastOrNull(),
            latestAlertToSpeak = prioritizedAlert,
            triggerOverspeedChime = triggerChime,
            finalSimClockMs = simClockMs,
            allTriggeredManeuvers = triggeredManeuvers,
            allTriggeredAlerts = triggeredAlerts
        )
    }

    fun snapToRoute(fix: GpsFix, shape: List<LatLon>, hintIndex: Int = -1): SnapProjection {
        if (shape.size == 1) {
            val pt = shape[0]
            return SnapProjection(
                point = pt,
                segmentIndex = 0,
                distanceMeters = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, pt.lat, pt.lon),
                roadBearing = fix.bearing
            )
        }
        val cosLat = cos(Math.toRadians(fix.lat)).coerceAtLeast(0.1)

        fun searchRange(startIdx: Int, endExclusive: Int): SnapProjection {
            var bestDist = Double.MAX_VALUE
            var bestPoint = shape[startIdx.coerceIn(0, shape.lastIndex)]
            var bestIdx = startIdx.coerceIn(0, (shape.lastIndex - 1).coerceAtLeast(0))
            var bestT = 0.0
            var bestBearing = fix.bearing

            for (i in startIdx until endExclusive) {
                val a = shape[i]
                val b = shape[i + 1]
                val ax = a.lon * cosLat
                val ay = a.lat
                val bx = b.lon * cosLat
                val by = b.lat
                val px = fix.lon * cosLat
                val py = fix.lat

                val dx = bx - ax
                val dy = by - ay
                val lenSq = dx * dx + dy * dy
                val t = if (lenSq > 1e-15) {
                    (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
                } else 0.0

                val projLat = a.lat + t * (b.lat - a.lat)
                val projLon = a.lon + t * (b.lon - a.lon)
                val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, projLat, projLon)
                val isCloser = dist < bestDist - 0.01
                // When tied at a shared vertex (end of segment i-1 == start of segment i), prefer the outgoing segment
                val isForwardVertexTie = abs(dist - bestDist) <= 0.01 && bestT >= 0.999 && t < 0.999
                if (isCloser || isForwardVertexTie) {
                    bestDist = dist
                    bestPoint = LatLon(projLat, projLon)
                    bestIdx = i
                    bestT = t
                    bestBearing = ValhallaResponseParser.bearingDegrees(a, b).toFloat()
                }
            }
            return SnapProjection(bestPoint, bestIdx, bestDist, bestBearing)
        }

        // Fast local window search around previous segment index for smooth 32x simulation on long routes
        if (hintIndex in 0 until shape.lastIndex && shape.size > 160) {
            val winStart = (hintIndex - 8).coerceAtLeast(0)
            val winEnd = (hintIndex + 120).coerceAtMost(shape.lastIndex)
            val local = searchRange(winStart, winEnd)
            if (local.distanceMeters <= 35.0) {
                return local
            }
        }
        return searchRange(0, shape.lastIndex)
    }

    private fun advanceAlongShape(
        shape: List<LatLon>,
        startIndex: Int,
        distanceMeters: Double,
        currentLocation: LatLon? = null
    ): Triple<Int, LatLon, Float> {
        var idx = startIndex.coerceIn(0, shape.lastIndex)
        var remaining = distanceMeters
        var curPt = currentLocation ?: shape[idx]
        while (idx < shape.lastIndex && remaining > 0.0) {
            val nextPt = shape[idx + 1]
            val segLen = OfflinePlaceSearchEngine.haversineMeters(curPt.lat, curPt.lon, nextPt.lat, nextPt.lon)
            if (segLen <= 0.1) {
                idx++
                curPt = shape[idx]
                continue
            }
            if (remaining < segLen) {
                val frac = remaining / segLen
                val lat = curPt.lat + (nextPt.lat - curPt.lat) * frac
                val lon = curPt.lon + (nextPt.lon - curPt.lon) * frac
                val bearing = ValhallaResponseParser.bearingDegrees(curPt, nextPt).toFloat()
                return Triple(idx, LatLon(lat, lon), bearing)
            }
            remaining -= segLen
            idx++
            curPt = shape[idx]
        }
        val last = shape.last()
        val prev = shape.getOrElse(shape.lastIndex - 1) { last }
        return Triple(shape.lastIndex, last, ValhallaResponseParser.bearingDegrees(prev, last).toFloat())
    }

    /**
     * Evaluates approaching speed cameras and red light cameras during free driving (no active route).
     * Returns `(activeBannerAlert, alertToSpeakOnce)`.
     */
    fun evaluateFreeDriveCameraAlert(
        fix: GpsFix,
        cameras: List<RoadHazardPoint>,
        settings: UserSettings
    ): Pair<AheadAlert?, AheadAlert?> {
        if (cameras.isEmpty()) return null to null
        if (!settings.alertSpeedCameraEnabled && !settings.alertRedLightCameraEnabled) return null to null

        val isHighway = fix.speedMps >= 20f
        val maxDist = cameraWarningDistanceMeters(isHighway)
        val nearbyIds = mutableSetOf<String>()
        var nearest: AheadAlert? = null

        for (h in cameras) {
            val alertId = stableCameraAlertId(h)
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, h.lat, h.lon)
            if (dist <= 1_200.0) {
                nearbyIds.add(alertId)
            }
            val alert = buildCameraAlertIfAhead(
                fix = fix,
                hazard = h,
                distanceMeters = dist,
                maxDistanceMeters = maxDist,
                settings = settings
            ) ?: continue
            if (nearest == null || alert.distanceMeters < nearest.distanceMeters) {
                nearest = alert
            }
        }

        // Allow a camera to warn again only after the driver has moved > 1.2 km away from it
        spokenFreeDriveCameraIds.retainAll(nearbyIds)

        val toSpeak = if (nearest != null && spokenFreeDriveCameraIds.add(nearest.id)) {
            nearest
        } else {
            null
        }
        return nearest to toSpeak
    }

    private fun cameraWarningDistanceMeters(isHighway: Boolean): Double =
        if (isHighway) CAMERA_WARN_HIGHWAY_METERS else CAMERA_WARN_CITY_METERS

    private fun stableCameraAlertId(h: RoadHazardPoint): String =
        "cam_${h.osmId?.takeIf { it.isNotBlank() } ?: h.id}"

    private fun buildCameraAlertIfAhead(
        fix: GpsFix,
        hazard: RoadHazardPoint,
        distanceMeters: Double,
        maxDistanceMeters: Double,
        settings: UserSettings
    ): AheadAlert? {
        if (distanceMeters < 10.0 || distanceMeters > maxDistanceMeters) return null
        val (kind, titleEn, titleFa) = when (hazard.type.lowercase()) {
            "speed_camera" -> {
                if (!settings.alertSpeedCameraEnabled) return null
                Triple(RoadAlertKind.SPEED_CAMERA, "Speed camera ahead", "دوربین سرعت در پیش است")
            }
            "red_light_camera" -> {
                if (!settings.alertRedLightCameraEnabled) return null
                Triple(RoadAlertKind.RED_LIGHT_CAMERA, "Red light camera ahead", "دوربین چراغ قرمز در پیش است")
            }
            else -> return null
        }

        if (!isCameraAheadInTravelDirection(fix, hazard, distanceMeters)) return null

        return AheadAlert(
            id = stableCameraAlertId(hazard),
            kind = kind,
            lat = hazard.lat,
            lon = hazard.lon,
            distanceMeters = distanceMeters,
            titleEn = titleEn,
            titleFa = titleFa,
            detail = hazard.label,
            newSpeedLimitMph = hazard.speedLimitMph
        )
    }

    private fun isCameraAheadInTravelDirection(
        fix: GpsFix,
        camera: RoadHazardPoint,
        distanceMeters: Double
    ): Boolean {
        val hasDirection = fix.hasBearing || fix.speedMps >= 2.0f
        if (!hasDirection) return distanceMeters <= 120.0

        val bearingToCamera = ValhallaResponseParser.bearingDegrees(
            LatLon(fix.lat, fix.lon),
            LatLon(camera.lat, camera.lon)
        )
        val angleToCamera = ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), bearingToCamera)
        if (angleToCamera > 45.0) return false

        // Reject cameras on parallel streets / frontage roads (> 35 m lateral offset)
        val lateralOffsetMeters = distanceMeters * sin(Math.toRadians(angleToCamera))
        if (lateralOffsetMeters > 35.0) return false

        // If OSM provides enforcement direction, match direction of travel within 55°
        val camDir = camera.directionDegrees
        if (camDir != null && !camDir.isNaN()) {
            val dirDiff = ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), camDir.toDouble())
            if (dirDiff > 55.0) return false
        }
        return true
    }

    private fun findNearestAheadAlert(
        fix: GpsFix,
        routeAlerts: List<AheadAlert>,
        dbHazards: List<RoadHazardPoint>,
        userReports: List<UserReportEntity>,
        settings: UserSettings,
        isHighway: Boolean = fix.speedMps >= 20f
    ): AheadAlert? {
        val candidates = mutableListOf<AheadAlert>()
        val now = System.currentTimeMillis()
        val currentMph = fix.speedMps * 2.23694f
        val cameraMaxDist = cameraWarningDistanceMeters(isHighway)

        // 1. Route-derived alerts (speed drops, speed-aware sharp curves, toll booths)
        for (a in routeAlerts) {
            val enabled = when (a.kind) {
                RoadAlertKind.SPEED_LIMIT_DROP -> settings.alertSpeedDropEnabled
                RoadAlertKind.SHARP_CURVE -> settings.alertSharpCurveEnabled &&
                    (currentMph >= 25f || a.curveAngleDegrees >= 75.0)
                RoadAlertKind.TOLL_BOOTH -> settings.alertTollBoothEnabled
                else -> true
            }
            if (!enabled) continue
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, a.lat, a.lon)
            if (dist in 25.0..750.0 && isRoughlyAhead(fix, a.lat, a.lon)) {
                candidates.add(a.copy(distanceMeters = dist))
            }
        }

        // 2. Map DB road hazards (cameras, railroad crossings, school zones, toll booths, optional stop signs & signals)
        for (h in dbHazards) {
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, h.lat, h.lon)
            val lowerType = h.type.lowercase()
            if (lowerType == "speed_camera" || lowerType == "red_light_camera") {
                buildCameraAlertIfAhead(
                    fix = fix,
                    hazard = h,
                    distanceMeters = dist,
                    maxDistanceMeters = cameraMaxDist,
                    settings = settings
                )?.let { candidates.add(it) }
                continue
            }
            if (dist in 20.0..500.0 && isRoughlyAhead(fix, h.lat, h.lon)) {
                val (kind, titleEn, titleFa) = when (lowerType) {
                    "railroad", "railway_crossing" -> {
                        if (!settings.alertRailroadEnabled) continue
                        Triple(RoadAlertKind.RAILROAD_CROSSING, "Railroad crossing ahead", "جلوتر تقاطع راه‌آهن است")
                    }
                    "school_zone", "school" -> {
                        if (!settings.alertSchoolZoneEnabled) continue
                        Triple(RoadAlertKind.SCHOOL_ZONE, "School zone ahead", "جلوتر منطقه مدرسه است")
                    }
                    "stop_sign", "stop" -> {
                        if (!settings.alertStopSignEnabled) continue
                        Triple(RoadAlertKind.STOP_SIGN, "Stop sign ahead", "جلوتر تابلوی ایست است")
                    }
                    "traffic_signal", "traffic_signals" -> {
                        if (!settings.alertStopSignEnabled) continue
                        Triple(RoadAlertKind.TRAFFIC_SIGNAL, "Traffic signal ahead", "جلوتر چراغ راهنمایی است")
                    }
                    "toll", "toll_booth" -> {
                        if (!settings.alertTollBoothEnabled) continue
                        Triple(RoadAlertKind.TOLL_BOOTH, "Toll booth ahead", "جلوتر عوارضی است")
                    }
                    else -> continue
                }
                candidates.add(
                    AheadAlert(
                        id = "db_hazard_${h.id}",
                        kind = kind,
                        lat = h.lat,
                        lon = h.lon,
                        distanceMeters = dist,
                        titleEn = titleEn,
                        titleFa = titleFa,
                        detail = h.label,
                        newSpeedLimitMph = h.speedLimitMph
                    )
                )
            }
        }

        // 3. All 8 Waze-style local user reports (respecting travel direction when report has bearing)
        for (r in userReports) {
            if (r.expiresAt <= now) continue
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, r.lat, r.lon)
            if (dist in 15.0..700.0 && isRoughlyAhead(fix, r.lat, r.lon)) {
                // If report recorded a direction of travel, match within 95 degrees
                if (r.bearing >= 0f && fix.speedMps >= 3f) {
                    val headingDiff = ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), r.bearing.toDouble())
                    if (headingDiff > 95.0) continue
                }
                val rType = runCatching { ReportType.valueOf(r.type) }.getOrNull() ?: continue
                val (kind, titleEn, titleFa) = when (rType) {
                    ReportType.POLICE -> Triple(RoadAlertKind.USER_POLICE, "Police reported ahead", "پلیس جلوتر گزارش شده است")
                    ReportType.HAZARD -> Triple(RoadAlertKind.USER_HAZARD, "Road hazard reported ahead", "خطر در جاده جلوتر گزارش شده است")
                    ReportType.CRASH -> Triple(RoadAlertKind.USER_CRASH, "Crash reported ahead", "تصادف جلوتر گزارش شده است")
                    ReportType.ROAD_CLOSURE -> Triple(RoadAlertKind.USER_ROAD_CLOSURE, "Road closed ahead", "جاده جلوتر مسدود است")
                    ReportType.SPEED_TRAP -> Triple(RoadAlertKind.USER_SPEED_TRAP, "Speed trap reported ahead", "کنترل سرعت جلوتر گزارش شده است")
                    ReportType.CONSTRUCTION -> Triple(RoadAlertKind.USER_CONSTRUCTION, "Construction zone ahead", "عملیات عمرانی جلوتر است")
                    ReportType.OBJECT_ON_ROAD -> Triple(RoadAlertKind.USER_OBJECT_ON_ROAD, "Object on road ahead", "شیء در جاده جلوتر گزارش شده است")
                    ReportType.STALLED_VEHICLE -> Triple(RoadAlertKind.USER_STALLED_VEHICLE, "Stalled vehicle ahead", "خودروی متوقف جلوتر گزارش شده است")
                }
                candidates.add(
                    AheadAlert(
                        id = "user_report_${r.id}",
                        kind = kind,
                        lat = r.lat,
                        lon = r.lon,
                        distanceMeters = dist,
                        titleEn = titleEn,
                        titleFa = titleFa,
                        detail = r.note.ifBlank { r.roadName },
                        userReportId = r.id
                    )
                )
            }
        }

        // Prioritize approaching cameras over generic low-priority signs if both are ahead
        val nearestCamera = candidates
            .filter { it.kind == RoadAlertKind.SPEED_CAMERA || it.kind == RoadAlertKind.RED_LIGHT_CAMERA }
            .minByOrNull { it.distanceMeters }
        return nearestCamera ?: candidates.minByOrNull { it.distanceMeters }
    }

    private fun isRoughlyAhead(fix: GpsFix, targetLat: Double, targetLon: Double): Boolean {
        if (fix.speedMps < 3.0f) return true
        val bearingToTarget = ValhallaResponseParser.bearingDegrees(
            LatLon(fix.lat, fix.lon),
            LatLon(targetLat, targetLon)
        )
        return ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), bearingToTarget) <= 75.0
    }

    private fun polylineDistanceFromPoint(
        shape: List<LatLon>,
        fromPoint: LatLon,
        fromSegIdx: Int,
        toIdx: Int
    ): Double {
        if (shape.size < 2 || fromSegIdx >= toIdx) return 0.0
        val nextVertexIdx = (fromSegIdx + 1).coerceAtMost(toIdx).coerceIn(0, shape.lastIndex)
        val firstHop = OfflinePlaceSearchEngine.haversineMeters(
            fromPoint.lat, fromPoint.lon,
            shape[nextVertexIdx].lat, shape[nextVertexIdx].lon
        )
        return firstHop + polylineDistanceMeters(shape, nextVertexIdx, toIdx)
    }

    private fun polylineDistanceMeters(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
        if (shape.size < 2 || fromIdx >= toIdx) return 0.0
        val start = fromIdx.coerceIn(0, shape.lastIndex)
        val end = toIdx.coerceIn(start, shape.lastIndex)
        var sum = 0.0
        for (i in start until end) {
            val a = shape[i]
            val b = shape[i + 1]
            sum += OfflinePlaceSearchEngine.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        }
        return sum
    }

    private fun markPassedEarlierStages(spokenStages: MutableSet<String>, currentStage: String) {
        when (currentStage) {
            STAGE_HW_1MI -> spokenStages.add(STAGE_HW_2MI)
            STAGE_HW_HALF_MI -> {
                spokenStages.add(STAGE_HW_2MI)
                spokenStages.add(STAGE_HW_1MI)
            }
            STAGE_HW_QUARTER_MI -> {
                spokenStages.add(STAGE_HW_2MI)
                spokenStages.add(STAGE_HW_1MI)
                spokenStages.add(STAGE_HW_HALF_MI)
            }
            STAGE_HW_NOW -> {
                spokenStages.add(STAGE_HW_2MI)
                spokenStages.add(STAGE_HW_1MI)
                spokenStages.add(STAGE_HW_HALF_MI)
                spokenStages.add(STAGE_HW_QUARTER_MI)
            }
            STAGE_CITY_HALF_MI -> spokenStages.add(STAGE_CITY_1MI)
            STAGE_CITY_1000FT -> {
                spokenStages.add(STAGE_CITY_1MI)
                spokenStages.add(STAGE_CITY_HALF_MI)
            }
            STAGE_CITY_NOW -> {
                spokenStages.add(STAGE_CITY_1MI)
                spokenStages.add(STAGE_CITY_HALF_MI)
                spokenStages.add(STAGE_CITY_1000FT)
            }
        }
    }

    companion object {
        /** 2 mph in meters per second — below this speed, maneuver prompts are suppressed after startup. */
        const val MIN_VOICE_SPEED_MPS = 0.89408f

        /** 3 mph in meters per second — below this speed, GPS drift will not trigger rerouting. */
        const val MIN_REROUTE_SPEED_MPS = 1.34112f

        /** Lane strip visibility distance (~1 mile Imperial, ~1.5 km Metric) for both city streets and highways. */
        const val LANE_STRIP_WINDOW_METERS = 1780.0
        const val LANE_STRIP_WINDOW_METERS_METRIC = 1650.0

        fun laneStripWindowMeters(unit: DistanceUnit): Double =
            if (unit == DistanceUnit.METRIC) LANE_STRIP_WINDOW_METERS_METRIC else LANE_STRIP_WINDOW_METERS

        /** Minimum gap before announcing a different maneuver's early/advance prompt. */
        const val INTER_MANEUVER_COOLDOWN_MS = 3_000L

        /** Minimum gap before announcing a different maneuver's immediate turn prompt. */
        const val IMMEDIATE_INTER_MANEUVER_COOLDOWN_MS = 1_500L

        private const val EARLY_CATCHUP_BUFFER_METERS = 100.0
        private const val NEAR_CATCHUP_BUFFER_METERS = 50.0
        private const val EXTERNAL_PROMPT_MANEUVER_INDEX = -2

        /** Speed & red-light camera warning lead distances: ~1,000 ft city, ~0.25 mi highway. */
        const val CAMERA_WARN_CITY_METERS = 315.0
        const val CAMERA_WARN_HIGHWAY_METERS = 420.0

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

        private val EARLY_WARNING_MANEUVER_TYPES = setOf(
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
            ManeuverType.STAY_RIGHT
        )

        fun isEarlyWarningManeuverType(type: ManeuverType): Boolean = type in EARLY_WARNING_MANEUVER_TYPES
    }
}
