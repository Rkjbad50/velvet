package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.playback.DismissedTrackRegistry
import com.velvet.app.playback.PersistedPlaybackState
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.RepeatMode
import com.velvet.app.playback.ShuffleMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

class F1RemovedTrackShufflePersistenceTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Arijit Singh",
        durationSec: Int = 240,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = durationSec,
    )

    @Before
    fun setUp() {
        DismissedTrackRegistry.clear()
    }

    @After
    fun tearDown() {
        DismissedTrackRegistry.clear()
    }

    @Test
    fun removePlaylistSongs_thenToggleShuffleOnOffRepeatedly_neverReturns() {
        val tracks = (0..7).map { i -> song("pl_$i", "Track Number $i", "Artist $i") }
        val qm = QueueManager()
        qm.start(tracks, startIndex = 0, shuffled = false, rng = Random(42L))

        val toRemoveIds = setOf("pl_2", "pl_5", "pl_6")
        val targetQids = qm.upNext.filter { it.song.id in toRemoveIds }.map { it.qid }
        assertEquals(3, targetQids.size)
        targetQids.forEach { assertTrue(qm.removeItem(it)) }

        assertTrue(qm.sourceList.none { it.id in toRemoveIds })
        assertTrue(qm.upNext.none { it.song.id in toRemoveIds })
        assertTrue(qm.state.removedSongIds.containsAll(toRemoveIds))

        repeat(6) { cycle ->
            val rng = Random(100L + cycle)
            qm.enableShuffle(ShuffleMode.STANDARD, allowSmartShuffle = false, rng = rng)
            assertTrue("Shuffle ON cycle $cycle brought back removed track", qm.upNext.none { it.song.id in toRemoveIds })
            assertTrue("sourceList contaminated at cycle $cycle", qm.sourceList.none { it.id in toRemoveIds })

            qm.disableShuffle()
            assertTrue("Unshuffle cycle $cycle brought back removed track", qm.upNext.none { it.song.id in toRemoveIds })

            qm.setShuffleMode(ShuffleMode.SMART, allowSmartShuffle = true, rng = rng)
            assertTrue("Smart shuffle cycle $cycle brought back removed track", qm.upNext.none { it.song.id in toRemoveIds })

            qm.cycleShuffleMode(allowSmartShuffle = true, rng = rng)
            assertTrue("Cycle shuffle brought back removed track", qm.upNext.none { it.song.id in toRemoveIds })
        }
    }

    @Test
    fun removeAutoplaySong_thenShuffleAndReAppend_neverReturns() {
        val seed = song("seed_1", "Tumko Dekha Toh", "Jagjit Singh")
        val ap1 = song("ap_1", "Chitthi Na Koi Sandesh", "Jagjit Singh")
        val ap2 = song("ap_2", "Hoshwalon Ko Khabar Kya", "Jagjit Singh")
        val ap3 = song("ap_3", "Kagaz Ki Kashti", "Jagjit Singh")

        val qm = QueueManager()
        qm.start(listOf(seed), startIndex = 0, shuffled = false)
        qm.appendAutoplay(listOf(ap1, ap2, ap3))

        val ap2Qid = qm.upNext.first { it.song.id == "ap_2" }.qid
        assertTrue(qm.removeItem(ap2Qid))
        assertTrue(qm.upNext.none { it.song.id == "ap_2" })
        assertTrue(qm.sourceList.none { it.id == "ap_2" })

        repeat(4) { i ->
            qm.enableShuffle(ShuffleMode.STANDARD, rng = Random(200L + i))
            assertTrue(qm.upNext.none { it.song.id == "ap_2" })
            qm.disableShuffle()
            assertTrue(qm.upNext.none { it.song.id == "ap_2" })
        }

        // Attempting to re-append the removed autoplay track or inject it via Smart Shuffle must ignore it
        qm.appendAutoplay(listOf(ap2, song("ap_4", "Jhuki Jhuki Si Nazar", "Jagjit Singh")))
        assertTrue(qm.upNext.none { it.song.id == "ap_2" })
        assertTrue(qm.sourceList.none { it.id == "ap_2" })

        qm.enableShuffle(ShuffleMode.SMART, allowSmartShuffle = true, rng = Random(5L))
        qm.injectSmartRecommendations(listOf(ap2, song("smart_1", "Woh Kagaz Ki Kashti Live Extra", "Jagjit Singh")), rng = Random(6L))
        assertTrue(qm.upNext.none { it.song.id == "ap_2" })
    }

    @Test
    fun removeUserQueuedSong_thenShuffleAndRepeatAll_neverReturns() {
        val base = (0..3).map { song("b_$it", "Base Song $it", "Artist $it") }
        val queuedSong = song("uq_1", "Queued Song", "Special Artist")

        val qm = QueueManager()
        qm.start(base, startIndex = 0, shuffled = false)
        assertTrue(qm.enqueue(queuedSong))
        val qid = qm.userQueue.first { it.song.id == "uq_1" }.qid
        assertTrue(qm.removeItem(qid))
        assertTrue(qm.userQueue.isEmpty())

        qm.enableShuffle(ShuffleMode.STANDARD, rng = Random(10L))
        qm.disableShuffle()
        assertTrue(qm.upNext.none { it.song.id == "uq_1" })
        assertTrue(qm.userQueue.none { it.song.id == "uq_1" })

        qm.setRepeatMode(RepeatMode.ALL)
        repeat(6) {
            qm.advance(manual = true)
            assertFalse("Repeat.ALL brought back removed user-queued song", qm.current?.id == "uq_1")
        }
    }

    @Test
    fun duplicateRecordingAndCompositionOfRemovedSong_staysExcludedEverywhere() {
        val base = (0..2).map { song("orig_$it", "Unique Song $it", "Artist $it") }
        val removedUpload = song("vid_removed", "Tum Hi Ho (Official Audio)", "Arijit Singh - Topic", 262)
        val duplicateAltUpload = song("vid_alt_upload", "Tum Hi Ho | Aashiqui 2", "Arijit Singh", 264)
        val unrelated = song("vid_other", "Channa Mereya", "Arijit Singh", 289)

        val qm = QueueManager()
        qm.start(base + removedUpload + unrelated, startIndex = 0, shuffled = false)
        val removedQid = qm.upNext.first { it.song.id == "vid_removed" }.qid
        assertTrue(qm.removeItem(removedQid))

        assertTrue(qm.state.isRemoved(removedUpload))
        assertTrue(qm.state.isRemoved(duplicateAltUpload))

        // Try introducing duplicate upload through every path
        qm.appendAutoplay(listOf(duplicateAltUpload, song("vid_fresh", "Agar Tum Saath Ho", "Arijit Singh", 341)))
        qm.enableShuffle(ShuffleMode.SMART, allowSmartShuffle = true, rng = Random(3L))
        qm.injectSmartRecommendations(listOf(duplicateAltUpload), rng = Random(4L))
        qm.disableShuffle()

        assertTrue(qm.sourceList.none { it.id == "vid_removed" || it.id == "vid_alt_upload" })
        assertTrue(qm.upNext.none { it.song.id == "vid_removed" || it.song.id == "vid_alt_upload" })
        assertTrue(qm.upNext.any { it.song.id == "vid_fresh" })
    }

    @Test
    fun checkpointRestore_persistsRemovedSetAcrossRestartAndShuffles() {
        val tracks = (0..5).map { song("ck_$it", "Checkpoint Song $it", "Artist $it") }
        val dupOfCk3 = song("ck_3_alt", "Checkpoint Song 3 (Official Audio)", "Artist 3", 242)

        val qmBefore = QueueManager()
        qmBefore.start(tracks, startIndex = 0, shuffled = true, rng = Random(77L))
        val targetQid = qmBefore.upNext.first { it.song.id == "ck_3" }.qid
        assertTrue(qmBefore.removeItem(targetQid))

        val snapshot = PersistedPlaybackState(
            song = qmBefore.current!!,
            positionMs = 15_000L,
            durationMs = 240_000L,
            upNext = qmBefore.upNext.map { it.song },
            userQueue = qmBefore.userQueue.map { it.song },
            history = qmBefore.history,
            // Even if a stale sourceList still contained the removed track + duplicate recording, restore filters them out
            sourceList = tracks + dupOfCk3,
            shuffleMode = qmBefore.shuffleMode,
            repeatMode = RepeatMode.ALL,
            removedSongIds = qmBefore.state.removedSongIds,
            removedGroupIds = qmBefore.state.removedGroupIds,
            removedRecordingIds = qmBefore.state.removedRecordingIds,
        )

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(snapshot)
        val decoded = json.decodeFromString<PersistedPlaybackState>(encoded)

        val qmAfter = QueueManager()
        qmAfter.restore(decoded, allowSmartShuffle = true)
        assertTrue(qmAfter.sourceList.none { it.id == "ck_3" || it.id == "ck_3_alt" })
        assertTrue(qmAfter.upNext.none { it.id == "ck_3" || it.id == "ck_3_alt" })

        repeat(4) { idx ->
            qmAfter.enableShuffle(ShuffleMode.STANDARD, rng = Random(90L + idx))
            assertTrue(qmAfter.upNext.none { it.song.id == "ck_3" || it.song.id == "ck_3_alt" })
            qmAfter.disableShuffle()
            assertTrue(qmAfter.upNext.none { it.song.id == "ck_3" || it.song.id == "ck_3_alt" })
        }

        // Legacy checkpoint JSON without removed* fields must still decode cleanly
        val legacyJson = """{"song":{"id":"ck_0","title":"Checkpoint Song 0","artist":"Artist 0","durationSec":240}}"""
        val legacyDecoded = json.decodeFromString<PersistedPlaybackState>(legacyJson)
        assertTrue(legacyDecoded.removedSongIds.isEmpty())
    }

    @Test
    fun newContextAndQueueClear_resetRemovedSet_andDismissedRegistrySyncs() {
        val tracks = (0..4).map { song("ctx_$it", "Context Track $it", "Artist $it") }
        val qm = QueueManager(syncWithDismissedRegistry = true)
        qm.start(tracks, startIndex = 0, shuffled = false)

        // Dismissal via DismissedTrackRegistry updates active QueueState as well
        DismissedTrackRegistry.recordDismissal(tracks[2])
        assertTrue(qm.state.isRemoved(tracks[2]))
        assertTrue(qm.sourceList.none { it.id == "ctx_2" })
        assertTrue(qm.upNext.none { it.song.id == "ctx_2" })

        // Starting a new context clears removed state
        qm.start(tracks, startIndex = 0, shuffled = false)
        assertTrue(qm.state.removedSongIds.isEmpty())
        assertTrue(qm.state.removedGroupIds.isEmpty())
        assertTrue(qm.state.removedRecordingIds.isEmpty())
        assertFalse(DismissedTrackRegistry.isDismissed(tracks[2]))
        assertTrue(qm.upNext.any { it.song.id == "ctx_2" })

        // Remove again, then clear queue -> cleared
        val qid1 = qm.upNext.first { it.song.id == "ctx_1" }.qid
        assertTrue(qm.removeItem(qid1))
        assertTrue(DismissedTrackRegistry.isDismissed(tracks[1]))
        qm.clear()
        assertTrue(qm.state.removedSongIds.isEmpty())
        assertFalse(DismissedTrackRegistry.isDismissed(tracks[1]))

        // Remove again, clearUpcoming(clearRemoved = false) preserves set, clearRemoved = true clears set
        qm.start(tracks, startIndex = 0, shuffled = false)
        assertTrue(qm.removeItem(qm.upNext.first { it.song.id == "ctx_3" }.qid))
        qm.clearUpcoming(clearRemoved = false)
        assertTrue(qm.state.isRemoved(tracks[3]))
        qm.clearUpcoming(clearRemoved = true)
        assertFalse(qm.state.isRemoved(tracks[3]))
        assertFalse(DismissedTrackRegistry.isDismissed(tracks[3]))
    }
}
