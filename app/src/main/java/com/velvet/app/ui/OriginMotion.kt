package com.velvet.app.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/** Root-coordinate bounds and corner radius of the UI element launching a popup or screen. */
@Immutable
data class NavOrigin(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadiusDp: Float = 20f,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val isValid: Boolean get() = width > 0f && height > 0f

    companion object {
        fun fromRect(rect: Rect, cornerRadiusDp: Float = 20f): NavOrigin = NavOrigin(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            cornerRadiusDp = cornerRadiusDp,
        )
    }
}

/** Tracks exact pressed component bounds plus passive pointer fallback for seamless origin animations. */
class OriginRegistry {
    @Volatile
    private var lastTapRoot: Offset? = null

    @Volatile
    private var lastCapturedOrigin: NavOrigin? = null

    @Volatile
    private var lastCapturedAtMs: Long = 0L

    fun recordPointerDown(rootOffset: Offset) {
        lastTapRoot = rootOffset
    }

    fun recordContainer(origin: NavOrigin) {
        if (origin.isValid) {
            lastCapturedOrigin = origin
            lastCapturedAtMs = System.currentTimeMillis()
        }
    }

    fun recordContainer(boundsInRoot: Rect, cornerRadiusDp: Float = 20f) {
        recordContainer(NavOrigin.fromRect(boundsInRoot, cornerRadiusDp))
    }

    fun consumeBestOrigin(nowMs: Long = System.currentTimeMillis()): NavOrigin? {
        val picked = peekBestOrigin(nowMs)
        lastCapturedOrigin = null
        return picked
    }

    fun peekBestOrigin(nowMs: Long = System.currentTimeMillis()): NavOrigin? {
        val container = lastCapturedOrigin?.takeIf { nowMs - lastCapturedAtMs <= ORIGIN_CAPTURE_TTL_MS }
        if (container != null && container.isValid) return container
        val tap = lastTapRoot ?: return null
        val half = 28f
        return NavOrigin(tap.x - half, tap.y - half, tap.x + half, tap.y + half, cornerRadiusDp = 28f)
    }

    companion object {
        private const val ORIGIN_CAPTURE_TTL_MS = 650L
    }
}

val LocalOriginRegistry = staticCompositionLocalOf<OriginRegistry?> { null }

/** Computed frame state for fluid arc-gliding origin container transforms (unit-testable offline). */
@Immutable
data class OriginTransformSpec(
    val translationX: Float,
    val translationY: Float,
    val scale: Float,
    val cornerDp: Float,
    val shellAlpha: Float,
    val contentAlpha: Float,
    val scrimAlpha: Float,
)

object OriginTransformCalculator {

    // Silky critically-damped fluid springs: smooth organic glide without sharp snap or overshoot jerk
    val windowOpenSpring = spring<Float>(dampingRatio = 0.92f, stiffness = 360f)
    val windowCloseSpring = spring<Float>(dampingRatio = 0.95f, stiffness = 400f)

