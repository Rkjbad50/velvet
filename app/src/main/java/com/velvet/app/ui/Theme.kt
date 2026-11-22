package com.velvet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.velvet.app.R
import com.velvet.app.VelvetApp

internal val VelvetInk = Color(0xFF101010)
internal val VelvetLightText = Color(0xFFFFFFFF)
internal val VelvetAmber = Color(0xFFF59E0B)

/** Accent palette options for VELVET */
object VelvetAccents {
    val Purple = Color(0xFFB066FE)
    val Emerald = Color(0xFF10B981)
    val Cyan = Color(0xFF06B6D4)
    val Rose = Color(0xFFF43F5E)
    val Amber = Color(0xFFF59E0B)
    val Blue = Color(0xFF3B82F6)
    val Sunset = Color(0xFFFF7A00)
    val Lime = Color(0xFFA3E635)
    val Indigo = Color(0xFF6366F1)
    val Crimson = Color(0xFFFF1744)

    fun colorFor(name: String, dark: Boolean): Color = when (name.uppercase()) {
        "EMERALD" -> if (dark) Color(0xFF34D399) else Color(0xFF059669)
        "CYAN" -> if (dark) Color(0xFF22D3EE) else Color(0xFF0891B2)
        "ROSE" -> if (dark) Color(0xFFFB7185) else Color(0xFFE11D48)
        "AMBER" -> if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
        "BLUE" -> if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
        "SUNSET" -> if (dark) Color(0xFFFF8A50) else Color(0xFFE65100)
        "LIME" -> if (dark) Color(0xFFA3E635) else Color(0xFF65A30D)
        "INDIGO" -> if (dark) Color(0xFF818CF8) else Color(0xFF4F46E5)
        "CRIMSON" -> if (dark) Color(0xFFFF5252) else Color(0xFFDC2626)
        else -> if (dark) Color(0xFFC084FC) else Color(0xFF9333EA) // VELVET Signature Purple
    }

    val available = listOf(
        "VELVET" to Purple,
        "EMERALD" to Emerald,
        "CYAN" to Cyan,
        "ROSE" to Rose,
        "AMBER" to Amber,
        "BLUE" to Blue,
        "SUNSET" to Sunset,
        "LIME" to Lime,
        "INDIGO" to Indigo,
        "CRIMSON" to Crimson,
    )
}

@Immutable
internal data class VelvetColors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val pressed: Color,
    val text: Color,
    val muted: Color,
    val accentText: Color,
    val danger: Color,
    val rule: Color,
    val track: Color,
    val glassBorder: Color,
) {
    val accent = accentText
    val onAccent = Color.White
}

/** Material 3 Expressive pitch AMOLED black solid theme */
private fun createPitchAmoledColors(accent: Color) = VelvetColors(
    background = Color(0xFF000000), // Extreme AMOLED pitch black
    surface = Color(0xFF141416), // 100% Solid opaque dark container
    raised = Color(0xFF1F1F24), // 100% Solid opaque elevated card
    pressed = Color(0xFF2B2B33), // Solid pressed surface
    text = Color(0xFFF9FAFB),
    muted = Color(0xFF9CA3AF),
    accentText = accent,
    danger = Color(0xFFFF5252),
    rule = Color(0xFF26262D),
    track = Color(0xFF2E2E38),
    glassBorder = Color(0xFF2A2A34), // Subtle solid border
)

/** Material 3 Expressive clean light solid theme */
private fun createLightGlassColors(accent: Color) = VelvetColors(
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF), // 100% Solid opaque pure white
    raised = Color(0xFFF0F2F5), // Solid opaque elevated card
    pressed = Color(0xFFE5E7EB),
    text = Color(0xFF0F172A),
    muted = Color(0xFF64748B),
    accentText = accent,
    danger = Color(0xFFEF4444),
    rule = Color(0xFFE2E8F0),
    track = Color(0xFFCBD5E1),
    glassBorder = Color(0xFFE2E8F0),
)

private val LocalVelvetColors = staticCompositionLocalOf { createPitchAmoledColors(VelvetAccents.Purple) }

