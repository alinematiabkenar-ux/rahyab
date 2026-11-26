package com.example.data.packs

import com.example.navigation.ValhallaPolylineAndGeo
import kotlin.math.min

/** D3: Row from `<state>_extras.db` table `hpms_lanes`. */
data class HpmsLanesRecord(
    val id: Long,
    val throughLanes: Int,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val polyline: String
)

/** D1/D4: Row from `<state>_extras.db` table `tiger_ranges`. */
data class TigerRangeRecord(
    val id: Long,
    val stateCode: String,
    val street: String,
    val streetNorm: String,
    val city: String,
    val zip: String,
    val fromLeft: Int?,
    val toLeft: Int?,
    val fromRight: Int?,
    val toRight: Int?,
    val parityLeft: String,
    val parityRight: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val polyline: String
)

/** D2/D5: Row from `<state>_extras.db` table `gnis_names`. */
data class GnisNameRecord(
    val id: Long,
    val stateCode: String,
    val name: String,
    val featureClass: String,
    val county: String,
    val lat: Double,
    val lon: Double
)

/** Pure Kotlin matching for D3 `hpms_lanes` through-lane count lookup along a heading. */
object ExtrasV2MatchingHelper {
    const val HPMS_LANES_MAX_METERS = 25.0
    const val HPMS_LANES_MAX_HEADING_DEG = 30.0

    fun matchHpmsThroughLanes(
        lat: Double,
        lon: Double,
        headingDegrees: Float?,
        candidates: List<HpmsLanesRecord>,
        maxDistanceMeters: Double = HPMS_LANES_MAX_METERS,
        maxHeadingDiffDegrees: Double = HPMS_LANES_MAX_HEADING_DEG
    ): Int? {
        if (candidates.isEmpty()) return null
        val bboxPadDeg = 0.00035
        var bestLanes: Int? = null
        var bestDist = maxDistanceMeters

        for (rec in candidates) {
            if (rec.throughLanes !in 1..16) continue
            if (lat < rec.minLat - bboxPadDeg || lat > rec.maxLat + bboxPadDeg ||
                lon < rec.minLon - bboxPadDeg || lon > rec.maxLon + bboxPadDeg
            ) {
                continue
            }
            val pts = ValhallaPolylineAndGeo.decodePolyline6(rec.polyline)
            if (pts.size < 2) continue
            for (i in 0 until pts.lastIndex) {
                val a = pts[i]
                val b = pts[i + 1]
                val dist = ExtrasMatchingHelper.pointToSegmentDistanceMeters(lat, lon, a, b)
                if (dist > bestDist) continue
                if (headingDegrees != null && headingDegrees.isFinite()) {
                    val segBearing = ValhallaPolylineAndGeo.bearingDegrees(a, b)
                    val diffFwd = ValhallaPolylineAndGeo.angleDiffDegrees(headingDegrees.toDouble(), segBearing)
                    val diffRev = ValhallaPolylineAndGeo.angleDiffDegrees(
                        headingDegrees.toDouble(),
                        (segBearing + 180.0) % 360.0
                    )
                    if (min(diffFwd, diffRev) > maxHeadingDiffDegrees) continue
                }
                bestDist = dist
                bestLanes = rec.throughLanes
            }
        }
        return bestLanes
    }
}
