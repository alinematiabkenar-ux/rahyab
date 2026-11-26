package com.example.navigation

import com.example.i18n.DistanceUnit
import kotlin.math.max

/**
 * Speed-aware and road-class-aware voice stage selection extracted from [NavigationSessionController].
 */
internal object NavigationVoiceTriggerPolicy {

    private const val EARLY_CATCHUP_BUFFER_METERS = 100.0
    private const val NEAR_CATCHUP_BUFFER_METERS = 50.0

    fun selectVoiceStage(
        hasArrived: Boolean,
        isWalkingMode: Boolean,
        speedMps: Float,
        isHighway: Boolean,
        upcomingManeuver: RouteManeuver,
        approachManeuver: RouteManeuver?,
        distToNextManeuver: Double,
        unit: DistanceUnit,
        spokenForManeuver: Set<String>,
        travelMode: TravelMode = if (isWalkingMode) TravelMode.WALK else TravelMode.DRIVE
    ): String? {
        val hasLaneData = upcomingManeuver.lanes.isNotEmpty()
        val isMultiLaneCityRoad = upcomingManeuver.laneCount >= 2 ||
            upcomingManeuver.isMultiLaneRoad ||
            (approachManeuver?.laneCount ?: 0) >= 2 ||
            (approachManeuver?.isMultiLaneRoad == true) ||
            ((approachManeuver?.speedLimitMph ?: upcomingManeuver.speedLimitMph ?: 0) >= 35)
        val isEligibleForEarlyCityWarning = !isHighway &&
            NavigationSessionController.isEarlyWarningManeuverType(upcomingManeuver.type) &&
            (hasLaneData || isMultiLaneCityRoad)

        val isMetric = unit == DistanceUnit.METRIC
        val earlyFarRange = if (isMetric) 1380.0..1650.0 else 1450.0..1780.0
        val earlyMidRange = if (isMetric) 720.0..880.0 else 720.0..920.0
        val hwQuarterRange = if (isMetric) 340.0..470.0 else 340.0..490.0
        val cityNearRange = if (isMetric) 250.0..350.0 else 260.0..365.0
        val dynamicPrepDist = ManeuverTimingPlanner.computePreparationDistanceMeters(
            maneuver = upcomingManeuver,
            approachManeuver = approachManeuver,
            speedMps = speedMps,
            isHighway = isHighway
        )
        val hwFinalDist = ManeuverTimingPlanner.computeFinalTurnDistanceMeters(speedMps, isHighway = true)
        val cityFinalDist = ManeuverTimingPlanner.computeFinalTurnDistanceMeters(speedMps, isHighway = false)

        return when {
            hasArrived -> NavigationSessionController.STAGE_ARRIVED
            travelMode == TravelMode.WALK || isWalkingMode ->
                if (distToNextManeuver <= 35.0 && upcomingManeuver.type != ManeuverType.STRAIGHT) {
                    NavigationSessionController.STAGE_CITY_NOW
                } else null
            travelMode == TravelMode.BIKE -> when {
                upcomingManeuver.type == ManeuverType.STRAIGHT || upcomingManeuver.type == ManeuverType.DEPART -> null
                distToNextManeuver <= 35.0 -> NavigationSessionController.STAGE_BIKE_30M
                distToNextManeuver in 80.0..130.0 -> NavigationSessionController.STAGE_BIKE_100M
                else -> null
            }
            speedMps < NavigationSessionController.MIN_VOICE_SPEED_MPS -> null
            isHighway -> when {
                distToNextManeuver <= hwFinalDist -> NavigationSessionController.STAGE_HW_NOW
                distToNextManeuver in hwQuarterRange -> NavigationSessionController.STAGE_HW_QUARTER_MI
                distToNextManeuver in earlyMidRange -> NavigationSessionController.STAGE_HW_HALF_MI
                distToNextManeuver in earlyFarRange -> NavigationSessionController.STAGE_HW_1MI
                distToNextManeuver in (2950.0..3450.0) -> NavigationSessionController.STAGE_HW_2MI
                distToNextManeuver > earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyFarRange.start &&
                    NavigationSessionController.STAGE_HW_1MI !in spokenForManeuver -> NavigationSessionController.STAGE_HW_1MI
                distToNextManeuver > earlyMidRange.endInclusive &&
                    distToNextManeuver <= earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    NavigationSessionController.STAGE_HW_1MI !in spokenForManeuver &&
                    NavigationSessionController.STAGE_HW_HALF_MI !in spokenForManeuver -> NavigationSessionController.STAGE_HW_HALF_MI
                distToNextManeuver > hwQuarterRange.endInclusive + NEAR_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyMidRange.start &&
                    NavigationSessionController.STAGE_HW_HALF_MI !in spokenForManeuver -> NavigationSessionController.STAGE_HW_HALF_MI
                distToNextManeuver > hwFinalDist + 30.0 &&
                    distToNextManeuver < hwQuarterRange.start &&
                    NavigationSessionController.STAGE_HW_QUARTER_MI !in spokenForManeuver ->
                    NavigationSessionController.STAGE_HW_QUARTER_MI
                else -> null
            }
            isEligibleForEarlyCityWarning -> when {
                distToNextManeuver <= cityFinalDist -> NavigationSessionController.STAGE_CITY_NOW
                distToNextManeuver in cityNearRange -> NavigationSessionController.STAGE_CITY_1000FT
                distToNextManeuver in earlyMidRange -> NavigationSessionController.STAGE_CITY_HALF_MI
                distToNextManeuver in earlyFarRange -> NavigationSessionController.STAGE_CITY_1MI
                distToNextManeuver > earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyFarRange.start &&
                    NavigationSessionController.STAGE_CITY_1MI !in spokenForManeuver -> NavigationSessionController.STAGE_CITY_1MI
                distToNextManeuver > earlyMidRange.endInclusive &&
                    distToNextManeuver <= earlyMidRange.endInclusive + EARLY_CATCHUP_BUFFER_METERS &&
                    NavigationSessionController.STAGE_CITY_1MI !in spokenForManeuver &&
                    NavigationSessionController.STAGE_CITY_HALF_MI !in spokenForManeuver -> NavigationSessionController.STAGE_CITY_HALF_MI
                distToNextManeuver > cityNearRange.endInclusive + NEAR_CATCHUP_BUFFER_METERS &&
                    distToNextManeuver < earlyMidRange.start &&
                    NavigationSessionController.STAGE_CITY_HALF_MI !in spokenForManeuver -> NavigationSessionController.STAGE_CITY_HALF_MI
                // Short-block catchup below 1000ft window so short multilane blocks always get a preparation cue
                distToNextManeuver > cityFinalDist + 25.0 &&
                    distToNextManeuver < cityNearRange.start &&
                    NavigationSessionController.STAGE_CITY_1000FT !in spokenForManeuver ->
                    NavigationSessionController.STAGE_CITY_1000FT
                else -> null
            }
            else -> when {
                distToNextManeuver <= cityFinalDist -> NavigationSessionController.STAGE_CITY_NOW
                distToNextManeuver in cityNearRange -> NavigationSessionController.STAGE_CITY_1000FT
                // Short-block & low-speed fallback: ensure urban preparation cue never falls into dead zone
                distToNextManeuver > cityFinalDist + 20.0 &&
                    distToNextManeuver < cityNearRange.start &&
                    distToNextManeuver <= max(cityNearRange.start, dynamicPrepDist) &&
                    NavigationSessionController.STAGE_CITY_1000FT !in spokenForManeuver ->
                    NavigationSessionController.STAGE_CITY_1000FT
                else -> null
            }
        }
    }

