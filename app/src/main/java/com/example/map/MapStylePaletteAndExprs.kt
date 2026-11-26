package com.example.map

import com.example.i18n.AppLanguage
import org.json.JSONArray

/**
 * Pure mechanical extraction from [MapStyleBuilder] (R2):
 * day/night palettes and shared MapLibre expression builders.
 */
internal data class MapPalette(
    val background: String,
    val land: String,
    val park: String,
    val water: String,
    val building: String,
    val building3d: String,
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

internal object MapStylePaletteAndExprs {

    val DayMapPalette = MapPalette(
        background = "#E6EDF5",
        land = "#F4F6F8",
        park = "#CBE8CD",
        water = "#A8D5F2",
        building = "#DCE2EA",
        building3d = "#CBD5E1",
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

    val NightMapPalette = MapPalette(
        background = "#091022",
        land = "#0E172E",
        park = "#112B2A",
        water = "#071E3D",
        building = "#182442",
        building3d = "#24355B",
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

    val NightCarMapPalette = NightMapPalette.copy(
        minorRoad = "#3E5682",
        majorRoad = "#5470A3"
    )

    fun protomapsKindExpr(): JSONArray =
        JSONArray()
            .put("coalesce")
            .put(JSONArray().put("get").put("kind"))
            .put(JSONArray().put("get").put("pmap:kind"))

    fun kindInFilter(vararg kinds: String): JSONArray {
        val lit = JSONArray()
        kinds.forEach { lit.put(it) }
        return JSONArray()
            .put("in")
            .put(protomapsKindExpr())
            .put(JSONArray().put("literal").put(lit))
    }

    fun kindEqFilter(kind: String): JSONArray =
        JSONArray()
            .put("==")
            .put(protomapsKindExpr())
            .put(kind)

    fun englishStreetNameExpr(): JSONArray =
        JSONArray()
            .put("coalesce")
            .put(JSONArray().put("get").put("name:en"))
            .put(JSONArray().put("get").put("name"))

    fun highwayRefExpr(): JSONArray =
        JSONArray()
            .put("coalesce")
            .put(JSONArray().put("get").put("ref"))
            .put(JSONArray().put("get").put("shield_text"))

    fun localizedPlaceNameExpr(language: AppLanguage): JSONArray =
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
}
