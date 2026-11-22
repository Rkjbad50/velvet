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
class SessionHistory(val maxSize: Int = 200) {
    private val lock = Any()
    private val songEntries = ArrayDeque<Song>()
    private val keyEntries = ArrayDeque<Pair<String, String>>()

    fun record(song: Song) {
        val key = canonicalKey(song.title, song.artist)
        record(key, song.id)
        synchronized(lock) {
            songEntries.removeAll { it.id == song.id || SongIdentity.isStrictMatch(it, song) }
            songEntries.addLast(song)
            while (songEntries.size > maxSize) {
                songEntries.removeFirst()
            }
        }
    }

    fun record(canonicalKey: String, videoId: String) {
        synchronized(lock) {
            keyEntries.removeAll { it.first == canonicalKey || it.second == videoId }
            keyEntries.addLast(canonicalKey to videoId)
            while (keyEntries.size > maxSize) {
                keyEntries.removeFirst()
            }
        }
    }

    fun contains(song: Song): Boolean {
        synchronized(lock) {
            if (songEntries.any { it.id == song.id || SongIdentity.isStrictMatch(it, song) }) return true
            val key = canonicalKey(song.title, song.artist)
            return keyEntries.any { it.first == key || it.second == song.id }
        }
    }

    fun contains(canonicalKey: String, videoId: String): Boolean {
        synchronized(lock) {
            return keyEntries.any { it.first == canonicalKey || it.second == videoId } ||
                songEntries.any { it.id == videoId || canonicalKey(it.title, it.artist) == canonicalKey }
        }
    }

    fun clear() {
        synchronized(lock) {
            songEntries.clear()
            keyEntries.clear()
        }
    }

