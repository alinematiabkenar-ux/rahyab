package com.example.data.packs

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.example.data.local.InstalledPackDao
import com.example.data.local.InstalledPackEntity
import com.example.data.local.PackInstallStatus
import com.example.data.model.UsStatesCatalog
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Manages offline US state & regional map packs on device storage.
 *
 * Each pack consists of 3 files under `<filesDir>/packs/<packCode>/`:
 *  - `<packCode>.pmtiles`      (Protomaps vector tiles for MapLibre Native)
 *  - `<packCode>_valhalla.tar` (Valhalla routing tile archive with ~30-mile neighbor border buffer,
 *                               also extracted into `<filesDir>/valhalla_tiles/` so routes cross
 *                               state lines seamlessly without conflicts or duplicate roads)
 *  - `<packCode>_places.db`    (SQLite FTS5 places, brands, addresses, and road hazards database)
 *
 * Supports:
 *  - Multi-state download queue with pause, resume, and automatic retry on transient network loss
 *  - Multi-part GitHub Release asset download + concatenation (> 2 GiB assets split into .part001...)
 *  - Full SHA-256 checksum verification before installation
 *  - Build version tracking & cross-pack build mismatch detection
 *  - Zero-permission Android Storage Access Framework local file import (.zip, .pmtiles, .tar, .db)
 */
