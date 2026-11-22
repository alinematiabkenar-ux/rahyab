package com.example.data.search

import com.example.data.online.OnlineHttpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TEMPORARY PHASE 1 DEVELOPER TEST CLIENT (ONLY active when the developer explicitly enables
 * "Oregon Online Test Mode" in Settings before local state packs have been built/imported).
 *
 * Queries free, keyless public OpenStreetMap search endpoints (Photon by Komoot + Nominatim)
 * bounded to Oregon so you can verify live map display and the "Best Buy" near Eugene, OR
 * (as well as Persian queries like "بست بای", "پمپ بنزین", "داروخانه") right away on the emulator.
 */
class OnlineOregonDevSearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun searchOregonOnline(
        rawQuery: String,
        userLat: Double,
        userLon: Double,
        limit: Int = 20
    ): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        val expanded = PersianQueryNormalizer.expandQuery(rawQuery)
        val searchTerm = when {
            expanded.normalizedLatinQueries.isNotEmpty() -> expanded.normalizedLatinQueries.first()
            expanded.matchedCategoryTags.isNotEmpty() -> expanded.matchedCategoryTags.first().replace("_", " ")
            else -> rawQuery.trim()
        }
        if (searchTerm.isBlank()) return@withContext emptyList()

        val combined = mutableListOf<PlaceSearchResult>()

        // 1. Query Photon (Komoot public OSM search) biased near user location in Oregon
        runCatching {
            val photonUrl = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
                .addQueryParameter("q", searchTerm)
                .addQueryParameter("lat", userLat.toString())
                .addQueryParameter("lon", userLon.toString())
                .addQueryParameter("bbox", "$OR_MIN_LON,$OR_MIN_LAT,$OR_MAX_LON,$OR_MAX_LAT")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("lang", "en")
                .build()

            val req = Request.Builder()
                .url(photonUrl)
                .header("User-Agent", OnlineHttpConfig.userAgent())
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    combined.addAll(parsePhotonGeoJson(body, userLat, userLon, searchTerm))
                }
            }
        }

        // 2. Also query OpenStreetMap Nominatim bounded to Oregon when Photon has few hits
        //    or for exact brand POI matching (e.g. "Best Buy" near Eugene/Springfield OR)
        if (combined.size < 5) {
            runCatching {
                val nomUrl = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", searchTerm)
                    .addQueryParameter("format", "jsonv2")
                    .addQueryParameter("addressdetails", "1")
                    .addQueryParameter("countrycodes", "us")
                    .addQueryParameter("viewbox", "$OR_MIN_LON,$OR_MAX_LAT,$OR_MAX_LON,$OR_MIN_LAT")
                    .addQueryParameter("bounded", "1")
                    .addQueryParameter("limit", limit.toString())
                    .build()

                val req = Request.Builder()
                    .url(nomUrl)
                    .header("User-Agent", OnlineHttpConfig.userAgent())
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        combined.addAll(parseNominatimJson(body, userLat, userLon, searchTerm))
                    }
                }
            }
        }

        // Deduplicate & rank by text relevance + proximity to user
        val sorted = combined.sortedByDescending { it.relevanceScore }
        val deduped = mutableListOf<PlaceSearchResult>()
        for (item in sorted) {
            val isDup = deduped.any { existing ->
                existing.name.equals(item.name, ignoreCase = true) &&
                    OfflinePlaceSearchEngine.haversineMeters(existing.lat, existing.lon, item.lat, item.lon) < 120.0
            }
            if (!isDup) deduped.add(item)
        }
        deduped.take(limit)
    }

    private fun parsePhotonGeoJson(
        json: String,
        userLat: Double,
        userLon: Double,
        query: String
    ): List<PlaceSearchResult> {
        val root = JSONObject(json)
        val features = root.optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<PlaceSearchResult>()

        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val props = f.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name", "").ifBlank {
                listOf(props.optString("housenumber"), props.optString("street"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            if (name.isBlank()) continue

            val streetLine = listOf(props.optString("housenumber"), props.optString("street"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val city = props.optString("city", props.optString("town", props.optString("village", "")))
            val state = props.optString("state", "OR")
            val postcode = props.optString("postcode", "")
            val category = props.optString("osm_value", props.optString("type", "place"))

            val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, lat, lon)
            val sim = OfflinePlaceSearchEngine.normalizedSimilarity(query, name)
            val score = sim * 85.0 + (60.0 / (1.0 + dist / 15000.0))

            out.add(
                PlaceSearchResult(
                    id = "dev_photon_${props.optLong("osm_id", i.toLong())}_$i",
                    name = name,
                    brand = if (sim > 0.75) name else "",
                    category = category,
                    address = streetLine,
                    city = city,
                    stateCode = if (state.equals("Oregon", true)) "OR" else state.take(2).uppercase(),
                    postcode = postcode,
                    lat = lat,
                    lon = lon,
                    distanceMeters = dist,
                    relevanceScore = score,
                    source = "dev_online_osm"
                )
            )
        }
        return out
    }

    private fun parseNominatimJson(
        json: String,
        userLat: Double,
        userLon: Double,
        query: String
    ): List<PlaceSearchResult> {
        val arr = JSONArray(json)
        val out = mutableListOf<PlaceSearchResult>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optString("lat").toDoubleOrNull() ?: continue
            val lon = obj.optString("lon").toDoubleOrNull() ?: continue
            val addr = obj.optJSONObject("address") ?: JSONObject()

            val name = obj.optString("name", "").ifBlank {
                obj.optString("display_name", "").substringBefore(",")
            }
            if (name.isBlank()) continue

            val house = addr.optString("house_number", "")
            val road = addr.optString("road", "")
            val streetLine = listOf(house, road).filter { it.isNotBlank() }.joinToString(" ")
            val city = addr.optString("city", addr.optString("town", addr.optString("village", "")))
            val postcode = addr.optString("postcode", "")
            val category = obj.optString("type", obj.optString("category", "place"))

            val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, lat, lon)
            val sim = OfflinePlaceSearchEngine.normalizedSimilarity(query, name)
            val score = sim * 90.0 + (60.0 / (1.0 + dist / 15000.0))

            out.add(
                PlaceSearchResult(
                    id = "dev_nom_${obj.optLong("place_id", i.toLong())}",
                    name = name,
                    brand = if (sim > 0.75) name else "",
                    category = category,
                    address = streetLine.ifBlank { obj.optString("display_name", "").substringAfter(", ", "") },
                    city = city,
                    stateCode = "OR",
                    postcode = postcode,
                    lat = lat,
                    lon = lon,
                    distanceMeters = dist,
                    relevanceScore = score,
                    source = "dev_online_osm"
                )
            )
        }
        return out
    }

    companion object {
        private const val OR_MIN_LON = -124.5662
        private const val OR_MIN_LAT = 41.9918
        private const val OR_MAX_LON = -116.4635
        private const val OR_MAX_LAT = 46.2920
    }
}
