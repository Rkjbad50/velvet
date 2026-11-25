package com.velvet.app.recommendation.features

import com.velvet.app.recommendation.model.MusicType
import java.util.Locale

/**
 * Controlled genre, subgenre, alias, and music-type compatibility vocabulary.
 * Prevents free-form keyword sprawl and preserves semantic distances between musical styles.
 */
object GenreOntology {

    const val DEVOTIONAL = "devotional"
    const val SUFI_GHAZAL = "sufi_ghazal"
    const val BOLLYWOOD = "bollywood"
    const val PUNJABI = "punjabi"
    const val SOUTH_INDIAN = "south_indian"
    const val INDIAN_CLASSICAL = "indian_classical"
    const val INDIAN_INDIE = "indian_indie"
    const val WESTERN_POP = "western_pop"
    const val ROCK = "rock"
    const val HIP_HOP = "hip_hop"
    const val EDM = "edm"
    const val LOFI_CHILL = "lofi_chill"

    private val subgenreToParent = mapOf(
        "bhajan" to DEVOTIONAL,
        "aarti" to DEVOTIONAL,
        "chalisa" to DEVOTIONAL,
        "mantra" to DEVOTIONAL,
        "kirtan" to DEVOTIONAL,
        "sufi" to SUFI_GHAZAL,
        "ghazal" to SUFI_GHAZAL,
        "qawwali" to SUFI_GHAZAL,
        "bollywood_romantic" to BOLLYWOOD,
        "bollywood_retro" to BOLLYWOOD,
        "bollywood_90s" to BOLLYWOOD,
        "bollywood_2000s" to BOLLYWOOD,
        "bollywood_party" to BOLLYWOOD,
        "bollywood_sad" to BOLLYWOOD,
        "punjabi_pop" to PUNJABI,
        "punjabi_bhangra" to PUNJABI,
        "punjabi_hip_hop" to PUNJABI,
        "tamil_film" to SOUTH_INDIAN,
        "telugu_film" to SOUTH_INDIAN,
        "malayalam_film" to SOUTH_INDIAN,
        "kannada_film" to SOUTH_INDIAN,
        "carnatic" to INDIAN_CLASSICAL,
        "hindustani_classical" to INDIAN_CLASSICAL,
        "raga_instrumental" to INDIAN_CLASSICAL,
        "indie_pop" to INDIAN_INDIE,
        "indie_acoustic" to INDIAN_INDIE,
        "synth_pop" to WESTERN_POP,
        "dance_pop" to WESTERN_POP,
        "alt_rock" to ROCK,
        "classic_rock" to ROCK,
        "desi_hip_hop" to HIP_HOP,
        "lofi_beats" to LOFI_CHILL,
    )

    private val aliases = mapOf(
        "bhakti" to DEVOTIONAL,
        "bhakti & devotional" to DEVOTIONAL,
        "spiritual" to DEVOTIONAL,
        "hindi devotional" to DEVOTIONAL,
        "arti" to "aarti",
        "keertan" to "kirtan",
        "qawali" to "qawwali",
        "sufi & ghazal" to SUFI_GHAZAL,
        "hindi film" to BOLLYWOOD,
        "hindi melody" to "bollywood_romantic",
        "romantic" to "bollywood_romantic",
        "sad & soulful" to "bollywood_sad",
        "old is gold" to "bollywood_retro",
        "retro bollywood" to "bollywood_retro",
        "90s & retro" to "bollywood_retro",
        "bhangra" to "punjabi_bhangra",
        "punjabi beats" to PUNJABI,
        "kollywood" to "tamil_film",
        "tollywood" to "telugu_film",
        "mollywood" to "malayalam_film",
        "sandalwood" to "kannada_film",
        "south film" to SOUTH_INDIAN,
        "classical" to INDIAN_CLASSICAL,
        "lo-fi" to LOFI_CHILL,
        "lofi" to LOFI_CHILL,
        "lo-fi & chill" to LOFI_CHILL,
        "lofi & chill" to LOFI_CHILL,
        "electronic" to EDM,
        "edm & party" to EDM,
        "rap" to HIP_HOP,
        "hip-hop & rap" to HIP_HOP,
        "hip hop & rap" to HIP_HOP,
        "indian_indie" to INDIAN_INDIE,
        "western pop" to WESTERN_POP,
        "indian classical" to INDIAN_CLASSICAL,
        "south indian" to SOUTH_INDIAN,
    )