    fun compute(
        origin: NavOrigin?,
        containerWidth: Float,
        containerHeight: Float,
        progress: Float,
        isExiting: Boolean,
        targetCenterX: Float = containerWidth * 0.5f,
        targetCenterY: Float = containerHeight * 0.5f,
        minStartScale: Float = 0.16f,
        maxStartScale: Float = 0.72f,
        targetCornerDp: Float = 0f,
        targetScrimAlpha: Float = 0.34f,
        fallbackOffsetYPx: Float = 72f,
    ): OriginTransformSpec {
        val p = progress.coerceIn(0f, 1.04f)
        val clamped = p.coerceIn(0f, 1f)

        if (origin == null || !origin.isValid || containerWidth <= 1f || containerHeight <= 1f) {
            return OriginTransformSpec(
                translationX = 0f,
                translationY = (1f - p) * fallbackOffsetYPx,
                scale = 0.92f + 0.08f * p,
                cornerDp = targetCornerDp + (1f - clamped) * 18f,
                shellAlpha = clamped,
                contentAlpha = if (isExiting) ((clamped - 0.15f) / 0.65f).coerceIn(0f, 1f) else clamped,
                scrimAlpha = targetScrimAlpha * clamped,
            )
        }

        val ratioX = origin.width / containerWidth
        val ratioY = origin.height / containerHeight
        val startScale = maxOf(ratioX, ratioY).coerceIn(minStartScale, maxStartScale)
        val scale = startScale + (1f - startScale) * p

        val dx = origin.centerX - targetCenterX
        val dy = origin.centerY - targetCenterY
        val rem = (1f - clamped)
        val moveX = if (p <= 1f) rem.pow(1.22f) else (1f - p)
        val moveY = if (p <= 1f) rem.pow(0.88f) else (1f - p)

        // Compensate corner radius by inverse scale so submerged pill retains soft icon/card curvature.
        val visualOriginCorner = maxOf(origin.cornerRadiusDp, 22f)
        val startCornerDp = (visualOriginCorner / startScale.coerceAtLeast(0.18f)).coerceIn(24f, 120f)
        val cornerDp = targetCornerDp + (startCornerDp - targetCornerDp) * rem

        val shellAlpha = (clamped / 0.16f).coerceIn(0f, 1f)
        val contentAlpha = if (isExiting) {
            ((clamped - 0.22f) / 0.62f).coerceIn(0f, 1f)
        } else {
            ((clamped - 0.04f) / 0.34f).coerceIn(0f, 1f)
        }

        return OriginTransformSpec(
            translationX = dx * moveX,
            translationY = dy * moveY,
            scale = scale,
            cornerDp = cornerDp,
            shellAlpha = shellAlpha,
            contentAlpha = contentAlpha,
            scrimAlpha = targetScrimAlpha * clamped,
        )
    }
}

/**
 * Renders [content] inside an origin-anchored morphing container that unfolds along a smooth
 * parabolic arc out of [origin] when opening and submerges cleanly back into [origin] on close.
 */
@Composable
fun OriginTransformBox(
    origin: NavOrigin?,
    progress: Float,
    isExiting: Boolean,
    containerBackground: Color,
    modifier: Modifier = Modifier,
    minStartScale: Float = 0.16f,
    maxStartScale: Float = 0.72f,
    targetCornerDp: Float = 0f,
    targetScrimAlpha: Float = 0.34f,
    showScrim: Boolean = true,
    content: @Composable () -> Unit,
) {
    var boxW by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableFloatStateOf(0f) }
    var boxTopLeftInRoot by remember { mutableStateOf(Offset.Zero) }
    val densityPx = LocalDensity.current.density
    val spec = OriginTransformCalculator.compute(
        origin = origin,
        containerWidth = boxW,
        containerHeight = boxH,
        progress = progress,
        isExiting = isExiting,
        targetCenterX = boxTopLeftInRoot.x + boxW * 0.5f,
        targetCenterY = boxTopLeftInRoot.y + boxH * 0.5f,
        minStartScale = minStartScale,
        maxStartScale = maxStartScale,
        targetCornerDp = targetCornerDp,
        targetScrimAlpha = targetScrimAlpha,
        fallbackOffsetYPx = 28f * densityPx,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                if (coords.isAttached) boxTopLeftInRoot = coords.positionInRoot()
            }
            .onSizeChanged { s ->
                boxW = s.width.toFloat()
                boxH = s.height.toFloat()
            },
    ) {
        if (showScrim && spec.scrimAlpha > 0.005f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = spec.scrimAlpha)),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                .background(containerBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = spec.contentAlpha },
            ) {
                content()
            }
        }
    }
}

/**
 * Passively records this element's root-window bounds on press/release so any dialog, menu, or
 * screen triggered by this button/card expands from and submerges directly back into it.
 */
fun Modifier.captureOriginOnPress(cornerRadiusDp: Float = 20f): Modifier = composed {
    val registry = LocalOriginRegistry.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { c ->
        coords = c
    }.pointerInput(registry, cornerRadiusDp) {
        if (registry == null) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                    val active = coords
                    if (active != null && active.isAttached) {
                        registry.recordContainer(active.boundsInRoot(), cornerRadiusDp)
                    }
                }
            }
        }
    }
}
