package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.example.navigation.JunctionScene
import com.example.navigation.JunctionSceneType
import com.example.navigation.JunctionSide
import com.example.navigation.LaneConfidence

/**
 * J2: Shared Android Canvas renderer producing Sygic-style highway junction view [Bitmap]s
 * for both Phone UI ([JunctionViewCard]) and Android Auto (`RoutingInfo.setJunctionImage`).
 */
object JunctionViewRenderer {

    private data class Palette(
        val skyTop: Int,
        val skyBottom: Int,
        val terrainTop: Int,
        val terrainBottom: Int,
        val asphalt: Int,
        val asphaltDim: Int,
        val gantry: Int,
        val signBg: Int,
        val signBorder: Int,
        val routeGlow: Int,
        val routeSolid: Int,
        val laneDash: Int,
        val shoulderYellow: Int
    )

    private val DAY_PALETTE = Palette(
        skyTop = Color.parseColor("#1D84D8"),
        skyBottom = Color.parseColor("#93D5FA"),
        terrainTop = Color.parseColor("#5B8C3E"),
        terrainBottom = Color.parseColor("#3E6629"),
        asphalt = Color.parseColor("#3B4252"),
        asphaltDim = Color.parseColor("#2B313E"),
        gantry = Color.parseColor("#94A3B8"),
        signBg = Color.parseColor("#006B54"),
        signBorder = Color.WHITE,
        routeGlow = Color.argb(120, 0, 168, 232),
        routeSolid = Color.parseColor("#00B4F0"),
        laneDash = Color.argb(225, 255, 255, 255),
        shoulderYellow = Color.parseColor("#FACC15")
    )

    private val NIGHT_PALETTE = Palette(
        skyTop = Color.parseColor("#071022"),
        skyBottom = Color.parseColor("#162544"),
        terrainTop = Color.parseColor("#112627"),
        terrainBottom = Color.parseColor("#0A1718"),
        asphalt = Color.parseColor("#232B3A"),
        asphaltDim = Color.parseColor("#171D28"),
        gantry = Color.parseColor("#475569"),
        signBg = Color.parseColor("#004B3B"),
        signBorder = Color.parseColor("#E2E8F0"),
        routeGlow = Color.argb(135, 0, 229, 255),
        routeSolid = Color.parseColor("#00E5FF"),
        laneDash = Color.argb(200, 226, 232, 240),
        shoulderYellow = Color.parseColor("#EAB308")
    )

    fun render(
        scene: JunctionScene,
        widthPx: Int = 720,
        heightPx: Int = 360,
        isNightMode: Boolean = false,
        laneConfidence: LaneConfidence = LaneConfidence.HIGH
    ): Bitmap {
        val w = widthPx.coerceIn(240, 1280)
        val h = heightPx.coerceIn(140, 720)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = if (isNightMode) NIGHT_PALETTE else DAY_PALETTE

        val horizonY = h * 0.46f
        drawSkyAndTerrain(canvas, w.toFloat(), h.toFloat(), horizonY, p)
        drawPerspectiveRoadAndBranch(canvas, w.toFloat(), h.toFloat(), horizonY, scene, p, laneConfidence)
        JunctionViewSignDrawer.drawOverheadGantryAndSign(
            canvas = canvas,
            w = w.toFloat(),
            h = h.toFloat(),
            horizonY = horizonY,
            scene = scene,
            gantryColor = p.gantry,
            signBgColor = p.signBg,
            signBorderColor = p.signBorder
        )
        return bmp
    }

    private fun drawSkyAndTerrain(canvas: Canvas, w: Float, h: Float, horizonY: Float, p: Palette) {
        val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, horizonY, p.skyTop, p.skyBottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, horizonY, skyPaint)

