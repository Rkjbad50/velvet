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
import com.tunehub.app.data.isSameTrackAs
import com.tunehub.app.playback.PlaybackService
import com.tunehub.app.playback.PlaylistStart
import com.tunehub.app.playback.QueueExoAdapter
import com.tunehub.app.playback.QueueItem
import com.tunehub.app.playback.QueueManager
import com.tunehub.app.playback.QueueState
import com.tunehub.app.playback.RepeatMode
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
    var error by mutableStateOf<String?>(null)
    var currentPlaylistId by mutableStateOf<String?>(null)
        private set
    var isRadioActive by mutableStateOf(false)
        private set
    private var isExtendingAutoplay = false

    /** Pure single source of truth queue state. */
    var queueState by mutableStateOf(queueManager.state)
        private set

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

    private fun syncToExoPlayer(playlistStart: PlaylistStart? = null, isRadio: Boolean = isRadioActive) {
        withController { c ->
            QueueExoAdapter.syncToExoPlayer(c, queueManager.state, playlistStart, isRadio)
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
        val isAutoplayOn = TuneHubApp.library.data.value.autoplay || isRadioActive
        if (isAutoplayOn && queueManager.upNext.size < 4) {
            checkAndExtendAutoplay()
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
        isRadioActive = radio
        val start = playlistId?.let { PlaylistStart(it, UUID.randomUUID().toString()) }
        queueManager.start(songs, startIndex, shuffled)
        syncQueueState()
        syncToExoPlayer(playlistStart = start, isRadio = radio)

        val isAutoplayOn = TuneHubApp.library.data.value.autoplay || radio
        if (isAutoplayOn && queueManager.upNext.isEmpty()) {
            queueManager.current?.id?.let { checkAndExtendAutoplay(it) }
        }
    }

    /**
     * Cycle through all shuffle modes: OFF -> STANDARD -> SMART -> OFF.
     * Displays clean toasts and triggers smart recommendation loading when SMART is activated.
     */
    fun cycleShuffle(context: Context? = null) {
        val newMode = queueManager.cycleShuffleMode()
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
     * of the currently playing track/playlist, filtering strictly for original source audio
     * and ensuring zero duplicates from the playlist or different creators of the same song.
     */
    fun loadSmartShuffleRecommendations() {
        val currentSong = current ?: return
        val seeds = (listOf(currentSong) + queueManager.sourceList.shuffled().take(4)).distinctBy { it.id }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val candidates = mutableListOf<Song>()
                for (seed in seeds) {
                    val related = runCatching { StreamResolver.related(seed.id) }.getOrDefault(emptyList())
                    candidates.addAll(related)
                }

                // Collect all current songs from playlist, queue, history and current track
                val allExistingSongs = (
                    queueManager.sourceList +
                    queueManager.history +
                    listOfNotNull(queueManager.current) +
                    queueManager.userQueue.map { it.song }
                )

                val distinctFresh = mutableListOf<Song>()
                for (item in candidates) {
                    if (!item.isPureMusic() || !item.isOriginalMusicAudio()) continue
                    
                    // Reject any song that is already in playlist or queue (by ID or cleaned title)
                    val inPlaylistOrQueue = allExistingSongs.any { it.isSameTrackAs(item) }
                    if (inPlaylistOrQueue) continue

                    // Reject any duplicate within the new recommendations set itself
                    val alreadyInFresh = distinctFresh.any { it.isSameTrackAs(item) }
                    if (alreadyInFresh) continue

                    distinctFresh.add(item)
                }

                val sorted = distinctFresh
                    .sortedByDescending { it.preferredMusicSource }
                    .take(12)

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
        if (controller?.mediaItemCount == 0 || current == null) {
            play(listOf(song))
            return
        }
        queueManager.playNext(song)
        syncQueueState()
        syncToExoPlayer()
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
        syncToExoPlayer()
        appContext?.let { Toast.makeText(it, "Added to queue", Toast.LENGTH_SHORT).show() }
    }

    /** Move a queue item (by qid) to the top of the upcoming list (play next). */
    fun moveToTop(qid: String) {
        val moved = queueManager.moveToTop(qid)
        if (moved) {
            syncQueueState()
            syncToExoPlayer()
        }
    }

    /** Remove a queue item by qid. */
    fun removeItem(qid: String) {
        val removed = queueManager.removeItem(qid)
        if (removed) {
            syncQueueState()
            syncToExoPlayer()
        }
    }

    /** Jump to a specific queue item by qid. */
    fun jumpToQid(qid: String) {
        val targetSong = queueManager.jumpToQid(qid) ?: return
        syncQueueState()
        syncToExoPlayer()
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
        syncToExoPlayer()
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
        val targetSeed = seedId ?: currentSong.id
        val isAutoplayEnabled = TuneHubApp.library.data.value.autoplay || isRadioActive
        if (!isAutoplayEnabled) return
        if (queueManager.upNext.size >= 6 && !autoPlayNext) return
        if (isExtendingAutoplay) return

        isExtendingAutoplay = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val related = StreamResolver.related(targetSeed)
                    .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
                    .sortedByDescending { it.preferredMusicSource }

                withContext(Dispatchers.Main) {
                    if (related.isNotEmpty()) {
                        if (autoPlayNext && queueManager.upNext.isEmpty() && queueManager.userQueue.isEmpty()) {
                            val first = related.first()
                            val rest = related.drop(1)
                            queueManager.start(listOf(first) + rest, 0, false)
                            syncQueueState()
                            syncToExoPlayer()
                        } else {
                            queueManager.appendRadioSongs(related)
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

    fun positionMs(): Long = controller?.currentPosition ?: 0L
}
