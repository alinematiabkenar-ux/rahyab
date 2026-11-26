package com.example.map

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.navigation.LatLon
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Pure geometric helpers extracted from [LaneOverlayBuilder] for polyline offsetting,
 * approach/outgoing distance sampling, and verified outgoing highway ramp guidance paths.
 */
internal object LaneOverlayGeometryHelper {

    private const val METERS_PER_DEG_LAT = 111_320.0
    const val HIGHWAY_RAMP_GUIDANCE_METERS = 210.0

    fun offsetPolylineMeters(
        points: List<LatLon>,
        origin: LatLon,
        rightOffsetMeters: Double
    ): List<LatLon> {
        if (points.size < 2) return points
        val cosLat = cos(Math.toRadians(origin.lat)).coerceAtLeast(0.1)
        val metersPerDegLon = METERS_PER_DEG_LAT * cosLat

        val xs = DoubleArray(points.size) { i -> (points[i].lon - origin.lon) * metersPerDegLon }
        val ys = DoubleArray(points.size) { i -> (points[i].lat - origin.lat) * METERS_PER_DEG_LAT }

        val out = ArrayList<LatLon>(points.size)
        for (i in points.indices) {
            var tx = 0.0
            var ty = 0.0
            if (i > 0) {
                val dx = xs[i] - xs[i - 1]
                val dy = ys[i] - ys[i - 1]
                val len = hypot(dx, dy)
                if (len > 1e-4) {
                    tx += dx / len
                    ty += dy / len
                }
            }
            if (i < points.lastIndex) {
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val len = hypot(dx, dy)
                if (len > 1e-4) {
                    tx += dx / len
                    ty += dy / len
                }
            }
            val tLen = hypot(tx, ty)
            val nx: Double
            val ny: Double
            if (tLen > 1e-4) {
                val ux = tx / tLen
                val uy = ty / tLen
                nx = uy
                ny = -ux
            } else {
                nx = 0.0
                ny = 0.0
            }
            val ox = xs[i] + nx * rightOffsetMeters
            val oy = ys[i] + ny * rightOffsetMeters
            out.add(
                LatLon(
                    lat = origin.lat + oy / METERS_PER_DEG_LAT,
                    lon = origin.lon + ox / metersPerDegLon
                )
            )
        }
        return out
    }

    fun distanceAlongShape(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
        val a = fromIdx.coerceIn(0, shape.lastIndex)
        val b = toIdx.coerceIn(0, shape.lastIndex)
        if (b <= a) return 0.0
        var sum = 0.0
        for (i in a until b) {
            sum += OfflinePlaceSearchEngine.haversineMeters(
                shape[i].lat, shape[i].lon, shape[i + 1].lat, shape[i + 1].lon
            )
        }
        return sum
    }

    fun collectBackward(shape: List<LatLon>, pivotIdx: Int, targetMeters: Double): List<LatLon> =
        collectAlong(shape, pivotIdx, targetMeters, step = -1).asReversed()

    fun collectForward(shape: List<LatLon>, pivotIdx: Int, targetMeters: Double): List<LatLon> =
        collectAlong(shape, pivotIdx, targetMeters, step = 1)

    /**
     * Builds the verified blue outgoing ramp guidance path along the active route centerline
     * for longer highway exits/ramps (up to [HIGHWAY_RAMP_GUIDANCE_METERS] past the split).
     * As the vehicle advances past [pivotIdx] onto the ramp, trims behind [snappedShapeIndex]
     * so the outgoing ramp path stays ahead of the vehicle until established on the ramp.
     */
    fun buildOutgoingRampPath(
        shape: List<LatLon>,
        pivotIdx: Int,
        snappedShapeIndex: Int,
        maxMetersPastSplit: Double = HIGHWAY_RAMP_GUIDANCE_METERS
    ): List<LatLon> {
        if (shape.size < 2) return emptyList()
        val safePivot = pivotIdx.coerceIn(0, shape.lastIndex)
        if (safePivot >= shape.lastIndex) return emptyList()
        val safeSnap = snappedShapeIndex.coerceIn(0, shape.lastIndex)
        val distPastPivot = if (safeSnap > safePivot) {
            distanceAlongShape(shape, safePivot, safeSnap)
        } else {
            0.0
        }
        val remainingAhead = (maxMetersPastSplit - distPastPivot).coerceAtLeast(0.0)
        if (remainingAhead < 8.0) return emptyList()
        val startIdx = if (safeSnap > safePivot) safeSnap else safePivot
        val forward = collectForward(shape, startIdx, remainingAhead)
        if (forward.isEmpty()) return emptyList()
        return ArrayList<LatLon>(forward.size + 1).apply {
            add(shape[startIdx])
            addAll(forward)
        }
    }

    private fun collectAlong(shape: List<LatLon>, pivotIdx: Int, targetMeters: Double, step: Int): List<LatLon> {
        val out = ArrayList<LatLon>()
        var acc = 0.0
        var i = pivotIdx
        while (acc < targetMeters && (if (step < 0) i > 0 else i < shape.lastIndex)) {
            val cur = shape[i]
            val adj = shape[i + step]
            val seg = OfflinePlaceSearchEngine.haversineMeters(cur.lat, cur.lon, adj.lat, adj.lon)
            if (seg <= 0.05) { i += step; continue }
            if (acc + seg >= targetMeters) {
                out.add(interpolate(cur, adj, ((targetMeters - acc) / seg).coerceIn(0.0, 1.0)))
                break
            }
            out.add(adj)
            acc += seg
            i += step
        }
        return out
    }

    fun interpolate(a: LatLon, b: LatLon, frac: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * frac, a.lon + (b.lon - a.lon) * frac)
}
