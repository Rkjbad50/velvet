package com.velvet.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Thin wrapper over NewPipeExtractor's YouTube service. Non-suspend calls block: run them on Dispatchers.IO. */
object YouTube {
    const val FILTER_SONGS = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
    const val FILTER_VIDEOS = YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
    const val FILTER_ARTISTS = YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
    const val FILTER_ALBUMS = YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
    const val FILTER_PLAYLISTS = YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS

    private val service get() = ServiceList.YouTube

    private fun activeLanguageState(): SearchLanguageState? = runCatching {
        LanguageLocaleConfig.fromSelectedLanguages(com.velvet.app.VelvetApp.library.data.value.musicLanguages)
    }.getOrNull()

    /** A paged search bound to an active [SearchLanguageState] for consistent locale & filtering. */
    class Search(
        private val query: String,
        private val filter: String,
        private val languageState: SearchLanguageState? = null,
    ) {
        private val effectiveQuery: String = if (languageState != null && filter != FILTER_ARTISTS) {
            SearchLanguageFilter.scopeSearchQuery(query, languageState)
        } else query

        private val extractor: SearchExtractor =
            service.getSearchExtractor(effectiveQuery, listOf(filter), "").apply {
                if (languageState != null) {
                    runCatching {
                        forceLocalization(languageState.newPipeLocalization)
                        forceContentCountry(languageState.newPipeContentCountry)
                    }
                }
            }
        private var nextPage: Page? = null
        private var started = false

        var hasMore = true
            private set

        fun loadNext(): List<BrowseItem> {
            val isFirstPage = !started
            val pageResult = runCatching {
                val page = if (!started) {
                    extractor.fetchPage()
                    started = true
                    extractor.initialPage
                } else {
                    extractor.getPage(nextPage ?: return emptyList())
                }
                nextPage = page.nextPage
                hasMore = page.hasNextPage()
                page.items.mapNotNull { it.toBrowseItem(isAlbum = filter == FILTER_ALBUMS) }
            }
            val items = pageResult.getOrNull().orEmpty()
            val rawItems = when (filter) {
                FILTER_ARTISTS -> {
                    if (items.isEmpty() && isFirstPage) {
                        hasMore = false
                        YouTubeMusicInnertube.searchArtists(
                            query = effectiveQuery,
                            glOverride = languageState?.gl,
                            hlOverride = languageState?.hl,
                        ).map { BrowseItem.ArtistItem(it) }
                    } else {
                        items
                    }
                }
                FILTER_SONGS -> {
                    val baseItems = if (items.isEmpty() && isFirstPage) {
                        hasMore = false
                        YouTubeMusicInnertube.searchSongs(
                            query = effectiveQuery,
                            glOverride = languageState?.gl,
                            hlOverride = languageState?.hl,
                        ).map { BrowseItem.SongItem(it) }
                    } else {
                        items
                    }
                    baseItems.mapNotNull { item ->
                        val songItem = item as? BrowseItem.SongItem ?: return@mapNotNull item
                        val catalogSong = songItem.song.copy(
                            isYouTubeMusicCatalog = true,
                            preferredMusicSource = songItem.song.preferredMusicSource || songItem.song.hasSquareArtwork,
                        )
                        if (catalogSong.isPureMusic() && catalogSong.isOriginalMusicAudio()) {
                            BrowseItem.SongItem(catalogSong)
                        } else null
                    }.sortedByDescending { (it as? BrowseItem.SongItem)?.song?.preferredMusicSource == true }
                }
                else -> {
                    pageResult.getOrThrow()
                    items
                }
            }
            return if (languageState != null) {
                SearchLanguageFilter.filterBrowseItems(rawItems, languageState, explicitQuery = query)
            } else rawItems
        }
    }

    /** Everything at once for the "All" tab. Failures in one section don't sink the others. */
    class TopResults(
        val songs: List<Song>,
        val artists: List<ArtistRef>,
        val albums: List<AlbumRef>,
        val playlists: List<AlbumRef>,
    )

