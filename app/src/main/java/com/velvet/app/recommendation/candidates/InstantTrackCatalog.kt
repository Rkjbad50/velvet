package com.velvet.app.recommendation.candidates

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.CandidateSourceStatus
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RecommendationMode
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Ultra-fast in-memory registry of verified studio tracks populated passively by Home shelves,
 * Search results, Related fetches, and Library tracks. Allows Autoplay & Queue to populate
 * verified similar studio songs in < 2ms with zero blocking network wait.
 */
object InstantTrackCatalog {

    private const val MAX_ENTRIES = 500
    private val pool = ConcurrentHashMap<String, Song>()

    fun seed(songs: List<Song>) {
        if (songs.isEmpty()) return
        for (song in songs) {
            if (song.id.isBlank() || song.title.isBlank()) continue
            val cls = RecordingIdentityResolver.resolve(song).releaseClassification
            if (!cls.isVerifiedOriginalAudio) continue
            pool[song.id] = song
        }
        if (pool.size > MAX_ENTRIES) {
            val overflow = pool.size - MAX_ENTRIES
            val keysToDrop = pool.keys.take(overflow)
            keysToDrop.forEach { pool.remove(it) }
        }
    }

    fun snapshot(): List<Song> = pool.values.toList()

    fun clear() = pool.clear()

    /**
     * Synchronously ranks in-memory studio tracks matching [seed] strictly by:
     * Genre -> MusicType / Category -> Mood -> Language -> Era -> Artist diversity.
     */
    fun matchSimilar(
        seed: Song,
        extraPool: List<Song> = emptyList(),
        excludedIds: Set<String> = emptySet(),
        excludedRecIds: Set<String> = emptySet(),
        preferredLanguageIds: Set<String> = emptySet(),
        featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
        allowArtistHeavy: Boolean = false,
        limit: Int = 10,
    ): List<Song> {
        val combined = (pool.values + extraPool).distinctBy { it.id }
        if (combined.isEmpty() || limit <= 0) return emptyList()

        val seedFeat = featureRepository.getCachedOrExtract(seed)
        val seedTopType = seedFeat.musicTypes.maxByOrNull { it.value }?.key
        val seedIsDevotional = GenreOntology.DEVOTIONAL in seedFeat.genreIds ||
            seedTopType in setOf(
                MusicType.CHALISA,
                MusicType.AARTI,
                MusicType.BHAJAN,
                MusicType.MANTRA,
                MusicType.DEVOTIONAL,
            )
        val seedArtistNorm = normalizePrimaryArtist(seed.artist)

        val scored = combined.mapNotNull { cand ->
            if (cand.id.isBlank() || cand.id == seed.id || cand.id in excludedIds) return@mapNotNull null
            if (cand.durationSec == 0L || cand.durationSec in 1L..44L) return@mapNotNull null
            val identity = RecordingIdentityResolver.resolve(cand)
            if (!identity.releaseClassification.isVerifiedOriginalAudio) return@mapNotNull null
            if (identity.canonicalRecordingId in excludedRecIds ||
                SongIdentity.canonicalGroupId(cand) in excludedRecIds
            ) {
                return@mapNotNull null
            }
            if (RecordingIdentityResolver.isSameCompositionTitle(seed, cand)) return@mapNotNull null

            val feat = featureRepository.getCachedOrExtract(cand)
            val candType = feat.musicTypes.maxByOrNull { it.value }?.key
            if (candType == MusicType.PODCAST_OR_SPOKEN) return@mapNotNull null

            // Guard devotional integrity strictly
            if (seedIsDevotional) {
                val candDevotional = GenreOntology.DEVOTIONAL in feat.genreIds || MusicType.CLASSICAL in feat.musicTypes
                if (!candDevotional) return@mapNotNull null
                if ((feat.moodIds["party_celebratory"] ?: 0.0) >= 0.5) return@mapNotNull null
            }

            // Score: Genre first, then Category/Type, Mood, Language, Era, Tier
            val genreSim = seedFeat.genreIds.keys.maxOfOrNull { sg ->
                feat.genreIds.keys.maxOfOrNull { cg -> GenreOntology.similarity(sg, cg) } ?: 0.0
            } ?: 0.0
            val subgenreBonus = if (seedFeat.subgenreIds.keys.any { it in feat.subgenreIds }) 0.20 else 0.0
            val typeSim = if (seedTopType != null && candType != null &&
                seedTopType != MusicType.UNKNOWN && candType != MusicType.UNKNOWN
            ) {
                GenreOntology.musicTypeCompatibility(seedTopType, candType)
            } else 0.30
            val moodOverlap = seedFeat.moodIds.keys.intersect(feat.moodIds.keys).size.toDouble() /
                maxOf(1, maxOf(seedFeat.moodIds.size, feat.moodIds.size))
            val langBonus = when {
                seedFeat.languageIds.keys.any { it in feat.languageIds } -> 0.16
                preferredLanguageIds.isNotEmpty() && feat.languageIds.keys.any { it in preferredLanguageIds } -> 0.12
                else -> 0.0
            }
            val candArtistNorm = normalizePrimaryArtist(cand.artist)
            val sameArtist = allowArtistHeavy && seedArtistNorm.isNotBlank() && candArtistNorm == seedArtistNorm
            val sharedLanguage = seedFeat.languageIds.keys.any { it in feat.languageIds }
            val hasMinimumAffinity = when {
                seedIsDevotional -> true
                sameArtist -> true
                seedFeat.languageIds.isNotEmpty() ->
                    genreSim >= 0.25 || sharedLanguage || (moodOverlap >= 0.25 && typeSim >= 0.5)
                else ->
                    genreSim >= 0.25 || subgenreBonus > 0.0 || moodOverlap >= 0.2 || typeSim >= 0.65
            }
            if (!hasMinimumAffinity) return@mapNotNull null

            val eraBonus = if (seedFeat.eraBucket != null && seedFeat.eraBucket == feat.eraBucket) 0.08 else 0.0
            val artistBonus = if (sameArtist) 0.35 else 0.0
            val tierBonus = identity.releaseClassification.audioClass.qualityTier * 0.02

            val total = genreSim * 0.45 + subgenreBonus + typeSim * 0.25 + moodOverlap * 0.12 +
                langBonus + eraBonus + artistBonus + tierBonus

            cand to total
        }.sortedByDescending { it.second }

        val capPerArtist = if (allowArtistHeavy) limit else 2
        val artistCounts = mutableMapOf<String, Int>()
        val picked = mutableListOf<Song>()
        for ((song, _) in scored) {
            if (picked.size >= limit) break
            val key = normalizePrimaryArtist(song.artist).ifBlank { song.artist.lowercase() }
            val count = artistCounts[key] ?: 0
            if (count >= capPerArtist) continue
            artistCounts[key] = count + 1
            picked.add(song)
        }
        return picked
    }
}

