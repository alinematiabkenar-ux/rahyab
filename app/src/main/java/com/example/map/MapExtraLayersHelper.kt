package com.example.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.search.RoadHazardPoint
import com.example.navigation.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Dynamically adds and updates v19 map overlay layers (speed bumps at zoom 15+ and parked car
 * blue "P" marker) on the loaded MapLibre [Style] without growing [MapStyleBuilder].
 */
object MapExtraLayersHelper {

    const val SOURCE_SPEED_BUMPS = "rahyab_speed_bumps"
    const val LAYER_SPEED_BUMPS_SYMBOL = "rahyab_speed_bumps_symbol"
    const val IMAGE_SPEED_BUMP = "rahyab_speed_bump_icon"

    const val SOURCE_PARKED_CAR = "rahyab_parked_car"
    const val LAYER_PARKED_CAR_SYMBOL = "rahyab_parked_car_symbol"
    const val IMAGE_PARKED_CAR = "rahyab_parked_car_icon"

    const val SOURCE_STOP_SIGNS = "rahyab_stop_signs"
    const val LAYER_STOP_SIGNS_SYMBOL = "rahyab_stop_signs_symbol"
    const val IMAGE_STOP_SIGN = "rahyab_stop_sign_icon"

    const val SOURCE_MANEUVER_ARROW_LINE = "rahyab_maneuver_arrow_line_src"
    const val SOURCE_MANEUVER_ARROW_HEAD = "rahyab_maneuver_arrow_head_src"
    const val LAYER_MANEUVER_ARROW_CASING = "rahyab_maneuver_arrow_casing"
    const val LAYER_MANEUVER_ARROW_LINE = "rahyab_maneuver_arrow_line"
    const val LAYER_MANEUVER_ARROW_HEAD = "rahyab_maneuver_arrow_head"
    const val IMAGE_MANEUVER_ARROW_HEAD = "rahyab_maneuver_arrow_head_icon"

