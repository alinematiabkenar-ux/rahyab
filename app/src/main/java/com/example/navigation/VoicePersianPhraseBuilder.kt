package com.example.navigation

/**
 * Pure mechanical extraction from [VoiceGuidanceManager] (R1):
 * builds natural spoken Persian navigation instructions.
 */
internal object VoicePersianPhraseBuilder {

    fun buildPersianInstruction(
        type: ManeuverType,
        distPhrase: String,
        lanePhrase: String?,
        isEarlyPrepare: Boolean,
        exitNum: String?,
        toward: String,
        roadName: String,
        roundaboutExit: Int?
    ): String {
        val prefix = if (distPhrase.isNotEmpty()) "در $distPhrase، " else ""

        if (!lanePhrase.isNullOrBlank()) {
            val integrated = when (type) {
                ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                    val exitPart = if (!exitNum.isNullOrBlank()) "خروجی $exitNum" else "خروجی"
                    when {
                        toward.isNotBlank() -> "${prefix}از $lanePhrase وارد $exitPart به سمت $toward شوید."
                        roadName.isNotBlank() -> "${prefix}از $lanePhrase وارد $exitPart و $roadName شوید."
                        else -> "${prefix}از $lanePhrase وارد $exitPart شوید."
                    }
                }
                ManeuverType.LEFT ->
                    if (roadName.isNotBlank()) "${prefix}از $lanePhrase به چپ بپیچید و وارد $roadName شوید."
                    else "${prefix}از $lanePhrase به چپ بپیچید."
                ManeuverType.RIGHT ->
                    if (roadName.isNotBlank()) "${prefix}از $lanePhrase به راست بپیچید و وارد $roadName شوید."
                    else "${prefix}از $lanePhrase به راست بپیچید."
                ManeuverType.STAY_LEFT ->
                    if (toward.isNotBlank()) "${prefix}از $lanePhrase سمت چپ به سمت $toward بمانید."
                    else "${prefix}از $lanePhrase سمت چپ بمانید."
                ManeuverType.STAY_RIGHT ->
                    if (toward.isNotBlank()) "${prefix}از $lanePhrase سمت راست به سمت $toward بمانید."
                    else "${prefix}از $lanePhrase سمت راست بمانید."
                ManeuverType.MERGE ->
                    if (roadName.isNotBlank()) "${prefix}از $lanePhrase وارد بزرگراه $roadName شوید."
                    else "${prefix}از $lanePhrase وارد مسیر اصلی شوید."
                else -> null
            }
            if (integrated != null) return integrated
        }

