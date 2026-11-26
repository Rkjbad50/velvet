package com.velvet.app.unit

import com.velvet.app.data.BackupCodec
import com.velvet.app.data.LibraryData
import com.velvet.app.data.ListenContext
import com.velvet.app.data.ListenEndReason
import com.velvet.app.data.ListenEvent
import com.velvet.app.data.Playlist
import com.velvet.app.data.Song
import com.velvet.app.data.mergeLibraries
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.QueueManager
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.LibraryTrackCandidateSource
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.features.MetadataFeatureExtractor
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.filtering.DefaultEligibilityPolicy
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.recommendation.vibe.ArtistLanguageMap
import com.velvet.app.recommendation.vibe.ArtistLanguageProfile
import com.velvet.app.recommendation.vibe.ArtistLanguageRegistry
import com.velvet.app.recommendation.vibe.SessionVibe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase P4 golden verification (C3):
 * - Hindi 90s seed -> 60 consecutive picks with 0 English, 0 Punjabi, 0 South-Indic (engine & local fallback).
 * - Tamil seed -> Tamil only.
 * - English seed -> English only.
 * - Mixed-language setting -> mixture across user-selected languages allowed.
 * - ArtistLanguageMap learning, Signal-1 artist veto, and optional Backups field compatibility.
 */
class Phase4SessionVibeAndLanguageLockTest {

    @Before
    fun resetState() {
        QueueHealthMonitor.clearForTest()
        InstantTrackCatalog.clear()
        ArtistLanguageRegistry.clearForTest()
    }