internal val velvetColors: VelvetColors
    @Composable get() = LocalVelvetColors.current

/** Material 3 Expressive solid card surface helper */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = velvetColors.surface,
    borderColor: Color = velvetColors.glassBorder,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
            .border(1.dp, borderColor, shape),
    ) {
        content()
    }
}

private fun VelvetColors.colorScheme(dark: Boolean) =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accentText,
        onPrimary = Color.White,
        primaryContainer = raised,
        onPrimaryContainer = text,
        inversePrimary = accentText,
        secondary = accentText,
        onSecondary = Color.White,
        secondaryContainer = raised,
        onSecondaryContainer = text,
        tertiary = accentText,
        onTertiary = Color.White,
        tertiaryContainer = raised,
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        surface = surface,
        surfaceTint = accent,
        onSurface = text,
        inverseSurface = if (dark) Color(0xFFF4F6F9) else Color(0xFF000000),
        inverseOnSurface = if (dark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
        surfaceVariant = raised,
        onSurfaceVariant = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = background,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = pressed,
        outline = track,
        outlineVariant = rule,
        error = danger,
    )

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resource: Int, weight: FontWeight) =
    Font(resource, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

internal val EditorialSerif = FontFamily(
    variableFont(R.font.source_serif_4, FontWeight.Normal),
    variableFont(R.font.source_serif_4, FontWeight.Bold),
)

internal val SacrificeFont = FontFamily(
    Font(R.font.sacrifice, FontWeight.Bold),
    Font(R.font.sacrifice, FontWeight.Normal),
)

private val SpaceGrotesk = FontFamily(
    variableFont(R.font.space_grotesk, FontWeight.Normal),
    variableFont(R.font.space_grotesk, FontWeight.Bold),
)

private val CoolJazz = FontFamily(Font(R.font.cool_jazz))
private val ChocoCooky = FontFamily(Font(R.font.choco_cooky))
private val Rosemary = FontFamily(Font(R.font.rosemary))

fun getFontFamily(fontName: String): FontFamily = when (fontName.uppercase()) {
    "SOURCE_SERIF" -> EditorialSerif
    "SPACE_GROTESK" -> SpaceGrotesk
    "MONOSPACE" -> FontFamily.Monospace
    "COOL_JAZZ" -> CoolJazz
    "CHOCO_COOKY" -> ChocoCooky
    "ROSEMARY" -> Rosemary
    else -> FontFamily.Default
}

private fun createTypography(family: FontFamily, isMonospace: Boolean = false) = Typography(
    displaySmall = TextStyle(fontFamily = family, fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = family, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = family, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = if (isMonospace) FontWeight.SemiBold else FontWeight.Medium),
    titleLarge = TextStyle(fontFamily = family, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = if (isMonospace) FontWeight.SemiBold else FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = if (isMonospace) FontWeight.SemiBold else FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = if (isMonospace) FontWeight.SemiBold else FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = if (isMonospace) FontWeight.SemiBold else FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (isMonospace) FontWeight.Medium else FontWeight.Normal),
    labelSmall = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontWeight = if (isMonospace) FontWeight.SemiBold else FontWeight.Normal),
)

@Composable
fun VelvetTheme(content: @Composable () -> Unit) {
    val library = VelvetApp.library
    val data by library.data.collectAsStateWithLifecycle()
    val isDark = data.isDarkTheme
    val accent = VelvetAccents.colorFor(data.accentColor, isDark)
    val colors = if (isDark) createPitchAmoledColors(accent) else createLightGlassColors(accent)
    val fontName = data.fontPreference
    val typography = createTypography(getFontFamily(fontName), isMonospace = fontName.equals("MONOSPACE", ignoreCase = true))

    CompositionLocalProvider(LocalVelvetColors provides colors) {
        MaterialTheme(
            colorScheme = colors.colorScheme(dark = isDark),
            typography = typography,
            content = content,
        )
    }
}