    fun canonicalize(raw: String): String? {
        val norm = raw.trim().lowercase(Locale.ROOT)
        if (norm.isBlank()) return null
        aliases[norm]?.let { return it }
        if (norm in subgenreToParent || norm in subgenreToParent.values) return norm
        return null
    }

    fun parentOf(subgenreOrGenre: String): String =
        subgenreToParent[subgenreOrGenre] ?: subgenreOrGenre

    /**
     * Semantic genre distance in [0.0, 1.0].
     */
    fun similarity(aRaw: String, bRaw: String): Double {
        val a = canonicalize(aRaw) ?: return 0.0
        val b = canonicalize(bRaw) ?: return 0.0
        if (a == b) return 1.0

        val parentA = parentOf(a)
        val parentB = parentOf(b)
        if (parentA == parentB) return 0.80

        val pair = setOf(parentA, parentB)
        return when {
            pair == setOf(DEVOTIONAL, INDIAN_CLASSICAL) -> 0.45
            pair == setOf(SUFI_GHAZAL, INDIAN_CLASSICAL) -> 0.55
            pair == setOf(SUFI_GHAZAL, BOLLYWOOD) -> 0.40
            pair == setOf(BOLLYWOOD, INDIAN_INDIE) -> 0.55
            pair == setOf(BOLLYWOOD, PUNJABI) -> 0.38
            pair == setOf(PUNJABI, HIP_HOP) -> 0.50
            pair == setOf(WESTERN_POP, EDM) -> 0.45
            pair == setOf(DEVOTIONAL, BOLLYWOOD) || pair == setOf(DEVOTIONAL, PUNJABI) -> 0.0
            pair == setOf(DEVOTIONAL, HIP_HOP) || pair == setOf(DEVOTIONAL, EDM) -> 0.0
            else -> 0.15
        }
    }

    /**
     * Compatibility matrix across distinct semantic music forms (e.g. Chalisa vs Party Film Song).
     */
    fun musicTypeCompatibility(a: MusicType, b: MusicType): Double {
        if (a == MusicType.UNKNOWN || b == MusicType.UNKNOWN) return 0.5
        if (a == b) return 1.0

        val devotionalForms = setOf(MusicType.CHALISA, MusicType.AARTI, MusicType.BHAJAN, MusicType.MANTRA, MusicType.DEVOTIONAL)
        if (a in devotionalForms && b in devotionalForms) {
            return when {
                a == MusicType.CHALISA && b == MusicType.MANTRA -> 0.72
                else -> 0.85
            }
        }
        if ((a in devotionalForms && b !in devotionalForms) || (b in devotionalForms && a !in devotionalForms)) {
            return if (a == MusicType.CLASSICAL || b == MusicType.CLASSICAL) 0.40 else 0.0
        }

        val sufiForms = setOf(MusicType.SUFI, MusicType.GHAZAL, MusicType.QAWALI)
        if (a in sufiForms && b in sufiForms) return 0.82

        val secularPopular = setOf(MusicType.SONG, MusicType.FILM_SONG, MusicType.SOUNDTRACK, MusicType.INDEPENDENT)
        if (a in secularPopular && b in secularPopular) return 0.80

        if (a == MusicType.PODCAST_OR_SPOKEN || b == MusicType.PODCAST_OR_SPOKEN) return 0.0
        return 0.25
    }
}