    fun markPassedEarlierStages(spokenStages: MutableSet<String>, currentStage: String) {
        when (currentStage) {
            NavigationSessionController.STAGE_HW_1MI ->
                spokenStages.add(NavigationSessionController.STAGE_HW_2MI)
            NavigationSessionController.STAGE_HW_HALF_MI -> {
                spokenStages.add(NavigationSessionController.STAGE_HW_2MI)
                spokenStages.add(NavigationSessionController.STAGE_HW_1MI)
            }
            NavigationSessionController.STAGE_HW_QUARTER_MI -> {
                spokenStages.add(NavigationSessionController.STAGE_HW_2MI)
                spokenStages.add(NavigationSessionController.STAGE_HW_1MI)
                spokenStages.add(NavigationSessionController.STAGE_HW_HALF_MI)
            }
            NavigationSessionController.STAGE_HW_NOW -> {
                spokenStages.add(NavigationSessionController.STAGE_HW_2MI)
                spokenStages.add(NavigationSessionController.STAGE_HW_1MI)
                spokenStages.add(NavigationSessionController.STAGE_HW_HALF_MI)
                spokenStages.add(NavigationSessionController.STAGE_HW_QUARTER_MI)
            }
            NavigationSessionController.STAGE_CITY_HALF_MI ->
                spokenStages.add(NavigationSessionController.STAGE_CITY_1MI)
            NavigationSessionController.STAGE_CITY_1000FT -> {
                spokenStages.add(NavigationSessionController.STAGE_CITY_1MI)
                spokenStages.add(NavigationSessionController.STAGE_CITY_HALF_MI)
            }
            NavigationSessionController.STAGE_CITY_NOW -> {
                spokenStages.add(NavigationSessionController.STAGE_CITY_1MI)
                spokenStages.add(NavigationSessionController.STAGE_CITY_HALF_MI)
                spokenStages.add(NavigationSessionController.STAGE_CITY_1000FT)
            }
            NavigationSessionController.STAGE_BIKE_30M ->
                spokenStages.add(NavigationSessionController.STAGE_BIKE_100M)
        }
    }

