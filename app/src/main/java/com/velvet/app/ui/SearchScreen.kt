package com.velvet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velvet.app.VelvetApp
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.BrowseItem
import com.velvet.app.data.Song
import com.velvet.app.data.YouTube
import com.velvet.app.recommendation.model.PlaybackAttribution
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchFilter(val label: String, val ytFilter: String?) {
    All("All", null),
    Songs("Songs", YouTube.FILTER_SONGS),
    Artists("Artists", YouTube.FILTER_ARTISTS),
    Albums("Albums", YouTube.FILTER_ALBUMS),
    Playlists("Playlists", YouTube.FILTER_PLAYLISTS),
    Videos("Videos", YouTube.FILTER_VIDEOS),
}

class SearchViewModel : ViewModel() {
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
    var activeQueryId by mutableStateOf<String?>(null)
        private set
    var searchRequestId by mutableStateOf<String?>(null)
        private set
    val searchSessionId: String = UUID.randomUUID().toString()

    private var search: YouTube.Search? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun onQueryChange(q: String) {
        query = q
        showingResults = false
        suggestJob?.cancel()
        if (q.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            suggestions = withContext(Dispatchers.IO) {
                runCatching { YouTube.suggestions(q) }.getOrDefault(emptyList())
            }
        }
    }

    fun submit(q: String = query, remember: Boolean = true) {
        if (q.isBlank()) return
        query = q
        showingResults = true
        searchRequestId = UUID.randomUUID().toString()
        if (remember) {
            val qId = UUID.randomUUID().toString()
            activeQueryId = qId
            VelvetApp.library.addSearch(q)
            VelvetApp.eventRecorderOrNull()?.recordSearchSubmitted(
                queryId = qId,
                rawQuery = q,
                searchSessionId = searchSessionId,
            )
        }
        suggestJob?.cancel()
        suggestions = emptyList()
        top = null
        items = emptyList()
        error = null
        search = null
        searchJob?.cancel()
        val f = filter
        searchJob = viewModelScope.launch {
            loading = true
            try {
                if (f.ytFilter == null) {
                    val results = YouTube.searchAll(q)
                    top = results
                    if (results.songs.isEmpty() && results.artists.isEmpty() && results.albums.isEmpty()) {
                        error = "Nothing found, or YouTube didn't answer. Check your connection."
                    }
                } else {
                    val s = withContext(Dispatchers.IO) { YouTube.Search(q, f.ytFilter) }
                    search = s
                    items = withContext(Dispatchers.IO) { s.loadNext() }.distinctBy(::itemKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            } finally {
                if (isActive) loading = false
            }
        }
    }

    fun loadMore() {
        val s = search ?: return
        if (loading || !s.hasMore) return
        searchJob = viewModelScope.launch {
            loading = true
            try {
                val more = withContext(Dispatchers.IO) { s.loadNext() }
                val seen = items.map(::itemKey).toSet()
                items = items + more.filter { itemKey(it) !in seen }.distinctBy(::itemKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                if (isActive) loading = false
            }
        }
    }

    fun selectFilter(f: SearchFilter) {
        if (f == filter) return
        filter = f
        if (showingResults) submit(remember = false)
    }

    fun clear() {
        onQueryChange("")
        top = null
        items = emptyList()
        activeQueryId = null
        searchRequestId = null
    }
}

fun itemKey(item: BrowseItem): String = when (item) {
    is BrowseItem.SongItem -> "s-" + item.song.id
    is BrowseItem.ArtistItem -> "a-" + item.artist.url
    is BrowseItem.AlbumItem -> "l-" + item.album.url
}

private val genres = listOf(
    "Bollywood", "Punjabi", "Lo-fi", "Pop", "Hip-Hop", "Chill", "Workout", "Romance",
    "Party", "Rock", "Indie", "K-Pop", "Focus", "Sad songs", "EDM", "Retro",
)

@Composable
fun SearchScreen(vm: SearchViewModel) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard = { keyboard?.hide(); focusManager.clearFocus() }
    val nav = LocalNav.current

    Column(Modifier.fillMaxSize()) {
        BrowseTitle("Search", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp))
        TextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("What do you want to listen to?") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = vm::clear) { Icon(Icons.Default.Clear, "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = velvetColors.raised,
                unfocusedContainerColor = velvetColors.surface,
                focusedTextColor = velvetColors.text,
                unfocusedTextColor = velvetColors.text,
                focusedPlaceholderColor = velvetColors.muted,
                unfocusedPlaceholderColor = velvetColors.muted,
                focusedLeadingIconColor = velvetColors.accentText,
                unfocusedLeadingIconColor = velvetColors.muted,
                focusedTrailingIconColor = velvetColors.text,
                unfocusedTrailingIconColor = velvetColors.text,
                cursorColor = velvetColors.accentText,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { dismissKeyboard(); vm.submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )

        when {
            !vm.showingResults && vm.suggestions.isNotEmpty() -> Suggestions(vm.suggestions) { s ->
                dismissKeyboard()
                vm.submit(s)
            }
            !vm.showingResults -> SearchHome(
                onSearch = { q -> dismissKeyboard(); vm.submit(q) },
                onGenre = { nav.open(Route.Genre(it)) },
            )
            else -> {
                FilterChips(vm.filter, vm::selectFilter)
                SearchResults(vm)
            }
        }
    }
}

@Composable
private fun FilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchFilter.entries.forEach { f -> BrowseChip(f.label, selected = selected == f) { onSelect(f) } }
    }
}

