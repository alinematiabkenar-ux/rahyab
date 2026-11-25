package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * X7: Throttled, non-blocking cache for optional HPMS speed limit fallback queries.
 * Only ever used when OSM has no `maxspeed` tag on the current road segment.
 */
class HpmsSpeedFallbackCache {

    @Volatile
    var cachedSpeedMph: Int? = null
        private set

    @Volatile
    private var lastQueryTimeMs: Long = 0L

    @Volatile
    private var lastQueryLoc: LatLon? = null

    private val inFlight = AtomicBoolean(false)

    fun reset() {
        cachedSpeedMph = null
        lastQueryTimeMs = 0L
        lastQueryLoc = null
        inFlight.set(false)
    }

    fun shouldRefresh(nowMs: Long, lat: Double, lon: Double): Boolean {
        val prev = lastQueryLoc ?: return true
        if (nowMs - lastQueryTimeMs >= MIN_REFRESH_INTERVAL_MS) return true
        return OfflinePlaceSearchEngine.haversineMeters(prev.lat, prev.lon, lat, lon) >= MIN_REFRESH_DISTANCE_METERS
    }

    fun maybeRefreshAsync(
        scope: CoroutineScope,
        nowMs: Long,
        lat: Double,
        lon: Double,
        lookupOnIo: () -> Int?
    ) {
        if (!shouldRefresh(nowMs, lat, lon)) return
        if (!inFlight.compareAndSet(false, true)) return
        lastQueryTimeMs = nowMs
        lastQueryLoc = LatLon(lat, lon)
        scope.launch(Dispatchers.IO) {
            try {
                cachedSpeedMph = runCatching { lookupOnIo() }.getOrNull()
            } finally {
                inFlight.set(false)
            }
        }
    }

    companion object {
        const val SOURCE_OSM = "osm"
        const val SOURCE_HPMS = "hpms"
        const val SOURCE_NONE = "none"
        const val MIN_REFRESH_INTERVAL_MS = 2_000L
        const val MIN_REFRESH_DISTANCE_METERS = 30.0

        /**
         * Resolves effective speed limit and its source tag (`"osm"`, `"hpms"`, or `"none"`).
         * Guarantees OSM values are NEVER overridden by HPMS, and non-DRIVE modes always return `null` / `"none"`.
         */
        fun resolveSpeedLimitWithSource(
            isDrive: Boolean,
            osmSpeedLimitMph: Int?,
            hpmsFallbackMph: Int?
        ): Pair<Int?, String> {
            if (!isDrive) return null to SOURCE_NONE
            if (osmSpeedLimitMph != null) return osmSpeedLimitMph to SOURCE_OSM
            if (hpmsFallbackMph != null) return hpmsFallbackMph to SOURCE_HPMS
            return null to SOURCE_NONE
        }
    }
}
