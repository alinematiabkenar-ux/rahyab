package com.example

import com.example.data.search.PlaceSearchResult
import com.example.map.LaneOverlayBuilder
import com.example.navigation.LaneDirection
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.ParkingNearDestinationFinder
import com.example.navigation.ParkingSpotType
import com.example.navigation.RouteManeuver
import com.example.navigation.SpeedLimitChangeAhead
import com.example.navigation.SpeedLimitDropVoiceTracker
import com.example.navigation.TravelMode
import com.example.navigation.VoiceCommand
import com.example.navigation.VoiceCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1: Pure JVM unit tests for LN (LaneOverlayBuilder), SL (SpeedLimitChangeAhead),
 * PK (ParkingNearDestinationFinder), and VC (VoiceCommandParser).
 */
class NextBatchUnitTest {

    // -------------------------------------------------------------------------
    // LN: LaneOverlayBuilder offsets & guards
    // -------------------------------------------------------------------------

    @Test
    fun laneOverlay_oneWayThreeLanes_offsetsSeparatorsAndTargets() {
        val offsets = LaneOverlayBuilder.computeLaneOffsetsMeters(laneCount = 3, isOneWay = true)
        assertEquals(3, offsets.size)
        assertEquals(-3.6, offsets[0], 1e-6)
        assertEquals(0.0, offsets[1], 1e-6)
        assertEquals(3.6, offsets[2], 1e-6)

        val seps = LaneOverlayBuilder.computeSeparatorOffsetsMeters(laneCount = 3, isOneWay = true)
        assertEquals(2, seps.size)
        assertEquals(-1.8, seps[0], 1e-6)
        assertEquals(1.8, seps[1], 1e-6)

        val route = buildStraightNorthRoute(
            pointCount = 40,
            stepMeters = 25.0,
            maneuverShapeIndex = 30,
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
                LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
            ),
            oneWayByShapeIndex = mapOf(29 to true, 30 to true)
        )
        val overlay = LaneOverlayBuilder.build(
            route = route,
            currentManeuverIndex = 0,
            snappedShapeIndex = 15,
            distanceToNextManeuverMeters = 375.0,
            travelMode = TravelMode.DRIVE,
            isOffRoute = false,
            enabled = true
        )
        assertNotNull(overlay)
        assertTrue(overlay!!.isOneWay)
        assertEquals(3, overlay.lanes.size)
        assertEquals(2, overlay.separators.size)
        assertEquals(1, overlay.arrowMarkers.size)
        assertFalse(overlay.lanes[0].isTarget)
        assertFalse(overlay.lanes[1].isTarget)
        assertTrue(overlay.lanes[2].isTarget)
        assertEquals(LaneDirection.SLIGHT_RIGHT, overlay.arrowMarkers.first().direction)
    }

    @Test
    fun laneOverlay_twoWayTwoLanes_offsetsToRightHalfOfRoad() {
        val offsets = LaneOverlayBuilder.computeLaneOffsetsMeters(laneCount = 2, isOneWay = false)
        assertEquals(2, offsets.size)
        assertEquals(1.8, offsets[0], 1e-6)
        assertEquals(5.4, offsets[1], 1e-6)

        val seps = LaneOverlayBuilder.computeSeparatorOffsetsMeters(laneCount = 2, isOneWay = false)
        assertEquals(1, seps.size)
        assertEquals(3.6, seps[0], 1e-6)

        val route = buildStraightNorthRoute(
            pointCount = 30,
            stepMeters = 25.0,
            maneuverShapeIndex = 20,
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.LEFT), listOf(LaneDirection.LEFT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE)
            ),
            oneWayByShapeIndex = mapOf(19 to false, 20 to false)
        )
        val overlay = LaneOverlayBuilder.build(
            route = route,
            currentManeuverIndex = 0,
            snappedShapeIndex = 10,
            distanceToNextManeuverMeters = 250.0,
            travelMode = TravelMode.DRIVE
        )
        assertNotNull(overlay)
        assertFalse(overlay!!.isOneWay)
        assertEquals(1.8, overlay.lanes[0].offsetMeters, 1e-6)
        assertEquals(5.4, overlay.lanes[1].offsetMeters, 1e-6)
    }

    @Test
    fun laneOverlay_guards_missingAllValidWalkBikeOffRouteDisabled() {
        val singleLaneRoute = buildStraightNorthRoute(
            pointCount = 25,
            stepMeters = 25.0,
            maneuverShapeIndex = 18,
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
            )
        )
        assertNull(LaneOverlayBuilder.build(singleLaneRoute, 0, 10, 200.0, TravelMode.DRIVE))

        val allValidRoute = buildStraightNorthRoute(
            pointCount = 25,
            stepMeters = 25.0,
            maneuverShapeIndex = 18,
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.THROUGH), listOf(LaneDirection.THROUGH), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = true, level = LaneHighlightTier.VALID)
            )
        )
        assertNull(LaneOverlayBuilder.build(allValidRoute, 0, 10, 200.0, TravelMode.DRIVE))

        val validOverlayRoute = buildStraightNorthRoute(
            pointCount = 25,
            stepMeters = 25.0,
            maneuverShapeIndex = 18,
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
                LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
            )
        )
        assertNull(LaneOverlayBuilder.build(validOverlayRoute, 0, 10, 200.0, TravelMode.WALK))
        assertNull(LaneOverlayBuilder.build(validOverlayRoute, 0, 10, 200.0, TravelMode.BIKE))
        assertNull(LaneOverlayBuilder.build(validOverlayRoute, 0, 10, 200.0, TravelMode.DRIVE, isOffRoute = true))
        assertNull(LaneOverlayBuilder.build(validOverlayRoute, 0, 10, 200.0, TravelMode.DRIVE, enabled = false))
        assertNull(LaneOverlayBuilder.build(validOverlayRoute, 0, 0, 650.0, TravelMode.DRIVE))
    }

    // -------------------------------------------------------------------------
    // SL: SpeedLimitChangeAhead thresholds, duration & unknown handling
    // -------------------------------------------------------------------------

    @Test
    fun speedLimitDrop_65to45LastingOver150m_detected() {
        // 25 m per step: indices 0..10 at 65 mph, 11..22 at 45 mph (11 * 25 = 275 m >= 150 m)
        val limits = buildMap {
            for (i in 0..10) put(i, 65)
            for (i in 11..22) put(i, 45)
        }
        val route = buildStraightNorthRoute(pointCount = 23, stepMeters = 25.0, speedLimits = limits)
        val drop = SpeedLimitChangeAhead.findNextDrop(route, snappedShapeIndex = 0, currentSpeedLimitMph = 65)
        assertNotNull(drop)
        assertEquals(11, drop!!.changeShapeIndex)
        assertEquals(65, drop.currentLimitMph)
        assertEquals(45, drop.newLimitMph)
        assertEquals(275.0, drop.distanceMeters, 15.0)
    }

    @Test
    fun speedLimitDrop_45to40_ignored() {
        val limits = buildMap {
            for (i in 0..8) put(i, 45)
            for (i in 9..20) put(i, 40)
        }
        val route = buildStraightNorthRoute(pointCount = 21, stepMeters = 25.0, speedLimits = limits)
        val drop = SpeedLimitChangeAhead.findNextDrop(route, snappedShapeIndex = 0, currentSpeedLimitMph = 45)
        assertNull(drop)
    }

    @Test
    fun speedLimitDrop_shortDipUnder150m_ignored() {
        // Drop to 35 mph lasts only 3 * 25 = 75 m before returning to 65 mph
        val limits = buildMap {
            for (i in 0..8) put(i, 65)
            for (i in 9..11) put(i, 35)
            for (i in 12..25) put(i, 65)
        }
        val route = buildStraightNorthRoute(pointCount = 26, stepMeters = 25.0, speedLimits = limits)
        val drop = SpeedLimitChangeAhead.findNextDrop(route, snappedShapeIndex = 0, currentSpeedLimitMph = 65)
        assertNull(drop)
    }

    @Test
    fun speedLimitDrop_unknownValuesIgnoredAndDropAfterGapStillDetected() {
        val emptyLimitsRoute = buildStraightNorthRoute(pointCount = 20, stepMeters = 25.0, speedLimits = emptyMap())
        assertNull(SpeedLimitChangeAhead.findNextDrop(emptyLimitsRoute, snappedShapeIndex = 0))

        // Unknown gap between indices 5..9, then sustained 45 mph at 10..20
        val gapLimits = buildMap {
            for (i in 0..4) put(i, 65)
            for (i in 10..20) put(i, 45)
        }
        val gapRoute = buildStraightNorthRoute(pointCount = 21, stepMeters = 25.0, speedLimits = gapLimits)
        val drop = SpeedLimitChangeAhead.findNextDrop(gapRoute, snappedShapeIndex = 0, currentSpeedLimitMph = 65)
        assertNotNull(drop)
        assertEquals(45, drop!!.newLimitMph)
        assertEquals(10, drop.changeShapeIndex)

        // Voice tracker fires once in DRIVE, never repeats, and stays silent in WALK/BIKE
        val tracker = SpeedLimitDropVoiceTracker()
        assertNull(tracker.checkVoiceTrigger(drop, speedMps = 25f, travelMode = TravelMode.WALK, enabled = true))
        assertNull(tracker.checkVoiceTrigger(drop, speedMps = 25f, travelMode = TravelMode.DRIVE, enabled = false))
        assertNotNull(tracker.checkVoiceTrigger(drop, speedMps = 35f, travelMode = TravelMode.DRIVE, enabled = true))
        assertNull(tracker.checkVoiceTrigger(drop, speedMps = 35f, travelMode = TravelMode.DRIVE, enabled = true))
    }

    // -------------------------------------------------------------------------
    // PK: ParkingNearDestinationFinder 400 m limit, sorting & cap at 5
    // -------------------------------------------------------------------------

    @Test
    fun parkingFinder_filters400m_sortsByWalkingDistance_capsAtFive() {
        val destLat = 44.0521
        val destLon = -123.0868
        val metersToDegLat = 1.0 / 111_320.0

        val candidates = listOf(
            makeParkingPlace("p_far", "Far Garage", "parking", destLat + 520.0 * metersToDegLat, destLon),
            makeParkingPlace("p_350", "South Surface Lot", "parking", destLat - 350.0 * metersToDegLat, destLon),
            makeParkingPlace("p_080", "Downtown Parking Garage", "parking", destLat + 80.0 * metersToDegLat, destLon),
            makeParkingPlace("p_220", "Street Meter Parking", "parking", destLat + 220.0 * metersToDegLat, destLon),
            makeParkingPlace("p_150", "Oak Street Lot", "parking", destLat - 150.0 * metersToDegLat, destLon),
            makeParkingPlace("p_300", "City Deck", "parking", destLat + 300.0 * metersToDegLat, destLon),
            makeParkingPlace("p_390", "Edge Parking Lot", "parking", destLat - 390.0 * metersToDegLat, destLon)
        )

        val ranked = ParkingNearDestinationFinder.rankAndFilterCandidates(destLat, destLon, candidates)
        assertEquals(5, ranked.size)
        assertTrue(ranked.none { it.id == "p_far" })
        assertTrue(ranked.none { it.id == "p_390" })
        assertEquals(listOf("p_080", "p_150", "p_220", "p_300", "p_350"), ranked.map { it.id })

        assertEquals(ParkingSpotType.GARAGE, ranked[0].type)
        assertEquals(ParkingSpotType.LOT, ranked[1].type)
        assertEquals(ParkingSpotType.STREET, ranked[2].type)
        assertEquals(ParkingSpotType.GARAGE, ranked[3].type)
        assertEquals(ranked[0].straightLineMeters * 1.25, ranked[0].walkDistanceMeters, 1e-4)
        assertTrue(ranked.all { it.walkMinutes >= 1 })
    }

    // -------------------------------------------------------------------------
    // VC: VoiceCommandParser all commands + unknown text
    // -------------------------------------------------------------------------

    @Test
    fun voiceCommandParser_recognizesAllSupportedCommandsAndUnknown() {
        assertEquals(VoiceCommand.NavigateTo("costco"), VoiceCommandParser.parse("Navigate to Costco"))
        assertEquals(VoiceCommand.NavigateTo("trader joe's"), VoiceCommandParser.parse("take me to Trader Joe’s!"))
        assertEquals(VoiceCommand.NavigateTo("eugene airport"), VoiceCommandParser.parse("Go to Eugene Airport."))

        assertEquals(VoiceCommand.GoHome, VoiceCommandParser.parse("Go home"))
        assertEquals(VoiceCommand.GoHome, VoiceCommandParser.parse("take me home"))
        assertEquals(VoiceCommand.GoWork, VoiceCommandParser.parse("Go to work"))
        assertEquals(VoiceCommand.GoWork, VoiceCommandParser.parse("navigate to work"))

        assertEquals(VoiceCommand.FindNearest("gas station"), VoiceCommandParser.parse("Find nearest gas station"))
        assertEquals(VoiceCommand.FindNearest("coffee shop"), VoiceCommandParser.parse("Find the nearest coffee shop"))
        assertEquals(VoiceCommand.FindNearest("pharmacy"), VoiceCommandParser.parse("nearest pharmacy"))

        assertEquals(VoiceCommand.WhereIsMyCar, VoiceCommandParser.parse("Where is my car?"))
        assertEquals(VoiceCommand.WhereIsMyCar, VoiceCommandParser.parse("Where's my car"))
        assertEquals(VoiceCommand.WalkToMyCar, VoiceCommandParser.parse("Walk to my car"))
        assertEquals(VoiceCommand.WalkToMyCar, VoiceCommandParser.parse("take me to my car"))

        assertEquals(VoiceCommand.StopNavigation, VoiceCommandParser.parse("Stop navigation"))
        assertEquals(VoiceCommand.StopNavigation, VoiceCommandParser.parse("Cancel route"))
        assertEquals(VoiceCommand.Mute, VoiceCommandParser.parse("Mute"))
        assertEquals(VoiceCommand.Mute, VoiceCommandParser.parse("mute voice"))
        assertEquals(VoiceCommand.Unmute, VoiceCommandParser.parse("Unmute"))
        assertEquals(VoiceCommand.AskEta, VoiceCommandParser.parse("When will I arrive?"))
        assertEquals(VoiceCommand.AskEta, VoiceCommandParser.parse("What's my ETA?"))
        assertEquals(VoiceCommand.RepeatInstruction, VoiceCommandParser.parse("Repeat"))
        assertEquals(VoiceCommand.RepeatInstruction, VoiceCommandParser.parse("say that again"))
        assertEquals(VoiceCommand.ReportRoadClosed, VoiceCommandParser.parse("Report road closed"))
        assertEquals(VoiceCommand.ZoomIn, VoiceCommandParser.parse("Zoom in"))
        assertEquals(VoiceCommand.ZoomOut, VoiceCommandParser.parse("Zoom out"))

        assertTrue(VoiceCommandParser.parse("play some jazz music") is VoiceCommand.Unknown)
        assertTrue(VoiceCommandParser.parse("   ") is VoiceCommand.Unknown)
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private fun buildStraightNorthRoute(
        pointCount: Int,
        stepMeters: Double,
        maneuverShapeIndex: Int = pointCount - 2,
        lanes: List<LaneInfo> = emptyList(),
        oneWayByShapeIndex: Map<Int, Boolean> = emptyMap(),
        speedLimits: Map<Int, Int> = emptyMap()
    ): OfflineRoute {
        val baseLat = 44.0500
        val baseLon = -123.0868
        val stepDegLat = stepMeters / 111_320.0
        val shape = List(pointCount) { i -> LatLon(baseLat + i * stepDegLat, baseLon) }
        val pivotIdx = maneuverShapeIndex.coerceIn(0, shape.lastIndex)
        val maneuvers = if (lanes.isNotEmpty()) {
            listOf(
                RouteManeuver(
                    index = 0,
                    type = ManeuverType.EXIT_RIGHT,
                    instructionEn = "Take exit",
                    streetNames = listOf("I-5"),
                    lengthMeters = pointCount * stepMeters,
                    timeSeconds = pointCount * 2.0,
                    beginShapeIndex = pivotIdx,
                    endShapeIndex = shape.lastIndex,
                    lat = shape[pivotIdx].lat,
                    lon = shape[pivotIdx].lon,
                    lanes = lanes
                )
            )
        } else {
            emptyList()
        }
        return OfflineRoute(
            id = "test_route",
            summaryLabel = "Test Route",
            distanceMeters = (pointCount - 1) * stepMeters,
            durationSeconds = (pointCount - 1) * 2.0,
            shape = shape,
            maneuvers = maneuvers,
            speedLimitsByShapeIndex = speedLimits,
            oneWayByShapeIndex = oneWayByShapeIndex
        )
    }

    private fun makeParkingPlace(
        id: String,
        name: String,
        category: String,
        lat: Double,
        lon: Double
    ): PlaceSearchResult = PlaceSearchResult(
        id = id,
        name = name,
        brand = "",
        category = category,
        address = "",
        city = "Eugene",
        stateCode = "OR",
        postcode = "97401",
        lat = lat,
        lon = lon,
        distanceMeters = 0.0,
        relevanceScore = 100.0,
        source = "offline_db"
    )
}