@Composable
private fun Suggestions(suggestions: List<String>, onPick: (String) -> Unit) {
    LazyColumn {
        items(suggestions) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(s, modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHome(onSearch: (String) -> Unit, onGenre: (String) -> Unit) {
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    HistoryDialog(confirm) { confirm = null }
    val library = VelvetApp.library
    val data by library.data.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BrowseSection("Browse genres") }
        item {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                genres.forEach { name -> BrowseChip(name) { onGenre(name) } }
            }
        }
        if (data.searches.isNotEmpty()) {
            item {
                BrowseSection("Recent searches", action = "Clear") {
                    confirm = ConfirmRequest("Clear search history?", "Your recent searches will be removed.", "Clear history") {
                        library.clearSearches()
                    }
                }
            }
            items(data.searches, key = { "q-$it" }) { q ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSearch(q) }
                        .padding(start = 20.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(q, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp))
                    IconButton(onClick = { library.removeSearch(q) }) {
                        Icon(Icons.Default.Close, "Remove $q", tint = velvetColors.muted, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(start = 56.dp, end = 20.dp), color = velvetColors.rule)
            }
        }
    }
}

@Composable
private fun SearchResults(vm: SearchViewModel) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, vm.filter, vm.query) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (vm.filter != SearchFilter.All && last >= vm.items.size - 5) vm.loadMore() }
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        val top = vm.top
        when {
            vm.error != null && top == null && vm.items.isEmpty() -> item { ErrorBox(vm.error ?: "") { vm.submit(remember = false) } }
            vm.filter == SearchFilter.All && top != null -> topResults(
                top = top,
                query = vm.query,
                onMore = vm::selectFilter,
                onSongClick = { vm.clear() },
                queryId = vm.activeQueryId,
                requestId = vm.searchRequestId,
                searchSessionId = vm.searchSessionId,
            )
            else -> {
                val qId = vm.activeQueryId
                val reqId = vm.searchRequestId
                val currentQuery = vm.query
                val sessionId = vm.searchSessionId
                itemsIndexed(vm.items, key = { _, item -> itemKey(item) }) { index, item ->
                    if (item is BrowseItem.SongItem && qId != null && reqId != null) {
                        LaunchedEffect(reqId, item.song.id, index) {
                            VelvetApp.eventRecorderOrNull()?.recordSearchResultImpression(
                                queryId = qId,
                                rawQuery = currentQuery,
                                requestId = reqId,
                                position = index,
                                song = item.song,
                                searchSessionId = sessionId,
                            )
                        }
                    }
                    BrowseItemRow(
                        item = item,
                        onSongClick = { vm.clear() },
                        attributionProvider = if (item is BrowseItem.SongItem && qId != null && reqId != null) {
                            {
                                VelvetApp.eventRecorderOrNull()?.recordSearchResultClicked(
                                    queryId = qId,
                                    rawQuery = currentQuery,
                                    requestId = reqId,
                                    position = index,
                                    song = item.song,
                                    searchSessionId = sessionId,
                                ) ?: PlaybackAttribution()
                            }
                        } else null,
                    )
                    HorizontalDivider(Modifier.padding(start = 88.dp, end = 20.dp), color = velvetColors.rule)
                }
                if (!vm.loading && vm.items.isEmpty() && vm.error == null) item { EmptyHint("No results") }
            }
        }
        if (vm.loading) item { LoadingBox() }
    }
}

