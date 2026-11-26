package com.velvet.app.recommendation.features

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.AudioFeatureVector
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.TempoBucket
import com.velvet.app.recommendation.model.TrackFeatureRecord
import java.util.Locale

/**
 * Deterministic Tier-1 metadata feature extractor producing confidence-weighted,
 * provenance-tracked [TrackFeatureRecord] objects without fabricating unknown metadata.
 */
class MetadataFeatureExtractor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val artistLanguageLookup: ((String) -> Pair<String, Double>?)? = {
        com.velvet.app.recommendation.vibe.ArtistLanguageMap.inferArtistLanguage(it)
    },
) {
    fun extract(
        song: Song,
        enrichment: EnrichmentMetadata? = null,
    ): TrackFeatureRecord {
        val identity = RecordingIdentityResolver.resolve(
            song = song,
            musicBrainzRecordingId = enrichment?.musicBrainzRecordingId,
            isrc = enrichment?.isrc,
            fetchedAtMillis = clock(),
        )
        val parsed = SongIdentity.normalizeTitle(song.title)
        val titleLow = song.title.lowercase(Locale.ROOT)
        val artistNorm = normalizePrimaryArtist(song.artist)
        val albumNorm = (parsed.movie ?: song.album)?.lowercase(Locale.ROOT).orEmpty()
        val titleAlbumText = "$titleLow ${parsed.cleanTitle} $albumNorm"
        val combinedText = "$titleAlbumText $artistNorm"

        val hasExplicitTitleDevotional = containsToken(
            titleAlbumText,
            listOf("chalisa", "aarti", "arti", "mantra", "stotra", "stotram", "ashtakam", "suktam", "kavach", "jaap", "bhajan", "kirtan", "keertan", "amritwani", "vandana", "bhakti", "devotional") + DEVOTIONAL_DEITY_TERMS,
        )
        val hasExplicitSecularTitleTone = !hasExplicitTitleDevotional && containsToken(titleAlbumText, PARTY_MOOD_TERMS)

        val provenance = mutableMapOf<String, String>()
        provenance["release_audio"] = identity.releaseClassification.reasonCode

        // 1. MusicType (semantic form, confidence-weighted)
        val musicTypes = linkedMapOf<MusicType, Double>()
        when {
            containsToken(combinedText, listOf("podcast", "episode", "interview", "audiobook", "speech", "dialogue")) -> {
                musicTypes[MusicType.PODCAST_OR_SPOKEN] = 0.90
                provenance["music_type"] = "spoken_keyword"
            }
            containsToken(titleAlbumText, listOf("chalisa")) -> {
                musicTypes[MusicType.CHALISA] = 0.92
                musicTypes[MusicType.DEVOTIONAL] = 0.90
                provenance["music_type"] = "title_form_chalisa"
            }
            containsToken(titleAlbumText, listOf("aarti", "arti")) -> {
                musicTypes[MusicType.AARTI] = 0.92
                musicTypes[MusicType.DEVOTIONAL] = 0.90
                provenance["music_type"] = "title_form_aarti"
            }
            containsToken(titleAlbumText, listOf("mantra", "stotra", "stotram", "ashtakam", "suktam", "kavach", "jaap")) -> {
                musicTypes[MusicType.MANTRA] = 0.90
                musicTypes[MusicType.DEVOTIONAL] = 0.88
                provenance["music_type"] = "title_form_mantra"
            }
            !hasExplicitSecularTitleTone && containsToken(combinedText, listOf("bhajan", "kirtan", "keertan", "amritwani", "vandana", "bhakti", "devotional")) -> {
                musicTypes[MusicType.BHAJAN] = 0.85
                musicTypes[MusicType.DEVOTIONAL] = 0.88
                provenance["music_type"] = "devotional_term"
            }
            !hasExplicitSecularTitleTone && (DEVOTIONAL_ARTISTS.any { artistNorm.contains(it) } || containsToken(combinedText, DEVOTIONAL_DEITY_TERMS)) -> {
                musicTypes[MusicType.DEVOTIONAL] = 0.72
                musicTypes[MusicType.BHAJAN] = 0.65
                provenance["music_type"] = "devotional_artist_or_deity"
            }
            containsToken(combinedText, listOf("qawwali", "qawali")) -> {
                musicTypes[MusicType.QAWALI] = 0.90
                musicTypes[MusicType.SUFI] = 0.75
                provenance["music_type"] = "qawwali_term"
            }
            containsToken(combinedText, listOf("ghazal", "nazm")) || GHAZAL_ARTISTS.any { artistNorm.contains(it) } -> {
                musicTypes[MusicType.GHAZAL] = 0.88
                provenance["music_type"] = "ghazal_term_or_artist"
            }
            containsToken(combinedText, listOf("sufi")) || SUFI_ARTISTS.any { artistNorm.contains(it) } -> {
                musicTypes[MusicType.SUFI] = 0.86
                provenance["music_type"] = "sufi_term_or_artist"
            }
            containsToken(combinedText, listOf("classical", "raga", "raag", "carnatic", "hindustani")) -> {
                musicTypes[MusicType.CLASSICAL] = 0.84
                provenance["music_type"] = "classical_term"
            }
            containsToken(titleLow, listOf("instrumental")) -> {
                musicTypes[MusicType.INSTRUMENTAL] = 0.88
                provenance["music_type"] = "instrumental_title"
            }
            !parsed.movie.isNullOrBlank() || containsToken(combinedText, listOf("original soundtrack", "soundtrack", "ost")) -> {
                musicTypes[MusicType.FILM_SONG] = 0.80
                musicTypes[MusicType.SOUNDTRACK] = 0.75
                provenance["music_type"] = "film_or_ost_hint"
            }
            artistNorm.isNotBlank() && parsed.cleanTitle.isNotBlank() -> {
                musicTypes[MusicType.SONG] = 0.60
                provenance["music_type"] = "default_song_fallback"
            }
            else -> {
                musicTypes[MusicType.UNKNOWN] = 0.0
                provenance["music_type"] = "unknown"
            }
        }

        // 2. Genres & subgenres (multi-label, controlled ontology)
        val genreIds = linkedMapOf<String, Double>()
        val subgenreIds = linkedMapOf<String, Double>()

        if (MusicType.DEVOTIONAL in musicTypes || MusicType.CHALISA in musicTypes || MusicType.AARTI in musicTypes) {
            genreIds[GenreOntology.DEVOTIONAL] = musicTypes.values.maxOrNull() ?: 0.80
            if (MusicType.CHALISA in musicTypes) subgenreIds["chalisa"] = 0.92
            if (MusicType.AARTI in musicTypes) subgenreIds["aarti"] = 0.92
            if (MusicType.BHAJAN in musicTypes) subgenreIds["bhajan"] = 0.85
            if (MusicType.MANTRA in musicTypes) subgenreIds["mantra"] = 0.90
        }
        if (MusicType.SUFI in musicTypes || MusicType.GHAZAL in musicTypes || MusicType.QAWALI in musicTypes) {
            genreIds[GenreOntology.SUFI_GHAZAL] = 0.86
            if (MusicType.GHAZAL in musicTypes) subgenreIds["ghazal"] = 0.88
            if (MusicType.QAWALI in musicTypes) subgenreIds["qawwali"] = 0.90
            if (MusicType.SUFI in musicTypes) subgenreIds["sufi"] = 0.86
        }
        if (hasGurmukhi(song.title) || containsToken(combinedText, listOf("punjabi", "bhangra")) || PUNJABI_ARTISTS.any { artistNorm.contains(it) }) {
            genreIds[GenreOntology.PUNJABI] = if (hasGurmukhi(song.title) || containsToken(combinedText, listOf("punjabi"))) 0.88 else 0.72
            if (containsToken(combinedText, listOf("bhangra", "dhol"))) {
                subgenreIds["punjabi_bhangra"] = 0.82
            } else {
                subgenreIds["punjabi_pop"] = 0.72
            }
        }
        if (hasSouthIndicScript(song.title) || containsToken(combinedText, SOUTH_INDIC_TERMS)) {
            genreIds[GenreOntology.SOUTH_INDIAN] = 0.85
            if (hasTamilScript(song.title) || containsToken(combinedText, listOf("tamil", "kollywood", "anirudh", "ilayaraja"))) {
                subgenreIds["tamil_film"] = 0.85
            }
            if (hasTeluguScript(song.title) || containsToken(combinedText, listOf("telugu", "tollywood", "devi sri prasad", "thaman"))) {
                subgenreIds["telugu_film"] = 0.85
            }
            if (hasMalayalamScript(song.title) || containsToken(combinedText, listOf("malayalam", "mollywood"))) {
                subgenreIds["malayalam_film"] = 0.85
            }
            if (hasKannadaScript(song.title) || containsToken(combinedText, listOf("kannada", "sandalwood"))) {
                subgenreIds["kannada_film"] = 0.85
            }
        }
        if (MusicType.CLASSICAL in musicTypes) {
            genreIds[GenreOntology.INDIAN_CLASSICAL] = 0.84
        }
        if (containsToken(combinedText, listOf("lo-fi", "lofi", "chillhop"))) {
            genreIds[GenreOntology.LOFI_CHILL] = 0.85
        }
        if (RETRO_ARTISTS.any { artistNorm.contains(it) } && GenreOntology.DEVOTIONAL !in genreIds) {
            genreIds[GenreOntology.BOLLYWOOD] = 0.75
            subgenreIds["bollywood_retro"] = 0.70
        }
        if (BOLLYWOOD_90S_ARTISTS.any { artistNorm.contains(it) } && GenreOntology.DEVOTIONAL !in genreIds) {
            genreIds[GenreOntology.BOLLYWOOD] = maxOf(genreIds[GenreOntology.BOLLYWOOD] ?: 0.0, 0.75)
            subgenreIds["bollywood_90s"] = 0.70
        }
        if (TWO_THOUSANDS_ARTISTS.any { artistNorm.contains(it) } && GenreOntology.DEVOTIONAL !in genreIds) {
            genreIds[GenreOntology.BOLLYWOOD] = maxOf(genreIds[GenreOntology.BOLLYWOOD] ?: 0.0, 0.72)
            subgenreIds["bollywood_2000s"] = 0.70
        }
        if (MODERN_ROMANTIC_ARTISTS.any { artistNorm.contains(it) } && GenreOntology.DEVOTIONAL !in genreIds) {
            genreIds[GenreOntology.BOLLYWOOD] = maxOf(genreIds[GenreOntology.BOLLYWOOD] ?: 0.0, 0.68)
            subgenreIds["bollywood_romantic"] = 0.65
        }
        if (INDIE_ARTISTS.any { artistNorm.contains(it) }) {
            genreIds[GenreOntology.INDIAN_INDIE] = 0.78
            subgenreIds["indie_pop"] = 0.72
        }
        if (WESTERN_POP_ARTISTS.any { artistNorm.contains(it) }) {
            genreIds[GenreOntology.WESTERN_POP] = 0.72
        }
        if (hasExplicitSecularTitleTone && GenreOntology.EDM !in genreIds && GenreOntology.BOLLYWOOD !in genreIds && GenreOntology.PUNJABI !in genreIds) {
            genreIds[GenreOntology.EDM] = 0.72
        }

        enrichment?.verifiedGenreIds?.forEach { (rawGenre, conf) ->
            GenreOntology.canonicalize(rawGenre)?.let { canon ->
                val parent = GenreOntology.parentOf(canon)
                genreIds[parent] = maxOf(genreIds[parent] ?: 0.0, conf)
                if (canon != parent) subgenreIds[canon] = maxOf(subgenreIds[canon] ?: 0.0, conf)
                provenance["genre"] = "enrichment_${enrichment.providerName}"
            }
        }
        if (genreIds.isNotEmpty() && "genre" !in provenance) {
            provenance["genre"] = "tier1_metadata_heuristics"
        }

        // 3. Multi-label moods
        val moodIds = linkedMapOf<String, Double>()
        if (GenreOntology.DEVOTIONAL in genreIds) moodIds["devotional_spiritual"] = 0.88
        if (GenreOntology.SUFI_GHAZAL in genreIds) moodIds["sufi_mystical"] = 0.85
        if (containsToken(combinedText, SAD_MOOD_TERMS)) moodIds["sad_melancholic"] = 0.82
        if (containsToken(combinedText, PARTY_MOOD_TERMS) || FAST_ARTISTS.any { artistNorm.contains(it) }) {
            moodIds["party_celebratory"] = 0.78
            moodIds["energetic"] = 0.72
        }
        if (containsToken(combinedText, ROMANTIC_MOOD_TERMS) || MODERN_ROMANTIC_ARTISTS.any { artistNorm.contains(it) }) {
            moodIds["romantic_love"] = if (containsToken(combinedText, ROMANTIC_MOOD_TERMS)) 0.82 else 0.55
        }
        if (containsToken(combinedText, CHILL_MOOD_TERMS)) {
            moodIds["chill_relaxing"] = 0.80
            moodIds["calm"] = 0.72
        }
        if ("bollywood_retro" in subgenreIds || containsToken(combinedText, listOf("retro", "vintage", "purane", "purani yaadein", "old is gold"))) {
            moodIds["nostalgic_retro"] = 0.75
        }

        // 4. Separate script detection from ISO language IDs cleanly
        val scriptIds = detectScripts(song.title)
        val languageIds = linkedMapOf<String, Double>()
        var languageConf = 0.0

        when {
            !enrichment?.explicitLanguageId.isNullOrBlank() -> {
                languageIds[enrichment!!.explicitLanguageId!!.lowercase(Locale.ROOT)] = 0.95
                languageConf = 0.95
                provenance["language"] = "enrichment_${enrichment.providerName}"
            }
            hasTamilScript(song.title) || containsToken(combinedText, listOf("tamil", "kollywood")) -> {
                languageIds["ta"] = 0.90
                languageConf = 0.90
                provenance["language"] = "script_or_explicit_token"
            }
            hasTeluguScript(song.title) || containsToken(combinedText, listOf("telugu", "tollywood")) -> {
                languageIds["te"] = 0.90
                languageConf = 0.90
                provenance["language"] = "script_or_explicit_token"
            }
            hasMalayalamScript(song.title) || containsToken(combinedText, listOf("malayalam", "mollywood")) -> {
                languageIds["ml"] = 0.90
                languageConf = 0.90
                provenance["language"] = "script_or_explicit_token"
            }
            hasKannadaScript(song.title) || containsToken(combinedText, listOf("kannada", "sandalwood")) -> {
                languageIds["kn"] = 0.90
                languageConf = 0.90
                provenance["language"] = "script_or_explicit_token"
            }
            hasBengaliScript(song.title) || containsToken(combinedText, listOf("bengali", "bangla", "rabindra sangeet")) -> {
                languageIds["bn"] = 0.88
                languageConf = 0.88
                provenance["language"] = "script_or_explicit_token"
            }
            hasGurmukhi(song.title) || containsToken(combinedText, listOf("punjabi")) -> {
                languageIds["pa"] = 0.86
                languageConf = 0.86
                provenance["language"] = "script_or_explicit_token"
            }
            containsToken(combinedText, listOf("sanskrit", "stotram", "ashtakam", "shloka", "shlok", "suktam")) -> {
                languageIds["sa"] = 0.80
                languageConf = 0.80
                provenance["language"] = "explicit_liturgical_form"
            }
            containsToken(combinedText, listOf("hindi", "bollywood")) -> {
                languageIds["hi"] = 0.80
                languageConf = 0.80
                provenance["language"] = "explicit_token"
            }
            containsToken(titleAlbumText, listOf("english")) -> {
                languageIds["en"] = 0.85
                languageConf = 0.85
                provenance["language"] = "explicit_token"
            }
            else -> {
                val artistLang = artistLanguageLookup?.invoke(song.artist)
                when {
                    artistLang != null -> {
                        languageIds[artistLang.first] = artistLang.second
                        languageConf = artistLang.second
                        provenance["language"] = "artist_language_map"
                    }
                    "Deva" in scriptIds -> {
                        // Devanagari can be Hindi, Sanskrit, or Marathi — keep multi-label and moderate confidence
                        languageIds["hi"] = 0.55
                        languageIds["sa"] = if (MusicType.MANTRA in musicTypes || MusicType.CHALISA in musicTypes) 0.40 else 0.20
                        languageConf = 0.55
                        provenance["language"] = "devanagari_script_ambiguous"
                    }
                    GenreOntology.PUNJABI in genreIds -> {
                        languageIds["pa"] = 0.65
                        languageConf = 0.65
                        provenance["language"] = "punjabi_genre_prior"
                    }
                    GenreOntology.BOLLYWOOD in genreIds || GenreOntology.DEVOTIONAL in genreIds -> {
                        languageIds["hi"] = 0.55
                        languageConf = 0.55
                        provenance["language"] = "bollywood_or_devotional_prior"
                    }
                    GenreOntology.WESTERN_POP in genreIds -> {
                        languageIds["en"] = 0.60
                        languageConf = 0.60
                        provenance["language"] = "western_pop_prior"
                    }
                    else -> {
                        provenance["language"] = "unknown"
                    }
                }
            }
        }

        // 5. Era (verified release year separate from remaster year; unknown stays null)
        val verifiedYear = enrichment?.originalReleaseYear?.takeIf { it in 1900..2100 }
        val isRemasterOnly = enrichment?.isRemasterYearOnly == true
        val (eraYear, eraBucket, eraConf) = when {
            verifiedYear != null && !isRemasterOnly -> {
                provenance["era"] = "verified_release_year"
                Triple(verifiedYear, yearToEraBucket(verifiedYear), enrichment?.confidence?.coerceIn(0.7, 1.0) ?: 0.90)
            }
            verifiedYear != null && isRemasterOnly -> {
                provenance["era"] = "remaster_year_only"
                Triple(null, null, 0.0)
            }
            RETRO_ARTISTS.any { artistNorm.contains(it) } -> {
                provenance["era"] = "artist_era_prior"
                Triple(null, "1950_1979", 0.38)
            }
            BOLLYWOOD_90S_ARTISTS.any { artistNorm.contains(it) } -> {
                provenance["era"] = "artist_era_prior"
                Triple(null, "1980_1999", 0.38)
            }
            TWO_THOUSANDS_ARTISTS.any { artistNorm.contains(it) } -> {
                provenance["era"] = "artist_era_prior"
                Triple(null, "2000_2009", 0.35)
            }
            MODERN_ROMANTIC_ARTISTS.any { artistNorm.contains(it) } || PUNJABI_ARTISTS.any { artistNorm.contains(it) } -> {
                provenance["era"] = "artist_era_prior"
                Triple(null, "2010_plus", 0.32)
            }
            else -> {
                provenance["era"] = "unknown"
                Triple(null, null, 0.0)
            }
        }

        // 6. Tempo (unknown stays UNKNOWN/0.0 unless measured BPM or clear cue exists)
        val bpm = enrichment?.measuredBpm?.takeIf { it in 30.0..260.0 }
        val audioVector = bpm?.let {
            AudioFeatureVector(
                bpm = it,
                bpmConfidence = 0.92,
                featureModelVersion = enrichment?.providerName,
                analysedAtMillis = clock(),
            )
        }
        val (tempoBucket, tempoConf) = when {
            bpm != null -> {
                provenance["tempo"] = "measured_bpm"
                val bucket = when {
                    bpm < 88.0 -> TempoBucket.SLOW
                    bpm <= 118.0 -> TempoBucket.MEDIUM
                    else -> TempoBucket.FAST
                }
                bucket to 0.92
            }
            containsToken(combinedText, SLOW_TEMPO_TERMS) || GHAZAL_ARTISTS.any { artistNorm.contains(it) } -> {
                provenance["tempo"] = "title_or_genre_weak_cue"
                TempoBucket.SLOW to 0.38
            }
            containsToken(combinedText, FAST_TEMPO_TERMS) || FAST_ARTISTS.any { artistNorm.contains(it) } -> {
                provenance["tempo"] = "title_or_genre_weak_cue"
                TempoBucket.FAST to 0.38
            }
            else -> {
                provenance["tempo"] = "unknown"
                TempoBucket.UNKNOWN to 0.0
            }
        }

        val populatedDims = listOf(
            genreIds.isNotEmpty(),
            musicTypes.keys.any { it != MusicType.UNKNOWN },
            moodIds.isNotEmpty(),
            languageIds.isNotEmpty(),
            eraBucket != null,
            song.durationSec > 0L,
            !parsed.movie.isNullOrBlank() || !song.album.isNullOrBlank(),
            identity.releaseClassification.isVerifiedOriginalAudio,
        ).count { it }
        val completeness = populatedDims / 8.0

        val tags = buildSet {
            if (identity.releaseClassification.isVerifiedOriginalAudio) add("verified_original_audio")
            if (identity.releaseClassification.audioClass.isExplicitlyRejected) add("rejected_media_type")
            if (song.isTopicRelease) add("topic_release")
            if (song.hasSquareArtwork) add("square_artwork")
        }

        return TrackFeatureRecord(
            canonicalRecordingId = identity.canonicalRecordingId,
            providerItemId = song.id,
            primaryArtistIds = identity.providerRefs.firstOrNull()?.let { SongIdentity.normalizeArtists(song.artist) } ?: emptySet(),
            artistDisplayNames = listOf(song.artist).filter { it.isNotBlank() },
            albumOrReleaseId = enrichment?.albumReleaseId ?: parsed.movie ?: song.album,
            title = song.title,
            durationMs = song.durationSec.takeIf { it > 0L }?.times(1000L),
            genreIds = genreIds,
            subgenreIds = subgenreIds,
            musicTypes = musicTypes,
            moodIds = moodIds,
            languageIds = languageIds,
            languageConfidence = languageConf,
            scriptIds = scriptIds,
            eraStartYear = eraYear,
            eraBucket = eraBucket,
            eraConfidence = eraConf,
            isRemasterYearOnly = isRemasterOnly,
            tempoBucket = tempoBucket,
            tempoConfidence = tempoConf,
            versionKind = identity.versionKind,
            releaseAudioClass = identity.releaseClassification.audioClass,
            releaseConfidence = identity.releaseClassification.confidence,
            sourceConfidence = identity.confidence,
            metadataCompleteness = completeness,
            featureProvenance = provenance,
            audio = audioVector,
            tags = tags,
            updatedAtMillis = clock(),
        ).validated()
    }

    private fun yearToEraBucket(year: Int): String = when {
        year < 1980 -> "1950_1979"
        year in 1980..1999 -> "1980_1999"
        year in 2000..2009 -> "2000_2009"
        else -> "2010_plus"
    }

    private fun containsToken(text: String, terms: Collection<String>): Boolean {
        val words = text.split(Regex("""[\s\p{Punct}]+""")).filter { it.isNotBlank() }.toSet()
        return terms.any { term ->
            if (term.contains(" ")) text.contains(term) else term in words
        }
    }

    private fun hasGurmukhi(s: String) = s.any { it in '\u0A00'..'\u0A7F' }
    private fun hasBengaliScript(s: String) = s.any { it in '\u0980'..'\u09FF' }
    private fun hasTamilScript(s: String) = s.any { it in '\u0B80'..'\u0BFF' }
    private fun hasTeluguScript(s: String) = s.any { it in '\u0C00'..'\u0C7F' }
    private fun hasKannadaScript(s: String) = s.any { it in '\u0C80'..'\u0CFF' }
    private fun hasMalayalamScript(s: String) = s.any { it in '\u0D00'..'\u0D7F' }
    private fun hasSouthIndicScript(s: String) =
        hasTamilScript(s) || hasTeluguScript(s) || hasKannadaScript(s) || hasMalayalamScript(s)

    private fun detectScripts(s: String): Set<String> = buildSet {
        if (s.any { it in '\u0900'..'\u097F' }) add("Deva")
        if (hasGurmukhi(s)) add("Guru")
        if (hasBengaliScript(s)) add("Beng")
        if (hasTamilScript(s)) add("Taml")
        if (hasTeluguScript(s)) add("Telu")
        if (hasKannadaScript(s)) add("Knda")
        if (hasMalayalamScript(s)) add("Mlym")
        if (s.any { it in '\u0600'..'\u06FF' }) add("Arab")
        if (s.any { it in 'a'..'z' || it in 'A'..'Z' }) add("Latn")
    }

    companion object {
        private val DEVOTIONAL_DEITY_TERMS = setOf(
            "hanuman", "shree", "shri", "ram", "krishna", "radha", "shiv", "shiva", "mahadev",
            "ganesh", "ganpati", "durga", "gayatri", "sai", "mahakaal", "sankatmochan", "sunderkand",
        )
        private val DEVOTIONAL_ARTISTS = setOf(
            "gulshan kumar", "anuradha paudwal", "anup jalota", "lakhbir singh lakkha",
            "suresh wadkar", "hansraj raghuwanshi", "hariharan bhakti",
        )
        private val SUFI_ARTISTS = setOf("nusrat fateh ali khan", "rahat fateh ali khan", "abida parveen", "wadali brothers", "sabri brothers")
        private val GHAZAL_ARTISTS = setOf("jagjit singh", "mehdi hassan", "ghulam ali", "pankaj udhas", "chitra singh")
        private val PUNJABI_ARTISTS = setOf("sidhu moosewala", "karan aujla", "diljit dosanjh", "ap dhillon", "shubh", "b praak", "harrdy sandhu", "guru randhawa")
        private val SOUTH_INDIC_TERMS = setOf("tamil", "telugu", "malayalam", "kannada", "anirudh", "ilayaraja", "sid sriram", "spb", "ar rahman", "a r rahman", "devi sri prasad", "thaman")
        private val RETRO_ARTISTS = setOf("lata mangeshkar", "kishore kumar", "mohammed rafi", "asha bhosle", "mukesh", "r d burman", "s d burman")
        private val BOLLYWOOD_90S_ARTISTS = setOf("kumar sanu", "udit narayan", "alka yagnik", "abhijeet", "sonu nigam", "kavita krishnamurthy", "nadeem shravan")
        private val TWO_THOUSANDS_ARTISTS = setOf("shaan", "kk", "mohit chauhan", "sunidhi chauhan", "pritam", "himesh reshammiya")
        private val MODERN_ROMANTIC_ARTISTS = setOf("arijit singh", "shreya ghoshal", "atif aslam", "jubin nautiyal", "darshan raval", "armaan malik", "mithoon", "vishal mishra")
        private val INDIE_ARTISTS = setOf("prateek kuhad", "anuv jain", "jasleen royal", "when chai met toast", "ritviz", "the local train")
        private val WESTERN_POP_ARTISTS = setOf("taylor swift", "the weeknd", "dua lipa", "ed sheeran", "billie eilish", "coldplay", "lana del rey")
        private val FAST_ARTISTS = setOf("badshah", "yo yo honey singh", "honey singh", "mika singh", "raftaar", "guru randhawa", "diljit dosanjh")
        private val FAST_TEMPO_TERMS = setOf("dance", "bhangra", "party", "club", "dhol", "dhamaka", "fast", "groove")
        private val SLOW_TEMPO_TERMS = setOf("sad", "dard", "judaai", "melancholy", "ballad", "ghazal", "lori", "soothing", "calm", "peaceful", "tanhai")
        private val SAD_MOOD_TERMS = setOf("sad", "dard", "bewafa", "judaai", "tanha", "tanhai", "alvida", "aansoo", "breakup", "heartbreak", "gham")
        private val PARTY_MOOD_TERMS = setOf("party", "club", "nach", "dance", "dhamaka", "disco", "shaadi", "wedding", "bhangra", "dhol")
        private val ROMANTIC_MOOD_TERMS = setOf("love", "ishq", "pyaar", "pyar", "prem", "dil", "mohabbat", "hamsafar", "romantic", "sanam", "chahat")
        private val CHILL_MOOD_TERMS = setOf("chill", "relax", "calm", "peace", "lofi", "lo-fi", "sukoon", "breeze")
    }
}
