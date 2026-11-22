package com.example.data.online

import android.content.Context
import android.os.SystemClock
import com.example.data.search.PlaceSearchResult
import com.example.util.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optional online search fallback powered by Komoot Photon (https://photon.komoot.io/api/).
 * Requires NO API key, enforces a strict >= 1 second rate limit, uses a 5s timeout and the
 * standard RahYab User-Agent, and fails silently when offline or unreachable.
 */
class PhotonSearchClient(private val context: Context) {

    private val client = OnlineHttpConfig.createShortTimeoutClient(timeoutSeconds = 5L)

    @Volatile
    private var lastRequestElapsedMs: Long = 0L

    companion object {
        const val SOURCE_ONLINE_PHOTON = "online_photon"
        private const val MIN_REQUEST_INTERVAL_MS = 1_000L
    }

    suspend fun search(
        query: String,
        biasLat: Double,
        biasLon: Double,
        limit: Int = 10
    ): Result<List<PlaceSearchResult>> = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.length < 2) return@withContext Result.success(emptyList())

        // Enforce strict rate limit: at most 1 request per second
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedSinceLast = nowElapsed - lastRequestElapsedMs
        if (elapsedSinceLast in 0 until MIN_REQUEST_INTERVAL_MS) {
            return@withContext Result.success(emptyList())
        }
        lastRequestElapsedMs = nowElapsed

        runCatching {
            val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
                .addQueryParameter("q", cleaned)
                .addQueryParameter("lat", String.format(Locale.US, "%.5f", biasLat))
                .addQueryParameter("lon", String.format(Locale.US, "%.5f", biasLon))
                .addQueryParameter("limit", limit.coerceIn(1, 15).toString())
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} from photon.komoot.io")
                }
                val body = response.body?.string().orEmpty()
                parsePhotonGeoJson(body, biasLat, biasLon)
            }
        }.onFailure { err ->
            CrashLogManager.logError(context, "PhotonSearchClient", "Photon fallback search failed", err)
        }
    }

    internal fun parsePhotonGeoJson(
        jsonText: String,
        biasLat: Double,
        biasLon: Double
    ): List<PlaceSearchResult> {
        if (jsonText.isBlank()) return emptyList()
        val root = JSONObject(jsonText)
        val features = root.optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<PlaceSearchResult>()
        val seenKeys = mutableSetOf<String>()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val props = feature.optJSONObject("properties") ?: continue
            val name = props.optString("name", "").trim()
            val houseNumber = props.optString("housenumber", "").trim()
            val street = props.optString("street", "").trim()
            val city = props.optString("city", "").ifBlank {
                props.optString("town", "").ifBlank { props.optString("village", "") }
            }.trim()
            val state = props.optString("state", "").trim()
            val postcode = props.optString("postcode", "").trim()
            val osmValue = props.optString("osm_value", "").trim()
            val osmKey = props.optString("osm_key", "").trim()

            val streetLine = listOf(houseNumber, street).filter { it.isNotBlank() }.joinToString(" ")
            val displayName = name.ifBlank { streetLine }.ifBlank { city }
            if (displayName.isBlank()) continue

            val addressParts = buildList {
                if (streetLine.isNotBlank() && !streetLine.equals(displayName, ignoreCase = true)) {
                    add(streetLine)
                }
                if (city.isNotBlank() && !city.equals(displayName, ignoreCase = true)) {
                    add(city)
                }
                val stateZip = listOf(state, postcode).filter { it.isNotBlank() }.joinToString(" ")
                if (stateZip.isNotBlank()) add(stateZip)
            }
            val address = addressParts.joinToString(", ")

            val dedupKey = "${displayName.lowercase(Locale.US)}|${String.format(Locale.US, "%.4f,%.4f", lat, lon)}"
            if (!seenKeys.add(dedupKey)) continue

            val distMeters = haversineMeters(biasLat, biasLon, lat, lon)
            val category = osmValue.ifBlank { osmKey }.replace('_', ' ').ifBlank { "place" }

            val osmId = props.optLong("osm_id", 0L)
            val resolvedStateCode = com.example.data.model.UsStatesCatalog
                .findBestStateForLocation(lat, lon)?.code.orEmpty()

            results.add(
                PlaceSearchResult(
                    id = "photon_${osmId}_${i}_${lat}_${lon}",
                    name = displayName,
                    brand = "",
                    category = category,
                    address = address,
                    city = city,
                    stateCode = resolvedStateCode.ifBlank { state.take(2).lowercase(Locale.US) },
                    postcode = postcode,
                    lat = lat,
                    lon = lon,
                    distanceMeters = distMeters,
                    relevanceScore = (50.0 - i * 2.0).coerceAtLeast(10.0),
                    source = SOURCE_ONLINE_PHOTON
                )
            )
        }
        return results
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2.0 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
