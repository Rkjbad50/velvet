package com.velvet.app.recommendation.features

import com.velvet.app.data.BoundedCache
import com.velvet.app.data.Song
import com.velvet.app.recommendation.model.TrackFeatureRecord

/**
 * Tier-2 external metadata enrichment payload (MusicBrainz / ISRC / curated release data).
 * Never stores unverified guesses as truth; every value retains source confidence.
 */
data class EnrichmentMetadata(
    val canonicalKey: String,
    val musicBrainzRecordingId: String? = null,
    val isrc: String? = null,
    val originalReleaseYear: Int? = null,
    val isRemasterYearOnly: Boolean = false,
    val albumReleaseId: String? = null,
    val verifiedGenreIds: Map<String, Double> = emptyMap(),
    val explicitLanguageId: String? = null,
    val measuredBpm: Double? = null,
    val confidence: Double = 0.0,
    val providerName: String = "none",
)

/**
 * Async abstraction for Tier-2 metadata enrichment (Phase 10).
 * Phase 2 keeps [NoOpMetadataEnrichmentProvider] active with zero live network calls.
 */
interface MetadataEnrichmentProvider {
    suspend fun lookup(song: Song): EnrichmentMetadata?
}

object NoOpMetadataEnrichmentProvider : MetadataEnrichmentProvider {
    override suspend fun lookup(song: Song): EnrichmentMetadata? = null
}

/**
 * Thread-safe feature cache keyed by provider ID + schema version.
 */
class TrackFeatureRepository(
    private val extractor: MetadataFeatureExtractor = MetadataFeatureExtractor(),
    private val enrichmentProvider: MetadataEnrichmentProvider = NoOpMetadataEnrichmentProvider,
    maxCacheSize: Int = 1000,
) {
    private val cache = BoundedCache<String, TrackFeatureRecord>(maxCacheSize)

    private fun cacheKey(song: Song): String =
        "${song.id}|v${TrackFeatureRecord.CURRENT_FEATURE_SCHEMA_VERSION}|" +
            "${song.isTopicRelease}|${song.isUploaderVerified}|${song.rawUploaderName.orEmpty()}"

    fun getCachedOrExtract(song: Song): TrackFeatureRecord {
        val key = cacheKey(song)
        return cache.getOrPut(key) {
            extractor.extract(song)
        }
    }

    suspend fun enrichCached(songs: List<Song>): Map<String, TrackFeatureRecord> {
        val out = LinkedHashMap<String, TrackFeatureRecord>(songs.size)
        for (song in songs) {
            val cached = cache[cacheKey(song)]
            if (cached != null) {
                out[song.id] = cached
                continue
            }
            val enrichment = runCatching { enrichmentProvider.lookup(song) }.getOrNull()
            val record = extractor.extract(
                song = song,
                enrichment = enrichment,
            )
            cache[cacheKey(song)] = record
            out[song.id] = record
        }
        return out
    }

    fun clear() = cache.clear()
}
