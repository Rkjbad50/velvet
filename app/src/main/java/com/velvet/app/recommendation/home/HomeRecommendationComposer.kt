package com.velvet.app.recommendation.home

import com.velvet.app.data.Song
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.engine.RecommendationEngine
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.VersionKind
import com.velvet.app.recommendation.profile.UserProfileRepository
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import java.util.UUID

data class PersonalizedMix(
    val title: String,
    val genreHint: String?,
    val songs: List<Song>,
)

/**
 * Composes personalized, non-redundant Home shelves and Personalized Mix playlists while strictly
 * enforcing VELVET's Original Studio Audio Only invariant across all recommendation surfaces.
 *
 * Guarantees:
 * - Zero Official Music Videos, Lyric Videos, Visualizers, Live performances, or UNVERIFIED uploads.
 * - Cold-start safe (never crashes or fabricates fake taste on empty history).
 * - Deduplicates across canonical recording IDs and enforces shelf-level artist diversity.
 */
class HomeRecommendationComposer(
    private val engine: RecommendationEngine? = null,
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val profileRepository: UserProfileRepository? = null,
) {

    /**
     * Filters any track list to strict verified original studio audio only.
     * Unverified uploads, music videos, lyric videos, visualizers, live concerts,
     * spoken content, and unsolicited alternate versions fail closed.
     */
    fun filterStrictOriginalAudio(
        songs: List<Song>,
        allowAlternateVersions: Boolean = false,
    ): List<Song> {
        if (songs.isEmpty()) return emptyList()
        val seenRecIds = mutableSetOf<String>()
        return buildList {
            for (song in songs) {
                if (song.id.isBlank() || song.title.isBlank()) continue
                val id = RecordingIdentityResolver.resolve(song)
                if (!id.releaseClassification.isVerifiedOriginalAudio) continue
                if (!allowAlternateVersions && id.versionKind !in ALLOWED_ORIGINAL_VERSIONS) continue

                val feat = featureRepository.getCachedOrExtract(song)
                val topType = feat.musicTypes.maxByOrNull { it.value }?.key
                if (topType == MusicType.PODCAST_OR_SPOKEN) continue

                val durSec = song.durationSec
                if (durSec in 1L..44L || durSec == 0L) continue
                val maxDurSec = if (topType in LONG_FORM_TYPES) 1_200L else 660L
                if (durSec > maxDurSec) continue

                if (seenRecIds.add(id.canonicalRecordingId)) {
                    add(song)
                }
            }
        }
    }

    /**
     * Filters raw Trending candidates to verified studio audio and boosts user-preferred languages
     * cleanly without allowing same-artist flooding.
     */
    fun rankAndFilterTrending(
        rawTrending: List<Song>,
        preferredLanguageIds: Set<String> = emptySet(),
        limit: Int = 20,
    ): List<Song> {
        val strict = filterStrictOriginalAudio(rawTrending)
        if (strict.isEmpty()) return emptyList()

        val sorted = strict.sortedWith(
            compareByDescending<Song> { song ->
                val feat = featureRepository.getCachedOrExtract(song)
                if (preferredLanguageIds.isNotEmpty() && feat.languageIds.keys.any { it in preferredLanguageIds }) 1 else 0
            }.thenByDescending { song ->
                RecordingIdentityResolver.resolve(song).releaseClassification.audioClass.qualityTier
            },
        )
        return capArtists(sorted, maxPerArtist = 2, limit = limit)
    }

    /**
     * Builds Home "Quick picks" using the recommendation engine when available, enriched with
     * diversity and strict original audio verification. Cold-start returns empty cleanly unless
     * [fallbackCatalog] is provided.
     */
    suspend fun composeQuickPicks(
        recent: List<Song>,
        liked: List<Song> = emptyList(),
        preferredLanguageIds: Set<String> = emptySet(),
        directSearchProvider: (suspend (String) -> List<Song>)? = null,
        fallbackCatalog: List<Song> = emptyList(),
        limit: Int = 10,
    ): List<Song> {
        val verifiedSeeds = filterStrictOriginalAudio(recent + liked)
        if (verifiedSeeds.isEmpty() && fallbackCatalog.isEmpty()) return emptyList()

        val playedIds = recent.mapTo(mutableSetOf()) { it.id }
        val playedRecs = recent.mapTo(mutableSetOf()) { RecordingIdentityResolver.resolve(it).canonicalRecordingId }

        if (engine != null && verifiedSeeds.isNotEmpty()) {
            val diverseSeeds = verifiedSeeds
                .distinctBy { normalizePrimaryArtist(it.artist) }
                .take(3)
            val result = engine.recommend(
                RecommendationRequest(
                    requestId = UUID.randomUUID().toString(),
                    mode = RecommendationMode.HOME_SONGS,
                    seedSongs = diverseSeeds,
                    currentSong = diverseSeeds.firstOrNull(),
                    recentSongs = recent.take(12),
                    excludedProviderIds = playedIds,
                    excludedRecordingIds = playedRecs,
                    requestedLanguageIds = preferredLanguageIds,
                    limit = limit,
                ),
            )
            val engineSongs = filterStrictOriginalAudio(result.candidates.map { it.song })
            if (engineSongs.isNotEmpty()) {
                return capArtists(engineSongs, maxPerArtist = 2, limit = limit)
            }
        }

        // Clean fallback via direct search or fallback catalog — still strict studio audio only
        val fetched = buildList {
            if (directSearchProvider != null && verifiedSeeds.isNotEmpty()) {
                val seedArtists = verifiedSeeds
                    .mapNotNull { it.artist.trim().takeIf { a -> a.isNotBlank() } }
                    .distinctBy { it.lowercase() }
                    .take(3)
                for (artist in seedArtists) {
                    val items = try {
                        directSearchProvider("$artist songs")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    addAll(items)
                }
            }
            addAll(fallbackCatalog)
        }.filterNot { song ->
            song.id in playedIds || RecordingIdentityResolver.resolve(song).canonicalRecordingId in playedRecs
        }

        return capArtists(filterStrictOriginalAudio(fetched), maxPerArtist = 2, limit = limit)
    }

    /**
     * Builds "Speed Dial" filling all [limit] (9) cells whenever verified studio audio exists,
     * dynamically adapting to listening habits (frequency, completion, likes, active genre/mood,
     * preferred languages, and fatigue/skips) blended with fresh discovery tracks.
     */
    fun composeSpeedDial(
        recent: List<Song>,
        liked: List<Song>,
        quickPicks: List<Song>,
        trending: List<Song>,
        limit: Int = 9,
        playlistsSongs: List<Song> = emptyList(),
        preferredLanguageIds: Set<String> = emptySet(),
        tasteProfile: UserTasteSnapshot? = null,
    ): List<Song> {
        if (limit <= 0) return emptyList()

        val recentIndexMap = linkedMapOf<String, Int>()
        val historyFrequency = mutableMapOf<String, Int>()
        recent.forEachIndexed { idx, s ->
            val recId = RecordingIdentityResolver.resolve(s).canonicalRecordingId
            if (recId !in recentIndexMap) recentIndexMap[recId] = idx
            historyFrequency[recId] = (historyFrequency[recId] ?: 0) + 1
        }
        val likedRecIds = liked.mapTo(mutableSetOf()) { RecordingIdentityResolver.resolve(it).canonicalRecordingId }
        val playlistRecIds = playlistsSongs.mapTo(mutableSetOf()) { RecordingIdentityResolver.resolve(it).canonicalRecordingId }

        fun habitScore(song: Song, recId: String, isFamiliar: Boolean): Double {
            if (tasteProfile?.isExplicitlyDisliked(song) == true) return Double.NEGATIVE_INFINITY
            val feat = featureRepository.getCachedOrExtract(song)
            var score = 0.0

            val recencyIdx = recentIndexMap[recId]
            if (recencyIdx != null) {
                score += 0.45 * (1.0 - (recencyIdx / 25.0).coerceAtMost(0.90))
            }
            val repeatCount = historyFrequency[recId] ?: 0
            if (repeatCount > 1) {
                score += minOf(0.30, (repeatCount - 1) * 0.12)
            }
            if (recId in likedRecIds) score += 0.30
            if (recId in playlistRecIds) score += 0.14

            if (tasteProfile != null) {
                score += (tasteProfile.longTerm.recordingWeights[recId] ?: 0.0) * 0.45
                score += (tasteProfile.mediumTerm.recordingWeights[recId] ?: 0.0) * 0.35
                score += (tasteProfile.session.recordingWeights[recId] ?: 0.0) * 0.25

                val genreFit = feat.genreIds.entries.maxOfOrNull { (g, c) ->
                    ((tasteProfile.mediumTerm.genreWeights[g] ?: 0.0) * 0.55 +
                        (tasteProfile.longTerm.genreWeights[g] ?: 0.0) * 0.45) * c
                } ?: 0.0
                val moodFit = feat.moodIds.entries.maxOfOrNull { (m, c) ->
                    ((tasteProfile.session.moodWeights[m] ?: 0.0) * 0.55 +
                        (tasteProfile.mediumTerm.moodWeights[m] ?: 0.0) * 0.45) * c
                } ?: 0.0
                score += 0.28 * genreFit + 0.20 * moodFit

                val skipPenalty = tasteProfile.fatigue.singleSkipPenalties[recId] ?: 0.0
                score -= (skipPenalty * 0.50).coerceAtMost(0.45)
                if (recId in tasteProfile.fatigue.dailyOverplayedRecordingIds) {
                    score -= 0.18
                }
            }

            if (preferredLanguageIds.isNotEmpty() && feat.languageIds.keys.any { it in preferredLanguageIds }) {
                score += 0.15
            }
            val tierBonus = RecordingIdentityResolver.resolve(song).releaseClassification.audioClass.qualityTier * 0.03
            if (!isFamiliar) score += tierBonus
            return score
        }

        val familiarFiltered = filterStrictOriginalAudio(recent + liked + playlistsSongs)
        val familiarScored = familiarFiltered
            .map { it to habitScore(it, RecordingIdentityResolver.resolve(it).canonicalRecordingId, isFamiliar = true) }
            .filter { it.second > Double.NEGATIVE_INFINITY }
            .sortedByDescending { it.second }
            .map { it.first }

        val freshFiltered = filterStrictOriginalAudio(quickPicks + trending)
        val freshScored = freshFiltered
            .map { it to habitScore(it, RecordingIdentityResolver.resolve(it).canonicalRecordingId, isFamiliar = false) }
            .filter { it.second > Double.NEGATIVE_INFINITY }
            .sortedByDescending { it.second }
            .map { it.first }

        val selected = mutableListOf<Song>()
        val selectedRecIds = mutableSetOf<String>()
        val artistCounts = mutableMapOf<String, Int>()

        fun tryAdd(song: Song, maxPerArtist: Int): Boolean {
            if (selected.size >= limit) return false
            val recId = RecordingIdentityResolver.resolve(song).canonicalRecordingId
            if (recId in selectedRecIds) return false
            val artistKey = normalizePrimaryArtist(song.artist).ifBlank { song.artist.lowercase() }
            val count = artistCounts[artistKey] ?: 0
            if (count >= maxPerArtist) return false
            selectedRecIds.add(recId)
            artistCounts[artistKey] = count + 1
            selected.add(song)
            return true
        }

        // Phase 1: Balanced mix — up to 4 habit/history tracks (diverse artists) + fresh discovery
        val familiarTarget = if (freshScored.isEmpty()) limit else minOf(4, familiarScored.size)
        for (song in familiarScored) {
            if (selected.size >= familiarTarget) break
            tryAdd(song, maxPerArtist = 1)
        }
        for (song in freshScored) {
            if (selected.size >= limit) break
            tryAdd(song, maxPerArtist = 2)
        }

        // Phase 2: Backfill remaining slots cleanly so all 9/9 Speed Dial cells fill whenever available
        if (selected.size < limit) {
            val remainder = familiarScored + freshScored
            for (song in remainder) {
                if (selected.size >= limit) break
                tryAdd(song, maxPerArtist = 2)
            }
            for (song in remainder) {
                if (selected.size >= limit) break
                tryAdd(song, maxPerArtist = Int.MAX_VALUE)
            }
        }

        return selected.take(limit)
    }

    /**
     * Picks verified studio seeds for "Made for you" Artist Radio cards, diversifying across
     * primary artists AND musical genres.
     */
    fun composeMadeForYouMixSeeds(
        recent: List<Song>,
        liked: List<Song>,
        limit: Int = 8,
    ): List<Song> {
        val strict = filterStrictOriginalAudio(recent + liked)
        if (strict.isEmpty()) return emptyList()

        val selected = mutableListOf<Song>()
        val usedArtists = mutableSetOf<String>()
        val genreCounts = mutableMapOf<String, Int>()

        // Pass 1: maximize artist + genre breadth
        for (song in strict) {
            if (selected.size >= limit) break
            val artistKey = normalizePrimaryArtist(song.artist).ifBlank { song.artist.lowercase() }
            if (!usedArtists.add(artistKey)) continue
            val topGenre = featureRepository.getCachedOrExtract(song).genreIds.maxByOrNull { it.value }?.key
            if (topGenre != null && (genreCounts[topGenre] ?: 0) >= 3 && selected.size < strict.size) continue
            selected.add(song)
            if (topGenre != null) genreCounts[topGenre] = (genreCounts[topGenre] ?: 0) + 1
        }
        // Pass 2: fill remainder cleanly from remaining distinct artists
        if (selected.size < limit) {
            for (song in strict) {
                if (selected.size >= limit) break
                val artistKey = normalizePrimaryArtist(song.artist).ifBlank { song.artist.lowercase() }
                if (artistKey in usedArtists) continue
                usedArtists.add(artistKey)
                selected.add(song)
            }
        }
        return selected
    }

    /**
     * Generates a transition-coherent personalized playlist using SequenceOptimizer via engine.
     */
    suspend fun composePersonalizedMix(
        recent: List<Song>,
        liked: List<Song>,
        fallbackTrending: List<Song> = emptyList(),
        preferredLanguageIds: Set<String> = emptySet(),
        limit: Int = 12,
    ): PersonalizedMix {
        val profile = try {
            profileRepository?.getSnapshot()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val topGenre = profile?.longTerm?.genreWeights?.maxByOrNull { it.value }?.key
            ?: profile?.mediumTerm?.genreWeights?.maxByOrNull { it.value }?.key
        val title = when (topGenre) {
            GenreOntology.DEVOTIONAL -> "Peaceful Bhakti & Devotional Mix"
            GenreOntology.SUFI_GHAZAL -> "Soulful Sufi & Ghazal Mix"
            GenreOntology.BOLLYWOOD -> "Personalized Hindi Studio Mix"
            GenreOntology.PUNJABI -> "Punjabi Studio Vibes Mix"
            GenreOntology.SOUTH_INDIAN -> "South Indian Audio Mix"
            else -> "Your Velvet Daily Mix"
        }

        val verifiedSeeds = filterStrictOriginalAudio(recent + liked)
        if (engine != null && verifiedSeeds.isNotEmpty()) {
            val result = engine.recommend(
                RecommendationRequest(
                    requestId = UUID.randomUUID().toString(),
                    mode = RecommendationMode.PERSONALIZED_PLAYLIST,
                    seedSongs = verifiedSeeds.take(3),
                    currentSong = verifiedSeeds.firstOrNull(),
                    recentSongs = recent.take(10),
                    requestedLanguageIds = preferredLanguageIds,
                    limit = limit,
                ),
            )
            val tracks = filterStrictOriginalAudio(result.candidates.map { it.song })
            if (tracks.isNotEmpty()) {
                return PersonalizedMix(title, topGenre, capArtists(tracks, maxPerArtist = 2, limit = limit))
            }
        }

        // Cold-start safe fallback: strict verified studio tracks only
        val fallback = capArtists(
            filterStrictOriginalAudio(liked + fallbackTrending),
            maxPerArtist = 2,
            limit = limit,
        )
        return PersonalizedMix(title, topGenre, fallback)
    }

    private fun capArtists(songs: List<Song>, maxPerArtist: Int, limit: Int): List<Song> {
        val counts = mutableMapOf<String, Int>()
        val result = mutableListOf<Song>()
        for (song in songs) {
            if (result.size >= limit) break
            val key = normalizePrimaryArtist(song.artist).ifBlank { song.artist.lowercase() }
            val current = counts[key] ?: 0
            if (current >= maxPerArtist) continue
            counts[key] = current + 1
            result.add(song)
        }
        return result
    }

    companion object {
        private val ALLOWED_ORIGINAL_VERSIONS = setOf(
            VersionKind.ORIGINAL_STUDIO,
            VersionKind.UNKNOWN,
        )
        private val LONG_FORM_TYPES = setOf(
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
    }
}
