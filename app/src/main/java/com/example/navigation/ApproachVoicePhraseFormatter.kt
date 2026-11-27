package com.example.navigation

import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit

/**
 * Formats spoken English and natural Persian phrases for the unified highway (2 mi / 1 mi / 500 m / now)
 * and city (1 km / post-signal / pre-stop-line / combined) approach schedules.
 *
 * Uses actual exit number, side, street, or destination when available and omits unavailable details.
 * Preserves the requested mixed units on highways (2 miles, 1 mile, 500 meters) and uses actual
 * remaining distance when a stage fires late due to late start or a GPS gap.
 */
internal object ApproachVoicePhraseFormatter {

    fun formatStagePhrase(
        maneuver: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        stage: String,
        lanePhrase: String?
    ): String? {
        return when (stage) {
            NavigationSessionController.STAGE_HW_2MI ->
                if (isHighwayDecision(maneuver)) formatHighwayTwoMile(maneuver, distanceMeters, unit, language) else null
            NavigationSessionController.STAGE_HW_1MI ->
                if (isHighwayDecision(maneuver)) formatHighwayOneMile(maneuver, distanceMeters, unit, language, lanePhrase) else null
            NavigationSessionController.STAGE_HW_500M ->
                formatHighway500Meters(maneuver, distanceMeters, language)
            NavigationSessionController.STAGE_HW_NOW ->
                if (isHighwayDecision(maneuver)) formatHighwayNow(maneuver, language) else null
            NavigationSessionController.STAGE_CITY_1KM ->
                formatCityOneKilometer(maneuver, distanceMeters, unit, language)
            NavigationSessionController.STAGE_CITY_POST_SIGNAL ->
                formatCityNextTurnAfterSignal(maneuver, language)
            NavigationSessionController.STAGE_CITY_POST_SIGNAL_DIST ->
                formatCityDistanceAfterSignal(maneuver, distanceMeters, unit, language, lanePhrase)
            NavigationSessionController.STAGE_CITY_1KM_POST_SIGNAL ->
                formatCityCombined1KmAndSignal(maneuver, distanceMeters, unit, language, lanePhrase)
            NavigationSessionController.STAGE_CITY_PRE_STOP_LINE ->
                formatCityPreStopLine(maneuver, distanceMeters, unit, language, lanePhrase)
            else -> null
        }
    }

    private fun isHighwayDecision(m: RouteManeuver): Boolean =
        m.isHighwayExitOrSplit || m.type in setOf(
            ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT,
            ManeuverType.RAMP_RIGHT, ManeuverType.RAMP_LEFT,
            ManeuverType.STAY_RIGHT, ManeuverType.STAY_LEFT
        )

