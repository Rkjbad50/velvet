package com.velvet.app.recommendation.vibe

import com.velvet.app.data.LibraryData
import com.velvet.app.data.ListenEndReason
import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.MetadataFeatureExtractor
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Learned per-artist linguistic, genre, and era signature persisted on device (C3).
 */
@Serializable
data class ArtistLanguageProfile(
    val languageWeights: Map<String, Double> = emptyMap(),
    val genreWeights: Map<String, Double> = emptyMap(),
    val eraBuckets: Map<String, Double> = emptyMap(),
    val sampleCount: Int = 0,
    val updatedAtMillis: Long = 0L,
) {
    val dominantLanguage: String?
        get() = languageWeights.entries.maxByOrNull { it.value }?.key

    val dominantShare: Double
        get() {
            val total = languageWeights.values.sum()
            val top = languageWeights.values.maxOrNull() ?: 0.0
            return if (total > 0.0) top / total else 0.0
        }

    val dominantGenre: String?
        get() = genreWeights.entries.maxByOrNull { it.value }?.key

    val dominantEra: String?
        get() = eraBuckets.entries.maxByOrNull { it.value }?.key
}

/**
 * Computes, merges, and resolves on-device artist -> language/genre/era priors
 * learned from liked songs, user playlists, and completed listens.
 */
object ArtistLanguageMap {
    private const val MAX_PERSISTED_ARTISTS = 500

    private val playlistLanguageTokens = mapOf(
        "hindi" to "hi",
        "bollywood" to "hi",
        "english" to "en",
        "punjabi" to "pa",
        "tamil" to "ta",
        "kollywood" to "ta",
        "telugu" to "te",
        "tollywood" to "te",
        "malayalam" to "ml",
        "mollywood" to "ml",
        "kannada" to "kn",
        "sandalwood" to "kn",
        "bengali" to "bn",
        "bangla" to "bn",
        "sanskrit" to "sa",
    )

    private val curatedArtistLanguages: Map<String, String> = buildMap {
        val hindi = listOf(
            "lata mangeshkar", "kishore kumar", "mohammed rafi", "asha bhosle", "mukesh",
            "r d burman", "s d burman", "kumar sanu", "udit narayan", "alka yagnik",
            "abhijeet", "sonu nigam", "kavita krishnamurthy", "nadeem shravan", "shaan",
            "kk", "mohit chauhan", "sunidhi chauhan", "pritam", "himesh reshammiya",
            "arijit singh", "shreya ghoshal", "atif aslam", "jubin nautiyal", "darshan raval",
            "armaan malik", "mithoon", "vishal mishra", "jagjit singh", "pankaj udhas",
            "rahat fateh ali khan", "nusrat fateh ali khan", "gulshan kumar",
            "anuradha paudwal", "anup jalota", "lakhbir singh lakkha", "palak muchhal",
            "suresh wadkar", "shankar mahadevan", "roop kumar rathod", "sadhanasargam",
            "sadhana sargam", "babul supriyo", "vinyl", "sukhwinder singh",
        )
        val english = listOf(
            "taylor swift", "the weeknd", "dua lipa", "ed sheeran", "billie eilish",
            "coldplay", "lana del rey", "drake", "justin bieber", "ariana grande",
            "bruno mars", "eminem", "shawn mendes", "charlie puth", "maroon 5",
            "imagine dragons", "linkin park", "adele", "post malone", "olivia rodrigo",
            "katy perry", "rihanna", "sabrina carpenter", "harry styles", "calvin harris",
            "kendrick lamar", "selena gomez", "camila cabello", "john legend", "sam smith",
            "bebe rexha", "one direction", "backstreet boys", "queen", "beatles",
        )
        val punjabi = listOf(
            "sidhu moosewala", "sidhu moose wala", "karan aujla", "diljit dosanjh",
            "ap dhillon", "shubh", "b praak", "harrdy sandhu", "guru randhawa",
            "amrinder gill", "satinder sartaj", "gippy grewal", "jass manak", "parmish verma",
            "babbu maan", "garry sandhu",
        )
        val tamil = listOf(
            "anirudh ravichander", "anirudh", "ilayaraja", "ilaiyaraaja",
            "yuvan shankar raja", "harris jayaraj", "santhosh narayanan", "g v prakash",
            "s p balasubrahmanyam", "dhanush", "vijay antony", "hiphop tamizha",
            "karthik", "ks chithra",
        )
        val telugu = listOf(
            "devi sri prasad", "thaman s", "s thaman", "mani sharma", "m m keeravani",
            "mickey j meyer", "anup rubens",
        )
        val malayalam = listOf("shaan rahman", "sushin shyam", "heshem abdul wahab", "heshan abdul wahab", "vineeth sreenivasan")
        val kannada = listOf("vijay prakash", "ravi basrur", "charan raj", "arjun janya")

        hindi.forEach { put(it, "hi") }
        english.forEach { put(it, "en") }
        punjabi.forEach { put(it, "pa") }
        tamil.forEach { put(it, "ta") }
        telugu.forEach { put(it, "te") }
        malayalam.forEach { put(it, "ml") }
        kannada.forEach { put(it, "kn") }
    }

