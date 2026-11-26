package com.example.navigation

import com.example.data.model.UsStateInfo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double)

enum class ManeuverType {
    DEPART,
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    RAMP_RIGHT,
    RAMP_LEFT,
    EXIT_RIGHT,
    EXIT_LEFT,
    STAY_RIGHT,
    STAY_LEFT,
    MERGE,
    ROUNDABOUT,
    FERRY,
    ARRIVE,
    ARRIVE_RIGHT,
    ARRIVE_LEFT
}

enum class LaneDirection {
    SHARP_LEFT,
    LEFT,
    SLIGHT_LEFT,
    THROUGH,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    MERGE_LEFT,
    MERGE_RIGHT
}

enum class LaneHighlightTier {
    /** Best lane(s) for continuing along the active route without extra lane changes (Valhalla `active` bitmask non-zero). */
    ACTIVE,
    /** Allowed lane(s) valid for following the maneuver (Valhalla `valid` bitmask non-zero, `active` zero). */
    VALID,
    /** Other / non-recommended lane(s). */
    INACTIVE
}

enum class LaneSource {
    DIRECT,
    EDGE_APPROACH,
    CARRIED_ALIGNED,
    NONE
}

enum class LaneConfidence {
    HIGH,
    MEDIUM,
    LOW
}

data class LaneInfo(
    val directions: List<LaneDirection>,
    val activeDirections: List<LaneDirection>,
    val isRecommended: Boolean,
    val isActive: Boolean = isRecommended,
    val isValid: Boolean = isRecommended,
    val level: LaneHighlightTier = when {
        isActive -> LaneHighlightTier.ACTIVE
        isValid || isRecommended -> LaneHighlightTier.VALID
        else -> LaneHighlightTier.INACTIVE
    },
    val isUnmarkedSlot: Boolean = false
) {
    val highlightTier: LaneHighlightTier
        get() = level
}

data class HighwaySignpost(
    val exitNumbers: List<String> = emptyList(),
    val exitBranches: List<String> = emptyList(),
    val exitTowards: List<String> = emptyList(),
    val exitNames: List<String> = emptyList()
) {
    val hasContent: Boolean
        get() = exitNumbers.isNotEmpty() || exitBranches.isNotEmpty() || exitTowards.isNotEmpty() || exitNames.isNotEmpty()

    val primaryExitNumber: String? get() = exitNumbers.firstOrNull()
    val primaryRoadNumbers: String get() = exitBranches.joinToString(" / ")
    val primaryDestinations: String get() = (exitTowards + exitNames).distinct().joinToString(" • ")
}

data class RouteManeuver(
    val index: Int,
    val type: ManeuverType,
    val instructionEn: String,
    val streetNames: List<String>,
    val lengthMeters: Double,
    val timeSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val lat: Double,
    val lon: Double,
    val signpost: HighwaySignpost? = null,
    val lanes: List<LaneInfo> = emptyList(),
    val isHighwayExitOrSplit: Boolean = false,
    val isHighwayRoad: Boolean = false,
    val roundaboutExitCount: Int? = null,
    val hasToll: Boolean = false,
    val speedLimitMph: Int? = null,
    val laneCount: Int = lanes.size,
    val isMultiLaneRoad: Boolean = lanes.size >= 2,
    val laneSource: LaneSource = if (lanes.isNotEmpty()) LaneSource.DIRECT else LaneSource.NONE,
    val carriedLaneDistanceMeters: Double = 0.0,
    val carriedLaneHadInterveningIntersection: Boolean = false,
    val rawLaneJson: String = "",
    val hasRoadLaneGeometry: Boolean = true
)

enum class RoadAlertKind {
    SPEED_LIMIT_DROP,
    SHARP_CURVE,
    TOLL_BOOTH,
    RAILROAD_CROSSING,
    SCHOOL_ZONE,
    STOP_SIGN,
    TRAFFIC_SIGNAL,
    SPEED_CAMERA,
    RED_LIGHT_CAMERA,
    SPEED_BUMP,
    STEEP_GRADE,
    USER_POLICE,
    USER_HAZARD,
    USER_CRASH,
    USER_ROAD_CLOSURE,
    USER_SPEED_TRAP,
    USER_CONSTRUCTION,
    USER_OBJECT_ON_ROAD,
    USER_STALLED_VEHICLE
}

