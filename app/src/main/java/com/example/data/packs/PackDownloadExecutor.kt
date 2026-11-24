package com.example.data.packs

import com.example.data.local.InstalledPackDao
import com.example.data.local.InstalledPackEntity
import com.example.data.local.PackInstallStatus
import com.example.data.model.UsStatesCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pure mechanical extraction from [StatePackManager] (R3):
 * executes smart per-component pack downloads with retries, deferred swap staging during
 * active navigation, and post-download Valhalla extraction & places indexing.
 */
internal object PackDownloadExecutor {

    suspend fun executePackDownloadWithRetry(
        manager: StatePackManager,
        installedPackDao: InstalledPackDao,
        scope: CoroutineScope,
        code: String,
        baseUrl: String
    ) {
        val entry = manager.manifest.value?.states?.find { it.stateCode.equals(code, true) } ?: return
        val totalSize = entry.totalSizeBytes.takeIf { it > 0 }
            ?: (entry.pmtiles.sizeBytes + entry.valhalla.sizeBytes + entry.places.sizeBytes)

        var lastError: Exception? = null
        val maxAttempts = 3

        for (attempt in 1..maxAttempts) {
            try {
                val existingBefore = installedPackDao.getByState(code)
                val localHashes = MapUpdateSmartPlanner.resolveLocalHashesForDownload(manager, code, existingBefore)
                val plan = MapUpdateSmartPlanner.planSmartUpdate(
                    localHashes = localHashes,
                    remote = entry,
                    hasLocalPmtiles = manager.isValidPmtilesFile(manager.pmtilesFile(code)),
                    hasLocalValhalla = manager.valhallaTarFile(code).exists() && manager.valhallaTarFile(code).length() > 512L,
                    hasLocalPlaces = manager.isValidPlacesDb(manager.placesDbFile(code))
                )
                val effectiveDownloadBytes = plan.downloadBytes.takeIf { it > 0L } ?: totalSize
                val deferSwap = manager.isNavigationActiveProvider() &&
                    existingBefore?.hasPmtiles == true &&
                    existingBefore.hasValhalla &&
                    existingBefore.hasPlacesDb

                if (!deferSwap) {
                    installedPackDao.upsert(
                        InstalledPackEntity(
                            stateCode = code,
                            status = PackInstallStatus.DOWNLOADING.name,
                            installedVersion = existingBefore?.installedVersion ?: "",
                            buildVersion = existingBefore?.buildVersion ?: "",
                            availableVersion = entry.version,
                            parentStateCode = entry.parentStateCode ?: UsStatesCatalog.byCode[code]?.parentStateCode,
                            totalBytes = effectiveDownloadBytes,
                            downloadedBytes = 0L,
                            hasPmtiles = existingBefore?.hasPmtiles ?: false,
                            hasValhalla = existingBefore?.hasValhalla ?: false,
                            hasPlacesDb = existingBefore?.hasPlacesDb ?: false,
                            placesSource = entry.placesSource,
                            pmtilesSha256 = existingBefore?.pmtilesSha256.orEmpty(),
                            valhallaSha256 = existingBefore?.valhallaSha256.orEmpty(),
                            placesSha256 = existingBefore?.placesSha256.orEmpty(),
                            errorMessage = null
                        )
                    )
                }

                val effectiveBase = manager.manifest.value?.baseUrl?.takeIf { u -> u.isNotBlank() }
                    ?: baseUrl.trim().trimEnd('/')

                val pendingDir = if (deferSwap) MapUpdateSmartPlanner.pendingStateDir(manager.packsRootDir, code) else null
                val filesToFetch = buildList {
                    if (plan.downloadPmtiles) add(entry.pmtiles to (if (deferSwap) File(pendingDir, "${code}.pmtiles") else manager.pmtilesFile(code)))
                    if (plan.downloadValhalla) add(entry.valhalla to (if (deferSwap) File(pendingDir, "${code}_valhalla.tar") else manager.valhallaTarFile(code)))
                    if (plan.downloadPlaces) add(entry.places to (if (deferSwap) File(pendingDir, "${code}_places.db") else manager.placesDbFile(code)))
                }

                var completedPriorBytes = 0L
                for ((fileEntry, targetFile) in filesToFetch) {
                    if (targetFile.exists() && fileEntry.sha256.isNotBlank() &&
                        StatePackManager.sha256Of(targetFile).equals(fileEntry.sha256, true)
                    ) {
                        completedPriorBytes += targetFile.length()
                        continue
                    }

                    manager.downloadEntryAndVerify(
                        fileEntry = fileEntry,
                        effectiveBase = effectiveBase,
                        targetFile = targetFile,
                        onProgress = { currentFileBytes ->
                            if (!deferSwap) {
                                scope.launch {
                                    val current = installedPackDao.getByState(code)
                                    if (current != null && current.status == PackInstallStatus.DOWNLOADING.name) {
                                        installedPackDao.upsert(
                                            current.copy(
                                                downloadedBytes = (completedPriorBytes + currentFileBytes).coerceAtMost(effectiveDownloadBytes),
                                                updatedAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onVerifying = {
                            if (!deferSwap) {
                                scope.launch {
                                    val current = installedPackDao.getByState(code)
                                    if (current != null) {
                                        installedPackDao.upsert(
                                            current.copy(status = PackInstallStatus.VERIFYING.name)
                                        )
                                    }
                                }
                            }
                        }
                    )
                    completedPriorBytes += targetFile.length()
                }

                // X4: Optional <state>_extras.db download (non-fatal if absent or download fails)
                entry.extras?.let { extrasEntry ->
                    val extrasTarget = if (deferSwap) File(pendingDir, "${code}_extras.db") else manager.extrasDbFile(code)
                    val alreadyValid = extrasTarget.exists() && extrasEntry.sha256.isNotBlank() &&
                        StatePackManager.sha256Of(extrasTarget).equals(extrasEntry.sha256, true)
                    if (!alreadyValid) {
                        runCatching {
                            manager.downloadEntryAndVerify(
                                fileEntry = extrasEntry,
                                effectiveBase = effectiveBase,
                                targetFile = extrasTarget,
                                onProgress = {},
                                onVerifying = {}
                            )
                        }
                    }
                }

                val newPmSha = entry.pmtiles.sha256.ifBlank { localHashes.pmtilesSha256 }
                val newVhSha = entry.valhalla.sha256.ifBlank { localHashes.valhallaSha256 }
                val newDbSha = entry.places.sha256.ifBlank { localHashes.placesSha256 }

                if (deferSwap && pendingDir != null) {
                    MapUpdateSmartPlanner.writePendingMeta(
                        pendingDir,
                        PendingSwapMeta(
                            stateCode = code,
                            version = entry.version,
                            buildVersion = entry.effectiveBuildVersion,
                            placesSource = entry.placesSource,
                            parentStateCode = entry.parentStateCode ?: UsStatesCatalog.byCode[code]?.parentStateCode,
                            pmtilesSha256 = newPmSha,
                            valhallaSha256 = newVhSha,
                            placesSha256 = newDbSha,
                            hasStagedPmtiles = plan.downloadPmtiles,
                            hasStagedValhalla = plan.downloadValhalla,
                            hasStagedPlaces = plan.downloadPlaces
                        )
                    )
                    manager.notifyPacksChangedInternal()
                    manager.onUpdatePostponed?.invoke(code)
                    return
                }

                val pmValid = manager.isValidPmtilesFile(manager.pmtilesFile(code))
                val dbValid = manager.isValidPlacesDb(manager.placesDbFile(code))
                val vhValid = manager.valhallaTarFile(code).exists() && manager.valhallaTarFile(code).length() > 512L
                if (!pmValid || !dbValid || !vhValid) {
                    throw IllegalStateException(PackArchiveAndValidation.buildMissingFilesNote(pmValid, vhValid, dbValid))
                }

                if (plan.downloadValhalla || !manager.hasAnyValidValhallaTiles()) {
                    manager.extractTarIntoDirectory(manager.valhallaTarFile(code), manager.unifiedValhallaTileDir)
                }

                val diskBytes = manager.getStateDiskBytes(code)
                installedPackDao.upsert(
                    InstalledPackEntity(
                        stateCode = code,
                        status = PackInstallStatus.INSTALLED.name,
                        installedVersion = entry.version,
                        buildVersion = entry.effectiveBuildVersion,
                        availableVersion = entry.version,
                        parentStateCode = entry.parentStateCode ?: UsStatesCatalog.byCode[code]?.parentStateCode,
                        totalBytes = diskBytes,
                        downloadedBytes = diskBytes,
                        hasPmtiles = true,
                        hasValhalla = true,
                        hasPlacesDb = true,
                        placesSource = entry.placesSource,
                        pmtilesSha256 = newPmSha.ifBlank { StatePackManager.sha256Of(manager.pmtilesFile(code)) },
                        valhallaSha256 = newVhSha.ifBlank { StatePackManager.sha256Of(manager.valhallaTarFile(code)) },
                        placesSha256 = newDbSha.ifBlank { StatePackManager.sha256Of(manager.placesDbFile(code)) },
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                manager.notifyPacksChangedInternal()
                if (plan.downloadPlaces) {
                    manager.scheduleBackgroundPlacesIndexing(code, manager.placesDbFile(code))
                }
                return
            } catch (ce: CancellationException) {
                val existing = installedPackDao.getByState(code)
                if (existing != null &&
                    (existing.status == PackInstallStatus.DOWNLOADING.name || existing.status == PackInstallStatus.QUEUED.name)
                ) {
                    installedPackDao.upsert(
                        existing.copy(
                            status = PackInstallStatus.PAUSED.name,
                            downloadedBytes = manager.computePartialBytesInternal(code),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                throw ce
            } catch (e: Exception) {
                lastError = e
                val isChecksumError = e.message?.contains("SHA-256", ignoreCase = true) == true
                if (!isChecksumError && attempt < maxAttempts) {
                    delay(attempt * 3_000L)
                    continue
                }
                break
            }
        }

        val existing = installedPackDao.getByState(code)
        installedPackDao.upsert(
            (existing ?: InstalledPackEntity(stateCode = code, status = PackInstallStatus.CORRUPTED.name)).copy(
                status = PackInstallStatus.CORRUPTED.name,
                errorMessage = lastError?.message ?: "Download or verification failed",
                updatedAt = System.currentTimeMillis()
            )
        )
        manager.notifyPacksChangedInternal()
    }
}
