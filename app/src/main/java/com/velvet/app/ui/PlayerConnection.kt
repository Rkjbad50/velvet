package com.velvet.app.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.velvet.app.VelvetApp
import com.velvet.app.data.BrowseItem
import com.velvet.app.data.PlaybackContext
import com.velvet.app.data.RecentType
import com.velvet.app.data.Song
import com.velvet.app.recommendation.engine.ExoSyncSerializer
import com.velvet.app.recommendation.engine.NextTapCoalescer
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.engine.RunwayTrigger
import com.velvet.app.recommendation.model.PlaybackAttribution
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationSurface
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.YouTube
import com.velvet.app.data.isDuplicateOf
import com.velvet.app.data.isOriginalMusicAudio
import com.velvet.app.data.isPureMusic
import com.velvet.app.data.isSameTrackAs
import com.velvet.app.playback.DismissedTrackRegistry
import com.velvet.app.playback.PersistedPlaybackState
import com.velvet.app.playback.PlaybackCheckpointScheduler
import com.velvet.app.playback.PlaybackService
import com.velvet.app.playback.PlaybackStateManager
import com.velvet.app.playback.PlaylistStart
import com.velvet.app.playback.QueueExoAdapter
import com.velvet.app.playback.QueueItem
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.QueueState
import com.velvet.app.playback.RepeatMode
import com.velvet.app.playback.ShuffleMode
import com.velvet.app.playback.toMediaItem
import com.velvet.app.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The UI's handle on PlaybackService, mirrored into Compose state. */
object PlayerConnection {
    val queueManager = QueueManager()

    var controller by mutableStateOf<MediaController?>(null)
        private set
    var current by mutableStateOf<Song?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var savedInitialPositionMs by mutableLongStateOf(0L)
        private set
    private var optimisticPlayState: Boolean? = null
    private var optimisticPlayUntilMs: Long = 0L
    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekUntilMs: Long = 0L
    private fun monoNowMs(): Long = System.nanoTime() / 1_000_000L
    var error by mutableStateOf<String?>(null)
    var brokenStreamingBanner by mutableStateOf<String?>(null)
        private set

    fun showStreamingBrokenBanner(message: String) {
        brokenStreamingBanner = message
    }

    fun dismissStreamingBrokenBanner() {
        brokenStreamingBanner = null
    }
    var currentPlaylistId by mutableStateOf<String?>(null)
        private set
    var isRadioActive by mutableStateOf(false)
        private set
    var isArtistRadioActive by mutableStateOf(false)
        private set
    var radioSeedSong by mutableStateOf<Song?>(null)
        private set
    var activeRadioSessionId by mutableStateOf<String?>(null)
        private set
    var radioBannerState by mutableStateOf<RadioBannerState?>(null)
        private set

    fun triggerRadioBanner(title: String) {
        radioBannerState = RadioBannerState(title = title, token = System.currentTimeMillis())
    }

