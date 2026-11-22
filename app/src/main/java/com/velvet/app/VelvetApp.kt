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

    val sessionHistory = com.velvet.app.playback.SessionHistory(50)
    val autoplayPipeline by lazy {
        com.velvet.app.playback.AutoplayPipeline(
            sources = listOf(
                com.velvet.app.playback.CandidateSource { com.velvet.app.data.StreamResolver.related(it.id) },
                com.velvet.app.playback.CandidateSource { seed ->
                    val cluster = com.velvet.app.playback.detectCluster(seed)
                    val extra = mutableListOf<com.velvet.app.data.Song>()

                    // 1. Contextual thematic search (for Devotional / Punjabi / Artist)
                    if (cluster == com.velvet.app.playback.MusicCluster.DEVOTIONAL) {
                        runCatching {
                            val q = if (seed.title.contains("chalisa", ignoreCase = true) || seed.title.contains("hanuman", ignoreCase = true)) {
                                "Hanuman Chalisa Sankatmochan Bajrang Baan"
                            } else if (seed.title.contains("shiv", ignoreCase = true) || seed.title.contains("mahadev", ignoreCase = true)) {
                                "Shiv Tandav Mahadev Bhajan Aarti"
                            } else {
                                "${seed.title} bhajan"
                            }
                            com.velvet.app.data.YouTube.Search(q, com.velvet.app.data.YouTube.FILTER_SONGS).loadNext()
                                .filterIsInstance<com.velvet.app.data.BrowseItem.SongItem>()
                                .map { it.song }
                                .filter { it.id != seed.id && it.isPureMusic() && it.isOriginalMusicAudio() }
                        }.getOrNull()?.let { extra.addAll(it) }
                    } else {
                        val artist = seed.artist.removeSuffix(" - Topic").trim()
                        if (artist.isNotBlank()) {
                            runCatching {
                                com.velvet.app.data.YouTube.Search("$artist songs", com.velvet.app.data.YouTube.FILTER_SONGS).loadNext()
                                    .filterIsInstance<com.velvet.app.data.BrowseItem.SongItem>()
                                    .map { it.song }
                                    .filter { it.id != seed.id && it.isPureMusic() && it.isOriginalMusicAudio() }
                            }.getOrNull()?.let { extra.addAll(it) }
                        }
                    }

                    // 2. User Library & Taste chaining: include matching tracks from liked & recent
                    val libSongs = (library.data.value.liked + library.data.value.recent.take(30))
                        .filter { s ->
                            s.id != seed.id &&
                            (com.velvet.app.playback.detectCluster(s) == cluster ||
                             com.velvet.app.playback.normalizePrimaryArtist(s.artist) == com.velvet.app.playback.normalizePrimaryArtist(seed.artist))
                        }
                    extra.addAll(libSongs)
                    extra
                }
            ),
            history = sessionHistory,
            userTasteProvider = {
                val data = library.data.value
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
                )
            }
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
        downloads = Downloads(this, http, library)
        backups = Backups(this, library)

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
    }
}
