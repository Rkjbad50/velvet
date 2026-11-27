package com.velvet.app.recommendation.engine

import com.velvet.app.data.Song
import com.velvet.app.playback.RepeatMode
import com.velvet.app.playback.SongIdentity
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class RefillStatus {
    IDLE,
    IN_FLIGHT,
    COOLDOWN,
    EXHAUSTED,
    PAUSED,
}

data class RefillPolicy(
    val targetUpcomingBuffer: Int = 8,
    val lowWatermarkTrigger: Int = 4,
    val refillBatchSize: Int = 10,
    val minCooldownMs: Long = 1_500L,
    val failureBackoffMs: Long = 8_000L,
    val maxEmptyRefillsPerAnchor: Int = 2,
)

data class RefillState(
    val status: RefillStatus = RefillStatus.IDLE,
    val lastAttemptedAtMillis: Long = 0L,
    val lastSuccessAtMillis: Long = 0L,
    val consecutiveFailures: Int = 0,
    val emptyStreakForCurrentAnchor: Int = 0,
    val lastAnchorId: String? = null,
    val backoffUntilMillis: Long = 0L,
    val activeGeneration: Long = 0L,
)

data class QueueRefillSnapshot(
    val currentSong: Song?,
    val upcomingSongs: List<Song>,
    val userQueueSongs: List<Song> = emptyList(),
    val historySongs: List<Song> = emptyList(),
    val queueVersion: Long,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isAutoplayEnabled: Boolean = true,
    val isRadioActive: Boolean = false,
    val isArtistRadioActive: Boolean = false,
    val radioSeedSong: Song? = null,
    val radioSessionId: String? = null,
    val preferredLanguageIds: Set<String> = emptySet(),
    val excludedProviderIds: Set<String> = emptySet(),
    val seedSongsOverride: List<Song> = emptyList(),
    val allowMixedLanguages: Boolean = false,
    val artistLanguageMap: Map<String, com.velvet.app.recommendation.vibe.ArtistLanguageProfile> = emptyMap(),
    val fallbackAnchorPool: List<Song> = emptyList(),
    val randomSeed: Long? = null,
    val sessionSkippedArtistCounts: Map<String, Int> = emptyMap(),
    val sessionSkippedGenreCounts: Map<String, Int> = emptyMap(),
)

sealed class RefillDecision {
    data class Skip(val reason: String) : RefillDecision()
    data class AppendSongs(
        val requestId: String,
        val mode: RecommendationMode,
        val songs: List<Song>,
        val result: RecommendationResult,
        val replaceUpcomingIfEmptyAndPlayNext: Boolean,
    ) : RefillDecision()
    data class SafeEmpty(
        val requestId: String,
        val reason: String,
        val status: RefillStatus,
    ) : RefillDecision()
}

/**
 * Coordinates safe asynchronous queue refill with explicit state machine transitions,
 * cooldown/exhaustion protection, and optimistic-concurrency queueVersion verification.
 *
 * Guarantees:
 * - QueueManager remains the single logical source of truth (never mutated inside background workers).
 * - RepeatMode.ONE stops automatic refill unless explicitly forced.
 * - Strict Original Studio Audio Only failures or empty verified pools transition to EXHAUSTED
 *   instead of relaxing eligibility or looping endlessly.
 */
