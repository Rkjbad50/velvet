package com.velvet.app.recommendation.ranking

import com.velvet.app.recommendation.model.RecommendationMode

/**
 * Calibrated, normalised weights for a single recommendation mode.
 * Direct artist similarity ([selectedArtistWeight]) is strictly 0.0 outside explicit ARTIST_RADIO.
 */
data class RankerWeights(
    val typeFit: Double,
    val genreFit: Double,
    val moodFit: Double,
    val audioFit: Double,
    val tempoEnergyFit: Double,
    val languageFit: Double,
    val eraFit: Double,
    val contextFit: Double,
    val personalTasteFit: Double,
    val searchIntentFit: Double,
    val selectedArtistWeight: Double,
    val artistAffinityFit: Double,
    val qualityFit: Double,
    val noveltyFit: Double,
    val penaltyScale: Double = 1.0,
    val modelVersion: String = PHASE5_MODEL_VERSION,
) {
    companion object {
        const val PHASE5_MODEL_VERSION = "v2.7.5-norm-ranker-p5"
    }
}

object ModeRankerConfigs {

    fun forMode(mode: RecommendationMode): RankerWeights = when (mode) {
        RecommendationMode.SIMILAR_SONGS -> RankerWeights(
            typeFit = 0.24,
            genreFit = 0.26,
            moodFit = 0.16,
            audioFit = 0.14,
            tempoEnergyFit = 0.08,
            languageFit = 0.06,
            eraFit = 0.05,
            contextFit = 0.06,
            personalTasteFit = 0.06,
            searchIntentFit = 0.02,
            selectedArtistWeight = 0.0, // Music-first invariant: zero direct artist match bonus
            artistAffinityFit = 0.02,
            qualityFit = 0.06,
            noveltyFit = 0.04,
            penaltyScale = 1.0,
        )

        RecommendationMode.SONG_RADIO -> RankerWeights(
            typeFit = 0.22,
            genreFit = 0.24,
            moodFit = 0.15,
            audioFit = 0.12,
            tempoEnergyFit = 0.07,
            languageFit = 0.06,
            eraFit = 0.05,
            contextFit = 0.06,
            personalTasteFit = 0.10,
            searchIntentFit = 0.04,
            selectedArtistWeight = 0.0, // Music-first invariant
            artistAffinityFit = 0.03,
            qualityFit = 0.06,
            noveltyFit = 0.07,
            penaltyScale = 1.0,
        )

        RecommendationMode.ARTIST_RADIO -> RankerWeights(
            typeFit = 0.18,
            genreFit = 0.16,
            moodFit = 0.08,
            audioFit = 0.08,
            tempoEnergyFit = 0.05,
            languageFit = 0.04,
            eraFit = 0.03,
            contextFit = 0.05,
            personalTasteFit = 0.06,
            searchIntentFit = 0.02,
            selectedArtistWeight = 0.36, // Explicit selected artist intent
            artistAffinityFit = 0.06,
            qualityFit = 0.06,
            noveltyFit = 0.04,
            penaltyScale = 1.0,
        )

        RecommendationMode.NEXT_SONG -> RankerWeights(
            typeFit = 0.22,
            genreFit = 0.22,
            moodFit = 0.16,
            audioFit = 0.12,
            tempoEnergyFit = 0.10,
            languageFit = 0.06,
            eraFit = 0.04,
            contextFit = 0.08,
            personalTasteFit = 0.10,
            searchIntentFit = 0.08,
            selectedArtistWeight = 0.0,
            artistAffinityFit = 0.03,
            qualityFit = 0.06,
            noveltyFit = 0.05,
            penaltyScale = 1.1,
        )

        RecommendationMode.AUTOPLAY_REFILL -> RankerWeights(
            typeFit = 0.22,
            genreFit = 0.23,
            moodFit = 0.15,
            audioFit = 0.12,
            tempoEnergyFit = 0.08,
            languageFit = 0.07,
            eraFit = 0.05,
            contextFit = 0.07,
            personalTasteFit = 0.10,
            searchIntentFit = 0.05,
            selectedArtistWeight = 0.0,
            artistAffinityFit = 0.03,
            qualityFit = 0.06,
            noveltyFit = 0.06,
            penaltyScale = 1.1,
        )

        RecommendationMode.HOME_SONGS,
        RecommendationMode.HOME_RADIOS,
        RecommendationMode.HOME_PLAYLISTS,
        RecommendationMode.PERSONALIZED_PLAYLIST -> RankerWeights(
            typeFit = 0.16,
            genreFit = 0.18,
            moodFit = 0.14,
            audioFit = 0.10,
            tempoEnergyFit = 0.06,
            languageFit = 0.07,
            eraFit = 0.05,
            contextFit = 0.06,
            personalTasteFit = 0.18,
            searchIntentFit = 0.07,
            selectedArtistWeight = 0.0,
            artistAffinityFit = 0.05,
            qualityFit = 0.07,
            noveltyFit = 0.09,
            penaltyScale = 1.0,
        )
    }
}
