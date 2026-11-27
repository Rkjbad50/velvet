package com.velvet.app.unit

import com.grack.nanojson.JsonParser
import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.QueueManager
import com.velvet.app.recommendation.candidates.ArtistCatalogueCandidateSource
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.CandidateSourceRequest
import com.velvet.app.recommendation.candidates.CollaboratorCandidateSource
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.RollingCandidatePool
import com.velvet.app.recommendation.candidates.SameReleaseCandidateSource
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
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.recommendation.vibe.ArtistLanguageRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * F3 verification:
 * - Multi-page continuation pulling in StreamResolver (>= 60 raw candidates, fresh pages prepended before cached page1).
 * - Fixed-order gate degradation ladder when verified survivors < 40 (near-duplicate unplayed -> era -> mood, never language).
 * - Chained session seed fan-out & expanding G2/G3/G4/G5 candidate generators.
 * - Background rolling candidate pool warm-up + local-first refill sustaining 200 advances without dropping below REFILL_BELOW (15).
 */
class F3SupplyAndContinuationTest {

    @Before
    fun setUp() {
        DiagnosticsLogger.clear()
        DiagnosticsLogger.detachLogDirectoryForTest()
        QueueHealthMonitor.clearForTest()
        InstantTrackCatalog.clear()
        ArtistLanguageRegistry.clearForTest()
        StreamResolver.clearRelatedCacheForTest()
        StreamResolver.nextRadioPageProviderForTest = null
    }

    @After
    fun tearDown() {
        StreamResolver.nextRadioPageProviderForTest = null
        StreamResolver.clearRelatedCacheForTest()
    }