class AutoplayRefillCoordinator(
    private val engine: RecommendationEngine,
    private val policy: RefillPolicy = RefillPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val watchdogTimeoutMs: Long = RunwayPolicy.WATCHDOG_TIMEOUT_MS,
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val rollingPool: com.velvet.app.recommendation.candidates.RollingCandidatePool =
        com.velvet.app.recommendation.candidates.RollingCandidatePool(),
    private val urgentFallbackProvider: (suspend (Song, QueueRefillSnapshot, Set<String>) -> List<Song>)? = null,
) {
    private val stateMutex = Mutex()
    private val warmUpMutex = Mutex()
    private var state = RefillState()

    fun mergeIntoRollingPool(
        songs: List<Song>,
        excludedIds: Set<String> = emptySet(),
        excludedRecIds: Set<String> = emptySet(),
        excludedCompositionKeys: Set<String> = emptySet(),
    ) {
        rollingPool.merge(
            songs = songs,
            excludedIds = excludedIds,
            excludedRecIds = excludedRecIds,
            excludedCompositionKeys = excludedCompositionKeys,
        )
        com.velvet.app.diagnostics.QueueHealthMonitor.recordRollingPoolSize(rollingPool.size())
    }

    fun rollingPoolSize(): Int = rollingPool.size()

    suspend fun currentState(): RefillState = stateMutex.withLock { state }

    suspend fun resetForNewSeed(seedId: String? = null) {
        stateMutex.withLock {
            state = state.copy(
                status = RefillStatus.IDLE,
                emptyStreakForCurrentAnchor = 0,
                consecutiveFailures = 0,
                backoffUntilMillis = 0L,
                lastAnchorId = seedId,
                activeGeneration = state.activeGeneration + 1L,
            )
        }
    }

    private class FunnelCapture(
        val anchorId: String,
        val anchorTitle: String,
    ) {
        var requestId: String = ""
        var wasSteppedAnchor: Boolean = false
        var steppedFromAnchorId: String? = null
        var activeAnchorId: String = anchorId
        var activeAnchorTitle: String = anchorTitle
        var engineDiag: RecommendationDiagnostics? = null
        var steppedDiag: RecommendationDiagnostics? = null
        var dismissedDropCount: Int = 0
        var rollingPoolFetched: Int = 0
        var rollingPoolAccepted: Int = 0
        var urgentFallbackFetched: Int = 0
        var urgentFallbackAccepted: Int = 0
        var acceptedSongs: List<Song> = emptyList()
        var acceptedSourceIds: Set<String> = emptySet()
        var libraryOrInstantAcceptedCount: Int = 0
    }

    suspend fun requestRefill(
        initialSnapshot: QueueRefillSnapshot,
        latestSnapshotProvider: () -> QueueRefillSnapshot,
        seedId: String? = null,
        autoPlayNext: Boolean = false,
        force: Boolean = false,
        dismissedFilter: (Song) -> Boolean = { false },
    ): RefillDecision {
        val startMs = clock()
        val resolvedAnchor = resolveAnchor(initialSnapshot, seedId)
        val anchorId = resolvedAnchor?.id ?: "(none)"
        val anchorTitle = resolvedAnchor?.title ?: "(none)"
        val capture = FunnelCapture(anchorId = anchorId, anchorTitle = anchorTitle)
        val decision = runCatching {
            requestRefillInternal(
                initialSnapshot = initialSnapshot,
                latestSnapshotProvider = latestSnapshotProvider,
                seedId = seedId,
                autoPlayNext = autoPlayNext,
                force = force,
                dismissedFilter = dismissedFilter,
                capture = capture,
            )
        }.getOrElse { throw it }
        val modeName = when {
            initialSnapshot.isArtistRadioActive -> RecommendationMode.ARTIST_RADIO.name
            initialSnapshot.isRadioActive -> RecommendationMode.SONG_RADIO.name
            else -> RecommendationMode.AUTOPLAY_REFILL.name
        }
        val latestSnap = runCatching { latestSnapshotProvider() }.getOrElse { initialSnapshot }
        val durationMs = (clock() - startMs).coerceAtLeast(0L)
        val snapStatus = stateMutex.withLock { state.status }
        val queueAheadBefore = initialSnapshot.upcomingSongs.size
        val queueAheadAfter = when (decision) {
            is RefillDecision.AppendSongs -> latestSnap.upcomingSongs.size + decision.songs.size
            else -> latestSnap.upcomingSongs.size
        }
        when (decision) {
            is RefillDecision.AppendSongs -> com.velvet.app.diagnostics.QueueHealthMonitor.recordRefillDecision(
                seedTitle = anchorTitle,
                mode = modeName,
                outcome = "APPEND",
                reason = "ok",
                appendedCount = decision.songs.size,
                durationMs = durationMs,
                queueAheadAfter = queueAheadAfter,
                status = snapStatus,
                timestampMs = clock(),
            )
            is RefillDecision.SafeEmpty -> com.velvet.app.diagnostics.QueueHealthMonitor.recordRefillDecision(
                seedTitle = anchorTitle,
                mode = modeName,
                outcome = "EMPTY",
                reason = decision.reason,
                appendedCount = 0,
                durationMs = durationMs,
                queueAheadAfter = queueAheadAfter,
                status = decision.status,
                timestampMs = clock(),
            )
            is RefillDecision.Skip -> com.velvet.app.diagnostics.QueueHealthMonitor.recordRefillDecision(
                seedTitle = anchorTitle,
                mode = modeName,
                outcome = "SKIP",
                reason = decision.reason,
                appendedCount = 0,
                durationMs = durationMs,
                queueAheadAfter = queueAheadAfter,
                status = snapStatus,
                timestampMs = clock(),
            )
        }
        recordFunnelToMonitor(
            capture = capture,
            decision = decision,
            modeName = modeName,
            durationMs = durationMs,
            queueAheadBefore = queueAheadBefore,
            queueAheadAfter = queueAheadAfter,
        )
        return decision
    }

    private fun recordFunnelToMonitor(
        capture: FunnelCapture,
        decision: RefillDecision,
        modeName: String,
        durationMs: Long,
        queueAheadBefore: Int,
        queueAheadAfter: Int,
    ) {
        val activeDiag = capture.steppedDiag ?: capture.engineDiag
        val perGen = linkedMapOf<String, com.velvet.app.diagnostics.StageFunnelCounts>()
        activeDiag?.perGeneratorFunnels?.let { perGen.putAll(it) }

        if (capture.rollingPoolFetched > 0 || capture.rollingPoolAccepted > 0) {
            perGen["rolling_pool"] = com.velvet.app.diagnostics.StageFunnelCounts(
                sourceId = "rolling_pool",
                fetched = capture.rollingPoolFetched,
                afterEligibility = capture.rollingPoolFetched,
                afterIdentityDedupe = capture.rollingPoolFetched,
                afterDismissedFilter = capture.rollingPoolAccepted,
                afterLanguageGate = capture.rollingPoolAccepted,
                afterGenreTypeGate = capture.rollingPoolAccepted,
                afterArtistPurity = capture.rollingPoolAccepted,
                accepted = capture.rollingPoolAccepted,
                appended = capture.rollingPoolAccepted,
            )
        }
        if (capture.wasSteppedAnchor && capture.steppedDiag != null) {
            val sf = capture.steppedDiag?.totalFunnel
            if (sf != null) {
                perGen["stepped_anchor"] = sf.copy(sourceId = "stepped_anchor")
            }
        }
        if (capture.urgentFallbackFetched > 0 || capture.urgentFallbackAccepted > 0) {
            perGen["urgent_fallback"] = com.velvet.app.diagnostics.StageFunnelCounts(
                sourceId = "urgent_fallback",
                fetched = capture.urgentFallbackFetched,
                afterEligibility = capture.urgentFallbackAccepted,
                afterIdentityDedupe = capture.urgentFallbackAccepted,
                afterDismissedFilter = capture.urgentFallbackAccepted,
                afterLanguageGate = capture.urgentFallbackAccepted,
                afterGenreTypeGate = capture.urgentFallbackAccepted,
                afterArtistPurity = capture.urgentFallbackAccepted,
                accepted = capture.urgentFallbackAccepted,
                appended = capture.urgentFallbackAccepted,
            )
        }

        val acceptedCount = when (decision) {
            is RefillDecision.AppendSongs -> decision.songs.size
            else -> 0
        }
        val baseTotal = activeDiag?.totalFunnel
        val dismissedAdjusted = if (baseTotal != null) {
            (baseTotal.afterDismissedFilter - capture.dismissedDropCount).coerceAtLeast(acceptedCount)
        } else 0
        val totalFunnel = if (baseTotal != null) {
            baseTotal.copy(
                fetched = baseTotal.fetched + capture.rollingPoolFetched + capture.urgentFallbackFetched,
                afterDismissedFilter = dismissedAdjusted,
                accepted = acceptedCount,
                appended = acceptedCount,
            )
        } else {
            com.velvet.app.diagnostics.StageFunnelCounts(
                sourceId = "TOTAL",
                fetched = capture.rollingPoolFetched + capture.urgentFallbackFetched,
                accepted = acceptedCount,
                appended = acceptedCount,
            )
        }

        val (decisionLabel, decisionReason) = when (decision) {
            is RefillDecision.AppendSongs -> "APPENDED" to "ok"
            is RefillDecision.Skip -> (if (decision.reason == "anchor_exhausted") "EXHAUSTED" else "SKIP") to decision.reason
            is RefillDecision.SafeEmpty -> (if (decision.status == RefillStatus.EXHAUSTED) "EXHAUSTED" else "COOLDOWN") to decision.reason
        }
        val requestId = when (decision) {
            is RefillDecision.AppendSongs -> decision.requestId
            is RefillDecision.SafeEmpty -> decision.requestId.ifBlank { capture.requestId }
            is RefillDecision.Skip -> capture.requestId
        }.ifBlank { "refill-${clock()}" }

        val nextStats = runCatching {
            com.velvet.app.data.StreamResolver.peekNextStats(capture.activeAnchorId)
                ?: com.velvet.app.data.YouTubeMusicInnertube.peekNextStats(capture.activeAnchorId)
        }.getOrNull()

        val rejects = linkedMapOf<String, Int>()
        capture.engineDiag?.rejectedReasonCounts?.forEach { (k, v) -> rejects[k] = (rejects[k] ?: 0) + v }
        capture.steppedDiag?.rejectedReasonCounts?.forEach { (k, v) -> rejects["stepped:$k"] = (rejects["stepped:$k"] ?: 0) + v }
        if (capture.dismissedDropCount > 0) {
            rejects["dismissed_in_session"] = capture.dismissedDropCount
        }

        com.velvet.app.diagnostics.QueueHealthMonitor.recordRefillFunnel(
            com.velvet.app.diagnostics.RefillFunnelRecord(
                timestampMs = clock(),
                requestId = requestId,
                mode = modeName,
                anchorId = capture.activeAnchorId,
                anchorTitle = capture.activeAnchorTitle,
                wasSteppedAnchor = capture.wasSteppedAnchor,
                steppedFromAnchorId = capture.steppedFromAnchorId,
                decision = decisionLabel,
                decisionReason = decisionReason,
                latencyMs = durationMs,
                queueAheadBefore = queueAheadBefore,
                queueAheadAfter = queueAheadAfter,
                continuationPagesFetched = nextStats?.pagesFetched ?: 0,
                continuationHasNext = nextStats?.hasNext ?: false,
                totalFunnel = totalFunnel,
                perGeneratorFunnels = perGen,
                rejectReasonCounts = rejects,
                acceptedSourceIds = capture.acceptedSourceIds,
                acceptedFromLibraryOrInstantCatalog = capture.libraryOrInstantAcceptedCount > 0,
                libraryOrInstantAcceptedCount = capture.libraryOrInstantAcceptedCount,
            ),
        )
    }

    private suspend fun requestRefillInternal(
        initialSnapshot: QueueRefillSnapshot,
        latestSnapshotProvider: () -> QueueRefillSnapshot,
        seedId: String?,
        autoPlayNext: Boolean,
        force: Boolean,
        dismissedFilter: (Song) -> Boolean,
        capture: FunnelCapture,
    ): RefillDecision {
        val anchor = resolveAnchor(initialSnapshot, seedId)
            ?: return RefillDecision.Skip("no_anchor_song")

        val mode = when {
            initialSnapshot.isArtistRadioActive -> RecommendationMode.ARTIST_RADIO
            initialSnapshot.isRadioActive -> RecommendationMode.SONG_RADIO
            else -> RecommendationMode.AUTOPLAY_REFILL
        }

        val generation = stateMutex.withLock {
            val now = clock()
            if (!initialSnapshot.isAutoplayEnabled && !initialSnapshot.isRadioActive) {
                state = state.copy(status = RefillStatus.PAUSED)
                return RefillDecision.Skip("autoplay_disabled")
            }
            if (initialSnapshot.repeatMode == RepeatMode.ONE && !force && !autoPlayNext) {
                state = state.copy(status = RefillStatus.PAUSED)
                return RefillDecision.Skip("repeat_one_active")
            }

            val upNextCount = initialSnapshot.upcomingSongs.size
            val isRunwayPolicy = policy.targetUpcomingBuffer >= RunwayPolicy.REFILL_BELOW
            val isUrgent = isRunwayPolicy && upNextCount < RunwayPolicy.URGENT_BELOW
            if (upNextCount >= policy.lowWatermarkTrigger && !force && !autoPlayNext) {
                return RefillDecision.Skip("buffer_healthy")
            }
            if (upNextCount >= policy.targetUpcomingBuffer && !force) {
                return RefillDecision.Skip("buffer_at_target")
            }

            val sameAnchor = state.lastAnchorId == anchor.id
            val effectiveEmptyStreak = if (sameAnchor) state.emptyStreakForCurrentAnchor else 0

            if (sameAnchor && effectiveEmptyStreak >= policy.maxEmptyRefillsPerAnchor && !force && !isUrgent) {
                state = state.copy(status = RefillStatus.EXHAUSTED, emptyStreakForCurrentAnchor = effectiveEmptyStreak)
                return RefillDecision.Skip("anchor_exhausted")
            }

            if (state.status == RefillStatus.IN_FLIGHT && !force) {
                return RefillDecision.Skip("already_in_flight")
            }
            if (!force && !autoPlayNext && !isUrgent && now < state.backoffUntilMillis) {
                state = state.copy(status = RefillStatus.COOLDOWN)
                return RefillDecision.Skip("cooldown_active")
            }
            if (!force && !autoPlayNext && !isUrgent && sameAnchor && (now - state.lastAttemptedAtMillis) in 0L until policy.minCooldownMs) {
                return RefillDecision.Skip("min_cooldown_active")
            }

            val nextGen = state.activeGeneration + 1L
            state = state.copy(
                status = RefillStatus.IN_FLIGHT,
                lastAttemptedAtMillis = now,
                lastAnchorId = anchor.id,
                emptyStreakForCurrentAnchor = effectiveEmptyStreak,
                activeGeneration = nextGen,
            )
            nextGen
        }

        return try {
            val upNextCount = initialSnapshot.upcomingSongs.size
            val isRunwayPolicy = policy.targetUpcomingBuffer >= RunwayPolicy.REFILL_BELOW
            val isUrgent = isRunwayPolicy && upNextCount < RunwayPolicy.URGENT_BELOW
            val neededCount = if (isRunwayPolicy) {
                (policy.targetUpcomingBuffer - upNextCount).coerceIn(RunwayPolicy.MIN_BATCH, RunwayPolicy.MAX_BATCH)
            } else if (force) {
                policy.refillBatchSize
            } else {
                (policy.targetUpcomingBuffer - upNextCount).coerceIn(4, policy.refillBatchSize)
            }

            val contextPool = (
                initialSnapshot.historySongs +
                    listOfNotNull(initialSnapshot.currentSong) +
                    initialSnapshot.userQueueSongs +
                    initialSnapshot.upcomingSongs
                ).distinctBy { it.id }

            val lockedVibe = com.velvet.app.recommendation.vibe.SessionVibe.fromSessionTracks(
                tracks = listOf(anchor) + initialSnapshot.historySongs.takeLast(4).reversed(),
                featureRepository = featureRepository,
                artistLanguageMap = initialSnapshot.artistLanguageMap,
                requestedLanguageIds = initialSnapshot.preferredLanguageIds,
                allowMixedLanguages = initialSnapshot.allowMixedLanguages,
            )

            // F3 Local-first RAM drain: satisfy up to neededCount from warm RollingCandidatePool before slow network fetches
            val initialExclIds = buildQueueAndHistoryExcludedIds(initialSnapshot)
            val initialSeenComps = buildQueueAndHistoryCompositionKeys(initialSnapshot)
            val preDrainTake = if (mode != RecommendationMode.ARTIST_RADIO && rollingPool.size() > 0) {
                capture.rollingPoolFetched = maxOf(capture.rollingPoolFetched, rollingPool.size())
                rollingPool.drainEligible(
                    maxCount = neededCount,
                    excludedIds = initialExclIds + initialSnapshot.excludedProviderIds,
                    excludedRecIds = initialExclIds,
                    excludedCompositionKeys = initialSeenComps,
                ) { song ->
                    !dismissedFilter(song) &&
                        matchesSessionVibe(song, anchor, lockedVibe, initialSnapshot, mode)
                }
            } else {
                emptyList()
            }
            if (preDrainTake.isNotEmpty()) {
                capture.rollingPoolAccepted += preDrainTake.size
            }

            var engineResult: RecommendationResult? = null
            var failureReason: String? = null
            val preDrainIds = preDrainTake.map { it.id }
            val preDrainRecIds = preDrainTake.map { RecordingIdentityResolver.resolve(it).canonicalRecordingId }
            val remainingNeeded = (neededCount - preDrainTake.size).coerceAtLeast(0)

            if (remainingNeeded > 0 || (force && preDrainTake.isEmpty())) {
                val chainedSeeds = buildChainedSeeds(anchor, initialSnapshot, mode)
                // Bypass stale candidate pool cache when paging forward on the same anchor or forcing
                if (state.lastAnchorId == anchor.id || force) {
                    engine.invalidateCandidateCache(anchor.id)
                }
                try {
                    engineResult = withTimeout(watchdogTimeoutMs) {
                        engine.nextSimilar(
                            seed = anchor,
                            contextSnapshot = (contextPool + preDrainTake).distinctBy { it.id },
                            mode = mode,
                            limit = neededCount,
                            radioSessionId = initialSnapshot.radioSessionId,
                            excludedRecordingIds = preDrainRecIds.toSet(),
                            excludedProviderIds = initialSnapshot.excludedProviderIds + preDrainIds,
                            queueSongs = (initialSnapshot.upcomingSongs + preDrainTake).distinctBy { it.id },
                            userQueueSongs = initialSnapshot.userQueueSongs,
                            recentSongs = initialSnapshot.historySongs.takeLast(8),
                            selectedArtistId = if (mode == RecommendationMode.ARTIST_RADIO) {
                                (initialSnapshot.radioSeedSong ?: anchor).artist
                            } else null,
                            requestedLanguageIds = initialSnapshot.preferredLanguageIds,
                            seedSongsOverride = chainedSeeds,
                            allowMixedLanguages = initialSnapshot.allowMixedLanguages,
                            artistLanguageMap = initialSnapshot.artistLanguageMap,
                            sessionVibeOverride = lockedVibe,
                            randomSeed = initialSnapshot.randomSeed,
                            sessionSkippedArtistCounts = initialSnapshot.sessionSkippedArtistCounts,
                            sessionSkippedGenreCounts = initialSnapshot.sessionSkippedGenreCounts,
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    failureReason = "watchdog_timeout"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failureReason = "engine_error"
                }
            } else {
                capture.requestId = "rolling-pool-${clock()}"
            }

            val canTryUrgentFallback = isUrgent && urgentFallbackProvider != null
            if (engineResult == null && preDrainTake.isEmpty() && !canTryUrgentFallback && rollingPool.size() == 0) {
                stateMutex.withLock {
                    if (state.activeGeneration == generation) {
                        val failures = state.consecutiveFailures + 1
                        val delayMs = if (failureReason == "watchdog_timeout") policy.minCooldownMs else policy.failureBackoffMs
                        state = state.copy(
                            status = RefillStatus.COOLDOWN,
                            consecutiveFailures = failures,
                            backoffUntilMillis = clock() + delayMs,
                        )
                    }
                }
                return RefillDecision.SafeEmpty("", failureReason ?: "engine_error", RefillStatus.COOLDOWN)
            }

            val latest = latestSnapshotProvider()
            val staleReason = validatePostFlightState(
                latest = latest,
                expectedAnchorId = anchor.id,
                generation = generation,
                force = force,
            )
            if (staleReason != null) {
                return RefillDecision.Skip(staleReason)
            }

            // Commit-time identity & composition revalidation against the LATEST live queue snapshot
            val alreadyInQueueIds = buildSet {
                val activeSongs = listOfNotNull(latest.currentSong) +
                    latest.upcomingSongs +
                    latest.userQueueSongs +
                    latest.historySongs
                for (s in activeSongs) {
                    add(s.id)
                    add(RecordingIdentityResolver.resolve(s).canonicalRecordingId)
                    add(SongIdentity.canonicalGroupId(s))
                }
            }
            val alreadySeenComps = buildSet {
                val activeSongs = listOfNotNull(latest.currentSong) +
                    latest.upcomingSongs +
                    latest.userQueueSongs +
                    latest.historySongs
                for (s in activeSongs) {
                    val key = RecordingIdentityResolver.canonicalCompositionTitleKey(s)
                    if (key.isNotBlank()) add(key)
                }
            }

            fun isSongEligibleForCommit(song: Song): Boolean {
                if (dismissedFilter(song)) return false
                if (song.id in latest.excludedProviderIds || song.id in alreadyInQueueIds) return false
                val recId = RecordingIdentityResolver.resolve(song).canonicalRecordingId
                if (recId in alreadyInQueueIds || SongIdentity.canonicalGroupId(song) in alreadyInQueueIds) return false
                val compKey = RecordingIdentityResolver.canonicalCompositionTitleKey(song)
                if (compKey.isNotBlank() && compKey in alreadySeenComps) return false
                return true
            }

            val rawEngineSongs = engineResult?.candidates.orEmpty().map { it.song }
            capture.engineDiag = engineResult?.diagnosticsId?.let { diagId ->
                (engine as? DefaultRecommendationEngine)?.diagnosticsLog?.recent(12)?.find { it.diagnosticsId == diagId }
            }
            if (capture.requestId.isBlank()) {
                capture.requestId = engineResult?.requestId.orEmpty()
            }
            capture.dismissedDropCount = rawEngineSongs.count { dismissedFilter(it) }
            val validPreDrain = preDrainTake.filter(::isSongEligibleForCommit)
            val preDrainCommittedIds = validPreDrain.mapTo(HashSet()) { it.id }
            val preDrainCommittedRecs = validPreDrain.mapTo(HashSet()) {
                RecordingIdentityResolver.resolve(it).canonicalRecordingId
            }
            val preDrainCommittedComps = validPreDrain.mapNotNullTo(HashSet()) {
                RecordingIdentityResolver.canonicalCompositionTitleKey(it).takeIf { k -> k.isNotBlank() }
            }
            val engineAccepted = rawEngineSongs.filter { s ->
                isSongEligibleForCommit(s) &&
                    s.id !in preDrainCommittedIds &&
                    RecordingIdentityResolver.resolve(s).canonicalRecordingId !in preDrainCommittedRecs &&
                    RecordingIdentityResolver.canonicalCompositionTitleKey(s).let { ck -> ck.isBlank() || ck !in preDrainCommittedComps }
            }
            var acceptedSongs = validPreDrain + engineAccepted
            val engineTakeCount = (neededCount - validPreDrain.size).coerceAtLeast(0)
            val surplusEngineSongs = buildList {
                if (engineAccepted.size > engineTakeCount) addAll(engineAccepted.drop(engineTakeCount))
                addAll(
                    engineResult?.overflowSongs.orEmpty().filter { s ->
                        isSongEligibleForCommit(s) && s.id !in preDrainCommittedIds
                    },
                )
            }
            if (surplusEngineSongs.isNotEmpty()) {
                val chosenNow = acceptedSongs.take(neededCount)
                rollingPool.merge(
                    songs = surplusEngineSongs,
                    excludedIds = alreadyInQueueIds + latest.excludedProviderIds + chosenNow.map { it.id },
                    excludedRecIds = alreadyInQueueIds + chosenNow.map {
                        RecordingIdentityResolver.resolve(it).canonicalRecordingId
                    },
                    excludedCompositionKeys = alreadySeenComps + chosenNow.mapNotNull {
                        RecordingIdentityResolver.canonicalCompositionTitleKey(it).takeIf { k -> k.isNotBlank() }
                    },
                )
            }
            if (acceptedSongs.size > neededCount) {
                acceptedSongs = acceptedSongs.take(neededCount)
            }

            // Top up from the rolling candidate pool before stepping anchors if still short
            val rollingTakeIds = preDrainCommittedIds.toMutableSet()
            if (acceptedSongs.size < neededCount && rollingPool.size() > 0) {
                capture.rollingPoolFetched = maxOf(capture.rollingPoolFetched, rollingPool.size())
                val poolTake = rollingPool.drainEligible(
                    maxCount = neededCount - acceptedSongs.size,
                    excludedIds = alreadyInQueueIds + latest.excludedProviderIds + acceptedSongs.map { it.id },
                    excludedRecIds = alreadyInQueueIds + acceptedSongs.map { RecordingIdentityResolver.resolve(it).canonicalRecordingId },
                    excludedCompositionKeys = alreadySeenComps + acceptedSongs.mapNotNull {
                        RecordingIdentityResolver.canonicalCompositionTitleKey(it).takeIf { k -> k.isNotBlank() }
                    },
                    predicate = { song ->
                        !dismissedFilter(song) &&
                            matchesSessionVibe(song, anchor, lockedVibe, latest, mode)
                    },
                )
                if (poolTake.isNotEmpty()) {
                    rollingTakeIds.addAll(poolTake.map { it.id })
                    capture.rollingPoolAccepted += poolTake.size
                    acceptedSongs = (acceptedSongs + poolTake).distinctBy { it.id }
                }
            }

            // C4: Anchor stepping on exhaustion (retry next-most-recent liked/completed track while keeping SessionVibe locked)
            var steppedEngineResult: RecommendationResult? = null
            if (acceptedSongs.isEmpty() && mode != RecommendationMode.ARTIST_RADIO && latest.fallbackAnchorPool.isNotEmpty()) {
                val steppedCandidates = latest.fallbackAnchorPool
                    .filter { it.id != anchor.id && it.id.isNotBlank() }
                    .take(4)
                for (stepAnchor in steppedCandidates) {
                    engine.invalidateCandidateCache(stepAnchor.id)
                    val steppedResult = try {
                        withTimeoutOrNull(watchdogTimeoutMs / 2) {
                            engine.nextSimilar(
                                seed = stepAnchor,
                                contextSnapshot = contextPool + acceptedSongs,
                                mode = mode,
                                limit = neededCount,
                                radioSessionId = latest.radioSessionId,
                                excludedProviderIds = latest.excludedProviderIds + alreadyInQueueIds,
                                queueSongs = latest.upcomingSongs,
                                userQueueSongs = latest.userQueueSongs,
                                recentSongs = latest.historySongs.takeLast(8),
                                requestedLanguageIds = latest.preferredLanguageIds,
                                allowMixedLanguages = latest.allowMixedLanguages,
                                artistLanguageMap = latest.artistLanguageMap,
                                sessionVibeOverride = lockedVibe,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val validStepped = steppedResult?.candidates.orEmpty()
                        .map { it.song }
                        .filter(::isSongEligibleForCommit)
                    if (validStepped.isNotEmpty()) {
                        steppedEngineResult = steppedResult
                        capture.wasSteppedAnchor = true
                        capture.steppedFromAnchorId = anchor.id
                        capture.activeAnchorId = stepAnchor.id
                        capture.activeAnchorTitle = stepAnchor.title
                        capture.steppedDiag = steppedResult?.diagnosticsId?.let { diagId ->
                            (engine as? DefaultRecommendationEngine)?.diagnosticsLog?.recent(12)?.find { it.diagnosticsId == diagId }
                        }
                        acceptedSongs = validStepped.take(neededCount)
                        if (validStepped.size > neededCount) {
                            rollingPool.merge(
                                songs = validStepped.drop(neededCount),
                                excludedIds = alreadyInQueueIds + latest.excludedProviderIds,
                                excludedRecIds = alreadyInQueueIds,
                                excludedCompositionKeys = alreadySeenComps,
                            )
                        }
                        if (engineResult == null) {
                            engineResult = steppedResult
                            failureReason = null
                        }
                        break
                    }
                }
            }

            com.velvet.app.diagnostics.QueueHealthMonitor.recordRollingPoolSize(rollingPool.size())

            var usedUrgentFallback = false
            if (acceptedSongs.isEmpty() && canTryUrgentFallback) {
                val fallbackRaw = try {
                    withTimeoutOrNull(1_500L) {
                        urgentFallbackProvider!!.invoke(anchor, latest, alreadyInQueueIds)
                    }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                capture.urgentFallbackFetched = fallbackRaw.size
                val vibe = com.velvet.app.recommendation.vibe.SessionVibe.fromSessionTracks(
                    tracks = listOf(anchor) + latest.historySongs.takeLast(4).reversed(),
                    featureRepository = featureRepository,
                    artistLanguageMap = latest.artistLanguageMap,
                    requestedLanguageIds = latest.preferredLanguageIds,
                    allowMixedLanguages = latest.allowMixedLanguages,
                )
                val allowedVibeLangs = vibe.effectiveAllowedLanguages
                acceptedSongs = fallbackRaw
                    .asSequence()
                    .distinctBy { it.id }
                    .filter { song ->
                        if (song.id.isBlank() || song.title.isBlank()) return@filter false
                        if (song.durationSec in 0L..44L) return@filter false
                        if (dismissedFilter(song)) return@filter false
                        if (song.id in latest.excludedProviderIds || song.id in alreadyInQueueIds) return@filter false
                        val identity = RecordingIdentityResolver.resolve(song)
                        if (!identity.releaseClassification.isVerifiedOriginalAudio) return@filter false
                        if (identity.canonicalRecordingId in alreadyInQueueIds ||
                            SongIdentity.canonicalGroupId(song) in alreadyInQueueIds
                        ) {
                            return@filter false
                        }
                        if (RecordingIdentityResolver.isSameCompositionTitle(anchor, song)) return@filter false
                        if (allowedVibeLangs.isNotEmpty()) {
                            val feat = featureRepository.getCachedOrExtract(song)
                            val (candLangs, candLangConf) = com.velvet.app.recommendation.vibe.SessionVibe.resolveSongLanguage(
                                song = song,
                                features = feat,
                                artistLanguageMap = latest.artistLanguageMap,
                            )
                            val artistLang = com.velvet.app.recommendation.vibe.ArtistLanguageMap.inferArtistLanguage(
                                song.artist,
                                latest.artistLanguageMap,
                            )
                            if (artistLang != null && artistLang.second >= 0.72 && artistLang.first !in allowedVibeLangs) {
                                return@filter false
                            }
                            if (candLangs.isNotEmpty() && candLangConf >= 0.65 && candLangs.none { it in allowedVibeLangs }) {
                                return@filter false
                            }
                        }
                        true
                    }
                    .take(neededCount)
                    .toList()
                usedUrgentFallback = acceptedSongs.isNotEmpty()
                capture.urgentFallbackAccepted = acceptedSongs.size
            }

            // Record accepted source IDs & library-only share into funnel capture
            val trackSourceMap = buildMap<String, Set<String>> {
                engineResult?.candidates.orEmpty().forEach { put(it.song.id, it.sourceIds) }
                steppedEngineResult?.candidates.orEmpty().forEach { put(it.song.id, it.sourceIds) }
            }
            val acceptedSources = linkedSetOf<String>()
            var libOnlyCount = 0
            for (s in acceptedSongs) {
                when {
                    usedUrgentFallback -> {
                        acceptedSources.add("urgent_fallback")
                        libOnlyCount++
                    }
                    s.id in rollingTakeIds -> {
                        acceptedSources.add("rolling_pool")
                    }
                    else -> {
                        val srcs = trackSourceMap[s.id].orEmpty()
                        acceptedSources.addAll(srcs)
                        if (capture.wasSteppedAnchor) acceptedSources.add("stepped_anchor")
                        if (srcs.isNotEmpty() && srcs.all { it == "g6_library" || it == "g0_instant_catalog" }) {
                            libOnlyCount++
                        }
                    }
                }
            }
            capture.acceptedSongs = acceptedSongs
            capture.acceptedSourceIds = acceptedSources
            capture.libraryOrInstantAcceptedCount = libOnlyCount

            val resolvedResult = engineResult ?: RecommendationResult(
                requestId = capture.requestId.ifBlank { failureReason ?: "urgent-fallback" },
                mode = mode,
                candidates = emptyList(),
                modelVersion = "fallback",
                generatedAtMillis = clock(),
                sourceHealth = emptyMap(),
                diagnosticsId = "",
            )

            stateMutex.withLock {
                if (state.activeGeneration != generation) {
                    return@withLock RefillDecision.Skip("superseded_generation")
                }
                val now = clock()
                if (acceptedSongs.isEmpty()) {
                    if (failureReason != null) {
                        val failures = state.consecutiveFailures + 1
                        val delayMs = if (failureReason == "watchdog_timeout") policy.minCooldownMs else policy.failureBackoffMs
                        state = state.copy(
                            status = RefillStatus.COOLDOWN,
                            consecutiveFailures = failures,
                            backoffUntilMillis = now + delayMs,
                        )
                        return@withLock RefillDecision.SafeEmpty("", failureReason, RefillStatus.COOLDOWN)
                    }
                    val emptyStreak = if (state.lastAnchorId == anchor.id) state.emptyStreakForCurrentAnchor + 1 else 1
                    val newStatus = if (emptyStreak >= policy.maxEmptyRefillsPerAnchor) {
                        RefillStatus.EXHAUSTED
                    } else {
                        RefillStatus.COOLDOWN
                    }
                    state = state.copy(
                        status = newStatus,
                        consecutiveFailures = 0,
                        emptyStreakForCurrentAnchor = emptyStreak,
                        backoffUntilMillis = now + policy.minCooldownMs,
                    )
                    RefillDecision.SafeEmpty(resolvedResult.requestId, "no_eligible_verified_studio_audio", newStatus)
                } else {
                    state = state.copy(
                        status = RefillStatus.IDLE,
                        lastSuccessAtMillis = now,
                        consecutiveFailures = 0,
                        emptyStreakForCurrentAnchor = 0,
                        backoffUntilMillis = 0L,
                    )
                    RefillDecision.AppendSongs(
                        requestId = resolvedResult.requestId,
                        mode = mode,
                        songs = acceptedSongs,
                        result = resolvedResult,
                        replaceUpcomingIfEmptyAndPlayNext = autoPlayNext &&
                            latest.upcomingSongs.isEmpty() &&
                            latest.userQueueSongs.isEmpty(),
                    )
                }
            }
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (state.activeGeneration == generation && state.status == RefillStatus.IN_FLIGHT) {
                        state = state.copy(status = RefillStatus.IDLE)
                    }
                }
            }
        }
    }

    /**
     * Proactively tops up the in-memory [rollingPool] off the critical path when buffer size drops
     * below [targetPoolSize], using chained session anchors so foreground refills hit local RAM first.
     */
    suspend fun ensureRollingPoolWarm(
        initialSnapshot: QueueRefillSnapshot,
        dismissedFilter: (Song) -> Boolean = { false },
        targetPoolSize: Int = 60,
    ): Int {
        if (!initialSnapshot.isAutoplayEnabled && !initialSnapshot.isRadioActive) return 0
        if (initialSnapshot.isArtistRadioActive) return 0
        if (rollingPool.size() >= targetPoolSize) return 0
        if (warmUpMutex.isLocked) return 0
        return warmUpMutex.withLock {
            if (rollingPool.size() >= targetPoolSize) return@withLock 0
            val anchor = resolveAnchor(initialSnapshot, null) ?: return@withLock 0
            val mode = if (initialSnapshot.isRadioActive) RecommendationMode.SONG_RADIO else RecommendationMode.AUTOPLAY_REFILL
            val chainedSeeds = buildChainedSeeds(anchor, initialSnapshot, mode)
            val lockedVibe = com.velvet.app.recommendation.vibe.SessionVibe.fromSessionTracks(
                tracks = listOf(anchor) + initialSnapshot.historySongs.takeLast(4).reversed(),
                featureRepository = featureRepository,
                artistLanguageMap = initialSnapshot.artistLanguageMap,
                requestedLanguageIds = initialSnapshot.preferredLanguageIds,
                allowMixedLanguages = initialSnapshot.allowMixedLanguages,
            )
            val exclIds = buildQueueAndHistoryExcludedIds(initialSnapshot)
            val seenComps = buildQueueAndHistoryCompositionKeys(initialSnapshot)
            val contextPool = (
                initialSnapshot.historySongs +
                    listOfNotNull(initialSnapshot.currentSong) +
                    initialSnapshot.userQueueSongs +
                    initialSnapshot.upcomingSongs
                ).distinctBy { it.id }

            engine.invalidateCandidateCache(anchor.id)
            val res = try {
                withTimeoutOrNull(watchdogTimeoutMs) {
                    engine.nextSimilar(
                        seed = anchor,
                        contextSnapshot = contextPool,
                        mode = mode,
                        limit = 25,
                        radioSessionId = initialSnapshot.radioSessionId,
                        excludedProviderIds = initialSnapshot.excludedProviderIds + exclIds,
                        queueSongs = initialSnapshot.upcomingSongs,
                        userQueueSongs = initialSnapshot.userQueueSongs,
                        recentSongs = initialSnapshot.historySongs.takeLast(8),
                        requestedLanguageIds = initialSnapshot.preferredLanguageIds,
                        seedSongsOverride = chainedSeeds,
                        allowMixedLanguages = initialSnapshot.allowMixedLanguages,
                        artistLanguageMap = initialSnapshot.artistLanguageMap,
                        sessionVibeOverride = lockedVibe,
                        randomSeed = initialSnapshot.randomSeed,
                        sessionSkippedArtistCounts = initialSnapshot.sessionSkippedArtistCounts,
                        sessionSkippedGenreCounts = initialSnapshot.sessionSkippedGenreCounts,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@withLock 0

            val poolAdditions = (res.candidates.map { it.song } + res.overflowSongs)
                .distinctBy { it.id }
                .filter { s -> !dismissedFilter(s) && matchesSessionVibe(s, anchor, lockedVibe, initialSnapshot, mode) }
            val added = rollingPool.merge(
                songs = poolAdditions,
                excludedIds = exclIds + initialSnapshot.excludedProviderIds,
                excludedRecIds = exclIds,
                excludedCompositionKeys = seenComps,
            )
            com.velvet.app.diagnostics.QueueHealthMonitor.recordRollingPoolSize(rollingPool.size())
            added
        }
    }

    private fun buildChainedSeeds(
        anchor: Song,
        snapshot: QueueRefillSnapshot,
        mode: RecommendationMode,
    ): List<Song> {
        if (mode == RecommendationMode.ARTIST_RADIO) return listOf(anchor)
        return (listOf(anchor) + snapshot.seedSongsOverride + snapshot.historySongs.takeLast(3).reversed())
            .distinctBy { it.id }
            .take(4)
    }

    private fun buildQueueAndHistoryExcludedIds(snapshot: QueueRefillSnapshot): Set<String> = buildSet {
        val activeSongs = listOfNotNull(snapshot.currentSong) +
            snapshot.upcomingSongs +
            snapshot.userQueueSongs +
            snapshot.historySongs
        for (s in activeSongs) {
            add(s.id)
            add(RecordingIdentityResolver.resolve(s).canonicalRecordingId)
            add(SongIdentity.canonicalGroupId(s))
        }
    }

    private fun buildQueueAndHistoryCompositionKeys(snapshot: QueueRefillSnapshot): Set<String> = buildSet {
        val activeSongs = listOfNotNull(snapshot.currentSong) +
            snapshot.upcomingSongs +
            snapshot.userQueueSongs +
            snapshot.historySongs
        for (s in activeSongs) {
            val key = RecordingIdentityResolver.canonicalCompositionTitleKey(s)
            if (key.isNotBlank()) add(key)
        }
    }

    private fun matchesSessionVibe(
        song: Song,
        anchor: Song,
        lockedVibe: com.velvet.app.recommendation.vibe.SessionVibe,
        snapshot: QueueRefillSnapshot,
        mode: RecommendationMode,
    ): Boolean {
        if (song.id.isBlank() || song.title.isBlank()) return false
        if (song.durationSec in 0L..44L) return false
        val identity = RecordingIdentityResolver.resolve(song)
        if (!identity.releaseClassification.isVerifiedOriginalAudio) return false
        if (RecordingIdentityResolver.isSameCompositionTitle(anchor, song)) return false

        val feat = featureRepository.getCachedOrExtract(song)
        val allowedVibeLangs = lockedVibe.effectiveAllowedLanguages
        if (allowedVibeLangs.isNotEmpty()) {
            val (candLangs, candLangConf) = com.velvet.app.recommendation.vibe.SessionVibe.resolveSongLanguage(
                song = song,
                features = feat,
                artistLanguageMap = snapshot.artistLanguageMap,
            )
            val artistLang = com.velvet.app.recommendation.vibe.ArtistLanguageMap.inferArtistLanguage(
                song.artist,
                snapshot.artistLanguageMap,
            )
            if (artistLang != null && artistLang.second >= 0.72 && artistLang.first !in allowedVibeLangs) {
                return false
            }
            if (candLangs.isNotEmpty() && candLangConf >= 0.65 && candLangs.none { it in allowedVibeLangs }) {
                return false
            }
        }
        if (mode != RecommendationMode.ARTIST_RADIO) {
            val seedFeat = featureRepository.getCachedOrExtract(anchor)
            val seedIsDevotional = com.velvet.app.recommendation.features.GenreOntology.DEVOTIONAL in seedFeat.genreIds
            if (seedIsDevotional) {
                val candHasParty = (feat.moodIds["party_celebratory"] ?: 0.0) >= 0.5
                val candDevOrClass = com.velvet.app.recommendation.features.GenreOntology.DEVOTIONAL in feat.genreIds ||
                    com.velvet.app.recommendation.model.MusicType.CLASSICAL in feat.musicTypes
                if (!candDevOrClass || candHasParty) return false
            }
            val seedType = seedFeat.musicTypes.maxByOrNull { it.value }?.key
                ?: com.velvet.app.recommendation.model.MusicType.UNKNOWN
            val candType = feat.musicTypes.maxByOrNull { it.value }?.key
                ?: com.velvet.app.recommendation.model.MusicType.UNKNOWN
            if (seedType != com.velvet.app.recommendation.model.MusicType.UNKNOWN &&
                candType != com.velvet.app.recommendation.model.MusicType.UNKNOWN &&
                com.velvet.app.recommendation.features.GenreOntology.musicTypeCompatibility(seedType, candType) <
                com.velvet.app.recommendation.vibe.SessionVibe.MIN_TYPE_COMPATIBILITY
            ) {
                return false
            }
            val vibeGenres = lockedVibe.genreWeights.keys.ifEmpty { seedFeat.genreIds.keys }
            val candGenres = feat.genreIds.keys
            if (vibeGenres.isNotEmpty() && candGenres.isNotEmpty() &&
                com.velvet.app.recommendation.vibe.SessionVibe.maxGenreSimilarity(vibeGenres, candGenres) <
                com.velvet.app.recommendation.vibe.SessionVibe.MIN_GENRE_SIMILARITY
            ) {
                return false
            }
        }
        return true
    }

    private suspend fun validatePostFlightState(
        latest: QueueRefillSnapshot,
        expectedAnchorId: String,
        generation: Long,
        force: Boolean,
    ): String? {
        val currentGen = stateMutex.withLock { state.activeGeneration }
        if (currentGen != generation) return "superseded_generation"
        if (!latest.isAutoplayEnabled && !latest.isRadioActive) return "autoplay_disabled_during_fetch"
        if (latest.repeatMode == RepeatMode.ONE && !force) return "repeat_one_enabled_during_fetch"
        val anchorStillInSession =
            latest.currentSong?.id == expectedAnchorId ||
                latest.radioSeedSong?.id == expectedAnchorId ||
                latest.historySongs.any { it.id == expectedAnchorId } ||
                latest.upcomingSongs.any { it.id == expectedAnchorId } ||
                latest.userQueueSongs.any { it.id == expectedAnchorId }
        if (!force && !anchorStillInSession) {
            return "session_replaced"
        }
        return null
    }

    private fun resolveAnchor(snapshot: QueueRefillSnapshot, seedId: String?): Song? {
        if (seedId != null) {
            return snapshot.upcomingSongs.find { it.id == seedId }
                ?: snapshot.userQueueSongs.find { it.id == seedId }
                ?: snapshot.historySongs.find { it.id == seedId }
                ?: snapshot.currentSong
        }
        return snapshot.currentSong ?: snapshot.historySongs.lastOrNull()
    }
}
