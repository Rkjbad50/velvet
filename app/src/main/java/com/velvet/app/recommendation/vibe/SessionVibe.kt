package com.velvet.app.recommendation.vibe

import com.velvet.app.data.Song
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.TempoBucket
import com.velvet.app.recommendation.model.TrackFeatureRecord
import java.util.Locale

/**
 * Active session vibe lock built from up to 5 anchor + positive tracks using
 * RadioSessionManager-style decaying weights (C3).
 * Prevents cross-language, cross-genre, and tonal drift across endless queues.
 */
data class SessionVibe(
    val languages: Set<String> = emptySet(),
    val allowMixedLanguages: Boolean = false,
    val allowedUserLanguageIds: Set<String> = emptySet(),
    val genreWeights: Map<String, Double> = emptyMap(),
    val typeWeights: Map<MusicType, Double> = emptyMap(),
    val moodWeights: Map<String, Double> = emptyMap(),
    val eraBuckets: Map<String, Double> = emptyMap(),
    val tempoBuckets: Map<TempoBucket, Double> = emptyMap(),
    val artists: Set<String> = emptySet(),
    val anchorTitle: String = "",
) {
    /**
     * Normalized set of allowed languages under the current lock state.
     * - When [allowMixedLanguages] is false: locks tightly to [languages] (the current vibe).
     * - When [allowMixedLanguages] is true: permits user's allowed languages + session languages (unrestricted if empty).
     */
    val effectiveAllowedLanguages: Set<String>
        get() = if (allowMixedLanguages) {
            (allowedUserLanguageIds + languages).map { it.lowercase(Locale.ROOT) }.toSet()
        } else {
            languages.map { it.lowercase(Locale.ROOT) }.toSet()
        }

    fun topMoodLabel(): String = moodWeights.maxByOrNull { it.value }?.key ?: "unknown"

    companion object {
        val TRUSTED_LINEAGE_SOURCES = setOf(
            "g1_related",
            "g2_same_release",
            "g3_primary_artist",
            "g4_collaborators",
            "g5_genre_neighbours",
        )

        const val MIN_GENRE_SIMILARITY = 0.40
        const val MIN_TYPE_COMPATIBILITY = 0.40
        const val MIN_UNKNOWN_LANG_GENRE_SIMILARITY = 0.45
        const val MIN_UNKNOWN_LANG_TYPE_COMPATIBILITY = 0.50

        private val DECAY_WEIGHTS = listOf(0.45, 0.25, 0.15, 0.09, 0.06)

        fun resolveSongLanguage(
            song: Song,
            features: TrackFeatureRecord,
            artistLanguageMap: Map<String, ArtistLanguageProfile> = emptyMap(),
        ): Pair<Set<String>, Double> {
            // Signal 3 explicit script / enrichment / high-confidence token beats generic priors
            if (features.languageIds.isNotEmpty() && features.languageConfidence >= 0.84) {
                return features.languageIds.keys.map { it.lowercase(Locale.ROOT) }.toSet() to features.languageConfidence
            }
            // Signal 1: ArtistLanguageMap (on-device learned + curated artist language priors)
            val artistInf = ArtistLanguageMap.inferArtistLanguage(song.artist, artistLanguageMap)
            if (artistInf != null && artistInf.second >= 0.72) {
                return setOf(artistInf.first.lowercase(Locale.ROOT)) to artistInf.second
            }
            // Remaining Signal 3 metadata extraction
            if (features.languageIds.isNotEmpty() && features.languageConfidence >= 0.65) {
                return features.languageIds.keys.map { it.lowercase(Locale.ROOT) }.toSet() to features.languageConfidence
            }
            return emptySet<String>() to 0.0
        }

        fun fromSessionTracks(
            tracks: List<Song>,
            featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
            artistLanguageMap: Map<String, ArtistLanguageProfile> = emptyMap(),
            requestedLanguageIds: Set<String> = emptySet(),
            allowMixedLanguages: Boolean = false,
        ): SessionVibe {
            val window = tracks.distinctBy { it.id }.take(5)
            if (window.isEmpty()) {
                return SessionVibe(
                    languages = requestedLanguageIds.map { it.lowercase(Locale.ROOT) }.toSet(),
                    allowMixedLanguages = allowMixedLanguages,
                    allowedUserLanguageIds = requestedLanguageIds.map { it.lowercase(Locale.ROOT) }.toSet(),
                )
            }

            val anchor = window.first()
            val weights = DECAY_WEIGHTS.take(window.size)
            val normSum = weights.sum().coerceAtLeast(1e-6)

            val langScores = linkedMapOf<String, Double>()
            val genreWeights = linkedMapOf<String, Double>()
            val typeWeights = linkedMapOf<MusicType, Double>()
            val moodWeights = linkedMapOf<String, Double>()
            val eraBuckets = linkedMapOf<String, Double>()
            val tempoBuckets = linkedMapOf<TempoBucket, Double>()
            val artists = linkedSetOf<String>()

            window.forEachIndexed { idx, song ->
                val w = weights[idx] / normSum
                val feat = featureRepository.getCachedOrExtract(song)
                val (langs, langConf) = resolveSongLanguage(song, feat, artistLanguageMap)
                if (langs.isNotEmpty() && langConf >= 0.65) {
                    langs.forEach { l -> langScores[l] = (langScores[l] ?: 0.0) + w * langConf }
                }
                feat.genreIds.forEach { (g, v) -> genreWeights[g] = (genreWeights[g] ?: 0.0) + w * v }
                feat.musicTypes.forEach { (t, v) ->
                    if (t != MusicType.UNKNOWN) typeWeights[t] = (typeWeights[t] ?: 0.0) + w * v
                }
                feat.moodIds.forEach { (m, v) -> moodWeights[m] = (moodWeights[m] ?: 0.0) + w * v }
                feat.eraBucket?.let { era -> eraBuckets[era] = (eraBuckets[era] ?: 0.0) + w }
                if (feat.tempoBucket != TempoBucket.UNKNOWN) {
                    tempoBuckets[feat.tempoBucket] = (tempoBuckets[feat.tempoBucket] ?: 0.0) + w
                }
                ArtistLanguageMap.allArtistKeys(song.artist).forEach { artists.add(it) }
            }

            val anchorFeat = featureRepository.getCachedOrExtract(anchor)
            val (anchorLangs, anchorLangConf) = resolveSongLanguage(anchor, anchorFeat, artistLanguageMap)

            val lockedLanguages: Set<String> = when {
                // Anchor itself has clear detected language & mixed mode is OFF: lock tightly to anchor language(s)
                !allowMixedLanguages && anchorLangs.isNotEmpty() && anchorLangConf >= 0.72 -> anchorLangs
                langScores.isNotEmpty() -> {
                    val topScore = langScores.values.maxOrNull() ?: 0.0
                    langScores.filterValues { it >= topScore * 0.55 }.keys.toSet()
                }
                requestedLanguageIds.isNotEmpty() -> requestedLanguageIds.map { it.lowercase(Locale.ROOT) }.toSet()
                else -> emptySet()
            }

            return SessionVibe(
                languages = lockedLanguages,
                allowMixedLanguages = allowMixedLanguages,
                allowedUserLanguageIds = requestedLanguageIds.map { it.lowercase(Locale.ROOT) }.toSet(),
                genreWeights = genreWeights,
                typeWeights = typeWeights,
                moodWeights = moodWeights,
                eraBuckets = eraBuckets,
                tempoBuckets = tempoBuckets,
                artists = artists,
                anchorTitle = anchor.title,
            )
        }

        fun fromRequest(
            request: RecommendationRequest,
            featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
            artistLanguageMap: Map<String, ArtistLanguageProfile> = request.artistLanguageMap,
        ): SessionVibe {
            val tracks = (request.seedSongs + listOfNotNull(request.currentSong) + request.recentSongs.takeLast(4).reversed())
                .distinctBy { it.id }
            val requestedLangs = request.requestedLanguageIds
                .map { it.lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }
                .toSet()
            return fromSessionTracks(
                tracks = tracks,
                featureRepository = featureRepository,
                artistLanguageMap = artistLanguageMap,
                requestedLanguageIds = requestedLangs,
                allowMixedLanguages = request.allowMixedLanguages,
            )
        }

        fun maxGenreSimilarity(
            seedGenres: Set<String>,
            candGenres: Set<String>,
        ): Double {
            if (seedGenres.isEmpty() || candGenres.isEmpty()) return 1.0
            return seedGenres.maxOfOrNull { sg ->
                candGenres.maxOfOrNull { cg -> GenreOntology.similarity(sg, cg) } ?: 0.0
            } ?: 0.0
        }
    }
}
