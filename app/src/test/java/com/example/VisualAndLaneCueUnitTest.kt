package com.example

import androidx.datastore.preferences.core.preferencesOf
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.search.RoadHazardPoint
import com.example.i18n.DistanceUnit
import com.example.map.ManeuverArrowGeometry
import com.example.navigation.AheadAlert
import com.example.navigation.LaneChangeCueKind
import com.example.navigation.LaneChangeCuePlanner
import com.example.navigation.LaneChangeCueTracker
import com.example.navigation.LaneChangeTiming
import com.example.navigation.LaneDirection
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LaneTargetSide
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.RouteProgressBarPlanner
import com.example.navigation.RouteProgressEvent
import com.example.navigation.RouteProgressEventKind
import com.example.navigation.SpeedRingState
import com.example.navigation.SpeedometerRingPolicy
import com.example.navigation.TrafficLightVoiceEvaluator
import com.example.navigation.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1: Pure JVM unit tests for S1-S3, L1-L6, and V1-V3:
 * - LaneChangeCuePlanner & LaneChangeCueTracker
 * - Traffic light / stop sign / lane change settings migration
 * - RouteProgressBarPlanner placement, < 4% clustering, and max-20 icon cap
 * - SpeedometerRingPolicy color thresholds (imperial & metric) and HPMS source check
 * - TrafficLightVoiceEvaluator & ManeuverArrowGeometry sanity checks
 */
class VisualAndLaneCueUnitTest {

    // --- 1. LaneChangeCuePlanner & Tracker ---

    @Test
    fun laneChangePlanner_fiveLanesTwoRightTargets_computesLanesAndHighwayCue() {
        val lanes = listOf(
            lane(active = false),
            lane(active = false),
            lane(active = false),
            lane(active = true),
            lane(active = true)
        )
        val maneuver = sampleManeuver(lanes = lanes, isHighwayExitOrSplit = true, isHighwayRoad = true)
        val plan = LaneChangeCuePlanner.plan(maneuver, speedMps = 30f, travelMode = TravelMode.DRIVE)

        assertNotNull(plan)
        assertEquals(3, plan!!.lanesToCross)
        assertEquals(LaneTargetSide.RIGHT, plan.targetSide)
        assertTrue(plan.isHighway)
        // perLane = max(120, 30 * 5 = 150) = 150; raw = 3 * 150 + 300 = 750
        assertEquals(750.0, plan.cueDistanceMeters, 0.1)
        assertEquals(450.0, plan.lastChanceDistanceMeters!!, 0.1)
    }

    @Test
    fun laneChangePlanner_allLanesValidOrEmpty_returnsNull() {
        val allValid = sampleManeuver(lanes = listOf(lane(true), lane(true), lane(true)))
        assertNull(LaneChangeCuePlanner.plan(allValid, 25f, TravelMode.DRIVE))

        val empty = sampleManeuver(lanes = emptyList())
        assertNull(LaneChangeCuePlanner.plan(empty, 25f, TravelMode.DRIVE))

        val single = sampleManeuver(lanes = listOf(lane(true)))
        assertNull(LaneChangeCuePlanner.plan(single, 25f, TravelMode.DRIVE))
    }

    @Test
    fun laneChangePlanner_walkAndBike_alwaysReturnNull() {
        val maneuver = sampleManeuver(lanes = listOf(lane(false), lane(true)), isHighwayRoad = false)
        assertNull(LaneChangeCuePlanner.plan(maneuver, 5f, TravelMode.WALK))
        assertNull(LaneChangeCuePlanner.plan(maneuver, 6f, TravelMode.BIKE))
        assertNotNull(LaneChangeCuePlanner.plan(maneuver, 12f, TravelMode.DRIVE))
    }

