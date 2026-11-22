package com.example.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.search.RoadHazardPoint
import com.example.navigation.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
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

    fun registerExtraOverlayLayers(context: Context, style: Style) {
        runCatching {
            createVectorBitmap(context, R.drawable.ic_speed_bump_map, 44, 44)?.let { bmp ->
                style.addImage(IMAGE_SPEED_BUMP, bmp)
            }
            style.addImage(IMAGE_PARKED_CAR, createParkedCarBitmap())

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
