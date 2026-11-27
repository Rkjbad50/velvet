package com.velvet.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.velvet.app.R
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.BrowseItem
import com.velvet.app.data.LibraryData
import com.velvet.app.data.YouTube
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.data.thumbSized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private data class SurveyLanguageOption(
    val id: String,
    val nativeLabel: String,
    val accentHint: Color,
)

private data class SurveyGenreOption(
    val key: String,
    val subtitle: String,
    val languagesHint: Set<String> = emptySet(),
    val swatch: Color,
)

private val SURVEY_LANGUAGES: List<SurveyLanguageOption> by lazy {
    com.velvet.app.data.LanguageLocaleConfig.allLanguages.map { spec ->
        SurveyLanguageOption(spec.id, spec.nativeLabel, Color(spec.accentArgb))
    }
}

/**
 * Musical era filter pills available in the onboarding artist picker.
 */
enum class SurveyArtistEra(val label: String, val shortLabel: String) {
    ALL("All Eras", "All"),
    CLASSICS_50_70("1950s–70s Classics", "50s–70s"),
    GOLDEN_80_90("1980s–90s Evergreen", "80s–90s"),
    ERA_2000S("2000s Icons", "2000s"),
    MODERN_2010_NOW("2010s–Present", "2010s+"),
}

/**
 * Curated artist entry tagged with its primary musical era.
 */
data class SurveyArtistEntry(
    val name: String,
    val era: SurveyArtistEra,
)

/**
 * Curated multi-era artist catalog spanning all languages in [com.velvet.app.data.LanguageLocaleConfig].
 */
object SurveyArtistCatalog {
    private val ERA_DISPLAY_ORDER = listOf(
        SurveyArtistEra.MODERN_2010_NOW,
        SurveyArtistEra.GOLDEN_80_90,
        SurveyArtistEra.ERA_2000S,
        SurveyArtistEra.CLASSICS_50_70,
    )

    private val DEFAULT_LANGUAGES = listOf("Hindi", "English", "Punjabi")

    private fun eraArtists(
        classics5070: List<String>,
        golden8090: List<String>,
        era2000s: List<String>,
        modern2010Now: List<String>,
    ): List<SurveyArtistEntry> = buildList {
        classics5070.forEach { add(SurveyArtistEntry(it, SurveyArtistEra.CLASSICS_50_70)) }
        golden8090.forEach { add(SurveyArtistEntry(it, SurveyArtistEra.GOLDEN_80_90)) }
        era2000s.forEach { add(SurveyArtistEntry(it, SurveyArtistEra.ERA_2000S)) }
        modern2010Now.forEach { add(SurveyArtistEntry(it, SurveyArtistEra.MODERN_2010_NOW)) }
    }

