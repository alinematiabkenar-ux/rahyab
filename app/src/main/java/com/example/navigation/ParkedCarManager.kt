package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine

/**
 * Candidate parked car location produced by auto-detection or manual save.
 */
data class ParkedSpotCandidate(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val timestampMs: Long,
    val source: String
)

/**
 * Pure JVM state machine managing parked car auto-detection rules (F4.1 & F4.2):
 *  - Rule (a) Navigation arrival: first GPS fix after arrival with speed < 3 mph and accuracy < 50 m.
 *  - Rule (b) Free drive stop: speed > 15 mph continuously for >= 2 min, then < 3 mph for >= 2 min.
 *  - Rule (c) Car Bluetooth ACL disconnect: last good fix (accuracy < 50 m) from the last 2 min.
 *  - Rule (d) Manual "I parked here": saves immediately (never skipped near Home).
 *  - Home radius skip: all auto-saves are skipped within 150 m of Home when enabled (default ON).
 */
class ParkedCarManager {

    private val recentGoodFixes = ArrayDeque<GpsFix>()
    private var awaitingArrivalStopFix: Boolean = false
    private var drivingAbove15StartMs: Long? = null
    private var hasDrivenAbove15For2Min: Boolean = false
    private var stoppedBelow3StartMs: Long? = null

    @Synchronized
    fun onNavigationArrived(
        currentFix: GpsFix?,
        homeLat: Double?,
        homeLon: Double?,
        skipAtHomeEnabled: Boolean,
        isWalkingMode: Boolean
    ): ParkedSpotCandidate? {
        if (isWalkingMode) return null
        if (currentFix != null &&
            currentFix.speedMps < SLOW_SPEED_MPS &&
            currentFix.accuracyMeters < MAX_GOOD_ACCURACY_METERS
        ) {
            awaitingArrivalStopFix = false
            resetFreeDriveTracking()
            if (shouldSkipBecauseNearHome(currentFix.lat, currentFix.lon, homeLat, homeLon, skipAtHomeEnabled)) {
                return null
            }
            return ParkedSpotCandidate(
                lat = currentFix.lat,
                lon = currentFix.lon,
                accuracyMeters = currentFix.accuracyMeters,
                timestampMs = currentFix.timestampMs,
                source = SOURCE_NAV_ARRIVAL
            )
        }
        awaitingArrivalStopFix = true
        return null
    }

    @Synchronized
    fun onGpsFix(
        fix: GpsFix,
        isNavigating: Boolean,
        isWalkingMode: Boolean,
        homeLat: Double?,
        homeLon: Double?,
        skipAtHomeEnabled: Boolean
    ): ParkedSpotCandidate? {
        recordGoodFixIfValid(fix)
        if (isWalkingMode) return null

        // Rule (a): waiting for the first qualifying fix after navigation arrival
        if (awaitingArrivalStopFix) {
            if (fix.speedMps < SLOW_SPEED_MPS && fix.accuracyMeters < MAX_GOOD_ACCURACY_METERS) {
                awaitingArrivalStopFix = false
                resetFreeDriveTracking()
                if (shouldSkipBecauseNearHome(fix.lat, fix.lon, homeLat, homeLon, skipAtHomeEnabled)) {
                    return null
                }
                return ParkedSpotCandidate(
                    lat = fix.lat,
                    lon = fix.lon,
                    accuracyMeters = fix.accuracyMeters,
                    timestampMs = fix.timestampMs,
                    source = SOURCE_NAV_ARRIVAL
                )
            }
            return null
        }

        // Rule (b): Free-drive > 15 mph for 2 min, then < 3 mph for 2 min
        if (isNavigating) {
            resetFreeDriveTracking()
            return null
        }

        val nowMs = fix.timestampMs
        if (!hasDrivenAbove15For2Min) {
            if (fix.speedMps > DRIVING_SPEED_MPS) {
                val start = drivingAbove15StartMs
                if (start == null) {
                    drivingAbove15StartMs = nowMs
                } else if (nowMs - start >= DRIVE_DURATION_MS) {
                    hasDrivenAbove15For2Min = true
                    stoppedBelow3StartMs = null
                }
            } else {
                drivingAbove15StartMs = null
            }
            return null
        }

        // Already drove > 15 mph for >= 2 min; watch for a sustained stop < 3 mph for >= 2 min
        if (fix.speedMps < SLOW_SPEED_MPS) {
            val stopStart = stoppedBelow3StartMs
            if (stopStart == null) {
                stoppedBelow3StartMs = nowMs
            } else if (nowMs - stopStart >= STOP_DURATION_MS) {
                val bestFix = if (fix.accuracyMeters < MAX_GOOD_ACCURACY_METERS) {
                    fix
                } else {
                    findLatestGoodFixWithinWindow(nowMs)
                }
                resetFreeDriveTracking()
                if (bestFix == null) return null
                if (shouldSkipBecauseNearHome(bestFix.lat, bestFix.lon, homeLat, homeLon, skipAtHomeEnabled)) {
                    return null
                }
                return ParkedSpotCandidate(
                    lat = bestFix.lat,
                    lon = bestFix.lon,
                    accuracyMeters = bestFix.accuracyMeters,
                    timestampMs = bestFix.timestampMs,
                    source = SOURCE_FREE_DRIVE
                )
            }
        } else if (fix.speedMps > DRIVING_SPEED_MPS) {
            // Resumed driving at speed: keep armed, restart stop timer
            stoppedBelow3StartMs = null
        } else {
            // Moving slowly (3..15 mph): reset stop timer so stop must be continuous < 3 mph
            stoppedBelow3StartMs = null
        }
        return null
    }