    data class VoiceTriggerResult(
        val maneuverToSpeak: Pair<RouteManeuver, String>?,
        val lastSpokenManeuverIndex: Int,
        val lastSpokenManeuverTimeMs: Long
    )

    fun evaluateVoiceTrigger(
        hasArrived: Boolean,
        isWalkingMode: Boolean,
        speedMps: Float,
        isHighway: Boolean,
        upcomingManeuver: RouteManeuver,
        approachManeuver: RouteManeuver?,
        distToNextManeuver: Double,
        unit: DistanceUnit,
        spokenForManeuver: MutableSet<String>,
        eventTimeMs: Long,
        lastSpokenManeuverIndex: Int,
        lastSpokenManeuverTimeMs: Long,
        travelMode: TravelMode = if (isWalkingMode) TravelMode.WALK else TravelMode.DRIVE
    ): VoiceTriggerResult {
        val rawStageKey = selectVoiceStage(
            hasArrived = hasArrived,
            isWalkingMode = isWalkingMode,
            speedMps = speedMps,
            isHighway = isHighway,
            upcomingManeuver = upcomingManeuver,
            approachManeuver = approachManeuver,
            distToNextManeuver = distToNextManeuver,
            unit = unit,
            spokenForManeuver = spokenForManeuver,
            travelMode = travelMode
        )
        val isDifferentManeuver = lastSpokenManeuverIndex != -1 && lastSpokenManeuverIndex != upcomingManeuver.index
        val elapsedSinceLastSpokenMs = eventTimeMs - lastSpokenManeuverTimeMs
        val bypassCooldown = rawStageKey != null && ManeuverTimingPlanner.shouldBypassInterManeuverCooldown(
            stage = rawStageKey,
            distToNextManeuver = distToNextManeuver,
            speedMps = speedMps,
            isHighway = isHighway,
            approachManeuver = approachManeuver
        )
        val requiredCooldownMs = when {
            rawStageKey == null || bypassCooldown -> 0L
            rawStageKey == NavigationSessionController.STAGE_ARRIVED -> 0L
            rawStageKey == NavigationSessionController.STAGE_CITY_NOW ||
                rawStageKey == NavigationSessionController.STAGE_HW_NOW ||
                rawStageKey == NavigationSessionController.STAGE_BIKE_30M ->
                NavigationSessionController.IMMEDIATE_INTER_MANEUVER_COOLDOWN_MS
            else -> NavigationSessionController.INTER_MANEUVER_COOLDOWN_MS
        }
        val isBlockedByOverlapGuard = rawStageKey != null &&
            isDifferentManeuver &&
            requiredCooldownMs > 0L &&
            elapsedSinceLastSpokenMs in 0L until requiredCooldownMs

        return if (rawStageKey != null && !isBlockedByOverlapGuard && spokenForManeuver.add(rawStageKey)) {
            markPassedEarlierStages(spokenForManeuver, rawStageKey)
            VoiceTriggerResult(
                maneuverToSpeak = upcomingManeuver to rawStageKey,
                lastSpokenManeuverIndex = upcomingManeuver.index,
                lastSpokenManeuverTimeMs = eventTimeMs
            )
        } else {
            VoiceTriggerResult(
                maneuverToSpeak = null,
                lastSpokenManeuverIndex = lastSpokenManeuverIndex,
                lastSpokenManeuverTimeMs = lastSpokenManeuverTimeMs
            )
        }
    }
}