/**
 * Local sub-millisecond candidate source backed by [InstantTrackCatalog] + Library so Queue refills
 * always have high-confidence studio-audio candidates even before slower remote sources return.
 */
class InstantTrackCatalogCandidateSource(
    private val extraSongsProvider: () -> List<Song> = { emptyList() },
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g0_instant_catalog"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seed = request.request.primarySeed
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        return try {
            val matches = InstantTrackCatalog.matchSimilar(
                seed = seed,
                extraPool = extraSongsProvider(),
                excludedIds = request.request.excludedProviderIds,
                excludedRecIds = request.request.excludedRecordingIds,
                preferredLanguageIds = request.request.requestedLanguageIds,
                featureRepository = featureRepository,
                allowArtistHeavy = request.request.mode == RecommendationMode.ARTIST_RADIO,
                limit = request.maxCandidates,
            )
            val wrapped = matches.mapIndexed { idx, song ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = 0.72 - (idx * 0.02).coerceAtMost(0.4),
                    evidenceCode = "instant_catalog_similarity",
                    confidence = 0.78,
                    fetchedAtMillis = clock(),
                )
            }
            CandidateSourceResult(
                sourceId = sourceId,
                candidates = wrapped,
                fetchedAtMillis = clock(),
                latencyMillis = (clock() - start).coerceAtLeast(0L),
                status = if (wrapped.isEmpty()) CandidateSourceStatus.EMPTY else CandidateSourceStatus.SUCCESS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CandidateSourceResult(sourceId, emptyList(), clock(), (clock() - start).coerceAtLeast(0L), CandidateSourceStatus.UNAVAILABLE, e.message)
        }
    }
}
