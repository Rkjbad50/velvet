package com.tunehub.app.unit

import com.tunehub.app.data.Song
import com.tunehub.app.playback.PlayerQueueManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueTest {

    private fun makeSong(id: String, title: String = "Title $id", artist: String = "Artist"): Song =
        Song(id = id, title = title, artist = artist, durationSec = 180)

    private fun makeSongs(count: Int): List<Song> =
        (0 until count).map { makeSong("song_$it", "Song #$it") }

    @Test
    fun testStartWithShuffle_currentIsTappedSong_noItemsBeforeCurrent_shuffledRest() {
        val songs = makeSongs(10)
        val manager = PlayerQueueManager()

        // User taps song at index 4 with shuffle
        val tappedSong = songs[4]
        manager.start(songs, startIndex = 4, shuffled = true)

        // 1. Current must be the tapped song
        assertEquals(tappedSong.id, manager.current?.id)

        // 2. Visible queue starts with the tapped song (index 0) — zero items before it!
        val queue = manager.visibleQueue
        assertEquals(10, queue.size)
        assertEquals(tappedSong.id, queue.first().song.id)
        assertEquals("current", queue.first().qid)

        // 3. History must be empty
        assertTrue(manager.history.isEmpty())

        // 4. upNext contains the remaining 9 songs, none of which is the tapped song
        assertEquals(9, manager.upNext.size)
        assertTrue(manager.upNext.none { it.song.id == tappedSong.id })

        // 5. No duplicates anywhere
        val ids = queue.map { it.song.id }
        assertEquals(10, ids.distinct().size)
    }

    @Test
    fun testToggleShuffle5TimesMidPlay_currentUnchanged_orderChanges_noDuplicates() {
        val songs = makeSongs(25)
        val manager = PlayerQueueManager()

        // Start playback at index 3 in normal linear mode
        val initialSong = songs[3]
        manager.start(songs, startIndex = 3, shuffled = false)

        assertEquals(initialSong.id, manager.current?.id)
        assertFalse(manager.shuffle)

        // Record initial linear upNext order
        val linearUpcomingIds = manager.upNext.map { it.song.id }
        assertEquals(21, linearUpcomingIds.size) // 25 - (3 + 1) = 21

        val shuffledOrders = mutableListOf<List<String>>()

        // Toggle shuffle 5 times (ON -> OFF -> ON -> OFF -> ON)
        for (i in 1..5) {
            val isShuffled = manager.toggleShuffle()

            // Current track MUST NEVER change, restart, or be null
            assertEquals(
                "Current song must remain identical across toggle $i",
                initialSong.id,
                manager.current?.id,
            )

            // Visible queue must ALWAYS have current at index 0
            assertEquals(
                "Current song must be the very first item in visibleQueue at toggle $i",
                initialSong.id,
                manager.visibleQueue.first().song.id,
            )

            // History must remain empty (no past songs in queue)
            assertTrue("History must be empty during toggle $i", manager.history.isEmpty())

            // No duplicates anywhere in visible queue
            val currentVisibleIds = manager.visibleQueue.map { it.song.id }
            assertEquals(
                "No duplicates allowed in visibleQueue at toggle $i",
                currentVisibleIds.distinct().size,
                currentVisibleIds.size,
            )

            if (isShuffled) {
                // When ON: upNext contains all other songs (24 songs) shuffled
                val upNextIds = manager.upNext.map { it.song.id }
                assertTrue("upNext must not contain current song", upNextIds.none { it == initialSong.id })
                assertEquals(24, upNextIds.size)
                shuffledOrders.add(upNextIds)
            } else {
                // When OFF: upNext restored to linear order after current position
                val restoredIds = manager.upNext.map { it.song.id }
                assertEquals(linearUpcomingIds, restoredIds)
            }
        }

        // Assert that the shuffled orders generated are valid permutations
        assertTrue("Shuffled orders must be collected", shuffledOrders.isNotEmpty())
        for (order in shuffledOrders) {
            // Must have all 24 other song IDs
            assertEquals(24, order.toSet().size)
        }
    }

    @Test
    fun testUserQueueRemainsUntouchedDuringShuffleToggle() {
        val songs = makeSongs(15)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        // User manually queues two songs
        val playNextSong = makeSong("custom_next", "Manual Next")
        val enqueuedSong = makeSong("custom_tail", "Manual Tail")

        manager.playNext(playNextSong)
        manager.enqueue(enqueuedSong)

        assertEquals(2, manager.userQueue.size)
        assertEquals("custom_next", manager.userQueue[0].song.id)
        assertEquals("custom_tail", manager.userQueue[1].song.id)

        // Toggle shuffle ON, OFF, ON
        manager.toggleShuffle()
        assertTrue(manager.shuffle)
        assertEquals(2, manager.userQueue.size)
        assertEquals("custom_next", manager.userQueue[0].song.id)
        assertEquals("custom_tail", manager.userQueue[1].song.id)
        // upNext must not contain the userQueue songs
        assertTrue(manager.upNext.none { it.song.id == "custom_next" || it.song.id == "custom_tail" })

        manager.toggleShuffle()
        assertFalse(manager.shuffle)
        assertEquals(2, manager.userQueue.size)
        assertEquals("custom_next", manager.userQueue[0].song.id)
        assertEquals("custom_tail", manager.userQueue[1].song.id)
    }

    @Test
    fun testMoveToTopAndRemoveByQid() {
        val songs = makeSongs(5)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        // Visible queue: song_0 (current), song_1, song_2, song_3, song_4
        val targetItem = manager.upNext[2] // song_3
        val targetQid = targetItem.qid

        // Move song_3 to top
        val moved = manager.moveToTop(targetQid)
        assertTrue(moved)

        // It should now be promoted to index 0 of userQueue!
        assertEquals(1, manager.userQueue.size)
        assertEquals(targetQid, manager.userQueue[0].qid)

        // Visible queue: [song_0, song_3, song_1, song_2, song_4]
        val vQueue = manager.visibleQueue
        assertEquals("song_0", vQueue[0].song.id)
        assertEquals("song_3", vQueue[1].song.id)

        // Remove item by qid
        val removed = manager.removeItem(targetQid)
        assertTrue(removed)
        assertTrue(manager.userQueue.isEmpty())
        assertTrue(manager.visibleQueue.none { it.qid == targetQid })

        // Cannot remove "current" item
        assertFalse(manager.removeItem("current"))
        assertNotNull(manager.current)
    }

    @Test
    fun testAdvanceQueue_movesCurrentToHistory_popsNext() {
        val songs = makeSongs(5)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        val firstSong = manager.current!!
        assertEquals("song_0", firstSong.id)

        // Advance to next track
        val nextSong = manager.advance()
        assertNotNull(nextSong)
        assertEquals("song_1", nextSong?.id)
        assertEquals("song_1", manager.current?.id)

        // History now has firstSong
        assertEquals(1, manager.history.size)
        assertEquals(firstSong.id, manager.history[0].id)

        // Visible queue NEVER contains history!
        val vQueue = manager.visibleQueue
        assertEquals("song_1", vQueue.first().song.id)
        assertTrue("History must never appear in visible queue", vQueue.none { it.song.id == "song_0" })
    }

    @Test
    fun testJumpToQid_switchesCurrentImmediately() {
        val songs = makeSongs(6)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        val targetItem = manager.upNext[3] // song_4
        val targetQid = targetItem.qid

        val jumped = manager.jumpToQid(targetQid)
        assertNotNull(jumped)
        assertEquals("song_4", jumped?.id)
        assertEquals("song_4", manager.current?.id)

        // Previous current (song_0) moved to history
        assertEquals(1, manager.history.size)
        assertEquals("song_0", manager.history[0].id)

        // Visible queue starts with song_4
        assertEquals("song_4", manager.visibleQueue.first().song.id)
    }

    @Test
    fun testEnqueueDuplicateRejected() {
        val songs = makeSongs(3)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        // Attempt to enqueue currently playing song
        val resCurrent = manager.enqueue(songs[0])
        assertFalse("Current song cannot be enqueued", resCurrent)

        // Attempt to enqueue upcoming song
        val resUpcoming = manager.enqueue(songs[1])
        assertFalse("Upcoming song cannot be enqueued as duplicate", resUpcoming)

        // Enqueue brand new song
        val newSong = makeSong("brand_new", "Fresh Song")
        val resNew = manager.enqueue(newSong)
        assertTrue("New song should be enqueued", resNew)
        assertEquals(1, manager.userQueue.size)
    }

    @Test
    fun testCycleShuffleModeAndSmartShuffleInjection() {
        val songs = makeSongs(5)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        assertEquals(com.tunehub.app.playback.ShuffleMode.OFF, manager.shuffleMode)
        assertFalse(manager.shuffle)

        // 1. Cycle to STANDARD
        val mode1 = manager.cycleShuffleMode()
        assertEquals(com.tunehub.app.playback.ShuffleMode.STANDARD, mode1)
        assertTrue(manager.shuffle)
        assertFalse(manager.isSmartShuffle)

        // 2. Cycle to SMART
        val mode2 = manager.cycleShuffleMode()
        assertEquals(com.tunehub.app.playback.ShuffleMode.SMART, mode2)
        assertTrue(manager.shuffle)
        assertTrue(manager.isSmartShuffle)

        // Inject smart recommendations
        val recSongs = listOf(makeSong("rec_1", "Rec 1"), makeSong("rec_2", "Rec 2"))
        manager.injectSmartRecommendations(recSongs)

        val smartItems = manager.upNext.filter { it.isSmartRecommended }
        assertEquals(2, smartItems.size)
        assertEquals("rec_1", smartItems[0].song.id)
        assertEquals("rec_2", smartItems[1].song.id)

        // 3. Cycle to OFF: smart recommendations must be cleared, linear restored
        val mode3 = manager.cycleShuffleMode()
        assertEquals(com.tunehub.app.playback.ShuffleMode.OFF, mode3)
        assertFalse(manager.shuffle)
        assertTrue(manager.upNext.none { it.isSmartRecommended })
    }

    @Test
    fun testAppendRadioSongsAutoplayQueueExtension() {
        val songs = makeSongs(1)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        assertEquals(1, manager.visibleQueue.size)
        assertTrue(manager.upNext.isEmpty())

        // Autoplay extensions arrives
        val autoplaySongs = makeSongs(5).drop(1)
        manager.appendRadioSongs(autoplaySongs)

        assertEquals(4, manager.upNext.size)
        assertEquals(5, manager.visibleQueue.size)

        // Advance to next autoplay song
        val next = manager.advance()
        assertEquals(autoplaySongs[0].id, next?.id)
        assertEquals(3, manager.upNext.size)
    }
}
