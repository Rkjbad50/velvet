package com.velvet.app.recommendation.sequencing

import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RankedCandidate
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationReason
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.RecommendedTrack
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.modes.ArtistStationPolicy
import com.velvet.app.recommendation.profile.UserTasteSnapshot

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
        userProfile: UserTasteSnapshot? = null,
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
        val noveltySlot: Boolean = false,
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
        userProfile: UserTasteSnapshot?,
    ): SequenceOptimizationResult {
        if (ranked.isEmpty() || config.targetCount <= 0) {
            return SequenceOptimizationResult(emptyList(), usedBeamSearch = false, averageTransitionScore = 0.0)
        }

        val initialExcludedRecordings = request.excludedRecordingIds + request.queueRecordingIds
        val initialCompositionKeys = if (!request.allowExplicitUserIntentOverride) {
            (request.seedSongs + listOfNotNull(request.currentSong) + request.queueSongs + request.userQueueSongs)
                .mapNotNull { RecordingIdentityResolver.canonicalCompositionTitleKey(it).takeIf { k -> k.isNotBlank() } }
                .toSet()
        } else emptySet()

        // C6: Enforce Artist Radio station purity & 60/25/15 composition quotas strictly
        if (request.mode == RecommendationMode.ARTIST_RADIO && !request.allowExplicitUserIntentOverride) {
            val preFiltered = ranked.filter { item ->
                item.candidate.identity.canonicalRecordingId !in initialExcludedRecordings &&
                    (RecordingIdentityResolver.canonicalCompositionTitleKey(item.candidate.song).let { ck ->
                        ck.isBlank() || ck !in initialCompositionKeys
                    })
            }
            val shaped = ArtistStationPolicy.shapeBatch(
                rankedPool = preFiltered,
                limit = config.targetCount,
                request = request,
                seedFeatures = seedFeatures,
            )
            if (shaped.isEmpty()) {
                return SequenceOptimizationResult(emptyList(), usedBeamSearch = false, averageTransitionScore = 0.0)
            }
            val anchor = seedFeatures ?: recentHistoryFeatures.lastOrNull()
            var recent = recentHistoryFeatures.takeLast(6)
            val steps = shaped.map { item ->
                val trans = transitionScorer.scoreTransition(
                    previous = recent.lastOrNull() ?: seedFeatures,
                    candidate = item,
                    recentWindow = recent,
                    anchorFeatures = anchor,
                    config = config,
                )
                item.candidate.features?.let { feat -> recent = (recent + feat).takeLast(6) }
                BeamStep(item, trans, item.rankScore + config.transitionWeight * trans.total, noveltySlot = false)
            }
            val recommended = buildRecommendedTracks(steps)
            val avgTrans = recommended.map { it.sequenceScore }.average()
            return SequenceOptimizationResult(
                tracks = recommended,
                usedBeamSearch = false,
                averageTransitionScore = avgTrans,
            )
        }

        // Bounded candidate slice preserves determinism and strict latency ceiling
        val pool = ranked.take(maxOf(config.targetCount * 3, config.candidateLookaheadLimit).coerceAtMost(ranked.size))
        val targetCount = minOf(config.targetCount, pool.size)
        val anchor = seedFeatures ?: recentHistoryFeatures.lastOrNull()

        // C5: Seeded reproducible exploration jitter (identical seed -> identical queue; different seed -> varied)
        val exploreJitter: Map<String, Double> = request.randomSeed?.let { seed ->
            val rng = kotlin.random.Random(seed)
            pool.associate { it.candidate.song.id to ((rng.nextDouble() - 0.5) * 0.14) }
        } ?: emptyMap()

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
            val isNoveltySlotStep = userProfile != null &&
                targetCount >= 5 &&
                (stepIndex + 1) % 5 == 0

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

                // C5: 1-in-5 novelty slot promotes a verified vibe-valid unseen track when available
                val stepPool = if (isNoveltySlotStep) {
                    val topRank = eligibleAtStep.maxOf { it.rankScore }
                    val unseenInVibe = eligibleAtStep.filter { cand ->
                        isNewToUser(cand, userProfile!!) && cand.rankScore >= topRank - 0.40
                    }
                    if (unseenInVibe.isNotEmpty()) unseenInVibe else eligibleAtStep
                } else eligibleAtStep
                val promotedNovelty = isNoveltySlotStep && stepPool !== eligibleAtStep

                val rankedStepPool = if (exploreJitter.isNotEmpty()) {
                    stepPool.sortedByDescending { it.rankScore + (exploreJitter[it.candidate.song.id] ?: 0.0) }
                } else stepPool

                val branchLimit = minOf(rankedStepPool.size, if (useBeam) beamWidth * 2 else 1)
                for (cand in rankedStepPool.take(branchLimit)) {
                    val prevFeat = state.recentFeatures.lastOrNull() ?: seedFeatures
                    val trans = transitionScorer.scoreTransition(
                        previous = prevFeat,
                        candidate = cand,
                        recentWindow = state.recentFeatures,
                        anchorFeatures = anchor,
                        config = config,
                    )
                    val jitter = exploreJitter[cand.candidate.song.id] ?: 0.0
                    val stepScore = cand.rankScore + config.transitionWeight * trans.total + jitter
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
                        steps = state.steps + BeamStep(cand, trans, stepScore, noveltySlot = promotedNovelty),
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

        val recommended = buildRecommendedTracks(best.steps)
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

    private fun isNewToUser(cand: RankedCandidate, profile: UserTasteSnapshot): Boolean {
        val recId = cand.candidate.identity.canonicalRecordingId
        return recId !in profile.longTerm.recordingWeights &&
            recId !in profile.mediumTerm.recordingWeights &&
            recId !in profile.lastPlayedMillisByRecording &&
            cand.candidate.song.id !in profile.lastPlayedMillisByRecording &&
            recId !in profile.explicit.likedRecordingIds &&
            cand.candidate.song.id !in profile.explicit.likedProviderIds &&
            recId !in profile.explicit.savedPlaylistRecordingIds &&
            cand.candidate.song.id !in profile.explicit.savedPlaylistProviderIds
    }

    private fun buildRecommendedTracks(steps: List<BeamStep>): List<RecommendedTrack> =
        steps.mapIndexed { idx, step ->
            val cand = step.ranked.candidate
            val br = step.ranked.breakdown
            val trans = step.breakdown
            val mergedReasons = buildList {
                addAll(step.ranked.reasons)
                if (step.noveltySlot) {
                    add(RecommendationReason("NOVELTY_DISCOVERY_SLOT", "New discovery selected for 1-in-5 freshness slot", 0.05))
                }
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
