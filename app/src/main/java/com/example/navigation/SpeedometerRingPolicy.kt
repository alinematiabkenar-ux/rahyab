package com.example.navigation

import com.example.i18n.DistanceUnit

enum class SpeedRingState {
    NORMAL,
    APPROACHING_LIMIT,
    OVERSPEED
}

/**
 * V3: Pure-Kotlin color-threshold policy for the circular speedometer ring on phone & Android Auto.
 * - [SpeedRingState.NORMAL]: no speed limit or comfortably below the limit.
 * - [SpeedRingState.APPROACHING_LIMIT] (yellow): within 3 mph (imperial) or 5 km/h (metric) of the limit.
 * - [SpeedRingState.OVERSPEED] (red): strictly above `limit + overspeedTolerance`.
 */
object SpeedometerRingPolicy {

    const val APPROACHING_MARGIN_MPH = 3.0f
    const val APPROACHING_MARGIN_KMH = 5.0f

    fun evaluate(
        currentSpeedMps: Float,
        speedLimitMph: Int?,
        overspeedToleranceMph: Int,
        unit: DistanceUnit
    ): SpeedRingState {
        if (speedLimitMph == null || speedLimitMph <= 0) return SpeedRingState.NORMAL
        val tolMph = overspeedToleranceMph.coerceAtLeast(0).toFloat()
        val safeSpeedMps = currentSpeedMps.coerceAtLeast(0f)

        return if (unit == DistanceUnit.METRIC) {
            val currentKmh = safeSpeedMps * 3.6f
            val limitKmh = speedLimitMph * 1.60934f
            val tolKmh = tolMph * 1.60934f
            when {
                currentKmh > limitKmh + tolKmh -> SpeedRingState.OVERSPEED
                currentKmh >= limitKmh - APPROACHING_MARGIN_KMH -> SpeedRingState.APPROACHING_LIMIT
                else -> SpeedRingState.NORMAL
            }
        } else {
            val currentMph = safeSpeedMps * 2.23694f
            val limitMph = speedLimitMph.toFloat()
            when {
                currentMph > limitMph + tolMph -> SpeedRingState.OVERSPEED
                currentMph >= limitMph - APPROACHING_MARGIN_MPH -> SpeedRingState.APPROACHING_LIMIT
                else -> SpeedRingState.NORMAL
            }
        }
    }

    fun isEstimatedLimitSource(speedLimitSource: String?): Boolean =
        speedLimitSource.equals("hpms", ignoreCase = true)
}
