package com.example.navigation

import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds Valhalla car-profile route JSON requests (including `exclude_locations` for active
 * user-reported Road Closures) and parses Valhalla JSON responses into up to 3 routes sorted
 * fastest-first, with polyline6 geometry, maneuvers, signposts, real OSM turn:lanes (including
 * approach-segment lanes), speed limits, and speed/curve/toll alerts.
 */
object ValhallaResponseParser {

    fun buildRouteRequestJson(
        origin: LatLon,
        destination: LatLon,
        options: RouteOptions,
        headingDegrees: Float? = null,
        alternatesCount: Int = 2
    ): String = ValhallaRequestBuilder.buildRouteRequestJson(
        origin = origin,
        destination = destination,
        options = options,
        headingDegrees = headingDegrees,
        alternatesCount = alternatesCount
    )

    fun buildTraceAttributesRequestJson(
        encodedPolyline6: String,
        travelMode: TravelMode = TravelMode.DRIVE
    ): String = ValhallaRequestBuilder.buildTraceAttributesRequestJson(encodedPolyline6, travelMode)

    data class TraceAttributesData(
        val speedLimitsByShapeIndex: Map<Int, Int>,
        val laneCountsByShapeIndex: Map<Int, Int>,
        val gradeSpans: List<EdgeGradeSpan> = emptyList(),
        val oneWayByShapeIndex: Map<Int, Boolean> = emptyMap(),
        val wayIdsByShapeIndex: Map<Int, Long> = emptyMap()
    )

    fun parseTraceAttributes(traceJson: String, shapeSize: Int): TraceAttributesData =
        ValhallaTraceAttributesParser.parseTraceAttributes(traceJson, shapeSize)

    fun enrichRouteWithTraceAttributes(route: OfflineRoute, traceJson: String): OfflineRoute =
        ValhallaTraceAttributesParser.enrichRouteWithTraceAttributes(route, traceJson)

    /**
     * Parses Valhalla's standard route response JSON (primary `trip` + up to 2 `alternates`)
     * into up to 3 OfflineRoute objects sorted fastest-first.
     */
    fun parseRoutes(responseJson: String): List<OfflineRoute> {
        val root = JSONObject(responseJson)
        if (root.has("error_code") || root.has("error")) {
            val err = root.optString("error", "Valhalla routing error")
            throw IllegalStateException(err)
        }

        val routes = mutableListOf<OfflineRoute>()
        root.optJSONObject("trip")?.let { tripObj ->
            routes.add(parseSingleTrip(tripObj, routeIndex = 0))
        }

        val alternates = root.optJSONArray("alternates")
        if (alternates != null) {
            for (i in 0 until minOf(alternates.length(), 2)) {
                val altEntry = alternates.optJSONObject(i) ?: continue
                val altTrip = altEntry.optJSONObject("trip") ?: continue
                routes.add(parseSingleTrip(altTrip, routeIndex = i + 1))
            }
        }

        // Fastest route is always selected by default at index 0
        return routes.sortedBy { it.durationSeconds }
    }

    fun mergeFastestAndShortestRoutes(
        fastestRoutes: List<OfflineRoute>,
        shortestRoute: OfflineRoute?,
        preferShortest: Boolean
    ): List<OfflineRoute> = ValhallaPolylineAndGeo.mergeFastestAndShortestRoutes(
        fastestRoutes = fastestRoutes,
        shortestRoute = shortestRoute,
        preferShortest = preferShortest
    )

