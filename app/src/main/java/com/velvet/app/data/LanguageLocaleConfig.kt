package com.velvet.app.data

import androidx.compose.runtime.Immutable
import com.velvet.app.recommendation.features.MetadataFeatureExtractor
import com.velvet.app.recommendation.vibe.ArtistLanguageMap
import com.velvet.app.recommendation.vibe.SessionVibe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.text.Normalizer
import java.util.Locale

/**
 * Single source of truth for music listening languages, API locale (`hl`/`gl`) parameters,
 * Search pre-hint chips, Genre catalog metadata, and strict cross-language filtering.
 */
@Immutable
data class LanguageSpec(
    val id: String,
    val nativeLabel: String,
    val isoCode: String,
    val hl: String,
    val gl: String,
    val accentArgb: Long,
    val keywords: Set<String>,
    val preHintQueries: List<String>,
)

@Immutable
data class SearchLanguageState(
    val selectedLanguages: List<String>,
    val isoCodes: Set<String>,
    val hl: String,
    val gl: String,
    val summaryLabel: String,
) {
    val primaryLanguage: String
        get() = selectedLanguages.firstOrNull() ?: "English"

    val key: String
        get() = selectedLanguages.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.sorted().joinToString("|")

    val newPipeLocalization: Localization
        get() = Localization(hl, gl)

    val newPipeContentCountry: ContentCountry
        get() = ContentCountry(gl)
}

@Immutable
data class GenreTileSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val primaryColorArgb: Long,
    val secondaryColorArgb: Long,
    val iconKey: String,
    val trendingSeed: String,
    val albumSeed: String,
    val playlistSeeds: List<String>,
    val rotatingSeeds: List<String>,
    val includeGlobalTerms: Boolean = false,
)

object LanguageLocaleConfig {

