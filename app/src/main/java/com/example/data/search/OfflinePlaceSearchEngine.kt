package com.example.data.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.local.SavedPlaceDao
import com.example.data.local.SavedPlaceEntity
import com.example.data.packs.StatePackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class PlaceSearchResult(
    val id: String,
    val name: String,
    val brand: String,
    val category: String,
    val address: String,
    val city: String,
    val stateCode: String,
    val postcode: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    val relevanceScore: Double,
    val source: String,
    val savedId: Long? = null
) {
    val formattedAddress: String
        get() = listOf(address, city, stateCode.uppercase(), postcode)
            .filter { it.isNotBlank() }
            .joinToString(", ")
}

data class RoadHazardPoint(
    val id: Long,
    val type: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    val speedLimitMph: Int? = null,
    val directionDegrees: Float? = null,
    val osmId: String? = null
)

/**
 * Fully offline on-device SQLite FTS5 place search engine powered by Jetpack's
 * `BundledSQLiteDriver` so FTS5 (`places_fts` + `bm25()`) works identically across
 * every Android device (including Android 15) and local JVM tests.
 */
class OfflinePlaceSearchEngine(
    private val packManager: StatePackManager,
    private val savedPlaceDao: SavedPlaceDao,
    private val sqliteDriver: SQLiteDriver = BundledSQLiteDriver()
) {
    private val addressSearchEngine = OfflineAddressSearchEngine(sqliteDriver)

    suspend fun search(
        query: String,
        userLat: Double,
        userLon: Double,
        limit: Int = 30
    ): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        val dbFiles = packManager.getInstalledPlacesDatabases()
        val savedPlaces = runCatching { savedPlaceDao.getAllOnce() }.getOrDefault(emptyList())
        searchAcrossDatabases(
            query = query,
            userLat = userLat,
            userLon = userLon,
            stateDbFiles = dbFiles,
            savedPlaces = savedPlaces,
            limit = limit
        )
    }

    fun searchAcrossDatabases(
        query: String,
        userLat: Double,
        userLon: Double,
        stateDbFiles: List<Pair<String, File>>,
        savedPlaces: List<SavedPlaceEntity> = emptyList(),
        limit: Int = 30
    ): List<PlaceSearchResult> {
        val parsedAddress = AddressNormalizer.parseAddressQuery(query)
        val addressResults = if (OfflineAddressSearchEngine.shouldRunAddressSearch(parsedAddress)) {
            addressSearchEngine.searchAcrossDatabases(
                parsed = parsedAddress,
                userLat = userLat,
                userLon = userLon,
                stateDbFiles = stateDbFiles,
                limit = minOf(12, limit)
            )
        } else {
            emptyList()
        }

        val expanded = PersianQueryNormalizer.expandQuery(query)
        if (expanded.normalizedLatinQueries.isEmpty() && expanded.matchedCategoryTags.isEmpty()) {
            return addressResults.take(limit)
        }

        val candidates = mutableListOf<PlaceSearchResult>()

        // 1. Saved Places (Home, Work, Favorites, Recent)
        for (saved in savedPlaces) {
            val score = scoreSavedPlace(saved, expanded)
            if (score > 0.0) {
                val dist = haversineMeters(userLat, userLon, saved.lat, saved.lon)
                candidates.add(
                    PlaceSearchResult(
                        id = "saved_${saved.id}",
                        name = saved.name,
                        brand = "",
                        category = saved.category.ifBlank { saved.type.lowercase() },
                        address = saved.address,
                        city = "",
                        stateCode = saved.stateCode,
                        postcode = "",
                        lat = saved.lat,
                        lon = saved.lon,
                        distanceMeters = dist,
                        relevanceScore = score + distanceBoost(dist) + 25.0,
                        source = "saved_${saved.type.lowercase()}",
                        savedId = saved.id
                    )
                )
            }
        }

        // 2. Installed per-state FTS5 databases (isolated per file so corrupted DBs never crash)
        for ((stateCode, dbFile) in stateDbFiles) {
            runCatching {
                querySingleStateDb(
                    stateCode = stateCode,
                    dbFile = dbFile,
                    expanded = expanded,
                    userLat = userLat,
                    userLon = userLon,
                    maxPerDb = limit * 2
                )
            }.onSuccess { results ->
                candidates.addAll(results)
            }
        }

        val placeResults = deduplicateResults(candidates)
            .sortedByDescending { it.relevanceScore }
        return if (parsedAddress.isAddressLike && addressResults.isNotEmpty()) {
            (addressResults + placeResults).take(limit)
        } else {
            (placeResults + addressResults).take(limit)
        }
    }

    suspend fun queryRoadHazardsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<RoadHazardPoint> = withContext(Dispatchers.IO) {
        queryRoadHazardsInFiles(
            dbFiles = packManager.getInstalledPlacesDatabases().map { it.second },
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
            camerasOnly = false,
            limit = 400
        )
    }

    suspend fun queryCamerasInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<RoadHazardPoint> = withContext(Dispatchers.IO) {
        queryRoadHazardsInFiles(
            dbFiles = packManager.getInstalledPlacesDatabases().map { it.second },
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
            camerasOnly = true,
            limit = 500
        )
    }

    suspend fun queryTrafficSignalsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<RoadHazardPoint> = withContext(Dispatchers.IO) {
        val raw = queryRoadHazardsInFiles(
            dbFiles = packManager.getInstalledPlacesDatabases().map { it.second },
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
            trafficSignalsOnly = true,
            limit = 1200
        )
        mergeNearbyTrafficSignals(raw, mergeRadiusMeters = 15.0)
    }

    suspend fun querySpeedBumpsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<RoadHazardPoint> = withContext(Dispatchers.IO) {
        queryRoadHazardsInFiles(
            dbFiles = packManager.getInstalledPlacesDatabases().map { it.second },
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
            speedBumpsOnly = true,
            limit = 600
        )
    }

    /**
     * Merges traffic signal nodes closer than [mergeRadiusMeters] (default ~15 m) into a single
     * averaged intersection centroid so multi-node intersections never crowd the map.
     */
    fun mergeNearbyTrafficSignals(
        signals: List<RoadHazardPoint>,
        mergeRadiusMeters: Double = 15.0
    ): List<RoadHazardPoint> {
        if (signals.size <= 1) return signals
        val visited = BooleanArray(signals.size)
        val merged = ArrayList<RoadHazardPoint>(signals.size)
        for (i in signals.indices) {
            if (visited[i]) continue
            visited[i] = true
            val seed = signals[i]
            var sumLat = seed.lat
            var sumLon = seed.lon
            var count = 1
            for (j in i + 1 until signals.size) {
                if (visited[j]) continue
                val other = signals[j]
                // Fast bounding pre-check (~0.0002 deg ≈ 22 m)
                if (kotlin.math.abs(seed.lat - other.lat) > 0.00025 ||
                    kotlin.math.abs(seed.lon - other.lon) > 0.00035
                ) continue
                if (haversineMeters(seed.lat, seed.lon, other.lat, other.lon) <= mergeRadiusMeters) {
                    visited[j] = true
                    sumLat += other.lat
                    sumLon += other.lon
                    count++
                }
            }
            merged.add(
                if (count == 1) seed else seed.copy(lat = sumLat / count, lon = sumLon / count)
            )
        }
        return merged
    }

    /**
     * Reads hazards/cameras across one or more SQLite places databases while gracefully supporting
     * older packs whose `road_hazards` table lacks `direction` or `osm_id` columns (or lacks the table).
     */
    fun queryRoadHazardsInFiles(
        dbFiles: List<File>,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        camerasOnly: Boolean = false,
        trafficSignalsOnly: Boolean = false,
        speedBumpsOnly: Boolean = false,
        limit: Int = 400
    ): List<RoadHazardPoint> {
        val hazards = mutableListOf<RoadHazardPoint>()
        for (dbFile in dbFiles) {
            runCatching {
                val conn = sqliteDriver.open(dbFile.absolutePath)
                try {
                    if (!hasTable(conn, "road_hazards")) return@runCatching
                    val columns = getTableColumns(conn, "road_hazards")
                    val hasDirection = columns.contains("direction")
                    val hasOsmId = columns.contains("osm_id")
                    val dirSelect = if (hasDirection) "direction" else "NULL"
                    val osmIdSelect = if (hasOsmId) "osm_id" else "NULL"
                    val typeFilter = when {
                        camerasOnly -> "AND type IN ('speed_camera', 'red_light_camera')"
                        trafficSignalsOnly -> "AND type IN ('traffic_signal', 'traffic_signals')"
                        speedBumpsOnly -> "AND type = 'speed_bump'"
                        else -> ""
                    }
                    val stmt = conn.prepare(
                        """
                        SELECT id, type, lat, lon, COALESCE(label, ''), speed_limit_mph, $dirSelect, $osmIdSelect
                        FROM road_hazards
                        WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                        $typeFilter
                        LIMIT ?
                        """.trimIndent()
                    )
                    try {
                        stmt.bindDouble(1, minLat)
                        stmt.bindDouble(2, maxLat)
                        stmt.bindDouble(3, minLon)
                        stmt.bindDouble(4, maxLon)
                        stmt.bindLong(5, limit.toLong())
                        while (stmt.step()) {
                            hazards.add(
                                RoadHazardPoint(
                                    id = stmt.getLong(0),
                                    type = stmt.getText(1),
                                    lat = stmt.getDouble(2),
                                    lon = stmt.getDouble(3),
                                    label = stmt.getText(4),
                                    speedLimitMph = if (stmt.isNull(5)) null else stmt.getLong(5).toInt(),
                                    directionDegrees = if (stmt.isNull(6)) null else stmt.getDouble(6).toFloat(),
                                    osmId = if (stmt.isNull(7)) null else stmt.getText(7).takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    } finally {
                        stmt.close()
                    }
                } finally {
                    conn.close()
                }
            }
        }
        return hazards
    }

    private fun getTableColumns(conn: SQLiteConnection, tableName: String): Set<String> {
        val cols = mutableSetOf<String>()
        val stmt = conn.prepare("PRAGMA table_info($tableName)")
        try {
            while (stmt.step()) {
                cols.add(stmt.getText(1).lowercase())
            }
        } finally {
            stmt.close()
        }
        return cols
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

    private fun querySingleStateDb(
        stateCode: String,
        dbFile: File,
        expanded: PersianQueryNormalizer.ExpandedQuery,
        userLat: Double,
        userLon: Double,
        maxPerDb: Int
    ): List<PlaceSearchResult> {
        val results = linkedMapOf<Long, PlaceSearchResult>()
        val conn = sqliteDriver.open(dbFile.absolutePath)
        try {
            // A. FTS5 search: FIRST run a ~50 km nearby query ordered by distance (LIMIT 100)
            // so chains with hundreds of statewide branches (Starbucks, Shell, Walmart, Safeway)
            // never miss the nearest local branch, THEN run the statewide BM25 query for the rest.
            val nearbyLatDelta = NEARBY_SEARCH_RADIUS_KM / 111.32
            val cosLat = cos(Math.toRadians(userLat)).coerceAtLeast(0.15)
            val nearbyLonDelta = NEARBY_SEARCH_RADIUS_KM / (111.32 * cosLat)
            val cosLatSq = cosLat * cosLat

            for (latinQuery in expanded.normalizedLatinQueries) {
                val ftsMatch = buildFts5MatchExpression(latinQuery)
                if (ftsMatch.isNotEmpty()) {
                    // A1. Nearby (~50 km bounding box) FTS5 match ordered by distance, up to 100 results
                    val nearbySql = """
                        SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                               COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                               COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                               COALESCE(p.popularity, 1.0), bm25(places_fts, 10.0, 12.0, 4.0, 5.0, 3.0, 2.0) AS rank_score
                        FROM places_fts
                        JOIN places p ON p.id = places_fts.rowid
                        WHERE places_fts MATCH ?
                          AND p.lat BETWEEN ? AND ?
                          AND p.lon BETWEEN ? AND ?
                        ORDER BY ((p.lat - ?) * (p.lat - ?) + (p.lon - ?) * (p.lon - ?) * ?) ASC
                        LIMIT ?
                    """.trimIndent()
                    val nearStmt = conn.prepare(nearbySql)
                    try {
                        nearStmt.bindText(1, stateCode.uppercase())
                        nearStmt.bindText(2, ftsMatch)
                        nearStmt.bindDouble(3, userLat - nearbyLatDelta)
                        nearStmt.bindDouble(4, userLat + nearbyLatDelta)
                        nearStmt.bindDouble(5, userLon - nearbyLonDelta)
                        nearStmt.bindDouble(6, userLon + nearbyLonDelta)
                        nearStmt.bindDouble(7, userLat)
                        nearStmt.bindDouble(8, userLat)
                        nearStmt.bindDouble(9, userLon)
                        nearStmt.bindDouble(10, userLon)
                        nearStmt.bindDouble(11, cosLatSq)
                        nearStmt.bindLong(12, NEARBY_SEARCH_LIMIT.toLong())
                        while (nearStmt.step()) {
                            val rowId = nearStmt.getLong(0)
                            val place = readRowAndScore(
                                stateCode = stateCode,
                                id = rowId,
                                name = nearStmt.getText(1),
                                brand = nearStmt.getText(2),
                                category = nearStmt.getText(3),
                                address = nearStmt.getText(4),
                                city = nearStmt.getText(5),
                                state = nearStmt.getText(6),
                                postcode = nearStmt.getText(7),
                                lat = nearStmt.getDouble(8),
                                lon = nearStmt.getDouble(9),
                                source = nearStmt.getText(10),
                                popularity = nearStmt.getDouble(11),
                                bm25 = nearStmt.getDouble(12),
                                query = latinQuery,
                                categoryTags = expanded.matchedCategoryTags,
                                userLat = userLat,
                                userLon = userLon
                            )
                            val existing = results[rowId]
                            if (existing == null || place.relevanceScore > existing.relevanceScore) {
                                results[rowId] = place
                            }
                        }
                    } finally {
                        nearStmt.close()
                    }

                    // A2. Statewide BM25 query for distant destinations & remaining matches
                    val statewideSql = """
                        SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                               COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                               COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                               COALESCE(p.popularity, 1.0), bm25(places_fts, 10.0, 12.0, 4.0, 5.0, 3.0, 2.0) AS rank_score
                        FROM places_fts
                        JOIN places p ON p.id = places_fts.rowid
                        WHERE places_fts MATCH ?
                        ORDER BY rank_score ASC
                        LIMIT ?
                    """.trimIndent()
                    val stmt = conn.prepare(statewideSql)
                    try {
                        stmt.bindText(1, stateCode.uppercase())
                        stmt.bindText(2, ftsMatch)
                        stmt.bindLong(3, maxPerDb.toLong())
                        while (stmt.step()) {
                            val rowId = stmt.getLong(0)
                            val place = readRowAndScore(
                                stateCode = stateCode,
                                id = rowId,
                                name = stmt.getText(1),
                                brand = stmt.getText(2),
                                category = stmt.getText(3),
                                address = stmt.getText(4),
                                city = stmt.getText(5),
                                state = stmt.getText(6),
                                postcode = stmt.getText(7),
                                lat = stmt.getDouble(8),
                                lon = stmt.getDouble(9),
                                source = stmt.getText(10),
                                popularity = stmt.getDouble(11),
                                bm25 = stmt.getDouble(12),
                                query = latinQuery,
                                categoryTags = expanded.matchedCategoryTags,
                                userLat = userLat,
                                userLon = userLon
                            )
                            val existing = results[rowId]
                            if (existing == null || place.relevanceScore > existing.relevanceScore) {
                                results[rowId] = place
                            }
                        }
                    } finally {
                        stmt.close()
                    }
                }
            }

            // B. Category FTS5 match (Persian categories like پمپ بنزین / داروخانه / سوپرمارکت)
            if (expanded.matchedCategoryTags.isNotEmpty()) {
                val matchExpr = expanded.matchedCategoryTags
                    .map { sanitizeFtsToken(it) }
                    .filter { it.isNotBlank() }
                    .joinToString(" OR ") { "category:$it*" }

                if (matchExpr.isNotBlank()) {
                    val latDelta = 0.75
                    val lonDelta = 1.0
                    val nearSql = """
                        SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                               COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                               COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                               COALESCE(p.popularity, 1.0)
                        FROM places_fts
                        JOIN places p ON p.id = places_fts.rowid
                        WHERE places_fts MATCH ?
                          AND p.lat BETWEEN ? AND ?
                          AND p.lon BETWEEN ? AND ?
                        LIMIT ?
                    """.trimIndent()
                    var foundNear = 0
                    val nearStmt = conn.prepare(nearSql)
                    try {
                        nearStmt.bindText(1, stateCode.uppercase())
                        nearStmt.bindText(2, matchExpr)
                        nearStmt.bindDouble(3, userLat - latDelta)
                        nearStmt.bindDouble(4, userLat + latDelta)
                        nearStmt.bindDouble(5, userLon - lonDelta)
                        nearStmt.bindDouble(6, userLon + lonDelta)
                        nearStmt.bindLong(7, maxPerDb.toLong())
                        while (nearStmt.step()) {
                            foundNear++
                            val rowId = nearStmt.getLong(0)
                            val place = readRowAndScore(
                                stateCode = stateCode,
                                id = rowId,
                                name = nearStmt.getText(1),
                                brand = nearStmt.getText(2),
                                category = nearStmt.getText(3),
                                address = nearStmt.getText(4),
                                city = nearStmt.getText(5),
                                state = nearStmt.getText(6),
                                postcode = nearStmt.getText(7),
                                lat = nearStmt.getDouble(8),
                                lon = nearStmt.getDouble(9),
                                source = nearStmt.getText(10),
                                popularity = nearStmt.getDouble(11),
                                bm25 = -5.0,
                                query = expanded.normalizedLatinQueries.firstOrNull() ?: expanded.original,
                                categoryTags = expanded.matchedCategoryTags,
                                userLat = userLat,
                                userLon = userLon
                            )
                            val existing = results[rowId]
                            if (existing == null || place.relevanceScore > existing.relevanceScore) {
                                results[rowId] = place
                            }
                        }
                    } finally {
                        nearStmt.close()
                    }

                    if (foundNear == 0) {
                        val stateSql = """
                            SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                                   COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                                   COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                                   COALESCE(p.popularity, 1.0)
                            FROM places_fts
                            JOIN places p ON p.id = places_fts.rowid
                            WHERE places_fts MATCH ?
                            LIMIT ?
                        """.trimIndent()
                        val stStmt = conn.prepare(stateSql)
                        try {
                            stStmt.bindText(1, stateCode.uppercase())
                            stStmt.bindText(2, matchExpr)
                            stStmt.bindLong(3, maxPerDb.toLong())
                            while (stStmt.step()) {
                                val rowId = stStmt.getLong(0)
                                val place = readRowAndScore(
                                    stateCode = stateCode,
                                    id = rowId,
                                    name = stStmt.getText(1),
                                    brand = stStmt.getText(2),
                                    category = stStmt.getText(3),
                                    address = stStmt.getText(4),
                                    city = stStmt.getText(5),
                                    state = stStmt.getText(6),
                                    postcode = stStmt.getText(7),
                                    lat = stStmt.getDouble(8),
                                    lon = stStmt.getDouble(9),
                                    source = stStmt.getText(10),
                                    popularity = stStmt.getDouble(11),
                                    bm25 = -4.0,
                                    query = expanded.normalizedLatinQueries.firstOrNull() ?: expanded.original,
                                    categoryTags = expanded.matchedCategoryTags,
                                    userLat = userLat,
                                    userLon = userLon
                                )
                                val existing = results[rowId]
                                if (existing == null || place.relevanceScore > existing.relevanceScore) {
                                    results[rowId] = place
                                }
                            }
                        } finally {
                            stStmt.close()
                        }
                    }
                }
            }

            // C. Typo-tolerant Levenshtein fuzzy fallback (e.g., "Bset Buy" -> "Best Buy")
            if (results.size < 5) {
                for (latinQuery in expanded.normalizedLatinQueries) {
                    val tokens = tokenize(latinQuery)
                    if (tokens.isEmpty()) continue
                    val seedPrefix = tokens.last().take(2).ifBlank { tokens.first().take(2) }
                    if (seedPrefix.length < 2) continue

                    val fuzzySql = """
                        SELECT id, name, COALESCE(brand, ''), COALESCE(category, ''),
                               COALESCE(address, ''), COALESCE(city, ''), COALESCE(state, ?),
                               COALESCE(postcode, ''), lat, lon, COALESCE(source, 'overture'),
                               COALESCE(popularity, 1.0)
                        FROM places
                        WHERE LOWER(name) LIKE ? OR LOWER(brand) LIKE ? OR LOWER(name) LIKE ?
                        LIMIT 400
                    """.trimIndent()
                    val likeA = "%${seedPrefix.lowercase()}%"
                    val likeB = "%${tokens.first().take(1).lowercase()}%"
                    val fStmt = conn.prepare(fuzzySql)
                    try {
                        fStmt.bindText(1, stateCode.uppercase())
                        fStmt.bindText(2, likeA)
                        fStmt.bindText(3, likeA)
                        fStmt.bindText(4, likeB)
                        while (fStmt.step()) {
                            val rowId = fStmt.getLong(0)
                            if (results.containsKey(rowId)) continue
                            val name = fStmt.getText(1)
                            val brand = fStmt.getText(2)
                            val sim = maxOf(
                                normalizedSimilarity(latinQuery, name),
                                normalizedSimilarity(latinQuery, brand)
                            )
                            if (sim >= 0.62) {
                                val dist = haversineMeters(userLat, userLon, fStmt.getDouble(8), fStmt.getDouble(9))
                                val score = sim * 80.0 + distanceBoost(dist) + fStmt.getDouble(11) * 3.0
                                results[rowId] = PlaceSearchResult(
                                    id = "${stateCode}_$rowId",
                                    name = name,
                                    brand = brand,
                                    category = fStmt.getText(3),
                                    address = fStmt.getText(4),
                                    city = fStmt.getText(5),
                                    stateCode = fStmt.getText(6),
                                    postcode = fStmt.getText(7),
                                    lat = fStmt.getDouble(8),
                                    lon = fStmt.getDouble(9),
                                    distanceMeters = dist,
                                    relevanceScore = score,
                                    source = fStmt.getText(10)
                                )
                            }
                        }
                    } finally {
                        fStmt.close()
                    }
                }
            }
        } finally {
            conn.close()
        }

        return results.values.toList()
    }

    private fun readRowAndScore(
        stateCode: String,
        id: Long,
        name: String,
        brand: String,
        category: String,
        address: String,
        city: String,
        state: String,
        postcode: String,
        lat: Double,
        lon: Double,
        source: String,
        popularity: Double,
        bm25: Double,
        query: String,
        categoryTags: List<String>,
        userLat: Double,
        userLon: Double
    ): PlaceSearchResult {
        val dist = haversineMeters(userLat, userLon, lat, lon)
        val qLower = query.trim().lowercase()
        val nameLower = name.lowercase()
        val brandLower = brand.lowercase()
        val catLower = category.lowercase()

        var textScore = (-bm25).coerceIn(0.0, 35.0) * 1.5
        if (nameLower == qLower || brandLower == qLower) textScore += 75.0
        else if (nameLower.startsWith(qLower) || brandLower.startsWith(qLower)) textScore += 50.0
        else if (nameLower.contains(qLower) || brandLower.contains(qLower)) textScore += 32.0

        if (categoryTags.any { tag -> catLower.contains(tag.lowercase()) }) {
            textScore += 45.0
        }

        val totalScore = textScore + distanceBoost(dist) + (popularity.coerceIn(0.0, 10.0) * 2.0)

        return PlaceSearchResult(
            id = "${stateCode}_$id",
            name = name,
            brand = brand,
            category = category,
            address = address,
            city = city,
            stateCode = state,
            postcode = postcode,
            lat = lat,
            lon = lon,
            distanceMeters = dist,
            relevanceScore = totalScore,
            source = source
        )
    }

    private fun scoreSavedPlace(
        saved: SavedPlaceEntity,
        expanded: PersianQueryNormalizer.ExpandedQuery
    ): Double {
        val hay = "${saved.name} ${saved.address} ${saved.category} ${saved.type}".lowercase()
        val rawLower = expanded.original.trim().lowercase()
        if (rawLower == "home" || rawLower == "خانه") {
            if (saved.type == "HOME") return 140.0
        }
        if (rawLower == "work" || rawLower == "محل کار") {
            if (saved.type == "WORK") return 140.0
        }
        for (q in expanded.normalizedLatinQueries) {
            val ql = q.lowercase()
            if (hay.contains(ql)) return 90.0
            val sim = normalizedSimilarity(ql, saved.name)
            if (sim >= 0.65) return sim * 75.0
        }
        return 0.0
    }

    private fun distanceBoost(distanceMeters: Double): Double {
        val km = distanceMeters / 1000.0
        return 60.0 / (1.0 + km / 15.0)
    }

    private fun buildFts5MatchExpression(query: String): String {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "${sanitizeFtsToken(it)}*" }
    }

    private fun sanitizeFtsToken(token: String): String =
        token.replace(Regex("[^a-zA-Z0-9_]"), "")

    private fun tokenize(text: String): List<String> =
        text.trim().split(Regex("[\\s,.-]+")).map { sanitizeFtsToken(it) }.filter { it.isNotBlank() }

    private fun deduplicateResults(items: List<PlaceSearchResult>): List<PlaceSearchResult> {
        val sorted = items.sortedByDescending { it.relevanceScore }
        val kept = mutableListOf<PlaceSearchResult>()
        for (item in sorted) {
            val duplicate = kept.any { existing ->
                existing.name.equals(item.name, ignoreCase = true) &&
                    haversineMeters(existing.lat, existing.lon, item.lat, item.lon) < 75.0
            }
            if (!duplicate) kept.add(item)
        }
        return kept
    }

    companion object {
        const val NEARBY_SEARCH_RADIUS_KM = 50.0
        const val NEARBY_SEARCH_LIMIT = 100

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }

        fun normalizedSimilarity(a: String, b: String): Double {
            val s1 = a.trim().lowercase()
            val s2 = b.trim().lowercase()
            if (s1.isEmpty() || s2.isEmpty()) return 0.0
            if (s1 == s2) return 1.0
            if (s2.contains(s1)) return 0.88
            val maxLen = maxOf(s1.length, s2.length)
            val dist = levenshtein(s1, s2)
            return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
        }

        private fun levenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val temp = dp[j]
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                    prev = temp
                }
            }
            return dp[b.length]
        }
    }
}
