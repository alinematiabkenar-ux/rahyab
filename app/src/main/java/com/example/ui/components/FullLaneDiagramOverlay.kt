package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.i18n.AppLanguage
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.i18n.stringsFor
import com.example.map.LaneHighlightColor
import com.example.navigation.LaneConfidence
import com.example.navigation.LaneDemoScenarios
import com.example.navigation.LaneGuidanceDecision
import com.example.navigation.LaneHighlightTier
import com.example.navigation.LaneSlotDecision
import com.example.navigation.LaneTargetSide
import com.example.navigation.RouteManeuver
import com.example.ui.components.LaneArrowCanvasDrawer.drawDashedLaneDivider
import com.example.ui.components.LaneArrowCanvasDrawer.drawLaneSlotArrows
import com.example.ui.theme.RahYabTheme

/**
 * High-contrast, glanceable full-lane guidance overlay driven by [LaneGuidanceDecision]
 * (derived from Valhalla `turn_lanes` / `approach_lanes` route instructions).
 *
 * Key guarantees:
 * - Shows ALL known lanes traveling in the driver's direction side-by-side in strict physical
 *   Left-to-Right order (`LayoutDirection.Ltr`) in both English and Persian RTL modes.
 * - Visible dashed lane dividers separate every lane column on a road-asphalt backdrop.
 * - Target (`ACTIVE`) lane renders in bright highlight color (default bright blue) with a bold
 *   white outline and a large white directional arrow (~1.5x the height of neighboring arrows)
 *   strictly contained inside its own lane column without covering adjacent lanes.
 * - Secondary (`VALID`) lanes use distinct medium emphasis; non-recommended (`INACTIVE`) lanes
 *   render in contrasting gray with their known directional arrows clearly visible.
 * - Displays an explicit position callout (e.g., "Second lane from the right.") and adapts
 *   smoothly to compact screens or simultaneous Junction View display via [BoxWithConstraints].
 */
