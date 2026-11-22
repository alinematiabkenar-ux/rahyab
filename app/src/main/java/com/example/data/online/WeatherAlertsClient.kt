package com.example.data.online

import android.content.Context
import com.example.navigation.LatLon
import com.example.navigation.OfflineRoute
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.example.util.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class WeatherAlertItem(
    val id: String,
    val title: String,
    val area: String,
    val endsText: String,
    val severity: String,
    val isAlongRoute: Boolean = false
)

/**
 * Fetches severe driving weather alerts from the US National Weather Service (api.weather.gov)
 * with no API key, a 5-second timeout, and the standard RahYab User-Agent.
 * Failures are logged silently and never interrupt offline navigation.
 */
class WeatherAlertsClient(private val context: Context) {

    private val client = OnlineHttpConfig.createShortTimeoutClient(timeoutSeconds = 5L)

    companion object {
        private const val ROUTE_SAMPLE_INTERVAL_METERS = 48_280.0 // ~30 miles
        private const val MAX_ROUTE_SAMPLE_POINTS = 6

        private val SEVERE_DRIVING_KEYWORDS = listOf(
            "winter storm",
            "ice storm",
            "freezing rain",
            "freezing drizzle",
            "freezing fog",
            "blizzard",
            "snow",
            "sleet",
            "winter weather",
            "flood",
            "flash flood",
            "dense fog",
            "fog",
            "high wind",
            "wind advisory",
            "tornado",
            "severe thunderstorm",
            "extreme heat",
            "excessive heat",
            "heat advisory",
            "wildfire",
            "fire weather",
            "red flag",
            "smoke",
            "air quality",
            "dust storm",
            "blowing dust",
            "hurricane",
            "tropical storm",
            "avalanche",
            "ice"
        )

        fun sampleRoutePointsEvery30Miles(route: OfflineRoute?): List<LatLon> {
            val shape = route?.shape ?: return emptyList()
            if (shape.size < 2) return emptyList()
            val samples = mutableListOf<LatLon>()
            var accumulated = 0.0
            var nextSampleDist = ROUTE_SAMPLE_INTERVAL_METERS
            for (i in 1 until shape.size) {
                accumulated += haversineMeters(
                    shape[i - 1].lat, shape[i - 1].lon,
                    shape[i].lat, shape[i].lon
                )
                if (accumulated >= nextSampleDist) {
                    samples.add(shape[i])
                    if (samples.size >= MAX_ROUTE_SAMPLE_POINTS) break
                    nextSampleDist += ROUTE_SAMPLE_INTERVAL_METERS
                }
            }
            if (samples.size < MAX_ROUTE_SAMPLE_POINTS) {
                val dest = shape.last()
                val lastAdded = samples.lastOrNull()
                if (lastAdded == null || haversineMeters(lastAdded.lat, lastAdded.lon, dest.lat, dest.lon) > 15_000.0) {
                    samples.add(dest)
                }
            }
            return samples
        }

        private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2.0 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        fun isSevereDrivingAlert(event: String, severity: String): Boolean {
            val lowerEvent = event.lowercase(Locale.US)
            if (SEVERE_DRIVING_KEYWORDS.any { lowerEvent.contains(it) }) return true
            val lowerSeverity = severity.lowercase(Locale.US)
            return lowerSeverity == "extreme" || lowerSeverity == "severe"
        }

        fun formatEndTime(isoRaw: String?): String {
            if (isoRaw.isNullOrBlank() || isoRaw.equals("null", ignoreCase = true)) return ""
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            )
            for (pattern in patterns) {
                val parsed = runCatching {
                    val parser = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    parser.parse(isoRaw)
                }.getOrNull()
                if (parsed != null) {
                    val out = SimpleDateFormat("EEE h:mm a", Locale.US).apply {
                        timeZone = TimeZone.getDefault()
                    }
                    return out.format(parsed)
                }
            }
            return isoRaw.take(16).replace('T', ' ')
        }
    }

    suspend fun fetchAlertsForPositionAndRoute(
        userLat: Double,
        userLon: Double,
        activeRoute: OfflineRoute?
    ): Result<List<WeatherAlertItem>> = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, WeatherAlertItem>()
        var anySucceeded = false
        var lastError: Throwable? = null

        val userRes = fetchPointAlerts(userLat, userLon, isAlongRoute = false)
        userRes.onSuccess { list ->
            anySucceeded = true
            list.forEach { merged.putIfAbsent(it.id, it) }
        }.onFailure { err ->
            lastError = err
        }

        val routePoints = sampleRoutePointsEvery30Miles(activeRoute)
        for (pt in routePoints) {
            if (haversineMeters(userLat, userLon, pt.lat, pt.lon) < 20_000.0) continue
            val ptRes = fetchPointAlerts(pt.lat, pt.lon, isAlongRoute = true)
            ptRes.onSuccess { list ->
                anySucceeded = true
                list.forEach { merged.putIfAbsent(it.id, it) }
            }.onFailure { err ->
                lastError = err
            }
        }

        if (anySucceeded) {
            val severityRank = mapOf("Extreme" to 0, "Severe" to 1, "Moderate" to 2)
            val sorted = merged.values.sortedWith(
                compareBy<WeatherAlertItem> { severityRank[it.severity] ?: 3 }
                    .thenBy { it.isAlongRoute }
            )
            Result.success(sorted)
        } else {
            Result.failure(lastError ?: IllegalStateException("Weather alerts request failed"))
        }
    }

    private fun fetchPointAlerts(
        lat: Double,
        lon: Double,
        isAlongRoute: Boolean
    ): Result<List<WeatherAlertItem>> {
        val latStr = String.format(Locale.US, "%.4f", lat)
        val lonStr = String.format(Locale.US, "%.4f", lon)
        val url = "https://api.weather.gov/alerts/active?point=$latStr,$lonStr"
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/geo+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} from api.weather.gov")
                }
                val body = response.body?.string().orEmpty()
                parseGeoJsonAlerts(body, isAlongRoute)
            }
        }.onFailure { err ->
            CrashLogManager.logError(context, "WeatherAlertsClient", "Failed to fetch NWS alerts for $latStr,$lonStr", err)
        }
    }

    internal fun parseGeoJsonAlerts(jsonText: String, isAlongRoute: Boolean): List<WeatherAlertItem> {
        if (jsonText.isBlank()) return emptyList()
        val root = JSONObject(jsonText)
        val features = root.optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<WeatherAlertItem>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val status = props.optString("status", "")
            if (status.equals("Test", ignoreCase = true)) continue
            val event = props.optString("event", "").trim()
            val severity = props.optString("severity", "").trim()
            if (event.isBlank() || !isSevereDrivingAlert(event, severity)) continue

            val id = props.optString("id", "").ifBlank { feature.optString("id", "$event-$i") }
            val area = props.optString("areaDesc", "").trim()
            val rawEnds = props.optString("ends", "").takeIf { it.isNotBlank() && it != "null" }
                ?: props.optString("expires", "")
            val endsFormatted = formatEndTime(rawEnds)
            results.add(
                WeatherAlertItem(
                    id = id,
                    title = event,
                    area = area,
                    endsText = endsFormatted,
                    severity = severity,
                    isAlongRoute = isAlongRoute
                )
            )
        }
        return results
    }
}
