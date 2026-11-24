package com.example.map

import com.example.i18n.AppLanguage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mechanical extraction from [MapStyleBuilder] (R2):
 * Protomaps POI, neighbourhood, locality (city/town), and region symbol layers.
 */
internal object MapStyleProtomapsPlaceLabels {

    fun addPlaceAndPoiLabels(
        layers: JSONArray,
        sourceId: String,
        palette: MapPalette,
        language: AppLanguage
    ) {
        val localizedName = MapStylePaletteAndExprs.localizedPlaceNameExpr(language)

        val poiIconMatchExpr = JSONArray()
            .put("match")
            .put(MapStylePaletteAndExprs.protomapsKindExpr())
            .put("fuel").put(MapStyleBuilder.IMAGE_POI_GAS)
            .put("charging_station").put(MapStyleBuilder.IMAGE_POI_EV)
            .put("parking").put(MapStyleBuilder.IMAGE_POI_PARKING)
            .put(JSONArray().put("restaurant").put("fast_food")).put(MapStyleBuilder.IMAGE_POI_RESTAURANT)
            .put("cafe").put(MapStyleBuilder.IMAGE_POI_CAFE)
            .put(JSONArray().put("supermarket").put("grocery").put("convenience")).put(MapStyleBuilder.IMAGE_POI_GROCERY)
            .put("pharmacy").put(MapStyleBuilder.IMAGE_POI_PHARMACY)
            .put(JSONArray().put("hospital").put("clinic")).put(MapStyleBuilder.IMAGE_POI_HOSPITAL)
            .put(JSONArray().put("school").put("university").put("college")).put(MapStyleBuilder.IMAGE_POI_SCHOOL)
            .put(JSONArray().put("bank").put("atm")).put(MapStyleBuilder.IMAGE_POI_BANK)
            .put("")

        layers.put(JSONObject().apply {
            put("id", "${sourceId}_label_pois")
            put("type", "symbol")
            put("source", sourceId)
            put("source-layer", "pois")
            put("minzoom", 15)
            put(
                "filter",
                JSONArray()
                    .put("all")
                    .put(JSONArray().put("has").put("name"))
                    .put(
                        MapStylePaletteAndExprs.kindInFilter(
                            "hospital", "clinic", "pharmacy", "fuel", "charging_station",
                            "supermarket", "grocery", "convenience", "school", "university", "college",
                            "parking", "bank", "atm", "restaurant", "fast_food", "cafe",
                            "library", "airport", "train_station", "bus_station",
                            "police", "fire_station", "post_office", "hotel", "museum", "stadium"
                        )
                    )
            )
            put("layout", JSONObject().apply {
                put("icon-image", poiIconMatchExpr)
                put("icon-size", 0.65)
                put("icon-allow-overlap", false)
                put("icon-padding", 4)
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_REGULAR))
                put("text-size", 11.0)
                put("text-anchor", "top")
                put("text-offset", JSONArray().put(0.0).put(0.95))
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
                    .put(MapStylePaletteAndExprs.kindInFilter("neighbourhood", "macrohood", "suburb", "borough"))
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_REGULAR))
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
                    .put(MapStylePaletteAndExprs.kindEqFilter("locality"))
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_MEDIUM))
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
                    .put(MapStylePaletteAndExprs.kindEqFilter("region"))
            )
            put("layout", JSONObject().apply {
                put("text-field", localizedName)
                put("text-font", JSONArray().put(MapStyleBuilder.FONT_MEDIUM))
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
}
