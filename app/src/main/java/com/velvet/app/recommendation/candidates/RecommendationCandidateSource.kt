package com.velvet.app.recommendation.candidates

import com.velvet.app.data.Song
import com.velvet.app.recommendation.model.CandidateSourceStatus
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.SourceHealth
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import java.util.concurrent.ConcurrentHashMap

data class RawTrackCandidate(
    val song: Song,
    val sourceId: String,
    val sourceRank: Int,
    val retrievalScore: Double? = null,
    val evidenceCode: String,
    val confidence: Double = 0.5,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
)

data class CandidateSourceRequest(
    val request: RecommendationRequest,
    val seedFeatures: List<TrackFeatureRecord>,
    val userProfile: UserTasteSnapshot,
    val maxCandidates: Int,
    val deadlineMillis: Long,
)

data class CandidateSourceResult(
    val sourceId: String,
    val candidates: List<RawTrackCandidate>,
    val fetchedAtMillis: Long,
    val latencyMillis: Long,
    val status: CandidateSourceStatus,
    val errorCode: String? = null,
)

interface RecommendationCandidateSource {
    val sourceId: String
    val enabled: Boolean get() = true
    suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult
}

/**
 * Per-source rolling health tracker and exponential backoff circuit breaker.
 * Prevents repeatedly hammering timed-out or rate-limited external APIs.
 */
class SourceCircuitBreaker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val failureThreshold: Int = 5,
    private val baseCooldownMs: Long = 5_000L,
    private val maxCooldownMs: Long = 30_000L,
) {
    private val states = ConcurrentHashMap<String, SourceHealth>()

    fun allowRequest(sourceId: String): Boolean {
        val current = states[sourceId] ?: return true
        if (current.consecutiveFailures < failureThreshold) return true
        return clock() >= current.circuitOpenUntilMillis
    }

    fun currentHealth(sourceId: String): SourceHealth? = states[sourceId]

    fun snapshotAll(): Map<String, SourceHealth> = states.toMap()

    fun recordResult(result: CandidateSourceResult): SourceHealth {
        val prev = states[result.sourceId]
        val now = clock()
        val updated = when (result.status) {
            CandidateSourceStatus.SUCCESS,
            CandidateSourceStatus.EMPTY -> SourceHealth(
                sourceId = result.sourceId,
                status = result.status,
                latencyMillis = result.latencyMillis,
                returnedCount = result.candidates.size,
                consecutiveFailures = 0,
                circuitOpenUntilMillis = 0L,
                errorCode = null,
            )
            CandidateSourceStatus.CIRCUIT_OPEN -> (prev ?: SourceHealth(
                sourceId = result.sourceId,
                status = CandidateSourceStatus.CIRCUIT_OPEN,
            )).copy(
                status = CandidateSourceStatus.CIRCUIT_OPEN,
                latencyMillis = 0L,
                returnedCount = 0,
                errorCode = result.errorCode ?: prev?.errorCode ?: "circuit_open",
            )
            CandidateSourceStatus.TIMEOUT,
            CandidateSourceStatus.RATE_LIMITED,
            CandidateSourceStatus.UNAVAILABLE,
            CandidateSourceStatus.INVALID -> {
                val failures = (prev?.consecutiveFailures ?: 0) + 1
                val openUntil = if (failures >= failureThreshold) {
                    val shift = (failures - failureThreshold).coerceAtMost(5)
                    now + (baseCooldownMs shl shift).coerceAtMost(maxCooldownMs)
                } else 0L
                SourceHealth(
                    sourceId = result.sourceId,
                    status = result.status,
                    latencyMillis = result.latencyMillis,
                    returnedCount = 0,
                    consecutiveFailures = failures,
                    circuitOpenUntilMillis = openUntil,
                    errorCode = result.errorCode ?: result.status.name,
                )
            }
        }
        states[result.sourceId] = updated
        return updated
    }

    fun reset(sourceId: String? = null) {
        if (sourceId != null) states.remove(sourceId) else states.clear()
    }
}
