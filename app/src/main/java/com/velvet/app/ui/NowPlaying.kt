package com.velvet.app.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.IconButton
import kotlin.math.roundToInt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.velvet.app.playback.QueueItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import com.velvet.app.playback.ShuffleMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.velvet.app.VelvetApp
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.Lyrics
import com.velvet.app.data.Song
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.formatDuration
import com.velvet.app.playback.SleepTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Polls the player position while something on screen shows it. */
@Composable
private fun rememberPosition(intervalMs: Long = 500): Long {
    var position by remember { mutableLongStateOf(PlayerConnection.positionMs()) }
    LaunchedEffect(PlayerConnection.controller, intervalMs) {
        while (true) {
            position = PlayerConnection.positionMs()
            delay(intervalMs)
        }
    }
    return position
}

/** Horizontal swipe skips tracks: left = next, right = previous. */
private fun Modifier.swipeToSkip(offset: () -> Float, onOffset: (Float) -> Unit): Modifier =
    pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragEnd = {
                val dx = offset()
                if (dx < -SWIPE_THRESHOLD) PlayerConnection.next() else if (dx > SWIPE_THRESHOLD) PlayerConnection.previous()
                onOffset(0f)
            },
            onDragCancel = { onOffset(0f) },
        ) { change, amount ->
            change.consume()
            onOffset(offset() + amount)
        }
    }

private const val SWIPE_THRESHOLD = 140f

// ------------------------------------------------------------------ mini player

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val song = PlayerConnection.current ?: return
    val position = rememberPosition()
    val duration = PlayerConnection.durationMs
    var dragX by remember { mutableFloatStateOf(0f) }
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val isDark = velvetColors.surface.luminance() < 0.5f
    val artColor = rememberArtColor(song.thumb, fallback = velvetColors.accent)

    val baseBg = velvetColors.raised
    val gradientBrush = remember(artColor, isDark, baseBg) {
        val subtleAlpha = if (isDark) 0.28f else 0.16f
        Brush.horizontalGradient(
            colors = listOf(
                artColor.copy(alpha = subtleAlpha),
                baseBg,
                baseBg,
            )
        )
    }

    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(baseBg)
            .background(gradientBrush)
            .border(1.dp, velvetColors.glassBorder, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Open now playing" }
            .swipeToSkip({ dragX }, { dragX = it }),
    ) {
        Row(
            Modifier
                .padding(start = 10.dp, top = 9.dp, bottom = 7.dp, end = 10.dp)
                .graphicsLayer { translationX = dragX / 2 },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(song.thumb, 42.dp, corner = 8.dp)
            Crossfade(
                targetState = song,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                animationSpec = tween(160),
                label = "miniPlayerTrack",
            ) {
                Column {
                    Text(it.title, style = MaterialTheme.typography.titleSmall, color = velvetColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.artist, style = MaterialTheme.typography.bodySmall, color = velvetColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            DockButton(AppIcons.SkipPrevious, "Previous track", iconSize = 22, tint = velvetColors.muted, onClick = PlayerConnection::previous)
            PlayPauseButton(size = 48)
            DockButton(AppIcons.SkipNext, "Next track", iconSize = 22, tint = velvetColors.muted, onClick = PlayerConnection::next)
        }
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 6.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(velvetColors.muted.copy(alpha = 0.2f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(velvetColors.accent, artColor))),
            )
        }
    }
}

/** Shows a spinner while the stream buffers. */
@Composable
private fun PlayPauseButton(size: Int) {
    AccentCircleButton(
        if (PlayerConnection.isPlaying) AppIcons.Pause else AppIcons.Play,
        if (PlayerConnection.isPlaying) "Pause" else "Play",
        size = size.dp,
        content = if (PlayerConnection.isBuffering) {
            { CircularProgressIndicator(Modifier.size((size * 0.42f).dp), color = velvetColors.onAccent, strokeWidth = 2.5.dp) }
        } else null,
        onClick = PlayerConnection::togglePlay,
    )
}

// ------------------------------------------------------------------ now playing

private sealed interface LyricsState {
    /** Automatic lookup is off in Settings; nothing is sent until the user taps. */
    data object NotRequested : LyricsState
    data object Loading : LyricsState
    data object Missing : LyricsState
    /** LRCLIB couldn't be reached; tapping retries. */
    data object Failed : LyricsState
    class Found(val result: Lyrics.Result) : LyricsState
}

// Compose lint misses the producer's value assignments with this Kotlin toolchain.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberLyrics(song: Song, automatic: Boolean, attempt: Int): LyricsState =
    produceState<LyricsState>(LyricsState.Loading, song.id, automatic, attempt) {
        if (!automatic && attempt == 0) {
            value = LyricsState.NotRequested
            return@produceState
        }
        value = LyricsState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                Lyrics.find(VelvetApp.http, song)?.let { LyricsState.Found(it) } ?: LyricsState.Missing
            } catch (e: Exception) {
                LyricsState.Failed
            }
        }
    }.value

