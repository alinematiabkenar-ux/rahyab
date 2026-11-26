package com.example

import androidx.sqlite.driver.AndroidSQLiteDriver
import com.example.data.packs.ExtrasDbReader
import com.example.data.packs.ExtrasV2MatchingHelper
import com.example.data.packs.TigerRangeRecord
import com.example.data.search.AddressNormalizer
import com.example.data.search.GnisPlaceSearchHelper
import com.example.data.search.OfflineAddressSearchEngine
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.TigerAddressInterpolator
import com.example.i18n.AppLanguage
import com.example.map.LaneOverlayBuilder
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDirection
import com.example.navigation.LaneGuidanceValidator
import com.example.navigation.LaneInfo
import com.example.navigation.LaneSource
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.TravelMode
import com.example.navigation.WrongLaneSuppressionRecord
import com.example.ui.components.SimulationSpeedStepper
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccuracyAndDataUnitTest {

    private fun lane(
        vararg dirs: LaneDirection,
        active: Boolean = false,
        valid: Boolean = active
    ): LaneInfo = LaneInfo(
        directions = dirs.toList(),
        activeDirections = if (active || valid) dirs.toList() else emptyList(),
        isRecommended = active || valid,
        isActive = active,
        isValid = valid
    )

    // -------------------------------------------------------------------------
    // S1: Simulator speed step buttons (1, 2, 4, 8, 16, 32, limits & sync)
    // -------------------------------------------------------------------------

    @Test
    fun simulatorSpeedStepperEnforcesBoundsAndSteps() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32), SimulationSpeedStepper.STEPS)
        assertFalse(SimulationSpeedStepper.canStepDown(1))
        assertTrue(SimulationSpeedStepper.canStepUp(1))
        assertEquals(1, SimulationSpeedStepper.stepDown(1))
        assertEquals(2, SimulationSpeedStepper.stepUp(1))
        assertEquals(4, SimulationSpeedStepper.stepUp(2))
        assertEquals(8, SimulationSpeedStepper.stepUp(4))
        assertEquals(16, SimulationSpeedStepper.stepUp(8))
        assertEquals(32, SimulationSpeedStepper.stepUp(16))
        assertEquals(32, SimulationSpeedStepper.stepUp(32))
        assertFalse(SimulationSpeedStepper.canStepUp(32))
        assertTrue(SimulationSpeedStepper.canStepDown(32))
        assertEquals(16, SimulationSpeedStepper.stepDown(32))
        assertEquals(4, SimulationSpeedStepper.clampToValidStep(5))
        assertEquals("×4", SimulationSpeedStepper.formatSpeedBadge(4, AppLanguage.ENGLISH))
        assertEquals("×۴", SimulationSpeedStepper.formatSpeedBadge(4, AppLanguage.PERSIAN))
    }

    // -------------------------------------------------------------------------
    // L2: LaneGuidanceValidator rules
    // -------------------------------------------------------------------------

    @Test
    fun laneValidatorRightTurnWithLeftTargetGivesLow() {
        val lanes = listOf(
            lane(LaneDirection.RIGHT, active = true),
            lane(LaneDirection.THROUGH),
            lane(LaneDirection.THROUGH)
        )
        val res = LaneGuidanceValidator.validate(
            maneuverType = ManeuverType.RIGHT,
            lanes = lanes,
            laneSource = LaneSource.DIRECT
        )
        assertEquals(LaneConfidence.LOW, res.confidence)
        assertTrue(res.reasons.any { it.contains("side", ignoreCase = true) || it.contains("right", ignoreCase = true) })
    }

    @Test
    fun laneValidatorIncompatibleArrowGivesLow() {
        val lanes = listOf(
            lane(LaneDirection.LEFT),
            lane(LaneDirection.THROUGH),
            lane(LaneDirection.LEFT, active = true)
        )
        val res = LaneGuidanceValidator.validate(
            maneuverType = ManeuverType.RIGHT,
            lanes = lanes,
            laneSource = LaneSource.DIRECT
        )
        assertEquals(LaneConfidence.LOW, res.confidence)
        assertTrue(res.reasons.any { it.contains("arrow", ignoreCase = true) })
    }

    @Test
    fun laneValidatorCarriedAlignedOver300mGivesLow() {
        val lanes = listOf(
            lane(LaneDirection.THROUGH),
            lane(LaneDirection.RIGHT, active = true)
        )
        val res = LaneGuidanceValidator.validate(
            maneuverType = ManeuverType.RIGHT,
            lanes = lanes,
            laneSource = LaneSource.CARRIED_ALIGNED,
            carriedDistanceMeters = 360.0
        )
        assertEquals(LaneConfidence.LOW, res.confidence)
        assertTrue(res.reasons.any { it.contains("carried", ignoreCase = true) })
    }

    @Test
    fun laneValidatorDirectWithMatchingArrowsGivesHigh() {
        val lanes = listOf(
            lane(LaneDirection.LEFT),
            lane(LaneDirection.THROUGH),
            lane(LaneDirection.RIGHT, active = true)
        )
        val res = LaneGuidanceValidator.validate(
            maneuverType = ManeuverType.RIGHT,
            lanes = lanes,
            laneSource = LaneSource.DIRECT,
            edgeLaneCountBeforeManeuver = 3
        )
        assertEquals(LaneConfidence.HIGH, res.confidence)
        assertEquals(listOf(2), res.targetIndices)
        assertTrue(res.hasAdvice)
    }

    @Test
    fun laneValidatorHpmsThroughLaneMismatchLowersOneLevel() {
        val lanes = listOf(
            lane(LaneDirection.THROUGH),
            lane(LaneDirection.THROUGH),
            lane(LaneDirection.THROUGH),
            lane(LaneDirection.SLIGHT_RIGHT, active = true)
        )
        val res = LaneGuidanceValidator.validate(
            maneuverType = ManeuverType.RAMP_RIGHT,
            lanes = lanes,
            laneSource = LaneSource.DIRECT,
            edgeLaneCountBeforeManeuver = 4,
            isHighway = true,
            hpmsThroughLanes = 1
        )
        assertEquals(LaneConfidence.MEDIUM, res.confidence)
        assertTrue(res.reasons.any { it.contains("hpms", ignoreCase = true) })
    }

    // -------------------------------------------------------------------------
    // L3: LaneOverlayBuilder draws only the final approach matching edge lane count
    // -------------------------------------------------------------------------

    @Test
    fun laneOverlayBuilderClampsApproachToMatchingEdgeLaneCount() {
        val shape = (0..10).map { i -> LatLon(44.0000 + i * 0.0009, -123.0900) }
        val edgeCounts = (0..6).associateWith { 2 } + (7..9).associateWith { 4 }
        val startManeuver = RouteManeuver(
            index = 0,
            type = ManeuverType.DEPART,
            instructionEn = "Drive north",
            streetNames = listOf("Oak St"),
            lengthMeters = 1000.0,
            timeSeconds = 60.0,
            beginShapeIndex = 0,
            endShapeIndex = 10,
            lat = shape.first().lat,
            lon = shape.first().lon
        )
        val turnManeuver = RouteManeuver(
            index = 1,
            type = ManeuverType.RIGHT,
            instructionEn = "Turn right",
            streetNames = listOf("Oak St"),
            lengthMeters = 200.0,
            timeSeconds = 20.0,
            beginShapeIndex = 10,
            endShapeIndex = 10,
            lat = shape.last().lat,
            lon = shape.last().lon,
            lanes = listOf(
                lane(LaneDirection.LEFT),
                lane(LaneDirection.THROUGH),
                lane(LaneDirection.THROUGH),
                lane(LaneDirection.RIGHT, active = true)
            ),
            laneSource = LaneSource.DIRECT
        )
        val route = OfflineRoute(
            id = "r1",
            summaryLabel = "Test",
            distanceMeters = 1000.0,
            durationSeconds = 60.0,
            shape = shape,
            maneuvers = listOf(startManeuver, turnManeuver),
            edgeLaneCountsByShapeIndex = edgeCounts
        )

        val overlay = LaneOverlayBuilder.build(
            route = route,
            currentManeuverIndex = 1,
            snappedShapeIndex = 1,
            distanceToNextManeuverMeters = 900.0,
            travelMode = TravelMode.DRIVE,
            isOffRoute = false,
            startDistanceMeters = 900.0,
            isFocusMode = true
        )
        assertNotNull(overlay)
        val targetStrip = overlay!!.lanes.first { it.isTarget }
        val drawnLengthMeters = OfflinePlaceSearchEngine.haversineMeters(
            targetStrip.points.first().lat,
            -123.0900,
            targetStrip.points.last().lat,
            -123.0900
        )
        assertTrue("Expected drawn length near 300 m, got $drawnLengthMeters", drawnLengthMeters in 240.0..360.0)
    }

    // -------------------------------------------------------------------------
    // L5: Wrong-lane report suppression within 30 m & 30 deg, cleared on map version change
    // -------------------------------------------------------------------------

    @Test
    fun wrongLaneSuppressionMatchesWithin30mAnd30DegAndClearsOnMapVersionChange() {
        val reports = listOf(
            WrongLaneSuppressionRecord(
                lat = 44.0521,
                lon = -123.0868,
                bearingDegrees = 10f,
                mapVersion = "2026.11.24"
            )
        )
        assertTrue(
            LaneGuidanceValidator.matchesWrongLaneSuppression(
                lat = 44.0522,
                lon = -123.0868,
                bearingDegrees = 355f,
                currentMapVersion = "2026.11.24",
                reports = reports
            )
        )
        assertFalse(
            LaneGuidanceValidator.matchesWrongLaneSuppression(
                lat = 44.0521,
                lon = -123.0868,
                bearingDegrees = 60f,
                currentMapVersion = "2026.11.24",
                reports = reports
            )
        )
        assertFalse(
            LaneGuidanceValidator.matchesWrongLaneSuppression(
                lat = 44.0531,
                lon = -123.0868,
                bearingDegrees = 10f,
                currentMapVersion = "2026.11.24",
                reports = reports
            )
        )
        assertFalse(
            LaneGuidanceValidator.matchesWrongLaneSuppression(
                lat = 44.0521,
                lon = -123.0868,
                bearingDegrees = 10f,
                currentMapVersion = "2026.12.01",
                reports = reports
            )
        )
    }

    // -------------------------------------------------------------------------
    // D4: TIGER interpolation (odd/even sides, out of range)
    // -------------------------------------------------------------------------

    @Test
    fun tigerInterpolationHonorsOddEvenParityAndOutOfRange() {
        val poly = encodePolyline6(listOf(LatLon(44.0500, -123.0900), LatLon(44.0510, -123.0900)))
        val record = TigerRangeRecord(
            id = 1L,
            stateCode = "OR",
            street = "Willamette St",
            streetNorm = "willamette st",
            city = "Eugene",
            zip = "97401",
            fromLeft = 100,
            toLeft = 198,
            fromRight = 101,
            toRight = 199,
            parityLeft = "E",
            parityRight = "O",
            minLat = 44.0500,
            minLon = -123.0900,
            maxLat = 44.0510,
            maxLon = -123.0900,
            polyline = poly
        )

        val evenHit = TigerAddressInterpolator.interpolateOnRange(150, record)
        assertNotNull(evenHit)
        assertEquals("L", evenHit!!.side)
        assertTrue(evenHit.lat in 44.05045..44.05056)

        val oddHit = TigerAddressInterpolator.interpolateOnRange(151, record)
        assertNotNull(oddHit)
        assertEquals("R", oddHit!!.side)
        assertTrue(oddHit.lat in 44.05045..44.05056)

        assertNull(TigerAddressInterpolator.interpolateOnRange(250, record))
        assertNull(TigerAddressInterpolator.interpolateOnRange(50, record))

        val oddOnly = record.copy(fromLeft = null, toLeft = null, parityLeft = "")
        assertNull(TigerAddressInterpolator.interpolateOnRange(150, oddOnly))
        assertNotNull(TigerAddressInterpolator.interpolateOnRange(151, oddOnly))
    }

    // -------------------------------------------------------------------------
    // D4 & D5 & ExtrasDbReader: schema_version 1 & 2, missing tables, TIGER fallback, GNIS prefix search
    // -------------------------------------------------------------------------

    @Test
    fun extrasReaderAcceptsSchemaV1AndV2AndMissingTablesGracefully() {
        val dir = Files.createTempDirectory("extras_v1_v2_test").toFile()
        try {
            val driver = AndroidSQLiteDriver()
            val reader = ExtrasDbReader(sqliteDriver = driver)

            val v1File = File(dir, "or_v1_extras.db")
            driver.open(v1File.absolutePath).use { conn ->
                exec(conn, "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                exec(conn, "INSERT INTO meta VALUES ('schema_version', '1')")
                exec(conn, "CREATE TABLE fra_crossings (crossing_id TEXT PRIMARY KEY, lat REAL, lon REAL, street TEXT, railroad TEXT, has_gates INT, has_lights INT, tracks INT, daily_trains INT)")
                exec(conn, "INSERT INTO fra_crossings VALUES ('X1', 44.05, -123.09, 'Oak', 'UP', 1, 1, 2, 10)")
            }
            assertTrue(reader.isSchemaSupported(v1File))
            assertEquals(1, reader.queryFraCrossingsInFiles(listOf("or" to v1File), 44.0, 44.1, -123.2, -123.0).size)
            assertTrue(reader.queryTigerRangesInFiles(listOf("or" to v1File), "willamette st", 44.05, -123.09).isEmpty())
            assertTrue(reader.queryGnisNamesInFiles(listOf("or" to v1File), "spencer*", 44.05, -123.09).isEmpty())
            assertTrue(reader.queryHpmsLanesInFiles(listOf("or" to v1File), 44.05, -123.09).isEmpty())

            val v2File = File(dir, "or_v2_extras.db")
            val segPoly = encodePolyline6(listOf(LatLon(44.0500, -123.0900), LatLon(44.0520, -123.0900)))
            driver.open(v2File.absolutePath).use { conn ->
                exec(conn, "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                exec(conn, "INSERT INTO meta VALUES ('schema_version', '2')")
                exec(
                    conn,
                    """
                    CREATE TABLE tiger_ranges (
                        id INTEGER PRIMARY KEY, street TEXT, street_norm TEXT, city TEXT, zip TEXT,
                        from_left INT, to_left INT, from_right INT, to_right INT,
                        parity_left TEXT, parity_right TEXT,
                        min_lat REAL, min_lon REAL, max_lat REAL, max_lon REAL, polyline TEXT
                    )
                    """.trimIndent()
                )
                exec(
                    conn,
                    "INSERT INTO tiger_ranges VALUES (1, 'Oak St', 'oak st', 'Eugene', '97401', 100, 198, 101, 199, 'E', 'O', 44.0500, -123.0900, 44.0520, -123.0900, '$segPoly')"
                )
                exec(
                    conn,
                    "CREATE TABLE gnis_names (id INTEGER PRIMARY KEY, name TEXT, feature_class TEXT, county TEXT, lat REAL, lon REAL)"
                )
                exec(conn, "CREATE VIRTUAL TABLE gnis_fts USING fts4(name)")
                exec(conn, "INSERT INTO gnis_names VALUES (1, 'Spencer Butte', 'Summit', 'Lane', 43.9832, -123.0978)")
                exec(conn, "INSERT INTO gnis_names VALUES (2, 'Spencer Creek', 'Stream', 'Lane', 44.0200, -123.1100)")
                exec(conn, "INSERT INTO gnis_fts (rowid, name) SELECT id, name FROM gnis_names")
                exec(
                    conn,
                    "CREATE TABLE hpms_lanes (id INTEGER PRIMARY KEY, through_lanes INT, min_lat REAL, min_lon REAL, max_lat REAL, max_lon REAL, polyline TEXT)"
                )
                exec(conn, "INSERT INTO hpms_lanes VALUES (1, 3, 44.0500, -123.0900, 44.0520, -123.0900, '$segPoly')")
            }
            assertTrue(reader.isSchemaSupported(v2File))

            val lanesCandidates = reader.queryHpmsLanesInFiles(listOf("or" to v2File), 44.0510, -123.0900)
            assertEquals(1, lanesCandidates.size)
            assertEquals(3, ExtrasV2MatchingHelper.matchHpmsThroughLanes(44.0510, -123.0900, 0f, lanesCandidates))

            val gnisHits = GnisPlaceSearchHelper.searchInFiles(
                extrasDbFiles = listOf("or" to v2File),
                queries = listOf("spen"),
                userLat = 44.0500,
                userLon = -123.0900,
                sqliteDriver = driver
            )
            assertEquals(2, gnisHits.size)
            assertEquals("Spencer Creek", gnisHits[0].name)
            assertEquals("Spencer Butte", gnisHits[1].name)
            assertEquals(GnisPlaceSearchHelper.SOURCE_GNIS_LANDMARK, gnisHits[0].source)

            val placesFile = File(dir, "or_places.db")
            driver.open(placesFile.absolutePath).use { conn ->
                exec(conn, "CREATE TABLE addresses (id INTEGER PRIMARY KEY, number TEXT, street TEXT, unit TEXT, city TEXT, state TEXT, postcode TEXT, lat REAL, lon REAL, source TEXT)")
                exec(conn, "CREATE VIRTUAL TABLE addresses_fts USING fts4(number, street, city, postcode)")
                exec(conn, "INSERT INTO addresses VALUES (10, '120', 'Oak St', 'Apt 2', 'Eugene', 'OR', '97401', 44.0504, -123.0900, 'overture')")
                exec(conn, "INSERT INTO addresses_fts (rowid, number, street, city, postcode) VALUES (10, '120', 'oak st', 'Eugene', '97401')")
            }

            val addrEngine = OfflineAddressSearchEngine(driver)
            val exactRes = addrEngine.searchAcrossDatabases(
                parsed = AddressNormalizer.parseAddressQuery("120 Oak St, Eugene"),
                userLat = 44.0500,
                userLon = -123.0900,
                stateDbFiles = listOf("or" to placesFile),
                extrasDbFiles = listOf("or" to v2File)
            )
            assertTrue(exactRes.isNotEmpty())
            assertEquals(OfflineAddressSearchEngine.SOURCE_EXACT_ADDRESS, exactRes.first().source)
            assertTrue(exactRes.first().name.contains("Apt 2"))

            val tigerRes = addrEngine.searchAcrossDatabases(
                parsed = AddressNormalizer.parseAddressQuery("151 Oak St, Eugene 97401"),
                userLat = 44.0500,
                userLon = -123.0900,
                stateDbFiles = listOf("or" to placesFile),
                extrasDbFiles = listOf("or" to v2File)
            )
            assertTrue(tigerRes.isNotEmpty())
            assertEquals(OfflineAddressSearchEngine.SOURCE_APPROX_ADDRESS, tigerRes.first().source)
            assertTrue(tigerRes.first().id.startsWith("tiger_approx_"))
            assertTrue(tigerRes.first().lat in 44.0509..44.0512)

            val v99File = File(dir, "or_v99_extras.db")
            driver.open(v99File.absolutePath).use { conn ->
                exec(conn, "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                exec(conn, "INSERT INTO meta VALUES ('schema_version', '99')")
            }
            assertFalse(reader.isSchemaSupported(v99File))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun exec(conn: androidx.sqlite.SQLiteConnection, sql: String) {
        val stmt = conn.prepare(sql)
        try {
            stmt.step()
        } finally {
            stmt.close()
        }
    }

    private fun encodePolyline6(points: List<LatLon>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLon = 0
        for (p in points) {
            val lat = kotlin.math.round(p.lat * 1_000_000.0).toInt()
            val lon = kotlin.math.round(p.lon * 1_000_000.0).toInt()
            encodeSigned(lat - prevLat, sb)
            encodeSigned(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeSigned(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1F)) + 63).toChar())
            v = v ushr 5
        }
        sb.append((v + 63).toChar())
    }
}
