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
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.graphics.PathEffect
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
import com.example.navigation.LaneDirection
import com.example.navigation.LaneInfo
import com.example.navigation.ManeuverType
import com.example.navigation.RoadAlertKind
import com.example.navigation.RouteManeuver
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
    ManeuverType.ARRIVE -> Icons.Filled.Flag
    else -> Icons.Filled.ArrowUpward
}

/**
 * Top turn-by-turn banner with authentic US highway green signpost pill and Sygic-style lane strip.
 * Lane strip is strictly omitted whenever `maneuver.lanes` is empty (never guessed or invented).
 */
@Composable
fun TurnByTurnTopHud(
    maneuver: RouteManeuver,
    distanceMeters: Double,
    unit: DistanceUnit,
    isRerouting: Boolean,
    showJunctionPreview: Boolean,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val distText = BilingualFormatter.formatDistance(distanceMeters, unit, language)
    val signpost = maneuver.signpost
    val useHighwayGreen = signpost != null || maneuver.isHighwayExitOrSplit

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("turn_by_turn_hud"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (useHighwayGreen) HighwaySignGreen else CockpitMidnight,
            border = BorderStroke(
                width = if (useHighwayGreen) 2.dp else 1.dp,
                color = if (useHighwayGreen) HighwaySignBorder else CyanBeacon.copy(alpha = 0.4f)
            ),
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Highway signpost top row: Exit badge + Road shield numbers
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
                }

                // Strictly show lane guidance strip ONLY when real OSM/Valhalla turn:lanes exist
                if (maneuver.lanes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LaneGuidanceStrip(
                        lanes = maneuver.lanes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lane_guidance_strip")
                    )
                }
            }
        }

        // Enlarged Sygic-style Junction Preview for highway exits & complex splits
        AnimatedVisibility(
            visible = showJunctionPreview,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            EnlargedJunctionPreviewCard(
                maneuver = maneuver,
                distanceText = distText,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("junction_preview_card")
            )
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
 * Sygic-style custom Canvas lane strip rendering arrows for EVERY physical lane
 * parsed from Valhalla / OSM `turn:lanes`. Recommended lanes glow crisp cyan-white;
 * non-recommended lanes stay subdued.
 */
@Composable
fun LaneGuidanceStrip(
    lanes: List<LaneInfo>,
    modifier: Modifier = Modifier
) {
    if (lanes.isEmpty()) return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.34f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            lanes.forEachIndexed { index, lane ->
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (lane.isRecommended) CyanBeacon.copy(alpha = 0.22f)
                            else Color.Transparent
                        )
                        .border(
                            width = if (lane.isRecommended) 1.5.dp else 0.dp,
                            color = if (lane.isRecommended) CyanBeacon else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(34.dp)) {
                        // Draw inactive directions first, then recommended active arrows on top
                        val inactive = lane.directions.filterNot { it in lane.activeDirections }
                        for (dir in inactive) {
                            drawLaneArrow(dir, Color.White.copy(alpha = 0.32f), strokeWidth = 3.5f)
                        }
                        val active = if (lane.isRecommended) {
                            lane.activeDirections.ifEmpty { lane.directions }
                        } else emptyList()
                        for (dir in active) {
                            drawLaneArrow(dir, Color.White, strokeWidth = 5.2f)
                        }
                    }
                }
                if (index < lanes.lastIndex) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLaneArrow(
    direction: LaneDirection,
    color: Color,
    strokeWidth: Float
) {
    val cx = size.width * 0.5f
    val bottom = size.height * 0.88f
    val midY = size.height * 0.52f
    val topY = size.height * 0.16f

    val path = Path()
    when (direction) {
        LaneDirection.THROUGH -> {
            path.moveTo(cx, bottom)
            path.lineTo(cx, topY)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(cx, topY), Offset(cx, bottom), color, strokeWidth)
        }
        LaneDirection.LEFT -> {
            path.moveTo(cx + size.width * 0.12f, bottom)
            path.lineTo(cx + size.width * 0.12f, midY)
            path.lineTo(size.width * 0.18f, midY)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(size.width * 0.18f, midY), Offset(cx, midY), color, strokeWidth)
        }
        LaneDirection.RIGHT -> {
            path.moveTo(cx - size.width * 0.12f, bottom)
            path.lineTo(cx - size.width * 0.12f, midY)
            path.lineTo(size.width * 0.82f, midY)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(size.width * 0.82f, midY), Offset(cx, midY), color, strokeWidth)
        }
        LaneDirection.SLIGHT_LEFT -> {
            path.moveTo(cx + size.width * 0.10f, bottom)
            path.lineTo(cx + size.width * 0.10f, midY + 4f)
            path.lineTo(size.width * 0.22f, topY + 4f)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(size.width * 0.22f, topY + 4f), Offset(cx + size.width * 0.10f, midY + 4f), color, strokeWidth)
        }
        LaneDirection.SLIGHT_RIGHT -> {
            path.moveTo(cx - size.width * 0.10f, bottom)
            path.lineTo(cx - size.width * 0.10f, midY + 4f)
            path.lineTo(size.width * 0.78f, topY + 4f)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(size.width * 0.78f, topY + 4f), Offset(cx - size.width * 0.10f, midY + 4f), color, strokeWidth)
        }
        LaneDirection.SHARP_LEFT -> {
            path.moveTo(cx + size.width * 0.15f, bottom)
            path.lineTo(cx + size.width * 0.15f, midY - 6f)
            path.lineTo(size.width * 0.20f, bottom * 0.82f)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(size.width * 0.20f, bottom * 0.82f), Offset(cx + size.width * 0.15f, midY - 6f), color, strokeWidth)
        }
        LaneDirection.SHARP_RIGHT -> {
            path.moveTo(cx - size.width * 0.15f, bottom)
            path.lineTo(cx - size.width * 0.15f, midY - 6f)
            path.lineTo(size.width * 0.80f, bottom * 0.82f)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(size.width * 0.80f, bottom * 0.82f), Offset(cx - size.width * 0.15f, midY - 6f), color, strokeWidth)
        }
        LaneDirection.U_TURN_LEFT, LaneDirection.U_TURN_RIGHT -> {
            val dirSign = if (direction == LaneDirection.U_TURN_LEFT) -1f else 1f
            val startX = cx - dirSign * size.width * 0.16f
            val endX = cx + dirSign * size.width * 0.16f
            path.moveTo(startX, bottom)
            path.lineTo(startX, topY + 10f)
            path.quadraticTo(cx, topY - 4f, endX, topY + 10f)
            path.lineTo(endX, midY + 8f)
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawArrowHead(Offset(endX, midY + 8f), Offset(endX, topY + 10f), color, strokeWidth)
        }
    }
}