/** "OPUS 160K", "OPUS 50K", or "OFFLINE" for downloads; fast-updating stream quality tracker. */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberQuality(song: Song, hiRes: Boolean): String {
    val initial = remember(song.id, hiRes) {
        if (VelvetApp.downloads.fileFor(song.id) != null) "OFFLINE"
        else StreamResolver.peek(song.id)?.quality ?: if (hiRes) "OPUS 160K" else "OPUS 50K"
    }
    return produceState(initialValue = initial, song.id, hiRes) {
        repeat(40) {
            if (VelvetApp.downloads.fileFor(song.id) != null) {
                value = "OFFLINE"
                return@produceState
            }
            StreamResolver.peek(song.id)?.let {
                value = it.quality
                return@produceState
            }
            delay(150)
        }
    }.value
}

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val song = PlayerConnection.current
    if (song == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val nav = LocalNav.current
    val context = LocalContext.current
    val openMenu = LocalSongMenu.current
    val likedIds by VelvetApp.library.likedIds.collectAsStateWithLifecycle()
    val sleep by SleepTimer.state.collectAsStateWithLifecycle()
    val liked = song.id in likedIds
    val settings by VelvetApp.library.data.collectAsStateWithLifecycle()
    val hiRes = !settings.dataSaver
    val ultra = settings.eq.ultra
    val quality = rememberQuality(song, hiRes)
    val position = rememberPosition(250)
    val duration = PlayerConnection.durationMs
    var lyricsAttempt by remember(song.id) { mutableIntStateOf(0) }
    val lyrics = key(song.id) { rememberLyrics(song, settings.autoLyrics, lyricsAttempt) }
    val requestLyrics: () -> Unit = { lyricsAttempt++ }

    var seekPreview by remember(song.id) { mutableStateOf<Float?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var artDrag by remember { mutableFloatStateOf(0f) }
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val openArtist: (() -> Unit)? = song.artistUrl?.let { url ->
        { nav.open(Route.Artist(ArtistRef(url, song.artist))) }
    }

    // this overlay sits outside the Scaffold, so set the content color it would have provided
    CompositionLocalProvider(LocalContentColor provides velvetColors.text) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // swallow taps so they don't reach the screen underneath
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            PaperBackground()
            val accent = velvetColors.accent
            val isDark = velvetColors.surface.luminance() < 0.5f
            val artColor = rememberArtColor(song.thumb, fallback = accent)
            val isPlaying = PlayerConnection.isPlaying

            // 1. Dynamic Breathing Gradient driven by Cover Art Palette (artColor)
            val breathTransition = rememberInfiniteTransition(label = "npBreathTransition")
            val breathPulse by if (isPlaying) {
                breathTransition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "artBreathPulse",
                )
            } else {
                remember { mutableFloatStateOf(1.0f) }
            }

            val breathAlpha = (if (isDark) 0.36f else 0.20f) * breathPulse

            // Dynamic top-to-bottom cover art accent gradient with breathing motion
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = if (isDark) {
                                listOf(
                                    artColor.copy(alpha = (breathAlpha * 1.15f).coerceIn(0.12f, 0.55f)),
                                    artColor.copy(alpha = (breathAlpha * 0.65f).coerceIn(0.06f, 0.35f)),
                                    artColor.copy(alpha = (breathAlpha * 0.25f).coerceIn(0.02f, 0.15f)),
                                    Color.Transparent,
                                    Color.Transparent,
                                )
                            } else {
                                listOf(
                                    artColor.copy(alpha = (breathAlpha * 0.85f).coerceIn(0.06f, 0.26f)),
                                    artColor.copy(alpha = (breathAlpha * 0.40f).coerceIn(0.03f, 0.14f)),
                                    Color.Transparent,
                                    Color.Transparent,
                                )
                            },
                            startY = 0f,
                            endY = 1600f,
                        )
                    )
            )

            // Soft radial ambient bloom directly behind artwork area
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(480.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                artColor.copy(alpha = (breathAlpha * 0.90f).coerceIn(0.08f, 0.45f)),
                                artColor.copy(alpha = (breathAlpha * 0.35f).coerceIn(0.03f, 0.20f)),
                                Color.Transparent,
                            ),
                            radius = 700f * breathPulse,
                        )
                    )
            )

            val gap = if (maxHeight < 720.dp) 6.dp else 10.dp
            val bottomPadding = if (maxHeight < 720.dp) 14.dp else 22.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top Bar - Mathematically Centered
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    // Left: Close Button
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color(0x24FFFFFF) else velvetColors.surface)
                            .border(1.dp, if (isDark) Color(0x1AFFFFFF) else velvetColors.glassBorder, CircleShape)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Close",
                            tint = velvetColors.text,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    val radioSubtitle = remember(PlayerConnection.isRadioActive, PlayerConnection.radioSeedSong, song) {
                        if (PlayerConnection.isRadioActive) {
                            (PlayerConnection.radioSeedSong?.title ?: song.title).trim()
                        } else null
                    }

                    // Center: "Now Playing" + Radio Subtitle + Theme Accent Indicator
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "Now Playing",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = if (radioSubtitle != null) 14.sp else 16.sp,
                            ),
                            color = velvetColors.text,
                            maxLines = 1,
                        )
                        if (radioSubtitle != null) {
                            Text(
                                text = "$radioSubtitle Radio",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .width(if (radioSubtitle != null) 30.dp else 36.dp)
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(accent),
                        )
                    }

                    // Right: Lyrics & MoreVert Menu
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0x24FFFFFF) else velvetColors.surface)
                                .border(1.dp, if (isDark) Color(0x1AFFFFFF) else velvetColors.glassBorder, CircleShape)
                            .clickable {
                                showLyrics = true
                                if (lyrics == LyricsState.NotRequested || lyrics == LyricsState.Failed) requestLyrics()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) accent else velvetColors.text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color(0x24FFFFFF) else velvetColors.surface)
                            .border(1.dp, if (isDark) Color(0x1AFFFFFF) else velvetColors.glassBorder, CircleShape)
                            .clickable { openMenu(SongMenuRequest(song, null)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = velvetColors.text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

                Spacer(Modifier.height(gap))

                // Artwork with Fuller & Edge-Blended Style + floating Share button directly below 3-dot icon
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val artworkSize = minOf(maxWidth - 2.dp, maxHeight, 345.dp)
                    if (artworkSize > 16.dp) {
                        val artworkCorner = 24.dp
                        Box(
                            modifier = Modifier
                                .size(artworkSize)
                                .graphicsLayer {
                                    translationX = artDrag
                                    alpha = 1f - (abs(artDrag) / 900f).coerceAtMost(0.5f)
                                }
                                .swipeToSkip({ artDrag }, { artDrag = it }),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Ambient bloom behind artwork
                            Box(
                                Modifier
                                    .size(artworkSize + 22.dp)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                artColor.copy(alpha = 0.45f),
                                                artColor.copy(alpha = 0.12f),
                                                Color.Transparent,
                                            )
                                        )
                                    )
                            )

                            // Main artwork container with soft corner rounding and deep shadow
                            Box(
                                Modifier
                                    .size(artworkSize)
                                    .shadow(
                                        elevation = 24.dp,
                                        shape = RoundedCornerShape(artworkCorner),
                                        spotColor = artColor.copy(alpha = 0.60f),
                                        ambientColor = artColor.copy(alpha = 0.35f),
                                    )
                                    .clip(RoundedCornerShape(artworkCorner)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Artwork(song.thumb, artworkSize, corner = artworkCorner)

                                // Soft bottom gradient blend into background
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = if (isDark) {
                                                    listOf(
                                                        Color.Transparent,
                                                        Color.Transparent,
                                                        Color(0x330A0C0E),
                                                        Color(0x880A0C0E),
                                                        Color(0xDD0A0C0E),
                                                    )
                                                } else {
                                                    listOf(
                                                        Color.Transparent,
                                                        Color.Transparent,
                                                        Color(0x10000000),
                                                        Color(0x30000000),
                                                        Color(0x55000000),
                                                    )
                                                }
                                            )
                                        )
                                )

                                // Edge blend vignette
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.radialGradient(
                                                colors = if (isDark) {
                                                    listOf(
                                                        Color.Transparent,
                                                        Color.Transparent,
                                                        Color(0x440A0C0E),
                                                        Color(0x880A0C0E),
                                                    )
                                                } else {
                                                    listOf(
                                                        Color.Transparent,
                                                        Color.Transparent,
                                                        Color(0x18000000),
                                                        Color(0x40000000),
                                                    )
                                                }
                                            )
                                        )
                                )
                            }

                            // Floating Share Button directly positioned underneath top bar's 3-dot icon
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 10.dp, end = 6.dp)
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) Color(0x66000000) else velvetColors.surface.copy(alpha = 0.92f))
                                    .border(1.dp, if (isDark) Color(0x33FFFFFF) else velvetColors.glassBorder, CircleShape)
                                    .clickable { shareSong(context, song) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = if (isDark) Color.White else velvetColors.text,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }

                // Pulse Wave Visualizer (Positioned BETWEEN Cover Art and Song Title)
                if (settings.showPulseWave) {
                    Spacer(Modifier.height(gap))
                    AudioWaveformVisualizer(
                        isPlaying = isPlaying,
                        accent = accent,
                        style = settings.pulseVisualizerStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    )
                }

                Spacer(Modifier.height(gap))

                // Track Info & Favorite Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                            ),
                            color = velvetColors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                            ),
                            color = velvetColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (openArtist != null) Modifier.clickable(onClick = openArtist) else Modifier,
                        )
                    }

                    // Heart/Favorite inside circular button with glowing theme accent border
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(velvetColors.surface)
                            .border(1.5.dp, accent, CircleShape)
                            .clickable { VelvetApp.library.toggleLike(song) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (liked) "Unlike" else "Like",
                            tint = accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.height(gap))

                // Dynamic Seekbar & Timers (with Plain Text Streaming Resolution Tag in Center)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) {
                    WavySeekBar(
                        songId = song.id,
                        progress = progress,
                        isPlaying = isPlaying,
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        playedColor = accent,
                        playheadColor = accent,
                        unplayedColor = accent.copy(alpha = 0.22f),
                        style = settings.seekbarStyle,
                        onSeek = { f -> if (duration > 0) PlayerConnection.seekTo((f * duration).toLong()) },
                        onSeekPreview = { seekPreview = it },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Elapsed Time (left)
                        Text(
                            formatDuration((seekPreview?.let { (it * duration).toLong() } ?: position) / 1000),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.sp,
                            ),
                            color = velvetColors.muted,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )

                        // Streaming resolution tag: locked to true center with absolute zero horizontal jitter
                        Text(
                            quality.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                            ),
                            color = accent,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clickable { PlayerConnection.setHiRes(!hiRes) },
                        )

                        // Duration (right)
                        Text(
                            formatDuration(duration / 1000),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 12.sp,
                            ),
                            color = velvetColors.muted,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }

                Spacer(Modifier.height(gap))

                // Playback Controls Dock (Clicky, Spring-Responsive & Accent Themed)
                ModernControlDock(accent = accent)

                Spacer(Modifier.height(gap))

                // Bottom 3 Action Cards (ULT Bass, Queue, Sleep Timer)
                PlayerBottomCards(
                    ultra = ultra,
                    sleep = sleep,
                    accent = accent,
                    onUltraToggle = { VelvetApp.library.updateEq { it.copy(ultra = !it.ultra) } },
                    onQueue = { showQueue = true },
                    onSleep = { showSleep = true },
                )
            }

            // Back closes the full-screen lyrics before it closes Now Playing
            BackHandler(enabled = showLyrics) { showLyrics = false }
            AnimatedVisibility(showLyrics, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                FullLyrics(lyrics, position, onRequest = requestLyrics, onClose = { showLyrics = false })
            }

            if (showQueue) {
                QueueSheet(maxHeight = maxHeight) { showQueue = false }
            }
        }

        if (showSleep) SleepSheet { showSleep = false }
    }
}

