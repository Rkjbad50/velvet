package com.velvet.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.velvet.app.VelvetApp
import com.velvet.app.data.BrowseItem
import com.velvet.app.data.GenreTileSpec
import com.velvet.app.data.LanguageLocaleConfig
import com.velvet.app.data.SearchLanguageFilter
import com.velvet.app.data.SearchLanguageState
import com.velvet.app.data.Song
import com.velvet.app.data.YouTube
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.recommendation.model.PlaybackAttribution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Body modes displayed below the Search bar. Recent searches remain hidden on [IDLE_HOME]
 * and only appear once the user actively taps/focuses the search bar ([FOCUSED_RECENTS]).
 */
enum class SearchBodyMode {
    IDLE_HOME,
    FOCUSED_RECENTS,
    SUGGESTIONS,
    RESULTS,
}

/**
 * Resolves the active [SearchBodyMode] from result state, query text, and search-bar focus.
 */
internal fun resolveSearchBodyMode(
    showingResults: Boolean,
    query: String,
    isSearchFocused: Boolean,
): SearchBodyMode = when {
    showingResults -> SearchBodyMode.RESULTS
    query.isNotBlank() -> SearchBodyMode.SUGGESTIONS
    isSearchFocused -> SearchBodyMode.FOCUSED_RECENTS
    else -> SearchBodyMode.IDLE_HOME
}

enum class SearchFilter(val label: String, val ytFilter: String?) {
    All("All", null),
    Songs("Songs", YouTube.FILTER_SONGS),
    Artists("Artists", YouTube.FILTER_ARTISTS),
    Albums("Albums", YouTube.FILTER_ALBUMS),
    Playlists("Playlists", YouTube.FILTER_PLAYLISTS),
}

class SearchViewModel : ViewModel() {

    private companion object {
        private val tileArtCache = ConcurrentHashMap<String, String>()
    }

