package com.example.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mechanical extraction from [MapStyleBuilder] (R2):
 * dynamic GeoJSON overlay sources and layers (routes, chevrons, reports, traffic lights, cameras,
 * destination pin, GPS accuracy circle, GPS puck, and user heading arrow).
 */
internal object MapStyleOverlayLayers {

    fun addDynamicOverlaySourcesAndLayers(
        sources: JSONObject,
        layers: JSONArray,
        isNightMode: Boolean
    ) {
        val emptyFc = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", JSONArray())
        }
        (MapStyleBuilder.ALL_ROUTE_SOURCES + listOf(
            MapStyleBuilder.SOURCE_DRIVEN_ROUTE,
            MapStyleBuilder.SOURCE_PRIMARY_ROUTE,
            MapStyleBuilder.SOURCE_DESTINATION,
            MapStyleBuilder.SOURCE_USER_REPORTS,
            MapStyleBuilder.SOURCE_CAMERAS,
            MapStyleBuilder.SOURCE_TRAFFIC_LIGHTS,
            MapStyleBuilder.SOURCE_GPS_ACCURACY,
            MapStyleBuilder.SOURCE_GPS_PUCK
        )).forEach { id ->
            sources.put(id, JSONObject().apply {
                put("type", "geojson")
                put("data", emptyFc)
            })
        }

