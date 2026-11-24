package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
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
import androidx.compose.ui.unit.sp
import com.example.data.preferences.UserSettings
import com.example.i18n.LocalAppStrings
import com.example.navigation.CyclingSpeedPreset
import com.example.navigation.TravelMode
import com.example.navigation.WalkingSpeedPreset

/**
 * M6: Travel mode settings section (default travel mode, walking speed, cycling speed,
 * and "Avoid hills when cycling").
 */
@Composable
internal fun SettingsTravelModeSectionCard(
    settings: UserSettings,
    onSelectDefaultMode: (TravelMode) -> Unit,
    onSelectWalkingSpeed: (WalkingSpeedPreset) -> Unit,
    onSelectCyclingSpeed: (CyclingSpeedPreset) -> Unit,
    onToggleAvoidHills: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_travel_mode_section")
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
                    imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.settingsSectionTravelModes,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Default travel mode
            Text(strings.settingsDefaultTravelMode, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modes = listOf(
                    Triple(TravelMode.DRIVE, strings.travelModeDrive, Icons.Filled.DirectionsCar),
                    Triple(TravelMode.WALK, strings.travelModeWalk, Icons.AutoMirrored.Filled.DirectionsWalk),
                    Triple(TravelMode.BIKE, strings.travelModeBike, Icons.AutoMirrored.Filled.DirectionsBike)
                )
                modes.forEach { (mode, label, icon) ->
                    FilterChip(
                        selected = settings.defaultTravelMode == mode,
                        onClick = { onSelectDefaultMode(mode) },
                        leadingIcon = {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("default_mode_chip_${mode.code}")
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            // Walking speed preset
            Text(strings.settingsWalkingSpeed, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val walkPresets = listOf(
                    WalkingSpeedPreset.SLOW to strings.settingsWalkSlow,
                    WalkingSpeedPreset.NORMAL to strings.settingsWalkNormal,
                    WalkingSpeedPreset.FAST to strings.settingsWalkFast
                )
                walkPresets.forEach { (preset, label) ->
                    FilterChip(
                        selected = settings.walkingSpeedPreset == preset,
                        onClick = { onSelectWalkingSpeed(preset) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("walk_speed_chip_${preset.name.lowercase()}")
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            // Cycling speed preset
            Text(strings.settingsCyclingSpeed, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val bikePresets = listOf(
                    CyclingSpeedPreset.RELAXED to strings.settingsBikeRelaxed,
                    CyclingSpeedPreset.NORMAL to strings.settingsBikeNormal,
                    CyclingSpeedPreset.FAST to strings.settingsBikeFast
                )
                bikePresets.forEach { (preset, label) ->
                    FilterChip(
                        selected = settings.cyclingSpeedPreset == preset,
                        onClick = { onSelectCyclingSpeed(preset) },
                        label = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bike_speed_chip_${preset.name.lowercase()}")
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = strings.settingsAvoidHillsCycling,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.avoidHillsWhenCycling,
                    onCheckedChange = onToggleAvoidHills,
                    modifier = Modifier.testTag("switch_avoid_hills_cycling")
                )
            }
        }
    }
}
