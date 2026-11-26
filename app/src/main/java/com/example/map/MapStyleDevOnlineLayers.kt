package com.example.map

import com.example.i18n.AppLanguage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mechanical extraction from [MapStyleBuilder] (R2):
 * OpenFreeMap / OpenMapTiles developer online basemap layers.
 */
internal object MapStyleDevOnlineLayers {

    fun addDevOnlineBasemapLayers(
        layers: JSONArray,
        sourceId: String,
        palette: MapPalette,
        language: AppLanguage,
        isCar: Boolean = false
    ) {
        val roadScale = if (isCar) 1.3 else 1.0
        val textScale = if (isCar) 1.15 else 1.0
        val localizedName = MapStylePaletteAndExprs.localizedPlaceNameExpr(language)
        val englishStreet = MapStylePaletteAndExprs.englishStreetNameExpr()

        layers.put(JSONObject().apply {
            put("id", "dev_park")
            put("type", "fill")
            put("source", sourceId)
            put("source-layer", "park")
            put("paint", JSONObject().apply {
                put("fill-color", palette.park)
                put("fill-opacity", 0.55)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_water")
            put("type", "fill")
            put("source", sourceId)
            put("source-layer", "water")
            put("paint", JSONObject().put("fill-color", palette.water))
        })
        layers.put(JSONObject().apply {
            put("id", "dev_buildings")
            put("type", "fill")
            put("source", sourceId)
            put("source-layer", "building")
            put("minzoom", 13)
            put("paint", JSONObject().apply {
                put("fill-color", palette.building)
                put("fill-opacity", 0.72)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev${MapStyleBuilder.LAYER_BUILDINGS_3D_SUFFIX}")
            put("type", "fill-extrusion")
            put("source", sourceId)
            put("source-layer", "building")
            put("minzoom", 15)
            put("layout", JSONObject().apply {
                put("visibility", "none")
            })
            put("paint", JSONObject().apply {
                put("fill-extrusion-color", palette.building3d)
                put(
                    "fill-extrusion-height",
                    JSONArray()
                        .put("interpolate")
                        .put(JSONArray().put("linear"))
                        .put(JSONArray().put("zoom"))
                        .put(15.0).put(0.0)
                        .put(15.7).put(
                            JSONArray()
                                .put("coalesce")
                                .put(JSONArray().put("to-number").put(JSONArray().put("get").put("render_height")))
                                .put(JSONArray().put("to-number").put(JSONArray().put("get").put("height")))
                                .put(8.0)
                        )
                )
                put(
                    "fill-extrusion-base",
                    JSONArray()
                        .put("coalesce")
                        .put(JSONArray().put("to-number").put(JSONArray().put("get").put("render_min_height")))
                        .put(JSONArray().put("to-number").put(JSONArray().put("get").put("min_height")))
                        .put(0.0)
                )
                put(
                    "fill-extrusion-opacity",
                    JSONArray()
                        .put("interpolate")
                        .put(JSONArray().put("linear"))
                        .put(JSONArray().put("zoom"))
                        .put(15.0).put(0.0)
                        .put(15.8).put(0.82)
                )
                put("fill-extrusion-vertical-gradient", true)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_roads_minor")
            put("type", "line")
            put("source", sourceId)
            put("source-layer", "transportation")
            put("filter", JSONArray().put("in").put("class").put("minor").put("service").put("tertiary"))
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", palette.minorRoad)
                put("line-width", 2.0 * roadScale)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_roads_major")
            put("type", "line")
            put("source", sourceId)
            put("source-layer", "transportation")
            put("filter", JSONArray().put("in").put("class").put("primary").put("secondary").put("trunk"))
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", palette.majorRoad)
                put("line-width", 4.0 * roadScale)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_roads_motorway")
            put("type", "line")
            put("source", sourceId)
            put("source-layer", "transportation")
            put("filter", JSONArray().put("==").put("class").put("motorway"))
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", palette.highway)
                put("line-width", 6.0)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_boundaries")
            put("type", "line")
            put("source", sourceId)
            put("source-layer", "boundary")
            put("paint", JSONObject().apply {
                put("line-color", palette.boundary)
                put("line-width", 1.5)
                put("line-dasharray", JSONArray().put(3).put(2))
            })
        })

        layers.put(JSONObject().apply {
            put("id", "dev_label_water")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "water_name")
            put("minzoom", 9)
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_ITALIC))
                put("text-size", 12.0 * textScale)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.waterLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.6)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_label_roads")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "transportation_name")
            put("minzoom", if (isCar) 12 else 13)
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("text-field", englishStreet)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_REGULAR))
                put("text-size", 12.0 * textScale)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.majorRoadLabel)
                put("text-halo-color", palette.roadHalo)
                put("text-halo-width", 2.0)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_label_highway_ref")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "transportation_name")
            put("minzoom", 8)
            put("filter", JSONArray().put("has").put("ref"))
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 320)
                put("text-field", JSONArray().put("get").put("ref"))
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_MEDIUM))
                put("text-size", 11.0 * textScale)
                put("text-rotation-alignment", "viewport")
                put("icon-image", MapStyleBuilder.IMAGE_HIGHWAY_SHIELD)
                put("icon-text-fit", "both")
                put("icon-text-fit-padding", JSONArray().put(2).put(6).put(2).put(6))
                put("icon-rotation-alignment", "viewport")
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.shieldText)
                put("text-halo-color", palette.shieldHalo)
                put("text-halo-width", 0.6)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "dev_label_places")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "place")
            put("minzoom", 5)
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_MEDIUM))
                put("text-size", 15.0 * textScale)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.cityLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 2.0)
            })
        })
    }
}
