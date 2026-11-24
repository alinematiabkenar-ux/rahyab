package com.example

import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.navigation.ActiveNavigationState
import com.example.navigation.HighwaySignpost
import com.example.navigation.JunctionSceneBuilder
import com.example.navigation.JunctionSceneType
import com.example.navigation.JunctionSide
import com.example.navigation.LaneDirection
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.RouteShield
import com.example.navigation.RouteShieldKind
import com.example.navigation.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J7: Pure JVM unit tests for [JunctionSceneBuilder] and highway junction view lifecycle.
 */
class JunctionSceneUnitTest {

    private fun lane(dir: LaneDirection, active: Boolean): LaneInfo = LaneInfo(
        directions = listOf(dir),
        activeDirections = if (active) listOf(dir) else emptyList(),
        isRecommended = active,
        isActive = active,
        isValid = active,
        level = if (active) LaneHighlightTier.ACTIVE else LaneHighlightTier.INACTIVE
    )

    @Test
    fun exitRight_threeMainAndOneExitLane_buildsExpectedSceneAndShields() {
        val maneuver = RouteManeuver(
            index = 3,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B onto I-105 West toward Eugene / Springfield.",
            streetNames = listOf("I-105 W", "OR-126 W"),
            lengthMeters = 600.0,
            timeSeconds = 25.0,
            beginShapeIndex = 10,
            endShapeIndex = 14,
            lat = 44.0725,
            lon = -123.0468,
            signpost = HighwaySignpost(
                exitNumbers = listOf("194B"),
                exitBranches = listOf("I-105", "OR-126"),
                exitTowards = listOf("Eugene", "Springfield")
            ),
            lanes = listOf(
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.SLIGHT_RIGHT, true)
            ),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )

