package com.velvet.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.velvet.app.R
import com.velvet.app.data.ContextRecentsManager
import com.velvet.app.data.Playlist
import com.velvet.app.data.RecentItem
import com.velvet.app.data.RecentType
import com.velvet.app.data.isPureMusic
import com.velvet.app.data.thumbSized
import kotlinx.coroutines.isActive
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import com.velvet.app.data.ArtistRef
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.velvet.app.VelvetApp
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.BrowseItem
import com.velvet.app.data.Song
import com.velvet.app.data.YouTube
import com.velvet.app.data.homePlaylists
import com.velvet.app.recommendation.model.PlaybackAttribution
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    val listState = LazyListState()

    var quickPicks by mutableStateOf<List<Song>>(emptyList())
        private set
    var trending by mutableStateOf<List<Song>>(emptyList())
        private set
    var moreForYou by mutableStateOf<List<AlbumRef>>(emptyList())
        private set
    var mixSeeds by mutableStateOf<List<Song>>(emptyList())
        private set
    var speedDial by mutableStateOf<List<Song>>(emptyList())
        private set
    var surveySeeds by mutableStateOf<List<Song>>(emptyList())
        private set

    private var historyIds = emptyList<String>()
    private var loadedTasteKey: String? = null
    private var loadedMoreSignature: String? = null
    private var lastMoreLoadedAtMs: Long = 0L
    private var moreForYouSpin: Int = 0
    private var lastDerivedKey: String? = null
    private var quickPicksJob: Job? = null
    private var trendingJob: Job? = null
    private var moreForYouJob: Job? = null
    private var derivedJob: Job? = null
    private val feedPrefs by lazy {
        application.getSharedPreferences("home_feed", Context.MODE_PRIVATE)
    }

    init {
        viewModelScope.launch {
            val libSnapshot = VelvetApp.library.data.value
            val locale = Locale.getDefault().toLanguageTag()
            val expectedTasteKey = onboardingTasteKey(libSnapshot)
            val cache = withContext(Dispatchers.IO) { feedPrefs }
            val cachedBundle = withContext(Dispatchers.IO) {
                val snapshot = cache.all
                val sameLocaleAndTaste = snapshot["locale"] == locale && snapshot["taste_key"] == expectedTasteKey
                val songs = if (sameLocaleAndTaste) {
                    (snapshot["songs"] as? String)?.let { encoded ->
                        runCatching { feedJson.decodeFromString(songList, encoded) }.getOrNull()
                    }.orEmpty()
                } else emptyList()
                val sd = if (sameLocaleAndTaste) {
                    (snapshot["speed_dial_songs"] as? String)?.let { encoded ->
                        runCatching { feedJson.decodeFromString(songList, encoded) }.getOrNull()
                    }.orEmpty()
                } else emptyList()
                val seeds = if (sameLocaleAndTaste) {
                    (snapshot["survey_seeds"] as? String)?.let { encoded ->
                        runCatching { feedJson.decodeFromString(songList, encoded) }.getOrNull()
                    }.orEmpty()
                } else emptyList()
                listOf(songs, sd, seeds, snapshot["fetched_at"] as? Long ?: 0L)
            }
            @Suppress("UNCHECKED_CAST")
            val cached = cachedBundle[0] as List<Song>
            @Suppress("UNCHECKED_CAST")
            val cachedSpeedDial = cachedBundle[1] as List<Song>
            @Suppress("UNCHECKED_CAST")
            val cachedSurveySeeds = cachedBundle[2] as List<Song>
            val fetchedAt = cachedBundle[3] as Long

            if (cached.isNotEmpty()) trending = cached
            if (cachedSurveySeeds.isNotEmpty()) surveySeeds = cachedSurveySeeds
            if (cachedSpeedDial.isNotEmpty()) speedDial = cachedSpeedDial.take(9)

            updateDerivedShelves(
                recent = libSnapshot.recent,
                liked = libSnapshot.liked,
                playlistsSongs = libSnapshot.playlists.flatMap { it.songs },
                eventCount = libSnapshot.listenEvents.size,
            )

            refreshForTaste(
                libSnapshot = libSnapshot,
                forceRefresh = cached.size < 12 || (System.currentTimeMillis() - fetchedAt) !in 0 until feedMaxAge,
            )
        }
    }

    private fun onboardingTasteKey(lib: com.velvet.app.data.LibraryData): String = buildString {
        append(lib.musicLanguages.map { it.trim() }.filter { it.isNotBlank() }.sorted().joinToString(","))
        append('|')
        append(lib.preferredArtists.map { it.trim() }.filter { it.isNotBlank() }.sorted().joinToString(","))
        append('|')
        append(lib.preferredGenres.map { it.trim() }.filter { it.isNotBlank() }.sorted().joinToString(","))
        append('|')
        append(lib.onboardingCompleted)
    }

    fun refreshAfterOnboarding() {
        val libSnapshot = VelvetApp.library.data.value
        val expectedKey = onboardingTasteKey(libSnapshot)
        if (loadedTasteKey != expectedKey) {
            refreshForTaste(libSnapshot = libSnapshot, forceRefresh = true)
        }
        loadFor(libSnapshot.recent, forceSurveyRefresh = true)
        loadMoreForYou(
            languages = libSnapshot.musicLanguages,
            artists = libSnapshot.preferredArtists,
            genres = libSnapshot.preferredGenres,
            forceRefresh = loadedTasteKey != expectedKey,
        )
    }

    fun refreshForTaste(
        libSnapshot: com.velvet.app.data.LibraryData = VelvetApp.library.data.value,
        forceRefresh: Boolean = false,
    ) {
        val tKey = onboardingTasteKey(libSnapshot)
        if (!forceRefresh && tKey == loadedTasteKey && trending.size >= 12) return
        loadedTasteKey = tKey

        trendingJob?.cancel()
        trendingJob = viewModelScope.launch {
            val locale = Locale.getDefault().toLanguageTag()
            val composer = VelvetApp.homeRecommendationComposerOrNull()
            val langs = libSnapshot.musicLanguages.mapNotNullTo(mutableSetOf(), ::mapLanguageToIso)
            val langNames = libSnapshot.musicLanguages.ifEmpty { listOf("Hindi", "English") }
            val chosenArtists = libSnapshot.preferredArtists
            val chosenGenres = libSnapshot.preferredGenres

            val (fetchedSurveySeeds, latestTrending) = withContext(Dispatchers.IO) {
                val seedTask = async {
                    if (chosenArtists.isEmpty() && chosenGenres.isEmpty()) {
                        emptyList()
                    } else {
                        val queries = buildList {
                            chosenArtists.take(4).forEach { add("$it songs") }
                            chosenGenres.take(3).forEach { genre ->
                                val langPrefix = langNames.firstOrNull().orEmpty()
                                add(if (langPrefix.isNotBlank()) "$langPrefix $genre songs" else "$genre songs")
                            }
                        }
                        queries.map { q ->
                            async {
                                runCatching {
                                    YouTube.Search(q, YouTube.FILTER_SONGS).loadNext()
                                        .filterIsInstance<BrowseItem.SongItem>()
                                        .map { it.song }
                                        .take(4)
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll().flatten().distinctBy { it.id }
                    }
                }

                val trendingTask = async {
                    val rawCharts = runCatching { YouTube.trendingMusic(langNames) }.getOrDefault(emptyList())
                    val profile = runCatching { VelvetApp.userProfileRepository.getSnapshot() }.getOrNull()
                    val artistSet = chosenArtists.map { it.trim() }.filter { it.isNotBlank() }.toSet()

                    val initialVerified = if (composer != null) {
                        composer.rankAndFilterTrending(
                            rawTrending = rawCharts,
                            preferredLanguageIds = langs,
                            preferredArtistNames = artistSet,
                            preferredGenres = chosenGenres,
                            tasteProfile = profile,
                            limit = 20,
                        )
                    } else {
                        rawCharts.rankMusic().take(20)
                    }

                    val supplementedRaw = if (initialVerified.size < 14) {
                        val suppQueries = buildList {
                            if (chosenGenres.isNotEmpty()) {
                                val topLang = langNames.firstOrNull() ?: "Hindi"
                                chosenGenres.take(2).forEach { add("$topLang $it top hit songs") }
                            }
                            langNames.take(2).forEach { add("$it top hit songs") }
                        }.distinct().take(3)
                        val extra = suppQueries.flatMap { q ->
                            runCatching {
                                YouTube.Search(q, YouTube.FILTER_SONGS).loadNext()
                                    .filterIsInstance<BrowseItem.SongItem>()
                                    .map { it.song }
                            }.getOrDefault(emptyList())
                        }
                        (rawCharts + extra).distinctBy { it.id }
                    } else {
                        rawCharts
                    }

                    if (composer != null) {
                        composer.rankAndFilterTrending(
                            rawTrending = supplementedRaw,
                            preferredLanguageIds = langs,
                            preferredArtistNames = artistSet,
                            preferredGenres = chosenGenres,
                            tasteProfile = profile,
                            limit = 20,
                        )
                    } else {
                        supplementedRaw.rankMusic().take(20)
                    }
                }

                val seeds = seedTask.await().let { rawSeeds ->
                    composer?.filterStrictOriginalAudio(rawSeeds) ?: rawSeeds.rankMusic()
                }
                val trends = trendingTask.await()

                runCatching {
                    feedPrefs.edit().apply {
                        putString("locale", locale)
                        putString("taste_key", tKey)
                        if (trends.isNotEmpty()) {
                            putString("songs", feedJson.encodeToString(songList, trends))
                            putLong("fetched_at", System.currentTimeMillis())
                        }
                        if (seeds.isNotEmpty()) {
                            putString("survey_seeds", feedJson.encodeToString(songList, seeds))
                        }
                    }.apply()
                }

                seeds to trends
            }

            if (fetchedSurveySeeds.isNotEmpty()) {
                surveySeeds = fetchedSurveySeeds
                com.velvet.app.recommendation.candidates.InstantTrackCatalog.seed(fetchedSurveySeeds)
            }
            if (latestTrending.isNotEmpty()) {
                trending = latestTrending
                com.velvet.app.recommendation.candidates.InstantTrackCatalog.seed(latestTrending)
            } else if (fetchedSurveySeeds.isNotEmpty() && trending.isEmpty()) {
                trending = fetchedSurveySeeds.take(20)
            }
            val libNow = VelvetApp.library.data.value
            updateDerivedShelves(
                recent = libNow.recent,
                liked = libNow.liked,
                playlistsSongs = libNow.playlists.flatMap { it.songs },
                eventCount = libNow.listenEvents.size,
            )
            if (libNow.recent.isEmpty() && fetchedSurveySeeds.isNotEmpty()) {
                loadFor(libNow.recent, forceSurveyRefresh = true)
            }
        }

        loadMoreForYou(
            languages = libSnapshot.musicLanguages,
            artists = libSnapshot.preferredArtists,
            genres = libSnapshot.preferredGenres,
            forceRefresh = forceRefresh,
        )
    }

    private fun mapLanguageToIso(raw: String): String? = when (raw.trim().lowercase(Locale.ROOT)) {
        "hindi" -> "hi"
        "english" -> "en"
        "punjabi" -> "pa"
        "bhojpuri" -> "bho"
        "haryanvi" -> "bgc"
        "bengali", "bangla" -> "bn"
        "tamil" -> "ta"
        "telugu" -> "te"
        "malayalam" -> "ml"
        "marathi" -> "mr"
        "gujarati" -> "gu"
        "kannada" -> "kn"
        "sanskrit" -> "sa"
        "urdu" -> "ur"
        else -> null
    }

    /** Build Quick Picks from strict verified studio recommendations anchored by listening habits or survey choices. */
    fun loadFor(recent: List<Song>, forceSurveyRefresh: Boolean = false) {
        val libData = VelvetApp.library.data.value
        val hasSurveyTaste = libData.preferredArtists.isNotEmpty() || libData.preferredGenres.isNotEmpty() || surveySeeds.isNotEmpty()
        val ids = recent.map { it.id }
        if (ids == historyIds && !forceSurveyRefresh) return
        historyIds = ids
        quickPicksJob?.cancel()
        if (recent.isEmpty() && !hasSurveyTaste) {
            quickPicks = emptyList()
            return
        }

        val played = ids.toSet()
        quickPicks = quickPicks.filterNot { it.id in played }
        quickPicksJob = viewModelScope.launch {
            val composer = VelvetApp.homeRecommendationComposerOrNull()
            val langs = libData.musicLanguages.mapNotNullTo(mutableSetOf(), ::mapLanguageToIso)
            val resolved = withContext(Dispatchers.IO) {
                val profile = runCatching { VelvetApp.userProfileRepository.getSnapshot() }.getOrNull()
                if (composer != null) {
                    composer.composeQuickPicks(
                        recent = recent,
                        liked = libData.liked,
                        preferredLanguageIds = langs,
                        directSearchProvider = { query ->
                            YouTube.Search(query, YouTube.FILTER_SONGS).loadNext()
                                .filterIsInstance<BrowseItem.SongItem>()
                                .map { it.song }
                        },
                        fallbackCatalog = (surveySeeds + trending).distinctBy { it.id },
                        preferredArtistNames = libData.preferredArtists.toSet(),
                        preferredGenres = libData.preferredGenres,
                        tasteProfile = profile,
                        limit = 15,
                    )
                } else {
                    emptyList()
                }
            }
            if (resolved.isNotEmpty()) {
                quickPicks = resolved
            }
        }
    }

    /** Refresh More For You albums and playlists dynamically using languages, artists, genres, moods & session rotation. */
    fun loadMoreForYou(
        languages: List<String>,
        artists: List<String> = VelvetApp.library.data.value.preferredArtists,
        genres: List<String> = VelvetApp.library.data.value.preferredGenres,
        forceRefresh: Boolean = false,
    ) {
        val langs = languages.ifEmpty { listOf("Hindi", "English") }
        val lib = VelvetApp.library.data.value
        val followedOrRecentArtists = (artists + lib.artists.map { it.name } + lib.recent.take(5).map { it.artist })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val signature = "${langs.joinToString(",")}|${followedOrRecentArtists.take(4).joinToString(",")}|${genres.joinToString(",")}"
        val now = System.currentTimeMillis()
        val ageMs = now - lastMoreLoadedAtMs
        if (!forceRefresh && signature == loadedMoreSignature && moreForYou.isNotEmpty() && ageMs < TimeUnit.MINUTES.toMillis(4)) {
            return
        }
        loadedMoreSignature = signature
        lastMoreLoadedAtMs = now
        moreForYouSpin++

        moreForYouJob?.cancel()
        moreForYouJob = viewModelScope.launch(Dispatchers.IO) {
            val playlistDescriptors = listOf(
                "Trending Now", "Top Hits", "Viral Hits", "New Releases",
                "Essentials", "Late Night Vibes", "Acoustic Chill", "Party Hits",
            )
            val albumDescriptors = listOf("Hits", "Essentials", "New Albums", "Top Releases")
            val seed = (now / TimeUnit.MINUTES.toMillis(2)) + moreForYouSpin * 31L
            val rng = java.util.Random(seed)

            data class FetchSpec(val query: String, val filter: String)
            val queryPool = mutableListOf<FetchSpec>()

            // 1. Chosen / listening-habit languages with rotating descriptors
            val shuffledPlDesc = playlistDescriptors.shuffled(rng)
            val shuffledAlDesc = albumDescriptors.shuffled(rng)
            langs.shuffled(rng).take(3).forEachIndexed { idx, lang ->
                queryPool += FetchSpec("$lang ${shuffledPlDesc[idx % shuffledPlDesc.size]}", YouTube.FILTER_PLAYLISTS)
                queryPool += FetchSpec("$lang ${shuffledAlDesc[idx % shuffledAlDesc.size]}", YouTube.FILTER_ALBUMS)
            }

            // 2. User-chosen genres (onboarding + taste) paired with active languages
            if (genres.isNotEmpty()) {
                val primaryLang = langs.first()
                genres.shuffled(rng).take(3).forEach { genre ->
                    queryPool += FetchSpec("$primaryLang $genre Playlist", YouTube.FILTER_PLAYLISTS)
                    queryPool += FetchSpec("$genre Best Albums", YouTube.FILTER_ALBUMS)
                }
            }

            // 3. Chosen / followed / recent artists mixes & albums
            if (followedOrRecentArtists.isNotEmpty()) {
                followedOrRecentArtists.shuffled(rng).take(3).forEach { artist ->
                    queryPool += FetchSpec("$artist Mix", YouTube.FILTER_PLAYLISTS)
                    queryPool += FetchSpec("$artist", YouTube.FILTER_ALBUMS)
                }
            }

            val selectedSpecs = queryPool.distinct().shuffled(rng).take(6)
            val buckets = selectedSpecs.map { spec ->
                async {
                    runCatching {
                        YouTube.Search(spec.query, spec.filter).loadNext()
                            .filterIsInstance<BrowseItem.AlbumItem>()
                            .map { it.album }
                            .take(4)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()

            // Interleave buckets round-robin so consecutive cards showcase varied themes & genres
            val interleaved = mutableListOf<AlbumRef>()
            val maxPerBucket = buckets.maxOfOrNull { it.size } ?: 0
            for (pos in 0 until maxPerBucket) {
                for (bucket in buckets) {
                    bucket.getOrNull(pos)?.let { interleaved.add(it) }
                }
            }
            val finalItems = interleaved.distinctBy { it.url }
            if (finalItems.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    moreForYou = finalItems
                }
            }
        }
    }

    /** Compute Speed Dial (all 9 cells atomically) and Made For You mix seeds dynamically from listening habits & history. */
    fun updateDerivedShelves(
        recent: List<Song>,
        liked: List<Song>,
        playlistsSongs: List<Song> = emptyList(),
        eventCount: Int = 0,
    ) {
        val qPicks = quickPicks
        val trend = trending
        val seeds = surveySeeds
        val libData = VelvetApp.library.data.value
        val key = buildString {
            recent.take(30).forEach { append(it.id).append(',') }
            append('|')
            liked.take(30).forEach { append(it.id).append(',') }
            append('|')
            playlistsSongs.take(30).forEach { append(it.id).append(',') }
            append('|')
            append(eventCount)
            append('|')
            append(onboardingTasteKey(libData))
            append('|')
            qPicks.take(20).forEach { append(it.id).append(',') }
            append('|')
            seeds.take(20).forEach { append(it.id).append(',') }
            append('|')
            trend.take(20).forEach { append(it.id).append(',') }
        }
        if (key == lastDerivedKey) return
        lastDerivedKey = key

        derivedJob?.cancel()
        derivedJob = viewModelScope.launch {
            val composer = VelvetApp.homeRecommendationComposerOrNull()
            val langs = libData.musicLanguages.mapNotNullTo(mutableSetOf(), ::mapLanguageToIso)
            val preferredArtistSet = libData.preferredArtists.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            val preferredGenres = libData.preferredGenres
            val (computedMixes, computedSpeedDial) = withContext(Dispatchers.Default) {
                val tasteProfile = runCatching { VelvetApp.userProfileRepository.getSnapshot() }.getOrNull()
                val m = composer?.composeMadeForYouMixSeeds(
                    recent = recent,
                    liked = liked,
                    limit = 8,
                    preferredArtistNames = preferredArtistSet,
                    fallbackCatalog = (seeds + trend).distinctBy { it.id },
                ) ?: (recent + liked + seeds).distinctBy { it.artist.lowercase() }.take(8)
                val sd = composer?.composeSpeedDial(
                    recent = recent,
                    liked = liked,
                    quickPicks = (qPicks + seeds).distinctBy { it.id },
                    trending = trend,
                    limit = 9,
                    playlistsSongs = playlistsSongs,
                    preferredLanguageIds = langs,
                    tasteProfile = tasteProfile,
                    preferredArtistNames = preferredArtistSet,
                    preferredGenres = preferredGenres,
                ) ?: run {
                    val userPicks = (recent + liked + playlistsSongs).distinctBy { it.id }.filter { it.isPureMusic() }.take(4)
                    val freshDiscovery = (qPicks + seeds + trend).distinctBy { it.id }
                        .filter { fresh -> fresh.isPureMusic() && userPicks.none { it.id == fresh.id } }
                    (userPicks + freshDiscovery).distinctBy { it.id }.take(9)
                }
                com.velvet.app.recommendation.candidates.InstantTrackCatalog.seed(
                    (sd + qPicks + recent + liked + trend + seeds).distinctBy { it.id },
                )
                m to sd
            }
            mixSeeds = computedMixes
            // Publish atomically so a complete 9/9 grid isn't temporarily replaced by a smaller partial grid mid-fetch
            if (computedSpeedDial.size >= minOf(9, speedDial.size) || speedDial.isEmpty() || libData.onboardingCompleted) {
                speedDial = computedSpeedDial
                if (computedSpeedDial.size >= 9) {
                    val locale = Locale.getDefault().toLanguageTag()
                    val tKey = onboardingTasteKey(libData)
                    withContext(Dispatchers.IO) {
                        runCatching {
                            feedPrefs.edit()
                                .putString("locale", locale)
                                .putString("taste_key", tKey)
                                .putString("speed_dial_songs", feedJson.encodeToString(songList, computedSpeedDial))
                                .apply()
                        }
                    }
                }
            }
        }
    }
}

private fun List<Song>.rankMusic() = distinctBy { it.id }.sortedByDescending { it.preferredMusicSource }

private val feedJson = Json { ignoreUnknownKeys = true }
private val songList = ListSerializer(Song.serializer())
private val feedMaxAge = TimeUnit.MINUTES.toMillis(30)

/** A tile in the top shortcuts grid. */
private class Shortcut(val title: String, val art: @Composable () -> Unit, val open: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val nav = LocalNav.current
    val data by VelvetApp.library.data.collectAsStateWithLifecycle()
    val recent = data.recent
    val quickPicks = vm.quickPicks
    val trending = vm.trending
    val moreForYou = vm.moreForYou
    val languages = data.musicLanguages
    val openMenu = LocalSongMenu.current

    val playlistSongs = remember(data.playlists) { data.playlists.flatMap { it.songs } }
    val eventCount = data.listenEvents.size

    val preferredArtists = data.preferredArtists
    val preferredGenres = data.preferredGenres
    val surveySeeds = vm.surveySeeds

    LaunchedEffect(recent, preferredArtists, preferredGenres, surveySeeds) {
        vm.loadFor(recent, forceSurveyRefresh = surveySeeds.isNotEmpty() || preferredArtists.isNotEmpty() || preferredGenres.isNotEmpty())
    }
    LaunchedEffect(languages, preferredArtists, preferredGenres, data.onboardingCompleted) {
        vm.refreshForTaste(data)
    }
    LaunchedEffect(recent, data.liked, playlistSongs, eventCount, quickPicks, trending, surveySeeds, languages, preferredArtists, preferredGenres) {
        vm.updateDerivedShelves(recent, data.liked, playlistSongs, eventCount)
    }

    val filteredRecents = remember(data.contextRecents, data.playlists) {
        ContextRecentsManager.filterDeletedPlaylists(data.contextRecents, data.playlists)
    }

    // Top 4 shortcut playlist tiles allocated strictly ranking-wise by recency of play:
    // 1st tile (top-left) = Very newest recently played playlist
    // 2nd tile (top-right) = 2nd newest recently played playlist
    // 3rd tile (bottom-left) = 3rd newest recently played playlist
    // 4th tile (bottom-right) = 4th newest recently played playlist
    val contextPlaylistRecents = remember(filteredRecents) {
        filteredRecents.filter { it.type == RecentType.LOCAL_PLAYLIST }.associate { it.id to it.lastPlayedAt }
    }
    val userPlaylists = remember(data.playlists, contextPlaylistRecents) {
        data.playlists
            .filter { it.songs.isNotEmpty() }
            .sortedWith(
                compareByDescending<Playlist> { p ->
                    maxOf(p.lastPlayedAt, contextPlaylistRecents[p.id] ?: 0L)
                }.thenByDescending { p ->
                    maxOf(p.createdAt, p.addedAt, p.updatedAt)
                }.thenByDescending { it.playCount }
            )
            .take(4)
    }
    val savedAlbums = data.albums.sortedByDescending { it.addedAt }
    val shortcuts = buildList {
        userPlaylists.forEach { p ->
            add(Shortcut(p.name, { PlaylistCover(p.songs, 58.dp, corner = 16.dp, customCover = p.customCover, coverMode = p.coverMode, coverRef = p.coverRef) }) { nav.open(Route.LocalPlaylist(p.id)) })
        }
        if (size < 4) {
            savedAlbums.take(4 - size).forEach { a ->
                add(Shortcut(a.title, { Artwork(a.thumb, 58.dp, corner = 16.dp, placeholder = AppIcons.Album) }) { nav.open(Route.Album(a)) })
            }
        }
    }.take(4)

    // Precomputed off Main thread inside HomeViewModel for zero tab-switch jerk
    val mixes = vm.mixSeeds
    val speedDialSongs = vm.speedDial

    val homeVisitRequestId = remember { UUID.randomUUID().toString() }

    val greeting = remember {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Night vibes"
        }
    }

    LazyColumn(
        state = vm.listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "velvet-heading") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_velvet_logo),
                    contentDescription = "VELVET Logo",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(46.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "VELVET",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = SacrificeFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = velvetColors.text,
                    modifier = Modifier.weight(1f),
                )
                // History clock icon button immediately to the left of Settings opens played songs history
                PanelIconButton(AppIcons.History, "Listening history") { nav.open(Route.History) }
                Spacer(Modifier.width(8.dp))
                PanelIconButton(Icons.Default.Settings, "Settings") { nav.open(Route.Settings) }
            }
        }

        item(key = "greeting") {
            Text(
                greeting,
                style = MaterialTheme.typography.titleMedium,
                color = velvetColors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        if (shortcuts.isNotEmpty()) {
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shortcuts.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { s -> ShortcutTile(s, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        val featured = recent.isEmpty() && PlayerConnection.current == null
        val spotlightQueue = if (featured) trending else recent
        val hero = PlayerConnection.current ?: spotlightQueue.firstOrNull()
        if (hero != null) {
            item(key = "spotlight") {
                Box(Modifier.padding(top = 10.dp)) {
                    SpotlightCard(hero, spotlightQueue, featured)
                }
            }
        }

        // 1. Trending now
        if (trending.isNotEmpty()) {
            item(key = "home-trending-now") {
                TrendingRail(trending, homeVisitRequestId)
            }
        }

        // 2. Speed Dial
        if (speedDialSongs.isNotEmpty()) {
            item(key = "speed-dial") {
                SpeedDialSection(speedDialSongs, homeVisitRequestId)
            }
        }

        // 3. Recently played
        if (filteredRecents.isNotEmpty()) {
            item(key = "home-recently-played") {
                BrowseSection("Recently played", action = "See all") { nav.open(Route.Recent) }
            }
            item(key = "home-recently-played-rail") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(filteredRecents.take(10), key = { "hr-${it.type}-${it.id}" }) { item ->
                        val playlist = if (item.type == RecentType.LOCAL_PLAYLIST) data.playlists.find { it.id == item.id } else null
                        RecentContextTile(
                            item = item,
                            playlist = playlist,
                            onClick = {
                                when (item.type) {
                                    RecentType.LOCAL_PLAYLIST -> nav.open(Route.LocalPlaylist(item.id))
                                    RecentType.REMOTE_PLAYLIST, RecentType.ALBUM -> nav.open(
                                        Route.Album(
                                            AlbumRef(
                                                url = item.id,
                                                title = item.title,
                                                subtitle = item.subtitle,
                                                thumb = item.artworkUrl.orEmpty(),
                                            ),
                                        ),
                                    )
                                    RecentType.ARTIST -> nav.open(
                                        Route.Artist(
                                            ArtistRef(
                                                url = item.id,
                                                name = item.title,
                                                thumb = item.artworkUrl.orEmpty(),
                                            ),
                                        ),
                                    )
                                    RecentType.GENRE_MIX -> nav.open(Route.Genre(item.title))
                                    RecentType.UNKNOWN -> {}
                                }
                            },
                        )
                    }
                }
            }
        }

        // 4. Quick picks
        if (quickPicks.isNotEmpty()) {
            item(key = "home-quick-picks") {
                QuickPicksRail(quickPicks, homeVisitRequestId) { index ->
                    PlayerConnection.startRadio(
                        song = quickPicks[index],
                        attribution = PlaybackAttribution(
                            surface = RecommendationSurface.HOME_SONG_RAIL,
                            mode = RecommendationMode.SONG_RADIO,
                            requestId = homeVisitRequestId,
                            shelfId = "quick_picks",
                            resultPosition = index,
                        ),
                    )
                }
            }
        }

        // 5. Made for you
        if (mixes.isNotEmpty()) {
            item(key = "home-made-for-you-header") { BrowseSection("Made for you") }
            item(key = "home-made-for-you-rail") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(mixes, key = { _, seed -> "mix-" + seed.id }) { index, seed ->
                        LaunchedEffect(homeVisitRequestId, seed.id, index) {
                            VelvetApp.eventRecorderOrNull()?.recordHomeCollectionImpression(
                                homeVisitRequestId = homeVisitRequestId,
                                shelfId = "made_for_you",
                                surface = RecommendationSurface.HOME_RADIO_RAIL,
                                mode = RecommendationMode.HOME_RADIOS,
                                position = index,
                                itemId = seed.id,
                            )
                        }
                        MixCard("${seed.artist} Radio", seed.thumb) {
                            PlayerConnection.startArtistRadio(
                                seed = seed,
                                attribution = PlaybackAttribution(
                                    surface = RecommendationSurface.HOME_RADIO_RAIL,
                                    mode = RecommendationMode.ARTIST_RADIO,
                                    requestId = homeVisitRequestId,
                                    shelfId = "made_for_you",
                                    resultPosition = index,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // 6. More for you (YouTube Music pre-made albums and playlists recommendation section)
        if (moreForYou.isNotEmpty()) {
            item(key = "home-more-for-you-header") { BrowseSection("More for you") }
            item(key = "home-more-for-you-rail") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(moreForYou, key = { _, album -> album.url }) { index, album ->
                        LaunchedEffect(homeVisitRequestId, album.url, index) {
                            VelvetApp.eventRecorderOrNull()?.recordHomeCollectionImpression(
                                homeVisitRequestId = homeVisitRequestId,
                                shelfId = "more_for_you",
                                surface = RecommendationSurface.HOME_PLAYLIST_RAIL,
                                mode = RecommendationMode.HOME_PLAYLISTS,
                                position = index,
                                itemId = album.url,
                            )
                        }
                        AlbumCard(album) { nav.open(Route.Album(album)) }
                    }
                }
            }
        }

        if (data.artists.isNotEmpty()) {
            item(key = "home-your-artists-header") { BrowseSection("Your artists") }
            item(key = "home-your-artists-rail") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(data.artists, key = { it.url }) { a -> ArtistCircle(a, 112.dp) { nav.open(Route.Artist(a)) } }
                }
            }
        }
    }
}

/** YouTube Music style Speed Dial grid section */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialSection(songs: List<Song>, homeVisitRequestId: String) {
    val openMenu = LocalSongMenu.current

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Speed Dial",
                style = MaterialTheme.typography.titleLarge,
                color = velvetColors.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Quick play",
                style = MaterialTheme.typography.bodySmall,
                color = velvetColors.accentText,
            )
        }

        // 3x3 Matrix layout: 3 rows of 3 columns, perfectly fitting screen width with no horizontal scroll
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val rows = songs.take(9).chunked(3)
            rows.forEachIndexed { rowIndex, rowSongs ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowSongs.forEachIndexed { colIndex, song ->
                        val position = rowIndex * 3 + colIndex
                        LaunchedEffect(homeVisitRequestId, song.id, position) {
                            VelvetApp.eventRecorderOrNull()?.recordHomeSongImpression(
                                homeVisitRequestId = homeVisitRequestId,
                                shelfId = "speed_dial",
                                surface = RecommendationSurface.HOME_SONG_RAIL,
                                position = position,
                                song = song,
                            )
                        }
                        SpeedDialSquareTile(
                            song = song,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                PlayerConnection.play(
                                    songs = listOf(song),
                                    attribution = PlaybackAttribution(
                                        surface = RecommendationSurface.HOME_SONG_RAIL,
                                        mode = RecommendationMode.HOME_SONGS,
                                        requestId = homeVisitRequestId,
                                        shelfId = "speed_dial",
                                        resultPosition = position,
                                    ),
                                )
                            },
                            onLongClick = { openMenu(SongMenuRequest(song)) },
                        )
                    }
                    repeat(3 - rowSongs.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialSquareTile(
    song: Song,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .captureOriginOnPress(cornerRadiusDp = 16f)
            .clip(RoundedCornerShape(16.dp))
            .background(velvetColors.raised)
            .border(1.dp, velvetColors.glassBorder, RoundedCornerShape(16.dp))
            .prefetchOnTouch(song.id)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (song.thumb != null) {
            AsyncImage(
                model = song.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.MusicNote,
                    contentDescription = null,
                    tint = velvetColors.muted,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // Solid dark gradient scrim for crisp legible typography
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x33000000),
                            Color(0xCC000000),
                            Color(0xF5000000),
                        ),
                        startY = 35f,
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0x80000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Play,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }

            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFFD1D5DB),
                )
            }
        }
    }
}

