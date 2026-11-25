package com.velvet.app.ui

import kotlin.math.abs

/**
 * Pure, deterministic playback & timeline synchronization rules shared by [PlayerConnection]
 * and unit-tested offline so UI transport controls, buffering state, and seekbar position
 * remain locked in sync with actual audio playback.
 */
object PlaybackControlSync {

    data class PositionSyncResult(
        val positionMs: Long,
        val savedPositionMs: Long,
        val clearPendingSeek: Boolean,
    )

    /**
     * Determines whether tapping Play/Pause should request Pause rather than Play.
     * Crucially respects playback intent ([playWhenReady]) even while temporarily buffering,
     * so tapping Pause while a stream is buffering pauses immediately instead of being ignored.
     */
    fun shouldPauseOnToggle(
        optimisticPlay: Boolean?,
        playWhenReady: Boolean,
        playbackState: Int,
        isPlaying: Boolean,
        stateEnded: Int,
        stateIdle: Int,
    ): Boolean {
        if (optimisticPlay != null) return optimisticPlay
        if (isPlaying) return true
        return playWhenReady && playbackState != stateEnded && playbackState != stateIdle
    }

    /**
     * Resolves whether UI Play/Pause controls should display active playback (Pause button)
     * or idle/paused (Play button), holding recent user tap intent across brief IPC latency
     * and ignoring micro-buffer dips when [playWhenReady] remains true.
     */
    fun resolveDisplayedPlaying(
        exoIsPlaying: Boolean,
        exoPlayWhenReady: Boolean,
        exoPlaybackState: Int,
        stateEnded: Int,
        stateBuffering: Int,
        optimisticPlay: Boolean?,
    ): Boolean {
        if (optimisticPlay != null) return optimisticPlay
        if (exoPlaybackState == stateEnded) return false
        return exoIsPlaying || (exoPlayWhenReady && exoPlaybackState == stateBuffering)
    }

    /**
     * Resolves whether the Play/Pause control should show a subtle buffering indicator.
     * Never spins when the user paused or after playback ended.
     */
    fun resolveDisplayedBuffering(
        exoPlaybackState: Int,
        stateBuffering: Int,
        displayedPlaying: Boolean,
        exoIsPlaying: Boolean,
    ): Boolean = displayedPlaying && exoPlaybackState == stateBuffering && !exoIsPlaying

    /**
     * Reconciles live player timeline position with optimistic user seeks and track transitions.
     * Prevents pre-seek position bounce-back during async seek buffering and avoids returning
     * a previous song's timestamp when starting a fresh track at 0ms.
     */
    fun reconcilePositionMs(
        rawPosMs: Long?,
        pendingSeekMs: Long?,
        pendingUntilMs: Long,
        nowMs: Long,
        savedPosMs: Long,
        hasMediaItems: Boolean,
        isPlayActive: Boolean,
    ): PositionSyncResult {
        val safeRaw = rawPosMs?.coerceAtLeast(0L)
        if (pendingSeekMs != null && nowMs < pendingUntilMs) {
            if (safeRaw != null && abs(safeRaw - pendingSeekMs) <= 1_000L) {
                return PositionSyncResult(
                    positionMs = safeRaw,
                    savedPositionMs = safeRaw,
                    clearPendingSeek = true,
                )
            }
            return PositionSyncResult(
                positionMs = pendingSeekMs.coerceAtLeast(0L),
                savedPositionMs = pendingSeekMs.coerceAtLeast(0L),
                clearPendingSeek = false,
            )
        }

        if (hasMediaItems && safeRaw != null) {
            if (safeRaw > 0L || isPlayActive) {
                return PositionSyncResult(
                    positionMs = safeRaw,
                    savedPositionMs = safeRaw,
                    clearPendingSeek = pendingSeekMs != null,
                )
            }
        }

        val fallback = savedPosMs.coerceAtLeast(0L)
        return PositionSyncResult(
            positionMs = fallback,
            savedPositionMs = fallback,
            clearPendingSeek = pendingSeekMs != null,
        )
    }
}

/** Pure seekbar math helpers for safe normalized timeline fractions. */
object SeekbarMath {
    fun clampFraction(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    fun fractionForPosition(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun positionForFraction(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (clampFraction(fraction) * durationMs.toFloat()).toLong().coerceIn(0L, durationMs)
    }
}
