package com.velvet.app.unit

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.CandidateSourceRequest
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.InstantTrackCatalogCandidateSource
import com.velvet.app.recommendation.candidates.LibraryTrackCandidateSource
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEvent
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.events.RecommendationEventType
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.recommendation.vibe.ArtistLanguageRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * F2 verification (Fix C2):
 * - Both g6_library and g0_instant_catalog strictly enforce exact SessionVibe language + genre/type gates,
 *   exclude the last N=50 played session tracks, and exclude tracks skipped early within the last 14 days.
 * - Library rediscovery cap stays <= 25% whenever discovery exists (no < limit/2 uncapped loophole),
 *   and never admits ungated library fallback.
 * - Stepped anchors use ONLY the last 10 played/completed tracks of the current session (never libData.liked).
 */
class F2LibraryAndAnchorGuardTest {

    @Before
    fun setUp() {
        DiagnosticsLogger.clear()
        DiagnosticsLogger.detachLogDirectoryForTest()
        QueueHealthMonitor.clearForTest()
        InstantTrackCatalog.clear()
        ArtistLanguageRegistry.clearForTest()
    }

    private fun topicSong(
        id: String,
        title: String,
        artist: String,
        album: String? = "Hindi Classics",
        durationSec: Long = 240L,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = durationSec,
        album = album,
        isTopicRelease = true,
        isUploaderVerified = true,
        rawUploaderName = "$artist - Topic",
        preferredMusicSource = true,
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    @Test
    fun libraryAndInstantSources_enforceSessionVibeLast50PlaysAnd14DayEarlySkipGuards() = runBlocking {
        val now = TimeUnit.DAYS.toMillis(60)
        val featureRepo = TrackFeatureRepository()
        val seed = topicSong("seed_f2", "Tumko Dekha Toh", "Kumar Sanu, Alka Yagnik", album = "Sanam")

        val validHindi = listOf(
            topicSong("lib_valid_1", "Meethi Yaadein", "Udit Narayan", album = "90s Gold"),
            topicSong("lib_valid_2", "Gehri Khamoshi", "Sonu Nigam", album = "Romantic Hits"),
            topicSong("lib_valid_3", "Bheegi Chandni", "Shreya Ghoshal", album = "Melodies"),
        )
        val offLangEnglish = topicSong("lib_off_en", "Blinding Lights", "The Weeknd", album = "After Hours")
        val offLangTamil = topicSong("lib_off_ta", "Rowdy Baby", "Dhanush", album = "Maari 2")
        val unknownArtistTrack = topicSong("lib_unknown_lang", "Mystery Drift", "Unknown Indie Creator", album = "Various")
        val spokenTrack = topicSong("lib_spoken", "Morning Comedy Podcast Show", "RJ Random", album = "Talks")

        val recent50Oldest = topicSong("lib_played_49_ago", "Pehli Nazar", "Atif Aslam", album = "Race")
        val fillerHistory = (1..49).map { idx ->
            topicSong("hist_$idx", "Filler Track Num$idx Code$idx", "Kumar Sanu", album = "Hist Album")
        }
        val history50 = listOf(recent50Oldest) + fillerHistory

        val skipped3DaysAgo = topicSong("lib_skip_3d", "Channa Mereya", "Arijit Singh", album = "ADHM")
        val skipped18DaysAgo = topicSong("lib_skip_18d", "Agar Tum Saath Ho", "Alka Yagnik", album = "Tamasha")

        val allLibrary = validHindi + listOf(
            offLangEnglish,
            offLangTamil,
            unknownArtistTrack,
            spokenTrack,
            recent50Oldest,
            skipped3DaysAgo,
            skipped18DaysAgo,
        )
        InstantTrackCatalog.seed(allLibrary)

        val eventRepo = RecommendationEventRepository(backend = InMemoryEventBackend(), clock = { now })
        eventRepo.record(
            RecommendationEvent(
                eventId = "skip_3d",
                eventType = RecommendationEventType.TRACK_SKIPPED_EARLY,
                occurredAtMillis = now - TimeUnit.DAYS.toMillis(3),
                providerItemId = skipped3DaysAgo.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(skipped3DaysAgo).canonicalRecordingId,
                artistIds = listOf("arijit singh"),
                recommendationSessionId = "prev_s",
            ),
        )
        eventRepo.record(
            RecommendationEvent(
                eventId = "skip_18d",
                eventType = RecommendationEventType.TRACK_SKIPPED_EARLY,
                occurredAtMillis = now - TimeUnit.DAYS.toMillis(18),
                providerItemId = skipped18DaysAgo.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(skipped18DaysAgo).canonicalRecordingId,
                artistIds = listOf("alka yagnik"),
                recommendationSessionId = "old_s",
            ),
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = eventRepo,
            libraryDataProvider = { LibraryData(liked = allLibrary) },
            clock = { now },
        )
        val profile = profileRepo.getSnapshot(sessionId = "cur_s", nowMillis = now)

        val req = RecommendationRequest(
            requestId = "f2-check",
            mode = RecommendationMode.AUTOPLAY_REFILL,
            seedSongs = listOf(seed),
            currentSong = seed,
            recentSongs = history50,
            requestedLanguageIds = setOf("hi", "en"),
            allowMixedLanguages = false,
            createdAtMillis = now,
        )
        val seedFeat = featureRepo.getCachedOrExtract(seed)

        val g6Res = LibraryTrackCandidateSource({ allLibrary }, featureRepo, clock = { now })
            .fetch(CandidateSourceRequest(req, listOf(seedFeat), profile, maxCandidates = 30, deadlineMillis = Long.MAX_VALUE))
        val g6Ids = g6Res.candidates.map { it.song.id }.toSet()

        val g0Res = InstantTrackCatalogCandidateSource(extraSongsProvider = { allLibrary }, featureRepository = featureRepo, clock = { now })
            .fetch(CandidateSourceRequest(req, listOf(seedFeat), profile, maxCandidates = 30, deadlineMillis = Long.MAX_VALUE))
        val g0Ids = g0Res.candidates.map { it.song.id }.toSet()

        for ((name, ids) in listOf("g6_library" to g6Ids, "g0_instant_catalog" to g0Ids)) {
            assertTrue("$name should keep valid Hindi vibe tracks ($ids)", "lib_valid_1" in ids && "lib_valid_2" in ids)
            assertFalse("$name must reject English off-vibe track", offLangEnglish.id in ids)
            assertFalse("$name must reject Tamil off-vibe track", offLangTamil.id in ids)
            assertFalse("$name must reject unknown-language track under locked Hindi vibe", unknownArtistTrack.id in ids)
            assertFalse("$name must reject spoken track", spokenTrack.id in ids)
            assertFalse("$name must exclude track played within last 50 of session", recent50Oldest.id in ids)
            assertFalse("$name must exclude track skipped early in last 14 days", skipped3DaysAgo.id in ids)
            assertTrue("$name may allow single early skip older than 14 days", skipped18DaysAgo.id in ids)
        }
    }

    @Test
    fun libraryRediscoveryCap_strictlyCapsTo25PercentEvenWhenDiscoveryBelowHalfLimit() = runBlocking {
        val featureRepo = TrackFeatureRepository()
        val seed = topicSong("cap_seed", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        // Only 4 discovery tracks (< limit/2 = 8 for limit = 16) -> previously triggered uncapped library dump!
        val sparseDiscovery = (1..4).map { i ->
            topicSong("disc_$i", "Dil Ki Roshni Alpha$i", "Shaan", album = "Discovery Album $i")
        }
        val manyLibrary = (1..16).map { i ->
            topicSong("lib_only_$i", "Purani Yaad Beta$i", "Sonu Nigam", album = "User Library $i")
        }

        val engine = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(
                    SeedRelatedCandidateSource(relatedProvider = { sparseDiscovery }),
                    LibraryTrackCandidateSource(libraryTracksProvider = { manyLibrary }, featureRepository = featureRepo),
                ),
                featureRepository = featureRepo,
                cacheTtlMs = 0L,
            ),
            userProfileRepository = DefaultUserProfileRepository(
                eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                libraryDataProvider = { LibraryData(liked = manyLibrary) },
            ),
            featureRepository = featureRepo,
        )

        val result = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 16, requestedLanguageIds = setOf("hi"))
        val libOnlyCount = result.candidates.count { c ->
            c.sourceIds.all { it == "g6_library" || it == "g0_instant_catalog" }
        }
        val totalReturned = result.candidates.size
        assertTrue("Sparse discovery must still appear", totalReturned >= 4)
        assertTrue(
            "Expected strict <= 25% library share even when discovery (${sparseDiscovery.size}) < limit/2 (lib=$libOnlyCount / total=$totalReturned)",
            libOnlyCount * 4 <= totalReturned,
        )
    }

    @Test
    fun anchorStepping_usesOnlyLast10SessionTracksAndNeverStepsOntoLikedSongs() = runBlocking {
        val drySeed = topicSong("anchor_dry", "Tujhe Dekha To", "Kumar Sanu", album = "DDLJ")
        val oldSessionTrackBeyond10 = topicSong("hist_too_old", "Anjaana Safar", "Kumar Sanu", album = "90s Old")
        val recent10SessionTracks = (1..10).map { i ->
            topicSong("hist_recent_$i", "Pyar Ki Kahaani $i", "Alka Yagnik", album = "90s Session $i")
        }
        val fullSessionHistory = listOf(oldSessionTrackBeyond10) + recent10SessionTracks
        val likedNotInSession = topicSong("liked_non_session", "External Liked Song", "Udit Narayan", album = "Liked Album")
        val likedForeignNotInSession = topicSong("liked_foreign", "Blinding Lights", "The Weeknd", album = "After Hours")

        val steppedAnchorResults = (1..10).map { i ->
            topicSong("step_res_$i", "Chandni Raat $i", "Sonu Nigam", album = "Recovered Classics $i")
        }
        val attemptedSeeds = mutableListOf<String>()
        val coordinator = AutoplayRefillCoordinator(
            engine = DefaultRecommendationEngine(
                candidatePoolBuilder = CandidatePoolBuilder(
                    sources = listOf(
                        SeedRelatedCandidateSource(
                            relatedProvider = { anchor ->
                                attemptedSeeds += anchor.id
                                when (anchor.id) {
                                    "hist_recent_10" -> steppedAnchorResults
                                    "liked_non_session", "liked_foreign", "hist_too_old" -> listOf(
                                        topicSong("bad_step_track", "Should Never Step Here", "Kumar Sanu"),
                                    )
                                    else -> emptyList()
                                }
                            },
                        ),
                    ),
                    cacheTtlMs = 0L,
                ),
                userProfileRepository = DefaultUserProfileRepository(
                    eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                    libraryDataProvider = { LibraryData(liked = listOf(likedNotInSession, likedForeignNotInSession)) },
                ),
            ),
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 0L,
            ),
        )

        val snap = QueueRefillSnapshot(
            currentSong = drySeed,
            upcomingSongs = emptyList(),
            historySongs = fullSessionHistory,
            queueVersion = 1L,
            isAutoplayEnabled = true,
            preferredLanguageIds = setOf("hi"),
            // Even if an external caller leaves non-session liked songs or too-old tracks in fallbackAnchorPool,
            // coordinator must restrict stepping strictly to this session's last 10 tracks.
            fallbackAnchorPool = listOf(likedForeignNotInSession, likedNotInSession, oldSessionTrackBeyond10) + recent10SessionTracks.reversed(),
        )
        val decision = coordinator.requestRefill(snap, { snap }, force = true)
        assertTrue("Expected session-only anchor stepping to succeed", decision is RefillDecision.AppendSongs)
        assertFalse("Must NEVER step onto non-session liked song", likedNotInSession.id in attemptedSeeds)
        assertFalse("Must NEVER step onto foreign non-session liked song", likedForeignNotInSession.id in attemptedSeeds)
        assertFalse("Must NEVER step beyond last 10 session history tracks", oldSessionTrackBeyond10.id in attemptedSeeds)
        assertTrue("Expected step onto recent session anchor hist_recent_10 (attempted=$attemptedSeeds)", "hist_recent_10" in attemptedSeeds)
    }
}
