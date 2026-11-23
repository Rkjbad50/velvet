package com.velvet.app.playback

import com.velvet.app.data.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe session-scoped memory of tracks explicitly removed/dismissed by the user.
 * Prevents AutoplayPipeline and background refills from re-inserting dismissed songs
 * or duplicate recordings of the same dismissed track in the active listening session.
 */
object DismissedTrackRegistry {

    private val dismissedVideoIds = ConcurrentHashMap.newKeySet<String>()
    private val dismissedCanonicalKeys = ConcurrentHashMap.newKeySet<String>()
    private val dismissedGroupIds = ConcurrentHashMap.newKeySet<String>()

    data class DismissalRecord(
        val videoId: String,
        val canonicalKey: String,
        val canonicalGroupId: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val records = java.util.concurrent.ConcurrentLinkedDeque<DismissalRecord>()

    /**
     * Records a queue item that the user explicitly removed/swiped away.
     */
    fun recordDismissal(item: QueueItem) {
        recordDismissal(item.song)
    }

    /**
     * Records a song that the user explicitly dismissed.
     */
    fun recordDismissal(song: Song) {
        if (song.id.isNotBlank()) {
            dismissedVideoIds.add(song.id)
        }
        val key = canonicalKey(song.title, song.artist)
        if (key.isNotBlank()) {
            dismissedCanonicalKeys.add(key)
        }
        val groupId = SongIdentity.canonicalGroupId(song)
        if (groupId.isNotBlank()) {
            dismissedGroupIds.add(groupId)
        }

        records.addFirst(
            DismissalRecord(
                videoId = song.id,
                canonicalKey = key,
                canonicalGroupId = groupId,
            )
        )
        while (records.size > 200) {
            records.removeLast()
        }
    }

    /**
     * Returns true if [song] has been dismissed in the current active session.
     */
    fun isDismissed(song: Song): Boolean {
        if (song.id.isNotBlank() && dismissedVideoIds.contains(song.id)) return true
        val key = canonicalKey(song.title, song.artist)
        if (key.isNotBlank() && dismissedCanonicalKeys.contains(key)) return true
        val groupId = SongIdentity.canonicalGroupId(song)
        if (groupId.isNotBlank() && dismissedGroupIds.contains(groupId)) return true
        return false
    }

    fun isDismissed(videoId: String, key: String? = null, groupId: String? = null): Boolean {
        if (videoId.isNotBlank() && dismissedVideoIds.contains(videoId)) return true
        if (!key.isNullOrBlank() && dismissedCanonicalKeys.contains(key)) return true
        if (!groupId.isNullOrBlank() && dismissedGroupIds.contains(groupId)) return true
        return false
    }

    /**
     * Clears session dismissals when a genuinely new playback session is intentionally started.
     */
    fun clear() {
        dismissedVideoIds.clear()
        dismissedCanonicalKeys.clear()
        dismissedGroupIds.clear()
        records.clear()
    }

    val count: Int get() = dismissedVideoIds.size

    val allDismissedIds: Set<String> get() = dismissedVideoIds.toSet()
}