    private fun formatHighwayTwoMile(
        m: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage
    ): String {
        val distEn = if (distanceMeters in 2_900.0..3_550.0) "two miles"
        else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.ENGLISH)
        val distFa = if (distanceMeters in 2_900.0..3_550.0) "دو مایلی"
        else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.PERSIAN)

        return if (language == AppLanguage.PERSIAN) {
            val action = persianHighwayExitOrForkAction(m, includeSide = true)
            "در $distFa، $action."
        } else {
            val action = englishHighwayExitOrForkAction(m, includeSide = true)
            "In $distEn, $action."
        }
    }

    private fun formatHighwayOneMile(
        m: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        lanePhrase: String?
    ): String {
        val nearOneMile = distanceMeters in 1_350.0..1_850.0
        val distEn = if (nearOneMile) "one mile"
        else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.ENGLISH)
        val distFa = if (nearOneMile) "یک مایلی"
        else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.PERSIAN)

        val exitNum = m.signpost?.primaryExitNumber
        val toward = m.signpost?.exitTowards?.firstOrNull().orEmpty()
        val roadName = m.streetNames.firstOrNull().orEmpty()

        if (language == AppLanguage.PERSIAN) {
            if (!lanePhrase.isNullOrBlank()) {
                val target = persianExitTarget(m, exitNum, toward, roadName)
                return "در $distFa، از $lanePhrase برای $target استفاده کنید."
            }
            val action = persianHighwayExitOrForkAction(m, includeSide = true)
            return "در $distFa، $action."
        }

        if (!lanePhrase.isNullOrBlank()) {
            val target = englishExitForClause(m, exitNum, toward, roadName)
            return "In $distEn, use $lanePhrase $target."
        }
        val action = englishHighwayExitOrForkAction(m, includeSide = true)
        return "In $distEn, $action."
    }

    private fun formatHighway500Meters(
        m: RouteManeuver,
        distanceMeters: Double,
        language: AppLanguage
    ): String {
        val near500 = distanceMeters in 410.0..560.0
        val roundedMeters = ((kotlin.math.round(distanceMeters / 50.0).toInt()) * 50).coerceAtLeast(150)
        return if (language == AppLanguage.PERSIAN) {
            val distFa = if (near500) "500 متری" else "$roundedMeters متری"
            val action = persianHighwayExitOrForkAction(m, includeSide = true)
            "در $distFa، $action."
        } else {
            val distEn = if (near500) "500 meters" else "$roundedMeters meters"
            val action = englishHighwayExitOrForkAction(m, includeSide = true)
            "In $distEn, $action."
        }
    }

    private fun formatHighwayNow(m: RouteManeuver, language: AppLanguage): String {
        val exitNum = m.signpost?.primaryExitNumber
        val toward = m.signpost?.exitTowards?.firstOrNull().orEmpty()
        val roadName = m.streetNames.firstOrNull().orEmpty()
        return if (language == AppLanguage.PERSIAN) {
            when (m.type) {
                ManeuverType.STAY_LEFT ->
                    if (toward.isNotBlank()) "الان در دوراهی سمت چپ به سمت $toward بمانید."
                    else "الان در دوراهی سمت چپ بمانید."
                ManeuverType.STAY_RIGHT ->
                    if (toward.isNotBlank()) "الان در دوراهی سمت راست به سمت $toward بمانید."
                    else "الان در دوراهی سمت راست بمانید."
                else -> {
                    val exitPart = if (!exitNum.isNullOrBlank()) "خروجی $exitNum" else "خروجی"
                    if (toward.isNotBlank()) "الان از $exitPart به سمت $toward خارج شوید."
                    else if (roadName.isNotBlank()) "الان از $exitPart وارد $roadName شوید."
                    else "الان از $exitPart خارج شوید."
                }
            }
        } else {
            when (m.type) {
                ManeuverType.STAY_LEFT ->
                    if (toward.isNotBlank()) "Keep left toward $toward now." else "Keep left now."
                ManeuverType.STAY_RIGHT ->
                    if (toward.isNotBlank()) "Keep right toward $toward now." else "Keep right now."
                else -> {
                    val base = if (!exitNum.isNullOrBlank()) "Take Exit $exitNum" else "Take the exit"
                    when {
                        toward.isNotBlank() -> "$base toward $toward now."
                        roadName.isNotBlank() -> "$base onto $roadName now."
                        else -> "$base now."
                    }
                }
            }
        }
    }

    private fun formatCityOneKilometer(
        m: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage
    ): String {
        val near1Km = distanceMeters in 880.0..1_100.0
        return if (language == AppLanguage.PERSIAN) {
            val distFa = if (near1Km) "یک کیلومتری"
            else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.PERSIAN)
            val action = persianCityAction(m)
            "در $distFa، $action."
        } else {
            val distEn = if (near1Km) "one kilometer"
            else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.ENGLISH)
            val action = englishCityAction(m)
            "In $distEn, $action."
        }
    }

    private fun formatCityNextTurnAfterSignal(m: RouteManeuver, language: AppLanguage): String {
        val roadName = m.streetNames.firstOrNull().orEmpty()
        return if (language == AppLanguage.PERSIAN) {
            val side = when (m.type) {
                ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> "چپ"
                else -> "راست"
            }
            if (roadName.isNotBlank()) "در پیچ $side بعدی وارد $roadName شوید."
            else "در پیچ $side بعدی بپیچید."
        } else {
            val side = when (m.type) {
                ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> "left"
                else -> "right"
            }
            if (roadName.isNotBlank()) "Take the next $side onto $roadName."
            else "Take the next $side."
        }
    }

    private fun formatCityDistanceAfterSignal(
        m: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        lanePhrase: String?
    ): String {
        val distStr = VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, language)
        return if (language == AppLanguage.PERSIAN) {
            val prefix = if (distStr.isNotBlank()) "در $distStr، " else ""
            val action = persianCityAction(m)
            if (!lanePhrase.isNullOrBlank()) "${prefix}از $lanePhrase استفاده کنید و $action."
            else "${prefix}$action."
        } else {
            val prefix = if (distStr.isNotBlank()) "In $distStr, " else ""
            val action = englishCityAction(m)
            val sentence = if (!lanePhrase.isNullOrBlank()) "${prefix}use $lanePhrase to $action."
            else "${prefix}$action."
            sentence.replaceFirstChar { it.uppercase() }
        }
    }

    private fun formatCityCombined1KmAndSignal(
        m: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        lanePhrase: String?
    ): String {
        val near1Km = distanceMeters in 880.0..1_100.0
        return if (language == AppLanguage.PERSIAN) {
            val distFa = if (near1Km) "یک کیلومتری"
            else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.PERSIAN)
            val action = persianCityAction(m)
            if (!lanePhrase.isNullOrBlank()) "بعد از چراغ راهنمایی، در $distFa از $lanePhrase استفاده کنید و $action."
            else "بعد از چراغ راهنمایی، در $distFa $action."
        } else {
            val distEn = if (near1Km) "one kilometer"
            else VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, AppLanguage.ENGLISH)
            val action = englishCityAction(m)
            if (!lanePhrase.isNullOrBlank()) "After the traffic light, in $distEn, use $lanePhrase to $action."
            else "After the traffic light, in $distEn, $action."
        }
    }

    private fun formatCityPreStopLine(
        m: RouteManeuver,
        distanceMeters: Double,
        unit: DistanceUnit,
        language: AppLanguage,
        lanePhrase: String?
    ): String {
        val distStr = VoicePersianPhraseBuilder.formatSpokenDistance(distanceMeters, unit, language)
        return if (language == AppLanguage.PERSIAN) {
            val action = persianCityAction(m)
            when {
                !lanePhrase.isNullOrBlank() -> "در چراغ راهنمایی پیش رو، از $lanePhrase استفاده کنید و $action."
                distStr.isNotBlank() -> "در $distStr، در چراغ راهنمایی $action."
                else -> "در چراغ راهنمایی پیش رو، $action."
            }
        } else {
            val action = englishCityAction(m)
            when {
                !lanePhrase.isNullOrBlank() -> "At the traffic light ahead, use $lanePhrase to $action."
                distStr.isNotBlank() -> "In $distStr, at the traffic light, $action."
                else -> "At the traffic light ahead, $action."
            }
        }
    }

    private fun englishHighwayExitOrForkAction(m: RouteManeuver, includeSide: Boolean): String {
        val exitNum = m.signpost?.primaryExitNumber
        val toward = m.signpost?.exitTowards?.firstOrNull().orEmpty()
        val roadName = m.streetNames.firstOrNull().orEmpty()
        val sideText = when (m.type) {
            ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT, ManeuverType.STAY_LEFT -> "on the left"
            ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.STAY_RIGHT -> "on the right"
            else -> ""
        }
        return when (m.type) {
            ManeuverType.STAY_LEFT -> buildSuffix("keep left", toward, roadName)
            ManeuverType.STAY_RIGHT -> buildSuffix("keep right", toward, roadName)
            ManeuverType.RAMP_LEFT -> buildSuffix("take the ramp on the left", toward, roadName)
            ManeuverType.RAMP_RIGHT -> buildSuffix("take the ramp on the right", toward, roadName)
            else -> {
                val base = if (!exitNum.isNullOrBlank()) "take Exit $exitNum" else "take the exit"
                val withSide = if (includeSide && sideText.isNotBlank()) "$base $sideText" else base
                buildSuffix(withSide, toward, roadName)
            }
        }
    }

    private fun englishExitForClause(
        m: RouteManeuver,
        exitNum: String?,
        toward: String,
        roadName: String
    ): String {
        return when (m.type) {
            ManeuverType.STAY_LEFT -> buildSuffix("to keep left", toward, roadName)
            ManeuverType.STAY_RIGHT -> buildSuffix("to keep right", toward, roadName)
            else -> {
                val exitPart = if (!exitNum.isNullOrBlank()) "for Exit $exitNum" else "for the exit"
                when {
                    toward.isNotBlank() -> "$exitPart toward $toward"
                    roadName.isNotBlank() -> "$exitPart onto $roadName"
                    else -> exitPart
                }
            }
        }
    }

    private fun persianHighwayExitOrForkAction(m: RouteManeuver, includeSide: Boolean): String {
        val exitNum = m.signpost?.primaryExitNumber
        val toward = m.signpost?.exitTowards?.firstOrNull().orEmpty()
        val roadName = m.streetNames.firstOrNull().orEmpty()
        val sideFa = when (m.type) {
            ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT, ManeuverType.STAY_LEFT -> "سمت چپ"
            ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.STAY_RIGHT -> "سمت راست"
            else -> ""
        }
        return when (m.type) {
            ManeuverType.STAY_LEFT ->
                if (toward.isNotBlank()) "در دوراهی سمت چپ به سمت $toward بمانید"
                else if (roadName.isNotBlank()) "در دوراهی سمت چپ به سمت $roadName بمانید"
                else "در دوراهی سمت چپ بمانید"
            ManeuverType.STAY_RIGHT ->
                if (toward.isNotBlank()) "در دوراهی سمت راست به سمت $toward بمانید"
                else if (roadName.isNotBlank()) "در دوراهی سمت راست به سمت $roadName بمانید"
                else "در دوراهی سمت راست بمانید"
            else -> {
                val exitPart = if (!exitNum.isNullOrBlank()) "خروجی $exitNum" else "خروجی"
                val sideClause = if (includeSide && sideFa.isNotBlank()) " در $sideFa" else ""
                when {
                    toward.isNotBlank() -> "از $exitPart$sideClause به سمت $toward خارج شوید"
                    roadName.isNotBlank() -> "از $exitPart$sideClause وارد $roadName شوید"
                    else -> "از $exitPart$sideClause خارج شوید"
                }
            }
        }
    }

    private fun persianExitTarget(
        m: RouteManeuver,
        exitNum: String?,
        toward: String,
        roadName: String
    ): String {
        return when (m.type) {
            ManeuverType.STAY_LEFT ->
                if (toward.isNotBlank()) "ماندن در سمت چپ به سمت $toward" else "ماندن در سمت چپ"
            ManeuverType.STAY_RIGHT ->
                if (toward.isNotBlank()) "ماندن در سمت راست به سمت $toward" else "ماندن در سمت راست"
            else -> {
                val exitPart = if (!exitNum.isNullOrBlank()) "خروجی $exitNum" else "خروجی"
                when {
                    toward.isNotBlank() -> "$exitPart به سمت $toward"
                    roadName.isNotBlank() -> "$exitPart به سمت $roadName"
                    else -> exitPart
                }
            }
        }
    }

    private fun englishCityAction(m: RouteManeuver): String {
        val roadName = m.streetNames.firstOrNull().orEmpty()
        val exitNum = m.signpost?.primaryExitNumber
        val toward = m.signpost?.exitTowards?.firstOrNull().orEmpty()
        return when (m.type) {
            ManeuverType.LEFT -> buildOnto("turn left", roadName)
            ManeuverType.SLIGHT_LEFT -> buildOnto("bear left", roadName)
            ManeuverType.SHARP_LEFT -> buildOnto("make a sharp left", roadName)
            ManeuverType.RIGHT -> buildOnto("turn right", roadName)
            ManeuverType.SLIGHT_RIGHT -> buildOnto("bear right", roadName)
            ManeuverType.SHARP_RIGHT -> buildOnto("make a sharp right", roadName)
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> buildOnto("make a U-turn", roadName)
            ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.RAMP_LEFT ->
                englishHighwayExitOrForkAction(m, includeSide = true)
            ManeuverType.STAY_LEFT -> buildSuffix("keep left", toward, roadName)
            ManeuverType.STAY_RIGHT -> buildSuffix("keep right", toward, roadName)
            ManeuverType.ROUNDABOUT -> {
                val n = m.roundaboutExitCount ?: 1
                val ord = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; else -> "${n}th" }
                buildOnto("at the roundabout, take the $ord exit", roadName)
            }
            else -> if (!exitNum.isNullOrBlank()) "take Exit $exitNum" else buildOnto("continue", roadName)
        }
    }

    private fun persianCityAction(m: RouteManeuver): String {
        val roadName = m.streetNames.firstOrNull().orEmpty()
        return when (m.type) {
            ManeuverType.LEFT -> if (roadName.isNotBlank()) "به چپ بپیچید و وارد $roadName شوید" else "به چپ بپیچید"
            ManeuverType.SLIGHT_LEFT -> if (roadName.isNotBlank()) "کمی به چپ بروید و وارد $roadName شوید" else "کمی به چپ متمایل شوید"
            ManeuverType.SHARP_LEFT -> if (roadName.isNotBlank()) "پیچ تند به چپ بگیرید و وارد $roadName شوید" else "پیچ تند به چپ بگیرید"
            ManeuverType.RIGHT -> if (roadName.isNotBlank()) "به راست بپیچید و وارد $roadName شوید" else "به راست بپیچید"
            ManeuverType.SLIGHT_RIGHT -> if (roadName.isNotBlank()) "کمی به راست بروید و وارد $roadName شوید" else "کمی به راست متمایل شوید"
            ManeuverType.SHARP_RIGHT -> if (roadName.isNotBlank()) "پیچ تند به راست بگیرید و وارد $roadName شوید" else "پیچ تند به راست بگیرید"
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> if (roadName.isNotBlank()) "دور بزنید و وارد $roadName شوید" else "دور بزنید"
            ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.RAMP_LEFT ->
                persianHighwayExitOrForkAction(m, includeSide = true)
            ManeuverType.STAY_LEFT -> if (roadName.isNotBlank()) "سمت چپ به سمت $roadName بمانید" else "سمت چپ بمانید"
            ManeuverType.STAY_RIGHT -> if (roadName.isNotBlank()) "سمت راست به سمت $roadName بمانید" else "سمت راست بمانید"
            ManeuverType.ROUNDABOUT -> {
                val n = m.roundaboutExitCount ?: 1
                val ord = when (n) { 1 -> "اول"; 2 -> "دوم"; 3 -> "سوم"; 4 -> "چهارم"; else -> "${n}م" }
                if (roadName.isNotBlank()) "در میدان از خروجی $ord وارد $roadName شوید" else "در میدان از خروجی $ord خارج شوید"
            }
            else -> if (roadName.isNotBlank()) "در $roadName مستقیم ادامه دهید" else "مستقیم ادامه دهید"
        }
    }

    private fun buildOnto(verb: String, roadName: String): String =
        if (roadName.isNotBlank()) "$verb onto $roadName" else verb

    private fun buildSuffix(base: String, toward: String, roadName: String): String = when {
        toward.isNotBlank() -> "$base toward $toward"
        roadName.isNotBlank() -> "$base onto $roadName"
        else -> base
    }
}
