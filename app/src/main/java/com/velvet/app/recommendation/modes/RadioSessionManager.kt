package com.velvet.app.recommendation.modes

import com.velvet.app.data.Song
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationMode

/**
 * Persistent state for an active radio or similar-songs listening session.
 * Maintains anchor fidelity (45%) while evolving smoothly from genuinely completed/positive plays
 * (25% / 20% / 10%) and ignoring early skips so outlier skips never pull the station off course.
 */
data class RadioSessionState(
    val sessionId: String,
    val mode: RecommendationMode,
    val initialAnchorSong: Song,
    val recentPositiveTracks: List<Song> = emptyList(),
    val playedRecordingIds: Set<String> = emptySet(),
    val skippedRecordingIds: Set<String> = emptySet(),
    val skippedArtistCounts: Map<String, Int> = emptyMap(),
    val skippedGenreCounts: Map<String, Int> = emptyMap(),
    val selectedArtistId: String? = null,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
)

class RadioSessionManager(
    private val featureRepository: com.velvet.app.recommendation.features.TrackFeatureRepository =
        com.velvet.app.recommendation.features.TrackFeatureRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var currentSession: RadioSessionState? = null

    @Synchronized
    fun startSession(
        anchor: Song,
        mode: RecommendationMode,
        sessionId: String,
        selectedArtistId: String? = if (mode == RecommendationMode.ARTIST_RADIO) anchor.artist else null,
    ): RadioSessionState {
        val now = clock()
        val anchorRecId = RecordingIdentityResolver.resolve(anchor).canonicalRecordingId
        val next = RadioSessionState(
            sessionId = sessionId,
            mode = mode,
            initialAnchorSong = anchor,
            recentPositiveTracks = emptyList(),
            playedRecordingIds = setOf(anchorRecId),
            skippedRecordingIds = emptySet(),
            selectedArtistId = selectedArtistId,
            startedAtMillis = now,
            updatedAtMillis = now,
        )
        currentSession = next
        return next
    }

    @Synchronized
    fun clearSession() {
        currentSession = null
    }

    @Synchronized
    fun currentState(): RadioSessionState? = currentSession

    @Synchronized
    fun onTrackCompletedOrLiked(track: Song) {
        val session = currentSession ?: return
        val recId = RecordingIdentityResolver.resolve(track).canonicalRecordingId
        val anchorRecId = RecordingIdentityResolver.resolve(session.initialAnchorSong).canonicalRecordingId
        val nextPlayed = session.playedRecordingIds + recId
        // C6: Keep Artist Radio anchor strictly locked (never drift onto collaborators/neighbours)
        if (session.mode == RecommendationMode.ARTIST_RADIO || recId == anchorRecId || recId in session.skippedRecordingIds) {
            currentSession = session.copy(playedRecordingIds = nextPlayed, updatedAtMillis = clock())
            return
        }
        val filtered = session.recentPositiveTracks.filterNot {
            RecordingIdentityResolver.resolve(it).canonicalRecordingId == recId
        }
        val nextPositives = (filtered + track).takeLast(3)
        currentSession = session.copy(
            recentPositiveTracks = nextPositives,
            playedRecordingIds = nextPlayed,
            updatedAtMillis = clock(),
        )
    }

    @Synchronized
    fun onTrackSkippedEarly(track: Song) {
        val session = currentSession ?: return
        val recId = RecordingIdentityResolver.resolve(track).canonicalRecordingId
        val feat = featureRepository.getCachedOrExtract(track)
        val nextArtistCounts = session.skippedArtistCounts.toMutableMap()
        ArtistStationPolicy.extractAllArtistKeys(track, feat).forEach { ak ->
            nextArtistCounts[ak] = (nextArtistCounts[ak] ?: 0) + 1
        }
        val nextGenreCounts = session.skippedGenreCounts.toMutableMap()
        feat.genreIds.filterValues { it >= 0.45 }.keys.forEach { g ->
            nextGenreCounts[g] = (nextGenreCounts[g] ?: 0) + 1
        }
        currentSession = session.copy(
            skippedRecordingIds = session.skippedRecordingIds + recId,
            skippedArtistCounts = nextArtistCounts,
            skippedGenreCounts = nextGenreCounts,
            recentPositiveTracks = session.recentPositiveTracks.filterNot {
                RecordingIdentityResolver.resolve(it).canonicalRecordingId == recId
            },
            updatedAtMillis = clock(),
        )
    }

    @Synchronized
    fun sessionSkippedArtistCounts(): Map<String, Int> = currentSession?.skippedArtistCounts.orEmpty()

    @Synchronized
    fun sessionSkippedGenreCounts(): Map<String, Int> = currentSession?.skippedGenreCounts.orEmpty()

    /**
     * Ordered seeds: [0] = initialAnchor (45%), [1] = most recent positive (25%),
     * [2] = 2nd recent positive (20%), [3] = 3rd recent positive (10%).
     */
    @Synchronized
    fun evolvedSeedSongs(fallbackSeed: Song): List<Song> {
        val session = currentSession ?: return listOf(fallbackSeed)
        if (session.mode == RecommendationMode.ARTIST_RADIO) {
            return listOf(session.initialAnchorSong)
        }
        return buildList {
            add(session.initialAnchorSong)
            addAll(session.recentPositiveTracks.reversed().take(3))
        }.distinctBy { it.id }
    }

    /**
     * Returns decaying normalized seed weights matching Blueprint §22 (45 / 25 / 20 / 10).
     */
    @Synchronized
    fun evolvedSeedWeights(fallbackSeed: Song): Map<String, Double> {
        val seeds = evolvedSeedSongs(fallbackSeed)
        if (seeds.size <= 1) return mapOf(seeds.first().id to 1.0)
        val rawWeights = listOf(0.45, 0.25, 0.20, 0.10).take(seeds.size)
        val sum = rawWeights.sum().coerceAtLeast(1e-6)
        return seeds.mapIndexed { idx, s -> s.id to (rawWeights[idx] / sum) }.toMap()
    }
}
