package com.velvet.app.playback

import com.velvet.app.data.BoundedCache
import com.velvet.app.data.Song
import com.velvet.app.diagnostics.DiagnosticsLogger
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Candidate generator source classifications (R2).
 */
enum class CandidateGeneratorType {
    G1_RADIO,
    G2_SAME_ALBUM_OR_MOVIE,
    G3_PRIMARY_ARTIST,
    G4_COLLABORATORS,
    G5_ERA_LANGUAGE,
    G6_OWNER_LIBRARY,
    G7_SESSION_MOMENTUM
}

/**
 * Candidate tagged with its origin generator sources and rank positions (R2).
 */
data class CandidatePoolItem(
    val song: Song,
    val sources: MutableSet<CandidateGeneratorType> = mutableSetOf(),
    val sourceRanks: MutableMap<CandidateGeneratorType, Int> = mutableMapOf(),
)

/**
 * Multi-song seed profile representing current song, recent listening history,
 * and context (playlist or album) to prevent mood derailment (R2).
 */
data class SeedProfile(
    val current: Song,
    val recentSongs: List<Song> = emptyList(),
    val contextName: String? = null,
) {
    fun artistWeights(): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        fun addArtists(song: Song, weight: Double) {
            val artists = SongIdentity.normalizeArtists(song.artist) + SongIdentity.normalizeTitle(song.title).extraArtists
            for (art in artists) {
                map[art] = (map[art] ?: 0.0) + weight
            }
        }
        addArtists(current, 1.0)
        val decays = listOf(0.7, 0.5, 0.35, 0.25)
        recentSongs.take(4).forEachIndexed { i, s ->
            addArtists(s, decays[i])
        }
        return map
    }

    val primaryMovie: String?
        get() = SongIdentity.normalizeTitle(current.title).movie ?: current.album ?: contextName
}

/**
 * Inspection details for a single candidate evaluated by the autoplay pipeline (R5).
 */
data class CandidateInspection(
    val song: Song,
    val sources: Set<CandidateGeneratorType>,
    val g1Rank: Int?,
    val decision: String,
    val duplicateOf: String? = null,
    val duplicateScore: Double? = null,
    val filterReason: String? = null,
    val scoreBreakdown: Map<String, Double> = emptyMap(),
    val finalScore: Double = 0.0,
    val finalRank: Int = -1,
)

/**
 * Log record for a candidate dropped as a duplicate of an existing kept song (R5).
 */
