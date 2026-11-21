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
     * Combines fastest-costing routes with an optional shortest-costing route so that:
     *  - When the shortest route differs meaningfully from the fastest route, it is ALWAYS
     *    included in the preview options (up to 3 total routes) and tagged `isShortestRoute = true`.
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
        val primaryFastest = sortedFastest.first().copy(isFastestRoute = true)
        val otherAlternates = sortedFastest.drop(1)

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
            // Keep at most 1 non-duplicate alternate alongside fastest + shortest (3 cards max)
            val extraAlt = otherAlternates.firstOrNull { alt ->
                isDistinctRoute(alt, taggedShortest) && isDistinctRoute(alt, primaryFastest)
            }
            if (preferShortest) {
                merged.add(taggedShortest)
                merged.add(primaryFastest)
                if (extraAlt != null) merged.add(extraAlt)
            } else {
                merged.add(primaryFastest)
                merged.add(taggedShortest)
                if (extraAlt != null) merged.add(extraAlt)
            }
        }
        return merged.take(3)
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
            var previousSpeedLimit: Int? = null
            var pendingApproachLanes: List<LaneInfo> = emptyList()

            for (m in 0 until maneuversArr.length()) {
                val mObj = maneuversArr.optJSONObject(m) ?: continue
                val rawType = mObj.optInt("type", 8)
                val type = mapValhallaManeuverType(rawType)
                val beginIdx = shapeOffset + mObj.optInt("begin_shape_index", 0)
                val endIdx = shapeOffset + mObj.optInt("end_shape_index", beginIdx)
                val point = combinedShape.getOrNull(beginIdx) ?: combinedShape.firstOrNull() ?: LatLon(0.0, 0.0)

                val streetNames = parseStringArray(mObj.optJSONArray("street_names"))
                val signpost = parseSignpost(mObj.optJSONObject("sign"))

                // Phase 3: Parse real Valhalla turn_lanes on this maneuver OR on the approach
                // segment right before the stop line. NEVER guess or invent lanes when absent!
                val directLanes = parseTurnLanes(mObj)
                val approachLanes = parseApproachSegmentLanes(mObj)
                val resolvedLanes = when {
                    directLanes.isNotEmpty() -> directLanes
                    pendingApproachLanes.isNotEmpty() && isTurnOrExitManeuver(type) -> pendingApproachLanes
                    else -> emptyList()
                }
                pendingApproachLanes = approachLanes

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
                        speedLimitMph = speedLimitMph
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

    private fun parseApproachSegmentLanes(maneuverObj: JSONObject): List<LaneInfo> {
        val approachArr = maneuverObj.optJSONArray("approach_lanes")
            ?: maneuverObj.optJSONArray("preceding_turn_lanes")
            ?: return emptyList()
        return parseLaneJsonArray(approachArr)
    }

    fun parseTurnLanes(maneuverObj: JSONObject): List<LaneInfo> {
        val rawArray = maneuverObj.optJSONArray("turn_lanes")
            ?: maneuverObj.optJSONArray("lanes")
            ?: return emptyList()
        return parseLaneJsonArray(rawArray)
    }

    private fun parseLaneJsonArray(rawArray: JSONArray): List<LaneInfo> {
        if (rawArray.length() == 0) return emptyList()
        val result = mutableListOf<LaneInfo>()
        for (i in 0 until rawArray.length()) {
            val laneObj = rawArray.optJSONObject(i) ?: continue
            val dirs = mutableListOf<LaneDirection>()
            val activeDirs = mutableListOf<LaneDirection>()

            val indicationsArr = laneObj.optJSONArray("indications")
                ?: laneObj.optJSONArray("directions")
            if (indicationsArr != null) {
                for (j in 0 until indicationsArr.length()) {
                    parseLaneDirectionToken(indicationsArr.optString(j))?.let { dirs.add(it) }
                }
            } else if (laneObj.has("directions_mask")) {
                dirs.addAll(decodeValhallaTurnLaneMask(laneObj.optInt("directions_mask")))
            }

            val activeMask = laneObj.optInt("active_direction", 0)
            if (activeMask > 0) {
                activeDirs.addAll(decodeValhallaTurnLaneMask(activeMask))
            } else {
                val activeStr = laneObj.optString("active_direction", "")
                parseLaneDirectionToken(activeStr)?.let { activeDirs.add(it) }
            }

            val valid = laneObj.optBoolean("valid", false) ||
                laneObj.optBoolean("active", false) ||
                activeDirs.isNotEmpty()

            if (dirs.isNotEmpty()) {
                result.add(
                    LaneInfo(
                        directions = dirs.distinct(),
                        activeDirections = if (activeDirs.isNotEmpty()) activeDirs.distinct() else if (valid) dirs.distinct() else emptyList(),
                        isRecommended = valid
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
            "reverse", "uturn", "u_turn", "uturn_left", "reverse_left" -> LaneDirection.U_TURN_LEFT
            "uturn_right", "reverse_right" -> LaneDirection.U_TURN_RIGHT
            else -> null
        }
    }

    private fun decodeValhallaTurnLaneMask(mask: Int): List<LaneDirection> {
        val list = mutableListOf<LaneDirection>()
        if ((mask and 4) != 0) list.add(LaneDirection.SHARP_LEFT)
        if ((mask and 8) != 0) list.add(LaneDirection.LEFT)
        if ((mask and 16) != 0) list.add(LaneDirection.SLIGHT_LEFT)
        if ((mask and 2) != 0) list.add(LaneDirection.THROUGH)
        if ((mask and 32) != 0) list.add(LaneDirection.SLIGHT_RIGHT)
        if ((mask and 64) != 0) list.add(LaneDirection.RIGHT)
        if ((mask and 128) != 0) list.add(LaneDirection.SHARP_RIGHT)
        if ((mask and 256) != 0) list.add(LaneDirection.U_TURN_LEFT)
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
