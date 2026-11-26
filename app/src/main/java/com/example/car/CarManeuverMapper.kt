package com.example.car

import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection as CarLaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.TravelEstimate
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDirection
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneInfo
import com.example.navigation.LaneTargetSide
import com.example.navigation.ManeuverType
import com.example.navigation.RouteManeuver
import com.example.ui.components.formatCarLaneSideHint
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * Pure JVM snapshot of a Car App TravelEstimate (for deterministic unit testing without Android stubs).
 */
data class TravelEstimateSnapshot(
    val remainingDistanceMeters: Double,
    val remainingTimeSeconds: Long,
    val arrivalEpochMs: Long
)

/**
 * Pure JVM lane direction representation for unit testing Valhalla bitmask -> Car LaneDirection mapping.
 */
data class MappedCarLaneDirection(
    val shape: Int,
    val isRecommended: Boolean
)

/**
 * AA6: Maps Valhalla maneuvers, lane bitmasks, and remaining route progress into Android Auto
 * Car App Library [Maneuver], [Lane], and [TravelEstimate] models.
 */
object CarManeuverMapper {

    // Standard Valhalla OSM turn_lanes bitmask constants
    const val BIT_THROUGH = 2
    const val BIT_SHARP_LEFT = 4
    const val BIT_LEFT = 8
    const val BIT_SLIGHT_LEFT = 16
    const val BIT_SLIGHT_RIGHT = 32
    const val BIT_RIGHT = 64
    const val BIT_SHARP_RIGHT = 128
    const val BIT_REVERSE = 256

    fun toCarManeuverType(type: ManeuverType): Int = when (type) {
        ManeuverType.DEPART -> Maneuver.TYPE_DEPART
        ManeuverType.STRAIGHT -> Maneuver.TYPE_STRAIGHT
        ManeuverType.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        ManeuverType.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        ManeuverType.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
        ManeuverType.U_TURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
        ManeuverType.U_TURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
        ManeuverType.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        ManeuverType.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
        ManeuverType.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
        ManeuverType.RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        ManeuverType.RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
        ManeuverType.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        ManeuverType.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        ManeuverType.STAY_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
        ManeuverType.STAY_LEFT -> Maneuver.TYPE_KEEP_LEFT
        ManeuverType.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        ManeuverType.ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        ManeuverType.FERRY -> Maneuver.TYPE_FERRY_BOAT
        ManeuverType.ARRIVE -> Maneuver.TYPE_DESTINATION
        ManeuverType.ARRIVE_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
        ManeuverType.ARRIVE_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
    }

    fun toCarManeuver(maneuver: RouteManeuver): Maneuver {
        val carType = toCarManeuverType(maneuver.type)
        val builder = Maneuver.Builder(carType)
        if (maneuver.type == ManeuverType.ROUNDABOUT) {
            builder.setRoundaboutExitNumber((maneuver.roundaboutExitCount ?: 1).coerceAtLeast(1))
        }
        return builder.build()
    }

    /**
     * Maps Valhalla's `directions` & `active` lane bitmasks directly to Car [CarLaneDirection] shapes.
     */
    fun mapValhallaLaneBitmask(
        directionsMask: Int,
        activeMask: Int = 0
    ): List<MappedCarLaneDirection> {
        val pairs = listOf(
            BIT_SHARP_LEFT to CarLaneDirection.SHAPE_SHARP_LEFT,
            BIT_LEFT to CarLaneDirection.SHAPE_NORMAL_LEFT,
            BIT_SLIGHT_LEFT to CarLaneDirection.SHAPE_SLIGHT_LEFT,
            BIT_THROUGH to CarLaneDirection.SHAPE_STRAIGHT,
            BIT_SLIGHT_RIGHT to CarLaneDirection.SHAPE_SLIGHT_RIGHT,
            BIT_RIGHT to CarLaneDirection.SHAPE_NORMAL_RIGHT,
            BIT_SHARP_RIGHT to CarLaneDirection.SHAPE_SHARP_RIGHT,
            BIT_REVERSE to CarLaneDirection.SHAPE_U_TURN_LEFT
        )
        val result = ArrayList<MappedCarLaneDirection>(4)
        for ((bit, shape) in pairs) {
            if ((directionsMask and bit) != 0) {
                result.add(MappedCarLaneDirection(shape = shape, isRecommended = (activeMask and bit) != 0))
            }
        }
        if (result.isEmpty()) {
            result.add(MappedCarLaneDirection(shape = CarLaneDirection.SHAPE_UNKNOWN, isRecommended = false))
        }
        return result
    }

