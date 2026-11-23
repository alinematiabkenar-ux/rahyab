package com.example.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.example.R
import com.example.map.MapBitmapIcons
import com.example.map.MapExtraLayersHelper
import com.example.map.MapStyleBuilder
import com.example.RahYabApplication
import com.example.map.RouteGeoJsonCache
import com.example.navigation.LatLon
import com.example.navigation.OfflineRoute
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Registers the exact same map marker bitmaps, shields, POI icons, camera icons, traffic lights,
 * speed bumps, and parked-car layers on the Android Auto MapLibre surface as on the phone,
 * and updates GeoJSON map sources from [com.example.navigation.RahYabNavigationHub].
 */
internal object CarMapStyleHelper {

    fun registerAllCarStyleImages(context: Context, style: Style, isNightMode: Boolean) {
        runCatching {
            style.addImage(MapStyleBuilder.IMAGE_USER_ARROW, createArrowBitmap(isPoorGps = false))
            style.addImage(MapStyleBuilder.IMAGE_USER_ARROW_GRAY, createArrowBitmap(isPoorGps = true))
            style.addImage(MapStyleBuilder.IMAGE_USER_CAR, createArrowBitmap(isPoorGps = false))
            style.addImage(MapStyleBuilder.IMAGE_USER_CAR_GRAY, createArrowBitmap(isPoorGps = true))
            style.addImage(MapStyleBuilder.IMAGE_ROUTE_CHEVRON, createChevronBitmap())
            style.addImage(MapStyleBuilder.IMAGE_HIGHWAY_SHIELD, createShieldBitmap(isNightMode))
            MapBitmapIcons.registerShieldsAndPoiIcons(style, isNightMode)
            style.addImage(MapStyleBuilder.IMAGE_SPEED_CAMERA, createCameraBitmap(isRedLight = false))
            style.addImage(MapStyleBuilder.IMAGE_RED_LIGHT_CAMERA, createCameraBitmap(isRedLight = true))
            createVectorBitmap(context, R.drawable.ic_traffic_light_map, 36, 54)?.let {
                style.addImage(MapStyleBuilder.IMAGE_TRAFFIC_LIGHT, it)
            }
            MapExtraLayersHelper.registerExtraOverlayLayers(context, style)
        }
    }

