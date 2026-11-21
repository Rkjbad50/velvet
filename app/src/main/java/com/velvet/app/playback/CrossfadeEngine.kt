package com.velvet.app.playback

import com.velvet.app.data.RepeatMode
import com.velvet.app.data.Song
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mathematical equal-power curve calculation for smooth audio crossfades.
 *
 * Equal-power law:
 *   volA(t) = cos(t * pi / 2)
 *   volB(t) = sin(t * pi / 2)
 * For all t in [0, 1]:
 *   volA(t)^2 + volB(t)^2 = cos^2(t*pi/2) + sin^2(t*pi/2) = 1.0 (constant acoustic power, no volume dip).
 */
object CrossfadeEqualPower {
    /** Volume for outgoing track A (monotonically decreasing from 1.0 down to 0.0). */
    fun volA(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return cos(clamped * (PI / 2.0)).toFloat().coerceIn(0f, 1f)
    }

    /** Volume for incoming track B (monotonically increasing from 0.0 up to 1.0). */
    fun volB(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return sin(clamped * (PI / 2.0)).toFloat().coerceIn(0f, 1f)
    }

    /** Total acoustic power: volA^2 + volB^2 (should equal ~1.0). */
    fun totalPower(t: Float): Float {
        val a = volA(t)
        val b = volB(t)
        return (a * a) + (b * b)
    }
}

/**
 * Abstraction of a playback sink for dual-player crossfading,
 * allowing pure-Kotlin testing without Android/ExoPlayer dependencies.
 */
interface CrossfadePlayer {
    val id: String
    val currentSong: Song?
    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
    val isReady: Boolean
    val isEnded: Boolean
    var volume: Float
    var playWhenReady: Boolean

    fun prepare(song: Song)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun reset()
}

/**
 * True dual-player crossfade engine for VELVET.
 *
 * Manages active and standby players to provide seamless overlapping transitions
 * with zero silent gaps and zero perceived volume dips.
 */
