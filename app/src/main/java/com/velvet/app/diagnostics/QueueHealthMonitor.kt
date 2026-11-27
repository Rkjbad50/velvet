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

data class StageFunnelCounts(
    val sourceId: String,
    val fetched: Int = 0,
    val afterEligibility: Int = 0,
    val afterIdentityDedupe: Int = 0,
    val afterDismissedFilter: Int = 0,
    val afterLanguageGate: Int = 0,
    val afterGenreTypeGate: Int = 0,
    val afterArtistPurity: Int = 0,
    val accepted: Int = 0,
    val appended: Int = 0,
) {
    fun formatLine(): String =
        "$sourceId: fetched=$fetched elig=$afterEligibility dedupe=$afterIdentityDedupe " +
            "dismiss=$afterDismissedFilter lang=$afterLanguageGate genre=$afterGenreTypeGate " +
            "purity=$afterArtistPurity acc=$accepted app=$appended"
}

enum class StreamPrefetchStatus {
    PENDING,
    PREFETCHING,
    CACHED,
    DOWNLOADED,
    FAILED,
}

data class StreamPrefetchEntry(
    val videoId: String,
    val slotIndex: Int,
    val status: StreamPrefetchStatus,
    val quality: String? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
)

enum class StallKind {
    REFILL_STALL,
    STREAM_RESOLVE_STALL,
}

data class StallEventRecord(
    val timestampMs: Long,
    val kind: StallKind,
    val targetId: String,
    val targetTitle: String,
    val durationMs: Long,
    val detail: String,
)

data class RefillFunnelRecord(
    val timestampMs: Long,
    val requestId: String,
    val mode: String,
    val anchorId: String,
    val anchorTitle: String,
    val wasSteppedAnchor: Boolean = false,
    val steppedFromAnchorId: String? = null,
    val decision: String,
    val decisionReason: String,
    val latencyMs: Long,
    val queueAheadBefore: Int,
    val queueAheadAfter: Int,
    val continuationPagesFetched: Int = 0,
    val continuationHasNext: Boolean = false,
    val totalFunnel: StageFunnelCounts = StageFunnelCounts("TOTAL"),
    val perGeneratorFunnels: Map<String, StageFunnelCounts> = emptyMap(),
    val rejectReasonCounts: Map<String, Int> = emptyMap(),
    val acceptedSourceIds: Set<String> = emptySet(),
    val acceptedFromLibraryOrInstantCatalog: Boolean = false,
    val libraryOrInstantAcceptedCount: Int = 0,
    val widenedRetryTriggered: Boolean = false,
)

data class QueueHealthSnapshot(
    val aheadCount: Int = 0,
    val userQueueCount: Int = 0,
    val runwayState: String = RefillStatus.IDLE.name,
    val lastRefillAtMs: Long? = null,
    val lastRefillDurationMs: Long? = null,
    val refillLatencyP50Ms: Long = 0L,
    val refillLatencyP95Ms: Long = 0L,
    val lastRefillResult: String = "Not started",
    val rawPoolSize: Int = 0,
    val eligiblePoolSize: Int = 0,
    val instantCatalogSize: Int = 0,
    val sessionVibe: SessionVibeSummary = SessionVibeSummary(),
    val rejectReasonCounts: Map<String, Int> = emptyMap(),
    val sourceHealth: Map<String, SourceHealth> = emptyMap(),
    val recentDecisions: List<RefillDecisionRecord> = emptyList(),
    val recentFunnels: List<RefillFunnelRecord> = emptyList(),
    val streamPrefetchEntries: List<StreamPrefetchEntry> = emptyList(),
    val streamPrefetchHitCount: Int = 0,
    val streamPrefetchMissCount: Int = 0,
    val lastStreamResolveDurationMs: Long? = null,
    val refillStallCount: Int = 0,
    val lastRefillStallMs: Long? = null,
    val totalRefillStallMs: Long = 0L,
    val streamResolveStallCount: Int = 0,
    val lastStreamResolveStallMs: Long? = null,
    val totalStreamResolveStallMs: Long = 0L,
    val recentStalls: List<StallEventRecord> = emptyList(),
    val lowRunwaySinceMs: Long? = null,
)