@Composable
fun FullLaneDiagramOverlay(
    decision: LaneGuidanceDecision,
    isNightMode: Boolean,
    modifier: Modifier = Modifier,
    highlightColor: LaneHighlightColor = LaneHighlightColor.BLUE,
    compact: Boolean = false,
    pulseTriggerKey: Int = 0,
    laneChangeCuePulseKey: Long = 0L
) {
    if (decision.slots.isEmpty() || decision.shouldSkipLaneChangeInstruction) return

    val language = LocalAppLanguage.current
    if (decision.confidence == LaneConfidence.LOW || !decision.hasActionableLaneGuidance) {
        LaneSideHintBanner(
            side = decision.suggestedSide ?: LaneTargetSide.MIDDLE,
            isNightMode = isNightMode,
            modifier = modifier
        )
        return
    }

    val pulseScale = remember { Animatable(1f) }
    val cueDimFactor = remember { Animatable(0f) }

    LaunchedEffect(pulseTriggerKey) {
        if (pulseTriggerKey > 0) {
            pulseScale.snapTo(1f)
            pulseScale.animateTo(1.06f, animationSpec = tween(220, easing = FastOutSlowInEasing))
            pulseScale.animateTo(1f, animationSpec = tween(320, easing = FastOutSlowInEasing))
        }
    }

    LaunchedEffect(laneChangeCuePulseKey) {
        if (laneChangeCuePulseKey > 0L) {
            cueDimFactor.snapTo(1f)
            repeat(4) {
                pulseScale.animateTo(1.06f, animationSpec = tween(160, easing = FastOutSlowInEasing))
                pulseScale.animateTo(1f, animationSpec = tween(160, easing = FastOutSlowInEasing))
            }
            cueDimFactor.animateTo(0f, animationSpec = tween(240))
        }
    }

    val accentBase = parseHighlightAccent(highlightColor)
    val roadBg = if (isNightMode) Color(0xFF0B1220) else Color(0xFF1E293B)
    val roadBorder = if (isNightMode) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.34f)
    val dividerColor = if (isNightMode) Color.White.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.52f)

    val activeFillTop = if (decision.confidence == LaneConfidence.MEDIUM) {
        accentBase.copy(alpha = 0.58f)
    } else {
        accentBase
    }
    val activeFillBottom = if (decision.confidence == LaneConfidence.MEDIUM) {
        accentBase.copy(alpha = 0.42f)
    } else {
        accentBase.copy(alpha = 0.84f)
    }
    val activeBorder = if (decision.confidence == LaneConfidence.MEDIUM) {
        Color.White.copy(alpha = 0.78f)
    } else {
        Color.White
    }

    val validBg = accentBase.copy(alpha = if (isNightMode) 0.28f else 0.34f)
    val validBorder = Color.White.copy(alpha = 0.72f)
    val validArrow = Color.White.copy(alpha = 0.92f)

    val inactiveBg = if (isNightMode) Color(0xFF334155) else Color(0xFF475569)
    val inactiveBorder = if (isNightMode) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.24f)
    val inactiveArrow = if (isNightMode) Color(0xFFCBD5E1) else Color(0xFFE2E8F0)

    val positionLabel = decision.positionText(language)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = roadBg,
        border = BorderStroke(1.5.dp, roadBorder),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("lane_guidance_strip")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("full_lane_diagram_overlay")
                .padding(horizontal = 8.dp, vertical = if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // CRITICAL: Always render lanes in physical Left-to-Right windshield order,
            // never mirrored even when LocalLayoutDirection is RTL in Persian.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val slots = decision.slots
                    val laneCount = slots.size
                    val dividerWidth: Dp = when {
                        laneCount >= 8 -> 2.dp
                        laneCount >= 6 -> 4.dp
                        else -> 6.dp
                    }
                    val totalDividersWidth = dividerWidth * (laneCount - 1).coerceAtLeast(0)
                    val availablePerLane = (maxWidth - totalDividersWidth) / laneCount
                    val useEvenFit = availablePerLane < 52.dp || laneCount >= 6
                    val slotWidth: Dp = if (useEvenFit) availablePerLane else minOf(78.dp, availablePerLane)
                    val baseHeight: Dp = when {
                        compact -> 60.dp
                        laneCount >= 8 -> 58.dp
                        laneCount >= 6 -> 68.dp
                        else -> 86.dp
                    }

                    Row(
                        modifier = if (useEvenFit) Modifier.fillMaxWidth() else Modifier,
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        slots.forEachIndexed { index, slot ->
                            if (index > 0) {
                                Canvas(
                                    modifier = Modifier
                                        .width(dividerWidth)
                                        .height(baseHeight)
                                        .testTag("full_lane_divider_$index")
                                ) {
                                    drawDashedLaneDivider(dividerColor)
                                }
                            }

                            val tier = slot.tier
                            val isActive = tier == LaneHighlightTier.ACTIVE
                            val isValid = tier == LaneHighlightTier.VALID
                            val isHighlighted = isActive || isValid
                            val arrowHeightFraction = when (tier) {
                                LaneHighlightTier.ACTIVE -> ACTIVE_ARROW_HEIGHT_FRACTION
                                LaneHighlightTier.VALID -> VALID_ARROW_HEIGHT_FRACTION
                                LaneHighlightTier.INACTIVE -> INACTIVE_ARROW_HEIGHT_FRACTION
                            }

                            val slotAlpha = if (!isHighlighted && cueDimFactor.value > 0.05f) {
                                1f - 0.42f * cueDimFactor.value
                            } else {
                                1f
                            }
                            val slotScale = if (isActive) pulseScale.value else 1f
                            val slotShape = RoundedCornerShape(if (laneCount >= 7) 7.dp else 11.dp)
                            val slotBorderWidth = when (tier) {
                                LaneHighlightTier.ACTIVE -> 3.dp
                                LaneHighlightTier.VALID -> 1.75.dp
                                LaneHighlightTier.INACTIVE -> 1.dp
                            }
                            val slotBorderColor = when (tier) {
                                LaneHighlightTier.ACTIVE -> activeBorder
                                LaneHighlightTier.VALID -> validBorder
                                LaneHighlightTier.INACTIVE -> inactiveBorder
                            }
                            val primaryArrowColor = when (tier) {
                                LaneHighlightTier.ACTIVE -> Color.White
                                LaneHighlightTier.VALID -> validArrow
                                LaneHighlightTier.INACTIVE -> inactiveArrow
                            }
                            val secondaryArrowColor = when (tier) {
                                LaneHighlightTier.ACTIVE -> Color.White.copy(alpha = 0.36f)
                                LaneHighlightTier.VALID -> Color.White.copy(alpha = 0.32f)
                                LaneHighlightTier.INACTIVE -> inactiveArrow
                            }

                            val slotDesc = buildSlotContentDescription(index, laneCount, slot)
                            val widthMod = if (useEvenFit) Modifier.weight(1f) else Modifier.width(slotWidth)

                            Box(
                                modifier = widthMod
                                    .height(baseHeight)
                                    .graphicsLayer {
                                        scaleX = slotScale
                                        scaleY = slotScale
                                        alpha = slotAlpha
                                    }
                                    .clip(slotShape)
                                    .then(
                                        if (isActive) {
                                            Modifier.background(
                                                Brush.verticalGradient(listOf(activeFillTop, activeFillBottom))
                                            )
                                        } else {
                                            Modifier.background(if (isValid) validBg else inactiveBg)
                                        }
                                    )
                                    .border(slotBorderWidth, slotBorderColor, slotShape)
                                    .testTag("lane_box_$index")
                                    .semantics { contentDescription = slotDesc },
                                contentAlignment = Alignment.Center
                            ) {
                                val arrowBoxHeight = baseHeight * arrowHeightFraction
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(arrowBoxHeight)
                                        .padding(horizontal = if (laneCount >= 7) 2.dp else 5.dp)
                                        .testTag("full_lane_arrow_$index")
                                ) {
                                    drawLaneSlotArrows(
                                        slot = slot,
                                        primaryColor = primaryArrowColor,
                                        dimmedColor = secondaryArrowColor,
                                        heightFraction = 1.0f
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!positionLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(if (compact) 5.dp else 8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accentBase.copy(alpha = if (isNightMode) 0.24f else 0.28f),
                    border = BorderStroke(1.dp, activeBorder.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = positionLabel,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (compact) 13.sp else 15.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 6.dp)
                            .testTag("full_lane_position_text")
                    )
                }
            }
        }
    }
}

