package com.example.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.navigation.LaneDirection
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneSlotDecision
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vector arrow & lane-marking drawing primitives for [FullLaneDiagramOverlay].
 *
 * Supports all 11 Valhalla turn-lane directions, combined multi-direction lanes (dimmed unused
 * directions painted underneath active directions on a shared lower stem), unmarked lane slots,
 * and configurable vertical arrow scaling so ACTIVE target arrows render ~1.5x taller than
 * neighboring INACTIVE arrows cleanly inside their lane column without overlapping neighbors.
 */
internal object LaneArrowCanvasDrawer {

    fun DrawScope.drawDashedLaneDivider(color: Color) {
        val cx = size.width * 0.5f
        val dashLen = (size.height * 0.14f).coerceIn(6f, 16f)
        val gapLen = (dashLen * 0.72f).coerceAtLeast(4f)
        drawLine(
            color = color,
            start = Offset(cx, size.height * 0.08f),
            end = Offset(cx, size.height * 0.92f),
            strokeWidth = (size.width * 0.55f).coerceIn(2f, 4f),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLen, gapLen), 0f)
        )
    }

    fun DrawScope.drawLaneSlotArrows(
        slot: LaneSlotDecision,
        primaryColor: Color,
        dimmedColor: Color,
        heightFraction: Float
    ) {
        val dirs = slot.directions
        if (slot.isUnmarked && dirs.isEmpty()) {
            drawUnmarkedLaneGlyph(dimmedColor)
            return
        }
        if (dirs.isEmpty()) return

        val highlightedDirs: Set<LaneDirection> = when (slot.tier) {
            LaneHighlightTier.ACTIVE, LaneHighlightTier.VALID ->
                slot.activeDirections.ifEmpty { dirs }.toSet()
            LaneHighlightTier.INACTIVE -> emptySet()
        }

        val isCombined = dirs.size > 1
        val scaledMinDim = minOf(size.width, size.height * heightFraction)
        val baseStroke = (scaledMinDim * if (isCombined) 0.115f else 0.145f).coerceIn(2.6f, 7.2f)
        val dimStroke = (baseStroke * 0.80f).coerceAtLeast(2.2f)
        val boldStroke = when (slot.tier) {
            LaneHighlightTier.ACTIVE -> (baseStroke * 1.18f).coerceAtMost(8.5f)
            LaneHighlightTier.VALID -> baseStroke * 1.02f
            LaneHighlightTier.INACTIVE -> dimStroke
        }

        // Pass 1: Non-highlighted directions underneath
        for (dir in dirs) {
            if (dir !in highlightedDirs) {
                drawSingleArrow(
                    direction = dir,
                    color = dimmedColor,
                    strokeWidth = dimStroke,
                    isCombined = isCombined,
                    heightFraction = heightFraction,
                    filledHead = false
                )
            }
        }

        // Pass 2: Highlighted Valhalla route directions crisply on top
        for (dir in dirs) {
            if (dir in highlightedDirs) {
                drawSingleArrow(
                    direction = dir,
                    color = primaryColor,
                    strokeWidth = boldStroke,
                    isCombined = isCombined,
                    heightFraction = heightFraction,
                    filledHead = slot.tier == LaneHighlightTier.ACTIVE
                )
            }
        }
    }

    private fun DrawScope.drawUnmarkedLaneGlyph(color: Color) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val r = (size.minDimension * 0.08f).coerceIn(2.5f, 5f)
        drawCircle(color = color.copy(alpha = 0.65f), radius = r, center = Offset(cx, cy - r * 3f))
        drawCircle(color = color.copy(alpha = 0.65f), radius = r, center = Offset(cx, cy))
        drawCircle(color = color.copy(alpha = 0.65f), radius = r, center = Offset(cx, cy + r * 3f))
    }

    private fun DrawScope.drawSingleArrow(
        direction: LaneDirection,
        color: Color,
        strokeWidth: Float,
        isCombined: Boolean,
        heightFraction: Float,
        filledHead: Boolean
    ) {
        val w = size.width
        val totalH = size.height
        val usableH = totalH * heightFraction.coerceIn(0.45f, 1.0f)
        val topOffset = (totalH - usableH) * 0.52f

        fun y(frac: Float): Float = topOffset + usableH * frac

        val cx = w * 0.50f
        val bottomY = y(0.94f)
        val stemSplitY = y(0.56f)
        val turnY = y(0.42f)
        val topY = y(0.08f)

        val stemX = if (isCombined) cx else when (direction) {
            LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> w * 0.62f
            LaneDirection.SLIGHT_LEFT -> w * 0.58f
            LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT -> w * 0.38f
            LaneDirection.SLIGHT_RIGHT -> w * 0.42f
            LaneDirection.MERGE_LEFT -> w * 0.66f
            LaneDirection.MERGE_RIGHT -> w * 0.34f
            else -> cx
        }

        val headScale = minOf(w, usableH) * if (isCombined) 0.22f else 0.28f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path()

        when (direction) {
            LaneDirection.THROUGH -> {
                path.moveTo(cx, bottomY)
                path.lineTo(cx, topY + headScale * 0.35f)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(cx, topY), Offset(cx, bottomY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.LEFT -> {
                val tipX = w * 0.14f
                path.moveTo(stemX, bottomY)
                path.lineTo(stemX, stemSplitY)
                path.quadraticTo(stemX, turnY, (stemX + tipX) * 0.52f, turnY)
                path.lineTo(tipX + headScale * 0.25f, turnY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(tipX, turnY), Offset(stemX, turnY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.RIGHT -> {
                val tipX = w * 0.86f
                path.moveTo(stemX, bottomY)
                path.lineTo(stemX, stemSplitY)
                path.quadraticTo(stemX, turnY, (stemX + tipX) * 0.48f, turnY)
                path.lineTo(tipX - headScale * 0.25f, turnY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(tipX, turnY), Offset(stemX, turnY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.SLIGHT_LEFT -> {
                val tipX = w * 0.20f
                val tipY = y(0.14f)
                path.moveTo(stemX, bottomY)
                path.lineTo(stemX, stemSplitY)
                path.lineTo(tipX, tipY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(tipX, tipY), Offset(stemX, stemSplitY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.SLIGHT_RIGHT -> {
                val tipX = w * 0.80f
                val tipY = y(0.14f)
                path.moveTo(stemX, bottomY)
                path.lineTo(stemX, stemSplitY)
                path.lineTo(tipX, tipY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(tipX, tipY), Offset(stemX, stemSplitY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.SHARP_LEFT -> {
                val tipX = w * 0.16f
                val tipY = y(0.70f)
                val apexY = y(0.32f)
                path.moveTo(stemX, bottomY)
                path.lineTo(stemX, apexY)
                path.lineTo(tipX, tipY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(tipX, tipY), Offset(stemX, apexY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.SHARP_RIGHT -> {
                val tipX = w * 0.84f
                val tipY = y(0.70f)
                val apexY = y(0.32f)
                path.moveTo(stemX, bottomY)
                path.lineTo(stemX, apexY)
                path.lineTo(tipX, tipY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(tipX, tipY), Offset(stemX, apexY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.U_TURN_LEFT, LaneDirection.U_TURN_RIGHT -> {
                val dirSign = if (direction == LaneDirection.U_TURN_LEFT) -1f else 1f
                val startX = if (isCombined) cx else cx - dirSign * w * 0.18f
                val endX = if (isCombined) cx + dirSign * w * 0.30f else cx + dirSign * w * 0.18f
                val midLoopX = (startX + endX) * 0.5f
                val archY = y(0.28f)
                val tipY = y(0.66f)
                path.moveTo(startX, bottomY)
                path.lineTo(startX, archY)
                path.quadraticTo(midLoopX, y(0.06f), endX, archY)
                path.lineTo(endX, tipY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(endX, tipY), Offset(endX, archY), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.MERGE_LEFT -> {
                val startX = if (isCombined) cx else w * 0.70f
                val targetX = w * 0.28f
                val tipY = y(0.12f)
                path.moveTo(startX, bottomY)
                path.cubicTo(startX, y(0.60f), targetX, y(0.52f), targetX, y(0.28f))
                path.lineTo(targetX, tipY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(targetX, tipY), Offset(targetX, y(0.36f)), color, strokeWidth, headScale, filledHead)
            }

            LaneDirection.MERGE_RIGHT -> {
                val startX = if (isCombined) cx else w * 0.30f
                val targetX = w * 0.72f
                val tipY = y(0.12f)
                path.moveTo(startX, bottomY)
                path.cubicTo(startX, y(0.60f), targetX, y(0.52f), targetX, y(0.28f))
                path.lineTo(targetX, tipY)
                drawPath(path, color, style = stroke)
                drawArrowHead(Offset(targetX, tipY), Offset(targetX, y(0.36f)), color, strokeWidth, headScale, filledHead)
            }
        }
    }

    private fun DrawScope.drawArrowHead(
        tip: Offset,
        from: Offset,
        color: Color,
        strokeWidth: Float,
        headLen: Float,
        filled: Boolean
    ) {
        val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
        val spreadRad = Math.toRadians(if (filled) 146.0 else 142.0)
        val leftAngle = angle + spreadRad
        val rightAngle = angle - spreadRad
        val leftPt = Offset(
            tip.x + (headLen * cos(leftAngle)).toFloat(),
            tip.y + (headLen * sin(leftAngle)).toFloat()
        )
        val rightPt = Offset(
            tip.x + (headLen * cos(rightAngle)).toFloat(),
            tip.y + (headLen * sin(rightAngle)).toFloat()
        )
        val headPath = Path().apply {
            moveTo(leftPt.x, leftPt.y)
            lineTo(tip.x, tip.y)
            lineTo(rightPt.x, rightPt.y)
            if (filled) close()
        }
        if (filled) {
            drawPath(headPath, color, style = Fill)
            drawPath(headPath, color, style = Stroke(width = strokeWidth * 0.45f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawPath(headPath, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
