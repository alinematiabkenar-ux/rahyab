package com.example.data.online

import android.content.Context
import android.net.Uri
import com.example.BuildConfig
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PlaceSearchResult
import com.example.util.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * Explicit, user-triggered online destination search powered by Google Maps Platform
 * Places API (New) (`places:searchText`).
 *
 * Offline-first & Google Maps Platform Terms of Service compliance:
 *  - NEVER called automatically during local search, typing, routing, or offline trips.
 *  - Only invoked when the user explicitly taps "Search Google Maps (Online)" while online,
 *    the feature is enabled in Settings, and `BuildConfig.GOOGLE_MAPS_API_KEY` is configured.
 *  - Results are strictly ephemeral in memory and are NEVER persisted, indexed, or bulk-cached
 *    into RahYab's offline state packs or local SQLite databases.
 *  - Displayed results include required "Powered by Google" attribution.
 *  - Short 5-second timeout ensures slow or unavailable networks never block local search
 *    or active navigation.
 */
class GoogleMapsPlacesClient(
    private val context: Context,
    private val apiKeyProvider: () -> String = { readOptionalBuildConfigApiKey() },
    private val httpClient: OkHttpClient? = null
) {
    private val lazyHttpClient: OkHttpClient by lazy {
        httpClient ?: OnlineHttpConfig.createShortTimeoutClient(timeoutSeconds = 5L)
    }

    val isConfigured: Boolean
        get() = isApiKeyConfigured(apiKeyProvider())

    suspend fun searchText(
        query: String,
        biasLat: Double,
        biasLon: Double,
        limit: Int = 8
    ): Result<List<PlaceSearchResult>> = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.length < 2) return@withContext Result.success(emptyList())

        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured(apiKey)) {
            return@withContext Result.failure(IllegalStateException(ERROR_API_KEY_NOT_CONFIGURED))
        }

        val safeLimit = limit.coerceIn(1, 10)
        val payload = buildSearchTextRequestJson(
            query = cleaned,
            biasLat = biasLat,
            biasLon = biasLon,
            maxResultCount = safeLimit
        )

        try {
            val request = Request.Builder()
                .url(PLACES_SEARCH_TEXT_ENDPOINT)
                .header("User-Agent", OnlineHttpConfig.userAgent())
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", PLACES_FIELD_MASK)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            lazyHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMsg = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                    }.getOrDefault("")
                    val msg = if (apiMsg.isNotBlank()) "Google Places HTTP ${response.code}: $apiMsg" else "Google Places HTTP ${response.code}"
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val parsed = parseSearchTextResponse(body, biasLat, biasLon, safeLimit)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            CrashLogManager.logError(context, "GoogleMapsPlaces", "Online Google Places search failed", e)
            Result.failure(e)
        }
    }

    companion object {
        const val SOURCE_ONLINE_GOOGLE_MAPS = "online_google_maps"
        const val ERROR_API_KEY_NOT_CONFIGURED = "GOOGLE_MAPS_API_KEY_NOT_CONFIGURED"
        const val PLACES_SEARCH_TEXT_ENDPOINT = "https://places.googleapis.com/v1/places:searchText"
        const val PLACES_FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location,places.primaryTypeDisplayName,places.addressComponents"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun readOptionalBuildConfigApiKey(): String = runCatching {
            val field = BuildConfig::class.java.getField("GOOGLE_MAPS_API_KEY")
            (field.get(null) as? String).orEmpty()
        }.getOrDefault("")

        fun isOptionalKeyConfigured(): Boolean = isApiKeyConfigured(readOptionalBuildConfigApiKey())

        fun isApiKeyConfigured(rawKey: String?): Boolean {
            val key = rawKey?.trim().orEmpty()
            if (key.length < 10) return false
            val upper = key.uppercase(Locale.US)
            if (upper.startsWith("YOUR_") || upper.startsWith("MY_") || upper.contains("PLACEHOLDER")) {
                return false
            }
            return true
        }

        fun buildSearchTextRequestJson(
            query: String,
            biasLat: Double,
            biasLon: Double,
            maxResultCount: Int = 8,
            radiusMeters: Double = 50_000.0
        ): String {
            val center = JSONObject()
                .put("latitude", biasLat)
                .put("longitude", biasLon)
            val circle = JSONObject()
                .put("center", center)
                .put("radius", radiusMeters.coerceIn(1_000.0, 50_000.0))
            val bias = JSONObject().put("circle", circle)
            return JSONObject()
                .put("textQuery", query)
                .put("maxResultCount", maxResultCount.coerceIn(1, 10))
                .put("locationBias", bias)
                .toString()
        }

        fun parseSearchTextResponse(
            jsonBody: String,
            biasLat: Double,
            biasLon: Double,
            limit: Int = 8
        ): List<PlaceSearchResult> {
            if (jsonBody.isBlank()) return emptyList()
            val root = JSONObject(jsonBody)
            val places = root.optJSONArray("places") ?: return emptyList()
            val out = mutableListOf<PlaceSearchResult>()

            for (i in 0 until minOf(places.length(), limit)) {
                val item = places.optJSONObject(i) ?: continue
                val placeId = item.optString("id").trim()
                val displayName = item.optJSONObject("displayName")?.optString("text")?.trim().orEmpty()
                val formattedAddress = item.optString("formattedAddress").trim()
                val location = item.optJSONObject("location") ?: continue
                val lat = location.optDouble("latitude", Double.NaN)
                val lon = location.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue

                val category = item.optJSONObject("primaryTypeDisplayName")?.optString("text")?.trim().orEmpty()
                val stateCode = extractStateShortCode(item)
                val dist = OfflinePlaceSearchEngine.haversineMeters(biasLat, biasLon, lat, lon)
                val title = displayName.ifBlank { formattedAddress.ifBlank { "Google Maps Place" } }

                out.add(
                    PlaceSearchResult(
                        id = if (placeId.isNotBlank()) "gmaps_$placeId" else "gmaps_${i}_${lat.toBits()}",
                        name = title,
                        brand = "",
                        category = category,
                        address = formattedAddress,
                        city = "",
                        stateCode = stateCode,
                        postcode = "",
                        lat = lat,
                        lon = lon,
                        distanceMeters = dist,
                        relevanceScore = (42.0 - i * 1.5).coerceAtLeast(10.0),
                        source = SOURCE_ONLINE_GOOGLE_MAPS
                    )
                )
            }
            return out
        }

        private fun extractStateShortCode(placeObj: JSONObject): String {
            val components = placeObj.optJSONArray("addressComponents") ?: return ""
            for (i in 0 until components.length()) {
                val comp = components.optJSONObject(i) ?: continue
                val types = comp.optJSONArray("types") ?: continue
                for (j in 0 until types.length()) {
                    if (types.optString(j) == "administrative_area_level_1") {
                        return comp.optString("shortText").trim().lowercase(Locale.US)
                    }
                }
            }
            return ""
        }

        fun buildGoogleMapsExternalUrl(place: PlaceSearchResult): String {
            val query = Uri.encode("${place.lat},${place.lon}")
            val rawPlaceId = place.id.removePrefix("gmaps_").takeIf {
                place.source == SOURCE_ONLINE_GOOGLE_MAPS && it.isNotBlank() && !it.contains("_")
            }
            return if (rawPlaceId != null) {
                "https://www.google.com/maps/search/?api=1&query=$query&query_place_id=${Uri.encode(rawPlaceId)}"
            } else {
                "https://www.google.com/maps/search/?api=1&query=$query"
            }
        }
    }
}
