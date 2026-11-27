package com.velvet.app.recommendation.sequencing

import com.velvet.app.recommendation.model.RecommendationMode

/**
 * Mode-aware sequencing policy controlling pacing, diversity cooldown windows,
 * maximum batch concentration, and bounded beam search width.
 */
data class SequenceConfig(
    val targetCount: Int,
    val artistCooldownWindow: Int,
    val albumCooldownWindow: Int,
    val maxArtistCountInBatch: Int,
    val maxSlowOrSadStreak: Int,
    val allowBridgeTransition: Boolean,
    val beamWidth: Int,
    val candidateLookaheadLimit: Int,
    val transitionWeight: Double,
    val minAcceptableRankScore: Double = -0.15,
    val widenEra: Boolean = false,
    val widenMood: Boolean = false,
)

object SequenceConfigs {

    fun forMode(mode: RecommendationMode, limit: Int): SequenceConfig {
        val safeLimit = limit.coerceIn(1, 50)
        return when (mode) {
            RecommendationMode.SIMILAR_SONGS -> SequenceConfig(
                targetCount = safeLimit,
                artistCooldownWindow = 2,
                albumCooldownWindow = 2,
                maxArtistCountInBatch = maxOf(2, safeLimit / 4),
                maxSlowOrSadStreak = 4,
                allowBridgeTransition = true,
                beamWidth = 4,
                candidateLookaheadLimit = 36,
                transitionWeight = 0.28,
            )

            RecommendationMode.SONG_RADIO -> SequenceConfig(
                targetCount = safeLimit,
                artistCooldownWindow = 3,
                albumCooldownWindow = 3,
                maxArtistCountInBatch = maxOf(2, safeLimit / 5),
                maxSlowOrSadStreak = 3,
                allowBridgeTransition = true,
                beamWidth = 4,
                candidateLookaheadLimit = 40,
                transitionWeight = 0.34,
            )

            RecommendationMode.AUTOPLAY_REFILL -> SequenceConfig(
                targetCount = safeLimit,
                artistCooldownWindow = 3,
                albumCooldownWindow = 3,
                maxArtistCountInBatch = maxOf(2, safeLimit / 5),
                maxSlowOrSadStreak = 3,
                allowBridgeTransition = true,
                beamWidth = 4,
                candidateLookaheadLimit = 36,
                transitionWeight = 0.36,
            )

            RecommendationMode.ARTIST_RADIO -> SequenceConfig(
                targetCount = safeLimit,
                // Selected artist dominates Artist Radio, but still avoid consecutive same-album dumps
                artistCooldownWindow = 0,
                albumCooldownWindow = 2,
                maxArtistCountInBatch = safeLimit,
                maxSlowOrSadStreak = 4,
                allowBridgeTransition = true,
                beamWidth = 4,
                candidateLookaheadLimit = 36,
                transitionWeight = 0.30,
            )

            RecommendationMode.NEXT_SONG -> SequenceConfig(
                targetCount = safeLimit,
                artistCooldownWindow = 2,
                albumCooldownWindow = 2,
                maxArtistCountInBatch = maxOf(2, safeLimit / 3),
                maxSlowOrSadStreak = 3,
                allowBridgeTransition = true,
                beamWidth = 3,
                candidateLookaheadLimit = 28,
                transitionWeight = 0.38,
            )

            RecommendationMode.HOME_SONGS,
            RecommendationMode.HOME_RADIOS,
            RecommendationMode.HOME_PLAYLISTS,
            RecommendationMode.PERSONALIZED_PLAYLIST -> SequenceConfig(
                targetCount = safeLimit,
                artistCooldownWindow = 3,
                albumCooldownWindow = 3,
                maxArtistCountInBatch = maxOf(2, safeLimit / 5),
                maxSlowOrSadStreak = 3,
                allowBridgeTransition = true,
                beamWidth = 4,
                candidateLookaheadLimit = 40,
                transitionWeight = 0.32,
            )
        }
    }
}