    val allLanguages: List<LanguageSpec> = listOf(
        LanguageSpec(
            id = "Hindi",
            nativeLabel = "हिन्दी",
            isoCode = "hi",
            hl = "hi",
            gl = "IN",
            accentArgb = 0xFFFF6B6B,
            keywords = setOf("hindi", "bollywood", "hindustani", "bhajan", "aarti", "chalisa", "mantra", "deshbhakti", "indipop"),
            preHintQueries = listOf("Latest Hindi Hits", "Arijit Singh", "Romantic Bollywood", "90s Evergreen Hindi", "Hindi Lo-Fi"),
        ),
        LanguageSpec(
            id = "English",
            nativeLabel = "Global",
            isoCode = "en",
            hl = "en",
            gl = "US",
            accentArgb = 0xFF4DABF7,
            keywords = setOf("english", "billboard", "uk top", "american pop", "western pop", "grammy"),
            preHintQueries = listOf("Global Top 50", "Pop Hits", "Late Night R&B", "Indie Chill", "Classic Rock"),
        ),
        LanguageSpec(
            id = "Punjabi",
            nativeLabel = "ਪੰਜਾਬੀ",
            isoCode = "pa",
            hl = "pa",
            gl = "IN",
            accentArgb = 0xFFFF922B,
            keywords = setOf("punjabi", "bhangra", "panjabi", "pollywood", "gurbani"),
            preHintQueries = listOf("Punjabi Top Hits", "Karan Aujla", "Diljit Dosanjh", "Punjabi Chill", "Bhangra Anthems"),
        ),
        LanguageSpec(
            id = "Bhojpuri",
            nativeLabel = "भोजपुरी",
            isoCode = "bho",
            hl = "hi",
            gl = "IN",
            accentArgb = 0xFFFCC419,
            keywords = setOf("bhojpuri", "chhath", "bihari", "purbi"),
            preHintQueries = listOf("Latest Bhojpuri Hits", "Pawan Singh", "Khesari Lal Yadav", "Bhojpuri Romantic", "Bhojpuri DJ"),
        ),
        LanguageSpec(
            id = "Haryanvi",
            nativeLabel = "हरियाणवी",
            isoCode = "bgc",
            hl = "hi",
            gl = "IN",
            accentArgb = 0xFF51CF66,
            keywords = setOf("haryanvi", "haryanavi", "desi ragni"),
            preHintQueries = listOf("Haryanvi Trending", "Masoom Sharma", "Sapna Choudhary", "Haryanvi Workout", "Latest Haryanvi"),
        ),
        LanguageSpec(
            id = "Tamil",
            nativeLabel = "தமிழ்",
            isoCode = "ta",
            hl = "ta",
            gl = "IN",
            accentArgb = 0xFFCC5DE8,
            keywords = setOf("tamil", "kollywood", "tamizh", "kuthu", "carnatic tamil"),
            preHintQueries = listOf("Tamil Trending", "Anirudh Ravichander", "A.R. Rahman Tamil", "Tamil Melody", "Kollywood Classics"),
        ),
        LanguageSpec(
            id = "Telugu",
            nativeLabel = "తెలుగు",
            isoCode = "te",
            hl = "te",
            gl = "IN",
            accentArgb = 0xFF20C997,
            keywords = setOf("telugu", "tollywood", "telangana folk"),
            preHintQueries = listOf("Telugu Top 40", "Devi Sri Prasad", "Thaman S Hits", "Telugu Melody", "Tollywood Mass Beats"),
        ),
        LanguageSpec(
            id = "Bengali",
            nativeLabel = "বাংলা",
            isoCode = "bn",
            hl = "bn",
            gl = "IN",
            accentArgb = 0xFF339AF0,
            keywords = setOf("bengali", "bangla", "rabindra sangeet", "adhunik bangla", "baul", "tollywood bangla"),
            preHintQueries = listOf("Bengali Modern Hits", "Rabindra Sangeet", "Anupam Roy", "Bangla Rock", "Bengali Romantic"),
        ),
        LanguageSpec(
            id = "Malayalam",
            nativeLabel = "മലയാളം",
            isoCode = "ml",
            hl = "ml",
            gl = "IN",
            accentArgb = 0xFF22B8CF,
            keywords = setOf("malayalam", "mollywood", "mallu", "mapila"),
            preHintQueries = listOf("Malayalam Hits", "Sushin Shyam", "Vineeth Sreenivasan", "Malayalam Melody", "Mollywood Indie"),
        ),
        LanguageSpec(
            id = "Kannada",
            nativeLabel = "ಕನ್ನಡ",
            isoCode = "kn",
            hl = "kn",
            gl = "IN",
            accentArgb = 0xFFF06595,
            keywords = setOf("kannada", "sandalwood", "bhavageethe"),
            preHintQueries = listOf("Kannada Trending", "Vijay Prakash", "Arjun Janya", "Kannada Romantic", "Sandalwood Hits"),
        ),
        LanguageSpec(
            id = "Marathi",
            nativeLabel = "मराठी",
            isoCode = "mr",
            hl = "mr",
            gl = "IN",
            accentArgb = 0xFFFF8787,
            keywords = setOf("marathi", "lavani", "abhang", "natyasangeet", "marathi film"),
            preHintQueries = listOf("Marathi Top Hits", "Ajay-Atul", "Marathi Romantic", "Marathi Lo-Fi", "Abhang & Bhajan"),
        ),
        LanguageSpec(
            id = "Gujarati",
            nativeLabel = "ગુજરાતી",
            isoCode = "gu",
            hl = "gu",
            gl = "IN",
            accentArgb = 0xFF94D82D,
            keywords = setOf("gujarati", "garba", "dandiya", "sugam sangeet"),
            preHintQueries = listOf("Gujarati Trending", "Aditya Gadhvi", "Garba Special", "Gujarati Romantic", "Kirtidan Gadhvi"),
        ),
        LanguageSpec(
            id = "Sanskrit",
            nativeLabel = "संस्कृतम्",
            isoCode = "sa",
            hl = "hi",
            gl = "IN",
            accentArgb = 0xFFFFA94D,
            keywords = setOf("sanskrit", "stotram", "ashtakam", "shloka", "shlok", "suktam", "vedic", "upanishad"),
            preHintQueries = listOf("Sanskrit Stotram", "Shiva Tandav Stotram", "Vedic Mantras", "Gayatri Mantra", "Bhagavad Gita Chants"),
        ),
        LanguageSpec(
            id = "Urdu",
            nativeLabel = "اردو",
            isoCode = "ur",
            hl = "ur",
            gl = "IN",
            accentArgb = 0xFF845EF7,
            keywords = setOf("urdu", "ghazal", "qawwali", "qawali", "nazm", "sufi urdu", "coke studio"),
            preHintQueries = listOf("Urdu Ghazals", "Nusrat Fateh Ali Khan", "Rahat Fateh Ali Khan", "Jagjit Singh", "Urdu Sufi Qawwali"),
        ),
    )

    private val byLowerName = allLanguages.associateBy { it.id.lowercase(Locale.ROOT) }
    private val byIsoCode = allLanguages.associateBy { it.isoCode.lowercase(Locale.ROOT) }

    private val indianLanguages = setOf(
        "hindi", "punjabi", "bhojpuri", "haryanvi", "tamil", "telugu",
        "bengali", "malayalam", "kannada", "marathi", "gujarati", "sanskrit", "urdu",
    )