@Composable
private fun SpinningCdArtwork(
    url: String?,
    size: Dp = 64.dp,
    isPlaying: Boolean = false,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 8000, easing = LinearEasing),
                )
                rotation.snapTo(rotation.value % 360f)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .rotate(rotation.value)
            .clip(CircleShape)
            .background(Color(0xFF141416))
            .border(1.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            val px = with(LocalDensity.current) { size.roundToPx() }
            AsyncImage(
                model = thumbSized(url, px),
                contentDescription = "CD Art",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        } else {
            Icon(
                AppIcons.MusicNote,
                contentDescription = null,
                tint = velvetColors.muted,
                modifier = Modifier.size(size * 0.4f),
            )
        }

        // Concentric vinyl groove highlights
        Box(
            modifier = Modifier
                .fillMaxSize(0.84f)
                .border(1.dp, Color(0x22FFFFFF), CircleShape),
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.64f)
                .border(1.dp, Color(0x18FFFFFF), CircleShape),
        )

        // Center hub ring
        Box(
            modifier = Modifier
                .size(size * 0.32f)
                .clip(CircleShape)
                .background(Color(0xD91C1C22))
                .border(1.5.dp, Color(0x88FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Center spindle hole
            Box(
                modifier = Modifier
                    .size(size * 0.12f)
                    .clip(CircleShape)
                    .background(Color(0xFF0C0C0E))
                    .border(1.dp, Color(0x55FFFFFF), CircleShape),
            )
        }
    }
}

