package com.velvet.app.unit

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.recommendation.candidates.ArtistCatalogueCandidateSource
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.home.HomeRecommendationComposer
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.modes.RadioSessionManager
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8ModesAndHomeTest {

    private fun topicSong(id: String, title: String, artist: String, durationSec: Long = 240L, album: String? = null) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = durationSec,
        album = album,
        isTopicRelease = true,
        rawUploaderName = "$artist - Topic",
        preferredMusicSource = true,
    )

    private fun buildEngine(
        catalog: List<Song>,
        artistCatalog: List<Song> = emptyList(),
        clock: () -> Long = { 30_000_000L },
    ): DefaultRecommendationEngine {
        val poolBuilder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = { catalog }, clock = clock),
                ArtistCatalogueCandidateSource(artistTracksProvider = { artistCatalog.ifEmpty { catalog } }, clock = clock),
                GenreLanguageNeighbourSource(querySearchProvider = { catalog }, clock = clock),
            ),
            clock = clock,
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { LibraryData() },
            clock = clock,
        )
        return DefaultRecommendationEngine(
            candidatePoolBuilder = poolBuilder,
            userProfileRepository = profileRepo,
            clock = clock,
        )
    }

    @Test
    fun radioSessionEvolvesWithCompletedTracks45252010AndIgnoresEarlySkips() {
        var now = 10_000L
        val mgr = RadioSessionManager { ++now }
        val anchor = topicSong("a0", "Tum Hi Ho", "Arijit Singh")
        val pos1 = topicSong("p1", "Jeena Jeena", "Atif Aslam")
        val pos2 = topicSong("p2", "Agar Tum Saath Ho", "Alka Yagnik")
        val skipOutlier = topicSong("skip_party", "DJ Club Party", "Badshah")

        mgr.startSession(anchor, RecommendationMode.SONG_RADIO, sessionId = "rad-1")
        mgr.onTrackCompletedOrLiked(pos1)
        mgr.onTrackSkippedEarly(skipOutlier)
        mgr.onTrackCompletedOrLiked(pos2)
        // Late attempt to re-add skipped outlier as positive must stay ignored
        mgr.onTrackCompletedOrLiked(skipOutlier)

        val seeds = mgr.evolvedSeedSongs(anchor)
        assertEquals(listOf("a0", "p2", "p1"), seeds.map { it.id })

        val weights = mgr.evolvedSeedWeights(anchor)
        assertTrue(weights["a0"]!! > weights["p2"]!!)
        assertTrue(weights["p2"]!! > weights["p1"]!!)
        assertFalse(skipOutlier.id in weights)
    }

    @Test
    fun artistRadioPrioritizesSelectedArtistWhileSimilarAndNextStayMusicFirst() = runBlocking {
        val seed = topicSong("seed_arijit", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        val sameArtistUnrelated = topicSong("ari_party", "Nashe Si Chadh Gayi Party Dance", "Arijit Singh")
        val diffArtistMatch = topicSong("atif_match", "Jeena Jeena Ishq Romantic", "Atif Aslam", album = "Aashiqui 2")
        val mixedPool = listOf(sameArtistUnrelated, diffArtistMatch)

        val engine = buildEngine(mixedPool, artistCatalog = listOf(sameArtistUnrelated))

        val similarRes = engine.nextSimilar(seed, mode = RecommendationMode.SIMILAR_SONGS, limit = 5)
        assertEquals(diffArtistMatch.id, similarRes.candidates.first().song.id)

        val nextRes = engine.nextSimilar(seed, mode = RecommendationMode.NEXT_SONG, limit = 5)
        assertEquals(diffArtistMatch.id, nextRes.candidates.first().song.id)

        val artistRadioRes = engine.nextSimilar(
            seed,
            mode = RecommendationMode.ARTIST_RADIO,
            selectedArtistId = "Arijit Singh",
            limit = 5,
        )
        assertEquals(sameArtistUnrelated.id, artistRadioRes.candidates.first().song.id)
    }

    @Test
    fun homeComposerStrictlyFiltersOmvsAndUnverifiedAcrossAllHomeShelves() = runBlocking {
        val recent = listOf(
            topicSong("r1", "Kesariya", "Arijit Singh"),
            topicSong("r2", "Kun Faya Kun", "Mohit Chauhan"),
            Song("r_bad_omv", "Channa Mereya (Official Music Video)", "Arijit Singh", 280L, isUploaderVerified = true, rawUploaderName = "Sony Music India"),
        )
        val rawPool = listOf(
            topicSong("good_1", "Agar Tum Saath Ho", "Alka Yagnik"),
            topicSong("good_2", "Khwaja Mere Khwaja", "A. R. Rahman"),
            topicSong("good_3", "Pehla Nasha", "Udit Narayan"),
            Song("bad_omv", "Shayad (Official Video)", "T-Series", 250L, isUploaderVerified = true, rawUploaderName = "T-Series"),
            Song("bad_lyric", "Apna Bana Le (Lyrical Video)", "Arijit Singh", 240L, isUploaderVerified = true, rawUploaderName = "Zee Music Company"),
            Song("bad_live", "Tum Hi Ho (Live Concert)", "Arijit Singh", 260L, isTopicRelease = true, rawUploaderName = "Arijit Singh - Topic"),
            Song("bad_unv", "Raabta", "T-Series", 245L, isUploaderVerified = true, rawUploaderName = "T-Series"),
        )

        val engine = buildEngine(rawPool)
        val composer = HomeRecommendationComposer(engine = engine)

        val trending = composer.rankAndFilterTrending(rawPool, limit = 10)
        assertEquals(setOf("good_1", "good_2", "good_3"), trending.map { it.id }.toSet())

        val quickPicks = composer.composeQuickPicks(recent = recent, limit = 10)
        assertTrue(quickPicks.isNotEmpty())
        assertTrue(quickPicks.all { it.id.startsWith("good_") })

        val speedDial = composer.composeSpeedDial(recent = recent, liked = emptyList(), quickPicks = quickPicks, trending = trending)
        assertFalse(speedDial.any { it.id.startsWith("bad_") || it.id == "r_bad_omv" })
        assertTrue(speedDial.all { it.id.startsWith("r") || it.id.startsWith("good_") })

        val mixes = composer.composeMadeForYouMixSeeds(recent = recent, liked = emptyList())
        assertEquals(setOf("r1", "r2"), mixes.map { it.id }.toSet())

        val personalizedMix = composer.composePersonalizedMix(recent = recent, liked = emptyList(), fallbackTrending = trending, limit = 10)
        assertTrue(personalizedMix.songs.isNotEmpty())
        assertTrue(personalizedMix.songs.none { it.id.startsWith("bad_") || it.id == "r_bad_omv" })
    }

    @Test
    fun homeComposerColdStartSafeOnEmptyHistoryAndReturnsEmptyGracefullyWithoutCrashing() = runBlocking {
        val onlyBadTrending = listOf(
            Song("bad_1", "Hit Track (Official Music Video)", "Label", 230L, isUploaderVerified = true, rawUploaderName = "Label"),
            Song("bad_2", "Ambiguous Channel Song", "Sony Music", 230L, isUploaderVerified = true, rawUploaderName = "Sony Music"),
        )
        val engine = buildEngine(onlyBadTrending)
        val composer = HomeRecommendationComposer(engine = engine)

        assertTrue(composer.rankAndFilterTrending(onlyBadTrending).isEmpty())
        assertTrue(composer.composeQuickPicks(recent = emptyList()).isEmpty())
        assertTrue(composer.composeSpeedDial(emptyList(), emptyList(), emptyList(), composer.rankAndFilterTrending(onlyBadTrending)).isEmpty())
        val mix = composer.composePersonalizedMix(recent = emptyList(), liked = emptyList(), fallbackTrending = onlyBadTrending)
        assertTrue(mix.songs.isEmpty())
    }

    @Test
    fun speedDialFillsAll9CellsWhenVerifiedSongsExistAndAdaptsToListeningHabits() = runBlocking {
        val recentHistory = listOf(
            topicSong("r_old", "Purani Yaad", "Kishore Kumar"),
            topicSong("r_fav", "Tum Hi Ho", "Arijit Singh"),
            topicSong("r_fav", "Tum Hi Ho", "Arijit Singh"),
        )
        val liked = listOf(topicSong("lk_1", "Agar Tum Saath Ho", "Alka Yagnik"))
        val trendingCatalog = (1..10).map { i ->
            topicSong("tr_$i", "Studio Track $i", "Artist $i")
        }

        val composer = HomeRecommendationComposer()
        val dial = composer.composeSpeedDial(
            recent = recentHistory,
            liked = liked,
            quickPicks = emptyList(),
            trending = trendingCatalog,
            limit = 9,
        )

        assertEquals("Speed Dial must fill all 9 cells cleanly", 9, dial.size)
        assertEquals(9, dial.map { it.id }.distinct().size)
        // Highest habit engagement ("r_fav" repeated + "lk_1" liked) appear before cold tracks
        val dialIds = dial.map { it.id }.toSet()
        assertTrue("r_fav" in dialIds)
        assertTrue("lk_1" in dialIds)

        // When user listens heavily to a new track + likes it, Speed Dial updates dynamically
        val newHistory = listOf(
            topicSong("new_obs", "Apna Bana Le", "Sachin-Jigar"),
            topicSong("new_obs", "Apna Bana Le", "Sachin-Jigar"),
        ) + recentHistory
        val updatedDial = composer.composeSpeedDial(
            recent = newHistory,
            liked = liked + topicSong("new_obs", "Apna Bana Le", "Sachin-Jigar"),
            quickPicks = emptyList(),
            trending = trendingCatalog,
            limit = 9,
        )
        assertEquals(9, updatedDial.size)
        assertTrue("new_obs" in updatedDial.take(4).map { it.id })
    }
}