private fun DrawScope.drawArrowHead(tip: Offset, from: Offset, color: Color, strokeWidth: Float) {
    val angle = kotlin.math.atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
    val headLen = size.minDimension * 0.24f
    val leftAngle = angle + Math.toRadians(145.0)
    val rightAngle = angle - Math.toRadians(145.0)
    val headPath = Path().apply {
        moveTo(
            tip.x + (headLen * kotlin.math.cos(leftAngle)).toFloat(),
            tip.y + (headLen * kotlin.math.sin(leftAngle)).toFloat()
        )
        lineTo(tip.x, tip.y)
        lineTo(
            tip.x + (headLen * kotlin.math.cos(rightAngle)).toFloat(),
            tip.y + (headLen * kotlin.math.sin(rightAngle)).toFloat()
        )
    }
    drawPath(headPath, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * Enlarged 3D-perspective highway exit / junction split visual diagram
 * showing the overhead signpost gantry and illuminated target lanes.
 */
@Composable
fun EnlargedJunctionPreviewCard(
    maneuver: RouteManeuver,
    distanceText: String,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val isRightExit = maneuver.type in setOf(
        ManeuverType.EXIT_RIGHT,
        ManeuverType.RAMP_RIGHT,
        ManeuverType.STAY_RIGHT,
        ManeuverType.SLIGHT_RIGHT,
        ManeuverType.RIGHT
    )
    val lanes = maneuver.lanes
    val signpost = maneuver.signpost

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CockpitMidnight),
        border = BorderStroke(1.5.dp, CyanBeacon.copy(alpha = 0.65f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.junctionPreviewLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanBeacon
                )
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = Color.White
                )
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

            // 3D perspective road & split ramp diagram
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                val w = size.width
                val h = size.height

                // Night horizon sky gradient
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A1931), Color(0xFF162447))
                    )
                )

                // Main highway trapezoid
                val roadPath = Path().apply {
                    moveTo(w * 0.14f, h)
                    lineTo(w * 0.34f, h * 0.12f)
                    lineTo(w * 0.66f, h * 0.12f)
                    lineTo(w * 0.86f, h)
                    close()
                }
                drawPath(roadPath, Color(0xFF2B3448))

                // Exit ramp branch splitting left or right
                val rampPath = Path().apply {
                    if (isRightExit) {
                        moveTo(w * 0.62f, h)
                        lineTo(w * 0.60f, h * 0.38f)
                        lineTo(w * 0.94f, h * 0.12f)
                        lineTo(w, h * 0.25f)
                        lineTo(w * 0.86f, h)
                        close()
                    } else {
                        moveTo(w * 0.38f, h)
                        lineTo(w * 0.40f, h * 0.38f)
                        lineTo(w * 0.06f, h * 0.12f)
                        lineTo(0f, h * 0.25f)
                        lineTo(w * 0.14f, h)
                        close()
                    }
                }
                drawPath(rampPath, Color(0xFF334155))

                // Dashed lane dividers matching actual lane count (only if lane data exists)
                val laneCount = lanes.size.coerceAtLeast(2)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                for (i in 1 until laneCount) {
                    val frac = i.toFloat() / laneCount.toFloat()
                    val bx = w * (0.14f + frac * 0.72f)
                    val tx = w * (0.34f + frac * 0.32f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = Offset(bx, h),
                        end = Offset(tx, h * 0.14f),
                        strokeWidth = 3f,
                        pathEffect = dashEffect
                    )
                }

                // Glowing cyan guidance trajectory through the recommended lane(s)
                val activeLaneIdx = lanes.indexOfFirst { it.isRecommended }.let {
                    if (it >= 0) it else if (isRightExit) laneCount - 1 else 0
                }
                val laneCenterFrac = (activeLaneIdx + 0.5f) / laneCount.toFloat()
                val startX = w * (0.14f + laneCenterFrac * 0.72f)
                val targetX = if (isRightExit) w * 0.88f else w * 0.12f

                val glowPath = Path().apply {
                    moveTo(startX, h * 0.96f)
                    quadraticTo(startX, h * 0.45f, targetX, h * 0.18f)
                }
                drawPath(
                    glowPath,
                    color = CyanBeacon.copy(alpha = 0.35f),
                    style = Stroke(width = 22f, cap = StrokeCap.Round)
                )
                drawPath(
                    glowPath,
                    color = CyanBeacon,
                    style = Stroke(width = 9f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * Authentic US MUTCD Speed Limit sign paired with live GPS speedometer pill.
 * Turns warning red when exceeding speed limit.
 */
@Composable
fun SpeedometerAndLimitCluster(
    currentSpeedMps: Float,
    speedLimitMph: Int?,
    isOverspeed: Boolean,
    unit: DistanceUnit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val currentSpeed = BilingualFormatter.formatSpeedValue(currentSpeedMps, unit)
    val speedColor by animateColorAsState(
        targetValue = if (isOverspeed) AlertRed else CockpitMidnight,
        label = "overspeed_bg"
    )

    Column(
        modifier = modifier.testTag("speed_cluster"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // US Speed Limit Sign (shown whenever speed limit data exists on road segment)
        if (speedLimitMph != null) {
            val displayLimit = BilingualFormatter.convertMphToUnit(speedLimitMph, unit)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White,
                border = BorderStroke(2.5.dp, Color(0xFF0F172A)),
                shadowElevation = 6.dp,
                modifier = Modifier.width(64.dp)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp),
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
                        color = Color(0xFF0F172A)
                    )
                }
            }
        }

        // Current GPS Speedometer circular badge
        Surface(
            shape = CircleShape,
            color = speedColor,
            border = BorderStroke(2.dp, if (isOverspeed) Color.White else CyanBeacon),
            shadowElevation = 8.dp,
            modifier = Modifier.size(68.dp)
        ) {
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
    }
}