    private fun topicSong(
        id: String,
        title: String,
        artist: String,
        album: String? = "Bollywood Melodies",
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

    private fun loadRealF0FixturePages(key: String, seedId: String): Pair<YouTubeMusicInnertube.NextRadioPage, YouTubeMusicInnertube.NextRadioPage> {
        val loader = javaClass.classLoader!!
        val initRoot = InputStreamReader(loader.getResourceAsStream("fixtures/ytm/${key}_next_initial.json")!!, Charsets.UTF_8).use {
            JsonParser.`object`().from(it)
        }
        val contRoot = InputStreamReader(loader.getResourceAsStream("fixtures/ytm/${key}_next_cont1.json")!!, Charsets.UTF_8).use {
            JsonParser.`object`().from(it)
        }
        return YouTubeMusicInnertube.parseNextRadioPageJson(initRoot, seedId) to
            YouTubeMusicInnertube.parseContinuationRadioPageJson(contRoot, seedId)
    }

    private fun uniqueHindiCatalog(prefix: String, count: Int, startIdx: Int = 0): List<Song> {
        val wordsA = listOf(
            "Meethi", "Gehri", "Chandni", "Savera", "Anjani", "Sunehri", "Bheegi", "Chupke",
            "Khwabi", "Roshni", "Sargam", "Dastaan", "Muskaan", "Pehli", "Nayi", "Sard",
            "Halke", "Dhalti", "Uljhi", "Pyari", "Awaaz", "Tarana", "Lamha", "Subah",
        )
        val wordsB = listOf(
            "Baarish", "Hawayein", "Raatein", "Khamoshi", "Manzil", "Kahaani", "Parchhai", "Yaadein",
            "Duniya", "Sapne", "Nazar", "Geet", "Wada", "Meet", "Saath", "Safar",
            "Dhadkan", "Aashiqui", "Prem", "Ishq", "Dil", "Pal", "Rang", "Deep",
        )
        val artists = listOf(
            "Kumar Sanu", "Udit Narayan", "Alka Yagnik", "Sonu Nigam", "Abhijeet",
            "Kavita Krishnamurthy", "Sadhana Sargam", "Lata Mangeshkar", "Kishore Kumar",
            "Mohammed Rafi", "Shaan", "KK", "Arijit Singh", "Shreya Ghoshal", "Atif Aslam",
            "Jubin Nautiyal", "Mohit Chauhan", "Sunidhi Chauhan", "Armaan Malik", "Babul Supriyo",
        )
        return (0 until count).map { offset ->
            val i = startIdx + offset
            val code = "${'A' + (i / 676) % 26}${'a' + (i / 26) % 26}${'A' + (i % 26)}${'z' - ((i * 3) % 26)}${'a' + ((i * 7) % 26)}"
            topicSong(
                id = "${prefix}_$i",
                title = "${wordsA[i % wordsA.size]} ${wordsB[(i / wordsA.size) % wordsB.size]} $code",
                artist = artists[i % artists.size],
                album = "90s Classics Vol ${i % 6}",
            )
        }
    }

    private fun uniqueDomainCatalog(
        prefix: String,
        languageKey: String,
        count: Int,
        startIdx: Int = 0,
    ): List<Song> {
        if (languageKey == "hi") return uniqueHindiCatalog(prefix, count, startIdx)
        val artists = when (languageKey) {
            "en" -> listOf(
                "The Weeknd", "Dua Lipa", "Ed Sheeran", "Taylor Swift", "Coldplay",
                "Bruno Mars", "Ariana Grande", "Billie Eilish", "Harry Styles", "Imagine Dragons",
                "Maroon 5", "Charlie Puth", "Sia", "Post Malone", "Shawn Mendes",
                "Adele", "Sam Smith", "Olivia Rodrigo", "Katy Perry", "Justin Timberlake",
            )
            else -> listOf(
                "Anirudh Ravichander", "A.R. Rahman", "Yuvan Shankar Raja", "Harris Jayaraj",
                "Sid Sriram", "Dhanush", "Karthik", "Shreya Ghoshal", "Chinmayi",
                "Ilaiyaraaja", "G.V. Prakash", "S.P. Balasubrahmanyam", "Haricharan", "Vijay Yesudas",
                "Santhosh Narayanan", "Saindhavi", "Pradeep Kumar", "Naresh Iyer", "K.S. Chithra", "Unni Menon",
            )
        }
        val adj = listOf("Midnight", "Golden", " Velvet", "Velvet", "Electric", "Silver", "Crystal", "Starlight", "Neon", "Summer", "Winter", "Ocean", "Skyline", "Echo", "Mirage", "Amber")
        val noun = listOf("Dreams", "Waves", "Horizon", "Pulse", "Light", "Motion", "Heart", "Rhythm", "Melody", "Voices", "Fire", "Shadow", "Rain", "Glow", "Breeze", "Dawn")
        return (0 until count).map { offset ->
            val i = startIdx + offset
            val code = "${'A' + (i / 676) % 26}${'a' + (i / 26) % 26}${'A' + (i % 26)}${'z' - ((i * 5) % 26)}"
            val tag = if (languageKey == "ta") "Tamil Cinema" else "Pop Edition"
            topicSong(
                id = "${prefix}_$i",
                title = "${adj[i % adj.size].trim()} ${noun[(i / adj.size) % noun.size]} $code",
                artist = artists[i % artists.size],
                album = "$tag Vol ${i % 5}",
            )
        }
    }

    @Test
    fun streamResolverRelatedWithContinuation_pullsMultiplePagesUpTo60AndPrependsFreshPages() {
        val seed = topicSong("6BofSu4xedk", "Tumko Dekha Toh", "Alka Yagnik, Kumar Sanu")
        val (page1, cont1Real) = loadRealF0FixturePages("hindi_90s", seed.id)
        val page2Extra = uniqueHindiCatalog("sr_c2", 25, startIdx = 0)
        val page3Extra = uniqueHindiCatalog("sr_c3", 25, startIdx = 25)
        val page4Extra = uniqueHindiCatalog("sr_c4", 25, startIdx = 50)

        val fetchedTokens = mutableListOf<String?>()
        StreamResolver.nextRadioPageProviderForTest = { _, tok ->
            fetchedTokens += tok
            when (tok) {
                null -> YouTubeMusicInnertube.NextRadioPage(page1.songs, continuationToken = "TOK_1")
                "TOK_1" -> YouTubeMusicInnertube.NextRadioPage(cont1Real.songs, continuationToken = "TOK_2")
                "TOK_2" -> YouTubeMusicInnertube.NextRadioPage(page2Extra, continuationToken = "TOK_3")
                "TOK_3" -> YouTubeMusicInnertube.NextRadioPage(page3Extra, continuationToken = "TOK_4")
                "TOK_4" -> YouTubeMusicInnertube.NextRadioPage(page4Extra, continuationToken = null)
                else -> YouTubeMusicInnertube.NextRadioPage(emptyList())
            }
        }

        // Initial fetch pulls up to minRawCandidates = 95 across multiple continuation pages (beyond TOK_1)
        val initial = StreamResolver.relatedWithContinuation(
            videoId = seed.id,
            seedHint = seed,
            loadNextPage = true,
            minRawCandidates = 95,
            maxContinuationPages = 3,
        )
        assertTrue("Initial call should accumulate >= 60 raw candidates via continuations (got ${initial.size})", initial.size >= 60)
        assertTrue("Should have paged beyond TOK_1 (fetchedTokens=$fetchedTokens)", fetchedTokens.contains("TOK_1") && fetchedTokens.contains("TOK_2"))

        // Subsequent call fetches the next unread continuation token and places fresh songs at the FRONT ahead of page1
        val tokensBeforeSecondCall = fetchedTokens.size
        val next = StreamResolver.relatedWithContinuation(
            videoId = seed.id,
            seedHint = seed,
            loadNextPage = true,
            minRawCandidates = 20,
            maxContinuationPages = 1,
        )
        val newlyFetchedTok = fetchedTokens.drop(tokensBeforeSecondCall).lastOrNull()
        val expectedFreshPrefix = when (newlyFetchedTok) {
            "TOK_2" -> "sr_c2_"
            "TOK_3" -> "sr_c3_"
            "TOK_4" -> "sr_c4_"
            else -> error("Unexpected continuation token fetched on second call: $newlyFetchedTok (all=$fetchedTokens)")
        }
        val firstFreshIdx = next.indexOfFirst { it.id.startsWith(expectedFreshPrefix) }
        val firstOldPage1Idx = next.indexOfFirst { it.id == page1.songs.first().id }
        assertTrue(
            "Fresh continuation tracks ($expectedFreshPrefix) must be placed before cached page1 ($firstFreshIdx vs $firstOldPage1Idx)",
            firstFreshIdx in 0 until firstOldPage1Idx,
        )
    }

    @Test
    fun gateDegradationLadder_runsInExactFixedOrderAndNeverRelaxesLanguageOrDevotional() = runBlocking {
        val featureRepo = TrackFeatureRepository()
        val seed = topicSong("seed_hi", "Tumko Dekha Toh", "Kumar Sanu", album = "Sanam (1997)")

        // Case A: 45 unique eligible songs -> zero relaxation needed
        val base45 = uniqueHindiCatalog("gd_a", 45, startIdx = 0)
        val engineA = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(SeedRelatedCandidateSource(relatedProvider = { base45 })),
                featureRepository = featureRepo,
                cacheTtlMs = 0L,
            ),
            userProfileRepository = DefaultUserProfileRepository(
                eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                libraryDataProvider = { LibraryData() },
            ),
            featureRepository = featureRepo,
        )
        val resA = engineA.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 20, requestedLanguageIds = setOf("hi"))
        val diagA = engineA.diagnosticsLog.recent(1).first()
        assertFalse(diagA.constraintRelaxations.contains("near_duplicate_unplayed_uploads"))
        assertFalse(diagA.constraintRelaxations.contains("widen_era"))
        assertFalse(diagA.constraintRelaxations.contains("widen_mood"))
        assertEquals(20, resA.candidates.size)

        // Case B: 25 unique songs + 20 unplayed alternate verified uploads of those 25 -> Step 1 ONLY rescues to >= 40
        val unique25 = uniqueHindiCatalog("gd_b", 25, startIdx = 100)
        val alternate20 = unique25.take(20).mapIndexed { idx, s ->
            s.copy(id = "gd_b_alt_$idx", album = "${s.album} (Deluxe Edition)")
        }
        val engineB = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(SeedRelatedCandidateSource(relatedProvider = { unique25 + alternate20 })),
                featureRepository = featureRepo,
                cacheTtlMs = 0L,
            ),
            userProfileRepository = DefaultUserProfileRepository(
                eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                libraryDataProvider = { LibraryData() },
            ),
            featureRepository = featureRepo,
        )
        val resB = engineB.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 20, requestedLanguageIds = setOf("hi"))
        val diagB = engineB.diagnosticsLog.recent(1).first()
        assertTrue("Expected Step 1 near_duplicate_unplayed_uploads, got ${diagB.constraintRelaxations}", "near_duplicate_unplayed_uploads" in diagB.constraintRelaxations)
        assertFalse("Must stop at Step 1 without widening era: ${diagB.constraintRelaxations}", "widen_era" in diagB.constraintRelaxations)
        assertFalse("Must stop at Step 1 without widening mood: ${diagB.constraintRelaxations}", "widen_mood" in diagB.constraintRelaxations)
        val batchCompKeys = resB.candidates.map { RecordingIdentityResolver.canonicalCompositionTitleKey(it.song) }.toSet()
        assertEquals("Emitted batch must have zero composition repeats", resB.candidates.size, batchCompKeys.size)

        // Case C: < 40 survivors even after Step 1 triggers Step 2 (widen_era) and Step 3 (widen_mood),
        // yet language + devotional safety ALWAYS stay strict
        val devSeed = topicSong("dev_seed", "Shree Hanuman Chalisa", "Hariharan", album = "Hanuman Chalisa", durationSec = 520L)
        val validDevotional = (0 until 10).map { i ->
            topicSong("dev_ok_$i", "Shiv Stotram Bhajan Mala $i", "Hariharan", album = "Bhakti Classics")
        }
        val partyTrack = topicSong("bad_party", "Aaj Ki Party Dance Club Mix", "Mika Singh", album = "Party Hits")
        val englishTrack = topicSong("bad_en", "Blinding Lights", "The Weeknd", album = "After Hours")
        val southDevotional = topicSong("bad_ta", "Kanda Sashti Kavacham", "Soolamangalam Sisters", album = "Tamil Devotional")
        val engineC = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(SeedRelatedCandidateSource(relatedProvider = { validDevotional + partyTrack + englishTrack + southDevotional })),
                featureRepository = featureRepo,
                cacheTtlMs = 0L,
            ),
            userProfileRepository = DefaultUserProfileRepository(
                eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                libraryDataProvider = { LibraryData() },
            ),
            featureRepository = featureRepo,
        )
        val resC = engineC.nextSimilar(devSeed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 15, requestedLanguageIds = setOf("hi"))
        val diagC = engineC.diagnosticsLog.recent(1).first()
        val idx1 = diagC.constraintRelaxations.indexOf("near_duplicate_unplayed_uploads")
        val idx2 = diagC.constraintRelaxations.indexOf("widen_era")
        val idx3 = diagC.constraintRelaxations.indexOf("widen_mood")
        assertTrue("Fixed ladder order 1 -> 2 -> 3 must hold: ${diagC.constraintRelaxations}", idx1 in 0 until idx2 && idx2 < idx3)
        val returnedIdsC = resC.candidates.map { it.song.id }.toSet()
        assertFalse("Language mismatch English must NEVER leak on degradation", "bad_en" in returnedIdsC)
        assertFalse("South regional devotional mismatch must NEVER leak on degradation", "bad_ta" in returnedIdsC)
        assertFalse("Devotional + Party mood must NEVER leak even when widenMood=true", "bad_party" in returnedIdsC)
    }

    @Test
    fun chainedSessionSeedsAndExpandingGenerators_expandSupplyCleanly() = runBlocking {
        val featureRepo = TrackFeatureRepository()
        val seed0 = topicSong("ch_0", "Tumko Dekha Toh", "Kumar Sanu, Alka Yagnik", album = "Hamara Dil (Original Motion Picture Soundtrack)")
        val seed1 = topicSong("ch_1", "Chura Ke Dil Mera", "Kumar Sanu, Alka Yagnik", album = "Main Khiladi (Deluxe Edition)")
        val seed2 = topicSong("ch_2", "Pehla Nasha", "Udit Narayan, Sadhana Sargam", album = "Jo Jeeta Wohi Sikandar")

        // When primary seed is sparse (< 25 unexcluded), G1 fans out to secondary chained session seeds and lifts un-excluded tracks first
        val excludedKnown = uniqueHindiCatalog("excl_old", 12, startIdx = 0)
        val providerCalls = mutableListOf<String>()
        val g1 = SeedRelatedCandidateSource(
            relatedProvider = { s ->
                providerCalls += s.id
                when (s.id) {
                    seed0.id -> excludedKnown + uniqueHindiCatalog("ch_p0", 5, startIdx = 50)
                    seed1.id -> uniqueHindiCatalog("ch_p1", 12, startIdx = 70)
                    seed2.id -> uniqueHindiCatalog("ch_p2", 12, startIdx = 90)
                    else -> emptyList()
                }
            },
        )
        val reqSparse = RecommendationRequest(
            requestId = "r-chain",
            mode = RecommendationMode.AUTOPLAY_REFILL,
            seedSongs = listOf(seed0, seed1, seed2),
            excludedProviderIds = excludedKnown.mapTo(HashSet()) { it.id },
        )
        val resG1 = g1.fetch(CandidateSourceRequest(reqSparse, emptyList(), DefaultUserProfileRepository(RecommendationEventRepository(InMemoryEventBackend()), { LibraryData() }).getSnapshot(), maxCandidates = 35, deadlineMillis = Long.MAX_VALUE))
        assertTrue("Expected chained seed fan-out across seed0, seed1, seed2 (calls=$providerCalls)", providerCalls.containsAll(listOf(seed0.id, seed1.id, seed2.id)))
        assertTrue("First G1 candidate must be un-excluded rather than stale cached excluded track", resG1.candidates.first().song.id !in reqSparse.excludedProviderIds)

        // When primary returns >= 25 un-excluded, G1 keeps a single fast call
        val singleCalls = AtomicInteger(0)
        val g1Fast = SeedRelatedCandidateSource(
            relatedProvider = {
                singleCalls.incrementAndGet()
                uniqueHindiCatalog("fast28", 28, startIdx = 120)
            },
        )
        g1Fast.fetch(CandidateSourceRequest(reqSparse.copy(excludedProviderIds = emptySet()), emptyList(), DefaultUserProfileRepository(RecommendationEventRepository(InMemoryEventBackend()), { LibraryData() }).getSnapshot(), maxCandidates = 40, deadlineMillis = Long.MAX_VALUE))
        assertEquals("Primary with >= 25 un-excluded songs should not extra-call secondary seeds", 1, singleCalls.get())

        // G2 cleans soundtrack suffixes & checks second seed when needed; G3 admits up to 12 in normal mode; G4 scans multiple seeds; G5 rotates up to 3 queries
        val seenQueries = mutableListOf<String>()
        val g2 = SameReleaseCandidateSource(releaseTracksProvider = { q -> seenQueries += q; uniqueHindiCatalog("rel", 4) })
        g2.fetch(CandidateSourceRequest(reqSparse, emptyList(), DefaultUserProfileRepository(RecommendationEventRepository(InMemoryEventBackend()), { LibraryData() }).getSnapshot(), maxCandidates = 20, deadlineMillis = Long.MAX_VALUE))
        assertFalse("Soundtrack noise suffix must be stripped: $seenQueries", seenQueries.first().contains("Original Motion Picture Soundtrack", ignoreCase = true))

        val g3 = ArtistCatalogueCandidateSource(artistTracksProvider = { uniqueHindiCatalog("art", 20) })
        val g3Res = g3.fetch(CandidateSourceRequest(reqSparse, emptyList(), DefaultUserProfileRepository(RecommendationEventRepository(InMemoryEventBackend()), { LibraryData() }).getSnapshot(), maxCandidates = 20, deadlineMillis = Long.MAX_VALUE))
        assertEquals("G3 normal cap raised from 5 to 12", 12, g3Res.candidates.size)

        val g4 = CollaboratorCandidateSource(artistTracksProvider = { co -> listOf(topicSong("co_$co", "Co Song $co", co)) })
        val g4Res = g4.fetch(CandidateSourceRequest(reqSparse, emptyList(), DefaultUserProfileRepository(RecommendationEventRepository(InMemoryEventBackend()), { LibraryData() }).getSnapshot(), maxCandidates = 20, deadlineMillis = Long.MAX_VALUE))
        assertTrue("G4 should gather collaborators across session seeds", g4Res.candidates.size >= 2)

        val g5Queries = mutableListOf<String>()
        val g5 = GenreLanguageNeighbourSource(querySearchProvider = { q -> g5Queries += q; emptyList() })
        val seedFeat = featureRepo.getCachedOrExtract(seed0)
        g5.fetch(CandidateSourceRequest(reqSparse.copy(requestedLanguageIds = setOf("hi")), listOf(seedFeat), DefaultUserProfileRepository(RecommendationEventRepository(InMemoryEventBackend()), { LibraryData() }).getSnapshot(), maxCandidates = 60, deadlineMillis = Long.MAX_VALUE))
        assertEquals("G5 should issue 3 distinct rotating language-strict queries", 3, g5Queries.distinct().size)
        assertTrue("All G5 queries must preserve Hindi language focus: $g5Queries", g5Queries.all { "Hindi" in it })
    }

    @Test
    fun realF0FixturesPlusPagedContinuationsAndBackgroundPool_sustains200AdvancesAboveRefillBelow() = runBlocking {
        val scenarios = listOf(
            Triple("hindi_90s", "hi", setOf("hi", "en")),
            Triple("english_pop", "en", setOf("hi", "en")),
            Triple("tamil", "ta", setOf("hi", "en", "ta")),
        )
        val featureRepo = TrackFeatureRepository()

        for ((key, langKey, userLangs) in scenarios) {
            QueueHealthMonitor.clearForTest()
            InstantTrackCatalog.clear()

            val seed = when (key) {
                "hindi_90s" -> topicSong("6BofSu4xedk", "Tumko Dekha Toh", "Alka Yagnik, Kumar Sanu", "Hamara Dil Aapke Paas Hai", 276L)
                "english_pop" -> topicSong("J7p4bzqLvCw", "Blinding Lights", "The Weeknd", "Blinding Lights", 202L)
                else -> topicSong("geIAO3PrE2g", "Why This Kolaveri Di? (The Soup of Love)", "Anirudh Ravichander, Dhanush", "3", 260L)
            }
            val (realPage1, realCont1) = loadRealF0FixturePages(key, seed.id)
            // Build realistic multi-page continuation stream starting with REAL F0 page1 + cont1
            val syntheticPages = uniqueDomainCatalog("f3_${key}", langKey, count = 260, startIdx = 0).chunked(24)
            val allPages = listOf(realPage1.songs, realCont1.songs) + syntheticPages
            val pageCursor = AtomicInteger(0)
            val g5Pool = uniqueDomainCatalog("g5_${key}", langKey, count = 160, startIdx = 300).chunked(20)
            val g5Cursor = AtomicInteger(0)

            val poolBuilder = CandidatePoolBuilder(
                sources = listOf(
                    SeedRelatedCandidateSource(
                        relatedProvider = {
                            // Simulates F3 StreamResolver bundling up to 3 pages (~60 candidates) initially / per fresh continuation batch
                            val idx = pageCursor.getAndUpdate { (it + 3).coerceAtMost(allPages.size) }
                            (allPages.getOrElse(idx) { emptyList() } +
                                allPages.getOrElse(idx + 1) { emptyList() } +
                                allPages.getOrElse(idx + 2) { emptyList() }).distinctBy { s -> s.id }
                        },
                    ),
                    GenreLanguageNeighbourSource(
                        querySearchProvider = {
                            val idx = g5Cursor.getAndIncrement() % g5Pool.size
                            g5Pool[idx]
                        },
                    ),
                ),
                featureRepository = featureRepo,
                cacheTtlMs = 0L,
            )
            val engine = DefaultRecommendationEngine(
                candidatePoolBuilder = poolBuilder,
                userProfileRepository = DefaultUserProfileRepository(
                    eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                    libraryDataProvider = { LibraryData() },
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

            val qm = QueueManager()
            qm.start(listOf(seed), startIndex = 0)

            fun snapshot() = QueueRefillSnapshot(
                currentSong = qm.current,
                upcomingSongs = qm.upNext.map { it.song },
                historySongs = qm.history,
                queueVersion = qm.queueVersion,
                isAutoplayEnabled = true,
                isRadioActive = true,
                radioSeedSong = seed,
                preferredLanguageIds = userLangs,
                allowMixedLanguages = false,
                fallbackAnchorPool = qm.history.takeLast(10).reversed(),
            )

            // Initial queue fill
            val initDecision = coordinator.requestRefill(snapshot(), ::snapshot, force = true)
            assertTrue("$key initial fill must succeed", initDecision is RefillDecision.AppendSongs)
            val initSongs = (initDecision as RefillDecision.AppendSongs).songs
            assertTrue("$key initial fill must append >= MIN_BATCH (15), got ${initSongs.size}", initSongs.size >= RunwayPolicy.MIN_BATCH)
            qm.appendAutoplay(initSongs)

            val played = ArrayList<Song>(200)
            var minAheadObserved = Int.MAX_VALUE
            var smallRefillCount = 0

            repeat(200) { stepIdx ->
                if (coordinator.rollingPoolSize() < 60) {
                    coordinator.ensureRollingPoolWarm(snapshot(), targetPoolSize = 60)
                }
                val snap = snapshot()
                if (snap.upcomingSongs.size < RunwayPolicy.REFILL_BELOW) {
                    val decision = coordinator.requestRefill(snap, ::snapshot, force = qm.upNext.isEmpty())
                    if (decision is RefillDecision.AppendSongs && decision.songs.isNotEmpty()) {
                        if (decision.songs.size < RunwayPolicy.MIN_BATCH && snap.upcomingSongs.size + decision.songs.size < RunwayPolicy.TARGET_AHEAD) {
                            smallRefillCount++
                        }
                        qm.appendAutoplay(decision.songs)
                    }
                }
                minAheadObserved = minOf(minAheadObserved, qm.upNext.size)
                assertTrue(
                    "$key advance #${stepIdx + 1}: queue-ahead dropped to ${qm.upNext.size} (< REFILL_BELOW ${RunwayPolicy.REFILL_BELOW})",
                    qm.upNext.size >= RunwayPolicy.REFILL_BELOW,
                )
                val next = qm.advance(manual = false)
                assertNotNull("$key advance #${stepIdx + 1} unexpectedly returned null", next)
                played += next!!
            }

            val compKeys = played.map { RecordingIdentityResolver.canonicalCompositionTitleKey(it) }.toSet()
            assertEquals("$key must sustain all 200 advances", 200, played.size)
            assertEquals("$key must have ZERO composition repeats across 200 advances", 200, compKeys.size)
            assertEquals("$key must have zero undersized active refills", 0, smallRefillCount)

            val funnels = QueueHealthMonitor.recentFunnels(30).reversed()
            val active = funnels.filter { it.decision == "APPENDED" }
            assertTrue("$key should record active funnels", active.isNotEmpty())
            val avgFetched = active.map { it.totalFunnel.fetched }.average()
            val avgElig = active.map { it.totalFunnel.afterEligibility }.average()
            val avgAcc = active.map { it.totalFunnel.accepted }.average()
            println(
                "[F3-AVG] seed=$key refills=${active.size} minAhead=$minAheadObserved " +
                    "avgFetched=${"%.1f".format(avgFetched)} avgElig=${"%.1f".format(avgElig)} avgAcc=${"%.1f".format(avgAcc)} " +
                    "rollingHits=${active.count { "rolling_pool" in it.acceptedSourceIds }}",
            )
        }
    }
}