@Composable
private fun AudioWaveformVisualizer(
    isPlaying: Boolean,
    accent: Color,
    style: String = "FLUID_WAVE",
    modifier: Modifier = Modifier,
) {
    // Cadence ~120 BPM fluid acoustic wave ripple (2400ms cycle)
    val beatTransition = rememberInfiniteTransition(label = "fluidBeat")
    val beatCycle by if (isPlaying) {
        beatTransition.animateFloat(
            initialValue = 0f,
            targetValue = (4 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "beatCycle",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    // Toned down theme accent color for refined luminous acoustic aesthetic
    val tonedAccent = remember(accent) { accent.copy(alpha = 0.85f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft warm luminous ambient glow behind center waves
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(38.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.30f),
                            accent.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        radius = 280f,
                    ),
                ),
        )

        Canvas(modifier = Modifier.fillMaxWidth().height(38.dp)) {
            val width = size.width
            val height = size.height
            val midY = height / 2f

            when (style) {
                "BEAT_BARS" -> {
                    // Modern Studio Symmetrical Equalizer Spectrum Bars (PowerAmp aesthetic)
                    val barCount = 38
                    val spacing = 3.dp.toPx()
                    val totalSpacing = (barCount - 1) * spacing
                    val barWidth = ((width * 0.88f - totalSpacing) / barCount).coerceIn(2.5f, 6f)
                    val startX = (width - (barCount * barWidth + totalSpacing)) / 2f

                    val bounce = if (isPlaying) {
                        0.60f + 0.40f * kotlin.math.abs(kotlin.math.sin(beatCycle.toDouble())).toFloat()
                    } else {
                        0.35f
                    }

                    for (i in 0 until barCount) {
                        val bx = startX + i * (barWidth + spacing)
                        val u = (((bx + barWidth / 2f) - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                        val envelope = (kotlin.math.exp(-2.6 * u * u) * (1.0 - 0.7 * u * u)).toFloat().coerceAtLeast(0.08f)

                        val freqPhase1 = beatCycle * 1.6 + i * 0.42
                        val freqPhase2 = beatCycle * 0.85 + i * 0.28
                        val rawFreq = (kotlin.math.sin(freqPhase1) * 0.55 + kotlin.math.cos(freqPhase2) * 0.45).toFloat()
                        val activity = (0.25f + 0.75f * ((rawFreq + 1f) / 2f)).coerceIn(0.15f, 1f)

                        val barHeight = ((height * 0.78f) * envelope * bounce * activity).coerceIn(4f, height * 0.90f)
                        val top = midY - barHeight / 2f

                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    tonedAccent,
                                    tonedAccent.copy(alpha = 0.55f),
                                    tonedAccent,
                                ),
                                startY = top,
                                endY = top + barHeight,
                            ),
                            topLeft = Offset(bx, top),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                        )
                    }
                }

                "NEON_RIPPLE" -> {
                    // Concentric Expanding Radial Acoustic Ripples & Pulsing Sound Particle Sparks
                    val centerX = width / 2f
                    val centerY = midY
                    val bounce = if (isPlaying) {
                        0.60f + 0.40f * kotlin.math.abs(kotlin.math.sin(beatCycle.toDouble())).toFloat()
                    } else {
                        0.35f
                    }

                    // 5 Concentric expanding elliptical sonic ripples radiating outward from center
                    val ringCount = 5
                    for (r in 0 until ringCount) {
                        val ringProgress = if (isPlaying) {
                            ((beatCycle / (4 * Math.PI.toFloat()) * 2f + r * (1f / ringCount)) % 1f)
                        } else {
                            (r + 1f) / (ringCount + 1f)
                        }
                        val ringW = (width * 0.42f) * ringProgress
                        val ringH = (height * 0.40f) * ringProgress * bounce
                        val ringAlpha = ((1f - ringProgress) * 0.85f * bounce).coerceIn(0.02f, 0.90f)
                        val strokeW = (2.4f * (1f - ringProgress * 0.5f)).dp.toPx()

                        drawOval(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    tonedAccent.copy(alpha = ringAlpha * 0.15f),
                                    tonedAccent.copy(alpha = ringAlpha),
                                    tonedAccent.copy(alpha = 0f),
                                ),
                                center = Offset(centerX, centerY),
                                radius = (ringW + 8.dp.toPx()).coerceAtLeast(10f),
                            ),
                            topLeft = Offset(centerX - ringW, centerY - ringH),
                            size = Size(ringW * 2f, ringH * 2f),
                            style = Stroke(width = strokeW),
                        )
                    }

                    // Radiating audio acoustic nodes along bilateral horizontal sonic rays
                    val particleCount = 14
                    for (p in 0 until particleCount) {
                        val side = if (p % 2 == 0) 1f else -1f
                        val pIdx = p / 2
                        val pNorm = (pIdx + 1f) / (particleCount / 2f + 1f)
                        val px = centerX + side * (width * 0.44f * pNorm)
                        val particlePulse = if (isPlaying) {
                            0.45f + 0.55f * kotlin.math.abs(kotlin.math.sin(beatCycle * 1.5 + pIdx * 0.7)).toFloat()
                        } else {
                            0.4f
                        }
                        val pEnv = (1f - pNorm * 0.7f) * bounce
                        val pAlpha = (pEnv * particlePulse).coerceIn(0.05f, 0.95f)
                        val py = centerY + (kotlin.math.sin(beatCycle + pIdx).toFloat() * 2.5.dp.toPx() * bounce)

                        drawCircle(
                            color = tonedAccent.copy(alpha = pAlpha),
                            radius = (1.5.dp.toPx() + 1.2.dp.toPx() * particlePulse),
                            center = Offset(px, py),
                        )
                    }

                    // Radiant central sonic core emitter
                    val corePulse = if (isPlaying) {
                        0.75f + 0.25f * kotlin.math.abs(kotlin.math.sin(beatCycle * 2.0)).toFloat()
                    } else {
                        0.60f
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.95f),
                                tonedAccent,
                                tonedAccent.copy(alpha = 0.25f),
                                Color.Transparent,
                            ),
                            center = Offset(centerX, centerY),
                            radius = 12.dp.toPx() * corePulse,
                        ),
                        radius = 8.dp.toPx() * corePulse,
                        center = Offset(centerX, centerY),
                    )
                }

                "MINIMAL_CREST" -> {
                    // Sleek Precision Acoustic Needle / Crest Profile
                    val crestPath = Path()
                    val bounce = if (isPlaying) {
                        0.60f + 0.40f * kotlin.math.abs(kotlin.math.sin(beatCycle.toDouble())).toFloat()
                    } else {
                        0.35f
                    }
                    val maxAmp = (height * 0.44f) * bounce
                    val stepPx = 2.5f
                    val count = (width / stepPx).toInt() + 1

                    crestPath.moveTo(0f, midY)
                    for (i in 0..count) {
                        val x = (i * stepPx).coerceAtMost(width)
                        val u = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                        val envelope = (kotlin.math.exp(-4.2 * u * u) * (1.0 - u * u)).toFloat().coerceAtLeast(0f)
                        val phase = (4.0 * Math.PI * (x / width) - beatCycle * 1.5).toDouble()
                        val wave = (kotlin.math.sin(phase) * 0.85 + kotlin.math.cos(phase * 2.1) * 0.15).toFloat()
                        val y = midY - maxAmp * envelope * wave
                        crestPath.lineTo(x, y)
                    }

                    // Tapered razor-sharp acoustic line
                    drawPath(
                        path = crestPath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                tonedAccent.copy(alpha = 0.20f),
                                tonedAccent,
                                tonedAccent.copy(alpha = 0.20f),
                                Color.Transparent,
                            ),
                        ),
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    // Luminous center pulse point
                    val centerPulse = if (isPlaying) (0.7f + 0.3f * kotlin.math.abs(kotlin.math.sin(beatCycle * 1.5)).toFloat()) else 0.6f
                    drawCircle(
                        color = tonedAccent.copy(alpha = 0.95f),
                        radius = (3.2.dp.toPx()) * centerPulse,
                        center = Offset(width / 2f, midY),
                    )
                    drawCircle(
                        color = tonedAccent.copy(alpha = 0.25f),
                        radius = (8.dp.toPx()) * centerPulse,
                        center = Offset(width / 2f, midY),
                    )
                }

                else -> {
                    // "FLUID_WAVE" (Default Silky Traveling Harmonic Wave)
                    val primaryFillPath = Path()
                    val primaryStrokePath = Path()
                    val secondaryFillPath = Path()
                    val secondaryStrokePath = Path()

                    val stepPx = 4f
                    val count = (width / stepPx).toInt() + 1

                    val bounce = if (isPlaying) {
                        0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(beatCycle.toDouble())).toFloat()
                    } else {
                        0.35f
                    }
                    val maxAmp = (height * 0.42f) * bounce

                    primaryFillPath.moveTo(0f, midY)
                    secondaryFillPath.moveTo(0f, midY)

                    for (i in 0..count) {
                        val x = (i * stepPx).coerceAtMost(width)
                        val u = ((x - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                        val envelope = (kotlin.math.exp(-3.2 * u * u) * (1.0 - u * u)).toFloat().coerceAtLeast(0f)

                        val phase1 = (3.5 * Math.PI * (x / width) - beatCycle * 1.2).toDouble()
                        val phase2 = (5.2 * Math.PI * (x / width) + beatCycle * 0.9 + 1.2).toDouble()
                        val phase3 = (2.4 * Math.PI * (x / width) - beatCycle * 0.6 + 2.4).toDouble()

                        val wave1 = (kotlin.math.sin(phase1) * 0.72 + kotlin.math.cos(phase2) * 0.28).toFloat()
                        val y1 = midY - maxAmp * envelope * wave1

                        val wave2 = (kotlin.math.sin(phase2) * 0.60 - kotlin.math.cos(phase3) * 0.40).toFloat()
                        val y2 = midY + (maxAmp * 0.75f) * envelope * wave2

                        if (i == 0) {
                            primaryStrokePath.moveTo(x, y1)
                            secondaryStrokePath.moveTo(x, y2)
                        } else {
                            primaryStrokePath.lineTo(x, y1)
                            secondaryStrokePath.lineTo(x, y2)
                        }
                        primaryFillPath.lineTo(x, y1)
                        secondaryFillPath.lineTo(x, y2)
                    }

                    primaryFillPath.lineTo(width, midY)
                    primaryFillPath.close()

                    secondaryFillPath.lineTo(width, midY)
                    secondaryFillPath.close()

                    drawPath(
                        path = primaryFillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(tonedAccent.copy(alpha = 0.22f), tonedAccent.copy(alpha = 0.02f)),
                            startY = 0f,
                            endY = midY,
                        ),
                    )
                    drawPath(
                        path = secondaryFillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(tonedAccent.copy(alpha = 0.02f), tonedAccent.copy(alpha = 0.16f)),
                            startY = midY,
                            endY = height,
                        ),
                    )

                    drawPath(
                        path = secondaryStrokePath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                tonedAccent.copy(alpha = 0.05f),
                                tonedAccent.copy(alpha = 0.60f),
                                tonedAccent.copy(alpha = 0.05f),
                            ),
                        ),
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawPath(
                        path = primaryStrokePath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                tonedAccent.copy(alpha = 0.10f),
                                tonedAccent,
                                tonedAccent.copy(alpha = 0.10f),
                            ),
                        ),
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    val filamentStep = 14.dp.toPx()
                    val filamentCount = (width / filamentStep).toInt()
                    for (j in 1 until filamentCount) {
                        val fx = j * filamentStep
                        val fu = ((fx - width / 2f) / (width / 2f)).coerceIn(-1f, 1f)
                        val fenvelope = (kotlin.math.exp(-3.2 * fu * fu) * (1.0 - fu * fu)).toFloat().coerceAtLeast(0f)
                        val fphase1 = (3.5 * Math.PI * (fx / width) - beatCycle * 1.2).toDouble()
                        val fwave1 = (kotlin.math.sin(fphase1) * 0.72).toFloat()
                        val fy1 = midY - maxAmp * fenvelope * fwave1

                        if (fenvelope > 0.05f) {
                            val dotAlpha = (fenvelope * 0.85f).coerceIn(0f, 1f)
                            drawCircle(
                                color = tonedAccent.copy(alpha = dotAlpha),
                                radius = 1.6.dp.toPx(),
                                center = Offset(fx, fy1),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernControlDock(accent: Color) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shuffleMode = PlayerConnection.shuffleMode
        val shuffleInteractionSource = remember { MutableInteractionSource() }
        val isShufflePressed by shuffleInteractionSource.collectIsPressedAsState()
        val shuffleScale by animateFloatAsState(
            targetValue = if (isShufflePressed) 0.88f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "shuffleScale",
        )

        Box(
            modifier = Modifier
                .scale(shuffleScale)
                .size(46.dp)
                .clip(CircleShape)
                .background(velvetColors.surface)
                .border(1.dp, if (shuffleMode != ShuffleMode.OFF) accent else velvetColors.glassBorder, CircleShape)
                .clickable(
                    interactionSource = shuffleInteractionSource,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    PlayerConnection.cycleShuffle(context)
                },
            contentAlignment = Alignment.Center,
        ) {
            when (shuffleMode) {
                ShuffleMode.OFF -> {
                    Icon(
                        AppIcons.Shuffle,
                        contentDescription = "Shuffle off",
                        tint = velvetColors.muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
                ShuffleMode.STANDARD -> {
                    Icon(
                        AppIcons.Shuffle,
                        contentDescription = "Shuffle on",
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                ShuffleMode.SMART -> {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            AppIcons.Shuffle,
                            contentDescription = "Smart Shuffle on",
                            tint = accent,
                            modifier = Modifier.size(22.dp),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(9.dp)
                                .background(accent, CircleShape),
                        )
                    }
                }
            }
        }

        val prevInteractionSource = remember { MutableInteractionSource() }
        val isPrevPressed by prevInteractionSource.collectIsPressedAsState()
        val prevScale by animateFloatAsState(
            targetValue = if (isPrevPressed) 0.86f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "prevScale",
        )

        Box(
            modifier = Modifier
                .scale(prevScale)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = prevInteractionSource,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    PlayerConnection.previous()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.SkipPrevious,
                contentDescription = "Previous",
                tint = velvetColors.text,
                modifier = Modifier.size(30.dp),
            )
        }

        // Tactile Springy & Clicky Play/Pause Button
        val playPauseInteractionSource = remember { MutableInteractionSource() }
        val isPressed by playPauseInteractionSource.collectIsPressedAsState()
        val playPauseScale by animateFloatAsState(
            targetValue = if (isPressed) 0.86f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "playPauseScale",
        )
        val playPauseIconColor = if (accent.luminance() > 0.55f) Color(0xFF09140D) else Color.White

        Box(
            modifier = Modifier
                .scale(playPauseScale)
                .size(68.dp)
                .shadow(
                    elevation = if (isPressed) 8.dp else 18.dp,
                    shape = CircleShape,
                    spotColor = accent.copy(alpha = 0.65f),
                    ambientColor = accent.copy(alpha = 0.45f),
                )
                .clip(CircleShape)
                .background(accent)
                .clickable(
                    interactionSource = playPauseInteractionSource,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } catch (_: Exception) {}
                    PlayerConnection.togglePlay()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (PlayerConnection.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = playPauseIconColor,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    if (PlayerConnection.isPlaying) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (PlayerConnection.isPlaying) "Pause" else "Play",
                    tint = playPauseIconColor,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        val nextInteractionSource = remember { MutableInteractionSource() }
        val isNextPressed by nextInteractionSource.collectIsPressedAsState()
        val nextScale by animateFloatAsState(
            targetValue = if (isNextPressed) 0.86f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "nextScale",
        )

        Box(
            modifier = Modifier
                .scale(nextScale)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = nextInteractionSource,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    PlayerConnection.next()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.SkipNext,
                contentDescription = "Next",
                tint = velvetColors.text,
                modifier = Modifier.size(30.dp),
            )
        }

        val repeatMode = PlayerConnection.repeatMode
        val repeatInteractionSource = remember { MutableInteractionSource() }
        val isRepeatPressed by repeatInteractionSource.collectIsPressedAsState()
        val repeatScale by animateFloatAsState(
            targetValue = if (isRepeatPressed) 0.88f else 1.0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "repeatScale",
        )

        Box(
            modifier = Modifier
                .scale(repeatScale)
                .size(46.dp)
                .clip(CircleShape)
                .background(velvetColors.surface)
                .border(1.dp, if (repeatMode != Player.REPEAT_MODE_OFF) accent else velvetColors.glassBorder, CircleShape)
                .clickable(
                    interactionSource = repeatInteractionSource,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    PlayerConnection.cycleRepeat()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (repeatMode == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                contentDescription = "Repeat",
                tint = if (repeatMode == Player.REPEAT_MODE_OFF) velvetColors.muted else accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun PlayerBottomCards(
    ultra: Boolean,
    sleep: SleepTimer.State,
    accent: Color,
    onUltraToggle: () -> Unit,
    onQueue: () -> Unit,
    onSleep: () -> Unit,
) {
    val sleepActive = sleep != SleepTimer.State.Off
    val haptic = LocalHapticFeedback.current
    val isDark = velvetColors.surface.luminance() < 0.5f

    val ultraInteraction = remember { MutableInteractionSource() }
    val isUltraPressed by ultraInteraction.collectIsPressedAsState()
    val ultraScale by animateFloatAsState(
        targetValue = if (isUltraPressed) 0.92f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ultraScale",
    )

    val queueInteraction = remember { MutableInteractionSource() }
    val isQueuePressed by queueInteraction.collectIsPressedAsState()
    val queueScale by animateFloatAsState(
        targetValue = if (isQueuePressed) 0.92f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "queueScale",
    )

    val sleepInteraction = remember { MutableInteractionSource() }
    val isSleepPressed by sleepInteraction.collectIsPressedAsState()
    val sleepScale by animateFloatAsState(
        targetValue = if (isSleepPressed) 0.92f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "sleepScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Card 1: ULT Bass (Tactile spring bounce)
        Box(
            modifier = Modifier
                .weight(1f)
                .scale(ultraScale)
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .then(
                    if (ultra) {
                        Modifier
                            .background(Brush.verticalGradient(listOf(accent.copy(alpha = if (isDark) 0.22f else 0.14f), accent.copy(alpha = if (isDark) 0.08f else 0.04f))))
                            .border(1.5.dp, accent, RoundedCornerShape(16.dp))
                    } else {
                        Modifier
                            .background(velvetColors.surface)
                            .border(1.dp, velvetColors.glassBorder, RoundedCornerShape(16.dp))
                    }
                )
                .clickable(
                    interactionSource = ultraInteraction,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    onUltraToggle()
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    AppIcons.VolumeUp,
                    contentDescription = "ULT Bass",
                    tint = if (ultra) accent else velvetColors.muted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "ULT Bass",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    ),
                    color = if (ultra) (if (isDark) Color.White else accent) else velvetColors.muted,
                )
            }
        }

        // Card 2: Queue (Tactile spring bounce)
        Box(
            modifier = Modifier
                .weight(1f)
                .scale(queueScale)
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(velvetColors.surface)
                .border(1.dp, velvetColors.glassBorder, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = queueInteraction,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    onQueue()
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    AppIcons.QueueMusic,
                    contentDescription = "Queue",
                    tint = velvetColors.text,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Queue",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                    ),
                    color = velvetColors.text,
                )
            }
        }

        // Card 3: Sleep Timer (Tactile spring bounce)
        Box(
            modifier = Modifier
                .weight(1f)
                .scale(sleepScale)
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .then(
                    if (sleepActive) {
                        Modifier
                            .background(Brush.verticalGradient(listOf(accent.copy(alpha = if (isDark) 0.22f else 0.14f), accent.copy(alpha = if (isDark) 0.08f else 0.04f))))
                            .border(1.5.dp, accent, RoundedCornerShape(16.dp))
                    } else {
                        Modifier
                            .background(velvetColors.surface)
                            .border(1.dp, velvetColors.glassBorder, RoundedCornerShape(16.dp))
                    }
                )
                .clickable(
                    interactionSource = sleepInteraction,
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    onSleep()
                }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    AppIcons.Timer,
                    contentDescription = "Sleep Timer",
                    tint = if (sleepActive) accent else velvetColors.muted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (sleepActive) sleepLabel(sleep) else "Sleep Timer",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                    ),
                    color = if (sleepActive) (if (isDark) Color.White else accent) else velvetColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, iconSize: Int = 24, tint: Color = velvetColors.text, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}

@Composable
private fun TimeLabel(ms: Long) {
    Text(formatDuration(ms / 1000), style = MaterialTheme.typography.labelSmall, color = velvetColors.muted)
}

@Composable
private fun sleepLabel(state: SleepTimer.State): String = when (state) {
    is SleepTimer.State.At -> {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(state) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        "Sleep " + formatDuration(((state.endsAtMs - now) / 1000).coerceAtLeast(0))
    }
    SleepTimer.State.EndOfSong -> "Sleep: end of song"
    SleepTimer.State.Off -> "Sleep"
}

// ------------------------------------------------------------------ lyrics

private fun currentLine(lines: List<Lyrics.Line>, positionMs: Long): Int =
    lines.indexOfLast { it.timeMs <= positionMs + 300 }

@Composable
private fun FullLyrics(
    state: LyricsState,
    position: Long,
    onRequest: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        PaperBackground()
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PanelIconButton(Icons.Default.Close, "Close lyrics", onClick = onClose)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Lyrics", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        PlayerConnection.current?.title.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when (state) {
                LyricsState.Loading -> LoadingBox(Modifier.fillMaxSize())
                LyricsState.Missing -> EmptyHint("No lyrics found for this song.")
                LyricsState.Failed -> ErrorBox("Couldn't reach the lyrics service.", onRetry = onRequest)
                LyricsState.NotRequested -> TextButton(
                    onClick = onRequest,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text("Look up lyrics")
                }
                is LyricsState.Found -> LyricsText(state.result, position)
            }
        }
    }
}

@Composable
private fun LyricsText(result: Lyrics.Result, position: Long) {
    val listState = rememberLazyListState()
    val current = if (result.synced) currentLine(result.lines, position) else -1

    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }

    if (result.synced) {
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
            itemsIndexed(result.lines) { i, line ->
                val color by animateColorAsState(
                    if (i == current) velvetColors.accentText else velvetColors.muted,
                    tween(180),
                    label = "lyricHighlight",
                )
                Text(
                    line.text.ifBlank { "♪" },
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { PlayerConnection.seekTo(line.timeMs) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    } else {
        Text(
            result.plain,
            fontSize = 22.sp,
            lineHeight = 32.sp,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        )
    }
}

// ------------------------------------------------------------------ sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(onDismiss: () -> Unit) {
    val state by SleepTimer.state.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = velvetColors.surface,
        tonalElevation = 0.dp,
        scrimColor = Color(0x99000000),
    ) {
        Text("Sleep timer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
        listOf(5, 15, 30, 45, 60, 90, 120).forEach { minutes ->
            SheetOption("$minutes minutes") { SleepTimer.start(minutes); onDismiss() }
        }
        SheetOption("End of song") { SleepTimer.endOfSong(); onDismiss() }
        if (state != SleepTimer.State.Off) SheetOption("Turn off timer") { SleepTimer.cancel(); onDismiss() }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetOption(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueSheet(
    maxHeight: Dp,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val fullHeightPx = with(density) { maxHeight.toPx() }
    val peekHeightPx = fullHeightPx * 0.40f
    val expandedHeightPx = fullHeightPx * 0.90f

    val sheetHeightAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val dismissWithAnimation: () -> Unit = {
        coroutineScope.launch {
            sheetHeightAnim.animateTo(0f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        sheetHeightAnim.animateTo(
            targetValue = peekHeightPx,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    BackHandler(enabled = true) {
        if (sheetHeightAnim.value > peekHeightPx + 40f) {
            coroutineScope.launch {
                sheetHeightAnim.animateTo(
                    peekHeightPx,
                    spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                )
            }
        } else {
            dismissWithAnimation()
        }
    }

    val queueState = PlayerConnection.queueState
    val currentItem = queueState.current?.copy(qid = "current")
    val userQueue = queueState.userQueue
    val upNext = queueState.upNext
    val listState = rememberLazyListState()
    val openMenu = LocalSongMenu.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val isQueueLocked = PlayerConnection.isQueueLocked
    val libraryData by VelvetApp.library.data.collectAsStateWithLifecycle()
    val isDragReorderEnabled = libraryData.queueDragReorderEnabled

    var draggingQid by remember { mutableStateOf<String?>(null) }
    var draggingIsUserQueue by remember { mutableStateOf(false) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    var totalDragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragStartVersion by remember { mutableLongStateOf(-1L) }
    var previewUserQueue by remember { mutableStateOf<List<QueueItem>?>(null) }
    var previewUpNext by remember { mutableStateOf<List<QueueItem>?>(null) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    val haptic = LocalHapticFeedback.current

    val getItemHeightPx: () -> Float = {
        val measured = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == draggingQid }
            ?.size
            ?: listState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.key != "header_now_playing" && it.key != "header_up_next" && it.key != "header_next_in_queue"
            }?.size
        measured?.toFloat() ?: with(density) { 62.dp.toPx() }
    }

    // Safe discard if queue mutated underneath during drag
    LaunchedEffect(PlayerConnection.queueManager.queueVersion) {
        if (draggingQid != null && PlayerConnection.queueManager.queueVersion != dragStartVersion) {
            autoScrollJob?.cancel()
            autoScrollJob = null
            draggingQid = null
            dragStartIndex = -1
            dragCurrentIndex = -1
            totalDragOffsetY = 0f
            previewUserQueue = null
            previewUpNext = null
        }
    }

    val onDragStart: (String, Boolean) -> Unit = { qid, isUserQueue ->
        if (!isQueueLocked && isDragReorderEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            draggingQid = qid
            draggingIsUserQueue = isUserQueue
            totalDragOffsetY = 0f
            dragStartVersion = PlayerConnection.queueManager.queueVersion
            if (isUserQueue) {
                val idx = userQueue.indexOfFirst { it.qid == qid }
                dragStartIndex = idx
                dragCurrentIndex = idx
                previewUserQueue = userQueue.toList()
                previewUpNext = null
            } else {
                val idx = upNext.indexOfFirst { it.qid == qid }
                dragStartIndex = idx
                dragCurrentIndex = idx
                previewUpNext = upNext.toList()
                previewUserQueue = null
            }
        }
    }

    val onDragDelta: (Float) -> Unit = { deltaY ->
        if (draggingQid != null && dragStartIndex >= 0) {
            totalDragOffsetY += deltaY
            val qid = draggingQid
            val baseList = if (draggingIsUserQueue) userQueue else upNext
            val rowHeightPx = getItemHeightPx().coerceAtLeast(10f)

            // Hysteresis deadzone (0.55): prevents rapid jitter/thrashing when dragging near slot boundaries
            val rawDeltaSlots = (totalDragOffsetY - (dragCurrentIndex - dragStartIndex) * rowHeightPx) / rowHeightPx
            val targetIndex = when {
                rawDeltaSlots > 0.55f -> (dragCurrentIndex + 1).coerceIn(0, baseList.size - 1)
                rawDeltaSlots < -0.55f -> (dragCurrentIndex - 1).coerceIn(0, baseList.size - 1)
                else -> dragCurrentIndex
            }

            if (targetIndex != dragCurrentIndex) {
                dragCurrentIndex = targetIndex
                val mutable = baseList.toMutableList()
                val itemIdx = mutable.indexOfFirst { it.qid == qid }
                if (itemIdx >= 0) {
                    val item = mutable.removeAt(itemIdx)
                    mutable.add(targetIndex, item)
                    if (draggingIsUserQueue) {
                        previewUserQueue = mutable.toList()
                    } else {
                        previewUpNext = mutable.toList()
                    }
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                }
            }

            // Viewport-edge-based auto-scroll: auto-scroll activates ONLY when the dragged item is near the viewport top or bottom edge
            val edgeZonePx = with(density) { 56.dp.toPx() }
            val viewportHeight = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toFloat()
            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingQid }
            val itemTopInViewport = (itemInfo?.offset?.toFloat() ?: 0f) + (totalDragOffsetY - (dragCurrentIndex - dragStartIndex) * rowHeightPx)
            val itemBottomInViewport = itemTopInViewport + rowHeightPx

            val isNearTop = itemTopInViewport < edgeZonePx && listState.canScrollBackward
            val isNearBottom = itemBottomInViewport > (viewportHeight - edgeZonePx) && listState.canScrollForward

            if (isNearTop) {
                if (autoScrollJob == null || autoScrollJob?.isActive != true) {
                    autoScrollJob = coroutineScope.launch {
                        while (isActive && draggingQid != null && listState.canScrollBackward) {
                            val curInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingQid }
                            val curTop = (curInfo?.offset?.toFloat() ?: 0f) + (totalDragOffsetY - (dragCurrentIndex - dragStartIndex) * rowHeightPx)
                            if (curTop >= edgeZonePx) break
                            val speed = ((edgeZonePx - curTop) / edgeZonePx).coerceIn(0.25f, 1f) * 14f
                            listState.scrollBy(-speed)
                            delay(16)
                        }
                    }
                }
            } else if (isNearBottom) {
                if (autoScrollJob == null || autoScrollJob?.isActive != true) {
                    autoScrollJob = coroutineScope.launch {
                        while (isActive && draggingQid != null && listState.canScrollForward) {
                            val curInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingQid }
                            val curBottom = (curInfo?.offset?.toFloat() ?: 0f) + (totalDragOffsetY - (dragCurrentIndex - dragStartIndex) * rowHeightPx) + rowHeightPx
                            if (curBottom <= (viewportHeight - edgeZonePx)) break
                            val speed = ((curBottom - (viewportHeight - edgeZonePx)) / edgeZonePx).coerceIn(0.25f, 1f) * 14f
                            listState.scrollBy(speed)
                            delay(16)
                        }
                    }
                }
            } else {
                autoScrollJob?.cancel()
                autoScrollJob = null
            }
        }
    }

    val onDragEnd: () -> Unit = {
        autoScrollJob?.cancel()
        autoScrollJob = null
        val qid = draggingQid
        val targetIndex = dragCurrentIndex
        val startVer = dragStartVersion
        if (qid != null && targetIndex >= 0 && !isQueueLocked && PlayerConnection.queueManager.queueVersion == startVer) {
            PlayerConnection.reorder(qid, targetIndex)
        }
        draggingQid = null
        dragStartIndex = -1
        dragCurrentIndex = -1
        totalDragOffsetY = 0f
        previewUserQueue = null
        previewUpNext = null
    }

    val onDragCancel: () -> Unit = {
        autoScrollJob?.cancel()
        autoScrollJob = null
        draggingQid = null
        dragStartIndex = -1
        dragCurrentIndex = -1
        totalDragOffsetY = 0f
        previewUserQueue = null
        previewUpNext = null
    }

    val displayUserQueue = previewUserQueue ?: userQueue
    val displayUpNext = previewUpNext ?: upNext

    val nestedScrollConnection = remember(peekHeightPx, expandedHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val deltaY = available.y
                // Upward drag: if sheet is below 90%, expand sheet instead of scrolling list
                if (deltaY < 0 && sheetHeightAnim.value < expandedHeightPx - 1f) {
                    val newHeight = (sheetHeightAnim.value - deltaY).coerceAtMost(expandedHeightPx)
                    val consumed = newHeight - sheetHeightAnim.value
                    coroutineScope.launch { sheetHeightAnim.snapTo(newHeight) }
                    return Offset(0f, -consumed)
                }
                // Downward drag: if list is at top, collapse sheet instead of scrolling list
                if (deltaY > 0 && !listState.canScrollBackward && sheetHeightAnim.value > 0f) {
                    val newHeight = (sheetHeightAnim.value - deltaY).coerceAtLeast(0f)
                    val consumed = sheetHeightAnim.value - newHeight
                    coroutineScope.launch { sheetHeightAnim.snapTo(newHeight) }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val deltaY = available.y
                // Downward drag: if list is at top, collapse sheet
                if (deltaY > 0 && !listState.canScrollBackward) {
                    val newHeight = (sheetHeightAnim.value - deltaY).coerceAtLeast(0f)
                    val consumed = sheetHeightAnim.value - newHeight
                    coroutineScope.launch { sheetHeightAnim.snapTo(newHeight) }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val current = sheetHeightAnim.value
                val midPoint = (peekHeightPx + expandedHeightPx) / 2f
                val dismissThreshold = peekHeightPx * 0.5f

                if (available.y < -400f) {
                    sheetHeightAnim.animateTo(
                        expandedHeightPx,
                        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    )
                    return available
                } else if (available.y > 600f) {
                    if (current > peekHeightPx + 40f) {
                        sheetHeightAnim.animateTo(
                            peekHeightPx,
                            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                        )
                    } else {
                        dismissWithAnimation()
                    }
                    return available
                }

                if (current > midPoint) {
                    sheetHeightAnim.animateTo(
                        expandedHeightPx,
                        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    )
                } else if (current > dismissThreshold) {
                    sheetHeightAnim.animateTo(
                        peekHeightPx,
                        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    )
                } else {
                    dismissWithAnimation()
                }
                return Velocity.Zero
            }
        }
    }

    val currentHeightDp = with(density) { sheetHeightAnim.value.coerceAtLeast(0f).toDp() }
    val isDark = velvetColors.surface.luminance() < 0.5f
    val scrimProgress = (sheetHeightAnim.value / expandedHeightPx).coerceIn(0f, 1f)
    val scrimAlpha = scrimProgress * (if (isDark) 0.65f else 0.45f)

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Semi-transparent backdrop scrim (tapping dismisses queue)
        if (scrimAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = dismissWithAnimation,
                    ),
            )
        }

        // Bottom Sheet Surface anchored permanently to physical bottom ("ground")
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(currentHeightDp)
                .nestedScroll(nestedScrollConnection),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = velvetColors.surface,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                // Top Sheet Header: Entire area (pill + title + lock button + padding) is draggable and responsive to vertical flings
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                val newHeight = (sheetHeightAnim.value - delta).coerceIn(0f, expandedHeightPx)
                                coroutineScope.launch {
                                    sheetHeightAnim.snapTo(newHeight)
                                }
                            },
                            onDragStopped = { velocity ->
                                val current = sheetHeightAnim.value
                                val midPoint = (peekHeightPx + expandedHeightPx) / 2f
                                val dismissThreshold = peekHeightPx * 0.5f

                                if (velocity < -400f) {
                                    sheetHeightAnim.animateTo(
                                        expandedHeightPx,
                                        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                    )
                                } else if (velocity > 600f) {
                                    if (current > peekHeightPx + 40f) {
                                        sheetHeightAnim.animateTo(
                                            peekHeightPx,
                                            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                        )
                                    } else {
                                        dismissWithAnimation()
                                    }
                                } else {
                                    if (current > midPoint) {
                                        sheetHeightAnim.animateTo(
                                            expandedHeightPx,
                                            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                        )
                                    } else if (current > dismissThreshold) {
                                        sheetHeightAnim.animateTo(
                                            peekHeightPx,
                                            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                        )
                                    } else {
                                        dismissWithAnimation()
                                    }
                                }
                            },
                        ),
                ) {
                    // Tap pill handle to toggle peek / expanded
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    coroutineScope.launch {
                                        val target = if (sheetHeightAnim.value > peekHeightPx + 40f) peekHeightPx else expandedHeightPx
                                        sheetHeightAnim.animateTo(
                                            target,
                                            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                                        )
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(velvetColors.muted.copy(alpha = 0.45f)),
                        )
                    }

                    Row(
                        Modifier.padding(start = 22.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Queue", style = MaterialTheme.typography.headlineSmall, color = velvetColors.text)
                            Text(
                                if (isQueueLocked) "Queue locked" else "Swipe right to play next, left to remove",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isQueueLocked) velvetColors.accentText else velvetColors.muted,
                            )
                        }
                        IconButton(onClick = {
                            PlayerConnection.isQueueLocked = !PlayerConnection.isQueueLocked
                            android.widget.Toast.makeText(
                                context,
                                if (PlayerConnection.isQueueLocked) "Queue Locked" else "Queue Unlocked",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }) {
                            Icon(
                                if (isQueueLocked) AppIcons.Lock else AppIcons.LockOpen,
                                contentDescription = if (isQueueLocked) "Unlock Queue" else "Lock Queue",
                                tint = if (isQueueLocked) velvetColors.accentText else velvetColors.muted,
                            )
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 16.dp),
                ) {
                    if (currentItem != null) {
                        item(key = "header_now_playing") {
                            Text(
                                "NOW PLAYING",
                                style = MaterialTheme.typography.labelSmall,
                                color = velvetColors.accentText,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                        item(key = currentItem.qid) {
                            QueueRow(
                                item = currentItem,
                                isCurrent = true,
                                isQueueLocked = isQueueLocked,
                                context = context,
                                openMenu = openMenu,
                            )
                        }
                    }

                    if (displayUserQueue.isNotEmpty()) {
                        item(key = "header_next_in_queue") {
                            Text(
                                "NEXT IN QUEUE",
                                style = MaterialTheme.typography.labelSmall,
                                color = velvetColors.accentText,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(displayUserQueue, key = { _, item -> item.qid }) { index, item ->
                            val isDraggingThis = draggingQid == item.qid
                            val rowHeight = getItemHeightPx()
                            val visualOffset = if (isDraggingThis) {
                                totalDragOffsetY - (dragCurrentIndex - dragStartIndex) * rowHeight
                            } else 0f
                            QueueRow(
                                item = item,
                                isCurrent = false,
                                isQueueLocked = isQueueLocked,
                                context = context,
                                openMenu = openMenu,
                                isDragging = isDraggingThis,
                                dragOffsetY = visualOffset,
                                canMoveUp = index > 0,
                                canMoveDown = index < displayUserQueue.size - 1,
                                onMoveUp = { PlayerConnection.reorder(item.qid, index - 1) },
                                onMoveDown = { PlayerConnection.reorder(item.qid, index + 1) },
                                isDragEnabled = isDragReorderEnabled,
                                onDragStart = { onDragStart(item.qid, true) },
                                onDragDelta = onDragDelta,
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                            )
                        }
                    }

                    if (displayUpNext.isNotEmpty()) {
                        val sourceLabel = when {
                            PlayerConnection.isRadioActive -> "RADIO"
                            !PlayerConnection.currentPlaylistId.isNullOrBlank() -> "PLAYLIST"
                            else -> "AUTOPLAY"
                        }
                        item(key = "header_up_next") {
                            Text(
                                "NEXT FROM $sourceLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = velvetColors.muted,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(displayUpNext, key = { _, item -> item.qid }) { index, item ->
                            val isDraggingThis = draggingQid == item.qid
                            val rowHeight = getItemHeightPx()
                            val visualOffset = if (isDraggingThis) {
                                totalDragOffsetY - (dragCurrentIndex - dragStartIndex) * rowHeight
                            } else 0f
                            QueueRow(
                                item = item,
                                isCurrent = false,
                                isQueueLocked = isQueueLocked,
                                context = context,
                                openMenu = openMenu,
                                isDragging = isDraggingThis,
                                dragOffsetY = visualOffset,
                                canMoveUp = index > 0,
                                canMoveDown = index < displayUpNext.size - 1,
                                onMoveUp = { PlayerConnection.reorder(item.qid, index - 1) },
                                onMoveDown = { PlayerConnection.reorder(item.qid, index + 1) },
                                isDragEnabled = isDragReorderEnabled,
                                onDragStart = { onDragStart(item.qid, false) },
                                onDragDelta = onDragDelta,
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    isQueueLocked: Boolean,
    context: android.content.Context,
    openMenu: (SongMenuRequest) -> Unit,
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    isDragEnabled: Boolean = false,
    onDragStart: () -> Unit = {},
    onDragDelta: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val isSmart = item.isSmartRecommended
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 85.dp.toPx() }
    val animOffsetX = remember(item.qid) { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateItem()
            .graphicsLayer {
                if (isDragging) {
                    translationY = dragOffsetY
                    scaleX = 1.03f
                    scaleY = 1.03f
                    shadowElevation = 8.dp.toPx()
                } else {
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                    shadowElevation = 0f
                }
            }
            .zIndex(if (isDragging) 2f else 0f)
            .padding(horizontal = 14.dp, vertical = 3.dp),
    ) {
        // Spotify-style swipe underlay revealed on horizontal swipe
        val currentOffset = animOffsetX.value
        if (currentOffset != 0f && !isDragging) {
            val isSwipingRight = currentOffset > 0
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSwipingRight) Color(0xFF1DB954) else Color(0xFFE53935)),
                contentAlignment = if (isSwipingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isSwipingRight) {
                        Icon(
                            AppIcons.PlayNext,
                            contentDescription = "Play Next",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "Play Next",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            "Remove",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // Foreground track row card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { androidx.compose.ui.unit.IntOffset(animOffsetX.value.roundToInt(), 0) }
                .then(
                    if (!isCurrent && !isQueueLocked) {
                        Modifier.pointerInput(item.qid) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var isHorizontal = false
                                val touchSlop = viewConfiguration.touchSlop

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                        isHorizontal = true
                                        break
                                    }
                                    if (kotlin.math.abs(dy) > touchSlop) {
                                        // Vertical scroll: pass through directly to parent LazyColumn
                                        break
                                    }
                                }

                                if (isHorizontal) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            val offset = animOffsetX.value
                                            if (offset > swipeThresholdPx) {
                                                PlayerConnection.moveToTop(item.qid)
                                                android.widget.Toast.makeText(context, "Moved to top of queue", android.widget.Toast.LENGTH_SHORT).show()
                                                coroutineScope.launch {
                                                    animOffsetX.animateTo(
                                                        0f,
                                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                                    )
                                                }
                                            } else if (offset < -swipeThresholdPx) {
                                                android.widget.Toast.makeText(context, "Removed from queue", android.widget.Toast.LENGTH_SHORT).show()
                                                coroutineScope.launch {
                                                    animOffsetX.animateTo(-1500f, tween(durationMillis = 180))
                                                    PlayerConnection.removeItem(item.qid)
                                                }
                                            } else {
                                                coroutineScope.launch {
                                                    animOffsetX.animateTo(
                                                        0f,
                                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                                    )
                                                }
                                            }
                                            break
                                        }
                                        change.consume()
                                        val dx = change.position.x - change.previousPosition.x
                                        coroutineScope.launch {
                                            val target = (animOffsetX.value + dx).coerceIn(-600f, 600f)
                                            animOffsetX.snapTo(target)
                                        }
                                    }
                                }
                            }
                        }
                    } else Modifier
                )
                .then(
                    if (isSmart) {
                        Modifier
                            .background(velvetColors.surface, RoundedCornerShape(14.dp))
                            .background(velvetColors.accent.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                            .border(1.dp, velvetColors.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    } else if (isCurrent) {
                        Modifier
                            .background(velvetColors.raised, RoundedCornerShape(14.dp))
                            .border(1.dp, velvetColors.rule, RoundedCornerShape(14.dp))
                    } else {
                        Modifier.background(velvetColors.surface, RoundedCornerShape(14.dp))
                    }
                )
                .clickable(
                    enabled = !isCurrent,
                    onClick = {
                        if (!isDragging) {
                            PlayerConnection.jumpToQid(item.qid)
                        }
                    },
                )
                .semantics {
                    if (!isCurrent && !isQueueLocked) {
                        val actions = mutableListOf<CustomAccessibilityAction>()
                        if (canMoveUp) {
                            actions.add(
                                CustomAccessibilityAction("Move up") {
                                    onMoveUp()
                                    true
                                },
                            )
                        }
                        if (canMoveDown) {
                            actions.add(
                                CustomAccessibilityAction("Move down") {
                                    onMoveDown()
                                    true
                                },
                            )
                        }
                        if (actions.isNotEmpty()) {
                            customActions = actions
                        }
                    }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp)) {
                    Artwork(item.song.thumb, 46.dp, corner = 14.dp)
                    if (isSmart) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(16.dp)
                                .background(velvetColors.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                AppIcons.Sparkles,
                                contentDescription = "Smart recommendation",
                                tint = velvetColors.onAccent,
                                modifier = Modifier.size(10.dp),
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        item.song.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isCurrent) velvetColors.accentText else velvetColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSmart) {
                            Text(
                                "✨ Recommended • ",
                                style = MaterialTheme.typography.labelSmall,
                                color = velvetColors.accentText,
                            )
                        }
                        Text(
                            if (isCurrent) "Now playing" else item.song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCurrent) velvetColors.accentText else if (isSmart) velvetColors.accentText.copy(alpha = 0.85f) else velvetColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!isCurrent && !isQueueLocked) {
                    if (isSmart) {
                        var addedToPlaylist by remember(item.qid) { mutableStateOf(false) }
                        DockButton(
                            if (addedToPlaylist) Icons.Default.Check else Icons.Default.Add,
                            "Add to playlist",
                            tint = if (addedToPlaylist) velvetColors.accentText else velvetColors.accent,
                        ) {
                            val currentPid = PlayerConnection.currentPlaylistId
                            if (currentPid != null) {
                                VelvetApp.library.addToPlaylist(currentPid, item.song)
                                addedToPlaylist = true
                                android.widget.Toast.makeText(context, "Added to current playlist", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                VelvetApp.library.toggleLike(item.song)
                                addedToPlaylist = true
                                android.widget.Toast.makeText(context, "Added to Liked Songs", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            openMenu(
                                SongMenuRequest(
                                    song = item.song,
                                    queueItemId = item.qid,
                                    onMoveUp = if (canMoveUp) onMoveUp else null,
                                    onMoveDown = if (canMoveDown) onMoveDown else null,
                                )
                            )
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Track options",
                            tint = velvetColors.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (isDragEnabled && !isQueueLocked && !isCurrent) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .pointerInput(item.qid) {
                                    detectDragGestures(
                                        onDragStart = { onDragStart() },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            onDragDelta(dragAmount.y)
                                        },
                                        onDragEnd = { onDragEnd() },
                                        onDragCancel = { onDragCancel() },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                AppIcons.DragHandle,
                                contentDescription = "Hold and drag to reorder",
                                tint = velvetColors.muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

