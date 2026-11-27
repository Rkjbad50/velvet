package com.velvet.app.playback

import com.velvet.app.data.AlbumRef
import com.velvet.app.data.SearchLanguageState
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Deterministic cover-art resolution, progressive collection loading, and fallback helpers
 * for albums, compilations, playlists, queue items, Media3 metadata, and player surfaces.
 *
 * Fallback priority per item is strictly:
 *   trackArtworkUrl > albumArtworkUrl > videoPlaceholderUrl
 * A fetched cover matching [albumArtworkUrl] is allowed to stay when no distinct square track
 * cover exists, while any distinct valid square cover strictly wins over album/placeholder art.
 */
object CoverArtResolver {
    const val DEFAULT_LOOKAHEAD_COUNT = 10
    private const val PROGRESSIVE_BATCH_SIZE = 6

    private val SAFE_ANNOTATION_REGEX = Regex(
        """(?i)(\[(?:from|movie)[^\]]*\]|\((?:from|movie|original(?:\s+motion\s+picture)?(?:\s+soundtrack)?|with\s+jhankar(?:\s+beats)?|jhankar(?:\s+beats)?|remastered(?:\s+\d{4})?|full\s+song|audio|hd|feat\.?[^)]*|ft\.?[^)]*)\))""",
    )
    private val DISALLOWED_VARIANT_REGEX = Regex(
        """(?i)\b(remix|live|concert|cover|unplugged|acoustic|karaoke|instrumental|slowed|reverb|lo-fi|lofi|mashup|medley|club\s+mix|dj\s+mix|dj)\b""",
    )
    private val COMPILATION_TITLE_HINT_REGEX = Regex(
        """(?i)\b(various\s+artists|evergreen|hits|jhankar|romantic\s+songs|best\s+of|top\s+\d+|collection|mix|mashup|jukebox|non-?stop|greatest)\b""",
    )

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

    /** Returns true for square Google-hosted YT Music cover URLs (`lh3` / `yt3`). */
    fun isSquareHostedThumb(url: String?): Boolean {
        val clean = url?.trim()?.lowercase(Locale.ROOT) ?: return false
        return clean.contains("lh3.googleusercontent.com") || clean.contains("yt3.googleusercontent.com")
    }

    /**
     * Returns true when [url] is a low-specificity generic fallback thumbnail (e.g. `i.ytimg.com/...`)
     * rather than dedicated high-res square YouTube Music cover art (`lh3` / `yt3`).
     */
    fun isGenericVideoFallbackThumb(url: String?): Boolean {
        val clean = url?.trim()?.lowercase(Locale.ROOT) ?: return true
        if (clean.isBlank()) return true
        return clean.contains("i.ytimg.com/vi/") || clean.contains("img.youtube.com/vi/")
    }

