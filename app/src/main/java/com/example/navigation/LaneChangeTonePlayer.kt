package com.example.navigation

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.example.i18n.AppLanguage

/**
 * L2: Plays deterministic offline lane-change audio tones without blocking the main thread:
 * - Triple beep: 3 short tones (120 ms tone, 120 ms gap) before or after the voice cue.
 * - Single beep: 1 short tone (120 ms) at the last-chance distance.
 */
internal class LaneChangeTonePlayer {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun playTripleBeep(onCompleted: (() -> Unit)? = null) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 92) }.getOrNull()
        if (tone == null) {
            onCompleted?.invoke()
            return
        }
        val stepMs = BEEP_DURATION_MS + BEEP_GAP_MS // 240 ms
        mainHandler.post {
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
        }
        mainHandler.postDelayed({
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
        }, stepMs.toLong())
        mainHandler.postDelayed({
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
        }, (stepMs * 2).toLong())
        mainHandler.postDelayed({
            runCatching { tone.release() }
            onCompleted?.invoke()
        }, (stepMs * 2 + BEEP_DURATION_MS + 40).toLong())
    }

    fun playSingleBeep(onCompleted: (() -> Unit)? = null) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 92) }.getOrNull()
        if (tone == null) {
            onCompleted?.invoke()
            return
        }
        mainHandler.post {
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS) }
        }
        mainHandler.postDelayed({
            runCatching { tone.release() }
            onCompleted?.invoke()
        }, (BEEP_DURATION_MS + 50).toLong())
    }

    fun cancelPending() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val BEEP_DURATION_MS = 120
        const val BEEP_GAP_MS = 120

        fun formatCuePhrase(targetSide: LaneTargetSide, language: AppLanguage): String {
            return if (language == AppLanguage.PERSIAN) {
                when (targetSide) {
                    LaneTargetSide.RIGHT -> "حالا خط را عوض کنید، سمت راست بمانید."
                    LaneTargetSide.LEFT -> "حالا خط را عوض کنید، سمت چپ بمانید."
                    LaneTargetSide.MIDDLE -> "حالا خط را عوض کنید، خط وسط بمانید."
                }
            } else {
                when (targetSide) {
                    LaneTargetSide.RIGHT -> "Change lanes now, keep right."
                    LaneTargetSide.LEFT -> "Change lanes now, keep left."
                    LaneTargetSide.MIDDLE -> "Change lanes now, keep middle."
                }
            }
        }

        fun appendNowToPrompt(prompt: String, language: AppLanguage): String {
            val trimmed = prompt.trim()
            if (trimmed.isEmpty()) return trimmed
            val hasPeriod = trimmed.endsWith(".")
            val core = if (hasPeriod) trimmed.dropLast(1).trimEnd() else trimmed
            val suffix = if (language == AppLanguage.PERSIAN) " همین حالا" else " now"
            return if (core.endsWith(suffix.trim(), ignoreCase = true)) {
                trimmed
            } else {
                "$core$suffix."
            }
        }
    }
}
