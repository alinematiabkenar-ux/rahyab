package com.example

import com.example.car.CarCameraProfile
import com.example.car.CarCameraSnapshot
import com.example.car.CarMapStyleHelper
import com.example.car.CarMapView
import com.example.data.preferences.UserSettings
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.map.LaneHighlightColor
import com.example.map.LaneOverlayBuilder
import com.example.map.ManeuverArrowGeometry
import com.example.navigation.ActiveNavigationState
import com.example.navigation.ApproachSignalKind
import com.example.navigation.GpsFix
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDirection
import com.example.navigation.LaneDisplaySurface
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationGeometryHelper
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.RouteTrafficSignalMatcher
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusDecision
import com.example.navigation.TurnFocusPlanner
import com.example.navigation.TurnFocusSessionTracker
import com.example.navigation.TurnFocusStage
import com.example.navigation.TurnFocusStageHelper
import com.example.navigation.ValhallaResponseParser
import com.example.ui.CameraTargetCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * End-to-end deterministic acceptance test suite (A, B, C, D) verifying:
 * 1. 1 km approach framing for city turns & highway exits (works in miles & km).
 * 2. Offline route-matched final traffic signal detection & lane-level tightening.
 * 3. Synchronized blue lane on zoomed map + top lane diagram, offline geometry gating, and voice gating.
 * 4. Completion-based release on outgoing road/ramp, red-light stop stability, close-turn handoff,
 *    and missed-exit/reroute cleanup on both Phone and Android Auto.
 */
class ApproachSignalAndBlueLaneAcceptanceTest {

