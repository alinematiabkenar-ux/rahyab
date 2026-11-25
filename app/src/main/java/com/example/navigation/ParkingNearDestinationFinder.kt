package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.PlaceSearchResult
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

enum class ParkingSpotType {
    GARAGE,
    LOT,
    STREET,
    UNKNOWN
}

data class DestinationParkingOption(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val type: ParkingSpotType,
    val straightLineMeters: Double,
    val walkDistanceMeters: Double,
    val walkMinutes: Int
)

/**
 * PK1: Offline finder and ranker for parking options within 400 m of a destination.
 *
 * Pure Kotlin ranking helper [rankAndFilterCandidates] filters strictly to <= 400 m, estimates
 * walking distance (`straightLine * 1.25`) and walking minutes, deduplicates close points,
 * and returns up to 5 options sorted by walking distance.
 *
 * [findNearDestination] queries the offline places database and, for the top 3 candidates,
 * optionally refines walking distance & minutes using offline pedestrian routing when each
 * route finishes in under 1 second.
 */
object ParkingNearDestinationFinder {

    const val MAX_PARKING_RADIUS_METERS = 400.0
    const val MAX_RESULTS = 5
    const val WALK_DISTANCE_MULTIPLIER = 1.25
    const val WALK_SPEED_METERS_PER_MIN = 80.0
    private const val DEDUP_METERS = 25.0
    private const val PEDESTRIAN_ROUTE_TIMEOUT_MS = 900L

    fun inferParkingType(name: String, category: String): ParkingSpotType {
        val hay = "$name $category".lowercase()
        return when {
            hay.contains("garage") || hay.contains("structure") ||
                hay.contains("parkade") || hay.contains("deck") || hay.contains("multi-storey") -> ParkingSpotType.GARAGE
            hay.contains("lot") || hay.contains("surface") -> ParkingSpotType.LOT
            hay.contains("on-street") || hay.contains("street parking") ||
                hay.contains("meter") || hay.contains("curb") -> ParkingSpotType.STREET
            hay.contains("parking") -> ParkingSpotType.LOT
            else -> ParkingSpotType.UNKNOWN
        }
    }

    fun rankAndFilterCandidates(
        destLat: Double,
        destLon: Double,
        candidates: List<PlaceSearchResult>,
        maxRadiusMeters: Double = MAX_PARKING_RADIUS_METERS,
        maxResults: Int = MAX_RESULTS
    ): List<DestinationParkingOption> {
        if (candidates.isEmpty() || maxResults <= 0) return emptyList()

        val mapped = candidates.mapNotNull { c ->
            val straight = OfflinePlaceSearchEngine.haversineMeters(destLat, destLon, c.lat, c.lon)
            if (straight > maxRadiusMeters) return@mapNotNull null
            val walkDist = straight * WALK_DISTANCE_MULTIPLIER
            val walkMins = (walkDist / WALK_SPEED_METERS_PER_MIN).roundToInt().coerceAtLeast(1)
            DestinationParkingOption(
                id = c.id,
                name = c.name.trim(),
                lat = c.lat,
                lon = c.lon,
                type = inferParkingType(c.name, c.category),
                straightLineMeters = straight,
                walkDistanceMeters = walkDist,
                walkMinutes = walkMins
            )
        }.sortedBy { it.walkDistanceMeters }

        val deduped = mutableListOf<DestinationParkingOption>()
        for (opt in mapped) {
            val dupIdx = deduped.indexOfFirst { existing ->
                OfflinePlaceSearchEngine.haversineMeters(existing.lat, existing.lon, opt.lat, opt.lon) < DEDUP_METERS
            }
            if (dupIdx < 0) {
                deduped.add(opt)
            } else if (deduped[dupIdx].name.isBlank() && opt.name.isNotBlank()) {
                deduped[dupIdx] = opt
            }
            if (deduped.size >= maxResults) break
        }
        return deduped
    }

    suspend fun findNearDestination(
        searchEngine: OfflinePlaceSearchEngine,
        routingEngine: ValhallaRoutingEngine,
        destLat: Double,
        destLon: Double,
        allowDevOnlineFallback: Boolean = false
    ): List<DestinationParkingOption> {
        val raw = searchEngine.queryNearbyParking(
            destLat = destLat,
            destLon = destLon,
            radiusMeters = MAX_PARKING_RADIUS_METERS,
            limit = 40
        )
        val initial = rankAndFilterCandidates(destLat, destLon, raw)
        if (initial.isEmpty()) return emptyList()

        val refined = initial.mapIndexed { idx, opt ->
            if (idx >= 3) return@mapIndexed opt
            val walkRoute = withTimeoutOrNull(PEDESTRIAN_ROUTE_TIMEOUT_MS) {
                routingEngine.calculateRoutes(
                    origin = LatLon(opt.lat, opt.lon),
                    destination = LatLon(destLat, destLon),
                    options = RouteOptions(travelMode = TravelMode.WALK),
                    headingDegrees = null,
                    allowDevOnlineFallback = allowDevOnlineFallback
                ).getOrNull()?.firstOrNull()
            }
            if (walkRoute != null && walkRoute.distanceMeters > 0.0) {
                val mins = (walkRoute.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
                opt.copy(
                    walkDistanceMeters = walkRoute.distanceMeters,
                    walkMinutes = mins
                )
            } else {
                opt
            }
        }
        return refined.sortedBy { it.walkDistanceMeters }
    }
}