@Composable
fun BrowseItemRow(
    item: BrowseItem,
    onSongClick: ((Song) -> Unit)? = null,
    attributionProvider: (() -> PlaybackAttribution)? = null,
) {
    val nav = LocalNav.current
    when (item) {
        is BrowseItem.SongItem -> SongRow(item.song, onClick = {
            val attr = attributionProvider?.invoke() ?: PlaybackAttribution()
            PlayerConnection.play(listOf(item.song), attribution = attr)
            onSongClick?.invoke(item.song)
        })
        is BrowseItem.ArtistItem -> BrowseRow(
            title = item.artist.name,
            subtitle = artistSubtitle(item.artist),
            thumb = item.artist.thumb,
            round = true,
            placeholder = Icons.Default.Person,
            showChevron = true,
        ) { nav.open(Route.Artist(item.artist)) }
        is BrowseItem.AlbumItem -> BrowseRow(
            title = item.album.title,
            subtitle = listOf(if (item.album.isAlbum) "Album" else "Playlist", item.album.subtitle)
                .filter { it.isNotBlank() }.joinToString(" • "),
            thumb = item.album.thumb,
            showChevron = true,
        ) { nav.open(Route.Album(item.album)) }
    }
}

fun LazyListScope.topResults(
    top: YouTube.TopResults,
    query: String,
    onMore: ((SearchFilter) -> Unit)?,
    onSongClick: ((Song) -> Unit)? = null,
    queryId: String? = null,
    requestId: String? = null,
    searchSessionId: String? = null,
) {
    val seeAll = if (onMore != null) "See all" else null
    val topArtist = top.artists.firstOrNull()?.takeIf { it.name.equals(query.trim(), ignoreCase = true) }
    val topSong = top.songs.firstOrNull()

    if (topArtist != null || topSong != null) {
        item { BrowseSection("Top result") }
        item {
            val displayedSong = if (topArtist == null) topSong else null
            if (displayedSong != null && queryId != null && requestId != null) {
                LaunchedEffect(requestId, displayedSong.id, 0) {
                    VelvetApp.eventRecorderOrNull()?.recordSearchResultImpression(
                        queryId = queryId,
                        rawQuery = query,
                        requestId = requestId,
                        position = 0,
                        song = displayedSong,
                        searchSessionId = searchSessionId ?: requestId,
                    )
                }
            }
            TopResultCard(
                artist = topArtist,
                song = displayedSong,
                onSongClick = onSongClick,
                attributionProvider = if (displayedSong != null && queryId != null && requestId != null) {
                    {
                        VelvetApp.eventRecorderOrNull()?.recordSearchResultClicked(
                            queryId = queryId,
                            rawQuery = query,
                            requestId = requestId,
                            position = 0,
                            song = displayedSong,
                            searchSessionId = searchSessionId ?: requestId,
                        ) ?: PlaybackAttribution()
                    }
                } else null,
            )
        }
    }
    if (top.songs.isNotEmpty()) {
        item { BrowseSection("Songs", action = seeAll) { onMore?.invoke(SearchFilter.Songs) } }
        itemsIndexed(top.songs.take(5), key = { _, song -> "ts-" + song.id }) { index, song ->
            if (queryId != null && requestId != null) {
                LaunchedEffect(requestId, song.id, index) {
                    VelvetApp.eventRecorderOrNull()?.recordSearchResultImpression(
                        queryId = queryId,
                        rawQuery = query,
                        requestId = requestId,
                        position = index,
                        song = song,
                        searchSessionId = searchSessionId ?: requestId,
                    )
                }
            }
            SongRow(song, onClick = {
                val attr = if (queryId != null && requestId != null) {
                    VelvetApp.eventRecorderOrNull()?.recordSearchResultClicked(
                        queryId = queryId,
                        rawQuery = query,
                        requestId = requestId,
                        position = index,
                        song = song,
                        searchSessionId = searchSessionId ?: requestId,
                    ) ?: PlaybackAttribution()
                } else {
                    PlaybackAttribution()
                }
                PlayerConnection.play(listOf(song), attribution = attr)
                onSongClick?.invoke(song)
            })
            HorizontalDivider(Modifier.padding(start = 88.dp, end = 20.dp), color = velvetColors.rule)
        }
    }
    if (top.artists.isNotEmpty()) {
        item { BrowseSection("Artists", action = seeAll) { onMore?.invoke(SearchFilter.Artists) } }
        item {
            val nav = LocalNav.current
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(top.artists.take(10), key = { it.url }) { a -> ArtistCircle(a, 120.dp) { nav.open(Route.Artist(a)) } }
            }
        }
    }
    if (top.albums.isNotEmpty()) {
        item { BrowseSection("Albums", action = seeAll) { onMore?.invoke(SearchFilter.Albums) } }
        item {
            val nav = LocalNav.current
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(top.albums.take(10), key = { it.url }) { a -> AlbumCard(a, 140.dp) { nav.open(Route.Album(a)) } }
            }
        }
    }
    if (top.playlists.isNotEmpty()) {
        item { BrowseSection("Playlists", action = seeAll) { onMore?.invoke(SearchFilter.Playlists) } }
        item {
            val nav = LocalNav.current
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(top.playlists.take(10), key = { it.url }) { a -> AlbumCard(a, 140.dp) { nav.open(Route.Album(a)) } }
            }
        }
    }
}

