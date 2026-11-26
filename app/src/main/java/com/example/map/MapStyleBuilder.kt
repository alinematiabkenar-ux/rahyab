package com.example.map

import com.example.i18n.AppLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates a self-contained offline MapLibre GL Style JSON (version 8) that renders
 * all downloaded Protomaps v4 (and v3) PMTiles files simultaneously via MapLibre Native's
 * `pmtiles://file://<abs_path>` protocol, using bundled offline PBF font glyphs from
 * `asset://fonts/{fontstack}/{range}.pbf`.
 *
 * Delegates layer assembly to [MapStyleProtomapsGeometry], [MapStyleProtomapsRoadLabels],
 * [MapStyleProtomapsPlaceLabels], [MapStyleDevOnlineLayers], and [MapStyleOverlayLayers].
 */
object MapStyleBuilder {

    const val OFFLINE_GLYPHS_URL = "asset://fonts/{fontstack}/{range}.pbf"

    const val FONT_REGULAR = "Noto Sans Regular"
    const val FONT_MEDIUM = "Noto Sans Medium"
    const val FONT_ITALIC = "Noto Sans Italic"

    const val SOURCE_ROUTE_0 = "rahyab_route_0"
    const val SOURCE_ROUTE_1 = "rahyab_route_1"
    const val SOURCE_ROUTE_2 = "rahyab_route_2"
    const val SOURCE_ROUTE_3 = "rahyab_route_3"
    const val SOURCE_PRIMARY_ROUTE = "rahyab_primary_route"
    const val SOURCE_DRIVEN_ROUTE = "rahyab_driven_route"
    const val SOURCE_DESTINATION = "rahyab_destination"
    const val SOURCE_USER_REPORTS = "rahyab_user_reports"
    const val SOURCE_CAMERAS = "rahyab_cameras"
    const val SOURCE_TRAFFIC_LIGHTS = "rahyab_traffic_lights"
    const val SOURCE_GPS_ACCURACY = "rahyab_gps_accuracy"
    const val SOURCE_GPS_PUCK = "rahyab_gps_puck"

    const val LAYER_ROUTE_0 = "rahyab_route_0_line"
    const val LAYER_ROUTE_1 = "rahyab_route_1_line"
    const val LAYER_ROUTE_2 = "rahyab_route_2_line"
    const val LAYER_ROUTE_3 = "rahyab_route_3_line"
    const val LAYER_DRIVEN_ROUTE_CASING = "rahyab_driven_route_casing"
    const val LAYER_DRIVEN_ROUTE = "rahyab_driven_route_line"
    const val LAYER_PRIMARY_ROUTE_CASING = "rahyab_primary_route_casing"
    const val LAYER_PRIMARY_ROUTE = "rahyab_primary_route_line"
    const val LAYER_ROUTE_CHEVRONS = "rahyab_route_chevrons"
    const val LAYER_DESTINATION_CIRCLE = "rahyab_destination_circle"
    const val LAYER_USER_REPORTS_CIRCLE = "rahyab_user_reports_circle"
    const val LAYER_CAMERAS_SYMBOL = "rahyab_cameras_symbol"
    const val LAYER_TRAFFIC_LIGHTS_SYMBOL = "rahyab_traffic_lights_symbol"
    const val LAYER_GPS_ACCURACY = "rahyab_gps_accuracy_fill"
    const val LAYER_GPS_ACCURACY_OUTLINE = "rahyab_gps_accuracy_outline"
    const val LAYER_GPS_PUCK = "rahyab_gps_puck_circle"
    const val LAYER_GPS_ARROW = "rahyab_gps_arrow_symbol"
    const val LAYER_BUILDINGS_3D_SUFFIX = "_buildings_3d"

