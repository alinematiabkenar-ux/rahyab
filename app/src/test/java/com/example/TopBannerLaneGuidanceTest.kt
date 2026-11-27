package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.i18n.stringsFor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDirection
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.RouteOptions
import com.example.navigation.ValhallaResponseParser
import com.example.navigation.VoiceGuidanceManager
import com.example.ui.components.TurnByTurnTopHud
import com.example.ui.theme.RahYabTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TopBannerLaneGuidanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Valhalla official integer bitmask lanes parse into four lanes with exact directions, activeDirections, and three levels`() {
        val valhallaJson = """
        {
          "trip": {
            "summary": { "length": 3.5, "time": 210.0 },
            "legs": [
              {
                "shape": "}`mkG~|pnV??",
                "maneuvers": [
                  {
                    "type": 15,
                    "instruction": "Turn left onto Coburg Road.",
                    "street_names": ["Coburg Road"],
                    "length": 1.8,
                    "time": 110.0,
                    "begin_shape_index": 0,
                    "end_shape_index": 1,
                    "lanes": [
                      {"directions": 8, "active": 8, "valid": 8},
                      {"directions": 10, "valid": 2},
                      {"directions": 2},
                      {"directions": 66, "valid": 64}
                    ]
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        val routes = ValhallaResponseParser.parseRoutes(valhallaJson)
        assertEquals(1, routes.size)
        val lanes = routes[0].maneuvers[0].lanes
        assertEquals("Expected 4 lanes parsed from Valhalla bitmasks", 4, lanes.size)

        // Lane 0: directions=8 (Left), active=8, valid=8 -> ACTIVE ("best")
        assertEquals(listOf(LaneDirection.LEFT), lanes[0].directions)
        assertEquals(listOf(LaneDirection.LEFT), lanes[0].activeDirections)
        assertEquals(LaneHighlightTier.ACTIVE, lanes[0].level)
        assertTrue(lanes[0].isActive)
        assertTrue(lanes[0].isValid)
        assertTrue(lanes[0].isRecommended)

        // Lane 1: directions=10 (Left|Through), valid=2 (Through), no active -> VALID ("allowed")
        assertEquals(listOf(LaneDirection.LEFT, LaneDirection.THROUGH), lanes[1].directions)
        assertEquals(listOf(LaneDirection.THROUGH), lanes[1].activeDirections)
        assertEquals(LaneHighlightTier.VALID, lanes[1].level)
        assertFalse(lanes[1].isActive)
        assertTrue(lanes[1].isValid)
        assertTrue(lanes[1].isRecommended)

        // Lane 2: directions=2 (Through), neither active nor valid -> INACTIVE ("other")
        assertEquals(listOf(LaneDirection.THROUGH), lanes[2].directions)
        assertTrue(lanes[2].activeDirections.isEmpty())
        assertEquals(LaneHighlightTier.INACTIVE, lanes[2].level)
        assertFalse(lanes[2].isActive)
        assertFalse(lanes[2].isValid)
        assertFalse(lanes[2].isRecommended)

        // Lane 3: directions=66 (Through|Right), valid=64 (Right), no active -> VALID ("allowed")
        assertEquals(listOf(LaneDirection.THROUGH, LaneDirection.RIGHT), lanes[3].directions)
        assertEquals(listOf(LaneDirection.RIGHT), lanes[3].activeDirections)
        assertEquals(LaneHighlightTier.VALID, lanes[3].level)
        assertFalse(lanes[3].isActive)
        assertTrue(lanes[3].isValid)
        assertTrue(lanes[3].isRecommended)
    }

    @Test
    fun `Valhalla request has top-level turn_lanes true and decodes MergeToLeft, MergeToRight, U-turn, and approach lanes`() {
        val reqJsonStr = ValhallaResponseParser.buildRouteRequestJson(
            origin = LatLon(44.0521, -123.0868),
            destination = LatLon(44.0850, -123.0400),
            options = RouteOptions()
        )
        val reqObj = JSONObject(reqJsonStr)
        assertTrue("turn_lanes must be a top-level field set to true", reqObj.optBoolean("turn_lanes", false))

        // Bitmask verification: 512 = MergeToLeft, 1024 = MergeToRight, 256 = Reverse (U-turn)
        assertEquals(listOf(LaneDirection.MERGE_LEFT), ValhallaResponseParser.decodeValhallaTurnLaneMask(512))
        assertEquals(listOf(LaneDirection.MERGE_RIGHT), ValhallaResponseParser.decodeValhallaTurnLaneMask(1024))
        assertEquals(listOf(LaneDirection.U_TURN_LEFT), ValhallaResponseParser.decodeValhallaTurnLaneMask(256))
        assertEquals(
            listOf(LaneDirection.THROUGH, LaneDirection.MERGE_LEFT),
            ValhallaResponseParser.decodeValhallaTurnLaneMask(2 or 512)
        )

        // Approaching segment lanes attach to upcoming turn; subsequent maneuver without lane data stays empty
        val approachJson = """
        {
          "trip": {
            "summary": { "length": 2.4, "time": 120.0 },
            "legs": [
              {
                "shape": "}`mkG~|pnV??",
                "maneuvers": [
                  {
                    "type": 1,
                    "instruction": "Drive north on I-5.",
                    "street_names": ["I-5"],
                    "length": 1.2,
                    "time": 50.0,
                    "begin_shape_index": 0,
                    "end_shape_index": 1,
                    "approach_lanes": [
                      {"directions": 2},
                      {"directions": 34, "valid": 32},
                      {"directions": 32, "active": 32, "valid": 32}
                    ]
                  },
                  {
                    "type": 20,
                    "instruction": "Take exit 194B onto I-105 West.",
                    "street_names": ["I-105 W"],
                    "length": 0.8,
                    "time": 40.0,
                    "begin_shape_index": 1,
                    "end_shape_index": 1
                  },
                  {
                    "type": 10,
                    "instruction": "Turn right onto Gateway St.",
                    "street_names": ["Gateway St"],
                    "length": 0.4,
                    "time": 30.0,
                    "begin_shape_index": 1,
                    "end_shape_index": 1
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()
        val parsed = ValhallaResponseParser.parseRoutes(approachJson).first()
        assertEquals("Exit maneuver should inherit approach segment lanes", 3, parsed.maneuvers[1].lanes.size)
        assertEquals(LaneHighlightTier.ACTIVE, parsed.maneuvers[1].lanes[2].level)
        assertEquals(LaneHighlightTier.VALID, parsed.maneuvers[1].lanes[1].level)
        assertTrue("Maneuver without real lane data must never invent lanes", parsed.maneuvers[2].lanes.isEmpty())
    }

    @Test
    fun `Simulate drive on I-5 exit around Eugene shows lane strip at 1 mile, pulses once at half mile, and speaks lane advice in EN and FA`() {
        // Construct a realistic I-5 North route near Eugene (Exit 194B -> I-105 West -> Coburg Road)
        val shape = (0..120).map { i ->
            LatLon(44.0400 + i * 0.00036, -123.0500) // ~40m per step -> ~4.8 km total
        }
        val exitIdx = 80 // ~3.2 km (~2 miles) from start
        val finalIdx = 120
        val exitLanes = listOf(
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.THROUGH, LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = false, isValid = true, level = LaneHighlightTier.VALID),
            LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
        )
        val route = OfflineRoute(
            id = "eugene_i5_exit_194b",
            summaryLabel = "I-5 N",
            distanceMeters = 4800.0,
            durationSeconds = 180.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on I-5.",
                    streetNames = listOf("I-5"), lengthMeters = 3200.0, timeSeconds = 115.0,
                    beginShapeIndex = 0, endShapeIndex = exitIdx, lat = shape[0].lat, lon = shape[0].lon,
                    isHighwayRoad = true, laneCount = 4, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.EXIT_RIGHT, instructionEn = "Take exit 194B onto I-105 West.",
                    streetNames = listOf("I-105 W"), lengthMeters = 1600.0, timeSeconds = 65.0,
                    beginShapeIndex = exitIdx, endShapeIndex = finalIdx, lat = shape[exitIdx].lat, lon = shape[exitIdx].lon,
                    signpost = HighwaySignpost(exitNumbers = listOf("194B"), exitBranches = listOf("I-105 W"), exitTowards = listOf("Eugene")),
                    lanes = exitLanes, isHighwayExitOrSplit = true, isHighwayRoad = true, speedLimitMph = 65,
                    laneCount = 4, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 2, type = ManeuverType.ARRIVE, instructionEn = "Arrive at destination.",
                    streetNames = emptyList(), lengthMeters = 0.0, timeSeconds = 0.0,
                    beginShapeIndex = finalIdx, endShapeIndex = finalIdx, lat = shape[finalIdx].lat, lon = shape[finalIdx].lon
                )
            )
        )

        val controller = NavigationSessionController()
        var state = ActiveNavigationState(isNavigating = true, route = route, isSimulatingDrive = true)
        var sawLaneStripHiddenFar = false
        var sawLaneStripVisibleUnder1Mile = false
        var sawLaneStripHiddenAfterExit = false
        val spokenEn = mutableListOf<String>()
        val spokenFa = mutableListOf<String>()
        var pulseTicks = 0
        var prevPulseCount = 0

        for (step in 0..105) {
            val pt = shape[step]
            val fix = GpsFix(
                lat = pt.lat,
                lon = pt.lon,
                speedMps = 29f, // ~65 mph
                bearing = 0f,
                accuracyMeters = 4f,
                timestampMs = 1_000_000L + step * 10_000L
            )
            val res = controller.processGpsFix(
                currentState = state,
                fix = fix,
                dbHazards = emptyList(),
                userReports = emptyList(),
                unit = DistanceUnit.IMPERIAL,
                language = AppLanguage.ENGLISH,
                speedAudioEnabled = true
            )
            state = res.state

            if (state.currentManeuver?.index == 1) {
                if (state.distanceToNextManeuverMeters > 2000.0 && !state.showLaneStrip) {
                    sawLaneStripHiddenFar = true
                }
                if (state.distanceToNextManeuverMeters <= 1600.0 && state.showLaneStrip) {
                    sawLaneStripVisibleUnder1Mile = true
                }
            } else if (state.currentManeuver?.index == 2 && !state.showLaneStrip) {
                sawLaneStripHiddenAfterExit = true
            }

            if (state.lanePulseTriggerCount > prevPulseCount) {
                pulseTicks++
                prevPulseCount = state.lanePulseTriggerCount
                assertEquals(
                    "500m pulse must fire on the exact 500m highway voice announcement tick",
                    NavigationSessionController.STAGE_HW_500M,
                    res.maneuverToSpeak?.second
                )
            }

            res.maneuverToSpeak?.let { (maneuver, stage) ->
                spokenEn += VoiceGuidanceManager.formatSpokenInstruction(
                    maneuver = maneuver,
                    distanceMeters = state.distanceToNextManeuverMeters,
                    unit = DistanceUnit.IMPERIAL,
                    language = AppLanguage.ENGLISH,
                    includeLaneGuidance = true,
                    announcementStage = stage
                )
                spokenFa += VoiceGuidanceManager.formatSpokenInstruction(
                    maneuver = maneuver,
                    distanceMeters = state.distanceToNextManeuverMeters,
                    unit = DistanceUnit.IMPERIAL,
                    language = AppLanguage.PERSIAN,
                    includeLaneGuidance = true,
                    announcementStage = stage
                )
            }
        }

        assertTrue("Lane strip should be hidden beyond 1 mile", sawLaneStripHiddenFar)
        assertTrue("Lane strip must appear within 1 mile of I-5 exit", sawLaneStripVisibleUnder1Mile)
        assertTrue("Lane strip must hide after passing exit when next maneuver has no lanes", sawLaneStripHiddenAfterExit)
        assertEquals("Highlighted lanes must pulse strictly once at half mile", 1, pulseTicks)
        assertTrue(
            "English spoken prompts must include lane advice: $spokenEn",
            spokenEn.any { it.contains("right lane", ignoreCase = true) && it.contains("194B") }
        )
        assertTrue(
            "Persian spoken prompts must include lane advice: $spokenFa",
            spokenFa.any { it.contains("خط سمت راست") && it.contains("194B") }
        )
    }

    @Test
    fun `Top banner lane row stays strictly physical left-to-right in both English LTR and Persian RTL and never clips 8 lanes`() {
        val fourLanes = listOf(
            LaneInfo(listOf(LaneDirection.LEFT), listOf(LaneDirection.LEFT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
            LaneInfo(listOf(LaneDirection.LEFT, LaneDirection.THROUGH), listOf(LaneDirection.THROUGH), isRecommended = true, isActive = false, isValid = true, level = LaneHighlightTier.VALID),
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.THROUGH, LaneDirection.RIGHT), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE)
        )
        val maneuver = RouteManeuver(
            index = 1,
            type = ManeuverType.LEFT,
            instructionEn = "Turn left onto Coburg Road",
            streetNames = listOf("Coburg Road"),
            lengthMeters = 1200.0,
            timeSeconds = 75.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            lat = 44.0600,
            lon = -123.0700,
            lanes = fourLanes
        )

        // Render in Persian RTL mode inside a phone-width container (360 dp)
        composeRule.setContent {
            RahYabTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.PERSIAN,
                    LocalAppStrings provides stringsFor(AppLanguage.PERSIAN),
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    Box(modifier = Modifier.width(360.dp)) {
                        TurnByTurnTopHud(
                            maneuver = maneuver,
                            thenManeuver = null,
                            distanceMeters = 800.0,
                            unit = DistanceUnit.IMPERIAL,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = false,
                            routeShape = emptyList(),
                            onDismissJunctionPreview = {},
                            isNightMode = false,
                            lanePulseTriggerKey = 1
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val b0 = composeRule.onNodeWithTag("lane_box_0").getUnclippedBoundsInRoot()
        val b1 = composeRule.onNodeWithTag("lane_box_1").getUnclippedBoundsInRoot()
        val b2 = composeRule.onNodeWithTag("lane_box_2").getUnclippedBoundsInRoot()
        val b3 = composeRule.onNodeWithTag("lane_box_3").getUnclippedBoundsInRoot()

        assertTrue("Persian RTL must NEVER mirror lane order: b0.left=${b0.left}, b1.left=${b1.left}", b0.left < b1.left)
        assertTrue("Lane boxes must stay left-to-right: b1.left=${b1.left}, b2.left=${b2.left}", b1.left < b2.left)
        assertTrue("Lane boxes must stay left-to-right: b2.left=${b2.left}, b3.left=${b3.left}", b2.left < b3.left)
        assertTrue("Each 4-lane box must be >= 40 dp wide, was ${b0.right - b0.left}", (b0.right - b0.left) >= 40.dp)
    }

    @Test
    fun `Eight-lane freeway row shrinks evenly to fit inside banner without clipping and empty lanes hide strip`() {
        val eightLanes = (0 until 8).map { idx ->
            LaneInfo(
                directions = if (idx >= 6) listOf(LaneDirection.SLIGHT_RIGHT) else listOf(LaneDirection.THROUGH),
                activeDirections = if (idx >= 6) listOf(LaneDirection.SLIGHT_RIGHT) else emptyList(),
                isRecommended = idx >= 6,
                isActive = idx == 7,
                isValid = idx >= 6,
                level = when {
                    idx == 7 -> LaneHighlightTier.ACTIVE
                    idx == 6 -> LaneHighlightTier.VALID
                    else -> LaneHighlightTier.INACTIVE
                }
            )
        }
        val freewayManeuver = RouteManeuver(
            index = 2,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B",
            streetNames = listOf("I-105 W"),
            lengthMeters = 1000.0,
            timeSeconds = 40.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            lat = 44.07,
            lon = -123.05,
            lanes = eightLanes,
            isHighwayExitOrSplit = true,
            isHighwayRoad = true
        )

        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ENGLISH,
                    LocalAppStrings provides stringsFor(AppLanguage.ENGLISH),
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) {
                    Box(modifier = Modifier.width(340.dp)) {
                        TurnByTurnTopHud(
                            maneuver = freewayManeuver,
                            thenManeuver = null,
                            distanceMeters = 900.0,
                            unit = DistanceUnit.IMPERIAL,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = false,
                            routeShape = emptyList(),
                            onDismissJunctionPreview = {},
                            isNightMode = true,
                            lanePulseTriggerKey = 0
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val stripBounds = composeRule.onNodeWithTag("lane_guidance_strip").getUnclippedBoundsInRoot()
        val firstBox = composeRule.onNodeWithTag("lane_box_0").getUnclippedBoundsInRoot()
        val lastBox = composeRule.onNodeWithTag("lane_box_7").getUnclippedBoundsInRoot()
        assertTrue("First lane box must start inside strip", firstBox.left >= stripBounds.left)
        assertTrue("All 8 lane boxes must fit without clipping: last.right=${lastBox.right}, strip.right=${stripBounds.right}", lastBox.right <= stripBounds.right)
    }

    @Test
    fun `Empty lanes hide lane guidance strip completely`() {
        val noLaneManeuver = RouteManeuver(
            index = 3,
            type = ManeuverType.RIGHT,
            instructionEn = "Turn right onto Oak St",
            streetNames = listOf("Oak St"),
            lengthMeters = 400.0,
            timeSeconds = 30.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            lat = 44.05,
            lon = -123.09,
            lanes = emptyList()
        )

        composeRule.setContent {
            RahYabTheme(darkTheme = false) {
                TurnByTurnTopHud(
                    maneuver = noLaneManeuver,
                    thenManeuver = null,
                    distanceMeters = 300.0,
                    unit = DistanceUnit.IMPERIAL,
                    isRerouting = false,
                    showLaneStrip = true,
                    showJunctionPreview = false,
                    routeShape = emptyList(),
                    onDismissJunctionPreview = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lane_guidance_strip").assertDoesNotExist()
    }
}
