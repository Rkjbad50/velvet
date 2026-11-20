package com.tunehub.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunehub.app.TuneHubApp
import com.tunehub.app.data.AlbumRef
import com.tunehub.app.data.ArtistRef
import com.tunehub.app.data.Song
import com.tunehub.app.data.YouTube
import com.tunehub.app.data.totalDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun songCountLabel(count: Int): String = if (count == 1) "1 song" else "$count songs"

private fun songMeta(songs: List<Song>): String =
    listOf(songCountLabel(songs.size), totalDuration(songs)).filter { it.isNotBlank() }.joinToString(" • ")

private fun shuffle(songs: List<Song>, playlistId: String? = null) {
    if (songs.isNotEmpty()) PlayerConnection.play(songs, songs.indices.random(), shuffled = true, playlistId = playlistId)
}

// ------------------------------------------------------------------ YouTube albums & playlists

class AlbumViewModel : ViewModel() {
    var page by mutableStateOf<YouTube.AlbumPage?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loaded: String? = null

    fun load(album: AlbumRef, force: Boolean = false) {
        if (!force && loaded == album.url) return
        loaded = album.url
        error = null
        viewModelScope.launch {
            try {
                page = YouTube.albumPage(album.url, album)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load"
            }
        }
    }
}

@Composable
fun AlbumScreen(album: AlbumRef, onBack: () -> Unit) {
    val vm: AlbumViewModel = viewModel(key = "album-" + album.url)
    LaunchedEffect(album.url) { vm.load(album) }
    val library = TuneHubApp.library
    val data by library.data.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val info = vm.page?.album ?: album
    val songs = vm.page?.songs.orEmpty()
    val saved = data.albums.any { it.url == album.url }
    val tint = rememberArtColor(info.thumb)
    // album pages list tracks by number; their thumbnails are all the same cover
    val isAlbum = info.isAlbum

    BackPage(onBack) {
        item {
            CollectionHeader(
                title = info.title,
                subtitle = info.subtitle,
                meta = listOf(if (isAlbum) "Album" else "Playlist", songMeta(songs)).filter { it.isNotBlank() }.joinToString(" • "),
                tint = tint,
                art = { Artwork(info.thumb, 230.dp, corner = 34.dp, placeholder = AppIcons.Album) },
                playEnabled = songs.isNotEmpty(),
                onPlay = { PlayerConnection.play(songs) },
                onShuffle = { shuffle(songs) },
                onSubtitleClick = songs.firstOrNull()?.artistUrl?.takeIf { isAlbum }?.let { url ->
                    { nav.open(Route.Artist(ArtistRef(url, info.subtitle))) }
                },
                actions = {
                    PanelIconButton(
                        if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (saved) "Remove from library" else "Save to library",
                        size = 48.dp,
                        tint = if (saved) tuneColors.danger else tuneColors.text,
                    ) { library.toggleAlbum(info) }
                    DownloadAllButton(songs)
                },
            )
        }
        when {
            vm.page == null && vm.error != null -> item { ErrorBox(vm.error ?: "") { vm.load(album, force = true) } }
            vm.page == null -> item { LoadingBox() }
            songs.isEmpty() -> item { EmptyHint("This is empty.") }
            else -> itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    song,
                    onClick = { PlayerConnection.play(songs, i) },
                    trackNumber = if (isAlbum) i + 1 else null,
                    showArt = !isAlbum,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ local collections

@Composable
fun LikedScreen(onBack: () -> Unit) {
    val data by TuneHubApp.library.data.collectAsStateWithLifecycle()
    val songs = data.liked
    BackPage(onBack) {
        item {
            CollectionHeader(
                title = "Liked Songs",
                subtitle = "",
                meta = songMeta(songs),
                tint = tuneColors.danger,
                art = { LikedCover(230.dp, corner = 34.dp) },
                playEnabled = songs.isNotEmpty(),
                onPlay = { PlayerConnection.play(songs) },
                onShuffle = { shuffle(songs) },
                actions = { DownloadAllButton(songs) },
            )
        }
        if (songs.isEmpty()) item { EmptyHint("Songs you like will appear here. Tap ♡ on any song.") }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, onClick = { PlayerConnection.play(songs, i) })
        }
    }
}

@Composable
fun RecentScreen(onBack: () -> Unit) {
    val data by TuneHubApp.library.data.collectAsStateWithLifecycle()
    val songs = data.recent.take(50)
    BackPage(onBack, heading = { ScreenHeading("HISTORY", "Recently played") }) {
        if (songs.isEmpty()) item { EmptyHint("Your listening history will appear here.") }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, onClick = { PlayerConnection.play(songs, index) })
        }
    }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val data by TuneHubApp.library.data.collectAsStateWithLifecycle()
    val progress by TuneHubApp.downloads.progress.collectAsStateWithLifecycle()
    val songs = data.downloads
    BackPage(onBack) {
        item {
            CollectionHeader(
                title = "Downloads",
                subtitle = "",
                meta = songMeta(songs) + if (progress.isNotEmpty()) " • ${progress.size} downloading" else "",
                tint = tuneColors.accentText,
                art = { DownloadsCover(230.dp, corner = 34.dp) },
                playEnabled = songs.isNotEmpty(),
                onPlay = { PlayerConnection.play(songs) },
                onShuffle = { shuffle(songs) },
            )
        }
        if (progress.isNotEmpty()) {
            item {
                LinearProgressIndicator(
                    progress = { progress.values.average().toFloat() },
                    modifier = Modifier
                        .fillParentMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (songs.isEmpty() && progress.isEmpty()) {
            item { EmptyHint("Download songs, albums or playlists to play them without internet.") }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, onClick = { PlayerConnection.play(songs, i) })
        }
    }
}

@Composable
fun LocalPlaylistScreen(id: String, onBack: () -> Unit) {
    val library = TuneHubApp.library
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by library.data.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmDialog(confirm) { confirm = null }
    val playlist = data.playlists.find { it.id == id }
    if (playlist == null) {
        // deleted while open
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var isEditing by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var draftSongs by remember(playlist.id, isEditing) { mutableStateOf(playlist.songs) }
    var recentlyRemoved by remember { mutableStateOf<Pair<Int, Song>?>(null) }
    var showUndoToast by remember { mutableStateOf(false) }

    val activeSongs = if (isEditing) draftSongs else playlist.songs

    Box(Modifier.fillMaxSize()) {
        BackPage(onBack) {
            item {
                CollectionHeader(
                    title = playlist.name,
                    subtitle = if (isEditing) "Editing playlist (${draftSongs.size} tracks)" else "",
                    meta = "Playlist • " + songMeta(activeSongs),
                    tint = rememberArtColor(activeSongs.firstOrNull()?.thumb),
                    art = { PlaylistCover(activeSongs, if (activeSongs.size <= 1) 156.dp else 230.dp, corner = 28.dp) },
                    artworkBackdropSize = if (activeSongs.size <= 1) 156.dp else 300.dp,
                    playEnabled = activeSongs.isNotEmpty() && !isEditing,
                    onPlay = { PlayerConnection.play(activeSongs, playlistId = id) },
                    onShuffle = { shuffle(activeSongs, playlistId = id) },
                    topActions = {
                        if (!isEditing) {
                            PanelIconButton(Icons.Default.Edit, "Edit tracks") {
                                draftSongs = playlist.songs
                                isEditing = true
                            }
                            PanelIconButton(AppIcons.Sort, "Sort") { showSortMenu = true }
                            PanelIconButton(Icons.Default.Delete, "Delete playlist") {
                                confirm = ConfirmRequest(
                                    "Delete \"${playlist.name}\"?",
                                    "This playlist and its ${playlist.songs.size} songs will be removed from your library. This can't be undone.",
                                    "Delete",
                                ) { library.deletePlaylist(playlist.id) }
                            }
                        } else {
                            PanelIconButton(AppIcons.Sort, "Sort") { showSortMenu = true }
                            PanelIconButton(Icons.Default.Check, "Save") {
                                library.setPlaylistSongs(playlist.id, draftSongs)
                                isEditing = false
                                Toast.makeText(context, "Playlist updated & saved", Toast.LENGTH_SHORT).show()
                            }
                            PanelIconButton(Icons.Default.Close, "Cancel") {
                                draftSongs = playlist.songs
                                isEditing = false
                            }
                        }
                    },
                    actions = {
                        if (isEditing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        library.setPlaylistSongs(playlist.id, draftSongs)
                                        isEditing = false
                                        Toast.makeText(context, "Playlist updated & saved", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = tuneColors.accentText),
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Icon(Icons.Default.Check, "Save", tint = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save changes", color = Color.White)
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    draftSongs = playlist.songs
                                    isEditing = false
                                }) {
                                    Text("Cancel", color = tuneColors.muted)
                                }
                            }
                        } else {
                            DownloadAllButton(activeSongs)
                        }
                    },
                )
            }

            if (showSortMenu) {
                item {
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Alphabetical (A - Z)") },
                            onClick = {
                                draftSongs = draftSongs.sortedBy { it.title.lowercase() }
                                isEditing = true
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Alphabetical (Z - A)") },
                            onClick = {
                                draftSongs = draftSongs.sortedByDescending { it.title.lowercase() }
                                isEditing = true
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Artist (A - Z)") },
                            onClick = {
                                draftSongs = draftSongs.sortedBy { it.artist.lowercase() }
                                isEditing = true
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Artist (Z - A)") },
                            onClick = {
                                draftSongs = draftSongs.sortedByDescending { it.artist.lowercase() }
                                isEditing = true
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Duration (Shortest first)") },
                            onClick = {
                                draftSongs = draftSongs.sortedBy { it.durationSec }
                                isEditing = true
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Duration (Longest first)") },
                            onClick = {
                                draftSongs = draftSongs.sortedByDescending { it.durationSec }
                                isEditing = true
                                showSortMenu = false
                            },
                        )
                    }
                }
            }

            if (activeSongs.isEmpty()) {
                item { EmptyHint("No songs in this playlist.") }
            }
            itemsIndexed(activeSongs, key = { _, s -> s.id }) { i, song ->
                if (isEditing) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(song.thumb, 50.dp, corner = 12.dp)
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 14.dp),
                        ) {
                            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = tuneColors.muted, maxLines = 1)
                        }
                        IconButton(onClick = {
                            val idx = draftSongs.indexOf(song)
                            recentlyRemoved = idx to song
                            draftSongs = draftSongs.filterNot { it.id == song.id }
                            showUndoToast = true
                            scope.launch {
                                delay(2000)
                                showUndoToast = false
                                recentlyRemoved = null
                            }
                        }) {
                            Icon(Icons.Default.Close, "Remove track", tint = tuneColors.danger)
                        }
                    }
                } else {
                    SongRow(song, onClick = { PlayerConnection.play(activeSongs, i, playlistId = id) }, playlistId = id)
                }
            }
        }

        // 2-second Undo snackbar prompt
        if (showUndoToast && recentlyRemoved != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) {
                GlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    color = tuneColors.surface,
                    borderColor = tuneColors.glassBorder,
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Track removed", color = tuneColors.text, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = {
                            val removed = recentlyRemoved
                            if (removed != null) {
                                val list = draftSongs.toMutableList()
                                list.add(removed.first.coerceAtMost(list.size), removed.second)
                                draftSongs = list
                                showUndoToast = false
                                recentlyRemoved = null
                            }
                        }) {
                            Text("UNDO (2s)", color = tuneColors.accentText, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        NamePlaylistDialog("Rename playlist", initial = playlist.name, onDismiss = { renaming = false }) {
            library.renamePlaylist(playlist.id, it)
        }
    }
}
