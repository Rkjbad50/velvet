package com.velvet.app.recommendation.model

import com.velvet.app.data.Song

enum class CandidateSourceStatus {
    SUCCESS,
    EMPTY,
    TIMEOUT,
    RATE_LIMITED,
    UNAVAILABLE,
    CIRCUIT_OPEN,
    INVALID,
}

data class SourceHealth(
    val sourceId: String,
    val status: CandidateSourceStatus,
    val latencyMillis: Long = 0L,
    val returnedCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val circuitOpenUntilMillis: Long = 0L,
    val errorCode: String? = null,
)

data class CandidateSourceEvidence(
    val sourceId: String,
    val rank: Int? = null,
    val retrievalScore: Double? = null,
    val fetchedAtMillis: Long = 0L,
    val evidenceCode: String,
    val confidence: Double = 0.5,
)

data class CandidateTrack(
    val song: Song,
    val identity: TrackIdentity,
    val features: TrackFeatureRecord?,
    val sourceEvidence: List<CandidateSourceEvidence>,
    val candidateRetrievedAtMillis: Long,
) {
    val sourceIds: Set<String>
        get() = sourceEvidence.mapTo(mutableSetOf()) { it.sourceId }
}

data class EligibilityDecision(
    val eligible: Boolean,
    val reasons: List<String>,
    val confidence: Double,
)

data class RecommendationReason(
    val code: String,
    val humanReadable: String,
    val contribution: Double? = null,
)

data class RankScoreBreakdown(
    val musicTypeFit: Double = 0.0,
    val genreFit: Double = 0.0,
    val moodFit: Double = 0.0,
    val audioFit: Double = 0.0,
    val tempoEnergyFit: Double = 0.0,
    val languageFit: Double = 0.0,
    val eraFit: Double = 0.0,
    val contextFit: Double = 0.0,
    val personalTasteFit: Double = 0.0,
    val searchIntentFit: Double = 0.0,
    val artistAffinityFit: Double = 0.0,
    val qualityFit: Double = 0.0,
    val noveltyFit: Double = 0.0,
    val penalties: Map<String, Double> = emptyMap(),
    val confidenceMultiplier: Double = 1.0,
    val total: Double = 0.0,
)

data class RankedCandidate(
    val candidate: CandidateTrack,
    val rankScore: Double,
    val breakdown: RankScoreBreakdown,
    val reasons: List<RecommendationReason> = emptyList(),
)

data class RecommendedTrack(
    val song: Song,
    val recordingId: String,
    val workId: String,
    val sourceIds: Set<String>,
    val rankScore: Double,
    val sequenceScore: Double,
    val finalScore: Double,
    val featureConfidence: Double,
    val releaseAudioClass: ReleaseAudioClass,
    val reasons: List<RecommendationReason>,
    val scoreBreakdown: Map<String, Double>,
    val selectedPosition: Int,
)

data class RecommendationResult(
    val requestId: String,
    val mode: RecommendationMode,
    val candidates: List<RecommendedTrack>,
    val modelVersion: String,
    val generatedAtMillis: Long,
    val sourceHealth: Map<String, SourceHealth>,
    val diagnosticsId: String,
    val overflowSongs: List<Song> = emptyList(),
)
