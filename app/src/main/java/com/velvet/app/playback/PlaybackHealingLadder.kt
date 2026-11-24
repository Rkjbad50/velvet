package com.velvet.app.playback

import androidx.media3.common.PlaybackException
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.LastErrorTracker

enum class PlaybackErrorCategory {
    TRANSIENT_IO,            // timeout, connection reset, socket error
    EXPIRED_OR_FORBIDDEN,    // HTTP 403, 410, URL expired
    STREAM_CANDIDATE_FAILED, // bad stream candidate, unsupported format, 404
    UNKNOWN
}

sealed interface HealingDecision {
    data object NoAction : HealingDecision
    data class RetryTransient(val attempt: Int, val backoffMs: Long) : HealingDecision
    data class InvalidateAndReResolve(val videoId: String) : HealingDecision
    data class TryNextCandidate(val videoId: String) : HealingDecision
    data class TryNextClient(val videoId: String) : HealingDecision
    data class SkipTrack(val failedVideoId: String) : HealingDecision
    data class CircuitBreakerTrip(
        val failedVideoId: String,
        val consecutiveFailures: Int,
        val showBanner: Boolean,
        val bannerMessage: String,
    ) : HealingDecision
}

interface Clock {
    fun currentTimeMillis(): Long
}

class SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

interface HealingDiagnostics {
    fun log(tag: String, message: String)
    fun recordError(error: Throwable, videoId: String?, host: String?, customErrorCodeName: String?)
}

object DefaultHealingDiagnostics : HealingDiagnostics {
    override fun log(tag: String, message: String) {
        DiagnosticsLogger.w(tag, message)
    }

    override fun recordError(error: Throwable, videoId: String?, host: String?, customErrorCodeName: String?) {
        LastErrorTracker.record(
            error = error,
            videoId = videoId,
            url = host?.let { "https://$it/videoplayback" },
            customErrorCodeName = customErrorCodeName,
        )
    }
}

/**
 * Pure-Kotlin state machine implementing the W3-B Self-healing playback ladder:
 * 1. Transient IO error (timeout, connection reset): 2 retries with exponential backoff
 * 2. HTTP 403/410/expired URL: invalidate cache entry, re-resolve, retry
 * 3. Next stream candidate (itag/bitrate), then next client (VisionOS Fast <-> NewPipeExtractor Full)
 * 4. Still failing: skip to next queue item (do not stall)
 * 5. 3 DIFFERENT songs in a row fail with the same error class: stop auto-skipping, pause,
 *    and show ONE banner (max once per 10 minutes).
 */
