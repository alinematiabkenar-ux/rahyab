package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.car.CarCameraProfile
import com.example.car.CarMapView
import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.HighwaySignpost
import com.example.navigation.LaneDirection
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationGeometryHelper
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusPlanner
import com.example.navigation.TurnFocusSessionTracker
import com.example.navigation.ValhallaResponseParser
import com.example.ui.CameraTargetCommand
import com.example.ui.RahYabViewModel
import com.example.ui.handleNavigationGpsFixImpl
import com.example.ui.invalidatePendingCameraCommandsImpl
import com.example.ui.pauseCameraFollowImpl
import com.example.ui.resetTurnFocusImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * End-to-end replay verification for Turn Focus camera timing, junction display gating,
 * close consecutive intersections (25–40 m), GPS gaps, reroute invalidation, and manual pan.
 *
 * Exercises the production navigation controller, Turn Focus tracker, ViewModel camera command
 * emitter, Android Auto CarCameraProfile, and a 60 Hz MapLibre camera animator + viewport
 * readability evaluator to measure what the driver actually sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TurnFocusAndJunctionTimingReplayTest {

    // --- 60 Hz Camera Animation & Viewport Readability Simulator ---

    private data class CameraPose(
        val lat: Double,
        val lon: Double,
        val zoom: Double,
        val tilt: Double,
        val bearing: Double,
        val anchorLowerQuarter: Boolean
    )

    private class CameraAnimationTimeline(
        initialPose: CameraPose,
        private val viewportWidthPx: Double = 1080.0,
        private val viewportHeightPx: Double = 2200.0,
        private val lowerQuarterTopPadFraction: Double = 0.42
    ) {
        var currentPose: CameraPose = initialPose
            private set
        private var animStartPose: CameraPose = initialPose
        private var animTargetPose: CameraPose? = null
        private var animStartMs: Long = 0L
        private var animDurationMs: Int = 0
        private var activeCommand: CameraTargetCommand? = null
        var canceledTransitionsCount: Int = 0
            private set
        var rejectedCommandsCount: Int = 0
            private set

        fun submitCommand(
            cmd: CameraTargetCommand,
            nowMs: Long,
            activeRouteId: String?,
            isCameraFollowing: Boolean,
            minValidGeneration: Long
        ): Boolean {
            if (!cmd.isValidFor(activeRouteId, isCameraFollowing, minValidGeneration)) {
                rejectedCommandsCount++
                return false
            }
            activeCommand = cmd
            animStartPose = currentPose
            val target = CameraPose(
                lat = cmd.lat,
                lon = cmd.lon,
                zoom = cmd.zoom,
                tilt = cmd.tilt,
                bearing = cmd.bearing,
                anchorLowerQuarter = cmd.anchorLowerQuarter
            )
            if (cmd.animationDurationMs <= 0) {
                currentPose = target
                animTargetPose = null
                animDurationMs = 0
            } else {
                animTargetPose = target
                animStartMs = nowMs
                animDurationMs = cmd.animationDurationMs
            }
            return true
        }

        fun cancelTransitions() {
            if (animTargetPose != null) {
                canceledTransitionsCount++
            }
            animTargetPose = null
            animDurationMs = 0
            activeCommand = null
        }

        fun isAnimating(): Boolean = animTargetPose != null

        fun advanceTo(nowMs: Long) {
            val target = animTargetPose ?: return
            if (animDurationMs <= 0) {
                currentPose = target
                animTargetPose = null
                return
            }
            val u = ((nowMs - animStartMs).toDouble() / animDurationMs.toDouble()).coerceIn(0.0, 1.0)
            // Cubic ease-out matching MapLibre animateCamera/easeTo
            val eased = 1.0 - (1.0 - u).pow(3.0)
            val dBearing = shortestAngleDelta(animStartPose.bearing, target.bearing)
            currentPose = CameraPose(
                lat = animStartPose.lat + (target.lat - animStartPose.lat) * eased,
                lon = animStartPose.lon + (target.lon - animStartPose.lon) * eased,
                zoom = animStartPose.zoom + (target.zoom - animStartPose.zoom) * eased,
                tilt = animStartPose.tilt + (target.tilt - animStartPose.tilt) * eased,
                bearing = (animStartPose.bearing + dBearing * eased + 360.0) % 360.0,
                anchorLowerQuarter = if (u >= 0.5) target.anchorLowerQuarter else animStartPose.anchorLowerQuarter
            )
            if (u >= 1.0) {
                animTargetPose = null
            }
        }

        /**
         * Returns true when the camera is zoomed tightly enough for street/branch separation
         * (`zoom >= minReadableZoom`) AND both the junction vertex and an outgoing-branch probe
         * project inside the visible screen frustum ahead of the vehicle puck.
         */
        fun isJunctionAndOutgoingReadable(
            junctionPoint: LatLon,
            outgoingProbe: LatLon,
            minReadableZoom: Double = 17.0
        ): Boolean {
            if (currentPose.zoom < minReadableZoom) return false
            return isPointInViewport(junctionPoint) && isPointInViewport(outgoingProbe)
        }

        private fun isPointInViewport(point: LatLon): Boolean {
            val metersPerPx = 156_543.03392 * cos(Math.toRadians(currentPose.lat)).coerceAtLeast(0.1) /
                2.0.pow(currentPose.zoom)
            val northMeters = (point.lat - currentPose.lat) * 111_320.0
            val eastMeters = (point.lon - currentPose.lon) * 111_320.0 * cos(Math.toRadians(currentPose.lat))
            val bRad = Math.toRadians(currentPose.bearing)
            // Forward (up on screen) and right (east on screen) in vehicle-aligned frame
            val forwardMeters = northMeters * cos(bRad) + eastMeters * sin(bRad)
            val rightMeters = -northMeters * sin(bRad) + eastMeters * cos(bRad)

            val padFrac = if (currentPose.anchorLowerQuarter) lowerQuarterTopPadFraction else 0.34
            val puckScreenY = viewportHeightPx * (0.5 + padFrac * 0.5)
            // 3D tilt stretches visible ground ahead by ~1 / cos(tilt)
            val tiltForwardScale = 1.0 / cos(Math.toRadians(currentPose.tilt.coerceIn(0.0, 58.0)))
            val forwardRangeMeters = (puckScreenY * metersPerPx) * tiltForwardScale
            val backwardRangeMeters = ((viewportHeightPx - puckScreenY) * metersPerPx)
            val halfWidthMeters = (viewportWidthPx * 0.5) * metersPerPx * (1.0 + (forwardMeters / forwardRangeMeters.coerceAtLeast(1.0)).coerceAtLeast(0.0) * 0.5)

            return forwardMeters in (-backwardRangeMeters)..forwardRangeMeters &&
                abs(rightMeters) <= halfWidthMeters
        }

        private fun shortestAngleDelta(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }
    }

    private data class ReplayMetrics(
        val label: String,
        val focusDecisionUpdateIdx: Int,
        val focusDecisionDistMeters: Double,
        val focusDecisionTimeSec: Double,
        val firstMoveDistMeters: Double,
        val firstMoveTimeSec: Double,
        val firstReadableDistMeters: Double,
        val firstReadableTimeSec: Double,
        val passedAtSec: Double
    ) {
        fun formatLine(): String =
            "$label | decision: update=#$focusDecisionUpdateIdx, %.1fm / %.2fs | firstMove: %.1fm / %.2fs | readable: %.1fm / %.2fs | passedAt: %.2fs"
                .format(
                    focusDecisionDistMeters, focusDecisionTimeSec,
                    firstMoveDistMeters, firstMoveTimeSec,
                    firstReadableDistMeters, firstReadableTimeSec,
                    passedAtSec
                )
    }

    // --- Route Builders ---

    private fun offsetLatLon(origin: LatLon, northMeters: Double, eastMeters: Double): LatLon {
        val lat = origin.lat + northMeters / 111_320.0
        val lon = origin.lon + eastMeters / (111_320.0 * cos(Math.toRadians(origin.lat)))
        return LatLon(lat, lon)
    }

    private fun buildSegmentPoints(from: LatLon, to: LatLon, stepMeters: Double = 10.0): List<LatLon> {
        val dist = com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(from.lat, from.lon, to.lat, to.lon)
        val steps = (dist / stepMeters).toInt().coerceAtLeast(1)
        return List(steps + 1) { i ->
            val f = i.toDouble() / steps.toDouble()
            LatLon(from.lat + (to.lat - from.lat) * f, from.lon + (to.lon - from.lon) * f)
        }
    }

    private fun buildCityRightTurnRoute(routeId: String = "city_right_turn"): OfflineRoute {
        val p0 = LatLon(44.0500, -123.0868)
        val turnPt = offsetLatLon(p0, northMeters = 700.0, eastMeters = 0.0)
        val endPt = offsetLatLon(turnPt, northMeters = 0.0, eastMeters = 250.0)
        val leg1 = buildSegmentPoints(p0, turnPt, 10.0) // indices 0..70
        val leg2 = buildSegmentPoints(turnPt, endPt, 10.0).drop(1) // indices 71..95
        val shape = leg1 + leg2
        val turnIdx = leg1.lastIndex // 70 (700 m)

        val maneuvers = listOf(
            RouteManeuver(0, ManeuverType.DEPART, "Head north on Willamette St", listOf("Willamette St"), 700.0, 52.0, 0, turnIdx, p0.lat, p0.lon),
            RouteManeuver(
                index = 1,
                type = ManeuverType.RIGHT,
                instructionEn = "Turn right onto 13th Ave",
                streetNames = listOf("13th Ave"),
                lengthMeters = 250.0,
                timeSeconds = 20.0,
                beginShapeIndex = turnIdx,
                endShapeIndex = shape.lastIndex,
                lat = shape[turnIdx].lat,
                lon = shape[turnIdx].lon,
                lanes = listOf(
                    LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), false, false, false),
                    LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), true, true, true)
                )
            ),
            RouteManeuver(2, ManeuverType.ARRIVE, "Arrive", listOf("13th Ave"), 0.0, 0.0, shape.lastIndex, shape.lastIndex, endPt.lat, endPt.lon)
        )
        return OfflineRoute(
            id = routeId,
            summaryLabel = "City Right Turn",
            distanceMeters = 950.0,
            durationSeconds = 72.0,
            shape = shape,
            maneuvers = maneuvers
        )
    }

    private fun buildHighwayExitRoute(routeId: String = "hwy_exit_65mph"): OfflineRoute {
        val p0 = LatLon(44.0400, -123.0468)
        val splitPt = offsetLatLon(p0, northMeters = 1500.0, eastMeters = 0.0)
        // Outgoing exit ramp angles 30 deg right (NE) for 400 m
        val rampEnd = offsetLatLon(splitPt, northMeters = 400.0 * cos(Math.toRadians(30.0)), eastMeters = 400.0 * sin(Math.toRadians(30.0)))
        val leg1 = buildSegmentPoints(p0, splitPt, 10.0) // 0..150
        val leg2 = buildSegmentPoints(splitPt, rampEnd, 10.0).drop(1) // 151..190
        val shape = leg1 + leg2
        val splitIdx = leg1.lastIndex // 150 (1500 m)

        val exitLanes = listOf(
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), false, false, false),
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), false, false, false),
            LaneInfo(listOf(LaneDirection.SLIGHT_RIGHT), listOf(LaneDirection.SLIGHT_RIGHT), true, true, true)
        )
        val maneuvers = listOf(
            RouteManeuver(0, ManeuverType.DEPART, "Head north on I-5", listOf("I-5"), 1500.0, 52.0, 0, splitIdx, p0.lat, p0.lon, isHighwayRoad = true),
            RouteManeuver(
                index = 1,
                type = ManeuverType.EXIT_RIGHT,
                instructionEn = "Take exit 194B toward I-105 W",
                streetNames = listOf("I-105 W"),
                lengthMeters = 400.0,
                timeSeconds = 18.0,
                beginShapeIndex = splitIdx,
                endShapeIndex = shape.lastIndex,
                lat = shape[splitIdx].lat,
                lon = shape[splitIdx].lon,
                isHighwayRoad = true,
                isHighwayExitOrSplit = true,
                lanes = exitLanes,
                signpost = HighwaySignpost(listOf("194B"), listOf("I-105 W"), listOf("Eugene"))
            ),
            RouteManeuver(2, ManeuverType.ARRIVE, "Arrive", listOf("I-105 W"), 0.0, 0.0, shape.lastIndex, shape.lastIndex, rampEnd.lat, rampEnd.lon, isHighwayRoad = true)
        )
        return OfflineRoute(
            id = routeId,
            summaryLabel = "I-5 Exit 194B",
            distanceMeters = 1900.0,
            durationSeconds = 70.0,
            shape = shape,
            maneuvers = maneuvers
        )
    }

    private fun buildCloseConsecutiveTurnsRoute(spacingMeters: Double = 30.0): OfflineRoute {
        val p0 = LatLon(44.0500, -123.0868)
        val turn1Pt = offsetLatLon(p0, northMeters = 600.0, eastMeters = 0.0)
        val turn2Pt = offsetLatLon(turn1Pt, northMeters = 0.0, eastMeters = spacingMeters)
        val endPt = offsetLatLon(turn2Pt, northMeters = 250.0, eastMeters = 0.0)

        val leg1 = buildSegmentPoints(p0, turn1Pt, 10.0) // 0..60 (600 m)
        val leg2 = buildSegmentPoints(turn1Pt, turn2Pt, 5.0).drop(1) // 61..66 (30 m)
        val leg3 = buildSegmentPoints(turn2Pt, endPt, 10.0).drop(1) // 67..91 (250 m)
        val shape = leg1 + leg2 + leg3
        val turn1Idx = leg1.lastIndex
        val turn2Idx = turn1Idx + leg2.size

        val maneuvers = listOf(
            RouteManeuver(0, ManeuverType.DEPART, "Head north on Oak St", listOf("Oak St"), 600.0, 55.0, 0, turn1Idx, p0.lat, p0.lon),
            RouteManeuver(1, ManeuverType.RIGHT, "Turn right onto 11th Ave", listOf("11th Ave"), spacingMeters, 3.0, turn1Idx, turn2Idx, shape[turn1Idx].lat, shape[turn1Idx].lon),
            RouteManeuver(2, ManeuverType.LEFT, "Turn left onto Pearl St", listOf("Pearl St"), 250.0, 23.0, turn2Idx, shape.lastIndex, shape[turn2Idx].lat, shape[turn2Idx].lon),
            RouteManeuver(3, ManeuverType.ARRIVE, "Arrive", listOf("Pearl St"), 0.0, 0.0, shape.lastIndex, shape.lastIndex, endPt.lat, endPt.lon)
        )
        return OfflineRoute(
            id = "close_pair_${spacingMeters.toInt()}m",
            summaryLabel = "Close Pair",
            distanceMeters = 600.0 + spacingMeters + 250.0,
            durationSeconds = 81.0,
            shape = shape,
            maneuvers = maneuvers
        )
    }

    private fun makeTraceAlongRoute(
        route: OfflineRoute,
        speedMps: Float,
        dtSec: Double,
        startProgressMeters: Double,
        endProgressMeters: Double,
        startClockMs: Long = 1_700_000_000_000L
    ): List<Pair<Long, GpsFix>> {
        val result = mutableListOf<Pair<Long, GpsFix>>()
        var dist = startProgressMeters
        var elapsedSec = 0.0
        while (dist <= endProgressMeters + 0.01) {
            val (_, pt, bearing) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, dist)
            val tMs = startClockMs + (elapsedSec * 1000.0).toLong()
            result.add(
                tMs to GpsFix(
                    lat = pt.lat,
                    lon = pt.lon,
                    bearing = bearing,
                    hasBearing = true,
                    speedMps = speedMps,
                    accuracyMeters = 4f,
                    timestampMs = tMs
                )
            )
            dist += speedMps * dtSec
            elapsedSec += dtSec
        }
        return result
    }

    private fun seedViewModelRoute(vm: RahYabViewModel, route: OfflineRoute, startLoc: LatLon) {
        vm.navController.reset()
        vm.resetTurnFocusImpl()
        vm.invalidatePendingCameraCommandsImpl()
        vm._isCameraFollowing.value = true
        vm._isHeadingUpMode.value = true
        vm._navigationState.value = ActiveNavigationState(
            isNavigating = true,
            route = route,
            destinationName = route.summaryLabel,
            currentManeuverIndex = 0,
            distanceToNextManeuverMeters = route.maneuvers.firstOrNull()?.lengthMeters ?: route.distanceMeters,
            remainingDistanceMeters = route.distanceMeters,
            remainingTimeSeconds = route.durationSeconds,
            snappedLocation = startLoc,
            snappedBearing = 0f,
            snappedShapeIndex = 0,
            travelMode = TravelMode.DRIVE
        )
    }

    // --- CASE 1: City right turn — Idle audio vs Speech busy for 4 s ---

    @Test
    fun case1_cityRightTurn_idleAudioVsSpeechBusy4s_identicalCameraTiming() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        val route = buildCityRightTurnRoute()
        val speedMps = 13.4f // 30 mph
        val trace = makeTraceAlongRoute(route, speedMps, dtSec = 1.0, startProgressMeters = 100.0, endProgressMeters = 780.0)
        val maneuverProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, route.maneuvers[1].beginShapeIndex)
        val junctionPt = route.shape[route.maneuvers[1].beginShapeIndex]
        val outgoingProbe = route.shape[(route.maneuvers[1].beginShapeIndex + 4).coerceAtMost(route.shape.lastIndex)]

        fun runCityReplay(label: String, busySpeechFor4s: Boolean): Pair<ReplayMetrics, CameraTargetCommand?> {
            val vm = RahYabViewModel(app)
            seedViewModelRoute(vm, route, trace.first().second.let { LatLon(it.lat, it.lon) })
            if (busySpeechFor4s) {
                // Force TTS manager into active-speaking state (4 s ongoing speech cue)
                vm.voiceManager.speakCustomText(
                    "In a quarter mile turn right onto 13th Avenue and continue past the intersection toward the university campus",
                    AppLanguage.ENGLISH
                )
            }
            val startT = trace.first().first
            val timeline = CameraAnimationTimeline(
                CameraPose(trace.first().second.lat, trace.first().second.lon, zoom = 16.5, tilt = 55.0, bearing = 0.0, anchorLowerQuarter = false)
            )
            var decisionIdx = -1
            var decisionDist = -1.0
            var decisionTime = -1.0
            var firstMoveDist = -1.0
            var firstMoveTime = -1.0
            var firstReadableDist = -1.0
            var firstReadableTime = -1.0
            var firstFocusCmd: CameraTargetCommand? = null
            var lastCmdNonce = -1L
            var passedSec = -1.0

            trace.forEachIndexed { idx, (tMs, fix) ->
                vm.handleNavigationGpsFixImpl(fix)
                val nav = vm.navigationState.value
                val cmd = vm.cameraCommand.value
                val remaining = (maneuverProgress - nav.snappedRouteProgressMeters).coerceAtLeast(0.0)
                val ttm = remaining / speedMps

                if (passedSec < 0 && nav.snappedRouteProgressMeters >= maneuverProgress) {
                    passedSec = (tMs - startT) / 1000.0
                }
                if (decisionIdx < 0 && nav.turnFocusActive && nav.turnFocusCameraActive && nav.turnFocusManeuverIndex == 1) {
                    decisionIdx = idx
                    decisionDist = remaining
                    decisionTime = ttm
                }
                if (cmd != null && cmd.nonce != lastCmdNonce) {
                    lastCmdNonce = cmd.nonce
                    if (cmd.isFocusCommand && firstFocusCmd == null) {
                        firstFocusCmd = cmd
                    }
                    timeline.submitCommand(
                        cmd = cmd,
                        nowMs = tMs,
                        activeRouteId = nav.route?.id,
                        isCameraFollowing = vm.isCameraFollowing.value,
                        minValidGeneration = vm.cameraGeneration.value
                    )
                }

                // Step 60 Hz frames until next GPS update (1000 ms)
                val zoomBeforeFocus = 16.5
                val stepEnd = tMs + 1000L
                var frameT = tMs + 17L
                while (frameT <= stepEnd) {
                    timeline.advanceTo(frameT)
                    val dtFromFix = (frameT - tMs) / 1000.0
                    val liveRemaining = (remaining - speedMps * dtFromFix).coerceAtLeast(0.0)
                    val liveTtm = liveRemaining / speedMps
                    if (decisionIdx >= 0 && firstMoveDist < 0 && timeline.currentPose.zoom >= zoomBeforeFocus + 0.02) {
                        firstMoveDist = liveRemaining
                        firstMoveTime = liveTtm
                    }
                    if (decisionIdx >= 0 && firstReadableDist < 0 &&
                        timeline.isJunctionAndOutgoingReadable(junctionPt, outgoingProbe, minReadableZoom = 17.5)
                    ) {
                        firstReadableDist = liveRemaining
                        firstReadableTime = liveTtm
                    }
                    frameT += 17L
                }
            }
            return ReplayMetrics(label, decisionIdx, decisionDist, decisionTime, firstMoveDist, firstMoveTime, firstReadableDist, firstReadableTime, passedSec) to firstFocusCmd
        }

        val (idleMetrics, idleCmd) = runCityReplay("Case1_Phone_IdleAudio", busySpeechFor4s = false)
        val (busyMetrics, busyCmd) = runCityReplay("Case1_Phone_BusySpeech4s", busySpeechFor4s = true)
        println(idleMetrics.formatLine())
        println(busyMetrics.formatLine())

        assertNotNull("Idle run must emit focus camera command", idleCmd)
        assertNotNull("Busy-speech run must emit focus camera command", busyCmd)
        assertEquals("Camera decision MUST happen on exact same navigation update regardless of busy speech",
            idleMetrics.focusDecisionUpdateIdx, busyMetrics.focusDecisionUpdateIdx)
        assertEquals("Focus decision distance must be identical", idleMetrics.focusDecisionDistMeters, busyMetrics.focusDecisionDistMeters, 0.01)
        assertEquals("First visible camera movement distance must be identical", idleMetrics.firstMoveDistMeters, busyMetrics.firstMoveDistMeters, 0.01)
        assertEquals("First readable frame distance must be identical", idleMetrics.firstReadableDistMeters, busyMetrics.firstReadableDistMeters, 0.01)
        assertTrue("First readable frame must occur well before passing maneuver", busyMetrics.firstReadableDistMeters > 200.0)
    }

    // --- CASE 2: Highway exit at 65 mph (Phone + Android Auto + late/sparse GPS fix) ---

    @Test
    fun case2_highwayExit65mph_phoneAndAndroidAuto_visibleBeforeSplit() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        val route = buildHighwayExitRoute()
        val speedMps = 29.06f // 65 mph
        val trace = makeTraceAlongRoute(route, speedMps, dtSec = 1.0, startProgressMeters = 200.0, endProgressMeters = 1700.0)
        val splitIdx = route.maneuvers[1].beginShapeIndex
        val splitProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, splitIdx)
        val splitPt = route.shape[splitIdx]
        val outgoingRampProbe = route.shape[(splitIdx + 5).coerceAtMost(route.shape.lastIndex)]

        // 2A. Phone path through RahYabViewModel
        val vm = RahYabViewModel(app)
        seedViewModelRoute(vm, route, trace.first().second.let { LatLon(it.lat, it.lon) })
        val phoneTimeline = CameraAnimationTimeline(
            CameraPose(trace.first().second.lat, trace.first().second.lon, zoom = 15.0, tilt = 55.0, bearing = 0.0, anchorLowerQuarter = false)
        )
        // 2B. Android Auto path through NavigationSessionController + TurnFocusSessionTracker + CarCameraProfile
        val autoController = NavigationSessionController()
        val autoTracker = TurnFocusSessionTracker()
        val autoSettings = UserSettings()
        var autoState = ActiveNavigationState(isNavigating = true, route = route, snappedLocation = LatLon(trace.first().second.lat, trace.first().second.lon))
        val autoTimeline = CameraAnimationTimeline(
            CameraPose(trace.first().second.lat, trace.first().second.lon, zoom = 14.2, tilt = 40.0, bearing = 0.0, anchorLowerQuarter = false),
            viewportWidthPx = 1280.0,
            viewportHeightPx = 720.0,
            lowerQuarterTopPadFraction = 0.38
        )

        var phoneDecIdx = -1; var phoneDecDist = -1.0; var phoneDecTime = -1.0
        var phoneMoveDist = -1.0; var phoneMoveTime = -1.0
        var phoneReadDist = -1.0; var phoneReadTime = -1.0
        var phoneJunctionCardDist = -1.0
        var autoDecIdx = -1; var autoDecDist = -1.0; var autoDecTime = -1.0
        var autoMoveDist = -1.0; var autoMoveTime = -1.0
        var autoReadDist = -1.0; var autoReadTime = -1.0
        var lastPhoneNonce = -1L
        var passedSec = -1.0
        val startT = trace.first().first

        trace.forEachIndexed { idx, (tMs, fix) ->
            // Phone step
            vm.handleNavigationGpsFixImpl(fix)
            val nav = vm.navigationState.value
            val remaining = (splitProgress - nav.snappedRouteProgressMeters).coerceAtLeast(0.0)
            val ttm = remaining / speedMps
            if (passedSec < 0 && nav.snappedRouteProgressMeters >= splitProgress) passedSec = (tMs - startT) / 1000.0
            if (phoneDecIdx < 0 && nav.turnFocusActive && nav.turnFocusCameraActive && nav.turnFocusManeuverIndex == 1) {
                phoneDecIdx = idx; phoneDecDist = remaining; phoneDecTime = ttm
            }
            if (phoneJunctionCardDist < 0 && nav.showJunctionPreview && nav.activeJunctionManeuverIndex == 1) {
                phoneJunctionCardDist = remaining
            }
            val cmd = vm.cameraCommand.value
            if (cmd != null && cmd.nonce != lastPhoneNonce) {
                lastPhoneNonce = cmd.nonce
                phoneTimeline.submitCommand(cmd, tMs, nav.route?.id, vm.isCameraFollowing.value, vm.cameraGeneration.value)
            }

            // Android Auto step
            val autoRes = autoController.processGpsFix(
                autoState, fix, emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, autoSettings, nowMs = tMs
            )
            autoState = autoTracker.applyToState(autoRes.state, speedMps, autoSettings)
            val autoRem = (splitProgress - autoState.snappedRouteProgressMeters).coerceAtLeast(0.0)
            val autoTtm = autoRem / speedMps
            val wasAutoFocused = autoDecIdx >= 0
            if (!wasAutoFocused && autoState.turnFocusCameraActive && autoState.turnFocusManeuverIndex == 1) {
                autoDecIdx = idx; autoDecDist = autoRem; autoDecTime = autoTtm
            }
            val carTarget = CarCameraProfile.computeCarCameraTarget(
                basePhoneZoom = 15.0,
                carMapView = CarMapView.NORMAL,
                isNavigating = true,
                is3DView = true,
                focusCameraActive = autoState.turnFocusCameraActive,
                focusIsHighway = autoState.turnFocusIsHighway,
                focusClosePair = autoState.turnFocusClosePair
            )
            val carLoc = autoState.snappedLocation!!
            autoTimeline.submitCommand(
                CameraTargetCommand(
                    lat = carLoc.lat, lon = carLoc.lon, zoom = carTarget.zoom, tilt = carTarget.tilt,
                    bearing = autoState.snappedBearing.toDouble(),
                    animationDurationMs = if (!wasAutoFocused && autoState.turnFocusCameraActive) autoState.turnFocusEnterAnimMs else 650,
                    anchorLowerQuarter = carTarget.anchorLowerQuarter, routeId = route.id,
                    maneuverIndex = autoState.turnFocusManeuverIndex, generation = 1L, isFocusCommand = autoState.turnFocusCameraActive
                ),
                nowMs = tMs, activeRouteId = route.id, isCameraFollowing = true, minValidGeneration = 1L
            )

            // 60 Hz camera interpolation
            var frameT = tMs + 17L
            while (frameT <= tMs + 1000L) {
                phoneTimeline.advanceTo(frameT)
                autoTimeline.advanceTo(frameT)
                val dt = (frameT - tMs) / 1000.0
                val pRem = (remaining - speedMps * dt).coerceAtLeast(0.0)
                val aRem = (autoRem - speedMps * dt).coerceAtLeast(0.0)
                if (phoneDecIdx >= 0 && phoneMoveDist < 0 && phoneTimeline.currentPose.zoom >= 15.02) {
                    phoneMoveDist = pRem; phoneMoveTime = pRem / speedMps
                }
                if (phoneDecIdx >= 0 && phoneReadDist < 0 &&
                    (nav.showJunctionPreview || phoneTimeline.isJunctionAndOutgoingReadable(splitPt, outgoingRampProbe, 17.0))
                ) {
                    phoneReadDist = pRem; phoneReadTime = pRem / speedMps
                }
                if (autoDecIdx >= 0 && autoMoveDist < 0 && autoTimeline.currentPose.zoom >= 14.22) {
                    autoMoveDist = aRem; autoMoveTime = aRem / speedMps
                }
                if (autoDecIdx >= 0 && autoReadDist < 0 &&
                    autoTimeline.isJunctionAndOutgoingReadable(splitPt, outgoingRampProbe, 17.0)
                ) {
                    autoReadDist = aRem; autoReadTime = aRem / speedMps
                }
                frameT += 17L
            }
        }

        val phoneMetrics = ReplayMetrics("Case2_Phone_65mphExit", phoneDecIdx, phoneDecDist, phoneDecTime, phoneMoveDist, phoneMoveTime, phoneReadDist, phoneReadTime, passedSec)
        val autoMetrics = ReplayMetrics("Case2_Auto_65mphExit", autoDecIdx, autoDecDist, autoDecTime, autoMoveDist, autoMoveTime, autoReadDist, autoReadTime, passedSec)
        println(phoneMetrics.formatLine() + " | junctionCard=%.1fm".format(phoneJunctionCardDist))
        println(autoMetrics.formatLine())

        assertTrue("Phone focus must decide >= 900 m before 65 mph exit (was $phoneDecDist)", phoneDecDist >= 900.0)
        assertTrue("Phone junction card must appear >= 700 m before exit (was $phoneJunctionCardDist)", phoneJunctionCardDist >= 700.0)
        assertTrue("Phone readable outgoing ramp/junction must appear well before split (was $phoneReadDist m / $phoneReadTime s)",
            phoneReadDist > 150.0 && phoneReadTime > 5.0)
        assertTrue("Android Auto readable outgoing ramp must appear before split (was $autoReadDist m / $autoReadTime s)",
            autoReadDist > 100.0 && autoReadTime > 3.5)

        // 2C. Sparse / late GPS fix arriving 140 m before 65 mph exit with 1.5 s fix age -> fast adaptive animation
        val lateTracker = TurnFocusSessionTracker()
        val latePt = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, splitProgress - 140.0)
        val lateDec = lateTracker.evaluate(
            route = route, currentManeuverIndex = 1, snappedShapeIndex = latePt.first,
            distanceToNextManeuverMeters = 140.0, speedMps = speedMps, travelMode = TravelMode.DRIVE,
            isNavigating = true, isOffRoute = false, isRerouting = false, hasArrived = false,
            config = TurnFocusConfig(), snappedLocation = latePt.second, gpsAgeMs = 1500L
        )
        assertTrue("Late 65 mph fix must still enter focus", lateDec.isActive && lateDec.justEntered)
        assertTrue("Late 65 mph fix must shorten enter animation (< 900 ms, was ${lateDec.recommendedEnterAnimMs})",
            lateDec.recommendedEnterAnimMs in TurnFocusPlanner.MIN_ENTER_ANIM_MS..900)
    }

    // --- CASE 3: Two turns 25–40 m apart (30 m spacing) ---

    @Test
    fun case3_closeConsecutiveTurns30mApart_secondTurnVisibleInTimeNoBackZoom() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        val route = buildCloseConsecutiveTurnsRoute(spacingMeters = 30.0)
        val speedMps = 11.0f // ~25 mph
        val trace = makeTraceAlongRoute(route, speedMps, dtSec = 0.5, startProgressMeters = 50.0, endProgressMeters = 720.0)
        val turn1Idx = route.maneuvers[1].beginShapeIndex
        val turn2Idx = route.maneuvers[2].beginShapeIndex
        val turn1Progress = NavigationGeometryHelper.vertexProgressMeters(route.shape, turn1Idx)
        val turn2Progress = NavigationGeometryHelper.vertexProgressMeters(route.shape, turn2Idx)
        val turn2Pt = route.shape[turn2Idx]
        val turn2OutgoingProbe = route.shape[(turn2Idx + 3).coerceAtMost(route.shape.lastIndex)]

        val vm = RahYabViewModel(app)
        seedViewModelRoute(vm, route, trace.first().second.let { LatLon(it.lat, it.lon) })
        val timeline = CameraAnimationTimeline(
            CameraPose(trace.first().second.lat, trace.first().second.lon, zoom = 16.5, tilt = 55.0, bearing = 0.0, anchorLowerQuarter = false)
        )

        var sawClosePairZoomOnTurn1 = false
        var turn2DecIdx = -1; var turn2DecDist = -1.0; var turn2DecTime = -1.0
        var turn2MoveDist = -1.0; var turn2MoveTime = -1.0
        var turn2ReadDist = -1.0; var turn2ReadTime = -1.0
        var turn2PassedSec = -1.0
        var revertedToTurn1AfterTurn2 = false
        var turn1EnteredCount = 0
        var prevFocusIdx = -1
        var lastNonce = -1L
        val startT = trace.first().first

        trace.forEachIndexed { idx, (tMs, fix) ->
            vm.handleNavigationGpsFixImpl(fix)
            val nav = vm.navigationState.value
            val remainingToTurn2 = (turn2Progress - nav.snappedRouteProgressMeters).coerceAtLeast(0.0)
            val ttm2 = remainingToTurn2 / speedMps

            if (nav.turnFocusManeuverIndex == 1 && prevFocusIdx != 1) turn1EnteredCount++
            if (turn2DecIdx >= 0 && nav.turnFocusManeuverIndex == 1) revertedToTurn1AfterTurn2 = true
            if (nav.turnFocusManeuverIndex == 1 && nav.turnFocusClosePair) sawClosePairZoomOnTurn1 = true
            prevFocusIdx = nav.turnFocusManeuverIndex

            if (turn2PassedSec < 0 && nav.snappedRouteProgressMeters >= turn2Progress) {
                turn2PassedSec = (tMs - startT) / 1000.0
            }
            if (turn2DecIdx < 0 && nav.turnFocusActive && nav.turnFocusCameraActive && nav.turnFocusManeuverIndex == 2) {
                turn2DecIdx = idx; turn2DecDist = remainingToTurn2; turn2DecTime = ttm2
            }
            val cmd = vm.cameraCommand.value
            if (cmd != null && cmd.nonce != lastNonce) {
                lastNonce = cmd.nonce
                if (cmd.maneuverIndex == 2 && cmd.isFocusCommand) {
                    assertTrue("Close second turn must use fast enter animation (<= 420 ms, was ${cmd.animationDurationMs})",
                        cmd.animationDurationMs <= TurnFocusPlanner.CLOSE_TURN_MAX_ANIM_MS)
                }
                timeline.submitCommand(cmd, tMs, nav.route?.id, vm.isCameraFollowing.value, vm.cameraGeneration.value)
            }

            var frameT = tMs + 17L
            while (frameT <= tMs + 500L) {
                timeline.advanceTo(frameT)
                val dt = (frameT - tMs) / 1000.0
                val liveRem2 = (remainingToTurn2 - speedMps * dt).coerceAtLeast(0.0)
                if (turn2DecIdx >= 0 && turn2MoveDist < 0 && timeline.isAnimating()) {
                    turn2MoveDist = liveRem2; turn2MoveTime = liveRem2 / speedMps
                }
                if (turn2DecIdx >= 0 && turn2ReadDist < 0 &&
                    timeline.isJunctionAndOutgoingReadable(turn2Pt, turn2OutgoingProbe, minReadableZoom = 17.3)
                ) {
                    turn2ReadDist = liveRem2; turn2ReadTime = liveRem2 / speedMps
                }
                frameT += 17L
            }
        }

        val m = ReplayMetrics("Case3_Phone_CloseTurns30m", turn2DecIdx, turn2DecDist, turn2DecTime, turn2MoveDist, turn2MoveTime, turn2ReadDist, turn2ReadTime, turn2PassedSec)
        println(m.formatLine() + " | closePairFramed=$sawClosePairZoomOnTurn1 | turn1Entries=$turn1EnteredCount")

        assertTrue("Approaching turn 1 must detect close pair and frame both turns", sawClosePairZoomOnTurn1)
        assertTrue("Second turn must decide immediately after passing turn 1 (> 15 m before turn 2, was $turn2DecDist)", turn2DecDist > 15.0)
        assertTrue("Second turn and outgoing leg must be readable BEFORE reaching turn 2 (was $turn2ReadDist m / $turn2ReadTime s)",
            turn2ReadDist > 10.0 && turn2ReadTime > 0.9)
        assertFalse("Must never zoom back to turn 1 after turn 2 takes focus", revertedToTurn1AfterTurn2)
        assertEquals("Turn 1 must only enter Turn Focus once", 1, turn1EnteredCount)
    }

    // --- CASE 4: GPS gap jumping from before an exit to beyond it ---

    @Test
    fun case4_gpsGapJumpingPastExit_neverDisplaysPassedExitForFirstTime() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        val route = buildHighwayExitRoute("hwy_exit_gps_gap")
        val splitIdx = route.maneuvers[1].beginShapeIndex
        val splitProgress = NavigationGeometryHelper.vertexProgressMeters(route.shape, splitIdx)
        val speedMps = 29.06f
        val baseT = 1_700_000_000_000L

        val vm = RahYabViewModel(app)
        val (_, beforePt, beforeBrg) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, splitProgress - 1150.0)
        val (_, past25Pt, past25Brg) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, splitProgress + 25.0)
        val (_, past80Pt, past80Brg) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, splitProgress + 80.0)

        seedViewModelRoute(vm, route, beforePt)

        // Fix 1: 1150 m before exit (outside 1000 m Turn Focus and 800 m Junction View)
        vm.handleNavigationGpsFixImpl(GpsFix(lat = beforePt.lat, lon = beforePt.lon, bearing = beforeBrg, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT, hasBearing = true))
        assertFalse("Exit card not yet shown at 1150 m before exit", vm.navigationState.value.showJunctionPreview)
        assertFalse("Turn Focus not yet active at 1150 m before exit", vm.navigationState.value.turnFocusActive)

        // GPS gap -> next reliable fix arrives 25 m PAST the exit (within the 50 m post-pass radius!)
        vm.handleNavigationGpsFixImpl(GpsFix(lat = past25Pt.lat, lon = past25Pt.lon, bearing = past25Brg, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT + 40_000L, hasBearing = true))
        val afterGap = vm.navigationState.value
        println("Case4_GpsGapPastExit | progress=%.1fm (exit=%.1fm) | showJunction=${afterGap.showJunctionPreview} | activeJunctionIdx=${afterGap.activeJunctionManeuverIndex} | turnFocus=${afterGap.turnFocusActive} | currentManeuverIdx=${afterGap.currentManeuverIndex}"
            .format(afterGap.snappedRouteProgressMeters, splitProgress))

        assertFalse("Passed exit card MUST NEVER appear for the first time after a GPS gap", afterGap.showJunctionPreview)
        assertEquals("Active junction maneuver index must stay -1", -1, afterGap.activeJunctionManeuverIndex)
        assertFalse("Turn Focus MUST NOT activate for an already-passed exit", afterGap.turnFocusActive)
        assertFalse("Turn Focus camera MUST NOT activate for an already-passed exit", afterGap.turnFocusCameraActive)
        assertEquals("Navigation must skip past exit 1 to next maneuver 2", 2, afterGap.currentManeuverIndex)

        // Next fix 80 m past exit continues cleanly
        vm.handleNavigationGpsFixImpl(GpsFix(lat = past80Pt.lat, lon = past80Pt.lon, bearing = past80Brg, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT + 42_000L, hasBearing = true))
        assertFalse(vm.navigationState.value.showJunctionPreview)
        assertFalse(vm.navigationState.value.turnFocusActive)

        // Contrast control: if a fix DID arrive on approach (400 m before exit), 25 m post-pass hold IS allowed
        val controlCtrl = NavigationSessionController()
        val (_, approachPt, approachBrg) = NavigationGeometryHelper.advanceAlongShape(route.shape, 0, splitProgress - 400.0)
        var ctrlState = ActiveNavigationState(isNavigating = true, route = route, snappedLocation = approachPt)
        ctrlState = controlCtrl.processGpsFix(
            ctrlState, GpsFix(lat = approachPt.lat, lon = approachPt.lon, bearing = approachBrg, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT, hasBearing = true),
            emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, UserSettings(), nowMs = baseT
        ).state
        assertTrue("Control: exit card appears on approach at 400 m", ctrlState.showJunctionPreview && ctrlState.activeJunctionManeuverIndex == 1)
        ctrlState = controlCtrl.processGpsFix(
            ctrlState, GpsFix(lat = past25Pt.lat, lon = past25Pt.lon, bearing = past25Brg, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT + 15_000L, hasBearing = true),
            emptyList(), emptyList(), DistanceUnit.IMPERIAL, AppLanguage.ENGLISH, false, UserSettings(), nowMs = baseT + 15_000L
        ).state
        assertTrue("Control: short 25 m post-pass hold is preserved when card was already displayed on approach",
            ctrlState.showJunctionPreview && ctrlState.activeJunctionManeuverIndex == 1)
    }

    // --- CASE 5: Reroute during pending animation (same maneuver index) + driver manual pan ---

    @Test
    fun case5_rerouteDuringPendingAnimationAndManualPan_obsoleteCommandNeverControlsCamera() {
        val app = ApplicationProvider.getApplicationContext<RahYabApplication>()
        val routeA = buildCityRightTurnRoute("route_A")
        val routeB = buildCityRightTurnRoute("route_B") // Reuses maneuver index 1!
        val speedMps = 13.4f
        val baseT = 1_700_000_000_000L
        val (_, pt450, brg450) = NavigationGeometryHelper.advanceAlongShape(routeA.shape, 0, 250.0) // 450 m before turn 1

        // 5A. Reroute during pending focus animation
        val vm = RahYabViewModel(app)
        seedViewModelRoute(vm, routeA, pt450)
        val timeline = CameraAnimationTimeline(
            CameraPose(pt450.lat, pt450.lon, zoom = 16.5, tilt = 55.0, bearing = 0.0, anchorLowerQuarter = false)
        )

        vm.handleNavigationGpsFixImpl(GpsFix(lat = pt450.lat, lon = pt450.lon, bearing = brg450, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT, hasBearing = true))
        val cmdRouteA = vm.cameraCommand.value
        assertNotNull("Route A must emit Turn Focus camera command", cmdRouteA)
        assertTrue("Command must be focus command for maneuver 1 on route_A",
            cmdRouteA!!.isFocusCommand && cmdRouteA.routeId == "route_A" && cmdRouteA.maneuverIndex == 1)

        // Start animating cmdRouteA, then mid-animation (250 ms) reroute to route_B (same maneuverIndex == 1!)
        assertTrue(timeline.submitCommand(cmdRouteA, baseT, "route_A", true, vm.cameraGeneration.value))
        timeline.advanceTo(baseT + 250L)
        assertTrue("Animation should still be in progress at 250 ms", timeline.isAnimating())
        val zoomAtReroute = timeline.currentPose.zoom

        // Reroute invalidates pending camera commands and swaps route
        vm.resetTurnFocusImpl()
        vm.invalidatePendingCameraCommandsImpl()
        vm._navigationState.value = vm._navigationState.value.copy(route = routeB, currentManeuverIndex = 1, isRerouting = false)
        timeline.cancelTransitions()

        assertNull("ViewModel pending camera command must be cleared on reroute", vm.cameraCommand.value)
        assertFalse("Stale Route A command MUST be rejected on Route B even with identical maneuverIndex=1",
            cmdRouteA.isValidFor(activeRouteId = routeB.id, isCameraFollowing = true, minValidGeneration = vm.cameraGeneration.value))
        assertFalse("Timeline must reject re-delivery of obsolete Route A command",
            timeline.submitCommand(cmdRouteA, baseT + 300L, routeB.id, true, vm.cameraGeneration.value))

        // Advance time 1500 ms -> canceled animation must NOT keep zooming toward Route A target
        timeline.advanceTo(baseT + 1800L)
        assertEquals("Canceled animation must stay frozen where canceled, not snap to Route A focus zoom",
            zoomAtReroute, timeline.currentPose.zoom, 1e-6)
        assertEquals("Exactly 1 transition canceled", 1, timeline.canceledTransitionsCount)

        // 5B. Driver manually pans map during active Turn Focus
        seedViewModelRoute(vm, routeA, pt450)
        val panTimeline = CameraAnimationTimeline(
            CameraPose(pt450.lat, pt450.lon, zoom = 16.5, tilt = 55.0, bearing = 0.0, anchorLowerQuarter = false)
        )
        vm.handleNavigationGpsFixImpl(GpsFix(lat = pt450.lat, lon = pt450.lon, bearing = brg450, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT, hasBearing = true))
        val focusCmd = vm.cameraCommand.value!!
        assertTrue(panTimeline.submitCommand(focusCmd, baseT, routeA.id, vm.isCameraFollowing.value, vm.cameraGeneration.value))
        panTimeline.advanceTo(baseT + 200L)
        val zoomAtPan = panTimeline.currentPose.zoom

        // Driver pans the map -> cancels animation and pauses camera follow
        vm.pauseCameraFollowImpl()
        panTimeline.cancelTransitions()

        assertFalse("Follow mode must pause on manual pan", vm.isCameraFollowing.value)
        assertFalse("Focus command from before pan must be invalidated",
            focusCmd.isValidFor(routeA.id, vm.isCameraFollowing.value, vm.cameraGeneration.value))

        // Subsequent GPS update during this Turn Focus must NOT hijack the camera back
        val (_, pt420, brg420) = NavigationGeometryHelper.advanceAlongShape(routeA.shape, 0, 280.0)
        vm.handleNavigationGpsFixImpl(GpsFix(lat = pt420.lat, lon = pt420.lon, bearing = brg420, speedMps = speedMps, accuracyMeters = 4f, timestampMs = baseT + 1000L, hasBearing = true))
        assertNull("No camera command may be emitted while user manually panned during Turn Focus", vm.cameraCommand.value)
        panTimeline.advanceTo(baseT + 2000L)
        assertEquals("Camera must stay under driver control after manual pan", zoomAtPan, panTimeline.currentPose.zoom, 1e-6)

        println("Case5_RerouteAndManualPan | rerouteZoomFrozenAt=%.2f (target=%.2f) | rejectedCmds=${timeline.rejectedCommandsCount} | manualPanZoomFrozenAt=%.2f | followAfterPan=${vm.isCameraFollowing.value}"
            .format(zoomAtReroute, cmdRouteA.zoom, zoomAtPan))
    }
}
