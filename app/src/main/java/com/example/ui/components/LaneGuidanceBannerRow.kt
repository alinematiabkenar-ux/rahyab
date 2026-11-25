package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.navigation.LaneDirection
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneInfo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Waze / Apple Maps-style lane guidance row integrated directly inside the top maneuver banner.
 *
 * Guarantees:
 * - Strictly physical Left-to-Right windshield order (`LayoutDirection.Ltr`) even when the app
 *   is in Persian RTL mode — lanes are NEVER mirrored.
 * - Three visual prominence tiers:
 *     1. `ACTIVE` (best lanes): bright white arrows on a high-contrast accent background with a
 *        crisp white border.
 *     2. `VALID` (allowed lanes): medium-bright arrows on a softly tinted lane box.
 *     3. `INACTIVE` (other lanes): dim gray arrows on a recessed box.
 * - Combined-arrow lanes draw dimmed non-needed arrows first, then highlight ONLY the needed
 *   arrow(s) (`activeDirections`) on top with a clean shared lower stem.
 * - Supports all turn arrows plus merge arrows (`MERGE_LEFT`, `MERGE_RIGHT`).
 * - Minimum 40 dp lane boxes for normal roads (44–56 dp target) and smooth equal-weight shrinking
 *   when many lanes exist (e.g., 7–9 lane freeways) so ALL lanes fit in a single row without clipping.
 * - Gently pulses highlighted (`ACTIVE` and `VALID`) lanes once when synchronized with the half-mile
 *   voice announcement (`pulseTriggerKey`).
 * - Strictly renders nothing when [lanes] is empty.
 */
