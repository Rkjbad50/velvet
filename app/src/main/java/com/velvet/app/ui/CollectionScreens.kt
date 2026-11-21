package com.velvet.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
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
import java.io.FileOutputStream

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
    val library = VelvetApp.library
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

@Composable
fun RecentScreen(onBack: () -> Unit) {
    val data by VelvetApp.library.data.collectAsStateWithLifecycle()
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
    var showSortMenu by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var draftSongs by remember(playlist.id, isEditing) { mutableStateOf(playlist.songs) }
    var recentlyRemoved by remember { mutableStateOf<Pair<Int, Song>?>(null) }
    var showUndoToast by remember { mutableStateOf(false) }

    val activeSongs = if (isEditing) draftSongs else playlist.songs

    Box(Modifier.fillMaxSize()) {
        BackPage(onBack) {
            item {
                val trackCountText = "${activeSongs.size} ${if (activeSongs.size == 1) "track" else "tracks"}"
                CollectionHeader(
                    title = playlist.name,
                    subtitle = if (isEditing) "Editing playlist ($trackCountText)" else playlist.description.orEmpty(),
                    meta = "$trackCountText • " + songMeta(activeSongs),
                    tint = rememberArtColor(activeSongs.firstOrNull()?.thumb),
                    onTitleClick = { if (!isEditing) renaming = true },
                    onTitleLongClick = { if (!isEditing) renaming = true },
                    art = {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(28.dp))
                                .clickable { showCoverPicker = true },
                        ) {
                            PlaylistCover(
                                activeSongs,
                                if (activeSongs.size <= 1) 156.dp else 230.dp,
                                corner = 28.dp,
                                customCover = playlist.customCover,
                            )
                            Box(
                                modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(36.dp)
                                .background(velvetColors.surface.copy(alpha = 0.90f), CircleShape)
                                .border(1.dp, velvetColors.rule, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    AppIcons.Palette,
                                    contentDescription = "Change cover art",
                                    tint = velvetColors.accentText,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                    artworkBackdropSize = if (activeSongs.size <= 1) 156.dp else 300.dp,
                    playEnabled = activeSongs.isNotEmpty() && !isEditing,
                    onPlay = { PlayerConnection.play(activeSongs, playlistId = id) },
                    onShuffle = { shuffle(activeSongs, playlistId = id) },
                    topActions = {
                        if (!isEditing) {
                            PanelIconButton(AppIcons.Pencil, "Rename playlist") { renaming = true }
                            PanelIconButton(Icons.Default.Edit, "Edit tracks") {
                                draftSongs = playlist.songs
                                isEditing = true
                            }
                            PanelIconButton(AppIcons.Palette, "Change cover art") { showCoverPicker = true }
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

    if (showCoverPicker) {
        PlaylistCoverPickerSheet(playlist = playlist, onDismiss = { showCoverPicker = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistCoverPickerSheet(
    playlist: Playlist,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val library = VelvetApp.library

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val dir = File(context.filesDir, "playlist_covers")
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, "cover_${playlist.id}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                library.setPlaylistCover(playlist.id, "file://${targetFile.absolutePath}")
                Toast.makeText(context, "Cover art updated from gallery", Toast.LENGTH_SHORT).show()
                onDismiss()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "Change Playlist Cover",
                style = MaterialTheme.typography.headlineSmall,
                color = velvetColors.text,
            )
            Text(
                "Pick an in-built gradient preset or upload a custom image",
                style = MaterialTheme.typography.bodySmall,
                color = velvetColors.muted,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )

            Text(
                "10 IN-BUILT PRESETS",
                style = MaterialTheme.typography.labelSmall,
                color = velvetColors.accentText,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val rows = PlaylistCoverPresets.presets.chunked(5)
                rows.forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            val isSelected = playlist.customCover == "preset:${preset.id}"
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        library.setPlaylistCover(playlist.id, "preset:${preset.id}")
                                        Toast.makeText(context, "Cover updated: ${preset.name}", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(preset.gradient)
                                        .then(
                                            if (isSelected) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(14.dp))
                                            else Modifier.border(1.dp, velvetColors.rule, RoundedCornerShape(14.dp))
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        preset.icon,
                                        contentDescription = preset.name,
                                        tint = preset.iconTint,
                                        modifier = Modifier.fillMaxSize(0.42f),
                                    )
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(3.dp)
                                                .size(14.dp)
                                                .background(Color.White, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(10.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    preset.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) velvetColors.accentText else velvetColors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = velvetColors.rule)
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = velvetColors.raised),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Icon(AppIcons.Palette, contentDescription = null, tint = velvetColors.accentText)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Upload from Gallery / Files",
                        style = MaterialTheme.typography.titleMedium,
                        color = velvetColors.text,
                    )
                }
            }

            if (playlist.customCover != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        library.setPlaylistCover(playlist.id, null)
                        Toast.makeText(context, "Reset to default song collage", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset to default collage", color = velvetColors.danger)
                }
            }
        }
    }
}
