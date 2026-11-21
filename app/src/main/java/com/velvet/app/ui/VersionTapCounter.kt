package com.velvet.app.ui

sealed interface VersionTapEvent {
    object Silent : VersionTapEvent
    data class StepsRemaining(val steps: Int) : VersionTapEvent
    object Unlocked : VersionTapEvent
    object AlreadyEnabled : VersionTapEvent
}

/**
 * Pure-Kotlin state machine for the 7-tap developer options unlock counter.
 *
 * Rules:
 * - Taps 1-3: Silent
 * - Taps 4-6: StepsRemaining(N) ("You are now N steps away from developer options")
 * - Tap 7: Unlocked (unlocks + opens Route.Developer)
 * - Resets after 2 s idle (gap between taps > 2000 ms)
 * - If already unlocked: AlreadyEnabled ("Developer options are already enabled")
 */
class VersionTapCounter(
    private val idleTimeoutMs: Long = 2000L,
    private val requiredTaps: Int = 7,
) {
    private var tapCount = 0
    private var lastTapTimestamp = 0L

    fun onVersionTapped(currentTimeMs: Long, isAlreadyUnlocked: Boolean): VersionTapEvent {
        if (isAlreadyUnlocked) {
            tapCount = 0
            return VersionTapEvent.AlreadyEnabled
        }

        if (lastTapTimestamp > 0L && (currentTimeMs - lastTapTimestamp) > idleTimeoutMs) {
            tapCount = 0
        }
        lastTapTimestamp = currentTimeMs
        tapCount++

        return when {
            tapCount < 4 -> VersionTapEvent.Silent
            tapCount in 4 until requiredTaps -> VersionTapEvent.StepsRemaining(requiredTaps - tapCount)
            tapCount >= requiredTaps -> {
                tapCount = 0
                VersionTapEvent.Unlocked
            }
            else -> VersionTapEvent.Silent
        }
    }

    fun reset() {
        tapCount = 0
        lastTapTimestamp = 0L
    }

    val currentTapCount: Int get() = tapCount
}
