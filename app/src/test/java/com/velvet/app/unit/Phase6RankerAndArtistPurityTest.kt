package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.recommendation.candidates.ArtistCatalogueCandidateSource
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.CollaboratorCandidateSource
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationSurface
import com.velvet.app.recommendation.modes.ArtistStationPolicy
import com.velvet.app.recommendation.modes.ArtistStationTier
import com.velvet.app.recommendation.modes.RadioSessionManager
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class Phase6RankerAndArtistPurityTest {

    private fun topic(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Long = 240L,
    ) = Song(
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
        relatedCatalog: List<Song>,
        artistCatalog: List<Song> = emptyList(),
        collaboratorCatalog: Map<String, List<Song>> = emptyMap(),
        neighbourCatalog: List<Song> = emptyList(),
        eventRepo: RecommendationEventRepository = RecommendationEventRepository(InMemoryEventBackend()),
        now: Long = 100L * TimeUnit.DAYS.toMillis(1),
    ): Pair<DefaultRecommendationEngine, TrackFeatureRepository> {
        val features = TrackFeatureRepository()
        val poolBuilder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = { relatedCatalog }, clock = { now }),
                ArtistCatalogueCandidateSource(artistTracksProvider = { artistCatalog }, clock = { now }),
                CollaboratorCandidateSource(
                    artistTracksProvider = { co -> collaboratorCatalog[co.lowercase()].orEmpty() },
                    clock = { now },
                ),
                GenreLanguageNeighbourSource(querySearchProvider = { neighbourCatalog }, clock = { now }),
            ),
            featureRepository = features,
            clock = { now },
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = eventRepo,
            libraryDataProvider = { com.velvet.app.data.LibraryData() },
            builder = com.velvet.app.recommendation.profile.TasteProfileBuilder(features),
            clock = { now },
        )
        return DefaultRecommendationEngine(
            candidatePoolBuilder = poolBuilder,
            userProfileRepository = profileRepo,
            featureRepository = features,
            clock = { now },
        ) to features
    }

    @Test
    fun artistRadio_enforces60PercentSelectedArtist_collaborators_sameEraNeighbours_andZeroUnrelated() = runBlocking {
        val seed = topic("seed_arijit", "Tum Hi Ho", "Arijit Singh, Mithoon", album = "Aashiqui 2")
        val arijitTracks = (1..14).map { idx ->
            val tag = ('A' + idx)
            topic("ar_$idx", "Dil Ka Safar Part$tag (2018)", "Arijit Singh", album = if (idx % 2 == 0) "AlbumAlpha" else "AlbumBeta")
        }
        val duplicateComposition = topic("ar_dup", "Dil Ka Safar PartB (Unplugged)", "Arijit Singh", album = "AlbumAlpha")
        val collaboratorTracks = listOf(
            topic("co_1", "Sun Raha Hai", "Ankit Tiwari", album = "Aashiqui 2"),
            topic("co_2", "Banjaara Romantic", "Mohammed Irfan, Mithoon", album = "Ek Villain"),
            topic("co_3", "Galiyan Tera Pyar", "Ankit Tiwari", album = "Ek Villain"),
            topic("co_4", "Teri Meri Kahani", "Shreya Ghoshal, Mithoon", album = "Bollywood Mix"),
        )
        val sameEraNeighbours = listOf(
            topic("nb_1", "Jeena Jeena", "Atif Aslam", album = "Badlapur (2015)"),
            topic("nb_2", "Tera Hone Laga Hoon", "Atif Aslam", album = "Ajab Prem (2012)"),
            topic("nb_3", "Lut Gaye", "Jubin Nautiyal", album = "Singles (2021)"),
            topic("nb_4", "Humnava Mere", "Jubin Nautiyal", album = "Romantic (2018)"),
        )
        val unrelatedArtists = listOf(
            topic("unr_en", "Blank Space", "Taylor Swift", album = "1989"),
            topic("unr_pa", "Brown Munde", "AP Dhillon", album = "Punjabi Beats"),
            topic("unr_retro", "Mere Sapno Ki Rani (1969)", "Kishore Kumar", album = "Aradhana"),
            topic("unr_bhajan", "Aisi Lagi Lagan Bhajan", "Anup Jalota", album = "Bhajan Sandhya"),
            topic("unr_edm", "Faded Festival Electro", "Alan Walker", album = "Different World"),
        )

        val (engine, _) = buildEngine(
            relatedCatalog = sameEraNeighbours + unrelatedArtists,
            artistCatalog = arijitTracks + duplicateComposition,
            collaboratorCatalog = mapOf("mithoon" to collaboratorTracks),
            neighbourCatalog = sameEraNeighbours + unrelatedArtists,
        )

        val result = engine.nextSimilar(
            seed = seed,
            mode = RecommendationMode.ARTIST_RADIO,
            limit = 15,
            selectedArtistId = "Arijit Singh",
        )

        val returnedIds = result.candidates.map { it.song.id }.toSet()
        assertTrue(result.candidates.size >= 10)
        // 0 unrelated artists
        assertTrue(unrelatedArtists.none { it.id in returnedIds })
        // 0 repeated compositions
        assertFalse(duplicateComposition.id in returnedIds)

        val selectedCount = result.candidates.count { "arijit" in it.song.artist.lowercase() }
        val share = selectedCount.toDouble() / result.candidates.size.toDouble()
        assertTrue("Expected >= 60% selected artist but got $selectedCount/${result.candidates.size}", share >= 0.60)
        // Collaborators should appear cleanly
        assertTrue(collaboratorTracks.any { it.id in returnedIds })
        // Anchors in RadioSessionManager must NOT drift onto neighbours/collaborators
        val rsm = RadioSessionManager(clock = { 1000L })
        rsm.startSession(seed, RecommendationMode.ARTIST_RADIO, "s-ar", selectedArtistId = "Arijit Singh")
        rsm.onTrackCompletedOrLiked(sameEraNeighbours.first())
        assertEquals(listOf(seed), rsm.evolvedSeedSongs(seed))
    }

    @Test
    fun artistRadio_shortCatalog_scalesTotalDownwardInsteadOfPaddingUnrelated() = runBlocking {
        val seed = topic("seed_short", "Khamoshiyan", "Arijit Singh")
        val shortArtistCatalog = listOf(
            topic("s_1", "Naina Ashq", "Arijit Singh", album = "Holiday (2014)"),
            topic("s_2", "Muskurane Ki Wajah", "Arijit Singh", album = "CityLights (2014)"),
            topic("s_3", "Sooraj Dooba", "Arijit Singh", album = "Roy (2015)"),
        )
        val unrelatedPool = listOf(
            topic("bad_en", "Shake It Off", "Taylor Swift"),
            topic("bad_pa", "295 Track", "Sidhu Moose Wala"),
            topic("bad_ta", "Arabic Kuthu", "Anirudh Ravichander"),
            topic("bad_old", "Pal Pal Dil Ke Paas (1973)", "Kishore Kumar"),
        )
        val (engine, _) = buildEngine(
            relatedCatalog = shortArtistCatalog + unrelatedPool,
            artistCatalog = shortArtistCatalog,
            neighbourCatalog = unrelatedPool,
        )

        val result = engine.nextSimilar(
            seed = seed,
            mode = RecommendationMode.ARTIST_RADIO,
            limit = 20,
            selectedArtistId = "Arijit Singh",
        )

        assertEquals(3, result.candidates.size)
        assertEquals(shortArtistCatalog.map { it.id }.toSet(), result.candidates.map { it.song.id }.toSet())
    }

    @Test
    fun deterministicExploration_sameSeedProducesIdenticalOrder_differentSeedVariesWithinVibe() = runBlocking {
        val seed = topic("seed_det", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2 (2013)")
        val hindiPool = (1..12).map { i ->
            val letter = ('A' + i)
            topic("hi_$i", "Dil Ki Baat $letter", "Hindi Singer $letter", album = "Romance ${2013 + (i % 5)}")
        }
        val (engine, _) = buildEngine(relatedCatalog = hindiPool)

        val run1 = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 10, randomSeed = 12345L)
        val run2 = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 10, randomSeed = 12345L)
        val run3 = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 10, randomSeed = 99999L)

        assertEquals(run1.candidates.map { it.song.id }, run2.candidates.map { it.song.id })
        assertEquals(10, run3.candidates.size)
        assertNotEquals(run1.candidates.map { it.song.id }, run3.candidates.map { it.song.id })
        assertTrue(run3.candidates.all { it.song.id.startsWith("hi_") })
    }

    @Test
    fun tasteLearning_completingArtistAAndSkippingArtistB_ranksArtistAAboveArtistBInEngineAndLocalCatalog() = runBlocking {
        val now = TimeUnit.DAYS.toMillis(80)
        val eventRepo = RecommendationEventRepository(InMemoryEventBackend())
        val seed = topic("seed_t", "Raabta Romantic", "Pritam", album = "Agent Vinod (2012)")

        // User completes songs by Sonu Nigam (Artist A) and early-skips Shaan (Artist B) in active session
        val histA1 = topic("hist_a1", "Abhi Mujh Mein Kahin", "Sonu Nigam", album = "Agneepath (2012)")
        val histA2 = topic("hist_a2", "Kal Ho Naa Ho", "Sonu Nigam", album = "KHNH (2003)")
        val histB1 = topic("hist_b1", "Chand Sifarish", "Shaan", album = "Fanaa (2006)")

        eventRepo.record(
            RecommendationEvent(
                eventId = "e_comp_1",
                eventType = RecommendationEventType.TRACK_COMPLETED,
                occurredAtMillis = now - 600_000L,
                providerItemId = histA1.id,
                artistIds = listOf("Sonu Nigam"),
                surface = RecommendationSurface.AUTOPLAY_QUEUE,
                recommendationSessionId = "sess_taste",
            ),
        )
        eventRepo.record(
            RecommendationEvent(
                eventId = "e_comp_2",
                eventType = RecommendationEventType.TRACK_COMPLETED,
                occurredAtMillis = now - 400_000L,
                providerItemId = histA2.id,
                artistIds = listOf("Sonu Nigam"),
                surface = RecommendationSurface.AUTOPLAY_QUEUE,
                recommendationSessionId = "sess_taste",
            ),
        )
        eventRepo.record(
            RecommendationEvent(
                eventId = "e_skip_b",
                eventType = RecommendationEventType.TRACK_SKIPPED_EARLY,
                occurredAtMillis = now - 120_000L,
                providerItemId = histB1.id,
                artistIds = listOf("Shaan"),
                playedMs = 4_000L,
                surface = RecommendationSurface.AUTOPLAY_QUEUE,
                recommendationSessionId = "sess_taste",
            ),
        )

        val candA = topic("cand_a", "Saathiya Pyar Love", "Sonu Nigam", album = "Romantic (2010)")
        val candB = topic("cand_b", "Tanha Dil Pyar Love", "Shaan", album = "Romantic (2010)")

        val features = TrackFeatureRepository()
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = eventRepo,
            libraryDataProvider = { com.velvet.app.data.LibraryData(liked = listOf(histA1, histA2)) },
            builder = com.velvet.app.recommendation.profile.TasteProfileBuilder(features),
            clock = { now },
        )
        val poolBuilder = CandidatePoolBuilder(
            sources = listOf(SeedRelatedCandidateSource(relatedProvider = { listOf(candA, candB) }, clock = { now })),
            featureRepository = features,
            clock = { now },
        )
        val engine = DefaultRecommendationEngine(poolBuilder, profileRepo, features, clock = { now })

        val rsm = RadioSessionManager(featureRepository = features, clock = { now })
        rsm.startSession(seed, RecommendationMode.SONG_RADIO, "sess_taste")
        rsm.onTrackSkippedEarly(histB1)

        val res = engine.nextSimilar(
            seed = seed,
            mode = RecommendationMode.SONG_RADIO,
            radioSessionId = "sess_taste",
            limit = 5,
            sessionSkippedArtistCounts = rsm.sessionSkippedArtistCounts(),
            sessionSkippedGenreCounts = rsm.sessionSkippedGenreCounts(),
        )
        assertEquals(listOf("cand_a", "cand_b"), res.candidates.map { it.song.id })
        assertTrue(res.candidates.first { it.song.id == "cand_b" }.scoreBreakdown.isNotEmpty())

        // Verify InstantTrackCatalog.matchSimilar local fallback also respects taste + session skip
        val profileSnap = profileRepo.getSnapshot(sessionId = "sess_taste")
        val localRanked = InstantTrackCatalog.matchSimilar(
            seed = seed,
            extraPool = listOf(candB, candA),
            tasteSnapshot = profileSnap,
            sessionSkippedArtistCounts = rsm.sessionSkippedArtistCounts(),
            sessionSkippedGenreCounts = rsm.sessionSkippedGenreCounts(),
            nowMillis = now,
        )
        assertEquals(listOf("cand_a", "cand_b"), localRanked.map { it.id })
    }

    @Test
    fun freshnessAndNovelty_30DayUnplayedTrackBoostedAndOneInFiveSlotPromotesNewDiscoveryInsideVibe() = runBlocking {
        val now = TimeUnit.DAYS.toMillis(120)
        val eventRepo = RecommendationEventRepository(InMemoryEventBackend())
        val seed = topic("seed_n", "Agar Tum Saath Ho", "Alka Yagnik, Arijit Singh", album = "Tamasha (2015)")

        val oldFavorite = topic("old_fav", "Pehla Nasha Romance", "Udit Narayan", album = "Classic (2012)")
        val recentPlayed = topic("recent_pl", "Tujhe Dekha Romance", "Udit Narayan", album = "Classic (2012)")

        // Played oldFavorite 40 days ago, recentPlayed 1 day ago
        eventRepo.record(
            RecommendationEvent(
                eventId = "ev_old",
                eventType = RecommendationEventType.TRACK_COMPLETED,
                occurredAtMillis = now - TimeUnit.DAYS.toMillis(40),
                providerItemId = oldFavorite.id,
                artistIds = listOf("Udit Narayan"),
                recommendationSessionId = "s_old",
            ),
        )
        eventRepo.record(
            RecommendationEvent(
                eventId = "ev_recent",
                eventType = RecommendationEventType.TRACK_COMPLETED,
                occurredAtMillis = now - TimeUnit.DAYS.toMillis(1),
                providerItemId = recentPlayed.id,
                artistIds = listOf("Udit Narayan"),
                recommendationSessionId = "s_recent",
            ),
        )

        // Add more known/played tracks + brand-new Hindi discoveries for 1-in-5 novelty slot
        val knownFiller = (1..8).map { i ->
            val letter = ('A' + i)
            val s = topic("known_$i", "Dil Yaad Song $letter", "Hindi Vocalist $letter", album = "Mix $letter (2014)")
            eventRepo.record(
                RecommendationEvent(
                    eventId = "ev_k_$i",
                    eventType = RecommendationEventType.TRACK_COMPLETED,
                    occurredAtMillis = now - TimeUnit.DAYS.toMillis(2),
                    providerItemId = s.id,
                    artistIds = listOf(s.artist),
                    recommendationSessionId = "s_recent",
                ),
            )
            s
        }
        val unseenDiscoveries = (1..5).map { i ->
            val letter = ('M' + i)
            topic("new_$i", "Nayi Subah Geet $letter", "Shreya Ghoshal", album = "Fresh $letter (2018)")
        }

        val (engine, features) = buildEngine(
            relatedCatalog = listOf(recentPlayed, oldFavorite) + knownFiller + unseenDiscoveries,
            eventRepo = eventRepo,
            now = now,
        )

        val result = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 10)
        val ids = result.candidates.map { it.song.id }
        val oldIdx = ids.indexOf("old_fav")
        val recIdx = ids.indexOf("recent_pl")
        assertTrue("Both tracks should appear: $ids", oldIdx >= 0 && recIdx >= 0)
        assertTrue("30+ day unplayed track must outrank yesterday's track ($oldIdx vs $recIdx)", oldIdx < recIdx)

        // Slot index 4 (5th song) and index 9 (10th song) = 1-in-5 novelty discovery inside vibe
        val slot5 = result.candidates[4]
        assertTrue("5th slot must promote a new-to-user Hindi track, got ${slot5.song.id}", slot5.song.id.startsWith("new_"))
        val newCount = result.candidates.count { it.song.id.startsWith("new_") }
        assertTrue("At least 1 in 5 tracks must be new to user inside SessionVibe (got $newCount / ${result.candidates.size})", newCount >= 2)
        // Also verify ArtistStationPolicy classification excludes cross-language unrelated tracks directly
        val taSong = topic("ta_chk", "Rowdy Baby", "Dhanush")
        val tierCheck = ArtistStationPolicy.classify(
            candidate = com.velvet.app.recommendation.model.CandidateTrack(
                song = taSong,
                identity = com.velvet.app.recommendation.identity.RecordingIdentityResolver.resolve(taSong),
                features = features.getCachedOrExtract(taSong),
                sourceEvidence = emptyList(),
                candidateRetrievedAtMillis = now,
            ),
            ctx = ArtistStationPolicy.buildContext(
                com.velvet.app.recommendation.model.RecommendationRequest(
                    requestId = "t",
                    mode = RecommendationMode.ARTIST_RADIO,
                    seedSongs = listOf(seed),
                    selectedArtistId = "Arijit Singh",
                ),
            ),
            request = com.velvet.app.recommendation.model.RecommendationRequest("t", RecommendationMode.ARTIST_RADIO),
        )
        assertEquals(ArtistStationTier.UNRELATED_ARTIST, tierCheck)
    }
}