/**
 * Convenience overload that evaluates a Valhalla [RouteManeuver] directly into a
 * [LaneGuidanceDecision] and renders [FullLaneDiagramOverlay].
 */
@Composable
fun FullLaneDiagramOverlay(
    maneuver: RouteManeuver,
    isNightMode: Boolean,
    modifier: Modifier = Modifier,
    highlightColor: LaneHighlightColor = LaneHighlightColor.BLUE,
    compact: Boolean = false,
    pulseTriggerKey: Int = 0,
    laneChangeCuePulseKey: Long = 0L
) {
    val decision = remember(maneuver) { LaneGuidanceDecision.evaluate(maneuver) }
    FullLaneDiagramOverlay(
        decision = decision,
        isNightMode = isNightMode,
        modifier = modifier,
        highlightColor = highlightColor,
        compact = compact,
        pulseTriggerKey = pulseTriggerKey,
        laneChangeCuePulseKey = laneChangeCuePulseKey
    )
}

private fun parseHighlightAccent(color: LaneHighlightColor): Color = when (color) {
    LaneHighlightColor.BLUE -> Color(0xFF1E88E5)
    LaneHighlightColor.RED -> Color(0xFFE53935)
    LaneHighlightColor.GREEN -> Color(0xFF2E7D32)
}

private fun buildSlotContentDescription(index: Int, total: Int, slot: LaneSlotDecision): String {
    val tierLabel = when (slot.tier) {
        LaneHighlightTier.ACTIVE -> "Recommended lane"
        LaneHighlightTier.VALID -> "Allowed lane"
        LaneHighlightTier.INACTIVE -> "Other lane"
    }
    val dirs = if (slot.directions.isEmpty()) {
        "unmarked"
    } else {
        slot.directions.joinToString(" and ") { it.name.lowercase().replace('_', ' ') }
    }
    return "Lane ${index + 1} of $total: $tierLabel ($dirs)"
}

// Target arrow height (0.87) / neighbor arrow height (0.58) = 1.50x exact proportion
internal const val ACTIVE_ARROW_HEIGHT_FRACTION = 0.87f
internal const val VALID_ARROW_HEIGHT_FRACTION = 0.70f
internal const val INACTIVE_ARROW_HEIGHT_FRACTION = 0.58f

@Preview(showBackground = true, backgroundColor = 0xFF0F172A, widthDp = 360)
@Composable
private fun FullLaneDiagramFourLanePreview() {
    val demoManeuver = remember { LaneDemoScenarios.buildFourLaneSecondFromRightRoute().maneuvers[1] }
    RahYabTheme(darkTheme = true) {
        CompositionLocalProvider(
            LocalAppLanguage provides AppLanguage.ENGLISH,
            LocalAppStrings provides stringsFor(AppLanguage.ENGLISH)
        ) {
            FullLaneDiagramOverlay(
                maneuver = demoManeuver,
                isNightMode = true,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
