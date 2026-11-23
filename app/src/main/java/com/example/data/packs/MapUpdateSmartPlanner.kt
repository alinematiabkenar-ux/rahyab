package com.example.data.packs

import com.example.data.local.InstalledPackDao
import com.example.data.local.InstalledPackEntity
import com.example.data.local.PackInstallStatus
import com.example.data.model.UsStatesCatalog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Stored or computed SHA-256 hashes for the 3 components of an installed state pack.
 */
data class InstalledComponentHashes(
    val pmtilesSha256: String = "",
    val valhallaSha256: String = "",
    val placesSha256: String = ""
)

/**
 * Pure diff plan specifying which components must be downloaded for a pack update
 * and the exact real download size in bytes.
 */
data class SmartUpdatePlan(
    val downloadPmtiles: Boolean,
    val downloadValhalla: Boolean,
    val downloadPlaces: Boolean,
    val downloadBytes: Long,
    val skippedBytes: Long,
    val changedComponents: List<String>
) {
    val hasAnyDownload: Boolean
        get() = downloadPmtiles || downloadValhalla || downloadPlaces
}

/**
 * Metadata for a downloaded pack update staged while turn-by-turn navigation is active,
 * ready to be swapped atomically once navigation ends.
 */
data class PendingSwapMeta(
    val stateCode: String,
    val version: String,
    val buildVersion: String,
    val placesSource: String,
    val parentStateCode: String?,
    val pmtilesSha256: String,
    val valhallaSha256: String,
    val placesSha256: String,
    val hasStagedPmtiles: Boolean,
    val hasStagedValhalla: Boolean,
    val hasStagedPlaces: Boolean
)

/**
 * F3.3 Smart Map Pack Updater:
 * - Compares per-component SHA-256 hashes (pmtiles, valhalla, places) to download only changed files.
 * - Computes real download bytes before starting an update.
 * - Stages verified updated files under `<packsRoot>/_pending_updates/<stateCode>/` when navigation
 *   is active, and atomically swaps them in once navigation ends.
 */
object MapUpdateSmartPlanner {

    private const val PENDING_DIR_NAME = "_pending_updates"
    private const val META_FILENAME = "pending_meta.properties"

    /**
     * Pure JVM planner comparing installed component SHA-256 hashes against the remote manifest.
     * Unchanged components with matching non-blank SHA-256 hashes and present local files are skipped.
     */
    fun planSmartUpdate(
        localHashes: InstalledComponentHashes,
        remote: StateManifestEntry,
        hasLocalPmtiles: Boolean = true,
        hasLocalValhalla: Boolean = true,
        hasLocalPlaces: Boolean = true
    ): SmartUpdatePlan {
        val skipPmtiles = hasLocalPmtiles && hashesMatch(localHashes.pmtilesSha256, remote.pmtiles.sha256)
        val skipValhalla = hasLocalValhalla && hashesMatch(localHashes.valhallaSha256, remote.valhalla.sha256)
        val skipPlaces = hasLocalPlaces && hashesMatch(localHashes.placesSha256, remote.places.sha256)

        val dlPmtiles = !skipPmtiles
        val dlValhalla = !skipValhalla
        val dlPlaces = !skipPlaces

        var dlBytes = 0L
        var skippedBytes = 0L
        val changed = mutableListOf<String>()

        if (dlPmtiles) {
            dlBytes += remote.pmtiles.sizeBytes.coerceAtLeast(0L)
            changed += "pmtiles"
        } else {
            skippedBytes += remote.pmtiles.sizeBytes.coerceAtLeast(0L)
        }

        if (dlValhalla) {
            dlBytes += remote.valhalla.sizeBytes.coerceAtLeast(0L)
            changed += "valhalla"
        } else {
            skippedBytes += remote.valhalla.sizeBytes.coerceAtLeast(0L)
        }

        if (dlPlaces) {
            dlBytes += remote.places.sizeBytes.coerceAtLeast(0L)
            changed += "places"
        } else {
            skippedBytes += remote.places.sizeBytes.coerceAtLeast(0L)
        }

        return SmartUpdatePlan(
            downloadPmtiles = dlPmtiles,
            downloadValhalla = dlValhalla,
            downloadPlaces = dlPlaces,
            downloadBytes = dlBytes,
            skippedBytes = skippedBytes,
            changedComponents = changed
        )
    }

