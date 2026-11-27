package com.velvet.app.playback

import com.velvet.app.data.AlbumRef
import com.velvet.app.data.SearchLanguageState
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deterministic cover-art resolution and fallback helpers for albums, playlists,
 * queue items, Media3 metadata, and player surfaces.
 *
 * Fallback priority per item is strictly:
 *   trackArtworkUrl ?: albumArtworkUrl ?: placeholderUrl
 * Individual non-blank track artwork is never overridden by a parent album's artwork.
 */
object CoverArtResolver {
    /**
     * Resolves the effective artwork URL for an individual track without letting
     * a parent album cover clobber the track's own artwork.
     */
    fun resolveTrackArtworkUrl(
        trackArtworkUrl: String?,
        albumArtworkUrl: String? = null,
        placeholderUrl: String? = null,
    ): String? {
        val track = trackArtworkUrl?.trim()?.takeIf { it.isNotBlank() }
        val album = albumArtworkUrl?.trim()?.takeIf { it.isNotBlank() }
        val placeholder = placeholderUrl?.trim()?.takeIf { it.isNotBlank() }
        return track ?: album ?: placeholder
    }

    /**
     * Returns true when [url] is a low-specificity generic fallback thumbnail (e.g. `i.ytimg.com/...`)
     * rather than dedicated high-res square YouTube Music cover art (`lh3.googleusercontent.com`).
     */
    fun isGenericVideoFallbackThumb(url: String?): Boolean {
        val clean = url?.trim()?.lowercase() ?: return true
        if (clean.isBlank()) return true
        return clean.contains("i.ytimg.com/vi/") || clean.contains("img.youtube.com/vi/")
    }

    /**
     * Merges a newly fetched track-level thumbnail with an existing URL, preferring genuine
     * track-specific artwork over generic `ytimg` fallbacks or duplicated parent album covers.
     */
    fun mergeTrackArtwork(
        existingTrackThumb: String?,
        candidateTrackThumb: String?,
        albumThumb: String? = null,
        videoId: String? = null,
    ): String? {
        val existing = existingTrackThumb?.trim()?.takeIf { it.isNotBlank() }
        val candidate = candidateTrackThumb?.trim()?.takeIf { it.isNotBlank() }
        val album = albumThumb?.trim()?.takeIf { it.isNotBlank() }

        val chosen = when {
            candidate != null && !isGenericVideoFallbackThumb(candidate) && candidate != album -> candidate
            existing != null && !isGenericVideoFallbackThumb(existing) && existing != album -> existing
            candidate != null && candidate != album && isGenericVideoFallbackThumb(existing) -> candidate
            existing != null && existing != album -> existing
            candidate != null -> candidate
            else -> existing
        }
        val fallbackPlaceholder = videoId?.takeIf { it.isNotBlank() }?.let {
            "https://i.ytimg.com/vi/$it/hqdefault.jpg"
        }
        return resolveTrackArtworkUrl(chosen, album, fallbackPlaceholder)
    }

    /**
     * Applies per-track fallback ordering (`track.thumb ?: albumThumb ?: placeholder`) across a list
     * of songs while preserving every track's distinct artwork.
     */
    fun applyAlbumFallback(
        songs: List<Song>,
        albumArtworkUrl: String?,
    ): List<Song> {
        val cleanAlbum = albumArtworkUrl?.trim()?.takeIf { it.isNotBlank() }
        return songs.map { song ->
            val resolved = resolveTrackArtworkUrl(
                trackArtworkUrl = song.thumb,
                albumArtworkUrl = cleanAlbum,
                placeholderUrl = song.id.takeIf { it.isNotBlank() }?.let {
                    "https://i.ytimg.com/vi/$it/hqdefault.jpg"
                },
            )
            if (resolved == song.thumb) song else song.copy(thumb = resolved)
        }
    }

    /**
     * Returns true when a track's current artwork looks missing, generic (`i.ytimg.com`), or identical
     * to a parent album/playlist cover and may benefit from an async per-track artwork lookup.
     */
    fun shouldAttemptTrackArtworkLookup(
        song: Song,
        parentAlbumArtworkUrl: String? = null,
    ): Boolean {
        val thumb = song.thumb?.trim()?.takeIf { it.isNotBlank() } ?: return true
        if (isGenericVideoFallbackThumb(thumb)) return true
        val parent = parentAlbumArtworkUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return thumb == parent
    }