    val artistsByLanguage: Map<String, List<SurveyArtistEntry>> = mapOf(
        "Hindi" to eraArtists(
            classics5070 = listOf(
                "Mohammed Rafi", "Lata Mangeshkar", "Kishore Kumar", "Asha Bhosle",
                "Mukesh", "Manna Dey", "R.D. Burman", "S.D. Burman",
                "Hemant Kumar", "Geeta Dutt", "Mahendra Kapoor", "Talat Mahmood",
            ),
            golden8090 = listOf(
                "Kumar Sanu", "Alka Yagnik", "Udit Narayan", "Sonu Nigam",
                "Kavita Krishnamurthy", "Abhijeet Bhattacharya", "Anuradha Paudwal", "Sadhana Sargam",
                "Nadeem-Shravan", "Jatin-Lalit", "Bappi Lahiri", "S.P. Balasubrahmanyam",
                "Jagjit Singh", "Pankaj Udhas",
            ),
            era2000s = listOf(
                "KK", "Shaan", "Mohit Chauhan", "Sunidhi Chauhan",
                "Atif Aslam", "Pritam", "Himesh Reshammiya", "Vishal-Shekhar",
                "Rahat Fateh Ali Khan", "Sukhwinder Singh", "Shankar-Ehsaan-Loy",
                "Roop Kumar Rathod", "Shreya Ghoshal",
            ),
            modern2010Now = listOf(
                "Arijit Singh", "Jubin Nautiyal", "Armaan Malik", "Neha Kakkar",
                "Ankit Tiwari", "Darshan Raval", "Mithoon", "Vishal Mishra",
                "Asees Kaur", "Sachet-Parampara", "Jasleen Royal", "Amit Trivedi",
                "Badshah", "Diljit Dosanjh",
            ),
        ),
        "English" to eraArtists(
            classics5070 = listOf(
                "The Beatles", "Queen", "Elvis Presley", "Pink Floyd",
                "Elton John", "Bee Gees", "ABBA", "Eagles",
                "Led Zeppelin", "Fleetwood Mac",
            ),
            golden8090 = listOf(
                "Michael Jackson", "Madonna", "Whitney Houston", "Bryan Adams",
                "Backstreet Boys", "George Michael", "Nirvana", "Celine Dion",
                "Mariah Carey", "Prince",
            ),
            era2000s = listOf(
                "Coldplay", "Linkin Park", "Eminem", "Rihanna",
                "Britney Spears", "Maroon 5", "Akon", "Alicia Keys",
                "Beyonce", "Katy Perry", "Green Day",
            ),
            modern2010Now = listOf(
                "The Weeknd", "Taylor Swift", "Billie Eilish", "Ed Sheeran",
                "Dua Lipa", "Bruno Mars", "Justin Bieber", "Ariana Grande",
                "Drake", "Olivia Rodrigo", "Sabrina Carpenter", "Harry Styles",
                "Post Malone",
            ),
        ),
        "Punjabi" to eraArtists(
            classics5070 = listOf(
                "Surinder Kaur", "Asa Singh Mastana", "Yamla Jatt", "Mohd. Sadiq",
            ),
            golden8090 = listOf(
                "Gurdas Maan", "Kuldeep Manak", "Hans Raj Hans", "Jazzy B",
                "Harbhajan Mann", "Amar Singh Chamkila",
            ),
            era2000s = listOf(
                "Babbu Maan", "Amrinder Gill", "Gippy Grewal", "Garry Sandhu",
                "Sukhbir", "Yo Yo Honey Singh",
            ),
            modern2010Now = listOf(
                "Diljit Dosanjh", "Karan Aujla", "AP Dhillon", "Sidhu Moose Wala",
                "Shubh", "Guru Randhawa", "B Praak", "Harrdy Sandhu",
                "Satinder Sartaaj", "Arjan Dhillon",
            ),
        ),
        "Tamil" to eraArtists(
            classics5070 = listOf(
                "T.M. Soundararajan", "P. Susheela", "S. Janaki", "K.J. Yesudas",
            ),
            golden8090 = listOf(
                "Ilaiyaraaja", "A.R. Rahman", "S.P. Balasubrahmanyam", "K.S. Chithra",
                "Mano", "Unnikrishnan",
            ),
            era2000s = listOf(
                "Harris Jayaraj", "Yuvan Shankar Raja", "Karthik", "Vijay Antony",
                "G.V. Prakash Kumar", "Harish Raghavendra",
            ),
            modern2010Now = listOf(
                "Anirudh Ravichander", "Sid Sriram", "Santhosh Narayanan", "Pradeep Kumar",
                "Jonita Gandhi", "Hiphop Tamizha", "Shreya Ghoshal",
            ),
        ),
        "Telugu" to eraArtists(
            classics5070 = listOf(
                "Ghantasala", "P. Susheela", "S. Janaki", "S.P. Balasubrahmanyam",
            ),
            golden8090 = listOf(
                "Ilaiyaraaja", "M.M. Keeravani", "K.S. Chithra", "Mano",
                "Vandemataram Srinivas",
            ),
            era2000s = listOf(
                "Devi Sri Prasad", "Mani Sharma", "KK", "Mickey J Meyer",
                "Karthik", "Udit Narayan",
            ),
            modern2010Now = listOf(
                "Thaman S", "Sid Sriram", "Anirudh Ravichander", "Anurag Kulkarni",
                "Armaan Malik", "Shreya Ghoshal", "Mangli",
            ),
        ),
        "Malayalam" to eraArtists(
            classics5070 = listOf(
                "K.J. Yesudas", "P. Jayachandran", "S. Janaki", "G. Devarajan",
            ),
            golden8090 = listOf(
                "K.S. Chithra", "M.G. Sreekumar", "Johnson Master", "Vidyasagar",
                "Raveendran",
            ),
            era2000s = listOf(
                "Vijay Yesudas", "Deepak Dev", "Alphonse Joseph", "Shreya Ghoshal",
                "Karthik",
            ),
            modern2010Now = listOf(
                "Vineeth Sreenivasan", "Hesham Abdul Wahab", "Sushin Shyam",
                "Shaan Rahman", "Gopi Sundar", "Sithara Krishnakumar",
            ),
        ),
        "Kannada" to eraArtists(
            classics5070 = listOf(
                "P.B. Sreenivas", "Rajkumar", "S. Janaki",
            ),
            golden8090 = listOf(
                "C. Ashwath", "S.P. Balasubrahmanyam", "Hamsalekha", "Rajesh Krishnan",
            ),
            era2000s = listOf(
                "Sonu Nigam", "Mano Murthy", "V. Harikrishna", "Raghu Dixit",
            ),
            modern2010Now = listOf(
                "Vijay Prakash", "Sanjith Hegde", "Arjun Janya", "Ravi Basrur",
                "Charan Raj", "Armaan Malik",
            ),
        ),
        "Bengali" to eraArtists(
            classics5070 = listOf(
                "Hemanta Mukherjee", "Manna Dey", "Kishore Kumar", "Sandhya Mukherjee",
                "Shyamal Mitra",
            ),
            golden8090 = listOf(
                "Asha Bhosle", "Kumar Sanu", "Nachiketa Chakraborty", "Kabir Suman",
                "Lopamudra Mitra",
            ),
            era2000s = listOf(
                "Shaan", "Rupam Islam", "Fossils", "Babul Supriyo",
                "Shreya Ghoshal",
            ),
            modern2010Now = listOf(
                "Arijit Singh", "Anupam Roy", "Monali Thakur", "Iman Chakraborty",
                "Somlata Acharyya",
            ),
        ),
        "Marathi" to eraArtists(
            classics5070 = listOf(
                "Lata Mangeshkar", "Asha Bhosle", "Sudhir Phadke", "Hridaynath Mangeshkar",
            ),
            golden8090 = listOf(
                "Suresh Wadkar", "Anuradha Paudwal", "Arun Date", "Ravindra Sathe",
            ),
            era2000s = listOf(
                "Ajay-Atul", "Avadhoot Gupte", "Vaishali Samant", "Sonu Nigam",
            ),
            modern2010Now = listOf(
                "Shreya Ghoshal", "Bela Shende", "Adarsh Shinde", "Shankar Mahadevan",
                "Arya Ambekar",
            ),
        ),
        "Gujarati" to eraArtists(
            classics5070 = listOf(
                "Avinash Vyas", "Asha Bhosle", "Mahendra Kapoor", "Praful Dave",
            ),
            golden8090 = listOf(
                "Purushottam Upadhyay", "Alka Yagnik", "Falguni Pathak", "Osman Mir",
            ),
            era2000s = listOf(
                "Kirtidan Gadhvi", "Parthiv Gohil", "Bhoomi Trivedi", "Aishwarya Majmudar",
            ),
            modern2010Now = listOf(
                "Aditya Gadhvi", "Jigrra", "Darshan Raval", "Geeta Rabari",
                "Kinjal Dave",
            ),
        ),
        "Bhojpuri" to eraArtists(
            classics5070 = listOf(
                "Sharda Sinha", "Bhikhari Thakur",
            ),
            golden8090 = listOf(
                "Udit Narayan", "Manoj Tiwari", "Bharat Sharma Vyas",
            ),
            era2000s = listOf(
                "Ravi Kishan", "Dinesh Lal Yadav", "Kalpana Patowary",
            ),
            modern2010Now = listOf(
                "Pawan Singh", "Khesari Lal Yadav", "Shilpi Raj", "Arvind Akela Kallu",
                "Ritesh Pandey",
            ),
        ),
        "Haryanvi" to eraArtists(
            classics5070 = listOf(
                "Lakhmi Chand", "Baje Bhagat",
            ),
            golden8090 = listOf(
                "Rajender Singh Kharkiya", "Pale Ram Dahiya",
            ),
            era2000s = listOf(
                "Gajender Phogat", "Raj Mawar",
            ),
            modern2010Now = listOf(
                "Masoom Sharma", "Dhanda Nyoliwala", "Sapna Choudhary",
                "Renuka Panwar", "Gulzaar Chhaniwala",
            ),
        ),
        "Sanskrit" to eraArtists(
            classics5070 = listOf(
                "M.S. Subbulakshmi", "Bhimsen Joshi", "Lata Mangeshkar",
            ),
            golden8090 = listOf(
                "Pandit Jasraj", "Anup Jalota", "Jagjit Singh", "Gulshan Kumar",
            ),
            era2000s = listOf(
                "Shankar Mahadevan", "Uma Mohan", "Hariharan", "Suresh Wadkar",
            ),
            modern2010Now = listOf(
                "Madhavi Madhukar Jha", "Kaushiki Chakraborty", "Anuradha Paudwal",
                "Agam Aggarwal",
            ),
        ),
        "Urdu" to eraArtists(
            classics5070 = listOf(
                "Mehdi Hassan", "Begum Akhter", "Noor Jehan", "Talat Mahmood",
            ),
            golden8090 = listOf(
                "Nusrat Fateh Ali Khan", "Jagjit Singh", "Ghulam Ali",
                "Abida Parveen", "Farida Khanum", "Pankaj Udhas",
            ),
            era2000s = listOf(
                "Rahat Fateh Ali Khan", "Atif Aslam", "Shafqat Amanat Ali",
            ),
            modern2010Now = listOf(
                "Ali Sethi", "Kaifi Khalil", "Arijit Singh", "Asim Azhar",
            ),
        ),
    )

