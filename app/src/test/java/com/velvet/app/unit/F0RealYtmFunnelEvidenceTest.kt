package com.velvet.app.unit

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.QueueManager
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.InstantTrackCatalogCandidateSource
import com.velvet.app.recommendation.candidates.LibraryIndex
import com.velvet.app.recommendation.candidates.LibraryTrackCandidateSource
import com.velvet.app.recommendation.candidates.RollingCandidatePool
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.recommendation.vibe.ArtistLanguageRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * F0 Evidence & Instrumentation Verification:
 * - Parses 5 REAL trimmed YT Music /next + continuation JSON responses
 *   (hindi_90s, english_pop, tamil, artist_radio, devotional).
 * - Measures exact stage-by-stage refill funnel numbers and library share
 *   without altering recommendation behaviour.
 * - Verifies last-30 funnel ring buffer, plain-text export, and redaction.
 */
class F0RealYtmFunnelEvidenceTest {

    private data class SeedFixtureSpec(
        val key: String,
        val seed: Song,
        val mode: RecommendationMode,
        val userLanguages: Set<String>,
    )

    private val specs = listOf(
        SeedFixtureSpec(
            key = "hindi_90s",
            seed = topicSong(
                id = "6BofSu4xedk",
                title = "Tumko Dekha Toh",
                artist = "Alka Yagnik, Kumar Sanu",
                album = "Hamara Dil Aapke Paas Hai (Original Motion Picture Soundtrack)",
                durationSec = 276L,
            ),
            mode = RecommendationMode.AUTOPLAY_REFILL,
            userLanguages = setOf("hi", "en"),
        ),
        SeedFixtureSpec(
            key = "english_pop",
            seed = topicSong(
                id = "J7p4bzqLvCw",
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "Blinding Lights",
                durationSec = 202L,
            ),
            mode = RecommendationMode.AUTOPLAY_REFILL,
            userLanguages = setOf("hi", "en"),
        ),
        SeedFixtureSpec(
            key = "tamil",
            seed = topicSong(
                id = "geIAO3PrE2g",
                title = "Why This Kolaveri Di? (The Soup of Love)",
                artist = "Anirudh Ravichander, Dhanush",
                album = "Why This Kolaveri Di? (The Soup of Love)",
                durationSec = 260L,
            ),
            mode = RecommendationMode.AUTOPLAY_REFILL,
            userLanguages = setOf("hi", "en", "ta"),
        ),
        SeedFixtureSpec(
            key = "artist_radio",
            seed = topicSong(
                id = "fsiPzT50ZiM",
                title = "Tum Hi Ho",
                artist = "Arijit Singh",
                album = "Aashiqui 2",
                durationSec = 262L,
            ),
            mode = RecommendationMode.ARTIST_RADIO,
            userLanguages = setOf("hi", "en"),
        ),
        SeedFixtureSpec(
            key = "devotional",
            seed = topicSong(
                id = "8MGrTEUgBwY",
                title = "Shree Hanuman Chalisa",
                artist = "Hariharan",
                album = "Shree Hanuman Chalisa (Hanuman Ashtak)",
                durationSec = 587L,
            ),
            mode = RecommendationMode.AUTOPLAY_REFILL,
            userLanguages = setOf("hi", "en"),
        ),
    )

    @Before
    fun resetState() {
        DiagnosticsLogger.clear()
        DiagnosticsLogger.detachLogDirectoryForTest()
        QueueHealthMonitor.clearForTest()
        InstantTrackCatalog.clear()
        ArtistLanguageRegistry.clearForTest()
        YouTubeMusicInnertube.clearNextStatsForTest()
    }

    private fun topicSong(
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
        isUploaderVerified = true,
        rawUploaderName = artist,
        preferredMusicSource = true,
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    private fun loadFixtureJson(path: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
            ?: error("Missing test fixture resource: $path")
        return InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            JsonParser.`object`().from(reader)
        }
    }