    private fun hashesMatch(localSha: String, remoteSha: String): Boolean {
        val a = localSha.trim()
        val b = remoteSha.trim()
        return a.isNotEmpty() && b.isNotEmpty() && a.equals(b, ignoreCase = true)
    }

    fun pendingRootDir(packsRootDir: File): File = File(packsRootDir, PENDING_DIR_NAME)

    fun pendingStateDir(packsRootDir: File, stateCode: String): File =
        File(pendingRootDir(packsRootDir), stateCode.lowercase()).apply { mkdirs() }

    fun hasPendingSwapForState(packsRootDir: File, stateCode: String): Boolean =
        File(File(pendingRootDir(packsRootDir), stateCode.lowercase()), META_FILENAME).exists()

    fun writePendingMeta(dir: File, meta: PendingSwapMeta) {
        dir.mkdirs()
        val props = Properties().apply {
            setProperty("stateCode", meta.stateCode)
            setProperty("version", meta.version)
            setProperty("buildVersion", meta.buildVersion)
            setProperty("placesSource", meta.placesSource)
            setProperty("parentStateCode", meta.parentStateCode.orEmpty())
            setProperty("pmtilesSha256", meta.pmtilesSha256)
            setProperty("valhallaSha256", meta.valhallaSha256)
            setProperty("placesSha256", meta.placesSha256)
            setProperty("hasStagedPmtiles", meta.hasStagedPmtiles.toString())
            setProperty("hasStagedValhalla", meta.hasStagedValhalla.toString())
            setProperty("hasStagedPlaces", meta.hasStagedPlaces.toString())
        }
        FileOutputStream(File(dir, META_FILENAME)).use { fos ->
            props.store(fos, "RahYab deferred map update metadata")
        }
    }

    fun readPendingMeta(dir: File): PendingSwapMeta? {
        val metaFile = File(dir, META_FILENAME)
        if (!metaFile.exists()) return null
        return runCatching {
            val props = Properties()
            FileInputStream(metaFile).use { props.load(it) }
            val code = props.getProperty("stateCode").orEmpty().lowercase()
            if (code.isBlank()) return null
            PendingSwapMeta(
                stateCode = code,
                version = props.getProperty("version").orEmpty(),
                buildVersion = props.getProperty("buildVersion").orEmpty(),
                placesSource = props.getProperty("placesSource").orEmpty().ifBlank { "overture+osm" },
                parentStateCode = props.getProperty("parentStateCode")?.takeIf { it.isNotBlank() },
                pmtilesSha256 = props.getProperty("pmtilesSha256").orEmpty(),
                valhallaSha256 = props.getProperty("valhallaSha256").orEmpty(),
                placesSha256 = props.getProperty("placesSha256").orEmpty(),
                hasStagedPmtiles = props.getProperty("hasStagedPmtiles").toBoolean(),
                hasStagedValhalla = props.getProperty("hasStagedValhalla").toBoolean(),
                hasStagedPlaces = props.getProperty("hasStagedPlaces").toBoolean()
            )
        }.getOrNull()
    }

