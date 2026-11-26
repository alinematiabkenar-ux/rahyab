package com.example.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.navigation.LaneDirection
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
 * LN2: Registers and updates MapLibre GeoJSON sources & layers for on-road lane highlighting
 * on BOTH phone and Android Auto.
 *
 * - Placed ABOVE the route line/chevrons and BELOW map labels/maneuver arrow/reports.
 * - Target lanes filled with user-selected highlight color (`#E53935`, `#1E88E5`, `#43A047`).
 * - Other lanes drawn gray (`#64748B`) at 60% opacity, with white dashed lane separators.
 * - Zoom-interpolated line width so each lane stays ~3.2 m wide; visible strictly at zoom >= 16.
 * - Rotated lane arrow icon drawn inside each target lane ~40 m before the maneuver.
 */
object LaneMapLayerHelper {

    const val SOURCE_LANE_STRIPS = "rahyab_lane_strips_src"
    const val SOURCE_LANE_SEPARATORS = "rahyab_lane_separators_src"
    const val SOURCE_LANE_ARROWS = "rahyab_lane_arrows_src"

    const val LAYER_LANE_NON_TARGET = "rahyab_lane_non_target_layer"
    const val LAYER_LANE_TARGET = "rahyab_lane_target_layer"
    const val LAYER_LANE_SEPARATORS = "rahyab_lane_separators_layer"
    const val LAYER_LANE_ARROWS = "rahyab_lane_arrows_layer"

    private const val IMG_ARROW_STRAIGHT = "rahyab_lane_arrow_straight"
    private const val IMG_ARROW_LEFT = "rahyab_lane_arrow_left"
    private const val IMG_ARROW_RIGHT = "rahyab_lane_arrow_right"
    private const val IMG_ARROW_SLIGHT_LEFT = "rahyab_lane_arrow_slight_left"
    private const val IMG_ARROW_SLIGHT_RIGHT = "rahyab_lane_arrow_slight_right"
    private const val IMG_ARROW_UTURN = "rahyab_lane_arrow_uturn"

    fun registerLaneOverlayLayers(style: Style) {
        runCatching {
            style.addImage(IMG_ARROW_STRAIGHT, createLaneArrowBitmap(LaneDirection.THROUGH))
            style.addImage(IMG_ARROW_LEFT, createLaneArrowBitmap(LaneDirection.LEFT))
            style.addImage(IMG_ARROW_RIGHT, createLaneArrowBitmap(LaneDirection.RIGHT))
            style.addImage(IMG_ARROW_SLIGHT_LEFT, createLaneArrowBitmap(LaneDirection.SLIGHT_LEFT))
            style.addImage(IMG_ARROW_SLIGHT_RIGHT, createLaneArrowBitmap(LaneDirection.SLIGHT_RIGHT))
            style.addImage(IMG_ARROW_UTURN, createLaneArrowBitmap(LaneDirection.U_TURN_LEFT))

            if (style.getSource(SOURCE_LANE_STRIPS) == null) {
                style.addSource(GeoJsonSource(SOURCE_LANE_STRIPS))
            }
            if (style.getSource(SOURCE_LANE_SEPARATORS) == null) {
                style.addSource(GeoJsonSource(SOURCE_LANE_SEPARATORS))
            }
            if (style.getSource(SOURCE_LANE_ARROWS) == null) {
                style.addSource(GeoJsonSource(SOURCE_LANE_ARROWS))
            }

            // Place above route chevrons and below maneuver arrow / labels / reports
            val anchorId = findLaneAnchorLayerId(style)
            val laneWidthExpr = Expression.interpolate(
                Expression.exponential(2.0f),
                Expression.zoom(),
                Expression.stop(16, 4.0f),
                Expression.stop(17, 7.8f),
                Expression.stop(18, 15.2f),
                Expression.stop(19, 30.0f),
                Expression.stop(20, 58.0f)
            )
            val sepWidthExpr = Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(16, 1.1f),
                Expression.stop(18, 1.8f),
                Expression.stop(20, 3.0f)
            )
            val arrowSizeExpr = Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(16, 0.36f),
                Expression.stop(17, 0.56f),
                Expression.stop(18, 0.85f),
                Expression.stop(19, 1.2f)
            )

            val scaledArrowSizeExpr = Expression.product(
                arrowSizeExpr,
                Expression.switchCase(
                    Expression.eq(Expression.get("is_large"), Expression.literal(true)),
                    Expression.literal(1.38f),
                    Expression.literal(1.0f)
                )
            )

