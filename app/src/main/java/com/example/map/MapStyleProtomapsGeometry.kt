package com.example.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mechanical extraction from [MapStyleBuilder] (R2):
 * builds Protomaps v4/v3 base geometry layers (earth, parks, water, buildings, 3D buildings, roads, boundaries).
 */
internal object MapStyleProtomapsGeometry {

    fun addProtomapsBaseGeometryLayers(
        layers: JSONArray,
        sourceId: String,
        palette: MapPalette,
        isCar: Boolean = false
    ) {
        val roadScale = if (isCar) 1.3 else 1.0
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_earth")
                put("type", "fill")
                put("source", sourceId)
                put("source-layer", "earth")
                put("paint", JSONObject().put("fill-color", palette.land))
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_landuse_park")
                put("type", "fill")
                put("source", sourceId)
                put("source-layer", "landuse")
                put(
                    "filter",
                    MapStylePaletteAndExprs.kindInFilter(
                        "park", "national_park", "nature_reserve", "forest",
                        "wood", "grass", "garden", "recreation_ground", "golf_course"
                    )
                )
                put("paint", JSONObject().apply {
                    put("fill-color", palette.park)
                    put("fill-opacity", 0.58)
                })
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_water")
                put("type", "fill")
                put("source", sourceId)
                put("source-layer", "water")
                put("paint", JSONObject().put("fill-color", palette.water))
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_buildings")
                put("type", "fill")
                put("source", sourceId)
                put("source-layer", "buildings")
                put("minzoom", 13)
                put("paint", JSONObject().apply {
                    put("fill-color", palette.building)
                    put("fill-opacity", 0.7)
                })
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}${MapStyleBuilder.LAYER_BUILDINGS_3D_SUFFIX}")
                put("type", "fill-extrusion")
                put("source", sourceId)
                put("source-layer", "buildings")
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
                                    .put(JSONArray().put("to-number").put(JSONArray().put("get").put("height")))
                                    .put(8.0)
                            )
                    )
                    put(
                        "fill-extrusion-base",
                        JSONArray()
                            .put("coalesce")
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
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_roads_minor")
                put("type", "line")
                put("source", sourceId)
                put("source-layer", "roads")
                put("filter", MapStylePaletteAndExprs.kindInFilter("minor_road", "other"))
                put("layout", JSONObject().apply {
                    put("line-cap", "round")
                    put("line-join", "round")
                })
                put("paint", JSONObject().apply {
                    put("line-color", palette.minorRoad)
                    put(
                        "line-width",
                        JSONArray()
                            .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                            .put(12).put(0.8 * roadScale)
                            .put(15).put(2.4 * roadScale)
                            .put(18).put(6.0 * roadScale)
                    )
                })
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_roads_major")
                put("type", "line")
                put("source", sourceId)
                put("source-layer", "roads")
                put("filter", MapStylePaletteAndExprs.kindInFilter("major_road", "medium_road"))
                put("layout", JSONObject().apply {
                    put("line-cap", "round")
                    put("line-join", "round")
                })
                put("paint", JSONObject().apply {
                    put("line-color", palette.majorRoad)
                    put(
                        "line-width",
                        JSONArray()
                            .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                            .put(8).put(1.2 * roadScale)
                            .put(13).put(3.6 * roadScale)
                            .put(17).put(8.5 * roadScale)
                    )
                })
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_roads_highway")
                put("type", "line")
                put("source", sourceId)
                put("source-layer", "roads")
                put("filter", MapStylePaletteAndExprs.kindEqFilter("highway"))
                put("layout", JSONObject().apply {
                    put("line-cap", "round")
                    put("line-join", "round")
                })
                put("paint", JSONObject().apply {
                    put("line-color", palette.highway)
                    put(
                        "line-width",
                        JSONArray()
                            .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                            .put(6).put(1.5)
                            .put(12).put(4.5)
                            .put(16).put(9.5)
                    )
                })
            }
        )
        layers.put(
            JSONObject().apply {
                put("id", "${sourceId}_boundaries")
                put("type", "line")
                put("source", sourceId)
                put("source-layer", "boundaries")
                put("paint", JSONObject().apply {
                    put("line-color", palette.boundary)
                    put("line-width", 1.5)
                    put("line-dasharray", JSONArray().put(3).put(2))
                })
            }
        )
    }
}