    @Test
    fun laneChangePlanner_cityAndHighwayClamping_andTimingMultipliers() {
        // City: 1 lane to cross at 10 m/s -> perLane = max(35, 40) = 40; buffer = 50 -> normal = 90 m
        val cityManeuver = sampleManeuver(
            lanes = listOf(lane(true), lane(false)),
            isHighwayExitOrSplit = false,
            isHighwayRoad = false
        )
        val early = LaneChangeCuePlanner.plan(cityManeuver, 10f, TravelMode.DRIVE, LaneChangeTiming.EARLY)!!
        val normal = LaneChangeCuePlanner.plan(cityManeuver, 10f, TravelMode.DRIVE, LaneChangeTiming.NORMAL)!!
        val late = LaneChangeCuePlanner.plan(cityManeuver, 10f, TravelMode.DRIVE, LaneChangeTiming.LATE)!!

        assertEquals(LaneTargetSide.LEFT, normal.targetSide)
        assertFalse(normal.isHighway)
        assertEquals(90.0, normal.cueDistanceMeters, 0.1)
        assertEquals(117.0, early.cueDistanceMeters, 0.1)
        assertEquals(72.0, late.cueDistanceMeters, 0.1)
        assertTrue(early.cueDistanceMeters > normal.cueDistanceMeters)
        assertTrue(normal.cueDistanceMeters > late.cueDistanceMeters)

        // City upper clamp (400 m) with 6 lanes across
        val hugeCity = sampleManeuver(
            lanes = listOf(lane(false), lane(false), lane(false), lane(false), lane(false), lane(false), lane(true)),
            isHighwayExitOrSplit = false,
            isHighwayRoad = false
        )
        val clampedCity = LaneChangeCuePlanner.plan(hugeCity, 18f, TravelMode.DRIVE, LaneChangeTiming.EARLY)!!
        assertEquals(LaneChangeCuePlanner.CITY_MAX_CUE_METERS, clampedCity.cueDistanceMeters, 0.1)

        // Middle target lane detection
        val middleManeuver = sampleManeuver(lanes = listOf(lane(false), lane(true), lane(false)))
        assertEquals(LaneTargetSide.MIDDLE, LaneChangeCuePlanner.plan(middleManeuver, 15f, TravelMode.DRIVE)!!.targetSide)
    }

    @Test
    fun laneChangeTracker_firesPrimaryAndLastChanceOnceEach() {
        val tracker = LaneChangeCueTracker()
        val m = sampleManeuver(
            index = 3,
            lanes = listOf(lane(false), lane(false), lane(false), lane(true)),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )
        // Cue = 3 * 150 + 300 = 750 m, lastChance = 450 m
        assertNull(tracker.evaluate(m, 900.0, 30f, TravelMode.DRIVE, LaneChangeTiming.NORMAL, beepsEnabled = true, lastChanceEnabled = true))

        val primary = tracker.evaluate(m, 740.0, 30f, TravelMode.DRIVE, LaneChangeTiming.NORMAL, beepsEnabled = true, lastChanceEnabled = true)
        assertNotNull(primary)
        assertEquals(LaneChangeCueKind.PRIMARY, primary!!.kind)
        assertTrue(primary.mergeWithHalfMile) // 750 m is within 150 m of 804.7 m

        // No duplicate primary
        assertNull(tracker.evaluate(m, 650.0, 30f, TravelMode.DRIVE, LaneChangeTiming.NORMAL, beepsEnabled = true, lastChanceEnabled = true))

        // Last chance fires at <= 450 m
        val lastChance = tracker.evaluate(m, 440.0, 30f, TravelMode.DRIVE, LaneChangeTiming.NORMAL, beepsEnabled = true, lastChanceEnabled = true)
        assertNotNull(lastChance)
        assertEquals(LaneChangeCueKind.LAST_CHANCE, lastChance!!.kind)

        // No duplicate last chance
        assertNull(tracker.evaluate(m, 300.0, 30f, TravelMode.DRIVE, LaneChangeTiming.NORMAL, beepsEnabled = true, lastChanceEnabled = true))
    }

    // --- 2. Settings Migration ---

    @Test
    fun settingsMigration_preservesLegacyKeysAndAppliesNewDefaults() {
        val legacyPrefs = preferencesOf(
            UserPreferencesRepository.KEY_ALERT_STOP_SIGN to true,
            UserPreferencesRepository.KEY_SHOW_TRAFFIC_LIGHTS to false
        )
        val migrated = UserPreferencesRepository.mapPreferencesToSettings(legacyPrefs)

        assertTrue(migrated.alertStopSignEnabled)
        assertFalse(migrated.showTrafficLights)
        // New keys get specified defaults
        assertFalse(migrated.alertTrafficLightEnabled)
        assertTrue(migrated.showStopSignsOnMap)
        assertTrue(migrated.laneChangeBeepsEnabled)
        assertEquals(LaneChangeTiming.NORMAL, migrated.laneChangeTiming)
        assertTrue(migrated.lastChanceBeepEnabled)

        // Fresh install defaults
        val fresh = UserPreferencesRepository.mapPreferencesToSettings(preferencesOf())
        assertTrue(fresh.showTrafficLights)
        assertFalse(fresh.alertTrafficLightEnabled)
        assertTrue(fresh.showStopSignsOnMap)
        assertFalse(fresh.alertStopSignEnabled)
    }

