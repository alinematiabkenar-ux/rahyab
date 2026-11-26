package com.example

import android.content.Context
import android.graphics.Color as AndroidColor
import android.speech.tts.TextToSpeech
import androidx.car.app.navigation.model.LaneDirection as CarLaneDirection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.car.CarLaneBitmapRenderer
import com.example.car.CarManeuverMapper
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.i18n.stringsFor
import com.example.map.LaneHighlightColor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.GpsFix
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDemoScenarios
import com.example.navigation.LaneDirection
import com.example.navigation.LaneDisplaySurface
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LaneSource
import com.example.navigation.LatLon
import com.example.navigation.ManeuverTimingPlanner
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.SpeechContextTag
import com.example.navigation.SpeechPlaybackEvent
import com.example.navigation.SpeechPlaybackSink
import com.example.navigation.TurnFocusAudioPlayer
import com.example.navigation.VoiceGuidanceManager
import com.example.ui.components.TurnByTurnTopHud
import com.example.ui.theme.RahYabTheme
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper

/**
 * Acceptance Tests A, B, and C for:
 * 1. Dedicated top lane-guidance panel synchronized with "Get in the blue lane" (phone & Android Auto).
 * 2. Early speed/complexity-aware two-stage turn & lane announcements, back-to-back previews,
 *    priority speech queue preemption, and GPS-jitter deduplication.
 * 3. Missing lane data, multi-target lanes, and reroute cancellation of stale delayed speech.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanePanelAndVoiceTimingAcceptanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildNorthboundShape(totalMeters: Int, stepMeters: Int = 20): List<LatLon> {
        val steps = (totalMeters / stepMeters).coerceAtLeast(2)
        val degPerMeter = 1.0 / 111_320.0
        return (0..steps).map { idx ->
            LatLon(44.0500 + idx * stepMeters * degPerMeter, -123.0800)
        }
    }

    private data class RecordedPlayback(
        val scenario: String,
        val maneuverIndex: Int,
        val stage: String,
        val text: String,
        val speedMps: Float,
        val remainingMeters: Double,
        val secondsBeforeManeuver: Double,
        val queueDelayMs: Long,
        val lanePanelVisibleAtPlaybackStart: Boolean
    )

    private fun replayDriveAndRecordPlaybacks(
        scenario: String,
        route: OfflineRoute,
        speedMps: Float,
        unit: DistanceUnit = DistanceUnit.IMPERIAL,
        language: AppLanguage = AppLanguage.ENGLISH,
        injectLowPriorityAlertAtDistMeters: Double? = null
    ): List<RecordedPlayback> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var simClockMs = 1_700_000_000_000L
        val voice = VoiceGuidanceManager(context, clockMs = { simClockMs })
        voice.onInit(TextToSpeech.SUCCESS)
        voice.setLanguage(language)

        val records = mutableListOf<RecordedPlayback>()
        var activeState = ActiveNavigationState(isNavigating = true, route = route, isSimulatingDrive = true)
        var currentSpeedMps = speedMps

        voice.setPlaybackSink(object : SpeechPlaybackSink {
            override fun onSpeakStarted(event: SpeechPlaybackEvent) {
                val stage = event.context.stage.ifBlank { "alert" }
                val rem = activeState.distanceToNextManeuverMeters
                val secBefore = if (currentSpeedMps > 0.1f) rem / currentSpeedMps else 0.0
                records.add(
                    RecordedPlayback(
                        scenario = scenario,
                        maneuverIndex = event.context.maneuverIndex ?: -1,
                        stage = stage,
                        text = event.text,
                        speedMps = currentSpeedMps,
                        remainingMeters = rem,
                        secondsBeforeManeuver = secBefore,
                        queueDelayMs = event.playbackStartedAtMs - event.queuedAtMs,
                        lanePanelVisibleAtPlaybackStart = activeState.showLaneStrip
                    )
                )
            }
        })

        val controller = NavigationSessionController()
        var injectedLowAlert = false
        var prevManeuverIdx = -1
        val stepDtSec = 0.5
        val maxTicks = ((route.distanceMeters / speedMps.coerceAtLeast(1f)) / stepDtSec).toInt() + 20

        for (tick in 0..maxTicks) {
            if (activeState.hasArrived) break
            val fix = controller.computeSimulatedFix(
                currentState = activeState,
                elapsedSeconds = stepDtSec,
                timestampMs = simClockMs
            )?.copy(speedMps = speedMps) ?: break

            val res = controller.processGpsFix(
                currentState = activeState,
                fix = fix,
                dbHazards = emptyList(),
                userReports = emptyList(),
                unit = unit,
                language = language,
                speedAudioEnabled = false
            )
            activeState = res.state
            currentSpeedMps = fix.speedMps

            if (prevManeuverIdx != -1 && prevManeuverIdx != activeState.currentManeuverIndex) {
                voice.cancelStaleForManeuverOrReroute(route.id, activeState.currentManeuverIndex)
            }
            prevManeuverIdx = activeState.currentManeuverIndex

            if (injectLowPriorityAlertAtDistMeters != null &&
                !injectedLowAlert &&
                activeState.currentManeuverIndex == 1 &&
                activeState.distanceToNextManeuverMeters <= injectLowPriorityAlertAtDistMeters
            ) {
                injectedLowAlert = true
                voice.speakAlert(
                    AheadAlert(
                        id = "low_alert_weather",
                        kind = RoadAlertKind.SCHOOL_ZONE,
                        lat = fix.lat,
                        lon = fix.lon,
                        distanceMeters = 120.0,
                        titleEn = "School zone ahead",
                        titleFa = "منطقه مدرسه"
                    ),
                    language = language,
                    unit = unit
                )
            }

            res.maneuverToSpeak?.let { (maneuver, stage) ->
                val decision = if (maneuver.lanes.isNotEmpty()) {
                    LaneGuidanceDecision.evaluate(maneuver, edgeLaneCountBeforeManeuver = maneuver.lanes.size)
                } else null
                val nextFollowUp = ManeuverTimingPlanner.findBackToBackFollowUp(
                    route = route,
                    upcomingManeuverIdx = activeState.currentManeuverIndex,
                    speedMps = fix.speedMps
                )
                voice.speakManeuver(
                    maneuver = maneuver,
                    distanceMeters = activeState.distanceToNextManeuverMeters,
                    unit = unit,
                    language = language,
                    announcementStage = stage,
                    includeLaneGuidance = decision?.hasActionableLaneGuidance == true,
                    nextManeuver = nextFollowUp,
                    laneDecision = decision,
                    routeId = route.id
                )
            }

            simClockMs += (stepDtSec * 1000.0).toLong()
        }

        voice.shutdown()
        return records
    }

    // -------------------------------------------------------------------------
    // ACCEPTANCE TEST A
    // -------------------------------------------------------------------------

    @Test
    fun acceptanceA_fourLaneDedicatedPanelSyncsBeforeBlueLaneSpeechAcrossJunctionNarrowAndPersianAndAuto() {
        val route = LaneDemoScenarios.buildFourLaneSecondFromRightRoute()
        val maneuver = route.maneuvers[1].copy(
            signpost = HighwaySignpost(
                exitNumbers = listOf("194B"),
                exitBranches = listOf("I-105 W"),
                exitTowards = listOf("Eugene")
            )
        )
        val decision = LaneGuidanceDecision.evaluate(maneuver, edgeLaneCountBeforeManeuver = 4)
        assertEquals(LaneConfidence.HIGH, decision.confidence)
        assertEquals(listOf(2), decision.preferredIndices)
        assertEquals("Second lane from the right.", decision.positionTextEn)

        // 1. Verify Turn Focus delayed post-beep playback: panel is ALREADY shown BEFORE "Get in the blue lane." starts
        val context = ApplicationProvider.getApplicationContext<Context>()
        var simClockMs = 1_700_000_100_000L
        val voice = VoiceGuidanceManager(context, clockMs = { simClockMs })
        voice.onInit(TextToSpeech.SUCCESS)
        var panelShownBeforeSpeech = false
        var capturedCue: String? = null
        val isPanelVisible = true

        voice.setPlaybackSink(object : SpeechPlaybackSink {
            override fun onSpeakStarted(event: SpeechPlaybackEvent) {
                capturedCue = event.text
                panelShownBeforeSpeech = isPanelVisible
            }
        })

        voice.playTurnFocusBeeps(
            language = AppLanguage.ENGLISH,
            speakShortPhrase = true,
            context = SpeechContextTag(routeId = route.id, maneuverIndex = maneuver.index, stage = "turn_focus"),
            isStillValid = { true },
            phraseProvider = {
                decision.resolveVoiceCue(
                    surface = LaneDisplaySurface.PHONE_SCREEN,
                    isDiagramVisibleOnActiveDisplay = isPanelVisible,
                    highlightColor = LaneHighlightColor.BLUE,
                    isCarHostColorControlled = false,
                    language = AppLanguage.ENGLISH
                )
            },
            onAllBeepsFinished = {}
        )
        shadowOf(Looper.getMainLooper()).idleFor(TurnFocusAudioPlayer.TOTAL_DURATION_MS + 400L, TimeUnit.MILLISECONDS)
        assertEquals("Get in the blue lane.", capturedCue)
        assertTrue("Dedicated lane panel must already be visible when speech starts", panelShownBeforeSpeech)
        voice.shutdown()

        // 2. Verify dedicated top panel layout directly below next-turn card, with Junction View open on narrow 320dp screen
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
                            distanceMeters = 520.0,
                            unit = DistanceUnit.IMPERIAL,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = true,
                            routeShape = route.shape,
                            onDismissJunctionPreview = {},
                            isNightMode = true,
                            laneDecision = decision,
                            laneHighlightColor = LaneHighlightColor.BLUE,
                            turnFocusActive = true
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val nextTurnCard = composeRule.onNodeWithTag("next_turn_instruction_card").getUnclippedBoundsInRoot()
        val dedicatedPanel = composeRule.onNodeWithTag("dedicated_lane_panel").getUnclippedBoundsInRoot()
        val junctionCard = composeRule.onNodeWithTag("junction_preview_card").getUnclippedBoundsInRoot()

        assertTrue(
            "Dedicated lane panel must sit directly below next-turn instruction card",
            nextTurnCard.bottom <= dedicatedPanel.top
        )
        assertTrue(
            "Junction preview must stack below the dedicated lane panel without hiding it",
            dedicatedPanel.bottom <= junctionCard.top
        )

        val boxes = (0..3).map { composeRule.onNodeWithTag("lane_box_$it").getUnclippedBoundsInRoot() }
        for (i in 0..2) {
            assertTrue("Lane $i must stay left of lane ${i + 1}", boxes[i].right <= boxes[i + 1].left)
            composeRule.onNodeWithTag("full_lane_divider_${i + 1}").assertIsDisplayed()
        }
        assertTrue("Last lane box must fit inside 320dp dedicated panel", boxes[3].right <= dedicatedPanel.right)

        composeRule.onNodeWithTag("lane_box_0").assertContentDescriptionContains("Other lane", substring = true)
        composeRule.onNodeWithTag("lane_box_1").assertContentDescriptionContains("Other lane", substring = true)
        composeRule.onNodeWithTag("lane_box_2").assertContentDescriptionContains("Recommended lane", substring = true)
        composeRule.onNodeWithTag("lane_box_3").assertContentDescriptionContains("Other lane", substring = true)

        val neighborArrow = composeRule.onNodeWithTag("full_lane_arrow_1").getUnclippedBoundsInRoot()
        val targetArrow = composeRule.onNodeWithTag("full_lane_arrow_2").getUnclippedBoundsInRoot()
        val heightRatio = (targetArrow.bottom - targetArrow.top).value / (neighborArrow.bottom - neighborArrow.top).value
        assertTrue("Recommended arrow must be ~1.5x neighbor height, got $heightRatio", heightRatio in 1.42f..1.58f)
        composeRule.onNodeWithTag("full_lane_position_text")
            .assertIsDisplayed()
            .assertTextEquals("Second lane from the right.")

        // 3. Verify Android Auto path separately: host Step lanes + rendered bitmap + self-contained voice cue
        val autoVoiceCue = decision.resolveVoiceCue(
            surface = LaneDisplaySurface.ANDROID_AUTO,
            isDiagramVisibleOnActiveDisplay = true,
            highlightColor = LaneHighlightColor.BLUE,
            isCarHostColorControlled = true,
            language = AppLanguage.ENGLISH
        )
        assertEquals("Use the second lane from the right.", autoVoiceCue)
        assertFalse("Android Auto must never say 'blue lane'", autoVoiceCue.orEmpty().contains("blue", ignoreCase = true))

        val carStep = CarManeuverMapper.buildStepOrFallback(
            maneuver = maneuver,
            language = AppLanguage.ENGLISH,
            includeLanes = true,
            lanesImageProvider = { lanes -> CarLaneBitmapRenderer.renderLanesIcon(lanes, decision.confidence, decision) },
            laneConfidence = decision.confidence,
            laneDecision = decision
        )
        assertEquals("Android Auto Step must include all 4 physical lanes", 4, carStep.lanes.size)
        assertNotNull("Android Auto Step must include lanes bitmap image", carStep.lanesImage)

        val carBmp = CarLaneBitmapRenderer.renderLanesBitmap(maneuver.lanes, decision.confidence, decision)
        assertNotNull(carBmp)
        assertEquals(4 * 54 + 12, carBmp!!.width)
        assertEquals(72, carBmp.height)
        // Verify lane 2 (index 2) contains bright blue fill while lane 0 is neutral gray
        val lane0Pixel = carBmp.getPixel(6 + 0 * 54 + 12, 16)
        val lane2Pixel = carBmp.getPixel(6 + 2 * 54 + 12, 16)
        assertTrue(
            "Car lane 2 bitmap slot must be bright blue (B > R + 100), got R=${AndroidColor.red(lane2Pixel)} G=${AndroidColor.green(lane2Pixel)} B=${AndroidColor.blue(lane2Pixel)}",
            AndroidColor.blue(lane2Pixel) > AndroidColor.red(lane2Pixel) + 100
        )
        assertTrue(
            "Car lane 0 bitmap slot must be neutral gray (|B - R| < 50), got R=${AndroidColor.red(lane0Pixel)} G=${AndroidColor.green(lane0Pixel)} B=${AndroidColor.blue(lane0Pixel)}",
            kotlin.math.abs(AndroidColor.blue(lane0Pixel) - AndroidColor.red(lane0Pixel)) < 50
        )

        // Verify unmarked lane slot maps to SHAPE_UNKNOWN on Android Auto (never fabricated straight)
        val unmarkedCarLanes = CarManeuverMapper.toCarLanes(
            listOf(
                LaneInfo(emptyList(), emptyList(), isRecommended = false, isUnmarkedSlot = true),
                LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true)
            )
        )
        assertEquals(2, unmarkedCarLanes.size)
        assertEquals(CarLaneDirection.SHAPE_UNKNOWN, unmarkedCarLanes[0].directions.first().shape)
    }

    @Test
    fun acceptanceA_persianRtlPreservesPhysicalLtrOrderAndLocalizedBlueLaneCue() {
        val route = LaneDemoScenarios.buildFourLaneSecondFromRightRoute()
        val maneuver = route.maneuvers[1]
        val decision = LaneGuidanceDecision.evaluate(maneuver, edgeLaneCountBeforeManeuver = 4)

        assertEquals(
            "به خط آبی بروید.",
            decision.resolveVoiceCue(
                surface = LaneDisplaySurface.PHONE_SCREEN,
                isDiagramVisibleOnActiveDisplay = true,
                highlightColor = LaneHighlightColor.BLUE,
                isCarHostColorControlled = false,
                language = AppLanguage.PERSIAN
            )
        )
        assertEquals(
            "از خط دوم از سمت راست استفاده کنید.",
            decision.resolveVoiceCue(
                surface = LaneDisplaySurface.ANDROID_AUTO,
                isDiagramVisibleOnActiveDisplay = true,
                highlightColor = LaneHighlightColor.BLUE,
                isCarHostColorControlled = true,
                language = AppLanguage.PERSIAN
            )
        )

        composeRule.setContent {
            RahYabTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.PERSIAN,
                    LocalAppStrings provides stringsFor(AppLanguage.PERSIAN),
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    Box(modifier = Modifier.width(320.dp)) {
                        TurnByTurnTopHud(
                            maneuver = maneuver,
                            thenManeuver = null,
                            distanceMeters = 450.0,
                            unit = DistanceUnit.METRIC,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = false,
                            routeShape = route.shape,
                            onDismissJunctionPreview = {},
                            isNightMode = false,
                            laneDecision = decision
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
        assertTrue("Persian RTL must preserve physical LTR lane order", b0.left < b1.left && b1.left < b2.left && b2.left < b3.left)
        composeRule.onNodeWithTag("full_lane_position_text")
            .assertIsDisplayed()
            .assertTextEquals("خط دوم از سمت راست.")
    }

    // -------------------------------------------------------------------------
    // ACCEPTANCE TEST B
    // -------------------------------------------------------------------------

    @Test
    fun acceptanceB_timingAtActualPlaybackStartForUrbanFastMultilaneBackToBackAndJitter() {
        // 1. Urban right turn (25 mph = 11.18 m/s) + short block check
        val urbanShape = buildNorthboundShape(totalMeters = 700, stepMeters = 10)
        val urbanTurnIdx = 55 // 550m
        val urbanRoute = OfflineRoute(
            id = "accept_b_urban",
            summaryLabel = "Urban Right Turn",
            distanceMeters = 700.0,
            durationSeconds = 65.0,
            shape = urbanShape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on Pearl Street.",
                    streetNames = listOf("Pearl Street"), lengthMeters = 550.0, timeSeconds = 50.0,
                    beginShapeIndex = 0, endShapeIndex = urbanTurnIdx, lat = urbanShape[0].lat, lon = urbanShape[0].lon,
                    speedLimitMph = 25, laneCount = 1, isMultiLaneRoad = false
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Oak Street.",
                    streetNames = listOf("Oak Street"), lengthMeters = 150.0, timeSeconds = 15.0,
                    beginShapeIndex = urbanTurnIdx, endShapeIndex = urbanShape.lastIndex,
                    lat = urbanShape[urbanTurnIdx].lat, lon = urbanShape[urbanTurnIdx].lon,
                    speedLimitMph = 25, laneCount = 1, isMultiLaneRoad = false
                )
            )
        )
        val urbanPlaybacks = replayDriveAndRecordPlaybacks(
            scenario = "Urban Right Turn (25 mph)",
            route = urbanRoute,
            speedMps = 11.18f
        ).filter { it.maneuverIndex == 1 }

        val urbanPrep = urbanPlaybacks.first { it.stage == NavigationSessionController.STAGE_CITY_1000FT }
        val urbanFinal = urbanPlaybacks.first { it.stage == NavigationSessionController.STAGE_CITY_NOW }
        assertTrue("Urban prep must fire >= 20s before turn, got ${urbanPrep.secondsBeforeManeuver}s", urbanPrep.secondsBeforeManeuver >= 20.0)
        assertTrue("Urban final must fire >= 5.0s before turn, got ${urbanFinal.secondsBeforeManeuver}s", urbanFinal.secondsBeforeManeuver >= 5.0)
        assertTrue("Urban final must fire >= 60m before turn, got ${urbanFinal.remainingMeters}m", urbanFinal.remainingMeters >= 60.0)

        // 2. Faster multilane approach (55 mph = 24.59 m/s, 4 lanes, with low-priority alert preempted before final cue)
        val fastShape = buildNorthboundShape(totalMeters = 2600, stepMeters = 20)
        val fastExitIdx = 110 // 2200m
        val fourLanes = LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1].lanes
        val fastRoute = OfflineRoute(
            id = "accept_b_fast_multilane",
            summaryLabel = "55mph 4-Lane Approach",
            distanceMeters = 2600.0,
            durationSeconds = 110.0,
            shape = fastShape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on Beltline Highway.",
                    streetNames = listOf("Beltline Hwy"), lengthMeters = 2200.0, timeSeconds = 90.0,
                    beginShapeIndex = 0, endShapeIndex = fastExitIdx, lat = fastShape[0].lat, lon = fastShape[0].lon,
                    isHighwayRoad = true, speedLimitMph = 55, laneCount = 4, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.EXIT_RIGHT, instructionEn = "Take exit 10 onto River Road.",
                    streetNames = listOf("River Road"), lengthMeters = 400.0, timeSeconds = 20.0,
                    beginShapeIndex = fastExitIdx, endShapeIndex = fastShape.lastIndex,
                    lat = fastShape[fastExitIdx].lat, lon = fastShape[fastExitIdx].lon,
                    lanes = fourLanes, laneSource = LaneSource.DIRECT,
                    isHighwayRoad = true, isHighwayExitOrSplit = true, speedLimitMph = 55, laneCount = 4, isMultiLaneRoad = true
                )
            )
        )
        val fastPlaybacks = replayDriveAndRecordPlaybacks(
            scenario = "Fast 4-Lane Approach (55 mph)",
            route = fastRoute,
            speedMps = 24.59f,
            injectLowPriorityAlertAtDistMeters = 210.0
        )
        val fastManeuverCues = fastPlaybacks.filter { it.maneuverIndex == 1 }
        val fastPrep = fastManeuverCues.first { it.stage == NavigationSessionController.STAGE_HW_1MI }
        val fastFinal = fastManeuverCues.first { it.stage == NavigationSessionController.STAGE_HW_NOW }
        assertTrue("Fast multilane lane panel must already be visible when prep cue starts", fastPrep.lanePanelVisibleAtPlaybackStart)
        assertTrue("Fast multilane prep must fire >= 55s before maneuver, got ${fastPrep.secondsBeforeManeuver}s", fastPrep.secondsBeforeManeuver >= 55.0)
        assertTrue("Fast multilane final must fire >= 6.0s before maneuver, got ${fastFinal.secondsBeforeManeuver}s", fastFinal.secondsBeforeManeuver >= 6.0)
        assertEquals("Imminent maneuver must preempt low-priority alert with 0ms queue delay", 0L, fastFinal.queueDelayMs)

        // 3. Two closely spaced back-to-back intersections (30 mph = 13.41 m/s: Left onto Main St, then 130m later Right onto Oak St)
        val b2bShape = buildNorthboundShape(totalMeters = 900, stepMeters = 10)
        val m1Idx = 50 // 500m
        val m2Idx = 63 // 630m (130m after M1!)
        val b2bRoute = OfflineRoute(
            id = "accept_b_back_to_back",
            summaryLabel = "Back-to-Back Turns",
            distanceMeters = 900.0,
            durationSeconds = 70.0,
            shape = b2bShape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on High Street.",
                    streetNames = listOf("High Street"), lengthMeters = 500.0, timeSeconds = 38.0,
                    beginShapeIndex = 0, endShapeIndex = m1Idx, lat = b2bShape[0].lat, lon = b2bShape[0].lon,
                    speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.LEFT, instructionEn = "Turn left onto Main Street.",
                    streetNames = listOf("Main Street"), lengthMeters = 130.0, timeSeconds = 11.0,
                    beginShapeIndex = m1Idx, endShapeIndex = m2Idx, lat = b2bShape[m1Idx].lat, lon = b2bShape[m1Idx].lon,
                    lanes = listOf(
                        LaneInfo(listOf(LaneDirection.LEFT), listOf(LaneDirection.LEFT), isRecommended = true, isActive = true, isValid = true),
                        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false)
                    ),
                    laneSource = LaneSource.DIRECT, speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 2, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Oak Street.",
                    streetNames = listOf("Oak Street"), lengthMeters = 270.0, timeSeconds = 21.0,
                    beginShapeIndex = m2Idx, endShapeIndex = b2bShape.lastIndex, lat = b2bShape[m2Idx].lat, lon = b2bShape[m2Idx].lon,
                    speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true
                )
            )
        )
        val b2bPlaybacks = replayDriveAndRecordPlaybacks(
            scenario = "Back-to-Back Intersections (30 mph, 130m spacing)",
            route = b2bRoute,
            speedMps = 13.41f
        )
        val m1Prep = b2bPlaybacks.first { it.maneuverIndex == 1 && it.stage == NavigationSessionController.STAGE_CITY_1000FT }
        val m1Final = b2bPlaybacks.first { it.maneuverIndex == 1 && it.stage == NavigationSessionController.STAGE_CITY_NOW }
        val m2Cues = b2bPlaybacks.filter { it.maneuverIndex == 2 }

        assertTrue(
            "M1 preparation cue must preview closely spaced M2: '${m1Prep.text}'",
            m1Prep.text.contains("then take the next right", ignoreCase = true) &&
                m1Prep.text.contains("Oak Street", ignoreCase = true)
        )
        assertTrue("M2 must fire promptly after M1 without cooldown block", m2Cues.isNotEmpty())
        val m2First = m2Cues.first()
        assertTrue("M2 must fire with >= 5.0s lead before Oak Street, got ${m2First.secondsBeforeManeuver}s", m2First.secondsBeforeManeuver >= 5.0)

        // Verify top lane panel labels which intersection its lanes belong to when thenManeuver is present
        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ENGLISH,
                    LocalAppStrings provides stringsFor(AppLanguage.ENGLISH)
                ) {
                    Box(modifier = Modifier.width(360.dp)) {
                        TurnByTurnTopHud(
                            maneuver = b2bRoute.maneuvers[1],
                            thenManeuver = b2bRoute.maneuvers[2],
                            distanceMeters = 220.0,
                            unit = DistanceUnit.IMPERIAL,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = false,
                            routeShape = b2bShape,
                            onDismissJunctionPreview = {},
                            isNightMode = true
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lane_panel_maneuver_label")
            .assertIsDisplayed()
            .assertTextEquals("Lanes for Main Street")

        // 4. Verify GPS jitter / stop-and-go never duplicates turn cues
        val jitterController = NavigationSessionController()
        var jitterState = ActiveNavigationState(isNavigating = true, route = urbanRoute, isSimulatingDrive = false)
        var triggerCount = 0
        val jitterIndices = listOf(22, 23, 21, 22, 23, 22, 24) // bouncing around ~330m from turn
        jitterIndices.forEachIndexed { i, idx ->
            val pt = urbanShape[idx]
            val res = jitterController.processGpsFix(
                currentState = jitterState,
                fix = GpsFix(pt.lat, pt.lon, speedMps = if (i % 2 == 0) 2.0f else 0.4f, bearing = 0f, accuracyMeters = 8f, timestampMs = 10_000L + i * 1_000L),
                dbHazards = emptyList(),
                userReports = emptyList(),
                unit = DistanceUnit.IMPERIAL,
                language = AppLanguage.ENGLISH,
                speedAudioEnabled = false
            )
            jitterState = res.state
            if (res.maneuverToSpeak != null) triggerCount++
        }
        assertEquals("GPS jitter / stop-and-go must emit preparation cue strictly once", 1, triggerCount)

        // Emit structured timing report for verification summary
        val allTimingLines = listOf(urbanPrep, urbanFinal, fastPrep, fastFinal, m1Prep, m1Final) + m2Cues
        allTimingLines.forEach { r ->
            println(
                "TIMING_METRIC | ${r.scenario} | M${r.maneuverIndex} | ${r.stage} | " +
                    "speed=${"%.2f".format(r.speedMps)}m/s (${"%.1f".format(r.speedMps * 2.23694f)}mph) | " +
                    "rem=${"%.1f".format(r.remainingMeters)}m | lead=${"%.1f".format(r.secondsBeforeManeuver)}s | " +
                    "queueDelay=${r.queueDelayMs}ms | panelVisible=${r.lanePanelVisibleAtPlaybackStart} | text=\"${r.text}\""
            )
        }
    }

    // -------------------------------------------------------------------------
    // ACCEPTANCE TEST C
    // -------------------------------------------------------------------------

    @Test
    fun acceptanceC_missingLaneDataTwoTargetLanesAndRerouteCancelsStaleSpeech() {
        // 1. Missing lane data -> no lane panel, verified turn instruction with street name, NEVER "blue lane"
        val noLaneManeuver = RouteManeuver(
            index = 1,
            type = ManeuverType.RIGHT,
            instructionEn = "Turn right onto Oak Street.",
            streetNames = listOf("Oak Street"),
            lengthMeters = 320.0,
            timeSeconds = 25.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            lat = 44.055,
            lon = -123.080,
            lanes = emptyList()
        )
        val missingDecision = LaneGuidanceDecision.evaluate(noLaneManeuver)
        assertFalse(missingDecision.hasActionableLaneGuidance)
        assertFalse(missingDecision.shouldShowFullDiagram)
        val missingCue = missingDecision.resolveVoiceCue(
            surface = LaneDisplaySurface.PHONE_SCREEN,
            isDiagramVisibleOnActiveDisplay = false,
            highlightColor = LaneHighlightColor.BLUE,
            isCarHostColorControlled = false,
            language = AppLanguage.ENGLISH
        )
        assertEquals("Turn right on Oak Street.", missingCue)
        assertFalse(missingCue.orEmpty().contains("lane", ignoreCase = true))

        // 2. Two equally recommended lanes -> both highlighted + matching voice guidance
        val twoTargetManeuver = RouteManeuver(
            index = 2,
            type = ManeuverType.EXIT_RIGHT,
            instructionEn = "Take exit 194B onto I-105 W",
            streetNames = listOf("I-105 W"),
            lengthMeters = 800.0,
            timeSeconds = 35.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            lat = 44.060,
            lon = -123.080,
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false),
                LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false),
                LaneInfo(listOf(LaneDirection.THROUGH, LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true),
                LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true)
            ),
            laneSource = LaneSource.DIRECT,
            isHighwayExitOrSplit = true
        )
        val twoTargetDecision = LaneGuidanceDecision.evaluate(twoTargetManeuver, edgeLaneCountBeforeManeuver = 4)
        assertEquals(listOf(2, 3), twoTargetDecision.preferredIndices)
        val twoTargetCue = twoTargetDecision.resolveVoiceCue(
            surface = LaneDisplaySurface.PHONE_SCREEN,
            isDiagramVisibleOnActiveDisplay = true,
            highlightColor = LaneHighlightColor.BLUE,
            isCarHostColorControlled = false,
            language = AppLanguage.ENGLISH
        )
        assertEquals("Use either of the two right lanes.", twoTargetCue)

        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ENGLISH,
                    LocalAppStrings provides stringsFor(AppLanguage.ENGLISH)
                ) {
                    Box(modifier = Modifier.width(360.dp)) {
                        TurnByTurnTopHud(
                            maneuver = twoTargetManeuver,
                            thenManeuver = null,
                            distanceMeters = 500.0,
                            unit = DistanceUnit.IMPERIAL,
                            isRerouting = false,
                            showLaneStrip = true,
                            showJunctionPreview = false,
                            routeShape = emptyList(),
                            onDismissJunctionPreview = {},
                            isNightMode = true,
                            laneDecision = twoTargetDecision
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lane_box_2").assertContentDescriptionContains("Recommended lane", substring = true)
        composeRule.onNodeWithTag("lane_box_3").assertContentDescriptionContains("Recommended lane", substring = true)
        composeRule.onNodeWithTag("full_lane_position_text")
            .assertIsDisplayed()
            .assertTextEquals("Use either of the two right lanes.")

        // 3. Reroute after delayed Turn Focus cue is queued -> stale speech canceled before playback starts
        val context = ApplicationProvider.getApplicationContext<Context>()
        val voice = VoiceGuidanceManager(context)
        voice.onInit(TextToSpeech.SUCCESS)
        val spokenTexts = mutableListOf<String>()
        voice.setPlaybackSink(object : SpeechPlaybackSink {
            override fun onSpeakStarted(event: SpeechPlaybackEvent) {
                spokenTexts.add(event.text)
            }
        })

        var activeRouteId = "route_old"
        var rerouting = false
        val fourLaneDecision = LaneGuidanceDecision.evaluate(
            LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1],
            edgeLaneCountBeforeManeuver = 4
        )
        voice.playTurnFocusBeeps(
            language = AppLanguage.ENGLISH,
            speakShortPhrase = true,
            context = SpeechContextTag(routeId = "route_old", maneuverIndex = 1, stage = "turn_focus"),
            isStillValid = { !rerouting && activeRouteId == "route_old" },
            phraseProvider = {
                fourLaneDecision.resolveVoiceCue(
                    surface = LaneDisplaySurface.PHONE_SCREEN,
                    isDiagramVisibleOnActiveDisplay = true,
                    highlightColor = LaneHighlightColor.BLUE,
                    isCarHostColorControlled = false,
                    language = AppLanguage.ENGLISH
                )
            },
            onAllBeepsFinished = {}
        )

        // Advance partway through the beeps, then trigger reroute
        shadowOf(Looper.getMainLooper()).idleFor(150L, TimeUnit.MILLISECONDS)
        rerouting = true
        activeRouteId = "route_new"
        voice.speakRerouting(AppLanguage.ENGLISH)

        // Advance well past original beep sequence; stale "Get in the blue lane." must NEVER play
        shadowOf(Looper.getMainLooper()).idleFor(TurnFocusAudioPlayer.TOTAL_DURATION_MS + 600L, TimeUnit.MILLISECONDS)
        assertTrue("Expected Rerouting prompt to play: $spokenTexts", spokenTexts.any { it.contains("Rerouting", ignoreCase = true) })
        assertFalse(
            "Stale 'Get in the blue lane.' must NEVER play after reroute: $spokenTexts",
            spokenTexts.any { it.contains("blue lane", ignoreCase = true) }
        )
        voice.shutdown()
    }

    @Test
    fun acceptanceC_reroutingClearsDedicatedLanePanelImmediately() {
        val maneuver = LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1]
        composeRule.setContent {
            RahYabTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.ENGLISH,
                    LocalAppStrings provides stringsFor(AppLanguage.ENGLISH)
                ) {
                    Box(modifier = Modifier.width(360.dp)) {
                        TurnByTurnTopHud(
                            maneuver = maneuver,
                            thenManeuver = null,
                            distanceMeters = 300.0,
                            unit = DistanceUnit.IMPERIAL,
                            isRerouting = true,
                            showLaneStrip = true,
                            showJunctionPreview = false,
                            routeShape = emptyList(),
                            onDismissJunctionPreview = {},
                            isNightMode = true,
                            turnFocusActive = true
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dedicated_lane_panel").assertDoesNotExist()
        composeRule.onNodeWithTag("lane_guidance_strip").assertDoesNotExist()
    }
}
