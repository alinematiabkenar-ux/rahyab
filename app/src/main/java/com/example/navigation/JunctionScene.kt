package com.example.navigation

/**
 * J1: Pure Kotlin data model and builder for Sygic-style highway junction views.
 * Zero Android dependencies so it is fast and directly unit-testable on the JVM.
 */
enum class JunctionSceneType {
    EXIT_RIGHT,
    EXIT_LEFT,
    FORK_LEFT,
    FORK_RIGHT,
    SPLIT_THREE_WAY,
    LANE_DROP
}

enum class JunctionSide {
    LEFT,
    RIGHT,
    CENTER
}

enum class RouteShieldKind {
    INTERSTATE,
    US,
    STATE
}

data class RouteShield(
    val kind: RouteShieldKind,
    val text: String
)

data class JunctionScene(
    val type: JunctionSceneType,
    val side: JunctionSide,
    val totalLanes: Int,
    val mainLaneCount: Int,
    val continuingLaneIndices: List<Int>,
    val exitLaneIndices: List<Int>,
    val recommendedLaneIndices: List<Int>,
    val exitNumber: String?,
    val shields: List<RouteShield>,
    val destinationLines: List<String>,
    val hasRealLaneData: Boolean
)

object JunctionSceneBuilder {

    private val HIGHWAY_JUNCTION_TYPES = setOf(
        ManeuverType.EXIT_RIGHT,
        ManeuverType.EXIT_LEFT,
        ManeuverType.RAMP_RIGHT,
        ManeuverType.RAMP_LEFT,
        ManeuverType.STAY_RIGHT,
        ManeuverType.STAY_LEFT,
        ManeuverType.MERGE
    )

    private val INTERSTATE_REGEX = Regex("""(?i)\bI[-\s]?(\d+[A-Z]?)\b""")
    private val US_ROUTE_REGEX = Regex("""(?i)\bUS[-\s]?(\d+[A-Z]?)\b""")
    private val STATE_ROUTE_REGEX = Regex("""(?i)\b([A-Z]{2}|SR|SH|HWY)[-\s]?(\d+[A-Z]?)\b""")

    fun isHighwayJunctionManeuver(maneuver: RouteManeuver): Boolean {
        if (!maneuver.isHighwayExitOrSplit && !maneuver.isHighwayRoad) return false
        if (maneuver.isHighwayExitOrSplit) return true
        return maneuver.type in HIGHWAY_JUNCTION_TYPES
    }

    /**
     * Resolves the highway maneuver whose junction view should be shown:
     * visible from 800 m (0.5 mi) before the maneuver until 50 m after passing it,
     * immediately hidden when off-route, dismissed for that maneuver, or outside DRIVE mode.
     */
    fun resolveActiveJunctionManeuver(
        route: OfflineRoute,
        upcomingManeuverIdx: Int,
        distToUpcomingMeters: Double,
        snappedLoc: LatLon,
        isDrive: Boolean,
        hasArrived: Boolean,
        isOffRoute: Boolean,
        junctionEnabled: Boolean,
        dismissedManeuverIndex: Int
    ): RouteManeuver? {
        if (!isDrive || hasArrived || isOffRoute || !junctionEnabled) return null
        val upcoming = route.maneuvers.getOrNull(upcomingManeuverIdx)
        if (upcoming != null &&
            dismissedManeuverIndex != upcoming.index &&
            distToUpcomingMeters <= 800.0 &&
            isHighwayJunctionManeuver(upcoming)
        ) {
            return upcoming
        }
        val prev = route.maneuvers.getOrNull(upcomingManeuverIdx - 1)
        if (prev != null &&
            dismissedManeuverIndex != prev.index &&
            isHighwayJunctionManeuver(prev)
        ) {
            val distAfter = com.example.data.search.OfflinePlaceSearchEngine.haversineMeters(
                snappedLoc.lat, snappedLoc.lon, prev.lat, prev.lon
            )
            if (distAfter <= 50.0) {
                return prev
            }
        }
        return null
    }

