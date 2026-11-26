package com.velvet.app.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe 500-line in-memory ring buffer logger with rolling persistent log file support.
 * Automatically redacts signed streaming URLs, authorization tokens, and cookies
 * before storing and logging, exposing only host + videoId for signed URLs.
 */
object DiagnosticsLogger {
    const val MAX_CAPACITY = 500
    const val MAX_FILE_BYTES = 256 * 1024L

    private val lock = Any()
    private val ringBuffer = ArrayDeque<LogEntry>(MAX_CAPACITY)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var logDir: File? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun attachLogDirectory(dir: File) {
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                logDir = dir
                if (ringBuffer.isEmpty()) {
                    loadTailFromDiskLocked(dir)
                }
            }
        }
    }

    fun detachLogDirectoryForTest() {
        synchronized(lock) {
            logDir = null
        }
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val sanitized = redact(message)
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = sanitized
        )

        synchronized(lock) {
            if (ringBuffer.size >= MAX_CAPACITY) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(entry)
            _entries.value = ringBuffer.toList()
            appendEntryToDiskLocked(entry)
        }

        runCatching {
            when (level) {
                LogLevel.INFO -> Log.i(tag, sanitized)
                LogLevel.WARN -> Log.w(tag, sanitized)
                LogLevel.ERROR -> Log.e(tag, sanitized)
            }
        }
    }

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val detail = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        log(LogLevel.ERROR, tag, detail)
    }

    fun tailFormatted(limit: Int = 200): List<String> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            if (ringBuffer.isNotEmpty()) {
                return ringBuffer.takeLast(limit).map(::formatEntry)
            }
            val dir = logDir ?: return emptyList()
            val logFile = File(dir, "diagnostics.log")
            if (!logFile.exists()) return emptyList()
            return runCatching {
                logFile.readLines().takeLast(limit)
            }.getOrDefault(emptyList())
        }
    }

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
            _entries.value = emptyList()
            logDir?.let { dir ->
                runCatching { File(dir, "diagnostics.log").delete() }
                runCatching { File(dir, "diagnostics.log.1").delete() }
            }
        }
    }

    private fun formatEntry(entry: LogEntry): String {
        val formattedTime = synchronized(timeFormat) {
            timeFormat.format(Date(entry.timestamp))
        }
        return "$formattedTime | ${entry.level.name} | [${entry.tag}] ${entry.message.replace('\n', ' ')}"
    }

    private fun appendEntryToDiskLocked(entry: LogEntry) {
        val dir = logDir ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val currentFile = File(dir, "diagnostics.log")
            if (currentFile.exists() && currentFile.length() >= MAX_FILE_BYTES) {
                val backup = File(dir, "diagnostics.log.1")
                if (backup.exists()) backup.delete()
                currentFile.renameTo(backup)
            }
            val line = formatEntry(entry) + "\n"
            FileOutputStream(currentFile, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        }
    }

    private fun loadTailFromDiskLocked(dir: File) {
        val currentFile = File(dir, "diagnostics.log")
        val backupFile = File(dir, "diagnostics.log.1")
        val lines = buildList {
            if (backupFile.exists()) {
                runCatching { addAll(backupFile.readLines().takeLast(MAX_CAPACITY)) }
            }
            if (currentFile.exists()) {
                runCatching { addAll(currentFile.readLines().takeLast(MAX_CAPACITY)) }
            }
        }.takeLast(MAX_CAPACITY)
        for (line in lines) {
            val parts = line.split(" | ", limit = 3)
            if (parts.size == 3) {
                val level = runCatching { LogLevel.valueOf(parts[1].trim()) }.getOrDefault(LogLevel.INFO)
                val ts = runCatching {
                    synchronized(timeFormat) { timeFormat.parse(parts[0].trim())?.time }
                }.getOrNull() ?: System.currentTimeMillis()
                val rest = parts[2]
                val tag = rest.substringAfter("[", "Persisted").substringBefore("]", "Persisted")
                val msg = rest.substringAfter("] ", rest)
                if (ringBuffer.size >= MAX_CAPACITY) ringBuffer.removeFirst()
                ringBuffer.addLast(LogEntry(timestamp = ts, level = level, tag = tag, message = msg))
            }
        }
        if (ringBuffer.isNotEmpty()) {
            _entries.value = ringBuffer.toList()
        }
    }

    /**
     * Redacts sensitive information:
     * - Cookies: Cookie: ..., Set-Cookie: ...
     * - Authorization tokens, account credentials, bearer tokens
     * - Signed streaming URLs: stripped to host + videoId only
     */
    fun redact(input: String): String {
        var text = input

        // 1. Redact cookies
        text = text.replace(Regex("""(?i)(cookie\s*[:=]\s*)([^\r\n;,]+)""")) {
            "${it.groupValues[1]}[REDACTED_COOKIE]"
        }
        text = text.replace(Regex("""(?i)\b(set-cookie\s*[:=]\s*)([^\r\n]+)""")) {
            "${it.groupValues[1]}[REDACTED_COOKIE]"
        }
        text = text.replace(Regex("""(?i)\b(SAPISID|SSID|HSID|SID|APISID)=([^;\s&]+)""")) {
            "${it.groupValues[1]}=[REDACTED_COOKIE]"
        }

        // 2. Redact accounts, bearer tokens, passwords
        text = text.replace(Regex("""(?i)(authorization\s*[:=]\s*Bearer\s+)[^\r\n;,]+""")) {
            "${it.groupValues[1]}[REDACTED_AUTH]"
        }
        text = text.replace(Regex("""(?i)\b(access_token|refresh_token|id_token|auth_token|api_key)=([^\s&]+)""")) {
            "${it.groupValues[1]}=[REDACTED_AUTH]"
        }

        // 3. Redact signed URLs (googlevideo, or URLs with signature / expire) to host + videoId only
        text = text.replace(Regex("""https?://[^\s"'<>\)]+""")) { matchResult ->
            redactUrl(matchResult.value)
        }

        return text
    }

    fun redactUrl(urlString: String): String {
        val uri = runCatching { URI(urlString) }.getOrNull() ?: return urlString
        val host = uri.host ?: return urlString

        val isSigned = host.contains("googlevideo.com") ||
            urlString.contains("expire=") ||
            urlString.contains("signature=") ||
            urlString.contains("sig=")

        if (isSigned) {
            val videoId = Regex("""[?&](?:v|docid|id)=([a-zA-Z0-9_-]{11})""").find(urlString)?.groupValues?.get(1)
                ?: Regex("""/([a-zA-Z0-9_-]{11})(?:/|\?|$)""").find(urlString)?.groupValues?.get(1)
            val path = uri.path?.take(15) ?: ""
            return if (videoId != null) {
                "https://$host$path... [videoId=$videoId]"
            } else {
                "https://$host$path... [signed params redacted]"
            }
        }
        return urlString
    }
}
