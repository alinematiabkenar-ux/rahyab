package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import kotlin.math.abs
import kotlin.math.sin

/**
 * Evaluates upcoming traffic lights for optional voice alerts (S3).
 *
 * Rules:
 * - Active ONLY in [TravelMode.DRIVE] when `enabled` is true (never in WALK or BIKE).
 * - Triggers around 150 m ahead (35 m..175 m window) within the forward heading cone.
 * - Speaks at most once per traffic light.
 * - Skips silently if voice guidance is currently speaking or queued (never interrupts).
 * - Cluster suppression: skips lights within 120 m of an already-announced light so only
 *   the first light of a cluster is spoken.
 */
class TrafficLightVoiceEvaluator {

    private val announcedKeys = HashSet<String>()
    private val announcedLocations = ArrayList<LatLon>()

    fun reset() {
        announcedKeys.clear()
        announcedLocations.clear()
    }

    fun evaluate(
        userLat: Double,
        userLon: Double,
        headingDegrees: Float,
        lights: List<RoadHazardPoint>,
        isDriveMode: Boolean,
        enabled: Boolean,
        isVoiceBusy: Boolean
    ): AheadAlert? {
        if (!isDriveMode || !enabled || isVoiceBusy || lights.isEmpty()) return null

        var bestLight: RoadHazardPoint? = null
        var bestKey: String? = null
        var bestDist = Double.MAX_VALUE

        for (sig in lights) {
            val key = signalKey(sig)
            if (key in announcedKeys) continue

            val dist = OfflinePlaceSearchEngine.haversineMeters(userLat, userLon, sig.lat, sig.lon)
            if (dist < MIN_TRIGGER_METERS || dist > MAX_TRIGGER_METERS || dist >= bestDist) continue

            val bearingToSig = ValhallaResponseParser.bearingDegrees(
                LatLon(userLat, userLon),
                LatLon(sig.lat, sig.lon)
            ).toFloat()
            val angleDiff = abs(CompassTracker.shortestAngleDelta(headingDegrees, bearingToSig))
            if (angleDiff > MAX_HEADING_CONE_DEGREES) continue

            val lateralMeters = dist * sin(Math.toRadians(angleDiff.toDouble()))
            if (lateralMeters > MAX_LATERAL_OFFSET_METERS) continue

            bestDist = dist
            bestLight = sig
            bestKey = key
        }

        val chosen = bestLight ?: return null
        val chosenKey = bestKey ?: return null

        // Mark light as consumed even if suppressed by a nearby cluster so it does not re-trigger later.
        announcedKeys.add(chosenKey)

        val withinCluster = announcedLocations.any { prev ->
            OfflinePlaceSearchEngine.haversineMeters(prev.lat, prev.lon, chosen.lat, chosen.lon) < CLUSTER_RADIUS_METERS
        }
        if (withinCluster) {
            return null
        }

        announcedLocations.add(LatLon(chosen.lat, chosen.lon))
        if (announcedLocations.size > 40) {
            announcedLocations.removeAt(0)
        }

        return AheadAlert(
            id = "traffic_light_$chosenKey",
            kind = RoadAlertKind.TRAFFIC_SIGNAL,
            lat = chosen.lat,
            lon = chosen.lon,
            distanceMeters = bestDist,
            titleEn = "Traffic light ahead",
            titleFa = "جلوتر چراغ راهنما است"
        )
    }

    private fun signalKey(sig: RoadHazardPoint): String {
        val osm = sig.osmId?.takeIf { it.isNotBlank() }
        if (osm != null) return osm
        val latBucket = (sig.lat * 10_000.0).toLong()
        val lonBucket = (sig.lon * 10_000.0).toLong()
        return "${latBucket}_${lonBucket}"
    }

    companion object {
        const val MIN_TRIGGER_METERS = 35.0
        const val MAX_TRIGGER_METERS = 175.0
        const val CLUSTER_RADIUS_METERS = 120.0
        const val MAX_HEADING_CONE_DEGREES = 35.0f
        const val MAX_LATERAL_OFFSET_METERS = 18.0
    }
}
