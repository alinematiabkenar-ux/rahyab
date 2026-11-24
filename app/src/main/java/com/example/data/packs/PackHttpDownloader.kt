package com.example.data.packs

import com.example.data.online.OnlineHttpConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Pure mechanical extraction from [StatePackManager] (R3):
 * resumable HTTP file downloader supporting single-file and multi-part GitHub Release assets
 * with SHA-256 verification.
 */
internal class PackHttpDownloader {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun downloadEntryAndVerify(
        fileEntry: PackFileEntry,
        effectiveBase: String,
        targetFile: File,
        onProgress: (Long) -> Unit,
        onVerifying: () -> Unit
    ) {
        if (fileEntry.parts.isNotEmpty()) {
            val partFiles = mutableListOf<File>()
            var partsBytesSoFar = 0L
            for (part in fileEntry.parts) {
                val partTemp = File(targetFile.parentFile, "${part.filename}.part")
                val partFinal = File(targetFile.parentFile, part.filename)
                if (partFinal.exists() && part.sha256.isNotBlank() &&
                    PackArchiveAndValidation.sha256Of(partFinal).equals(part.sha256, true)
                ) {
                    partsBytesSoFar += partFinal.length()
                    partFiles.add(partFinal)
                    onProgress(partsBytesSoFar)
                    continue
                }
                val partUrl = resolveFileUrl(effectiveBase, part.url, part.filename)
                downloadWithResume(partUrl, partTemp) { written ->
                    onProgress(partsBytesSoFar + written)
                }
                if (part.sha256.isNotBlank() && !PackArchiveAndValidation.sha256Of(partTemp).equals(part.sha256, true)) {
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
            PackArchiveAndValidation.joinPartFiles(partFiles, joinedTemp)

            if (fileEntry.sha256.isNotBlank() &&
                !PackArchiveAndValidation.sha256Of(joinedTemp).equals(fileEntry.sha256, true)
            ) {
                joinedTemp.delete()
                throw IllegalStateException("SHA-256 mismatch after joining ${fileEntry.filename}")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!joinedTemp.renameTo(targetFile)) {
                joinedTemp.copyTo(targetFile, overwrite = true)
                joinedTemp.delete()
            }
            partFiles.forEach { it.delete() }
        } else {
            val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
            val resolvedUrl = resolveFileUrl(effectiveBase, fileEntry.url, fileEntry.filename)
            downloadWithResume(resolvedUrl, partFile, onProgress)

            onVerifying()
            if (fileEntry.sha256.isNotBlank()) {
                val actualHash = PackArchiveAndValidation.sha256Of(partFile)
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

    private fun downloadWithResume(
        url: String,
        partFile: File,
        onProgress: (Long) -> Unit
    ) {
        val existingLength = if (partFile.exists()) partFile.length() else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", OnlineHttpConfig.userAgent())
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
}
