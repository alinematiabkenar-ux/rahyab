package com.example

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BackupAndTransferManager
import com.example.data.local.ReportType
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.local.UserReportEntity
import com.example.data.model.UsStatesCatalog
import com.example.data.packs.StatePackManager
import com.example.data.preferences.UserSettings
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.MissingStatesException
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.RouteOptions
import com.example.navigation.ValhallaResponseParser
import com.example.navigation.VoiceGuidanceManager
import kotlinx.coroutines.test.runTest
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
@Config(sdk = [35])
class RahYabNavigationTests {

    private fun execBundledSql(dbFile: File, statements: List<String>) {
        val driver = BundledSQLiteDriver()
        val conn = driver.open(dbFile.absolutePath)
        try {
            for (sql in statements) {
                val stmt = conn.prepare(sql)
                try {
                    stmt.step()
                } finally {
                    stmt.close()
                }
            }
        } finally {
            conn.close()
        }
    }

    private fun createTempOregonFts5Db(context: Context): File {
        val dbFile = File(context.cacheDir, "test_or_places_${System.nanoTime()}.db")
        execBundledSql(
            dbFile,
            listOf(
                """
                CREATE TABLE places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    brand TEXT DEFAULT '',
                    category TEXT DEFAULT '',
                    address TEXT DEFAULT '',
                    city TEXT DEFAULT '',
                    state TEXT DEFAULT '',
                    postcode TEXT DEFAULT '',
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    source TEXT DEFAULT 'overture',
                    popularity REAL DEFAULT 1.0
                );
                """.trimIndent(),
                """
                CREATE VIRTUAL TABLE places_fts USING fts5(
                    name, brand, category, address, city, postcode,
                    content='places', content_rowid='id',
                    tokenize='unicode61 remove_diacritics 2', prefix='2 3 4'
                );
                """.trimIndent(),
                """
                INSERT INTO places (name, brand, category, address, city, state, postcode, lat, lon, source, popularity) VALUES
                ('Best Buy', 'Best Buy', 'electronics_store', '3300 Gateway St', 'Springfield', 'OR', '97477', 44.0793, -123.0439, 'overture', 5.0),
                ('Best Buy', 'Best Buy', 'electronics_store', '9910 NE Cascades Pkwy', 'Portland', 'OR', '97220', 45.5734, -122.5594, 'overture', 5.0),
                ('Walgreens Pharmacy', 'Walgreens', 'pharmacy', '1675 Coburg Rd', 'Eugene', 'OR', '97401', 44.0762, -123.0686, 'overture', 4.2),
                ('Chevron Gas Station', 'Chevron', 'gas_station', '1205 Franklin Blvd', 'Eugene', 'OR', '97403', 44.0457, -123.0763, 'overture', 4.0),
                ('Fred Meyer', 'Fred Meyer', 'supermarket grocery_store', '60 Division Ave', 'Eugene', 'OR', '97404', 44.0938, -123.1281, 'overture', 4.5);
                """.trimIndent(),
                "INSERT INTO places_fts(places_fts) VALUES('rebuild');"
            )
        )
        return dbFile
    }

    private fun createTempWashingtonFts5Db(context: Context): File {
        val dbFile = File(context.cacheDir, "test_wa_places_${System.nanoTime()}.db")
        execBundledSql(
            dbFile,
            listOf(
                """
                CREATE TABLE places (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    brand TEXT DEFAULT '',
                    category TEXT DEFAULT '',
                    address TEXT DEFAULT '',
                    city TEXT DEFAULT '',
                    state TEXT DEFAULT '',
                    postcode TEXT DEFAULT '',
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    source TEXT DEFAULT 'overture',
                    popularity REAL DEFAULT 1.0
                );
                """.trimIndent(),
                """
                CREATE VIRTUAL TABLE places_fts USING fts5(
                    name, brand, category, address, city, postcode,
                    content='places', content_rowid='id',
                    tokenize='unicode61 remove_diacritics 2', prefix='2 3 4'
                );
                """.trimIndent(),
                """
                INSERT INTO places (name, brand, category, address, city, state, postcode, lat, lon, source, popularity) VALUES
                ('Pike Place Market', 'Pike Place Market', 'market', '85 Pike St', 'Seattle', 'WA', '98101', 47.6097, -122.3422, 'overture', 5.0),
                ('REI Flagship Store', 'REI', 'sporting_goods', '222 Yale Ave N', 'Seattle', 'WA', '98109', 47.6199, -122.3304, 'overture', 4.9);
                """.trimIndent(),
                "INSERT INTO places_fts(places_fts) VALUES('rebuild');"
            )
        )
        return dbFile
    }

