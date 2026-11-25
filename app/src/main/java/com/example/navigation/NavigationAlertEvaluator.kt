package com.example.navigation

import com.example.data.local.ReportType
import com.example.data.local.UserReportEntity
import com.example.data.packs.ExtrasMatchingHelper
import com.example.data.packs.FraCrossingRecord
import com.example.data.preferences.UserSettings
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import kotlin.math.sin

/**
 * Evaluates approaching cameras, traffic lights, DB hazards, route alerts, and Waze-style user
 * reports along the active travel vector.
 */
internal object NavigationAlertEvaluator {

    fun evaluateFreeDriveCameraAlert(
        fix: GpsFix,
        cameras: List<RoadHazardPoint>,
        settings: UserSettings,
        spokenFreeDriveCameraIds: MutableSet<String>
    ): Pair<AheadAlert?, AheadAlert?> {
        if (cameras.isEmpty()) return null to null
        if (!settings.alertSpeedCameraEnabled && !settings.alertRedLightCameraEnabled) return null to null

        val isHighway = fix.speedMps >= 20f
        val maxDist = cameraWarningDistanceMeters(isHighway)
        val nearbyIds = mutableSetOf<String>()
        var nearest: AheadAlert? = null

        for (h in cameras) {
            val alertId = stableCameraAlertId(h)
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, h.lat, h.lon)
            if (dist <= 1_200.0) {
                nearbyIds.add(alertId)
            }
            val alert = buildCameraAlertIfAhead(
                fix = fix,
                hazard = h,
                distanceMeters = dist,
                maxDistanceMeters = maxDist,
                settings = settings
            ) ?: continue
            if (nearest == null || alert.distanceMeters < nearest.distanceMeters) {
                nearest = alert
            }
        }

        // Allow a camera to warn again only after the driver has moved > 1.2 km away from it
        spokenFreeDriveCameraIds.retainAll(nearbyIds)

