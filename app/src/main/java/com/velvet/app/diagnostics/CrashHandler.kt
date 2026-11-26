package com.velvet.app.diagnostics

import com.velvet.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashReport(
    val timestampMs: Long,
    val threadName: String,
    val appVersion: String,
    val buildCode: Int,
    val stackTrace: String,
    val recentLogs: List<String>,
    val rawText: String,
)

/**
 * Uncaught exception interceptor that persists the crash stack trace and the last
 * 200 redacted [DiagnosticsLogger] lines before chaining to Android's default handler.
 */
object CrashHandler {
    private const val LAST_CRASH_FILE = "last_crash.txt"
    private const val MAX_HISTORY_FILES = 5

    private val lock = Any()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installedHandler: Thread.UncaughtExceptionHandler? = null
    private var crashDirectory: File? = null

    private val _lastCrashText = MutableStateFlow<String?>(null)
    val lastCrashText: StateFlow<String?> = _lastCrashText.asStateFlow()

    fun install(
        crashDir: File,
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
    ) {
        synchronized(lock) {
            if (!crashDir.exists()) crashDir.mkdirs()
            crashDirectory = crashDir
            _lastCrashText.value = readLastCrash(crashDir)

            val currentDefault = Thread.getDefaultUncaughtExceptionHandler()
            if (currentDefault != null && currentDefault === installedHandler) {
                return
            }
            previousHandler = currentDefault
            val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    val logs = DiagnosticsLogger.tailFormatted(200)
                    recordCrash(
                        crashDir = crashDir,
                        threadName = thread.name,
                        throwable = throwable,
                        recentLogs = logs,
                        versionName = versionName,
                        versionCode = versionCode,
                    )
                } catch (_: Throwable) {
                } finally {
                    previousHandler?.uncaughtException(thread, throwable)
                }
            }
            installedHandler = handler
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }

    fun recordCrash(
        crashDir: File,
        threadName: String,
        throwable: Throwable,
        recentLogs: List<String>,
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        timestampMs: Long = System.currentTimeMillis(),
    ): CrashReport {
        val redactedTrace = DiagnosticsLogger.redact(throwable.stackTraceToString())
        val rawText = formatCrashReport(
            threadName = threadName,
            stackTrace = redactedTrace,
            recentLogs = recentLogs,
            versionName = versionName,
            versionCode = versionCode,
            timestampMs = timestampMs,
        )
        synchronized(lock) {
            runCatching {
                if (!crashDir.exists()) crashDir.mkdirs()
                val tmp = File(crashDir, "$LAST_CRASH_FILE.tmp")
                FileOutputStream(tmp).use { fos ->
                    fos.write(rawText.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
                val dest = File(crashDir, LAST_CRASH_FILE)
                if (!tmp.renameTo(dest)) {
                    dest.writeText(rawText, Charsets.UTF_8)
                    tmp.delete()
                }
                val historyFile = File(crashDir, "crash_$timestampMs.txt")
                historyFile.writeText(rawText, Charsets.UTF_8)
                pruneHistoryLocked(crashDir)
                _lastCrashText.value = rawText
            }
        }
        return CrashReport(
            timestampMs = timestampMs,
            threadName = threadName,
            appVersion = versionName,
            buildCode = versionCode,
            stackTrace = redactedTrace,
            recentLogs = recentLogs,
            rawText = rawText,
        )
    }

    fun formatCrashReport(
        threadName: String,
        stackTrace: String,
        recentLogs: List<String>,
        versionName: String,
        versionCode: Int,
        timestampMs: Long,
    ): String {
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        return buildString {
            appendLine("=== VELVET UNCAUGHT CRASH REPORT ===")
            appendLine("Time: ${timeFmt.format(Date(timestampMs))}")
            appendLine("App: $versionName (code $versionCode)")
            appendLine("Thread: $threadName")
            appendLine()
            appendLine("--- STACK TRACE ---")
            appendLine(stackTrace.trimEnd())
            appendLine()
            appendLine("--- LAST ${recentLogs.size} DIAGNOSTIC LOG LINES ---")
            if (recentLogs.isEmpty()) {
                appendLine("(none recorded)")
            } else {
                recentLogs.forEach { appendLine(it) }
            }
        }
    }

    fun readLastCrash(crashDir: File? = crashDirectory): String? {
        val dir = crashDir ?: return null
        val file = File(dir, LAST_CRASH_FILE)
        return if (file.exists()) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun clearLastCrash(crashDir: File? = crashDirectory) {
        synchronized(lock) {
            val dir = crashDir ?: crashDirectory
            if (dir != null) {
                runCatching { File(dir, LAST_CRASH_FILE).delete() }
                runCatching { File(dir, "$LAST_CRASH_FILE.tmp").delete() }
            }
            _lastCrashText.value = null
        }
    }

    private fun pruneHistoryLocked(crashDir: File) {
        val history = crashDir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        history.drop(MAX_HISTORY_FILES).forEach { runCatching { it.delete() } }
    }
}