    fun build(maneuver: RouteManeuver): JunctionScene? {
        if (!isHighwayJunctionManeuver(maneuver)) return null

        val lanes = maneuver.lanes
        val (type, side) = resolveTypeAndSide(maneuver, lanes)

        val exitNumber = maneuver.signpost?.primaryExitNumber?.trim()?.takeIf { it.isNotBlank() }
        val shields = parseRouteShields(
            branches = maneuver.signpost?.exitBranches.orEmpty(),
            streetNames = maneuver.streetNames
        )
        val destinationLines = buildDestinationLines(maneuver)

        if (lanes.isEmpty()) {
            return buildGenericScene(type, side, exitNumber, shields, destinationLines)
        }

        val totalLanes = lanes.size.coerceIn(2, 7)
        val recommended = lanes.mapIndexedNotNull { idx, lane ->
            if (lane.highlightTier == LaneHighlightTier.ACTIVE) idx else null
        }.ifEmpty {
            lanes.mapIndexedNotNull { idx, lane ->
                if (lane.isRecommended || lane.isValid) idx else null
            }
        }

        val (continuing, branch) = partitionLanes(type, side, lanes, recommended, totalLanes)
        val effectiveRecommended = recommended.ifEmpty {
            if (type == JunctionSceneType.LANE_DROP) continuing else branch
        }.filter { it in 0 until totalLanes }

        val mainLaneCount = continuing.size.coerceAtLeast(1)

        return JunctionScene(
            type = type,
            side = side,
            totalLanes = totalLanes,
            mainLaneCount = mainLaneCount,
            continuingLaneIndices = continuing,
            exitLaneIndices = branch,
            recommendedLaneIndices = effectiveRecommended,
            exitNumber = exitNumber,
            shields = shields,
            destinationLines = destinationLines,
            hasRealLaneData = true
        )
    }

    private fun resolveTypeAndSide(
        maneuver: RouteManeuver,
        lanes: List<LaneInfo>
    ): Pair<JunctionSceneType, JunctionSide> {
        // Detect 3-way split when lanes simultaneously diverge left, through, and right
        if (lanes.size >= 3 && (maneuver.type == ManeuverType.STAY_LEFT || maneuver.type == ManeuverType.STAY_RIGHT)) {
            val hasLeft = lanes.any { l ->
                l.directions.any { it == LaneDirection.SLIGHT_LEFT || it == LaneDirection.LEFT }
            }
            val hasThrough = lanes.any { l -> l.directions.any { it == LaneDirection.THROUGH } }
            val hasRight = lanes.any { l ->
                l.directions.any { it == LaneDirection.SLIGHT_RIGHT || it == LaneDirection.RIGHT }
            }
            if (hasLeft && hasThrough && hasRight) {
                val side = if (maneuver.type == ManeuverType.STAY_LEFT) JunctionSide.LEFT else JunctionSide.RIGHT
                return JunctionSceneType.SPLIT_THREE_WAY to side
            }
        }

        return when (maneuver.type) {
            ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT ->
                JunctionSceneType.EXIT_RIGHT to JunctionSide.RIGHT
            ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT ->
                JunctionSceneType.EXIT_LEFT to JunctionSide.LEFT
            ManeuverType.STAY_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT ->
                JunctionSceneType.FORK_LEFT to JunctionSide.LEFT
            ManeuverType.STAY_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.RIGHT ->
                JunctionSceneType.FORK_RIGHT to JunctionSide.RIGHT
            ManeuverType.MERGE -> {
                val mergeLeft = lanes.any { l -> l.directions.any { it == LaneDirection.MERGE_LEFT } } ||
                    maneuver.instructionEn.contains("left", ignoreCase = true)
                JunctionSceneType.LANE_DROP to (if (mergeLeft) JunctionSide.LEFT else JunctionSide.RIGHT)
            }
            else -> JunctionSceneType.EXIT_RIGHT to JunctionSide.RIGHT
        }
    }

