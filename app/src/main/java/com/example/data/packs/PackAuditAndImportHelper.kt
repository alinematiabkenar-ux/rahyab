package com.example.data.packs

import android.content.Context
import android.net.Uri
import com.example.data.local.InstalledPackDao
import com.example.data.local.InstalledPackEntity
import com.example.data.local.PackInstallStatus
import com.example.data.model.UsStatesCatalog
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure mechanical extraction from [StatePackManager] (R3):
 * startup pack integrity audit and zero-permission SAF local pack file import.
 */
internal object PackAuditAndImportHelper {

    suspend fun auditInstalledPacks(
        manager: StatePackManager,
        installedPackDao: InstalledPackDao,
        activeJobs: ConcurrentHashMap<String, Job>
    ) {
        val existing = installedPackDao.getAllOnce().associateBy { it.stateCode.lowercase() }
        for (state in UsStatesCatalog.allPackEntries) {
            val code = state.code
            val pm = manager.pmtilesFile(code)
            val vh = manager.valhallaTarFile(code)
            val db = manager.placesDbFile(code)
            val anyPresent = pm.exists() || vh.exists() || db.exists()
            val record = existing[code]

            if (!anyPresent && record == null) continue

            val pmValid = pm.exists() && manager.isValidPmtilesFile(pm)
            val vhValid = vh.exists() && vh.length() > 512L
            if (vhValid && !manager.hasAnyValidValhallaTiles()) {
                runCatching { manager.extractTarIntoDirectory(vh, manager.unifiedValhallaTileDir) }
            }
            val dbValid = db.exists() && manager.isValidPlacesDb(db)

            val currentStatus = record?.status?.let {
                runCatching { PackInstallStatus.valueOf(it) }.getOrNull()
            }

            if (currentStatus == PackInstallStatus.DOWNLOADING ||
                currentStatus == PackInstallStatus.PAUSED ||
                currentStatus == PackInstallStatus.QUEUED
            ) {
                if (activeJobs[code]?.isActive != true) {
                    installedPackDao.upsert(
                        record.copy(
                            status = PackInstallStatus.PAUSED.name,
                            hasPmtiles = pmValid,
                            hasValhalla = vhValid,
                            hasPlacesDb = dbValid,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                continue
            }

            val computedStatus = when {
                pmValid && vhValid && dbValid -> {
                    val manifestEntry = manager.manifest.value?.states?.find { it.stateCode.equals(code, true) }
                    if (manifestEntry != null && record != null &&
                        MapUpdateVersionHelper.isNewerVersion(record.installedVersion, manifestEntry.version)
                    ) {
                        PackInstallStatus.UPDATE_AVAILABLE
                    } else {
                        PackInstallStatus.INSTALLED
                    }
                }
                pmValid || vhValid || dbValid -> PackInstallStatus.CORRUPTED
                else -> PackInstallStatus.NOT_DOWNLOADED
            }

            if (computedStatus == PackInstallStatus.NOT_DOWNLOADED) {
                installedPackDao.delete(code)
            } else {
                val diskBytes = manager.getStateDiskBytes(code)
                installedPackDao.upsert(
                    InstalledPackEntity(
                        stateCode = code,
                        status = computedStatus.name,
                        installedVersion = record?.installedVersion ?: "local",
                        buildVersion = record?.buildVersion ?: record?.installedVersion ?: "local",
                        availableVersion = record?.availableVersion ?: "",
                        parentStateCode = state.parentStateCode,
                        totalBytes = record?.totalBytes?.takeIf { it > 0 } ?: diskBytes,
                        downloadedBytes = diskBytes,
                        hasPmtiles = pmValid,
                        hasValhalla = vhValid,
                        hasPlacesDb = dbValid,
                        placesSource = record?.placesSource ?: "overture+osm",
                        pmtilesSha256 = record?.pmtilesSha256.orEmpty(),
                        valhallaSha256 = record?.valhallaSha256.orEmpty(),
                        placesSha256 = record?.placesSha256.orEmpty(),
                        errorMessage = if (computedStatus == PackInstallStatus.CORRUPTED) {
                            PackArchiveAndValidation.buildMissingFilesNote(pmValid, vhValid, dbValid)
                        } else null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        manager.notifyPacksChangedInternal()
    }

    suspend fun importLocalUri(
        context: Context,
        manager: StatePackManager,
        stateCode: String,
        uri: Uri,
        displayName: String?
    ): Result<String> {
        val code = stateCode.lowercase()
        val lowerName = (displayName ?: uri.lastPathSegment ?: "").lowercase()
        return try {
            manager.stateDir(code)
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalArgumentException("Cannot open selected file"))

            input.use { stream ->
                when {
                    lowerName.endsWith(".zip") || lowerName.endsWith(".rahyab") -> {
                        PackArchiveAndValidation.extractZipPackForState(manager, code, stream)
                    }
                    lowerName.endsWith(".pmtiles") -> {
                        val target = manager.pmtilesFile(code)
                        PackArchiveAndValidation.copyStreamToFile(stream, target)
                        if (!manager.isValidPmtilesFile(target)) {
                            target.delete()
                            return Result.failure(IllegalArgumentException("Invalid PMTiles file header"))
                        }
                    }
                    lowerName.endsWith(".tar") -> {
                        val target = manager.valhallaTarFile(code)
                        PackArchiveAndValidation.copyStreamToFile(stream, target)
                        if (target.length() <= 512L) {
                            target.delete()
                            return Result.failure(IllegalArgumentException("Invalid Valhalla tar archive"))
                        }
                        manager.extractTarIntoDirectory(target, manager.unifiedValhallaTileDir)
                    }
                    lowerName.endsWith(".db") || lowerName.endsWith(".sqlite") -> {
                        val target = manager.placesDbFile(code)
                        PackArchiveAndValidation.copyStreamToFile(stream, target)
                        if (!manager.isValidPlacesDb(target)) {
                            target.delete()
                            return Result.failure(IllegalArgumentException("Invalid or corrupted FTS5 places SQLite database"))
                        }
                        manager.scheduleBackgroundPlacesIndexing(code, target)
                    }
                    else -> {
                        return Result.failure(
                            IllegalArgumentException("Unsupported file type. Choose .zip, .pmtiles, .tar, or .db")
                        )
                    }
                }
            }

            manager.auditInstalledPacks()
            if (lowerName.endsWith(".zip") || lowerName.endsWith(".rahyab")) {
                val dbFile = manager.placesDbFile(code)
                if (dbFile.exists() && manager.isValidPlacesDb(dbFile)) {
                    manager.scheduleBackgroundPlacesIndexing(code, dbFile)
                }
            }
            Result.success(displayName ?: "Imported")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