    fun toCarLaneDirectionShape(dir: LaneDirection): Int = when (dir) {
        LaneDirection.SHARP_LEFT -> CarLaneDirection.SHAPE_SHARP_LEFT
        LaneDirection.LEFT -> CarLaneDirection.SHAPE_NORMAL_LEFT
        LaneDirection.SLIGHT_LEFT -> CarLaneDirection.SHAPE_SLIGHT_LEFT
        LaneDirection.THROUGH -> CarLaneDirection.SHAPE_STRAIGHT
        LaneDirection.SLIGHT_RIGHT -> CarLaneDirection.SHAPE_SLIGHT_RIGHT
        LaneDirection.RIGHT -> CarLaneDirection.SHAPE_NORMAL_RIGHT
        LaneDirection.SHARP_RIGHT -> CarLaneDirection.SHAPE_SHARP_RIGHT
        LaneDirection.U_TURN_LEFT -> CarLaneDirection.SHAPE_U_TURN_LEFT
        LaneDirection.U_TURN_RIGHT -> CarLaneDirection.SHAPE_U_TURN_RIGHT
        LaneDirection.MERGE_LEFT -> CarLaneDirection.SHAPE_SLIGHT_LEFT
        LaneDirection.MERGE_RIGHT -> CarLaneDirection.SHAPE_SLIGHT_RIGHT
    }

    fun toCarLanes(lanes: List<LaneInfo>): List<Lane> {
        return lanes.map { laneInfo ->
            val builder = Lane.Builder()
            if (laneInfo.isUnmarkedSlot || laneInfo.directions.isEmpty()) {
                builder.addDirection(CarLaneDirection.create(CarLaneDirection.SHAPE_UNKNOWN, false))
            } else {
                for (dir in laneInfo.directions) {
                    val recommended = laneInfo.isRecommended &&
                        (laneInfo.activeDirections.isEmpty() || dir in laneInfo.activeDirections)
                    builder.addDirection(CarLaneDirection.create(toCarLaneDirectionShape(dir), recommended))
                }
            }
            builder.build()
        }
    }

    fun computeTravelEstimateSnapshot(
        remainingDistanceMeters: Double,
        remainingTimeSeconds: Double,
        nowEpochMs: Long
    ): TravelEstimateSnapshot {
        val safeDist = remainingDistanceMeters.coerceAtLeast(0.0)
        val safeSec = remainingTimeSeconds.roundToLong().coerceAtLeast(0L)
        return TravelEstimateSnapshot(
            remainingDistanceMeters = safeDist,
            remainingTimeSeconds = safeSec,
            arrivalEpochMs = nowEpochMs + safeSec * 1000L
        )
    }