    fun dismissRadioBanner() {
        radioBannerState = null
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val runwayBgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val runwayMainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exoSyncSerializer = ExoSyncSerializer(runwayMainScope)
    private val nextTapCoalescer = NextTapCoalescer(
        scope = runwayMainScope,
        delayMs = RunwayPolicy.NEXT_COALESCE_WINDOW_MS,
    ) {
        performNextAdvance()
    }
    @Volatile
    private var restoringPlaybackState = false
    private val checkpointScheduler = PlaybackCheckpointScheduler(
        scope = runwayBgScope,
        debounceMs = 300L,
        periodicIntervalMs = 15_000L,
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
        snapshotProvider = { buildPersistedStateOrNull() },
        onPersist = { state ->
            appContext?.let { ctx -> PlaybackStateManager.saveToDirectory(ctx.filesDir, state) }
        },
    )

    /**
     * Whether playback originated from a user-created local playlist.
     * Smart Shuffle is strictly restricted to user-created playlists only.
     */
    val isUserPlaylistActive: Boolean
        get() = currentPlaylistId != null && VelvetApp.library.data.value.playlists.any { it.id == currentPlaylistId }

    /**
     * Returns true if the session belonging to [playlistId] is currently active in the queue.
     */
    fun isPlaylistSessionActive(playlistId: String?): Boolean {
        if (playlistId.isNullOrBlank()) return false
        return currentPlaylistId == playlistId && (current != null || queueManager.sourceList.isNotEmpty())
    }

    /**
     * Returns true if the session belonging to [playlistId] is actively playing audio.
     */
    fun isPlaylistSessionPlaying(playlistId: String?): Boolean {
        return isPlaylistSessionActive(playlistId) && isPlaying
    }

    /**
     * Returns true if the session belonging to [playlistId] is currently paused.
     */
    fun isPlaylistSessionPaused(playlistId: String?): Boolean {
        return isPlaylistSessionActive(playlistId) && !isPlaying && current != null
    }

    /**
     * Authoritative session-aware Play/Pause transport control for collection/playlist headers.
     * - Case B: Same playlist actively playing -> Pause playback without touching queue or position.
     * - Case C: Same playlist is paused -> Resume existing session exactly where paused without restarting!
     * - Case A/D/E: Different playlist, ended session, or new playback -> Start fresh sequential session.
     */
    fun playOrResumePlaylist(
        playlistId: String,
        songs: List<Song>,
        context: PlaybackContext? = null,
    ) {
        if (songs.isEmpty()) return

        if (isPlaylistSessionPlaying(playlistId)) {
            togglePlay()
        } else if (isPlaylistSessionPaused(playlistId)) {
            val currentSong = current
            if (currentSong != null && (songs.any { it.id == currentSong.id } || currentPlaylistId == playlistId)) {
                togglePlay()
            } else {
                play(songs, startIndex = 0, shuffled = false, playlistId = playlistId, context = context)
            }
        } else {
            play(songs, startIndex = 0, shuffled = false, playlistId = playlistId, context = context)
        }
    }

    /**
     * Authoritative session-aware Shuffle Play control for collection/playlist headers.
     * - If same playlist is already active:
     *   - If shuffle is OFF: enables shuffle mode without interrupting current track, and resumes if paused.
     *   - If shuffle is ALREADY ON: generates a fresh randomized reshuffle of remaining eligible tracks
     *     and displays visual confirmation ("Reshuffled queue 🔀"), resuming if paused.
     * - Otherwise starts a new shuffled playback session starting from a randomized track.
     */
    fun shufflePlayOrToggle(
        playlistId: String,
        songs: List<Song>,
        context: PlaybackContext? = null,
    ) {
        if (songs.isEmpty()) return

        if (isPlaylistSessionActive(playlistId)) {
            val currentSong = current
            if (currentSong != null && (songs.any { it.id == currentSong.id } || currentPlaylistId == playlistId)) {
                val allowSmart = isUserPlaylistActive
                if (queueManager.shuffleMode == ShuffleMode.OFF) {
                    queueManager.setShuffleMode(ShuffleMode.STANDARD, allowSmartShuffle = allowSmart)
                    VelvetApp.library.setPersistedShuffleMode("STANDARD")
                    syncQueueState()
                    syncToExoPlayer()
                    showFirstTimeShuffleToast(ShuffleMode.STANDARD, context = null)
                } else {
                    // Running playlist already has shuffle on: reshuffle without repeating intrusive toasts
                    val activeMode = queueManager.shuffleMode
                    queueManager.setShuffleMode(activeMode, allowSmartShuffle = allowSmart)
                    syncQueueState()
                    syncToExoPlayer()
                }
                if (!isPlaying) {
                    togglePlay()
                }
                return
            }
        }

        val startIndex = (0 until songs.size).random()
        play(songs, startIndex = startIndex, shuffled = true, playlistId = playlistId, context = context)
    }

    /** Pure single source of truth queue state. */
    var queueState by mutableStateOf(queueManager.state)
        private set

    var isQueueLocked by mutableStateOf(false)

    val userQueue: List<QueueItem> get() = queueState.userQueue
    val upNext: List<QueueItem> get() = queueState.upNext
    val history: List<QueueItem> get() = queueState.history

    /** Whether shuffle is currently on. */
    var shuffle by mutableStateOf(false)
        private set
    var shuffleMode by mutableStateOf(ShuffleMode.OFF)
        private set

    /**
     * What the queue sheet shows: current song first (highlighted), then
     * user-queued items, then up-next. History is NEVER shown here.
     */
    var queue by mutableStateOf<List<QueueItem>>(emptyList())
        private set

    /** Always 0: the current item is always the first element of [queue]. */
    val index: Int get() = 0

    private fun syncQueueState(resetPositionOnTrackChange: Boolean = true) {
        val previousId = current?.id
        queueState = queueManager.state
        current = queueManager.current
        shuffle = queueManager.shuffle
        shuffleMode = queueManager.shuffleMode
        queue = queueManager.visibleQueue
        if (resetPositionOnTrackChange && current?.id != previousId) {
            savedInitialPositionMs = 0L
            pendingSeekTargetMs = null
            pendingSeekUntilMs = 0L
            durationMs = (current?.durationSec?.times(1000L)?.coerceAtLeast(0L) ?: 0L)
        }
        val langIds = runCatching {
            mapLanguagesToIso(VelvetApp.library.data.value.musicLanguages)
        }.getOrDefault(emptySet())
        val featRepo = runCatching { VelvetApp.trackFeatureRepository }.getOrNull()
        com.velvet.app.diagnostics.QueueHealthMonitor.recordQueueState(
            aheadCount = queueManager.upNext.size,
            userQueueCount = queueManager.userQueue.size,
            anchor = current,
            requestedLanguages = langIds,
            featureRepository = featRepo,
        )
        if (!restoringPlaybackState && current != null) {
            checkpointScheduler.scheduleDebounced()
        }
    }

    private fun syncToExoPlayer(
        playlistStart: PlaylistStart? = null,
        isRadio: Boolean = isRadioActive,
        autoPlay: Boolean = true,
        startPositionMs: Long = 0L,
    ) {
        exoSyncSerializer.submit {
            withController { c ->
                QueueExoAdapter.syncToExoPlayer(c, queueManager.state, playlistStart, isRadio, autoPlay, startPositionMs)
            }
        }
    }

    private fun persistedShuffleEnum(): ShuffleMode = when (VelvetApp.library.data.value.persistedShuffleMode) {
        "STANDARD" -> ShuffleMode.STANDARD
        "SMART" -> ShuffleMode.SMART
        else -> ShuffleMode.OFF
    }

    private fun showFirstTimeShuffleToast(mode: ShuffleMode, context: Context?) {
        val ctx = context ?: appContext ?: return
        val libData = VelvetApp.library.data.value
        when (mode) {
            ShuffleMode.STANDARD -> {
                if (!libData.shuffleToastShownOnce) {
                    VelvetApp.library.markShuffleToastShown()
                    runCatching { Toast.makeText(ctx, "Shuffle is on", Toast.LENGTH_SHORT).show() }
                }
            }
            ShuffleMode.SMART -> {
                if (!libData.smartShuffleToastShownOnce) {
                    VelvetApp.library.markSmartShuffleToastShown()
                    runCatching {
                        Toast.makeText(ctx, "Smart Shuffle is on ✨ (Mixing in fresh matching songs)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ShuffleMode.OFF -> Unit
        }
    }

    // ── Connection plumbing ──────────────────────────────────────────────────
    private var appContext: Context? = null
    private var started = false
    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    fun connect(context: Context) {
        appContext = context.applicationContext
        started = true
        VelvetApp.runwayControllerOrNull()?.startPeriodicTick(isPlayingProvider = { isPlaying }) {
            requestRunwayRefill(RunwayTrigger.PERIODIC_TICK)
        }
        checkpointScheduler.startPeriodic { isPlaying }
        if (current == null) {
            val savedShuffle = persistedShuffleEnum()
            shuffleMode = savedShuffle
            shuffle = savedShuffle != ShuffleMode.OFF
            restoreSavedPlaybackState(null)
        }
        ensureConnected()
    }

    fun disconnect() {
        started = false
        nextTapCoalescer.cancel()
        checkpointScheduler.flushNow()
        checkpointScheduler.stop()
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    private fun ensureConnected() {
        val context = appContext ?: return
        if (!started || future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlayerConnection.controller === controller) {
                        this@PlayerConnection.controller = null
                        future = null
                        ensureConnected()
                    }
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            if (future !== f) { MediaController.releaseFuture(f); return@addListener }
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) { future = null; error = "Couldn't connect to the player"; return@addListener }
            c.addListener(listener)
            controller = c
            syncBasicState(c)
            if (c.mediaItemCount == 0) {
                restoreSavedPlaybackState(c)
            }
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private var trackStartedAt: Long = System.currentTimeMillis()
    private var lastRecordedEventSongId: String? = null

    fun recordCurrentPlaybackEnd(endReason: com.velvet.app.data.ListenEndReason? = null) {
        val song = current ?: return
        if (song.id == lastRecordedEventSongId && endReason == null) return
        val posMs = controller?.currentPosition ?: savedInitialPositionMs
        val dur = if (durationMs > 0) durationMs else (song.durationSec.toLong() * 1000L)
        val pct = if (dur > 0) (posMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

        val reason = endReason ?: if (pct >= 0.85f || (dur > 0 && dur - posMs < 5000L)) {
            com.velvet.app.data.ListenEndReason.COMPLETED
        } else if (posMs < 30_000L) {
            com.velvet.app.data.ListenEndReason.SKIP_EARLY
        } else {
            com.velvet.app.data.ListenEndReason.SKIP_LATE
        }

        val ctx = when {
            isRadioActive -> com.velvet.app.data.ListenContext.AUTOPLAY
            currentPlaylistId != null -> com.velvet.app.data.ListenContext.PLAYLIST
            else -> com.velvet.app.data.ListenContext.QUEUE
        }
        val isLiked = VelvetApp.library.isLiked(song.id)
        val songGroupId = com.velvet.app.playback.SongIdentity.canonicalGroupId(song)
        val parsed = com.velvet.app.playback.SongIdentity.normalizeTitle(song.title)
        val artists = com.velvet.app.playback.SongIdentity.normalizeArtists(song.artist).toList()

        val event = com.velvet.app.data.ListenEvent(
            songGroupId = songGroupId,
            videoId = song.id,
            artistSet = artists,
            movie = parsed.movie ?: song.album,
            year = null,
            language = null,
            startedAt = trackStartedAt,
            playedMs = posMs,
            durationMs = dur,
            completionPct = pct,
            endReason = reason,
            context = ctx,
            liked = isLiked,
        )
        VelvetApp.library.recordListenEvent(event)
        if (isRadioActive) {
            when (reason) {
                com.velvet.app.data.ListenEndReason.COMPLETED,
                com.velvet.app.data.ListenEndReason.REPLAY -> VelvetApp.radioSessionManagerOrNull()?.onTrackCompletedOrLiked(song)
                com.velvet.app.data.ListenEndReason.SKIP_EARLY -> VelvetApp.radioSessionManagerOrNull()?.onTrackSkippedEarly(song)
                else -> Unit
            }
        }
        lastRecordedEventSongId = song.id
        trackStartedAt = System.currentTimeMillis()
    }

    var isAutoplayLoading by mutableStateOf(false)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncBasicState(player)
        override fun onPlayerError(error: PlaybackException) { this@PlayerConnection.error = error.message }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED &&
                (VelvetApp.library.data.value.autoplay || isRadioActive) &&
                queueManager.repeatMode != RepeatMode.ONE &&
                queueManager.userQueue.isEmpty() &&
                queueManager.upNext.isEmpty()
            ) {
                val seed = current ?: queueManager.current ?: queueManager.history.lastOrNull()
                if (seed != null) {
                    val instantAdded = takePreparedInstantSeed(seed)
                    if (instantAdded) {
                        recordCurrentPlaybackEnd(endReason = com.velvet.app.data.ListenEndReason.COMPLETED)
                        val adv = queueManager.advance(manual = false)
                        if (adv != null) {
                            syncQueueState()
                            syncToExoPlayer()
                            requestRunwayRefill(RunwayTrigger.TRACK_CHANGE)
                            return
                        }
                    }
                    requestRunwayRefill(
                        trigger = RunwayTrigger.TRACK_CHANGE,
                        seedId = seed.id,
                        autoPlayNext = true,
                        force = true,
                    )
                }
            }
        }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // When ExoPlayer auto-advances (track finished naturally), pop from our queue
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                val endReason = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    com.velvet.app.data.ListenEndReason.REPLAY
                } else {
                    com.velvet.app.data.ListenEndReason.COMPLETED
                }
                val completedOutgoing = current ?: queueManager.current
                recordCurrentPlaybackEnd(endReason = endReason)
                queueManager.advance(manual = false)
                syncQueueState()
                syncToExoPlayer()
                // Chained re-seeding: merge RDAMVM<completedTrack> into rolling pool & instant catalog off-main
                if (completedOutgoing != null && (VelvetApp.library.data.value.autoplay || isRadioActive)) {
                    runwayBgScope.launch {
                        val chained = runCatching {
                            StreamResolver.relatedWithContinuation(
                                videoId = completedOutgoing.id,
                                seedHint = completedOutgoing,
                                loadNextPage = false,
                            )
                        }.getOrDefault(emptyList())
                        if (chained.isNotEmpty()) {
                            runCatching {
                                VelvetApp.autoplayRefillCoordinator.mergeIntoRollingPool(chained)
                            }
                        }
                    }
                }
                requestRunwayRefill(RunwayTrigger.TRACK_CHANGE)
                if (queueManager.upNext.size < RunwayPolicy.REFILL_BELOW) {
                    (queueManager.upNext.lastOrNull()?.song ?: queueManager.current)?.let {
                        StreamResolver.prefetchRelated(it)
                    }
                }
            }
        }
    }

    /** Syncs basic playback state (play/pause/buffer/duration/repeat) from ExoPlayer. */
    private fun syncBasicState(p: Player) {
        val exoCurrent = p.currentMediaItem?.toSong()
        if (exoCurrent != null && exoCurrent.id != current?.id) {
            if (queueManager.current == null) {
                trackStartedAt = System.currentTimeMillis()
                lastRecordedEventSongId = null
                savedInitialPositionMs = 0L
                pendingSeekTargetMs = null
                pendingSeekUntilMs = 0L
                current = exoCurrent
                requestRunwayRefill(RunwayTrigger.TRACK_CHANGE)
            } else if (queueManager.current?.id != exoCurrent.id) {
                if (queueManager.userQueue.firstOrNull()?.song?.id == exoCurrent.id ||
                    queueManager.upNext.firstOrNull()?.song?.id == exoCurrent.id) {
                    trackStartedAt = System.currentTimeMillis()
                    lastRecordedEventSongId = null
                    queueManager.advance()
                    syncQueueState()
                    syncToExoPlayer()
                    requestRunwayRefill(RunwayTrigger.TRACK_CHANGE)
                }
                // Otherwise ignore stale pre-transition events while ExoPlayer catches up to queueManager.current
            }
            if (queueManager.upNext.size < RunwayPolicy.REFILL_BELOW) {
                StreamResolver.prefetchRelated(queueManager.upNext.lastOrNull()?.song ?: (queueManager.current ?: exoCurrent))
            }
        }

        val nowMs = monoNowMs()
        val exoPlayIntended = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        val activeOpt = optimisticPlayState?.takeIf { nowMs < optimisticPlayUntilMs }
        if (optimisticPlayState != null && (nowMs >= optimisticPlayUntilMs || exoPlayIntended == optimisticPlayState)) {
            optimisticPlayState = null
        }
        val effectiveOpt = if (optimisticPlayState != null && nowMs < optimisticPlayUntilMs) optimisticPlayState else activeOpt
        val displayedPlaying = PlaybackControlSync.resolveDisplayedPlaying(
            exoIsPlaying = p.isPlaying,
            exoPlayWhenReady = p.playWhenReady,
            exoPlaybackState = p.playbackState,
            stateEnded = Player.STATE_ENDED,
            stateBuffering = Player.STATE_BUFFERING,
            optimisticPlay = effectiveOpt,
        )
        playWhenReady = effectiveOpt ?: exoPlayIntended
        isPlaying = displayedPlaying
        isBuffering = PlaybackControlSync.resolveDisplayedBuffering(
            exoPlaybackState = p.playbackState,
            stateBuffering = Player.STATE_BUFFERING,
            displayedPlaying = displayedPlaying,
            exoIsPlaying = p.isPlaying,
        )

        val liveDur = p.duration
        if (liveDur > 0L) {
            durationMs = liveDur
        } else if (durationMs <= 0L) {
            durationMs = current?.durationSec?.times(1000L)?.coerceAtLeast(0L) ?: 0L
        }
        repeatMode = p.repeatMode

        val posSync = PlaybackControlSync.reconcilePositionMs(
            rawPosMs = p.currentPosition,
            pendingSeekMs = pendingSeekTargetMs,
            pendingUntilMs = pendingSeekUntilMs,
            nowMs = nowMs,
            savedPosMs = savedInitialPositionMs,
            hasMediaItems = p.mediaItemCount > 0,
            isPlayActive = p.isPlaying || exoPlayIntended,
        )
        if (posSync.clearPendingSeek) pendingSeekTargetMs = null
        savedInitialPositionMs = posSync.savedPositionMs
        if (p.isPlaying && exoCurrent != null && exoCurrent.id == current?.id && posSync.positionMs > 0L) {
            VelvetApp.eventRecorderOrNull()?.onPlaybackProgress(exoCurrent.id, posSync.positionMs, durationMs)
        }
        if (!p.isPlaying && !exoPlayIntended && posSync.positionMs > 0L) {
            persistState()
        }
    }

    private fun buildPersistedStateOrNull(): PersistedPlaybackState? {
        val cur = current ?: queueManager.current ?: return null
        val pos = controller?.currentPosition ?: savedInitialPositionMs
        return PersistedPlaybackState(
            song = cur,
            positionMs = pos.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            upNext = queueManager.upNext.map { it.song },
            userQueue = queueManager.userQueue.map { it.song },
            history = queueManager.history,
            sourceList = queueManager.sourceList,
            playlistId = currentPlaylistId,
            isRadio = isRadioActive,
            isArtistRadio = isArtistRadioActive,
            radioSeedSong = radioSeedSong,
            shuffleMode = queueManager.shuffleMode,
            repeatMode = queueManager.repeatMode,
        )
    }

    fun persistState() {
        if (appContext == null) return
        checkpointScheduler.flushNow()
    }

    fun restoreSavedPlaybackState(c: MediaController? = controller) {
        val context = appContext ?: return
        val saved = PlaybackStateManager.load(context)
            ?: VelvetApp.library.data.value.recent.firstOrNull()?.let {
                PersistedPlaybackState(
                    song = it,
                    durationMs = it.durationSec * 1000L,
                )
            } ?: return

        restoringPlaybackState = true
        try {
            val song = saved.song
            current = song
            currentPlaylistId = saved.playlistId
            isRadioActive = saved.isRadio
            isArtistRadioActive = saved.isRadio && saved.isArtistRadio
            radioSeedSong = saved.radioSeedSong ?: if (saved.isRadio) song else null
            savedInitialPositionMs = saved.positionMs
            durationMs = if (saved.durationMs > 0L) saved.durationMs else (song.durationSec * 1000L)

            val resolvedSavedShuffle = if (saved.shuffleMode != ShuffleMode.OFF) {
                saved.shuffleMode
            } else if (saved.upNext.isEmpty() && saved.sourceList.isEmpty()) {
                persistedShuffleEnum()
            } else {
                ShuffleMode.OFF
            }
            val effectiveSaved = saved.copy(shuffleMode = resolvedSavedShuffle)
            queueManager.restore(effectiveSaved, allowSmartShuffle = isUserPlaylistActive)
            syncQueueState(resetPositionOnTrackChange = false)

            if (c != null) {
                syncToExoPlayer(
                    playlistStart = null,
                    isRadio = saved.isRadio,
                    autoPlay = false,
                    startPositionMs = saved.positionMs,
                )
            }
            if (VelvetApp.library.data.value.autoplay || saved.isRadio) {
                requestRunwayRefill(RunwayTrigger.APP_START_OR_RESTORE, seedId = song.id)
            }
        } finally {
            restoringPlaybackState = false
        }
    }

    private fun withController(replaces: Boolean = false, action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) { action(c); return }
        if (replaces) pending.clear()
        pending.addLast(action)
        ensureConnected()
    }

