package com.example

import com.example.data.local.ReportType
import com.example.data.local.UserReportEntity
import com.example.data.preferences.UserSettings
import com.example.data.search.RoadHazardPoint
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.CyclingSpeedPreset
import com.example.navigation.GpsFix
import com.example.navigation.LatLon
import com.example.navigation.NavigationAlertEvaluator
import com.example.navigation.NavigationSimulationEngine
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteOptions
import com.example.navigation.TravelMode
import com.example.navigation.TravelModeEtaAdjuster
import com.example.navigation.ValhallaRequestBuilder
import com.example.navigation.WalkingSpeedPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8 unit tests covering:
 * - Valhalla request JSON for DRIVE, WALK, and BIKE (costing, speeds, use_roads, use_hills, alternates, heading)
 * - trace_attributes requesting grades only (never speed limits) for WALK and BIKE
 * - "Too far" distance limits (> 50 km WALK, > 150 km BIKE, unlimited DRIVE)
 * - TravelModeEtaAdjuster clamping strictly within 0.6x..1.5x and never adjusting while stopped < 3 min
 * - Alerts disabled in WALK (all) and BIKE (all except railroad crossing & steep grade)
 * - "Walk to car" (RouteOptions(isPedestrian = true)) still producing a pedestrian request
 * - Simulation speeds for WALK (5 km/h) and BIKE (18 km/h)
 */
class TravelModeUnitTest {

    private val origin = LatLon(44.0521, -123.0868)
    private val dest = LatLon(44.0600, -123.0700)

    @Test
    fun driveRequestJson_includesAutoCostingHeadingAndAlternates() {
        val json = ValhallaRequestBuilder.buildRouteRequestJson(
            origin = origin,
            destination = dest,
            options = RouteOptions(
                avoidTolls = true,
                avoidHighways = false,
                avoidFerries = true,
                travelMode = TravelMode.DRIVE
            ),
            headingDegrees = 90f,
            alternatesCount = 2
        )
        assertTrue(json.contains("\"costing\":\"auto\""))
        assertTrue(json.contains("\"use_tolls\":0.0"))
        assertTrue(json.contains("\"use_highways\":1.0"))
        assertTrue(json.contains("\"use_ferry\":0.0"))
        assertTrue(json.contains("\"heading\":90"))
        assertTrue(json.contains("\"alternates\":2"))

        val traceJson = ValhallaRequestBuilder.buildTraceAttributesRequestJson("abc123", TravelMode.DRIVE)
        assertTrue(traceJson.contains("\"edge.speed_limit\""))
        assertTrue(traceJson.contains("\"edge.max_downward_grade\""))
    }

    @Test
    fun walkRequestJson_usesPedestrianSpeedNoHeadingZeroAlternatesAndNoSpeedLimitTrace() {
        val json = ValhallaRequestBuilder.buildRouteRequestJson(
            origin = origin,
            destination = dest,
            options = RouteOptions(
                travelMode = TravelMode.WALK,
                walkingSpeedKmh = WalkingSpeedPreset.FAST.speedKmh
            ),
            headingDegrees = 180f,
            alternatesCount = 2
        )
        assertTrue(json.contains("\"costing\":\"pedestrian\""))
        assertTrue(json.contains("\"walking_speed\":6.0"))
        assertFalse("WALK request must omit heading", json.contains("\"heading\""))
        assertTrue(json.contains("\"alternates\":0"))

        val traceJson = ValhallaRequestBuilder.buildTraceAttributesRequestJson("abc123", TravelMode.WALK)
        assertTrue(traceJson.contains("\"costing\":\"pedestrian\""))
        assertFalse("WALK trace_attributes must never request speed_limit", traceJson.contains("edge.speed_limit"))
        assertTrue(traceJson.contains("\"edge.max_upward_grade\""))
        assertTrue(traceJson.contains("\"edge.max_downward_grade\""))
    }

    @Test
    fun bikeRequestJson_usesHybridSpeedUseRoadsUseHillsAndOneAlternate() {
        val avoidHillsJson = ValhallaRequestBuilder.buildRouteRequestJson(
            origin = origin,
            destination = dest,
            options = RouteOptions(
                travelMode = TravelMode.BIKE,
                cyclingSpeedKmh = CyclingSpeedPreset.RELAXED.speedKmh,
                avoidHillsWhenCycling = true
            ),
            headingDegrees = 45f,
            alternatesCount = 2
        )
        assertTrue(avoidHillsJson.contains("\"costing\":\"bicycle\""))
        assertTrue(avoidHillsJson.contains("\"bicycle_type\":\"Hybrid\""))
        assertTrue(avoidHillsJson.contains("\"cycling_speed\":14.0"))
        assertTrue(avoidHillsJson.contains("\"use_roads\":0.25"))
        assertTrue(avoidHillsJson.contains("\"use_hills\":0.1"))
        assertTrue(avoidHillsJson.contains("\"alternates\":1"))

        val normalHillsJson = ValhallaRequestBuilder.buildRouteRequestJson(
            origin = origin,
            destination = dest,
            options = RouteOptions(
                travelMode = TravelMode.BIKE,
                cyclingSpeedKmh = CyclingSpeedPreset.FAST.speedKmh,
                avoidHillsWhenCycling = false
            ),
            headingDegrees = null,
            alternatesCount = 2
        )
        assertTrue(normalHillsJson.contains("\"cycling_speed\":23.0"))
        assertTrue(normalHillsJson.contains("\"use_hills\":0.4"))

        val traceJson = ValhallaRequestBuilder.buildTraceAttributesRequestJson("abc123", TravelMode.BIKE)
        assertTrue(traceJson.contains("\"costing\":\"bicycle\""))
        assertFalse("BIKE trace_attributes must never request speed_limit", traceJson.contains("edge.speed_limit"))
        assertTrue(traceJson.contains("\"edge.weighted_grade\""))
    }