    // --- 3. Route Progress Bar Planner ---

    @Test
    fun routeProgressBar_hiddenInWalk_placesEventsAndClustersUnder4Percent() {
        // Straight northbound route of ~10 km (100 points spaced ~111 m apart)
        val shape = (0..100).map { i -> LatLon(44.0 + i * 0.001, -123.08) }
        val alerts = listOf(
            // Two alerts very close together (~2% apart -> should cluster into 1 icon with count = 2)
            AheadAlert("c1", RoadAlertKind.SPEED_CAMERA, shape[20].lat, shape[20].lon, 0.0, "Speed camera", "دوربین سرعت"),
            AheadAlert("s1", RoadAlertKind.SCHOOL_ZONE, shape[22].lat, shape[22].lon, 0.0, "School zone", "منطقه مدرسه"),
            // Distinct alert far away (~60%)
            AheadAlert("r1", RoadAlertKind.RAILROAD_CROSSING, shape[60].lat, shape[60].lon, 0.0, "Railroad", "راه‌آهن")
        )
        val exitManeuver = sampleManeuver(
            index = 2,
            type = ManeuverType.EXIT_RIGHT,
            beginShapeIndex = 80,
            isHighwayExitOrSplit = true
        )
        val route = OfflineRoute(
            id = "r1",
            summaryLabel = "I-5",
            distanceMeters = 11100.0,
            durationSeconds = 400.0,
            shape = shape,
            maneuvers = listOf(exitManeuver),
            roadAlerts = alerts
        )

        // Hidden in WALK
        assertTrue(RouteProgressBarPlanner.buildEvents(route, 0, 11100.0, TravelMode.WALK).isEmpty())

        // Visible in DRIVE & BIKE
        val driveEvents = RouteProgressBarPlanner.buildEvents(route, 0, 11100.0, TravelMode.DRIVE)
        assertEquals(4, driveEvents.size) // 1 clustered + 1 railroad + 1 exit + 1 destination

        // First event is the 2-item cluster at ~0.21, keeping higher-priority SPEED_CAMERA
        assertEquals(RouteProgressEventKind.SPEED_CAMERA, driveEvents[0].kind)
        assertEquals(2, driveEvents[0].clusterCount)
        assertEquals(0.21f, driveEvents[0].fractionAlongRemaining, 0.02f)

        assertEquals(RouteProgressEventKind.RAILROAD_CROSSING, driveEvents[1].kind)
        assertEquals(0.60f, driveEvents[1].fractionAlongRemaining, 0.02f)

        assertEquals(RouteProgressEventKind.HIGHWAY_EXIT, driveEvents[2].kind)
        assertEquals(0.80f, driveEvents[2].fractionAlongRemaining, 0.02f)

        assertEquals(RouteProgressEventKind.DESTINATION, driveEvents.last().kind)
        assertEquals(1.0f, driveEvents.last().fractionAlongRemaining, 0.001f)
    }

    @Test
    fun routeProgressBar_capsAtMax20IconsPreservingDestination() {
        // 30 well-spaced alerts (> 4% apart? With 30 alerts across 0..1, spacing is ~3.3%, so use 25 non-overlapping alerts spaced 0.045 via direct planner/route)
        val shape = (0..1000).map { i -> LatLon(44.0 + i * 0.0005, -123.08) }
        // 24 alerts spaced 41 indices apart (~4.1% of route -> no clustering)
        val alerts = (1..24).map { k ->
            val idx = k * 41
            AheadAlert("a$k", RoadAlertKind.STEEP_GRADE, shape[idx].lat, shape[idx].lon, 0.0, "Grade", "شیب")
        }
        val route = OfflineRoute(
            id = "long",
            summaryLabel = "Long",
            distanceMeters = 55500.0,
            durationSeconds = 2000.0,
            shape = shape,
            maneuvers = emptyList(),
            roadAlerts = alerts
        )

        val events = RouteProgressBarPlanner.buildEvents(route, 0, 55500.0, TravelMode.DRIVE)
        assertEquals(RouteProgressBarPlanner.MAX_ICONS, events.size)
        assertEquals(RouteProgressEventKind.DESTINATION, events.last().kind)
        assertEquals(1.0f, events.last().fractionAlongRemaining, 0.001f)
    }

