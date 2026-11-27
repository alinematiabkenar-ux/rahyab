package com.example.navigation

import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure mechanical extraction from [VoiceGuidanceManager] (R1):
 * formats spoken turn-by-turn, alert, arrival, departure, lane, and distance phrases.
 */
internal object VoicePhraseBuilder {

    fun formatReroutingPrompt(language: AppLanguage): String =
        if (language == AppLanguage.PERSIAN) "در حال مسیریابی مجدد." else "Rerouting."

    fun formatAlertAnnouncement(
        alert: AheadAlert,
        language: AppLanguage,
        unit: DistanceUnit = DistanceUnit.IMPERIAL
    ): String {
        if (alert.kind == RoadAlertKind.SPEED_CAMERA) {
            val limitMph = alert.newSpeedLimitMph
            if (limitMph != null && limitMph > 0) {
                val shownLimit = if (unit == DistanceUnit.METRIC) {
                    (limitMph * 1.60934).roundToInt()
                } else {
                    limitMph
                }
                return if (language == AppLanguage.PERSIAN) {
                    val unitText = if (unit == DistanceUnit.METRIC) "کیلومتر" else "مایل"
                    "دوربین سرعت در پیش است، سرعت مجاز $shownLimit $unitText بر ساعت."
                } else {
                    val unitText = if (unit == DistanceUnit.METRIC) "kilometers" else "miles"
                    "Speed camera ahead, speed limit $shownLimit $unitText per hour."
                }
            }
            return if (language == AppLanguage.PERSIAN) "دوربین سرعت در پیش است." else "Speed camera ahead."
        }
        if (alert.kind == RoadAlertKind.RED_LIGHT_CAMERA) {
            return if (language == AppLanguage.PERSIAN) "دوربین چراغ قرمز در پیش است." else "Red light camera ahead."
        }
        return if (language == AppLanguage.PERSIAN) {
            alert.titleFa.ifBlank { alert.titleEn }
        } else {
            alert.titleEn
        }
    }

    fun formatArrivalPrompt(type: ManeuverType, destinationName: String, language: AppLanguage): String {
        return if (language == AppLanguage.PERSIAN) {
            when (type) {
                ManeuverType.ARRIVE_RIGHT -> "به مقصد رسیدید. مقصد سمت راست شماست."
                ManeuverType.ARRIVE_LEFT -> "به مقصد رسیدید. مقصد سمت چپ شماست."
                else -> if (destinationName.isNotBlank()) "به $destinationName رسیدید." else "به مقصد رسیدید."
            }
        } else {
            when (type) {
                ManeuverType.ARRIVE_RIGHT -> "You have arrived. Your destination is on the right."
                ManeuverType.ARRIVE_LEFT -> "You have arrived. Your destination is on the left."
                else -> if (destinationName.isNotBlank()) "You have arrived at $destinationName." else "You have arrived at your destination."
            }
        }
    }

    fun formatStartInstruction(route: OfflineRoute, language: AppLanguage): String {
        val firstManeuver = route.maneuvers.firstOrNull() ?: return ""
        val nextManeuver = route.maneuvers.getOrNull(1)
        val startRoad = firstManeuver.streetNames.firstOrNull().orEmpty()
        val startBearing = computeInitialRouteBearing(route.shape)
        val cardinal = startBearing?.let { cardinalDirectionLabel(it, language) }

        val departClause = if (language == AppLanguage.PERSIAN) {
            when {
                cardinal != null && startRoad.isNotBlank() -> "به سمت $cardinal در $startRoad حرکت کنید"
                cardinal != null -> "به سمت $cardinal حرکت کنید"
                startRoad.isNotBlank() -> "در $startRoad حرکت کنید"
                else -> "مسیر را شروع کنید"
            }
        } else {
            when {
                cardinal != null && startRoad.isNotBlank() -> "Head $cardinal on $startRoad"
                cardinal != null -> "Head $cardinal"
                startRoad.isNotBlank() -> "Head along $startRoad"
                else -> "Start on the route"
            }
        }

        val thenClause = if (nextManeuver != null &&
            nextManeuver.type != ManeuverType.DEPART &&
            nextManeuver.type != ManeuverType.ARRIVE &&
            nextManeuver.type != ManeuverType.ARRIVE_LEFT &&
            nextManeuver.type != ManeuverType.ARRIVE_RIGHT
        ) {
            val nextSentence = formatSpokenInstruction(
                maneuver = nextManeuver,
                distanceMeters = 0.0,
                unit = DistanceUnit.IMPERIAL,
                language = language,
                includeLaneGuidance = false
            ).trimEnd('.')
            if (language == AppLanguage.PERSIAN) {
                "سپس $nextSentence"
            } else {
                "then ${nextSentence.replaceFirstChar { it.lowercase() }}"
            }
        } else {
            null
        }

        return if (!thenClause.isNullOrBlank()) {
            if (language == AppLanguage.PERSIAN) "$departClause، $thenClause." else "$departClause, $thenClause."
        } else {
            "$departClause."
        }
    }

