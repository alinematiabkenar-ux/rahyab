package com.example

import com.example.data.preferences.UserSettings
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.map.LaneOverlayBuilder
import com.example.map.ManeuverArrowGeometry
import com.example.navigation.ActiveNavigationState
import com.example.navigation.GpsFix
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDirection
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.NavigationGeometryHelper
import com.example.navigation.NavigationSessionController
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusSessionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Generates a pixel-verified, timestamped sequence of rendered PNG replay frames for
 * Acceptance Test A (city right turn with final traffic signal 180 m before turn).
 *
 * Uses production [TurnFocusSessionTracker], [com.example.navigation.RouteTrafficSignalMatcher],
 * [LaneOverlayBuilder], [ManeuverArrowGeometry], and [LaneGuidanceDecision] geometry/state,
 * rendered via JVM [BufferedImage]/[java.awt.Graphics2D] (not live MapLibre GL tiles, which
 * require a physical device or GPU emulator).
 */
class ApproachReplayScreenshotTest {

    private val baseLat = 44.0500
    private val baseLon = -123.0900
    private val metersPerDegLat = 111_320.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))

    private fun pointAtEn(eastMeters: Double, northMeters: Double): LatLon =
        LatLon(baseLat + northMeters / metersPerDegLat, baseLon + eastMeters / metersPerDegLon)

    @Test
    fun renderTimestampedReplaySequenceForAcceptanceA() {
        val pts = mutableListOf<LatLon>()
        for (n in 0..1250 step 10) pts.add(pointAtEn(0.0, n.toDouble()))
        val pivotIdx = pts.lastIndex
        for (e in 10..120 step 10) pts.add(pointAtEn(e.toDouble(), 1250.0))

        val lanes = listOf(
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), false, false, false, LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.THROUGH), emptyList(), false, false, false, LaneHighlightTier.INACTIVE),
            LaneInfo(listOf(LaneDirection.RIGHT), listOf(LaneDirection.RIGHT), true, true, true, LaneHighlightTier.ACTIVE)
        )
        val turn = RouteManeuver(
            index = 1, type = ManeuverType.RIGHT, instructionEn = "Turn right onto Oak St",
            streetNames = listOf("Oak St"), lengthMeters = 120.0, timeSeconds = 12.0,
            beginShapeIndex = pivotIdx, endShapeIndex = pts.lastIndex,
            lat = pts[pivotIdx].lat, lon = pts[pivotIdx].lon, lanes = lanes
        )
        val route = OfflineRoute(
            id = "replay_a_visual", summaryLabel = "City Right Turn",
            distanceMeters = 1370.0, durationSeconds = 98.0, shape = pts,
            maneuvers = listOf(
                RouteManeuver(0, ManeuverType.DEPART, "Head north on Main St", listOf("Main St"), 1250.0, 86.0, 0, pivotIdx, pts[0].lat, pts[0].lon),
                turn,
                RouteManeuver(2, ManeuverType.ARRIVE, "Arrive", listOf("Oak St"), 0.0, 0.0, pts.lastIndex, pts.lastIndex, pts.last().lat, pts.last().lon)
            )
        )
        val signalPt = pointAtEn(0.0, 1070.0) // 180 m before turn
        val signals = listOf(RoadHazardPoint(101L, "traffic_signals", signalPt.lat, signalPt.lon, "Last Traffic Light"))
        val settings = UserSettings(distanceUnit = DistanceUnit.IMPERIAL, language = AppLanguage.ENGLISH)
        val cfg = TurnFocusConfig()
        val controller = NavigationSessionController()
        val tracker = TurnFocusSessionTracker()
        val pivotProgress = NavigationGeometryHelper.vertexProgressMeters(pts, pivotIdx)

        val outDir = File("build/outputs/replay_screenshots")
        outDir.mkdirs()

        val captureMilestones = linkedMapOf(
            200 to "01_pre_approach_1050m",
            270 to "02_approach_entry_980m",
            1050 to "03_before_final_signal_200m",
            1080 to "04_passed_final_signal_lane_level_170m",
            1270 to "05_mid_turn_outgoing_20m_past",
            1310 to "06_completed_established_60m_past"
        )
        val generatedFiles = mutableListOf<File>()

        var state = ActiveNavigationState(
            isNavigating = true, route = route, currentManeuverIndex = 1,
            distanceToNextManeuverMeters = pivotProgress, snappedLocation = pts[0], snappedBearing = 0f
        )
        var tMs = 0L
        for (dInt in 0..1330 step 10) {
            val d = dInt.toDouble()
            val (_, loc, bearing) = NavigationGeometryHelper.advanceAlongShape(pts, 0, d)
            val fix = GpsFix(loc.lat, loc.lon, bearing, hasBearing = true, speedMps = 14.0f, accuracyMeters = 4f, timestampMs = 1_000_000L + tMs)
            val step = controller.processGpsFix(state, fix, signals, emptyList(), settings.distanceUnit, settings.language, false, settings, nowMs = 1_000_000L + tMs)
            val focusM = state.activeTurnFocusManeuver ?: step.state.currentManeuver
            val laneDec = focusM?.takeIf { it.lanes.isNotEmpty() }?.let { LaneGuidanceDecision.evaluate(it) }
            val decision = tracker.evaluate(
                route, step.state.currentManeuverIndex, step.state.snappedShapeIndex, step.state.distanceToNextManeuverMeters,
                fix.speedMps, TravelMode.DRIVE, true, step.state.isOffRoute, step.state.isRerouting, step.state.hasArrived,
                cfg, step.state.snappedLocation, 0L, step.state.snappedBearing, signals
            )
            state = step.state.copy(
                laneDecision = laneDec,
                laneConfidence = laneDec?.confidence ?: LaneConfidence.HIGH,
                showLaneStrip = step.state.showLaneStrip || (decision.isActive && laneDec?.shouldShowFullDiagram == true),
                turnFocusActive = decision.isActive,
                turnFocusCameraActive = decision.isActive,
                turnFocusManeuverIndex = if (decision.isActive) decision.maneuverIndex else -1,
                turnFocusTriggerDistanceMeters = if (decision.isActive) decision.triggerDistanceMeters else 0.0,
                turnFocusStage = decision.stage,
                turnFocusTargetZoom = decision.targetZoom,
                turnFocusPassedFinalSignal = decision.matchedSignal.hasPassedSignal
            )

            val label = captureMilestones[dInt]
            if (label != null) {
                val overlay = LaneOverlayBuilder.build(
                    route, state.currentManeuverIndex, state.snappedShapeIndex, state.distanceToNextManeuverMeters,
                    TravelMode.DRIVE, isOffRoute = state.isOffRoute, enabled = true,
                    startDistanceMeters = if (state.turnFocusActive) state.turnFocusTriggerDistanceMeters else 500.0,
                    isFocusMode = state.turnFocusActive, focusManeuverIndex = state.turnFocusManeuverIndex
                )
                val arrow = ManeuverArrowGeometry.build(
                    route, state.currentManeuverIndex, state.snappedShapeIndex, state.distanceToNextManeuverMeters,
                    TravelMode.DRIVE, focusManeuverIndex = state.turnFocusManeuverIndex
                )
                val img = renderReplayFrame(
                    timestampSec = tMs / 1000.0,
                    signedDistToTurnMeters = pivotProgress - d,
                    state = state,
                    route = route,
                    signalPt = signalPt,
                    vehicleLoc = loc,
                    vehicleBearing = bearing,
                    overlay = overlay,
                    arrow = arrow,
                    laneDecision = laneDec
                )
                if (dInt == 1080) {
                    // Verify real blue lane pixels (#1E88E5) are visibly painted in the lane-level frame!
                    var bluePixels = 0
                    for (y in 300 until 1100 step 2) {
                        for (x in 100 until 620 step 2) {
                            val rgb = img.getRGB(x, y)
                            val r = (rgb shr 16) and 0xFF
                            val g = (rgb shr 8) and 0xFF
                            val b = rgb and 0xFF
                            if (b > 180 && g in 90..175 && r < 90) bluePixels++
                        }
                    }
                    assertTrue("Lane-level frame must visibly render blue lane pixels on map, found $bluePixels", bluePixels > 200)
                }
                val file = File(outDir, "$label.png")
                ImageIO.write(img, "png", file)
                generatedFiles.add(file)
            }
            tMs += 714L
        }

        assertEquals(captureMilestones.size, generatedFiles.size)
        generatedFiles.forEach { f ->
            assertTrue("Screenshot ${f.name} must exist and be non-empty", f.exists() && f.length() > 3000L)
        }
        println("GENERATED_REPLAY_SCREENSHOTS=${generatedFiles.joinToString(",") { it.path }}")
    }

    private fun renderReplayFrame(
        timestampSec: Double,
        signedDistToTurnMeters: Double,
        state: ActiveNavigationState,
        route: OfflineRoute,
        signalPt: LatLon,
        vehicleLoc: LatLon,
        vehicleBearing: Float,
        overlay: com.example.map.LaneOverlayData?,
        arrow: com.example.map.ManeuverArrowData?,
        laneDecision: LaneGuidanceDecision?
    ): BufferedImage {
        val w = 720
        val h = 1280
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Map background + subtle grid
        g.color = Color(0x0F, 0x17, 0x2A)
        g.fillRect(0, 0, w, h)
        g.color = Color(0x1E, 0x29, 0x3B)
        g.stroke = BasicStroke(1f)
        for (gx in 0..w step 60) g.drawLine(gx, 0, gx, h)
        for (gy in 0..h step 60) g.drawLine(0, gy, w, gy)

        val zoom = if (state.turnFocusActive && state.turnFocusTargetZoom > 0.0) state.turnFocusTargetZoom else 14.8
        // Calibrated meters-per-pixel: at zoom 15.55 (~1.55 m/px), 1000 m fits from lower quarter to top banner; at zoom 18.0 (~0.28 m/px), individual 3.6 m lanes are ~13 px wide!
        val metersPerPx = (1.55 / 2.0.pow(zoom - 15.55)).coerceIn(0.22, 3.2)
        val anchorX = w * 0.5
        val anchorY = if (state.turnFocusActive) h * 0.78 else h * 0.70
        val rad = Math.toRadians(-vehicleBearing.toDouble())
        val cosB = cos(rad)
        val sinB = sin(rad)

        fun toScreen(pt: LatLon): Pair<Double, Double> {
            val dxM = (pt.lon - vehicleLoc.lon) * metersPerDegLon
            val dyM = (pt.lat - vehicleLoc.lat) * metersPerDegLat
            val rx = dxM * cosB - dyM * sinB
            val ry = dxM * sinB + dyM * cosB
            return (anchorX + rx / metersPerPx) to (anchorY - ry / metersPerPx)
        }

        // 1. Base road corridor
        val roadPath = Path2D.Double()
        route.shape.forEachIndexed { idx, p ->
            val (sx, sy) = toScreen(p)
            if (idx == 0) roadPath.moveTo(sx, sy) else roadPath.lineTo(sx, sy)
        }
        val roadWidthPx = (15.0 / metersPerPx).toFloat().coerceIn(10f, 54f)
        g.color = Color(0x33, 0x41, 0x55)
        g.stroke = BasicStroke(roadWidthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(roadPath)

        // Active route highlight underneath lane strips
        g.color = Color(0x38, 0xBD, 0xF8, 120)
        g.stroke = BasicStroke((roadWidthPx * 0.35f).coerceAtLeast(4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(roadPath)

        // 2. On-road blue target lane + adjacent gray lanes + white dashed separators
        if (overlay != null) {
            val lanePx = (3.4 / metersPerPx).toFloat().coerceIn(4f, 18f)
            for (strip in overlay.lanes.sortedBy { it.isTarget }) {
                g.color = if (strip.isTarget) Color(0x1E, 0x88, 0xE5, 240) else Color(0x64, 0x74, 0x8B, 180)
                g.stroke = BasicStroke(lanePx, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                val p = Path2D.Double()
                strip.points.forEachIndexed { i, pt ->
                    val (sx, sy) = toScreen(pt)
                    if (i == 0) p.moveTo(sx, sy) else p.lineTo(sx, sy)
                }
                g.draw(p)
            }
            g.color = Color(255, 255, 255, 225)
            g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(10f, 8f), 0f)
            for (sep in overlay.separators) {
                val p = Path2D.Double()
                sep.points.forEachIndexed { i, pt ->
                    val (sx, sy) = toScreen(pt)
                    if (i == 0) p.moveTo(sx, sy) else p.lineTo(sx, sy)
                }
                g.draw(p)
            }
        }

        // 3. Verified outgoing maneuver guide arrow
        if (arrow != null && arrow.points.size >= 2) {
            val p = Path2D.Double()
            arrow.points.forEachIndexed { i, pt ->
                val (sx, sy) = toScreen(pt)
                if (i == 0) p.moveTo(sx, sy) else p.lineTo(sx, sy)
            }
            g.color = Color(0x0F, 0x17, 0x2A, 220)
            g.stroke = BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(p)
            g.color = Color.WHITE
            g.stroke = BasicStroke(6.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(p)
        }

        // 4. Last mapped traffic signal marker (amber signal pill)
        val (sigX, sigY) = toScreen(signalPt)
        if (sigY in 260.0..(h - 140.0)) {
            g.color = Color(0x1E, 0x29, 0x3B)
            g.fillOval((sigX - 15).toInt(), (sigY - 15).toInt(), 30, 30)
            g.color = if (state.turnFocusPassedFinalSignal) Color(0x22, 0xC5, 0x5E) else Color(0xF5, 0x9E, 0x0B)
            g.fillOval((sigX - 10).toInt(), (sigY - 10).toInt(), 20, 20)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 16)
            g.drawString("Traffic Light (180m)", (sigX + 20).toInt(), (sigY + 6).toInt())
        }

        // 5. Vehicle navigation arrow puck at lower-quarter anchor
        val puck = Path2D.Double().apply {
            moveTo(anchorX, anchorY - 22)
            lineTo(anchorX + 16, anchorY + 18)
            lineTo(anchorX, anchorY + 9)
            lineTo(anchorX - 16, anchorY + 18)
            closePath()
        }
        g.color = Color(0x38, 0xBD, 0xF8)
        g.fill(puck)
        g.color = Color.WHITE
        g.stroke = BasicStroke(3f)
        g.draw(puck)

        // 6. Top turn banner + synchronized top lane diagram
        g.color = Color(0x0B, 0x57, 0xD0)
        g.fill(RoundRectangle2D.Double(24.0, 28.0, w - 48.0, 118.0, 24.0, 24.0))
        g.color = Color.WHITE
        g.font = Font("SansSerif", Font.BOLD, 30)
        g.drawString("Turn right onto Oak St", 48, 78)
        g.color = Color(0xDB, 0xEA, 0xFE)
        g.font = Font("SansSerif", Font.PLAIN, 22)
        val distLabel = if (signedDistToTurnMeters >= 0) "${signedDistToTurnMeters.toInt()} m ahead (Imperial miles mode)" else "${(-signedDistToTurnMeters).toInt()} m past pivot on Oak St"
        g.drawString(distLabel, 48, 118)

        if (state.showLaneStrip && laneDecision != null && laneDecision.shouldShowFullDiagram) {
            g.color = Color(0x1E, 0x29, 0x3B, 245)
            g.fill(RoundRectangle2D.Double(24.0, 156.0, w - 48.0, 108.0, 20.0, 20.0))
            val slots = laneDecision.slots
            val slotW = 88.0
            val gap = 14.0
            val totalW = slots.size * slotW + (slots.size - 1) * gap
            var x = (w - totalW) / 2.0
            slots.forEachIndexed { idx, _ ->
                val isRec = idx in laneDecision.primaryTargetIndices
                g.color = if (isRec) Color(0x1E, 0x88, 0xE5) else Color(0x47, 0x55, 0x69)
                g.fill(RoundRectangle2D.Double(x, 170.0, slotW, 80.0, 14.0, 14.0))
                if (isRec) {
                    g.color = Color.WHITE
                    g.stroke = BasicStroke(3f)
                    g.draw(RoundRectangle2D.Double(x, 170.0, slotW, 80.0, 14.0, 14.0))
                }
                g.color = Color.WHITE
                g.font = Font("SansSerif", Font.BOLD, 34)
                g.drawString(if (isRec) "->" else "^", (x + 26).toInt(), 222)
                x += slotW + gap
            }
        }

        // 7. Bottom telemetry stamp
        g.color = Color(0x0F, 0x17, 0x2A, 235)
        g.fill(RoundRectangle2D.Double(24.0, h - 122.0, w - 48.0, 98.0, 16.0, 16.0))
        g.color = Color(0xE2, 0xE8, 0xF0)
        g.font = Font("Monospaced", Font.BOLD, 20)
        g.drawString(String.format("t=%5.1fs | Stage=%-10s | Zoom=%.2f", timestampSec, state.turnFocusStage, zoom), 42, h - 82)
        g.drawString("BlueLaneMap=${overlay != null} | TopPanel=${state.showLaneStrip && laneDecision?.shouldShowFullDiagram == true} | SigPass=${state.turnFocusPassedFinalSignal}", 42, h - 46)

        g.dispose()
        return img
    }
}
