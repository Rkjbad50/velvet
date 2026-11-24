package com.velvet.app.recommendation.candidates

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.model.CandidateSourceStatus
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.TrackFeatureRecord
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * G1: Seed-related / radio continuation source.
 */
class SeedRelatedCandidateSource(
    private val relatedProvider: suspend (Song) -> List<Song>,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g1_related"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seed = request.request.primarySeed
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        return try {
            val raw = relatedProvider(seed).take(request.maxCandidates)
            val wrapped = raw.mapIndexed { idx, song ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = 1.0 - (idx * 0.02).coerceAtMost(0.8),
                    evidenceCode = "seed_related_track",
                    confidence = 0.75,
                    fetchedAtMillis = clock(),
                )
            }
            val elapsed = (clock() - start).coerceAtLeast(0L)
            CandidateSourceResult(
                sourceId = sourceId,
                candidates = wrapped,
                fetchedAtMillis = clock(),
                latencyMillis = elapsed,
                status = if (wrapped.isEmpty()) CandidateSourceStatus.EMPTY else CandidateSourceStatus.SUCCESS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CandidateSourceResult(sourceId, emptyList(), clock(), (clock() - start).coerceAtLeast(0L), CandidateSourceStatus.UNAVAILABLE, e.message)
        }
    }
}

/**
 * G2: Same release / album / soundtrack context source.
 */