    private fun loadFixturePages(spec: SeedFixtureSpec): Pair<YouTubeMusicInnertube.NextRadioPage, YouTubeMusicInnertube.NextRadioPage> {
        val initRoot = loadFixtureJson("fixtures/ytm/${spec.key}_next_initial.json")
        val contRoot = loadFixtureJson("fixtures/ytm/${spec.key}_next_cont1.json")
        val page1 = YouTubeMusicInnertube.parseNextRadioPageJson(initRoot, excludeVideoId = spec.seed.id)
        val page2 = YouTubeMusicInnertube.parseContinuationRadioPageJson(contRoot, excludeVideoId = spec.seed.id)
        return page1 to page2
    }

    private fun buildSimulatedUserPhoneLibrary(): List<Song> = buildList {
        // Simulates a realistic user's Liked / Instant catalog with Hindi + English + Punjabi songs
        val entries = listOf(
            Triple("lib_hi_1", "Channa Mereya", "Arijit Singh"),
            Triple("lib_hi_2", "Agar Tum Saath Ho", "Alka Yagnik, Arijit Singh"),
            Triple("lib_hi_3", "Pehla Nasha", "Udit Narayan, Sadhana Sargam"),
            Triple("lib_hi_4", "Tere Naam", "Udit Narayan, Alka Yagnik"),
            Triple("lib_hi_5", "Kal Ho Naa Ho", "Sonu Nigam"),
            Triple("lib_hi_6", "Ghar More Pardesiya", "Shreya Ghoshal"),
            Triple("lib_hi_7", "Raabta", "Arijit Singh"),
            Triple("lib_hi_8", "Tujh Mein Rab Dikhta Hai", "Roop Kumar Rathod"),
            Triple("lib_en_1", "Shape of You", "Ed Sheeran"),
            Triple("lib_en_2", "Levitating", "Dua Lipa"),
            Triple("lib_en_3", "Yellow", "Coldplay"),
            Triple("lib_en_4", "Anti-Hero", "Taylor Swift"),
            Triple("lib_en_5", "As It Was", "Harry Styles"),
            Triple("lib_pa_1", "Excuses", "AP Dhillon"),
            Triple("lib_pa_2", "295", "Sidhu Moose Wala"),
            Triple("lib_dev_1", "Shiv Tandav Stotram", "Shankar Mahadevan"),
            Triple("lib_dev_2", "Achyutam Keshavam", "Vikram Hazra"),
        )
        entries.forEachIndexed { idx, (id, title, artist) ->
            add(
                topicSong(
                    id = id,
                    title = title,
                    artist = artist,
                    album = "User Library Album ${idx % 4}",
                    durationSec = 240L,
                ),
            )
        }
    }

    @Test
    fun realYtmNextAndContinuationFixturesParseCleanlyWithValidTokens() {
        for (spec in specs) {
            val (page1, page2) = loadFixturePages(spec)
            val combinedUnique = (page1.songs + page2.songs).distinctBy { it.id }
            val newInCont1 = combinedUnique.size - page1.songs.size
            assertTrue("${spec.key} page1 should parse studio tracks", page1.songs.isNotEmpty())
            assertNotNull("${spec.key} page1 should expose continuationToken (hasNext=true)", page1.continuationToken)
            assertTrue("${spec.key} cont1 should parse studio tracks", page2.songs.isNotEmpty())
            assertNotNull("${spec.key} cont1 should expose continuationToken (hasNext=true)", page2.continuationToken)
            println(
                "[F0-PARSE] ${spec.key}: page1=${page1.songs.size} hasNext=${page1.continuationToken != null} " +
                    "cont1=${page2.songs.size} newUniqueInCont1=$newInCont1 relatedId=${page1.relatedBrowseId}",
            )
        }
    }

