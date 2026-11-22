package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ReportType
import com.example.data.online.NetworkState
import com.example.data.online.OnlineSourceState
import com.example.data.online.OnlineSourcesSummary
import com.example.data.preferences.UserSettings
import com.example.data.search.PlaceSearchResult
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.ui.components.OneTapReportGrid
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertSafeGreen
import com.example.ui.theme.CockpitMidnight
import com.example.ui.theme.CyanBeacon
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
internal fun ActiveNavigationBottomBar(
    destinationName: String,
    destinationAddress: String,
    remainingDistanceMeters: Double,
    remainingTimeSeconds: Double,
    hasArrived: Boolean,
    unit: DistanceUnit,
    onEndNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CockpitMidnight.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, CyanBeacon.copy(alpha = 0.45f)),
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_nav_bottom_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (hasArrived) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.arrivedTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = AlertSafeGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = destinationAddress.ifBlank { destinationName.ifBlank { strings.arrivedSubtitle } },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = BilingualFormatter.formatDuration(remainingTimeSeconds, language),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = if (language == AppLanguage.PERSIAN) {
                                    MaterialTheme.typography.titleLarge.fontFamily
                                } else {
                                    JetBrainsMonoFamily
                                },
                                fontWeight = FontWeight.Bold
                            ),
                            color = AlertSafeGreen
                        )
                        Text(
                            text = "${BilingualFormatter.formatDistance(remainingDistanceMeters, unit, language)} • ${strings.arrivalLabel} ${BilingualFormatter.formatArrivalTime(remainingTimeSeconds, language)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Button(
                onClick = onEndNavigation,
                colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("end_navigation_button")
            ) {
                Text(strings.endNavigation, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveModalsAndDialogs(
    showReportSheet: Boolean,
    onDismissReportSheet: () -> Unit,
    onSubmitReport: (ReportType) -> Unit,
    externalMatchChoices: List<PlaceSearchResult>,
    onSelectExternalMatch: (PlaceSearchResult) -> Unit,
    onDismissExternalMatches: () -> Unit,
    showAttributionDialog: Boolean,
    onDismissAttributionDialog: () -> Unit,
    showCloudStatusDialog: Boolean,
    onDismissCloudStatusDialog: () -> Unit,
    settings: UserSettings,
    networkState: NetworkState,
    onlineSources: OnlineSourcesSummary
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current

    // One-Tap Driving Report Bottom Sheet (All 8 Waze-style report types)
    if (showReportSheet) {
        ModalBottomSheet(onDismissRequest = onDismissReportSheet) {
            OneTapReportGrid(
                onSelectReport = { reportType ->
                    onSubmitReport(reportType)
                    onDismissReportSheet()
                },
                onClose = onDismissReportSheet
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Short list picker when a shared address / map link matches several offline places
    if (externalMatchChoices.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = onDismissExternalMatches) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("external_link_matches_sheet"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = strings.externalLinkMatchesTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                externalMatchChoices.forEach { place ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectExternalMatch(place) }
                            .testTag("external_match_${place.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(place.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (place.formattedAddress.isNotBlank()) {
                                    Text(
                                        place.formattedAddress,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (place.distanceMeters > 0.0) {
                                Text(
                                    text = BilingualFormatter.formatDistance(place.distanceMeters, settings.distanceUnit, language),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Required Map Licenses Attribution Dialog
    if (showAttributionDialog) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismissAttributionDialog,
            title = { Text(strings.aboutTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.osmAttributionFull, style = MaterialTheme.typography.bodyMedium)
                    Text(strings.overtureAttributionFull, style = MaterialTheme.typography.bodyMedium)
                    Text(strings.overtureAddressesAttributionFull, style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://docs.overturemaps.org/attribution/")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    ) {
                        Text(strings.overtureAttributionLinkLabel)
                    }
                    Text(strings.openSourceLicensesBody, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissAttributionDialog) { Text(strings.close) }
            }
        )
    }

    // Online Sources Status Dialog (tapped from map cloud indicator)
    if (showCloudStatusDialog) {
        val overallStatusText = when {
            !settings.onlineFeaturesEnabled -> strings.cloudStatusDisabled
            networkState.isWifi -> strings.cloudStatusOnlineWifi
            networkState.isOnline -> strings.cloudStatusOnlineMobile
            else -> strings.cloudStatusOffline
        }
        AlertDialog(
            onDismissRequest = onDismissCloudStatusDialog,
            title = { Text(strings.cloudDialogTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = overallStatusText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (networkState.isOnline && settings.onlineFeaturesEnabled) AlertSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    OnlineSourceStatusRow(strings.onlineSourceWeatherName, onlineSources.weatherAlerts.state, onlineSources.weatherAlerts.detail, strings)
                    OnlineSourceStatusRow(strings.onlineSourceSearchName, onlineSources.onlineSearch.state, onlineSources.onlineSearch.detail, strings)
                    OnlineSourceStatusRow(strings.onlineSourceMapUpdatesName, onlineSources.mapUpdates.state, onlineSources.mapUpdates.detail, strings)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissCloudStatusDialog) { Text(strings.close) }
            }
        )
    }
}

@Composable
internal fun OnlineSourceStatusRow(
    name: String,
    state: OnlineSourceState,
    detail: String,
    strings: AppStrings
) {
    val (label, color) = when (state) {
        OnlineSourceState.ACTIVE -> strings.onlineSourceStateWorking to AlertSafeGreen
        OnlineSourceState.IDLE -> strings.onlineSourceStateReady to CyanBeacon
        OnlineSourceState.DISABLED -> strings.onlineSourceStateOff to Color.Gray
        OnlineSourceState.OFFLINE -> strings.onlineSourceStateOffline to Color.Gray
        OnlineSourceState.ERROR -> strings.onlineSourceStateUnavailable to AlertAmber
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
    }
}
