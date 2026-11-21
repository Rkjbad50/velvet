package com.velvet.app.unit

import com.velvet.app.ui.VersionTapCounter
import com.velvet.app.ui.VersionTapEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VersionTapCounterTest {

    private lateinit var counter: VersionTapCounter

    @Before
    fun setUp() {
        counter = VersionTapCounter(idleTimeoutMs = 2000L, requiredTaps = 7)
    }

    @Test
    fun testTaps1To3AreSilent() {
        var time = 1000L
        assertEquals(VersionTapEvent.Silent, counter.onVersionTapped(time, isAlreadyUnlocked = false))
        time += 300L
        assertEquals(VersionTapEvent.Silent, counter.onVersionTapped(time, isAlreadyUnlocked = false))
        time += 300L
        assertEquals(VersionTapEvent.Silent, counter.onVersionTapped(time, isAlreadyUnlocked = false))
        assertEquals(3, counter.currentTapCount)
    }

    @Test
    fun testTaps4To6ShowStepsRemaining() {
        var time = 1000L
        for (i in 1..3) {
            counter.onVersionTapped(time, isAlreadyUnlocked = false)
            time += 200L
        }

        // Tap 4 -> 3 steps away
        val ev4 = counter.onVersionTapped(time, isAlreadyUnlocked = false)
        assertTrue(ev4 is VersionTapEvent.StepsRemaining)
        assertEquals(3, (ev4 as VersionTapEvent.StepsRemaining).steps)

        // Tap 5 -> 2 steps away
        time += 200L
        val ev5 = counter.onVersionTapped(time, isAlreadyUnlocked = false)
        assertTrue(ev5 is VersionTapEvent.StepsRemaining)
        assertEquals(2, (ev5 as VersionTapEvent.StepsRemaining).steps)

        // Tap 6 -> 1 step away
        time += 200L
        val ev6 = counter.onVersionTapped(time, isAlreadyUnlocked = false)
        assertTrue(ev6 is VersionTapEvent.StepsRemaining)
        assertEquals(1, (ev6 as VersionTapEvent.StepsRemaining).steps)
    }

    @Test
    fun testTap7UnlocksAndResetsCounter() {
        var time = 1000L
        for (i in 1..6) {
            counter.onVersionTapped(time, isAlreadyUnlocked = false)
            time += 200L
        }

        val ev7 = counter.onVersionTapped(time, isAlreadyUnlocked = false)
        assertEquals(VersionTapEvent.Unlocked, ev7)
        assertEquals(0, counter.currentTapCount)
    }

    @Test
    fun testIdleTimeoutResetsCounter() {
        var time = 1000L
        counter.onVersionTapped(time, isAlreadyUnlocked = false) // Tap 1
        time += 200L
        counter.onVersionTapped(time, isAlreadyUnlocked = false) // Tap 2
        time += 200L
        counter.onVersionTapped(time, isAlreadyUnlocked = false) // Tap 3
        time += 200L
        val ev4 = counter.onVersionTapped(time, isAlreadyUnlocked = false) // Tap 4
        assertTrue(ev4 is VersionTapEvent.StepsRemaining)

        // Wait 2500 ms (> 2000 ms idle timeout)
        time += 2500L
        val evAfterIdle = counter.onVersionTapped(time, isAlreadyUnlocked = false)
        // Must reset back to Tap 1 (Silent)
        assertEquals(VersionTapEvent.Silent, evAfterIdle)
        assertEquals(1, counter.currentTapCount)
    }

    @Test
    fun testAlreadyUnlockedShowsAlreadyEnabled() {
        val ev = counter.onVersionTapped(1000L, isAlreadyUnlocked = true)
        assertEquals(VersionTapEvent.AlreadyEnabled, ev)
        assertEquals(0, counter.currentTapCount)
    }
}
