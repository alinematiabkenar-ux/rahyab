package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
import com.example.navigation.LaneDirection
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LaneSource
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.RouteManeuver
import com.example.ui.components.TurnByTurnTopHud
import com.example.ui.theme.RahYabTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Robolectric Compose UI screenshot tests rendering the actual TurnByTurnTopHud + dedicated
 * blue-lane panel (with junction preview visible) in English LTR and Persian RTL to PNG files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntegratedGuidanceScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun saveRootScreenshot(fileName: String): Bitmap {
        composeRule.waitForIdle()
        val bounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val decor = composeRule.activity.window.decorView
        val fullW = decor.width.coerceAtLeast(1)
        val fullH = decor.height.coerceAtLeast(1)
        val fullBmp = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        composeRule.runOnUiThread {
            decor.draw(Canvas(fullBmp))
        }
        val left = floor(bounds.left).toInt().coerceIn(0, fullW - 1)
        val top = floor(bounds.top).toInt().coerceIn(0, fullH - 1)
        val width = ceil(bounds.width).toInt().coerceIn(1, fullW - left)
        val height = ceil(bounds.height).toInt().coerceIn(1, fullH - top)
        val cropped = Bitmap.createBitmap(fullBmp, left, top, width, height)
        val dir = File("build/outputs/regression_screenshots").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("Screenshot $fileName must exist and be non-empty", file.exists() && file.length() > 1000L)
        println("GENERATED_COMPOSE_SCREENSHOT=${file.path} (${cropped.width}x${cropped.height}, ${file.length()} bytes)")
        return cropped
    }

    private fun countBrightBluePixels(bmp: Bitmap): Int {
        var count = 0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val px = bmp.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (b > 170 && b > r + 60) count++
            }
        }
        return count
    }

    @Test
    fun renderHighway500mExitHudWithBlueLanePanelAndJunctionPreview_EnglishLtr() {
        val lanes = listOf(
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
            LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
        )
        val exitManeuver = RouteManeuver(
            index = 1,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take Exit 195 on the right toward Beltline Hwy",
            streetNames = listOf("Beltline Hwy"),
            lengthMeters = 350.0,
            timeSeconds = 20.0,
            beginShapeIndex = 1,
            endShapeIndex = 2,
            lat = 44.086,
            lon = -123.090,
            signpost = HighwaySignpost(
                exitNumbers = listOf("195"),
                exitBranches = listOf("OR-569"),
                exitTowards = listOf("Beltline Hwy / Eugene")
            ),
            lanes = lanes,
            laneSource = LaneSource.DIRECT,
            isHighwayRoad = true,
            isHighwayExitOrSplit = true,
            laneCount = 4,
            isMultiLaneRoad = true,
            hasRoadLaneGeometry = true
        )
        val decision = LaneGuidanceDecision.evaluate(exitManeuver, edgeLaneCountBeforeManeuver = 4)
        val shape = listOf(LatLon(44.080, -123.090), LatLon(44.086, -123.090), LatLon(44.089, -123.087))

        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ENGLISH,
                    LocalAppStrings provides stringsFor(AppLanguage.ENGLISH),
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) {
                    Box(
                        modifier = Modifier
                            .width(390.dp)
                            .background(Color(0xFF0E1722))
                            .padding(12.dp)
                    ) {
                        TurnByTurnTopHud(
                            maneuver = exitManeuver,
                            thenManeuver = null,
                            distanceMeters = 500.0,
                            unit = DistanceUnit.METRIC,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = true,
                            routeShape = shape,
                            onDismissJunctionPreview = {},
                            isNightMode = true,
                            laneConfidence = LaneConfidence.HIGH,
                            laneSideHint = decision.suggestedSide,
                            laneDecision = decision,
                            laneHighlightColor = LaneHighlightColor.BLUE,
                            turnFocusActive = true
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("dedicated_lane_panel").assertExists()
        composeRule.onNodeWithTag("lane_box_2").assertExists()
        composeRule.onNodeWithTag("lane_box_3").assertExists()
        val bmp = saveRootScreenshot("highway_exit_195_500m_en_ltr.png")
        val bluePixels = countBrightBluePixels(bmp)
        assertTrue("Highway 500m HUD screenshot must render bright blue lane pixels (found $bluePixels)", bluePixels > 500)
    }

    @Test
    fun renderCityRightTurnHudWithBlueLanePanel_PersianRtlPreservesPhysicalLtr() {
        val lanes = listOf(
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = true, level = LaneHighlightTier.VALID),
            LaneInfo(listOf(LaneDirection.THROUGH, LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
        )
        val turnManeuver = RouteManeuver(
            index = 1,
            type = ManeuverType.RIGHT,
            instructionEn = "Turn right onto Oak Street",
            streetNames = listOf("Oak Street"),
            lengthMeters = 150.0,
            timeSeconds = 18.0,
            beginShapeIndex = 1,
            endShapeIndex = 2,
            lat = 44.063,
            lon = -123.090,
            lanes = lanes,
            laneSource = LaneSource.DIRECT,
            laneCount = 2,
            isMultiLaneRoad = true,
            hasRoadLaneGeometry = true
        )
        val decision = LaneGuidanceDecision.evaluate(turnManeuver, edgeLaneCountBeforeManeuver = 2)
        val shape = listOf(LatLon(44.055, -123.090), LatLon(44.063, -123.090), LatLon(44.063, -123.085))

        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.PERSIAN,
                    LocalAppStrings provides stringsFor(AppLanguage.PERSIAN),
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    Column(
                        modifier = Modifier
                            .width(390.dp)
                            .background(Color(0xFF0E1722))
                            .padding(12.dp)
                            .fillMaxWidth()
                    ) {
                        TurnByTurnTopHud(
                            maneuver = turnManeuver,
                            thenManeuver = null,
                            distanceMeters = 210.0,
                            unit = DistanceUnit.METRIC,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = false,
                            routeShape = shape,
                            onDismissJunctionPreview = {},
                            isNightMode = true,
                            laneConfidence = LaneConfidence.HIGH,
                            laneSideHint = decision.suggestedSide,
                            laneDecision = decision,
                            laneHighlightColor = LaneHighlightColor.BLUE,
                            turnFocusActive = true
                        )
                    }
                }
            }
        }
        val slot0 = composeRule.onNodeWithTag("lane_box_0").fetchSemanticsNode().boundsInRoot
        val slot1 = composeRule.onNodeWithTag("lane_box_1").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Physical LTR order must hold in Persian RTL (slot0.left=${slot0.left} < slot1.left=${slot1.left})",
            slot0.left < slot1.left
        )
        val bmp = saveRootScreenshot("city_oak_st_blue_lane_fa_rtl.png")
        val bluePixels = countBrightBluePixels(bmp)
        assertTrue("Persian RTL city HUD screenshot must render bright blue lane pixels (found $bluePixels)", bluePixels > 300)
    }
}