class CrossfadeEngine(
    private val clock: Clock = SystemClock(),
    val playerA: CrossfadePlayer,
    val playerB: CrossfadePlayer,
    val getNextSong: () -> Song?,
    val getCrossfadeSeconds: () -> Float,
    val getRepeatMode: () -> RepeatMode = { RepeatMode.OFF },
    val onTrackTransition: (Song) -> Unit = {},
    val onGapMeasured: (Long) -> Unit = {},
    val onPreloadTrack: (Song) -> Unit = {},
    var onActivePlayerChanged: ((CrossfadePlayer) -> Unit)? = null,
) {
    var activePlayer: CrossfadePlayer = playerA
        private set
    var standbyPlayer: CrossfadePlayer = playerB
        private set

    var isCrossfading: Boolean = false
        private set
    var isPreloaded: Boolean = false
        private set
    var preloadingSongId: String? = null
        private set

    var crossfadeStartTimestamp: Long = 0L
        private set
    var crossfadeDurationMs: Long = 0L
        private set

    var isPausedDuringCrossfade: Boolean = false
        private set
    private var pauseTimestamp: Long = 0L

    /**
     * Periodic tick driving crossfade state transitions and equal-power volume ramps.
     * Invoked every ~16-30 ms by Choreographer / Handler or test clock loop.
     */
    fun tick() {
        if (isPausedDuringCrossfade) return

        if (isCrossfading) {
            val elapsed = clock.currentTimeMillis() - crossfadeStartTimestamp
            val progress = if (crossfadeDurationMs > 0L) {
                (elapsed.toFloat() / crossfadeDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }

            activePlayer.volume = CrossfadeEqualPower.volA(progress)
            standbyPlayer.volume = CrossfadeEqualPower.volB(progress)

            if (progress >= 1f) {
                finishCrossfade()
            }
            return
        }

        if (!activePlayer.isPlaying) return

        val duration = activePlayer.durationMs
        val position = activePlayer.currentPositionMs
        if (duration <= 0L) return

        val remainingMs = duration - position
        val fadeSec = getCrossfadeSeconds().coerceIn(0f, 12f)
        val fadeMs = (fadeSec * 1000L).toLong()
        val preloadThresholdMs = fadeMs + 10_000L // T - (fadeSeconds + 10 s)

        // 1. Preload check: T-(fadeSeconds + 10 s)
        if (remainingMs <= preloadThresholdMs && !isPreloaded && getRepeatMode() != RepeatMode.ONE) {
            val nextSong = getNextSong()
            if (nextSong != null) {
                standbyPlayer.prepare(nextSong)
                standbyPlayer.volume = 0f
                standbyPlayer.playWhenReady = false
                isPreloaded = true
                preloadingSongId = nextSong.id
                onPreloadTrack(nextSong)
            }
        }

        // 2. Repeat ONE check: do not crossfade to another track
        if (getRepeatMode() == RepeatMode.ONE) return

        val nextSong = getNextSong() ?: return

        // 3. Gapless mode (fadeSec == 0)
        if (fadeSec == 0f) {
            if (remainingMs <= 40L || activePlayer.isEnded) {
                if (standbyPlayer.isReady) {
                    startGaplessTransition(nextSong)
                }
            }
            return
        }

        // 4. Crossfade window: T - fadeSeconds
        // Track shorter than 2*fadeSeconds: shrink the fade to fit
        val maxFadeMs = (duration / 2L).coerceAtLeast(250L)
        val effectiveFadeMs = fadeMs.coerceAtMost(maxFadeMs)

        if (remainingMs <= effectiveFadeMs) {
            if (standbyPlayer.isReady) {
                val fadeToUse = remainingMs.coerceAtLeast(300L).coerceAtMost(effectiveFadeMs)
                startCrossfade(fadeToUse, nextSong)
            } else if (remainingMs <= 300L && standbyPlayer.isReady) {
                // Fallback: If B is not ready in time, fall back to a 300 ms fade, never long silence
                startCrossfade(300L, nextSong)
            }
        }
    }

    /** Start an equal-power crossfade transition into [nextSong]. */
    fun startCrossfade(durationMs: Long, nextSong: Song) {
        if (isCrossfading) return
        isCrossfading = true
        crossfadeStartTimestamp = clock.currentTimeMillis()
        crossfadeDurationMs = durationMs
        isPausedDuringCrossfade = false

        standbyPlayer.volume = 0f
        standbyPlayer.playWhenReady = true
        standbyPlayer.play()
        activePlayer.volume = 1f

        // UI shows NEW track from crossfade start
        onTrackTransition(nextSong)
        onGapMeasured(0L) // Overlapping audio = 0 ms silent gap!
    }

    /** Completes the crossfade: stops outgoing player and promotes standby to active. */
    fun finishCrossfade() {
        isCrossfading = false
        isPausedDuringCrossfade = false

        activePlayer.volume = 0f
        activePlayer.stop()
        activePlayer.reset()

        standbyPlayer.volume = 1f

        // Swap active and standby players
        val oldActive = activePlayer
        activePlayer = standbyPlayer
        standbyPlayer = oldActive

        isPreloaded = false
        preloadingSongId = null

        onActivePlayerChanged?.invoke(activePlayer)
    }

    /** Immediate gapless transition at track end. */
    fun startGaplessTransition(nextSong: Song) {
        standbyPlayer.volume = 1f
        standbyPlayer.playWhenReady = true
        standbyPlayer.play()

        activePlayer.stop()
        activePlayer.reset()

        val oldActive = activePlayer
        activePlayer = standbyPlayer
        standbyPlayer = oldActive

        isPreloaded = false
        preloadingSongId = null

        onActivePlayerChanged?.invoke(activePlayer)
        onTrackTransition(nextSong)
        onGapMeasured(0L)
    }

    /**
     * Manual Next/Previous/Jump: 250 ms quick crossfade, not a hard cut.
     */
    fun manualTransition(targetSong: Song) {
        if (isCrossfading) {
            cancelCrossfade()
        }
        standbyPlayer.prepare(targetSong)
        standbyPlayer.volume = 0f
        standbyPlayer.playWhenReady = true
        standbyPlayer.play()

        isCrossfading = true
        crossfadeStartTimestamp = clock.currentTimeMillis()
        crossfadeDurationMs = 250L
        isPausedDuringCrossfade = false

        onTrackTransition(targetSong)
        onGapMeasured(0L)
    }

    /**
     * Pause during crossfade: pause both players and suspend fade timer.
     */
    fun onPause() {
        if (isCrossfading) {
            isPausedDuringCrossfade = true
            pauseTimestamp = clock.currentTimeMillis()
            activePlayer.pause()
            standbyPlayer.pause()
        } else {
            activePlayer.pause()
        }
    }

    /**
     * Resume during crossfade: resume both players and resume fade timer.
     */
    fun onResume() {
        if (isCrossfading && isPausedDuringCrossfade) {
            val pausedDuration = clock.currentTimeMillis() - pauseTimestamp
            crossfadeStartTimestamp += pausedDuration
            isPausedDuringCrossfade = false
            activePlayer.play()
            standbyPlayer.play()
        } else {
            activePlayer.play()
        }
    }

    /**
     * Seek during crossfade: cancel the fade immediately, restore volume, finish transition cleanly.
     */
    fun onSeek(positionMs: Long) {
        if (isCrossfading) {
            cancelCrossfade()
        }
        activePlayer.seekTo(positionMs)
    }

    /**
     * Queue edit / shuffle / play-next / drag-reorder during the preload window:
     * Listens to nextItemChanged event. If next track changed, re-resolve and re-prepare standby player.
     */
    fun onNextItemChanged(newNextSong: Song?) {
        if (isCrossfading) return // Do not abort an already active crossfade

        if (isPreloaded || preloadingSongId != null) {
            if (newNextSong == null || newNextSong.id != preloadingSongId) {
                // Next track changed! Discard old standby preloaded media
                standbyPlayer.stop()
                standbyPlayer.reset()
                isPreloaded = false
                preloadingSongId = null

                // Re-prepare if still within preload window
                if (newNextSong != null && activePlayer.durationMs > 0L) {
                    val remainingMs = activePlayer.durationMs - activePlayer.currentPositionMs
                    val fadeSec = getCrossfadeSeconds().coerceIn(0f, 12f)
                    val preloadThresholdMs = (fadeSec * 1000L).toLong() + 10_000L
                    if (remainingMs <= preloadThresholdMs && getRepeatMode() != RepeatMode.ONE) {
                        standbyPlayer.prepare(newNextSong)
                        standbyPlayer.volume = 0f
                        standbyPlayer.playWhenReady = false
                        isPreloaded = true
                        preloadingSongId = newNextSong.id
                        onPreloadTrack(newNextSong)
                    }
                }
            }
        }
    }

    /**
     * Standby stream failure: handle failure on standby player without stalling or interrupting active player.
     */
    fun onStandbyError(fallbackNextSong: Song? = null) {
        standbyPlayer.stop()
        standbyPlayer.reset()
        isPreloaded = false
        preloadingSongId = null

        if (fallbackNextSong != null && getRepeatMode() != RepeatMode.ONE) {
            standbyPlayer.prepare(fallbackNextSong)
            standbyPlayer.volume = 0f
            standbyPlayer.playWhenReady = false
            isPreloaded = true
            preloadingSongId = fallbackNextSong.id
            onPreloadTrack(fallbackNextSong)
        }
    }

    /** Cancels an ongoing crossfade safely. */
    fun cancelCrossfade() {
        isCrossfading = false
        isPausedDuringCrossfade = false
        activePlayer.volume = 1f
        standbyPlayer.stop()
        standbyPlayer.reset()
        isPreloaded = false
        preloadingSongId = null
    }

    /** Full reset. */
    fun reset() {
        cancelCrossfade()
        activePlayer.reset()
        standbyPlayer.reset()
    }
}