    /**
     * Merges a newly fetched track-level thumbnail with an existing URL:
     * 1) Distinct non-generic track cover (`!= album`) always wins.
     * 2) Existing distinct non-generic track cover is never clobbered by parent album or ytimg.
     * 3) Equal-to-album non-generic square cover is allowed to stay (`album` > `i.ytimg.com`).
     * 4) Per-track video thumbnail still beats blank/missing fallback.
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
            candidate != null && !isGenericVideoFallbackThumb(candidate) -> candidate
            existing != null && !isGenericVideoFallbackThumb(existing) -> existing
            candidate != null && candidate != album -> candidate
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
     * Immediately applies any cached square artwork from [CoverArtCache] and [knownSongs]
     * plus parent album fallback without blocking UI on network calls.
     */
    fun applyInitialKnownArtwork(
        songs: List<Song>,
        albumArtworkUrl: String?,
    ): List<Song> {
        val cleanAlbum = albumArtworkUrl?.trim()?.takeIf { it.isNotBlank() }
        return songs.distinctBy { it.id }.map { song ->
            val diskCached = CoverArtCache.get(song.id)
            val memCached = getKnownSongArtwork(song.id)
            val bestKnown = mergeTrackArtwork(
                existingTrackThumb = memCached ?: song.thumb,
                candidateTrackThumb = diskCached,
                albumThumb = cleanAlbum,
                videoId = song.id,
            )
            val mergedWithAlbum = mergeTrackArtwork(
                existingTrackThumb = song.thumb,
                candidateTrackThumb = bestKnown,
                albumThumb = cleanAlbum,
                videoId = song.id,
            )
            song.copy(
                thumb = mergedWithAlbum,
                hasSquareArtwork = song.hasSquareArtwork || isSquareHostedThumb(mergedWithAlbum),
            )
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

    fun isLikelyCompilationOrMixed(album: AlbumRef, songs: List<Song>): Boolean {
        if (!album.isAlbum) return true
        val sub = album.subtitle.trim().lowercase(Locale.ROOT)
        if (sub.contains("various") || sub.contains("compilation")) return true
        if (COMPILATION_TITLE_HINT_REGEX.containsMatchIn(album.title)) return true
        val distinctPrimaryArtists = songs.asSequence()
            .map { extractPrimaryArtistForArtworkLookup(it.artist).lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() && it != "unknown artist" }
            .distinct()
            .take(4)
            .count()
        return distinctPrimaryArtists >= 3
    }

    fun cleanTitleForArtworkLookup(rawTitle: String): String =
        rawTitle.replace(SAFE_ANNOTATION_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun extractPrimaryArtistForArtworkLookup(rawArtist: String): String =
        rawArtist.split(Regex("""\s*(?:,|&|/|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bx\b)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

    private fun normalizeTokenText(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(".", "")
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Accepts a single/soundtrack song match ONLY when normalized title and primary artist
     * are an exact match and [candidateTitle] is not a remix/live/cover/karaoke/lofi variant.
     */
    fun isExactStudioSongMatch(
        sourceTitle: String,
        sourceArtist: String,
        candidateTitle: String,
        candidateArtist: String,
    ): Boolean {
        if (DISALLOWED_VARIANT_REGEX.containsMatchIn(candidateTitle)) return false
        val cleanSourceTitle = normalizeTokenText(cleanTitleForArtworkLookup(sourceTitle))
        val cleanCandidateTitle = normalizeTokenText(cleanTitleForArtworkLookup(candidateTitle))
        if (cleanSourceTitle.isBlank() || cleanSourceTitle != cleanCandidateTitle) return false

        val srcPrimary = normalizeTokenText(extractPrimaryArtistForArtworkLookup(sourceArtist))
        val candPrimary = normalizeTokenText(extractPrimaryArtistForArtworkLookup(candidateArtist))
        if (srcPrimary.isBlank() || candPrimary.isBlank()) return false
        return srcPrimary == candPrimary
    }

    /**
     * Non-blocking progressive resolution for album/compilation/playlist tracks:
     * - Runs optional fast collection browse merge once, updating immediately.
     * - Prioritizes currently visible rows + [lookaheadCount] rows ahead with no 24-track cap.
     * - Persists distinct covers into [CoverArtCache] and invokes [onSongsUpdated]/[onTrackResolved].
     * - Cooperatively stops on coroutine cancellation when leaving the screen.
     */
    suspend fun resolveAlbumArtworkProgressively(
        collectionUrl: String,
        album: AlbumRef,
        initialSongs: List<Song>,
        languageState: SearchLanguageState? = null,
        visibleWindowProvider: () -> IntRange = { 0..20 },
        lookaheadCount: Int = DEFAULT_LOOKAHEAD_COUNT,
        batchSize: Int = PROGRESSIVE_BATCH_SIZE,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        browseProvider: suspend (String) -> List<Song> = { url ->
            withTimeoutOrNull(2_400L) {
                withContext(ioDispatcher) {
                    runCatching {
                        YouTubeMusicInnertube.browseCollectionTracks(
                            urlOrBrowseId = url,
                            glOverride = languageState?.gl,
                            hlOverride = languageState?.hl,
                            limit = maxOf(initialSongs.size + 10, 120),
                        )
                    }.getOrDefault(emptyList())
                }
            }.orEmpty()
        },
        nextArtworkProvider: suspend (Song) -> String? = { track ->
            withTimeoutOrNull(2_000L) {
                withContext(ioDispatcher) {
                    runCatching {
                        YouTubeMusicInnertube.fetchTrackArtworkUrl(
                            videoId = track.id,
                            glOverride = languageState?.gl,
                            hlOverride = languageState?.hl,
                        )
                    }.getOrNull()
                }
            }
        },
        songSearchArtworkProvider: suspend (Song) -> String? = { track ->
            withTimeoutOrNull(2_500L) {
                withContext(ioDispatcher) {
                    runCatching {
                        YouTubeMusicInnertube.searchStrictSongArtwork(
                            title = track.title,
                            artist = track.artist,
                            glOverride = languageState?.gl,
                            hlOverride = languageState?.hl,
                        )
                    }.getOrNull()
                }
            }
        },
        onSongsUpdated: suspend (List<Song>) -> Unit,
        onTrackResolved: suspend (Song) -> Unit = {},
    ) = coroutineScope {
        if (initialSongs.isEmpty()) return@coroutineScope
        val working = applyInitialKnownArtwork(initialSongs, album.thumb).toMutableList()
        if (working != initialSongs) {
            onSongsUpdated(working.toList())
        }

        // Fast initial collection browse enrichment
        ensureActive()
        val browseSongs = browseProvider(collectionUrl)
        if (browseSongs.isNotEmpty()) {
            val byId = browseSongs.associateBy { it.id }
            var browseChanged = false
            val upgraded = mutableListOf<Song>()
            for (i in working.indices) {
                val song = working[i]
                val ytm = byId[song.id] ?: continue
                val mergedThumb = mergeTrackArtwork(
                    existingTrackThumb = song.thumb,
                    candidateTrackThumb = ytm.thumb,
                    albumThumb = album.thumb,
                    videoId = song.id,
                )
                val updated = song.copy(
                    thumb = mergedThumb,
                    album = song.album?.takeIf { it.isNotBlank() }
                        ?: ytm.album?.takeIf { it.isNotBlank() }
                        ?: album.title,
                    artistUrl = song.artistUrl ?: ytm.artistUrl,
                    isTopicRelease = song.isTopicRelease || ytm.isTopicRelease,
                    hasSquareArtwork = song.hasSquareArtwork || ytm.hasSquareArtwork || isSquareHostedThumb(mergedThumb),
                    isYouTubeMusicCatalog = song.isYouTubeMusicCatalog || album.isAlbum || ytm.isYouTubeMusicCatalog,
                    preferredMusicSource = song.preferredMusicSource || ytm.preferredMusicSource,
                )
                if (updated != song) {
                    working[i] = updated
                    browseChanged = true
                    if (updated.thumb != song.thumb && !updated.thumb.isNullOrBlank()) {
                        CoverArtCache.put(updated.id, updated.thumb, updated.hasSquareArtwork)
                        upgraded += updated
                    }
                }
            }
            if (browseChanged) {
                onSongsUpdated(working.toList())
                upgraded.forEach { onTrackResolved(it) }
            }
        }

        val compilationLike = isLikelyCompilationOrMixed(album, working)
        val attempted = BooleanArray(working.size)
        for (i in working.indices) {
            val s = working[i]
            val hasDistinctNonGeneric = !s.thumb.isNullOrBlank() &&
                !isGenericVideoFallbackThumb(s.thumb) &&
                s.thumb != album.thumb
            val cached = CoverArtCache.get(s.id)
            val alreadyComplete = hasDistinctNonGeneric ||
                (!compilationLike && album.isAlbum && !cached.isNullOrBlank() && !isGenericVideoFallbackThumb(cached))
            if (alreadyComplete) {
                attempted[i] = true
            }
        }

        val semaphore = Semaphore(batchSize.coerceIn(1, 8))
        while (true) {
            ensureActive()
            val window = visibleWindowProvider()
            val startIdx = (window.first - 2).coerceIn(0, working.lastIndex)
            val endIdx = (window.last + lookaheadCount).coerceIn(startIdx, working.lastIndex)

            val prioritized = ArrayList<Int>(batchSize)
            for (i in startIdx..endIdx) {
                if (!attempted[i]) {
                    prioritized.add(i)
                    if (prioritized.size >= batchSize) break
                }
            }
            if (prioritized.size < batchSize) {
                for (i in working.indices) {
                    if (!attempted[i] && i !in prioritized) {
                        prioritized.add(i)
                        if (prioritized.size >= batchSize) break
                    }
                }
            }
            if (prioritized.isEmpty()) break

            prioritized.forEach { attempted[it] = true }

            val resolvedBatch = prioritized.map { idx ->
                val song = working[idx]
                async {
                    semaphore.withPermit {
                        ensureActive()
                        val cacheHit = CoverArtCache.get(song.id)
                        val candidateFromCache = if (!cacheHit.isNullOrBlank() && cacheHit != song.thumb &&
                            !isGenericVideoFallbackThumb(cacheHit)
                        ) {
                            cacheHit
                        } else {
                            null
                        }

                        var candidate = candidateFromCache ?: nextArtworkProvider(song)
                        val nextWasDistinctSquare = !candidate.isNullOrBlank() &&
                            !isGenericVideoFallbackThumb(candidate) &&
                            candidate != album.thumb

                        if (!nextWasDistinctSquare && compilationLike) {
                            val singleReleaseArt = songSearchArtworkProvider(song)
                            if (!singleReleaseArt.isNullOrBlank() && !isGenericVideoFallbackThumb(singleReleaseArt)) {
                                candidate = singleReleaseArt
                            }
                        }

                        val finalThumb = mergeTrackArtwork(
                            existingTrackThumb = song.thumb,
                            candidateTrackThumb = candidate,
                            albumThumb = album.thumb,
                            videoId = song.id,
                        )
                        idx to finalThumb
                    }
                }
            }.awaitAll()

            ensureActive()
            var anyChanged = false
            val upgradedSongs = mutableListOf<Song>()
            for ((idx, finalThumb) in resolvedBatch) {
                val prev = working[idx]
                if (!finalThumb.isNullOrBlank()) {
                    val isSquare = prev.hasSquareArtwork || isSquareHostedThumb(finalThumb)
                    CoverArtCache.put(prev.id, finalThumb, isSquareArtwork = isSquare)
                    if (finalThumb != prev.thumb) {
                        val updated = prev.copy(thumb = finalThumb, hasSquareArtwork = isSquare)
                        working[idx] = updated
                        anyChanged = true
                        upgradedSongs += updated
                    }
                }
            }
            if (anyChanged) {
                onSongsUpdated(working.toList())
                upgradedSongs.forEach { onTrackResolved(it) }
            }
        }
    }

    /**
     * Synchronous-await helper preserved for compatibility; delegates to
     * [resolveAlbumArtworkProgressively] across ALL tracks with no 24-track cap.
     */
    suspend fun enrichAlbumTracks(
        collectionUrl: String,
        album: AlbumRef,
        rawSongs: List<Song>,
        languageState: SearchLanguageState? = null,
    ): List<Song> {
        var latest = applyInitialKnownArtwork(rawSongs, album.thumb)
        resolveAlbumArtworkProgressively(
            collectionUrl = collectionUrl,
            album = album,
            initialSongs = rawSongs,
            languageState = languageState,
            onSongsUpdated = { updated -> latest = updated },
        )
        return latest
    }
}
