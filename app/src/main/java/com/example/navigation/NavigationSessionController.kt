package com.example.navigation

import com.example.data.local.ReportType
import com.example.data.local.UserReportEntity
import com.example.data.preferences.UserSettings
import com.example.data.search.OfflinePlaceSearchEngine
import com.example.data.search.RoadHazardPoint
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import kotlin.math.cos
import kotlin.math.max

/**
 * Pure turn-by-turn navigation session calculator covering Phases 2, 3, and 4:
 *  - Orthogonal segment projection map matching (snap-to-road within 48 m)
 *  - Off-route detection (> 48 m off route for 3 consecutive updates)
 *  - Speed-aware & road-class-aware voice timing:
 *      * Highways: ~2 mi, ~1 mi, ~0.25 mi, and at maneuver
 *      * City streets: ~1,000 ft and just before turn
 *  - Early Sygic-style lane strip visibility (~1 mi highway, ~1,000 ft city) — strictly hidden if no lane data
 *  - Real-geometry enlarged Junction Preview (~0.5 mi before highway exits/splits), respecting Settings & tap-dismiss
 *  - Speed limit tracking (strictly null when OSM maxspeed is absent!) & configurable warning threshold (0 / 5 / 10 mph)
 *  - Road alerts ahead (speed drops, speed-aware sharp curves, tolls, railroad, school zones, optional stop signs/signals)
 *    and all 8 Waze-style user report types (respecting direction of travel)
 *  - Dead-reckoning tunnel mode when GPS signal is lost + Simulate Drive generator for emulator testing
 */
class NavigationSessionController {

    private var offRouteCount = 0
    private var lastRerouteRequestMs = 0L
    private val lastSpokenAlertIds = mutableSetOf<String>()

    fun reset() {
        offRouteCount = 0
        lastRerouteRequestMs = 0L
        lastSpokenAlertIds.clear()
    }

    data class UpdateResult(
        val state: ActiveNavigationState,
        val shouldReroute: Boolean,
        val maneuverToSpeak: Pair<RouteManeuver, String>? = null,
        val alertToSpeak: AheadAlert? = null,
        val triggerOverspeedChime: Boolean = false
    )

    data class SnapProjection(
        val point: LatLon,
        val segmentIndex: Int,
        val distanceMeters: Double,
        val roadBearing: Float
    )

