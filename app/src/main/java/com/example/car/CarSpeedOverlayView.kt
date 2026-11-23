package com.example.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import kotlin.math.roundToInt

/**
 * Lightweight Canvas overlay rendered atop the Android Auto MapLibre surface:
 * 1. Draws a centered message inside `visibleArea` when no offline state pack is installed.
 * 2. Draws the US-style speed limit sign and live speed badge in the lower-start corner of
 *    `visibleArea` (turning red when over the speed limit).
 */
class CarSpeedOverlayView(context: Context) : View(context) {

    private var visibleArea: Rect = Rect()
    private var noPackMessage: String? = null
    private var showSpeedOverlay: Boolean = false
    private var speedMps: Float = 0f
    private var speedLimitMph: Int? = null
    private var isOverspeed: Boolean = false
    private var distanceUnit: DistanceUnit = DistanceUnit.IMPERIAL
    private var language: AppLanguage = AppLanguage.ENGLISH
    private var isDarkMode: Boolean = true

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val limitBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val limitBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#0F172A")
    }
    private val speedValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val unitLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val limitHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#0F172A")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val noPackTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun updateVisibleArea(rect: Rect) {
        visibleArea = Rect(rect)
        invalidate()
    }

    fun updateState(
        noPackMsg: String?,
        showSpeed: Boolean,
        currentSpeedMps: Float,
        currentLimitMph: Int?,
        overspeed: Boolean,
        unit: DistanceUnit,
        lang: AppLanguage,
        darkMode: Boolean
    ) {
        noPackMessage = noPackMsg
        showSpeedOverlay = showSpeed
        speedMps = currentSpeedMps
        speedLimitMph = currentLimitMph
        isOverspeed = overspeed
        distanceUnit = unit
        language = lang
        isDarkMode = darkMode
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val safeRect = if (visibleArea.width() > 0 && visibleArea.height() > 0) {
            visibleArea
        } else {
            Rect(0, 0, width, height)
        }

        noPackMessage?.let { msg ->
            drawNoPackOverlay(canvas, safeRect, msg, density)
            return
        }

        if (showSpeedOverlay) {
            drawSpeedCluster(canvas, safeRect, density)
        }
    }

    private fun drawNoPackOverlay(canvas: Canvas, safe: Rect, msg: String, density: Float) {
        val pad = 20f * density
        val boxW = (safe.width() - pad * 2).coerceIn(180f * density, 460f * density)
        noPackTextPaint.color = if (isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        noPackTextPaint.textSize = 18f * density
        val layout = StaticLayout.Builder.obtain(msg, 0, msg.length, noPackTextPaint, (boxW - 32f * density).toInt().coerceAtLeast(100))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val boxH = layout.height + 32f * density
        val left = safe.exactCenterX() - boxW / 2f
        val top = safe.exactCenterY() - boxH / 2f
        cardBgPaint.color = if (isDarkMode) Color.argb(232, 15, 23, 42) else Color.argb(238, 248, 250, 252)
        cardBorderPaint.color = Color.parseColor("#38BDF8")
        cardBorderPaint.strokeWidth = 2f * density
        val rect = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, cardBgPaint)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, cardBorderPaint)

        canvas.save()
        canvas.translate(left + 16f * density, top + 16f * density)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawSpeedCluster(canvas: Canvas, safe: Rect, density: Float) {
        val margin = 14f * density
        val badgeW = 64f * density
        val badgeH = 68f * density
        val gap = 8f * density
        val left = safe.left + margin
        var bottom = safe.bottom - margin

        // 1. Live speed pill (red background when overspeed)
        val speedRect = RectF(left, bottom - badgeH, left + badgeW, bottom)
        cardBgPaint.color = when {
            isOverspeed -> Color.parseColor("#DC2626")
            isDarkMode -> Color.argb(230, 15, 23, 42)
            else -> Color.argb(238, 255, 255, 255)
        }
        cardBorderPaint.color = if (isOverspeed) Color.WHITE else Color.parseColor("#38BDF8")
        cardBorderPaint.strokeWidth = 2f * density
        canvas.drawRoundRect(speedRect, 14f * density, 14f * density, cardBgPaint)
        canvas.drawRoundRect(speedRect, 14f * density, 14f * density, cardBorderPaint)

        val displaySpeed = if (distanceUnit == DistanceUnit.METRIC) {
            (speedMps * 3.6f).roundToInt().coerceAtLeast(0)
        } else {
            (speedMps * 2.23694f).roundToInt().coerceAtLeast(0)
        }
        val speedText = BilingualFormatter.formatNumber(displaySpeed, language)
        val unitText = if (distanceUnit == DistanceUnit.METRIC) {
            if (language == AppLanguage.PERSIAN) "کم/س" else "km/h"
        } else {
            if (language == AppLanguage.PERSIAN) "مایل/س" else "mph"
        }
        speedValuePaint.color = if (isOverspeed || isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        speedValuePaint.textSize = 24f * density
        unitLabelPaint.color = if (isOverspeed) Color.WHITE else Color.parseColor("#94A3B8")
        unitLabelPaint.textSize = 11f * density
        canvas.drawText(speedText, speedRect.centerX(), speedRect.top + 38f * density, speedValuePaint)
        canvas.drawText(unitText, speedRect.centerX(), speedRect.bottom - 10f * density, unitLabelPaint)

        // 2. Speed limit sign directly above speed pill (strictly when real OSM maxspeed exists)
        val limitMph = speedLimitMph ?: return
        val displayLimit = if (distanceUnit == DistanceUnit.METRIC) {
            ((limitMph * 1.60934) / 5.0).roundToInt() * 5
        } else {
            limitMph
        }
        bottom = speedRect.top - gap
        val limitH = 74f * density
        val limitRect = RectF(left, bottom - limitH, left + badgeW, bottom)
        limitBorderPaint.strokeWidth = 3f * density
        canvas.drawRoundRect(limitRect, 10f * density, 10f * density, limitBgPaint)
        canvas.drawRoundRect(limitRect, 10f * density, 10f * density, limitBorderPaint)

        limitHeaderPaint.textSize = 9.5f * density
        canvas.drawText("LIMIT", limitRect.centerX(), limitRect.top + 18f * density, limitHeaderPaint)
        speedValuePaint.color = if (isOverspeed) Color.parseColor("#DC2626") else Color.parseColor("#0F172A")
        speedValuePaint.textSize = 26f * density
        canvas.drawText(
            BilingualFormatter.formatNumber(displayLimit, language),
            limitRect.centerX(),
            limitRect.bottom - 14f * density,
            speedValuePaint
        )
    }
}
