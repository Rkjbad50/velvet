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
            removedUploads += (cluster.size - 1)
            val songs = cluster.map { it.song }
            val chosenSong = if (request.requireVerifiedOriginalAudio) {
                RecordingIdentityResolver.selectBestVerifiedOriginalAudio(songs) ?: cluster.first().song
            } else {
                cluster.maxByOrNull { it.identity.releaseClassification.audioClass.qualityTier }?.song ?: cluster.first().song
            }
            val chosenTrack = cluster.first { it.song.id == chosenSong.id }
            val mergedEvidence = cluster
                .flatMap { it.sourceEvidence }
                .distinctBy { "${it.sourceId}:${it.rank}" }
            winners.add(chosenTrack.copy(sourceEvidence = mergedEvidence))
        }

        return DuplicateFilterResult(
            kept = winners,
            removedAlreadyKnownCount = removedKnown,
            removedDuplicateUploadCount = removedUploads,
        )
    }
}
