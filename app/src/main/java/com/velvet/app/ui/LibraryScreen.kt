package com.velvet.app.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.velvet.app.VelvetApp
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.CoverMode
import com.velvet.app.data.Playlist
import com.velvet.app.data.PlaylistSorter
import com.velvet.app.data.SortDirection

enum class LibraryFilter(val label: String) {
    PLAYLISTS("Playlists"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val library = VelvetApp.library
    val nav = LocalNav.current
    val context = LocalContext.current
    val data by library.data.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var sortKey by rememberSaveable { mutableStateOf(PlaylistSorter.LibrarySortKey.RECENTLY_ADDED) }
    var sortDir by rememberSaveable { mutableStateOf(SortDirection.DESC) }
    var isGridView by rememberSaveable { mutableStateOf(false) }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showPlusMenu by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    var editingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var playlistToManage by remember { mutableStateOf<Playlist?>(null) }
    var confirmDeletePlaylist by remember { mutableStateOf<Playlist?>(null) }

    if (confirmDeletePlaylist != null) {
        val target = confirmDeletePlaylist!!
        ConfirmDialog(
            ConfirmRequest(
                title = "Delete \"${target.name}\"?",
                message = "This playlist and its ${target.songs.size} songs will be removed from your library. This can't be undone.",
                confirmLabel = "Delete",
                action = {
                    library.deletePlaylist(target.id)
                    confirmDeletePlaylist = null
                },
            ),
        ) {
            confirmDeletePlaylist = null
        }
    }

    // Prepare lists
    val filteredPlaylists = remember(data.playlists, searchQuery) {
        data.playlists.filter {
            searchQuery.isBlank() || it.name.contains(searchQuery.trim(), ignoreCase = true)
        }
    }
    val filteredAlbums = remember(data.albums, searchQuery) {
        data.albums.filter {
            searchQuery.isBlank() || it.title.contains(searchQuery.trim(), ignoreCase = true) || it.subtitle.contains(searchQuery.trim(), ignoreCase = true)
        }
    }
    val filteredArtists = remember(data.artists, searchQuery) {
        data.artists.filter {
            searchQuery.isBlank() || it.name.contains(searchQuery.trim(), ignoreCase = true)
        }
    }

    // Sort items using PlaylistSorter.sortLibrary
    val sortedPlaylists = remember(filteredPlaylists, sortKey, sortDir) {
        PlaylistSorter.sortLibrary(
            items = filteredPlaylists,
            key = sortKey,
            direction = sortDir,
            titleSelector = { it.name },
            addedAtSelector = { it.addedAt },
            lastPlayedAtSelector = { it.lastPlayedAt },
        )
    }
    val sortedAlbums = remember(filteredAlbums, sortKey, sortDir) {
        PlaylistSorter.sortLibrary(
            items = filteredAlbums,
            key = sortKey,
            direction = sortDir,
            titleSelector = { it.title },
            addedAtSelector = { it.addedAt },
        )
    }
    val sortedArtists = remember(filteredArtists, sortKey, sortDir) {
        PlaylistSorter.sortLibrary(
            items = filteredArtists,
            key = sortKey,
            direction = sortDir,
            titleSelector = { it.name },
            addedAtSelector = { it.addedAt },
        )
    }

    val isLightAccent = (0.299f * velvetColors.accentText.red + 0.587f * velvetColors.accentText.green + 0.114f * velvetColors.accentText.blue) > 0.55f
    val likedTextColor = if (isLightAccent) Color(0xFF111111) else Color.White
    val likedMutedColor = if (isLightAccent) Color(0xFF333333) else Color.White.copy(alpha = 0.82f)

    Column(Modifier.fillMaxSize()) {
        // 1. Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowseTitle("Library", Modifier.weight(1f))

            IconButton(
                onClick = {
                    isSearching = !isSearching
                    if (!isSearching) searchQuery = ""
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (isSearching) Icons.Default.Close else AppIcons.Search,
                    contentDescription = if (isSearching) "Close search" else "Search in library",
                    tint = velvetColors.text,
                    modifier = Modifier.size(22.dp),
                )
            }

            Box {
                IconButton(
                    onClick = { showPlusMenu = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Create or import",
                        tint = velvetColors.text,
                        modifier = Modifier.size(24.dp),
                    )
                }

                DropdownMenu(
                    expanded = showPlusMenu,
                    onDismissRequest = { showPlusMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Create playlist") },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = {
                            showPlusMenu = false
                            showCreateDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Import playlist") },
                        leadingIcon = { Icon(AppIcons.Download, null) },
                        onClick = {
                            showPlusMenu = false
                            nav.open(Route.Import)
                        },
                    )
                }
            }
        }

        // Inline in-library search field if active
        if (isSearching) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search your library...", color = velvetColors.muted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = velvetColors.accentText,
                        unfocusedBorderColor = velvetColors.rule,
                        focusedTextColor = velvetColors.text,
                        unfocusedTextColor = velvetColors.text,
                    ),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            // 2. TWO ROUNDED SQUARE TILES SIDE BY SIDE
            // Exactly (screenWidth - 16dp side padding*2 - 12dp gap) / 2 wide with aspectRatio 1:1, corner radius 20 dp
            item(key = "library-hero-tiles") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // LIKED SONGS TILE
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .shadow(8.dp, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        velvetColors.accentText,
                                        velvetColors.accentText.copy(alpha = 0.82f),
                                    ),
                                ),
                            )
                            .clickable { nav.open(Route.Liked) }
                            .padding(16.dp)
                            .semantics { contentDescription = "Liked Songs, ${songCountLabel(data.liked.size)}" },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.20f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = likedTextColor,
                                    modifier = Modifier.size(24.dp),
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "Liked Songs",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = likedTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    songCountLabel(data.liked.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = likedMutedColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // DOWNLOADS TILE
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(velvetColors.raised)
                            .border(1.dp, velvetColors.rule, RoundedCornerShape(20.dp))
                            .clickable { nav.open(Route.Downloads) }
                            .padding(16.dp)
                            .semantics { contentDescription = "Downloads, ${songCountLabel(data.downloads.size)}" },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(velvetColors.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    AppIcons.Download,
                                    contentDescription = null,
                                    tint = velvetColors.accentText,
                                    modifier = Modifier.size(24.dp),
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "Downloads",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = velvetColors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    songCountLabel(data.downloads.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = velvetColors.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 3. Filter Chips Row: Playlists, Artists, Albums (single-select, remembered)
            item(key = "library-filter-chips") {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrowseChip("All", selected = filter == null) { filter = null }
                    LibraryFilter.entries.forEach { f ->
                        BrowseChip(f.label, selected = filter == f) {
                            filter = if (filter == f) null else f
                        }
                    }
                }
            }

            // 4. Sort + View Row
            item(key = "library-sort-view-row") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { showSortSheet = true },
                        shape = RoundedCornerShape(20.dp),
                        color = velvetColors.raised,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Sort, contentDescription = "Sort library", tint = velvetColors.accentText, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(sortKey.label, style = MaterialTheme.typography.labelMedium, color = velvetColors.text)
                        }
                    }

                    IconButton(
                        onClick = { isGridView = !isGridView },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (isGridView) AppIcons.QueueMusic else AppIcons.GridView,
                            contentDescription = if (isGridView) "Switch to list view" else "Switch to grid view",
                            tint = velvetColors.text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 5. The List / Grid Content
            val showPlaylists = filter == null || filter == LibraryFilter.PLAYLISTS
            val showAlbums = filter == null || filter == LibraryFilter.ALBUMS
            val showArtists = filter == null || filter == LibraryFilter.ARTISTS

            val isEmpty = (showPlaylists && sortedPlaylists.isEmpty()) &&
                (!showAlbums || sortedAlbums.isEmpty()) &&
                (!showArtists || sortedArtists.isEmpty())

            if (isEmpty) {
                item(key = "library-empty-state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            when (filter) {
                                LibraryFilter.PLAYLISTS -> "No playlists yet"
                                LibraryFilter.ALBUMS -> "No saved albums yet"
                                LibraryFilter.ARTISTS -> "No followed artists yet"
                                else -> "Your library is quiet"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = velvetColors.text,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (filter) {
                                LibraryFilter.PLAYLISTS -> "Create your first playlist or import from Spotify/YouTube Music."
                                LibraryFilter.ALBUMS -> "Explore albums and tap ♡ to save them here."
                                LibraryFilter.ARTISTS -> "Search your favorite artists and follow them."
                                else -> "Bring in playlists from Spotify or YouTube Music, or create your first playlist."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = velvetColors.muted,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { showCreateDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = velvetColors.accentText),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text("Create playlist", color = Color.White)
                            }
                            Button(
                                onClick = { nav.open(Route.Import) },
                                colors = ButtonDefaults.buttonColors(containerColor = velvetColors.raised),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text("Import", color = velvetColors.text)
                            }
                        }
                    }
                }
            } else if (isGridView) {
                // Grid layout
                item(key = "library-grid-content") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (showPlaylists && sortedPlaylists.isNotEmpty()) {
                            val chunkedPlaylists = sortedPlaylists.chunked(2)
                            chunkedPlaylists.forEach { rowPairs ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    rowPairs.forEach { p ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .combinedClickable(
                                                    onClick = { nav.open(Route.LocalPlaylist(p.id)) },
                                                    onLongClick = { playlistToManage = p },
                                                ),
                                        ) {
                                            Column {
                                                PlaylistCover(
                                                    songs = p.songs,
                                                    size = 160.dp,
                                                    corner = 14.dp,
                                                    customCover = p.customCover,
                                                    coverMode = p.coverMode,
                                                    coverRef = p.coverRef,
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("Playlist • ${songCountLabel(p.songs.size)}", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1)
                                            }
                                        }
                                    }
                                    if (rowPairs.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        if (showAlbums && sortedAlbums.isNotEmpty()) {
                            val chunkedAlbums = sortedAlbums.chunked(2)
                            chunkedAlbums.forEach { rowPairs ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    rowPairs.forEach { a ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { nav.open(Route.Album(a)) },
                                        ) {
                                            Column {
                                                Artwork(a.thumb, 160.dp, corner = 14.dp, placeholder = AppIcons.Album)
                                                Spacer(Modifier.height(6.dp))
                                                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(if (a.isAlbum) "Album" else "Playlist", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1)
                                            }
                                        }
                                    }
                                    if (rowPairs.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        if (showArtists && sortedArtists.isNotEmpty()) {
                            val chunkedArtists = sortedArtists.chunked(2)
                            chunkedArtists.forEach { rowPairs ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    rowPairs.forEach { r ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { nav.open(Route.Artist(r)) },
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Artwork(r.thumb, 140.dp, corner = 70.dp, placeholder = Icons.Default.Person)
                                                Spacer(Modifier.height(6.dp))
                                                Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("Artist", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1)
                                            }
                                        }
                                    }
                                    if (rowPairs.size == 1) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // List layout
                if (showPlaylists) {
                    items(sortedPlaylists, key = { "pl-${it.id}" }) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { nav.open(Route.LocalPlaylist(p.id)) },
                                    onLongClick = { playlistToManage = p },
                                )
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlaylistCover(
                                songs = p.songs,
                                size = 56.dp,
                                corner = 12.dp,
                                customCover = p.customCover,
                                coverMode = p.coverMode,
                                coverRef = p.coverRef,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Playlist • ${songCountLabel(p.songs.size)}", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 90.dp, end = 20.dp), color = velvetColors.rule.copy(alpha = 0.5f))
                    }
                }

                if (showAlbums) {
                    items(sortedAlbums, key = { "al-${it.url}" }) { a ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.open(Route.Album(a)) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(a.thumb, 56.dp, corner = 12.dp, placeholder = AppIcons.Album)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${if (a.isAlbum) "Album" else "Playlist"} • ${a.subtitle}", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 90.dp, end = 20.dp), color = velvetColors.rule.copy(alpha = 0.5f))
                    }
                }

                if (showArtists) {
                    items(sortedArtists, key = { "ar-${it.url}" }) { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.open(Route.Artist(r)) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(r.thumb, 56.dp, corner = 28.dp, placeholder = Icons.Default.Person)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                            ) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Artist", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 90.dp, end = 20.dp), color = velvetColors.rule.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    // 6. Long-press Action Sheet on a Playlist: Edit, Duplicate, Delete
    if (playlistToManage != null) {
        val target = playlistToManage!!
        ModalBottomSheet(
            onDismissRequest = { playlistToManage = null },
            containerColor = velvetColors.surface,
            tonalElevation = 0.dp,
            scrimColor = Color(0x99000000),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(target.name, style = MaterialTheme.typography.titleLarge, color = velvetColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Playlist • ${songCountLabel(target.songs.size)}", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted)
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingPlaylist = target
                            playlistToManage = null
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Pencil, null, tint = velvetColors.text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Edit playlist", style = MaterialTheme.typography.titleMedium, color = velvetColors.text)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newId = library.duplicatePlaylist(target.id)
                            playlistToManage = null
                            if (newId != null) {
                                Toast.makeText(context, "Playlist duplicated", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Share, null, tint = velvetColors.text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Duplicate playlist", style = MaterialTheme.typography.titleMedium, color = velvetColors.text)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            confirmDeletePlaylist = target
                            playlistToManage = null
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Delete, null, tint = velvetColors.danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Delete playlist", style = MaterialTheme.typography.titleMedium, color = velvetColors.danger)
                }
            }
        }
    }

    // Edit sheet opened from long-press
    if (editingPlaylist != null) {
        val target = editingPlaylist!!
        EditPlaylistSheet(
            playlist = target,
            existingPlaylists = data.playlists,
            onDismiss = { editingPlaylist = null },
            onSave = { newName, newDesc, newCoverMode, newCoverRef ->
                library.updatePlaylistDetails(target.id, newName, newDesc)
                library.setPlaylistCover(target.id, newCoverMode, newCoverRef)
                editingPlaylist = null
                Toast.makeText(context, "Playlist updated", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // Sort sheet for Library
    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            containerColor = velvetColors.surface,
            tonalElevation = 0.dp,
            scrimColor = Color(0x99000000),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text("Sort Library", style = MaterialTheme.typography.titleLarge, color = velvetColors.text)
                Spacer(Modifier.height(16.dp))

                PlaylistSorter.LibrarySortKey.entries.forEach { key ->
                    val isSelected = sortKey == key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) {
                                    sortDir = if (sortDir == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                                } else {
                                    sortKey = key
                                    sortDir = if (key == PlaylistSorter.LibrarySortKey.ALPHABETICAL) SortDirection.ASC else SortDirection.DESC
                                }
                                showSortSheet = false
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            key.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) velvetColors.accentText else velvetColors.text,
                        )
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = velvetColors.accentText)
                        }
                    }
                    HorizontalDivider(color = velvetColors.rule.copy(alpha = 0.5f))
                }
            }
        }
    }

    if (showCreateDialog) {
        NamePlaylistDialog("Give your playlist a name", onDismiss = { showCreateDialog = false }) { name ->
            val newId = library.createPlaylist(name)
            nav.open(Route.LocalPlaylist(newId))
        }
    }
}