/** Sleek, lean, and modern NOW PLAYING card with spinning CD artwork, breathing ambient gradient, and no seekbar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotlightCard(song: Song, queue: List<Song>, featured: Boolean) {
    val nav = LocalNav.current
    val isCurrent = PlayerConnection.current?.id == song.id
    val isPlaying = isCurrent && PlayerConnection.isPlaying
    val openMenu = LocalSongMenu.current
    val artColor = rememberArtColor(song.thumb, fallback = velvetColors.accent)

    // Breathing effect animation that pulses while song is playing and freezes when paused
    val breathingFraction = remember { Animatable(0.25f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                breathingFraction.animateTo(
                    targetValue = 0.9f,
                    animationSpec = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                )
                breathingFraction.animateTo(
                    targetValue = 0.25f,
                    animationSpec = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    val gradientBrush = remember(artColor, breathingFraction.value) {
        val alphaStart = (0.30f * breathingFraction.value).coerceIn(0.06f, 0.42f)
        val alphaMid = (0.12f * breathingFraction.value).coerceIn(0.02f, 0.20f)
        Brush.horizontalGradient(
            colors = listOf(
                artColor.copy(alpha = alphaStart),
                artColor.copy(alpha = alphaMid),
                Color.Transparent,
            ),
        )
    }

    GlassSurface(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isCurrent) nav.nowPlayingOpen = true
                    else PlayerConnection.play(queue.take(50), queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
                },
                onLongClick = { openMenu(SongMenuRequest(song)) },
            ),
        shape = RoundedCornerShape(20.dp),
        color = velvetColors.surface,
        borderColor = velvetColors.glassBorder,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // Circular Spinning CD Album Art with breathing ambient halo
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = 1f + 0.12f * breathingFraction.value
                            scaleY = 1f + 0.12f * breathingFraction.value
                            alpha = breathingFraction.value
                        }
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    artColor.copy(alpha = 0.50f),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                )
                SpinningCdArtwork(
                    url = song.thumb,
                    size = 64.dp,
                    isPlaying = isPlaying,
                )
            }

            // Song Info (Title, Artist, and Now Playing header)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(velvetColors.accentText),
                        )
                    }
                    Text(
                        when {
                            isCurrent -> "NOW PLAYING"
                            featured -> "FROM TRENDING"
                            else -> "CONTINUE LISTENING"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 0.8.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = velvetColors.accentText,
                    )
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = velvetColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = velvetColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Sleek Modern Play / Pause Accent Button
            AccentCircleButton(
                icon = if (isPlaying) AppIcons.Pause else AppIcons.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                size = 46.dp,
            ) {
                if (isCurrent) PlayerConnection.togglePlay()
                else PlayerConnection.play(queue.take(50), queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
            }
        }
    }
}
}

@Composable
private fun ShortcutTile(s: Shortcut, modifier: Modifier) {
    GlassSurface(
        modifier = modifier
            .height(70.dp)
            .captureOriginOnPress(cornerRadiusDp = 20f)
            .clickable(onClick = s.open),
        shape = RoundedCornerShape(20.dp),
        color = velvetColors.surface,
        borderColor = velvetColors.glassBorder,
    ) {
        Row(
            Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            s.art()
            Text(
                s.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/** A radio mix: the seed song's cover with the mix name over a dark fade. */
