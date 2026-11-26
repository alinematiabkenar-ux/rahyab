package com.example.data.packs

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos

/**
 * X6, X7 & D1–D5: Safe, read-only SQLite reader for optional `<state>_extras.db` files.
 * Uses Jetpack's `BundledSQLiteDriver` (supporting FTS5 across Android and JVM unit tests).
 * Accepts `meta.schema_version` in {"1", "2"}, caches validity per file mtime, checks
 * table existence before querying every optional table, and fails silently when absent/corrupt.
 */
class ExtrasDbReader(
    private val packManager: StatePackManager? = null,
    private val sqliteDriver: SQLiteDriver = BundledSQLiteDriver()
) {
    private val schemaValidityCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun clearCaches() {
        schemaValidityCache.clear()
    }

    fun hasAnyValidExtrasDb(): Boolean =
        packManager?.getInstalledExtrasDatabases().orEmpty().any { (_, file) -> isSchemaSupported(file) }

    fun isSchemaSupported(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 512L) return false
        val path = file.absolutePath
        val mtime = file.lastModified()
        schemaValidityCache[path]?.let { (cachedMtime, valid) ->
            if (cachedMtime == mtime) return valid
        }
        val valid = runCatching {
            val conn = sqliteDriver.open(path)
            try {
                if (!hasTable(conn, "meta")) return@runCatching false
                val stmt = conn.prepare("SELECT value FROM meta WHERE key = 'schema_version' LIMIT 1")
                try {
                    if (!stmt.step()) return@runCatching false
                    val version = stmt.getText(0).trim()
                    version in SUPPORTED_SCHEMA_VERSIONS
                } finally {
                    stmt.close()
                }
            } finally {
                conn.close()
            }
        }.getOrDefault(false)
        schemaValidityCache[path] = mtime to valid
        return valid
    }

    fun queryFraCrossingsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limitPerPack: Int = 1500
    ): List<FraCrossingRecord> = queryFraCrossingsInFiles(
        files = packManager?.getInstalledExtrasDatabases().orEmpty(),
        minLat = minLat,
        maxLat = maxLat,
        minLon = minLon,
        maxLon = maxLon,
        limitPerPack = limitPerPack
    )

    fun queryFraCrossingsInFiles(
        files: List<Pair<String, File>>,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limitPerPack: Int = 1500
    ): List<FraCrossingRecord> {
        if (files.isEmpty()) return emptyList()
        val results = mutableListOf<FraCrossingRecord>()
        for ((_, file) in files) {
            if (!isSchemaSupported(file)) continue
            runCatching {
                val conn = sqliteDriver.open(file.absolutePath)
                try {
                    if (!hasTable(conn, "fra_crossings")) return@runCatching
                    val stmt = conn.prepare(
                        """
                        SELECT crossing_id, lat, lon, street, railroad, has_gates, has_lights, tracks, daily_trains
                        FROM fra_crossings
                        WHERE lat >= ? AND lat <= ? AND lon >= ? AND lon <= ?
                        LIMIT ?
                        """.trimIndent()
                    )
                    try {
                        stmt.bindDouble(1, minLat)
                        stmt.bindDouble(2, maxLat)
                        stmt.bindDouble(3, minLon)
                        stmt.bindDouble(4, maxLon)
                        stmt.bindLong(5, limitPerPack.toLong())
                        while (stmt.step()) {
                            results.add(
                                FraCrossingRecord(
                                    crossingId = stmt.getText(0),
                                    lat = stmt.getDouble(1),
                                    lon = stmt.getDouble(2),
                                    street = stmt.getText(3),
                                    railroad = stmt.getText(4),
                                    hasGates = stmt.getLong(5) != 0L,
                                    hasLights = stmt.getLong(6) != 0L,
                                    tracks = stmt.getLong(7).toInt(),
                                    dailyTrains = stmt.getLong(8).toInt()
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
        return results
    }

    fun queryHpmsSpeedCandidates(
        lat: Double,
        lon: Double,
        padDeg: Double = 0.00035,
        limitPerPack: Int = 64
    ): List<HpmsSpeedRecord> = queryHpmsSpeedInFiles(
        files = packManager?.getInstalledExtrasDatabases().orEmpty(),
        lat = lat,
        lon = lon,
        padDeg = padDeg,
        limitPerPack = limitPerPack
    )

    fun queryHpmsSpeedInFiles(
        files: List<Pair<String, File>>,
        lat: Double,
        lon: Double,
        padDeg: Double = 0.00035,
        limitPerPack: Int = 64
    ): List<HpmsSpeedRecord> {
        if (files.isEmpty()) return emptyList()
        val results = mutableListOf<HpmsSpeedRecord>()
        for ((_, file) in files) {
            if (!isSchemaSupported(file)) continue
            runCatching {
                val conn = sqliteDriver.open(file.absolutePath)
                try {
                    if (!hasTable(conn, "hpms_speed")) return@runCatching
                    val stmt = conn.prepare(
                        """
                        SELECT id, speed_mph, COALESCE(route_name, ''), min_lat, min_lon, max_lat, max_lon, polyline
                        FROM hpms_speed
                        WHERE min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?
                        LIMIT ?
                        """.trimIndent()
                    )
                    try {
                        stmt.bindDouble(1, lat + padDeg)
                        stmt.bindDouble(2, lat - padDeg)
                        stmt.bindDouble(3, lon + padDeg)
                        stmt.bindDouble(4, lon - padDeg)
                        stmt.bindLong(5, limitPerPack.toLong())
                        while (stmt.step()) {
                            results.add(
                                HpmsSpeedRecord(
                                    id = stmt.getLong(0),
                                    speedMph = stmt.getLong(1).toInt(),
                                    routeName = stmt.getText(2),
                                    minLat = stmt.getDouble(3),
                                    minLon = stmt.getDouble(4),
                                    maxLat = stmt.getDouble(5),
                                    maxLon = stmt.getDouble(6),
                                    polyline = stmt.getText(7)
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
        return results
    }

    fun lookupHpmsSpeedLimitMph(lat: Double, lon: Double, headingDegrees: Float?): Int? {
        val candidates = queryHpmsSpeedCandidates(lat, lon)
        if (candidates.isEmpty()) return null
        return ExtrasMatchingHelper.matchHpmsSpeedLimitMph(lat, lon, headingDegrees, candidates)
    }

    fun queryHpmsLanesCandidates(
        lat: Double,
        lon: Double,
        padDeg: Double = 0.0004,
        limitPerPack: Int = 64
    ): List<HpmsLanesRecord> = queryHpmsLanesInFiles(
        files = packManager?.getInstalledExtrasDatabases().orEmpty(),
        lat = lat,
        lon = lon,
        padDeg = padDeg,
        limitPerPack = limitPerPack
    )

    fun queryHpmsLanesInFiles(
        files: List<Pair<String, File>>,
        lat: Double,
        lon: Double,
        padDeg: Double = 0.0004,
        limitPerPack: Int = 64
    ): List<HpmsLanesRecord> {
        if (files.isEmpty()) return emptyList()
        val results = mutableListOf<HpmsLanesRecord>()
        for ((_, file) in files) {
            if (!isSchemaSupported(file)) continue
            runCatching {
                val conn = sqliteDriver.open(file.absolutePath)
                try {
                    if (!hasTable(conn, "hpms_lanes")) return@runCatching
                    val stmt = conn.prepare(
                        """
                        SELECT id, through_lanes, min_lat, min_lon, max_lat, max_lon, polyline
                        FROM hpms_lanes
                        WHERE min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?
                        LIMIT ?
                        """.trimIndent()
                    )
                    try {
                        stmt.bindDouble(1, lat + padDeg)
                        stmt.bindDouble(2, lat - padDeg)
                        stmt.bindDouble(3, lon + padDeg)
                        stmt.bindDouble(4, lon - padDeg)
                        stmt.bindLong(5, limitPerPack.toLong())
                        while (stmt.step()) {
                            results.add(
                                HpmsLanesRecord(
                                    id = stmt.getLong(0),
                                    throughLanes = stmt.getLong(1).toInt(),
                                    minLat = stmt.getDouble(2),
                                    minLon = stmt.getDouble(3),
                                    maxLat = stmt.getDouble(4),
                                    maxLon = stmt.getDouble(5),
                                    polyline = stmt.getText(6)
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
        return results
    }

    fun lookupHpmsThroughLanes(lat: Double, lon: Double, headingDegrees: Float?): Int? {
        val candidates = queryHpmsLanesCandidates(lat, lon)
        if (candidates.isEmpty()) return null
        return ExtrasV2MatchingHelper.matchHpmsThroughLanes(lat, lon, headingDegrees, candidates)
    }

    fun queryTigerRangesInFiles(
        files: List<Pair<String, File>>,
        streetNorm: String,
        userLat: Double,
        userLon: Double,
        zipHint: String = "",
        cityHint: String = "",
        limitPerPack: Int = 250
    ): List<TigerRangeRecord> {
        val norm = streetNorm.trim().lowercase()
        if (files.isEmpty() || norm.isEmpty()) return emptyList()
        val cosLat = cos(Math.toRadians(userLat)).coerceAtLeast(0.15)
        val results = mutableListOf<TigerRangeRecord>()

        for ((stateCode, file) in files) {
            if (!isSchemaSupported(file)) continue
            runCatching {
                val conn = sqliteDriver.open(file.absolutePath)
                try {
                    if (!hasTable(conn, "tiger_ranges")) return@runCatching
                    val hasZip = zipHint.length == 5
                    val hasCity = cityHint.isNotBlank()
                    val filterClause = when {
                        hasZip -> "AND (zip = ? OR COALESCE(zip, '') = '')"
                        hasCity -> "AND (LOWER(COALESCE(city, '')) LIKE ? OR COALESCE(city, '') = '')"
                        else -> ""
                    }
                    val sql = """
                        SELECT id, COALESCE(street, ''), COALESCE(street_norm, ''), COALESCE(city, ''), COALESCE(zip, ''),
                               from_left, to_left, from_right, to_right,
                               COALESCE(parity_left, ''), COALESCE(parity_right, ''),
                               min_lat, min_lon, max_lat, max_lon, COALESCE(polyline, '')
                        FROM tiger_ranges
                        WHERE street_norm = ?
                        $filterClause
                        ORDER BY ((((min_lat + max_lat) * 0.5) - ?) * (((min_lat + max_lat) * 0.5) - ?)
                                + ((((min_lon + max_lon) * 0.5) - ?) * ?) * ((((min_lon + max_lon) * 0.5) - ?) * ?)) ASC
                        LIMIT ?
                    """.trimIndent()
                    val stmt = conn.prepare(sql)
                    try {
                        var idx = 1
                        stmt.bindText(idx++, norm)
                        if (hasZip) stmt.bindText(idx++, zipHint)
                        else if (hasCity) stmt.bindText(idx++, "%${cityHint.trim().lowercase()}%")
                        stmt.bindDouble(idx++, userLat)
                        stmt.bindDouble(idx++, userLat)
                        stmt.bindDouble(idx++, userLon)
                        stmt.bindDouble(idx++, cosLat)
                        stmt.bindDouble(idx++, userLon)
                        stmt.bindDouble(idx++, cosLat)
                        stmt.bindLong(idx, limitPerPack.toLong())
                        while (stmt.step()) {
                            results.add(
                                TigerRangeRecord(
                                    id = stmt.getLong(0),
                                    stateCode = stateCode.uppercase(),
                                    street = stmt.getText(1),
                                    streetNorm = stmt.getText(2),
                                    city = stmt.getText(3),
                                    zip = stmt.getText(4),
                                    fromLeft = if (stmt.isNull(5)) null else stmt.getLong(5).toInt(),
                                    toLeft = if (stmt.isNull(6)) null else stmt.getLong(6).toInt(),
                                    fromRight = if (stmt.isNull(7)) null else stmt.getLong(7).toInt(),
                                    toRight = if (stmt.isNull(8)) null else stmt.getLong(8).toInt(),
                                    parityLeft = stmt.getText(9),
                                    parityRight = stmt.getText(10),
                                    minLat = stmt.getDouble(11),
                                    minLon = stmt.getDouble(12),
                                    maxLat = stmt.getDouble(13),
                                    maxLon = stmt.getDouble(14),
                                    polyline = stmt.getText(15)
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
        return results
    }

    fun queryGnisNamesInFiles(
        files: List<Pair<String, File>>,
        prefixMatchExpr: String,
        userLat: Double,
        userLon: Double,
        limitPerPack: Int = 30
    ): List<GnisNameRecord> {
        if (files.isEmpty() || prefixMatchExpr.isBlank()) return emptyList()
        val cosLat = cos(Math.toRadians(userLat)).coerceAtLeast(0.15)
        val results = mutableListOf<GnisNameRecord>()

        for ((stateCode, file) in files) {
            if (!isSchemaSupported(file)) continue
            runCatching {
                val conn = sqliteDriver.open(file.absolutePath)
                try {
                    if (!hasTable(conn, "gnis_names") || !hasTable(conn, "gnis_fts")) return@runCatching
                    val sql = """
                        SELECT g.id, g.name, COALESCE(g.feature_class, ''), COALESCE(g.county, ''), g.lat, g.lon
                        FROM gnis_fts f
                        JOIN gnis_names g ON g.id = f.rowid
                        WHERE gnis_fts MATCH ?
                        ORDER BY ((g.lat - ?) * (g.lat - ?) + ((g.lon - ?) * ?) * ((g.lon - ?) * ?)) ASC
                        LIMIT ?
                    """.trimIndent()
                    val stmt = conn.prepare(sql)
                    try {
                        stmt.bindText(1, prefixMatchExpr)
                        stmt.bindDouble(2, userLat)
                        stmt.bindDouble(3, userLat)
                        stmt.bindDouble(4, userLon)
                        stmt.bindDouble(5, cosLat)
                        stmt.bindDouble(6, userLon)
                        stmt.bindDouble(7, cosLat)
                        stmt.bindLong(8, limitPerPack.toLong())
                        while (stmt.step()) {
                            results.add(
                                GnisNameRecord(
                                    id = stmt.getLong(0),
                                    stateCode = stateCode.uppercase(),
                                    name = stmt.getText(1),
                                    featureClass = stmt.getText(2),
                                    county = stmt.getText(3),
                                    lat = stmt.getDouble(4),
                                    lon = stmt.getDouble(5)
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
        return results
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
        val SUPPORTED_SCHEMA_VERSIONS: Set<String> = setOf("1", "2")
    }
}
