package com.example

import com.example.data.packs.InstalledComponentHashes
import com.example.data.packs.MapUpdateSmartPlanner
import com.example.data.packs.MapUpdateVersionHelper
import com.example.data.packs.PackFileEntry
import com.example.data.packs.StateManifestEntry
import com.example.i18n.AppLanguage
import com.example.navigation.CameraFollowController
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.ParkedCarManager
import com.example.navigation.RouteOptions
import com.example.navigation.ValhallaResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for F3 (Monthly Map Updates + Smart SHA-256 diff)
 * and F4 (Parked Car auto-save rules, Home 150 m radius, Walk to Car pedestrian routing).
 */
class FeaturesV21UnitTest {

    @Test
    fun versionComparisonHandlesDatedSemverAndLocalPacks() {
        assertTrue(MapUpdateVersionHelper.isNewerVersion("2026.04.15", "2026.05.01"))
        assertTrue(MapUpdateVersionHelper.isNewerVersion("2026-04", "2026-05"))
        assertTrue(MapUpdateVersionHelper.isNewerVersion("v1.9.0", "v1.10.0"))
        assertFalse(MapUpdateVersionHelper.isNewerVersion("2026.05.01", "2026.05.01"))
        assertFalse(MapUpdateVersionHelper.isNewerVersion("2026.05.01", "2026.04.15"))
        assertFalse(MapUpdateVersionHelper.isNewerVersion("local", "2026.05.01"))
        assertFalse(MapUpdateVersionHelper.isNewerVersion("", "2026.05.01"))
        assertFalse(MapUpdateVersionHelper.isNewerVersion("2026.04.15", ""))

        val enBody = MapUpdateVersionHelper.formatUpdateNotificationBody(listOf("Oregon"), AppLanguage.ENGLISH)
        val faBody = MapUpdateVersionHelper.formatUpdateNotificationBody(listOf("اورگان"), AppLanguage.PERSIAN)
        assertEquals("New map for Oregon is available", enBody)
        assertEquals("نقشهٔ تازهٔ اورگان آماده است", faBody)
    }

    @Test
    fun smartUpdatePlannerDownloadsOnlyChangedComponentsWithExactSize() {
        val remote = StateManifestEntry(
            stateCode = "or",
            name = "Oregon",
            version = "2026.05.01",
            buildVersion = "2026.05.01",
            totalSizeBytes = 1000L + 2000L + 500L,
            pmtiles = PackFileEntry("or.pmtiles", "", 1000L, "sha_pm_new"),
            valhalla = PackFileEntry("or_valhalla.tar", "", 2000L, "sha_vh_same"),
            places = PackFileEntry("or_places.db", "", 500L, "sha_db_new")
        )

        val unchanged = MapUpdateSmartPlanner.planSmartUpdate(
            localHashes = InstalledComponentHashes("sha_pm_new", "sha_vh_same", "sha_db_new"),
            remote = remote
        )
        assertFalse(unchanged.hasAnyDownload)
        assertEquals(0L, unchanged.downloadBytes)
        assertEquals(3500L, unchanged.skippedBytes)

        val onlyPlaces = MapUpdateSmartPlanner.planSmartUpdate(
            localHashes = InstalledComponentHashes("sha_pm_new", "sha_vh_same", "sha_db_old"),
            remote = remote
        )
        assertFalse(onlyPlaces.downloadPmtiles)
        assertFalse(onlyPlaces.downloadValhalla)
        assertTrue(onlyPlaces.downloadPlaces)
        assertEquals(500L, onlyPlaces.downloadBytes)
        assertEquals(listOf("places"), onlyPlaces.changedComponents)

        val legacy = MapUpdateSmartPlanner.planSmartUpdate(
            localHashes = InstalledComponentHashes("", "", ""),
            remote = remote
        )
        assertTrue(legacy.downloadPmtiles && legacy.downloadValhalla && legacy.downloadPlaces)
        assertEquals(3500L, legacy.downloadBytes)
    }

    @Test
    fun parkedCarHomeRadiusRuleRespectsToggleAnd150mThreshold() {
        val homeLat = 44.0521
        val homeLon = -123.0868

        assertTrue(
            ParkedCarManager.shouldSkipBecauseNearHome(
                lat = 44.05264,
                lon = homeLon,
                homeLat = homeLat,
                homeLon = homeLon,
                skipAtHomeEnabled = true
            )
        )
        assertFalse(
            ParkedCarManager.shouldSkipBecauseNearHome(
                lat = 44.05264,
                lon = homeLon,
                homeLat = homeLat,
                homeLon = homeLon,
                skipAtHomeEnabled = false
            )
        )
        assertFalse(
            ParkedCarManager.shouldSkipBecauseNearHome(
                lat = 44.0553,
                lon = homeLon,
                homeLat = homeLat,
                homeLon = homeLon,
                skipAtHomeEnabled = true
            )
        )
    }

