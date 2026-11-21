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
    U_TURN_RIGHT
}

data class LaneInfo(
    val directions: List<LaneDirection>,
    val activeDirections: List<LaneDirection>,
    val isRecommended: Boolean
)

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
    val speedLimitMph: Int? = null
)

enum class RoadAlertKind {
    SPEED_LIMIT_DROP,
    SHARP_CURVE,
    TOLL_BOOTH,
    RAILROAD_CROSSING,
    SCHOOL_ZONE,
    STOP_SIGN,
    TRAFFIC_SIGNAL,
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
    val roadAlerts: List<AheadAlert> = emptyList(),
    val crossesStateLines: Boolean = false,
    val statesCrossed: List<String> = emptyList(),
    val hasTolls: Boolean = false,
    val isFastestRoute: Boolean = false,
    val isShortestRoute: Boolean = false
)

data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val preferShortest: Boolean = false,
    val avoidLocations: List<LatLon> = emptyList() // Active user-reported road closures
)

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
    val currentSpeedLimitMph: Int? = null, // Strictly null when no OSM maxspeed exists — never guessed!
    val isOverspeed: Boolean = false,
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val isGpsSignalLost: Boolean = false,
    val isSimulatingDrive: Boolean = false,
    val hasArrived: Boolean = false,
    val lastSpokenInstruction: String = "",
    val activeAheadAlert: AheadAlert? = null,
    val showLaneStrip: Boolean = false,
    val showJunctionPreview: Boolean = false,
    val dismissedJunctionManeuverIndex: Int = -1
) {
    val currentManeuver: RouteManeuver?
        get() = route?.maneuvers?.getOrNull(currentManeuverIndex)

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
