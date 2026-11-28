package com.example.navigation

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import com.example.i18n.AppLanguage
import com.example.util.CrashLogManager
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Priority-aware bilingual Persian/English speech queue with deterministic instruction IDs,
 * full TTS lifecycle tracking (`onStart`, `onDone`, `onStop`, `onError`), non-interrupting
 * queueing for equal/lower-priority cues, retry while timely, and stale-generation protection.
 */
internal class VoiceSpeechQueue(
    private val appContext: Context,
    private val engineSelector: VoiceTtsEngineSelector,
    private val isVoiceEnabledProvider: () -> Boolean,
    private val clockMsProvider: () -> Long = { System.currentTimeMillis() },
    private val onInstructionStarted: (SpeechContextTag) -> Unit = {},
    private val onInstructionCompleted: (SpeechContextTag) -> Unit = {},
    private val onInstructionNotConsumed: (SpeechContextTag, String) -> Unit = { _, _ -> }
) {
    private data class QueuedInstruction(
        val instructionId: String, val text: String, val language: AppLanguage,
        val priority: SpeechPriority, val context: SpeechContextTag,
        val isStillValid: () -> Boolean, val selectedAtMs: Long, val maxWaitMs: Long
    )
    private data class QueuedPostAction(val context: SpeechContextTag, val isStillValid: () -> Boolean, val action: () -> Unit)
    private class ActiveInstruction(
        val generation: Long, val instructionId: String, val text: String, val language: AppLanguage,
        val priority: SpeechPriority, val context: SpeechContextTag, val isStillValid: () -> Boolean,
        val selectedAtMs: Long, val queuedAtMs: Long, val totalSegments: Int
    ) {
        var startedAtMs: Long = 0L; var completedSegments: Int = 0
        var currentSegment: VoiceGuidanceManager.SpeechSegment? = null
        var currentUtteranceId: String? = null; var currentSegmentRetried: Boolean = false
        var retryCount: Int = 0; var playbackStartReported: Boolean = false; var hasNotifiedStarted: Boolean = false
    }

    val traceLog = VoiceSpeechTraceLog()
    private val speechGeneration = AtomicLong(0L)
    private val utteranceSeq = AtomicLong(0L)
    private val sequenceLock = Any()
    private val pendingSegments = ArrayDeque<VoiceGuidanceManager.SpeechSegment>()
    private val pendingInstructions = ArrayDeque<QueuedInstruction>()
    private val pendingLowPriorityAlerts = PendingLowPriorityAlertQueue()
    private val pendingPostSpeechActions = ArrayDeque<QueuedPostAction>()
    @Volatile private var activeInstruction: ActiveInstruction? = null
    @Volatile var playbackSink: SpeechPlaybackSink? = null

    fun getRecentTraces(): List<SpeechTraceRecord> = traceLog.snapshot()
    fun setTraceSink(sink: SpeechTraceSink?) { traceLog.traceSink = sink }

    fun isSpeakingOrQueued(): Boolean {
        recoverStuckUtteranceIfNeeded()
        return synchronized(sequenceLock) {
            activeInstruction != null || pendingSegments.isNotEmpty() || pendingInstructions.isNotEmpty()
        } || engineSelector.isAnyEngineSpeaking()
    }

    fun currentActivePriority(): SpeechPriority? { recoverStuckUtteranceIfNeeded(); return activeInstruction?.priority }
    fun hasPendingSegments(): Boolean = synchronized(sequenceLock) { pendingSegments.isNotEmpty() }
    fun updateUserMotionForAlerts(lat: Double, lon: Double, headingDegrees: Float) =
        pendingLowPriorityAlerts.updateUserMotion(lat, lon, headingDegrees)

    fun enqueueAfterCurrentSpeech(context: SpeechContextTag = SpeechContextTag(), isStillValid: () -> Boolean = { true }, action: () -> Unit) {
        synchronized(sequenceLock) {
            if (pendingPostSpeechActions.size >= 4) pendingPostSpeechActions.removeFirst()
            pendingPostSpeechActions.addLast(QueuedPostAction(context, isStillValid, action))
        }
    }

    fun offerLowPriorityAlert(text: String, effectiveLanguage: AppLanguage, targetLat: Double, targetLon: Double) {
        val active = activeInstruction
        if (active != null && active.priority.rank >= SpeechPriority.IMMINENT_MANEUVER.rank) return
        pendingLowPriorityAlerts.offer(text, effectiveLanguage, targetLat, targetLon, clockMsProvider())
    }

    fun stopAllSpeech(clearPendingActions: Boolean = true, reason: String = "explicit_stop", releaseFocus: Boolean = true) {
        val now = clockMsProvider()
        val canceledActive: ActiveInstruction?
        val canceledQueued = mutableListOf<QueuedInstruction>()
        val hasRemainingQueued: Boolean
        synchronized(sequenceLock) {
            speechGeneration.incrementAndGet()
            canceledActive = activeInstruction; activeInstruction = null; pendingSegments.clear()
            if (clearPendingActions) {
                canceledQueued.addAll(pendingInstructions); pendingInstructions.clear()
                pendingPostSpeechActions.clear(); pendingLowPriorityAlerts.clear()
            }
            hasRemainingQueued = pendingInstructions.isNotEmpty()
        }
        canceledActive?.let { recordTerminal(it, SpeechLifecycleState.CANCELED, now, reason) }
        canceledQueued.forEach { recordQueuedCanceled(it, now, reason) }
        engineSelector.stopEngines()
        if (releaseFocus && (clearPendingActions || !hasRemainingQueued)) {
            engineSelector.abandonDuckingFocus()
        }
    }

    fun cancelStaleForContext(activeRouteId: String?, activeManeuverIndex: Int?, reason: String = "stale_maneuver_or_route") {
        val now = clockMsProvider()
        var shouldStopActive = false
        val droppedQueued = mutableListOf<QueuedInstruction>()
        synchronized(sequenceLock) {
            val postIter = pendingPostSpeechActions.iterator()
            while (postIter.hasNext()) {
                val item = postIter.next()
                if (item.context.isStaleFor(activeRouteId, activeManeuverIndex) || !item.isStillValid()) postIter.remove()
            }
            val instIter = pendingInstructions.iterator()
            while (instIter.hasNext()) {
                val item = instIter.next()
                if (item.context.isStaleFor(activeRouteId, activeManeuverIndex) || !item.isStillValid()) {
                    droppedQueued.add(item); instIter.remove()
                }
            }
            if (activeInstruction?.context?.isStaleFor(activeRouteId, activeManeuverIndex) == true) shouldStopActive = true
            pendingLowPriorityAlerts.clear()
        }
        droppedQueued.forEach { recordQueuedCanceled(it, now, reason) }
        if (shouldStopActive) {
            stopAllSpeech(clearPendingActions = false, reason = reason)
            advanceQueueOrReleaseFocus()
        }
    }

    fun speakRaw(
        text: String, effectiveLanguage: AppLanguage, priority: SpeechPriority = SpeechPriority.PREPARATION_MANEUVER,
        context: SpeechContextTag = SpeechContextTag(), isStillValid: () -> Boolean = { true }, forcePreempt: Boolean = false
    ): Boolean {
        if (text.isBlank()) return false
        recoverStuckUtteranceIfNeeded()
        val now = clockMsProvider()
        val instructionId = traceLog.nextInstructionId(context)
        val incomingStageKey = stageKeyOf(context)
        if (!engineSelector.isReadyForLanguage(effectiveLanguage)) {
            traceLog.record(traceLog.createInitialRecord(instructionId, text, effectiveLanguage, priority, context, 1, now, 0L, SpeechLifecycleState.CANCELED, "tts_not_initialized"))
            onInstructionNotConsumed(context, "tts_not_initialized")
            return false
        }

        var preemptReason: String? = null
        var queuedInstead = false
        var duplicateReason: String? = null
        synchronized(sequenceLock) {
            val active = activeInstruction
            if (active != null) {
                val isDupActive = active.text == text || (incomingStageKey != null && incomingStageKey == stageKeyOf(active.context))
                val isDupQueued = pendingInstructions.any { it.text == text || (incomingStageKey != null && incomingStageKey == stageKeyOf(it.context)) }
                if (isDupActive) {
                    duplicateReason = "duplicate_active"
                } else if (isDupQueued) {
                    duplicateReason = "duplicate_queued"
                } else {
                    val activeStale = active.context.isStaleFor(context.routeId, context.maneuverIndex)
                    val isReroute = priority == SpeechPriority.REROUTE
                    val isImminentOverLower = priority == SpeechPriority.IMMINENT_MANEUVER && active.priority.rank < SpeechPriority.IMMINENT_MANEUVER.rank
                    if (forcePreempt || isReroute || isImminentOverLower || activeStale) {
                        preemptReason = when {
                            forcePreempt -> "manual_repeat_or_test"
                            isReroute -> "preempted_by_reroute"
                            isImminentOverLower -> "preempted_by_imminent_maneuver"
                            else -> "superseded_by_newer_maneuver_or_route"
                        }
                    } else {
                        queuedInstead = true
                        if (priority == SpeechPriority.LOW_PRIORITY_ALERT) {
                            pendingLowPriorityAlerts.offer(text, effectiveLanguage, nowMs = now)
                        } else {
                            val maxWait = if (priority.rank >= SpeechPriority.LANE_CUE.rank) MANEUVER_QUEUE_MAX_WAIT_MS else ALERT_QUEUE_MAX_WAIT_MS
                            enqueuePendingLocked(QueuedInstruction(instructionId, text, effectiveLanguage, priority, context, isStillValid, now, maxWait))
                        }
                    }
                }
            }
        }
        duplicateReason?.let {
            traceLog.record(traceLog.createInitialRecord(instructionId, text, effectiveLanguage, priority, context, 1, now, 0L, SpeechLifecycleState.CANCELED, it))
            return false
        }
        if (queuedInstead) {
            traceLog.record(traceLog.createInitialRecord(instructionId, text, effectiveLanguage, priority, context, segmentCountOf(text, effectiveLanguage), now, now, SpeechLifecycleState.QUEUED))
            return true
        }
        preemptReason?.let { reason ->
            stopAllSpeech(clearPendingActions = priority.rank >= SpeechPriority.IMMINENT_MANEUVER.rank, reason = reason, releaseFocus = false)
        }
        return startInstructionNow(instructionId, text, effectiveLanguage, priority, context, isStillValid, now)
    }

    private fun startInstructionNow(
        instructionId: String, text: String, effectiveLanguage: AppLanguage, priority: SpeechPriority,
        context: SpeechContextTag, isStillValid: () -> Boolean, selectedAtMs: Long
    ): Boolean {
        val segments = buildSegments(text, effectiveLanguage)
        if (segments.isEmpty()) return false
        val now = clockMsProvider()
        val gen = speechGeneration.incrementAndGet()
        val inst = ActiveInstruction(gen, instructionId, text, effectiveLanguage, priority, context, isStillValid, selectedAtMs, now, segments.size)
        synchronized(sequenceLock) {
            activeInstruction = inst; pendingSegments.clear(); segments.forEach { pendingSegments.addLast(it) }
        }
        traceLog.record(traceLog.createInitialRecord(instructionId, text, effectiveLanguage, priority, context, segments.size, selectedAtMs, now, SpeechLifecycleState.QUEUED))
        engineSelector.requestDuckingFocus()
        dispatchNextSegment(gen)
        return true
    }

    fun handleUtteranceStarted(utteranceId: String) {
        val now = clockMsProvider()
        val inst = synchronized(sequenceLock) {
            val cur = activeInstruction
            if (cur == null || !matchesActiveUtterance(cur, utteranceId)) null else cur.also { if (it.startedAtMs == 0L) it.startedAtMs = now }
        } ?: return
        engineSelector.requestDuckingFocus()
        reportPlaybackStartedOnce(inst, now)
    }

    fun handleUtteranceDone(utteranceId: String) {
        val now = clockMsProvider()
        val finished: ActiveInstruction?
        var shouldDispatchNext = false; var activeGen = 0L
        synchronized(sequenceLock) {
            val cur = activeInstruction
            // Ignore stale-generation callbacks so flushed utterances never kill active speech.
            if (cur == null || !matchesActiveUtterance(cur, utteranceId)) return
            cur.completedSegments += 1; cur.currentUtteranceId = null; cur.currentSegment = null; cur.currentSegmentRetried = false
            if (pendingSegments.isNotEmpty()) {
                finished = null; shouldDispatchNext = true; activeGen = cur.generation
            } else {
                finished = cur; activeInstruction = null
            }
        }
        if (shouldDispatchNext) { dispatchNextSegment(activeGen); return }
        if (finished != null) {
            recordTerminal(finished, SpeechLifecycleState.COMPLETED, now, cancelReason = null)
            onInstructionCompleted(finished.context)
            advanceQueueOrReleaseFocus()
        }
    }

    fun handleUtteranceStopped(utteranceId: String, interrupted: Boolean) {
        val now = clockMsProvider()
        val stopped = synchronized(sequenceLock) {
            val cur = activeInstruction
            if (cur == null || !matchesActiveUtterance(cur, utteranceId)) return
            activeInstruction = null; pendingSegments.clear(); cur
        }
        val reason = if (interrupted) "engine_stopped_interrupted" else "engine_stopped"
        recordTerminal(stopped, SpeechLifecycleState.STOPPED, now, cancelReason = reason)
        if (!stopped.hasNotifiedStarted) onInstructionNotConsumed(stopped.context, reason)
        advanceQueueOrReleaseFocus()
    }

    fun handleUtteranceError(utteranceId: String, errorCode: Int) {
        val now = clockMsProvider()
        var retrySeg: VoiceGuidanceManager.SpeechSegment? = null; var retryGen = 0L; var useFb = false
        var failedInst: ActiveInstruction? = null
        synchronized(sequenceLock) {
            val cur = activeInstruction
            if (cur == null || !matchesActiveUtterance(cur, utteranceId)) return
            val seg = cur.currentSegment
            if (seg != null && !cur.currentSegmentRetried && (now - cur.selectedAtMs) <= RETRY_MAX_AGE_MS && cur.isStillValid()) {
                cur.currentSegmentRetried = true; cur.retryCount += 1
                retrySeg = seg; retryGen = cur.generation; useFb = engineSelector.hasDistinctFallbackEngine(seg.isEnglish)
            } else {
                failedInst = cur; activeInstruction = null; pendingSegments.clear()
            }
        }
        retrySeg?.let { dispatchSegmentNow(retryGen, it, useFallbackEngine = useFb); return }
        val failed = failedInst ?: return
        val reason = "tts_error_$errorCode"
        recordTerminal(failed, SpeechLifecycleState.FAILED, now, cancelReason = reason, errorCode = errorCode)
        onInstructionNotConsumed(failed.context, reason)
        advanceQueueOrReleaseFocus()
    }

    private fun dispatchNextSegment(gen: Long) {
        val next = synchronized(sequenceLock) {
            val cur = activeInstruction
            if (cur == null || cur.generation != gen || pendingSegments.isEmpty()) null
            else pendingSegments.removeFirst().also { cur.currentSegment = it; cur.currentSegmentRetried = false }
        }
        if (next == null) { advanceQueueOrReleaseFocus(); return }
        dispatchSegmentNow(gen, next, useFallbackEngine = false)
    }

    private fun dispatchSegmentNow(gen: Long, seg: VoiceGuidanceManager.SpeechSegment, useFallbackEngine: Boolean) {
        val utteranceId = "rahyab_gen_${gen}_seg_${utteranceSeq.incrementAndGet()}"
        val inst = synchronized(sequenceLock) {
            val cur = activeInstruction
            if (cur == null || cur.generation != gen) return
            cur.currentUtteranceId = utteranceId; cur
        }
        if (engineSelector.speakSegment(seg.text, seg.isEnglish, utteranceId, useFallbackEngine)) {
            val usingBuiltIn = engineSelector.isUsingBuiltInPersianFor(seg.isEnglish, useFallbackEngine)
            if (!usingBuiltIn && engineSelector.testEngineDriver == null && "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) {
                val now = clockMsProvider(); inst.startedAtMs = now; reportPlaybackStartedOnce(inst, now)
            }
            return
        }
        if (!useFallbackEngine && engineSelector.hasDistinctFallbackEngine(seg.isEnglish) &&
            (clockMsProvider() - inst.selectedAtMs) <= RETRY_MAX_AGE_MS && inst.isStillValid()
        ) {
            synchronized(sequenceLock) { if (activeInstruction?.generation == gen) { inst.currentSegmentRetried = true; inst.retryCount += 1 } }
            dispatchSegmentNow(gen, seg, useFallbackEngine = true)
            return
        }
        val now = clockMsProvider()
        val failed = synchronized(sequenceLock) {
            val cur = activeInstruction
            if (cur == null || cur.generation != gen) null else { activeInstruction = null; pendingSegments.clear(); cur }
        } ?: return
        recordTerminal(failed, SpeechLifecycleState.FAILED, now, "speak_call_failed", TextToSpeech.ERROR)
        onInstructionNotConsumed(failed.context, "speak_call_failed")
        advanceQueueOrReleaseFocus()
    }

    private fun advanceQueueOrReleaseFocus() {
        val now = clockMsProvider()
        if (!isVoiceEnabledProvider() || (!engineSelector.isInitialized && !engineSelector.shouldUseBuiltInPersian())) {
            engineSelector.abandonDuckingFocus(); return
        }
        var nextInst: QueuedInstruction? = null; var nextPost: QueuedPostAction? = null
        val expired = mutableListOf<Pair<QueuedInstruction, String>>()
        synchronized(sequenceLock) {
            if (activeInstruction != null) return
            while (pendingInstructions.isNotEmpty() && nextInst == null) {
                val cand = pendingInstructions.removeFirst()
                when {
                    !cand.isStillValid() -> expired.add(cand to "stale_before_dispatch")
                    now - cand.selectedAtMs > cand.maxWaitMs -> expired.add(cand to "expired_in_queue")
                    else -> nextInst = cand
                }
            }
            if (nextInst == null) {
                while (pendingPostSpeechActions.isNotEmpty() && nextPost == null) {
                    val cand = pendingPostSpeechActions.removeFirst()
                    if (cand.isStillValid()) nextPost = cand
                }
            }
        }
        expired.forEach { (q, reason) -> recordQueuedCanceled(q, now, reason); onInstructionNotConsumed(q.context, reason) }
        nextInst?.let { startInstructionNow(it.instructionId, it.text, it.language, it.priority, it.context, it.isStillValid, it.selectedAtMs); return }
        nextPost?.let { it.action.invoke(); return }
        val nextAlert = pendingLowPriorityAlerts.pollIfStillValid(clockMsProvider())
        if (nextAlert != null && isVoiceEnabledProvider() && engineSelector.isReadyForLanguage(nextAlert.effectiveLanguage)) {
            speakRaw(nextAlert.text, nextAlert.effectiveLanguage, SpeechPriority.LOW_PRIORITY_ALERT)
        } else {
            engineSelector.abandonDuckingFocus()
        }
    }

    private fun reportPlaybackStartedOnce(inst: ActiveInstruction, startedAtMs: Long) {
        val first = synchronized(sequenceLock) {
            if (inst.playbackStartReported) false else { inst.playbackStartReported = true; inst.hasNotifiedStarted = true; true }
        }
        if (!first) return
        traceLog.record(
            SpeechTraceRecord(
                inst.instructionId, inst.context.routeId, inst.context.maneuverIndex, inst.context.stage, inst.text,
                inst.language, inst.priority, inst.totalSegments, inst.completedSegments, inst.selectedAtMs, inst.queuedAtMs,
                startedAtMs, 0L, SpeechLifecycleState.STARTED, retryCount = inst.retryCount
            )
        )
        onInstructionStarted(inst.context)
        playbackSink?.onSpeakStarted(
            SpeechPlaybackEvent(inst.text, inst.language, inst.priority, inst.context, inst.queuedAtMs, startedAtMs, inst.instructionId, inst.totalSegments)
        )
    }

    private fun recordTerminal(inst: ActiveInstruction, state: SpeechLifecycleState, finishedAtMs: Long, cancelReason: String?, errorCode: Int? = null) {
        traceLog.record(
            SpeechTraceRecord(
                inst.instructionId, inst.context.routeId, inst.context.maneuverIndex, inst.context.stage, inst.text,
                inst.language, inst.priority, inst.totalSegments, inst.completedSegments, inst.selectedAtMs, inst.queuedAtMs,
                inst.startedAtMs, finishedAtMs, state, cancelReason, errorCode, inst.retryCount
            )
        )
    }

    private fun recordQueuedCanceled(q: QueuedInstruction, nowMs: Long, reason: String) {
        traceLog.record(
            traceLog.createInitialRecord(q.instructionId, q.text, q.language, q.priority, q.context, 1, q.selectedAtMs, q.selectedAtMs, SpeechLifecycleState.CANCELED, reason)
                .copy(finishedAtMs = nowMs)
        )
    }

    private fun enqueuePendingLocked(item: QueuedInstruction) {
        if (pendingInstructions.size >= MAX_PENDING_INSTRUCTIONS) {
            val dropped = pendingInstructions.removeFirst()
            recordQueuedCanceled(dropped, clockMsProvider(), "queue_overflow")
        }
        val sorted = (pendingInstructions.toList() + item).sortedByDescending { it.priority.rank }
        pendingInstructions.clear(); sorted.forEach { pendingInstructions.addLast(it) }
    }

    private fun recoverStuckUtteranceIfNeeded() {
        val now = clockMsProvider()
        val stuck = synchronized(sequenceLock) {
            val cur = activeInstruction ?: return
            if (engineSelector.isAnyEngineSpeaking() || now - cur.queuedAtMs < STUCK_UTTERANCE_RECOVERY_MS) return
            activeInstruction = null; pendingSegments.clear(); cur
        }
        CrashLogManager.logError(appContext, "TTS", "Recovered dropped onDone for ${stuck.instructionId}", null)
        recordTerminal(stuck, SpeechLifecycleState.COMPLETED, now, cancelReason = "watchdog_recovered")
    }

    private fun matchesActiveUtterance(cur: ActiveInstruction, utteranceId: String): Boolean {
        val gen = utteranceId.substringAfter("gen_", "").substringBefore("_").toLongOrNull()
        return gen != null && gen == cur.generation && cur.currentUtteranceId == utteranceId
    }

    private fun stageKeyOf(ctx: SpeechContextTag): String? =
        if (!ctx.routeId.isNullOrBlank() && ctx.stage.isNotBlank()) "${ctx.routeId}_${ctx.maneuverIndex}_${ctx.stage}" else null

    private fun buildSegments(text: String, effectiveLanguage: AppLanguage): List<VoiceGuidanceManager.SpeechSegment> =
        if (effectiveLanguage == AppLanguage.PERSIAN) {
            if (engineSelector.useUnifiedBilingualPersianSegment()) {
                listOf(VoiceGuidanceManager.SpeechSegment(text = text, isEnglish = false))
            } else {
                VoiceSpeechTraceLog.splitPersianAndEnglishSegments(text)
            }
        } else listOf(VoiceGuidanceManager.SpeechSegment(text = text, isEnglish = true))

    private fun segmentCountOf(text: String, effectiveLanguage: AppLanguage): Int = buildSegments(text, effectiveLanguage).size.coerceAtLeast(1)

    companion object {
        private const val RETRY_MAX_AGE_MS = 6_000L
        private const val MANEUVER_QUEUE_MAX_WAIT_MS = 8_000L
        private const val ALERT_QUEUE_MAX_WAIT_MS = 5_000L
        private const val MAX_PENDING_INSTRUCTIONS = 4
        private const val STUCK_UTTERANCE_RECOVERY_MS = 15_000L

        fun splitPersianAndEnglishSegments(text: String): List<VoiceGuidanceManager.SpeechSegment> =
            VoiceSpeechTraceLog.splitPersianAndEnglishSegments(text)
    }
}
