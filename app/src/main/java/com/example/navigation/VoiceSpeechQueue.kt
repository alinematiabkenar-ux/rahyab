package com.example.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import com.example.i18n.AppLanguage
import com.example.util.CrashLogManager
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure mechanical extraction from [VoiceGuidanceManager] (R1):
 * sequences bilingual Persian/English speech segments and pending low-priority alerts.
 */
internal class VoiceSpeechQueue(
    private val appContext: Context,
    private val engineSelector: VoiceTtsEngineSelector,
    private val isVoiceEnabledProvider: () -> Boolean
) {
    private val speechGeneration = AtomicLong(0L)
    private val sequenceLock = Any()
    private val pendingSegments = ArrayDeque<VoiceGuidanceManager.SpeechSegment>()
    private val pendingLowPriorityAlerts = PendingLowPriorityAlertQueue()
    private val pendingPostSpeechActions = ArrayDeque<() -> Unit>()
    @Volatile
    private var activeSequenceGeneration: Long = 0L

    fun enqueueAfterCurrentSpeech(action: () -> Unit) {
        synchronized(sequenceLock) {
            if (pendingPostSpeechActions.size >= 4) pendingPostSpeechActions.removeFirst()
            pendingPostSpeechActions.addLast(action)
        }
    }

    fun updateUserMotionForAlerts(lat: Double, lon: Double, headingDegrees: Float) {
        pendingLowPriorityAlerts.updateUserMotion(lat, lon, headingDegrees)
    }

    fun hasPendingSegments(): Boolean = synchronized(sequenceLock) { pendingSegments.isNotEmpty() }

    fun offerLowPriorityAlert(
        text: String,
        effectiveLanguage: AppLanguage,
        targetLat: Double,
        targetLon: Double
    ) {
        pendingLowPriorityAlerts.offer(
            text = text,
            effectiveLanguage = effectiveLanguage,
            targetLat = targetLat,
            targetLon = targetLon
        )
    }

    fun stopAllSpeech() {
        speechGeneration.incrementAndGet()
        synchronized(sequenceLock) {
            pendingSegments.clear()
        }
        engineSelector.stopEngines()
    }

    fun speakRaw(text: String, effectiveLanguage: AppLanguage) {
        if (text.isBlank()) return
        runCatching {
            stopAllSpeech()
            val gen = speechGeneration.incrementAndGet()
            activeSequenceGeneration = gen

            val segments = if (effectiveLanguage == AppLanguage.PERSIAN) {
                splitPersianAndEnglishSegments(text)
            } else {
                listOf(VoiceGuidanceManager.SpeechSegment(text = text, isEnglish = true))
            }

            if (segments.isEmpty()) return
            synchronized(sequenceLock) {
                pendingSegments.clear()
                segments.forEach { pendingSegments.addLast(it) }
            }
            engineSelector.requestDuckingFocus()
            dispatchNextSegment(gen)
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "speakRaw failed, falling back to default engine", err)
            runCatching {
                engineSelector.tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rahyab_fb_${System.currentTimeMillis()}")
            }
        }
    }

    fun handleUtteranceFinished(utteranceId: String?) {
        val gen = utteranceId
            ?.substringAfter("gen_", missingDelimiterValue = "")
            ?.substringBefore("_")
            ?.toLongOrNull()
        if (gen == null || gen != activeSequenceGeneration) {
            val isStillPending = synchronized(sequenceLock) { pendingSegments.isNotEmpty() }
            if (!isStillPending) {
                speakPendingLowPriorityAlertIfReady()
            }
            return
        }
        dispatchNextSegment(gen)
    }

    private fun speakPendingLowPriorityAlertIfReady(): Boolean {
        val nextAlert = pendingLowPriorityAlerts.pollIfStillValid() ?: return false
        if (!isVoiceEnabledProvider() || !engineSelector.isInitialized) return false
        speakRaw(nextAlert.text, nextAlert.effectiveLanguage)
        return true
    }

    private fun dispatchNextSegment(gen: Long) {
        if (gen != activeSequenceGeneration) return
        val next = synchronized(sequenceLock) {
            if (gen != activeSequenceGeneration || pendingSegments.isEmpty()) null
            else pendingSegments.removeFirst()
        }
        if (next == null) {
            val queuedAction = synchronized(sequenceLock) {
                if (pendingPostSpeechActions.isNotEmpty()) pendingPostSpeechActions.removeFirst() else null
            }
            if (queuedAction != null) {
                queuedAction.invoke()
                return
            }
            if (!speakPendingLowPriorityAlertIfReady()) {
                engineSelector.abandonDuckingFocus()
            }
            return
        }

        val engine = if (next.isEnglish) {
            engineSelector.activeEnglishEngine() ?: engineSelector.tts
        } else {
            engineSelector.activePersianEngine() ?: engineSelector.tts
        }
        if (engine == null) {
            engineSelector.abandonDuckingFocus()
            return
        }

        val utteranceId = "rahyab_gen_${gen}_${System.nanoTime()}"
        val spokenOk = runCatching {
            if (next.isEnglish) {
                engine.language = Locale.US
            } else {
                engineSelector.selectPersianLocale(engine)
            }
            val res = engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            res == TextToSpeech.SUCCESS
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "Segment speak failed (${next.isEnglish})", err)
        }.getOrDefault(false)

        if (!spokenOk) {
            val fallbackEngine = engineSelector.tts
            val fbOk = if (fallbackEngine != null && fallbackEngine !== engine) {
                runCatching {
                    fallbackEngine.language = if (next.isEnglish) Locale.US else Locale.forLanguageTag("fa-IR")
                    fallbackEngine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
            } else false
            if (!fbOk) {
                dispatchNextSegment(gen)
            }
        }
    }

    companion object {
        private val ENGLISH_TOKEN_REGEX = Regex(
            """(?:[A-Za-z][A-Za-z0-9'\-./]*|\d+[A-Za-z]+(?:-[A-Za-z0-9]+)*)(?:\s+(?:[A-Za-z0-9][A-Za-z0-9'\-./]*|&))*"""
        )

        fun splitPersianAndEnglishSegments(text: String): List<VoiceGuidanceManager.SpeechSegment> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()

            val segments = mutableListOf<VoiceGuidanceManager.SpeechSegment>()
            var cursor = 0
            for (match in ENGLISH_TOKEN_REGEX.findAll(trimmed)) {
                val start = match.range.first
                val end = match.range.last + 1
                if (start > cursor) {
                    val faPart = trimmed.substring(cursor, start).trim()
                    if (faPart.isNotEmpty() && faPart.any { it.isLetterOrDigit() }) {
                        segments.add(VoiceGuidanceManager.SpeechSegment(faPart, isEnglish = false))
                    }
                }
                val enPart = match.value.trim().trimEnd('.', ',', '،', ';', ':')
                if (enPart.isNotEmpty()) {
                    segments.add(VoiceGuidanceManager.SpeechSegment(enPart, isEnglish = true))
                }
                cursor = end
            }
            if (cursor < trimmed.length) {
                val tail = trimmed.substring(cursor).trim()
                if (tail.isNotEmpty() && tail.any { it.isLetterOrDigit() }) {
                    segments.add(VoiceGuidanceManager.SpeechSegment(tail, isEnglish = false))
                }
            }
            return if (segments.isEmpty()) {
                listOf(VoiceGuidanceManager.SpeechSegment(trimmed, isEnglish = false))
            } else {
                segments
            }
        }
    }
}
