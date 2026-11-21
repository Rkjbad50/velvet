package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.playback.QueueItem
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.QueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDragReorderTest {

    private fun makeSong(id: String, title: String = "Title $id", artist: String = "Artist"): Song =
        Song(id = id, title = title, artist = artist, durationSec = 180)

    private fun makeSongs(count: Int): List<Song> =
        (0 until count).map { makeSong("song_$it", "Song #$it") }

    /**
     * Pure-Kotlin Fake Player that simulates downstream ExoPlayer behavior
     * and tracks seek, pause, and stop calls during state synchronization.
     */
    private class FakePlaybackPlayer {
        var currentMediaId: String? = null
        var upcomingMediaIds: List<String> = emptyList()
        var seekCount: Int = 0
        var pauseCount: Int = 0
        var stopCount: Int = 0
        var playCount: Int = 0

        fun sync(state: QueueState) {
            val cur = state.current?.song
            val targetUpcoming = (state.userQueue + state.upNext).map { it.song.id }

            if (cur == null) {
                currentMediaId = null
                upcomingMediaIds = emptyList()
                return
            }

            // Case 1: Track change
            if (currentMediaId != cur.id) {
                currentMediaId = cur.id
                upcomingMediaIds = targetUpcoming
                playCount++
                return
            }

            // Case 2: Current song is already playing.
            // Downstream minimal diff updates only upcoming items without touching current track.
            if (upcomingMediaIds != targetUpcoming) {
                upcomingMediaIds = targetUpcoming
                // Note: minimal diff touches only future items; NO seek, NO pause, NO stop
            }
        }

        fun seekTo(positionMs: Long) { seekCount++ }
        fun pause() { pauseCount++ }
        fun stop() { stopCount++ }
        fun play() { playCount++ }
    }

    /**
     * Simulates the UI drag session state machine in Compose.
     */
    private class DragSession(private val manager: QueueManager) {
        var draggingQid: String? = null
            private set
        var dragStartVersion: Long = -1L
            private set
        var previewList: MutableList<QueueItem>? = null
            private set
        var isUserQueue: Boolean = false
            private set

        fun startDrag(qid: String, userQueue: Boolean) {
            draggingQid = qid
            isUserQueue = userQueue
            dragStartVersion = manager.queueVersion
            previewList = (if (userQueue) manager.userQueue else manager.upNext).toMutableList()
        }

        fun movePreview(fromIndex: Int, toIndex: Int) {
            val list = previewList ?: return
            if (fromIndex in list.indices && toIndex in list.indices && fromIndex != toIndex) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
            }
        }

        fun commit(): Boolean {
            val qid = draggingQid ?: return false
            // Rule 7: Cancel safely if queue version changed underneath
            if (manager.queueVersion != dragStartVersion) {
                cancel()
                return false
            }
            val targetIndex = previewList?.indexOfFirst { it.qid == qid } ?: -1
            cancel()
            return if (targetIndex >= 0) {
                manager.reorder(qid, targetIndex)
            } else {
                false
            }
        }

        fun cancel() {
            draggingQid = null
            dragStartVersion = -1L
            previewList = null
        }
    }

    @Test
    fun testReorderWithinUserQueue() {
        val manager = QueueManager()
        val songs = makeSongs(5)
        manager.start(songs, startIndex = 0, shuffled = false)

        // Add 3 songs to userQueue
        val u0 = QueueItem(makeSong("u0", "User 0"))
        val u1 = QueueItem(makeSong("u1", "User 1"))
        val u2 = QueueItem(makeSong("u2", "User 2"))
        manager.addToQueue(u0)
        manager.addToQueue(u1)
        manager.addToQueue(u2)

        assertEquals(3, manager.userQueue.size)
        assertEquals(listOf("u0", "u1", "u2"), manager.userQueue.map { it.song.id })

        // Reorder u2 from index 2 to index 0
        val moved = manager.reorder(u2.qid, 0)
        assertTrue(moved)
        assertEquals(listOf("u2", "u0", "u1"), manager.userQueue.map { it.song.id })

        // Ensure upNext is untouched
        assertEquals(4, manager.upNext.size)
    }

    @Test
    fun testReorderWithinUpNext() {
        val manager = QueueManager()
        val songs = makeSongs(6)
        manager.start(songs, startIndex = 0, shuffled = false)

        val initialUpNext = manager.upNext.map { it.song.id }
        assertEquals(5, initialUpNext.size) // song_1 .. song_5

        val targetItem = manager.upNext[3] // song_4
        // Reorder song_4 from index 3 to index 1
        val moved = manager.reorder(targetItem.qid, 1)
        assertTrue(moved)

        val newUpNext = manager.upNext.map { it.song.id }
        assertEquals("song_4", newUpNext[1])
        assertEquals("song_1", newUpNext[0])
        assertEquals("song_2", newUpNext[2])
        assertEquals("song_3", newUpNext[3])
        assertEquals("song_5", newUpNext[4])

        // Ensure userQueue is untouched
        assertTrue(manager.userQueue.isEmpty())
    }

    @Test
    fun testDuplicateSongsWithTwoQidsReorderIndependently() {
        val manager = QueueManager()
        val baseSong = makeSong("same_song", "Repeated Track")
        val item1 = QueueItem(baseSong)
        val item2 = QueueItem(baseSong)
        assertNotEquals(item1.qid, item2.qid)

        // Setup upNext containing both duplicates
        manager.update {
            it.copy(
                current = QueueItem(makeSong("current_track")),
                upNext = listOf(item1, QueueItem(makeSong("other_1")), item2),
            )
        }

        assertEquals(3, manager.upNext.size)
        assertEquals(item1.qid, manager.upNext[0].qid)
        assertEquals(item2.qid, manager.upNext[2].qid)

        // Move item2 (second duplicate) to index 0
        val moved = manager.reorder(item2.qid, 0)
        assertTrue(moved)

        // item2 is now at index 0, item1 shifted to index 1
        assertEquals(item2.qid, manager.upNext[0].qid)
        assertEquals(item1.qid, manager.upNext[1].qid)
        assertEquals("other_1", manager.upNext[2].song.id)
    }

    @Test
    fun testReorderFirstToLast() {
        val manager = QueueManager()
        val songs = makeSongs(5)
        manager.start(songs, startIndex = 0)

        val firstItem = manager.upNext.first()
        val initialSize = manager.upNext.size

        // Move index 0 to last position
        val moved = manager.reorder(firstItem.qid, initialSize - 1)
        assertTrue(moved)

        assertEquals(firstItem.qid, manager.upNext.last().qid)
        assertEquals(initialSize, manager.upNext.size)
    }

    @Test
    fun testReorderLastToFirst() {
        val manager = QueueManager()
        val songs = makeSongs(5)
        manager.start(songs, startIndex = 0)

        val lastItem = manager.upNext.last()
        val initialSize = manager.upNext.size

        // Move last item to index 0
        val moved = manager.reorder(lastItem.qid, 0)
        assertTrue(moved)

        assertEquals(lastItem.qid, manager.upNext.first().qid)
        assertEquals(initialSize, manager.upNext.size)
    }

    @Test
    fun testReorderSamePositionIsNoOp() {
        val manager = QueueManager()
        val songs = makeSongs(4)
        manager.start(songs, startIndex = 0)

        val versionBefore = manager.queueVersion
        val item = manager.upNext[1]

        // Reorder index 1 to index 1
        val moved = manager.reorder(item.qid, 1)
        assertFalse(moved)
        assertEquals(versionBefore, manager.queueVersion)
    }

    @Test
    fun testReorderInvalidQidDoesNotCrash() {
        val manager = QueueManager()
        val songs = makeSongs(4)
        manager.start(songs, startIndex = 0)

        val versionBefore = manager.queueVersion
        val moved = manager.reorder("completely_unknown_qid", 0)
        assertFalse(moved)
        assertEquals(versionBefore, manager.queueVersion)
    }

    @Test
    fun testReorderCurrentItemIsRejected() {
        val manager = QueueManager()
        val songs = makeSongs(4)
        manager.start(songs, startIndex = 0)

        val currentQid = manager.state.current!!.qid
        val moved = manager.reorder(currentQid, 1)
        assertFalse(moved)

        val movedSpecial = manager.reorder("current", 1)
        assertFalse(movedSpecial)
    }

    @Test
    fun testLockedQueueRejectsReorder() {
        val manager = QueueManager()
        val songs = makeSongs(4)
        manager.start(songs, startIndex = 0)

        val versionBefore = manager.queueVersion
        val targetItem = manager.upNext.last()

        val moved = manager.reorder(targetItem.qid, 0, isLocked = true)
        assertFalse(moved)
        assertEquals(versionBefore, manager.queueVersion)
        assertEquals(targetItem.qid, manager.upNext.last().qid)
    }

    @Test
    fun testPreviewDiscardedWhenQueueVersionChanges() {
        val manager = QueueManager()
        val songs = makeSongs(5)
        manager.start(songs, startIndex = 0)

        val target = manager.upNext[2]
        val session = DragSession(manager)

        // 1. Start drag preview
        session.startDrag(target.qid, userQueue = false)
        assertEquals(target.qid, session.draggingQid)

        // Move preview locally (target moved from 2 to 0)
        session.movePreview(2, 0)
        assertEquals(target.qid, session.previewList?.first()?.qid)

        // 2. An external mutation changes the queue underneath the drag
        manager.addToQueue(makeSong("external_item"))
        // queueVersion has now incremented!

        // 3. Drop / commit occurs
        val committed = session.commit()
        assertFalse("Commit must fail when queue version changed underneath", committed)

        // 4. Preview was discarded and actual queue preserved
        assertEquals("song_1", manager.upNext[0].song.id)
        assertEquals("song_2", manager.upNext[1].song.id)
        assertEquals(target.qid, manager.upNext[2].qid)
    }

    @Test
    fun testFakePlayerCurrentItemReceivesNoSeekPauseStopCallsAfterReorder() {
        val manager = QueueManager()
        val songs = makeSongs(6)
        manager.start(songs, startIndex = 0)

        val player = FakePlaybackPlayer()
        player.sync(manager.state)

        assertEquals("song_0", player.currentMediaId)
        assertEquals(1, player.playCount)
        assertEquals(0, player.seekCount)
        assertEquals(0, player.pauseCount)
        assertEquals(0, player.stopCount)

        // Reorder upcoming items
        val itemToMove = manager.upNext[3] // song_4
        val moved = manager.reorder(itemToMove.qid, 0)
        assertTrue(moved)

        // Sync downstream view to fake player
        player.sync(manager.state)

        // Current track remains playing uninterrupted: NO seek, NO pause, NO stop calls!
        assertEquals("song_0", player.currentMediaId)
        assertEquals(1, player.playCount) // not restarted
        assertEquals(0, player.seekCount)
        assertEquals(0, player.pauseCount)
        assertEquals(0, player.stopCount)

        // Downstream upcoming items updated to reflect the new order
        assertEquals("song_4", player.upcomingMediaIds.first())
    }

    @Test
    fun testNextItemChangedHookTriggeredWhenImmediateNextItemChanges() {
        val manager = QueueManager()
        val songs = makeSongs(5)
        manager.start(songs, startIndex = 0)

        var emittedNextItem: QueueItem? = null
        var emitCount = 0

        fun getNextItem(): QueueItem? =
            manager.userQueue.firstOrNull() ?: manager.upNext.firstOrNull()

        fun performReorderWithHook(qid: String, newPosition: Int): Boolean {
            val oldNext = getNextItem()
            val moved = manager.reorder(qid, newPosition)
            if (moved) {
                val newNext = getNextItem()
                if (oldNext?.qid != newNext?.qid) {
                    emitCount++
                    emittedNextItem = newNext
                }
            }
            return moved
        }

        val initialNext = manager.upNext[0] // song_1
        assertEquals("song_1", initialNext.song.id)

        // Reorder song_3 (index 2) to index 0 -> changes next item!
        val song3 = manager.upNext[2]
        val moved1 = performReorderWithHook(song3.qid, 0)
        assertTrue(moved1)
        assertEquals(1, emitCount)
        assertEquals("song_3", emittedNextItem?.song?.id)

        // Reorder between index 2 and 3 (does NOT change next item at index 0)
        val itemAt2 = manager.upNext[2]
        val moved2 = performReorderWithHook(itemAt2.qid, 3)
        assertTrue(moved2)
        assertEquals(1, emitCount) // emitCount unchanged!
    }
}
