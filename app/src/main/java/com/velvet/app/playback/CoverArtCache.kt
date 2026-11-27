package com.velvet.app.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CoverArtCacheEntry(
    val videoId: String,
    val thumbUrl: String,
    val isSquareArtwork: Boolean = false,
    val updatedAtMs: Long = 0L,
)

@Serializable
private data class CoverArtCacheFile(
    val entries: List<CoverArtCacheEntry> = emptyList(),
)

/**
 * Bounded persistent per-track artwork cache keyed by [videoId].
 * Keeps UI rows, queue state, MiniPlayer, NowPlaying, and Media3 metadata consistent across
 * sessions without letting generic `i.ytimg.com/vi/` frames overwrite square YTM artwork.
 */
object CoverArtCache {
    const val MAX_ENTRIES = 1_000
    const val MAX_FILE_BYTES = 512L * 1024L
    private const val CACHE_FILENAME = "cover_art_cache.json"

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val map = LinkedHashMap<String, CoverArtCacheEntry>(128, 0.75f, true)
    private var cacheFile: File? = null

    fun attachDirectory(dir: File) {
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, CACHE_FILENAME)
                cacheFile = file
                map.clear()
                if (file.exists() && file.length() in 1L..MAX_FILE_BYTES) {
                    val decoded = json.decodeFromString<CoverArtCacheFile>(file.readText())
                    decoded.entries.takeLast(MAX_ENTRIES).forEach { entry ->
                        if (entry.videoId.isNotBlank() && entry.thumbUrl.isNotBlank()) {
                            map[entry.videoId] = entry
                        }
                    }
                    trimToBoundsLocked()
                }
            }
        }
    }

    fun get(videoId: String): String? = getEntry(videoId)?.thumbUrl

    fun getEntry(videoId: String): CoverArtCacheEntry? {
        if (videoId.isBlank()) return null
        return synchronized(lock) { map[videoId] }
    }

    fun put(
        videoId: String,
        thumbUrl: String?,
        isSquareArtwork: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        persist: Boolean = true,
    ): Boolean {
        val cleanId = videoId.trim()
        val cleanUrl = thumbUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (cleanId.isBlank()) return false
        val urlLooksSquare = isSquareArtwork ||
            cleanUrl.contains("lh3.googleusercontent.com", ignoreCase = true) ||
            cleanUrl.contains("yt3.googleusercontent.com", ignoreCase = true)
        val isGeneric = CoverArtResolver.isGenericVideoFallbackThumb(cleanUrl)
        var changed = false
        synchronized(lock) {
            val existing = map[cleanId]
            if (existing != null) {
                val existingIsGeneric = CoverArtResolver.isGenericVideoFallbackThumb(existing.thumbUrl)
                if (!existingIsGeneric && isGeneric) {
                    return false
                }
                if (existing.thumbUrl == cleanUrl && (existing.isSquareArtwork || !urlLooksSquare)) {
                    return false
                }
            }
            map[cleanId] = CoverArtCacheEntry(
                videoId = cleanId,
                thumbUrl = cleanUrl,
                isSquareArtwork = urlLooksSquare,
                updatedAtMs = nowMs,
            )
            trimToBoundsLocked()
            changed = true
            if (persist) saveToDiskLocked()
        }
        return changed
    }

    fun putBatch(entries: List<Pair<String, String>>, nowMs: Long = System.currentTimeMillis()): Int {
        if (entries.isEmpty()) return 0
        var count = 0
        synchronized(lock) {
            for ((vid, url) in entries) {
                if (put(vid, url, nowMs = nowMs, persist = false)) {
                    count++
                }
            }
            if (count > 0) saveToDiskLocked()
        }
        return count
    }

    fun snapshot(): Map<String, CoverArtCacheEntry> = synchronized(lock) { LinkedHashMap(map) }

    fun size(): Int = synchronized(lock) { map.size }

    fun clearForTest(deleteFile: Boolean = true) {
        synchronized(lock) {
            map.clear()
            if (deleteFile) {
                runCatching { cacheFile?.delete() }
            }
            cacheFile = null
        }
    }

    private fun trimToBoundsLocked() {
        while (map.size > MAX_ENTRIES) {
            val eldestKey = map.entries.firstOrNull()?.key ?: break
            map.remove(eldestKey)
        }
    }

    private fun saveToDiskLocked() {
        val target = cacheFile ?: return
        runCatching {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            var entriesToWrite = map.values.toList()
            var encoded = json.encodeToString(CoverArtCacheFile(entriesToWrite))
            while (encoded.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES && entriesToWrite.size > 16) {
                val dropCount = maxOf(1, entriesToWrite.size / 4)
                entriesToWrite = entriesToWrite.drop(dropCount)
                encoded = json.encodeToString(CoverArtCacheFile(entriesToWrite))
            }
            val tmp = File(parent, "$CACHE_FILENAME.tmp")
            tmp.writeText(encoded)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }
}
