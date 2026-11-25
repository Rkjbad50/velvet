package com.velvet.app.unit

import androidx.compose.ui.geometry.Offset
import com.velvet.app.data.GitHubRelease
import com.velvet.app.data.ReleaseCheck
import com.velvet.app.data.UpdatePayload
import com.velvet.app.data.AppUpdateState
import com.velvet.app.ui.NavOrigin
import com.velvet.app.ui.NavState
import com.velvet.app.ui.Navigator
import com.velvet.app.ui.OriginRegistry
import com.velvet.app.ui.OriginTransformCalculator
import com.velvet.app.ui.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OriginMotionAndNavTest {

    @Test
    fun settingsButtonOriginStartsAndSubmergesIntoTopRightButtonAndSettlesAtZero() {
        // Simulate top-right Settings button on a 1080x2400 display
        val settingsOrigin = NavOrigin(
            left = 900f,
            top = 110f,
            right = 1044f,
            bottom = 254f,
            cornerRadiusDp = 24f,
        )
        val w = 1080f
        val h = 2400f

        val atZero = OriginTransformCalculator.compute(
            origin = settingsOrigin,
            containerWidth = w,
            containerHeight = h,
            progress = 0f,
            isExiting = false,
        )
        // Center of Settings button = (972, 182), Screen center = (540, 1200) -> dx=+432, dy=-1018
        assertEquals(432f, atZero.translationX, 0.5f)
        assertEquals(-1018f, atZero.translationY, 0.5f)
        assertEquals(0.16f, atZero.scale, 0.001f)
        assertEquals(0f, atZero.shellAlpha, 0.001f)
        assertTrue(atZero.cornerDp > 24f)

        // Mid-flight opening sweeps cleanly along curved arc rather than straight calculator diagonal
        val midOpen = OriginTransformCalculator.compute(
            origin = settingsOrigin,
            containerWidth = w,
            containerHeight = h,
            progress = 0.5f,
            isExiting = false,
        )
        assertTrue(abs(midOpen.translationX / 432f) < abs(midOpen.translationY / -1018f))
        assertEquals(1f, midOpen.shellAlpha, 0.001f)
        assertTrue(midOpen.contentAlpha > 0.9f)

        // Exiting fades inner UI text cleanly before pill finishes melting into Settings button
        val lateExit = OriginTransformCalculator.compute(
            origin = settingsOrigin,
            containerWidth = w,
            containerHeight = h,
            progress = 0.20f,
            isExiting = true,
        )
        assertEquals(0f, lateExit.contentAlpha, 0.001f)
        assertEquals(1f, lateExit.shellAlpha, 0.001f)

        // Settled state rests with exact zero translation, full scale, and zero clipping corners
        val settled = OriginTransformCalculator.compute(
            origin = settingsOrigin,
            containerWidth = w,
            containerHeight = h,
            progress = 1f,
            isExiting = false,
        )
        assertEquals(0f, settled.translationX, 0.001f)
        assertEquals(0f, settled.translationY, 0.001f)
        assertEquals(1f, settled.scale, 0.001f)
        assertEquals(0f, settled.cornerDp, 0.001f)
        assertEquals(1f, settled.contentAlpha, 0.001f)
    }

    @Test
    fun missingOriginFallsBackGracefullyWithoutNaN() {
        val fallback = OriginTransformCalculator.compute(
            origin = null,
            containerWidth = 1080f,
            containerHeight = 2400f,
            progress = 0.5f,
            isExiting = false,
            fallbackOffsetYPx = 80f,
        )
        assertFalse(fallback.translationX.isNaN())
        assertFalse(fallback.translationY.isNaN())
        assertEquals(0f, fallback.translationX, 0.001f)
        assertEquals(40f, fallback.translationY, 0.001f)
        assertEquals(0.96f, fallback.scale, 0.001f)
    }

    @Test
    fun originRegistryPrefersExactContainerAndNavigatorPreservesStackOrigins() {
        val registry = OriginRegistry()
        registry.recordPointerDown(Offset(500f, 900f))
        val gearOrigin = NavOrigin(900f, 110f, 1044f, 254f, 24f)
        registry.recordContainer(gearOrigin)

        val nav = Navigator()
        nav.originProvider = { registry.consumeBestOrigin() }

        nav.open(Route.Settings)
        val settingsEntry = nav.currentEntries.single()
        assertEquals(Route.Settings, settingsEntry.route)
        assertEquals(gearOrigin, settingsEntry.origin)

        // Next tap with only pointer down uses smooth tap pill fallback
        registry.recordPointerDown(Offset(400f, 600f))
        nav.open(Route.Equalizer)
        assertEquals(2, nav.currentEntries.size)
        val eqOrigin = nav.currentEntries.last().origin
        assertNotNull(eqOrigin)
        assertEquals(400f, eqOrigin!!.centerX, 0.1f)
        assertEquals(600f, eqOrigin.centerY, 0.1f)

        // Closing Equalizer leaves Settings entry and its gearOrigin completely intact
        nav.back()
        assertEquals(1, nav.currentEntries.size)
        assertEquals(Route.Settings, nav.currentEntries.single().route)
        assertEquals(gearOrigin, nav.currentEntries.single().origin)

        // State serialization remains clean across tab/back navigation
        val saved: NavState = nav.state()
        assertEquals(listOf(Route.Settings), saved.stacks[0])
        val restored = Navigator(saved)
        assertEquals(Route.Settings, restored.current)
        assertNull(restored.currentEntries.single().origin)
    }

    @Test
    fun releaseCheckHandlesUpgradedBuildNumberWithin276Safely() {
        val release = GitHubRelease(
            tag_name = "v2.7.6",
            name = "Version 2.7.6",
            html_url = "https://github.com/Rkjbad50/velvet/releases/tag/v2.7.6",
        )
        val sameBuildPayload = UpdatePayload("2.7.6", 220, "abc", "velvet.apk")
        val newerBuildPayload = UpdatePayload("2.7.6", 221, "def", "velvet.apk")

        val upToDate = ReleaseCheck.evaluateAppUpdate(release, sameBuildPayload, installedVersionCode = 220, installedVersionName = "2.7.6")
        assertTrue(upToDate is AppUpdateState.UpToDate)

        val available = ReleaseCheck.evaluateAppUpdate(release, newerBuildPayload, installedVersionCode = 220, installedVersionName = "2.7.6")
        assertTrue(available is AppUpdateState.UpdateAvailable)
    }

    @Test
    fun releaseCheckOffersOfficialV11UpdateOverInstalledV276Build() {
        val officialRelease = GitHubRelease(
            tag_name = "v1.1",
            name = "Version 1.1",
            html_url = "https://github.com/Rkjbad50/velvet/releases/tag/v1.1",
        )
        val v11Payload = UpdatePayload("1.1", 245, "deadbeef", "velvet.apk")
        val state = ReleaseCheck.evaluateAppUpdate(
            release = officialRelease,
            updatePayload = v11Payload,
            installedVersionCode = 240,
            installedVersionName = "2.7.6",
        )
        assertTrue(state is AppUpdateState.UpdateAvailable)
    }
}
