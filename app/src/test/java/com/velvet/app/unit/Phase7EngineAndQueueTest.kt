package com.velvet.app.unit

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.RepeatMode
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RefillStatus
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7EngineAndQueueTest {

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
        relatedDelayMs: Long = 0L,
        clock: () -> Long = { 5_000_000L },
    ): DefaultRecommendationEngine {
        val poolBuilder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(
                    relatedProvider = {
                        if (relatedDelayMs > 0L) delay(relatedDelayMs)
                        catalog
                    },
                    clock = clock,
                ),
                GenreLanguageNeighbourSource(
                    querySearchProvider = { catalog.reversed() },
                    clock = clock,
                ),
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
    fun fullEngineRejectsOmvsLyricsLivesAndUnverifiedWithZeroLeaks() = runBlocking {
        val seed = topicSong("seed_romantic", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        val catalog = listOf(
            topicSong("valid_1", "Agar Tum Saath Ho", "Alka Yagnik"),
            topicSong("valid_2", "Jeena Jeena", "Atif Aslam"),
            Song("rej_omv", "Channa Mereya (Official Music Video)", "Arijit Singh", 280L, isUploaderVerified = true, rawUploaderName = "Sony Music India"),
            Song("rej_lyric", "Tera Ban Jaunga (Lyrical Video)", "Akhil Sachdeva", 236L, isUploaderVerified = true, rawUploaderName = "T-Series"),
            Song("rej_live", "Kesariya (Live Concert Stage)", "Arijit Singh", 250L, isTopicRelease = true, rawUploaderName = "Arijit Singh - Topic"),
            Song("rej_unv", "Shayad", "T-Series", 248L, isUploaderVerified = true, rawUploaderName = "T-Series"),
        )

        val engine = buildEngine(catalog)
        val result = engine.nextSimilar(seed = seed, mode = RecommendationMode.SONG_RADIO, limit = 10)

        assertEquals(setOf("valid_1", "valid_2"), result.candidates.map { it.song.id }.toSet())
        assertTrue(result.candidates.all { it.releaseAudioClass != ReleaseAudioClass.UNVERIFIED && !it.releaseAudioClass.isExplicitlyRejected })

        val diag = engine.diagnosticsLog.latest()!!
        assertEquals(2, diag.returnedCount)
        assertTrue(diag.rejectedReasonCounts.values.sum() >= 4)
    }

    @Test
    fun coordinatorRevalidatesAndPreservesUserQueueWithoutStaleDiscardOnConcurrentMutation() = runBlocking {
        val seed = topicSong("s1", "Tum Hi Ho", "Arijit Singh")
        val manualSong = topicSong("manual_1", "Pehla Nasha", "Udit Narayan")
        val validRec = topicSong("rec1", "Agar Tum Saath Ho", "Alka Yagnik")
        // Engine returns both validRec and a duplicate of the concurrently added manualSong
        val engine = buildEngine(listOf(manualSong, validRec), relatedDelayMs = 200L)
        val coordinator = AutoplayRefillCoordinator(engine)

        val queueManager = QueueManager()
        queueManager.start(listOf(seed), startIndex = 0)

        fun snapshot() = QueueRefillSnapshot(
            currentSong = queueManager.current,
            upcomingSongs = queueManager.upNext.map { it.song },
            userQueueSongs = queueManager.userQueue.map { it.song },
            historySongs = queueManager.history,
            queueVersion = queueManager.queueVersion,
            isAutoplayEnabled = true,
        )

        val initial = snapshot()
        val deferred = async {
            coordinator.requestRefill(initial, latestSnapshotProvider = ::snapshot)
        }
        delay(35L)
        // Mutate QueueManager mid-flight (user queues a manual song -> queueVersion bumps)
        queueManager.playNext(manualSong)

        val decision = deferred.await()
        assertTrue("Valid refill must NOT be discarded on queueVersion change, got $decision", decision is RefillDecision.AppendSongs)
        val appended = (decision as RefillDecision.AppendSongs).songs
        assertEquals("Commit-time revalidation must exclude concurrently added manual_1 and keep valid rec1", listOf("rec1"), appended.map { it.id })
        assertEquals(RefillStatus.IDLE, coordinator.currentState().status)
        queueManager.appendAutoplay(appended)
        assertEquals(listOf("manual_1"), queueManager.userQueue.map { it.song.id })
        assertEquals(listOf("rec1"), queueManager.upNext.map { it.song.id })
    }

    @Test
    fun refillPolicyHonorsLowWatermarkRepeatOneAndEmptyExhaustion() = runBlocking {
        var now = 10_000_000L
        val clock = { now }
        val seed = topicSong("s1", "Shree Hanuman Chalisa", "Hariharan", durationSec = 580L)
        val omvOnlyCatalog = listOf(
            Song("omv_bad", "Chalisa Official Video", "T-Series", 580L, isUploaderVerified = true, rawUploaderName = "T-Series"),
        )
        val engine = buildEngine(omvOnlyCatalog, clock = clock)
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(targetUpcomingBuffer = 8, lowWatermarkTrigger = 4, minCooldownMs = 1_000L, maxEmptyRefillsPerAnchor = 2),
            clock = clock,
        )

        // 1. RepeatMode.ONE pauses automatic refill
        val repeatSnap = QueueRefillSnapshot(seed, emptyList(), queueVersion = 1L, repeatMode = RepeatMode.ONE, isAutoplayEnabled = true)
        assertEquals("repeat_one_active", (coordinator.requestRefill(repeatSnap, { repeatSnap }) as RefillDecision.Skip).reason)

        // 2. Healthy buffer skips
        val healthySnap = QueueRefillSnapshot(
            seed,
            upcomingSongs = (1..4).map { topicSong("up_$it", "Aarti $it", "Artist $it") },
            queueVersion = 2L,
            isAutoplayEnabled = true,
        )
        assertEquals("buffer_healthy", (coordinator.requestRefill(healthySnap, { healthySnap }) as RefillDecision.Skip).reason)

        // 3. OMV-only failure returns SafeEmpty (NO video leak) and transitions cleanly to EXHAUSTED on repeat
        val emptySnap = QueueRefillSnapshot(seed, emptyList(), queueVersion = 3L, isAutoplayEnabled = true)
        val firstEmpty = coordinator.requestRefill(emptySnap, { emptySnap })
        assertTrue(firstEmpty is RefillDecision.SafeEmpty)
        assertEquals(RefillStatus.COOLDOWN, (firstEmpty as RefillDecision.SafeEmpty).status)

        now += 2_000L
        val secondEmpty = coordinator.requestRefill(emptySnap, { emptySnap })
        assertTrue(secondEmpty is RefillDecision.SafeEmpty)
        assertEquals(RefillStatus.EXHAUSTED, (secondEmpty as RefillDecision.SafeEmpty).status)

        now += 5_000L
        val exhaustedSkip = coordinator.requestRefill(emptySnap, { emptySnap })
        assertEquals("anchor_exhausted", (exhaustedSkip as RefillDecision.Skip).reason)
    }

    @Test
    fun simulatedFourHourSessionMaintainsZeroOmvsZeroDuplicatesAndArtistDiversity() = runBlocking {
        var now = 20_000_000L
        val clock = { now }
        val artists = listOf("Arijit Singh", "Atif Aslam", "Alka Yagnik", "KK", "Jubin Nautiyal", "Armaan Malik", "Shreya Ghoshal", "Sonu Nigam", "Mohit Chauhan", "Rahat Fateh Ali Khan")
        val adjectives = listOf("Savera", "Dhalti", "Meethi", "Gehri", "Uljhi", "Nayi", "Khwabi", "Pehli", "Chupke", "Sard")
        val nouns = listOf("Baarish", "Raatein", "Hawayein", "Kahaani", "Parchhai", "Manzil", "Khamoshi", "Roshni", "Sargam", "Dastaan")
        val verifiedCatalog = (0 until 60).map { i ->
            val artist = artists[i % artists.size]
            val adj = adjectives[i / nouns.size]
            val noun = nouns[i % nouns.size]
            topicSong("studio_${i + 1}", "$adj $noun", artist, durationSec = 240L, album = "Ishq Album ${i % 15}")
        }
        val pollutedCatalog = verifiedCatalog + listOf(
            Song("bad_omv_a", "Romantic Melody (Official Music Video)", "Arijit Singh", 240L, isUploaderVerified = true, rawUploaderName = "Sony Music India"),
            Song("bad_unv_b", "Pyaar Track Unverified", "Random Channel", 230L, isUploaderVerified = true, rawUploaderName = "Random Channel"),
        )

        val engine = buildEngine(pollutedCatalog, clock = clock)
        val coordinator = AutoplayRefillCoordinator(engine, policy = RefillPolicy(minCooldownMs = 0L), clock = clock)
        val queueManager = QueueManager()
        val startSeed = topicSong("studio_0", "Noor e Ishq", "Arijit Singh", durationSec = 240L, album = "Ishq Album 0")
        queueManager.start(listOf(startSeed), startIndex = 0)

        fun snapshot() = QueueRefillSnapshot(
            currentSong = queueManager.current,
            upcomingSongs = queueManager.upNext.map { it.song },
            userQueueSongs = queueManager.userQueue.map { it.song },
            historySongs = queueManager.history,
            queueVersion = queueManager.queueVersion,
            isAutoplayEnabled = true,
        )

        val playedIds = mutableListOf<String>()
        var backToBackArtistPairs = 0

        for (step in 1..40) {
            now += 240_000L // +4 minutes per track (~2.7h to 4h session)
            val snap = snapshot()
            val decision = coordinator.requestRefill(snap, ::snapshot)
            if (decision is RefillDecision.AppendSongs) {
                queueManager.appendAutoplay(decision.songs)
            }
            val current = queueManager.current!!
            assertTrue("OMV leaked into queue: ${current.id}", current.id.startsWith("studio_"))
            if (playedIds.isNotEmpty()) {
                val prevSong = verifiedCatalog.firstOrNull { it.id == playedIds.last() } ?: startSeed
                if (prevSong.artist == current.artist) backToBackArtistPairs++
            }
            playedIds += current.id

            val advanced = queueManager.advance(manual = false)
            if (advanced == null) break
        }

        // Strict quality assertions over sustained session
        assertEquals("Every played song across the session must be unique (zero repeats)", playedIds.size, playedIds.toSet().size)
        assertTrue("Expected sustained multi-track queue playback, played=${playedIds.size}", playedIds.size >= 25)
        assertTrue("Back-to-back same artist rate must remain <10%, found=$backToBackArtistPairs/${playedIds.size}", backToBackArtistPairs <= playedIds.size / 10)
    }
}