    // ── Playback commands ────────────────────────────────────────────────────

    private fun currentRepeatLabel(): String = when (queueManager.repeatMode) {
        RepeatMode.ONE -> "ONE"
        RepeatMode.ALL -> "ALL"
        RepeatMode.OFF -> "OFF"
    }

    fun play(
        songs: List<Song>,
        startIndex: Int = 0,
        shuffled: Boolean = false,
        radio: Boolean = false,
        isArtistRadio: Boolean = false,
        playlistId: String? = null,
        context: PlaybackContext? = null,
        attribution: PlaybackAttribution = PlaybackAttribution(),
    ) {
        if (songs.isEmpty()) return
        val outgoingPos = controller?.currentPosition ?: savedInitialPositionMs
        val outgoingDur = durationMs
        if (current != null) {
            recordCurrentPlaybackEnd(endReason = null)
        }
        if (context != null) {
            VelvetApp.library.addContextRecent(context)
        }
        currentPlaylistId = playlistId
        isRadioActive = radio
        isArtistRadioActive = if (radio) isArtistRadio else false
        radioSeedSong = if (radio) (songs.getOrNull(startIndex) ?: songs.firstOrNull()) else null
        if (!radio) {
            activeRadioSessionId = null
            VelvetApp.radioSessionManagerOrNull()?.clearSession()
            dismissRadioBanner()
        } else {
            activeRadioSessionId = attribution.recommendationSessionId ?: activeRadioSessionId
        }
        val isUserPlaylist = playlistId != null && VelvetApp.library.data.value.playlists.any { it.id == playlistId }
        val start = playlistId?.let { PlaylistStart(it, UUID.randomUUID().toString()) }
        val savedShuffleMode = persistedShuffleEnum()
        val effectiveShuffled = shuffled || (!radio && songs.size > 1 && savedShuffleMode != ShuffleMode.OFF)
        if (shuffled) {
            VelvetApp.library.setPersistedShuffleMode("STANDARD")
        }

        com.velvet.app.playback.DismissedTrackRegistry.clear()
        queueManager.start(songs, startIndex, effectiveShuffled)
        // Restore persisted Smart Shuffle strictly on user-created playlists; keep Standard elsewhere without clobbering stored mode
        val shouldApplySmart = !shuffled && !radio && songs.size > 1 && savedShuffleMode == ShuffleMode.SMART && isUserPlaylist
        if (shouldApplySmart) {
            queueManager.setShuffleMode(ShuffleMode.SMART, allowSmartShuffle = true)
        } else if (!isUserPlaylist && queueManager.shuffleMode == ShuffleMode.SMART) {
            queueManager.setShuffleMode(if (effectiveShuffled) ShuffleMode.STANDARD else ShuffleMode.OFF, allowSmartShuffle = false)
        }

        val resolvedContextId = playlistId ?: context?.id
        val effectiveSurface = when {
            attribution.surface != RecommendationSurface.OTHER -> attribution.surface
            playlistId != null || context?.type == RecentType.LOCAL_PLAYLIST || context?.type == RecentType.ALBUM || context?.type == RecentType.REMOTE_PLAYLIST ->
                RecommendationSurface.LIBRARY
            radio -> if (isArtistRadio) RecommendationSurface.ARTIST_RADIO else RecommendationSurface.SONG_RADIO
            else -> RecommendationSurface.OTHER
        }
        val effectiveAttribution = attribution.copy(
            surface = effectiveSurface,
            playlistContextId = attribution.playlistContextId ?: resolvedContextId,
            mode = attribution.mode ?: if (radio) {
                if (isArtistRadio) RecommendationMode.ARTIST_RADIO else RecommendationMode.SONG_RADIO
            } else null,
        )
        queueManager.current?.let { activeTrack ->
            VelvetApp.eventRecorderOrNull()?.let { rec ->
                if (playlistId != null || context?.type == RecentType.LOCAL_PLAYLIST || context?.type == RecentType.REMOTE_PLAYLIST || context?.type == RecentType.ALBUM) {
                    rec.recordPlaylistStarted(resolvedContextId ?: activeTrack.id, effectiveSurface)
                }
                rec.onPlayRequested(
                    song = activeTrack,
                    attribution = effectiveAttribution,
                    isRadioSession = radio,
                    outgoingPositionMs = outgoingPos,
                    outgoingDurationMs = outgoingDur,
                    repeatModeLabel = currentRepeatLabel(),
                )
            }
        }

        runwayBgScope.launch {
            com.velvet.app.recommendation.candidates.InstantTrackCatalog.seed(songs)
        }
        val isAutoplayOn = VelvetApp.library.data.value.autoplay || radio
        if (isAutoplayOn && queueManager.upNext.isEmpty()) {
            queueManager.current?.let { seedTrack ->
                takePreparedInstantSeed(seedTrack)
            }
        }

        savedInitialPositionMs = 0L
        pendingSeekTargetMs = null
        pendingSeekUntilMs = 0L
        optimisticPlayState = true
        optimisticPlayUntilMs = monoNowMs() + 750L
        playWhenReady = true
        isPlaying = true

        syncQueueState()
        syncToExoPlayer(playlistStart = start, isRadio = radio)
        if (queueManager.shuffleMode == ShuffleMode.SMART && isUserPlaylist) {
            loadSmartShuffleRecommendations()
        }

        if (isAutoplayOn) {
            queueManager.current?.id?.let {
                requestRunwayRefill(RunwayTrigger.TRACK_CHANGE, seedId = it)
            }
            // Prefetch next upcoming track off main without competing on current anchor
            queueManager.upNext.firstOrNull()?.song?.let {
                StreamResolver.prefetchRelated(it)
            }
        }
    }

