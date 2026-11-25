package com.example.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.navigation.DestinationParkingOption
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * PK2: Draws crisp "P" map icons for parking options near the destination.
 */
object ParkingOverlayLayerHelper {

    const val SOURCE_DEST_PARKING = "rahyab_dest_parking_src"
    const val LAYER_DEST_PARKING_ICON = "rahyab_dest_parking_icon_layer"
    const val IMAGE_DEST_PARKING_P = "rahyab_dest_parking_p_icon"

    fun registerDestinationParkingLayer(style: Style) {
        runCatching {
            style.addImage(IMAGE_DEST_PARKING_P, createParkingBadgeBitmap())
            if (style.getSource(SOURCE_DEST_PARKING) == null) {
                style.addSource(GeoJsonSource(SOURCE_DEST_PARKING))
            }
            if (style.getLayer(LAYER_DEST_PARKING_ICON) == null) {
                val layer = SymbolLayer(LAYER_DEST_PARKING_ICON, SOURCE_DEST_PARKING).apply {
                    setProperties(
                        PropertyFactory.iconImage(IMAGE_DEST_PARKING_P),
                        PropertyFactory.iconSize(0.86f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                }
                if (style.getLayer(MapStyleBuilder.LAYER_GPS_PUCK) != null) {
                    style.addLayerBelow(layer, MapStyleBuilder.LAYER_GPS_PUCK)
                } else {
                    style.addLayer(layer)
                }
            }
        }
    }

    fun updateDestinationParkingIcons(style: Style, spots: List<DestinationParkingOption>) {
        if (!style.isFullyLoaded) return
        val fc = if (spots.isEmpty()) {
            FeatureCollection.fromFeatures(emptyArray())
        } else {
            FeatureCollection.fromFeatures(
                spots.map { spot ->
                    Feature.fromGeometry(Point.fromLngLat(spot.lon, spot.lat)).apply {
                        addStringProperty("id", spot.id)
                        addStringProperty("name", spot.name)
                    }
                }
            )
        }
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_DEST_PARKING)?.setGeoJson(fc)
        }
    }

    private fun createParkingBadgeBitmap(): Bitmap {
        val size = 56
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(5f, 5f, size - 5f, size - 5f)
        canvas.drawRoundRect(
            rect, 14f, 14f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D4ED8") }
        )
        canvas.drawRoundRect(
            rect, 14f, 14f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
                color = Color.WHITE
            }
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 31f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText("P", size / 2f, baseline, textPaint)
        return bmp
    }
}
