package com.velvet.app.recommendation.sequencing

import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RankedCandidate
import com.velvet.app.recommendation.model.TempoBucket
import com.velvet.app.recommendation.model.TrackFeatureRecord
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

data class TransitionBreakdown(
    val tempoContinuity: Double = 0.0,
    val moodSmoothness: Double = 0.0,
    val genreContinuity: Double = 0.0,
    val musicTypeContinuity: Double = 0.0,
    val languageContinuity: Double = 0.0,
    val eraSmoothness: Double = 0.0,
    val bridgeBonus: Double = 0.0,
    val repetitionPenalty: Double = 0.0,
    val fatiguePenalty: Double = 0.0,
    val total: Double = 0.0,
)

interface TransitionScorer {
    fun scoreTransition(
        previous: TrackFeatureRecord?,
        candidate: RankedCandidate,
        recentWindow: List<TrackFeatureRecord>,
        anchorFeatures: TrackFeatureRecord?,
        config: SequenceConfig,
    ): TransitionBreakdown
}

/**
 * Evaluates pairwise and sliding-window flow between consecutive queue tracks:
 * - Harmonic / close-BPM and TempoBucket pacing without penalizing unknown BPM.
 * - Smooth mood & energy progression (avoids whiplash or monotonous sad streaks).
 * - Controlled bridge reward when smoothly connecting different musical styles.
 * - Cooldown penalties for artist/album/work repetition in the recent window.
 */
class DefaultTransitionScorer : TransitionScorer {

    override fun scoreTransition(
        previous: TrackFeatureRecord?,
        candidate: RankedCandidate,
        recentWindow: List<TrackFeatureRecord>,
        anchorFeatures: TrackFeatureRecord?,
        config: SequenceConfig,
    ): TransitionBreakdown {
        val candFeat = candidate.candidate.features
        if (previous == null && recentWindow.isEmpty()) {
            return TransitionBreakdown(total = 0.0)
        }

        val ref = previous ?: recentWindow.lastOrNull()
        val tempoCont = computeTempoContinuity(ref, candFeat)
        val moodSmooth = computeMoodSmoothness(ref, candFeat, recentWindow, config)
        val genreCont = computeGenreContinuity(ref, candFeat)
        val typeCont = computeMusicTypeContinuity(ref, candFeat)
        val langCont = computeLanguageContinuity(ref, candFeat)
        val eraSmooth = computeEraSmoothness(ref, candFeat)

        val bridge = if (config.allowBridgeTransition && ref != null && anchorFeatures != null && candFeat != null) {
            val prevSim = GenreOntology.similarity(
                ref.genreIds.maxByOrNull { it.value }?.key.orEmpty(),
                candFeat.genreIds.maxByOrNull { it.value }?.key.orEmpty(),
            )
            val anchorSim = GenreOntology.similarity(
                anchorFeatures.genreIds.maxByOrNull { it.value }?.key.orEmpty(),
                candFeat.genreIds.maxByOrNull { it.value }?.key.orEmpty(),
            )
            if (prevSim in 0.25..0.75 && anchorSim in 0.25..0.75) 0.10 else 0.0
        } else 0.0

        val repPenalty = computeRepetitionPenalty(candFeat, candidate, recentWindow, config)
        val fatiguePenalty = computeStreakFatiguePenalty(candFeat, recentWindow, config)

        val weightedSum = (0.24 * tempoCont) +
            (0.24 * moodSmooth) +
            (0.22 * genreCont) +
            (0.16 * typeCont) +
            (0.08 * langCont) +
            (0.06 * eraSmooth) +
            bridge -
            repPenalty -
            fatiguePenalty

        val safeTotal = if (weightedSum.isNaN() || weightedSum.isInfinite()) 0.0 else weightedSum.coerceIn(-1.5, 1.0)
        return TransitionBreakdown(
            tempoContinuity = tempoCont,
            moodSmoothness = moodSmooth,
            genreContinuity = genreCont,
            musicTypeContinuity = typeCont,
            languageContinuity = langCont,
            eraSmoothness = eraSmooth,
            bridgeBonus = bridge,
            repetitionPenalty = repPenalty,
            fatiguePenalty = fatiguePenalty,
            total = safeTotal,
        )
    }

