package com.velvet.app.diagnostics

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/**
 * Tracks the last playback or extraction error with URL host-only redaction and client metadata.
 */
object LastErrorTracker {
    private val _lastError = MutableStateFlow<LastErrorInfo?>(null)
    val lastError: StateFlow<LastErrorInfo?> = _lastError.asStateFlow()

    // Last stream resolution metadata for context when player errors occur
    @Volatile var lastResolvedVideoId: String? = null
    @Volatile var lastResolvedClient: String? = null
    @Volatile var lastResolveDurationMs: Long? = null

    fun record(
        error: Throwable,
        videoId: String? = null,
        clientUsed: String? = null,
        resolveDurationMs: Long? = null,
        url: String? = null,
        customErrorCodeName: String? = null,
    ) {
        val codeName = customErrorCodeName ?: when (error) {
            is PlaybackException -> error.errorCodeName
            else -> error.javaClass.simpleName
        }

        val causes = generateSequence(error) { it.cause }
            .map { "${it.javaClass.simpleName}: ${it.message ?: "no message"}" }
            .toList()

        val httpException = generateSequence(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()

        val httpStatus = httpException?.responseCode

        val effectiveUrl = url ?: httpException?.dataSpec?.uri?.toString() ?: generateSequence(error) { it.cause }
            .filterIsInstance<HttpDataSource.HttpDataSourceException>()
            .firstOrNull()?.dataSpec?.uri?.toString()

        val hostOnly = effectiveUrl?.let { raw ->
            runCatching { URI(raw).host }.getOrNull()
                ?: raw.substringAfter("://").substringBefore("/").substringBefore("?")
        }

        val vid = videoId ?: lastResolvedVideoId
        val client = clientUsed ?: lastResolvedClient
        val duration = resolveDurationMs ?: lastResolveDurationMs

        val info = LastErrorInfo(
            errorCodeName = codeName,
            causeChain = causes,
            httpStatus = httpStatus,
            urlHost = hostOnly,
            videoId = vid,
            clientUsed = client,
            resolveDurationMs = duration,
            timestamp = System.currentTimeMillis(),
        )

        _lastError.value = info
        DiagnosticsLogger.e("LastErrorTracker", "Last error recorded: $codeName (host=$hostOnly, status=$httpStatus, videoId=$vid)", error)
    }

    fun clear() {
        _lastError.value = null
    }
}