    private fun computeInitialRouteBearing(shape: List<LatLon>): Double? {
        if (shape.size < 2) return null
        val start = shape.first()
        val target = shape.drop(1).firstOrNull { pt ->
            com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(
                start.lat, start.lon, pt.lat, pt.lon
            ) >= 12.0
        } ?: shape[1]
        return ValhallaResponseParser.bearingDegrees(start, target)
    }

    private fun cardinalDirectionLabel(bearingDeg: Double, language: AppLanguage): String {
        val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
        val idx = (((normalized + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)
        return if (language == AppLanguage.PERSIAN) {
            when (idx) {
                0 -> "شمال"
                1 -> "شمال شرق"
                2 -> "شرق"
                3 -> "جنوب شرق"
                4 -> "جنوب"
                5 -> "جنوب غرب"
                6 -> "غرب"
                else -> "شمال غرب"
            }
        } else {
            when (idx) {
                0 -> "north"
                1 -> "northeast"
                2 -> "east"
                3 -> "southeast"
                4 -> "south"
                5 -> "southwest"
                6 -> "west"
                else -> "northwest"
            }
        }
    }

    fun isEarlyPrepareStage(stage: String): Boolean =
        stage == NavigationSessionController.STAGE_CITY_1MI ||
            stage == NavigationSessionController.STAGE_CITY_1KM ||
            stage == NavigationSessionController.STAGE_CITY_HALF_MI ||
            stage == NavigationSessionController.STAGE_HW_1MI ||
            stage == NavigationSessionController.STAGE_HW_500M ||
            stage == NavigationSessionController.STAGE_HW_HALF_MI ||
            stage == NavigationSessionController.STAGE_HW_2MI

    fun isFinalTurnStage(stage: String): Boolean =
        stage == NavigationSessionController.STAGE_CITY_NOW ||
            stage == NavigationSessionController.STAGE_HW_NOW ||
            stage == NavigationSessionController.STAGE_BIKE_30M

    fun formatSpokenInstruction(
        maneuver: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        includeLaneGuidance: Boolean = true,
        announcementStage: String = "",
        nextManeuver: RouteManeuver? = null,
        laneDecision: LaneGuidanceDecision? = null
    ): String {
        val isFinalStage = isFinalTurnStage(announcementStage)
        val allowLanes = includeLaneGuidance &&
            !isFinalStage &&
            (laneDecision == null || (laneDecision.hasActionableLaneGuidance &&
                !laneDecision.shouldSkipLaneChangeInstruction &&
                laneDecision.confidence != LaneConfidence.LOW))
        val lanePhrase = if (allowLanes) buildLaneVoiceClause(maneuver.lanes, language) else null

        val scheduledPrimary = if (announcementStage.isNotEmpty()) {
            ApproachVoicePhraseFormatter.formatStagePhrase(
                maneuver = maneuver,
                distanceMeters = distanceMeters,
                unit = unit,
                language = language,
                stage = announcementStage,
                lanePhrase = lanePhrase
            )
        } else null

        val primary = scheduledPrimary ?: run {
            val distPhrase = if (isFinalStage) "" else formatSpokenDistance(distanceMeters, unit, language)
            val isEarlyPrepare = lanePhrase.isNullOrBlank() && isEarlyPrepareStage(announcementStage)
            val exitNum = maneuver.signpost?.primaryExitNumber
            val toward = maneuver.signpost?.exitTowards?.firstOrNull().orEmpty()
            val roadName = maneuver.streetNames.firstOrNull().orEmpty()
            if (language == AppLanguage.PERSIAN) {
                VoicePersianPhraseBuilder.buildPersianInstruction(
                    maneuver.type, distPhrase, lanePhrase, isEarlyPrepare, exitNum, toward, roadName, maneuver.roundaboutExitCount
                )
            } else {
                buildEnglishInstruction(
                    maneuver.type, distPhrase, lanePhrase, isEarlyPrepare, exitNum, toward, roadName, maneuver.roundaboutExitCount
                )
            }
        }
        val followUpClause = nextManeuver?.let { buildBackToBackClause(it, language) }
        return if (!followUpClause.isNullOrBlank()) {
            val base = primary.trimEnd('.', '۔')
            if (language == AppLanguage.PERSIAN) "$base، $followUpClause." else "$base, $followUpClause."
        } else {
            primary
        }
    }

    fun buildBackToBackClause(nextManeuver: RouteManeuver, language: AppLanguage): String? {
        val road = nextManeuver.streetNames.firstOrNull()?.takeIf { it.isNotBlank() }
        return if (language == AppLanguage.PERSIAN) {
            when (nextManeuver.type) {
                ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT ->
                    if (road != null) "سپس بلافاصله به راست وارد $road شوید" else "سپس بلافاصله به راست بپیچید"
                ManeuverType.SLIGHT_RIGHT -> "سپس بلافاصله کمی به راست بروید"
                ManeuverType.LEFT, ManeuverType.SHARP_LEFT ->
                    if (road != null) "سپس بلافاصله به چپ وارد $road شوید" else "سپس بلافاصله به چپ بپیچید"
                ManeuverType.SLIGHT_LEFT -> "سپس بلافاصله کمی به چپ بروید"
                ManeuverType.EXIT_RIGHT -> "سپس از خروجی سمت راست خارج شوید"
                ManeuverType.EXIT_LEFT -> "سپس از خروجی سمت چپ خارج شوید"
                ManeuverType.RAMP_RIGHT -> "سپس وارد رمپ سمت راست شوید"
                ManeuverType.RAMP_LEFT -> "سپس وارد رمپ سمت چپ شوید"
                ManeuverType.STAY_RIGHT -> "سپس در سمت راست بمانید"
                ManeuverType.STAY_LEFT -> "سپس در سمت چپ بمانید"
                ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> "سپس دور بزنید"
                ManeuverType.ROUNDABOUT -> "سپس وارد میدان شوید"
                else -> null
            }
        } else {
            when (nextManeuver.type) {
                ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT ->
                    if (road != null) "then take the next right onto $road" else "then take the next right"
                ManeuverType.SLIGHT_RIGHT ->
                    if (road != null) "then bear right onto $road" else "then bear right"
                ManeuverType.LEFT, ManeuverType.SHARP_LEFT ->
                    if (road != null) "then take the next left onto $road" else "then take the next left"
                ManeuverType.SLIGHT_LEFT ->
                    if (road != null) "then bear left onto $road" else "then bear left"
                ManeuverType.EXIT_RIGHT -> "then take the right exit"
                ManeuverType.EXIT_LEFT -> "then take the left exit"
                ManeuverType.RAMP_RIGHT -> "then take the right ramp"
                ManeuverType.RAMP_LEFT -> "then take the left ramp"
                ManeuverType.STAY_RIGHT -> "then keep right"
                ManeuverType.STAY_LEFT -> "then keep left"
                ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> "then make a U-turn"
                ManeuverType.ROUNDABOUT -> "then enter the roundabout"
                else -> null
            }
        }
    }

    private fun buildEnglishInstruction(
        type: ManeuverType,
        distPhrase: String,
        lanePhrase: String?,
        isEarlyPrepare: Boolean,
        exitNum: String?,
        toward: String,
        roadName: String,
        roundaboutExit: Int?
    ): String {
        val prefix = if (distPhrase.isNotEmpty()) "In $distPhrase, " else ""

        val core = when (type) {
            ManeuverType.LEFT -> buildOnto("turn left", roadName)
            ManeuverType.SLIGHT_LEFT -> buildOnto("bear left", roadName)
            ManeuverType.SHARP_LEFT -> buildOnto("make a sharp left", roadName)
            ManeuverType.RIGHT -> buildOnto("turn right", roadName)
            ManeuverType.SLIGHT_RIGHT -> buildOnto("bear right", roadName)
            ManeuverType.SHARP_RIGHT -> buildOnto("make a sharp right", roadName)
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT ->
                if (roadName.isNotBlank()) "make a U-turn onto $roadName" else "make a U-turn"
            ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                val base = if (!exitNum.isNullOrBlank()) "take exit $exitNum" else "take the exit"
                when {
                    toward.isNotBlank() -> "$base toward $toward"
                    roadName.isNotBlank() -> "$base onto $roadName"
                    else -> base
                }
            }
            ManeuverType.RAMP_RIGHT -> {
                val base = "take the ramp on the right"
                if (roadName.isNotBlank()) "$base onto $roadName" else base
            }
            ManeuverType.RAMP_LEFT -> {
                val base = "take the ramp on the left"
                if (roadName.isNotBlank()) "$base onto $roadName" else base
            }
            ManeuverType.STAY_LEFT -> {
                val base = "keep left at the fork"
                if (toward.isNotBlank()) "$base toward $toward"
                else if (roadName.isNotBlank()) "$base onto $roadName" else base
            }
            ManeuverType.STAY_RIGHT -> {
                val base = "keep right at the fork"
                if (toward.isNotBlank()) "$base toward $toward"
                else if (roadName.isNotBlank()) "$base onto $roadName" else base
            }
            ManeuverType.MERGE -> if (roadName.isNotBlank()) "merge onto $roadName" else "merge"
            ManeuverType.ROUNDABOUT -> {
                val ordinal = englishOrdinal(roundaboutExit ?: 1)
                val base = "at the roundabout, take the $ordinal exit"
                if (roadName.isNotBlank()) "$base onto $roadName" else base
            }
            ManeuverType.ARRIVE_RIGHT -> return if (distPhrase.isNotEmpty()) {
                "In $distPhrase, your destination will be on the right."
            } else {
                "Your destination is on the right."
            }
            ManeuverType.ARRIVE_LEFT -> return if (distPhrase.isNotEmpty()) {
                "In $distPhrase, your destination will be on the left."
            } else {
                "Your destination is on the left."
            }
            ManeuverType.ARRIVE -> return if (distPhrase.isNotEmpty()) {
                "In $distPhrase, you will arrive at your destination."
            } else {
                "You have arrived at your destination."
            }
            else -> if (roadName.isNotBlank()) "continue on $roadName" else "continue straight"
        }

        val sentence = when {
            !lanePhrase.isNullOrBlank() -> "${prefix}use $lanePhrase to $core."
            isEarlyPrepare && type != ManeuverType.STRAIGHT && type != ManeuverType.DEPART -> {
                if (type == ManeuverType.ROUNDABOUT) {
                    val ordinal = englishOrdinal(roundaboutExit ?: 1)
                    val exitTarget = if (roadName.isNotBlank()) " onto $roadName" else ""
                    "${prefix}prepare to take the $ordinal exit at the roundabout$exitTarget."
                } else {
                    "${prefix}prepare to $core."
                }
            }
            else -> "${prefix}$core."
        }
        return sentence.replaceFirstChar { it.uppercase() }
    }

    private fun buildOnto(verb: String, roadName: String): String =
        if (roadName.isNotBlank()) "$verb onto $roadName" else verb

    private fun englishOrdinal(n: Int): String = when (n) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        5 -> "fifth"
        else -> "${n}th"
    }

