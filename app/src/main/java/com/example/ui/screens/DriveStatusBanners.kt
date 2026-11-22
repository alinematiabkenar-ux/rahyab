package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.online.WeatherAlertItem
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed
import com.example.ui.theme.CockpitMidnight
import com.example.ui.theme.CyanBeacon

@Composable
internal fun SevereWeatherAlertBanner(
    alert: WeatherAlertItem,
    untilPrefix: String,
    routeSuffix: String,
    closeContentDescription: String,
    onDismiss: () -> Unit
) {
    val bgColor = if (alert.severity.equals("Extreme", ignoreCase = true)) {
        AlertRed.copy(alpha = 0.94f)
    } else {
        AlertAmber.copy(alpha = 0.96f)
    }
    val contentColor = if (alert.severity.equals("Extreme", ignoreCase = true)) Color.White else Color(0xFF0F172A)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("weather_alert_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = alert.title,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (alert.isAlongRoute) "${alert.title} $routeSuffix" else alert.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitleParts = buildList {
                    if (alert.area.isNotBlank()) add(alert.area)
                    if (alert.endsText.isNotBlank()) add("$untilPrefix ${alert.endsText}")
                }.joinToString(" • ")
                if (subtitleParts.isNotBlank()) {
                    Text(
                        text = subtitleParts,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.9f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("dismiss_weather_alert_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = closeContentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun TrafficLightAheadPill(
    distanceMeters: Double,
    unit: DistanceUnit,
    language: AppLanguage,
    contentDescription: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CockpitMidnight.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
        shadowElevation = 4.dp,
        modifier = Modifier.testTag("traffic_light_ahead_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_traffic_light_map),
                contentDescription = contentDescription,
                modifier = Modifier.size(width = 14.dp, height = 21.dp)
            )
            Text(
                text = BilingualFormatter.formatDistance(distanceMeters, unit, language),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
internal fun SimulationModeBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AlertRed,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
        modifier = modifier.testTag("simulation_mode_badge")
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
internal fun ApproximateLocationBanner(
    title: String,
    body: String,
    buttonLabel: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        border = BorderStroke(1.5.dp, AlertAmber),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("approximate_location_banner")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.GpsOff,
                    contentDescription = title,
                    tint = AlertAmber,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AlertAmber,
                    contentColor = Color(0xFF0F172A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("open_app_settings_button")
            ) {
                Text(
                    text = buttonLabel,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
internal fun CompassCalibrationBanner(
    message: String,
    closeContentDescription: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, CyanBeacon.copy(alpha = 0.65f)),
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("compass_calibration_banner")
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = null,
                tint = CyanBeacon,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_compass_calibration_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = closeContentDescription,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun NoMapPackPromptCard(
    onNavigateToMaps: () -> Unit,
    onEnableDevOregon: () -> Unit
) {
    val strings = com.example.i18n.LocalAppStrings.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.noMapPackLoaded, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(strings.searchNoPacksBody, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onNavigateToMaps, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("empty_pack_download_button")) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                    Text(strings.downloadOrImportAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                androidx.compose.material3.OutlinedButton(onClick = onEnableDevOregon, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("enable_dev_oregon_button")) {
                    Text(strings.enableDevOregonQuickAction, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun GpsSignalLostPill(label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AlertAmber,
        shadowElevation = 4.dp,
        modifier = Modifier.testTag("gps_signal_lost_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.GpsOff, contentDescription = label, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
            Text(text = label, color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}


