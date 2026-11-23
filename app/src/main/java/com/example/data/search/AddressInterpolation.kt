package com.example.data.search

import kotlin.math.abs

internal data class OfflineAddressRow(
    val id: Long,
    val number: String,
    val street: String,
    val unit: String,
    val city: String,
    val state: String,
    val postcode: String,
    val lat: Double,
    val lon: Double,
    val source: String
)

/**
 * Scoring, formatting, deduplication, and linear house-number interpolation helpers for
 * [OfflineAddressSearchEngine] (split into its own file to keep every Kotlin file under 400 lines).
 */
internal object AddressInterpolation {

    fun scoreAddressMatch(
        row: OfflineAddressRow,
        parsed: AddressNormalizer.ParsedAddressQuery,
        userLat: Double,
        userLon: Double
    ): Double {
        val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, row.lat, row.lon)
        var score = 135.0
        val normRowStreet = AddressNormalizer.normalizeStreet(row.street)
        if (parsed.normalizedStreet.isNotBlank()) {
            score += when {
                normRowStreet == parsed.normalizedStreet -> 60.0
                normRowStreet.contains(parsed.normalizedStreet) -> 35.0
                else -> 12.0
            }
        }
        if (parsed.houseNumber.isNotEmpty() &&
            AddressNormalizer.normalizeHouseNumber(row.number) == parsed.houseNumber
        ) {
            score += 70.0
        }
        if (parsed.cityHint.isNotBlank()) {
            if (row.city.lowercase().contains(parsed.cityHint)) {
                score += 40.0
            } else {
                // Penalize non-matching city rows returned via A2 city fallback
                score -= 45.0
            }
        }
        if (parsed.zipCode.isNotEmpty() && row.postcode.startsWith(parsed.zipCode)) {
            score += 35.0
        }
        if (row.source == "overture") score += 4.0
        val km = dist / 1000.0
        score += 65.0 / (1.0 + km / 15.0)
        return score
    }

    fun buildApproximateResults(
        parsed: AddressNormalizer.ParsedAddressQuery,
        targetNumber: Long,
        streetCandidates: List<Pair<String, OfflineAddressRow>>,
        userLat: Double,
        userLon: Double,
        maxResults: Int
    ): List<PlaceSearchResult> {
        val grouped = streetCandidates.groupBy { (stCode, row) ->
            Triple(
                stCode.lowercase(),
                AddressNormalizer.normalizeStreet(row.street),
                row.city.trim().lowercase()
            )
        }
        val bestGroup = grouped.values.minByOrNull { group ->
            val sample = group.first().second
            val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, sample.lat, sample.lon)
            val cityPenalty = if (parsed.cityHint.isNotBlank() &&
                !sample.city.lowercase().contains(parsed.cityHint)
            ) 500_000.0 else 0.0
            dist + cityPenalty
        } ?: return emptyList()

        val numbered = bestGroup.mapNotNull { (stCode, row) ->
            val n = AddressNormalizer.extractLeadingLong(row.number) ?: return@mapNotNull null
            Triple(n, stCode, row)
        }.sortedBy { it.first }
        if (numbered.isEmpty()) return emptyList()

        val out = mutableListOf<PlaceSearchResult>()

        val lower = numbered.lastOrNull { it.first < targetNumber }
        val upper = numbered.firstOrNull { it.first > targetNumber }
        if (lower != null && upper != null && upper.first > lower.first) {
            val spanMeters = OfflinePlaceSearchEngine.haversineMeters(
                lower.third.lat, lower.third.lon, upper.third.lat, upper.third.lon
            )
            if (spanMeters in 1.0..3000.0) {
                val t = (targetNumber - lower.first).toDouble() / (upper.first - lower.first).toDouble()
                val interpLat = lower.third.lat + (upper.third.lat - lower.third.lat) * t
                val interpLon = lower.third.lon + (upper.third.lon - lower.third.lon) * t
                val ref = lower.third
                val interpRow = ref.copy(
                    id = -targetNumber,
                    number = parsed.houseNumber.uppercase(),
                    unit = "",
                    lat = interpLat,
                    lon = interpLon,
                    source = OfflineAddressSearchEngine.SOURCE_APPROX_ADDRESS
                )
                val cityPenalty = if (parsed.cityHint.isNotBlank() &&
                    !ref.city.lowercase().contains(parsed.cityHint)
                ) -45.0 else 0.0
                out.add(
                    toPlaceSearchResult(
                        stateCode = lower.second,
                        row = interpRow,
                        userLat = userLat,
                        userLon = userLon,
                        score = 175.0 + cityPenalty + 50.0 / (1.0 + OfflinePlaceSearchEngine.haversineMeters(
                            userLat, userLon, interpLat, interpLon
                        ) / 15000.0),
                        isApproximate = true
                    )
                )
            }
        }

        val nearest = numbered
            .sortedBy { abs(it.first - targetNumber) }
            .distinctBy { it.first }
            .take((maxResults - out.size).coerceAtLeast(1))
        for ((idx, item) in nearest.withIndex()) {
            val (_, stCode, row) = item
            val cityPenalty = if (parsed.cityHint.isNotBlank() &&
                !row.city.lowercase().contains(parsed.cityHint)
            ) -45.0 else 0.0
            out.add(
                toPlaceSearchResult(
                    stateCode = stCode,
                    row = row,
                    userLat = userLat,
                    userLon = userLon,
                    score = 150.0 + cityPenalty - idx * 5.0,
                    isApproximate = true
                )
            )
        }
        return out.take(maxResults)
    }

    fun toPlaceSearchResult(
        stateCode: String,
        row: OfflineAddressRow,
        userLat: Double,
        userLon: Double,
        score: Double,
        isApproximate: Boolean
    ): PlaceSearchResult {
        val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, row.lat, row.lon)
        val displayLine = AddressNormalizer.formatUsAddress(
            number = row.number,
            street = row.street,
            unit = row.unit,
            city = row.city,
            state = row.state.ifBlank { stateCode.uppercase() },
            postcode = row.postcode
        )
        val streetLine = listOf(row.number, row.street)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .let { base ->
                if (row.unit.isNotBlank()) "$base #${row.unit.removePrefix("#")}" else base
            }
        return PlaceSearchResult(
            id = if (isApproximate) "addr_approx_${stateCode}_${row.id}_${row.number}"
            else "addr_${stateCode}_${row.id}",
            name = displayLine.ifBlank { streetLine },
            brand = "",
            category = if (isApproximate) OfflineAddressSearchEngine.CATEGORY_APPROX_ADDRESS
            else OfflineAddressSearchEngine.CATEGORY_ADDRESS,
            address = streetLine,
            city = row.city,
            stateCode = row.state.ifBlank { stateCode.uppercase() },
            postcode = row.postcode,
            lat = row.lat,
            lon = row.lon,
            distanceMeters = dist,
            relevanceScore = score,
            source = if (isApproximate) OfflineAddressSearchEngine.SOURCE_APPROX_ADDRESS
            else OfflineAddressSearchEngine.SOURCE_EXACT_ADDRESS
        )
    }

    fun deduplicateAddresses(items: List<PlaceSearchResult>): List<PlaceSearchResult> {
        val kept = mutableListOf<PlaceSearchResult>()
        for (item in items.sortedByDescending { it.relevanceScore }) {
            val dup = kept.any { existing ->
                existing.name.equals(item.name, ignoreCase = true) &&
                    OfflinePlaceSearchEngine.haversineMeters(
                        existing.lat, existing.lon, item.lat, item.lon
                    ) < 40.0
            }
            if (!dup) kept.add(item)
        }
        return kept
    }
}