    @Test
    fun routeProgressBar_clusterEventsDirectly_mergesUnder4Percent() {
        val raw = listOf(
            RouteProgressEvent("1", RouteProgressEventKind.HIGHWAY_EXIT, 0.30f, 3000.0),
            RouteProgressEvent("2", RouteProgressEventKind.SPEED_CAMERA, 0.32f, 3200.0),
            RouteProgressEvent("3", RouteProgressEventKind.RAILROAD_CROSSING, 0.50f, 5000.0)
        )
        val clustered = RouteProgressBarPlanner.clusterEvents(raw)
        assertEquals(2, clustered.size)
        assertEquals(2, clustered[0].clusterCount)
        assertEquals(RouteProgressEventKind.SPEED_CAMERA, clustered[0].kind)
        assertEquals(1, clustered[1].clusterCount)
    }

    // --- 4. Speedometer Ring Color Thresholds ---

    @Test
    fun speedometerRingPolicy_imperialAndMetricThresholdsAndHpmsLabel() {
        // No speed limit -> always NORMAL
        assertEquals(
            SpeedRingState.NORMAL,
            SpeedometerRingPolicy.evaluate(currentSpeedMps = 40f, speedLimitMph = null, overspeedToleranceMph = 5, unit = DistanceUnit.IMPERIAL)
        )

        // Imperial: 65 mph limit, 5 mph tolerance
        // 60 mph -> NORMAL
        assertEquals(
            SpeedRingState.NORMAL,
            SpeedometerRingPolicy.evaluate(mphToMps(60f), speedLimitMph = 65, overspeedToleranceMph = 5, unit = DistanceUnit.IMPERIAL)
        )
        // 62.5 mph (within 3 mph of 65) -> APPROACHING_LIMIT (yellow)
        assertEquals(
            SpeedRingState.APPROACHING_LIMIT,
            SpeedometerRingPolicy.evaluate(mphToMps(62.5f), speedLimitMph = 65, overspeedToleranceMph = 5, unit = DistanceUnit.IMPERIAL)
        )
        // 69 mph (over limit, within +5 tolerance) -> APPROACHING_LIMIT (yellow)
        assertEquals(
            SpeedRingState.APPROACHING_LIMIT,
            SpeedometerRingPolicy.evaluate(mphToMps(69f), speedLimitMph = 65, overspeedToleranceMph = 5, unit = DistanceUnit.IMPERIAL)
        )
        // 71 mph (over 65 + 5) -> OVERSPEED (red)
        assertEquals(
            SpeedRingState.OVERSPEED,
            SpeedometerRingPolicy.evaluate(mphToMps(71f), speedLimitMph = 65, overspeedToleranceMph = 5, unit = DistanceUnit.IMPERIAL)
        )

        // Metric: 50 mph (~80.47 km/h), 5 mph (~8.05 km/h) tolerance -> red above ~88.5 km/h, yellow >= ~75.5 km/h
        assertEquals(
            SpeedRingState.NORMAL,
            SpeedometerRingPolicy.evaluate(kmhToMps(70f), speedLimitMph = 50, overspeedToleranceMph = 5, unit = DistanceUnit.METRIC)
        )
        assertEquals(
            SpeedRingState.APPROACHING_LIMIT,
            SpeedometerRingPolicy.evaluate(kmhToMps(76f), speedLimitMph = 50, overspeedToleranceMph = 5, unit = DistanceUnit.METRIC)
        )
        assertEquals(
            SpeedRingState.OVERSPEED,
            SpeedometerRingPolicy.evaluate(kmhToMps(91f), speedLimitMph = 50, overspeedToleranceMph = 5, unit = DistanceUnit.METRIC)
        )

        // HPMS source detection
        assertTrue(SpeedometerRingPolicy.isEstimatedLimitSource("hpms"))
        assertTrue(SpeedometerRingPolicy.isEstimatedLimitSource("HPMS"))
        assertFalse(SpeedometerRingPolicy.isEstimatedLimitSource("osm"))
        assertFalse(SpeedometerRingPolicy.isEstimatedLimitSource("none"))
    }

    // --- 5. S3 Traffic Light Voice & V1 Maneuver Arrow Sanity Checks ---

