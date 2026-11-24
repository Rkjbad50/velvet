package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.recommendation.features.MetadataFeatureExtractor
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.AudioFeatureVector
import com.velvet.app.recommendation.model.CandidateSourceEvidence
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.RankScoreBreakdown
import com.velvet.app.recommendation.model.RankedCandidate
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.TempoBucket
import com.velvet.app.recommendation.sequencing.DefaultSequenceOptimizer
import com.velvet.app.recommendation.sequencing.SequenceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6SequenceOptimizerTest {

    private val extractor = MetadataFeatureExtractor { 1_000_000L }

    private fun ranked(
        id: String,
        title: String,
        artist: String,
        score: Double,
        album: String? = null,
        bpm: Double? = null,
        tempoBucket: TempoBucket? = null,
    ): RankedCandidate {
        val song = Song(
            id = id,
            title = title,
            artist = artist,
            durationSec = 240L,
            album = album,
            isTopicRelease = true,
            rawUploaderName = "$artist - Topic",
            preferredMusicSource = true,
        )
        var features = extractor.extract(song)
        if (bpm != null) {
            features = features.copy(audio = AudioFeatureVector(bpm = bpm, bpmConfidence = 0.90))
        }
        if (tempoBucket != null) {
            features = features.copy(tempoBucket = tempoBucket, tempoConfidence = 0.85)
        }
        val cand = CandidateTrack(
            song = song,
            identity = RecordingIdentityResolver.resolve(song),
            features = features,
            sourceEvidence = listOf(CandidateSourceEvidence("g1_related", 0, 0.9, evidenceCode = "test")),
            candidateRetrievedAtMillis = 1_000_000L,
        )
        return RankedCandidate(cand, rankScore = score, breakdown = RankScoreBreakdown(total = score))
    }

    @Test
    fun beamSearchAvoidsBackToBackSameArtistAndAlbumDumpInNormalRadio() {
        val seedSong = Song(
            id = "seed_1",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            durationSec = 262L,
            album = "Aashiqui 2",
            isTopicRelease = true,
            rawUploaderName = "Arijit Singh - Topic",
            preferredMusicSource = true,
        )
        val pool = listOf(
            ranked("a1", "Chahun Main Ya Naa", "Arijit Singh", score = 0.95, album = "Aashiqui 2"),
            ranked("a2", "Meri Aashiqui", "Arijit Singh", score = 0.94, album = "Aashiqui 2"),
            ranked("a3", "Milne Hai Mujhse Aayi", "Arijit Singh", score = 0.93, album = "Aashiqui 2"),
            ranked("b1", "Jeena Jeena", "Atif Aslam", score = 0.91, album = "Badlapur"),
            ranked("c1", "Agar Tum Saath Ho", "Alka Yagnik", score = 0.90, album = "Tamasha"),
            ranked("d1", "Zara Sa", "KK", score = 0.89, album = "Jannat"),
        )

        val res = DefaultSequenceOptimizer().optimize(
            ranked = pool,
            request = RecommendationRequest("req-seq-1", RecommendationMode.SONG_RADIO, seedSongs = listOf(seedSong), limit = 4),
            recentHistoryFeatures = listOf(extractor.extract(seedSong)),
            seedFeatures = extractor.extract(seedSong),
        )

        assertEquals(4, res.tracks.size)
        val artists = res.tracks.map { it.song.artist }
        // Seed was Arijit Singh so position 0 must not immediately repeat Arijit Singh, and no adjacent duplicates
        assertTrue("First track should respect cooldown from seed artist Arijit Singh", artists.first() != "Arijit Singh")
        for (i in 1 until artists.size) {
            assertTrue("Adjacent tracks must not share identical primary artist: $artists", artists[i] != artists[i - 1])
        }
        assertTrue(res.usedBeamSearch)
    }

    @Test
    fun smoothTempoFlowPrefersHarmonicAndGradualTransitionsOverJarringWhiplash() {
        val seedSong = Song(
            id = "seed_bpm",
            title = "Kesariya",
            artist = "Arijit Singh",
            durationSec = 268L,
            isTopicRelease = true,
            rawUploaderName = "Arijit Singh - Topic",
            preferredMusicSource = true,
        )
        val seedFeat = extractor.extract(seedSong).copy(audio = AudioFeatureVector(bpm = 96.0, bpmConfidence = 0.92))

        // Nearly tied rankScores, but non-harmonic 138 BPM whiplash vs smooth 98->104->110 flow
        val pool = listOf(
            ranked("jarring_fast", "Hard Club Party Beat", "Badshah", score = 0.855, bpm = 138.0, tempoBucket = TempoBucket.FAST),
            ranked("smooth_98", "Subah Subah Love", "Armaan Malik", score = 0.850, bpm = 98.0, tempoBucket = TempoBucket.MEDIUM),
            ranked("smooth_104", "Dil Diyan Gallan", "Atif Aslam", score = 0.848, bpm = 104.0, tempoBucket = TempoBucket.MEDIUM),
            ranked("smooth_110", "Tera Hone Laga Hoon", "Jubin Nautiyal", score = 0.846, bpm = 110.0, tempoBucket = TempoBucket.MEDIUM),
        )

        val res = DefaultSequenceOptimizer().optimize(
            ranked = pool,
            request = RecommendationRequest("req-flow", RecommendationMode.AUTOPLAY_REFILL, seedSongs = listOf(seedSong), limit = 3),
            recentHistoryFeatures = listOf(seedFeat),
            seedFeatures = seedFeat,
        )

        assertEquals(3, res.tracks.size)
        assertEquals(listOf("smooth_98", "smooth_104", "smooth_110"), res.tracks.map { it.song.id })
        assertTrue(res.averageTransitionScore > 0.0)
    }

    @Test
    fun gracefulCooldownRelaxationWhenNarrowPoolHasOnlyOneArtistAndNeverDuplicatesWorks() {
        val dupA = ranked("id_1", "Mere Rashke Qamar", "Nusrat Fateh Ali Khan", score = 0.92)
        val dupWork = ranked("id_2", "Mere Rashke Qamar (Remastered)", "Nusrat Fateh Ali Khan", score = 0.91)
        val distinct1 = ranked("id_3", "Afreen Afreen", "Nusrat Fateh Ali Khan", score = 0.89)
        val distinct2 = ranked("id_4", "Tumhe Dillagi", "Nusrat Fateh Ali Khan", score = 0.88)

        val res = DefaultSequenceOptimizer().optimize(
            ranked = listOf(dupA, dupWork, distinct1, distinct2),
            request = RecommendationRequest("req-narrow", RecommendationMode.SIMILAR_SONGS, limit = 3),
            config = SequenceConfig(
                targetCount = 3,
                artistCooldownWindow = 2,
                albumCooldownWindow = 2,
                maxArtistCountInBatch = 1,
                maxSlowOrSadStreak = 3,
                allowBridgeTransition = true,
                beamWidth = 4,
                candidateLookaheadLimit = 20,
                transitionWeight = 0.30,
            ),
        )

        // Should relax artist cap/cooldown to return all 3 distinct works, while still blocking duplicate workId id_2
        assertEquals(3, res.tracks.size)
        assertEquals(3, res.tracks.map { it.workId }.toSet().size)
        assertTrue(res.constraintRelaxations.isNotEmpty())
        assertTrue(res.tracks.none { it.song.id == "id_2" })
    }

    @Test
    fun deterministicOrderingAcrossRepeatedRunsAndValidIndices() {
        val pool = listOf(
            ranked("x1", "Track One", "Artist A", score = 0.85, tempoBucket = TempoBucket.MEDIUM),
            ranked("x2", "Track Two", "Artist B", score = 0.85, tempoBucket = TempoBucket.MEDIUM),
            ranked("x3", "Track Three", "Artist C", score = 0.85, tempoBucket = TempoBucket.MEDIUM),
            ranked("x4", "Track Four", "Artist D", score = 0.84, tempoBucket = TempoBucket.MEDIUM),
        )
        val optimizer = DefaultSequenceOptimizer()
        val req = RecommendationRequest("req-det", RecommendationMode.SONG_RADIO, limit = 4)

        val run1 = optimizer.optimize(pool, req)
        val run2 = optimizer.optimize(pool, req)

        assertEquals(run1.tracks.map { it.song.id }, run2.tracks.map { it.song.id })
        assertEquals(listOf(0, 1, 2, 3), run1.tracks.map { it.selectedPosition })
        assertTrue(run1.tracks.all { it.scoreBreakdown.containsKey("transition_score") && it.scoreBreakdown.containsKey("final_score") })
    }
}
