package com.example.navigation

import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parses Valhalla `trace_attributes` payloads and enriches [OfflineRoute] instances with real OSM
 * speed limits, edge lane counts (DRIVE only), and elevation steep-grade warnings (X5: DRIVE/BIKE/WALK).
 */
internal object ValhallaTraceAttributesParser {

    fun parseTraceAttributes(
        traceJson: String,
        shapeSize: Int
    ): ValhallaResponseParser.TraceAttributesData {
        if (traceJson.isBlank() || shapeSize <= 0) {
            return ValhallaResponseParser.TraceAttributesData(emptyMap(), emptyMap(), emptyList())
        }
        val root = JSONObject(traceJson)
        if (root.has("error_code") || root.has("error")) {
            return ValhallaResponseParser.TraceAttributesData(emptyMap(), emptyMap(), emptyList())
        }
        val edges = root.optJSONArray("edges")
            ?: return ValhallaResponseParser.TraceAttributesData(emptyMap(), emptyMap(), emptyList())
        val speedLimits = mutableMapOf<Int, Int>()
        val laneCounts = mutableMapOf<Int, Int>()
        val wayIds = mutableMapOf<Int, Long>()
        val oneWayMap = mutableMapOf<Int, Boolean>()
        val gradeSpans = mutableListOf<EdgeGradeSpan>()
        val lastIdx = shapeSize - 1

        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            val beginRaw = edge.optInt("begin_shape_index", -1)
            val endRaw = edge.optInt("end_shape_index", -1)
            if (beginRaw < 0 || endRaw < beginRaw) continue
            val beginIdx = beginRaw.coerceIn(0, lastIdx)
            val endIdx = endRaw.coerceIn(beginIdx, lastIdx)

            val ownedEndIdx = when {
                beginIdx == endIdx -> endIdx
                endIdx == lastIdx -> endIdx
                else -> endIdx - 1
            }

            // Valhalla returns edge.speed_limit in km/h (0 or omitted or 255 = unlimited/unknown)
            val speedKph = if (edge.has("speed_limit") && !edge.isNull("speed_limit")) {
                edge.optDouble("speed_limit", 0.0)
            } else {
                0.0
            }
            if (speedKph > 0.0 && speedKph < 250.0) {
                val speedMph = (speedKph * 0.621371).roundToInt().coerceIn(5, 85)
                for (idx in beginIdx..ownedEndIdx) {
                    speedLimits[idx] = speedMph
                }
            }

            val laneCount = if (edge.has("lane_count") && !edge.isNull("lane_count")) {
                edge.optInt("lane_count", 0)
            } else {
                0
            }
            if (laneCount > 0) {
                for (idx in beginIdx..ownedEndIdx) {
                    laneCounts[idx] = laneCount
                }
            }

            val wayId = if (edge.has("way_id") && !edge.isNull("way_id")) {
                edge.optLong("way_id", 0L)
            } else {
                0L
            }
            if (wayId > 0L) {
                for (idx in beginIdx..ownedEndIdx) {
                    wayIds[idx] = wayId
                }
            }

            val traversability = edge.optString("traversability", "").trim().lowercase()
            val isOneWayEdge = when (traversability) {
                "forward", "backward" -> true
                "both" -> false
                else -> null
            }
            if (isOneWayEdge != null) {
                for (idx in beginIdx..ownedEndIdx) {
                    oneWayMap[idx] = isOneWayEdge
                }
            }

            // X5: Optional elevation grades (absent on old packs without elevation tiles)
            val maxUp = optFiniteDouble(edge, "max_upward_grade")
            val maxDown = optFiniteDouble(edge, "max_downward_grade")
            val weighted = optFiniteDouble(edge, "weighted_grade")
            if (endIdx > beginIdx && (maxUp != null || maxDown != null || weighted != null)) {
                gradeSpans.add(
                    EdgeGradeSpan(
                        beginShapeIndex = beginIdx,
                        endShapeIndex = endIdx,
                        maxUpwardGrade = maxUp,
                        maxDownwardGrade = maxDown,
                        weightedGrade = weighted
                    )
                )
            }
        }

