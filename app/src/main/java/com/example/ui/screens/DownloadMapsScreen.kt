package com.example.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.PackInstallStatus
import com.example.data.packs.StatePackUiItem
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.LocalAppLanguage
import com.example.i18n.LocalAppStrings
import com.example.ui.RahYabViewModel
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AlertSafeGreen

@Composable
fun DownloadMapsScreen(
    viewModel: RahYabViewModel,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statePacks by viewModel.statePacks.collectAsStateWithLifecycle()
    val isFetchingManifest by viewModel.isFetchingManifest.collectAsStateWithLifecycle()
    val manifestError by viewModel.manifestError.collectAsStateWithLifecycle()
    val lowStorageStateCode by viewModel.lowStoragePromptStateCode.collectAsStateWithLifecycle()
    val packsTick by viewModel.packsChangedTick.collectAsStateWithLifecycle()

    var filterText by remember { mutableStateOf("") }
    var showInstalledOnly by remember { mutableStateOf(false) }
    var deleteConfirmStateCode by remember { mutableStateOf<String?>(null) }
    var pendingImportStateCode by remember { mutableStateOf<String?>(null) }

    val (usedBytes, availableBytes) = remember(packsTick, statePacks) {
        viewModel.getStorageStats()
    }

    // Zero-permission Android Storage Access Framework document picker for local pack import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val stateCode = pendingImportStateCode
        if (uri != null && stateCode != null) {
            val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
            viewModel.importLocalPackFile(stateCode, uri, displayName)
        }
        pendingImportStateCode = null
    }

    val filteredPacks = remember(statePacks, filterText, showInstalledOnly) {
        val q = filterText.trim().lowercase()
        statePacks.filter { pack ->
            val matchesFilter = if (showInstalledOnly) {
                pack.status == PackInstallStatus.INSTALLED ||
                    pack.status == PackInstallStatus.UPDATE_AVAILABLE ||
                    pack.status == PackInstallStatus.CORRUPTED
            } else true

            val matchesText = q.isEmpty() ||
                pack.nameEn.lowercase().contains(q) ||
                pack.nameFa.contains(q) ||
                pack.abbr.lowercase().contains(q)

            matchesFilter && matchesText
        }.sortedWith(
            // Keep Oregon & installed states easy to spot when browsing
            compareByDescending<StatePackUiItem> { it.status == PackInstallStatus.INSTALLED || it.status == PackInstallStatus.UPDATE_AVAILABLE }
                .thenByDescending { it.stateCode == "or" }
                .thenBy { it.nameEn }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header & Storage Overview
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.mapsScreenTitle,
                    style = MaterialTheme.typography.headlineMedium
                )
                IconButton(
                    onClick = { viewModel.refreshManifest() },
                    enabled = !isFetchingManifest && settings.packBaseUrl.isNotBlank(),
                    modifier = Modifier.testTag("refresh_manifest_button")
                ) {
                    if (isFetchingManifest) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = strings.refreshManifest)
                    }
                }
            }

            // Storage Meter Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${strings.storageUsedLabel}: ${BilingualFormatter.formatBytes(usedBytes, language)}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "${strings.storageAvailableLabel}: ${BilingualFormatter.formatBytes(availableBytes, language)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (settings.packBaseUrl.isBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.manifestUnconfiguredHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (manifestError != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = manifestError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Search & Filter Row
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text(strings.filterStatesHint) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("filter_states_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showInstalledOnly,
                    onClick = { showInstalledOnly = false },
                    label = { Text(strings.filterAll) }
                )
                FilterChip(
                    selected = showInstalledOnly,
                    onClick = { showInstalledOnly = true },
                    label = {
                        val count = statePacks.count { it.status == PackInstallStatus.INSTALLED || it.status == PackInstallStatus.UPDATE_AVAILABLE }
                        Text("${strings.filterInstalled} (${BilingualFormatter.formatNumber(count, language)})")
                    }
                )
            }
        }

        // All 50 States + Washington, D.C. List
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredPacks, key = { it.stateCode }) { pack ->
                StatePackCard(
                    pack = pack,
                    canDownloadOnline = settings.packBaseUrl.isNotBlank() && pack.hasManifestEntry,
                    onDownloadOrResume = { viewModel.requestDownloadStatePack(pack.stateCode) },
                    onPause = { viewModel.pauseStatePackDownload(pack.stateCode) },
                    onDelete = { deleteConfirmStateCode = pack.stateCode },
                    onImportLocal = {
                        pendingImportStateCode = pack.stateCode
                        importLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
        }
    }

    // Low Storage Warning Dialog
    if (lowStorageStateCode != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLowStoragePrompt() },
            title = { Text(strings.lowStorageTitle) },
            text = { Text(strings.lowStorageBody) },
            confirmButton = {
                Button(onClick = { viewModel.confirmLowStorageDownload() }) {
                    Text(strings.proceedAnyway)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLowStoragePrompt() }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (deleteConfirmStateCode != null) {
        val code = deleteConfirmStateCode!!
        AlertDialog(
            onDismissRequest = { deleteConfirmStateCode = null },
            title = { Text(strings.confirmDeleteTitle) },
            text = { Text(strings.confirmDeleteBody) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteStatePack(code)
                        deleteConfirmStateCode = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text(strings.actionDelete)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmStateCode = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@Composable
private fun StatePackCard(
    pack: StatePackUiItem,
    canDownloadOnline: Boolean,
    onDownloadOrResume: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onImportLocal: () -> Unit
) {
    val strings = LocalAppStrings.current
    val language = LocalAppLanguage.current
    val stateName = if (language == AppLanguage.PERSIAN) "${pack.nameFa} (${pack.abbr})" else "${pack.nameEn} (${pack.abbr})"

    val statusText = when (pack.status) {
        PackInstallStatus.NOT_DOWNLOADED -> strings.statusNotDownloaded
        PackInstallStatus.DOWNLOADING -> "${strings.statusDownloading} (${BilingualFormatter.formatNumber((pack.progress * 100).toInt(), language)}%)"
        PackInstallStatus.PAUSED -> strings.statusPaused
        PackInstallStatus.VERIFYING -> strings.statusVerifying
        PackInstallStatus.INSTALLED -> "${strings.statusInstalled}${if (pack.installedVersion.isNotBlank()) " • v${pack.installedVersion}" else ""}"
        PackInstallStatus.UPDATE_AVAILABLE -> "${strings.statusUpdateAvailable} (v${pack.availableVersion})"
        PackInstallStatus.CORRUPTED -> pack.errorMessage ?: strings.statusCorrupted
    }

    val statusColor = when (pack.status) {
        PackInstallStatus.INSTALLED -> AlertSafeGreen
        PackInstallStatus.UPDATE_AVAILABLE -> AlertAmber
        PackInstallStatus.CORRUPTED -> AlertRed
        PackInstallStatus.DOWNLOADING, PackInstallStatus.VERIFYING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (pack.status == PackInstallStatus.INSTALLED) AlertSafeGreen.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("state_pack_card_${pack.stateCode}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stateName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val shownBytes = when {
                    pack.installedBytesOnDisk > 0L -> pack.installedBytesOnDisk
                    pack.totalSizeBytes > 0L -> pack.totalSizeBytes
                    else -> 0L
                }
                if (shownBytes > 0L) {
                    Text(
                        text = BilingualFormatter.formatBytes(shownBytes, language),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3-File Component Indicators (PMTiles, Valhalla, Places FTS5)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilePillBadge("PMTiles", pack.hasPmtiles)
                FilePillBadge("Valhalla", pack.hasValhalla)
                FilePillBadge("Places FTS5", pack.hasPlacesDb)
            }

            if (pack.status == PackInstallStatus.DOWNLOADING || pack.status == PackInstallStatus.PAUSED || pack.status == PackInstallStatus.VERIFYING) {
                LinearProgressIndicator(
                    progress = { pack.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (pack.status) {
                    PackInstallStatus.NOT_DOWNLOADED, PackInstallStatus.CORRUPTED -> {
                        if (canDownloadOnline) {
                            Button(
                                onClick = onDownloadOrResume,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("download_pack_${pack.stateCode}")
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (pack.status == PackInstallStatus.CORRUPTED) strings.actionRetry else strings.actionDownload)
                            }
                        }
                        OutlinedButton(
                            onClick = onImportLocal,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("import_pack_${pack.stateCode}")
                        ) {
                            Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.actionImportFiles)
                        }
                        if (pack.status == PackInstallStatus.CORRUPTED) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = strings.actionDelete, tint = AlertRed)
                            }
                        }
                    }
                    PackInstallStatus.DOWNLOADING, PackInstallStatus.VERIFYING -> {
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.actionPause)
                        }
                    }
                    PackInstallStatus.PAUSED -> {
                        Button(
                            onClick = onDownloadOrResume,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.actionResume)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = strings.actionDelete, tint = AlertRed)
                        }
                    }
                    PackInstallStatus.UPDATE_AVAILABLE -> {
                        Button(
                            onClick = onDownloadOrResume,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.actionUpdate)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = strings.actionDelete, tint = AlertRed)
                        }
                    }
                    PackInstallStatus.INSTALLED -> {
                        OutlinedButton(
                            onClick = onImportLocal,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.actionImportFiles)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("delete_pack_${pack.stateCode}")
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = AlertRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.actionDelete, color = AlertRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePillBadge(label: String, present: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (present) AlertSafeGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (present) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (present) AlertSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (present) AlertSafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
