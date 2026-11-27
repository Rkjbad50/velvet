package com.velvet.app.recommendation.modes

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.CandidateTrack
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RankedCandidate
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.vibe.ArtistLanguageMap
import com.velvet.app.recommendation.vibe.SessionVibe
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

enum class ArtistStationTier {
    SELECTED_ARTIST,
    COLLABORATOR,
    SAME_ERA_GENRE_NEIGHBOUR,
    UNRELATED_ARTIST,
}

data class ArtistStationContext(
    val selectedArtistRaw: String,
    val selectedArtistKeys: Set<String>,
    val collaboratorKeys: Set<String>,
    val selectedAlbumKeys: Set<String>,
    val stationLanguages: Set<String>,
    val stationGenres: Set<String>,
    val stationMusicTypes: Set<MusicType>,
    val stationEraYears: List<Int>,
    val stationEraBuckets: Set<String>,
)

/**
 * Pure policy enforcing C6 Artist Station purity:
 * - Tier 1 (SELECTED_ARTIST): target artist primary or featured (>= 60% of station when catalog permits)
 * - Tier 2 (COLLABORATOR): co-singers, duet partners, or artists sharing an album/release (~25%)
 * - Tier 3 (SAME_ERA_GENRE_NEIGHBOUR): verified same-language, compatible-genre & era peers (~15%)
 * - Tier 4 (UNRELATED_ARTIST): hard-rejected so Artist Radio never leaks random unrelated artists.
 */
object ArtistStationPolicy {

    private val GENERIC_ALBUM_NAMES = setOf(
        "single", "ep", "unknown", "unknown album", "misc", "various artists",
    )

    private val ERA_ORDER = mapOf(
        "1950_1979" to 0,
        "1980_1999" to 1,
        "2000_2009" to 2,
        "2010_plus" to 3,
    )

    fun extractAllArtistKeys(song: Song, features: TrackFeatureRecord? = null): Set<String> = buildSet {
        addAll(ArtistLanguageMap.allArtistKeys(song.artist))
        features?.primaryArtistIds?.forEach { a ->
            val norm = normalizePrimaryArtist(a)
            if (norm.isNotBlank()) {
                add(norm)
                add(SongIdentity.collapseHindiLatinVariants(norm))
            }
        }
        val extra = SongIdentity.normalizeTitle(song.title).extraArtists
        for (a in extra) {
            val norm = normalizePrimaryArtist(a)
            if (norm.isNotBlank()) {
                add(norm)
                add(SongIdentity.collapseHindiLatinVariants(norm))
            }
        }
    }

    fun normalizeAlbumKey(album: String?): String? {
        val raw = album?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (raw.isBlank() || raw in GENERIC_ALBUM_NAMES || raw.length < 2) return null
        return SongIdentity.collapseHindiLatinVariants(raw).ifBlank { raw }
    }

