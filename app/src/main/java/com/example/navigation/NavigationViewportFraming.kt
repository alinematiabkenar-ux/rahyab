package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Unobstructed map viewport dimensions and safe marker clearance metrics for Phone and Android Auto.
 */
data class MapViewportMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val insetLeftPx: Int = 0,
    val insetTopPx: Int = 0,
    val insetRightPx: Int = 0,
    val insetBottomPx: Int = 0,
    val density: Float = 1f,
    val markerRadiusPx: Float = 26f * density
) {
    val usableLeft: Float get() = insetLeftPx.coerceIn(0, widthPx).toFloat()
    val usableTop: Float get() = insetTopPx.coerceIn(0, heightPx).toFloat()
    val usableRight: Float get() = (widthPx - insetRightPx).coerceIn(insetLeftPx.coerceIn(0, widthPx), widthPx).toFloat()
    val usableBottom: Float get() = (heightPx - insetBottomPx).coerceIn(insetTopPx.coerceIn(0, heightPx), heightPx).toFloat()
    val usableWidth: Float get() = (usableRight - usableLeft).coerceAtLeast(1f)
    val usableHeight: Float get() = (usableBottom - usableTop).coerceAtLeast(1f)
    val isValid: Boolean get() = widthPx > 0 && heightPx > 0

    fun isMarkerFullyInside(pt: ScreenPoint, extraMarginPx: Float = 0f): Boolean {
        val margin = markerRadiusPx + extraMarginPx
        return pt.x.isFinite() && pt.y.isFinite() &&
            pt.x - margin >= usableLeft &&
            pt.x + margin <= usableRight &&
            pt.y - margin >= usableTop &&
            pt.y + margin <= usableBottom
    }
}

data class ScreenPoint(val x: Float, val y: Float)

data class BoundedFocusFraming(
    val cameraTarget: LatLon,
    val zoom: Double,
    val padding: DoubleArray,
    val lookAheadMeters: Double,
    val projectedVehicle: ScreenPoint,
    val vehicleFullyVisible: Boolean
)

/**
 * Pure Web Mercator + 3D perspective camera projection and unobstructed-viewport framing helper.
 * Guarantees that the entire vehicle marker stays strictly inside the unobstructed map area
 * (accounting for top HUD, dedicated blue-lane panel, junction card, bottom bar, and Android Auto
 * visible/stable insets) across Turn Focus entry, tightening, exit, and normal follow.
 */
object NavigationViewportFraming {

    private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
    private const val MAPLIBRE_VERTICAL_FOV_DEG = 36.87
    private const val MIN_ADAPTIVE_FOCUS_ZOOM = 14.2

    /**
     * Computes MapLibre `[left, top, right, bottom]` camera padding placing the camera anchor
     * in the lower third (normal follow) or lower quarter (Turn Focus) of the unobstructed area
     * while guaranteeing full marker clearance above the bottom inset and below the top overlay.
     */
    fun computeSafeCameraPadding(
        viewport: MapViewportMetrics,
        anchorLowerThird: Boolean,
        anchorLowerQuarter: Boolean
    ): DoubleArray {
        if (!viewport.isValid) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val padLeft = viewport.usableLeft.toDouble()
        val padRight = (viewport.widthPx - viewport.usableRight).toDouble().coerceAtLeast(0.0)
        val padBottom = (viewport.heightPx - viewport.usableBottom).toDouble().coerceAtLeast(0.0)

        val minBottomClearance = (viewport.markerRadiusPx + 22f * viewport.density).toDouble()
            .coerceAtMost(viewport.usableHeight * 0.45)
        val minTopClearance = (viewport.markerRadiusPx + 18f * viewport.density).toDouble()
            .coerceAtMost(viewport.usableHeight * 0.45)

        val desiredFraction = when {
            anchorLowerQuarter -> 0.70
            anchorLowerThird -> 0.64
            else -> 0.50
        }
        val minY = viewport.usableTop + minTopClearance
        val maxY = (viewport.usableBottom - minBottomClearance).coerceAtLeast(minY)
        val anchorY = (viewport.usableTop + viewport.usableHeight * desiredFraction).coerceIn(minY, maxY)

        // MapLibre places the camera target at anchorY = (heightPx + padTop - padBottom) / 2
        val maxTopPad = (viewport.heightPx - padBottom - viewport.markerRadiusPx * 2.0).coerceAtLeast(viewport.usableTop.toDouble())
        val padTop = (2.0 * anchorY - viewport.heightPx + padBottom)
            .coerceIn(viewport.usableTop.toDouble(), maxTopPad)

        return doubleArrayOf(padLeft, padTop, padRight, padBottom)
    }

