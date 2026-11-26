package com.velvet.app.recommendation.candidates

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.playback.normalizePrimaryArtist
import com.velvet.app.recommendation.features.TrackFeatureRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.model.TrackIdentity
import java.util.concurrent.ConcurrentHashMap

data class IndexedLibraryTrack(
    val song: Song,
    val identity: TrackIdentity,
    val features: TrackFeatureRecord,
    val signature: String,
)

data class LibraryIndexSnapshot(
    val allVerifiedSongs: List<Song> = emptyList(),
    val byLanguage: Map<String, List<Song>> = emptyMap(),
    val byGenre: Map<String, List<Song>> = emptyMap(),
    val byArtist: Map<String, List<Song>> = emptyMap(),
    val digest: String = "",
)

/**
 * Incrementally maintained off-main index of verified studio audio tracks across Liked,
 * Recent, and User Playlists so refills never linearly scan/extract the full library on Main thread.
 */
class LibraryIndex(
    private val featureRepository: TrackFeatureRepository = TrackFeatureRepository(),
) {
    private val indexedCache = ConcurrentHashMap<String, IndexedLibraryTrack>()
    @Volatile
    var snapshot: LibraryIndexSnapshot = LibraryIndexSnapshot()
        private set

    private fun songSignature(song: Song): String =
        "${song.id}|${song.title}|${song.artist}|${song.isTopicRelease}|${song.isUploaderVerified}|${song.rawUploaderName.orEmpty()}"

    private fun buildLibraryDigest(data: LibraryData): String = buildString {
        append(data.liked.size).append(':')
        append(data.liked.firstOrNull()?.id.orEmpty()).append(':')
        append(data.liked.lastOrNull()?.id.orEmpty()).append('|')
        append(data.recent.size).append(':')
        append(data.recent.firstOrNull()?.id.orEmpty()).append('|')
        append(data.playlists.size).append(':')
        append(data.playlists.sumOf { it.songs.size })
    }

    /**
     * Updates the index incrementally from [data]. Safe to run repeatedly; unchanged song signatures
     * reuse cached identity & feature records with zero redundant extraction.
     */
    fun updateIncremental(data: LibraryData): LibraryIndexSnapshot {
        val digest = buildLibraryDigest(data)
        if (digest == snapshot.digest && snapshot.allVerifiedSongs.isNotEmpty()) {
            return snapshot
        }

        val combined = (data.liked + data.recent + data.playlists.flatMap { it.songs })
            .distinctBy { it.id }

        val activeKeys = HashSet<String>(combined.size * 2)
        val verifiedSongs = ArrayList<Song>(combined.size)
        val langBuckets = HashMap<String, MutableList<Song>>()
        val genreBuckets = HashMap<String, MutableList<Song>>()
        val artistBuckets = HashMap<String, MutableList<Song>>()

        for (song in combined) {
            if (song.id.isBlank() || song.title.isBlank()) continue
            activeKeys.add(song.id)
            val sig = songSignature(song)
            val entry = indexedCache[song.id]?.takeIf { it.signature == sig } ?: run {
                val idRec = RecordingIdentityResolver.resolve(song)
                val featRec = featureRepository.getCachedOrExtract(song)
                IndexedLibraryTrack(song, idRec, featRec, sig).also { indexedCache[song.id] = it }
            }
            if (!entry.identity.releaseClassification.isVerifiedOriginalAudio) continue
            verifiedSongs.add(song)

            for (lang in entry.features.languageIds.keys) {
                langBuckets.getOrPut(lang) { ArrayList() }.add(song)
            }
            for (genre in entry.features.genreIds.keys) {
                genreBuckets.getOrPut(genre) { ArrayList() }.add(song)
            }
            val normArtist = normalizePrimaryArtist(song.artist)
            if (normArtist.isNotBlank()) {
                artistBuckets.getOrPut(normArtist) { ArrayList() }.add(song)
            }
        }

        if (indexedCache.size > 2_500) {
            indexedCache.keys.filter { it !in activeKeys }.forEach { indexedCache.remove(it) }
        }

        val nextSnapshot = LibraryIndexSnapshot(
            allVerifiedSongs = verifiedSongs,
            byLanguage = langBuckets,
            byGenre = genreBuckets,
            byArtist = artistBuckets,
            digest = digest,
        )
        snapshot = nextSnapshot
        return nextSnapshot
    }

    /**
     * Queries pre-indexed buckets rather than linearly scanning every playlist track per refill.
     */
    fun findSimilarFromIndex(
        seed: Song,
        excludedIds: Set<String> = emptySet(),
        excludedRecIds: Set<String> = emptySet(),
        preferredLanguageIds: Set<String> = emptySet(),
        allowArtistHeavy: Boolean = false,
        extraPool: List<Song> = emptyList(),
        limit: Int = 15,
        allowMixedLanguages: Boolean = false,
        artistLanguageMap: Map<String, com.velvet.app.recommendation.vibe.ArtistLanguageProfile> =
            com.velvet.app.recommendation.vibe.ArtistLanguageRegistry.snapshot(),
    ): List<Song> {
        val currentSnap = snapshot
        val seedFeat = featureRepository.getCachedOrExtract(seed)
        val bucketPool = LinkedHashMap<String, Song>()

        fun addBucket(list: List<Song>?) {
            if (list.isNullOrEmpty()) return
            for (s in list) {
                if (s.id !in bucketPool) bucketPool[s.id] = s
            }
        }

        extraPool.forEach { if (it.id !in bucketPool) bucketPool[it.id] = it }
        seedFeat.genreIds.keys.forEach { addBucket(currentSnap.byGenre[it]) }
        val seedArtistNorm = normalizePrimaryArtist(seed.artist)
        if (seedArtistNorm.isNotBlank()) addBucket(currentSnap.byArtist[seedArtistNorm])
        val targetLangs = if (allowMixedLanguages && preferredLanguageIds.isNotEmpty()) {
            preferredLanguageIds
        } else {
            seedFeat.languageIds.keys.ifEmpty { preferredLanguageIds }
        }
        targetLangs.forEach { addBucket(currentSnap.byLanguage[it]) }
        if (bucketPool.size < 40) {
            addBucket(currentSnap.allVerifiedSongs.take(80))
        }

        return InstantTrackCatalog.matchSimilar(
            seed = seed,
            extraPool = bucketPool.values.toList(),
            excludedIds = excludedIds,
            excludedRecIds = excludedRecIds,
            preferredLanguageIds = preferredLanguageIds,
            featureRepository = featureRepository,
            allowArtistHeavy = allowArtistHeavy,
            limit = limit,
            allowMixedLanguages = allowMixedLanguages,
            artistLanguageMap = artistLanguageMap,
        )
    }

    fun clear() {
        indexedCache.clear()
        snapshot = LibraryIndexSnapshot()
    }
}
