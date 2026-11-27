package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import kotlin.math.abs
import kotlin.math.cos

/**
 * Pure geometry and map-matching utilities extracted from [NavigationSessionController].
 */
internal object NavigationGeometryHelper {

    fun snapToRoute(
        fix: GpsFix,
        shape: List<LatLon>,
        hintIndex: Int = -1
    ): NavigationSessionController.SnapProjection {
        if (shape.size == 1) {
            val pt = shape[0]
            return NavigationSessionController.SnapProjection(
                point = pt,
                segmentIndex = 0,
                distanceMeters = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, pt.lat, pt.lon),
                roadBearing = fix.bearing
            )
        }
        val cosLat = cos(Math.toRadians(fix.lat)).coerceAtLeast(0.1)

        fun searchRange(startIdx: Int, endExclusive: Int): NavigationSessionController.SnapProjection {
            var bestDist = Double.MAX_VALUE
            var bestPoint = shape[startIdx.coerceIn(0, shape.lastIndex)]
            var bestIdx = startIdx.coerceIn(0, (shape.lastIndex - 1).coerceAtLeast(0))
            var bestT = 0.0
            var bestBearing = fix.bearing

            for (i in startIdx until endExclusive) {
                val a = shape[i]
                val b = shape[i + 1]
                val ax = a.lon * cosLat
                val ay = a.lat
                val bx = b.lon * cosLat
                val by = b.lat
                val px = fix.lon * cosLat
                val py = fix.lat

                val dx = bx - ax
                val dy = by - ay
                val lenSq = dx * dx + dy * dy
                val t = if (lenSq > 1e-15) {
                    (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
                } else 0.0

                val projLat = a.lat + t * (b.lat - a.lat)
                val projLon = a.lon + t * (b.lon - a.lon)
                val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, projLat, projLon)
                val isCloser = dist < bestDist - 0.01
                // When tied at a shared vertex (end of segment i-1 == start of segment i), prefer the outgoing segment
                val isForwardVertexTie = abs(dist - bestDist) <= 0.01 && bestT >= 0.999 && t < 0.999
                if (isCloser || isForwardVertexTie) {
                    bestDist = dist
                    bestPoint = LatLon(projLat, projLon)
                    bestIdx = i
                    bestT = t
                    bestBearing = ValhallaResponseParser.bearingDegrees(a, b).toFloat()
                }
            }
            return NavigationSessionController.SnapProjection(bestPoint, bestIdx, bestDist, bestBearing)
        }

