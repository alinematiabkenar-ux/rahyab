package com.example.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import com.example.i18n.AppLanguage
import com.example.util.CrashLogManager
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Priority-aware bilingual Persian/English speech queue with route/maneuver generation guards
 * and actual playback-start telemetry.
 *
 * Key guarantees:
 * - Higher-priority utterances ([SpeechPriority.IMMINENT_MANEUVER], [SpeechPriority.REROUTE])
 *   immediately preempt lower-priority alerts and flush stale queued speech.
 * - Lower-priority alerts ([SpeechPriority.LOW_PRIORITY_ALERT]) never interrupt an active
 *   maneuver or lane guidance instruction.
 * - [stopAllSpeech] and [cancelStaleForContext] discard pending segments, pending post-speech
 *   actions, AND queued low-priority alerts so stale phrases never speak after rerouting or
 *   passing a turn.
 */
internal class VoiceSpeechQueue(
    private val appContext: Context,
    private val engineSelector: VoiceTtsEngineSelector,
    private val isVoiceEnabledProvider: () -> Boolean,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private data class QueuedPostAction(
        val context: SpeechContextTag,
        val isStillValid: () -> Boolean,
        val action: () -> Unit
    )

    private val speechGeneration = AtomicLong(0L)
    private val sequenceLock = Any()
    private val pendingSegments = ArrayDeque<VoiceGuidanceManager.SpeechSegment>()
    private val pendingLowPriorityAlerts = PendingLowPriorityAlertQueue()
    private val pendingPostSpeechActions = ArrayDeque<QueuedPostAction>()

    @Volatile
    private var activeSequenceGeneration: Long = 0L
    @Volatile
    private var activePriority: SpeechPriority = SpeechPriority.LOW_PRIORITY_ALERT
    @Volatile
    private var activeContext: SpeechContextTag = SpeechContextTag()
    @Volatile
    private var activeFullText: String = ""
    @Volatile
    private var activeLanguage: AppLanguage = AppLanguage.ENGLISH
    @Volatile
    private var activeQueuedAtMs: Long = 0L
    @Volatile
    private var activePlaybackStartReported: Boolean = false
    @Volatile
    private var isSequenceActive: Boolean = false

    @Volatile
    var playbackSink: SpeechPlaybackSink? = null

    fun isSpeakingOrQueued(): Boolean =
        isSequenceActive || engineSelector.isAnyEngineSpeaking() || hasPendingSegments()

    fun currentActivePriority(): SpeechPriority? =
        if (isSpeakingOrQueued()) activePriority else null

    fun enqueueAfterCurrentSpeech(
        context: SpeechContextTag = SpeechContextTag(),
        isStillValid: () -> Boolean = { true },
        action: () -> Unit
    ) {
        synchronized(sequenceLock) {
            if (pendingPostSpeechActions.size >= 4) pendingPostSpeechActions.removeFirst()
            pendingPostSpeechActions.addLast(QueuedPostAction(context, isStillValid, action))
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
        // Never queue a low-priority alert while an imminent maneuver or reroute is active.
        if (isSpeakingOrQueued() && activePriority.rank >= SpeechPriority.IMMINENT_MANEUVER.rank) {
            return
        }
        pendingLowPriorityAlerts.offer(
            text = text,
            effectiveLanguage = effectiveLanguage,
            targetLat = targetLat,
            targetLon = targetLon,
            nowMs = clockMsProvider()
        )
    }

    fun stopAllSpeech(clearPendingActions: Boolean = true) {
        speechGeneration.incrementAndGet()
        synchronized(sequenceLock) {
            pendingSegments.clear()
            if (clearPendingActions) {
                pendingPostSpeechActions.clear()
                pendingLowPriorityAlerts.clear()
            }
        }
        isSequenceActive = false
        activePriority = SpeechPriority.LOW_PRIORITY_ALERT
        activeContext = SpeechContextTag()
        engineSelector.stopEngines()
    }

    /**
     * Cancels any currently playing or queued speech / post-speech callbacks belonging to a
     * different route or an older/passed maneuver.
     */
    fun cancelStaleForContext(activeRouteId: String?, activeManeuverIndex: Int?) {
        var shouldStopActive = false
        synchronized(sequenceLock) {
            val iter = pendingPostSpeechActions.iterator()
            while (iter.hasNext()) {
                val item = iter.next()
                if (item.context.isStaleFor(activeRouteId, activeManeuverIndex) || !item.isStillValid()) {
                    iter.remove()
                }
            }
            if (isSequenceActive && activeContext.isStaleFor(activeRouteId, activeManeuverIndex)) {
                shouldStopActive = true
            }
            pendingLowPriorityAlerts.clear()
        }
        if (shouldStopActive) {
            stopAllSpeech(clearPendingActions = false)
        }
    }

    fun speakRaw(
        text: String,
        effectiveLanguage: AppLanguage,
        priority: SpeechPriority = SpeechPriority.PREPARATION_MANEUVER,
        context: SpeechContextTag = SpeechContextTag()
    ): Boolean {
        if (text.isBlank()) return false

        // Lower-priority alerts must NEVER interrupt an active higher-priority maneuver instruction.
        if (isSpeakingOrQueued() && priority.rank < activePriority.rank) {
            if (priority == SpeechPriority.LOW_PRIORITY_ALERT) {
                pendingLowPriorityAlerts.offer(
                    text = text,
                    effectiveLanguage = effectiveLanguage,
                    nowMs = clockMsProvider()
                )
            }
            return false
        }

        val queuedAt = clockMsProvider()
        runCatching {
            val clearPending = priority.rank >= SpeechPriority.IMMINENT_MANEUVER.rank
            stopAllSpeech(clearPendingActions = clearPending)
            val gen = speechGeneration.incrementAndGet()
            activeSequenceGeneration = gen
            activePriority = priority
            activeContext = context
            activeFullText = text
            activeLanguage = effectiveLanguage
            activeQueuedAtMs = queuedAt
            activePlaybackStartReported = false
            isSequenceActive = true

            val segments = if (effectiveLanguage == AppLanguage.PERSIAN) {
                splitPersianAndEnglishSegments(text)
            } else {
                listOf(VoiceGuidanceManager.SpeechSegment(text = text, isEnglish = true))
            }

            if (segments.isEmpty()) {
                isSequenceActive = false
                return false
            }
            synchronized(sequenceLock) {
                pendingSegments.clear()
                segments.forEach { pendingSegments.addLast(it) }
            }
            engineSelector.requestDuckingFocus()
            dispatchNextSegment(gen)
        }.onFailure { err ->
            CrashLogManager.logError(appContext, "TTS", "speakRaw failed, falling back to default engine", err)
            runCatching {
                engineSelector.tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rahyab_fb_${clockMsProvider()}")
                reportPlaybackStartedOnce()
            }
        }
        return true
    }

    fun handleUtteranceStarted() {
        reportPlaybackStartedOnce()
    }

    fun handleUtteranceFinished(utteranceId: String?) {
        val gen = utteranceId
            ?.substringAfter("gen_", missingDelimiterValue = "")
            ?.substringBefore("_")
            ?.toLongOrNull()
        if (gen == null || gen != activeSequenceGeneration) {
            val isStillPending = synchronized(sequenceLock) { pendingSegments.isNotEmpty() }
            if (!isStillPending) {
                isSequenceActive = false
                speakPendingLowPriorityAlertIfReady()
            }
            return
        }
        dispatchNextSegment(gen)
    }

    private fun reportPlaybackStartedOnce() {
        if (activePlaybackStartReported) return
        activePlaybackStartReported = true
        val sink = playbackSink ?: return
        sink.onSpeakStarted(
            SpeechPlaybackEvent(
                text = activeFullText,
                language = activeLanguage,
                priority = activePriority,
                context = activeContext,
                queuedAtMs = activeQueuedAtMs,
                playbackStartedAtMs = clockMsProvider()
            )
        )
    }

    private fun speakPendingLowPriorityAlertIfReady(): Boolean {
        val nextAlert = pendingLowPriorityAlerts.pollIfStillValid(clockMsProvider()) ?: return false
        if (!isVoiceEnabledProvider() || !engineSelector.isInitialized) return false
        return speakRaw(
            text = nextAlert.text,
            effectiveLanguage = nextAlert.effectiveLanguage,
            priority = SpeechPriority.LOW_PRIORITY_ALERT
        )
    }

    private fun dispatchNextSegment(gen: Long) {
        if (gen != activeSequenceGeneration) return
        val next = synchronized(sequenceLock) {
            if (gen != activeSequenceGeneration || pendingSegments.isEmpty()) null
            else pendingSegments.removeFirst()
        }
        if (next == null) {
            isSequenceActive = false
            activePriority = SpeechPriority.LOW_PRIORITY_ALERT
            val queuedAction = synchronized(sequenceLock) {
                var valid: QueuedPostAction? = null
                while (pendingPostSpeechActions.isNotEmpty() && valid == null) {
                    val candidate = pendingPostSpeechActions.removeFirst()
                    if (candidate.isStillValid()) valid = candidate
                }
                valid
            }
            if (queuedAction != null) {
                queuedAction.action.invoke()
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
            isSequenceActive = false
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

        if (spokenOk) {
            reportPlaybackStartedOnce()
        } else {
            val fallbackEngine = engineSelector.tts
            val fbOk = if (fallbackEngine != null && fallbackEngine !== engine) {
                runCatching {
                    fallbackEngine.language = if (next.isEnglish) Locale.US else Locale.forLanguageTag("fa-IR")
                    fallbackEngine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
            } else false
            if (fbOk) {
                reportPlaybackStartedOnce()
            } else {
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
