package com.example

import com.example.data.packs.ExtrasMatchingHelper
import com.example.data.packs.FraCrossingRecord
import com.example.data.packs.HpmsSpeedRecord
import com.example.data.preferences.UserSettings
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.EdgeGradeSpan
import com.example.navigation.GpsFix
import com.example.navigation.HpmsSpeedFallbackCache
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationAlertEvaluator
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.SteepGradeEvaluator
import com.example.navigation.TravelMode
import com.example.navigation.ValhallaPolylineAndGeo
import com.example.navigation.ValhallaTraceAttributesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X8: Pure JVM unit tests for X5 (steep grade warnings), X6 (FRA railroad crossing enrichment
 * & FRA-only crossings), and X7 (HPMS speed limit fallback & heading filter), including missing
 * `extras.db` and old packs without elevation grades.
 */
class ExtrasAndGradesUnitTest {

    @Test
    fun fraEnrichment_formatsGatesLightsOnlyAndNoneInEnglishAndPersian() {
        val baseAlert = AheadAlert(
            id = "db_hazard_1",
            kind = RoadAlertKind.RAILROAD_CROSSING,
            lat = 44.0521,
            lon = -123.0868,
            distanceMeters = 200.0,
            titleEn = "Railroad crossing ahead",
            titleFa = "جلوتر تقاطع راه‌آهن است"
        )

        val gates = FraCrossingRecord("G1", 44.05215, -123.0868, street = "Main St", railroad = "UP", hasGates = true, hasLights = true)
        val enrichedGates = ExtrasMatchingHelper.enrichRailroadAlertWithFra(baseAlert, listOf(gates))
        assertTrue(enrichedGates.titleEn.contains("with gates"))
        assertTrue(enrichedGates.titleFa.contains("با راهبند"))

        val lightsOnly = FraCrossingRecord("L1", 44.05215, -123.0868, hasGates = false, hasLights = true)
        val enrichedLights = ExtrasMatchingHelper.enrichRailroadAlertWithFra(baseAlert, listOf(lightsOnly))
        assertTrue(enrichedLights.titleEn.contains("lights only"))
        assertTrue(enrichedLights.titleFa.contains("فقط چراغ"))

        val none = FraCrossingRecord("N1", 44.05215, -123.0868, hasGates = false, hasLights = false)
        val enrichedNone = ExtrasMatchingHelper.enrichRailroadAlertWithFra(baseAlert, listOf(none))
        assertTrue(enrichedNone.titleEn.contains("no gates or lights, look both ways"))
        assertTrue(enrichedNone.titleFa.contains("بدون راهبند و چراغ، به دو طرف نگاه کنید"))
    }

    @Test
    fun fraAlertEvaluator_enrichesOsmRailroadCrossingAhead() {
        val crossing = FraCrossingRecord("G2", 44.0540, -123.0868, street = "Oak St", railroad = "BNSF", hasGates = true, hasLights = true)
        val osmHazard = RoadHazardPoint(
            id = 42L,
            type = "railroad",
            lat = 44.05402,
            lon = -123.0868,
            label = "Oak St Crossing"
        )
        val fix = GpsFix(lat = 44.0520, lon = -123.0868, bearing = 0f, hasBearing = true, speedMps = 15f, accuracyMeters = 5f, timestampMs = 1_000L)
        val alert = NavigationAlertEvaluator.findNearestAheadAlert(
            fix = fix,
            routeAlerts = emptyList(),
            dbHazards = listOf(osmHazard),
            userReports = emptyList(),
            settings = UserSettings(alertRailroadEnabled = true),
            travelMode = TravelMode.DRIVE,
            fraCrossings = listOf(crossing)
        )
        assertNotNull(alert)
        assertEquals(RoadAlertKind.RAILROAD_CROSSING, alert!!.kind)
        assertTrue(alert.titleEn.contains("with gates"))
        assertTrue(alert.titleFa.contains("با راهبند"))
    }

    @Test
    fun fraOnlyAlerts_addedOnRouteAndSkippedWithin40mOfOsmCrossing() {
        val routeShape = listOf(
            LatLon(44.0500, -123.0868),
            LatLon(44.0600, -123.0868)
        )
        val osmCrossing = RoadHazardPoint(id = 10L, type = "railroad", lat = 44.0520, lon = -123.0868, label = "")
        val nearOsmFra = FraCrossingRecord("NEAR", 44.05215, -123.0868, hasGates = true) // ~16.7 m from OSM -> skipped
        val fraOnlyCrossing = FraCrossingRecord("ONLY", 44.0560, -123.0868, street = "5th Ave", hasGates = false, hasLights = false) // far from OSM -> kept

        val alerts = ExtrasMatchingHelper.buildFraOnlyRouteAlerts(
            routeShape = routeShape,
            existingHazards = listOf(osmCrossing),
            fraCrossings = listOf(nearOsmFra, fraOnlyCrossing)
        )
        assertEquals(1, alerts.size)
        assertEquals("fra_crossing_ONLY", alerts[0].id)
        assertEquals(RoadAlertKind.RAILROAD_CROSSING, alerts[0].kind)
        assertTrue(alerts[0].titleEn.contains("no gates or lights"))
        assertTrue(alerts[0].titleFa.contains("بدون راهبند و چراغ"))
    }

