package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.DismissedTrackRegistry
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.QueuePlayerSync
import com.velvet.app.playback.ReconcileOutcome
import com.velvet.app.playback.ShuffleMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Round 3 B1 tests:
 * - Reproduces multi-step idle desync (before fix: old single-step syncBasicState leaves stale song & skips on prev+next).
 * - Verifies QueueManager.reconcileTo + QueuePlayerSync across 1/2/5 steps ahead, userQueue, history, unknown item, idempotence, IPC guard, F1 removed-set preservation, and DiagnosticsLogger + QueueHealthMonitor mismatch reporting.
 */
class B1IdleDesyncAndReconcileTest {

    private fun song(index: Int): Song = Song(
        id = "vid_$index",
        title = "Song Title $index",
        artist = "Artist $index",
        album = "Album $index",
        durationSec = 210L + index,
        thumb = "https://example.com/art_$index.jpg",
    )

    @Before
    fun setUp() {
        QueueHealthMonitor.clearForTest()
        DismissedTrackRegistry.clear()
    }

    @After
    fun tearDown() {
        QueueHealthMonitor.clearForTest()
        DismissedTrackRegistry.clear()
    }

    @Test
    fun b1_0_reproduction_oldSingleStepSyncFailsOn2StepIdleAdvance_whereasReconcileFixesPrevNextAndLogsMismatch() {
        val tracks = (0..5).map(::song)
        val legacyQm = QueueManager(syncWithDismissedRegistry = false).apply {
            start(tracks, startIndex = 0, shuffled = false)
        }
        // Fake ExoPlayer played vid_0 -> vid_1 -> vid_2 while UI listener was detached during screen lock
        val exoPlaying = tracks[2]

        // Old syncBasicState predicate only checked index 0 of userQueue / upNext:
        val oldSingleStepWouldSync =
            legacyQm.userQueue.firstOrNull()?.song?.id == exoPlaying.id ||
                legacyQm.upNext.firstOrNull()?.song?.id == exoPlaying.id
        assertEquals(false, oldSingleStepWouldSync)
        // Stuck showing vid_0 cover/title instead of ExoPlayer's vid_2!
        assertNotEquals(exoPlaying.id, legacyQm.current?.id)
        assertNotEquals(exoPlaying.thumb, legacyQm.current?.thumb)

        // New fixed path: reconcile via QueuePlayerSync
        val fixedQm = QueueManager(syncWithDismissedRegistry = false).apply {
            start(tracks, startIndex = 0, shuffled = false)
        }
        val beforeLogs = DiagnosticsLogger.entries.value.size
        val res = QueuePlayerSync.reconcileIfChanged(
            queueManager = fixedQm,
            playerSong = exoPlaying,
            source = "lifecycle_foreground",
            force = true,
        )
        assertNotNull(res)
        assertEquals(ReconcileOutcome.ADVANCED_UP_NEXT, res!!.outcome)
        assertEquals(exoPlaying.id, fixedQm.current?.id)
        assertEquals(exoPlaying.thumb, fixedQm.current?.thumb)
        assertEquals(listOf("vid_0", "vid_1"), fixedQm.history.map { it.id })
        assertEquals(listOf("vid_3", "vid_4", "vid_5"), fixedQm.upNext.map { it.id })

        // Verify DiagnosticsLogger + QueueHealthMonitor recorded the state mismatch
        val health = QueueHealthMonitor.state.value
        assertEquals(1, health.stateMismatchCount)
        assertTrue(health.lastStateMismatch!!.contains("queueCurrent=vid_0"))
        assertTrue(health.lastStateMismatch!!.contains("playerItem=vid_2"))
        assertTrue(health.lastStateMismatch!!.contains("passedOver=1"))
        val plain = QueueHealthMonitor.formatPlainText(health)
        assertTrue(plain.contains("--- STATE MISMATCHES ---"))
        assertTrue(plain.contains("playerItem=vid_2"))

        val addedLogs = DiagnosticsLogger.entries.value.drop(beforeLogs)
        assertTrue(
            "DiagnosticsLogger should contain StateMismatch entry",
            addedLogs.any { it.tag == "StateMismatch" && it.message.contains("playerItem=vid_2") },
        )
    }