    fun toCarTravelEstimate(
        remainingDistanceMeters: Double,
        remainingTimeSeconds: Double,
        unit: DistanceUnit,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TravelEstimate {
        val snap = computeTravelEstimateSnapshot(remainingDistanceMeters, remainingTimeSeconds, nowEpochMs)
        val arrival = DateTimeWithZone.create(snap.arrivalEpochMs, TimeZone.getDefault())
        return TravelEstimate.Builder(
            CarMapActionsHelper.toCarDistance(snap.remainingDistanceMeters, unit),
            arrival
        )
            .setRemainingTimeSeconds(snap.remainingTimeSeconds)
            .build()
    }

    /**
     * Builds concise bilingual maneuver cue text for Android Auto's Step card while preserving
     * raw street and highway names from the map data (AA8).
     */
    fun formatCarStepCue(maneuver: RouteManeuver, language: AppLanguage): String {
        val road = maneuver.signpost?.primaryRoadNumbers?.takeIf { it.isNotBlank() }
            ?: maneuver.streetNames.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: ""
        if (language != AppLanguage.PERSIAN) {
            return if (road.isNotBlank()) road else maneuver.instructionEn.ifBlank { "Continue" }
        }
        val actionFa = when (maneuver.type) {
            ManeuverType.LEFT -> "به چپ"
            ManeuverType.SLIGHT_LEFT -> "کمی به چپ"
            ManeuverType.SHARP_LEFT -> "تند به چپ"
            ManeuverType.RIGHT -> "به راست"
            ManeuverType.SLIGHT_RIGHT -> "کمی به راست"
            ManeuverType.SHARP_RIGHT -> "تند به راست"
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> "دور بزنید"
            ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT -> "خروجی"
            ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT -> "رمپ"
            ManeuverType.STAY_LEFT -> "سمت چپ بمانید"
            ManeuverType.STAY_RIGHT -> "سمت راست بمانید"
            ManeuverType.MERGE -> "ادغام شوید"
            ManeuverType.ROUNDABOUT -> "میدان"
            ManeuverType.FERRY -> "کشتی"
            ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> "مقصد"
            ManeuverType.DEPART, ManeuverType.STRAIGHT -> "مستقیم"
        }
        return if (road.isNotBlank()) "$actionFa · $road" else actionFa
    }

    /**
     * B2: Always produces a non-blank maneuver cue string even when street names and
     * `instructionEn` are missing (e.g., unnamed highway connectors or ramps).
     */
    fun formatFallbackStepCue(maneuver: RouteManeuver?, language: AppLanguage): String {
        if (maneuver == null) {
            return if (language == AppLanguage.PERSIAN) "مستقیم ادامه دهید" else "Continue"
        }
        val road = maneuver.signpost?.primaryRoadNumbers?.takeIf { it.isNotBlank() }
            ?: maneuver.streetNames.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: ""
        if (language == AppLanguage.PERSIAN) {
            val formatted = formatCarStepCue(maneuver, language).trim()
            if (formatted.isNotBlank()) return formatted
        } else {
            if (road.isNotBlank()) return road
            if (maneuver.instructionEn.isNotBlank()) return maneuver.instructionEn.trim()
        }
        val exitNum = maneuver.signpost?.primaryExitNumber?.takeIf { it.isNotBlank() }
        if (language == AppLanguage.PERSIAN) {
            return if (exitNum != null) "خروجی $exitNum" else "ادامه دهید"
        }
        return when (maneuver.type) {
            ManeuverType.LEFT -> "Turn left"
            ManeuverType.SLIGHT_LEFT -> "Bear left"
            ManeuverType.SHARP_LEFT -> "Sharp left"
            ManeuverType.RIGHT -> "Turn right"
            ManeuverType.SLIGHT_RIGHT -> "Bear right"
            ManeuverType.SHARP_RIGHT -> "Sharp right"
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> "Make a U-turn"
            ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT -> if (exitNum != null) "Take exit $exitNum" else "Take exit"
            ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT -> "Take ramp"
            ManeuverType.STAY_LEFT -> "Keep left"
            ManeuverType.STAY_RIGHT -> "Keep right"
            ManeuverType.MERGE -> "Merge"
            ManeuverType.ROUNDABOUT -> "Enter roundabout"
            ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> "Arrive at destination"
            ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.FERRY -> "Continue"
        }
    }

    /**
     * B2: Builds a valid Android Auto [androidx.car.app.navigation.model.Step] for any active
     * navigation state, falling back cleanly when street names or maneuver metadata are missing.
     */
    fun buildStepOrFallback(
        maneuver: RouteManeuver?,
        language: AppLanguage,
        includeLanes: Boolean = false,
        lanesImageProvider: ((List<LaneInfo>) -> androidx.car.app.model.CarIcon?)? = null,
        laneConfidence: LaneConfidence = LaneConfidence.HIGH,
        laneSideHint: LaneTargetSide? = null,
        laneDecision: LaneGuidanceDecision? = null
    ): androidx.car.app.navigation.model.Step {
        val baseCue = formatFallbackStepCue(maneuver, language)
        val effectiveDecision = if (includeLanes && maneuver != null && maneuver.lanes.isNotEmpty()) {
            laneDecision ?: LaneGuidanceDecision.evaluate(maneuver)
        } else {
            null
        }
        val effectiveConfidence = when {
            laneConfidence == LaneConfidence.LOW -> LaneConfidence.LOW
            effectiveDecision != null -> effectiveDecision.confidence
            else -> laneConfidence
        }
        val effectiveSideHint = laneSideHint ?: effectiveDecision?.suggestedSide
        val skipAllSuitable = effectiveDecision?.shouldSkipLaneChangeInstruction == true
        val useLowConfidenceHint = includeLanes &&
            !skipAllSuitable &&
            maneuver != null &&
            maneuver.lanes.isNotEmpty() &&
            (effectiveConfidence == LaneConfidence.LOW || effectiveDecision?.hasActionableLaneGuidance == false)
        val cue = if (useLowConfidenceHint && effectiveSideHint != null) {
            "$baseCue · ${formatCarLaneSideHint(effectiveSideHint, language)}"
        } else {
            baseCue
        }
        val carManeuver = if (maneuver != null) {
            toCarManeuver(maneuver)
        } else {
            Maneuver.Builder(Maneuver.TYPE_STRAIGHT).build()
        }
        val stepBuilder = androidx.car.app.navigation.model.Step.Builder(cue)
            .setManeuver(carManeuver)

        val roadName = maneuver?.streetNames?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: maneuver?.signpost?.primaryRoadNumbers?.takeIf { it.isNotBlank() }
        if (!roadName.isNullOrBlank()) {
            stepBuilder.setRoad(roadName)
        }

        val canAttachCarLanes = includeLanes &&
            !skipAllSuitable &&
            !useLowConfidenceHint &&
            maneuver != null &&
            maneuver.lanes.isNotEmpty() &&
            (effectiveDecision == null || (effectiveDecision.hasActionableLaneGuidance && effectiveDecision.shouldShowFullDiagram))

        if (canAttachCarLanes && maneuver != null) {
            toCarLanes(maneuver.lanes).forEach { lane ->
                stepBuilder.addLane(lane)
            }
            lanesImageProvider?.invoke(maneuver.lanes)?.let { icon ->
                stepBuilder.setLanesImage(icon)
            }
        }
        return stepBuilder.build()
    }
}