    /**
     * Cycle shuffle mode.
     * When playing from a user-created playlist: OFF -> STANDARD -> SMART -> OFF.
     * Everywhere else (standalone songs, search results, artist, albums): SMART is disabled,
     * so it cycles strictly between OFF <-> STANDARD (ON).
     */
    fun cycleShuffle(context: Context? = null) {
        val activeSong = queueManager.current ?: current
        if (activeSong == null) {
            (context ?: appContext)?.let {
                runCatching { Toast.makeText(it, "Nothing playing right now", Toast.LENGTH_SHORT).show() }
            }
            return
        }
        // Re-anchor QueueManager if UI/ExoPlayer has an active track but queueManager.current became null
        if (queueManager.current == null) {
            queueManager.start(listOf(activeSong), startIndex = 0, shuffled = false)
        }
        val allowSmart = isUserPlaylistActive && (queueManager.upNext.isNotEmpty() || queueManager.sourceList.size > 1)
        val newMode = queueManager.cycleShuffleMode(allowSmartShuffle = allowSmart)
        VelvetApp.library.setPersistedShuffleMode(newMode.name)
        syncQueueState()
        syncToExoPlayer()

        if (newMode != ShuffleMode.OFF && (VelvetApp.library.data.value.autoplay || isRadioActive)) {
            requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION, seedId = activeSong.id)
        }

