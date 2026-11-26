package com.velvet.app.recommendation.engine

import com.velvet.app.data.Song
import com.velvet.app.playback.RepeatMode
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
    private val stateMutex = Mutex()
    private var state = RefillState()

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

    suspend fun requestRefill(
        initialSnapshot: QueueRefillSnapshot,
        latestSnapshotProvider: () -> QueueRefillSnapshot,
        seedId: String? = null,
        autoPlayNext: Boolean = false,
        force: Boolean = false,
        dismissedFilter: (Song) -> Boolean = { false },
    ): RefillDecision {
        val startMs = clock()
        val decision = runCatching {
            requestRefillInternal(
                initialSnapshot = initialSnapshot,
                latestSnapshotProvider = latestSnapshotProvider,
                seedId = seedId,
                autoPlayNext = autoPlayNext,
                force = force,
                dismissedFilter = dismissedFilter,
            )
        }.getOrElse { throw it }
        val anchorTitle = resolveAnchor(initialSnapshot, seedId)?.title ?: "(none)"
        val modeName = when {
            initialSnapshot.isArtistRadioActive -> RecommendationMode.ARTIST_RADIO.name
            initialSnapshot.isRadioActive -> RecommendationMode.SONG_RADIO.name
            else -> RecommendationMode.AUTOPLAY_REFILL.name
        }
        val latestSnap = runCatching { latestSnapshotProvider() }.getOrElse { initialSnapshot }
        val durationMs = (clock() - startMs).coerceAtLeast(0L)
        val snapStatus = stateMutex.withLock { state.status }
        when (decision) {
            is RefillDecision.AppendSongs -> com.velvet.app.diagnostics.QueueHealthMonitor.recordRefillDecision(
                seedTitle = anchorTitle,
                mode = modeName,
                outcome = "APPEND",
                reason = "ok",
                appendedCount = decision.songs.size,
                durationMs = durationMs,
                queueAheadAfter = latestSnap.upcomingSongs.size + decision.songs.size,
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
                queueAheadAfter = latestSnap.upcomingSongs.size,
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
                queueAheadAfter = latestSnap.upcomingSongs.size,
                status = snapStatus,
                timestampMs = clock(),
            )
        }
        return decision
    }

    private suspend fun requestRefillInternal(
        initialSnapshot: QueueRefillSnapshot,
        latestSnapshotProvider: () -> QueueRefillSnapshot,
        seedId: String?,
        autoPlayNext: Boolean,
        force: Boolean,
        dismissedFilter: (Song) -> Boolean,
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
            if (upNextCount >= policy.lowWatermarkTrigger && !force && !autoPlayNext) {
                return RefillDecision.Skip("buffer_healthy")
            }
            if (upNextCount >= policy.targetUpcomingBuffer && !force) {
                return RefillDecision.Skip("buffer_at_target")
            }

            val sameAnchor = state.lastAnchorId == anchor.id
            val effectiveEmptyStreak = if (sameAnchor) state.emptyStreakForCurrentAnchor else 0

            if (sameAnchor && effectiveEmptyStreak >= policy.maxEmptyRefillsPerAnchor && !force) {
                state = state.copy(status = RefillStatus.EXHAUSTED, emptyStreakForCurrentAnchor = effectiveEmptyStreak)
                return RefillDecision.Skip("anchor_exhausted")
            }

            if (state.status == RefillStatus.IN_FLIGHT && !force) {
                return RefillDecision.Skip("already_in_flight")
            }
            if (!force && !autoPlayNext && now < state.backoffUntilMillis) {
                state = state.copy(status = RefillStatus.COOLDOWN)
                return RefillDecision.Skip("cooldown_active")
            }
            if (!force && !autoPlayNext && sameAnchor && (now - state.lastAttemptedAtMillis) in 0L until policy.minCooldownMs) {
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

        val neededCount = if (force) {
            policy.refillBatchSize
        } else {
            (policy.targetUpcomingBuffer - initialSnapshot.upcomingSongs.size).coerceIn(4, policy.refillBatchSize)
        }

        val contextPool = (
            initialSnapshot.historySongs +
                listOfNotNull(initialSnapshot.currentSong) +
                initialSnapshot.userQueueSongs +
                initialSnapshot.upcomingSongs
            ).distinctBy { it.id }

        val result = try {
            engine.nextSimilar(
                seed = anchor,
                contextSnapshot = contextPool,
                mode = mode,
                limit = neededCount,
                radioSessionId = initialSnapshot.radioSessionId,
                excludedProviderIds = initialSnapshot.excludedProviderIds,
                queueSongs = initialSnapshot.upcomingSongs,
                userQueueSongs = initialSnapshot.userQueueSongs,
                recentSongs = initialSnapshot.historySongs.takeLast(8),
                selectedArtistId = if (mode == RecommendationMode.ARTIST_RADIO) {
                    (initialSnapshot.radioSeedSong ?: anchor).artist
                } else null,
                requestedLanguageIds = initialSnapshot.preferredLanguageIds,
                seedSongsOverride = initialSnapshot.seedSongsOverride,
            )
        } catch (e: CancellationException) {
            stateMutex.withLock {
                if (state.activeGeneration == generation && state.status == RefillStatus.IN_FLIGHT) {
                    state = state.copy(status = RefillStatus.IDLE)
                }
            }
            throw e
        } catch (_: Exception) {
            stateMutex.withLock {
                if (state.activeGeneration == generation) {
                    val failures = state.consecutiveFailures + 1
                    state = state.copy(
                        status = RefillStatus.COOLDOWN,
                        consecutiveFailures = failures,
                        backoffUntilMillis = clock() + policy.failureBackoffMs,
                    )
                }
            }
            return RefillDecision.SafeEmpty("", "engine_error", RefillStatus.COOLDOWN)
        }

        val latest = latestSnapshotProvider()
        val staleReason = validatePostFlightState(
            initial = initialSnapshot,
            latest = latest,
            expectedAnchorId = anchor.id,
            generation = generation,
            force = force,
        )
        if (staleReason != null) {
            return RefillDecision.Skip(staleReason)
        }

        val alreadyInQueueIds = (
            listOfNotNull(latest.currentSong) +
                latest.upcomingSongs +
                latest.userQueueSongs +
                latest.historySongs.takeLast(10)
            ).map { RecordingIdentityResolver.resolve(it).canonicalRecordingId }.toSet()

        val acceptedSongs = result.candidates
            .map { it.song }
            .filterNot { song ->
                dismissedFilter(song) ||
                    song.id in latest.excludedProviderIds ||
                    RecordingIdentityResolver.resolve(song).canonicalRecordingId in alreadyInQueueIds
            }

        return stateMutex.withLock {
            if (state.activeGeneration != generation) {
                return@withLock RefillDecision.Skip("superseded_generation")
            }
            val now = clock()
            if (acceptedSongs.isEmpty()) {
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
                RefillDecision.SafeEmpty(result.requestId, "no_eligible_verified_studio_audio", newStatus)
            } else {
                state = state.copy(
                    status = RefillStatus.IDLE,
                    lastSuccessAtMillis = now,
                    consecutiveFailures = 0,
                    emptyStreakForCurrentAnchor = 0,
                    backoffUntilMillis = 0L,
                )
                RefillDecision.AppendSongs(
                    requestId = result.requestId,
                    mode = mode,
                    songs = acceptedSongs,
                    result = result,
                    replaceUpcomingIfEmptyAndPlayNext = autoPlayNext &&
                        latest.upcomingSongs.isEmpty() &&
                        latest.userQueueSongs.isEmpty(),
                )
            }
        }
    }

    private suspend fun validatePostFlightState(
        initial: QueueRefillSnapshot,
        latest: QueueRefillSnapshot,
        expectedAnchorId: String,
        generation: Long,
        force: Boolean,
    ): String? {
        val currentGen = stateMutex.withLock { state.activeGeneration }
        if (currentGen != generation) return "superseded_generation"
        if (!latest.isAutoplayEnabled && !latest.isRadioActive) return "autoplay_disabled_during_fetch"
        if (latest.repeatMode == RepeatMode.ONE && !force) return "repeat_one_enabled_during_fetch"
        if (latest.queueVersion != initial.queueVersion) return "stale_queue_version"
        val currentOrLastId = latest.currentSong?.id ?: latest.historySongs.lastOrNull()?.id
        if (currentOrLastId != expectedAnchorId && latest.radioSeedSong?.id != expectedAnchorId) {
            return "active_seed_changed"
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
