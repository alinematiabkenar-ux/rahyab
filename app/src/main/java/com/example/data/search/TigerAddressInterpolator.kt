package com.example.data.search

import com.example.data.packs.TigerRangeRecord
import com.example.navigation.LatLon
import com.example.navigation.ValhallaPolylineAndGeo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TigerInterpolationMatch(
    val lat: Double,
    val lon: Double,
    val side: String,
    val fraction: Double,
    val record: TigerRangeRecord
)

/**
 * D4: Pure-Kotlin TIGER/Line address range interpolation along polyline6 street segments.
 * Picks the side (left or right) whose numeric bounds and odd/even parity contain the target
 * house number, interpolates linearly along the segment's cumulative polyline distance, and
 * emits results tagged with [OfflineAddressSearchEngine.SOURCE_APPROX_ADDRESS] ("Approximate"/"تقریبی").
 */
object TigerAddressInterpolator {

    fun matchesParity(houseNumber: Int, parity: String, from: Int, to: Int): Boolean {
        if (houseNumber <= 0) return false
        return when (parity.trim().uppercase()) {
            "E", "EVEN" -> houseNumber % 2 == 0
            "O", "ODD" -> houseNumber % 2 != 0
            "B", "BOTH" -> true
            else -> {
                // If parity is unspecified, enforce parity only when both endpoints share the same parity
                if (from % 2 == to % 2) (houseNumber % 2 == from % 2) else true
            }
        }
    }

    fun interpolateOnRange(houseNumber: Int, record: TigerRangeRecord): TigerInterpolationMatch? {
        if (houseNumber <= 0) return null

        val leftMatch = trySide(
            houseNumber = houseNumber,
            from = record.fromLeft,
            to = record.toLeft,
            parity = record.parityLeft,
            side = "L"
        )
        val rightMatch = trySide(
            houseNumber = houseNumber,
            from = record.fromRight,
            to = record.toRight,
            parity = record.parityRight,
            side = "R"
        )

        val chosen = when {
            leftMatch != null && rightMatch != null -> {
                val leftSpecific = record.parityLeft.uppercase() in setOf("E", "O")
                val rightSpecific = record.parityRight.uppercase() in setOf("E", "O")
                when {
                    leftSpecific && !rightSpecific -> leftMatch
                    rightSpecific && !leftSpecific -> rightMatch
                    else -> leftMatch
                }
            }
            leftMatch != null -> leftMatch
            rightMatch != null -> rightMatch
            else -> return null
        }

        val pts = ValhallaPolylineAndGeo.decodePolyline6(record.polyline)
        val coord = interpolateAlongPolyline(pts, chosen.second)
            ?: LatLon(
                lat = (record.minLat + record.maxLat) * 0.5,
                lon = (record.minLon + record.maxLon) * 0.5
            )
        return TigerInterpolationMatch(
            lat = coord.lat,
            lon = coord.lon,
            side = chosen.first,
            fraction = chosen.second,
            record = record
        )
    }

    private fun trySide(
        houseNumber: Int,
        from: Int?,
        to: Int?,
        parity: String,
        side: String
    ): Pair<String, Double>? {
        if (from == null || to == null || from <= 0 || to <= 0) return null
        val lo = min(from, to)
        val hi = max(from, to)
        if (houseNumber !in lo..hi) return null
        if (!matchesParity(houseNumber, parity, from, to)) return null

        val span = (to - from).toDouble()
        val t = if (abs(span) < 1e-9) 0.5 else ((houseNumber - from).toDouble() / span).coerceIn(0.0, 1.0)
        return side to t
    }

    fun interpolateAlongPolyline(points: List<LatLon>, fraction: Double): LatLon? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.first()
        val f = fraction.coerceIn(0.0, 1.0)