    /**
     * Projects [point] onto screen pixels for a MapLibre camera at ([cameraLat], [cameraLon]),
     * [zoom], [tiltDeg], [bearingDeg], and [padding] (`[left, top, right, bottom]`).
     * Models MapLibre's 3D tilt perspective magnification so points behind the camera anchor
     * accurately reflect downward screen displacement.
     */
    fun projectLatLonToScreen(
        point: LatLon,
        cameraLat: Double,
        cameraLon: Double,
        zoom: Double,
        tiltDeg: Double,
        bearingDeg: Double,
        padding: DoubleArray,
        viewport: MapViewportMetrics
    ): ScreenPoint {
        val padLeft = padding.getOrElse(0) { 0.0 }
        val padTop = padding.getOrElse(1) { 0.0 }
        val padRight = padding.getOrElse(2) { 0.0 }
        val padBottom = padding.getOrElse(3) { 0.0 }

        val anchorX = (viewport.widthPx + padLeft - padRight) * 0.5
        val anchorY = (viewport.heightPx + padTop - padBottom) * 0.5

        val cosLat = cos(Math.toRadians(cameraLat)).coerceAtLeast(0.15)
        val northMeters = (point.lat - cameraLat) * 111_320.0
        val eastMeters = (point.lon - cameraLon) * 111_320.0 * cosLat

        val bearRad = Math.toRadians(bearingDeg)
        val fwdMeters = northMeters * cos(bearRad) + eastMeters * sin(bearRad)
        val rightMeters = -northMeters * sin(bearRad) + eastMeters * cos(bearRad)

        val tileScalePx = 512.0 * 2.0.pow(zoom) * viewport.density.coerceAtLeast(1f) / 2.0
        val pxPerMeter = tileScalePx / (EARTH_CIRCUMFERENCE_METERS * cosLat)

        // Screen coordinates on the un-tilted plane (+y is downward = behind camera target)
        val planeX = rightMeters * pxPerMeter
        val planeY = -fwdMeters * pxPerMeter

        val clampedTiltRad = Math.toRadians(tiltDeg.coerceIn(0.0, 65.0))
        if (clampedTiltRad < 0.001) {
            return ScreenPoint((anchorX + planeX).toFloat(), (anchorY + planeY).toFloat())
        }

        val fovRad = Math.toRadians(MAPLIBRE_VERTICAL_FOV_DEG)
        val cameraDist = (viewport.heightPx.coerceAtLeast(1) * 0.5) / tan(fovRad * 0.5)
        val zCam = (cameraDist - planeY * sin(clampedTiltRad)).coerceAtLeast(cameraDist * 0.12)
        val perspectiveScale = cameraDist / zCam

        val screenX = anchorX + planeX * perspectiveScale
        val screenY = anchorY + planeY * cos(clampedTiltRad) * perspectiveScale
        return ScreenPoint(screenX.toFloat(), screenY.toFloat())
    }

