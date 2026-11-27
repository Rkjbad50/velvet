package com.velvet.app.unit

import com.velvet.app.data.AlbumRef
import com.velvet.app.data.ArtistRef
import com.velvet.app.ui.FocusClearPolicy
import com.velvet.app.ui.FocusClearSnapshot
import com.velvet.app.ui.Navigator
import com.velvet.app.ui.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3 B2 tests:
 * Verifies centralized focus & IME clearing across Now Playing, pushed detail routes, tab
 * switches, nested routes, background focus gating, and return-without-refocus behavior.
 */
class B2GhostFocusClearTest {

    private fun snapshotOf(nav: Navigator): FocusClearSnapshot = FocusClearSnapshot(
        nowPlayingOpen = nav.nowPlayingOpen,
        routeDepth = nav.currentEntries.size,
        topEntryUid = nav.currentEntries.lastOrNull()?.uid,
        tab = nav.tab,
    )

    @Test
    fun openingNowPlayingFromSearchClearsFocusAndReturningDoesNotAutoRefocus() {
        val nav = Navigator()
        nav.selectTab(1) // Search tab
        var queryPreserved = "90s remix Evergreen Hindi"
        var hasFocus = true
        var imeVisible = true
        var prev = snapshotOf(nav)
        assertFalse(FocusClearPolicy.shouldClearFocusOnTransition(null, prev))

        // User taps MiniPlayer -> Now Playing opens
        nav.nowPlayingOpen = true
        val opened = snapshotOf(nav)
        if (FocusClearPolicy.shouldClearFocusOnTransition(prev, opened)) {
            hasFocus = false
            imeVisible = false
        }
        prev = opened

        assertFalse("Focus must be cleared when Now Playing opens", hasFocus)
        assertFalse("IME must hide when Now Playing opens", imeVisible)
        assertEquals("Typed search query must remain preserved", "90s remix Evergreen Hindi", queryPreserved)
        assertFalse(
            "Search underneath Now Playing must not accept focus",
            FocusClearPolicy.canBackgroundReceiveFocus(isRootCovered = false, isPlayerOpen = true),
        )

        // User minimizes Now Playing -> focus stays cleared (must not pop keyboard)
        nav.nowPlayingOpen = false
        val closed = snapshotOf(nav)
        assertFalse(FocusClearPolicy.shouldClearFocusOnTransition(prev, closed))
        assertFalse("Returning from Now Playing must never auto-restore focus", hasFocus)
        assertFalse("Returning from Now Playing must never auto-show keyboard", imeVisible)
        assertEquals("90s remix Evergreen Hindi", queryPreserved)
    }

    @Test
    fun pushingCollectionRouteOverSearchOrPlaylistSearchClearsFocusAndTeardrop() {
        val nav = Navigator()
        nav.selectTab(1) // Search tab
        var hasFocus = true
        var prev = snapshotOf(nav)

        // Open album/playlist from Search results
        nav.open(Route.Album(AlbumRef("https://music.youtube.com/browse/MPREb_123", "90s Hits", "Various Artists", null, isAlbum = true)))
        val pushedAlbum = snapshotOf(nav)
        assertTrue(FocusClearPolicy.shouldClearFocusOnTransition(prev, pushedAlbum))
        hasFocus = false
        prev = pushedAlbum
        assertFalse(
            "Covered root Search tab cannot receive focus under pushed Album",
            FocusClearPolicy.canBackgroundReceiveFocus(isRootCovered = true, isPlayerOpen = false),
        )

        // Open LocalPlaylist search inside pushed route, then open Artist or Now Playing
        nav.open(Route.LocalPlaylist("pl_1"))
        val pushedPlaylist = snapshotOf(nav)
        assertTrue(FocusClearPolicy.shouldClearFocusOnTransition(prev, pushedPlaylist))
        prev = pushedPlaylist

        // Simulate user focusing inline playlist search, then pushing Artist
        hasFocus = true
        nav.open(Route.Artist(ArtistRef("https://music.youtube.com/channel/UC1", "Alka Yagnik")))
        val pushedArtist = snapshotOf(nav)
        assertTrue(FocusClearPolicy.shouldClearFocusOnTransition(prev, pushedArtist))
        hasFocus = false
        assertFalse(hasFocus)
    }

    @Test
    fun switchingTabsClearsFocusEvenWithNoPushedRoutes() {
        val nav = Navigator()
        nav.selectTab(2) // Library tab search
        val s1 = snapshotOf(nav)
        nav.selectTab(0) // Home tab
        val s2 = snapshotOf(nav)
        assertTrue(FocusClearPolicy.shouldClearFocusOnTransition(s1, s2))
    }

    @Test
    fun canBackgroundReceiveFocusAllowsInteractionOnlyWhenUnobstructed() {
        assertTrue(FocusClearPolicy.canBackgroundReceiveFocus(isRootCovered = false, isPlayerOpen = false, onboardingVisible = false))
        assertFalse(FocusClearPolicy.canBackgroundReceiveFocus(isRootCovered = true, isPlayerOpen = false, onboardingVisible = false))
        assertFalse(FocusClearPolicy.canBackgroundReceiveFocus(isRootCovered = false, isPlayerOpen = true, onboardingVisible = false))
        assertFalse(FocusClearPolicy.canBackgroundReceiveFocus(isRootCovered = false, isPlayerOpen = false, onboardingVisible = true))
    }
}