    @Test
    fun hpmsMatching_acceptsAlignedHeadingWithin20mAndIgnoresHeadingOver30Deg() {
        val pts = listOf(
            LatLon(44.0500, -123.0868),
            LatLon(44.0550, -123.0868) // Due North (bearing 0 / 180)
        )
        val encoded = ValhallaPolylineAndGeo.encodePolyline6(pts)
        val record = HpmsSpeedRecord(
            id = 1L,
            speedMph = 55,
            routeName = "OR-99",
            minLat = 44.0500,
            minLon = -123.0868,
            maxLat = 44.0550,
            maxLon = -123.0868,
            polyline = encoded
        )

        // Within 20 m, heading 10° (diff 10° <= 30°) -> matches
        assertEquals(55, ExtrasMatchingHelper.matchHpmsSpeedLimitMph(44.0525, -123.08675, 10f, listOf(record)))
        // Reverse direction heading 170° (diff 10° from 180°) -> matches
        assertEquals(55, ExtrasMatchingHelper.matchHpmsSpeedLimitMph(44.0525, -123.08675, 170f, listOf(record)))
        // Heading 45° (diff 45° > 30°) -> ignored
        assertNull(ExtrasMatchingHelper.matchHpmsSpeedLimitMph(44.0525, -123.08675, 45f, listOf(record)))
        // Perpendicular heading 90° -> ignored
        assertNull(ExtrasMatchingHelper.matchHpmsSpeedLimitMph(44.0525, -123.08675, 90f, listOf(record)))
        // Too far laterally (> 20 m east: ~80 m) -> ignored
        assertNull(ExtrasMatchingHelper.matchHpmsSpeedLimitMph(44.0525, -123.0858, 0f, listOf(record)))
    }

    @Test
    fun hpmsFallback_usedOnlyWhenOsmIsNullAndNeverOverridesOsm() {
        assertEquals(65 to HpmsSpeedFallbackCache.SOURCE_OSM, HpmsSpeedFallbackCache.resolveSpeedLimitWithSource(true, 65, 45))
        assertEquals(45 to HpmsSpeedFallbackCache.SOURCE_HPMS, HpmsSpeedFallbackCache.resolveSpeedLimitWithSource(true, null, 45))
        assertEquals(null to HpmsSpeedFallbackCache.SOURCE_NONE, HpmsSpeedFallbackCache.resolveSpeedLimitWithSource(true, null, null))
        assertEquals(null to HpmsSpeedFallbackCache.SOURCE_NONE, HpmsSpeedFallbackCache.resolveSpeedLimitWithSource(false, 65, 45))

        // Verify full NavigationSessionController preserves OSM speed limit and falls back to HPMS only when OSM is null
        val shape = listOf(LatLon(44.0500, -123.0868), LatLon(44.0550, -123.0868))
        val maneuvers = listOf(
            RouteManeuver(0, ManeuverType.DEPART, "Head north", listOf("I-5"), 550.0, 20.0, 0, 1, 44.0500, -123.0868),
            RouteManeuver(1, ManeuverType.ARRIVE, "Arrive", emptyList(), 0.0, 0.0, 1, 1, 44.0550, -123.0868)
        )
        val fix = GpsFix(44.0510, -123.0868, bearing = 0f, hasBearing = true, speedMps = 20f, accuracyMeters = 4f, timestampMs = 10_000L)
        val controller = NavigationSessionController()

        val routeWithOsm = OfflineRoute("r_osm", "Fastest", 550.0, 20.0, shape, maneuvers, speedLimitsByShapeIndex = mapOf(0 to 65, 1 to 65))
        val resOsm = controller.processGpsFix(
            currentState = ActiveNavigationState(isNavigating = true, route = routeWithOsm),
            fix = fix,
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = true,
            hpmsFallbackSpeedMph = 50
        )
        assertEquals(65, resOsm.state.currentSpeedLimitMph)
        assertEquals("osm", resOsm.state.speedLimitSource)

        val routeWithoutOsm = OfflineRoute("r_hpms", "Fastest", 550.0, 20.0, shape, maneuvers, speedLimitsByShapeIndex = emptyMap())
        val resHpms = controller.processGpsFix(
            currentState = ActiveNavigationState(isNavigating = true, route = routeWithoutOsm),
            fix = fix,
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = true,
            hpmsFallbackSpeedMph = 50
        )
        assertEquals(50, resHpms.state.currentSpeedLimitMph)
        assertEquals("hpms", resHpms.state.speedLimitSource)

        val resNone = controller.processGpsFix(
            currentState = ActiveNavigationState(isNavigating = true, route = routeWithoutOsm),
            fix = fix,
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = true,
            hpmsFallbackSpeedMph = null
        )
        assertNull(resNone.state.currentSpeedLimitMph)
        assertEquals("none", resNone.state.speedLimitSource)
    }