    fun processGpsFix(
        currentState: ActiveNavigationState,
        fix: GpsFix,
        dbHazards: List<RoadHazardPoint>,
        userReports: List<UserReportEntity>,
        unit: DistanceUnit,
        language: AppLanguage,
        speedAudioEnabled: Boolean,
        settings: UserSettings = UserSettings(
            language = language,
            distanceUnit = unit,
            speedWarningAudioEnabled = speedAudioEnabled
        )
    ): UpdateResult {
        val route = currentState.route ?: return UpdateResult(currentState, shouldReroute = false)
        if (route.shape.isEmpty() || route.maneuvers.isEmpty()) {
            return UpdateResult(currentState, shouldReroute = false)
        }

        // 1. Orthogonal projection map matching (snap-to-road)
        val snap = snapToRoute(fix, route.shape)
        val bestIdx = snap.segmentIndex
        val bestDist = snap.distanceMeters

        // 2. Off-route check (> 48 m away for 3 consecutive GPS updates)
        val isOffRouteNow = bestDist > 48.0
        if (isOffRouteNow) {
            offRouteCount++
        } else {
            offRouteCount = 0
        }
        val now = System.currentTimeMillis()
        val shouldReroute = offRouteCount >= 3 && (now - lastRerouteRequestMs > 12_000L)
        if (shouldReroute) {
            lastRerouteRequestMs = now
            offRouteCount = 0
        }

        val snappedLoc = if (!isOffRouteNow) snap.point else LatLon(fix.lat, fix.lon)
        val snappedBearing = if (!isOffRouteNow) snap.roadBearing else fix.bearing

        // 3. Determine upcoming maneuver
        var upcomingManeuverIdx = route.maneuvers.indexOfFirst { it.beginShapeIndex > bestIdx }
        if (upcomingManeuverIdx == -1) {
            upcomingManeuverIdx = route.maneuvers.lastIndex
        }
        val upcomingManeuver = route.maneuvers[upcomingManeuverIdx]

        // 4. Distance & live speed-blended ETA
        val distToNextManeuver = polylineDistanceMeters(route.shape, bestIdx, upcomingManeuver.beginShapeIndex)
            .coerceAtLeast(
                OfflinePlaceSearchEngine.haversineMeters(snappedLoc.lat, snappedLoc.lon, upcomingManeuver.lat, upcomingManeuver.lon)
            )
        val remainingDistance = polylineDistanceMeters(route.shape, bestIdx, route.shape.lastIndex)
        val fractionRemaining = if (route.distanceMeters > 1.0) {
            (remainingDistance / route.distanceMeters).coerceIn(0.0, 1.0)
        } else 0.0
        val baselineRemainingTime = route.durationSeconds * fractionRemaining
        val remainingTime = if (fix.speedMps > 3.5f && remainingDistance > 100.0) {
            val liveEstimate = remainingDistance / fix.speedMps
            max(10.0, baselineRemainingTime * 0.65 + liveEstimate * 0.35)
        } else {
            max(10.0, baselineRemainingTime)
        }
        val timeToNext = if (fix.speedMps > 2.5f) {
            distToNextManeuver / fix.speedMps
        } else {
            upcomingManeuver.timeSeconds * (distToNextManeuver / max(1.0, upcomingManeuver.lengthMeters)).coerceIn(0.0, 1.0)
        }

        // 5. Arrival check
        val destPoint = route.shape.last()
        val distToDest = OfflinePlaceSearchEngine.haversineMeters(snappedLoc.lat, snappedLoc.lon, destPoint.lat, destPoint.lon)
        val hasArrived = distToDest < 35.0 && remainingDistance < 60.0

        // 6. Speed-aware & road-class-aware voice timing
        val isHighway = upcomingManeuver.isHighwayRoad || fix.speedMps >= 20f
        val speedLeadMeters = max(0.0, fix.speedMps * 4.5)
        val stageKey: String? = when {
            hasArrived -> "arrived"
            isHighway -> when {
                distToNextManeuver in (2950.0..3450.0) -> "hw_2mi"
                distToNextManeuver in (1450.0..1780.0) -> "hw_1mi"
                distToNextManeuver in (340.0..490.0) -> "hw_quarter_mi"
                distToNextManeuver <= max(110.0, speedLeadMeters) -> "hw_now"
                else -> null
            }
            else -> when {
                distToNextManeuver in (260.0..365.0) -> "city_1000ft"
                distToNextManeuver <= max(55.0, fix.speedMps * 4.0) -> "city_now"
                else -> null
            }
        }
        val maneuverToSpeak = stageKey?.let { upcomingManeuver to it }

        // 7. Phase 3: Early Sygic-style Lane Strip visibility (~1 mi highway, ~1,000 ft city)
        // Strictly hidden if `upcomingManeuver.lanes` is empty — NEVER invented!
        val laneWindowMeters = if (upcomingManeuver.isHighwayExitOrSplit || isHighway) 1750.0 else 380.0
        val showLaneStrip = !hasArrived &&
            upcomingManeuver.lanes.isNotEmpty() &&
            distToNextManeuver <= laneWindowMeters

        // 8. Phase 3: Enlarged Junction Preview (~0.5 mi / 850 m before highway exits & complex splits)
        val isDismissedForThisManeuver = currentState.dismissedJunctionManeuverIndex == upcomingManeuver.index
        val showJunctionPreview = !hasArrived &&
            settings.junctionPreviewEnabled &&
            !isDismissedForThisManeuver &&
            upcomingManeuver.isHighwayExitOrSplit &&
            distToNextManeuver <= 850.0

        // 9. Phase 3: Speed limit (strictly null if OSM maxspeed is absent — NEVER guessed!)
        val speedLimitMph = route.speedLimitsByShapeIndex[bestIdx]
            ?: upcomingManeuver.speedLimitMph
        val currentMph = fix.speedMps * 2.23694f
        val isOverspeed = speedLimitMph != null && currentMph > (speedLimitMph + settings.speedWarningThresholdMph)

        // 10. Phase 3 & 4: Ahead Alerts (filtered by user's individual alert switches)
        val activeAheadAlert = findNearestAheadAlert(
            fix = fix.copy(lat = snappedLoc.lat, lon = snappedLoc.lon, bearing = snappedBearing),
            routeAlerts = route.roadAlerts,
            dbHazards = dbHazards,
            userReports = userReports,
            settings = settings
        )
        val alertToSpeak = if (activeAheadAlert != null &&
            activeAheadAlert.distanceMeters <= 550.0 &&
            lastSpokenAlertIds.add(activeAheadAlert.id)
        ) {
            activeAheadAlert
        } else null

        val nextState = currentState.copy(
            currentManeuverIndex = upcomingManeuverIdx,
            distanceToNextManeuverMeters = distToNextManeuver,
            timeToNextManeuverSeconds = timeToNext,
            remainingDistanceMeters = remainingDistance,
            remainingTimeSeconds = remainingTime,
            currentSpeedMps = fix.speedMps,
            snappedLocation = snappedLoc,
            snappedBearing = snappedBearing,
            snappedShapeIndex = bestIdx,
            currentSpeedLimitMph = speedLimitMph,
            isOverspeed = isOverspeed,
            isOffRoute = isOffRouteNow,
            isRerouting = shouldReroute || currentState.isRerouting,
            isGpsSignalLost = false,
            hasArrived = hasArrived,
            activeAheadAlert = activeAheadAlert,
            showLaneStrip = showLaneStrip,
            showJunctionPreview = showJunctionPreview
        )

        return UpdateResult(
            state = nextState,
            shouldReroute = shouldReroute,
            maneuverToSpeak = maneuverToSpeak,
            alertToSpeak = alertToSpeak,
            triggerOverspeedChime = isOverspeed && settings.speedWarningAudioEnabled
        )
    }

