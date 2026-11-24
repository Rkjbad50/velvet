package com.velvet.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.velvet.app.data.Backups
import com.velvet.app.data.Downloads
import com.velvet.app.data.Library
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.YtDownloader
import com.velvet.app.data.isOriginalMusicAudio
import com.velvet.app.data.isPureMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

class VelvetApp : Application(), ImageLoaderFactory {
    lateinit var library: Library
        private set
    lateinit var downloads: Downloads
        private set
    lateinit var backups: Backups
        private set
    lateinit var http: OkHttpClient
        private set

    val sessionHistory = com.velvet.app.playback.SessionHistory(200)
    val recommendationEventRepo by lazy {
        com.velvet.app.recommendation.events.RecommendationEventRepository(
            backend = com.velvet.app.recommendation.events.AndroidSqliteEventBackend(this),
        )
    }
    val eventRecorder by lazy {
        com.velvet.app.recommendation.events.EventRecorder(recommendationEventRepo)
    }
    val autoplayPipeline by lazy {
        com.velvet.app.playback.AutoplayPipeline(
            sources = listOf(
                com.velvet.app.playback.CandidateSource { com.velvet.app.data.StreamResolver.related(it.id) },
            ),
            history = sessionHistory,
            albumTracksProvider = { query ->
                runCatching {
                    com.velvet.app.data.YouTube.Search(query, com.velvet.app.data.YouTube.FILTER_SONGS).loadNext()
                        .filterIsInstance<com.velvet.app.data.BrowseItem.SongItem>()
                        .map { it.song }
                        .filter { it.isPureMusic() }
                }.getOrDefault(emptyList())
            },
            artistTracksProvider = { artist ->
                runCatching {
                    com.velvet.app.data.YouTube.Search("$artist songs", com.velvet.app.data.YouTube.FILTER_SONGS).loadNext()
                        .filterIsInstance<com.velvet.app.data.BrowseItem.SongItem>()
                        .map { it.song }
                        .filter { it.isPureMusic() }
                }.getOrDefault(emptyList())
            },
            libraryTracksProvider = {
                val data = library.data.value
                data.liked + data.playlists.flatMap { it.songs }
            },
            recentCompletedProvider = {
                library.data.value.recent.take(50)
            },
            userTasteProvider = {
                val data = library.data.value
                val allUserSongs = (data.liked + data.recent.take(30) + data.playlists.flatMap { it.songs })
                com.velvet.app.playback.UserTasteProfile(
                    favoriteArtists = (data.artists.map { it.name } + data.liked.map { it.artist })
                        .map { com.velvet.app.playback.normalizePrimaryArtist(it) }.filter { it.isNotBlank() }.toSet(),
                    recentArtists = data.recent.take(30)
                        .map { com.velvet.app.playback.normalizePrimaryArtist(it.artist) }.filter { it.isNotBlank() }.toSet(),
                    playlistArtists = data.playlists.flatMap { it.songs }
                        .map { com.velvet.app.playback.normalizePrimaryArtist(it.artist) }.filter { it.isNotBlank() }.toSet(),
                    likedTrackKeys = data.liked.map { com.velvet.app.playback.canonicalKey(it.title, it.artist) }.toSet(),
                    recentTrackKeys = data.recent.take(30).map { com.velvet.app.playback.canonicalKey(it.title, it.artist) }.toSet(),
                    preferredLanguages = data.musicLanguages,
                    frequentClusters = allUserSongs.groupingBy { com.velvet.app.playback.detectCluster(it) }.eachCount(),
                    frequentMoods = allUserSongs.groupingBy { com.velvet.app.playback.detectMood(it) }.eachCount(),
                )
            },
            persistedPlayedGroupIdsProvider = { library.data.value.playedGroupIds },
            tasteProfileProvider = {
                com.velvet.app.playback.TasteEngine.getOrComputeProfile(library.data.value.listenEvents)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val locale = Locale.getDefault()
        // the content country decides which songs YouTube Music lets you stream
        NewPipe.init(
            YtDownloader(http),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
        library = Library(filesDir)
        library.onRecommendationAction = { hook ->
            when (hook) {
                is com.velvet.app.data.LibraryActionHook.LikeToggled ->
                    eventRecorder.recordLikeChanged(hook.song, hook.isNowLiked)
                is com.velvet.app.data.LibraryActionHook.PlaylistSongChanged ->
                    eventRecorder.recordPlaylistMembershipChanged(hook.playlistId, hook.song, hook.added)
                com.velvet.app.data.LibraryActionHook.SearchesCleared ->
                    eventRecorder.clearSearchIntentHistory()
                com.velvet.app.data.LibraryActionHook.ListenEventsCleared ->
                    eventRecorder.clearAllEvents()
            }
        }
        downloads = Downloads(this, http, library)
        backups = Backups(this, library)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (library.loadedCleanly) {
                runCatching {
                    recommendationEventRepo.migrateLegacyListenEventsIfNeeded(library.data.value.listenEvents)
                    recommendationEventRepo.pruneOldEvents()
                }
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            library.data.map { it.dataSaver }.distinctUntilChanged().collect { StreamResolver.dataSaver = it }
        }

        if (library.data.value.autoUpdateCheck) {
            com.velvet.app.data.UpdateCheckJobService.schedule(this)
        }
    }

    // Artwork URLs never change, so ignore the servers' short cache headers and keep images on disk
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(http)
        .crossfade(150)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(200L * 1024 * 1024).build() }
        .build()

    companion object {
        lateinit var instance: VelvetApp
            private set

        val library: Library get() = instance.library
        val downloads: Downloads get() = instance.downloads
        val backups: Backups get() = instance.backups
        val http: OkHttpClient get() = instance.http
        val sessionHistory: com.velvet.app.playback.SessionHistory get() = instance.sessionHistory
        val autoplayPipeline: com.velvet.app.playback.AutoplayPipeline get() = instance.autoplayPipeline
        val recommendationEventRepo: com.velvet.app.recommendation.events.RecommendationEventRepository
            get() = instance.recommendationEventRepo
        val eventRecorder: com.velvet.app.recommendation.events.EventRecorder
            get() = instance.eventRecorder

        fun eventRecorderOrNull(): com.velvet.app.recommendation.events.EventRecorder? =
            if (::instance.isInitialized) runCatching { instance.eventRecorder }.getOrNull() else null
    }
}
