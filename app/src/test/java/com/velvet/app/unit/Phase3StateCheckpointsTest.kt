package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.playback.PersistedPlaybackState
import com.velvet.app.playback.PlaybackCheckpointScheduler
import com.velvet.app.playback.PlaybackStateManager
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.RepeatMode
import com.velvet.app.playback.ShuffleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase P3 verification:
 * - Atomic fsync persistence of full queue runway + sourceList + repeat/shuffle/radio state.
 * - 300ms debounced mutation checkpoint + periodic checkpoint while playing.
 * - Crash / process-kill restore bringing back exact current track, position, and upcoming runway.
 */
class Phase3StateCheckpointsTest {

    private fun studioSong(id: String, title: String, artist: String) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = 240L,
        album = "Velvet Album",
        isTopicRelease = true,
        rawUploaderName = "$artist - Topic",
        preferredMusicSource = true,
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    @Test
    fun midTrackCrashAndKillRestoresExactTrackPositionAndUpcomingRunway() {
        val tempDir = Files.createTempDirectory("velvet_p3_restore").toFile()
        try {
            val playlistSongs = (0 until 25).map { i ->
                studioSong("tr_$i", "Title Number Alpha$i", "Artist ${i % 5}")
            }
            val qm = QueueManager()
            qm.start(playlistSongs, startIndex = 0, shuffled = false)
            qm.setShuffleMode(ShuffleMode.STANDARD, allowSmartShuffle = false)
            qm.setRepeatMode(RepeatMode.ALL)
            qm.advance(manual = false)
            qm.advance(manual = false)
            val userAdded = studioSong("user_q", "Requested Encore Track", "Sonu Nigam")
            qm.playNext(userAdded)
            val radioSeed = playlistSongs.first()

            val expectedCurrent = qm.current!!
            val expectedUpNext = qm.upNext.map { it.song }
            val expectedUserQueue = qm.userQueue.map { it.song }
            val expectedHistory = qm.history
            val expectedSource = qm.sourceList

            val snapshotBeforeCrash = PersistedPlaybackState(
                song = expectedCurrent,
                positionMs = 84_500L,
                durationMs = 240_000L,
                upNext = expectedUpNext,
                userQueue = expectedUserQueue,
                history = expectedHistory,
                sourceList = expectedSource,
                playlistId = "pl_hindi_gold",
                isRadio = true,
                isArtistRadio = true,
                radioSeedSong = radioSeed,
                shuffleMode = qm.shuffleMode,
                repeatMode = qm.repeatMode,
            )

            assertTrue(PlaybackStateManager.saveToDirectory(tempDir, snapshotBeforeCrash))
            assertTrue(File(tempDir, "playback_state.json").exists())
            assertFalse("Temp checkpoint file must not be left dangling", File(tempDir, "playback_state.json.tmp").exists())

            // Simulate complete process kill: construct brand new QueueManager from disk only
            val loaded = PlaybackStateManager.loadFromDirectory(tempDir)
            assertNotNull("Checkpoint should survive process kill", loaded)
            loaded!!

            val freshQueueAfterRestart = QueueManager()
            freshQueueAfterRestart.restore(loaded, allowSmartShuffle = false)

            assertEquals(expectedCurrent.id, loaded.song.id)
            assertEquals(84_500L, loaded.positionMs)
            assertEquals("pl_hindi_gold", loaded.playlistId)
            assertTrue(loaded.isRadio)
            assertTrue(loaded.isArtistRadio)
            assertEquals(radioSeed.id, loaded.radioSeedSong?.id)

            assertEquals(expectedCurrent.id, freshQueueAfterRestart.current?.id)
            assertEquals(expectedUpNext.map { it.id }, freshQueueAfterRestart.upNext.map { it.song.id })
            assertEquals(expectedUserQueue.map { it.id }, freshQueueAfterRestart.userQueue.map { it.song.id })
            assertEquals(expectedHistory.map { it.id }, freshQueueAfterRestart.history.map { it.id })
            assertEquals(expectedSource.map { it.id }, freshQueueAfterRestart.sourceList.map { it.id })
            assertEquals(ShuffleMode.STANDARD, freshQueueAfterRestart.shuffleMode)
            assertEquals(RepeatMode.ALL, freshQueueAfterRestart.repeatMode)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun debouncedAndPeriodicCheckpointsCoalesceMutationsAndTrackPlaybackPosition() = runBlocking {
        val tempDir = Files.createTempDirectory("velvet_p3_sched").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val song = studioSong("s1", "Midnight Melody", "Shreya Ghoshal")
            val posMs = AtomicLong(1_000L)
            val isPlaying = AtomicBoolean(false)
            val persistCount = AtomicInteger(0)

            val scheduler = PlaybackCheckpointScheduler(
                scope = scope,
                debounceMs = 60L,
                periodicIntervalMs = 80L,
                mainDispatcher = Dispatchers.Default,
                ioDispatcher = Dispatchers.IO,
                snapshotProvider = {
                    PersistedPlaybackState(
                        song = song,
                        positionMs = posMs.get(),
                        durationMs = 240_000L,
                        upNext = listOf(studioSong("s2", "Silver Sky", "KK")),
                    )
                },
                onPersist = { state ->
                    if (PlaybackStateManager.saveToDirectory(tempDir, state)) {
                        persistCount.incrementAndGet()
                    }
                },
            )

            // Rapid queue/track mutations coalesce into 1 disk write with the latest position
            repeat(10) { idx ->
                posMs.set((idx + 1) * 1_000L)
                scheduler.scheduleDebounced()
                delay(5L)
            }
            delay(180L)
            assertEquals("10 rapid mutations within debounce window should coalesce into 1 write", 1, persistCount.get())
            assertEquals(10_000L, PlaybackStateManager.loadFromDirectory(tempDir)?.positionMs)

            // Periodic checkpoint runs while playing and pauses when playback pauses
            isPlaying.set(true)
            posMs.set(45_000L)
            scheduler.startPeriodic { isPlaying.get() }
            delay(210L)
            val countWhilePlaying = persistCount.get()
            assertTrue("Expected periodic checkpoints while playing (count=$countWhilePlaying)", countWhilePlaying >= 2)
            assertEquals(45_000L, PlaybackStateManager.loadFromDirectory(tempDir)?.positionMs)

            isPlaying.set(false)
            // Allow any already-dispatched tick on IO to settle before observing paused baseline
            delay(100L)
            val pausedBaseline = persistCount.get()
            posMs.set(99_000L)
            delay(200L)
            assertEquals("Periodic checkpoints should idle while paused", pausedBaseline, persistCount.get())
            assertEquals(45_000L, PlaybackStateManager.loadFromDirectory(tempDir)?.positionMs)
            scheduler.stop()
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun legacyCheckpointWithoutRunwayMetadataDecodesSafely() {
        val tempDir = Files.createTempDirectory("velvet_p3_legacy").toFile()
        try {
            val legacyJson = """
                {
                  "song": {"id":"old_1","title":"Retro Song","artist":"Kishore Kumar","durationSec":210},
                  "positionMs": 55123,
                  "durationMs": 210000,
                  "upNext": [{"id":"old_2","title":"Dream Song","artist":"Lata Mangeshkar","durationSec":195}],
                  "playlistId": "p_retro",
                  "isRadio": false,
                  "shuffleMode": "STANDARD",
                  "savedAt": 1700000000000
                }
            """.trimIndent()
            File(tempDir, "playback_state.json").writeText(legacyJson)

            val loaded = PlaybackStateManager.loadFromDirectory(tempDir)
            assertNotNull(loaded)
            loaded!!
            val qm = QueueManager()
            qm.restore(loaded)

            assertEquals("old_1", qm.current?.id)
            assertEquals(listOf("old_2"), qm.upNext.map { it.song.id })
            assertEquals(RepeatMode.OFF, qm.repeatMode)
            assertFalse(loaded.isArtistRadio)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
