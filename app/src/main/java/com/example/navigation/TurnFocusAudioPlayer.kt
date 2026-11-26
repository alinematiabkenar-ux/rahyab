package com.example.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * F2: Plays the exact Turn Focus 3-beep cue (3 short beeps: 150 ms tone, 150 ms gap, ~750 ms total)
 * using [ToneGenerator.TONE_PROP_BEEP] with navigation guidance audio attributes and transient
 * audio ducking. If voice guidance is currently speaking, waits up to [MAX_VOICE_WAIT_MS] (4 s)
 * for speech to finish before playing the 3 beeps.
 */
class TurnFocusAudioPlayer(
    context: Context?
) {
    private val audioManager = context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeFocusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    fun playTurnFocusBeeps(
        isVoiceSpeaking: () -> Boolean,
        onAllBeepsFinished: () -> Unit
    ) {
        cancelPending()
        val deadlineMs = SystemClock.elapsedRealtime() + MAX_VOICE_WAIT_MS
        waitForSpeechThenPlay(isVoiceSpeaking, deadlineMs, onAllBeepsFinished)
    }

    private fun waitForSpeechThenPlay(
        isVoiceSpeaking: () -> Boolean,
        deadlineMs: Long,
        onAllBeepsFinished: () -> Unit
    ) {
        if (isVoiceSpeaking() && SystemClock.elapsedRealtime() < deadlineMs) {
            mainHandler.postDelayed({
                waitForSpeechThenPlay(isVoiceSpeaking, deadlineMs, onAllBeepsFinished)
            }, SPEECH_POLL_MS)
            return
        }
        startThreeBeepsNow(onAllBeepsFinished)
    }

    private fun startThreeBeepsNow(onAllBeepsFinished: () -> Unit) {
        requestDuckingFocus()
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME) }.getOrNull()
        if (tone == null) {
            abandonDuckingFocus()
            onAllBeepsFinished()
            return
        }
        val offsets = beepStartOffsetsMs()
        for (offsetMs in offsets) {
            if (offsetMs == 0L) {
                mainHandler.post {
                    runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
                }
            } else {
                mainHandler.postDelayed({
                    runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
                }, offsetMs)
            }
        }
        mainHandler.postDelayed({
            runCatching { tone.release() }
            abandonDuckingFocus()
            onAllBeepsFinished()
        }, TOTAL_DURATION_MS)
    }

    fun cancelPending() {
        mainHandler.removeCallbacksAndMessages(null)
        abandonDuckingFocus()
    }

    private fun requestDuckingFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                activeFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonDuckingFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        }
    }

    companion object {
        const val BEEP_COUNT = 3
        const val BEEP_DURATION_MS = 150
        const val BEEP_GAP_MS = 150
        const val TOTAL_DURATION_MS = 750L // 150 + 150 + 150 + 150 + 150
        const val POST_BEEP_ZOOM_DELAY_MS = 300L
        const val MAX_VOICE_WAIT_MS = 4_000L
        private const val SPEECH_POLL_MS = 100L
        private const val BEEP_VOLUME = 92

        /** Exact start timestamps (ms) for each of the 3 beeps: [0, 300, 600]. */
        fun beepStartOffsetsMs(): List<Long> =
            List(BEEP_COUNT) { i -> i.toLong() * (BEEP_DURATION_MS + BEEP_GAP_MS) }
    }
}
