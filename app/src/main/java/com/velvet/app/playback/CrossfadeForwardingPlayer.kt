package com.velvet.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A [ForwardingPlayer] that acts as the single unified player exposed to MediaSession,
 * notification, lock screen, and UI. It dynamically routes all playback controls
 * and property queries to the currently active [ExoPlayer].
 */
@OptIn(UnstableApi::class)
class CrossfadeForwardingPlayer(
    initialPlayer: ExoPlayer,
    var onUserPause: (() -> Unit)? = null,
    var onUserResume: (() -> Unit)? = null,
    var onUserSeek: ((Long) -> Unit)? = null,
) : ForwardingPlayer(initialPlayer) {

    var activePlayer: ExoPlayer = initialPlayer
        private set

    private val externalListeners = CopyOnWriteArraySet<Player.Listener>()

    private val internalListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player === activePlayer) {
                for (l in externalListeners) {
                    l.onEvents(this@CrossfadeForwardingPlayer, events)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (activePlayer.currentMediaItem == mediaItem) {
                for (l in externalListeners) {
                    l.onMediaItemTransition(mediaItem, reason)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            for (l in externalListeners) {
                l.onPlaybackStateChanged(playbackState)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            for (l in externalListeners) {
                l.onIsPlayingChanged(isPlaying)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            for (l in externalListeners) {
                l.onPlayerError(error)
            }
        }
    }

    fun attachTo(player: ExoPlayer) {
        player.addListener(internalListener)
    }

    fun setActive(newActive: ExoPlayer) {
        if (activePlayer === newActive) return
        activePlayer = newActive
        val currentItem = activePlayer.currentMediaItem
        for (l in externalListeners) {
            l.onMediaItemTransition(currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            l.onPlaybackStateChanged(activePlayer.playbackState)
            l.onPlayWhenReadyChanged(activePlayer.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            l.onIsPlayingChanged(activePlayer.isPlaying)
        }
    }

    override fun addListener(listener: Player.Listener) {
        externalListeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        externalListeners.remove(listener)
    }

    override fun play() {
        onUserResume?.invoke()
        activePlayer.play()
    }

    override fun pause() {
        onUserPause?.invoke()
        activePlayer.pause()
    }

    override fun prepare() = activePlayer.prepare()
    override fun stop() = activePlayer.stop()
    override fun release() {
        activePlayer.release()
    }

    override fun seekTo(positionMs: Long) {
        onUserSeek?.invoke(positionMs)
        activePlayer.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        onUserSeek?.invoke(positionMs)
        activePlayer.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekBack() = activePlayer.seekBack()
    override fun seekForward() = activePlayer.seekForward()

    override fun getPlaybackState(): Int = activePlayer.playbackState
    override fun getPlayWhenReady(): Boolean = activePlayer.playWhenReady
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) onUserResume?.invoke() else onUserPause?.invoke()
        activePlayer.playWhenReady = playWhenReady
    }
    override fun isPlaying(): Boolean = activePlayer.isPlaying
    override fun isLoading(): Boolean = activePlayer.isLoading
    override fun getPlayerError(): PlaybackException? = activePlayer.playerError

    override fun getCurrentPosition(): Long = activePlayer.currentPosition
    override fun getDuration(): Long = activePlayer.duration
    override fun getBufferedPosition(): Long = activePlayer.bufferedPosition
    override fun getContentPosition(): Long = activePlayer.contentPosition
    override fun getContentDuration(): Long = activePlayer.contentDuration
    override fun getTotalBufferedDuration(): Long = activePlayer.totalBufferedDuration

    override fun getCurrentMediaItem(): MediaItem? = activePlayer.currentMediaItem
    override fun getMediaItemCount(): Int = activePlayer.mediaItemCount
    override fun getMediaItemAt(index: Int): MediaItem = activePlayer.getMediaItemAt(index)
    override fun getCurrentMediaItemIndex(): Int = activePlayer.currentMediaItemIndex
    override fun hasNextMediaItem(): Boolean = activePlayer.hasNextMediaItem()
    override fun hasPreviousMediaItem(): Boolean = activePlayer.hasPreviousMediaItem()
    override fun getNextMediaItemIndex(): Int = activePlayer.nextMediaItemIndex
    override fun getPreviousMediaItemIndex(): Int = activePlayer.previousMediaItemIndex

    override fun getCurrentTimeline(): Timeline = activePlayer.currentTimeline
    override fun getCurrentTracks(): Tracks = activePlayer.currentTracks
    override fun getTrackSelectionParameters(): TrackSelectionParameters = activePlayer.trackSelectionParameters
    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        activePlayer.trackSelectionParameters = parameters
    }

    override fun getAvailableCommands(): Player.Commands = activePlayer.availableCommands
    override fun isCommandAvailable(command: Int): Boolean = activePlayer.isCommandAvailable(command)

    override fun getRepeatMode(): Int = activePlayer.repeatMode
    override fun setRepeatMode(repeatMode: Int) {
        activePlayer.repeatMode = repeatMode
    }
    override fun getShuffleModeEnabled(): Boolean = activePlayer.shuffleModeEnabled
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        activePlayer.shuffleModeEnabled = shuffleModeEnabled
    }

    override fun getAudioAttributes(): AudioAttributes = activePlayer.audioAttributes
    override fun getVolume(): Float = activePlayer.volume
    override fun setVolume(volume: Float) {
        activePlayer.volume = volume
    }

    override fun getPlaybackParameters(): PlaybackParameters = activePlayer.playbackParameters
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        activePlayer.playbackParameters = playbackParameters
    }
    override fun getPlaybackSuppressionReason(): Int = activePlayer.playbackSuppressionReason

    override fun getDeviceInfo(): DeviceInfo = activePlayer.deviceInfo
    override fun getDeviceVolume(): Int = activePlayer.deviceVolume
    override fun isDeviceMuted(): Boolean = activePlayer.isDeviceMuted
    override fun setDeviceVolume(volume: Int, flags: Int) = activePlayer.setDeviceVolume(volume, flags)
    override fun setDeviceMuted(muted: Boolean, flags: Int) = activePlayer.setDeviceMuted(muted, flags)

    override fun setMediaItem(mediaItem: MediaItem) = activePlayer.setMediaItem(mediaItem)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = activePlayer.setMediaItem(mediaItem, startPositionMs)
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = activePlayer.setMediaItem(mediaItem, resetPosition)
    override fun setMediaItems(mediaItems: List<MediaItem>) = activePlayer.setMediaItems(mediaItems)
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = activePlayer.setMediaItems(mediaItems, resetPosition)
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = activePlayer.setMediaItems(mediaItems, startIndex, startPositionMs)

    override fun addMediaItem(mediaItem: MediaItem) = activePlayer.addMediaItem(mediaItem)
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = activePlayer.addMediaItem(index, mediaItem)
    override fun addMediaItems(mediaItems: List<MediaItem>) = activePlayer.addMediaItems(mediaItems)
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = activePlayer.addMediaItems(index, mediaItems)

    override fun removeMediaItem(index: Int) = activePlayer.removeMediaItem(index)
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = activePlayer.removeMediaItems(fromIndex, toIndex)
    override fun clearMediaItems() = activePlayer.clearMediaItems()
}
