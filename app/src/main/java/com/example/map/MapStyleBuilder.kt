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
 * Layer z-order guarantees map text labels never cover active/candidate route lines or
 * the user's live position arrow:
 *   1. Background, earth, parks, water, buildings, roads, boundaries
 *   2. Basemap symbol labels (water, parks, minor/major streets, highway shields, POIs, places)
 *   3. Up to 4 color-coded candidate routes + highlighted active/selected route on top
 *   4. User report markers, destination marker, GPS accuracy circle, GPS puck, position arrow
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
    const val SOURCE_DESTINATION = "rahyab_destination"
    const val SOURCE_USER_REPORTS = "rahyab_user_reports"
    const val SOURCE_CAMERAS = "rahyab_cameras"
    const val SOURCE_GPS_ACCURACY = "rahyab_gps_accuracy"
    const val SOURCE_GPS_PUCK = "rahyab_gps_puck"

    const val LAYER_ROUTE_0 = "rahyab_route_0_line"
    const val LAYER_ROUTE_1 = "rahyab_route_1_line"
    const val LAYER_ROUTE_2 = "rahyab_route_2_line"
    const val LAYER_ROUTE_3 = "rahyab_route_3_line"
    const val LAYER_PRIMARY_ROUTE_CASING = "rahyab_primary_route_casing"
    const val LAYER_PRIMARY_ROUTE = "rahyab_primary_route_line"
    const val LAYER_DESTINATION_CIRCLE = "rahyab_destination_circle"
    const val LAYER_USER_REPORTS_CIRCLE = "rahyab_user_reports_circle"
    const val LAYER_CAMERAS_SYMBOL = "rahyab_cameras_symbol"
    const val LAYER_GPS_ACCURACY = "rahyab_gps_accuracy_fill"
    const val LAYER_GPS_ACCURACY_OUTLINE = "rahyab_gps_accuracy_outline"
    const val LAYER_GPS_PUCK = "rahyab_gps_puck_circle"
    const val LAYER_GPS_ARROW = "rahyab_gps_arrow_symbol"

    const val IMAGE_USER_ARROW = "rahyab_user_heading_arrow"
    const val IMAGE_HIGHWAY_SHIELD = "rahyab_highway_shield_bg"
    const val IMAGE_SPEED_CAMERA = "rahyab_speed_camera_icon"
    const val IMAGE_RED_LIGHT_CAMERA = "rahyab_red_light_camera_icon"

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

    fun buildOfflineStyleJson(
        installedPmtiles: List<Pair<String, File>>,
        isNightMode: Boolean,
        useDevOnlineBasemap: Boolean = false,
        language: AppLanguage = AppLanguage.ENGLISH
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
                addProtomapsBaseGeometryLayers(layers, sourceId, palette)
            }
            // Add all basemap text/symbol layers AFTER geometry and BEFORE route & GPS overlays
            for (sourceId in sourceIds) {
                addProtomapsLabelLayers(layers, sourceId, palette, language)
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
            addDevOnlineBasemapLayers(layers, sourceId, palette, language)
        }

        // 3. Dynamic GeoJSON sources for up to 4 color-coded routes, destination, reports, and GPS puck
        val emptyFc = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", JSONArray())
        }
        (ALL_ROUTE_SOURCES + listOf(
            SOURCE_PRIMARY_ROUTE,
            SOURCE_DESTINATION,
            SOURCE_USER_REPORTS,
            SOURCE_CAMERAS,
            SOURCE_GPS_ACCURACY,
            SOURCE_GPS_PUCK
        )).forEach { id ->
            sources.put(id, JSONObject().apply {
                put("type", "geojson")
                put("data", emptyFc)
            })
        }

        // Unselected candidate route lines in 4 distinct colors (rendered ABOVE map labels)
        listOf(
            Triple(LAYER_ROUTE_3, SOURCE_ROUTE_3, ROUTE_COLOR_3),
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
                    put("line-opacity", 0.68)
                })
            })
        }

        // Selected / Active route casing & glowing highlighted core on top of all labels & unselected routes
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

        // Speed cameras & red light cameras (visible from zoom 13)
        layers.put(JSONObject().apply {
            put("id", LAYER_CAMERAS_SYMBOL)
            put("type", "symbol")
            put("source", SOURCE_CAMERAS)
            put("minzoom", 13)
            put("layout", JSONObject().apply {
                put("icon-image", JSONArray().put("get").put("icon"))
                put("icon-size", 0.85)
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", false)
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

        // Light blue GPS accuracy circle (geographic radius in meters) + live position puck & arrow
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
                put("circle-radius", 8.5)
                put("circle-color", "#00D4C8")
                put("circle-stroke-width", 2.5)
                put("circle-stroke-color", "#FFFFFF")
            })
        })
        layers.put(JSONObject().apply {
            put("id", LAYER_GPS_ARROW)
            put("type", "symbol")
            put("source", SOURCE_GPS_PUCK)
            put("layout", JSONObject().apply {
                put("icon-image", IMAGE_USER_ARROW)
                put("icon-size", 0.95)
                put("icon-rotate", JSONArray().put("get").put("bearing"))
                put("icon-rotation-alignment", "map")
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", true)
            })
        })

        return JSONObject().apply {
            put("version", 8)
            put("name", if (isNightMode) "RahYab Night" else "RahYab Day")
            put("glyphs", OFFLINE_GLYPHS_URL)
            put("sources", sources)
            put("layers", layers)
        }.toString()
    }

    /**
     * Supports both Protomaps v4 (`kind`) and v3 (`pmap:kind`) vector tile properties.
     */
    private fun protomapsKindExpr(): JSONArray =
        JSONArray()
            .put("coalesce")
            .put(JSONArray().put("get").put("kind"))
            .put(JSONArray().put("get").put("pmap:kind"))

    private fun kindInFilter(vararg kinds: String): JSONArray {
        val lit = JSONArray()
        kinds.forEach { lit.put(it) }
        return JSONArray()
            .put("in")
            .put(protomapsKindExpr())
            .put(JSONArray().put("literal").put(lit))
    }

    private fun kindEqFilter(kind: String): JSONArray =
        JSONArray()
            .put("==")
            .put(protomapsKindExpr())
            .put(kind)

    /**
     * Street names & highway signs MUST always remain in English (matching US physical street signs)
     * even when the app language is Persian.
     */
    private fun englishStreetNameExpr(): JSONArray =
        JSONArray()
            .put("coalesce")
            .put(JSONArray().put("get").put("name:en"))
            .put(JSONArray().put("get").put("name"))

    /**
     * Highway route numbers (e.g. I-5, OR-126, US-101) always use the English ref/shield text.
     */
    private fun highwayRefExpr(): JSONArray =
        JSONArray()
            .put("coalesce")
            .put(JSONArray().put("get").put("ref"))
            .put(JSONArray().put("get").put("shield_text"))

    /**
     * City, town, neighborhood, water, park, and POI labels use Persian (`name:fa`) when
     * the app is set to Persian and a Persian name exists, falling back to English.
     */
    private fun localizedPlaceNameExpr(language: AppLanguage): JSONArray =
        if (language == AppLanguage.PERSIAN) {
            JSONArray()
                .put("coalesce")
                .put(JSONArray().put("get").put("name:fa"))
                .put(JSONArray().put("get").put("name:en"))
                .put(JSONArray().put("get").put("name"))
        } else {
            JSONArray()
                .put("coalesce")
                .put(JSONArray().put("get").put("name:en"))
                .put(JSONArray().put("get").put("name"))
        }

    private fun addProtomapsBaseGeometryLayers(
        layers: JSONArray,
        sourceId: String,
        palette: MapPalette
    ) {
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
                    kindInFilter(
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
                put("id", "${sourceId}_roads_minor")
                put("type", "line")
                put("source", sourceId)
                put("source-layer", "roads")
                put("filter", kindInFilter("minor_road", "other"))
                put("layout", JSONObject().apply {
                    put("line-cap", "round")
                    put("line-join", "round")
                })
                put("paint", JSONObject().apply {
                    put("line-color", palette.minorRoad)
                    put("line-width", JSONArray()
                        .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                        .put(12).put(0.8)
                        .put(15).put(2.4)
                        .put(18).put(6.0)
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
                put("filter", kindInFilter("major_road", "medium_road"))
                put("layout", JSONObject().apply {
                    put("line-cap", "round")
                    put("line-join", "round")
                })
                put("paint", JSONObject().apply {
                    put("line-color", palette.majorRoad)
                    put("line-width", JSONArray()
                        .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                        .put(8).put(1.2)
                        .put(13).put(3.6)
                        .put(17).put(8.5)
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
                put("filter", kindEqFilter("highway"))
                put("layout", JSONObject().apply {
                    put("line-cap", "round")
                    put("line-join", "round")
                })
                put("paint", JSONObject().apply {
                    put("line-color", palette.highway)
                    put("line-width", JSONArray()
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

    private fun addProtomapsLabelLayers(
        layers: JSONArray,
        sourceId: String,
        palette: MapPalette,
        language: AppLanguage
    ) {
        val localizedName = localizedPlaceNameExpr(language)
        val englishStreet = englishStreetNameExpr()
        val highwayRef = highwayRefExpr()

        // 1. Water names (lakes, oceans, bays)
        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_water")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "water")
            put("minzoom", 8)
            put("filter", JSONArray().put("has").put("name"))
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(FONT_ITALIC))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(8).put(10.5)
                    .put(14).put(13.5)
                )
                put("text-max-width", 8)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.waterLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.6)
            })
        })

        // 1b. River / waterway line names (Protomaps v4 physical_line)
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
                put("text-font", JSONArray().put(FONT_ITALIC))
                put("text-size", 11.5)
                put("text-max-angle", 35)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.waterLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.5)
            })
        })

        // 2. Park & green space names (landuse layer)
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
                        kindInFilter(
                            "park", "national_park", "nature_reserve", "forest",
                            "garden", "recreation_ground", "playground", "golf_course"
                        )
                    )
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(FONT_ITALIC))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(13).put(10.5)
                    .put(16).put(12.5)
                )
                put("text-max-width", 8)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.parkLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.6)
            })
        })

        // 3. Minor street names along roads (from zoom 15, ALWAYS English)
        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_roads_minor")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "roads")
            put("minzoom", 15)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(kindInFilter("minor_road", "other"))
            )
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 260)
                put("text-field", englishStreet)
                put("text-font", JSONArray().put(FONT_REGULAR))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(15).put(11.0)
                    .put(18).put(13.5)
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

        // 4. Major road names along roads (from zoom 13, ALWAYS English)
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
                    .put(kindInFilter("highway", "major_road", "medium_road"))
            )
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 280)
                put("text-field", englishStreet)
                put("text-font", JSONArray().put(FONT_MEDIUM))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(13).put(11.5)
                    .put(16).put(14.0)
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

        // 5. Highway route shields / bold route numbers (I-5, OR-126, US-101 — ALWAYS English)
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
                    .put(
                        JSONArray()
                            .put("any")
                            .put(JSONArray().put("has").put("ref"))
                            .put(JSONArray().put("has").put("shield_text"))
                    )
                    .put(kindInFilter("highway", "major_road"))
            )
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("symbol-spacing", 320)
                put("text-field", highwayRef)
                put("text-font", JSONArray().put(FONT_MEDIUM))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(8).put(10.0)
                    .put(13).put(11.5)
                    .put(16).put(12.5)
                )
                put("text-rotation-alignment", "viewport")
                put("icon-image", IMAGE_HIGHWAY_SHIELD)
                put("icon-text-fit", "both")
                put("icon-text-fit-padding", JSONArray().put(2).put(6).put(2).put(6))
                put("icon-rotation-alignment", "viewport")
                put("text-padding", 6)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.shieldText)
                put("text-halo-color", palette.shieldHalo)
                put("text-halo-width", 0.6)
            })
        })

        // 6. Important Points of Interest (pois layer — high zoom only so map stays clean)
        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_pois")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "pois")
            put("minzoom", 16)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(
                        kindInFilter(
                            "hospital", "clinic", "pharmacy", "fuel", "charging_station",
                            "supermarket", "grocery", "school", "university", "college",
                            "library", "airport", "train_station", "bus_station", "parking",
                            "police", "fire_station", "post_office", "bank", "hotel",
                            "park", "restaurant", "cafe", "museum", "stadium"
                        )
                    )
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(FONT_REGULAR))
                put("text-size", 11.0)
                put("text-max-width", 8)
                put("text-padding", 6)
                put("text-optional", true)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.poiLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.6)
            })
        })

        // 7. Neighborhood / suburb names (places layer)
        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_places_neighbourhood")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "places")
            put("minzoom", 12)
            put("maxzoom", 16)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(kindInFilter("neighbourhood", "macrohood", "suburb", "borough"))
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(FONT_REGULAR))
                put("text-transform", "uppercase")
                put("text-letter-spacing", 0.08)
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(12).put(10.0)
                    .put(15).put(12.5)
                )
                put("text-max-width", 9)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.neighbourhoodLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 1.8)
            })
        })

        // 8. City, town, and village names (places layer, sized by importance)
        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_places_locality")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "places")
            put("minzoom", 5)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(kindEqFilter("locality"))
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(FONT_MEDIUM))
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(5).put(
                        JSONArray().put("case")
                            .put(JSONArray().put("==").put(JSONArray().put("get").put("kind_detail")).put("city")).put(13.0)
                            .put(10.5)
                    )
                    .put(10).put(
                        JSONArray().put("case")
                            .put(JSONArray().put("==").put(JSONArray().put("get").put("kind_detail")).put("city")).put(17.0)
                            .put(JSONArray().put("==").put(JSONArray().put("get").put("kind_detail")).put("town")).put(13.5)
                            .put(11.5)
                    )
                    .put(14).put(
                        JSONArray().put("case")
                            .put(JSONArray().put("==").put(JSONArray().put("get").put("kind_detail")).put("city")).put(20.0)
                            .put(JSONArray().put("==").put(JSONArray().put("get").put("kind_detail")).put("town")).put(15.5)
                            .put(13.0)
                    )
                )
                put("text-max-width", 9)
                put("text-padding", 4)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.cityLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 2.2)
            })
        })

        // 9. State / region names at low zoom
        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_places_region")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "places")
            put("minzoom", 3)
            put("maxzoom", 8)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(kindEqFilter("region"))
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(FONT_MEDIUM))
                put("text-transform", "uppercase")
                put("text-letter-spacing", 0.12)
                put("text-size", JSONArray()
                    .put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(3).put(10.0)
                    .put(7).put(14.0)
                )
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.neighbourhoodLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 2.0)
            })
        })
    }

    private fun addDevOnlineBasemapLayers(
        layers: JSONArray,
        sourceId: String,
        palette: MapPalette,
        language: AppLanguage
    ) {
        val localizedName = localizedPlaceNameExpr(language)
        val englishStreet = englishStreetNameExpr()

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
                put("line-width", 4.0)
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

        // Dev Online symbol labels (OpenMapTiles schema) — also placed BEFORE route & GPS layers
        layers.put(JSONObject().apply {
            put("id", "dev_label_water")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "water_name")
            put("minzoom", 9)
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(FONT_ITALIC))
                put("text-size", 12.0)
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
            put("minzoom", 13)
            put("layout", JSONObject().apply {
                put("symbol-placement", "line")
                put("text-field", englishStreet)
                put("text-font", JSONArray().put(FONT_REGULAR))
                put("text-size", 12.0)
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
                put("text-font", JSONArray().put(FONT_MEDIUM))
                put("text-size", 11.0)
                put("text-rotation-alignment", "viewport")
                put("icon-image", IMAGE_HIGHWAY_SHIELD)
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
                put("text-font", JSONArray().put(FONT_MEDIUM))
                put("text-size", 15.0)
            })
            put("paint", JSONObject().apply {
                put("text-color", palette.cityLabel)
                put("text-halo-color", palette.labelHalo)
                put("text-halo-width", 2.0)
            })
        })
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
        val boundary: String,
        val minorRoadLabel: String,
        val majorRoadLabel: String,
        val roadHalo: String,
        val cityLabel: String,
        val neighbourhoodLabel: String,
        val waterLabel: String,
        val parkLabel: String,
        val poiLabel: String,
        val labelHalo: String,
        val shieldText: String,
        val shieldHalo: String
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
        boundary = "#94A3B8",
        minorRoadLabel = "#334155",
        majorRoadLabel = "#0F172A",
        roadHalo = "#FFFFFF",
        cityLabel = "#0F172A",
        neighbourhoodLabel = "#475569",
        waterLabel = "#0369A1",
        parkLabel = "#166534",
        poiLabel = "#334155",
        labelHalo = "#FFFFFF",
        shieldText = "#FFFFFF",
        shieldHalo = "#0F172A"
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
        boundary = "#475569",
        minorRoadLabel = "#CBD5E1",
        majorRoadLabel = "#F8FAFC",
        roadHalo = "#091022",
        cityLabel = "#F8FAFC",
        neighbourhoodLabel = "#94A3B8",
        waterLabel = "#7DD3FC",
        parkLabel = "#86EFAC",
        poiLabel = "#CBD5E1",
        labelHalo = "#091022",
        shieldText = "#F8FAFC",
        shieldHalo = "#091022"
    )
}
