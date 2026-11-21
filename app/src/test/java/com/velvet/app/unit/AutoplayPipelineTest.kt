package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.playback.AutoplayConfig
import com.velvet.app.playback.AutoplayPipeline
import com.velvet.app.playback.CandidateSource
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.QueueState
import com.velvet.app.playback.SessionHistory
import com.velvet.app.playback.canonicalKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoplayPipelineTest {

    private lateinit var history: SessionHistory

    @Before
    fun setUp() {
        history = SessionHistory(50)
    }

    private fun makeSong(
        id: String,
        title: String,
        artist: String = "Test Artist",
        durationSec: Long = 200,
        preferred: Boolean = false,
        artistUrl: String? = null,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = durationSec,
        preferredMusicSource = preferred,
        artistUrl = artistUrl,
    )

    @Test
    fun testCanonicalKey_stripsNoiseAndDelimiters() {
        val key1 = canonicalKey("Kesariya - Full Video | Brahmastra", "Arijit Singh")
        val key2 = canonicalKey("Kesariya (Lyric Video)", "Arijit Singh")
        assertEquals(key1, key2)
        assertEquals("kesariya|arijit singh", key1)
    }

    @Test
    fun testHardFilter_rejectsSlowedReverbUnlessInSeed() = runBlocking {
        val seed = makeSong("seed1", "Tum Hi Ho", "Arijit Singh")
        val slowed = makeSong("c1", "Tum Hi Ho (Slowed + Reverb)", "Arijit Singh - Topic", preferred = true)
        val normal = makeSong("c2", "Channa Mereya", "Arijit Singh - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(slowed, normal) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(1, next.size)
        assertEquals("c2", next[0].id)
    }

    @Test
    fun testHardFilter_allowsRemixWhenSeedIsRemix() = runBlocking {
        val seed = makeSong("seed1", "Project X (Remix)", "Artist A")
        val remixCandidate = makeSong("c1", "Song Y (Club Remix)", "Artist B - Topic", preferred = true)
        val normalCandidate = makeSong("c2", "Song Z", "Artist C - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(remixCandidate, normalCandidate) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(2, next.size)
        assertTrue(next.any { it.id == "c1" })
    }

    @Test
    fun testEligibilityAndDedupe_onlyTopicSurvives() = runBlocking {
        val seed = makeSong("seed1", "Raabta", "Arijit Singh")
        val topic = makeSong("t1", "Tum Hi Ho", "Arijit Singh - Topic", preferred = true)
        val omv = makeSong("t2", "Tum Hi Ho", "T-Series", preferred = false)
        val ugc = makeSong("t3", "Tum Hi Ho", "Random Music HD", preferred = false)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(ugc, omv, topic) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals("Only the Topic (ATV) version must survive", 1, next.size)
        assertEquals("t1", next[0].id)
    }

    @Test
    fun testDedupeWinner_atvBeatsOmvEvenWithEarlierOmvYear() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Artist")
        val atv2019 = makeSong("atv", "Song A", "Artist - Topic", preferred = true)
        val omv2018 = makeSong("omv", "Song A", "Artist", preferred = false)

        val years = mapOf("atv" to 2019, "omv" to 2018)
        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(omv2018, atv2019) }),
            history = history,
            releaseYearProvider = { years[it] },
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(1, next.size)
        assertEquals("atv", next[0].id)
    }

    @Test
    fun testDedupeWinner_twoAtvEarliestYearWins() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Artist")
        val atv2022 = makeSong("atv22", "Song A", "Artist - Topic", preferred = true)
        val atv2018 = makeSong("atv18", "Song A", "Artist - Topic", preferred = true)

        val years = mapOf("atv22" to 2022, "atv18" to 2018)
        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(atv2022, atv2018) }),
            history = history,
            releaseYearProvider = { years[it] },
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(1, next.size)
        assertEquals("atv18", next[0].id)
    }

    @Test
    fun testDedupeWinner_missingDatesFallsBackToSourceOrder() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Artist")
        val atv1 = makeSong("first", "Song A", "Artist - Topic", preferred = true)
        val atv2 = makeSong("second", "Song A", "Artist - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(atv1, atv2) }),
            history = history,
            releaseYearProvider = { null }, // missing dates
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(1, next.size)
        assertEquals("first", next[0].id)
    }

    @Test
    fun testDedupe_historySongRejected() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Artist")
        val played = makeSong("played1", "Song In History", "Singer - Topic", preferred = true)
        val fresh = makeSong("fresh1", "Song Fresh", "Singer - Topic", preferred = true)

        history.record(played)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(played, fresh) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(1, next.size)
        assertEquals("fresh1", next[0].id)
    }

    @Test
    fun testSpacing_separatesSameArtistNeighbours() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Other Artist")
        val songA1 = makeSong("a1", "Track 1", "Artist A - Topic", preferred = true)
        val songA2 = makeSong("a2", "Track 2", "Artist A - Topic", preferred = true)
        val songB1 = makeSong("b1", "Track 3", "Artist B - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(songA1, songA2, songB1) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(3, next.size)
        // No two adjacent songs should have Artist A
        assertFalse(next[0].artist.contains("Artist A") && next[1].artist.contains("Artist A"))
        assertFalse(next[1].artist.contains("Artist A") && next[2].artist.contains("Artist A"))
    }

    @Test
    fun testSpacing_relaxesWhenAllSongsShareArtist() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Other Artist")
        val songA1 = makeSong("a1", "Track 1", "Artist A - Topic", preferred = true)
        val songA2 = makeSong("a2", "Track 2", "Artist A - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(songA1, songA2) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(2, next.size)
    }

    @Test
    fun testTake_fewerThanNReturnsFewerWithoutPadding() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Artist")
        val candidates = listOf(
            makeSong("c1", "Track 1", "Artist - Topic", preferred = true),
            makeSong("c2", "Track 2", "Artist - Topic", preferred = true),
        )

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { candidates }),
            history = history,
        )

        val next = pipeline.next(seed, n = 10)
        assertEquals(2, next.size)
    }

    @Test
    fun testDeterminism_sameInputsProduceIdenticalResults() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Artist")
        val pool = (1..10).map { makeSong("id_$it", "Song $it", "Artist ${it % 3} - Topic", preferred = true) }

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { pool }),
            history = history,
        )

        val run1 = pipeline.next(seed, n = 5)
        val run2 = pipeline.next(seed, n = 5)
        assertEquals(run1.map { it.id }, run2.map { it.id })
    }

    @Test
    fun testFallback_expandOnceWhenZeroSurvive() = runBlocking {
        val seed = makeSong("seed1", "Seed", "Artist")
        val lastQueued = makeSong("last1", "Last Queued", "Artist B")
        val fallbackSong = makeSong("fb1", "Fallback Track", "Artist C - Topic", preferred = true)

        val source = CandidateSource { target ->
            if (target.id == seed.id) emptyList() else listOf(fallbackSong)
        }

        val pipeline = AutoplayPipeline(
            sources = listOf(source),
            history = history,
        )

        val next = pipeline.next(seed, queueSnapshot = listOf(lastQueued), n = 5)
        assertEquals(1, next.size)
        assertEquals("fb1", next[0].id)
    }

    @Test
    fun testUserQueue_duplicateSongsNotFilteredByUserQueueOperations() {
        val song1 = makeSong("dup1", "Duplicate Song", "Artist")
        var state = QueueState()
        state = QueueManager.start(state, listOf(song1))

        // Manually adding the same song to userQueue must succeed and assign a new unique qid
        state = QueueManager.addToQueue(state, song1)
        state = QueueManager.addToQueue(state, song1)

        assertEquals(2, state.userQueue.size)
        assertEquals("dup1", state.userQueue[0].song.id)
        assertEquals("dup1", state.userQueue[1].song.id)
        // Ensure qids are distinct
        assertFalse(state.userQueue[0].qid == state.userQueue[1].qid)
    }
}
