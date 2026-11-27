package com.example.navigation

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * F2: Plays the exact Turn Focus 3-beep cue (3 short beeps: 150 ms tone, 150 ms gap, ~750 ms total)
 * using [ToneGenerator.TONE_PROP_BEEP] coordinated with shared navigation audio focus.
 *
 * If voice guidance is currently speaking, waits up to [MAX_VOICE_WAIT_MS] for active speech to
 * finish before playing the 3 beeps. If speech is STILL active when the deadline expires, skips
 * the tone generator so beeps never barge over or steal focus from a speaking turn instruction.
 */
class TurnFocusAudioPlayer(
    context: Context?,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onAcquireFocus: (() -> Unit)? = null,
    private val onReleaseFocus: (() -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private var activeToneGenerator: ToneGenerator? = null

    fun playTurnFocusBeeps(
        isVoiceSpeaking: () -> Boolean,
        isStillValid: () -> Boolean = { true },
        onAllBeepsFinished: () -> Unit
    ) {
        cancelPending()
        val gen = generation.incrementAndGet()
        if (!isStillValid()) return
        val deadlineMs = clockMs() + MAX_VOICE_WAIT_MS
        waitForSpeechThenPlay(gen, isVoiceSpeaking, isStillValid, deadlineMs, onAllBeepsFinished)
    }

    private fun waitForSpeechThenPlay(
        gen: Long,
        isVoiceSpeaking: () -> Boolean,
        isStillValid: () -> Boolean,
        deadlineMs: Long,
        onAllBeepsFinished: () -> Unit
    ) {
        if (generation.get() != gen || !isStillValid()) return
        if (isVoiceSpeaking()) {
            if (clockMs() < deadlineMs) {
                mainHandler.postDelayed({
                    waitForSpeechThenPlay(gen, isVoiceSpeaking, isStillValid, deadlineMs, onAllBeepsFinished)
                }, SPEECH_POLL_MS)
            } else if (generation.get() == gen && isStillValid()) {
                // Never play tones over an instruction that is still speaking; hand off cleanly
                // so any follow-up lane cue queues behind the active instruction instead of interrupting.
                onAllBeepsFinished()
            }
            return
        }
        startThreeBeepsNow(gen, isVoiceSpeaking, isStillValid, onAllBeepsFinished)
    }

    private fun startThreeBeepsNow(
        gen: Long,
        isVoiceSpeaking: () -> Boolean,
        isStillValid: () -> Boolean,
        onAllBeepsFinished: () -> Unit
    ) {
        if (generation.get() != gen || !isStillValid()) return
        onAcquireFocus?.invoke()
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME) }.getOrNull()
        if (tone == null) {
            onReleaseFocus?.invoke()
            if (generation.get() == gen && isStillValid()) {
                onAllBeepsFinished()
            }
            return
        }
        activeToneGenerator = tone
        val offsets = beepStartOffsetsMs()
        for (offsetMs in offsets) {
            val fireTone = Runnable {
                if (generation.get() == gen && isStillValid() && !isVoiceSpeaking()) {
                    runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
                }
            }
            if (offsetMs == 0L) mainHandler.post(fireTone) else mainHandler.postDelayed(fireTone, offsetMs)
        }
        mainHandler.postDelayed({
            if (activeToneGenerator === tone) activeToneGenerator = null
            runCatching { tone.release() }
            onReleaseFocus?.invoke()
            if (generation.get() == gen && isStillValid()) {
                onAllBeepsFinished()
            }
        }, TOTAL_DURATION_MS)
    }

    fun cancelPending() {
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        activeToneGenerator?.let { tone ->
            runCatching { tone.stopTone() }
            runCatching { tone.release() }
        }
        activeToneGenerator = null
        onReleaseFocus?.invoke()
    }

    companion object {
        const val BEEP_COUNT = 3
        const val BEEP_DURATION_MS = 150
        const val BEEP_GAP_MS = 150
        const val TOTAL_DURATION_MS = 750L // 150 + 150 + 150 + 150 + 150
        const val POST_BEEP_ZOOM_DELAY_MS = 300L
        const val MAX_VOICE_WAIT_MS = 7_500L
        private const val SPEECH_POLL_MS = 100L
        private const val BEEP_VOLUME = 92

        /** Exact start timestamps (ms) for each of the 3 beeps: [0, 300, 600]. */
        fun beepStartOffsetsMs(): List<Long> =
            List(BEEP_COUNT) { i -> i.toLong() * (BEEP_DURATION_MS + BEEP_GAP_MS) }
    }
}
