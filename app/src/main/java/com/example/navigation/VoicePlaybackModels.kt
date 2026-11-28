package com.example.navigation

import com.example.i18n.AppLanguage

/**
 * Explicit speech priority levels so imminent turn instructions and reroutes always preempt
 * lower-priority alerts (speed bumps, traffic lights, weather, speed limit drops), and
 * lane cues or duplicate preparation prompts never interrupt an active maneuver instruction.
 */
enum class SpeechPriority(val rank: Int) {
    LOW_PRIORITY_ALERT(10),
    CRITICAL_ALERT(20),
    LANE_CUE(35),
    PREPARATION_MANEUVER(40),
    IMMINENT_MANEUVER(50),
    REROUTE(60)
}

/**
 * Lifecycle state of a single spoken navigation instruction from selection through
 * TTS engine completion, cancellation, or error.
 */
enum class SpeechLifecycleState(val isTerminal: Boolean) {
    SELECTED(false),
    QUEUED(false),
    STARTED(false),
    COMPLETED(true),
    STOPPED(true),
    FAILED(true),
    CANCELED(true)
}

/**
 * Identifies the route, maneuver, and announcement stage an utterance belongs to,
 * enabling deterministic IDs, deduplication, and stale-context cancellation.
 */
data class SpeechContextTag(
    val routeId: String? = null,
    val maneuverIndex: Int? = null,
    val stage: String = ""
) {
    fun isStaleFor(activeRouteId: String?, activeManeuverIndex: Int?): Boolean {
        if (routeId != null && activeRouteId != null && routeId != activeRouteId) return true
        if (stage == "start") {
            return activeManeuverIndex != null && activeManeuverIndex > 1
        }
        if (maneuverIndex != null && activeManeuverIndex != null && maneuverIndex != activeManeuverIndex) return true
        return false
    }
}

/**
 * Complete audit record for one navigation voice instruction, covering intended text,
 * route/maneuver/stage identity, segment counts, timestamps, final outcome, and cancel reason.
 */
data class SpeechTraceRecord(
    val instructionId: String,
    val routeId: String?,
    val maneuverIndex: Int?,
    val stage: String,
    val intendedText: String,
    val language: AppLanguage,
    val priority: SpeechPriority,
    val segmentCount: Int,
    val completedSegments: Int,
    val selectedAtMs: Long,
    val queuedAtMs: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val finalState: SpeechLifecycleState,
    val cancelReason: String? = null,
    val errorCode: Int? = null,
    val retryCount: Int = 0
)

/**
 * Optional observer invoked whenever an instruction enters a terminal or milestone lifecycle state.
 */
fun interface SpeechTraceSink {
    fun onTraceUpdated(record: SpeechTraceRecord)
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
    val playbackStartedAtMs: Long,
    val instructionId: String = "",
    val segmentCount: Int = 1
)

/**
 * Optional hook used by tests/diagnostics to observe actual TTS playback starts
 * without needing live Android hardware speech engines.
 */
fun interface SpeechPlaybackSink {
    fun onSpeakStarted(event: SpeechPlaybackEvent)
}

/**
 * Callback interface matching Android's [android.speech.tts.UtteranceProgressListener]
 * (`onStart`, `onDone`, `onStop`, `onError`), allowing deterministic unit tests to drive
 * real segment-by-segment TTS callbacks without mocking [VoiceSpeechQueue].
 */
interface TtsUtteranceCallbacks {
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onStop(utteranceId: String, interrupted: Boolean)
    fun onError(utteranceId: String, errorCode: Int)
}

/**
 * Optional test/diagnostic driver replacing Android's native [android.speech.tts.TextToSpeech]
 * transport while keeping the entire production queue, audio focus, segment splitting, and
 * lifecycle callback machinery intact.
 */
interface TtsEngineDriver {
    val isReady: Boolean
    val isSpeaking: Boolean
    fun speakSegment(
        text: String,
        isEnglish: Boolean,
        useFallbackEngine: Boolean,
        utteranceId: String,
        callbacks: TtsUtteranceCallbacks
    ): Boolean
    fun stopAll()
}
