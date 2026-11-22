package com.example.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.maplibre.android.maps.Style

/**
 * Generates crisp, 100% offline vector-drawn bitmaps for MapLibre symbols:
 * - US Interstate (red crest + blue shield), US Route (white shield), and State Route (oval) shields
 * - Common high-zoom POI icons (gas, parking, restaurant, cafe, grocery, pharmacy, hospital, school, bank, EV)
 */
object MapBitmapIcons {

    fun registerShieldsAndPoiIcons(style: Style, isNightMode: Boolean) {
        style.addImage(MapStyleBuilder.IMAGE_SHIELD_INTERSTATE, createInterstateShieldBitmap())
        style.addImage(MapStyleBuilder.IMAGE_SHIELD_US_ROUTE, createUsRouteShieldBitmap(isNightMode))
        style.addImage(MapStyleBuilder.IMAGE_SHIELD_STATE, createStateRouteShieldBitmap(isNightMode))

        style.addImage(MapStyleBuilder.IMAGE_POI_GAS, createPoiBadge("#0284C7", "G"))
        style.addImage(MapStyleBuilder.IMAGE_POI_PARKING, createPoiBadge("#2563EB", "P"))
        style.addImage(MapStyleBuilder.IMAGE_POI_RESTAURANT, createPoiBadge("#EA580C", "R"))
        style.addImage(MapStyleBuilder.IMAGE_POI_CAFE, createPoiBadge("#92400E", "C"))
        style.addImage(MapStyleBuilder.IMAGE_POI_GROCERY, createPoiBadge("#16A34A", "M"))
        style.addImage(MapStyleBuilder.IMAGE_POI_PHARMACY, createPoiCrossBadge("#DC2626", isFilledCross = false))
        style.addImage(MapStyleBuilder.IMAGE_POI_HOSPITAL, createPoiCrossBadge("#DC2626", isFilledCross = true))
        style.addImage(MapStyleBuilder.IMAGE_POI_SCHOOL, createPoiBadge("#7C3AED", "S"))
        style.addImage(MapStyleBuilder.IMAGE_POI_BANK, createPoiBadge("#0F766E", "$"))
        style.addImage(MapStyleBuilder.IMAGE_POI_EV, createPoiEvBadge("#059669"))
    }

    private fun createInterstateShieldBitmap(): Bitmap {
        val w = 72
        val h = 48
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val shieldPath = Path().apply {
            moveTo(6f, 6f)
            lineTo(w - 6f, 6f)
            quadTo(w - 2f, h * 0.55f, w / 2f, h - 3f)
            quadTo(2f, h * 0.55f, 6f, 6f)
            close()
        }

        // Blue lower body
        canvas.drawPath(shieldPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#1D4ED8")
        })

        // Red Interstate top crest clipped to shield
        canvas.save()
        canvas.clipPath(shieldPath)
        canvas.drawRect(0f, 0f, w.toFloat(), 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DC2626")
        })
        canvas.drawLine(0f, 15.5f, w.toFloat(), 15.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2f
            color = Color.WHITE
        })
        canvas.restore()

        // Crisp white outer border
        canvas.drawPath(shieldPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        })
        return bmp
    }

    private fun createUsRouteShieldBitmap(isNightMode: Boolean): Bitmap {
        val w = 70
        val h = 44
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val shieldPath = Path().apply {
            moveTo(10f, 4f)
            lineTo(w - 10f, 4f)
            lineTo(w - 4f, 12f)
            quadTo(w - 4f, h - 10f, w / 2f, h - 3f)
            quadTo(4f, h - 10f, 4f, 12f)
            close()
        }
        val bg = if (isNightMode) Color.parseColor("#F1F5F9") else Color.WHITE
        val border = Color.parseColor("#0F172A")

        canvas.drawPath(shieldPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bg
        })
        canvas.drawPath(shieldPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.2f
            color = border
        })
        return bmp
    }

    private fun createStateRouteShieldBitmap(isNightMode: Boolean): Bitmap {
        val w = 68
        val h = 40
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(3f, 3f, w - 3f, h - 3f)

        val bg = if (isNightMode) Color.parseColor("#E2E8F0") else Color.WHITE
        val border = Color.parseColor("#334155")

        canvas.drawRoundRect(rect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bg
        })
        canvas.drawRoundRect(rect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = border
        })
        return bmp
    }

    private fun createPoiBadge(bgHex: String, glyph: String): Bitmap {
        val size = 44
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        drawPoiCircleBase(canvas, cx, cy, Color.parseColor(bgHex))

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val yOffset = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(glyph, cx, cy - yOffset, textPaint)
        return bmp
    }

    private fun createPoiCrossBadge(bgHex: String, isFilledCross: Boolean): Bitmap {
        val size = 44
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        drawPoiCircleBase(canvas, cx, cy, Color.parseColor(bgHex))

        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val arm = if (isFilledCross) 10.5f else 9f
        val thick = if (isFilledCross) 3.4f else 2.5f
        canvas.drawRoundRect(RectF(cx - thick, cy - arm, cx + thick, cy + arm), 2f, 2f, crossPaint)
        canvas.drawRoundRect(RectF(cx - arm, cy - thick, cx + arm, cy + thick), 2f, 2f, crossPaint)
        return bmp
    }

    private fun createPoiEvBadge(bgHex: String): Bitmap {
        val size = 44
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        drawPoiCircleBase(canvas, cx, cy, Color.parseColor(bgHex))

        // Crisp lightning bolt vector for EV charging station
        val bolt = Path().apply {
            moveTo(cx + 2f, cy - 11f)
            lineTo(cx - 7f, cy + 1f)
            lineTo(cx, cy + 1f)
            lineTo(cx - 2f, cy + 11f)
            lineTo(cx + 7f, cy - 1f)
            lineTo(cx, cy - 1f)
            close()
        }
        canvas.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
        return bmp
    }

    private fun drawPoiCircleBase(canvas: Canvas, cx: Float, cy: Float, bgColor: Int) {
        canvas.drawCircle(cx, cy + 1.5f, 18.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 15, 23, 42)
        })
        canvas.drawCircle(cx, cy, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        })
        canvas.drawCircle(cx, cy, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
        })
    }
}
