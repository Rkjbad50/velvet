package com.velvet.app.playback

import com.velvet.app.data.Song
import com.velvet.app.diagnostics.QueueHealthMonitor

/**
 * Pure-Kotlin synchronization helper ensuring [QueueManager] always reconciles to ExoPlayer's
 * active media item after idle/background transitions, controller reconnects, or before manual
 * transport actions, while respecting in-flight async `syncToExoPlayer` IPC writes.
 */
object QueuePlayerSync {
    fun reconcileIfChanged(
        queueManager: QueueManager,
        playerSong: Song?,
        source: String,
        pendingExpectedSongId: String? = null,
        pendingExpectedUntilMs: Long = 0L,
        nowMs: Long = 0L,
        force: Boolean = false,
        recordExpectedSingleStepMismatch: Boolean = true,
    ): ReconcileResult? {
        if (playerSong == null || playerSong.id.isBlank()) return null
        if (!force && pendingExpectedSongId != null && nowMs < pendingExpectedUntilMs) {
            if (playerSong.id != pendingExpectedSongId) {
                return null
            }
        }
        val prevCurrentId = queueManager.current?.id
        if (prevCurrentId == playerSong.id) {
            return ReconcileResult(queueManager.state, ReconcileOutcome.NO_OP)
        }
        val result = queueManager.reconcileTo(playerSong.id, playerSong)
        val isExpectedSingleStepAdvance = (result.outcome == ReconcileOutcome.ADVANCED_USER_QUEUE ||
            result.outcome == ReconcileOutcome.ADVANCED_UP_NEXT) &&
            result.passedOverItems.isEmpty()
        val shouldRecordMismatch = result.outcome != ReconcileOutcome.NO_OP &&
            prevCurrentId != null &&
            (!isExpectedSingleStepAdvance || recordExpectedSingleStepMismatch)
        if (shouldRecordMismatch) {
            QueueHealthMonitor.recordStateMismatch(
                queueCurrentId = prevCurrentId,
                playerSongId = playerSong.id,
                source = source,
                passedOverCount = result.passedOverItems.size,
            )
        }
        return result
    }
}
