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
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.model.PlaybackAttribution
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationSurface
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.YouTube
import com.velvet.app.data.isDuplicateOf
import com.velvet.app.data.isOriginalMusicAudio
import com.velvet.app.data.isPureMusic
import com.velvet.app.data.isSameTrackAs
import com.velvet.app.playback.PersistedPlaybackState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private var isExtendingAutoplay = false
    private var autoplayJob: Job? = null
    private var currentAutoplayGeneration = 0L

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
            if (currentSong != null && songs.any { it.id == currentSong.id }) {
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
            if (currentSong != null && songs.any { it.id == currentSong.id }) {
                val allowSmart = isUserPlaylistActive
                if (queueManager.shuffleMode == ShuffleMode.OFF) {
                    queueManager.setShuffleMode(ShuffleMode.STANDARD, allowSmartShuffle = allowSmart)
                    syncQueueState()
                    syncToExoPlayer()
                    (context?.let { null } ?: appContext)?.let {
                        Toast.makeText(it, "Shuffle is on", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Running playlist already has shuffle on: tapping Shuffle reshuffles the upcoming queue
                    queueManager.setShuffleMode(ShuffleMode.STANDARD, allowSmartShuffle = allowSmart)
                    syncQueueState()
                    syncToExoPlayer()
                    (context?.let { null } ?: appContext)?.let {
                        Toast.makeText(it, "Reshuffled queue 🔀", Toast.LENGTH_SHORT).show()
                    }
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
    }

    private fun syncToExoPlayer(
        playlistStart: PlaylistStart? = null,
        isRadio: Boolean = isRadioActive,
        autoPlay: Boolean = true,
        startPositionMs: Long = 0L,
    ) {
        withController { c ->
            QueueExoAdapter.syncToExoPlayer(c, queueManager.state, playlistStart, isRadio, autoPlay, startPositionMs)
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
        if (current == null) {
            restoreSavedPlaybackState(null)
        }
        ensureConnected()
    }

    fun disconnect() {
        started = false
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncBasicState(player)
        override fun onPlayerError(error: PlaybackException) { this@PlayerConnection.error = error.message }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // When ExoPlayer auto-advances (track finished naturally), pop from our queue
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                val endReason = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    com.velvet.app.data.ListenEndReason.REPLAY
                } else {
                    com.velvet.app.data.ListenEndReason.COMPLETED
                }
                recordCurrentPlaybackEnd(endReason = endReason)
                queueManager.advance(manual = false)
                syncQueueState()
                syncToExoPlayer()
                checkAndExtendAutoplay()
                if (queueManager.upNext.size <= 4) {
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
            } else if (queueManager.current?.id != exoCurrent.id) {
                if (queueManager.userQueue.firstOrNull()?.song?.id == exoCurrent.id ||
                    queueManager.upNext.firstOrNull()?.song?.id == exoCurrent.id) {
                    trackStartedAt = System.currentTimeMillis()
                    lastRecordedEventSongId = null
                    queueManager.advance()
                    syncQueueState()
                    syncToExoPlayer()
                }
                // Otherwise ignore stale pre-transition events while ExoPlayer catches up to queueManager.current
            }
            if (queueManager.upNext.size <= 4) {
                StreamResolver.prefetchRelated(queueManager.upNext.lastOrNull()?.song ?: (queueManager.current ?: exoCurrent))
            }
        }
        val isAutoplayOn = VelvetApp.library.data.value.autoplay || isRadioActive
        if (isAutoplayOn && queueManager.upNext.size < 4) {
            checkAndExtendAutoplay()
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

    fun persistState() {
        val cur = current ?: return
        val ctx = appContext ?: return
        val pos = controller?.currentPosition ?: savedInitialPositionMs
        PlaybackStateManager.save(
            context = ctx,
            song = cur,
            positionMs = pos,
            durationMs = durationMs,
            upNext = upNext.map { it.song },
            userQueue = userQueue.map { it.song },
            history = history.map { it.song },
            playlistId = currentPlaylistId,
            isRadio = isRadioActive,
            shuffleMode = shuffleMode,
        )
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

        val song = saved.song
        current = song
        currentPlaylistId = saved.playlistId
        isRadioActive = saved.isRadio
        isArtistRadioActive = false
        radioSeedSong = if (saved.isRadio) song else null
        savedInitialPositionMs = saved.positionMs
        durationMs = if (saved.durationMs > 0L) saved.durationMs else (song.durationSec * 1000L)

        val fullList = (saved.history + listOf(song) + saved.upNext).distinctBy { it.id }
        val startIndex = fullList.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        queueManager.start(fullList, startIndex = startIndex, shuffled = false)
        if (saved.userQueue.isNotEmpty()) {
            saved.userQueue.forEach { queueManager.playNext(it) }
        }
        if (saved.shuffleMode == ShuffleMode.STANDARD) {
            queueManager.setShuffleMode(ShuffleMode.STANDARD, allowSmartShuffle = false)
        } else if (saved.shuffleMode == ShuffleMode.SMART && isUserPlaylistActive) {
            queueManager.setShuffleMode(ShuffleMode.SMART, allowSmartShuffle = true)
        }
        syncQueueState(resetPositionOnTrackChange = false)

        c?.let { controller ->
            QueueExoAdapter.syncToExoPlayer(
                player = controller,
                state = queueManager.state,
                playlistStart = null,
                isRadio = saved.isRadio,
                autoPlay = false,
                startPositionMs = saved.positionMs,
            )
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

        com.velvet.app.playback.DismissedTrackRegistry.clear()
        queueManager.start(songs, startIndex, shuffled)
        // Strictly disallow Smart Shuffle when playing outside a user-created playlist
        if (!isUserPlaylist && queueManager.shuffleMode == ShuffleMode.SMART) {
            queueManager.setShuffleMode(if (shuffled) ShuffleMode.STANDARD else ShuffleMode.OFF, allowSmartShuffle = false)
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

        savedInitialPositionMs = 0L
        pendingSeekTargetMs = null
        pendingSeekUntilMs = 0L
        optimisticPlayState = true
        optimisticPlayUntilMs = monoNowMs() + 750L
        playWhenReady = true
        isPlaying = true

        syncQueueState()
        syncToExoPlayer(playlistStart = start, isRadio = radio)

        val isAutoplayOn = VelvetApp.library.data.value.autoplay || radio
        if (isAutoplayOn) {
            if (queueManager.upNext.size < 4) {
                queueManager.current?.id?.let { checkAndExtendAutoplay(it) }
            }
            (queueManager.upNext.lastOrNull()?.song ?: queueManager.current)?.let {
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
        syncQueueState()
        syncToExoPlayer()

        if (queueManager.upNext.isEmpty() && queueManager.userQueue.isEmpty() &&
            newMode != ShuffleMode.OFF &&
            (VelvetApp.library.data.value.autoplay || isRadioActive)
        ) {
            checkAndExtendAutoplay(seedId = activeSong.id)
        }

        when (newMode) {
            ShuffleMode.OFF -> {
                (context ?: appContext)?.let { runCatching { Toast.makeText(it, "Shuffle is off", Toast.LENGTH_SHORT).show() } }
            }
            ShuffleMode.STANDARD -> {
                (context ?: appContext)?.let { runCatching { Toast.makeText(it, "Shuffle is on", Toast.LENGTH_SHORT).show() } }
            }
            ShuffleMode.SMART -> {
                (context ?: appContext)?.let {
                    runCatching {
                        Toast.makeText(it, "Smart Shuffle is on ✨ (Mixing in fresh matching songs)", Toast.LENGTH_SHORT).show()
                    }
                }
                loadSmartShuffleRecommendations()
            }
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
            VelvetApp.autoplayPipeline.clearCandidatePoolCache(song.id)
            queueManager.clearUpcoming()
            syncQueueState()
            syncToExoPlayer(isRadio = true)
            checkAndExtendAutoplay(seedId = song.id, force = true)
        } else {
            VelvetApp.autoplayPipeline.clearCandidatePoolCache(song.id)
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
        VelvetApp.autoplayPipeline.clearCandidatePoolCache(seed.id)
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
                checkAndExtendAutoplay()
                return
            }
        }

        val seed = outgoing ?: queueManager.history.lastOrNull() ?: return
        val canAutoplay = VelvetApp.library.data.value.autoplay || isRadioActive
        if (canAutoplay) {
            // Never null out queueManager.current while ExoPlayer & NowPlaying are still active on the seed song.
            if (!isExtendingAutoplay) {
                appContext?.let {
                    runCatching { Toast.makeText(it, "Finding similar songs...", Toast.LENGTH_SHORT).show() }
                }
            }
            checkAndExtendAutoplay(seed.id, autoPlayNext = true, force = true)
        } else {
            appContext?.let {
                runCatching { Toast.makeText(it, "No next track in queue", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun buildRefillSnapshot(): QueueRefillSnapshot {
        val libData = VelvetApp.library.data.value
        val st = queueManager.state
        val ver = queueManager.queueVersion
        return QueueRefillSnapshot(
            currentSong = current ?: st.current?.song,
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
            seedSongsOverride = if (isRadioActive) {
                val anchor = radioSeedSong ?: current ?: st.current?.song
                if (anchor != null) VelvetApp.radioSessionManagerOrNull()?.evolvedSeedSongs(anchor).orEmpty() else emptyList()
            } else emptyList(),
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

    fun checkAndExtendAutoplay(seedId: String? = null, autoPlayNext: Boolean = false, force: Boolean = false) {
        val currentSong = current ?: queueManager.history.lastOrNull() ?: return
        val targetSeed = if (seedId != null) {
            queueManager.upNext.find { it.song.id == seedId }?.song
                ?: queueManager.userQueue.find { it.song.id == seedId }?.song
                ?: queueManager.history.find { it.id == seedId }
                ?: currentSong
        } else currentSong
        val isAutoplayEnabled = VelvetApp.library.data.value.autoplay || isRadioActive
        if (!isAutoplayEnabled) return
        if (queueManager.upNext.size >= 6 && !autoPlayNext && !force) return
        if (isExtendingAutoplay && !force) return

        val generation = ++currentAutoplayGeneration
        val initialSnapshot = buildRefillSnapshot()
        isExtendingAutoplay = true
        autoplayJob?.cancel()
        autoplayJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                if (force) {
                    VelvetApp.autoplayRefillCoordinator.resetForNewSeed(targetSeed.id)
                }
                val decision = withTimeoutOrNull(2_650L) {
                    VelvetApp.autoplayRefillCoordinator.requestRefill(
                        initialSnapshot = initialSnapshot,
                        latestSnapshotProvider = { buildRefillSnapshot() },
                        seedId = targetSeed.id,
                        autoPlayNext = autoPlayNext,
                        force = force,
                        dismissedFilter = { com.velvet.app.playback.DismissedTrackRegistry.isDismissed(it) },
                    )
                } ?: RefillDecision.SafeEmpty(
                    requestId = "timeout-$generation",
                    reason = "Queue refill hit 2.65s latency ceiling",
                    status = com.velvet.app.recommendation.engine.RefillStatus.IDLE,
                )

                withContext(Dispatchers.Main) {
                    if (!isActive || generation != currentAutoplayGeneration) return@withContext
                    if (queueManager.queueVersion != initialSnapshot.queueVersion) return@withContext
                    when (decision) {
                        is RefillDecision.AppendSongs -> {
                            val filtered = decision.songs.filterNot {
                                com.velvet.app.playback.DismissedTrackRegistry.isDismissed(it)
                            }
                            if (filtered.isNotEmpty()) {
                                VelvetApp.eventRecorderOrNull()?.registerAutoplayCandidates(
                                    requestId = decision.requestId,
                                    candidates = filtered,
                                    mode = decision.mode,
                                )
                                if (decision.replaceUpcomingIfEmptyAndPlayNext &&
                                    queueManager.upNext.isEmpty() &&
                                    queueManager.userQueue.isEmpty()
                                ) {
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
                            } else if (autoPlayNext) {
                                appContext?.let {
                                    runCatching { Toast.makeText(it, "No similar tracks available right now", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                        is RefillDecision.SafeEmpty -> {
                            com.velvet.app.diagnostics.DiagnosticsLogger.i(
                                "Autoplay",
                                "SafeEmpty(${decision.status}): ${decision.reason}",
                            )
                            if (autoPlayNext) {
                                appContext?.let {
                                    runCatching { Toast.makeText(it, "No similar tracks found for this song", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                        is RefillDecision.Skip -> Unit
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                if (generation == currentAutoplayGeneration) {
                    isExtendingAutoplay = false
                }
            }
        }
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
