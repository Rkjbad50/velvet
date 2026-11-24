package com.velvet.app.unit

import com.velvet.app.data.ListenEndReason
import com.velvet.app.data.ListenEvent
import com.velvet.app.data.Song
import com.velvet.app.playback.AutoplayConfig
import com.velvet.app.playback.AutoplayPipeline
import com.velvet.app.playback.CandidateGeneratorType
import com.velvet.app.playback.CandidateSource
import com.velvet.app.playback.MusicCluster
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.QueueState
import com.velvet.app.playback.SeedProfile
import com.velvet.app.playback.SessionHistory
import com.velvet.app.playback.SongEra
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.SongMood
import com.velvet.app.playback.SongTempo
import com.velvet.app.playback.TasteEngine
import com.velvet.app.playback.TasteProfile
import com.velvet.app.playback.UserTasteProfile
import com.velvet.app.playback.Weights
import com.velvet.app.playback.canonicalKey
import com.velvet.app.playback.detectCluster
import com.velvet.app.playback.detectEra
import com.velvet.app.playback.detectMood
import com.velvet.app.playback.detectTempo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun testLanguageAndDevotionalAffinity_hanumanChalisaRejectsTamilFilmAndKeepsDevotional() = runBlocking {
        val seed = makeSong("seed_hc", "Shree Hanuman Chalisa", "Hariharan")
        val dev1 = makeSong("dev1", "Sankatmochan Hanuman Ashtak", "Hariharan - Topic", preferred = true)
        val dev2 = makeSong("dev2", "श्री हनुमान चालीसा", "Gulshan Kumar - Topic", preferred = true)
        val dev3 = makeSong("dev3", "Mahadev Shankar", "Gulshan Kumar - Topic", preferred = true)
        val tamil1 = makeSong("tam1", "Pala Pala", "Harris Jayaraj - Topic", preferred = true)
        val tamil2 = makeSong("tam2", "Saamikittay", "Himesh Reshammiya - Topic", preferred = true)
        val tamil3 = makeSong("tam3", "Chinna Chinna Aasai", "A. R. Rahman - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(tamil1, dev1, tamil2, dev2, dev3, tamil3) }),
            history = history,
            userTasteProvider = {
                com.velvet.app.playback.UserTasteProfile(
                    favoriteArtists = setOf("hariharan", "gulshan kumar"),
                    preferredLanguages = listOf("Hindi", "English"),
                )
            },
        )

        val next = pipeline.next(seed, n = 5)
        // Tamil film songs must be filtered out / suppressed from devotional Hindi seed
        assertTrue(next.none { it.id == "tam1" })
        assertTrue(next.none { it.id == "tam2" })
        assertTrue(next.none { it.id == "tam3" })
        assertEquals(3, next.size)
        assertTrue(next.all { it.id in setOf("dev1", "dev2", "dev3") })
    }

    @Test
    fun testUserTasteBinding_boostsFavoriteAndRecentArtists() = runBlocking {
        val seed = makeSong("seed_pop", "Pop Seed Song", "Seed Singer")
        val candUnknown = makeSong("c_unk", "Track Unknown", "Unknown Artist - Topic", preferred = true)
        val candFav = makeSong("c_fav", "Track Fav", "Loved Singer - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candUnknown, candFav) }),
            history = history,
            userTasteProvider = {
                com.velvet.app.playback.UserTasteProfile(
                    favoriteArtists = setOf("loved singer"),
                    preferredLanguages = listOf("Hindi", "English"),
                )
            },
        )

        val next = pipeline.next(seed, n = 2)
        assertEquals(2, next.size)
        // User's loved artist must rank first due to taste binding
        assertEquals("c_fav", next[0].id)
        assertEquals("c_unk", next[1].id)
    }

    @Test
    fun testDetectCluster_identifiesClustersAccurately() {
        assertEquals(
            com.velvet.app.playback.MusicCluster.DEVOTIONAL,
            com.velvet.app.playback.detectCluster(makeSong("1", "Shree Hanuman Chalisa", "Hariharan")),
        )
        assertEquals(
            com.velvet.app.playback.MusicCluster.DEVOTIONAL,
            com.velvet.app.playback.detectCluster(makeSong("2", "Shiv Tandav Stotram", "Shankar Mahadevan")),
        )
        assertEquals(
            com.velvet.app.playback.MusicCluster.PUNJABI_BHANGRA,
            com.velvet.app.playback.detectCluster(makeSong("3", "Winning Speech", "Karan Aujla")),
        )
        assertEquals(
            com.velvet.app.playback.MusicCluster.RETRO_CLASSIC,
            com.velvet.app.playback.detectCluster(makeSong("4", "Lag Ja Gale", "Lata Mangeshkar")),
        )
        assertEquals(
            com.velvet.app.playback.MusicCluster.BOLLYWOOD_MELODY,
            com.velvet.app.playback.detectCluster(makeSong("5", "Aankhon Ki Gustakhiyan", "Kumar Sanu")),
        )
        assertEquals(
            com.velvet.app.playback.MusicCluster.CARNATIC_SOUTH_FILM,
            com.velvet.app.playback.detectCluster(makeSong("6", "Pala Pala", "Harris Jayaraj")),
        )
    }

    @Test
    fun testDetectTempo_identifiesAccurately() {
        assertEquals(SongTempo.FAST, detectTempo(makeSong("t1", "Dance Party Dhamaka", "Singer - Topic")))
        assertEquals(SongTempo.FAST, detectTempo(makeSong("t2", "Dil Diyan Gallan (Remix)", "Atif Aslam - Topic")))
        assertEquals(SongTempo.FAST, detectTempo(makeSong("t3", "Winning Speech", "Karan Aujla")))
        assertEquals(SongTempo.SLOW, detectTempo(makeSong("t4", "Chithi Aayi Hai", "Pankaj Udhas - Topic")))
        assertEquals(SongTempo.SLOW, detectTempo(makeSong("t5", "Tum Hi Ho (Acoustic)", "Arijit Singh - Topic")))
        assertEquals(SongTempo.MEDIUM, detectTempo(makeSong("t6", "Kesariya", "Arijit Singh - Topic")))
    }

    @Test
    fun testDetectMood_identifiesAccurately() {
        assertEquals(SongMood.DEVOTIONAL_SPIRITUAL, detectMood(makeSong("m1", "Shree Hanuman Chalisa", "Hariharan")))
        assertEquals(SongMood.SAD_MELANCHOLIC, detectMood(makeSong("m2", "Dard Dilon Ke Kam Ho Jaate", "Himesh Reshammiya - Topic")))
        assertEquals(SongMood.PARTY_CELEBRATORY, detectMood(makeSong("m3", "Saturday Saturday Dance Party", "Badshah - Topic")))
        assertEquals(SongMood.ROMANTIC_LOVE, detectMood(makeSong("m4", "Tum Hi Ho Meri Aashiqui", "Arijit Singh - Topic")))
        assertEquals(SongMood.SUFI_MYSTICAL, detectMood(makeSong("m5", "Dama Dam Mast Qalandar", "Nusrat Fateh Ali Khan - Topic")))
        assertEquals(SongMood.NOSTALGIC_RETRO, detectMood(makeSong("m6", "Lag Ja Gale Classic", "Lata Mangeshkar - Topic")))
    }

    @Test
    fun testDetectEra_identifiesAccurately() {
        assertEquals(SongEra.VINTAGE_RETRO, detectEra(makeSong("e1", "Song 70s"), releaseYear = 1974))
        assertEquals(SongEra.EIGHTIES_NINETIES, detectEra(makeSong("e2", "Song 90s"), releaseYear = 1994))
        assertEquals(SongEra.TWO_THOUSANDS, detectEra(makeSong("e3", "Song 2000s"), releaseYear = 2004))
        assertEquals(SongEra.MODERN_CONTEMPORARY, detectEra(makeSong("e4", "Song 2020s"), releaseYear = 2023))

        assertEquals(SongEra.VINTAGE_RETRO, detectEra(makeSong("e5", "Vintage Song", "Lata Mangeshkar")))
        assertEquals(SongEra.EIGHTIES_NINETIES, detectEra(makeSong("e6", "90s Hit", "Kumar Sanu")))
        assertEquals(SongEra.TWO_THOUSANDS, detectEra(makeSong("e7", "2000s Melody", "KK")))
        assertEquals(SongEra.MODERN_CONTEMPORARY, detectEra(makeSong("e8", "Modern Indie", "Arijit Singh")))
    }

    @Test
    fun testScoring_priority1_genreDominatesOverMismatchedArtist() = runBlocking {
        val seed = makeSong("seed_dev", "Shree Hanuman Chalisa", "Hariharan")
        val candDevOtherArtist = makeSong("c_dev", "Sankatmochan Hanuman Ashtak", "Anup Jalota - Topic", preferred = true)
        val candPartySameArtist = makeSong("c_party", "Lungi Dance Party Remix", "Hariharan - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candPartySameArtist, candDevOtherArtist) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        // Devotional candidate must rank, while party candidate with same artist must be completely eliminated
        assertEquals(1, next.size)
        assertEquals("c_dev", next[0].id)
    }

    @Test
    fun testScoring_priority2_tempoMatchBreaksTies() = runBlocking {
        val seed = makeSong("seed_fast", "Saturday Dance Club Beat", "Dance DJ")
        val candFast = makeSong("c_fast", "Friday Night Party Dance", "Pop DJ - Topic", preferred = true)
        val candMedium = makeSong("c_med", "Everyday Pop Melody", "Pop DJ - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candMedium, candFast) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 2)
        assertEquals(2, next.size)
        // Fast candidate with matching tempo must rank first ahead of medium tempo candidate
        assertEquals("c_fast", next[0].id)
        assertEquals("c_med", next[1].id)
    }

    @Test
    fun testScoring_priority2_tempoClashSeverelyPenalized() = runBlocking {
        val seed = makeSong("seed_fast", "Saturday Dance Club Beat", "Dance DJ")
        val candNormal = makeSong("c_norm", "Everyday Pop Track", "Pop Singer - Topic", preferred = true)
        val candSlow = makeSong("c_slow", "Midnight Sad Ballad (Acoustic)", "Slow Singer - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candSlow, candNormal) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 2)
        // A slow sad acoustic ballad clashes with fast party dance seed and is eliminated
        assertEquals(1, next.size)
        assertEquals("c_norm", next[0].id)
        assertFalse(next.any { it.id == "c_slow" })
    }

    @Test
    fun testScoring_priority3_moodMatchingBoostsCompatible() = runBlocking {
        val seed = makeSong("seed_sad", "Dard Dilon Ke Tanhai", "Singer")
        val candSad = makeSong("c_sad", "Judai Aur Ansoo Sad Song", "Other Singer - Topic", preferred = true)
        val candGeneral = makeSong("c_gen", "Normal Day Track", "Other Singer - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candGeneral, candSad) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 2)
        assertEquals(2, next.size)
        // Sad mood matching track must rank first
        assertEquals("c_sad", next[0].id)
        assertEquals("c_gen", next[1].id)
    }

    @Test
    fun testScoring_priority4_pastListeningActivityBoostsClusterAndMood() = runBlocking {
        val seed = makeSong("seed_pop", "General Pop Track", "Singer")
        val candDev = makeSong("c_dev", "Shree Ram Bhajan", "Dev Singer - Topic", preferred = true)
        val candRetro = makeSong("c_retro", "Purane Geet Classic", "Retro Singer - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candRetro, candDev) }),
            history = history,
            userTasteProvider = {
                UserTasteProfile(
                    frequentClusters = mapOf(MusicCluster.DEVOTIONAL to 10),
                    frequentMoods = mapOf(SongMood.DEVOTIONAL_SPIRITUAL to 10),
                )
            },
        )

        val next = pipeline.next(seed, n = 2)
        assertEquals(2, next.size)
        // Candidate matching user's frequent past listening cluster & mood must rank first
        assertEquals("c_dev", next[0].id)
        assertEquals("c_retro", next[1].id)
    }

    @Test
    fun testScoring_priority5_sameEraBoostsWhenGenreMatches() = runBlocking {
        val seed = makeSong("seed_90s", "Aankhon Ki Gustakhiyan", "Kumar Sanu") // 90s Bollywood melody
        val cand90s = makeSong("c_90s", "Mera Dil Bhi Kitna Pagal Hai", "Alka Yagnik - Topic", preferred = true)
        val cand2020s = makeSong("c_2020", "Heeriye Modern Pop", "Jasleen Royal - Topic", preferred = true)

        val years = mapOf("seed_90s" to 1999, "c_90s" to 1991, "c_2020" to 2023)
        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(cand2020s, cand90s) }),
            history = history,
            releaseYearProvider = { years[it] },
        )

        val next = pipeline.next(seed, n = 2)
        assertEquals(2, next.size)
        // 90s candidate sharing same era and matching genre must rank first
        assertEquals("c_90s", next[0].id)
        assertEquals("c_2020", next[1].id)
    }

    @Test
    fun testScoring_priority6_artistBoostOnlyWhenGenreAndTempoCompatible() = runBlocking {
        val seed = makeSong("seed_sufi", "Khyal Ghazal", "Singer A")
        val candSufiSameArtist = makeSong("c_sufi", "Nazm Ghazal", "Singer A - Topic", preferred = true)
        val candFastSameArtist = makeSong("c_fast", "Club Dance Party Remix", "Singer A - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candFastSameArtist, candSufiSameArtist) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 2)
        // Sufi track with matching genre & tempo receives artist boost and ranks first;
        // Fast party track clashes in tempo and mood with the sufi seed
        assertEquals("c_sufi", next[0].id)
    }

    @Test
    fun testCandidatePool_multiSourceAggregatesAndTagsGenerators() = runBlocking {
        val seed = makeSong("seed1", "Tum Hi Ho", "Arijit Singh").copy(album = "Aashiqui 2")
        val candRadio = makeSong("c_rad", "Channa Mereya", "Arijit Singh", preferred = true)
        val candAlbum = makeSong("c_alb", "Meri Aashiqui", "Arijit Singh", preferred = true)
        val candArtist = makeSong("c_art", "Kesariya", "Arijit Singh", preferred = true)
        val candLib = makeSong("c_lib", "Ae Dil Hai Mushkil", "Arijit Singh", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candRadio) }),
            history = history,
            albumTracksProvider = { listOf(candAlbum) },
            artistTracksProvider = { listOf(candArtist) },
            libraryTracksProvider = { listOf(candLib) },
        )

        val pool = pipeline.fetchCandidatePool(SeedProfile(current = seed))
        assertTrue(pool.any { it.song.id == "c_rad" && it.sources.contains(CandidateGeneratorType.G1_RADIO) })
        assertTrue(pool.any { it.song.id == "c_alb" && it.sources.contains(CandidateGeneratorType.G2_SAME_ALBUM_OR_MOVIE) })
        assertTrue(pool.any { it.song.id == "c_art" && it.sources.contains(CandidateGeneratorType.G3_PRIMARY_ARTIST) })
        assertTrue(pool.any { it.song.id == "c_lib" && it.sources.contains(CandidateGeneratorType.G6_OWNER_LIBRARY) })
    }

    @Test
    fun testCandidatePool_timeboxTimeoutDoesNotFailPipeline() = runBlocking {
        val seed = makeSong("seed_slow", "Title A", "Artist A")
        val fastSong = makeSong("fast_1", "Fast Song", "Artist A", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource {
                delay(3000) // Exceeds 200ms timeout
                listOf(makeSong("slow_1", "Slow Song", "Artist A"))
            }),
            history = history,
            artistTracksProvider = {
                listOf(fastSong)
            },
            timeboxMs = 200L,
        )

        val pool = pipeline.fetchCandidatePool(SeedProfile(current = seed))
        assertTrue(pool.any { it.song.id == "fast_1" })
        assertFalse(pool.any { it.song.id == "slow_1" })
    }

    @Test
    fun testSeedProfile_weightingDecaysOlderPlayedSongs() {
        val current = makeSong("s0", "Current Song", "Artist Main")
        val recent1 = makeSong("s1", "Recent 1", "Artist One")
        val recent2 = makeSong("s2", "Recent 2", "Artist Two")
        val recent3 = makeSong("s3", "Recent 3", "Artist Three")

        val profile = SeedProfile(
            current = current,
            recentSongs = listOf(recent1, recent2, recent3),
        )

        val weights = profile.artistWeights()
        val wMain = weights["artist main"] ?: 0.0
        val wOne = weights["artist one"] ?: 0.0
        val wTwo = weights["artist two"] ?: 0.0
        val wThree = weights["artist three"] ?: 0.0

        assertTrue("Current artist must have weight >= 1.0", wMain >= 1.0)
        assertTrue("Recent1 must have higher weight than Recent2", wOne > wTwo)
        assertTrue("Recent2 must have higher weight than Recent3", wTwo > wThree)
    }

    @Test
    fun testCandidatePool_cachesPerSeedForSession() = runBlocking {
        val seed = makeSong("seed_cache", "Tum Hi Ho", "Arijit Singh")
        var callCount = 0
        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource {
                callCount++
                listOf(makeSong("c_cached", "Channa Mereya", "Arijit Singh", preferred = true))
            }),
            history = history,
        )

        val pool1 = pipeline.fetchCandidatePool(SeedProfile(current = seed))
        val pool2 = pipeline.fetchCandidatePool(SeedProfile(current = seed))
        assertEquals(1, callCount)
        assertEquals(pool1.size, pool2.size)
    }

    @Test
    fun testScoring_multiTermFormula_movieAndArtistAndTaste() = runBlocking {
        val seed = makeSong("seed_hddcs", "Aankhon Ki Gustakhiyan (From \"Hum Dil De Chuke Sanam\")", "Kumar Sanu")
        val candSameMovie = makeSong("c_movie", "Chand Chupa Badal Mein (From \"Hum Dil De Chuke Sanam\")", "Udit Narayan - Topic", preferred = true)
        val candOther = makeSong("c_other", "Tujhe Dekha To", "Kumar Sanu - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candOther, candSameMovie) }),
            history = history,
            tasteProfileProvider = {
                TasteProfile(
                    movieAffinity = mapOf("hum dil de chuke sanam" to 0.8),
                    artistAffinity = mapOf("udit narayan" to 0.7),
                )
            },
        )

        val breakdowns = mutableMapOf<String, Map<String, Double>>()
        val scored = pipeline.scoreCandidates(
            candidates = listOf(candOther, candSameMovie),
            seedProfile = SeedProfile(current = seed),
            tasteProfile = TasteProfile(
                movieAffinity = mapOf("hum dil de chuke sanam" to 0.8),
                artistAffinity = mapOf("udit narayan" to 0.7),
            ),
            scoreBreakdownOut = breakdowns,
        )

        assertEquals("c_movie", scored.first().id)
        val movieBreakdown = breakdowns["c_movie"]!!
        assertEquals(Weights.W2_SAME_MOVIE_OR_ALBUM, movieBreakdown["w2_sameMovieOrAlbum"] ?: 0.0, 0.01)
        assertTrue((movieBreakdown["w6_tasteScore"] ?: 0.0) > 0.0)
    }

    @Test
    fun testScoring_libraryBoostCappedAt15() {
        val seed = makeSong("s1", "Song A", "Artist 1")
        val cand = makeSong("c1", "Song B", "Artist 2", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(cand) }),
            history = history,
        )

        val breakdowns = mutableMapOf<String, Map<String, Double>>()
        pipeline.scoreCandidates(
            candidates = listOf(cand),
            seedProfile = SeedProfile(current = seed),
            userTaste = UserTasteProfile(
                likedTrackKeys = setOf(canonicalKey(cand.title, cand.artist)),
                favoriteArtists = setOf("artist 2"),
            ),
            scoreBreakdownOut = breakdowns,
        )

        val candBreakdown = breakdowns["c1"]!!
        val libraryBoost = candBreakdown["w7_libraryBoost"] ?: 0.0
        assertTrue("Library boost must be <= 15.0", libraryBoost <= 15.0)
        assertEquals(15.0, libraryBoost, 0.01)
    }

    @Test
    fun testDiversity_movieSpacingSeparatesSameMovieSoundtrackTracks() {
        val cand1 = makeSong("c1", "Tum Hi Ho (From \"Aashiqui 2\")", "Arijit Singh")
        val cand2 = makeSong("c2", "Sunn Raha Hai (From \"Aashiqui 2\")", "Ankit Tiwari")
        val cand3 = makeSong("c3", "Kalank Title Track", "Kumar Sanu")

        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)
        val spaced = pipeline.spaceCandidates(
            candidates = listOf(cand1, cand2, cand3),
            lastQueuedArtist = null,
            lastQueuedMovie = null,
        )

        // cand1 and cand2 share movie "Aashiqui 2". Spacing must not put cand2 immediately after cand1
        // since cand3 has different movie and different artist!
        assertEquals("c1", spaced[0].id)
        assertEquals("c3", spaced[1].id)
        assertEquals("c2", spaced[2].id)
    }

    @Test
    fun testDiversity_noveltySlotSelectedEveryFiveTracks() {
        val knownTrack = makeSong("k1", "Known 1", "Artist A")
        val knownTrack2 = makeSong("k2", "Known 2", "Artist B")
        val knownTrack3 = makeSong("k3", "Known 3", "Artist C")
        val knownTrack4 = makeSong("k4", "Known 4", "Artist D")
        val knownTrack5 = makeSong("k5", "Known 5", "Artist E")
        val novelTrack = makeSong("novel1", "Novel Discovery", "Artist Novel")

        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)
        val candidates = listOf(knownTrack, knownTrack2, knownTrack3, knownTrack4, knownTrack5, novelTrack)
        val playedKeys = setOf("k1", "k2", "k3", "k4", "k5")

        val spaced = pipeline.spaceCandidates(
            candidates = candidates,
            lastQueuedArtist = null,
            playedOrKnownKeys = playedKeys,
            epsilon = 0.0, // pure exploitation
        )

        // Novelty slot is index 4 (5th track)
        assertEquals("novel1", spaced[4].id)
    }

    @Test
    fun testDiversity_deterministicWithFixedRandomSeed() {
        val songs = (1..10).map { makeSong("s$it", "Song $it", "Artist ${it % 3}") }
        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)

        val run1 = pipeline.spaceCandidates(songs, lastQueuedArtist = null, randomSeed = 12345L)
        val run2 = pipeline.spaceCandidates(songs, lastQueuedArtist = null, randomSeed = 12345L)

        assertEquals(run1.map { it.id }, run2.map { it.id })
    }

    @Test
    fun testDiversity_strictArtistSpacing_prefersAlternativeArtistsOverSameArtist() {
        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)
        val pritam1 = makeSong("p1", "Subha Hone Na De", "Pritam")
        val pritam2 = makeSong("p2", "Tu Hi Meri Shab Hai", "Pritam")
        val arijit = makeSong("a1", "Tum Hi Ho", "Arijit Singh")
        val atif = makeSong("at1", "Tera Hone Laga Hoon", "Atif Aslam")
        val kk = makeSong("k1", "Zara Sa", "KK")
        val mohit = makeSong("m1", "Tum Se Hi", "Mohit Chauhan")
        val sonu = makeSong("s1", "Kal Ho Naa Ho", "Sonu Nigam")
        val sunidhi = makeSong("su1", "Kamli", "Sunidhi Chauhan")
        val shreya = makeSong("sh1", "Teri Ore", "Shreya Ghoshal")

        val candidates = listOf(pritam1, pritam2, arijit, atif, kk, mohit, sonu, sunidhi, shreya)

        val spaced = pipeline.spaceCandidates(
            candidates = candidates,
            lastQueuedArtist = "Pritam",
            noveltyInterval = 999, // disable novelty slot for pure spacing assertion
            epsilon = 0.0,
        )

        // Since lastQueuedArtist was "Pritam", first spaced track must NOT be Pritam!
        assertNotEquals("Pritam", spaced[0].artist)

        // Verify that when alternatives exist, no adjacent tracks share the same artist
        for (i in 0 until spaced.size - 1) {
            assertNotEquals(
                "Adjacent tracks must have different primary artists",
                spaced[i].artist,
                spaced[i + 1].artist,
            )
        }

        // Verify that Pritam tracks are properly spaced (at least 4 intervening tracks from seed)
        val pritamIndices = spaced.indices.filter { spaced[it].artist.equals("Pritam", ignoreCase = true) }
        assertTrue(pritamIndices.isNotEmpty())
        assertTrue("First Pritam appearance should be after at least 4 intervening tracks, actual: ${pritamIndices[0]}", pritamIndices[0] >= 4)
    }

    @Test
    fun testScoring_musicFirst_noPositiveBonusForArtistByDefault() {
        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)
        val seed = makeSong("seed", "Seed Song", "Kumar Sanu")
        val candidateSameArtist = makeSong("cand1", "Cand Song 1", "Kumar Sanu")
        val candidateDiffArtist = makeSong("cand2", "Cand Song 2", "Udit Narayan")

        val seedProfile = SeedProfile(current = seed)
        val breakdowns = mutableMapOf<String, Map<String, Double>>()

        pipeline.scoreCandidates(
            candidates = listOf(candidateSameArtist, candidateDiffArtist),
            seedProfile = seedProfile,
            scoreBreakdownOut = breakdowns,
        )

        val w3Same = breakdowns["cand1"]?.get("w3_artistSimilarity") ?: 0.0
        val w3Diff = breakdowns["cand2"]?.get("w3_artistSimilarity") ?: 0.0

        // In Autoplay V3 music-first spec: Artist receives 0.0 bonus in default similarity score
        assertEquals(0.0, w3Same, 0.001)
        assertEquals(0.0, w3Diff, 0.001)
    }

    @Test
    fun testScoring_artistRadio_prioritizesSelectedArtist() {
        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)
        val seed = makeSong("seed", "Seed Song", "Kumar Sanu")
        val candidateSameArtist = makeSong("cand1", "Cand Song 1", "Kumar Sanu")
        val candidateDiffArtist = makeSong("cand2", "Cand Song 2", "Udit Narayan")

        val seedProfile = SeedProfile(current = seed, isArtistRadio = true)
        val breakdowns = mutableMapOf<String, Map<String, Double>>()

        pipeline.scoreCandidates(
            candidates = listOf(candidateSameArtist, candidateDiffArtist),
            seedProfile = seedProfile,
            scoreBreakdownOut = breakdowns,
        )

        val w3Same = breakdowns["cand1"]?.get("w3_artistSimilarity") ?: 0.0
        val w3Diff = breakdowns["cand2"]?.get("w3_artistSimilarity") ?: 0.0

        // In Artist Radio mode: matching artist receives highest preference bonus (+100.0)
        assertEquals(100.0, w3Same, 0.001)
        assertEquals(0.0, w3Diff, 0.001)
    }

    @Test
    fun testScoring_musicFirst_differentArtistMatchingMovieOutranksSameArtistMismatched() {
        val pipeline = AutoplayPipeline(sources = emptyList(), history = history)
        val seed = makeSong("s1", "Aankhon Ki Gustakhiyan (From \"Hum Dil De Chuke Sanam\")", "Kumar Sanu")
        val candDiffArtistMatchMovie = makeSong("c1", "Chand Chupa Badal Mein (From \"Hum Dil De Chuke Sanam\")", "Udit Narayan")
        val candSameArtistDiffMovie = makeSong("c2", "Tujhe Dekha Toh (From \"DDLJ\")", "Kumar Sanu")

        val scored = pipeline.scoreCandidates(
            candidates = listOf(candSameArtistDiffMovie, candDiffArtistMatchMovie),
            seedProfile = SeedProfile(current = seed, isArtistRadio = false),
        )

        assertEquals("Different artist with matching movie/soundtrack must outrank same artist in music-first autoplay",
            "c1", scored.first().id)
    }

    @Test
    fun testSongRadio_zeroArtistBonusAndGenreMatchOutranks() {
        val pipeline = AutoplayPipeline(emptyList(), SessionHistory(20))
        val seed = makeSong("s_bhajan", "He Dukhbhanjan Marutnandan - Lofi Hanuman Bhajan", "Sanatan Bharat")
        val candDiffArtistBhajan = makeSong("c_wadkar", "Dharti Gagan Mein Hoti Hai", "Suresh Wadkar")
        val candSameArtistPop = makeSong("c_pop", "Party Night EDM Song", "Sanatan Bharat")

        val breakdowns = mutableMapOf<String, Map<String, Double>>()
        val scored = pipeline.scoreCandidates(
            candidates = listOf(candSameArtistPop, candDiffArtistBhajan),
            seedProfile = SeedProfile(current = seed, isArtistRadio = false),
            scoreBreakdownOut = breakdowns,
        )

        val sameArtistBreakdown = breakdowns["c_pop"] ?: emptyMap()
        assertEquals("In Song Radio (isArtistRadio=false), same artist must receive 0.0 similarity bonus",
            0.0, sameArtistBreakdown["w3_artistSimilarity"] ?: -1.0, 0.001)

        assertEquals("Compatible genre/bhajan candidate from different artist must outrank genre mismatch by same artist",
            "c_wadkar", scored.first().id)

        // Verify clearCandidatePoolCache executes without exception
        pipeline.clearCandidatePoolCache("s_bhajan")
        pipeline.clearCandidatePoolCache(null)
    }

    @Test
    fun testAutoplay_hierarchy_genreAboveMoodAboveTempo() {
        val pipeline = AutoplayPipeline(emptyList(), SessionHistory(20))
        val seed = makeSong("seed1", "Aankhon Ki Gustakhiyan", "Kumar Sanu")

        // Matches Genre (Bollywood 90s Melody: Udit Narayan)
        val candGenreMatch = makeSong("c_genre", "Chand Chupa Badal Mein", "Udit Narayan")
        // Clashing Genre (Punjabi Bhangra: Diljit Dosanjh)
        val candMoodOnly = makeSong("c_mood", "Do You Know Romantic Love", "Diljit Dosanjh")

        val breakdowns = mutableMapOf<String, Map<String, Double>>()
        val scored = pipeline.scoreCandidates(
            candidates = listOf(candMoodOnly, candGenreMatch),
            seedProfile = SeedProfile(current = seed, isArtistRadio = false),
            scoreBreakdownOut = breakdowns,
        )

        val genreScore = breakdowns["c_genre"]?.get("totalScore") ?: 0.0
        val moodScore = breakdowns["c_mood"]?.get("totalScore") ?: 0.0
        assertTrue("Genre match must outrank mood-only match when genre clashes", genreScore > moodScore)
        assertEquals("c_genre", scored.first().id)
    }

    // ── Offline Evaluation Harness (R5) ──────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixtures(): List<EvalFixture> {
        val stream = javaClass.classLoader?.getResourceAsStream("autoplay_eval_fixtures.json")
            ?: javaClass.getResourceAsStream("/autoplay_eval_fixtures.json")
            ?: error("autoplay_eval_fixtures.json not found in test resources")
        val content = stream.bufferedReader().use { it.readText() }
        return json.decodeFromString<EvalFixturesWrapper>(content).fixtures
    }

    @Test
    fun testOfflineEval_fixturesFileLoadableAndContainsFiveSeeds() {
        val fixtures = loadFixtures()
        assertEquals("Must contain 5 recorded seed fixtures", 5, fixtures.size)
    }

    @Test
    fun testOfflineEval_zeroDuplicateGroupsInFirst30Picks() = runBlocking {
        val fixtures = loadFixtures()
        for (fixture in fixtures) {
            val pipeline = AutoplayPipeline(
                sources = listOf(CandidateSource { fixture.candidates }),
                history = SessionHistory(),
                sessionSeed = 100L,
            )

            val picks = pipeline.next(fixture.seed, n = 30)

            // Assert no two picks in the result are duplicates of each other
            for (i in picks.indices) {
                for (j in i + 1 until picks.size) {
                    val sA = picks[i]
                    val sB = picks[j]
                    val isDup = SongIdentity.isStrictMatch(sA, sB)
                    assertFalse(
                        "Duplicate found in picks for seed '${fixture.seed.title}': '${sA.title}' vs '${sB.title}'",
                        isDup
                    )
                }
            }
        }
    }

    @Test
    fun testOfflineEval_noCandidateStrictEqualToQueueOrHistory() = runBlocking {
        val fixtures = loadFixtures()
        val fixture = fixtures.first()

        val historyObj = SessionHistory()
        val historySong = fixture.candidates[2]
        historyObj.record(historySong)

        val queueSong = fixture.candidates[3]
        val queueSnapshot = listOf(queueSong)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { fixture.candidates }),
            history = historyObj,
            sessionSeed = 100L,
        )

        val picks = pipeline.next(fixture.seed, queueSnapshot, n = 10)

        for (pick in picks) {
            assertFalse(
                "Pick '${pick.title}' must not match history song '${historySong.title}'",
                SongIdentity.isStrictMatch(pick, historySong)
            )
            assertFalse(
                "Pick '${pick.title}' must not match queued song '${queueSong.title}'",
                SongIdentity.isStrictMatch(pick, queueSong)
            )
        }
    }

    @Test
    fun testOfflineEval_seedExceptionAllowsRemixResultsOnlyForRemixSeed() = runBlocking {
        val fixtures = loadFixtures()
        val normalSeedFixture = fixtures.find { it.seed.id == "seed_1" }!!
        val remixSeedFixture = fixtures.find { it.seed.id == "seed_4" }!!

        // Normal seed: remix candidate must NOT survive
        val normalPipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { normalSeedFixture.candidates }),
            history = SessionHistory(),
        )
        val normalPicks = normalPipeline.next(normalSeedFixture.seed, n = 10)
        assertFalse(
            "Normal seed must not produce DJ Club Remix results",
            normalPicks.any { it.title.contains("remix", ignoreCase = true) }
        )

        // Remix seed: remix-friendly candidate CAN survive
        val remixPipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { remixSeedFixture.candidates }),
            history = SessionHistory(),
        )
        val remixPicks = remixPipeline.next(remixSeedFixture.seed, n = 10)
        assertTrue(
            "Remix seed must allow remix candidates (seed exception)",
            remixPicks.any { it.title.contains("remix", ignoreCase = true) || it.title.contains("club mix", ignoreCase = true) }
        )
    }

    @Test
    fun testOfflineEval_artistAndMovieSpacingAndNoveltySlot() = runBlocking {
        val fixtures = loadFixtures()
        val fixture = fixtures.find { it.seed.id == "seed_1" }!! // Hum Dil De Chuke Sanam

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { fixture.candidates }),
            history = SessionHistory(),
            sessionSeed = 42L,
        )

        val picks = pipeline.next(fixture.seed, n = 8)
        assertTrue("Must return at least 5 picks", picks.size >= 5)

        // Assert no two adjacent picks have the exact same primary artist (unless relaxed)
        for (i in 0 until picks.size - 1) {
            val a1 = com.velvet.app.playback.normalizePrimaryArtist(picks[i].artist)
            val a2 = com.velvet.app.playback.normalizePrimaryArtist(picks[i + 1].artist)
            if (a1.isNotBlank() && a2.isNotBlank() && a1 == a2) {
                // If adjacent share artist, check that all remaining candidates share that artist
                val remaining = picks.drop(i)
                val allShare = remaining.all { com.velvet.app.playback.normalizePrimaryArtist(it.artist) == a1 }
                assertTrue("Artist spacing violation between '${picks[i].title}' and '${picks[i+1].title}'", allShare)
            }
        }
    }

    @Test
    fun testOfflineEval_determinismWithFixedRandomSeed() = runBlocking {
        val fixtures = loadFixtures()
        val fixture = fixtures.first()

        val pipeline1 = AutoplayPipeline(
            sources = listOf(CandidateSource { fixture.candidates }),
            history = SessionHistory(),
            sessionSeed = 999L,
        )
        val pipeline2 = AutoplayPipeline(
            sources = listOf(CandidateSource { fixture.candidates }),
            history = SessionHistory(),
            sessionSeed = 999L,
        )

        val run1 = pipeline1.next(fixture.seed, n = 5)
        val run2 = pipeline2.next(fixture.seed, n = 5)

        assertEquals("Fixed random seed must yield deterministic results", run1.map { it.id }, run2.map { it.id })
    }

    @Test
    fun testOfflineEval_tasteLearningRanksArtistAAboveArtistB() = runBlocking {
        val candA = makeSong("cand_A", "Song of Artist A", "Artist A - Topic", preferred = true)
        val candB = makeSong("cand_B", "Song of Artist B", "Artist B - Topic", preferred = true)
        val seed = makeSong("seed_general", "General Song", "Singer Neutral")

        val now = System.currentTimeMillis()
        val events = listOf(
            ListenEvent(songGroupId = "gA", videoId = "vA", artistSet = listOf("Artist A"), startedAt = now - 5000, endReason = ListenEndReason.COMPLETED),
            ListenEvent(songGroupId = "gA2", videoId = "vA2", artistSet = listOf("Artist A"), startedAt = now - 3000, endReason = ListenEndReason.COMPLETED),
            ListenEvent(songGroupId = "gB", videoId = "vB", artistSet = listOf("Artist B"), startedAt = now - 1000, endReason = ListenEndReason.SKIP_EARLY)
        )
        val learnedProfile = TasteEngine.computeProfile(events, nowMillis = now)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candB, candA) }), // candB first in source
            history = SessionHistory(),
            tasteProfileProvider = { learnedProfile },
        )

        val picks = pipeline.next(seed, n = 2)
        assertEquals(2, picks.size)
        assertEquals("Artist A (completed) must rank above Artist B (skipped early)", "cand_A", picks.first().id)
    }

    @Test
    fun testBhaktiAutoplay_hardRejectsSecularSongsBySameArtist() = runBlocking {
        val seed = makeSong("seed_hc", "Shree Hanuman Chalisa", "Hariharan")
        val dev1 = makeSong("dev1", "Sankat Mochan Hanuman Ashtak", "Hariharan - Topic", preferred = true)
        val dev2 = makeSong("dev2", "Bajrang Baan", "Hariharan - Topic", preferred = true)
        val devOther = makeSong("dev3", "Jai Ganesh Deva", "Anuradha Paudwal - Topic", preferred = true)
        val secular1 = makeSong("sec1", "Tu Hi Re", "Hariharan - Topic", preferred = true)
        val secular2 = makeSong("sec2", "Lungi Dance Party Remix", "Hariharan - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(secular1, dev1, secular2, dev2, devOther) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(3, next.size)
        assertTrue(next.none { it.id == "sec1" })
        assertTrue(next.none { it.id == "sec2" })
        assertTrue(next.all { it.id in setOf("dev1", "dev2", "dev3") })
    }

    @Test
    fun testBhaktiAutoplay_hardRejectsSouthIndianAndEnglishPop() = runBlocking {
        val seed = makeSong("seed_hc", "Shree Hanuman Chalisa", "Hariharan")
        val devHindi = makeSong("dev_h", "Shiv Tandav Stotram", "Shankar Mahadevan - Topic", preferred = true)
        val southIndian = makeSong("south_1", "Pala Pala", "Harris Jayaraj - Topic", preferred = true)
        val englishPop = makeSong("eng_1", "Shape of You Party Pop", "Ed Sheeran - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(southIndian, devHindi, englishPop) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        assertEquals(1, next.size)
        assertEquals("dev_h", next[0].id)
    }

    @Test
    fun testBhaktiAutoplay_negativeScoreFallback_returnsEmptyListSafely() = runBlocking {
        val seed = makeSong("seed_hc", "Shree Hanuman Chalisa", "Hariharan")
        // All candidates are secular songs that will be hard rejected or score negative
        val secular1 = makeSong("sec1", "Club Dance Party Anthem", "Badshah - Topic", preferred = true)
        val secular2 = makeSong("sec2", "Saturday Saturday Night Club", "DJ Party - Topic", preferred = true)

        val pipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(secular1, secular2) }),
            history = history,
        )

        val next = pipeline.next(seed, n = 5)
        // Must return empty list safely instead of force-returning negative-score or mismatched tracks
        assertEquals(0, next.size)
    }

    @Test
    fun testNormalAutoplay_bollywoodAndPunjabiNotOverFiltered() = runBlocking {
        val seedBollywood = makeSong("seed_bolly", "Aankhon Ki Gustakhiyan", "Kumar Sanu")
        val candBolly1 = makeSong("b1", "Chand Chupa Badal Mein", "Udit Narayan - Topic", preferred = true)
        val candBolly2 = makeSong("b2", "Dheere Dheere Se", "Kumar Sanu - Topic", preferred = true)

        val pipelineBolly = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candBolly1, candBolly2) }),
            history = history,
        )
        val picksBolly = pipelineBolly.next(seedBollywood, n = 5)
        assertEquals(2, picksBolly.size)

        val seedPunjabi = makeSong("seed_punjabi", "Brown Munde", "AP Dhillon")
        val candPunjabi1 = makeSong("p1", "Excuses", "AP Dhillon - Topic", preferred = true)
        val candPunjabi2 = makeSong("p2", "Insane", "Shinda Kahlon - Topic", preferred = true)

        val pipelinePunjabi = AutoplayPipeline(
            sources = listOf(CandidateSource { listOf(candPunjabi1, candPunjabi2) }),
            history = history,
        )
        val picksPunjabi = pipelinePunjabi.next(seedPunjabi, n = 5)
        assertEquals(2, picksPunjabi.size)
    }
}

@Serializable
data class EvalFixture(
    val seed: Song,
    val candidates: List<Song>,
)

@Serializable
data class EvalFixturesWrapper(
    val fixtures: List<EvalFixture>,
)
