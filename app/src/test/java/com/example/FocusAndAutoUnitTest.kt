package com.example

import com.example.car.CarCameraProfile
import com.example.car.CarManeuverMapper
import com.example.car.CarMapView
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.map.LaneHighlightColor
import com.example.map.LaneOverlayBuilder
import com.example.map.MapStyleBuilder
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDirection
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusAudioPlayer
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusPlanner
import com.example.navigation.TurnFocusSessionTracker
import com.example.ui.enrichHighwayExitLanesForSimulation
import com.example.ui.screens.formatFocusDistanceChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1: Pure JVM unit tests for Android Auto camera/style/fallback-step fixes (A1, A2, B2)
 * and Turn Focus Mode (F1–F9): TurnFocusPlanner, 3-beep timing, LaneOverlayBuilder custom
 * start distance & focus arrows, RED -> BLUE one-time migration, and simulation enrichment.
 */
class FocusAndAutoUnitTest {

    private fun straightNorthShape(count: Int, stepMeters: Double = 10.0): List<LatLon> {
        val dLat = stepMeters / 111_320.0
        return List(count) { i -> LatLon(44.0521 + i * dLat, -123.0868) }
    }

    private fun makeManeuver(
        index: Int,
        type: ManeuverType,
        instructionEn: String,
        streetNames: List<String>,
        lengthMeters: Double,
        timeSeconds: Double,
        beginShapeIndex: Int,
        endShapeIndex: Int,
        isHighwayRoad: Boolean = false,
        isHighwayExitOrSplit: Boolean = false,
        lanes: List<LaneInfo> = emptyList(),
        signpost: HighwaySignpost? = null
    ): RouteManeuver = RouteManeuver(
        index = index,
        type = type,
        instructionEn = instructionEn,
        streetNames = streetNames,
        lengthMeters = lengthMeters,
        timeSeconds = timeSeconds,
        beginShapeIndex = beginShapeIndex,
        endShapeIndex = endShapeIndex,
        lat = 44.0521 + beginShapeIndex * (10.0 / 111_320.0),
        lon = -123.0868,
        isHighwayRoad = isHighwayRoad,
        isHighwayExitOrSplit = isHighwayExitOrSplit,
        lanes = lanes,
        signpost = signpost
    )

    private fun makeRoute(
        shapeCount: Int,
        maneuvers: List<RouteManeuver>,
        stepMeters: Double = 10.0
    ): OfflineRoute {
        val shape = straightNorthShape(shapeCount, stepMeters)
        return OfflineRoute(
            id = "test_route",
            distanceMeters = (shapeCount - 1) * stepMeters,
            durationSeconds = 180.0,
            shape = shape,
            maneuvers = maneuvers,
            summaryLabel = "Test"
        )
    }

