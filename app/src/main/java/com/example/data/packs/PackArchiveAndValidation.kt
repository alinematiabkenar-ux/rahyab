package com.example.data.packs

import android.content.Context
import com.example.util.CrashLogManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Pure mechanical extraction from [StatePackManager] (R3):
 * tar/zip extraction, PMTiles & SQLite places DB validation, SHA-256 hashing,
 * multi-part joining, and spatial places index creation.
 */
internal object PackArchiveAndValidation {

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

    fun extractZipPackForState(
        manager: StatePackManager,
        stateCode: String,
        stream: InputStream
    ) {
        ZipInputStream(BufferedInputStream(stream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = File(entry.name).name.lowercase()
                    val destination: File? = when {
                        entryName.endsWith(".pmtiles") -> manager.pmtilesFile(stateCode)
                        entryName.endsWith(".tar") -> manager.valhallaTarFile(stateCode)
                        entryName.endsWith(".db") || entryName.endsWith(".sqlite") -> manager.placesDbFile(stateCode)
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
                            extractTarIntoDirectory(destination, manager.unifiedValhallaTileDir)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun copyStreamToFile(stream: InputStream, dest: File) {
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

    fun isValidPlacesDb(context: Context, file: File): Boolean {
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
        } catch (_: LinkageError) {
            hasSqliteHeader(file)
        } catch (t: Throwable) {
            CrashLogManager.logError(context, "PackAudit", "Failed to validate places DB ${file.name}", t)
            false
        }
    }

    fun hasAddressSearchTables(file: File): Boolean {
        if (!file.exists() || file.length() < 4096L) return false
        return try {
            val driver = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
            val conn = driver.open(file.absolutePath)
            try {
                val stmt = conn.prepare(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('addresses', 'addresses_fts')"
                )
                try {
                    stmt.step() && stmt.getLong(0) >= 2L
                } finally {
                    stmt.close()
                }
            } finally {
                conn.close()
            }
        } catch (_: LinkageError) {
            runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    db.rawQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('addresses', 'addresses_fts')",
                        null
                    ).use { cursor ->
                        cursor.moveToFirst() && cursor.getLong(0) >= 2L
                    }
                }
            }.getOrDefault(false)
        } catch (_: Throwable) {
            false
        }
    }

    fun ensurePlacesLatLonIndex(
        context: Context,
        stateCode: String,
        dbFile: File,
        onStatusMessage: (String?) -> Unit
    ) {
        if (!dbFile.exists() || dbFile.length() < 1024L) return
        try {
            val driver = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            try {
                val checkStmt = conn.prepare(
                    "SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_places_lat_lon'"
                )
                val alreadyExists = try {
                    checkStmt.step()
                } finally {
                    checkStmt.close()
                }
                if (alreadyExists) return

                onStatusMessage("Optimizing search index (${stateCode.uppercase()})…")
                runCatching {
                    val busyStmt = conn.prepare("PRAGMA busy_timeout = 5000")
                    try { busyStmt.step() } finally { busyStmt.close() }
                }
                val stmt = conn.prepare("CREATE INDEX IF NOT EXISTS idx_places_lat_lon ON places(lat, lon)")
                try {
                    stmt.step()
                } finally {
                    stmt.close()
                }
            } finally {
                conn.close()
            }
        } catch (t: Throwable) {
            CrashLogManager.logError(
                context = context,
                tag = "PlacesIndex",
                message = "Skipped idx_places_lat_lon for ${stateCode.uppercase()}; continuing without spatial index",
                throwable = t
            )
        }
    }

    fun buildMissingFilesNote(pm: Boolean, vh: Boolean, db: Boolean): String {
        val missing = buildList {
            if (!pm) add("PMTiles")
            if (!vh) add("Valhalla routing")
            if (!db) add("Places DB")
        }
        return "Missing or invalid: ${missing.joinToString(", ")}"
    }

    fun folderSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

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

    private fun hasSqliteHeader(file: File): Boolean = runCatching {
        FileInputStream(file).use { fis ->
            val header = ByteArray(16)
            fis.read(header) == 16 && String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
        }
    }.getOrDefault(false)

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
}