    private fun computeTempoContinuity(prev: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        if (prev == null || cand == null) return 0.0
        val bpmA = prev.audio?.bpm
        val bpmB = cand.audio?.bpm
        if (bpmA != null && bpmB != null && bpmA > 0.0 && bpmB > 0.0) {
            val minLogDist = minOf(
                abs(ln(bpmB / bpmA)),
                abs(ln((bpmB * 2.0) / bpmA)),
                abs(ln((bpmB * 0.5) / bpmA)),
            )
            val conf = sqrt((prev.audio.bpmConfidence * cand.audio.bpmConfidence).coerceIn(0.0, 1.0))
            // Peak continuity near <=15% delta, jarring jump >35% penalized
            return ((1.0 - (minLogDist / 0.35)).coerceIn(-0.65, 1.0)) * conf
        }
        if (prev.tempoBucket == TempoBucket.UNKNOWN || cand.tempoBucket == TempoBucket.UNKNOWN) {
            return 0.0
        }
        val conf = minOf(prev.tempoConfidence, cand.tempoConfidence).coerceIn(0.0, 1.0)
        return when {
            prev.tempoBucket == cand.tempoBucket -> 0.75 * conf
            prev.tempoBucket == TempoBucket.MEDIUM || cand.tempoBucket == TempoBucket.MEDIUM -> 0.30 * conf
            else -> -0.50 * conf
        }
    }

    private fun computeMoodSmoothness(
        prev: TrackFeatureRecord?,
        cand: TrackFeatureRecord?,
        recentWindow: List<TrackFeatureRecord>,
        config: SequenceConfig,
    ): Double {
        if (prev == null || cand == null || prev.moodIds.isEmpty() || cand.moodIds.isEmpty()) return 0.0
        val allKeys = prev.moodIds.keys + cand.moodIds.keys
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (k in allKeys) {
            val a = prev.moodIds[k] ?: 0.0
            val b = cand.moodIds[k] ?: 0.0
            dot += a * b
            normA += a * a
            normB += b * b
        }
        val denom = sqrt(normA) * sqrt(normB)
        var sim = if (denom > 0.0 && dot > 0.0) (dot / denom).coerceIn(0.0, 1.0) else 0.0

        val clash = (prev.moodIds.containsKey("sad_melancholic") && cand.moodIds.containsKey("party_celebratory")) ||
            (prev.moodIds.containsKey("party_celebratory") && cand.moodIds.containsKey("sad_melancholic")) ||
            (prev.moodIds.containsKey("devotional_spiritual") && cand.moodIds.containsKey("party_celebratory"))
        if (clash) {
            sim = -0.60
        }

        // Prevent jarring endless sad-song rut when not explicitly searching sad mix
        val trailingSadStreak = recentWindow.takeLast(config.maxSlowOrSadStreak).count {
            it.moodIds.containsKey("sad_melancholic") && !it.moodIds.containsKey("romantic_love")
        }
        if (trailingSadStreak >= config.maxSlowOrSadStreak && cand.moodIds.containsKey("sad_melancholic")) {
            sim -= 0.25
        }
        return sim.coerceIn(-0.85, 1.0)
    }

    private fun computeGenreContinuity(prev: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        if (prev == null || cand == null || prev.genreIds.isEmpty() || cand.genreIds.isEmpty()) return 0.0
        var best = 0.0
        for ((gA, cA) in prev.genreIds) {
            for ((gB, cB) in cand.genreIds) {
                val sim = GenreOntology.similarity(gA, gB) * sqrt((cA * cB).coerceIn(0.0, 1.0))
                if (sim > best) best = sim
            }
        }
        return if (best <= 0.05) -0.45 else best.coerceIn(0.0, 1.0)
    }