class StatePackManager(
    private val context: Context,
    private val installedPackDao: InstalledPackDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val downloadQueue = ConcurrentLinkedQueue<Pair<String, String>>() // (packCode, baseUrl)
    private var queueWorkerJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val manifestAdapter = moshi.adapter(PackManifest::class.java)

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _manifest = MutableStateFlow<PackManifest?>(null)
    val manifest: StateFlow<PackManifest?> = _manifest.asStateFlow()

    private val _manifestError = MutableStateFlow<String?>(null)
    val manifestError: StateFlow<String?> = _manifestError.asStateFlow()

    private val _isFetchingManifest = MutableStateFlow(false)
    val isFetchingManifest: StateFlow<Boolean> = _isFetchingManifest.asStateFlow()

    private val _packsChangedTick = MutableStateFlow(0L)
    val packsChangedTick: StateFlow<Long> = _packsChangedTick.asStateFlow()

    val packsRootDir: File
        get() = File(context.filesDir, "packs").apply { mkdirs() }

    /**
     * Unified Valhalla tile directory where all downloaded states/regions extract their
     * hierarchical Valhalla tiles (0/, 1/, 2/...). Because adjacent states overlap by ~30 miles
     * at the border, extracting into a unified directory deduplicates identical tile IDs cleanly.
     */
    val unifiedValhallaTileDir: File
        get() = File(context.filesDir, "valhalla_tiles").apply { mkdirs() }

    fun stateDir(stateCode: String): File =
        File(packsRootDir, stateCode.lowercase()).apply { mkdirs() }

    fun pmtilesFile(stateCode: String): File =
        File(stateDir(stateCode), "${stateCode.lowercase()}.pmtiles")

    fun valhallaTarFile(stateCode: String): File =
        File(stateDir(stateCode), "${stateCode.lowercase()}_valhalla.tar")

    fun placesDbFile(stateCode: String): File =
        File(stateDir(stateCode), "${stateCode.lowercase()}_places.db")

    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun getTotalMapStorageUsedBytes(): Long {
        return folderSizeBytes(packsRootDir) + folderSizeBytes(unifiedValhallaTileDir)
    }

    fun getStateDiskBytes(stateCode: String): Long = folderSizeBytes(stateDir(stateCode))

    fun isLowStorageForPack(packSizeBytes: Long): Boolean {
        val required = (packSizeBytes * 1.5).toLong() + 250L * 1024L * 1024L
        return getAvailableStorageBytes() < required
    }

    fun getInstalledPmtilesFiles(): List<Pair<String, File>> {
        return UsStatesCatalog.allPackEntries.mapNotNull { state ->
            val file = pmtilesFile(state.code)
            if (file.exists() && isValidPmtilesFile(file)) {
                state.code to file
            } else {
                null
            }
        }
    }

    fun getInstalledPlacesDatabases(): List<Pair<String, File>> {
        return UsStatesCatalog.allPackEntries.mapNotNull { state ->
            val file = placesDbFile(state.code)
            if (file.exists() && isValidPlacesDb(file)) {
                state.code to file
            } else {
                null
            }
        }
    }

    fun getInstalledRoutingPackCodes(): Set<String> {
        return UsStatesCatalog.allPackEntries.mapNotNull { state ->
            val tar = valhallaTarFile(state.code)
            if (tar.exists() && tar.length() > 512L) state.code else null
        }.toSet()
    }

    fun hasAnyValidValhallaTiles(): Boolean {
        val dir = unifiedValhallaTileDir
        if (!dir.exists()) return false
        return dir.walkTopDown().any { it.isFile && it.name.endsWith(".gph") && it.length() > 0L }
    }

    fun getInstalledValhallaTarFiles(): List<File> {
        return UsStatesCatalog.allPackEntries.mapNotNull { state ->
            val tar = valhallaTarFile(state.code)
            if (tar.exists() && tar.length() > 512L) tar else null
        }
    }

    /**
     * Checks whether installed state packs came from different data build versions.
     * Returns the list of distinct non-blank build versions if > 1 (warning needed), or empty list if aligned.
     */
    suspend fun getMismatchedBuildVersions(): List<String> = withContext(Dispatchers.IO) {
        val installed = installedPackDao.getAllOnce().filter {
            it.status == PackInstallStatus.INSTALLED.name || it.status == PackInstallStatus.UPDATE_AVAILABLE.name
        }
        val versions = installed
            .map { it.buildVersion.ifBlank { it.installedVersion } }
            .filter { it.isNotBlank() && it != "local" }
            .distinct()
        if (versions.size > 1) versions else emptyList()
    }

    /**
     * Audits disk files against Room pack metadata at startup so corrupted, partial,
     * or manually removed packs never crash the app.
     */
    suspend fun auditInstalledPacks() = withContext(Dispatchers.IO) {
        val existing = installedPackDao.getAllOnce().associateBy { it.stateCode.lowercase() }
        for (state in UsStatesCatalog.allPackEntries) {
            val code = state.code
            val pm = pmtilesFile(code)
            val vh = valhallaTarFile(code)
            val db = placesDbFile(code)
            val anyPresent = pm.exists() || vh.exists() || db.exists()
            val record = existing[code]

            if (!anyPresent && record == null) continue

            val pmValid = pm.exists() && isValidPmtilesFile(pm)
            val vhValid = vh.exists() && vh.length() > 512L
            val dbValid = db.exists() && isValidPlacesDb(db)

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
                    val manifestEntry = _manifest.value?.states?.find { it.stateCode.equals(code, true) }
                    if (manifestEntry != null && record != null && record.installedVersion.isNotEmpty() && record.installedVersion != manifestEntry.version) {
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
                val diskBytes = getStateDiskBytes(code)
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
                        errorMessage = if (computedStatus == PackInstallStatus.CORRUPTED) {
                            buildMissingFilesNote(pmValid, vhValid, dbValid)
                        } else null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        notifyPacksChanged()
    }

    suspend fun fetchManifest(baseUrl: String): Result<PackManifest> = withContext(Dispatchers.IO) {
        val cleaned = baseUrl.trim().trimEnd('/')
        if (cleaned.isEmpty()) {
            _manifestError.value = null
            return@withContext Result.failure(IllegalStateException("Base URL not configured"))
        }
        _isFetchingManifest.value = true
        _manifestError.value = null
        try {
            val manifestUrl = if (cleaned.endsWith("manifest.json")) cleaned else "$cleaned/manifest.json"
            val request = Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", "RahYab-Android-Nav/1.1")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code} fetching manifest"
                    _manifestError.value = msg
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val body = response.body?.string().orEmpty()
                val parsed = manifestAdapter.fromJson(body)
                    ?: throw IllegalStateException("Invalid manifest.json format")
                _manifest.value = parsed

                val installed = installedPackDao.getAllOnce()
                for (pack in installed) {
                    val remote = parsed.states.find { it.stateCode.equals(pack.stateCode, true) }
                    if (remote != null &&
                        pack.status == PackInstallStatus.INSTALLED.name &&
                        pack.installedVersion != remote.version
                    ) {
                        installedPackDao.upsert(
                            pack.copy(
                                status = PackInstallStatus.UPDATE_AVAILABLE.name,
                                availableVersion = remote.version
                            )
                        )
                    }
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            _manifestError.value = e.message ?: "Unable to fetch manifest.json"
            Result.failure(e)
        } finally {
            _isFetchingManifest.value = false
        }
    }

    /**
     * Adds a state/region pack to the sequential download queue so multiple states can be
     * downloaded cleanly with pause, resume, and automatic retry after lost connection.
     */
    fun startOrResumeDownload(stateCode: String, baseUrl: String) {
        val code = stateCode.lowercase()
        val entry = _manifest.value?.states?.find { it.stateCode.equals(code, true) } ?: return
        if (activeJobs[code]?.isActive == true) return

        scope.launch {
            val existing = installedPackDao.getByState(code)
            val totalSize = entry.totalSizeBytes.takeIf { it > 0 }
                ?: (entry.pmtiles.sizeBytes + entry.valhalla.sizeBytes + entry.places.sizeBytes)
            installedPackDao.upsert(
                InstalledPackEntity(
                    stateCode = code,
                    status = PackInstallStatus.QUEUED.name,
                    installedVersion = existing?.installedVersion ?: "",
                    buildVersion = existing?.buildVersion ?: "",
                    availableVersion = entry.version,
                    parentStateCode = entry.parentStateCode ?: UsStatesCatalog.byCode[code]?.parentStateCode,
                    totalBytes = totalSize,
                    downloadedBytes = computePartialBytes(code),
                    errorMessage = null
                )
            )
            if (downloadQueue.none { it.first == code }) {
                downloadQueue.add(code to baseUrl)
            }
            ensureQueueWorkerRunning()
        }
    }

    fun updateAllOutdatedPacks(baseUrl: String) {
        scope.launch {
            val outdated = installedPackDao.getAllOnce().filter {
                it.status == PackInstallStatus.UPDATE_AVAILABLE.name
            }
            for (pack in outdated) {
                startOrResumeDownload(pack.stateCode, baseUrl)
            }
        }
    }

    private suspend fun ensureQueueWorkerRunning() {
        queueMutex.withLock {
            if (queueWorkerJob?.isActive == true) return
            queueWorkerJob = scope.launch {
                while (true) {
                    val next = downloadQueue.poll() ?: break
                    val (code, baseUrl) = next
                    val job = launch { executePackDownloadWithRetry(code, baseUrl) }
                    activeJobs[code] = job
                    job.join()
                    activeJobs.remove(code)
                }
            }
        }
    }

    private suspend fun executePackDownloadWithRetry(code: String, baseUrl: String) {
        val entry = _manifest.value?.states?.find { it.stateCode.equals(code, true) } ?: return
        val totalSize = entry.totalSizeBytes.takeIf { it > 0 }
            ?: (entry.pmtiles.sizeBytes + entry.valhalla.sizeBytes + entry.places.sizeBytes)

        var lastError: Exception? = null
        val maxAttempts = 3

        for (attempt in 1..maxAttempts) {
            try {
                installedPackDao.upsert(
                    InstalledPackEntity(
                        stateCode = code,
                        status = PackInstallStatus.DOWNLOADING.name,
                        installedVersion = installedPackDao.getByState(code)?.installedVersion ?: "",
                        buildVersion = installedPackDao.getByState(code)?.buildVersion ?: "",
                        availableVersion = entry.version,
                        parentStateCode = entry.parentStateCode ?: UsStatesCatalog.byCode[code]?.parentStateCode,
                        totalBytes = totalSize,
                        downloadedBytes = computePartialBytes(code),
                        errorMessage = null
                    )
                )

                val effectiveBase = _manifest.value?.baseUrl?.takeIf { u -> u.isNotBlank() }
                    ?: baseUrl.trim().trimEnd('/')

                val filesToFetch = listOf(
                    entry.pmtiles to pmtilesFile(code),
                    entry.valhalla to valhallaTarFile(code),
                    entry.places to placesDbFile(code)
                )

                var completedPriorBytes = 0L
                for ((fileEntry, targetFile) in filesToFetch) {
                    if (targetFile.exists() && fileEntry.sha256.isNotBlank() && sha256Of(targetFile).equals(fileEntry.sha256, true)) {
                        completedPriorBytes += targetFile.length()
                        continue
                    }

                    downloadEntryAndVerify(
                        fileEntry = fileEntry,
                        effectiveBase = effectiveBase,
                        targetFile = targetFile,
                        onProgress = { currentFileBytes ->
                            scope.launch {
                                val current = installedPackDao.getByState(code)
                                if (current != null && current.status == PackInstallStatus.DOWNLOADING.name) {
                                    installedPackDao.upsert(
                                        current.copy(
                                            downloadedBytes = (completedPriorBytes + currentFileBytes).coerceAtMost(totalSize),
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        },
                        onVerifying = {
                            scope.launch {
                                val current = installedPackDao.getByState(code)
                                if (current != null) {
                                    installedPackDao.upsert(
                                        current.copy(status = PackInstallStatus.VERIFYING.name)
                                    )
                                }
                            }
                        }
                    )
                    completedPriorBytes += targetFile.length()
                }

                // Validate integrity of downloaded pack files
                val pmValid = isValidPmtilesFile(pmtilesFile(code))
                val dbValid = isValidPlacesDb(placesDbFile(code))
                val vhValid = valhallaTarFile(code).exists() && valhallaTarFile(code).length() > 512L
                if (!pmValid || !dbValid || !vhValid) {
                    throw IllegalStateException(buildMissingFilesNote(pmValid, vhValid, dbValid))
                }

                // Extract Valhalla tar into unified tile directory for seamless cross-state routing
                extractTarIntoDirectory(valhallaTarFile(code), unifiedValhallaTileDir)

                val diskBytes = getStateDiskBytes(code)
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
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                notifyPacksChanged()
                return
            } catch (ce: CancellationException) {
                val existing = installedPackDao.getByState(code)
                if (existing != null &&
                    (existing.status == PackInstallStatus.DOWNLOADING.name || existing.status == PackInstallStatus.QUEUED.name)
                ) {
                    installedPackDao.upsert(
                        existing.copy(
                            status = PackInstallStatus.PAUSED.name,
                            downloadedBytes = computePartialBytes(code),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                throw ce
            } catch (e: Exception) {
                lastError = e
                val isChecksumError = e.message?.contains("SHA-256", ignoreCase = true) == true
                if (!isChecksumError && attempt < maxAttempts) {
                    // Automatic retry after transient connection loss with exponential backoff
                    delay(attempt * 3_000L)
                    continue
                }
                break
            }
        }

        // All retry attempts failed
        val existing = installedPackDao.getByState(code)
        installedPackDao.upsert(
            (existing ?: InstalledPackEntity(stateCode = code, status = PackInstallStatus.CORRUPTED.name)).copy(
                status = PackInstallStatus.CORRUPTED.name,
                errorMessage = lastError?.message ?: "Download or verification failed",
                updatedAt = System.currentTimeMillis()
            )
        )
        notifyPacksChanged()
    }

    /**
     * Downloads a single PackFileEntry — supporting both single-file assets AND multi-part
     * GitHub Release assets (`parts` list when > 2 GiB) — joins parts cleanly, and verifies SHA-256.
     */
    fun downloadEntryAndVerify(
        fileEntry: PackFileEntry,
        effectiveBase: String,
        targetFile: File,
        onProgress: (Long) -> Unit,
        onVerifying: () -> Unit
    ) {
        if (fileEntry.parts.isNotEmpty()) {
            // Multi-part GitHub Release asset download + join
            val partFiles = mutableListOf<File>()
            var partsBytesSoFar = 0L
            for (part in fileEntry.parts) {
                val partTemp = File(targetFile.parentFile, "${part.filename}.part")
                val partFinal = File(targetFile.parentFile, part.filename)
                if (partFinal.exists() && part.sha256.isNotBlank() && sha256Of(partFinal).equals(part.sha256, true)) {
                    partsBytesSoFar += partFinal.length()
                    partFiles.add(partFinal)
                    onProgress(partsBytesSoFar)
                    continue
                }
                val partUrl = resolveFileUrl(effectiveBase, part.url, part.filename)
                downloadWithResume(partUrl, partTemp) { written ->
                    onProgress(partsBytesSoFar + written)
                }
                if (part.sha256.isNotBlank() && !sha256Of(partTemp).equals(part.sha256, true)) {
                    partTemp.delete()
                    throw IllegalStateException("SHA-256 mismatch for part ${part.filename}")
                }
                if (partFinal.exists()) partFinal.delete()
                if (!partTemp.renameTo(partFinal)) {
                    partTemp.copyTo(partFinal, overwrite = true)
                    partTemp.delete()
                }
                partsBytesSoFar += partFinal.length()
                partFiles.add(partFinal)
            }

            onVerifying()
            val joinedTemp = File(targetFile.parentFile, "${targetFile.name}.joined")
            joinPartFiles(partFiles, joinedTemp)

            if (fileEntry.sha256.isNotBlank() && !sha256Of(joinedTemp).equals(fileEntry.sha256, true)) {
                joinedTemp.delete()
                throw IllegalStateException("SHA-256 mismatch after joining ${fileEntry.filename}")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!joinedTemp.renameTo(targetFile)) {
                joinedTemp.copyTo(targetFile, overwrite = true)
                joinedTemp.delete()
            }
            // Clean up individual part files once joined & verified
            partFiles.forEach { it.delete() }
        } else {
            // Standard single-file download with HTTP Range resume
            val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
            val resolvedUrl = resolveFileUrl(effectiveBase, fileEntry.url, fileEntry.filename)
            downloadWithResume(resolvedUrl, partFile, onProgress)

            onVerifying()
            if (fileEntry.sha256.isNotBlank()) {
                val actualHash = sha256Of(partFile)
                if (!actualHash.equals(fileEntry.sha256, ignoreCase = true)) {
                    partFile.delete()
                    throw IllegalStateException("SHA-256 mismatch for ${fileEntry.filename}")
                }
            }
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
        }
    }

    fun pauseDownload(stateCode: String) {
        val code = stateCode.lowercase()
        downloadQueue.removeAll { it.first == code }
        activeJobs[code]?.cancel()
        scope.launch {
            val current = installedPackDao.getByState(code)
            if (current != null) {
                installedPackDao.upsert(
                    current.copy(
                        status = PackInstallStatus.PAUSED.name,
                        downloadedBytes = computePartialBytes(code),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    suspend fun deletePack(stateCode: String) = withContext(Dispatchers.IO) {
        val code = stateCode.lowercase()
        downloadQueue.removeAll { it.first == code }
        activeJobs[code]?.cancel()
        activeJobs.remove(code)
        stateDir(code).deleteRecursively()
        installedPackDao.delete(code)
        rebuildUnifiedValhallaDirectory()
        notifyPacksChanged()
    }

    /**
     * Imports a local pack archive (.zip) or individual state file (.pmtiles, .tar, .db)
     * selected via Android Storage Access Framework (zero storage permissions required).
     */
    suspend fun importLocalUri(stateCode: String, uri: Uri, displayName: String?): Result<String> = withContext(Dispatchers.IO) {
        val code = stateCode.lowercase()
        val lowerName = (displayName ?: uri.lastPathSegment ?: "").lowercase()
        try {
            val dir = stateDir(code)
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalArgumentException("Cannot open selected file"))

            input.use { stream ->
                when {
                    lowerName.endsWith(".zip") || lowerName.endsWith(".rahyab") -> {
                        extractZipPackForState(code, stream, dir)
                    }
                    lowerName.endsWith(".pmtiles") -> {
                        val target = pmtilesFile(code)
                        copyStreamToFile(stream, target)
                        if (!isValidPmtilesFile(target)) {
                            target.delete()
                            return@withContext Result.failure(IllegalArgumentException("Invalid PMTiles file header"))
                        }
                    }
                    lowerName.endsWith(".tar") -> {
                        val target = valhallaTarFile(code)
                        copyStreamToFile(stream, target)
                        if (target.length() <= 512L) {
                            target.delete()
                            return@withContext Result.failure(IllegalArgumentException("Invalid Valhalla tar archive"))
                        }
                        extractTarIntoDirectory(target, unifiedValhallaTileDir)
                    }
                    lowerName.endsWith(".db") || lowerName.endsWith(".sqlite") -> {
                        val target = placesDbFile(code)
                        copyStreamToFile(stream, target)
                        if (!isValidPlacesDb(target)) {
                            target.delete()
                            return@withContext Result.failure(IllegalArgumentException("Invalid or corrupted FTS5 places SQLite database"))
                        }
                    }
                    else -> {
                        return@withContext Result.failure(
                            IllegalArgumentException("Unsupported file type. Choose .zip, .pmtiles, .tar, or .db")
                        )
                    }
                }
            }

            auditInstalledPacks()
            Result.success(displayName ?: "Imported")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZipPackForState(stateCode: String, stream: InputStream, stateDir: File) {
        ZipInputStream(BufferedInputStream(stream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = File(entry.name).name.lowercase()
                    val destination: File? = when {
                        entryName.endsWith(".pmtiles") -> pmtilesFile(stateCode)
                        entryName.endsWith(".tar") -> valhallaTarFile(stateCode)
                        entryName.endsWith(".db") || entryName.endsWith(".sqlite") -> placesDbFile(stateCode)
                        else -> null
                    }
                    if (destination != null) {
                        FileOutputStream(destination).use { fos ->
                            val buf = ByteArray(16 * 1024)
                            var read: Int
                            while (zis.read(buf).also { read = it } != -1) {
                                fos.write(buf, 0, read)
                            }
                        }
                        if (destination.name.endsWith(".tar")) {
                            extractTarIntoDirectory(destination, unifiedValhallaTileDir)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun rebuildUnifiedValhallaDirectory() {
        unifiedValhallaTileDir.deleteRecursively()
        unifiedValhallaTileDir.mkdirs()
        for (tar in getInstalledValhallaTarFiles()) {
            runCatching { extractTarIntoDirectory(tar, unifiedValhallaTileDir) }
        }
    }

    /**
     * Pure-Kotlin POSIX/USTAR tar extractor for Valhalla tile archives (.gph tiles).
     * Protects against path traversal (Zip Slip) and handles overlapping 30-mile border
     * buffer tiles cleanly without conflicts.
     */
    fun extractTarIntoDirectory(tarFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        BufferedInputStream(FileInputStream(tarFile)).use { bis ->
            val header = ByteArray(512)
            while (true) {
                val readHeader = readFully(bis, header)
                if (readHeader < 512) break
                if (header.all { it == 0.toByte() }) break

                val name = parseTarString(header, 0, 100)
                val prefix = parseTarString(header, 345, 155)
                val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
                val size = parseTarOctal(header, 124, 12)
                val typeFlag = header[156].toInt().toChar()

                val outFile = File(destDir, fullName)
                val canonicalOut = outFile.canonicalPath
                val isSafe = canonicalOut.startsWith(canonicalDest + File.separator) || canonicalOut == canonicalDest

                if (isSafe && (typeFlag == '0' || typeFlag == '\u0000') && fullName.isNotBlank()) {
                    outFile.parentFile?.mkdirs()
                    // Write atomically via temp file so overlapping border tiles never leave partial reads
                    val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp")
                    FileOutputStream(tmpFile).use { fos ->
                        var remaining = size
                        val buf = ByteArray(16 * 1024)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = bis.read(buf, 0, toRead)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if (outFile.exists()) outFile.delete()
                    if (!tmpFile.renameTo(outFile)) {
                        tmpFile.copyTo(outFile, overwrite = true)
                        tmpFile.delete()
                    }
                } else {
                    skipFully(bis, size)
                }

                val remainder = (size % 512L).toInt()
                if (remainder > 0) {
                    skipFully(bis, (512 - remainder).toLong())
                }
            }
        }
    }

    private fun downloadWithResume(
        url: String,
        partFile: File,
        onProgress: (Long) -> Unit
    ) {
        val existingLength = if (partFile.exists()) partFile.length() else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "RahYab-Android-Nav/1.1")
            .get()
        if (existingLength > 0L) {
            reqBuilder.header("Range", "bytes=$existingLength-")
        }

        okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                onProgress(existingLength)
                return
            }
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code} downloading $url")
            }

            val append = response.code == 206 && existingLength > 0L
            val body = response.body ?: throw IllegalStateException("Empty download body")
            var written = if (append) existingLength else 0L
            var lastReported = 0L

            RandomAccessFile(partFile, "rw").use { raf ->
                if (append) {
                    raf.seek(existingLength)
                } else {
                    raf.setLength(0L)
                }
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        written += bytesRead
                        if (written - lastReported >= 256 * 1024) {
                            lastReported = written
                            onProgress(written)
                        }
                    }
                }
            }
            onProgress(written)
        }
    }

    fun resolveFileUrl(baseUrl: String, entryUrl: String, filename: String): String {
        return when {
            entryUrl.startsWith("http://") || entryUrl.startsWith("https://") -> entryUrl
            entryUrl.isNotBlank() -> "${baseUrl.trimEnd('/')}/${entryUrl.trimStart('/')}"
            else -> "${baseUrl.trimEnd('/')}/$filename"
        }
    }

    private fun computePartialBytes(stateCode: String): Long {
        val dir = stateDir(stateCode)
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun copyStreamToFile(stream: InputStream, dest: File) {
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                out.write(buf, 0, read)
            }
        }
    }

    fun isValidPmtilesFile(file: File): Boolean {
        if (!file.exists() || file.length() < 127L) return false
        return try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(7)
                if (fis.read(magic) != 7) return false
                String(magic, Charsets.US_ASCII) == "PMTiles"
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isValidPlacesDb(file: File): Boolean {
        if (!file.exists() || file.length() < 4096L) return false
        return try {
            val driver = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
            val conn = driver.open(file.absolutePath)
            try {
                val stmt = conn.prepare(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('places', 'places_fts')"
                )
                try {
                    stmt.step() && stmt.getLong(0) >= 2L
                } finally {
                    stmt.close()
                }
            } finally {
                conn.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildMissingFilesNote(pm: Boolean, vh: Boolean, db: Boolean): String {
        val missing = buildList {
            if (!pm) add("PMTiles")
            if (!vh) add("Valhalla routing")
            if (!db) add("Places DB")
        }
        return "Missing or invalid: ${missing.joinToString(", ")}"
    }

    private fun folderSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val trash = ByteArray(4096)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val n = input.read(trash, 0, minOf(trash.size.toLong(), remaining).toInt())
                if (n <= 0) break
                remaining -= n
            }
        }
    }

    private fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun parseTarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val str = parseTarString(header, offset, length).trim()
        if (str.isEmpty()) return 0L
        return str.toLongOrNull(8) ?: 0L
    }

    private fun notifyPacksChanged() {
        _packsChangedTick.value = System.currentTimeMillis()
    }

    companion object {
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(32 * 1024)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun joinPartFiles(parts: List<File>, destFile: File) {
            FileOutputStream(destFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                for (part in parts) {
                    FileInputStream(part).use { fis ->
                        var read: Int
                        while (fis.read(buf).also { read = it } != -1) {
                            fos.write(buf, 0, read)
                        }
                    }
                }
            }
        }
    }
}
