package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import com.example.map.LaneHighlightColor
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDemoScenarios
import com.example.navigation.LaneDirection
import com.example.navigation.LaneDisplaySurface
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LaneSource
import com.example.navigation.ManeuverType
import com.example.navigation.RouteManeuver
import com.example.navigation.ValhallaResponseParser
import com.example.ui.components.FullLaneDiagramOverlay
import com.example.ui.components.TurnByTurnTopHud
import com.example.ui.theme.RahYabTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullLaneDiagramOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fourLaneSecondFromRight_showsAllFourLanesWithDividers1Point5xTargetArrowAndPositionText() {
        val maneuver = LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1]
        val decision = LaneGuidanceDecision.evaluate(maneuver, edgeLaneCountBeforeManeuver = 4)

        assertEquals(LaneConfidence.HIGH, decision.confidence)
        assertEquals(listOf(2), decision.preferredIndices)
        assertEquals("Second lane from the right.", decision.positionTextEn)
        assertEquals(
            "Get in the blue lane.",
            decision.resolveVoiceCue(
                surface = LaneDisplaySurface.PHONE_SCREEN,
                isDiagramVisibleOnActiveDisplay = true,
                highlightColor = LaneHighlightColor.BLUE,
                isCarHostColorControlled = false,
                language = AppLanguage.ENGLISH
            )
        )
        assertEquals(
            "Use the second lane from the right.",
            decision.resolveVoiceCue(
                surface = LaneDisplaySurface.SCREEN_OFF,
                isDiagramVisibleOnActiveDisplay = false,
                highlightColor = LaneHighlightColor.BLUE,
                isCarHostColorControlled = false,
                language = AppLanguage.ENGLISH
            )
        )

        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ENGLISH,
                    LocalAppStrings provides stringsFor(AppLanguage.ENGLISH)
                ) {
                    Box(modifier = Modifier.width(360.dp)) {
                        FullLaneDiagramOverlay(
                            decision = decision,
                            isNightMode = true,
                            highlightColor = LaneHighlightColor.BLUE
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("full_lane_diagram_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("full_lane_position_text")
            .assertIsDisplayed()
            .assertTextEquals("Second lane from the right.")

        // Verify all 4 lanes exist in strict physical LTR order without overlap + 3 dividers
        val boxes = (0..3).map { composeRule.onNodeWithTag("lane_box_$it").getUnclippedBoundsInRoot() }
        for (i in 0..2) {
            assertTrue("Lane $i must stay strictly left of lane ${i + 1}", boxes[i].right <= boxes[i + 1].left)
            composeRule.onNodeWithTag("full_lane_divider_${i + 1}").assertIsDisplayed()
        }

        // Target arrow (lane 2) must be ~1.5x the height of neighboring inactive arrows (lane 1)
        val neighborArrow = composeRule.onNodeWithTag("full_lane_arrow_1").getUnclippedBoundsInRoot()
        val targetArrow = composeRule.onNodeWithTag("full_lane_arrow_2").getUnclippedBoundsInRoot()
        val ratio = (targetArrow.bottom - targetArrow.top).value / (neighborArrow.bottom - neighborArrow.top).value
        assertTrue("Expected target arrow ~1.5x neighbor height, got $ratio", ratio in 1.42f..1.58f)
        // Target arrow must remain strictly within its own lane box bounds
        assertTrue(targetArrow.left >= boxes[2].left && targetArrow.right <= boxes[2].right)
    }

    @Test
    fun persianRtlAndTwoRightLanes_preservesPhysicalLtrOrderAndExpectedPhrasing() {
        val maneuver = RouteManeuver(
            index = 1,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B",
            streetNames = listOf("I-105 W"),
            lengthMeters = 900.0,
            timeSeconds = 40.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            lat = 44.06,
            lon = -123.05,
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false),
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false),
                LaneInfo(listOf(LaneDirection.THROUGH, LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true),
                LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true)
            ),
            laneSource = LaneSource.DIRECT,
            isHighwayExitOrSplit = true
        )
        val decision = LaneGuidanceDecision.evaluate(maneuver)
        assertEquals("Use either of the two right lanes.", decision.positionTextEn)
        assertEquals("از هر یک از دو خط سمت راست استفاده کنید.", decision.positionTextFa)

        composeRule.setContent {
            RahYabTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.PERSIAN,
                    LocalAppStrings provides stringsFor(AppLanguage.PERSIAN),
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    Box(modifier = Modifier.width(360.dp)) {
                        FullLaneDiagramOverlay(decision = decision, isNightMode = false)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val b0 = composeRule.onNodeWithTag("lane_box_0").getUnclippedBoundsInRoot()
        val b3 = composeRule.onNodeWithTag("lane_box_3").getUnclippedBoundsInRoot()
        assertTrue("Persian RTL must keep physical LTR order", b0.left < b3.left)
        composeRule.onNodeWithTag("full_lane_position_text")
            .assertIsDisplayed()
            .assertTextEquals("از هر یک از دو خط سمت راست استفاده کنید.")
    }

    @Test
    fun junctionViewEnabledOnSmallScreen_keepsFullLaneDiagramVisibleWithoutClipping() {
        val maneuver = LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1].copy(
            signpost = HighwaySignpost(exitNumbers = listOf("194B"), exitBranches = listOf("I-105 W"), exitTowards = listOf("Eugene"))
        )
        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ENGLISH,
                    LocalAppStrings provides stringsFor(AppLanguage.ENGLISH)
                ) {
                    Box(modifier = Modifier.width(320.dp)) {
                        TurnByTurnTopHud(
                            maneuver = maneuver,
                            thenManeuver = null,
                            distanceMeters = 600.0,
                            unit = DistanceUnit.IMPERIAL,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = true,
                            routeShape = emptyList(),
                            onDismissJunctionPreview = {},
                            isNightMode = true
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val overlay = composeRule.onNodeWithTag("full_lane_diagram_overlay").getUnclippedBoundsInRoot()
        val lastBox = composeRule.onNodeWithTag("lane_box_3").getUnclippedBoundsInRoot()
        assertTrue("Last lane box must fit inside 320dp overlay", lastBox.right <= overlay.right)
        composeRule.onNodeWithTag("full_lane_position_text").assertIsDisplayed()
    }

    @Test
    fun unmarkedValhallaSlotPreservesPhysicalIndexAndAllSuitableLanesSkipOverlay() {
        val json = """
        {
          "trip": {
            "summary": { "length": 1.0, "time": 60.0 },
            "legs": [{
              "shape": "}`mkG~|pnV??",
              "maneuvers": [{
                "type": 10,
                "instruction": "Turn right.",
                "street_names": ["Main St"],
                "length": 0.5,
                "time": 30.0,
                "begin_shape_index": 0,
                "end_shape_index": 1,
                "lanes": [
                  {"directions": 8},
                  {"directions": 0},
                  {"directions": 64, "active": 64, "valid": 64}
                ]
              }]
            }]
          }
        }
        """.trimIndent()
        val parsed = ValhallaResponseParser.parseRoutes(json).first()
        val lanes = parsed.maneuvers[0].lanes
        assertEquals("Unmarked middle slot must not be dropped", 3, lanes.size)
        assertTrue(lanes[1].isUnmarkedSlot)
        assertTrue(lanes[2].isActive)

        val allValidManeuver = parsed.maneuvers[0].copy(
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.THROUGH), listOf(LaneDirection.THROUGH), isRecommended = true, isActive = true, isValid = true),
                LaneInfo(listOf(LaneDirection.THROUGH), listOf(LaneDirection.THROUGH), isRecommended = true, isActive = true, isValid = true)
            )
        )
        val allValidDecision = LaneGuidanceDecision.evaluate(allValidManeuver)
        assertTrue(allValidDecision.shouldSkipLaneChangeInstruction)
        assertNull(
            allValidDecision.resolveVoiceCue(
                surface = LaneDisplaySurface.PHONE_SCREEN,
                isDiagramVisibleOnActiveDisplay = true,
                highlightColor = LaneHighlightColor.BLUE,
                isCarHostColorControlled = false,
                language = AppLanguage.ENGLISH
            )
        )
    }
}
