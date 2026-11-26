package com.velvet.app.recommendation.engine

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.SongIdentity
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.LibraryIndex
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object RunwayPolicy {
    const val TARGET_AHEAD = 30
    const val REFILL_BELOW = 15
    const val URGENT_BELOW = 6
    const val MIN_BATCH = 15
    const val MAX_BATCH = 20
    const val WATCHDOG_TIMEOUT_MS = 8_000L
    const val PERIODIC_TICK_MS = 20_000L
    const val NEXT_COALESCE_WINDOW_MS = 150L
}

enum class RunwayTrigger {
    TRACK_CHANGE,
    QUEUE_MUTATION,
    APP_START_OR_RESTORE,
    MANUAL_NEXT,
    AUTOPLAY_TOGGLED,
    NETWORK_REGAINED,
    PERIODIC_TICK,
    MANUAL_RETRY,
}

/**
 * Coalesces rapid Next taps arriving within [delayMs] into a single queue advance + ExoPlayer sync.
 */
class NextTapCoalescer(
    private val scope: CoroutineScope,
    private val delayMs: Long = RunwayPolicy.NEXT_COALESCE_WINDOW_MS,
    private val onCoalesced: () -> Unit,
) {
    private var pendingJob: Job? = null
    @Volatile
    var queuedTaps: Int = 0
        private set

    fun tap() {
        queuedTaps++
        pendingJob?.cancel()
        pendingJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            queuedTaps = 0
            onCoalesced()
        }
    }

    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
        queuedTaps = 0
    }
}

/**
 * Serializes downstream ExoPlayer queue mutations so two `syncToExoPlayer` calls
 * can never run concurrently or interleave out-of-order updates.
 */
class ExoSyncSerializer(
    private val scope: CoroutineScope,
    private val dispatchDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val lock = Any()
    private var running = false
    private var pendingTask: (() -> Unit)? = null

    fun submit(task: () -> Unit) {
        val startLoop = synchronized(lock) {
            pendingTask = task
            if (running) {
                false
            } else {
                running = true
                true
            }
        }
        if (startLoop) {
            scope.launch(dispatchDispatcher) {
                while (true) {
                    val next = synchronized(lock) {
                        val t = pendingTask
                        pendingTask = null
                        if (t == null) {
                            running = false
                        }
                        t
                    } ?: break
                    runCatching { next() }
                }
            }
        }
    }
}

