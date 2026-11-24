package com.velvet.app.recommendation.model

import kotlinx.serialization.Serializable

/**
 * Semantic music form/category distinct from broad genre (Blueprint §9).
 */
@Serializable
enum class MusicType {
    SONG,
    BHAJAN,
    AARTI,
    CHALISA,
    MANTRA,
    QAWALI,
    GHAZAL,
    SUFI,
    FILM_SONG,
    INDEPENDENT,
    CLASSICAL,
    INSTRUMENTAL,
    SOUNDTRACK,
    CHILDREN,
    DEVOTIONAL,
    PODCAST_OR_SPOKEN,
    UNKNOWN,
}

@Serializable
enum class TempoBucket {
    SLOW,
    MEDIUM,
    FAST,
    UNKNOWN,
}

@Serializable
data class ConfidenceValue<T>(
    val value: T,
    val confidence: Double,
    val provenance: String,
)

/**
 * Measured or model-predicted acoustic descriptors (null/empty until Tier-3 audio analysis runs).
 */
@Serializable
data class AudioFeatureVector(
    val bpm: Double? = null,
    val bpmConfidence: Double = 0.0,
    val energy: Double? = null,
    val valence: Double? = null,
    val danceability: Double? = null,
    val acousticness: Double? = null,
    val instrumentalness: Double? = null,
    val loudnessDb: Double? = null,
    val timbreVector: List<Float> = emptyList(),
    val embedding: List<Float> = emptyList(),
    val featureModelVersion: String? = null,
    val analysedAtMillis: Long? = null,
)

/**
 * Canonical confidence-bearing track representation consumed by filtering, ranking, and sequencing.
 * Missing metadata stays explicitly null/empty rather than fabricated.
 */
@Serializable
data class TrackFeatureRecord(
    val canonicalRecordingId: String,
    val providerItemId: String,
    val primaryArtistIds: Set<String> = emptySet(),
    val artistDisplayNames: List<String> = emptyList(),
    val albumOrReleaseId: String? = null,
    val title: String,
    val durationMs: Long?,
    val genreIds: Map<String, Double> = emptyMap(),
    val subgenreIds: Map<String, Double> = emptyMap(),
    val musicTypes: Map<MusicType, Double> = emptyMap(),
    val moodIds: Map<String, Double> = emptyMap(),
    val languageIds: Map<String, Double> = emptyMap(),
    val languageConfidence: Double = 0.0,
    val scriptIds: Set<String> = emptySet(),
    val eraStartYear: Int? = null,
    val eraBucket: String? = null,
    val eraConfidence: Double = 0.0,
    val isRemasterYearOnly: Boolean = false,
    val tempoBucket: TempoBucket = TempoBucket.UNKNOWN,
    val tempoConfidence: Double = 0.0,
    val regionIds: Set<String> = emptySet(),
    val versionKind: VersionKind = VersionKind.UNKNOWN,
    val releaseAudioClass: ReleaseAudioClass = ReleaseAudioClass.UNVERIFIED,
    val releaseConfidence: Double = 0.0,
    val sourceConfidence: Double = 0.0,
    val metadataCompleteness: Double = 0.0,
    val featureProvenance: Map<String, String> = emptyMap(),
    val audio: AudioFeatureVector? = null,
    val tags: Set<String> = emptySet(),
    val featureSchemaVersion: Int = CURRENT_FEATURE_SCHEMA_VERSION,
    val updatedAtMillis: Long = 0L,
) {
    /**
     * Returns a sanitized copy with all confidence scores normalized to [0.0, 1.0].
     */
    fun validated(): TrackFeatureRecord = copy(
        genreIds = genreIds.mapValues { (_, v) -> clampUnit(v) }.filterValues { it > 0.0 },
        subgenreIds = subgenreIds.mapValues { (_, v) -> clampUnit(v) }.filterValues { it > 0.0 },
        musicTypes = musicTypes.mapValues { (_, v) -> clampUnit(v) }.filterValues { it > 0.0 },
        moodIds = moodIds.mapValues { (_, v) -> clampUnit(v) }.filterValues { it > 0.0 },
        languageIds = languageIds.mapValues { (_, v) -> clampUnit(v) }.filterValues { it > 0.0 },
        languageConfidence = clampUnit(languageConfidence),
        eraConfidence = if (eraStartYear == null && eraBucket == null) 0.0 else clampUnit(eraConfidence),
        tempoConfidence = if (tempoBucket == TempoBucket.UNKNOWN && audio?.bpm == null) 0.0 else clampUnit(tempoConfidence),
        releaseConfidence = clampUnit(releaseConfidence),
        sourceConfidence = clampUnit(sourceConfidence),
        metadataCompleteness = clampUnit(metadataCompleteness),
    )

    companion object {
        const val CURRENT_FEATURE_SCHEMA_VERSION = 1

        private fun clampUnit(value: Double): Double =
            if (value.isNaN() || value.isInfinite()) 0.0 else value.coerceIn(0.0, 1.0)
    }
}