    private val curatedExtraArtistsByIso: Map<String, Set<String>> = mapOf(
        "bho" to setOf("pawan singh", "khesari lal yadav", "shilpi raj", "dinesh lal yadav", "nirahua", "riteish pandey", "arvind akela kallu"),
        "bgc" to setOf("masoom sharma", "dhanda nyoliwala", "sapna choudhary", "renuka panwar", "gulzaar chhaniwala", "kp kundu"),
        "mr" to setOf("ajay atul", "ajay-atul", "avadhoot gupte", "belaga", "vaishali samant", "bella shende", "shankar mahadevan marathi"),
        "gu" to setOf("jigrra", "aditya gadhvi", "kirtidan gadhvi", "geeta rabari", "kinjal dave", "osman mir", "parthiv gohil"),
        "bn" to setOf("anupam roy", "rupam islam", "fossils", "nachiketa", "arijit singh bengali", "shreya ghoshal bengali", "hemanta mukherjee"),
        "ur" to setOf("nusrat fateh ali khan", "rahat fateh ali khan", "mehdi hassan", "ghulam ali", "abida parveen", "farida khanum"),
        "sa" to setOf("uma mohan", "madhavi madhukar jha", "pandit jasraj", "sanatan sankirtan"),
    )

    fun specForName(name: String): LanguageSpec? =
        byLowerName[name.trim().lowercase(Locale.ROOT)]

    fun mapLanguageToIso(raw: String): String? {
        val clean = raw.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return null
        byLowerName[clean]?.let { return it.isoCode }
        if (clean in byIsoCode) return clean
        return when (clean) {
            "bangla" -> "bn"
            "panjabi" -> "pa"
            "tamizh" -> "ta"
            else -> null
        }
    }

    fun mapLanguagesToIso(languages: List<String>): Set<String> =
        languages.mapNotNullTo(linkedSetOf(), ::mapLanguageToIso)

    fun normalizeSelectedLanguages(rawLanguages: List<String>): List<String> {
        val normalized = rawLanguages
            .mapNotNull { raw -> specForName(raw)?.id }
            .distinct()
        return normalized.ifEmpty { listOf("Hindi", "English") }
    }

    fun fromSelectedLanguages(rawLanguages: List<String>): SearchLanguageState {
        val normalized = normalizeSelectedLanguages(rawLanguages)
        val specs = normalized.mapNotNull(::specForName)
        val isos = specs.mapTo(linkedSetOf()) { it.isoCode }
        val primary = specs.firstOrNull()
        val hasIndian = normalized.any { it.lowercase(Locale.ROOT) in indianLanguages }
        val gl = if (hasIndian) "IN" else (primary?.gl ?: "US")
        val hl = primary?.hl ?: "en"
        val summary = when {
            normalized.size <= 2 -> normalized.joinToString(" • ")
            else -> "${normalized.take(2).joinToString(" • ")} +${normalized.size - 2}"
        }
        return SearchLanguageState(
            selectedLanguages = normalized,
            isoCodes = isos,
            hl = hl,
            gl = gl,
            summaryLabel = summary,
        )
    }

    /**
     * Generates pre-hint Search chips strictly scoped to the user's selected languages.
     */
    fun preHintQueriesFor(state: SearchLanguageState, limit: Int = 10): List<String> {
        val specs = state.selectedLanguages.mapNotNull(::specForName).ifEmpty { listOfNotNull(specForName("English")) }
        val maxLen = specs.maxOfOrNull { it.preHintQueries.size } ?: 0
        val out = mutableListOf<String>()
        for (idx in 0 until maxLen) {
            for (spec in specs) {
                spec.preHintQueries.getOrNull(idx)?.let { out += it }
            }
        }
        return out.distinct().take(limit)
    }