        // Fast local window search around previous segment index for smooth 32x simulation on long routes
        if (hintIndex in 0 until shape.lastIndex && shape.size > 160) {
            val winStart = (hintIndex - 8).coerceAtLeast(0)
            val winEnd = (hintIndex + 120).coerceAtMost(shape.lastIndex)
            val local = searchRange(winStart, winEnd)
            if (local.distanceMeters <= 35.0) {
                return local
            }
        }
        return searchRange(0, shape.lastIndex)
    }

    fun advanceAlongShape(
        shape: List<LatLon>,
        startIndex: Int,
        distanceMeters: Double,
        currentLocation: LatLon? = null
    ): Triple<Int, LatLon, Float> {
        var idx = startIndex.coerceIn(0, shape.lastIndex)
        var remaining = distanceMeters
        var curPt = currentLocation ?: shape[idx]
        if (remaining <= 0.0) {
            val bearing = if (idx < shape.lastIndex) {
                ValhallaResponseParser.bearingDegrees(curPt, shape[idx + 1]).toFloat()
            } else {
                val prev = shape.getOrElse((idx - 1).coerceAtLeast(0)) { curPt }
                ValhallaResponseParser.bearingDegrees(prev, curPt).toFloat()
            }
            return Triple(idx, curPt, bearing)
        }
        while (idx < shape.lastIndex && remaining > 0.0) {
            val nextPt = shape[idx + 1]
            val segLen = OfflinePlaceSearchEngine.haversineMeters(curPt.lat, curPt.lon, nextPt.lat, nextPt.lon)
            if (segLen <= 0.1) {
                idx++
                curPt = shape[idx]
                continue
            }
            if (remaining < segLen) {
                val frac = remaining / segLen
                val lat = curPt.lat + (nextPt.lat - curPt.lat) * frac
                val lon = curPt.lon + (nextPt.lon - curPt.lon) * frac
                val bearing = ValhallaResponseParser.bearingDegrees(curPt, nextPt).toFloat()
                return Triple(idx, LatLon(lat, lon), bearing)
            }
            remaining -= segLen
            idx++
            curPt = shape[idx]
        }
        val last = shape.last()
        val prev = shape.getOrElse(shape.lastIndex - 1) { last }
        return Triple(shape.lastIndex, last, ValhallaResponseParser.bearingDegrees(prev, last).toFloat())
    }

    fun polylineDistanceFromPoint(
        shape: List<LatLon>,
        fromPoint: LatLon,
        fromSegIdx: Int,
        toIdx: Int
    ): Double {
        if (shape.size < 2 || fromSegIdx >= toIdx) return 0.0
        val nextVertexIdx = (fromSegIdx + 1).coerceAtMost(toIdx).coerceIn(0, shape.lastIndex)
        val firstHop = OfflinePlaceSearchEngine.haversineMeters(
            fromPoint.lat, fromPoint.lon,
            shape[nextVertexIdx].lat, shape[nextVertexIdx].lon
        )
        return firstHop + polylineDistanceMeters(shape, nextVertexIdx, toIdx)
    }

    fun polylineDistanceMeters(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
        if (shape.size < 2 || fromIdx >= toIdx) return 0.0
        val start = fromIdx.coerceIn(0, shape.lastIndex)
        val end = toIdx.coerceIn(start, shape.lastIndex)
        var sum = 0.0
        for (i in start until end) {
            val a = shape[i]
            val b = shape[i + 1]
            sum += OfflinePlaceSearchEngine.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        }
        return sum
    }

    /**
     * Returns cumulative route distance in meters from `shape[0]` to `shape[vertexIdx]`.
     */
    fun vertexProgressMeters(shape: List<LatLon>, vertexIdx: Int): Double {
        if (shape.size < 2 || vertexIdx <= 0) return 0.0
        return polylineDistanceMeters(shape, 0, vertexIdx.coerceAtMost(shape.lastIndex))
    }

    /**
     * Exact matched-route progress in meters, including sub-segment position within
     * `shape[snappedSegIdx] -> shape[snappedSegIdx + 1]` so long sparse segments never
     * collapse to integer vertex indices.
     */
    fun progressAlongRouteMeters(
        shape: List<LatLon>,
        snappedLocation: LatLon,
        snappedSegIdx: Int
    ): Double {
        if (shape.size < 2) return 0.0
        val clampedSeg = snappedSegIdx.coerceIn(0, shape.lastIndex)
        val baseDist = polylineDistanceMeters(shape, 0, clampedSeg)
        if (clampedSeg >= shape.lastIndex) return baseDist
        val segStart = shape[clampedSeg]
        val segEnd = shape[clampedSeg + 1]
        val segLen = OfflinePlaceSearchEngine.haversineMeters(segStart.lat, segStart.lon, segEnd.lat, segEnd.lon)
        if (segLen <= 1e-6) return baseDist
        val cosLat = cos(Math.toRadians(segStart.lat)).coerceAtLeast(0.1)
        val dx = (segEnd.lon - segStart.lon) * cosLat
        val dy = segEnd.lat - segStart.lat
        val lenSq = dx * dx + dy * dy
        val frac = if (lenSq > 1e-15) {
            (((snappedLocation.lon - segStart.lon) * cosLat * dx + (snappedLocation.lat - segStart.lat) * dy) / lenSq)
                .coerceIn(0.0, 1.0)
        } else 0.0
        return baseDist + segLen * frac
    }

    /**
     * Signed distance along the matched route from the vehicle to [targetVertexIdx]:
     * positive while approaching, zero at the vertex, negative once passed.
     */
    fun signedDistanceToVertexMeters(
        shape: List<LatLon>,
        snappedLocation: LatLon,
        snappedSegIdx: Int,
        targetVertexIdx: Int
    ): Double {
        return vertexProgressMeters(shape, targetVertexIdx) -
            progressAlongRouteMeters(shape, snappedLocation, snappedSegIdx)
    }

    fun isRoughlyAhead(fix: GpsFix, targetLat: Double, targetLon: Double): Boolean {
        if (fix.speedMps < 3.0f) return true
        val bearingToTarget = ValhallaResponseParser.bearingDegrees(
            LatLon(fix.lat, fix.lon),
            LatLon(targetLat, targetLon)
        )
        return ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), bearingToTarget) <= 75.0
    }
}