/**
 * Waze-style approaching hazard / alert banner (speed drops, sharp curves, tolls,
 * railroad crossings, school zones, stop signs, and local user reports).
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
        RoadAlertKind.USER_CRASH, RoadAlertKind.USER_ROAD_CLOSURE, RoadAlertKind.STOP_SIGN -> AlertRed
        else -> AlertAmber
    }

    val icon = when (alert.kind) {
        RoadAlertKind.USER_POLICE -> Icons.Filled.LocalPolice
        RoadAlertKind.USER_CRASH -> Icons.Filled.CarCrash
        RoadAlertKind.USER_ROAD_CLOSURE -> Icons.Filled.Block
        RoadAlertKind.USER_SPEED_TRAP, RoadAlertKind.SPEED_LIMIT_DROP -> Icons.Filled.Speed
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
                    Icon(
                        imageVector = icon,
                        contentDescription = alert.titleEn,
                        tint = accentColor,
                        modifier = Modifier.size(26.dp)
                    )
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
 * Large, thumb-friendly 5-tile driving report selector (Police, Hazard, Crash, Road Closure, Speed Trap).
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
        Triple(ReportType.SPEED_TRAP, strings.reportSpeedTrap, Icons.Filled.Speed to Color(0xFF0EA5E9))
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.reportSheetTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Row 1 (3 large buttons)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.take(3).forEach { (type, label, iconColor) ->
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

        // Row 2 (2 large buttons)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.drop(3).forEach { (type, label, iconColor) ->
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

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
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
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.65f)),
        modifier = modifier
            .height(92.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}