    const val IMAGE_USER_ARROW = "rahyab_user_heading_arrow"
    const val IMAGE_USER_ARROW_GRAY = "rahyab_user_heading_arrow_gray"
    const val IMAGE_USER_CAR = "rahyab_user_heading_car"
    const val IMAGE_USER_CAR_GRAY = "rahyab_user_heading_car_gray"
    const val IMAGE_ROUTE_CHEVRON = "rahyab_route_chevron_icon"
    const val IMAGE_HIGHWAY_SHIELD = "rahyab_highway_shield_bg"
    const val IMAGE_SHIELD_INTERSTATE = "rahyab_shield_interstate"
    const val IMAGE_SHIELD_US_ROUTE = "rahyab_shield_us_route"
    const val IMAGE_SHIELD_STATE = "rahyab_shield_state"
    const val IMAGE_POI_GAS = "rahyab_poi_gas"
    const val IMAGE_POI_PARKING = "rahyab_poi_parking"
    const val IMAGE_POI_RESTAURANT = "rahyab_poi_restaurant"
    const val IMAGE_POI_CAFE = "rahyab_poi_cafe"
    const val IMAGE_POI_GROCERY = "rahyab_poi_grocery"
    const val IMAGE_POI_PHARMACY = "rahyab_poi_pharmacy"
    const val IMAGE_POI_HOSPITAL = "rahyab_poi_hospital"
    const val IMAGE_POI_SCHOOL = "rahyab_poi_school"
    const val IMAGE_POI_BANK = "rahyab_poi_bank"
    const val IMAGE_POI_EV = "rahyab_poi_ev"
    const val IMAGE_SPEED_CAMERA = "rahyab_speed_camera_icon"
    const val IMAGE_RED_LIGHT_CAMERA = "rahyab_red_light_camera_icon"
    const val IMAGE_TRAFFIC_LIGHT = "rahyab_traffic_light_icon"

    const val ROUTE_COLOR_0 = "#00A8E8" // Cyan-Blue (Fastest / Route 1)
    const val ROUTE_COLOR_1 = "#10B981" // Emerald (Shortest / Alt 1)
    const val ROUTE_COLOR_2 = "#A855F7" // Violet (Alternative 2)
    const val ROUTE_COLOR_3 = "#F59E0B" // Amber (Alternative 3 / 4th route)

    val ALL_ROUTE_SOURCES = listOf(
        SOURCE_ROUTE_0,
        SOURCE_ROUTE_1,
        SOURCE_ROUTE_2,
        SOURCE_ROUTE_3
    )

    val ALL_ROUTE_COLORS = listOf(
        ROUTE_COLOR_0,
        ROUTE_COLOR_1,
        ROUTE_COLOR_2,
        ROUTE_COLOR_3
    )

    enum class StyleProfile {
        PHONE,
        CAR
    }

    fun buildOfflineStyleJson(
        installedPmtiles: List<Pair<String, File>>,
        isNightMode: Boolean,
        useDevOnlineBasemap: Boolean = false,
        language: AppLanguage = AppLanguage.ENGLISH,
        styleProfile: StyleProfile = StyleProfile.PHONE
    ): String {
        val isCar = styleProfile == StyleProfile.CAR
        val palette = when {
            isNightMode && isCar -> MapStylePaletteAndExprs.NightCarMapPalette
            isNightMode -> MapStylePaletteAndExprs.NightMapPalette
            else -> MapStylePaletteAndExprs.DayMapPalette
        }

        val sources = JSONObject()
        val layers = JSONArray()

        layers.put(
            JSONObject().apply {
                put("id", "background")
                put("type", "background")
                put("paint", JSONObject().put("background-color", palette.background))
            }
        )

        if (installedPmtiles.isNotEmpty()) {
            val sourceIds = mutableListOf<String>()
            for ((stateCode, pmtilesFile) in installedPmtiles) {
                val sourceId = "pmtiles_${stateCode.replace("-", "_")}"
                sourceIds.add(sourceId)
                sources.put(
                    sourceId,
                    JSONObject().apply {
                        put("type", "vector")
                        put("url", "pmtiles://file://${pmtilesFile.absolutePath}")
                    }
                )
                MapStyleProtomapsGeometry.addProtomapsBaseGeometryLayers(layers, sourceId, palette, isCar)
            }
            for (sourceId in sourceIds) {
                MapStyleProtomapsRoadLabels.addRoadAndNaturalLabels(layers, sourceId, palette, language, isCar)
                MapStyleProtomapsPlaceLabels.addPlaceAndPoiLabels(layers, sourceId, palette, language, isCar)
            }
        } else if (useDevOnlineBasemap) {
            val sourceId = "openfreemap_dev"
            sources.put(
                sourceId,
                JSONObject().apply {
                    put("type", "vector")
                    put("url", "https://tiles.openfreemap.org/planet")
                }
            )
            MapStyleDevOnlineLayers.addDevOnlineBasemapLayers(layers, sourceId, palette, language, isCar)
        }

        MapStyleOverlayLayers.addDynamicOverlaySourcesAndLayers(sources, layers, isNightMode)

        return JSONObject().apply {
            put("version", 8)
            put("name", if (isNightMode) "RahYab Night" else "RahYab Day")
            put("glyphs", OFFLINE_GLYPHS_URL)
            put("sources", sources)
            put("layers", layers)
        }.toString()
    }
}