        val segLengths = DoubleArray(points.lastIndex)
        var totalLen = 0.0
        for (i in 0 until points.lastIndex) {
            val len = OfflinePlaceSearchEngine.haversineMeters(
                points[i].lat, points[i].lon,
                points[i + 1].lat, points[i + 1].lon
            )
            segLengths[i] = len
            totalLen += len
        }
        if (totalLen <= 1e-6) return points.first()

        val targetDist = f * totalLen
        var acc = 0.0
        for (i in segLengths.indices) {
            val segLen = segLengths[i]
            if (acc + segLen >= targetDist || i == segLengths.lastIndex) {
                val localT = if (segLen <= 1e-6) 0.0 else ((targetDist - acc) / segLen).coerceIn(0.0, 1.0)
                val a = points[i]
                val b = points[i + 1]
                return LatLon(
                    lat = a.lat + (b.lat - a.lat) * localT,
                    lon = a.lon + (b.lon - a.lon) * localT
                )
            }
            acc += segLen
        }
        return points.last()
    }

    fun buildTigerFallbackResults(
        parsed: AddressNormalizer.ParsedAddressQuery,
        candidates: List<TigerRangeRecord>,
        userLat: Double,
        userLon: Double,
        maxResults: Int = 5
    ): List<PlaceSearchResult> {
        val numLong = parsed.numericHouseNumber ?: return emptyList()
        if (numLong <= 0L || numLong > Int.MAX_VALUE || parsed.normalizedStreet.isBlank()) return emptyList()
        val houseNumber = numLong.toInt()
        if (candidates.isEmpty()) return emptyList()

        val stateFiltered = if (parsed.stateHint.isNotBlank()) {
            candidates.filter { rec ->
                rec.stateCode.isBlank() || rec.stateCode.equals(parsed.stateHint, ignoreCase = true)
            }
        } else {
            candidates
        }

        val zipFiltered = if (parsed.zipCode.length == 5) {
            val strictZip = stateFiltered.filter { it.zip.startsWith(parsed.zipCode) }
            if (strictZip.isNotEmpty()) strictZip else stateFiltered.filter { it.zip.isBlank() }
        } else {
            stateFiltered
        }

        val matches = mutableListOf<PlaceSearchResult>()
        for (rec in zipFiltered) {
            if (rec.streetNorm != parsed.normalizedStreet) continue
            val hit = interpolateOnRange(houseNumber, rec) ?: continue
            val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, hit.lat, hit.lon)
            var score = 180.0 + 55.0 / (1.0 + (dist / 1000.0) / 15.0)
            if (parsed.zipCode.length == 5 && rec.zip.startsWith(parsed.zipCode)) score += 35.0
            if (parsed.cityHint.isNotBlank()) {
                if (rec.city.lowercase().contains(parsed.cityHint)) score += 35.0
                else if (rec.city.isNotBlank()) score -= 45.0
            }
            val displayNumber = parsed.houseNumber.uppercase().ifBlank { houseNumber.toString() }
            val displayName = AddressNormalizer.formatUsAddress(
                number = displayNumber,
                street = rec.street,
                unit = parsed.unit,
                city = rec.city,
                state = rec.stateCode.uppercase(),
                postcode = rec.zip
            )
            val streetLine = "$displayNumber ${rec.street}".trim()
            matches.add(
                PlaceSearchResult(
                    id = "tiger_approx_${rec.stateCode.lowercase()}_${rec.id}_$houseNumber",
                    name = displayName.ifBlank { streetLine },
                    brand = "",
                    category = OfflineAddressSearchEngine.CATEGORY_APPROX_ADDRESS,
                    address = streetLine,
                    city = rec.city,
                    stateCode = rec.stateCode.uppercase(),
                    postcode = rec.zip,
                    lat = hit.lat,
                    lon = hit.lon,
                    distanceMeters = dist,
                    relevanceScore = score,
                    source = OfflineAddressSearchEngine.SOURCE_APPROX_ADDRESS
                )
            )
        }

        return AddressInterpolation.deduplicateAddresses(matches)
            .sortedByDescending { it.relevanceScore }
            .take(maxResults)
    }
}
