package com.velvet.app.diagnostics

import com.velvet.app.data.Song
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.engine.RecommendationDiagnostics
import com.velvet.app.recommendation.engine.RefillStatus
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.model.SourceHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RefillDecisionRecord(
    val timestampMs: Long,
    val seedTitle: String,
    val mode: String,
    val outcome: String,
    val reason: String,
    val appendedCount: Int,
    val durationMs: Long,
    val queueAheadAfter: Int,
)

data class SessionVibeSummary(
    val anchorTitle: String = "(none)",
    val anchorArtist: String = "",
    val languages: Set<String> = emptySet(),
    val genres: Map<String, Double> = emptyMap(),
    val mood: String = "unknown",
)

data class QueueHealthSnapshot(
    val aheadCount: Int = 0,
    val userQueueCount: Int = 0,
    val runwayState: String = RefillStatus.IDLE.name,
    val lastRefillAtMs: Long? = null,
    val lastRefillDurationMs: Long? = null,
    val lastRefillResult: String = "Not started",
    val rawPoolSize: Int = 0,
    val eligiblePoolSize: Int = 0,
    val instantCatalogSize: Int = 0,
    val sessionVibe: SessionVibeSummary = SessionVibeSummary(),
    val rejectReasonCounts: Map<String, Int> = emptyMap(),
    val sourceHealth: Map<String, SourceHealth> = emptyMap(),
    val recentDecisions: List<RefillDecisionRecord> = emptyList(),
)

/**
 * Passive read-only observability aggregator backing Developer Options > Queue Health (C8).
 * Never alters playback decisions or blocks playback threads.
 */
object QueueHealthMonitor {
    const val MAX_DECISIONS = 20

    private val lock = Any()
    private val decisions = ArrayDeque<RefillDecisionRecord>(MAX_DECISIONS)
    private val cumulativeRejects = linkedMapOf<String, Int>()
    private val latestSourceHealth = linkedMapOf<String, SourceHealth>()

    private val _state = MutableStateFlow(QueueHealthSnapshot())
    val state: StateFlow<QueueHealthSnapshot> = _state.asStateFlow()

    fun recordQueueState(
        aheadCount: Int,
        userQueueCount: Int = 0,
        anchor: Song? = null,
        runwayState: String? = null,
        requestedLanguages: Set<String> = emptySet(),
        featureRepository: TrackFeatureRepository? = null,
        catalogSize: Int = InstantTrackCatalog.snapshot().size,
    ) {
        val vibe = if (anchor != null) {
            val feat = featureRepository?.getCachedOrExtract(anchor)
            val topMood = feat?.moodIds?.maxByOrNull { it.value }?.key ?: "neutral"
            val langs = if (requestedLanguages.isNotEmpty()) {
                requestedLanguages
            } else {
                feat?.languageIds?.keys.orEmpty()
            }
            SessionVibeSummary(
                anchorTitle = anchor.title,
                anchorArtist = anchor.artist,
                languages = langs,
                genres = feat?.genreIds.orEmpty(),
                mood = topMood,
            )
        } else {
            null
        }
        synchronized(lock) {
            val prev = _state.value
            _state.value = prev.copy(
                aheadCount = aheadCount,
                userQueueCount = userQueueCount,
                runwayState = runwayState ?: prev.runwayState,
                instantCatalogSize = catalogSize,
                sessionVibe = vibe ?: prev.sessionVibe,
            )
        }
    }

    fun recordEngineDiagnostics(
        diag: RecommendationDiagnostics,
        seed: Song? = null,
        featureRepository: TrackFeatureRepository? = null,
        requestedLanguages: Set<String> = emptySet(),
        catalogSize: Int = InstantTrackCatalog.snapshot().size,
    ) {
        val seedFeat = seed?.let { featureRepository?.getCachedOrExtract(it) }
        synchronized(lock) {
            diag.rejectedReasonCounts.forEach { (reason, count) ->
                cumulativeRejects[reason] = (cumulativeRejects[reason] ?: 0) + count
            }
            if (diag.sourceHealth.isNotEmpty()) {
                latestSourceHealth.putAll(diag.sourceHealth)
            }
            val prev = _state.value
            val updatedVibe = if (seed != null) {
                SessionVibeSummary(
                    anchorTitle = seed.title,
                    anchorArtist = seed.artist,
                    languages = requestedLanguages.ifEmpty { seedFeat?.languageIds?.keys.orEmpty() },
                    genres = seedFeat?.genreIds.orEmpty(),
                    mood = seedFeat?.moodIds?.maxByOrNull { it.value }?.key ?: prev.sessionVibe.mood,
                )
            } else {
                prev.sessionVibe
            }
            _state.value = prev.copy(
                rawPoolSize = diag.rawCandidateCount,
                eligiblePoolSize = diag.eligibleCandidateCount,
                instantCatalogSize = catalogSize,
                sessionVibe = updatedVibe,
                rejectReasonCounts = cumulativeRejects.toMap(),
                sourceHealth = latestSourceHealth.toMap(),
            )
        }
    }

