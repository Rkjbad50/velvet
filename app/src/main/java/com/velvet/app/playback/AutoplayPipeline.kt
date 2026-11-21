package com.velvet.app.playback

import com.velvet.app.data.BoundedCache
import com.velvet.app.data.Song
import com.velvet.app.diagnostics.DiagnosticsLogger
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * Interface for fetching raw candidate songs for autoplay.
 */
fun interface CandidateSource {
    suspend fun candidates(seed: Song): List<Song>
}

/**
 * Configuration for the autoplay candidate pipeline.
 */
data class AutoplayConfig(
    val blockedKeywords: Set<String> = DEFAULT_BLOCKED_KEYWORDS,
    val minDurationSec: Long = 90L,
    val maxDurationSec: Long = 600L,
) {
    companion object {
        val DEFAULT_BLOCKED_KEYWORDS = setOf(
            "remix", "slowed", "reverb", "sped up", "speed up", "nightcore",
            "lofi", "lo-fi", "cover", "live", "karaoke", "8d", "16d",
            "bass boosted", "instrumental", "mashup", "reprise", "jukebox", "ringtone",
        )
    }
}

/**
 * Thread-safe in-memory ring buffer (default size 50) of canonicalKey + videoId.
 * Used to prevent previously played or queued songs from appearing in autoplay.
 */
class SessionHistory(val maxSize: Int = 50) {
    private val lock = Any()
    private val entries = ArrayDeque<Pair<String, String>>()

    fun record(song: Song) {
        val key = canonicalKey(song.title, song.artist)
        record(key, song.id)
    }

    fun record(canonicalKey: String, videoId: String) {
        synchronized(lock) {
            entries.removeAll { it.first == canonicalKey || it.second == videoId }
            entries.addLast(canonicalKey to videoId)
            while (entries.size > maxSize) {
                entries.removeFirst()
            }
        }
    }