    /**
     * Enriches tracks inside an album or pre-built playlist/compilation with individual YT Music
     * track cover art (via InnerTube browse + bounded parallel per-track lookups) without ever
     * letting the parent album cover override distinct track artwork.
     */
    suspend fun enrichAlbumTracks(
        collectionUrl: String,
        album: AlbumRef,
        rawSongs: List<Song>,
        languageState: SearchLanguageState? = null,
    ): List<Song> = coroutineScope {
        val deduped = rawSongs.distinctBy { it.id }
        val browseSongs = withTimeoutOrNull(2_400L) {
            runCatching {
                YouTubeMusicInnertube.browseCollectionTracks(
                    urlOrBrowseId = collectionUrl,
                    glOverride = languageState?.gl,
                    hlOverride = languageState?.hl,
                    limit = maxOf(deduped.size + 10, 50),
                )
            }.getOrDefault(emptyList())
        }.orEmpty()
        val browseById = browseSongs.associateBy { it.id }
        val baseSongs = if (deduped.isNotEmpty()) deduped else browseSongs

        val mergedFirstPass = baseSongs.map { song ->
            val ytm = browseById[song.id]
            val mergedThumb = mergeTrackArtwork(
                existingTrackThumb = song.thumb,
                candidateTrackThumb = ytm?.thumb,
                albumThumb = album.thumb,
                videoId = song.id,
            )
            song.copy(
                thumb = mergedThumb,
                album = song.album?.takeIf { it.isNotBlank() }
                    ?: ytm?.album?.takeIf { it.isNotBlank() }
                    ?: album.title,
                artistUrl = song.artistUrl ?: ytm?.artistUrl,
                isTopicRelease = song.isTopicRelease || (ytm?.isTopicRelease == true),
                hasSquareArtwork = song.hasSquareArtwork || (ytm?.hasSquareArtwork == true),
                isYouTubeMusicCatalog = song.isYouTubeMusicCatalog || album.isAlbum || (ytm?.isYouTubeMusicCatalog == true),
                preferredMusicSource = song.preferredMusicSource ||
                    (ytm?.preferredMusicSource == true) ||
                    (album.isAlbum && (song.hasSquareArtwork || ytm?.hasSquareArtwork == true)),
            )
        }

        val needsIndividualArt = mergedFirstPass.filter { shouldAttemptTrackArtworkLookup(it, album.thumb) }
        val distinctArtists = mergedFirstPass.map { it.artist.trim().lowercase() }.distinct().size
        val looksLikeMultiSourceOrUniform = !album.isAlbum || distinctArtists > 1 ||
            mergedFirstPass.all { isGenericVideoFallbackThumb(it.thumb) || it.thumb == album.thumb }

        if (!looksLikeMultiSourceOrUniform || needsIndividualArt.isEmpty()) {
            return@coroutineScope mergedFirstPass
        }

        val semaphore = Semaphore(6)
        val resolvedArtMap = needsIndividualArt.take(24).map { track ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val fetched = withTimeoutOrNull(2_000L) {
                        runCatching {
                            YouTubeMusicInnertube.fetchTrackArtworkUrl(
                                videoId = track.id,
                                glOverride = languageState?.gl,
                                hlOverride = languageState?.hl,
                            )
                        }.getOrNull()
                    }
                    track.id to fetched
                }
            }
        }.awaitAll().toMap()

        mergedFirstPass.map { song ->
            val individual = resolvedArtMap[song.id]
            if (individual.isNullOrBlank()) {
                song
            } else {
                val finalThumb = mergeTrackArtwork(
                    existingTrackThumb = song.thumb,
                    candidateTrackThumb = individual,
                    albumThumb = album.thumb,
                    videoId = song.id,
                )
                song.copy(
                    thumb = finalThumb,
                    hasSquareArtwork = song.hasSquareArtwork || finalThumb?.contains("lh3.googleusercontent.com") == true,
                )
            }
        }
    }
}
