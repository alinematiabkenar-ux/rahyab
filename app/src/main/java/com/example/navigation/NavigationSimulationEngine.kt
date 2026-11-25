package com.example.navigation

import com.example.data.local.UserReportEntity
import com.example.data.preferences.UserSettings
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import kotlin.math.cos

/**
 * Dead-reckoning and realistic simulated-drive advancement extracted from [NavigationSessionController].
 */
internal object NavigationSimulationEngine {

    fun advanceDeadReckoning(
        currentState: ActiveNavigationState,
        elapsedSeconds: Double
    ): ActiveNavigationState {
        val route = currentState.route ?: return currentState.copy(isGpsSignalLost = true)
        if (route.shape.size < 2) return currentState.copy(isGpsSignalLost = true)

        val speedMps = currentState.currentSpeedMps.coerceAtLeast(8f)
        val advanceMeters = speedMps * elapsedSeconds
        val (newIdx, newLoc, newBearing) = NavigationGeometryHelper.advanceAlongShape(
            shape = route.shape,
            startIndex = currentState.snappedShapeIndex,
            distanceMeters = advanceMeters,
            currentLocation = currentState.snappedLocation
        )
        val remainingDist = NavigationGeometryHelper.polylineDistanceFromPoint(
            route.shape, newLoc, newIdx, route.shape.lastIndex
        )
        val upcoming = route.maneuvers.getOrNull(currentState.currentManeuverIndex)
        val distToNext = if (upcoming != null) {
            NavigationGeometryHelper.polylineDistanceFromPoint(route.shape, newLoc, newIdx, upcoming.beginShapeIndex)
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

    fun computeRealistic1xSpeedMps(currentState: ActiveNavigationState): Float {
        when (currentState.effectiveTravelMode) {
            TravelMode.WALK -> return WALK_SIM_SPEED_MPS
            TravelMode.BIKE -> return BIKE_SIM_SPEED_MPS
            TravelMode.DRIVE -> Unit
        }
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
            NavigationSessionController.isEarlyWarningManeuverType(upcoming.type) ||
                upcoming.type == ManeuverType.ROUNDABOUT ||
                upcoming.type == ManeuverType.ARRIVE ||
                upcoming.type == ManeuverType.ARRIVE_LEFT ||
                upcoming.type == ManeuverType.ARRIVE_RIGHT
            )
        if (needsSlowdown && distToTurn in 0.0..140.0) {
            val turnTargetMps = when {
                upcoming.type == ManeuverType.ARRIVE ||
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

    const val WALK_SIM_SPEED_MPS = 5.0f / 3.6f // 5 km/h
    const val BIKE_SIM_SPEED_MPS = 18.0f / 3.6f // 18 km/h

    fun computeSimulatedFix(
        currentState: ActiveNavigationState,
        elapsedSeconds: Double,
        wrongTurnOffsetMeters: Double = 0.0,
        timestampMs: Long = System.currentTimeMillis()
    ): GpsFix? {
        val route = currentState.route ?: return null
        if (route.shape.size < 2) return null

        val realisticSpeedMps = computeRealistic1xSpeedMps(currentState)

        val (_, baseLoc, bearing) = NavigationGeometryHelper.advanceAlongShape(
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

    fun advanceSimulationStep(
        controller: NavigationSessionController,
        currentState: ActiveNavigationState,
        totalSimulatedSeconds: Double,
        startingSimClockMs: Long,
        wrongTurnOffsetMeters: Double,
        dbHazards: List<RoadHazardPoint>,
        userReports: List<UserReportEntity>,
        unit: DistanceUnit,
        language: AppLanguage,
        speedAudioEnabled: Boolean,
        settings: UserSettings,
        fraCrossings: List<com.example.data.packs.FraCrossingRecord> = emptyList(),
        hpmsFallbackSpeedMph: Int? = null
    ): NavigationSessionController.SimulationStepBatchResult {
        if (!currentState.isNavigating || currentState.hasArrived || currentState.route == null) {
            return NavigationSessionController.SimulationStepBatchResult(
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

            val res = controller.processGpsFix(
                currentState = state,
                fix = simFix,
                dbHazards = dbHazards,
                userReports = userReports,
                unit = unit,
                language = language,
                speedAudioEnabled = speedAudioEnabled,
                settings = settings,
                fraCrossings = fraCrossings,
                hpmsFallbackSpeedMph = hpmsFallbackSpeedMph
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

        return NavigationSessionController.SimulationStepBatchResult(
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
}