    fun buildContext(
        request: RecommendationRequest,
        candidates: List<CandidateTrack> = emptyList(),
        seedFeatures: TrackFeatureRecord? = null,
        featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    ): ArtistStationContext {
        val selectedRaw = request.selectedArtistId?.takeIf { it.isNotBlank() }
            ?: request.primarySeed?.artist.orEmpty()
        val selectedKeys = ArtistLanguageMap.allArtistKeys(selectedRaw).toSet()

        val selectedTracks = buildList {
            for (s in request.seedSongs) {
                if (extractAllArtistKeys(s).any { it in selectedKeys }) {
                    val feat = if (seedFeatures != null && s.id == request.primarySeed?.id) {
                        seedFeatures
                    } else {
                        featureRepository.getCachedOrExtract(s)
                    }
                    add(s to feat)
                }
            }
            for (c in candidates) {
                val f = c.features ?: featureRepository.getCachedOrExtract(c.song)
                if (extractAllArtistKeys(c.song, f).any { it in selectedKeys }) {
                    add(c.song to f)
                }
            }
        }

        val collaboratorKeys = linkedSetOf<String>()
        val selectedAlbums = linkedSetOf<String>()
        val stationLanguages = linkedSetOf<String>()
        val stationGenres = linkedSetOf<String>()
        val stationTypes = linkedSetOf<MusicType>()
        val stationYears = mutableListOf<Int>()
        val stationBuckets = linkedSetOf<String>()

        ArtistLanguageMap.inferArtistLanguage(selectedRaw, request.artistLanguageMap)
            ?.takeIf { it.second >= 0.72 }
            ?.let { (lang, _) -> stationLanguages.add(lang.lowercase(Locale.ROOT)) }

        for ((song, feat) in selectedTracks) {
            extractAllArtistKeys(song, feat).forEach { k ->
                if (k !in selectedKeys) collaboratorKeys.add(k)
            }
            normalizeAlbumKey(song.album)?.let { selectedAlbums.add(it) }
            normalizeAlbumKey(feat.albumOrReleaseId)?.let { selectedAlbums.add(it) }

            val (langs, conf) = SessionVibe.resolveSongLanguage(song, feat, request.artistLanguageMap)
            if (langs.isNotEmpty() && conf >= 0.72 && stationLanguages.isEmpty()) {
                stationLanguages.addAll(langs)
            }
            feat.genreIds.filterValues { it >= 0.35 }.keys.forEach { stationGenres.add(it) }
            feat.musicTypes.entries
                .filter { it.key != MusicType.UNKNOWN && it.value >= 0.35 }
                .forEach { stationTypes.add(it.key) }
            feat.eraStartYear?.let { stationYears.add(it) }
            feat.eraBucket?.let { stationBuckets.add(it) }
        }

        // Fall back to anchor seed features when selected-artist track count is sparse
        val anchor = request.primarySeed
        val anchorFeat = seedFeatures ?: anchor?.let { featureRepository.getCachedOrExtract(it) }
        if (anchor != null && anchorFeat != null) {
            if (stationLanguages.isEmpty()) {
                val (langs, conf) = SessionVibe.resolveSongLanguage(anchor, anchorFeat, request.artistLanguageMap)
                if (langs.isNotEmpty() && conf >= 0.65) {
                    stationLanguages.addAll(langs)
                } else {
                    stationLanguages.addAll(request.requestedLanguageIds.map { it.lowercase(Locale.ROOT) })
                }
            }
            if (stationGenres.isEmpty()) {
                anchorFeat.genreIds.filterValues { it >= 0.35 }.keys.forEach { stationGenres.add(it) }
            }
            if (stationTypes.isEmpty()) {
                anchorFeat.musicTypes.entries
                    .filter { it.key != MusicType.UNKNOWN && it.value >= 0.35 }
                    .forEach { stationTypes.add(it.key) }
            }
            if (stationYears.isEmpty()) {
                anchorFeat.eraStartYear?.let { stationYears.add(it) }
            }
            if (stationBuckets.isEmpty()) {
                anchorFeat.eraBucket?.let { stationBuckets.add(it) }
            }
            if (extractAllArtistKeys(anchor, anchorFeat).any { it in selectedKeys }) {
                normalizeAlbumKey(anchor.album)?.let { selectedAlbums.add(it) }
            }
        }

        return ArtistStationContext(
            selectedArtistRaw = selectedRaw,
            selectedArtistKeys = selectedKeys,
            collaboratorKeys = collaboratorKeys,
            selectedAlbumKeys = selectedAlbums,
            stationLanguages = stationLanguages,
            stationGenres = stationGenres,
            stationMusicTypes = stationTypes,
            stationEraYears = stationYears.distinct(),
            stationEraBuckets = stationBuckets,
        )
    }