    /**
     * Generates dynamic Spotify-style Genre tiles strictly adhering to the selected languages.
     * Universal moods receive active-language subtitles & queries; regional tiles only appear
     * when their matching languages are active.
     */
    fun genreCatalogFor(state: SearchLanguageState): List<GenreTileSpec> {
        val activeLangs = state.selectedLanguages
        val activeIsos = state.isoCodes
        val langTag = if (activeLangs.size == 1) activeLangs.first() else activeLangs.take(2).joinToString(" & ")
        val tiles = mutableListOf<GenreTileSpec>()

        fun addUniversal(
            id: String,
            title: String,
            subtitleTemplate: String,
            primaryColorArgb: Long,
            secondaryColorArgb: Long,
            iconKey: String,
            trendingSeed: String,
            albumSeed: String,
            playlistSeeds: List<String>,
            rotatingSeeds: List<String>,
        ) {
            tiles += GenreTileSpec(
                id = id,
                title = title,
                subtitle = subtitleTemplate.replace("{lang}", langTag),
                primaryColorArgb = primaryColorArgb,
                secondaryColorArgb = secondaryColorArgb,
                iconKey = iconKey,
                trendingSeed = trendingSeed,
                albumSeed = albumSeed,
                playlistSeeds = playlistSeeds,
                rotatingSeeds = rotatingSeeds,
            )
        }

        // Spotlight language-authentic tiles first
        if ("hi" in activeIsos) {
            tiles += GenreTileSpec(
                id = "bollywood",
                title = "Bollywood",
                subtitle = "Hindi film charts & evergreens",
                primaryColorArgb = 0xFFE13300,
                secondaryColorArgb = 0xFFFF7A33,
                iconKey = "sparkles",
                trendingSeed = "Bollywood trending songs",
                albumSeed = "Latest Bollywood soundtrack albums",
                playlistSeeds = listOf("Bollywood top hits playlist", "Bollywood blockbuster mix", "Modern Hindi cinema hits"),
                rotatingSeeds = listOf("Bollywood feel good hits", "Bollywood unplugged gems", "Hindi movie chartbusters"),
            )
        }
        if ("pa" in activeIsos || "bgc" in activeIsos) {
            val regionName = if ("pa" in activeIsos) "Punjabi" else "Haryanvi"
            tiles += GenreTileSpec(
                id = "punjabi_beats",
                title = if ("pa" in activeIsos && "bgc" !in activeIsos) "Punjabi Beats" else "Desi & Punjabi",
                subtitle = "$regionName anthems & bass bangers",
                primaryColorArgb = 0xFFDC6803,
                secondaryColorArgb = 0xFFF59E0B,
                iconKey = "bolt",
                trendingSeed = "$regionName trending songs",
                albumSeed = "New $regionName albums",
                playlistSeeds = listOf("$regionName top hits playlist", "$regionName gym & drive mix", "$regionName chill vibes"),
                rotatingSeeds = listOf("$regionName urban beats", "$regionName chart hits", "$regionName fresh drops"),
            )
        }
        if ("ta" in activeIsos) {
            tiles += GenreTileSpec(
                id = "kollywood",
                title = "Kollywood",
                subtitle = "Tamil film hits & fresh drops",
                primaryColorArgb = 0xFF7F1D1D,
                secondaryColorArgb = 0xFFEF4444,
                iconKey = "disc",
                trendingSeed = "Tamil trending songs",
                albumSeed = "Latest Tamil movie albums",
                playlistSeeds = listOf("Tamil top hits playlist", "Kollywood melody mix", "Anirudh & Rahman Tamil hits"),
                rotatingSeeds = listOf("Tamil love melodies", "Tamil night drive", "Latest Tamil chartbusters"),
            )
        }
        if ("te" in activeIsos) {
            tiles += GenreTileSpec(
                id = "tollywood",
                title = "Tollywood",
                subtitle = "Telugu OSTs & chart hits",
                primaryColorArgb = 0xFF0F766E,
                secondaryColorArgb = 0xFF14B8A6,
                iconKey = "disc",
                trendingSeed = "Telugu trending songs",
                albumSeed = "Latest Telugu movie albums",
                playlistSeeds = listOf("Telugu top hits playlist", "Tollywood mass beats", "Telugu soothing melodies"),
                rotatingSeeds = listOf("Telugu romantic gems", "Latest Telugu hits", "Telugu chartbusters"),
            )
        }
        if ("ml" in activeIsos) {
            tiles += GenreTileSpec(
                id = "mollywood",
                title = "Mollywood",
                subtitle = "Malayalam cinema & indie gems",
                primaryColorArgb = 0xFF0369A1,
                secondaryColorArgb = 0xFF22D3EE,
                iconKey = "headphones",
                trendingSeed = "Malayalam trending songs",
                albumSeed = "Latest Malayalam albums",
                playlistSeeds = listOf("Malayalam hits playlist", "Malayalam rain melodies", "Malayalam feel good"),
                rotatingSeeds = listOf("Malayalam acoustic", "Mollywood new songs", "Malayalam classics"),
            )
        }
        if ("kn" in activeIsos) {
            tiles += GenreTileSpec(
                id = "sandalwood",
                title = "Sandalwood",
                subtitle = "Kannada hits & soulful OSTs",
                primaryColorArgb = 0xFF9D174D,
                secondaryColorArgb = 0xFFEC4899,
                iconKey = "music",
                trendingSeed = "Kannada trending songs",
                albumSeed = "Latest Kannada albums",
                playlistSeeds = listOf("Kannada top hits playlist", "Kannada romantic melodies", "Sandalwood chartbusters"),
                rotatingSeeds = listOf("Kannada new releases", "Kannada feel good", "Kannada unplugged"),
            )
        }
        if ("en" in activeIsos) {
            tiles += GenreTileSpec(
                id = "western_pop",
                title = "Global Pop",
                subtitle = "Chart toppers & viral hits",
                primaryColorArgb = 0xFF1E3A8A,
                secondaryColorArgb = 0xFF3B82F6,
                iconKey = "globe",
                trendingSeed = "English pop trending hits",
                albumSeed = "New English pop albums",
                playlistSeeds = listOf("Pop essentials playlist", "Global viral pop hits", "Synth pop favorites"),
                rotatingSeeds = listOf("English chart hits", "Today's pop gems", "Feel-good pop"),
            )
        }
        if ("hi" in activeIsos || "sa" in activeIsos || "mr" in activeIsos || "gu" in activeIsos || "bho" in activeIsos) {
            val bhaktiLang = when {
                "sa" in activeIsos -> "Sanskrit"
                "mr" in activeIsos && "hi" !in activeIsos -> "Marathi"
                "gu" in activeIsos && "hi" !in activeIsos -> "Gujarati"
                "bho" in activeIsos && "hi" !in activeIsos -> "Bhojpuri"
                else -> "Hindi"
            }
            tiles += GenreTileSpec(
                id = "devotional",
                title = "Devotional",
                subtitle = "$bhaktiLang bhajans, mantras & peace",
                primaryColorArgb = 0xFFB45309,
                secondaryColorArgb = 0xFFF59E0B,
                iconKey = "sun",
                trendingSeed = "$bhaktiLang devotional bhajan songs",
                albumSeed = "$bhaktiLang devotional albums",
                playlistSeeds = listOf("$bhaktiLang morning bhajans playlist", "Peaceful mantra chants", "Aarti & Chalisa collection"),
                rotatingSeeds = listOf("$bhaktiLang soulful bhajan", "Evening sandhya aarti", "Sacred stotram & peace"),
            )
        }
        if ("hi" in activeIsos || "ur" in activeIsos || "pa" in activeIsos) {
            val sufiLang = if ("ur" in activeIsos && "hi" !in activeIsos) "Urdu" else "Hindi Urdu"
            tiles += GenreTileSpec(
                id = "sufi_ghazal",
                title = "Sufi & Ghazal",
                subtitle = "Poetic vocals & qawwali warmth",
                primaryColorArgb = 0xFF5B21B6,
                secondaryColorArgb = 0xFF8B5CF6,
                iconKey = "feather",
                trendingSeed = "$sufiLang sufi ghazal songs",
                albumSeed = "$sufiLang ghazal albums",
                playlistSeeds = listOf("Sufi & Qawwali essentials", "Evening ghazal collection", "Soulful poetry classics"),
                rotatingSeeds = listOf("Unplugged sufi gems", "Timeless ghazals", "Qawwali & sufi sessions"),
            )
        }

        // Core language-adapted mood & activity tiles (automatically localized to selected languages)
        addUniversal(
            id = "romantic",
            title = "Romantic",
            subtitleTemplate = "{lang} love & warm melodies",
            primaryColorArgb = 0xFFBE123C,
            secondaryColorArgb = 0xFFFB7185,
            iconKey = "heart",
            trendingSeed = "romantic songs",
            albumSeed = "romantic love albums",
            playlistSeeds = listOf("romantic top hits playlist", "late night love songs", "acoustic romance mix"),
            rotatingSeeds = listOf("sweet romantic ballads", "all time love melodies", "soft romantic duets"),
        )
        addUniversal(
            id = "sad_soulful",
            title = "Sad & Soulful",
            subtitleTemplate = "Deep {lang} heartbreak & healing",
            primaryColorArgb = 0xFF312E81,
            secondaryColorArgb = 0xFF6366F1,
            iconKey = "rain",
            trendingSeed = "sad soulful songs",
            albumSeed = "sad acoustic albums",
            playlistSeeds = listOf("soulful sad songs playlist", "rainy night feels", "broken heart ballads"),
            rotatingSeeds = listOf("emotional deep vocals", "midnight melancholic", "unplugged sad songs"),
        )
        addUniversal(
            id = "lofi_chill",
            title = "Chill & Lo-Fi",
            subtitleTemplate = "Laid-back {lang} study & night grooves",
            primaryColorArgb = 0xFF155E75,
            secondaryColorArgb = 0xFF06B6D4,
            iconKey = "moon",
            trendingSeed = "lofi chill songs",
            albumSeed = "chill acoustic lo-fi albums",
            playlistSeeds = listOf("chill lofi playlist", "late night drive vibes", "focus & study chill"),
            rotatingSeeds = listOf("mellow chill grooves", "coffee shop acoustic", "dreamy slowed reverb"),
        )
        addUniversal(
            id = "party_dance",
            title = "Party & Dance",
            subtitleTemplate = "High-energy {lang} club & weekend hits",
            primaryColorArgb = 0xFF86198F,
            secondaryColorArgb = 0xFFD946EF,
            iconKey = "flame",
            trendingSeed = "party dance hit songs",
            albumSeed = "dance party hits albums",
            playlistSeeds = listOf("weekend party hits playlist", "high energy dance mix", "club & DJ bangers"),
            rotatingSeeds = listOf("floor fillers", "upbeat celebration hits", "groove dance floor"),
        )
        addUniversal(
            id = "workout",
            title = "Workout",
            subtitleTemplate = "{lang} power tracks & PR fuel",
            primaryColorArgb = 0xFF166534,
            secondaryColorArgb = 0xFF22C55E,
            iconKey = "dumbbell",
            trendingSeed = "workout gym motivation songs",
            albumSeed = "high tempo workout albums",
            playlistSeeds = listOf("gym pump workout playlist", "cardio running beats", "beast mode energy"),
            rotatingSeeds = listOf("heavy bass workout", "adrenaline running mix", "power anthem hits"),
        )
        addUniversal(
            id = "retro_90s",
            title = "90s & Retro",
            subtitleTemplate = "Golden-era {lang} classics",
            primaryColorArgb = 0xFF9F1239,
            secondaryColorArgb = 0xFFF43F5E,
            iconKey = "vinyl",
            trendingSeed = "90s retro classic songs",
            albumSeed = "90s evergreen classics albums",
            playlistSeeds = listOf("90s evergreen hits playlist", "old is gold retro mix", "nostalgic 90s gems"),
            rotatingSeeds = listOf("80s 90s timeless classics", "golden era hits", "vintage favorites"),
        )
        addUniversal(
            id = "indie_acoustic",
            title = "Indie & Acoustic",
            subtitleTemplate = "Fresh {lang} independent voices",
            primaryColorArgb = 0xFF065F46,
            secondaryColorArgb = 0xFF34D399,
            iconKey = "guitar",
            trendingSeed = "indie acoustic songs",
            albumSeed = "indie acoustic albums",
            playlistSeeds = listOf("indie acoustic rising playlist", "unplugged indie voices", "singer songwriter gems"),
            rotatingSeeds = listOf("fresh independent artists", "stripped acoustic sessions", "modern indie folk"),
        )
        if ("en" in activeIsos || "hi" in activeIsos || "pa" in activeIsos) {
            addUniversal(
                id = "hip_hop",
                title = "Hip-Hop & Rap",
                subtitleTemplate = "{lang} bars, drill & trap flow",
                primaryColorArgb = 0xFF7C2D12,
                secondaryColorArgb = 0xFFEA580C,
                iconKey = "mic",
                trendingSeed = "hip hop rap hits",
                albumSeed = "hip hop rap albums",
                playlistSeeds = listOf("hip hop essentials playlist", "underground rap bars", "trap & drill flow"),
                rotatingSeeds = listOf("lyrical hip hop", "bass heavy rap", "fresh hip hop drops"),
            )
        }
        return tiles.distinctBy { it.id }
    }
}