    private fun parseSingleTrip(trip: JSONObject, routeIndex: Int): OfflineRoute {
        val legs = trip.optJSONArray("legs") ?: JSONArray()
        val combinedShape = mutableListOf<LatLon>()
        val combinedManeuvers = mutableListOf<RouteManeuver>()
        val speedLimits = mutableMapOf<Int, Int>()
        val alerts = mutableListOf<AheadAlert>()
        var hasTolls = false
        var maneuverCounter = 0

        for (l in 0 until legs.length()) {
            val leg = legs.optJSONObject(l) ?: continue
            val shapeOffset = combinedShape.size
            val encoded = leg.optString("shape", "")
            val legPoints = decodePolyline6(encoded)
            combinedShape.addAll(legPoints)

            val maneuversArr = leg.optJSONArray("maneuvers") ?: JSONArray()
            val legEdgesArr = leg.optJSONArray("edges") ?: leg.optJSONArray("segments")
            val legIntersectionsArr = leg.optJSONArray("intersections")
            var previousSpeedLimit: Int? = null
            var pendingApproachLanes: List<LaneInfo> = emptyList()
            var pendingApproachDistanceMeters = 0.0
            var pendingApproachHadInterveningIntersection = false
            var pendingApproachRawJson = ""

            for (m in 0 until maneuversArr.length()) {
                val mObj = maneuversArr.optJSONObject(m) ?: continue
                val rawType = mObj.optInt("type", 8)
                val type = ValhallaPolylineAndGeo.mapValhallaManeuverType(rawType)
                val beginIdx = shapeOffset + mObj.optInt("begin_shape_index", 0)
                val endIdx = shapeOffset + mObj.optInt("end_shape_index", beginIdx)
                val point = combinedShape.getOrNull(beginIdx) ?: combinedShape.firstOrNull() ?: LatLon(0.0, 0.0)

                val streetNames = ValhallaPolylineAndGeo.parseStringArray(mObj.optJSONArray("street_names"))
                val signpost = ValhallaPolylineAndGeo.parseSignpost(mObj.optJSONObject("sign"))

                // Phase 3 & Banner Lane Guidance: Read real Valhalla lane data from the maneuver
                // itself (turn_lanes / lanes / banner_instructions / intersections) AND from
                // approaching road segments before the intersection. NEVER invent lanes when absent!
                val directLanes = ValhallaApproachLaneResolver.parseManeuverDirectLanes(mObj, type)
                val approachLanesOnManeuver = ValhallaApproachLaneResolver.parseApproachSegmentLanes(mObj)
                val edgeApproachLanes = ValhallaApproachLaneResolver.findApproachingEdgeLanes(
                    edgesArr = legEdgesArr,
                    intersectionsArr = legIntersectionsArr,
                    shapeOffset = shapeOffset,
                    maneuverBeginIdx = beginIdx,
                    combinedShape = combinedShape,
                    maneuverType = type
                )

                val laneSource: LaneSource
                val resolvedLanes: List<LaneInfo>
                val carriedDistanceMeters: Double
                val carriedHadIntervening: Boolean
                val rawLaneJson: String
                when {
                    directLanes.isNotEmpty() -> {
                        laneSource = LaneSource.DIRECT
                        resolvedLanes = directLanes
                        carriedDistanceMeters = 0.0
                        carriedHadIntervening = false
                        rawLaneJson = extractRawLaneJson(mObj).ifBlank { serializeLanesFallback(directLanes) }
                    }
                    edgeApproachLanes.isNotEmpty() && ValhallaApproachLaneResolver.isTurnOrExitManeuver(type) -> {
                        laneSource = LaneSource.EDGE_APPROACH
                        resolvedLanes = edgeApproachLanes
                        carriedDistanceMeters = 0.0
                        carriedHadIntervening = false
                        rawLaneJson = serializeLanesFallback(edgeApproachLanes)
                    }
                    pendingApproachLanes.isNotEmpty() &&
                        pendingApproachDistanceMeters <= ValhallaApproachLaneResolver.MAX_APPROACH_LANE_LOOKBACK_METERS &&
                        ValhallaApproachLaneResolver.isTurnOrExitManeuver(type) -> {
                        laneSource = LaneSource.CARRIED_ALIGNED
                        resolvedLanes = ValhallaApproachLaneResolver.alignApproachLanesWithManeuver(pendingApproachLanes, type)
                        carriedDistanceMeters = pendingApproachDistanceMeters
                        carriedHadIntervening = pendingApproachHadInterveningIntersection
                        rawLaneJson = pendingApproachRawJson.ifBlank { serializeLanesFallback(resolvedLanes) }
                    }
                    else -> {
                        laneSource = LaneSource.NONE
                        resolvedLanes = emptyList()
                        carriedDistanceMeters = 0.0
                        carriedHadIntervening = false
                        rawLaneJson = ""
                    }
                }

                // Track lane data stored on approaching road segments leading into the next turn
                val segmentLengthMeters = mObj.optDouble("length", 0.0) * 1000.0
                val carryFromThisSegment = when {
                    approachLanesOnManeuver.isNotEmpty() -> approachLanesOnManeuver
                    !ValhallaApproachLaneResolver.isTurnOrExitManeuver(type) && directLanes.isNotEmpty() -> directLanes
                    else -> emptyList()
                }
                if (carryFromThisSegment.isNotEmpty()) {
                    pendingApproachLanes = carryFromThisSegment
                    pendingApproachDistanceMeters = segmentLengthMeters
                    pendingApproachHadInterveningIntersection = false
                    pendingApproachRawJson = extractRawLaneJson(mObj).ifBlank { serializeLanesFallback(carryFromThisSegment) }
                } else if (type == ManeuverType.STRAIGHT || type == ManeuverType.DEPART || type == ManeuverType.MERGE) {
                    if (pendingApproachLanes.isNotEmpty()) {
                        pendingApproachHadInterveningIntersection = true
                    }
                    pendingApproachDistanceMeters += segmentLengthMeters
                    if (pendingApproachDistanceMeters > ValhallaApproachLaneResolver.MAX_APPROACH_LANE_LOOKBACK_METERS) {
                        pendingApproachLanes = emptyList()
                        pendingApproachHadInterveningIntersection = false
                        pendingApproachRawJson = ""
                    }
                } else {
                    pendingApproachLanes = emptyList()
                    pendingApproachDistanceMeters = 0.0
                    pendingApproachHadInterveningIntersection = false
                    pendingApproachRawJson = ""
                }

                val isToll = mObj.optBoolean("toll", false)
                val isHighwayFlag = mObj.optBoolean("highway", false) ||
                    streetNames.any {
                        it.startsWith("I-") || it.startsWith("I ") ||
                            it.startsWith("US-") || it.startsWith("OR-") || it.startsWith("WA-")
                    }
                val roundaboutExitCount = if (mObj.has("roundabout_exit_count")) {
                    mObj.optInt("roundabout_exit_count", 0).takeIf { it > 0 }
                } else null

                if (isToll) hasTolls = true

                // Strictly read OSM maxspeed / speed_limit from Valhalla — NEVER invent a speed limit!
                val speedLimitKph = when {
                    mObj.has("speed_limit") -> mObj.optDouble("speed_limit", -1.0)
                    mObj.has("max_speed") -> mObj.optDouble("max_speed", -1.0)
                    else -> -1.0
                }
                val speedLimitMph = if (speedLimitKph > 0.0) {
                    (speedLimitKph * 0.621371).toInt().coerceIn(10, 85)
                } else null

                if (speedLimitMph != null) {
                    for (idx in beginIdx..minOf(endIdx, combinedShape.lastIndex)) {
                        speedLimits[idx] = speedLimitMph
                    }
                    if (previousSpeedLimit != null && (previousSpeedLimit - speedLimitMph) >= 15) {
                        val faSpeed = BilingualFormatter.formatNumber(speedLimitMph, AppLanguage.PERSIAN)
                        alerts.add(
                            AheadAlert(
                                id = "speed_drop_$maneuverCounter",
                                kind = RoadAlertKind.SPEED_LIMIT_DROP,
                                lat = point.lat,
                                lon = point.lon,
                                distanceMeters = 0.0,
                                titleEn = "Speed limit drops to $speedLimitMph ahead",
                                titleFa = "سرعت مجاز جلوتر به $faSpeed مایل کاهش پیدا می‌کند",
                                newSpeedLimitMph = speedLimitMph
                            )
                        )
                    }
                    previousSpeedLimit = speedLimitMph
                }

                if (isToll) {
                    alerts.add(
                        AheadAlert(
                            id = "toll_$maneuverCounter",
                            kind = RoadAlertKind.TOLL_BOOTH,
                            lat = point.lat,
                            lon = point.lon,
                            distanceMeters = 0.0,
                            titleEn = "Toll booth ahead",
                            titleFa = "جلوتر عوارضی است",
                            detail = streetNames.firstOrNull().orEmpty()
                        )
                    )
                }

                val isExitOrSplit = type in setOf(
                    ManeuverType.EXIT_RIGHT,
                    ManeuverType.EXIT_LEFT,
                    ManeuverType.RAMP_RIGHT,
                    ManeuverType.RAMP_LEFT,
                    ManeuverType.STAY_RIGHT,
                    ManeuverType.STAY_LEFT
                ) || (signpost?.exitNumbers?.isNotEmpty() == true) ||
                    (isHighwayFlag && signpost?.hasContent == true && type in setOf(
                        ManeuverType.SLIGHT_RIGHT,
                        ManeuverType.SLIGHT_LEFT,
                        ManeuverType.MERGE
                    ))

                val lengthKm = mObj.optDouble("length", 0.0)
                val timeSec = mObj.optDouble("time", 0.0)

                val explicitLaneCount = maxOf(
                    mObj.optInt("lane_count", 0),
                    mObj.optInt("lanes_count", 0),
                    mObj.optInt("lanes_forward", 0),
                    resolvedLanes.size
                )
                val isMultiLaneRoad = explicitLaneCount >= 2 ||
                    isHighwayFlag ||
                    isExitOrSplit ||
                    (speedLimitMph != null && speedLimitMph >= 35)

                combinedManeuvers.add(
                    RouteManeuver(
                        index = maneuverCounter++,
                        type = type,
                        instructionEn = mObj.optString("instruction", ""),
                        streetNames = streetNames,
                        lengthMeters = lengthKm * 1000.0,
                        timeSeconds = timeSec,
                        beginShapeIndex = beginIdx,
                        endShapeIndex = endIdx,
                        lat = point.lat,
                        lon = point.lon,
                        signpost = signpost?.takeIf { it.hasContent },
                        lanes = resolvedLanes,
                        isHighwayExitOrSplit = isExitOrSplit,
                        isHighwayRoad = isHighwayFlag || isExitOrSplit,
                        roundaboutExitCount = roundaboutExitCount,
                        hasToll = isToll,
                        speedLimitMph = speedLimitMph,
                        laneCount = explicitLaneCount,
                        isMultiLaneRoad = isMultiLaneRoad,
                        laneSource = laneSource,
                        carriedLaneDistanceMeters = carriedDistanceMeters,
                        carriedLaneHadInterveningIntersection = carriedHadIntervening,
                        rawLaneJson = rawLaneJson
                    )
                )
            }
        }

        alerts.addAll(ValhallaPolylineAndGeo.detectSharpCurves(combinedShape))
        val statesCrossed = ValhallaPolylineAndGeo.detectStatesCrossed(combinedShape)

        val summary = trip.optJSONObject("summary")
        val totalLengthMeters = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0
        val totalTimeSeconds = summary?.optDouble("time", 0.0) ?: 0.0

        val majorRoad = combinedManeuvers
            .maxByOrNull { it.lengthMeters }
            ?.streetNames
            ?.firstOrNull()
            .orEmpty()

        return OfflineRoute(
            id = "route_$routeIndex",
            summaryLabel = majorRoad.ifBlank { "Route ${routeIndex + 1}" },
            distanceMeters = totalLengthMeters,
            durationSeconds = totalTimeSeconds,
            shape = combinedShape,
            maneuvers = combinedManeuvers,
            speedLimitsByShapeIndex = speedLimits,
            roadAlerts = alerts,
            crossesStateLines = statesCrossed.size > 1,
            statesCrossed = statesCrossed,
            hasTolls = hasTolls
        )
    }

