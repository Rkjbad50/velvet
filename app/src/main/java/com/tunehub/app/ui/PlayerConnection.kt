package com.tunehub.app.ui

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
import com.tunehub.app.TuneHubApp
import com.tunehub.app.data.BrowseItem
import com.tunehub.app.data.Song
import com.tunehub.app.data.StreamResolver
import com.tunehub.app.data.YouTube
import com.tunehub.app.data.isDuplicateOf
import com.tunehub.app.data.isOriginalMusicAudio
import com.tunehub.app.data.isPureMusic
import com.tunehub.app.playback.PlaybackService
import com.tunehub.app.playback.PlayerQueueManager
import com.tunehub.app.playback.PlaylistStart
import com.tunehub.app.playback.QueueItem
import com.tunehub.app.playback.ShuffleMode
import com.tunehub.app.playback.toMediaItem
import com.tunehub.app.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The UI's handle on PlaybackService, mirrored into Compose state. */
object PlayerConnection {
    val queueManager = PlayerQueueManager()

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
    var error by mutableStateOf<String?>(null)
    var currentPlaylistId by mutableStateOf<String?>(null)
        private set

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
        current = queueManager.current
        shuffle = queueManager.shuffle
        shuffleMode = queueManager.shuffleMode
        queue = queueManager.visibleQueue
    }

    // ── Connection plumbing ──────────────────────────────────────────────────
    private var appContext: Context? = null
    private var started = false
    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    fun connect(context: Context) {
        appContext = context.applicationContext
        started = true
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
                queueManager.advance()
                syncQueueState()
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
                } else {
                    current = exoCurrent
                }
            }
        }
        isPlaying = p.isPlaying
        isBuffering = p.playbackState == Player.STATE_BUFFERING
        durationMs = p.duration.coerceAtLeast(0)
        repeatMode = p.repeatMode
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
    ) {
        if (songs.isEmpty()) return
        currentPlaylistId = playlistId
        queueManager.start(songs, startIndex, shuffled)
        syncQueueState()

        val startSong = queueManager.current ?: return
        val start = playlistId?.let { PlaylistStart(it, UUID.randomUUID().toString()) }
        val allItems = listOf(startSong) + (queueManager.userQueue.map { it.song }) + (queueManager.upNext.map { it.song })
        val mediaItems = allItems.mapIndexed { i, song ->
            song.toMediaItem(radio = radio && i == 0, playlistStart = start)
        }
        withController(replaces = true) { c ->
            c.shuffleModeEnabled = false // Handled directly in PlayerQueueManager
            c.setMediaItems(mediaItems, 0, 0)
            c.prepare()
            c.play()
        }
    }

    /**
     * Cycle through all shuffle modes: OFF -> STANDARD -> SMART -> OFF.
     * Displays clean toasts and triggers smart recommendation loading when SMART is activated.
     */
    fun cycleShuffle(context: Context? = null) {
        val newMode = queueManager.cycleShuffleMode()
        syncQueueState()
        syncUpNextToExoPlayer()

        when (newMode) {
            ShuffleMode.OFF -> {
                (context ?: appContext)?.let { Toast.makeText(it, "Shuffle is off", Toast.LENGTH_SHORT).show() }
            }
            ShuffleMode.STANDARD -> {
                (context ?: appContext)?.let { Toast.makeText(it, "Shuffle is on", Toast.LENGTH_SHORT).show() }
            }
            ShuffleMode.SMART -> {
                (context ?: appContext)?.let { Toast.makeText(it, "Smart Shuffle is on ✨", Toast.LENGTH_SHORT).show() }
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
     * of the currently playing track/playlist, filtering strictly for original source audio.
     */
    fun loadSmartShuffleRecommendations() {
        val currentSong = current ?: return
        val seeds = (listOf(currentSong) + queueManager.sourceList.take(3)).distinctBy { it.id }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val candidates = mutableListOf<Song>()
                for (seed in seeds) {
                    val related = runCatching { StreamResolver.related(seed.id) }.getOrDefault(emptyList())
                    candidates.addAll(related)
                }
                val filtered = candidates
                    .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
                    .distinctBy { it.id }
                    .sortedByDescending { it.preferredMusicSource }
                    .take(8)

                withContext(Dispatchers.Main) {
                    if (queueManager.shuffleMode == ShuffleMode.SMART) {
                        queueManager.injectSmartRecommendations(filtered)
                        syncQueueState()
                        syncUpNextToExoPlayer()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Syncs our userQueue + upNext to ExoPlayer AFTER current track (without interrupting it). */
    private fun syncUpNextToExoPlayer() {
        withController { c ->
            val currentIdx = c.currentMediaItemIndex
            val after = currentIdx + 1
            if (after < c.mediaItemCount) {
                c.removeMediaItems(after, c.mediaItemCount)
            }
            val upcoming = (queueManager.userQueue + queueManager.upNext).map { it.song.toMediaItem() }
            if (upcoming.isNotEmpty()) {
                c.addMediaItems(upcoming)
            }
        }
    }

    /** Add song to play immediately after current. */
    fun playNext(song: Song) {
        if (controller?.mediaItemCount == 0 || current == null) {
            play(listOf(song))
            return
        }
        queueManager.playNext(song)
        syncQueueState()
        syncUpNextToExoPlayer()
        appContext?.let { Toast.makeText(it, "\"${song.title}\" will play next", Toast.LENGTH_SHORT).show() }
    }

    /** Add song to end of queue. */
    fun enqueue(song: Song) {
        if (controller?.mediaItemCount == 0 || current == null) {
            play(listOf(song))
            return
        }
        val added = queueManager.enqueue(song)
        if (!added) {
            appContext?.let { Toast.makeText(it, "\"${song.title}\" already in queue", Toast.LENGTH_SHORT).show() }
            return
        }
        syncQueueState()
        syncUpNextToExoPlayer()
        appContext?.let { Toast.makeText(it, "Added to queue", Toast.LENGTH_SHORT).show() }
    }

    /** Move a queue item (by qid) to the top of the upcoming list (play next). */
    fun moveToTop(qid: String) {
        val moved = queueManager.moveToTop(qid)
        if (moved) {
            syncQueueState()
            syncUpNextToExoPlayer()
        }
    }

    /** Remove a queue item by qid. */
    fun removeItem(qid: String) {
        val removed = queueManager.removeItem(qid)
        if (removed) {
            syncQueueState()
            syncUpNextToExoPlayer()
        }
    }

    /** Jump to a specific queue item by qid. */
    fun jumpToQid(qid: String) {
        val targetSong = queueManager.jumpToQid(qid) ?: return
        syncQueueState()
        withController { c ->
            val mediaItems = (listOf(targetSong) + (queueManager.userQueue + queueManager.upNext).map { it.song })
                .map { it.toMediaItem() }
            c.setMediaItems(mediaItems, 0, 0)
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) c.pause()
        else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun startRadio(song: Song) = play(listOf(song), radio = true)

    fun clearUpcoming() {
        queueManager.clearUpcoming()
        syncQueueState()
        syncUpNextToExoPlayer()
    }

    fun move(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun setHiRes(on: Boolean) {
        TuneHubApp.library.setDataSaver(!on)
        StreamResolver.dataSaver = !on
        PlaybackService.reloadCurrent()
    }

    fun next() {
        val nextSong = queueManager.advance()
        if (nextSong != null) {
            syncQueueState()
            withController { c ->
                val mediaItems = (listOf(nextSong) + (queueManager.userQueue + queueManager.upNext).map { it.song })
                    .map { it.toMediaItem() }
                c.setMediaItems(mediaItems, 0, 0)
                c.prepare()
                c.play()
            }
        }
    }

    fun previous() {
        withController { c ->
            if (c.currentPosition > 3000L) {
                c.seekTo(0)
            } else {
                val prevSong = queueManager.previous()
                if (prevSong != null) {
                    syncQueueState()
                    val mediaItems = (listOf(prevSong) + (queueManager.userQueue + queueManager.upNext).map { it.song })
                        .map { it.toMediaItem() }
                    c.setMediaItems(mediaItems, 0, 0)
                    c.prepare()
                    c.play()
                } else {
                    c.seekTo(0)
                }
            }
        }
    }

    fun seekTo(ms: Long) = withController { it.seekTo(ms) }

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

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0L
}
