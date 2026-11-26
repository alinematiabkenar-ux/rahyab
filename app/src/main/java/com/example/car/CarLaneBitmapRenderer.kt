package com.example.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDirection
import com.example.navigation.LaneInfo

/**
 * AA6: Draws a crisp Android Auto lane guidance strip bitmap (`Step.Builder.setLanesImage`)
 * with recommended/active lanes rendered bright white and non-recommended lanes muted gray.
 */
object CarLaneBitmapRenderer {

    fun renderLanesIcon(
        lanes: List<LaneInfo>,
        confidence: LaneConfidence = LaneConfidence.HIGH
    ): CarIcon? {
        if (lanes.isEmpty() || confidence == LaneConfidence.LOW) return null
        val visible = lanes.take(8)
        val laneW = 48
        val laneH = 64
        val width = (visible.size * laneW).coerceAtLeast(laneW)
        val bmp = Bitmap.createBitmap(width, laneH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 203, 213, 225)
            strokeWidth = 2f
        }
        val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (confidence == LaneConfidence.MEDIUM) {
                Color.argb(175, 147, 197, 253)
            } else {
                Color.WHITE
            }
            style = Paint.Style.STROKE
            strokeWidth = if (confidence == LaneConfidence.MEDIUM) 4.6f else 5.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(125, 148, 163, 184)
            style = Paint.Style.STROKE
            strokeWidth = 4.0f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        visible.forEachIndexed { idx, lane ->
            val cx = idx * laneW + laneW / 2f
            if (idx > 0) {
                val x = idx * laneW.toFloat()
                canvas.drawLine(x, 10f, x, laneH - 10f, dividerPaint)
            }
            val dirs = lane.directions.ifEmpty { listOf(LaneDirection.THROUGH) }
            // Draw inactive arrows first so active/recommended arrow renders crisply on top
            val sorted = dirs.sortedBy { d ->
                lane.isRecommended && (lane.activeDirections.isEmpty() || d in lane.activeDirections)
            }
            for (dir in sorted) {
                val isRec = lane.isRecommended &&
                    (lane.activeDirections.isEmpty() || dir in lane.activeDirections)
                drawArrow(canvas, cx, laneH.toFloat(), dir, if (isRec) activePaint else inactivePaint)
            }
        }

        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    private fun drawArrow(
        canvas: Canvas,
        cx: Float,
        height: Float,
        dir: LaneDirection,
        paint: Paint
    ) {
        val bottomY = height - 10f
        val midY = height * 0.48f
        val topY = 12f
        val path = Path()

        when (dir) {
            LaneDirection.THROUGH -> {
                path.moveTo(cx, bottomY)
                path.lineTo(cx, topY)
                path.moveTo(cx - 8f, topY + 9f)
                path.lineTo(cx, topY)
                path.lineTo(cx + 8f, topY + 9f)
            }
            LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> {
                val tipX = cx - 15f
                val tipY = if (dir == LaneDirection.SHARP_LEFT) midY + 8f else midY - 6f
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX + 7f, tipY - 6f)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX + 7f, tipY + 6f)
            }
            LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT -> {
                val tipX = cx + 15f
                val tipY = if (dir == LaneDirection.SHARP_RIGHT) midY + 8f else midY - 6f
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX - 7f, tipY - 6f)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX - 7f, tipY + 6f)
            }
            LaneDirection.SLIGHT_LEFT, LaneDirection.MERGE_LEFT -> {
                val tipX = cx - 12f
                val tipY = topY + 4f
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY + 4f)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX + 1f, tipY + 9f)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX + 9f, tipY + 2f)
            }
            LaneDirection.SLIGHT_RIGHT, LaneDirection.MERGE_RIGHT -> {
                val tipX = cx + 12f
                val tipY = topY + 4f
                path.moveTo(cx, bottomY)
                path.lineTo(cx, midY + 4f)
                path.lineTo(tipX, tipY)
                path.moveTo(tipX - 1f, tipY + 9f)
                path.lineTo(tipX, tipY)
                path.lineTo(tipX - 9f, tipY + 2f)
            }
            LaneDirection.U_TURN_LEFT -> {
                path.moveTo(cx + 5f, bottomY)
                path.lineTo(cx + 5f, 22f)
                path.quadTo(cx + 5f, 12f, cx - 5f, 12f)
                path.quadTo(cx - 12f, 12f, cx - 12f, 24f)
                path.lineTo(cx - 12f, 36f)
                path.moveTo(cx - 17f, 29f)
                path.lineTo(cx - 12f, 36f)
                path.lineTo(cx - 7f, 29f)
            }
            LaneDirection.U_TURN_RIGHT -> {
                path.moveTo(cx - 5f, bottomY)
                path.lineTo(cx - 5f, 22f)
                path.quadTo(cx - 5f, 12f, cx + 5f, 12f)
                path.quadTo(cx + 12f, 12f, cx + 12f, 24f)
                path.lineTo(cx + 12f, 36f)
                path.moveTo(cx + 7f, 29f)
                path.lineTo(cx + 12f, 36f)
                path.lineTo(cx + 17f, 29f)
            }
        }
        canvas.drawPath(path, paint)
    }
}
