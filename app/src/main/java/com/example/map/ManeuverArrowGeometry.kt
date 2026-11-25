package com.example.map

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.OfflineRoute
import com.example.navigation.TravelMode
import com.example.navigation.ValhallaResponseParser

data class ManeuverArrowData(
    val points: List<LatLon>,
    val tip: LatLon,
    val tipBearingDegrees: Float
)

/**
 * V1: Pure Kotlin geometry builder for the crisp white-with-dark-outline maneuver arrow rendered
 * directly on the map around the upcoming turn or highway exit (40 m before to 25 m after).
 *
 * Visibility rules:
 * - Active ONLY in [TravelMode.DRIVE] and [TravelMode.BIKE] (never [TravelMode.WALK]).
 * - Shown only when upcoming maneuver is within 500 m (city) or 1000 m (highway).
 * - Hidden once passed (`snappedShapeIndex >= maneuver.beginShapeIndex`) or for non-turn maneuvers.
 */
object ManeuverArrowGeometry {

    const val BEFORE_MANEUVER_METERS = 40.0
    const val AFTER_MANEUVER_METERS = 25.0
    const val MAX_DISTANCE_CITY_METERS = 500.0
    const val MAX_DISTANCE_HIGHWAY_METERS = 1000.0

    fun build(
        route: OfflineRoute?,
        currentManeuverIndex: Int,
        snappedShapeIndex: Int,
        distanceToNextManeuverMeters: Double,
        travelMode: TravelMode
    ): ManeuverArrowData? {
        if (travelMode == TravelMode.WALK) return null
        if (route == null || route.shape.size < 2) return null
        val maneuver = route.maneuvers.getOrNull(currentManeuverIndex) ?: return null
        if (!isArrowEligibleManeuver(maneuver.type)) return null

        val maxDistance = if (maneuver.isHighwayExitOrSplit || maneuver.isHighwayRoad) {
            MAX_DISTANCE_HIGHWAY_METERS
        } else {
            MAX_DISTANCE_CITY_METERS
        }
        if (distanceToNextManeuverMeters > maxDistance || distanceToNextManeuverMeters < 0.0) return null

        val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, route.shape.lastIndex)
        if (snappedShapeIndex >= pivotIdx || pivotIdx <= 0 || pivotIdx >= route.shape.lastIndex) {
            return null
        }

        val beforePts = collectDistancePointsBackward(route.shape, pivotIdx, BEFORE_MANEUVER_METERS)
        val afterPts = collectDistancePointsForward(route.shape, pivotIdx, AFTER_MANEUVER_METERS)

        val combined = ArrayList<LatLon>(beforePts.size + afterPts.size + 1)
        combined.addAll(beforePts)
        combined.add(route.shape[pivotIdx])
        combined.addAll(afterPts)

        if (combined.size < 2) return null
        val tip = combined.last()
        val prev = combined[combined.lastIndex - 1]
        val bearing = ValhallaResponseParser.bearingDegrees(prev, tip).toFloat()
        return ManeuverArrowData(
            points = combined,
            tip = tip,
            tipBearingDegrees = bearing
        )
    }

    private fun isArrowEligibleManeuver(type: ManeuverType): Boolean = when (type) {
        ManeuverType.DEPART,
        ManeuverType.STRAIGHT,
        ManeuverType.ARRIVE,
        ManeuverType.ARRIVE_LEFT,
        ManeuverType.ARRIVE_RIGHT,
        ManeuverType.FERRY -> false
        else -> true
    }

    private fun collectDistancePointsBackward(
        shape: List<LatLon>,
        pivotIdx: Int,
        targetMeters: Double
    ): List<LatLon> {
        val reversed = ArrayList<LatLon>()
        var accumulated = 0.0
        var i = pivotIdx
        while (i > 0 && accumulated < targetMeters) {
            val cur = shape[i]
            val prev = shape[i - 1]
            val seg = OfflinePlaceSearchEngine.haversineMeters(cur.lat, cur.lon, prev.lat, prev.lon)
            if (seg <= 0.05) {
                i--
                continue
            }
            if (accumulated + seg >= targetMeters) {
                val frac = ((targetMeters - accumulated) / seg).coerceIn(0.0, 1.0)
                reversed.add(interpolate(cur, prev, frac))
                break
            } else {
                reversed.add(prev)
                accumulated += seg
                i--
            }
        }
        return reversed.asReversed()
    }

    private fun collectDistancePointsForward(
        shape: List<LatLon>,
        pivotIdx: Int,
        targetMeters: Double
    ): List<LatLon> {
        val out = ArrayList<LatLon>()
        var accumulated = 0.0
        var i = pivotIdx
        while (i < shape.lastIndex && accumulated < targetMeters) {
            val cur = shape[i]
            val next = shape[i + 1]
            val seg = OfflinePlaceSearchEngine.haversineMeters(cur.lat, cur.lon, next.lat, next.lon)
            if (seg <= 0.05) {
                i++
                continue
            }
            if (accumulated + seg >= targetMeters) {
                val frac = ((targetMeters - accumulated) / seg).coerceIn(0.0, 1.0)
                out.add(interpolate(cur, next, frac))
                break
            } else {
                out.add(next)
                accumulated += seg
                i++
            }
        }
        return out
    }

    private fun interpolate(a: LatLon, b: LatLon, fraction: Double): LatLon =
        LatLon(
            lat = a.lat + (b.lat - a.lat) * fraction,
            lon = a.lon + (b.lon - a.lon) * fraction
        )
}
