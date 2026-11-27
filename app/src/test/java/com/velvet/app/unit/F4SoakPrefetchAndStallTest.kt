package com.velvet.app.unit

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.data.StreamResolver
import com.velvet.app.diagnostics.AutoplaySoakTester
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.diagnostics.StallKind
import com.velvet.app.diagnostics.StreamPrefetchStatus
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.CandidateSourceRequest
import com.velvet.app.recommendation.candidates.CandidateSourceResult
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.RawTrackCandidate
import com.velvet.app.recommendation.candidates.RecommendationCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RecommendationDiagnosticsLog
import com.velvet.app.recommendation.engine.RecommendationEngine
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.model.CandidateSourceStatus
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.RecommendationResult
import com.velvet.app.recommendation.model.ScoredCandidate
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class F4SoakPrefetchAndStallTest {

    private val hindiArtists = listOf(
        "Arijit Singh",
        "Shreya Ghoshal",
        "Sonu Nigam",
        "Sunidhi Chauhan",
        "Pritam",
        "Mithoon",
        "Atif Aslam",
        "Jubin Nautiyal",
        "Vishal Dadlani",
        "Armaan Malik",
        "Mohit Chauhan",
        "Alka Yagnik",
        "Udit Narayan",
        "Amit Trivedi",
        "A.R. Rahman",
    )

    private val firstWords = listOf(
        "Safar", "Chandni", "Barish", "Dhadkan", "Khwaab",
        "Manzil", "Rooh", "Sitaron", "Nadiya", "Palkon",
        "Zindagi", "Umeed", "Aasmaan", "Mehfil", "Tanhai",
        "Muskaan", "Savera", "Shaam", "Hawa", "Lehrein",
    )

    private val secondWords = listOf(
        "Geet", "Raag", "Dhun", "Nagma", "Tarana",
        "Sargam", "Saaz", "Sur", "Kahaani", "Pal",
    )

    private fun hindiTrack(index: Int, prefix: String = "f4trk"): Song {
        val w1 = firstWords[index % firstWords.size]
        val w2 = secondWords[(index / firstWords.size) % secondWords.size]
        val artist = hindiArtists[index % hindiArtists.size]
        val suffixNum = (index / (firstWords.size * secondWords.size)).let { if (it > 0) " Vol $it" else "" }
        val rawId = "${prefix}${index.toString().padStart(11 - prefix.length, '0')}".take(11)
        return Song(
            id = rawId,
            title = "$w1 $w2$suffixNum",
            artist = artist,
            durationSec = 235L + (index % 40),
            thumb = "https://lh3.googleusercontent.com/test/$rawId=w544-h544-l90-rj",
            preferredMusicSource = true,
            album = "$w1 Album",
            isTopicRelease = true,
            isUploaderVerified = true,
            hasSquareArtwork = true,
            isYouTubeMusicCatalog = true,
        )
    }

    @Before
    fun setUp() {
        QueueHealthMonitor.clearForTest()
        StreamResolver.clearCache()
        StreamResolver.resolveProviderForTest = null
        StreamResolver.streamResolveStallThresholdMsForTest = null
        StreamResolver.nextRadioPageProviderForTest = null
        InstantTrackCatalog.clear()
        DiagnosticsLogger.detachLogDirectoryForTest()
        DiagnosticsLogger.clear()
    }

    @After
    fun tearDown() {
        StreamResolver.resolveProviderForTest = null
        StreamResolver.streamResolveStallThresholdMsForTest = null
        StreamResolver.nextRadioPageProviderForTest = null
        StreamResolver.clearCache()
        QueueHealthMonitor.clearForTest()
    }

    @Test
    fun prefetchUpcomingPrefetchesNextThreeSongsAndTracksStreamStallsOnCacheMiss() {
        val resolvedOrder = CopyOnWriteArrayList<String>()
        StreamResolver.streamResolveStallThresholdMsForTest = 60L
        StreamResolver.resolveProviderForTest = { videoId ->
            resolvedOrder += videoId
            if (videoId == "f4cold00004") {
                Thread.sleep(90L)
            }
            StreamResolver.Resolved(
                url = "https://mock.googlevideo.com/$videoId",
                headers = emptyMap(),
                expiresAtMs = System.currentTimeMillis() + 3_600_000L,
                quality = "OPUS 160K",
                rendition = "251",
                fast = true,
            )
        }

        val initial = StreamResolver.prefetchUpcoming(
            videoIds = listOf("f4pref00001", "f4down00002", "f4pref00003", "f4cold00004"),
            count = 3,
            isDownloaded = { it == "f4down00002" },
        )
        assertEquals(3, initial.size)

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            val entries = QueueHealthMonitor.state.value.streamPrefetchEntries
            if (entries.size == 3 &&
                entries[0].status == StreamPrefetchStatus.CACHED &&
                entries[1].status == StreamPrefetchStatus.DOWNLOADED &&
                entries[2].status == StreamPrefetchStatus.CACHED
            ) {
                break
            }
            Thread.sleep(20L)
        }

        val snapAfterPrefetch = QueueHealthMonitor.state.value
        assertEquals(3, snapAfterPrefetch.streamPrefetchEntries.size)
        assertEquals(StreamPrefetchStatus.CACHED, snapAfterPrefetch.streamPrefetchEntries[0].status)
        assertEquals(StreamPrefetchStatus.DOWNLOADED, snapAfterPrefetch.streamPrefetchEntries[1].status)
        assertEquals(StreamPrefetchStatus.CACHED, snapAfterPrefetch.streamPrefetchEntries[2].status)
        assertFalse("4th track must not be prefetched ahead of N+1..N+3", "f4cold00004" in resolvedOrder)

        // Foreground resolve hits prefetched N+1 and N+3 with zero stream-resolve stalls
        StreamResolver.resolve("f4pref00001")
        StreamResolver.resolve("f4pref00003")
        val afterHits = QueueHealthMonitor.state.value
        assertEquals(2, afterHits.streamPrefetchHitCount)
        assertEquals(0, afterHits.streamResolveStallCount)

        // Foreground resolve on non-prefetched slow track triggers STREAM_RESOLVE_STALL separately
        StreamResolver.resolve("f4cold00004")
        val afterMiss = QueueHealthMonitor.state.value
        assertEquals(1, afterMiss.streamPrefetchMissCount)
        assertEquals(1, afterMiss.streamResolveStallCount)
        assertEquals(0, afterMiss.refillStallCount)
        assertEquals(StallKind.STREAM_RESOLVE_STALL, afterMiss.recentStalls.first().kind)
        assertEquals("f4cold00004", afterMiss.recentStalls.first().targetId)
    }

    @Test
    fun queueHealthSeparatesRefillStallsAndStreamResolveStallsInTelemetryAndReport() {
        QueueHealthMonitor.recordQueueState(
            aheadCount = 3,
            isPlaying = true,
            anchor = hindiTrack(0, "f4anc"),
            nowMs = 10_000L,
        )
        QueueHealthMonitor.recordQueueState(
            aheadCount = 12,
            isPlaying = true,
            anchor = hindiTrack(0, "f4anc"),
            nowMs = 12_600L,
        )
        QueueHealthMonitor.recordRefillStall(
            anchorId = "f4anc000000",
            anchorTitle = "Safar Geet",
            waitMs = 950L,
            detail = "empty_queue_wait",
            timestampMs = 13_000L,
        )
        QueueHealthMonitor.recordStreamResolve(
            videoId = "f4cold00009",
            cacheHit = false,
            durationMs = 640L,
            succeeded = true,
            songTitle = "Chandni Raag",
            timestampMs = 14_000L,
        )

        val snap = QueueHealthMonitor.state.value
        assertEquals(2, snap.refillStallCount)
        assertEquals(1, snap.streamResolveStallCount)
        assertTrue(snap.recentStalls.any { it.kind == StallKind.REFILL_STALL })
        assertTrue(snap.recentStalls.any { it.kind == StallKind.STREAM_RESOLVE_STALL })

        val report = QueueHealthMonitor.formatPlainText(snap)
        assertTrue(report.contains("STREAM PREFETCH (N+1..N+3)"))
        assertTrue(report.contains("STALLS (REFILL vs STREAM-RESOLVE)"))
        assertTrue(report.contains("Refill Stalls: count=2"))
        assertTrue(report.contains("Stream-Resolve Stalls: count=1"))
    }

    @Test
    fun doubleShortRefillBelowMinBatchTriggersImmediateWidenedSecondPass() = runBlocking {
        val anchor = hindiTrack(0, "f4ws")
        val firstShortBatch = (1..8).map { hindiTrack(it, "f4wa") }
        val secondShortBatch = (9..13).map { hindiTrack(it, "f4wb") }
        val widenedBatch = (14..30).map { hindiTrack(it, "f4wc") }

        val callCounter = AtomicInteger(0)
        val recordedRequests = CopyOnWriteArrayList<RecommendationRequest>()
        val spyEngine = object : RecommendationEngine {
            override suspend fun recommend(request: RecommendationRequest): RecommendationResult {
                error("not used directly")
            }

            override suspend fun nextSimilar(
                seed: Song,
                contextSnapshot: List<Song>,
                mode: RecommendationMode,
                limit: Int,
                radioSessionId: String?,
                excludedRecordingIds: Set<String>,
                excludedProviderIds: Set<String>,
                queueSongs: List<Song>,
                userQueueSongs: List<Song>,
                recentSongs: List<Song>,
                selectedArtistId: String?,
                requestedLanguageIds: Set<String>,
                allowExplicitUserIntentOverride: Boolean,
                seedSongsOverride: List<Song>,
                allowMixedLanguages: Boolean,
                artistLanguageMap: Map<String, com.velvet.app.recommendation.vibe.ArtistLanguageProfile>,
                sessionVibeOverride: com.velvet.app.recommendation.vibe.SessionVibe?,
                randomSeed: Long?,
                sessionSkippedArtistCounts: Map<String, Int>,
                sessionSkippedGenreCounts: Map<String, Int>,
                widenEra: Boolean,
                widenMood: Boolean,
            ): RecommendationResult {
                val req = RecommendationRequest(
                    requestId = "req-${callCounter.incrementAndGet()}",
                    mode = mode,
                    seedSongs = listOf(seed),
                    currentSong = seed,
                    widenEra = widenEra,
                    widenMood = widenMood,
                )
                recordedRequests += req
                val chosen = when {
                    widenEra && widenMood -> widenedBatch
                    callCounter.get() == 1 -> firstShortBatch
                    else -> secondShortBatch
                }.filter { it.id !in excludedProviderIds }

                return RecommendationResult(
                    requestId = req.requestId,
                    mode = mode,
                    candidates = chosen.mapIndexed { idx, song ->
                        ScoredCandidate(
                            song = song,
                            score = 1.0 - idx * 0.01,
                            sourceIds = setOf(if (widenEra) "g5_genre_neighbours" else "g1_related"),
                            evidence = emptyList(),
                        )
                    },
                    modelVersion = "test",
                    generatedAtMillis = 10_000L,
                    sourceHealth = emptyMap(),
                    diagnosticsId = "",
                )
            }

            override fun invalidateCandidateCache(seedId: String?) = Unit
        }

        val coordinator = AutoplayRefillCoordinator(
            engine = spyEngine,
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 20_000L,
            ),
            clock = { 10_000L },
        )

        val snap1 = QueueRefillSnapshot(
            currentSong = anchor,
            upcomingSongs = emptyList(),
            queueVersion = 1L,
            isAutoplayEnabled = true,
            preferredLanguageIds = setOf("hi"),
        )
        val dec1 = coordinator.requestRefill(snap1, { snap1 })
        assertTrue(dec1 is RefillDecision.AppendSongs)
        assertEquals(8, (dec1 as RefillDecision.AppendSongs).songs.size)
        assertFalse(QueueHealthMonitor.state.value.recentFunnels.first().widenedRetryTriggered)
        assertEquals(1, coordinator.currentState().consecutiveShortRefills)

        // Second refill immediately (same timestamp 10_000L, same anchor, < REFILL_BELOW):
        // initial pass yields 5 (< MIN_BATCH=15) -> must trigger immediate widened second pass without 20s wait!
        val snap2 = QueueRefillSnapshot(
            currentSong = anchor,
            upcomingSongs = dec1.songs,
            queueVersion = 2L,
            isAutoplayEnabled = true,
            preferredLanguageIds = setOf("hi"),
        )
        val dec2 = coordinator.requestRefill(snap2, { snap2 })
        assertTrue(dec2 is RefillDecision.AppendSongs)
        val batch2 = (dec2 as RefillDecision.AppendSongs).songs
        assertTrue(
            "Expected immediate widened pass to raise 2nd refill >= MIN_BATCH (${RunwayPolicy.MIN_BATCH}), got ${batch2.size}",
            batch2.size >= RunwayPolicy.MIN_BATCH,
        )
        assertTrue(recordedRequests.any { it.widenEra && it.widenMood })
        val funnel2 = QueueHealthMonitor.state.value.recentFunnels.first()
        assertTrue(funnel2.widenedRetryTriggered)
        assertTrue("widened_retry" in funnel2.acceptedSourceIds)
        assertEquals(0, coordinator.currentState().consecutiveShortRefills)
    }

    @Test
    fun autoplaySoakTestAdvancesSixtyTimesWithFunnelStatsAndDetectsStallsAndEmpties() = runBlocking {
        val catalog = (1..220).map { hindiTrack(it, "f4soak") }
        var nowMs = 1_000_000L
        val healthyEngine = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(
                    object : RecommendationCandidateSource {
                        override val sourceId: String = "g1_related"
                        override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
                            val excluded = request.request.excludedProviderIds
                            val fresh = catalog.filter { it.id !in excluded }.take(request.maxCandidates)
                            return CandidateSourceResult(
                                sourceId = sourceId,
                                candidates = fresh.mapIndexed { idx, song ->
                                    RawTrackCandidate(
                                        song = song,
                                        sourceId = sourceId,
                                        sourceRank = idx,
                                        retrievalScore = 0.9 - idx * 0.005,
                                        evidenceCode = "soak_test",
                                    )
                                },
                                fetchedAtMillis = nowMs,
                                latencyMillis = 40L,
                                status = if (fresh.isEmpty()) CandidateSourceStatus.EMPTY else CandidateSourceStatus.SUCCESS,
                            )
                        }
                    },
                ),
                clock = { nowMs },
            ),
            userProfileRepository = DefaultUserProfileRepository(
                RecommendationEventRepository(InMemoryEventBackend()),
                libraryDataProvider = { LibraryData() },
                clock = { nowMs },
            ),
            diagnosticsLog = RecommendationDiagnosticsLog(),
            clock = { nowMs },
        )

        val seed = hindiTrack(0, "f4seed")
        val healthyResult = AutoplaySoakTester.runSoakTest(
            languageName = "Hindi",
            targetAdvances = 60,
            engine = healthyEngine,
            featureRepository = TrackFeatureRepository(),
            seedSongProvider = { _, _ -> seed },
            clock = {
                nowMs += 50L
                nowMs
            },
            warmUpEnabled = false,
        )

        assertEquals(60, healthyResult.completedAdvances)
        assertEquals(60, healthyResult.targetAdvances)
        assertEquals(0, healthyResult.emptyAdvanceCount)
        assertEquals(0, healthyResult.stallCount)
        assertTrue(healthyResult.minQueueAhead >= RunwayPolicy.URGENT_BELOW)
        assertTrue(healthyResult.avgQueueAhead >= 10.0)
        assertTrue(healthyResult.funnelAverages.refillCount >= 3)
        assertTrue(healthyResult.funnelAverages.avgFetched > 0.0)
        assertTrue(healthyResult.funnelAverages.avgAccepted > 0.0)
        assertTrue(healthyResult.emptyOrExhaustedDecisions.isEmpty())
        val healthyReport = healthyResult.formatPlainText()
        assertTrue(healthyReport.contains("Queue Ahead: min="))
        assertTrue(healthyReport.contains("FUNNEL AVERAGES"))
        assertTrue(healthyReport.contains("EMPTY / EXHAUSTED DECISIONS (0)"))

        // Second soak scenario: simulate one slow empty-queue stall (> 2s) and an exhausted SafeEmpty decision
        var stallClockMs = 5_000_000L
        var requestSeq = 0
        val stallAndEmptyEngine = object : RecommendationEngine {
            override suspend fun recommend(request: RecommendationRequest): RecommendationResult {
                error("unused")
            }

            override suspend fun nextSimilar(
                seed: Song,
                contextSnapshot: List<Song>,
                mode: RecommendationMode,
                limit: Int,
                radioSessionId: String?,
                excludedRecordingIds: Set<String>,
                excludedProviderIds: Set<String>,
                queueSongs: List<Song>,
                userQueueSongs: List<Song>,
                recentSongs: List<Song>,
                selectedArtistId: String?,
                requestedLanguageIds: Set<String>,
                allowExplicitUserIntentOverride: Boolean,
                seedSongsOverride: List<Song>,
                allowMixedLanguages: Boolean,
                artistLanguageMap: Map<String, com.velvet.app.recommendation.vibe.ArtistLanguageProfile>,
                sessionVibeOverride: com.velvet.app.recommendation.vibe.SessionVibe?,
                randomSeed: Long?,
                sessionSkippedArtistCounts: Map<String, Int>,
                sessionSkippedGenreCounts: Map<String, Int>,
                widenEra: Boolean,
                widenMood: Boolean,
            ): RecommendationResult {
                val seq = ++requestSeq
                if (seq == 1) {
                    // Initial empty-queue refill stalls for 2_400ms (> 2_000ms threshold)
                    stallClockMs += 2_400L
                } else if (seq == 2) {
                    // Return empty -> triggers SafeEmpty & records empty decision
                    return RecommendationResult(
                        requestId = "soak-empty-$seq",
                        mode = mode,
                        candidates = emptyList(),
                        modelVersion = "test",
                        generatedAtMillis = stallClockMs,
                        sourceHealth = emptyMap(),
                        diagnosticsId = "",
                    )
                }
                val batch = catalog.filter { it.id !in excludedProviderIds }.take(limit)
                return RecommendationResult(
                    requestId = "soak-$seq",
                    mode = mode,
                    candidates = batch.mapIndexed { idx, s ->
                        ScoredCandidate(s, 0.9 - idx * 0.01, setOf("g1_related"), emptyList())
                    },
                    modelVersion = "test",
                    generatedAtMillis = stallClockMs,
                    sourceHealth = emptyMap(),
                    diagnosticsId = "",
                )
            }

            override fun invalidateCandidateCache(seedId: String?) = Unit
        }

        val stallResult = AutoplaySoakTester.runSoakTest(
            languageName = "Hindi",
            targetAdvances = 15,
            engine = stallAndEmptyEngine,
            featureRepository = TrackFeatureRepository(),
            seedSongProvider = { _, _ -> seed },
            clock = { stallClockMs },
            warmUpEnabled = false,
        )
        assertNotNull(stallResult)
        assertEquals(15, stallResult.completedAdvances)
        assertTrue("Expected >=1 stall for >2s empty-queue wait", stallResult.stallCount >= 1)
        assertTrue(
            "Expected empty/exhausted decision to be recorded, got ${stallResult.emptyOrExhaustedDecisions}",
            stallResult.emptyOrExhaustedDecisions.isNotEmpty(),
        )
        assertTrue(stallResult.formatPlainText().contains("EMPTY / EXHAUSTED DECISIONS (${stallResult.emptyOrExhaustedDecisions.size})"))
    }
}