    @Test
    fun realYtmFixturesThroughEngineMeasureColdAndLibraryFunnelNumbers() = runBlocking {
        val phoneLibrary = buildSimulatedUserPhoneLibrary()
        val featureRepo = TrackFeatureRepository()

        for (spec in specs) {
            val (page1, page2) = loadFixturePages(spec)
            val mergedTwoPages = (page1.songs + page2.songs).distinctBy { it.id }

            // Measure BOTH (A) Cold start (g1_related only) and (B) Warm phone library (g1 + g6 + g0 + fallbackAnchorPool)
            for (warmLibrary in listOf(false, true)) {
                QueueHealthMonitor.clearForTest()
                InstantTrackCatalog.clear()
                val libData = if (warmLibrary) LibraryData(liked = phoneLibrary) else LibraryData()
                val libIndex = LibraryIndex(featureRepo).apply { updateIncremental(libData) }
                if (warmLibrary) {
                    InstantTrackCatalog.seed(phoneLibrary)
                }

                val qm = QueueManager()
                qm.start(listOf(spec.seed), startIndex = 0)
                val refillCallCount = AtomicInteger(0)

                val poolBuilder = CandidatePoolBuilder(
                    sources = listOf(
                        InstantTrackCatalogCandidateSource(
                            extraSongsProvider = { if (warmLibrary) libIndex.snapshot.allVerifiedSongs else emptyList() },
                            featureRepository = featureRepo,
                        ),
                        SeedRelatedCandidateSource(
                            relatedProvider = {
                                // Match StreamResolver behavior: 1st call returns page1; subsequent calls have cont1 appended after page1
                                if (refillCallCount.getAndIncrement() == 0) page1.songs else mergedTwoPages
                            },
                        ),
                        LibraryTrackCandidateSource(
                            libraryTracksProvider = { if (warmLibrary) phoneLibrary else emptyList() },
                            featureRepository = featureRepo,
                        ),
                    ),
                    featureRepository = featureRepo,
                    cacheTtlMs = 0L,
                )
                val engine = DefaultRecommendationEngine(
                    candidatePoolBuilder = poolBuilder,
                    userProfileRepository = DefaultUserProfileRepository(
                        eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                        libraryDataProvider = { libData },
                    ),
                    featureRepository = featureRepo,
                )
                val rollingPool = RollingCandidatePool()
                val coordinator = AutoplayRefillCoordinator(
                    engine = engine,
                    policy = RefillPolicy(
                        targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                        lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                        refillBatchSize = RunwayPolicy.MAX_BATCH,
                        minCooldownMs = 0L,
                    ),
                    featureRepository = featureRepo,
                    rollingPool = rollingPool,
                )

                fun snapshot() = QueueRefillSnapshot(
                    currentSong = qm.current,
                    upcomingSongs = qm.upNext.map { it.song },
                    historySongs = qm.history,
                    queueVersion = qm.queueVersion,
                    isAutoplayEnabled = true,
                    isRadioActive = true,
                    isArtistRadioActive = spec.mode == RecommendationMode.ARTIST_RADIO,
                    radioSeedSong = spec.seed,
                    preferredLanguageIds = spec.userLanguages,
                    allowMixedLanguages = false,
                    fallbackAnchorPool = if (warmLibrary) phoneLibrary else emptyList(),
                )

                // Simulate 25 real sequential track advances, triggering refill whenever ahead < REFILL_BELOW (15)
                repeat(25) {
                    val snap = snapshot()
                    if (snap.upcomingSongs.size < RunwayPolicy.REFILL_BELOW) {
                        val decision = coordinator.requestRefill(snap, ::snapshot, force = qm.upNext.isEmpty())
                        if (decision is RefillDecision.AppendSongs && decision.songs.isNotEmpty()) {
                            qm.appendAutoplay(decision.songs)
                        }
                    }
                    qm.advance(manual = false)
                }

                val funnels = QueueHealthMonitor.recentFunnels(30).reversed()
                assertTrue("${spec.key} (warm=$warmLibrary) must record at least 1 refill funnel", funnels.isNotEmpty())
                val activeFunnels = funnels.filter { it.decision != "SKIP" }.ifEmpty { funnels }
                val n = activeFunnels.size.toDouble()
                val avgFetched = activeFunnels.sumOf { it.totalFunnel.fetched } / n
                val avgElig = activeFunnels.sumOf { it.totalFunnel.afterEligibility } / n
                val avgDedupe = activeFunnels.sumOf { it.totalFunnel.afterIdentityDedupe } / n
                val avgLang = activeFunnels.sumOf { it.totalFunnel.afterLanguageGate } / n
                val avgGenre = activeFunnels.sumOf { it.totalFunnel.afterGenreTypeGate } / n
                val avgPurity = activeFunnels.sumOf { it.totalFunnel.afterArtistPurity } / n
                val avgAcc = activeFunnels.sumOf { it.totalFunnel.accepted } / n
                val totalAcc = activeFunnels.sumOf { it.totalFunnel.accepted }.coerceAtLeast(1)
                val totalLib = activeFunnels.sumOf { it.libraryOrInstantAcceptedCount }
                val libSharePct = (totalLib * 100.0) / totalAcc
                println(
                    "[F0-AVG] seed=${spec.key} warmLib=$warmLibrary activeRefills=${activeFunnels.size} (all=${funnels.size}) | " +
                        "fetched=${"%.1f".format(avgFetched)} elig=${"%.1f".format(avgElig)} " +
                        "dedupe=${"%.1f".format(avgDedupe)} lang=${"%.1f".format(avgLang)} " +
                        "genre=${"%.1f".format(avgGenre)} purity=${"%.1f".format(avgPurity)} " +
                        "acc=${"%.1f".format(avgAcc)} libShare=${"%.1f".format(libSharePct)}% ($totalLib/$totalAcc)",
                )
                activeFunnels.forEachIndexed { idx, f ->
                    println(
                        "  refill#${idx + 1} ${f.decision}(${f.decisionReason}) stepped=${f.wasSteppedAnchor} | " +
                            "${f.totalFunnel.formatLine()} | lib=${f.libraryOrInstantAcceptedCount}/${f.totalFunnel.accepted} | srcs=${f.acceptedSourceIds} | rejects=${f.rejectReasonCounts}",
                    )
                    f.perGeneratorFunnels.toSortedMap().values.forEach { g ->
                        println("     gen -> ${g.formatLine()}")
                    }
                }
            }
        }
    }

