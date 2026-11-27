package com.example.navigation

import android.util.Log
import com.example.i18n.AppLanguage
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe debug trace log and bilingual Persian/English segment splitter for spoken
 * navigation instructions. Records deterministic route/maneuver/stage IDs, complete intended
 * text, lifecycle timestamps, segment progress, final outcome, and cancellation/failure reason.
 */
internal class VoiceSpeechTraceLog(
    private val maxEntries: Int = 64
) {
    private val lock = Any()
    private val sequence = AtomicLong(0L)
    private val records = ArrayDeque<SpeechTraceRecord>()

    @Volatile
    var traceSink: SpeechTraceSink? = null

    fun nextInstructionId(context: SpeechContextTag): String {
        val seq = sequence.incrementAndGet()
        val routePart = context.routeId?.takeIf { it.isNotBlank() }?.take(12) ?: "noroute"
        val manPart = context.maneuverIndex?.toString() ?: "none"
        val stagePart = context.stage.ifBlank { "general" }
        return "nav_${routePart}_m${manPart}_${stagePart}_$seq"
    }

    fun record(entry: SpeechTraceRecord) {
        synchronized(lock) {
            if (records.size >= maxEntries) records.removeFirst()
            records.addLast(entry)
        }
        runCatching {
            val reasonPart = entry.cancelReason?.let { " reason=$it" }.orEmpty()
            val errPart = entry.errorCode?.let { " err=$it" }.orEmpty()
            val retryPart = if (entry.retryCount > 0) " retries=${entry.retryCount}" else ""
            Log.d(
                TAG,
                "[${entry.instructionId}] state=${entry.finalState} lang=${entry.language} " +
                    "prio=${entry.priority} seg=${entry.completedSegments}/${entry.segmentCount}" +
                    "$reasonPart$errPart$retryPart sel=${entry.selectedAtMs} q=${entry.queuedAtMs} " +
                    "start=${entry.startedAtMs} end=${entry.finishedAtMs} text=\"${entry.intendedText}\""
            )
        }
        traceSink?.onTraceUpdated(entry)
    }

    fun snapshot(): List<SpeechTraceRecord> = synchronized(lock) { records.toList() }

    fun createInitialRecord(
        instructionId: String,
        intendedText: String,
        language: AppLanguage,
        priority: SpeechPriority,
        context: SpeechContextTag,
        segmentCount: Int,
        selectedAtMs: Long,
        queuedAtMs: Long = 0L,
        finalState: SpeechLifecycleState = SpeechLifecycleState.SELECTED,
        cancelReason: String? = null
    ): SpeechTraceRecord = SpeechTraceRecord(
        instructionId = instructionId,
        routeId = context.routeId,
        maneuverIndex = context.maneuverIndex,
        stage = context.stage,
        intendedText = intendedText,
        language = language,
        priority = priority,
        segmentCount = segmentCount,
        completedSegments = 0,
        selectedAtMs = selectedAtMs,
        queuedAtMs = queuedAtMs,
        startedAtMs = 0L,
        finishedAtMs = if (finalState.isTerminal) selectedAtMs else 0L,
        finalState = finalState,
        cancelReason = cancelReason
    )

    companion object {
        private const val TAG = "RahYabVoice"

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
            return if (segments.isEmpty()) listOf(VoiceGuidanceManager.SpeechSegment(trimmed, isEnglish = false)) else segments
        }
    }
}