    private val languageByLowerKey: Map<String, String> by lazy {
        artistsByLanguage.keys.associateBy { it.lowercase(Locale.ROOT) }
    }

    /**
     * Returns curated artist entries for [selectedLanguages] filtered by [era].
     * Uses only [selectedLanguages] when non-empty; defaults to Hindi, English, and Punjabi only when empty.
     * For [SurveyArtistEra.ALL], round-robins across eras so top rows showcase all musical eras.
     */
    fun curatedEntriesFor(
        selectedLanguages: List<String>,
        era: SurveyArtistEra = SurveyArtistEra.ALL,
    ): List<SurveyArtistEntry> {
        val activeLangs = (if (selectedLanguages.isNotEmpty()) selectedLanguages else DEFAULT_LANGUAGES)
            .distinctBy { it.trim().lowercase(Locale.ROOT) }
        val perLanguageBuckets = activeLangs.mapNotNull { raw ->
            val key = languageByLowerKey[raw.trim().lowercase(Locale.ROOT)] ?: return@mapNotNull null
            val entries = artistsByLanguage[key].orEmpty()
            if (era == SurveyArtistEra.ALL) {
                interleaveEras(entries)
            } else {
                entries.filter { it.era == era }
            }
        }
        return roundRobinUnique(perLanguageBuckets)
    }

