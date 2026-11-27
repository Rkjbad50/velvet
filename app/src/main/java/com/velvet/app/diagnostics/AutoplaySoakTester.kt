package com.velvet.app.diagnostics

import com.velvet.app.VelvetApp
import com.velvet.app.data.LanguageLocaleConfig
import com.velvet.app.data.Song
import com.velvet.app.data.YouTube
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.data.isOriginalMusicAudio
import com.velvet.app.data.isPureMusic
import com.velvet.app.playback.QueueManager
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.RollingCandidatePool
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RecommendationEngine
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.profile.UserProfileRepository
import com.velvet.app.recommendation.vibe.ArtistLanguageProfile
import com.velvet.app.recommendation.vibe.ArtistLanguageRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class SoakEmptyDecision(
    val step: Int,
    val refillNumber: Int,
    val anchorId: String,
    val anchorTitle: String,
    val status: String,
    val reason: String,
    val timestampMs: Long,
)

data class SoakFunnelAverages(
    val refillCount: Int,
    val avgFetched: Double,
    val avgAfterEligibility: Double,
    val avgAfterIdentityDedupe: Double,
    val avgAfterDismissed: Double,
    val avgAfterLanguageGate: Double,
    val avgAfterGenreTypeGate: Double,
    val avgAfterArtistPurity: Double,
    val avgAccepted: Double,
    val avgAppended: Double,
    val avgLibOrInstantPct: Double,
    val steppedAnchorRefills: Int,
    val widenedRetryCount: Int,
)

data class SoakTestResult(
    val languageName: String,
    val languageIso: String,
    val seedSong: Song,
    val targetAdvances: Int,
    val completedAdvances: Int,
    val durationMs: Long,
    val minQueueAhead: Int,
    val avgQueueAhead: Double,
    val maxQueueAhead: Int,
    val stallCount: Int,
    val emptyAdvanceCount: Int,
    val refillLatencyP50Ms: Long,
    val refillLatencyP95Ms: Long,
    val funnelAverages: SoakFunnelAverages,
    val emptyOrExhaustedDecisions: List<SoakEmptyDecision>,
    val stallDetails: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    fun formatPlainText(): String = buildString {
        appendLine("=== VELVET AUTOPLAY SOAK TEST ===")
        appendLine("Language: $languageName ($languageIso)")
        appendLine("Seed: ${seedSong.title} — ${seedSong.artist} [${seedSong.id}]")
        appendLine("Advances: $completedAdvances / $targetAdvances (elapsed=${durationMs}ms${if (cancelled) ", CANCELLED" else ""})")
        appendLine("Queue Ahead: min=$minQueueAhead, avg=${"%.2f".format(Locale.US, avgQueueAhead)}, max=$maxQueueAhead")
        appendLine("Stalls (ahead < ${RunwayPolicy.URGENT_BELOW} for >2s or exhausted advance): $stallCount (emptyAdvances=$emptyAdvanceCount)")
        appendLine("Refill Latency: p50=${refillLatencyP50Ms}ms, p95=${refillLatencyP95Ms}ms (refills=${funnelAverages.refillCount})")
        appendLine()
        appendLine("--- FUNNEL AVERAGES (${funnelAverages.refillCount} refills) ---")
        appendLine(
            "avg fetched=${"%.1f".format(Locale.US, funnelAverages.avgFetched)} -> " +
                "elig=${"%.1f".format(Locale.US, funnelAverages.avgAfterEligibility)} -> " +
                "dedupe=${"%.1f".format(Locale.US, funnelAverages.avgAfterIdentityDedupe)} -> " +
                "dismiss=${"%.1f".format(Locale.US, funnelAverages.avgAfterDismissed)} -> " +
                "lang=${"%.1f".format(Locale.US, funnelAverages.avgAfterLanguageGate)} -> " +
                "genre=${"%.1f".format(Locale.US, funnelAverages.avgAfterGenreTypeGate)} -> " +
                "purity=${"%.1f".format(Locale.US, funnelAverages.avgAfterArtistPurity)} -> " +
                "acc=${"%.1f".format(Locale.US, funnelAverages.avgAccepted)} -> " +
                "app=${"%.1f".format(Locale.US, funnelAverages.avgAppended)}"
        )
        appendLine(
            "libOrInstantAvgPct=${"%.1f".format(Locale.US, funnelAverages.avgLibOrInstantPct)}% | " +
                "steppedAnchors=${funnelAverages.steppedAnchorRefills} | widenedRetries=${funnelAverages.widenedRetryCount}"
        )
        appendLine()
        appendLine("--- EMPTY / EXHAUSTED DECISIONS (${emptyOrExhaustedDecisions.size}) ---")
        if (emptyOrExhaustedDecisions.isEmpty()) {
            appendLine("(none — 0 empty/exhausted decisions)")
        } else {
            emptyOrExhaustedDecisions.forEachIndexed { idx, e ->
                appendLine("${idx + 1}. step=${e.step} refill#${e.refillNumber} status=${e.status} reason=${e.reason} anchor=${e.anchorId} (${e.anchorTitle})")
            }
        }
        if (stallDetails.isNotEmpty()) {
            appendLine()
            appendLine("--- STALL DETAILS (${stallDetails.size}) ---")
            stallDetails.forEachIndexed { idx, msg ->
                appendLine("${idx + 1}. $msg")
            }
        }
    }
}

