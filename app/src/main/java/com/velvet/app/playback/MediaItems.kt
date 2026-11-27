package com.velvet.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.velvet.app.data.BoundedCache
import com.velvet.app.data.Song

/** Queue items carry this placeholder URI; PlaybackService swaps in the real stream URL at load time. */
const val SONG_SCHEME = "velvet"
private const val EXTRA_DURATION = "dur"
private const val EXTRA_ARTIST_URL = "artist_url"
private const val EXTRA_RADIO = "radio"
private const val EXTRA_PLAYLIST_ID = "playlist_id"
private const val EXTRA_PLAYLIST_START = "playlist_start"

data class PlaylistStart(val playlistId: String, val token: String)

/** UI and service share a process, so keep full Song objects instead of relying on metadata round trips. */
private val knownSongs = BoundedCache<String, Song>(2000)

fun songUri(id: String): Uri = Uri.Builder().scheme(SONG_SCHEME).authority("song").appendPath(id).build()

/**
 * Updates cached artwork in [knownSongs] when asynchronous track-level cover art is resolved.
 */
fun updateKnownSongArtwork(songId: String, thumbUrl: String) {
    if (songId.isBlank() || thumbUrl.isBlank()) return
    val existing = knownSongs[songId] ?: return
    val merged = CoverArtResolver.mergeTrackArtwork(
        existingTrackThumb = existing.thumb,
        candidateTrackThumb = thumbUrl,
        videoId = songId,
    ) ?: return
    if (merged != existing.thumb) {
        knownSongs[songId] = existing.copy(
            thumb = merged,
            hasSquareArtwork = existing.hasSquareArtwork || merged.contains("lh3.googleusercontent.com"),
        )
    }
}

/**
 * Pure JVM helper resolving per-track artwork for Media3 metadata without clobbering
 * individual track covers with album artwork or stale cached placeholders.
 */
internal fun resolveMediaItemArtworkUrl(
    song: Song,
    existingCached: Song?,
    fallbackAlbumArt: String? = null,
): String? = CoverArtResolver.mergeTrackArtwork(
    existingTrackThumb = existingCached?.thumb,
    candidateTrackThumb = song.thumb,
    albumThumb = fallbackAlbumArt,
    videoId = song.id,
)

/**
 * Pure JVM reconciliation helper used when mapping a Media3 [MediaItem] back to [Song],
 * ensuring neither cached entries nor MediaMetadata override richer per-track artwork.
 */
internal fun reconcileSongWithMediaArtwork(
    mediaId: String,
    cached: Song?,
    mediaTitle: String,
    mediaArtist: String,
    mediaAlbum: String?,
    mediaDurationSec: Long,
    mediaThumb: String?,
    mediaArtistUrl: String?,
): Song {
    val cleanMediaThumb = mediaThumb?.trim()?.takeIf { it.isNotBlank() }
    if (cached != null) {
        val mergedThumb = CoverArtResolver.mergeTrackArtwork(
            existingTrackThumb = cached.thumb,
            candidateTrackThumb = cleanMediaThumb,
            videoId = mediaId,
        )
        return if (mergedThumb != cached.thumb) cached.copy(thumb = mergedThumb) else cached
    }
    return Song(
        id = mediaId,
        title = mediaTitle,
        artist = mediaArtist,
        album = mediaAlbum,
        durationSec = mediaDurationSec,
        thumb = cleanMediaThumb ?: "https://i.ytimg.com/vi/$mediaId/hqdefault.jpg",
        artistUrl = mediaArtistUrl,
    )
}

/**
 * [radio] marks the seed of a "song radio": the service keeps such a queue going with related
 * songs even when autoplay is off.
 */
fun Song.toMediaItem(
    radio: Boolean = false,
    playlistStart: PlaylistStart? = null,
    fallbackAlbumArt: String? = null,
): MediaItem {
    val existing = knownSongs[id]
    val resolvedThumb = resolveMediaItemArtworkUrl(
        song = this,
        existingCached = existing,
        fallbackAlbumArt = fallbackAlbumArt,
    )
    val enrichedSong = if (resolvedThumb != thumb) copy(thumb = resolvedThumb) else this
    knownSongs[id] = enrichedSong
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(songUri(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(resolvedThumb?.let(Uri::parse))
                .setExtras(
                    Bundle().apply {
                        putLong(EXTRA_DURATION, durationSec)
                        putString(EXTRA_ARTIST_URL, artistUrl)
                        putBoolean(EXTRA_RADIO, radio)
                        if (playlistStart != null) {
                            putString(EXTRA_PLAYLIST_ID, playlistStart.playlistId)
                            putString(EXTRA_PLAYLIST_START, playlistStart.token)
                        }
                    }
                )
                .build()
        )
        .build()
}

fun MediaItem.isRadio(): Boolean = mediaMetadata.extras?.getBoolean(EXTRA_RADIO, false) == true

internal fun MediaItem.playlistStart(): PlaylistStart? {
    val extras = mediaMetadata.extras ?: return null
    val id = extras.getString(EXTRA_PLAYLIST_ID) ?: return null
    val token = extras.getString(EXTRA_PLAYLIST_START) ?: return null
    return PlaylistStart(id, token)
}

fun MediaItem.toSong(): Song = reconcileSongWithMediaArtwork(
    mediaId = mediaId,
    cached = knownSongs[mediaId],
    mediaTitle = mediaMetadata.title?.toString().orEmpty(),
    mediaArtist = mediaMetadata.artist?.toString().orEmpty(),
    mediaAlbum = mediaMetadata.albumTitle?.toString(),
    mediaDurationSec = mediaMetadata.extras?.getLong(EXTRA_DURATION, -1) ?: -1,
    mediaThumb = mediaMetadata.artworkUri?.toString(),
    mediaArtistUrl = mediaMetadata.extras?.getString(EXTRA_ARTIST_URL),
)