    @Test
    fun walkToCarConstructor_stillProducesPedestrianRequest() {
        val walkToCarOptions = RouteOptions(isPedestrian = true)
        assertEquals(TravelMode.WALK, walkToCarOptions.travelMode)
        assertTrue(walkToCarOptions.isPedestrian)

        val json = ValhallaRequestBuilder.buildRouteRequestJson(
            origin = origin,
            destination = dest,
            options = walkToCarOptions,
            headingDegrees = 120f
        )
        assertTrue(json.contains("\"costing\":\"pedestrian\""))
        assertFalse(json.contains("\"heading\""))
        assertTrue(json.contains("\"alternates\":0"))
    }

    @Test
    fun tooFarDistanceLimits_matchWalk50KmAndBike150Km() {
        assertFalse(TravelMode.isTooFar(TravelMode.WALK, 50_000.0))
        assertTrue(TravelMode.isTooFar(TravelMode.WALK, 50_001.0))

        assertFalse(TravelMode.isTooFar(TravelMode.BIKE, 150_000.0))
        assertTrue(TravelMode.isTooFar(TravelMode.BIKE, 150_001.0))

        assertFalse(TravelMode.isTooFar(TravelMode.DRIVE, 500_000.0))
    }

    @Test
    fun etaAdjuster_clampsTo06xAnd15xAndIgnoresShortStopsUnder3Minutes() {
        val adjuster = TravelModeEtaAdjuster()
        // Valhalla assumes 5.0 m/s (18 km/h) for a 5,000 m bike route taking 1,000 s
        val routeDist = 5_000.0
        val routeDur = 1_000.0
        val baselineRem = 600.0

        // Very fast rider (15 m/s = 3x faster than Valhalla -> raw 0.333x -> clamped to 0.6x)
        val fastEta = adjuster.adjustRemainingTimeSeconds(
            mode = TravelMode.BIKE,
            baselineRemainingTimeSeconds = baselineRem,
            routeDistanceMeters = routeDist,
            routeDurationSeconds = routeDur,
            currentSpeedMps = 15.0f,
            timestampMs = 10_000L
        )
        assertEquals(baselineRem * 0.6, fastEta, 0.5)

        // Stopped for 2 minutes (< 3 min): must NEVER adjust away from 0.6x
        val stopped1Min = adjuster.adjustRemainingTimeSeconds(
            mode = TravelMode.BIKE,
            baselineRemainingTimeSeconds = baselineRem,
            routeDistanceMeters = routeDist,
            routeDurationSeconds = routeDur,
            currentSpeedMps = 0.0f,
            timestampMs = 70_000L
        )
        assertEquals(baselineRem * 0.6, stopped1Min, 0.5)

        val stopped2Min = adjuster.adjustRemainingTimeSeconds(
            mode = TravelMode.BIKE,
            baselineRemainingTimeSeconds = baselineRem,
            routeDistanceMeters = routeDist,
            routeDurationSeconds = routeDur,
            currentSpeedMps = 0.1f,
            timestampMs = 180_000L // 110s after stop started at 70_000L (< 180s)
        )
        assertEquals(baselineRem * 0.6, stopped2Min, 0.5)

        // Very slow walker (0.5 m/s vs Valhalla 1.5 m/s -> raw 3.0x -> clamped to 1.5x)
        val walkAdjuster = TravelModeEtaAdjuster()
        val slowWalkEta = walkAdjuster.adjustRemainingTimeSeconds(
            mode = TravelMode.WALK,
            baselineRemainingTimeSeconds = 1_000.0,
            routeDistanceMeters = 1_500.0,
            routeDurationSeconds = 1_000.0,
            currentSpeedMps = 0.5f,
            timestampMs = 1_000L
        )
        assertEquals(1_500.0, slowWalkEta, 0.5)

        // DRIVE mode is never altered by TravelModeEtaAdjuster
        val driveEta = walkAdjuster.adjustRemainingTimeSeconds(
            mode = TravelMode.DRIVE,
            baselineRemainingTimeSeconds = 800.0,
            routeDistanceMeters = 10_000.0,
            routeDurationSeconds = 800.0,
            currentSpeedMps = 30.0f,
            timestampMs = 2_000L
        )
        assertEquals(800.0, driveEta, 0.01)
    }