    /** Single authoritative Selected Language StateFlow shared across Search UI & APIs. */
    val languageState: StateFlow<SearchLanguageState> = VelvetApp.library.data
        .map { LanguageLocaleConfig.fromSelectedLanguages(it.musicLanguages) }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LanguageLocaleConfig.fromSelectedLanguages(
                runCatching { VelvetApp.library.data.value.musicLanguages }.getOrDefault(emptyList()),
            ),
        )

    val preHintQueries: StateFlow<List<String>> = languageState
        .map { LanguageLocaleConfig.preHintQueriesFor(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LanguageLocaleConfig.preHintQueriesFor(languageState.value),
        )

    val genreTiles: StateFlow<List<GenreTileSpec>> = languageState
        .map { LanguageLocaleConfig.genreCatalogFor(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LanguageLocaleConfig.genreCatalogFor(languageState.value),
        )

    private val _genreArtUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val genreArtUrls: StateFlow<Map<String, String>> = _genreArtUrls.asStateFlow()

    var query by mutableStateOf("")
        private set
    var filter by mutableStateOf(SearchFilter.All)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var top by mutableStateOf<YouTube.TopResults?>(null)
        private set
    var items by mutableStateOf<List<BrowseItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showingResults by mutableStateOf(false)
        private set

    var activeSearchSessionId by mutableStateOf<String?>(null)
        private set
    var activeQueryId by mutableStateOf<String?>(null)
        private set
    var homeResetNonce by mutableIntStateOf(0)
        private set

    private var pager: YouTube.Search? = null
    private var paging = false
    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var genreArtJob: Job? = null
    private var lastAppliedLanguageKey: String? = null

    init {
        viewModelScope.launch {
            languageState.collect { state ->
                val changed = lastAppliedLanguageKey != null && lastAppliedLanguageKey != state.key
                lastAppliedLanguageKey = state.key
                loadGenreTileArtwork(state, LanguageLocaleConfig.genreCatalogFor(state))
                if (changed) {
                    suggestions = emptyList()
                    if (showingResults && query.isNotBlank()) {
                        submit(searchQuery = query, remember = false)
                    } else if (query.isNotBlank()) {
                        onQuery(query)
                    }
                }
            }
        }
    }

    private fun loadGenreTileArtwork(state: SearchLanguageState, tiles: List<GenreTileSpec>) {
        // Hydrate any cached entries immediately without waiting for network
        val initialMap = buildMap {
            for (tile in tiles) {
                tileArtCache["${tile.id}|${state.key}"]?.let { put(tile.id, it) }
            }
        }
        _genreArtUrls.value = initialMap

        val missing = tiles.filter { !tileArtCache.containsKey("${it.id}|${state.key}") }
        if (missing.isEmpty()) return

        genreArtJob?.cancel()
        genreArtJob = viewModelScope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(3)
            for (tile in missing) {
                semaphore.withPermit {
                    val scopedQuery = SearchLanguageFilter.scopeSearchQuery(
                        query = tile.trendingSeed,
                        state = state,
                        forceLanguageHint = true,
                    )
                    val songs = withTimeoutOrNull(2_400L) {
                        runCatching {
                            YouTubeMusicInnertube.searchSongs(
                                query = scopedQuery,
                                limit = 5,
                                glOverride = state.gl,
                                hlOverride = state.hl,
                            )
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                    val filtered = SearchLanguageFilter.filterSongs(songs, state, tile.trendingSeed)
                    val artUrl = filtered.firstNotNullOfOrNull { s ->
                        s.thumb?.takeIf { it.isNotBlank() && !it.contains("i.ytimg.com/vi/") }
                    } ?: filtered.firstOrNull()?.thumb
                    if (!artUrl.isNullOrBlank()) {
                        tileArtCache["${tile.id}|${state.key}"] = artUrl
                        withContext(Dispatchers.Main) {
                            _genreArtUrls.value = _genreArtUrls.value + (tile.id to artUrl)
                        }
                    }
                }
            }
        }
    }

    fun onQuery(newQuery: String) {
        query = newQuery
        showingResults = false
        error = null
        suggestJob?.cancel()
        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            suggestions = emptyList()
            return
        }
        val langState = languageState.value
        val localHints = preHintQueries.value
            .filter { it.contains(trimmed, ignoreCase = true) }
        suggestions = localHints

        suggestJob = viewModelScope.launch {
            delay(220)
            val remote = withContext(Dispatchers.IO) {
                runCatching { YouTube.suggestions(trimmed, langState) }.getOrDefault(emptyList())
            }
            val merged = SearchLanguageFilter.filterSuggestions(
                suggestions = localHints + remote,
                rawQuery = trimmed,
                state = langState,
            )
            if (query.trim() == trimmed && !showingResults) {
                suggestions = merged.take(10)
            }
        }
    }

    fun clear() {
        suggestJob?.cancel()
        searchJob?.cancel()
        query = ""
        suggestions = emptyList()
        top = null
        items = emptyList()
        pager = null
        loading = false
        error = null
        showingResults = false
        activeQueryId = null
    }

    /** Clears active search state and signals the Search screen to drop focus back to idle browse. */
    fun resetToHome() {
        clear()
        homeResetNonce += 1
    }

    fun submit(searchQuery: String = query, remember: Boolean = true) {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        query = q
        showingResults = true
        suggestions = emptyList()
        suggestJob?.cancel()
        if (remember) VelvetApp.library.addSearch(q)

        val sessionId = activeSearchSessionId ?: UUID.randomUUID().toString().also {
            activeSearchSessionId = it
        }
        val queryId = UUID.randomUUID().toString()
        activeQueryId = queryId
        VelvetApp.eventRecorderOrNull()?.recordSearchSubmitted(
            queryId = queryId,
            rawQuery = q,
            searchSessionId = sessionId,
        )

        val langState = languageState.value
        searchJob?.cancel()
        top = null
        items = emptyList()
        pager = null
        error = null
        loading = true

        searchJob = viewModelScope.launch {
            try {
                if (filter == SearchFilter.All) {
                    val results = withContext(Dispatchers.IO) {
                        YouTube.searchAll(q, languageState = langState)
                    }
                    top = results
                    val impressionCandidates = results.songs.take(8)
                    if (impressionCandidates.isNotEmpty()) {
                        VelvetApp.eventRecorderOrNull()?.let { rec ->
                            impressionCandidates.forEachIndexed { idx, song ->
                                rec.recordSearchResultImpression(
                                    queryId = queryId,
                                    rawQuery = q,
                                    requestId = queryId,
                                    position = idx,
                                    song = song,
                                    searchSessionId = sessionId,
                                )
                            }
                        }
                    }
                } else {
                    val search = YouTube.Search(q, filter.ytFilter!!, languageState = langState)
                    pager = search
                    val loadedItems = withContext(Dispatchers.IO) { search.loadNext() }
                    items = loadedItems
                    val songCandidates = loadedItems.filterIsInstance<BrowseItem.SongItem>().map { it.song }.take(8)
                    if (songCandidates.isNotEmpty()) {
                        VelvetApp.eventRecorderOrNull()?.let { rec ->
                            songCandidates.forEachIndexed { idx, song ->
                                rec.recordSearchResultImpression(
                                    queryId = queryId,
                                    rawQuery = q,
                                    requestId = queryId,
                                    position = idx,
                                    song = song,
                                    searchSessionId = sessionId,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            } finally {
                loading = false
            }
        }
    }

    fun selectFilter(f: SearchFilter) {
        if (filter == f) return
        filter = f
        if (showingResults) submit(remember = false)
    }

    fun loadMore() {
        val s = pager ?: return
        if (paging || loading || !s.hasMore) return
        paging = true
        viewModelScope.launch {
            try {
                items = items + withContext(Dispatchers.IO) { s.loadNext() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // ignore load-more transient failure cleanly
            } finally {
                paging = false
            }
        }
    }
}

@Composable
fun SearchScreen(vm: SearchViewModel) {
    val library = VelvetApp.library
    val data by library.data.collectAsStateWithLifecycle()
    val langState by vm.languageState.collectAsStateWithLifecycle()
    val preHintQueries by vm.preHintQueries.collectAsStateWithLifecycle()
    val genreTiles by vm.genreTiles.collectAsStateWithLifecycle()
    val genreArtUrls by vm.genreArtUrls.collectAsStateWithLifecycle()

    val nav = LocalNav.current
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    var languageSheetOpen by remember { mutableStateOf(false) }
    var isSearchFocused by rememberSaveable { mutableStateOf(false) }

    fun exitSearch() {
        keyboardController?.hide()
        focus.clearFocus()
        isSearchFocused = false
        vm.clear()
    }

    fun submitPickedQuery(picked: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        keyboardController?.hide()
        focus.clearFocus()
        isSearchFocused = false
        vm.submit(picked)
    }

    BackHandler(
        enabled = nav.tab == 1 &&
            nav.currentEntries.isEmpty() &&
            !nav.nowPlayingOpen &&
            !languageSheetOpen &&
            (isSearchFocused || vm.showingResults || vm.query.isNotBlank()),
    ) {
        exitSearch()
    }

    LaunchedEffect(nav.tab) {
        if (nav.tab != 1) {
            keyboardController?.hide()
            focus.clearFocus()
            if (!vm.showingResults && vm.query.isBlank()) {
                isSearchFocused = false
            }
        }
    }

    LaunchedEffect(vm.homeResetNonce) {
        if (vm.homeResetNonce > 0) {
            keyboardController?.hide()
            focus.clearFocus()
            isSearchFocused = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { isSearchFocused = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // Modern Search Header + Active Language Switcher Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineMedium,
                color = velvetColors.text,
            )
            val langChipInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .velvetTactilePress(langChipInteraction, pressedScale = 0.94f)
                    .captureOriginOnPress(cornerRadiusDp = 18f)
                    .clip(CircleShape)
                    .background(velvetColors.surface)
                    .border(1.dp, velvetColors.glassBorder, CircleShape)
                    .clickable(
                        interactionSource = langChipInteraction,
                        indication = ripple(color = velvetColors.accent),
                        role = Role.Button,
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        languageSheetOpen = true
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(velvetColors.accent),
                )
                Text(
                    text = langState.summaryLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = velvetColors.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val hintExample = preHintQueries.firstOrNull() ?: "${langState.primaryLanguage} hits"
        val isSearchActive = isSearchFocused || vm.query.isNotEmpty() || vm.showingResults
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQuery,
            placeholder = {
                Text(
                    text = "Search songs, artists, albums in ${langState.primaryLanguage}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (isSearchActive) {
                        IconButton(
                            onClick = { exitSearch() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = velvetColors.text,
                            )
                        }
                    } else {
                        Icon(Icons.Default.Search, null, tint = velvetColors.muted)
                    }
                }
            },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            vm.clear()
                            isSearchFocused = true
                            runCatching { searchFocusRequester.requestFocus() }
                            keyboardController?.show()
                        },
                    ) {
                        Icon(Icons.Default.Clear, "Clear search query")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = velvetColors.surface,
                unfocusedContainerColor = velvetColors.surface,
                focusedBorderColor = velvetColors.accent,
                unfocusedBorderColor = velvetColors.glassBorder,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (vm.query.isNotBlank()) {
                    keyboardController?.hide()
                    focus.clearFocus()
                    isSearchFocused = false
                    vm.submit()
                }
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .zIndex(1f)
                .focusRequester(searchFocusRequester)
                .onFocusChanged { state ->
                    isSearchFocused = state.isFocused
                },
        )

        AnimatedVisibility(
            visible = vm.showingResults,
            enter = fadeIn(VelvetMotion.fastTween()),
            exit = fadeOut(VelvetMotion.fastTween()),
        ) {
            FilterChips(vm.filter, vm::selectFilter)
        }

        val viewMode = resolveSearchBodyMode(
            showingResults = vm.showingResults,
            query = vm.query,
            isSearchFocused = isSearchFocused,
        )

        AnimatedContent(
            targetState = viewMode,
            transitionSpec = {
                (fadeIn(VelvetMotion.fastTween()) togetherWith fadeOut(VelvetMotion.fastTween(110)))
                    .using(SizeTransform(clip = true) { _, _ -> snap() })
            },
            label = "searchBodyTransition",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) { mode ->
            when (mode) {
                SearchBodyMode.RESULTS -> SearchResults(vm)
                SearchBodyMode.SUGGESTIONS -> Suggestions(
                    list = vm.suggestions,
                    placeholderHint = hintExample,
                    loading = false,
                    onPick = { submitPickedQuery(it) },
                )
                SearchBodyMode.FOCUSED_RECENTS -> FocusedRecentsView(
                    recentSearches = data.searches,
                    langState = langState,
                    preHintQueries = preHintQueries,
                    onRecentClick = { submitPickedQuery(it) },
                    onRemoveRecent = library::removeSearch,
                    onClearRecent = library::clearSearches,
                    onQuickPickClick = { submitPickedQuery(it) },
                )
                SearchBodyMode.IDLE_HOME -> SearchHome(
                    langState = langState,
                    preHintQueries = preHintQueries,
                    genreTiles = genreTiles,
                    genreArtUrls = genreArtUrls,
                    onPreHintClick = { submitPickedQuery(it) },
                    onGenre = { genre ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        nav.open(Route.Genre(genre.title))
                    },
                )
            }
        }
    }

    if (languageSheetOpen) {
        SearchLanguageSelectorSheet(
            selected = langState.selectedLanguages,
            onDismiss = { languageSheetOpen = false },
            onSave = { updated ->
                library.setMusicLanguages(updated)
                languageSheetOpen = false
            },
        )
    }
}

@Composable
private fun FilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchFilter.entries.forEach { f ->
            BrowseChip(f.label, selected = selected == f) { onSelect(f) }
        }
    }
}

@Composable
private fun Suggestions(
    list: List<String>,
    placeholderHint: String,
    loading: Boolean,
    onPick: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (list.isEmpty() && !loading) {
            item(key = "hint_empty") {
                EmptyHint("Try searching \"$placeholderHint\" or press Search on your keyboard.")
            }
        }
        items(list, key = { it }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem(placementSpec = VelvetMotion.Springs.ListPlacement)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = velvetColors.accent),
                        onClick = { onPick(item) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Default.Search, null, tint = velvetColors.muted, modifier = Modifier.size(18.dp))
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyLarge,
                    color = velvetColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(Modifier.padding(start = 52.dp, end = 20.dp), color = velvetColors.rule)
        }
    }
}

@Composable
private fun QuickPickChipsRow(
    langKey: String,
    queries: List<String>,
    onPick: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(queries, key = { "$langKey:$it" }) { hint ->
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .velvetTactilePress(interaction, pressedScale = 0.94f)
                    .clip(CircleShape)
                    .background(velvetColors.surface)
                    .border(1.dp, velvetColors.glassBorder, CircleShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(color = velvetColors.accent),
                        role = Role.Button,
                        onClick = { onPick(hint) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    AppIcons.Sparkles,
                    null,
                    tint = velvetColors.accentText,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelLarge,
                    color = velvetColors.text,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FocusedRecentsView(
    recentSearches: List<String>,
    langState: SearchLanguageState,
    preHintQueries: List<String>,
    onRecentClick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onQuickPickClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (recentSearches.isNotEmpty()) {
            item(key = "focused_recent_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Recent searches",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = velvetColors.text,
                    )
                    TextButton(onClick = onClearRecent) {
                        Text("Clear")
                    }
                }
            }
            item(key = "focused_recent_list") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(velvetColors.surface)
                        .border(1.dp, velvetColors.glassBorder, RoundedCornerShape(16.dp)),
                ) {
                    recentSearches.forEachIndexed { idx, q ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = velvetColors.rule,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = velvetColors.accent),
                                    onClick = { onRecentClick(q) },
                                )
                                .padding(start = 16.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                AppIcons.History,
                                null,
                                tint = velvetColors.muted,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = q,
                                style = MaterialTheme.typography.bodyLarge,
                                color = velvetColors.text,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { onRemoveRecent(q) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove $q",
                                    tint = velvetColors.muted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "focused_recent_empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "No recent searches yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = velvetColors.text,
                    )
                    Text(
                        text = "Type a song, artist, or album — or pick a quick search in ${langState.summaryLabel}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = velvetColors.muted,
                    )
                }
            }
            if (preHintQueries.isNotEmpty()) {
                item(key = "focused_quick_picks_header") {
                    Text(
                        text = "Quick picks in ${langState.summaryLabel}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = velvetColors.text,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item(key = "focused_quick_picks_strip") {
                    QuickPickChipsRow(
                        langKey = langState.key,
                        queries = preHintQueries,
                        onPick = onQuickPickClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHome(
    langState: SearchLanguageState,
    preHintQueries: List<String>,
    genreTiles: List<GenreTileSpec>,
    genreArtUrls: Map<String, String>,
    onPreHintClick: (String) -> Unit,
    onGenre: (GenreTileSpec) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Pre-hint language-scoped quick picks
        if (preHintQueries.isNotEmpty()) {
            item(key = "pre_hints_header", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Quick picks in ${langState.summaryLabel}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = velvetColors.text,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            item(key = "pre_hints_strip", span = { GridItemSpan(maxLineSpan) }) {
                QuickPickChipsRow(
                    langKey = langState.key,
                    queries = preHintQueries,
                    onPick = onPreHintClick,
                )
            }
        }

        // 2. Spotify-style Browse All / Genres responsive grid
        item(key = "genres_header", span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                Text(
                    text = "Browse genres",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = velvetColors.text,
                )
                Text(
                    text = "Tuned for ${langState.summaryLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = velvetColors.muted,
                )
            }
        }

        gridItems(
            items = genreTiles,
            key = { "${langState.key}:${it.id}" },
        ) { tile ->
            SpotifyGenreTile(
                spec = tile,
                artworkUrl = genreArtUrls[tile.id],
                modifier = Modifier.animateItem(placementSpec = VelvetMotion.Springs.ListPlacement),
                onClick = { onGenre(tile) },
            )
        }
    }
}

@Composable
private fun SpotifyGenreTile(
    spec: GenreTileSpec,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val primaryColor = Color(spec.primaryColorArgb)
    val secondaryColor = Color(spec.secondaryColorArgb)
    val context = LocalContext.current
    val artRequest = remember(context, artworkUrl) {
        artworkUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(VelvetMotion.Duration.ArtworkCrossfade)
                .build()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(108.dp)
            .velvetTactilePress(interactionSource, pressedScale = 0.95f)
            .captureOriginOnPress(cornerRadiusDp = 16f)
            .shadow(6.dp, VelvetMotion.Shape.Tile, spotColor = primaryColor.copy(alpha = 0.35f))
            .clip(VelvetMotion.Shape.Tile)
            .background(
                Brush.linearGradient(
                    colors = listOf(primaryColor, secondaryColor),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), VelvetMotion.Shape.Tile)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.30f)),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "${spec.title} genre" },
    ) {
        // Subtle ambient glow arc behind angled artwork
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 18.dp, y = 18.dp)
                .size(86.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
        )

        // Bold title + 1-line subtitle pinned left so corner artwork never collides with text
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.68f)
                .padding(start = 14.dp, top = 13.dp, end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = spec.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.84f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Angled / cropped Spotify-style decorative cover card anchored bottom-right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 10.dp, y = 10.dp)
                .size(66.dp)
                .graphicsLayer { rotationZ = 22f }
                .shadow(10.dp, RoundedCornerShape(10.dp), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.25f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (spec.iconKey) {
                    "disc", "vinyl" -> AppIcons.Album
                    "sparkles" -> AppIcons.Sparkles
                    else -> AppIcons.MusicNote
                },
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.88f),
                modifier = Modifier.size(28.dp),
            )
            if (artRequest != null) {
                AsyncImage(
                    model = artRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SearchResults(vm: SearchViewModel) {
    val state = rememberLazyListState()
    val nav = LocalNav.current
    val langState by vm.languageState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (vm.filter != SearchFilter.All && last >= vm.items.size - 5) vm.loadMore() }
    }
    val top = vm.top
    val isEmptyAll = vm.filter == SearchFilter.All && top != null &&
        top.songs.isEmpty() && top.artists.isEmpty() && top.albums.isEmpty() && top.playlists.isEmpty()

    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        when {
            vm.error != null && top == null && vm.items.isEmpty() -> {
                item(key = "search_err") {
                    ErrorBox(vm.error ?: "") { vm.submit(remember = false) }
                }
            }
            vm.filter == SearchFilter.All && top != null -> {
                if (isEmptyAll) {
                    item(key = "search_empty_all") {
                        EmptyHint(
                            "No results found in ${langState.summaryLabel}. Try another query or add more listening languages above.",
                        )
                    }
                } else {
                    topResults(
                        top = top,
                        queryText = vm.query,
                        queryId = vm.activeQueryId,
                        searchSessionId = vm.activeSearchSessionId,
                        onMore = vm::selectFilter,
                    )
                }
            }
            vm.items.isNotEmpty() -> {
                val songList = vm.items.filterIsInstance<BrowseItem.SongItem>().map { it.song }
                itemsIndexed(vm.items, key = { idx, item ->
                    when (item) {
                        is BrowseItem.SongItem -> "s-${item.song.id}-$idx"
                        is BrowseItem.ArtistItem -> "a-${item.artist.url}-$idx"
                        is BrowseItem.AlbumItem -> "l-${item.album.url}-$idx"
                    }
                }) { idx, item ->
                    val qId = vm.activeQueryId
                    val sSessId = vm.activeSearchSessionId
                    Box(Modifier.animateItem(placementSpec = VelvetMotion.Springs.ListPlacement)) {
                        when (item) {
                            is BrowseItem.SongItem -> {
                                val songIdx = songList.indexOfFirst { it.id == item.song.id }.coerceAtLeast(0)
                                SongRow(
                                    song = item.song,
                                    onClick = {
                                        val attr = VelvetApp.eventRecorderOrNull()?.recordSearchResultClicked(
                                            queryId = qId ?: "search",
                                            rawQuery = vm.query,
                                            requestId = qId ?: "search",
                                            position = songIdx,
                                            song = item.song,
                                            searchSessionId = sSessId ?: qId ?: "search",
                                        ) ?: PlaybackAttribution()
                                        PlayerConnection.play(
                                            songs = songList.ifEmpty { listOf(item.song) },
                                            startIndex = songIdx,
                                            attribution = attr,
                                        )
                                    },
                                )
                            }
                            is BrowseItem.ArtistItem -> BrowseRow(
                                title = item.artist.name,
                                subtitle = "Artist",
                                thumb = item.artist.thumb,
                                round = true,
                                placeholder = Icons.Default.Person,
                                onClick = { nav.open(Route.Artist(item.artist)) },
                            )
                            is BrowseItem.AlbumItem -> BrowseRow(
                                title = item.album.title,
                                subtitle = item.album.subtitle,
                                thumb = item.album.thumb,
                                round = false,
                                placeholder = AppIcons.Album,
                                onClick = { nav.open(Route.Album(item.album)) },
                            )
                        }
                    }
                }
            }
            !vm.loading -> {
                item(key = "search_empty_tab") {
                    EmptyHint(
                        "No ${vm.filter.label.lowercase()} matched in ${langState.summaryLabel}.",
                    )
                }
            }
        }
        if (vm.loading) {
            item(key = "search_loading") { LoadingBox() }
        }
    }
}

private fun LazyListScope.topResults(
    top: YouTube.TopResults,
    queryText: String,
    queryId: String?,
    searchSessionId: String?,
    onMore: (SearchFilter) -> Unit,
) {
    if (top.songs.isNotEmpty()) {
        item(key = "top_songs_hdr") { BrowseSection("Songs", action = "See all") { onMore(SearchFilter.Songs) } }
        itemsIndexed(top.songs.take(5), key = { _, s -> "top-s-${s.id}" }) { idx, song ->
            Box(Modifier.animateItem(placementSpec = VelvetMotion.Springs.ListPlacement)) {
                SongRow(
                    song = song,
                    onClick = {
                        val attr = VelvetApp.eventRecorderOrNull()?.recordSearchResultClicked(
                            queryId = queryId ?: "search",
                            rawQuery = queryText,
                            requestId = queryId ?: "search",
                            position = idx,
                            song = song,
                            searchSessionId = searchSessionId ?: queryId ?: "search",
                        ) ?: PlaybackAttribution()
                        PlayerConnection.play(top.songs, idx, attribution = attr)
                    },
                )
            }
        }
    }
    if (top.artists.isNotEmpty()) {
        item(key = "top_artists_hdr") { BrowseSection("Artists", action = "See all") { onMore(SearchFilter.Artists) } }
        item(key = "top_artists_row") {
            val nav = LocalNav.current
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(top.artists.take(10), key = { "art-${it.url}" }) { artist ->
                    ArtistCircle(artist = artist) { nav.open(Route.Artist(artist)) }
                }
            }
        }
    }
    if (top.albums.isNotEmpty()) {
        item(key = "top_albums_hdr") { BrowseSection("Albums", action = "See all") { onMore(SearchFilter.Albums) } }
        item(key = "top_albums_row") {
            val nav = LocalNav.current
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(top.albums.take(10), key = { "alb-${it.url}" }) { album ->
                    AlbumCard(album = album) { nav.open(Route.Album(album)) }
                }
            }
        }
    }
    if (top.playlists.isNotEmpty()) {
        item(key = "top_pl_hdr") { BrowseSection("Playlists", action = "See all") { onMore(SearchFilter.Playlists) } }
        item(key = "top_pl_row") {
            val nav = LocalNav.current
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(top.playlists.take(10), key = { "pl-${it.url}" }) { pl ->
                    AlbumCard(album = pl) { nav.open(Route.Album(pl)) }
                }
            }
        }
    }
}