    private fun partitionLanes(
        type: JunctionSceneType,
        side: JunctionSide,
        lanes: List<LaneInfo>,
        recommended: List<Int>,
        totalLanes: Int
    ): Pair<List<Int>, List<Int>> {
        val allIndices = (0 until totalLanes).toList()

        if (lanes.size == 1) {
            return if (side == JunctionSide.LEFT) listOf(1) to listOf(0) else listOf(0) to listOf(1)
        }

        val rightDirections = setOf(LaneDirection.SLIGHT_RIGHT, LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT)
        val leftDirections = setOf(LaneDirection.SLIGHT_LEFT, LaneDirection.LEFT, LaneDirection.SHARP_LEFT)

        val rawBranch = when (type) {
            JunctionSceneType.EXIT_RIGHT, JunctionSceneType.FORK_RIGHT -> {
                val byDir = lanes.mapIndexedNotNull { idx, lane ->
                    if (lane.directions.any { it in rightDirections }) idx else null
                }
                byDir.ifEmpty {
                    recommended.filter { it >= totalLanes / 2 }.ifEmpty { listOf(totalLanes - 1) }
                }
            }
            JunctionSceneType.EXIT_LEFT, JunctionSceneType.FORK_LEFT -> {
                val byDir = lanes.mapIndexedNotNull { idx, lane ->
                    if (lane.directions.any { it in leftDirections }) idx else null
                }
                byDir.ifEmpty {
                    recommended.filter { it < (totalLanes + 1) / 2 }.ifEmpty { listOf(0) }
                }
            }
            JunctionSceneType.SPLIT_THREE_WAY -> {
                if (side == JunctionSide.LEFT) listOf(0) else listOf(totalLanes - 1)
            }
            JunctionSceneType.LANE_DROP -> {
                if (side == JunctionSide.LEFT) listOf(0) else listOf(totalLanes - 1)
            }
        }.filter { it in 0 until totalLanes }.distinct().sorted()

        val rawContinuing = allIndices.filterNot { it in rawBranch }
        return if (rawContinuing.isEmpty()) {
            // If all lanes have branch arrows (e.g. mandatory exit), keep at least 1 continuing lane visual
            val cont = if (side == JunctionSide.LEFT) listOf(totalLanes - 1) else listOf(0)
            cont to allIndices.filterNot { it in cont }.ifEmpty { listOf(if (side == JunctionSide.LEFT) 0 else totalLanes - 1) }
        } else {
            rawContinuing to rawBranch
        }
    }