        val terrainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, horizonY, 0f, h, p.terrainTop, p.terrainBottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, horizonY, w, h, terrainPaint)
    }

    private fun drawPerspectiveRoadAndBranch(
        canvas: Canvas,
        w: Float,
        h: Float,
        horizonY: Float,
        scene: JunctionScene,
        p: Palette,
        laneConfidence: LaneConfidence
    ) {
        val total = scene.totalLanes.coerceIn(2, 7)
        val roadBottomLeft = w * 0.10f
        val roadBottomRight = w * 0.90f
        val roadTopLeft = w * 0.36f
        val roadTopRight = w * 0.64f

        val asphaltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.asphalt
        }

        val mainRoadPath = Path().apply {
            moveTo(roadBottomLeft, h)
            lineTo(roadTopLeft, horizonY)
            lineTo(roadTopRight, horizonY)
            lineTo(roadBottomRight, h)
            close()
        }
        canvas.drawPath(mainRoadPath, asphaltPaint)

        val branchRight = scene.side == JunctionSide.RIGHT || scene.type == JunctionSceneType.SPLIT_THREE_WAY
        val branchLeft = scene.side == JunctionSide.LEFT || scene.type == JunctionSceneType.SPLIT_THREE_WAY
        val splitY = horizonY + (h - horizonY) * 0.42f

        if (branchRight && scene.type != JunctionSceneType.LANE_DROP) {
            val branchPath = Path().apply {
                moveTo(lerp(roadTopRight, roadBottomRight, 0.25f) - w * 0.08f, horizonY + (h - horizonY) * 0.25f)
                quadTo(w * 0.72f, splitY * 0.96f, w * 0.96f, horizonY + (h - horizonY) * 0.16f)
                lineTo(w, horizonY + (h - horizonY) * 0.42f)
                quadTo(w * 0.80f, splitY + (h - splitY) * 0.28f, roadBottomRight, h)
                lineTo(lerp(roadTopRight, roadBottomRight, 0.55f) - w * 0.12f, h)
                close()
            }
            canvas.drawPath(branchPath, asphaltPaint)
        }
        if (branchLeft && scene.type != JunctionSceneType.LANE_DROP) {
            val branchPath = Path().apply {
                moveTo(lerp(roadTopLeft, roadBottomLeft, 0.25f) + w * 0.08f, horizonY + (h - horizonY) * 0.25f)
                quadTo(w * 0.28f, splitY * 0.96f, w * 0.04f, horizonY + (h - horizonY) * 0.16f)
                lineTo(0f, horizonY + (h - horizonY) * 0.42f)
                quadTo(w * 0.20f, splitY + (h - splitY) * 0.28f, roadBottomLeft, h)
                lineTo(lerp(roadTopLeft, roadBottomLeft, 0.55f) + w * 0.12f, h)
                close()
            }
            canvas.drawPath(branchPath, asphaltPaint)
        }

        val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.asphaltDim
            alpha = 115
        }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.routeGlow
            if (laneConfidence == LaneConfidence.MEDIUM) {
                alpha = (Color.alpha(p.routeGlow) * 0.5f).toInt().coerceAtLeast(35)
            }
        }

        if (laneConfidence != LaneConfidence.LOW) {
            val recSet = scene.recommendedLaneIndices.toSet()
            for (idx in 0 until total) {
                val t0 = idx.toFloat() / total
                val t1 = (idx + 1).toFloat() / total
                val b0 = lerp(roadBottomLeft, roadBottomRight, t0)
                val b1 = lerp(roadBottomLeft, roadBottomRight, t1)
                val top0 = lerp(roadTopLeft, roadTopRight, t0)
                val top1 = lerp(roadTopLeft, roadTopRight, t1)

                val isRec = idx in recSet
                val isExitLane = idx in scene.exitLaneIndices && scene.type != JunctionSceneType.LANE_DROP
                val lanePath = Path()
                if (isRec && isExitLane && scene.side == JunctionSide.RIGHT) {
                    lanePath.moveTo(b0, h)
                    lanePath.quadTo(lerp(b0, w * 0.85f, 0.45f), splitY + (h - splitY) * 0.15f, w * 0.93f, horizonY + (h - horizonY) * 0.20f)
                    lanePath.lineTo(w * 0.99f, horizonY + (h - horizonY) * 0.34f)
                    lanePath.quadTo(lerp(b1, w * 0.90f, 0.45f), splitY + (h - splitY) * 0.28f, b1, h)
                    lanePath.close()
                } else if (isRec && isExitLane && scene.side == JunctionSide.LEFT) {
                    lanePath.moveTo(b0, h)
                    lanePath.quadTo(lerp(b0, w * 0.10f, 0.45f), splitY + (h - splitY) * 0.28f, w * 0.01f, horizonY + (h - horizonY) * 0.34f)
                    lanePath.lineTo(w * 0.07f, horizonY + (h - horizonY) * 0.20f)
                    lanePath.quadTo(lerp(b1, w * 0.15f, 0.45f), splitY + (h - splitY) * 0.15f, b1, h)
                    lanePath.close()
                } else {
                    lanePath.moveTo(b0, h)
                    lanePath.lineTo(top0, horizonY)
                    lanePath.lineTo(top1, horizonY)
                    lanePath.lineTo(b1, h)
                    lanePath.close()
                }
                canvas.drawPath(lanePath, if (isRec) highlightPaint else dimPaint)
            }
        }

        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.006f).coerceAtLeast(2.5f)
            color = p.shoulderYellow
        }
        canvas.drawLine(roadBottomLeft, h, roadTopLeft, horizonY, edgePaint)
        edgePaint.color = Color.WHITE
        canvas.drawLine(roadBottomRight, h, roadTopRight, horizonY, edgePaint)

        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.0045f).coerceAtLeast(2f)
            color = p.laneDash
            pathEffect = DashPathEffect(floatArrayOf(h * 0.055f, h * 0.045f), 0f)
        }
        for (i in 1 until total) {
            val t = i.toFloat() / total
            canvas.drawLine(lerp(roadBottomLeft, roadBottomRight, t), h, lerp(roadTopLeft, roadTopRight, t), horizonY, dashPaint)
        }

        if (laneConfidence != LaneConfidence.LOW) {
            drawRouteGuidanceArrow(canvas, w, h, horizonY, roadBottomLeft, roadBottomRight, roadTopLeft, roadTopRight, scene, p)
        }
    }

    private fun drawRouteGuidanceArrow(
        canvas: Canvas,
        w: Float,
        h: Float,
        horizonY: Float,
        roadBottomLeft: Float,
        roadBottomRight: Float,
        roadTopLeft: Float,
        roadTopRight: Float,
        scene: JunctionScene,
        p: Palette
    ) {
        val total = scene.totalLanes.coerceIn(2, 7)
        val rec = scene.recommendedLaneIndices.ifEmpty { listOf(total - 1) }
        val laneCenterFrac = (rec.average().toFloat() + 0.5f) / total

        val startX = lerp(roadBottomLeft, roadBottomRight, laneCenterFrac)
        val startY = h * 0.94f
        val isExitRight = (scene.type == JunctionSceneType.EXIT_RIGHT || scene.type == JunctionSceneType.FORK_RIGHT) ||
            (scene.type == JunctionSceneType.SPLIT_THREE_WAY && scene.side == JunctionSide.RIGHT)
        val isExitLeft = (scene.type == JunctionSceneType.EXIT_LEFT || scene.type == JunctionSceneType.FORK_LEFT) ||
            (scene.type == JunctionSceneType.SPLIT_THREE_WAY && scene.side == JunctionSide.LEFT)

        val endX: Float
        val endY: Float
        val ctrlX: Float
        val ctrlY: Float
        if (isExitRight) {
            endX = w * 0.88f
            endY = horizonY + (h - horizonY) * 0.28f
            ctrlX = lerp(startX, endX, 0.35f)
            ctrlY = horizonY + (h - horizonY) * 0.52f
        } else if (isExitLeft) {
            endX = w * 0.12f
            endY = horizonY + (h - horizonY) * 0.28f
            ctrlX = lerp(startX, endX, 0.35f)
            ctrlY = horizonY + (h - horizonY) * 0.52f
        } else {
            endX = lerp(roadTopLeft, roadTopRight, laneCenterFrac)
            endY = horizonY + (h - horizonY) * 0.18f
            ctrlX = lerp(startX, endX, 0.5f)
            ctrlY = (startY + endY) * 0.5f
        }

        val shaftPath = Path().apply {
            moveTo(startX, startY)
            quadTo(ctrlX, ctrlY, endX, endY)
        }
        canvas.drawPath(shaftPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.036f).coerceAtLeast(12f)
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        })
        canvas.drawPath(shaftPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.025f).coerceAtLeast(8f)
            strokeCap = Paint.Cap.ROUND
            color = p.routeSolid
        })

        val dx = endX - ctrlX
        val dy = endY - ctrlY
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dx / len
        val uy = dy / len
        val px = -uy
        val py = ux
        val headLen = (w * 0.055f).coerceAtLeast(18f)
        val headHalfW = headLen * 0.68f
        val tipX = endX + ux * (headLen * 0.45f)
        val tipY = endY + uy * (headLen * 0.45f)
        val baseX = endX - ux * (headLen * 0.45f)
        val baseY = endY - uy * (headLen * 0.45f)

        val headPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseX + px * headHalfW, baseY + py * headHalfW)
            lineTo(baseX - px * headHalfW, baseY - py * headHalfW)
            close()
        }
        canvas.drawPath(headPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE })
        val innerHead = Path().apply {
            moveTo(tipX - ux * 2.5f, tipY - uy * 2.5f)
            lineTo(baseX + px * (headHalfW * 0.72f), baseY + py * (headHalfW * 0.72f))
            lineTo(baseX - px * (headHalfW * 0.72f), baseY - py * (headHalfW * 0.72f))
            close()
        }
        canvas.drawPath(innerHead, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = p.routeSolid })
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