    private fun studioSong(
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
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    private fun buildHindi90sPool(count: Int = 80): List<Song> {
        val adj = listOf("Nayi", "Meethi", "Gehri", "Chupke", "Pehli", "Sunehri", "Anjani", "Bheegi", "Rang", "Chandni")
        val nouns = listOf("Baarish", "Raatein", "Hawayein", "Kahaani", "Manzil", "Khamoshi", "Roshni", "Dastaan", "Aashiqui", "Dhadkan")
        val singers90s = listOf("Kumar Sanu", "Udit Narayan", "Alka Yagnik", "Sonu Nigam", "Abhijeet", "Kavita Krishnamurthy")
        return buildList {
            var idx = 0
            for (a in adj) {
                for (n in nouns) {
                    if (size >= count) return@buildList
                    val id = "hi_$idx"
                    add(studioSong(id, "$a $n", singers90s[idx % singers90s.size], album = "90s Bollywood Romance"))
                    idx++
                }
            }
        }
    }

    private fun buildEnglishPool(count: Int = 25): List<Song> {
        val first = listOf("Neon", "Midnight", "Crystal", "Velvet", "Electric", "Golden")
        val second = listOf("Echoes", "Skyline", "Mirrors", "Shadows", "Dreams", "Oceans")
        val singers = listOf("Taylor Swift", "Ed Sheeran", "Dua Lipa", "The Weeknd", "Coldplay", "Billie Eilish")
        return buildList {
            var idx = 0
            for (f in first) {
                for (s in second) {
                    if (size >= count) return@buildList
                    add(studioSong("en_$idx", "$f $s", singers[idx % singers.size], album = "Pop Sessions"))
                    idx++
                }
            }
        }
    }

    private fun buildPunjabiPool(count: Int = 20): List<Song> {
        val first = listOf("Patiala", "Chandigarh", "Lahore", "Jatt", "Bhangra")
        val second = listOf("Peg", "Gabru", "Tashan", "Dhol", "Swagger")
        val singers = listOf("Sidhu Moosewala", "Diljit Dosanjh", "Karan Aujla", "AP Dhillon", "Guru Randhawa")
        return buildList {
            var idx = 0
            for (f in first) {
                for (s in second) {
                    if (size >= count) return@buildList
                    add(studioSong("pa_$idx", "$f $s", singers[idx % singers.size], album = "Punjabi Hits"))
                    idx++
                }
            }
        }
    }

    private fun buildSouthIndicPool(count: Int = 20): List<Song> {
        val first = listOf("Kollywood", "Tollywood", "Mollywood", "Sandalwood", "Chennai")
        val second = listOf("Breeze", "Beat", "Rhythm", "Dawn", "Vibe")
        val singers = listOf("Anirudh Ravichander", "Yuvan Shankar Raja", "Devi Sri Prasad", "Thaman S")
        return buildList {
            var idx = 0
            for (f in first) {
                for (s in second) {
                    if (size >= count) return@buildList
                    val langTag = when (idx % 3) {
                        0 -> "Tamil"
                        1 -> "Telugu"
                        else -> "Malayalam"
                    }
                    add(studioSong("si_$idx", "$f $langTag $s", singers[idx % singers.size], album = "$langTag Ost"))
                    idx++
                }
            }
        }
    }

    private fun buildEngine(
        pool: List<Song>,
        libraryData: LibraryData = LibraryData(),
    ): DefaultRecommendationEngine {
        val builder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = { pool }),
                GenreLanguageNeighbourSource(querySearchProvider = { pool.reversed() }),
            ),
            cacheTtlMs = 0L,
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { libraryData },
        )
        return DefaultRecommendationEngine(
            candidatePoolBuilder = builder,
            userProfileRepository = profileRepo,
        )
    }

    @Test
    fun hindi90sSeedPicks60ConsecutiveHindiTracksZeroEnglishPunjabiOrSouthIndic() = runBlocking {
        val seed = studioSong("seed_hi90", "Tujhe Dekha To Yeh Jaana Sanam", "Kumar Sanu", album = "DDLJ")
        val hindiPool = buildHindi90sPool(80)
        val foreignPool = buildEnglishPool(25) + buildPunjabiPool(20) + buildSouthIndicPool(20)
        val fullCatalog = (hindiPool + foreignPool).shuffled(java.util.Random(77L))

        val libData = LibraryData(
            musicLanguages = listOf("Hindi", "English"),
            allowMixedLanguagesInAutoplay = false,
            liked = fullCatalog,
        )
        val engine = buildEngine(fullCatalog, libData)
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 0L,
            ),
        )

        val qm = QueueManager()
        qm.start(listOf(seed), startIndex = 0)
        val consecutivePicks = mutableListOf<Song>()

        while (consecutivePicks.size < 60) {
            val snap = QueueRefillSnapshot(
                currentSong = qm.current,
                upcomingSongs = qm.upNext.map { it.song },
                historySongs = qm.history,
                queueVersion = qm.queueVersion,
                isAutoplayEnabled = true,
                preferredLanguageIds = setOf("hi", "en"),
                allowMixedLanguages = false,
                artistLanguageMap = libData.artistLanguageMap,
            )
            val decision = coordinator.requestRefill(snap, { snap }, force = qm.upNext.isEmpty())
            assertTrue("Expected AppendSongs, got $decision at picks=${consecutivePicks.size}", decision is RefillDecision.AppendSongs)
            val batch = (decision as RefillDecision.AppendSongs).songs
            qm.appendAutoplay(batch)
            while (qm.upNext.isNotEmpty() && consecutivePicks.size < 60) {
                val next = qm.advance(manual = false) ?: break
                consecutivePicks.add(next)
            }
        }

        assertEquals(60, consecutivePicks.size)
        assertTrue("Expected zero English tracks in 60 Hindi 90s picks", consecutivePicks.none { it.id.startsWith("en_") })
        assertTrue("Expected zero Punjabi tracks in 60 Hindi 90s picks", consecutivePicks.none { it.id.startsWith("pa_") })
        assertTrue("Expected zero South-Indic tracks in 60 Hindi 90s picks", consecutivePicks.none { it.id.startsWith("si_") })
        assertTrue("All 60 picks must be Hindi", consecutivePicks.all { it.id.startsWith("hi_") })

        // Verify local/instant fallback enforces identical lock
        val localMatches = InstantTrackCatalog.matchSimilar(
            seed = seed,
            extraPool = fullCatalog,
            preferredLanguageIds = setOf("hi", "en"),
            allowMixedLanguages = false,
            limit = 20,
        )
        assertTrue(localMatches.isNotEmpty())
        assertTrue("Local fallback leaked foreign track", localMatches.all { it.id.startsWith("hi_") })
    }

    @Test
    fun tamilSeedReturnsTamilOnlyUnderSessionVibeLock() = runBlocking {
        val tamilSeed = studioSong("seed_ta", "தமிழ் Kollywood Magic", "Anirudh Ravichander", album = "Tamil Hits")
        val tamilPool = (0 until 15).map { i ->
            val words = listOf("Chennai", "Madurai", "Marina", "Kaveri", "Nilgiri", "Thamirabarani", "Kovai", "Trichy")
            val tags = listOf("Breeze", "Dawn", "Vibe", "Melody", "Echo")
            studioSong("ta_only_$i", "${words[i % words.size]} Tamil ${tags[i / 8]}", "Anirudh Ravichander", album = "Tamil Ost")
        }
        val mixedCatalog = (tamilPool + buildHindi90sPool(20) + buildEnglishPool(15) + buildPunjabiPool(10))
            .shuffled(java.util.Random(19L))

        val engine = buildEngine(mixedCatalog)
        val res = engine.nextSimilar(
            seed = tamilSeed,
            mode = RecommendationMode.AUTOPLAY_REFILL,
            requestedLanguageIds = setOf("hi", "en", "ta"),
            allowMixedLanguages = false,
            limit = 15,
        )
        assertTrue(res.candidates.isNotEmpty())
        assertTrue(
            "Expected Tamil-only picks, got ${res.candidates.map { it.song.id }}",
            res.candidates.all { it.song.id.startsWith("ta_only_") },
        )

        val localTamil = InstantTrackCatalog.matchSimilar(
            seed = tamilSeed,
            extraPool = mixedCatalog,
            preferredLanguageIds = setOf("hi", "en", "ta"),
            allowMixedLanguages = false,
            limit = 10,
        )
        assertTrue(localTamil.isNotEmpty())
        assertTrue(localTamil.all { it.id.startsWith("ta_only_") })
    }

    @Test
    fun englishSeedReturnsEnglishOnlyUnderSessionVibeLock() = runBlocking {
        val enSeed = studioSong("seed_en", "Lavender Haze Night", "Taylor Swift", album = "Pop Sessions")
        val mixedCatalog = (buildEnglishPool(20) + buildHindi90sPool(20) + buildPunjabiPool(10) + buildSouthIndicPool(10))
            .shuffled(java.util.Random(23L))

        val engine = buildEngine(mixedCatalog)
        val res = engine.nextSimilar(
            seed = enSeed,
            mode = RecommendationMode.AUTOPLAY_REFILL,
            requestedLanguageIds = setOf("hi", "en"),
            allowMixedLanguages = false,
            limit = 15,
        )
        assertTrue(res.candidates.isNotEmpty())
        assertTrue(
            "Expected English-only picks, got ${res.candidates.map { it.song.id }}",
            res.candidates.all { it.song.id.startsWith("en_") },
        )

        val localEn = InstantTrackCatalog.matchSimilar(
            seed = enSeed,
            extraPool = mixedCatalog,
            preferredLanguageIds = setOf("hi", "en"),
            allowMixedLanguages = false,
            limit = 10,
        )
        assertTrue(localEn.isNotEmpty())
        assertTrue(localEn.all { it.id.startsWith("en_") })
    }

    @Test
    fun mixedLanguageUserSettingPermitsMixtureOfSelectedLanguagesAndBlocksUnselected() = runBlocking {
        val seed = studioSong("seed_mix", "Acoustic Nights Song", "Local Indie", album = "Sessions")
        val hiTracks = (0 until 8).map { i ->
            studioSong("hi_m_$i", "Hindi Romantic Track $i", "Arijit Singh", album = "Bollywood")
        }
        val enTracks = (0 until 8).map { i ->
            studioSong("en_m_$i", "English Pop Track $i", "Ed Sheeran", album = "Pop")
        }
        val paTracks = buildPunjabiPool(6)
        val fullCatalog = hiTracks + enTracks + paTracks

        val engine = buildEngine(fullCatalog)
        val res = engine.nextSimilar(
            seed = seed,
            mode = RecommendationMode.AUTOPLAY_REFILL,
            requestedLanguageIds = setOf("hi", "en"),
            allowMixedLanguages = true,
            limit = 16,
        )
        val ids = res.candidates.map { it.song.id }
        assertTrue("Mixed mode must include Hindi tracks (got $ids)", ids.any { it.startsWith("hi_m_") })
        assertTrue("Mixed mode must include English tracks (got $ids)", ids.any { it.startsWith("en_m_") })
        assertTrue("Unselected Punjabi tracks still rejected unless user chose Punjabi (got $ids)", ids.none { it.startsWith("pa_") })

        val localMix = InstantTrackCatalog.matchSimilar(
            seed = seed,
            extraPool = fullCatalog,
            preferredLanguageIds = setOf("hi", "en"),
            allowMixedLanguages = true,
            limit = 16,
        )
        assertTrue(localMix.any { it.id.startsWith("hi_m_") })
        assertTrue(localMix.any { it.id.startsWith("en_m_") })
        assertTrue(localMix.none { it.id.startsWith("pa_") })
    }

    @Test
    fun artistLanguageMapLearnsFromLibraryVetosAlienArtistAndSurvivesBackupCompatibility() {
        val likedEn = listOf(
            studioSong("l1", "English Breeze Morning", "Solstice Unknown"),
            studioSong("l2", "English Midnight Drive", "Solstice Unknown"),
        )
        val playlistTamil = Playlist(
            id = "pl_ta",
            name = "Tamil Classics",
            songs = listOf(studioSong("p1", "Velvet Sky Song", "Vannam Ensemble")),
        )
        val completedHindiEvent = ListenEvent(
            songGroupId = "grp_c1",
            videoId = "c1",
            artistSet = listOf("Desi Vocalist"),
            startedAt = 500_000L,
            playedMs = 220_000L,
            durationMs = 240_000L,
            completionPct = 0.92f,
            endReason = ListenEndReason.COMPLETED,
            context = ListenContext.AUTOPLAY,
            language = "hi",
        )
        val libData = LibraryData(
            liked = likedEn,
            playlists = listOf(playlistTamil),
            listenEvents = listOf(completedHindiEvent),
            allowMixedLanguagesInAutoplay = true,
        )

        val learnedMap = ArtistLanguageMap.learnFromLibrary(libData, MetadataFeatureExtractor(), nowMillis = 1_000_000L)
        assertEquals("en", ArtistLanguageMap.inferArtistLanguage("Solstice Unknown", learnedMap)?.first)
        assertEquals("ta", ArtistLanguageMap.inferArtistLanguage("Vannam Ensemble", learnedMap)?.first)
        assertEquals("hi", ArtistLanguageMap.inferArtistLanguage("Desi Vocalist", learnedMap)?.first)

        // Backup encode/decode & merge preserve optional fields
        val withMap = libData.copy(artistLanguageMap = learnedMap)
        val decoded = BackupCodec.decode(BackupCodec.encode(withMap))
        assertTrue(decoded.allowMixedLanguagesInAutoplay)
        assertEquals(learnedMap, decoded.artistLanguageMap)

        val merged = mergeLibraries(LibraryData(), decoded)
        assertTrue(merged.allowMixedLanguagesInAutoplay)
        assertEquals("en", merged.artistLanguageMap["solstice unknown"]?.dominantLanguage)

        // Old backup without new fields decodes with safe defaults
        val legacyBackupJson = """{"app":"VELVET","backupVersion":1,"exportedAt":1000,"library":{"musicLanguages":["Hindi"]}}"""
        val legacyDecoded = BackupCodec.decode(legacyBackupJson)
        assertFalse(legacyDecoded.allowMixedLanguagesInAutoplay)
        assertTrue(legacyDecoded.artistLanguageMap.isEmpty())

        // Signal-1 veto in EligibilityPolicy: "Solstice Unknown" has ambiguous title but learned "en" -> vetoed under Hindi lock!
        ArtistLanguageRegistry.setDeviceMap(learnedMap)
        val hiSeed = studioSong("s_hi", "Tujhe Dekha To", "Kumar Sanu", album = "DDLJ")
        val ambiguousAlienCand = studioSong("c_alien", "Nayi Raat", "Solstice Unknown")
        val req = RecommendationRequest(
            requestId = "req-veto",
            mode = RecommendationMode.AUTOPLAY_REFILL,
            seedSongs = listOf(hiSeed),
            allowMixedLanguages = false,
            artistLanguageMap = learnedMap,
            sessionVibe = SessionVibe(languages = setOf("hi"), allowMixedLanguages = false),
        )
        val extractor = MetadataFeatureExtractor()
        val policy = DefaultEligibilityPolicy()
        val decision = policy.evaluate(
            candidate = com.velvet.app.recommendation.model.CandidateTrack(
                song = ambiguousAlienCand,
                identity = com.velvet.app.recommendation.identity.RecordingIdentityResolver.resolve(ambiguousAlienCand),
                features = extractor.extract(ambiguousAlienCand),
                sourceEvidence = listOf(
                    com.velvet.app.recommendation.model.CandidateSourceEvidence(
                        sourceId = "g1_related",
                        evidenceCode = "test",
                    ),
                ),
                candidateRetrievedAtMillis = 1L,
            ),
            request = req,
            profile = com.velvet.app.recommendation.profile.UserTasteSnapshot(),
            seedFeatures = extractor.extract(hiSeed),
        )
        assertFalse("Expected Signal-1 artist_language_mismatch veto", decision.eligible)
        assertTrue(decision.reasons.any { "artist_language_mismatch" in it || "lang" in it })
    }

    @Test
    fun raisedGenreAndTypeGateBlocksWeakPunjabiAndUntrustedUnknownLanguage() {
        val hiSeed = studioSong("s_hi2", "Pehla Nasha", "Udit Narayan", album = "Bollywood Classic")
        val punjabiWeakGenre = studioSong("pa_g", "Punjabi Club Beat", "Diljit Dosanjh")
        val untrustedUnknown = studioSong("unk_lib", "Random Melody", "Indie Unknown Person")
        val trustedRelatedUnknown = studioSong("unk_rel", "Khamosh Shaam", "Indie Unknown Person")

        val extractor = MetadataFeatureExtractor()
        val seedFeat = extractor.extract(hiSeed)
        val req = RecommendationRequest(
            requestId = "req-gate",
            mode = RecommendationMode.AUTOPLAY_REFILL,
            seedSongs = listOf(hiSeed),
            sessionVibe = SessionVibe(
                languages = setOf("hi"),
                allowMixedLanguages = false,
                genreWeights = seedFeat.genreIds,
            ),
        )
        val policy = DefaultEligibilityPolicy()

        fun eval(song: Song, sourceId: String) = policy.evaluate(
            candidate = com.velvet.app.recommendation.model.CandidateTrack(
                song = song,
                identity = com.velvet.app.recommendation.identity.RecordingIdentityResolver.resolve(song),
                features = extractor.extract(song),
                sourceEvidence = listOf(
                    com.velvet.app.recommendation.model.CandidateSourceEvidence(sourceId = sourceId, evidenceCode = "t"),
                ),
                candidateRetrievedAtMillis = 1L,
            ),
            request = req,
            profile = com.velvet.app.recommendation.profile.UserTasteSnapshot(),
            seedFeatures = seedFeat,
        )

        val paDecision = eval(punjabiWeakGenre, "g1_related")
        assertFalse("Punjabi genre (0.38 < 0.40) and language must be rejected", paDecision.eligible)

        val libUnknownDecision = eval(untrustedUnknown, "g6_library")
        assertFalse("Unknown language from non-lineage library source must be rejected", libUnknownDecision.eligible)

        val relUnknownDecision = eval(trustedRelatedUnknown, "g1_related")
        assertTrue("Unknown language with trusted g1_related lineage & compatible MusicType inherits vibe", relUnknownDecision.eligible)
    }
}
