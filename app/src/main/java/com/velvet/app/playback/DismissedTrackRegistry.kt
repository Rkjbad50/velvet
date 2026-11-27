package com.velvet.app.playback

import com.velvet.app.data.Song
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe session-scoped memory of tracks explicitly removed/dismissed by the user.
 * Prevents AutoplayRefillCoordinator, background refills, and QueueManager rebuilds from
 * re-inserting dismissed songs or duplicate recordings of the same dismissed track.
 */
object DismissedTrackRegistry {

    private val dismissedVideoIds = ConcurrentHashMap.newKeySet<String>()
    private val dismissedCanonicalKeys = ConcurrentHashMap.newKeySet<String>()
    private val dismissedGroupIds = ConcurrentHashMap.newKeySet<String>()
    private val dismissedRecordingIds = ConcurrentHashMap.newKeySet<String>()
    private val observers = CopyOnWriteArrayList<WeakReference<(Song) -> Unit>>()

    data class DismissalRecord(
        val videoId: String,
        val canonicalKey: String,
        val canonicalGroupId: String,
        val canonicalRecordingId: String = "",
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val records = java.util.concurrent.ConcurrentLinkedDeque<DismissalRecord>()

    /** Registers a weakly referenced listener notified whenever [recordDismissal] records a song. */
    fun addObserver(observer: (Song) -> Unit) {
        observers.removeAll { it.get() == null || it.get() === observer }
        observers.add(WeakReference(observer))
    }

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
        val recordingId = RecordingIdentityResolver.resolve(song).canonicalRecordingId
        if (recordingId.isNotBlank()) {
            dismissedRecordingIds.add(recordingId)
        }

        records.addFirst(
            DismissalRecord(
                videoId = song.id,
                canonicalKey = key,
                canonicalGroupId = groupId,
                canonicalRecordingId = recordingId,
            )
        )
        while (records.size > 200) {
            records.removeLast()
        }

        observers.forEach { ref ->
            val listener = ref.get()
            if (listener == null) {
                observers.remove(ref)
            } else {
                listener(song)
            }
        }
    }

    /**
     * Restores raw session dismissal identifiers from a persisted playback checkpoint.
     */
    fun restore(
        videoIds: Set<String> = emptySet(),
        groupIds: Set<String> = emptySet(),
        recordingIds: Set<String> = emptySet(),
    ) {
        clear()
        videoIds.filter { it.isNotBlank() }.forEach { dismissedVideoIds.add(it) }
        groupIds.filter { it.isNotBlank() }.forEach { dismissedGroupIds.add(it) }
        recordingIds.filter { it.isNotBlank() }.forEach { dismissedRecordingIds.add(it) }
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
        val recordingId = RecordingIdentityResolver.resolve(song).canonicalRecordingId
        if (recordingId.isNotBlank() && dismissedRecordingIds.contains(recordingId)) return true
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
        dismissedRecordingIds.clear()
        records.clear()
    }

    val count: Int get() = dismissedVideoIds.size

    val allDismissedIds: Set<String> get() = dismissedVideoIds.toSet()
    val allDismissedGroupIds: Set<String> get() = dismissedGroupIds.toSet()
    val allDismissedRecordingIds: Set<String> get() = dismissedRecordingIds.toSet()
}
