package com.velvet.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.velvet.app.R
import com.velvet.app.VelvetApp
import com.velvet.app.data.StreamResolver
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.LastErrorTracker
import com.velvet.app.data.isDuplicateOf
import com.velvet.app.data.isValidVideoId
import com.velvet.app.ui.MainActivity
import com.velvet.app.ui.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * The invisible "backend": an ExoPlayer that keeps playing with the screen off, exposed
 * through a MediaSession so the notification, lock screen and headphones can control it.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requestNumber = AtomicLong()

    private var retriedId: String? = null
    private var failuresInARow = 0
    private var announcedSessionId = C.AUDIO_SESSION_ID_UNSET
    private val healingLadder = PlaybackHealingLadder()

    /** Bumped whenever the queue is replaced. */
    private var queueGeneration = 0

    // Crossfade automix state
    private var crossfadeMonitorJob: Job? = null
    private var fadeInJob: Job? = null
    private var isFadingOut = false

    // Gap measurement between track A and B (for debug diagnostics)
    private var lastAudibleTimestampA = 0L
    private var lastAudibleTrackId: String? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_velvet_mono) },
        )

        // The stream is resolved first, so the cache key names the exact audio file (rendition);
        // downloaded songs come back as file:// and skip the streaming cache entirely.
        // The app's OkHttp client keeps warm HTTP/2 connections to googlevideo.
        val cached = CacheDataSource.Factory()
            .setCache(cache(this))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(VelvetApp.http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val routed = DefaultDataSource.Factory(this, cached)
        val resolving = ResolvingDataSource.Factory(routed) { spec -> resolve(spec) }

        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // start after half a second of audio instead of the default 2.5 s
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 60_000, 500, 2_000)
                    .build()
            )
            .build()
        exo.addListener(listener)
        player = exo
        onAudioSession(exo.audioSessionId)
        scope.launch {
            VelvetApp.library.data.map { it.eq }.distinctUntilChanged().collect { AudioFx.apply(it) }
        }
        // the cache before rendition-keyed entries; its keys can't be trusted any more
        scope.launch(Dispatchers.IO) { File(cacheDir, "media").deleteRecursively() }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, exo)
            .setCallback(callback)
            .setSessionActivity(openApp)
            .build()

        monitorCrossfade()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        persistCurrentState()
        exo.pause()
        exo.stop()
        exo.clearMediaItems()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        persistCurrentState()
        crossfadeMonitorJob?.cancel()
        fadeInJob?.cancel()
        scope.cancel()
        AudioFx.release()
        SleepTimer.cancel()
        announce(announcedSessionId, open = false)
        player = null
        session?.run {
            exo.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun persistCurrentState() {
        if (!::exo.isInitialized) return
        val currentSong = exo.currentMediaItem?.toSong() ?: return
        PlaybackStateManager.save(
            context = this,
            song = currentSong,
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            durationMs = exo.duration.coerceAtLeast(0L),
            upNext = com.velvet.app.ui.PlayerConnection.upNext.map { it.song },
            userQueue = com.velvet.app.ui.PlayerConnection.userQueue.map { it.song },
            history = com.velvet.app.ui.PlayerConnection.history.map { it.song },
            playlistId = com.velvet.app.ui.PlayerConnection.currentPlaylistId,
            isRadio = com.velvet.app.ui.PlayerConnection.isRadioActive,
            shuffleMode = com.velvet.app.ui.PlayerConnection.shuffleMode,
        )
    }

    /** Effects and external equalizers follow the player's real audio session. */
    private fun onAudioSession(sessionId: Int) {
        if (sessionId == announcedSessionId) return
        announce(announcedSessionId, open = false)
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
            announce(sessionId, open = true)
            AudioFx.attach(sessionId)
        }
        announcedSessionId = sessionId
    }

    // Lets system / third-party equalizers attach to our audio
    private fun announce(sessionId: Int, open: Boolean) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        val action = if (open) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        runCatching {
            sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            )
        }
    }

    /** Runs on ExoPlayer's loader thread, so the blocking lookup is fine here. */
    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SONG_SCHEME) return spec
        val id = spec.uri.lastPathSegment?.takeIf(::isValidVideoId) ?: throw IOException("Invalid song")

        VelvetApp.downloads.fileFor(id)?.let { return spec.buildUpon().setUri(Uri.fromFile(it)).build() }

        val stream = try {
            StreamResolver.resolve(id)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Couldn't load stream for $id", e)
        }
        // Same request shape NewPipe's player uses for googlevideo URLs
        return spec.buildUpon()
            .setUri(stream.url + "&rn=" + requestNumber.incrementAndGet())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(POST_BODY)
            .setHttpRequestHeaders(spec.httpRequestHeaders + stream.headers)
            .setKey("$CACHE_KEY_VERSION:$id:${stream.rendition}")
            .build()
    }

    private val callback = object : MediaSession.Callback {
        // Items from controllers arrive without their URI: rebuild it from a validated media id
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.filter { isValidVideoId(it.mediaId) }
                .map { it.buildUpon().setUri(songUri(it.mediaId)).build() }
                .toMutableList()
        )
    }

    private val listener = object : Player.Listener {
        private var countedPlaylistStart: String? = null

        override fun onEvents(player: Player, events: Player.Events) {
            if (!player.isPlaying) return
            val start = player.currentMediaItem?.playlistStart() ?: return
            if (start.token == countedPlaylistStart) return
            countedPlaylistStart = start.token
            VelvetApp.library.recordPlaylistPlay(start.playlistId)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = onAudioSession(audioSessionId)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                queueGeneration++
            }
            if (mediaItem.mediaId != retriedId) retriedId = null
            VelvetApp.library.addRecent(mediaItem.toSong())
            VelvetApp.sessionHistory.record(mediaItem.toSong())
            trimPlayedHistory()
            prefetchNext()

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                if (lastAudibleTimestampA == 0L) {
                    lastAudibleTimestampA = System.currentTimeMillis()
                }
                val crossfadeSec = VelvetApp.library.data.value.crossfadeSeconds
                if (crossfadeSec > 0f) {
                    fadeInTrack(crossfadeSec)
                } else {
                    isFadingOut = false
                    exo.volume = 1.0f
                }
            } else {
                // Manual change by user (Seek, Next, Previous, Jump) — DO NOT crossfade
                lastAudibleTimestampA = 0L
                lastAudibleTrackId = null
                isFadingOut = false
                fadeInJob?.cancel()
                exo.volume = 1.0f
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {}

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                failuresInARow = 0
                val curId = exo.currentMediaItem?.mediaId
                curId?.let { id ->
                    healingLadder.onPlaybackSuccess(id)
                }
                if (lastAudibleTimestampA > 0L) {
                    val gapMs = (System.currentTimeMillis() - lastAudibleTimestampA).coerceAtLeast(0L)
                    DiagnosticsLogger.i(
                        "Crossfade",
                        "Track transition gap measured: ${gapMs}ms between '$lastAudibleTrackId' and '$curId'",
                    )
                    lastAudibleTimestampA = 0L
                    lastAudibleTrackId = null
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                lastAudibleTimestampA = System.currentTimeMillis()
                lastAudibleTrackId = exo.currentMediaItem?.mediaId
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.onSongEndPause()
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private fun handleError(error: PlaybackException) {
        val id = exo.currentMediaItem?.mediaId ?: return
        healingLadder.selfHealingEnabled = VelvetApp.library.data.value.selfHealingEnabled
        failuresInARow++
        LastErrorTracker.record(error, videoId = id)

        if (!healingLadder.selfHealingEnabled) {
            if (isTemporaryOutage(error)) {
                toast(if (isNetworkDown(error)) "No connection" else "YouTube isn't responding. Try again in a moment")
                return
            }
            if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                exo.pause()
                toast("Playback keeps failing. Check your connection, then press play")
                failuresInARow = 0
                return
            }
            val title = exo.currentMediaItem?.mediaMetadata?.title ?: "song"
            toast("Couldn't play $title")
            if (exo.hasNextMediaItem()) {
                exo.seekToNextMediaItem()
                exo.prepare()
                exo.play()
            }
            return
        }

        val category = PlaybackHealingLadder.classifyError(error)
        val decision = healingLadder.handleFailure(
            videoId = id,
            errorCategory = category,
            error = error,
            host = LastErrorTracker.lastError.value?.urlHost,
        )

        when (decision) {
            is HealingDecision.NoAction -> {
                val title = exo.currentMediaItem?.mediaMetadata?.title ?: "song"
                toast("Couldn't play $title")
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                }
            }
            is HealingDecision.RetryTransient -> {
                scope.launch {
                    delay(decision.backoffMs)
                    exo.prepare()
                    exo.play()
                }
            }
            is HealingDecision.InvalidateAndReResolve -> {
                StreamResolver.invalidate(decision.videoId)
                reloadQueue(exo, resume = true)
            }
            is HealingDecision.TryNextCandidate -> {
                StreamResolver.invalidate(decision.videoId)
                reloadQueue(exo, resume = true)
            }
            is HealingDecision.TryNextClient -> {
                StreamResolver.togglePreferredClient()
                StreamResolver.invalidate(decision.videoId)
                reloadQueue(exo, resume = true)
            }
            is HealingDecision.SkipTrack -> {
                val title = exo.currentMediaItem?.mediaMetadata?.title ?: "song"
                toast("Couldn't play $title. Skipping to next...")
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                }
            }
            is HealingDecision.CircuitBreakerTrip -> {
                exo.pause()
                if (decision.showBanner) {
                    PlayerConnection.showStreamingBrokenBanner(decision.bannerMessage)
                }
            }
        }
    }

    private fun isNetworkDown(error: PlaybackException) =
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    private fun isTemporaryOutage(error: PlaybackException): Boolean {
        if (isNetworkDown(error)) return true
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return http != null && (http.responseCode == 429 || http.responseCode >= 500)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Resolve the next song's stream early so skipping is instant. */
    private fun prefetchNext() {
        val next = exo.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val id = exo.getMediaItemAt(next).mediaId
        if (VelvetApp.downloads.fileFor(id) != null) return
        StreamResolver.prefetch(id)
    }

    /** Keeps at most [HISTORY_KEPT] already-played songs in the queue so long sessions stay light. */
    private fun trimPlayedHistory() {
        val index = exo.currentMediaItemIndex
        if (index > HISTORY_KEPT + 10) exo.removeMediaItems(0, index - HISTORY_KEPT)
    }



    private fun monitorCrossfade() {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = scope.launch {
            while (isActive) {
                delay(50)
                if (!::exo.isInitialized || !exo.isPlaying) continue
                val crossfadeSec = VelvetApp.library.data.value.crossfadeSeconds
                if (crossfadeSec <= 0f) {
                    if (isFadingOut) {
                        isFadingOut = false
                        exo.volume = 1.0f
                    }
                    continue
                }

                val duration = exo.duration
                val position = exo.currentPosition
                if (duration <= 0 || position <= 0) continue

                val remainingMs = duration - position
                val fadeMs = (crossfadeSec * 1000L).toLong()

                if (remainingMs in 1..fadeMs && exo.hasNextMediaItem()) {
                    isFadingOut = true
                    val progress = (remainingMs.toFloat() / fadeMs.toFloat()).coerceIn(0.01f, 1f)
                    val smoothVol = kotlin.math.sin(progress * (Math.PI / 2.0)).toFloat().coerceIn(0.01f, 1f)
                    exo.volume = smoothVol
                    if (smoothVol <= 0.05f && lastAudibleTimestampA == 0L) {
                        lastAudibleTimestampA = System.currentTimeMillis()
                        lastAudibleTrackId = exo.currentMediaItem?.mediaId
                    }
                } else if (isFadingOut && remainingMs > fadeMs) {
                    isFadingOut = false
                    exo.volume = 1.0f
                }
            }
        }
    }

    private fun fadeInTrack(crossfadeSec: Float) {
        fadeInJob?.cancel()
        fadeInJob = scope.launch {
            isFadingOut = false
            val fadeDurationMs = (crossfadeSec * 800L).toLong().coerceIn(400L, 4000L)
            val steps = 30
            val stepDelay = (fadeDurationMs / steps).coerceAtLeast(15L)
            for (i in 0..steps) {
                val progress = (i.toFloat() / steps).coerceIn(0f, 1f)
                val smoothVol = kotlin.math.sin(progress * (Math.PI / 2.0)).toFloat().coerceIn(0.05f, 1f)
                exo.volume = smoothVol
                delay(stepDelay)
            }
            exo.volume = 1.0f
        }
    }

    companion object {
        private val POST_BODY = byteArrayOf(0x78, 0)
        private const val HISTORY_KEPT = 50
        private const val MAX_FAILURES_IN_A_ROW = 3
        private const val CACHE_KEY_VERSION = "v2"
        private const val CACHE_BYTES = 512L * 1024 * 1024

        /** The live player, for the sleep timer and equalizer. Null when the service isn't running. */
        var player: ExoPlayer? = null
            private set

        private var sharedCache: SimpleCache? = null

        // SimpleCache allows one instance per folder per process, and the service can be recreated
        @Synchronized
        internal fun cache(context: Context): SimpleCache = sharedCache ?: SimpleCache(
            File(context.cacheDir, "media2"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        ).also { sharedCache = it }

        fun cacheBytes(): Long = sharedCache?.cacheSpace ?: 0L

        /** Reloads the queue at the same spot, e.g. so a quality switch takes effect right away. */
        fun reloadCurrent() {
            val p = player ?: return
            reloadQueue(p, resume = p.playWhenReady)
        }

        /** Clears stream resolver in-memory cache and ExoPlayer cache for current playback. */
        fun clearStreamCache() {
            StreamResolver.clearCache()
            runCatching {
                sharedCache?.keys?.forEach { key ->
                    sharedCache?.removeResource(key)
                }
            }
        }

        /** Invalidate current song stream and reload/retry playback immediately. */
        fun reResolveAndRetryCurrent() {
            val p = player ?: return
            val currentId = p.currentMediaItem?.mediaId
            if (currentId != null) {
                StreamResolver.invalidate(currentId)
            }
            reloadQueue(p, resume = true)
        }

        // a fresh media source for the current song, at the same position
        private fun reloadQueue(p: ExoPlayer, resume: Boolean) {
            if (p.mediaItemCount == 0) return
            val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
            val index = p.currentMediaItemIndex
            val position = p.currentPosition
            p.setMediaItems(items, index, position)
            p.prepare()
            p.playWhenReady = resume
        }
    }
}
