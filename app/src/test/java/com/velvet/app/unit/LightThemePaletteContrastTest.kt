package com.velvet.app.unit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.velvet.app.ui.VelvetAccents
import com.velvet.app.ui.VelvetLightPalette
import com.velvet.app.ui.createLightGlassColors
import com.velvet.app.ui.createPitchAmoledColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-JVM tests verifying the warm creamy ivory Light Mode palette, elevation hierarchy,
 * WCAG contrast ratios, and accent separation against the cream surface.
 */
class LightThemePaletteContrastTest {

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val fgLum = foreground.luminance().toDouble()
        val bgLum = background.luminance().toDouble()
        val lighter = max(fgLum, bgLum)
        val darker = min(fgLum, bgLum)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertWarmNonWhite(label: String, color: Color) {
        assertNotEquals("$label must not be stark #FFFFFF", Color.White, color)
        assertTrue(
            "$label must have at least one channel below 1.0f (no stark pure white)",
            color.red < 1f || color.green < 1f || color.blue < 1f,
        )
        assertTrue(
            "$label should maintain warm channel bias (red=${color.red} > blue=${color.blue})",
            color.red > color.blue,
        )
    }

    @Test
    fun lightPaletteAvoidsPureWhiteAndMaintainsWarmCreamTone() {
        val paletteTokens = listOf(
            "Background" to VelvetLightPalette.Background,
            "Surface" to VelvetLightPalette.Surface,
            "Raised" to VelvetLightPalette.Raised,
            "Pressed" to VelvetLightPalette.Pressed,
            "Text" to VelvetLightPalette.Text,
            "Muted" to VelvetLightPalette.Muted,
            "Rule" to VelvetLightPalette.Rule,
            "Track" to VelvetLightPalette.Track,
            "GlassBorder" to VelvetLightPalette.GlassBorder,
        )
        paletteTokens.forEach { (name, color) ->
            assertWarmNonWhite("VelvetLightPalette.$name", color)
        }

        val accent = VelvetAccents.colorFor("VELVET", dark = false)
        val lightColors = createLightGlassColors(accent)
        listOf(
            "background" to lightColors.background,
            "surface" to lightColors.surface,
            "raised" to lightColors.raised,
            "pressed" to lightColors.pressed,
            "rule" to lightColors.rule,
            "track" to lightColors.track,
            "glassBorder" to lightColors.glassBorder,
        ).forEach { (name, color) ->
            assertWarmNonWhite("createLightGlassColors.$name", color)
        }

        assertEquals(VelvetLightPalette.Background, lightColors.background)
        assertEquals(VelvetLightPalette.Surface, lightColors.surface)
        assertEquals(VelvetLightPalette.Raised, lightColors.raised)
        assertEquals(VelvetLightPalette.Pressed, lightColors.pressed)
        assertEquals(Color(0xFFDC2626), lightColors.danger)
    }

    @Test
    fun lightThemeElevationHierarchyHasDistinctBrightnessSteps() {
        val colors = createLightGlassColors(VelvetAccents.colorFor("VELVET", dark = false))
        val surfaceLum = colors.surface.luminance()
        val backgroundLum = colors.background.luminance()
        val raisedLum = colors.raised.luminance()
        val pressedLum = colors.pressed.luminance()

        assertTrue("Surface must be brighter than Background", surfaceLum > backgroundLum)
        assertTrue("Background must be brighter than Raised", backgroundLum > raisedLum)
        assertTrue("Raised must be brighter than Pressed", raisedLum > pressedLum)

        assertTrue("Surface-to-Background brightness step must be distinct", surfaceLum - backgroundLum >= 0.04f)
        assertTrue("Background-to-Raised brightness step must be distinct", backgroundLum - raisedLum >= 0.05f)
        assertTrue("Raised-to-Pressed brightness step must be distinct", raisedLum - pressedLum >= 0.08f)
    }

    @Test
    fun lightThemeTextAndMutedMeetWcagContrastThresholds() {
        val colors = createLightGlassColors(VelvetAccents.colorFor("VELVET", dark = false))

        assertTrue(
            "Primary text on Surface must meet >= 7.0 contrast",
            contrastRatio(colors.text, colors.surface) >= 7.0,
        )
        assertTrue(
            "Primary text on Background must meet >= 7.0 contrast",
            contrastRatio(colors.text, colors.background) >= 7.0,
        )
        assertTrue(
            "Primary text on Raised must meet >= 7.0 contrast",
            contrastRatio(colors.text, colors.raised) >= 7.0,
        )
        assertTrue(
            "Muted text on Surface must meet >= 4.5 contrast",
            contrastRatio(colors.muted, colors.surface) >= 4.5,
        )
        assertTrue(
            "Muted text on Background must meet >= 4.5 contrast",
            contrastRatio(colors.muted, colors.background) >= 4.5,
        )
    }

    @Test
    fun lightModeAccentsMaintainClearContrastAgainstCreamSurface() {
        VelvetAccents.available.forEach { (name, _) ->
            val lightAccent = VelvetAccents.colorFor(name, dark = false)
            val ratio = contrastRatio(lightAccent, VelvetLightPalette.Surface)
            assertTrue(
                "Accent $name light contrast ($ratio) must be >= 2.2 on Surface",
                ratio >= 2.2,
            )
            if (!name.equals("LIME", ignoreCase = true)) {
                assertTrue(
                    "Non-Lime accent $name light contrast ($ratio) must be >= 3.0 on Surface",
                    ratio >= 3.0,
                )
            }

            val darkAccent = VelvetAccents.colorFor(name, dark = true)
            val darkColors = createPitchAmoledColors(darkAccent)
            assertEquals(Color(0xFF000000), darkColors.background)
            assertEquals(darkAccent, darkColors.accentText)
        }
    }
}