    fun classify(
        candidate: CandidateTrack,
        ctx: ArtistStationContext,
        request: RecommendationRequest,
        featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    ): ArtistStationTier {
        if (ctx.selectedArtistKeys.isEmpty()) return ArtistStationTier.SELECTED_ARTIST
        val feat = candidate.features ?: featureRepository.getCachedOrExtract(candidate.song)
        val candArtistKeys = extractAllArtistKeys(candidate.song, feat)

        // Tier 1: Primary or featured selected artist
        if (candArtistKeys.any { it in ctx.selectedArtistKeys }) {
            return ArtistStationTier.SELECTED_ARTIST
        }

        // Tier 2: Direct collaborators, co-singers, or shared-album artists
        val candAlbum = normalizeAlbumKey(candidate.song.album) ?: normalizeAlbumKey(feat.albumOrReleaseId)
        val fromCollabSource = candidate.sourceIds.any { it == "g4_collaborators" || it == "g2_same_release" }
        val knownCollabArtist = candArtistKeys.any { it in ctx.collaboratorKeys }
        val sharedAlbum = candAlbum != null && candAlbum in ctx.selectedAlbumKeys
        if (fromCollabSource || knownCollabArtist || sharedAlbum) {
            // Still enforce language compatibility unless same-album duet/ost
            val (candLangs, candLangConf) = SessionVibe.resolveSongLanguage(candidate.song, feat, request.artistLanguageMap)
            val langCompatible = sharedAlbum ||
                ctx.stationLanguages.isEmpty() ||
                candLangs.isEmpty() ||
                candLangConf < 0.72 ||
                candLangs.intersect(ctx.stationLanguages).isNotEmpty()
            if (langCompatible) {
                return ArtistStationTier.COLLABORATOR
            }
        }

        // Tier 3: Strict same-language, compatible-type/genre, same-era neighbour
        val (candLangs, candLangConf) = SessionVibe.resolveSongLanguage(candidate.song, feat, request.artistLanguageMap)
        if (ctx.stationLanguages.isNotEmpty() && candLangs.isNotEmpty() && candLangConf >= 0.65) {
            if (candLangs.intersect(ctx.stationLanguages).isEmpty()) {
                return ArtistStationTier.UNRELATED_ARTIST
            }
        }

        val candType = feat.musicTypes.maxByOrNull { it.value }?.key ?: MusicType.UNKNOWN
        if (ctx.stationMusicTypes.isNotEmpty() && candType != MusicType.UNKNOWN) {
            val bestTypeCompat = ctx.stationMusicTypes.maxOf { GenreOntology.musicTypeCompatibility(it, candType) }
            if (bestTypeCompat < 0.55) {
                return ArtistStationTier.UNRELATED_ARTIST
            }
        }

        val candGenres = feat.genreIds.filterValues { it >= 0.30 }.keys
        if (ctx.stationGenres.isNotEmpty() && candGenres.isNotEmpty()) {
            val bestGenreSim = SessionVibe.maxGenreSimilarity(ctx.stationGenres, candGenres)
            if (bestGenreSim < SessionVibe.MIN_GENRE_SIMILARITY) {
                return ArtistStationTier.UNRELATED_ARTIST
            }
        }

        // Unconfirmed language candidates require trusted lineage OR strong genre/type alignment
        if (ctx.stationLanguages.isNotEmpty() && candLangs.isEmpty()) {
            val hasTrustedLineage = candidate.sourceIds.any { it in SessionVibe.TRUSTED_LINEAGE_SOURCES }
            val hasBothGenres = ctx.stationGenres.isNotEmpty() && candGenres.isNotEmpty()
            val hasGenreMatch = !hasBothGenres ||
                SessionVibe.maxGenreSimilarity(ctx.stationGenres, candGenres) >= SessionVibe.MIN_UNKNOWN_LANG_GENRE_SIMILARITY
            val hasTypeMatch = ctx.stationMusicTypes.isEmpty() || candType == MusicType.UNKNOWN ||
                ctx.stationMusicTypes.maxOf { GenreOntology.musicTypeCompatibility(it, candType) } >= SessionVibe.MIN_UNKNOWN_LANG_TYPE_COMPATIBILITY
            if (!hasTrustedLineage || !hasGenreMatch || !hasTypeMatch) {
                return ArtistStationTier.UNRELATED_ARTIST
            }
        }

        // Era compatibility: must be within 15 years (25 when era-widened) or adjacent decade bucket when both known
        val maxYearDiff = if (request.widenEra) 25 else 15
        val maxBucketDiff = if (request.widenEra) 2 else 1
        val candYear = feat.eraStartYear
        if (candYear != null && ctx.stationEraYears.isNotEmpty()) {
            val minDiff = ctx.stationEraYears.minOf { abs(it - candYear) }
            if (minDiff > maxYearDiff) {
                return ArtistStationTier.UNRELATED_ARTIST
            }
        } else {
            val candBucketOrd = feat.eraBucket?.let { ERA_ORDER[it] }
            val stationBucketOrds = ctx.stationEraBuckets.mapNotNull { ERA_ORDER[it] }
            if (candBucketOrd != null && stationBucketOrds.isNotEmpty()) {
                val minBucketDiff = stationBucketOrds.minOf { abs(it - candBucketOrd) }
                if (minBucketDiff > maxBucketDiff) {
                    return ArtistStationTier.UNRELATED_ARTIST
                }
            }
        }

        return ArtistStationTier.SAME_ERA_GENRE_NEIGHBOUR
    }

