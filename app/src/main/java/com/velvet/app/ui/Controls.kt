package com.velvet.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.luminance
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
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = VelvetMotion.Springs.TactilePress,
        label = "panelIconScale",
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier
            .size(size)
            .captureOriginOnPress(cornerRadiusDp = size.value * 0.5f)
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
        targetValue = if (isPressed && enabled) 0.90f else 1.0f,
        animationSpec = VelvetMotion.Springs.TactilePress,
        label = "accentCircleScale",
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier
            .size(size)
            .captureOriginOnPress(cornerRadiusDp = size.value * 0.5f)
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

@Composable
private fun rememberSmoothSeekFraction(
    songId: String,
    progress: Float,
    dragFraction: Float?,
): Float {
    val clampedProgress = SeekbarMath.clampFraction(progress)
    val smooth = remember(songId) { Animatable(clampedProgress) }
    LaunchedEffect(songId, clampedProgress, dragFraction) {
        if (dragFraction != null) {
            smooth.snapTo(SeekbarMath.clampFraction(dragFraction))
        } else {
            val delta = kotlin.math.abs(clampedProgress - smooth.value)
            when {
                delta > 0.03f -> smooth.animateTo(
                    clampedProgress,
                    VelvetMotion.Springs.WindowOpen,
                )
                delta > 0.0005f -> smooth.animateTo(
                    clampedProgress,
                    VelvetMotion.linearTween(durationMillis = VelvetMotion.Duration.Micro),
                )
                else -> smooth.snapTo(clampedProgress)
            }
        }
    }
    return dragFraction?.let(SeekbarMath::clampFraction) ?: smooth.value.coerceIn(0f, 1f)
}

@Composable
private fun Modifier.seekBarPointerInput(
    songId: String,
    onDragFraction: (Float?) -> Unit,
    onSeekPreview: (Float?) -> Unit,
    onSeekCommit: (Float) -> Unit,
): Modifier {
    val latestDrag by rememberUpdatedState(onDragFraction)
    val latestPreview by rememberUpdatedState(onSeekPreview)
    val latestCommit by rememberUpdatedState(onSeekCommit)
    return pointerInput(songId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val widthPx = size.width.toFloat().coerceAtLeast(1f)
            fun frac(x: Float) = (x / widthPx).coerceIn(0f, 1f)

            val slop = viewConfiguration.touchSlop * 0.65f
            var dragActive = false
            var latestFraction = frac(down.position.x)

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    latestDrag(null)
                    latestPreview(null)
                    break
                }
                if (!change.pressed) {
                    latestDrag(null)
                    latestPreview(null)
                    latestCommit(latestFraction)
                    break
                }
                if (change.isConsumed) {
                    latestDrag(null)
                    latestPreview(null)
                    break
                }

                val dx = kotlin.math.abs(change.position.x - down.position.x)
                val dy = kotlin.math.abs(change.position.y - down.position.y)
                if (!dragActive) {
                    if (dy > slop * 1.4f && dy > dx * 1.6f) {
                        latestDrag(null)
                        latestPreview(null)
                        break
                    }
                    if (dx >= slop * 0.45f) {
                        dragActive = true
                        latestFraction = frac(change.position.x)
                        latestDrag(latestFraction)
                        latestPreview(latestFraction)
                        change.consume()
                    }
                } else {
                    latestFraction = frac(change.position.x)
                    latestDrag(latestFraction)
                    latestPreview(latestFraction)
                    change.consume()
                }
            }
        }
    }
}

