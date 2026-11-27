package com.velvet.app.playback

import com.velvet.app.data.Song
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * Thread-safe in-memory ring buffer of canonicalKey + videoId for recent playback history.
 */
class SessionHistory(val maxSize: Int = 200) {
    private val lock = Any()
    private val songEntries = ArrayDeque<Song>()
    private val keyEntries = ArrayDeque<Pair<String, String>>()

    fun record(song: Song) {
        val key = canonicalKey(song.title, song.artist)
        record(key, song.id)
        synchronized(lock) {
            songEntries.removeAll { it.id == song.id || SongIdentity.isStrictMatch(it, song) }
            songEntries.addLast(song)
            while (songEntries.size > maxSize) {
                songEntries.removeFirst()
            }
        }
    }

    fun record(canonicalKey: String, videoId: String) {
        synchronized(lock) {
            keyEntries.removeAll { it.first == canonicalKey || it.second == videoId }
            keyEntries.addLast(canonicalKey to videoId)
            while (keyEntries.size > maxSize) {
                keyEntries.removeFirst()
            }
        }
    }

    fun contains(song: Song): Boolean {
        synchronized(lock) {
            if (songEntries.any { it.id == song.id || SongIdentity.isStrictMatch(it, song) }) return true
            val key = canonicalKey(song.title, song.artist)
            return keyEntries.any { it.first == key || it.second == song.id }
        }
    }

    fun contains(canonicalKey: String, videoId: String): Boolean {
        synchronized(lock) {
            return keyEntries.any { it.first == canonicalKey || it.second == videoId } ||
                songEntries.any { it.id == videoId || canonicalKey(it.title, it.artist) == canonicalKey }
        }
    }

    fun clear() {
        synchronized(lock) {
            songEntries.clear()
            keyEntries.clear()
        }
    }

    fun size(): Int = synchronized(lock) { maxOf(songEntries.size, keyEntries.size) }
}

/**
 * Computes canonical identity key for a song:
 * canonicalKey = normalize(title) + "|" + normalize(primaryArtist).
 */
fun canonicalKey(title: String, artist: String): String {
    val normTitle = normalizeTitle(title)
    val normArtist = normalizePrimaryArtist(artist)
    return "$normTitle|$normArtist"
}

/**
 * Normalizes title by removing noise, bracketed descriptions, video descriptors, and punctuation.
 */
fun normalizeTitle(title: String): String {
    val nfkd = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    val stripped = nfkd.replace(Regex("\\p{M}"), "")
    val withoutBrackets = stripped
        .replace(Regex("""\((?:official|video|audio|lyric|lyrics|remaster|remastered|hd|4k|8k|full|song|visualizer|from|feat|ft|ost|film version|original|music video)[^)]*\)"""), "")
        .replace(Regex("""\[(?:official|video|audio|lyric|lyrics|remaster|remastered|hd|4k|8k|full|song|visualizer|from|feat|ft|ost|film version|original|music video)[^\]]*\]"""), "")
    val withoutPipes = withoutBrackets.split("|").firstOrNull() ?: withoutBrackets
    val cleaned = withoutPipes
        .replace(" - topic", "")
        .replace("- topic", "")
        .replace("vevo", "")
        .replace("official video", "")
        .replace("full video song", "")
        .replace("full video", "")
        .replace("video song", "")
        .replace("full song", "")
        .replace("lyrical song", "")
        .replace("audio song", "")
        .replace("lyric video", "")
    return cleaned.replace(Regex("""[^\p{L}0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

/**
 * Normalizes artist down to the primary artist (before ",", "&", " x ", "feat", "ft").
 */
fun normalizePrimaryArtist(artist: String): String {
    val first = artist.split(Regex(""",|&|\bx\b|\bfeat\b|\bft\b""", RegexOption.IGNORE_CASE))
        .firstOrNull() ?: artist
    val nfkd = Normalizer.normalize(first.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    val stripped = nfkd.replace(Regex("\\p{M}"), "")
    val cleaned = stripped
        .replace(" - topic", "")
        .replace("- topic", "")
        .replace("vevo", "")
    return cleaned.replace(Regex("""[^\p{L}0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
}
