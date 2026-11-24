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
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end Blueprint §39 Golden Scenario & Strict Original Studio Audio Only acceptance suite.
 */
class GoldenAcceptanceSuiteTest {

    private fun topic(id: String, title: String, artist: String, durationSec: Long = 240L, album: String? = null) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = durationSec,
        album = album,
        isTopicRelease = true,
        rawUploaderName = "$artist - Topic",
        preferredMusicSource = true,
    )

    private fun buildTestEngine(
        catalog: List<Song>,
        eventRepo: RecommendationEventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
        libraryProvider: () -> LibraryData = { LibraryData() },
        clock: () -> Long = { 50_000_000L },
    ): DefaultRecommendationEngine {
        val pool = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = { catalog }, clock = clock),
                ArtistCatalogueCandidateSource(artistTracksProvider = { catalog }, clock = clock),
                GenreLanguageNeighbourSource(querySearchProvider = { catalog }, clock = clock),
            ),
            clock = clock,
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = eventRepo,
            libraryDataProvider = libraryProvider,
            clock = clock,
        )
        return DefaultRecommendationEngine(pool, profileRepo, clock = clock)
    }

    @Test
    fun goldenDevotionalSafety_chalisaSelectsVerifiedDevotionalNeverPartyOrVideo() = runBlocking {
        val seed = topic("seed_chalisa", "Shree Hanuman Chalisa", "Hariharan Bhakti", durationSec = 580L)
        val catalog = listOf(
            topic("dev_1", "Om Jai Jagdish Hare Aarti", "Anuradha Paudwal", durationSec = 310L),
            topic("dev_2", "Shiv Tandav Stotram Mantra", "Shankar Mahadevan", durationSec = 420L),
            topic("party_same_artist", "Saturday Night Party Dance", "Hariharan Bhakti", durationSec = 220L),
            Song("dev_omv", "Hanuman Chalisa (Official Video)", "T-Series Bhakti", 580L, isUploaderVerified = true, rawUploaderName = "T-Series Bhakti Sagar"),
            Song("dev_unv", "Shri Krishna Govind", "T-Series Bhakti Sagar", 340L, isUploaderVerified = true, rawUploaderName = "T-Series Bhakti Sagar"),
        )

        val engine = buildTestEngine(catalog)
        val result = engine.nextSimilar(seed, mode = RecommendationMode.SONG_RADIO, limit = 10)

        assertEquals(setOf("dev_1", "dev_2"), result.candidates.map { it.song.id }.toSet())
        assertTrue(result.candidates.none { it.song.id in setOf("party_same_artist", "dev_omv", "dev_unv") })
    }

    @Test
    fun golden90sBollywoodRomantic_prioritizesEraRomanceAndRejectsLyricVideos() = runBlocking {
        val seed = topic("seed_90s", "Tujhe Dekha To Yeh Jaana Sanam", "Kumar Sanu", album = "DDLJ")
        val catalog = listOf(
            topic("fit_90s", "Pehla Nasha Pyar Ishq", "Udit Narayan", album = "Jo Jeeta Wohi Sikandar"),
            topic("mismatch_edm", "DJ Club Party Beat Dance", "Badshah"),
            Song("bad_lyric_90s", "Pardesi Pardesi (Lyrical Video)", "Alka Yagnik", 310L, isUploaderVerified = true, rawUploaderName = "Tips Official"),
        )

        val engine = buildTestEngine(catalog)
        val result = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 10)

        assertEquals("fit_90s", result.candidates.first().song.id)
        assertFalse(result.candidates.any { it.song.id == "bad_lyric_90s" })
        val edmIdx = result.candidates.indexOfFirst { it.song.id == "mismatch_edm" }
        assertTrue(edmIdx == -1 || edmIdx > 0)
    }

    @Test
    fun goldenLanguageBoundary_enforcesExplicitTamilPreferenceSafely() = runBlocking {
        val seed = topic("seed_ta", "Munbe Vaa", "A R Rahman")
        val tamilSong = topic("ta_ok", "Why This Kolaveri Tamil Melody", "Anirudh")
        val hindiParty = topic("hi_clash", "Hindi Bollywood Party", "Badshah")
        val catalog = listOf(tamilSong, hindiParty)

        val engine = buildTestEngine(catalog)
        val res = engine.nextSimilar(
            seed = seed,
            mode = RecommendationMode.SIMILAR_SONGS,
            requestedLanguageIds = setOf("ta"),
            limit = 5,
        )
        assertEquals(listOf("ta_ok"), res.candidates.map { it.song.id })
    }

    @Test
    fun goldenFeedbackSuppression_notInterestedBlocksTrackWithoutBanningWholeGenre() = runBlocking {
        val now = 50_000_000L
        val eventRepo = RecommendationEventRepository(backend = InMemoryEventBackend())

        val seed = topic("s_rom", "Kesariya", "Arijit Singh")
        val suppressedSong = topic("sup_1", "Ishq Track Bad", "Armaan Malik")
        val allowedSong = topic("ok_1", "Jeena Jeena Love", "Atif Aslam")

        val resolvedSuppressed = RecordingIdentityResolver.resolve(suppressedSong)
        eventRepo.record(
            RecommendationEvent(
                eventId = "ni-1",
                eventType = RecommendationEventType.RECOMMENDATION_NOT_INTERESTED,
                occurredAtMillis = now - 1_000L,
                providerItemId = suppressedSong.id,
                canonicalRecordingId = resolvedSuppressed.canonicalRecordingId,
                workId = resolvedSuppressed.workId,
                recommendationSessionId = "sess-1",
            ),
        )
        eventRepo.flushBlocking()

        val engine = buildTestEngine(listOf(suppressedSong, allowedSong), eventRepo = eventRepo, clock = { now })
        val result = engine.nextSimilar(seed, mode = RecommendationMode.SONG_RADIO, limit = 10)

        assertEquals(listOf("ok_1"), result.candidates.map { it.song.id })
    }

    @Test
    fun goldenStrictOriginalStudioAudioGate_enforcedAcrossEveryModeZeroVideoZeroUnverified() = runBlocking {
        val seed = topic("seed_audio", "Agar Tum Saath Ho", "Arijit Singh")
        val acceptedTopic = topic("acc_topic", "Tum Se Hi (Official Audio)", "Mohit Chauhan")
        val acceptedLabelStudio = Song(
            id = "acc_label",
            title = "Dil Diyan Gallan (Official Audio)",
            artist = "Atif Aslam",
            durationSec = 260L,
            isUploaderVerified = true,
            rawUploaderName = "Sony Music India",
            preferredMusicSource = true,
            releaseDescriptionHint = "Official label studio audio track",
        )
        val acceptedOst = Song(
            id = "acc_ost",
            title = "Tere Naina (Original Motion Picture Soundtrack)",
            artist = "Shafqat Amanat Ali",
            durationSec = 278L,
            album = "My Name Is Khan",
            isUploaderVerified = true,
            rawUploaderName = "Sony Music India",
            preferredMusicSource = true,
        )
        val acceptedVerifiedStudio = Song(
            id = "acc_studio",
            title = "Alag Aasmaan (Official Audio)",
            artist = "Anuv Jain",
            durationSec = 212L,
            isUploaderVerified = true,
            rawUploaderName = "Anuv Jain",
            preferredMusicSource = true,
        )

        val rejectedAndUnverifiedPool = listOf(
            Song("rej_omv", "Channa Mereya (Official Music Video)", "Arijit Singh", 289L, isUploaderVerified = true, rawUploaderName = "Sony Music India", preferredMusicSource = true),
            Song("rej_ov", "Khaabon Ke Parindey (Official Video)", "Mohit Chauhan", 252L, isUploaderVerified = true, rawUploaderName = "T-Series", preferredMusicSource = true),
            Song("rej_lyric", "Apna Bana Le (Lyrical Video)", "Arijit Singh", 261L, isUploaderVerified = true, rawUploaderName = "Zee Music Company", preferredMusicSource = true),
            Song("rej_vis", "Husn (Visualizer)", "Anuv Jain", 217L, isUploaderVerified = true, rawUploaderName = "Anuv Jain", preferredMusicSource = true),
            Song("rej_live", "Kun Faya Kun (Live Concert Performance)", "A R Rahman", 470L, isTopicRelease = true, rawUploaderName = "A R Rahman - Topic", preferredMusicSource = true),
            Song("rej_remix", "Kesariya (DJ Chetas Remix)", "Arijit Singh", 230L, isTopicRelease = true, rawUploaderName = "Arijit Singh - Topic", preferredMusicSource = true),
            Song("rej_slowed", "Tum Hi Ho (Slowed + Reverb)", "Arijit Singh", 310L, isTopicRelease = true, rawUploaderName = "LoFi Maker", preferredMusicSource = true),
            Song("rej_unv_verified_channel", "Ve Kamleya", "Zee Music Company", 248L, isUploaderVerified = true, rawUploaderName = "Zee Music Company", preferredMusicSource = true),
        )

        val expectedAcceptedIds = setOf("acc_topic", "acc_label", "acc_ost", "acc_studio")
        val fullCatalog = listOf(acceptedTopic, acceptedLabelStudio, acceptedOst, acceptedVerifiedStudio) + rejectedAndUnverifiedPool
        val engine = buildTestEngine(fullCatalog)

        val modesToVerify = listOf(
            RecommendationMode.SIMILAR_SONGS,
            RecommendationMode.SONG_RADIO,
            RecommendationMode.ARTIST_RADIO,
            RecommendationMode.NEXT_SONG,
            RecommendationMode.AUTOPLAY_REFILL,
            RecommendationMode.HOME_SONGS,
            RecommendationMode.PERSONALIZED_PLAYLIST,
        )

        for (mode in modesToVerify) {
            val res = engine.recommend(
                RecommendationRequest(
                    requestId = "accept-${mode.name}",
                    mode = mode,
                    seedSongs = listOf(seed),
                    selectedArtistId = if (mode == RecommendationMode.ARTIST_RADIO) "Mohit Chauhan" else null,
                    limit = 20,
                ),
            )
            val returnedIds = res.candidates.map { it.song.id }.toSet()
            assertEquals("Mode ${mode.name} must accept strictly verified original studio audio only", expectedAcceptedIds, returnedIds)
            assertTrue(
                "Mode ${mode.name} leaked non-studio/unverified class",
                res.candidates.all {
                    it.releaseAudioClass in setOf(
                        ReleaseAudioClass.VERIFIED_TOPIC_AUDIO,
                        ReleaseAudioClass.VERIFIED_LABEL_STUDIO_AUDIO,
                        ReleaseAudioClass.VERIFIED_OST_AUDIO,
                        ReleaseAudioClass.VERIFIED_STUDIO_AUDIO,
                    )
                },
            )
        }
    }
}
