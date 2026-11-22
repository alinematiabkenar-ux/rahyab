package com.example.util

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions and writes the full error text and timestamp to a local file so
 * the next app launch can display a clear recovery dialog with a "Copy error details" button.
 */
object CrashLogManager {

    private const val CRASH_LOG_FILE_NAME = "rahyab_last_crash.txt"
    private const val ERROR_LOG_FILE_NAME = "rahyab_error_log.txt"
    private const val MAX_STORED_ERROR_LINES = 200

    @Volatile
    private var installed = false
    private val logLock = Any()

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    writeCrashLog(appContext, thread.name, throwable)
                    logError(
                        context = appContext,
                        tag = "Uncaught/${thread.name}",
                        message = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
                    )
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    fun writeCrashLog(
        context: Context,
        threadName: String,
        throwable: Throwable,
        timestampMs: Long = System.currentTimeMillis()
    ): File {
        val file = crashLogFile(context)
        val report = formatCrashReport(threadName, throwable, timestampMs)
        file.parentFile?.mkdirs()
        file.writeText(report, Charsets.UTF_8)
        return file
    }

    fun logError(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        runCatching {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val ts = formatter.format(Date(timestampMs))
            val detail = if (throwable != null) {
                val errMsg = throwable.message?.take(160).orEmpty()
                " (${throwable.javaClass.simpleName}: $errMsg)"
            } else {
                ""
            }
            val cleanMsg = message.replace('\n', ' ').replace('\r', ' ').trim()
            val line = "[$ts] [$tag] $cleanMsg$detail"
            synchronized(logLock) {
                val file = errorLogFile(context)
                file.parentFile?.mkdirs()
                val existing = if (file.exists()) {
                    file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                val updated = (existing + line).takeLast(MAX_STORED_ERROR_LINES)
                file.writeText(updated.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        }
    }

    fun readLastErrorLines(context: Context, maxLines: Int = 20): List<String> {
        return runCatching {
            synchronized(logLock) {
                val file = errorLogFile(context)
                if (!file.exists() || file.length() == 0L) return@runCatching emptyList()
                file.readLines(Charsets.UTF_8)
                    .filter { it.isNotBlank() }
                    .takeLast(maxLines.coerceAtLeast(1))
            }
        }.getOrDefault(emptyList())
    }

    fun clearErrorLog(context: Context) {
        runCatching {
            synchronized(logLock) {
                val file = errorLogFile(context)
                if (file.exists()) file.delete()
            }
        }
    }

    fun formatCrashReport(
        threadName: String,
        throwable: Throwable,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val formattedTime = formatter.format(Date(timestampMs))
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()

        return buildString {
            appendLine("RahYab Crash Report")
            appendLine("Time: $formattedTime ($timestampMs)")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: $threadName")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            appendLine()
            append(sw.toString())
        }.trimEnd()
    }

    fun readPendingCrashLog(context: Context): String? {
        return runCatching {
            val file = crashLogFile(context)
            if (file.exists() && file.length() > 0L) {
                file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
    }

    fun clearPendingCrashLog(context: Context) {
        runCatching {
            val file = crashLogFile(context)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    internal fun crashLogFile(context: Context): File =
        File(context.applicationContext.filesDir, CRASH_LOG_FILE_NAME)

    internal fun errorLogFile(context: Context): File =
        File(context.applicationContext.filesDir, ERROR_LOG_FILE_NAME)
}
