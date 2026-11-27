package com.velvet.app.recommendation.engine

import com.velvet.app.diagnostics.StageFunnelCounts
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.SourceHealth
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Privacy-safe operational and quality diagnostics recorded per engine invocation.
 * Never logs raw user search queries or identifiable history text.
 */
data class RecommendationDiagnostics(
    val diagnosticsId: String,
    val requestId: String,
    val mode: RecommendationMode,
    val rawCandidateCount: Int,
    val eligibleCandidateCount: Int,
    val duplicateRemovedCount: Int,
    val rejectedReasonCounts: Map<String, Int>,
    val sourceHealth: Map<String, SourceHealth>,
    val averageTransitionScore: Double,
    val constraintRelaxations: List<String>,
    val returnedCount: Int,
    val durationMs: Long,
    val usedCache: Boolean,
    val recordedAtMillis: Long,
    val totalFunnel: StageFunnelCounts? = null,
    val perGeneratorFunnels: Map<String, StageFunnelCounts> = emptyMap(),
)

class RecommendationDiagnosticsLog(
    private val maxEntries: Int = 40,
) {
    private val entries = ConcurrentLinkedDeque<RecommendationDiagnostics>()

    fun record(diagnostics: RecommendationDiagnostics) {
        entries.addFirst(diagnostics)
        while (entries.size > maxEntries) {
            entries.pollLast()
        }
    }

    fun latest(): RecommendationDiagnostics? = entries.peekFirst()

    fun recent(limit: Int = 10): List<RecommendationDiagnostics> = entries.take(limit)

    fun clear() {
        entries.clear()
    }
}
