package com.velvet.app.recommendation.identity

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.recommendation.model.MusicalWorkRef
import com.velvet.app.recommendation.model.ProviderTrackRef
import com.velvet.app.recommendation.model.ReleaseAudioClass
import com.velvet.app.recommendation.model.TrackIdentity
import com.velvet.app.recommendation.model.VersionKind
import java.util.Locale

/**
 * Separates provider item identity, canonical recording identity (performance/arrangement),
 * and underlying musical work identity while delegating matching heuristics to [SongIdentity].
 */
object RecordingIdentityResolver {

    private val WORK_VERSION_PAREN_REGEX = Regex(
        """[\[(][^)\]]*\b(?:remix|remixed|dj\s+mix|club\s+mix|slowed|reverb|lofi|lo-fi|sped\s+up|speed\s+up|speedup|""" +
            """nightcore|acoustic|unplugged|live|concert|cover|instrumental|karaoke|mashup|remaster|remastered|""" +
            """reprise|jhankar|encore|version)\b[^)\]]*[\])]""",
        RegexOption.IGNORE_CASE,
    )

    private val WORK_VERSION_TAIL_REGEX = Regex(
        """(?i)(?:\s*-\s*(?:dj\s+)?(?:remix|live(?:\s+performance|\s+concert|\s+session)?|acoustic|unplugged|""" +
            """slowed(?:\s*\+?\s*reverb)?|reverb|lofi|lo-fi|mashup|instrumental|karaoke|cover(?:\s+by\s+.*)?)|""" +
            """\b(?:live\s+performance|live\s+concert|dj\s+remix|slowed\s*\+?\s*reverb)\b)$""",
    )

    fun resolveWork(song: Song): MusicalWorkRef {
        val rawTitle = song.title.trim()
        val baseTitle = WORK_VERSION_TAIL_REGEX.replace(
            WORK_VERSION_PAREN_REGEX.replace(rawTitle, " "),
            " ",
        ).trim().ifBlank { rawTitle }
        val parsed = SongIdentity.normalizeTitle(baseTitle)
        val rawParsed = SongIdentity.normalizeTitle(rawTitle)
        val artists = (SongIdentity.normalizeArtists(song.artist) + rawParsed.extraArtists)
            .filter { it.isNotBlank() }
            .toSet()
        val primary = artists.firstOrNull() ?: song.artist.lowercase(Locale.ROOT).trim().take(20)
        return MusicalWorkRef(
            workId = "work:${parsed.comparisonKey}|$primary",
            normalizedTitle = parsed.cleanTitle,
            comparisonKey = parsed.comparisonKey,
            primaryArtists = artists,
            albumOrWorkTitle = rawParsed.movie ?: song.album,
        )
    }

    fun resolve(
        song: Song,
        musicBrainzRecordingId: String? = null,
        isrc: String? = null,
        fetchedAtMillis: Long = 0L,
    ): TrackIdentity {
        val parsed = SongIdentity.normalizeTitle(song.title)
        val work = resolveWork(song)
        val classification = ReleaseAudioClassifier.classify(song)
        val versionKind = ReleaseAudioClassifier.detectVersionKind(song)
        val versionSuffix = versionBucket(versionKind, parsed.versionTags)

        val primaryArtist = work.primaryArtists.firstOrNull()
            ?: song.artist.lowercase(Locale.ROOT).trim().take(20)
        val canonicalRecId = when {
            !isrc.isNullOrBlank() -> "rec:isrc:${isrc.trim().uppercase(Locale.ROOT)}"
            !musicBrainzRecordingId.isNullOrBlank() -> "rec:mbid:${musicBrainzRecordingId.trim()}"
            else -> "rec:${work.comparisonKey}|$primaryArtist|$versionSuffix"
        }

        val evidence = buildSet {
            addAll(classification.evidence)
            add("version_kind:${versionKind.name.lowercase(Locale.ROOT)}")
            if (!isrc.isNullOrBlank()) add("isrc")
            if (!musicBrainzRecordingId.isNullOrBlank()) add("musicbrainz_id")
            if (!parsed.movie.isNullOrBlank() || !song.album.isNullOrBlank()) add("album_or_movie_present")
        }

        val identityConfidence = when {
            !isrc.isNullOrBlank() || !musicBrainzRecordingId.isNullOrBlank() -> 0.98
            classification.audioClass == ReleaseAudioClass.VERIFIED_TOPIC_AUDIO && parsed.comparisonKey.isNotBlank() -> 0.92
            classification.isVerifiedOriginalAudio && parsed.comparisonKey.isNotBlank() && work.primaryArtists.isNotEmpty() -> 0.85
            parsed.comparisonKey.isNotBlank() && work.primaryArtists.isNotEmpty() -> 0.72
            else -> 0.45
        }

        val providerRef = ProviderTrackRef(
            provider = "youtube",
            providerItemId = song.id,
            playbackLocator = song.artistUrl,
            uploaderOrChannel = song.rawUploaderName ?: song.artist,
            verifiedChannel = song.isUploaderVerified,
            isTopicChannel = song.isTopicRelease || classification.audioClass == ReleaseAudioClass.VERIFIED_TOPIC_AUDIO,
            fetchedAtMillis = fetchedAtMillis,
        )

        return TrackIdentity(
            providerRefs = listOf(providerRef),
            canonicalRecordingId = canonicalRecId,
            workId = work.workId,
            musicBrainzRecordingId = musicBrainzRecordingId,
            isrc = isrc,
            confidence = identityConfidence,
            versionKind = versionKind,
            releaseClassification = classification,
            identityEvidence = evidence,
        )
    }

