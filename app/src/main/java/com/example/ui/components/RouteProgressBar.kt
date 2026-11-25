package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.RouteProgressEvent
import com.example.navigation.RouteProgressEventKind
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed
import com.example.ui.theme.CockpitMidnight
import com.example.ui.theme.CyanBeacon
import com.example.ui.theme.HighwaySignGreen
import kotlinx.coroutines.delay

/**
 * V2: Sygic-style thin vertical route progress bar on the right edge (DRIVE & BIKE only).
 * Bottom represents the current position (`0f`); top represents the destination (`1f`).
 * Tapping any event icon shows its localized label and distance for 3 seconds.
 */
@Composable
fun RouteProgressBar(
    events: List<RouteProgressEvent>,
    unit: DistanceUnit,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    var selectedEvent by remember { mutableStateOf<RouteProgressEvent?>(null) }

    LaunchedEffect(selectedEvent?.id) {
        if (selectedEvent != null) {
            delay(3_000L)
            selectedEvent = null
        }
    }

    Box(
        modifier = modifier
            .testTag("route_progress_bar")
            .semantics { contentDescription = strings.routeProgressBarDesc },
        contentAlignment = Alignment.CenterEnd
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .width(30.dp)
                .fillMaxHeight()
        ) {
            val iconSize = 22.dp
            val usableHeight = (maxHeight - iconSize).coerceAtLeast(1.dp)

            // Vertical capsule track
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(8.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(CockpitMidnight.copy(alpha = 0.78f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .width(4.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF38BDF8), CyanBeacon)
                            )
                        )
                )
            }

            // Current position anchor dot at bottom (fraction = 0f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.5.dp, CyanBeacon, CircleShape)
            )

            // Route event icons positioned from bottom (0f) to top (1f)
            for (ev in events) {
                val yOffset = usableHeight * (1f - ev.fractionAlongRemaining.coerceIn(0f, 1f))
                val (badgeColor, iconVec) = eventStyle(ev.kind)
                val label = resolveEventLabel(ev, strings, language)

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = yOffset)
                        .size(28.dp)
                        .clickable { selectedEvent = ev }
                        .testTag("route_progress_event_${ev.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .clip(CircleShape)
                            .background(badgeColor)
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = iconVec,
                            contentDescription = label,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    if (ev.clusterCount > 1) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(CockpitMidnight)
                                .border(1.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = BilingualFormatter.formatNumber(ev.clusterCount, language),
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                lineHeight = 8.sp
                            )
                        }
                    }
                }
            }
        }

        // 3-second floating distance tooltip shown to the start/left of the bar when tapped
        AnimatedVisibility(
            visible = selectedEvent != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 36.dp)
        ) {
            selectedEvent?.let { ev ->
                val distText = BilingualFormatter.formatDistance(ev.distanceMeters, unit, language)
                val label = resolveEventLabel(ev, strings, language)
                val countSuffix = if (ev.clusterCount > 1) " (×${BilingualFormatter.formatNumber(ev.clusterCount, language)})" else ""
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CockpitMidnight.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, CyanBeacon.copy(alpha = 0.7f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.testTag("route_progress_tooltip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$label$countSuffix • $distText",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun eventStyle(kind: RouteProgressEventKind): Pair<Color, ImageVector> = when (kind) {
    RouteProgressEventKind.SPEED_CAMERA -> AlertRed to Icons.Filled.CameraAlt
    RouteProgressEventKind.RED_LIGHT_CAMERA -> AlertRed to Icons.Filled.Traffic
    RouteProgressEventKind.RAILROAD_CROSSING -> AlertAmber to Icons.Filled.Train
    RouteProgressEventKind.STEEP_GRADE -> Color(0xFFF97316) to Icons.Filled.Terrain
    RouteProgressEventKind.SCHOOL_ZONE -> Color(0xFFEAB308) to Icons.Filled.School
    RouteProgressEventKind.HIGHWAY_EXIT -> HighwaySignGreen to Icons.AutoMirrored.Filled.CallSplit
    RouteProgressEventKind.DESTINATION -> CockpitMidnight to Icons.Filled.Flag
}

private fun resolveEventLabel(
    event: RouteProgressEvent,
    strings: AppStrings,
    language: AppLanguage
): String {
    val custom = if (language == AppLanguage.PERSIAN) event.labelFa else event.labelEn
    if (custom.isNotBlank()) return custom
    return when (event.kind) {
        RouteProgressEventKind.SPEED_CAMERA -> strings.alertSpeedCamera
        RouteProgressEventKind.RED_LIGHT_CAMERA -> strings.alertRedLightCamera
        RouteProgressEventKind.RAILROAD_CROSSING -> strings.alertRailroad
        RouteProgressEventKind.STEEP_GRADE -> strings.alertToggleSteepGrade
        RouteProgressEventKind.SCHOOL_ZONE -> strings.alertSchoolZone
        RouteProgressEventKind.HIGHWAY_EXIT -> strings.routeEventHighwayExit
        RouteProgressEventKind.DESTINATION -> strings.routeEventDestination
    }
}
