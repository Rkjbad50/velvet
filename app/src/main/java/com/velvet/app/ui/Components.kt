package com.velvet.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.velvet.app.data.CoverMode
import com.velvet.app.data.Playlist
import com.velvet.app.data.PlaylistNameValidator
import java.io.File
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.velvet.app.VelvetApp
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.Song
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.formatDuration
import com.velvet.app.data.thumbSized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ------------------------------------------------------------------ artwork

/** Album art at the right resolution for its size, over a matte placeholder. */
@Composable
fun Artwork(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    placeholder: ImageVector = AppIcons.MusicNote,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(velvetColors.raised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = velvetColors.muted, modifier = Modifier.fillMaxSize(0.4f))
        if (url != null) {
            AsyncImage(
                model = thumbSized(url, px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

data class CoverPreset(
    val id: Int,
    val name: String,
    val gradient: Brush,
    val icon: ImageVector,
    val iconTint: Color = Color.White,
)

object PlaylistCoverPresets {
    val presets = listOf(
        CoverPreset(
            id = 0,
            name = "Sunset Glow",
            gradient = Brush.linearGradient(listOf(Color(0xFFFF512F), Color(0xFFDD2476), Color(0xFF4A00E0))),
            icon = AppIcons.QueueMusic,
        ),
        CoverPreset(
            id = 1,
            name = "Cyberpunk Neon",
            gradient = Brush.linearGradient(listOf(Color(0xFF00F2FE), Color(0xFF4FACFE), Color(0xFF7F00FF))),
            icon = AppIcons.Sparkles,
        ),
        CoverPreset(
            id = 2,
            name = "Velvet Rose",
            gradient = Brush.linearGradient(listOf(Color(0xFFED213A), Color(0xFF93291E))),
            icon = Icons.Default.Favorite,
        ),
        CoverPreset(
            id = 3,
            name = "Deep Ocean",
            gradient = Brush.linearGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))),
            icon = AppIcons.Radio,
        ),
        CoverPreset(
            id = 4,
            name = "Golden Hour",
            gradient = Brush.linearGradient(listOf(Color(0xFFF7971E), Color(0xFFFFD200))),
            icon = AppIcons.Sparkles,
            iconTint = Color(0xFF2A1500),
        ),
        CoverPreset(
            id = 5,
            name = "Emerald Flow",
            gradient = Brush.linearGradient(listOf(Color(0xFF11998E), Color(0xFF38EF7D))),
            icon = AppIcons.PlaylistAdd,
        ),
        CoverPreset(
            id = 6,
            name = "Nordic Aurora",
            gradient = Brush.linearGradient(listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0), Color(0xFF00C9FF))),
            icon = AppIcons.Sparkles,
        ),
        CoverPreset(
            id = 7,
            name = "AMOLED Prism",
            gradient = Brush.linearGradient(listOf(Color(0xFF18181B), Color(0xFF27272A), Color(0xFF09090B))),
            icon = AppIcons.Library,
            iconTint = Color(0xFFA855F7),
        ),
        CoverPreset(
            id = 8,
            name = "Midnight Groove",
            gradient = Brush.linearGradient(listOf(Color(0xFF1A2980), Color(0xFF26D0CE))),
            icon = AppIcons.Repeat,
        ),
        CoverPreset(
            id = 9,
            name = "Pastel Dream",
            gradient = Brush.linearGradient(listOf(Color(0xFFFF9A8B), Color(0xFFFF6A88), Color(0xFFFF99AC))),
            icon = Icons.Default.Favorite,
        ),
        CoverPreset(
            id = 10,
            name = "Solar Flare",
            gradient = Brush.linearGradient(listOf(Color(0xFFFF416C), Color(0xFFFF4B2B), Color(0xFFFF8008))),
            icon = AppIcons.Sparkles,
        ),
        CoverPreset(
            id = 11,
            name = "Cosmic Indigo",
            gradient = Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B))),
            icon = AppIcons.Radio,
        ),
    )

    @Composable
    fun RenderPreset(presetId: Int, size: Dp, corner: Dp) {
        val preset = presets.getOrNull(presetId) ?: presets[0]
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(preset.gradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                preset.icon,
                contentDescription = preset.name,
                tint = preset.iconTint,
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
    }
}

