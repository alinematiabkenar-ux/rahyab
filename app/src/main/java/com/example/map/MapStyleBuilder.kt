package com.example.map

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generates a self-contained offline MapLibre GL Style JSON (version 8) that renders
 * all downloaded Protomaps v4 PMTiles files simultaneously via MapLibre Native's native
 * `pmtiles://file://<abs_path>` protocol.
 *
 * Renders up to 3 candidate routes in 3 distinct colors (Route 0 Cyan-Blue, Route 1 Emerald,
 * Route 2 Violet) with the selected route highlighted thicker on top, plus color-coded markers
 * for all 8 Waze-style user report types.
 */
object MapStyleBuilder {

    const val SOURCE_ROUTE_0 = "rahyab_route_0"
    const val SOURCE_ROUTE_1 = "rahyab_route_1"
    const val SOURCE_ROUTE_2 = "rahyab_route_2"
    const val SOURCE_PRIMARY_ROUTE = "rahyab_primary_route"
    const val SOURCE_DESTINATION = "rahyab_destination"
    const val SOURCE_USER_REPORTS = "rahyab_user_reports"
    const val SOURCE_GPS_ACCURACY = "rahyab_gps_accuracy"
    const val SOURCE_GPS_PUCK = "rahyab_gps_puck"

    const val LAYER_ROUTE_0 = "rahyab_route_0_line"
    const val LAYER_ROUTE_1 = "rahyab_route_1_line"
    const val LAYER_ROUTE_2 = "rahyab_route_2_line"
    const val LAYER_PRIMARY_ROUTE_CASING = "rahyab_primary_route_casing"
    const val LAYER_PRIMARY_ROUTE = "rahyab_primary_route_line"
    const val LAYER_DESTINATION_CIRCLE = "rahyab_destination_circle"
    const val LAYER_USER_REPORTS_CIRCLE = "rahyab_user_reports_circle"
    const val LAYER_GPS_ACCURACY = "rahyab_gps_accuracy_fill"
    const val LAYER_GPS_ACCURACY_OUTLINE = "rahyab_gps_accuracy_outline"
    const val LAYER_GPS_PUCK = "rahyab_gps_puck_circle"

    const val ROUTE_COLOR_0 = "#00A8E8" // Cyan-Blue (Fastest / Route 1)
    const val ROUTE_COLOR_1 = "#10B981" // Emerald (Alternative 1)
    const val ROUTE_COLOR_2 = "#A855F7" // Violet (Alternative 2)

