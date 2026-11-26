package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.local.ReportType
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.i18n.MapThemeMode
import com.example.navigation.RoutePreference
import com.example.ui.RahYabViewModel
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed

@Composable
fun SettingsScreen(
    viewModel: RahYabViewModel,
    onOpenDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isPersianTtsAvailable by viewModel.isPersianTtsAvailable.collectAsStateWithLifecycle()
    val activeReports by viewModel.activeUserReports.collectAsStateWithLifecycle()
    val statePacks by viewModel.statePacks.collectAsStateWithLifecycle()

    var githubOwnerDraft by remember { mutableStateOf(settings.githubOwner) }
    var githubRepoDraft by remember { mutableStateOf(settings.githubRepo) }
    var baseUrlDraft by remember { mutableStateOf(settings.packBaseUrl) }

    LaunchedEffect(settings.githubOwner) {
        githubOwnerDraft = settings.githubOwner
    }
    LaunchedEffect(settings.githubRepo) {
        githubRepoDraft = settings.githubRepo
    }
    LaunchedEffect(settings.packBaseUrl) {
        baseUrlDraft = settings.packBaseUrl
    }

    // SAF launchers for Exporting & Importing User Reports (Phase 4)
    val exportReportsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportReportsToUri(uri) }

    val importReportsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importReportsFromUri(uri) }

    // SAF launchers for Full Settings + Saved Places Backup & Phone Transfer (Final Step)
    val exportFullBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportFullBackupToUri(uri) }

    val importFullBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importFullBackupFromUri(uri) }

    val installedDataBuildVersion = remember(statePacks) { viewModel.getInstalledDataBuildVersion() }

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

                Spacer(Modifier.height(4.dp))
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

                Spacer(Modifier.height(4.dp))
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

        // 1B. Turn Focus Mode & Android Auto Car Map View
        item {
            TurnFocusSettingsSection(settings = settings, viewModel = viewModel)
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
                if (!settings.turnFocusModeEnabled) {
                    SettingsToggleRow(
                        label = strings.laneChangeBeepsToggle,
                        checked = settings.laneChangeBeepsEnabled,
                        onCheckedChange = { viewModel.setLaneChangeBeepsEnabled(it) },
                        testTag = "switch_lane_change_beeps"
                    )
                    if (settings.laneChangeBeepsEnabled) {
                        Text(strings.laneChangeTimingLabel, style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val timingChoices = listOf(
                                com.example.navigation.LaneChangeTiming.EARLY to strings.laneChangeTimingEarly,
                                com.example.navigation.LaneChangeTiming.NORMAL to strings.laneChangeTimingNormal,
                                com.example.navigation.LaneChangeTiming.LATE to strings.laneChangeTimingLate
                            )
                            timingChoices.forEach { (timing, label) ->
                                FilterChip(
                                    selected = settings.laneChangeTiming == timing,
                                    onClick = { viewModel.setLaneChangeTiming(timing) },
                                    label = { Text(label) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("lane_timing_${timing.code}_chip")
                                )
                            }
                        }
                        SettingsToggleRow(
                            label = strings.lastChanceBeepToggle,
                            checked = settings.lastChanceBeepEnabled,
                            onCheckedChange = { viewModel.setLastChanceBeepEnabled(it) },
                            testTag = "switch_last_chance_beep"
                        )
                    }
                }

                if (settings.language == AppLanguage.PERSIAN) {
                    SettingsToggleRow(
                        label = strings.diagUseEnglishVoiceInPersian,
                        checked = settings.useEnglishVoiceInPersianMode,
                        onCheckedChange = { viewModel.setUseEnglishVoiceInPersianMode(it) },
                        testTag = "switch_english_voice_in_persian"
                    )
                }

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
                                text = strings.diagPersianInstallSteps,
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

        // 3. Phase 3: Lane Guidance, Junction Previews, Speed Threshold & Individual Road Alerts
        item {
            SettingsSectionCard(
                title = strings.sectionRoadAlerts,
                icon = Icons.Filled.NotificationsActive
            ) {
                SettingsToggleRow(
                    label = strings.settingsJunctionViewTitle,
                    checked = settings.junctionPreviewEnabled,
                    onCheckedChange = { viewModel.setJunctionPreviewEnabled(it) },
                    testTag = "switch_junction_preview"
                )
                SettingsToggleRow(
                    label = strings.laneHighlightOnMapToggle,
                    checked = settings.laneHighlightOnMapEnabled,
                    onCheckedChange = { viewModel.setLaneHighlightOnMapEnabled(it) },
                    testTag = "switch_lane_highlight_on_map"
                )
                if (settings.laneHighlightOnMapEnabled) {
                    Text(strings.laneHighlightColorLabel, style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val colorChoices = listOf(
                            Triple(com.example.map.LaneHighlightColor.RED, strings.laneColorRed, Color(0xFFE53935)),
                            Triple(com.example.map.LaneHighlightColor.BLUE, strings.laneColorBlue, Color(0xFF1E88E5)),
                            Triple(com.example.map.LaneHighlightColor.GREEN, strings.laneColorGreen, Color(0xFF43A047))
                        )
                        colorChoices.forEach { (choice, label, swatch) ->
                            FilterChip(
                                selected = settings.laneHighlightColor == choice,
                                onClick = { viewModel.setLaneHighlightColor(choice) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(swatch)
                                    )
                                },
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("lane_color_${choice.code}_chip")
                            )
                        }
                    }
                }

                Text(strings.speedWarningThresholdLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val unitLabel = if (settings.distanceUnit == DistanceUnit.IMPERIAL) strings.speedUnitMph else strings.speedUnitKmh
                    listOf(0, 5, 10).forEach { thresholdMph ->
                        val shownVal = BilingualFormatter.convertMphToUnit(thresholdMph, settings.distanceUnit)
                        val label = "+${BilingualFormatter.formatNumber(shownVal, language)} $unitLabel"
                        FilterChip(
                            selected = settings.speedWarningThresholdMph == thresholdMph,
                            onClick = { viewModel.setSpeedWarningThresholdMph(thresholdMph) },
                            label = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("speed_threshold_$thresholdMph")
                        )
                    }
                }

                HorizontalDivider()
                SettingsToggleRow(
                    label = strings.speedLimitDropWarningToggle,
                    checked = settings.alertSpeedDropEnabled,
                    onCheckedChange = { viewModel.setAlertSpeedDropEnabled(it) },
                    testTag = "switch_alert_speed_drop"
                )
                SettingsToggleRow(
                    label = strings.alertToggleSharpCurve,
                    checked = settings.alertSharpCurveEnabled,
                    onCheckedChange = { viewModel.setAlertSharpCurveEnabled(it) },
                    testTag = "switch_alert_sharp_curve"
                )
                SettingsToggleRow(
                    label = strings.alertToggleTollBooth,
                    checked = settings.alertTollBoothEnabled,
                    onCheckedChange = { viewModel.setAlertTollBoothEnabled(it) },
                    testTag = "switch_alert_toll_booth"
                )
                SettingsToggleRow(
                    label = strings.alertToggleRailroad,
                    checked = settings.alertRailroadEnabled,
                    onCheckedChange = { viewModel.setAlertRailroadEnabled(it) },
                    testTag = "switch_alert_railroad"
                )
                SettingsToggleRow(
                    label = strings.alertToggleSteepGrade,
                    checked = settings.alertSteepGradeEnabled,
                    onCheckedChange = { viewModel.setAlertSteepGradeEnabled(it) },
                    testTag = "switch_alert_steep_grade"
                )
                SettingsToggleRow(
                    label = strings.alertToggleSchoolZone,
                    checked = settings.alertSchoolZoneEnabled,
                    onCheckedChange = { viewModel.setAlertSchoolZoneEnabled(it) },
                    testTag = "switch_alert_school_zone"
                )
                SettingsToggleRow(
                    label = strings.alertToggleSpeedCamera,
                    checked = settings.alertSpeedCameraEnabled,
                    onCheckedChange = { viewModel.setAlertSpeedCameraEnabled(it) },
                    testTag = "switch_alert_speed_camera"
                )
                SettingsToggleRow(
                    label = strings.alertToggleRedLightCamera,
                    checked = settings.alertRedLightCameraEnabled,
                    onCheckedChange = { viewModel.setAlertRedLightCameraEnabled(it) },
                    testTag = "switch_alert_red_light_camera"
                )
                SettingsToggleRow(
                    label = strings.alertToggleSpeedBump,
                    checked = settings.alertSpeedBumpEnabled,
                    onCheckedChange = { viewModel.setAlertSpeedBumpEnabled(it) },
                    testTag = "switch_alert_speed_bump"
                )
                HorizontalDivider()
                Text(
                    text = strings.sectionTrafficLightsStopSigns,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                SettingsToggleRow(
                    label = strings.showTrafficLightsToggle,
                    checked = settings.showTrafficLights,
                    onCheckedChange = { viewModel.setShowTrafficLights(it) },
                    testTag = "switch_show_traffic_lights"
                )
                SettingsToggleRow(
                    label = strings.alertToggleTrafficLight,
                    checked = settings.alertTrafficLightEnabled,
                    onCheckedChange = { viewModel.setAlertTrafficLightEnabled(it) },
                    testTag = "switch_alert_traffic_light"
                )
                SettingsToggleRow(
                    label = strings.showStopSignsToggle,
                    checked = settings.showStopSignsOnMap,
                    onCheckedChange = { viewModel.setShowStopSignsOnMap(it) },
                    testTag = "switch_show_stop_signs_map"
                )
                SettingsToggleRow(
                    label = strings.alertToggleStopSign,
                    checked = settings.alertStopSignEnabled,
                    onCheckedChange = { viewModel.setAlertStopSignEnabled(it) },
                    testTag = "switch_alert_stop_sign"
                )
                HorizontalDivider()
                SettingsToggleRow(
                    label = strings.show3DBuildingsToggle,
                    checked = settings.show3DBuildings,
                    onCheckedChange = { viewModel.setShow3DBuildings(it) },
                    testTag = "switch_show_3d_buildings"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = strings.positionMarkerStyleLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.positionMarkerStyle != "car",
                            onClick = { viewModel.setPositionMarkerStyle("arrow") },
                            label = { Text(strings.positionMarkerArrow) },
                            modifier = Modifier.testTag("chip_marker_arrow")
                        )
                        FilterChip(
                            selected = settings.positionMarkerStyle == "car",
                            onClick = { viewModel.setPositionMarkerStyle("car") },
                            label = { Text(strings.positionMarkerCar) },
                            modifier = Modifier.testTag("chip_marker_car")
                        )
                    }
                }
            }
        }

        // 3b. Online features (no API keys, fully optional, safe offline)
        item {
            SettingsSectionCard(
                title = strings.sectionOnlineFeatures,
                icon = Icons.Filled.Cloud
            ) {
                SettingsToggleRow(
                    label = strings.onlineFeaturesMainSwitch,
                    checked = settings.onlineFeaturesEnabled,
                    onCheckedChange = { viewModel.setOnlineFeaturesEnabled(it) },
                    testTag = "switch_online_features_master"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (settings.onlineFeaturesEnabled) {
                    SettingsToggleRow(
                        label = strings.onlineWeatherAlertsSwitch,
                        checked = settings.onlineWeatherAlertsEnabled,
                        onCheckedChange = { viewModel.setOnlineWeatherAlertsEnabled(it) },
                        testTag = "switch_online_weather_alerts"
                    )
                    SettingsToggleRow(
                        label = strings.onlineSearchFallbackSwitch,
                        checked = settings.onlineSearchFallbackEnabled,
                        onCheckedChange = { viewModel.setOnlineSearchFallbackEnabled(it) },
                        testTag = "switch_online_search_fallback"
                    )
                    SettingsToggleRow(
                        label = strings.autoCheckMapUpdatesSwitch,
                        checked = settings.autoCheckMapUpdatesEnabled,
                        onCheckedChange = { viewModel.setAutoCheckMapUpdatesEnabled(it) },
                        testTag = "switch_auto_check_map_updates"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsToggleRow(
                    label = strings.wifiOnlyLargeDownloadsSwitch,
                    checked = settings.wifiOnlyLargeDownloads,
                    onCheckedChange = { viewModel.setWifiOnlyLargeDownloads(it) },
                    testTag = "switch_wifi_only_large_downloads"
                )
                SettingsToggleRow(
                    label = strings.autoUpdateMapsOnWifiSwitch,
                    checked = settings.autoUpdateMapsOnWifi,
                    onCheckedChange = { viewModel.setAutoUpdateMapsOnWifi(it) },
                    testTag = "switch_auto_update_maps_on_wifi"
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = strings.onlineLocationDisclosureTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.onlineLocationDisclosureBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // F4: Parked Car & Optional Car Bluetooth Settings
        item {
            SettingsParkingSectionCard(
                settings = settings,
                onToggleSkipAtHome = { viewModel.setSkipParkingAtHome(it) },
                onSelectCarBluetoothDevice = { addr, name -> viewModel.setCarBluetoothDevice(addr, name) }
            )
        }

        // 4. Phase 5: GitHub Releases Map Pack Source + Optional Custom Base URL
        item {
            SettingsSectionCard(
                title = strings.sectionPackServer,
                icon = Icons.Filled.CloudDownload
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = githubOwnerDraft,
                        onValueChange = { githubOwnerDraft = it },
                        label = { Text(strings.githubOwnerLabel) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("github_owner_input")
                    )
                    OutlinedTextField(
                        value = githubRepoDraft,
                        onValueChange = { githubRepoDraft = it },
                        label = { Text(strings.githubRepoLabel) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("github_repo_input")
                    )
                }
                Button(
                    onClick = { viewModel.saveGitHubRepository(githubOwnerDraft, githubRepoDraft) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_github_repo_button")
                ) {
                    Text(strings.saveGithubRepo)
                }

                if (settings.effectivePackBaseUrl.isNotBlank()) {
                    Text(
                        text = "${strings.activePackUrlLabel}:\n${settings.effectivePackBaseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider()
                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = { baseUrlDraft = it },
                    label = { Text(strings.customBaseUrlLabel) },
                    placeholder = { Text(strings.baseUrlPlaceholder) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pack_base_url_input")
                )
                OutlinedButton(
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

        // 4b. M6: Travel Modes (Default Mode, Walking Speed, Cycling Speed, Avoid Hills)
        item {
            SettingsTravelModeSectionCard(
                settings = settings,
                onSelectDefaultMode = { viewModel.setDefaultTravelMode(it) },
                onSelectWalkingSpeed = { viewModel.setWalkingSpeedPreset(it) },
                onSelectCyclingSpeed = { viewModel.setCyclingSpeedPreset(it) },
                onToggleAvoidHills = { viewModel.setAvoidHillsWhenCycling(it) }
            )
        }

        // 5. Routing Defaults
        item {
            SettingsSectionCard(
                title = strings.sectionRoutingDefaults,
                icon = Icons.Filled.Route
            ) {
                Text(strings.routePreferenceLabel, style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.routePreference == RoutePreference.FASTEST,
                        onClick = { viewModel.setRoutePreference(RoutePreference.FASTEST) },
                        label = { Text(strings.routePrimary) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("route_pref_fastest_chip")
                    )
                    FilterChip(
                        selected = settings.routePreference == RoutePreference.SHORTEST,
                        onClick = { viewModel.setRoutePreference(RoutePreference.SHORTEST) },
                        label = { Text(strings.routeShortest) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("route_pref_shortest_chip")
                    )
                }

                HorizontalDivider()
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

        // 6. Phase 4: My Active Driving Reports (optional post-drive notes + confirm/delete + export/import JSON)
        item {
            SettingsSectionCard(
                title = strings.sectionMyReports,
                icon = Icons.Filled.ReportProblem
            ) {
                if (activeReports.isEmpty()) {
                    Text(
                        text = strings.noActiveReports,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    activeReports.take(10).forEach { report ->
                        var noteDraft by remember(report.id, report.note) { mutableStateOf(report.note) }
                        val typeLabel = when (runCatching { ReportType.valueOf(report.type) }.getOrNull()) {
                            ReportType.POLICE -> strings.reportPolice
                            ReportType.HAZARD -> strings.reportHazard
                            ReportType.CRASH -> strings.reportCrash
                            ReportType.ROAD_CLOSURE -> strings.reportClosure
                            ReportType.SPEED_TRAP -> strings.reportSpeedTrap
                            ReportType.CONSTRUCTION -> strings.reportConstruction
                            ReportType.OBJECT_ON_ROAD -> strings.reportObjectOnRoad
                            ReportType.STALLED_VEHICLE -> strings.reportStalledVehicle
                            null -> report.type
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = typeLabel + if (report.roadName.isNotBlank()) " • ${report.roadName}" else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.dismissUserReport(report.id) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = strings.actionDelete, tint = AlertRed)
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = noteDraft,
                                        onValueChange = { noteDraft = it },
                                        placeholder = { Text(strings.addNoteHint, fontSize = 12.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.updateReportNote(report.id, noteDraft) },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Filled.Check, contentDescription = strings.saveNote, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { exportReportsLauncher.launch("rahyab_reports.json") },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_reports_button")
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.exportReports, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { importReportsLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("import_reports_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.importReports, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                }
            }
        }

        // 7. Final Step: Export & Import Settings + Saved Places for New Phone Transfer
        item {
            SettingsSectionCard(
                title = strings.sectionBackupTransfer,
                icon = Icons.Filled.Backup
            ) {
                Button(
                    onClick = { exportFullBackupLauncher.launch("rahyab_settings_and_places.json") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("export_full_backup_button")
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.exportBackupButton)
                }
                OutlinedButton(
                    onClick = { importFullBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("import_full_backup_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.importBackupButton)
                }
            }
        }

        // 8. Developer & Emulator Testing Options
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.5.dp, AlertAmber.copy(alpha = 0.7f)),
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
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(strings.simulateDriveTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = strings.simulateDriveSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.simulateDriveEnabled,
                            onCheckedChange = { viewModel.setSimulateDriveEnabled(it) },
                            modifier = Modifier.testTag("switch_simulate_drive")
                        )
                    }
                }
            }
        }

        // 8b. Diagnostics Screen Entry
        item {
            SettingsSectionCard(
                title = strings.diagnosticsTitle,
                icon = Icons.Filled.BugReport
            ) {
                Text(
                    text = strings.diagnosticsSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_diagnostics_button")
                ) {
                    Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.openDiagnostics)
                }
            }
        }

        // 9. About RahYab, Versions & Open-Source Licenses
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
                Text(
                    text = "${strings.appVersionLabel}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${strings.dataBuildVersionLabel}: $installedDataBuildVersion",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                Text(strings.osmAttributionFull, style = MaterialTheme.typography.bodyMedium)
                Text(strings.overtureAttributionFull, style = MaterialTheme.typography.bodyMedium)
                Text(strings.overtureAddressesAttributionFull, style = MaterialTheme.typography.bodyMedium)
                Text(strings.extrasAttributionFull, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://docs.overturemaps.org/attribution/")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("overture_attribution_link_button")
                ) {
                    Text(strings.overtureAttributionLinkLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