data class SoakTestState(
    val isRunning: Boolean = false,
    val selectedLanguage: String = "Hindi",
    val currentStep: Int = 0,
    val targetSteps: Int = 60,
    val currentAhead: Int = 0,
    val stallCount: Int = 0,
    val statusLine: String = "Idle",
    val result: SoakTestResult? = null,
    val errorMessage: String? = null,
)

/**
 * Developer Options soak runner (F4): advances an isolated QueueManager 60 times using the real
 * recommendation engine and network sources without ExoPlayer audio playback.
 */
object AutoplaySoakTester {
    const val DEFAULT_TARGET_ADVANCES = 60
    const val STALL_DURATION_THRESHOLD_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(SoakTestState())
    val state: StateFlow<SoakTestState> = _state.asStateFlow()

    fun selectLanguage(languageName: String) {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(selectedLanguage = languageName, errorMessage = null)
    }

    fun startForLanguage(languageName: String, targetAdvances: Int = DEFAULT_TARGET_ADVANCES) {
        if (_state.value.isRunning) return
        activeJob = scope.launch {
            try {
                runSoakTest(
                    languageName = languageName,
                    targetAdvances = targetAdvances,
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    statusLine = "Cancelled",
                )
            } catch (e: Exception) {
                DiagnosticsLogger.e("AutoplaySoak", "Soak test failed for $languageName", e)
                _state.value = _state.value.copy(
                    isRunning = false,
                    statusLine = "Failed: ${e.message}",
                    errorMessage = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        _state.value = _state.value.copy(isRunning = false, statusLine = "Cancelled by user")
    }

    suspend fun runSoakTest(
        languageName: String,
        targetAdvances: Int = DEFAULT_TARGET_ADVANCES,
        engine: RecommendationEngine? = null,
        featureRepository: TrackFeatureRepository? = null,
        userProfileRepository: UserProfileRepository? = null,
        artistLanguageMapProvider: () -> Map<String, ArtistLanguageProfile> = { ArtistLanguageRegistry.snapshot() },
        seedSongProvider: (suspend (languageName: String, isoCode: String) -> Song?)? = null,
        clock: () -> Long = System::currentTimeMillis,
        warmUpEnabled: Boolean = true,
        minAheadStallThreshold: Int = RunwayPolicy.URGENT_BELOW,
        stallDurationThresholdMs: Long = STALL_DURATION_THRESHOLD_MS,
    ): SoakTestResult {
        val isoCode = LanguageLocaleConfig.mapLanguageToIso(languageName) ?: "hi"
        val resolvedEngine = engine ?: VelvetApp.recommendationEngine
        val resolvedFeatures = featureRepository ?: runCatching { VelvetApp.trackFeatureRepository }.getOrElse { TrackFeatureRepository() }
        val resolvedProfiles = userProfileRepository ?: runCatching { VelvetApp.userProfileRepository }.getOrNull()

        _state.value = SoakTestState(
            isRunning = true,
            selectedLanguage = languageName,
            currentStep = 0,
            targetSteps = targetAdvances,
            statusLine = "Resolving $languageName seed song...",
        )

        val seedSong = seedSongProvider?.invoke(languageName, isoCode)
            ?: resolveNetworkOrCatalogSeed(languageName, isoCode, resolvedFeatures)
            ?: error("Could not find a verified studio seed track for $languageName")

        val startedMs = clock()
        val capturedFunnels = mutableListOf<RefillFunnelRecord>()
        val emptyDecisions = mutableListOf<SoakEmptyDecision>()
        val refillLatencies = mutableListOf<Long>()
        val stallDetails = mutableListOf<String>()
        val aheadSamples = mutableListOf<Int>()

        val queue = QueueManager(syncWithDismissedRegistry = false)
        queue.start(listOf(seedSong), startIndex = 0, shuffled = false)
        val localPool = RollingCandidatePool()
        val coordinator = AutoplayRefillCoordinator(
            engine = resolvedEngine,
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 0L,
            ),
            clock = clock,
            featureRepository = resolvedFeatures,
            rollingPool = localPool,
            userProfileRepository = resolvedProfiles,
            onFunnelRecorded = { capturedFunnels += it },
        )

        var stallCount = 0
        var emptyAdvanceCount = 0
        var refillCounter = 0
        var lowRunwaySinceMs: Long? = null
        var minAheadObserved = Int.MAX_VALUE
        var maxAheadObserved = 0
        val artistLangMap = artistLanguageMapProvider()

        fun buildSnapshot(): QueueRefillSnapshot = QueueRefillSnapshot(
            currentSong = queue.current ?: seedSong,
            upcomingSongs = queue.upNext.map { it.song },
            userQueueSongs = queue.userQueue.map { it.song },
            historySongs = queue.history,
            queueVersion = queue.queueVersion,
            isAutoplayEnabled = true,
            preferredLanguageIds = setOf(isoCode),
            artistLanguageMap = artistLangMap,
        )

        fun updateLowRunwayWindow(currentAhead: Int, nowMs: Long) {
            if (currentAhead < minAheadStallThreshold) {
                if (lowRunwaySinceMs == null) {
                    lowRunwaySinceMs = nowMs
                }
            } else {
                val since = lowRunwaySinceMs
                if (since != null && (nowMs - since) > stallDurationThresholdMs) {
                    stallCount++
                    stallDetails += "low_runway(<$minAheadStallThreshold) lasted ${nowMs - since}ms"
                }
                lowRunwaySinceMs = null
            }
        }

        suspend fun executeRefill(step: Int, force: Boolean, autoPlayNext: Boolean) {
            val aheadBefore = queue.upNext.size
            refillCounter++
            val t0 = clock()
            val decision = coordinator.requestRefill(
                initialSnapshot = buildSnapshot(),
                latestSnapshotProvider = ::buildSnapshot,
                seedId = queue.current?.id,
                autoPlayNext = autoPlayNext,
                force = force,
            )
            val t1 = clock()
            val elapsed = (t1 - t0).coerceAtLeast(0L)
            refillLatencies += elapsed

            when (decision) {
                is RefillDecision.AppendSongs -> {
                    val existingIds = buildSet {
                        queue.current?.id?.let(::add)
                        queue.upNext.forEach { add(it.song.id) }
                        queue.history.forEach { add(it.id) }
                    }
                    val toAppend = decision.songs.filter { it.id !in existingIds }
                    if (toAppend.isNotEmpty()) {
                        queue.appendAutoplay(toAppend)
                    }
                }
                is RefillDecision.SafeEmpty -> {
                    emptyDecisions += SoakEmptyDecision(
                        step = step,
                        refillNumber = refillCounter,
                        anchorId = queue.current?.id ?: seedSong.id,
                        anchorTitle = queue.current?.title ?: seedSong.title,
                        status = decision.status.name,
                        reason = decision.reason,
                        timestampMs = t1,
                    )
                }
                is RefillDecision.Skip -> {
                    if (decision.reason == "anchor_exhausted") {
                        emptyDecisions += SoakEmptyDecision(
                            step = step,
                            refillNumber = refillCounter,
                            anchorId = queue.current?.id ?: seedSong.id,
                            anchorTitle = queue.current?.title ?: seedSong.title,
                            status = "EXHAUSTED",
                            reason = decision.reason,
                            timestampMs = t1,
                        )
                    }
                }
            }

            val aheadAfter = queue.upNext.size
            if (aheadBefore < minAheadStallThreshold && elapsed > stallDurationThresholdMs) {
                stallCount++
                stallDetails += "step=$step refill_wait=${elapsed}ms (aheadBefore=$aheadBefore -> $aheadAfter)"
                lowRunwaySinceMs = null
            } else {
                updateLowRunwayWindow(aheadAfter, t1)
            }
        }

        // Step 0: initial seed fill + rolling warm-up
        executeRefill(step = 0, force = true, autoPlayNext = false)
        if (warmUpEnabled && localPool.size() < 30) {
            runCatching { coordinator.ensureRollingPoolWarm(buildSnapshot(), targetPoolSize = 40) }
        }
        val initialAhead = queue.upNext.size
        minAheadObserved = minOf(minAheadObserved, initialAhead)
        maxAheadObserved = maxOf(maxAheadObserved, initialAhead)

        var completedAdvances = 0
        var cancelled = false
        try {
            for (step in 1..targetAdvances) {
                currentCoroutineContext().ensureActive()
                if (queue.upNext.isEmpty()) {
                    executeRefill(step = step, force = true, autoPlayNext = true)
                }
                val advanced = queue.advance(manual = false)
                if (advanced == null) {
                    emptyAdvanceCount++
                    stallCount++
                    stallDetails += "step=$step queue_empty_on_advance"
                } else {
                    completedAdvances++
                }
                val aheadAfterAdvance = queue.upNext.size
                minAheadObserved = minOf(minAheadObserved, aheadAfterAdvance)
                maxAheadObserved = maxOf(maxAheadObserved, aheadAfterAdvance)
                updateLowRunwayWindow(aheadAfterAdvance, clock())

                if (queue.upNext.size < RunwayPolicy.REFILL_BELOW) {
                    executeRefill(step = step, force = false, autoPlayNext = false)
                }
                if (warmUpEnabled && localPool.size() < 25) {
                    runCatching { coordinator.ensureRollingPoolWarm(buildSnapshot(), targetPoolSize = 40) }
                }

                val stepAhead = queue.upNext.size
                aheadSamples += stepAhead
                minAheadObserved = minOf(minAheadObserved, stepAhead)
                maxAheadObserved = maxOf(maxAheadObserved, stepAhead)

                _state.value = _state.value.copy(
                    currentStep = step,
                    currentAhead = stepAhead,
                    stallCount = stallCount,
                    statusLine = "Step $step/$targetAdvances • ahead=$stepAhead • stalls=$stallCount",
                )
            }
        } catch (ce: CancellationException) {
            cancelled = true
            throw ce
        } finally {
            val openStallSince = lowRunwaySinceMs
            if (openStallSince != null && (clock() - openStallSince) > stallDurationThresholdMs) {
                stallCount++
                stallDetails += "trailing_low_runway=${clock() - openStallSince}ms"
            }
        }

        val sortedLat = refillLatencies.sorted()
        val p50 = if (sortedLat.isEmpty()) 0L else sortedLat[sortedLat.size / 2]
        val p95 = if (sortedLat.isEmpty()) 0L else sortedLat[((sortedLat.size - 1) * 95) / 100]
        val minAheadFinal = if (minAheadObserved == Int.MAX_VALUE) 0 else minAheadObserved
        val avgAhead = if (aheadSamples.isEmpty()) 0.0 else aheadSamples.average()
        val funnelAvgs = computeFunnelAverages(capturedFunnels)
        val totalDurationMs = (clock() - startedMs).coerceAtLeast(0L)

        val result = SoakTestResult(
            languageName = languageName,
            languageIso = isoCode,
            seedSong = seedSong,
            targetAdvances = targetAdvances,
            completedAdvances = completedAdvances,
            durationMs = totalDurationMs,
            minQueueAhead = minAheadFinal,
            avgQueueAhead = avgAhead,
            maxQueueAhead = maxAheadObserved,
            stallCount = stallCount,
            emptyAdvanceCount = emptyAdvanceCount,
            refillLatencyP50Ms = p50,
            refillLatencyP95Ms = p95,
            funnelAverages = funnelAvgs,
            emptyOrExhaustedDecisions = emptyDecisions.toList(),
            stallDetails = stallDetails.toList(),
            cancelled = cancelled,
        )
        DiagnosticsLogger.i(
            "AutoplaySoak",
            "Completed $completedAdvances/$targetAdvances steps lang=$languageName minAhead=$minAheadFinal avgAhead=${"%.2f".format(Locale.US, avgAhead)} stalls=$stallCount p50=${p50}ms p95=${p95}ms empties=${emptyDecisions.size}",
        )
        _state.value = _state.value.copy(
            isRunning = false,
            currentStep = completedAdvances,
            currentAhead = queue.upNext.size,
            stallCount = stallCount,
            statusLine = "Done: $completedAdvances/$targetAdvances steps • stalls=$stallCount",
            result = result,
        )
        return result
    }

    internal fun computeFunnelAverages(funnels: List<RefillFunnelRecord>): SoakFunnelAverages {
        if (funnels.isEmpty()) {
            return SoakFunnelAverages(
                refillCount = 0,
                avgFetched = 0.0,
                avgAfterEligibility = 0.0,
                avgAfterIdentityDedupe = 0.0,
                avgAfterDismissed = 0.0,
                avgAfterLanguageGate = 0.0,
                avgAfterGenreTypeGate = 0.0,
                avgAfterArtistPurity = 0.0,
                avgAccepted = 0.0,
                avgAppended = 0.0,
                avgLibOrInstantPct = 0.0,
                steppedAnchorRefills = 0,
                widenedRetryCount = 0,
            )
        }
        val n = funnels.size.toDouble()
        val libPcts = funnels.map { rec ->
            val acc = rec.totalFunnel.accepted
            if (acc > 0) (rec.libraryOrInstantAcceptedCount * 100.0) / acc else 0.0
        }
        return SoakFunnelAverages(
            refillCount = funnels.size,
            avgFetched = funnels.sumOf { it.totalFunnel.fetched } / n,
            avgAfterEligibility = funnels.sumOf { it.totalFunnel.afterEligibility } / n,
            avgAfterIdentityDedupe = funnels.sumOf { it.totalFunnel.afterIdentityDedupe } / n,
            avgAfterDismissed = funnels.sumOf { it.totalFunnel.afterDismissedFilter } / n,
            avgAfterLanguageGate = funnels.sumOf { it.totalFunnel.afterLanguageGate } / n,
            avgAfterGenreTypeGate = funnels.sumOf { it.totalFunnel.afterGenreTypeGate } / n,
            avgAfterArtistPurity = funnels.sumOf { it.totalFunnel.afterArtistPurity } / n,
            avgAccepted = funnels.sumOf { it.totalFunnel.accepted } / n,
            avgAppended = funnels.sumOf { it.totalFunnel.appended } / n,
            avgLibOrInstantPct = libPcts.average(),
            steppedAnchorRefills = funnels.count { it.wasSteppedAnchor },
            widenedRetryCount = funnels.count { it.widenedRetryTriggered },
        )
    }

    private suspend fun resolveNetworkOrCatalogSeed(
        languageName: String,
        isoCode: String,
        featureRepository: TrackFeatureRepository,
    ): Song? {
        val spec = LanguageLocaleConfig.specForName(languageName)
        val queries = spec?.preHintQueries?.ifEmpty { listOf("$languageName hit songs") }
            ?: listOf("$languageName hit songs")
        for (q in queries.take(2)) {
            val ytMusic = runCatching { YouTubeMusicInnertube.searchSongs(q) }.getOrDefault(emptyList())
                .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
            val matchedMusic = ytMusic.firstOrNull { song ->
                isoCode in featureRepository.getCachedOrExtract(song).languageIds.keys
            } ?: ytMusic.firstOrNull()
            if (matchedMusic != null) return matchedMusic

            val yt = runCatching { YouTube.searchAll(q).songs }.getOrDefault(emptyList())
                .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
            if (yt.isNotEmpty()) return yt.first()
        }
        val localPool = (InstantTrackCatalog.snapshot() +
            runCatching { VelvetApp.library.data.value.liked + VelvetApp.library.data.value.recent }.getOrDefault(emptyList()))
            .distinctBy { it.id }
            .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
        return localPool.firstOrNull { isoCode in featureRepository.getCachedOrExtract(it).languageIds.keys }
            ?: localPool.firstOrNull()
    }
}
