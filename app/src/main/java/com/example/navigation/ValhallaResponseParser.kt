package com.example.navigation

import com.example.data.model.UsStatesCatalog
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    ): String {
        val originObj = JSONObject().apply {
            put("lat", origin.lat)
            put("lon", origin.lon)
            put("type", "break")
            if (headingDegrees != null && headingDegrees in 0f..360f) {
                put("heading", headingDegrees.toInt())
                put("heading_tolerance", 60)
            }
        }
        val destObj = JSONObject().apply {
            put("lat", destination.lat)
            put("lon", destination.lon)
            put("type", "break")
        }

        val autoOptions = JSONObject().apply {
            put("use_tolls", if (options.avoidTolls) 0.0 else 0.5)
            put("use_highways", if (options.avoidHighways) 0.0 else 1.0)
            put("use_ferry", if (options.avoidFerries) 0.0 else 0.5)
            if (options.preferShortest) {
                put("shortest", true)
            }
        }

        return JSONObject().apply {
            put("locations", JSONArray().put(originObj).put(destObj))
            put("costing", "auto")
            put("costing_options", JSONObject().put("auto", autoOptions))
            put("units", "kilometers")
            put("language", "en-US")
            put("alternates", alternatesCount.coerceIn(0, 2))
            put("directions_options", JSONObject().apply {
                put("units", "kilometers")
                put("narrative", true)
            })
            put("banner_instructions", true)
            put("voice_instructions", false)
            put("turn_lanes", true)

            // Phase 4: Avoid active user-reported Road Closures via Valhalla exclude_locations
            if (options.avoidLocations.isNotEmpty()) {
                val avoidArr = JSONArray()
                options.avoidLocations.take(20).forEach { loc ->
                    avoidArr.put(JSONObject().apply {
                        put("lat", loc.lat)
                        put("lon", loc.lon)
                    })
                }
                put("exclude_locations", avoidArr)
            }
        }.toString()
    }

    /**
     * Builds a Valhalla `trace_attributes` JSON request using `shape_match: "edge_walk"` to fetch
     * real OSM edge speed limits (`edge.speed_limit` in km/h), shape index spans, and `edge.lane_count`
     * along a calculated route shape.
     */
    fun buildTraceAttributesRequestJson(encodedPolyline6: String): String {
        return JSONObject().apply {
            put("encoded_polyline", encodedPolyline6)
            put("shape_match", "edge_walk")
            put("costing", "auto")
            put("units", "kilometers")
            put("filters", JSONObject().apply {
                put(
                    "attributes",
                    JSONArray()
                        .put("edge.speed_limit")
                        .put("edge.begin_shape_index")
                        .put("edge.end_shape_index")
                        .put("edge.lane_count")
                )
                put("action", "include")
            })
        }.toString()
    }

    data class TraceAttributesData(
        val speedLimitsByShapeIndex: Map<Int, Int>,
        val laneCountsByShapeIndex: Map<Int, Int>
    )

    /**
     * Parses Valhalla `trace_attributes` response JSON into per-shape-index speed limits (mph,
     * converted from Valhalla's km/h values; missing/zero/unlimited values are strictly ignored)
     * and per-shape-index lane counts.
     */
    fun parseTraceAttributes(traceJson: String, shapeSize: Int): TraceAttributesData {
        if (traceJson.isBlank() || shapeSize <= 0) {
            return TraceAttributesData(emptyMap(), emptyMap())
        }
        val root = JSONObject(traceJson)
        if (root.has("error_code") || root.has("error")) {
            return TraceAttributesData(emptyMap(), emptyMap())
        }
        val edges = root.optJSONArray("edges") ?: return TraceAttributesData(emptyMap(), emptyMap())
        val speedLimits = mutableMapOf<Int, Int>()
        val laneCounts = mutableMapOf<Int, Int>()
        val lastIdx = shapeSize - 1

        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            val beginRaw = edge.optInt("begin_shape_index", -1)
            val endRaw = edge.optInt("end_shape_index", -1)
            if (beginRaw < 0 || endRaw < beginRaw) continue
            val beginIdx = beginRaw.coerceIn(0, lastIdx)
            val endIdx = endRaw.coerceIn(beginIdx, lastIdx)

            // Valhalla returns edge.speed_limit in km/h (0 or omitted or 255 = unlimited/unknown)
            val speedKph = if (edge.has("speed_limit") && !edge.isNull("speed_limit")) {
                edge.optDouble("speed_limit", 0.0)
            } else {
                0.0
            }
            if (speedKph > 0.0 && speedKph < 250.0) {
                val speedMph = (speedKph * 0.621371).roundToInt().coerceIn(5, 85)
                for (idx in beginIdx..endIdx) {
                    speedLimits[idx] = speedMph
                }
            }

            val laneCount = if (edge.has("lane_count") && !edge.isNull("lane_count")) {
                edge.optInt("lane_count", 0)
            } else {
                0
            }
            if (laneCount > 0) {
                for (idx in beginIdx..endIdx) {
                    laneCounts[idx] = laneCount
                }
            }
        }

        return TraceAttributesData(
            speedLimitsByShapeIndex = speedLimits,
            laneCountsByShapeIndex = laneCounts
        )
    }

    /**
     * Enriches an [OfflineRoute] with real OSM speed limits and edge lane counts returned by
     * Valhalla's `trace_attributes` action. Never invents speed limits where OSM data is absent.
     */
    fun enrichRouteWithTraceAttributes(route: OfflineRoute, traceJson: String): OfflineRoute {
        if (route.shape.isEmpty() || traceJson.isBlank()) return route
        val traceData = runCatching { parseTraceAttributes(traceJson, route.shape.size) }.getOrNull()
            ?: return route
        if (traceData.speedLimitsByShapeIndex.isEmpty() && traceData.laneCountsByShapeIndex.isEmpty()) {
            return route
        }

        val mergedSpeedLimits = LinkedHashMap<Int, Int>(route.speedLimitsByShapeIndex.size + traceData.speedLimitsByShapeIndex.size)
        mergedSpeedLimits.putAll(route.speedLimitsByShapeIndex)
        mergedSpeedLimits.putAll(traceData.speedLimitsByShapeIndex)

        val updatedManeuvers = route.maneuvers.map { maneuver ->
            val edgeSpeedMph = mergedSpeedLimits[maneuver.beginShapeIndex]
            val edgeLaneCount = traceData.laneCountsByShapeIndex[maneuver.beginShapeIndex] ?: 0
            val finalLaneCount = maxOf(maneuver.laneCount, edgeLaneCount, maneuver.lanes.size)
            val finalSpeedMph = maneuver.speedLimitMph ?: edgeSpeedMph
            maneuver.copy(
                speedLimitMph = finalSpeedMph,
                laneCount = finalLaneCount,
                isMultiLaneRoad = maneuver.isMultiLaneRoad ||
                    finalLaneCount >= 2 ||
                    (finalSpeedMph != null && finalSpeedMph >= 35)
            )
        }

        // Detect any newly revealed >= 15 mph speed limit drops along the route
        val existingDropIds = route.roadAlerts
            .filter { it.kind == RoadAlertKind.SPEED_LIMIT_DROP }
            .map { it.id }
            .toSet()
        val extraAlerts = mutableListOf<AheadAlert>()
        var prevSpeed: Int? = null
        for (m in updatedManeuvers) {
            val curSpeed = m.speedLimitMph
            if (curSpeed != null) {
                val dropId = "speed_drop_${m.index}"
                if (prevSpeed != null && (prevSpeed - curSpeed) >= 15 && dropId !in existingDropIds) {
                    val faSpeed = BilingualFormatter.formatNumber(curSpeed, AppLanguage.PERSIAN)
                    extraAlerts.add(
                        AheadAlert(
                            id = dropId,
                            kind = RoadAlertKind.SPEED_LIMIT_DROP,
                            lat = m.lat,
                            lon = m.lon,
                            distanceMeters = 0.0,
                            titleEn = "Speed limit drops to $curSpeed ahead",
                            titleFa = "سرعت مجاز جلوتر به $faSpeed مایل کاهش پیدا می‌کند",
                            newSpeedLimitMph = curSpeed
                        )
                    )
                }
                prevSpeed = curSpeed
            }
        }

        return route.copy(
            maneuvers = updatedManeuvers,
            speedLimitsByShapeIndex = mergedSpeedLimits,
            roadAlerts = if (extraAlerts.isEmpty()) route.roadAlerts else route.roadAlerts + extraAlerts
        )
    }

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

    /**
     * Combines fastest-costing routes (primary + up to 2 alternates) with an optional
     * shortest-costing route so that:
     *  - When the shortest route differs meaningfully from the fastest route, it is ALWAYS
     *    included in the preview options (up to 4 total routes: fastest, two alternatives,
     *    and shortest) and tagged `isShortestRoute = true`.
     *  - Index 0 reflects the user's RoutePreference (`preferShortest = true` puts Shortest first;
     *    default `FASTEST` puts Fastest first).
     */
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
            // Shortest route is genuinely shorter or distinct from the primary fastest route
            isDistinctRoute(s, primaryFastest)
        }

        val merged = mutableListOf<OfflineRoute>()
        if (candidateShortest == null) {
            // Fastest route is already the shortest route
            merged.add(primaryFastest.copy(isFastestRoute = true, isShortestRoute = true))
            merged.addAll(otherAlternates.take(2))
        } else {
            val taggedShortest = candidateShortest.copy(
                id = "route_shortest",
                isShortestRoute = true,
                isFastestRoute = false
            )
            // Keep up to 2 non-duplicate alternates alongside fastest + shortest (up to 4 routes total)
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
        // Check midpoint divergence in case distance & duration are nearly identical on different streets
        if (a.shape.size >= 3 && b.shape.size >= 3) {
            val midA = a.shape[a.shape.size / 2]
            val midB = b.shape[b.shape.size / 2]
            val midDist = OfflinePlaceSearchEngine.haversineMeters(midA.lat, midA.lon, midB.lat, midB.lon)
            if (midDist >= 120.0) return true
        }
        return false
    }

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

            for (m in 0 until maneuversArr.length()) {
                val mObj = maneuversArr.optJSONObject(m) ?: continue
                val rawType = mObj.optInt("type", 8)
                val type = mapValhallaManeuverType(rawType)
                val beginIdx = shapeOffset + mObj.optInt("begin_shape_index", 0)
                val endIdx = shapeOffset + mObj.optInt("end_shape_index", beginIdx)
                val point = combinedShape.getOrNull(beginIdx) ?: combinedShape.firstOrNull() ?: LatLon(0.0, 0.0)

                val streetNames = parseStringArray(mObj.optJSONArray("street_names"))
                val signpost = parseSignpost(mObj.optJSONObject("sign"))

                // Phase 3 & Banner Lane Guidance: Read real Valhalla lane data from the maneuver
                // itself (turn_lanes / lanes / banner_instructions / intersections) AND from
                // approaching road segments before the intersection. NEVER invent lanes when absent!
                val directLanes = parseManeuverDirectLanes(mObj, type)
                val approachLanesOnManeuver = parseApproachSegmentLanes(mObj)
                val edgeApproachLanes = findApproachingEdgeLanes(
                    edgesArr = legEdgesArr,
                    intersectionsArr = legIntersectionsArr,
                    shapeOffset = shapeOffset,
                    maneuverBeginIdx = beginIdx,
                    combinedShape = combinedShape,
                    maneuverType = type
                )

                val resolvedLanes = when {
                    directLanes.isNotEmpty() -> directLanes
                    edgeApproachLanes.isNotEmpty() && isTurnOrExitManeuver(type) -> edgeApproachLanes
                    pendingApproachLanes.isNotEmpty() &&
                        pendingApproachDistanceMeters <= MAX_APPROACH_LANE_LOOKBACK_METERS &&
                        isTurnOrExitManeuver(type) ->
                        alignApproachLanesWithManeuver(pendingApproachLanes, type)
                    else -> emptyList()
                }

                // Track lane data stored on approaching road segments leading into the next turn
                val segmentLengthMeters = mObj.optDouble("length", 0.0) * 1000.0
                val carryFromThisSegment = when {
                    approachLanesOnManeuver.isNotEmpty() -> approachLanesOnManeuver
                    !isTurnOrExitManeuver(type) && directLanes.isNotEmpty() -> directLanes
                    else -> emptyList()
                }
                if (carryFromThisSegment.isNotEmpty()) {
                    pendingApproachLanes = carryFromThisSegment
                    pendingApproachDistanceMeters = segmentLengthMeters
                } else if (type == ManeuverType.STRAIGHT || type == ManeuverType.DEPART || type == ManeuverType.MERGE) {
                    pendingApproachDistanceMeters += segmentLengthMeters
                    if (pendingApproachDistanceMeters > MAX_APPROACH_LANE_LOOKBACK_METERS) {
                        pendingApproachLanes = emptyList()
                    }
                } else {
                    // A turn/exit consumed or ended the previous approach corridor
                    pendingApproachLanes = emptyList()
                    pendingApproachDistanceMeters = 0.0
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
                ) || (signpost?.exitNumbers?.isNotEmpty() == true)

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
                        isMultiLaneRoad = isMultiLaneRoad
                    )
                )
            }
        }

        alerts.addAll(detectSharpCurves(combinedShape))
        val statesCrossed = detectStatesCrossed(combinedShape)

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

    private fun isTurnOrExitManeuver(type: ManeuverType): Boolean = type in setOf(
        ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT,
        ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT,
        ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT,
        ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT,
        ManeuverType.STAY_LEFT, ManeuverType.STAY_RIGHT,
        ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT
    )

    private const val MAX_APPROACH_LANE_LOOKBACK_METERS = 1750.0

    private fun parseManeuverDirectLanes(maneuverObj: JSONObject, type: ManeuverType): List<LaneInfo> {
        val direct = parseTurnLanes(maneuverObj, type)
        if (direct.isNotEmpty()) return direct

        val bannerLanes = parseBannerInstructionLanes(maneuverObj, type)
        if (bannerLanes.isNotEmpty()) return bannerLanes

        val intersectionLanes = parseIntersectionLanes(maneuverObj.optJSONArray("intersections"), type)
        if (intersectionLanes.isNotEmpty()) return intersectionLanes

        return emptyList()
    }

    private fun parseApproachSegmentLanes(maneuverObj: JSONObject): List<LaneInfo> {
        val approachArr = maneuverObj.optJSONArray("approach_lanes")
            ?: maneuverObj.optJSONArray("preceding_turn_lanes")
            ?: maneuverObj.optJSONArray("approach_turn_lanes")
        if (approachArr != null) {
            val parsed = parseLaneJsonArray(approachArr, maneuverType = null)
            if (parsed.isNotEmpty()) return parsed
        }
        // Intersections along this approaching segment (last intersection right before the next turn)
        val intersections = maneuverObj.optJSONArray("intersections")
        if (intersections != null) {
            val parsed = parseIntersectionLanes(intersections, maneuverType = null)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun findApproachingEdgeLanes(
        edgesArr: JSONArray?,
        intersectionsArr: JSONArray?,
        shapeOffset: Int,
        maneuverBeginIdx: Int,
        combinedShape: List<LatLon>,
        maneuverType: ManeuverType
    ): List<LaneInfo> {
        if (edgesArr != null && edgesArr.length() > 0) {
            // Walk edges backwards from the intersection to find the closest approaching segment with real lanes
            var bestEndIdx = -1
            var bestLanes: List<LaneInfo> = emptyList()
            for (i in 0 until edgesArr.length()) {
                val edge = edgesArr.optJSONObject(i) ?: continue
                val edgeEndIdx = shapeOffset + edge.optInt("end_shape_index", -1)
                if (edgeEndIdx < 0 || edgeEndIdx > maneuverBeginIdx) continue
                val distToTurn = shapeDistanceBetween(combinedShape, edgeEndIdx, maneuverBeginIdx)
                if (distToTurn > MAX_APPROACH_LANE_LOOKBACK_METERS) continue
                val lanes = parseTurnLanes(edge, maneuverType)
                if (lanes.isNotEmpty() && edgeEndIdx >= bestEndIdx) {
                    bestEndIdx = edgeEndIdx
                    bestLanes = lanes
                }
            }
            if (bestLanes.isNotEmpty()) {
                return alignApproachLanesWithManeuver(bestLanes, maneuverType)
            }
        }
        if (intersectionsArr != null && intersectionsArr.length() > 0) {
            var bestIdx = -1
            var bestLanes: List<LaneInfo> = emptyList()
            for (i in 0 until intersectionsArr.length()) {
                val inter = intersectionsArr.optJSONObject(i) ?: continue
                val interShapeIdx = shapeOffset + inter.optInt("shape_index", -1)
                if (interShapeIdx < 0 || interShapeIdx > maneuverBeginIdx) continue
                val distToTurn = shapeDistanceBetween(combinedShape, interShapeIdx, maneuverBeginIdx)
                if (distToTurn > MAX_APPROACH_LANE_LOOKBACK_METERS) continue
                val rawLanes = inter.optJSONArray("lanes") ?: inter.optJSONArray("turn_lanes") ?: continue
                val parsed = parseLaneJsonArray(rawLanes, maneuverType)
                if (parsed.isNotEmpty() && interShapeIdx >= bestIdx) {
                    bestIdx = interShapeIdx
                    bestLanes = parsed
                }
            }
            if (bestLanes.isNotEmpty()) {
                return alignApproachLanesWithManeuver(bestLanes, maneuverType)
            }
        }
        return emptyList()
    }

    private fun shapeDistanceBetween(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
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

    private fun parseBannerInstructionLanes(maneuverObj: JSONObject, maneuverType: ManeuverType?): List<LaneInfo> {
        val banners = maneuverObj.optJSONArray("banner_instructions") ?: return emptyList()
        for (i in 0 until banners.length()) {
            val banner = banners.optJSONObject(i) ?: continue
            val containers = listOfNotNull(
                banner.optJSONObject("sub"),
                banner.optJSONObject("primary")
            )
            for (container in containers) {
                val comps = container.optJSONArray("components") ?: continue
                val laneObjs = JSONArray()
                for (c in 0 until comps.length()) {
                    val comp = comps.optJSONObject(c) ?: continue
                    if (comp.optString("type").equals("lane", ignoreCase = true)) {
                        laneObjs.put(comp)
                    }
                }
                if (laneObjs.length() > 0) {
                    val parsed = parseLaneJsonArray(laneObjs, maneuverType)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
        return emptyList()
    }

    private fun parseIntersectionLanes(intersectionsArr: JSONArray?, maneuverType: ManeuverType?): List<LaneInfo> {
        if (intersectionsArr == null || intersectionsArr.length() == 0) return emptyList()
        for (i in intersectionsArr.length() - 1 downTo 0) {
            val inter = intersectionsArr.optJSONObject(i) ?: continue
            val rawLanes = inter.optJSONArray("lanes") ?: inter.optJSONArray("turn_lanes") ?: continue
            val parsed = parseLaneJsonArray(rawLanes, maneuverType)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /**
     * When lane arrows come from an approaching road segment before the turn, refine active
     * directions for combined-arrow lanes so only the arrow(s) needed for [maneuverType] are
     * highlighted (never inventing new lanes).
     */
    private fun alignApproachLanesWithManeuver(lanes: List<LaneInfo>, maneuverType: ManeuverType): List<LaneInfo> {
        val preferredDirs = preferredDirectionsForManeuver(maneuverType)
        val alreadyHasHighlight = lanes.any { it.isActive || it.isValid || it.isRecommended }
        if (alreadyHasHighlight) {
            return lanes.map { lane ->
                if ((lane.isActive || lane.isValid) && lane.directions.size > 1 && lane.activeDirections.size == lane.directions.size) {
                    val narrowed = lane.directions.filter { it in preferredDirs }
                    if (narrowed.isNotEmpty()) lane.copy(activeDirections = narrowed) else lane
                } else {
                    lane
                }
            }
        }
        // Approaching segment had raw OSM turn:lanes without active/valid flags: highlight matching real lanes only
        val hasMatchingLane = lanes.any { lane -> lane.directions.any { it in preferredDirs } }
        if (!hasMatchingLane) return lanes
        return lanes.map { lane ->
            val matched = lane.directions.filter { it in preferredDirs }
            if (matched.isNotEmpty()) {
                lane.copy(
                    activeDirections = matched,
                    isRecommended = true,
                    isActive = true,
                    isValid = true
                )
            } else {
                lane
            }
        }
    }

    private fun preferredDirectionsForManeuver(type: ManeuverType): Set<LaneDirection> = when (type) {
        ManeuverType.LEFT -> setOf(LaneDirection.LEFT, LaneDirection.SLIGHT_LEFT, LaneDirection.SHARP_LEFT)
        ManeuverType.SLIGHT_LEFT, ManeuverType.STAY_LEFT, ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT ->
            setOf(LaneDirection.SLIGHT_LEFT, LaneDirection.LEFT)
        ManeuverType.SHARP_LEFT -> setOf(LaneDirection.SHARP_LEFT, LaneDirection.LEFT)
        ManeuverType.RIGHT -> setOf(LaneDirection.RIGHT, LaneDirection.SLIGHT_RIGHT, LaneDirection.SHARP_RIGHT)
        ManeuverType.SLIGHT_RIGHT, ManeuverType.STAY_RIGHT, ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT ->
            setOf(LaneDirection.SLIGHT_RIGHT, LaneDirection.RIGHT)
        ManeuverType.SHARP_RIGHT -> setOf(LaneDirection.SHARP_RIGHT, LaneDirection.RIGHT)
        ManeuverType.U_TURN_LEFT -> setOf(LaneDirection.U_TURN_LEFT, LaneDirection.LEFT)
        ManeuverType.U_TURN_RIGHT -> setOf(LaneDirection.U_TURN_RIGHT, LaneDirection.RIGHT)
        ManeuverType.MERGE -> setOf(LaneDirection.THROUGH, LaneDirection.MERGE_LEFT, LaneDirection.MERGE_RIGHT)
        ManeuverType.STRAIGHT, ManeuverType.DEPART -> setOf(LaneDirection.THROUGH)
        else -> emptySet()
    }

    fun parseTurnLanes(maneuverObj: JSONObject, maneuverType: ManeuverType? = null): List<LaneInfo> {
        val rawArray = maneuverObj.optJSONArray("lanes")
            ?: maneuverObj.optJSONArray("turn_lanes")
            ?: return emptyList()
        return parseLaneJsonArray(rawArray, maneuverType)
    }

    private fun parseLaneJsonArray(rawArray: JSONArray, maneuverType: ManeuverType? = null): List<LaneInfo> {
        if (rawArray.length() == 0) return emptyList()

        // Detect whether legacy boolean-style payloads explicitly distinguish `active` vs `valid`
        var anyLegacyExplicitActive = false
        for (i in 0 until rawArray.length()) {
            val laneObj = rawArray.optJSONObject(i) ?: continue
            val rawActive = laneObj.opt("active")
            if ((rawActive is Boolean && rawActive) ||
                (rawActive is Number && rawActive.toInt() != 0) ||
                laneObj.optInt("active_direction", 0) > 0 ||
                laneObj.optString("active_direction", "").isNotBlank()
            ) {
                anyLegacyExplicitActive = true
                break
            }
        }

        val preferredDirs = maneuverType?.let { preferredDirectionsForManeuver(it) }.orEmpty()
        val result = mutableListOf<LaneInfo>()
        for (i in 0 until rawArray.length()) {
            val laneObj = rawArray.optJSONObject(i) ?: continue
            val dirs = mutableListOf<LaneDirection>()
            val activeDirs = mutableListOf<LaneDirection>()

            // 1. Official Valhalla format: "directions" is an integer bitmask (always present).
            //    Fall back to JSON array, semicolon/pipe-delimited string, or "directions_mask".
            val rawDirections = laneObj.opt("directions")
            val directionsInt = if (rawDirections is Number) laneObj.optInt("directions", 0) else 0
            if (directionsInt > 0) {
                dirs.addAll(decodeValhallaTurnLaneMask(directionsInt))
            } else {
                val indicationsArr = laneObj.optJSONArray("directions")
                    ?: laneObj.optJSONArray("indications")
                if (indicationsArr != null) {
                    for (j in 0 until indicationsArr.length()) {
                        parseLaneDirectionToken(indicationsArr.optString(j))?.let { dirs.add(it) }
                    }
                } else if (rawDirections is String && rawDirections.isNotBlank()) {
                    rawDirections.split(';', '|', ',').forEach { token ->
                        parseLaneDirectionToken(token)?.let { dirs.add(it) }
                    }
                } else if (laneObj.has("directions_mask")) {
                    dirs.addAll(decodeValhallaTurnLaneMask(laneObj.optInt("directions_mask", 0)))
                }
            }

            // 2. Official Valhalla format: "active" and "valid" are optional integer bitmasks.
            //    - Best lane (`ACTIVE`): `active` bitmask is non-zero
            //    - Allowed lane (`VALID`): `valid` bitmask is non-zero (and `active` is zero)
            //    - activeDirections = decode(active) if non-zero, else decode(valid)
            val rawActive = laneObj.opt("active")
            val rawValid = laneObj.opt("valid")
            val activeBitmask = if (rawActive is Number) laneObj.optInt("active", 0) else 0
            val validBitmask = if (rawValid is Number) laneObj.optInt("valid", 0) else 0
            val isOfficialBitmaskLane = (rawDirections is Number) || (rawActive is Number) || (rawValid is Number)

            val isActive: Boolean
            val isValid: Boolean
            if (isOfficialBitmaskLane && rawActive !is Boolean && rawValid !is Boolean) {
                if (activeBitmask != 0) {
                    activeDirs.addAll(decodeValhallaTurnLaneMask(activeBitmask))
                } else if (validBitmask != 0) {
                    activeDirs.addAll(decodeValhallaTurnLaneMask(validBitmask))
                }
                isActive = activeBitmask != 0
                isValid = validBitmask != 0 || activeBitmask != 0
            } else {
                // Fallback for legacy / Mapbox-style JSON payloads (boolean valid/active + string active_direction)
                val activeDirMask = laneObj.optInt("active_direction", 0)
                if (activeDirMask > 0) {
                    activeDirs.addAll(decodeValhallaTurnLaneMask(activeDirMask))
                } else {
                    val activeStr = laneObj.optString("active_direction", "")
                    parseLaneDirectionToken(activeStr)?.let { activeDirs.add(it) }
                }
                val validIndication = laneObj.optString("valid_indication", "")
                if (validIndication.isNotBlank()) {
                    parseLaneDirectionToken(validIndication)?.let { activeDirs.add(it) }
                }
                val activeDirsArr = laneObj.optJSONArray("active_directions")
                if (activeDirsArr != null) {
                    for (j in 0 until activeDirsArr.length()) {
                        parseLaneDirectionToken(activeDirsArr.optString(j))?.let { activeDirs.add(it) }
                    }
                }

                val hasActiveBool = rawActive is Boolean
                val explicitActive = (rawActive as? Boolean) == true
                val explicitValid = (rawValid as? Boolean) == true
                val hasExplicitActiveDir = activeDirMask > 0 || laneObj.optString("active_direction", "").isNotBlank()

                when {
                    hasActiveBool -> {
                        isActive = explicitActive
                        isValid = explicitValid || explicitActive
                    }
                    hasExplicitActiveDir -> {
                        isActive = true
                        isValid = true
                    }
                    explicitValid -> {
                        isActive = !anyLegacyExplicitActive
                        isValid = true
                    }
                    else -> {
                        isActive = false
                        isValid = false
                    }
                }
            }

            val isRecommended = isActive || isValid
            val level = when {
                isActive -> LaneHighlightTier.ACTIVE
                isValid -> LaneHighlightTier.VALID
                else -> LaneHighlightTier.INACTIVE
            }

            if (dirs.isNotEmpty()) {
                val distinctDirs = dirs.distinct()
                val resolvedActiveDirs = when {
                    activeDirs.isNotEmpty() -> activeDirs.distinct().filter { it in distinctDirs }.ifEmpty { activeDirs.distinct() }
                    isRecommended && distinctDirs.size > 1 && preferredDirs.isNotEmpty() ->
                        distinctDirs.filter { it in preferredDirs }.ifEmpty { distinctDirs }
                    isRecommended -> distinctDirs
                    else -> emptyList()
                }
                result.add(
                    LaneInfo(
                        directions = distinctDirs,
                        activeDirections = resolvedActiveDirs,
                        isRecommended = isRecommended,
                        isActive = isActive,
                        isValid = isValid,
                        level = level
                    )
                )
            }
        }
        return result
    }

    private fun parseLaneDirectionToken(token: String): LaneDirection? {
        return when (token.trim().lowercase()) {
            "sharp_left", "sharp left" -> LaneDirection.SHARP_LEFT
            "left" -> LaneDirection.LEFT
            "slight_left", "slight left" -> LaneDirection.SLIGHT_LEFT
            "through", "straight", "none" -> LaneDirection.THROUGH
            "slight_right", "slight right" -> LaneDirection.SLIGHT_RIGHT
            "right" -> LaneDirection.RIGHT
            "sharp_right", "sharp right" -> LaneDirection.SHARP_RIGHT
            "reverse", "uturn", "u_turn", "uturn_left", "reverse_left", "u-turn" -> LaneDirection.U_TURN_LEFT
            "uturn_right", "reverse_right" -> LaneDirection.U_TURN_RIGHT
            "merge_to_left", "merge_left", "merge left" -> LaneDirection.MERGE_LEFT
            "merge_to_right", "merge_right", "merge right" -> LaneDirection.MERGE_RIGHT
            else -> null
        }
    }

    /**
     * Decodes an official Valhalla turn lane bitmask into ordered [LaneDirection] values:
     * 1 None, 2 Through, 4 SharpLeft, 8 Left, 16 SlightLeft, 32 SlightRight,
     * 64 Right, 128 SharpRight, 256 Reverse (U-turn), 512 MergeToLeft, 1024 MergeToRight.
     */
    internal fun decodeValhallaTurnLaneMask(mask: Int): List<LaneDirection> {
        val list = mutableListOf<LaneDirection>()
        if ((mask and 4) != 0) list.add(LaneDirection.SHARP_LEFT)
        if ((mask and 8) != 0) list.add(LaneDirection.LEFT)
        if ((mask and 16) != 0) list.add(LaneDirection.SLIGHT_LEFT)
        if ((mask and 2) != 0) list.add(LaneDirection.THROUGH)
        if ((mask and 32) != 0) list.add(LaneDirection.SLIGHT_RIGHT)
        if ((mask and 64) != 0) list.add(LaneDirection.RIGHT)
        if ((mask and 128) != 0) list.add(LaneDirection.SHARP_RIGHT)
        if ((mask and 256) != 0) list.add(LaneDirection.U_TURN_LEFT)
        if ((mask and 512) != 0) list.add(LaneDirection.MERGE_LEFT)
        if ((mask and 1024) != 0) list.add(LaneDirection.MERGE_RIGHT)
        // Valhalla bit 1 = kNone (unmarked OSM lane, e.g. `turn:lanes=left|none|right`):
        // if no directional bits are set, represent it as a through lane so the physical lane box is not dropped.
        if (list.isEmpty() && (mask and 1) != 0) {
            list.add(LaneDirection.THROUGH)
        }
        return list
    }

    private fun parseSignpost(signObj: JSONObject?): HighwaySignpost? {
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
            exitBranches = extractTextList(signObj.optJSONArray("exit_branch_elements")),
            exitTowards = extractTextList(signObj.optJSONArray("exit_toward_elements")),
            exitNames = extractTextList(signObj.optJSONArray("exit_name_elements"))
        )
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) add(s)
            }
        }
    }

    private fun mapValhallaManeuverType(code: Int): ManeuverType = when (code) {
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
        25 -> ManeuverType.MERGE
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

    /**
     * Encodes a list of [LatLon] coordinates into Valhalla's standard polyline6 string format.
     */
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

    private fun detectSharpCurves(shape: List<LatLon>): List<AheadAlert> {
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

    private fun detectStatesCrossed(shape: List<LatLon>): List<String> {
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
