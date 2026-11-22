package com.velvet.app.playback

import com.velvet.app.data.ListenEndReason
import com.velvet.app.data.ListenEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Pure-Kotlin Personal Taste Profile derived from on-device listening events.
 * Recomputed lazily and cached; no external ML libraries.
 */
data class TasteProfile(
    val artistAffinity: Map<String, Double> = emptyMap(),
    val movieAffinity: Map<String, Double> = emptyMap(),
    val eraAffinity: Map<Int, Double> = emptyMap(), // decade buckets: 1980, 1990, 2000, 2010, 2020...
    val languageAffinity: Map<String, Double> = emptyMap(),
    val fatiguedGroupIds: Set<String> = emptySet(), // skipped early >= 2 times in 14 days -> blocked for 30 days
    val dailyOverplayedGroupIds: Set<String> = emptySet(), // played >= 3 times in 24h -> down-weighted
    val noveltyPreferenceRatio: Double = 0.5, // ratio of completed tracks that were new to owner
) {
    fun getArtistAffinity(artist: String): Double {
        val norm = artist.lowercase().trim()
        val collapsed = SongIdentity.collapseHindiLatinVariants(norm)
        return artistAffinity[norm] ?: artistAffinity[collapsed] ?: 0.0
    }

    fun getMovieAffinity(movie: String?): Double {
        if (movie.isNullOrBlank()) return 0.0
        val norm = movie.lowercase().trim()
        val collapsed = SongIdentity.collapseHindiLatinVariants(norm)
        return movieAffinity[norm] ?: movieAffinity[collapsed] ?: 0.0
    }
}

object TasteEngine {
    const val HALF_LIFE_MILLIS = 30.0 * 24.0 * 60.0 * 60.0 * 1000.0 // 30 days
    val DECAY_CONSTANT = Math.log(2.0) / HALF_LIFE_MILLIS // ln(2) / half_life

    const val FOURTEEN_DAYS_MILLIS = 14L * 24 * 60 * 60 * 1000L
    const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000L
    const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000L

    @Volatile
    private var cachedProfile: Pair<Int, TasteProfile>? = null

    fun getOrComputeProfile(events: List<ListenEvent>, nowMillis: Long = System.currentTimeMillis()): TasteProfile {
        val currentHash = events.size xor (events.lastOrNull()?.startedAt?.toInt() ?: 0)
        val cached = cachedProfile
        if (cached != null && cached.first == currentHash) {
            return cached.second
        }
        val computed = computeProfile(events, nowMillis)
        cachedProfile = Pair(currentHash, computed)
        return computed
    }

