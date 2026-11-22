package com.example.data.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Fully offline house-number address search against per-state SQLite `addresses` + `addresses_fts`.
 * Gracefully skips older packs that do not yet contain an `addresses` table without crashing.
 */
class OfflineAddressSearchEngine(
    private val sqliteDriver: SQLiteDriver = BundledSQLiteDriver()
) {

    private data class AddressRow(
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

    private val tableExistsCache = ConcurrentHashMap<String, Boolean>()

    fun searchAcrossDatabases(
        parsed: AddressNormalizer.ParsedAddressQuery,
        userLat: Double,
        userLon: Double,
        stateDbFiles: List<Pair<String, File>>,
        limit: Int = 12
    ): List<PlaceSearchResult> {
        if (!parsed.isAddressLike && parsed.normalizedStreet.isBlank()) return emptyList()
        if (stateDbFiles.isEmpty()) return emptyList()

        val exactResults = mutableListOf<PlaceSearchResult>()
        val streetCandidates = mutableListOf<Pair<String, AddressRow>>()

        for ((stateCode, dbFile) in stateDbFiles) {
            if (!dbFile.exists() || !dbFile.isFile) continue
            runCatching {
                querySingleDb(
                    stateCode = stateCode,
                    dbFile = dbFile,
                    parsed = parsed,
                    userLat = userLat,
                    userLon = userLon,
                    exactOut = exactResults,
                    streetCandidatesOut = streetCandidates,
                    limit = limit
                )
            }
        }

        val combined = mutableListOf<PlaceSearchResult>()
        val dedupedExact = deduplicateAddresses(exactResults)
            .sortedByDescending { it.relevanceScore }
        combined.addAll(dedupedExact.take(limit))

        // If user entered a house number and no exact match was found, interpolate or show nearest on that street
        if (dedupedExact.isEmpty() && parsed.numericHouseNumber != null && streetCandidates.isNotEmpty()) {
            val approx = buildApproximateResults(
                parsed = parsed,
                targetNumber = parsed.numericHouseNumber,
                streetCandidates = streetCandidates,
                userLat = userLat,
                userLon = userLon,
                maxResults = minOf(5, limit)
            )
            combined.addAll(approx)
        }

        return combined.take(limit)
    }

    private fun querySingleDb(
        stateCode: String,
        dbFile: File,
        parsed: AddressNormalizer.ParsedAddressQuery,
        userLat: Double,
        userLon: Double,
        exactOut: MutableList<PlaceSearchResult>,
        streetCandidatesOut: MutableList<Pair<String, AddressRow>>,
        limit: Int
    ) {
        val cacheKey = "${dbFile.absolutePath}:${dbFile.lastModified()}"
        if (tableExistsCache[cacheKey] == false) return

        val conn = sqliteDriver.open(dbFile.absolutePath)
        try {
            val hasTables = tableExistsCache.getOrPut(cacheKey) {
                hasTable(conn, "addresses") && hasTable(conn, "addresses_fts")
            }
            if (!hasTables) return

            val matchExpr = buildExactFtsMatch(parsed)
            if (matchExpr.isNotEmpty()) {
                val rows = runFtsQuery(conn, matchExpr, maxRows = limit * 4)
                for (row in rows) {
                    if (!matchesOptionalFilters(row, parsed)) continue
                    val score = scoreAddressMatch(row, parsed, userLat, userLon)
                    exactOut.add(
                        toPlaceSearchResult(
                            stateCode = stateCode,
                            row = row,
                            userLat = userLat,
                            userLon = userLon,
                            score = score,
                            isApproximate = false
                        )
                    )
                }
            }

            // Collect same-street candidates for fallback interpolation / nearest house number
            if (parsed.numericHouseNumber != null && parsed.normalizedStreet.isNotBlank()) {
                val streetExpr = buildStreetOnlyFtsMatch(parsed)
                if (streetExpr.isNotEmpty()) {
                    val rows = runFtsQuery(conn, streetExpr, maxRows = 160)
                    for (row in rows) {
                        if (parsed.zipCode.isNotEmpty() && row.postcode.isNotEmpty() &&
                            !row.postcode.startsWith(parsed.zipCode)
                        ) continue
                        if (parsed.stateHint.isNotEmpty() && row.state.isNotEmpty() &&
                            !row.state.equals(parsed.stateHint, ignoreCase = true)
                        ) continue
                        streetCandidatesOut.add(stateCode to row)
                    }
                }
            }
        } finally {
            conn.close()
        }
    }

    private fun runFtsQuery(
        conn: SQLiteConnection,
        ftsMatch: String,
        maxRows: Int
    ): List<AddressRow> {
        val sql = """
            SELECT a.id, COALESCE(a.number, ''), COALESCE(a.street, ''), COALESCE(a.unit, ''),
                   COALESCE(a.city, ''), COALESCE(a.state, ''), COALESCE(a.postcode, ''),
                   a.lat, a.lon, COALESCE(a.source, 'overture')
            FROM addresses_fts
            JOIN addresses a ON a.id = addresses_fts.rowid
            WHERE addresses_fts MATCH ?
            LIMIT ?
        """.trimIndent()
        val rows = mutableListOf<AddressRow>()
        val stmt = conn.prepare(sql)
        try {
            stmt.bindText(1, ftsMatch)
            stmt.bindLong(2, maxRows.toLong())
            while (stmt.step()) {
                rows.add(
                    AddressRow(
                        id = stmt.getLong(0),
                        number = stmt.getText(1),
                        street = stmt.getText(2),
                        unit = stmt.getText(3),
                        city = stmt.getText(4),
                        state = stmt.getText(5),
                        postcode = stmt.getText(6),
                        lat = stmt.getDouble(7),
                        lon = stmt.getDouble(8),
                        source = stmt.getText(9)
                    )
                )
            }
        } finally {
            stmt.close()
        }
        return rows
    }

    private fun buildExactFtsMatch(parsed: AddressNormalizer.ParsedAddressQuery): String {
        val parts = mutableListOf<String>()
        val cleanNum = sanitizeToken(parsed.houseNumber)
        if (cleanNum.isNotEmpty()) {
            parts.add("number:$cleanNum")
        }
        val streetTokens = tokenize(parsed.normalizedStreet)
        for ((idx, tok) in streetTokens.withIndex()) {
            val suffix = if (idx == streetTokens.lastIndex && tok.length >= 2) "*" else ""
            parts.add("street:$tok$suffix")
        }
        if (parsed.cityHint.isNotBlank()) {
            val cityTokens = tokenize(parsed.cityHint)
            for (tok in cityTokens.take(2)) {
                parts.add("city:$tok*")
            }
        }
        if (parsed.zipCode.length == 5) {
            parts.add("postcode:${parsed.zipCode}")
        }
        return parts.joinToString(" ")
    }

    private fun buildStreetOnlyFtsMatch(parsed: AddressNormalizer.ParsedAddressQuery): String {
        val parts = mutableListOf<String>()
        val streetTokens = tokenize(parsed.normalizedStreet)
        if (streetTokens.isEmpty()) return ""
        for (tok in streetTokens) {
            parts.add("street:$tok*")
        }
        if (parsed.cityHint.isNotBlank()) {
            for (tok in tokenize(parsed.cityHint).take(2)) {
                parts.add("city:$tok*")
            }
        }
        return parts.joinToString(" ")
    }

    private fun matchesOptionalFilters(
        row: AddressRow,
        parsed: AddressNormalizer.ParsedAddressQuery
    ): Boolean {
        if (parsed.houseNumber.isNotEmpty()) {
            val rowNum = AddressNormalizer.normalizeHouseNumber(row.number)
            if (rowNum != parsed.houseNumber) return false
        }
        if (parsed.stateHint.isNotEmpty() && row.state.isNotEmpty() &&
            !row.state.equals(parsed.stateHint, ignoreCase = true)
        ) return false
        if (parsed.zipCode.isNotEmpty() && row.postcode.isNotEmpty() &&
            !row.postcode.startsWith(parsed.zipCode)
        ) return false
        return true
    }

    private fun scoreAddressMatch(
        row: AddressRow,
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
        if (parsed.cityHint.isNotBlank() && row.city.lowercase().contains(parsed.cityHint)) {
            score += 35.0
        }
        if (parsed.zipCode.isNotEmpty() && row.postcode.startsWith(parsed.zipCode)) {
            score += 35.0
        }
        if (row.source == "overture") score += 4.0
        val km = dist / 1000.0
        score += 65.0 / (1.0 + km / 15.0)
        return score
    }

    private fun buildApproximateResults(
        parsed: AddressNormalizer.ParsedAddressQuery,
        targetNumber: Long,
        streetCandidates: List<Pair<String, AddressRow>>,
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
        // Pick the street group closest to the user (preferring city hint match)
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

        // Linear interpolation between bracketing house numbers on the same street
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
                    source = SOURCE_APPROX_ADDRESS
                )
                out.add(
                    toPlaceSearchResult(
                        stateCode = lower.second,
                        row = interpRow,
                        userLat = userLat,
                        userLon = userLon,
                        score = 175.0 + 50.0 / (1.0 + OfflinePlaceSearchEngine.haversineMeters(
                            userLat, userLon, interpLat, interpLon
                        ) / 15000.0),
                        isApproximate = true
                    )
                )
            }
        }

        // Also include nearest existing house numbers on the street
        val nearest = numbered
            .sortedBy { abs(it.first - targetNumber) }
            .distinctBy { it.first }
            .take((maxResults - out.size).coerceAtLeast(1))
        for ((idx, item) in nearest.withIndex()) {
            val (_, stCode, row) = item
            out.add(
                toPlaceSearchResult(
                    stateCode = stCode,
                    row = row,
                    userLat = userLat,
                    userLon = userLon,
                    score = 150.0 - idx * 5.0,
                    isApproximate = true
                )
            )
        }
        return out.take(maxResults)
    }

    private fun toPlaceSearchResult(
        stateCode: String,
        row: AddressRow,
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
            category = if (isApproximate) CATEGORY_APPROX_ADDRESS else CATEGORY_ADDRESS,
            address = streetLine,
            city = row.city,
            stateCode = row.state.ifBlank { stateCode.uppercase() },
            postcode = row.postcode,
            lat = row.lat,
            lon = row.lon,
            distanceMeters = dist,
            relevanceScore = score,
            source = if (isApproximate) SOURCE_APPROX_ADDRESS else SOURCE_EXACT_ADDRESS
        )
    }

    private fun deduplicateAddresses(items: List<PlaceSearchResult>): List<PlaceSearchResult> {
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

    private fun hasTable(conn: SQLiteConnection, name: String): Boolean {
        val stmt = conn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")
        return try {
            stmt.bindText(1, name)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    private fun sanitizeToken(token: String): String =
        token.replace(Regex("[^a-zA-Z0-9]"), "")

    private fun tokenize(text: String): List<String> =
        text.trim().split(Regex("[\\s,.-]+")).map { sanitizeToken(it) }.filter { it.isNotBlank() }

    companion object {
        const val SOURCE_EXACT_ADDRESS = "address_offline"
        const val SOURCE_APPROX_ADDRESS = "address_approx"
        const val CATEGORY_ADDRESS = "address"
        const val CATEGORY_APPROX_ADDRESS = "approximate_address"
    }
}
