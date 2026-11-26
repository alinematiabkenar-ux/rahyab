package com.example.data.search

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.packs.ExtrasDbReader
import java.io.File

/**
 * D5: Prefix search against USGS GNIS `gnis_names` + FTS5 `gnis_fts` tables in `<state>_extras.db`.
 * Results represent natural features and landmarks (summits, lakes, parks, trails, bridges, etc.),
 * sorted strictly by distance from the user and appended after places and addresses.
 */
object GnisPlaceSearchHelper {
    const val SOURCE_GNIS_LANDMARK = "gnis_landmark"

    fun buildPrefixMatchExpression(query: String): String {
        val tokens = query.trim()
            .split(Regex("[\\s,.-]+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_]"), "") }
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }
    }

    fun searchInFiles(
        extrasDbFiles: List<Pair<String, File>>,
        queries: List<String>,
        userLat: Double,
        userLon: Double,
        sqliteDriver: SQLiteDriver = BundledSQLiteDriver(),
        limit: Int = 15
    ): List<PlaceSearchResult> {
        if (extrasDbFiles.isEmpty() || queries.isEmpty()) return emptyList()
        val reader = ExtrasDbReader(sqliteDriver = sqliteDriver)
        val seen = linkedMapOf<String, PlaceSearchResult>()

        for (q in queries) {
            val matchExpr = buildPrefixMatchExpression(q)
            if (matchExpr.isEmpty()) continue
            val records = reader.queryGnisNamesInFiles(
                files = extrasDbFiles,
                prefixMatchExpr = matchExpr,
                userLat = userLat,
                userLon = userLon,
                limitPerPack = limit * 2
            )
            for (rec in records) {
                val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, rec.lat, rec.lon)
                val key = "${rec.name.lowercase()}_${rec.featureClass.lowercase()}_${(rec.lat * 1000).toInt()}_${(rec.lon * 1000).toInt()}"
                if (seen.containsKey(key)) continue
                val countyDisplay = if (rec.county.isNotBlank()) "${rec.county} County" else ""
                seen[key] = PlaceSearchResult(
                    id = "gnis_${rec.stateCode.lowercase()}_${rec.id}",
                    name = rec.name,
                    brand = "",
                    category = rec.featureClass,
                    address = rec.featureClass,
                    city = countyDisplay,
                    stateCode = rec.stateCode.uppercase(),
                    postcode = "",
                    lat = rec.lat,
                    lon = rec.lon,
                    distanceMeters = dist,
                    relevanceScore = 10.0 / (1.0 + dist / 10_000.0),
                    source = SOURCE_GNIS_LANDMARK
                )
            }
        }

        return seen.values
            .sortedBy { it.distanceMeters }
            .take(limit)
    }
}
