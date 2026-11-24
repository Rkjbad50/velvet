package com.velvet.app.recommendation.engine

import com.velvet.app.data.Song
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.filtering.DefaultEligibilityPolicy
import com.velvet.app.recommendation.filtering.DuplicateFilter
import com.velvet.app.recommendation.filtering.EligibilityPolicy
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.RecommendationResult
import com.velvet.app.recommendation.profile.UserProfileRepository
import com.velvet.app.recommendation.ranking.CandidateRanker
import com.velvet.app.recommendation.ranking.DefaultCandidateRanker
import com.velvet.app.recommendation.ranking.RankerWeights
import com.velvet.app.recommendation.sequencing.DefaultSequenceOptimizer
import com.velvet.app.recommendation.sequencing.SequenceOptimizer
import java.util.UUID

interface RecommendationEngine {
    suspend fun recommend(request: RecommendationRequest): RecommendationResult

    suspend fun nextSimilar(
        seed: Song,
        contextSnapshot: List<Song> = emptyList(),
        mode: RecommendationMode = RecommendationMode.AUTOPLAY_REFILL,
        limit: Int = 10,
        radioSessionId: String? = null,
        excludedRecordingIds: Set<String> = emptySet(),
        excludedProviderIds: Set<String> = emptySet(),
        queueSongs: List<Song> = emptyList(),
        userQueueSongs: List<Song> = emptyList(),
        recentSongs: List<Song> = emptyList(),
        selectedArtistId: String? = null,
        requestedLanguageIds: Set<String> = emptySet(),
        allowExplicitUserIntentOverride: Boolean = false,
        seedSongsOverride: List<Song> = emptyList(),
    ): RecommendationResult

    fun invalidateCandidateCache(seedId: String? = null)
}

/**
 * Unified orchestration facade uniting:
 * Multi-source CandidatePoolBuilder -> Strict Original Studio Audio EligibilityPolicy ->
 * DuplicateFilter -> Normalised Mode-Aware CandidateRanker -> Bounded Beam SequenceOptimizer.
 */
