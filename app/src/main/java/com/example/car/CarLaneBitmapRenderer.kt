package com.example.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDirection
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo

/**
 * AA6: Draws a high-contrast Android Auto lane guidance strip bitmap (`Step.Builder.setLanesImage`)
 * synchronized with [LaneGuidanceDecision].
 *
 * - Keeps all lanes in strict physical Left-to-Right order with visible dashed dividers.
 * - Highlights recommended (`ACTIVE`) lanes in bright blue with a crisp white outline and bold white arrow.
 * - Renders secondary (`VALID`) lanes in softer blue tint and neighboring (`INACTIVE`) lanes in neutral gray.
 * - Preserves unmarked/unknown slots in their physical position without fabricating a straight arrow.
 */
object CarLaneBitmapRenderer {

    fun renderLanesIcon(
        lanes: List<LaneInfo>,
        confidence: LaneConfidence = LaneConfidence.HIGH,
        decision: LaneGuidanceDecision? = null
    ): CarIcon? {
        val bmp = renderLanesBitmap(lanes, confidence, decision) ?: return null
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    fun renderLanesBitmap(
        lanes: List<LaneInfo>,
        confidence: LaneConfidence = LaneConfidence.HIGH,
        decision: LaneGuidanceDecision? = null
    ): Bitmap? {
        if (lanes.isEmpty() || confidence == LaneConfidence.LOW) return null
        if (decision != null && (
                decision.shouldSkipLaneChangeInstruction ||
                    !decision.hasActionableLaneGuidance ||
                    decision.confidence == LaneConfidence.LOW
                )
        ) {
            return null
        }

        val visible = lanes.take(8)
        val laneW = 54
        val laneH = 72
        val pad = 6
        val width = (visible.size * laneW + pad * 2).coerceAtLeast(laneW + pad * 2)
        val bmp = Bitmap.createBitmap(width, laneH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59) // #1E293B road asphalt
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), laneH.toFloat()), 12f, 12f, backdropPaint)

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 226, 232, 240)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
        }
        val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (confidence == LaneConfidence.MEDIUM) {
                Color.argb(175, 30, 136, 229)
            } else {
                Color.rgb(30, 136, 229) // #1E88E5 bright blue
            }
            style = Paint.Style.FILL
        }
        val validFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 30, 136, 229)
            style = Paint.Style.FILL
        }
        val inactiveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(71, 85, 105) // #475569 neutral gray
            style = Paint.Style.FILL
        }
        val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
        }
        val inactiveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        val activeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5.4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val secondaryOnActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val inactiveArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.STROKE
            strokeWidth = 3.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val unmarkedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 203, 213, 225)
            style = Paint.Style.FILL
        }

        visible.forEachIndexed { idx, lane ->
            val left = pad + idx * laneW.toFloat()
            val cx = left + laneW / 2f
            if (idx > 0) {
                canvas.drawLine(left, 8f, left, laneH - 8f, dividerPaint)
            }

            val tier = decision?.slots?.getOrNull(idx)?.tier ?: lane.highlightTier
            val slotRect = RectF(left + 4f, 6f, left + laneW - 4f, laneH - 6f)
            val fillPaint = when (tier) {
                LaneHighlightTier.ACTIVE -> activeFillPaint
                LaneHighlightTier.VALID -> validFillPaint
                LaneHighlightTier.INACTIVE -> inactiveFillPaint
            }
            canvas.drawRoundRect(slotRect, 8f, 8f, fillPaint)
            canvas.drawRoundRect(
                slotRect,
                8f,
                8f,
                if (tier == LaneHighlightTier.ACTIVE) activeBorderPaint else inactiveBorderPaint
            )

            if (lane.isUnmarkedSlot || lane.directions.isEmpty()) {
                // Preserve unknown/unmarked lane position without inventing a straight arrow.
                canvas.drawCircle(cx, laneH * 0.5f, 4f, unmarkedPaint)
                return@forEachIndexed
            }

            val scale = when (tier) {
                LaneHighlightTier.ACTIVE -> 1.0f
                LaneHighlightTier.VALID -> 0.84f
                LaneHighlightTier.INACTIVE -> 0.68f
            }
            val sorted = lane.directions.sortedBy { d ->
                lane.isRecommended && (lane.activeDirections.isEmpty() || d in lane.activeDirections)
            }
            for (dir in sorted) {
                val isRecDir = lane.isRecommended &&
                    (lane.activeDirections.isEmpty() || dir in lane.activeDirections)
                val paint = when {
                    isRecDir && tier != LaneHighlightTier.INACTIVE -> activeArrowPaint
                    tier != LaneHighlightTier.INACTIVE -> secondaryOnActivePaint
                    else -> inactiveArrowPaint
                }
                drawArrow(canvas, cx, laneH.toFloat(), dir, scale, paint)
            }
        }

        return bmp
    }

    private fun drawArrow(
        canvas: Canvas,
        cx: Float,
        height: Float,
        dir: LaneDirection,
        scale: Float,
        paint: Paint
    ) {
        val cy = height * 0.5f
        val halfSpan = (height * 0.36f) * scale
        val bottomY = cy + halfSpan
        val topY = cy - halfSpan
        val midY = cy + halfSpan * 0.04f
        val armX = 14f * scale
        val head = 7.5f * scale
        val path = Path()

        when (dir) {
            LaneDirection.THROUGH -> {
                path.moveTo(cx, bottomY)
                path.lineTo(cx, topY)
                path.moveTo(cx - head, topY + head)
                path.lineTo(cx, topY)
                path.lineTo(cx + head, topY + head)
            }
            LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> {
                val tipX = cx - armX
                val tipY = if (dir == LaneDirection.SHARP_LEFT) midY + 7f * scale else midY - 6f * scale
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX + head * 0.9f, tipY - head * 0.8f)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX + head * 0.9f, tipY + head * 0.8f)
            }
            LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT -> {
                val tipX = cx + armX
                val tipY = if (dir == LaneDirection.SHARP_RIGHT) midY + 7f * scale else midY - 6f * scale
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX - head * 0.9f, tipY - head * 0.8f)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX - head * 0.9f, tipY + head * 0.8f)
            }
            LaneDirection.SLIGHT_LEFT, LaneDirection.MERGE_LEFT -> {
                val tipX = cx - armX * 0.85f
                val tipY = topY + 3f * scale
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY + 3f * scale)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX + head * 0.2f, tipY + head)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX + head, tipY + head * 0.25f)
            }
            LaneDirection.SLIGHT_RIGHT, LaneDirection.MERGE_RIGHT -> {
                val tipX = cx + armX * 0.85f
                val tipY = topY + 3f * scale
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY + 3f * scale)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX - head * 0.2f, tipY + head)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX - head, tipY + head * 0.25f)
            }
            LaneDirection.U_TURN_LEFT -> {
                path.moveTo(cx + 5f * scale, bottomY)
                path.lineTo(cx + 5f * scale, topY + 9f * scale)
                path.quadTo(cx + 5f * scale, topY, cx - 4f * scale, topY)
                path.quadTo(cx - 11f * scale, topY, cx - 11f * scale, topY + 11f * scale)
                path.lineTo(cx - 11f * scale, midY + 6f * scale)
                path.moveTo(cx - 16f * scale, midY)
                path.lineTo(cx - 11f * scale, midY + 6f * scale)
                path.lineTo(cx - 6f * scale, midY)
            }
            LaneDirection.U_TURN_RIGHT -> {
                path.moveTo(cx - 5f * scale, bottomY)
                path.lineTo(cx - 5f * scale, topY + 9f * scale)
                path.quadTo(cx - 5f * scale, topY, cx + 4f * scale, topY)
                path.quadTo(cx + 11f * scale, topY, cx + 11f * scale, topY + 11f * scale)
                path.lineTo(cx + 11f * scale, midY + 6f * scale)
                path.moveTo(cx + 6f * scale, midY)
                path.lineTo(cx + 11f * scale, midY + 6f * scale)
                path.lineTo(cx + 16f * scale, midY)
            }
        }
        canvas.drawPath(path, paint)
    }
}