    fun size(): Int = synchronized(lock) { maxOf(songEntries.size, keyEntries.size) }
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
    // 6. Strip punctuation and collapse spaces (preserves all Unicode letters across languages)
    return cleaned.replace(Regex("""[^\p{L}0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
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
    return cleaned.replace(Regex("""[^\p{L}0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

/**
 * User taste profile captured from library data (liked, recents, playlists, followed artists, preferred languages).
 */
data class UserTasteProfile(
    val favoriteArtists: Set<String> = emptySet(),
    val recentArtists: Set<String> = emptySet(),
    val playlistArtists: Set<String> = emptySet(),
    val likedTrackKeys: Set<String> = emptySet(),
    val recentTrackKeys: Set<String> = emptySet(),
    val preferredLanguages: List<String> = listOf("Hindi", "English"),
    val frequentClusters: Map<MusicCluster, Int> = emptyMap(),
    val frequentMoods: Map<SongMood, Int> = emptyMap(),
)

/**
 * Thematic music clusters for high-precision algorithmic cohesion.
 */
enum class MusicCluster {
    DEVOTIONAL,
    SUFI_GHAZAL,
    RETRO_CLASSIC,
    BOLLYWOOD_MELODY,
    PUNJABI_BHANGRA,
    CARNATIC_SOUTH_FILM,
    ROMANTIC_POP_INDIE,
    WESTERN_POP,
    GENERAL,
}

enum class SongTempo {
    FAST,
    MEDIUM,
    SLOW,
}

enum class SongMood {
    DEVOTIONAL_SPIRITUAL,
    ROMANTIC_LOVE,
    SAD_MELANCHOLIC,
    PARTY_CELEBRATORY,
    SUFI_MYSTICAL,
    NOSTALGIC_RETRO,
    CHILL_RELAXING,
    GENERAL,
}

enum class SongEra {
    VINTAGE_RETRO,
    EIGHTIES_NINETIES,
    TWO_THOUSANDS,
    MODERN_CONTEMPORARY,
    UNKNOWN,
}

fun hasDevanagari(text: String): Boolean = text.any { it in '\u0900'..'\u097F' }
fun hasSouthIndicScript(text: String): Boolean = text.any { it in '\u0B80'..'\u0D7F' }
fun hasGurmukhi(text: String): Boolean = text.any { it in '\u0A00'..'\u0A7F' }

private val SOUTH_INDIC_TERMS = setOf(
    "tamil", "telugu", "malayalam", "kannada", "anirudh", "harris jayaraj", "d. imman",
    "d imman", "yuvan", "sid sriram", "ilayaraja", "spb", "s. p. balasubrahmanyam", "chithra",
    "santhosh narayanan", "gv prakash", "g.v. prakash", "thaman", "devi sri prasad", "ghibran",
    "keeravani", "pala pala", "saamikittay", "chinna chinna", "nila kaaigiradhu", "kollywood", "tollywood",
)

private fun containsTerm(text: String, terms: Set<String>): Boolean {
    val lower = text.lowercase(Locale.ROOT)
    val words = lower.split(Regex("""[\s\p{Punct}]+""")).filter { it.isNotBlank() }.toSet()
    return terms.any { term ->
        if (term.contains(" ")) {
            lower.contains(term)
        } else {
            words.contains(term)
        }
    }
}

fun isSouthIndic(song: Song): Boolean {
    return containsTerm(song.title, SOUTH_INDIC_TERMS) || containsTerm(song.artist, SOUTH_INDIC_TERMS)
}

private val DEVOTIONAL_TERMS = setOf(
    "chalisa", "bhajan", "aarti", "arti", "mantra", "stotram", "stotra", "jaap", "jap",
    "kirtan", "keertan", "bhakti", "devotional", "hanuman", "shree", "shri", "ram", "rama",
    "krishna", "radha", "shiv", "shiva", "mahadev", "bholenath", "ganesh", "ganpati",
    "durga", "mata", "devi", "amritwani", "kavach", "katha", "dhun", "sankatmochan",
    "ashtak", "ashtakam", "sunderkand", "prarthana", "vandana", "har har", "om namah",
    "gayatri", "vishnu", "sai", "govind", "gopal", "bhagwan", "mandir",
)

private val DEVOTIONAL_ARTISTS = setOf(
    "gulshan kumar", "anuradha paudwal", "anup jalota", "lakhbir singh lakkha", "suresh wadkar",
    "mahendra kapoor", "ravindra jain", "shankar sahney", "t series bhakti", "bhakti sagar",
)

private val PUNJABI_ARTISTS = setOf(
    "sidhu moosewala", "karan aujla", "diljit dosanjh", "ap dhillon", "shubh", "amrit maan",
    "jassi gill", "jordan sandhu", "cheema y", "b praak", "jaani", "harrdy sandhu", "kaka", "sukhe",
)

private val SUFI_GHAZAL_TERMS = setOf(
    "ghazal", "sufi", "qawwali", "khyal", "thumri", "nazm", "nusrat fateh ali khan",
    "rahat fateh ali khan", "jagjit singh", "ghulam ali", "mehdi hassan", "pankaj udhas",
    "chitra singh", "sabri brothers", "wadali brothers", "abida parveen",
)

private val RETRO_ARTISTS = setOf(
    "lata mangeshkar", "kishore kumar", "mohammed rafi", "asha bhosle", "mukesh",
    "manna dey", "r.d. burman", "rd burman", "s.d. burman", "sd burman",
    "laxmikant pyarelal", "kalyanji anandji", "talat mahmood", "geeta dutt", "hemant kumar",
)

private val BOLLYWOOD_90S_ARTISTS = setOf(
    "kumar sanu", "udit narayan", "alka yagnik", "abhijeet", "sonu nigam",
    "kavita krishnamurthy", "sadhana sargam", "babul supriyo",
    "lucky ali", "jatin lalit", "nadeem shravan", "anu malik",
)

private val ROMANTIC_POP_ARTISTS = setOf(
    "arijit singh", "shreya ghoshal", "atif aslam", "jubin nautiyal", "darshan raval",
    "prateek kuhad", "anuv jain", "jasleen royal", "armaan malik", "amit trivedi",
    "sachin-jigar", "mithoon", "vishal mishra",
)

private val FAST_TEMPO_TERMS = setOf(
    "dance", "remix", "bhangra", "dj", "party", "club", "dhamaal", "groove", "fast",
    "dhol", "dhamaka", "beat", "bass", "trap", "mashup", "fest", "dynamite", "high rated",
    "proper patola", "bom diggy", "kudi nu nachne de", "nachle", "nachange", "thumka", "daru",
)

private val FAST_TEMPO_ARTISTS = setOf(
    "badshah", "yo yo honey singh", "honey singh", "sukhe", "mika singh", "hard kaur", "raftaar", "guru randhawa",
)

private val SLOW_TEMPO_TERMS = setOf(
    "slowed", "reverb", "acoustic", "unplugged", "sad", "dard", "judaai", "melancholy", "lofi",
    "lo-fi", "ballad", "ghazal", "khyal", "lori", "sleep", "soothing", "meditation", "peaceful",
    "calm", "relaxing", "tanhai", "khamoshi", "virah", "chithi", "alvida", "dard e dil", "bichhad",
)

private val SLOW_TEMPO_ARTISTS = setOf(
    "jagjit singh", "mehdi hassan", "ghulam ali", "pankaj udhas", "chitra singh", "tariq khan", "anup jalota",
)

private val SAD_MOOD_TERMS = setOf(
    "sad", "dard", "bewafa", "judaai", "tanha", "tanhai", "alvida", "aansoo", "roothna",
    "breakup", "heartbreak", "judai", "gham", "gam", "dhokha", "khamoshi", "tadap", "virah",
    "bichhad", "roye", "ansu",
)

private val PARTY_MOOD_TERMS = setOf(
    "party", "club", "daaru", "sharab", "nach", "dance", "dhamaka", "disco", "celebrat",
    "shaadi", "wedding", "bhangra", "blast", "dhol", "peene", "shots",
)

private val ROMANTIC_MOOD_TERMS = setOf(
    "love", "ishq", "pyaar", "pyar", "prem", "dil", "mohabbat", "hamsafar", "humsafar",
    "romantic", "sanam", "jaan", "sajan", "saajna", "deewana", "deewani", "chahat",
    "teri aashiqui", "tujhe dekha", "tum hi ho", "kesariya", "pehla nasha", "tum se hi",
)

private val CHILL_MOOD_TERMS = setOf(
    "chill", "relax", "coffee", "calm", "peace", "lofi", "lo-fi", "breeze", "sukoon",
)

private val TWO_THOUSANDS_ARTISTS = setOf(
    "shaan", "kk", "himesh reshammiya", "mohit chauhan", "sunidhi chauhan", "pritam",
    "shankar ehsaan loy", "vishal shekhar",
)

fun detectCluster(song: Song): MusicCluster {
    val lowerTitle = song.title.lowercase(Locale.ROOT)
    val normArtist = normalizePrimaryArtist(song.artist)

    if (containsTerm(song.title, DEVOTIONAL_TERMS) || containsTerm(normArtist, DEVOTIONAL_TERMS) || DEVOTIONAL_ARTISTS.any { normArtist.contains(it) }) {
        return MusicCluster.DEVOTIONAL
    }
    if (containsTerm(song.title, SUFI_GHAZAL_TERMS) || containsTerm(normArtist, SUFI_GHAZAL_TERMS)) {
        return MusicCluster.SUFI_GHAZAL
    }
    if (hasGurmukhi(song.title) || lowerTitle.contains("punjabi") || PUNJABI_ARTISTS.any { normArtist.contains(it) }) {
        return MusicCluster.PUNJABI_BHANGRA
    }
    if (hasSouthIndicScript(song.title) || isSouthIndic(song)) {
        return MusicCluster.CARNATIC_SOUTH_FILM
    }
    if (RETRO_ARTISTS.any { normArtist.contains(it) } || lowerTitle.contains("purane geet") || lowerTitle.contains("old is gold")) {
        return MusicCluster.RETRO_CLASSIC
    }
    if (BOLLYWOOD_90S_ARTISTS.any { normArtist.contains(it) }) {
        return MusicCluster.BOLLYWOOD_MELODY
    }
    if (ROMANTIC_POP_ARTISTS.any { normArtist.contains(it) }) {
        return MusicCluster.ROMANTIC_POP_INDIE
    }
    return MusicCluster.GENERAL
}

fun detectTempo(song: Song): SongTempo {
    val normArtist = normalizePrimaryArtist(song.artist)
    val cluster = detectCluster(song)
    if (containsTerm(song.title, SLOW_TEMPO_TERMS) || containsTerm(normArtist, SLOW_TEMPO_TERMS) || SLOW_TEMPO_ARTISTS.any { normArtist.contains(it) }) {
        return SongTempo.SLOW
    }
    if (containsTerm(song.title, FAST_TEMPO_TERMS) || containsTerm(normArtist, FAST_TEMPO_TERMS) || FAST_TEMPO_ARTISTS.any { normArtist.contains(it) }) {
        return SongTempo.FAST
    }
    if (cluster == MusicCluster.PUNJABI_BHANGRA) {
        return SongTempo.FAST
    }
    if (cluster == MusicCluster.SUFI_GHAZAL) {
        return SongTempo.SLOW
    }
    return SongTempo.MEDIUM
}

fun detectMood(song: Song): SongMood {
    val cluster = detectCluster(song)
    val normArtist = normalizePrimaryArtist(song.artist)
    if (cluster == MusicCluster.DEVOTIONAL || containsTerm(song.title, DEVOTIONAL_TERMS) || containsTerm(normArtist, DEVOTIONAL_TERMS)) {
        return SongMood.DEVOTIONAL_SPIRITUAL
    }
    if (cluster == MusicCluster.SUFI_GHAZAL || containsTerm(song.title, SUFI_GHAZAL_TERMS)) {
        return SongMood.SUFI_MYSTICAL
    }
    if (containsTerm(song.title, SAD_MOOD_TERMS) || containsTerm(normArtist, SAD_MOOD_TERMS)) {
        return SongMood.SAD_MELANCHOLIC
    }
    if (containsTerm(song.title, PARTY_MOOD_TERMS) || containsTerm(normArtist, PARTY_MOOD_TERMS) || FAST_TEMPO_ARTISTS.any { normArtist.contains(it) }) {
        return SongMood.PARTY_CELEBRATORY
    }
    if (containsTerm(song.title, ROMANTIC_MOOD_TERMS) || containsTerm(normArtist, ROMANTIC_MOOD_TERMS) || ROMANTIC_POP_ARTISTS.any { normArtist.contains(it) }) {
        return SongMood.ROMANTIC_LOVE
    }
    if (cluster == MusicCluster.RETRO_CLASSIC || containsTerm(song.title, setOf("purane", "purani yaadein", "old is gold", "retro", "vintage", "classic", "zamana"))) {
        return SongMood.NOSTALGIC_RETRO
    }
    if (containsTerm(song.title, CHILL_MOOD_TERMS)) {
        return SongMood.CHILL_RELAXING
    }
    return SongMood.GENERAL
}

fun detectEra(song: Song, releaseYear: Int? = null): SongEra {
    if (releaseYear != null && releaseYear > 0) {
        return when {
            releaseYear < 1980 -> SongEra.VINTAGE_RETRO
            releaseYear in 1980..1999 -> SongEra.EIGHTIES_NINETIES
            releaseYear in 2000..2009 -> SongEra.TWO_THOUSANDS
            else -> SongEra.MODERN_CONTEMPORARY
        }
    }
    val normArtist = normalizePrimaryArtist(song.artist)
    if (containsTerm(normArtist, RETRO_ARTISTS) || RETRO_ARTISTS.any { normArtist.contains(it) }) {
        return SongEra.VINTAGE_RETRO
    }
    if (containsTerm(normArtist, TWO_THOUSANDS_ARTISTS) || TWO_THOUSANDS_ARTISTS.any { normArtist.contains(it) }) {
        return SongEra.TWO_THOUSANDS
    }
    if (containsTerm(normArtist, BOLLYWOOD_90S_ARTISTS) || BOLLYWOOD_90S_ARTISTS.any { normArtist.contains(it) }) {
        return SongEra.EIGHTIES_NINETIES
    }
    if (containsTerm(normArtist, ROMANTIC_POP_ARTISTS) || containsTerm(normArtist, PUNJABI_ARTISTS) ||
        ROMANTIC_POP_ARTISTS.any { normArtist.contains(it) } || PUNJABI_ARTISTS.any { normArtist.contains(it) }
    ) {
        return SongEra.MODERN_CONTEMPORARY
    }
    return SongEra.UNKNOWN
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
    private val userTasteProvider: (() -> UserTasteProfile)? = null,
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

        // 5. Dedupe (history + queueSnapshot + group by SongIdentity)
        val queueKeys = queueSnapshot.map { canonicalKey(it.title, it.artist) }.toSet()
        val queueIds = queueSnapshot.map { it.id }.toSet()

        val nonDuplicateCandidates = mutableListOf<Song>()
        for (candidate in filtered) {
            val key = canonicalKey(candidate.title, candidate.artist)
            if (candidate.id == seed.id || key == seedKey || areSameRecording(seed, candidate) || SongIdentity.isStrictMatch(seed, candidate)) {
                removedDedupe++
                continue
            }
            if (candidate.id in queueIds || key in queueKeys || queueSnapshot.any { areSameRecording(it, candidate) || SongIdentity.isStrictMatch(it, candidate) }) {
                removedDedupe++
                continue
            }
            if (history.contains(candidate) || history.contains(key, candidate.id)) {
                removedDedupe++
                continue
            }
            nonDuplicateCandidates.add(candidate)
        }

        // Group candidates by recording identity (SongIdentity strict clustering)
        val groups = mutableListOf<MutableList<Song>>()
        for (candidate in nonDuplicateCandidates) {
            val matchedGroup = groups.find { group ->
                group.any { areSameRecording(it, candidate) || SongIdentity.isStrictMatch(it, candidate) }
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
                val winner = SongIdentity.selectRepresentative(group)
                dedupedWinners.add(winner)
            }
        }

        val taste = userTasteProvider?.invoke() ?: UserTasteProfile()
        // 6. Score (deterministic, cluster-aware, and taste-bound)
        val scored = scoreCandidates(dedupedWinners, seed, taste)

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
     * 6-Tier Hierarchical Scoring Model:
     * 1. GENRE / CLUSTER: Dominant thematic compatibility (+80 / -150).
     * 2. TEMPO: Cadence and energy alignment (+45 / -40).
     * 3. MOOD: Emotional continuity (+35 / -35).
     * 4. PAST SAME-TYPE SONG LISTENING ACTIVITY: Frequency-based taste binding from user history (+35 / +20).
     * 5. SAME ERA AND TIMELINE: Cohesion within the same musical decade/era if genre matches (+25).
     * 6. ARTIST: Artist continuity strictly conditioned on genre and tempo compatibility (+25 / +20).
     */
    fun scoreCandidates(
        candidates: List<Song>,
        seed: Song,
        taste: UserTasteProfile = UserTasteProfile(),
    ): List<Song> {
        val seedPrimary = normalizePrimaryArtist(seed.artist)
        val seedCluster = detectCluster(seed)
        val seedTempo = detectTempo(seed)
        val seedMood = detectMood(seed)
        val seedEra = detectEra(seed, releaseYearCache[seed.id])
        val seedIsDevanagari = hasDevanagari(seed.title) || hasDevanagari(seed.artist)

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
            val candPrimary = normalizePrimaryArtist(candidate.artist)
            val candCluster = detectCluster(candidate)
            val candTempo = detectTempo(candidate)
            val candMood = detectMood(candidate)
            val candEra = detectEra(candidate, releaseYearCache[candidate.id])
            val candIsDevanagari = hasDevanagari(candidate.title) || hasDevanagari(candidate.artist)
            val candIsSouth = hasSouthIndicScript(candidate.title) || isSouthIndic(candidate)

            // 0. Audio quality / Official source
            if (isAtv(candidate)) score += 35

            // ==========================================
            // PRIORITY 1: GENRE / CLUSTER (+80 / -150)
            // ==========================================
            if (seedCluster != MusicCluster.GENERAL && seedCluster == candCluster) {
                score += 80
            } else if (seedCluster == MusicCluster.DEVOTIONAL && candCluster != MusicCluster.DEVOTIONAL) {
                score -= 150 // Devotional sessions must NEVER mix with secular/party tracks
            } else if (seedCluster == MusicCluster.PUNJABI_BHANGRA && candCluster != MusicCluster.PUNJABI_BHANGRA) {
                score -= 70
            } else if (seedCluster != MusicCluster.GENERAL && candCluster != MusicCluster.GENERAL && seedCluster != candCluster) {
                score -= 30
            }

            // Language & Script consistency
            if (seedIsDevanagari && candIsDevanagari) {
                score += 20
            }
            if ((seedIsDevanagari || seedCluster == MusicCluster.DEVOTIONAL || seedCluster == MusicCluster.BOLLYWOOD_MELODY) && candIsSouth) {
                val userAllowsSouth = taste.preferredLanguages.any {
                    it.equals("Tamil", true) || it.equals("Telugu", true) ||
                        it.equals("Malayalam", true) || it.equals("Kannada", true)
                }
                if (!userAllowsSouth && candPrimary !in taste.favoriteArtists && candPrimary !in taste.recentArtists) {
                    score -= 150 // Language clash: suppress South Indian tracks in Hindi/Bhakti session
                }
            }

            // ==========================================
            // PRIORITY 2: TEMPO (+45 / -40)
            // ==========================================
            if (seedTempo == candTempo) {
                score += 45
            } else if ((seedTempo == SongTempo.FAST && candTempo == SongTempo.SLOW) ||
                (seedTempo == SongTempo.SLOW && candTempo == SongTempo.FAST)
            ) {
                score -= 40 // Major tempo clash: do not transition between high-energy fast and acoustic/sad slow
            } else {
                score += 10 // Medium tempo compatibility
            }

            // ==========================================
            // PRIORITY 3: MOOD (+35 / -35)
            // ==========================================
            if (seedMood != SongMood.GENERAL && seedMood == candMood) {
                score += 35
            } else if (
                (seedMood == SongMood.DEVOTIONAL_SPIRITUAL && candMood == SongMood.PARTY_CELEBRATORY) ||
                (seedMood == SongMood.SAD_MELANCHOLIC && candMood == SongMood.PARTY_CELEBRATORY) ||
                (seedMood == SongMood.PARTY_CELEBRATORY && candMood == SongMood.SAD_MELANCHOLIC)
            ) {
                score -= 35 // Mood clash
            }

            // ==========================================
            // PRIORITY 4: PAST SAME-TYPE SONG ACTIVITY (+35 / +20)
            // ==========================================
            val clusterActivityCount = taste.frequentClusters[candCluster] ?: 0
            if (clusterActivityCount > 0) {
                score += Math.min(35, clusterActivityCount * 5)
            }
            val moodActivityCount = taste.frequentMoods[candMood] ?: 0
            if (moodActivityCount > 0) {
                score += Math.min(20, moodActivityCount * 3)
            }
            val key = canonicalKey(candidate.title, candidate.artist)
            if (key in taste.likedTrackKeys) {
                score += 20
            }
            if (key in taste.recentTrackKeys) {
                score += 15
            }

            // ==========================================
            // PRIORITY 5: SAME ERA AND TIMELINE (+25)
            // ("BUT THE GENURE MUST BE SAME")
            // ==========================================
            if (seedEra != SongEra.UNKNOWN && seedEra == candEra) {
                val genreIsCompatible = seedCluster == candCluster ||
                    seedCluster == MusicCluster.GENERAL ||
                    candCluster == MusicCluster.GENERAL
                if (genreIsCompatible) {
                    score += 25
                }
            }

            // ==========================================
            // PRIORITY 6: ARTIST (+25 / +20)
            // ("BUT WITH SAME CATEGORY AND SAME TEMPO AND GENURE SONGS")
            // ==========================================
            val isGenreCompatibleForArtist = seedCluster == candCluster ||
                seedCluster == MusicCluster.GENERAL ||
                candCluster == MusicCluster.GENERAL
            val isTempoCompatibleForArtist = !(seedTempo == SongTempo.FAST && candTempo == SongTempo.SLOW) &&
                !(seedTempo == SongTempo.SLOW && candTempo == SongTempo.FAST)

            if (isGenreCompatibleForArtist && isTempoCompatibleForArtist) {
                if (candPrimary.isNotEmpty() && candPrimary == seedPrimary) {
                    score += 25
                }
                if (candPrimary in taste.favoriteArtists) {
                    score += 20
                }
                if (candPrimary in taste.recentArtists) {
                    score += 15
                }
                if (candPrimary in taste.playlistArtists) {
                    score += 10
                }
            }

            // Other metadata adjustments
            val lowerTitle = candidate.title.lowercase(Locale.ROOT)
            if (lowerTitle.contains("acoustic") || lowerTitle.contains("unplugged")) {
                if (seedTempo != SongTempo.SLOW) {
                    score -= 20
                }
            }
            val v = views[candidate.id]
            if (v != null && bottomThreshold != null && v <= bottomThreshold) {
                score -= 10
            }

            candidate to score
        }

        val filtered = scoredList.filter { it.second >= 0 }
        val toSort = if (filtered.isNotEmpty()) filtered else scoredList
        return toSort.sortedByDescending { it.second }.map { it.first }
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
