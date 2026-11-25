package com.velvet.app.unit

import androidx.media3.common.Player
import com.velvet.app.ui.OriginTransformCalculator
import com.velvet.app.ui.PlaybackControlSync
import com.velvet.app.ui.SeekbarMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies playback Play/Pause reliability, seekbar & timeline synchronization,
 * optimistic IPC state protection, and smoothly damped app animation specs.
 */
class PlayerSyncAndMotionTest {

    @Test
    fun pauseWorksDuringBufferingEvenWhenIsPlayingIsTemporarilyFalse() {
        val shouldPause = PlaybackControlSync.shouldPauseOnToggle(
            optimisticPlay = null,
            playWhenReady = true,
            playbackState = Player.STATE_BUFFERING,
            isPlaying = false,
            stateEnded = Player.STATE_ENDED,
            stateIdle = Player.STATE_IDLE,
        )
        assertTrue("Tapping Pause while streaming is buffering must pause rather than re-trigger play", shouldPause)

        val displayedPlaying = PlaybackControlSync.resolveDisplayedPlaying(
            exoIsPlaying = false,
            exoPlayWhenReady = true,
            exoPlaybackState = Player.STATE_BUFFERING,
            stateEnded = Player.STATE_ENDED,
            stateBuffering = Player.STATE_BUFFERING,
            optimisticPlay = null,
        )
        assertTrue("Play/Pause button must remain in active state during brief buffer dips", displayedPlaying)
    }

    @Test
    fun optimisticToggleHoldsUserIntentAcrossStaleBinderSnapshots() {
        val pausedDisplay = PlaybackControlSync.resolveDisplayedPlaying(
            exoIsPlaying = true,
            exoPlayWhenReady = true,
            exoPlaybackState = Player.STATE_READY,
            stateEnded = Player.STATE_ENDED,
            stateBuffering = Player.STATE_BUFFERING,
            optimisticPlay = false,
        )
        assertFalse("UI must reflect immediate Pause click without uneven bounce back", pausedDisplay)

        val bufferingAfterPause = PlaybackControlSync.resolveDisplayedBuffering(
            exoPlaybackState = Player.STATE_BUFFERING,
            stateBuffering = Player.STATE_BUFFERING,
            displayedPlaying = pausedDisplay,
            exoIsPlaying = false,
        )
        assertFalse("Paused track must never spin buffering indicator", bufferingAfterPause)
    }

    @Test
    fun seekToTargetIsPreservedAcrossAsyncSeekAndHandsOffCleanlyOnceConverged() {
        val stalePreSeek = PlaybackControlSync.reconcilePositionMs(
            rawPosMs = 15_000L,
            pendingSeekMs = 120_000L,
            pendingUntilMs = 2_000L,
            nowMs = 1_000L,
            savedPosMs = 120_000L,
            hasMediaItems = true,
            isPlayActive = true,
        )
        assertEquals(120_000L, stalePreSeek.positionMs)
        assertFalse(stalePreSeek.clearPendingSeek)

        val converged = PlaybackControlSync.reconcilePositionMs(
            rawPosMs = 120_350L,
            pendingSeekMs = 120_000L,
            pendingUntilMs = 2_000L,
            nowMs = 1_300L,
            savedPosMs = 120_000L,
            hasMediaItems = true,
            isPlayActive = true,
        )
        assertEquals(120_350L, converged.positionMs)
        assertTrue(converged.clearPendingSeek)
    }

    @Test
    fun activeTrackAtZeroPositionDoesNotLeakPreviousSongTimestamp() {
        val startOfNewSong = PlaybackControlSync.reconcilePositionMs(
            rawPosMs = 0L,
            pendingSeekMs = null,
            pendingUntilMs = 0L,
            nowMs = 5_000L,
            savedPosMs = 185_000L,
            hasMediaItems = true,
            isPlayActive = true,
        )
        assertEquals(0L, startOfNewSong.positionMs)
        assertEquals(0L, startOfNewSong.savedPositionMs)
    }

    @Test
    fun seekbarMathAndSmoothMotionSpringsAreSafeAndCriticallyDamped() {
        assertEquals(0f, SeekbarMath.fractionForPosition(10_000L, 0L), 0.0001f)
        assertEquals(0f, SeekbarMath.clampFraction(Float.NaN), 0.0001f)
        assertEquals(1f, SeekbarMath.fractionForPosition(250_000L, 200_000L), 0.0001f)
        assertEquals(90_000L, SeekbarMath.positionForFraction(0.45f, 200_000L))

        assertTrue(OriginTransformCalculator.windowOpenSpring.dampingRatio >= 0.90f)
        assertTrue(OriginTransformCalculator.windowCloseSpring.dampingRatio >= 0.90f)
    }
}
