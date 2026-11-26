package com.example.navigation

/**
 * Deterministic, map-data-independent simulator scenarios for verifying full-lane guidance
 * behavior on demand without relying on installed or changing OSM map tiles.
 */
object LaneDemoScenarios {

    const val FOUR_LANE_DEMO_ROUTE_ID = "demo_four_lane_second_from_right"

    /**
     * Main acceptance test scenario:
     * - 4 lanes traveling in the driver's direction (physical LTR order).
     * - Only lane 3 from the left (index 2, "Second lane from the right.") follows the route.
     * - Lane 0 (far left): THROUGH (inactive)
     * - Lane 1 (second from left): THROUGH (inactive)
     * - Lane 2 (3rd from left / 2nd from right): SLIGHT_RIGHT (ACTIVE, sole target)
     * - Lane 3 (far right): SHARP_RIGHT (inactive, separate local exit-only lane)
     * - Starts 1.1 km before the split so the full 4-lane diagram appears immediately before
     *   the Turn Focus audio cue ("Get in the blue lane.") and remains visible through the split.
     */
    fun buildFourLaneSecondFromRightRoute(): OfflineRoute {
        // 75 points spaced ~30 m apart heading due north (~2.22 km total) near Eugene, OR
        val approachPoints = (0..45).map { i ->
            LatLon(44.0450 + i * 0.00027, -123.0500)
        }
        val pivot = approachPoints.last()
        val branchPoints = (1..30).map { i ->
            LatLon(pivot.lat + i * 0.00025, pivot.lon + i * 0.00009)
        }
        val shape = approachPoints + branchPoints
        val splitIndex = approachPoints.lastIndex
        val endIndex = shape.lastIndex

        val lanes = listOf(
            LaneInfo(
                directions = listOf(LaneDirection.THROUGH),
                activeDirections = emptyList(),
                isRecommended = false,
                isActive = false,
                isValid = false,
                level = LaneHighlightTier.INACTIVE
            ),
            LaneInfo(
                directions = listOf(LaneDirection.THROUGH),
                activeDirections = emptyList(),
                isRecommended = false,
                isActive = false,
                isValid = false,
                level = LaneHighlightTier.INACTIVE
            ),
            LaneInfo(
                directions = listOf(LaneDirection.SLIGHT_RIGHT),
                activeDirections = listOf(LaneDirection.SLIGHT_RIGHT),
                isRecommended = true,
                isActive = true,
                isValid = true,
                level = LaneHighlightTier.ACTIVE
            ),
            LaneInfo(
                directions = listOf(LaneDirection.SHARP_RIGHT),
                activeDirections = emptyList(),
                isRecommended = false,
                isActive = false,
                isValid = false,
                level = LaneHighlightTier.INACTIVE
            )
        )

        val depart = RouteManeuver(
            index = 0,
            type = ManeuverType.DEPART,
            instructionEn = "Head north on I-5 North",
            streetNames = listOf("I-5 N"),
            lengthMeters = 1350.0,
            timeSeconds = 52.0,
            beginShapeIndex = 0,
            endShapeIndex = splitIndex,
            lat = shape.first().lat,
            lon = shape.first().lon,
            isHighwayRoad = true,
            speedLimitMph = 65,
            laneCount = 4,
            isMultiLaneRoad = true
        )

        val split = RouteManeuver(
            index = 1,
            type = ManeuverType.STAY_RIGHT,
            instructionEn = "Keep right toward I-105 West / Eugene",
            streetNames = listOf("I-105 W"),
            lengthMeters = 870.0,
            timeSeconds = 36.0,
            beginShapeIndex = splitIndex,
            endShapeIndex = endIndex,
            lat = pivot.lat,
            lon = pivot.lon,
            signpost = HighwaySignpost(
                exitNumbers = listOf("194B"),
                exitBranches = listOf("I-105 W"),
                exitTowards = listOf("Eugene")
            ),
            lanes = lanes,
            isHighwayExitOrSplit = true,
            isHighwayRoad = true,
            speedLimitMph = 65,
            laneCount = 4,
            isMultiLaneRoad = true,
            laneSource = LaneSource.DIRECT,
            rawLaneJson = """[{"directions":2},{"directions":2},{"directions":32,"active":32,"valid":32},{"directions":128}]"""
        )

        val arrive = RouteManeuver(
            index = 2,
            type = ManeuverType.ARRIVE,
            instructionEn = "Arrive at I-105 West",
            streetNames = listOf("I-105 W"),
            lengthMeters = 0.0,
            timeSeconds = 0.0,
            beginShapeIndex = endIndex,
            endShapeIndex = endIndex,
            lat = shape.last().lat,
            lon = shape.last().lon
        )

        val oneWay = shape.indices.associateWith { true }
        val edgeLanes = (0..splitIndex).associateWith { 4 } + ((splitIndex + 1)..endIndex).associateWith { 2 }
        val speedLimits = shape.indices.associateWith { 65 }

        return OfflineRoute(
            id = FOUR_LANE_DEMO_ROUTE_ID,
            summaryLabel = "4-Lane Demo (Lane 3 of 4)",
            distanceMeters = 2220.0,
            durationSeconds = 88.0,
            shape = shape,
            maneuvers = listOf(depart, split, arrive),
            speedLimitsByShapeIndex = speedLimits,
            oneWayByShapeIndex = oneWay,
            edgeLaneCountsByShapeIndex = edgeLanes,
            travelMode = TravelMode.DRIVE
        )
    }
}
