package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.local.PackInstallStatus
import com.example.data.packs.StatePackManager
import com.example.data.packs.StatePackUiItem
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.i18n.BilingualFormatter
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.navigation.GpsFix
import com.example.navigation.LastRouteDiagnostics
import com.example.navigation.VoiceGuidanceManager
import com.example.ui.RahYabViewModel
import com.example.ui.theme.AlertAmber
import kotlinx.coroutines.delay
import java.util.Locale

data class DiagnosticsPackSnapshot(
    val name: String,
    val stateCode: String,
    val buildVersion: String,
    val placesSource: String,
    val fileSizes: StatePackManager.PackFileSizes
)

@Composable
fun DiagnosticsScreen(
    viewModel: RahYabViewModel,
    hasFineLocationPermission: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statePacks by viewModel.statePacks.collectAsStateWithLifecycle()
    val gpsFix by viewModel.currentGpsFix.collectAsStateWithLifecycle()
    val compassHeading by viewModel.compassHeadingDegrees.collectAsStateWithLifecycle()
    val compassAccuracy by viewModel.compassSensorAccuracy.collectAsStateWithLifecycle()
    val lastRouteDiag by viewModel.lastRouteDiagnostics.collectAsStateWithLifecycle()
    val ttsDiag by viewModel.ttsDiagnostics.collectAsStateWithLifecycle()
    val parkedCar by viewModel.parkedCar.collectAsStateWithLifecycle()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var errorLines by remember { mutableStateOf(viewModel.readLastErrorLogLines(20)) }

    LaunchedEffect(Unit) {
        viewModel.refreshTtsDiagnostics()
        errorLines = viewModel.readLastErrorLogLines(20)
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val installedPacks = remember(statePacks, language) {
        statePacks
            .filter {
                it.status == PackInstallStatus.INSTALLED || it.status == PackInstallStatus.UPDATE_AVAILABLE
            }
            .map { pack ->
                DiagnosticsPackSnapshot(
                    name = if (language == AppLanguage.PERSIAN) pack.nameFa else pack.nameEn,
                    stateCode = pack.stateCode,
                    buildVersion = pack.installedBuildVersion.ifBlank { pack.installedVersion.ifBlank { "—" } },
                    placesSource = pack.placesSource.ifBlank { "overture+osm" },
                    fileSizes = viewModel.getPackFileSizes(pack.stateCode)
                )
            }
    }

    val fullReportText = remember(
        installedPacks,
        gpsFix,
        nowMs,
        hasFineLocationPermission,
        compassHeading,
        compassAccuracy,
        lastRouteDiag,
        ttsDiag,
        errorLines,
        language,
        settings.lastManifestCheckEpochMs,
        settings.lastMapUpdateCheckResult,
        parkedCar
    ) {
        formatDiagnosticsReport(
            strings = strings,
            language = language,
            installedPacks = installedPacks,
            gpsFix = gpsFix,
            nowMs = nowMs,
            hasFineLocationPermission = hasFineLocationPermission,
            compassHeadingDegrees = compassHeading,
            compassSensorAccuracy = compassAccuracy,
            lastRoute = lastRouteDiag,
            ttsInfo = ttsDiag,
            errorLines = errorLines,
            lastMapUpdateCheckEpochMs = settings.lastManifestCheckEpochMs,
            lastMapUpdateCheckResult = settings.lastMapUpdateCheckResult,
            parkedCarTimeEpochMs = parkedCar?.parkedAtEpochMs,
            parkedCarAccuracyMeters = parkedCar?.accuracyMeters
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("diagnostics_screen")
    ) {
        // Header bar with Back arrow, title, and prominent "Copy all" button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("diagnostics_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = strings.backAction
                )
            }
            Text(
                text = strings.diagnosticsTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    viewModel.refreshTtsDiagnostics()
                    errorLines = viewModel.readLastErrorLogLines(20)
                    nowMs = System.currentTimeMillis()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("diagnostics_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = strings.actionRetry
                )
            }
            Button(
                onClick = {
                    runCatching {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("RahYab Diagnostics", fullReportText))
                    }
                    viewModel.showSnackbar(strings.diagnosticsCopied)
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("diagnostics_copy_all_button")
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.copyAll)
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. App & Device
            item {
                DiagCard(title = strings.diagSectionAppDevice, icon = Icons.Filled.PhoneAndroid) {
                    DiagKeyValueRow(
                        label = strings.appVersionLabel,
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    )
                    DiagKeyValueRow(
                        label = strings.diagAndroidVersion,
                        value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    )
                    DiagKeyValueRow(
                        label = strings.diagPhoneModel,
                        value = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                    )
                }
            }

            // 2. Installed Map Packs
            item {
                DiagCard(title = strings.diagSectionPacks, icon = Icons.Filled.Map) {
                    if (installedPacks.isEmpty()) {
                        Text(
                            text = strings.diagNoPacksInstalled,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        installedPacks.forEachIndexed { idx, pack ->
                            if (idx > 0) HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "${pack.name} (${pack.stateCode.uppercase()})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                DiagKeyValueRow(strings.diagBuildVersion, pack.buildVersion)
                                DiagKeyValueRow(strings.diagPlacesSource, pack.placesSource)
                                val sizesText = buildString {
                                    append("PMTiles: ${BilingualFormatter.formatBytes(pack.fileSizes.pmtilesBytes, language)}")
                                    append(" • Valhalla: ${BilingualFormatter.formatBytes(pack.fileSizes.valhallaBytes, language)}")
                                    append(" • Places: ${BilingualFormatter.formatBytes(pack.fileSizes.placesDbBytes, language)}")
                                    if (pack.fileSizes.extrasDbBytes > 0L) {
                                        append(" • Extras: ${BilingualFormatter.formatBytes(pack.fileSizes.extrasDbBytes, language)}")
                                    }
                                }
                                DiagKeyValueRow(
                                    label = strings.diagExtrasDbStatusLabel,
                                    value = if (pack.fileSizes.extrasDbBytes > 0L) {
                                        BilingualFormatter.formatBytes(pack.fileSizes.extrasDbBytes, language)
                                    } else {
                                        "—"
                                    }
                                )
                                Text(
                                    text = "${strings.diagFileSizes}: $sizesText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    DiagKeyValueRow(
                        label = strings.diagLastUpdateCheckTime,
                        value = formatCheckTimestamp(settings.lastManifestCheckEpochMs, strings, language)
                    )
                    DiagKeyValueRow(
                        label = strings.diagLastUpdateCheckResult,
                        value = settings.lastMapUpdateCheckResult.ifBlank { strings.diagNeverChecked }
                    )
                }
            }

            // 3. Current GPS & Compass
            item {
                DiagCard(title = strings.diagSectionGpsCompass, icon = Icons.Filled.Explore) {
                    if (gpsFix == null) {
                        Text(
                            text = strings.diagNoGpsFix,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val fix = gpsFix!!
                        val ageSec = ((nowMs - fix.timestampMs).coerceAtLeast(0L) / 1000L)
                        DiagKeyValueRow(
                            label = strings.diagGpsAccuracy,
                            value = String.format(Locale.US, "%.1f m", fix.accuracyMeters)
                        )
                        DiagKeyValueRow(
                            label = strings.diagGpsProvider,
                            value = fix.provider.ifBlank { "unknown" }
                        )
                        DiagKeyValueRow(
                            label = strings.diagGpsFixAge,
                            value = "${BilingualFormatter.formatNumber(ageSec.toInt(), language)} s"
                        )
                    }
                    DiagKeyValueRow(
                        label = strings.diagPreciseLocation,
                        value = if (hasFineLocationPermission) strings.diagGranted else strings.diagNotGranted
                    )
                    DiagKeyValueRow(
                        label = strings.diagCompassHeading,
                        value = compassHeading?.let { "${it.toInt()}°" } ?: "—"
                    )
                    DiagKeyValueRow(
                        label = strings.diagCompassAccuracy,
                        value = sensorAccuracyLabel(compassAccuracy, strings)
                    )
                }
            }

            // 3b. Parked Car (time & accuracy only — no coordinates)
            item {
                DiagCard(title = strings.diagSectionParkedCar, icon = Icons.Filled.Map) {
                    val spot = parkedCar
                    if (spot == null) {
                        Text(
                            text = strings.diagNoParkedCar,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        DiagKeyValueRow(
                            label = strings.diagParkedTime,
                            value = formatCheckTimestamp(spot.parkedAtEpochMs, strings, language)
                        )
                        DiagKeyValueRow(
                            label = strings.diagParkedAccuracy,
                            value = String.format(Locale.US, "%.1f m", spot.accuracyMeters)
                        )
                    }
                }
            }

            // 4. Last Calculated Route
            item {
                DiagCard(title = strings.diagSectionLastRoute, icon = Icons.Filled.Route) {
                    val routeDiag = lastRouteDiag
                    if (routeDiag == null) {
                        Text(
                            text = strings.diagNoRouteYet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        DiagKeyValueRow(
                            label = strings.diagManeuversCount,
                            value = BilingualFormatter.formatNumber(routeDiag.maneuverCount, language)
                        )
                        DiagKeyValueRow(
                            label = strings.diagManeuversWithLanes,
                            value = "${BilingualFormatter.formatNumber(routeDiag.maneuversWithLaneDataCount, language)} / ${BilingualFormatter.formatNumber(routeDiag.maneuverCount, language)}"
                        )
                        DiagKeyValueRow(
                            label = strings.diagShapeSpeedLimits,
                            value = "${BilingualFormatter.formatNumber(routeDiag.shapePointsWithSpeedLimitCount, language)} / ${BilingualFormatter.formatNumber(routeDiag.totalShapePoints, language)}"
                        )
                        DiagKeyValueRow(
                            label = strings.diagRoutingTimeMs,
                            value = "${BilingualFormatter.formatNumber(routeDiag.routingTimeMs.toInt(), language)} ms"
                        )
                    }
                }
            }

            // 5. Text-to-Speech (TTS) + Test Buttons + Persian Voice Setup & English Fallback Option
            item {
                DiagCard(title = strings.diagSectionTts, icon = Icons.Filled.RecordVoiceOver) {
                    DiagKeyValueRow(
                        label = strings.diagPersianTtsEngine,
                        value = ttsDiag.persianEngineLabel.ifBlank { ttsDiag.persianEnginePackage.ifBlank { ttsDiag.engineLabel.ifBlank { "—" } } }
                    )
                    DiagKeyValueRow(
                        label = strings.diagEnglishTtsEngine,
                        value = ttsDiag.englishEngineLabel.ifBlank { ttsDiag.englishEnginePackage.ifBlank { ttsDiag.engineLabel.ifBlank { "—" } } }
                    )
                    DiagKeyValueRow(
                        label = strings.diagPersianVoiceStatus,
                        value = "${strings.diagAvailableLabel}: ${yesNo(ttsDiag.isPersianAvailable, strings)} • ${strings.diagInstalledLabel}: ${yesNo(ttsDiag.isPersianInstalled, strings)}"
                    )
                    DiagKeyValueRow(
                        label = strings.diagEnglishVoiceStatus,
                        value = "${strings.diagAvailableLabel}: ${yesNo(ttsDiag.isEnglishUsAvailable, strings)} • ${strings.diagInstalledLabel}: ${yesNo(ttsDiag.isEnglishUsInstalled, strings)}"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.testPersianVoice() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("diag_test_persian_voice_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.diagTestPersianVoice, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.testEnglishVoice() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("diag_test_english_voice_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.diagTestEnglishVoice, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                    }

                    // If no Persian voice is installed/available, show clear Persian steps + option to keep app in Persian with English voice
                    if (!ttsDiag.isPersianAvailable || !ttsDiag.isPersianInstalled) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AlertAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, AlertAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diag_persian_tts_guide")
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

                                HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                        Text(
                                            text = strings.diagUseEnglishVoiceInPersian,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = strings.diagUseEnglishVoiceInPersianSubtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = settings.useEnglishVoiceInPersianMode,
                                        onCheckedChange = { viewModel.setUseEnglishVoiceInPersianMode(it) },
                                        modifier = Modifier.testTag("diag_switch_english_voice_in_persian")
                                    )
                                }
                            }
                        }
                    } else if (settings.language == AppLanguage.PERSIAN) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = strings.diagUseEnglishVoiceInPersian,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = strings.diagUseEnglishVoiceInPersianSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.useEnglishVoiceInPersianMode,
                                onCheckedChange = { viewModel.setUseEnglishVoiceInPersianMode(it) },
                                modifier = Modifier.testTag("diag_switch_english_voice_in_persian")
                            )
                        }
                    }
                }
            }

            // 6. Last 20 Lines of App Error Log
            item {
                DiagCard(title = strings.diagSectionErrorLog, icon = Icons.Filled.BugReport) {
                    if (errorLines.isEmpty()) {
                        Text(
                            text = strings.diagNoErrorsLogged,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorLines.joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .testTag("diag_error_log_text")
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DiagCard(
    title: String,
    icon: ImageVector,
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
private fun DiagKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun yesNo(flag: Boolean, strings: AppStrings): String = if (flag) strings.diagYes else strings.diagNo

private fun sensorAccuracyLabel(accuracy: Int, strings: AppStrings): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> strings.diagSensorHigh
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> strings.diagSensorMedium
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> strings.diagSensorLow
    SensorManager.SENSOR_STATUS_UNRELIABLE -> strings.diagSensorUnreliable
    else -> strings.diagSensorUnavailable
}

private fun formatCheckTimestamp(epochMs: Long, strings: AppStrings, language: AppLanguage): String {
    if (epochMs <= 0L) return strings.diagNeverChecked
    val formatted = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(epochMs))
    return BilingualFormatter.localizeDigits(formatted, language)
}

fun formatDiagnosticsReport(
    strings: AppStrings,
    language: AppLanguage,
    installedPacks: List<DiagnosticsPackSnapshot>,
    gpsFix: GpsFix?,
    nowMs: Long,
    hasFineLocationPermission: Boolean,
    compassHeadingDegrees: Float?,
    compassSensorAccuracy: Int,
    lastRoute: LastRouteDiagnostics?,
    ttsInfo: VoiceGuidanceManager.TtsDiagnosticsInfo,
    errorLines: List<String>,
    lastMapUpdateCheckEpochMs: Long = 0L,
    lastMapUpdateCheckResult: String = "",
    parkedCarTimeEpochMs: Long? = null,
    parkedCarAccuracyMeters: Float? = null
): String = buildString {
    appendLine("=== ${strings.appName} — ${strings.diagnosticsTitle} ===")
    appendLine()
    appendLine("[${strings.diagSectionAppDevice}]")
    appendLine("${strings.appVersionLabel}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("${strings.diagAndroidVersion}: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("${strings.diagPhoneModel}: ${Build.MANUFACTURER} ${Build.MODEL}".trim())
    appendLine()
    appendLine("[${strings.diagSectionPacks}]")
    if (installedPacks.isEmpty()) {
        appendLine(strings.diagNoPacksInstalled)
    } else {
        installedPacks.forEach { p ->
            appendLine("- ${p.name} (${p.stateCode.uppercase()}): ${strings.diagBuildVersion}=${p.buildVersion}, ${strings.diagPlacesSource}=${p.placesSource}")
            appendLine("  PMTiles=${BilingualFormatter.formatBytes(p.fileSizes.pmtilesBytes, language)}, Valhalla=${BilingualFormatter.formatBytes(p.fileSizes.valhallaBytes, language)}, PlacesDB=${BilingualFormatter.formatBytes(p.fileSizes.placesDbBytes, language)}")
        }
    }
    appendLine("${strings.diagLastUpdateCheckTime}: ${formatCheckTimestamp(lastMapUpdateCheckEpochMs, strings, language)}")
    appendLine("${strings.diagLastUpdateCheckResult}: ${lastMapUpdateCheckResult.ifBlank { strings.diagNeverChecked }}")
    appendLine()
    appendLine("[${strings.diagSectionGpsCompass}]")
    if (gpsFix == null) {
        appendLine(strings.diagNoGpsFix)
    } else {
        val ageSec = (nowMs - gpsFix.timestampMs).coerceAtLeast(0L) / 1000L
        appendLine("${strings.diagGpsAccuracy}: ${String.format(Locale.US, "%.1f m", gpsFix.accuracyMeters)}")
        appendLine("${strings.diagGpsProvider}: ${gpsFix.provider.ifBlank { "unknown" }}")
        appendLine("${strings.diagGpsFixAge}: ${ageSec} s")
    }
    appendLine("${strings.diagPreciseLocation}: ${if (hasFineLocationPermission) strings.diagGranted else strings.diagNotGranted}")
    appendLine("${strings.diagCompassHeading}: ${compassHeadingDegrees?.let { "${it.toInt()}°" } ?: "—"}")
    appendLine("${strings.diagCompassAccuracy}: ${sensorAccuracyLabel(compassSensorAccuracy, strings)}")
    appendLine()
    appendLine("[${strings.diagSectionParkedCar}]")
    if (parkedCarTimeEpochMs == null || parkedCarAccuracyMeters == null) {
        appendLine(strings.diagNoParkedCar)
    } else {
        appendLine("${strings.diagParkedTime}: ${formatCheckTimestamp(parkedCarTimeEpochMs, strings, language)}")
        appendLine("${strings.diagParkedAccuracy}: ${String.format(Locale.US, "%.1f m", parkedCarAccuracyMeters)}")
    }
    appendLine()
    appendLine("[${strings.diagSectionLastRoute}]")
    if (lastRoute == null) {
        appendLine(strings.diagNoRouteYet)
    } else {
        appendLine("${strings.diagManeuversCount}: ${lastRoute.maneuverCount}")
        appendLine("${strings.diagManeuversWithLanes}: ${lastRoute.maneuversWithLaneDataCount}/${lastRoute.maneuverCount}")
        appendLine("${strings.diagShapeSpeedLimits}: ${lastRoute.shapePointsWithSpeedLimitCount}/${lastRoute.totalShapePoints}")
        appendLine("${strings.diagRoutingTimeMs}: ${lastRoute.routingTimeMs} ms")
    }
    appendLine()
    appendLine("[${strings.diagSectionTts}]")
    appendLine("${strings.diagPersianTtsEngine}: ${ttsInfo.persianEngineLabel.ifBlank { ttsInfo.persianEnginePackage.ifBlank { "—" } }}")
    appendLine("${strings.diagEnglishTtsEngine}: ${ttsInfo.englishEngineLabel.ifBlank { ttsInfo.englishEnginePackage.ifBlank { "—" } }}")
    appendLine("${strings.diagPersianVoiceStatus}: ${strings.diagAvailableLabel}=${yesNo(ttsInfo.isPersianAvailable, strings)}, ${strings.diagInstalledLabel}=${yesNo(ttsInfo.isPersianInstalled, strings)}")
    appendLine("${strings.diagEnglishVoiceStatus}: ${strings.diagAvailableLabel}=${yesNo(ttsInfo.isEnglishUsAvailable, strings)}, ${strings.diagInstalledLabel}=${yesNo(ttsInfo.isEnglishUsInstalled, strings)}")
    appendLine()
    appendLine("[${strings.diagSectionErrorLog}]")
    if (errorLines.isEmpty()) {
        appendLine(strings.diagNoErrorsLogged)
    } else {
        errorLines.forEach { appendLine(it) }
    }
}.trimEnd()