/** 2×2 collage of the first four distinct covers, preset gradient/icon, track art, or custom uploaded image. */
@Composable
fun PlaylistCover(
    songs: List<Song>,
    size: Dp,
    corner: Dp = 14.dp,
    customCover: String? = null,
    coverMode: CoverMode = CoverMode.AUTO,
    coverRef: String? = null,
) {
    val context = LocalContext.current

    when (coverMode) {
        CoverMode.PRESET -> {
            val presetId = coverRef?.toIntOrNull() ?: 0
            PlaylistCoverPresets.RenderPreset(presetId, size, corner)
            return
        }
        CoverMode.CUSTOM_IMAGE -> {
            if (!coverRef.isNullOrBlank()) {
                val file = File(File(context.filesDir, "covers"), coverRef)
                if (file.exists() && file.length() > 0) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(size)
                            .clip(RoundedCornerShape(corner)),
                    )
                    return
                }
            }
        }
        CoverMode.TRACK_ART -> {
            val trackThumb = songs.find { it.id == coverRef }?.thumb ?: coverRef
            if (!trackThumb.isNullOrBlank()) {
                Artwork(trackThumb, size, corner = corner, placeholder = AppIcons.QueueMusic)
                return
            }
        }
        CoverMode.AUTO -> {
            // Handled below
        }
    }

    if (customCover != null) {
        if (customCover.startsWith("preset:")) {
            val presetId = customCover.removePrefix("preset:").toIntOrNull() ?: 0
            PlaylistCoverPresets.RenderPreset(presetId, size, corner)
            return
        } else if (customCover.startsWith("file://") || customCover.startsWith("/")) {
            val model = if (customCover.startsWith("file://")) customCover else "file://$customCover"
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(corner)),
            )
            return
        }
    }

    val thumbs = songs.mapNotNull { it.thumb }.distinct().take(4)
    if (thumbs.isEmpty()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(velvetColors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.QueueMusic,
                contentDescription = null,
                tint = velvetColors.accentText,
                modifier = Modifier.size(size * 0.42f),
            )
        }
        return
    }
    if (thumbs.size < 4) {
        Artwork(thumbs.first(), size, corner = corner, placeholder = AppIcons.QueueMusic)
        return
    }
    val half = size / 2
    val px = with(LocalDensity.current) { half.roundToPx() }
    Column(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner)),
    ) {
        for (row in 0..1) {
            Row {
                for (col in 0..1) {
                    AsyncImage(
                        model = thumbSized(thumbs[row * 2 + col], px),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(half),
                    )
                }
            }
        }
    }
}

@Composable
private fun IconCover(size: Dp, corner: Dp, fill: Color, icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.fillMaxSize(0.42f))
    }
}

@Composable
fun LikedCover(size: Dp, corner: Dp = 14.dp) =
    IconCover(size, corner, velvetColors.accent, Icons.Default.Favorite, velvetColors.onAccent)

@Composable
fun RecentCover(size: Dp, corner: Dp = 14.dp) =
    IconCover(size, corner, velvetColors.raised, AppIcons.History, velvetColors.accentText)

@Composable
fun DownloadsCover(size: Dp, corner: Dp = 14.dp) =
    IconCover(size, corner, velvetColors.raised, AppIcons.Download, velvetColors.accentText)

private val artColors = ConcurrentHashMap<String, Color>()

/** A vivid color pulled from the artwork, for the glows behind it. */
@Composable
fun rememberArtColor(url: String?, fallback: Color = velvetColors.accent): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(url?.let { artColors[it] } ?: fallback) }
    LaunchedEffect(url) {
        if (url == null || artColors.containsKey(url)) return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { extractColor(context, url) }
        if (extracted != null) {
            artColors[url] = extracted
            color = extracted
        }
    }
    return animateColorAsState(color, tween(700), label = "artColor").value
}

private suspend fun extractColor(context: Context, url: String): Color? = runCatching {
    val request = ImageRequest.Builder(context).data(thumbSized(url, 96)).size(96).allowHardware(false).build()
    val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
        ?: return@runCatching null
    val palette = Palette.from(bitmap).generate()
    val argb = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(0)))
    if (argb == 0) null else Color(argb)
}.getOrNull()

