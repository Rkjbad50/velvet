package com.velvet.app.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Person
import com.velvet.app.data.ContextRecentsManager
import com.velvet.app.data.PlaybackContext
import com.velvet.app.data.RecentItem
import com.velvet.app.data.RecentType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.velvet.app.VelvetApp
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.Playlist
import com.velvet.app.data.Song
import com.velvet.app.data.YouTube
import com.velvet.app.data.totalDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

internal fun songCountLabel(count: Int): String = if (count == 1) "1 song" else "$count songs"

private fun songMeta(songs: List<Song>): String =
    listOf(songCountLabel(songs.size), totalDuration(songs)).filter { it.isNotBlank() }.joinToString(" • ")

private fun shuffle(songs: List<Song>, playlistId: String? = null, context: PlaybackContext? = null) {
    if (songs.isNotEmpty()) PlayerConnection.play(songs, songs.indices.random(), shuffled = true, playlistId = playlistId, context = context)
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
    val library = VelvetApp.library
    val data by library.data.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val info = vm.page?.album ?: album
    val songs = vm.page?.songs.orEmpty()
    val saved = data.albums.any { it.url == album.url }
    val tint = rememberArtColor(info.thumb)
    // album pages list tracks by number; their thumbnails are all the same cover
    val isAlbum = info.isAlbum
    val playbackContext = PlaybackContext(
        id = album.url,
        type = if (isAlbum) RecentType.ALBUM else RecentType.REMOTE_PLAYLIST,
        title = info.title,
        subtitle = if (isAlbum) info.subtitle else "Playlist",
        artworkUrl = info.thumb,
    )

    BackPage(onBack) {
        item {
            CollectionHeader(
                title = info.title,
                subtitle = info.subtitle,
                meta = listOf(if (isAlbum) "Album" else "Playlist", songMeta(songs)).filter { it.isNotBlank() }.joinToString(" • "),
                tint = tint,
                art = { Artwork(info.thumb, 230.dp, corner = 34.dp, placeholder = AppIcons.Album) },
                playEnabled = songs.isNotEmpty(),
                onPlay = { PlayerConnection.play(songs, context = playbackContext) },
                onShuffle = { shuffle(songs, context = playbackContext) },
                onSubtitleClick = songs.firstOrNull()?.artistUrl?.takeIf { isAlbum }?.let { url ->
                    { nav.open(Route.Artist(ArtistRef(url, info.subtitle))) }
                },
                actions = {
                    PanelIconButton(
                        if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (saved) "Remove from library" else "Save to library",
                        size = 48.dp,
                        tint = if (saved) velvetColors.danger else velvetColors.text,
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
                    onClick = { PlayerConnection.play(songs, i, context = playbackContext) },
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
    val data by VelvetApp.library.data.collectAsStateWithLifecycle()
    val songs = data.liked
    BackPage(onBack) {
        item {
            CollectionHeader(
                title = "Liked Songs",
                subtitle = "",
                meta = songMeta(songs),
                tint = velvetColors.danger,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentScreen(onBack: () -> Unit) {
    val library = VelvetApp.library
    val data by library.data.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmDialog(confirm) { confirm = null }

    val recents = remember(data.contextRecents, data.playlists) {
        ContextRecentsManager.filterDeletedPlaylists(data.contextRecents, data.playlists)
    }

    BackPage(onBack, heading = { ScreenHeading("HISTORY", "Recently played") }) {
        if (recents.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            confirm = ConfirmRequest(
                                "Clear recents?",
                                "Your recently played containers will be cleared.",
                                "Clear",
                            ) { library.clearContextRecents() }
                        },
                    ) {
                        Text("Clear recents", color = velvetColors.muted)
                    }
                }
            }
        }
        if (recents.isEmpty()) {
            item { EmptyHint("Your recently played albums, artists and playlists will appear here.") }
        }
        itemsIndexed(recents, key = { _, item -> "${item.type}:${item.id}" }) { _, item ->
            RecentItemRow(
                item = item,
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
                onRemove = {
                    library.removeContextRecent(item.id, item.type)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentItemRow(
    item: RecentItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val isArtist = item.type == RecentType.ARTIST
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onRemove,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isArtist) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(velvetColors.accent)
                    .padding(2.dp),
            ) {
                Artwork(item.artworkUrl, 48.dp, corner = 24.dp, placeholder = Icons.Default.Person)
            }
        } else {
            Artwork(item.artworkUrl, 52.dp, corner = 14.dp, placeholder = AppIcons.Album)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle.ifEmpty {
                    when (item.type) {
                        RecentType.LOCAL_PLAYLIST, RecentType.REMOTE_PLAYLIST -> "Playlist"
                        RecentType.ALBUM -> "Album"
                        RecentType.ARTIST -> "Artist"
                        RecentType.GENRE_MIX -> "Mix"
                        RecentType.UNKNOWN -> ""
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = velvetColors.muted,
                maxLines = 1,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove from recents", tint = velvetColors.muted)
        }
    }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val data by VelvetApp.library.data.collectAsStateWithLifecycle()
    val progress by VelvetApp.downloads.progress.collectAsStateWithLifecycle()
    val songs = data.downloads
    BackPage(onBack) {
        item {
            CollectionHeader(
                title = "Downloads",
                subtitle = "",
                meta = songMeta(songs) + if (progress.isNotEmpty()) " • ${progress.size} downloading" else "",
                tint = velvetColors.accentText,
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
    val library = VelvetApp.library
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
    var draftSongs by remember(playlist.id, isEditing) { mutableStateOf(playlist.songs) }
    var recentlyRemoved by remember { mutableStateOf<Pair<Int, Song>?>(null) }
    var showUndoToast by remember { mutableStateOf(false) }

    val activeSongs = if (isEditing) draftSongs else playlist.songs
    val trackCountText = "${activeSongs.size} ${if (activeSongs.size == 1) "track" else "tracks"}"
    val playbackContext = PlaybackContext(
        id = playlist.id,
        type = RecentType.LOCAL_PLAYLIST,
        title = playlist.name,
        subtitle = trackCountText,
        artworkUrl = activeSongs.firstOrNull()?.thumb ?: playlist.customCover,
    )

    Box(Modifier.fillMaxSize()) {
        BackPage(onBack) {
            item {
                CollectionHeader(
                    title = playlist.name,
                    subtitle = if (isEditing) "Editing playlist ($trackCountText)" else playlist.description.orEmpty(),
                    meta = "$trackCountText • " + songMeta(activeSongs),
                    tint = rememberArtColor(activeSongs.firstOrNull()?.thumb),
                    onTitleClick = { if (!isEditing) renaming = true },
                    onTitleLongClick = { if (!isEditing) renaming = true },
                    art = {
                        PlaylistCover(
                            activeSongs,
                            if (activeSongs.size <= 1) 156.dp else 230.dp,
                            corner = 28.dp,
                            customCover = playlist.customCover,
                            coverMode = playlist.coverMode,
                            coverRef = playlist.coverRef,
                        )
                    },
                    artworkBackdropSize = if (activeSongs.size <= 1) 156.dp else 300.dp,
                    playEnabled = activeSongs.isNotEmpty() && !isEditing,
                    onPlay = { PlayerConnection.play(activeSongs, playlistId = id, context = playbackContext) },
                    onShuffle = { shuffle(activeSongs, playlistId = id, context = playbackContext) },
                    topActions = {
                        if (!isEditing) {
                            PanelIconButton(AppIcons.Pencil, "Edit playlist") { renaming = true }
                            PanelIconButton(Icons.Default.Delete, "Delete playlist") {
                                confirm = ConfirmRequest(
                                    "Delete \"${playlist.name}\"?",
                                    "This playlist and its ${playlist.songs.size} songs will be removed from your library. This can't be undone.",
                                    "Delete",
                                ) { library.deletePlaylist(playlist.id) }
                            }
                        } else {
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
                                    colors = ButtonDefaults.buttonColors(containerColor = velvetColors.accentText),
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
                                    Text("Cancel", color = velvetColors.muted)
                                }
                            }
                        } else {
                            DownloadAllButton(activeSongs)
                        }
                    },
                )
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
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1)
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
                            Icon(Icons.Default.Close, "Remove track", tint = velvetColors.danger)
                        }
                    }
                } else {
                    SongRow(song, onClick = { PlayerConnection.play(activeSongs, i, playlistId = id, context = playbackContext) }, playlistId = id)
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
                    color = velvetColors.surface,
                    borderColor = velvetColors.glassBorder,
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Track removed", color = velvetColors.text, style = MaterialTheme.typography.bodyMedium)
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
                            Text("UNDO (2s)", color = velvetColors.accentText, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        NamePlaylistDialog(
            title = "Rename playlist",
            initial = playlist.name,
            initialDescription = playlist.description.orEmpty(),
            currentPlaylistId = playlist.id,
            existingPlaylists = data.playlists,
            allowDescription = true,
            onDismiss = { renaming = false },
            onConfirmWithDescription = { newName, newDesc ->
                library.updatePlaylistDetails(playlist.id, newName, newDesc)
            },
        )
    }
}