    @Test
    fun missingExtrasDb_gracefullyReturnsEmptyAndNullWithoutCrashing() {
        val alert = AheadAlert("a1", RoadAlertKind.RAILROAD_CROSSING, 44.05, -123.08, 100.0, "Railroad crossing ahead", "جلوتر تقاطع راه‌آهن است")
        assertEquals(alert, ExtrasMatchingHelper.enrichRailroadAlertWithFra(alert, emptyList()))
        assertTrue(ExtrasMatchingHelper.buildFraOnlyRouteAlerts(listOf(LatLon(44.05, -123.08), LatLon(44.06, -123.08)), emptyList(), emptyList()).isEmpty())
        assertNull(ExtrasMatchingHelper.matchHpmsSpeedLimitMph(44.05, -123.08, 0f, emptyList()))
        assertNull(ExtrasMatchingHelper.findNearestFraCrossing(44.05, -123.08, emptyList()))
    }

    @Test
    fun steepGrades_detectsDowngradeAndUpgradeAndNoOpsWhenMissingOnOldPacks() {
        // 1.2 km straight north (~111 m per 0.001 deg lat -> 0.011 deg = ~1224 m >= 0.5 mi)
        val shape = listOf(
            LatLon(44.0500, -123.0868),
            LatLon(44.0555, -123.0868),
            LatLon(44.0610, -123.0868)
        )
        val downAlerts = SteepGradeEvaluator.detectSteepGradeAlerts(
            shape,
            listOf(EdgeGradeSpan(0, 2, maxDownwardGrade = -7.0, weightedGrade = -6.5))
        )
        assertEquals(1, downAlerts.size)
        assertEquals(RoadAlertKind.STEEP_GRADE, downAlerts[0].kind)
        assertTrue(downAlerts[0].titleEn.contains("Steep downgrade ahead"))
        assertTrue(downAlerts[0].titleFa.contains("سراشیبی تند"))

        val upAlerts = SteepGradeEvaluator.detectSteepGradeAlerts(
            shape,
            listOf(EdgeGradeSpan(0, 2, maxUpwardGrade = 8.0, weightedGrade = 7.5))
        )
        assertEquals(1, upAlerts.size)
        assertTrue(upAlerts[0].titleEn.contains("Steep upgrade ahead"))
        assertTrue(upAlerts[0].titleFa.contains("سربالایی تند"))

        // Gentle grade (< 6% down / < 7% up) -> ignored
        assertTrue(SteepGradeEvaluator.detectSteepGradeAlerts(shape, listOf(EdgeGradeSpan(0, 2, weightedGrade = -4.0))).isEmpty())

        // Missing grade values (old packs without elevation tiles) -> does nothing
        assertTrue(SteepGradeEvaluator.detectSteepGradeAlerts(shape, emptyList()).isEmpty())
        assertTrue(SteepGradeEvaluator.detectSteepGradeAlerts(shape, listOf(EdgeGradeSpan(0, 2, null, null, null))).isEmpty())

        // Valhalla kNoElevationData sentinel (32768) and short runs (< 0.5 mi) produce zero alerts
        assertTrue(SteepGradeEvaluator.detectSteepGradeAlerts(shape, listOf(EdgeGradeSpan(0, 2, 32768.0, -32768.0, 32768.0))).isEmpty())
        val shortShape = listOf(LatLon(44.0500, -123.0868), LatLon(44.0530, -123.0868)) // ~334 m < 0.5 mi
        assertTrue(SteepGradeEvaluator.detectSteepGradeAlerts(shortShape, listOf(EdgeGradeSpan(0, 1, weightedGrade = -8.0))).isEmpty())

        // Old-pack trace_attributes JSON without grade keys never crashes and produces zero grade alerts
        val oldPackTraceJson = """{"edges":[{"begin_shape_index":0,"end_shape_index":2,"speed_limit":88,"lane_count":2}]}"""
        val baseRoute = OfflineRoute("r_old", "Fastest", 1224.0, 60.0, shape, emptyList())
        val enriched = ValhallaTraceAttributesParser.enrichRouteWithTraceAttributes(baseRoute, oldPackTraceJson)
        assertFalse(enriched.roadAlerts.any { it.kind == RoadAlertKind.STEEP_GRADE })
    }
}