data class AheadAlert(
    val id: String,
    val kind: RoadAlertKind,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    val titleEn: String,
    val titleFa: String,
    val detail: String = "",
    val newSpeedLimitMph: Int? = null,
    val curveAngleDegrees: Double = 0.0,
    val userReportId: Long? = null
)

enum class RoutePreference(val code: String) {
    FASTEST("fastest"),
    SHORTEST("shortest")
}

data class OfflineRoute(
    val id: String,
    val summaryLabel: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val shape: List<LatLon>,
    val maneuvers: List<RouteManeuver>,
    val speedLimitsByShapeIndex: Map<Int, Int> = emptyMap(),
    val oneWayByShapeIndex: Map<Int, Boolean> = emptyMap(),
    val edgeLaneCountsByShapeIndex: Map<Int, Int> = emptyMap(),
    val edgeWayIdsByShapeIndex: Map<Int, Long> = emptyMap(),
    val roadAlerts: List<AheadAlert> = emptyList(),
    val crossesStateLines: Boolean = false,
    val statesCrossed: List<String> = emptyList(),
    val hasTolls: Boolean = false,
    val isFastestRoute: Boolean = false,
    val isShortestRoute: Boolean = false,
    val travelMode: TravelMode = TravelMode.DRIVE,
    val hasRoadLaneGeometry: Boolean = true
)

data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val preferShortest: Boolean = false,
    val avoidLocations: List<LatLon> = emptyList(), // Active user-reported road closures
    val travelMode: TravelMode = TravelMode.DRIVE,
    val walkingSpeedKmh: Double = WalkingSpeedPreset.NORMAL.speedKmh,
    val cyclingSpeedKmh: Double = CyclingSpeedPreset.NORMAL.speedKmh,
    val avoidHillsWhenCycling: Boolean = false
) {
    val isPedestrian: Boolean
        get() = travelMode == TravelMode.WALK

    /** Backward-compatible constructor so existing `RouteOptions(isPedestrian = true)` calls work unchanged. */
    constructor(isPedestrian: Boolean) : this(
        travelMode = if (isPedestrian) TravelMode.WALK else TravelMode.DRIVE
    )
}

class MissingStatesException(
    val missingStates: List<UsStateInfo>,
    message: String
) : IllegalStateException(message)

