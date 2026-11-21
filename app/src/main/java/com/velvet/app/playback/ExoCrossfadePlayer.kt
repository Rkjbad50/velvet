package com.velvet.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.velvet.app.data.Song

/**
 * Adapter that connects an [ExoPlayer] instance to [CrossfadePlayer]
 * for use in the dual-player [CrossfadeEngine].
 */
@OptIn(UnstableApi::class)
class ExoCrossfadePlayer(
    val exo: ExoPlayer,
    override val id: String,
) : CrossfadePlayer {

    override val currentSong: Song?
        get() = exo.currentMediaItem?.toSong()

    override val durationMs: Long
        get() = exo.duration.coerceAtLeast(0L)

    override val currentPositionMs: Long
        get() = exo.currentPosition.coerceAtLeast(0L)

    override val isPlaying: Boolean
        get() = exo.isPlaying

    override val isReady: Boolean
        get() = exo.playbackState == Player.STATE_READY

    override val isEnded: Boolean
        get() = exo.playbackState == Player.STATE_ENDED

    override var volume: Float
        get() = exo.volume
        set(value) {
            exo.volume = value
        }

    override var playWhenReady: Boolean
        get() = exo.playWhenReady
        set(value) {
            exo.playWhenReady = value
        }

    override fun prepare(song: Song) {
        val mediaItem = song.toMediaItem()
        exo.setMediaItem(mediaItem)
        exo.prepare()
    }

    override fun play() {
        exo.play()
    }

    override fun pause() {
        exo.pause()
    }

    override fun stop() {
        exo.stop()
    }

    override fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs)
    }

    override fun reset() {
        exo.stop()
        exo.clearMediaItems()
    }
}
