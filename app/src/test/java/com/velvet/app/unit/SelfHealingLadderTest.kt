package com.velvet.app.unit

import com.velvet.app.playback.Clock
import com.velvet.app.playback.HealingDecision
import com.velvet.app.playback.HealingDiagnostics
import com.velvet.app.playback.PlaybackErrorCategory
import com.velvet.app.playback.PlaybackHealingLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelfHealingLadderTest {

    private class FakeClock(var timeMs: Long = 1000L) : Clock {
        override fun currentTimeMillis(): Long = timeMs
        fun advance(ms: Long) { timeMs += ms }
    }

    private class FakeDiagnostics : HealingDiagnostics {
        val logs = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun log(tag: String, message: String) {
            logs.add("$tag: $message")
        }

        override fun recordError(error: Throwable, videoId: String?, host: String?, customErrorCodeName: String?) {
            errors.add("$customErrorCodeName (videoId=$videoId, host=$host)")
        }
    }

    private lateinit var clock: FakeClock
    private lateinit var diagnostics: FakeDiagnostics
    private lateinit var ladder: PlaybackHealingLadder

    @Before
    fun setUp() {
        clock = FakeClock(1000L)
        diagnostics = FakeDiagnostics()
        ladder = PlaybackHealingLadder(
            selfHealingEnabled = true,
            clock = clock,
            baseBackoffMs = 500L,
            diagnostics = diagnostics
        )
    }

    @Test
    fun testLadderOrderForTransientIO() {
        val videoId = "vidTransient"

        // Step 1 - Attempt 1: Transient IO retry 1 with 500ms backoff
        val d1 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO, host = "googlevideo.com")
        assertTrue(d1 is HealingDecision.RetryTransient)
        assertEquals(1, (d1 as HealingDecision.RetryTransient).attempt)
        assertEquals(500L, d1.backoffMs)

        // Step 1 - Attempt 2: Transient IO retry 2 with 1000ms exponential backoff
        val d2 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO, host = "googlevideo.com")
        assertTrue(d2 is HealingDecision.RetryTransient)
        assertEquals(2, (d2 as HealingDecision.RetryTransient).attempt)
        assertEquals(1000L, d2.backoffMs)

        // Step 2: Cache invalidation & re-resolve
        val d3 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO, host = "googlevideo.com")
        assertTrue(d3 is HealingDecision.InvalidateAndReResolve)
        assertEquals(videoId, (d3 as HealingDecision.InvalidateAndReResolve).videoId)

        // Step 3a: Try next stream candidate
        val d4 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO, host = "googlevideo.com")
        assertTrue(d4 is HealingDecision.TryNextCandidate)
        assertEquals(videoId, (d4 as HealingDecision.TryNextCandidate).videoId)

        // Step 3b: Try next client (fast <-> full)
        val d5 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO, host = "googlevideo.com")
        assertTrue(d5 is HealingDecision.TryNextClient)
        assertEquals(videoId, (d5 as HealingDecision.TryNextClient).videoId)

        // Step 4: Skip track
        val d6 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO, host = "googlevideo.com")
        assertTrue(d6 is HealingDecision.SkipTrack)
        assertEquals(videoId, (d6 as HealingDecision.SkipTrack).failedVideoId)
    }

    @Test
    fun testLadderOrderForExpiredOrForbidden() {
        val videoId = "vidExpired"

        // Step 2: 403/410/expired jumps directly to cache invalidation & re-resolve
        val d1 = ladder.handleFailure(videoId, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN, host = "googlevideo.com")
        assertTrue(d1 is HealingDecision.InvalidateAndReResolve)
        assertEquals(videoId, (d1 as HealingDecision.InvalidateAndReResolve).videoId)

        // Step 3a: Next candidate
        val d2 = ladder.handleFailure(videoId, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN, host = "googlevideo.com")
        assertTrue(d2 is HealingDecision.TryNextCandidate)

        // Step 3b: Next client
        val d3 = ladder.handleFailure(videoId, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN, host = "googlevideo.com")
        assertTrue(d3 is HealingDecision.TryNextClient)

        // Step 4: Skip track
        val d4 = ladder.handleFailure(videoId, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN, host = "googlevideo.com")
        assertTrue(d4 is HealingDecision.SkipTrack)
    }

    @Test
    fun testEachStepRunsAtMostOncePerSong() {
        val videoId = "vidStepsOnce"
        val state = ladder.getSongState(videoId)

        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Retry 1
        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Retry 2
        assertEquals(2, state.transientRetries)

        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Invalidate cache
        assertTrue(state.cacheInvalidatedAndRetried)

        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Next candidate
        assertTrue(state.triedNextCandidate)

        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Next client
        assertTrue(state.triedNextClient)

        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Skip track
        assertTrue(state.skipped)
    }

    @Test
    fun testNoInfiniteLoop() {
        val videoId = "vidLoop"
        // Run until skipped
        repeat(6) {
            ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO)
        }
        assertTrue(ladder.getSongState(videoId).skipped)

        // Further failures on the same song must return NoAction and never loop
        val subsequent = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO)
        assertEquals(HealingDecision.NoAction, subsequent)
    }

    @Test
    fun testSameSongFailingThreeTimesDoesNotTriggerStrikeRule() {
        val videoId = "vidSameFails3Times"

        // Failure 1 (retry 1)
        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO)
        // Failure 2 (retry 2)
        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO)
        // Failure 3 (cache invalidate)
        val d3 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO)

        assertFalse(d3 is HealingDecision.CircuitBreakerTrip)
        assertEquals(0, ladder.consecutiveFailuresCount) // Not skipped yet

        // Even when skipped:
        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Next candidate
        ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Next client
        val d6 = ladder.handleFailure(videoId, PlaybackErrorCategory.TRANSIENT_IO) // Skip track

        assertTrue(d6 is HealingDecision.SkipTrack)
        // Distinct songs count is only 1!
        assertEquals(1, ladder.consecutiveFailuresCount)
    }

    @Test
    fun testThreeStrikeRuleWithThreeDifferentSongs() {
        // Song 1 fails completely and skips
        val song1 = "song1"
        repeat(6) { ladder.handleFailure(song1, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        assertEquals(1, ladder.consecutiveFailuresCount)

        // Song 2 fails completely and skips
        val song2 = "song2"
        repeat(6) { ladder.handleFailure(song2, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        assertEquals(2, ladder.consecutiveFailuresCount)

        // Song 3 fails: when reaching skip, it trips the circuit breaker!
        val song3 = "song3"
        ladder.handleFailure(song3, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) // Invalidate
        ladder.handleFailure(song3, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) // Next candidate
        ladder.handleFailure(song3, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) // Next client
        val tripDecision = ladder.handleFailure(song3, PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) // Strike 3!

        assertTrue(tripDecision is HealingDecision.CircuitBreakerTrip)
        val trip = tripDecision as HealingDecision.CircuitBreakerTrip
        assertEquals(song3, trip.failedVideoId)
        assertEquals(3, trip.consecutiveFailures)
        assertTrue(trip.showBanner)
        assertEquals(PlaybackHealingLadder.BANNER_TEXT, trip.bannerMessage)
    }

    @Test
    fun testPlaybackSuccessResetsConsecutiveFailures() {
        // Song 1 fails and skips (strike 1)
        repeat(6) { ladder.handleFailure("song1", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        assertEquals(1, ladder.consecutiveFailuresCount)

        // Song 2 plays successfully!
        ladder.onPlaybackSuccess("song2")
        assertEquals(0, ladder.consecutiveFailuresCount)

        // Song 3 fails and skips (strike 1, not strike 2)
        repeat(6) { ladder.handleFailure("song3", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        assertEquals(1, ladder.consecutiveFailuresCount)
    }

    @Test
    fun testDifferentErrorClassResetsConsecutiveFailures() {
        // Song 1 fails with TRANSIENT_IO
        repeat(6) { ladder.handleFailure("song1", PlaybackErrorCategory.TRANSIENT_IO) }
        assertEquals(1, ladder.consecutiveFailuresCount)

        // Song 2 fails with EXPIRED_OR_FORBIDDEN -> class mismatch resets streak to 1
        repeat(6) { ladder.handleFailure("song2", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        assertEquals(1, ladder.consecutiveFailuresCount)
    }

    @Test
    fun testBannerRateLimit() {
        clock.timeMs = 1000L

        // Trip 1
        repeat(6) { ladder.handleFailure("s1", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        repeat(6) { ladder.handleFailure("s2", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        repeat(3) { ladder.handleFailure("s3", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        val trip1 = ladder.handleFailure("s3", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) as HealingDecision.CircuitBreakerTrip
        assertTrue(trip1.showBanner)

        // Advance 5 minutes (300,000 ms, within 10 min rate limit)
        clock.advance(5 * 60_000L)
        ladder.reset()

        // Trip 2 within 10 minutes
        repeat(6) { ladder.handleFailure("s4", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        repeat(6) { ladder.handleFailure("s5", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        repeat(3) { ladder.handleFailure("s6", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        val trip2 = ladder.handleFailure("s6", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) as HealingDecision.CircuitBreakerTrip
        assertFalse("Banner must be rate-limited within 10 minutes", trip2.showBanner)

        // Advance 6 more minutes (total 11 minutes > 10 min rate limit)
        clock.advance(6 * 60_000L)
        ladder.reset()

        // Trip 3 after 11 minutes
        repeat(6) { ladder.handleFailure("s7", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        repeat(6) { ladder.handleFailure("s8", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        repeat(3) { ladder.handleFailure("s9", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) }
        val trip3 = ladder.handleFailure("s9", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN) as HealingDecision.CircuitBreakerTrip
        assertTrue("Banner must show after 10 minutes have elapsed", trip3.showBanner)
    }

    @Test
    fun testSelfHealingDisabledMeansNoLadder() {
        ladder.selfHealingEnabled = false

        val d1 = ladder.handleFailure("vid1", PlaybackErrorCategory.TRANSIENT_IO)
        assertEquals(HealingDecision.NoAction, d1)

        val d2 = ladder.handleFailure("vid2", PlaybackErrorCategory.EXPIRED_OR_FORBIDDEN)
        assertEquals(HealingDecision.NoAction, d2)
    }
}
