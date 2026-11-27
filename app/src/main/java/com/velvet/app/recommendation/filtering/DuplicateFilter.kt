package com.velvet.app.recommendation.filtering

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.RecommendationRequest

data class DuplicateFilterResult(
    val kept: List<CandidateTrack>,
    val removedAlreadyKnownCount: Int,
    val removedDuplicateUploadCount: Int,
)

/**
 * Removes seed/queued/recent duplicates and collapses equivalent uploads of the exact same
 * studio recording while preserving alternate versions cleanly when distinct.
 */
class DuplicateFilter {

    fun filter(
        candidates: List<CandidateTrack>,
        request: RecommendationRequest,
        extraExcludedSongs: List<Song> = emptyList(),
    ): DuplicateFilterResult {
        if (candidates.isEmpty()) {
            return DuplicateFilterResult(emptyList(), 0, 0)
        }

        val referenceSongs = (
            request.seedSongs +
                listOfNotNull(request.currentSong) +
                request.queueSongs +
                request.userQueueSongs +
                request.recentSongs +
                extraExcludedSongs
            ).distinctBy { it.id }

        val excludedProviderIds = (
            request.excludedProviderIds +
                referenceSongs.map { it.id }
            ).toSet()

        val excludedRecordingIds = (
            request.excludedRecordingIds +
                request.seedRecordingIds +
                listOfNotNull(request.currentRecordingId) +
                request.queueRecordingIds +
                request.userQueueRecordingIds +
                request.recentRecordingIds +
                referenceSongs.map { RecordingIdentityResolver.resolve(it).canonicalRecordingId } +
                referenceSongs.map { SongIdentity.canonicalGroupId(it) }
            ).toSet()

        val blockSameCompositionTitle = !request.allowExplicitUserIntentOverride
        var removedKnown = 0
        val fresh = mutableListOf<CandidateTrack>()

        for (candidate in candidates) {
            val song = candidate.song
            val recId = candidate.identity.canonicalRecordingId
            val legacyGroup = SongIdentity.canonicalGroupId(song)

            val matchesExcludedId = song.id in excludedProviderIds ||
                recId in excludedRecordingIds ||
                legacyGroup in excludedRecordingIds ||
                referenceSongs.any { ref ->
                    RecordingIdentityResolver.isSameRecording(ref, song) ||
                        (blockSameCompositionTitle && RecordingIdentityResolver.isSameCompositionTitle(ref, song))
                }

            if (matchesExcludedId) {
                removedKnown++
                continue
            }
            fresh.add(candidate)
        }

        // Group fresh candidates by same recording identity & composition title
        // so Autoplay/Similar Songs never emit multiple singers/versions of the same song title
        val clusters = mutableListOf<MutableList<CandidateTrack>>()
        for (item in fresh) {
            val matched = clusters.find { cluster ->
                cluster.any { existing ->
                    existing.identity.canonicalRecordingId == item.identity.canonicalRecordingId ||
                        RecordingIdentityResolver.isSameRecording(existing.song, item.song) ||
                        (blockSameCompositionTitle && RecordingIdentityResolver.isSameCompositionTitle(existing.song, item.song))
                }
            }
            if (matched != null) {
                matched.add(item)
            } else {
                clusters.add(mutableListOf(item))
            }
        }

        var removedUploads = 0
        val winners = mutableListOf<CandidateTrack>()
        for (cluster in clusters) {
            if (cluster.size == 1) {
                winners.add(cluster.first())
                continue
            }
            val orderedCluster = cluster
                .distinctBy { it.song.id }
                .sortedWith(
                    compareByDescending<CandidateTrack> {
                        if (request.requireVerifiedOriginalAudio) it.identity.releaseClassification.isVerifiedOriginalAudio else true
                    }.thenByDescending { it.identity.releaseClassification.audioClass.qualityTier }
                        .thenByDescending { it.sourceEvidence.size },
                )
            val chosenSong = if (request.requireVerifiedOriginalAudio) {
                RecordingIdentityResolver.selectBestVerifiedOriginalAudio(orderedCluster.map { it.song })
                    ?: orderedCluster.first().song
            } else {
                orderedCluster.first().song
            }
            val primaryWinner = orderedCluster.firstOrNull { it.song.id == chosenSong.id } ?: orderedCluster.first()
            val mergedEvidence = cluster
                .flatMap { it.sourceEvidence }
                .distinctBy { "${it.sourceId}:${it.rank}" }
            if (request.allowNearDuplicateUnplayedUploads) {
                // Step 1 gate degradation: preserve up to 1 additional unplayed alternate verified upload of the SAME recording
                // (never allow different-artist covers/compositions of the same title).
                val keptFromCluster = buildList {
                    add(primaryWinner.copy(sourceEvidence = mergedEvidence))
                    orderedCluster
                        .filter { cand ->
                            cand.song.id != primaryWinner.song.id &&
                                (cand.identity.canonicalRecordingId == primaryWinner.identity.canonicalRecordingId ||
                                    RecordingIdentityResolver.isSameRecording(primaryWinner.song, cand.song))
                        }
                        .take(1)
                        .forEach { add(it) }
                }
                removedUploads += (cluster.size - keptFromCluster.size).coerceAtLeast(0)
                winners.addAll(keptFromCluster)
            } else {
                removedUploads += (cluster.size - 1)
                winners.add(primaryWinner.copy(sourceEvidence = mergedEvidence))
            }
        }

        return DuplicateFilterResult(
            kept = winners,
            removedAlreadyKnownCount = removedKnown,
            removedDuplicateUploadCount = removedUploads,
        )
    }
}
