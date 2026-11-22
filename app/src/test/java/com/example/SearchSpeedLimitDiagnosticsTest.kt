package com.example

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.hardware.SensorManager
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.test.core.app.ApplicationProvider
import com.example.data.packs.StatePackManager
import com.example.data.preferences.UserSettings
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.EnglishStrings
import com.example.i18n.PersianStrings
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.LastRouteDiagnostics
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.ValhallaResponseParser
import com.example.navigation.VoiceGuidanceManager
import com.example.ui.screens.DiagnosticsPackSnapshot
import com.example.ui.screens.formatDiagnosticsReport
import com.example.util.CrashLogManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchSpeedLimitDiagnosticsTest {

    // -------------------------------------------------------------------------
    // PART 1: Nearest Branch Chain Search (Starbucks, Shell, Walmart, Best Buy)
    // -------------------------------------------------------------------------

    @Test
    fun part1_chainSearchFromEugene_returnsNearestEugeneBranchFirstDespite120StatewideMatches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.cacheDir, "test_or_chain_places.db")
        if (dbFile.exists()) dbFile.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                """
                CREATE TABLE places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    brand TEXT,
                    category TEXT,
                    address TEXT,
                    city TEXT,
                    state TEXT,
                    postcode TEXT,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    popularity REAL DEFAULT 1.0,
                    source TEXT DEFAULT 'overture'
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_places_lat_lon ON places(lat, lon)")

            fun insertPlace(
                name: String,
                brand: String,
                category: String,
                address: String,
                city: String,
                lat: Double,
                lon: Double,
                popularity: Double = 10.0
            ) {
                db.execSQL(
                    """
                    INSERT INTO places(name, brand, category, address, city, state, postcode, lat, lon, popularity, source)
                    VALUES (?, ?, ?, ?, ?, 'OR', '97401', ?, ?, ?, 'overture')
                    """.trimIndent(),
                    arrayOf<Any>(name, brand, category, address, city, lat, lon, popularity)
                )
            }

            // Insert 120 distant Portland branches FIRST with higher popularity so a statewide
            // LIMIT 60 BM25-only query would completely miss Eugene!
            val chains = listOf(
                Triple("Starbucks", "Starbucks", "coffee"),
                Triple("Shell", "Shell", "fuel"),
                Triple("Walmart", "Walmart", "supermarket"),
                Triple("Best Buy", "Best Buy", "electronics")
            )
            for ((chainName, brand, cat) in chains) {
                for (i in 1..120) {
                    val lat = 45.52 + (i % 15) * 0.004
                    val lon = -122.67 - (i / 15) * 0.004
                    insertPlace(
                        name = chainName,
                        brand = brand,
                        category = cat,
                        address = "${1000 + i} NE Broadway",
                        city = "Portland",
                        lat = lat,
                        lon = lon,
                        popularity = 50.0
                    )
                }
            }

            // Now insert Eugene/Springfield branches near (44.0521, -123.0868)
            insertPlace("Starbucks", "Starbucks", "coffee", "810 Willamette St", "Eugene", 44.0512, -123.0920, popularity = 5.0)
            insertPlace("Starbucks", "Starbucks", "coffee", "2850 Willamette St", "Eugene", 44.0274, -123.0915, popularity = 5.0)
            insertPlace("Shell", "Shell", "fuel", "1990 Franklin Blvd", "Eugene", 44.0456, -123.0630, popularity = 5.0)
            insertPlace("Walmart Supercenter", "Walmart", "supermarket", "4550 W 11th Ave", "Eugene", 44.0472, -123.1680, popularity = 5.0)
            insertPlace("Best Buy", "Best Buy", "electronics", "3300 Gateway St", "Springfield", 44.0759, -123.0446, popularity = 5.0)
        } finally {
            db.close()
        }

        val driver = RobolectricFts5EmulatingSqliteDriver()
        val engine = OfflinePlaceSearchEngine(
            packManager = StatePackManager(context, com.example.data.local.RahYabDatabase.getInstance(context).installedPackDao()),
            savedPlaceDao = com.example.data.local.RahYabDatabase.getInstance(context).savedPlaceDao(),
            sqliteDriver = driver
        )

        val eugeneLat = 44.0521
        val eugeneLon = -123.0868

        for (query in listOf("Starbucks", "Shell", "Walmart", "Best Buy")) {
            val results = engine.searchAcrossDatabases(
                query = query,
                userLat = eugeneLat,
                userLon = eugeneLon,
                stateDbFiles = listOf("or" to dbFile),
                limit = 10
            )
            assertTrue("Expected search results for '$query'", results.isNotEmpty())
            val top = results.first()
            assertTrue(
                "Nearest branch for '$query' should be in Eugene/Springfield (< 15 km), got ${top.name} in ${top.city} (${top.distanceMeters.toInt()} m)",
                top.distanceMeters < 15_000.0
            )
        }
        assertTrue("Nearby 50 km bounding-box SQL query must execute before statewide query", driver.executedNearbyQueryCount >= 4)
        assertTrue("Statewide SQL query must also execute", driver.executedStatewideQueryCount >= 4)
    }

    // -------------------------------------------------------------------------
    // PART 2: Speed Limits via Valhalla trace_attributes + Simulate Drive
    // -------------------------------------------------------------------------

    @Test
    fun part2_traceAttributesEnrichesSpeedLimitsAndSimulateDriveReflectsRealLimitsOnly() {
        // 1. Verify polyline6 encode/decode round-trip & trace_attributes request JSON
        val shape = listOf(
            LatLon(44.05210, -123.08680), // 0: I-5 North (edge 0: 0..2, 65 mph / 105 kph)
            LatLon(44.05600, -123.08680), // 1: I-5 North (65 mph)
            LatLon(44.06000, -123.08680), // 2: Exit onto Coburg Rd (edge 1: 2..4, 35 mph / 56 kph)
            LatLon(44.06300, -123.08200), // 3: Coburg Rd (35 mph)
            LatLon(44.06600, -123.08200), // 4: Turn onto untagged Oak St (edge 2: 4..6, no OSM maxspeed)
            LatLon(44.06800, -123.07900), // 5: Untagged side street
            LatLon(44.07000, -123.07900)  // 6: Untagged side street end
        )
        val encoded = ValhallaResponseParser.encodePolyline6(shape)
        val decoded = ValhallaResponseParser.decodePolyline6(encoded)
        assertEquals(shape.size, decoded.size)
        for (i in shape.indices) {
            assertEquals(shape[i].lat, decoded[i].lat, 1e-5)
            assertEquals(shape[i].lon, decoded[i].lon, 1e-5)
        }

        val reqJson = JSONObject(ValhallaResponseParser.buildTraceAttributesRequestJson(encoded))
        assertEquals("edge_walk", reqJson.getString("shape_match"))
        assertEquals("auto", reqJson.getString("costing"))
        val filters = reqJson.getJSONObject("filters")
        assertEquals("include", filters.getString("action"))
        val attrs = filters.getJSONArray("attributes").toString()
        assertTrue(attrs.contains("edge.speed_limit"))
        assertTrue(attrs.contains("edge.begin_shape_index"))
        assertTrue(attrs.contains("edge.end_shape_index"))
        assertTrue(attrs.contains("edge.lane_count"))

        // 2. Build an un-enriched route (just like Valhalla /route returns — no speed_limit on maneuvers)
        val baseRoute = OfflineRoute(
            id = "eugene_i5_coburg",
            summaryLabel = "Via I-5 & Coburg Rd",
            distanceMeters = 2500.0,
            durationSeconds = 180.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Drive north on I-5",
                    streetNames = listOf("I-5"),
                    lengthMeters = 900.0,
                    timeSeconds = 35.0,
                    beginShapeIndex = 0,
                    endShapeIndex = 2,
                    lat = shape[0].lat,
                    lon = shape[0].lon
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.EXIT_RIGHT,
                    instructionEn = "Take exit onto Coburg Rd",
                    streetNames = listOf("Coburg Rd"),
                    lengthMeters = 900.0,
                    timeSeconds = 70.0,
                    beginShapeIndex = 2,
                    endShapeIndex = 4,
                    lat = shape[2].lat,
                    lon = shape[2].lon
                ),
                RouteManeuver(
                    index = 2,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Oak St",
                    streetNames = listOf("Oak St"),
                    lengthMeters = 700.0,
                    timeSeconds = 75.0,
                    beginShapeIndex = 4,
                    endShapeIndex = 6,
                    lat = shape[4].lat,
                    lon = shape[4].lon
                )
            ),
            speedLimitsByShapeIndex = emptyMap()
        )

        // Realistic contiguous Valhalla trace_attributes response (edge.speed_limit in km/h; untagged edge has 0)
        val traceJson = """
            {
              "edges": [
                { "begin_shape_index": 0, "end_shape_index": 2, "speed_limit": 105, "lane_count": 3 },
                { "begin_shape_index": 2, "end_shape_index": 4, "speed_limit": 56, "lane_count": 2 },
                { "begin_shape_index": 4, "end_shape_index": 6, "speed_limit": 0, "lane_count": 1 }
              ]
            }
        """.trimIndent()

        val enriched = ValhallaResponseParser.enrichRouteWithTraceAttributes(baseRoute, traceJson)
        assertEquals(65, enriched.speedLimitsByShapeIndex[0])
        assertEquals(65, enriched.speedLimitsByShapeIndex[1])
        assertEquals(35, enriched.speedLimitsByShapeIndex[2])
        assertEquals(35, enriched.speedLimitsByShapeIndex[3])
        // Untagged street must stay null (never invented, and Coburg Rd's 35 must NOT bleed onto index 4!)
        assertNull(enriched.speedLimitsByShapeIndex[4])
        assertNull(enriched.speedLimitsByShapeIndex[5])
        assertNull(enriched.speedLimitsByShapeIndex[6])
        assertEquals(3, enriched.maneuvers[0].laneCount)
        assertTrue(enriched.maneuvers[1].isMultiLaneRoad)
        assertTrue(
            "Expected SPEED_LIMIT_DROP alert from 65 -> 35 mph",
            enriched.roadAlerts.any { it.kind == RoadAlertKind.SPEED_LIMIT_DROP && it.newSpeedLimitMph == 35 }
        )

        // 3. Simulate drive along I-5 -> Coburg Rd -> untagged Oak St
        val controller = NavigationSessionController()
        var navState = ActiveNavigationState(
            isNavigating = true,
            route = enriched,
            currentManeuverIndex = 0,
            snappedShapeIndex = 0,
            destinationName = "Oak St"
        )
        val settings = UserSettings(language = AppLanguage.ENGLISH, distanceUnit = DistanceUnit.IMPERIAL)

        // On I-5 (index 1): should show 65 mph
        val resI5 = controller.processGpsFix(
            currentState = navState,
            fix = GpsFix(lat = shape[1].lat, lon = shape[1].lon, speedMps = 28f, bearing = 0f, accuracyMeters = 4f, timestampMs = 1_000L),
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = false,
            settings = settings
        )
        navState = resI5.state
        assertEquals(65, navState.currentSpeedLimitMph)

        // At transition onto Coburg Rd (index 2 & 3): should immediately update to 35 mph
        val resCoburgStart = controller.processGpsFix(
            currentState = navState,
            fix = GpsFix(lat = shape[2].lat, lon = shape[2].lon, speedMps = 16f, bearing = 45f, accuracyMeters = 4f, timestampMs = 2_000L),
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = false,
            settings = settings
        )
        navState = resCoburgStart.state
        assertEquals(35, navState.currentSpeedLimitMph)

        val resCoburgMid = controller.processGpsFix(
            currentState = navState,
            fix = GpsFix(lat = shape[3].lat, lon = shape[3].lon, speedMps = 15f, bearing = 0f, accuracyMeters = 4f, timestampMs = 3_000L),
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = false,
            settings = settings
        )
        navState = resCoburgMid.state
        assertEquals(35, navState.currentSpeedLimitMph)

        // On untagged Oak St (index 4 & 5): speed limit MUST be null (hidden!)
        val resUntaggedStart = controller.processGpsFix(
            currentState = navState,
            fix = GpsFix(lat = shape[4].lat, lon = shape[4].lon, speedMps = 10f, bearing = 45f, accuracyMeters = 4f, timestampMs = 4_000L),
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = false,
            settings = settings
        )
        assertNull("Speed limit must be null at start of untagged street", resUntaggedStart.state.currentSpeedLimitMph)

        val resUntaggedMid = controller.processGpsFix(
            currentState = resUntaggedStart.state,
            fix = GpsFix(lat = shape[5].lat, lon = shape[5].lon, speedMps = 10f, bearing = 0f, accuracyMeters = 4f, timestampMs = 5_000L),
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = false,
            settings = settings
        )
        assertNull("Speed limit sign must stay hidden when OSM speed_limit is absent", resUntaggedMid.state.currentSpeedLimitMph)
    }

    // -------------------------------------------------------------------------
    // PART 3: Diagnostics Report, Error Log (last 20 lines), & Persian TTS Guide
    // -------------------------------------------------------------------------

    @Test
    fun part3_errorLogRetainsLast20LinesAndDiagnosticsReportContainsAllRequiredFields() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CrashLogManager.clearErrorLog(context)

        for (i in 1..30) {
            CrashLogManager.logError(context, "DiagTest", "Simulated error #$i")
        }
        val last20 = CrashLogManager.readLastErrorLines(context, 20)
        assertEquals(20, last20.size)
        assertTrue(last20.first().contains("Simulated error #11"))
        assertTrue(last20.last().contains("Simulated error #30"))

        val packs = listOf(
            DiagnosticsPackSnapshot(
                name = "Oregon",
                stateCode = "or",
                buildVersion = "2026.11.01",
                placesSource = "overture+osm",
                fileSizes = StatePackManager.PackFileSizes(
                    pmtilesBytes = 250_000_000L,
                    valhallaBytes = 180_000_000L,
                    placesDbBytes = 95_000_000L
                )
            )
        )
        val nowMs = 1_700_000_010_000L
        val gpsFix = GpsFix(
            lat = 44.0521,
            lon = -123.0868,
            speedMps = 13.4f,
            bearing = 90f,
            accuracyMeters = 3.5f,
            timestampMs = nowMs - 4_000L,
            provider = "fused"
        )
        val routeDiag = LastRouteDiagnostics(
            maneuverCount = 14,
            maneuversWithLaneDataCount = 9,
            shapePointsWithSpeedLimitCount = 412,
            totalShapePoints = 500,
            routingTimeMs = 187L,
            traceAttributesEnriched = true
        )
        val ttsInfo = VoiceGuidanceManager.TtsDiagnosticsInfo(
            enginePackage = "com.google.android.tts",
            engineLabel = "Speech Recognition & Synthesis from Google",
            isInitialized = true,
            isPersianAvailable = false,
            isPersianInstalled = false,
            isEnglishUsAvailable = true,
            isEnglishUsInstalled = true
        )

        val reportEn = formatDiagnosticsReport(
            strings = EnglishStrings,
            language = AppLanguage.ENGLISH,
            installedPacks = packs,
            gpsFix = gpsFix,
            nowMs = nowMs,
            hasFineLocationPermission = true,
            compassHeadingDegrees = 142f,
            compassSensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            lastRoute = routeDiag,
            ttsInfo = ttsInfo,
            errorLines = last20
        )

        assertTrue(reportEn.contains("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        assertTrue(reportEn.contains("Oregon (OR)"))
        assertTrue(reportEn.contains("2026.11.01"))
        assertTrue(reportEn.contains("overture+osm"))
        assertTrue(reportEn.contains("PMTiles="))
        assertTrue(reportEn.contains("Valhalla="))
        assertTrue(reportEn.contains("PlacesDB="))
        assertTrue(reportEn.contains("3.5 m"))
        assertTrue(reportEn.contains("fused"))
        assertTrue(reportEn.contains("4 s"))
        assertTrue(reportEn.contains("Granted"))
        assertTrue(reportEn.contains("142°"))
        assertTrue(reportEn.contains("High"))
        assertTrue(reportEn.contains("9/14"))
        assertTrue(reportEn.contains("412/500"))
        assertTrue(reportEn.contains("187 ms"))
        assertTrue(reportEn.contains("Speech Recognition & Synthesis from Google"))
        assertTrue(reportEn.contains("Simulated error #30"))
        assertFalse(reportEn.contains("Simulated error #10"))

        val reportFa = formatDiagnosticsReport(
            strings = PersianStrings,
            language = AppLanguage.PERSIAN,
            installedPacks = packs,
            gpsFix = gpsFix,
            nowMs = nowMs,
            hasFineLocationPermission = true,
            compassHeadingDegrees = 142f,
            compassSensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
            lastRoute = routeDiag,
            ttsInfo = ttsInfo,
            errorLines = last20
        )
        assertTrue(reportFa.contains(PersianStrings.diagnosticsTitle))
        assertTrue(reportFa.contains(PersianStrings.diagGranted))
        assertTrue(reportFa.contains(PersianStrings.diagSensorHigh))
        assertTrue(PersianStrings.diagPersianInstallSteps.contains("Install voice data"))
        assertNotNull(PersianStrings.diagUseEnglishVoiceInPersian)
    }

    /**
     * Test-only [SQLiteDriver] backed by Robolectric's Android [SQLiteDatabase].
     * Because Robolectric's host SQLite binary omits the FTS5 module (`no such module: fts5`),
     * this adapter translates `places_fts MATCH ?` & `bm25(...)` into equivalent SQL on the real
     * SQLite `places` table while executing the EXACT nearby bounding-box `BETWEEN` + Euclidean
     * distance `ORDER BY` + `LIMIT` and statewide `ORDER BY` + `LIMIT` clauses verbatim.
     */
    private class RobolectricFts5EmulatingSqliteDriver : SQLiteDriver {
        var executedNearbyQueryCount: Int = 0
        var executedStatewideQueryCount: Int = 0

        override fun open(fileName: String): SQLiteConnection {
            val db = SQLiteDatabase.openDatabase(fileName, null, SQLiteDatabase.OPEN_READONLY)
            return object : SQLiteConnection {
                override fun prepare(sql: String): SQLiteStatement = AndroidStatement(db, sql)
                override fun close() = db.close()
            }
        }

        private inner class AndroidStatement(
            private val db: SQLiteDatabase,
            private val originalSql: String
        ) : SQLiteStatement {
            private val args = mutableMapOf<Int, String?>()
            private var cursor: Cursor? = null
            private var executed = false

            override fun bindBlob(index: Int, value: ByteArray) { args[index] = value.decodeToString() }
            override fun bindDouble(index: Int, value: Double) { args[index] = value.toString() }
            override fun bindLong(index: Int, value: Long) { args[index] = value.toString() }
            override fun bindText(index: Int, value: String) { args[index] = value }
            override fun bindNull(index: Int) { args[index] = null }

            override fun step(): Boolean {
                if (!executed) {
                    executed = true
                    val maxIdx = args.keys.maxOrNull() ?: 0
                    val rawArgs = Array(maxIdx) { idx -> args[idx + 1] ?: "" }
                    if (originalSql.contains("places_fts MATCH ?")) {
                        val isNearby = originalSql.contains("p.lat BETWEEN ? AND ?")
                        if (isNearby) executedNearbyQueryCount++ else executedStatewideQueryCount++
                        val ftsExpr = rawArgs.getOrNull(1).orEmpty()
                        val tokens = ftsExpr
                            .replace("*", "")
                            .replace("\"", "")
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() && !it.equals("AND", true) && !it.equals("OR", true) }
                        val tokenWhere = if (tokens.isEmpty()) "1=1" else tokens.joinToString(" AND ") {
                            "(LOWER(p.name || ' ' || COALESCE(p.brand,'') || ' ' || COALESCE(p.category,'') || ' ' || COALESCE(p.address,'') || ' ' || COALESCE(p.city,'')) LIKE ?)"
                        }
                        val tokenArgs = tokens.map { "%${it.lowercase()}%" }
                        val translatedSql: String
                        val translatedArgs: List<String>
                        if (isNearby) {
                            translatedSql = """
                                SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                                       COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                                       COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                                       COALESCE(p.popularity, 1.0), -8.0 AS rank_score
                                FROM places p
                                WHERE $tokenWhere
                                  AND p.lat BETWEEN ? AND ?
                                  AND p.lon BETWEEN ? AND ?
                                ORDER BY ((p.lat - ?) * (p.lat - ?) + (p.lon - ?) * (p.lon - ?) * ?) ASC
                                LIMIT ?
                            """.trimIndent()
                            translatedArgs = buildList {
                                add(rawArgs[0])
                                addAll(tokenArgs)
                                for (i in 2 until rawArgs.size) add(rawArgs[i])
                            }
                        } else {
                            translatedSql = """
                                SELECT p.id, p.name, COALESCE(p.brand, ''), COALESCE(p.category, ''),
                                       COALESCE(p.address, ''), COALESCE(p.city, ''), COALESCE(p.state, ?),
                                       COALESCE(p.postcode, ''), p.lat, p.lon, COALESCE(p.source, 'overture'),
                                       COALESCE(p.popularity, 1.0), -8.0 AS rank_score
                                FROM places p
                                WHERE $tokenWhere
                                ORDER BY rank_score ASC, p.id ASC
                                LIMIT ?
                            """.trimIndent()
                            translatedArgs = buildList {
                                add(rawArgs[0])
                                addAll(tokenArgs)
                                add(rawArgs.last())
                            }
                        }
                        cursor = db.rawQuery(translatedSql, translatedArgs.toTypedArray())
                    } else {
                        cursor = db.rawQuery(originalSql, rawArgs)
                    }
                }
                return cursor?.moveToNext() ?: false
            }

            override fun getBlob(index: Int): ByteArray = cursor!!.getBlob(index)
            override fun getDouble(index: Int): Double = cursor!!.getDouble(index)
            override fun getLong(index: Int): Long = cursor!!.getLong(index)
            override fun getText(index: Int): String = cursor!!.getString(index) ?: ""
            override fun isNull(index: Int): Boolean = cursor!!.isNull(index)
            override fun getColumnCount(): Int = cursor?.columnCount ?: 0
            override fun getColumnName(index: Int): String = cursor!!.getColumnName(index)
            override fun getColumnType(index: Int): Int = cursor!!.getType(index)
            override fun reset() {
                cursor?.close()
                cursor = null
                executed = false
            }
            override fun clearBindings() { args.clear() }
            override fun close() {
                cursor?.close()
                cursor = null
            }
        }
    }
}
