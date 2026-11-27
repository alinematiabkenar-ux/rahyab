package com.example.data.packs

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.example.data.local.InstalledPackDao
import com.example.data.local.InstalledPackEntity
import com.example.data.local.PackInstallStatus
import com.example.data.model.UsStatesCatalog
import com.example.data.online.OnlineHttpConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Coordinates offline US state & regional map packs on device storage.
 * Delegates archive extraction/validation to [PackArchiveAndValidation], HTTP transfers to
 * [PackHttpDownloader], retry/smart-diff execution to [PackDownloadExecutor], and startup audit/import
 * to [PackAuditAndImportHelper].
 */
class StatePackManager(
    private val context: Context,
    private val installedPackDao: InstalledPackDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val downloadQueue = ConcurrentLinkedQueue<Pair<String, String>>()
    private var queueWorkerJob: Job? = null

    private val httpDownloader = PackHttpDownloader()
    private val manifestHttpClient = OnlineHttpConfig.createShortTimeoutClient(timeoutSeconds = 5L)

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val manifestAdapter = moshi.adapter(PackManifest::class.java)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val indexingJobs = ConcurrentHashMap<String, Job>()

    private val _manifest = MutableStateFlow<PackManifest?>(null)
    val manifest: StateFlow<PackManifest?> = _manifest.asStateFlow()

    private val _manifestError = MutableStateFlow<String?>(null)
    val manifestError: StateFlow<String?> = _manifestError.asStateFlow()

    private val _isFetchingManifest = MutableStateFlow(false)
    val isFetchingManifest: StateFlow<Boolean> = _isFetchingManifest.asStateFlow()

    private val _packsChangedTick = MutableStateFlow(0L)
    val packsChangedTick: StateFlow<Long> = _packsChangedTick.asStateFlow()

    private val _indexingStatusMessage = MutableStateFlow<String?>(null)
    val indexingStatusMessage: StateFlow<String?> = _indexingStatusMessage.asStateFlow()

    @Volatile
    var isNavigationActiveProvider: () -> Boolean = { false }

    @Volatile
    var onUpdatePostponed: ((String) -> Unit)? = null

    suspend fun applyPendingSwapsIfIdle(): Int = withContext(Dispatchers.IO) {
        if (isNavigationActiveProvider()) return@withContext 0
        val count = MapUpdateSmartPlanner.applyPendingSwaps(this@StatePackManager, installedPackDao)
        if (count > 0) notifyPacksChanged()
        count
    }

    val packsRootDir: File
        get() = File(context.filesDir, "packs").apply { mkdirs() }

    val unifiedValhallaTileDir: File
        get() = File(context.filesDir, "valhalla_tiles").apply { mkdirs() }

    fun stateDir(stateCode: String): File =
        File(packsRootDir, stateCode.lowercase()).apply { mkdirs() }

    fun pmtilesFile(stateCode: String): File =
        File(stateDir(stateCode), "${stateCode.lowercase()}.pmtiles")

    fun valhallaTarFile(stateCode: String): File =
        File(stateDir(stateCode), "${stateCode.lowercase()}_valhalla.tar")

    fun placesDbFile(stateCode: String): File = File(stateDir(stateCode), "${stateCode.lowercase()}_places.db")
    fun extrasDbFile(stateCode: String): File = File(stateDir(stateCode), "${stateCode.lowercase()}_extras.db")

    fun getAvailableStorageBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        0L
    }

    fun getTotalMapStorageUsedBytes(): Long =
        PackArchiveAndValidation.folderSizeBytes(packsRootDir) +
            PackArchiveAndValidation.folderSizeBytes(unifiedValhallaTileDir)

    fun getStateDiskBytes(stateCode: String): Long =
        PackArchiveAndValidation.folderSizeBytes(stateDir(stateCode))

    fun isLowStorageForPack(packSizeBytes: Long): Boolean {
        val required = (packSizeBytes * 1.5).toLong() + 250L * 1024L * 1024L
        return getAvailableStorageBytes() < required
    }

    fun getInstalledPmtilesFiles(): List<Pair<String, File>> =
        UsStatesCatalog.allPackEntries.mapNotNull { state ->
            val file = pmtilesFile(state.code)
            if (file.exists() && isValidPmtilesFile(file)) state.code to file else null
        }

    fun getInstalledPlacesDatabases(): List<Pair<String, File>> = UsStatesCatalog.allPackEntries.mapNotNull { state ->
        placesDbFile(state.code).takeIf { it.exists() && isValidPlacesDb(it) }?.let { state.code to it }
    }
    fun getInstalledExtrasDatabases(): List<Pair<String, File>> = UsStatesCatalog.allPackEntries.mapNotNull { state ->
        extrasDbFile(state.code).takeIf { it.exists() && it.length() > 1024L }?.let { state.code to it }
    }

    fun getInstalledRoutingPackCodes(): Set<String> =
        UsStatesCatalog.allPackEntries.mapNotNull { state ->
            val tar = valhallaTarFile(state.code)
            if (tar.exists() && tar.length() > 512L) state.code else null
        }.toSet()

    fun hasAnyValidValhallaTiles(): Boolean {
        val dir = unifiedValhallaTileDir
        if (!dir.exists()) return false
        return dir.walkTopDown().any { it.isFile && it.name.endsWith(".gph") && it.length() > 0L }
    }

    fun hasUsableRoutingForState(stateCode: String): Boolean {
        val tar = valhallaTarFile(stateCode)
        return tar.exists() && tar.length() > 512L && hasAnyValidValhallaTiles()
    }

    fun hasUsableAddressIndex(stateCode: String): Boolean {
        val places = placesDbFile(stateCode)
        val extras = extrasDbFile(stateCode)
        return PackArchiveAndValidation.hasAddressSearchTables(places) || (extras.exists() && extras.length() > 1024L)
    }

    fun getInstalledValhallaTarFiles(): List<File> =
        UsStatesCatalog.allPackEntries.mapNotNull { state ->
            val tar = valhallaTarFile(state.code)
            if (tar.exists() && tar.length() > 512L) tar else null
        }

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

    suspend fun auditInstalledPacks() = withContext(Dispatchers.IO) {
        PackAuditAndImportHelper.auditInstalledPacks(this@StatePackManager, installedPackDao, activeJobs)
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
                .header("User-Agent", OnlineHttpConfig.userAgent())
                .get()
                .build()
            manifestHttpClient.newCall(request).execute().use { response ->
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
                        MapUpdateVersionHelper.isNewerVersion(pack.installedVersion, remote.version)
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
                    placesSource = entry.placesSource,
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
                    val job = launch {
                        PackDownloadExecutor.executePackDownloadWithRetry(
                            manager = this@StatePackManager,
                            installedPackDao = installedPackDao,
                            scope = scope,
                            code = code,
                            baseUrl = baseUrl
                        )
                    }
                    activeJobs[code] = job
                    job.join()
                    activeJobs.remove(code)
                }
            }
        }
    }

    fun downloadEntryAndVerify(
        fileEntry: PackFileEntry,
        effectiveBase: String,
        targetFile: File,
        onProgress: (Long) -> Unit,
        onVerifying: () -> Unit
    ) = httpDownloader.downloadEntryAndVerify(fileEntry, effectiveBase, targetFile, onProgress, onVerifying)

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

    suspend fun importLocalUri(stateCode: String, uri: Uri, displayName: String?): Result<String> =
        withContext(Dispatchers.IO) {
            PackAuditAndImportHelper.importLocalUri(context, this@StatePackManager, stateCode, uri, displayName)
        }

    private suspend fun rebuildUnifiedValhallaDirectory() {
        unifiedValhallaTileDir.deleteRecursively()
        unifiedValhallaTileDir.mkdirs()
        for (tar in getInstalledValhallaTarFiles()) {
            runCatching { extractTarIntoDirectory(tar, unifiedValhallaTileDir) }
        }
    }

    fun extractTarIntoDirectory(tarFile: File, destDir: File) =
        PackArchiveAndValidation.extractTarIntoDirectory(tarFile, destDir)

    fun resolveFileUrl(baseUrl: String, entryUrl: String, filename: String): String =
        httpDownloader.resolveFileUrl(baseUrl, entryUrl, filename)

    private fun computePartialBytes(stateCode: String): Long =
        stateDir(stateCode).listFiles()?.sumOf { it.length() } ?: 0L

    internal fun computePartialBytesInternal(stateCode: String): Long = computePartialBytes(stateCode)

    fun isValidPmtilesFile(file: File): Boolean = PackArchiveAndValidation.isValidPmtilesFile(file)

    fun isValidPlacesDb(file: File): Boolean = PackArchiveAndValidation.isValidPlacesDb(context, file)

    fun scheduleBackgroundPlacesIndexing(stateCode: String, dbFile: File) {
        val code = stateCode.lowercase()
        if (!dbFile.exists() || dbFile.length() < 1024L) return
        if (indexingJobs[code]?.isActive == true) return
        val job = scope.launch(Dispatchers.IO) {
            try {
                ensurePlacesLatLonIndex(code, dbFile)
            } finally {
                indexingJobs.remove(code)
                if (_indexingStatusMessage.value?.contains(code.uppercase()) == true) {
                    _indexingStatusMessage.value = null
                }
            }
        }
        indexingJobs[code] = job
    }

    internal fun ensurePlacesLatLonIndex(stateCode: String, dbFile: File) =
        PackArchiveAndValidation.ensurePlacesLatLonIndex(context, stateCode, dbFile) { msg ->
            _indexingStatusMessage.value = msg
        }

    data class PackFileSizes(
        val pmtilesBytes: Long,
        val valhallaBytes: Long,
        val placesDbBytes: Long,
        val extrasDbBytes: Long = 0L
    ) {
        val totalBytes: Long get() = pmtilesBytes + valhallaBytes + placesDbBytes + extrasDbBytes
    }

    fun getPackFileSizes(stateCode: String): PackFileSizes = PackFileSizes(
        pmtilesBytes = pmtilesFile(stateCode).takeIf { it.exists() }?.length() ?: 0L,
        valhallaBytes = valhallaTarFile(stateCode).takeIf { it.exists() }?.length() ?: 0L,
        placesDbBytes = placesDbFile(stateCode).takeIf { it.exists() }?.length() ?: 0L,
        extrasDbBytes = extrasDbFile(stateCode).takeIf { it.exists() }?.length() ?: 0L
    )

    private fun notifyPacksChanged() {
        _packsChangedTick.value = System.currentTimeMillis()
    }

    internal fun notifyPacksChangedInternal() = notifyPacksChanged()

    companion object {
        fun sha256Of(file: File): String = PackArchiveAndValidation.sha256Of(file)

        fun joinPartFiles(parts: List<File>, destFile: File) =
            PackArchiveAndValidation.joinPartFiles(parts, destFile)
    }
}
