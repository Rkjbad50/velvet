package com.tunehub.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Matte surface with the same hairline used in the bottom dock. */
@Composable
fun Modifier.panel(shape: Shape, fill: Color = tuneColors.surface, stroke: Color = tuneColors.glassBorder): Modifier =
    clip(shape).background(fill).border(1.dp, stroke, shape)

@Composable
fun PaperBackground(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxSize().background(tuneColors.background))

@Composable
fun PanelIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = tuneColors.text,
    fill: Color = tuneColors.surface,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .panel(RoundedCornerShape(12.dp), fill = fill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun AccentCircleButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) tuneColors.accent else tuneColors.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Icon(icon, null, tint = tuneColors.onAccent, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
fun FilterPill(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(48.dp)
            .panel(shape, fill = if (selected) tuneColors.accent else tuneColors.surface)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tuneColors.onAccent else tuneColors.text,
        )
    }
}

/** OneUI-style wavy / squiggly seekbar with smooth undulating sine wave and circular playhead. */
@Composable
fun WavySeekBar(
    songId: String,
    progress: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    playedColor: Color,
    playheadColor: Color,
    unplayedColor: Color,
    style: String = "WATER_WAVE",
    onSeek: (Float) -> Unit,
    onSeekPreview: (Float?) -> Unit = {},
) {
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnPreview by rememberUpdatedState(onSeekPreview)
    var dragFraction by remember(songId) { mutableStateOf<Float?>(null) }
    val shown = (dragFraction ?: progress).coerceIn(0f, 1f)

    val transition = rememberInfiniteTransition(label = "wave")
    val phase by if (isPlaying) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing)
            ),
            label = "wavePhase"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Canvas(
        modifier
            .semantics {
                contentDescription = "Seek bar"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                stateDescription = "${(progress * 100).toInt()}%"
                setProgress { target ->
                    currentOnSeek(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(songId) {
                detectTapGestures { offset -> currentOnSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(songId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragFraction?.let(currentOnSeek)
                        dragFraction = null
                        currentOnPreview(null)
                    },
                    onDragCancel = {
                        dragFraction = null
                        currentOnPreview(null)
                    },
                ) { change, _ ->
                    change.consume()
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    dragFraction = f
                    currentOnPreview(f)
                }
            },
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val playheadX = (shown * width).coerceIn(0f, width)

        when (style.uppercase()) {
            "APPLE_MUSIC" -> {
                // Style 2: Apple Music Minimalist sleek aesthetic
                val barHeight = 3.5.dp.toPx()
                if (playheadX < width) {
                    drawLine(
                        color = unplayedColor.copy(alpha = 0.25f),
                        start = Offset(playheadX, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = barHeight,
                        cap = StrokeCap.Round,
                    )
                }
                if (playheadX > 0f) {
                    drawLine(
                        color = playedColor,
                        start = Offset(0f, centerY),
                        end = Offset(playheadX, centerY),
                        strokeWidth = barHeight,
                        cap = StrokeCap.Round,
                    )
                }
                // Precision minimalist disc thumb
                drawCircle(
                    color = playheadColor.copy(alpha = 0.35f),
                    radius = 8.dp.toPx(),
                    center = Offset(playheadX, centerY),
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.5.dp.toPx(),
                    center = Offset(playheadX, centerY),
                )
            }

            "PIXEL_EXPRESSIVE" -> {
                // Style 1: Pixel UI Material Expressive (Android 17) capsule track with dynamic wave
                val trackHeight = 10.dp.toPx()
                val pillRadius = trackHeight / 2f
                drawRoundRect(
                    color = unplayedColor.copy(alpha = 0.24f),
                    topLeft = Offset(0f, centerY - pillRadius),
                    size = Size(width, trackHeight),
                    cornerRadius = CornerRadius(pillRadius, pillRadius),
                )
                if (playheadX > 0f) {
                    val wavePath = Path()
                    val wavelength = 22.dp.toPx()
                    val amplitude = 3.2.dp.toPx()
                    wavePath.moveTo(0f, centerY)
                    val step = 1.5.dp.toPx().coerceAtLeast(1f)
                    var x = 0f
                    while (x <= playheadX) {
                        val dampAtEdges = when {
                            x < 8.dp.toPx() -> (x / 8.dp.toPx()).coerceIn(0f, 1f)
                            playheadX - x < 8.dp.toPx() -> ((playheadX - x) / 8.dp.toPx()).coerceIn(0f, 1f)
                            else -> 1f
                        }
                        val waveY = centerY + (amplitude * dampAtEdges * kotlin.math.sin((x / wavelength) * 2 * Math.PI - phase * 1.2)).toFloat()
                        wavePath.lineTo(x, waveY)
                        x += step
                    }
                    wavePath.lineTo(playheadX, centerY)
                    drawPath(
                        path = wavePath,
                        color = playedColor,
                        style = Stroke(
                            width = 5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
                // Expressive vertical pill capsule thumb
                val thumbW = 5.dp.toPx()
                val thumbH = 18.dp.toPx()
                drawRoundRect(
                    color = playheadColor,
                    topLeft = Offset(playheadX - thumbW / 2f, centerY - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(playheadX - (thumbW * 0.4f) / 2f, centerY - (thumbH * 0.55f) / 2f),
                    size = Size(thumbW * 0.4f, thumbH * 0.55f),
                    cornerRadius = CornerRadius(thumbW * 0.2f, thumbW * 0.2f),
                )
            }

            "PLANE_FLIGHT" -> {
                // Style 3: Supersonic Airplane Runway & Flight Tracker ✈️
                val trackHeight = 3.dp.toPx()
                if (playheadX < width) {
                    drawLine(
                        color = unplayedColor.copy(alpha = 0.25f),
                        start = Offset(playheadX, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = trackHeight,
                        cap = StrokeCap.Round,
                    )
                }
                if (playheadX > 0f) {
                    // Radiant jet contrail glow trail
                    drawLine(
                        color = playedColor.copy(alpha = 0.28f),
                        start = Offset(0f, centerY),
                        end = Offset(playheadX, centerY),
                        strokeWidth = 8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = playedColor,
                        start = Offset(0f, centerY),
                        end = Offset(playheadX, centerY),
                        strokeWidth = trackHeight,
                        cap = StrokeCap.Round,
                    )
                }
                // Supersonic Jet icon pointing in flight direction
                val planeSize = 9.dp.toPx()
                val bankY = if (isPlaying) (kotlin.math.sin(phase.toDouble()).toFloat() * 1.5.dp.toPx()) else 0f
                val planePath = Path().apply {
                    val noseX = playheadX + planeSize * 1.15f
                    val noseY = centerY + bankY
                    val tailX = playheadX - planeSize * 0.85f
                    val tailY = centerY + bankY

                    moveTo(noseX, noseY)
                    // Right wing
                    lineTo(playheadX - planeSize * 0.1f, centerY + bankY + planeSize * 0.95f)
                    lineTo(playheadX - planeSize * 0.35f, centerY + bankY + planeSize * 0.95f)
                    lineTo(playheadX - planeSize * 0.1f, centerY + bankY + planeSize * 0.25f)
                    // Right tail fin
                    lineTo(tailX, centerY + bankY + planeSize * 0.45f)
                    lineTo(tailX - planeSize * 0.2f, centerY + bankY + planeSize * 0.45f)
                    lineTo(tailX, centerY + bankY)
                    // Left tail fin
                    lineTo(tailX - planeSize * 0.2f, centerY + bankY - planeSize * 0.45f)
                    lineTo(tailX, centerY + bankY - planeSize * 0.45f)
                    lineTo(playheadX - planeSize * 0.1f, centerY + bankY - planeSize * 0.25f)
                    // Left wing
                    lineTo(playheadX - planeSize * 0.35f, centerY + bankY - planeSize * 0.95f)
                    lineTo(playheadX - planeSize * 0.1f, centerY + bankY - planeSize * 0.95f)
                    close()
                }
                drawPath(path = planePath, color = playheadColor)
                // Jet cockpit luminous dot
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = Offset(playheadX + planeSize * 0.3f, centerY + bankY),
                )
            }

            else -> {
                // Style 4: Thicker Material 3 Expressive Water Wavy (Default)
                val strokeWidth = 4.8.dp.toPx()
                if (playheadX < width) {
                    drawLine(
                        color = unplayedColor,
                        start = Offset(playheadX, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }

                if (playheadX > 0f) {
                    val wavePath = Path()
                    val wavelength = 32.dp.toPx()
                    val amplitude = 4.5.dp.toPx()

                    wavePath.moveTo(0f, centerY)
                    val step = 1.5.dp.toPx().coerceAtLeast(1f)
                    var x = 0f
                    while (x <= playheadX) {
                        val dampAtEdges = when {
                            x < 10.dp.toPx() -> (x / 10.dp.toPx()).coerceIn(0f, 1f)
                            playheadX - x < 10.dp.toPx() -> ((playheadX - x) / 10.dp.toPx()).coerceIn(0f, 1f)
                            else -> 1f
                        }
                        val k = (x / wavelength) * 2 * Math.PI - phase
                        val waveHarmonic = 0.85 * kotlin.math.sin(k) + 0.30 * kotlin.math.sin(2 * k)
                        val waveY = centerY + (amplitude * dampAtEdges * waveHarmonic).toFloat()
                        wavePath.lineTo(x, waveY)
                        x += step
                    }
                    wavePath.lineTo(playheadX, centerY)

                    drawPath(
                        path = wavePath,
                        color = playedColor,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }

                // Dual water droplet thumb
                drawCircle(
                    color = playheadColor,
                    radius = 7.5.dp.toPx(),
                    center = Offset(playheadX, centerY),
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.5.dp.toPx(),
                    center = Offset(playheadX, centerY),
                )
            }
        }
    }
}

/** The LCD progress display also handles taps, drags, and accessibility seeks. */
@Composable
fun SegmentedSeekBar(
    songId: String,
    progress: Float,
    modifier: Modifier = Modifier,
    playedColor: Color,
    playheadColor: Color,
    unplayedColor: Color,
    onSeek: (Float) -> Unit,
    onSeekPreview: (Float?) -> Unit = {},
) {
    // the gesture handlers live across recompositions: always call the latest callbacks
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnPreview by rememberUpdatedState(onSeekPreview)
    var dragFraction by remember(songId) { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: progress

    Canvas(
        modifier
            .semantics {
                contentDescription = "Seek bar"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                stateDescription = "${(progress * 100).toInt()}%"
                setProgress { target ->
                    currentOnSeek(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(songId) {
                detectTapGestures { offset -> currentOnSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(songId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragFraction?.let(currentOnSeek)
                        dragFraction = null
                        currentOnPreview(null)
                    },
                    onDragCancel = {
                        dragFraction = null
                        currentOnPreview(null)
                    },
                ) { change, _ ->
                    change.consume()
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    dragFraction = f
                    currentOnPreview(f)
                }
            },
    ) {
        val segments = 20
        val filled = (shown * segments).roundToInt().coerceIn(0, segments)
        val gap = minOf(3.dp.toPx(), size.width / (2 * segments))
        val width = (size.width - gap * (segments - 1)) / segments
        val height = 10.dp.toPx()
        repeat(segments) { index ->
            drawRoundRect(
                color = when {
                    index == filled - 1 -> playheadColor
                    index < filled -> playedColor
                    else -> unplayedColor
                },
                topLeft = Offset(index * (width + gap), (size.height - height) / 2),
                size = Size(width, height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}
