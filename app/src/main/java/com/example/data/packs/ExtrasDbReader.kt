package com.example.data.packs

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * X6 & X7: Safe, read-only SQLite reader for optional `<state>_extras.db` files.
 * Validates `meta.schema_version == "1"` before querying, caches validity per file mtime,
 * and fails silently (returning empty/null) whenever `extras.db` is absent, outdated, or corrupt.
 */
class ExtrasDbReader(
    private val packManager: StatePackManager
) {
    private val schemaValidityCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun hasAnyValidExtrasDb(): Boolean =
        packManager.getInstalledExtrasDatabases().any { (_, file) -> isSchemaV1Valid(file) }

    fun queryFraCrossingsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limitPerPack: Int = 1500
    ): List<FraCrossingRecord> {
        val files = packManager.getInstalledExtrasDatabases()
        if (files.isEmpty()) return emptyList()
        val results = mutableListOf<FraCrossingRecord>()
        for ((_, file) in files) {
            if (!isSchemaV1Valid(file)) continue
            runCatching {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                ).use { db ->
                    db.rawQuery(
                        """
                        SELECT crossing_id, lat, lon, street, railroad, has_gates, has_lights, tracks, daily_trains
                        FROM fra_crossings
                        WHERE lat >= ? AND lat <= ? AND lon >= ? AND lon <= ?
                        LIMIT ?
                        """.trimIndent(),
                        arrayOf(
                            minLat.toString(),
                            maxLat.toString(),
                            minLon.toString(),
                            maxLon.toString(),
                            limitPerPack.toString()
                        )
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            results.add(
                                FraCrossingRecord(
                                    crossingId = cursor.getString(0).orEmpty(),
                                    lat = cursor.getDouble(1),
                                    lon = cursor.getDouble(2),
                                    street = cursor.getString(3).orEmpty(),
                                    railroad = cursor.getString(4).orEmpty(),
                                    hasGates = cursor.getInt(5) != 0,
                                    hasLights = cursor.getInt(6) != 0,
                                    tracks = cursor.getInt(7),
                                    dailyTrains = cursor.getInt(8)
                                )
                            )
                        }
                    }
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
    ): List<HpmsSpeedRecord> {
        val files = packManager.getInstalledExtrasDatabases()
        if (files.isEmpty()) return emptyList()
        val results = mutableListOf<HpmsSpeedRecord>()
        for ((_, file) in files) {
            if (!isSchemaV1Valid(file)) continue
            runCatching {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                ).use { db ->
                    db.rawQuery(
                        """
                        SELECT id, speed_mph, route_name, min_lat, min_lon, max_lat, max_lon, polyline
                        FROM hpms_speed
                        WHERE min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?
                        LIMIT ?
                        """.trimIndent(),
                        arrayOf(
                            (lat + padDeg).toString(),
                            (lat - padDeg).toString(),
                            (lon + padDeg).toString(),
                            (lon - padDeg).toString(),
                            limitPerPack.toString()
                        )
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            results.add(
                                HpmsSpeedRecord(
                                    id = cursor.getLong(0),
                                    speedMph = cursor.getInt(1),
                                    routeName = cursor.getString(2).orEmpty(),
                                    minLat = cursor.getDouble(3),
                                    minLon = cursor.getDouble(4),
                                    maxLat = cursor.getDouble(5),
                                    maxLon = cursor.getDouble(6),
                                    polyline = cursor.getString(7).orEmpty()
                                )
                            )
                        }
                    }
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

    private fun isSchemaV1Valid(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        val path = file.absolutePath
        val mtime = file.lastModified()
        schemaValidityCache[path]?.let { (cachedMtime, valid) ->
            if (cachedMtime == mtime) return valid
        }
        val valid = runCatching {
            SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key = 'schema_version' LIMIT 1", null).use { c ->
                    c.moveToFirst() && c.getString(0)?.trim() == "1"
                }
            }
        }.getOrDefault(false)
        schemaValidityCache[path] = mtime to valid
        return valid
    }
}