/**
 * Pure, testable Search & Genre language scoping and anti-leak filter engine.
 */
object SearchLanguageFilter {

    private val scriptRanges: Map<String, CharRange> = mapOf(
        "Guru" to '\u0A00'..'\u0A7F',
        "Beng" to '\u0980'..'\u09FF',
        "Taml" to '\u0B80'..'\u0BFF',
        "Telu" to '\u0C00'..'\u0C7F',
        "Knda" to '\u0C80'..'\u0CFF',
        "Mlym" to '\u0D00'..'\u0D7F',
        "Gujr" to '\u0A80'..'\u0AFF',
        "Deva" to '\u0900'..'\u097F',
        "Arab" to '\u0600'..'\u06FF',
    )

    private val broadQueryTokens = setOf(
        "top", "hits", "hit", "trending", "latest", "new", "songs", "song", "music",
        "romantic", "love", "sad", "party", "dance", "chill", "lofi", "lo-fi", "workout",
        "gym", "retro", "90s", "80s", "classic", "classics", "playlist", "playlists",
        "album", "albums", "mix", "vibes", "indie", "acoustic", "rap", "hip", "hop", "pop",
        "rock", "edm", "bhajan", "devotional", "sufi", "ghazal", "sleep", "focus", "drive",
    )

    private val multilingualMarkers = listOf(
        "multilingual", "pan india", "pan-india", "all languages", "instrumental",
        "piano cover", "violin cover", "flute instrumental", "guitar instrumental",
        "lofi beats", "lo-fi beats", "coke studio", "world music", "mashup",
    )

