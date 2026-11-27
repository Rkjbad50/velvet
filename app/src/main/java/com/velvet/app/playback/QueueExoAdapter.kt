package com.velvet.app.playback

import androidx.media3.common.Player

/**
 * Single adapter between [QueueState] and ExoPlayer.
 *
 * Principle:
 * ExoPlayer is strictly a downstream view of [QueueState], never the source of truth.
 * Changes apply minimal diffs (add/remove future media items) without interrupting
 * the currently playing item (no seek, no pause, no restart).
 */
object QueueExoAdapter {
    /**
     * Updates upcoming (not currently playing) ExoPlayer MediaItems in-place when enriched track
     * artwork or metadata arrives asynchronously, avoiding any interruption to active audio.
     */
    fun refreshUpcomingMetadata(
        player: Player,
        upcomingSongs: List<com.velvet.app.data.Song>,
        fallbackAlbumArt: String? = null,
    ) {
        val curIdx = player.currentMediaItemIndex
        if (curIdx < 0 || curIdx >= player.mediaItemCount) return
        val byId = upcomingSongs.associateBy { it.id }
        for (idx in (curIdx + 1) until player.mediaItemCount) {
            val existing = player.getMediaItemAt(idx)
            val updated = byId[existing.mediaId] ?: continue
            val resolvedThumb = CoverArtResolver.resolveTrackArtworkUrl(
                trackArtworkUrl = updated.thumb,
                albumArtworkUrl = fallbackAlbumArt,
                placeholderUrl = "https://i.ytimg.com/vi/${updated.id}/hqdefault.jpg",
            )
            val currentUri = existing.mediaMetadata.artworkUri?.toString()
            if (resolvedThumb != null && resolvedThumb != currentUri) {
                player.replaceMediaItem(
                    idx,
                    updated.toMediaItem(fallbackAlbumArt = fallbackAlbumArt),
                )
            }
        }
    }

    /**
     * Diffs [state] against [player] and synchronizes media items downstream.
     */
    fun syncToExoPlayer(
        player: Player,
        state: QueueState,
        playlistStart: PlaylistStart? = null,
        isRadio: Boolean = false,
        autoPlay: Boolean = true,
        startPositionMs: Long = 0L,
        fallbackAlbumArt: String? = null,
    ) {
        val currentItem = state.current
        if (currentItem == null) {
            if (player.mediaItemCount > 0) {
                player.clearMediaItems()
            }
            return
        }

        val targetUpcoming = state.userQueue + state.upNext

        // Case 1: ExoPlayer is empty or its current track does not match state.current
        val exoCurrentId = player.currentMediaItem?.mediaId
        if (player.mediaItemCount == 0 || exoCurrentId != currentItem.song.id) {
            val allItems = (listOf(currentItem) + targetUpcoming).mapIndexed { i, item ->
                item.song.toMediaItem(
                    radio = isRadio && i == 0,
                    playlistStart = if (i == 0) playlistStart else null,
                    fallbackAlbumArt = fallbackAlbumArt,
                )
            }
            val pos = if (startPositionMs > 0L) startPositionMs else 0L
            player.setMediaItems(allItems, 0, pos)
            player.prepare()
            if (autoPlay) {
                player.play()
            }
            return
        }

        // Case 2: ExoPlayer is already playing currentItem. Diff and sync only upcoming items.
        val curIdx = player.currentMediaItemIndex
        if (curIdx < 0 || curIdx >= player.mediaItemCount) {
            val allItems = (listOf(currentItem) + targetUpcoming).mapIndexed { i, item ->
                item.song.toMediaItem(
                    radio = isRadio && i == 0,
                    playlistStart = if (i == 0) playlistStart else null,
                    fallbackAlbumArt = fallbackAlbumArt,
                )
            }
            val pos = if (startPositionMs > 0L) startPositionMs else 0L
            player.setMediaItems(allItems, 0, pos)
            player.prepare()
            if (autoPlay) {
                player.play()
            }
            return
        }
        val existingUpcomingCount = (player.mediaItemCount - (curIdx + 1)).coerceAtLeast(0)
        val existingUpcomingIds = if (existingUpcomingCount > 0) {
            (curIdx + 1 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        } else {
            emptyList()
        }

        val targetUpcomingIds = targetUpcoming.map { it.song.id }

        // Minimal diff: only touch future items if order or tracks changed
        if (existingUpcomingIds != targetUpcomingIds) {
            val after = (curIdx + 1).coerceAtLeast(0)
            if (after < player.mediaItemCount) {
                player.removeMediaItems(after, player.mediaItemCount)
            }
            if (targetUpcoming.isNotEmpty()) {
                player.addMediaItems(targetUpcoming.map { it.song.toMediaItem(fallbackAlbumArt = fallbackAlbumArt) })
            }
        } else if (targetUpcoming.isNotEmpty()) {
            refreshUpcomingMetadata(player, targetUpcoming.map { it.song }, fallbackAlbumArt)
        }

        // Sync repeatMode to ExoPlayer
        val targetExoRepeat = when (state.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        if (player.repeatMode != targetExoRepeat) {
            player.repeatMode = targetExoRepeat
        }

        // ShuffleMode is managed deterministically by QueueManager; ExoPlayer's internal shuffle stays off
        if (player.shuffleModeEnabled) {
            player.shuffleModeEnabled = false
        }
    }
}
