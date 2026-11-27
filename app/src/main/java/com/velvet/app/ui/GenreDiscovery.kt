package com.velvet.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velvet.app.VelvetApp
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.BrowseItem
import com.velvet.app.data.GenreTileSpec
import com.velvet.app.data.LanguageLocaleConfig
import com.velvet.app.data.SearchLanguageFilter
import com.velvet.app.data.SearchLanguageState
import com.velvet.app.data.Song
import com.velvet.app.data.YouTube
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.data.isOriginalMusicAudio
import com.velvet.app.data.isPureMusic
import com.velvet.app.playback.CoverArtResolver
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class GenreHubContent(
    val spec: GenreTileSpec,
    val languageState: SearchLanguageState,
    val trendingTracks: List<Song>,
    val latestAlbums: List<AlbumRef>,
    val curatedPlaylists: List<AlbumRef>,
    val rotatingRecommendations: List<Song>,
    val rotationEpoch: Int,
    val loadedAtMs: Long,
) {
    val isEmpty: Boolean
        get() = trendingTracks.isEmpty() &&
            latestAlbums.isEmpty() &&
            curatedPlaylists.isEmpty() &&
            rotatingRecommendations.isEmpty()
}

@Immutable
data class GenreUiState(
    val genreName: String = "",
    val spec: GenreTileSpec? = null,
    val languageSummary: String = "",
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val content: GenreHubContent? = null,
    val error: String? = null,
    val refreshWarning: String? = null,
)

/**
 * Pure Kotlin helpers for genre seed resolution, time/spin-based rotating recommendations,
 * artist diversification, and trending/rotating deduplication.
 */
object GenreRotationStrategy {

    private const val ROTATION_WINDOW_MS = 15 * 60 * 1000L

    fun resolveSpec(name: String, state: SearchLanguageState): GenreTileSpec {
        val trimmed = name.trim()
        val catalog = LanguageLocaleConfig.genreCatalogFor(state)
        catalog.firstOrNull {
            it.title.equals(trimmed, ignoreCase = true) || it.id.equals(trimmed, ignoreCase = true)
        }?.let { return it }

        val langLabel = state.primaryLanguage
        return GenreTileSpec(
            id = trimmed.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "_").trim('_').ifBlank { "genre" },
            title = trimmed.ifBlank { "Discover" },
            subtitle = "$langLabel curated picks & new releases",
            primaryColorArgb = 0xFF4C1D95,
            secondaryColorArgb = 0xFF8B5CF6,
            iconKey = "sparkles",
            trendingSeed = "$trimmed trending songs",
            albumSeed = "$trimmed latest albums",
            playlistSeeds = listOf("$trimmed top playlist", "$trimmed chill mix", "$trimmed essentials"),
            rotatingSeeds = listOf("$trimmed fresh picks", "$trimmed acoustic hits", "$trimmed hidden gems"),
        )
    }

    fun selectRotatingSeeds(
        spec: GenreTileSpec,
        spin: Int,
        nowMs: Long = System.currentTimeMillis(),
        maxSeeds: Int = 2,
    ): List<String> {
        val pool = (spec.rotatingSeeds + spec.playlistSeeds).distinct()
        if (pool.isEmpty()) return emptyList()
        val bucket = (nowMs / ROTATION_WINDOW_MS) + spin * 17L
        val shuffled = pool.shuffled(Random(bucket xor spec.id.hashCode().toLong()))
        return shuffled.take(maxSeeds.coerceIn(1, pool.size))
    }

    fun filterRotatingExcludingTrending(
        candidates: List<Song>,
        trending: List<Song>,
        spin: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 14,
    ): List<Song> {
        val trendingIds = trending.mapTo(mutableSetOf()) { it.id }
        val trendingRecordings = trending.mapTo(mutableSetOf()) {
            RecordingIdentityResolver.resolve(it).canonicalRecordingId
        }
        val bucket = (nowMs / ROTATION_WINDOW_MS) + spin * 31L
        val shuffled = candidates
            .distinctBy { it.id }
            .filter { song ->
                song.id !in trendingIds &&
                    RecordingIdentityResolver.resolve(song).canonicalRecordingId !in trendingRecordings
            }
            .shuffled(Random(bucket))

        // Interleave across artists so rotating shelf never clusters one singer consecutively
        val byArtist = linkedMapOf<String, ArrayDeque<Song>>()
        for (song in shuffled) {
            val key = song.artist.trim().lowercase(Locale.ROOT)
            byArtist.getOrPut(key) { ArrayDeque() }.addLast(song)
        }
        val diversified = mutableListOf<Song>()
        while (diversified.size < limit && byArtist.values.any { it.isNotEmpty() }) {
            for (queue in byArtist.values) {
                if (diversified.size >= limit) break
                if (queue.isNotEmpty()) diversified += queue.removeFirst()
            }
        }
        return diversified
    }
}