// ------------------------------------------------------------------ song rows & actions

/** Starts looking up a song's stream as soon as a finger touches it, before the tap completes. */
fun Modifier.prefetchOnTouch(songId: String): Modifier = pointerInput(songId) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        StreamResolver.prefetch(songId)
    }
}

/** Opens the song actions sheet; provided by the app root so there's only ever one sheet. */
class SongMenuRequest(
    val song: Song,
    val playlistId: String? = null,
    val queueItemId: String? = null,
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
)

val LocalSongMenu = staticCompositionLocalOf<(SongMenuRequest) -> Unit> { {} }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    playlistId: String? = null,
    trackNumber: Int? = null,
    showArt: Boolean = true,
    sourceHint: String? = null,
) {
    val openMenu = LocalSongMenu.current
    val active = PlayerConnection.current?.id == song.id
    val downloadedIds by VelvetApp.library.downloadedIds.collectAsStateWithLifecycle()
    val downloaded = song.id in downloadedIds

    Row(
        Modifier
            .fillMaxWidth()
            .prefetchOnTouch(song.id)
            .combinedClickable(onClick = onClick, onLongClick = { openMenu(SongMenuRequest(song, playlistId)) })
            .padding(start = 20.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            Text(
                trackNumber.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (active) velvetColors.accentText else velvetColors.muted,
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
        if (showArt) Artwork(song.thumb, 54.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (active) velvetColors.accentText else velvetColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(
                        AppIcons.DownloadDone, "Downloaded",
                        tint = velvetColors.accentText,
                        modifier = Modifier
                            .size(15.dp)
                            .padding(end = 3.dp),
                    )
                }
                val duration = formatDuration(song.durationSec)
                Text(
                    if (duration.isEmpty()) song.artist else "${song.artist} • $duration",
                    style = MaterialTheme.typography.bodyMedium,
                    color = velvetColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (sourceHint != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Icon(
                        AppIcons.QueueMusic,
                        contentDescription = null,
                        tint = velvetColors.accentText,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        sourceHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = velvetColors.accentText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = { openMenu(SongMenuRequest(song, playlistId)) }) {
            Icon(Icons.Default.MoreVert, "More", tint = velvetColors.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(request: SongMenuRequest, onDismiss: () -> Unit) {
    val song = request.song
    val library = VelvetApp.library
    val downloads = VelvetApp.downloads
    val likedIds by library.likedIds.collectAsStateWithLifecycle()
    val downloadedIds by library.downloadedIds.collectAsStateWithLifecycle()
    val progress by downloads.progress.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val context = LocalContext.current
    var pickingPlaylist by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmDialog(confirm) { confirm = null }

    val liked = song.id in likedIds
    val downloaded = song.id in downloadedIds
    val downloading = progress.containsKey(song.id)

    VelvetModalSheet(
        onDismissRequest = onDismiss,
    ) { sheet ->
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(song.thumb, 56.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = velvetColors.muted)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = velvetColors.rule)

        AnimatedContent(
            targetState = pickingPlaylist,
            transitionSpec = {
                (slideInVertically(initialOffsetY = { it / 10 }, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(200, easing = FastOutSlowInEasing)))
                    .togetherWith(
                        slideOutVertically(targetOffsetY = { -it / 12 }, animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                            fadeOut(tween(160, easing = FastOutSlowInEasing)),
                    )
            },
            label = "songSheetContent",
        ) { isPicking ->
            Column(Modifier.fillMaxWidth()) {
                if (isPicking) {
                    PlaylistPicker(song, onDone = { sheet.dismiss() })
                } else {
                    SheetAction(
                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                        tint = if (liked) velvetColors.danger else null,
                    ) { sheet.dismissThen { library.toggleLike(song) } }
                    SheetAction(AppIcons.PlaylistAdd, "Add to playlist") { pickingPlaylist = true }
                    SheetAction(AppIcons.SkipNext, "Play next") { sheet.dismissThen { PlayerConnection.playNext(song) } }
                    SheetAction(AppIcons.QueueMusic, "Add to queue") { sheet.dismissThen { PlayerConnection.enqueue(song) } }
                    SheetAction(AppIcons.Radio, "Start song radio") { sheet.dismissThen { PlayerConnection.startRadio(song) } }
                    when {
                        downloaded -> SheetAction(Icons.Default.Delete, "Remove download") {
                            confirm = ConfirmRequest(
                                "Remove download?",
                                "\"${song.title}\" will no longer play offline. You can download it again any time.",
                                "Remove",
                            ) { sheet.dismissThen { downloads.remove(song.id) } }
                        }
                        downloading -> SheetAction(AppIcons.Download, "Downloading…", enabled = false) {}
                        else -> SheetAction(AppIcons.Download, "Download") { sheet.dismissThen { downloads.download(song) } }
                    }
                    if (song.artistUrl != null) {
                        val originRegistry = LocalOriginRegistry.current
                        SheetAction(Icons.Default.Person, "Go to artist") {
                            val artistOrigin = originRegistry?.peekBestOrigin()
                            sheet.dismissThen {
                                nav.open(Route.Artist(ArtistRef(song.artistUrl, song.artist)), origin = artistOrigin)
                            }
                        }
                    }
                    SheetAction(Icons.Default.Share, "Share") { sheet.dismissThen { shareSong(context, song) } }
                    if (request.onMoveUp != null) {
                        SheetAction(Icons.Default.KeyboardArrowUp, "Move up") {
                            sheet.dismissThen { request.onMoveUp.invoke() }
                        }
                    }
                    if (request.onMoveDown != null) {
                        SheetAction(Icons.Default.KeyboardArrowDown, "Move down") {
                            sheet.dismissThen { request.onMoveDown.invoke() }
                        }
                    }
                    if (request.playlistId != null) {
                        SheetAction(Icons.Default.Delete, "Remove from this playlist") {
                            confirm = ConfirmRequest(
                                "Remove from playlist?",
                                "\"${song.title}\" will be removed from this playlist.",
                                "Remove",
                            ) {
                                sheet.dismissThen { library.removeFromPlaylist(request.playlistId, song.id) }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PlaylistPicker(song: Song, onDone: () -> Unit) {
    val library = VelvetApp.library
    val data by library.data.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    SheetAction(Icons.Default.Add, "New playlist") { creating = true }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        items(data.playlists, key = { it.id }) { p ->
            val already = p.songs.any { it.id == song.id }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !already) { library.addToPlaylist(p.id, song); onDone() }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaylistCover(p.songs, 46.dp, customCover = p.customCover, coverMode = p.coverMode, coverRef = p.coverRef)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (already) "Already added" else "${p.songs.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.muted,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))

    if (creating) {
        NamePlaylistDialog("New playlist", onDismiss = { creating = false }) { name ->
            library.createPlaylist(name, listOf(song))
            onDone()
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .captureOriginOnPress(cornerRadiusDp = 16f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .panel(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint ?: velvetColors.accentText, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) velvetColors.text else velvetColors.muted,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

fun shareSong(context: Context, song: Song) {
    val text = "${song.title} – ${song.artist}\nhttps://music.youtube.com/watch?v=${song.id}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ------------------------------------------------------------------ albums & artists

@Composable
fun AlbumCard(album: AlbumRef, size: Dp = 150.dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(size)
            .captureOriginOnPress(cornerRadiusDp = 26f)
            .clickable(onClick = onClick),
    ) {
        Artwork(album.thumb, size, corner = 26.dp, placeholder = AppIcons.Album)
        Text(
            album.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (album.subtitle.isNotBlank()) {
            Text(
                album.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = velvetColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ArtistCircle(artist: ArtistRef, size: Dp = 128.dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(size)
            .captureOriginOnPress(cornerRadiusDp = size.value * 0.5f)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(velvetColors.accent)
                .padding(3.dp),
        ) {
            Artwork(artist.thumb, size - 6.dp, corner = size / 2, placeholder = Icons.Default.Person)
        }
        Text(
            artist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text("Artist", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted)
    }
}

/** List row for an album, playlist or artist (artists get round art). */
@Composable
fun BrowseRow(
    title: String,
    subtitle: String,
    thumb: String?,
    round: Boolean = false,
    placeholder: ImageVector = AppIcons.Album,
    art: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .captureOriginOnPress(cornerRadiusDp = 16f)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (art != null) art() else Artwork(thumb, 58.dp, corner = if (round) 29.dp else 14.dp, placeholder = placeholder)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = if (showChevron) 8.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = velvetColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showChevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = velvetColors.muted)
    }
}

fun artistSubtitle(artist: ArtistRef): String = "Artist"

// ------------------------------------------------------------------ collection pages

/** A scrolling page with a back button that stays put while the content scrolls. */
@Composable
fun BackPage(onBack: () -> Unit, heading: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    if (heading != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
                Box(Modifier.weight(1f)) { heading() }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), content = content)
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            content()
        }
        PanelIconButton(
            Icons.AutoMirrored.Filled.ArrowBack,
            "Back",
            modifier = Modifier.padding(12.dp),
            fill = velvetColors.background,
            onClick = onBack,
        )
    }
}

/** Header for albums, playlists, Liked Songs and Downloads: glowing art, titles, actions, play button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String,
    meta: String,
    tint: Color,
    art: @Composable () -> Unit,
    playEnabled: Boolean,
    isPlaying: Boolean = false,
    isShuffled: Boolean = false,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    artworkBackdropSize: Dp = 300.dp,
    onTitleClick: (() -> Unit)? = null,
    onTitleLongClick: (() -> Unit)? = null,
    onSubtitleClick: (() -> Unit)? = null,
    topActions: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        // the back arrow floats above the page (see BackPage); this row leaves room for it
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) { topActions() }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(artworkBackdropSize)
                    .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.55f), Color.Transparent))),
            )
            art()
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            val titleModifier = if (onTitleClick != null || onTitleLongClick != null) {
                Modifier.combinedClickable(
                    onClick = { onTitleClick?.invoke() },
                    onLongClick = { onTitleLongClick?.invoke() },
                )
            } else {
                Modifier
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = velvetColors.accentText,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .then(if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier),
                )
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = velvetColors.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                actions()
                Spacer(Modifier.weight(1f))
                PanelIconButton(
                    icon = AppIcons.Shuffle,
                    contentDescription = if (isShuffled) "Shuffle is on (tap to reshuffle)" else "Shuffle play",
                    size = 48.dp,
                    tint = if (isShuffled) velvetColors.accentText else velvetColors.text,
                    fill = if (isShuffled) velvetColors.accent.copy(alpha = 0.20f) else velvetColors.surface,
                    onClick = { if (playEnabled) onShuffle() },
                )
                PlayCircle(
                    enabled = playEnabled,
                    icon = if (isPlaying) AppIcons.Pause else AppIcons.Play,
                    onClick = onPlay,
                )
            }
        }
    }
}

@Composable
fun PlayCircle(
    enabled: Boolean = true,
    size: Dp = 60.dp,
    icon: ImageVector = AppIcons.Play,
    onClick: () -> Unit,
) =
    AccentCircleButton(
        icon = icon,
        contentDescription = if (icon == AppIcons.Pause) "Pause" else "Play",
        size = size,
        enabled = enabled,
        onClick = onClick,
    )

/** Download-all toggle for a list of songs; shows progress while downloading. */
@Composable
fun DownloadAllButton(songs: List<Song>) {
    val downloadedIds by VelvetApp.library.downloadedIds.collectAsStateWithLifecycle()
    val progress by VelvetApp.downloads.progress.collectAsStateWithLifecycle()
    val all = songs.isNotEmpty() && songs.all { it.id in downloadedIds }
    val busy = songs.any { progress.containsKey(it.id) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    ConfirmDialog(confirm) { confirm = null }

    Box(
        Modifier
            .size(48.dp)
            .panel(RoundedCornerShape(17.dp))
            .clickable(enabled = songs.isNotEmpty() && !busy) {
                if (all) {
                    confirm = ConfirmRequest(
                        "Remove ${songs.size} downloads?",
                        "These songs will no longer play offline. You can download them again any time.",
                        "Remove all",
                    ) { VelvetApp.downloads.removeAll(songs.map { it.id }) }
                } else {
                    VelvetApp.downloads.downloadAll(songs)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = velvetColors.accentText)
            all -> Icon(AppIcons.DownloadDone, "Remove downloads", tint = velvetColors.accentText)
            else -> Icon(AppIcons.Download, "Download all", tint = velvetColors.text)
        }
    }
}

// ------------------------------------------------------------------ misc

@Composable
fun NamePlaylistDialog(
    title: String,
    initial: String = "",
    initialDescription: String = "",
    currentPlaylistId: String? = null,
    existingPlaylists: List<Playlist> = emptyList(),
    allowDescription: Boolean = false,
    onDismiss: () -> Unit,
    onConfirmWithDescription: (name: String, description: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var description by remember { mutableStateOf(initialDescription) }
    val validation = PlaylistNameValidator.validate(name, currentPlaylistId, existingPlaylists)
    val isValid = validation is PlaylistNameValidator.ValidationResult.Valid

    fun submit(dialog: VelvetDialogScope) {
        if (isValid) {
            dialog.dismissThen {
                onConfirmWithDescription(name.trim(), description.trim().ifEmpty { null })
                onDismiss()
            }
        }
    }

    VelvetAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { dialog ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("My playlist") },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = validation is PlaylistNameValidator.ValidationResult.Error,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit(dialog) }),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (validation is PlaylistNameValidator.ValidationResult.Error) {
                        Text(
                            text = validation.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = velvetColors.danger,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        text = "${name.length}/${PlaylistNameValidator.MAX_LENGTH}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (name.length > PlaylistNameValidator.MAX_LENGTH) velvetColors.danger else velvetColors.muted,
                    )
                }

                if (allowDescription) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        placeholder = { Text("Add an optional description") },
                        label = { Text("Description (optional)") },
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit(dialog) }),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { dialog ->
            TextButton(
                enabled = isValid,
                onClick = { submit(dialog) },
            ) {
                Text("Save")
            }
        },
        dismissButton = { dialog ->
            TextButton(onClick = { dialog.dismissThen(onDismiss) }) {
                Text("Cancel", color = velvetColors.muted)
            }
        },
    )
}

@Composable
fun NamePlaylistDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    NamePlaylistDialog(
        title = title,
        initial = initial,
        initialDescription = "",
        currentPlaylistId = null,
        existingPlaylists = emptyList(),
        allowDescription = false,
        onDismiss = onDismiss,
        onConfirmWithDescription = { name, _ -> onConfirm(name) },
    )
}

@Composable
fun ScreenHeading(label: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = velvetColors.accentText)
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = velvetColors.accentText) }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = velvetColors.muted,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 24.dp),
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(Modifier.size(34.dp), color = velvetColors.accentText, strokeWidth = 3.dp) }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = velvetColors.muted)
        FilterPill("Retry", onClick = onRetry)
    }
}


/** Shared silky spring motion constants and container primitives for popups, sheets, dialogs and menus. */
object VelvetPopupMotion {
    val DialogShape = RoundedCornerShape(24.dp)
    val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val MenuShape = RoundedCornerShape(16.dp)
    val enterSpring = spring<Float>(dampingRatio = 0.90f, stiffness = 380f)
    val exitTween = tween<Float>(durationMillis = 210, easing = FastOutSlowInEasing)
    val sliderSpring = spring<Float>(dampingRatio = 0.88f, stiffness = 460f)
}

class VelvetDialogScope internal constructor(
    private val closeHandler: (() -> Unit) -> Unit,
) {
    /** Smoothly animates the dialog out before running [afterAction]. */
    fun dismissThen(afterAction: () -> Unit = {}) {
        closeHandler(afterAction)
    }
}

class VelvetSheetScope internal constructor(
    private val hideHandler: (() -> Unit) -> Unit,
) {
    fun dismiss() = hideHandler {}

    /** Smoothly hides the bottom sheet before running [afterHide]. */
    fun dismissThen(afterHide: () -> Unit) = hideHandler(afterHide)
}

@Composable
private fun VelvetDialogOverlayShell(
    origin: NavOrigin?,
    progress: Float,
    isExiting: Boolean,
    canDismiss: Boolean,
    onRequestClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        dialogWindow?.setDimAmount(0f)
        dialogWindow?.setWindowAnimations(0)
    }
    BackHandler(enabled = true) {
        if (canDismiss) onRequestClose()
    }
    var overlayW by remember { mutableFloatStateOf(0f) }
    var overlayH by remember { mutableFloatStateOf(0f) }
    var overlayTopLeftInRoot by remember { mutableStateOf(Offset.Zero) }
    val densityPx = LocalDensity.current.density
    val spec = OriginTransformCalculator.compute(
        origin = origin,
        containerWidth = overlayW,
        containerHeight = overlayH,
        progress = progress,
        isExiting = isExiting,
        targetCenterX = overlayTopLeftInRoot.x + overlayW * 0.5f,
        targetCenterY = overlayTopLeftInRoot.y + overlayH * 0.5f,
        minStartScale = 0.28f,
        maxStartScale = 0.78f,
        targetCornerDp = 24f,
        targetScrimAlpha = 0.56f,
        fallbackOffsetYPx = 24f * densityPx,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> if (c.isAttached) overlayTopLeftInRoot = c.positionInRoot() }
            .onSizeChanged { s ->
                overlayW = s.width.toFloat()
                overlayH = s.height.toFloat()
            }
            .background(Color.Black.copy(alpha = spec.scrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (canDismiss) onRequestClose()
            }
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = spec.translationX
                    translationY = spec.translationY
                    scaleX = spec.scale
                    scaleY = spec.scale
                    alpha = spec.shellAlpha
                    if (spec.cornerDp > 0.25f) {
                        shape = RoundedCornerShape(spec.cornerDp.dp)
                        clip = true
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            content()
        }
    }
}

/** Modal dialog container with unified 120Hz spring entrance and reverse exit animation. */
@Composable
fun VelvetAnimatedDialog(
    onDismissRequest: () -> Unit,
    canDismiss: Boolean = true,
    content: @Composable VelvetDialogScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val registry = LocalOriginRegistry.current
    val origin = remember { registry?.consumeBestOrigin() }
    val progress = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, OriginTransformCalculator.windowOpenSpring)
    }

    val closeAndRun: (() -> Unit) -> Unit = { afterAction ->
        if (!isClosing) {
            isClosing = true
            scope.launch {
                progress.animateTo(0f, OriginTransformCalculator.windowCloseSpring)
                afterAction()
            }
        }
    }

    Dialog(
        onDismissRequest = { if (canDismiss) closeAndRun(onDismissRequest) },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogScope = remember(closeAndRun) { VelvetDialogScope(closeAndRun) }
        VelvetDialogOverlayShell(
            origin = origin,
            progress = progress.value,
            isExiting = isClosing,
            canDismiss = canDismiss,
            onRequestClose = { closeAndRun(onDismissRequest) },
        ) {
            dialogScope.content()
        }
    }
}

/** Nullable-state dialog host so even external dismissal animates out cleanly before unmounting. */
@Composable
fun <T> VelvetDialogHost(
    target: T?,
    onDismissRequest: () -> Unit,
    canDismiss: Boolean = true,
    content: @Composable VelvetDialogScope.(T) -> Unit,
) {
    val registry = LocalOriginRegistry.current
    var active by remember { mutableStateOf(target) }
    var activeOrigin by remember { mutableStateOf<NavOrigin?>(null) }
    var isExiting by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(target) {
        if (target != null) {
            if (active == null) {
                activeOrigin = registry?.consumeBestOrigin()
            }
            active = target
            isExiting = false
            progress.animateTo(1f, OriginTransformCalculator.windowOpenSpring)
        } else if (active != null) {
            isExiting = true
            progress.animateTo(0f, OriginTransformCalculator.windowCloseSpring)
            active = null
            activeOrigin = null
        }
    }

    val current = active ?: return
    val closeAndRun: (() -> Unit) -> Unit = { afterAction ->
        afterAction()
        onDismissRequest()
    }

    Dialog(
        onDismissRequest = { if (canDismiss) onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogScope = remember(onDismissRequest) { VelvetDialogScope(closeAndRun) }
        VelvetDialogOverlayShell(
            origin = activeOrigin,
            progress = progress.value,
            isExiting = isExiting,
            canDismiss = canDismiss,
            onRequestClose = onDismissRequest,
        ) {
            dialogScope.content(current)
        }
    }
}

@Composable
internal fun VelvetAlertDialogCard(
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = VelvetPopupMotion.DialogShape,
        color = velvetColors.surface,
        contentColor = velvetColors.text,
        border = BorderStroke(1.dp, velvetColors.rule),
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
        ) {
            title()
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f, fill = false)) {
                text()
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

@Composable
fun VelvetAlertDialog(
    onDismissRequest: () -> Unit,
    canDismiss: Boolean = true,
    title: @Composable () -> Unit,
    text: @Composable (VelvetDialogScope) -> Unit,
    confirmButton: @Composable (VelvetDialogScope) -> Unit,
    dismissButton: (@Composable (VelvetDialogScope) -> Unit)? = null,
) {
    VelvetAnimatedDialog(
        onDismissRequest = onDismissRequest,
        canDismiss = canDismiss,
    ) {
        val dialog = this
        VelvetAlertDialogCard(
            title = title,
            text = { text(dialog) },
            confirmButton = { confirmButton(dialog) },
            dismissButton = dismissButton?.let { btn -> { btn(dialog) } },
        )
    }
}

/** Smooth Material 3 bottom sheet with fluid close helper when dismissing via internal actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VelvetModalSheet(
    onDismissRequest: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.(VelvetSheetScope) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    val scope = rememberCoroutineScope()
    var hiding by remember { mutableStateOf(false) }

    val hideAndRun: (() -> Unit) -> Unit = { afterHide ->
        if (!hiding) {
            hiding = true
            scope.launch {
                try {
                    sheetState.hide()
                } finally {
                    afterHide()
                    onDismissRequest()
                }
            }
        }
    }
    val sheetScope = remember { VelvetSheetScope(hideAndRun) }
    val registry = LocalOriginRegistry.current
    val origin = remember { registry?.consumeBestOrigin() }
    var sheetW by remember { mutableFloatStateOf(0f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, VelvetPopupMotion.enterSpring)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = VelvetPopupMotion.SheetShape,
        containerColor = velvetColors.surface,
        tonalElevation = 0.dp,
        scrimColor = Color(0x99000000),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(velvetColors.muted.copy(alpha = 0.34f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { sheetW = it.width.toFloat() }
                .graphicsLayer {
                    val p = reveal.value.coerceIn(0f, 1.04f)
                    val clamped = p.coerceIn(0f, 1f)
                    alpha = 0.35f + 0.65f * clamped
                    translationY = (1f - p) * 18.dp.toPx()
                    if (origin != null && origin.isValid && sheetW > 0f) {
                        translationX = (origin.centerX - sheetW * 0.5f) * 0.08f * (1f - clamped)
                    }
                    val s = 0.96f + 0.04f * p
                    scaleX = s
                    scaleY = s
                },
        ) {
            content(sheetScope)
        }
    }
}

@Composable
fun VelvetDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = VelvetPopupMotion.MenuShape,
        containerColor = velvetColors.raised,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, velvetColors.glassBorder),
        content = content,
    )
}

/** A destructive action waiting for the user to confirm it. */
class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val action: () -> Unit,
)

/** Asks before anything is deleted; nothing happens unless the user taps the red button. */
@Composable
fun ConfirmDialog(request: ConfirmRequest?, onDismiss: () -> Unit) {
    VelvetDialogHost(target = request, onDismissRequest = onDismiss) { active ->
        VelvetAlertDialogCard(
            title = { Text(active.title, style = MaterialTheme.typography.headlineSmall) },
            text = { Text(active.message, style = MaterialTheme.typography.bodyLarge, color = velvetColors.muted) },
            confirmButton = {
                TextButton(onClick = { active.action(); onDismiss() }) {
                    Text(active.confirmLabel, color = velvetColors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = velvetColors.text)
                }
            },
        )
    }
}