@Composable
private fun SearchLanguageSelectorSheet(
    selected: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val allLanguages = remember { LanguageLocaleConfig.allLanguages }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected) } }

    VelvetModalSheet(onDismissRequest = onDismiss) { sheet ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Search & discovery languages",
                style = MaterialTheme.typography.titleLarge,
                color = velvetColors.text,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Pre-hints, genres, suggestions, and search results stay synced to your picked languages.",
                style = MaterialTheme.typography.bodySmall,
                color = velvetColors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                allLanguages.forEach { spec ->
                    val checked = spec.id in chosen
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (checked) {
                                    if (chosen.size > 1) chosen.remove(spec.id)
                                } else {
                                    chosen.add(spec.id)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(spec.accentArgb)),
                            )
                            Text(
                                text = "${spec.id} (${spec.nativeLabel})",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (checked) velvetColors.accentText else velvetColors.text,
                                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                if (on) {
                                    if (spec.id !in chosen) chosen.add(spec.id)
                                } else if (chosen.size > 1) {
                                    chosen.remove(spec.id)
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = velvetColors.accent,
                                checkmarkColor = Color.White,
                                uncheckedColor = velvetColors.muted,
                            ),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { sheet.dismissThen(onDismiss) }) {
                    Text("Cancel", color = velvetColors.muted)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { sheet.dismissThen { onSave(chosen.toList()) } }) {
                    Text("Apply", color = velvetColors.accentText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