    fun buildOfflineStyleJson(
        installedPmtiles: List<Pair<String, File>>,
        isNightMode: Boolean,
        useDevOnlineBasemap: Boolean = false
    ): String {
        val palette = if (isNightMode) NightMapPalette else DayMapPalette

        val sources = JSONObject()
        val layers = JSONArray()

        // 1. Background canvas
        layers.put(
            JSONObject().apply {
                put("id", "background")
                put("type", "background")
                put("paint", JSONObject().put("background-color", palette.background))
            }
        )

        // 2A. Offline state & regional PMTiles archives (default 100% offline path)
        if (installedPmtiles.isNotEmpty()) {
            for ((stateCode, pmtilesFile) in installedPmtiles) {
                val sourceId = "pmtiles_${stateCode.replace("-", "_")}"
                sources.put(
                    sourceId,
                    JSONObject().apply {
                        put("type", "vector")
                        put("url", "pmtiles://file://${pmtilesFile.absolutePath}")
                    }
                )

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
                        put("paint", JSONObject().apply {
                            put("fill-color", palette.park)
                            put("fill-opacity", 0.55)
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
                        put("id", "${sourceId}_roads_minor")
                        put("type", "line")
                        put("source", sourceId)
                        put("source-layer", "roads")
                        put("filter", JSONArray().put("in").put("pmap:kind").put("minor_road").put("other"))
                        put("layout", JSONObject().apply {
                            put("line-cap", "round")
                            put("line-join", "round")
                        })
                        put("paint", JSONObject().apply {
                            put("line-color", palette.minorRoad)
                            put("line-width", 2.0)
                        })
                    }
                )
                layers.put(
                    JSONObject().apply {
                        put("id", "${sourceId}_roads_major")
                        put("type", "line")
                        put("source", sourceId)
                        put("source-layer", "roads")
                        put("filter", JSONArray().put("in").put("pmap:kind").put("major_road").put("medium_road"))
                        put("layout", JSONObject().apply {
                            put("line-cap", "round")
                            put("line-join", "round")
                        })
                        put("paint", JSONObject().apply {
                            put("line-color", palette.majorRoad)
                            put("line-width", 4.0)
                        })
                    }
                )
                layers.put(
                    JSONObject().apply {
                        put("id", "${sourceId}_roads_highway")
                        put("type", "line")
                        put("source", sourceId)
                        put("source-layer", "roads")
                        put("filter", JSONArray().put("==").put("pmap:kind").put("highway"))
                        put("layout", JSONObject().apply {
                            put("line-cap", "round")
                            put("line-join", "round")
                        })
                        put("paint", JSONObject().apply {
                            put("line-color", palette.highway)
                            put("line-width", 6.0)
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
        } else if (useDevOnlineBasemap) {
            // 2B. Temporary Developer Option: OpenFreeMap public OSM vector tiles
            val sourceId = "openfreemap_dev"
            sources.put(
                sourceId,
                JSONObject().apply {
                    put("type", "vector")
                    put("url", "https://tiles.openfreemap.org/planet")
                }
            )
            layers.put(
                JSONObject().apply {
                    put("id", "dev_park")
                    put("type", "fill")
                    put("source", sourceId)
                    put("source-layer", "park")
                    put("paint", JSONObject().apply {
                        put("fill-color", palette.park)
                        put("fill-opacity", 0.55)
                    })
                }
            )
            layers.put(
                JSONObject().apply {
                    put("id", "dev_water")
                    put("type", "fill")
                    put("source", sourceId)
                    put("source-layer", "water")
                    put("paint", JSONObject().put("fill-color", palette.water))
                }
            )
            layers.put(
                JSONObject().apply {
                    put("id", "dev_buildings")
                    put("type", "fill")
                    put("source", sourceId)
                    put("source-layer", "building")
                    put("minzoom", 13)
                    put("paint", JSONObject().apply {
                        put("fill-color", palette.building)
                        put("fill-opacity", 0.72)
                    })
                }
            )
            layers.put(
                JSONObject().apply {
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
                        put("line-width", 2.0)
                    })
                }
            )
            layers.put(
                JSONObject().apply {
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
                        put("line-width", 4.0)
                    })
                }
            )
            layers.put(
                JSONObject().apply {
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
                }
            )
            layers.put(
                JSONObject().apply {
                    put("id", "dev_boundaries")
                    put("type", "line")
                    put("source", sourceId)
                    put("source-layer", "boundary")
                    put("paint", JSONObject().apply {
                        put("line-color", palette.boundary)
                        put("line-width", 1.5)
                        put("line-dasharray", JSONArray().put(3).put(2))
                    })
                }
            )
        }

        // 3. Dynamic GeoJSON sources for up to 3 color-coded routes, destination, reports, and GPS puck
        val emptyFc = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", JSONArray())
        }
        listOf(
            SOURCE_ROUTE_0,
            SOURCE_ROUTE_1,
            SOURCE_ROUTE_2,
            SOURCE_PRIMARY_ROUTE,
            SOURCE_DESTINATION,
            SOURCE_USER_REPORTS,
            SOURCE_GPS_ACCURACY,
            SOURCE_GPS_PUCK
        ).forEach { id ->
            sources.put(id, JSONObject().apply {
                put("type", "geojson")
                put("data", emptyFc)
            })
        }

        // Unselected candidate route lines in 3 distinct colors
        listOf(
            Triple(LAYER_ROUTE_2, SOURCE_ROUTE_2, ROUTE_COLOR_2),
            Triple(LAYER_ROUTE_1, SOURCE_ROUTE_1, ROUTE_COLOR_1),
            Triple(LAYER_ROUTE_0, SOURCE_ROUTE_0, ROUTE_COLOR_0)
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
                    put("line-opacity", 0.65)
                })
            })
        }

        // Selected / Active route casing & glowing highlighted core on top
        layers.put(JSONObject().apply {
            put("id", LAYER_PRIMARY_ROUTE_CASING)
            put("type", "line")
            put("source", SOURCE_PRIMARY_ROUTE)
            put("layout", JSONObject().apply {
                put("line-cap", "round")
                put("line-join", "round")
            })
            put("paint", JSONObject().apply {
                put("line-color", if (isNightMode) "#0F172A" else "#0A2540")
                put("line-width", 12.5)
            })
        })
        layers.put(JSONObject().apply {
            put("id", LAYER_PRIMARY_ROUTE)
            put("type", "line")
            put("source", SOURCE_PRIMARY_ROUTE)
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
                        .put(if (isNightMode) "#00E5FF" else ROUTE_COLOR_0)
                )
                put("line-width", 8.0)
            })
        })

        // Color-coded User-reported alerts layer
        layers.put(JSONObject().apply {
            put("id", LAYER_USER_REPORTS_CIRCLE)
            put("type", "circle")
            put("source", SOURCE_USER_REPORTS)
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

        // Destination marker
        layers.put(JSONObject().apply {
            put("id", LAYER_DESTINATION_CIRCLE)
            put("type", "circle")
            put("source", SOURCE_DESTINATION)
            put("paint", JSONObject().apply {
                put("circle-radius", 11.0)
                put("circle-color", "#E53935")
                put("circle-stroke-width", 3.0)
                put("circle-stroke-color", "#FFFFFF")
            })
        })

        // Light blue GPS accuracy circle (geographic radius in meters) + live position puck
        layers.put(JSONObject().apply {
            put("id", LAYER_GPS_ACCURACY)
            put("type", "fill")
            put("source", SOURCE_GPS_ACCURACY)
            put("paint", JSONObject().apply {
                put("fill-color", "#60A5FA")
                put("fill-opacity", 0.22)
            })
        })
        layers.put(JSONObject().apply {
            put("id", LAYER_GPS_ACCURACY_OUTLINE)
            put("type", "line")
            put("source", SOURCE_GPS_ACCURACY)
            put("paint", JSONObject().apply {
                put("line-color", "#38BDF8")
                put("line-width", 1.5)
                put("line-opacity", 0.65)
            })
        })
        layers.put(JSONObject().apply {
            put("id", LAYER_GPS_PUCK)
            put("type", "circle")
            put("source", SOURCE_GPS_PUCK)
            put("paint", JSONObject().apply {
                put("circle-radius", 9.5)
                put("circle-color", "#00D4C8")
                put("circle-stroke-width", 3.0)
                put("circle-stroke-color", "#FFFFFF")
            })
        })

        return JSONObject().apply {
            put("version", 8)
            put("name", if (isNightMode) "RahYab Night" else "RahYab Day")
            put("sources", sources)
            put("layers", layers)
        }.toString()
    }

    private data class MapPalette(
        val background: String,
        val land: String,
        val park: String,
        val water: String,
        val building: String,
        val minorRoad: String,
        val majorRoad: String,
        val highway: String,
        val boundary: String
    )

    private val DayMapPalette = MapPalette(
        background = "#E6EDF5",
        land = "#F4F6F8",
        park = "#CBE8CD",
        water = "#A8D5F2",
        building = "#DCE2EA",
        minorRoad = "#FFFFFF",
        majorRoad = "#FFE39E",
        highway = "#FFAE52",
        boundary = "#94A3B8"
    )

    private val NightMapPalette = MapPalette(
        background = "#091022",
        land = "#0E172E",
        park = "#112B2A",
        water = "#071E3D",
        building = "#182442",
        minorRoad = "#223254",
        majorRoad = "#364B75",
        highway = "#D97706",
        boundary = "#475569"
    )
}