    fun parseTurnLanes(maneuverObj: JSONObject, maneuverType: ManeuverType? = null): List<LaneInfo> =
        ValhallaTurnLaneDecoder.parseTurnLanes(maneuverObj, maneuverType)

    internal fun decodeValhallaTurnLaneMask(mask: Int): List<LaneDirection> =
        ValhallaTurnLaneDecoder.decodeValhallaTurnLaneMask(mask)

    fun decodePolyline6(encoded: String): List<LatLon> =
        ValhallaPolylineAndGeo.decodePolyline6(encoded)

    fun encodePolyline6(points: List<LatLon>): String =
        ValhallaPolylineAndGeo.encodePolyline6(points)

    fun bearingDegrees(from: LatLon, to: LatLon): Double =
        ValhallaPolylineAndGeo.bearingDegrees(from, to)

    fun angleDiffDegrees(b1: Double, b2: Double): Double =
        ValhallaPolylineAndGeo.angleDiffDegrees(b1, b2)

    private fun extractRawLaneJson(mObj: JSONObject): String {
        val keys = listOf("turn_lanes", "lanes", "approach_lanes", "approach_turn_lanes", "intersections", "banner_instructions")
        for (key in keys) {
            val arr = mObj.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr.toString()
            val str = mObj.optString(key, "")
            if (str.isNotBlank()) return str
        }
        return ""
    }

    private fun serializeLanesFallback(lanes: List<LaneInfo>): String {
        val arr = JSONArray()
        lanes.forEach { lane ->
            arr.put(
                JSONObject().apply {
                    put("dirs", JSONArray(lane.directions.map { it.name }))
                    put("active", lane.isActive)
                    put("valid", lane.isValid)
                }
            )
        }
        return arr.toString()
    }
}
