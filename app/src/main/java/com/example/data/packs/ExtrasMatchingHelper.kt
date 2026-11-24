package com.example.data.packs

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.stringsFor
import com.example.navigation.AheadAlert
import com.example.navigation.LatLon
import com.example.navigation.RoadAlertKind
import com.example.navigation.ValhallaPolylineAndGeo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/** X6: Row from `<state>_extras.db` table `fra_crossings`. */
data class FraCrossingRecord(
    val crossingId: String,
    val lat: Double,
    val lon: Double,
    val street: String = "",
    val railroad: String = "",
    val hasGates: Boolean = false,
    val hasLights: Boolean = false,
    val tracks: Int = 0,
    val dailyTrains: Int = 0
)

/** X7: Row from `<state>_extras.db` table `hpms_speed`. */
data class HpmsSpeedRecord(
    val id: Long,
    val speedMph: Int,
    val routeName: String = "",
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val polyline: String
)

/**
 * Pure Kotlin matching logic for X6 (FRA railroad crossing enrichment & FRA-only crossings)
 * and X7 (HPMS speed limit polyline + heading fallback). Zero Android dependencies for fast JVM tests.
 */
object ExtrasMatchingHelper {

    const val FRA_MATCH_MAX_METERS = 40.0
    const val FRA_ROUTE_CORRIDOR_METERS = 30.0
    const val HPMS_MATCH_MAX_METERS = 20.0
    const val HPMS_MATCH_MAX_HEADING_DEG = 30.0

    fun formatFraProtectionDetail(crossing: FraCrossingRecord, language: AppLanguage): String {
        val str = stringsFor(language)
        return when {
            crossing.hasGates -> str.fraWithGates
            crossing.hasLights -> str.fraLightsOnly
            else -> str.fraNoGatesOrLights
        }
    }

    fun findNearestFraCrossing(
        lat: Double,
        lon: Double,
        crossings: List<FraCrossingRecord>,
        maxDistanceMeters: Double = FRA_MATCH_MAX_METERS
    ): FraCrossingRecord? {
        if (crossings.isEmpty()) return null
        var best: FraCrossingRecord? = null
        var bestDist = maxDistanceMeters
        for (c in crossings) {
            val d = OfflinePlaceSearchEngine.haversineMeters(lat, lon, c.lat, c.lon)
            if (d <= bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    fun enrichRailroadAlertWithFra(
        alert: AheadAlert,
        crossings: List<FraCrossingRecord>,
        maxDistanceMeters: Double = FRA_MATCH_MAX_METERS
    ): AheadAlert {
        if (alert.kind != RoadAlertKind.RAILROAD_CROSSING || crossings.isEmpty()) return alert
        val matched = findNearestFraCrossing(alert.lat, alert.lon, crossings, maxDistanceMeters) ?: return alert
        val enDetail = formatFraProtectionDetail(matched, AppLanguage.ENGLISH)
        val faDetail = formatFraProtectionDetail(matched, AppLanguage.PERSIAN)
        val newTitleEn = if (alert.titleEn.contains(enDetail, ignoreCase = true)) {
            alert.titleEn
        } else {
            "${alert.titleEn}, $enDetail"
        }
        val newTitleFa = if (alert.titleFa.contains(faDetail)) {
            alert.titleFa
        } else {
            "${alert.titleFa}، $faDetail"
        }
        val extraInfo = listOf(matched.street, matched.railroad)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        return alert.copy(
            titleEn = newTitleEn,
            titleFa = newTitleFa,
            detail = alert.detail.ifBlank { extraInfo }
        )
    }

    /**
     * X6: Builds [RoadAlertKind.RAILROAD_CROSSING] alerts for FRA crossings sitting along the route
     * that are NOT already represented in OSM [existingHazards] within 40 m.
     */
    fun buildFraOnlyRouteAlerts(
        routeShape: List<LatLon>,
        existingHazards: List<RoadHazardPoint>,
        fraCrossings: List<FraCrossingRecord>,
        corridorMeters: Double = FRA_ROUTE_CORRIDOR_METERS
    ): List<AheadAlert> {
        if (routeShape.size < 2 || fraCrossings.isEmpty()) return emptyList()
        val osmRailroads = existingHazards.filter {
            val t = it.type.lowercase()
            t == "railroad" || t == "railway_crossing"
        }
        val out = mutableListOf<AheadAlert>()
        for (c in fraCrossings) {
            val coveredByOsm = osmRailroads.any { h ->
                OfflinePlaceSearchEngine.haversineMeters(c.lat, c.lon, h.lat, h.lon) <= FRA_MATCH_MAX_METERS
            }
            if (coveredByOsm) continue
            val distToRoute = minDistancePointToPolylineMeters(c.lat, c.lon, routeShape)
            if (distToRoute <= corridorMeters) {
                val enProtection = formatFraProtectionDetail(c, AppLanguage.ENGLISH)
                val faProtection = formatFraProtectionDetail(c, AppLanguage.PERSIAN)
                val detail = listOf(c.street, c.railroad).filter { it.isNotBlank() }.joinToString(" • ")
                out.add(
                    AheadAlert(
                        id = "fra_crossing_${c.crossingId}",
                        kind = RoadAlertKind.RAILROAD_CROSSING,
                        lat = c.lat,
                        lon = c.lon,
                        distanceMeters = 0.0,
                        titleEn = "Railroad crossing ahead, $enProtection",
                        titleFa = "جلوتر تقاطع راه‌آهن است، $faProtection",
                        detail = detail
                    )
                )
            }
        }
        return out
    }

    /**
     * X7: Matches `(lat, lon, headingDegrees)` against candidate HPMS polyline segments.
     * Picks the nearest segment within 20 m whose bearing (forward or reverse) is within 30 deg
     * of `headingDegrees` (if heading is available). Returns `null` if no match qualifies.
     */
    fun matchHpmsSpeedLimitMph(
        lat: Double,
        lon: Double,
        headingDegrees: Float?,
        candidates: List<HpmsSpeedRecord>,
        maxDistanceMeters: Double = HPMS_MATCH_MAX_METERS,
        maxHeadingDiffDegrees: Double = HPMS_MATCH_MAX_HEADING_DEG
    ): Int? {
        if (candidates.isEmpty()) return null
        val bboxPadDeg = 0.0003 // ~33 m bbox guard
        var bestSpeed: Int? = null
        var bestDist = maxDistanceMeters

        for (rec in candidates) {
            if (rec.speedMph !in 10..85) continue
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
                val dist = pointToSegmentDistanceMeters(lat, lon, a, b)
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
                bestSpeed = rec.speedMph
            }
        }
        return bestSpeed
    }

    fun minDistancePointToPolylineMeters(lat: Double, lon: Double, polyline: List<LatLon>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) {
            return OfflinePlaceSearchEngine.haversineMeters(lat, lon, polyline[0].lat, polyline[0].lon)
        }
        var best = Double.MAX_VALUE
        for (i in 0 until polyline.lastIndex) {
            val d = pointToSegmentDistanceMeters(lat, lon, polyline[i], polyline[i + 1])
            if (d < best) best = d
        }
        return best
    }

    fun pointToSegmentDistanceMeters(lat: Double, lon: Double, a: LatLon, b: LatLon): Double {
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cosLat
        val ax = (a.lon - lon) * metersPerDegLon
        val ay = (a.lat - lat) * metersPerDegLat
        val bx = (b.lon - lon) * metersPerDegLon
        val by = (b.lat - lat) * metersPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-6) return hypot(ax, ay)
        val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }
}
