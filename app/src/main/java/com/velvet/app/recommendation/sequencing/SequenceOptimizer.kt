package com.velvet.app.recommendation.sequencing

import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RankedCandidate
import com.velvet.app.recommendation.model.RecommendationReason
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.RecommendedTrack
import com.velvet.app.recommendation.model.TrackFeatureRecord

data class SequenceOptimizationResult(
    val tracks: List<RecommendedTrack>,
    val usedBeamSearch: Boolean,
    val averageTransitionScore: Double,
    val constraintRelaxations: List<String> = emptyList(),
)

interface SequenceOptimizer {
    fun optimize(
        ranked: List<RankedCandidate>,
        request: RecommendationRequest,
        recentHistoryFeatures: List<TrackFeatureRecord> = emptyList(),
        seedFeatures: TrackFeatureRecord? = null,
        config: SequenceConfig = SequenceConfigs.forMode(request.mode, request.limit),
    ): SequenceOptimizationResult
}

/**
 * Deterministic bounded beam-search sequence optimizer with progressive diversity relaxation.
 *
 * Guarantees:
 * - Never admits candidates outside the pre-filtered/ranked eligible pool.
 * - Never relaxes duplicate recording/work exclusions.
 * - Uses soft artist/album cooldowns that only relax when distinct artists are scarce.
 * - Bounded candidate lookahead + beam width keeps worst-case latency sub-millisecond on JVM/device.
 */