    /**
     * Merges instant local substring matches from [curated] with [remoteResults] when query length >= 2,
     * deduplicating case-insensitively while upgrading missing thumbnails from remote hits.
     */
    fun filterArtistsForQuery(
        curated: List<ArtistRef>,
        remoteResults: List<ArtistRef>,
        rawQuery: String,
    ): List<ArtistRef> {
        val q = rawQuery.trim()
        if (q.length < 2) return curated
        val localMatches = curated.filter { it.name.contains(q, ignoreCase = true) }
        val byKey = LinkedHashMap<String, ArtistRef>()
        for (ref in localMatches + remoteResults) {
            val key = ref.name.trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) continue
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = ref
            } else if (existing.thumb.isNullOrBlank() && !ref.thumb.isNullOrBlank()) {
                byKey[key] = ref.copy(
                    name = existing.name,
                    url = ref.url.ifBlank { existing.url },
                )
            }
        }
        return byKey.values.toList()
    }

    private fun interleaveEras(entries: List<SurveyArtistEntry>): List<SurveyArtistEntry> {
        val buckets = ERA_DISPLAY_ORDER.map { eraKey -> entries.filter { it.era == eraKey } }
        return roundRobinUnique(buckets)
    }

    private fun roundRobinUnique(lists: List<List<SurveyArtistEntry>>): List<SurveyArtistEntry> {
        if (lists.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<SurveyArtistEntry>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (idx in 0 until maxLen) {
            for (bucket in lists) {
                if (idx >= bucket.size) continue
                val entry = bucket[idx]
                val key = entry.name.trim().lowercase(Locale.ROOT)
                if (key.isNotEmpty() && seen.add(key)) {
                    out.add(entry)
                }
            }
        }
        return out
    }
}

private val SURVEY_GENRES = listOf(
    SurveyGenreOption("Bollywood", "Modern & classic Hindi cinema", setOf("Hindi"), Color(0xFFFF6B6B)),
    SurveyGenreOption("Bhakti & Devotional", "Bhajans, aarti, mantra & peace", setOf("Hindi", "Sanskrit", "Bhojpuri", "Gujarati", "Marathi"), Color(0xFFFF9F43)),
    SurveyGenreOption("Punjabi Beats", "High-energy & chill Punjabi", setOf("Punjabi", "Haryanvi"), Color(0xFFF59E0B)),
    SurveyGenreOption("Sufi & Ghazal", "Soulful poetry & acoustic warmth", setOf("Hindi", "Urdu", "Punjabi"), Color(0xFF9775FA)),
    SurveyGenreOption("90s & Retro", "Timeless golden era classics", setOf("Hindi", "Marathi", "Bengali"), Color(0xFFEC4899)),
    SurveyGenreOption("Romantic", "Warm melodies & love anthems", setOf("Hindi", "English", "Punjabi"), Color(0xFFF43F5E)),
    SurveyGenreOption("Sad & Soulful", "Deep vocals & late-night feels", setOf("Hindi", "Punjabi", "English"), Color(0xFF6366F1)),
    SurveyGenreOption("South Indian", "Tamil, Telugu, Malayalam & Kannada", setOf("Tamil", "Telugu", "Malayalam", "Kannada"), Color(0xFF14B8A6)),
    SurveyGenreOption("Indian Indie", "Acoustic storytellers & originals", setOf("Hindi", "English", "Bengali"), Color(0xFF10B981)),
    SurveyGenreOption("Western Pop", "Global chart-toppers & radio hits", setOf("English"), Color(0xFF3B82F6)),
    SurveyGenreOption("Hip-Hop & Rap", "Desi hip-hop & global bars", setOf("English", "Hindi", "Punjabi", "Haryanvi"), Color(0xFFEAB308)),
    SurveyGenreOption("Lo-Fi & Chill", "Soft beats for focus & night", setOf("Hindi", "English"), Color(0xFF06B6D4)),
    SurveyGenreOption("EDM & Party", "Club bangers & high tempo", setOf("English", "Hindi", "Punjabi"), Color(0xFF8B5CF6)),
    SurveyGenreOption("Indian Classical", "Raga, instrumental & maestro", setOf("Hindi", "Sanskrit", "Tamil", "Kannada", "Bengali"), Color(0xFFD97706)),
    SurveyGenreOption("Rock", "Alt-rock, guitars & anthems", setOf("English", "Hindi", "Bengali"), Color(0xFFEF4444)),
)

