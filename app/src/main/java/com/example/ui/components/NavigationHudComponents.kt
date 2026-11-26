package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ReportType
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.AheadAlert
import com.example.navigation.HighwaySignpost
import com.example.navigation.LatLon
import com.example.navigation.ManeuverType
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
import com.example.navigation.ValhallaResponseParser
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertPoliceBlue
import com.example.ui.theme.AlertRed
import com.example.ui.theme.CockpitMidnight
import com.example.ui.theme.CockpitSurfaceDark
import com.example.ui.theme.CyanBeacon
import com.example.ui.theme.HighwaySignBorder
import com.example.ui.theme.HighwaySignGreen
import com.example.ui.theme.HighwaySignGreenDark
import com.example.ui.theme.JetBrainsMonoFamily
import kotlin.math.cos
import kotlin.math.sin

fun maneuverIconFor(type: ManeuverType): ImageVector = when (type) {
    ManeuverType.LEFT -> Icons.Filled.TurnLeft
    ManeuverType.SLIGHT_LEFT, ManeuverType.STAY_LEFT, ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT -> Icons.Filled.TurnSlightLeft
    ManeuverType.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    ManeuverType.RIGHT -> Icons.Filled.TurnRight
    ManeuverType.SLIGHT_RIGHT, ManeuverType.STAY_RIGHT, ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT -> Icons.Filled.TurnSlightRight
    ManeuverType.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> Icons.Filled.UTurnLeft
    ManeuverType.ROUNDABOUT -> Icons.Filled.RoundaboutRight
    ManeuverType.MERGE -> Icons.AutoMirrored.Filled.MergeType
    ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> Icons.Filled.Flag
    else -> Icons.Filled.ArrowUpward
}

/**
 * Top turn-by-turn banner with authentic US highway green signpost pill, integrated Waze/Apple Maps-style
 * lane guidance row directly inside the banner (strictly hidden when no real OSM/Valhalla lane data exists),
 * secondary "Then ..." strip, and tap-to-close real-geometry enlarged junction preview.
 */