class PlaybackHealingLadder(
    var selfHealingEnabled: Boolean = true,
    private val clock: Clock = SystemClock(),
    private val baseBackoffMs: Long = 500L,
    private val diagnostics: HealingDiagnostics = DefaultHealingDiagnostics,
) {
    companion object {
        const val BANNER_RATE_LIMIT_MS = 10 * 60_000L // 10 minutes
        const val BANNER_TEXT =
            "Streaming looks broken (likely YouTube changed). Open Developer options or check for update."

        fun classifyError(error: Throwable): PlaybackErrorCategory {
            val causes = generateSequence(error) { it.cause }.toList()
            for (t in causes) {
                val name = t.javaClass.simpleName
                val msg = (t.message ?: "").lowercase()

                if (msg.contains("403") || msg.contains("410") || msg.contains("forbidden") || msg.contains("expired")) {
                    return PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN
                }

                if (name.contains("SocketTimeout") || name.contains("ConnectException") || name.contains("UnknownHost")
                    || msg.contains("timeout") || msg.contains("connection reset") || msg.contains("broken pipe")
                    || msg.contains("connection abort")
                ) {
                    return PlaybackErrorCategory.TRANSIENT_IO
                }

                if (t is PlaybackException) {
                    when (t.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                            return PlaybackErrorCategory.TRANSIENT_IO
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                            if (msg.contains("403") || msg.contains("410")) return PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN
                        }
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                            return PlaybackErrorCategory.STREAM_CANDIDATE_FAILED
                    }
                }
            }
            return PlaybackErrorCategory.UNKNOWN
        }
    }

    data class SongLadderState(
        val videoId: String,
        var transientRetries: Int = 0,
        var cacheInvalidatedAndRetried: Boolean = false,
        var triedNextCandidate: Boolean = false,
        var triedNextClient: Boolean = false,
        var skipped: Boolean = false,
    )

    private val songStates = mutableMapOf<String, SongLadderState>()
    private val consecutiveFailedSongs = mutableListOf<String>()
    private var lastFailedCategory: PlaybackErrorCategory? = null
    private var lastBannerShownTimestamp: Long = 0L

    fun getSongState(videoId: String): SongLadderState =
        songStates.getOrPut(videoId) { SongLadderState(videoId) }

    fun onPlaybackSuccess(videoId: String) {
        consecutiveFailedSongs.clear()
        lastFailedCategory = null
        songStates.remove(videoId)
        diagnostics.log("SelfHealing", "Playback succeeded for videoId=$videoId; consecutive failures reset.")
    }

    fun handleFailure(
        videoId: String,
        errorCategory: PlaybackErrorCategory,
        error: Throwable? = null,
        host: String? = null,
    ): HealingDecision {
        if (!selfHealingEnabled) {
            diagnostics.log("SelfHealing", "Self-healing disabled; skipping ladder for videoId=$videoId")
            return HealingDecision.NoAction
        }

        val state = getSongState(videoId)

        // Never loop forever if track was already skipped
        if (state.skipped) {
            diagnostics.log("SelfHealing", "Track $videoId was already skipped; ignoring subsequent errors to avoid infinite loop.")
            return HealingDecision.NoAction
        }

        val exception = error ?: RuntimeException("Playback failure: $errorCategory")

        // 1. Transient IO error: 2 retries with exponential backoff
        if (errorCategory == PlaybackErrorCategory.TRANSIENT_IO && state.transientRetries < 2) {
            state.transientRetries++
            val backoffMs = baseBackoffMs * (1L shl (state.transientRetries - 1))
            val detail = "Step 1: Transient IO retry ${state.transientRetries}/2 (backoff: ${backoffMs}ms) for videoId=$videoId [host=$host]"
            diagnostics.log("SelfHealing", detail)
            diagnostics.recordError(exception, videoId, host, "TRANSIENT_IO_RETRY_${state.transientRetries}")
            return HealingDecision.RetryTransient(state.transientRetries, backoffMs)
        }

        // 2. HTTP 403/410/expired URL (or after transient retries exhausted): invalidate cache & re-resolve
        if (!state.cacheInvalidatedAndRetried && (errorCategory == PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN || errorCategory == PlaybackErrorCategory.TRANSIENT_IO)) {
            state.cacheInvalidatedAndRetried = true
            val detail = "Step 2: Invalidate cache and re-resolve stream for videoId=$videoId [host=$host]"
            diagnostics.log("SelfHealing", detail)
            diagnostics.recordError(exception, videoId, host, "CACHE_INVALIDATED_RE_RESOLVE")
            return HealingDecision.InvalidateAndReResolve(videoId)
        }

        // 3a. Try next stream candidate (itag/bitrate)
        if (!state.triedNextCandidate) {
            state.triedNextCandidate = true
            val detail = "Step 3a: Try next stream candidate (itag/bitrate) for videoId=$videoId [host=$host]"
            diagnostics.log("SelfHealing", detail)
            diagnostics.recordError(exception, videoId, host, "NEXT_STREAM_CANDIDATE")
            return HealingDecision.TryNextCandidate(videoId)
        }

        // 3b. Try next client (VisionOS Fast <-> NewPipeExtractor Full)
        if (!state.triedNextClient) {
            state.triedNextClient = true
            val detail = "Step 3b: Switch to alternate extractor client for videoId=$videoId [host=$host]"
            diagnostics.log("SelfHealing", detail)
            diagnostics.recordError(exception, videoId, host, "NEXT_CLIENT_FALLBACK")
            return HealingDecision.TryNextClient(videoId)
        }

        // 4. Still failing: skip to next queue item (do not stall)
        state.skipped = true
        val detail = "Step 4: All healing steps exhausted for videoId=$videoId. Skipping track."
        diagnostics.log("SelfHealing", detail)
        diagnostics.recordError(exception, videoId, host, "SKIP_FAILED_TRACK")

        // 5. 3 DIFFERENT songs in a row fail with the same error class
        if (lastFailedCategory != errorCategory) {
            lastFailedCategory = errorCategory
            consecutiveFailedSongs.clear()
        }
        if (!consecutiveFailedSongs.contains(videoId)) {
            consecutiveFailedSongs.add(videoId)
        }

        if (consecutiveFailedSongs.size >= 3) {
            val now = clock.currentTimeMillis()
            val showBanner = (lastBannerShownTimestamp == 0L) || (now - lastBannerShownTimestamp >= BANNER_RATE_LIMIT_MS)
            if (showBanner) {
                lastBannerShownTimestamp = now
            }
            diagnostics.log("SelfHealing", "Step 5: 3 different songs failed consecutively with $errorCategory! Circuit breaker tripped.")
            return HealingDecision.CircuitBreakerTrip(
                failedVideoId = videoId,
                consecutiveFailures = consecutiveFailedSongs.size,
                showBanner = showBanner,
                bannerMessage = BANNER_TEXT,
            )
        }

        return HealingDecision.SkipTrack(failedVideoId = videoId)
    }

    fun reset() {
        songStates.clear()
        consecutiveFailedSongs.clear()
        lastFailedCategory = null
    }

    fun resetAll() {
        reset()
        lastBannerShownTimestamp = 0L
    }

    val consecutiveFailuresCount: Int get() = consecutiveFailedSongs.size
}