    @Test
    fun queueHealthFunnelRingBufferCapsAt30AndRedactsSensitiveStrings() = runBlocking {
        val firstSpec = specs.first()
        val (page1, _) = loadFixturePages(firstSpec)
        val sensitiveSeed = firstSpec.seed.copy(
            title = "Secret Song https://rr1.googlevideo.com/videoplayback?signature=BADSIG&expire=999&id=abcdefghijk ; Authorization: Bearer ya29.SECRET_TOKEN_XYZ",
        )
        val engine = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(SeedRelatedCandidateSource(relatedProvider = { page1.songs })),
                cacheTtlMs = 0L,
            ),
            userProfileRepository = DefaultUserProfileRepository(
                eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                libraryDataProvider = { LibraryData() },
            ),
        )
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 0L,
            ),
        )

        val snap = QueueRefillSnapshot(
            currentSong = sensitiveSeed,
            upcomingSongs = emptyList(),
            queueVersion = 1L,
            isAutoplayEnabled = true,
        )
        repeat(35) {
            coordinator.requestRefill(snap, { snap }, force = true)
        }

        val stored = QueueHealthMonitor.recentFunnels(30)
        assertEquals("Ring buffer must store at most 30 recent refill funnels", 30, stored.size)
        val plain = QueueHealthMonitor.formatLastFunnelsPlainText(30)
        assertTrue(plain.contains("VELVET LAST 30 REFILL FUNNELS"))
        assertTrue(plain.contains("TOTAL: fetched="))
        assertTrue(plain.contains("contPages="))
        assertFalse("Bearer token must be redacted", plain.contains("SECRET_TOKEN_XYZ"))
        assertFalse("Signed URL signature must be redacted", plain.contains("BADSIG"))
    }
}