    fun updatePositionAndRoutes(app: RahYabApplication, style: Style?) {
        if (style == null || !style.isFullyLoaded) return
        val hub = app.navigationHub
        val nav = hub.navigationState.value
        val fix = app.locationTracker.currentFix.value
        val emptyFc = RouteGeoJsonCache.EMPTY_FEATURE_COLLECTION

        runCatching {
            val activeRoute = if (nav.isNavigating) nav.route else null
            if (activeRoute != null && activeRoute.shape.size >= 2) {
                val shape = activeRoute.shape
                val split = nav.snappedShapeIndex.coerceIn(0, shape.lastIndex)
                val driven = if (split >= 1) {
                    FeatureCollection.fromFeature(
                        Feature.fromGeometry(LineString.fromLngLats(shape.subList(0, split + 1).map { Point.fromLngLat(it.lon, it.lat) }))
                    )
                } else emptyFc
                val remPts = shape.subList(split, shape.size).map { Point.fromLngLat(it.lon, it.lat) }
                val rem = if (remPts.size >= 2) {
                    FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(remPts)).apply {
                        addStringProperty("color", MapStyleBuilder.ROUTE_COLOR_0)
                    })
                } else emptyFc
                MapStyleBuilder.ALL_ROUTE_SOURCES.forEach { style.getSourceAs<GeoJsonSource>(it)?.setGeoJson(emptyFc) }
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_DRIVEN_ROUTE)?.setGeoJson(driven)
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)?.setGeoJson(rem)
            } else {
                val candidates = hub.candidateRoutes.value
                val selIdx = hub.selectedRouteIndex.value.coerceIn(0, candidates.lastIndex.coerceAtLeast(0))
                MapStyleBuilder.ALL_ROUTE_SOURCES.forEachIndexed { idx, srcId ->
                    val r = candidates.getOrNull(idx)
                    val fc = if (r != null && idx != selIdx && r.shape.size >= 2) {
                        buildRouteFc(r, colorHex = null)
                    } else emptyFc
                    style.getSourceAs<GeoJsonSource>(srcId)?.setGeoJson(fc)
                }
                val sel = candidates.getOrNull(selIdx)
                val primary = if (sel != null && sel.shape.size >= 2) {
                    buildRouteFc(sel, MapStyleBuilder.ALL_ROUTE_COLORS.getOrElse(selIdx) { MapStyleBuilder.ROUTE_COLOR_0 })
                } else emptyFc
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_DRIVEN_ROUTE)?.setGeoJson(emptyFc)
                style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_PRIMARY_ROUTE)?.setGeoJson(primary)
            }

            val place = hub.selectedPlace.value
            val destFc = if (place != null) {
                FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(place.lon, place.lat)))
            } else emptyFc
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_DESTINATION)?.setGeoJson(destFc)

            val displayLoc = nav.snappedLocation ?: fix?.let { LatLon(it.lat, it.lon) }
            val bearing = if (nav.isNavigating) nav.snappedBearing else hub.userHeadingDegrees.value
            val puckFc = if (displayLoc != null) {
                FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(displayLoc.lon, displayLoc.lat)).apply {
                    addNumberProperty("bearing", bearing)
                    addStringProperty("icon", MapStyleBuilder.IMAGE_USER_ARROW)
                    addStringProperty("haloColor", "#38BDF8")
                })
            } else emptyFc
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_GPS_PUCK)?.setGeoJson(puckFc)
        }
    }

    fun updateExtraLayers(app: RahYabApplication, style: Style?) {
        if (style == null || !style.isFullyLoaded) return
        val hub = app.navigationHub
        runCatching {
            val camsFc = FeatureCollection.fromFeatures(hub.visibleCameras.value.map { cam ->
                Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply {
                    val icon = if (cam.type.equals("red_light_camera", ignoreCase = true)) {
                        MapStyleBuilder.IMAGE_RED_LIGHT_CAMERA
                    } else MapStyleBuilder.IMAGE_SPEED_CAMERA
                    addStringProperty("icon", icon)
                }
            })
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_CAMERAS)?.setGeoJson(camsFc)
            val lightsFc = FeatureCollection.fromFeatures(hub.visibleTrafficLights.value.map {
                Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))
            })
            style.getSourceAs<GeoJsonSource>(MapStyleBuilder.SOURCE_TRAFFIC_LIGHTS)?.setGeoJson(lightsFc)
            MapExtraLayersHelper.updateSpeedBumps(style, hub.visibleSpeedBumps.value)
        }
    }

    private fun buildRouteFc(route: OfflineRoute, colorHex: String?): FeatureCollection {
        val pts = route.shape.map { Point.fromLngLat(it.lon, it.lat) }
        val feat = Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
            if (!colorHex.isNullOrBlank()) addStringProperty("color", colorHex)
        }
        return FeatureCollection.fromFeature(feat)
    }

    private fun createArrowBitmap(isPoorGps: Boolean): Bitmap {
        val size = 112
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val leftColor = if (isPoorGps) AndroidColor.parseColor("#94A3B8") else AndroidColor.parseColor("#38BDF8")
        val rightColor = if (isPoorGps) AndroidColor.parseColor("#64748B") else AndroidColor.parseColor("#0284C7")
        val left = Path().apply {
            moveTo(cx, 10f); lineTo(cx, cy + 14f); lineTo(cx - 29f, size - 21f); close()
        }
        val right = Path().apply {
            moveTo(cx, 10f); lineTo(cx + 29f, size - 21f); lineTo(cx, cy + 14f); close()
        }
        val outline = Path().apply {
            moveTo(cx, 10f); lineTo(cx + 29f, size - 21f); lineTo(cx, cy + 14f); lineTo(cx - 29f, size - 21f); close()
        }
        canvas.drawPath(left, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = leftColor })
        canvas.drawPath(right, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rightColor })
        canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5.5f
            strokeJoin = Paint.Join.ROUND
            color = AndroidColor.WHITE
        })
        return bmp
    }

    private fun createChevronBitmap(): Bitmap {
        val size = 28
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = Path().apply {
            moveTo(8f, 6f); lineTo(20f, size / 2f); lineTo(8f, size - 6f)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = AndroidColor.argb(225, 255, 255, 255)
        })
        return bmp
    }

    private fun createShieldBitmap(isNightMode: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(2f, 2f, 62f, 34f)
        canvas.drawRoundRect(rect, 9f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isNightMode) AndroidColor.parseColor("#1D4ED8") else AndroidColor.parseColor("#1E40AF")
        })
        canvas.drawRoundRect(rect, 9f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = AndroidColor.WHITE
        })
        return bmp
    }

    private fun createCameraBitmap(isRedLight: Boolean): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        canvas.drawCircle(cx, cy, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isRedLight) AndroidColor.parseColor("#1E293B") else AndroidColor.parseColor("#DC2626")
        })
        canvas.drawCircle(cx, cy, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f
            color = if (isRedLight) AndroidColor.parseColor("#EF4444") else AndroidColor.WHITE
        })
        canvas.drawCircle(cx, cy, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
        return bmp
    }

    private fun createVectorBitmap(context: Context, resId: Int, w: Int, h: Int): Bitmap? {
        val d = ContextCompat.getDrawable(context, resId) ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp
    }
}
