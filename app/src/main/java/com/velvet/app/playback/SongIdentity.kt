package com.velvet.app.playback

import com.velvet.app.data.Song
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure Kotlin Song Identity & Deduplication Engine (Zero Android dependencies).
 * Provides high-precision "same song" detection across uploads, transliteration tolerance,
 * semantic version tag awareness, canonical grouping, and optimal representative picking.
 */
object SongIdentity {

    const val STRICT_THRESHOLD = 0.76
    const val LOOSE_THRESHOLD = 0.58

    private val VERSION_TAG_REGEX = Regex(
        """\b(remix|remixed|dj remix|club mix|slowed|reverb|lo-?fi|sped up|speed up|speedup|nightcore|""" +
            """acoustic|unplugged|live|cover|instrumental|karaoke|mashup|remastered|remaster|reprise|""" +
            """male version|female version|sad version|female|male|sad)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val NOISE_BRACKETS_REGEX = Regex(
        """\((?:official|video|audio|lyric|lyrics|hd|4k|8k|full song|full video|video song|audio song|""" +
            """visualizer|original soundtrack|ost|film version|music video|full track|hook step|dance video)[^)]*\)""",
        RegexOption.IGNORE_CASE,
    )

    private val NOISE_SQUARE_REGEX = Regex(
        """\[(?:official|video|audio|lyric|lyrics|hd|4k|8k|full song|full video|video song|audio song|""" +
            """visualizer|original soundtrack|ost|film version|music video|full track|hook step|dance video)[^\]]*\]""",
        RegexOption.IGNORE_CASE,
    )

    private val MOVIE_REGEX = Regex(
        """(?:\(|\[|\b)(?:from\s+["']?([^)"']+)["']?|film:\s*([^)"'\]]+)|movie:\s*([^)"'\]]+))(?:\)|\]|\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val FEAT_REGEX = Regex(
        """(?:\(|\[|\b)(?:feat\.?|ft\.?|with)\s+([^)"'\]]+)(?:\)|\]|\b)""",
        RegexOption.IGNORE_CASE,
    )

    data class ParsedTitle(
        val cleanTitle: String,
        val comparisonKey: String,
        val movie: String?,
        val extraArtists: Set<String>,
        val versionTags: Set<String>,
    )

    /**
     * Normalizes a song title:
     * - Detects and preserves semantic version tags (remix, acoustic, live, etc.)
     * - Extracts movie / album segment
     * - Extracts featured artist credits
     * - Strips upload noise (Official Video, HD, Jukebox, VEVO, etc.)
     * - Creates a phonetic / transliteration-tolerant comparison key for Hindi Latin script & Devanagari
     */
    fun normalizeTitle(raw: String): ParsedTitle {
        var text = raw.trim()

        // 1. Extract semantic version tags BEFORE noise stripping
        val versionTags = mutableSetOf<String>()
        VERSION_TAG_REGEX.findAll(text).forEach { m ->
            val tag = m.value.lowercase(Locale.ROOT)
            val canonicalTag = when {
                tag.contains("remix") || tag.contains("club mix") -> "remix"
                tag.contains("slowed") -> "slowed"
                tag.contains("reverb") -> "reverb"
                tag.contains("lofi") || tag.contains("lo-fi") -> "lofi"
                tag.contains("sped") || tag.contains("speed") || tag.contains("nightcore") -> "sped up"
                tag.contains("acoustic") -> "acoustic"
                tag.contains("unplugged") -> "unplugged"
                tag.contains("live") -> "live"
                tag.contains("cover") -> "cover"
                tag.contains("instrumental") -> "instrumental"
                tag.contains("karaoke") -> "karaoke"
                tag.contains("mashup") -> "mashup"
                tag.contains("remaster") -> "remastered"
                tag.contains("reprise") -> "reprise"
                tag.contains("female") -> "female version"
                tag.contains("male") -> "male version"
                tag.contains("sad") -> "sad version"
                else -> tag
            }
            versionTags.add(canonicalTag)
        }

        // 2. Extract Movie / Film segment
        var movie: String? = null
        val movieMatch = MOVIE_REGEX.find(text)
        if (movieMatch != null) {
            movie = (movieMatch.groupValues[1].ifEmpty { movieMatch.groupValues[2] }.ifEmpty { movieMatch.groupValues[3] }).trim()
            text = text.replace(movieMatch.value, " ")
        }

        // 3. Extract featured / with credits
        val extraArtists = mutableSetOf<String>()
        val featMatch = FEAT_REGEX.find(text)
        if (featMatch != null) {
            val artistsRaw = featMatch.groupValues[1]
            extraArtists.addAll(normalizeArtists(artistsRaw))
            text = text.replace(featMatch.value, " ")
        }

        // 4. Strip upload noise brackets & square brackets
        text = NOISE_BRACKETS_REGEX.replace(text, " ")
        text = NOISE_SQUARE_REGEX.replace(text, " ")

        // 5. Split noise after pipes or common upload tails
        text = text.split("|").firstOrNull() ?: text
        text = text.split(" - Topic").firstOrNull() ?: text
        text = text.split("- Topic").firstOrNull() ?: text

        // 6. Strip unbracketed upload noise phrases
        val uploadPhrases = listOf(
            "official video", "official audio", "full video song", "full video",
            "full song", "video song", "audio song", "lyrical song", "lyric video",
            "vevo", "t-series", "zee music company", "sony music india", "yrf",
            "tips official", "speed records", "jukebox", "full album",
        )
        var lower = text.lowercase(Locale.ROOT)
        for (phrase in uploadPhrases) {
            lower = lower.replace(phrase, " ")
        }

        // 7. Unicode NFKD & diacritics stripping
        val nfkd = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        val stripped = nfkd.replace(Regex("\\p{M}"), "")
        val cleanLetters = stripped.replace(Regex("""[^\p{L}0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()

        // 8. Generate comparison key (Devanagari transliteration + Latin Hindi collapsing)
        val latinized = transliterateDevanagariToLatin(cleanLetters)
        val comparisonKey = collapseHindiLatinVariants(latinized)

        return ParsedTitle(
            cleanTitle = cleanLetters,
            comparisonKey = comparisonKey,
            movie = movie?.let { normalizePrimaryText(it) },
            extraArtists = extraArtists,
            versionTags = versionTags,
        )
    }

    /**
     * Splits artist credits on standard separators, casefolds, strips channel/topic noise,
     * and filters out unknown/empty values. Returns a Set of normalized artist names.
     */
    fun normalizeArtists(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        val tokens = raw.split(Regex(""",|&|/|\bfeat\.?\b|\bft\.?\b|\bwith\b|\band\b|\bx\b""", RegexOption.IGNORE_CASE))
        val result = mutableSetOf<String>()
        val ignored = setOf("various artists", "various", "unknown", "null", "va", "unknown artist", "artist")

        for (token in tokens) {
            val nfkd = Normalizer.normalize(token.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            val stripped = nfkd.replace(Regex("\\p{M}"), "")
            var clean = stripped
                .replace(" - topic", "")
                .replace("- topic", "")
                .replace("vevo", "")
                .replace("official", "")
                .replace("records", "")
                .replace("music", "")
                .replace(Regex("""[^\p{L}0-9\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            // Transliterate Devanagari artist names to Latin for consistent matching
            clean = collapseHindiLatinVariants(transliterateDevanagariToLatin(clean))

            if (clean.isNotBlank() && clean !in ignored) {
                result.add(clean)
            }
        }
        return result
    }

    /**
     * Calculates the "same song" probability score in [0.0, 1.0] between two tracks.
     */
    fun sameSong(a: Song, b: Song): Double {
        // 1. Identical Video ID => 1.0
        if (a.id.isNotBlank() && a.id == b.id) return 1.0

        val parsedA = normalizeTitle(a.title)
        val parsedB = normalizeTitle(b.title)

        // 2. Semantic Version Tags must be identical (remix is NOT same as original)
        if (parsedA.versionTags != parsedB.versionTags) {
            return 0.0
        }

        // 3. Duration Closeness Check
        val dA = a.durationSec
        val dB = b.durationSec
        val durationScore: Double
        if (dA > 0 && dB > 0) {
            val diff = abs(dA - dB)
            durationScore = when {
                diff <= 4 -> 1.0
                diff <= 10 -> 0.82
                diff <= 15 -> 0.45
                else -> {
                    // Over 15s difference: cannot be same song unless comparison titles are identical
                    if (parsedA.comparisonKey != parsedB.comparisonKey) {
                        return 0.0
                    }
                    0.20
                }
            }
        } else {
            durationScore = 0.75 // Duration unknown on one or both sides
        }

        // 4. Title Similarity (Token-set ratio + Normalized Levenshtein)
        val titleScore = computeTitleSimilarity(parsedA.comparisonKey, parsedB.comparisonKey)
        if (titleScore < 0.45) {
            return 0.0 // Insufficient title correlation
        }

        // 5. Artist Overlap Check
        val artistsA = normalizeArtists(a.artist) + parsedA.extraArtists
        val artistsB = normalizeArtists(b.artist) + parsedB.extraArtists

        val artistScore: Double
        if (artistsA.isNotEmpty() && artistsB.isNotEmpty()) {
            val intersection = artistsA.intersect(artistsB)
            val union = artistsA.union(artistsB)
            val jaccard = intersection.size.toDouble() / union.size.toDouble()
            // If at least one primary/featured singer matches, give strong weight
            artistScore = if (intersection.isNotEmpty()) max(jaccard, 0.75) else jaccard
        } else {
            // One or both sides missing reliable artist (channel name / Various Artists)
            artistScore = if (titleScore >= 0.85 && durationScore >= 0.8) 0.80 else 0.50
        }

        // 6. Movie / Album Bonus or Penalty
        var movieBonus = 0.0
        val movieA = parsedA.movie ?: a.album?.let { normalizePrimaryText(it) }
        val movieB = parsedB.movie ?: b.album?.let { normalizePrimaryText(it) }
        if (movieA != null && movieB != null) {
            if (movieA == movieB || movieA.contains(movieB) || movieB.contains(movieA)) {
                movieBonus = 0.10
            } else if (titleScore < 0.90) {
                // Different movies with non-identical titles
                movieBonus = -0.15
            }
        }

        // 7. Weighted Composite Score
        val composite = (0.55 * titleScore) + (0.25 * artistScore) + (0.20 * durationScore) + movieBonus
        return composite.coerceIn(0.0, 1.0)
    }

    fun isStrictMatch(a: Song, b: Song): Boolean = sameSong(a, b) >= STRICT_THRESHOLD

    fun isLooseMatch(a: Song, b: Song): Boolean = sameSong(a, b) >= LOOSE_THRESHOLD

    /**
     * Clusters tracks into duplicate groups using union-find with hash-bucket pre-filtering.
     * Returns a map of songId -> canonicalGroupId.
     */
    fun canonicalGroupId(songs: List<Song>): Map<String, String> {
        if (songs.isEmpty()) return emptyMap()
        val n = songs.size
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) root = parent[root]
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) parent[rootI] = rootJ
        }

        // Bucket by first token + duration band (20s) to avoid O(n^2) over entire catalog
        val buckets = mutableMapOf<String, MutableList<Int>>()
        for (i in 0 until n) {
            val s = songs[i]
            val parsed = normalizeTitle(s.title)
            val firstToken = parsed.comparisonKey.split(" ").firstOrNull() ?: ""
            val durBand = if (s.durationSec > 0) (s.durationSec / 20).toInt() else -1
            val key = "$firstToken|$durBand"
            buckets.getOrPut(key) { mutableListOf() }.add(i)
            if (durBand > 0) {
                // Also add to adjacent duration bands
                buckets.getOrPut("$firstToken|${durBand - 1}") { mutableListOf() }.add(i)
                buckets.getOrPut("$firstToken|${durBand + 1}") { mutableListOf() }.add(i)
            }
        }

        val checkedPairs = mutableSetOf<Long>()
        for (bucket in buckets.values) {
            val size = bucket.size
            for (i in 0 until size) {
                for (j in i + 1 until size) {
                    val idxA = bucket[i]
                    val idxB = bucket[j]
                    val pairKey = (idxA.toLong() shl 32) or (idxB.toLong() and 0xFFFFFFFFL)
                    if (checkedPairs.add(pairKey)) {
                        if (isStrictMatch(songs[idxA], songs[idxB])) {
                            union(idxA, idxB)
                        }
                    }
                }
            }
        }

        val result = mutableMapOf<String, String>()
        for (i in 0 until n) {
            val leader = songs[find(i)]
            val leaderParsed = normalizeTitle(leader.title)
            val groupId = "group:${leaderParsed.comparisonKey}|${leader.artist.lowercase(Locale.ROOT).take(15)}"
            result[songs[i].id] = groupId
        }
        return result
    }

    /**
     * Selects the highest-quality representative upload inside a duplicate group:
     * 1. If user already played or liked a member, keep that videoId so history stays consistent.
     * 2. Official audio (ATV / ends with '- Topic' / preferredMusicSource) > Official video (OMV) > UGC/Lyrical.
     * 3. Metadata completeness and duration reliability.
     */
    fun selectRepresentative(group: List<Song>, playedOrLikedIds: Set<String> = emptySet()): Song {
        if (group.size == 1) return group.first()

        // 1. Prioritize already played or liked track
        val existing = group.firstOrNull { it.id in playedOrLikedIds }
        if (existing != null) return existing

        // 2. Score by upload quality tier
        return group.maxWithOrNull(
            compareBy<Song> { song ->
                when {
                    song.preferredMusicSource || song.artist.endsWith("- Topic", ignoreCase = true) -> 300 // ATV
                    song.title.contains("Official Music Video", ignoreCase = true) || song.title.contains("Official Video", ignoreCase = true) -> 200 // OMV
                    song.title.contains("Lyrical", ignoreCase = true) || song.title.contains("Lyrics", ignoreCase = true) -> 100 // Lyrical
                    else -> 50 // UGC
                }
            }.thenBy { it.durationSec > 0 }
                .thenBy { it.album != null }
                .thenBy { it.artistUrl != null }
                .thenByDescending { it.id }, // Stable deterministic tie-breaker
        ) ?: group.first()
    }

    // --- Internal Helpers ---

    private fun normalizePrimaryText(text: String): String {
        val nfkd = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        val stripped = nfkd.replace(Regex("\\p{M}"), "")
        return stripped.replace(Regex("""[^\p{L}0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun computeTitleSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val tokensA = s1.split(" ").filter { it.isNotBlank() }.toSet()
        val tokensB = s2.split(" ").filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val common = tokensA.intersect(tokensB)
        val tokenRatio = (2.0 * common.size) / (tokensA.size + tokensB.size)
        if (tokensA == tokensB) return 1.0

        val maxLen = max(s1.length, s2.length)
        val levSim = if (maxLen == 0) 1.0 else 1.0 - (levenshtein(s1, s2).toDouble() / maxLen)

        return (0.60 * tokenRatio) + (0.40 * levSim)
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else minOf(prev, dp[j], dp[j - 1]) + 1
                prev = temp
            }
        }
        return dp[s2.length]
    }

    /**
     * Collapses transliteration variants for Hindi in Latin script:
     * aa->a, ee->i, oo->u, v->w, ph->f, double letters -> single letter.
     */
    fun collapseHindiLatinVariants(raw: String): String {
        var s = raw.lowercase(Locale.ROOT)
        s = s.replace("aa", "a")
        s = s.replace("ee", "i")
        s = s.replace("oo", "u")
        s = s.replace("v", "w")
        s = s.replace("ph", "f")
        s = s.replace("kh", "k")
        s = s.replace("gh", "g")
        s = s.replace("jh", "j")
        s = s.replace("dh", "d")
        s = s.replace("bh", "b")
        s = s.replace("sh", "s")
        s = s.replace("toh", "to")
        s = s.replace("yeh", "ye")
        s = s.replace("woh", "wo")

        // Collapse duplicate consecutive characters (e.g. "sanamm" -> "sanam", "gustakhiyaan" -> "gustakhiyan")
        val sb = StringBuilder()
        var lastChar = ' '
        for (ch in s) {
            if (ch != lastChar || !ch.isLetter()) {
                sb.append(ch)
                lastChar = ch
            }
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Fast, lightweight Devanagari to Latin phonetic transliterator.
     */
    fun transliterateDevanagariToLatin(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                'अ' -> sb.append("a")
                'आ' -> sb.append("aa")
                'इ' -> sb.append("i")
                'ई' -> sb.append("ee")
                'उ' -> sb.append("u")
                'ऊ' -> sb.append("oo")
                'ऋ' -> sb.append("ri")
                'ए' -> sb.append("e")
                'ऐ' -> sb.append("ai")
                'ओ' -> sb.append("o")
                'औ' -> sb.append("au")
                'ं', 'ँ' -> sb.append("n")
                'ः' -> sb.append("h")
                'ा' -> sb.append("a")
                'ि' -> sb.append("i")
                'ी' -> sb.append("i")
                'ु' -> sb.append("u")
                'ू' -> sb.append("u")
                'ृ' -> sb.append("ri")
                'े' -> sb.append("e")
                'ै' -> sb.append("ai")
                'ो' -> sb.append("o")
                'ौ' -> sb.append("au")
                '्' -> { /* virama: suppresses inherent vowel */ }
                'क' -> sb.append("k")
                'ख' -> sb.append("kh")
                'ग' -> sb.append("g")
                'घ' -> sb.append("gh")
                'च' -> sb.append("ch")
                'छ' -> sb.append("chh")
                'ज' -> sb.append("j")
                'झ' -> sb.append("jh")
                'ट', 'त' -> sb.append("t")
                'ठ', 'थ' -> sb.append("th")
                'ड', 'द' -> sb.append("d")
                'ढ', 'ध' -> sb.append("dh")
                'ण', 'न' -> sb.append("n")
                'प' -> sb.append("p")
                'फ' -> sb.append("ph")
                'ब' -> sb.append("b")
                'भ' -> sb.append("bh")
                'म' -> sb.append("m")
                'य' -> sb.append("y")
                'र' -> sb.append("r")
                'ल' -> sb.append("l")
                'व' -> sb.append("v")
                'श', 'ष', 'स' -> sb.append("s")
                'ह' -> sb.append("h")
                '\u093C' -> { /* nukta: ignore */ }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