@Composable
private fun MixCard(title: String, thumb: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .width(204.dp)
            .height(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Artwork(thumb, 204.dp, Modifier.matchParentSize(), corner = 18.dp)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(AppIcons.Radio, null, tint = velvetColors.accentText, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickPicksRail(
    songs: List<Song>,
    homeVisitRequestId: String,
    onPlay: (Int) -> Unit,
) {
    BrowseSection("Quick picks")
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            LaunchedEffect(homeVisitRequestId, song.id, index) {
                VelvetApp.eventRecorderOrNull()?.recordHomeSongImpression(
                    homeVisitRequestId = homeVisitRequestId,
                    shelfId = "quick_picks",
                    surface = RecommendationSurface.HOME_SONG_RAIL,
                    position = index,
                    song = song,
                )
            }
            SongCard(song) { onPlay(index) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    val openMenu = LocalSongMenu.current
    Column(
        Modifier
            .width(144.dp)
            .prefetchOnTouch(song.id)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { openMenu(SongMenuRequest(song)) },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(song.thumb, 144.dp, corner = 16.dp)
        Text(
            song.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrendingRail(songs: List<Song>, homeVisitRequestId: String) {
    val openMenu = LocalSongMenu.current
    BrowseSection("Trending now")
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(songs.chunked(3), key = { _, group -> group.first().id }) { page, group ->
            GlassSurface(
                modifier = Modifier.width(280.dp),
                shape = RoundedCornerShape(18.dp),
                color = velvetColors.surface,
                borderColor = velvetColors.glassBorder,
            ) {
                Column {
                    group.forEachIndexed { offset, song ->
                        val index = page * 3 + offset
                        LaunchedEffect(homeVisitRequestId, song.id, index) {
                            VelvetApp.eventRecorderOrNull()?.recordHomeSongImpression(
                                homeVisitRequestId = homeVisitRequestId,
                                shelfId = "trending",
                                surface = RecommendationSurface.HOME_SONG_RAIL,
                                position = index,
                                song = song,
                            )
                        }
                        if (offset > 0) HorizontalDivider(Modifier.padding(start = 64.dp, end = 12.dp), color = velvetColors.rule)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .prefetchOnTouch(song.id)
                                .combinedClickable(
                                    onClick = {
                                        PlayerConnection.play(
                                            songs = songs,
                                            startIndex = index,
                                            attribution = PlaybackAttribution(
                                                surface = RecommendationSurface.HOME_SONG_RAIL,
                                                mode = RecommendationMode.HOME_SONGS,
                                                requestId = homeVisitRequestId,
                                                shelfId = "trending",
                                                resultPosition = index,
                                            ),
                                        )
                                    },
                                    onLongClick = { openMenu(SongMenuRequest(song)) },
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Artwork(song.thumb, 44.dp, corner = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(AppIcons.Play, null, tint = velvetColors.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentContextTile(
    item: RecentItem,
    playlist: Playlist? = null,
    onClick: () -> Unit,
) {
    val isArtist = item.type == RecentType.ARTIST
    val isLocalPlaylist = item.type == RecentType.LOCAL_PLAYLIST
    Column(
        Modifier
            .width(130.dp)
            .captureOriginOnPress(cornerRadiusDp = if (isArtist) 60f else 22f)
            .clickable(onClick = onClick),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (isArtist) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(velvetColors.accent)
                    .padding(3.dp),
            ) {
                Artwork(item.artworkUrl, 114.dp, corner = 57.dp, placeholder = Icons.Default.Person)
            }
        } else if (isLocalPlaylist && playlist != null) {
            PlaylistCover(
                songs = playlist.songs,
                size = 130.dp,
                corner = 22.dp,
                customCover = playlist.customCover,
                coverMode = playlist.coverMode,
                coverRef = playlist.coverRef,
            )
        } else {
            Artwork(item.artworkUrl, 130.dp, corner = 22.dp, placeholder = AppIcons.Album)
        }
        Text(
            playlist?.name ?: item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (isLocalPlaylist && playlist != null) {
                "Playlist • ${songCountLabel(playlist.songs.size)}"
            } else {
                item.subtitle.ifEmpty {
                    when (item.type) {
                        RecentType.LOCAL_PLAYLIST, RecentType.REMOTE_PLAYLIST -> "Playlist"
                        RecentType.ALBUM -> "Album"
                        RecentType.ARTIST -> "Artist"
                        RecentType.GENRE_MIX -> "Mix"
                        RecentType.UNKNOWN -> ""
                    }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = velvetColors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