    // ==================== PHASE 1 TESTS ====================

    @Test
    fun `Phase 1 - Best Buy near Eugene Oregon returns Gateway St Springfield first`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = createTempOregonFts5Db(context)
        try {
            val app = context as RahYabApplication
            val results = app.offlineSearchEngine.searchAcrossDatabases(
                query = "Best Buy",
                userLat = 44.0521,
                userLon = -123.0868,
                stateDbFiles = listOf("or" to dbFile)
            )
            assertTrue("Expected Best Buy results", results.isNotEmpty())
            val top = results.first()
            assertEquals("Best Buy", top.name)
            assertTrue("Expected 3300 Gateway St near Eugene", top.formattedAddress.contains("3300 Gateway St"))
            assertTrue("Expected Eugene/Springfield store closer than Portland", top.distanceMeters < 10_000.0)
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun `Phase 1 - Persian transliteration, categories, typos, and saved places work offline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = createTempOregonFts5Db(context)
        try {
            val app = context as RahYabApplication

            val bestBuyFa = app.offlineSearchEngine.searchAcrossDatabases("بست بای", 44.0521, -123.0868, listOf("or" to dbFile))
            assertTrue("Expected Best Buy from بست بای", bestBuyFa.any { it.name == "Best Buy" })

            val pharmacyFa = app.offlineSearchEngine.searchAcrossDatabases("داروخانه", 44.0521, -123.0868, listOf("or" to dbFile))
            assertTrue("Expected Pharmacy from داروخانه", pharmacyFa.any { it.category.contains("pharmacy") })

            val gasFa = app.offlineSearchEngine.searchAcrossDatabases("پمپ بنزین", 44.0521, -123.0868, listOf("or" to dbFile))
            assertTrue("Expected Gas Station from پمپ بنزین", gasFa.any { it.category.contains("gas_station") })

            val groceryFa = app.offlineSearchEngine.searchAcrossDatabases("سوپرمارکت", 44.0521, -123.0868, listOf("or" to dbFile))
            assertTrue("Expected Supermarket from سوپرمارکت", groceryFa.any { it.name == "Fred Meyer" })

            val typoResults = app.offlineSearchEngine.searchAcrossDatabases("Bset Buy", 44.0521, -123.0868, listOf("or" to dbFile))
            assertTrue("Expected Best Buy despite typo", typoResults.any { it.name == "Best Buy" })

            val savedHome = SavedPlaceEntity(
                id = 1L, type = SavedPlaceType.HOME.name, name = "My Home",
                address = "100 Willamette St, Eugene, OR", category = "home",
                lat = 44.05, lon = -123.09, stateCode = "or"
            )
            val homeResults = app.offlineSearchEngine.searchAcrossDatabases("خانه", 44.0521, -123.0868, listOf("or" to dbFile), listOf(savedHome))
            assertEquals("My Home", homeResults.firstOrNull()?.name)
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun `Phase 1 - Corrupted database and invalid PMTiles never crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as RahYabApplication
        val corruptDb = File(context.cacheDir, "corrupt_places.db").apply { writeText("not a sqlite file") }
        val corruptPm = File(context.cacheDir, "corrupt.pmtiles").apply { writeText("bad header") }
        try {
            assertFalse(app.packManager.isValidPlacesDb(corruptDb))
            assertFalse(app.packManager.isValidPmtilesFile(corruptPm))
            val results = app.offlineSearchEngine.searchAcrossDatabases("Best Buy", 44.0521, -123.0868, listOf("or" to corruptDb))
            assertTrue(results.isEmpty())
        } finally {
            corruptDb.delete()
            corruptPm.delete()
        }
    }

