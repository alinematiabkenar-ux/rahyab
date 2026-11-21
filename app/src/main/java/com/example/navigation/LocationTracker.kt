package com.example.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speedMps: Float,
    val accuracyMeters: Float,
    val timestampMs: Long
)

/**
 * Pure offline GPS location tracker with strict freshness/accuracy guards:
 *
 * - Startup last-known locations older than 2 minutes or worse than 100 m accuracy are ignored.
 * - Foreground map screen (not navigating): `PRIORITY_HIGH_ACCURACY` at 2 s interval.
 * - Active navigation: `PRIORITY_HIGH_ACCURACY` at 1 s interval using Fused Location Provider
 *   only; raw `GPS_PROVIDER` is enabled only as a fallback if no fused update arrives for 3 s,
 *   and is paused again as soon as fused updates resume.
 * - Background (not navigating): `PRIORITY_LOW_POWER` to conserve battery.
 * - Accuracy guard: ignores fixes worse than 50 m (or coarse accuracy jumps) when a more
 *   accurate fix was received in the last 5 seconds.
 */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _currentFix = MutableStateFlow<GpsFix?>(null)
    val currentFix: StateFlow<GpsFix?> = _currentFix.asStateFlow()

    private var isTracking = false
    private var isActiveNavigationMode = false
    private var isAppInForeground = true
    private var isGpsFallbackActive = false
    private var lastAcceptedElapsedMs: Long = 0L
    private var lastAcceptedAccuracyMeters: Float = Float.MAX_VALUE

    private val fusedFallbackRunnable = Runnable {
        activateGpsFallbackIfNeeded()
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            // Fused provider is alive: stop raw GPS fallback so the two sources never fight
            if (isGpsFallbackActive) {
                stopGpsFallback()
            }
            if (isTracking && isActiveNavigationMode) {
                rescheduleFusedFallbackWatchdog()
            }
            emitLocation(loc)
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Only accept raw GPS updates while fallback mode is actively engaged
            if (!isGpsFallbackActive) return
            emitLocation(location)
        }

        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true

        // Seed with last known location ONLY if fresh (<= 2 min) and accurate (<= 100 m)
        runCatching {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastGps != null && isUsableLastKnownLocation(lastGps)) {
                emitLocation(lastGps)
            }
        }
        runCatching {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _currentFix.value == null && isUsableLastKnownLocation(loc)) {
                    emitLocation(loc)
                }
            }
        }

        applyLocationRequestProfile()
    }

    /**
     * Switches between turn-by-turn navigation GPS (1 s high-accuracy, fused primary + 3 s GPS fallback)
     * and map browsing / background modes.
     */
    @SuppressLint("MissingPermission")
    fun setNavigationMode(active: Boolean) {
        if (isActiveNavigationMode == active && isTracking) return
        isActiveNavigationMode = active
        if (isTracking) {
            applyLocationRequestProfile()
        }
    }

    /**
     * Updates foreground/background visibility so foreground map browsing stays on
     * `PRIORITY_HIGH_ACCURACY` (2 s), while low-power mode is used only when the app
     * is in the background and not navigating.
     */
    @SuppressLint("MissingPermission")
    fun setAppInForeground(inForeground: Boolean) {
        if (isAppInForeground == inForeground) return
        isAppInForeground = inForeground
        if (isTracking) {
            applyLocationRequestProfile()
        }
    }

    /**
     * Re-applies the active location request profile (for example after the user enables
     * "Use precise location" in Android Settings and returns to the app).
     */
    @SuppressLint("MissingPermission")
    fun refreshTrackingProfile() {
        if (isTracking) {
            applyLocationRequestProfile()
        } else {
            startTracking()
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationRequestProfile() {
        runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
        stopGpsFallback()
        cancelFusedFallbackWatchdog()

        val priority: Int
        val intervalMs: Long
        val minIntervalMs: Long
        val minDistanceMeters: Float

        when {
            isActiveNavigationMode -> {
                priority = Priority.PRIORITY_HIGH_ACCURACY
                intervalMs = NAV_INTERVAL_MS
                minIntervalMs = 500L
                minDistanceMeters = 0f
            }
            isAppInForeground -> {
                priority = Priority.PRIORITY_HIGH_ACCURACY
                intervalMs = FOREGROUND_MAP_INTERVAL_MS
                minIntervalMs = 1_000L
                minDistanceMeters = 0f
            }
            else -> {
                priority = Priority.PRIORITY_LOW_POWER
                intervalMs = BACKGROUND_LOW_POWER_INTERVAL_MS
                minIntervalMs = 5_000L
                minDistanceMeters = 25f
            }
        }

        runCatching {
            val req = LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .setMinUpdateDistanceMeters(minDistanceMeters)
                .build()
            fusedClient.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
        }

        // During navigation, use Fused only; engage raw GPS_PROVIDER only if fused goes silent for 3 s
        if (isActiveNavigationMode) {
            rescheduleFusedFallbackWatchdog()
        }
    }

    private fun rescheduleFusedFallbackWatchdog() {
        mainHandler.removeCallbacks(fusedFallbackRunnable)
        mainHandler.postDelayed(fusedFallbackRunnable, FUSED_FALLBACK_TIMEOUT_MS)
    }

    private fun cancelFusedFallbackWatchdog() {
        mainHandler.removeCallbacks(fusedFallbackRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun activateGpsFallbackIfNeeded() {
        if (!isTracking || !isActiveNavigationMode || isGpsFallbackActive) return
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                isGpsFallbackActive = true
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    NAV_INTERVAL_MS,
                    0f,
                    gpsListener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    private fun stopGpsFallback() {
        isGpsFallbackActive = false
        runCatching { locationManager.removeUpdates(gpsListener) }
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        cancelFusedFallbackWatchdog()
        stopGpsFallback()
        runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
    }

    private fun isUsableLastKnownLocation(loc: Location): Boolean {
        if (!loc.hasAccuracy() || loc.accuracy <= 0f || loc.accuracy > MAX_LAST_KNOWN_ACCURACY_M) {
            return false
        }
        val ageMs = computeLocationAgeMs(loc)
        return ageMs in 0L..MAX_LAST_KNOWN_AGE_MS
    }

    private fun computeLocationAgeMs(loc: Location): Long {
        if (loc.elapsedRealtimeNanos > 0L) {
            return (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        }
        if (loc.time > 0L) {
            return System.currentTimeMillis() - loc.time
        }
        return Long.MAX_VALUE
    }

    private fun emitLocation(loc: Location) {
        val prev = _currentFix.value
        val nowElapsed = SystemClock.elapsedRealtime()
        val incomingAccuracy = if (loc.hasAccuracy() && loc.accuracy > 0f) loc.accuracy else Float.MAX_VALUE

        // Reject fixes worse than 50 m (or coarse jumps) when a better fix was received within 5 s
        if (prev != null) {
            val elapsedSinceLast = nowElapsed - lastAcceptedElapsedMs
            if (elapsedSinceLast in 0L..RECENT_FIX_WINDOW_MS && incomingAccuracy > lastAcceptedAccuracyMeters) {
                if (incomingAccuracy > BAD_ACCURACY_THRESHOLD_M ||
                    (incomingAccuracy > lastAcceptedAccuracyMeters * 2f && incomingAccuracy > 25f)
                ) {
                    return
                }
            }
        }

        val bearing = if (loc.hasBearing() && loc.speed > 1.2f) {
            loc.bearing
        } else {
            prev?.bearing ?: 0f
        }

        lastAcceptedElapsedMs = nowElapsed
        lastAcceptedAccuracyMeters = incomingAccuracy

        _currentFix.value = GpsFix(
            lat = loc.latitude,
            lon = loc.longitude,
            bearing = bearing,
            speedMps = if (loc.hasSpeed()) loc.speed.coerceAtLeast(0f) else 0f,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.coerceAtLeast(0f) else 0f,
            timestampMs = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }

    companion object {
        const val MAX_LAST_KNOWN_AGE_MS = 2 * 60 * 1000L // 2 minutes
        const val MAX_LAST_KNOWN_ACCURACY_M = 100f
        const val BAD_ACCURACY_THRESHOLD_M = 50f
        const val RECENT_FIX_WINDOW_MS = 5_000L
        const val FUSED_FALLBACK_TIMEOUT_MS = 3_000L
        const val NAV_INTERVAL_MS = 1_000L
        const val FOREGROUND_MAP_INTERVAL_MS = 2_000L
        const val BACKGROUND_LOW_POWER_INTERVAL_MS = 10_000L
    }
}