    /**
     * Bounds Turn Focus look-ahead and optionally widens zoom on compact viewports so:
     * 1. The vehicle marker NEVER leaves the unobstructed [viewport] area (even with 55° tilt,
     *    top HUD, dedicated blue-lane panel, junction card, and bottom bar open).
     * 2. As much of the upcoming junction and outgoing road as fits stays framed ahead.
     */
    fun computeBoundedFocusFraming(
        vehicleLoc: LatLon,
        desiredCameraTarget: LatLon,
        baseZoom: Double,
        tiltDeg: Double,
        bearingDeg: Double,
        viewport: MapViewportMetrics,
        junctionPivot: LatLon? = null,
        anchorLowerQuarter: Boolean = true
    ): BoundedFocusFraming {
        val padding = computeSafeCameraPadding(
            viewport = viewport,
            anchorLowerThird = !anchorLowerQuarter,
            anchorLowerQuarter = anchorLowerQuarter
        )
        if (!viewport.isValid) {
            val shift = OfflinePlaceSearchEngine.haversineMeters(
                vehicleLoc.lat, vehicleLoc.lon, desiredCameraTarget.lat, desiredCameraTarget.lon
            )
            return BoundedFocusFraming(
                cameraTarget = desiredCameraTarget,
                zoom = baseZoom,
                padding = padding,
                lookAheadMeters = shift,
                projectedVehicle = ScreenPoint(0f, 0f),
                vehicleFullyVisible = true
            )
        }

        val safetyMarginPx = (8f * viewport.density).coerceAtMost(viewport.usableHeight * 0.08f)
        fun lerpTarget(frac: Double): LatLon = LatLon(
            lat = vehicleLoc.lat + (desiredCameraTarget.lat - vehicleLoc.lat) * frac,
            lon = vehicleLoc.lon + (desiredCameraTarget.lon - vehicleLoc.lon) * frac
        )

        fun maxSafeFractionForZoom(zoomCandidate: Double): Double {
            val fullPt = projectLatLonToScreen(
                point = vehicleLoc,
                cameraLat = desiredCameraTarget.lat,
                cameraLon = desiredCameraTarget.lon,
                zoom = zoomCandidate,
                tiltDeg = tiltDeg,
                bearingDeg = bearingDeg,
                padding = padding,
                viewport = viewport
            )
            if (viewport.isMarkerFullyInside(fullPt, safetyMarginPx)) return 1.0

            var lo = 0.0
            var hi = 1.0
            repeat(12) {
                val mid = (lo + hi) * 0.5
                val t = lerpTarget(mid)
                val pt = projectLatLonToScreen(
                    point = vehicleLoc,
                    cameraLat = t.lat,
                    cameraLon = t.lon,
                    zoom = zoomCandidate,
                    tiltDeg = tiltDeg,
                    bearingDeg = bearingDeg,
                    padding = padding,
                    viewport = viewport
                )
                if (viewport.isMarkerFullyInside(pt, safetyMarginPx)) {
                    lo = mid
                } else {
                    hi = mid
                }
            }
            return lo
        }

        var chosenZoom = baseZoom
        var chosenFrac = maxSafeFractionForZoom(chosenZoom)

        // On compact viewports, if clamping look-ahead pushed the upcoming junction off the top,
        // moderately widen zoom (respecting MIN_ADAPTIVE_FOCUS_ZOOM) while keeping the marker safe.
        if (junctionPivot != null && chosenFrac < 0.85) {
            var candidateZoom = chosenZoom
            while (candidateZoom > MIN_ADAPTIVE_FOCUS_ZOOM + 0.05) {
                val t = lerpTarget(chosenFrac)
                val jPt = projectLatLonToScreen(
                    point = junctionPivot,
                    cameraLat = t.lat,
                    cameraLon = t.lon,
                    zoom = candidateZoom,
                    tiltDeg = tiltDeg,
                    bearingDeg = bearingDeg,
                    padding = padding,
                    viewport = viewport
                )
                if (jPt.y >= viewport.usableTop + 12f * viewport.density) break
                candidateZoom = (candidateZoom - 0.25).coerceAtLeast(MIN_ADAPTIVE_FOCUS_ZOOM)
                val nextFrac = maxSafeFractionForZoom(candidateZoom)
                chosenZoom = candidateZoom
                chosenFrac = nextFrac
            }
        }

        val finalTarget = if (abs(chosenFrac) < 1e-4) vehicleLoc else lerpTarget(chosenFrac)
        val projectedVehicle = projectLatLonToScreen(
            point = vehicleLoc,
            cameraLat = finalTarget.lat,
            cameraLon = finalTarget.lon,
            zoom = chosenZoom,
            tiltDeg = tiltDeg,
            bearingDeg = bearingDeg,
            padding = padding,
            viewport = viewport
        )
        val lookAheadMeters = OfflinePlaceSearchEngine.haversineMeters(
            vehicleLoc.lat, vehicleLoc.lon, finalTarget.lat, finalTarget.lon
        )
        return BoundedFocusFraming(
            cameraTarget = finalTarget,
            zoom = chosenZoom,
            padding = padding,
            lookAheadMeters = lookAheadMeters,
            projectedVehicle = projectedVehicle,
            vehicleFullyVisible = viewport.isMarkerFullyInside(projectedVehicle)
        )
    }
}
