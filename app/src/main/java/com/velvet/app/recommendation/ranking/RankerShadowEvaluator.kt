package com.velvet.app.recommendation.ranking

import com.velvet.app.playback.AutoplayPipeline
import com.velvet.app.playback.CandidateSource
import com.velvet.app.playback.SeedProfile
import com.velvet.app.playback.SessionHistory
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.filtering.DefaultEligibilityPolicy
import com.velvet.app.recommendation.filtering.DuplicateFilter
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.RankedCandidate
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import kotlinx.coroutines.runBlocking

data class ShadowComparisonReport(
    val requestId: String,
    val mode: RecommendationMode,
    val legacyIds: List<String>,
    val phase5Ids: List<String>,
    val legacyLeakCountOfVideosOrUnverified: Int,
    val phase5LeakCountOfVideosOrUnverified: Int,
    val topKOverlapRate: Double,
    val phase5RankedCandidates: List<RankedCandidate>,
)

/**
 * Offline & shadow-mode evaluator running legacy [AutoplayPipeline] and Phase 5 [CandidateRanker]
 * + Strict Original Studio Audio gate side-by-side over identical candidate pools without altering live queues.
 */
class RankerShadowEvaluator(
    private val ranker: CandidateRanker = DefaultCandidateRanker(),
    private val eligibilityPolicy: DefaultEligibilityPolicy = DefaultEligibilityPolicy(),
    private val duplicateFilter: DuplicateFilter = DuplicateFilter(),
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
) {
    fun evaluateShadow(
        pool: List<CandidateTrack>,
        request: RecommendationRequest,
        profile: UserTasteSnapshot,
        topK: Int = 5,
    ): ShadowComparisonReport {
        val seed = request.primarySeed
            ?: return ShadowComparisonReport(request.requestId, request.mode, emptyList(), emptyList(), 0, 0, 0.0, emptyList())

        val rawSongs = pool.map { it.song }
        val legacyPipeline = AutoplayPipeline(
            sources = listOf(CandidateSource { rawSongs }),
            history = SessionHistory(100),
            tasteProfileProvider = { profile.toLegacyTasteProfile() },
        )
        val legacySongs = runBlocking {
            legacyPipeline.next(
                seedProfile = SeedProfile(
                    current = seed,
                    recentSongs = request.recentSongs,
                    isArtistRadio = request.mode == RecommendationMode.ARTIST_RADIO,
                ),
                queueSnapshot = request.queueSongs + request.userQueueSongs,
                n = request.limit,
            )
        }
        val legacyIds = legacySongs.map { it.id }

        val candById = pool.associateBy { it.song.id }
        val legacyLeakCount = legacyIds.count { id ->
            val cls = candById[id]?.identity?.releaseClassification
            cls != null && !cls.isVerifiedOriginalAudio
        }

        val seedFeatures = featureRepository.getCachedOrExtract(seed)
        val eligible = pool.filter { cand ->
            eligibilityPolicy.evaluate(cand, request, profile, seedFeatures).eligible
        }
        val unique = duplicateFilter.filter(eligible, request).kept
        val ranked = ranker.rank(unique, request, profile, seedFeatures).take(request.limit)
        val phase5Ids = ranked.map { it.candidate.song.id }
        val phase5LeakCount = ranked.count { !it.candidate.identity.releaseClassification.isVerifiedOriginalAudio }

        val effectiveK = minOf(topK, minOf(legacyIds.size, phase5Ids.size)).coerceAtLeast(1)
        val overlap = if (legacyIds.isEmpty() || phase5Ids.isEmpty()) 0.0 else {
            legacyIds.take(topK).toSet().intersect(phase5Ids.take(topK).toSet()).size.toDouble() / effectiveK.toDouble()
        }

        return ShadowComparisonReport(
            requestId = request.requestId,
            mode = request.mode,
            legacyIds = legacyIds,
            phase5Ids = phase5Ids,
            legacyLeakCountOfVideosOrUnverified = legacyLeakCount,
            phase5LeakCountOfVideosOrUnverified = phase5LeakCount,
            topKOverlapRate = overlap,
            phase5RankedCandidates = ranked,
        )
    }
}
