package com.velvet.app.recommendation.model

import com.velvet.app.data.Song

/**
 * Typed request carrying seed songs, queue/history exclusions, active radio/playlist context,
 * explicit user constraints, and cancellation/session boundaries across all recommendation surfaces.
 */
data class RecommendationRequest(
    val requestId: String,
    val mode: RecommendationMode,
    val seedSongs: List<Song> = emptyList(),
    val seedRecordingIds: List<String> = emptyList(),
    val currentSong: Song? = null,
    val currentRecordingId: String? = null,
    val recentSongs: List<Song> = emptyList(),
    val recentRecordingIds: List<String> = emptyList(),
    val queueSongs: List<Song> = emptyList(),
    val queueRecordingIds: Set<String> = emptySet(),
    val userQueueSongs: List<Song> = emptyList(),
    val userQueueRecordingIds: Set<String> = emptySet(),
    val excludedRecordingIds: Set<String> = emptySet(),
    val excludedProviderIds: Set<String> = emptySet(),
    val selectedArtistId: String? = null,
    val selectedGenreIds: Set<String> = emptySet(),
    val selectedMoodIds: Set<String> = emptySet(),
    val requestedLanguageIds: Set<String> = emptySet(),
    val locale: String? = null,
    val playlistContextId: String? = null,
    val radioSessionId: String? = null,
    val limit: Int = 20,
    val noveltyTarget: Double? = null,
    val allowExplicitUserIntentOverride: Boolean = false,
    val requireVerifiedOriginalAudio: Boolean = true,
    val allowMixedLanguages: Boolean = false,
    val allowNearDuplicateUnplayedUploads: Boolean = false,
    val widenEra: Boolean = false,
    val widenMood: Boolean = false,
    val sessionVibe: com.velvet.app.recommendation.vibe.SessionVibe? = null,
    val artistLanguageMap: Map<String, com.velvet.app.recommendation.vibe.ArtistLanguageProfile> = emptyMap(),
    val randomSeed: Long? = null,
    val sessionSkippedArtistCounts: Map<String, Int> = emptyMap(),
    val sessionSkippedGenreCounts: Map<String, Int> = emptyMap(),
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    val primarySeed: Song?
        get() = seedSongs.firstOrNull() ?: currentSong
}