/** Material icons not in material-icons-core (the extended set adds ~10 MB to the APK). */
object AppIcons {
    val Play = icon("M8 5v14l11-7z")
    val Pause = icon("M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val SkipNext = icon("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    val SkipPrevious = icon("M6 6h2v12H6zm3.5 6l8.5 6V6z")
    val Shuffle = icon(
        "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 " +
            "17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
    )
    val SmartShuffle = icon(
        "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 " +
            "17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z " +
            "M18 1.5l.6 1.4 1.4.6-1.4.6-.6 1.4-.6-1.4-1.4-.6 1.4-.6z",
    )
    val Sparkles = icon("M12 3l1.5 3.5L17 8l-3.5 1.5L12 13l-1.5-3.5L7 8l3.5-1.5zM19 14l1 2.2 2.2 1-2.2 1-1 2.2-1-2.2-2.2-1 2.2-1z")
    val Repeat = icon("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z")
    val RepeatOne = icon(
        "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z",
    )
    val PlayNext = icon("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    val QueueMusic = icon(
        "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 " +
            "1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z",
    )
    val Library = icon(
        "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 " +
            "1.38-1.12 2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 " +
            "6H2v14c0 1.1.9 2 2 2h14v-2H4V6z",
    )
    val PlaylistAdd = icon("M14 10H2v2h12v-2zm0-4H2v2h12V6zm4 8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2 16h8v-2H2v2z")
    val MusicNote = icon(
        "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z",
    )

    val VolumeUp = icon(
        "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 " +
            "3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    )
    val Download = icon("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    val DownloadDone = icon("M5 18h14v2H5v-2zm4.6-2.7L5 10.7l2-1.9 2.6 2.6L17 4l2 2-9.4 9.3z")
    val Lyrics = icon("M14 17H4v2h10v-2zm6-8H4v2h16V9zM4 15h16v-2H4v2zM4 5v2h16V5H4z")
    val Timer = icon(
        "M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 " +
            "4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 " +
            "20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z",
    )
    val Radio = icon(
        "M12 5c-3.87 0-7 3.13-7 7h2c0-2.76 2.24-5 5-5s5 2.24 5 5h2c0-3.87-3.13-7-7-7zm1 9.29c.88-.39 " +
            "1.5-1.26 1.5-2.29 0-1.38-1.12-2.5-2.5-2.5S9.5 10.62 9.5 12c0 1.02.62 1.9 1.5 2.29v3.3L7.59 " +
            "21 9 22.41l3-3 3 3L16.41 21 13 17.59v-3.3zM12 1C5.93 1 1 5.93 1 12h2c0-4.97 4.03-9 9-9s9 " +
            "4.03 9 9h2c0-6.07-4.93-11-11-11z",
    )
    val Album = icon(
        "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-" +
            "4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 " +
            "1-.45 1-1-.45-1-1-1z",
    )
    val Sort = icon("M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z")
    val Equalizer = icon("M10 20h4V4h-4v16zm-6 0h4v-8H4v8zM16 9v11h4V9h-4z")
    val History = icon(
        "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 " +
            "7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-" +
            "9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
    )
    val Lock = icon(
        "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z",
    )
    val LockOpen = icon(
        "M12 17c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm6-9h-1V6c0-2.76-2.24-5-5-5-2.56 0-4.64 1.93-4.95 4.42l1.99.24C10.22 4.03 11 3 12 3c1.66 0 3 1.34 3 3v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm0 12H6V10h12v10z",
    )
    val Palette = icon(
        "M12 3c-4.97 0-9 4.03-9 9 0 2.12.74 4.07 1.97 5.61.38.48.96.79 1.6.79h1.79c1.1 0 2 .9 2 2v1.59c0 .78.63 1.41 1.41 1.41H12c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
    )
    val Pencil = icon("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z")
    val DragHandle = icon("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    val Search = icon("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    val GridView = icon("M4 11h5V5H4v6zm0 7h5v-6H4v6zm6 0h5v-6h-5v6zm6 0h5v-6h-5v6zm-6-7h5V5h-5v6zm6-6v6h5V5h-5z")

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
