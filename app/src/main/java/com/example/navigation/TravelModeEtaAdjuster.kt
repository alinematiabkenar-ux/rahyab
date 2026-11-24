package com.example.navigation

/**
 * Adjusts remaining travel time for [TravelMode.WALK] and [TravelMode.BIKE] using the user's
 * measured average moving speed over a sliding 3-minute window.
 *
 * Rules:
 * - Applies strictly to WALK and BIKE (DRIVE returns the baseline estimate untouched).
 * - Clamps the adjustment multiplier to [MIN_MULTIPLIER] (0.6x) .. [MAX_MULTIPLIER] (1.5x) of
 *   Valhalla's baseline remaining time estimate.
 * - Never adjusts the estimate while the user has been stopped for less than 3 minutes
 *   (short stops at crosswalks or traffic lights keep the previously measured pace multiplier).
 */
class TravelModeEtaAdjuster {

    private data class SpeedSample(
        val timestampMs: Long,
        val speedMps: Float
    )

    private val movingSamples = ArrayDeque<SpeedSample>()
    private var stoppedSinceMs: Long? = null
    private var currentMultiplier: Double = 1.0

    fun reset() {
        movingSamples.clear()
        stoppedSinceMs = null
        currentMultiplier = 1.0
    }

    /**
     * Records the latest speed sample and returns the effective ETA multiplier in [0.6, 1.5].
     */
    fun updateAndGetMultiplier(
        mode: TravelMode,
        routeDistanceMeters: Double,
        routeDurationSeconds: Double,
        currentSpeedMps: Float,
        timestampMs: Long
    ): Double {
        if (mode == TravelMode.DRIVE) return 1.0
        if (routeDistanceMeters <= 1.0 || routeDurationSeconds <= 1.0) return 1.0

        val windowStartMs = timestampMs - WINDOW_MS
        while (movingSamples.isNotEmpty() && movingSamples.first().timestampMs < windowStartMs) {
            movingSamples.removeFirst()
        }

        val minMovingSpeedMps = when (mode) {
            TravelMode.WALK -> MIN_WALK_MOVING_MPS
            TravelMode.BIKE -> MIN_BIKE_MOVING_MPS
            TravelMode.DRIVE -> return 1.0
        }

        if (currentSpeedMps >= minMovingSpeedMps) {
            stoppedSinceMs = null
            movingSamples.addLast(SpeedSample(timestampMs, currentSpeedMps))
            val avgMeasuredMps = movingSamples.sumOf { it.speedMps.toDouble() } / movingSamples.size
            if (avgMeasuredMps > 0.05) {
                val valhallaAssumedMps = routeDistanceMeters / routeDurationSeconds
                currentMultiplier = (valhallaAssumedMps / avgMeasuredMps)
                    .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
            }
            return currentMultiplier
        }

        // User is currently stopped or barely moving
        val stopStart = stoppedSinceMs ?: timestampMs.also { stoppedSinceMs = it }
        val stoppedDurationMs = (timestampMs - stopStart).coerceAtLeast(0L)
        if (stoppedDurationMs < WINDOW_MS) {
            // Never adjust while stopped for less than 3 minutes
            return currentMultiplier
        }

        // Stopped for >= 3 minutes: revert to baseline Valhalla estimate until moving again
        movingSamples.clear()
        currentMultiplier = 1.0
        return 1.0
    }

    fun adjustRemainingTimeSeconds(
        mode: TravelMode,
        baselineRemainingTimeSeconds: Double,
        routeDistanceMeters: Double,
        routeDurationSeconds: Double,
        currentSpeedMps: Float,
        timestampMs: Long
    ): Double {
        if (mode == TravelMode.DRIVE || baselineRemainingTimeSeconds <= 0.0) {
            return baselineRemainingTimeSeconds
        }
        val multiplier = updateAndGetMultiplier(
            mode = mode,
            routeDistanceMeters = routeDistanceMeters,
            routeDurationSeconds = routeDurationSeconds,
            currentSpeedMps = currentSpeedMps,
            timestampMs = timestampMs
        )
        return (baselineRemainingTimeSeconds * multiplier).coerceAtLeast(1.0)
    }

    companion object {
        const val WINDOW_MS = 180_000L // 3 minutes
        const val MIN_MULTIPLIER = 0.6
        const val MAX_MULTIPLIER = 1.5
        const val MIN_WALK_MOVING_MPS = 0.4f // ~1.4 km/h
        const val MIN_BIKE_MOVING_MPS = 0.8f // ~2.9 km/h
    }
}
