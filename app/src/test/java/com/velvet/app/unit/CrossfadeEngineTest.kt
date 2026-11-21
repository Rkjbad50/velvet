package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.playback.Clock
import com.velvet.app.playback.CrossfadeEngine
import com.velvet.app.playback.CrossfadeEqualPower
import com.velvet.app.playback.CrossfadePlayer
import com.velvet.app.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CrossfadeEngineTest {

    private class FakeClock(var current: Long = 10_000L) : Clock {
        override fun currentTimeMillis(): Long = current
        fun advance(ms: Long) {
            current += ms
        }
    }

    private class FakeCrossfadePlayer(override val id: String) : CrossfadePlayer {
        override var currentSong: Song? = null
        override var durationMs: Long = 100_000L
        override var currentPositionMs: Long = 0L
        override var isPlaying: Boolean = false
        override var isReady: Boolean = true
        override var isEnded: Boolean = false
        override var volume: Float = 1.0f
        override var playWhenReady: Boolean = false

        var prepareCount = 0
        var playCount = 0
        var pauseCount = 0
        var stopCount = 0
        var resetCount = 0
        var lastSoughtPosition: Long = -1L

        override fun prepare(song: Song) {
            prepareCount++
            currentSong = song
        }

        override fun play() {
            playCount++
            isPlaying = true
        }

        override fun pause() {
            pauseCount++
            isPlaying = false
        }

        override fun stop() {
            stopCount++
            isPlaying = false
        }

        override fun seekTo(positionMs: Long) {
            lastSoughtPosition = positionMs
            currentPositionMs = positionMs
        }

        override fun reset() {
            resetCount++
            currentSong = null
            isPlaying = false
        }
    }

    private fun sampleSong(id: String, title: String) = Song(
        id = id,
        title = title,
        artist = "Test Artist",
        duration = 100,
    )

    // ── 1. Mathematical Equal-Power Curve Tests ──────────────────────────────

    @Test
    fun equalPowerCurves_powerIsConstantAtAllPoints() {
        var t = 0.0f
        while (t <= 1.0f) {
            val totalPower = CrossfadeEqualPower.totalPower(t)
            assertEquals("Power dip at t=$t", 1.0f, totalPower, 0.001f)
            t += 0.05f
        }
    }

    @Test
    fun equalPowerCurves_exactEndpoints() {
        assertEquals(1.0f, CrossfadeEqualPower.volA(0.0f), 0.0001f)
        assertEquals(0.0f, CrossfadeEqualPower.volB(0.0f), 0.0001f)

        assertEquals(0.0f, CrossfadeEqualPower.volA(1.0f), 0.0001f)
        assertEquals(1.0f, CrossfadeEqualPower.volB(1.0f), 0.0001f)
    }

    @Test
    fun equalPowerCurves_midpointEliminatesVolumeDip() {
        val midA = CrossfadeEqualPower.volA(0.5f)
        val midB = CrossfadeEqualPower.volB(0.5f)
        // In linear crossfade: midA = 0.5, midB = 0.5 -> power = 0.50 (-3dB dip!)
        // In equal-power: midA ≈ 0.7071, midB ≈ 0.7071 -> power = 1.00 (NO DIP!)
        assertEquals(0.7071f, midA, 0.001f)
        assertEquals(0.7071f, midB, 0.001f)
        assertEquals(1.0f, (midA * midA) + (midB * midB), 0.001f)
    }

    @Test
    fun equalPowerCurves_monotonicity() {
        var prevA = CrossfadeEqualPower.volA(0.0f)
        var prevB = CrossfadeEqualPower.volB(0.0f)
        var t = 0.05f
        while (t <= 1.0f) {
            val curA = CrossfadeEqualPower.volA(t)
            val curB = CrossfadeEqualPower.volB(t)
            assertTrue("volA must decrease monotonically: $curA <= $prevA", curA <= prevA + 0.0001f)
            assertTrue("volB must increase monotonically: $curB >= $prevB", curB >= prevB - 0.0001f)
            prevA = curA
            prevB = curB
            t += 0.05f
        }
    }

    // ── 2. Timeline Trigger & Preload Tests ───────────────────────────────────

    @Test
    fun timeline_preloadsAtTMinusFadePlus10s() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        var preloadedSong: Song? = null
        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
            onPreloadTrack = { preloadedSong = it },
        )

        // Preload threshold: T - (5s + 10s) = 85s (15s remaining)
        // Position 80s -> 20s remaining (before threshold): not preloaded yet
        pA.currentPositionMs = 80_000L
        engine.tick()
        assertFalse(engine.isPreloaded)
        assertNull(preloadedSong)
        assertEquals(0, pB.prepareCount)

        // Position 85s -> 15s remaining (reaches threshold): prepares standby player
        pA.currentPositionMs = 85_000L
        engine.tick()
        assertTrue(engine.isPreloaded)
        assertEquals("s2", engine.preloadingSongId)
        assertEquals(song2, preloadedSong)
        assertEquals(1, pB.prepareCount)
        assertEquals(0.0f, pB.volume, 0.001f)
        assertFalse(pB.playWhenReady)
    }

    @Test
    fun timeline_startsCrossfadeAtTMinusFade_andShowsNewTrackImmediately() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        var transitionedSong: Song? = null
        var measuredGap: Long = -1L

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
            onTrackTransition = { transitionedSong = it },
            onGapMeasured = { measuredGap = it },
        )

        // Trigger preload first
        pA.currentPositionMs = 85_000L
        engine.tick()

        // Position 94s -> 6s remaining (before 5s fade threshold): not crossfading yet
        pA.currentPositionMs = 94_000L
        engine.tick()
        assertFalse(engine.isCrossfading)
        assertNull(transitionedSong)

        // Position 95s -> 5s remaining (crossfade window reached): starts crossfade!
        pA.currentPositionMs = 95_000L
        engine.tick()

        assertTrue(engine.isCrossfading)
        assertEquals(song2, transitionedSong) // UI shows NEW track from crossfade start!
        assertEquals(0L, measuredGap) // Overlapping playback = 0ms gap!
        assertTrue(pB.isPlaying)
        assertTrue(pB.playWhenReady)
    }

    @Test
    fun timeline_equalPowerVolumeProgression_andCompletionAtT() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        var promotedPlayer: CrossfadePlayer? = null
        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 4.0f }, // 4 second fade
            onActivePlayerChanged = { promotedPlayer = it },
        )

        // Start crossfade at 96s (4s remaining)
        pA.currentPositionMs = 96_000L
        engine.tick()
        assertTrue(engine.isCrossfading)

        // Midpoint: 2.0s elapsed (50% progress)
        clock.advance(2000L)
        engine.tick()
        assertEquals(0.7071f, pA.volume, 0.01f)
        assertEquals(0.7071f, pB.volume, 0.01f)
        assertEquals(1.0f, (pA.volume * pA.volume) + (pB.volume * pB.volume), 0.01f)
        assertTrue(engine.isCrossfading)

        // Full duration: 4.0s elapsed (100% progress)
        clock.advance(2000L)
        engine.tick()

        // Finished! Player A stopped, Player B promoted to active with vol=1.0
        assertFalse(engine.isCrossfading)
        assertEquals(0.0f, pA.volume, 0.001f)
        assertEquals(1.0f, pB.volume, 0.001f)
        assertEquals(pB, engine.activePlayer)
        assertEquals(pA, engine.standbyPlayer)
        assertEquals(pB, promotedPlayer)
        assertEquals(1, pA.stopCount)
        assertEquals(1, pA.resetCount)
    }

    // ── 3. Fallback & Edge Cases ──────────────────────────────────────────────

    @Test
    fun fallback_whenStandbyNotReadyAtTMinusFade_waitsAndFallsBackTo300ms() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true
        pB.isReady = false // Still buffering!

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
        )

        // At 95s (T-5s): standby is NOT ready, so crossfade does not start prematurely
        pA.currentPositionMs = 95_000L
        engine.tick()
        assertFalse(engine.isCrossfading)

        // At 99.8s (T-200ms): standby finishes buffering and becomes ready
        pA.currentPositionMs = 99_800L
        pB.isReady = true
        engine.tick()

        // Triggers 300ms fallback crossfade!
        assertTrue(engine.isCrossfading)
        assertEquals(300L, engine.crossfadeDurationMs)
    }

    @Test
    fun manualTransition_triggers250msQuickCrossfade() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song3 = sampleSong("s3", "Song 3")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        var transitionedSong: Song? = null
        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { null },
            getCrossfadeSeconds = { 5.0f },
            onTrackTransition = { transitionedSong = it },
        )

        // Manual skip to song3
        engine.manualTransition(song3)

        assertTrue(engine.isCrossfading)
        assertEquals(250L, engine.crossfadeDurationMs)
        assertEquals(song3, transitionedSong)
        assertEquals(song3, pB.currentSong)
        assertTrue(pB.isPlaying)

        // 125ms elapsed (50% progress)
        clock.advance(125L)
        engine.tick()
        assertEquals(0.7071f, pA.volume, 0.01f)
        assertEquals(0.7071f, pB.volume, 0.01f)

        // 250ms elapsed: completes transition
        clock.advance(125L)
        engine.tick()
        assertFalse(engine.isCrossfading)
        assertEquals(pB, engine.activePlayer)
        assertEquals(1.0f, pB.volume, 0.001f)
    }

    @Test
    fun pauseAndResumeDuringCrossfade_pausesBothAndResumesInSync() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 4.0f },
        )

        // Start 4s crossfade at 96s
        pA.currentPositionMs = 96_000L
        engine.tick()
        assertTrue(engine.isCrossfading)

        // 1.0s elapsed (25% progress)
        clock.advance(1000L)
        engine.tick()

        // User pauses
        engine.onPause()
        assertTrue(engine.isPausedDuringCrossfade)
        assertFalse(pA.isPlaying)
        assertFalse(pB.isPlaying)

        // 5 seconds pass while paused
        clock.advance(5000L)
        engine.tick() // Ticks while paused should not advance progress
        assertTrue(engine.isCrossfading)

        // User resumes
        engine.onResume()
        assertFalse(engine.isPausedDuringCrossfade)
        assertTrue(pA.isPlaying)
        assertTrue(pB.isPlaying)

        // 1.0s after resume (total elapsed 2.0s -> 50% progress, unaffected by 5s pause)
        clock.advance(1000L)
        engine.tick()
        assertEquals(0.7071f, pA.volume, 0.01f)
        assertEquals(0.7071f, pB.volume, 0.01f)
    }

    @Test
    fun seekDuringCrossfade_cancelsFadeAndRestoresVolume() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
        )

        // Start crossfade
        pA.currentPositionMs = 95_000L
        engine.tick()
        assertTrue(engine.isCrossfading)

        // User seeks back to 30s
        engine.onSeek(30_000L)

        assertFalse(engine.isCrossfading)
        assertEquals(1.0f, pA.volume, 0.001f) // Full volume restored!
        assertEquals(30_000L, pA.lastSoughtPosition)
        assertEquals(1, pB.stopCount)
        assertEquals(1, pB.resetCount)
    }

    @Test
    fun nextItemChanged_reordersStandbyBeforeCrossfade() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")
        val song3 = sampleSong("s3", "Song 3")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
        )

        // Preload song2 at 85s
        pA.currentPositionMs = 85_000L
        engine.tick()
        assertEquals("s2", engine.preloadingSongId)

        // User reorders or adds to queue -> next item changes to song3!
        engine.onNextItemChanged(song3)

        // Old standby preloaded track discarded and re-prepared with song3
        assertEquals("s3", engine.preloadingSongId)
        assertEquals(song3, pB.currentSong)
        assertEquals(2, pB.prepareCount) // Prepared twice: once for s2, re-prepared for s3
    }

    @Test
    fun nextItemChanged_doesNotInterruptActiveCrossfade() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")
        val song3 = sampleSong("s3", "Song 3")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
        )

        // Start active crossfade into song2
        pA.currentPositionMs = 95_000L
        engine.tick()
        assertTrue(engine.isCrossfading)

        // Queue change during active fade does not abort the audible transition
        engine.onNextItemChanged(song3)
        assertTrue(engine.isCrossfading)
        assertEquals(song2, pB.currentSong)
    }

    @Test
    fun repeatModeOne_noCrossfadeToNextSong() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
            getRepeatMode = { RepeatMode.ONE },
        )

        // At 85s and 95s: does not preload and does not crossfade
        pA.currentPositionMs = 85_000L
        engine.tick()
        assertFalse(engine.isPreloaded)

        pA.currentPositionMs = 95_000L
        engine.tick()
        assertFalse(engine.isCrossfading)
    }

    @Test
    fun lastTrackInQueue_noCrossfade() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { null }, // Queue empty / last track
            getCrossfadeSeconds = { 5.0f },
        )

        pA.currentPositionMs = 95_000L
        engine.tick()
        assertFalse(engine.isCrossfading)
    }

    @Test
    fun shortTrack_shrinksFadeToFit() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Short Song")
        val song2 = sampleSong("s2", "Song 2")

        // Track duration 6 seconds, fade setting 5s
        // 2 * 5s = 10s > 6s, so fade is shrunk to 6s / 2 = 3s
        pA.currentSong = song1
        pA.durationMs = 6000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
        )

        // At 2.5s (3.5s remaining): not in shrunk fade window yet
        pA.currentPositionMs = 2500L
        engine.tick()
        assertFalse(engine.isCrossfading)

        // At 3.0s (3.0s remaining): reaches shrunk fade window!
        pA.currentPositionMs = 3000L
        engine.tick()
        assertTrue(engine.isCrossfading)
        assertEquals(3000L, engine.crossfadeDurationMs)
    }

    @Test
    fun gaplessMode_instantTransitionAtTrackEnd() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        var transitionedSong: Song? = null
        var gapMeasured: Long = -1L

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 0.0f }, // 0 = Gapless
            onTrackTransition = { transitionedSong = it },
            onGapMeasured = { gapMeasured = it },
        )

        // Preload happens at T-10s
        pA.currentPositionMs = 90_000L
        engine.tick()
        assertTrue(engine.isPreloaded)

        // Track reaches end (within 40ms)
        pA.currentPositionMs = 99_980L
        engine.tick()

        // Instant gapless swap
        assertEquals(song2, transitionedSong)
        assertEquals(0L, gapMeasured)
        assertEquals(pB, engine.activePlayer)
        assertEquals(1.0f, pB.volume, 0.001f)
    }

    @Test
    fun standbyStreamError_handlesWithoutStallingActivePlayer() {
        val clock = FakeClock(10_000L)
        val pA = FakeCrossfadePlayer("pA")
        val pB = FakeCrossfadePlayer("pB")
        val song1 = sampleSong("s1", "Song 1")
        val song2 = sampleSong("s2", "Song 2")
        val fallbackSong = sampleSong("s3", "Song 3")

        pA.currentSong = song1
        pA.durationMs = 100_000L
        pA.isPlaying = true

        val engine = CrossfadeEngine(
            clock = clock,
            playerA = pA,
            playerB = pB,
            getNextSong = { song2 },
            getCrossfadeSeconds = { 5.0f },
        )

        // Preload song2
        pA.currentPositionMs = 85_000L
        engine.tick()
        assertTrue(engine.isPreloaded)

        // Standby player encounters stream error
        engine.onStandbyError(fallbackNextSong = fallbackSong)

        // Active player is undisturbed!
        assertTrue(pA.isPlaying)
        assertEquals(1.0f, pA.volume, 0.001f)

        // Standby re-prepared with fallback song
        assertEquals("s3", engine.preloadingSongId)
        assertEquals(fallbackSong, pB.currentSong)
    }
}