    fun registerExtraOverlayLayers(context: Context, style: Style) {
        runCatching {
            createVectorBitmap(context, R.drawable.ic_speed_bump_map, 44, 44)?.let { bmp ->
                style.addImage(IMAGE_SPEED_BUMP, bmp)
            }
            createVectorBitmap(context, R.drawable.ic_stop_sign_map, 36, 36)?.let { bmp ->
                style.addImage(IMAGE_STOP_SIGN, bmp)
            }
            style.addImage(IMAGE_PARKED_CAR, createParkedCarBitmap())
            style.addImage(IMAGE_MANEUVER_ARROW_HEAD, createManeuverArrowHeadBitmap())
            registerManeuverArrowLayers(style)
            LaneMapLayerHelper.registerLaneOverlayLayers(style)
            ParkingOverlayLayerHelper.registerDestinationParkingLayer(style)

            if (style.getSource(SOURCE_SPEED_BUMPS) == null) {
                style.addSource(GeoJsonSource(SOURCE_SPEED_BUMPS))
            }
            if (style.getLayer(LAYER_SPEED_BUMPS_SYMBOL) == null) {
                val bumpLayer = SymbolLayer(LAYER_SPEED_BUMPS_SYMBOL, SOURCE_SPEED_BUMPS).apply {
                    minZoom = 15.0f
                    setProperties(
                        PropertyFactory.iconImage(IMAGE_SPEED_BUMP),
                        PropertyFactory.iconSize(0.74f),
                        PropertyFactory.iconPadding(4f),
                        PropertyFactory.iconAllowOverlap(false),
                        PropertyFactory.iconIgnorePlacement(false)
                    )
                }
                if (style.getLayer(MapStyleBuilder.LAYER_DESTINATION_CIRCLE) != null) {
                    style.addLayerBelow(bumpLayer, MapStyleBuilder.LAYER_DESTINATION_CIRCLE)
                } else {
                    style.addLayer(bumpLayer)
                }
            }

            if (style.getSource(SOURCE_STOP_SIGNS) == null) {
                style.addSource(GeoJsonSource(SOURCE_STOP_SIGNS))
            }
            if (style.getLayer(LAYER_STOP_SIGNS_SYMBOL) == null) {
                val stopLayer = SymbolLayer(LAYER_STOP_SIGNS_SYMBOL, SOURCE_STOP_SIGNS).apply {
                    minZoom = 16.0f
                    setProperties(
                        PropertyFactory.iconImage(IMAGE_STOP_SIGN),
                        PropertyFactory.iconSize(0.72f),
                        PropertyFactory.iconPadding(4f),
                        PropertyFactory.iconAllowOverlap(false),
                        PropertyFactory.iconIgnorePlacement(false)
                    )
                }
                if (style.getLayer(MapStyleBuilder.LAYER_DESTINATION_CIRCLE) != null) {
                    style.addLayerBelow(stopLayer, MapStyleBuilder.LAYER_DESTINATION_CIRCLE)
                } else {
                    style.addLayer(stopLayer)
                }
            }

            if (style.getSource(SOURCE_PARKED_CAR) == null) {
                style.addSource(GeoJsonSource(SOURCE_PARKED_CAR))
            }
            if (style.getLayer(LAYER_PARKED_CAR_SYMBOL) == null) {
                val parkedLayer = SymbolLayer(LAYER_PARKED_CAR_SYMBOL, SOURCE_PARKED_CAR).apply {
                    minZoom = 9.0f
                    setProperties(
                        PropertyFactory.iconImage(IMAGE_PARKED_CAR),
                        PropertyFactory.iconSize(0.95f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                }
                style.addLayer(parkedLayer)
            }
        }
    }

    fun updateSpeedBumps(style: Style, speedBumps: List<RoadHazardPoint>) {
        if (!style.isFullyLoaded) return
        val fc = if (speedBumps.isEmpty()) {
            FeatureCollection.fromFeatures(emptyArray())
        } else {
            FeatureCollection.fromFeatures(
                speedBumps.map { bump ->
                    Feature.fromGeometry(Point.fromLngLat(bump.lon, bump.lat))
                }
            )
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_SPEED_BUMPS)?.setGeoJson(fc)
        }
    }

    fun updateStopSigns(style: Style, stopSigns: List<RoadHazardPoint>) {
        if (!style.isFullyLoaded) return
        val fc = if (stopSigns.isEmpty()) {
            FeatureCollection.fromFeatures(emptyArray())
        } else {
            FeatureCollection.fromFeatures(
                stopSigns.map { s -> Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)) }
            )
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_STOP_SIGNS)?.setGeoJson(fc)
        }
    }

    fun updateParkedCar(style: Style, parkedLocation: LatLon?) {
        if (!style.isFullyLoaded) return
        val fc = if (parkedLocation == null) {
            FeatureCollection.fromFeatures(emptyArray())
        } else {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(parkedLocation.lon, parkedLocation.lat))
            )
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_PARKED_CAR)?.setGeoJson(fc)
        }
    }

    fun updateManeuverArrow(style: Style, arrow: ManeuverArrowData?) {
        if (!style.isFullyLoaded) return
        val emptyFc = FeatureCollection.fromFeatures(emptyArray())
        val lineFc = if (arrow != null && arrow.points.size >= 2) {
            val pts = arrow.points.map { Point.fromLngLat(it.lon, it.lat) }
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts)))
        } else {
            emptyFc
        }
        val headFc = if (arrow != null) {
            val feat = Feature.fromGeometry(Point.fromLngLat(arrow.tip.lon, arrow.tip.lat)).apply {
                addNumberProperty("bearing", arrow.tipBearingDegrees)
            }
            FeatureCollection.fromFeature(feat)
        } else {
            emptyFc
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_MANEUVER_ARROW_LINE)?.setGeoJson(lineFc)
            style.getSourceAs<GeoJsonSource>(SOURCE_MANEUVER_ARROW_HEAD)?.setGeoJson(headFc)
        }
    }

    private fun registerManeuverArrowLayers(style: Style) {
        if (style.getSource(SOURCE_MANEUVER_ARROW_LINE) == null) {
            style.addSource(GeoJsonSource(SOURCE_MANEUVER_ARROW_LINE))
        }
        if (style.getSource(SOURCE_MANEUVER_ARROW_HEAD) == null) {
            style.addSource(GeoJsonSource(SOURCE_MANEUVER_ARROW_HEAD))
        }
        val anchorId = findManeuverArrowAnchorLayerId(style)
        if (style.getLayer(LAYER_MANEUVER_ARROW_CASING) == null) {
            val casing = LineLayer(LAYER_MANEUVER_ARROW_CASING, SOURCE_MANEUVER_ARROW_LINE).apply {
                minZoom = 15.0f
                setProperties(
                    PropertyFactory.lineColor("#0F172A"),
                    PropertyFactory.lineWidth(12.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
            addAboveRouteOrBelowAnchor(style, casing, anchorId)
        }
        if (style.getLayer(LAYER_MANEUVER_ARROW_LINE) == null) {
            val line = LineLayer(LAYER_MANEUVER_ARROW_LINE, SOURCE_MANEUVER_ARROW_LINE).apply {
                minZoom = 15.0f
                setProperties(
                    PropertyFactory.lineColor("#FFFFFF"),
                    PropertyFactory.lineWidth(7.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
            addAboveRouteOrBelowAnchor(style, line, anchorId)
        }
        if (style.getLayer(LAYER_MANEUVER_ARROW_HEAD) == null) {
            val head = SymbolLayer(LAYER_MANEUVER_ARROW_HEAD, SOURCE_MANEUVER_ARROW_HEAD).apply {
                minZoom = 15.0f
                setProperties(
                    PropertyFactory.iconImage(IMAGE_MANEUVER_ARROW_HEAD),
                    PropertyFactory.iconSize(0.86f),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            }
            addAboveRouteOrBelowAnchor(style, head, anchorId)
        }
    }

    private fun findManeuverArrowAnchorLayerId(style: Style): String? {
        val allLayers = style.layers
        val routeIdx = allLayers.indexOfFirst { it.id == MapStyleBuilder.LAYER_ROUTE_CHEVRONS }
        if (routeIdx >= 0) {
            val labelAboveRoute = allLayers.drop(routeIdx + 1)
                .firstOrNull { it.id.contains("label", ignoreCase = true) }
            if (labelAboveRoute != null) return labelAboveRoute.id
        }
        return if (style.getLayer(MapStyleBuilder.LAYER_USER_REPORTS_CIRCLE) != null) {
            MapStyleBuilder.LAYER_USER_REPORTS_CIRCLE
        } else {
            null
        }
    }

    private fun addAboveRouteOrBelowAnchor(
        style: Style,
        layer: org.maplibre.android.style.layers.Layer,
        anchorId: String?
    ) {
        if (anchorId != null && style.getLayer(anchorId) != null) {
            style.addLayerBelow(layer, anchorId)
        } else {
            style.addLayer(layer)
        }
    }

    private fun createManeuverArrowHeadBitmap(): Bitmap {
        val size = 56
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val path = Path().apply {
            moveTo(cx, 6f)
            lineTo(size - 9f, size - 11f)
            lineTo(cx, size - 19f)
            lineTo(9f, size - 11f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#0F172A")
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        })
        return bmp
    }

    private fun createVectorBitmap(context: Context, resId: Int, widthPx: Int, heightPx: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return null
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, widthPx, heightPx)
        drawable.draw(canvas)
        return bitmap
    }

    /** Draws a crisp blue circular "P" badge with a white ring and drop shadow for the parked car. */
    private fun createParkedCarBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
        }
        canvas.drawCircle(cx, cy + 2f, 26f, shadowPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        canvas.drawCircle(cx, cy, 25f, borderPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1D4ED8")
        }
        canvas.drawCircle(cx, cy, 21f, fillPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 29f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("P", cx, textY, textPaint)
        return bmp
    }
}