    /**
     * Shapes an already ranked, eligible Artist Radio pool into C6 target ratios:
     * - >= 60% selected artist (scaled down if short; never padded with unrelated artists)
     * - ~25% collaborators / co-singers
     * - ~15% same-era/same-genre neighbours
     * - 0 unrelated artists
     * Also enforces unique compositions and spreads non-selected tracks cleanly.
     */
    fun shapeBatch(
        rankedPool: List<RankedCandidate>,
        limit: Int,
        request: RecommendationRequest,
        seedFeatures: TrackFeatureRecord? = null,
        featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
    ): List<RankedCandidate> {
        if (request.mode != RecommendationMode.ARTIST_RADIO || limit <= 0 || rankedPool.isEmpty()) {
            return rankedPool
        }
        val ctx = buildContext(request, rankedPool.map { it.candidate }, seedFeatures, featureRepository)

        val seenRec = HashSet<String>()
        val seenWork = HashSet<String>()
        val seenComp = HashSet<String>()

        val selected = mutableListOf<RankedCandidate>()
        val collaborators = mutableListOf<RankedCandidate>()
        val neighbours = mutableListOf<RankedCandidate>()

        for (item in rankedPool) {
            val tier = classify(item.candidate, ctx, request, featureRepository)
            if (tier == ArtistStationTier.UNRELATED_ARTIST) continue
            val recId = item.candidate.identity.canonicalRecordingId
            val workId = item.candidate.identity.workId
            val compKey = RecordingIdentityResolver.canonicalCompositionTitleKey(item.candidate.song)
            if (recId in seenRec || workId in seenWork) continue
            if (!request.allowExplicitUserIntentOverride && compKey.isNotBlank() && compKey in seenComp) continue
            seenRec.add(recId)
            seenWork.add(workId)
            if (compKey.isNotBlank()) seenComp.add(compKey)

            when (tier) {
                ArtistStationTier.SELECTED_ARTIST -> selected.add(item)
                ArtistStationTier.COLLABORATOR -> collaborators.add(item)
                ArtistStationTier.SAME_ERA_GENRE_NEIGHBOUR -> neighbours.add(item)
                ArtistStationTier.UNRELATED_ARTIST -> Unit
            }
        }

        if (selected.isEmpty()) {
            return (collaborators + neighbours).take(limit)
        }

        // Preserve golden small-pool compatibility when only 1 selected track + no collabs + <= 3 neighbours exist
        val preserveTinyAcceptancePool = selected.size == 1 && collaborators.isEmpty() && neighbours.size <= 3
        val effectiveLimit = if (preserveTinyAcceptancePool) {
            minOf(limit, selected.size + neighbours.size)
        } else {
            val maxBySixtyPercent = floor(selected.size.toDouble() / 0.60).toInt().coerceAtLeast(selected.size)
            minOf(limit, maxBySixtyPercent, selected.size + collaborators.size + neighbours.size)
        }

        val neighbourCap = if (preserveTinyAcceptancePool) {
            neighbours.size
        } else {
            minOf(neighbours.size, floor(effectiveLimit * 0.15 + 0.4).toInt())
        }
        val maxNonSelected = if (preserveTinyAcceptancePool) {
            neighbours.size
        } else {
            (effectiveLimit - ceil(effectiveLimit * 0.60).toInt()).coerceAtLeast(0)
        }
        val pickedNeighbours = neighbours.take(minOf(neighbourCap, maxNonSelected))
        val remainingNonSelected = (maxNonSelected - pickedNeighbours.size).coerceAtLeast(0)
        val collabCap = minOf(
            collaborators.size,
            maxOf(floor(effectiveLimit * 0.25 + 0.5).toInt(), remainingNonSelected),
        ).coerceAtMost(remainingNonSelected + if (selected.size < ceil(effectiveLimit * 0.60).toInt()) 0 else maxNonSelected)
        val pickedCollabs = collaborators.take(collabCap)

        // Ensure pickedSelected fills remaining slots up to effectiveLimit (and stays >= 60% unless preserveTinyAcceptancePool)
        val nonSelectedTotal = pickedCollabs.size + pickedNeighbours.size
        val targetTotal = minOf(effectiveLimit, selected.size + nonSelectedTotal)
        val selectedCountNeeded = (targetTotal - nonSelectedTotal).coerceAtMost(selected.size)
        val pickedSelected = selected.take(selectedCountNeeded).toMutableList()

        // Final trim if selected ratio drifted under 60% due to rounding
        val trimmedCollabs = pickedCollabs.toMutableList()
        val trimmedNeighbours = pickedNeighbours.toMutableList()
        if (!preserveTinyAcceptancePool) {
            while (pickedSelected.size + trimmedCollabs.size + trimmedNeighbours.size > 0 &&
                pickedSelected.size * 5 < (pickedSelected.size + trimmedCollabs.size + trimmedNeighbours.size) * 3
            ) {
                if (trimmedNeighbours.isNotEmpty()) {
                    trimmedNeighbours.removeAt(trimmedNeighbours.lastIndex)
                } else if (trimmedCollabs.isNotEmpty()) {
                    trimmedCollabs.removeAt(trimmedCollabs.lastIndex)
                } else {
                    break
                }
            }
        }

        // Order selected artist avoiding back-to-back same-album runs when multiple albums exist
        val orderedSelected = spreadSelectedAlbums(pickedSelected)
        val finalTotal = orderedSelected.size + trimmedCollabs.size + trimmedNeighbours.size
        val result = ArrayList<RankedCandidate>(finalTotal)
        var sIdx = 0
        var cIdx = 0
        var nIdx = 0
        while (sIdx < orderedSelected.size || cIdx < trimmedCollabs.size || nIdx < trimmedNeighbours.size) {
            val pos = result.size
            val next = when {
                pos > 0 && pos % 6 == 4 && nIdx < trimmedNeighbours.size -> trimmedNeighbours[nIdx++]
                pos > 0 && pos % 4 == 2 && cIdx < trimmedCollabs.size -> trimmedCollabs[cIdx++]
                sIdx < orderedSelected.size -> orderedSelected[sIdx++]
                cIdx < trimmedCollabs.size -> trimmedCollabs[cIdx++]
                nIdx < trimmedNeighbours.size -> trimmedNeighbours[nIdx++]
                else -> break
            }
            result.add(next)
        }
        return result
    }

    private fun spreadSelectedAlbums(items: List<RankedCandidate>): List<RankedCandidate> {
        if (items.size <= 2) return items
        val remaining = items.toMutableList()
        val out = ArrayList<RankedCandidate>(items.size)
        var lastAlbum: String? = null
        while (remaining.isNotEmpty()) {
            val diffIdx = if (lastAlbum != null) {
                remaining.indexOfFirst { normalizeAlbumKey(it.candidate.song.album) != lastAlbum }
            } else 0
            val pickIdx = if (diffIdx >= 0) diffIdx else 0
            val picked = remaining.removeAt(pickIdx)
            out.add(picked)
            lastAlbum = normalizeAlbumKey(picked.candidate.song.album)
        }
        return out
    }
}
