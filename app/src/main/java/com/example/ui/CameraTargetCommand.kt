package com.example.ui

import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable camera move/animation command emitted by [RahYabViewModel] and validated by
 * [com.example.ui.components.MapLibreMapContainer] before applying to MapLibre.
 */
data class CameraTargetCommand(
    val lat: Double,
    val lon: Double,
    val zoom: Double = 14.5,
    val tilt: Double = 0.0,
    val bearing: Double = 0.0,
    val animationDurationMs: Int = 500,
    val anchorLowerThird: Boolean = false,
    val anchorLowerQuarter: Boolean = false,
    val routeId: String? = null,
    val maneuverIndex: Int = -1,
    val generation: Long = 0L,
    val isFocusCommand: Boolean = false,
    val cancelPendingAnimation: Boolean = false,
    val nonce: Long = nextCommandNonce()
) {
    companion object {
        private val nonceCounter = AtomicLong(1L)
        fun nextCommandNonce(): Long = nonceCounter.incrementAndGet()
    }
    /**
     * Validates that this camera command has not been superseded by a reroute, route switch,
     * navigation stop, or manual user pan/zoom gesture.
     */
    fun isValidFor(
        activeRouteId: String?,
        isCameraFollowing: Boolean,
        minValidGeneration: Long,
        userPannedDuringFocus: Boolean = false
    ): Boolean {
        if (cancelPendingAnimation) return false
        if (generation < minValidGeneration) return false
        if (isFocusCommand && (!isCameraFollowing || userPannedDuringFocus)) return false
        if (routeId != null && activeRouteId != null && routeId != activeRouteId) return false
        if (isFocusCommand && routeId != null && activeRouteId == null) return false
        return true
    }
}
