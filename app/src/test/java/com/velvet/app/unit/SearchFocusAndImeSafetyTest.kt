package com.velvet.app.unit

import androidx.compose.ui.unit.dp
import com.velvet.app.ui.DP_SPRING_DAMPING_RATIO
import com.velvet.app.ui.SearchBodyMode
import com.velvet.app.ui.VelvetMotion
import com.velvet.app.ui.nonNegativeDpPadding
import com.velvet.app.ui.resolveSearchBodyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit checks for Chunk 1:
 * 1) Critically damped / no-bounce Dp springs & non-negative padding clamping (prevents IME tap crash).
 * 2) Search body mode resolution gating Recent searches behind search bar focus.
 */
class SearchFocusAndImeSafetyTest {

    @Test
    fun dpStandardSpringIsNoBounceAndCannotOvershootBelowZero() {
        assertTrue(
            "DpStandard dampingRatio must be >= 1f so dockHeight -> 0.dp never overshoots negative",
            VelvetMotion.Springs.DpStandard.dampingRatio >= 1f,
        )
        assertTrue(
            "DP_SPRING_DAMPING_RATIO must be >= 1f",
            DP_SPRING_DAMPING_RATIO >= 1f,
        )
    }

    @Test
    fun nonNegativeDpPaddingClampsNegativeInputsAndPreservesPositiveValues() {
        assertEquals(0.dp, nonNegativeDpPadding((-12).dp))
        assertEquals(0.dp, nonNegativeDpPadding((-0.01).dp))
        assertEquals(0.dp, nonNegativeDpPadding(0.dp))
        assertEquals(76.dp, nonNegativeDpPadding(76.dp))
    }

    @Test
    fun resolveSearchBodyModeGatesRecentSearchesBehindSearchFocus() {
        assertEquals(
            SearchBodyMode.IDLE_HOME,
            resolveSearchBodyMode(
                showingResults = false,
                query = "",
                isSearchFocused = false,
            ),
        )

        assertEquals(
            SearchBodyMode.FOCUSED_RECENTS,
            resolveSearchBodyMode(
                showingResults = false,
                query = "",
                isSearchFocused = true,
            ),
        )

        assertEquals(
            SearchBodyMode.FOCUSED_RECENTS,
            resolveSearchBodyMode(
                showingResults = false,
                query = "   ",
                isSearchFocused = true,
            ),
        )

        assertEquals(
            SearchBodyMode.SUGGESTIONS,
            resolveSearchBodyMode(
                showingResults = false,
                query = "arijit",
                isSearchFocused = true,
            ),
        )
        assertEquals(
            SearchBodyMode.SUGGESTIONS,
            resolveSearchBodyMode(
                showingResults = false,
                query = "arijit",
                isSearchFocused = false,
            ),
        )

        assertEquals(
            SearchBodyMode.RESULTS,
            resolveSearchBodyMode(
                showingResults = true,
                query = "arijit",
                isSearchFocused = false,
            ),
        )
        assertEquals(
            SearchBodyMode.RESULTS,
            resolveSearchBodyMode(
                showingResults = true,
                query = "",
                isSearchFocused = true,
            ),
        )
    }
}
