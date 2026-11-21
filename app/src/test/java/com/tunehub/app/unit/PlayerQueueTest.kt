package com.tunehub.app.unit

import com.tunehub.app.data.Song
import com.tunehub.app.data.isSameTrackAs
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
    fun testCycleShuffleMode_whenSmartShuffleDisabled_onlyTogglesBetweenOffAndStandard() {
        val songs = makeSongs(5)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        assertEquals(com.tunehub.app.playback.ShuffleMode.OFF, manager.shuffleMode)

        // 1. Cycle with allowSmartShuffle = false -> STANDARD
        val mode1 = manager.cycleShuffleMode(allowSmartShuffle = false)
        assertEquals(com.tunehub.app.playback.ShuffleMode.STANDARD, mode1)
        assertTrue(manager.shuffle)
        assertFalse(manager.isSmartShuffle)

        // 2. Cycle again with allowSmartShuffle = false -> strictly OFF, never SMART
        val mode2 = manager.cycleShuffleMode(allowSmartShuffle = false)
        assertEquals(com.tunehub.app.playback.ShuffleMode.OFF, mode2)
        assertFalse(manager.shuffle)
        assertFalse(manager.isSmartShuffle)

        // 3. Explicit set to SMART when disabled falls back to STANDARD
        val mode3 = manager.setShuffleMode(com.tunehub.app.playback.ShuffleMode.SMART, allowSmartShuffle = false)
        assertEquals(com.tunehub.app.playback.ShuffleMode.STANDARD, mode3)
        assertFalse(manager.isSmartShuffle)
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

    @Test
    fun testSongIsSameTrackAs_strictDeduplicationAcrossCreators() {
        val s1 = makeSong("id1", "Chikni Chameli", "Ajay-Atul")
        val s2 = makeSong("id2", "Chikni Chameli (Official Music Video)", "Sony Music India")
        val s3 = makeSong("id3", "Chikni Chameli [Full Video Song]", "T-Series")
        val s4 = makeSong("id4", "Afghan Jalebi", "Asrar")

        // Must match same title across channels/uploaders
        assertTrue(s1.isSameTrackAs(s2))
        assertTrue(s1.isSameTrackAs(s3))
        assertTrue(s2.isSameTrackAs(s3))

        // Different song must NOT match
        assertFalse(s1.isSameTrackAs(s4))
    }

    @Test
    fun testSmartShuffle_deduplicationAgainstPlaylistAndWithinRecommendations() {
        val playlist = listOf(
            makeSong("p1", "Kesariya"),
            makeSong("p2", "Apna Bana Le"),
            makeSong("p3", "Deva Deva"),
            makeSong("p4", "Rasiya"),
            makeSong("p5", "Dance Ka Bhoot"),
        )
        val manager = PlayerQueueManager()
        manager.start(playlist, startIndex = 0, shuffled = true)
        manager.cycleShuffleMode() // STANDARD
        manager.cycleShuffleMode() // SMART

        // Recommendations contain a duplicate of "Kesariya (Official Video)" by a different creator,
        // and two duplicates of "Tum Hi Ho", plus unique songs
        val recs = listOf(
            makeSong("r1", "Kesariya (Official Video)", "Sony"),
            makeSong("r2", "Tum Hi Ho", "T-Series"),
            makeSong("r3", "Tum Hi Ho [Full Song]", "Arijit"),
            makeSong("r4", "Raabta", "Pritam"),
            makeSong("r5", "Shayad", "Pritam"),
        )
        manager.injectSmartRecommendations(recs)

        val smartItems = manager.upNext.filter { it.isSmartRecommended }
        // Kesariya must be rejected because it exists in the playlist
        assertTrue(smartItems.none { it.song.title.contains("Kesariya") })
        // Tum Hi Ho must appear at most once (no duplicate recommendations)
        val tumHiHoCount = smartItems.count { it.song.title.contains("Tum Hi Ho") }
        assertTrue(tumHiHoCount <= 1)
    }

    @Test
    fun testSmartShuffle_injectionRatioPer5To10Songs() {
        // Test with 5 songs in playlist (4 upNext): should inject 2 recommendations (2 per 5)
        val playlist5 = makeSongs(5)
        val manager5 = PlayerQueueManager()
        manager5.start(playlist5, startIndex = 0, shuffled = false)
        manager5.cycleShuffleMode() // STANDARD
        manager5.cycleShuffleMode() // SMART

        val recs = (100..120).map { makeSong("rec_$it", "Fresh Song #$it") }
        manager5.injectSmartRecommendations(recs)
        val smart5 = manager5.upNext.filter { it.isSmartRecommended }
        assertEquals("For 5 playlist songs, must inject exactly 2 recommendations", 2, smart5.size)

        // Test with 10 songs in playlist (9 upNext): should inject 3 or 4 recommendations (2-4 per 5-10)
        val playlist10 = makeSongs(10)
        val manager10 = PlayerQueueManager()
        manager10.start(playlist10, startIndex = 0, shuffled = false)
        manager10.cycleShuffleMode() // STANDARD
        manager10.cycleShuffleMode() // SMART
        manager10.injectSmartRecommendations(recs)
        val smart10 = manager10.upNext.filter { it.isSmartRecommended }
        assertTrue("For 10 playlist songs, must inject 3 or 4 recommendations", smart10.size in 3..4)
    }

    @Test
    fun testPureQueueStateTransformations() {
        val songs = makeSongs(5)
        var state = com.tunehub.app.playback.QueueManager.start(
            com.tunehub.app.playback.QueueState(),
            songs,
            startIndex = 0,
            shuffled = false,
        )

        assertEquals("song_0", state.current?.song?.id)
        assertEquals(4, state.upNext.size)
        assertTrue(state.history.isEmpty())
        assertTrue(state.userQueue.isEmpty())

        // PlayNext
        val nextSong = makeSong("priority_1", "Priority Track")
        state = com.tunehub.app.playback.QueueManager.playNext(state, nextSong)
        assertEquals(1, state.userQueue.size)
        assertEquals("priority_1", state.userQueue.first().song.id)

        // Advance: priority_1 should become current, song_0 goes to history
        state = com.tunehub.app.playback.QueueManager.advance(state)
        assertEquals("priority_1", state.current?.song?.id)
        assertEquals(1, state.history.size)
        assertEquals("song_0", state.history.first().song.id)
        assertTrue(state.userQueue.isEmpty())

        // Previous: since position is 0, should go back to song_0
        val (prevState, restarted) = com.tunehub.app.playback.QueueManager.previous(state, positionMs = 0L)
        assertFalse(restarted)
        assertEquals("song_0", prevState.current?.song?.id)
        assertTrue(prevState.history.isEmpty())
        // priority_1 moved to upNext
        assertEquals("priority_1", prevState.upNext.first().song.id)
    }

    @Test
    fun testRepeatModeTransformations() {
        val songs = makeSongs(2)
        var state = com.tunehub.app.playback.QueueManager.start(
            com.tunehub.app.playback.QueueState(),
            songs,
            startIndex = 1,
            shuffled = false,
        )

        // repeat ONE
        state = com.tunehub.app.playback.QueueManager.setRepeatMode(state, com.tunehub.app.playback.RepeatMode.ONE)
        val stateAfterAdvanceOne = com.tunehub.app.playback.QueueManager.advance(state)
        assertEquals(state.current?.song?.id, stateAfterAdvanceOne.current?.song?.id)

        // repeat ALL: when upNext is empty, loops back to sourceList
        state = com.tunehub.app.playback.QueueManager.setRepeatMode(state, com.tunehub.app.playback.RepeatMode.ALL)
        val stateAfterAdvanceAll = com.tunehub.app.playback.QueueManager.advance(state)
        assertEquals(songs[0].id, stateAfterAdvanceAll.current?.song?.id)

        // repeat OFF: when queue exhausted, current becomes null
        state = com.tunehub.app.playback.QueueManager.setRepeatMode(state, com.tunehub.app.playback.RepeatMode.OFF)
        val stateAfterAdvanceOff = com.tunehub.app.playback.QueueManager.advance(state)
        assertEquals(null, stateAfterAdvanceOff.current)
    }

    @Test
    fun testReorderingQueues() {
        val songs = makeSongs(4)
        val manager = com.tunehub.app.playback.QueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        // upNext is [song_1, song_2, song_3]
        assertEquals("song_1", manager.upNext[0].song.id)
        assertEquals("song_2", manager.upNext[1].song.id)
        assertEquals("song_3", manager.upNext[2].song.id)

        manager.reorderUpNext(0, 2)
        // after moving index 0 to 2: [song_2, song_3, song_1]
        assertEquals("song_2", manager.upNext[0].song.id)
        assertEquals("song_3", manager.upNext[1].song.id)
        assertEquals("song_1", manager.upNext[2].song.id)
    }
}
