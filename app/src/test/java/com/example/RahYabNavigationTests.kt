package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ReportType
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.local.UserReportEntity
import com.example.data.model.UsStatesCatalog
import com.example.data.packs.StatePackManager
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PersianQueryNormalizer
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDirection
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationSessionController
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.RouteOptions
import com.example.navigation.ValhallaResponseParser
import com.example.navigation.VoiceGuidanceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RahYabNavigationTests {

    private fun createTempOregonFts5Db(context: Context): File {
        val dbFile = File(context.cacheDir, "test_or_places_${System.nanoTime()}.db")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL(
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
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE places_fts USING fts5(
                name, brand, category, address, city, postcode,
                content='places', content_rowid='id',
                tokenize='unicode61 remove_diacritics 2', prefix='2 3 4'
            );
            """.trimIndent()
        )

        // Real Oregon locations (Eugene / Springfield / Portland) matching data-pipeline schema
        db.execSQL(
            """
            INSERT INTO places (name, brand, category, address, city, state, postcode, lat, lon, source, popularity) VALUES
            ('Best Buy', 'Best Buy', 'electronics_store', '3300 Gateway St', 'Springfield', 'OR', '97477', 44.0793, -123.0439, 'overture', 5.0),
            ('Best Buy', 'Best Buy', 'electronics_store', '9910 NE Cascades Pkwy', 'Portland', 'OR', '97220', 45.5734, -122.5594, 'overture', 5.0),
            ('Walgreens Pharmacy', 'Walgreens', 'pharmacy', '1675 Coburg Rd', 'Eugene', 'OR', '97401', 44.0762, -123.0686, 'overture', 4.2),
            ('Chevron Gas Station', 'Chevron', 'gas_station', '1205 Franklin Blvd', 'Eugene', 'OR', '97403', 44.0457, -123.0763, 'overture', 4.0),
            ('Fred Meyer', 'Fred Meyer', 'supermarket grocery_store', '60 Division Ave', 'Eugene', 'OR', '97404', 44.0938, -123.1281, 'overture', 4.5);
            """.trimIndent()
        )
        db.execSQL("INSERT INTO places_fts(places_fts) VALUES('rebuild');")
        db.close()
        return dbFile
    }

    @Test
    fun `Phase 1 - Best Buy near Eugene Oregon search returns Eugene area Best Buy first`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = createTempOregonFts5Db(context)
        try {
            val app = context as RahYabApplication
            val results = app.offlineSearchEngine.searchAcrossDatabases(
                query = "Best Buy",
                userLat = 44.0521, // Eugene, OR
                userLon = -123.0868,
                stateDbFiles = listOf("or" to dbFile)
            )

            assertTrue("Expected Best Buy results", results.isNotEmpty())
            val top = results.first()
            assertEquals("Best Buy", top.name)
            assertTrue("Expected Gateway St address near Eugene", top.formattedAddress.contains("3300 Gateway St"))
            assertTrue("Expected Springfield/Eugene area closer than Portland", top.distanceMeters < 10_000.0)
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun `Phase 1 - Persian transliteration and categories work offline in FTS5`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = createTempOregonFts5Db(context)
        try {
            val app = context as RahYabApplication

            // Persian transliterated brand: "بست بای" -> Best Buy
            val bestBuyFa = app.offlineSearchEngine.searchAcrossDatabases(
                query = "بست بای",
                userLat = 44.0521,
                userLon = -123.0868,
                stateDbFiles = listOf("or" to dbFile)
            )
            assertTrue("Expected Best Buy from Persian query بست بای", bestBuyFa.any { it.name == "Best Buy" })

            // Persian category: "داروخانه" -> pharmacy
            val pharmacyFa = app.offlineSearchEngine.searchAcrossDatabases(
                query = "داروخانه",
                userLat = 44.0521,
                userLon = -123.0868,
                stateDbFiles = listOf("or" to dbFile)
            )
            assertTrue("Expected Pharmacy from داروخانه", pharmacyFa.any { it.category.contains("pharmacy") })

            // Persian category: "پمپ بنزین" -> gas station
            val gasFa = app.offlineSearchEngine.searchAcrossDatabases(
                query = "پمپ بنزین",
                userLat = 44.0521,
                userLon = -123.0868,
                stateDbFiles = listOf("or" to dbFile)
            )
            assertTrue("Expected Gas Station from پمپ بنزین", gasFa.any { it.category.contains("gas_station") })

            // Typo tolerance: "Bset Buy" -> Best Buy
            val typoResults = app.offlineSearchEngine.searchAcrossDatabases(
                query = "Bset Buy",
                userLat = 44.0521,
                userLon = -123.0868,
                stateDbFiles = listOf("or" to dbFile)
            )
            assertTrue("Expected Best Buy despite typo", typoResults.any { it.name == "Best Buy" })

            // Saved place search ("Home" & "خانه")
            val savedHome = SavedPlaceEntity(
                id = 1L,
                type = SavedPlaceType.HOME.name,
                name = "My Home",
                address = "100 Willamette St, Eugene, OR",
                category = "home",
                lat = 44.05,
                lon = -123.09,
                stateCode = "or"
            )
            val homeResults = app.offlineSearchEngine.searchAcrossDatabases(
                query = "خانه",
                userLat = 44.0521,
                userLon = -123.0868,
                stateDbFiles = listOf("or" to dbFile),
                savedPlaces = listOf(savedHome)
            )
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

            val results = app.offlineSearchEngine.searchAcrossDatabases(
                query = "Best Buy",
                userLat = 44.0521,
                userLon = -123.0868,
                stateDbFiles = listOf("or" to corruptDb)
            )
            assertTrue(results.isEmpty())
        } finally {
            corruptDb.delete()
            corruptPm.delete()
        }
    }

    @Test
    fun `Phase 2 and 3 - Valhalla route parsing, lane guidance, signposts, and strict empty-lanes honesty`() {
        val sampleValhallaJson = """
        {
          "trip": {
            "summary": { "length": 14.5, "time": 780.0 },
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
                      "exit_toward_elements": [{"text": "Eugene"}, {"text": "Springfield"}]
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
                  }
                ]
              }
            ]
          },
          "alternates": [
            {
              "trip": {
                "summary": { "length": 16.2, "time": 900.0 },
                "legs": [
                  {
                    "shape": "}`mkG~|pnV??",
                    "maneuvers": [
                      {
                        "type": 8,
                        "instruction": "Continue on Beltline Rd.",
                        "street_names": ["Beltline Rd"],
                        "length": 16.2,
                        "time": 900.0,
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
        assertEquals(2, primary.maneuvers.size)

        // Maneuver 0 has explicit 3 lanes with right 2 lanes recommended + Exit 194B signpost
        val exitManeuver = primary.maneuvers[0]
        assertEquals(3, exitManeuver.lanes.size)
        assertFalse(exitManeuver.lanes[0].isRecommended)
        assertTrue(exitManeuver.lanes[1].isRecommended)
        assertTrue(exitManeuver.lanes[2].isRecommended)
        assertEquals("194B", exitManeuver.signpost?.primaryExitNumber)
        assertEquals("I-105 W", exitManeuver.signpost?.primaryRoadNumbers)

        // Early lane voice announcement in English & Persian
        val voiceEn = VoiceGuidanceManager.formatSpokenInstruction(
            maneuver = exitManeuver,
            distanceMeters = 805.0, // ~0.5 mile
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH
        )
        assertTrue("Expected right two lanes & Exit 194B: $voiceEn", voiceEn.contains("right two lanes") && voiceEn.contains("Exit 194B"))

        val voiceFa = VoiceGuidanceManager.formatSpokenInstruction(
            maneuver = exitManeuver,
            distanceMeters = 805.0,
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.PERSIAN
        )
        assertTrue("Expected Persian two right lanes & verbatim Exit 194B: $voiceFa", voiceFa.contains("دو خط سمت راست") && voiceFa.contains("194B"))

        // Maneuver 1 has NO turn_lanes -> MUST be strictly empty (never guessed!)
        val turnManeuver = primary.maneuvers[1]
        assertTrue("Never invent lanes when Valhalla omits turn_lanes", turnManeuver.lanes.isEmpty())

        // Speed limit drop detected between 65 mph (105 kph) and 34 mph (56 kph)
        assertTrue(primary.roadAlerts.any { it.kind == RoadAlertKind.SPEED_LIMIT_DROP })

        // Alternate route toll detected
        assertTrue(routes[1].hasTolls)
        assertTrue(routes[1].roadAlerts.any { it.kind == RoadAlertKind.TOLL_BOOTH })
    }

    @Test
    fun `Phase 4 - All 50 states plus DC exist and Persian digits format properly`() {
        assertEquals(51, UsStatesCatalog.allStates.size)
        assertNotNull(UsStatesCatalog.byCode["or"])
        assertNotNull(UsStatesCatalog.byCode["dc"])

        assertEquals("۴۵", BilingualFormatter.formatNumber(45, AppLanguage.PERSIAN))
        assertEquals("45", BilingualFormatter.formatNumber(45, AppLanguage.ENGLISH))

        // Verify user report proximity alert in NavigationSessionController
        val controller = NavigationSessionController()
        val route = com.example.navigation.OfflineRoute(
            id = "r0",
            summaryLabel = "I-5",
            distanceMeters = 2000.0,
            durationSeconds = 120.0,
            shape = listOf(LatLon(44.0500, -123.0800), LatLon(44.0600, -123.0800)),
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.STRAIGHT,
                    instructionEn = "Continue on I-5",
                    streetNames = listOf("I-5"),
                    lengthMeters = 2000.0,
                    timeSeconds = 120.0,
                    beginShapeIndex = 0,
                    endShapeIndex = 1,
                    lat = 44.0600,
                    lon = -123.0800,
                    speedLimitMph = 55
                )
            )
        )
        val reportAhead = UserReportEntity(
            id = 7L,
            type = ReportType.POLICE.name,
            lat = 44.0530, // ~333m north along heading 0°
            lon = -123.0800,
            roadName = "I-5",
            expiresAt = System.currentTimeMillis() + 600_000L
        )
        val update = controller.processGpsFix(
            currentState = ActiveNavigationState(isNavigating = true, route = route),
            fix = GpsFix(lat = 44.0500, lon = -123.0800, bearing = 0f, speedMps = 30f, accuracyMeters = 5f, timestampMs = System.currentTimeMillis()),
            dbHazards = emptyList(),
            userReports = listOf(reportAhead),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = true
        )
        assertTrue("Expected overspeed warning (30 m/s = 67 mph > 55 + 5)", update.state.isOverspeed)
        assertEquals(RoadAlertKind.USER_POLICE, update.state.activeAheadAlert?.kind)
    }
}
