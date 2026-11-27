package com.example

import android.graphics.Rect
import com.example.car.CarCameraProfile
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.navigation.ActiveNavigationState
import com.example.navigation.LaneDemoScenarios
import com.example.navigation.LaneSource
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.MapViewportMetrics
import com.example.navigation.NavigationViewportFraming
import com.example.navigation.OfflineRoute
import com.example.navigation.RouteManeuver
import com.example.navigation.SynchronizedFollowAnimator
import com.example.navigation.TravelMode
import com.example.navigation.TurnFocusConfig
import com.example.navigation.TurnFocusPlanner
import com.example.navigation.TurnFocusSessionTracker
import com.example.navigation.TurnFocusStage
import com.example.navigation.TurnFocusStageHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import kotlin.math.abs

/**
 * Bug 2 Acceptance Tests:
 * - Replays 500 m and 1,000 m Turn Focus approach, tightening, turn/exit, and GPS gap + recovery
 *   across Standard Phone, Small Phone (with top HUD + blue lane panel + junction card + bottom bar),
 *   and Android Auto (`visibleArea` inset).
 * - Checks marker projected screen coordinates on EVERY intermediate animation frame (33 ms ticks),
 *   proving the ENTIRE marker stays strictly inside the unobstructed map area with zero lag buildup.
 * - Verifies stale fix rejection, GPS gap snap without backlog replay, manual pan independence,
 *   and Recenter synchronization.
 * - Exports an animated GIF + keyframe PNGs in `app/build/outputs/regression_recordings/` showing
 *   intermediate frames of approach, turn, exit, and GPS-gap recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkerCameraSyncAcceptanceTest {

    private data class FrameVerificationSummary(
        val label: String,
        val totalFrames: Int,
        val outOfBoundsFrames: Int,
        val maxMarkerToFixLagMeters: Double,
        val minBottomClearancePx: Float,
        val minTopClearancePx: Float,
        val minZoomUsed: Double,
        val maxZoomUsed: Double
    )

    private data class RecordedFrame(
        val title: String,
        val remainingMeters: Double,
        val stage: TurnFocusStage,
        val markerLat: Double,
        val markerLon: Double,
        val markerBearing: Float,
        val camLat: Double,
        val camLon: Double,
        val camZoom: Double,
        val camTilt: Double,
        val camBearing: Double,
        val padding: DoubleArray,
        val isGpsGapRecovered: Boolean = false
    )

    private fun buildHighwayExitWithRightRampRoute(): OfflineRoute {
        val degPerM = 1.0 / 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(44.06))
        val pts = mutableListOf<LatLon>()
        // 1,400 m straight north approach (indices 0..70, 20 m step)
        for (i in 0..70) {
            pts.add(LatLon(44.0500 + i * 20.0 * degPerM, -123.0800))
        }
        val pivotIdx = pts.lastIndex
        val pivot = pts.last()
        // 300 m curving right exit ramp (indices 71..85, heading 45 deg northeast)
        for (k in 1..15) {
            val d = k * 20.0
            val dN = d * 0.7071 * degPerM
            val dE = d * 0.7071 * degPerM / cosLat
            pts.add(LatLon(pivot.lat + dN, pivot.lon + dE))
        }
        val lanes = LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1].lanes
        return OfflineRoute(
            id = "sync_hwy_exit",
            summaryLabel = "I-5 Exit 195B Ramp",
            distanceMeters = 1_700.0,
            durationSeconds = 65.0,
            shape = pts,
            maneuvers = listOf(
                RouteManeuver(
                    index = 0, type = ManeuverType.DEPART, instructionEn = "Head north on I-5.",
                    streetNames = listOf("I-5"), lengthMeters = 1_400.0, timeSeconds = 50.0,
                    beginShapeIndex = 0, endShapeIndex = pivotIdx, lat = pts[0].lat, lon = pts[0].lon,
                    isHighwayRoad = true, speedLimitMph = 65, laneCount = 4, isMultiLaneRoad = true
                ),
                RouteManeuver(
                    index = 1, type = ManeuverType.EXIT_RIGHT, instructionEn = "Take exit 195B onto Beltline Hwy.",
                    streetNames = listOf("Beltline Hwy"), lengthMeters = 300.0, timeSeconds = 15.0,
                    beginShapeIndex = pivotIdx, endShapeIndex = pts.lastIndex, lat = pivot.lat, lon = pivot.lon,
                    lanes = lanes, laneSource = LaneSource.DIRECT,
                    isHighwayRoad = true, isHighwayExitOrSplit = true, speedLimitMph = 65, laneCount = 4, isMultiLaneRoad = true
                )
            )
        )
    }

    private fun replayAcrossViewport(
        label: String,
        route: OfflineRoute,
        viewport: MapViewportMetrics,
        approachZoomMeters: Double,
        isAndroidAuto: Boolean,
        captureFrames: MutableList<RecordedFrame>? = null
    ): FrameVerificationSummary {
        val tracker = TurnFocusSessionTracker()
        val animator = SynchronizedFollowAnimator()
        val config = TurnFocusConfig(
            enabled = true,
            beepsEnabled = false,
            cityDistanceMeters = approachZoomMeters,
            highwayDistanceMeters = approachZoomMeters,
            wideHighwayEarlierEnabled = false
        )
        val exitManeuver = route.maneuvers[1]
        val pivotIdx = exitManeuver.beginShapeIndex
        val pivotPt = route.shape[pivotIdx]

        var nowMs = 10_000L
        var fixTimestampMs = 1_700_000_000_000L
        var totalFrames = 0
        var outOfBoundsFrames = 0
        var maxLagMeters = 0.0
        var minBottomClearance = Float.MAX_VALUE
        var minTopClearance = Float.MAX_VALUE
        var minZoom = Double.MAX_VALUE
        var maxZoom = 0.0

        // Drive from index 10 (1,200 m before exit) to index 83 (260 m onto exit ramp),
        // with a 5-second / 160 m GPS gap between index 40 and index 48!
        val indices = (10..39).toList() + listOf(48) + (49..83).toList()
        for ((stepIdx, shapeIdx) in indices.withIndex()) {
            val isGapRecovery = shapeIdx == 48
            val dtMs = if (isGapRecovery) 5_000L else 500L
            if (stepIdx > 0) {
                // Sample intermediate 33 ms animation frames during the previous fix's transition
                val animSteps = if (isGapRecovery) 2 else 15
                val subDt = (if (isGapRecovery) 100L else dtMs / animSteps)
                repeat(animSteps) {
                    nowMs += subDt
                    val pose = animator.sample(nowMs) ?: return@repeat
                    val proj = NavigationViewportFraming.projectLatLonToScreen(
                        point = LatLon(pose.markerLat, pose.markerLon),
                        cameraLat = pose.camLat,
                        cameraLon = pose.camLon,
                        zoom = pose.camZoom,
                        tiltDeg = pose.camTilt,
                        bearingDeg = pose.camBearing,
                        padding = pose.paddingArray(),
                        viewport = viewport
                    )
                    totalFrames++
                    if (!viewport.isMarkerFullyInside(proj)) {
                        outOfBoundsFrames++
                    }
                    val bottomClear = viewport.usableBottom - (proj.y + viewport.markerRadiusPx)
                    val topClear = (proj.y - viewport.markerRadiusPx) - viewport.usableTop
                    if (bottomClear < minBottomClearance) minBottomClearance = bottomClear
                    if (topClear < minTopClearance) minTopClearance = topClear
                    if (pose.camZoom < minZoom) minZoom = pose.camZoom
                    if (pose.camZoom > maxZoom) maxZoom = pose.camZoom
                }
            }
            fixTimestampMs += dtMs

            val pt = route.shape[shapeIdx]
            val nextPt = route.shape[(shapeIdx + 1).coerceAtMost(route.shape.lastIndex)]
            val bearing = com.example.navigation.ValhallaResponseParser.bearingDegrees(pt, nextPt).toFloat()
            val remToExit = if (shapeIdx <= pivotIdx) (pivotIdx - shapeIdx) * 20.0 else 0.0
            val curMIdx = if (shapeIdx < pivotIdx) 1 else 1
            val decision = tracker.evaluate(
                route = route,
                currentManeuverIndex = curMIdx,
                snappedShapeIndex = shapeIdx,
                distanceToNextManeuverMeters = remToExit,
                speedMps = 26.0f,
                travelMode = TravelMode.DRIVE,
                isNavigating = true,
                isOffRoute = false,
                isRerouting = false,
                hasArrived = false,
                config = config,
                snappedLocation = pt,
                gpsAgeMs = 0L,
                snappedBearing = bearing
            )

            val focusActive = decision.isActive
            val stage = decision.stage
            val baseZoom = if (focusActive && decision.targetZoom > 0.0) decision.targetZoom else 15.2
            val tilt = if (focusActive) TurnFocusPlanner.FOCUS_TILT_3D else 45.0

            val desiredTarget = if (focusActive) {
                TurnFocusStageHelper.computeApproachCameraTarget(route, exitManeuver, pt, remToExit, stage)
            } else pt

            val (camTarget, finalZoom, padding) = if (focusActive) {
                if (isAndroidAuto) {
                    val nav = ActiveNavigationState(
                        isNavigating = true, route = route, currentManeuverIndex = 1,
                        distanceToNextManeuverMeters = remToExit, turnFocusActive = true,
                        turnFocusCameraActive = true, turnFocusIsHighway = true,
                        turnFocusManeuverIndex = 1, turnFocusStage = stage, turnFocusTargetZoom = baseZoom
                    )
                    val upd = CarCameraProfile.computeFocusCameraUpdate(
                        nav = nav, loc = pt, bearing = bearing, is3DView = true,
                        enteringFocus = decision.justEntered, tightening = decision.justTightenedToLaneLevel,
                        viewport = viewport
                    )
                    val areaRect = Rect(
                        viewport.insetLeftPx, viewport.insetTopPx,
                        viewport.widthPx - viewport.insetRightPx, viewport.heightPx - viewport.insetBottomPx
                    )
                    val pad = CarCameraProfile.computeCameraPadding(
                        viewport.widthPx, viewport.heightPx, areaRect,
                        anchorLowerThird = false, anchorLowerQuarter = true, pixelRatio = viewport.density
                    )
                    Triple(upd.target, upd.zoom, pad)
                } else {
                    val framed = NavigationViewportFraming.computeBoundedFocusFraming(
                        vehicleLoc = pt,
                        desiredCameraTarget = desiredTarget,
                        baseZoom = baseZoom,
                        tiltDeg = tilt,
                        bearingDeg = bearing.toDouble(),
                        viewport = viewport,
                        junctionPivot = pivotPt,
                        anchorLowerQuarter = true
                    )
                    Triple(framed.cameraTarget, framed.zoom, framed.padding)
                }
            } else {
                val pad = NavigationViewportFraming.computeSafeCameraPadding(viewport, anchorLowerThird = true, anchorLowerQuarter = false)
                Triple(pt, baseZoom, pad)
            }

            // Inject a stale out-of-order fix right after step 15 -> must be rejected!
            if (stepIdx == 15) {
                val acceptedStale = animator.onNavigationTargetUpdate(
                    nowMs = nowMs, fixTimestampMs = fixTimestampMs - 1_500L,
                    markerLat = route.shape[10].lat, markerLon = route.shape[10].lon, markerBearing = 0f,
                    camLat = route.shape[10].lat, camLon = route.shape[10].lon,
                    camZoom = 15.0, camTilt = 45.0, camBearing = 0.0, padding = padding, durationMs = 400
                )
                assertFalse("Stale out-of-order GPS fix must be rejected", acceptedStale)
            }

            val animDur = if (decision.justEntered || decision.justTightenedToLaneLevel) 600 else 450
            animator.onNavigationTargetUpdate(
                nowMs = nowMs,
                fixTimestampMs = fixTimestampMs,
                markerLat = pt.lat,
                markerLon = pt.lon,
                markerBearing = bearing,
                camLat = camTarget.lat,
                camLon = camTarget.lon,
                camZoom = finalZoom,
                camTilt = tilt,
                camBearing = bearing.toDouble(),
                padding = padding,
                durationMs = animDur,
                isFollowingCamera = true,
                forceSnap = false,
                useEaseOut = decision.justEntered || decision.justTightenedToLaneLevel
            )

            // Verify GPS gap recovery snapped directly to latest reliable fix with 0 m backlog lag!
            val immediatePose = animator.sample(nowMs)!!
            if (isGapRecovery) {
                val snapErr = OfflinePlaceSearchEngine.haversineMeters(immediatePose.markerLat, immediatePose.markerLon, pt.lat, pt.lon)
                assertTrue("GPS gap recovery must snap directly to latest reliable fix (err=${snapErr}m)", snapErr < 0.5)
            }

            // Check convergence at the end of the final step (and after each regular 500 ms step >= 450 ms animDur)
            if (animDur <= 500 || stepIdx == indices.lastIndex) {
                val checkPose = animator.sample(nowMs + animDur.toLong())!!
                val lagAtEnd = OfflinePlaceSearchEngine.haversineMeters(checkPose.markerLat, checkPose.markerLon, pt.lat, pt.lon)
                if (lagAtEnd > maxLagMeters) maxLagMeters = lagAtEnd
            }

            // Check the immediate sampled frame right on target arrival too
            val immProj = NavigationViewportFraming.projectLatLonToScreen(
                LatLon(immediatePose.markerLat, immediatePose.markerLon),
                immediatePose.camLat, immediatePose.camLon, immediatePose.camZoom,
                immediatePose.camTilt, immediatePose.camBearing, immediatePose.paddingArray(), viewport
            )
            totalFrames++
            if (!viewport.isMarkerFullyInside(immProj)) outOfBoundsFrames++

            if (captureFrames != null) {
                captureFrames.add(
                    RecordedFrame(
                        title = if (isGapRecovery) "GPS Gap Recovered (Snap)" else "Stage: ${stage.name}",
                        remainingMeters = remToExit,
                        stage = stage,
                        markerLat = immediatePose.markerLat,
                        markerLon = immediatePose.markerLon,
                        markerBearing = immediatePose.markerBearing,
                        camLat = immediatePose.camLat,
                        camLon = immediatePose.camLon,
                        camZoom = immediatePose.camZoom,
                        camTilt = immediatePose.camTilt,
                        camBearing = immediatePose.camBearing,
                        padding = immediatePose.paddingArray(),
                        isGpsGapRecovered = isGapRecovery
                    )
                )
            }
        }

        val summary = FrameVerificationSummary(
            label = label,
            totalFrames = totalFrames,
            outOfBoundsFrames = outOfBoundsFrames,
            maxMarkerToFixLagMeters = maxLagMeters,
            minBottomClearancePx = minBottomClearance,
            minTopClearancePx = minTopClearance,
            minZoomUsed = minZoom,
            maxZoomUsed = maxZoom
        )
        println(
            "MARKER_SYNC_REPORT | ${summary.label} | frames=${summary.totalFrames} | " +
                "oob=${summary.outOfBoundsFrames} | maxEndLag=${"%.3f".format(summary.maxMarkerToFixLagMeters)}m | " +
                "minBottomClear=${"%.1f".format(summary.minBottomClearancePx)}px | " +
                "minTopClear=${"%.1f".format(summary.minTopClearancePx)}px | " +
                "zoom=[${"%.2f".format(summary.minZoomUsed)}..${"%.2f".format(summary.maxZoomUsed)}]"
        )
        return summary
    }

    @Test
    fun bug2_markerNeverLeavesUnobstructedViewportAcross500mAnd1000mPhoneSmallPhoneAndAuto_andExportsGif() {
        val route = buildHighwayExitWithRightRampRoute()

        val standardPhone = MapViewportMetrics(
            widthPx = 1080, heightPx = 2400,
            insetTopPx = 860, insetBottomPx = 280, density = 2.625f
        )
        val smallPhone = MapViewportMetrics(
            widthPx = 720, heightPx = 1280,
            insetTopPx = 540, insetBottomPx = 190, density = 2.0f
        )
        val androidAuto = MapViewportMetrics(
            widthPx = 800, heightPx = 480,
            insetLeftPx = 260, insetTopPx = 20, insetRightPx = 20, insetBottomPx = 20, density = 1.0f
        )

        val recordedForGif = mutableListOf<RecordedFrame>()
        val results = listOf(
            replayAcrossViewport("Phone_Standard_1000m", route, standardPhone, 1000.0, isAndroidAuto = false),
            replayAcrossViewport("Phone_Standard_500m", route, standardPhone, 500.0, isAndroidAuto = false),
            replayAcrossViewport("Phone_Small_1000m", route, smallPhone, 1000.0, isAndroidAuto = false, captureFrames = recordedForGif),
            replayAcrossViewport("Phone_Small_500m", route, smallPhone, 500.0, isAndroidAuto = false),
            replayAcrossViewport("AndroidAuto_1000m", route, androidAuto, 1000.0, isAndroidAuto = true),
            replayAcrossViewport("AndroidAuto_500m", route, androidAuto, 500.0, isAndroidAuto = true)
        )

        for (r in results) {
            assertTrue("${r.label} must evaluate >= 500 intermediate frames", r.totalFrames >= 500)
            assertEquals("${r.label} must have ZERO frames where vehicle marker leaves unobstructed map area", 0, r.outOfBoundsFrames)
            assertTrue("${r.label} must converge with zero lag accumulation (maxEndLag=${r.maxMarkerToFixLagMeters}m)", r.maxMarkerToFixLagMeters < 0.05)
            assertTrue("${r.label} marker must stay above bottom inset (minBottomClear=${r.minBottomClearancePx}px)", r.minBottomClearancePx >= 0f)
            assertTrue("${r.label} marker must stay below top HUD/lane panel (minTopClear=${r.minTopClearancePx}px)", r.minTopClearancePx >= 0f)
        }

        // Export animated GIF + keyframe PNGs of intermediate frames for visual inspection
        val outDir = File("build/outputs/regression_recordings").apply { mkdirs() }
        val gifFile = File(outDir, "turn_focus_sync_approach_turn_exit.gif")
        exportAnimatedGifAndKeyframes(route, smallPhone, recordedForGif, gifFile, outDir)
        assertTrue("Animated GIF recording must be written and non-empty", gifFile.exists() && gifFile.length() > 10_000L)
        println("RECORDING_ARTIFACT | gif=${gifFile.absolutePath} (${gifFile.length()} bytes, frames=${recordedForGif.size})")
    }

    @Test
    fun bug2_manualPanYieldsCameraWhileMarkerUpdates_andRecenterRestoresSynchronization() {
        val animator = SynchronizedFollowAnimator()
        val pad = doubleArrayOf(0.0, 400.0, 0.0, 180.0)
        animator.onNavigationTargetUpdate(
            nowMs = 1_000L, fixTimestampMs = 1_000L,
            markerLat = 44.0500, markerLon = -123.0800, markerBearing = 0f,
            camLat = 44.0504, camLon = -123.0800, camZoom = 16.2, camTilt = 55.0, camBearing = 0.0,
            padding = pad, durationMs = 400, isFollowingCamera = true
        )
        // User starts panning manually -> camera updates stop immediately
        animator.pauseCameraFollow()
        animator.onMarkerOnlyUpdate(
            nowMs = 1_500L, fixTimestampMs = 1_500L,
            markerLat = 44.0508, markerLon = -123.0800, markerBearing = 2f, durationMs = 200
        )
        val midPanPose = animator.sample(1_600L)!!
        assertFalse("Camera must remain yielded during manual pan/pinch", midPanPose.updateCamera)
        assertTrue("Vehicle marker must continue advancing smoothly during manual pan", midPanPose.markerLat > 44.0502)

        // User taps Recenter -> synchronized camera + marker resume immediately from latest reliable fix
        animator.resumeCameraFollow()
        animator.onNavigationTargetUpdate(
            nowMs = 2_000L, fixTimestampMs = 2_000L,
            markerLat = 44.0512, markerLon = -123.0800, markerBearing = 0f,
            camLat = 44.0515, camLon = -123.0800, camZoom = 16.5, camTilt = 55.0, camBearing = 0.0,
            padding = pad, durationMs = 300, isFollowingCamera = true, useEaseOut = true
        )
        val recenteredEnd = animator.sample(2_300L)!!
        assertTrue("Recenter must re-enable synchronized camera follow", recenteredEnd.updateCamera)
        assertEquals(44.0512, recenteredEnd.markerLat, 1e-6)
        assertEquals(44.0515, recenteredEnd.camLat, 1e-6)
    }

    private fun exportAnimatedGifAndKeyframes(
        route: OfflineRoute,
        viewport: MapViewportMetrics,
        frames: List<RecordedFrame>,
        gifFile: File,
        outDir: File
    ) {
        val scale = 0.5f // 360x640 crisp recording matching small phone proportions
        val imgW = (viewport.widthPx * scale).toInt()
        val imgH = (viewport.heightPx * scale).toInt()
        val rendered = frames.mapIndexed { idx, f -> renderFrameImage(route, viewport, f, imgW, imgH, scale, idx + 1, frames.size) }

        // Save 4 keyframe PNGs for direct visual inspection
        if (rendered.isNotEmpty()) {
            val keyIndices = listOf(
                0 to "frame_01_approach_1km.png",
                (rendered.size / 3).coerceIn(0, rendered.lastIndex) to "frame_02_tighten_lane_panel.png",
                frames.indexOfFirst { it.isGpsGapRecovered }.takeIf { it >= 0 }?.let { it to "frame_03_gps_gap_recovered.png" }
                    ?: ((rendered.size * 2 / 3).coerceIn(0, rendered.lastIndex) to "frame_03_mid_turn_exit.png"),
                (rendered.size - 1) to "frame_04_exit_ramp_established.png"
            )
            keyIndices.forEach { (i, name) -> ImageIO.write(rendered[i], "png", File(outDir, name)) }
        }

        // Write standard multi-frame animated GIF via JVM ImageIO GIF writer
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        FileImageOutputStream(gifFile).use { ios ->
            writer.output = ios
            writer.prepareWriteSequence(null)
            for (img in rendered) {
                val writeParam = writer.defaultWriteParam
                val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB), writeParam)
                val metaName = meta.nativeMetadataFormatName
                val root = meta.getAsTree(metaName) as IIOMetadataNode
                val gce = IIOMetadataNode("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", "12") // 120 ms per frame
                    setAttribute("transparentColorIndex", "0")
                }
                root.appendChild(gce)
                val appExts = IIOMetadataNode("ApplicationExtensions")
                val appExt = IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(0x1, 0x0, 0x0)
                }
                appExts.appendChild(appExt)
                root.appendChild(appExts)
                meta.setFromTree(metaName, root)
                writer.writeToSequence(IIOImage(img, null, meta), writeParam)
            }
            writer.endWriteSequence()
            writer.dispose()
        }
    }

    private fun renderFrameImage(
        route: OfflineRoute,
        viewport: MapViewportMetrics,
        f: RecordedFrame,
        w: Int,
        h: Int,
        scale: Float,
        frameNum: Int,
        total: Int
    ): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // 1. Dark map background
        g.color = Color(0x0F, 0x17, 0x2A)
        g.fillRect(0, 0, w, h)

        // 2. Project and draw route polyline & blue recommended lane strip
        val projectedPts = route.shape.map { pt ->
            NavigationViewportFraming.projectLatLonToScreen(
                pt, f.camLat, f.camLon, f.camZoom, f.camTilt, f.camBearing, f.padding, viewport
            )
        }
        g.stroke = BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(0x33, 0x41, 0x55)
        for (i in 0 until projectedPts.lastIndex) {
            val a = projectedPts[i]; val b = projectedPts[i + 1]
            g.drawLine((a.x * scale).toInt(), (a.y * scale).toInt(), (b.x * scale).toInt(), (b.y * scale).toInt())
        }
        // Highlight blue lane along approach into exit ramp
        g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(0x25, 0x63, 0xEB)
        for (i in 40 until projectedPts.lastIndex) {
            val a = projectedPts[i]; val b = projectedPts[i + 1]
            g.drawLine((a.x * scale).toInt(), (a.y * scale).toInt(), (b.x * scale).toInt(), (b.y * scale).toInt())
        }

        // 3. Draw unobstructed safe map boundary (green dashed box)
        val uLeft = (viewport.usableLeft * scale).toInt()
        val uTop = (viewport.usableTop * scale).toInt()
        val uW = (viewport.usableWidth * scale).toInt()
        val uH = (viewport.usableHeight * scale).toInt()
        g.color = Color(0x22, 0xC5, 0x5E)
        g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
        g.drawRect(uLeft + 1, uTop + 1, uW - 2, uH - 2)

        // 4. Draw vehicle marker at its true projected screen coordinate
        val vehPt = NavigationViewportFraming.projectLatLonToScreen(
            LatLon(f.markerLat, f.markerLon),
            f.camLat, f.camLon, f.camZoom, f.camTilt, f.camBearing, f.padding, viewport
        )
        val vx = (vehPt.x * scale).toDouble()
        val vy = (vehPt.y * scale).toDouble()
        val r = (viewport.markerRadiusPx * scale).toDouble()
        val oldTx = g.transform
        g.transform = AffineTransform(oldTx).apply { translate(vx, vy) }
        g.color = Color(0x38, 0xBD, 0xF8, 90)
        g.fillOval((-r).toInt(), (-r).toInt(), (r * 2).toInt(), (r * 2).toInt())
        val arrow = Path2D.Double().apply {
            moveTo(0.0, -r * 0.85)
            lineTo(r * 0.65, r * 0.72)
            lineTo(0.0, r * 0.35)
            lineTo(-r * 0.65, r * 0.72)
            closePath()
        }
        g.color = Color(0x38, 0xBD, 0xF8)
        g.fill(arrow)
        g.color = Color.WHITE
        g.stroke = BasicStroke(2f)
        g.draw(arrow)
        g.transform = oldTx

        // 5. Top HUD + Dedicated Blue Lane Panel + Junction Card overlay representation
        g.color = Color(0x1E, 0x29, 0x3B)
        g.fillRoundRect(10, 8, w - 20, uTop - 16, 14, 14)
        g.color = Color.WHITE
        g.font = Font("SansSerif", Font.BOLD, 13)
        g.drawString("Exit 195B - Beltline Hwy (${f.remainingMeters.toInt()} m)", 20, 32)
        // 4-lane panel with lane 2 bright blue
        val laneY = 44
        val laneW = (w - 60) / 4
        for (i in 0..3) {
            g.color = if (i == 2) Color(0x25, 0x63, 0xEB) else Color(0x47, 0x55, 0x69)
            g.fillRoundRect(20 + i * (laneW + 6), laneY, laneW, 44, 8, 8)
            g.color = Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 12)
            g.drawString(if (i == 2) "BLUE" else "|", 32 + i * (laneW + 6), laneY + 26)
        }
        g.color = Color(0x94, 0xA3, 0xB8)
        g.font = Font("SansSerif", Font.PLAIN, 11)
        g.drawString("Frame $frameNum/$total | ${f.title} | z=${"%.2f".format(f.camZoom)}", 20, uTop - 22)

        // 6. Bottom ETA bar overlay representation
        g.color = Color(0x1E, 0x29, 0x3B)
        g.fillRoundRect(10, uTop + uH + 6, w - 20, h - (uTop + uH) - 14, 14, 14)
        g.color = Color(0x4A, 0xDE, 0x80)
        g.font = Font("SansSerif", Font.BOLD, 12)
        val ok = viewport.isMarkerFullyInside(vehPt)
        g.drawString("Marker inside safe area: $ok | y=${vehPt.y.toInt()}px", 20, uTop + uH + 34)

        g.dispose()
        return img
    }
}
