package com.velvet.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    val trackFeatureRepository by lazy {
        com.velvet.app.recommendation.features.TrackFeatureRepository()
    }
    val userProfileRepository by lazy {
        com.velvet.app.recommendation.profile.DefaultUserProfileRepository(
            eventRepository = recommendationEventRepo,
            libraryDataProvider = { library.data.value },
            builder = com.velvet.app.recommendation.profile.TasteProfileBuilder(trackFeatureRepository),
        )
    }

    val libraryIndex by lazy {
        com.velvet.app.recommendation.candidates.LibraryIndex(trackFeatureRepository)
    }

    val recommendationEngine: com.velvet.app.recommendation.engine.DefaultRecommendationEngine by lazy {
        // Fast native YT Music InnerTube first (~200-350ms), bounded NewPipe supplement only when sparse.
        val searchSongsProvider: suspend (String) -> List<com.velvet.app.data.Song> = { query ->
            val itSongs = runCatching { com.velvet.app.data.YouTubeMusicInnertube.searchSongs(query) }
                .getOrDefault(emptyList())
            val npSongs = if (itSongs.size < 6) {
                kotlinx.coroutines.withTimeoutOrNull(1_300L) {
                    runCatching {
                        com.velvet.app.data.YouTube.Search(query, com.velvet.app.data.YouTube.FILTER_SONGS)
                            .loadNext()
                            .filterIsInstance<com.velvet.app.data.BrowseItem.SongItem>()
                            .map { it.song }
                    }.getOrDefault(emptyList())
                }.orEmpty()
            } else emptyList()
            (itSongs + npSongs).distinctBy { it.id }
        }
        val sources = listOf(
            com.velvet.app.recommendation.candidates.InstantTrackCatalogCandidateSource(
                extraSongsProvider = { libraryIndex.snapshot.allVerifiedSongs },
                featureRepository = trackFeatureRepository,
            ),
            com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource(
                relatedProvider = { seed ->
                    runCatching {
                        StreamResolver.relatedWithContinuation(seed.id, seedHint = seed, loadNextPage = true)
                    }.getOrDefault(emptyList())
                },
            ),
            com.velvet.app.recommendation.candidates.SameReleaseCandidateSource(
                releaseTracksProvider = searchSongsProvider,
            ),
            com.velvet.app.recommendation.candidates.ArtistCatalogueCandidateSource(
                artistTracksProvider = { artist -> searchSongsProvider("$artist songs") },
            ),
            com.velvet.app.recommendation.candidates.CollaboratorCandidateSource(
                artistTracksProvider = { artist -> searchSongsProvider("$artist songs") },
            ),
            com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource(
                querySearchProvider = searchSongsProvider,
            ),
            com.velvet.app.recommendation.candidates.LibraryTrackCandidateSource(
                libraryTracksProvider = {
                    val data = library.data.value
                    data.liked + data.playlists.flatMap { it.songs }
                },
                featureRepository = trackFeatureRepository,
            ),
            com.velvet.app.recommendation.candidates.SessionMomentumCandidateSource(
                recentSessionSongsProvider = { library.data.value.recent.take(50) },
                featureRepository = trackFeatureRepository,
            ),
            com.velvet.app.recommendation.candidates.AudioNeighbourCandidateSource(enabled = false),
            com.velvet.app.recommendation.candidates.SearchIntentCandidateSource(
                querySearchProvider = searchSongsProvider,
            ),
        )
        com.velvet.app.recommendation.engine.DefaultRecommendationEngine(
            candidatePoolBuilder = com.velvet.app.recommendation.candidates.CandidatePoolBuilder(
                sources = sources,
                featureRepository = trackFeatureRepository,
                perSourceTimeoutMs = 3_200L,
                overallDeadlineMs = 3_600L,
                enableEarlyQuorum = true,
                earlyQuorumMinCandidates = 10,
                earlyQuorumGraceMs = 150L,
            ),
            userProfileRepository = userProfileRepository,
            featureRepository = trackFeatureRepository,
        )
    }

    val radioSessionManager by lazy {
        com.velvet.app.recommendation.modes.RadioSessionManager()
    }

    val homeRecommendationComposer by lazy {
        com.velvet.app.recommendation.home.HomeRecommendationComposer(
            engine = recommendationEngine,
            featureRepository = trackFeatureRepository,
            profileRepository = userProfileRepository,
        )
    }

    val rollingCandidatePool by lazy {
        com.velvet.app.recommendation.candidates.RollingCandidatePool()
    }

    val autoplayRefillCoordinator by lazy {
        com.velvet.app.recommendation.engine.AutoplayRefillCoordinator(
            engine = recommendationEngine,
            policy = com.velvet.app.recommendation.engine.RefillPolicy(
                targetUpcomingBuffer = com.velvet.app.recommendation.engine.RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = com.velvet.app.recommendation.engine.RunwayPolicy.REFILL_BELOW,
                refillBatchSize = com.velvet.app.recommendation.engine.RunwayPolicy.MAX_BATCH,
                minCooldownMs = 300L,
            ),
            featureRepository = trackFeatureRepository,
            rollingPool = rollingCandidatePool,
            urgentFallbackProvider = { anchor, snap, excludedIds ->
                val seedPool = (
                    StreamResolver.peekRelated(anchor.id).orEmpty() +
                        com.velvet.app.recommendation.candidates.InstantTrackCatalog.snapshot()
                    ).distinctBy { it.id }
                libraryIndex.findSimilarFromIndex(
                    seed = anchor,
                    excludedIds = excludedIds,
                    excludedRecIds = excludedIds,
                    preferredLanguageIds = snap.preferredLanguageIds,
                    allowArtistHeavy = snap.isArtistRadioActive,
                    extraPool = seedPool,
                    limit = com.velvet.app.recommendation.engine.RunwayPolicy.MIN_BATCH,
                    allowMixedLanguages = snap.allowMixedLanguages,
                    artistLanguageMap = snap.artistLanguageMap,
                )
            },
        )
    }

    val runwayController by lazy {
        com.velvet.app.recommendation.engine.RunwayController(
            coordinator = autoplayRefillCoordinator,
            libraryIndex = libraryIndex,
            featureRepository = trackFeatureRepository,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        com.velvet.app.diagnostics.DiagnosticsLogger.attachLogDirectory(java.io.File(filesDir, "diagnostics"))
        com.velvet.app.diagnostics.CrashHandler.install(java.io.File(filesDir, "crash"))

        http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        library = Library(filesDir)
        val initialLangState = com.velvet.app.data.LanguageLocaleConfig.fromSelectedLanguages(library.data.value.musicLanguages)
        // the content country & localization match user's listening language preference
        NewPipe.init(
            YtDownloader(http),
            initialLangState.newPipeLocalization,
            initialLangState.newPipeContentCountry,
        )
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
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            library.data.map { it.musicLanguages }.distinctUntilChanged().drop(1).collect { langs ->
                val state = com.velvet.app.data.LanguageLocaleConfig.fromSelectedLanguages(langs)
                NewPipe.setPreferredLocalization(state.newPipeLocalization)
                NewPipe.setPreferredContentCountry(state.newPipeContentCountry)
            }
        }

        var lastLangLearningDigest = ""
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)).launch {
            library.data.collect { data ->
                libraryIndex.updateIncremental(data)
                val langDigest = "${data.liked.size}:${data.playlists.sumOf { it.resolvedSongs.size }}:${data.listenEvents.size}"
                if (langDigest != lastLangLearningDigest) {
                    lastLangLearningDigest = langDigest
                    val updatedMap = com.velvet.app.recommendation.vibe.ArtistLanguageMap.learnFromLibrary(data)
                    if (updatedMap != data.artistLanguageMap) {
                        library.updateArtistLanguageMap(updatedMap)
                    }
                }
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            library.data.map { it.autoplay }.distinctUntilChanged().drop(1).collect { enabled ->
                if (enabled) {
                    com.velvet.app.ui.PlayerConnection.onAutoplayToggledOn()
                }
            }
        }

        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                var lostSinceLastAvailable = false
                val mainHandler = Handler(Looper.getMainLooper())
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        lostSinceLastAvailable = true
                    }

                    override fun onAvailable(network: Network) {
                        if (lostSinceLastAvailable) {
                            lostSinceLastAvailable = false
                            mainHandler.post {
                                com.velvet.app.ui.PlayerConnection.onNetworkRegained()
                            }
                        }
                    }
                })
            }
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
        val recommendationEventRepo: com.velvet.app.recommendation.events.RecommendationEventRepository
            get() = instance.recommendationEventRepo
        val eventRecorder: com.velvet.app.recommendation.events.EventRecorder
            get() = instance.eventRecorder
        val trackFeatureRepository: com.velvet.app.recommendation.features.TrackFeatureRepository
            get() = instance.trackFeatureRepository
        val userProfileRepository: com.velvet.app.recommendation.profile.UserProfileRepository
            get() = instance.userProfileRepository
        val recommendationEngine: com.velvet.app.recommendation.engine.DefaultRecommendationEngine
            get() = instance.recommendationEngine
        val radioSessionManager: com.velvet.app.recommendation.modes.RadioSessionManager
            get() = instance.radioSessionManager
        val homeRecommendationComposer: com.velvet.app.recommendation.home.HomeRecommendationComposer
            get() = instance.homeRecommendationComposer
        val libraryIndex: com.velvet.app.recommendation.candidates.LibraryIndex
            get() = instance.libraryIndex
        val autoplayRefillCoordinator: com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
            get() = instance.autoplayRefillCoordinator
        val runwayController: com.velvet.app.recommendation.engine.RunwayController
            get() = instance.runwayController

        fun eventRecorderOrNull(): com.velvet.app.recommendation.events.EventRecorder? =
            if (::instance.isInitialized) runCatching { instance.eventRecorder }.getOrNull() else null

        fun recommendationEngineOrNull(): com.velvet.app.recommendation.engine.DefaultRecommendationEngine? =
            if (::instance.isInitialized) runCatching { instance.recommendationEngine }.getOrNull() else null

        fun radioSessionManagerOrNull(): com.velvet.app.recommendation.modes.RadioSessionManager? =
            if (::instance.isInitialized) runCatching { instance.radioSessionManager }.getOrNull() else null

        fun runwayControllerOrNull(): com.velvet.app.recommendation.engine.RunwayController? =
            if (::instance.isInitialized) runCatching { instance.runwayController }.getOrNull() else null

        fun homeRecommendationComposerOrNull(): com.velvet.app.recommendation.home.HomeRecommendationComposer? =
            if (::instance.isInitialized) runCatching { instance.homeRecommendationComposer }.getOrNull() else null
    }
}