data class DroppedDuplicateEntry(
    val droppedSongId: String,
    val droppedTitle: String,
    val droppedArtist: String,
    val keptSongId: String,
    val keptTitle: String,
    val keptArtist: String,
    val similarityScore: Double,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Inspection report capturing the candidate pool evaluation for Developer options (R5).
 */
data class AutoplayInspectionReport(
    val seedId: String,
    val seedTitle: String,
    val seedArtist: String,
    val timestamp: Long = System.currentTimeMillis(),
    val candidates: List<CandidateInspection> = emptyList(),
    val droppedDuplicates: List<DroppedDuplicateEntry> = emptyList(),
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"seedId\": \"").append(seedId).append("\",\n")
        sb.append("  \"seedTitle\": \"").append(seedTitle.replace("\"", "\\\"")).append("\",\n")
        sb.append("  \"seedArtist\": \"").append(seedArtist.replace("\"", "\\\"")).append("\",\n")
        sb.append("  \"timestamp\": ").append(timestamp).append(",\n")
        sb.append("  \"candidatesCount\": ").append(candidates.size).append(",\n")
        sb.append("  \"candidates\": [\n")
        candidates.forEachIndexed { i, c ->
            sb.append("    {\n")
            sb.append("      \"id\": \"").append(c.song.id).append("\",\n")
            sb.append("      \"title\": \"").append(c.song.title.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"artist\": \"").append(c.song.artist.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"decision\": \"").append(c.decision).append("\",\n")
            sb.append("      \"sources\": [").append(c.sources.joinToString { "\"$it\"" }).append("],\n")
            sb.append("      \"finalRank\": ").append(c.finalRank).append(",\n")
            sb.append("      \"finalScore\": ").append(c.finalScore).append(",\n")
            sb.append("      \"breakdown\": {")
            sb.append(c.scoreBreakdown.entries.joinToString { "\"${it.key}\": ${it.value}" })
            sb.append("}\n")
            sb.append("    }").append(if (i < candidates.size - 1) "," else "").append("\n")
        }
        sb.append("  ],\n")
        sb.append("  \"droppedDuplicates\": [\n")
        droppedDuplicates.forEachIndexed { i, d ->
            sb.append("    {\n")
            sb.append("      \"droppedId\": \"").append(d.droppedSongId).append("\",\n")
            sb.append("      \"droppedTitle\": \"").append(d.droppedTitle.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"droppedArtist\": \"").append(d.droppedArtist.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"keptId\": \"").append(d.keptSongId).append("\",\n")
            sb.append("      \"keptTitle\": \"").append(d.keptTitle.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"keptArtist\": \"").append(d.keptArtist.replace("\"", "\\\"")).append("\",\n")
            sb.append("      \"similarity\": ").append(d.similarityScore).append("\n")
            sb.append("    }").append(if (i < droppedDuplicates.size - 1) "," else "").append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }
}

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
    private val albumTracksProvider: (suspend (String) -> List<Song>)? = null,
    private val artistTracksProvider: (suspend (String) -> List<Song>)? = null,
    private val libraryTracksProvider: (suspend () -> List<Song>)? = null,
    private val recentCompletedProvider: (suspend () -> List<Song>)? = null,
    private val timeboxMs: Long = 1500L,
    private val persistedPlayedGroupIdsProvider: (() -> List<String>)? = null,
    private val tasteProfileProvider: (() -> TasteProfile)? = null,
    val sessionSeed: Long = 42L,
) {
    companion object {
        @Volatile
        var lastInspectionReport: AutoplayInspectionReport? = null
        val recentDroppedDuplicates = java.util.concurrent.ConcurrentLinkedDeque<DroppedDuplicateEntry>()
    }

    private val releaseYearCache = BoundedCache<String, Int>(200)
    private val candidatePoolCache = BoundedCache<String, List<CandidatePoolItem>>(50)

    fun getCachedPool(seedId: String): List<CandidatePoolItem>? = candidatePoolCache[seedId]

    /**
     * Multi-source candidate pool generator (G1..G7) executed in parallel with a 1500ms timebox.
     * Caches pool per seed for the session and merges source tags and rank positions.
     */
    suspend fun fetchCandidatePool(seedProfile: SeedProfile): List<CandidatePoolItem> = coroutineScope {
        val seed = seedProfile.current
        val cached = candidatePoolCache[seed.id]
        if (cached != null) return@coroutineScope cached

        val parsedSeed = SongIdentity.normalizeTitle(seed.title)
        val primaryArtist = normalizePrimaryArtist(seed.artist)
        val movieOrAlbum = parsedSeed.movie ?: seed.album ?: seedProfile.contextName

        // G1: Primary Radio / Watch-Next
        val g1Deferred = async(Dispatchers.IO) {
            withTimeoutOrNull(timeboxMs) {
                runCatching {
                    val list = mutableListOf<Song>()
                    for (source in sources) {
                        list.addAll(source.candidates(seed))
                    }
                    list
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

        // G2: Same Album / Movie
        val g2Deferred = async(Dispatchers.IO) {
            if (!movieOrAlbum.isNullOrBlank()) {
                withTimeoutOrNull(timeboxMs) {
                    runCatching {
                        albumTracksProvider?.invoke(movieOrAlbum) ?: emptyList()
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            } else emptyList()
        }

        // G3: Same Primary Artist
        val g3Deferred = async(Dispatchers.IO) {
            if (primaryArtist.isNotBlank()) {
                withTimeoutOrNull(timeboxMs) {
                    runCatching {
                        artistTracksProvider?.invoke(primaryArtist) ?: emptyList()
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            } else emptyList()
        }

        // G4: Co-artists / Collaborators
        val g4Deferred = async(Dispatchers.IO) {
            val coArtists = parsedSeed.extraArtists + (SongIdentity.normalizeArtists(seed.artist) - primaryArtist)
            if (coArtists.isNotEmpty()) {
                withTimeoutOrNull(timeboxMs) {
                    runCatching {
                        val list = mutableListOf<Song>()
                        for (co in coArtists.take(3)) {
                            artistTracksProvider?.invoke(co)?.let { list.addAll(it.take(5)) }
                        }
                        list
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            } else emptyList()
        }

        // G5: Era + Language Neighbours
        val g5Deferred = async(Dispatchers.IO) {
            withTimeoutOrNull(timeboxMs) {
                emptyList<Song>()
            } ?: emptyList()
        }

        // G6: Owner's Library
        val g6Deferred = async(Dispatchers.IO) {
            withTimeoutOrNull(timeboxMs) {
                runCatching {
                    libraryTracksProvider?.invoke()?.filter { s ->
                        s.id != seed.id && (
                            normalizePrimaryArtist(s.artist) == primaryArtist ||
                            (movieOrAlbum != null && (s.album?.equals(movieOrAlbum, ignoreCase = true) == true ||
                                SongIdentity.normalizeTitle(s.title).movie?.equals(movieOrAlbum, ignoreCase = true) == true))
                        )
                    } ?: emptyList()
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

        // G7: Session Momentum
        val g7Deferred = async(Dispatchers.IO) {
            withTimeoutOrNull(timeboxMs) {
                runCatching {
                    recentCompletedProvider?.invoke()?.filter { s ->
                        s.id != seed.id && normalizePrimaryArtist(s.artist) == primaryArtist
                    } ?: emptyList()
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

        val g1 = g1Deferred.await()
        val g2 = g2Deferred.await()
        val g3 = g3Deferred.await()
        val g4 = g4Deferred.await()
        val g5 = g5Deferred.await()
        val g6 = g6Deferred.await()
        val g7 = g7Deferred.await()

        val genOutputs = listOf(
            CandidateGeneratorType.G1_RADIO to g1,
            CandidateGeneratorType.G2_SAME_ALBUM_OR_MOVIE to g2,
            CandidateGeneratorType.G3_PRIMARY_ARTIST to g3,
            CandidateGeneratorType.G4_COLLABORATORS to g4,
            CandidateGeneratorType.G5_ERA_LANGUAGE to g5,
            CandidateGeneratorType.G6_OWNER_LIBRARY to g6,
            CandidateGeneratorType.G7_SESSION_MOMENTUM to g7,
        )

        val pool = mutableListOf<CandidatePoolItem>()
        for ((genType, songs) in genOutputs) {
            for ((rank, song) in songs.withIndex()) {
                val existing = pool.find { it.song.id == song.id }
                if (existing != null) {
                    existing.sources.add(genType)
                    existing.sourceRanks.putIfAbsent(genType, rank)
                } else {
                    pool.add(
                        CandidatePoolItem(
                            song = song,
                            sources = mutableSetOf(genType),
                            sourceRanks = mutableMapOf(genType to rank),
                        )
                    )
                }
            }
        }

        candidatePoolCache[seed.id] = pool
        pool
    }

    /**
     * Resolves the next N autoplay candidate songs starting from [seed].
     * Excludes anything currently in [queueSnapshot] or [history].
     */
    suspend fun next(
        seed: Song,
        queueSnapshot: List<Song> = emptyList(),
        n: Int = 10,
    ): List<Song> = next(SeedProfile(current = seed), queueSnapshot, n)

    suspend fun next(
        seedProfile: SeedProfile,
        queueSnapshot: List<Song> = emptyList(),
        n: Int = 10,
    ): List<Song> {
        val result = executePipeline(seedProfile, queueSnapshot, n, isFallback = false)
        if (result.isEmpty()) {
            // Never-empty fallback: expand ONCE (related of the last queued song or seed) and re-run once
            val fallbackSeed = queueSnapshot.lastOrNull() ?: seedProfile.current
            return executePipeline(SeedProfile(current = fallbackSeed), queueSnapshot, n, isFallback = true)
        }
        return result
    }

    private suspend fun executePipeline(
        seedProfile: SeedProfile,
        queueSnapshot: List<Song>,
        n: Int,
        isFallback: Boolean,
    ): List<Song> {
        val seed = seedProfile.current
        val seedKey = canonicalKey(seed.title, seed.artist)
        val seedPrimaryArtist = normalizePrimaryArtist(seed.artist)

        // 1. Fetch Candidate Pool
        val pool = fetchCandidatePool(seedProfile)
        val fetchedCount = pool.size

        var removedEligibility = 0
        var removedHardFilter = 0
        var removedDedupe = 0

        // 2 & 3. Normalize & Eligibility
        val eligiblePool = mutableListOf<CandidatePoolItem>()
        for (item in pool) {
            if (!isEligible(item.song, seedPrimaryArtist)) {
                removedEligibility++
            } else {
                eligiblePool.add(item)
            }
        }

        // 4. Hard filter (duration & blocked keywords)
        val filteredPool = mutableListOf<CandidatePoolItem>()
        for (item in eligiblePool) {
            if (isHardFiltered(item.song, seed.title)) {
                removedHardFilter++
            } else {
                filteredPool.add(item)
            }
        }

        // 5. Dedupe (history + queueSnapshot + group by SongIdentity)
        val queueKeys = queueSnapshot.map { canonicalKey(it.title, it.artist) }.toSet()
        val queueIds = queueSnapshot.map { it.id }.toSet()
        val persistedGroups = persistedPlayedGroupIdsProvider?.invoke()?.toSet() ?: emptySet()
        val tasteProfile = tasteProfileProvider?.invoke() ?: TasteProfile()

        val nonDuplicatePool = mutableListOf<CandidatePoolItem>()
        for (item in filteredPool) {
            val candidate = item.song
            val key = canonicalKey(candidate.title, candidate.artist)
            val groupId = SongIdentity.canonicalGroupId(candidate)
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
            if (groupId in persistedGroups) {
                removedDedupe++
                continue
            }
            if (groupId in tasteProfile.fatiguedGroupIds) {
                removedDedupe++
                continue
            }
            nonDuplicatePool.add(item)
        }

        // Group candidates by recording identity (SongIdentity strict clustering)
        val groups = mutableListOf<MutableList<CandidatePoolItem>>()
        for (item in nonDuplicatePool) {
            val matchedGroup = groups.find { group ->
                group.any { areSameRecording(it.song, item.song) || SongIdentity.isStrictMatch(it.song, item.song) }
            }
            if (matchedGroup != null) {
                matchedGroup.add(item)
            } else {
                groups.add(mutableListOf(item))
            }
        }

        val dedupedWinners = mutableListOf<Song>()
        val poolItemMap = mutableMapOf<String, CandidatePoolItem>()
        for (group in groups) {
            if (group.size == 1) {
                val item = group.first()
                dedupedWinners.add(item.song)
                poolItemMap[item.song.id] = item
            } else {
                removedDedupe += (group.size - 1)
                val songsInGroup = group.map { it.song }
                val winner = selectWinner(songsInGroup)
                dedupedWinners.add(winner)
                val allSources = group.flatMap { it.sources }.toMutableSet()
                val allRanks = mutableMapOf<CandidateGeneratorType, Int>()
                group.forEach { allRanks.putAll(it.sourceRanks) }
                poolItemMap[winner.id] = CandidatePoolItem(winner, allSources, allRanks)

                for (dropped in group) {
                    if (dropped.song.id != winner.id) {
                        val simScore = SongIdentity.sameSong(dropped.song, winner)
                        recentDroppedDuplicates.addFirst(
                            DroppedDuplicateEntry(
                                droppedSongId = dropped.song.id,
                                droppedTitle = dropped.song.title,
                                droppedArtist = dropped.song.artist,
                                keptSongId = winner.id,
                                keptTitle = winner.title,
                                keptArtist = winner.artist,
                                similarityScore = simScore,
                            )
                        )
                        while (recentDroppedDuplicates.size > 50) {
                            recentDroppedDuplicates.removeLast()
                        }
                    }
                }
            }
        }

        val userTaste = userTasteProvider?.invoke() ?: UserTasteProfile()

        // 6. Score (multi-term ranking formula with personal taste)
        val scoreBreakdowns = mutableMapOf<String, Map<String, Double>>()
        val scored = scoreCandidates(
            candidates = dedupedWinners,
            seedProfile = seedProfile,
            tasteProfile = tasteProfile,
            userTaste = userTaste,
            poolItemMap = poolItemMap,
            scoreBreakdownOut = scoreBreakdowns,
        )

        // 7. Space (MMR diversity: artist spacing + movie spacing + novelty slot)
        val lastQueuedSong = queueSnapshot.lastOrNull()
        val lastQueuedArtist = lastQueuedSong?.let { normalizePrimaryArtist(it.artist) }
        val lastQueuedMovie = lastQueuedSong?.let { SongIdentity.normalizeTitle(it.title).movie ?: it.album }
        val playedOrKnownKeys = (queueSnapshot.map { it.id } +
            queueSnapshot.map { canonicalKey(it.title, it.artist) } +
            (persistedPlayedGroupIdsProvider?.invoke() ?: emptyList())).toSet()

        val rngSeed = sessionSeed xor seed.id.hashCode().toLong()
        val spaced = spaceCandidates(
            candidates = scored,
            lastQueuedArtist = lastQueuedArtist,
            lastQueuedMovie = lastQueuedMovie,
            playedOrKnownKeys = playedOrKnownKeys,
            randomSeed = rngSeed,
        )

        // 8. Take
        val finalResult = spaced.take(n)

        // Inspection report for Developer options
        val finalRankMap = finalResult.mapIndexed { idx, s -> s.id to (idx + 1) }.toMap()
        val inspections = mutableListOf<CandidateInspection>()
        for (item in pool) {
            val s = item.song
            val g1Rank = item.sourceRanks[CandidateGeneratorType.G1_RADIO]
            val breakdown = scoreBreakdowns[s.id] ?: emptyMap()
            val finalScore = breakdown["totalScore"] ?: 0.0
            val rank = finalRankMap[s.id] ?: -1

            val decision = when {
                finalRankMap.containsKey(s.id) -> "KEPT"
                !isEligible(s, seedPrimaryArtist) -> "DROPPED_ELIGIBILITY"
                isHardFiltered(s, seed.title) -> "DROPPED_HARD_FILTER"
                SongIdentity.canonicalGroupId(s) in persistedGroups -> "DROPPED_PERSISTED_HISTORY"
                SongIdentity.canonicalGroupId(s) in tasteProfile.fatiguedGroupIds -> "DROPPED_FATIGUE"
                dedupedWinners.none { it.id == s.id } -> "DROPPED_DUPLICATE"
                else -> "DROPPED_RANK"
            }

            inspections.add(
                CandidateInspection(
                    song = s,
                    sources = item.sources,
                    g1Rank = g1Rank,
                    decision = decision,
                    scoreBreakdown = breakdown,
                    finalScore = finalScore,
                    finalRank = rank,
                )
            )
        }

        lastInspectionReport = AutoplayInspectionReport(
            seedId = seed.id,
            seedTitle = seed.title,
            seedArtist = seed.artist,
            candidates = inspections,
            droppedDuplicates = recentDroppedDuplicates.toList(),
        )

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
        poolItemMap: Map<String, CandidatePoolItem> = emptyMap(),
    ): List<Song> = scoreCandidates(
        candidates = candidates,
        seedProfile = SeedProfile(current = seed),
        tasteProfile = TasteProfile(),
        userTaste = taste,
        poolItemMap = poolItemMap,
    )

    /**
     * Multi-term Personal Autoplay Ranking Formula (R4):
     * score(candidate | seedProfile, taste) =
     *    w1 * generatorRankScore (G1 rank & multi-generator agreement)
     *  + w2 * sameMovieOrAlbum
     *  + w3 * artistSimilarity (decay-weighted match with seed profile)
     *  + w4 * eraProximity
     *  + w5 * languageMatch & genre cluster alignment
     *  + w6 * tasteScore (personal artistAffinity & movieAffinity)
     *  + w7 * libraryBoost (liked / playlist tracks, capped at +15)
     *  + w8 * qualityScore (ATV > OMV > UGC, metadata completeness)
     *  - penalties (daily overplay, duration mismatch, remix mismatch, genre clash, language clash)
     */
    fun scoreCandidates(
        candidates: List<Song>,
        seedProfile: SeedProfile,
        tasteProfile: TasteProfile = TasteProfile(),
        userTaste: UserTasteProfile = UserTasteProfile(),
        poolItemMap: Map<String, CandidatePoolItem> = emptyMap(),
        scoreBreakdownOut: MutableMap<String, Map<String, Double>>? = null,
    ): List<Song> {
        val seed = seedProfile.current
        val seedPrimary = normalizePrimaryArtist(seed.artist)
        val seedParsed = SongIdentity.normalizeTitle(seed.title)
        val seedMovie = seedParsed.movie ?: seed.album ?: seedProfile.contextName
        val seedCluster = detectCluster(seed)
        val seedTempo = detectTempo(seed)
        val seedMood = detectMood(seed)
        val seedEra = detectEra(seed, releaseYearCache[seed.id])
        val seedIsDevanagari = hasDevanagari(seed.title) || hasDevanagari(seed.artist)
        val profileArtistWeights = seedProfile.artistWeights()

        val views = candidates.mapNotNull { song ->
            val v = viewCountProvider?.invoke(song.id)
            if (v != null && v >= 0) song.id to v else null
        }.toMap()

        val bottomThreshold = if (views.size >= 4) {
            val sortedViews = views.values.sorted()
            sortedViews[sortedViews.size / 4]
        } else null

        val scoredList = candidates.map { candidate ->
            val candPrimary = normalizePrimaryArtist(candidate.artist)
            val candParsed = SongIdentity.normalizeTitle(candidate.title)
            val candMovie = candParsed.movie ?: candidate.album
            val candCluster = detectCluster(candidate)
            val candTempo = detectTempo(candidate)
            val candMood = detectMood(candidate)
            val candEra = detectEra(candidate, releaseYearCache[candidate.id])
            val candIsDevanagari = hasDevanagari(candidate.title) || hasDevanagari(candidate.artist)
            val candIsSouth = hasSouthIndicScript(candidate.title) || isSouthIndic(candidate)

            val breakdown = mutableMapOf<String, Double>()

            // 1. W1: Generator Rank & Source Agreement
            var w1 = 0.0
            val poolItem = poolItemMap[candidate.id]
            if (poolItem != null) {
                if (poolItem.sources.size > 1) {
                    w1 += (poolItem.sources.size - 1) * Weights.MULTI_SOURCE_AGREEMENT_BONUS
                }
                val g1Rank = poolItem.sourceRanks[CandidateGeneratorType.G1_RADIO]
                if (g1Rank != null) {
                    w1 += maxOf(0.0, Weights.G1_RANK_MAX_BONUS - g1Rank)
                }
            }
            breakdown["w1_generatorRank"] = w1

            // 2. W2: Same Movie or Album
            var w2 = 0.0
            if (!seedMovie.isNullOrBlank() && !candMovie.isNullOrBlank()) {
                val seedMovieClean = SongIdentity.collapseHindiLatinVariants(seedMovie.lowercase(Locale.ROOT).trim())
                val candMovieClean = SongIdentity.collapseHindiLatinVariants(candMovie.lowercase(Locale.ROOT).trim())
                if (seedMovieClean == candMovieClean || seedMovie.equals(candMovie, ignoreCase = true)) {
                    w2 = Weights.W2_SAME_MOVIE_OR_ALBUM
                }
            }
            breakdown["w2_sameMovieOrAlbum"] = w2

            // 3. W3: Artist Similarity (conditioned on genre & tempo compatibility)
            var w3 = 0.0
            val isGenreCompatibleForArtist = seedCluster == candCluster ||
                seedCluster == MusicCluster.GENERAL ||
                candCluster == MusicCluster.GENERAL
            val isTempoCompatibleForArtist = !(seedTempo == SongTempo.FAST && candTempo == SongTempo.SLOW) &&
                !(seedTempo == SongTempo.SLOW && candTempo == SongTempo.FAST)

            if (isGenreCompatibleForArtist && isTempoCompatibleForArtist) {
                val candArtists = (SongIdentity.normalizeArtists(candidate.artist) + listOf(candPrimary))
                    .filter { it.isNotBlank() }
                    .map { SongIdentity.collapseHindiLatinVariants(it) }
                val maxArtistWeight = candArtists.maxOfOrNull { profileArtistWeights[it] ?: 0.0 } ?: 0.0
                if (maxArtistWeight > 0.0) {
                    w3 = Weights.W3_ARTIST_SIMILARITY * maxArtistWeight
                } else if (candPrimary.isNotEmpty() && candPrimary == seedPrimary) {
                    w3 = Weights.W3_ARTIST_SIMILARITY
                }
            }
            breakdown["w3_artistSimilarity"] = w3

            // 4. W4: Era Proximity (boosted when genre matches)
            var w4 = 0.0
            val genreIsCompatibleForEra = seedCluster == candCluster ||
                seedCluster == MusicCluster.GENERAL ||
                candCluster == MusicCluster.GENERAL

            if (genreIsCompatibleForEra) {
                val seedYear = releaseYearCache[seed.id]
                val candYear = releaseYearCache[candidate.id]
                if (seedYear != null && candYear != null && seedYear > 0 && candYear > 0) {
                    val seedDecade = (seedYear / 10) * 10
                    val candDecade = (candYear / 10) * 10
                    val proximity = if (seedDecade == candDecade) 1.0 else if (abs(seedDecade - candDecade) <= 10) 0.5 else 0.0
                    w4 = Weights.W4_ERA_PROXIMITY * proximity
                } else if (seedEra != SongEra.UNKNOWN && seedEra == candEra) {
                    w4 = Weights.W4_ERA_PROXIMITY
                }
            }
            breakdown["w4_eraProximity"] = w4

            // 5. W5: Language Match & Genre Cluster Alignment
            var w5 = 0.0
            if (seedIsDevanagari && candIsDevanagari) {
                w5 += Weights.W5_LANGUAGE_MATCH
            }
            if (seedCluster != MusicCluster.GENERAL && seedCluster == candCluster) {
                w5 += 80.0
            }
            if (seedTempo == candTempo) {
                w5 += 35.0
            }
            if (seedMood != SongMood.GENERAL && seedMood == candMood) {
                w5 += 25.0
            }
            breakdown["w5_languageMatch"] = w5

            // 6. W6: Personal Taste Score
            var w6 = 0.0
            val candArtists = (SongIdentity.normalizeArtists(candidate.artist) + listOf(candPrimary))
                .filter { it.isNotBlank() }
                .map { SongIdentity.collapseHindiLatinVariants(it) }
            val artistAffinity = candArtists.maxOfOrNull { tasteProfile.getArtistAffinity(it) } ?: 0.0
            val movieAffinity = candMovie?.let { tasteProfile.getMovieAffinity(it) } ?: 0.0
            val combinedTaste = (artistAffinity * 0.7 + movieAffinity * 0.3).coerceIn(-1.0, 1.0)
            w6 = Weights.W6_TASTE_SCORE * combinedTaste

            // Past activity frequency from UserTasteProfile
            val clusterActivityCount = userTaste.frequentClusters[candCluster] ?: 0
            if (clusterActivityCount > 0) {
                w6 += Math.min(25.0, clusterActivityCount * 4.0)
            }
            val moodActivityCount = userTaste.frequentMoods[candMood] ?: 0
            if (moodActivityCount > 0) {
                w6 += Math.min(15.0, moodActivityCount * 2.0)
            }
            breakdown["w6_tasteScore"] = w6

            // 7. W7: Library Boost (capped at +15.0)
            var w7 = 0.0
            val candidateKey = canonicalKey(candidate.title, candidate.artist)
            if (candidateKey in userTaste.likedTrackKeys || candidate.id in userTaste.likedTrackKeys) {
                w7 = Weights.W7_LIBRARY_BOOST
            } else if (candPrimary in userTaste.favoriteArtists || candPrimary in userTaste.playlistArtists) {
                w7 = 10.0
            }
            breakdown["w7_libraryBoost"] = w7

            // 8. W8: Quality Score (ATV > OMV > UGC)
            var w8 = 0.0
            if (isAtv(candidate)) {
                w8 += Weights.W8_QUALITY_ATV
            } else if (isOmv(candidate, seedPrimary)) {
                w8 += Weights.W8_QUALITY_OMV
            }
            if (!candidate.album.isNullOrBlank()) {
                w8 += Weights.W8_QUALITY_METADATA
            }
            breakdown["w8_qualityScore"] = w8

            // 9. Penalties
            var penalties = 0.0
            val groupId = SongIdentity.canonicalGroupId(candidate)
            if (groupId in tasteProfile.dailyOverplayedGroupIds) {
                penalties += Weights.PENALTY_DAILY_OVERPLAY
            }
            if (abs(seed.durationSec - candidate.durationSec) > 120 && candidate.durationSec > 0 && seed.durationSec > 0) {
                penalties += Weights.PENALTY_DURATION_MISMATCH
            }
            val candVersions = candParsed.versionTags
            val seedVersions = seedParsed.versionTags
            if (candVersions.isNotEmpty() && !candVersions.all { it in seedVersions }) {
                penalties += Weights.PENALTY_VERSION_TAG_MISMATCH
            }
            if (seedCluster == MusicCluster.DEVOTIONAL && candCluster != MusicCluster.DEVOTIONAL) {
                penalties += Weights.PENALTY_GENRE_CLASH
            } else if (seedCluster == MusicCluster.PUNJABI_BHANGRA && candCluster != MusicCluster.PUNJABI_BHANGRA) {
                penalties += 60.0
            }
            if ((seedIsDevanagari || seedCluster == MusicCluster.DEVOTIONAL || seedCluster == MusicCluster.BOLLYWOOD_MELODY) && candIsSouth) {
                val userAllowsSouth = userTaste.preferredLanguages.any {
                    it.equals("Tamil", true) || it.equals("Telugu", true) ||
                        it.equals("Malayalam", true) || it.equals("Kannada", true)
                }
                if (!userAllowsSouth && candPrimary !in userTaste.favoriteArtists) {
                    penalties += Weights.PENALTY_LANGUAGE_CLASH
                }
            }
            if ((seedTempo == SongTempo.FAST && candTempo == SongTempo.SLOW) ||
                (seedTempo == SongTempo.SLOW && candTempo == SongTempo.FAST)
            ) {
                penalties += 35.0
            }
            val v = views[candidate.id]
            if (v != null && bottomThreshold != null && v <= bottomThreshold) {
                penalties += 10.0
            }
            breakdown["penalties"] = -penalties

            val totalScore = (w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8) - penalties
            breakdown["totalScore"] = totalScore

            scoreBreakdownOut?.put(candidate.id, breakdown)
            candidate to totalScore
        }

        val filtered = scoredList.filter { it.second >= 0 }
        val toSort = if (filtered.isNotEmpty()) filtered else scoredList
        return toSort.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Spaces candidates to preserve diversity:
     * - No two consecutive songs share primary artist.
     * - No two consecutive songs share movie / soundtrack album.
     * - Novelty slot: dedicated slot every 5 songs for discovering songs never played by owner.
     * - Epsilon-greedy top-K exploration (0.1 epsilon) for freshness with optional deterministic test seed.
     */
    fun spaceCandidates(candidates: List<Song>, lastQueuedArtist: String?): List<Song> =
        spaceCandidates(candidates, lastQueuedArtist, null)

    fun spaceCandidates(
        candidates: List<Song>,
        lastQueuedArtist: String?,
        lastQueuedMovie: String? = null,
        playedOrKnownKeys: Set<String> = emptySet(),
        randomSeed: Long? = null,
        noveltyInterval: Int = Weights.NOVELTY_SLOT_INTERVAL,
        epsilon: Double = Weights.EPSILON_GREEDY,
    ): List<Song> {
        if (candidates.size <= 1) return candidates
        val pool = candidates.toMutableList()
        val spaced = mutableListOf<Song>()
        var prevArtist = lastQueuedArtist
        var prevMovie = lastQueuedMovie
        val rng = if (randomSeed != null) kotlin.random.Random(randomSeed) else kotlin.random.Random.Default

        var pickIndex = 0
        while (pool.isNotEmpty()) {
            val isNoveltySlot = (pickIndex % noveltyInterval == (noveltyInterval - 1))
            var chosenIndex = -1

            // 1. If novelty slot, find the highest-scoring candidate not previously played/known
            if (isNoveltySlot) {
                chosenIndex = pool.indexOfFirst { song ->
                    val key = canonicalKey(song.title, song.artist)
                    val gid = SongIdentity.canonicalGroupId(song)
                    val isNovel = song.id !in playedOrKnownKeys && key !in playedOrKnownKeys && gid !in playedOrKnownKeys
                    val satisfiesArtistSpacing = normalizePrimaryArtist(song.artist) != prevArtist
                    isNovel && satisfiesArtistSpacing
                }
            }

            // 2. Normal slot or novelty fallback
            if (chosenIndex < 0) {
                // Find candidates that satisfy both artist and movie spacing
                var eligibleIndices = pool.indices.filter { idx ->
                    val s = pool[idx]
                    val artistOk = normalizePrimaryArtist(s.artist) != prevArtist
                    val candMovie = SongIdentity.normalizeTitle(s.title).movie ?: s.album
                    val movieOk = prevMovie.isNullOrBlank() || candMovie.isNullOrBlank() ||
                        !candMovie.equals(prevMovie, ignoreCase = true)
                    artistOk && movieOk
                }

                // If none, relax movie spacing (artist spacing only)
                if (eligibleIndices.isEmpty()) {
                    eligibleIndices = pool.indices.filter { idx ->
                        normalizePrimaryArtist(pool[idx].artist) != prevArtist
                    }
                }

                // If still none, relax artist spacing as well (all pool candidates)
                if (eligibleIndices.isEmpty()) {
                    eligibleIndices = pool.indices.toList()
                }

                // Epsilon-greedy top-K exploration vs exploitation
                val topK = eligibleIndices.take(Weights.TOP_K_POOL_SIZE)
                if (topK.size > 1 && rng.nextDouble() < epsilon) {
                    chosenIndex = topK[rng.nextInt(topK.size)]
                } else {
                    chosenIndex = topK.first()
                }
            }

            val chosen = pool.removeAt(chosenIndex)
            spaced.add(chosen)
            prevArtist = normalizePrimaryArtist(chosen.artist)
            prevMovie = SongIdentity.normalizeTitle(chosen.title).movie ?: chosen.album
            pickIndex++
        }
        return spaced
    }
}