    /**
     * Dead-reckoning mode when GPS signal drops (e.g. inside a tunnel):
     * advances the snapped marker along the route polyline at the last known speed.
     */
    fun advanceDeadReckoning(
        currentState: ActiveNavigationState,
        elapsedSeconds: Double
    ): ActiveNavigationState {
        val route = currentState.route ?: return currentState.copy(isGpsSignalLost = true)
        if (route.shape.size < 2) return currentState.copy(isGpsSignalLost = true)

        val speedMps = currentState.currentSpeedMps.coerceAtLeast(8f)
        val advanceMeters = speedMps * elapsedSeconds
        val (newIdx, newLoc, newBearing) = advanceAlongShape(
            shape = route.shape,
            startIndex = currentState.snappedShapeIndex,
            distanceMeters = advanceMeters
        )
        val remainingDist = polylineDistanceMeters(route.shape, newIdx, route.shape.lastIndex)
        val upcoming = route.maneuvers.getOrNull(currentState.currentManeuverIndex)
        val distToNext = if (upcoming != null) {
            polylineDistanceMeters(route.shape, newIdx, upcoming.beginShapeIndex)
        } else 0.0

        return currentState.copy(
            snappedLocation = newLoc,
            snappedBearing = newBearing,
            snappedShapeIndex = newIdx,
            remainingDistanceMeters = remainingDist,
            distanceToNextManeuverMeters = distToNext,
            isGpsSignalLost = true
        )
    }

