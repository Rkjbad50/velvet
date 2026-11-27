package com.velvet.app.unit

import androidx.compose.ui.unit.dp
import com.velvet.app.data.EqSettings
import com.velvet.app.playback.AudioFx
import com.velvet.app.ui.resolveRootContentBottomPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugfixesSearchBounceAndAudioFxTest {

    @Test
    fun rootContentBottomPaddingTracksDockAndImeMonotonicallyWithoutSpringBounce() {
        val dock = 132.dp
        val density = 3f
        val navPx = 144 // 48.dp

        // Keyboard closed -> reserves exact dock height
        assertEquals(dock, resolveRootContentBottomPadding(dock, imeBottomPx = 0, navBottomPx = navPx, densityPxPerDp = density))

        // Keyboard opening partially behind dock (< 132.dp above nav) -> stays steady at dock height
        assertEquals(dock, resolveRootContentBottomPadding(dock, imeBottomPx = 300, navBottomPx = navPx, densityPxPerDp = density))

        // Keyboard rises smoothly above dock (1044px -> (1044-144)/3 = 300.dp) -> monotonically expands
        val p1 = resolveRootContentBottomPadding(dock, imeBottomPx = 750, navBottomPx = navPx, densityPxPerDp = density)
        val p2 = resolveRootContentBottomPadding(dock, imeBottomPx = 1044, navBottomPx = navPx, densityPxPerDp = density)
        assertTrue(p1 >= dock)
        assertTrue(p2 >= p1)
        assertEquals(300.dp, p2)
    }

    @Test
    fun resolveTargetProfile_firstUltraActivationOverridesManualPresetCleanly() {
        val fiveBandInfo = AudioFx.BandInfo(
            centersHz = listOf(60, 230, 910, 3600, 14000),
            minMb = -1500,
            maxMb = 1500,
        )
        // Simulate prior manual/off state with residual preset/sliders
        val beforeClick = EqSettings(
            enabled = false,
            preset = "Vocal",
            bands = listOf(-200, 0, 400, 500, 200),
            bass = 200,
            virtualizer = 100,
            loudness = 50,
            ultra = false,
        )

        val offProfile = AudioFx.resolveTargetProfile(beforeClick, fiveBandInfo)
        assertFalse(offProfile.active)

        // First click enabling ULT Bass must resolve exact PRESET_ULTRA on 1st call identically to OFF->ON
        val firstUltraClick = AudioFx.resolveTargetProfile(beforeClick.copy(ultra = true), fiveBandInfo)
        val afterToggleOffOn = AudioFx.resolveTargetProfile(beforeClick.copy(ultra = false).copy(ultra = true), fiveBandInfo)

        assertTrue(firstUltraClick.active)
        assertEquals(AudioFx.curve(AudioFx.PRESET_ULTRA, fiveBandInfo), firstUltraClick.bandLevels)
        assertEquals(850, firstUltraClick.bassStrength)
        assertEquals(700, firstUltraClick.virtualizerStrength)
        assertEquals(450, firstUltraClick.loudnessGainMb)
        assertEquals(firstUltraClick, afterToggleOffOn)
    }
}