    /**
     * Checks whether two provider tracks represent the exact same underlying recording
     * (never collapsing distinct live, remix, cover, acoustic, or studio recordings).
     */
    fun isSameRecording(a: Song, b: Song): Boolean {
        if (a.id.isNotBlank() && a.id == b.id) return true
        val vA = ReleaseAudioClassifier.detectVersionKind(a)
        val vB = ReleaseAudioClassifier.detectVersionKind(b)
        if (!areVersionKindsCompatibleForRecordingMatch(vA, vB)) return false
        return SongIdentity.isStrictMatch(a, b)
    }

    /**
     * Checks whether two tracks share the same musical composition/work even if one is
     * studio original and another is acoustic/live/remix/cover.
     */
    fun isSameMusicalWork(a: Song, b: Song): Boolean {
        val wA = resolveWork(a)
        val wB = resolveWork(b)
        if (wA.comparisonKey.isBlank() || wB.comparisonKey.isBlank()) return false
        if (wA.comparisonKey != wB.comparisonKey) return false
        return wA.primaryArtists.isEmpty() || wB.primaryArtists.isEmpty() ||
            wA.primaryArtists.any { it in wB.primaryArtists }
    }

    private val HONORIFIC_NOISE_WORDS = setOf(
        "shree", "shri", "sri", "jai", "om", "maa", "mata", "baba", "lord",
        "the", "a", "an", "of", "vol", "part", "chapter", "full", "original",
    )

    fun canonicalCompositionTitleKey(song: Song): String = resolveWork(song).comparisonKey

    /**
     * Detects when two tracks represent the same underlying song/chalisa/bhajan composition title
     * regardless of singer or uploader (e.g., "Hanuman Chalisa - Hariharan" vs "Shree Hanuman Chalisa - Gulshan Kumar"),
     * preventing Autoplay / Similar Songs from repeating the same title in a queue.
     */
    fun isSameCompositionTitle(a: Song, b: Song): Boolean {
        val kA = canonicalCompositionTitleKey(a)
        val kB = canonicalCompositionTitleKey(b)
        if (kA.isBlank() || kB.isBlank()) return false
        if (kA == kB) return true

        val tokensA = kA.split(' ').filter { it.length >= 3 && it !in HONORIFIC_NOISE_WORDS }.toSet()
        val tokensB = kB.split(' ').filter { it.length >= 3 && it !in HONORIFIC_NOISE_WORDS }.toSet()
        if (tokensA.isNotEmpty() && tokensA == tokensB) return true
        if (tokensA.size >= 2 && tokensB.size >= 2) {
            val smaller = minOf(tokensA.size, tokensB.size)
            val overlap = tokensA.intersect(tokensB).size
            if (overlap == smaller && smaller >= 2) return true
        }
        return false
    }

    /**
     * Selects the cleanest verified studio audio upload within a duplicate recording cluster
     * for recommendation surfaces. Returns null if no candidate passes verified studio audio policy.
     */
    fun selectBestVerifiedOriginalAudio(group: List<Song>): Song? {
        if (group.isEmpty()) return null
        val verified = group.filter { ReleaseAudioClassifier.classify(it).isVerifiedOriginalAudio }
        if (verified.isEmpty()) return null
        return verified.maxWithOrNull(
            compareBy<Song> { ReleaseAudioClassifier.classify(it).audioClass.qualityTier }
                .thenBy { ReleaseAudioClassifier.classify(it).confidence }
                .thenBy { it.durationSec > 0 }
                .thenBy { !it.album.isNullOrBlank() }
                .thenBy { it.hasSquareArtwork }
                .thenByDescending { it.id },
        )
    }

    private fun areVersionKindsCompatibleForRecordingMatch(a: VersionKind, b: VersionKind): Boolean {
        if (a == b) return true
        val unverifiedOrStudio = setOf(VersionKind.ORIGINAL_STUDIO, VersionKind.UNKNOWN, VersionKind.OFFICIAL_MUSIC_VIDEO)
        if (a in unverifiedOrStudio && b in unverifiedOrStudio) return true
        return false
    }

    private fun versionBucket(kind: VersionKind, extraTags: Set<String>): String = when (kind) {
        VersionKind.ORIGINAL_STUDIO -> "studio"
        VersionKind.UNKNOWN -> if (extraTags.isEmpty()) "studio" else extraTags.sorted().joinToString(",")
        VersionKind.OFFICIAL_MUSIC_VIDEO -> "official_video"
        VersionKind.LYRIC_VIDEO -> "lyric_video"
        VersionKind.VISUALIZER -> "visualizer"
        else -> kind.name.lowercase(Locale.ROOT)
    }
}
