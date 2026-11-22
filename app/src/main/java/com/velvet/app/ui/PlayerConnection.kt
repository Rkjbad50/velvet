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
import com.velvet.app.data.Song
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
    var isBuffering by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var savedInitialPositionMs by mutableLongStateOf(0L)
        private set
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
    private var isExtendingAutoplay = false

    /**
     * Whether playback originated from a user-created local playlist.
     * Smart Shuffle is strictly restricted to user-created playlists only.
     */
    val isUserPlaylistActive: Boolean
        get() = currentPlaylistId != null && VelvetApp.library.data.value.playlists.any { it.id == currentPlaylistId }

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

    private fun syncQueueState() {
        queueState = queueManager.state
        current = queueManager.current
        shuffle = queueManager.shuffle
        shuffleMode = queueManager.shuffleMode
        queue = queueManager.visibleQueue
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncBasicState(player)
        override fun onPlayerError(error: PlaybackException) { this@PlayerConnection.error = error.message }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // When ExoPlayer auto-advances (track finished naturally), pop from our queue
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                queueManager.advance(manual = false)
                syncQueueState()
                syncToExoPlayer()
                checkAndExtendAutoplay()
            }
        }
    }

    /** Syncs basic playback state (play/pause/buffer/duration/repeat) from ExoPlayer. */
    private fun syncBasicState(p: Player) {
        val exoCurrent = p.currentMediaItem?.toSong()
        if (exoCurrent != null && exoCurrent.id != current?.id) {
            if (queueManager.current?.id != exoCurrent.id) {
                if (queueManager.userQueue.firstOrNull()?.song?.id == exoCurrent.id ||
                    queueManager.upNext.firstOrNull()?.song?.id == exoCurrent.id) {
                    queueManager.advance()
                    syncQueueState()
                    syncToExoPlayer()
                } else {
                    current = exoCurrent
                }
            }
        }
        val isAutoplayOn = VelvetApp.library.data.value.autoplay || isRadioActive
        if (isAutoplayOn && queueManager.upNext.size < 4) {
            checkAndExtendAutoplay()
        }
        isPlaying = p.isPlaying
        isBuffering = p.playbackState == Player.STATE_BUFFERING
        durationMs = if (p.duration > 0L) p.duration else (current?.durationSec?.times(1000L)?.coerceAtLeast(0L) ?: 0L)
        repeatMode = p.repeatMode
        if (p.currentPosition > 0L) {
            savedInitialPositionMs = p.currentPosition
        }
        if (!p.isPlaying && p.currentPosition > 0L) {
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
        syncQueueState()

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

    fun play(
        songs: List<Song>,
        startIndex: Int = 0,
        shuffled: Boolean = false,
        radio: Boolean = false,
        playlistId: String? = null,
        context: PlaybackContext? = null,
    ) {
        if (songs.isEmpty()) return
        if (context != null) {
            VelvetApp.library.addContextRecent(context)
        }
        currentPlaylistId = playlistId
        isRadioActive = radio
        val isUserPlaylist = playlistId != null && VelvetApp.library.data.value.playlists.any { it.id == playlistId }
        val start = playlistId?.let { PlaylistStart(it, UUID.randomUUID().toString()) }

        queueManager.start(songs, startIndex, shuffled)
        // Strictly disallow Smart Shuffle when playing outside a user-created playlist
        if (!isUserPlaylist && queueManager.shuffleMode == ShuffleMode.SMART) {
            queueManager.setShuffleMode(if (shuffled) ShuffleMode.STANDARD else ShuffleMode.OFF, allowSmartShuffle = false)
        }
        syncQueueState()
        syncToExoPlayer(playlistStart = start, isRadio = radio)

        val isAutoplayOn = VelvetApp.library.data.value.autoplay || radio
        if (isAutoplayOn && queueManager.upNext.isEmpty()) {
            queueManager.current?.id?.let { checkAndExtendAutoplay(it) }
        }
    }

    /**
     * Cycle shuffle mode.
     * When playing from a user-created playlist: OFF -> STANDARD -> SMART -> OFF.
     * Everywhere else (standalone songs, search results, artist, albums): SMART is disabled,
     * so it cycles strictly between OFF <-> STANDARD (ON).
     */
    fun cycleShuffle(context: Context? = null) {
        val allowSmart = isUserPlaylistActive
        val newMode = queueManager.cycleShuffleMode(allowSmartShuffle = allowSmart)
        syncQueueState()
        syncToExoPlayer()

        when (newMode) {
            ShuffleMode.OFF -> {
                (context ?: appContext)?.let { Toast.makeText(it, "Shuffle is off", Toast.LENGTH_SHORT).show() }
            }
            ShuffleMode.STANDARD -> {
                (context ?: appContext)?.let { Toast.makeText(it, "Shuffle is on", Toast.LENGTH_SHORT).show() }
            }
            ShuffleMode.SMART -> {
                (context ?: appContext)?.let { Toast.makeText(it, "Smart Shuffle is on ✨ (Mixing in fresh matching songs)", Toast.LENGTH_SHORT).show() }
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
        val seeds = (listOf(currentSong) + queueManager.sourceList.shuffled().take(4)).distinctBy { it.id }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val candidates = mutableListOf<Song>()
                val snapshot = (
                    queueManager.sourceList +
                    queueManager.history +
                    listOfNotNull(queueManager.current) +
                    queueManager.userQueue.map { it.song }
                )
                for (seed in seeds) {
                    val recs = runCatching { VelvetApp.autoplayPipeline.next(seed, snapshot, n = 6) }.getOrDefault(emptyList())
                    candidates.addAll(recs)
                }

                val sorted = candidates.distinctBy { it.id }.take(12)

                withContext(Dispatchers.Main) {
                    if (queueManager.shuffleMode == ShuffleMode.SMART && sorted.isNotEmpty()) {
                        queueManager.injectSmartRecommendations(sorted)
                        syncQueueState()
                        syncToExoPlayer()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Add song to play immediately after current. */
    fun playNext(song: Song) {
        val isAlreadyQueued = (listOfNotNull(current) + queueManager.upNext.map { it.song } + queueManager.userQueue.map { it.song })
            .any { it.id == song.id || com.velvet.app.playback.SongIdentity.isStrictMatch(it, song) }
        if (isAlreadyQueued) {
            appContext?.let { Toast.makeText(it, "Already in your queue", Toast.LENGTH_SHORT).show() }
        }

        VelvetApp.sessionHistory.record(song)
        if (controller?.mediaItemCount == 0 || current == null) {
            play(listOf(song))
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
    fun enqueue(song: Song) {
        val isAlreadyQueued = (listOfNotNull(current) + queueManager.upNext.map { it.song } + queueManager.userQueue.map { it.song })
            .any { it.id == song.id || com.velvet.app.playback.SongIdentity.isStrictMatch(it, song) }
        if (isAlreadyQueued) {
            appContext?.let { Toast.makeText(it, "Already in your queue", Toast.LENGTH_SHORT).show() }
        }

        VelvetApp.sessionHistory.record(song)
        if (controller?.mediaItemCount == 0 || current == null) {
            play(listOf(song))
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
        val removed = queueManager.removeItem(qid)
        if (removed) {
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
        val targetSong = queueManager.jumpToQid(qid) ?: return
        syncQueueState()
        syncToExoPlayer()
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) {
            c.pause()
            persistState()
        } else {
            if (c.mediaItemCount == 0 && current != null) {
                syncToExoPlayer(autoPlay = true, startPositionMs = savedInitialPositionMs)
            } else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.play()
            }
        }
    }

    fun startRadio(song: Song) = play(listOf(song), radio = true)

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
        val nextSong = queueManager.advance(manual = true)
        if (nextSong != null) {
            syncQueueState()
            syncToExoPlayer()
            checkAndExtendAutoplay()
        } else {
            val cur = current
            if (cur != null) {
                checkAndExtendAutoplay(cur.id, autoPlayNext = true)
            }
        }
    }

    fun checkAndExtendAutoplay(seedId: String? = null, autoPlayNext: Boolean = false) {
        val currentSong = current ?: return
        val targetSeed = if (seedId != null) {
            queueManager.upNext.find { it.song.id == seedId }?.song
                ?: queueManager.userQueue.find { it.song.id == seedId }?.song
                ?: currentSong
        } else currentSong
        val isAutoplayEnabled = VelvetApp.library.data.value.autoplay || isRadioActive
        if (!isAutoplayEnabled) return
        if (queueManager.upNext.size >= 6 && !autoPlayNext) return
        if (isExtendingAutoplay) return

        isExtendingAutoplay = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = (
                    queueManager.history +
                    queueManager.sourceList +
                    listOfNotNull(current) +
                    queueManager.userQueue.map { it.song } +
                    queueManager.upNext.map { it.song }
                )
                val playlist = currentPlaylistId?.let { id -> VelvetApp.library.data.value.playlists.find { it.id == id } }
                val contextTitle = playlist?.name ?: queueManager.sourceList.firstOrNull()?.album
                val recentSongs = queueManager.history.takeLast(4).reversed()
                val seedProfile = com.velvet.app.playback.SeedProfile(
                    current = targetSeed,
                    recentSongs = recentSongs,
                    contextName = contextTitle,
                )
                val related = VelvetApp.autoplayPipeline.next(seedProfile, snapshot, n = 10)

                withContext(Dispatchers.Main) {
                    if (related.isNotEmpty()) {
                        if (autoPlayNext && queueManager.upNext.isEmpty() && queueManager.userQueue.isEmpty()) {
                            val first = related.first()
                            val rest = related.drop(1)
                            queueManager.start(listOf(first) + rest, 0, false)
                            syncQueueState()
                            syncToExoPlayer()
                        } else {
                            queueManager.appendAutoplay(related)
                            syncQueueState()
                            syncToExoPlayer()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                isExtendingAutoplay = false
            }
        }
    }

    fun previous() {
        withController { c ->
            if (c.currentPosition > 3000L) {
                c.seekTo(0)
            } else {
                val prevSong = queueManager.previous(c.currentPosition)
                if (prevSong != null) {
                    syncQueueState()
                    syncToExoPlayer()
                } else {
                    c.seekTo(0)
                }
            }
        }
    }

    fun seekTo(ms: Long) {
        savedInitialPositionMs = ms
        withController { c ->
            if (c.mediaItemCount == 0 && current != null) {
                syncToExoPlayer(autoPlay = false, startPositionMs = ms)
            } else {
                c.seekTo(ms)
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
        val pos = controller?.currentPosition ?: 0L
        return if (pos > 0L) pos else savedInitialPositionMs
    }
}