class DefaultRecommendationEngine(
    private val candidatePoolBuilder: CandidatePoolBuilder,
    private val userProfileRepository: UserProfileRepository,
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val eligibilityPolicy: EligibilityPolicy = DefaultEligibilityPolicy(),
    private val duplicateFilter: DuplicateFilter = DuplicateFilter(),
    private val ranker: CandidateRanker = DefaultCandidateRanker(),
    private val sequenceOptimizer: SequenceOptimizer = DefaultSequenceOptimizer(),
    val diagnosticsLog: RecommendationDiagnosticsLog = RecommendationDiagnosticsLog(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationEngine {

    override suspend fun recommend(request: RecommendationRequest): RecommendationResult {
        val startedAt = clock()
        val profile = userProfileRepository.getSnapshot(sessionId = request.radioSessionId)
        val pool = candidatePoolBuilder.build(request, profile)

        val primarySeed = request.primarySeed
        val seedFeatures = buildWeightedSeedFeatures(request, primarySeed)

        val rejectionCounts = linkedMapOf<String, Int>()
        val eligible = pool.candidates.filter { cand ->
            val decision = eligibilityPolicy.evaluate(cand, request, profile, seedFeatures)
            if (!decision.eligible) {
                decision.reasons.forEach { reason ->
                    rejectionCounts[reason] = (rejectionCounts[reason] ?: 0) + 1
                }
            }
            decision.eligible
        }

        val dedupedResult = duplicateFilter.filter(eligible, request)
        val ranked = ranker.rank(dedupedResult.kept, request, profile, seedFeatures)

        val chronologicalLeadIn = (
            request.recentSongs.takeLast(4) +
                listOfNotNull(primarySeed) +
                request.userQueueSongs +
                request.queueSongs
            ).distinctBy { it.id }.takeLast(6)
        val recentFeatures = chronologicalLeadIn
            .map { featureRepository.getCachedOrExtract(it) }
        val seamAnchorFeatures = recentFeatures.lastOrNull() ?: seedFeatures
        val sequenced = sequenceOptimizer.optimize(
            ranked = ranked,
            request = request,
            recentHistoryFeatures = recentFeatures,
            seedFeatures = seamAnchorFeatures,
        )

        val finishedAt = clock()
        val diagnosticsId = UUID.randomUUID().toString()
        diagnosticsLog.record(
            RecommendationDiagnostics(
                diagnosticsId = diagnosticsId,
                requestId = request.requestId,
                mode = request.mode,
                rawCandidateCount = pool.rawFetchedCount,
                eligibleCandidateCount = eligible.size,
                duplicateRemovedCount = dedupedResult.removedAlreadyKnownCount + dedupedResult.removedDuplicateUploadCount,
                rejectedReasonCounts = rejectionCounts,
                sourceHealth = pool.sourceHealth,
                averageTransitionScore = sequenced.averageTransitionScore,
                constraintRelaxations = sequenced.constraintRelaxations,
                returnedCount = sequenced.tracks.size,
                durationMs = (finishedAt - startedAt).coerceAtLeast(0L),
                usedCache = pool.fromCache,
                recordedAtMillis = finishedAt,
            ),
        )

        return RecommendationResult(
            requestId = request.requestId,
            mode = request.mode,
            candidates = sequenced.tracks,
            modelVersion = RankerWeights.PHASE5_MODEL_VERSION,
            generatedAtMillis = finishedAt,
            sourceHealth = pool.sourceHealth,
            diagnosticsId = diagnosticsId,
        )
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
    ): RecommendationResult {
        val snapshotRecs = contextSnapshot.map { RecordingIdentityResolver.resolve(it).canonicalRecordingId }
        val snapshotIds = contextSnapshot.map { it.id }
        val effectiveSeeds = if (seedSongsOverride.isNotEmpty()) seedSongsOverride else listOf(seed)
        val request = RecommendationRequest(
            requestId = UUID.randomUUID().toString(),
            mode = mode,
            seedSongs = effectiveSeeds,
            seedRecordingIds = effectiveSeeds.map { RecordingIdentityResolver.resolve(it).canonicalRecordingId },
            currentSong = seed,
            currentRecordingId = RecordingIdentityResolver.resolve(seed).canonicalRecordingId,
            recentSongs = recentSongs,
            queueSongs = queueSongs,
            userQueueSongs = userQueueSongs,
            excludedRecordingIds = excludedRecordingIds + snapshotRecs,
            excludedProviderIds = excludedProviderIds + snapshotIds,
            selectedArtistId = selectedArtistId,
            requestedLanguageIds = requestedLanguageIds,
            radioSessionId = radioSessionId,
            limit = limit,
            allowExplicitUserIntentOverride = allowExplicitUserIntentOverride,
            requireVerifiedOriginalAudio = true,
            createdAtMillis = clock(),
        )
        return recommend(request)
    }

    /**
     * Blends anchor (45%) + recent positive radio tracks (25% / 20% / 10%) for evolved SONG_RADIO / SIMILAR_SONGS
     * without permitting incompatible categories to overwrite primary anchor identity.
     */
    private fun buildWeightedSeedFeatures(
        request: RecommendationRequest,
        primarySeed: Song?,
    ): com.velvet.app.recommendation.model.TrackFeatureRecord? {
        val anchor = primarySeed?.let { featureRepository.getCachedOrExtract(it) } ?: return null
        if (request.seedSongs.size <= 1 || request.mode == RecommendationMode.ARTIST_RADIO) {
            return anchor
        }
        val decayWeights = listOf(0.45, 0.25, 0.20, 0.10)
        val seedRecords = request.seedSongs.take(decayWeights.size).mapIndexed { idx, s ->
            decayWeights[idx] to featureRepository.getCachedOrExtract(s)
        }
        val anchorTopType = anchor.musicTypes.maxByOrNull { it.value }?.key
        val compatSeeds = seedRecords.filter { (w, rec) ->
            val recType = rec.musicTypes.maxByOrNull { it.value }?.key
            if (anchorTopType == null || recType == null) w == decayWeights[0]
            else com.velvet.app.recommendation.features.GenreOntology.musicTypeCompatibility(anchorTopType, recType) >= 0.45
        }
        if (compatSeeds.size <= 1) return anchor

        val normFactor = compatSeeds.sumOf { it.first }.coerceAtLeast(1e-6)
        val genres = linkedMapOf<String, Double>()
        val subgenres = linkedMapOf<String, Double>()
        val moods = linkedMapOf<String, Double>()
        for ((w, rec) in compatSeeds) {
            val weight = w / normFactor
            rec.genreIds.forEach { (g, v) -> genres[g] = (genres[g] ?: 0.0) + v * weight }
            rec.subgenreIds.forEach { (sg, v) -> subgenres[sg] = (subgenres[sg] ?: 0.0) + v * weight }
            rec.moodIds.forEach { (m, v) -> moods[m] = (moods[m] ?: 0.0) + v * weight }
        }
        return anchor.copy(
            genreIds = genres.mapValues { it.value.coerceIn(0.0, 1.0) },
            subgenreIds = subgenres.mapValues { it.value.coerceIn(0.0, 1.0) },
            moodIds = moods.mapValues { it.value.coerceIn(0.0, 1.0) },
        )
    }

    override fun invalidateCandidateCache(seedId: String?) {
        candidatePoolBuilder.invalidateCache()
    }
}