    fun contains(canonicalKey: String, videoId: String): Boolean {
        synchronized(lock) {
            return entries.any { it.first == canonicalKey || it.second == videoId }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }
}

/**
 * Computes canonical identity key for a song:
 * canonicalKey = normalize(title) + "|" + normalize(primaryArtist).
 */
fun canonicalKey(title: String, artist: String): String {
    val normTitle = normalizeTitle(title)
    val normArtist = normalizePrimaryArtist(artist)
    return "$normTitle|$normArtist"
}

/**
 * Normalizes title by removing noise, bracketed descriptions, video descriptors, and punctuation.
 */
fun normalizeTitle(title: String): String {
    // 1. NFKD decomposition & lowercase
    val nfkd = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    // 2. Strip diacritics
    val stripped = nfkd.replace(Regex("\\p{M}"), "")
    // 3. Drop bracketed noise: (...), [...]
    val withoutBrackets = stripped
        .replace(Regex("""\((?:official|video|audio|lyric|lyrics|remaster|remastered|hd|4k|8k|full|song|visualizer|from|feat|ft|ost|film version|original|music video)[^)]*\)"""), "")
        .replace(Regex("""\[(?:official|video|audio|lyric|lyrics|remaster|remastered|hd|4k|8k|full|song|visualizer|from|feat|ft|ost|film version|original|music video)[^\]]*\]"""), "")
    // 4. Drop noise before/after pipes or hyphens with video descriptors
    val withoutPipes = withoutBrackets.split("|").firstOrNull() ?: withoutBrackets
    // 5. Drop unbracketed noise
    val cleaned = withoutPipes
        .replace(" - topic", "")
        .replace("- topic", "")
        .replace("vevo", "")
        .replace("official video", "")
        .replace("full video song", "")
        .replace("full video", "")
        .replace("video song", "")
        .replace("full song", "")
        .replace("lyrical song", "")
        .replace("audio song", "")
        .replace("lyric video", "")
    // 6. Strip punctuation and collapse spaces
    return cleaned.replace(Regex("""[^a-z0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

/**
 * Normalizes artist down to the primary artist (before ",", "&", " x ", "feat", "ft").
 */
fun normalizePrimaryArtist(artist: String): String {
    val first = artist.split(Regex(""",|&|\bx\b|\bfeat\b|\bft\b""", RegexOption.IGNORE_CASE))
        .firstOrNull() ?: artist
    val nfkd = Normalizer.normalize(first.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    val stripped = nfkd.replace(Regex("\\p{M}"), "")
    val cleaned = stripped
        .replace(" - topic", "")
        .replace("- topic", "")
        .replace("vevo", "")
    return cleaned.replace(Regex("""[^a-z0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

/**
 * Pure-Kotlin Autoplay Candidate Pipeline:
 * fetch -> normalize -> eligibility -> hardFilter -> dedupe -> score -> space -> take(N)
 */
class AutoplayPipeline(
    private val sources: List<CandidateSource>,
    val history: SessionHistory,
    val config: AutoplayConfig = AutoplayConfig(),
    private val releaseYearProvider: (suspend (String) -> Int?)? = null,
    private val viewCountProvider: ((String) -> Long?)? = null,
) {
    private val releaseYearCache = BoundedCache<String, Int>(200)

    /**
     * Resolves the next N autoplay candidate songs starting from [seed].
     * Excludes anything currently in [queueSnapshot] or [history].
     */
    suspend fun next(
        seed: Song,
        queueSnapshot: List<Song> = emptyList(),
        n: Int = 10,
    ): List<Song> {
        val result = executePipeline(seed, queueSnapshot, n, isFallback = false)
        if (result.isEmpty()) {
            // Never-empty fallback: expand ONCE (related of the last queued song or seed) and re-run once
            val fallbackSeed = queueSnapshot.lastOrNull() ?: seed
            return executePipeline(fallbackSeed, queueSnapshot, n, isFallback = true)
        }
        return result
    }

    private suspend fun executePipeline(
        seed: Song,
        queueSnapshot: List<Song>,
        n: Int,
        isFallback: Boolean,
    ): List<Song> {
        val seedKey = canonicalKey(seed.title, seed.artist)
        val seedPrimaryArtist = normalizePrimaryArtist(seed.artist)

        // 1. Fetch
        val rawCandidates = mutableListOf<Song>()
        for (source in sources) {
            try {
                rawCandidates.addAll(source.candidates(seed))
            } catch (e: Exception) {
                DiagnosticsLogger.w("Autoplay", "Source candidate fetch failed: ${e.message}")
            }
        }
        val fetchedCount = rawCandidates.size

        var removedEligibility = 0
        var removedHardFilter = 0
        var removedDedupe = 0

        // 2 & 3. Normalize & Eligibility
        val eligible = mutableListOf<Song>()
        for (candidate in rawCandidates) {
            if (!isEligible(candidate, seedPrimaryArtist)) {
                removedEligibility++
            } else {
                eligible.add(candidate)
            }
        }

        // 4. Hard filter (duration & blocked keywords)
        val filtered = mutableListOf<Song>()
        for (candidate in eligible) {
            if (isHardFiltered(candidate, seed.title)) {
                removedHardFilter++
            } else {
                filtered.add(candidate)
            }
        }

        // 5. Dedupe (history + queueSnapshot + group by canonicalKey)
        val queueKeys = queueSnapshot.map { canonicalKey(it.title, it.artist) }.toSet()
        val queueIds = queueSnapshot.map { it.id }.toSet()

        val nonDuplicateCandidates = mutableListOf<Song>()
        for (candidate in filtered) {
            val key = canonicalKey(candidate.title, candidate.artist)
            if (candidate.id == seed.id || key == seedKey || areSameRecording(seed, candidate)) {
                removedDedupe++
                continue
            }
            if (candidate.id in queueIds || key in queueKeys || queueSnapshot.any { areSameRecording(it, candidate) }) {
                removedDedupe++
                continue
            }
            if (history.contains(key, candidate.id)) {
                removedDedupe++
                continue
            }
            nonDuplicateCandidates.add(candidate)
        }

        // Group candidates by recording identity (canonicalKey or title match with label/same artist)
        val groups = mutableListOf<MutableList<Song>>()
        for (candidate in nonDuplicateCandidates) {
            val matchedGroup = groups.find { group ->
                group.any { areSameRecording(it, candidate) }
            }
            if (matchedGroup != null) {
                matchedGroup.add(candidate)
            } else {
                groups.add(mutableListOf(candidate))
            }
        }

        val dedupedWinners = mutableListOf<Song>()
        for (group in groups) {
            if (group.size == 1) {
                dedupedWinners.add(group.first())
            } else {
                removedDedupe += (group.size - 1)
                val winner = selectWinner(group)
                dedupedWinners.add(winner)
            }
        }

        // 6. Score (deterministic)
        val scored = scoreCandidates(dedupedWinners, seed)

        // 7. Space (separate consecutive songs by primary artist)
        val lastQueuedArtist = queueSnapshot.lastOrNull()?.let { normalizePrimaryArtist(it.artist) }
        val spaced = spaceCandidates(scored, lastQueuedArtist)

        // 8. Take
        val finalResult = spaced.take(n)

        DiagnosticsLogger.i(
            "Autoplay",
            "AutoplayPipeline: seed='${seed.title}', fetched=$fetchedCount, " +
                "eligibilityRemoved=$removedEligibility, hardFilterRemoved=$removedHardFilter, " +
                "dedupeRemoved=$removedDedupe, returned=${finalResult.size} (fallback=$isFallback)",
        )

        return finalResult
    }

    /**
     * Determines whether a candidate passes eligibility:
     * - Keeps ATV (ends with '- Topic', title 'Provided to YouTube by', preferredMusicSource == true).
     * - Keeps OMV whose uploader matches primaryArtist or official/verified channel.
     * - Rejects UGC, re-uploads, lyric channels, shorts, unknown type.
     */
    fun isEligible(song: Song, seedPrimaryArtist: String): Boolean {
        if (isAtv(song)) return true
        if (isOmv(song, seedPrimaryArtist)) return true
        return false
    }

    fun isAtv(song: Song): Boolean {
        if (song.preferredMusicSource) return true
        if (song.artist.endsWith("- Topic", ignoreCase = true) || song.artist.contains("- Topic", ignoreCase = true)) return true
        if (song.artistUrl?.contains("- Topic", ignoreCase = true) == true) return true
        if (song.title.contains("Provided to YouTube by", ignoreCase = true)) return true
        return false
    }

    fun isOmv(song: Song, seedPrimaryArtist: String): Boolean {
        if (isAtv(song)) return false
        val lowerArtist = song.artist.lowercase(Locale.ROOT)
        // Explicitly reject UGC and unofficial upload indicators
        if (lowerArtist.contains("random") || lowerArtist.contains("lyrics") || lowerArtist.contains("edit") ||
            lowerArtist.contains("status") || lowerArtist.contains("fan") || lowerArtist.contains("cover")
        ) {
            return false
        }

        val candPrimary = normalizePrimaryArtist(song.artist)
        if (candPrimary.isNotEmpty() && seedPrimaryArtist.isNotEmpty() &&
            (candPrimary == seedPrimaryArtist || song.artist.contains(seedPrimaryArtist, ignoreCase = true))
        ) {
            return true
        }
        if (song.preferredMusicSource) return true

        // Known major official record labels
        if (isLabel(lowerArtist)) {
            return true
        }
        return false
    }

    private fun areSameRecording(a: Song, b: Song): Boolean {
        if (a.id == b.id) return true
        if (canonicalKey(a.title, a.artist) == canonicalKey(b.title, b.artist)) return true
        if (normalizeTitle(a.title) == normalizeTitle(b.title)) {
            val artistA = normalizePrimaryArtist(a.artist)
            val artistB = normalizePrimaryArtist(b.artist)
            val isLabelA = isLabel(artistA)
            val isLabelB = isLabel(artistB)
            if (isLabelA || isLabelB || artistA == artistB || artistA.isEmpty() || artistB.isEmpty()) {
                val durDiff = Math.abs(a.durationSec - b.durationSec)
                if (a.durationSec <= 0 || b.durationSec <= 0 || durDiff <= 4) {
                    return true
                }
            }
        }
        return false
    }

    private fun isLabel(artist: String): Boolean {
        val lower = artist.lowercase(Locale.ROOT)
        return lower.contains("t-series") || lower.contains("t series") || lower.contains("vevo") ||
            lower.contains("records") || lower.contains("sony music") || lower.contains("zee music") ||
            lower.contains("yrf") || lower.contains("tips official") || lower.contains("speed records") ||
            lower.contains("warner") || lower.contains("universal music")
    }

    /**
     * Hard filter rejects blocked keywords unless seed title also contains them,
     * and enforces duration bounds [90s, 600s].
     */
    fun isHardFiltered(candidate: Song, seedTitle: String): Boolean {
        if (candidate.durationSec in 1 until config.minDurationSec || candidate.durationSec > config.maxDurationSec) {
            return true
        }
        val lowerCandidate = candidate.title.lowercase(Locale.ROOT)
        val lowerSeed = seedTitle.lowercase(Locale.ROOT)

        for (kw in config.blockedKeywords) {
            val pattern = Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(lowerCandidate)) {
                // Keyword is allowed ONLY if seed title contains the same keyword
                if (!pattern.containsMatchIn(lowerSeed)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Dedupe winner selection order:
     * 1. ATV beats OMV
     * 2. Earliest release year (fetched lazily for groups > 1)
     * 3. First in source order (tie-break)
     */
    private suspend fun selectWinner(group: List<Song>): Song {
        val atvs = group.filter { isAtv(it) }
        val candidatesToCompare = if (atvs.isNotEmpty()) atvs else group

        if (candidatesToCompare.size == 1) return candidatesToCompare.first()

        // Fetch release years lazily for members
        val years = mutableMapOf<String, Int>()
        if (releaseYearProvider != null) {
            for (song in candidatesToCompare) {
                val cached = releaseYearCache[song.id]
                val year = if (cached != null) {
                    cached
                } else {
                    val fetched = releaseYearProvider.invoke(song.id) ?: 0
                    if (fetched > 0) releaseYearCache[song.id] = fetched
                    fetched
                }
                if (year > 0) years[song.id] = year
            }
        }

        val sorted = candidatesToCompare.sortedWith { a, b ->
            val ya = years[a.id]
            val yb = years[b.id]
            when {
                ya != null && yb != null -> ya.compareTo(yb) // Earliest year first
                ya != null -> -1
                yb != null -> 1
                else -> 0 // Keep source order
            }
        }
        return sorted.first()
    }

    /**
     * Deterministic scoring:
     * +50 ATV / official artist channel
     * +10 artist equals seed artist
     * -20 acoustic / unplugged
     * -10 bottom view-count quartile
     */
    private fun scoreCandidates(candidates: List<Song>, seed: Song): List<Song> {
        val seedPrimary = normalizePrimaryArtist(seed.artist)
        val views = candidates.mapNotNull { song ->
            val v = viewCountProvider?.invoke(song.id)
            if (v != null && v >= 0) song.id to v else null
        }.toMap()

        val bottomThreshold = if (views.size >= 4) {
            val sortedViews = views.values.sorted()
            sortedViews[sortedViews.size / 4]
        } else null

        val scoredList = candidates.map { candidate ->
            var score = 0
            if (isAtv(candidate)) score += 50
            if (normalizePrimaryArtist(candidate.artist) == seedPrimary) score += 10
            val lowerTitle = candidate.title.lowercase(Locale.ROOT)
            if (lowerTitle.contains("acoustic") || lowerTitle.contains("unplugged")) {
                score -= 20
            }
            val v = views[candidate.id]
            if (v != null && bottomThreshold != null && v <= bottomThreshold) {
                score -= 10
            }
            candidate to score
        }

        // Stable sort descending by score (preserves relative source order for ties)
        return scoredList.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Spaces songs so no two consecutive songs share primary artist.
     * If mathematically impossible, relaxes only this spacing rule.
     */
    fun spaceCandidates(candidates: List<Song>, lastQueuedArtist: String?): List<Song> {
        if (candidates.size <= 1) return candidates
        val pool = candidates.toMutableList()
        val spaced = mutableListOf<Song>()
        var prevArtist = lastQueuedArtist

        while (pool.isNotEmpty()) {
            val nextIdx = pool.indexOfFirst { normalizePrimaryArtist(it.artist) != prevArtist }
            if (nextIdx >= 0) {
                val chosen = pool.removeAt(nextIdx)
                spaced.add(chosen)
                prevArtist = normalizePrimaryArtist(chosen.artist)
            } else {
                // Relax rule: cannot separate anymore, append remainder
                spaced.addAll(pool)
                break
            }
        }
        return spaced
    }
}