    fun computeProfile(
        events: List<ListenEvent>,
        nowMillis: Long = System.currentTimeMillis(),
    ): TasteProfile {
        if (events.isEmpty()) return TasteProfile()

        val artistRawScores = mutableMapOf<String, Double>()
        val movieRawScores = mutableMapOf<String, Double>()
        val eraRawScores = mutableMapOf<Int, Double>()
        val langRawScores = mutableMapOf<String, Double>()

        val earlySkipsIn14Days = mutableMapOf<String, MutableList<Long>>()
        val playsInLastDay = mutableMapOf<String, Int>()

        val seenSongGroups = mutableSetOf<String>()
        var newSongsCompleted = 0
        var totalSongsCompleted = 0

        val sortedEvents = events.sortedBy { it.startedAt }

        for (event in sortedEvents) {
            val age = max(0L, nowMillis - event.startedAt).toDouble()
            val timeWeight = exp(-DECAY_CONSTANT * age)

            // Weight by endReason & liked/playlist: completion +1, replay +1.5, like +2, add-to-playlist +2, skip<30s -1.5, skip late -0.5
            var eventWeight = when (event.endReason) {
                ListenEndReason.COMPLETED -> 1.0
                ListenEndReason.REPLAY -> 1.5
                ListenEndReason.SKIP_EARLY -> -1.5
                ListenEndReason.SKIP_LATE -> -0.5
            }
            if (event.liked) {
                eventWeight += 2.0
            }
            if (event.addedToPlaylist) {
                eventWeight += 2.0
            }

            val weightedScore = eventWeight * timeWeight

            // Artists: index by both raw normalized and collapsed variants
            val artistTokens = mutableSetOf<String>()
            for (artistRaw in event.artistSet) {
                val clean = artistRaw.lowercase().trim()
                if (clean.isNotBlank()) {
                    artistTokens.add(clean)
                    artistTokens.add(SongIdentity.collapseHindiLatinVariants(clean))
                }
                for (norm in SongIdentity.normalizeArtists(artistRaw)) {
                    artistTokens.add(norm)
                    artistTokens.add(SongIdentity.collapseHindiLatinVariants(norm))
                }
            }

            for (artist in artistTokens) {
                artistRawScores[artist] = (artistRawScores[artist] ?: 0.0) + weightedScore
            }

            // Movie: index by both clean and collapsed variants
            event.movie?.takeIf { it.isNotBlank() }?.let { m ->
                val normMovie = m.lowercase().trim()
                val collapsed = SongIdentity.collapseHindiLatinVariants(normMovie)
                movieRawScores[normMovie] = (movieRawScores[normMovie] ?: 0.0) + weightedScore
                if (collapsed != normMovie) {
                    movieRawScores[collapsed] = (movieRawScores[collapsed] ?: 0.0) + weightedScore
                }
            }

            // Era (decade bucket)
            event.year?.let { y ->
                if (y in 1900..2030) {
                    val decade = (y / 10) * 10
                    eraRawScores[decade] = (eraRawScores[decade] ?: 0.0) + weightedScore
                }
            }

            // Language
            event.language?.takeIf { it.isNotBlank() }?.let { lang ->
                val normLang = lang.lowercase().trim()
                langRawScores[normLang] = (langRawScores[normLang] ?: 0.0) + weightedScore
            }

            // Fatigue tracking: skipped early twice in 14 days
            if (event.endReason == ListenEndReason.SKIP_EARLY) {
                if (nowMillis - event.startedAt <= FOURTEEN_DAYS_MILLIS) {
                    earlySkipsIn14Days.getOrPut(event.songGroupId) { mutableListOf() }.add(event.startedAt)
                }
            }

            // Daily overplay tracking: played 3+ times in a day
            if (nowMillis - event.startedAt <= ONE_DAY_MILLIS) {
                playsInLastDay[event.songGroupId] = (playsInLastDay[event.songGroupId] ?: 0) + 1
            }

            // Novelty preference tracking
            if (event.endReason == ListenEndReason.COMPLETED) {
                totalSongsCompleted++
                if (seenSongGroups.add(event.songGroupId)) {
                    newSongsCompleted++
                }
            } else {
                seenSongGroups.add(event.songGroupId)
            }
        }

        // Fatigued groups: skipped early >= 2 times in 14 days -> blocked for 30 days from latest skip
        val fatigued = earlySkipsIn14Days.filter { (_, timestamps) ->
            timestamps.size >= 2 && (nowMillis - timestamps.maxOrNull()!! <= THIRTY_DAYS_MILLIS)
        }.keys

        val overplayed = playsInLastDay.filter { (_, count) -> count >= 3 }.keys

        val noveltyRatio = if (totalSongsCompleted > 0) {
            newSongsCompleted.toDouble() / totalSongsCompleted.toDouble()
        } else 0.5

        return TasteProfile(
            artistAffinity = normalizeAffinities(artistRawScores),
            movieAffinity = normalizeAffinities(movieRawScores),
            eraAffinity = normalizeAffinities(eraRawScores),
            languageAffinity = normalizeAffinities(langRawScores),
            fatiguedGroupIds = fatigued,
            dailyOverplayedGroupIds = overplayed,
            noveltyPreferenceRatio = noveltyRatio.coerceIn(0.0, 1.0),
        )
    }

    private fun <T> normalizeAffinities(raw: Map<T, Double>): Map<T, Double> {
        if (raw.isEmpty()) return emptyMap()
        val maxAbs = raw.values.maxOfOrNull { abs(it) } ?: 1.0
        val scale = if (maxAbs > 0.0) maxAbs else 1.0
        return raw.mapValues { (_, v) -> (v / scale).coerceIn(-1.0, 1.0) }
    }
}