class DefaultSequenceOptimizer(
    private val transitionScorer: TransitionScorer = DefaultTransitionScorer(),
) : SequenceOptimizer {

    private data class BeamStep(
        val ranked: RankedCandidate,
        val breakdown: TransitionBreakdown,
        val combinedStepScore: Double,
    )

    private data class BeamState(
        val steps: List<BeamStep>,
        val recentFeatures: List<TrackFeatureRecord>,
        val usedRecordingIds: Set<String>,
        val usedWorkIds: Set<String>,
        val usedCompositionKeys: Set<String>,
        val artistCounts: Map<String, Int>,
        val cumulativeScore: Double,
    )

    override fun optimize(
        ranked: List<RankedCandidate>,
        request: RecommendationRequest,
        recentHistoryFeatures: List<TrackFeatureRecord>,
        seedFeatures: TrackFeatureRecord?,
        config: SequenceConfig,
    ): SequenceOptimizationResult {
        if (ranked.isEmpty() || config.targetCount <= 0) {
            return SequenceOptimizationResult(emptyList(), usedBeamSearch = false, averageTransitionScore = 0.0)
        }

        // Bounded candidate slice preserves determinism and strict latency ceiling
        val pool = ranked.take(maxOf(config.targetCount * 3, config.candidateLookaheadLimit).coerceAtMost(ranked.size))
        val targetCount = minOf(config.targetCount, pool.size)
        val anchor = seedFeatures ?: recentHistoryFeatures.lastOrNull()

        val initialExcludedRecordings = request.excludedRecordingIds + request.queueRecordingIds
        val initialCompositionKeys = if (!request.allowExplicitUserIntentOverride) {
            (request.seedSongs + listOfNotNull(request.currentSong) + request.queueSongs + request.userQueueSongs)
                .mapNotNull { RecordingIdentityResolver.canonicalCompositionTitleKey(it).takeIf { k -> k.isNotBlank() } }
                .toSet()
        } else emptySet()
        val beamWidth = config.beamWidth.coerceIn(1, 6)
        val useBeam = beamWidth > 1 && pool.size > 1 && targetCount > 1

        var beam = listOf(
            BeamState(
                steps = emptyList(),
                recentFeatures = recentHistoryFeatures.takeLast(6),
                usedRecordingIds = initialExcludedRecordings,
                usedWorkIds = emptySet(),
                usedCompositionKeys = initialCompositionKeys,
                artistCounts = emptyMap(),
                cumulativeScore = 0.0,
            ),
        )
        val relaxations = linkedSetOf<String>()

        for (stepIndex in 0 until targetCount) {
            val nextStates = mutableListOf<BeamState>()

            for (state in beam) {
                val eligibleAtStep = filterStepCandidates(
                    pool = pool,
                    state = state,
                    strictArtistCooldown = true,
                    strictArtistCap = true,
                    config = config,
                ).ifEmpty {
                    relaxations += "relaxed_artist_cooldown"
                    filterStepCandidates(
                        pool = pool,
                        state = state,
                        strictArtistCooldown = false,
                        strictArtistCap = true,
                        config = config,
                    )
                }.ifEmpty {
                    relaxations += "relaxed_artist_batch_cap"
                    filterStepCandidates(
                        pool = pool,
                        state = state,
                        strictArtistCooldown = false,
                        strictArtistCap = false,
                        config = config,
                    )
                }

                if (eligibleAtStep.isEmpty()) continue

                val branchLimit = minOf(eligibleAtStep.size, if (useBeam) beamWidth * 2 else 1)
                for (cand in eligibleAtStep.take(branchLimit)) {
                    val prevFeat = state.recentFeatures.lastOrNull() ?: seedFeatures
                    val trans = transitionScorer.scoreTransition(
                        previous = prevFeat,
                        candidate = cand,
                        recentWindow = state.recentFeatures,
                        anchorFeatures = anchor,
                        config = config,
                    )
                    val stepScore = cand.rankScore + config.transitionWeight * trans.total
                    val nextArtists = state.artistCounts.toMutableMap()
                    cand.candidate.features?.primaryArtistIds?.forEach { a ->
                        nextArtists[a] = (nextArtists[a] ?: 0) + 1
                    }
                    val nextRecSet = state.usedRecordingIds + cand.candidate.identity.canonicalRecordingId
                    val nextWorkSet = state.usedWorkIds + cand.candidate.identity.workId
                    val compKey = RecordingIdentityResolver.canonicalCompositionTitleKey(cand.candidate.song)
                    val nextCompSet = if (!request.allowExplicitUserIntentOverride && compKey.isNotBlank()) {
                        state.usedCompositionKeys + compKey
                    } else state.usedCompositionKeys
                    val nextRecent = if (cand.candidate.features != null) {
                        (state.recentFeatures + cand.candidate.features).takeLast(6)
                    } else state.recentFeatures

                    nextStates += BeamState(
                        steps = state.steps + BeamStep(cand, trans, stepScore),
                        recentFeatures = nextRecent,
                        usedRecordingIds = nextRecSet,
                        usedWorkIds = nextWorkSet,
                        usedCompositionKeys = nextCompSet,
                        artistCounts = nextArtists,
                        cumulativeScore = state.cumulativeScore + stepScore,
                    )
                }
            }

            if (nextStates.isEmpty()) break

            beam = nextStates
                .sortedWith(
                    compareByDescending<BeamState> { it.cumulativeScore }
                        .thenBy { it.steps.joinToString("|") { s -> s.ranked.candidate.song.id } },
                )
                .take(beamWidth)
        }

        val best = beam.maxWithOrNull(
            compareBy<BeamState> { it.steps.size }
                .thenBy { it.cumulativeScore },
        ) ?: return SequenceOptimizationResult(emptyList(), usedBeamSearch = useBeam, averageTransitionScore = 0.0)

        val recommended = best.steps.mapIndexed { idx, step ->
            val cand = step.ranked.candidate
            val br = step.ranked.breakdown
            val trans = step.breakdown
            val mergedReasons = buildList {
                addAll(step.ranked.reasons)
                if (trans.tempoContinuity >= 0.45) {
                    add(RecommendationReason("SMOOTH_TEMPO_FLOW", "Smooth rhythmic transition from previous track", trans.tempoContinuity))
                }
                if (trans.bridgeBonus > 0.0) {
                    add(RecommendationReason("STYLE_BRIDGE", "Smooth stylistic bridge across queue", trans.bridgeBonus))
                }
            }

            val breakdownMap = linkedMapOf(
                "music_type_fit" to br.musicTypeFit,
                "genre_fit" to br.genreFit,
                "mood_fit" to br.moodFit,
                "audio_fit" to br.audioFit,
                "tempo_energy_fit" to br.tempoEnergyFit,
                "language_fit" to br.languageFit,
                "era_fit" to br.eraFit,
                "context_fit" to br.contextFit,
                "personal_taste_fit" to br.personalTasteFit,
                "search_intent_fit" to br.searchIntentFit,
                "artist_affinity_fit" to br.artistAffinityFit,
                "quality_fit" to br.qualityFit,
                "novelty_fit" to br.noveltyFit,
                "confidence_multiplier" to br.confidenceMultiplier,
                "rank_score" to step.ranked.rankScore,
                "transition_score" to trans.total,
                "transition_tempo" to trans.tempoContinuity,
                "transition_mood" to trans.moodSmoothness,
                "transition_genre" to trans.genreContinuity,
                "transition_repetition_penalty" to trans.repetitionPenalty,
                "final_score" to step.combinedStepScore,
            )

            RecommendedTrack(
                song = cand.song,
                recordingId = cand.identity.canonicalRecordingId,
                workId = cand.identity.workId,
                sourceIds = cand.sourceIds,
                rankScore = step.ranked.rankScore,
                sequenceScore = trans.total,
                finalScore = step.combinedStepScore,
                featureConfidence = cand.features?.metadataCompleteness ?: 0.0,
                releaseAudioClass = cand.identity.releaseClassification.audioClass,
                reasons = mergedReasons,
                scoreBreakdown = breakdownMap,
                selectedPosition = idx,
            )
        }

        val avgTrans = if (recommended.isNotEmpty()) {
            recommended.map { it.sequenceScore }.average()
        } else 0.0

        return SequenceOptimizationResult(
            tracks = recommended,
            usedBeamSearch = useBeam,
            averageTransitionScore = avgTrans,
            constraintRelaxations = relaxations.toList(),
        )
    }

    private fun filterStepCandidates(
        pool: List<RankedCandidate>,
        state: BeamState,
        strictArtistCooldown: Boolean,
        strictArtistCap: Boolean,
        config: SequenceConfig,
    ): List<RankedCandidate> {
        val cooldownSlice = if (strictArtistCooldown && config.artistCooldownWindow > 0) {
            state.recentFeatures.takeLast(config.artistCooldownWindow)
        } else emptyList()

        return pool.filter { ranked ->
            val cand = ranked.candidate
            if (cand.identity.canonicalRecordingId in state.usedRecordingIds) return@filter false
            if (cand.identity.workId in state.usedWorkIds) return@filter false
            if (state.usedCompositionKeys.isNotEmpty()) {
                val compKey = RecordingIdentityResolver.canonicalCompositionTitleKey(cand.song)
                if (compKey.isNotBlank() && compKey in state.usedCompositionKeys) return@filter false
            }

            val artists = cand.features?.primaryArtistIds.orEmpty()
            if (strictArtistCap && artists.isNotEmpty()) {
                val exceedsCap = artists.any { a -> (state.artistCounts[a] ?: 0) >= config.maxArtistCountInBatch }
                if (exceedsCap) return@filter false
            }

            if (cooldownSlice.isNotEmpty() && artists.isNotEmpty()) {
                val hitsCooldown = cooldownSlice.any { recent ->
                    artists.any { a -> a in recent.primaryArtistIds }
                }
                if (hitsCooldown) return@filter false
            }

            true
        }
    }
}
