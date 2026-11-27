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
        allowMixedLanguages: Boolean = false,
        artistLanguageMap: Map<String, com.velvet.app.recommendation.vibe.ArtistLanguageProfile> = emptyMap(),
        sessionVibeOverride: com.velvet.app.recommendation.vibe.SessionVibe? = null,
        randomSeed: Long? = null,
        sessionSkippedArtistCounts: Map<String, Int> = emptyMap(),
        sessionSkippedGenreCounts: Map<String, Int> = emptyMap(),
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
        val vibe = request.sessionVibe ?: com.velvet.app.recommendation.vibe.SessionVibe.fromRequest(
            request = request,
            featureRepository = featureRepository,
            artistLanguageMap = request.artistLanguageMap,
        )
        val effectiveRequest = if (request.sessionVibe != null) request else request.copy(sessionVibe = vibe)

        val rejectionCounts = linkedMapOf<String, Int>()
        val evalReasonsById = LinkedHashMap<String, List<String>>(pool.candidates.size)
        val eligible = pool.candidates.filter { cand ->
            val decision = eligibilityPolicy.evaluate(cand, effectiveRequest, profile, seedFeatures)
            evalReasonsById[cand.song.id] = decision.reasons
            if (!decision.eligible) {
                decision.reasons.forEach { reason ->
                    rejectionCounts[reason] = (rejectionCounts[reason] ?: 0) + 1
                }
            }
            decision.eligible
        }

        val dedupedResult = duplicateFilter.filter(eligible, effectiveRequest)
        val ranked = ranker.rank(dedupedResult.kept, effectiveRequest, profile, seedFeatures)

        val chronologicalLeadIn = (
            effectiveRequest.recentSongs.takeLast(4) +
                listOfNotNull(primarySeed) +
                effectiveRequest.userQueueSongs +
                effectiveRequest.queueSongs
            ).distinctBy { it.id }.takeLast(6)
        val recentFeatures = chronologicalLeadIn
            .map { featureRepository.getCachedOrExtract(it) }
        val seamAnchorFeatures = recentFeatures.lastOrNull() ?: seedFeatures
        val sequenced = sequenceOptimizer.optimize(
            ranked = ranked,
            request = effectiveRequest,
            recentHistoryFeatures = recentFeatures,
            seedFeatures = seamAnchorFeatures,
            userProfile = profile,
        )
        val finalTracks = applyLibraryRediscoveryCap(
            selected = sequenced.tracks,
            rankedPool = ranked,
            request = effectiveRequest,
            recentHistoryFeatures = recentFeatures,
            seedFeatures = seamAnchorFeatures,
            userProfile = profile,
        )

        val finishedAt = clock()
        val (totalFunnel, perGeneratorFunnels) = computePassiveFunnelCounts(
            pool = pool,
            evalReasonsById = evalReasonsById,
            keptAfterDuplicateFilter = dedupedResult.kept,
            acceptedTracks = finalTracks,
            request = effectiveRequest,
        )
        val diagnosticsId = UUID.randomUUID().toString()
        val diagRecord = RecommendationDiagnostics(
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
            returnedCount = finalTracks.size,
            durationMs = (finishedAt - startedAt).coerceAtLeast(0L),
            usedCache = pool.fromCache,
            recordedAtMillis = finishedAt,
            totalFunnel = totalFunnel,
            perGeneratorFunnels = perGeneratorFunnels,
        )
        diagnosticsLog.record(diagRecord)
        com.velvet.app.diagnostics.QueueHealthMonitor.recordEngineDiagnostics(
            diag = diagRecord,
            seed = primarySeed,
            featureRepository = featureRepository,
            requestedLanguages = request.requestedLanguageIds,
        )

        val finalSelectedIds = finalTracks.mapTo(HashSet()) { it.song.id }
        val overflowSongs = ranked.asSequence()
            .map { it.candidate.song }
            .filter { it.id !in finalSelectedIds }
            .take(80)
            .toList()

        return RecommendationResult(
            requestId = request.requestId,
            mode = request.mode,
            candidates = finalTracks,
            modelVersion = RankerWeights.PHASE5_MODEL_VERSION,
            generatedAtMillis = finishedAt,
            sourceHealth = pool.sourceHealth,
            diagnosticsId = diagnosticsId,
            overflowSongs = overflowSongs,
        )
    }

    private fun isLibraryOnlySource(sourceIds: Set<String>): Boolean =
        sourceIds.isNotEmpty() && sourceIds.all { it == "g6_library" || it == "g0_instant_catalog" }

    /**
     * C4: Caps owner's library rediscovery tracks to ~25% of an online autoplay batch when
     * fresh discovery candidates exist, while leaving library fallback intact offline.
     */
    private fun applyLibraryRediscoveryCap(
        selected: List<com.velvet.app.recommendation.model.RecommendedTrack>,
        rankedPool: List<com.velvet.app.recommendation.model.RankedCandidate>,
        request: RecommendationRequest,
        recentHistoryFeatures: List<com.velvet.app.recommendation.model.TrackFeatureRecord>,
        seedFeatures: com.velvet.app.recommendation.model.TrackFeatureRecord?,
        userProfile: com.velvet.app.recommendation.profile.UserTasteSnapshot? = null,
    ): List<com.velvet.app.recommendation.model.RecommendedTrack> {
        if (request.mode !in setOf(RecommendationMode.AUTOPLAY_REFILL, RecommendationMode.SIMILAR_SONGS, RecommendationMode.SONG_RADIO)) {
            return selected
        }
        val limit = request.limit
        if (limit < 4 || selected.isEmpty()) return selected

        val selectedIds = selected.mapTo(HashSet()) { it.song.id }
        val unusedDiscovery = rankedPool.filter {
            it.candidate.song.id !in selectedIds && !isLibraryOnlySource(it.candidate.sourceIds)
        }
        val selectedDiscoveryCount = selected.count { !isLibraryOnlySource(it.sourceIds) }
        val totalDiscoveryAvailable = selectedDiscoveryCount + unusedDiscovery.size
        // Never cap library fallback if external discovery candidates are empty/scarce
        if (totalDiscoveryAvailable < limit / 2) return selected

        val maxLibraryCount = (limit * 0.25).toInt().coerceAtLeast(1)
        val libraryInSelected = selected.count { isLibraryOnlySource(it.sourceIds) }
        if (libraryInSelected <= maxLibraryCount) return selected

        val kept = ArrayList<com.velvet.app.recommendation.model.RecommendedTrack>(limit)
        var libUsed = 0
        for (track in selected) {
            if (isLibraryOnlySource(track.sourceIds)) {
                if (libUsed < maxLibraryCount) {
                    kept.add(track)
                    libUsed++
                }
            } else {
                kept.add(track)
            }
        }
        if (kept.size < limit && unusedDiscovery.isNotEmpty()) {
            val fillSlots = limit - kept.size
            val existingRecs = kept.mapTo(HashSet()) { it.recordingId }
            val extra = sequenceOptimizer.optimize(
                ranked = unusedDiscovery.filter { it.candidate.identity.canonicalRecordingId !in existingRecs },
                request = request.copy(limit = fillSlots, excludedRecordingIds = request.excludedRecordingIds + existingRecs),
                recentHistoryFeatures = recentHistoryFeatures,
                seedFeatures = seedFeatures,
                userProfile = userProfile,
            ).tracks
            kept.addAll(extra.take(fillSlots))
        }
        return kept.mapIndexed { idx, t -> t.copy(selectedPosition = idx) }
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
        allowMixedLanguages: Boolean,
        artistLanguageMap: Map<String, com.velvet.app.recommendation.vibe.ArtistLanguageProfile>,
        sessionVibeOverride: com.velvet.app.recommendation.vibe.SessionVibe?,
        randomSeed: Long?,
        sessionSkippedArtistCounts: Map<String, Int>,
        sessionSkippedGenreCounts: Map<String, Int>,
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
            allowMixedLanguages = allowMixedLanguages,
            artistLanguageMap = artistLanguageMap,
            sessionVibe = sessionVibeOverride,
            randomSeed = randomSeed,
            sessionSkippedArtistCounts = sessionSkippedArtistCounts,
            sessionSkippedGenreCounts = sessionSkippedGenreCounts,
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

    /**
     * Passive read-only F0 funnel telemetry. Never modifies [acceptedTracks] or any gate outcome.
     */
    private fun computePassiveFunnelCounts(
        pool: com.velvet.app.recommendation.candidates.CandidatePool,
        evalReasonsById: Map<String, List<String>>,
        keptAfterDuplicateFilter: List<com.velvet.app.recommendation.model.CandidateTrack>,
        acceptedTracks: List<com.velvet.app.recommendation.model.RecommendedTrack>,
        request: RecommendationRequest,
    ): Pair<com.velvet.app.diagnostics.StageFunnelCounts, Map<String, com.velvet.app.diagnostics.StageFunnelCounts>> {
        val keptByDedupeIds = keptAfterDuplicateFilter.mapTo(HashSet()) { it.song.id }
        val acceptedIds = acceptedTracks.mapTo(HashSet()) { it.song.id }

        data class GatePass(
            val baseElig: Boolean,
            val idDedupe: Boolean,
            val langOk: Boolean,
            val genreTypeOk: Boolean,
            val purityOk: Boolean,
            val accepted: Boolean,
        )

        val refSongs = (
            request.seedSongs +
                listOfNotNull(request.currentSong) +
                request.queueSongs +
                request.userQueueSongs +
                request.recentSongs
            ).distinctBy { it.id }
        val excludedRecs = (
            request.excludedRecordingIds +
                request.seedRecordingIds +
                listOfNotNull(request.currentRecordingId) +
                request.queueRecordingIds +
                request.userQueueRecordingIds +
                request.recentRecordingIds +
                refSongs.map { RecordingIdentityResolver.resolve(it).canonicalRecordingId } +
                refSongs.map { com.velvet.app.playback.SongIdentity.canonicalGroupId(it) }
            ).toHashSet()
        val excludedIds = (request.excludedProviderIds + refSongs.map { it.id }).toHashSet()
        val blockComp = !request.allowExplicitUserIntentOverride
        val refComps = if (blockComp) {
            refSongs.mapNotNullTo(HashSet()) { RecordingIdentityResolver.canonicalCompositionTitleKey(it).takeIf { k -> k.isNotBlank() } }
        } else emptySet()

        // Preserve all keptAfterDuplicateFilter winners; deduplicate other base-eligible tracks for funnel accounting
        val seenRecs = HashSet<String>(excludedRecs).apply {
            keptAfterDuplicateFilter.forEach { add(it.identity.canonicalRecordingId) }
        }
        val seenComps = HashSet<String>(refComps).apply {
            if (blockComp) {
                keptAfterDuplicateFilter.forEach {
                    val ck = RecordingIdentityResolver.canonicalCompositionTitleKey(it.song)
                    if (ck.isNotBlank()) add(ck)
                }
            }
        }

        val passBySongId = LinkedHashMap<String, GatePass>(pool.candidates.size)
        for (cand in pool.candidates) {
            val id = cand.song.id
            val reasons = evalReasonsById[id].orEmpty()
            val baseElig = reasons.none { !isLanguageReason(it) && !isGenreTypeReason(it) && !isArtistPurityReason(it) }
            val langOk = baseElig && reasons.none(::isLanguageReason)
            val genreOk = langOk && reasons.none(::isGenreTypeReason)
            val purityOk = genreOk && reasons.none(::isArtistPurityReason)

            val idDedupe = when {
                !baseElig -> false
                id in keptByDedupeIds -> true
                else -> {
                    val recId = cand.identity.canonicalRecordingId
                    val grpId = com.velvet.app.playback.SongIdentity.canonicalGroupId(cand.song)
                    val compKey = if (blockComp) RecordingIdentityResolver.canonicalCompositionTitleKey(cand.song) else ""
                    val matchesKnown = id in excludedIds ||
                        recId in excludedRecs ||
                        grpId in excludedRecs ||
                        (compKey.isNotBlank() && compKey in excludedRecs) ||
                        refSongs.any { RecordingIdentityResolver.isSameRecording(it, cand.song) } ||
                        (compKey.isNotBlank() && compKey in seenComps) ||
                        recId in seenRecs ||
                        grpId in seenRecs
                    if (!matchesKnown) {
                        seenRecs.add(recId)
                        seenRecs.add(grpId)
                        if (compKey.isNotBlank()) seenComps.add(compKey)
                        true
                    } else false
                }
            }

            val finalPurityOk = purityOk && idDedupe
            passBySongId[id] = GatePass(
                baseElig = baseElig,
                idDedupe = idDedupe,
                langOk = langOk && idDedupe,
                genreTypeOk = genreOk && idDedupe,
                purityOk = finalPurityOk,
                accepted = finalPurityOk && id in acceptedIds,
            )
        }

        fun countFunnel(sourceId: String, fetched: Int, subset: List<com.velvet.app.recommendation.model.CandidateTrack>): com.velvet.app.diagnostics.StageFunnelCounts {
            var elig = 0
            var dedupe = 0
            var lang = 0
            var genre = 0
            var purity = 0
            var acc = 0
            for (c in subset) {
                val p = passBySongId[c.song.id] ?: continue
                if (p.baseElig) elig++
                if (p.idDedupe) dedupe++
                if (p.langOk) lang++
                if (p.genreTypeOk) genre++
                if (p.purityOk) purity++
                if (p.accepted) acc++
            }
            return com.velvet.app.diagnostics.StageFunnelCounts(
                sourceId = sourceId,
                fetched = maxOf(fetched, subset.size),
                afterEligibility = elig,
                afterIdentityDedupe = dedupe,
                afterDismissedFilter = dedupe,
                afterLanguageGate = lang,
                afterGenreTypeGate = genre,
                afterArtistPurity = purity,
                accepted = acc,
                appended = acc,
            )
        }

        val allSourceIds = (pool.rawCountBySource.keys + pool.sourceHealth.keys + pool.candidates.flatMap { it.sourceIds }).toSortedSet()
        val perSource = LinkedHashMap<String, com.velvet.app.diagnostics.StageFunnelCounts>()
        for (srcId in allSourceIds) {
            val rawFetched = pool.rawCountBySource[srcId] ?: pool.sourceHealth[srcId]?.returnedCount ?: 0
            val srcCands = pool.candidates.filter { srcId in it.sourceIds }
            perSource[srcId] = countFunnel(srcId, rawFetched, srcCands)
        }
        val total = countFunnel("TOTAL", pool.rawFetchedCount, pool.candidates).copy(
            accepted = acceptedTracks.size,
            appended = acceptedTracks.size,
        )
        return total to perSource
    }

    private fun isLanguageReason(reason: String): Boolean =
        reason == "artist_language_mismatch" ||
            reason == "vibe_language_mismatch" ||
            reason == "explicit_language_mismatch" ||
            reason == "unknown_language_low_vibe_or_untrusted_lineage" ||
            reason == "devotional_regional_mismatch"

    private fun isGenreTypeReason(reason: String): Boolean =
        reason == "music_type_genre_mismatch" ||
            reason == "genre_compatibility_below_threshold" ||
            reason == "devotional_category_mismatch"

    private fun isArtistPurityReason(reason: String): Boolean =
        reason == "artist_station_unrelated_artist"
}
