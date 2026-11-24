package com.example.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.navigation.JunctionScene
import com.example.navigation.JunctionSide
import com.example.navigation.RouteShield
import com.example.navigation.RouteShieldKind
import kotlin.math.min

/**
 * Draws the overhead highway gantry, green US MUTCD signpost, EXIT tab, route shields
 * (Interstate, US, State), and verbatim destination lines for [JunctionViewRenderer].
 */
internal object JunctionViewSignDrawer {

    fun drawOverheadGantryAndSign(
        canvas: Canvas,
        w: Float,
        h: Float,
        horizonY: Float,
        scene: JunctionScene,
        gantryColor: Int,
        signBgColor: Int,
        signBorderColor: Int
    ) {
        val gantryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.014f).coerceAtLeast(5f)
            color = gantryColor
        }
        val signTop = if (scene.exitNumber != null) h * 0.14f else h * 0.06f
        val signBottom = horizonY - h * 0.04f
        val signMidY = (signTop + signBottom) * 0.5f

        canvas.drawLine(w * 0.07f, signMidY, w * 0.07f, horizonY + h * 0.18f, gantryPaint)
        canvas.drawLine(w * 0.93f, signMidY, w * 0.93f, horizonY + h * 0.18f, gantryPaint)
        gantryPaint.strokeWidth = (w * 0.010f).coerceAtLeast(3.5f)
        canvas.drawLine(w * 0.07f, signMidY, w * 0.93f, signMidY, gantryPaint)

        val signLeft = w * 0.12f
        val signRight = w * 0.88f
        val signRect = RectF(signLeft, signTop, signRight, signBottom)
        val radius = min(w, h) * 0.032f

        val signFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = signBgColor
        }
        val signBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.005f).coerceAtLeast(2.2f)
            color = signBorderColor
        }
        canvas.drawRoundRect(signRect, radius, radius, signFill)
        canvas.drawRoundRect(signRect, radius, radius, signBorder)

        scene.exitNumber?.let { exitNum ->
            val tabText = "EXIT $exitNum"
            val tabTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (h * 0.062f).coerceIn(13f, 26f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val textW = tabTextPaint.measureText(tabText)
            val tabW = textW + w * 0.045f
            val tabH = h * 0.105f
            val tabLeft = if (scene.side == JunctionSide.LEFT) signLeft else signRight - tabW
            val tabRect = RectF(tabLeft, (signTop - tabH + 2f).coerceAtLeast(2f), tabLeft + tabW, signTop + 2f)
            canvas.drawRoundRect(tabRect, radius * 0.7f, radius * 0.7f, signFill)
            canvas.drawRoundRect(tabRect, radius * 0.7f, radius * 0.7f, signBorder)
            val fm = tabTextPaint.fontMetrics
            val ty = tabRect.centerY() - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText(tabText, tabRect.centerX() - textW * 0.5f, ty, tabTextPaint)
        }

        val innerPadX = w * 0.03f
        var contentTop = signRect.top + h * 0.025f
        val availW = signRect.width() - innerPadX * 2f

        if (scene.shields.isNotEmpty()) {
            val shieldH = (signRect.height() * 0.34f).coerceIn(22f, 44f)
            drawShieldRow(canvas, scene.shields, signRect.centerX(), contentTop, shieldH)
            contentTop += shieldH + h * 0.018f
        }

        val lines = scene.destinationLines.take(2)
        if (lines.isNotEmpty()) {
            val remainingH = (signRect.bottom - contentTop - h * 0.02f).coerceAtLeast(18f)
            val lineH = remainingH / lines.size
            val textSize = (lineH * 0.64f).coerceIn(13f, h * 0.088f)
            val destPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val fm = destPaint.fontMetrics
            lines.forEachIndexed { i, raw ->
                val clipped = ellipsizeText(raw, destPaint, availW)
                val cy = contentTop + lineH * (i + 0.5f) - (fm.ascent + fm.descent) * 0.5f
                canvas.drawText(clipped, signRect.centerX(), cy, destPaint)
            }
        }
    }

    private fun drawShieldRow(
        canvas: Canvas,
        shields: List<RouteShield>,
        centerX: Float,
        topY: Float,
        shieldH: Float
    ) {
        val gap = shieldH * 0.30f
        val widths = shields.map { (shieldH * 1.15f).coerceAtLeast(shieldH * (0.55f + it.text.length * 0.16f)) }
        val totalW = widths.sum() + gap * (shields.size - 1).coerceAtLeast(0)
        var x = centerX - totalW * 0.5f
        shields.forEachIndexed { idx, shield ->
            val sw = widths[idx]
            drawSingleShield(canvas, shield, RectF(x, topY, x + sw, topY + shieldH))
            x += sw + gap
        }
    }

    private fun drawSingleShield(canvas: Canvas, shield: RouteShield, rect: RectF) {
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.WHITE
        }
        when (shield.kind) {
            RouteShieldKind.INTERSTATE -> {
                val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#003F87") }
                val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B91C1C") }
                canvas.drawRoundRect(rect, rect.height() * 0.28f, rect.height() * 0.28f, bluePaint)
                val capRect = RectF(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.30f)
                canvas.drawRoundRect(capRect, rect.height() * 0.20f, rect.height() * 0.20f, redPaint)
                canvas.drawRoundRect(rect, rect.height() * 0.28f, rect.height() * 0.28f, borderPaint)
                drawCenteredShieldText(canvas, shield.text.removePrefix("I-"), rect, Color.WHITE, yOffset = rect.height() * 0.10f)
            }
            RouteShieldKind.US -> {
                val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                borderPaint.color = Color.parseColor("#111827")
                canvas.drawRoundRect(rect, rect.height() * 0.32f, rect.height() * 0.32f, whitePaint)
                canvas.drawRoundRect(rect, rect.height() * 0.32f, rect.height() * 0.32f, borderPaint)
                drawCenteredShieldText(canvas, shield.text.removePrefix("US-"), rect, Color.parseColor("#111827"))
            }
            RouteShieldKind.STATE -> {
                val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                borderPaint.color = Color.parseColor("#0F172A")
                canvas.drawRoundRect(rect, rect.height() * 0.18f, rect.height() * 0.18f, whitePaint)
                canvas.drawRoundRect(rect, rect.height() * 0.18f, rect.height() * 0.18f, borderPaint)
                drawCenteredShieldText(canvas, shield.text.replace("-", " "), rect, Color.parseColor("#0F172A"))
            }
        }
    }

    private fun drawCenteredShieldText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        color: Int,
        yOffset: Float = 0f
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = (rect.height() * 0.48f).coerceIn(10f, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        val y = rect.centerY() + yOffset - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(ellipsizeText(text, paint, rect.width() - 6f), rect.centerX(), y, paint)
    }

    private fun ellipsizeText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