        val scene = JunctionSceneBuilder.build(maneuver)
        assertNotNull(scene)
        scene!!
        assertEquals(JunctionSceneType.EXIT_RIGHT, scene.type)
        assertEquals(JunctionSide.RIGHT, scene.side)
        assertEquals(4, scene.totalLanes)
        assertEquals(3, scene.mainLaneCount)
        assertEquals(listOf(0, 1, 2), scene.continuingLaneIndices)
        assertEquals(listOf(3), scene.exitLaneIndices)
        assertEquals(listOf(3), scene.recommendedLaneIndices)
        assertEquals("194B", scene.exitNumber)
        assertEquals(
            listOf(
                RouteShield(RouteShieldKind.INTERSTATE, "I-105"),
                RouteShield(RouteShieldKind.STATE, "OR-126")
            ),
            scene.shields
        )
        assertEquals(listOf("Eugene", "Springfield"), scene.destinationLines)
        assertTrue(scene.hasRealLaneData)
    }

    @Test
    fun exitLeft_buildsLeftExitScene() {
        val maneuver = RouteManeuver(
            index = 2,
            type = ManeuverType.EXIT_LEFT,
            instructionEn = "Take left exit 195 onto Beltline Hwy.",
            streetNames = listOf("OR-569 W"),
            lengthMeters = 500.0,
            timeSeconds = 20.0,
            beginShapeIndex = 5,
            endShapeIndex = 8,
            lat = 44.100,
            lon = -123.046,
            signpost = HighwaySignpost(
                exitNumbers = listOf("195"),
                exitBranches = listOf("OR-569", "US-99"),
                exitTowards = listOf("Airport")
            ),
            lanes = listOf(
                lane(LaneDirection.SLIGHT_LEFT, true),
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.THROUGH, false)
            ),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )

        val scene = JunctionSceneBuilder.build(maneuver)
        assertNotNull(scene)
        scene!!
        assertEquals(JunctionSceneType.EXIT_LEFT, scene.type)
        assertEquals(JunctionSide.LEFT, scene.side)
        assertEquals(listOf(0), scene.exitLaneIndices)
        assertEquals(listOf(1, 2), scene.continuingLaneIndices)
        assertEquals(listOf(0), scene.recommendedLaneIndices)
        assertEquals("195", scene.exitNumber)
        assertTrue(scene.shields.any { it.kind == RouteShieldKind.US && it.text == "US-99" })
    }

    @Test
    fun highwayFork_leftAndRight_buildsForkScenes() {
        val forkLeft = RouteManeuver(
            index = 1,
            type = ManeuverType.STAY_LEFT,
            instructionEn = "Keep left onto I-5 North.",
            streetNames = listOf("I-5 N"),
            lengthMeters = 400.0,
            timeSeconds = 18.0,
            beginShapeIndex = 3,
            endShapeIndex = 5,
            lat = 44.08,
            lon = -123.05,
            lanes = listOf(
                lane(LaneDirection.SLIGHT_LEFT, true),
                lane(LaneDirection.SLIGHT_LEFT, true),
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.THROUGH, false)
            ),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )

        val sceneLeft = JunctionSceneBuilder.build(forkLeft)!!
        assertEquals(JunctionSceneType.FORK_LEFT, sceneLeft.type)
        assertEquals(JunctionSide.LEFT, sceneLeft.side)
        assertEquals(listOf(0, 1), sceneLeft.exitLaneIndices)
        assertEquals(listOf(2, 3), sceneLeft.continuingLaneIndices)

        val forkRight = forkLeft.copy(
            type = ManeuverType.STAY_RIGHT,
            lanes = listOf(
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.SLIGHT_RIGHT, true)
            )
        )
        val sceneRight = JunctionSceneBuilder.build(forkRight)!!
        assertEquals(JunctionSceneType.FORK_RIGHT, sceneRight.type)
        assertEquals(JunctionSide.RIGHT, sceneRight.side)
        assertEquals(listOf(1), sceneRight.exitLaneIndices)
        assertEquals(listOf(0), sceneRight.continuingLaneIndices)
    }

    @Test
    fun missingSignpost_fallsBackToStreetName() {
        val maneuver = RouteManeuver(
            index = 4,
            type = ManeuverType.RAMP_RIGHT,
            instructionEn = "Take the ramp onto Franklin Blvd.",
            streetNames = listOf("Franklin Blvd"),
            lengthMeters = 350.0,
            timeSeconds = 15.0,
            beginShapeIndex = 8,
            endShapeIndex = 10,
            lat = 44.045,
            lon = -123.055,
            signpost = null,
            lanes = listOf(
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.RIGHT, true)
            ),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )

        val scene = JunctionSceneBuilder.build(maneuver)!!
        assertNull(scene.exitNumber)
        assertTrue(scene.shields.isEmpty())
        assertEquals(listOf("Franklin Blvd"), scene.destinationLines)
        assertTrue(scene.hasRealLaneData)
    }

    @Test
    fun missingLanes_buildsGenericFallbackScene() {
        val maneuver = RouteManeuver(
            index = 5,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194A toward Springfield.",
            streetNames = listOf("OR-126 E"),
            lengthMeters = 450.0,
            timeSeconds = 20.0,
            beginShapeIndex = 6,
            endShapeIndex = 8,
            lat = 44.070,
            lon = -123.046,
            signpost = HighwaySignpost(
                exitNumbers = listOf("194A"),
                exitBranches = listOf("OR-126"),
                exitTowards = listOf("Springfield")
            ),
            lanes = emptyList(),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )

        val scene = JunctionSceneBuilder.build(maneuver)!!
        assertFalse(scene.hasRealLaneData)
        assertEquals(JunctionSceneType.EXIT_RIGHT, scene.type)
        assertEquals(4, scene.totalLanes)
        assertEquals(3, scene.mainLaneCount)
        assertEquals(listOf(3), scene.exitLaneIndices)
        assertEquals("194A", scene.exitNumber)
    }

    @Test
    fun nonHighwayCityTurn_returnsNull() {
        val cityTurn = RouteManeuver(
            index = 1,
            type = ManeuverType.RIGHT,
            instructionEn = "Turn right onto Willamette St.",
            streetNames = listOf("Willamette St"),
            lengthMeters = 120.0,
            timeSeconds = 14.0,
            beginShapeIndex = 2,
            endShapeIndex = 4,
            lat = 44.052,
            lon = -123.092,
            signpost = null,
            lanes = listOf(
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.RIGHT, true)
            ),
            isHighwayExitOrSplit = false,
            isHighwayRoad = false
        )

        assertNull(JunctionSceneBuilder.build(cityTurn))
    }

    @Test
    fun resolveActiveJunctionManeuver_enforcesDistanceModesDismissAndOffRoute() {
        val exitLat = 44.0725
        val exitLon = -123.0468
        val route = buildI5SpringfieldTestRoute(exitLat, exitLon)

        // Visible at 790 m before exit
        assertEquals(
            1,
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route = route,
                upcomingManeuverIdx = 1,
                distToUpcomingMeters = 790.0,
                snappedLoc = LatLon(44.0654, exitLon),
                isDrive = true,
                hasArrived = false,
                isOffRoute = false,
                junctionEnabled = true,
                dismissedManeuverIndex = -1
            )?.index
        )

        // Hidden at 850 m before exit
        assertNull(
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route = route,
                upcomingManeuverIdx = 1,
                distToUpcomingMeters = 850.0,
                snappedLoc = LatLon(44.0648, exitLon),
                isDrive = true,
                hasArrived = false,
                isOffRoute = false,
                junctionEnabled = true,
                dismissedManeuverIndex = -1
            )
        )

        // Still visible 30 m after passing the exit
        assertEquals(
            1,
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route = route,
                upcomingManeuverIdx = 2,
                distToUpcomingMeters = 1200.0,
                snappedLoc = LatLon(exitLat + 0.00027, exitLon),
                isDrive = true,
                hasArrived = false,
                isOffRoute = false,
                junctionEnabled = true,
                dismissedManeuverIndex = -1
            )?.index
        )

        // Hidden 70 m after passing the exit
        assertNull(
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route = route,
                upcomingManeuverIdx = 2,
                distToUpcomingMeters = 1130.0,
                snappedLoc = LatLon(exitLat + 0.00063, exitLon),
                isDrive = true,
                hasArrived = false,
                isOffRoute = false,
                junctionEnabled = true,
                dismissedManeuverIndex = -1
            )
        )

        // Hidden when off-route, dismissed, disabled, or non-DRIVE
        assertNull(
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route, 1, 400.0, LatLon(44.069, exitLon),
                isDrive = true, hasArrived = false, isOffRoute = true, junctionEnabled = true, dismissedManeuverIndex = -1
            )
        )
        assertNull(
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route, 1, 400.0, LatLon(44.069, exitLon),
                isDrive = true, hasArrived = false, isOffRoute = false, junctionEnabled = true, dismissedManeuverIndex = 1
            )
        )
        assertNull(
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route, 1, 400.0, LatLon(44.069, exitLon),
                isDrive = true, hasArrived = false, isOffRoute = false, junctionEnabled = false, dismissedManeuverIndex = -1
            )
        )
        assertNull(
            JunctionSceneBuilder.resolveActiveJunctionManeuver(
                route, 1, 400.0, LatLon(44.069, exitLon),
                isDrive = false, hasArrived = false, isOffRoute = false, junctionEnabled = true, dismissedManeuverIndex = -1
            )
        )
    }

    @Test
    fun i5EugeneSpringfieldSimulation_triggersJunctionViewWithin800m() {
        val route = buildI5SpringfieldTestRoute(44.0725, -123.0468)
        val controller = NavigationSessionController()
        var state = ActiveNavigationState(
            isNavigating = true,
            isSimulatingDrive = true,
            travelMode = TravelMode.DRIVE,
            route = route,
            currentManeuverIndex = 0,
            snappedShapeIndex = 0,
            snappedLocation = route.shape.first(),
            remainingDistanceMeters = route.distanceMeters,
            remainingTimeSeconds = route.durationSeconds
        )

        var sawJunctionView = false
        var simClockMs = 1_700_000_000_000L
        val settings = UserSettings(junctionPreviewEnabled = true)

        repeat(45) {
            val batch = controller.advanceSimulationStep(
                currentState = state,
                totalSimulatedSeconds = 2.0,
                startingSimClockMs = simClockMs,
                wrongTurnOffsetMeters = 0.0,
                dbHazards = emptyList(),
                userReports = emptyList(),
                unit = DistanceUnit.IMPERIAL,
                language = AppLanguage.ENGLISH,
                speedAudioEnabled = false,
                settings = settings
            )
            state = batch.state
            simClockMs = batch.finalSimClockMs
            if (state.showJunctionPreview && state.activeJunctionManeuver?.signpost?.primaryExitNumber == "194B") {
                sawJunctionView = true
            }
        }

        assertTrue("I-5 simulation should trigger Junction View at Exit 194B", sawJunctionView)
    }

    private fun buildI5SpringfieldTestRoute(exitLat: Double, exitLon: Double): OfflineRoute {
        val shape = (0..20).map { step ->
            val lat = (exitLat - 0.0108) + step * 0.0009 // ~100 m per step
            LatLon(lat, exitLon)
        }
        val depart = RouteManeuver(
            index = 0,
            type = ManeuverType.DEPART,
            instructionEn = "Drive north on I-5.",
            streetNames = listOf("I-5 N"),
            lengthMeters = 1200.0,
            timeSeconds = 42.0,
            beginShapeIndex = 0,
            endShapeIndex = 12,
            lat = shape[0].lat,
            lon = shape[0].lon,
            isHighwayRoad = true
        )
        val exit194B = RouteManeuver(
            index = 1,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B onto I-105 West toward Eugene / Springfield.",
            streetNames = listOf("I-105 W", "OR-126 W"),
            lengthMeters = 800.0,
            timeSeconds = 30.0,
            beginShapeIndex = 12,
            endShapeIndex = 20,
            lat = shape[12].lat,
            lon = shape[12].lon,
            signpost = HighwaySignpost(
                exitNumbers = listOf("194B"),
                exitBranches = listOf("I-105", "OR-126"),
                exitTowards = listOf("Eugene", "Springfield")
            ),
            lanes = listOf(
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.THROUGH, false),
                lane(LaneDirection.SLIGHT_RIGHT, true)
            ),
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )
        val arrive = RouteManeuver(
            index = 2,
            type = ManeuverType.ARRIVE,
            instructionEn = "You have arrived.",
            streetNames = listOf("I-105 W"),
            lengthMeters = 0.0,
            timeSeconds = 0.0,
            beginShapeIndex = 20,
            endShapeIndex = 20,
            lat = shape[20].lat,
            lon = shape[20].lon
        )
        return OfflineRoute(
            id = "i5_springfield_test",
            summaryLabel = "I-5 N",
            distanceMeters = 2000.0,
            durationSeconds = 72.0,
            shape = shape,
            maneuvers = listOf(depart, exit194B, arrive),
            travelMode = TravelMode.DRIVE
        )
    }
}
