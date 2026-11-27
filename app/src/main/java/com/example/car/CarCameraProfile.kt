package com.example.car

import android.graphics.Rect
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.navigation.ActiveNavigationState
import com.example.navigation.LatLon
import com.example.navigation.TurnFocusPlanner
import com.example.navigation.TurnFocusStage
import com.example.navigation.TurnFocusStageHelper
import com.example.navigation.ValhallaResponseParser
import kotlin.math.abs

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

data class CarCameraSnapshot(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val tilt: Double,
    val bearing: Double,
    val routeId: String?,
    val maneuverIndex: Int
)

data class CarFocusCameraUpdate(
    val target: LatLon,
    val zoom: Double,
    val tilt: Double,
    val bearing: Double,
    val animMs: Int
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
     * When [focusCameraActive] is true, overrides the normal car profile with two-stage
     * Turn Focus zoom/tilt (1 km approach framing -> tightened lane-level view).
     */
    fun computeCarCameraTarget(
        basePhoneZoom: Double,
        carMapView: CarMapView,
        isNavigating: Boolean,
        is3DView: Boolean,
        focusCameraActive: Boolean = false,
        focusIsHighway: Boolean = false,
        focusClosePair: Boolean = false,
        focusTargetZoom: Double = 0.0,
        focusStage: TurnFocusStage = TurnFocusStage.NONE
    ): CarCameraTarget {
        if (focusCameraActive) {
            val focusZoom = when {
                focusTargetZoom > 0.0 -> focusTargetZoom
                focusStage == TurnFocusStage.APPROACH -> TurnFocusStageHelper.APPROACH_MIN_ZOOM_1KM
                focusIsHighway -> FOCUS_ZOOM_HIGHWAY
                focusClosePair -> TurnFocusPlanner.FOCUS_ZOOM_CLOSE_PAIR
                else -> FOCUS_ZOOM_CITY
            }
            return CarCameraTarget(
                zoom = focusZoom,
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

    fun resolveCarCameraAnimMs(
        enteringFocus: Boolean,
        tighteningToLaneLevel: Boolean,
        exitingFocus: Boolean,
        focusEnterAnimMs: Int,
        defaultFollowAnimMs: Int
    ): Int = when {
        enteringFocus -> focusEnterAnimMs.coerceIn(TurnFocusPlanner.MIN_ENTER_ANIM_MS, FOCUS_ENTER_ANIM_MS)
        tighteningToLaneLevel -> TurnFocusStageHelper.TIGHTEN_ANIM_MS
        exitingFocus -> FOCUS_EXIT_ANIM_MS
        else -> defaultFollowAnimMs
    }

    fun shouldSkipStationaryFocusUpdate(
        previous: CarCameraSnapshot?,
        currentLoc: LatLon,
        targetZoom: Double,
        targetTilt: Double,
        bearing: Double,
        speedMps: Float,
        routeId: String?,
        maneuverIndex: Int
    ): Boolean {
        if (previous == null || speedMps >= 0.5f) return false
        return previous.routeId == routeId &&
            previous.maneuverIndex == maneuverIndex &&
            abs(previous.zoom - targetZoom) < 0.05 &&
            abs(previous.tilt - targetTilt) < 0.5 &&
            ValhallaResponseParser.angleDiffDegrees(previous.bearing, bearing) < 4.0 &&
            OfflinePlaceSearchEngine.haversineMeters(previous.lat, previous.lon, currentLoc.lat, currentLoc.lon) < 2.5
    }

    /**
     * Converts Android Auto surface dimensions + visible/stable Rect into [com.example.navigation.MapViewportMetrics].
     */
    fun toViewportMetrics(
        surfaceWidth: Int,
        surfaceHeight: Int,
        area: Rect,
        pixelRatio: Float = 1f
    ): com.example.navigation.MapViewportMetrics {
        val hasArea = area.width() > 0 && area.height() > 0
        val left = if (hasArea) area.left.coerceIn(0, surfaceWidth) else 0
        val top = if (hasArea) area.top.coerceIn(0, surfaceHeight) else 0
        val rightInset = if (hasArea) (surfaceWidth - area.right).coerceIn(0, surfaceWidth) else 0
        val bottomInset = if (hasArea) (surfaceHeight - area.bottom).coerceIn(0, surfaceHeight) else 0
        return com.example.navigation.MapViewportMetrics(
            widthPx = surfaceWidth,
            heightPx = surfaceHeight,
            insetLeftPx = left,
            insetTopPx = top,
            insetRightPx = rightInset,
            insetBottomPx = bottomInset,
            density = pixelRatio.coerceAtLeast(1f)
        )
    }

    /**
     * Computes MapLibre camera padding [left, top, right, bottom] to keep the car puck
     * safely inside the unobstructed Android Auto visible/stable area in the lower third
     * normally, or lower quarter during Turn Focus mode.
     */
    fun computeCameraPadding(
        surfaceWidth: Int,
        surfaceHeight: Int,
        area: Rect,
        anchorLowerThird: Boolean,
        anchorLowerQuarter: Boolean,
        pixelRatio: Float = 1f
    ): DoubleArray {
        val viewport = toViewportMetrics(surfaceWidth, surfaceHeight, area, pixelRatio)
        return com.example.navigation.NavigationViewportFraming.computeSafeCameraPadding(
            viewport = viewport,
            anchorLowerThird = anchorLowerThird,
            anchorLowerQuarter = anchorLowerQuarter
        )
    }

    /**
     * Computes Android Auto Turn Focus camera framing bounded to the unobstructed car surface so
     * the entire vehicle marker stays visible while framing the upcoming turn/exit and outgoing road.
     */
    fun computeFocusCameraUpdate(
        nav: ActiveNavigationState,
        loc: LatLon,
        bearing: Float,
        is3DView: Boolean,
        enteringFocus: Boolean,
        tightening: Boolean,
        viewport: com.example.navigation.MapViewportMetrics? = null
    ): CarFocusCameraUpdate {
        val effectiveStage = if (nav.turnFocusStage != TurnFocusStage.NONE) {
            nav.turnFocusStage
        } else {
            TurnFocusStage.LANE_LEVEL
        }
        val desiredTarget = TurnFocusStageHelper.computeApproachCameraTarget(
            route = nav.route,
            maneuver = nav.displayedManeuver,
            vehicleLoc = loc,
            remainingMeters = nav.displayedManeuverDistanceMeters,
            stage = effectiveStage
        )
        val carTarget = computeCarCameraTarget(
            basePhoneZoom = TurnFocusPlanner.FOCUS_ZOOM_CITY,
            carMapView = CarMapView.NORMAL,
            isNavigating = true,
            is3DView = is3DView,
            focusCameraActive = true,
            focusIsHighway = nav.turnFocusIsHighway,
            focusClosePair = nav.turnFocusClosePair,
            focusTargetZoom = nav.turnFocusTargetZoom,
            focusStage = effectiveStage
        )
        val pivot = nav.route?.let { r ->
            nav.displayedManeuver?.let { m ->
                r.shape.getOrNull(m.beginShapeIndex.coerceIn(0, r.shape.lastIndex.coerceAtLeast(0)))
            }
        }
        val (boundedTarget, boundedZoom) = if (viewport != null && viewport.isValid) {
            val framed = com.example.navigation.NavigationViewportFraming.computeBoundedFocusFraming(
                vehicleLoc = loc,
                desiredCameraTarget = desiredTarget,
                baseZoom = carTarget.zoom,
                tiltDeg = carTarget.tilt,
                bearingDeg = bearing.toDouble(),
                viewport = viewport,
                junctionPivot = pivot,
                anchorLowerQuarter = true
            )
            framed.cameraTarget to framed.zoom
        } else {
            desiredTarget to carTarget.zoom
        }
        val animMs = resolveCarCameraAnimMs(
            enteringFocus = enteringFocus,
            tighteningToLaneLevel = tightening,
            exitingFocus = false,
            focusEnterAnimMs = nav.turnFocusEnterAnimMs,
            defaultFollowAnimMs = 350
        )
        return CarFocusCameraUpdate(
            target = boundedTarget,
            zoom = boundedZoom,
            tilt = carTarget.tilt,
            bearing = bearing.toDouble(),
            animMs = animMs
        )
    }
}