    @Test
    fun trafficLightVoiceEvaluator_clusterAndBusyVoiceSuppression() {
        val evaluator = TrafficLightVoiceEvaluator()
        val light1 = RoadHazardPoint(1L, "traffic_signals", 44.0011, -123.08, "Signal", osmId = "node/1")
        val light2 = RoadHazardPoint(2L, "traffic_signals", 44.0015, -123.08, "Signal 2", osmId = "node/2") // ~44 m from light1 (< 120 m)

        // Busy voice -> skipped
        assertNull(evaluator.evaluate(44.0, -123.08, 0f, listOf(light1), isDriveMode = true, enabled = true, isVoiceBusy = true))
        // Walk mode -> skipped
        assertNull(evaluator.evaluate(44.0, -123.08, 0f, listOf(light1), isDriveMode = false, enabled = true, isVoiceBusy = false))

        // First light triggers at ~122 m ahead
        val first = evaluator.evaluate(44.0, -123.08, 0f, listOf(light1, light2), isDriveMode = true, enabled = true, isVoiceBusy = false)
        assertNotNull(first)
        assertEquals(RoadAlertKind.TRAFFIC_SIGNAL, first!!.kind)

        // Second light within 120 m cluster is suppressed
        assertNull(evaluator.evaluate(44.0005, -123.08, 0f, listOf(light1, light2), isDriveMode = true, enabled = true, isVoiceBusy = false))
    }

    @Test
    fun maneuverArrowGeometry_respectsModeAndDistanceThresholds() {
        val shape = listOf(
            LatLon(44.0000, -123.0800),
            LatLon(44.0005, -123.0800),
            LatLon(44.0010, -123.0800),
            LatLon(44.0010, -123.0790)
        )
        val turnRight = sampleManeuver(index = 0, type = ManeuverType.RIGHT, beginShapeIndex = 2, isHighwayRoad = false)
        val route = OfflineRoute("r", "Test", 200.0, 20.0, shape, listOf(turnRight))

        assertNotNull(ManeuverArrowGeometry.build(route, 0, snappedShapeIndex = 0, distanceToNextManeuverMeters = 120.0, travelMode = TravelMode.DRIVE))
        assertNotNull(ManeuverArrowGeometry.build(route, 0, snappedShapeIndex = 0, distanceToNextManeuverMeters = 120.0, travelMode = TravelMode.BIKE))
        assertNull(ManeuverArrowGeometry.build(route, 0, snappedShapeIndex = 0, distanceToNextManeuverMeters = 120.0, travelMode = TravelMode.WALK))
        assertNull(ManeuverArrowGeometry.build(route, 0, snappedShapeIndex = 0, distanceToNextManeuverMeters = 650.0, travelMode = TravelMode.DRIVE))
        assertNull(ManeuverArrowGeometry.build(route, 0, snappedShapeIndex = 2, distanceToNextManeuverMeters = 0.0, travelMode = TravelMode.DRIVE))
    }

    private fun lane(active: Boolean): LaneInfo = LaneInfo(
        directions = listOf(LaneDirection.THROUGH),
        activeDirections = if (active) listOf(LaneDirection.THROUGH) else emptyList(),
        isRecommended = active,
        isActive = active,
        isValid = active,
        level = if (active) LaneHighlightTier.ACTIVE else LaneHighlightTier.INACTIVE
    )

    private fun sampleManeuver(
        index: Int = 1,
        type: ManeuverType = ManeuverType.EXIT_RIGHT,
        beginShapeIndex: Int = 10,
        lanes: List<LaneInfo> = emptyList(),
        isHighwayExitOrSplit: Boolean = false,
        isHighwayRoad: Boolean = false
    ): RouteManeuver = RouteManeuver(
        index = index,
        type = type,
        instructionEn = "Take exit",
        streetNames = listOf("I-5"),
        lengthMeters = 1000.0,
        timeSeconds = 40.0,
        beginShapeIndex = beginShapeIndex,
        endShapeIndex = beginShapeIndex + 5,
        lat = 44.05,
        lon = -123.08,
        lanes = lanes,
        isHighwayExitOrSplit = isHighwayExitOrSplit,
        isHighwayRoad = isHighwayRoad
    )

    private fun mphToMps(mph: Float): Float = mph / 2.23694f
    private fun kmhToMps(kmh: Float): Float = kmh / 3.6f
}
