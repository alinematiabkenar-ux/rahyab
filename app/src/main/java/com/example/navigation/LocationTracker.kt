package com.example.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
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
 * Pure offline GPS location tracker with battery-aware adaptive sampling (Phase 4).
 *
 * - During active navigation: 1 s high-accuracy GPS + direct hardware `GPS_PROVIDER` fallback
 * - While browsing/idle: 5 s / 15 m balanced power-saving interval to conserve battery
 */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _currentFix = MutableStateFlow<GpsFix?>(null)
    val currentFix: StateFlow<GpsFix?> = _currentFix.asStateFlow()

    private var isTracking = false
    private var isActiveNavigationMode = false

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { emitLocation(it) }
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            emitLocation(location)
        }
        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        isTracking = true

        // Seed with last known GPS fix immediately
        runCatching {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastGps != null) emitLocation(lastGps)
        }
        runCatching {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _currentFix.value == null) emitLocation(loc)
            }
        }

        applyLocationRequestProfile(activeNav = isActiveNavigationMode)
    }

    /**
     * Switches between high-frequency turn-by-turn GPS (1 s) and low-battery browsing mode (5 s / 15 m).
     */
    @SuppressLint("MissingPermission")
    fun setNavigationMode(active: Boolean) {
        if (isActiveNavigationMode == active && isTracking) return
        isActiveNavigationMode = active
        if (isTracking) {
            applyLocationRequestProfile(activeNav = active)
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationRequestProfile(activeNav: Boolean) {
        runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
        runCatching { locationManager.removeUpdates(gpsListener) }

        val intervalMs = if (activeNav) 1000L else 5000L
        val minIntervalMs = if (activeNav) 500L else 2500L
        val minDistanceMeters = if (activeNav) 0f else 15f
        val priority = if (activeNav) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        runCatching {
            val req = LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .setMinUpdateDistanceMeters(minDistanceMeters)
                .build()
            fusedClient.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
        }

        // Direct hardware GPS listener active during turn-by-turn driving (zero Play Services dependency)
        if (activeNav) {
            runCatching {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        1f,
                        gpsListener,
                        Looper.getMainLooper()
                    )
                }
            }
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
        runCatching { locationManager.removeUpdates(gpsListener) }
    }

    private fun emitLocation(loc: Location) {
        val prev = _currentFix.value
        val bearing = if (loc.hasBearing() && loc.speed > 1.2f) {
            loc.bearing
        } else {
            prev?.bearing ?: 0f
        }
        _currentFix.value = GpsFix(
            lat = loc.latitude,
            lon = loc.longitude,
            bearing = bearing,
            speedMps = if (loc.hasSpeed()) loc.speed.coerceAtLeast(0f) else 0f,
            accuracyMeters = loc.accuracy,
            timestampMs = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }
}
