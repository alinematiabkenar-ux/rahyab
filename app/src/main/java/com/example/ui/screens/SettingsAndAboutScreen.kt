package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.i18n.AppLanguage
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppStrings
import com.example.i18n.MapThemeMode
import com.example.ui.RahYabViewModel
import com.example.ui.theme.AlertAmber

@Composable
fun SettingsScreen(
    viewModel: RahYabViewModel,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isPersianTtsAvailable by viewModel.isPersianTtsAvailable.collectAsStateWithLifecycle()

    var baseUrlDraft by remember { mutableStateOf(settings.packBaseUrl) }
    LaunchedEffect(settings.packBaseUrl) {
        if (baseUrlDraft.isEmpty() && settings.packBaseUrl.isNotEmpty()) {
            baseUrlDraft = settings.packBaseUrl
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = strings.settingsTitle,
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // 1. Language, Units, and Day/Night Theme (Live switching without restart)
        item {
            SettingsSectionCard(
                title = strings.sectionLanguageUnits,
                icon = Icons.Filled.Language
            ) {
                Text(strings.languageLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.language == AppLanguage.ENGLISH,
                        onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) },
                        label = { Text(strings.languageEnglish) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("lang_english_chip")
                    )
                    FilterChip(
                        selected = settings.language == AppLanguage.PERSIAN,
                        onClick = { viewModel.setLanguage(AppLanguage.PERSIAN) },
                        label = { Text(strings.languagePersian) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("lang_persian_chip")
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(strings.unitsLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.distanceUnit == DistanceUnit.IMPERIAL,
                        onClick = { viewModel.setDistanceUnit(DistanceUnit.IMPERIAL) },
                        label = { Text(strings.unitsImperial) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("units_imperial_chip")
                    )
                    FilterChip(
                        selected = settings.distanceUnit == DistanceUnit.METRIC,
                        onClick = { viewModel.setDistanceUnit(DistanceUnit.METRIC) },
                        label = { Text(strings.unitsMetric) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("units_metric_chip")
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(strings.themeModeLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.themeMode == MapThemeMode.AUTO,
                        onClick = { viewModel.setThemeMode(MapThemeMode.AUTO) },
                        label = { Text(strings.themeAuto) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = settings.themeMode == MapThemeMode.DAY,
                        onClick = { viewModel.setThemeMode(MapThemeMode.DAY) },
                        label = { Text(strings.themeDay) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = settings.themeMode == MapThemeMode.NIGHT,
                        onClick = { viewModel.setThemeMode(MapThemeMode.NIGHT) },
                        label = { Text(strings.themeNight) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2. Bilingual Voice Guidance & Persian TTS Install Guide
        item {
            SettingsSectionCard(
                title = strings.sectionVoiceGuidance,
                icon = Icons.Filled.RecordVoiceOver
            ) {
                SettingsToggleRow(
                    label = strings.voiceGuidanceEnabled,
                    checked = settings.voiceGuidanceEnabled,
                    onCheckedChange = { viewModel.setVoiceEnabled(it) },
                    testTag = "switch_voice_guidance"
                )
                SettingsToggleRow(
                    label = strings.speedWarningAudio,
                    checked = settings.speedWarningAudioEnabled,
                    onCheckedChange = { viewModel.setSpeedAudioEnabled(it) },
                    testTag = "switch_speed_audio"
                )

                // Persian TTS Missing Notice & Direct Android TTS Settings Shortcut
                if (settings.language == AppLanguage.PERSIAN && !isPersianTtsAvailable) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = AlertAmber.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, AlertAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = AlertAmber)
                                Text(
                                    text = strings.persianTtsMissingTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = strings.persianTtsMissingBody,
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Filled.SettingsVoice, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(strings.openTtsSettings)
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.testVoiceGuidance() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("test_voice_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.testVoiceButton)
                }
            }
        }

        // 3. Static Map Pack Host Base URL (manifest.json)
        item {
            SettingsSectionCard(
                title = strings.sectionPackServer,
                icon = Icons.Filled.CloudDownload
            ) {
                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = { baseUrlDraft = it },
                    label = { Text(strings.baseUrlLabel) },
                    placeholder = { Text(strings.baseUrlPlaceholder) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pack_base_url_input")
                )
                Button(
                    onClick = { viewModel.savePackBaseUrl(baseUrlDraft) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_base_url_button")
                ) {
                    Text(strings.saveBaseUrl)
                }
            }
        }

        // 4. Routing Defaults
        item {
            SettingsSectionCard(
                title = strings.sectionRoutingDefaults,
                icon = Icons.Filled.Route
            ) {
                SettingsToggleRow(
                    label = strings.avoidTolls,
                    checked = settings.avoidTolls,
                    onCheckedChange = { viewModel.setAvoidTolls(it) },
                    testTag = "switch_avoid_tolls"
                )
                SettingsToggleRow(
                    label = strings.avoidHighways,
                    checked = settings.avoidHighways,
                    onCheckedChange = { viewModel.setAvoidHighways(it) },
                    testTag = "switch_avoid_highways"
                )
                SettingsToggleRow(
                    label = strings.avoidFerries,
                    checked = settings.avoidFerries,
                    onCheckedChange = { viewModel.setAvoidFerries(it) },
                    testTag = "switch_avoid_ferries"
                )
            }
        }

        // 5. Temporary Phase 1 Developer Testing Option (Oregon Public Online Sources)
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.5.dp, AlertAmber.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Build, contentDescription = null, tint = AlertAmber)
                        Text(strings.sectionDevTesting, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(strings.devOnlineOregonTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = strings.devOnlineOregonSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.devOnlineOregonMode,
                            onCheckedChange = { viewModel.setDevOnlineOregonMode(it) },
                            modifier = Modifier.testTag("switch_dev_online_oregon")
                        )
                    }
                }
            }
        }

        // 6. Required Licenses & Attribution (About RahYab)
        item {
            SettingsSectionCard(
                title = strings.sectionAboutLicenses,
                icon = Icons.Filled.Info
            ) {
                Text(
                    text = "${strings.appName} — ${strings.tagline}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                Text(strings.osmAttributionFull, style = MaterialTheme.typography.bodyMedium)
                Text(strings.overtureAttributionFull, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = strings.openSourceLicensesBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
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