/**
 * Single owner of the deep "runway" ahead buffer ([RunwayPolicy.TARGET_AHEAD] / [RunwayPolicy.REFILL_BELOW])
 * and off-main recommendation preparation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunwayController(
    private val coordinator: AutoplayRefillCoordinator,
    private val libraryIndex: LibraryIndex,
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val bgDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + bgDispatcher)
    private val preparedSeedCache = ConcurrentHashMap<String, List<Song>>()
    private val refillLatenciesMs = ArrayDeque<Long>(100)
    private var activeRefillJob: Job? = null
    private var periodicJob: Job? = null

    fun startPeriodicTick(
        isPlayingProvider: () -> Boolean,
        onTick: () -> Unit,
    ) {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                delay(RunwayPolicy.PERIODIC_TICK_MS)
                if (isPlayingProvider()) {
                    withContext(mainDispatcher) { onTick() }
                }
            }
        }
    }

    /**
     * Main-thread O(1) lookup of already-prepared verified candidates for [seedId].
     * Never performs identity parsing or full library scans on callers' thread.
     */
    fun takePreparedInstant(
        seedId: String,
        excludedIds: Set<String>,
        dismissedFilter: (Song) -> Boolean = { false },
        limit: Int = 6,
    ): List<Song> {
        val cached = preparedSeedCache[seedId] ?: return emptyList()
        return cached
            .asSequence()
            .filter { it.id !in excludedIds && !dismissedFilter(it) }
            .take(limit)
            .toList()
    }

    /**
     * Off-main background preparation so upcoming seeds already have verified studio matches
     * cached in RAM before the track ends or the user taps Next.
     */
    fun prepareSeedInBackground(
        seed: Song,
        libraryDataProvider: () -> LibraryData,
        excludedIdsProvider: () -> Set<String>,
        preferredLanguageIds: Set<String>,
        allowArtistHeavy: Boolean,
        extraPoolProvider: () -> List<Song> = { emptyList() },
    ) {
        scope.launch {
            libraryIndex.updateIncremental(libraryDataProvider())
            val exclIds = excludedIdsProvider()
            val matches = libraryIndex.findSimilarFromIndex(
                seed = seed,
                excludedIds = exclIds,
                preferredLanguageIds = preferredLanguageIds,
                allowArtistHeavy = allowArtistHeavy,
                extraPool = extraPoolProvider(),
                limit = RunwayPolicy.MAX_BATCH,
            )
            if (matches.isNotEmpty()) {
                preparedSeedCache[seed.id] = matches
                if (preparedSeedCache.size > 64) {
                    val dropKeys = preparedSeedCache.keys.take(preparedSeedCache.size - 48)
                    dropKeys.forEach {
                        if (it != seed.id) preparedSeedCache.remove(it)
                    }
                }
            }
        }
    }

    fun triggerRefill(
        trigger: RunwayTrigger,
        snapshotProvider: () -> QueueRefillSnapshot,
        libraryDataProvider: () -> LibraryData,
        seedId: String? = null,
        autoPlayNext: Boolean = false,
        force: Boolean = false,
        dismissedFilter: (Song) -> Boolean = { false },
        onDecision: (RefillDecision) -> Unit,
    ): Job? {
        val snap = snapshotProvider()
        if (!snap.isAutoplayEnabled && !snap.isRadioActive) return null
        if (snap.upcomingSongs.size >= RunwayPolicy.REFILL_BELOW && !force && !autoPlayNext) {
            return null
        }
        val effectiveForce = force || autoPlayNext || trigger == RunwayTrigger.MANUAL_RETRY
        if (activeRefillJob?.isActive == true && !effectiveForce) {
            return activeRefillJob
        }
        if (effectiveForce) {
            activeRefillJob?.cancel()
        }

        val job = scope.launch {
            val t0 = clock()
            if (effectiveForce) {
                coordinator.resetForNewSeed(seedId ?: snap.currentSong?.id)
            }
            libraryIndex.updateIncremental(libraryDataProvider())
            val decision = coordinator.requestRefill(
                initialSnapshot = snapshotProvider(),
                latestSnapshotProvider = snapshotProvider,
                seedId = seedId,
                autoPlayNext = autoPlayNext,
                force = effectiveForce,
                dismissedFilter = dismissedFilter,
            )
            val elapsed = (clock() - t0).coerceAtLeast(0L)
            recordLatency(elapsed)

            if (decision is RefillDecision.AppendSongs && decision.songs.isNotEmpty()) {
                val anchor = snap.currentSong
                if (anchor != null) {
                    preparedSeedCache[anchor.id] = decision.songs
                }
            } else if (decision is RefillDecision.SafeEmpty) {
                DiagnosticsLogger.i("Runway", "Trigger=$trigger returned SafeEmpty(${decision.status}): ${decision.reason}")
            }
            withContext(mainDispatcher) {
                onDecision(decision)
            }
        }
        activeRefillJob = job
        return job
    }

    private fun recordLatency(ms: Long) {
        synchronized(refillLatenciesMs) {
            if (refillLatenciesMs.size >= 100) refillLatenciesMs.removeFirst()
            refillLatenciesMs.addLast(ms)
            val sorted = refillLatenciesMs.sorted()
            val p50 = sorted[sorted.size / 2]
            val p95 = sorted[((sorted.size - 1) * 95) / 100]
            QueueHealthMonitor.recordLatencyPercentiles(p50, p95)
        }
    }

    fun latencyPercentiles(): Pair<Long, Long> = synchronized(refillLatenciesMs) {
        if (refillLatenciesMs.isEmpty()) return 0L to 0L
        val sorted = refillLatenciesMs.sorted()
        sorted[sorted.size / 2] to sorted[((sorted.size - 1) * 95) / 100]
    }
}