/** OneUI-style wavy / squiggly seekbar with smooth 120Hz playhead glide and circular handle. */
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
    var dragFraction by remember(songId) { mutableStateOf<Float?>(null) }
    val shown = rememberSmoothSeekFraction(songId, progress, dragFraction)
    val isDragging = dragFraction != null
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.30f else 1f,
        animationSpec = VelvetPopupMotion.sliderSpring,
        label = "seekThumbScale",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.44f else 0.28f,
        animationSpec = VelvetMotion.fastTween(),
        label = "seekHaloAlpha",
    )
    val activePulse by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = VelvetMotion.standardTween(VelvetMotion.Duration.Emphasized),
        label = "seekDotActivePulse",
    )
    var phase by remember(songId) { mutableFloatStateOf(0f) }
    LaunchedEffect(songId, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastNs = withFrameNanos { it }
        while (true) {
            val nowNs = withFrameNanos { it }
            val dtSec = ((nowNs - lastNs).coerceIn(0L, 64_000_000L)) / 1_000_000_000f
            lastNs = nowNs
            phase = ((phase + dtSec * ((2f * Math.PI.toFloat()) / 1.4f)) % (2f * Math.PI.toFloat()))
        }
    }

    val isDarkSurface = velvetColors.surface.luminance() < 0.5f
    val handleFillColor = if (isDarkSurface) Color.White else VelvetLightPalette.Surface

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
            .seekBarPointerInput(
                songId = songId,
                onDragFraction = { dragFraction = it },
                onSeekPreview = onSeekPreview,
                onSeekCommit = onSeek,
            ),
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
                val thumbW = 6.dp.toPx() * (0.95f + 0.15f * thumbScale)
                val thumbH = 22.dp.toPx() * (0.85f + 0.15f * thumbScale)
                val thumbTopLeft = Offset(playheadX - thumbW / 2f, centerY - thumbH / 2f)
                val thumbSize = Size(thumbW, thumbH)
                val thumbCorner = CornerRadius(thumbW / 2f, thumbW / 2f)
                drawRoundRect(
                    color = playheadColor.copy(alpha = haloAlpha),
                    topLeft = Offset(thumbTopLeft.x - 2.5.dp.toPx(), thumbTopLeft.y - 2.5.dp.toPx()),
                    size = Size(thumbW + 5.dp.toPx(), thumbH + 5.dp.toPx()),
                    cornerRadius = CornerRadius((thumbW + 5.dp.toPx()) / 2f, (thumbW + 5.dp.toPx()) / 2f),
                )
                drawRoundRect(
                    color = handleFillColor,
                    topLeft = thumbTopLeft,
                    size = thumbSize,
                    cornerRadius = thumbCorner,
                )
                drawRoundRect(
                    color = playedColor,
                    topLeft = thumbTopLeft,
                    size = thumbSize,
                    cornerRadius = thumbCorner,
                    style = Stroke(width = 1.4.dp.toPx()),
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
                            val pulse = 1.08f + (0.28f * kotlin.math.sin(phase.toDouble()).toFloat()) * activePulse
                            drawCircle(
                                color = handleFillColor,
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
                val thumbRadius = 5.dp.toPx() * (0.90f + 0.14f * thumbScale)
                val thumbCenter = Offset(playheadX, centerY)
                drawCircle(
                    color = playheadColor.copy(alpha = haloAlpha),
                    radius = 9.dp.toPx() * thumbScale,
                    center = thumbCenter,
                )
                drawCircle(
                    color = handleFillColor,
                    radius = thumbRadius,
                    center = thumbCenter,
                )
                drawCircle(
                    color = playedColor,
                    radius = thumbRadius,
                    center = thumbCenter,
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
    val currentOnSeek by rememberUpdatedState(onSeek)
    var dragFraction by remember(songId) { mutableStateOf<Float?>(null) }
    val shown = rememberSmoothSeekFraction(songId, progress, dragFraction)
    val activeHeightBoost by animateFloatAsState(
        targetValue = if (dragFraction != null) 1.30f else 1f,
        animationSpec = VelvetPopupMotion.sliderSpring,
        label = "segmentedBoost",
    )

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
            .seekBarPointerInput(
                songId = songId,
                onDragFraction = { dragFraction = it },
                onSeekPreview = onSeekPreview,
                onSeekCommit = onSeek,
            ),
    ) {
        val segments = 20
        val filled = (shown * segments).roundToInt().coerceIn(0, segments)
        val gap = minOf(3.dp.toPx(), size.width / (2 * segments))
        val width = (size.width - gap * (segments - 1)) / segments
        val height = 10.dp.toPx()
        repeat(segments) { index ->
            val segH = if (index == filled - 1) height * activeHeightBoost else height
            drawRoundRect(
                color = when {
                    index == filled - 1 -> playheadColor
                    index < filled -> playedColor
                    else -> unplayedColor
                },
                topLeft = Offset(index * (width + gap), (size.height - segH) / 2),
                size = Size(width, segH),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}
