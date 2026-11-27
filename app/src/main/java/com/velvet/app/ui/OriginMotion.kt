package com.velvet.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Centralized app-wide motion system for VELVET: standardized durations, Material-inspired
 * easing curves, tuned 60/120fps spring physics, shape radii, shimmer loaders, and tactile
 * interaction modifiers.
 */
object VelvetMotion {

    object Duration {
        const val Micro = 120
        const val Fast = 180
        const val Standard = 240
        const val Emphasized = 320
        const val ColorTransition = 360
        const val ArtworkCrossfade = 180
        const val BannerEnter = 240
        const val BannerExit = 200
        const val ShimmerCycle = 1150
    }

    object Easing {
        val Standard: androidx.compose.animation.core.Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
        val Decelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
        val Accelerate: androidx.compose.animation.core.Easing = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
        val Linear: androidx.compose.animation.core.Easing = LinearEasing
    }

    object Springs {
        val WindowOpen = spring<Float>(dampingRatio = 0.90f, stiffness = 560f, visibilityThreshold = 0.001f)
        val WindowClose = spring<Float>(dampingRatio = 0.94f, stiffness = 620f, visibilityThreshold = 0.001f)
        val PopupEnter = spring<Float>(dampingRatio = 0.88f, stiffness = 580f, visibilityThreshold = 0.001f)
        val PopupExit = spring<Float>(dampingRatio = 0.94f, stiffness = 640f, visibilityThreshold = 0.001f)
        val Interactive = spring<Float>(dampingRatio = 0.88f, stiffness = 560f, visibilityThreshold = 0.001f)
        val TactilePress = spring<Float>(dampingRatio = 0.82f, stiffness = 620f, visibilityThreshold = 0.001f)
        val Expressive = spring<Float>(dampingRatio = 0.78f, stiffness = 440f, visibilityThreshold = 0.001f)
        val ListPlacement = spring<IntOffset>(dampingRatio = 0.88f, stiffness = 480f)
        val DpStandard = spring<Dp>(dampingRatio = 0.90f, stiffness = 520f)
    }

    object Shape {
        val Dialog = RoundedCornerShape(24.dp)
        val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        val Menu = RoundedCornerShape(16.dp)
        val Tile = RoundedCornerShape(16.dp)
    }

    fun <T> standardTween(durationMillis: Int = Duration.Standard) =
        tween<T>(durationMillis = durationMillis, easing = Easing.Standard)

    fun <T> fastTween(durationMillis: Int = Duration.Fast) =
        tween<T>(durationMillis = durationMillis, easing = Easing.Standard)

    fun <T> linearTween(durationMillis: Int = Duration.Micro) =
        tween<T>(durationMillis = durationMillis, easing = Easing.Linear)

    fun modalFadeEnter(): EnterTransition =
        fadeIn(tween(Duration.Standard, easing = Easing.Decelerate)) +
            scaleIn(initialScale = 0.96f, animationSpec = Springs.WindowOpen)

    fun modalFadeExit(): ExitTransition =
        fadeOut(tween(Duration.Emphasized, easing = Easing.Accelerate)) +
            scaleOut(targetScale = 0.96f, animationSpec = tween(Duration.Emphasized, easing = Easing.Accelerate))

    fun bannerEnter(): EnterTransition =
        slideInVertically(
            initialOffsetY = { -it - 48 },
            animationSpec = tween(Duration.BannerEnter, easing = Easing.Decelerate),
        ) + fadeIn(tween(Duration.BannerEnter, easing = Easing.Standard)) +
            scaleIn(initialScale = 0.95f, animationSpec = Springs.PopupEnter)

    fun bannerExit(): ExitTransition =
        slideOutVertically(
            targetOffsetY = { -it - 48 },
            animationSpec = tween(Duration.BannerExit, easing = Easing.Accelerate),
        ) + fadeOut(tween(Duration.BannerExit, easing = Easing.Accelerate)) +
            scaleOut(targetScale = 0.95f, animationSpec = tween(Duration.BannerExit, easing = Easing.Accelerate))
}

/**
 * Shared infinite shimmer brush for skeleton placeholders across Home, Search, and Genre hubs.
 */
