package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.features.EnrichmentMetadata
import com.velvet.app.recommendation.features.MetadataFeatureExtractor
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.AudioFeatureVector
import com.velvet.app.recommendation.model.CandidateSourceEvidence
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.profile.ExplicitPreferenceSet
import com.velvet.app.recommendation.profile.FeaturePreferenceVector
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import com.velvet.app.recommendation.ranking.DefaultCandidateRanker
import com.velvet.app.recommendation.ranking.RankerShadowEvaluator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5RankerTest {

    private val extractor = MetadataFeatureExtractor { 1_000_000L }

    private fun makeCandidate(
        song: Song,
        enrichment: EnrichmentMetadata? = null,
        bpm: Double? = null,
    ): CandidateTrack {
        val baseFeatures = extractor.extract(song, enrichment)
        val features = if (bpm != null) {
            baseFeatures.copy(audio = AudioFeatureVector(bpm = bpm, bpmConfidence = 0.92))
        } else baseFeatures
        val id = RecordingIdentityResolver.resolve(song)
        return CandidateTrack(
            song = song,
            identity = id,
            features = features,
            sourceEvidence = listOf(CandidateSourceEvidence("g1_related", rank = 0, retrievalScore = 0.9, evidenceCode = "test", confidence = 0.8)),
            candidateRetrievedAtMillis = 1_000_000L,
        )
    }

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
    fun goldenHierarchy_compatibleMusicBeatsSameArtistMismatchEvenWithFavoriteArtistTaste() {
        val seed = topicSong("h_seed", "Shree Hanuman Chalisa", "Hariharan Bhakti", durationSec = 580)
        val sameArtistParty = topicSong("h_same_bad", "Saturday Night Party Dance", "Hariharan Bhakti", durationSec = 220)
        val diffArtistBhajan = topicSong("h_diff_good", "Jai Ganesh Deva Aarti", "Anuradha Paudwal", durationSec = 310)

        val heavyArtistTaste = UserTasteSnapshot(
            longTerm = FeaturePreferenceVector(artistWeights = mapOf("hariharan bhakti" to 1.0)),
            explicit = ExplicitPreferenceSet(
                likedRecordingIds = setOf(RecordingIdentityResolver.resolve(sameArtistParty).canonicalRecordingId),
            ),
        )
        val req = RecommendationRequest("r-hier", RecommendationMode.SONG_RADIO, seedSongs = listOf(seed))
        val ranker = DefaultCandidateRanker()
        val ranked = ranker.rank(
            candidates = listOf(makeCandidate(sameArtistParty), makeCandidate(diffArtistBhajan)),
            request = req,
            profile = heavyArtistTaste,
            seedFeatures = extractor.extract(seed),
        )

        assertEquals(diffArtistBhajan.id, ranked.first().candidate.song.id)
        val badBreakdown = ranked.last().breakdown
        assertEquals(0.0, badBreakdown.personalTasteFit, 0.001)
        assertTrue(badBreakdown.penalties.containsKey("music_type_clash") || badBreakdown.penalties.containsKey("genre_clash"))
    }

    @Test
    fun artistRadioPrioritizesSelectedArtistWhereasSongRadioRemainsMusicFirst() {
        val seed = topicSong("s_arijit", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2")
        val sameArtistWeak = topicSong("c_arijit", "Khaabon Ke Parindey", "Arijit Singh")
        val diffArtistExactRomantic = topicSong("c_atif", "Jeena Jeena Ishq Romantic Love", "Atif Aslam", album = "Aashiqui 2")

        val pool = listOf(makeCandidate(sameArtistWeak), makeCandidate(diffArtistExactRomantic))
        val seedFeat = extractor.extract(seed)
        val ranker = DefaultCandidateRanker()

        val songRadioRanked = ranker.rank(
            pool,
            RecommendationRequest("r-sr", RecommendationMode.SONG_RADIO, seedSongs = listOf(seed)),
            UserTasteSnapshot(),
            seedFeat,
        )
        assertEquals(diffArtistExactRomantic.id, songRadioRanked.first().candidate.song.id)
        assertEquals(0.0, songRadioRanked.first { it.candidate.song.id == c_arijit_id(sameArtistWeak) }.breakdown.artistAffinityFit, 0.001)

        val artistRadioRanked = ranker.rank(
            pool,
            RecommendationRequest(
                "r-ar",
                RecommendationMode.ARTIST_RADIO,
                seedSongs = listOf(seed),
                selectedArtistId = "Arijit Singh",
            ),
            UserTasteSnapshot(),
            seedFeat,
        )
        assertEquals(sameArtistWeak.id, artistRadioRanked.first().candidate.song.id)
        assertTrue(artistRadioRanked.first().breakdown.artistAffinityFit > 0.25)
    }

    private fun c_arijit_id(song: Song) = song.id

    @Test
    fun halfTimeDoubleTimeBpmAlignsAndUnknownFeaturesNeverCreatePenalties() {
        val seedSong = topicSong("s_bpm", "Pehla Nasha", "Udit Narayan")
        val seedFeat = extractor.extract(seedSong).copy(audio = AudioFeatureVector(bpm = 70.0, bpmConfidence = 0.92))

        val doubleTimeCompat = makeCandidate(topicSong("c_140", "Tum Se Hi", "Mohit Chauhan"), bpm = 140.0)
        val clashingBpm = makeCandidate(topicSong("c_105", "Tujhe Dekha", "Kumar Sanu"), bpm = 105.0)
        val missingBpmCand = makeCandidate(topicSong("c_unknown", "Unknown Groove Track", "Indie Artist"), bpm = null)

        val ranker = DefaultCandidateRanker()
        val ranked = ranker.rank(
            candidates = listOf(clashingBpm, missingBpmCand, doubleTimeCompat),
            request = RecommendationRequest("r-bpm", RecommendationMode.SIMILAR_SONGS, seedSongs = listOf(seedSong)),
            profile = UserTasteSnapshot(),
            seedFeatures = seedFeat,
        )

        val doubleTimeScored = ranked.first { it.candidate.song.id == "c_140" }
        val clashScored = ranked.first { it.candidate.song.id == "c_105" }
        val missingScored = ranked.first { it.candidate.song.id == "c_unknown" }

        assertTrue(doubleTimeScored.breakdown.tempoEnergyFit > clashScored.breakdown.tempoEnergyFit)
        assertEquals(0.0, missingScored.breakdown.tempoEnergyFit, 0.001)
        assertTrue(missingScored.breakdown.penalties.isEmpty())
        assertFalse(doubleTimeScored.rankScore.isNaN())
    }

    @Test
    fun qualityScoreRewardsOnlyVerifiedAudioNeverOmvAndShadowEvaluatorCatchesVideoLeaks() = runBlocking {
        val seed = topicSong("s_shadow", "Kesariya", "Arijit Singh", album = "Brahmastra")
        val verifiedAudio = topicSong("aud_1", "Agar Tum Saath Ho", "Alka Yagnik, Arijit Singh")
        val verifiedOfficialVideo = Song(
            id = "omv_1",
            title = "Channa Mereya (Official Video)",
            artist = "Arijit Singh",
            durationSec = 289,
            isUploaderVerified = true,
            rawUploaderName = "Sony Music India",
            preferredMusicSource = true,
        )
        val unverifiedVerifiedChannel = Song(
            id = "unv_1",
            title = "Shayad",
            artist = "Sony Music India",
            durationSec = 247,
            isUploaderVerified = true,
            rawUploaderName = "Sony Music India",
            preferredMusicSource = true,
        )

        val pool = CandidatePoolBuilder(
            sources = listOf(SeedRelatedCandidateSource(relatedProvider = { listOf(verifiedOfficialVideo, unverifiedVerifiedChannel, verifiedAudio) })),
        ).build(RecommendationRequest("r-shad", RecommendationMode.SONG_RADIO, seedSongs = listOf(seed)), UserTasteSnapshot())

        val omvTrack = pool.candidates.first { it.song.id == "omv_1" }
        assertEquals(ReleaseAudioClass.REJECTED_OFFICIAL_VIDEO, omvTrack.identity.releaseClassification.audioClass)

        val shadow = RankerShadowEvaluator().evaluateShadow(
            pool = pool.candidates,
            request = RecommendationRequest("r-shad", RecommendationMode.SONG_RADIO, seedSongs = listOf(seed)),
            profile = UserTasteSnapshot(),
        )

        // Legacy pipeline admits verified channel / OMV tracks; Phase 5 + Strict Audio Eligibility admits ZERO videos or unverified items
        assertTrue("Legacy baseline leaked OMV/unverified", shadow.legacyLeakCountOfVideosOrUnverified > 0)
        assertEquals("Phase 5 must never leak videos or unverified uploads", 0, shadow.phase5LeakCountOfVideosOrUnverified)
        assertEquals(listOf("aud_1"), shadow.phase5Ids)
        val topBreakdown = shadow.phase5RankedCandidates.first().breakdown
        assertTrue(topBreakdown.qualityFit > 0.0)
        assertTrue(shadow.phase5RankedCandidates.first().reasons.any { it.code == "VERIFIED_STUDIO_AUDIO" })
    }
}