    /**
     * Resolves component hashes from Room DB first, falling back to SHA-256 on disk only
     * for components whose stored hash is blank (legacy packs upgraded from older versions).
     */
    fun resolveLocalHashesForDownload(
        packManager: StatePackManager,
        stateCode: String,
        existing: InstalledPackEntity?
    ): InstalledComponentHashes {
        val pmFile = packManager.pmtilesFile(stateCode)
        val vhFile = packManager.valhallaTarFile(stateCode)
        val dbFile = packManager.placesDbFile(stateCode)
        val pmHash = existing?.pmtilesSha256?.takeIf { it.isNotBlank() }
            ?: if (pmFile.exists()) StatePackManager.sha256Of(pmFile) else ""
        val vhHash = existing?.valhallaSha256?.takeIf { it.isNotBlank() }
            ?: if (vhFile.exists()) StatePackManager.sha256Of(vhFile) else ""
        val dbHash = existing?.placesSha256?.takeIf { it.isNotBlank() }
            ?: if (dbFile.exists()) StatePackManager.sha256Of(dbFile) else ""
        return InstalledComponentHashes(pmHash, vhHash, dbHash)
    }

    /**
     * Applies any staged map updates once navigation is no longer active.
     * Returns the number of state packs swapped in.
     */
    suspend fun applyPendingSwaps(
        packManager: StatePackManager,
        installedPackDao: InstalledPackDao
    ): Int {
        val root = pendingRootDir(packManager.packsRootDir)
        if (!root.exists()) return 0
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        var appliedCount = 0

        for (dir in dirs) {
            val meta = readPendingMeta(dir) ?: continue
            val code = meta.stateCode
            val stagedPm = File(dir, "${code}.pmtiles")
            val stagedVh = File(dir, "${code}_valhalla.tar")
            val stagedDb = File(dir, "${code}_places.db")

            val swappedPm = if (meta.hasStagedPmtiles && stagedPm.exists() && packManager.isValidPmtilesFile(stagedPm)) {
                moveAtomic(stagedPm, packManager.pmtilesFile(code))
                true
            } else false

            val swappedVh = if (meta.hasStagedValhalla && stagedVh.exists() && stagedVh.length() > 512L) {
                moveAtomic(stagedVh, packManager.valhallaTarFile(code))
                packManager.extractTarIntoDirectory(packManager.valhallaTarFile(code), packManager.unifiedValhallaTileDir)
                true
            } else false

            val swappedDb = if (meta.hasStagedPlaces && stagedDb.exists() && packManager.isValidPlacesDb(stagedDb)) {
                moveAtomic(stagedDb, packManager.placesDbFile(code))
                true
            } else false

            val pmOk = packManager.isValidPmtilesFile(packManager.pmtilesFile(code))
            val vhOk = packManager.valhallaTarFile(code).exists() && packManager.valhallaTarFile(code).length() > 512L
            val dbOk = packManager.isValidPlacesDb(packManager.placesDbFile(code))

            if (pmOk && vhOk && dbOk) {
                val diskBytes = packManager.getStateDiskBytes(code)
                installedPackDao.upsert(
                    InstalledPackEntity(
                        stateCode = code,
                        status = PackInstallStatus.INSTALLED.name,
                        installedVersion = meta.version,
                        buildVersion = meta.buildVersion.ifBlank { meta.version },
                        availableVersion = meta.version,
                        parentStateCode = meta.parentStateCode ?: UsStatesCatalog.byCode[code]?.parentStateCode,
                        totalBytes = diskBytes,
                        downloadedBytes = diskBytes,
                        hasPmtiles = true,
                        hasValhalla = true,
                        hasPlacesDb = true,
                        placesSource = meta.placesSource,
                        pmtilesSha256 = meta.pmtilesSha256,
                        valhallaSha256 = meta.valhallaSha256,
                        placesSha256 = meta.placesSha256,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                if (swappedDb) {
                    packManager.scheduleBackgroundPlacesIndexing(code, packManager.placesDbFile(code))
                }
                if (swappedPm || swappedVh || swappedDb) {
                    appliedCount++
                }
            }
            dir.deleteRecursively()
        }
        return appliedCount
    }

    private fun moveAtomic(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }
}