    // ==================== PHASE 2 & PHASE 3 TESTS ====================

    @Test
    fun `Phase 2 and 3 - I-5 Eugene to Portland parsing, lanes, signposts, English and Persian voice, and strict empty-lanes honesty`() {
        val sampleValhallaJson = """
        {
          "trip": {
            "summary": { "length": 176.5, "time": 6300.0 },
            "legs": [
              {
                "shape": "}`mkG~|pnV??",
                "maneuvers": [
                  {
                    "type": 20,
                    "instruction": "Take exit 194B on the right onto I-105 West.",
                    "street_names": ["I-105 W"],
                    "length": 4.2,
                    "time": 180.0,
                    "begin_shape_index": 0,
                    "end_shape_index": 1,
                    "speed_limit": 105,
                    "sign": {
                      "exit_number_elements": [{"text": "194B"}],
                      "exit_branch_elements": [{"text": "I-105 W"}],
                      "exit_toward_elements": [{"text": "Portland"}]
                    },
                    "turn_lanes": [
                      {"indications": ["through"], "valid": false},
                      {"indications": ["through", "slight_right"], "active_direction": "slight_right", "valid": true},
                      {"indications": ["slight_right"], "active_direction": "slight_right", "valid": true}
                    ]
                  },
                  {
                    "type": 10,
                    "instruction": "Turn right onto Gateway St.",
                    "street_names": ["Gateway St"],
                    "length": 1.1,
                    "time": 90.0,
                    "begin_shape_index": 1,
                    "end_shape_index": 1,
                    "speed_limit": 56
                  },
                  {
                    "type": 15,
                    "instruction": "Turn left onto Franklin Blvd.",
                    "street_names": ["Franklin Blvd"],
                    "length": 0.4,
                    "time": 45.0,
                    "begin_shape_index": 1,
                    "end_shape_index": 1
                  }
                ]
              }
            ]
          },
          "alternates": [
            {
              "trip": {
                "summary": { "length": 185.2, "time": 7100.0 },
                "legs": [
                  {
                    "shape": "}`mkG~|pnV??",
                    "maneuvers": [
                      {
                        "type": 8,
                        "instruction": "Continue on OR-99W.",
                        "street_names": ["OR-99W"],
                        "length": 185.2,
                        "time": 7100.0,
                        "begin_shape_index": 0,
                        "end_shape_index": 1,
                        "toll": true
                      }
                    ]
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val routes = ValhallaResponseParser.parseRoutes(sampleValhallaJson)
        assertEquals(2, routes.size)

        val primary = routes[0]
        assertEquals(3, primary.maneuvers.size)

        val exitManeuver = primary.maneuvers[0]
        assertEquals(3, exitManeuver.lanes.size)
        assertFalse(exitManeuver.lanes[0].isRecommended)
        assertTrue(exitManeuver.lanes[1].isRecommended)
        assertTrue(exitManeuver.lanes[2].isRecommended)
        assertEquals("194B", exitManeuver.signpost?.primaryExitNumber)
        assertEquals("I-105 W", exitManeuver.signpost?.primaryRoadNumbers)

        val voiceEn = VoiceGuidanceManager.formatSpokenInstruction(exitManeuver, 1609.0, DistanceUnit.IMPERIAL, AppLanguage.ENGLISH)
        assertTrue("Expected natural English lane + exit 194B: $voiceEn", voiceEn.contains("right two lanes") && voiceEn.contains("194B") && voiceEn.contains("Portland"))

        val voiceFa = VoiceGuidanceManager.formatSpokenInstruction(exitManeuver, 1609.0, DistanceUnit.IMPERIAL, AppLanguage.PERSIAN)
        assertTrue("Expected natural Persian lane + exit 194B: $voiceFa", voiceFa.contains("دو خط سمت راست") && voiceFa.contains("194B") && voiceFa.contains("Portland"))

        // Strict honesty: missing turn_lanes -> empty list (never invented!)
        assertTrue("Never invent lanes when Valhalla omits turn_lanes", primary.maneuvers[1].lanes.isEmpty())
        // Strict honesty: missing speed_limit -> null (never guessed!)
        assertNull("Never guess speed limit when OSM maxspeed is absent", primary.maneuvers[2].speedLimitMph)

        // Speed limit drop alert (65 mph -> 34 mph)
        assertTrue(primary.roadAlerts.any { it.kind == RoadAlertKind.SPEED_LIMIT_DROP })
        assertTrue(routes[1].hasTolls)
        assertTrue(routes[1].roadAlerts.any { it.kind == RoadAlertKind.TOLL_BOOTH })
    }

    @Test
    fun `Phase 2 and 3 - Wrong turn rerouting, snap-to-road, tunnel dead-reckoning, speed thresholds, and stop sign toggle`() {
        val controller = NavigationSessionController()
        val route = OfflineRoute(
            id = "r0",
            summaryLabel = "UO to Best Buy",
            distanceMeters = 3000.0,
            durationSeconds = 240.0,
            shape = listOf(LatLon(44.0450, -123.0750), LatLon(44.0600, -123.0750), LatLon(44.0750, -123.0750)),
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Gateway St",
                    streetNames = listOf("Gateway St"), lengthMeters = 1500.0, timeSeconds = 120.0,
                    beginShapeIndex = 1, endShapeIndex = 2, lat = 44.0600, lon = -123.0750, speedLimitMph = 45
                )
            )
        )

        val stopSignHazard = RoadHazardPoint(1L, "stop_sign", 44.0470, -123.0750, "Stop Sign", null)

        // 1. Default settings: stop signs are OFF by default, threshold is +5 mph
        val defaultSettings = UserSettings()
        assertFalse(defaultSettings.alertStopSignEnabled)
        val onRouteFix = GpsFix(44.0455, -123.0749, bearing = 0f, speedMps = 21.5f, accuracyMeters = 4f, timestampMs = System.currentTimeMillis()) // ~48 mph (< 45+5)
        val u1 = controller.processGpsFix(ActiveNavigationState(isNavigating = true, route = route), onRouteFix, listOf(stopSignHazard), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, true, defaultSettings)
        assertFalse("48 mph should NOT trigger +5 mph threshold on 45 mph road", u1.state.isOverspeed)
        assertNull("Stop signs must be OFF by default", u1.state.activeAheadAlert)
        assertNotNull("Snapped location should be computed", u1.state.snappedLocation)

        // 2. Threshold = 0 mph + Stop signs ON -> both trigger!
        val strictSettings = defaultSettings.copy(speedWarningThresholdMph = 0, alertStopSignEnabled = true)
        val u2 = controller.processGpsFix(u1.state, onRouteFix, listOf(stopSignHazard), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, true, strictSettings)
        assertTrue("48 mph MUST trigger +0 mph threshold on 45 mph road", u2.state.isOverspeed)
        assertEquals(RoadAlertKind.STOP_SIGN, u2.state.activeAheadAlert?.kind)

        // 3. Dead-reckoning tunnel mode advances position and flags GPS signal lost
        val deadReckoned = controller.advanceDeadReckoning(u2.state, elapsedSeconds = 2.0)
        assertTrue(deadReckoned.isGpsSignalLost)
        assertTrue(deadReckoned.remainingDistanceMeters < u2.state.remainingDistanceMeters)

        // 4. Three consecutive off-route fixes (> 48m away) trigger automatic rerouting
        val offRouteFix = GpsFix(44.0455, -123.0700, bearing = 90f, speedMps = 15f, accuracyMeters = 5f, timestampMs = System.currentTimeMillis())
        val r1 = controller.processGpsFix(u2.state, offRouteFix, emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false)
        val r2 = controller.processGpsFix(r1.state, offRouteFix, emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false)
        val r3 = controller.processGpsFix(r2.state, offRouteFix, emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false)
        assertTrue("Expected automatic rerouting after 3 off-route updates", r3.shouldReroute)
    }

    // ==================== PHASE 4 TESTS ====================

    @Test
    fun `Phase 4 - Cross-state Eugene OR to Seattle WA corridor check, missing state detection, and cross-state search`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as RahYabApplication
        val eugene = LatLon(44.0521, -123.0868)
        val seattle = LatLon(47.6062, -122.3321)

        // All 50 states + D.C. present + regional packs for CA, TX, FL, NY
        assertEquals(51, UsStatesCatalog.allStates.size)
        assertTrue(UsStatesCatalog.regionsByParent.containsKey("ca"))
        assertTrue(UsStatesCatalog.regionsByParent.containsKey("tx"))
        assertTrue(UsStatesCatalog.regionsByParent.containsKey("fl"))
        assertTrue(UsStatesCatalog.regionsByParent.containsKey("ny"))

        // With BOTH OR & WA installed -> zero missing states along Eugene -> Seattle
        val missingBothInstalled = app.routingEngine.detectMissingStatesForTrip(eugene, seattle, setOf("or", "wa"))
        assertTrue("Expected no missing states when OR and WA are installed", missingBothInstalled.isEmpty())

        // With ONLY OR installed -> detects Washington (WA) as missing!
        val missingWa = app.routingEngine.detectMissingStatesForTrip(eugene, seattle, setOf("or"))
        assertTrue("Expected Washington to be detected as missing", missingWa.any { it.code == "wa" })

        // Offline routing call without WA fails cleanly with MissingStatesException (never fake straight line)
        val routeResult = app.routingEngine.calculateRoutes(
            origin = eugene,
            destination = seattle,
            options = RouteOptions(),
            allowDevOnlineFallback = false
        )
        assertTrue(routeResult.isFailure)
        assertTrue(routeResult.exceptionOrNull() is MissingStatesException)

        // Cross-state place search: located in Eugene, OR searching for a Seattle, WA landmark
        val orDb = createTempOregonFts5Db(context)
        val waDb = createTempWashingtonFts5Db(context)
        try {
            val crossStateResults = app.offlineSearchEngine.searchAcrossDatabases(
                query = "Pike Place Market",
                userLat = eugene.lat,
                userLon = eugene.lon,
                stateDbFiles = listOf("or" to orDb, "wa" to waDb)
            )
            assertTrue("Expected Seattle WA store found while located in Eugene OR", crossStateResults.any { it.name == "Pike Place Market" && it.stateCode == "wa" })
        } finally {
            orDb.delete()
            waDb.delete()
        }
    }

    @Test
    fun `Phase 4 - Road closure passed as Valhalla exclude_locations and all 8 report types work with export-import`() {
        // Verify all 8 Waze-style report types exist with non-zero TTLs
        assertEquals(8, ReportType.entries.size)
        assertEquals(60, ReportType.POLICE.defaultTtlMinutes)
        assertEquals(720, ReportType.ROAD_CLOSURE.defaultTtlMinutes)
        assertEquals(14 * 24 * 60, ReportType.CONSTRUCTION.defaultTtlMinutes)

        // Verify Road Closure location is serialized into Valhalla `exclude_locations` so routing avoids it
        val closedRoad = LatLon(44.0612, -123.0680)
        val reqJson = ValhallaResponseParser.buildRouteRequestJson(
            origin = LatLon(44.0521, -123.0868),
            destination = LatLon(44.0793, -123.0439),
            options = RouteOptions(avoidLocations = listOf(closedRoad))
        )
        assertTrue("Expected exclude_locations in Valhalla request JSON", reqJson.contains("exclude_locations") && reqJson.contains("44.0612"))

        // Export & import reports JSON round-trip
        val now = System.currentTimeMillis()
        val sampleReports = listOf(
            UserReportEntity(
                id = 1L, type = ReportType.ROAD_CLOSURE.name, lat = 44.0612, lon = -123.0680,
                bearing = 45f, roadName = "Coburg Rd", note = "Bridge closed",
                createdAt = now, expiresAt = now + 3_600_000L
            ),
            UserReportEntity(
                id = 2L, type = ReportType.STALLED_VEHICLE.name, lat = 44.0700, lon = -123.0500,
                bearing = 0f, roadName = "I-5", createdAt = now, expiresAt = now + 3_600_000L
            )
        )
        val exported = BackupAndTransferManager.exportReportsJson(sampleReports)
        val reimported = BackupAndTransferManager.importReportsJson(exported, now)
        assertEquals(2, reimported.size)
        assertEquals("Bridge closed", reimported[0].note)
        assertEquals(ReportType.STALLED_VEHICLE.name, reimported[1].type)
    }

    // ==================== PHASE 5 & FINAL STEP TESTS ====================

    @Test
    fun `Phase 5 and Final Step - GitHub URL derivation, multi-part join + SHA-256, and full settings + saved places backup`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Phase 5: GitHub Releases URL derivation from owner + repo
        val ghSettings = UserSettings(githubOwner = "ali-user", githubRepo = "rahyab-us-maps", packBaseUrl = "")
        assertEquals("https://github.com/ali-user/rahyab-us-maps/releases/latest/download", ghSettings.effectivePackBaseUrl)

        // 2. Phase 5: Multi-part asset rejoining (> 2 GiB GitHub asset split support) + SHA-256 verification
        val p1 = File(context.cacheDir, "chunk.part001").apply { writeBytes("RAHYAB_PART_ONE_".toByteArray()) }
        val p2 = File(context.cacheDir, "chunk.part002").apply { writeBytes("RAHYAB_PART_TWO".toByteArray()) }
        val joined = File(context.cacheDir, "chunk.joined")
        try {
            StatePackManager.joinPartFiles(listOf(p1, p2), joined)
            assertEquals("RAHYAB_PART_ONE_RAHYAB_PART_TWO", joined.readText())
            assertEquals(64, StatePackManager.sha256Of(joined).length)
        } finally {
            p1.delete()
            p2.delete()
            joined.delete()
        }

        // 3. Final Step: Full Settings + Saved Places export & import for moving to a new phone
        val places = listOf(
            SavedPlaceEntity(
                type = SavedPlaceType.HOME.name, name = "Home Eugene",
                address = "123 Oak St, Eugene, OR", category = "home",
                lat = 44.05, lon = -123.09, stateCode = "or"
            ),
            SavedPlaceEntity(
                type = SavedPlaceType.FAVORITE.name, name = "Best Buy",
                address = "3300 Gateway St, Springfield, OR", category = "electronics_store",
                lat = 44.0793, lon = -123.0439, stateCode = "or"
            )
        )
        val backupSettings = ghSettings.copy(
            language = AppLanguage.PERSIAN,
            distanceUnit = DistanceUnit.METRIC,
            speedWarningThresholdMph = 10,
            alertStopSignEnabled = true
        )
        val backupJson = BackupAndTransferManager.exportFullBackupJson(backupSettings, places, emptyList())
        val restored = BackupAndTransferManager.importFullBackupJson(backupJson)
        assertEquals(AppLanguage.PERSIAN, restored.settings?.language)
        assertEquals(DistanceUnit.METRIC, restored.settings?.distanceUnit)
        assertEquals(10, restored.settings?.speedWarningThresholdMph)
        assertEquals(true, restored.settings?.alertStopSignEnabled)
        assertEquals("ali-user", restored.settings?.githubOwner)
        assertEquals(2, restored.savedPlaces.size)
        assertEquals("Home Eugene", restored.savedPlaces[0].name)

        // Persian digits check
        assertEquals("۴۵", BilingualFormatter.formatNumber(45, AppLanguage.PERSIAN))
    }
}