    suspend fun searchAll(
        query: String,
        languageState: SearchLanguageState? = activeLanguageState(),
    ): TopResults = coroutineScope {
        fun section(filter: String) = async(Dispatchers.IO) {
            runCatching { Search(query, filter, languageState).loadNext() }.getOrDefault(emptyList())
        }
        val songs = section(FILTER_SONGS)
        val artists = section(FILTER_ARTISTS)
        val albums = section(FILTER_ALBUMS)
        val playlists = section(FILTER_PLAYLISTS)
        val songList = songs.await().filterIsInstance<BrowseItem.SongItem>().map { it.song }
            .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
            .sortedByDescending { it.preferredMusicSource }
        val artistList = artists.await().filterIsInstance<BrowseItem.ArtistItem>().map { it.artist }
        val albumList = albums.await().filterIsInstance<BrowseItem.AlbumItem>().map { it.album }
        val playlistList = playlists.await().filterIsInstance<BrowseItem.AlbumItem>().map { it.album }
        TopResults(
            songs = if (languageState != null) SearchLanguageFilter.filterSongs(songList, languageState, explicitQuery = query) else songList,
            artists = if (languageState != null) SearchLanguageFilter.filterArtists(artistList, languageState, explicitQuery = query) else artistList,
            albums = if (languageState != null) SearchLanguageFilter.filterAlbums(albumList, languageState, explicitQuery = query) else albumList,
            playlists = if (languageState != null) SearchLanguageFilter.filterAlbums(playlistList, languageState, explicitQuery = query) else playlistList,
        )
    }

    fun suggestions(
        query: String,
        languageState: SearchLanguageState? = activeLanguageState(),
    ): List<String> {
        val extractor = service.suggestionExtractor.apply {
            if (languageState != null) {
                runCatching {
                    forceLocalization(languageState.newPipeLocalization)
                    forceContentCountry(languageState.newPipeContentCountry)
                }
            }
        }
        val raw = extractor.suggestionList(query)
        return if (languageState != null) {
            SearchLanguageFilter.filterSuggestions(raw, query, languageState)
        } else raw
    }

    fun streamInfo(videoId: String): StreamInfo =
        StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")

    fun songFrom(info: StreamInfo): Song {
        val rawUploader = info.uploaderName?.trim()?.takeIf { it.isNotBlank() }
        val descContent = runCatching { info.description?.content() }.getOrNull()
        val descHint = extractReleaseDescriptionHint(descContent)
        val topicChannel = isTopicUploaderName(rawUploader) || descHint != null
        val verifiedUploader = runCatching { info.isUploaderVerified }.getOrDefault(false)
        return Song(
            id = info.id,
            title = info.name,
            artist = cleanArtist(info.uploaderName),
            durationSec = info.duration,
            thumb = bestThumb(info.thumbnails, info.id),
            artistUrl = info.uploaderUrl,
            preferredMusicSource = topicChannel || verifiedUploader,
            isTopicRelease = topicChannel,
            isUploaderVerified = verifiedUploader,
            rawUploaderName = rawUploader,
            hasSquareArtwork = hasSquareThumb(info.thumbnails),
            releaseDescriptionHint = descHint,
        )
    }

    // ---------------------------------------------------------------- artists

    class ArtistPage(
        val artist: ArtistRef,
        val banner: String?,
        val popular: List<Song>,
        val albums: List<AlbumRef>,
        val videos: List<Song>,
    )

