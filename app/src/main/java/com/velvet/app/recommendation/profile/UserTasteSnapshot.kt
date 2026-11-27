package com.velvet.app.recommendation.profile

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.TasteProfile
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.MusicType
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Multi-dimension normalised affinity vector in [-1.0, 1.0].
 * Used independently for long-term, medium-term, and current-session horizons.
 */
@Serializable
data class FeaturePreferenceVector(
    val genreWeights: Map<String, Double> = emptyMap(),
    val subgenreWeights: Map<String, Double> = emptyMap(),
    val musicTypeWeights: Map<MusicType, Double> = emptyMap(),
    val moodWeights: Map<String, Double> = emptyMap(),
    val languageWeights: Map<String, Double> = emptyMap(),
    val eraWeights: Map<String, Double> = emptyMap(),
    val artistWeights: Map<String, Double> = emptyMap(),
    val albumWeights: Map<String, Double> = emptyMap(),
    val recordingWeights: Map<String, Double> = emptyMap(),
    val workWeights: Map<String, Double> = emptyMap(),
) {
    fun artistAffinity(artist: String): Double {
        if (artist.isBlank()) return 0.0
        val primary = normalizePrimaryArtist(artist)
        val rawLow = artist.lowercase(Locale.ROOT).trim()
        val collapsed = SongIdentity.collapseHindiLatinVariants(primary)
        return artistWeights[primary] ?: artistWeights[collapsed] ?: artistWeights[rawLow] ?: 0.0
    }

    fun albumAffinity(album: String?): Double {
        if (album.isNullOrBlank()) return 0.0
        val norm = album.lowercase(Locale.ROOT).trim()
        val collapsed = SongIdentity.collapseHindiLatinVariants(norm)
        return albumWeights[norm] ?: albumWeights[collapsed] ?: 0.0
    }
}

/**
 * Explicit user choices cleanly separated from inferred play/skip behaviour.
 */
@Serializable
data class ExplicitPreferenceSet(
    val likedRecordingIds: Set<String> = emptySet(),
    val likedProviderIds: Set<String> = emptySet(),
    val savedPlaylistRecordingIds: Set<String> = emptySet(),
    val savedPlaylistProviderIds: Set<String> = emptySet(),
    val dislikedRecordingIds: Set<String> = emptySet(),
    val dislikedProviderIds: Set<String> = emptySet(),
    val hiddenRecordingIds: Set<String> = emptySet(),
    val hiddenProviderIds: Set<String> = emptySet(),
    val followedArtistIds: Set<String> = emptySet(),
    val preferredLanguageIds: Set<String> = emptySet(),
)

/**
 * Short-lived local search intent distinguishing raw unconverted queries (weak prior)
 * from search queries followed by clicks and meaningful playback (strong intent).
 */
@Serializable
data class SearchIntentSnapshot(
    val queryCategoryWeights: Map<String, Double> = emptyMap(),
    val convertedRecordingBoosts: Map<String, Double> = emptyMap(),
    val convertedWorkBoosts: Map<String, Double> = emptyMap(),
    val convertedArtistWeights: Map<String, Double> = emptyMap(),
    val unconvertedQueriesCount: Int = 0,
    val convertedQueriesCount: Int = 0,
    val lastUpdatedMillis: Long = 0L,
)

/**
 * Item and session fatigue signals designed so one accidental skip never permanently bans a genre or artist.
 */
@Serializable
data class FatigueSnapshot(
    val fatiguedRecordingIds: Set<String> = emptySet(),
    val fatiguedProviderIds: Set<String> = emptySet(),
    val earlySkipped14dRecordingIds: Set<String> = emptySet(),
    val earlySkipped14dProviderIds: Set<String> = emptySet(),
    val sessionRemovedRecordingIds: Set<String> = emptySet(),
    val sessionRemovedProviderIds: Set<String> = emptySet(),
    val dailyOverplayedRecordingIds: Set<String> = emptySet(),
    val singleSkipPenalties: Map<String, Double> = emptyMap(),
    val repeatedArtistPenalty: Map<String, Double> = emptyMap(),
    val sessionArtistSkipPenalties: Map<String, Double> = emptyMap(),
    val sessionGenreSkipPenalties: Map<String, Double> = emptyMap(),
)

/**
 * Immutable multi-horizon listener intelligence snapshot derived from local events and library state.
 */
