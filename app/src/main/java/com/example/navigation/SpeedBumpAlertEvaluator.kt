package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import kotlin.math.abs

/**
 * Evaluates upcoming `speed_bump` hazards (from OSM `traffic_calming`) during both
 * active navigation and free driving.
 *
 * Rules:
 * - Alert distance: 120 m when speed < 30 mph, 200 m when speed >= 30 mph.
 * - Multiple bumps within 80 m of each other along the road collapse into a single cluster alert.
 * - Never speaks when speed < 5 mph.
 * - Singular: "Speed bump ahead" / "جلوتر سرعتگیر است".
 * - Plural (cluster): "Speed bumps ahead" / "جلوتر چند سرعتگیر است".
 */
class SpeedBumpAlertEvaluator {

    data class SpeedBumpCluster(
        val id: String,
        val leadBump: RoadHazardPoint,
        val count: Int,
        val distanceMeters: Double
    )

    private val announcedClusters = LinkedHashMap<String, Long>()
    private var lastCleanupMs = 0L

    fun reset() {
        announcedClusters.clear()
        lastCleanupMs = 0L
    }

    /**
     * Returns `Pair(activeBannerAlert, alertToSpeakNow)`.
     * `alertToSpeakNow` is null when speed < 5 mph or when this cluster was already spoken.
     */
    fun evaluate(
        userLat: Double,
        userLon: Double,
        headingDegrees: Float,
        speedMps: Float,
        bumps: List<RoadHazardPoint>,
        enabled: Boolean,
        isWalkingMode: Boolean = false,
        routeShape: List<LatLon>? = null,
        snappedShapeIndex: Int = 0,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<AheadAlert?, AheadAlert?> {
        if (!enabled || isWalkingMode || bumps.isEmpty()) return null to null

        if (nowMs - lastCleanupMs > 15_000L) {
            lastCleanupMs = nowMs
            val iter = announcedClusters.entries.iterator()
            while (iter.hasNext()) {
                if (nowMs - iter.next().value > ANNOUNCE_COOLDOWN_MS) iter.remove()
            }
        }

        val cluster = findNextSpeedBumpClusterAhead(
            userLat = userLat,
            userLon = userLon,
            headingDegrees = headingDegrees,
            speedMps = speedMps,
            bumps = bumps,
            routeShape = routeShape,
            snappedShapeIndex = snappedShapeIndex
        ) ?: return null to null

        val isMultiple = cluster.count > 1
        val alert = AheadAlert(
            id = cluster.id,
            kind = RoadAlertKind.SPEED_BUMP,
            lat = cluster.leadBump.lat,
            lon = cluster.leadBump.lon,
            distanceMeters = cluster.distanceMeters,
            titleEn = if (isMultiple) TITLE_PLURAL_EN else TITLE_SINGULAR_EN,
            titleFa = if (isMultiple) TITLE_PLURAL_FA else TITLE_SINGULAR_FA,
            detail = if (isMultiple) "${cluster.count}" else cluster.leadBump.label
        )

        val shouldSpeak = speedMps >= MIN_SPEECH_SPEED_MPS &&
            !announcedClusters.containsKey(cluster.id)
        if (shouldSpeak) {
            announcedClusters[cluster.id] = nowMs
        }
        return alert to (if (shouldSpeak) alert else null)
    }

    companion object {
        const val SPEED_30_MPH_MPS = 13.4112f
        const val MIN_SPEECH_SPEED_MPS = 2.2352f // ~5 mph
        const val ALERT_DISTANCE_UNDER_30_MPH_M = 120.0
        const val ALERT_DISTANCE_30_PLUS_MPH_M = 200.0
        const val CLUSTER_DISTANCE_METERS = 80.0
        const val MIN_AHEAD_DISTANCE_M = 8.0
        const val HEADING_CONE_DEGREES = 42.0
        const val ROUTE_LATERAL_TOLERANCE_M = 30.0
        private const val ANNOUNCE_COOLDOWN_MS = 90_000L

        const val TITLE_SINGULAR_EN = "Speed bump ahead"
        const val TITLE_SINGULAR_FA = "جلوتر سرعتگیر است"
        const val TITLE_PLURAL_EN = "Speed bumps ahead"
        const val TITLE_PLURAL_FA = "جلوتر چند سرعتگیر است"

        fun alertDistanceMeters(speedMps: Float): Double =
            if (speedMps >= SPEED_30_MPH_MPS) ALERT_DISTANCE_30_PLUS_MPH_M else ALERT_DISTANCE_UNDER_30_MPH_M

        /**
         * Finds the nearest speed bump ahead of the driver and groups any additional bumps
         * chained within [CLUSTER_DISTANCE_METERS] (80 m) into one cluster.
         */
        fun findNextSpeedBumpClusterAhead(
            userLat: Double,
            userLon: Double,
            headingDegrees: Float,
            speedMps: Float,
            bumps: List<RoadHazardPoint>,
            routeShape: List<LatLon>? = null,
            snappedShapeIndex: Int = 0
        ): SpeedBumpCluster? {
            val maxAlertDist = alertDistanceMeters(speedMps)
            val searchLimit = maxAlertDist + CLUSTER_DISTANCE_METERS * 3.0

            val aheadCandidates = mutableListOf<Pair<RoadHazardPoint, Double>>()
            val routeSlice = if (!routeShape.isNullOrEmpty()) {
                collectRouteAheadWindow(routeShape, snappedShapeIndex, searchLimit + 60.0)
            } else {
                null
            }

            for (bump in bumps) {
                if (!bump.type.equals("speed_bump", ignoreCase = true)) continue
                val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, bump.lat, bump.lon)
                if (dist < MIN_AHEAD_DISTANCE_M || dist > searchLimit) continue

                val isAhead = if (routeSlice != null && routeSlice.size >= 2) {
                    isPointNearPolyline(bump.lat, bump.lon, routeSlice, ROUTE_LATERAL_TOLERANCE_M)
                } else {
                    val bearingToBump = ValhallaResponseParser.bearingDegrees(
                        LatLon(userLat, userLon),
                        LatLon(bump.lat, bump.lon)
                    )
                    shortestHeadingDelta(headingDegrees.toDouble(), bearingToBump) <= HEADING_CONE_DEGREES
                }
                if (isAhead) {
                    aheadCandidates.add(bump to dist)
                }
            }

            if (aheadCandidates.isEmpty()) return null
            aheadCandidates.sortBy { it.second }

            val first = aheadCandidates.first()
            if (first.second > maxAlertDist) return null

            // Chain all ahead bumps within 80 m of any member already in this cluster
            val clusterMembers = mutableListOf(first.first)
            val remaining = aheadCandidates.drop(1).map { it.first }.toMutableList()
            var added = true
            while (added && remaining.isNotEmpty()) {
                added = false
                val iter = remaining.iterator()
                while (iter.hasNext()) {
                    val candidate = iter.next()
                    val nearExisting = clusterMembers.any { member ->
                        OfflinePlaceSearchEngine.haversineMeters(
                            member.lat, member.lon, candidate.lat, candidate.lon
                        ) <= CLUSTER_DISTANCE_METERS
                    }
                    if (nearExisting) {
                        clusterMembers.add(candidate)
                        iter.remove()
                        added = true
                    }
                }
            }

            // Stable cluster ID anchored on the sorted member keys so passing the first bump
            // does not re-trigger the remaining bumps in the same cluster
            val memberKey = clusterMembers
                .minByOrNull { it.osmId ?: "${it.lat}_${it.lon}" }
                ?.let { it.osmId?.takeIf { s -> s.isNotBlank() } ?: "%.4f_%.4f".format(it.lat, it.lon) }
                ?: "bump"

            return SpeedBumpCluster(
                id = "speed_bump_cluster_$memberKey",
                leadBump = first.first,
                count = clusterMembers.size,
                distanceMeters = first.second
            )
        }

        /**
         * Places synthetic speed bumps (one single bump + one 2-bump cluster 50 m apart)
         * along a simulated route so speed bump map icons, banners, and voice alerts can be tested.
         */
        fun generateSyntheticSimulationBumps(shape: List<LatLon>): List<RoadHazardPoint> {
            if (shape.size < 6) return emptyList()
            val cumDist = DoubleArray(shape.size)
            for (i in 1 until shape.size) {
                cumDist[i] = cumDist[i - 1] + OfflinePlaceSearchEngine.haversineMeters(
                    shape[i - 1].lat, shape[i - 1].lon, shape[i].lat, shape[i].lon
                )
            }
            val total = cumDist.last()
            if (total < 350.0) return emptyList()

            val out = mutableListOf<RoadHazardPoint>()
            val singleDist = (total * 0.25).coerceAtLeast(180.0).coerceAtMost(total - 120.0)
            pointAtDistance(shape, cumDist, singleDist)?.let { pt ->
                out.add(
                    RoadHazardPoint(
                        id = -9001L,
                        type = "speed_bump",
                        lat = pt.lat,
                        lon = pt.lon,
                        label = "bump",
                        osmId = "sim_bump_single"
                    )
                )
            }
            val cluster1Dist = (total * 0.55).coerceAtLeast(singleDist + 220.0)
            if (cluster1Dist + 65.0 < total - 80.0) {
                pointAtDistance(shape, cumDist, cluster1Dist)?.let { pt1 ->
                    out.add(
                        RoadHazardPoint(
                            id = -9002L,
                            type = "speed_bump",
                            lat = pt1.lat,
                            lon = pt1.lon,
                            label = "hump",
                            osmId = "sim_bump_cluster_a"
                        )
                    )
                }
                pointAtDistance(shape, cumDist, cluster1Dist + 50.0)?.let { pt2 ->
                    out.add(
                        RoadHazardPoint(
                            id = -9003L,
                            type = "speed_bump",
                            lat = pt2.lat,
                            lon = pt2.lon,
                            label = "hump",
                            osmId = "sim_bump_cluster_b"
                        )
                    )
                }
            }
            return out
        }

        private fun pointAtDistance(shape: List<LatLon>, cumDist: DoubleArray, targetMeters: Double): LatLon? {
            for (i in 1 until shape.size) {
                if (cumDist[i] >= targetMeters) {
                    val segLen = (cumDist[i] - cumDist[i - 1]).coerceAtLeast(0.001)
                    val frac = ((targetMeters - cumDist[i - 1]) / segLen).coerceIn(0.0, 1.0)
                    return LatLon(
                        lat = shape[i - 1].lat + (shape[i].lat - shape[i - 1].lat) * frac,
                        lon = shape[i - 1].lon + (shape[i].lon - shape[i - 1].lon) * frac
                    )
                }
            }
            return null
        }

        private fun collectRouteAheadWindow(
            shape: List<LatLon>,
            startIdx: Int,
            windowMeters: Double
        ): List<LatLon> {
            val safeStart = startIdx.coerceIn(0, shape.lastIndex)
            val result = ArrayList<LatLon>(32)
            result.add(shape[safeStart])
            var acc = 0.0
            for (i in (safeStart + 1) until shape.size) {
                acc += OfflinePlaceSearchEngine.haversineMeters(
                    shape[i - 1].lat, shape[i - 1].lon, shape[i].lat, shape[i].lon
                )
                result.add(shape[i])
                if (acc >= windowMeters) break
            }
            return result
        }

        private fun isPointNearPolyline(
            lat: Double,
            lon: Double,
            polyline: List<LatLon>,
            toleranceMeters: Double
        ): Boolean {
            for (i in 0 until polyline.lastIndex) {
                val a = polyline[i]
                val b = polyline[i + 1]
                val d = distancePointToSegmentMeters(lat, lon, a.lat, a.lon, b.lat, b.lon)
                if (d <= toleranceMeters) return true
            }
            return false
        }

        private fun distancePointToSegmentMeters(
            pLat: Double, pLon: Double,
            aLat: Double, aLon: Double,
            bLat: Double, bLon: Double
        ): Double {
            val cosLat = kotlin.math.cos(Math.toRadians(pLat)).coerceAtLeast(0.1)
            val ax = (aLon - pLon) * 111320.0 * cosLat
            val ay = (aLat - pLat) * 111320.0
            val bx = (bLon - pLon) * 111320.0 * cosLat
            val by = (bLat - pLat) * 111320.0
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1e-6) return kotlin.math.hypot(ax, ay)
            val t = ((-ax * dx - ay * dy) / lenSq).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            return kotlin.math.hypot(cx, cy)
        }

        private fun shortestHeadingDelta(h1: Double, h2: Double): Double {
            val diff = ((h2 - h1 + 540.0) % 360.0) - 180.0
            return abs(diff)
        }
    }
}