    @Test
    fun reconcileTo_exoAheadBy1_2_and_5_steps_preservesChronologicalHistoryWithoutDuplicates() {
        val tracks = (0..7).map(::song)

        // Ahead by 1
        val qm1 = QueueManager().apply { start(tracks, startIndex = 0) }
        val r1 = qm1.reconcileTo("vid_1", tracks[1])
        assertEquals(ReconcileOutcome.ADVANCED_UP_NEXT, r1.outcome)
        assertEquals(0, r1.passedOverItems.size)
        assertEquals("vid_1", qm1.current?.id)
        assertEquals(listOf("vid_0"), qm1.history.map { it.id })
        assertEquals((2..7).map { "vid_$it" }, qm1.upNext.map { it.id })

        // Ahead by 2
        val qm2 = QueueManager().apply { start(tracks, startIndex = 0) }
        val r2 = qm2.reconcileTo("vid_2", tracks[2])
        assertEquals(ReconcileOutcome.ADVANCED_UP_NEXT, r2.outcome)
        assertEquals(1, r2.passedOverItems.size)
        assertEquals("vid_2", qm2.current?.id)
        assertEquals(listOf("vid_0", "vid_1"), qm2.history.map { it.id })
        assertEquals((3..7).map { "vid_$it" }, qm2.upNext.map { it.id })

        // Ahead by 5
        val qm5 = QueueManager().apply { start(tracks, startIndex = 0) }
        val r5 = qm5.reconcileTo("vid_5", tracks[5])
        assertEquals(ReconcileOutcome.ADVANCED_UP_NEXT, r5.outcome)
        assertEquals(4, r5.passedOverItems.size)
        assertEquals("vid_5", qm5.current?.id)
        assertEquals((0..4).map { "vid_$it" }, qm5.history.map { it.id })
        assertEquals(listOf("vid_6", "vid_7"), qm5.upNext.map { it.id })
        val allIds = qm5.history.map { it.id } + listOfNotNull(qm5.current?.id) + qm5.upNext.map { it.id }
        assertEquals(8, allIds.distinct().size)
    }

    @Test
    fun reconcileTo_userQueue_history_unknown_and_idempotence() {
        val tracks = (0..5).map(::song)
        val qm = QueueManager().apply {
            start(tracks, startIndex = 0)
            addToQueue(song(10))
            addToQueue(song(11))
        }
        // Jump into second userQueue item
        val uRes = qm.reconcileTo("vid_11", song(11))
        assertEquals(ReconcileOutcome.ADVANCED_USER_QUEUE, uRes.outcome)
        assertEquals("vid_11", qm.current?.id)
        assertEquals(listOf("vid_0", "vid_10"), qm.history.map { it.id })
        assertTrue(qm.userQueue.isEmpty())
        assertEquals((1..5).map { "vid_$it" }, qm.upNext.map { it.id })

        // Repeat call is idempotent NO_OP and does not log extra mismatch
        val prevCount = QueueHealthMonitor.state.value.stateMismatchCount
        val noop = QueuePlayerSync.reconcileIfChanged(
            queueManager = qm,
            playerSong = song(11),
            source = "sync_basic_state",
            force = true,
        )!!
        assertEquals(ReconcileOutcome.NO_OP, noop.outcome)
        assertEquals(prevCount, QueueHealthMonitor.state.value.stateMismatchCount)

        // Back in history (MOVED_FROM_HISTORY)
        val hRes = qm.reconcileTo("vid_0", tracks[0])
        assertEquals(ReconcileOutcome.MOVED_FROM_HISTORY, hRes.outcome)
        assertEquals("vid_0", qm.current?.id)
        assertTrue(qm.history.isEmpty())
        assertEquals(listOf("vid_10", "vid_11", "vid_1", "vid_2", "vid_3", "vid_4", "vid_5"), qm.upNext.map { it.id })

        // Unknown external track (ADOPTED_UNKNOWN) preserves upcoming queue
        val unknown = song(99)
        val unkRes = qm.reconcileTo(unknown.id, unknown)
        assertEquals(ReconcileOutcome.ADOPTED_UNKNOWN, unkRes.outcome)
        assertEquals("vid_99", qm.current?.id)
        assertEquals(listOf("vid_0"), qm.history.map { it.id })
        assertEquals(listOf("vid_10", "vid_11", "vid_1", "vid_2", "vid_3", "vid_4", "vid_5"), qm.upNext.map { it.id })
    }