    private val extraArtistsByIso: Map<String, Set<String>> = mapOf(
        "bho" to setOf("pawan singh", "khesari lal", "khesari lal yadav", "shilpi raj", "dinesh lal yadav", "nirahua", "riteish pandey", "arvind akela"),
        "bgc" to setOf("masoom sharma", "dhanda nyoliwala", "sapna choudhary", "renuka panwar", "gulzaar chhaniwala"),
        "mr" to setOf("ajay-atul", "ajay atul", "avadhoot gupte", "vaishali samant", "bella shende", "saleel kulkarni"),
        "gu" to setOf("jigrra", "aditya gadhvi", "kirtidan gadhvi", "geeta rabari", "kinjal dave", "osman mir"),
        "bn" to setOf("anupam roy", "rupam islam", "fossils", "nachiketa chakraborty", "monali thakur", "lagnajita chakraborty"),
        "ur" to setOf("nusrat fateh ali khan", "rahat fateh ali khan", "mehdi hassan", "ghulam ali", "abida parveen", "farida khanum", "ali sethi"),
        "sa" to setOf("pandit jasraj", "madhavi madhukar"),
    )

    private val scriptIsoCompatibility: Map<String, Set<String>> = mapOf(
        "Guru" to setOf("pa"),
        "Beng" to setOf("bn"),
        "Taml" to setOf("ta"),
        "Telu" to setOf("te"),
        "Knda" to setOf("kn"),
        "Mlym" to setOf("ml"),
        "Gujr" to setOf("gu"),
        "Arab" to setOf("ur"),
        "Deva" to setOf("hi", "mr", "sa", "bho", "bgc"),
    )

