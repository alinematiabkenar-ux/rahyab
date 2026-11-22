package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.online.NetworkState
import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.ActiveNavigationState
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertSafeGreen
import com.example.ui.theme.CyanBeacon
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
internal fun DriveControlRail(
    navState: ActiveNavigationState,
    settings: UserSettings,
    networkState: NetworkState,
    isSimulationActive: Boolean,
    simulationSpeed: Int,
    mapCameraBearingDegrees: Float,
    isHeadingUpMode: Boolean,
    isCameraFollowing: Boolean,
    onTriggerSimulatedWrongTurn: () -> Unit,
    onOpenCloudStatus: () -> Unit,
    onToggleSimulation: () -> Unit,
    onCycleSimulationSpeed: () -> Unit,
    onResetSimulationSpeed: () -> Unit,
    onRepeatVoice: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggle3DView: () -> Unit,
    onToggleCompassMode: () -> Unit,
    onRecenter: () -> Unit,
    onOpenReportSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Simulate Wrong Turn button (only visible when Simulate Drive is active)
        if (navState.isNavigating && navState.isSimulatingDrive) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AlertRed,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .clickable(onClick = onTriggerSimulatedWrongTurn)
                    .testTag("simulate_wrong_turn_button")
            ) {
                Text(
                    text = strings.simulateWrongTurn,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        // Small online/offline cloud status indicator
        val isOnlineActive = networkState.isOnline && settings.onlineFeaturesEnabled
        SmallCockpitButton(
            icon = if (isOnlineActive) Icons.Filled.Cloud else Icons.Filled.CloudOff,
            contentDescription = if (!settings.onlineFeaturesEnabled) {
                strings.cloudStatusDisabled
            } else if (networkState.isWifi) {
                strings.cloudStatusOnlineWifi
            } else if (networkState.isOnline) {
                strings.cloudStatusOnlineMobile
            } else {
                strings.cloudStatusOffline
            },
            onClick = onOpenCloudStatus,
            testTag = "cloud_status_button",
            tint = if (isOnlineActive) AlertSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // One-tap Sygic-style Simulate button (red when active)
        SimulateToggleButton(
            isSimulating = isSimulationActive,
            contentDescription = strings.simulateButtonLabel,
            onClick = onToggleSimulation
        )

        // Simulation Speed button (1x..32x) — visible ONLY while simulation is on
        if (isSimulationActive) {
            SimulationSpeedButton(
                speedMultiplier = simulationSpeed,
                language = language,
                contentDescription = strings.simulationSpeedLabel,
                onTapCycle = onCycleSimulationSpeed,
                onLongPressReset = onResetSimulationSpeed
            )
        }

        // Repeat last spoken instruction button during navigation
        if (navState.isNavigating) {
            SmallCockpitButton(
                icon = Icons.Filled.Replay,
                contentDescription = strings.repeatVoice,
                onClick = onRepeatVoice,
                testTag = "repeat_voice_button"
            )
        }

        // Voice mute/unmute
        SmallCockpitButton(
            icon = if (settings.voiceGuidanceEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = if (settings.voiceGuidanceEnabled) strings.muteVoice else strings.unmuteVoice,
            onClick = onToggleVoice,
            testTag = "toggle_voice_button"
        )

        // 3D / 2D camera toggle
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(50.dp)
                .clickable(onClick = onToggle3DView)
                .testTag("toggle_3d_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (settings.is3DView) strings.view3D else strings.view2D,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Interactive Compass Button (needle points to geographic North; tap toggles North-Up / Heading-Up)
        CompassControlButton(
            mapBearingDegrees = mapCameraBearingDegrees,
            isHeadingUpMode = isHeadingUpMode,
            contentDescription = if (isHeadingUpMode) strings.compassNorthUp else strings.compassHeadingUp,
            onClick = onToggleCompassMode
        )

        // Recenter GPS (highlighted cyan whenever camera following is paused in any mode)
        SmallCockpitButton(
            icon = Icons.Filled.MyLocation,
            contentDescription = strings.recenter,
            onClick = onRecenter,
            highlight = !isCameraFollowing,
            testTag = "recenter_gps_button"
        )

        // Large Waze-style One-Tap Report Button (8 report types)
        FloatingActionButton(
            onClick = onOpenReportSheet,
            containerColor = AlertAmber,
            contentColor = Color(0xFF0F172A),
            shape = CircleShape,
            modifier = Modifier
                .size(62.dp)
                .testTag("open_report_fab")
        ) {
            Icon(
                imageVector = Icons.Filled.ReportProblem,
                contentDescription = strings.reportButton,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
internal fun SimulateToggleButton(
    isSimulating: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (isSimulating) AlertRed else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = if (isSimulating) BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(50.dp)
            .clickable(onClick = onClick)
            .testTag("simulate_toggle_button")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.DirectionsCar,
                contentDescription = contentDescription,
                tint = if (isSimulating) Color.White else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.Center)
                    .padding(bottom = 2.dp)
            )
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (isSimulating) Color.White else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 5.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SimulationSpeedButton(
    speedMultiplier: Int,
    language: AppLanguage,
    contentDescription: String,
    onTapCycle: () -> Unit,
    onLongPressReset: () -> Unit
) {
    val label = if (language == AppLanguage.PERSIAN) {
        "${BilingualFormatter.formatNumber(speedMultiplier, AppLanguage.PERSIAN)}x"
    } else {
        "${speedMultiplier}x"
    }
    Surface(
        shape = CircleShape,
        color = AlertRed,
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.65f)),
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClickLabel = contentDescription,
                onClick = onTapCycle,
                onLongClick = onLongPressReset
            )
            .testTag("simulate_speed_button")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                fontFamily = JetBrainsMonoFamily
            )
        }
    }
}

@Composable
internal fun CompassControlButton(
    mapBearingDegrees: Float,
    isHeadingUpMode: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = if (isHeadingUpMode) {
            BorderStroke(2.dp, CyanBeacon)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        },
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(50.dp)
            .clickable(onClick = onClick)
            .testTag("compass_mode_button")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer { rotationZ = -mapBearingDegrees }
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val tipY = size.height * 0.12f
                val tailY = size.height * 0.88f
                val halfWidth = size.width * 0.17f

                val northPath = Path().apply {
                    moveTo(cx, tipY)
                    lineTo(cx + halfWidth, cy)
                    lineTo(cx - halfWidth, cy)
                    close()
                }
                drawPath(path = northPath, color = AlertRed)

                val southPath = Path().apply {
                    moveTo(cx, tailY)
                    lineTo(cx + halfWidth, cy)
                    lineTo(cx - halfWidth, cy)
                    close()
                }
                drawPath(path = southPath, color = Color(0xFF94A3B8))

                drawCircle(
                    color = Color.White,
                    radius = size.minDimension * 0.08f,
                    center = Offset(cx, cy)
                )
            }
            Text(
                text = "N",
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                color = AlertRed,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 1.dp)
            )
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = contentDescription,
                tint = Color.Transparent,
                modifier = Modifier.size(1.dp)
            )
        }
    }
}

@Composable
internal fun SmallCockpitButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    highlight: Boolean = false,
    tint: Color? = null
) {
    Surface(
        shape = CircleShape,
        color = if (highlight) CyanBeacon else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
        modifier = Modifier.size(50.dp).clickable(onClick = onClick).testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint ?: if (highlight) Color(0xFF0F172A) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

