package com.example.navigation

import com.example.i18n.AppLanguage

/**
 * Explicit speech priority levels so imminent turn instructions and reroutes always preempt
 * lower-priority alerts (speed bumps, traffic lights, weather, speed limit drops), and
 * low-priority alerts never interrupt an active maneuver or lane instruction.
 */
enum class SpeechPriority(val rank: Int) {
    LOW_PRIORITY_ALERT(10),
    CRITICAL_ALERT(20),
    LANE_CUE(40),
    PREPARATION_MANEUVER(40),
    IMMINENT_MANEUVER(50),
    REROUTE(60)
}

/**
 * Identifies the route and maneuver an utterance or delayed post-beep phrase belongs to,
 * enabling immediate cancellation when the route or maneuver changes before playback starts.
 */
data class SpeechContextTag(
    val routeId: String? = null,
    val maneuverIndex: Int? = null,
    val stage: String = ""
) {
    fun isStaleFor(activeRouteId: String?, activeManeuverIndex: Int?): Boolean {
        if (routeId != null && activeRouteId != null && routeId != activeRouteId) return true
        if (maneuverIndex != null && activeManeuverIndex != null && maneuverIndex != activeManeuverIndex) return true
        return false
    }
}

/**
 * Recorded when an utterance actually begins playback (not merely when it was queued),
 * supporting diagnostics and deterministic timing verification.
 */
data class SpeechPlaybackEvent(
    val text: String,
    val language: AppLanguage,
    val priority: SpeechPriority,
    val context: SpeechContextTag,
    val queuedAtMs: Long,
    val playbackStartedAtMs: Long
)

/**
 * Optional hook used by tests/diagnostics to observe or intercept actual TTS playback starts
 * without needing live Android hardware speech engines.
 */
fun interface SpeechPlaybackSink {
    fun onSpeakStarted(event: SpeechPlaybackEvent)
}