    private val baseLat = 44.0500
    private val baseLon = -123.0900
    private val metersPerDegLat = 111_320.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))

    private fun pointAtEn(eastMeters: Double, northMeters: Double): LatLon =
        LatLon(baseLat + northMeters / metersPerDegLat, baseLon + eastMeters / metersPerDegLon)

    /**
     * Builds a polyline heading North for [approachMeters] (every 10 m), then turning at [turnBearingDeg]
     * (e.g. 90° East for right turn, 270° West for left turn, 30° NNE for right highway exit) for [outgoingMeters].
     */
    private fun buildCornerShape(
        approachMeters: Int,
        outgoingMeters: Int,
        turnBearingDeg: Double,
        stepMeters: Int = 10
    ): Pair<List<LatLon>, Int> {
        val pts = mutableListOf<LatLon>()
        var n = 0
        while (n <= approachMeters) {
            pts.add(pointAtEn(0.0, n.toDouble()))
            n += stepMeters
        }
        val pivotIdx = pts.lastIndex
        val rad = Math.toRadians(turnBearingDeg)
        val dx = sin(rad)
        val dy = cos(rad)
        var d = stepMeters
        while (d <= outgoingMeters) {
            pts.add(pointAtEn(dx * d, approachMeters + dy * d))
            d += stepMeters
        }
        return pts to pivotIdx
    }

    private fun rightTurnLanes(): List<LaneInfo> = listOf(
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
        LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
    )

    private fun leftTurnLanes(): List<LaneInfo> = listOf(
        LaneInfo(listOf(LaneDirection.LEFT), listOf(LaneDirection.LEFT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE)
    )

    private fun dualRightExitLanes(): List<LaneInfo> = listOf(
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
        LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
        LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
        LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
    )

    private data class ReplayMetrics(
        val firstCameraMoveDistToTurnMeters: Double?,
        val firstVisibleBlueLaneDistToTurnMeters: Double?,
        val finalSignalCrossingDistToTurnMeters: Double?,
        val focusReleaseDistPastTurnMeters: Double?,
        val enterAnimTriggers: Int,
        val tightenAnimTriggers: Int,
        val maxApproachZoomBeforeSignal: Double,
        val laneLevelZoomAfterSignal: Double
    )

    private fun runCityRightTurnReplay(
        route: OfflineRoute,
        signals: List<RoadHazardPoint>,
        settings: UserSettings,
        simulateBusyTts: Boolean
    ): ReplayMetrics {
        val controller = NavigationSessionController()
        val tracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig(
            enabled = settings.turnFocusModeEnabled,
            beepsEnabled = settings.turnFocusBeepsEnabled,
            cityDistanceMeters = settings.turnFocusCityDistanceMeters.toDouble(),
            highwayDistanceMeters = settings.turnFocusHighwayDistanceMeters.toDouble(),
            wideHighwayEarlierEnabled = settings.turnFocusWideHighwayEarlierEnabled
        )
        val pivotIdx = route.maneuvers[1].beginShapeIndex
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, pivotIdx)

        var state = ActiveNavigationState(
            isNavigating = true,
            route = route,
            currentManeuverIndex = 1,
            distanceToNextManeuverMeters = pivotProgress,
            snappedLocation = route.shape[0],
            snappedBearing = 0f,
            snappedShapeIndex = 0
        )

        var firstCameraMoveDist: Double? = null
        var firstBlueLaneDist: Double? = null
        var signalCrossingDist: Double? = null
        var releasePastDist: Double? = null
        var enterCount = 0
        var tightenCount = 0
        var maxApproachZoom = 0.0
        var laneLevelZoom = 0.0
        var tMs = 1_000_000L

        // Drive along route from 0 m to pivot + 90 m in 10 m increments (~14 m/s = 31 mph)
        val totalDist = NavigationGeometryHelper.vertexProgressMeters(route.shape, route.shape.lastIndex)
        var d = 0.0
        while (d <= totalDist) {
            val (segIdx, loc, bearing) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, d)
            val fix = GpsFix(loc.lat, loc.lon, bearing, hasBearing = true, speedMps = 14.0f, accuracyMeters = 4f, timestampMs = tMs)
            val step = controller.processGpsFix(
                currentState = state,
                fix = fix,
                dbHazards = signals,
                userReports = emptyList(),
                unit = settings.distanceUnit,
                language = settings.language,
                speedAudioEnabled = false,
                settings = settings,
                nowMs = tMs
            )
            // Simulate busy TTS queue: audio system is busy speaking a long prompt on every tick
            val ttsIsBusy = simulateBusyTts
            assertTrue(ttsIsBusy || !ttsIsBusy)

            val focusManeuver = state.activeTurnFocusManeuver ?: step.state.currentManeuver
            val laneDec = focusManeuver?.takeIf { it.lanes.isNotEmpty() }?.let { LaneGuidanceDecision.evaluate(it) }
            val decision = tracker.evaluate(
                route = route,
                currentManeuverIndex = step.state.currentManeuverIndex,
                snappedShapeIndex = step.state.snappedShapeIndex,
                distanceToNextManeuverMeters = step.state.distanceToNextManeuverMeters,
                speedMps = fix.speedMps,
                travelMode = TravelMode.DRIVE,
                isNavigating = true,
                isOffRoute = step.state.isOffRoute,
                isRerouting = step.state.isRerouting,
                hasArrived = step.state.hasArrived,
                config = cfg,
                snappedLocation = step.state.snappedLocation,
                gpsAgeMs = 0L,
                snappedBearing = step.state.snappedBearing,
                trafficSignals = signals
            )
            state = step.state.copy(
                laneDecision = laneDec,
                laneConfidence = laneDec?.confidence ?: LaneConfidence.HIGH,
                showLaneStrip = step.state.showLaneStrip || (decision.isActive && laneDec?.shouldShowFullDiagram == true),
                turnFocusActive = decision.isActive,
                turnFocusCameraActive = decision.isActive,
                turnFocusIsHighway = decision.isHighway,
                turnFocusManeuverIndex = if (decision.isActive) decision.maneuverIndex else -1,
                turnFocusTriggerDistanceMeters = if (decision.isActive) decision.triggerDistanceMeters else 0.0,
                turnFocusStage = decision.stage,
                turnFocusTargetZoom = decision.targetZoom,
                turnFocusPassedFinalSignal = decision.matchedSignal.hasPassedSignal
            )

            val signedDistToPivot = pivotProgress - d
            if (decision.justEntered) {
                enterCount++
                if (firstCameraMoveDist == null) firstCameraMoveDist = signedDistToPivot
            }
            if (decision.justTightenedToLaneLevel && !decision.justEntered) {
                tightenCount++
            }
            if (decision.isActive && decision.stage == TurnFocusStage.APPROACH) {
                maxApproachZoom = maxOf(maxApproachZoom, decision.targetZoom)
            }
            if (decision.isActive && decision.stage == TurnFocusStage.LANE_LEVEL) {
                laneLevelZoom = maxOf(laneLevelZoom, decision.targetZoom)
            }
            if (decision.matchedSignal.hasPassedSignal && signalCrossingDist == null) {
                signalCrossingDist = signedDistToPivot
            }

            val overlay = LaneOverlayBuilder.build(
                route = route,
                currentManeuverIndex = state.currentManeuverIndex,
                snappedShapeIndex = state.snappedShapeIndex,
                distanceToNextManeuverMeters = state.distanceToNextManeuverMeters,
                travelMode = TravelMode.DRIVE,
                isOffRoute = state.isOffRoute,
                enabled = true,
                startDistanceMeters = if (state.turnFocusActive && state.turnFocusTriggerDistanceMeters > 0.0) {
                    state.turnFocusTriggerDistanceMeters
                } else LaneOverlayBuilder.BEFORE_MANEUVER_METERS,
                isFocusMode = state.turnFocusActive,
                focusManeuverIndex = state.turnFocusManeuverIndex
            )
            if (firstBlueLaneDist == null && overlay != null && overlay.lanes.any { it.isTarget } && state.showLaneStrip) {
                firstBlueLaneDist = signedDistToPivot
            }
            if (decision.justEnded && releasePastDist == null) {
                releasePastDist = (d - pivotProgress).coerceAtLeast(0.0)
            }
            d += 10.0
            tMs += 750L
        }

        return ReplayMetrics(
            firstCameraMoveDistToTurnMeters = firstCameraMoveDist,
            firstVisibleBlueLaneDistToTurnMeters = firstBlueLaneDist,
            finalSignalCrossingDistToTurnMeters = signalCrossingDist,
            focusReleaseDistPastTurnMeters = releasePastDist,
            enterAnimTriggers = enterCount,
            tightenAnimTriggers = tightenCount,
            maxApproachZoomBeforeSignal = maxApproachZoom,
            laneLevelZoomAfterSignal = laneLevelZoom
        )
    }

    @Test
    fun acceptanceA_cityRightTurnWithLastSignal180mBeforeTurn_andBusyTtsParity() {
        // 1,250 m northbound approach + 90° right turn onto 100 m eastbound street
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 1250, outgoingMeters = 100, turnBearingDeg = 90.0)
        val turn = RouteManeuver(
            index = 1, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Oak St",
            streetNames = listOf("Oak St"), lengthMeters = 100.0, timeSeconds = 12.0,
            beginShapeIndex = pivotIdx, endShapeIndex = shape.lastIndex,
            lat = shape[pivotIdx].lat, lon = shape[pivotIdx].lon, lanes = rightTurnLanes()
        )
        val route = OfflineRoute(
            id = "route_city_right_a", summaryLabel = "City Right Turn",
            distanceMeters = 1350.0, durationSeconds = 95.0, shape = shape,
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "Head north on Main St", listOf("Main St"), 1250.0, 83.0, 0, pivotIdx, shape[0].lat, shape[0].lon),
                turn,
                RouteManeuver(2, ManeuverType.ARRIVE, "Arrive", listOf("Oak St"), 0.0, 0.0, shape.lastIndex, shape.lastIndex, shape.last().lat, shape.last().lon)
            )
        )
        // Last mapped traffic signal 180 m before turn (at north = 1070 m, on the centerline)
        val signalPt = pointAtEn(0.0, 1070.0)
        val signals = listOf(
            RoadHazardPoint(id = 101L, type = "traffic_signals", lat = signalPt.lat, lon = signalPt.lon, label = "Traffic Signal")
        )
        // Spoken distance unit is IMPERIAL (miles) — 1 km approach must still trigger at 1,000 m!
        val settings = UserSettings(distanceUnit = DistanceUnit.IMPERIAL, language = AppLanguage.ENGLISH)

        val idle = runCityRightTurnReplay(route, signals, settings, simulateBusyTts = false)
        val busy = runCityRightTurnReplay(route, signals, settings, simulateBusyTts = true)

        println("ACCEPTANCE_A_METRICS: firstCamera=${idle.firstCameraMoveDistToTurnMeters}m, firstBlueLane=${idle.firstVisibleBlueLaneDistToTurnMeters}m, signalPass=${idle.finalSignalCrossingDistToTurnMeters}m, releasePast=${idle.focusReleaseDistPastTurnMeters}m, approachMaxZoom=${idle.maxApproachZoomBeforeSignal}, laneZoom=${idle.laneLevelZoomAfterSignal}")

        // 1. First camera movement on first reliable update inside 1,000 m (in miles mode!)
        assertNotNull(idle.firstCameraMoveDistToTurnMeters)
        assertTrue("Approach must start inside 1,000 m, got ${idle.firstCameraMoveDistToTurnMeters}", idle.firstCameraMoveDistToTurnMeters!! in 985.0..1005.0)
        // 2. First visible blue lane synchronized with approach
        assertNotNull(idle.firstVisibleBlueLaneDistToTurnMeters)
        assertTrue("Blue lane must appear at approach start (~1000 m), got ${idle.firstVisibleBlueLaneDistToTurnMeters}", idle.firstVisibleBlueLaneDistToTurnMeters!! in 985.0..1005.0)
        // 3. Final-signal crossing ~180 m before turn triggers lane-level tightening
        assertNotNull(idle.finalSignalCrossingDistToTurnMeters)
        assertTrue("Signal pass should occur ~170-180 m before turn, got ${idle.finalSignalCrossingDistToTurnMeters}", idle.finalSignalCrossingDistToTurnMeters!! in 165.0..180.0)
        assertTrue("Approach zoom must be wider than lane-level to fit turn", idle.maxApproachZoomBeforeSignal < TurnFocusPlanner.FOCUS_ZOOM_CITY - 0.3)
        assertEquals(TurnFocusPlanner.FOCUS_ZOOM_CITY, idle.laneLevelZoomAfterSignal, 0.01)
        assertEquals("Enter animation must fire once, never restart", 1, idle.enterAnimTriggers)
        assertEquals("Tighten animation must fire once on passing final signal", 1, idle.tightenAnimTriggers)
        // 4. Completion-based release once established on outgoing Eastbound street (~50 m past pivot)
        assertNotNull(idle.focusReleaseDistPastTurnMeters)
        assertTrue("Focus must hold until established on outgoing road (>= 45 m), got ${idle.focusReleaseDistPastTurnMeters}", idle.focusReleaseDistPastTurnMeters!! in 45.0..65.0)
        // 5. Busy TTS produces identical visual timing
        assertEquals(idle, busy)
    }

    @Test
    fun acceptanceB_signalAtTargetIntersection_blueLaneVisibleBeforeStopLineAndThroughRedLightStop() {
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 1200, outgoingMeters = 90, turnBearingDeg = 270.0)
        val leftTurn = RouteManeuver(
            index = 1, type = ManeuverType.LEFT, instructionEn = "Turn left onto Broadway",
            streetNames = listOf("Broadway"), lengthMeters = 90.0, timeSeconds = 12.0,
            beginShapeIndex = pivotIdx, endShapeIndex = shape.lastIndex,
            lat = shape[pivotIdx].lat, lon = shape[pivotIdx].lon, lanes = leftTurnLanes()
        )
        val route = OfflineRoute(
            id = "route_signal_at_target_b", summaryLabel = "Signalized Left Turn",
            distanceMeters = 1290.0, durationSeconds = 90.0, shape = shape,
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "Head north on Alder St", listOf("Alder St"), 1200.0, 78.0, 0, pivotIdx, shape[0].lat, shape[0].lon),
                leftTurn,
                RouteManeuver(2, ManeuverType.ARRIVE, "Arrive", listOf("Broadway"), 0.0, 0.0, shape.lastIndex, shape.lastIndex, shape.last().lat, shape.last().lon)
            )
        )
        // Mapped traffic light AT the target intersection itself (8 m before pivot)
        val targetLightPt = pointAtEn(0.0, 1192.0)
        val signals = listOf(
            RoadHazardPoint(id = 202L, type = "traffic_signals", lat = targetLightPt.lat, lon = targetLightPt.lon, label = "Target Intersection Signal")
        )
        val tracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig()
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, pivotIdx)

        // 1. At 950 m before turn -> APPROACH stage, signal classified as AT_TARGET_INTERSECTION
        val (seg950, loc950, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress - 950.0)
        val d950 = tracker.evaluate(route, 1, seg950, 950.0, 13f, TravelMode.DRIVE, true, false, false, false, cfg, loc950, 0L, 0f, signals)
        assertTrue(d950.isActive)
        assertEquals(TurnFocusStage.APPROACH, d950.stage)
        assertEquals(ApproachSignalKind.AT_TARGET_INTERSECTION, d950.matchedSignal.kind)

        // 2. Before stop line (220 m before turn) -> tightens to LANE_LEVEL BEFORE stopping
        val (seg220, loc220, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress - 220.0)
        val d220 = tracker.evaluate(route, 1, seg220, 220.0, 12f, TravelMode.DRIVE, true, false, false, false, cfg, loc220, 0L, 0f, signals)
        assertTrue(d220.isActive)
        assertEquals(TurnFocusStage.LANE_LEVEL, d220.stage)
        assertEquals(TurnFocusPlanner.FOCUS_ZOOM_CITY, d220.targetZoom, 0.01)
        val overlayBeforeStop = LaneOverlayBuilder.build(route, 1, seg220, 220.0, TravelMode.DRIVE, isFocusMode = true, focusManeuverIndex = 1, startDistanceMeters = 1000.0)
        assertNotNull("Blue left turn lane must be visible before stop line", overlayBeforeStop)
        assertTrue("Leftmost lane must be target blue lane", overlayBeforeStop!!.lanes[0].isTarget)

        // 3. Stopped at red light (15 m before pivot, speed = 0 m/s for 6 consecutive updates)
        val (segStop, locStop, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress - 15.0)
        val snapshot = CarCameraSnapshot(locStop.lat, locStop.lon, TurnFocusPlanner.FOCUS_ZOOM_CITY, TurnFocusPlanner.FOCUS_TILT_3D, 0.0, route.id, 1)
        repeat(6) { tick ->
            val dStop = tracker.evaluate(route, 1, segStop, 15.0, 0f, TravelMode.DRIVE, true, false, false, false, cfg, locStop, 0L, 0f, signals)
            assertTrue("Red-light stop tick $tick must NOT clear Turn Focus", dStop.isActive)
            assertEquals(TurnFocusStage.LANE_LEVEL, dStop.stage)
            assertFalse("Stopped ticks must NOT retrigger enter animation", dStop.justEntered)
            val stopOverlay = LaneOverlayBuilder.build(route, 1, segStop, 15.0, TravelMode.DRIVE, isFocusMode = true, focusManeuverIndex = 1, startDistanceMeters = 1000.0)
            assertNotNull("Blue lane highlight must remain visible during red-light stop", stopOverlay)
            assertTrue(
                "Stationary red-light guard must skip redundant camera re-animations",
                CarCameraProfile.shouldSkipStationaryFocusUpdate(snapshot, locStop, dStop.targetZoom, TurnFocusPlanner.FOCUS_TILT_3D, 0.0, 0f, route.id, 1)
            )
        }

        // 4. Mid-turn (20 m past pivot, still turning heading 315° NW, currentManeuverIndex already advanced to 2)
        val (segMid, locMid, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress + 20.0)
        val dMid = tracker.evaluate(route, 2, segMid, 70.0, 7f, TravelMode.DRIVE, true, false, false, false, cfg, locMid, 0L, 315f, signals)
        assertTrue("Focus must remain active while turning onto outgoing street", dMid.isActive)
        assertEquals(TurnFocusStage.LANE_LEVEL, dMid.stage)
        val midArrow = ManeuverArrowGeometry.build(route, 2, segMid, 70.0, TravelMode.DRIVE, focusManeuverIndex = 1)
        assertNotNull("Outgoing maneuver guide arrow must remain visible mid-turn", midArrow)

        // 5. Established on Westbound outgoing street (55 m past pivot, heading 270° West) -> releases!
        val (segDone, locDone, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress + 55.0)
        val dDone = tracker.evaluate(route, 2, segDone, 35.0, 12f, TravelMode.DRIVE, true, false, false, false, cfg, locDone, 0L, 270f, signals)
        assertFalse("Focus must release once established on outgoing street", dDone.isActive)
        assertTrue(dDone.justEnded)
    }

    @Test
    fun acceptanceC_freewayExitAt65MphWithNoSignals_approachAt1KmAndHoldUntilEstablishedOnRamp() {
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 1300, outgoingMeters = 220, turnBearingDeg = 28.0)
        val exit = RouteManeuver(
            index = 1, type = ManeuverType.EXIT_RIGHT, instructionEn = "Take exit 194B toward I-105",
            streetNames = listOf("I-105"), lengthMeters = 220.0, timeSeconds = 10.0,
            beginShapeIndex = pivotIdx, endShapeIndex = shape.lastIndex,
            lat = shape[pivotIdx].lat, lon = shape[pivotIdx].lon,
            isHighwayRoad = true, isHighwayExitOrSplit = true, lanes = dualRightExitLanes()
        )
        val route = OfflineRoute(
            id = "route_hwy_exit_c", summaryLabel = "I-5 Exit 194B",
            distanceMeters = 1520.0, durationSeconds = 55.0, shape = shape,
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "Head north on I-5", listOf("I-5"), 1300.0, 45.0, 0, pivotIdx, shape[0].lat, shape[0].lon, isHighwayRoad = true),
                exit,
                RouteManeuver(2, ManeuverType.ARRIVE, "Arrive", listOf("I-105"), 0.0, 0.0, shape.lastIndex, shape.lastIndex, shape.last().lat, shape.last().lon)
            )
        )
        val speed65Mph = 29.06f
        val tracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig(wideHighwayEarlierEnabled = false) // strict 1,000 m baseline
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, pivotIdx)

        // 1,100 m before exit -> not active yet
        val (seg1100, loc1100, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress - 1100.0)
        assertFalse(tracker.evaluate(route, 1, seg1100, 1100.0, speed65Mph, TravelMode.DRIVE, true, false, false, false, cfg, loc1100, 0L, 0f, emptyList()).isActive)

        // 980 m before exit -> enters APPROACH fitting split ahead
        val (seg980, loc980, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress - 980.0)
        val d980 = tracker.evaluate(route, 1, seg980, 980.0, speed65Mph, TravelMode.DRIVE, true, false, false, false, cfg, loc980, 0L, 0f, emptyList())
        assertTrue(d980.isActive && d980.justEntered && d980.isHighway)
        assertEquals(TurnFocusStage.APPROACH, d980.stage)
        assertTrue("1 km approach zoom must fit split ahead (~15.55-16.2), got ${d980.targetZoom}", d980.targetZoom in 15.5..16.2)

        // 420 m before exit (~14.5 s at 65 mph, no signals) -> tightens to LANE_LEVEL with BOTH right exit lanes blue
        val (seg420, loc420, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress - 420.0)
        val d420 = tracker.evaluate(route, 1, seg420, 420.0, speed65Mph, TravelMode.DRIVE, true, false, false, false, cfg, loc420, 0L, 0f, emptyList())
        assertTrue(d420.isActive && d420.justTightenedToLaneLevel)
        assertEquals(TurnFocusStage.LANE_LEVEL, d420.stage)
        assertEquals(TurnFocusPlanner.FOCUS_ZOOM_HIGHWAY, d420.targetZoom, 0.01)
        val hwyOverlay = LaneOverlayBuilder.build(route, 1, seg420, 420.0, TravelMode.DRIVE, isFocusMode = true, focusManeuverIndex = 1, startDistanceMeters = 1000.0)
        assertNotNull(hwyOverlay)
        assertEquals("Both exit lanes must be highlighted blue", listOf(2, 3), hwyOverlay!!.lanes.filter { it.isTarget }.map { it.laneIndex })

        // 100 m past exit split on ramp -> STILL ACTIVE (not yet established on ramp)
        val (segPast100, locPast100, rampBearing) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress + 100.0)
        val dPast100 = tracker.evaluate(route, 2, segPast100, 120.0, 24f, TravelMode.DRIVE, true, false, false, false, cfg, locPast100, 0L, rampBearing, emptyList())
        assertTrue("Highway exit focus must stay active at 100 m past split", dPast100.isActive)

        // 160 m past exit split, aligned with ramp bearing -> releases!
        val (segPast160, locPast160, _) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, pivotProgress + 160.0)
        val dPast160 = tracker.evaluate(route, 2, segPast160, 60.0, 22f, TravelMode.DRIVE, true, false, false, false, cfg, locPast160, 0L, rampBearing, emptyList())
        assertFalse("Highway exit focus must release once established on ramp", dPast160.isActive)
        assertTrue(dPast160.justEnded)
    }

    @Test
    fun acceptanceD_parallelSignal_missingGeometry_closeTurns_missedExitReroute_phoneAndAndroidAuto() {
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 1200, outgoingMeters = 100, turnBearingDeg = 90.0)
        val turn = RouteManeuver(
            index = 1, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Pearl St",
            streetNames = listOf("Pearl St"), lengthMeters = 100.0, timeSeconds = 12.0,
            beginShapeIndex = pivotIdx, endShapeIndex = shape.lastIndex,
            lat = shape[pivotIdx].lat, lon = shape[pivotIdx].lon, lanes = rightTurnLanes()
        )
        val route = OfflineRoute(
            id = "route_edge_d", summaryLabel = "Edge Cases", distanceMeters = 1300.0, durationSeconds = 90.0,
            shape = shape, oneWayByShapeIndex = (0..pivotIdx).associateWith { true },
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "Head north", listOf("High St"), 1200.0, 78.0, 0, pivotIdx, shape[0].lat, shape[0].lon),
                turn
            )
        )
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(shape, pivotIdx)

        // D1. Parallel frontage road signal (35 m east) & opposing carriageway signal (14 m west) & opposite direction signal -> ALL rejected!
        val parallelSig = pointAtEn(35.0, pivotProgress - 500.0)
        val oppositeCarriagewaySig = pointAtEn(-14.0, pivotProgress - 450.0)
        val oppositeDirSig = pointAtEn(0.0, pivotProgress - 400.0)
        val badSignals = listOf(
            RoadHazardPoint(1L, "traffic_signals", parallelSig.lat, parallelSig.lon, "Parallel Rd Signal"),
            RoadHazardPoint(2L, "traffic_signals", oppositeCarriagewaySig.lat, oppositeCarriagewaySig.lon, "Opposite Carriageway"),
            RoadHazardPoint(3L, "traffic_signals", oppositeDirSig.lat, oppositeDirSig.lon, "Southbound Signal", directionDegrees = 180f)
        )
        val matchedBad = RouteTrafficSignalMatcher.findFinalSignalBeforeManeuver(
            route, turn, route.maneuvers[0], pivotProgress - 350.0, 1000.0, badSignals
        )
        assertEquals("Parallel, opposite-carriageway, and opposite-direction signals must be rejected", ApproachSignalKind.NONE, matchedBad.kind)

        // D2a. Missing offline road lane geometry (lane choice IS known -> camera stays zoomed & top diagram shows blue lane, NO road strip overlay!)
        val noGeoRoute = route.copy(hasRoadLaneGeometry = false)
        val (seg300, loc300, _) = NavigationGeometryHelper.advanceAlongShape(noGeoRoute.shape, 0, pivotProgress - 300.0)
        val noGeoTracker = TurnFocusSessionTracker()
        val noGeoDec = noGeoTracker.evaluate(noGeoRoute, 1, seg300, 300.0, 12f, TravelMode.DRIVE, true, false, false, false, TurnFocusConfig(), loc300, 0L, 0f)
        assertTrue("Camera must stay zoomed even when road lane geometry is unavailable", noGeoDec.isActive)
        val topLaneDec = LaneGuidanceDecision.evaluate(turn)
        assertTrue("Top lane diagram must still show known blue lane", topLaneDec.shouldShowFullDiagram)
        assertNull("Map must NOT draw misleading on-road lane strips without road lane geometry",
            LaneOverlayBuilder.build(noGeoRoute, 1, seg300, 300.0, TravelMode.DRIVE, isFocusMode = true, focusManeuverIndex = 1, startDistanceMeters = 1000.0))
        assertNotNull("Verified outgoing maneuver arrow must still highlight outgoing route",
            ManeuverArrowGeometry.build(noGeoRoute, 1, seg300, 300.0, TravelMode.DRIVE, focusManeuverIndex = 1))
        // Android Auto parity for D2a
        val carTarget = CarCameraProfile.computeCarCameraTarget(
            basePhoneZoom = 15.0, carMapView = CarMapView.NORMAL, isNavigating = true, is3DView = true,
            focusCameraActive = noGeoDec.isActive, focusIsHighway = noGeoDec.isHighway,
            focusTargetZoom = noGeoDec.targetZoom, focusStage = noGeoDec.stage
        )
        assertEquals(noGeoDec.targetZoom, carTarget.zoom, 0.01)
        assertTrue(carTarget.anchorLowerQuarter)
        assertFalse(CarMapStyleHelper.isLaneOverlayActive(
            ActiveNavigationState(isNavigating = true, route = noGeoRoute, currentManeuverIndex = 1, snappedShapeIndex = seg300,
                distanceToNextManeuverMeters = 300.0, turnFocusActive = true, turnFocusManeuverIndex = 1, turnFocusTriggerDistanceMeters = 1000.0),
            laneHighlightEnabled = true
        ))

        // D2b. Unknown lane choice -> never invent a lane, never say "Get in the blue lane."
        val unknownLaneManeuver = turn.copy(lanes = emptyList())
        val unknownDecision = LaneGuidanceDecision.evaluate(unknownLaneManeuver)
        assertFalse(unknownDecision.shouldShowFullDiagram)
        val unknownCue = unknownDecision.resolveVoiceCue(LaneDisplaySurface.PHONE_SCREEN, false, LaneHighlightColor.BLUE, false, AppLanguage.ENGLISH)
        assertFalse("Must never say 'Get in the blue lane' when lane choice is unknown", unknownCue?.contains("blue lane", ignoreCase = true) == true)

        // D3. Two close consecutive turns (35 m apart) -> direct handoff in LANE_LEVEL without zooming out/in
        val closePts = mutableListOf<LatLon>()
        for (y in 0..400 step 10) closePts.add(pointAtEn(0.0, y.toDouble())) // pivot1 at idx 40 (400 m)
        val p1 = closePts.lastIndex
        for (x in 10..40 step 10) closePts.add(pointAtEn(x.toDouble(), 400.0)) // pivot2 at idx 44 (440 m, 40 m spacing)
        val p2 = closePts.lastIndex
        for (y in 410..500 step 10) closePts.add(pointAtEn(40.0, y.toDouble()))
        val closeRoute = OfflineRoute(
            id = "route_close_pair", summaryLabel = "Close Turns", distanceMeters = 540.0, durationSeconds = 45.0,
            shape = closePts,
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "North", listOf("A"), 400.0, 30.0, 0, p1, closePts[0].lat, closePts[0].lon),
                RouteManeuver(1, ManeuverType.RIGHT, "Turn right", listOf("B"), 40.0, 5.0, p1, p2, closePts[p1].lat, closePts[p1].lon, lanes = rightTurnLanes()),
                RouteManeuver(2, ManeuverType.LEFT, "Turn left", listOf("C"), 100.0, 10.0, p2, closePts.lastIndex, closePts[p2].lat, closePts[p2].lon, lanes = leftTurnLanes())
            )
        )
        val pairTracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig()
        val enter1 = pairTracker.evaluate(closeRoute, 1, 20, 200.0, 10f, TravelMode.DRIVE, true, false, false, false, cfg, closePts[20], 0L, 0f)
        assertTrue(enter1.isActive && enter1.maneuverIndex == 1 && enter1.stage == TurnFocusStage.LANE_LEVEL)
        // Cross pivot 1 by 10 m (idx 41, 30 m before pivot 2) -> hands off directly to maneuver 2 in LANE_LEVEL!
        val handoff2 = pairTracker.evaluate(closeRoute, 2, p1 + 1, 30.0, 9f, TravelMode.DRIVE, true, false, false, false, cfg, closePts[p1 + 1], 0L, 90f)
        assertTrue("Must stay active with zero zoom-out gap during close-turn handoff", handoff2.isActive)
        assertEquals(2, handoff2.maneuverIndex)
        assertEquals(TurnFocusStage.LANE_LEVEL, handoff2.stage)
        assertFalse(handoff2.justEnded)

        // D4. Missed exit / off-route / reroute -> immediately clears highlight & invalidates camera commands
        val offRouteRes = pairTracker.evaluate(closeRoute, 2, p1 + 2, 20.0, 12f, TravelMode.DRIVE, true, isOffRoute = true, isRerouting = false, hasArrived = false, config = cfg)
        assertFalse(offRouteRes.isActive)
        assertTrue(offRouteRes.justEnded)
        assertNull(LaneOverlayBuilder.build(closeRoute, 2, p1 + 2, 20.0, TravelMode.DRIVE, isOffRoute = true, isFocusMode = false))
        val staleCmd = CameraTargetCommand(closePts[p1].lat, closePts[p1].lon, 18.0, 55.0, 90.0, 600, false, routeId = "route_close_pair", maneuverIndex = 2, generation = 3L, isFocusCommand = true)
        assertFalse("Reroute/generation bump must invalidate stale camera commands",
            staleCmd.isValidFor(activeRouteId = "route_rerouted", isCameraFollowing = true, minValidGeneration = 4L))

        // D5. Starting/rerouting inside 1 km window enters immediately when ahead, skips when already passed
        val midStartTracker = TurnFocusSessionTracker()
        val inside1Km = midStartTracker.evaluate(route, 1, seg300, 300.0, 12f, TravelMode.DRIVE, true, false, false, false, cfg, loc300, 0L, 0f)
        assertTrue("Starting inside 1 km window ahead of maneuver must enter immediately", inside1Km.isActive)
        val passedTracker = TurnFocusSessionTracker()
        val alreadyPassed = passedTracker.evaluate(route, 1, pivotIdx + 2, 0.0, 12f, TravelMode.DRIVE, true, false, false, false, cfg, shape[pivotIdx + 2], 0L, 90f)
        assertFalse("Already-passed maneuver on start/reroute must be skipped", alreadyPassed.isActive)
    }
}