    fun allArtistKeys(artistString: String): List<String> {
        val rawParts = SongIdentity.normalizeArtists(artistString)
        val primary = normalizePrimaryArtist(artistString)
        return (listOf(primary) + rawParts)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun curatedLanguageForArtist(artistString: String): Pair<String, Double>? {
        val keys = allArtistKeys(artistString)
        for (k in keys) {
            curatedArtistLanguages[k]?.let { return it to 0.82 }
            curatedArtistLanguages.entries.firstOrNull { (known, _) ->
                known.length >= 5 && (k.contains(known) || known.contains(k))
            }?.let { return it.value to 0.78 }
        }
        return null
    }

    /**
     * Resolves an artist's primary language (Signal 1):
     * 1. Learned on-device map (`deviceMap`, then process registry).
     * 2. Curated artist-language prior.
     */
    fun inferArtistLanguage(
        artistString: String,
        deviceMap: Map<String, ArtistLanguageProfile> = emptyMap(),
    ): Pair<String, Double>? {
        if (artistString.isBlank()) return null
        val keys = allArtistKeys(artistString)
        val mergedRegistry = if (deviceMap.isEmpty()) ArtistLanguageRegistry.snapshot() else deviceMap

        for (key in keys) {
            val profile = mergedRegistry[key] ?: deviceMap[key]
            if (profile != null && profile.sampleCount >= 1) {
                val domLang = profile.dominantLanguage
                val domWeight = domLang?.let { profile.languageWeights[it] } ?: 0.0
                val share = profile.dominantShare
                val reliable = domLang != null &&
                    ((profile.sampleCount >= 2 && share >= 0.65) || (share >= 0.80 && domWeight >= 0.70))
                if (reliable) {
                    val conf = (0.76 + 0.19 * share).coerceIn(0.76, 0.95)
                    return domLang!! to conf
                }
            }
        }
        return curatedLanguageForArtist(artistString)
    }

    fun mergeProfiles(a: ArtistLanguageProfile, b: ArtistLanguageProfile): ArtistLanguageProfile {
        val langs = a.languageWeights.toMutableMap()
        b.languageWeights.forEach { (k, v) -> langs[k] = (langs[k] ?: 0.0) + v }
        val genres = a.genreWeights.toMutableMap()
        b.genreWeights.forEach { (k, v) -> genres[k] = (genres[k] ?: 0.0) + v }
        val eras = a.eraBuckets.toMutableMap()
        b.eraBuckets.forEach { (k, v) -> eras[k] = (eras[k] ?: 0.0) + v }
        return ArtistLanguageProfile(
            languageWeights = langs,
            genreWeights = genres,
            eraBuckets = eras,
            sampleCount = a.sampleCount + b.sampleCount,
            updatedAtMillis = maxOf(a.updatedAtMillis, b.updatedAtMillis),
        )
    }

    fun mergeMaps(
        base: Map<String, ArtistLanguageProfile>,
        incoming: Map<String, ArtistLanguageProfile>,
    ): Map<String, ArtistLanguageProfile> {
        if (incoming.isEmpty()) return base
        val combined = LinkedHashMap(base)
        incoming.forEach { (artist, prof) ->
            combined[artist] = combined[artist]?.let { mergeProfiles(it, prof) } ?: prof
        }
        if (combined.size <= MAX_PERSISTED_ARTISTS) return combined
        return combined.entries
            .sortedWith(compareByDescending<Map.Entry<String, ArtistLanguageProfile>> { it.value.sampleCount }.thenByDescending { it.value.updatedAtMillis })
            .take(MAX_PERSISTED_ARTISTS)
            .associate { it.key to it.value }
    }

    /**
     * Rebuilds the on-device [ArtistLanguageProfile] map from Liked songs, Playlists,
     * and Completed/Replayed listen events. Never guesses from unplayed or early-skipped items.
     */
    fun learnFromLibrary(
        data: LibraryData,
        extractor: MetadataFeatureExtractor = MetadataFeatureExtractor(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, ArtistLanguageProfile> {
        val acc = LinkedHashMap<String, ArtistLanguageProfile>()

        fun recordSong(song: Song, weight: Double, explicitPlaylistLang: String? = null) {
            val keys = allArtistKeys(song.artist)
            if (keys.isEmpty()) return
            val feat = extractor.extract(song)
            val langMap = LinkedHashMap<String, Double>()
            if (!explicitPlaylistLang.isNullOrBlank()) {
                langMap[explicitPlaylistLang] = weight
            } else if (feat.languageIds.isNotEmpty() && feat.languageConfidence >= 0.55) {
                feat.languageIds.forEach { (lang, conf) ->
                    langMap[lang] = conf * weight
                }
            }
            val genreMap = feat.genreIds.mapValues { it.value * weight }
            val eraMap = feat.eraBucket?.let { mapOf(it to weight) }.orEmpty()
            if (langMap.isEmpty() && genreMap.isEmpty() && eraMap.isEmpty()) return

            val obs = ArtistLanguageProfile(
                languageWeights = langMap,
                genreWeights = genreMap,
                eraBuckets = eraMap,
                sampleCount = 1,
                updatedAtMillis = nowMillis,
            )
            keys.forEach { key ->
                acc[key] = acc[key]?.let { mergeProfiles(it, obs) } ?: obs
            }
        }

        data.liked.forEach { song ->
            recordSong(song, weight = 1.5)
        }
        data.playlists.forEach { pl ->
            val plNameLow = pl.name.lowercase(Locale.ROOT)
            val plLang = playlistLanguageTokens.entries.firstOrNull { (tok, _) -> tok in plNameLow }?.value
            pl.resolvedSongs.forEach { song ->
                recordSong(song, weight = 1.2, explicitPlaylistLang = plLang)
            }
        }
        val knownSongs = (data.liked + data.recent + data.playlists.flatMap { it.resolvedSongs })
            .associateBy { it.id }
        data.listenEvents.forEach { ev ->
            if (ev.endReason != ListenEndReason.COMPLETED && ev.endReason != ListenEndReason.REPLAY) return@forEach
            val song = knownSongs[ev.videoId]
            if (song != null) {
                recordSong(song, weight = 1.0)
            } else if (!ev.language.isNullOrBlank() && ev.artistSet.isNotEmpty()) {
                val obs = ArtistLanguageProfile(
                    languageWeights = mapOf(ev.language.lowercase(Locale.ROOT) to 1.0),
                    sampleCount = 1,
                    updatedAtMillis = ev.startedAt,
                )
                ev.artistSet.forEach { rawArtist ->
                    val k = normalizePrimaryArtist(rawArtist).ifBlank { rawArtist.trim().lowercase(Locale.ROOT) }
                    if (k.isNotBlank()) acc[k] = acc[k]?.let { mergeProfiles(it, obs) } ?: obs
                }
            }
        }

        val merged = mergeMaps(data.artistLanguageMap, emptyMap())
        if (acc.isEmpty()) return merged
        val out = LinkedHashMap<String, ArtistLanguageProfile>()
        data.artistLanguageMap.forEach { (k, v) -> if (k !in acc) out[k] = v }
        acc.forEach { (k, fresh) ->
            val existing = data.artistLanguageMap[k]
            out[k] = if (existing == null || fresh.sampleCount >= existing.sampleCount) {
                fresh
            } else {
                existing
            }
        }
        return mergeMaps(emptyMap(), out)
    }
}

/**
 * Lightweight thread-safe live view of the on-device [ArtistLanguageProfile] map.
 */
object ArtistLanguageRegistry {
    private val map = ConcurrentHashMap<String, ArtistLanguageProfile>()

    fun setDeviceMap(profiles: Map<String, ArtistLanguageProfile>) {
        if (profiles.isEmpty() && map.isEmpty()) return
        val newKeys = profiles.keys
        map.keys.filter { it !in newKeys }.forEach { map.remove(it) }
        profiles.forEach { (k, v) -> map[k] = v }
    }

    fun snapshot(): Map<String, ArtistLanguageProfile> = map.toMap()

    fun clearForTest() = map.clear()
}