    /**
     * Produces realistic synthetic GPS fixes along the active route for the Developer
     * "Simulate drive" mode, with optional lateral offset to simulate a wrong turn!
     */
    fun computeSimulatedFix(
        currentState: ActiveNavigationState,
        elapsedSeconds: Double,
        wrongTurnOffsetMeters: Double = 0.0
    ): GpsFix? {
        val route = currentState.route ?: return null
        if (route.shape.size < 2) return null

        val upcoming = currentState.currentManeuver
        val targetSpeedMps = when {
            upcoming?.speedLimitMph != null -> (upcoming.speedLimitMph * 0.44704f).coerceIn(10f, 32f)
            upcoming?.isHighwayRoad == true -> 29.0f // ~65 mph
            else -> 15.6f // ~35 mph city street
        }

        val (nextIdx, baseLoc, bearing) = advanceAlongShape(
            shape = route.shape,
            startIndex = currentState.snappedShapeIndex,
            distanceMeters = targetSpeedMps * elapsedSeconds
        )

        val finalLoc = if (wrongTurnOffsetMeters > 0.0) {
            // Shift perpendicularly east/south off the road by wrongTurnOffsetMeters
            val dLat = (wrongTurnOffsetMeters / 111_320.0)
            val dLon = (wrongTurnOffsetMeters / (111_320.0 * cos(Math.toRadians(baseLoc.lat)).coerceAtLeast(0.1)))
            LatLon(baseLoc.lat + dLat * 0.6, baseLoc.lon + dLon * 0.8)
        } else {
            baseLoc
        }

        return GpsFix(
            lat = finalLoc.lat,
            lon = finalLoc.lon,
            bearing = bearing,
            speedMps = targetSpeedMps,
            accuracyMeters = 4f,
            timestampMs = System.currentTimeMillis()
        )
    }

    fun snapToRoute(fix: GpsFix, shape: List<LatLon>): SnapProjection {
        if (shape.size == 1) {
            val pt = shape[0]
            return SnapProjection(
                point = pt,
                segmentIndex = 0,
                distanceMeters = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, pt.lat, pt.lon),
                roadBearing = fix.bearing
            )
        }
        val cosLat = cos(Math.toRadians(fix.lat)).coerceAtLeast(0.1)
        var bestDist = Double.MAX_VALUE
        var bestPoint = shape[0]
        var bestIdx = 0
        var bestBearing = fix.bearing

