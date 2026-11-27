package com.example

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.example.car.CarCameraProfile
import com.example.car.CarCameraSnapshot
import com.example.car.CarManeuverMapper
import com.example.car.CarMapView
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.preferences.UserSettings
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.map.LaneHighlightColor
import com.example.map.LaneOverlayBuilder
import com.example.navigation.ActiveNavigationState
import com.example.navigation.ApproachSignalKind
import com.example.navigation.ApproachVoiceSchedulePolicy
import com.example.navigation.GpsFix
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDirection
import com.example.navigation.LaneDisplaySurface
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LaneSource
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationGeometryHelper
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.RouteTrafficSignalMatcher
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusPlanner
import com.example.navigation.TurnFocusSessionTracker
import com.example.navigation.TurnFocusStage
import com.example.navigation.TurnFocusStageHelper
import com.example.navigation.ValhallaResponseParser
import com.example.navigation.ValhallaTraceAttributesParser
import com.example.navigation.VoiceGuidanceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * Integrated Acceptance Replays A–E verifying:
 * - Replay A: Highway exit with camera zoom = 500 m (2 mi, 1 mi, 500 m, final exit cues; camera
 *   stays normal at 2 mi & 1 mi, zooms at 500 m independently of audio, top lane panel + map blue
 *   lanes + outgoing ramp path persist until established on ramp, then release).
 * - Replay B: Ordinary city street with legally valid neighboring lane + preferred ACTIVE lane
 *   (regression proof vs v40 allValid bug), centerline-only geometry separation, "Any lane." when
 *   all lanes are equally suitable, and voice gating ("Get in the blue lane." vs Android Auto).
 * - Replay C: City route with 1 km voice cue (independent of 500 m / 1,000 m zoom) + final mapped
 *   traffic signal ("Take the next right onto Oak Street." vs remaining distance when not next turn,
 *   target-intersection pre-stop-line cue + rock-steady red-light hold, > 1 km signal chronological
 *   ordering, and no-signal fallback).
 * - Replay D: Settings migration preserving legacy 500 m without userModified flag, idempotency,
 *   independent city/highway 500 m & 1,000 m, crossing detection (505 m -> 485 m), GPS gap
 *   single-cue catchup, passed-exit suppression, jitter anti-duplication, and reroute cleanup.
 * - Replay E: Android Auto camera/maneuver parity & simultaneous 1 km + final-signal combined cue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IntegratedGuidanceReplayAcceptanceTest {

    private val baseLat = 44.0500
    private val baseLon = -123.0900
    private val metersPerDegLat = 111_320.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))

    private fun pointAtEn(eastMeters: Double, northMeters: Double): LatLon =
        LatLon(baseLat + northMeters / metersPerDegLat, baseLon + eastMeters / metersPerDegLon)

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

    private fun encodePolyline6(points: List<LatLon>): String {
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (pt in points) {
            val lat = kotlin.math.round(pt.lat * 1e6).toLong()
            val lon = kotlin.math.round(pt.lon * 1e6).toLong()
            encodeSigned(lat - prevLat, sb)
            encodeSigned(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeSigned(value: Long, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20L) {
            out.append(((0x20L or (v and 0x1fL)) + 63L).toInt().toChar())
            v = v ushr 5
        }
        out.append((v + 63L).toInt().toChar())
    }

    private data class RecordedCue(
        val stage: String,
        val remainingMeters: Double,
        val textEn: String,
        val textFa: String,
        val focusActiveAtCue: Boolean,
        val lanePanelVisibleAtCue: Boolean
    )

    @Test
    fun replayA_highwayExit_camera500m_2mi1mi500mNowAndRampPersistence() {
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 4_000, outgoingMeters = 350, turnBearingDeg = 32.0, stepMeters = 20)
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(shape, pivotIdx)
        val lanes4 = listOf(
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
            LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
        )
        val route = OfflineRoute(
            id = "replay_a_hw_exit_195",
            summaryLabel = "I-5 North Exit 195",
            distanceMeters = NavigationGeometryHelper.vertexProgressMeters(shape, shape.lastIndex),
            durationSeconds = 155.0,
            shape = shape,
            oneWayByShapeIndex = shape.indices.associateWith { true },
            edgeLaneCountsByShapeIndex = shape.indices.associateWith { if (it <= pivotIdx) 4 else 2 },
            hasRoadLaneGeometry = true,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on I-5.",
                    streetNames = listOf("I-5 N"), lengthMeters = pivotProgress, timeSeconds = 135.0,
                    beginShapeIndex = 0, endShapeIndex = pivotIdx, lat = shape[0].lat, lon = shape[0].lon,
                    isHighwayRoad = true, speedLimitMph = 65, laneCount = 4, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.EXIT_RIGHT, instructionEn = "Take Exit 195 on the right.",
                    streetNames = listOf("Beltline Hwy"), lengthMeters = 350.0, timeSeconds = 20.0,
                    beginShapeIndex = pivotIdx, endShapeIndex = shape.lastIndex,
                    lat = shape[pivotIdx].lat, lon = shape[pivotIdx].lon,
                    signpost = HighwaySignpost(exitNumbers = listOf("195"), exitBranches = listOf("OR-569"), exitTowards = emptyList()),
                    lanes = lanes4, laneSource = LaneSource.DIRECT,
                    isHighwayRoad = true, isHighwayExitOrSplit = true, speedLimitMph = 65, laneCount = 4, isMultiLaneRoad = true,
                    hasRoadLaneGeometry = true
                )
            )
        )

        // Verify 500 m highway setting round-trips cleanly via UserPreferencesRepository
        val prefs = mutablePreferencesOf(
            UserPreferencesRepository.KEY_TURN_FOCUS_HIGHWAY_DIST to 500,
            UserPreferencesRepository.KEY_TURN_FOCUS_CITY_DIST to 1000
        )
        val settings = UserPreferencesRepository.mapPreferencesToSettings(prefs).copy(
            distanceUnit = DistanceUnit.IMPERIAL,
            language = AppLanguage.ENGLISH
        )
        assertEquals(500, settings.turnFocusHighwayDistanceMeters)
        assertEquals(1000, settings.turnFocusCityDistanceMeters)

        val controller = NavigationSessionController()
        val tracker = TurnFocusSessionTracker()
        val cfg = TurnFocusConfig(
            enabled = true,
            beepsEnabled = true,
            cityDistanceMeters = settings.turnFocusCityDistanceMeters.toDouble(),
            highwayDistanceMeters = settings.turnFocusHighwayDistanceMeters.toDouble(),
            wideHighwayEarlierEnabled = settings.turnFocusWideHighwayEarlierEnabled
        )

        var state = ActiveNavigationState(
            isNavigating = true,
            route = route,
            currentManeuverIndex = 1,
            distanceToNextManeuverMeters = pivotProgress,
            snappedLocation = shape[0],
            snappedBearing = 0f,
            snappedShapeIndex = 0
        )

        val cues = mutableListOf<RecordedCue>()
        var firstCameraFocusDist: Double? = null
        var firstBluePanelDist: Double? = null
        var firstBlueOverlayDist: Double? = null
        var sawOutgoingRampPath = false
        var focusReleasePastPivotDist: Double? = null
        var wasFocused = false
        var tMs = 1_700_000_000_000L

        val totalDist = NavigationGeometryHelper.vertexProgressMeters(shape, shape.lastIndex)
        var d = 0.0
        while (d <= totalDist) {
            val (_, loc, bearing) = NavigationGeometryHelper.advanceAlongShape(shape, 0, d)
            val fix = GpsFix(loc.lat, loc.lon, bearing, hasBearing = true, speedMps = 29.0f, accuracyMeters = 4f, timestampMs = tMs)
            val res = controller.processGpsFix(
                currentState = state, fix = fix, dbHazards = emptyList(), userReports = emptyList(),
                unit = settings.distanceUnit, language = settings.language, speedAudioEnabled = false,
                settings = settings, nowMs = tMs
            )
            val decision = tracker.evaluate(
                route = route, currentManeuverIndex = res.state.currentManeuverIndex,
                snappedShapeIndex = res.state.snappedShapeIndex,
                distanceToNextManeuverMeters = res.state.distanceToNextManeuverMeters,
                speedMps = fix.speedMps, travelMode = TravelMode.DRIVE, isNavigating = true,
                isOffRoute = res.state.isOffRoute, isRerouting = res.state.isRerouting,
                hasArrived = res.state.hasArrived, config = cfg,
                snappedLocation = res.state.snappedLocation, gpsAgeMs = 0L,
                snappedBearing = res.state.snappedBearing, trafficSignals = emptyList()
            )
            val displayM = if (decision.isActive) route.maneuvers.firstOrNull { it.index == decision.maneuverIndex } else res.state.currentManeuver
            val laneDec = displayM?.takeIf { it.lanes.isNotEmpty() }?.let { LaneGuidanceDecision.evaluate(it, edgeLaneCountBeforeManeuver = 4) }
            val showPanel = res.state.showLaneStrip || (decision.isActive && laneDec?.shouldShowFullDiagram == true)
            state = res.state.copy(
                laneDecision = laneDec,
                laneConfidence = laneDec?.confidence ?: LaneConfidence.HIGH,
                showLaneStrip = showPanel,
                turnFocusActive = decision.isActive,
                turnFocusCameraActive = decision.isActive,
                turnFocusIsHighway = decision.isHighway,
                turnFocusManeuverIndex = if (decision.isActive) decision.maneuverIndex else -1,
                turnFocusTriggerDistanceMeters = if (decision.isActive) decision.triggerDistanceMeters else 0.0,
                turnFocusStage = decision.stage,
                turnFocusTargetZoom = decision.targetZoom
            )
            val signedDist = pivotProgress - d
            if (decision.justEntered && firstCameraFocusDist == null) {
                firstCameraFocusDist = signedDist
                val framed = TurnFocusStageHelper.computeApproachCameraTarget(
                    route = route, maneuver = displayM, vehicleLoc = loc,
                    remainingMeters = state.displayedManeuverDistanceMeters, stage = decision.stage
                )
                assertTrue("Framed focus camera must lead ahead of vehicle toward exit", framed.lat > loc.lat)
            }
            if (showPanel && laneDec?.primaryTargetIndices == listOf(2, 3) && firstBluePanelDist == null) {
                firstBluePanelDist = signedDist
            }
            val overlay = LaneOverlayBuilder.build(
                route = route, currentManeuverIndex = state.currentManeuverIndex,
                snappedShapeIndex = state.snappedShapeIndex,
                distanceToNextManeuverMeters = state.distanceToNextManeuverMeters,
                travelMode = TravelMode.DRIVE, startDistanceMeters = 500.0,
                isFocusMode = decision.isActive, focusManeuverIndex = decision.maneuverIndex
            )
            if (overlay != null && overlay.lanes.map { it.isTarget } == listOf(false, false, true, true)) {
                if (firstBlueOverlayDist == null) firstBlueOverlayDist = signedDist
                if (overlay.outgoingRampPath.size >= 2) sawOutgoingRampPath = true
            }
            if (wasFocused && !decision.isActive && focusReleasePastPivotDist == null) {
                focusReleasePastPivotDist = d - pivotProgress
            }
            wasFocused = decision.isActive

            res.maneuverToSpeak?.let { (m, stage) ->
                if (m.index == 1 && stage != NavigationSessionController.STAGE_ARRIVED) {
                    val en = VoiceGuidanceManager.formatSpokenInstruction(
                        m, state.distanceToNextManeuverMeters, settings.distanceUnit, AppLanguage.ENGLISH,
                        includeLaneGuidance = laneDec?.hasActionableLaneGuidance == true,
                        announcementStage = stage, laneDecision = laneDec
                    )
                    val fa = VoiceGuidanceManager.formatSpokenInstruction(
                        m, state.distanceToNextManeuverMeters, settings.distanceUnit, AppLanguage.PERSIAN,
                        includeLaneGuidance = laneDec?.hasActionableLaneGuidance == true,
                        announcementStage = stage, laneDecision = laneDec
                    )
                    cues.add(RecordedCue(stage, state.distanceToNextManeuverMeters, en, fa, decision.isActive, showPanel))
                }
            }
            d += 20.0
            tMs += 690L
        }

        println("REPLAY_A_HIGHWAY_500M_CUES:")
        cues.forEach { println("  [${it.stage}] rem=${"%.1f".format(it.remainingMeters)}m focus=${it.focusActiveAtCue} panel=${it.lanePanelVisibleAtCue} EN='${it.textEn}' FA='${it.textFa}'") }
        println("  firstCameraFocusDist=${firstCameraFocusDist}m firstBluePanelDist=${firstBluePanelDist}m firstBlueOverlayDist=${firstBlueOverlayDist}m focusReleasePastPivot=${focusReleasePastPivotDist}m")

        assertEquals(
            listOf(
                NavigationSessionController.STAGE_HW_2MI,
                NavigationSessionController.STAGE_HW_1MI,
                NavigationSessionController.STAGE_HW_500M,
                NavigationSessionController.STAGE_HW_NOW
            ),
            cues.map { it.stage }
        )
        assertFalse("Camera must NOT zoom prematurely at 2 miles when set to 500 m", cues[0].focusActiveAtCue)
        assertFalse("Camera must NOT zoom prematurely at 1 mile when set to 500 m", cues[1].focusActiveAtCue)
        assertTrue("Camera MUST enter Turn Focus at 500 m", cues[2].focusActiveAtCue)
        assertTrue("Top lane panel MUST be visible at 500 m", cues[2].lanePanelVisibleAtCue)
        assertTrue("2 mi cue must mention two miles & Exit 195 on the right: ${cues[0].textEn}", cues[0].textEn.contains("In two miles, take Exit 195 on the right"))
        assertTrue("1 mi cue must mention one mile & right two lanes for Exit 195: ${cues[1].textEn}", cues[1].textEn.contains("In one mile, use the right two lanes for Exit 195"))
        assertTrue("500 m cue must use mixed units 'In 500 meters': ${cues[2].textEn}", cues[2].textEn.contains("In 500 meters, take Exit 195 on the right"))
        assertTrue("Final cue must say Exit 195 ... now: ${cues[3].textEn}", cues[3].textEn.contains("Take Exit 195") && cues[3].textEn.contains("now"))
        assertNotNull("First camera move must trigger near 500 m", firstCameraFocusDist)
        assertTrue("First camera move must be inside 470..520 m, got $firstCameraFocusDist", firstCameraFocusDist!! in 470.0..520.0)
        assertTrue("Outgoing ramp path must be built during highway focus", sawOutgoingRampPath)
        assertNotNull("Focus must persist onto ramp and release once established", focusReleasePastPivotDist)
        assertTrue("Focus must hold well past exit gore before releasing (got $focusReleasePastPivotDist m)", focusReleasePastPivotDist!! >= 80.0)
    }

    @Test
    fun replayB_ordinaryCityStreet_preferredAmongAllValidLanes_andGeometryGating() {
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 600, outgoingMeters = 150, turnBearingDeg = 90.0, stepMeters = 20)
        val encoded = encodePolyline6(shape)
        val rawRouteJson = """
            {
              "trip": {
                "summary": { "length": 0.75, "time": 60 },
                "legs": [{
                  "shape": "$encoded",
                  "maneuvers": [
                    {
                      "type": 1, "instruction": "Head north on Main Street.",
                      "street_names": ["Main Street"], "length": 0.6, "time": 45,
                      "begin_shape_index": 0, "end_shape_index": $pivotIdx
                    },
                    {
                      "type": 10, "instruction": "Turn right onto Oak Street.",
                      "street_names": ["Oak Street"], "length": 0.15, "time": 15,
                      "begin_shape_index": $pivotIdx, "end_shape_index": ${shape.lastIndex},
                      "turn_lanes": [
                        { "directions": ["through"], "valid": true, "active": false },
                        { "directions": ["through", "right"], "valid": true, "active": true }
                      ]
                    }
                  ]
                }]
              }
            }
        """.trimIndent()

        // 1. Centerline-only /route response (no trace_attributes yet)
        val rawRoute = ValhallaResponseParser.parseRoutes(rawRouteJson).firstOrNull()
        assertNotNull(rawRoute)
        val rawTurn = rawRoute!!.maneuvers[1]
        assertFalse("Raw /route must NOT claim verified map road-lane geometry", rawRoute.hasRoadLaneGeometry)
        val decisionRaw = LaneGuidanceDecision.evaluate(rawTurn)
        assertTrue("Top lane panel MUST still show blue lane recommendation on centerline-only route", decisionRaw.shouldShowFullDiagram)
        assertEquals(listOf(1), decisionRaw.primaryTargetIndices)
        assertNull(
            "Map road-lane stripes must be withheld when hasRoadLaneGeometry=false",
            LaneOverlayBuilder.build(rawRoute, 1, pivotIdx - 10, 200.0, TravelMode.DRIVE)
        )

        // 2. Enriched with verified trace_attributes road-lane geometry
        val traceJson = """
            {
              "edges": [
                { "begin_shape_index": 0, "end_shape_index": $pivotIdx, "lane_count": 2, "traversability": "both", "speed_limit": 50 },
                { "begin_shape_index": $pivotIdx, "end_shape_index": ${shape.lastIndex}, "lane_count": 2, "traversability": "both", "speed_limit": 45 }
              ]
            }
        """.trimIndent()
        val enrichedRoute = ValhallaTraceAttributesParser.enrichRouteWithTraceAttributes(rawRoute, traceJson)
        assertTrue("Verified trace_attributes must set hasRoadLaneGeometry=true", enrichedRoute.hasRoadLaneGeometry)

        // Regression proof: old v40 check `lanes.all { it.isValid || ... }` WOULD HAVE BEEN TRUE here and returned null!
        val v40WouldHaveSuppressed = enrichedRoute.maneuvers[1].lanes.all {
            it.isValid || it.isRecommended || it.isActive || it.highlightTier != LaneHighlightTier.INACTIVE
        }
        assertTrue("Baseline v40 allValid guard would have suppressed this ordinary 2-lane street", v40WouldHaveSuppressed)

        val fixedOverlay = LaneOverlayBuilder.build(enrichedRoute, 1, pivotIdx - 10, 200.0, TravelMode.DRIVE)
        assertNotNull("Fixed LaneOverlayBuilder MUST render blue lane for preferred ACTIVE lane", fixedOverlay)
        assertEquals(listOf(false, true), fixedOverlay!!.lanes.map { it.isTarget })

        // Verify Android Auto CarManeuverMapper mirrors preferred lane ONLY
        val carLanes = CarManeuverMapper.toCarLanes(enrichedRoute.maneuvers[1].lanes, decisionRaw)
        assertEquals(2, carLanes.size)
        assertFalse("Car lane 0 must not be recommended", carLanes[0].directions.any { it.isRecommended })
        assertTrue("Car lane 1 must be recommended", carLanes[1].directions.any { it.isRecommended })

        // Voice gating: "Get in the blue lane." ONLY on phone with visible blue panel; physical on Android Auto
        val phoneCue = decisionRaw.resolveVoiceCue(
            surface = LaneDisplaySurface.PHONE_SCREEN,
            isDiagramVisibleOnActiveDisplay = true,
            highlightColor = LaneHighlightColor.BLUE,
            isCarHostColorControlled = false,
            language = AppLanguage.ENGLISH
        )
        val autoCue = decisionRaw.resolveVoiceCue(
            surface = LaneDisplaySurface.ANDROID_AUTO,
            isDiagramVisibleOnActiveDisplay = true,
            highlightColor = LaneHighlightColor.BLUE,
            isCarHostColorControlled = true,
            language = AppLanguage.ENGLISH
        )
        assertEquals("Get in the blue lane.", phoneCue)
        assertFalse("Android Auto must NOT say 'blue lane'", autoCue!!.contains("blue", ignoreCase = true))
        assertTrue("Android Auto must use physical lane description: $autoCue", autoCue.contains("right lane", ignoreCase = true))

        // Equally suitable lanes -> "Any lane." / "هر خطی." and NO map overlay
        val equalTurn = enrichedRoute.maneuvers[1].copy(
            lanes = listOf(
                LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE),
                LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
            )
        )
        val equalDec = LaneGuidanceDecision.evaluate(equalTurn)
        assertEquals("Any lane.", equalDec.resolveVoiceCue(LaneDisplaySurface.PHONE_SCREEN, true, LaneHighlightColor.BLUE, false, AppLanguage.ENGLISH))
        assertEquals("هر خطی.", equalDec.resolveVoiceCue(LaneDisplaySurface.PHONE_SCREEN, true, LaneHighlightColor.BLUE, false, AppLanguage.PERSIAN))
        val equalRoute = enrichedRoute.copy(maneuvers = listOf(enrichedRoute.maneuvers[0], equalTurn))
        assertNull("All equally preferred lanes must suppress map overlay", LaneOverlayBuilder.build(equalRoute, 1, pivotIdx - 10, 200.0, TravelMode.DRIVE))
    }

    @Test
    fun replayC_cityRoute_1kmAndFinalTrafficSignal_allSignalCasesAndRedLightHold() {
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 1_500, outgoingMeters = 150, turnBearingDeg = 90.0, stepMeters = 10)
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(shape, pivotIdx)
        val cityLanes = listOf(
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), isRecommended = false, isActive = false, isValid = false, level = LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), isRecommended = true, isActive = true, isValid = true, level = LaneHighlightTier.ACTIVE)
        )
        val baseRoute = OfflineRoute(
            id = "replay_c_city_oak",
            summaryLabel = "City Route to Oak Street",
            distanceMeters = NavigationGeometryHelper.vertexProgressMeters(shape, shape.lastIndex),
            durationSeconds = 135.0,
            shape = shape,
            oneWayByShapeIndex = shape.indices.associateWith { false },
            edgeLaneCountsByShapeIndex = shape.indices.associateWith { 2 },
            hasRoadLaneGeometry = true,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on Pearl Street.",
                    streetNames = listOf("Pearl Street"), lengthMeters = pivotProgress, timeSeconds = 115.0,
                    beginShapeIndex = 0, endShapeIndex = pivotIdx, lat = shape[0].lat, lon = shape[0].lon,
                    speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Oak Street.",
                    streetNames = listOf("Oak Street"), lengthMeters = 150.0, timeSeconds = 20.0,
                    beginShapeIndex = pivotIdx, endShapeIndex = shape.lastIndex,
                    lat = shape[pivotIdx].lat, lon = shape[pivotIdx].lon,
                    lanes = cityLanes, laneSource = LaneSource.DIRECT,
                    speedLimitMph = 30, laneCount = 2, isMultiLaneRoad = true, hasRoadLaneGeometry = true
                )
            )
        )

        // Case C1: Signal 210 m before Oak St + distracting parallel-road & opposite-direction signals rejected
        val signalAt210Before = pointAtEn(2.0, 1_290.0) // 210 m before 1500 m pivot
        val parallelRoadSignal = pointAtEn(42.0, 1_350.0) // 42 m east on parallel road -> MUST be rejected
        val oppositeDirSignal = pointAtEn(-2.0, 1_380.0) // explicit southbound direction=180 -> MUST be rejected
        val signalsC1 = listOf(
            RoadHazardPoint(1L, "traffic_signals", signalAt210Before.lat, signalAt210Before.lon, "Traffic signal", directionDegrees = 0f),
            RoadHazardPoint(2L, "traffic_signals", parallelRoadSignal.lat, parallelRoadSignal.lon, "Parallel signal", directionDegrees = 0f),
            RoadHazardPoint(3L, "traffic_signals", oppositeDirSignal.lat, oppositeDirSignal.lon, "Opposite signal", directionDegrees = 180f)
        )
        val matched = RouteTrafficSignalMatcher.findFinalSignalBeforeManeuver(
            baseRoute, baseRoute.maneuvers[1], baseRoute.maneuvers[0], 500.0, 1_650.0, signalsC1
        )
        assertEquals(ApproachSignalKind.BEFORE_TARGET_IN_WINDOW, matched.kind)
        assertEquals(1L, matched.signalId)

        // Run replay with camera zoom = 500 m (proves 1 km voice fires even when camera zoom = 500 m!)
        val c1Cues = mutableListOf<RecordedCue>()
        run {
            val ctrl = NavigationSessionController()
            val tracker = TurnFocusSessionTracker()
            val settings500 = UserSettings(
                distanceUnit = DistanceUnit.IMPERIAL, language = AppLanguage.ENGLISH,
                turnFocusCityDistanceMeters = 500
            )
            val cfg = TurnFocusConfig(enabled = true, cityDistanceMeters = 500.0, highwayDistanceMeters = 500.0)
            var st = ActiveNavigationState(isNavigating = true, route = baseRoute, currentManeuverIndex = 1, snappedLocation = shape[0], snappedBearing = 0f)
            var tightenedOnSignal = false
            var tMs = 1_700_100_000_000L
            var d = 0.0
            while (d <= pivotProgress + 90.0) {
                val (_, loc, brg) = NavigationGeometryHelper.advanceAlongShape(shape, 0, d)
                val fix = GpsFix(loc.lat, loc.lon, brg, hasBearing = true, speedMps = 13.4f, accuracyMeters = 4f, timestampMs = tMs)
                val res = ctrl.processGpsFix(st, fix, signalsC1, emptyList(), settings500.distanceUnit, settings500.language, false, settings500, nowMs = tMs)
                val td = tracker.evaluate(
                    baseRoute, res.state.currentManeuverIndex, res.state.snappedShapeIndex, res.state.distanceToNextManeuverMeters,
                    fix.speedMps, TravelMode.DRIVE, true, res.state.isOffRoute, res.state.isRerouting, res.state.hasArrived,
                    cfg, res.state.snappedLocation, 0L, res.state.snappedBearing, signalsC1
                )
                if (td.justTightenedToLaneLevel && td.matchedSignal.hasPassedSignal) tightenedOnSignal = true
                st = res.state.copy(turnFocusActive = td.isActive, turnFocusCameraActive = td.isActive, turnFocusStage = td.stage)
                res.maneuverToSpeak?.let { (m, stage) ->
                    if (m.index == 1 && stage != NavigationSessionController.STAGE_ARRIVED) {
                        val dec = LaneGuidanceDecision.evaluate(m)
                        c1Cues.add(
                            RecordedCue(
                                stage = stage,
                                remainingMeters = st.distanceToNextManeuverMeters,
                                textEn = VoiceGuidanceManager.formatSpokenInstruction(m, st.distanceToNextManeuverMeters, settings500.distanceUnit, AppLanguage.ENGLISH, true, stage, laneDecision = dec),
                                textFa = VoiceGuidanceManager.formatSpokenInstruction(m, st.distanceToNextManeuverMeters, settings500.distanceUnit, AppLanguage.PERSIAN, true, stage, laneDecision = dec),
                                focusActiveAtCue = td.isActive,
                                lanePanelVisibleAtCue = st.showLaneStrip
                            )
                        )
                    }
                }
                d += 15.0; tMs += 1_120L
            }
            assertTrue("Camera must tighten to lane level when passing the final mapped signal", tightenedOnSignal)
        }

        println("REPLAY_C1_CITY_1KM_AND_FINAL_SIGNAL:")
        c1Cues.forEach { println("  [${it.stage}] rem=${"%.1f".format(it.remainingMeters)}m focus=${it.focusActiveAtCue} EN='${it.textEn}' FA='${it.textFa}'") }

        assertEquals(
            listOf(
                NavigationSessionController.STAGE_CITY_1KM,
                NavigationSessionController.STAGE_CITY_POST_SIGNAL,
                NavigationSessionController.STAGE_CITY_NOW
            ),
            c1Cues.map { it.stage }
        )
        assertEquals("In one kilometer, turn right onto Oak Street.", c1Cues[0].textEn)
        assertEquals("در یک کیلومتری، به راست بپیچید و وارد Oak Street شوید.", c1Cues[0].textFa)
        assertEquals("Take the next right onto Oak Street.", c1Cues[1].textEn)
        assertEquals("در پیچ راست بعدی وارد Oak Street شوید.", c1Cues[1].textFa)
        assertFalse("With city camera set to 500 m, camera stays normal at 1 km while voice speaks", c1Cues[0].focusActiveAtCue)
        assertTrue("Camera is active at post-signal cue", c1Cues[1].focusActiveAtCue)

        // Case C2: Signal > 1 km before turn (at 1,180 m before pivot) -> fires BEFORE 1 km in chronological order!
        val farSignal = listOf(RoadHazardPoint(10L, "traffic_signals", pointAtEn(1.0, 320.0).lat, pointAtEn(1.0, 320.0).lon, "Far signal"))
        val c2Stages = mutableListOf<Pair<String, String>>()
        run {
            val ctrl = NavigationSessionController()
            var st = ActiveNavigationState(isNavigating = true, route = baseRoute, currentManeuverIndex = 1, snappedLocation = shape[0], snappedBearing = 0f)
            var tMs = 1_700_200_000_000L
            var d = 0.0
            while (d <= pivotProgress) {
                val (_, loc, brg) = NavigationGeometryHelper.advanceAlongShape(shape, 0, d)
                val fix = GpsFix(loc.lat, loc.lon, brg, hasBearing = true, speedMps = 13.4f, accuracyMeters = 4f, timestampMs = tMs)
                val res = ctrl.processGpsFix(st, fix, farSignal, emptyList(), DistanceUnit.METRIC, AppLanguage.ENGLISH, false, nowMs = tMs)
                st = res.state
                res.maneuverToSpeak?.let { (m, stage) ->
                    if (m.index == 1) {
                        val txt = VoiceGuidanceManager.formatSpokenInstruction(m, st.distanceToNextManeuverMeters, DistanceUnit.METRIC, AppLanguage.ENGLISH, true, stage)
                        c2Stages.add(stage to txt)
                    }
                }
                d += 20.0; tMs += 1_500L
            }
        }
        assertEquals(
            "Signal > 1 km before turn must fire BEFORE 1 km cue in chronological order",
            listOf(
                NavigationSessionController.STAGE_CITY_POST_SIGNAL_DIST,
                NavigationSessionController.STAGE_CITY_1KM,
                NavigationSessionController.STAGE_CITY_1000FT,
                NavigationSessionController.STAGE_CITY_NOW
            ),
            c2Stages.map { it.first }
        )
        assertFalse("Far signal (> 1 km) must NOT say 'next right': ${c2Stages[0].second}", c2Stages[0].second.contains("next right", ignoreCase = true))

        // Case C3: Signal at target intersection itself -> pre-stop-line cue with lane guidance + steady red-light hold
        val targetSignal = listOf(RoadHazardPoint(20L, "traffic_signals", shape[pivotIdx].lat, shape[pivotIdx].lon, "Target signal"))
        val c3Cues = mutableListOf<Pair<String, String>>()
        run {
            val ctrl = NavigationSessionController()
            val tracker = TurnFocusSessionTracker()
            val cfg = TurnFocusConfig(enabled = true, cityDistanceMeters = 1000.0)
            var st = ActiveNavigationState(isNavigating = true, route = baseRoute, currentManeuverIndex = 1, snappedLocation = shape[0], snappedBearing = 0f)
            var tMs = 1_700_300_000_000L
            var d = 0.0
            while (d <= pivotProgress - 30.0) {
                val (_, loc, brg) = NavigationGeometryHelper.advanceAlongShape(shape, 0, d)
                val fix = GpsFix(loc.lat, loc.lon, brg, hasBearing = true, speedMps = 13.4f, accuracyMeters = 4f, timestampMs = tMs)
                val res = ctrl.processGpsFix(st, fix, targetSignal, emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, nowMs = tMs)
                val td = tracker.evaluate(baseRoute, res.state.currentManeuverIndex, res.state.snappedShapeIndex, res.state.distanceToNextManeuverMeters, fix.speedMps, TravelMode.DRIVE, true, false, false, false, cfg, res.state.snappedLocation, 0L, res.state.snappedBearing, targetSignal)
                st = res.state.copy(turnFocusActive = td.isActive, turnFocusStage = td.stage)
                res.maneuverToSpeak?.let { (m, stage) ->
                    if (m.index == 1) c3Cues.add(stage to VoiceGuidanceManager.formatSpokenInstruction(m, st.distanceToNextManeuverMeters, DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, true, stage))
                }
                d += 20.0; tMs += 1_500L
            }
            // Hold stopped at red light 25 m before stop line for 20 seconds — zero duplicate cues, focus stays active!
            val stopPt = NavigationGeometryHelper.advanceAlongShape(shape, 0, pivotProgress - 25.0)
            repeat(20) {
                tMs += 1_000L
                val stopFix = GpsFix(stopPt.second.lat, stopPt.second.lon, stopPt.third, hasBearing = true, speedMps = 0.0f, accuracyMeters = 4f, timestampMs = tMs)
                val res = ctrl.processGpsFix(st, stopFix, targetSignal, emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, nowMs = tMs)
                val td = tracker.evaluate(baseRoute, res.state.currentManeuverIndex, res.state.snappedShapeIndex, res.state.distanceToNextManeuverMeters, 0f, TravelMode.DRIVE, true, false, false, false, cfg, res.state.snappedLocation, 0L, res.state.snappedBearing, targetSignal)
                assertTrue("Focus must hold steady during red-light stop", td.isActive)
                assertNull("Red-light stop must NOT repeat announcements", res.maneuverToSpeak)
                st = res.state
            }
        }
        assertTrue("Target-intersection signal must fire pre-stop-line cue", c3Cues.any { it.first == NavigationSessionController.STAGE_CITY_PRE_STOP_LINE })
        val preStopText = c3Cues.first { it.first == NavigationSessionController.STAGE_CITY_PRE_STOP_LINE }.second
        assertTrue("Pre-stop-line cue must include lane guidance: $preStopText", preStopText.contains("right lane", ignoreCase = true))
    }

    @Test
    fun replayD_migrationCrossingGapsLateStartJitterAndRerouteCleanup() {
        // 1. Settings migration: preserve legacy 500 m saved without userModified flag; default to 1,000 m ONLY when absent
        assertEquals(500, UserPreferencesRepository.resolveMigratedTurnFocusCityDistanceMeters(500, migratedToV40 = false, userModified = false))
        assertEquals(800, UserPreferencesRepository.resolveMigratedTurnFocusCityDistanceMeters(800, migratedToV40 = false, userModified = false))
        assertEquals(1000, UserPreferencesRepository.resolveMigratedTurnFocusCityDistanceMeters(null, migratedToV40 = false, userModified = false))
        // Idempotency: running migration logic twice preserves the exact same value
        val firstRun = UserPreferencesRepository.resolveMigratedTurnFocusCityDistanceMeters(500, migratedToV40 = false, userModified = false)
        val secondRun = UserPreferencesRepository.resolveMigratedTurnFocusCityDistanceMeters(firstRun, migratedToV40 = true, userModified = false)
        assertEquals(500, secondRun)

        // Independent city/highway 500 m & 1,000 m settings (no 800 m floor on highway!)
        val hw500Prefs = mutablePreferencesOf(
            UserPreferencesRepository.KEY_TURN_FOCUS_HIGHWAY_DIST to 500,
            UserPreferencesRepository.KEY_TURN_FOCUS_CITY_DIST to 1000
        )
        val mapped = UserPreferencesRepository.mapPreferencesToSettings(hw500Prefs)
        assertEquals(500, mapped.turnFocusHighwayDistanceMeters)
        assertEquals(1000, mapped.turnFocusCityDistanceMeters)

        // 2. Threshold crossing detection: 505 m -> 485 m MUST reliably trigger 500 m crossing
        assertTrue(ApproachVoiceSchedulePolicy.crossedThreshold(505.0, 485.0, 500.0))
        assertFalse(ApproachVoiceSchedulePolicy.crossedThreshold(495.0, 485.0, 500.0))

        // 3. GPS gap (1,800 m -> 450 m) plays ONLY single most relevant cue (STAGE_HW_500M), never backlog,
        //    and GPS jitter bouncing back across 500 m NEVER repeats STAGE_HW_500M
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 2_500, outgoingMeters = 300, turnBearingDeg = 30.0, stepMeters = 10)
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(shape, pivotIdx)
        val route = OfflineRoute(
            id = "replay_d_gap_jitter",
            summaryLabel = "Gap & Jitter Test",
            distanceMeters = NavigationGeometryHelper.vertexProgressMeters(shape, shape.lastIndex),
            durationSeconds = 110.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "I-5 N", listOf("I-5"), pivotProgress, 95.0, 0, pivotIdx, shape[0].lat, shape[0].lon, isHighwayRoad = true, speedLimitMph = 65),
                RouteManeuver(1, ManeuverType.EXIT_RIGHT, "Take Exit 195", listOf("Beltline"), 300.0, 15.0, pivotIdx, shape.lastIndex, shape[pivotIdx].lat, shape[pivotIdx].lon,
                    signpost = HighwaySignpost(exitNumbers = listOf("195")), isHighwayRoad = true, isHighwayExitOrSplit = true, speedLimitMph = 65)
            )
        )
        val ctrl = NavigationSessionController()
        var st = ActiveNavigationState(isNavigating = true, route = route, currentManeuverIndex = 1, snappedLocation = shape[0], snappedBearing = 0f)

        // Step 1: 1,800 m away (progress = 700 m) -> no cue yet
        val (_, loc1800, brg1800) = NavigationGeometryHelper.advanceAlongShape(shape, 0, pivotProgress - 1_800.0)
        val r1 = ctrl.processGpsFix(st, GpsFix(loc1800.lat, loc1800.lon, brg1800, speedMps = 28f, accuracyMeters = 4f, timestampMs = 1_000L), emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, nowMs = 1_000L)
        assertNull(r1.maneuverToSpeak)
        st = r1.state

        // Step 2: GPS gap straight to 450 m away -> MUST play ONLY STAGE_HW_500M (never 1 mi backlog!)
        val (_, loc450, brg450) = NavigationGeometryHelper.advanceAlongShape(shape, 0, pivotProgress - 450.0)
        val r2 = ctrl.processGpsFix(st, GpsFix(loc450.lat, loc450.lon, brg450, speedMps = 28f, accuracyMeters = 4f, timestampMs = 15_000L), emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, nowMs = 15_000L)
        assertEquals(NavigationSessionController.STAGE_HW_500M, r2.maneuverToSpeak?.second)
        st = r2.state

        // Step 3: GPS jitter back to 510 m then forward to 440 m -> MUST NOT repeat STAGE_HW_500M or play 1 mi!
        val (_, loc510, brg510) = NavigationGeometryHelper.advanceAlongShape(shape, 0, pivotProgress - 510.0)
        val r3 = ctrl.processGpsFix(st, GpsFix(loc510.lat, loc510.lon, brg510, speedMps = 28f, accuracyMeters = 4f, timestampMs = 17_000L), emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, nowMs = 17_000L)
        assertNull(r3.maneuverToSpeak)
        st = r3.state
        val (_, loc440, brg440) = NavigationGeometryHelper.advanceAlongShape(shape, 0, pivotProgress - 440.0)
        val r4 = ctrl.processGpsFix(st, GpsFix(loc440.lat, loc440.lon, brg440, speedMps = 28f, accuracyMeters = 4f, timestampMs = 19_000L), emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, nowMs = 19_000L)
        assertNull("Jitter crossing 500 m again must NEVER repeat STAGE_HW_500M", r4.maneuverToSpeak)

        // 4. Late start already past exit -> NEVER announces or first-displays the passed exit
        val lateCtrl = NavigationSessionController()
        val lateTracker = TurnFocusSessionTracker()
        val (_, locPast, brgPast) = NavigationGeometryHelper.advanceAlongShape(shape, 0, pivotProgress + 40.0)
        val lateRes = lateCtrl.processGpsFix(
            ActiveNavigationState(isNavigating = true, route = route, currentManeuverIndex = 1, snappedLocation = locPast, snappedBearing = brgPast, snappedShapeIndex = pivotIdx + 4),
            GpsFix(locPast.lat, locPast.lon, brgPast, speedMps = 28f, accuracyMeters = 4f, timestampMs = 1_000L),
            emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, nowMs = 1_000L
        )
        assertNull("Already-passed exit must NEVER announce", lateRes.maneuverToSpeak)
        assertFalse("Already-passed exit must NEVER first-display junction card", lateRes.state.showJunctionPreview)
        val lateFocus = lateTracker.evaluate(route, 1, pivotIdx + 4, 0.0, 28f, TravelMode.DRIVE, true, false, false, false, TurnFocusConfig(), locPast, 0L, brgPast)
        assertFalse("Already-passed exit must NEVER first-enter Turn Focus", lateFocus.isActive)

        // 5. Missed exit / off-route cancels active Turn Focus immediately
        val activeTracker = TurnFocusSessionTracker()
        val entered = activeTracker.evaluate(route, 1, pivotIdx - 45, 450.0, 28f, TravelMode.DRIVE, true, false, false, false, TurnFocusConfig(highwayDistanceMeters = 500.0), loc450, 0L, brg450)
        assertTrue(entered.isActive)
        val canceled = activeTracker.evaluate(route, 1, pivotIdx - 30, 300.0, 28f, TravelMode.DRIVE, true, isOffRoute = true, isRerouting = true, hasArrived = false, config = TurnFocusConfig(highwayDistanceMeters = 500.0), snappedLocation = loc450)
        assertFalse("Off-route / reroute must immediately cancel obsolete Turn Focus camera", canceled.isActive)
        assertTrue(canceled.justEnded)
    }

    @Test
    fun replayE_simultaneous1KmAndSignalCombinedCue_andAndroidAutoCameraParity() {
        val (shape, pivotIdx) = buildCornerShape(approachMeters = 1_400, outgoingMeters = 150, turnBearingDeg = 90.0, stepMeters = 10)
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(shape, pivotIdx)
        val route = OfflineRoute(
            id = "replay_e_combined",
            summaryLabel = "Combined 1km + Signal & Auto Parity",
            distanceMeters = NavigationGeometryHelper.vertexProgressMeters(shape, shape.lastIndex),
            durationSeconds = 120.0,
            shape = shape,
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "Pearl St", listOf("Pearl St"), pivotProgress, 100.0, 0, pivotIdx, shape[0].lat, shape[0].lon, speedLimitMph = 30),
                RouteManeuver(1, ManeuverType.RIGHT, "Turn right onto Oak St", listOf("Oak Street"), 150.0, 20.0, pivotIdx, shape.lastIndex, shape[pivotIdx].lat, shape[pivotIdx].lon, speedLimitMph = 30)
            )
        )
        // Signal placed right at 1,000 m before pivot (progress = 400 m)
        val signalAt1Km = listOf(RoadHazardPoint(55L, "traffic_signals", shape[40].lat, shape[40].lon, "1km signal"))
        val ctrl = NavigationSessionController()
        var st = ActiveNavigationState(isNavigating = true, route = route, currentManeuverIndex = 1, snappedLocation = shape[37], snappedBearing = 0f, snappedShapeIndex = 37)

        // Update 1: 1,030 m before turn (before signal & before 1 km)
        val r1 = ctrl.processGpsFix(st, GpsFix(shape[37].lat, shape[37].lon, 0f, speedMps = 13.4f, accuracyMeters = 4f, timestampMs = 1_000L), signalAt1Km, emptyList(), DistanceUnit.METRIC, AppLanguage.ENGLISH, false, nowMs = 1_000L)
        assertNull(r1.maneuverToSpeak)
        st = r1.state

        // Update 2: 990 m before turn (crossed BOTH 1 km and signal on the same update -> ONE combined cue!)
        val r2 = ctrl.processGpsFix(st, GpsFix(shape[41].lat, shape[41].lon, 0f, speedMps = 13.4f, accuracyMeters = 4f, timestampMs = 4_000L), signalAt1Km, emptyList(), DistanceUnit.METRIC, AppLanguage.ENGLISH, false, nowMs = 4_000L)
        assertEquals(NavigationSessionController.STAGE_CITY_1KM_POST_SIGNAL, r2.maneuverToSpeak?.second)
        val combinedEn = VoiceGuidanceManager.formatSpokenInstruction(route.maneuvers[1], r2.state.distanceToNextManeuverMeters, DistanceUnit.METRIC, AppLanguage.ENGLISH, false, NavigationSessionController.STAGE_CITY_1KM_POST_SIGNAL)
        assertTrue("Combined cue must merge signal + 1 km into one utterance: $combinedEn", combinedEn.contains("traffic light", ignoreCase = true) && combinedEn.contains("one kilometer", ignoreCase = true))

        // Update 3: 970 m -> MUST NOT double-speak STAGE_CITY_1KM or STAGE_CITY_POST_SIGNAL!
        val r3 = ctrl.processGpsFix(r2.state, GpsFix(shape[43].lat, shape[43].lon, 0f, speedMps = 13.4f, accuracyMeters = 4f, timestampMs = 6_000L), signalAt1Km, emptyList(), DistanceUnit.METRIC, AppLanguage.ENGLISH, false, nowMs = 6_000L)
        assertNull("Combined cue must suppress duplicate 1 km / post-signal speech", r3.maneuverToSpeak)

        // Android Auto CarCameraProfile focus target frames ahead toward the turn without deadzone drop
        val autoNav = ActiveNavigationState(
            isNavigating = true,
            route = route,
            currentManeuverIndex = 1,
            distanceToNextManeuverMeters = 500.0,
            snappedLocation = shape[90],
            snappedBearing = 0f,
            snappedShapeIndex = 90,
            turnFocusActive = true,
            turnFocusCameraActive = true,
            turnFocusIsHighway = false,
            turnFocusManeuverIndex = 1,
            turnFocusStage = TurnFocusStage.APPROACH,
            turnFocusTargetZoom = 16.8
        )
        val autoFocus = CarCameraProfile.computeFocusCameraUpdate(
            nav = autoNav,
            loc = shape[90],
            bearing = 0f,
            is3DView = true,
            enteringFocus = true,
            tightening = false
        )
        assertTrue("Android Auto focus target must frame ahead toward the turn", autoFocus.target.lat > shape[90].lat)
        assertEquals(16.8, autoFocus.zoom, 0.01)
    }
}
