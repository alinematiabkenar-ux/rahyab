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
import com.example.i18n.stringsFor
import com.example.navigation.SpeedRingState
import com.example.navigation.SpeedometerRingPolicy
import kotlin.math.roundToInt

/**
 * Lightweight Canvas overlay rendered atop the Android Auto MapLibre surface:
 * 1. Draws a centered message inside `visibleArea` when no offline state pack is installed.
 * 2. V3: Draws the circular live speedometer ring side-by-side with the US-style speed limit
 *    sign in the lower-start corner of `visibleArea`, with ring color thresholds and "est."
 *    label for HPMS estimated speed limits.
 */
class CarSpeedOverlayView(context: Context) : View(context) {

    private var visibleArea: Rect = Rect()
    private var noPackMessage: String? = null
    private var showSpeedOverlay: Boolean = false
    private var speedMps: Float = 0f
    private var speedLimitMph: Int? = null
    private var isOverspeed: Boolean = false
    private var overspeedToleranceMph: Int = 5
    private var speedLimitSource: String = "none"
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
    private val estLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#475569")
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
        darkMode: Boolean,
        toleranceMph: Int = 5,
        limitSource: String = "none"
    ) {
        noPackMessage = noPackMsg
        showSpeedOverlay = showSpeed
        speedMps = currentSpeedMps
        speedLimitMph = currentLimitMph
        isOverspeed = overspeed
        overspeedToleranceMph = toleranceMph
        speedLimitSource = limitSource
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
        val ringDiameter = 72f * density
        val radius = ringDiameter / 2f
        val gap = 10f * density
        val cx = safe.left + margin + radius
        val cy = safe.bottom - margin - radius

        val ringState = SpeedometerRingPolicy.evaluate(
            currentSpeedMps = speedMps,
            speedLimitMph = speedLimitMph,
            overspeedToleranceMph = overspeedToleranceMph,
            unit = distanceUnit
        )
        val effectiveOverspeed = isOverspeed || ringState == SpeedRingState.OVERSPEED

        // 1. Live circular speedometer ring
        cardBgPaint.color = when {
            effectiveOverspeed -> Color.parseColor("#DC2626")
            isDarkMode -> Color.argb(236, 15, 23, 42)
            else -> Color.argb(242, 255, 255, 255)
        }
        canvas.drawCircle(cx, cy, radius - 2f * density, cardBgPaint)

        cardBorderPaint.color = when {
            effectiveOverspeed -> Color.parseColor("#EF4444")
            ringState == SpeedRingState.APPROACHING_LIMIT -> Color.parseColor("#FACC15")
            else -> Color.parseColor("#38BDF8")
        }
        cardBorderPaint.strokeWidth = 4.5f * density
        canvas.drawCircle(cx, cy, radius - 2.5f * density, cardBorderPaint)

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
        speedValuePaint.color = if (effectiveOverspeed || isDarkMode) Color.WHITE else Color.parseColor("#0F172A")
        speedValuePaint.textSize = 24f * density
        unitLabelPaint.color = if (effectiveOverspeed) Color.WHITE else Color.parseColor("#94A3B8")
        unitLabelPaint.textSize = 10.5f * density
        canvas.drawText(speedText, cx, cy + 5f * density, speedValuePaint)
        canvas.drawText(unitText, cx, cy + 20f * density, unitLabelPaint)

        // 2. Speed limit sign side-by-side next to the circular speedometer
        val limitMph = speedLimitMph ?: return
        val displayLimit = if (distanceUnit == DistanceUnit.METRIC) {
            ((limitMph * 1.60934) / 5.0).roundToInt() * 5
        } else {
            limitMph
        }
        val isEstimated = SpeedometerRingPolicy.isEstimatedLimitSource(speedLimitSource)
        val limitW = 64f * density
        val limitH = if (isEstimated) 78f * density else 70f * density
        val limitLeft = cx + radius + gap
        val limitTop = cy - limitH / 2f
        val limitRect = RectF(limitLeft, limitTop, limitLeft + limitW, limitTop + limitH)
        limitBorderPaint.strokeWidth = 3f * density
        canvas.drawRoundRect(limitRect, 10f * density, 10f * density, limitBgPaint)
        canvas.drawRoundRect(limitRect, 10f * density, 10f * density, limitBorderPaint)

        limitHeaderPaint.textSize = 9.5f * density
        canvas.drawText("LIMIT", limitRect.centerX(), limitRect.top + 17f * density, limitHeaderPaint)
        speedValuePaint.color = if (effectiveOverspeed) Color.parseColor("#DC2626") else Color.parseColor("#0F172A")
        speedValuePaint.textSize = 25f * density
        val numberBaseline = if (isEstimated) limitRect.bottom - 20f * density else limitRect.bottom - 13f * density
        canvas.drawText(
            BilingualFormatter.formatNumber(displayLimit, language),
            limitRect.centerX(),
            numberBaseline,
            speedValuePaint
        )
        if (isEstimated) {
            estLabelPaint.textSize = 9f * density
            canvas.drawText(
                stringsFor(language).speedLimitEstimatedLabel,
                limitRect.centerX(),
                limitRect.bottom - 6f * density,
                estLabelPaint
            )
        }
    }
}
