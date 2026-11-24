package com.example.navigation

import com.example.data.model.UsStatesCatalog
import com.example.data.search.OfflinePlaceSearchEngine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polyline6 encoding/decoding, route geometry utilities, signpost/maneuver type parsing,
 * sharp-curve/state-crossing detection, and fastest/shortest route merging.
 */
internal object ValhallaPolylineAndGeo {

    fun mergeFastestAndShortestRoutes(
        fastestRoutes: List<OfflineRoute>,
        shortestRoute: OfflineRoute?,
        preferShortest: Boolean
    ): List<OfflineRoute> {
        if (fastestRoutes.isEmpty() && shortestRoute == null) return emptyList()
        if (fastestRoutes.isEmpty()) {
            return listOf(shortestRoute!!.copy(id = "route_shortest", isFastestRoute = true, isShortestRoute = true))
        }

        val sortedFastest = fastestRoutes.sortedBy { it.durationSeconds }
        val primaryFastest = sortedFastest.first().copy(id = "route_fastest", isFastestRoute = true)
        val otherAlternates = sortedFastest.drop(1).mapIndexed { idx, alt ->
            alt.copy(id = "route_alt_${idx + 1}", isFastestRoute = false, isShortestRoute = false)
        }

        val candidateShortest = shortestRoute?.takeIf { s ->
            isDistinctRoute(s, primaryFastest)
        }

        val merged = mutableListOf<OfflineRoute>()
        if (candidateShortest == null) {
            merged.add(primaryFastest.copy(isFastestRoute = true, isShortestRoute = true))
            merged.addAll(otherAlternates.take(2))
        } else {
            val taggedShortest = candidateShortest.copy(
                id = "route_shortest",
                isShortestRoute = true,
                isFastestRoute = false
            )
            val extraAlts = otherAlternates.filter { alt ->
                isDistinctRoute(alt, taggedShortest) && isDistinctRoute(alt, primaryFastest)
            }.take(2)
            if (preferShortest) {
                merged.add(taggedShortest)
                merged.add(primaryFastest)
                merged.addAll(extraAlts)
            } else {
                merged.add(primaryFastest)
                merged.add(taggedShortest)
                merged.addAll(extraAlts)
            }
        }
        return merged.take(4)
    }

    private fun isDistinctRoute(a: OfflineRoute, b: OfflineRoute): Boolean {
        val distDiff = abs(a.distanceMeters - b.distanceMeters)
        val timeDiff = abs(a.durationSeconds - b.durationSeconds)
        val pctDistDiff = if (b.distanceMeters > 1.0) distDiff / b.distanceMeters else 0.0
        if (distDiff >= 100.0 || pctDistDiff >= 0.02 || timeDiff >= 20.0) {
            return true
        }
        if (a.shape.size >= 3 && b.shape.size >= 3) {
            val midA = a.shape[a.shape.size / 2]
            val midB = b.shape[b.shape.size / 2]
            val midDist = OfflinePlaceSearchEngine.haversineMeters(midA.lat, midA.lon, midB.lat, midB.lon)
            if (midDist >= 120.0) return true
        }
        return false
    }

    fun shapeDistanceBetween(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
        if (shape.size < 2 || fromIdx >= toIdx) return 0.0
        val start = fromIdx.coerceIn(0, shape.lastIndex)
        val end = toIdx.coerceIn(start, shape.lastIndex)
        var sum = 0.0
        for (i in start until end) {
            sum += OfflinePlaceSearchEngine.haversineMeters(
                shape[i].lat, shape[i].lon,
                shape[i + 1].lat, shape[i + 1].lon
            )
        }
        return sum
    }

