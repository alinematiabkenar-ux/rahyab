package com.example.map

import com.example.i18n.AppLanguage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mechanical extraction from [MapStyleBuilder] (R2):
 * Protomaps water, park, street name, and highway route shield symbol layers.
 */
internal object MapStyleProtomapsRoadLabels {

    fun addRoadAndNaturalLabels(
        layers: JSONArray,
        sourceId: String,
        palette: MapPalette,
        language: AppLanguage,
        isCar: Boolean = false
    ) {
        val textScale = if (isCar) 1.15 else 1.0
        val minorRoadMinZoom = if (isCar) 14 else 15
        val localizedName = MapStylePaletteAndExprs.localizedPlaceNameExpr(language)
        val englishStreet = MapStylePaletteAndExprs.englishStreetNameExpr()
        val highwayRef = MapStylePaletteAndExprs.highwayRefExpr()

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_water")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "water")
            put("minzoom", 8)
            put("filter", JSONArray().put("has").put("name"))
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_ITALIC))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(8).put(10.5 * textScale)
                    .put(14).put(13.5 * textScale)
                )
                put("text-max-width", 8)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.waterLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.6)
            })
        })

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_waterway")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "physical_line")
            put("minzoom", 12)
            put("filter", JSONArray().put("has").put("name"))
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_ITALIC))
                put("text-size", 11.5 * textScale)
                put("text-max-angle", 35)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.waterLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.5)
            })
        })

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_parks")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "landuse")
            put("minzoom", 13)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(
                        MapStylePaletteAndExprs.kindInFilter(
                            "park", "national_park", "nature_reserve", "forest",
                            "garden", "recreation_ground", "playground", "golf_course"
                        )
                    )
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_ITALIC))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(13).put(10.5 * textScale)
                    .put(16).put(12.5 * textScale)
                )
                put("text-max-width", 8)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.parkLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.6)
            })
        })

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_roads_minor")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "roads")
            put("minzoom", minorRoadMinZoom)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(MapStylePaletteAndExprs.kindInFilter("minor_road", "other"))
            )
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 260)
                put("text-field", englishStreet)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_REGULAR))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(minorRoadMinZoom).put(11.0 * textScale)
                    .put(18).put(13.5 * textScale)
                )
                put("text-max-angle", 30)
                put("text-padding", 4)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.minorRoadLabel)
                put("text-halo-color", palette.roadHalo)
                put("text-halo-width", 2.0)
            })
        })

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_roads_major")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "roads")
            put("minzoom", 13)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(MapStylePaletteAndExprs.kindInFilter("highway", "major_road", "medium_road"))
            )
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 280)
                put("text-field", englishStreet)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_MEDIUM))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(13).put(11.5 * textScale)
                    .put(16).put(14.0 * textScale)
                )
                put("text-max-angle", 30)
                put("text-padding", 4)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.majorRoadLabel)
                put("text-halo-color", palette.roadHalo)
                put("text-halo-width", 2.2)
            })
        })

        val hasRefOrShield = JSONArray()
            .put("any")
            .put(JSONArray().put("has").put("ref"))
            .put(JSONArray().put("has").put("shield_text"))
        val isInterstateExpr = JSONArray()
            .put("any")
            .put(JSONArray().put("==").put(JSONArray().put("get").put("network")).put("US:I"))
            .put(JSONArray().put("==").put(JSONArray().put("slice").put(JSONArray().put("to-string").put(highwayRef)).put(0).put(2)).put("I-"))
            .put(JSONArray().put("==").put(JSONArray().put("slice").put(JSONArray().put("to-string").put(highwayRef)).put(0).put(2)).put("I "))
        val isUsRouteExpr = JSONArray()
            .put("any")
            .put(JSONArray().put("==").put(JSONArray().put("get").put("network")).put("US:US"))
            .put(JSONArray().put("==").put(JSONArray().put("slice").put(JSONArray().put("to-string").put(highwayRef)).put(0).put(2)).put("US"))

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_highway_shields_interstate")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "roads")
            put("minzoom", 7)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(hasRefOrShield)
                    .put(MapStylePaletteAndExprs.kindInFilter("highway", "major_road"))
                    .put(isInterstateExpr)
            )
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 320)
                put("text-field", highwayRef)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_MEDIUM))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(7).put(9.5 * textScale)
                    .put(13).put(11.5 * textScale)
                    .put(16).put(12.5 * textScale)
                )
                put("text-rotation-alignment", "viewport")
                put("icon-image", MapStyleBuilder.IMAGE_SHIELD_INTERSTATE)
                put("icon-text-fit", "both")
                put("icon-text-fit-padding", JSONArray().put(7).put(8).put(3).put(8))
                put("icon-rotation-alignment", "viewport")
                put("text-padding", 6)
            })
            put("paint", JSONObject().apply {
                put("text-color", "#FFFFFF")
                put("text-halo-color", "#0F172A")
                put("text-halo-width", 0.5)
            })
        })

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_highway_shields")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "roads")
            put("minzoom", 8)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(hasRefOrShield)
                    .put(MapStylePaletteAndExprs.kindInFilter("highway", "major_road"))
                    .put(JSONArray().put("!").put(isInterstateExpr))
            )
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 320)
                put("text-field", highwayRef)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_MEDIUM))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(8).put(9.5 * textScale)
                    .put(13).put(11.0 * textScale)
                    .put(16).put(12.0 * textScale)
                )
                put("text-rotation-alignment", "viewport")
                put(
                    "icon-image",
                    JSONArray()
                        .put("case")
                        .put(isUsRouteExpr).put(MapStyleBuilder.IMAGE_SHIELD_US_ROUTE)
                        .put(MapStyleBuilder.IMAGE_SHIELD_STATE)
                )
                put("icon-text-fit", "both")
                put("icon-text-fit-padding", JSONArray().put(4).put(8).put(4).put(8))
                put("icon-rotation-alignment", "viewport")
                put("text-padding", 6)
            })
            put("paint", JSONObject().apply {
                put("text-color", "#0F172A")
                put("text-halo-color", "#FFFFFF")
                put("text-halo-width", 0.4)
            })
        })
    }
}