    private fun buildGenericScene(
        type: JunctionSceneType,
        side: JunctionSide,
        exitNumber: String?,
        shields: List<RouteShield>,
        destinationLines: List<String>
    ): JunctionScene {
        return when (type) {
            JunctionSceneType.EXIT_RIGHT -> JunctionScene(
                type = type,
                side = JunctionSide.RIGHT,
                totalLanes = 4,
                mainLaneCount = 3,
                continuingLaneIndices = listOf(0, 1, 2),
                exitLaneIndices = listOf(3),
                recommendedLaneIndices = listOf(3),
                exitNumber = exitNumber,
                shields = shields,
                destinationLines = destinationLines,
                hasRealLaneData = false
            )
            JunctionSceneType.EXIT_LEFT -> JunctionScene(
                type = type,
                side = JunctionSide.LEFT,
                totalLanes = 4,
                mainLaneCount = 3,
                continuingLaneIndices = listOf(1, 2, 3),
                exitLaneIndices = listOf(0),
                recommendedLaneIndices = listOf(0),
                exitNumber = exitNumber,
                shields = shields,
                destinationLines = destinationLines,
                hasRealLaneData = false
            )
            JunctionSceneType.FORK_RIGHT -> JunctionScene(
                type = type,
                side = JunctionSide.RIGHT,
                totalLanes = 4,
                mainLaneCount = 2,
                continuingLaneIndices = listOf(0, 1),
                exitLaneIndices = listOf(2, 3),
                recommendedLaneIndices = listOf(2, 3),
                exitNumber = exitNumber,
                shields = shields,
                destinationLines = destinationLines,
                hasRealLaneData = false
            )
            JunctionSceneType.FORK_LEFT -> JunctionScene(
                type = type,
                side = JunctionSide.LEFT,
                totalLanes = 4,
                mainLaneCount = 2,
                continuingLaneIndices = listOf(2, 3),
                exitLaneIndices = listOf(0, 1),
                recommendedLaneIndices = listOf(0, 1),
                exitNumber = exitNumber,
                shields = shields,
                destinationLines = destinationLines,
                hasRealLaneData = false
            )
            JunctionSceneType.SPLIT_THREE_WAY -> JunctionScene(
                type = type,
                side = side,
                totalLanes = 3,
                mainLaneCount = 2,
                continuingLaneIndices = listOf(1),
                exitLaneIndices = if (side == JunctionSide.LEFT) listOf(0) else listOf(2),
                recommendedLaneIndices = if (side == JunctionSide.LEFT) listOf(0) else listOf(2),
                exitNumber = exitNumber,
                shields = shields,
                destinationLines = destinationLines,
                hasRealLaneData = false
            )
            JunctionSceneType.LANE_DROP -> JunctionScene(
                type = type,
                side = side,
                totalLanes = 3,
                mainLaneCount = 2,
                continuingLaneIndices = if (side == JunctionSide.LEFT) listOf(1, 2) else listOf(0, 1),
                exitLaneIndices = if (side == JunctionSide.LEFT) listOf(0) else listOf(2),
                recommendedLaneIndices = if (side == JunctionSide.LEFT) listOf(1, 2) else listOf(0, 1),
                exitNumber = exitNumber,
                shields = shields,
                destinationLines = destinationLines,
                hasRealLaneData = false
            )
        }
    }

    private fun buildDestinationLines(maneuver: RouteManeuver): List<String> {
        val sp = maneuver.signpost
        val fromSignpost = if (sp != null) {
            (sp.exitTowards + sp.exitNames)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)
        } else {
            emptyList()
        }
        if (fromSignpost.isNotEmpty()) return fromSignpost
        return maneuver.streetNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
    }

    fun parseRouteShields(
        branches: List<String>,
        streetNames: List<String> = emptyList()
    ): List<RouteShield> {
        val sources = branches.ifEmpty { streetNames }
        val result = mutableListOf<RouteShield>()
        val seen = mutableSetOf<String>()

        for (raw in sources) {
            val tokens = raw.split('/', ';', ',').map { it.trim() }.filter { it.isNotBlank() }
            for (token in tokens) {
                val shield = parseSingleShield(token) ?: continue
                val key = "${shield.kind}:${shield.text}"
                if (seen.add(key)) {
                    result.add(shield)
                    if (result.size >= 3) return result
                }
            }
        }
        return result
    }

    private fun parseSingleShield(token: String): RouteShield? {
        INTERSTATE_REGEX.find(token)?.let { m ->
            return RouteShield(RouteShieldKind.INTERSTATE, "I-${m.groupValues[1].uppercase()}")
        }
        US_ROUTE_REGEX.find(token)?.let { m ->
            return RouteShield(RouteShieldKind.US, "US-${m.groupValues[1].uppercase()}")
        }
        STATE_ROUTE_REGEX.find(token)?.let { m ->
            val prefix = m.groupValues[1].uppercase()
            val num = m.groupValues[2].uppercase()
            if (prefix != "US") {
                return RouteShield(RouteShieldKind.STATE, "$prefix-$num")
            }
        }
        return null
    }
}
