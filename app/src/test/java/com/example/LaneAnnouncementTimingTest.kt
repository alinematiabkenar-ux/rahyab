package com.example

import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDirection
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.VoiceGuidanceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaneAnnouncementTimingTest {

    private data class RecordedAnnouncement(
        val maneuverIndex: Int,
        val stage: String,
        val distanceMeters: Double,
        val timestampMs: Long,
        val textEn: String,
        val textFa: String
    )

    /**
     * Builds a straight northbound polyline with 25 m spacing so Simulate Drive and exact
     * distance checks match real-world Oregon distances accurately.
     */
    private fun buildNorthboundShape(totalMeters: Int, stepMeters: Int = 25): List<LatLon> {
        val baseLat = 44.0500
        val baseLon = -123.0700
        val metersPerDegLat = 111_132.0
        val count = (totalMeters / stepMeters) + 1
        return List(count) { i ->
            LatLon(baseLat + (i * stepMeters) / metersPerDegLat, baseLon)
        }
    }

    private fun runSimulatedDrive(
        route: OfflineRoute,
        unit: DistanceUnit,
        stepSeconds: Double = 2.0,
        maxTicks: Int = 220
    ): Pair<List<RecordedAnnouncement>, List<Pair<Double, Boolean>>> {
        val controller = NavigationSessionController()
        var state = ActiveNavigationState(
            isNavigating = true,
            route = route,
            currentManeuverIndex = 0,
            snappedLocation = route.shape.first(),
            snappedShapeIndex = 0
        )
        val announcements = mutableListOf<RecordedAnnouncement>()
        val laneStripSamples = mutableListOf<Pair<Double, Boolean>>()
        var simTimeMs = 1_700_000_000_000L

        for (tick in 0 until maxTicks) {
            if (state.hasArrived) break
            simTimeMs += (stepSeconds * 1000.0).toLong()
            val rawFix = controller.computeSimulatedFix(state, elapsedSeconds = stepSeconds) ?: break
            val fix = rawFix.copy(timestampMs = simTimeMs)

            val res = controller.processGpsFix(
                currentState = state,
                fix = fix,
                dbHazards = emptyList(),
                userReports = emptyList(),
                unit = unit,
                language = AppLanguage.ENGLISH,
                speedAudioEnabled = false
            )
            state = res.state
            laneStripSamples.add(state.distanceToNextManeuverMeters to state.showLaneStrip)

            res.maneuverToSpeak?.let { (maneuver, stage) ->
                val textEn = VoiceGuidanceManager.formatSpokenInstruction(
                    maneuver = maneuver,
                    distanceMeters = state.distanceToNextManeuverMeters,
                    unit = unit,
                    language = AppLanguage.ENGLISH,
                    includeLaneGuidance = maneuver.lanes.isNotEmpty(),
                    announcementStage = stage
                )
                val textFa = VoiceGuidanceManager.formatSpokenInstruction(
                    maneuver = maneuver,
                    distanceMeters = state.distanceToNextManeuverMeters,
                    unit = unit,
                    language = AppLanguage.PERSIAN,
                    includeLaneGuidance = maneuver.lanes.isNotEmpty(),
                    announcementStage = stage
                )
                announcements.add(
                    RecordedAnnouncement(
                        maneuverIndex = maneuver.index,
                        stage = stage,
                        distanceMeters = state.distanceToNextManeuverMeters,
                        timestampMs = simTimeMs,
                        textEn = textEn,
                        textFa = textFa
                    )
                )
            }
        }
        return announcements to laneStripSamples
    }

    private fun rightTwoLanesOfThree(): List<LaneInfo> = listOf(
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false),
        LaneInfo(listOf(LaneDirection.THROUGH, LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true),
        LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true)
    )

    private fun leftOneLaneOfThree(): List<LaneInfo> = listOf(
        LaneInfo(listOf(LaneDirection.LEFT), listOf(LaneDirection.LEFT), isRecommended = true),
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false),
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false)
    )

    @Test
    fun cityRouteWithLaneDataSpeaks1MiHalfMi1000FtAndFinalOnceWithExactLaneAdviceAnd1MiLaneStrip() {
        val shape = buildNorthboundShape(totalMeters = 2600, stepMeters = 25)
        val turnIdx = 96 // 2400 m from start
        val route = OfflineRoute(
            id = "city_coburg_lanes",
            summaryLabel = "Franklin Blvd to Coburg Road",
            distanceMeters = 2600.0,
            durationSeconds = 180.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Head north on Franklin Blvd",
                    streetNames = listOf("Franklin Blvd"),
                    lengthMeters = 2400.0,
                    timeSeconds = 160.0,
                    beginShapeIndex = 0,
                    endShapeIndex = turnIdx,
                    lat = shape[0].lat,
                    lon = shape[0].lon,
                    speedLimitMph = 35,
                    laneCount = 3,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Coburg Road",
                    streetNames = listOf("Coburg Road"),
                    lengthMeters = 200.0,
                    timeSeconds = 20.0,
                    beginShapeIndex = turnIdx,
                    endShapeIndex = shape.lastIndex,
                    lat = shape[turnIdx].lat,
                    lon = shape[turnIdx].lon,
                    lanes = rightTwoLanesOfThree(),
                    speedLimitMph = 35,
                    laneCount = 3,
                    isMultiLaneRoad = true
                )
            )
        )

        val (imperialEvents, laneStripSamples) = runSimulatedDrive(route, DistanceUnit.IMPERIAL)
        val turnEvents = imperialEvents.filter { it.maneuverIndex == 1 && it.stage != NavigationSessionController.STAGE_ARRIVED }
        assertEquals(
            listOf(
                NavigationSessionController.STAGE_CITY_1MI,
                NavigationSessionController.STAGE_CITY_HALF_MI,
                NavigationSessionController.STAGE_CITY_1000FT,
                NavigationSessionController.STAGE_CITY_NOW
            ),
            turnEvents.map { it.stage }
        )

        // Verify exact English & Persian phrasing at 1 mile and half a mile
        val at1Mi = turnEvents[0]
        assertTrue("1mi trigger should be near 1 mile: ${at1Mi.distanceMeters}m", at1Mi.distanceMeters in 1450.0..1780.0)
        assertEquals("In 1 mile, use the right two lanes to turn right onto Coburg Road.", at1Mi.textEn)
        assertEquals("در یک مایلی، از دو خط سمت راست به راست بپیچید و وارد Coburg Road شوید.", at1Mi.textFa)

        val atHalfMi = turnEvents[1]
        assertTrue("0.5mi trigger should be near half a mile: ${atHalfMi.distanceMeters}m", atHalfMi.distanceMeters in 720.0..920.0)
        assertEquals("In half a mile, use the right two lanes to turn right onto Coburg Road.", atHalfMi.textEn)
        assertEquals("در نیم مایلی، از دو خط سمت راست به راست بپیچید و وارد Coburg Road شوید.", atHalfMi.textFa)

        // Lane strip visible from ~1 mile on city streets when lane data exists, hidden beyond 1 mile
        assertTrue("Lane strip should be hidden beyond 1 mile", laneStripSamples.any { it.first > 1800.0 && !it.second })
        assertTrue("Lane strip must show at ~1 mile on city streets", laneStripSamples.any { it.first in 1400.0..1750.0 && it.second })

        // Verify Metric units (~1.5 km and ~800 m)
        val (metricEvents, _) = runSimulatedDrive(route, DistanceUnit.METRIC)
        val metricTurn = metricEvents.filter { it.maneuverIndex == 1 && it.stage != NavigationSessionController.STAGE_ARRIVED }
        assertEquals(
            listOf(
                NavigationSessionController.STAGE_CITY_1MI,
                NavigationSessionController.STAGE_CITY_HALF_MI,
                NavigationSessionController.STAGE_CITY_1000FT,
                NavigationSessionController.STAGE_CITY_NOW
            ),
            metricTurn.map { it.stage }
        )
        assertEquals("In 1.5 kilometers, use the right two lanes to turn right onto Coburg Road.", metricTurn[0].textEn)
        assertEquals("در 1.5 کیلومتری، از دو خط سمت راست به راست بپیچید و وارد Coburg Road شوید.", metricTurn[0].textFa)
        assertEquals("In 800 meters, use the right two lanes to turn right onto Coburg Road.", metricTurn[1].textEn)
        assertEquals("در 800 متری، از دو خط سمت راست به راست بپیچید و وارد Coburg Road شوید.", metricTurn[1].textFa)
    }

    @Test
    fun multiLaneCityRoadWithoutLaneDataUsesPrepareToPhrasingAndNeverInventsLanes() {
        val shape = buildNorthboundShape(totalMeters = 2500, stepMeters = 25)
        val turnIdx = 92 // 2300 m from start
        val route = OfflineRoute(
            id = "city_coburg_no_lanes",
            summaryLabel = "Multi-lane street without turn_lanes",
            distanceMeters = 2500.0,
            durationSeconds = 170.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Head north on Harlow Rd",
                    streetNames = listOf("Harlow Rd"),
                    lengthMeters = 2300.0,
                    timeSeconds = 150.0,
                    beginShapeIndex = 0,
                    endShapeIndex = turnIdx,
                    lat = shape[0].lat,
                    lon = shape[0].lon,
                    speedLimitMph = 35,
                    laneCount = 2,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Coburg Road",
                    streetNames = listOf("Coburg Road"),
                    lengthMeters = 200.0,
                    timeSeconds = 20.0,
                    beginShapeIndex = turnIdx,
                    endShapeIndex = shape.lastIndex,
                    lat = shape[turnIdx].lat,
                    lon = shape[turnIdx].lon,
                    lanes = emptyList(), // NO lane data
                    speedLimitMph = 35,
                    laneCount = 2,
                    isMultiLaneRoad = true
                )
            )
        )

        val (events, laneStripSamples) = runSimulatedDrive(route, DistanceUnit.IMPERIAL)
        val turnEvents = events.filter { it.maneuverIndex == 1 && it.stage != NavigationSessionController.STAGE_ARRIVED }
        assertEquals(
            listOf(
                NavigationSessionController.STAGE_CITY_1MI,
                NavigationSessionController.STAGE_CITY_HALF_MI,
                NavigationSessionController.STAGE_CITY_1000FT,
                NavigationSessionController.STAGE_CITY_NOW
            ),
            turnEvents.map { it.stage }
        )

        assertEquals("In 1 mile, prepare to turn right onto Coburg Road.", turnEvents[0].textEn)
        assertEquals("در یک مایلی، برای پیچیدن به راست به سمت Coburg Road آماده شوید.", turnEvents[0].textFa)
        assertEquals("In half a mile, prepare to turn right onto Coburg Road.", turnEvents[1].textEn)
        assertEquals("در نیم مایلی، برای پیچیدن به راست به سمت Coburg Road آماده شوید.", turnEvents[1].textFa)

        // Strictly never invent lanes in voice or UI
        assertFalse("Must never invent 'lane' when no lane data exists", turnEvents[0].textEn.lowercase().contains("lane"))
        assertFalse("Must never invent 'خط' when no lane data exists", turnEvents[0].textFa.contains("خط"))
        assertTrue("Lane strip must NEVER show when lanes list is empty", laneStripSamples.none { it.second })
    }

    @Test
    fun smallResidentialStreetKeepsExisting1000FtAndFinalTimingOnly() {
        val shape = buildNorthboundShape(totalMeters = 2400, stepMeters = 25)
        val turnIdx = 88 // 2200 m from start
        val route = OfflineRoute(
            id = "city_residential",
            summaryLabel = "Small Residential Street",
            distanceMeters = 2400.0,
            durationSeconds = 220.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Head north on Maple St",
                    streetNames = listOf("Maple St"),
                    lengthMeters = 2200.0,
                    timeSeconds = 200.0,
                    beginShapeIndex = 0,
                    endShapeIndex = turnIdx,
                    lat = shape[0].lat,
                    lon = shape[0].lon,
                    speedLimitMph = 25,
                    laneCount = 1,
                    isMultiLaneRoad = false
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.LEFT,
                    instructionEn = "Turn left onto Oak Ave",
                    streetNames = listOf("Oak Ave"),
                    lengthMeters = 200.0,
                    timeSeconds = 20.0,
                    beginShapeIndex = turnIdx,
                    endShapeIndex = shape.lastIndex,
                    lat = shape[turnIdx].lat,
                    lon = shape[turnIdx].lon,
                    lanes = emptyList(),
                    speedLimitMph = 25,
                    laneCount = 1,
                    isMultiLaneRoad = false
                )
            )
        )

        val (events, _) = runSimulatedDrive(route, DistanceUnit.IMPERIAL)
        val turnEvents = events.filter { it.maneuverIndex == 1 && it.stage != NavigationSessionController.STAGE_ARRIVED }
        assertEquals(
            "Small residential streets must NOT trigger 1 mi or 0.5 mi early warnings",
            listOf(
                NavigationSessionController.STAGE_CITY_1000FT,
                NavigationSessionController.STAGE_CITY_NOW
            ),
            turnEvents.map { it.stage }
        )
    }

    @Test
    fun highwayI5RepeatsLaneAdviceAt1MiAndHalfMiInImperialAndMetric() {
        val shape = buildNorthboundShape(totalMeters = 4200, stepMeters = 25)
        val exitIdx = 152 // 3800 m from start
        val route = OfflineRoute(
            id = "i5_exit_194b",
            summaryLabel = "I-5 North to Exit 194B",
            distanceMeters = 4200.0,
            durationSeconds = 160.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Continue on I-5 North",
                    streetNames = listOf("I-5 N"),
                    lengthMeters = 3800.0,
                    timeSeconds = 135.0,
                    beginShapeIndex = 0,
                    endShapeIndex = exitIdx,
                    lat = shape[0].lat,
                    lon = shape[0].lon,
                    isHighwayRoad = true,
                    speedLimitMph = 65,
                    laneCount = 3,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.EXIT_RIGHT,
                    instructionEn = "Take exit 194B toward Portland",
                    streetNames = listOf("I-105 W"),
                    lengthMeters = 400.0,
                    timeSeconds = 25.0,
                    beginShapeIndex = exitIdx,
                    endShapeIndex = shape.lastIndex,
                    lat = shape[exitIdx].lat,
                    lon = shape[exitIdx].lon,
                    signpost = HighwaySignpost(
                        exitNumbers = listOf("194B"),
                        exitBranches = listOf("I-105 W"),
                        exitTowards = listOf("Portland")
                    ),
                    lanes = rightTwoLanesOfThree(),
                    isHighwayExitOrSplit = true,
                    isHighwayRoad = true,
                    speedLimitMph = 65,
                    laneCount = 3,
                    isMultiLaneRoad = true
                )
            )
        )

        val (imperialEvents, laneStripSamples) = runSimulatedDrive(route, DistanceUnit.IMPERIAL)
        val exitEvents = imperialEvents.filter { it.maneuverIndex == 1 && it.stage != NavigationSessionController.STAGE_ARRIVED }
        assertEquals(
            listOf(
                NavigationSessionController.STAGE_HW_2MI,
                NavigationSessionController.STAGE_HW_1MI,
                NavigationSessionController.STAGE_HW_HALF_MI,
                NavigationSessionController.STAGE_HW_QUARTER_MI,
                NavigationSessionController.STAGE_HW_NOW
            ),
            exitEvents.map { it.stage }
        )

        // Lane advice repeated at 1 mi and 0.5 mi in both English and Persian
        assertEquals("In 1 mile, use the right two lanes to take exit 194B toward Portland.", exitEvents[1].textEn)
        assertEquals("در یک مایلی، از دو خط سمت راست وارد خروجی 194B به سمت Portland شوید.", exitEvents[1].textFa)
        assertEquals("In half a mile, use the right two lanes to take exit 194B toward Portland.", exitEvents[2].textEn)
        assertEquals("در نیم مایلی، از دو خط سمت راست وارد خروجی 194B به سمت Portland شوید.", exitEvents[2].textFa)

        assertTrue("Lane strip must appear at ~1 mile on I-5", laneStripSamples.any { it.first in 1400.0..1750.0 && it.second })

        // Metric units on I-5 (~1.5 km and ~800 m)
        val (metricEvents, _) = runSimulatedDrive(route, DistanceUnit.METRIC)
        val metricExit = metricEvents.filter { it.maneuverIndex == 1 && it.stage != NavigationSessionController.STAGE_ARRIVED }
        assertEquals(
            listOf(
                NavigationSessionController.STAGE_HW_2MI,
                NavigationSessionController.STAGE_HW_1MI,
                NavigationSessionController.STAGE_HW_HALF_MI,
                NavigationSessionController.STAGE_HW_QUARTER_MI,
                NavigationSessionController.STAGE_HW_NOW
            ),
            metricExit.map { it.stage }
        )
        assertEquals("In 1.5 kilometers, use the right two lanes to take exit 194B toward Portland.", metricExit[1].textEn)
        assertEquals("در 1.5 کیلومتری، از دو خط سمت راست وارد خروجی 194B به سمت Portland شوید.", metricExit[1].textFa)
        assertEquals("In 800 meters, use the right two lanes to take exit 194B toward Portland.", metricExit[2].textEn)
        assertEquals("در 800 متری، از دو خط سمت راست وارد خروجی 194B به سمت Portland شوید.", metricExit[2].textFa)
    }

    @Test
    fun shortSpacingAfterPreviousManeuverSpeaksRealRemainingDistanceAndNeverOverlapsTwoManeuvers() {
        // Maneuver 1 at 500 m, Maneuver 2 only 0.72 miles (~1150 m) later at 1650 m
        val shape = buildNorthboundShape(totalMeters = 1900, stepMeters = 25)
        val m1Idx = 20  // 500 m
        val m2Idx = 66  // 1650 m (1150 m after M1 = ~0.71 miles)
        val route = OfflineRoute(
            id = "short_spacing_city",
            summaryLabel = "Close Consecutive Turns",
            distanceMeters = 1900.0,
            durationSeconds = 130.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Head north on Willamette St",
                    streetNames = listOf("Willamette St"),
                    lengthMeters = 500.0,
                    timeSeconds = 35.0,
                    beginShapeIndex = 0,
                    endShapeIndex = m1Idx,
                    lat = shape[0].lat,
                    lon = shape[0].lon,
                    speedLimitMph = 35,
                    laneCount = 2,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Franklin Blvd",
                    streetNames = listOf("Franklin Blvd"),
                    lengthMeters = 1150.0,
                    timeSeconds = 75.0,
                    beginShapeIndex = m1Idx,
                    endShapeIndex = m2Idx,
                    lat = shape[m1Idx].lat,
                    lon = shape[m1Idx].lon,
                    speedLimitMph = 35,
                    laneCount = 3,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 2,
                    type = ManeuverType.LEFT,
                    instructionEn = "Turn left onto Coburg Road",
                    streetNames = listOf("Coburg Road"),
                    lengthMeters = 250.0,
                    timeSeconds = 20.0,
                    beginShapeIndex = m2Idx,
                    endShapeIndex = shape.lastIndex,
                    lat = shape[m2Idx].lat,
                    lon = shape[m2Idx].lon,
                    lanes = leftOneLaneOfThree(),
                    speedLimitMph = 35,
                    laneCount = 3,
                    isMultiLaneRoad = true
                )
            )
        )

        val (events, _) = runSimulatedDrive(route, DistanceUnit.IMPERIAL, stepSeconds = 1.0, maxTicks = 200)
        val m1Events = events.filter { it.maneuverIndex == 1 && it.stage != NavigationSessionController.STAGE_ARRIVED }
        val m2Events = events.filter { it.maneuverIndex == 2 && it.stage != NavigationSessionController.STAGE_ARRIVED }

        assertTrue("Maneuver 1 should announce final turn before completion", m1Events.any { it.stage == NavigationSessionController.STAGE_CITY_NOW })
        assertEquals(
            "Maneuver 2 must NOT skip early warning even though spacing is ~0.7 mi",
            listOf(
                NavigationSessionController.STAGE_CITY_1MI,
                NavigationSessionController.STAGE_CITY_HALF_MI,
                NavigationSessionController.STAGE_CITY_1000FT,
                NavigationSessionController.STAGE_CITY_NOW
            ),
            m2Events.map { it.stage }
        )

        // First warning for M2 must speak real remaining distance (~0.7 miles) and NOT overlap M1's final prompt
        val m1Last = m1Events.last()
        val m2First = m2Events.first()
        assertTrue(
            "Two different maneuvers must NEVER be announced at the same moment (gap=${m2First.timestampMs - m1Last.timestampMs}ms)",
            (m2First.timestampMs - m1Last.timestampMs) >= NavigationSessionController.INTER_MANEUVER_COOLDOWN_MS
        )
        assertTrue(
            "Expected real remaining distance (~0.7 miles) in English: ${m2First.textEn}",
            m2First.textEn.startsWith("In 0.7 miles, use the left lane to turn left onto Coburg Road.")
        )
        assertTrue(
            "Expected real remaining distance (~0.7 مایلی) in Persian: ${m2First.textFa}",
            m2First.textFa.startsWith("در 0.7 مایلی، از خط سمت چپ به چپ بپیچید و وارد Coburg Road شوید.")
        )
    }

    @Test
    fun speedUnder2MphSuppressesAllManeuverAnnouncements() {
        val shape = buildNorthboundShape(totalMeters = 2000, stepMeters = 25)
        val turnIdx = 72 // 1800 m
        val route = OfflineRoute(
            id = "slow_speed_guard",
            summaryLabel = "Stopped / Slow Traffic",
            distanceMeters = 2000.0,
            durationSeconds = 200.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.DEPART,
                    instructionEn = "Head north",
                    streetNames = listOf("Coburg Road"),
                    lengthMeters = 1800.0,
                    timeSeconds = 180.0,
                    beginShapeIndex = 0,
                    endShapeIndex = turnIdx,
                    lat = shape[0].lat,
                    lon = shape[0].lon,
                    speedLimitMph = 35,
                    isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1,
                    type = ManeuverType.RIGHT,
                    instructionEn = "Turn right onto Gateway St",
                    streetNames = listOf("Gateway St"),
                    lengthMeters = 200.0,
                    timeSeconds = 20.0,
                    beginShapeIndex = turnIdx,
                    endShapeIndex = shape.lastIndex,
                    lat = shape[turnIdx].lat,
                    lon = shape[turnIdx].lon,
                    lanes = rightTwoLanesOfThree(),
                    speedLimitMph = 35,
                    isMultiLaneRoad = true
                )
            )
        )

        val controller = NavigationSessionController()
        var state = ActiveNavigationState(isNavigating = true, route = route)
        // Test right inside 1 mile (~1600 m), half mile (~800 m), 1000 ft (~300 m), and final (~40 m) at 1.5 mph (0.67 m/s)
        val probeIndices = listOf(8, 40, 60, 70) // distances ~1600m, ~800m, ~300m, ~50m
        for ((i, idx) in probeIndices.withIndex()) {
            val pt = shape[idx]
            val fix = GpsFix(
                lat = pt.lat,
                lon = pt.lon,
                bearing = 0f,
                speedMps = 0.67f, // ~1.5 mph (< 2 mph)
                accuracyMeters = 4f,
                timestampMs = 1_700_000_000_000L + i * 10_000L
            )
            val res = controller.processGpsFix(
                currentState = state,
                fix = fix,
                dbHazards = emptyList(),
                userReports = emptyList(),
                unit = DistanceUnit.IMPERIAL,
                language = AppLanguage.ENGLISH,
                speedAudioEnabled = false
            )
            state = res.state
            assertEquals("Speed under 2 mph must suppress all maneuver prompts", null, res.maneuverToSpeak)
        }
    }
}