class SameReleaseCandidateSource(
    private val releaseTracksProvider: suspend (String) -> List<Song>,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g2_same_release"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seed = request.request.primarySeed
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        val parsed = SongIdentity.normalizeTitle(seed.title)
        val releaseQuery = seed.album?.takeIf { it.isNotBlank() } ?: parsed.movie
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)

        return try {
            val raw = releaseTracksProvider(releaseQuery).take(request.maxCandidates)
            val wrapped = raw.mapIndexed { idx, song ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = 0.85,
                    evidenceCode = "same_release:$releaseQuery",
                    confidence = 0.80,
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

/**
 * G3: Primary artist catalogue source (small quota in normal mode; large in ARTIST_RADIO).
 */
class ArtistCatalogueCandidateSource(
    private val artistTracksProvider: suspend (String) -> List<Song>,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g3_primary_artist"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seed = request.request.primarySeed
        val artist = request.request.selectedArtistId
            ?: seed?.artist?.let(::normalizePrimaryArtist)
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        if (artist.isBlank()) {
            return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        }
        val cap = if (request.request.mode == RecommendationMode.ARTIST_RADIO) {
            request.maxCandidates
        } else {
            minOf(request.maxCandidates, 5)
        }
        return try {
            val raw = artistTracksProvider(artist).take(cap)
            val wrapped = raw.mapIndexed { idx, song ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = if (request.request.mode == RecommendationMode.ARTIST_RADIO) 0.95 else 0.55,
                    evidenceCode = "primary_artist:$artist",
                    confidence = 0.70,
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

/**
 * G4: Collaborators / featured artist source.
 */
class CollaboratorCandidateSource(
    private val artistTracksProvider: suspend (String) -> List<Song>,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g4_collaborators"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seed = request.request.primarySeed
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        val parsed = SongIdentity.normalizeTitle(seed.title)
        val primary = normalizePrimaryArtist(seed.artist)
        val coArtists = (parsed.extraArtists + SongIdentity.normalizeArtists(seed.artist) - primary)
            .filter { it.isNotBlank() }
        if (coArtists.isEmpty()) {
            return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        }
        val perCoCap = if (request.request.mode == RecommendationMode.ARTIST_RADIO) 5 else 2
        val coLimit = if (request.request.mode == RecommendationMode.ARTIST_RADIO) 4 else 2
        return try {
            val list = mutableListOf<RawTrackCandidate>()
            var rank = 0
            for (co in coArtists.take(coLimit)) {
                for (song in artistTracksProvider(co).take(perCoCap)) {
                    list.add(
                        RawTrackCandidate(
                            song = song,
                            sourceId = sourceId,
                            sourceRank = rank++,
                            retrievalScore = 0.65,
                            evidenceCode = "collaborator:$co",
                            confidence = 0.65,
                            fetchedAtMillis = clock(),
                        ),
                    )
                }
            }
            CandidateSourceResult(
                sourceId = sourceId,
                candidates = list.take(request.maxCandidates),
                fetchedAtMillis = clock(),
                latencyMillis = (clock() - start).coerceAtLeast(0L),
                status = if (list.isEmpty()) CandidateSourceStatus.EMPTY else CandidateSourceStatus.SUCCESS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CandidateSourceResult(sourceId, emptyList(), clock(), (clock() - start).coerceAtLeast(0L), CandidateSourceStatus.UNAVAILABLE, e.message)
        }
    }
}

/**
 * G5: Real Genre / Subgenre / MusicType / Language Neighbours generator (replaces the empty v2.7.5 stub).
 * Uses structured seed features + GenreOntology to query related music streams cleanly without dominating by artist.
 */
class GenreLanguageNeighbourSource(
    private val querySearchProvider: suspend (String) -> List<Song>,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g5_genre_neighbours"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seedFeatures = request.seedFeatures.firstOrNull()
        val query = buildGenreQuery(seedFeatures, request)
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)

        return try {
            val songs = querySearchProvider(query).take(request.maxCandidates)
            val wrapped = songs.mapIndexed { idx, song ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = 0.78,
                    evidenceCode = "genre_neighbour_query:$query",
                    confidence = 0.70,
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

    internal fun buildGenreQuery(features: TrackFeatureRecord?, req: CandidateSourceRequest): String? {
        val explicitGenre = req.request.selectedGenreIds.firstOrNull()
        val topSubgenre = features?.subgenreIds?.maxByOrNull { it.value }?.key
        val topGenre = features?.genreIds?.maxByOrNull { it.value }?.key
        val topType = features?.musicTypes?.maxByOrNull { it.value }?.key
        val topLang = req.request.requestedLanguageIds.firstOrNull()
            ?: features?.languageIds?.maxByOrNull { it.value }?.key

        val langLabel = when (topLang) {
            "hi" -> "Hindi"
            "pa" -> "Punjabi"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "ml" -> "Malayalam"
            "kn" -> "Kannada"
            "bn" -> "Bengali"
            "sa" -> "Sanskrit"
            "en" -> "English"
            else -> ""
        }

        val coreTopic = when {
            explicitGenre != null -> explicitGenre.replace('_', ' ')
            topType == MusicType.CHALISA -> "Hanuman Chalisa devotional audio"
            topType == MusicType.AARTI -> "$langLabel aarti devotional songs".trim()
            topType == MusicType.MANTRA -> "Sanskrit mantra chanting audio"
            topType == MusicType.BHAJAN -> "$langLabel bhajan devotional songs".trim()
            topType == MusicType.QAWALI -> "Qawwali sufi original audio"
            topType == MusicType.GHAZAL -> "Classic ghazal audio"
            topType == MusicType.SUFI -> "Sufi original audio songs"
            topSubgenre != null -> "${topSubgenre.replace('_', ' ')} songs"
            topGenre != null -> "${topGenre.replace('_', ' ')} songs"
            else -> null
        } ?: return null

        val eraHint = when (features?.eraBucket) {
            "1950_1979" -> "classic retro "
            "1980_1999" -> "90s "
            "2000_2009" -> "2000s "
            else -> ""
        }
        return "$eraHint$langLabel $coreTopic".replace(Regex("""\s+"""), " ").trim()
    }
}

/**
 * G6: Feature-aware Owner's Library generator (retrieves by genre/type/mood/language & taste affinity,
 * rather than restricting to exact same artist only).
 */
class LibraryTrackCandidateSource(
    private val libraryTracksProvider: suspend () -> List<Song>,
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g6_library"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seedFeatures = request.seedFeatures.firstOrNull()
        return try {
            val allLibrary = libraryTracksProvider()
            if (allLibrary.isEmpty()) {
                return CandidateSourceResult(sourceId, emptyList(), clock(), (clock() - start).coerceAtLeast(0L), CandidateSourceStatus.EMPTY)
            }

            val scored = allLibrary.mapNotNull { song ->
                val f = featureRepository.getCachedOrExtract(song)
                val sim = if (seedFeatures != null) featureSimilarity(seedFeatures, f) else 0.35
                if (sim >= 0.25) song to sim else null
            }.sortedByDescending { it.second }.take(request.maxCandidates)

            val wrapped = scored.mapIndexed { idx, (song, sim) ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = sim,
                    evidenceCode = "library_feature_match",
                    confidence = 0.85,
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

    private fun featureSimilarity(a: TrackFeatureRecord, b: TrackFeatureRecord): Double {
        var score = 0.0
        val typeA = a.musicTypes.maxByOrNull { it.value }?.key
        val typeB = b.musicTypes.maxByOrNull { it.value }?.key
        if (typeA != null && typeB != null) {
            score += 0.35 * GenreOntology.musicTypeCompatibility(typeA, typeB)
        }
        val genreOverlap = a.genreIds.keys.maxOfOrNull { ga ->
            b.genreIds.keys.maxOfOrNull { gb -> GenreOntology.similarity(ga, gb) } ?: 0.0
        } ?: 0.0
        score += 0.40 * genreOverlap
        val moodOverlap = a.moodIds.keys.intersect(b.moodIds.keys).size.toDouble() /
            maxOf(1, maxOf(a.moodIds.size, b.moodIds.size))
        score += 0.15 * moodOverlap
        val sameAlbum = !a.albumOrReleaseId.isNullOrBlank() && a.albumOrReleaseId.equals(b.albumOrReleaseId, ignoreCase = true)
        if (sameAlbum) score += 0.20
        val sameArtist = a.primaryArtistIds.any { it in b.primaryArtistIds }
        if (sameArtist) score += 0.15
        return score.coerceIn(0.0, 1.0)
    }
}

/**
 * G7: Session Momentum source retrieving compatible recent session songs by session vector fit.
 */
class SessionMomentumCandidateSource(
    private val recentSessionSongsProvider: suspend () -> List<Song>,
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g7_session_momentum"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val seed = request.request.primarySeed
        return try {
            val recent = recentSessionSongsProvider()
            if (recent.isEmpty()) {
                return CandidateSourceResult(sourceId, emptyList(), clock(), (clock() - start).coerceAtLeast(0L), CandidateSourceStatus.EMPTY)
            }
            val seedGenre = request.seedFeatures.firstOrNull()?.genreIds?.keys ?: emptySet()
            val matches = recent.filter { s ->
                s.id != seed?.id && (
                    seedGenre.isEmpty() ||
                        featureRepository.getCachedOrExtract(s).genreIds.keys.any { g ->
                            seedGenre.any { sg -> GenreOntology.similarity(g, sg) >= 0.4 }
                        }
                    )
            }.take(request.maxCandidates)

            val wrapped = matches.mapIndexed { idx, song ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = 0.65,
                    evidenceCode = "session_momentum_recent",
                    confidence = 0.65,
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

/**
 * G8: Audio Similarity Neighbours interface/stub cleanly disabled until Tier-3 embeddings exist (Blueprint Gate 4).
 */
class AudioNeighbourCandidateSource(
    override val enabled: Boolean = false,
    private val vectorLookupProvider: (suspend (TrackFeatureRecord, Int) -> List<Song>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g8_audio_neighbours"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val now = clock()
        if (!enabled || vectorLookupProvider == null) {
            return CandidateSourceResult(
                sourceId = sourceId,
                candidates = emptyList(),
                fetchedAtMillis = now,
                latencyMillis = 0L,
                status = CandidateSourceStatus.UNAVAILABLE,
                errorCode = "audio_embeddings_disabled_phase4",
            )
        }
        val seedFeatures = request.seedFeatures.firstOrNull()
        val hasEmbedding = seedFeatures?.audio?.embedding?.isNotEmpty() == true
        if (!hasEmbedding) {
            return CandidateSourceResult(sourceId, emptyList(), now, 0L, CandidateSourceStatus.EMPTY)
        }
        return try {
            val songs = vectorLookupProvider.invoke(seedFeatures!!, request.maxCandidates)
            val wrapped = songs.mapIndexed { idx, song ->
                RawTrackCandidate(song, sourceId, idx, 0.85, "audio_embedding_nn", 0.75, clock())
            }
            CandidateSourceResult(sourceId, wrapped, clock(), (clock() - now).coerceAtLeast(0L), CandidateSourceStatus.SUCCESS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CandidateSourceResult(sourceId, emptyList(), clock(), (clock() - now).coerceAtLeast(0L), CandidateSourceStatus.UNAVAILABLE, e.message)
        }
    }
}

/**
 * G13: Search-Intent retrieval using active converted session search interest cleanly.
 */
class SearchIntentCandidateSource(
    private val querySearchProvider: suspend (String) -> List<Song>,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecommendationCandidateSource {
    override val sourceId: String = "g13_search_intent"

    override suspend fun fetch(request: CandidateSourceRequest): CandidateSourceResult {
        val start = clock()
        val searchIntent = request.userProfile.searchIntent
        if (searchIntent.convertedQueriesCount == 0 || searchIntent.queryCategoryWeights.isEmpty()) {
            return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        }
        val topCategory = searchIntent.queryCategoryWeights.maxByOrNull { it.value }?.key
            ?: return CandidateSourceResult(sourceId, emptyList(), start, 0L, CandidateSourceStatus.EMPTY)
        val query = "${topCategory.replace('_', ' ')} songs"
        return try {
            val songs = querySearchProvider(query).take(minOf(request.maxCandidates, 8))
            val wrapped = songs.mapIndexed { idx, song ->
                RawTrackCandidate(
                    song = song,
                    sourceId = sourceId,
                    sourceRank = idx,
                    retrievalScore = 0.60,
                    evidenceCode = "converted_search_intent:$topCategory",
                    confidence = 0.65,
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
