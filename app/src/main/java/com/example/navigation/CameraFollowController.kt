package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import kotlin.math.abs

/**
 * Pure camera-follow decision engine used for both free-drive mode (Waze/Sygic style)
 * and active navigation / simulation.
 *
 * Responsibilities:
 * - Smooth speed-based zoom (closer in city, wider on highways, never jumping)
 * - Minimum movement threshold (skip camera animation when moved < 2 meters and bearing unchanged)
 * - Lower-third vertical anchor in heading-up mode vs centered in north-up mode
 * - Auto-recenter trigger after ~10 seconds of no map touch while driving > 10 mph
 */
class CameraFollowController {

    private var lastAnimatedLat: Double? = null
    private var lastAnimatedLon: Double? = null
    private var lastAnimatedBearing: Float = Float.NaN
    private var lastAnimatedHeadingUp: Boolean? = null
    private var smoothedZoom: Double? = null
    private var lastUserTouchMs: Long = 0L

    data class CameraFollowUpdate(
        val lat: Double,
        val lon: Double,
        val zoom: Double,
        val tilt: Double,
        val bearing: Double,
        val animationDurationMs: Int,
        val anchorLowerThird: Boolean
    )

    fun reset() {
        lastAnimatedLat = null
        lastAnimatedLon = null
        lastAnimatedBearing = Float.NaN
        lastAnimatedHeadingUp = null
        smoothedZoom = null
    }

    fun recordUserMapGesture(nowMs: Long) {
        lastUserTouchMs = nowMs
    }

    /**
     * Returns true when camera following is paused, no place/route preview is open,
     * the user is driving faster than 10 mph (~4.4704 m/s), and at least 10 seconds
     * have elapsed since the last map touch.
     */
    fun shouldAutoRecenter(
        isCameraFollowing: Boolean,
        isPlaceOrPreviewOpen: Boolean,
        speedMps: Float,
        nowMs: Long
    ): Boolean {
        if (isCameraFollowing || isPlaceOrPreviewOpen) return false
        if (speedMps <= AUTO_RECENTER_MIN_SPEED_MPS) return false
        if (lastUserTouchMs <= 0L) return false
        return (nowMs - lastUserTouchMs) >= AUTO_RECENTER_DELAY_MS
    }

    /**
     * Computes the next smooth camera command for a GPS or simulated position update,
     * or returns null if the car moved less than 2 meters and bearing/mode did not change meaningfully.
     */
    fun computeFollowCameraUpdate(
        lat: Double,
        lon: Double,
        speedMps: Float,
        headingDegrees: Float,
        isHeadingUpMode: Boolean,
        is3DView: Boolean,
        isNavigating: Boolean,
        simulationSpeedMultiplier: Int = 1,
        forceImmediate: Boolean = false,
        isWalkingMode: Boolean = false,
        travelMode: TravelMode = if (isWalkingMode) TravelMode.WALK else TravelMode.DRIVE
    ): CameraFollowUpdate? {
        val effectiveMode = if (isWalkingMode) TravelMode.WALK else travelMode
        val effectiveHeadingUp = if (effectiveMode == TravelMode.BIKE) true else isHeadingUpMode
        val targetBearing = if (effectiveHeadingUp) {
            CompassTracker.normalizeAngle(headingDegrees)
        } else {
            0f
        }

        val movedMeters = if (lastAnimatedLat != null && lastAnimatedLon != null) {
            OfflinePlaceSearchEngine.haversineMeters(lastAnimatedLat!!, lastAnimatedLon!!, lat, lon)
        } else {
            Double.MAX_VALUE
        }

        val bearingDelta = if (lastAnimatedBearing.isNaN()) {
            Float.MAX_VALUE
        } else {
            abs(CompassTracker.shortestAngleDelta(lastAnimatedBearing, targetBearing))
        }

        val modeChanged = lastAnimatedHeadingUp != effectiveHeadingUp

        if (!forceImmediate && !modeChanged && movedMeters < MIN_MOVE_METERS && bearingDelta < MIN_BEARING_CHANGE_DEG) {
            return null
        }

        val targetZoom = when (effectiveMode) {
            TravelMode.WALK -> 17.0
            TravelMode.BIKE -> 16.0
            TravelMode.DRIVE -> targetZoomForSpeedMps(speedMps, isNavigating)
        }
        val nextZoom = smoothZoom(targetZoom, forceImmediate)

        val tilt = when {
            effectiveMode != TravelMode.DRIVE -> 0.0
            !is3DView -> 0.0
            effectiveHeadingUp -> if (isNavigating) 55.0 else 45.0
            else -> 0.0
        }

        val animDurationMs = when {
            forceImmediate -> 450
            simulationSpeedMultiplier >= 16 -> 0
            simulationSpeedMultiplier >= 8 -> 140
            simulationSpeedMultiplier > 1 -> 220
            else -> REAL_GPS_ANIMATION_MS
        }

        lastAnimatedLat = lat
        lastAnimatedLon = lon
        lastAnimatedBearing = targetBearing
        lastAnimatedHeadingUp = isHeadingUpMode

        return CameraFollowUpdate(
            lat = lat,
            lon = lon,
            zoom = nextZoom,
            tilt = tilt,
            bearing = targetBearing.toDouble(),
            animationDurationMs = animDurationMs,
            anchorLowerThird = isHeadingUpMode
        )
    }

    private fun smoothZoom(targetZoom: Double, forceImmediate: Boolean): Double {
        val prev = smoothedZoom
        val next = if (prev == null || forceImmediate) {
            targetZoom
        } else {
            val blended = prev + (targetZoom - prev) * ZOOM_SMOOTHING_ALPHA
            val delta = (blended - prev).coerceIn(-MAX_ZOOM_STEP_PER_FIX, MAX_ZOOM_STEP_PER_FIX)
            prev + delta
        }
        smoothedZoom = next
        return next
    }

    companion object {
        const val MIN_MOVE_METERS = 2.0
        const val MIN_BEARING_CHANGE_DEG = 8.0f
        const val REAL_GPS_ANIMATION_MS = 1000
        const val AUTO_RECENTER_MIN_SPEED_MPS = 4.4704f // 10 mph
        const val AUTO_RECENTER_DELAY_MS = 10_000L
        private const val ZOOM_SMOOTHING_ALPHA = 0.25
        private const val MAX_ZOOM_STEP_PER_FIX = 0.22

        /**
         * Piecewise linear speed -> zoom curve:
         * - Stationary / slow city (< 15 mph / 6.7 m/s): ~17.2 (close street view)
         * - City driving (15–35 mph / 6.7–15.6 m/s): 17.2 -> 16.4
         * - Suburban / arterial (35–55 mph / 15.6–24.6 m/s): 16.4 -> 15.6
         * - Highway (55–75+ mph / 24.6–33.5+ m/s): 15.6 -> 14.8 (wide highway view)
         */
        fun targetZoomForSpeedMps(speedMps: Float, isNavigating: Boolean = false): Double {
            val mph = (speedMps * 2.23694f).coerceAtLeast(0f)
            val baseZoom = when {
                mph <= 15f -> 17.2
                mph <= 35f -> 17.2 - ((mph - 15f) / 20f) * 0.8
                mph <= 55f -> 16.4 - ((mph - 35f) / 20f) * 0.8
                mph <= 75f -> 15.6 - ((mph - 55f) / 20f) * 0.8
                else -> 14.8
            }
            return if (isNavigating) baseZoom else (baseZoom - 0.15)
        }
    }
}