        if (isEarlyPrepare) {
            val prepareSentence = when (type) {
                ManeuverType.RIGHT ->
                    if (roadName.isNotBlank()) "${prefix}برای پیچیدن به راست به سمت $roadName آماده شوید."
                    else "${prefix}برای پیچیدن به راست آماده شوید."
                ManeuverType.LEFT ->
                    if (roadName.isNotBlank()) "${prefix}برای پیچیدن به چپ به سمت $roadName آماده شوید."
                    else "${prefix}برای پیچیدن به چپ آماده شوید."
                ManeuverType.SLIGHT_RIGHT ->
                    if (roadName.isNotBlank()) "${prefix}برای متمایل شدن به راست به سمت $roadName آماده شوید."
                    else "${prefix}برای متمایل شدن به راست آماده شوید."
                ManeuverType.SLIGHT_LEFT ->
                    if (roadName.isNotBlank()) "${prefix}برای متمایل شدن به چپ به سمت $roadName آماده شوید."
                    else "${prefix}برای متمایل شدن به چپ آماده شوید."
                ManeuverType.SHARP_RIGHT ->
                    if (roadName.isNotBlank()) "${prefix}برای پیچ تند به راست به سمت $roadName آماده شوید."
                    else "${prefix}برای پیچ تند به راست آماده شوید."
                ManeuverType.SHARP_LEFT ->
                    if (roadName.isNotBlank()) "${prefix}برای پیچ تند به چپ به سمت $roadName آماده شوید."
                    else "${prefix}برای پیچ تند به چپ آماده شوید."
                ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT ->
                    if (roadName.isNotBlank()) "${prefix}برای دور زدن به سمت $roadName آماده شوید."
                    else "${prefix}برای دور زدن آماده شوید."
                ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                    val exitPart = if (!exitNum.isNullOrBlank()) "خروجی $exitNum" else "خروجی"
                    when {
                        toward.isNotBlank() -> "${prefix}برای ورود به $exitPart به سمت $toward آماده شوید."
                        roadName.isNotBlank() -> "${prefix}برای ورود به $exitPart به سمت $roadName آماده شوید."
                        else -> "${prefix}برای ورود به $exitPart آماده شوید."
                    }
                }
                ManeuverType.RAMP_RIGHT ->
                    if (roadName.isNotBlank()) "${prefix}برای ورود به رمپ سمت راست به سمت $roadName آماده شوید."
                    else "${prefix}برای ورود به رمپ سمت راست آماده شوید."
                ManeuverType.RAMP_LEFT ->
                    if (roadName.isNotBlank()) "${prefix}برای ورود به رمپ سمت چپ به سمت $roadName آماده شوید."
                    else "${prefix}برای ورود به رمپ سمت چپ آماده شوید."
                ManeuverType.STAY_RIGHT -> {
                    val target = toward.ifBlank { roadName }
                    if (target.isNotBlank()) "${prefix}برای ماندن در سمت راست به سمت $target آماده شوید."
                    else "${prefix}برای ماندن در سمت راست در دوراهی آماده شوید."
                }
                ManeuverType.STAY_LEFT -> {
                    val target = toward.ifBlank { roadName }
                    if (target.isNotBlank()) "${prefix}برای ماندن در سمت چپ به سمت $target آماده شوید."
                    else "${prefix}برای ماندن در سمت چپ در دوراهی آماده شوید."
                }
                ManeuverType.MERGE ->
                    if (roadName.isNotBlank()) "${prefix}برای ورود به $roadName آماده شوید."
                    else "${prefix}برای ورود به مسیر اصلی آماده شوید."
                else -> null
            }
            if (prepareSentence != null) return prepareSentence
        }

        val core = when (type) {
            ManeuverType.LEFT ->
                if (roadName.isNotBlank()) "به چپ بپیچید و وارد $roadName شوید" else "به چپ بپیچید"
            ManeuverType.SLIGHT_LEFT ->
                if (roadName.isNotBlank()) "کمی به چپ بروید و وارد $roadName شوید" else "کمی به چپ متمایل شوید"
            ManeuverType.SHARP_LEFT ->
                if (roadName.isNotBlank()) "پیچ تند به چپ بگیرید و وارد $roadName شوید" else "پیچ تند به چپ بگیرید"
            ManeuverType.RIGHT ->
                if (roadName.isNotBlank()) "به راست بپیچید و وارد $roadName شوید" else "به راست بپیچید"
            ManeuverType.SLIGHT_RIGHT ->
                if (roadName.isNotBlank()) "کمی به راست بروید و وارد $roadName شوید" else "کمی به راست متمایل شوید"
            ManeuverType.SHARP_RIGHT ->
                if (roadName.isNotBlank()) "پیچ تند به راست بگیرید و وارد $roadName شوید" else "پیچ تند به راست بگیرید"
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT ->
                if (roadName.isNotBlank()) "دور بزنید و وارد $roadName شوید" else "دور بزنید"
            ManeuverType.EXIT_RIGHT, ManeuverType.EXIT_LEFT -> {
                val exitPart = if (!exitNum.isNullOrBlank()) "از خروجی $exitNum" else "از خروجی"
                when {
                    toward.isNotBlank() -> "$exitPart به سمت $toward خارج شوید"
                    roadName.isNotBlank() -> "$exitPart وارد $roadName شوید"
                    else -> "$exitPart خارج شوید"
                }
            }
            ManeuverType.RAMP_RIGHT ->
                if (roadName.isNotBlank()) "از رمپ سمت راست وارد $roadName شوید" else "وارد رمپ سمت راست شوید"
            ManeuverType.RAMP_LEFT ->
                if (roadName.isNotBlank()) "از رمپ سمت چپ وارد $roadName شوید" else "وارد رمپ سمت چپ شوید"
            ManeuverType.STAY_LEFT -> {
                val base = "در دوراهی، سمت چپ بمانید"
                if (toward.isNotBlank()) "$base به سمت $toward"
                else if (roadName.isNotBlank()) "$base به سمت $roadName" else base
            }
            ManeuverType.STAY_RIGHT -> {
                val base = "در دوراهی، سمت راست بمانید"
                if (toward.isNotBlank()) "$base به سمت $toward"
                else if (roadName.isNotBlank()) "$base به سمت $roadName" else base
            }
            ManeuverType.MERGE ->
                if (roadName.isNotBlank()) "وارد $roadName شوید" else "وارد مسیر اصلی شوید"
            ManeuverType.ROUNDABOUT -> {
                val ord = persianOrdinal(roundaboutExit ?: 1)
                val base = "در میدان، از خروجی $ord خارج شوید"
                if (roadName.isNotBlank()) "$base و وارد $roadName شوید" else base
            }
            ManeuverType.ARRIVE_RIGHT -> return if (distPhrase.isNotEmpty()) {
                "در $distPhrase، مقصد سمت راست شماست."
            } else {
                "مقصد سمت راست شماست."
            }
            ManeuverType.ARRIVE_LEFT -> return if (distPhrase.isNotEmpty()) {
                "در $distPhrase، مقصد سمت چپ شماست."
            } else {
                "مقصد سمت چپ شماست."
            }
            ManeuverType.ARRIVE -> return if (distPhrase.isNotEmpty()) {
                "در $distPhrase، به مقصد خواهید رسید."
            } else {
                "به مقصد رسیدید."
            }
            else -> if (roadName.isNotBlank()) "در $roadName مستقیم ادامه دهید" else "مستقیم ادامه دهید"
        }

        return if (!lanePhrase.isNullOrBlank()) {
            "${prefix}از $lanePhrase استفاده کنید؛ $core."
        } else {
            "${prefix}$core."
        }
    }

    private fun persianOrdinal(n: Int): String = when (n) {
        1 -> "اول"
        2 -> "دوم"
        3 -> "سوم"
        4 -> "چهارم"
        5 -> "پنجم"
        else -> "${n}م"
    }
}