    fun buildLaneVoiceClause(lanes: List<LaneInfo>, language: AppLanguage): String? {
        if (lanes.size < 2) return null
        val activeIndices = lanes.mapIndexedNotNull { idx, lane ->
            if (lane.highlightTier == LaneHighlightTier.ACTIVE) idx else null
        }.ifEmpty {
            lanes.mapIndexedNotNull { idx, lane -> if (lane.isRecommended) idx else null }
        }
        if (activeIndices.isEmpty() || activeIndices.size == lanes.size) return null

        val count = activeIndices.size
        val fromRight = activeIndices.last() == lanes.lastIndex
        val fromLeft = activeIndices.first() == 0

        if (count == 1 && !fromRight && !fromLeft && lanes.size >= 4) {
            val idx = activeIndices.first()
            val posFromLeft = idx + 1
            val posFromRight = lanes.size - idx
            return if (posFromRight <= posFromLeft) {
                if (language == AppLanguage.PERSIAN) "خط ${persianLaneOrdinal(posFromRight)} از سمت راست"
                else "the ${englishOrdinal(posFromRight)} lane from the right"
            } else {
                if (language == AppLanguage.PERSIAN) "خط ${persianLaneOrdinal(posFromLeft)} از سمت چپ"
                else "the ${englishOrdinal(posFromLeft)} lane from the left"
            }
        }

        return if (language == AppLanguage.PERSIAN) {
            val countWord = when (count) {
                1 -> "خط"
                2 -> "دو خط"
                3 -> "سه خط"
                else -> "$count خط"
            }
            when {
                fromRight -> "$countWord سمت راست"
                fromLeft -> "$countWord سمت چپ"
                else -> "$countWord میانی"
            }
        } else {
            val countWord = when (count) {
                1 -> "lane"
                2 -> "two lanes"
                3 -> "three lanes"
                else -> "$count lanes"
            }
            when {
                fromRight && count == 1 -> "the right lane"
                fromRight -> "the right $countWord"
                fromLeft && count == 1 -> "the left lane"
                fromLeft -> "the left $countWord"
                count == 1 -> "the middle lane"
                else -> "the middle $countWord"
            }
        }
    }

    private fun persianLaneOrdinal(n: Int): String = when (n) {
        1 -> "اول"
        2 -> "دوم"
        3 -> "سوم"
        4 -> "چهارم"
        5 -> "پنجم"
        else -> "${n}م"
    }

    fun formatSpokenDistance(meters: Double, unit: DistanceUnit, language: AppLanguage): String =
        VoicePersianPhraseBuilder.formatSpokenDistance(meters, unit, language)
}
