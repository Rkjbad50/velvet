package com.velvet.app.recommendation.candidates

import com.velvet.app.data.BoundedCache
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.CandidateSourceEvidence
import com.velvet.app.recommendation.model.CandidateSourceStatus
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.SourceHealth
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class CandidatePool(
    val candidates: List<CandidateTrack>,
    val sourceHealth: Map<String, SourceHealth>,
    val rawFetchedCount: Int,
    val fromCache: Boolean = false,
)

/**
 * Multi-source candidate orchestrator with:
 * - bounded parallel source execution & timeouts;
 * - per-source circuit breakers;
 * - context-aware TTL caching (keyed by seed + mode + locale + languages + schema version);
 * - per-source quotas & per-artist pool diversity caps;
 * - complete source provenance preservation (never mutates queues directly).
 */
class CandidatePoolBuilder(
    private val sources: List<RecommendationCandidateSource>,
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val circuitBreaker: SourceCircuitBreaker = SourceCircuitBreaker(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val perSourceTimeoutMs: Long = 2_000L,
    private val overallDeadlineMs: Long = 2_300L,
    private val cacheTtlMs: Long = 15 * 60 * 1000L,
    private val maxRawPoolSize: Int = 200,
    private val enableEarlyQuorum: Boolean = false,
    private val earlyQuorumMinCandidates: Int = 14,
    private val earlyQuorumGraceMs: Long = 160L,
) {
    private data class CachedPoolEntry(
        val pool: CandidatePool,
        val storedAtMillis: Long,
    )

    private val cache = BoundedCache<String, CachedPoolEntry>(50)

    suspend fun build(
        request: RecommendationRequest,
        userProfile: UserTasteSnapshot,
    ): CandidatePool = coroutineScope {
        val now = clock()
        val cacheKey = buildCacheKey(request)
        val cached = cache[cacheKey]
        if (cached != null && (now - cached.storedAtMillis) in 0L..cacheTtlMs) {
            return@coroutineScope cached.pool.copy(fromCache = true)
        }

        val seedFeatures = request.seedSongs.map { featureRepository.getCachedOrExtract(it) }
            .ifEmpty { listOfNotNull(request.currentSong?.let { featureRepository.getCachedOrExtract(it) }) }

        val enabledSources = sources.filter { it.enabled }
        if (enabledSources.isEmpty()) {
            return@coroutineScope CandidatePool(emptyList(), emptyMap(), 0)
        }

        val quotas = computeSourceQuotas(request.mode)
        val collected = ConcurrentHashMap<String, CandidateSourceResult>()
        val completedIds = ConcurrentHashMap.newKeySet<String>()
        val seenUniqueSongIds = ConcurrentHashMap.newKeySet<String>()
        val quorumSignal = CompletableDeferred<Unit>()

        fun notifySourceDone(res: CandidateSourceResult) {
            collected[res.sourceId] = res
            completedIds.add(res.sourceId)
            res.candidates.forEach { seenUniqueSongIds.add(it.song.id) }
            if (!enableEarlyQuorum || quorumSignal.isCompleted) return

            if (completedIds.size >= enabledSources.size) {
                quorumSignal.complete(Unit)
                return
            }
            val primaryDone = when (request.mode) {
                RecommendationMode.ARTIST_RADIO ->
                    ("g3_primary_artist" in completedIds) &&
                        ("g1_related" in completedIds || seenUniqueSongIds.size >= earlyQuorumMinCandidates)
                else ->
                    (("g1_related" in completedIds) && ("g5_genre_neighbours" in completedIds)) ||
                        ((("g1_related" in completedIds) || ("g5_genre_neighbours" in completedIds)) &&
                            seenUniqueSongIds.size >= earlyQuorumMinCandidates)
            }
            if (primaryDone) {
                quorumSignal.complete(Unit)
            }
        }

        val jobs = enabledSources.map { source ->
            async(Dispatchers.IO) {
                if (!circuitBreaker.allowRequest(source.sourceId)) {
                    val open = CandidateSourceResult(
                        sourceId = source.sourceId,
                        candidates = emptyList(),
                        fetchedAtMillis = clock(),
                        latencyMillis = 0L,
                        status = CandidateSourceStatus.CIRCUIT_OPEN,
                        errorCode = "circuit_open",
                    )
                    circuitBreaker.recordResult(open)
                    notifySourceDone(open)
                    return@async
                }
                val quotaMax = quotas[source.sourceId] ?: DEFAULT_SOURCE_QUOTA
                val srcReq = CandidateSourceRequest(
                    request = request,
                    seedFeatures = seedFeatures,
                    userProfile = userProfile,
                    maxCandidates = quotaMax,
                    deadlineMillis = clock() + perSourceTimeoutMs,
                )
                val res = try {
                    withTimeoutOrNull(perSourceTimeoutMs) {
                        source.fetch(srcReq)
                    } ?: CandidateSourceResult(
                        sourceId = source.sourceId,
                        candidates = emptyList(),
                        fetchedAtMillis = clock(),
                        latencyMillis = perSourceTimeoutMs,
                        status = CandidateSourceStatus.TIMEOUT,
                        errorCode = "timeout_${perSourceTimeoutMs}ms",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CandidateSourceResult(
                        sourceId = source.sourceId,
                        candidates = emptyList(),
                        fetchedAtMillis = clock(),
                        latencyMillis = 0L,
                        status = CandidateSourceStatus.UNAVAILABLE,
                        errorCode = e.message ?: "source_exception",
                    )
                }
                circuitBreaker.recordResult(res)
                notifySourceDone(res)
            }
        }
        if (enableEarlyQuorum) {
            withTimeoutOrNull(overallDeadlineMs) {
                quorumSignal.await()
                if (completedIds.size < enabledSources.size) {
                    withTimeoutOrNull(earlyQuorumGraceMs) {
                        jobs.joinAll()
                    }
                }
            }
        } else {
            withTimeoutOrNull(overallDeadlineMs) {
                jobs.joinAll()
            }
        }
        jobs.forEach { if (it.isActive) it.cancel() }

        val sourceResults = enabledSources.map { source ->
            collected[source.sourceId] ?: CandidateSourceResult(
                sourceId = source.sourceId,
                candidates = emptyList(),
                fetchedAtMillis = clock(),
                latencyMillis = overallDeadlineMs,
                status = CandidateSourceStatus.TIMEOUT,
                errorCode = "overall_deadline_${overallDeadlineMs}ms",
            ).also { circuitBreaker.recordResult(it) }
        }

        val healthMap = linkedMapOf<String, SourceHealth>()
        val rawMergedByVideoId = linkedMapOf<String, MutableList<RawTrackCandidate>>()
        var rawFetchedCount = 0

        for (res in sourceResults) {
            healthMap[res.sourceId] = circuitBreaker.currentHealth(res.sourceId) ?: SourceHealth(
                sourceId = res.sourceId,
                status = res.status,
                latencyMillis = res.latencyMillis,
                returnedCount = res.candidates.size,
                errorCode = res.errorCode,
            )
            for (cand in res.candidates) {
                rawFetchedCount++
                rawMergedByVideoId.getOrPut(cand.song.id) { mutableListOf() }.add(cand)
            }
        }

        // Apply per-artist pre-rank pool diversity cap in non-Artist-Radio modes
        val maxPerArtist = if (request.mode == RecommendationMode.ARTIST_RADIO) Int.MAX_VALUE else 6
        val artistCounts = mutableMapOf<String, Int>()
        val keptEntries = mutableListOf<Pair<String, List<RawTrackCandidate>>>()

        for ((videoId, evidences) in rawMergedByVideoId) {
            if (keptEntries.size >= maxRawPoolSize) break
            val song = evidences.first().song
            val primaryArtist = normalizePrimaryArtist(song.artist)
            if (primaryArtist.isNotBlank() && maxPerArtist != Int.MAX_VALUE) {
                val count = artistCounts[primaryArtist] ?: 0
                if (count >= maxPerArtist) continue
                artistCounts[primaryArtist] = count + 1
            }
            keptEntries.add(videoId to evidences)
        }

        val songsForFeatures = keptEntries.map { it.second.first().song }
        val featureMap = featureRepository.enrichCached(songsForFeatures)
        val retrievedAt = clock()

        val finalCandidates = keptEntries.map { (videoId, rawList) ->
            val song = rawList.first().song
            val identity = RecordingIdentityResolver.resolve(song, fetchedAtMillis = retrievedAt)
            val evidenceList = rawList.map { r ->
                CandidateSourceEvidence(
                    sourceId = r.sourceId,
                    rank = r.sourceRank,
                    retrievalScore = r.retrievalScore,
                    fetchedAtMillis = r.fetchedAtMillis,
                    evidenceCode = r.evidenceCode,
                    confidence = r.confidence,
                )
            }
            CandidateTrack(
                song = song,
                identity = identity,
                features = featureMap[videoId],
                sourceEvidence = evidenceList,
                candidateRetrievedAtMillis = retrievedAt,
            )
        }

        val pool = CandidatePool(
            candidates = finalCandidates,
            sourceHealth = healthMap,
            rawFetchedCount = rawFetchedCount,
            fromCache = false,
        )
        if (finalCandidates.isNotEmpty()) {
            cache[cacheKey] = CachedPoolEntry(pool, retrievedAt)
        }
        pool
    }

    fun invalidateCache() = cache.clear()

    private fun buildCacheKey(req: RecommendationRequest): String {
        val seedKey = req.primarySeed?.let { RecordingIdentityResolver.resolve(it).canonicalRecordingId }
            ?: req.seedRecordingIds.joinToString(",")
        return "$seedKey|${req.mode.name}|${req.locale.orEmpty()}|${req.selectedArtistId.orEmpty()}|" +
            "${req.requestedLanguageIds.sorted().joinToString(",")}|${req.allowMixedLanguages}|${req.artistLanguageMap.size}|v${TrackFeatureRecord.CURRENT_FEATURE_SCHEMA_VERSION}"
    }

    private fun computeSourceQuotas(mode: RecommendationMode): Map<String, Int> = when (mode) {
        RecommendationMode.ARTIST_RADIO -> mapOf(
            "g0_instant_catalog" to 20,
            "g3_primary_artist" to 45,
            "g4_collaborators" to 20,
            "g1_related" to 25,
            "g5_genre_neighbours" to 15,
            "g6_library" to 15,
            "g2_same_release" to 15,
        )
        else -> mapOf(
            "g0_instant_catalog" to 25,
            "g1_related" to 40,
            "g5_genre_neighbours" to 35,
            "g2_same_release" to 20,
            "g3_primary_artist" to 10,
            "g4_collaborators" to 15,
            "g6_library" to 25,
            "g7_session_momentum" to 15,
            "g13_search_intent" to 12,
        )
    }

    companion object {
        private const val DEFAULT_SOURCE_QUOTA = 25
    }
}
