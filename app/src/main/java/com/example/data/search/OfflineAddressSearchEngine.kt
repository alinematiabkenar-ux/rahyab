package com.example.data.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos

/**
 * Fully offline house-number address search against per-state SQLite `addresses` + `addresses_fts`.
 *
 * Features:
 * - Distance-first SQL ordering before LIMIT (60 exact, 400 street-only) so nearest matches win.
 * - Automatic city-filter fallback when a city-filtered query yields 0 rows.
 * - Guards against running on plain business queries (only runs for address-like or street-suffix queries).
 * - Gracefully skips older packs that do not yet contain an `addresses` table without crashing.
 */
class OfflineAddressSearchEngine(
    private val sqliteDriver: SQLiteDriver = BundledSQLiteDriver()
) {
    private val tableExistsCache = ConcurrentHashMap<String, Boolean>()

    private val extrasDbReader = com.example.data.packs.ExtrasDbReader(sqliteDriver = sqliteDriver)

    fun searchAcrossDatabases(
        parsed: AddressNormalizer.ParsedAddressQuery,
        userLat: Double,
        userLon: Double,
        stateDbFiles: List<Pair<String, File>>,
        limit: Int = 12,
        extrasDbFiles: List<Pair<String, File>> = emptyList()
    ): List<PlaceSearchResult> {
        if (!shouldRunAddressSearch(parsed)) return emptyList()
        if (stateDbFiles.isEmpty() && extrasDbFiles.isEmpty()) return emptyList()

        val exactResults = mutableListOf<PlaceSearchResult>()
        val streetCandidates = mutableListOf<Pair<String, OfflineAddressRow>>()

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
                    streetCandidatesOut = streetCandidates
                )
            }
        }

        val combined = mutableListOf<PlaceSearchResult>()
        val dedupedExact = AddressInterpolation.deduplicateAddresses(exactResults)
            .sortedByDescending { it.relevanceScore }
        combined.addAll(dedupedExact.take(limit))

        // D4: Exact addresses always win. When no exact match exists for a house number,
        // first try TIGER/Line address ranges (parity + polyline interpolation), then fall back
        // to point-to-point interpolation from `addresses` street candidates.
        if (dedupedExact.isEmpty() && parsed.numericHouseNumber != null) {
            val tigerApprox = if (extrasDbFiles.isNotEmpty() && parsed.normalizedStreet.isNotBlank()) {
                val tigerRows = extrasDbReader.queryTigerRangesInFiles(
                    files = extrasDbFiles,
                    streetNorm = parsed.normalizedStreet,
                    userLat = userLat,
                    userLon = userLon,
                    zipHint = parsed.zipCode,
                    cityHint = parsed.cityHint
                )
                TigerAddressInterpolator.buildTigerFallbackResults(
                    parsed = parsed,
                    candidates = tigerRows,
                    userLat = userLat,
                    userLon = userLon,
                    maxResults = minOf(5, limit)
                )
            } else {
                emptyList()
            }
            if (tigerApprox.isNotEmpty()) {
                combined.addAll(tigerApprox)
            } else if (streetCandidates.isNotEmpty()) {
                val approx = AddressInterpolation.buildApproximateResults(
                    parsed = parsed,
                    targetNumber = parsed.numericHouseNumber,
                    streetCandidates = streetCandidates,
                    userLat = userLat,
                    userLon = userLon,
                    maxResults = minOf(5, limit)
                )
                combined.addAll(approx)
            }
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
        streetCandidatesOut: MutableList<Pair<String, OfflineAddressRow>>
    ) {
        val cacheKey = "${dbFile.absolutePath}:${dbFile.lastModified()}"
        if (tableExistsCache[cacheKey] == false) return

        val conn = sqliteDriver.open(dbFile.absolutePath)
        try {
            val hasTables = tableExistsCache.getOrPut(cacheKey) {
                hasTable(conn, "addresses") && hasTable(conn, "addresses_fts")
            }
            if (!hasTables) return

            // A1 + A2: Exact query ordered by distance (LIMIT 60) with city fallback if 0 rows
            val exactMatchWithCity = buildExactFtsMatch(parsed, includeCity = true)
            if (exactMatchWithCity.isNotEmpty()) {
                var rows = runFtsQuery(conn, exactMatchWithCity, userLat, userLon, EXACT_QUERY_LIMIT)
                if (rows.isEmpty() && parsed.cityHint.isNotBlank()) {
                    val fallbackMatch = buildExactFtsMatch(parsed, includeCity = false)
                    if (fallbackMatch.isNotEmpty()) {
                        rows = runFtsQuery(conn, fallbackMatch, userLat, userLon, EXACT_QUERY_LIMIT)
                    }
                }
                for (row in rows) {
                    if (!matchesOptionalFilters(row, parsed)) continue
                    val score = AddressInterpolation.scoreAddressMatch(row, parsed, userLat, userLon)
                    exactOut.add(
                        AddressInterpolation.toPlaceSearchResult(
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

            // A1 + A2: Street-only candidates ordered by distance (LIMIT 400) with city fallback
            if (parsed.numericHouseNumber != null && parsed.normalizedStreet.isNotBlank()) {
                val streetWithCity = buildStreetOnlyFtsMatch(parsed, includeCity = true)
                if (streetWithCity.isNotEmpty()) {
                    var rows = runFtsQuery(conn, streetWithCity, userLat, userLon, STREET_CANDIDATE_LIMIT)
                    if (rows.isEmpty() && parsed.cityHint.isNotBlank()) {
                        val fallbackStreet = buildStreetOnlyFtsMatch(parsed, includeCity = false)
                        if (fallbackStreet.isNotEmpty()) {
                            rows = runFtsQuery(conn, fallbackStreet, userLat, userLon, STREET_CANDIDATE_LIMIT)
                        }
                    }
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

    internal fun runFtsQuery(
        conn: SQLiteConnection,
        ftsMatch: String,
        userLat: Double,
        userLon: Double,
        maxRows: Int
    ): List<OfflineAddressRow> {
        val cosLat = cos(Math.toRadians(userLat)).coerceAtLeast(0.15)
        val sql = buildDistanceFirstFtsSql()
        val rows = mutableListOf<OfflineAddressRow>()
        val stmt = conn.prepare(sql)
        try {
            stmt.bindText(1, ftsMatch)
            stmt.bindDouble(2, userLat)
            stmt.bindDouble(3, userLat)
            stmt.bindDouble(4, userLon)
            stmt.bindDouble(5, cosLat)
            stmt.bindDouble(6, userLon)
            stmt.bindDouble(7, cosLat)
            stmt.bindLong(8, maxRows.toLong())
            while (stmt.step()) {
                rows.add(
                    OfflineAddressRow(
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

    private fun matchesOptionalFilters(
        row: OfflineAddressRow,
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

    private fun hasTable(conn: SQLiteConnection, name: String): Boolean {
        val stmt = conn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")
        return try {
            stmt.bindText(1, name)
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    companion object {
        const val SOURCE_EXACT_ADDRESS = "address_offline"
        const val SOURCE_APPROX_ADDRESS = "address_approx"
        const val CATEGORY_ADDRESS = "address"
        const val CATEGORY_APPROX_ADDRESS = "approximate_address"
        const val EXACT_QUERY_LIMIT = 60
        const val STREET_CANDIDATE_LIMIT = 400

        /**
         * A3: Only run the address engine when the query is address-like OR contains a street
         * suffix word — never run it for plain business names like "best buy" or "starbucks".
         */
        fun shouldRunAddressSearch(parsed: AddressNormalizer.ParsedAddressQuery): Boolean {
            if (parsed.normalizedQuery.isBlank()) return false
            return parsed.isAddressLike || AddressNormalizer.containsStreetWord(parsed.normalizedQuery)
        }

        /** A1: Distance-first SQL ordering before LIMIT with bound cos(latitude). */
        fun buildDistanceFirstFtsSql(): String = """
            SELECT a.id, COALESCE(a.number, ''), COALESCE(a.street, ''), COALESCE(a.unit, ''),
                   COALESCE(a.city, ''), COALESCE(a.state, ''), COALESCE(a.postcode, ''),
                   a.lat, a.lon, COALESCE(a.source, 'overture')
            FROM addresses_fts
            JOIN addresses a ON a.id = addresses_fts.rowid
            WHERE addresses_fts MATCH ?
            ORDER BY ((a.lat - ?) * (a.lat - ?) + ((a.lon - ?) * ?) * ((a.lon - ?) * ?)) ASC
            LIMIT ?
        """.trimIndent()

        fun buildExactFtsMatch(
            parsed: AddressNormalizer.ParsedAddressQuery,
            includeCity: Boolean = true
        ): String {
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
            if (includeCity && parsed.cityHint.isNotBlank()) {
                for (tok in tokenize(parsed.cityHint).take(2)) {
                    parts.add("city:$tok*")
                }
            }
            if (parsed.zipCode.length == 5) {
                parts.add("postcode:${parsed.zipCode}")
            }
            return parts.joinToString(" ")
        }

        fun buildStreetOnlyFtsMatch(
            parsed: AddressNormalizer.ParsedAddressQuery,
            includeCity: Boolean = true
        ): String {
            val parts = mutableListOf<String>()
            val streetTokens = tokenize(parsed.normalizedStreet)
            if (streetTokens.isEmpty()) return ""
            for (tok in streetTokens) {
                parts.add("street:$tok*")
            }
            if (includeCity && parsed.cityHint.isNotBlank()) {
                for (tok in tokenize(parsed.cityHint).take(2)) {
                    parts.add("city:$tok*")
                }
            }
            return parts.joinToString(" ")
        }

        private fun sanitizeToken(token: String): String =
            token.replace(Regex("[^a-zA-Z0-9]"), "")

        private fun tokenize(text: String): List<String> =
            text.trim().split(Regex("[\\s,.-]+")).map { sanitizeToken(it) }.filter { it.isNotBlank() }
    }
}