            if (style.getLayer(LAYER_LANE_NON_TARGET) == null) {
                val otherLayer = LineLayer(LAYER_LANE_NON_TARGET, SOURCE_LANE_STRIPS).apply {
                    minZoom = 15.5f
                    setFilter(Expression.eq(Expression.get("is_target"), Expression.literal(false)))
                    setProperties(
                        PropertyFactory.lineColor("#64748B"),
                        PropertyFactory.lineOpacity(0.45f),
                        PropertyFactory.lineWidth(laneWidthExpr),
                        PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                }
                addBelowAnchor(style, otherLayer, anchorId)
            }

            if (style.getLayer(LAYER_LANE_TARGET) == null) {
                val targetLayer = LineLayer(LAYER_LANE_TARGET, SOURCE_LANE_STRIPS).apply {
                    minZoom = 15.5f
                    setFilter(Expression.eq(Expression.get("is_target"), Expression.literal(true)))
                    setProperties(
                        PropertyFactory.lineColor(Expression.get("color")),
                        PropertyFactory.lineOpacity(0.85f),
                        PropertyFactory.lineWidth(laneWidthExpr),
                        PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                }
                addBelowAnchor(style, targetLayer, anchorId)
            }

            if (style.getLayer(LAYER_LANE_SEPARATORS) == null) {
                val sepLayer = LineLayer(LAYER_LANE_SEPARATORS, SOURCE_LANE_SEPARATORS).apply {
                    minZoom = 15.5f
                    setProperties(
                        PropertyFactory.lineColor("#FFFFFF"),
                        PropertyFactory.lineOpacity(0.92f),
                        PropertyFactory.lineWidth(sepWidthExpr),
                        PropertyFactory.lineDasharray(arrayOf(2.2f, 2.2f))
                    )
                }
                addBelowAnchor(style, sepLayer, anchorId)
            }

            if (style.getLayer(LAYER_LANE_ARROWS) == null) {
                val arrowLayer = SymbolLayer(LAYER_LANE_ARROWS, SOURCE_LANE_ARROWS).apply {
                    minZoom = 15.5f
                    setProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconSize(scaledArrowSizeExpr),
                        PropertyFactory.iconRotate(Expression.get("bearing")),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                }
                addBelowAnchor(style, arrowLayer, anchorId)
            }
        }
    }