        listOf(
            Triple(MapStyleBuilder.LAYER_ROUTE_3, MapStyleBuilder.SOURCE_ROUTE_3, MapStyleBuilder.ROUTE_COLOR_3),
            Triple(MapStyleBuilder.LAYER_ROUTE_2, MapStyleBuilder.SOURCE_ROUTE_2, MapStyleBuilder.ROUTE_COLOR_2),
            Triple(MapStyleBuilder.LAYER_ROUTE_1, MapStyleBuilder.SOURCE_ROUTE_1, MapStyleBuilder.ROUTE_COLOR_1),
            Triple(MapStyleBuilder.LAYER_ROUTE_0, MapStyleBuilder.SOURCE_ROUTE_0, MapStyleBuilder.ROUTE_COLOR_0)
        ).forEach { (layerId, sourceId, colorHex) ->
            layers.put(JSONObject().apply {
                put("id", layerId)
                put("type", "line")
                put("source", sourceId)
                put("layout", JSONObject().apply {
                    put("line-cap", "round")
                    put("line-join", "round")
                })
                put("paint", JSONObject().apply {
                    put("line-color", colorHex)
                    put("line-width", 6.5)
                    put("line-opacity", 0.68)
                })
            })
        }

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_DRIVEN_ROUTE_CASING)
            put("type", "line")
            put("source", MapStyleBuilder.SOURCE_DRIVEN_ROUTE)
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", if (isNightMode) "#1E293B" else "#475569")
                put("line-width", 11.5)
                put("line-opacity", 0.72)
            })
        })
        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_DRIVEN_ROUTE)
            put("type", "line")
            put("source", MapStyleBuilder.SOURCE_DRIVEN_ROUTE)
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", if (isNightMode) "#64748B" else "#94A3B8")
                put("line-width", 7.2)
                put("line-opacity", 0.78)
            })
        })

        val driveRouteFilter = JSONArray()
            .put("all")
            .put(JSONArray().put("!=").put(JSONArray().put("get").put("mode")).put("walk"))
            .put(JSONArray().put("!=").put(JSONArray().put("get").put("mode")).put("bike"))

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_PRIMARY_ROUTE_CASING)
            put("type", "line")
            put("source", MapStyleBuilder.SOURCE_PRIMARY_ROUTE)
            put("filter", driveRouteFilter)
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", if (isNightMode) "#0B1F3A" else "#0A2540")
                put("line-width", 13.0)
            })
        })
        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_PRIMARY_ROUTE)
            put("type", "line")
            put("source", MapStyleBuilder.SOURCE_PRIMARY_ROUTE)
            put("filter", driveRouteFilter)
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put(
                    "line-color",
                    JSONArray()
                        .put("coalesce")
                        .put(JSONArray().put("get").put("color"))
                        .put(if (isNightMode) "#00E5FF" else MapStyleBuilder.ROUTE_COLOR_0)
                )
                put("line-width", 8.2)
            })
        })
        layers.put(JSONObject().apply {
            put("id", "rahyab_primary_route_walk")
            put("type", "line")
            put("source", MapStyleBuilder.SOURCE_PRIMARY_ROUTE)
            put("filter", JSONArray().put("==").put(JSONArray().put("get").put("mode")).put("walk"))
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", if (isNightMode) "#60A5FA" else "#2563EB")
                put("line-width", 6.0)
                put("line-dasharray", JSONArray().put(0.5).put(2.0))
            })
        })
        layers.put(JSONObject().apply {
            put("id", "rahyab_primary_route_bike")
            put("type", "line")
            put("source", MapStyleBuilder.SOURCE_PRIMARY_ROUTE)
            put("filter", JSONArray().put("==").put(JSONArray().put("get").put("mode")).put("bike"))
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", if (isNightMode) "#4ADE80" else "#16A34A")
                put("line-width", 6.5)
                put("line-dasharray", JSONArray().put(2.5).put(1.5))
            })
        })

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_ROUTE_CHEVRONS)
            put("type", "symbol")
            put("source", MapStyleBuilder.SOURCE_PRIMARY_ROUTE)
            put("filter", driveRouteFilter)
            put("minzoom", 15)
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 72)
                put("icon-image", MapStyleBuilder.IMAGE_ROUTE_CHEVRON)
                put("icon-size", 0.72)
                put("icon-rotation-alignment", "map")
                put("icon-pitch-alignment", "map")
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", true)
            })
        })

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_USER_REPORTS_CIRCLE)
            put("type", "circle")
            put("source", MapStyleBuilder.SOURCE_USER_REPORTS)
            put("paint", JSONObject().apply {
                put("circle-radius", 10.5)
                put(
                    "circle-color",
                    JSONArray()
                        .put("coalesce")
                        .put(JSONArray().put("get").put("color"))
                        .put("#F59E0B")
                )
                put("circle-stroke-width", 2.5)
                put("circle-stroke-color", "#FFFFFF")
            })
        })

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_TRAFFIC_LIGHTS_SYMBOL)
            put("type", "symbol")
            put("source", MapStyleBuilder.SOURCE_TRAFFIC_LIGHTS)
            put("minzoom", 15)
            put("layout", JSONObject().apply {
                put("icon-image", MapStyleBuilder.IMAGE_TRAFFIC_LIGHT)
                put("icon-size", 0.68)
                put("icon-padding", 4.0)
                put("icon-allow-overlap", false)
                put("icon-ignore-placement", false)
            })
        })

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_CAMERAS_SYMBOL)
            put("type", "symbol")
            put("source", MapStyleBuilder.SOURCE_CAMERAS)
            put("minzoom", 13)
            put("layout", JSONObject().apply {
                put("icon-image", JSONArray().put("get").put("icon"))
                put("icon-size", 0.85)
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", false)
            })
        })

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_DESTINATION_CIRCLE)
            put("type", "circle")
            put("source", MapStyleBuilder.SOURCE_DESTINATION)
            put("paint", JSONObject().apply {
                put("circle-radius", 11.0)
                put("circle-color", "#E53935")
                put("circle-stroke-width", 3.0)
                put("circle-stroke-color", "#FFFFFF")
            })
        })

        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_GPS_ACCURACY)
            put("type", "fill")
            put("source", MapStyleBuilder.SOURCE_GPS_ACCURACY)
            put("paint", JSONObject().apply {
                put("fill-color", "#60A5FA")
                put("fill-opacity", 0.22)
            })
        })
        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_GPS_ACCURACY_OUTLINE)
            put("type", "line")
            put("source", MapStyleBuilder.SOURCE_GPS_ACCURACY)
            put("paint", JSONObject().apply {
                put("line-color", "#38BDF8")
                put("line-width", 1.5)
                put("line-opacity", 0.65)
            })
        })
        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_GPS_PUCK)
            put("type", "circle")
            put("source", MapStyleBuilder.SOURCE_GPS_PUCK)
            put("paint", JSONObject().apply {
                put("circle-radius", 16.0)
                put(
                    "circle-color",
                    JSONArray()
                        .put("coalesce")
                        .put(JSONArray().put("get").put("haloColor"))
                        .put("#38BDF8")
                )
                put("circle-opacity", 0.22)
                put("circle-stroke-width", 1.5)
                put("circle-stroke-color", "#FFFFFF")
                put("circle-stroke-opacity", 0.55)
            })
        })
        layers.put(JSONObject().apply {
            put("id", MapStyleBuilder.LAYER_GPS_ARROW)
            put("type", "symbol")
            put("source", MapStyleBuilder.SOURCE_GPS_PUCK)
            put("layout", JSONObject().apply {
                put(
                    "icon-image",
                    JSONArray()
                        .put("coalesce")
                        .put(JSONArray().put("get").put("icon"))
                        .put(MapStyleBuilder.IMAGE_USER_ARROW)
                )
                put("icon-size", 0.92)
                put("icon-rotate", JSONArray().put("get").put("bearing"))
                put("icon-rotation-alignment", "map")
                put("icon-pitch-alignment", "map")
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", true)
            })
        })
    }
}
