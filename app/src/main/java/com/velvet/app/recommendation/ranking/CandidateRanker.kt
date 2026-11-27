package com.velvet.app.recommendation.ranking

import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RankScoreBreakdown
import com.velvet.app.recommendation.model.RankedCandidate
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationReason
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.model.TempoBucket
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

interface CandidateRanker {
    fun rank(
        candidates: List<CandidateTrack>,
        request: RecommendationRequest,
        profile: UserTasteSnapshot,
        seedFeatures: TrackFeatureRecord? = null,
    ): List<RankedCandidate>
}

/**
 * Normalised, confidence-aware, mode-configurable candidate ranker.
 *
 * Guarantees:
 * - Zero direct positive artist boost in normal Similar Songs / Song Radio / Autoplay.
 * - Zero OMV quality bonus (only verified original studio audio classes score quality credit).
 * - Missing features (BPM, era, language, audio embeddings) never generate false mismatches.
 * - Personal taste / artist affinity is capped and cannot override incompatible music type or genre.
 */
class DefaultCandidateRanker(
    private val configProvider: (RecommendationMode) -> RankerWeights = ModeRankerConfigs::forMode,
) : CandidateRanker {

    override fun rank(
        candidates: List<CandidateTrack>,
        request: RecommendationRequest,
        profile: UserTasteSnapshot,
        seedFeatures: TrackFeatureRecord?,
    ): List<RankedCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val weights = configProvider(request.mode)
        val seedSong = request.primarySeed
        val seedRecId = seedFeatures?.canonicalRecordingId

        val maxSourceCount = candidates.maxOfOrNull { it.sourceEvidence.size }?.coerceAtLeast(1) ?: 1

        val scored = candidates.map { cand ->
            val feat = cand.features
            val rawType = computeMusicTypeFit(seedFeatures, feat)
            val rawGenre = computeGenreFit(seedFeatures, feat, request)
            val rawMood = computeMoodFit(seedFeatures, feat, request)
            val rawAudio = computeAudioFit(seedFeatures, feat)
            val rawTempo = computeTempoFit(seedFeatures, feat)
            val rawLanguage = computeLanguageFit(seedFeatures, feat, request, profile)
            val rawEra = computeEraFit(seedFeatures, feat, request)
            val rawContext = computeContextFit(cand, seedFeatures, maxSourceCount)

            val compatibleMusic = rawType >= 0.0 && rawGenre >= -0.25 && rawMood >= -0.25
            val rawPersonalTaste = if (compatibleMusic) {
                computePersonalTasteFit(cand, feat, profile)
            } else 0.0
            val rawSearchIntent = if (compatibleMusic) {
                computeSearchIntentFit(cand, feat, profile)
            } else 0.0

            val selectedKeys = request.selectedArtistId
                ?.takeIf { it.isNotBlank() }
                ?.let { com.velvet.app.recommendation.vibe.ArtistLanguageMap.allArtistKeys(it) }
                ?: seedSong?.artist?.let { com.velvet.app.recommendation.vibe.ArtistLanguageMap.allArtistKeys(it) }
                    .orEmpty()
            val candArtistKeys = if (feat != null) {
                com.velvet.app.recommendation.modes.ArtistStationPolicy.extractAllArtistKeys(cand.song, feat)
            } else {
                com.velvet.app.recommendation.vibe.ArtistLanguageMap.allArtistKeys(cand.song.artist)
            }
            val selectedArtistNorm = request.selectedArtistId?.let(::normalizePrimaryArtist)
                ?: seedSong?.artist?.let(::normalizePrimaryArtist)
            val candPrimaryNorm = normalizePrimaryArtist(cand.song.artist)
            val isSamePrimaryArtist = !selectedArtistNorm.isNullOrBlank() && candPrimaryNorm.isNotBlank() &&
                (candPrimaryNorm == selectedArtistNorm || candPrimaryNorm.contains(selectedArtistNorm) || selectedArtistNorm.contains(candPrimaryNorm))
            val isFeaturedArtist = !isSamePrimaryArtist && selectedKeys.isNotEmpty() && candArtistKeys.any { it in selectedKeys }
            val rawSelectedArtist = when {
                request.mode != RecommendationMode.ARTIST_RADIO -> 0.0
                isSamePrimaryArtist -> 1.0
                isFeaturedArtist -> 0.90
                else -> 0.0
            }
            val rawPersonalArtist = if (compatibleMusic && feat != null) {
                feat.artistDisplayNames
                    .maxOfOrNull { profile.longTerm.artistAffinity(it) * 0.6 + profile.mediumTerm.artistAffinity(it) * 0.4 }
                    ?.coerceIn(-0.5, 1.0) ?: 0.0
            } else 0.0

            val rawQuality = computeQualityFit(cand, feat)
            val rawNovelty = computeNoveltyFit(cand, profile)

            val penalties = computePenalties(
                cand = cand,
                feat = feat,
                seed = seedFeatures,
                rawType = rawType,
                rawGenre = rawGenre,
                rawMood = rawMood,
                profile = profile,
                scale = weights.penaltyScale,
                req = request,
                rawSelectedArtist = rawSelectedArtist,
            )
            val totalPenalties = penalties.values.sum()

            val wType = rawType * weights.typeFit
            val wGenre = rawGenre * weights.genreFit
            val wMood = rawMood * weights.moodFit
            val wAudio = rawAudio * weights.audioFit
            val wTempo = rawTempo * weights.tempoEnergyFit
            val wLang = rawLanguage * weights.languageFit
            val wEra = rawEra * weights.eraFit
            val wContext = rawContext * weights.contextFit
            val wPersonal = rawPersonalTaste * weights.personalTasteFit
            val wSearch = rawSearchIntent * weights.searchIntentFit
            val wArtist = rawSelectedArtist * weights.selectedArtistWeight + rawPersonalArtist * weights.artistAffinityFit
            val wQuality = rawQuality * weights.qualityFit
            val wNovelty = rawNovelty * weights.noveltyFit

            val completeness = feat?.metadataCompleteness ?: 0.25
            val confMult = (0.70 + 0.30 * completeness).coerceIn(0.70, 1.0)
            val freshnessBonus = computeFreshnessBonus(cand, profile, request)
            val artistRadioOrderBonus = if (request.mode == RecommendationMode.ARTIST_RADIO && rawSelectedArtist > 0.0) {
                val bestRank = cand.sourceEvidence.mapNotNull { it.rank }.minOrNull()
                val popScore = bestRank?.let { 1.0 / (1.0 + it * 0.15) } ?: 0.5
                val recencyScore = feat?.eraStartYear?.let { ((it - 1960).coerceIn(0, 70) / 70.0) } ?: 0.5
                0.04 * popScore + 0.02 * recencyScore
            } else 0.0
            val rawPositiveSum = wType + wGenre + wMood + wAudio + wTempo + wLang + wEra +
                wContext + wPersonal + wSearch + wArtist + wQuality + wNovelty + freshnessBonus + artistRadioOrderBonus
            val total = sanitize((rawPositiveSum * confMult) - totalPenalties)

            val breakdown = RankScoreBreakdown(
                musicTypeFit = sanitize(wType),
                genreFit = sanitize(wGenre),
                moodFit = sanitize(wMood),
                audioFit = sanitize(wAudio),
                tempoEnergyFit = sanitize(wTempo),
                languageFit = sanitize(wLang),
                eraFit = sanitize(wEra),
                contextFit = sanitize(wContext),
                personalTasteFit = sanitize(wPersonal),
                searchIntentFit = sanitize(wSearch),
                artistAffinityFit = sanitize(wArtist),
                qualityFit = sanitize(wQuality),
                noveltyFit = sanitize(wNovelty + freshnessBonus),
                penalties = penalties.mapValues { (_, v) -> sanitize(v) },
                confidenceMultiplier = sanitize(confMult),
                total = total,
            )

            val reasons = buildReasons(cand, rawType, rawGenre, rawMood, rawTempo, rawSelectedArtist, rawPersonalTaste, rawQuality, penalties, breakdown, freshnessBonus)

            RankedCandidate(
                candidate = cand,
                rankScore = total,
                breakdown = breakdown,
                reasons = reasons,
            )
        }

        // Deterministic sort: highest score -> verified quality tier -> multi-source count -> lexical ID
        return scored.sortedWith(
            compareByDescending<RankedCandidate> { it.rankScore }
                .thenByDescending { it.candidate.identity.releaseClassification.audioClass.qualityTier }
                .thenByDescending { it.candidate.sourceEvidence.size }
                .thenBy { it.candidate.song.id },
        )
    }

    private fun computeMusicTypeFit(seed: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        if (seed == null || cand == null) return 0.0
        val seedEntry = seed.musicTypes.maxByOrNull { it.value } ?: return 0.0
        val candEntry = cand.musicTypes.maxByOrNull { it.value } ?: return 0.0
        if (seedEntry.key == MusicType.UNKNOWN || candEntry.key == MusicType.UNKNOWN) return 0.0
        val compat = GenreOntology.musicTypeCompatibility(seedEntry.key, candEntry.key)
        val pairConf = sqrt((seedEntry.value * candEntry.value).coerceIn(0.0, 1.0))
        return if (compat <= 0.10) {
            // Incompatible types penalised strongly
            -0.85 * pairConf
        } else {
            (compat * pairConf).coerceIn(0.0, 1.0)
        }
    }

    private fun computeGenreFit(
        seed: TrackFeatureRecord?,
        cand: TrackFeatureRecord?,
        req: RecommendationRequest,
    ): Double {
        if (cand == null) return 0.0
        val targetGenres = buildMap<String, Double> {
            seed?.genreIds?.forEach { (k, v) -> put(k, v) }
            req.selectedGenreIds.forEach { raw ->
                GenreOntology.canonicalize(raw)?.let { put(it, 1.0) }
            }
        }
        if (targetGenres.isEmpty() || cand.genreIds.isEmpty()) return 0.0

        var bestMatch = 0.0
        var bestWeight = 0.0
        for ((gSeed, cSeed) in targetGenres) {
            for ((gCand, cCand) in cand.genreIds) {
                val sim = GenreOntology.similarity(gSeed, gCand)
                val pairConf = sqrt((cSeed * cCand).coerceIn(0.0, 1.0))
                val weighted = sim * pairConf
                if (weighted > bestMatch) bestMatch = weighted
                if (pairConf > bestWeight) bestWeight = pairConf
            }
        }
        // Also check subgenres for sharper boost when exact subgenre matches
        if (seed != null && seed.subgenreIds.isNotEmpty() && cand.subgenreIds.isNotEmpty()) {
            val subBoost = seed.subgenreIds.maxOfOrNull { (sgA, ca) ->
                cand.subgenreIds.maxOfOrNull { (sgB, cb) ->
                    GenreOntology.similarity(sgA, sgB) * sqrt((ca * cb).coerceIn(0.0, 1.0))
                } ?: 0.0
            } ?: 0.0
            bestMatch = maxOf(bestMatch, subBoost)
        }
        return if (bestMatch == 0.0 && bestWeight >= 0.65) {
            -0.65 * bestWeight
        } else {
            bestMatch.coerceIn(0.0, 1.0)
        }
    }

    private fun computeMoodFit(seed: TrackFeatureRecord?, cand: TrackFeatureRecord?, req: RecommendationRequest): Double {
        if (seed == null || cand == null || seed.moodIds.isEmpty() || cand.moodIds.isEmpty()) return 0.0
        val allMoods = seed.moodIds.keys + cand.moodIds.keys
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (m in allMoods) {
            val a = seed.moodIds[m] ?: 0.0
            val b = cand.moodIds[m] ?: 0.0
            dot += a * b
            normA += a * a
            normB += b * b
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom > 0.0 && dot > 0.0) {
            return (dot / denom).coerceIn(0.0, 1.0)
        }
        val devotionalPartyClash = (seed.moodIds.containsKey("devotional_spiritual") && cand.moodIds.containsKey("party_celebratory")) ||
            (seed.moodIds.containsKey("party_celebratory") && cand.moodIds.containsKey("devotional_spiritual"))
        val secularClash = !req.widenMood && (
            (seed.moodIds.containsKey("sad_melancholic") && cand.moodIds.containsKey("party_celebratory")) ||
                (seed.moodIds.containsKey("party_celebratory") && cand.moodIds.containsKey("sad_melancholic"))
            )
        return if (devotionalPartyClash || secularClash) -0.45 else 0.0
    }

    private fun computeAudioFit(seed: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        val aVec = seed?.audio?.embedding
        val bVec = cand?.audio?.embedding
        if (aVec.isNullOrEmpty() || bVec.isNullOrEmpty() || aVec.size != bVec.size) return 0.0
        if (seed.audio.featureModelVersion != cand.audio.featureModelVersion) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in aVec.indices) {
            dot += aVec[i] * bVec[i]
            normA += aVec[i] * aVec[i]
            normB += bVec[i] * bVec[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0.0) (dot / denom).coerceIn(0.0, 1.0) else 0.0
    }

    private fun computeTempoFit(seed: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        if (seed == null || cand == null) return 0.0
        val bpmA = seed.audio?.bpm
        val bpmB = cand.audio?.bpm
        if (bpmA != null && bpmB != null && bpmA > 0.0 && bpmB > 0.0) {
            // Evaluate 1.0x, 0.5x half-time, and 2.0x double-time rhythmic alignment (Blueprint §30.2)
            val minLogDist = minOf(
                abs(ln(bpmB / bpmA)),
                abs(ln((bpmB * 2.0) / bpmA)),
                abs(ln((bpmB * 0.5) / bpmA)),
            )
            val conf = sqrt((seed.audio.bpmConfidence * cand.audio.bpmConfidence).coerceIn(0.0, 1.0))
            val sim = (1.0 - (minLogDist / 0.35)).coerceIn(-0.4, 1.0)
            return sim * conf
        }
        if (seed.tempoBucket == TempoBucket.UNKNOWN || cand.tempoBucket == TempoBucket.UNKNOWN) {
            // Missing BPM/tempo never creates a false mismatch
            return 0.0
        }
        val conf = minOf(seed.tempoConfidence, cand.tempoConfidence)
        return when {
            seed.tempoBucket == cand.tempoBucket -> 0.85 * conf
            (seed.tempoBucket == TempoBucket.FAST && cand.tempoBucket == TempoBucket.SLOW) ||
                (seed.tempoBucket == TempoBucket.SLOW && cand.tempoBucket == TempoBucket.FAST) -> -0.35 * conf
            else -> 0.25 * conf
        }
    }

    private fun computeLanguageFit(
        seed: TrackFeatureRecord?,
        cand: TrackFeatureRecord?,
        req: RecommendationRequest,
        profile: UserTasteSnapshot,
    ): Double {
        if (cand == null || cand.languageIds.isEmpty() || cand.languageConfidence <= 0.0) return 0.0
        val targets = buildSet {
            addAll(req.requestedLanguageIds)
            seed?.languageIds?.keys?.let { addAll(it) }
        }
        if (targets.isEmpty()) {
            val userLangMatch = cand.languageIds.keys.any { it in profile.explicit.preferredLanguageIds }
            return if (userLangMatch) 0.50 * cand.languageConfidence else 0.0
        }
        val overlap = cand.languageIds.entries.maxOfOrNull { (l, c) ->
            if (l in targets) c else 0.0
        } ?: 0.0
        if (overlap > 0.0) {
            return overlap * cand.languageConfidence
        }
        // Only softly down-rank if user is multilingual and knows that language
        val inUserLangs = cand.languageIds.keys.any { it in profile.explicit.preferredLanguageIds }
        return if (inUserLangs) 0.15 else -0.45 * cand.languageConfidence
    }

    private fun computeEraFit(seed: TrackFeatureRecord?, cand: TrackFeatureRecord?, req: RecommendationRequest): Double {
        if (seed == null || cand == null) return 0.0
        val conf = minOf(seed.eraConfidence, cand.eraConfidence)
        if (conf <= 0.0) return 0.0
        val yA = seed.eraStartYear
        val yB = cand.eraStartYear
        if (yA != null && yB != null) {
            val diff = abs(yA - yB)
            val sim = if (req.widenEra) {
                when {
                    diff <= 10 -> 0.90
                    diff <= 25 -> 0.50
                    else -> 0.10
                }
            } else {
                when {
                    diff <= 4 -> 1.0
                    diff <= 10 -> 0.70
                    diff <= 20 -> 0.30
                    else -> -0.25
                }
            }
            return sim * conf
        }
        if (seed.eraBucket != null && cand.eraBucket != null) {
            return when {
                seed.eraBucket == cand.eraBucket -> 0.80 * conf
                req.widenEra -> 0.25 * conf
                else -> 0.0
            }
        }
        return 0.0
    }

    private fun computeContextFit(
        cand: CandidateTrack,
        seed: TrackFeatureRecord?,
        maxSourceCount: Int,
    ): Double {
        val sameAlbum = if (!seed?.albumOrReleaseId.isNullOrBlank() &&
            !cand.features?.albumOrReleaseId.isNullOrBlank() &&
            seed?.albumOrReleaseId.equals(cand.features?.albumOrReleaseId, ignoreCase = true)
        ) 0.55 else 0.0

        val sourceAgreement = (cand.sourceEvidence.size.toDouble() / maxSourceCount.toDouble()).coerceIn(0.0, 1.0)
        val bestRankBonus = cand.sourceEvidence
            .mapNotNull { it.rank }
            .minOrNull()
            ?.let { r -> (1.0 / (1.0 + r * 0.25)) }
            ?: 0.5

        return (sameAlbum + 0.25 * sourceAgreement + 0.20 * bestRankBonus).coerceIn(0.0, 1.0)
    }

    private fun computePersonalTasteFit(
        cand: CandidateTrack,
        feat: TrackFeatureRecord?,
        profile: UserTasteSnapshot,
    ): Double {
        val recId = cand.identity.canonicalRecordingId
        var score = 0.0
        if (recId in profile.explicit.likedRecordingIds || cand.song.id in profile.explicit.likedProviderIds) {
            score += 0.45
        }
        if (recId in profile.explicit.savedPlaylistRecordingIds || cand.song.id in profile.explicit.savedPlaylistProviderIds) {
            score += 0.25
        }
        if (feat != null) {
            val genreTaste = feat.genreIds.entries.maxOfOrNull { (g, c) ->
                ((profile.longTerm.genreWeights[g] ?: 0.0) * 0.55 + (profile.mediumTerm.genreWeights[g] ?: 0.0) * 0.45) * c
            } ?: 0.0
            val moodTaste = feat.moodIds.entries.maxOfOrNull { (m, c) ->
                ((profile.longTerm.moodWeights[m] ?: 0.0) * 0.50 + (profile.mediumTerm.moodWeights[m] ?: 0.0) * 0.50) * c
            } ?: 0.0
            score += 0.30 * genreTaste + 0.20 * moodTaste
        }
        return score.coerceIn(-0.5, 1.0)
    }

    private fun computeSearchIntentFit(
        cand: CandidateTrack,
        feat: TrackFeatureRecord?,
        profile: UserTasteSnapshot,
    ): Double {
        val intent = profile.searchIntent
        val recBoost = intent.convertedRecordingBoosts[cand.identity.canonicalRecordingId] ?: 0.0
        val workBoost = intent.convertedWorkBoosts[cand.identity.workId] ?: 0.0
        val catBoost = feat?.genreIds?.keys?.maxOfOrNull { g -> intent.queryCategoryWeights[g] ?: 0.0 } ?: 0.0
        return maxOf(recBoost, workBoost, 0.4 * catBoost).coerceIn(0.0, 1.0)
    }

    private fun computeQualityFit(cand: CandidateTrack, feat: TrackFeatureRecord?): Double {
        // Strict Original Studio Audio Only: zero OMV/UNVERIFIED quality bonus
        val releaseScore = when (cand.identity.releaseClassification.audioClass) {
            ReleaseAudioClass.VERIFIED_TOPIC_AUDIO -> 0.85
            ReleaseAudioClass.VERIFIED_LABEL_STUDIO_AUDIO -> 0.78
            ReleaseAudioClass.VERIFIED_OST_AUDIO -> 0.75
            ReleaseAudioClass.VERIFIED_STUDIO_AUDIO -> 0.70
            else -> 0.0
        }
        val completenessBonus = 0.15 * (feat?.metadataCompleteness ?: 0.0)
        return (releaseScore + completenessBonus).coerceIn(0.0, 1.0)
    }

    private fun computeNoveltyFit(cand: CandidateTrack, profile: UserTasteSnapshot): Double {
        val seenBefore = cand.identity.canonicalRecordingId in profile.longTerm.recordingWeights ||
            cand.identity.canonicalRecordingId in profile.explicit.likedRecordingIds
        return if (!seenBefore) {
            profile.noveltyPreference.coerceIn(0.0, 1.0)
        } else {
            (1.0 - profile.noveltyPreference).coerceIn(0.0, 1.0) * 0.35
        }
    }

    private fun computeFreshnessBonus(
        cand: CandidateTrack,
        profile: UserTasteSnapshot,
        req: RecommendationRequest,
    ): Double {
        val now = profile.generatedAtMillis.takeIf { it > 0L } ?: req.createdAtMillis
        val lastPlayed = profile.lastPlayedMillis(cand.song, cand.identity.canonicalRecordingId) ?: return 0.0
        val elapsed = (now - lastPlayed).coerceAtLeast(0L)
        return if (elapsed >= 30L * 24L * 60L * 60L * 1000L) 0.10 else 0.0
    }

    private fun computePenalties(
        cand: CandidateTrack,
        feat: TrackFeatureRecord?,
        seed: TrackFeatureRecord?,
        rawType: Double,
        rawGenre: Double,
        rawMood: Double,
        profile: UserTasteSnapshot,
        scale: Double,
        req: RecommendationRequest,
        rawSelectedArtist: Double,
    ): Map<String, Double> {
        val map = linkedMapOf<String, Double>()
        val recId = cand.identity.canonicalRecordingId
        if (recId in profile.fatigue.dailyOverplayedRecordingIds) {
            map["daily_overplay"] = 0.28 * scale
        }
        profile.fatigue.singleSkipPenalties[recId]?.let { p ->
            if (p > 0.0) map["recent_single_skip"] = (p * 0.6).coerceAtMost(0.20) * scale
        }
        val artistPenalty = feat?.primaryArtistIds?.minOfOrNull { a ->
            profile.fatigue.repeatedArtistPenalty[a] ?: 0.0
        } ?: 0.0
        if (artistPenalty < 0.0) {
            map["repeated_artist_skip"] = abs(artistPenalty).coerceAtMost(0.35) * scale
        }
        if (rawSelectedArtist == 0.0) {
            val candArtistKeys = if (feat != null) {
                com.velvet.app.recommendation.modes.ArtistStationPolicy.extractAllArtistKeys(cand.song, feat)
            } else {
                com.velvet.app.recommendation.vibe.ArtistLanguageMap.allArtistKeys(cand.song.artist)
            }
            val sessionArtistPen = candArtistKeys.maxOfOrNull { ak ->
                maxOf(
                    profile.fatigue.sessionArtistSkipPenalties[ak] ?: 0.0,
                    ((req.sessionSkippedArtistCounts[ak] ?: 0) * 0.18).coerceAtMost(0.45),
                )
            } ?: 0.0
            if (sessionArtistPen > 0.0) {
                map["session_artist_skip"] = sessionArtistPen * scale
            }
            val sessionGenrePen = feat?.genreIds
                ?.filterValues { it >= 0.45 }
                ?.keys
                ?.maxOfOrNull { g ->
                    maxOf(
                        profile.fatigue.sessionGenreSkipPenalties[g] ?: 0.0,
                        ((req.sessionSkippedGenreCounts[g] ?: 0) * 0.14).coerceAtMost(0.35),
                    )
                } ?: 0.0
            if (sessionGenrePen > 0.0) {
                map["session_genre_skip"] = sessionGenrePen * scale
            }
        }
        val seedDur = seed?.durationMs
        val candDur = feat?.durationMs
        if (seedDur != null && candDur != null && seedDur > 0L && candDur > 0L) {
            if (abs(seedDur - candDur) > 150_000L) {
                map["duration_mismatch"] = 0.12 * scale
            }
        }
        if (rawType < -0.2) map["music_type_clash"] = 0.35 * scale
        if (rawGenre < -0.2) map["genre_clash"] = 0.25 * scale
        val devotionalPartyClash = (seed?.moodIds?.containsKey("devotional_spiritual") == true && feat?.moodIds?.containsKey("party_celebratory") == true) ||
            (feat?.moodIds?.containsKey("devotional_spiritual") == true && seed?.moodIds?.containsKey("party_celebratory") == true)
        if (rawMood < -0.2 && (!req.widenMood || devotionalPartyClash)) {
            map["mood_clash"] = 0.12 * scale
        }
        return map
    }

    private fun buildReasons(
        cand: CandidateTrack,
        rawType: Double,
        rawGenre: Double,
        rawMood: Double,
        rawTempo: Double,
        rawSelectedArtist: Double,
        rawPersonal: Double,
        rawQuality: Double,
        penalties: Map<String, Double>,
        breakdown: RankScoreBreakdown,
        freshnessBonus: Double = 0.0,
    ): List<RecommendationReason> = buildList {
        if (rawType > 0.5) add(RecommendationReason("TYPE_MATCH", "Matches seed musical category", breakdown.musicTypeFit))
        if (rawGenre > 0.5) add(RecommendationReason("GENRE_MATCH", "Matches musical genre and style", breakdown.genreFit))
        if (rawMood > 0.4) add(RecommendationReason("MOOD_MATCH", "Fits listening mood", breakdown.moodFit))
        if (rawTempo > 0.4) add(RecommendationReason("TEMPO_MATCH", "Compatible rhythm / tempo", breakdown.tempoEnergyFit))
        if (rawSelectedArtist > 0.0) add(RecommendationReason("SELECTED_ARTIST", "Requested Artist Radio performer", breakdown.artistAffinityFit))
        if (rawPersonal > 0.2) add(RecommendationReason("PERSONAL_TASTE", "Aligned with listening history", breakdown.personalTasteFit))
        if (freshnessBonus > 0.0) add(RecommendationReason("FRESHNESS_30D_BOOST", "Rediscovered track unplayed for 30+ days", freshnessBonus))
        if (cand.identity.releaseClassification.isVerifiedOriginalAudio && rawQuality > 0.5) {
            add(RecommendationReason("VERIFIED_STUDIO_AUDIO", "Verified original studio audio (${cand.identity.releaseClassification.audioClass.name})", breakdown.qualityFit))
        }
        for ((k, p) in penalties) {
            add(RecommendationReason("PENALTY_${k.uppercase(Locale.ROOT)}", "Demoted due to $k", -p))
        }
    }

    private fun sanitize(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0 else v
}