data class ActiveNavigationState(
    val isNavigating: Boolean = false,
    val route: OfflineRoute? = null,
    val destinationName: String = "",
    val destinationAddress: String = "",
    val currentManeuverIndex: Int = 0,
    val distanceToNextManeuverMeters: Double = 0.0,
    val timeToNextManeuverSeconds: Double = 0.0,
    val remainingDistanceMeters: Double = 0.0,
    val remainingTimeSeconds: Double = 0.0,
    val currentSpeedMps: Float = 0f,
    val snappedLocation: LatLon? = null,
    val snappedBearing: Float = 0f,
    val snappedShapeIndex: Int = 0,
    val snappedRouteProgressMeters: Double = 0.0,
    val lastGpsFixAgeMs: Long = 0L,
    val currentSpeedLimitMph: Int? = null, // OSM maxspeed or HPMS fallback — never guessed!
    val speedLimitSource: String = "none", // "osm", "hpms", or "none"
    val upcomingSpeedLimitMph: Int? = null,
    val upcomingSpeedLimitDistanceMeters: Double? = null,
    val isOverspeed: Boolean = false,
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val isGpsSignalLost: Boolean = false,
    val isSimulatingDrive: Boolean = false,
    val hasArrived: Boolean = false,
    val lastSpokenInstruction: String = "",
    val activeAheadAlert: AheadAlert? = null,
    val showLaneStrip: Boolean = false,
    val lanePulseManeuverIndex: Int = -1,
    val lanePulseTriggerCount: Int = 0,
    val laneChangeCueManeuverIndex: Int = -1,
    val laneChangeCuePulseAtMs: Long = 0L,
    val turnFocusActive: Boolean = false,
    val turnFocusCameraActive: Boolean = false,
    val turnFocusIsHighway: Boolean = false,
    val turnFocusManeuverIndex: Int = -1,
    val turnFocusTriggerDistanceMeters: Double = 0.0,
    val turnFocusEnterAnimMs: Int = TurnFocusPlanner.DEFAULT_ENTER_ANIM_MS,
    val turnFocusClosePair: Boolean = false,
    val turnFocusStage: TurnFocusStage = TurnFocusStage.NONE,
    val turnFocusTargetZoom: Double = 0.0,
    val turnFocusFinalSignalDistanceBeforeManeuverMeters: Double? = null,
    val turnFocusPassedFinalSignal: Boolean = false,
    val showJunctionPreview: Boolean = false,
    val activeJunctionManeuverIndex: Int = -1,
    val dismissedJunctionManeuverIndex: Int = -1,
    val laneConfidence: LaneConfidence = LaneConfidence.HIGH,
    val laneConfidenceReasons: List<String> = emptyList(),
    val laneSideHint: LaneTargetSide? = null,
    val laneDecision: LaneGuidanceDecision? = null,
    val travelMode: TravelMode = TravelMode.DRIVE,
    val isWalkingMode: Boolean = (travelMode == TravelMode.WALK)
) {
    val effectiveTravelMode: TravelMode
        get() = if (isWalkingMode) TravelMode.WALK else travelMode
    val currentManeuver: RouteManeuver?
        get() = route?.maneuvers?.getOrNull(currentManeuverIndex)
    val activeTurnFocusManeuver: RouteManeuver?
        get() = if (turnFocusActive && turnFocusManeuverIndex >= 0) {
            route?.maneuvers?.firstOrNull { it.index == turnFocusManeuverIndex }
        } else null
    val displayedManeuver: RouteManeuver?
        get() = activeTurnFocusManeuver ?: currentManeuver
    val displayedManeuverDistanceMeters: Double
        get() {
            val focus = activeTurnFocusManeuver
            val cur = currentManeuver
            if (focus != null && cur != null && focus.index != cur.index) {
                val shape = route?.shape
                if (shape != null && shape.size >= 2) {
                    val pivotIdx = focus.beginShapeIndex.coerceIn(0, shape.lastIndex)
                    val pivotProg = NavigationGeometryHelper.vertexProgressMeters(shape, pivotIdx)
                    val vehicleProg = if (snappedRouteProgressMeters > 0.0) {
                        snappedRouteProgressMeters
                    } else {
                        NavigationGeometryHelper.vertexProgressMeters(shape, snappedShapeIndex.coerceIn(0, shape.lastIndex))
                    }
                    return (pivotProg - vehicleProg).coerceAtLeast(0.0)
                }
                return 0.0
            }
            return distanceToNextManeuverMeters
        }
    val activeJunctionManeuver: RouteManeuver?
        get() = route?.maneuvers?.firstOrNull { it.index == activeJunctionManeuverIndex } ?: displayedManeuver

    /**
     * Secondary "Then ..." maneuver preview shown below the top banner when the subsequent turn
     * comes soon after the immediate maneuver (within 500 m or 45 s).
     */
    val thenManeuver: RouteManeuver?
        get() {
            val cur = currentManeuver ?: return null
            val next = route?.maneuvers?.getOrNull(currentManeuverIndex + 1) ?: return null
            return if (cur.lengthMeters <= 500.0 || cur.timeSeconds <= 45.0) next else null
        }

    val displayedThenManeuver: RouteManeuver?
        get() {
            val active = displayedManeuver ?: return null
            val maneuvers = route?.maneuvers ?: return null
            val listIdx = maneuvers.indexOfFirst { it.index == active.index }
            if (listIdx < 0) return null
            val next = maneuvers.getOrNull(listIdx + 1) ?: return null
            return if (active.lengthMeters <= 500.0 || active.timeSeconds <= 45.0) next else null
        }
}

object SolarNightCalculator {
    fun isNightTime(lat: Double, lon: Double, currentMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = currentMillis }
        val dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val hourLocal = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0

        val declinationRad = Math.toRadians(-23.44) * cos(2.0 * PI * (dayOfYear + 10) / 365.0)
        val latRad = Math.toRadians(lat.coerceIn(-65.0, 65.0))
        val cosH = (-tan(latRad) * tan(declinationRad)).coerceIn(-0.99, 0.99)
        val halfDayHours = Math.toDegrees(kotlin.math.acos(cosH)) / 15.0
        val sunrise = (12.0 - halfDayHours).coerceIn(4.5, 9.0)
        val sunset = (12.0 + halfDayHours).coerceIn(15.5, 21.5)
        return hourLocal < sunrise || hourLocal >= sunset
    }
}
