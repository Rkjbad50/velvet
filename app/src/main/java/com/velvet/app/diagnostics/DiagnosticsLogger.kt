package com.velvet.app.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/**
 * Thread-safe 500-line in-memory ring buffer logger.
 * Automatically redacts signed streaming URLs, authorization tokens, and cookies
 * before storing and logging, exposing only host + videoId for signed URLs.
 */
object DiagnosticsLogger {
    const val MAX_CAPACITY = 500

    private val lock = Any()
    private val ringBuffer = ArrayDeque<LogEntry>(MAX_CAPACITY)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

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

    fun clear() {
        synchronized(lock) {
            ringBuffer.clear()
            _entries.value = emptyList()
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