    private fun computeMusicTypeContinuity(prev: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        if (prev == null || cand == null) return 0.0
        val a = prev.musicTypes.maxByOrNull { it.value }?.key ?: MusicType.UNKNOWN
        val b = cand.musicTypes.maxByOrNull { it.value }?.key ?: MusicType.UNKNOWN
        if (a == MusicType.UNKNOWN || b == MusicType.UNKNOWN) return 0.0
        val compat = GenreOntology.musicTypeCompatibility(a, b)
        return if (compat <= 0.10) -0.75 else compat
    }

    private fun computeLanguageContinuity(prev: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        if (prev == null || cand == null || prev.languageIds.isEmpty() || cand.languageIds.isEmpty()) return 0.0
        val overlap = prev.languageIds.keys.intersect(cand.languageIds.keys)
        return if (overlap.isNotEmpty()) {
            0.70 * minOf(prev.languageConfidence, cand.languageConfidence)
        } else {
            -0.20 * minOf(prev.languageConfidence, cand.languageConfidence)
        }
    }

    private fun computeEraSmoothness(prev: TrackFeatureRecord?, cand: TrackFeatureRecord?): Double {
        if (prev == null || cand == null) return 0.0
        val yearA = prev.eraStartYear
        val yearB = cand.eraStartYear
        if (yearA != null && yearB != null) {
            val diff = abs(yearA - yearB)
            return when {
                diff <= 5 -> 0.80
                diff <= 12 -> 0.45
                diff >= 30 -> -0.25
                else -> 0.10
            }
        }
        val bA = prev.eraBucket
        val bB = cand.eraBucket
        return if (bA != null && bB != null) {
            if (bA == bB) 0.55 else 0.0
        } else 0.0
    }

    private fun computeRepetitionPenalty(
        candFeat: TrackFeatureRecord?,
        candidate: RankedCandidate,
        recentWindow: List<TrackFeatureRecord>,
        config: SequenceConfig,
    ): Double {
        if (recentWindow.isEmpty()) return 0.0
        var penalty = 0.0

        val candArtists = candFeat?.primaryArtistIds.orEmpty()
        if (candArtists.isNotEmpty() && config.artistCooldownWindow > 0) {
            val window = recentWindow.takeLast(config.artistCooldownWindow)
            for (idx in window.indices.reversed()) {
                val distFromEnd = window.size - 1 - idx
                if (candArtists.any { it in window[idx].primaryArtistIds }) {
                    penalty += when (distFromEnd) {
                        0 -> 0.65
                        1 -> 0.35
                        else -> 0.18
                    }
                    break
                }
            }
        }

        val candAlbum = candFeat?.albumOrReleaseId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (candAlbum != null && config.albumCooldownWindow > 0) {
            val albumWindow = recentWindow.takeLast(config.albumCooldownWindow)
            for (idx in albumWindow.indices.reversed()) {
                val recentAlbum = albumWindow[idx].albumOrReleaseId?.trim()?.lowercase()
                if (recentAlbum != null && recentAlbum == candAlbum) {
                    val distFromEnd = albumWindow.size - 1 - idx
                    penalty += if (distFromEnd == 0) 0.40 else 0.20
                    break
                }
            }
        }

        // Duplicate normalized work guard inside sliding window
        val candRecId = candidate.candidate.identity.canonicalRecordingId
        if (recentWindow.any { it.canonicalRecordingId == candRecId }) {
            penalty += 1.0
        }

        return penalty.coerceAtMost(1.2)
    }

    private fun computeStreakFatiguePenalty(
        candFeat: TrackFeatureRecord?,
        recentWindow: List<TrackFeatureRecord>,
        config: SequenceConfig,
    ): Double {
        if (candFeat == null || recentWindow.size < config.maxSlowOrSadStreak) return 0.0
        val tail = recentWindow.takeLast(config.maxSlowOrSadStreak)
        if (tail.all { it.tempoBucket == TempoBucket.SLOW } && candFeat.tempoBucket == TempoBucket.SLOW) {
            return 0.25
        }
        return 0.0
    }
}
