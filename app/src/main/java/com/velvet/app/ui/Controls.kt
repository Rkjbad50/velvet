package com.velvet.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
fun Modifier.panel(shape: Shape, fill: Color = velvetColors.surface, stroke: Color = velvetColors.glassBorder): Modifier =
    clip(shape).background(fill).border(1.dp, stroke, shape)

@Composable
fun PaperBackground(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxSize().background(velvetColors.background))

@Composable
fun PanelIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = velvetColors.text,
    fill: Color = velvetColors.surface,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "panelIconScale",
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .panel(RoundedCornerShape(12.dp), fill = fill)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    onClick()
                },
            )
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "accentCircleScale",
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (enabled) velvetColors.accent else velvetColors.raised)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    onClick()
                },
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else Icon(icon, null, tint = velvetColors.onAccent, modifier = Modifier.size(size * 0.45f))
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
            .panel(shape, fill = if (selected) velvetColors.accent else velvetColors.surface)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) velvetColors.onAccent else velvetColors.text,
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
            "WIDE" -> {
                // Style 2: Wide - bold, tactile capsule slider
                val trackHeight = 12.dp.toPx()
                val pillRadius = trackHeight / 2f
                // Unplayed background capsule
                drawRoundRect(
                    color = unplayedColor.copy(alpha = 0.22f),
                    topLeft = Offset(0f, centerY - pillRadius),
                    size = Size(width, trackHeight),
                    cornerRadius = CornerRadius(pillRadius, pillRadius),
                )
                // Played filled capsule
                if (playheadX > 0f) {
                    drawRoundRect(
                        color = playedColor,
                        topLeft = Offset(0f, centerY - pillRadius),
                        size = Size(playheadX, trackHeight),
                        cornerRadius = CornerRadius(pillRadius, pillRadius),
                    )
                }
                // Tactile vertical pill handle
                val thumbW = 6.dp.toPx()
                val thumbH = 22.dp.toPx()
                drawRoundRect(
                    color = playheadColor.copy(alpha = 0.35f),
                    topLeft = Offset(playheadX - thumbW / 2f - 2.dp.toPx(), centerY - thumbH / 2f - 2.dp.toPx()),
                    size = Size(thumbW + 4.dp.toPx(), thumbH + 4.dp.toPx()),
                    cornerRadius = CornerRadius((thumbW + 4.dp.toPx()) / 2f, (thumbW + 4.dp.toPx()) / 2f),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(playheadX - thumbW / 2f, centerY - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f),
                )
            }

            "DOT_MATRIX" -> {
                // Style 3: DotMatrix - digital audio workstation LED matrix
                val dotSpacing = 8.5.dp.toPx()
                val dotRadius = 2.4.dp.toPx()
                val count = (width / dotSpacing).toInt()

                for (i in 0..count) {
                    val dotX = (i * dotSpacing).coerceIn(0f, width)
                    val isPlayed = dotX <= playheadX
                    val isNearest = kotlin.math.abs(dotX - playheadX) < (dotSpacing / 2f)

                    if (isPlayed) {
                        // Glowing played LED
                        drawCircle(
                            color = playedColor.copy(alpha = 0.25f),
                            radius = dotRadius * 1.8f,
                            center = Offset(dotX, centerY),
                        )
                        drawCircle(
                            color = playedColor,
                            radius = dotRadius,
                            center = Offset(dotX, centerY),
                        )
                        if (isNearest) {
                            val pulse = if (isPlaying) (1f + 0.35f * kotlin.math.sin(phase.toDouble()).toFloat()) else 1.2f
                            drawCircle(
                                color = Color.White,
                                radius = dotRadius * pulse * 0.8f,
                                center = Offset(dotX, centerY),
                            )
                        }
                    } else {
                        // Dim unplayed LED
                        drawCircle(
                            color = unplayedColor.copy(alpha = 0.22f),
                            radius = dotRadius * 0.8f,
                            center = Offset(dotX, centerY),
                        )
                    }
                }
            }

            else -> {
                // Style 1: Minimal (Default) - sleek, ultra-clean precision audiophile bar
                val trackHeight = 3.dp.toPx()
                if (playheadX < width) {
                    drawLine(
                        color = unplayedColor.copy(alpha = 0.22f),
                        start = Offset(playheadX, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = trackHeight,
                        cap = StrokeCap.Round,
                    )
                }
                if (playheadX > 0f) {
                    drawLine(
                        color = playedColor,
                        start = Offset(0f, centerY),
                        end = Offset(playheadX, centerY),
                        strokeWidth = trackHeight,
                        cap = StrokeCap.Round,
                    )
                }
                // Refined luminous circular thumb
                drawCircle(
                    color = playheadColor.copy(alpha = 0.30f),
                    radius = 9.dp.toPx(),
                    center = Offset(playheadX, centerY),
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(playheadX, centerY),
                )
                drawCircle(
                    color = playedColor,
                    radius = 5.dp.toPx(),
                    center = Offset(playheadX, centerY),
                    style = Stroke(width = 1.5.dp.toPx()),
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
