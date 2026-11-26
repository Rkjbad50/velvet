package com.velvet.app.recommendation.candidates

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.recommendation.identity.RecordingIdentityResolver

/**
 * Rolling in-memory pool cache of 100..200 verified tracks replenished across
 * /next continuation pages, chained track completions, and candidate generators (C4).
 */
class RollingCandidatePool(
    val maxPoolSize: Int = MAX_POOL_SIZE,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Song>()

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() = synchronized(lock) { entries.clear() }

    /**
     * Merges fresh verified songs into the rolling buffer, evicting oldest entries above [maxPoolSize].
     */
    fun merge(
        songs: List<Song>,
        excludedIds: Set<String> = emptySet(),
        excludedRecIds: Set<String> = emptySet(),
        excludedCompositionKeys: Set<String> = emptySet(),
    ): Int = synchronized(lock) {
        if (songs.isEmpty()) return@synchronized 0
        var added = 0
        val existingRecs = entries.values.mapTo(HashSet()) { RecordingIdentityResolver.resolve(it).canonicalRecordingId }
        val existingComps = entries.values.mapNotNullTo(HashSet()) {
            RecordingIdentityResolver.canonicalCompositionTitleKey(it).takeIf { k -> k.isNotBlank() }
        }
        for (song in songs) {
            if (song.id.isBlank() || song.title.isBlank()) continue
            if (song.id in excludedIds || song.id in entries) continue
            val recId = RecordingIdentityResolver.resolve(song).canonicalRecordingId
            val grpId = SongIdentity.canonicalGroupId(song)
            val compKey = RecordingIdentityResolver.canonicalCompositionTitleKey(song)
            if (recId in excludedRecIds || grpId in excludedRecIds || recId in existingRecs) continue
            if (compKey.isNotBlank() && (compKey in excludedCompositionKeys || compKey in existingComps)) continue

            entries[song.id] = song
            existingRecs.add(recId)
            if (compKey.isNotBlank()) existingComps.add(compKey)
            added++
        }
        while (entries.size > maxPoolSize) {
            val firstKey = entries.keys.firstOrNull() ?: break
            entries.remove(firstKey)
        }
        added
    }

    /**
     * Draws up to [maxCount] verified eligible songs from the rolling pool while evicting consumed
     * or now-excluded tracks.
     */
    fun drainEligible(
        maxCount: Int,
        excludedIds: Set<String>,
        excludedRecIds: Set<String>,
        excludedCompositionKeys: Set<String>,
        predicate: (Song) -> Boolean = { true },
    ): List<Song> = synchronized(lock) {
        if (maxCount <= 0 || entries.isEmpty()) return@synchronized emptyList()
        val picked = ArrayList<Song>(maxCount)
        val pickedIds = HashSet<String>()
        val seenRecs = HashSet<String>(excludedRecIds)
        val seenComps = HashSet<String>(excludedCompositionKeys)
        val toRemove = ArrayList<String>()

        for ((id, song) in entries) {
            val recId = RecordingIdentityResolver.resolve(song).canonicalRecordingId
            val grpId = SongIdentity.canonicalGroupId(song)
            val compKey = RecordingIdentityResolver.canonicalCompositionTitleKey(song)
            val alreadySeen = id in excludedIds ||
                recId in seenRecs ||
                grpId in seenRecs ||
                (compKey.isNotBlank() && compKey in seenComps)

            if (alreadySeen) {
                toRemove.add(id)
                continue
            }
            if (picked.size < maxCount && predicate(song)) {
                picked.add(song)
                pickedIds.add(id)
                seenRecs.add(recId)
                seenRecs.add(grpId)
                if (compKey.isNotBlank()) seenComps.add(compKey)
                toRemove.add(id)
            }
        }
        toRemove.forEach { entries.remove(it) }
        picked
    }

    companion object {
        const val MIN_TARGET_POOL_SIZE = 100
        const val MAX_POOL_SIZE = 200
    }
}