/**
 * Passive read-only observability aggregator backing Developer Options > Queue Health (C8 + F0 + F4).
 * Never alters playback decisions or blocks playback threads.
 */
object QueueHealthMonitor {
    const val MAX_DECISIONS = 20
    const val MAX_FUNNELS = 30
    const val MAX_STALLS = 25
    const val STREAM_RESOLVE_STALL_THRESHOLD_MS = 250L
    const val LOW_RUNWAY_STALL_THRESHOLD_MS = 2_000L

    private val lock = Any()
    private val decisions = ArrayDeque<RefillDecisionRecord>(MAX_DECISIONS)
    private val funnels = ArrayDeque<RefillFunnelRecord>(MAX_FUNNELS)
    private val stalls = ArrayDeque<StallEventRecord>(MAX_STALLS)
    private val cumulativeRejects = linkedMapOf<String, Int>()
    private val latestSourceHealth = linkedMapOf<String, SourceHealth>()
    private var lowRunwayStallRecordedForEpisode = false

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
        isPlaying: Boolean? = null,
        nowMs: Long = System.currentTimeMillis(),
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
        var detectedLowRunwayStallWaitMs: Long? = null
        synchronized(lock) {
            val prev = _state.value
            val playingActive = isPlaying ?: false
            var nextLowRunwaySince = prev.lowRunwaySinceMs
            if (isPlaying != null) {
                if (playingActive && aheadCount < com.velvet.app.recommendation.engine.RunwayPolicy.URGENT_BELOW) {
                    if (nextLowRunwaySince == null) {
                        nextLowRunwaySince = nowMs
                        lowRunwayStallRecordedForEpisode = false
                    } else if (!lowRunwayStallRecordedForEpisode &&
                        (nowMs - nextLowRunwaySince) > LOW_RUNWAY_STALL_THRESHOLD_MS
                    ) {
                        lowRunwayStallRecordedForEpisode = true
                        detectedLowRunwayStallWaitMs = nowMs - nextLowRunwaySince
                    }
                } else {
                    if (nextLowRunwaySince != null &&
                        !lowRunwayStallRecordedForEpisode &&
                        playingActive &&
                        (nowMs - nextLowRunwaySince) > LOW_RUNWAY_STALL_THRESHOLD_MS
                    ) {
                        detectedLowRunwayStallWaitMs = nowMs - nextLowRunwaySince
                    }
                    nextLowRunwaySince = null
                    lowRunwayStallRecordedForEpisode = false
                }
            }
            _state.value = prev.copy(
                aheadCount = aheadCount,
                userQueueCount = userQueueCount,
                runwayState = runwayState ?: prev.runwayState,
                instantCatalogSize = catalogSize,
                sessionVibe = vibe ?: prev.sessionVibe,
                lowRunwaySinceMs = nextLowRunwaySince,
            )
        }
        detectedLowRunwayStallWaitMs?.let { elapsed ->
            recordRefillStall(
                anchorId = anchor?.id ?: "(unknown)",
                anchorTitle = anchor?.title ?: "(unknown)",
                waitMs = elapsed,
                detail = "ahead=$aheadCount remained < ${com.velvet.app.recommendation.engine.RunwayPolicy.URGENT_BELOW} for >${LOW_RUNWAY_STALL_THRESHOLD_MS}ms",
                timestampMs = nowMs,
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
                "genre" in k || "type" in k || "devotional" in k || "spoken" in k || "podcast" in k -> genre += count
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

    fun recordLatencyPercentiles(p50Ms: Long, p95Ms: Long) {
        synchronized(lock) {
            _state.value = _state.value.copy(
                refillLatencyP50Ms = p50Ms,
                refillLatencyP95Ms = p95Ms,
            )
        }
    }

    fun recordRollingPoolSize(poolSize: Int) {
        synchronized(lock) {
            val prev = _state.value
            _state.value = prev.copy(
                rawPoolSize = maxOf(prev.rawPoolSize, poolSize),
                eligiblePoolSize = maxOf(prev.eligiblePoolSize, poolSize),
            )
        }
    }

    fun recordStreamPrefetch(entries: List<StreamPrefetchEntry>) {
        synchronized(lock) {
            _state.value = _state.value.copy(streamPrefetchEntries = entries)
        }
    }

    fun recordRefillStall(
        anchorId: String,
        anchorTitle: String,
        waitMs: Long,
        detail: String = "",
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val safeWait = waitMs.coerceAtLeast(0L)
        val record = StallEventRecord(
            timestampMs = timestampMs,
            kind = StallKind.REFILL_STALL,
            targetId = anchorId,
            targetTitle = anchorTitle,
            durationMs = safeWait,
            detail = detail,
        )
        synchronized(lock) {
            if (stalls.size >= MAX_STALLS) stalls.removeFirst()
            stalls.addLast(record)
            val prev = _state.value
            _state.value = prev.copy(
                refillStallCount = prev.refillStallCount + 1,
                lastRefillStallMs = safeWait,
                totalRefillStallMs = prev.totalRefillStallMs + safeWait,
                recentStalls = stalls.toList().reversed(),
            )
        }
        DiagnosticsLogger.w("RefillStall", "anchor=$anchorId ($anchorTitle) waitMs=$safeWait $detail".trim())
    }

    fun recordStreamResolve(
        videoId: String,
        cacheHit: Boolean,
        durationMs: Long,
        succeeded: Boolean,
        errorDetail: String? = null,
        songTitle: String = videoId,
        stallThresholdMs: Long = STREAM_RESOLVE_STALL_THRESHOLD_MS,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val safeMs = durationMs.coerceAtLeast(0L)
        val isStall = !cacheHit && (!succeeded || safeMs >= stallThresholdMs)
        val stallRecord = if (isStall) {
            StallEventRecord(
                timestampMs = timestampMs,
                kind = StallKind.STREAM_RESOLVE_STALL,
                targetId = videoId,
                targetTitle = songTitle,
                durationMs = safeMs,
                detail = buildString {
                    append(if (succeeded) "cold_resolve_slow" else "resolve_failed")
                    if (!errorDetail.isNullOrBlank()) append(": $errorDetail")
                },
            )
        } else {
            null
        }
        synchronized(lock) {
            val prev = _state.value
            if (stallRecord != null) {
                if (stalls.size >= MAX_STALLS) stalls.removeFirst()
                stalls.addLast(stallRecord)
            }
            _state.value = prev.copy(
                streamPrefetchHitCount = prev.streamPrefetchHitCount + if (cacheHit) 1 else 0,
                streamPrefetchMissCount = prev.streamPrefetchMissCount + if (cacheHit) 0 else 1,
                lastStreamResolveDurationMs = safeMs,
                streamResolveStallCount = prev.streamResolveStallCount + if (isStall) 1 else 0,
                lastStreamResolveStallMs = if (isStall) safeMs else prev.lastStreamResolveStallMs,
                totalStreamResolveStallMs = prev.totalStreamResolveStallMs + if (isStall) safeMs else 0L,
                recentStalls = if (stallRecord != null) stalls.toList().reversed() else prev.recentStalls,
            )
        }
        if (isStall) {
            DiagnosticsLogger.w(
                "StreamResolveStall",
                "videoId=$videoId durationMs=$safeMs succeeded=$succeeded ${errorDetail.orEmpty()}".trim(),
            )
        }
    }

    fun formatPlainText(snapshot: QueueHealthSnapshot = _state.value): String {
        val tf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val lastAt = snapshot.lastRefillAtMs?.let { tf.format(Date(it)) } ?: "Never"
        val buckets = summarizeRejectBuckets(snapshot.rejectReasonCounts)
        val readyPrefetch = snapshot.streamPrefetchEntries.count {
            it.status == StreamPrefetchStatus.CACHED || it.status == StreamPrefetchStatus.DOWNLOADED
        }
        return buildString {
            appendLine("=== VELVET QUEUE HEALTH ===")
            appendLine("Ahead Count: ${snapshot.aheadCount} (userQueue=${snapshot.userQueueCount})")
            appendLine("Runway State: ${snapshot.runwayState}")
            appendLine("Last Refill Time: $lastAt")
            appendLine("Last Refill Duration: ${snapshot.lastRefillDurationMs?.let { "${it}ms" } ?: "N/A"} (p50=${snapshot.refillLatencyP50Ms}ms, p95=${snapshot.refillLatencyP95Ms}ms)")
            appendLine("Last Refill Result: ${snapshot.lastRefillResult}")
            appendLine("Pool Size: raw=${snapshot.rawPoolSize}, eligible=${snapshot.eligiblePoolSize}, catalog=${snapshot.instantCatalogSize}")
            appendLine()
            appendLine("--- STREAM PREFETCH (N+1..N+3) ---")
            appendLine(
                "Ready: $readyPrefetch/${snapshot.streamPrefetchEntries.size} | " +
                    "PrefetchHits=${snapshot.streamPrefetchHitCount} | Misses=${snapshot.streamPrefetchMissCount} | " +
                    "LastResolve=${snapshot.lastStreamResolveDurationMs?.let { "${it}ms" } ?: "N/A"}"
            )
            if (snapshot.streamPrefetchEntries.isEmpty()) {
                appendLine("(no upcoming tracks prefetched yet)")
            } else {
                snapshot.streamPrefetchEntries.forEach { p ->
                    val detail = listOfNotNull(
                        p.quality,
                        p.latencyMs?.let { "${it}ms" },
                        p.error,
                    ).joinToString(" ")
                    appendLine("  N+${p.slotIndex}: ${p.videoId} [${p.status.name}]${if (detail.isNotBlank()) " ($detail)" else ""}")
                }
            }
            appendLine()
            appendLine("--- STALLS (REFILL vs STREAM-RESOLVE) ---")
            appendLine(
                "Refill Stalls: count=${snapshot.refillStallCount}, " +
                    "last=${snapshot.lastRefillStallMs?.let { "${it}ms" } ?: "N/A"}, " +
                    "total=${snapshot.totalRefillStallMs}ms"
            )
            appendLine(
                "Stream-Resolve Stalls: count=${snapshot.streamResolveStallCount}, " +
                    "last=${snapshot.lastStreamResolveStallMs?.let { "${it}ms" } ?: "N/A"}, " +
                    "total=${snapshot.totalStreamResolveStallMs}ms"
            )
            if (snapshot.recentStalls.isNotEmpty()) {
                snapshot.recentStalls.take(10).forEachIndexed { idx, s ->
                    appendLine("  ${idx + 1}. [${tf.format(Date(s.timestampMs))}] ${s.kind.name} ${s.durationMs}ms target=${s.targetId} (${s.targetTitle}) ${s.detail}".trimEnd())
                }
            }
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
            appendLine()
            appendLine("--- LAST ${snapshot.recentFunnels.size} REFILL FUNNELS ---")
            if (snapshot.recentFunnels.isEmpty()) {
                appendLine("(none recorded yet)")
            } else {
                append(formatFunnelsBodyLocked(snapshot.recentFunnels, tf))
            }
        }
    }

    fun recordRefillFunnel(record: RefillFunnelRecord) {
        synchronized(lock) {
            if (funnels.size >= MAX_FUNNELS) {
                funnels.removeFirst()
            }
            funnels.addLast(record)
            _state.value = _state.value.copy(
                recentFunnels = funnels.toList().reversed(),
            )
        }
        val summary = buildString {
            append("${record.mode} ${record.decision}(${record.decisionReason}) ")
            append("anchor=${record.anchorId}")
            if (record.wasSteppedAnchor) append("(steppedFrom=${record.steppedFromAnchorId})")
            append(" ahead=${record.queueAheadBefore}->${record.queueAheadAfter}")
            append(" contPages=${record.continuationPagesFetched} hasNext=${record.continuationHasNext}")
            append(" ${record.latencyMs}ms libOnly=${record.acceptedFromLibraryOrInstantCatalog}(${record.libraryOrInstantAcceptedCount})")
            append(" | ${record.totalFunnel.formatLine()}")
            if (record.acceptedSourceIds.isNotEmpty()) {
                append(" | srcs=${record.acceptedSourceIds.sorted().joinToString(",")}")
            }
        }
        DiagnosticsLogger.i("RefillFunnel", summary)
    }

    fun updateFunnelAppended(
        requestId: String,
        actualAppended: Int,
        queueAheadAfter: Int,
    ) {
        if (requestId.isBlank()) return
        synchronized(lock) {
            val idx = funnels.indexOfLast { it.requestId == requestId }
            if (idx < 0) return
            val list = funnels.toMutableList()
            val cur = list[idx]
            val updated = cur.copy(
                queueAheadAfter = queueAheadAfter,
                totalFunnel = cur.totalFunnel.copy(appended = actualAppended),
            )
            list[idx] = updated
            funnels.clear()
            funnels.addAll(list)
            _state.value = _state.value.copy(
                aheadCount = queueAheadAfter,
                recentFunnels = funnels.toList().reversed(),
            )
        }
    }

    fun recentFunnels(limit: Int = MAX_FUNNELS): List<RefillFunnelRecord> = synchronized(lock) {
        funnels.toList().reversed().take(limit.coerceAtLeast(0))
    }

    fun formatLastFunnelsPlainText(limit: Int = MAX_FUNNELS): String {
        val list = recentFunnels(limit)
        val tf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val raw = buildString {
            appendLine("=== VELVET LAST ${list.size} REFILL FUNNELS (MAX $MAX_FUNNELS) ===")
            if (list.isEmpty()) {
                appendLine("(no refill funnels recorded yet)")
            } else {
                append(formatFunnelsBodyLocked(list, tf))
            }
        }
        return DiagnosticsLogger.redact(raw)
    }

    private fun formatFunnelsBodyLocked(
        list: List<RefillFunnelRecord>,
        tf: SimpleDateFormat,
    ): String = buildString {
        list.forEachIndexed { idx, f ->
            val steppedText = if (f.wasSteppedAnchor) "YES (from=${f.steppedFromAnchorId ?: "unknown"})" else "NO"
            val libCount = f.libraryOrInstantAcceptedCount
            val totalAcc = f.totalFunnel.accepted
            val libPct = if (totalAcc > 0) (libCount * 100) / totalAcc else 0
            appendLine("${idx + 1}. [${tf.format(Date(f.timestampMs))}] mode=${f.mode} decision=${f.decision}(${f.decisionReason}) latency=${f.latencyMs}ms ahead=${f.queueAheadBefore}->${f.queueAheadAfter}")
            appendLine("   anchor=${f.anchorId} stepped=$steppedText widenedRetry=${if (f.widenedRetryTriggered) "YES" else "NO"} contPages=${f.continuationPagesFetched} hasNext=${f.continuationHasNext} | title=${f.anchorTitle}")
            appendLine("   acceptedSources=${f.acceptedSourceIds.sorted().joinToString(",").ifBlank { "(none)" }} | libOrInstant=${f.acceptedFromLibraryOrInstantCatalog} ($libCount/$totalAcc = ${libPct}%)")
            appendLine("   ${f.totalFunnel.formatLine()}")
            if (f.perGeneratorFunnels.isNotEmpty()) {
                f.perGeneratorFunnels.toSortedMap().values.forEach { g ->
                    appendLine("     - ${g.formatLine()}")
                }
            }
            if (f.rejectReasonCounts.isNotEmpty()) {
                val rejects = f.rejectReasonCounts.entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}=${it.value}" }
                appendLine("   rejects: $rejects")
            }
        }
    }

    fun clearForTest() {
        synchronized(lock) {
            decisions.clear()
            funnels.clear()
            stalls.clear()
            cumulativeRejects.clear()
            latestSourceHealth.clear()
            lowRunwayStallRecordedForEpisode = false
            _state.value = QueueHealthSnapshot()
        }
    }
}
