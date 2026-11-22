package com.velvet.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velvet.app.VelvetApp
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.ContextRecentsManager
import com.velvet.app.data.CoverMode
import com.velvet.app.data.PlaybackContext
import com.velvet.app.data.Playlist
import com.velvet.app.data.PlaylistCoverManager
import com.velvet.app.data.PlaylistEntry
import com.velvet.app.data.PlaylistSorter
import com.velvet.app.data.RecentItem
import com.velvet.app.data.RecentType
import com.velvet.app.data.Song
import com.velvet.app.data.SortDirection
import com.velvet.app.data.SortKey
import com.velvet.app.data.YouTube
import com.velvet.app.data.totalDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            val playlist = if (item.type == RecentType.LOCAL_PLAYLIST) data.playlists.find { it.id == item.id } else null
            RecentItemRow(
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
    playlist: Playlist? = null,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val isArtist = item.type == RecentType.ARTIST
    val isLocalPlaylist = item.type == RecentType.LOCAL_PLAYLIST
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
        } else if (isLocalPlaylist && playlist != null) {
            PlaylistCover(
                songs = playlist.songs,
                size = 52.dp,
                corner = 14.dp,
                customCover = playlist.customCover,
                coverMode = playlist.coverMode,
                coverRef = playlist.coverRef,
            )
        } else {
            Artwork(item.artworkUrl, 52.dp, corner = 14.dp, placeholder = AppIcons.Album)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                playlist?.name ?: item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalPlaylistScreen(id: String, onBack: () -> Unit) {
    val library = VelvetApp.library
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by library.data.collectAsStateWithLifecycle()

    var showEditSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmDialog(confirm) { confirm = null }

    val playlist = data.playlists.find { it.id == id }
    if (playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // Sort entries using pure PlaylistSorter engine
    val activeEntries = remember(playlist.entries, playlist.songs, playlist.sortKey, playlist.sortDirection) {
        PlaylistSorter.sort(playlist.resolvedEntries(), playlist.sortKey, playlist.sortDirection)
    }
    val activeSongs = remember(activeEntries) {
        activeEntries.map { it.song }
    }

    var recentlyRemoved by remember { mutableStateOf<Pair<Int, PlaylistEntry>?>(null) }
    var showUndoToast by remember { mutableStateOf(false) }

    val songCountStr = songCountLabel(activeEntries.size)
    val coverUrl = when (playlist.coverMode) {
        CoverMode.CUSTOM_IMAGE -> playlist.coverRef?.let { "file://${File(File(context.filesDir, "covers"), it).absolutePath}" }
        CoverMode.PRESET -> playlist.customCover ?: "preset:${playlist.coverRef}"
        CoverMode.TRACK_ART -> activeSongs.find { it.id == playlist.coverRef }?.thumb ?: activeSongs.firstOrNull()?.thumb
        CoverMode.AUTO -> activeSongs.firstOrNull()?.thumb
    } ?: playlist.customCover ?: activeSongs.firstOrNull()?.thumb

    val playbackContext = PlaybackContext(
        id = playlist.id,
        type = RecentType.LOCAL_PLAYLIST,
        title = playlist.name,
        subtitle = songCountStr,
        artworkUrl = coverUrl,
    )

    Box(Modifier.fillMaxSize()) {
        BackPage(onBack) {
            item {
                CollectionHeader(
                    title = playlist.name,
                    subtitle = playlist.description.orEmpty(),
                    meta = listOf(songCountStr, totalDuration(activeSongs)).filter { it.isNotBlank() }.joinToString(" • "),
                    tint = rememberArtColor(activeSongs.firstOrNull()?.thumb),
                    onTitleClick = { showEditSheet = true },
                    onTitleLongClick = { showEditSheet = true },
                    art = {
                        Box(
                            modifier = Modifier
                                .shadow(12.dp, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showEditSheet = true },
                        ) {
                            PlaylistCover(
                                activeSongs,
                                if (activeSongs.size <= 1) 156.dp else 230.dp,
                                corner = 12.dp,
                                customCover = playlist.customCover,
                                coverMode = playlist.coverMode,
                                coverRef = playlist.coverRef,
                            )
                        }
                    },
                    artworkBackdropSize = if (activeSongs.size <= 1) 156.dp else 300.dp,
                    playEnabled = activeSongs.isNotEmpty(),
                    onPlay = { PlayerConnection.play(activeSongs, playlistId = id, context = playbackContext) },
                    onShuffle = { shuffle(activeSongs, playlistId = id, context = playbackContext) },
                    topActions = {
                        PanelIconButton(AppIcons.Pencil, "Edit playlist") { showEditSheet = true }
                        Box {
                            PanelIconButton(Icons.Default.MoreVert, "More options") { showOverflowMenu = true }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit playlist") },
                                    leadingIcon = { Icon(AppIcons.Pencil, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showEditSheet = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate playlist") },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        val newId = library.duplicatePlaylist(playlist.id)
                                        if (newId != null) {
                                            Toast.makeText(context, "Playlist duplicated", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete playlist", color = velvetColors.danger) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = velvetColors.danger) },
                                    onClick = {
                                        showOverflowMenu = false
                                        confirm = ConfirmRequest(
                                            "Delete \"${playlist.name}\"?",
                                            "This playlist and its ${playlist.songs.size} songs will be removed from your library. This can't be undone.",
                                            "Delete",
                                        ) {
                                            library.deletePlaylist(playlist.id)
                                            onBack()
                                        }
                                    },
                                )
                            }
                        }
                    },
                    actions = {
                        Surface(
                            onClick = { showSortSheet = true },
                            shape = RoundedCornerShape(20.dp),
                            color = velvetColors.raised,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    AppIcons.Sort,
                                    contentDescription = "Sort playlist",
                                    tint = velvetColors.accentText,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    PlaylistSorter.getSortLabel(playlist.sortKey, playlist.sortDirection),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = velvetColors.text,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        DownloadAllButton(activeSongs)
                    },
                )
            }

            if (playlist.sortKey != SortKey.CUSTOM && activeEntries.isNotEmpty()) {
                item {
                    Text(
                        "Switch to Custom order to rearrange",
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (activeEntries.isEmpty()) {
                item { EmptyHint("No songs in this playlist.") }
            }

            itemsIndexed(activeEntries, key = { _, entry -> entry.entryId }) { index, entry ->
                SongRow(
                    song = entry.song,
                    onClick = {
                        PlayerConnection.play(activeSongs, index, playlistId = id, context = playbackContext)
                    },
                    playlistId = id,
                )
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
                                val currentEntries = playlist.resolvedEntries().toMutableList()
                                currentEntries.add(removed.first.coerceAtMost(currentEntries.size), removed.second)
                                library.setPlaylistEntries(playlist.id, currentEntries)
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

    if (showEditSheet) {
        EditPlaylistSheet(
            playlist = playlist,
            existingPlaylists = data.playlists,
            onDismiss = { showEditSheet = false },
            onSave = { newName, newDesc, newCoverMode, newCoverRef ->
                library.updatePlaylistDetails(playlist.id, newName, newDesc)
                library.setPlaylistCover(playlist.id, newCoverMode, newCoverRef)
                showEditSheet = false
                Toast.makeText(context, "Playlist updated", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showSortSheet) {
        PlaylistSortSheet(
            playlist = playlist,
            activeEntries = activeEntries,
            onDismiss = { showSortSheet = false },
            onSelectSort = { newKey, newDir ->
                library.setPlaylistSort(playlist.id, newKey, newDir)
            },
            onSaveAsCustom = { sortedEntries ->
                library.savePlaylistOrder(playlist.id, sortedEntries)
                Toast.makeText(context, "Order saved as Custom", Toast.LENGTH_SHORT).show()
                showSortSheet = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPlaylistSheet(
    playlist: Playlist,
    existingPlaylists: List<Playlist>,
    onDismiss: () -> Unit,
    onSave: (newName: String, newDesc: String?, newCoverMode: CoverMode, newCoverRef: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf(playlist.name) }
    var description by rememberSaveable { mutableStateOf(playlist.description.orEmpty()) }
    var coverMode by rememberSaveable { mutableStateOf(playlist.coverMode) }
    var coverRef by rememberSaveable { mutableStateOf(playlist.coverRef) }

    var showCoverPicker by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val trimmedName = name.trim()
    val isNameBlank = trimmedName.isEmpty()
    val isNameTooLong = name.length > 80
    val isDescTooLong = description.length > 300
    val isDuplicate = existingPlaylists.any { it.id != playlist.id && it.name.trim().equals(trimmedName, ignoreCase = true) }
    val canSave = !isNameBlank && !isNameTooLong && !isDescTooLong

    val isDirty = name != playlist.name ||
        description != playlist.description.orEmpty() ||
        coverMode != playlist.coverMode ||
        coverRef != playlist.coverRef

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    val fileName = withContext(Dispatchers.IO) {
                        PlaylistCoverManager.processAndSaveCover(context, playlist.id, bytes)
                    }
                    if (fileName != null) {
                        coverMode = CoverMode.CUSTOM_IMAGE
                        coverRef = fileName
                        Toast.makeText(context, "Cover photo updated", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Could not process image", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Could not read image file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showDiscardConfirm) {
        ConfirmDialog(
            ConfirmRequest(
                title = "Discard unsaved changes?",
                message = "You have unsaved changes. Discard them?",
                confirmLabel = "Discard",
                action = {
                    showDiscardConfirm = false
                    onDismiss()
                },
            ),
        ) {
            showDiscardConfirm = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (isDirty) showDiscardConfirm = true else onDismiss()
        },
        containerColor = velvetColors.surface,
        tonalElevation = 0.dp,
        scrimColor = Color(0x99000000),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    if (isDirty) showDiscardConfirm = true else onDismiss()
                }) {
                    Text("Cancel", color = velvetColors.muted)
                }
                Text("Edit Playlist", style = MaterialTheme.typography.titleMedium, color = velvetColors.text)
                Button(
                    onClick = {
                        if (canSave) {
                            onSave(trimmedName, description.trim().ifEmpty { null }, coverMode, coverRef)
                        }
                    },
                    enabled = canSave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = velvetColors.accentText,
                        disabledContainerColor = velvetColors.raised,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Save", color = if (canSave) Color.White else velvetColors.muted)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Cover preview with 12dp rounded corners and soft shadow
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                PlaylistCover(
                    songs = playlist.songs,
                    size = 130.dp,
                    corner = 12.dp,
                    coverMode = coverMode,
                    coverRef = coverRef,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Three cover action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showCoverPicker = true }) {
                    Icon(AppIcons.Palette, contentDescription = null, modifier = Modifier.size(18.dp), tint = velvetColors.accentText)
                    Spacer(Modifier.width(6.dp))
                    Text("Choose cover", color = velvetColors.accentText, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = velvetColors.accentText)
                    Spacer(Modifier.width(6.dp))
                    Text("Upload", color = velvetColors.accentText, style = MaterialTheme.typography.labelMedium)
                }
                if (coverMode != CoverMode.AUTO) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        coverMode = CoverMode.AUTO
                        coverRef = null
                    }) {
                        Text("Revert", color = velvetColors.danger, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 80) name = it },
                label = { Text("Playlist name *") },
                singleLine = true,
                isError = isNameBlank || isNameTooLong,
                supportingText = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (isDuplicate) {
                            Text("A playlist with this name already exists", color = velvetColors.accentText)
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }
                        Text("${name.length}/80", color = if (isNameTooLong) velvetColors.danger else velvetColors.muted)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = velvetColors.accentText,
                    unfocusedBorderColor = velvetColors.rule,
                    focusedTextColor = velvetColors.text,
                    unfocusedTextColor = velvetColors.text,
                ),
            )

            Spacer(Modifier.height(8.dp))

            // Description field
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 300) description = it },
                label = { Text("Description (optional)") },
                maxLines = 3,
                isError = isDescTooLong,
                supportingText = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("${description.length}/300", color = if (isDescTooLong) velvetColors.danger else velvetColors.muted)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = velvetColors.accentText,
                    unfocusedBorderColor = velvetColors.rule,
                    focusedTextColor = velvetColors.text,
                    unfocusedTextColor = velvetColors.text,
                ),
            )
        }
    }

    if (showCoverPicker) {
        ChooseCoverPickerSheet(
            playlist = playlist,
            selectedMode = coverMode,
            selectedRef = coverRef,
            onDismiss = { showCoverPicker = false },
            onSelect = { mode, ref ->
                coverMode = mode
                coverRef = ref
                showCoverPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseCoverPickerSheet(
    playlist: Playlist,
    selectedMode: CoverMode,
    selectedRef: String?,
    onDismiss: () -> Unit,
    onSelect: (CoverMode, String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    val trackThumbs = remember(playlist.songs) {
        playlist.songs.mapNotNull { it.thumb }.distinct()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = velvetColors.surface,
        tonalElevation = 0.dp,
        scrimColor = Color(0x99000000),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Choose Cover Art", style = MaterialTheme.typography.titleLarge, color = velvetColors.text)
            Spacer(Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = velvetColors.surface,
                contentColor = velvetColors.accentText,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("From songs (${trackThumbs.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Presets (12)") },
                )
            }

            Spacer(Modifier.height(16.dp))

            if (selectedTab == 0) {
                if (trackThumbs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No track artworks in this playlist yet.", color = velvetColors.muted)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(trackThumbs) { thumbUrl ->
                            val isSelected = selectedMode == CoverMode.TRACK_ART && selectedRef == thumbUrl
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) velvetColors.accentText else velvetColors.rule,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable {
                                        onSelect(CoverMode.TRACK_ART, thumbUrl)
                                    },
                            ) {
                                Artwork(thumbUrl, 100.dp, corner = 12.dp)
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                            .background(velvetColors.accentText, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(PlaylistCoverPresets.presets) { preset ->
                        val isSelected = selectedMode == CoverMode.PRESET && selectedRef == preset.id.toString()
                        Column(
                            modifier = Modifier
                                .clickable {
                                    onSelect(CoverMode.PRESET, preset.id.toString())
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(preset.gradient)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else velvetColors.rule,
                                        shape = RoundedCornerShape(12.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(preset.icon, contentDescription = null, tint = preset.iconTint, modifier = Modifier.size(32.dp))
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                            .background(Color.White, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(preset.name, style = MaterialTheme.typography.labelSmall, color = velvetColors.text, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSortSheet(
    playlist: Playlist,
    activeEntries: List<PlaylistEntry>,
    onDismiss: () -> Unit,
    onSelectSort: (SortKey, SortDirection) -> Unit,
    onSaveAsCustom: (List<PlaylistEntry>) -> Unit,
) {
    val context = LocalContext.current
    var confirmCustom by remember { mutableStateOf(false) }

    if (confirmCustom) {
        ConfirmDialog(
            ConfirmRequest(
                title = "Freeze as Custom Order?",
                message = "This will permanently bake the current view into the playlist's track list and switch the sort mode to Custom so you can manually drag and rearrange tracks.",
                confirmLabel = "Freeze as Custom",
                action = {
                    confirmCustom = false
                    onSaveAsCustom(activeEntries)
                },
            ),
        ) {
            confirmCustom = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sort Playlist", style = MaterialTheme.typography.titleLarge, color = velvetColors.text)
                    Text(
                        "Active: ${PlaylistSorter.getSortLabel(playlist.sortKey, playlist.sortDirection)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.accentText,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Done", color = velvetColors.accentText, style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.height(16.dp))

            val options = listOf(
                SortKey.CUSTOM to "Custom order",
                SortKey.TITLE to "Title",
                SortKey.ARTIST to "Artist",
                SortKey.ALBUM to "Album",
                SortKey.DATE_ADDED to "Recently added",
                SortKey.DURATION to "Duration",
            )

            options.forEach { (key, label) ->
                val isSelected = playlist.sortKey == key
                val directionBadge = when (key) {
                    SortKey.CUSTOM -> null
                    SortKey.TITLE, SortKey.ARTIST, SortKey.ALBUM -> {
                        val dir = if (isSelected) playlist.sortDirection else SortDirection.ASC
                        if (dir == SortDirection.ASC) "A → Z" else "Z → A"
                    }
                    SortKey.DATE_ADDED -> {
                        val dir = if (isSelected) playlist.sortDirection else SortDirection.DESC
                        if (dir == SortDirection.DESC) "Newest" else "Oldest"
                    }
                    SortKey.DURATION -> {
                        val dir = if (isSelected) playlist.sortDirection else SortDirection.ASC
                        if (dir == SortDirection.ASC) "Shortest" else "Longest"
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (key == SortKey.CUSTOM) {
                                onSelectSort(SortKey.CUSTOM, SortDirection.ASC)
                                Toast.makeText(context, "Sorted by Custom order", Toast.LENGTH_SHORT).show()
                            } else if (isSelected) {
                                // Toggle direction if already selected
                                val newDir = if (playlist.sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                                onSelectSort(key, newDir)
                                Toast.makeText(context, "Sorted by ${PlaylistSorter.getSortLabel(key, newDir)}", Toast.LENGTH_SHORT).show()
                            } else {
                                val defaultDir = if (key == SortKey.DATE_ADDED) SortDirection.DESC else SortDirection.ASC
                                onSelectSort(key, defaultDir)
                                Toast.makeText(context, "Sorted by ${PlaylistSorter.getSortLabel(key, defaultDir)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) velvetColors.accentText else velvetColors.text,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (directionBadge != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) velvetColors.accent.copy(alpha = 0.2f) else velvetColors.raised,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text(
                                    directionBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) velvetColors.accentText else velvetColors.muted,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = velvetColors.accentText)
                        }
                    }
                }
                HorizontalDivider(color = velvetColors.rule.copy(alpha = 0.5f))
            }

            if (playlist.sortKey != SortKey.CUSTOM) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            "Manual reordering",
                            style = MaterialTheme.typography.bodyMedium,
                            color = velvetColors.text,
                        )
                        Text(
                            "Freeze this sorted order to rearrange songs manually by dragging",
                            style = MaterialTheme.typography.bodySmall,
                            color = velvetColors.muted,
                        )
                    }
                    Button(
                        onClick = { confirmCustom = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = velvetColors.raised),
                    ) {
                        Text("Set as Custom", color = velvetColors.accentText, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}


