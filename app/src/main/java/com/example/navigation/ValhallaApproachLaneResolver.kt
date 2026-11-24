package com.example.navigation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves direct and approach-segment lane information from Valhalla maneuver, banner instruction,
 * edge, and intersection JSON structures.
 */
internal object ValhallaApproachLaneResolver {

    const val MAX_APPROACH_LANE_LOOKBACK_METERS = 1750.0

    fun isTurnOrExitManeuver(type: ManeuverType): Boolean = type in setOf(
        ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT,
        ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT,
        ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT,
        ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT,
        ManeuverType.STAY_LEFT, ManeuverType.STAY_RIGHT,
        ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT
    )

    fun parseManeuverDirectLanes(maneuverObj: JSONObject, type: ManeuverType): List<LaneInfo> {
        val direct = ValhallaTurnLaneDecoder.parseTurnLanes(maneuverObj, type)
        if (direct.isNotEmpty()) return direct

        val bannerLanes = parseBannerInstructionLanes(maneuverObj, type)
        if (bannerLanes.isNotEmpty()) return bannerLanes

        val intersectionLanes = parseIntersectionLanes(maneuverObj.optJSONArray("intersections"), type)
        if (intersectionLanes.isNotEmpty()) return intersectionLanes

        return emptyList()
    }

    fun parseApproachSegmentLanes(maneuverObj: JSONObject): List<LaneInfo> {
        val approachArr = maneuverObj.optJSONArray("approach_lanes")
            ?: maneuverObj.optJSONArray("preceding_turn_lanes")
            ?: maneuverObj.optJSONArray("approach_turn_lanes")
        if (approachArr != null) {
            val parsed = ValhallaTurnLaneDecoder.parseLaneJsonArray(approachArr, maneuverType = null)
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

    fun findApproachingEdgeLanes(
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
                val distToTurn = ValhallaPolylineAndGeo.shapeDistanceBetween(
                    combinedShape, edgeEndIdx, maneuverBeginIdx
                )
                if (distToTurn > MAX_APPROACH_LANE_LOOKBACK_METERS) continue
                val lanes = ValhallaTurnLaneDecoder.parseTurnLanes(edge, maneuverType)
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
                val distToTurn = ValhallaPolylineAndGeo.shapeDistanceBetween(
                    combinedShape, interShapeIdx, maneuverBeginIdx
                )
                if (distToTurn > MAX_APPROACH_LANE_LOOKBACK_METERS) continue
                val rawLanes = inter.optJSONArray("lanes") ?: inter.optJSONArray("turn_lanes") ?: continue
                val parsed = ValhallaTurnLaneDecoder.parseLaneJsonArray(rawLanes, maneuverType)
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

    private fun parseBannerInstructionLanes(
        maneuverObj: JSONObject,
        maneuverType: ManeuverType?
    ): List<LaneInfo> {
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
                    val parsed = ValhallaTurnLaneDecoder.parseLaneJsonArray(laneObjs, maneuverType)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
        return emptyList()
    }

    private fun parseIntersectionLanes(
        intersectionsArr: JSONArray?,
        maneuverType: ManeuverType?
    ): List<LaneInfo> {
        if (intersectionsArr == null || intersectionsArr.length() == 0) return emptyList()
        for (i in intersectionsArr.length() - 1 downTo 0) {
            val inter = intersectionsArr.optJSONObject(i) ?: continue
            val rawLanes = inter.optJSONArray("lanes") ?: inter.optJSONArray("turn_lanes") ?: continue
            val parsed = ValhallaTurnLaneDecoder.parseLaneJsonArray(rawLanes, maneuverType)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /**
     * When lane arrows come from an approaching road segment before the turn, refine active
     * directions for combined-arrow lanes so only the arrow(s) needed for [maneuverType] are
     * highlighted (never inventing new lanes).
     */
    fun alignApproachLanesWithManeuver(lanes: List<LaneInfo>, maneuverType: ManeuverType): List<LaneInfo> {
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

    fun preferredDirectionsForManeuver(type: ManeuverType): Set<LaneDirection> = when (type) {
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
}
