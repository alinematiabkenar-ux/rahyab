package com.example.navigation

/**
 * Supported offline routing and navigation travel modes.
 */
enum class TravelMode(val code: String) {
    DRIVE("drive"),
    WALK("walk"),
    BIKE("bike");

    companion object {
        const val MAX_WALK_DISTANCE_METERS: Double = 50_000.0
        const val MAX_BIKE_DISTANCE_METERS: Double = 150_000.0

        fun fromCode(code: String?): TravelMode =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: DRIVE

        fun isTooFar(mode: TravelMode, distanceMeters: Double): Boolean = when (mode) {
            DRIVE -> false
            WALK -> distanceMeters > MAX_WALK_DISTANCE_METERS
            BIKE -> distanceMeters > MAX_BIKE_DISTANCE_METERS
        }
    }
}

enum class WalkingSpeedPreset(val code: String, val speedKmh: Double) {
    SLOW("slow", 4.0),
    NORMAL("normal", 5.1),
    FAST("fast", 6.0);

    companion object {
        fun fromCode(code: String?): WalkingSpeedPreset =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: NORMAL
    }
}

enum class CyclingSpeedPreset(val code: String, val speedKmh: Double) {
    RELAXED("relaxed", 14.0),
    NORMAL("normal", 18.0),
    FAST("fast", 23.0);

    companion object {
        fun fromCode(code: String?): CyclingSpeedPreset =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: NORMAL
    }
}
