package com.example.navigation

/**
 * VC2: Pure Kotlin offline English voice command parser.
 *
 * Parses transcripts returned by Android's on-device English SpeechRecognizer into strongly-typed
 * navigation commands. Unknown or empty phrases return [VoiceCommand.Unknown].
 */
sealed class VoiceCommand {
    data class NavigateTo(val query: String) : VoiceCommand()
    data object GoHome : VoiceCommand()
    data object GoWork : VoiceCommand()
    data class FindNearest(val categoryQuery: String) : VoiceCommand()
    data object WhereIsMyCar : VoiceCommand()
    data object WalkToMyCar : VoiceCommand()
    data object StopNavigation : VoiceCommand()
    data object Mute : VoiceCommand()
    data object Unmute : VoiceCommand()
    data object AskEta : VoiceCommand()
    data object RepeatInstruction : VoiceCommand()
    data object ReportRoadClosed : VoiceCommand()
    data object ZoomIn : VoiceCommand()
    data object ZoomOut : VoiceCommand()
    data class Unknown(val rawText: String) : VoiceCommand()
}

object VoiceCommandParser {

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val EDGE_PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':', '"', '\'', ' ')

    private val GO_HOME_PHRASES = setOf(
        "go home",
        "take me home",
        "navigate home",
        "navigate to home",
        "drive home",
        "drive me home",
        "go to home"
    )

    private val GO_WORK_PHRASES = setOf(
        "go to work",
        "take me to work",
        "navigate to work",
        "go work",
        "drive to work",
        "drive me to work",
        "navigate work"
    )

    private val WHERE_IS_CAR_PHRASES = setOf(
        "where is my car",
        "where's my car",
        "wheres my car",
        "find my car",
        "where did i park"
    )

    private val WALK_TO_CAR_PHRASES = setOf(
        "walk to my car",
        "take me to my car",
        "navigate to my car",
        "go to my car"
    )

    private val STOP_NAV_PHRASES = setOf(
        "stop navigation",
        "cancel route",
        "end navigation",
        "stop route",
        "cancel navigation",
        "stop navigating",
        "end route"
    )

    private val UNMUTE_PHRASES = setOf(
        "unmute",
        "unmute voice",
        "unmute guidance",
        "turn voice on",
        "voice on"
    )

    private val MUTE_PHRASES = setOf(
        "mute",
        "mute voice",
        "mute guidance",
        "turn voice off",
        "voice off",
        "quiet"
    )

    private val ETA_PHRASES = setOf(
        "when will i arrive",
        "what's my eta",
        "whats my eta",
        "what is my eta",
        "eta",
        "arrival time",
        "how long until i arrive"
    )

    private val REPEAT_PHRASES = setOf(
        "repeat",
        "repeat instruction",
        "repeat last instruction",
        "say that again",
        "repeat that"
    )

    private val REPORT_CLOSED_PHRASES = setOf(
        "report road closed",
        "road closed",
        "report closure",
        "report road closure"
    )

    private val ZOOM_IN_PHRASES = setOf("zoom in", "zoom closer")
    private val ZOOM_OUT_PHRASES = setOf("zoom out", "zoom farther", "zoom further")

    private val NEAREST_PREFIXES = listOf("find the nearest ", "find nearest ", "nearest ")
    private val NAVIGATE_PREFIXES = listOf("navigate to ", "take me to ", "go to ", "drive to ", "directions to ")

    fun parse(rawTranscript: String): VoiceCommand {
        val trimmed = rawTranscript.trim()
        if (trimmed.isEmpty()) return VoiceCommand.Unknown("")

        val normalized = trimmed
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
            .trim(*EDGE_PUNCTUATION)

        if (normalized.isEmpty()) return VoiceCommand.Unknown(trimmed)

        return when {
            normalized in GO_HOME_PHRASES -> VoiceCommand.GoHome
            normalized in GO_WORK_PHRASES -> VoiceCommand.GoWork
            normalized in WHERE_IS_CAR_PHRASES -> VoiceCommand.WhereIsMyCar
            normalized in WALK_TO_CAR_PHRASES -> VoiceCommand.WalkToMyCar
            normalized in STOP_NAV_PHRASES -> VoiceCommand.StopNavigation
            normalized in UNMUTE_PHRASES -> VoiceCommand.Unmute
            normalized in MUTE_PHRASES -> VoiceCommand.Mute
            normalized in ETA_PHRASES -> VoiceCommand.AskEta
            normalized in REPEAT_PHRASES -> VoiceCommand.RepeatInstruction
            normalized in REPORT_CLOSED_PHRASES -> VoiceCommand.ReportRoadClosed
            normalized in ZOOM_IN_PHRASES -> VoiceCommand.ZoomIn
            normalized in ZOOM_OUT_PHRASES -> VoiceCommand.ZoomOut
            else -> parsePrefixedCommand(normalized, trimmed)
        }
    }

    private fun parsePrefixedCommand(normalized: String, rawTrimmed: String): VoiceCommand {
        for (prefix in NEAREST_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                val category = stripLeadingArticle(normalized.removePrefix(prefix).trim(*EDGE_PUNCTUATION))
                if (category.isNotEmpty()) return VoiceCommand.FindNearest(category)
            }
        }
        for (prefix in NAVIGATE_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                val query = normalized.removePrefix(prefix).trim(*EDGE_PUNCTUATION)
                if (query.isNotEmpty()) return VoiceCommand.NavigateTo(query)
            }
        }
        return VoiceCommand.Unknown(rawTrimmed)
    }

    private fun stripLeadingArticle(text: String): String = when {
        text.startsWith("the ") -> text.removePrefix("the ").trim()
        text.startsWith("a ") -> text.removePrefix("a ").trim()
        text.startsWith("an ") -> text.removePrefix("an ").trim()
        else -> text
    }
}
