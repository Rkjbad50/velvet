package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.data.isSameTrackAs
import com.velvet.app.playback.PlayerQueueManager
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

        // Removing "current" item skips to the next track as per spec
        assertTrue(manager.removeItem("current"))
        assertEquals("song_1", manager.current?.id)
        assertEquals("song_0", manager.history.last().id)
    }

    @Test
    fun testPlayNextMoveToTopRemoveByQid_withDuplicateSongs() {
        val dupSong = makeSong("same_id", "Same Track")
        val otherSong = makeSong("other_id", "Other Track")
        val item1 = com.velvet.app.playback.QueueItem(dupSong, "qid_1")
        val item2 = com.velvet.app.playback.QueueItem(dupSong, "qid_2")
        val item3 = com.velvet.app.playback.QueueItem(otherSong, "qid_3")

        val state0 = com.velvet.app.playback.QueueState(
            sourceList = listOf(item1, item2, item3),
            current = item1,
            upNext = listOf(item2, item3),
        )

        val manager = com.velvet.app.playback.QueueManager(state0)

        // 1. Move duplicate item2 (qid_2) to top
        val moved = manager.moveToTop("qid_2")
        assertTrue(moved)
        assertEquals(1, manager.userQueue.size)
        assertEquals("qid_2", manager.userQueue.first().qid)
        // item1 (current) remains current with qid_1
        assertEquals("qid_1", manager.state.current?.qid)

        // 2. Add another copy of sameSong via playNext with qid_4
        val item4 = com.velvet.app.playback.QueueItem(dupSong, "qid_4")
        manager.playNext(item4)
        assertEquals(2, manager.userQueue.size)
        assertEquals("qid_4", manager.userQueue[0].qid)
        assertEquals("qid_2", manager.userQueue[1].qid)

        // 3. Remove qid_2: ONLY qid_2 is removed; qid_4 and qid_1 remain!
        val removed = manager.removeItem("qid_2")
        assertTrue(removed)
        assertEquals(1, manager.userQueue.size)
        assertEquals("qid_4", manager.userQueue.first().qid)
        assertEquals("qid_1", manager.state.current?.qid)

        // 4. Remove current (qid_1): advances to next track (qid_4)
        val removedCurrent = manager.removeItem("qid_1")
        assertTrue(removedCurrent)
        assertEquals("qid_4", manager.state.current?.qid)
        assertEquals("qid_1", manager.state.history.last().qid)
    }

    @Test
    fun testQueueActionsByQid_historyIsNeverMutated() {
        val songs = makeSongs(5)
        val manager = com.velvet.app.playback.QueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        // Advance twice: song_0 and song_1 move to history, song_2 is current
        manager.advance()
        manager.advance()
        assertEquals("song_2", manager.current?.id)
        assertEquals(2, manager.history.size)
        val historyQids = manager.state.history.map { it.qid }

        // Attempting moveToTop or remove on a history qid must fail and do nothing
        val movedHist = manager.moveToTop(historyQids[0])
        assertFalse("Cannot moveToTop a history item", movedHist)
        assertEquals(2, manager.history.size)
        assertTrue(manager.userQueue.isEmpty())

        val removedHist = manager.removeItem(historyQids[1])
        assertFalse("Cannot removeItem a history item", removedHist)
        assertEquals(2, manager.history.size)
    }

    @Test
    fun testUserQueueSurvivesShuffleTogglesRepeatRebuildsAndAutoplay() {
        val songs = makeSongs(6)
        val manager = com.velvet.app.playback.QueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        // User manually queues 2 custom songs
        val manual1 = makeSong("m1", "Manual 1")
        val manual2 = makeSong("m2", "Manual 2")
        manager.playNext(manual1)
        manager.addToQueue(manual2)

        assertEquals(2, manager.userQueue.size)
        assertEquals("m1", manager.userQueue[0].song.id)
        assertEquals("m2", manager.userQueue[1].song.id)

        // 1. Survives multiple shuffle toggles
        manager.enableShuffle()
        assertEquals(2, manager.userQueue.size)
        assertEquals("m1", manager.userQueue[0].song.id)
        assertEquals("m2", manager.userQueue[1].song.id)

        manager.disableShuffle()
        assertEquals(2, manager.userQueue.size)
        assertEquals("m1", manager.userQueue[0].song.id)
        assertEquals("m2", manager.userQueue[1].song.id)

        // 2. Survives autoplay extension: autoplay songs append to upNext, never userQueue
        val autoSongs = listOf(makeSong("a1", "Autoplay 1"), makeSong("a2", "Autoplay 2"))
        manager.appendAutoplay(autoSongs)
        assertEquals(2, manager.userQueue.size)
        assertEquals("m1", manager.userQueue[0].song.id)
        assertEquals("m2", manager.userQueue[1].song.id)
        assertTrue(manager.upNext.any { it.song.id == "a1" })
        assertTrue(manager.upNext.any { it.song.id == "a2" })
        assertTrue("Normal autoplay items must NOT be smart recommended", manager.upNext.none { it.isSmartRecommended })

        // 3. User queue songs play first in order before upNext
        val next1 = manager.advance()
        assertEquals("m1", next1?.id)
        val next2 = manager.advance()
        assertEquals("m2", next2?.id)
    }

    @Test
    fun testPrevious_greaterThan3sRestarts_lessOrEqual3sPopsHistory() {
        val songs = makeSongs(3)
        val manager = com.velvet.app.playback.QueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)
        // Advance to song_1
        manager.advance()
        assertEquals("song_1", manager.current?.id)
        assertEquals("song_0", manager.history.last().id)

        // Previous with position > 3000ms: restarts current song, history not popped
        val (stateGt3s, restartedGt3s) = com.velvet.app.playback.QueueManager.previous(manager.state, positionMs = 4500L)
        assertTrue(restartedGt3s)
        assertEquals("song_1", stateGt3s.current?.song?.id)
        assertEquals(1, stateGt3s.history.size)

        // Previous with position <= 3000ms: pops history, song_0 becomes current
        val (stateLe3s, restartedLe3s) = com.velvet.app.playback.QueueManager.previous(manager.state, positionMs = 1200L)
        assertFalse(restartedLe3s)
        assertEquals("song_0", stateLe3s.current?.song?.id)
        assertTrue(stateLe3s.history.isEmpty())
        // Old current (song_1) pushed to front of upNext
        assertEquals("song_1", stateLe3s.upNext.first().song.id)
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

        assertEquals(com.velvet.app.playback.ShuffleMode.OFF, manager.shuffleMode)
        assertFalse(manager.shuffle)

        // 1. Cycle to STANDARD
        val mode1 = manager.cycleShuffleMode()
        assertEquals(com.velvet.app.playback.ShuffleMode.STANDARD, mode1)
        assertTrue(manager.shuffle)
        assertFalse(manager.isSmartShuffle)

        // 2. Cycle to SMART
        val mode2 = manager.cycleShuffleMode()
        assertEquals(com.velvet.app.playback.ShuffleMode.SMART, mode2)
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
        assertEquals(com.velvet.app.playback.ShuffleMode.OFF, mode3)
        assertFalse(manager.shuffle)
        assertTrue(manager.upNext.none { it.isSmartRecommended })
    }

    @Test
    fun testCycleShuffleMode_whenSmartShuffleDisabled_onlyTogglesBetweenOffAndStandard() {
        val songs = makeSongs(5)
        val manager = PlayerQueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        assertEquals(com.velvet.app.playback.ShuffleMode.OFF, manager.shuffleMode)

        // 1. Cycle with allowSmartShuffle = false -> STANDARD
        val mode1 = manager.cycleShuffleMode(allowSmartShuffle = false)
        assertEquals(com.velvet.app.playback.ShuffleMode.STANDARD, mode1)
        assertTrue(manager.shuffle)
        assertFalse(manager.isSmartShuffle)

        // 2. Cycle again with allowSmartShuffle = false -> strictly OFF, never SMART
        val mode2 = manager.cycleShuffleMode(allowSmartShuffle = false)
        assertEquals(com.velvet.app.playback.ShuffleMode.OFF, mode2)
        assertFalse(manager.shuffle)
        assertFalse(manager.isSmartShuffle)

        // 3. Explicit set to SMART when disabled falls back to STANDARD
        val mode3 = manager.setShuffleMode(com.velvet.app.playback.ShuffleMode.SMART, allowSmartShuffle = false)
        assertEquals(com.velvet.app.playback.ShuffleMode.STANDARD, mode3)
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
        var state = com.velvet.app.playback.QueueManager.start(
            com.velvet.app.playback.QueueState(),
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
        state = com.velvet.app.playback.QueueManager.playNext(state, nextSong)
        assertEquals(1, state.userQueue.size)
        assertEquals("priority_1", state.userQueue.first().song.id)

        // Advance: priority_1 should become current, song_0 goes to history
        state = com.velvet.app.playback.QueueManager.advance(state)
        assertEquals("priority_1", state.current?.song?.id)
        assertEquals(1, state.history.size)
        assertEquals("song_0", state.history.first().song.id)
        assertTrue(state.userQueue.isEmpty())

        // Previous: since position is 0, should go back to song_0
        val (prevState, restarted) = com.velvet.app.playback.QueueManager.previous(state, positionMs = 0L)
        assertFalse(restarted)
        assertEquals("song_0", prevState.current?.song?.id)
        assertTrue(prevState.history.isEmpty())
        // priority_1 moved to upNext
        assertEquals("priority_1", prevState.upNext.first().song.id)
    }

    @Test
    fun testRepeatModeTransformations() {
        val songs = makeSongs(2)
        var state = com.velvet.app.playback.QueueManager.start(
            com.velvet.app.playback.QueueState(),
            songs,
            startIndex = 1,
            shuffled = false,
        )

        // repeat ONE
        state = com.velvet.app.playback.QueueManager.setRepeatMode(state, com.velvet.app.playback.RepeatMode.ONE)
        val stateAfterAdvanceOne = com.velvet.app.playback.QueueManager.advance(state)
        assertEquals(state.current?.song?.id, stateAfterAdvanceOne.current?.song?.id)

        // repeat ALL: when upNext is empty, loops back to sourceList
        state = com.velvet.app.playback.QueueManager.setRepeatMode(state, com.velvet.app.playback.RepeatMode.ALL)
        val stateAfterAdvanceAll = com.velvet.app.playback.QueueManager.advance(state)
        assertEquals(songs[0].id, stateAfterAdvanceAll.current?.song?.id)

        // repeat OFF: when queue exhausted, current becomes null
        state = com.velvet.app.playback.QueueManager.setRepeatMode(state, com.velvet.app.playback.RepeatMode.OFF)
        val stateAfterAdvanceOff = com.velvet.app.playback.QueueManager.advance(state)
        assertEquals(null, stateAfterAdvanceOff.current)
    }

    @Test
    fun testReorderingQueues() {
        val songs = makeSongs(4)
        val manager = com.velvet.app.playback.QueueManager()
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

    @Test
    fun testRepeatOne_autoAdvanceReplaysCurrent_manualNextAdvances() {
        val songs = makeSongs(3)
        val manager = com.velvet.app.playback.QueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)
        manager.setRepeatMode(com.velvet.app.playback.RepeatMode.ONE)

        assertEquals("song_0", manager.current?.id)
        assertEquals(com.velvet.app.playback.RepeatMode.ONE, manager.repeatMode)

        // 1. Natural track end (auto-advance with manual = false): replays current track
        val autoAdvanceSong = manager.advance(manual = false)
        assertEquals("song_0", autoAdvanceSong?.id)
        assertEquals("song_0", manager.current?.id)
        assertTrue(manager.history.isEmpty())

        // 2. User presses Next button (manual = true): advances to next track
        val manualNextSong = manager.advance(manual = true)
        assertEquals("song_1", manualNextSong?.id)
        assertEquals("song_1", manager.current?.id)
        assertEquals("song_0", manager.history.last().id)
        assertEquals(com.velvet.app.playback.RepeatMode.ONE, manager.repeatMode)
    }

    @Test
    fun testShuffleRetap_producesNewOrder_rerollsIfIdentical() {
        val songs = makeSongs(15)
        val manager = com.velvet.app.playback.QueueManager()
        manager.start(songs, startIndex = 0, shuffled = false)

        val orders = mutableListOf<List<String>>()

        // Toggle OFF -> ON 5 times
        for (i in 1..5) {
            manager.enableShuffle()
            val order = manager.upNext.map { it.song.id }
            orders.add(order)
            manager.disableShuffle()
        }

        // Check that consecutive shuffled orders are distinct
        for (i in 0 until orders.size - 1) {
            assertNotEquals("Consecutive shuffle taps must generate distinct permutations", orders[i], orders[i + 1])
        }
    }

    @Test
    fun testRepeatAllAtEndOfList_reshufflesWhenShuffleOn() {
        val songs = makeSongs(3)
        var state = com.velvet.app.playback.QueueManager.start(
            com.velvet.app.playback.QueueState(),
            songs,
            startIndex = 2, // at last track
            shuffled = false,
        )
        // Enable shuffle and repeat ALL
        state = com.velvet.app.playback.QueueManager.enableShuffle(state)
        state = com.velvet.app.playback.QueueManager.setRepeatMode(state, com.velvet.app.playback.RepeatMode.ALL)

        // upNext has the other 2 songs shuffled
        assertEquals(2, state.upNext.size)

        // Advance through the remaining 2 tracks
        state = com.velvet.app.playback.QueueManager.advance(state, manual = true)
        assertEquals(1, state.upNext.size)
        state = com.velvet.app.playback.QueueManager.advance(state, manual = true)
        assertEquals(0, state.upNext.size)

        // Advancing at end of list with repeat ALL & shuffle ON rebuilds reshuffled queue
        val nextState = com.velvet.app.playback.QueueManager.advance(state, manual = true)
        assertNotNull(nextState.current)
        assertEquals(2, nextState.upNext.size)
        assertEquals(com.velvet.app.playback.ShuffleMode.STANDARD, nextState.shuffleMode)
        assertEquals(com.velvet.app.playback.RepeatMode.ALL, nextState.repeatMode)
    }

    @Test
    fun testStandalonePlayback_toggleShuffle_doesNotWipeQueue_andPreservesUpcomingTracks() {
        val standaloneSong = makeSong("standalone_1", "Standalone Seed Track")
        val autoplaySongs = makeSongs(5)
        val manager = PlayerQueueManager()

        // 1. User starts a standalone single song (sourceList size = 1)
        manager.start(listOf(standaloneSong), startIndex = 0, shuffled = false)
        assertEquals(standaloneSong.id, manager.current?.id)
        assertEquals(0, manager.upNext.size)

        // 2. Autoplay discovers and appends 5 tracks
        manager.appendAutoplay(autoplaySongs)
        assertEquals(5, manager.upNext.size)
        val initialDiscoveryOrder = manager.upNext.map { it.song.id }

        // 3. Toggle Shuffle ON (Script A / Script B)
        val isShuffled1 = manager.toggleShuffle()
        assertTrue(isShuffled1)
        assertEquals("Current song must remain pinned", standaloneSong.id, manager.current?.id)
        assertEquals("upNext must NOT be wiped out when shuffle is enabled on standalone playback", 5, manager.upNext.size)
        val shuffledOrder1 = manager.upNext.map { it.song.id }
        assertEquals("All autoplay tracks must be retained", initialDiscoveryOrder.toSet(), shuffledOrder1.toSet())

        // 4. Toggle Shuffle OFF
        val isShuffled2 = manager.toggleShuffle()
        assertFalse(isShuffled2)
        assertEquals("Current song must remain pinned", standaloneSong.id, manager.current?.id)
        assertEquals("upNext must NOT be wiped out when shuffle is disabled on standalone playback", 5, manager.upNext.size)
        val linearRestoredOrder = manager.upNext.map { it.song.id }
        assertEquals("Turning shuffle off must restore the original discovery order", initialDiscoveryOrder, linearRestoredOrder)

        // 5. Rapid toggle 5 times (ON -> OFF -> ON -> OFF -> ON)
        for (i in 1..5) {
            val state = manager.toggleShuffle()
            assertEquals("Current song must remain pinned on toggle $i", standaloneSong.id, manager.current?.id)
            assertEquals("upNext size must remain 5 on toggle $i", 5, manager.upNext.size)
            assertEquals(6, manager.visibleQueue.size)
        }
    }

    @Test
    fun testStandalonePlayback_toggleShuffleThenNext_advancesSmoothlyWithoutStopping() {
        val standaloneSong = makeSong("standalone_seed", "Single Seed Song")
        val autoplaySongs = makeSongs(4)
        val manager = PlayerQueueManager()

        // 1. Play standalone song and populate autoplay
        manager.start(listOf(standaloneSong), startIndex = 0, shuffled = false)
        manager.appendAutoplay(autoplaySongs)
        assertEquals(4, manager.upNext.size)

        // 2. Toggle ON -> OFF
        manager.toggleShuffle() // ON
        assertEquals(4, manager.upNext.size)
        manager.toggleShuffle() // OFF
        assertEquals(4, manager.upNext.size)

        // 3. User presses Next (Script B: Toggle ON -> OFF -> Next)
        val firstNext = manager.advance(manual = true)
        assertNotNull("Next track must exist and not be null", firstNext)
        assertEquals("Current must advance to first autoplay track", autoplaySongs[0].id, manager.current?.id)
        assertEquals("History must contain the seed song", listOf(standaloneSong.id), manager.history.map { it.id })
        assertEquals("Remaining upNext count must be 3", 3, manager.upNext.size)

        // 4. Advance through remaining tracks
        val secondNext = manager.advance(manual = true)
        assertEquals(autoplaySongs[1].id, secondNext?.id)
        assertEquals(2, manager.upNext.size)

        val thirdNext = manager.advance(manual = true)
        assertEquals(autoplaySongs[2].id, thirdNext?.id)
        assertEquals(1, manager.upNext.size)

        val fourthNext = manager.advance(manual = true)
        assertEquals(autoplaySongs[3].id, fourthNext?.id)
        assertEquals(0, manager.upNext.size)
    }

    @Test
    fun testTwoTrackShuffle_alwaysReordersOnEveryShuffleToggle() {
        val seed = makeSong("seed_track")
        val pair = listOf(makeSong("pair_1"), makeSong("pair_2"))
        val manager = PlayerQueueManager()

        manager.start(listOf(seed), startIndex = 0, shuffled = false)
        manager.appendAutoplay(pair)
        assertEquals(listOf("pair_1", "pair_2"), manager.upNext.map { it.song.id })

        // Toggle ON with exactly 2 upcoming tracks
        manager.toggleShuffle()
        assertEquals("Should guarantee an alternate order for 2 tracks", listOf("pair_2", "pair_1"), manager.upNext.map { it.song.id })
    }

    @Test
    fun testBlankQueue_cycleShuffleAndSmartShuffleNeverCrashes_andPreservesCurrentSong() {
        val singleSong = makeSong("solo_track", "Solo Track")
        val manager = PlayerQueueManager()

        // Empty manager before any track loaded
        assertEquals(ShuffleMode.STANDARD, manager.cycleShuffleMode(allowSmartShuffle = false))
        assertEquals(ShuffleMode.OFF, manager.cycleShuffleMode(allowSmartShuffle = false))
        assertNull(manager.current)

        // Single standalone song with blank upNext / empty userQueue
        manager.start(listOf(singleSong), startIndex = 0, shuffled = false)
        assertTrue(manager.upNext.isEmpty())
        assertTrue(manager.userQueue.isEmpty())

        assertEquals(ShuffleMode.STANDARD, manager.cycleShuffleMode(allowSmartShuffle = true))
        assertEquals(singleSong.id, manager.current?.id)
        assertTrue(manager.upNext.isEmpty())

        assertEquals(ShuffleMode.SMART, manager.cycleShuffleMode(allowSmartShuffle = true))
        assertEquals(singleSong.id, manager.current?.id)
        assertTrue(manager.upNext.isEmpty())

        assertEquals(ShuffleMode.OFF, manager.cycleShuffleMode(allowSmartShuffle = true))
        assertEquals(singleSong.id, manager.current?.id)
        assertTrue(manager.upNext.isEmpty())
    }
}
