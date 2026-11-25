package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.stringsFor
import kotlin.math.abs

data class SpeedLimitDropAhead(
    val changeShapeIndex: Int,
    val distanceMeters: Double,
    val currentLimitMph: Int,
    val newLimitMph: Int
)

/**
 * SL1: Pure Kotlin lookahead detector for significant upcoming speed limit drops along the active
 * route. Detects drops strictly greater than 5 mph (> 8 km/h, e.g. 65 -> 45 or 45 -> 35; ignores
 * minor 5 mph steps such as 45 -> 40) where the lower speed limit lasts at least 150 meters.
 * Unknown/missing speed limits are safely ignored.
 */
object SpeedLimitChangeAhead {

    const val MIN_DROP_EXCLUSIVE_MPH = 5
    const val MIN_LOWER_LIMIT_DURATION_METERS = 150.0
    const val DEFAULT_LOOKAHEAD_METERS = 1500.0
    const val VISUAL_SIGN_WINDOW_METERS = 500.0

    fun findNextDrop(
        route: OfflineRoute?,
        snappedShapeIndex: Int,
        currentSpeedLimitMph: Int? = null,
        maxLookaheadMeters: Double = DEFAULT_LOOKAHEAD_METERS
    ): SpeedLimitDropAhead? {
        if (route == null || route.shape.size < 2 || route.speedLimitsByShapeIndex.isEmpty()) return null
        val shape = route.shape
        val limits = route.speedLimitsByShapeIndex
        val startIdx = snappedShapeIndex.coerceIn(0, shape.lastIndex)

        var activeLimitMph = currentSpeedLimitMph?.takeIf { it > 0 }
            ?: resolveNearestKnownLimit(limits, startIdx)
        var distanceFromStart = 0.0
        var idx = startIdx

        while (idx < shape.lastIndex && distanceFromStart <= maxLookaheadMeters) {
            val nextIdx = idx + 1
            val segMeters = OfflinePlaceSearchEngine.haversineMeters(
                shape[idx].lat, shape[idx].lon, shape[nextIdx].lat, shape[nextIdx].lon
            )
            distanceFromStart += segMeters
            if (distanceFromStart > maxLookaheadMeters) break

            val candidateLimit = limits[nextIdx]?.takeIf { it > 0 }
            if (candidateLimit != null) {
                val base = activeLimitMph
                if (base == null) {
                    activeLimitMph = candidateLimit
                } else if (base - candidateLimit > MIN_DROP_EXCLUSIVE_MPH) {
                    if (lastsAtLeastMeters(shape, limits, nextIdx, base, candidateLimit, MIN_LOWER_LIMIT_DURATION_METERS)) {
                        return SpeedLimitDropAhead(
                            changeShapeIndex = nextIdx,
                            distanceMeters = distanceFromStart,
                            currentLimitMph = base,
                            newLimitMph = candidateLimit
                        )
                    }
                }
            }
            idx = nextIdx
        }
        return null
    }

    private fun resolveNearestKnownLimit(limits: Map<Int, Int>, startIdx: Int): Int? {
        limits[startIdx]?.takeIf { it > 0 }?.let { return it }
        for (delta in 1..3) {
            limits[startIdx - delta]?.takeIf { it > 0 }?.let { return it }
            limits[startIdx + delta]?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    private fun lastsAtLeastMeters(
        shape: List<LatLon>,
        limits: Map<Int, Int>,
        changeIdx: Int,
        baseLimitMph: Int,
        lowerLimitMph: Int,
        requiredMeters: Double
    ): Boolean {
        var sustainedMeters = 0.0
        var j = changeIdx
        while (j < shape.lastIndex) {
            val next = j + 1
            sustainedMeters += OfflinePlaceSearchEngine.haversineMeters(
                shape[j].lat, shape[j].lon, shape[next].lat, shape[next].lon
            )
            val nextLim = limits[next]?.takeIf { it > 0 }
            if (nextLim != null && nextLim > lowerLimitMph && baseLimitMph - nextLim <= MIN_DROP_EXCLUSIVE_MPH) {
                return sustainedMeters >= requiredMeters
            }
            if (sustainedMeters >= requiredMeters) return true
            j = next
        }
        return sustainedMeters >= requiredMeters
    }
}

/**
 * SL2: Announces each qualifying speed limit drop ONCE in DRIVE mode at
 * `max(150 m, speedMps * 8 s)` without repeating due to minor shape index jitter.
 */
class SpeedLimitDropVoiceTracker {

    private val announcedKeys = mutableSetOf<String>()
    private var lastAnnouncedShapeIndex: Int = -100
    private var lastAnnouncedLimitMph: Int = -1

    fun reset() {
        announcedKeys.clear()
        lastAnnouncedShapeIndex = -100
        lastAnnouncedLimitMph = -1
    }

    fun checkVoiceTrigger(
        drop: SpeedLimitDropAhead?,
        speedMps: Float,
        travelMode: TravelMode,
        enabled: Boolean
    ): SpeedLimitDropAhead? {
        if (!enabled || travelMode != TravelMode.DRIVE || drop == null) return null
        val triggerDist = maxOf(150.0, speedMps.toDouble().coerceAtLeast(0.0) * 8.0)
        if (drop.distanceMeters > triggerDist || drop.distanceMeters < 15.0) return null

        val key = "${drop.changeShapeIndex}_${drop.newLimitMph}"
        if (key in announcedKeys) return null
        if (abs(drop.changeShapeIndex - lastAnnouncedShapeIndex) <= 10 &&
            drop.newLimitMph == lastAnnouncedLimitMph
        ) {
            return null
        }

        announcedKeys.add(key)
        lastAnnouncedShapeIndex = drop.changeShapeIndex
        lastAnnouncedLimitMph = drop.newLimitMph
        return drop
    }

    companion object {
        fun formatVoicePhrase(
            newLimitMph: Int,
            unit: DistanceUnit,
            language: AppLanguage
        ): String {
            val s = stringsFor(language)
            val shownLimit = BilingualFormatter.convertMphToUnit(newLimitMph, unit)
            val num = BilingualFormatter.formatNumber(shownLimit, language)
            return "${s.speedLimitDropVoicePrefix} $num ${s.speedLimitDropVoiceSuffix}".trim()
        }
    }
}