    fun parseSignpost(signObj: JSONObject?): HighwaySignpost? {
        if (signObj == null) return null
        fun extractTextList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val el = arr.opt(i)
                when (el) {
                    is JSONObject -> el.optString("text").takeIf { it.isNotBlank() }?.let { out.add(it) }
                    is String -> el.takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
            }
            return out
        }
        return HighwaySignpost(
            exitNumbers = extractTextList(signObj.optJSONArray("exit_number_elements")),
            exitBranches = (extractTextList(signObj.optJSONArray("exit_branch_elements")) +
                extractTextList(signObj.optJSONArray("guide_branch_elements"))).distinct(),
            exitTowards = (extractTextList(signObj.optJSONArray("exit_toward_elements")) +
                extractTextList(signObj.optJSONArray("guide_toward_elements"))).distinct(),
            exitNames = extractTextList(signObj.optJSONArray("exit_name_elements"))
        )
    }

    fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) add(s)
            }
        }
    }

    fun mapValhallaManeuverType(code: Int): ManeuverType = when (code) {
        1, 2, 3 -> ManeuverType.DEPART
        4 -> ManeuverType.ARRIVE
        5 -> ManeuverType.ARRIVE_RIGHT
        6 -> ManeuverType.ARRIVE_LEFT
        7, 8 -> ManeuverType.STRAIGHT
        9 -> ManeuverType.SLIGHT_RIGHT
        10 -> ManeuverType.RIGHT
        11 -> ManeuverType.SHARP_RIGHT
        12 -> ManeuverType.U_TURN_RIGHT
        13 -> ManeuverType.U_TURN_LEFT
        14 -> ManeuverType.SHARP_LEFT
        15 -> ManeuverType.LEFT
        16 -> ManeuverType.SLIGHT_LEFT
        17 -> ManeuverType.STRAIGHT
        18 -> ManeuverType.RAMP_RIGHT
        19 -> ManeuverType.RAMP_LEFT
        20 -> ManeuverType.EXIT_RIGHT
        21 -> ManeuverType.EXIT_LEFT
        22 -> ManeuverType.STRAIGHT
        23 -> ManeuverType.STAY_RIGHT
        24 -> ManeuverType.STAY_LEFT
        25, 37, 38 -> ManeuverType.MERGE
        26, 27 -> ManeuverType.ROUNDABOUT
        28, 29 -> ManeuverType.FERRY
        else -> ManeuverType.STRAIGHT
    }

    fun decodePolyline6(encoded: String): List<LatLon> {
        if (encoded.isEmpty()) return emptyList()
        val points = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= encoded.length) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            shift = 0
            result = 0
            do {
                if (index >= encoded.length) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

            points.add(LatLon(lat / 1e6, lon / 1e6))
        }
        return points
    }

    fun encodePolyline6(points: List<LatLon>): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder(points.size * 4)
        var prevLat = 0L
        var prevLon = 0L
        for (pt in points) {
            val latE6 = Math.round(pt.lat * 1e6)
            val lonE6 = Math.round(pt.lon * 1e6)
            encodeSignedVarint(latE6 - prevLat, sb)
            encodeSignedVarint(lonE6 - prevLon, sb)
            prevLat = latE6
            prevLon = lonE6
        }
        return sb.toString()
    }

    private fun encodeSignedVarint(value: Long, out: StringBuilder) {
        var v = if (value < 0L) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20L) {
            out.append(((0x20L or (v and 0x1fL)) + 63L).toInt().toChar())
            v = v ushr 5
        }
        out.append((v + 63L).toInt().toChar())
    }

    fun detectSharpCurves(shape: List<LatLon>): List<AheadAlert> {
        if (shape.size < 5) return emptyList()
        val alerts = mutableListOf<AheadAlert>()
        var lastAlertIdx = -20
        for (i in 2 until shape.size - 2) {
            if (i - lastAlertIdx < 12) continue
            val pPrev = shape[i - 2]
            val pMid = shape[i]
            val pNext = shape[i + 2]
            val d1 = OfflinePlaceSearchEngine.haversineMeters(pPrev.lat, pPrev.lon, pMid.lat, pMid.lon)
            val d2 = OfflinePlaceSearchEngine.haversineMeters(pMid.lat, pMid.lon, pNext.lat, pNext.lon)
            if (d1 in 15.0..110.0 && d2 in 15.0..110.0) {
                val b1 = bearingDegrees(pPrev, pMid)
                val b2 = bearingDegrees(pMid, pNext)
                val diff = angleDiffDegrees(b1, b2)
                if (diff in 55.0..145.0) {
                    lastAlertIdx = i
                    alerts.add(
                        AheadAlert(
                            id = "curve_$i",
                            kind = RoadAlertKind.SHARP_CURVE,
                            lat = pMid.lat,
                            lon = pMid.lon,
                            distanceMeters = 0.0,
                            titleEn = "Sharp curve ahead",
                            titleFa = "پیچ تند در پیش است",
                            curveAngleDegrees = diff
                        )
                    )
                }
            }
        }
        return alerts
    }

    fun detectStatesCrossed(shape: List<LatLon>): List<String> {
        if (shape.isEmpty()) return emptyList()
        val step = (shape.size / 40).coerceAtLeast(1)
        val states = linkedSetOf<String>()
        for (i in shape.indices step step) {
            val pt = shape[i]
            UsStatesCatalog.findBestStateForLocation(pt.lat, pt.lon)?.let {
                states.add(it.abbr)
            }
        }
        val last = shape.last()
        UsStatesCatalog.findBestStateForLocation(last.lat, last.lon)?.let {
            states.add(it.abbr)
        }
        return states.toList()
    }

    fun bearingDegrees(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDiffDegrees(b1: Double, b2: Double): Double {
        val diff = abs(b1 - b2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }
}