@Serializable
data class UserTasteSnapshot(
    val longTerm: FeaturePreferenceVector = FeaturePreferenceVector(),
    val mediumTerm: FeaturePreferenceVector = FeaturePreferenceVector(),
    val session: FeaturePreferenceVector = FeaturePreferenceVector(),
    val explicit: ExplicitPreferenceSet = ExplicitPreferenceSet(),
    val searchIntent: SearchIntentSnapshot = SearchIntentSnapshot(),
    val fatigue: FatigueSnapshot = FatigueSnapshot(),
    val noveltyPreference: Double = 0.5,
    val generatedAtMillis: Long = 0L,
    val sourceEventWatermark: String = "empty",
    val processedEventCount: Int = 0,
    val lastPlayedMillisByRecording: Map<String, Long> = emptyMap(),
) {
    fun lastPlayedMillis(song: Song, canonicalRecordingId: String? = null): Long? {
        val canon = canonicalRecordingId ?: RecordingIdentityResolver.resolve(song).canonicalRecordingId
        return lastPlayedMillisByRecording[canon]
            ?: lastPlayedMillisByRecording[song.id]
            ?: lastPlayedMillisByRecording[SongIdentity.canonicalGroupId(song)]
    }
    fun isExplicitlyDisliked(song: Song): Boolean {
        val identity = RecordingIdentityResolver.resolve(song)
        val legacyGroup = SongIdentity.canonicalGroupId(song)
        return song.id in explicit.dislikedProviderIds ||
            identity.canonicalRecordingId in explicit.dislikedRecordingIds ||
            legacyGroup in explicit.dislikedRecordingIds ||
            song.id in explicit.hiddenProviderIds ||
            identity.canonicalRecordingId in explicit.hiddenRecordingIds ||
            legacyGroup in explicit.hiddenRecordingIds
    }

    fun isFatiguedOrRemovedInSession(song: Song): Boolean {
        val identity = RecordingIdentityResolver.resolve(song)
        val legacyGroup = SongIdentity.canonicalGroupId(song)
        return song.id in fatigue.fatiguedProviderIds ||
            identity.canonicalRecordingId in fatigue.fatiguedRecordingIds ||
            legacyGroup in fatigue.fatiguedRecordingIds ||
            song.id in fatigue.sessionRemovedProviderIds ||
            identity.canonicalRecordingId in fatigue.sessionRemovedRecordingIds ||
            legacyGroup in fatigue.sessionRemovedRecordingIds
    }

    fun isEarlySkippedWithin14Days(song: Song): Boolean {
        val identity = RecordingIdentityResolver.resolve(song)
        val legacyGroup = SongIdentity.canonicalGroupId(song)
        return song.id in fatigue.earlySkipped14dProviderIds ||
            identity.canonicalRecordingId in fatigue.earlySkipped14dRecordingIds ||
            legacyGroup in fatigue.earlySkipped14dRecordingIds
    }

    /**
     * Bridges back cleanly to legacy [TasteProfile] without discarding existing callers.
     */
    fun toLegacyTasteProfile(): TasteProfile {
        val mergedArtists = LinkedHashMap<String, Double>()
        longTerm.artistWeights.forEach { (k, v) -> mergedArtists[k] = 0.6 * v }
        mediumTerm.artistWeights.forEach { (k, v) ->
            mergedArtists[k] = ((mergedArtists[k] ?: 0.0) + 0.4 * v).coerceIn(-1.0, 1.0)
        }
        val mergedAlbums = LinkedHashMap<String, Double>()
        longTerm.albumWeights.forEach { (k, v) -> mergedAlbums[k] = 0.6 * v }
        mediumTerm.albumWeights.forEach { (k, v) ->
            mergedAlbums[k] = ((mergedAlbums[k] ?: 0.0) + 0.4 * v).coerceIn(-1.0, 1.0)
        }
        val eraDecades = LinkedHashMap<Int, Double>()
        longTerm.eraWeights.forEach { (bucket, v) ->
            val dec = when (bucket) {
                "1950_1979" -> 1970
                "1980_1999" -> 1990
                "2000_2009" -> 2000
                "2010_plus" -> 2020
                else -> null
            }
            if (dec != null) eraDecades[dec] = v
        }
        return TasteProfile(
            artistAffinity = mergedArtists,
            movieAffinity = mergedAlbums,
            eraAffinity = eraDecades,
            languageAffinity = mediumTerm.languageWeights.ifEmpty { longTerm.languageWeights },
            fatiguedGroupIds = fatigue.fatiguedRecordingIds,
            dailyOverplayedGroupIds = fatigue.dailyOverplayedRecordingIds,
            noveltyPreferenceRatio = noveltyPreference,
        )
    }
}