        for (i in 0 until shape.lastIndex) {
            val a = shape[i]
            val b = shape[i + 1]
            val ax = a.lon * cosLat
            val ay = a.lat
            val bx = b.lon * cosLat
            val by = b.lat
            val px = fix.lon * cosLat
            val py = fix.lat

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq > 1e-15) {
                (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
            } else 0.0

            val projLat = a.lat + t * (b.lat - a.lat)
            val projLon = a.lon + t * (b.lon - a.lon)
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, projLat, projLon)
            if (dist < bestDist) {
                bestDist = dist
                bestPoint = LatLon(projLat, projLon)
                bestIdx = i
                bestBearing = ValhallaResponseParser.bearingDegrees(a, b).toFloat()
            }
        }
        return SnapProjection(bestPoint, bestIdx, bestDist, bestBearing)
    }

    private fun advanceAlongShape(
        shape: List<LatLon>,
        startIndex: Int,
        distanceMeters: Double
    ): Triple<Int, LatLon, Float> {
        var idx = startIndex.coerceIn(0, shape.lastIndex)
        var remaining = distanceMeters
        while (idx < shape.lastIndex && remaining > 0.0) {
            val a = shape[idx]
            val b = shape[idx + 1]
            val segLen = OfflinePlaceSearchEngine.haversineMeters(a.lat, a.lon, b.lat, b.lon)
            if (segLen <= 0.1) {
                idx++
                continue
            }
            if (remaining < segLen) {
                val frac = remaining / segLen
                val lat = a.lat + (b.lat - a.lat) * frac
                val lon = a.lon + (b.lon - a.lon) * frac
                val bearing = ValhallaResponseParser.bearingDegrees(a, b).toFloat()
                return Triple(idx, LatLon(lat, lon), bearing)
            }
            remaining -= segLen
            idx++
        }
        val last = shape.last()
        val prev = shape.getOrElse(shape.lastIndex - 1) { last }
        return Triple(shape.lastIndex, last, ValhallaResponseParser.bearingDegrees(prev, last).toFloat())
    }

    private fun findNearestAheadAlert(
        fix: GpsFix,
        routeAlerts: List<AheadAlert>,
        dbHazards: List<RoadHazardPoint>,
        userReports: List<UserReportEntity>,
        settings: UserSettings
    ): AheadAlert? {
        val candidates = mutableListOf<AheadAlert>()
        val now = System.currentTimeMillis()
        val currentMph = fix.speedMps * 2.23694f

        // 1. Route-derived alerts (speed drops, speed-aware sharp curves, toll booths)
        for (a in routeAlerts) {
            val enabled = when (a.kind) {
                RoadAlertKind.SPEED_LIMIT_DROP -> settings.alertSpeedDropEnabled
                RoadAlertKind.SHARP_CURVE -> settings.alertSharpCurveEnabled &&
                    (currentMph >= 25f || a.curveAngleDegrees >= 75.0)
                RoadAlertKind.TOLL_BOOTH -> settings.alertTollBoothEnabled
                else -> true
            }
            if (!enabled) continue
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, a.lat, a.lon)
            if (dist in 25.0..750.0 && isRoughlyAhead(fix, a.lat, a.lon)) {
                candidates.add(a.copy(distanceMeters = dist))
            }
        }

        // 2. Map DB road hazards (railroad crossings, school zones, toll booths, optional stop signs & signals)
        for (h in dbHazards) {
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, h.lat, h.lon)
            if (dist in 20.0..500.0 && isRoughlyAhead(fix, h.lat, h.lon)) {
                val (kind, titleEn, titleFa) = when (h.type.lowercase()) {
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
                    "traffic_signal", "traffic_signals" -> {
                        if (!settings.alertStopSignEnabled) continue
                        Triple(RoadAlertKind.TRAFFIC_SIGNAL, "Traffic signal ahead", "جلوتر چراغ راهنمایی است")
                    }
                    "toll", "toll_booth" -> {
                        if (!settings.alertTollBoothEnabled) continue
                        Triple(RoadAlertKind.TOLL_BOOTH, "Toll booth ahead", "جلوتر عوارضی است")
                    }
                    else -> continue
                }
                candidates.add(
                    AheadAlert(
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
                )
            }
        }

        // 3. All 8 Waze-style local user reports (respecting travel direction when report has bearing)
        for (r in userReports) {
            if (r.expiresAt <= now) continue
            val dist = OfflinePlaceSearchEngine.haversineMeters(fix.lat, fix.lon, r.lat, r.lon)
            if (dist in 15.0..700.0 && isRoughlyAhead(fix, r.lat, r.lon)) {
                // If report recorded a direction of travel, match within 95 degrees
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

        return candidates.minByOrNull { it.distanceMeters }
    }

    private fun isRoughlyAhead(fix: GpsFix, targetLat: Double, targetLon: Double): Boolean {
        if (fix.speedMps < 3.0f) return true
        val bearingToTarget = ValhallaResponseParser.bearingDegrees(
            LatLon(fix.lat, fix.lon),
            LatLon(targetLat, targetLon)
        )
        return ValhallaResponseParser.angleDiffDegrees(fix.bearing.toDouble(), bearingToTarget) <= 75.0
    }

    private fun polylineDistanceMeters(shape: List<LatLon>, fromIdx: Int, toIdx: Int): Double {
        if (shape.size < 2 || fromIdx >= toIdx) return 0.0
        val start = fromIdx.coerceIn(0, shape.lastIndex)
        val end = toIdx.coerceIn(start, shape.lastIndex)
        var sum = 0.0
        for (i in start until end) {
            val a = shape[i]
            val b = shape[i + 1]
            sum += OfflinePlaceSearchEngine.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        }
        return sum
    }
}