    @Test
    fun parkedCarNavArrivalAndWalkingArrivalRules() {
        val mgr = ParkedCarManager()
        val homeLat = 44.0521
        val homeLon = -123.0868
        val farLat = 44.0800
        val farLon = -123.0900

        val walkArrival = mgr.onNavigationArrived(
            currentFix = GpsFix(farLat, farLon, bearing = 0f, speedMps = 0.2f, accuracyMeters = 8f, timestampMs = 1_000L),
            homeLat = homeLat,
            homeLon = homeLon,
            skipAtHomeEnabled = true,
            isWalkingMode = true
        )
        assertNull(walkArrival)

        val fastArrival = mgr.onNavigationArrived(
            currentFix = GpsFix(farLat, farLon, bearing = 0f, speedMps = 5.0f, accuracyMeters = 8f, timestampMs = 2_000L),
            homeLat = homeLat,
            homeLon = homeLon,
            skipAtHomeEnabled = true,
            isWalkingMode = false
        )
        assertNull(fastArrival)

        val poorFix = mgr.onGpsFix(
            fix = GpsFix(farLat, farLon, bearing = 0f, speedMps = 0.5f, accuracyMeters = 60f, timestampMs = 3_000L),
            isNavigating = false,
            isWalkingMode = false,
            homeLat = homeLat,
            homeLon = homeLon,
            skipAtHomeEnabled = true
        )
        assertNull(poorFix)

        val goodFix = mgr.onGpsFix(
            fix = GpsFix(farLat, farLon, bearing = 0f, speedMps = 0.4f, accuracyMeters = 9f, timestampMs = 4_000L),
            isNavigating = false,
            isWalkingMode = false,
            homeLat = homeLat,
            homeLon = homeLon,
            skipAtHomeEnabled = true
        )
        assertNotNull(goodFix)
        assertEquals(ParkedCarManager.SOURCE_NAV_ARRIVAL, goodFix!!.source)
    }

    @Test
    fun parkedCarFreeDriveStopAndBluetoothDisconnectRules() {
        val mgr = ParkedCarManager()
        val lat = 44.0900
        val lon = -123.1000
        var t = 1_000_000L

        assertNull(mgr.onGpsFix(GpsFix(lat, lon, bearing = 0f, speedMps = 8.5f, accuracyMeters = 10f, timestampMs = t), false, false, null, null, true))
        t += 125_000L
        assertNull(mgr.onGpsFix(GpsFix(lat, lon, bearing = 0f, speedMps = 8.5f, accuracyMeters = 10f, timestampMs = t), false, false, null, null, true))

        t += 10_000L
        assertNull(mgr.onGpsFix(GpsFix(lat, lon, bearing = 0f, speedMps = 0.3f, accuracyMeters = 10f, timestampMs = t), false, false, null, null, true))
        t += 60_000L
        assertNull(mgr.onGpsFix(GpsFix(lat, lon, bearing = 0f, speedMps = 0.2f, accuracyMeters = 10f, timestampMs = t), false, false, null, null, true))

        t += 65_000L
        val saved = mgr.onGpsFix(GpsFix(lat, lon, bearing = 0f, speedMps = 0.1f, accuracyMeters = 12f, timestampMs = t), false, false, null, null, true)
        assertNotNull(saved)
        assertEquals(ParkedCarManager.SOURCE_FREE_DRIVE, saved!!.source)

        val btSpot = mgr.onCarBluetoothDisconnected(
            nowMs = t + 30_000L,
            homeLat = null,
            homeLon = null,
            skipAtHomeEnabled = true
        )
        assertNotNull(btSpot)
        assertEquals(ParkedCarManager.SOURCE_BLUETOOTH, btSpot!!.source)

        val manual = mgr.buildManualParkedSpot(
            currentFix = GpsFix(lat, lon, bearing = 0f, speedMps = 0f, accuracyMeters = 7f, timestampMs = t),
            fallbackLocation = LatLon(lat, lon),
            nowMs = t + 5000L
        )
        assertEquals(ParkedCarManager.SOURCE_MANUAL, manual.source)
    }

    @Test
    fun walkToCarUsesPedestrianOptionsAndZoom17Tilt0And15mArrival() {
        val opts = RouteOptions(isPedestrian = true)
        assertTrue(opts.isPedestrian)

        val cam = CameraFollowController().computeFollowCameraUpdate(
            lat = 44.0521,
            lon = -123.0868,
            speedMps = 1.4f,
            headingDegrees = 45f,
            isHeadingUpMode = true,
            is3DView = true,
            isNavigating = true,
            forceImmediate = true,
            isWalkingMode = true
        )
        assertNotNull(cam)
        assertEquals(17.0, cam!!.zoom, 0.01)
        assertEquals(0.0, cam.tilt, 0.01)

        val start = LatLon(44.0521, -123.0868)
        val dest = LatLon(44.0522, -123.0868) // ~11.1 m north
        val route = com.example.navigation.OfflineRoute(
            id = "walk_1",
            summaryLabel = "Walk",
            distanceMeters = 12.0,
            durationSeconds = 10.0,
            shape = listOf(start, dest),
            maneuvers = listOf(
                com.example.navigation.RouteManeuver(
                    index = 0,
                    type = com.example.navigation.ManeuverType.ARRIVE,
                    instructionEn = "Arrive at car",
                    streetNames = emptyList(),
                    lengthMeters = 12.0,
                    timeSeconds = 10.0,
                    beginShapeIndex = 0,
                    endShapeIndex = 1,
                    lat = dest.lat,
                    lon = dest.lon,
                    speedLimitMph = 25
                )
            ),
            speedLimitsByShapeIndex = mapOf(0 to 25, 1 to 25)
        )
        val controller = com.example.navigation.NavigationSessionController()
        val initial = com.example.navigation.ActiveNavigationState(
            isNavigating = true,
            isWalkingMode = true,
            route = route,
            destinationName = "Parked car"
        )
        val res = controller.processGpsFix(
            currentState = initial,
            fix = GpsFix(start.lat, start.lon, bearing = 0f, speedMps = 1.3f, accuracyMeters = 5f, timestampMs = 1_000L),
            dbHazards = emptyList(),
            userReports = emptyList(),
            unit = com.example.i18n.DistanceUnit.METRIC,
            language = AppLanguage.ENGLISH,
            speedAudioEnabled = true
        )
        assertTrue(res.state.hasArrived)
        assertNull(res.state.currentSpeedLimitMph)
        assertFalse(res.state.showLaneStrip)
        assertFalse(res.triggerOverspeedChime)
    }
}
