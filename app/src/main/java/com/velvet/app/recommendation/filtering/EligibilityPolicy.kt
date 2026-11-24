package com.velvet.app.recommendation.filtering

import com.velvet.app.playback.SongIdentity
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.EligibilityDecision
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.model.VersionKind
import com.velvet.app.recommendation.profile.UserTasteSnapshot

interface EligibilityPolicy {
    fun evaluate(
        candidate: CandidateTrack,
        request: RecommendationRequest,
        profile: UserTasteSnapshot,
        seedFeatures: TrackFeatureRecord? = null,
    ): EligibilityDecision
}

/**
 * Hard safety, content-type, language, fatigue, and Strict Original Studio Audio eligibility gate.
 * Items rejected here cannot be rescued by any ranking weight.
 */
class DefaultEligibilityPolicy : EligibilityPolicy {

    override fun evaluate(
        candidate: CandidateTrack,
        request: RecommendationRequest,
        profile: UserTasteSnapshot,
        seedFeatures: TrackFeatureRecord?,
    ): EligibilityDecision {
        val song = candidate.song
        val reasons = mutableListOf<String>()
        val recId = candidate.identity.canonicalRecordingId
        val legacyGroup = SongIdentity.canonicalGroupId(song)

        if (song.id.isBlank() || song.title.isBlank()) {
            reasons.add("invalid_provider_identity")
            return EligibilityDecision(eligible = false, reasons = reasons, confidence = 1.0)
        }

        // 1. Strict Original Studio Audio Only enforcement (no OMV, lyric video, visualizer, live, or UNVERIFIED leaks)
        val releaseCls = candidate.identity.releaseClassification
        val audioClass = releaseCls.audioClass
        if (request.requireVerifiedOriginalAudio) {
            when {
                audioClass == ReleaseAudioClass.REJECTED_ALTERNATE_VERSION -> {
                    val allowedByExplicitAlternateMode = request.allowExplicitUserIntentOverride &&
                        seedFeatures != null &&
                        seedFeatures.versionKind == candidate.identity.versionKind &&
                        candidate.identity.versionKind != VersionKind.UNKNOWN
                    if (!allowedByExplicitAlternateMode) {
                        reasons.add("rejected_alternate_version:${candidate.identity.versionKind.name.lowercase()}")
                    }
                }
                audioClass.isExplicitlyRejected -> {
                    reasons.add(releaseCls.reasonCode)
                }
                audioClass == ReleaseAudioClass.UNVERIFIED -> {
                    reasons.add("unverified_release_not_allowed:${releaseCls.reasonCode}")
                }
            }
        } else if (audioClass == ReleaseAudioClass.REJECTED_NON_MUSIC) {
            reasons.add(releaseCls.reasonCode)
        }

        // 2. Exclusions: explicit request exclusions, profile dislikes/hides, fatigue, active session removal
        if (song.id in request.excludedProviderIds ||
            recId in request.excludedRecordingIds ||
            legacyGroup in request.excludedRecordingIds
        ) {
            reasons.add("explicitly_excluded")
        }
        if (profile.isExplicitlyDisliked(song)) {
            reasons.add("user_disliked_or_hidden")
        }
        if (profile.isFatiguedOrRemovedInSession(song)) {
            reasons.add("fatigued_or_session_removed")
        }

        // 3. Spoken / podcast hard gate
        val features = candidate.features
        val topMusicType = features?.musicTypes?.maxByOrNull { it.value }?.key
        if (topMusicType == MusicType.PODCAST_OR_SPOKEN) {
            reasons.add("rejected_spoken_or_podcast")
        }

        // 4. Content-type-aware duration sanity (devotional/classical/raga allow long-form audio)
        val durSec = song.durationSec
        if (durSec == 0L || (durSec in 1L..44L)) {
            reasons.add("duration_too_short")
        } else if (durSec > 0L) {
            val isLongFormEligible = topMusicType in setOf(
                MusicType.CHALISA,
                MusicType.AARTI,
                MusicType.BHAJAN,
                MusicType.MANTRA,
                MusicType.DEVOTIONAL,
                MusicType.CLASSICAL,
                MusicType.INSTRUMENTAL,
                MusicType.SUFI,
                MusicType.QAWALI,
            )
            val maxDurSec = if (isLongFormEligible) 1_200L else 660L
            if (durSec > maxDurSec) {
                reasons.add("duration_exceeds_limit:$maxDurSec")
            }
        }

        // 5. Devotional / category integrity guardrail
        if (seedFeatures != null && features != null) {
            val seedType = seedFeatures.musicTypes.maxByOrNull { it.value }?.key ?: MusicType.UNKNOWN
            val candType = topMusicType ?: MusicType.UNKNOWN
            val seedIsDevotional = GenreOntology.DEVOTIONAL in seedFeatures.genreIds
            if (seedIsDevotional) {
                val compatibility = GenreOntology.musicTypeCompatibility(seedType, candType)
                val candHasPartyMood = (features.moodIds["party_celebratory"] ?: 0.0) >= 0.5
                val candIsDevotionalOrClassical = GenreOntology.DEVOTIONAL in features.genreIds ||
                    MusicType.CLASSICAL in features.musicTypes
                if (!candIsDevotionalOrClassical || compatibility <= 0.1 || candHasPartyMood) {
                    reasons.add("devotional_category_mismatch")
                }
                val seedIsHindiOrSanskrit = ("hi" in seedFeatures.languageIds || "sa" in seedFeatures.languageIds) &&
                    GenreOntology.SOUTH_INDIAN !in seedFeatures.genreIds
                val candIsSouthIndic = GenreOntology.SOUTH_INDIAN in features.genreIds
                val userWantsSouthLanguage = profile.explicit.preferredLanguageIds.any { it in setOf("ta", "te", "ml", "kn") } ||
                    request.requestedLanguageIds.any { it in setOf("ta", "te", "ml", "kn") }
                if (seedIsHindiOrSanskrit && candIsSouthIndic && !userWantsSouthLanguage) {
                    reasons.add("devotional_regional_mismatch")
                }
            }
        }

        // 6. Explicit requested language hard gate (high-confidence conflicts only; unknown stays uncertain, not vetoed)
        if (request.requestedLanguageIds.isNotEmpty() && !request.allowExplicitUserIntentOverride && features != null) {
            if (features.languageConfidence >= 0.70 && features.languageIds.isNotEmpty()) {
                val overlaps = features.languageIds.keys.any { it in request.requestedLanguageIds }
                if (!overlaps) {
                    reasons.add("explicit_language_mismatch")
                }
            }
        }

        return EligibilityDecision(
            eligible = reasons.isEmpty(),
            reasons = reasons,
            confidence = releaseCls.confidence,
        )
    }
}
