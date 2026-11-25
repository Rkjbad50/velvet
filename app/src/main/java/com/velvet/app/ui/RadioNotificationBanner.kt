package com.velvet.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * State token for triggering and displaying the Song Radio Playing notification banner.
 */
data class RadioBannerState(
    val title: String,
    val token: Long = System.currentTimeMillis(),
)

/**
 * Prominent, informative and indicative banner displayed when Song Radio begins.
 * Highlights the seed song title and confirms that recommendations and queue
 * are shifting toward matching genre, tempo, language, and era.
 */
@Composable
fun RadioNotificationBanner(
    bannerState: RadioBannerState?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    val isDark = isSystemInDarkTheme()
    val accent = velvetColors.accent

    var renderedBanner by remember { mutableStateOf(bannerState) }
    LaunchedEffect(bannerState) {
        if (bannerState != null) renderedBanner = bannerState
    }

    LaunchedEffect(bannerState?.token) {
        if (bannerState != null) {
            delay(3600)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = bannerState != null,
        enter = slideInVertically(
            initialOffsetY = { -it - 48 },
            animationSpec = spring(dampingRatio = 0.88f, stiffness = 380f),
        ) + fadeIn(tween(240, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.90f, stiffness = 400f)),
        exit = slideOutVertically(
            targetOffsetY = { -it - 48 },
            animationSpec = tween(220, easing = FastOutSlowInEasing),
        ) + fadeOut(tween(200, easing = FastOutSlowInEasing)) +
            scaleOut(targetScale = 0.95f, animationSpec = tween(200, easing = FastOutSlowInEasing)),
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        val activeBanner = bannerState ?: renderedBanner
        if (activeBanner != null) {
            val bannerState = activeBanner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (isDark) Color(0xF2161922) else velvetColors.raised.copy(alpha = 0.98f)
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.8f),
                                accent.copy(alpha = 0.35f),
                                Color.Transparent,
                            )
                        ),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .clickable { onDismiss() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pulsing / Glowing Radio Icon badge
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Radio,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Informative text instance
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "\"${bannerState.title}\"",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                ),
                                color = velvetColors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = "Radio Playing",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                ),
                                color = accent
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Shifting queue to similar genre, tempo & era",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = velvetColors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Mini live equalizer bars
                    MiniRadioEqualizerBars(accent = accent)
                }
            }
        }
    }
}

/**
 * 3 mini animated equalizer bars representing the active live radio stream.
 */
@Composable
fun MiniRadioEqualizerBars(accent: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "radioEq")
    val h1 by infiniteTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h3"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        modifier = Modifier
            .height(18.dp)
            .padding(end = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(h1)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(h2)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight(h3)
                .clip(RoundedCornerShape(1.5.dp))
                .background(accent)
        )
    }
}