    @Synchronized
    fun onCarBluetoothDisconnected(
        nowMs: Long,
        homeLat: Double?,
        homeLon: Double?,
        skipAtHomeEnabled: Boolean
    ): ParkedSpotCandidate? {
        val fix = findLatestGoodFixWithinWindow(nowMs) ?: return null
        awaitingArrivalStopFix = false
        resetFreeDriveTracking()
        if (shouldSkipBecauseNearHome(fix.lat, fix.lon, homeLat, homeLon, skipAtHomeEnabled)) {
            return null
        }
        return ParkedSpotCandidate(
            lat = fix.lat,
            lon = fix.lon,
            accuracyMeters = fix.accuracyMeters,
            timestampMs = fix.timestampMs,
            source = SOURCE_BLUETOOTH
        )
    }

    @Synchronized
    fun buildManualParkedSpot(
        currentFix: GpsFix?,
        fallbackLocation: LatLon,
        nowMs: Long = System.currentTimeMillis()
    ): ParkedSpotCandidate {
        awaitingArrivalStopFix = false
        resetFreeDriveTracking()
        return if (currentFix != null) {
            ParkedSpotCandidate(
                lat = currentFix.lat,
                lon = currentFix.lon,
                accuracyMeters = currentFix.accuracyMeters,
                timestampMs = nowMs,
                source = SOURCE_MANUAL
            )
        } else {
            ParkedSpotCandidate(
                lat = fallbackLocation.lat,
                lon = fallbackLocation.lon,
                accuracyMeters = 25f,
                timestampMs = nowMs,
                source = SOURCE_MANUAL
            )
        }
    }

    @Synchronized
    fun reset() {
        recentGoodFixes.clear()
        awaitingArrivalStopFix = false
        resetFreeDriveTracking()
    }

    private fun resetFreeDriveTracking() {
        drivingAbove15StartMs = null
        hasDrivenAbove15For2Min = false
        stoppedBelow3StartMs = null
    }

    private fun recordGoodFixIfValid(fix: GpsFix) {
        if (fix.accuracyMeters < MAX_GOOD_ACCURACY_METERS) {
            recentGoodFixes.addLast(fix)
        }
        val cutoff = fix.timestampMs - RECENT_FIX_WINDOW_MS
        while (recentGoodFixes.isNotEmpty() && recentGoodFixes.first().timestampMs < cutoff) {
            recentGoodFixes.removeFirst()
        }
        while (recentGoodFixes.size > MAX_BUFFER_SIZE) {
            recentGoodFixes.removeFirst()
        }
    }

    private fun findLatestGoodFixWithinWindow(nowMs: Long): GpsFix? {
        val cutoff = nowMs - RECENT_FIX_WINDOW_MS
        for (i in recentGoodFixes.size - 1 downTo 0) {
            val candidate = recentGoodFixes[i]
            if (candidate.timestampMs >= cutoff && candidate.accuracyMeters < MAX_GOOD_ACCURACY_METERS) {
                return candidate
            }
        }
        return null
    }

    companion object {
        const val SLOW_SPEED_MPS = 1.34112f          // 3 mph
        const val DRIVING_SPEED_MPS = 6.7056f        // 15 mph
        const val MAX_GOOD_ACCURACY_METERS = 50f
        const val DRIVE_DURATION_MS = 120_000L       // 2 minutes
        const val STOP_DURATION_MS = 120_000L        // 2 minutes
        const val RECENT_FIX_WINDOW_MS = 120_000L    // 2 minutes
        const val HOME_SKIP_RADIUS_METERS = 150.0
        private const val MAX_BUFFER_SIZE = 240

        const val SOURCE_NAV_ARRIVAL = "nav_arrival"
        const val SOURCE_FREE_DRIVE = "free_drive"
        const val SOURCE_BLUETOOTH = "bluetooth"
        const val SOURCE_MANUAL = "manual"

        fun shouldSkipBecauseNearHome(
            lat: Double,
            lon: Double,
            homeLat: Double?,
            homeLon: Double?,
            skipAtHomeEnabled: Boolean
        ): Boolean {
            if (!skipAtHomeEnabled || homeLat == null || homeLon == null) return false
            val distance = OfflinePlaceSearchEngine.haversineMeters(lat, lon, homeLat, homeLon)
            return distance <= HOME_SKIP_RADIUS_METERS
        }
    }
}
