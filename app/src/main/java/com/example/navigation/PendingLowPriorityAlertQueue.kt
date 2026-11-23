package com.example.navigation

import com.example.i18n.AppLanguage
import kotlin.math.abs

/**
 * Holds at most ONE pending low-priority voice alert (such as a speed bump warning) while TTS
 * is busy with a higher-priority turn/lane or standalone utterance.
 *
 * When the active utterance finishes, [pollIfStillValid] returns the pending alert UNLESS:
 * - it is older than [MAX_AGE_MS] (8 seconds), OR
 * - the alert point (e.g., speed bump) is already behind the user's current position & heading.
 */
class PendingLowPriorityAlertQueue {

    data class PendingAlert(
        val text: String,
        val effectiveLanguage: AppLanguage,
        val enqueuedAtMs: Long,
        val targetLat: Double?,
        val targetLon: Double?
    )

    private val lock = Any()
    private var pending: PendingAlert? = null
    private var lastUserLat: Double? = null
    private var lastUserLon: Double? = null
    private var lastUserHeadingDeg: Float? = null

    fun updateUserMotion(lat: Double, lon: Double, headingDegrees: Float) {
        synchronized(lock) {
            lastUserLat = lat
            lastUserLon = lon
            lastUserHeadingDeg = headingDegrees
        }
    }

    fun offer(
        text: String,
        effectiveLanguage: AppLanguage,
        targetLat: Double? = null,
        targetLon: Double? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (text.isBlank()) return
        synchronized(lock) {
            pending = PendingAlert(
                text = text,
                effectiveLanguage = effectiveLanguage,
                enqueuedAtMs = nowMs,
                targetLat = targetLat,
                targetLon = targetLon
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            pending = null
        }
    }

    fun pollIfStillValid(nowMs: Long = System.currentTimeMillis()): PendingAlert? {
        val candidate: PendingAlert
        val uLat: Double?
        val uLon: Double?
        val uHeading: Float?
        synchronized(lock) {
            candidate = pending ?: return null
            pending = null
            uLat = lastUserLat
            uLon = lastUserLon
            uHeading = lastUserHeadingDeg
        }

        if (nowMs - candidate.enqueuedAtMs > MAX_AGE_MS) {
            return null
        }

        val tLat = candidate.targetLat
        val tLon = candidate.targetLon
        if (tLat != null && tLon != null && uLat != null && uLon != null && uHeading != null) {
            if (isTargetBehindUser(uLat, uLon, uHeading, tLat, tLon)) {
                return null
            }
        }
        return candidate
    }

    companion object {
        const val MAX_AGE_MS = 8_000L

        /**
         * Returns true when the target coordinate is behind the driver's heading (> 95° off bow).
         */
        fun isTargetBehindUser(
            userLat: Double,
            userLon: Double,
            userHeadingDeg: Float,
            targetLat: Double,
            targetLon: Double
        ): Boolean {
            val bearingToTarget = ValhallaResponseParser.bearingDegrees(
                LatLon(userLat, userLon),
                LatLon(targetLat, targetLon)
            )
            val delta = abs(((bearingToTarget - userHeadingDeg.toDouble() + 540.0) % 360.0) - 180.0)
            return delta > 95.0
        }
    }
}
