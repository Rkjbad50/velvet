package com.velvet.app.recommendation.model

import kotlinx.serialization.Serializable

/**
 * Granular musical arrangement/release kind separating distinct performances
 * (original studio, live, acoustic, remix, cover, slowed, etc.).
 */
@Serializable
enum class VersionKind {
    ORIGINAL_STUDIO,
    OFFICIAL_MUSIC_VIDEO,
    LYRIC_VIDEO,
    VISUALIZER,
    LIVE,
    ACOUSTIC,
    UNPLUGGED,
    REMIX,
    REPRISE,
    REMASTER,
    INSTRUMENTAL,
    COVER,
    KARAOKE,
    SLOWED,
    SPED_UP,
    MASHUP,
    UNKNOWN,
}

/**
 * Strict release-level audio authenticity classification enforcing VELVET's
 * Original Studio Music Audio Only rule for recommendation-generated queues.
 */
@Serializable
enum class ReleaseAudioClass(
    val isVerifiedOriginalAudio: Boolean,
    val isExplicitlyRejected: Boolean,
    val qualityTier: Int,
) {
    VERIFIED_TOPIC_AUDIO(true, false, 600),
    VERIFIED_LABEL_STUDIO_AUDIO(true, false, 560),
    VERIFIED_OST_AUDIO(true, false, 540),
    VERIFIED_STUDIO_AUDIO(true, false, 520),
    REJECTED_OFFICIAL_VIDEO(false, true, 0),
    REJECTED_LYRIC_VIDEO(false, true, 0),
    REJECTED_VISUALIZER(false, true, 0),
    REJECTED_LIVE(false, true, 0),
    REJECTED_ALTERNATE_VERSION(false, true, 0),
    REJECTED_COVER_OR_UGC(false, true, 0),
    REJECTED_NON_MUSIC(false, true, 0),
    UNVERIFIED(false, false, 100),
}

@Serializable
data class ReleaseClassification(
    val audioClass: ReleaseAudioClass,
    val confidence: Double,
    val evidence: Set<String> = emptySet(),
    val reasonCode: String,
) {
    val isVerifiedOriginalAudio: Boolean get() = audioClass.isVerifiedOriginalAudio
}

/**
 * Specific playable item reference from an external/local source provider.
 */
@Serializable
data class ProviderTrackRef(
    val provider: String,
    val providerItemId: String,
    val playbackLocator: String? = null,
    val uploaderOrChannel: String? = null,
    val verifiedChannel: Boolean = false,
    val isTopicChannel: Boolean = false,
    val fetchedAtMillis: Long = 0L,
)

@Serializable
data class MusicalWorkRef(
    val workId: String,
    val normalizedTitle: String,
    val comparisonKey: String,
    val primaryArtists: Set<String> = emptySet(),
    val albumOrWorkTitle: String? = null,
)

/**
 * Three-level music identity:
 * - Provider item ([providerRefs])
 * - Canonical recording/performance ([canonicalRecordingId], [versionKind], [releaseClassification])
 * - Underlying musical work/composition ([workId])
 */
@Serializable
data class TrackIdentity(
    val providerRefs: List<ProviderTrackRef>,
    val canonicalRecordingId: String,
    val workId: String,
    val musicBrainzRecordingId: String? = null,
    val isrc: String? = null,
    val confidence: Double,
    val versionKind: VersionKind,
    val releaseClassification: ReleaseClassification,
    val identityEvidence: Set<String> = emptySet(),
)
