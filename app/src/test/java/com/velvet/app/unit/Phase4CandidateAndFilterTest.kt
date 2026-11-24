package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.recommendation.candidates.AudioNeighbourCandidateSource
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.CandidateSourceRequest
import com.velvet.app.recommendation.candidates.CandidateSourceResult
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.LibraryTrackCandidateSource
import com.velvet.app.recommendation.candidates.RawTrackCandidate
import com.velvet.app.recommendation.candidates.RecommendationCandidateSource
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.candidates.SourceCircuitBreaker
import com.velvet.app.recommendation.features.MetadataFeatureExtractor
import com.velvet.app.recommendation.filtering.DefaultEligibilityPolicy
import com.velvet.app.recommendation.filtering.DuplicateFilter
import com.velvet.app.recommendation.model.CandidateSourceStatus
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.VersionKind
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Phase4CandidateAndFilterTest {

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

    @Test
    fun singleSourceFailureTimeoutAndG8DisabledDoNotBreakPoolBuild() = runBlocking {
        val seed = topicSong("seed1", "Kesariya", "Arijit Singh")
        val c1 = topicSong("cand1", "Shayad", "Arijit Singh")
        val c2 = topicSong("cand2", "Apna Bana Le", "Arijit Singh")

        val throwingSource = object : RecommendationCandidateSource {
            override val sourceId = "failing_src"
            override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
                throw IllegalStateException("upstream 500")
            }
        }
        val slowSource = object : RecommendationCandidateSource {
            override val sourceId = "slow_src"
            override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
                delay(2_000L)
                return CandidateSourceResult(sourceId, emptyList(), 0L, 2_000L, CandidateSourceStatus.SUCCESS)
            }
        }
        val healthyG1 = SeedRelatedCandidateSource({ listOf(c1, c2) })
        val g5 = GenreLanguageNeighbourSource({ q ->
            if (q.contains("bollywood", ignoreCase = true)) listOf(c2) else emptyList()
        })
        val g8 = AudioNeighbourCandidateSource(enabled = false)

        val builder = CandidatePoolBuilder(
            sources = listOf(throwingSource, slowSource, healthyG1, g5, g8),
            perSourceTimeoutMs = 150L,
            overallDeadlineMs = 1_000L,
        )
        val req = RecommendationRequest(
            requestId = "req-1",
            mode = RecommendationMode.SONG_RADIO,
            seedSongs = listOf(seed),
        )
        val pool = builder.build(req, UserTasteSnapshot())

        assertFalse(pool.fromCache)
        assertEquals(listOf("cand1", "cand2"), pool.candidates.map { it.song.id })
        // cand2 came from both g1_related and g5_genre_neighbours and preserved both evidences
        val mergedC2 = pool.candidates.first { it.song.id == "cand2" }
        assertTrue("g1_related" in mergedC2.sourceIds)
        assertTrue("g5_genre_neighbours" in mergedC2.sourceIds)
        assertEquals(CandidateSourceStatus.UNAVAILABLE, pool.sourceHealth["failing_src"]?.status)
        assertEquals(CandidateSourceStatus.TIMEOUT, pool.sourceHealth["slow_src"]?.status)
        assertEquals(CandidateSourceStatus.SUCCESS, pool.sourceHealth["g1_related"]?.status)
        assertFalse(g8.enabled)
    }

    @Test
    fun circuitBreakerTripsAndRecoversAfterCooldownAndCacheRespectsModeKeys() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val calls = AtomicInteger(0)
        val breaker = SourceCircuitBreaker(clock = { clock.get() }, failureThreshold = 2, baseCooldownMs = 10_000L)
        var shouldFail = true

        val flakySource = object : RecommendationCandidateSource {
            override val sourceId = "flaky"
            override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
                calls.incrementAndGet()
                if (shouldFail) {
                    return CandidateSourceResult(sourceId, emptyList(), clock.get(), 10L, CandidateSourceStatus.RATE_LIMITED, "429")
                }
                return CandidateSourceResult(
                    sourceId = sourceId,
                    candidates = listOf(
                        RawTrackCandidate(topicSong("ok1", "Hawayein", "Pritam"), sourceId, 0, 0.9, "ok", 0.9, clock.get()),
                    ),
                    fetchedAtMillis = clock.get(),
                    latencyMillis = 10L,
                    status = CandidateSourceStatus.SUCCESS,
                )
            }
        }

        val builder = CandidatePoolBuilder(
            sources = listOf(flakySource),
            circuitBreaker = breaker,
            clock = { clock.get() },
            cacheTtlMs = 30_000L,
        )
        val req = RecommendationRequest("r1", RecommendationMode.SONG_RADIO, seedSongs = listOf(topicSong("s1", "Kesariya", "Pritam")))

        builder.build(req, UserTasteSnapshot())
        builder.build(req, UserTasteSnapshot()) // 2nd failure opens circuit
        assertEquals(2, calls.get())

        val circuitOpenPool = builder.build(req, UserTasteSnapshot())
        assertEquals(2, calls.get()) // blocked by open circuit without network call
        assertEquals(CandidateSourceStatus.CIRCUIT_OPEN, circuitOpenPool.sourceHealth["flaky"]?.status)

        // Advance clock past cooldown -> half-open probe succeeds & caches pool
        shouldFail = false
        clock.addAndGet(15_000L)
        val recoveredPool = builder.build(req, UserTasteSnapshot())
        assertEquals(3, calls.get())
        assertEquals(1, recoveredPool.candidates.size)

        // Same mode hits cache
        val cachedPool = builder.build(req, UserTasteSnapshot())
        assertTrue(cachedPool.fromCache)
        assertEquals(3, calls.get())

        // Changing mode invalidates cache key cleanly
        val artistReq = req.copy(mode = RecommendationMode.ARTIST_RADIO)
        val artistPool = builder.build(artistReq, UserTasteSnapshot())
        assertFalse(artistPool.fromCache)
        assertEquals(4, calls.get())
    }

    @Test
    fun cancellationPropagatesImmediatelyWithoutBeingSwallowed() = runBlocking {
        val builder = CandidatePoolBuilder(
            sources = listOf(
                object : RecommendationCandidateSource {
                    override val sourceId = "slow"
                    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
                        delay(5_000L)
                        return CandidateSourceResult(sourceId, emptyList(), 0L, 5_000L, CandidateSourceStatus.SUCCESS)
                    }
                },
            ),
            perSourceTimeoutMs = 5_000L,
            overallDeadlineMs = 5_000L,
        )
        val job = async {
            builder.build(
                RecommendationRequest("cancel-req", RecommendationMode.AUTOPLAY_REFILL, seedSongs = listOf(topicSong("s1", "Song", "Artist"))),
                UserTasteSnapshot(),
            )
        }
        delay(40L)
        job.cancel(CancellationException("seed changed"))
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun strictOriginalAudioEligibilityRejectsAllVideosUnverifiedAndAlternatesFailClosed() = runBlocking {
        val seed = topicSong("seed", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        val verifiedTopic = topicSong("v_top", "Sun Raha Hai", "Ankit Tiwari", album = "Aashiqui 2")
        val verifiedLabelAudio = Song(
            id = "v_lbl",
            title = "Chahu Main Ya Naa (Official Audio)",
            artist = "Palak Muchhal, Arijit Singh",
            durationSec = 304,
            isUploaderVerified = true,
            rawUploaderName = "T-Series",
            preferredMusicSource = true,
        )
        val officialVideoVerified = Song(
            id = "rej_omv",
            title = "Milne Hai Mujhse Aayi (Official Video)",
            artist = "Arijit Singh",
            durationSec = 295,
            isUploaderVerified = true,
            rawUploaderName = "T-Series",
            preferredMusicSource = true,
        )
        val lyricVideo = Song(
            id = "rej_lyr",
            title = "Aasan Nahin Yahan (Lyrical Video)",
            artist = "Arijit Singh",
            durationSec = 214,
            isUploaderVerified = true,
            rawUploaderName = "T-Series",
        )
        val visualizer = Song(
            id = "rej_vis",
            title = "Bhula Dena (Official Visualizer)",
            artist = "Mustafa Zahid",
            durationSec = 240,
            isUploaderVerified = true,
        )
        val liveRecording = Song(
            id = "rej_live",
            title = "Tum Hi Ho (Live Concert)",
            artist = "Arijit Singh",
            durationSec = 260,
            isUploaderVerified = true,
        )
        val remix = Song(
            id = "rej_rmx",
            title = "Piya Aaye Na (DJ Remix)",
            artist = "KK",
            durationSec = 245,
        )
        val ambiguousVerifiedNoAudioProof = Song(
            id = "amb_ver",
            title = "Hum Mar Jayenge",
            artist = "T-Series",
            durationSec = 306,
            isUploaderVerified = true,
            isTopicRelease = false,
            rawUploaderName = "T-Series",
            preferredMusicSource = true,
        )

        val builder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(
                    relatedProvider = {
                        listOf(
                            verifiedTopic, verifiedLabelAudio, officialVideoVerified,
                            lyricVideo, visualizer, liveRecording, remix, ambiguousVerifiedNoAudioProof,
                        )
                    },
                ),
            ),
        )
        val extractor = MetadataFeatureExtractor()
        val seedFeatures = extractor.extract(seed)
        val req = RecommendationRequest("r-strict", RecommendationMode.SONG_RADIO, seedSongs = listOf(seed))
        val pool = builder.build(req, UserTasteSnapshot())
        val policy = DefaultEligibilityPolicy()

        val eligible = pool.candidates.filter {
            policy.evaluate(it, req, UserTasteSnapshot(), seedFeatures).eligible
        }
        assertEquals(setOf("v_top", "v_lbl"), eligible.map { it.song.id }.toSet())

        // If pool ONLY contains official music video + UNVERIFIED, result MUST be empty (never padded with videos!)
        val videoOnlyPool = CandidatePoolBuilder(
            sources = listOf(SeedRelatedCandidateSource(relatedProvider = { listOf(officialVideoVerified, ambiguousVerifiedNoAudioProof) })),
        ).build(req.copy(requestId = "r-empty-strict"), UserTasteSnapshot())
        val keptFromVideoPool = videoOnlyPool.candidates.filter {
            policy.evaluate(it, req, UserTasteSnapshot(), seedFeatures).eligible
        }
        assertTrue("Strict original-audio policy must return empty rather than leaking OMV or UNVERIFIED", keptFromVideoPool.isEmpty())

        // Explicit Remix Radio override permits matching REMIX when seed itself is a Remix
        val remixSeed = Song("seed_rmx", "Kesariya (DJ Remix)", "Arijit Singh", 220)
        val remixReq = RecommendationRequest(
            requestId = "r-remix",
            mode = RecommendationMode.SONG_RADIO,
            seedSongs = listOf(remixSeed),
            allowExplicitUserIntentOverride = true,
        )
        val remixCand = pool.candidates.first { it.song.id == "rej_rmx" }
        assertTrue(policy.evaluate(remixCand, remixReq, UserTasteSnapshot(), extractor.extract(remixSeed)).eligible)
        assertEquals(VersionKind.REMIX, remixCand.identity.versionKind)
    }

    @Test
    fun duplicateFilterCollapsesSameStudioUploadAndDevotionalGateBlocksPartyMismatch() = runBlocking {
        val devotionalSeed = topicSong("h_seed", "Shree Hanuman Chalisa", "Hariharan Bhakti", durationSec = 580)
        val sameChalisaTopic = topicSong("h_dup1", "Shree Hanuman Chalisa", "Hariharan Bhakti", durationSec = 581)
        val sameChalisaLabel = Song(
            id = "h_dup2",
            title = "Shree Hanuman Chalisa (Official Audio)",
            artist = "Hariharan Bhakti",
            durationSec = 580,
            isUploaderVerified = true,
            rawUploaderName = "T-Series Bhakti",
            preferredMusicSource = true,
        )
        val bhajanValid = topicSong("h_valid1", "Shiv Tandav Stotram", "Shankar Mahadevan Bhakti", durationSec = 540)
        val secularPartySameArtist = topicSong("h_party", "Aaj Ki Party Dance", "Hariharan Bhakti", durationSec = 220)
        val tamilTrack = topicSong("h_tamil", "Tamil Bhakti Vandana", "S. P. Balasubrahmanyam", durationSec = 260)

        val builder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = { listOf(sameChalisaTopic, bhajanValid, secularPartySameArtist, tamilTrack) }),
                LibraryTrackCandidateSource(libraryTracksProvider = { listOf(sameChalisaLabel, bhajanValid) }),
            ),
        )
        val req = RecommendationRequest("r-dev", RecommendationMode.SONG_RADIO, seedSongs = listOf(devotionalSeed))
        val pool = builder.build(req, UserTasteSnapshot())
        val seedFeatures = MetadataFeatureExtractor().extract(devotionalSeed)
        val policy = DefaultEligibilityPolicy()

        val eligible = pool.candidates.filter {
            policy.evaluate(it, req, UserTasteSnapshot(), seedFeatures).eligible
        }
        // Party dance and unrequested South-Indic mismatch dropped; long 580s devotional allowed
        assertFalse(eligible.any { it.song.id == "h_party" })
        assertFalse(eligible.any { it.song.id == "h_tamil" })
        assertTrue(eligible.any { it.song.id == "h_valid1" })

        val deduped = DuplicateFilter().filter(eligible, req)
        assertEquals(listOf("h_valid1"), deduped.kept.map { it.song.id })
        assertTrue(deduped.removedAlreadyKnownCount >= 1)
    }
}
