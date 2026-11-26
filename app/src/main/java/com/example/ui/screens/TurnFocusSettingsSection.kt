package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.car.CarMapView
import com.example.data.preferences.UserSettings
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppStrings
import com.example.ui.RahYabViewModel

/**
 * F7 & A1: Settings card for Turn Focus Mode (3 beeps, focus zoom, blue lanes)
 * and Android Auto Car Map View zoom level (Close / Normal / Wide).
 */
@Composable
fun TurnFocusSettingsSection(
    settings: UserSettings,
    viewModel: RahYabViewModel,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val lang = settings.language
    val unit = settings.distanceUnit

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.turnFocusSectionTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // A1: Android Auto car map zoom view
            Text(strings.carMapViewLabel, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val carViews = listOf(
                    CarMapView.CLOSE to strings.carMapViewClose,
                    CarMapView.NORMAL to strings.carMapViewNormal,
                    CarMapView.WIDE to strings.carMapViewWide
                )
                carViews.forEach { (view, label) ->
                    FilterChip(
                        selected = settings.carMapView == view,
                        onClick = { viewModel.setCarMapView(view) },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("car_map_view_${view.code}_chip")
                    )
                }
            }

            HorizontalDivider()

            // F7: Turn Focus master toggle
            FocusToggleRow(
                label = strings.turnFocusModeToggle,
                checked = settings.turnFocusModeEnabled,
                onCheckedChange = { viewModel.setTurnFocusModeEnabled(it) },
                testTag = "switch_turn_focus_mode"
            )

            if (settings.turnFocusModeEnabled) {
                FocusToggleRow(
                    label = strings.turnFocusBeepsToggle,
                    checked = settings.turnFocusBeepsEnabled,
                    onCheckedChange = { viewModel.setTurnFocusBeepsEnabled(it) },
                    testTag = "switch_turn_focus_beeps"
                )

                Spacer(Modifier.height(2.dp))
                Text(strings.turnFocusCityDistanceLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(500, 800, 1000).forEach { meters ->
                        FilterChip(
                            selected = settings.turnFocusCityDistanceMeters == meters,
                            onClick = { viewModel.setTurnFocusCityDistanceMeters(meters) },
                            label = {
                                Text(
                                    text = formatFocusDistanceChip(meters, unit, lang),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("turn_focus_city_${meters}_chip")
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(strings.turnFocusHighwayDistanceLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(800, 1000, 1500).forEach { meters ->
                        FilterChip(
                            selected = settings.turnFocusHighwayDistanceMeters == meters,
                            onClick = { viewModel.setTurnFocusHighwayDistanceMeters(meters) },
                            label = {
                                Text(
                                    text = formatFocusDistanceChip(meters, unit, lang),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("turn_focus_highway_${meters}_chip")
                        )
                    }
                }

                FocusToggleRow(
                    label = strings.turnFocusWideHighwayToggle,
                    checked = settings.turnFocusWideHighwayEarlierEnabled,
                    onCheckedChange = { viewModel.setTurnFocusWideHighwayEarlierEnabled(it) },
                    testTag = "switch_turn_focus_wide_highway"
                )

                FocusToggleRow(
                    label = strings.turnFocusShortPhraseToggle,
                    checked = settings.turnFocusShortPhraseEnabled,
                    onCheckedChange = { viewModel.setTurnFocusShortPhraseEnabled(it) },
                    testTag = "switch_turn_focus_short_phrase"
                )
            }
        }
    }
}

@Composable
private fun FocusToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

internal fun formatFocusDistanceChip(
    meters: Int,
    unit: DistanceUnit,
    language: AppLanguage
): String {
    val raw = when (unit) {
        DistanceUnit.IMPERIAL -> {
            val miles = when (meters) {
                300 -> "0.2"
                500 -> "0.3"
                800 -> "0.5"
                1000 -> "0.6"
                1500 -> "0.9"
                else -> String.format(java.util.Locale.US, "%.1f", meters / 1609.344)
            }
            if (language == AppLanguage.PERSIAN) "$meters متر ($miles مایل)" else "${meters}m (${miles}mi)"
        }
        DistanceUnit.METRIC -> {
            if (language == AppLanguage.PERSIAN) "$meters متر" else "$meters m"
        }
    }
    return BilingualFormatter.localizeDigits(raw, language)
}