    @Test
    fun alertsPerMode_disabledInWalkAndRestrictedToRailAndSteepGradeInBike() {
        val settings = UserSettings(
            alertSpeedCameraEnabled = true,
            alertRedLightCameraEnabled = true,
            alertRailroadEnabled = true,
            alertSteepGradeEnabled = true,
            alertSchoolZoneEnabled = true,
            alertSpeedDropEnabled = true,
            alertSharpCurveEnabled = true,
            alertTollBoothEnabled = true,
            alertStopSignEnabled = true
        )
        val fix = GpsFix(
            lat = 44.0500,
            lon = -123.0800,
            bearing = 0f,
            hasBearing = true,
            speedMps = 5f,
            accuracyMeters = 5f,
            timestampMs = System.currentTimeMillis()
        )
        val speedCamera = RoadHazardPoint(
            id = 1L,
            type = "speed_camera",
            lat = 44.0512,
            lon = -123.0800,
            label = "Camera",
            speedLimitMph = 35
        )
        val railroad = RoadHazardPoint(
            id = 2L,
            type = "railroad",
            lat = 44.0518,
            lon = -123.0800,
            label = "RR Crossing"
        )
        val steepGrade = AheadAlert(
            id = "steep_1",
            kind = RoadAlertKind.STEEP_GRADE,
            lat = 44.0550,
            lon = -123.0800,
            distanceMeters = 550.0,
            titleEn = "Steep downgrade ahead, 7% for 1 mile",
            titleFa = "جلوتر سراشیبی تند است"
        )
        val policeReport = UserReportEntity(
            id = 9L,
            type = ReportType.POLICE.name,
            lat = 44.0510,
            lon = -123.0800,
            bearing = 0f,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 3_600_000L
        )

        // WALK: zero alerts of any kind
        val walkAlert = NavigationAlertEvaluator.findNearestAheadAlert(
            fix = fix,
            routeAlerts = listOf(steepGrade),
            dbHazards = listOf(speedCamera, railroad),
            userReports = listOf(policeReport),
            settings = settings,
            travelMode = TravelMode.WALK
        )
        assertNull("WALK mode must suppress all alerts", walkAlert)

        // BIKE: camera and police report ignored; railroad crossing allowed
        val bikeRailAlert = NavigationAlertEvaluator.findNearestAheadAlert(
            fix = fix,
            routeAlerts = emptyList(),
            dbHazards = listOf(speedCamera, railroad),
            userReports = listOf(policeReport),
            settings = settings,
            travelMode = TravelMode.BIKE
        )
        assertNotNull(bikeRailAlert)
        assertEquals(RoadAlertKind.RAILROAD_CROSSING, bikeRailAlert!!.kind)

        // BIKE: steep grade allowed when only camera + steep grade present
        val bikeGradeAlert = NavigationAlertEvaluator.findNearestAheadAlert(
            fix = fix,
            routeAlerts = listOf(steepGrade),
            dbHazards = listOf(speedCamera),
            userReports = listOf(policeReport),
            settings = settings,
            travelMode = TravelMode.BIKE
        )
        assertNotNull(bikeGradeAlert)
        assertEquals(RoadAlertKind.STEEP_GRADE, bikeGradeAlert!!.kind)

        // DRIVE: speed camera prioritized
        val driveAlert = NavigationAlertEvaluator.findNearestAheadAlert(
            fix = fix,
            routeAlerts = listOf(steepGrade),
            dbHazards = listOf(speedCamera, railroad),
            userReports = listOf(policeReport),
            settings = settings,
            travelMode = TravelMode.DRIVE
        )
        assertNotNull(driveAlert)
        assertEquals(RoadAlertKind.SPEED_CAMERA, driveAlert!!.kind)
    }

    @Test
    fun simulationSpeeds_matchWalk5KmhAndBike18Kmh() {
        val dummyRoute = OfflineRoute(
            id = "r1",
            summaryLabel = "Test",
            distanceMeters = 1000.0,
            durationSeconds = 500.0,
            shape = listOf(origin, dest),
            maneuvers = emptyList(),
            travelMode = TravelMode.WALK
        )
        val walkSpeed = NavigationSimulationEngine.computeRealistic1xSpeedMps(
            ActiveNavigationState(isNavigating = true, travelMode = TravelMode.WALK, isWalkingMode = true, route = dummyRoute)
        )
        assertEquals(5.0f / 3.6f, walkSpeed, 0.01f)

        val bikeSpeed = NavigationSimulationEngine.computeRealistic1xSpeedMps(
            ActiveNavigationState(isNavigating = true, travelMode = TravelMode.BIKE, route = dummyRoute.copy(travelMode = TravelMode.BIKE))
        )
        assertEquals(18.0f / 3.6f, bikeSpeed, 0.01f)
    }
}