    suspend fun artistPage(url: String, nameHint: String): ArtistPage = coroutineScope {
        val info = async(Dispatchers.IO) { runCatching { ChannelInfo.getInfo(service, url) }.getOrNull() }
        // search by the name we already have, in parallel with the channel lookup
        val popular = async(Dispatchers.IO) {
            runCatching {
                val name = nameHint
                val found = Search(name, FILTER_SONGS).loadNext()
                    .filterIsInstance<BrowseItem.SongItem>().map { it.song }
                // keep songs actually by this artist; YouTube mixes in covers and features
                val own = found.filter { it.artistUrl == url || it.artist.contains(name, ignoreCase = true) }
                own.ifEmpty { found.take(5) }.take(10)
            }.getOrDefault(emptyList())
        }
        val channel = info.await()
        val name = channel?.name?.let(::cleanArtist) ?: nameHint
        val albums = async(Dispatchers.IO) {
            runCatching {
                val tab = channel?.tabs?.firstOrNull { it.contentFilters.contains(ChannelTabs.ALBUMS) }
                    ?: return@runCatching emptyList<AlbumRef>()
                ChannelTabInfo.getInfo(service, tab).relatedItems
                    .mapNotNull { (it as? PlaylistInfoItem)?.toAlbum(isAlbum = true) }
            }.getOrDefault(emptyList()).ifEmpty {
                // channel has no releases tab: fall back to an album search for the name
                runCatching {
                    Search(name, FILTER_ALBUMS).loadNext()
                        .filterIsInstance<BrowseItem.AlbumItem>().map { it.album }
                        .filter { it.subtitle.contains(name, ignoreCase = true) }
                }.getOrDefault(emptyList())
            }
        }
        val videos = async(Dispatchers.IO) {
            runCatching {
                val tab = channel?.tabs?.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
                    ?: return@runCatching emptyList<Song>()
                ChannelTabInfo.getInfo(service, tab).relatedItems
                    .mapNotNull { (it as? StreamInfoItem)?.toSong() }.take(12)
            }.getOrDefault(emptyList())
        }

        ArtistPage(
            artist = ArtistRef(
                url = url,
                name = name,
                thumb = channel?.avatars?.let { biggest(it) },
                subscribers = channel?.subscriberCount ?: -1,
            ),
            banner = channel?.banners?.let { biggest(it) },
            popular = popular.await(),
            albums = albums.await(),
            videos = videos.await(),
        )
    }

    // ---------------------------------------------------------------- albums & playlists

    class AlbumPage(val album: AlbumRef, val songs: List<Song>)

    suspend fun albumPage(url: String, hint: AlbumRef?): AlbumPage = withContext(Dispatchers.IO) {
        val normalized = url.replace("music.youtube.com", "www.youtube.com")
        val info = PlaylistInfo.getInfo(service, normalized)
        val songs = info.relatedItems.mapNotNull { it.toSong() }.toMutableList()
        var next = info.nextPage
        var pages = 0
        while (Page.isValid(next) && pages < MAX_PLAYLIST_PAGES) {
            val more = runCatching { PlaylistInfo.getMoreItems(service, normalized, next) }.getOrNull() ?: break
            songs += more.items.mapNotNull { it.toSong() }
            next = more.nextPage
            pages++
        }
        val album = AlbumRef(
            url = url,
            // YouTube names album playlists "Album – Title"
            title = hint?.title?.takeIf { it.isNotBlank() }
                ?: info.name.replace(Regex("""^(Album|EP|Single)\s+[–-]\s+"""), ""),
            subtitle = hint?.subtitle?.takeIf { it.isNotBlank() } ?: cleanArtist(info.uploaderName),
            thumb = hint?.thumb ?: biggest(info.thumbnails) ?: songs.firstOrNull()?.thumb,
            isAlbum = hint?.isAlbum ?: true,
        )
        AlbumPage(
            album,
            com.velvet.app.playback.CoverArtResolver.enrichAlbumTracks(
                collectionUrl = normalized,
                album = album,
                rawSongs = songs,
                languageState = activeLanguageState(),
            ),
        )
    }