class GenreViewModel : ViewModel() {

    private companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private val hubCache = ConcurrentHashMap<String, GenreHubContent>()
    }

    private val _uiState = MutableStateFlow(GenreUiState())
    val uiState: StateFlow<GenreUiState> = _uiState.asStateFlow()

    private var activeGenreName: String = ""
    private var rotationSpin: Int = 0
    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            VelvetApp.library.data
                .map { LanguageLocaleConfig.fromSelectedLanguages(it.musicLanguages) }
                .distinctUntilChanged()
                .collect { langState ->
                    if (activeGenreName.isNotBlank()) {
                        loadInternal(activeGenreName, langState, forceRefresh = false)
                    }
                }
        }
    }

    fun load(genreName: String, forceRefresh: Boolean = false) {
        val trimmed = genreName.trim()
        if (trimmed.isEmpty()) return
        val nameChanged = activeGenreName != trimmed
        activeGenreName = trimmed
        val langState = LanguageLocaleConfig.fromSelectedLanguages(
            runCatching { VelvetApp.library.data.value.musicLanguages }.getOrDefault(emptyList()),
        )
        val resolvedSpec = GenreRotationStrategy.resolveSpec(trimmed, langState)
        loadInternal(
            trimmed,
            langState,
            forceRefresh = forceRefresh || (nameChanged && !hubCache.containsKey(cacheKey(resolvedSpec, langState))),
        )
    }

    fun refresh() {
        if (activeGenreName.isBlank()) return
        rotationSpin += 1
        val langState = LanguageLocaleConfig.fromSelectedLanguages(
            runCatching { VelvetApp.library.data.value.musicLanguages }.getOrDefault(emptyList()),
        )
        loadInternal(activeGenreName, langState, forceRefresh = true)
    }

    private fun cacheKey(spec: GenreTileSpec, langState: SearchLanguageState): String =
        "${spec.id}|${langState.key}"

    private fun loadInternal(
        genreName: String,
        langState: SearchLanguageState,
        forceRefresh: Boolean,
    ) {
        val spec = GenreRotationStrategy.resolveSpec(genreName, langState)
        val key = cacheKey(spec, langState)
        val cached = hubCache[key]
        val now = System.currentTimeMillis()
        val isCacheFresh = cached != null && (now - cached.loadedAtMs) in 0 until CACHE_TTL_MS

        if (cached != null) {
            _uiState.value = GenreUiState(
                genreName = genreName,
                spec = spec,
                languageSummary = langState.summaryLabel,
                isLoading = false,
                isRefreshing = forceRefresh || !isCacheFresh,
                content = cached,
                error = null,
                refreshWarning = null,
            )
            if (!forceRefresh && isCacheFresh) return
        } else {
            _uiState.value = GenreUiState(
                genreName = genreName,
                spec = spec,
                languageSummary = langState.summaryLabel,
                isLoading = true,
                isRefreshing = false,
                content = null,
                error = null,
                refreshWarning = null,
            )
        }

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    fetchGenreHub(spec, langState, rotationSpin)
                }
                if (loaded.isEmpty) {
                    val prev = _uiState.value.content
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = if (prev == null) "No $genreName releases found for ${langState.summaryLabel} right now." else null,
                        refreshWarning = if (prev != null) "Couldn't refresh picks right now." else null,
                    )
                } else {
                    hubCache[key] = loaded
                    _uiState.value = GenreUiState(
                        genreName = genreName,
                        spec = spec,
                        languageSummary = langState.summaryLabel,
                        isLoading = false,
                        isRefreshing = false,
                        content = loaded,
                        error = null,
                        refreshWarning = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val prev = _uiState.value.content
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = if (prev == null) (e.message ?: "Couldn't load $genreName discovery hub") else null,
                    refreshWarning = if (prev != null) "Offline or timed out — showing saved $genreName picks." else null,
                )
            }
        }
    }

    private suspend fun fetchGenreHub(
        spec: GenreTileSpec,
        langState: SearchLanguageState,
        spin: Int,
    ): GenreHubContent = coroutineScope {
        val now = System.currentTimeMillis()
        val scopedTrendingQuery = SearchLanguageFilter.scopeSearchQuery(spec.trendingSeed, langState, forceLanguageHint = true)
        val scopedAlbumQuery = SearchLanguageFilter.scopeSearchQuery(spec.albumSeed, langState, forceLanguageHint = true)
        val rotatingQueries = GenreRotationStrategy.selectRotatingSeeds(spec, spin, now, maxSeeds = 2)
            .map { SearchLanguageFilter.scopeSearchQuery(it, langState, forceLanguageHint = true) }

        val trendingInnertubeJob = async {
            withTimeoutOrNull(3_800L) {
                runCatching {
                    YouTubeMusicInnertube.searchSongs(
                        query = scopedTrendingQuery,
                        limit = 20,
                        glOverride = langState.gl,
                        hlOverride = langState.hl,
                    )
                }.getOrDefault(emptyList())
            }.orEmpty()
        }
        val trendingSearchJob = async {
            withTimeoutOrNull(4_000L) {
                runCatching {
                    YouTube.Search(spec.trendingSeed, YouTube.FILTER_SONGS, langState)
                        .loadNext()
                        .filterIsInstance<BrowseItem.SongItem>()
                        .map { it.song }
                }.getOrDefault(emptyList())
            }.orEmpty()
        }
        val chartsOverlapJob = async {
            withTimeoutOrNull(2_800L) {
                runCatching {
                    YouTubeMusicInnertube.fetchTrendingCharts(
                        preferredLanguages = langState.selectedLanguages,
                        limit = 20,
                    )
                }.getOrDefault(emptyList())
            }.orEmpty()
        }
        val albumsJob = async {
            withTimeoutOrNull(4_200L) {
                runCatching {
                    YouTube.Search(scopedAlbumQuery, YouTube.FILTER_ALBUMS, langState)
                        .loadNext()
                        .filterIsInstance<BrowseItem.AlbumItem>()
                        .map { it.album }
                }.getOrDefault(emptyList())
            }.orEmpty()
        }
        val playlistJobs = spec.playlistSeeds.take(3).map { seed ->
            val q = SearchLanguageFilter.scopeSearchQuery(seed, langState, forceLanguageHint = true)
            async {
                withTimeoutOrNull(4_000L) {
                    runCatching {
                        YouTube.Search(q, YouTube.FILTER_PLAYLISTS, langState)
                            .loadNext()
                            .filterIsInstance<BrowseItem.AlbumItem>()
                            .map { it.album }
                            .take(6)
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }
        val rotatingJobs = rotatingQueries.map { q ->
            async {
                withTimeoutOrNull(3_600L) {
                    runCatching {
                        YouTubeMusicInnertube.searchSongs(
                            query = q,
                            limit = 16,
                            glOverride = langState.gl,
                            hlOverride = langState.hl,
                        )
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }

        val innertubeTrending = trendingInnertubeJob.await()
        val newpipeTrending = trendingSearchJob.await()
        val chartTracks = chartsOverlapJob.await()
        val chartIds = chartTracks.mapTo(mutableSetOf()) { it.id }

        val mergedTrendingRaw = (innertubeTrending + newpipeTrending)
            .filter { it.isPureMusic() && it.isOriginalMusicAudio() }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<Song> { it.id in chartIds }
                    .thenByDescending { it.preferredMusicSource }
                    .thenByDescending { !CoverArtResolver.isGenericVideoFallbackThumb(it.thumb) },
            )
        val filteredTrending = SearchLanguageFilter.filterSongs(
            songs = mergedTrendingRaw,
            state = langState,
            explicitQuery = spec.trendingSeed,
        ).distinctBy { RecordingIdentityResolver.resolve(it).canonicalRecordingId }
            .take(16)

        val latestAlbums = SearchLanguageFilter.filterAlbums(
            albums = albumsJob.await().distinctBy { it.url },
            state = langState,
            explicitQuery = spec.albumSeed,
        ).take(12)

        val playlistBuckets = playlistJobs.awaitAll()
        val interleavedPlaylists = mutableListOf<AlbumRef>()
        val maxBucket = playlistBuckets.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxBucket) {
            for (b in playlistBuckets) {
                b.getOrNull(i)?.let { interleavedPlaylists += it }
            }
        }
        val curatedPlaylists = SearchLanguageFilter.filterAlbums(
            albums = interleavedPlaylists.distinctBy { it.url },
            state = langState,
            explicitQuery = spec.title,
        ).take(14)

        val rawRotating = rotatingJobs.awaitAll().flatten() +
            InstantTrackCatalog.snapshot().filter { song ->
                song.isPureMusic() && song.isOriginalMusicAudio()
            }
        val langRotating = SearchLanguageFilter.filterSongs(
            songs = rawRotating,
            state = langState,
            explicitQuery = spec.title,
        )
        val rotatingRecommendations = GenreRotationStrategy.filterRotatingExcludingTrending(
            candidates = langRotating,
            trending = filteredTrending,
            spin = spin,
            nowMs = now,
            limit = 14,
        )

        GenreHubContent(
            spec = spec,
            languageState = langState,
            trendingTracks = filteredTrending,
            latestAlbums = latestAlbums,
            curatedPlaylists = curatedPlaylists,
            rotatingRecommendations = rotatingRecommendations,
            rotationEpoch = spin,
            loadedAtMs = now,
        )
    }
}

@Composable
fun GenreScreen(name: String, onBack: () -> Unit) {
    val vm: GenreViewModel = viewModel(key = "genre-hub-$name")
    val state by vm.uiState.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(name) {
        vm.load(name)
    }

    val spec = state.spec ?: GenreRotationStrategy.resolveSpec(name, LanguageLocaleConfig.fromSelectedLanguages(emptyList()))
    val primaryColor = Color(spec.primaryColorArgb)
    val secondaryColor = Color(spec.secondaryColorArgb)
    val content = state.content

    BackPage(
        onBack = onBack,
        heading = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = velvetColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = CircleShape,
                    color = velvetColors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, velvetColors.glassBorder),
                ) {
                    Text(
                        text = state.languageSummary.ifBlank { "For You" },
                        style = MaterialTheme.typography.labelMedium,
                        color = velvetColors.accentText,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        },
    ) {
        // 1. Dynamic vibrant genre hero banner
        item(key = "genre_hero") {
            GenreHeroBanner(
                title = spec.title,
                subtitle = spec.subtitle,
                languageSummary = state.languageSummary,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                trendingTracks = content?.trendingTracks.orEmpty(),
                isRefreshing = state.isRefreshing,
                onPlayAll = {
                    val tracks = content?.trendingTracks.orEmpty()
                    if (tracks.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        PlayerConnection.play(tracks, 0)
                    }
                },
                onShuffleAll = {
                    val tracks = content?.trendingTracks.orEmpty()
                    if (tracks.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        PlayerConnection.play(tracks, 0, shuffled = true)
                    }
                },
                onRefresh = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    vm.refresh()
                },
            )
        }

        // Optional non-intrusive banner when stale cache is shown while offline
        state.refreshWarning?.let { warning ->
            item(key = "genre_refresh_warn") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(velvetColors.surface)
                        .border(1.dp, velvetColors.glassBorder, RoundedCornerShape(12.dp))
                        .clickable { vm.refresh() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Retry",
                        style = MaterialTheme.typography.labelMedium,
                        color = velvetColors.accentText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        when {
            state.isLoading && content == null -> {
                item(key = "genre_shimmer") {
                    GenreHubShimmer()
                }
            }
            content == null && state.error != null -> {
                item(key = "genre_error") {
                    ErrorBox(state.error ?: "Couldn't load $name") {
                        vm.load(name, forceRefresh = true)
                    }
                }
            }
            content != null -> {
                // 2. Trending Now in [Genre]
                if (content.trendingTracks.isNotEmpty()) {
                    item(key = "sec_trending_hdr") {
                        SectionTitle(
                            text = "Trending Now in ${spec.title}",
                            action = "Play all",
                            onAction = { PlayerConnection.play(content.trendingTracks, 0) },
                        )
                    }
                    itemsIndexed(
                        items = content.trendingTracks.take(10),
                        key = { _, s -> "tr-${s.id}" },
                    ) { idx, song ->
                        SongRow(
                            song = song,
                            trackNumber = idx + 1,
                            modifier = Modifier.animateItem(placementSpec = VelvetMotion.Springs.ListPlacement),
                            onClick = { PlayerConnection.play(content.trendingTracks, idx) },
                        )
                    }
                }

                // 3. Latest Releases & New Albums
                if (content.latestAlbums.isNotEmpty()) {
                    item(key = "sec_albums_hdr") {
                        SectionTitle("Latest Releases & New Albums")
                    }
                    item(key = "sec_albums_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(content.latestAlbums, key = { "al-${it.url}" }) { album ->
                                AlbumCard(album = album, size = 152.dp) {
                                    nav.open(Route.Album(album))
                                }
                            }
                        }
                    }
                }

                // 4. Curated & Specialized Playlists
                if (content.curatedPlaylists.isNotEmpty()) {
                    item(key = "sec_playlists_hdr") {
                        SectionTitle("Curated & Specialized Playlists")
                    }
                    item(key = "sec_playlists_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(content.curatedPlaylists, key = { "pl-${it.url}" }) { playlist ->
                                AlbumCard(album = playlist, size = 152.dp) {
                                    nav.open(Route.Album(playlist))
                                }
                            }
                        }
                    }
                }

                // 5. Dynamic / Rotating Recommendations
                if (content.rotatingRecommendations.isNotEmpty()) {
                    item(key = "sec_rotating_hdr") {
                        SectionTitle(
                            text = "Fresh & Rotating Picks",
                            action = if (state.isRefreshing) "Rotating…" else "Rotate",
                            onAction = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.refresh()
                            },
                        )
                    }
                    itemsIndexed(
                        items = content.rotatingRecommendations,
                        key = { _, s -> "rot-${content.rotationEpoch}-${s.id}" },
                    ) { idx, song ->
                        SongRow(
                            song = song,
                            modifier = Modifier.animateItem(placementSpec = VelvetMotion.Springs.ListPlacement),
                            onClick = { PlayerConnection.play(content.rotatingRecommendations, idx) },
                        )
                    }
                }

                if (content.isEmpty) {
                    item(key = "genre_empty") {
                        EmptyHint("No $name discoveries match your selected language right now.")
                    }
                }
            }
        }

        item(key = "genre_bottom_pad") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GenreHeroBanner(
    title: String,
    subtitle: String,
    languageSummary: String,
    primaryColor: Color,
    secondaryColor: Color,
    trendingTracks: List<Song>,
    isRefreshing: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onRefresh: () -> Unit,
) {
    val refreshSpin by animateFloatAsState(
        targetValue = if (isRefreshing) 360f else 0f,
        animationSpec = if (isRefreshing) tween(750, easing = LinearEasing) else VelvetMotion.fastTween(),
        label = "genreRefreshSpin",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(primaryColor, secondaryColor.copy(alpha = 0.88f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.24f),
                ) {
                    Text(
                        text = if (trendingTracks.isNotEmpty()) {
                            "Discovery Hub • ${trendingTracks.size} picks"
                        } else {
                            "Discovery Hub"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.22f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = Color.White),
                            role = Role.Button,
                            onClick = onRefresh,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh picks",
                        tint = Color.White,
                        modifier = Modifier
                            .size(19.dp)
                            .graphicsLayer { rotationZ = refreshSpin },
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.90f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val playInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .velvetTactilePress(playInteraction, enabled = trendingTracks.isNotEmpty())
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(
                            enabled = trendingTracks.isNotEmpty(),
                            interactionSource = playInteraction,
                            indication = ripple(color = primaryColor),
                            role = Role.Button,
                            onClick = onPlayAll,
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AppIcons.Play, null, tint = Color(0xFF101010), modifier = Modifier.size(18.dp))
                    Text("Play Trending", style = MaterialTheme.typography.labelLarge, color = Color(0xFF101010), fontWeight = FontWeight.Bold)
                }

                val shuffleInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .velvetTactilePress(shuffleInteraction, enabled = trendingTracks.isNotEmpty())
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.28f))
                        .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                        .clickable(
                            enabled = trendingTracks.isNotEmpty(),
                            interactionSource = shuffleInteraction,
                            indication = ripple(color = Color.White),
                            role = Role.Button,
                            onClick = onShuffleAll,
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AppIcons.Shuffle, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    Text("Shuffle", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun GenreHubShimmer() {
    val shimmer = rememberVelvetShimmerBrush()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Section header + track skeletons
        Box(
            Modifier
                .width(190.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer),
        )
        repeat(5) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(shimmer),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmer),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.45f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmer),
                    )
                }
            }
        }

        // Albums horizontal shelf skeleton
        Box(
            Modifier
                .width(210.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(shimmer),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(144.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(shimmer),
                    )
                    Box(
                        Modifier
                            .width(110.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmer),
                    )
                }
            }
        }
    }
}