    @Test
    fun reconcileThenPreviousThenNext_landsOnExactExpectedSongsWithoutSkipping() {
        val tracks = (0..5).map(::song)
        val qm = QueueManager().apply { start(tracks, startIndex = 0, shuffled = false) }

        // ExoPlayer advanced to vid_3 behind UI's back
        QueuePlayerSync.reconcileIfChanged(qm, tracks[3], source = "before_previous", force = true)
        assertEquals("vid_3", qm.current?.id)

        // User taps Previous (pos < 3s) -> lands on vid_2, with vid_3 pushed to front of upNext
        val prevSong = qm.previous(positionMs = 500L)
        assertEquals("vid_2", prevSong?.id)
        assertEquals("vid_2", qm.current?.id)
        assertEquals(listOf("vid_3", "vid_4", "vid_5"), qm.upNext.map { it.id })

        // User taps Next -> returns to vid_3, NOT vid_4!
        val next1 = qm.advance(manual = true)
        assertEquals("vid_3", next1?.id)
        // Next again -> vid_4
        val next2 = qm.advance(manual = true)
        assertEquals("vid_4", next2?.id)
    }

    @Test
    fun pendingIpcGuardIgnoresStaleNonForcedSyncUntilWindowExpires() {
        val tracks = (0..3).map(::song)
        val qm = QueueManager().apply { start(tracks, startIndex = 0) }
        qm.advance(manual = true) // now at vid_1, expecting ExoPlayer to reach vid_1

        // Stale event from ExoPlayer still reporting vid_0 inside pending window
        val ignored = QueuePlayerSync.reconcileIfChanged(
            queueManager = qm,
            playerSong = tracks[0],
            source = "sync_basic_state",
            pendingExpectedSongId = "vid_1",
            pendingExpectedUntilMs = 1_900L,
            nowMs = 1_200L,
            force = false,
        )
        assertNull(ignored)
        assertEquals("vid_1", qm.current?.id)

        // Explicit force=true bypasses window
        val forced = QueuePlayerSync.reconcileIfChanged(
            queueManager = qm,
            playerSong = tracks[2],
            source = "before_next",
            pendingExpectedSongId = "vid_1",
            pendingExpectedUntilMs = 1_900L,
            nowMs = 1_200L,
            force = true,
        )
        assertEquals(ReconcileOutcome.ADVANCED_UP_NEXT, forced?.outcome)
        assertEquals("vid_2", qm.current?.id)
    }

    @Test
    fun reconcileAndSubsequentShufflePreserveRound2F1RemovedSet() {
        val tracks = (0..6).map(::song)
        val qm = QueueManager(syncWithDismissedRegistry = false).apply {
            start(tracks, startIndex = 0, shuffled = false)
        }
        val removeQid = qm.upNext.first { it.song.id == "vid_3" }.qid
        qm.removeItem(removeQid)
        assertTrue("vid_3" in qm.state.removedSongIds)

        // Reconcile ahead to vid_4
        QueuePlayerSync.reconcileIfChanged(qm, tracks[4], source = "lifecycle_foreground", force = true)
        assertEquals("vid_4", qm.current?.id)
        assertTrue("vid_3" in qm.state.removedSongIds)

        // Toggle shuffle ON then OFF -> vid_3 must stay excluded
        qm.setShuffleMode(ShuffleMode.STANDARD, allowSmartShuffle = false, rng = Random(7L))
        assertTrue(qm.upNext.none { it.song.id == "vid_3" })
        qm.setShuffleMode(ShuffleMode.OFF, allowSmartShuffle = false)
        assertTrue(qm.upNext.none { it.song.id == "vid_3" })
    }

    @Test
    fun normal1StepAutoTransitionDoesNotSpamMismatchCounter() {
        val tracks = (0..3).map(::song)
        val qm = QueueManager().apply { start(tracks, startIndex = 0) }
        val res = QueuePlayerSync.reconcileIfChanged(
            queueManager = qm,
            playerSong = tracks[1],
            source = "transition_1",
            force = true,
            recordExpectedSingleStepMismatch = false,
        )
        assertEquals(ReconcileOutcome.ADVANCED_UP_NEXT, res?.outcome)
        assertEquals("vid_1", qm.current?.id)
        assertEquals(0, QueueHealthMonitor.state.value.stateMismatchCount)
    }
}