    /**
     * Live YouTube Music trending charts combining InnerTube (`FEmusic_charts` + `FEmusic_explore`)
     * with NewPipeExtractor's `trending_music` kiosk in parallel, filtered for pure studio audio.
     */
    fun trendingMusic(preferredLanguages: List<String> = emptyList()): List<Song> = runBlocking {
        coroutineScope {
            val innertubeCharts = async(Dispatchers.IO) {
                runCatching { YouTubeMusicInnertube.fetchTrendingCharts(preferredLanguages, limit = 30) }
                    .getOrDefault(emptyList())
            }
            val newpipeKiosk = async(Dispatchers.IO) {
                runCatching {
                    val extractor = service.kioskList.getExtractorById("trending_music", null)
                    extractor.fetchPage()
                    extractor.initialPage.items.mapNotNull { (it as? StreamInfoItem)?.toSong() }
                        .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
                        .sortedByDescending { it.preferredMusicSource }
                }.getOrDefault(emptyList())
            }
            (innertubeCharts.await() + newpipeKiosk.await())
                .distinctBy { it.id }
                .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Pulls the video id out of any youtube.com / youtu.be / music.youtube.com link. */
    fun videoIdFromText(text: String): String? =
        Regex("""https?://\S+""").findAll(text).firstNotNullOfOrNull { videoIdFromUrl(it.value) }

    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

    // only real YouTube links: an unrelated URL with "v=" in it is ignored
    private fun videoIdFromUrl(url: String): String? {
        val uri = runCatching { java.net.URI(url.trimEnd('.', ',', ')', '!')) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in youtubeHosts) return null
        val path = uri.path.orEmpty().trim('/').split('/')
        val id = when {
            host == "youtu.be" -> path.firstOrNull()
            path.firstOrNull() in setOf("shorts", "embed", "live") -> path.getOrNull(1)
            else -> uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
        }
        return id?.takeIf(::isValidVideoId)
    }

    fun InfoItem.toBrowseItem(isAlbum: Boolean): BrowseItem? = when (this) {
        is StreamInfoItem -> toSong()?.let { BrowseItem.SongItem(it) }
        is ChannelInfoItem -> BrowseItem.ArtistItem(
            // the search result's subscriber count is misparsed (10x off); the artist page has the real one
            ArtistRef(url = url, name = cleanArtist(name), thumb = biggest(thumbnails))
        )
        is PlaylistInfoItem -> BrowseItem.AlbumItem(toAlbum(isAlbum))
        else -> null
    }

    fun StreamInfoItem.toSong(): Song? {
        if (streamType == StreamType.LIVE_STREAM) return null
        val id = runCatching { service.streamLHFactory.getId(url) }.getOrNull() ?: return null
        val rawUploader = uploaderName?.trim()?.takeIf { it.isNotBlank() }
        val topicChannel = isTopicUploaderName(rawUploader)
        return Song(
            id = id,
            title = name,
            artist = cleanArtist(uploaderName),
            durationSec = duration,
            thumb = bestThumb(thumbnails, id),
            artistUrl = uploaderUrl,
            preferredMusicSource = isUploaderVerified || topicChannel,
            isTopicRelease = topicChannel,
            isUploaderVerified = isUploaderVerified,
            rawUploaderName = rawUploader,
            hasSquareArtwork = hasSquareThumb(thumbnails),
        )
    }

    private fun PlaylistInfoItem.toAlbum(isAlbum: Boolean) = AlbumRef(
        url = url,
        title = name,
        subtitle = cleanArtist(uploaderName),
        thumb = biggest(thumbnails),
        isAlbum = isAlbum,
    )

    private fun isTopicUploaderName(name: String?): Boolean {
        val trimmed = name?.trim() ?: return false
        return trimmed.endsWith("- Topic", ignoreCase = true) ||
            trimmed.endsWith(" - Topic", ignoreCase = true)
    }

    private fun extractReleaseDescriptionHint(content: String?): String? {
        if (content.isNullOrBlank()) return null
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.contains("Provided to YouTube by", ignoreCase = true) ||
                    line.contains("Auto-generated by YouTube", ignoreCase = true)
            }
            ?.take(120)
    }

    private fun hasSquareThumb(images: List<Image>): Boolean =
        images.any { it.width > 0 && it.height > 0 && it.width == it.height } ||
            images.any { it.url.contains("lh3.googleusercontent.com", ignoreCase = true) }

    private fun cleanArtist(name: String?): String = name.orEmpty().removeSuffix(" - Topic")

    private fun biggest(images: List<Image>): String? =
        images.maxByOrNull { it.height }?.url?.let(::upscale)

    private fun bestThumb(images: List<Image>, id: String): String =
        biggest(images) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    // YouTube Music art comes as tiny "=w60-h60" googleusercontent images; ask for a big one
    private fun upscale(url: String): String =
        url.replace(Regex("""=w\d+-h\d+[^/]*$"""), "=w544-h544-l90-rj")
            .replace(Regex("""=s\d+[^/]*$"""), "=s544")

    private const val MAX_PLAYLIST_PAGES = 100
}