        showFirstTimeShuffleToast(newMode, context)
        if (newMode == ShuffleMode.SMART) {
            loadSmartShuffleRecommendations()
        }
    }

    /**
     * Toggle shuffle. When turning ON: Fisher-Yates reshuffle of sourceList
     * excluding current song and userQueue items. Current track is NOT interrupted.
     * When turning OFF: restore linear order from sourceList after current position.
     */
    fun toggleShuffle() {
        cycleShuffle(appContext)
    }

    /**
     * Loads algorithmic smart recommendations that match the genre, tempo, era, and style
     * of the currently playing playlist, strictly restricted to user-created playlists only.
     */
    fun loadSmartShuffleRecommendations() {
        if (!isUserPlaylistActive) return
        val currentSong = current ?: return
        val seeds = (listOf(currentSong) + queueManager.sourceList.shuffled().take(3)).distinctBy { it.id }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = (
                    queueManager.sourceList +
                    queueManager.history +
                    listOfNotNull(queueManager.current) +
                    queueManager.userQueue.map { it.song }
                )
                val langIds = mapLanguagesToIso(VelvetApp.library.data.value.musicLanguages)
                val candidates = mutableListOf<Song>()
                for (seed in seeds.take(3)) {
                    val result = VelvetApp.recommendationEngine.nextSimilar(
                        seed = seed,
                        contextSnapshot = snapshot + candidates,
                        mode = RecommendationMode.AUTOPLAY_REFILL,
                        limit = 5,
                        queueSongs = queueManager.upNext.map { it.song },
                        userQueueSongs = queueManager.userQueue.map { it.song },
                        recentSongs = queueManager.history.takeLast(6),
                        requestedLanguageIds = langIds,
                    )
                    candidates.addAll(result.candidates.map { it.song })
                }

                val sorted = candidates
                    .filterNot { com.velvet.app.playback.DismissedTrackRegistry.isDismissed(it) }
                    .distinctBy { it.id }
                    .take(12)

                    withContext(Dispatchers.Main) {
                        if (queueManager.shuffleMode == ShuffleMode.SMART && sorted.isNotEmpty()) {
                            VelvetApp.eventRecorderOrNull()?.registerAutoplayCandidates(
                                requestId = UUID.randomUUID().toString(),
                                candidates = sorted,
                                mode = RecommendationMode.AUTOPLAY_REFILL,
                                sourceId = "smart_shuffle",
                            )
                            queueManager.injectSmartRecommendations(sorted)
                            syncQueueState()
                            syncToExoPlayer()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
        }
    }

    /** Add song to play immediately after current. */
    fun playNext(song: Song, surface: RecommendationSurface = RecommendationSurface.PLAYER_NEXT) {
        val isAlreadyQueued = (listOfNotNull(current) + queueManager.upNext.map { it.song } + queueManager.userQueue.map { it.song })
            .any { it.id == song.id || com.velvet.app.playback.SongIdentity.isStrictMatch(it, song) }
        if (isAlreadyQueued) {
            appContext?.let { Toast.makeText(it, "Already in your queue", Toast.LENGTH_SHORT).show() }
        }

        VelvetApp.sessionHistory.record(song)
        VelvetApp.eventRecorderOrNull()?.recordQueuePlayNext(song, surface)
        if (controller?.mediaItemCount == 0 || current == null) {
            play(listOf(song), attribution = PlaybackAttribution(surface = surface))
            return
        }
        queueManager.playNext(song)
        syncQueueState()
        syncToExoPlayer()
        requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
        if (!isAlreadyQueued) {
            appContext?.let { Toast.makeText(it, "\"${song.title}\" will play next", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Add song to end of queue. */
    fun enqueue(song: Song, surface: RecommendationSurface = RecommendationSurface.PLAYER_NEXT) {
        val isAlreadyQueued = (listOfNotNull(current) + queueManager.upNext.map { it.song } + queueManager.userQueue.map { it.song })
            .any { it.id == song.id || com.velvet.app.playback.SongIdentity.isStrictMatch(it, song) }
        if (isAlreadyQueued) {
            appContext?.let { Toast.makeText(it, "Already in your queue", Toast.LENGTH_SHORT).show() }
        }

        VelvetApp.sessionHistory.record(song)
        VelvetApp.eventRecorderOrNull()?.recordQueueAdded(song, surface)
        if (controller?.mediaItemCount == 0 || current == null) {
            play(listOf(song), attribution = PlaybackAttribution(surface = surface))
            return
        }
        val added = queueManager.enqueue(song)
        if (!added && !isAlreadyQueued) {
            appContext?.let { Toast.makeText(it, "\"${song.title}\" already in queue", Toast.LENGTH_SHORT).show() }
            return
        }
        syncQueueState()
        syncToExoPlayer()
        requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
        if (!isAlreadyQueued) {
            appContext?.let { Toast.makeText(it, "Added to queue", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Move a queue item (by qid) to the top of the upcoming list (play next). */
    fun moveToTop(qid: String) {
        if (isQueueLocked) return
        val moved = queueManager.moveToTop(qid)
        if (moved) {
            syncQueueState()
            syncToExoPlayer()
            requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
        }
    }

    /** Remove a queue item by qid. */
    fun removeItem(qid: String) {
        if (isQueueLocked) return
        val removingCurrent = (qid == "current" || qid == queueState.current?.qid)
        val outgoing = current
        val outgoingPos = controller?.currentPosition ?: savedInitialPositionMs
        val outgoingDur = durationMs
        val itemToDismiss = queueManager.upNext.find { it.qid == qid } ?: queueManager.userQueue.find { it.qid == qid }
        if (itemToDismiss != null) {
            com.velvet.app.playback.DismissedTrackRegistry.recordDismissal(itemToDismiss)
            VelvetApp.eventRecorderOrNull()?.recordQueueRemoved(
                song = itemToDismiss.song,
                wasModelRecommended = itemToDismiss.isSmartRecommended,
            )
        }
        val removed = queueManager.removeItem(qid)
        if (removed) {
            if (removingCurrent && outgoing != null) {
                VelvetApp.eventRecorderOrNull()?.onExplicitTrackAdvance(
                    outgoingSong = outgoing,
                    nextSong = queueManager.current,
                    positionMs = outgoingPos,
                    durationMs = outgoingDur,
                    repeatModeLabel = currentRepeatLabel(),
                    transitionReason = "QUEUE_REMOVE_CURRENT",
                )
            }
            syncQueueState()
            syncToExoPlayer()
            requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
        }
    }

    /**
     * Reorders an item identified by [qid] to [newPosition] within its own section (userQueue or upNext).
     * Respects [isQueueLocked].
     */
    fun reorder(qid: String, newPosition: Int): Boolean {
        if (isQueueLocked) return false
        val moved = queueManager.reorder(qid, newPosition, isLocked = isQueueLocked)
        if (moved) {
            syncQueueState()
            syncToExoPlayer()
            requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
        }
        return moved
    }

    /** Jump to a specific queue item by qid. */
    fun jumpToQid(qid: String) {
        val outgoing = current
        val outgoingPos = controller?.currentPosition ?: savedInitialPositionMs
        val outgoingDur = durationMs
        recordCurrentPlaybackEnd(endReason = null)
        val targetSong = queueManager.jumpToQid(qid) ?: return
        VelvetApp.eventRecorderOrNull()?.onExplicitTrackAdvance(
            outgoingSong = outgoing,
            nextSong = targetSong,
            positionMs = outgoingPos,
            durationMs = outgoingDur,
            repeatModeLabel = currentRepeatLabel(),
            transitionReason = "JUMP_TO_QID",
        )
        syncQueueState()
        syncToExoPlayer()
        requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
    }

    fun togglePlay() = withController { c ->
        val nowMs = monoNowMs()
        val activeOpt = optimisticPlayState?.takeIf { nowMs < optimisticPlayUntilMs }
        val shouldPause = PlaybackControlSync.shouldPauseOnToggle(
            optimisticPlay = activeOpt,
            playWhenReady = c.playWhenReady,
            playbackState = c.playbackState,
            isPlaying = c.isPlaying,
            stateEnded = Player.STATE_ENDED,
            stateIdle = Player.STATE_IDLE,
        )
        if (shouldPause) {
            optimisticPlayState = false
            optimisticPlayUntilMs = nowMs + 450L
            playWhenReady = false
            isPlaying = false
            isBuffering = false
            c.pause()
            persistState()
        } else {
            optimisticPlayState = true
            optimisticPlayUntilMs = nowMs + 650L
            playWhenReady = true
            isPlaying = true
            if (c.mediaItemCount == 0 && current != null) {
                syncToExoPlayer(autoPlay = true, startPositionMs = savedInitialPositionMs)
            } else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) {
                    c.seekToDefaultPosition()
                    savedInitialPositionMs = 0L
                    pendingSeekTargetMs = 0L
                    pendingSeekUntilMs = nowMs + 800L
                }
                c.play()
            }
        }
    }

    fun startRadio(song: Song, attribution: PlaybackAttribution = PlaybackAttribution()) {
        radioSeedSong = song
        isRadioActive = true
        isArtistRadioActive = false
        currentPlaylistId = null
        triggerRadioBanner(song.title)
        val radioSessionId = VelvetApp.eventRecorderOrNull()?.recordRadioStarted(
            seed = song,
            isArtistRadio = false,
            attribution = attribution,
        )
        activeRadioSessionId = radioSessionId
        VelvetApp.radioSessionManagerOrNull()?.startSession(
            anchor = song,
            mode = RecommendationMode.SONG_RADIO,
            sessionId = radioSessionId ?: UUID.randomUUID().toString(),
        )
        VelvetApp.recommendationEngineOrNull()?.invalidateCandidateCache(song.id)

        if (current?.id == song.id) {
            com.velvet.app.playback.DismissedTrackRegistry.clear()
            queueManager.clearUpcoming()
            syncQueueState()
            syncToExoPlayer(isRadio = true)
            requestRunwayRefill(RunwayTrigger.TRACK_CHANGE, seedId = song.id, force = true)
        } else {
            play(
                listOf(song),
                radio = true,
                isArtistRadio = false,
                attribution = attribution.copy(
                    mode = RecommendationMode.SONG_RADIO,
                    surface = if (attribution.surface != RecommendationSurface.OTHER) attribution.surface else RecommendationSurface.SONG_RADIO,
                    recommendationSessionId = radioSessionId ?: attribution.recommendationSessionId,
                ),
            )
        }
    }

    fun startArtistRadio(seed: Song, attribution: PlaybackAttribution = PlaybackAttribution()) {
        radioSeedSong = seed
        isRadioActive = true
        isArtistRadioActive = true
        currentPlaylistId = null
        triggerRadioBanner("${seed.artist} Radio")
        val radioSessionId = VelvetApp.eventRecorderOrNull()?.recordRadioStarted(
            seed = seed,
            isArtistRadio = true,
            attribution = attribution,
        )
        activeRadioSessionId = radioSessionId
        VelvetApp.radioSessionManagerOrNull()?.startSession(
            anchor = seed,
            mode = RecommendationMode.ARTIST_RADIO,
            sessionId = radioSessionId ?: UUID.randomUUID().toString(),
            selectedArtistId = seed.artist,
        )
        VelvetApp.recommendationEngineOrNull()?.invalidateCandidateCache(seed.id)
        play(
            listOf(seed),
            radio = true,
            isArtistRadio = true,
            attribution = attribution.copy(
                mode = RecommendationMode.ARTIST_RADIO,
                surface = if (attribution.surface != RecommendationSurface.OTHER) attribution.surface else RecommendationSurface.ARTIST_RADIO,
                recommendationSessionId = radioSessionId ?: attribution.recommendationSessionId,
            ),
        )
    }

    /**
     * Plays strictly verified similar studio tracks without treating playback as a Radio session.
     */
    fun playSimilarSongs(seed: Song, attribution: PlaybackAttribution = PlaybackAttribution()) {
        play(
            listOf(seed),
            radio = false,
            isArtistRadio = false,
            attribution = attribution.copy(
                mode = RecommendationMode.SIMILAR_SONGS,
            ),
        )
    }

    fun clearUpcoming() {
        queueManager.clearUpcoming()
        syncQueueState()
        syncToExoPlayer()
        requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
    }

    fun move(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun setHiRes(on: Boolean) {
        VelvetApp.library.setDataSaver(!on)
        StreamResolver.dataSaver = !on
        PlaybackService.reloadCurrent()
    }

    fun next() {
        nextTapCoalescer.tap()
    }

    private fun performNextAdvance() {
        val outgoing = current ?: queueManager.current
        val outgoingPos = controller?.currentPosition ?: savedInitialPositionMs
        val outgoingDur = durationMs
        val hasUpcoming = queueManager.userQueue.isNotEmpty() ||
            queueManager.upNext.isNotEmpty() ||
            (queueManager.repeatMode == RepeatMode.ALL && queueManager.sourceList.isNotEmpty())

        if (hasUpcoming) {
            recordCurrentPlaybackEnd(endReason = null)
            val nextSong = queueManager.advance(manual = true)
            VelvetApp.eventRecorderOrNull()?.onExplicitTrackAdvance(
                outgoingSong = outgoing,
                nextSong = nextSong,
                positionMs = outgoingPos,
                durationMs = outgoingDur,
                repeatModeLabel = currentRepeatLabel(),
                transitionReason = "MANUAL_NEXT",
            )
            if (nextSong != null) {
                syncQueueState()
                syncToExoPlayer()
                requestRunwayRefill(RunwayTrigger.MANUAL_NEXT)
                return
            }
        }

        val seed = outgoing ?: queueManager.history.lastOrNull() ?: return
        val canAutoplay = VelvetApp.library.data.value.autoplay || isRadioActive
        if (canAutoplay) {
            if (!isAutoplayLoading) {
                appContext?.let {
                    runCatching { Toast.makeText(it, "Finding similar songs...", Toast.LENGTH_SHORT).show() }
                }
            }
            requestRunwayRefill(
                trigger = RunwayTrigger.MANUAL_NEXT,
                seedId = seed.id,
                autoPlayNext = true,
                force = true,
            )
        } else {
            appContext?.let {
                runCatching { Toast.makeText(it, "No next track in queue", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun onAutoplayToggledOn() {
        requestRunwayRefill(RunwayTrigger.AUTOPLAY_TOGGLED)
    }

    fun onNetworkRegained() {
        requestRunwayRefill(
            trigger = RunwayTrigger.NETWORK_REGAINED,
            force = queueManager.upNext.size < RunwayPolicy.URGENT_BELOW,
        )
    }

    private fun buildRefillSnapshot(): QueueRefillSnapshot {
        val libData = VelvetApp.library.data.value
        val st = queueManager.state
        val ver = queueManager.queueVersion
        val activeSong = st.current?.song ?: current
        return QueueRefillSnapshot(
            currentSong = activeSong,
            upcomingSongs = st.upNext.map { it.song },
            userQueueSongs = st.userQueue.map { it.song },
            historySongs = st.history.map { it.song },
            queueVersion = ver,
            repeatMode = st.repeatMode,
            isAutoplayEnabled = libData.autoplay,
            isRadioActive = isRadioActive,
            isArtistRadioActive = isArtistRadioActive,
            radioSeedSong = radioSeedSong,
            radioSessionId = activeRadioSessionId,
            preferredLanguageIds = mapLanguagesToIso(libData.musicLanguages),
            allowMixedLanguages = libData.allowMixedLanguagesInAutoplay,
            artistLanguageMap = libData.artistLanguageMap,
            fallbackAnchorPool = (st.history.map { it.song }.reversed() + libData.liked)
                .distinctBy { it.id }
                .filterNot { it.id == activeSong?.id }
                .take(12),
            seedSongsOverride = if (isRadioActive) {
                val anchor = radioSeedSong ?: activeSong
                if (anchor != null) VelvetApp.radioSessionManagerOrNull()?.evolvedSeedSongs(anchor).orEmpty() else emptyList()
            } else emptyList(),
            randomSeed = if (!isArtistRadioActive) (ver * 1_000_003L) xor (activeSong?.id?.hashCode()?.toLong() ?: 0L) else null,
            sessionSkippedArtistCounts = VelvetApp.radioSessionManagerOrNull()?.sessionSkippedArtistCounts().orEmpty(),
            sessionSkippedGenreCounts = VelvetApp.radioSessionManagerOrNull()?.sessionSkippedGenreCounts().orEmpty(),
        )
    }

    private fun mapLanguagesToIso(languages: List<String>): Set<String> = languages.mapNotNullTo(mutableSetOf()) { raw ->
        when (raw.trim().lowercase()) {
            "hindi" -> "hi"
            "english" -> "en"
            "punjabi" -> "pa"
            "tamil" -> "ta"
            "telugu" -> "te"
            "malayalam" -> "ml"
            "kannada" -> "kn"
            "bengali" -> "bn"
            "sanskrit" -> "sa"
            else -> null
        }
    }

    internal fun computeInstantAutoplayMatches(
        seed: Song,
        libData: com.velvet.app.data.LibraryData,
        activeSongs: List<Song>,
        featureRepository: com.velvet.app.recommendation.features.TrackFeatureRepository,
        allowArtistHeavy: Boolean,
        cachedRelated: List<Song> = emptyList(),
        limit: Int = 6,
    ): List<Song> {
        val activeIds = buildSet {
            add(seed.id)
            activeSongs.forEach { add(it.id) }
        }
        val activeRecIds = buildSet {
            add(com.velvet.app.recommendation.identity.RecordingIdentityResolver.resolve(seed).canonicalRecordingId)
            add(com.velvet.app.playback.SongIdentity.canonicalGroupId(seed))
            for (s in activeSongs) {
                add(com.velvet.app.recommendation.identity.RecordingIdentityResolver.resolve(s).canonicalRecordingId)
                add(com.velvet.app.playback.SongIdentity.canonicalGroupId(s))
            }
        }
        val libraryPool = (libData.liked + libData.recent + libData.playlists.flatMap { it.songs })
            .distinctBy { it.id }
        val extraPool = (cachedRelated + libraryPool).distinctBy { it.id }
        return com.velvet.app.recommendation.candidates.InstantTrackCatalog.matchSimilar(
            seed = seed,
            extraPool = extraPool,
            excludedIds = activeIds,
            excludedRecIds = activeRecIds,
            preferredLanguageIds = mapLanguagesToIso(libData.musicLanguages),
            featureRepository = featureRepository,
            allowArtistHeavy = allowArtistHeavy,
            limit = limit,
            allowMixedLanguages = libData.allowMixedLanguagesInAutoplay,
            artistLanguageMap = libData.artistLanguageMap,
        )
    }

    /**
     * Schedules off-main preparation of verified matches for [seed] in [RunwayController]
     * so Main thread reads only precomputed RAM results.
     */
    private fun scheduleBackgroundSeedPrep(seed: Song) {
        val runway = VelvetApp.runwayControllerOrNull() ?: return
        val st = queueManager.state
        val activeIds = buildSet {
            add(seed.id)
            st.current?.song?.id?.let(::add)
            st.upNext.forEach { add(it.song.id) }
            st.userQueue.forEach { add(it.song.id) }
            st.history.forEach { add(it.song.id) }
        }
        val libData = VelvetApp.library.data.value
        val langIds = mapLanguagesToIso(libData.musicLanguages)
        runway.prepareSeedInBackground(
            seed = seed,
            libraryDataProvider = { VelvetApp.library.data.value },
            excludedIdsProvider = { activeIds },
            preferredLanguageIds = langIds,
            allowArtistHeavy = isArtistRadioActive,
            extraPoolProvider = { StreamResolver.peekRelated(seed.id).orEmpty() },
            allowMixedLanguages = libData.allowMixedLanguagesInAutoplay,
            artistLanguageMap = libData.artistLanguageMap,
        )
    }

    /**
     * O(1) main-thread read of background-prepared seed matches. Never scans the library
     * or parses recording identities synchronously on Main thread.
     */
    private fun takePreparedInstantSeed(seed: Song): Boolean {
        val runway = VelvetApp.runwayControllerOrNull() ?: return false
        val activeIds = buildSet {
            current?.id?.let(::add)
            queueManager.current?.id?.let(::add)
            queueManager.upNext.forEach { add(it.song.id) }
            queueManager.userQueue.forEach { add(it.song.id) }
            queueManager.history.forEach { add(it.id) }
        }
        val prepared = runway.takePreparedInstant(
            seedId = seed.id,
            excludedIds = activeIds,
            dismissedFilter = { DismissedTrackRegistry.isDismissed(it) },
            limit = 6,
        )
        if (prepared.isEmpty()) {
            scheduleBackgroundSeedPrep(seed)
            return false
        }
        queueManager.appendAutoplay(prepared)
        return true
    }

    fun requestRunwayRefill(
        trigger: RunwayTrigger,
        seedId: String? = null,
        autoPlayNext: Boolean = false,
        force: Boolean = false,
    ) {
        val currentSong = current ?: queueManager.current ?: queueManager.history.lastOrNull() ?: return
        val targetSeed = if (seedId != null) {
            queueManager.upNext.find { it.song.id == seedId }?.song
                ?: queueManager.userQueue.find { it.song.id == seedId }?.song
                ?: queueManager.history.find { it.id == seedId }
                ?: currentSong
        } else currentSong

        val isAutoplayEnabled = VelvetApp.library.data.value.autoplay || isRadioActive
        if (!isAutoplayEnabled) {
            isAutoplayLoading = false
            return
        }

        if (queueManager.upNext.isEmpty() && queueManager.userQueue.isEmpty()) {
            if (takePreparedInstantSeed(targetSeed)) {
                syncQueueState()
                syncToExoPlayer()
                isAutoplayLoading = false
                if (autoPlayNext) {
                    recordCurrentPlaybackEnd(endReason = null)
                    val advanced = queueManager.advance(manual = true)
                    if (advanced != null) {
                        syncQueueState()
                        syncToExoPlayer()
                        scheduleBackgroundSeedPrep(advanced)
                        if (queueManager.upNext.size < RunwayPolicy.REFILL_BELOW) {
                            requestRunwayRefill(trigger, seedId = advanced.id, autoPlayNext = false, force = false)
                        }
                        return
                    }
                }
            }
        }

        scheduleBackgroundSeedPrep(targetSeed)
        if (queueManager.upNext.size >= RunwayPolicy.REFILL_BELOW && !autoPlayNext && !force) {
            isAutoplayLoading = false
            return
        }

        val runway = VelvetApp.runwayControllerOrNull() ?: return
        runway.startPeriodicTick(isPlayingProvider = { isPlaying }) {
            requestRunwayRefill(RunwayTrigger.PERIODIC_TICK)
        }
        isAutoplayLoading = queueManager.upNext.isEmpty() && queueManager.userQueue.isEmpty()

        val job = runway.triggerRefill(
            trigger = trigger,
            snapshotProvider = { buildRefillSnapshot() },
            libraryDataProvider = { VelvetApp.library.data.value },
            seedId = targetSeed.id,
            autoPlayNext = autoPlayNext,
            force = force,
            dismissedFilter = { DismissedTrackRegistry.isDismissed(it) },
        ) { decision ->
            handleRefillDecision(
                decision = decision,
                targetSeed = targetSeed,
                seedId = seedId,
                autoPlayNext = autoPlayNext,
            )
        }
        if (job == null) {
            isAutoplayLoading = false
        }
    }

    private fun handleRefillDecision(
        decision: RefillDecision,
        targetSeed: Song,
        seedId: String?,
        autoPlayNext: Boolean,
    ) {
        val activeCurrentId = current?.id ?: queueManager.current?.id
        if (seedId == null && activeCurrentId != null && activeCurrentId != targetSeed.id) {
            isAutoplayLoading = false
            return
        }

        val candidateSongs = when (decision) {
            is RefillDecision.AppendSongs -> decision.songs
            else -> emptyList()
        }
        val existingIds = buildSet {
            current?.id?.let(::add)
            queueManager.current?.id?.let(::add)
            queueManager.upNext.forEach { add(it.song.id) }
            queueManager.userQueue.forEach { add(it.song.id) }
        }
        val filtered = candidateSongs.filter {
            it.id !in existingIds && !DismissedTrackRegistry.isDismissed(it)
        }
        if (filtered.isNotEmpty()) {
            if (decision is RefillDecision.AppendSongs) {
                VelvetApp.eventRecorderOrNull()?.registerAutoplayCandidates(
                    requestId = decision.requestId,
                    candidates = filtered,
                    mode = decision.mode,
                )
            }
            runwayBgScope.launch {
                com.velvet.app.recommendation.candidates.InstantTrackCatalog.seed(filtered)
            }
            val shouldPlayNow = (autoPlayNext ||
                (decision is RefillDecision.AppendSongs && decision.replaceUpcomingIfEmptyAndPlayNext)) &&
                queueManager.upNext.isEmpty() &&
                queueManager.userQueue.isEmpty()
            if (shouldPlayNow) {
                if (queueManager.current != null) {
                    recordCurrentPlaybackEnd(endReason = null)
                    queueManager.appendAutoplay(filtered)
                    val advanced = queueManager.advance(manual = true)
                    if (advanced == null) {
                        queueManager.start(filtered, 0, false)
                    }
                } else {
                    queueManager.start(filtered, 0, false)
                }
                syncQueueState()
                syncToExoPlayer()
            } else {
                queueManager.appendAutoplay(filtered)
                syncQueueState()
                syncToExoPlayer()
            }
        } else if (decision is RefillDecision.SafeEmpty) {
            com.velvet.app.diagnostics.DiagnosticsLogger.i(
                "Autoplay",
                "SafeEmpty(${decision.status}): ${decision.reason}",
            )
            if (autoPlayNext && queueManager.upNext.isEmpty() && queueManager.userQueue.isEmpty()) {
                appContext?.let {
                    runCatching { Toast.makeText(it, "No similar tracks found for this song", Toast.LENGTH_SHORT).show() }
                }
            }
        } else if (autoPlayNext && queueManager.upNext.isEmpty() && queueManager.userQueue.isEmpty()) {
            appContext?.let {
                runCatching { Toast.makeText(it, "No similar tracks available right now", Toast.LENGTH_SHORT).show() }
            }
        }
        isAutoplayLoading = false
    }

    fun checkAndExtendAutoplay(seedId: String? = null, autoPlayNext: Boolean = false, force: Boolean = false) {
        requestRunwayRefill(
            trigger = if (force) RunwayTrigger.MANUAL_RETRY else RunwayTrigger.QUEUE_MUTATION,
            seedId = seedId,
            autoPlayNext = autoPlayNext,
            force = force,
        )
    }

    fun previous() {
        withController { c ->
            val pos = c.currentPosition.coerceAtLeast(0L)
            val dur = durationMs
            val outgoing = current
            if (pos > 3000L) {
                outgoing?.let {
                    VelvetApp.eventRecorderOrNull()?.onManualReplayCurrent(it, pos, dur, currentRepeatLabel())
                }
                c.seekTo(0)
            } else {
                val prevSong = queueManager.previous(pos)
                if (prevSong != null) {
                    VelvetApp.eventRecorderOrNull()?.onExplicitTrackAdvance(
                        outgoingSong = outgoing,
                        nextSong = prevSong,
                        positionMs = pos,
                        durationMs = dur,
                        repeatModeLabel = currentRepeatLabel(),
                        transitionReason = "MANUAL_PREVIOUS",
                    )
                    syncQueueState()
                    syncToExoPlayer()
                    requestRunwayRefill(RunwayTrigger.TRACK_CHANGE)
                } else {
                    outgoing?.let {
                        VelvetApp.eventRecorderOrNull()?.onManualReplayCurrent(it, pos, dur, currentRepeatLabel())
                    }
                    savedInitialPositionMs = 0L
                    pendingSeekTargetMs = 0L
                    pendingSeekUntilMs = monoNowMs() + 900L
                    c.seekTo(0)
                }
            }
        }
    }

    fun seekTo(ms: Long) {
        val clampedMs = ms.coerceAtLeast(0L)
        val nowMs = monoNowMs()
        val prevPos = controller?.currentPosition ?: savedInitialPositionMs
        if (prevPos >= 30_000L && clampedMs < 3_000L) {
            current?.let {
                VelvetApp.eventRecorderOrNull()?.onManualReplayCurrent(it, prevPos, durationMs, currentRepeatLabel())
            }
        }
        savedInitialPositionMs = clampedMs
        pendingSeekTargetMs = clampedMs
        pendingSeekUntilMs = nowMs + 1_400L
        withController { c ->
            if (c.mediaItemCount == 0 && current != null) {
                syncToExoPlayer(autoPlay = isPlaying || playWhenReady, startPositionMs = clampedMs)
            } else {
                c.seekTo(clampedMs)
            }
            persistState()
        }
    }

    // Legacy compat shims — delegate to qid-based API
    fun jumpTo(i: Int) {
        val item = queue.getOrNull(i) ?: return
        if (item.qid == "current") return
        jumpToQid(item.qid)
    }

    fun removeAt(i: Int) {
        val item = queue.getOrNull(i) ?: return
        if (item.qid == "current") return
        removeItem(item.qid)
    }

    fun moveToTop(from: Int) {
        val item = queue.getOrNull(from) ?: return
        if (item.qid == "current") return
        moveToTop(item.qid)
    }

    fun cycleRepeat() {
        val targetMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> RepeatMode.ALL
            Player.REPEAT_MODE_ALL -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        queueManager.setRepeatMode(targetMode)
        syncQueueState()
        syncToExoPlayer()
        if (targetMode != RepeatMode.ONE) {
            requestRunwayRefill(RunwayTrigger.QUEUE_MUTATION)
        }
    }

    fun positionMs(): Long {
        val c = controller
        val nowMs = monoNowMs()
        if (c != null) {
            val liveDur = c.duration
            if (liveDur > 0L && liveDur != durationMs) {
                durationMs = liveDur
            }
            val exoPlayIntended = c.playWhenReady && c.playbackState != Player.STATE_ENDED
            val activeOpt = optimisticPlayState?.takeIf { nowMs < optimisticPlayUntilMs }
            if (optimisticPlayState != null && (nowMs >= optimisticPlayUntilMs || exoPlayIntended == optimisticPlayState)) {
                optimisticPlayState = null
            }
            if (activeOpt == null) {
                val livePlaying = PlaybackControlSync.resolveDisplayedPlaying(
                    exoIsPlaying = c.isPlaying,
                    exoPlayWhenReady = c.playWhenReady,
                    exoPlaybackState = c.playbackState,
                    stateEnded = Player.STATE_ENDED,
                    stateBuffering = Player.STATE_BUFFERING,
                    optimisticPlay = null,
                )
                if (livePlaying != isPlaying) isPlaying = livePlaying
                playWhenReady = exoPlayIntended
                val liveBuffering = PlaybackControlSync.resolveDisplayedBuffering(
                    exoPlaybackState = c.playbackState,
                    stateBuffering = Player.STATE_BUFFERING,
                    displayedPlaying = livePlaying,
                    exoIsPlaying = c.isPlaying,
                )
                if (liveBuffering != isBuffering) isBuffering = liveBuffering
            }
        }
        val sync = PlaybackControlSync.reconcilePositionMs(
            rawPosMs = c?.currentPosition,
            pendingSeekMs = pendingSeekTargetMs,
            pendingUntilMs = pendingSeekUntilMs,
            nowMs = nowMs,
            savedPosMs = savedInitialPositionMs,
            hasMediaItems = (c?.mediaItemCount ?: 0) > 0,
            isPlayActive = (c?.isPlaying == true) || (c?.playWhenReady == true && c.playbackState != Player.STATE_ENDED),
        )
        if (sync.clearPendingSeek) pendingSeekTargetMs = null
        savedInitialPositionMs = sync.savedPositionMs
        return sync.positionMs
    }
}