    private fun makeExitLanes(totalLanes: Int): List<LaneInfo> {
        val thru = LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), false, false, false)
        val exit = LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), true, true, true)
        return List(totalLanes - 1) { thru } + exit
    }

    @Test
    fun turnFocusPlannerTriggersAtCity500mAndHighway1000m() {
        val cityTurn = makeManeuver(1, ManeuverType.LEFT, "Turn left onto Willamette St", listOf("Willamette St"), 600.0, 40.0, 100, 120)
        val route = makeRoute(
            shapeCount = 150,
            maneuvers = listOf(
                makeManeuver(0, ManeuverType.DEPART, "Head north", listOf("13th Ave"), 1000.0, 60.0, 0, 100),
                cityTurn
            )
        )
        val tracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig()

        // 550 m away: not yet triggered
        val far = tracker.evaluate(route, 1, 45, 550.0, 13f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertFalse(far.isActive)
        assertFalse(far.justEntered)

        // 500 m away: triggers with beeps
        val trigger = tracker.evaluate(route, 1, 50, 500.0, 13f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertTrue(trigger.isActive)
        assertTrue(trigger.justEntered)
        assertTrue(trigger.shouldBeep)
        assertFalse(trigger.isHighway)
        assertEquals(500.0, trigger.triggerDistanceMeters, 0.1)

        // Still active at 30 m past pivot (index 103 -> 30 m past 100)
        val past30 = tracker.evaluate(route, 1, 103, 0.0, 13f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertTrue(past30.isActive)
        assertFalse(past30.justEntered)

        // Ends at >= 50 m past pivot (index 106 -> 60 m past 100)
        val past60 = tracker.evaluate(route, 1, 106, 0.0, 13f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertFalse(past60.isActive)
        assertTrue(past60.justEnded)

        // Never re-triggers for same maneuver index
        val retry = tracker.evaluate(route, 1, 60, 400.0, 13f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertFalse(retry.isActive)
        assertFalse(retry.justEntered)
    }

    @Test
    fun turnFocusPlannerHighwayExitAndWideHighwayRule() {
        val hwyExit = makeManeuver(
            index = 1,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B",
            streetNames = listOf("I-105"),
            lengthMeters = 1800.0,
            timeSeconds = 70.0,
            beginShapeIndex = 200,
            endShapeIndex = 240,
            isHighwayRoad = true,
            isHighwayExitOrSplit = true,
            lanes = makeExitLanes(6) // 5 lanes to cross at 70 mph (31 m/s) -> 5*155 + 300 = 1075 m
        )
        val route = makeRoute(
            shapeCount = 260,
            maneuvers = listOf(
                makeManeuver(0, ManeuverType.DEPART, "Head north on I-5", listOf("I-5"), 2000.0, 80.0, 0, 200, isHighwayRoad = true),
                hwyExit
            )
        )

        val wideOn = TurnFocusPlanner.computeTriggerDistanceMeters(hwyExit, route.maneuvers[0], 31.0f, TurnFocusConfig(wideHighwayEarlierEnabled = true))
        assertTrue("Wide highway 5-lane cross should trigger > 1000 m, was $wideOn", wideOn > 1000.0)
        assertTrue("Wide highway distance must be capped at 1600 m, was $wideOn", wideOn <= 1600.0)

        val ultraWide = hwyExit.copy(lanes = makeExitLanes(10))
        val capped = TurnFocusPlanner.computeTriggerDistanceMeters(ultraWide, route.maneuvers[0], 31.0f, TurnFocusConfig(wideHighwayEarlierEnabled = true))
        assertEquals(1600.0, capped, 0.1)

        val wideOff = TurnFocusPlanner.computeTriggerDistanceMeters(hwyExit, route.maneuvers[0], 31.0f, TurnFocusConfig(wideHighwayEarlierEnabled = false))
        assertEquals(1000.0, wideOff, 0.1)

        // Highway exit ends at >= 150 m past pivot (still active at 100 m past)
        val tracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig(wideHighwayEarlierEnabled = false)
        assertFalse(tracker.evaluate(route, 1, 95, 1050.0, 29f, TravelMode.DRIVE, true, false, false, false, cfg).isActive)
        val entered = tracker.evaluate(route, 1, 100, 1000.0, 29f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertTrue(entered.isActive && entered.justEntered && entered.isHighway && entered.shouldBeep)
        assertTrue(tracker.evaluate(route, 1, 210, 0.0, 29f, TravelMode.DRIVE, true, false, false, false, cfg).isActive) // 100 m past
        val ended = tracker.evaluate(route, 1, 216, 0.0, 29f, TravelMode.DRIVE, true, false, false, false, cfg) // 160 m past
        assertFalse(ended.isActive)
        assertTrue(ended.justEnded)
    }

    @Test
    fun turnFocusPreviousManeuverClearanceTooLateAndNonFocusRules() {
        val m0 = makeManeuver(0, ManeuverType.LEFT, "Turn left", listOf("Oak St"), 200.0, 15.0, 0, 20)
        val m1 = makeManeuver(1, ManeuverType.RIGHT, "Turn right", listOf("11th Ave"), 200.0, 15.0, 20, 40)
        val route = makeRoute(60, listOf(m0, m1))
        val tracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig()

        // Closely spaced turns (200 m < 500 m): at 10 m past m0 (idx 1) must NOT trigger yet
        val tooSoon = tracker.evaluate(route, 1, 1, 190.0, 11f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertFalse("Should wait 30 m past previous maneuver", tooSoon.isActive)

        // At 40 m past m0 (idx 4, 160 m before m1): triggers normally
        val cleared = tracker.evaluate(route, 1, 4, 160.0, 11f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertTrue(cleared.isActive)
        assertTrue(cleared.justEntered)

        // Off-route ends immediately
        val offRoute = tracker.evaluate(route, 1, 5, 150.0, 11f, TravelMode.DRIVE, true, true, false, false, cfg)
        assertFalse(offRoute.isActive)
        assertTrue(offRoute.justEnded)

        // Too-late rule: first observed at 100 m (< 150 m city) -> active visually, shouldBeep == false
        val lateTracker = TurnFocusSessionTracker()
        val longRoute = makeRoute(
            120,
            listOf(
                makeManeuver(0, ManeuverType.DEPART, "Start", listOf("A"), 1000.0, 60.0, 0, 100),
                makeManeuver(1, ManeuverType.LEFT, "Turn left", listOf("B"), 200.0, 20.0, 100, 115)
            )
        )
        val late = lateTracker.evaluate(longRoute, 1, 90, 100.0, 10f, TravelMode.DRIVE, true, false, false, false, cfg)
        assertTrue(late.isActive)
        assertTrue(late.justEntered)
        assertFalse("Too-late entry must not beep", late.shouldBeep)

        // Non-focus maneuvers never trigger
        listOf(ManeuverType.STRAIGHT, ManeuverType.MERGE, ManeuverType.DEPART, ManeuverType.ARRIVE, ManeuverType.FERRY).forEach { type ->
            val m = makeManeuver(1, type, "Go", listOf("Main St"), 500.0, 30.0, 50, 80)
            assertFalse("$type must not be a focus maneuver", TurnFocusPlanner.isFocusManeuver(m))
        }

        // Walk mode never triggers
        val walkRes = TurnFocusSessionTracker().evaluate(longRoute, 1, 60, 400.0, 1.4f, TravelMode.WALK, true, false, false, false, cfg)
        assertFalse(walkRes.isActive)
    }

    @Test
    fun threeBeepsTimingAndDefaultsSuppressOldBeeps() {
        assertEquals(3, TurnFocusAudioPlayer.BEEP_COUNT)
        assertEquals(150, TurnFocusAudioPlayer.BEEP_DURATION_MS)
        assertEquals(150, TurnFocusAudioPlayer.BEEP_GAP_MS)
        assertEquals(750L, TurnFocusAudioPlayer.TOTAL_DURATION_MS)
        assertEquals(listOf(0L, 300L, 600L), TurnFocusAudioPlayer.beepStartOffsetsMs())
        assertEquals(300L, TurnFocusAudioPlayer.POST_BEEP_ZOOM_DELAY_MS)

        val defaults = UserSettings()
        assertTrue(defaults.turnFocusModeEnabled)
        assertTrue(defaults.turnFocusBeepsEnabled)
        assertFalse(defaults.turnFocusShortPhraseEnabled)
        assertEquals(500, defaults.turnFocusCityDistanceMeters)
        assertEquals(1000, defaults.turnFocusHighwayDistanceMeters)
        assertTrue(defaults.turnFocusWideHighwayEarlierEnabled)
        assertEquals("500m (0.3mi)", formatFocusDistanceChip(500, DistanceUnit.IMPERIAL, AppLanguage.ENGLISH))
        assertEquals("1000 m", formatFocusDistanceChip(1000, DistanceUnit.METRIC, AppLanguage.ENGLISH))
    }

    @Test
    fun laneOverlayBuilderCustomStartDistanceAndFocusArrows() {
        val exitManeuver = makeManeuver(
            index = 1,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B",
            streetNames = listOf("I-105"),
            lengthMeters = 1200.0,
            timeSeconds = 45.0,
            beginShapeIndex = 120,
            endShapeIndex = 135,
            isHighwayRoad = true,
            isHighwayExitOrSplit = true,
            lanes = makeExitLanes(3)
        )
        val route = makeRoute(
            shapeCount = 140,
            maneuvers = listOf(
                makeManeuver(0, ManeuverType.DEPART, "I-5 North", listOf("I-5"), 1200.0, 45.0, 0, 120, isHighwayRoad = true),
                exitManeuver
            )
        )

        // Default 500 m returns null at 900 m away
        assertNull(LaneOverlayBuilder.build(route, 1, 30, 900.0, TravelMode.DRIVE))

        // Focus startDistanceMeters = 1000 m builds overlay at 900 m away with repeating 150 m arrows + 1 large 40 m arrow
        val overlay = LaneOverlayBuilder.build(
            route = route,
            currentManeuverIndex = 1,
            snappedShapeIndex = 30,
            distanceToNextManeuverMeters = 900.0,
            travelMode = TravelMode.DRIVE,
            startDistanceMeters = 1000.0,
            isFocusMode = true
        )
        assertNotNull(overlay)
        assertEquals(3, overlay!!.lanes.size)
        assertTrue("Focus mode should generate multiple lane arrows every 150 m", overlay.arrowMarkers.size >= 5)
        assertEquals(1, overlay.arrowMarkers.count { it.isLargeArrow })
    }

    @Test
    fun laneColorDefaultBlueAndOneTimeMigration() {
        assertEquals(LaneHighlightColor.BLUE, LaneHighlightColor.fromCode(null))
        assertEquals(LaneHighlightColor.BLUE, UserSettings().laneHighlightColor)
        assertEquals("#1E88E5", LaneHighlightColor.BLUE.hex)

        // Unmigrated default/legacy RED -> migrates to BLUE
        assertEquals(
            LaneHighlightColor.BLUE,
            UserPreferencesRepository.resolveMigratedLaneColor(null, migratedToBlue = false, userModified = false)
        )
        assertEquals(
            LaneHighlightColor.BLUE,
            UserPreferencesRepository.resolveMigratedLaneColor("red", migratedToBlue = false, userModified = false)
        )
        // Explicit GREEN before migration is preserved
        assertEquals(
            LaneHighlightColor.GREEN,
            UserPreferencesRepository.resolveMigratedLaneColor("green", migratedToBlue = false, userModified = false)
        )
        // Explicit RED after migration (or user-modified) is preserved
        assertEquals(
            LaneHighlightColor.RED,
            UserPreferencesRepository.resolveMigratedLaneColor("red", migratedToBlue = true, userModified = true)
        )
    }

    @Test
    fun carCameraProfileZoomOffsetsTiltAndFocusOverride() {
        assertEquals(0.0, CarMapView.CLOSE.zoomOffset, 0.001)
        assertEquals(-0.8, CarMapView.NORMAL.zoomOffset, 0.001)
        assertEquals(-1.5, CarMapView.WIDE.zoomOffset, 0.001)

        val navNormal = CarCameraProfile.computeCarCameraTarget(17.0, CarMapView.NORMAL, isNavigating = true, is3DView = true)
        assertEquals(16.2, navNormal.zoom, 0.001)
        assertEquals(40.0, navNormal.tilt, 0.001)
        assertFalse(navNormal.anchorLowerQuarter)

        val freeWide = CarCameraProfile.computeCarCameraTarget(16.5, CarMapView.WIDE, isNavigating = false, is3DView = true)
        assertEquals(15.0, freeWide.zoom, 0.001)
        assertEquals(30.0, freeWide.tilt, 0.001)

        val flat2d = CarCameraProfile.computeCarCameraTarget(16.5, CarMapView.CLOSE, isNavigating = true, is3DView = false)
        assertEquals(0.0, flat2d.tilt, 0.001)

        // Turn Focus overrides car profile
        val focusHwy = CarCameraProfile.computeCarCameraTarget(14.8, CarMapView.WIDE, true, true, focusCameraActive = true, focusIsHighway = true)
        assertEquals(17.3, focusHwy.zoom, 0.001)
        assertEquals(55.0, focusHwy.tilt, 0.001)
        assertTrue(focusHwy.anchorLowerQuarter)

        val focusCity2d = CarCameraProfile.computeCarCameraTarget(16.0, CarMapView.NORMAL, true, false, focusCameraActive = true, focusIsHighway = false)
        assertEquals(18.0, focusCity2d.zoom, 0.001)
        assertEquals(0.0, focusCity2d.tilt, 0.001)
        assertTrue(focusCity2d.anchorLowerQuarter)
    }

    @Test
    fun carMapStyleTunesMinorRoadsAndLabelsWithoutTouchingPhoneStyle() {
        val phoneNight = com.example.map.MapStylePaletteAndExprs.NightMapPalette
        val carNight = com.example.map.MapStylePaletteAndExprs.NightCarMapPalette
        assertEquals("#223254", phoneNight.minorRoad)
        assertEquals("#3E5682", carNight.minorRoad)
        assertEquals("#364B75", phoneNight.majorRoad)
        assertEquals("#5470A3", carNight.majorRoad)
        assertEquals(MapStyleBuilder.StyleProfile.PHONE, MapStyleBuilder.StyleProfile.valueOf("PHONE"))
        assertEquals(MapStyleBuilder.StyleProfile.CAR, MapStyleBuilder.StyleProfile.valueOf("CAR"))
    }

    @Test
    fun carManeuverMapperFallbackStepHandlesMissingStreetNamesAndNull() {
        val bareLeft = makeManeuver(1, ManeuverType.LEFT, "", emptyList(), 300.0, 20.0, 10, 20)
        val stepLeft = CarManeuverMapper.buildStepOrFallback(bareLeft, AppLanguage.ENGLISH)
        assertEquals("Turn left", stepLeft.cue?.toString())
        assertNotNull(stepLeft.maneuver)

        val bareExit = makeManeuver(
            index = 2,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "",
            streetNames = emptyList(),
            lengthMeters = 500.0,
            timeSeconds = 20.0,
            beginShapeIndex = 20,
            endShapeIndex = 30,
            signpost = HighwaySignpost(exitNumbers = listOf("194B"))
        )
        val stepExit = CarManeuverMapper.buildStepOrFallback(bareExit, AppLanguage.ENGLISH)
        assertEquals("Take exit 194B", stepExit.cue?.toString())

        val fallbackNull = CarManeuverMapper.buildStepOrFallback(null, AppLanguage.ENGLISH)
        assertEquals("Continue", fallbackNull.cue?.toString())
        assertNotNull(fallbackNull.maneuver)
    }

    @Test
    fun simulationLaneEnrichmentCoversHighwayExitsAndCityTurns() {
        val raw = makeRoute(
            shapeCount = 80,
            maneuvers = listOf(
                makeManeuver(0, ManeuverType.DEPART, "Start", listOf("Franklin Blvd"), 300.0, 25.0, 0, 25),
                makeManeuver(1, ManeuverType.LEFT, "Turn left onto Coburg Rd", listOf("Coburg Rd"), 300.0, 25.0, 25, 50),
                makeManeuver(2, ManeuverType.EXIT_RIGHT, "Take exit 194B", listOf("I-105"), 400.0, 20.0, 50, 70),
                makeManeuver(3, ManeuverType.STRAIGHT, "Continue", listOf("I-105"), 100.0, 5.0, 70, 79)
            )
        )
        val enriched = enrichHighwayExitLanesForSimulation(raw)
        assertEquals(2, enriched.maneuvers[1].lanes.size) // City left turn enriched
        assertTrue(enriched.maneuvers[1].lanes[0].isRecommended)
        assertEquals(3, enriched.maneuvers[2].lanes.size) // Highway exit enriched
        assertTrue(enriched.maneuvers[2].lanes[2].isRecommended)
        assertTrue(enriched.maneuvers[3].lanes.isEmpty()) // Straight untouched
    }
}