        return ValhallaResponseParser.TraceAttributesData(
            speedLimitsByShapeIndex = speedLimits,
            laneCountsByShapeIndex = laneCounts,
            gradeSpans = gradeSpans,
            oneWayByShapeIndex = oneWayMap,
            wayIdsByShapeIndex = wayIds
        )
    }

    private fun optFiniteDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        return if (v.isFinite()) v else null
    }

    fun enrichRouteWithTraceAttributes(route: OfflineRoute, traceJson: String): OfflineRoute {
        if (route.shape.isEmpty() || traceJson.isBlank()) return route
        val traceData = runCatching { parseTraceAttributes(traceJson, route.shape.size) }.getOrNull()
            ?: return route

        // X5: Steep grade alerts apply to DRIVE and BIKE (silently empty when old packs lack grades)
        val existingGradeIds = route.roadAlerts
            .filter { it.kind == RoadAlertKind.STEEP_GRADE }
            .map { it.id }
            .toSet()
        val steepGradeAlerts = SteepGradeEvaluator
            .detectSteepGradeAlerts(route.shape, traceData.gradeSpans)
            .filterNot { it.id in existingGradeIds }

        // M2: For WALK and BIKE, never attach speed limits or speed-drop alerts
        if (route.travelMode != TravelMode.DRIVE) {
            return if (steepGradeAlerts.isEmpty()) route else route.copy(
                roadAlerts = route.roadAlerts + steepGradeAlerts
            )
        }

        if (traceData.speedLimitsByShapeIndex.isEmpty() &&
            traceData.laneCountsByShapeIndex.isEmpty() &&
            traceData.wayIdsByShapeIndex.isEmpty() &&
            traceData.oneWayByShapeIndex.isEmpty() &&
            steepGradeAlerts.isEmpty()
        ) {
            return route
        }

        val mergedSpeedLimits = LinkedHashMap<Int, Int>(
            route.speedLimitsByShapeIndex.size + traceData.speedLimitsByShapeIndex.size
        )
        mergedSpeedLimits.putAll(route.speedLimitsByShapeIndex)
        mergedSpeedLimits.putAll(traceData.speedLimitsByShapeIndex)

        val mergedOneWay = LinkedHashMap<Int, Boolean>(
            route.oneWayByShapeIndex.size + traceData.oneWayByShapeIndex.size
        )
        mergedOneWay.putAll(route.oneWayByShapeIndex)
        mergedOneWay.putAll(traceData.oneWayByShapeIndex)

        val mergedEdgeLaneCounts = LinkedHashMap<Int, Int>(
            route.edgeLaneCountsByShapeIndex.size + traceData.laneCountsByShapeIndex.size
        )
        mergedEdgeLaneCounts.putAll(route.edgeLaneCountsByShapeIndex)
        mergedEdgeLaneCounts.putAll(traceData.laneCountsByShapeIndex)

        val mergedEdgeWayIds = LinkedHashMap<Int, Long>(
            route.edgeWayIdsByShapeIndex.size + traceData.wayIdsByShapeIndex.size
        )
        mergedEdgeWayIds.putAll(route.edgeWayIdsByShapeIndex)
        mergedEdgeWayIds.putAll(traceData.wayIdsByShapeIndex)

        val updatedManeuvers = route.maneuvers.map { maneuver ->
            val edgeSpeedMph = mergedSpeedLimits[maneuver.beginShapeIndex]
            val edgeLaneCount = traceData.laneCountsByShapeIndex[maneuver.beginShapeIndex] ?: 0
            val finalLaneCount = maxOf(maneuver.laneCount, edgeLaneCount, maneuver.lanes.size)
            val finalSpeedMph = maneuver.speedLimitMph ?: edgeSpeedMph
            val verifiedGeometry = hasVerifiedApproachLaneGeometry(
                shape = route.shape,
                maneuver = maneuver,
                oneWayByShapeIndex = mergedOneWay,
                edgeLaneCountsByShapeIndex = mergedEdgeLaneCounts
            )
            maneuver.copy(
                speedLimitMph = finalSpeedMph,
                laneCount = finalLaneCount,
                isMultiLaneRoad = maneuver.isMultiLaneRoad ||
                    finalLaneCount >= 2 ||
                    (finalSpeedMph != null && finalSpeedMph >= 35),
                hasRoadLaneGeometry = maneuver.hasRoadLaneGeometry || verifiedGeometry
            )
        }

        val existingDropIds = route.roadAlerts
            .filter { it.kind == RoadAlertKind.SPEED_LIMIT_DROP }
            .map { it.id }
            .toSet()
        val extraAlerts = mutableListOf<AheadAlert>()
        extraAlerts.addAll(steepGradeAlerts)
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

        val routeHasVerifiedGeometry = route.hasRoadLaneGeometry || updatedManeuvers.any { it.hasRoadLaneGeometry }

        return route.copy(
            maneuvers = updatedManeuvers,
            speedLimitsByShapeIndex = mergedSpeedLimits,
            oneWayByShapeIndex = mergedOneWay,
            edgeLaneCountsByShapeIndex = mergedEdgeLaneCounts,
            edgeWayIdsByShapeIndex = mergedEdgeWayIds,
            roadAlerts = if (extraAlerts.isEmpty()) route.roadAlerts else route.roadAlerts + extraAlerts,
            hasRoadLaneGeometry = routeHasVerifiedGeometry
        )
    }

    /**
     * Verifies whether trace_attributes supplies enough road-carriageway geometry on the
     * maneuver approach (known one-way/two-way traversability + consistent edge lane count
     * + at least 10 m of approach shape) to safely draw on-road lane strips.
     */
    private fun hasVerifiedApproachLaneGeometry(
        shape: List<LatLon>,
        maneuver: RouteManeuver,
        oneWayByShapeIndex: Map<Int, Boolean>,
        edgeLaneCountsByShapeIndex: Map<Int, Int>
    ): Boolean {
        if (shape.size < 2 || maneuver.lanes.size < 2) return false
        val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, shape.lastIndex)
        if (pivotIdx <= 0) return false
        val lookbackStart = (pivotIdx - 1).coerceAtLeast(0)
        val lookbackMin = (lookbackStart - 12).coerceAtLeast(0)
        var approachOneWay: Boolean? = null
        var approachEdgeLanes: Int? = null
        for (i in lookbackStart downTo lookbackMin) {
            if (approachOneWay == null) approachOneWay = oneWayByShapeIndex[i]
            if (approachEdgeLanes == null) approachEdgeLanes = edgeLaneCountsByShapeIndex[i]?.takeIf { it > 0 }
            if (approachOneWay != null && approachEdgeLanes != null) break
        }
        if (approachOneWay == null) approachOneWay = oneWayByShapeIndex[pivotIdx]
        if (approachEdgeLanes == null) approachEdgeLanes = edgeLaneCountsByShapeIndex[pivotIdx]?.takeIf { it > 0 }
        if (approachOneWay == null || approachEdgeLanes == null) return false
        if (abs(approachEdgeLanes - maneuver.lanes.size) > 1) return false
        var approachMeters = 0.0
        var i = pivotIdx
        while (i > 0 && approachMeters < 10.0) {
            approachMeters += RouteGeometryMath.haversineMeters(
                shape[i].lat, shape[i].lon, shape[i - 1].lat, shape[i - 1].lon
            )
            i--
        }
        return approachMeters >= 10.0
    }
}
