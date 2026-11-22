package com.example.navigation

import android.content.Intent
import android.net.Uri
import com.example.data.model.UsStateInfo
import com.example.data.model.UsStatesCatalog
import com.example.data.online.OnlineHttpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale
import java.util.regex.Pattern

sealed class ParsedExternalMapTarget {
    data class Coordinates(
        val lat: Double,
        val lon: Double,
        val label: String? = null
    ) : ParsedExternalMapTarget()

    data class SearchQuery(
        val query: String
    ) : ParsedExternalMapTarget()

    data class ShortLink(
        val url: String,
        val fallbackText: String? = null
    ) : ParsedExternalMapTarget()

    data object None : ParsedExternalMapTarget()
}

/**
 * Parses incoming Android `ACTION_VIEW` (`geo:`, Google Maps, Apple Maps, `maps.app.goo.gl`)
 * and `ACTION_SEND` (`text/plain`) intents into coordinates, offline search queries, or
 * expandable short map links.
 */
object ExternalMapIntentParser {

    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val AT_COORDS_REGEX = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val BANG_PIN_COORDS_REGEX = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val COORD_PAIR_REGEX = Regex("""^\s*(?:loc:)?(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\(([^)]*)\))?\s*$""")
    private val EMBEDDED_COORD_PAIR_REGEX = Regex("""(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})""")
    private val GOOGLE_PLACE_PATH_REGEX = Regex("""/maps/place/([^/@?]+)""", RegexOption.IGNORE_CASE)