@Composable
private fun TopResultCard(
    artist: ArtistRef?,
    song: Song?,
    onSongClick: ((Song) -> Unit)? = null,
    attributionProvider: (() -> PlaybackAttribution)? = null,
) {
    val nav = LocalNav.current
    val title = artist?.name ?: song?.title ?: return
    val subtitle = if (artist != null) "Artist" else "Song • ${song?.artist}"
    val thumb = artist?.thumb ?: song?.thumb
    val glow = rememberArtColor(thumb)
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .panel(RoundedCornerShape(12.dp), fill = velvetColors.surface)
            .background(Brush.horizontalGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent)))
            .clickable {
                if (artist != null) {
                    nav.open(Route.Artist(artist))
                } else song?.let { s ->
                    val attr = attributionProvider?.invoke() ?: PlaybackAttribution()
                    PlayerConnection.play(listOf(s), attribution = attr)
                    onSongClick?.invoke(s)
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            thumb, 76.dp,
            corner = if (artist != null) 38.dp else 10.dp,
            placeholder = if (artist != null) Icons.Default.Person else AppIcons.MusicNote,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (artist == null) AppIcons.Play else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = velvetColors.accentText)
    }
}

@Composable
fun GenreScreen(name: String, onBack: () -> Unit) {
    val vm: SearchViewModel = viewModel(key = "genre-$name")
    LaunchedEffect(name) { if (!vm.showingResults) vm.submit("$name songs", remember = false) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeading(name, onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            val top = vm.top
            if (top != null) topResults(top, "", onMore = null) else if (vm.loading) item { LoadingBox() }
            vm.error?.let { e -> if (top == null) item { ErrorBox(e) { vm.submit("$name songs", remember = false) } } }
        }
    }
}
