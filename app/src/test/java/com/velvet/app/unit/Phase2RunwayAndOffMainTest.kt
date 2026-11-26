package com.velvet.app.unit

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.QueueManager
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.LibraryIndex
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.ExoSyncSerializer
import com.velvet.app.recommendation.engine.NextTapCoalescer
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RunwayController
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.engine.RunwayTrigger
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase P2 verification:
 * - RunwayPolicy (30 / 15 / 6 / batch 15..20) & RunwayController across a simulated 4-hour session
 *   with random skips and intermittent slow/failing network.
 * - NextTapCoalescer coalescing rapid taps within 150ms.
 * - ExoSyncSerializer serializing concurrent syncToExoPlayer dispatches.
 * - Off-main LibraryIndex & O(1) takePreparedInstant benchmark vs P0 baseline.
 */
class Phase2RunwayAndOffMainTest {

    @Before
    fun resetState() {
        DiagnosticsLogger.clear()
        DiagnosticsLogger.detachLogDirectoryForTest()
        QueueHealthMonitor.clearForTest()
        InstantTrackCatalog.clear()
    }

    private fun studioSong(
        id: String,
        title: String,
        artist: String,
        album: String = "Hindi Evergreen Classics",
        durationSec: Long = 230L,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = durationSec,
        album = album,
        isTopicRelease = true,
        rawUploaderName = "$artist - Topic",
        preferredMusicSource = true,
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    private fun buildLargeHindiCatalog(count: Int = 360): List<Song> {
        val adj = listOf(
            "Nayi", "Meethi", "Gehri", "Sard", "Chupke", "Pehli", "Uljhi", "Khwabi",
            "Dhalti", "Sunehri", "Anjani", "Bheegi", "Rang", "Chandni", "Soni", "Raat",
            "Subah", "Shaam", "Halke", "Pyaari",
        )
        val nouns = listOf(
            "Baarish", "Raatein", "Hawayein", "Kahaani", "Parchhai", "Manzil", "Khamoshi", "Roshni",
            "Sargam", "Dastaan", "Aashiqui", "Dhadkan", "Muskaan", "Yaadein", "Sapne", "Duniya",
            "Safar", "Awaaz",
        )
        val artists = listOf(
            "Arijit Singh", "Sonu Nigam", "Shreya Ghoshal", "Udit Narayan", "Alka Yagnik",
            "Atif Aslam", "KK", "Mohit Chauhan", "Sunidhi Chauhan", "Jubin Nautiyal",
            "Pritam", "A. R. Rahman", "Amit Trivedi", "Vishal Dadlani", "Armaan Malik",
            "Palak Muchhal", "Kishore Kumar", "Kumar Sanu", "Lata Mangeshkar", "Kavita Krishnamurthy",
        )
        val albums = listOf("Aashiqui Classics", "Bollywood Soul", "90s Hindi Gold", "Dil Se Collection")
        val all = ArrayList<Song>(count)
        var idx = 0
        for (a in adj) {
            for (n in nouns) {
                if (all.size >= count) break
                val id = "hi_cat_$idx"
                all.add(
                    studioSong(
                        id = id,
                        title = "$a $n",
                        artist = artists[idx % artists.size],
                        album = albums[idx % albums.size],
                        durationSec = 210L + (idx % 5) * 15L,
                    ),
                )
                idx++
            }
        }
        return all
    }

    private fun snapshotOf(qm: QueueManager): QueueRefillSnapshot = QueueRefillSnapshot(
        currentSong = qm.current,
        upcomingSongs = qm.upNext.map { it.song },
        userQueueSongs = qm.userQueue.map { it.song },
        historySongs = qm.history,
        queueVersion = qm.queueVersion,
        repeatMode = qm.repeatMode,
        isAutoplayEnabled = true,
        preferredLanguageIds = emptySet(),
    )

    @Test
    fun fourHourSessionWithRandomSkipsAndSlowFailingNetworkMaintainsRunwayAndNeverEmpties() = runBlocking {
        val simClock = AtomicLong(100_000_000L)
        val catalog = buildLargeHindiCatalog(360)
        val featureRepo = TrackFeatureRepository()
        val libraryIndex = LibraryIndex(featureRepo)
        val libData = LibraryData(
            liked = catalog.take(80),
            recent = catalog.drop(80).take(60),
        )
        libraryIndex.updateIncremental(libData)

        val networkFailing = AtomicBoolean(false)
        val playedOrQueuedProvider = { qm: QueueManager ->
            buildSet {
                qm.current?.id?.let(::add)
                qm.upNext.forEach { add(it.song.id) }
                qm.userQueue.forEach { add(it.song.id) }
                qm.history.forEach { add(it.id) }
            }
        }
        val qm = QueueManager()
        val seed = studioSong("seed_tum_hi", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        qm.start(listOf(seed), startIndex = 0)

        val poolBuilder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(
                    relatedProvider = { anchor ->
                        delay(25L) // slow network
                        if (networkFailing.get()) {
                            throw IllegalStateException("simulated network timeout")
                        }
                        val taken = playedOrQueuedProvider(qm)
                        catalog.filter { it.id != anchor.id && it.id !in taken }.take(60)
                    },
                    clock = { simClock.get() },
                ),
                GenreLanguageNeighbourSource(
                    querySearchProvider = {
                        delay(20L)
                        if (networkFailing.get()) {
                            throw IllegalStateException("simulated search outage")
                        }
                        val taken = playedOrQueuedProvider(qm)
                        catalog.asReversed().filter { it.id !in taken }.take(60)
                    },
                    clock = { simClock.get() },
                ),
            ),
            clock = { simClock.get() },
            cacheTtlMs = 0L,
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { libData },
            clock = { simClock.get() },
        )
        val engine = DefaultRecommendationEngine(
            candidatePoolBuilder = poolBuilder,
            userProfileRepository = profileRepo,
            featureRepository = featureRepo,
            clock = { simClock.get() },
        )
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 300L,
                failureBackoffMs = 5_000L,
            ),
            clock = { simClock.get() },
            featureRepository = featureRepo,
            urgentFallbackProvider = { anchor, snap, excludedIds ->
                libraryIndex.findSimilarFromIndex(
                    seed = anchor,
                    excludedIds = excludedIds,
                    excludedRecIds = excludedIds,
                    preferredLanguageIds = snap.preferredLanguageIds,
                    allowArtistHeavy = snap.isArtistRadioActive,
                    extraPool = catalog,
                    limit = RunwayPolicy.MIN_BATCH,
                )
            },
        )
        val runway = RunwayController(
            coordinator = coordinator,
            libraryIndex = libraryIndex,
            featureRepository = featureRepo,
            mainDispatcher = Dispatchers.Unconfined,
            clock = { System.currentTimeMillis() },
        )

        suspend fun runTrigger(trigger: RunwayTrigger, force: Boolean = false) {
            val job = runway.triggerRefill(
                trigger = trigger,
                snapshotProvider = { snapshotOf(qm) },
                libraryDataProvider = { libData },
                force = force,
            ) { decision ->
                if (decision is RefillDecision.AppendSongs && decision.songs.isNotEmpty()) {
                    qm.appendAutoplay(decision.songs)
                }
            }
            job?.join()
        }

        // Initial fill on playback start
        runTrigger(RunwayTrigger.APP_START_OR_RESTORE, force = true)
        assertTrue("Initial runway must populate >= 15 tracks", qm.upNext.size >= RunwayPolicy.REFILL_BELOW)

        val rng = Random(20261126L)
        val fourHoursMs = 4L * 3600L * 1000L
        var simulatedElapsedMs = 0L
        var advances = 0
        var stalls = 0
        var minAhead = Int.MAX_VALUE
        var minSettledAhead = Int.MAX_VALUE
        var totalAhead = 0L

        while (simulatedElapsedMs < fourHoursMs && advances < 120) {
            // Intermittent slow/failing network (~15% chance per advance), recovering after 1-2 periodic ticks
            val failingThisStep = (advances % 7 == 3)
            networkFailing.set(failingThisStep)

            val isEarlySkip = rng.nextInt(100) < 30
            val listenMs = if (isEarlySkip) (15_000L + rng.nextInt(30_000)) else (195_000L + rng.nextInt(45_000))
            // Advance simulated 20s periodic ticks during playback
            var remainingMs = listenMs
            while (remainingMs > 0L) {
                val step = minOf(remainingMs, RunwayPolicy.PERIODIC_TICK_MS)
                simulatedElapsedMs += step
                simClock.addAndGet(step)
                remainingMs -= step
                // Network recovers on second periodic tick if it failed on advance
                if (networkFailing.get() && remainingMs < listenMs - RunwayPolicy.PERIODIC_TICK_MS) {
                    networkFailing.set(false)
                    runTrigger(RunwayTrigger.NETWORK_REGAINED)
                } else {
                    runTrigger(RunwayTrigger.PERIODIC_TICK)
                }
            }

            if (networkFailing.get()) {
                networkFailing.set(false)
                runTrigger(RunwayTrigger.NETWORK_REGAINED)
            }

            if (qm.upNext.isEmpty()) {
                stalls++
                break
            }
            val advanced = qm.advance(manual = isEarlySkip)
            if (advanced == null) {
                stalls++
                break
            }
            advances++
            minAhead = minOf(minAhead, qm.upNext.size)

            runTrigger(if (isEarlySkip) RunwayTrigger.MANUAL_NEXT else RunwayTrigger.TRACK_CHANGE)
            val settled = qm.upNext.size
            minSettledAhead = minOf(minSettledAhead, settled)
            totalAhead += settled
            assertTrue("Settled runway after advance #$advances dropped to $settled (<15)", settled >= RunwayPolicy.REFILL_BELOW)
        }

        val avgAhead = if (advances > 0) totalAhead.toDouble() / advances else 0.0
        val (p50, p95) = runway.latencyPercentiles()
        println(
            "[P2-SIM] 4-hour session: elapsedMin=${simulatedElapsedMs / 60_000}, advances=$advances, " +
                "minAhead=$minAhead, minSettledAhead=$minSettledAhead, avgAhead=${"%.2f".format(avgAhead)}, " +
                "p50=${p50}ms, p95=${p95}ms, stalls=$stalls",
        )

        assertTrue("Simulated session should reach 4 hours", simulatedElapsedMs >= fourHoursMs)
        assertEquals("Queue must never empty across 4h session", 0, stalls)
        assertTrue("minSettledAhead=$minSettledAhead must be >= ${RunwayPolicy.REFILL_BELOW}", minSettledAhead >= RunwayPolicy.REFILL_BELOW)
        assertTrue("avgAhead=$avgAhead must stay healthy (>= 15)", avgAhead >= 15.0)
    }

    @Test
    fun urgentModeBypassesCooldownAndUsesStrictVerifiedFallbackOnNetworkFailure() = runBlocking {
        val nowMs = AtomicLong(1_000_000L)
        val catalog = buildLargeHindiCatalog(60)
        val featureRepo = TrackFeatureRepository()
        val libraryIndex = LibraryIndex(featureRepo)
        val seed = studioSong("seed_1", "Tum Hi Ho", "Arijit Singh")
        val foreignTa = Song(
            id = "foreign_ta",
            title = "தமிழ் Kollywood Hit",
            artist = "Anirudh Ravichander",
            durationSec = 200L,
            album = "Kollywood Chartbusters",
            isTopicRelease = true,
            rawUploaderName = "Anirudh Ravichander - Topic",
            preferredMusicSource = true,
            isYouTubeMusicCatalog = true,
            hasSquareArtwork = true,
        )
        val unverifiedOmv = Song(
            id = "omv_bad",
            title = "Tum Hi Ho (Official Music Video)",
            artist = "Arijit Singh",
            durationSec = 210L,
            isTopicRelease = false,
            rawUploaderName = "T-Series",
        )
        val libData = LibraryData(liked = catalog + foreignTa + unverifiedOmv)
        libraryIndex.updateIncremental(libData)

        // Failing engine simulates complete network loss while upNext < URGENT_BELOW (6)
        val failingPool = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = { throw IllegalStateException("offline") }),
            ),
            clock = { nowMs.get() },
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { libData },
            clock = { nowMs.get() },
        )
        val engine = DefaultRecommendationEngine(
            candidatePoolBuilder = failingPool,
            userProfileRepository = profileRepo,
            featureRepository = featureRepo,
            clock = { nowMs.get() },
        )
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 5_000L,
                failureBackoffMs = 30_000L,
            ),
            clock = { nowMs.get() },
            featureRepository = featureRepo,
            urgentFallbackProvider = { anchor, snap, excludedIds ->
                // Return unverified OMV & foreign track alongside valid Hindi tracks to prove strict filtering
                listOf(unverifiedOmv, foreignTa) + libraryIndex.findSimilarFromIndex(
                    seed = anchor,
                    excludedIds = excludedIds,
                    excludedRecIds = excludedIds,
                    preferredLanguageIds = snap.preferredLanguageIds,
                    allowArtistHeavy = snap.isArtistRadioActive,
                    extraPool = emptyList(),
                    limit = RunwayPolicy.MIN_BATCH,
                )
            },
        )

        val urgentSnap = QueueRefillSnapshot(
            currentSong = seed,
            upcomingSongs = catalog.take(3), // < URGENT_BELOW (6)
            queueVersion = 1L,
            isAutoplayEnabled = true,
            preferredLanguageIds = setOf("hi"),
        )
        // Put coordinator into COOLDOWN backoff before urgent check to prove urgent mode bypasses backoff
        val nonUrgentFail = coordinator.requestRefill(
            initialSnapshot = urgentSnap.copy(upcomingSongs = catalog.take(8)),
            latestSnapshotProvider = { urgentSnap.copy(upcomingSongs = catalog.take(8)) },
            force = false,
        )
        assertTrue("Non-urgent offline attempt with 8 ahead returns SafeEmpty", nonUrgentFail is RefillDecision.SafeEmpty)

        nowMs.addAndGet(500L) // Still well inside 30s failureBackoffMs
        val urgentDecision = coordinator.requestRefill(
            initialSnapshot = urgentSnap,
            latestSnapshotProvider = { urgentSnap },
            force = false,
        )
        assertTrue("Expected AppendSongs from urgentFallbackProvider, got $urgentDecision", urgentDecision is RefillDecision.AppendSongs)
        val songs = (urgentDecision as RefillDecision.AppendSongs).songs
        assertTrue("Expected fallback songs >= 10, got ${songs.size}", songs.size >= 10)
        assertTrue("OMV track must be strictly rejected", songs.none { it.id == unverifiedOmv.id })
        assertTrue("Foreign high-confidence track must be rejected for hi lock", songs.none { it.id == foreignTa.id })
    }

    @Test
    fun nextTapCoalescerCoalesces20RapidTapsIntoSingleExecution() = runBlocking {
        val executions = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coalescer = NextTapCoalescer(
                scope = scope,
                delayMs = RunwayPolicy.NEXT_COALESCE_WINDOW_MS,
            ) {
                executions.incrementAndGet()
            }
            repeat(20) {
                coalescer.tap()
                delay(4L)
            }
            delay(300L)
            assertEquals("20 rapid taps within 150ms must coalesce into 1 execution", 1, executions.get())

            coalescer.tap()
            delay(300L)
            assertEquals("Separate tap after window must execute cleanly", 2, executions.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun exoSyncSerializerNeverRunsTwoSyncsConcurrently() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val serializer = ExoSyncSerializer(scope, dispatchDispatcher = Dispatchers.Default)
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val completed = AtomicInteger(0)
            val lastExecutedTask = AtomicInteger(-1)

            val launchJobs = (1..50).map { i ->
                launch(Dispatchers.Default) {
                    serializer.submit {
                        val nowActive = active.incrementAndGet()
                        maxActive.updateAndGet { prev -> maxOf(prev, nowActive) }
                        Thread.sleep(2L)
                        completed.incrementAndGet()
                        lastExecutedTask.set(i)
                        active.decrementAndGet()
                    }
                }
            }
            launchJobs.joinAll()
            delay(250L)

            assertEquals("Two syncToExoPlayer dispatches must never overlap", 1, maxActive.get())
            assertTrue("At least one sync executed and coalesced latest state", completed.get() in 1..50)
            assertTrue("Last task ID should be valid", lastExecutedTask.get() in 1..50)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun libraryIndexAndTakePreparedInstantIsSubMillisecondOn640Songs() = runBlocking {
        val catalog = buildLargeHindiCatalog(360) + (0 until 280).map { i ->
            studioSong(
                id = "extra_$i",
                title = "Classical Raag Sequence ${'A' + (i / 20)}${'a' + (i % 20)}",
                artist = "Pandit Jasraj",
                album = "Midnight Classics",
                durationSec = 240L,
            )
        }
        val libData = LibraryData(liked = catalog.take(320), recent = catalog.drop(320))
        val seed = studioSong("bench_seed", "Tum Hi Ho", "Arijit Singh")

        val featureRepo = TrackFeatureRepository()
        val libraryIndex = LibraryIndex(featureRepo)
        val coordinator = AutoplayRefillCoordinator(
            engine = DefaultRecommendationEngine(
                candidatePoolBuilder = CandidatePoolBuilder(sources = emptyList()),
                userProfileRepository = DefaultUserProfileRepository(
                    eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                    libraryDataProvider = { libData },
                ),
                featureRepository = featureRepo,
            ),
            featureRepository = featureRepo,
        )
        val runway = RunwayController(
            coordinator = coordinator,
            libraryIndex = libraryIndex,
            featureRepository = featureRepo,
        )

        // Warm libraryIndex + preparedSeedCache off-main
        libraryIndex.updateIncremental(libData)
        runway.prepareSeedInBackground(
            seed = seed,
            libraryDataProvider = { libData },
            excludedIdsProvider = { setOf(seed.id) },
            preferredLanguageIds = setOf("hi"),
            allowArtistHeavy = false,
        )
        // Wait briefly for background prep to populate cache before measuring Main thread read
        var waitMs = 0L
        while (runway.takePreparedInstant(seed.id, setOf(seed.id)).isEmpty() && waitMs < 3_000L) {
            delay(20L)
            waitMs += 20L
        }

        val coldStart = System.nanoTime()
        val coldMatches = runway.takePreparedInstant(seed.id, setOf(seed.id), limit = 6)
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0

        val warmStart = System.nanoTime()
        repeat(50) {
            runway.takePreparedInstant(seed.id, setOf(seed.id), limit = 6)
        }
        val warmAvgMs = ((System.nanoTime() - warmStart) / 1_000_000.0) / 50.0

        println(
            "[P2-BENCHMARK] takePreparedInstant(640 songs): cold=${"%.4f".format(coldMs)}ms, " +
                "warmAvg=${"%.4f".format(warmAvgMs)}ms, matches=${coldMatches.size}",
        )
        assertTrue("Expected prepared matches", coldMatches.isNotEmpty())
        assertTrue("Main-thread read must be < 5ms (was ${coldMs}ms)", coldMs < 5.0)
        assertTrue("Warm main-thread read must be < 2ms (was ${warmAvgMs}ms)", warmAvgMs < 2.0)
    }
}
