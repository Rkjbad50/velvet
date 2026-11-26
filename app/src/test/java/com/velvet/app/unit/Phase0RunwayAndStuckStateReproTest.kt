package com.velvet.app.unit

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Playlist
import com.velvet.app.data.Song
import com.velvet.app.diagnostics.CrashHandler
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.LogLevel
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.QueueManager
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RecommendationDiagnostics
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RefillStatus
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.model.CandidateSourceStatus
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.SourceHealth
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.ui.PlayerConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Phase0RunwayAndStuckStateReproTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetDiagnosticsAndCatalog() {
        DiagnosticsLogger.clear()
        DiagnosticsLogger.detachLogDirectoryForTest()
        QueueHealthMonitor.clearForTest()
        InstantTrackCatalog.clear()
    }

    private fun studioSong(
        id: String,
        title: String,
        artist: String,
        album: String? = "Bollywood Gold",
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
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    private fun buildEngine(
        catalog: List<Song>,
        relatedDelayMs: Long = 0L,
        clock: () -> Long = { 50_000_000L },
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
                    querySearchProvider = {
                        if (relatedDelayMs > 0L) delay(relatedDelayMs)
                        catalog.reversed()
                    },
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

    private fun snapshotOf(qm: QueueManager): QueueRefillSnapshot = QueueRefillSnapshot(
        currentSong = qm.current,
        upcomingSongs = qm.upNext.map { it.song },
        userQueueSongs = qm.userQueue.map { it.song },
        historySongs = qm.history,
        queueVersion = qm.queueVersion,
        isAutoplayEnabled = true,
    )

    /**
     * P0 Expected-Failing Reproduction (B2):
     * A stale_queue_version discard leaves AutoplayRefillCoordinator stuck in RefillStatus.IN_FLIGHT,
     * causing all subsequent non-forced refills to return Skip("already_in_flight").
     */
    @Test
    fun reproducesB2_staleDiscardMustNotLeaveStatusInFlightAndBlockSubsequentRefills() = runBlocking {
        val seed = studioSong("s_anchor", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        val recCatalog = (1..12).map { i ->
            studioSong("rec_$i", "Dil Diyan Gallan $i", "Singer $i", album = "Romance Vol $i")
        }
        val engine = buildEngine(recCatalog, relatedDelayMs = 150L)
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(targetUpcomingBuffer = 10, lowWatermarkTrigger = 6, refillBatchSize = 10, minCooldownMs = 0L),
        )

        val qm = QueueManager()
        qm.start(listOf(seed), startIndex = 0)
        val initialSnap = snapshotOf(qm)

        val firstDeferred = async {
            coordinator.requestRefill(initialSnap, latestSnapshotProvider = { snapshotOf(qm) }, force = false)
        }
        delay(35L)
        qm.playNext(studioSong("manual_next", "Pehla Nasha", "Udit Narayan"))

        val firstDecision = firstDeferred.await()
        assertTrue(firstDecision is RefillDecision.Skip)

        val statusAfterDiscard = coordinator.currentState().status
        val followUpSnap = snapshotOf(qm)
        val secondDecision = coordinator.requestRefill(followUpSnap, latestSnapshotProvider = { snapshotOf(qm) }, force = false)

        assertNotEquals(
            "B2 REPRODUCED: coordinator stayed stuck in IN_FLIGHT after stale discard",
            RefillStatus.IN_FLIGHT,
            statusAfterDiscard,
        )
        assertTrue(
            "B2 REPRODUCED: subsequent non-forced refill failed with $secondDecision instead of refilling queue",
            secondDecision is RefillDecision.AppendSongs && secondDecision.songs.isNotEmpty(),
        )
    }

    /**
     * P0 Expected-Failing Reproduction (B1 + B2):
     * With shallow trigger (lowWatermarkTrigger=6) and refill latency slower than track changes,
     * concurrent track advances discard the in-flight refill as stale_queue_version and drain the queue.
     */
    @Test
    fun reproducesB1_slowRefillLongerThanTrackChangesDrainsQueueBeforeRefillLands() = runBlocking {
        val artists = listOf("Arijit Singh", "Atif Aslam", "KK", "Jubin Nautiyal", "Shreya Ghoshal", "Sonu Nigam", "Pritam", "Mithoon")
        val initialTracks = (0..6).map { i ->
            studioSong("init_$i", "Meri Aashiqui $i", artists[i % artists.size], album = "Album $i")
        }
        val remoteCatalog = (1..25).map { i ->
            studioSong("remote_$i", "Khamoshiyan Track $i", artists[(i + 2) % artists.size], album = "Film Vol $i")
        }

        val engine = buildEngine(remoteCatalog, relatedDelayMs = 150L)
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(targetUpcomingBuffer = 10, lowWatermarkTrigger = 6, refillBatchSize = 10, minCooldownMs = 0L),
        )
        val qm = QueueManager()
        qm.start(initialTracks, startIndex = 0)

        var queueDrained = false
        coroutineScope {
            var activeRefillJob: kotlinx.coroutines.Job? = null
            for (step in 1..11) {
                val adv = qm.advance(manual = true)
                if (adv == null) {
                    queueDrained = true
                    break
                }
                if (qm.upNext.size < 6 && (activeRefillJob == null || activeRefillJob.isCompleted)) {
                    val snap = snapshotOf(qm)
                    activeRefillJob = launch {
                        val res = coordinator.requestRefill(snap, latestSnapshotProvider = { snapshotOf(qm) }, force = false)
                        if (res is RefillDecision.AppendSongs) {
                            qm.appendAutoplay(res.songs)
                        }
                    }
                }
                delay(70L)
            }
            activeRefillJob?.join()
        }

        assertFalse(
            "B1 REPRODUCED: shallow trigger + slow refill across track changes drained queue (status=${coordinator.currentState().status})",
            queueDrained,
        )
    }

    /**
     * P0 Diagnostic Benchmark (B3):
     * Measures main-thread cost of PlayerConnection.computeInstantAutoplayMatches
     * against a 640-song user library + catalog fixture.
     */
    @Test
    fun measuresInstantAutoplaySeedLatencyWith600SongLibraryFixture() {
        val artists = listOf("Arijit Singh", "Shreya Ghoshal", "Pritam", "Atif Aslam", "KK", "Armaan Malik", "Jubin Nautiyal", "Sonu Nigam", "Mohit Chauhan", "Alka Yagnik")
        val allSongs = (1..640).map { i ->
            val artist = artists[i % artists.size]
            studioSong(
                id = "lib_$i",
                title = "Dil Ki Baat Chapter $i (From Movie $i)",
                artist = artist,
                album = "Soundtrack ${i / 8}",
                durationSec = 220L + (i % 60),
            )
        }
        InstantTrackCatalog.seed(allSongs.take(500))
        val libData = LibraryData(
            liked = allSongs.subList(0, 200),
            recent = allSongs.subList(200, 260),
            playlists = listOf(
                Playlist(id = "pl_1", name = "Imported Huge 1", songs = allSongs.subList(260, 460)),
                Playlist(id = "pl_2", name = "Imported Huge 2", songs = allSongs.subList(460, 640)),
            ),
            musicLanguages = listOf("Hindi", "English"),
        )
        val activeSongs = allSongs.take(15)
        val seed = studioSong("seed_main", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        val featureRepo = TrackFeatureRepository()

        val tColdStart = System.nanoTime()
        val coldMatches = PlayerConnection.computeInstantAutoplayMatches(
            seed = seed,
            libData = libData,
            activeSongs = activeSongs,
            featureRepository = featureRepo,
            allowArtistHeavy = false,
            limit = 6,
        )
        val coldMs = (System.nanoTime() - tColdStart) / 1_000_000.0

        val warmSamplesMs = (1..5).map {
            val t0 = System.nanoTime()
            val res = PlayerConnection.computeInstantAutoplayMatches(
                seed = seed,
                libData = libData,
                activeSongs = activeSongs,
                featureRepository = featureRepo,
                allowArtistHeavy = false,
                limit = 6,
            )
            assertEquals(6, res.size)
            (System.nanoTime() - t0) / 1_000_000.0
        }
        val warmAvgMs = warmSamplesMs.average()
        println("[P0-BENCHMARK] tryInstantAutoplaySeed(640 songs): cold=${"%.2f".format(coldMs)}ms, warmAvg=${"%.2f".format(warmAvgMs)}ms, matches=${coldMatches.size}")
        assertEquals(6, coldMatches.size)
    }

    @Test
    fun crashHandlerAndPersistentLoggerSurviveUncaughtException() {
        val rootDir = tmp.newFolder("diag_root")
        val logDir = java.io.File(rootDir, "diagnostics")
        val crashDir = java.io.File(rootDir, "crash")

        DiagnosticsLogger.attachLogDirectory(logDir)
        for (i in 1..250) {
            DiagnosticsLogger.i("Autoplay", "Event $i url=https://rr1.googlevideo.com/videoplayback?expire=999&signature=SECRET$i&id=dQw4w9WgXcQ")
        }
        val tail200 = DiagnosticsLogger.tailFormatted(200)
        assertEquals(200, tail200.size)
        assertTrue(tail200.last().contains("Event 250"))
        assertFalse(tail200.any { it.contains("SECRET") })
        assertTrue(java.io.File(logDir, "diagnostics.log").exists())

        // Simulate fresh process reload reading persisted tail
        val reloadedLines = java.io.File(logDir, "diagnostics.log").readLines()
        assertTrue(reloadedLines.any { it.contains("Event 250") })

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var chainedInvoked = false
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> chainedInvoked = true }
            CrashHandler.install(crashDir, versionName = "1.1", versionCode = 248)
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()!!
            currentHandler.uncaughtException(
                Thread("test-main"),
                IllegalStateException("Simulated crash on https://rr2.googlevideo.com/videoplayback?signature=TOK123&id=abcdefghijk"),
            )
            assertTrue("Original default handler must be chained", chainedInvoked)
            val saved = CrashHandler.readLastCrash(crashDir)
            assertNotNull(saved)
            assertTrue(saved!!.contains("IllegalStateException"))
            assertFalse("Sensitive query signature must be redacted in crash file", saved.contains("TOK123"))
            assertTrue(saved.contains("LAST 200 DIAGNOSTIC LOG LINES"))
            assertTrue(saved.contains("Event 250"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            CrashHandler.clearLastCrash(crashDir)
            DiagnosticsLogger.detachLogDirectoryForTest()
        }
    }

    @Test
    fun queueHealthMonitorFormatsMetricsAndCapsLast20Decisions() {
        val anchor = studioSong("q_seed", "Kesariya", "Arijit Singh")
        QueueHealthMonitor.recordQueueState(
            aheadCount = 5,
            userQueueCount = 1,
            anchor = anchor,
            runwayState = RefillStatus.IDLE.name,
            requestedLanguages = setOf("hi"),
            featureRepository = TrackFeatureRepository(),
            catalogSize = 120,
        )
        QueueHealthMonitor.recordEngineDiagnostics(
            diag = RecommendationDiagnostics(
                diagnosticsId = "diag-1",
                requestId = "req-1",
                mode = RecommendationMode.AUTOPLAY_REFILL,
                rawCandidateCount = 42,
                eligibleCandidateCount = 18,
                duplicateRemovedCount = 6,
                rejectedReasonCounts = mapOf(
                    "explicit_language_mismatch" to 5,
                    "devotional_category_mismatch" to 3,
                    "unverified_release_not_allowed:omv" to 9,
                    "explicitly_excluded" to 2,
                ),
                sourceHealth = mapOf(
                    "g1_seed_related" to SourceHealth("g1_seed_related", CandidateSourceStatus.SUCCESS, 180L, 20),
                ),
                averageTransitionScore = 0.88,
                constraintRelaxations = emptyList(),
                returnedCount = 10,
                durationMs = 210L,
                usedCache = false,
                recordedAtMillis = 123456L,
            ),
            seed = anchor,
            featureRepository = TrackFeatureRepository(),
            requestedLanguages = setOf("hi"),
            catalogSize = 120,
        )
        for (i in 1..25) {
            QueueHealthMonitor.recordRefillDecision(
                seedTitle = "Track $i",
                mode = "AUTOPLAY_REFILL",
                outcome = "APPEND",
                reason = "ok",
                appendedCount = 4,
                durationMs = 150L + i,
                queueAheadAfter = 5 + i,
                status = RefillStatus.IDLE,
                timestampMs = 1000L + i,
            )
        }
        val text = QueueHealthMonitor.formatPlainText()
        assertTrue(text.contains("Ahead Count:"))
        assertTrue(text.contains("Runway State:"))
        assertTrue(text.contains("SESSION VIBE"))
        assertTrue(text.contains("language=5"))
        assertTrue(text.contains("not_studio=9"))
        assertTrue(text.contains("g1_seed_related: SUCCESS"))
        assertTrue(text.contains("LAST 20 REFILL DECISIONS"))
        assertEquals(20, QueueHealthMonitor.state.value.recentDecisions.size)
        assertTrue(text.contains("seed=Track 25"))
        assertFalse("Oldest decision beyond 20 must evict", text.lines().any { it.trimEnd().endsWith("seed=Track 1") })
    }

    @Test
    fun watchdogCancelsHungRefillAndResetsStatusFromInFlight() = runBlocking {
        var now = 90_000_000L
        val clock = { now }
        val seed = studioSong("w_seed", "Kesariya", "Arijit Singh")
        val catalog = listOf(studioSong("w_rec", "Tum Hi Ho", "Arijit Singh"))
        var simulateHang = true
        val engine = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(
                    SeedRelatedCandidateSource(
                        relatedProvider = {
                            if (simulateHang) delay(600L)
                            catalog
                        },
                        clock = clock,
                    ),
                ),
                clock = clock,
            ),
            userProfileRepository = DefaultUserProfileRepository(
                eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                libraryDataProvider = { LibraryData() },
                clock = clock,
            ),
            clock = clock,
        )
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(minCooldownMs = 0L),
            clock = clock,
            watchdogTimeoutMs = 90L,
        )
        val snap = QueueRefillSnapshot(
            currentSong = seed,
            upcomingSongs = emptyList(),
            queueVersion = 1L,
            isAutoplayEnabled = true,
        )
        val timedOutDecision = coordinator.requestRefill(snap, { snap }, force = false)
        assertTrue(timedOutDecision is RefillDecision.SafeEmpty)
        assertEquals("watchdog_timeout", (timedOutDecision as RefillDecision.SafeEmpty).reason)
        assertNotEquals(
            "Watchdog timeout must NEVER leave coordinator IN_FLIGHT",
            RefillStatus.IN_FLIGHT,
            coordinator.currentState().status,
        )

        // Same coordinator recovers cleanly on next non-forced refill without getting stuck IN_FLIGHT
        simulateHang = false
        now += 10L
        val recoveredDecision = coordinator.requestRefill(snap, { snap }, force = false)
        assertTrue("Expected recovered AppendSongs, got $recoveredDecision", recoveredDecision is RefillDecision.AppendSongs)
        assertEquals(RefillStatus.IDLE, coordinator.currentState().status)
    }
}
