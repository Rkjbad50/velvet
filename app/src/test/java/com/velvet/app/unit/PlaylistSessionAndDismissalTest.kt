package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.playback.AutoplayConfig
import com.velvet.app.playback.AutoplayPipeline
import com.velvet.app.playback.DismissedTrackRegistry
import com.velvet.app.playback.PlayerQueueManager
import com.velvet.app.playback.SessionHistory
import com.velvet.app.playback.ShuffleMode
import com.velvet.app.playback.SongIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistSessionAndDismissalTest {

    private fun makeSong(id: String, title: String = "Title $id", artist: String = "Artist"): Song =
        Song(id = id, title = title, artist = artist, durationSec = 240)

    private fun makePlaylist(count: Int): List<Song> =
        (0 until count).map { makeSong("song_$it", "Playlist Track #$it", "Velvet Artist") }

    @Before
    fun setup() {
        DismissedTrackRegistry.clear()
    }

    @Test
    fun testPlaylistSession_PlayPauseResume_preservesCurrentTrackAndQueue() {
        val songs = makePlaylist(10)
        val manager = PlayerQueueManager()

        // 1. User starts playlist sequentially
        manager.start(songs, startIndex = 0, shuffled = false)
        assertEquals("song_0", manager.current?.id)
        assertEquals(9, manager.upNext.size)

        // 2. User pauses (ExoPlayer state pauses, queue stays unchanged)
        val pausedTrack = manager.current
        val pausedUpNext = manager.upNext.map { it.song.id }
        assertNotNull(pausedTrack)

        // 3. User taps Play again from playlist header (Resume)
        // Correct behavior: do NOT call manager.start()! Queue and current track stay identical.
        assertEquals(pausedTrack?.id, manager.current?.id)
        assertEquals(pausedUpNext, manager.upNext.map { it.song.id })
    }

    @Test
    fun testPlaylistSession_ShufflePlayPauseResume_preservesShuffledCurrentAndUpcomingOrder() {
        val songs = makePlaylist(20)
        val manager = PlayerQueueManager()

        // 1. User starts playlist with Shuffle Play at a random track (e.g. index 7)
        manager.start(songs, startIndex = 7, shuffled = true)
        val initialCurrent = manager.current
        assertNotNull(initialCurrent)
        assertEquals("song_7", initialCurrent?.id)
        assertEquals(ShuffleMode.STANDARD, manager.shuffleMode)
        val initialShuffledUpNext = manager.upNext.map { it.song.id }
        assertEquals(19, initialShuffledUpNext.size)

        // 2. Playback pauses from playlist header
        val pausedTrackId = manager.current?.id
        assertEquals("song_7", pausedTrackId)

        // 3. User taps Play from playlist header (Resume)
        // BUG 2 regression test: Must NOT jump back to song_0 or regenerate a fresh queue!
        assertEquals("Current song must remain song_7 on resume", "song_7", manager.current?.id)
        assertEquals("Remaining shuffled queue order must be preserved exactly on resume",
            initialShuffledUpNext, manager.upNext.map { it.song.id })
        assertFalse("song_0 should not become current on resume of shuffled session",
            manager.current?.id == "song_0" && initialCurrent?.id != "song_0")
    }

    @Test
    fun testDismissedTrackRegistry_recordsAndPreventsReintroduction() {
        val song1 = Song(id = "vid_123", title = "Tum Hi Ho (Official Audio)", artist = "Arijit Singh - Topic", durationSec = 262)
        val song2SameRecordingDifferentUpload = Song(id = "vid_999", title = "Tum Hi Ho | Aashiqui 2", artist = "Arijit Singh", durationSec = 264)
        val unrelatedSong = Song(id = "vid_456", title = "Channa Mereya", artist = "Arijit Singh", durationSec = 289)

        assertFalse(DismissedTrackRegistry.isDismissed(song1))
        assertFalse(DismissedTrackRegistry.isDismissed(song2SameRecordingDifferentUpload))

        // User removes song1
        DismissedTrackRegistry.recordDismissal(song1)

        // song1 is dismissed
        assertTrue(DismissedTrackRegistry.isDismissed(song1))
        // song2 (same recording from different uploader) is recognized as dismissed
        assertTrue(DismissedTrackRegistry.isDismissed(song2SameRecordingDifferentUpload))
        // unrelated song is not dismissed
        assertFalse(DismissedTrackRegistry.isDismissed(unrelatedSong))

        // Session reset clears dismissal memory
        DismissedTrackRegistry.clear()
        assertFalse(DismissedTrackRegistry.isDismissed(song1))
    }

    @Test
    fun testAudioClassification_classifiesAccurately() {
        val topicSong = Song(id = "t1", title = "Kesariya", artist = "Arijit Singh - Topic", durationSec = 268)
        assertEquals(SongIdentity.AudioClassification.ORIGINAL_TOPIC_AUDIO, SongIdentity.classifySong(topicSong))
        assertTrue(SongIdentity.classifySong(topicSong).isOriginalStudioAudio)

        val officialAudio = Song(id = "t2", title = "Kesariya (Official Audio)", artist = "Sony Music India", durationSec = 268)
        assertEquals(SongIdentity.AudioClassification.RECORD_LABEL_STUDIO_AUDIO, SongIdentity.classifySong(officialAudio))
        assertTrue(SongIdentity.classifySong(officialAudio).isOriginalStudioAudio)

        val musicVideo = Song(id = "v1", title = "Kesariya - Official Music Video | Brahmāstra", artist = "Sony Music India", durationSec = 280)
        assertEquals(SongIdentity.AudioClassification.OFFICIAL_MUSIC_VIDEO, SongIdentity.classifySong(musicVideo))

        val lyricalVideo = Song(id = "l1", title = "Kesariya Lyrical Video Song", artist = "Sony Music India", durationSec = 270)
        assertEquals(SongIdentity.AudioClassification.LYRICAL_VIDEO, SongIdentity.classifySong(lyricalVideo))

        val dialogueScene = Song(id = "d1", title = "Kesariya Song with Film Dialogue Promo Scene", artist = "Film Clips", durationSec = 310)
        assertEquals(SongIdentity.AudioClassification.FILM_DIALOGUE_VERSION, SongIdentity.classifySong(dialogueScene))

        val enhancedReupload = Song(id = "e1", title = "Kesariya (4K 60FPS AI Enhanced Remastered Video)", artist = "HD Uploader", durationSec = 268)
        assertEquals(SongIdentity.AudioClassification.ENHANCED_REUPLOAD, SongIdentity.classifySong(enhancedReupload))

        val remix = Song(id = "r1", title = "Kesariya (DJ Remix Club Mix)", artist = "DJ Mixes", durationSec = 210)
        assertEquals(SongIdentity.AudioClassification.REMIX_OR_ALTERNATE_VERSION, SongIdentity.classifySong(remix))
    }

    @Test
    fun testRepresentativeSelection_prioritizesStudioAudioOverVideoAndLyrical() {
        val topic = Song(id = "topic_id", title = "Chaiyya Chaiyya", artist = "Sukhwinder Singh - Topic", durationSec = 390)
        val mv = Song(id = "mv_id", title = "Chaiyya Chaiyya - Official Video", artist = "Venus", durationSec = 405)
        val lyrical = Song(id = "lyr_id", title = "Chaiyya Chaiyya (Lyrical Video)", artist = "Venus", durationSec = 392)

        val group = listOf(mv, lyrical, topic)
        val winner = SongIdentity.selectRepresentative(group)

        assertEquals("Topic / Original Studio Audio must be selected as winner", "topic_id", winner.id)
    }

    @Test
    fun testAutoplayPipeline_hardFiltersDialogueAndDismissedTracks() {
        val history = SessionHistory()
        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)

        val seedTitle = "Pehla Nasha"
        val dialogueTrack = Song(id = "dial1", title = "Pehla Nasha Movie Dialogue Scene", artist = "Uploader", durationSec = 240)
        val dismissedTrack = Song(id = "dism1", title = "Roop Tera Mastana", artist = "Kishore Kumar - Topic", durationSec = 220)

        // 1. Dialogue track must be hard filtered out
        assertTrue(pipeline.isHardFiltered(dialogueTrack, seedTitle))

        // 2. Non-dismissed regular track is not hard filtered
        assertFalse(pipeline.isHardFiltered(dismissedTrack, seedTitle))

        // 3. Once dismissed, it must be hard filtered out
        DismissedTrackRegistry.recordDismissal(dismissedTrack)
        assertTrue(pipeline.isHardFiltered(dismissedTrack, seedTitle))
    }
}