    fun updateLaneOverlay(
        style: Style,
        overlay: LaneOverlayData?,
        highlightColorHex: String = LaneHighlightColor.BLUE.hex
    ) {
        if (!style.isFullyLoaded) return
        val emptyFc = FeatureCollection.fromFeatures(emptyArray())
        if (overlay == null) {
            runCatching {
                style.getSourceAs<GeoJsonSource>(SOURCE_LANE_STRIPS)?.setGeoJson(emptyFc)
                style.getSourceAs<GeoJsonSource>(SOURCE_LANE_SEPARATORS)?.setGeoJson(emptyFc)
                style.getSourceAs<GeoJsonSource>(SOURCE_LANE_ARROWS)?.setGeoJson(emptyFc)
            }
            return
        }

        val stripFeatures = overlay.lanes.mapNotNull { lane ->
            if (lane.points.size < 2) return@mapNotNull null
            val pts = lane.points.map { Point.fromLngLat(it.lon, it.lat) }
            Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                addNumberProperty("lane_index", lane.laneIndex)
                addBooleanProperty("is_target", lane.isTarget)
                addStringProperty("arrow_direction", lane.arrowDirection.name)
                addStringProperty("color", highlightColorHex)
            }
        }
        val sepFeatures = overlay.separators.mapNotNull { sep ->
            if (sep.points.size < 2) return@mapNotNull null
            val pts = sep.points.map { Point.fromLngLat(it.lon, it.lat) }
            Feature.fromGeometry(LineString.fromLngLats(pts))
        }
        val arrowFeatures = overlay.arrowMarkers.map { m ->
            Feature.fromGeometry(Point.fromLngLat(m.position.lon, m.position.lat)).apply {
                addNumberProperty("lane_index", m.laneIndex)
                addNumberProperty("bearing", m.bearingDegrees)
                addStringProperty("icon", iconForDirection(m.direction))
                addBooleanProperty("is_large", m.isLargeArrow)
            }
        }

        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_LANE_STRIPS)?.setGeoJson(FeatureCollection.fromFeatures(stripFeatures))
            style.getSourceAs<GeoJsonSource>(SOURCE_LANE_SEPARATORS)?.setGeoJson(FeatureCollection.fromFeatures(sepFeatures))
            style.getSourceAs<GeoJsonSource>(SOURCE_LANE_ARROWS)?.setGeoJson(FeatureCollection.fromFeatures(arrowFeatures))
        }
    }

    private fun iconForDirection(dir: LaneDirection): String = when (dir) {
        LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> IMG_ARROW_LEFT
        LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT -> IMG_ARROW_RIGHT
        LaneDirection.SLIGHT_LEFT, LaneDirection.MERGE_LEFT -> IMG_ARROW_SLIGHT_LEFT
        LaneDirection.SLIGHT_RIGHT, LaneDirection.MERGE_RIGHT -> IMG_ARROW_SLIGHT_RIGHT
        LaneDirection.U_TURN_LEFT, LaneDirection.U_TURN_RIGHT -> IMG_ARROW_UTURN
        LaneDirection.THROUGH -> IMG_ARROW_STRAIGHT
    }

    private fun findLaneAnchorLayerId(style: Style): String? {
        if (style.getLayer(MapExtraLayersHelper.LAYER_MANEUVER_ARROW_CASING) != null) {
            return MapExtraLayersHelper.LAYER_MANEUVER_ARROW_CASING
        }
        val allLayers = style.layers
        val routeIdx = allLayers.indexOfFirst { it.id == MapStyleBuilder.LAYER_ROUTE_CHEVRONS }
        if (routeIdx >= 0) {
            allLayers.drop(routeIdx + 1)
                .firstOrNull { it.id.contains("label", ignoreCase = true) }
                ?.let { return it.id }
        }
        return if (style.getLayer(MapStyleBuilder.LAYER_USER_REPORTS_CIRCLE) != null) {
            MapStyleBuilder.LAYER_USER_REPORTS_CIRCLE
        } else null
    }

    private fun addBelowAnchor(
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

    private fun createLaneArrowBitmap(direction: LaneDirection): Bitmap {
        val size = 56
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        when (direction) {
            LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> {
                path.moveTo(cx + 6f, size - 8f)
                path.lineTo(cx + 6f, 24f)
                path.lineTo(12f, 24f)
                path.moveTo(22f, 14f)
                path.lineTo(12f, 24f)
                path.lineTo(22f, 34f)
            }
            LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT -> {
                path.moveTo(cx - 6f, size - 8f)
                path.lineTo(cx - 6f, 24f)
                path.lineTo(size - 12f, 24f)
                path.moveTo(size - 22f, 14f)
                path.lineTo(size - 12f, 24f)
                path.lineTo(size - 22f, 34f)
            }
            LaneDirection.SLIGHT_LEFT, LaneDirection.MERGE_LEFT -> {
                path.moveTo(cx + 6f, size - 8f)
                path.lineTo(cx + 2f, 30f)
                path.lineTo(16f, 12f)
                path.moveTo(15f, 26f)
                path.lineTo(16f, 12f)
                path.lineTo(29f, 15f)
            }
            LaneDirection.SLIGHT_RIGHT, LaneDirection.MERGE_RIGHT -> {
                path.moveTo(cx - 6f, size - 8f)
                path.lineTo(cx - 2f, 30f)
                path.lineTo(size - 16f, 12f)
                path.moveTo(size - 15f, 26f)
                path.lineTo(size - 16f, 12f)
                path.lineTo(size - 29f, 15f)
            }
            LaneDirection.U_TURN_LEFT, LaneDirection.U_TURN_RIGHT -> {
                path.moveTo(cx + 10f, size - 8f)
                path.lineTo(cx + 10f, 18f)
                path.quadTo(cx + 10f, 8f, cx, 8f)
                path.quadTo(cx - 10f, 8f, cx - 10f, 18f)
                path.lineTo(cx - 10f, 34f)
                path.moveTo(cx - 18f, 26f)
                path.lineTo(cx - 10f, 34f)
                path.lineTo(cx - 2f, 26f)
            }
            LaneDirection.THROUGH -> {
                path.moveTo(cx, size - 8f)
                path.lineTo(cx, 12f)
                path.moveTo(cx - 11f, 23f)
                path.lineTo(cx, 12f)
                path.lineTo(cx + 11f, 23f)
            }
        }
        canvas.drawPath(path, strokePaint)
        return bmp
    }
}