        val toSpeak = if (nearest != null && spokenFreeDriveCameraIds.add(nearest.id)) {
            nearest
        } else {
            null
        }
        return nearest to toSpeak
    }

    fun findAheadTrafficLightMeters(
        fix: GpsFix,
        trafficLights: List<RoadHazardPoint>
    ): Double? {
        if (trafficLights.isEmpty()) return null
        val hasDirection = fix.hasBearing || fix.speedMps >= 1.5f
        var bestDist = Double.MAX_VALUE
        for (sig in trafficLights) {
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, sig.lat, sig.lon)
            if (dist < 10.0 || dist > NavigationSessionController.TRAFFIC_LIGHT_AHEAD_METERS) continue
            if (hasDirection) {
                val bearingToLight = ValhallaResponseParser.bearingDegrees(
                    LatLon(fix.lat, fix.lon),
                    LatLon(sig.lat, sig.lon)
                )
                val angleDiff = ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), bearingToLight)
                if (angleDiff > 35.0) continue
                val lateralMeters = dist * sin(Math.toRadians(angleDiff))
                if (lateralMeters > 18.0) continue
            } else if (dist > 45.0) {
                continue
            }
            if (dist < bestDist) bestDist = dist
        }
        return if (bestDist < Double.MAX_VALUE) bestDist else null
    }

    fun isAlertAllowedForMode(mode: TravelMode, kind: RoadAlertKind): Boolean = when (mode) {
        TravelMode.DRIVE -> true
        TravelMode.WALK -> false
        TravelMode.BIKE -> kind == RoadAlertKind.RAILROAD_CROSSING || kind == RoadAlertKind.STEEP_GRADE
    }

    fun findNearestAheadAlert(
        fix: GpsFix,
        routeAlerts: List<AheadAlert>,
        dbHazards: List<RoadHazardPoint>,
        userReports: List<UserReportEntity>,
        settings: UserSettings,
        isHighway: Boolean = fix.speedMps >= 20f,
        travelMode: TravelMode = TravelMode.DRIVE,
        fraCrossings: List<FraCrossingRecord> = emptyList()
    ): AheadAlert? {
        if (travelMode == TravelMode.WALK) return null
        val candidates = mutableListOf<AheadAlert>()
        val now = System.currentTimeMillis()
        val currentMph = fix.speedMps * 2.23694f
        val cameraMaxDist = cameraWarningDistanceMeters(isHighway)

        // 1. Route-derived alerts (speed drops, sharp curves, tolls, steep grades, FRA crossings)
        for (a in routeAlerts) {
            if (!isAlertAllowedForMode(travelMode, a.kind)) continue
            val enabled = when (a.kind) {
                RoadAlertKind.SPEED_LIMIT_DROP -> settings.alertSpeedDropEnabled
                RoadAlertKind.SHARP_CURVE -> settings.alertSharpCurveEnabled &&
                    (currentMph >= 25f || a.curveAngleDegrees >= 75.0)
                RoadAlertKind.TOLL_BOOTH -> settings.alertTollBoothEnabled
                RoadAlertKind.STEEP_GRADE -> settings.alertSteepGradeEnabled
                RoadAlertKind.RAILROAD_CROSSING -> settings.alertRailroadEnabled
                else -> true
            }
            if (!enabled) continue
            val maxDist = if (a.kind == RoadAlertKind.STEEP_GRADE) 1_750.0 else 750.0
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, a.lat, a.lon)
            if (dist in 25.0..maxDist && NavigationGeometryHelper.isRoughlyAhead(fix, a.lat, a.lon)) {
                val moved = a.copy(distanceMeters = dist)
                val enriched = if (moved.kind == RoadAlertKind.RAILROAD_CROSSING && fraCrossings.isNotEmpty()) {
                    ExtrasMatchingHelper.enrichRailroadAlertWithFra(moved, fraCrossings)
                } else moved
                candidates.add(enriched)
            }
        }

        // 2. Map DB road hazards (cameras, railroad crossings, school zones, toll booths, optional stop signs & signals)
        for (h in dbHazards) {
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, h.lat, h.lon)
            val lowerType = h.type.lowercase()
            if (lowerType == "speed_camera" || lowerType == "red_light_camera") {
                if (travelMode != TravelMode.DRIVE) continue
                buildCameraAlertIfAhead(
                    fix = fix,
                    hazard = h,
                    distanceMeters = dist,
                    maxDistanceMeters = cameraMaxDist,
                    settings = settings
                )?.let { candidates.add(it) }
                continue
            }
            if (dist in 20.0..500.0 && NavigationGeometryHelper.isRoughlyAhead(fix, h.lat, h.lon)) {
                val (kind, titleEn, titleFa) = when (lowerType) {
                    "railroad", "railway_crossing" -> {
                        if (!settings.alertRailroadEnabled) continue
                        Triple(RoadAlertKind.RAILROAD_CROSSING, "Railroad crossing ahead", "جلوتر تقاطع راه‌آهن است")
                    }
                    "school_zone", "school" -> {
                        if (!settings.alertSchoolZoneEnabled) continue
                        Triple(RoadAlertKind.SCHOOL_ZONE, "School zone ahead", "جلوتر منطقه مدرسه است")
                    }
                    "stop_sign", "stop" -> {
                        if (!settings.alertStopSignEnabled) continue
                        Triple(RoadAlertKind.STOP_SIGN, "Stop sign ahead", "جلوتر تابلوی ایست است")
                    }
                    "toll", "toll_booth" -> {
                        if (!settings.alertTollBoothEnabled) continue
                        Triple(RoadAlertKind.TOLL_BOOTH, "Toll booth ahead", "جلوتر عوارضی است")
                    }
                    else -> continue
                }
                if (!isAlertAllowedForMode(travelMode, kind)) continue
                val rawAlert = AheadAlert(
                    id = "db_hazard_${h.id}",
                    kind = kind,
                    lat = h.lat,
                    lon = h.lon,
                    distanceMeters = dist,
                    titleEn = titleEn,
                    titleFa = titleFa,
                    detail = h.label,
                    newSpeedLimitMph = h.speedLimitMph
                )
                candidates.add(
                    if (kind == RoadAlertKind.RAILROAD_CROSSING && fraCrossings.isNotEmpty()) {
                        ExtrasMatchingHelper.enrichRailroadAlertWithFra(rawAlert, fraCrossings)
                    } else rawAlert
                )
            }
        }

        // 3. All 8 Waze-style local user reports (DRIVE mode only)
        if (travelMode == TravelMode.DRIVE) {
            for (r in userReports) {
                if (r.expiresAt <= now) continue
                val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, r.lat, r.lon)
                if (dist in 15.0..700.0 && NavigationGeometryHelper.isRoughlyAhead(fix, r.lat, r.lon)) {
                    if (r.bearing >= 0f && fix.speedMps >= 3f) {
                        val headingDiff = ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), r.bearing.toDouble())
                        if (headingDiff > 95.0) continue
                    }
                    val rType = runCatching { ReportType.valueOf(r.type) }.getOrNull() ?: continue
                    val (kind, titleEn, titleFa) = when (rType) {
                        ReportType.POLICE -> Triple(RoadAlertKind.USER_POLICE, "Police reported ahead", "پلیس جلوتر گزارش شده است")
                        ReportType.HAZARD -> Triple(RoadAlertKind.USER_HAZARD, "Road hazard reported ahead", "خطر در جاده جلوتر گزارش شده است")
                        ReportType.CRASH -> Triple(RoadAlertKind.USER_CRASH, "Crash reported ahead", "تصادف جلوتر گزارش شده است")
                        ReportType.ROAD_CLOSURE -> Triple(RoadAlertKind.USER_ROAD_CLOSURE, "Road closed ahead", "جاده جلوتر مسدود است")
                        ReportType.SPEED_TRAP -> Triple(RoadAlertKind.USER_SPEED_TRAP, "Speed trap reported ahead", "کنترل سرعت جلوتر گزارش شده است")
                        ReportType.CONSTRUCTION -> Triple(RoadAlertKind.USER_CONSTRUCTION, "Construction zone ahead", "عملیات عمرانی جلوتر است")
                        ReportType.OBJECT_ON_ROAD -> Triple(RoadAlertKind.USER_OBJECT_ON_ROAD, "Object on road ahead", "شیء در جاده جلوتر گزارش شده است")
                        ReportType.STALLED_VEHICLE -> Triple(RoadAlertKind.USER_STALLED_VEHICLE, "Stalled vehicle ahead", "خودروی متوقف جلوتر گزارش شده است")
                    }
                    candidates.add(
                        AheadAlert(
                            id = "user_report_${r.id}",
                            kind = kind,
                            lat = r.lat,
                            lon = r.lon,
                            distanceMeters = dist,
                            titleEn = titleEn,
                            titleFa = titleFa,
                            detail = r.note.ifBlank { r.roadName },
                            userReportId = r.id
                        )
                    )
                }
            }
        }

        // Prioritize approaching cameras over generic low-priority signs if both are ahead
        val nearestCamera = candidates
            .filter { it.kind == RoadAlertKind.SPEED_CAMERA || it.kind == RoadAlertKind.RED_LIGHT_CAMERA }
            .minByOrNull { it.distanceMeters }
        return nearestCamera ?: candidates.minByOrNull { it.distanceMeters }
    }

    private fun cameraWarningDistanceMeters(isHighway: Boolean): Double =
        if (isHighway) NavigationSessionController.CAMERA_WARN_HIGHWAY_METERS
        else NavigationSessionController.CAMERA_WARN_CITY_METERS

    private fun stableCameraAlertId(h: RoadHazardPoint): String =
        "cam_${h.osmId?.takeIf { it.isNotBlank() } ?: h.id}"

    private fun buildCameraAlertIfAhead(
        fix: GpsFix,
        hazard: RoadHazardPoint,
        distanceMeters: Double,
        maxDistanceMeters: Double,
        settings: UserSettings
    ): AheadAlert? {
        if (distanceMeters < 10.0 || distanceMeters > maxDistanceMeters) return null
        val (kind, titleEn, titleFa) = when (hazard.type.lowercase()) {
            "speed_camera" -> {
                if (!settings.alertSpeedCameraEnabled) return null
                Triple(RoadAlertKind.SPEED_CAMERA, "Speed camera ahead", "دوربین سرعت در پیش است")
            }
            "red_light_camera" -> {
                if (!settings.alertRedLightCameraEnabled) return null
                Triple(RoadAlertKind.RED_LIGHT_CAMERA, "Red light camera ahead", "دوربین چراغ قرمز در پیش است")
            }
            else -> return null
        }

        if (!isCameraAheadInTravelDirection(fix, hazard, distanceMeters)) return null

        return AheadAlert(
            id = stableCameraAlertId(hazard),
            kind = kind,
            lat = hazard.lat,
            lon = hazard.lon,
            distanceMeters = distanceMeters,
            titleEn = titleEn,
            titleFa = titleFa,
            detail = hazard.label,
            newSpeedLimitMph = hazard.speedLimitMph
        )
    }

    private fun isCameraAheadInTravelDirection(
        fix: GpsFix,
        camera: RoadHazardPoint,
        distanceMeters: Double
    ): Boolean {
        val hasDirection = fix.hasBearing || fix.speedMps >= 2.0f
        if (!hasDirection) return distanceMeters <= 120.0

        val bearingToCamera = ValhallaResponseParser.bearingDegrees(
            LatLon(fix.lat, fix.lon),
            LatLon(camera.lat, camera.lon)
        )
        val angleToCamera = ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), bearingToCamera)
        if (angleToCamera > 45.0) return false

        // Reject cameras on parallel streets / frontage roads (> 35 m lateral offset)
        val lateralOffsetMeters = distanceMeters * sin(Math.toRadians(angleToCamera))
        if (lateralOffsetMeters > 35.0) return false

        // If OSM provides enforcement direction, match direction of travel within 55°
        val camDir = camera.directionDegrees
        if (camDir != null && !camDir.isNaN()) {
            val dirDiff = ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), camDir.toDouble())
            if (dirDiff > 55.0) return false
        }
        return true
    }
}
