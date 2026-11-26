package com.example.car

import android.graphics.Rect

/**
 * A1: Android Auto map zoom profile.
 * Car head-unit screens are wider and viewed from further away than phones,
 * so the default profile zooms out by -0.8 levels to show surrounding streets.
 */
enum class CarMapView(val code: String, val zoomOffset: Double) {
    CLOSE("close", 0.0),
    NORMAL("normal", -0.8),
    WIDE("wide", -1.5);

    companion object {
        fun fromCode(code: String?): CarMapView =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: NORMAL
    }
}

data class CarCameraTarget(
    val zoom: Double,
    val tilt: Double,
    val anchorLowerQuarter: Boolean
)

object CarCameraProfile {
    const val CAR_TILT_NAVIGATING = 40.0
    const val CAR_TILT_FREE_DRIVE = 30.0
    const val CAR_TILT_2D = 0.0
    const val FOCUS_ZOOM_HIGHWAY = 17.3
    const val FOCUS_ZOOM_CITY = 18.0
    const val FOCUS_TILT_3D = 55.0
    const val FOCUS_ENTER_ANIM_MS = 1200
    const val FOCUS_EXIT_ANIM_MS = 1500

    /**
     * Pure computation of car camera zoom and tilt.
     * When [focusCameraActive] is true, overrides the normal car profile with Turn Focus zoom/tilt.
     */
    fun computeCarCameraTarget(
        basePhoneZoom: Double,
        carMapView: CarMapView,
        isNavigating: Boolean,
        is3DView: Boolean,
        focusCameraActive: Boolean = false,
        focusIsHighway: Boolean = false
    ): CarCameraTarget {
        if (focusCameraActive) {
            return CarCameraTarget(
                zoom = if (focusIsHighway) FOCUS_ZOOM_HIGHWAY else FOCUS_ZOOM_CITY,
                tilt = if (is3DView) FOCUS_TILT_3D else CAR_TILT_2D,
                anchorLowerQuarter = true
            )
        }
        val zoom = (basePhoneZoom + carMapView.zoomOffset).coerceIn(11.5, 19.0)
        val tilt = when {
            !is3DView -> CAR_TILT_2D
            isNavigating -> CAR_TILT_NAVIGATING
            else -> CAR_TILT_FREE_DRIVE
        }
        return CarCameraTarget(
            zoom = zoom,
            tilt = tilt,
            anchorLowerQuarter = false
        )
    }

    /**
     * Computes MapLibre camera padding [left, top, right, bottom] to keep the car puck
     * in the lower third normally, or lower quarter during Turn Focus mode.
     */
    fun computeCameraPadding(
        surfaceWidth: Int,
        surfaceHeight: Int,
        area: Rect,
        anchorLowerThird: Boolean,
        anchorLowerQuarter: Boolean
    ): DoubleArray {
        val leftPad = area.left.toDouble().coerceAtLeast(0.0)
        val rightPad = (surfaceWidth - area.right).toDouble().coerceAtLeast(0.0)
        val bottomPad = (surfaceHeight - area.bottom).toDouble().coerceAtLeast(0.0)
        val extraTop = when {
            area.height() <= 0 -> 0.0
            anchorLowerQuarter -> area.height() * 0.38
            anchorLowerThird -> area.height() * 0.28
            else -> 0.0
        }
        val topPad = (area.top.toDouble() + extraTop).coerceAtLeast(0.0)
        return doubleArrayOf(leftPad, topPad, rightPad, bottomPad)
    }
}