@Composable
fun LaneGuidanceBannerRow(
    lanes: List<LaneInfo>,
    isNightMode: Boolean,
    useHighwayGreen: Boolean,
    pulseTriggerKey: Int,
    modifier: Modifier = Modifier,
    laneChangeCuePulseKey: Long = 0L
) {
    if (lanes.isEmpty()) return

    val pulseScale = remember { Animatable(1f) }
    val pulseGlow = remember { Animatable(0f) }
    val cuePulseActive = remember { Animatable(0f) }

    LaunchedEffect(pulseTriggerKey) {
        if (pulseTriggerKey > 0) {
            pulseScale.snapTo(1f)
            pulseGlow.snapTo(0f)
            pulseGlow.animateTo(1f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
            pulseScale.animateTo(1.09f, animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))
            pulseScale.animateTo(1f, animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing))
            pulseGlow.animateTo(0f, animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing))
        }
    }

    // L3: 3-second (3000 ms) visual pulse on lane change cue: bright target lanes + dimmed other lanes
    LaunchedEffect(laneChangeCuePulseKey) {
        if (laneChangeCuePulseKey > 0L) {
            cuePulseActive.snapTo(1f)
            repeat(5) {
                pulseGlow.animateTo(1f, animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
                pulseScale.animateTo(1.09f, animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
                pulseScale.animateTo(1.0f, animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
                pulseGlow.animateTo(0.35f, animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
            }
            pulseGlow.animateTo(0f, animationSpec = tween(durationMillis = 180))
            cuePulseActive.animateTo(0f, animationSpec = tween(durationMillis = 220))
        }
    }

    // High-contrast Day & Night visual palette tuned for both city navy and highway green banners
    val dividerColor = if (isNightMode) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.30f)
    val laneWellBg = when {
        useHighwayGreen && isNightMode -> Color.Black.copy(alpha = 0.28f)
        useHighwayGreen -> Color.Black.copy(alpha = 0.22f)
        isNightMode -> Color.Black.copy(alpha = 0.30f)
        else -> Color.Black.copy(alpha = 0.24f)
    }

    val activeBoxBg = when {
        useHighwayGreen && isNightMode -> Color(0xFF00A8E8)
        useHighwayGreen -> Color(0xFF0284C7)
        isNightMode -> Color(0xFF0096D6)
        else -> Color(0xFF0284C7)
    }
    val activeBoxBorder = Color.White
    val activeArrowColor = Color.White

    val validBoxBg = if (isNightMode) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.White.copy(alpha = 0.24f)
    }
    val validBoxBorder = if (isNightMode) {
        Color.White.copy(alpha = 0.56f)
    } else {
        Color.White.copy(alpha = 0.68f)
    }
    val validArrowColor = if (isNightMode) {
        Color.White.copy(alpha = 0.86f)
    } else {
        Color.White.copy(alpha = 0.90f)
    }

    val inactiveBoxBg = if (isNightMode) {
        Color.White.copy(alpha = 0.05f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    val inactiveBoxBorder = if (isNightMode) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.20f)
    }
    val inactiveArrowColor = if (isNightMode) {
        Color(0xFF94A3B8).copy(alpha = 0.45f)
    } else {
        Color(0xFFCBD5E1).copy(alpha = 0.52f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lane_guidance_strip")
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = dividerColor
        )
        Spacer(modifier = Modifier.height(10.dp))

        // CRITICAL: Always render lanes strictly Left-to-Right (physical windshield order),
        // even when LocalLayoutDirection is RTL in Persian!
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(laneWellBg)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val laneCount = lanes.size
                val gap: Dp = when {
                    laneCount >= 8 -> 2.dp
                    laneCount >= 6 -> 4.dp
                    else -> 6.dp
                }
                val totalGap = gap * (laneCount - 1).coerceAtLeast(0)
                val availablePerLane = (maxWidth - totalGap) / laneCount
                val useEvenShrink = availablePerLane < 44.dp
                val boxWidth: Dp = if (useEvenShrink) availablePerLane else minOf(56.dp, availablePerLane)
                val boxHeight: Dp = if (useEvenShrink) {
                    (availablePerLane * 1.16f).coerceIn(40.dp, 50.dp)
                } else {
                    52.dp
                }
                val canvasSize: Dp = (minOf(boxWidth, boxHeight) - 8.dp).coerceAtLeast(22.dp)

                Row(
                    modifier = if (useEvenShrink) Modifier.fillMaxWidth() else Modifier,
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    lanes.forEachIndexed { index, lane ->
                        val tier = lane.highlightTier
                        val isHighlighted = tier == LaneHighlightTier.ACTIVE || tier == LaneHighlightTier.VALID
                        val scale = if (isHighlighted) pulseScale.value else 1f
                        val glowAlpha = if (isHighlighted) pulseGlow.value else 0f

                        val boxBg = when (tier) {
                            LaneHighlightTier.ACTIVE -> activeBoxBg
                            LaneHighlightTier.VALID -> validBoxBg
                            LaneHighlightTier.INACTIVE -> inactiveBoxBg
                        }
                        val borderColor = when (tier) {
                            LaneHighlightTier.ACTIVE -> activeBoxBorder
                            LaneHighlightTier.VALID -> validBoxBorder.copy(
                                alpha = (validBoxBorder.alpha + glowAlpha * 0.25f).coerceAtMost(1f)
                            )
                            LaneHighlightTier.INACTIVE -> inactiveBoxBorder
                        }
                        val borderWidth = when (tier) {
                            LaneHighlightTier.ACTIVE -> if (glowAlpha > 0.05f) 2.6.dp else 2.dp
                            LaneHighlightTier.VALID -> if (glowAlpha > 0.05f) 2.dp else 1.4.dp
                            LaneHighlightTier.INACTIVE -> 1.dp
                        }
                        val primaryArrowColor = when (tier) {
                            LaneHighlightTier.ACTIVE -> activeArrowColor
                            LaneHighlightTier.VALID -> validArrowColor
                            LaneHighlightTier.INACTIVE -> inactiveArrowColor
                        }
                        val secondaryArrowColor = when (tier) {
                            LaneHighlightTier.ACTIVE -> Color.White.copy(alpha = 0.34f)
                            LaneHighlightTier.VALID -> Color.White.copy(alpha = 0.28f)
                            LaneHighlightTier.INACTIVE -> inactiveArrowColor
                        }

                        val boxAlpha = if (!isHighlighted && cuePulseActive.value > 0.05f) {
                            1f - (0.68f * cuePulseActive.value)
                        } else {
                            1f
                        }
                        val semanticDesc = buildLaneContentDescription(index, laneCount, lane)
                        val boxModifier = (if (useEvenShrink) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.width(boxWidth)
                        })
                            .height(boxHeight)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = boxAlpha
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(boxBg)
                            .border(
                                width = borderWidth,
                                color = borderColor,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .testTag("lane_box_$index")
                            .semantics { contentDescription = semanticDesc }

                        Box(
                            modifier = boxModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(canvasSize)) {
                                drawCombinedLaneGlyph(
                                    lane = lane,
                                    primaryColor = primaryArrowColor,
                                    dimmedColor = secondaryArrowColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildLaneContentDescription(index: Int, total: Int, lane: LaneInfo): String {
    val tierLabel = when (lane.highlightTier) {
        LaneHighlightTier.ACTIVE -> "Best lane"
        LaneHighlightTier.VALID -> "Allowed lane"
        LaneHighlightTier.INACTIVE -> "Other lane"
    }
    val dirs = lane.directions.joinToString(" and ") { it.name.lowercase().replace('_', ' ') }
    return "Lane ${index + 1} of $total: $tierLabel ($dirs)"
}

/**
 * Draws all arrows inside a single lane box.
 * In a combined-arrow lane, dimmed non-needed directions are painted first with a lighter stroke,
 * followed by the highlighted needed arrow(s) on top with a bold stroke, sharing a clean lower stem.
 */
private fun DrawScope.drawCombinedLaneGlyph(
    lane: LaneInfo,
    primaryColor: Color,
    dimmedColor: Color
) {
    val allDirs = lane.directions.ifEmpty { return }
    val tier = lane.highlightTier
    val highlightedDirs: Set<LaneDirection> = when (tier) {
        LaneHighlightTier.ACTIVE, LaneHighlightTier.VALID ->
            lane.activeDirections.ifEmpty { allDirs }.toSet()
        LaneHighlightTier.INACTIVE -> emptySet()
    }

    val isCombined = allDirs.size > 1
    val baseStroke = (size.minDimension * if (isCombined) 0.115f else 0.135f).coerceIn(2.6f, 6.2f)
    val dimStroke = (baseStroke * 0.78f).coerceAtLeast(2.2f)
    val boldStroke = when (tier) {
        LaneHighlightTier.ACTIVE -> baseStroke * 1.15f
        LaneHighlightTier.VALID -> baseStroke * 1.02f
        LaneHighlightTier.INACTIVE -> dimStroke
    }

    // Pass 1: Draw all non-highlighted arrows underneath
    for (dir in allDirs) {
        if (dir !in highlightedDirs) {
            drawSingleLaneArrow(
                direction = dir,
                color = dimmedColor,
                strokeWidth = dimStroke,
                isCombined = isCombined
            )
        }
    }

    // Pass 2: Draw highlighted needed arrow(s) cleanly on top
    for (dir in allDirs) {
        if (dir in highlightedDirs) {
            drawSingleLaneArrow(
                direction = dir,
                color = primaryColor,
                strokeWidth = boldStroke,
                isCombined = isCombined
            )
        }
    }
}

private fun DrawScope.drawSingleLaneArrow(
    direction: LaneDirection,
    color: Color,
    strokeWidth: Float,
    isCombined: Boolean
) {
    val w = size.width
    val h = size.height
    val cx = w * 0.50f
    val bottomY = h * 0.90f
    val stemSplitY = h * 0.56f
    val turnY = h * 0.44f
    val topY = h * 0.14f

    // Shared lower stem keeps combined arrows (e.g., straight+right, left+straight) super clean
    val stemX = if (isCombined) cx else when (direction) {
        LaneDirection.LEFT, LaneDirection.SHARP_LEFT -> w * 0.62f
        LaneDirection.SLIGHT_LEFT -> w * 0.58f
        LaneDirection.RIGHT, LaneDirection.SHARP_RIGHT -> w * 0.38f
        LaneDirection.SLIGHT_RIGHT -> w * 0.42f
        LaneDirection.MERGE_LEFT -> w * 0.66f
        LaneDirection.MERGE_RIGHT -> w * 0.34f
        else -> cx
    }

    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path()

    when (direction) {
        LaneDirection.THROUGH -> {
            path.moveTo(cx, bottomY)
            path.lineTo(cx, topY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(cx, topY), Offset(cx, bottomY), color, strokeWidth, isCombined)
        }

        LaneDirection.LEFT -> {
            val tipX = w * 0.15f
            path.moveTo(stemX, bottomY)
            path.lineTo(stemX, stemSplitY)
            path.quadraticTo(stemX, turnY, (stemX + tipX) * 0.52f, turnY)
            path.lineTo(tipX, turnY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(tipX, turnY), Offset(stemX, turnY), color, strokeWidth, isCombined)
        }

        LaneDirection.RIGHT -> {
            val tipX = w * 0.85f
            path.moveTo(stemX, bottomY)
            path.lineTo(stemX, stemSplitY)
            path.quadraticTo(stemX, turnY, (stemX + tipX) * 0.48f, turnY)
            path.lineTo(tipX, turnY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(tipX, turnY), Offset(stemX, turnY), color, strokeWidth, isCombined)
        }

        LaneDirection.SLIGHT_LEFT -> {
            val tipX = w * 0.21f
            val tipY = h * 0.20f
            path.moveTo(stemX, bottomY)
            path.lineTo(stemX, stemSplitY)
            path.lineTo(tipX, tipY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(tipX, tipY), Offset(stemX, stemSplitY), color, strokeWidth, isCombined)
        }

        LaneDirection.SLIGHT_RIGHT -> {
            val tipX = w * 0.79f
            val tipY = h * 0.20f
            path.moveTo(stemX, bottomY)
            path.lineTo(stemX, stemSplitY)
            path.lineTo(tipX, tipY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(tipX, tipY), Offset(stemX, stemSplitY), color, strokeWidth, isCombined)
        }

        LaneDirection.SHARP_LEFT -> {
            val tipX = w * 0.17f
            val tipY = h * 0.68f
            val apexY = h * 0.36f
            path.moveTo(stemX, bottomY)
            path.lineTo(stemX, apexY)
            path.lineTo(tipX, tipY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(tipX, tipY), Offset(stemX, apexY), color, strokeWidth, isCombined)
        }

        LaneDirection.SHARP_RIGHT -> {
            val tipX = w * 0.83f
            val tipY = h * 0.68f
            val apexY = h * 0.36f
            path.moveTo(stemX, bottomY)
            path.lineTo(stemX, apexY)
            path.lineTo(tipX, tipY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(tipX, tipY), Offset(stemX, apexY), color, strokeWidth, isCombined)
        }

        LaneDirection.U_TURN_LEFT, LaneDirection.U_TURN_RIGHT -> {
            val dirSign = if (direction == LaneDirection.U_TURN_LEFT) -1f else 1f
            val startX = if (isCombined) cx else cx - dirSign * w * 0.18f
            val endX = if (isCombined) cx + dirSign * w * 0.30f else cx + dirSign * w * 0.18f
            val midLoopX = (startX + endX) * 0.5f
            val archY = h * 0.30f
            val tipY = h * 0.64f
            path.moveTo(startX, bottomY)
            path.lineTo(startX, archY)
            path.quadraticTo(midLoopX, h * 0.08f, endX, archY)
            path.lineTo(endX, tipY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(endX, tipY), Offset(endX, archY), color, strokeWidth, isCombined)
        }

        LaneDirection.MERGE_LEFT -> {
            // Smooth S-curve merging from right into the left lane, finishing pointing straight up
            val startX = if (isCombined) cx else w * 0.70f
            val targetX = w * 0.28f
            val tipY = h * 0.16f
            path.moveTo(startX, bottomY)
            path.cubicTo(
                startX, h * 0.60f,
                targetX, h * 0.54f,
                targetX, h * 0.30f
            )
            path.lineTo(targetX, tipY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(targetX, tipY), Offset(targetX, h * 0.36f), color, strokeWidth, isCombined)
        }

        LaneDirection.MERGE_RIGHT -> {
            // Smooth S-curve merging from left into the right lane, finishing pointing straight up
            val startX = if (isCombined) cx else w * 0.30f
            val targetX = w * 0.72f
            val tipY = h * 0.16f
            path.moveTo(startX, bottomY)
            path.cubicTo(
                startX, h * 0.60f,
                targetX, h * 0.54f,
                targetX, h * 0.30f
            )
            path.lineTo(targetX, tipY)
            drawPath(path, color, style = stroke)
            drawCleanArrowHead(Offset(targetX, tipY), Offset(targetX, h * 0.36f), color, strokeWidth, isCombined)
        }
    }
}

private fun DrawScope.drawCleanArrowHead(
    tip: Offset,
    from: Offset,
    color: Color,
    strokeWidth: Float,
    isCombined: Boolean
) {
    val angle = atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
    val headLen = size.minDimension * if (isCombined) 0.21f else 0.25f
    val leftAngle = angle + Math.toRadians(142.0)
    val rightAngle = angle - Math.toRadians(142.0)
    val headPath = Path().apply {
        moveTo(
            tip.x + (headLen * cos(leftAngle)).toFloat(),
            tip.y + (headLen * sin(leftAngle)).toFloat()
        )
        lineTo(tip.x, tip.y)
        lineTo(
            tip.x + (headLen * cos(rightAngle)).toFloat(),
            tip.y + (headLen * sin(rightAngle)).toFloat()
        )
    }
    drawPath(
        headPath,
        color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}