    private val extractor = MetadataFeatureExtractor()

    private fun normalize(raw: String): String = Normalizer
        .normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "")
        .trim()

    fun isInherentlyMultilingual(
        title: String,
        artist: String = "",
        subtitle: String = "",
    ): Boolean {
        val combined = "${normalize(title)} ${normalize(artist)} ${normalize(subtitle)}"
        if (multilingualMarkers.any { combined.contains(it) }) return true

        val detectedNonLatinScripts = scriptRanges.entries
            .filter { (script, range) -> script != "Deva" && combined.any { it in range } }
            .map { it.key }
        if (detectedNonLatinScripts.size >= 2) return true
        if ("Deva" in scriptRanges && combined.any { it in '\u0900'..'\u097F' } && detectedNonLatinScripts.isNotEmpty()) {
            return true
        }
        return false
    }

    /**
     * Infers ISO language code for any Search/Genre item (`Song`, `AlbumRef`, or `ArtistRef`).
     * Returns `null` when language cannot be established reliably (so genuine Latin-script local
     * releases from a language-scoped `hl`/`gl` query aren't falsely discarded).
     */
    fun inferLanguageIso(
        title: String,
        artist: String = "",
        subtitle: String = "",
        song: Song? = null,
    ): String? {
        val rawCombined = "$title $artist $subtitle"
        // 1. Exclusive non-Latin script beats everything
        for ((script, range) in scriptRanges) {
            if (script == "Deva") continue
            if (rawCombined.any { it in range }) {
                return scriptIsoCompatibility[script]?.firstOrNull()
            }
        }
        if (rawCombined.any { it in '\u0900'..'\u097F' }) {
            val low = normalize(rawCombined)
            return when {
                "bhojpuri" in low -> "bho"
                "haryanvi" in low -> "bgc"
                "marathi" in low || "lavani" in low || "abhang" in low -> "mr"
                "sanskrit" in low || "stotram" in low || "shloka" in low -> "sa"
                else -> "hi"
            }
        }

        // 2. Explicit language token match in title/subtitle/artist
        val lowCombined = " ${normalize(rawCombined).replace(Regex("""[^\p{L}0-9\s]"""), " ")} "
        for (spec in LanguageLocaleConfig.allLanguages) {
            if (spec.keywords.any { kw -> lowCombined.contains(" $kw ") }) {
                return spec.isoCode
            }
        }

        // 3. Curated & learned artist map
        val artistNorm = normalize(artist)
        for ((iso, artists) in extraArtistsByIso) {
            if (artists.any { artistNorm.contains(it) }) return iso
        }
        val inferredArtist = ArtistLanguageMap.inferArtistLanguage(artist)
            ?: ArtistLanguageMap.inferArtistLanguage(title)
        if (inferredArtist != null && inferredArtist.second >= 0.72) {
            return inferredArtist.first.lowercase(Locale.ROOT)
        }

        // 4. Song metadata feature extractor when Song is available
        if (song != null) {
            val feat = extractor.extract(song)
            val resolved = SessionVibe.resolveSongLanguage(song, feat)
            if (resolved.second >= 0.65 && resolved.first.isNotEmpty()) {
                return resolved.first.first()
            }
        }
        return null
    }

    /**
     * Scopes broad/generic queries to the active selected language(s) when the query does not
     * already reference a language or specific non-generic entity.
     */
    fun scopeSearchQuery(
        query: String,
        state: SearchLanguageState,
        forceLanguageHint: Boolean = false,
    ): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return trimmed
        val norm = normalize(trimmed)
        val alreadyMentionsLanguage = LanguageLocaleConfig.allLanguages.any { spec ->
            spec.keywords.any { kw -> Regex("""\b${Regex.escape(kw)}\b""").containsMatchIn(norm) }
        }
        if (alreadyMentionsLanguage) return trimmed

        val tokens = norm.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
        val isBroad = forceLanguageHint || (tokens.isNotEmpty() && tokens.all { it in broadQueryTokens })
        if (!isBroad) return trimmed

        val langPrefix = state.primaryLanguage
        return "$langPrefix $trimmed".trim()
    }

    /**
     * Strict consistency predicate for items displayed inside Search & Genre screens.
     */
    fun isAllowedForState(
        title: String,
        artist: String = "",
        subtitle: String = "",
        song: Song? = null,
        state: SearchLanguageState,
        explicitQuery: String = "",
    ): Boolean {
        if (isInherentlyMultilingual(title, artist, subtitle)) return true
        val detectedIso = inferLanguageIso(title, artist, subtitle, song) ?: return true
        if (detectedIso in state.isoCodes) return true

        // Allow Devanagari overlaps cleanly when related Devanagari languages share script without opposing keyword
        val hasDeva = "$title $artist $subtitle".any { it in '\u0900'..'\u097F' }
        if (hasDeva && detectedIso == "hi" && state.isoCodes.any { it in setOf("hi", "mr", "sa", "bho", "bgc") }) {
            return true
        }

        // If the user explicitly typed that specific language or artist token inside their query, honor explicit user intent
        val queryNorm = normalize(explicitQuery)
        if (queryNorm.isNotBlank()) {
            val spec = LanguageLocaleConfig.allLanguages.firstOrNull { it.isoCode == detectedIso }
            if (spec != null && spec.keywords.any { kw -> queryNorm.contains(kw) }) return true
        }
        return false
    }

    fun filterSongs(
        songs: List<Song>,
        state: SearchLanguageState,
        explicitQuery: String = "",
    ): List<Song> = songs.filter { song ->
        isAllowedForState(
            title = song.title,
            artist = song.artist,
            subtitle = song.album.orEmpty(),
            song = song,
            state = state,
            explicitQuery = explicitQuery,
        )
    }

    fun filterAlbums(
        albums: List<AlbumRef>,
        state: SearchLanguageState,
        explicitQuery: String = "",
    ): List<AlbumRef> = albums.filter { album ->
        isAllowedForState(
            title = album.title,
            artist = album.subtitle,
            subtitle = "",
            song = null,
            state = state,
            explicitQuery = explicitQuery,
        )
    }

    fun filterArtists(
        artists: List<ArtistRef>,
        state: SearchLanguageState,
        explicitQuery: String = "",
    ): List<ArtistRef> = artists.filter { artist ->
        isAllowedForState(
            title = "",
            artist = artist.name,
            subtitle = "",
            song = null,
            state = state,
            explicitQuery = explicitQuery,
        )
    }

    fun filterBrowseItems(
        items: List<BrowseItem>,
        state: SearchLanguageState,
        explicitQuery: String = "",
    ): List<BrowseItem> = items.filter { item ->
        when (item) {
            is BrowseItem.SongItem -> isAllowedForState(item.song.title, item.song.artist, item.song.album.orEmpty(), item.song, state, explicitQuery)
            is BrowseItem.AlbumItem -> isAllowedForState(item.album.title, item.album.subtitle, "", null, state, explicitQuery)
            is BrowseItem.ArtistItem -> isAllowedForState("", item.artist.name, "", null, state, explicitQuery)
        }
    }

    fun filterSuggestions(
        suggestions: List<String>,
        rawQuery: String,
        state: SearchLanguageState,
    ): List<String> {
        val cleaned = suggestions.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val strict = cleaned.filter { s ->
            isAllowedForState(
                title = s,
                artist = s,
                subtitle = "",
                song = null,
                state = state,
                explicitQuery = rawQuery,
            )
        }
        return strict.sortedByDescending { suggestion ->
            val iso = inferLanguageIso(suggestion, suggestion)
            when {
                iso != null && iso in state.isoCodes -> 2
                normalize(suggestion).contains(state.primaryLanguage.lowercase(Locale.ROOT)) -> 2
                iso == null -> 1
                else -> 0
            }
        }
    }
}