@Composable
fun rememberVelvetShimmerBrush(
    baseColor: Color = velvetColors.raised,
    highlightColor: Color = velvetColors.pressed.copy(alpha = 0.85f),
): Brush {
    val transition = rememberInfiniteTransition(label = "velvetShimmer")
    val shift by transition.animateFloat(
        initialValue = -600f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = VelvetMotion.Duration.ShimmerCycle, easing = VelvetMotion.Easing.Linear),
            repeatMode = RepeatMode.Restart,
        ),
        label = "velvetShimmerShift",
    )
    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(shift - 320f, 0f),
        end = Offset(shift, 320f),
    )
}

/**
 * Consistent press scale-down micro-interaction tied to [VelvetMotion.Springs.TactilePress].
 */
fun Modifier.velvetTactilePress(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.95f,
    enabled: Boolean = true,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = VelvetMotion.Springs.TactilePress,
        label = "velvetTactilePress",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

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

    // Fast, crisp, fluid 120Hz springs: responsive entry & zero end-of-close snap
    val windowOpenSpring = spring<Float>(
        dampingRatio = 0.90f,
        stiffness = 560f,
        visibilityThreshold = 0.001f,
    )
    val windowCloseSpring = spring<Float>(
        dampingRatio = 0.94f,
        stiffness = 620f,
        visibilityThreshold = 0.001f,
    )

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
        val shellT = (clamped / 0.16f).coerceIn(0f, 1f)
        val smoothShell = shellT * shellT * (3f - 2f * shellT)
        val contentT = ((clamped - 0.20f) / 0.30f).coerceIn(0f, 1f)
        val smoothContent = contentT * contentT * (3f - 2f * contentT)

        if (origin == null || !origin.isValid || containerWidth <= 1f || containerHeight <= 1f) {
            val fallbackAlpha = (clamped / 0.28f).coerceIn(0f, 1f).let { t -> t * t * (3f - 2f * t) }
            return OriginTransformSpec(
                translationX = 0f,
                translationY = (1f - p) * fallbackOffsetYPx,
                scale = 0.92f + 0.08f * p,
                cornerDp = targetCornerDp + (1f - clamped) * 18f,
                shellAlpha = fallbackAlpha,
                contentAlpha = smoothContent.coerceAtLeast(if (!isExiting && clamped > 0.01f) fallbackAlpha else 0f),
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

        return OriginTransformSpec(
            translationX = dx * moveX,
            translationY = dy * moveY,
            scale = scale,
            cornerDp = cornerDp,
            shellAlpha = smoothShell,
            contentAlpha = smoothContent,
            scrimAlpha = targetScrimAlpha * clamped,
        )
    }
}

/**
 * Renders [content] inside an origin-anchored morphing container driven purely in GPU graphicsLayer
 * (zero per-frame recomposition during opening or closing) for full 90/120Hz fluidity.
 */
@Composable
fun OriginTransformBox(
    origin: NavOrigin?,
    progressProvider: () -> Float,
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

    fun currentSpec(): OriginTransformSpec = OriginTransformCalculator.compute(
        origin = origin,
        containerWidth = boxW,
        containerHeight = boxH,
        progress = progressProvider(),
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
        if (showScrim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = currentSpec().scrimAlpha
                    }
                    .background(Color.Black),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val spec = currentSpec()
                    translationX = spec.translationX
                    translationY = spec.translationY
                    scaleX = spec.scale
                    scaleY = spec.scale
                    alpha = spec.shellAlpha
                    if (spec.cornerDp >= 1f) {
                        shape = RoundedCornerShape(kotlin.math.round(spec.cornerDp).dp)
                        clip = true
                    }
                }
                .background(containerBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = currentSpec().contentAlpha },
            ) {
                content()
            }
        }
    }
}

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
) = OriginTransformBox(
    origin = origin,
    progressProvider = { progress },
    isExiting = isExiting,
    containerBackground = containerBackground,
    modifier = modifier,
    minStartScale = minStartScale,
    maxStartScale = maxStartScale,
    targetCornerDp = targetCornerDp,
    targetScrimAlpha = targetScrimAlpha,
    showScrim = showScrim,
    content = content,
)

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
