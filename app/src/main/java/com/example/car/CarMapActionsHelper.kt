package com.example.car

import android.text.SpannableString
import android.text.Spanned
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.core.graphics.drawable.IconCompat
import com.example.R
import com.example.data.local.SavedPlaceEntity
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PlaceSearchResult
import com.example.i18n.DistanceUnit
import com.example.navigation.LatLon
import kotlin.math.roundToLong

/**
 * Shared helpers for Android Auto icons, map action strip (zoom in/out, recenter, pan),
 * and Car App Distance/Duration spans.
 */
internal object CarMapActionsHelper {

    fun icon(carContext: CarContext, @DrawableRes resId: Int): CarIcon {
        return CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
    }

    fun buildMapActionStrip(
        carContext: CarContext,
        renderer: CarMapSurfaceRenderer
    ): ActionStrip {
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(carContext, R.drawable.ic_car_zoom_in))
                    .setOnClickListener { renderer.zoomIn() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(carContext, R.drawable.ic_car_zoom_out))
                    .setOnClickListener { renderer.zoomOut() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(carContext, R.drawable.ic_car_recenter))
                    .setOnClickListener { renderer.recenter() }
                    .build()
            )
            .addAction(Action.PAN)
            .build()
    }

    fun toCarDistance(meters: Double, unit: DistanceUnit): Distance {
        val safeMeters = meters.coerceAtLeast(0.0)
        return if (unit == DistanceUnit.METRIC) {
            if (safeMeters >= 1000.0) {
                val km = safeMeters / 1000.0
                if (km >= 10.0) {
                    Distance.create(km, Distance.UNIT_KILOMETERS)
                } else {
                    Distance.create(km, Distance.UNIT_KILOMETERS_P1)
                }
            } else {
                Distance.create(safeMeters, Distance.UNIT_METERS)
            }
        } else {
            val miles = safeMeters / 1609.344
            when {
                miles >= 10.0 -> Distance.create(miles, Distance.UNIT_MILES)
                miles >= 0.2 -> Distance.create(miles, Distance.UNIT_MILES_P1)
                else -> Distance.create(safeMeters * 3.28084, Distance.UNIT_FEET)
            }
        }
    }

    fun buildRouteSummarySpannable(
        durationSeconds: Double,
        distanceMeters: Double,
        unit: DistanceUnit,
        suffixLabel: String = ""
    ): CharSequence {
        val durationPart = " "
        val distancePart = " "
        val suffix = if (suffixLabel.isNotBlank()) " · $suffixLabel" else ""
        val raw = "$durationPart · $distancePart$suffix"
        val span = SpannableString(raw)
        span.setSpan(
            DurationSpan.create(durationSeconds.roundToLong().coerceAtLeast(60L)),
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val distStart = 4
        span.setSpan(
            DistanceSpan.create(toCarDistance(distanceMeters, unit)),
            distStart,
            distStart + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    fun savedEntityToPlace(entity: SavedPlaceEntity, ref: LatLon): PlaceSearchResult {
        val dist = OfflinePlaceSearchEngine.haversineMeters(ref.lat, ref.lon, entity.lat, entity.lon)
        return PlaceSearchResult(
            id = "saved_${entity.id}",
            name = entity.name,
            brand = "",
            category = entity.category,
            address = entity.address,
            city = "",
            stateCode = entity.stateCode,
            postcode = "",
            lat = entity.lat,
            lon = entity.lon,
            distanceMeters = dist,
            relevanceScore = 100.0,
            source = "saved_${entity.type.lowercase()}",
            savedId = entity.id
        )
    }
}