@Composable
fun TurnByTurnTopHud(
    maneuver: RouteManeuver,
    thenManeuver: RouteManeuver?,
    distanceMeters: Double,
    unit: DistanceUnit,
    isRerouting: Boolean,
    showLaneStrip: Boolean,
    showJunctionPreview: Boolean,
    routeShape: List<LatLon>,
    onDismissJunctionPreview: () -> Unit,
    modifier: Modifier = Modifier,
    isNightMode: Boolean = false,
    lanePulseTriggerKey: Int = 0,
    laneChangeCuePulseKey: Long = 0L,
    junctionManeuver: RouteManeuver = maneuver,
    laneConfidence: com.example.navigation.LaneConfidence = com.example.navigation.LaneConfidence.HIGH,
    laneSideHint: com.example.navigation.LaneTargetSide? = null,
    laneDecision: com.example.navigation.LaneGuidanceDecision? = null,
    laneHighlightColor: com.example.map.LaneHighlightColor = com.example.map.LaneHighlightColor.BLUE,
    showReportWrongLaneButton: Boolean = false,
    onReportWrongLane: () -> Unit = {}
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val distText = BilingualFormatter.formatDistance(distanceMeters, unit, language)
    val signpost = maneuver.signpost
    val useHighwayGreen = signpost != null || maneuver.isHighwayExitOrSplit
    val junctionScene = androidx.compose.runtime.remember(junctionManeuver, showJunctionPreview) {
        if (showJunctionPreview) com.example.navigation.JunctionSceneBuilder.build(junctionManeuver) else null
    }
    val showingJunctionCard = showJunctionPreview && junctionScene != null

    val bannerBg = when {
        useHighwayGreen && isNightMode -> Color(0xFF004B3B)
        useHighwayGreen -> HighwaySignGreen
        isNightMode -> CockpitMidnight
        else -> Color(0xFF0D1B3E)
    }
    val bannerBorder = when {
        useHighwayGreen && isNightMode -> HighwaySignBorder.copy(alpha = 0.85f)
        useHighwayGreen -> HighwaySignBorder
        isNightMode -> CyanBeacon.copy(alpha = 0.55f)
        else -> CyanBeacon.copy(alpha = 0.65f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("turn_by_turn_hud"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = bannerBg,
            border = BorderStroke(
                width = if (useHighwayGreen) 2.dp else 1.25.dp,
                color = bannerBorder
            ),
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (signpost != null && signpost.hasContent) {
                    HighwaySignpostHeader(signpost = signpost)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = maneuverIconFor(maneuver.type),
                            contentDescription = maneuver.instructionEn,
                            tint = if (useHighwayGreen) Color.White else CyanBeacon,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isRerouting) strings.rerouting else distText,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = if (language == AppLanguage.PERSIAN) MaterialTheme.typography.headlineMedium.fontFamily else JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            maxLines = 1
                        )
                        val primaryRoad = signpost?.primaryDestinations?.takeIf { it.isNotBlank() }
                            ?: maneuver.streetNames.joinToString(" / ").ifBlank { maneuver.instructionEn }
                        Text(
                            text = primaryRoad,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (showReportWrongLaneButton) {
                        ReportWrongLaneFlagButton(onClick = onReportWrongLane)
                    }
                }

                // Full lane diagram overlay driven by shared Valhalla LaneGuidanceDecision
                // Stays visible even when Junction View is active (adapting height via compact mode)
                if (showLaneStrip && maneuver.lanes.isNotEmpty()) {
                    val effectiveDecision = androidx.compose.runtime.remember(maneuver, laneDecision, laneConfidence, laneSideHint) {
                        val base = laneDecision ?: com.example.navigation.LaneGuidanceDecision.evaluate(maneuver)
                        if (laneConfidence == com.example.navigation.LaneConfidence.LOW && base.confidence != com.example.navigation.LaneConfidence.LOW) {
                            base.copy(
                                confidence = com.example.navigation.LaneConfidence.LOW,
                                suggestedSide = laneSideHint ?: base.suggestedSide,
                                hasActionableLaneGuidance = false,
                                shouldShowFullDiagram = false
                            )
                        } else {
                            base
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FullLaneDiagramOverlay(
                        decision = effectiveDecision,
                        isNightMode = isNightMode,
                        highlightColor = laneHighlightColor,
                        compact = showingJunctionCard,
                        pulseTriggerKey = lanePulseTriggerKey,
                        laneChangeCuePulseKey = laneChangeCuePulseKey
                    )
                }
            }
        }

        // Phase 2: Secondary "Then ..." maneuver strip when the next turn comes soon after
        if (thenManeuver != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CockpitSurfaceDark.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("then_maneuver_strip")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = strings.thenPrefix,
                        style = MaterialTheme.typography.labelLarge,
                        color = CyanBeacon,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = maneuverIconFor(thenManeuver.type),
                        contentDescription = thenManeuver.instructionEn,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = thenManeuver.streetNames.firstOrNull()?.ifBlank { thenManeuver.instructionEn }
                            ?: thenManeuver.instructionEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // J3: Sygic-style Highway Junction View (replaces small lane strip, tap to dismiss for current maneuver)
        AnimatedVisibility(
            visible = showingJunctionCard,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            junctionScene?.let { scene ->
                JunctionViewCard(
                    scene = scene,
                    distanceText = distText,
                    isNightMode = isNightMode,
                    onDismiss = onDismissJunctionPreview,
                    laneChangeCuePulseKey = laneChangeCuePulseKey,
                    laneConfidence = laneConfidence,
                    laneSideHint = laneSideHint,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HighwaySignpostHeader(signpost: HighwaySignpost) {
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        signpost.primaryExitNumber?.let { exitNum ->
            Surface(
                color = Color(0xFFFFD54F),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "${strings.exitLabel} $exitNum",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (signpost.primaryRoadNumbers.isNotBlank()) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = signpost.primaryRoadNumbers,
                    color = HighwaySignGreenDark,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}



/**
 * Enlarged Junction Preview drawn from the actual Valhalla route geometry around the
 * upcoming highway exit/split, rotated heading-up, with overhead green signpost and tap-to-close.
 */
@Composable
fun EnlargedJunctionPreviewCard(
    maneuver: RouteManeuver,
    distanceText: String,
    routeShape: List<LatLon>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val signpost = maneuver.signpost

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CockpitMidnight),
        border = BorderStroke(1.5.dp, CyanBeacon.copy(alpha = 0.65f)),
        modifier = modifier.clickable(onClick = onDismiss)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${strings.junctionPreviewLabel} • ${strings.tapToClosePreview}",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanBeacon
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = distanceText,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = Color.White
                    )
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = strings.close,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Overhead highway green gantry sign
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HighwaySignGreen)
                    .border(1.5.dp, Color.White, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    signpost?.primaryExitNumber?.let { exitNum ->
                        Text(
                            text = "${strings.exitLabel} $exitNum",
                            color = Color(0xFFFFD54F),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                    Text(
                        text = signpost?.primaryDestinations?.takeIf { it.isNotBlank() }
                            ?: maneuver.streetNames.firstOrNull().orEmpty(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Real route geometry zoomed around the junction vertex, rotated heading-up
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                val w = size.width
                val h = size.height

                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A1931), Color(0xFF162447))
                    )
                )

                val pivotIdx = maneuver.beginShapeIndex.coerceIn(0, (routeShape.lastIndex).coerceAtLeast(0))
                if (routeShape.size >= 3) {
                    val startIdx = (pivotIdx - 12).coerceAtLeast(0)
                    val endIdx = (pivotIdx + 18).coerceAtMost(routeShape.lastIndex)
                    val pivot = routeShape[pivotIdx]
                    val approachPt = routeShape[(pivotIdx - 2).coerceAtLeast(0)]
                    val approachBearingDeg = if (approachPt != pivot) {
                        ValhallaResponseParser.bearingDegrees(approachPt, pivot)
                    } else 0.0
                    val rotRad = Math.toRadians(-approachBearingDeg)
                    val cosR = cos(rotRad)
                    val sinR = sin(rotRad)
                    val cosLat = cos(Math.toRadians(pivot.lat)).coerceAtLeast(0.1)

                    // Project real GPS points into heading-up local meters around the junction
                    val localPts = (startIdx..endIdx).map { i ->
                        val p = routeShape[i]
                        val eastM = (p.lon - pivot.lon) * 111_320.0 * cosLat
                        val northM = (p.lat - pivot.lat) * 111_320.0
                        val rx = eastM * cosR - northM * sinR
                        val ry = eastM * sinR + northM * cosR
                        Offset(rx.toFloat(), ry.toFloat())
                    }

                    val maxExtent = localPts.maxOfOrNull { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) }
                        ?.coerceAtLeast(80f) ?: 150f
                    val scale = (minOf(w, h) * 0.42f) / maxExtent
                    val cx = w * 0.5f
                    val cy = h * 0.56f

                    val screenPts = localPts.map { pt ->
                        Offset(cx + pt.x * scale, cy - pt.y * scale)
                    }

                    // Draw main highway continuation straight ahead for visual split context
                    drawLine(
                        color = Color(0xFF334155),
                        start = Offset(cx, h),
                        end = Offset(cx, 0f),
                        strokeWidth = 28f,
                        cap = StrokeCap.Round
                    )

                    // Draw road asphalt casing along actual exit/split geometry
                    val roadPath = Path().apply {
                        screenPts.forEachIndexed { idx, offset ->
                            if (idx == 0) moveTo(offset.x, offset.y) else lineTo(offset.x, offset.y)
                        }
                    }
                    drawPath(
                        roadPath,
                        color = Color(0xFF2B3448),
                        style = Stroke(width = 30f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Glowing cyan guidance line along actual route geometry
                    drawPath(
                        roadPath,
                        color = CyanBeacon.copy(alpha = 0.35f),
                        style = Stroke(width = 18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        roadPath,
                        color = CyanBeacon,
                        style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Junction split pivot marker
                    drawCircle(
                        color = Color.White,
                        radius = 6f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = CyanBeacon,
                        radius = 3.5f,
                        center = Offset(cx, cy)
                    )
                }
            }
        }
    }
}

/**
 * V3: Authentic US MUTCD Speed Limit sign placed side-by-side with a circular speedometer ring.
 * Ring color reflects [com.example.navigation.SpeedometerRingPolicy] (normal cyan, yellow within
 * 3 mph / 5 km/h of limit, red when above limit + tolerance). Shows a subtle "est." / "تخمینی"
 * label under the speed limit when `speedLimitSource == "hpms"`.
 */
@Composable
fun SpeedometerAndLimitCluster(
    currentSpeedMps: Float,
    speedLimitMph: Int?,
    isOverspeed: Boolean,
    unit: DistanceUnit,
    modifier: Modifier = Modifier,
    overspeedToleranceMph: Int = 5,
    speedLimitSource: String = "none",
    upcomingSpeedLimitMph: Int? = null,
    upcomingSpeedLimitDistanceMeters: Double? = null
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val currentSpeed = BilingualFormatter.formatSpeedValue(currentSpeedMps, unit)
    val ringState = com.example.navigation.SpeedometerRingPolicy.evaluate(
        currentSpeedMps = currentSpeedMps,
        speedLimitMph = speedLimitMph,
        overspeedToleranceMph = overspeedToleranceMph,
        unit = unit
    )
    val effectiveOverspeed = isOverspeed || ringState == com.example.navigation.SpeedRingState.OVERSPEED
    val ringColor by animateColorAsState(
        targetValue = when {
            effectiveOverspeed -> AlertRed
            ringState == com.example.navigation.SpeedRingState.APPROACHING_LIMIT -> Color(0xFFFACC15)
            else -> CyanBeacon
        },
        label = "speed_ring_color"
    )
    val dialColor by animateColorAsState(
        targetValue = if (effectiveOverspeed) AlertRed.copy(alpha = 0.92f) else CockpitMidnight,
        label = "overspeed_bg"
    )
    val isEstimated = com.example.navigation.SpeedometerRingPolicy.isEstimatedLimitSource(speedLimitSource)

    Row(
        modifier = modifier.testTag("speed_cluster"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Circular speedometer with high-contrast outer ring
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .testTag("speedometer_ring")
        ) {
            Surface(
                shape = CircleShape,
                color = dialColor,
                shadowElevation = 8.dp,
                modifier = Modifier.size(68.dp)
            ) {}
            Canvas(modifier = Modifier.size(70.dp)) {
                val strokePx = 4.5.dp.toPx()
                val radius = (size.minDimension - strokePx) / 2f
                drawCircle(
                    color = Color.White.copy(alpha = 0.16f),
                    radius = radius,
                    style = Stroke(width = strokePx)
                )
                drawCircle(
                    color = ringColor,
                    radius = radius,
                    style = Stroke(width = strokePx)
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = BilingualFormatter.formatNumber(currentSpeed, language),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (language == AppLanguage.PERSIAN) MaterialTheme.typography.titleLarge.fontFamily else JetBrainsMonoFamily,
                    color = Color.White
                )
                Text(
                    text = if (unit == DistanceUnit.IMPERIAL) strings.speedUnitMph else strings.speedUnitKmh,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        if (speedLimitMph != null) {
            val displayLimit = BilingualFormatter.convertMphToUnit(speedLimitMph, unit)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White,
                border = BorderStroke(2.5.dp, Color(0xFF0F172A)),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .width(64.dp)
                    .testTag("speed_limit_sign")
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = strings.speedLimitTitle,
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F172A),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = BilingualFormatter.formatNumber(displayLimit, language),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = if (language == AppLanguage.PERSIAN) MaterialTheme.typography.headlineMedium.fontFamily else JetBrainsMonoFamily,
                        color = if (effectiveOverspeed) AlertRed else Color(0xFF0F172A)
                    )
                    if (isEstimated) {
                        Text(
                            text = strings.speedLimitEstimatedLabel,
                            fontSize = 8.5.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF475569),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("speed_limit_est_label")
                        )
                    }
                }
            }
        }

        if (upcomingSpeedLimitMph != null && upcomingSpeedLimitDistanceMeters != null && upcomingSpeedLimitDistanceMeters <= 500.0) {
            val nextLimit = BilingualFormatter.convertMphToUnit(upcomingSpeedLimitMph, unit)
            val distStr = BilingualFormatter.formatDistance(upcomingSpeedLimitDistanceMeters, unit, language)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White,
                border = BorderStroke(2.5.dp, AlertAmber),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .width(58.dp)
                    .testTag("upcoming_speed_limit_sign")
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 3.dp, horizontal = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "↓ $distStr",
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFB45309),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = BilingualFormatter.formatNumber(nextLimit, language),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = if (language == AppLanguage.PERSIAN) MaterialTheme.typography.titleMedium.fontFamily else JetBrainsMonoFamily,
                        color = Color(0xFF0F172A)
                    )
                }
            }
        }
    }
}

/**
 * Waze-style approaching hazard / alert banner supporting all 6 road alerts + traffic signals
 * and all 8 user report types, with Confirm / Cleared actions for user reports.
 */
@Composable
fun AheadAlertBanner(
    alert: AheadAlert,
    unit: DistanceUnit,
    onConfirmReport: (Long) -> Unit,
    onDismissReport: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val distStr = BilingualFormatter.formatDistance(alert.distanceMeters, unit, language)

    val accentColor = when (alert.kind) {
        RoadAlertKind.USER_POLICE, RoadAlertKind.USER_SPEED_TRAP -> AlertPoliceBlue
        RoadAlertKind.SPEED_CAMERA, RoadAlertKind.RED_LIGHT_CAMERA,
        RoadAlertKind.USER_CRASH, RoadAlertKind.USER_ROAD_CLOSURE, RoadAlertKind.STOP_SIGN -> AlertRed
        RoadAlertKind.USER_CONSTRUCTION -> Color(0xFFF97316)
        else -> AlertAmber
    }

    val icon = when (alert.kind) {
        RoadAlertKind.SPEED_CAMERA -> Icons.Filled.CameraAlt
        RoadAlertKind.RED_LIGHT_CAMERA -> Icons.Filled.Traffic
        RoadAlertKind.USER_POLICE -> Icons.Filled.LocalPolice
        RoadAlertKind.USER_CRASH -> Icons.Filled.CarCrash
        RoadAlertKind.USER_ROAD_CLOSURE -> Icons.Filled.Block
        RoadAlertKind.USER_SPEED_TRAP, RoadAlertKind.SPEED_LIMIT_DROP -> Icons.Filled.Speed
        RoadAlertKind.USER_CONSTRUCTION -> Icons.Filled.Construction
        RoadAlertKind.USER_STALLED_VEHICLE -> Icons.Filled.DirectionsCar
        RoadAlertKind.USER_OBJECT_ON_ROAD -> Icons.Filled.ReportProblem
        RoadAlertKind.RAILROAD_CROSSING -> Icons.Filled.Train
        RoadAlertKind.STEEP_GRADE -> Icons.Filled.Terrain
        RoadAlertKind.SCHOOL_ZONE -> Icons.Filled.School
        RoadAlertKind.TOLL_BOOTH -> Icons.Filled.Toll
        RoadAlertKind.TRAFFIC_SIGNAL -> Icons.Filled.Traffic
        else -> Icons.Filled.Warning
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CockpitSurfaceDark,
        border = BorderStroke(2.dp, accentColor),
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("ahead_alert_banner")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (alert.kind == RoadAlertKind.SPEED_BUMP) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.ic_speed_bump_map),
                            contentDescription = alert.titleEn,
                            modifier = Modifier.size(30.dp)
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = alert.titleEn,
                            tint = accentColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (language == AppLanguage.PERSIAN) alert.titleFa else alert.titleEn,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = distStr + if (alert.detail.isNotBlank()) " • ${alert.detail}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accentColor
                    )
                }

                if (alert.newSpeedLimitMph != null && alert.newSpeedLimitMph > 0) {
                    val shownSpeed = BilingualFormatter.convertMphToUnit(alert.newSpeedLimitMph, unit)
                    val unitLabel = if (unit == DistanceUnit.IMPERIAL) strings.speedUnitMph else strings.speedUnitKmh
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        border = BorderStroke(2.dp, accentColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = BilingualFormatter.formatNumber(shownSpeed, language),
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                lineHeight = 18.sp
                            )
                            Text(
                                text = unitLabel,
                                color = Color(0xFF334155),
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                lineHeight = 10.sp
                            )
                        }
                    }
                }
            }

            if (alert.userReportId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onConfirmReport(alert.userReportId) },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(strings.reportStillThere, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = { onDismissReport(alert.userReportId) },
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(strings.reportGone, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Large, thumb-friendly 8-tile Waze-style driving report selector:
 * Police, Hazard, Crash, Road Closed, Speed Trap, Construction, Object on Road, Stalled Vehicle.
 */
@Composable
fun OneTapReportGrid(
    onSelectReport: (ReportType) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val items = listOf(
        Triple(ReportType.POLICE, strings.reportPolice, Icons.Filled.LocalPolice to AlertPoliceBlue),
        Triple(ReportType.HAZARD, strings.reportHazard, Icons.Filled.Warning to AlertAmber),
        Triple(ReportType.CRASH, strings.reportCrash, Icons.Filled.CarCrash to AlertRed),
        Triple(ReportType.ROAD_CLOSURE, strings.reportClosure, Icons.Filled.Block to Color(0xFFDC2626)),
        Triple(ReportType.SPEED_TRAP, strings.reportSpeedTrap, Icons.Filled.Speed to Color(0xFF0EA5E9)),
        Triple(ReportType.CONSTRUCTION, strings.reportConstruction, Icons.Filled.Construction to Color(0xFFF97316)),
        Triple(ReportType.OBJECT_ON_ROAD, strings.reportObjectOnRoad, Icons.Filled.ReportProblem to Color(0xFFEAB308)),
        Triple(ReportType.STALLED_VEHICLE, strings.reportStalledVehicle, Icons.Filled.DirectionsCar to Color(0xFF8B5CF6))
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = strings.reportSheetTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        items.chunked(4).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (type, label, iconColor) ->
                    ReportTileButton(
                        label = label,
                        icon = iconColor.first,
                        accent = iconColor.second,
                        onClick = { onSelectReport(type) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("report_tile_${type.name.lowercase()}")
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(strings.cancel)
        }
    }
}

@Composable
private fun ReportTileButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.65f)),
        modifier = modifier
            .height(92.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
