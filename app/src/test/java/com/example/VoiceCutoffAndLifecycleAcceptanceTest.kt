package com.example

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.map.LaneHighlightColor
import com.example.navigation.ActiveNavigationState
import com.example.navigation.AheadAlert
import com.example.navigation.GpsFix
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDemoScenarios
import com.example.navigation.LaneDirection
import com.example.navigation.LaneDisplaySurface
import com.example.navigation.LaneGuidanceDecision
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
import com.example.navigation.SpeechLifecycleState
import com.example.navigation.SpeechTraceRecord
import com.example.navigation.TtsEngineDriver
import com.example.navigation.TtsUtteranceCallbacks
import com.example.navigation.TurnFocusAudioPlayer
import com.example.navigation.VoiceGuidanceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Bug 1 Acceptance Tests:
 * 1. Highway exit at 65 mph (2 mi, 1 mi, 500 m, final exit) with long exit name, lane cue,
 *    and Turn Focus beeps in English and Persian — verifies full intended text and final-segment
 *    onDone completion with zero mid-sentence cutoffs.
 * 2. City turn with stop at the last traffic light, closely spaced turns, and reroute while speech
 *    is active — verifies complete relevant instructions and single clean cancellation of obsolete speech.
 * 3. TTS callback lifecycle (queued vs started vs completed), timely retry on error, stage
 *    non-consumption on failure, stale-generation callback immunity, and Phone+Auto deduplication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceCutoffAndLifecycleAcceptanceTest {

    private data class ScheduledUtterance(
        val utteranceId: String,
        val text: String,
        val isEnglish: Boolean,
        val useFallback: Boolean,
        val startAtMs: Long,
        val doneAtMs: Long,
        val callbacks: TtsUtteranceCallbacks,
        var started: Boolean = false,
        var stopped: Boolean = false
    )

    private class TimedFakeTtsEngineDriver(
        private val nowMs: () -> Long,
        var failPrimaryCount: Int = 0,
        var failAllCount: Int = 0
    ) : TtsEngineDriver {
        override var isReady: Boolean = true
        private var current: ScheduledUtterance? = null
        private val pendingStaleCallbacks = mutableListOf<Pair<Long, () -> Unit>>()
        val spokenSegments = mutableListOf<String>()

        override val isSpeaking: Boolean
            get() = current != null

        override fun speakSegment(
            text: String,
            isEnglish: Boolean,
            useFallbackEngine: Boolean,
            utteranceId: String,
            callbacks: TtsUtteranceCallbacks
        ): Boolean {
            val t = nowMs()
            if (failAllCount > 0) {
                failAllCount--
                pendingStaleCallbacks.add((t + 25L) to { callbacks.onError(utteranceId, TextToSpeech.ERROR_SYNTHESIS) })
                return true
            }
            if (!useFallbackEngine && failPrimaryCount > 0) {
                failPrimaryCount--
                pendingStaleCallbacks.add((t + 25L) to { callbacks.onError(utteranceId, TextToSpeech.ERROR_SERVICE) })
                return true
            }
            val durationMs = (text.length * 42L).coerceIn(500L, 3_600L)
            current = ScheduledUtterance(
                utteranceId = utteranceId,
                text = text,
                isEnglish = isEnglish,
                useFallback = useFallbackEngine,
                startAtMs = t + 30L,
                doneAtMs = t + 30L + durationMs,
                callbacks = callbacks
            )
            return true
        }

        override fun stopAll() {
            val prev = current ?: return
            current = null
            prev.stopped = true
            val fireAt = nowMs() + 20L
            // Simulate Android's asynchronous stale onStop/onDone callback after flush!
            pendingStaleCallbacks.add(fireAt to { prev.callbacks.onStop(prev.utteranceId, true) })
        }

        fun advanceTo(targetMs: Long) {
            val dueStale = pendingStaleCallbacks.filter { it.first <= targetMs }.sortedBy { it.first }
            pendingStaleCallbacks.removeAll(dueStale.toSet())
            dueStale.forEach { it.second.invoke() }

            var safety = 0
            while (safety++ < 16) {
                val active = current ?: break
                if (!active.started && targetMs >= active.startAtMs) {
                    active.started = true
                    spokenSegments.add(active.text)
                    active.callbacks.onStart(active.utteranceId)
                }
                val stillActive = current ?: break
                if (stillActive === active && active.started && targetMs >= active.doneAtMs) {
                    current = null
                    active.callbacks.onDone(active.utteranceId)
                } else {
                    break
                }
            }
        }
    }

    private fun buildNorthboundShape(totalMeters: Int, stepMeters: Int = 20): List<LatLon> {
        val steps = (totalMeters / stepMeters).coerceAtLeast(2)
        val degPerMeter = 1.0 / 111_320.0
        return (0..steps).map { idx -> LatLon(44.0500 + idx * stepMeters * degPerMeter, -123.0800) }
    }

    private fun buildHighwayExitRoute(id: String): OfflineRoute {
        val shape = buildNorthboundShape(totalMeters = 4_200, stepMeters = 20)
        val exitIdx = 185 // 3,700 m
        val fourLanes = LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1].lanes
        return OfflineRoute(
            id = id,
            summaryLabel = "I-5 North to Exit 195B",
            distanceMeters = 4_200.0,
            durationSeconds = 150.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART,
                    instructionEn = "Head north on I-5 North.",
                    streetNames = listOf("I-5 North"), lengthMeters = 3_700.0, timeSeconds = 130.0,
                    beginShapeIndex = 0, endShapeIndex = exitIdx, lat = shape[0].lat, lon = shape[0].lon,
                    isHighwayRoad = true, speedLimitMph = 65, laneCount = 4, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.EXIT_RIGHT,
                    instructionEn = "Take exit 195B toward Beltline Highway West / Eugene Airport.",
                    streetNames = listOf("Beltline Highway West"), lengthMeters = 500.0, timeSeconds = 20.0,
                    beginShapeIndex = exitIdx, endShapeIndex = shape.lastIndex,
                    lat = shape[exitIdx].lat, lon = shape[exitIdx].lon,
                    lanes = fourLanes, laneSource = LaneSource.DIRECT,
                    signpost = HighwaySignpost(
                        exitNumbers = listOf("195B"),
                        exitBranches = listOf("Beltline Highway West"),
                        exitTowards = listOf("Eugene Airport")
                    ),
                    isHighwayRoad = true, isHighwayExitOrSplit = true, speedLimitMph = 65, laneCount = 4, isMultiLaneRoad = true
                )
            )
        )
    }

    private fun runHighwayExit65MphScenario(language: AppLanguage): List<SpeechTraceRecord> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var simClockMs = 1_700_000_000_000L
        val driver = TimedFakeTtsEngineDriver(nowMs = { simClockMs })
        val voice = VoiceGuidanceManager(context, clockMs = { simClockMs })
        voice.setTestEngineDriver(driver)
        voice.setLanguage(language)

        val route = buildHighwayExitRoute("hwy_65_${language.name.lowercase()}")
        val controller = NavigationSessionController()
        voice.onStageNotConsumed = { rId, mIdx, st -> controller.releaseUnspokenStage(rId, mIdx, st) }

        val terminalTraces = mutableListOf<SpeechTraceRecord>()
        voice.setTraceSink { rec -> if (rec.finalState.isTerminal) terminalTraces.add(rec) }

        var navState = ActiveNavigationState(isNavigating = true, route = route, isSimulatingDrive = true)
        val speed65MphMps = 29.0576f
        val stepSec = 0.25
        var triggeredTurnFocusBeeps = false
        var injectedLowAlert = false

        for (tick in 0..600) {
            if (navState.hasArrived) break
            val fix = controller.computeSimulatedFix(navState, stepSec, timestampMs = simClockMs)
                ?.copy(speedMps = speed65MphMps) ?: break
            val res = controller.processGpsFix(
                currentState = navState, fix = fix, dbHazards = emptyList(), userReports = emptyList(),
                unit = DistanceUnit.IMPERIAL, language = language, speedAudioEnabled = false, nowMs = simClockMs
            )
            navState = res.state

            res.maneuverToSpeak?.let { (m, stage) ->
                val decision = LaneGuidanceDecision.evaluate(m, edgeLaneCountBeforeManeuver = 4)
                voice.speakManeuver(
                    maneuver = m, distanceMeters = navState.distanceToNextManeuverMeters,
                    unit = DistanceUnit.IMPERIAL, language = language, announcementStage = stage,
                    includeLaneGuidance = true, laneDecision = decision, routeId = route.id
                )
                // Immediately send a duplicate cue on the same GPS tick to verify no interruption!
                voice.speakManeuver(
                    maneuver = m, distanceMeters = navState.distanceToNextManeuverMeters,
                    unit = DistanceUnit.IMPERIAL, language = language, announcementStage = stage,
                    includeLaneGuidance = true, laneDecision = decision, routeId = route.id
                )
            }

            // Trigger Turn Focus beeps + blue-lane cue right as 500 m exit instruction begins speaking!
            if (!triggeredTurnFocusBeeps && navState.currentManeuverIndex == 1 && navState.distanceToNextManeuverMeters <= 500.0) {
                triggeredTurnFocusBeeps = true
                val exitM = route.maneuvers[1]
                val decision = LaneGuidanceDecision.evaluate(exitM, edgeLaneCountBeforeManeuver = 4)
                voice.playTurnFocusBeeps(
                    language = language,
                    speakShortPhrase = true,
                    context = SpeechContextTag(routeId = route.id, maneuverIndex = exitM.index, stage = "turn_focus"),
                    isStillValid = { navState.isNavigating && navState.currentManeuverIndex == 1 },
                    phraseProvider = {
                        decision.resolveVoiceCue(
                            surface = LaneDisplaySurface.PHONE_SCREEN,
                            isDiagramVisibleOnActiveDisplay = true,
                            highlightColor = LaneHighlightColor.BLUE,
                            isCarHostColorControlled = false,
                            language = language
                        )
                    },
                    onAllBeepsFinished = {}
                )
            }

            // Inject a low-priority alert while 1-mile exit announcement is speaking
            if (!injectedLowAlert && navState.currentManeuverIndex == 1 && navState.distanceToNextManeuverMeters <= 1_580.0) {
                injectedLowAlert = true
                voice.speakAlert(
                    AheadAlert("school_1", RoadAlertKind.SCHOOL_ZONE, fix.lat, fix.lon, 180.0, "School zone ahead", "منطقه مدرسه"),
                    language = language, unit = DistanceUnit.IMPERIAL
                )
            }

            simClockMs += (stepSec * 1000.0).toLong()
            driver.advanceTo(simClockMs)
            shadowOf(Looper.getMainLooper()).idleFor((stepSec * 1000.0).toLong(), TimeUnit.MILLISECONDS)
        }

        // Flush remaining active speech after final exit trigger
        repeat(40) {
            simClockMs += 250L
            driver.advanceTo(simClockMs)
            shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        }
        voice.shutdown()
        return terminalTraces
    }

    @Test
    fun bug1_highwayExit65Mph_englishAndPersian_completeAllStagesAndSegmentsWithoutCutoff() {
        val enTraces = runHighwayExit65MphScenario(AppLanguage.ENGLISH)
        val faTraces = runHighwayExit65MphScenario(AppLanguage.PERSIAN)

        val expectedStages = listOf(
            NavigationSessionController.STAGE_HW_2MI,
            NavigationSessionController.STAGE_HW_1MI,
            NavigationSessionController.STAGE_HW_500M,
            NavigationSessionController.STAGE_HW_NOW
        )

        for ((label, traces) in listOf("ENGLISH" to enTraces, "PERSIAN" to faTraces)) {
            val maneuverCompleted = traces.filter { it.stage in expectedStages && it.finalState == SpeechLifecycleState.COMPLETED }
            maneuverCompleted.forEach { rec ->
                println(
                    "VOICE_REPORT | $label | id=${rec.instructionId} | stage=${rec.stage} | " +
                        "state=${rec.finalState} | seg=${rec.completedSegments}/${rec.segmentCount} | text=\"${rec.intendedText}\""
                )
            }
            for (stage in expectedStages) {
                val match = maneuverCompleted.firstOrNull { it.stage == stage }
                assertTrue("$label must complete stage $stage without cutoff; traces=$traces", match != null)
                assertEquals(
                    "$label stage $stage must finish its FINAL segment onDone",
                    match!!.segmentCount, match.completedSegments
                )
                assertTrue(
                    "$label stage $stage must preserve full exit identifier '195B': '${match.intendedText}'",
                    match.intendedText.contains("195B")
                )
                assertTrue(
                    "$label stage $stage must preserve destination/branch name: '${match.intendedText}'",
                    match.intendedText.contains("Eugene Airport") || match.intendedText.contains("Beltline Highway West")
                )
            }
            val laneCue = traces.firstOrNull { it.stage == "turn_focus" && it.finalState == SpeechLifecycleState.COMPLETED }
            assertTrue("$label Turn Focus blue-lane cue must complete after exit speech without interrupting it; traces=$traces", laneCue != null)
            println(
                "VOICE_REPORT | $label | id=${laneCue!!.instructionId} | stage=${laneCue.stage} | " +
                    "state=${laneCue.finalState} | seg=${laneCue.completedSegments}/${laneCue.segmentCount} | text=\"${laneCue.intendedText}\""
            )
            // Persian mixed Persian/English exit prompt must have multiple ordered segments all completed
            if (label == "PERSIAN") {
                val fa500m = maneuverCompleted.first { it.stage == NavigationSessionController.STAGE_HW_500M }
                assertTrue(
                    "Persian mixed exit instruction must dispatch multiple ordered segments, got ${fa500m.segmentCount}",
                    fa500m.segmentCount >= 2
                )
                assertEquals(fa500m.segmentCount, fa500m.completedSegments)
            }
        }
    }

    @Test
    fun bug1_cityTurnLastLightStop_closelySpacedTurns_andRerouteCancelsObsoleteSpeechOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var simClockMs = 1_700_100_000_000L
        val driver = TimedFakeTtsEngineDriver(nowMs = { simClockMs })
        val voice = VoiceGuidanceManager(context, clockMs = { simClockMs })
        voice.setTestEngineDriver(driver)
        voice.setLanguage(AppLanguage.ENGLISH)

        val terminalTraces = mutableListOf<SpeechTraceRecord>()
        voice.setTraceSink { rec -> if (rec.finalState.isTerminal) terminalTraces.add(rec) }

        val shape = buildNorthboundShape(totalMeters = 1_650, stepMeters = 10)
        val m1Idx = 125 // 1,250 m
        val m2Idx = 138 // 1,380 m (130 m after M1)
        val signalIdx = 103 // 1,030 m (220 m before M1)
        val route = OfflineRoute(
            id = "city_signal_route",
            summaryLabel = "City Left Then Right",
            distanceMeters = 1_650.0,
            durationSeconds = 135.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on High Street.",
                    streetNames = listOf("High Street"), lengthMeters = 1_250.0, timeSeconds = 95.0,
                    beginShapeIndex = 0, endShapeIndex = m1Idx, lat = shape[0].lat, lon = shape[0].lon,
                    speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.LEFT, instructionEn = "Turn left onto Willamette Street.",
                    streetNames = listOf("Willamette Street"), lengthMeters = 130.0, timeSeconds = 12.0,
                    beginShapeIndex = m1Idx, endShapeIndex = m2Idx, lat = shape[m1Idx].lat, lon = shape[m1Idx].lon,
                    lanes = listOf(
                        LaneInfo(listOf(LaneDirection.LEFT), listOf(LaneDirection.LEFT), isRecommended = true, isActive = true, isValid = true),
                        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false)
                    ),
                    laneSource = LaneSource.DIRECT, speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 2, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Oak Street.",
                    streetNames = listOf("Oak Street"), lengthMeters = 270.0, timeSeconds = 28.0,
                    beginShapeIndex = m2Idx, endShapeIndex = shape.lastIndex, lat = shape[m2Idx].lat, lon = shape[m2Idx].lon,
                    speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true
                )
            )
        )
        val lastLight = RoadHazardPoint(
            id = 77L, type = "traffic_signals", lat = shape[signalIdx].lat, lon = shape[signalIdx].lon,
            label = "Signal Before Willamette", osmId = "node/77"
        )

        val controller = NavigationSessionController()
        var navState = ActiveNavigationState(isNavigating = true, route = route, isSimulatingDrive = true)
        val citySpeedMps = 13.41f // 30 mph
        var stoppedAtLight = false

        // Drive past 1 km prep, stop 4 seconds at last traffic light, pass light and hear post-signal cue
        for (tick in 0..420) {
            if (navState.currentManeuverIndex >= 2) break
            val distToSignal = shape[signalIdx].lat - (navState.snappedLocation?.lat ?: shape[0].lat)
            val isAtRedLight = !stoppedAtLight && distToSignal in 0.0..0.00015
            if (isAtRedLight) {
                stoppedAtLight = true
                // Hold stationary for 4 seconds at the last traffic light
                repeat(16) {
                    val stopPos = navState.snappedLocation ?: shape[signalIdx - 1]
                    val stopFix = GpsFix(stopPos.lat, stopPos.lon, speedMps = 0f, bearing = 0f, accuracyMeters = 5f, timestampMs = simClockMs)
                    val stopRes = controller.processGpsFix(
                        navState, stopFix, listOf(lastLight), emptyList(), DistanceUnit.METRIC, AppLanguage.ENGLISH, false, nowMs = simClockMs
                    )
                    navState = stopRes.state
                    simClockMs += 250L
                    driver.advanceTo(simClockMs)
                }
            }

            val fix = controller.computeSimulatedFix(navState, 0.25, timestampMs = simClockMs)?.copy(speedMps = citySpeedMps) ?: break
            val res = controller.processGpsFix(
                navState, fix, listOf(lastLight), emptyList(), DistanceUnit.METRIC, AppLanguage.ENGLISH, false, nowMs = simClockMs
            )
            navState = res.state
            res.maneuverToSpeak?.let { (m, stage) ->
                val followUp = ManeuverTimingPlanner.findBackToBackFollowUp(route, navState.currentManeuverIndex, citySpeedMps)
                val dec = LaneGuidanceDecision.evaluate(m, edgeLaneCountBeforeManeuver = 2)
                voice.speakManeuver(
                    maneuver = m, distanceMeters = navState.distanceToNextManeuverMeters, unit = DistanceUnit.METRIC,
                    language = AppLanguage.ENGLISH, announcementStage = stage,
                    includeLaneGuidance = dec.hasActionableLaneGuidance, nextManeuver = followUp,
                    laneDecision = dec, routeId = route.id
                )
            }
            simClockMs += 250L
            driver.advanceTo(simClockMs)
            // Stop right after post-signal instruction starts so we can also test closely spaced queueing + reroute!
            if (terminalTraces.any { it.stage == NavigationSessionController.STAGE_CITY_POST_SIGNAL && it.finalState == SpeechLifecycleState.COMPLETED }) {
                break
            }
        }

        val city1km = terminalTraces.firstOrNull { it.stage == NavigationSessionController.STAGE_CITY_1KM && it.finalState == SpeechLifecycleState.COMPLETED }
        val postLight = terminalTraces.firstOrNull { it.stage == NavigationSessionController.STAGE_CITY_POST_SIGNAL && it.finalState == SpeechLifecycleState.COMPLETED }
        assertTrue("City 1 km cue must complete fully; traces=$terminalTraces", city1km != null)
        assertTrue("Post-last-traffic-light cue must complete fully after stopping at light; traces=$terminalTraces", postLight != null)
        assertTrue("Post-light cue must preview closely spaced second turn onto Oak Street: '${postLight!!.intendedText}'", postLight.intendedText.contains("Oak Street"))

        // Now start a preparation cue and immediately enqueue a second equal-priority cue -> MUST queue, NOT truncate!
        voice.speakManeuver(route.maneuvers[2], 320.0, DistanceUnit.METRIC, AppLanguage.ENGLISH, NavigationSessionController.STAGE_CITY_1000FT, routeId = route.id)
        simClockMs += 100L; driver.advanceTo(simClockMs)
        voice.speakCustomText("Stay in the right lane for Oak Street.", AppLanguage.ENGLISH)
        repeat(30) { simClockMs += 250L; driver.advanceTo(simClockMs) }
        val completedM2Prep = terminalTraces.firstOrNull { it.stage == NavigationSessionController.STAGE_CITY_1000FT && it.finalState == SpeechLifecycleState.COMPLETED }
        val completedQueued = terminalTraces.firstOrNull { it.intendedText == "Stay in the right lane for Oak Street." && it.finalState == SpeechLifecycleState.COMPLETED }
        assertTrue("Active maneuver cue must finish completely without truncation", completedM2Prep != null)
        assertTrue("Equal-priority follow-up cue must play cleanly after active cue finishes", completedQueued != null)

        // Now trigger a REROUTE mid-sentence while speech is actively playing!
        voice.speakManeuver(route.maneuvers[2], 120.0, DistanceUnit.METRIC, AppLanguage.ENGLISH, NavigationSessionController.STAGE_CITY_NOW, routeId = route.id)
        simClockMs += 150L; driver.advanceTo(simClockMs)
        assertTrue("Instruction must be actively speaking before reroute", voice.isSpeakingOrQueued())
        voice.speakRerouting(AppLanguage.ENGLISH)
        repeat(20) { simClockMs += 200L; driver.advanceTo(simClockMs) }

        val canceledByReroute = terminalTraces.filter { it.stage == NavigationSessionController.STAGE_CITY_NOW && it.finalState == SpeechLifecycleState.CANCELED }
        val rerouteCompleted = terminalTraces.firstOrNull { it.stage == "rerouting" && it.finalState == SpeechLifecycleState.COMPLETED }
        assertEquals("Obsolete active instruction must be canceled strictly once on reroute", 1, canceledByReroute.size)
        assertEquals("preempted_by_reroute", canceledByReroute.first().cancelReason)
        assertTrue("Rerouting prompt must play and complete cleanly after cancellation", rerouteCompleted != null)
        println("VOICE_REPORT | CITY_REROUTE | canceled=${canceledByReroute.first().instructionId} reason=${canceledByReroute.first().cancelReason} | reroute=${rerouteCompleted!!.instructionId} state=${rerouteCompleted.finalState}")
        voice.shutdown()
    }

    @Test
    fun bug1_lifecycleQueuedStartedCompleted_timelyRetry_nonConsumptionOnFailure_andPhoneAutoDedup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var simClockMs = 1_700_200_000_000L
        val driver = TimedFakeTtsEngineDriver(nowMs = { simClockMs }, failPrimaryCount = 1)
        val voice = VoiceGuidanceManager(context, clockMs = { simClockMs })
        voice.setTestEngineDriver(driver)

        val route = buildHighwayExitRoute("lifecycle_route")
        val m = route.maneuvers[1]
        val controller = NavigationSessionController()
        var releasedStages = mutableListOf<Triple<String?, Int, String>>()
        voice.onStageNotConsumed = { rId, mIdx, st ->
            releasedStages.add(Triple(rId, mIdx, st))
            controller.releaseUnspokenStage(rId, mIdx, st)
        }

        val allTraces = mutableListOf<SpeechTraceRecord>()
        voice.setTraceSink { allTraces.add(it) }

        // 1. Primary engine errors once -> retries on fallback while timely -> STARTED then COMPLETED!
        voice.speakManeuver(m, 1_609.0, DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, NavigationSessionController.STAGE_HW_1MI, routeId = route.id)
        assertFalse("Stage must NOT be marked started while only QUEUED", voice.hasStageStarted(route.id, m.index, NavigationSessionController.STAGE_HW_1MI))
        assertFalse("Stage must NOT be marked completed before final onDone", voice.hasStageCompleted(route.id, m.index, NavigationSessionController.STAGE_HW_1MI))

        // Advance past initial error (25 ms) -> triggers fallback retry, then advance past fallback start (30 ms)
        simClockMs += 30L; driver.advanceTo(simClockMs)
        simClockMs += 40L; driver.advanceTo(simClockMs)
        assertTrue("Stage must be STARTED after retry onStart", voice.hasStageStarted(route.id, m.index, NavigationSessionController.STAGE_HW_1MI))
        assertFalse("Stage must NOT be COMPLETED while still speaking", voice.hasStageCompleted(route.id, m.index, NavigationSessionController.STAGE_HW_1MI))

        // Duplicate call from Phone + Android Auto handoff for the exact same route/maneuver/stage -> ignored!
        voice.speakManeuver(m, 1_595.0, DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, NavigationSessionController.STAGE_HW_1MI, routeId = route.id)

        repeat(20) { simClockMs += 250L; driver.advanceTo(simClockMs) }
        assertTrue("Stage must be COMPLETED after final onDone", voice.hasStageCompleted(route.id, m.index, NavigationSessionController.STAGE_HW_1MI))
        val completed1mi = allTraces.filter { it.stage == NavigationSessionController.STAGE_HW_1MI && it.finalState == SpeechLifecycleState.COMPLETED }
        assertEquals("Phone + Android Auto duplicate must play strictly once", 1, completed1mi.size)
        assertEquals("Retry count must be recorded as 1", 1, completed1mi.first().retryCount)

        // 2. Persistent TTS error -> FAILED after 1 retry, stage released (NOT silently consumed), next timely update succeeds!
        driver.failAllCount = 2
        voice.speakManeuver(m, 500.0, DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, NavigationSessionController.STAGE_HW_500M, routeId = route.id)
        simClockMs += 35L; driver.advanceTo(simClockMs)
        simClockMs += 35L; driver.advanceTo(simClockMs)
        assertFalse("Failed stage must NOT be marked started", voice.hasStageStarted(route.id, m.index, NavigationSessionController.STAGE_HW_500M))
        assertFalse("Failed stage must NOT be marked completed", voice.hasStageCompleted(route.id, m.index, NavigationSessionController.STAGE_HW_500M))
        assertTrue("Failed stage must trigger onStageNotConsumed", releasedStages.any { it.third == NavigationSessionController.STAGE_HW_500M })

        // TTS recovers -> stage is NOT consumed, so next update speaks and completes!
        driver.failAllCount = 0
        voice.speakManeuver(m, 480.0, DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, NavigationSessionController.STAGE_HW_500M, routeId = route.id)
        repeat(20) { simClockMs += 250L; driver.advanceTo(simClockMs) }
        assertTrue("Recovered stage must complete cleanly", voice.hasStageCompleted(route.id, m.index, NavigationSessionController.STAGE_HW_500M))
        voice.shutdown()
    }
}