private val resolvedArtistCache = ConcurrentHashMap<String, ArtistRef>()

private fun filterVerifiedArtistMatches(
    expectedName: String,
    pool: List<ArtistRef>,
): List<ArtistRef> = pool.filter { ref ->
    !ref.thumb.isNullOrBlank() &&
        YouTubeMusicInnertube.artistNamesMatch(expectedName, ref.name)
}

/**
 * First-time (and re-runnable) 3-Step Personalization Survey:
 * 1) Languages -> 2) Artists Picker (min 3 enforced) -> 3) Genre Picker -> Start Listening!
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingSurveyScreen(
    initialData: LibraryData,
    onAnalyzeStart: (languages: List<String>, artists: List<ArtistRef>, genres: List<String>) -> Unit = { _, _, _ -> },
    onComplete: (languages: List<String>, artists: List<ArtistRef>, genres: List<String>) -> Unit,
) {
    val isEditingExisting = initialData.preferredArtists.isNotEmpty() || initialData.preferredGenres.isNotEmpty()
    var step by remember { mutableIntStateOf(0) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzingStatusIdx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val selectedLanguages = remember {
        mutableStateListOf<String>().apply {
            if (isEditingExisting && initialData.musicLanguages.isNotEmpty()) {
                addAll(initialData.musicLanguages)
            }
        }
    }
    val selectedArtists = remember {
        mutableStateListOf<ArtistRef>().apply {
            if (isEditingExisting) {
                val knownByName = initialData.artists.associateBy { it.name.lowercase() }
                initialData.preferredArtists.forEach { name ->
                    add(knownByName[name.lowercase()] ?: ArtistRef(url = "", name = name))
                }
            }
        }
    }
    val selectedGenres = remember {
        mutableStateListOf<String>().apply {
            if (isEditingExisting) {
                addAll(initialData.preferredGenres)
            }
        }
    }

    val avatarMap = remember { mutableStateMapOf<String, ArtistRef>().apply { putAll(resolvedArtistCache) } }
    var artistQuery by remember { mutableStateOf("") }
    var selectedEra by remember { mutableStateOf(SurveyArtistEra.ALL) }
    var artistSearchResults by remember { mutableStateOf<List<ArtistRef>>(emptyList()) }
    var isSearchingArtists by remember { mutableStateOf(false) }

    BackHandler(enabled = step > 0 && !isAnalyzing) {
        step = (step - 1).coerceAtLeast(0)
    }

    val curatedEntries = remember(selectedLanguages.toList(), selectedEra) {
        SurveyArtistCatalog.curatedEntriesFor(selectedLanguages.toList(), selectedEra)
    }
    val curatedArtists = remember(curatedEntries) {
        curatedEntries.map { entry ->
            val key = entry.name.lowercase(Locale.ROOT)
            avatarMap[key] ?: resolvedArtistCache[key] ?: ArtistRef(url = "", name = entry.name, thumb = null)
        }
    }

    // Search remote artists cleanly when query changes
    LaunchedEffect(artistQuery) {
        val q = artistQuery.trim()
        if (q.length < 2) {
            artistSearchResults = emptyList()
            isSearchingArtists = false
            return@LaunchedEffect
        }
        isSearchingArtists = true
        delay(260)
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val ytResults = runCatching {
                    YouTube.Search(q, YouTube.FILTER_ARTISTS).loadNext()
                        .filterIsInstance<BrowseItem.ArtistItem>()
                        .map { it.artist }
                }.getOrDefault(emptyList())
                val innerResults = if (ytResults.size < 4) {
                    YouTubeMusicInnertube.searchArtists(q, limit = 15)
                } else emptyList()
                (ytResults + innerResults)
                    .distinctBy { it.name.trim().lowercase(Locale.ROOT) }
                    .sortedByDescending { YouTubeMusicInnertube.artistMatchScore(q, it.name) }
            }.getOrDefault(emptyList())
        }
        found.forEach { ref ->
            val k = ref.name.lowercase(Locale.ROOT)
            if (!ref.thumb.isNullOrBlank()) {
                resolvedArtistCache[k] = ref
                avatarMap[k] = ref
            }
        }
        artistSearchResults = found
        isSearchingArtists = false
    }

    // Fetch exact artist avatars for Step 2 (only skip InnerTube when NewPipe already has a verified thumb match)
    LaunchedEffect(step, curatedArtists) {
        if (step != 1) return@LaunchedEffect
        val missing = curatedArtists.filter { avatarMap[it.name.lowercase(Locale.ROOT)]?.thumb.isNullOrBlank() }
        if (missing.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            missing.chunked(4).forEach { batch ->
                batch.map { candidate ->
                    async {
                        val picked = runCatching {
                            val npRaw = runCatching {
                                YouTube.Search(candidate.name, YouTube.FILTER_ARTISTS).loadNext()
                                    .filterIsInstance<BrowseItem.ArtistItem>()
                                    .map { it.artist }
                            }.getOrDefault(emptyList())
                            val npMatches = filterVerifiedArtistMatches(candidate.name, npRaw)
                            val verifiedPool = if (npMatches.isNotEmpty()) {
                                npMatches
                            } else {
                                val innerRaw = runCatching {
                                    YouTubeMusicInnertube.searchArtists(candidate.name, limit = 6)
                                }.getOrDefault(emptyList())
                                val innerMatches = filterVerifiedArtistMatches(candidate.name, innerRaw)
                                npMatches + innerMatches
                            }
                            verifiedPool.maxByOrNull { YouTubeMusicInnertube.artistMatchScore(candidate.name, it.name) }
                        }.getOrNull()

                        picked?.let { matched ->
                            val enriched = ArtistRef(
                                url = matched.url.ifBlank { candidate.url },
                                name = candidate.name,
                                thumb = matched.thumb,
                            )
                            val key = candidate.name.lowercase(Locale.ROOT)
                            resolvedArtistCache[key] = enriched
                            withContext(Dispatchers.Main) {
                                avatarMap[key] = enriched
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    val progressFraction by animateFloatAsState(
        targetValue = (step + 1) / 3f,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 320f),
        label = "surveyProgress",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(velvetColors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with logo, step badge & progress indicator
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (step > 0 && !isAnalyzing) {
                        IconButton(
                            onClick = { step-- },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = velvetColors.text,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_velvet_logo),
                            contentDescription = "VELVET",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Tune Your Velvet Mix",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = velvetColors.text,
                        )
                        Text(
                            when (step) {
                                0 -> "Step 1 of 3 · Languages"
                                1 -> "Step 2 of 3 · Favorite Artists"
                                else -> "Step 3 of 3 · Genres & Vibes"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = velvetColors.accentText,
                        )
                    }
                    Surface(
                        color = velvetColors.raised,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, velvetColors.glassBorder),
                    ) {
                        Text(
                            "${step + 1}/3",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = velvetColors.text,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(velvetColors.raised),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(velvetColors.accent, velvetColors.accentText.copy(alpha = 0.78f)),
                                ),
                            ),
                    )
                }
            }

            // Main step body
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally(spring(0.90f, 340f)) { w -> if (forward) w / 3 else -w / 3 } +
                            fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(spring(0.92f, 360f)) { w -> if (forward) -w / 4 else w / 4 } +
                                fadeOut(tween(180)))
                    },
                    label = "surveyStepTransition",
                ) { activeStep ->
                    when (activeStep) {
                        0 -> LanguageStepPage(
                            selected = selectedLanguages,
                            onToggle = { lang ->
                                if (lang in selectedLanguages) selectedLanguages.remove(lang)
                                else selectedLanguages.add(lang)
                            },
                        )
                        1 -> {
                            val visibleArtists = remember(curatedArtists, artistSearchResults, artistQuery) {
                                SurveyArtistCatalog.filterArtistsForQuery(
                                    curated = curatedArtists,
                                    remoteResults = artistSearchResults,
                                    rawQuery = artistQuery,
                                )
                            }
                            ArtistStepPage(
                                query = artistQuery,
                                onQueryChange = { artistQuery = it },
                                selectedEra = selectedEra,
                                onSelectEra = { selectedEra = it },
                                isSearching = isSearchingArtists,
                                artists = visibleArtists,
                                selectedArtists = selectedArtists,
                                avatarLookup = avatarMap,
                                onToggleArtist = { ref ->
                                    val idx = selectedArtists.indexOfFirst { it.name.equals(ref.name, ignoreCase = true) }
                                    if (idx >= 0) {
                                        selectedArtists.removeAt(idx)
                                    } else {
                                        val cached = avatarMap[ref.name.lowercase(Locale.ROOT)]
                                        selectedArtists.add(if (ref.thumb.isNullOrBlank() && cached != null) cached else ref)
                                    }
                                },
                            )
                        }
                        else -> GenreStepPage(
                            selectedLanguages = selectedLanguages,
                            selectedGenres = selectedGenres,
                            onToggleGenre = { genre ->
                                if (genre in selectedGenres) selectedGenres.remove(genre)
                                else selectedGenres.add(genre)
                            },
                        )
                    }
                }
            }

            // Sticky bottom action bar
            Surface(
                color = velvetColors.background,
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val canAdvance = when (step) {
                        0 -> selectedLanguages.isNotEmpty()
                        1 -> selectedArtists.size >= 3
                        else -> selectedGenres.isNotEmpty()
                    }
                    val buttonLabel = when (step) {
                        0 -> if (selectedLanguages.isEmpty()) "Select at least 1 language" else "Continue"
                        1 -> if (selectedArtists.size < 3) "Pick ${3 - selectedArtists.size} more artist${if (3 - selectedArtists.size == 1) "" else "s"} (${selectedArtists.size}/3)" else "Continue with ${selectedArtists.size} artists"
                        else -> if (selectedGenres.isEmpty()) "Choose at least 1 genre" else "Start Listening"
                    }

                    Button(
                        enabled = canAdvance && !isAnalyzing,
                        onClick = {
                            if (step < 2) {
                                step++
                            } else {
                                isAnalyzing = true
                                scope.launch {
                                    val resolvedArtists = selectedArtists.map { a ->
                                        avatarMap[a.name.lowercase()]?.let { enriched ->
                                            if (a.url.isBlank()) enriched else a
                                        } ?: a
                                    }
                                    onAnalyzeStart(
                                        selectedLanguages.toList(),
                                        resolvedArtists,
                                        selectedGenres.toList(),
                                    )
                                    analyzingStatusIdx = 0
                                    delay(360)
                                    analyzingStatusIdx = 1
                                    delay(360)
                                    analyzingStatusIdx = 2
                                    delay(260)
                                    onComplete(
                                        selectedLanguages.toList(),
                                        resolvedArtists,
                                        selectedGenres.toList(),
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = velvetColors.accent,
                            contentColor = velvetColors.onAccent,
                            disabledContainerColor = velvetColors.raised,
                            disabledContentColor = velvetColors.muted,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Text(
                            buttonLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Smooth Analyzing / Personalizing modal state after clicking Start Listening
        AnimatedVisibility(
            visible = isAnalyzing,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = spring(0.88f, 340f)),
            exit = fadeOut(tween(200)),
        ) {
            val statusLines = listOf(
                "Tuning live YouTube Music charts for ${selectedLanguages.take(2).joinToString(" & ")}...",
                "Mapping ${selectedArtists.size} favorite artists & ${selectedGenres.size} genres...",
                "Building your Speed Dial & personalized mixes...",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(velvetColors.background.copy(alpha = 0.96f)),
                contentAlignment = Alignment.Center,
            ) {
                GlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    color = velvetColors.surface,
                    borderColor = velvetColors.accent.copy(alpha = 0.4f),
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(velvetColors.accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = velvetColors.accent,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp),
                            )
                            Icon(
                                AppIcons.Sparkles,
                                contentDescription = null,
                                tint = velvetColors.accentText,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Personalizing your VELVET home...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = velvetColors.text,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            statusLines[analyzingStatusIdx.coerceIn(0, statusLines.lastIndex)],
                            style = MaterialTheme.typography.bodyMedium,
                            color = velvetColors.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageStepPage(
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    "What languages do you enjoy listening to?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = velvetColors.text,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pick one or more languages to customize live charts, Trending Now, and daily mixes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = velvetColors.muted,
                )
            }
        }

        items(SURVEY_LANGUAGES, key = { it.id }) { option ->
            val isSelected = option.id in selected
            val container by animateColorAsState(
                targetValue = if (isSelected) option.accentHint.copy(alpha = 0.16f) else velvetColors.surface,
                animationSpec = tween(180),
                label = "langBg",
            )
            val border by animateColorAsState(
                targetValue = if (isSelected) option.accentHint else velvetColors.glassBorder,
                animationSpec = tween(180),
                label = "langBorder",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(container)
                    .border(1.5.dp, border, RoundedCornerShape(16.dp))
                    .clickable { onToggle(option.id) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(option.accentHint),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.id,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = velvetColors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            option.nativeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = velvetColors.muted,
                            maxLines = 1,
                        )
                    }
                    AnimatedVisibility(
                        visible = isSelected,
                        enter = scaleIn(spring(0.75f, 450f)) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(option.accentHint),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistStepPage(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedEra: SurveyArtistEra,
    onSelectEra: (SurveyArtistEra) -> Unit,
    isSearching: Boolean,
    artists: List<ArtistRef>,
    selectedArtists: List<ArtistRef>,
    avatarLookup: Map<String, ArtistRef>,
    onToggleArtist: (ArtistRef) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Choose 3 or more artists",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = velvetColors.text,
                    )
                    Text(
                        "Search or tap artists across eras to tune your recommendations & radio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.muted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val metMinimum = selectedArtists.size >= 3
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (metMinimum) velvetColors.accent.copy(alpha = 0.20f) else velvetColors.raised,
                    border = BorderStroke(1.dp, if (metMinimum) velvetColors.accent else velvetColors.glassBorder),
                ) {
                    Text(
                        "${selectedArtists.size}/3 min",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (metMinimum) velvetColors.accentText else velvetColors.text,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            var searchFocused by remember { mutableStateOf(false) }
            val searchBorder by animateColorAsState(
                targetValue = if (searchFocused) velvetColors.accent else velvetColors.glassBorder,
                animationSpec = tween(160),
                label = "artistSearchBorder",
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(velvetColors.surface)
                        .border(1.dp, searchBorder, RoundedCornerShape(19.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = velvetColors.muted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                "Search singer, composer...",
                                style = MaterialTheme.typography.bodySmall,
                                color = velvetColors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = velvetColors.text,
                                fontSize = 13.sp,
                            ),
                            cursorBrush = SolidColor(velvetColors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { searchFocused = it.isFocused },
                        )
                    }
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 1.8.dp,
                            color = velvetColors.accent,
                        )
                    } else if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onQueryChange("") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = velvetColors.muted,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SurveyArtistEra.entries, key = { it.name }) { era ->
                val isEraSelected = era == selectedEra
                val pillBg by animateColorAsState(
                    targetValue = if (isEraSelected) velvetColors.accent.copy(alpha = 0.20f) else velvetColors.surface,
                    animationSpec = tween(160),
                    label = "eraPillBg",
                )
                val pillBorder by animateColorAsState(
                    targetValue = if (isEraSelected) velvetColors.accent else velvetColors.glassBorder,
                    animationSpec = tween(160),
                    label = "eraPillBorder",
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = pillBg,
                    border = BorderStroke(1.dp, pillBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelectEra(era) },
                ) {
                    Text(
                        text = if (era == SurveyArtistEra.ALL) era.label else era.shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isEraSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isEraSelected) velvetColors.accentText else velvetColors.muted,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (selectedArtists.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedArtists.forEach { picked ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = velvetColors.accent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, velvetColors.accent.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onToggleArtist(picked) },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                picked.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = velvetColors.text,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = velvetColors.accentText,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val context = LocalContext.current
        if (artists.isEmpty() && query.trim().length >= 2) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isSearching) "Searching artists..." else "No matching artists found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = velvetColors.muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(artists, key = { it.name.trim().lowercase(Locale.ROOT) }) { rawArtist ->
                    val artist = avatarLookup[rawArtist.name.lowercase(Locale.ROOT)]?.let { cached ->
                        if (rawArtist.thumb.isNullOrBlank()) cached else rawArtist
                    } ?: rawArtist
                    val isSelected = selectedArtists.any { it.name.equals(artist.name, ignoreCase = true) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onToggleArtist(artist) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            val avatarSize = 86.dp
                            val px = with(LocalDensity.current) { avatarSize.roundToPx() }
                            Box(
                                modifier = Modifier
                                    .size(avatarSize)
                                    .clip(CircleShape)
                                    .background(velvetColors.raised)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) velvetColors.accent else velvetColors.glassBorder,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    artist.name.take(2).uppercase(Locale.ROOT),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = velvetColors.muted,
                                )
                                val thumbUrl = artist.thumb
                                if (!thumbUrl.isNullOrBlank()) {
                                    key("${artist.name}|$thumbUrl") {
                                        AsyncImage(
                                            model = remember(thumbUrl, px) {
                                                ImageRequest.Builder(context)
                                                    .data(thumbSized(thumbUrl, px))
                                                    .crossfade(true)
                                                    .build()
                                            },
                                            contentDescription = artist.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }

                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(velvetColors.accent)
                                        .border(2.dp, velvetColors.background, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(7.dp))
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) velvetColors.accentText else velvetColors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreStepPage(
    selectedLanguages: List<String>,
    selectedGenres: List<String>,
    onToggleGenre: (String) -> Unit,
) {
    val activeLangs = remember(selectedLanguages.toList()) { selectedLanguages.toSet() }
    val sortedGenres = remember(activeLangs) {
        SURVEY_GENRES.sortedByDescending { option ->
            if (option.languagesHint.any { it in activeLangs }) 1 else 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            "What genres & vibes match your mood?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = velvetColors.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Select the genres you love. VELVET uses this to rank Trending charts, build Speed Dial, and spin Queue Autoplay.",
            style = MaterialTheme.typography.bodyMedium,
            color = velvetColors.muted,
        )
        Spacer(Modifier.height(16.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sortedGenres.forEach { genre ->
                val isSelected = genre.key in selectedGenres
                val bg by animateColorAsState(
                    targetValue = if (isSelected) genre.swatch.copy(alpha = 0.22f) else velvetColors.surface,
                    animationSpec = tween(180),
                    label = "genreBg",
                )
                val border by animateColorAsState(
                    targetValue = if (isSelected) genre.swatch else velvetColors.glassBorder,
                    animationSpec = tween(180),
                    label = "genreBorder",
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .border(1.5.dp, border, RoundedCornerShape(14.dp))
                        .clickable { onToggleGenre(genre.key) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(genre.swatch),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(
                            genre.key,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = velvetColors.text,
                        )
                        Text(
                            genre.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = velvetColors.muted,
                        )
                    }
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = genre.swatch,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