    fun parseIntent(intent: Intent?): ParsedExternalMapTarget {
        if (intent == null) return ParsedExternalMapTarget.None
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return ParsedExternalMapTarget.None
                parseUri(uri, fallbackText = null)
            }
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") != true) return ParsedExternalMapTarget.None
                val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
                parseSharedText(rawText, subject)
            }
            else -> ParsedExternalMapTarget.None
        }
    }

    fun parseSharedText(rawText: String, subject: String = ""): ParsedExternalMapTarget {
        val trimmed = rawText.trim()
        if (trimmed.isBlank() && subject.isBlank()) return ParsedExternalMapTarget.None

        val matchedUrl = URL_REGEX.find(trimmed)?.value?.trimEnd('.', ',', ')', ']')
        val textWithoutUrl = trimmed
            .replace(URL_REGEX, " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("Shared route", ignoreCase = true) }
            .joinToString(", ")
            .trim()
            .ifBlank { subject.trim() }
            .takeIf { it.isNotBlank() }

        if (matchedUrl != null) {
            val uri = runCatching { Uri.parse(matchedUrl) }.getOrNull()
            if (uri != null) {
                val parsed = parseUri(uri, fallbackText = textWithoutUrl)
                if (parsed !is ParsedExternalMapTarget.None) return parsed
            }
        }

        // Check raw coordinates like "44.0521, -123.0868"
        val directCoord = parseCoordinatePair(trimmed)
        if (directCoord != null) {
            return ParsedExternalMapTarget.Coordinates(
                lat = directCoord.first,
                lon = directCoord.second,
                label = directCoord.third ?: subject.takeIf { it.isNotBlank() }
            )
        }
        EMBEDDED_COORD_PAIR_REGEX.find(trimmed)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                return ParsedExternalMapTarget.Coordinates(
                    lat = lat,
                    lon = lon,
                    label = subject.takeIf { it.isNotBlank() }
                )
            }
        }

        val cleanQuery = (textWithoutUrl ?: trimmed).trim()
        return if (cleanQuery.isNotBlank()) {
            ParsedExternalMapTarget.SearchQuery(cleanQuery)
        } else {
            ParsedExternalMapTarget.None
        }
    }

    fun parseUri(uri: Uri, fallbackText: String? = null): ParsedExternalMapTarget {
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme == "geo") {
            return parseGeoUri(uri)
        }

        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val fullUrl = uri.toString()

        // Short Google Maps redirect links
        if (host == "maps.app.goo.gl" || (host == "goo.gl" && uri.path.orEmpty().startsWith("/maps"))) {
            return ParsedExternalMapTarget.ShortLink(url = fullUrl, fallbackText = fallbackText)
        }

        // Extract optional place name from /maps/place/<Name>/...
        val pathLabel = extractGooglePlaceLabel(uri.path.orEmpty())
            ?: uri.getQueryParameter("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackText

        // 1. Exact pin coords in Google Maps URLs (!3d<lat>!4d<lon>)
        BANG_PIN_COORDS_REGEX.find(fullUrl)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                return ParsedExternalMapTarget.Coordinates(lat, lon, label = pathLabel)
            }
        }

        // 2. Query parameters: q=, ll=, daddr=, destination=, sll=
        val qParam = uri.getQueryParameter("q")?.trim().orEmpty()
        if (qParam.isNotBlank()) {
            parseCoordinatePair(qParam)?.let { (lat, lon, inlineLabel) ->
                return ParsedExternalMapTarget.Coordinates(
                    lat = lat,
                    lon = lon,
                    label = inlineLabel ?: pathLabel
                )
            }
        }

        for (paramKey in listOf("ll", "daddr", "destination", "sll")) {
            val valStr = uri.getQueryParameter(paramKey)?.trim().orEmpty()
            if (valStr.isNotBlank()) {
                parseCoordinatePair(valStr)?.let { (lat, lon, inlineLabel) ->
                    val label = inlineLabel
                        ?: qParam.takeIf { it.isNotBlank() }
                        ?: uri.getQueryParameter("address")?.trim()?.takeIf { it.isNotBlank() }
                        ?: pathLabel
                    return ParsedExternalMapTarget.Coordinates(lat, lon, label = label)
                }
            }
        }

        // 3. @lat,lon anywhere in URL path/fragment
        AT_COORDS_REGEX.find(fullUrl)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                return ParsedExternalMapTarget.Coordinates(
                    lat = lat,
                    lon = lon,
                    label = qParam.takeIf { it.isNotBlank() } ?: pathLabel
                )
            }
        }

        // 4. Fallback to textual search query from q=, address=, daddr=, destination=, or /maps/place/<Name>
        val textQuery = qParam
            .ifBlank { uri.getQueryParameter("address")?.trim().orEmpty() }
            .ifBlank { uri.getQueryParameter("daddr")?.trim().orEmpty() }
            .ifBlank { uri.getQueryParameter("destination")?.trim().orEmpty() }
            .ifBlank { pathLabel.orEmpty() }
            .trim()

        return if (textQuery.isNotBlank()) {
            ParsedExternalMapTarget.SearchQuery(textQuery)
        } else {
            ParsedExternalMapTarget.None
        }
    }

    private fun parseGeoUri(uri: Uri): ParsedExternalMapTarget {
        val ssp = uri.schemeSpecificPart.orEmpty()
        val parts = ssp.split("?", limit = 2)
        val coordPart = parts.getOrNull(0)?.trim().orEmpty()
        val queryPart = parts.getOrNull(1).orEmpty()

        val qValue = extractQueryParamFromRaw(queryPart, "q")
        if (!qValue.isNullOrBlank()) {
            parseCoordinatePair(qValue)?.let { (lat, lon, inlineLabel) ->
                if (!(lat == 0.0 && lon == 0.0)) {
                    return ParsedExternalMapTarget.Coordinates(lat, lon, label = inlineLabel)
                }
            }
        }

        parseCoordinatePair(coordPart)?.let { (lat, lon, inlineLabel) ->
            if (!(lat == 0.0 && lon == 0.0)) {
                return ParsedExternalMapTarget.Coordinates(
                    lat = lat,
                    lon = lon,
                    label = inlineLabel ?: qValue?.takeIf { it.isNotBlank() }
                )
            }
        }

        return if (!qValue.isNullOrBlank()) {
            ParsedExternalMapTarget.SearchQuery(qValue.trim())
        } else {
            ParsedExternalMapTarget.None
        }
    }

    private fun extractQueryParamFromRaw(rawQuery: String, key: String): String? {
        if (rawQuery.isBlank()) return null
        for (pair in rawQuery.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0].equals(key, ignoreCase = true)) {
                return runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
            }
        }
        return null
    }

    private fun extractGooglePlaceLabel(path: String): String? {
        val match = GOOGLE_PLACE_PATH_REGEX.find(path) ?: return null
        val raw = match.groupValues[1]
        val decoded = runCatching { URLDecoder.decode(raw.replace('+', ' '), "UTF-8") }.getOrDefault(raw)
        return decoded.trim().takeIf { it.isNotBlank() }
    }

    private fun parseCoordinatePair(raw: String): Triple<Double, Double, String?>? {
        val cleaned = raw.trim()
        val match = COORD_PAIR_REGEX.matchEntire(cleaned) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (!isValidLatLon(lat, lon)) return null
        val label = match.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() }
        return Triple(lat, lon, label)
    }

    private fun isValidLatLon(lat: Double, lon: Double): Boolean =
        lat in -90.0..90.0 && lon in -180.0..180.0 && !lat.isNaN() && !lon.isNaN()

    /**
     * Expands a `maps.app.goo.gl` short link by following HTTP redirects in the background
     * with a short (~5s) timeout and the standard RahYab User-Agent.
     */
    suspend fun expandShortMapLink(shortUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = OnlineHttpConfig.createShortTimeoutClient(timeoutSeconds = 5L)
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            var currentUrl = shortUrl
            repeat(6) {
                val headReq = Request.Builder().url(currentUrl).head().build()
                var location: String? = null
                var statusCode = 0
                client.newCall(headReq).execute().use { resp ->
                    statusCode = resp.code
                    location = resp.header("Location")
                }
                if (location == null && (statusCode == 405 || statusCode == 403 || statusCode == 400)) {
                    val getReq = Request.Builder().url(currentUrl).get().build()
                    client.newCall(getReq).execute().use { resp ->
                        statusCode = resp.code
                        location = resp.header("Location")
                    }
                }
                if (statusCode in 300..399 && !location.isNullOrBlank()) {
                    val resolved = runCatching {
                        java.net.URL(java.net.URL(currentUrl), location).toString()
                    }.getOrDefault(location!!)
                    currentUrl = resolved
                    // Stop early once we have a full Google Maps URL with coordinates or /maps/
                    if (currentUrl.contains("google.com/maps") ||
                        currentUrl.contains("maps.google.com") ||
                        AT_COORDS_REGEX.containsMatchIn(currentUrl) ||
                        BANG_PIN_COORDS_REGEX.containsMatchIn(currentUrl)
                    ) {
                        return@withContext currentUrl
                    }
                } else {
                    return@withContext currentUrl.takeIf { it != shortUrl }
                }
            }
            currentUrl.takeIf { it != shortUrl }
        }.getOrNull()
    }

    /**
     * Detects if a plain-text address mentions a US state (full name or 2-letter abbreviation
     * after a comma/space) so we can suggest downloading that state pack when offline search
     * returns no matches.
     */
    fun detectMentionedUsState(text: String): UsStateInfo? {
        val lower = text.lowercase(Locale.US)
        for (state in UsStatesCatalog.allStates) {
            if (Pattern.compile("\\b${Pattern.quote(state.nameEn.lowercase(Locale.US))}\\b").matcher(lower).find()) {
                return state
            }
        }
        val upper = text.uppercase(Locale.US)
        val abbrMatch = Regex("""(?:,\s*|\s+)([A-Z]{2})(?:\s+\d{5}|\s*,|\s*$)""").findAll(upper)
        for (m in abbrMatch) {
            val abbr = m.groupValues[1]
            val found = UsStatesCatalog.allStates.firstOrNull { it.abbr.equals(abbr, ignoreCase = true) }
            if (found != null) return found
        }
        return null
    }
}