    fun recordRefillDecision(
        seedTitle: String,
        mode: String,
        outcome: String,
        reason: String,
        appendedCount: Int,
        durationMs: Long,
        queueAheadAfter: Int,
        status: RefillStatus,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val rec = RefillDecisionRecord(
            timestampMs = timestampMs,
            seedTitle = seedTitle,
            mode = mode,
            outcome = outcome,
            reason = reason,
            appendedCount = appendedCount,
            durationMs = durationMs,
            queueAheadAfter = queueAheadAfter,
        )
        synchronized(lock) {
            if (decisions.size >= MAX_DECISIONS) {
                decisions.removeFirst()
            }
            decisions.addLast(rec)
            val prev = _state.value
            _state.value = prev.copy(
                aheadCount = queueAheadAfter,
                runwayState = status.name,
                lastRefillAtMs = timestampMs,
                lastRefillDurationMs = durationMs,
                lastRefillResult = "$outcome($reason, +$appendedCount)",
                recentDecisions = decisions.toList().reversed(),
            )
        }
    }

    fun summarizeRejectBuckets(counts: Map<String, Int>): Map<String, Int> {
        var language = 0
        var genre = 0
        var duplicate = 0
        var notStudio = 0
        var other = 0
        counts.forEach { (key, count) ->
            val k = key.lowercase()
            when {
                "lang" in k -> language += count
                "genre" in k || "devotional" in k || "spoken" in k || "podcast" in k -> genre += count
                "dup" in k || "excluded" in k || "fatigued" in k -> duplicate += count
                "omv" in k || "lyric" in k || "live" in k || "unverified" in k ||
                    "alternate" in k || "visualizer" in k || "rejected_" in k -> notStudio += count
                else -> other += count
            }
        }
        return linkedMapOf(
            "language" to language,
            "genre" to genre,
            "duplicate" to duplicate,
            "not_studio" to notStudio,
            "other" to other,
        )
    }

    fun formatPlainText(snapshot: QueueHealthSnapshot = _state.value): String {
        val tf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val lastAt = snapshot.lastRefillAtMs?.let { tf.format(Date(it)) } ?: "Never"
        val buckets = summarizeRejectBuckets(snapshot.rejectReasonCounts)
        return buildString {
            appendLine("=== VELVET QUEUE HEALTH ===")
            appendLine("Ahead Count: ${snapshot.aheadCount} (userQueue=${snapshot.userQueueCount})")
            appendLine("Runway State: ${snapshot.runwayState}")
            appendLine("Last Refill Time: $lastAt")
            appendLine("Last Refill Duration: ${snapshot.lastRefillDurationMs?.let { "${it}ms" } ?: "N/A"}")
            appendLine("Last Refill Result: ${snapshot.lastRefillResult}")
            appendLine("Pool Size: raw=${snapshot.rawPoolSize}, eligible=${snapshot.eligiblePoolSize}, catalog=${snapshot.instantCatalogSize}")
            appendLine()
            appendLine("--- SESSION VIBE ---")
            appendLine("Anchor: ${snapshot.sessionVibe.anchorTitle}${if (snapshot.sessionVibe.anchorArtist.isNotBlank()) " — ${snapshot.sessionVibe.anchorArtist}" else ""}")
            appendLine("Languages: ${snapshot.sessionVibe.languages.ifEmpty { setOf("unknown") }.joinToString(", ")}")
            val genresText = if (snapshot.sessionVibe.genres.isEmpty()) {
                "(none)"
            } else {
                snapshot.sessionVibe.genres.entries.sortedByDescending { it.value }.joinToString(", ") {
                    "${it.key}=${"%.2f".format(Locale.US, it.value)}"
                }
            }
            appendLine("Genres: $genresText")
            appendLine("Mood: ${snapshot.sessionVibe.mood}")
            appendLine()
            appendLine("--- REJECT REASONS ---")
            appendLine("Summary: ${buckets.entries.joinToString(" | ") { "${it.key}=${it.value}" }}")
            if (snapshot.rejectReasonCounts.isEmpty()) {
                appendLine("Detail: (none)")
            } else {
                snapshot.rejectReasonCounts.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    appendLine("  $k: $v")
                }
            }
            appendLine()
            appendLine("--- SOURCE HEALTH (G0..G13 / INNERTUBE) ---")
            if (snapshot.sourceHealth.isEmpty()) {
                appendLine("(no source fetches recorded yet)")
            } else {
                snapshot.sourceHealth.toSortedMap().forEach { (src, health) ->
                    appendLine("  $src: ${health.status.name} count=${health.returnedCount} ${health.latencyMillis}ms fail=${health.consecutiveFailures}${health.errorCode?.let { " err=$it" } ?: ""}")
                }
            }
            appendLine()
            appendLine("--- LAST ${snapshot.recentDecisions.size} REFILL DECISIONS ---")
            if (snapshot.recentDecisions.isEmpty()) {
                appendLine("(none recorded yet)")
            } else {
                snapshot.recentDecisions.forEachIndexed { idx, d ->
                    appendLine("${idx + 1}. [${tf.format(Date(d.timestampMs))}] ${d.mode} | ${d.outcome} (${d.reason}) | +${d.appendedCount} | ${d.durationMs}ms | ahead=${d.queueAheadAfter} | seed=${d.seedTitle}")
                }
            }
        }
    }

    fun clearForTest() {
        synchronized(lock) {
            decisions.clear()
            cumulativeRejects.clear()
            latestSourceHealth.clear()
            _state.value = QueueHealthSnapshot()
        }
    }
}
