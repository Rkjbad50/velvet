package com.velvet.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class CoverMode {
    AUTO,
    TRACK_ART,
    CUSTOM_IMAGE,
    PRESET,
}

@Serializable
enum class SortKey {
    CUSTOM,
    TITLE,
    ARTIST,
    ALBUM,
    DATE_ADDED,
    DURATION,
}

@Serializable
enum class SortDirection {
    ASC,
    DESC,
}

@Serializable
data class PlaylistEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val song: Song,
    val addedAt: Long = 0L,
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long = -1,
    val thumb: String? = null,
    val artistUrl: String? = null,
    val preferredMusicSource: Boolean = false,
    val album: String? = null,
    val isTopicRelease: Boolean = false,
    val isUploaderVerified: Boolean = false,
    val rawUploaderName: String? = null,
    val hasSquareArtwork: Boolean = false,
    val releaseDescriptionHint: String? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val entries: List<PlaylistEntry> = emptyList(),
    val updatedAt: Long = 0,
    /** When it was created (0 for playlists saved before this field existed). */
    val addedAt: Long = 0,
    val createdAt: Long = 0L,
    val playCount: Long = 0,
    val lastPlayedAt: Long = 0,
    val customCover: String? = null,
    val description: String? = null,
    val coverMode: CoverMode = CoverMode.AUTO,
    val coverRef: String? = null,
    val sortKey: SortKey = SortKey.CUSTOM,
    val sortDirection: SortDirection = SortDirection.ASC,
) {
    fun resolvedEntries(): List<PlaylistEntry> {
        if (entries.isNotEmpty()) return entries
        return songs.mapIndexed { index, song ->
            PlaylistEntry(
                entryId = "${id}_legacy_${index}_${song.id}",
                song = song,
                addedAt = 0L,
            )
        }
    }

    val resolvedSongs: List<Song>
        get() = if (entries.isNotEmpty()) entries.map { it.song } else songs
}

internal fun List<Playlist>.homePlaylists(limit: Int = 2): List<Playlist> = asSequence()
    .filter { it.songs.isNotEmpty() }
    .sortedWith(
        compareByDescending<Playlist> { it.lastPlayedAt }
            .thenByDescending { it.playCount }
            .thenByDescending { maxOf(it.createdAt, it.addedAt, it.updatedAt) }
    )
    .take(limit)
    .toList()

/** A YouTube Music artist (a YouTube channel). */
@Serializable
data class ArtistRef(
    val url: String,
    val name: String,
    val thumb: String? = null,
    val subscribers: Long = -1,
    /** When the user followed it (0 for older entries). */
    val addedAt: Long = 0,
)

/** A YouTube Music album or a public playlist. */
@Serializable
data class AlbumRef(
    val url: String,
    val title: String,
    val subtitle: String = "",
    val thumb: String? = null,
    val isAlbum: Boolean = true,
    /** When the user saved it (0 for older entries). */
    val addedAt: Long = 0,
)

/** One row of search / browse results. */
sealed interface BrowseItem {
    data class SongItem(val song: Song) : BrowseItem
    data class ArtistItem(val artist: ArtistRef) : BrowseItem
    data class AlbumItem(val album: AlbumRef) : BrowseItem
}

fun formatDuration(seconds: Long): String {
    if (seconds < 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatCount(n: Long): String = when {
    n < 0 -> ""
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9).replace(".0B", "B")
    n >= 1_000_000 -> "%.1fM".format(n / 1e6).replace(".0M", "M")
    n >= 1_000 -> "%.1fK".format(n / 1e3).replace(".0K", "K")
    else -> n.toString()
}

fun totalDuration(songs: List<Song>): String {
    val total = songs.sumOf { it.durationSec.coerceAtLeast(0) }
    if (total <= 0) return ""
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "$h hr $m min" else "$m min"
}

/** Check if two songs represent the same track (restricting duplicate entries in queue). */
fun Song.isDuplicateOf(other: Song): Boolean {
    if (this.id == other.id) return true
    val t1 = this.title.normalizeSong()
    val t2 = other.title.normalizeSong()
    if (t1.isEmpty() || t2.isEmpty()) return false
    if (t1 == t2) {
        val a1 = this.artist.normalizeSong()
        val a2 = other.artist.normalizeSong()
        if (a1 == a2 || a1.contains(a2) || a2.contains(a1) || a1.isEmpty() || a2.isEmpty()) return true
        if (this.durationSec > 0 && other.durationSec > 0 && Math.abs(this.durationSec - other.durationSec) <= 4) return true
    }
    return false
}

/**
 * Strict track equality check for Smart Shuffle and queue deduplication.
 * Rejects tracks with matching normalized titles regardless of channel/uploader/creator differences,
 * ensuring no duplicate versions of the same song appear in the playlist or recommendations.
 */
fun Song.isSameTrackAs(other: Song): Boolean {
    if (this.id == other.id) return true
    val c1 = this.title.cleanSongTitle()
    val c2 = other.title.cleanSongTitle()
    if (c1.isNotEmpty() && c2.isNotEmpty()) {
        if (c1 == c2) return true
        val minLen = minOf(c1.length, c2.length)
        val maxLen = maxOf(c1.length, c2.length)
        if (minLen >= 5 && (c1.startsWith(c2) || c2.startsWith(c1) || c1.contains(c2) || c2.contains(c1))) {
            if (minLen.toFloat() / maxLen.toFloat() >= 0.70f) return true
        }
    }
    return this.isDuplicateOf(other)
}

fun String.cleanSongTitle(): String = this.lowercase()
    .replace(Regex("""\((?:official|video|audio|lyric|lyrics|remaster|remastered|hd|4k|8k|full|song|visualizer|from|feat|ft|ost|film version|original|music video)[^)]*\)"""), "")
    .replace(Regex("""\[(?:official|video|audio|lyric|lyrics|remaster|remastered|hd|4k|8k|full|song|visualizer|from|feat|ft|ost|film version|original|music video)[^\]]*\]"""), "")
    .replace("official video", "")
    .replace("full video song", "")
    .replace("video song", "")
    .replace("full song", "")
    .replace("lyrical song", "")
    .replace("audio song", "")
    .replace(Regex("""[^a-z0-9]"""), "")

private fun String.normalizeSong(): String = this.cleanSongTitle()

/**
 * The same artwork at about [px] pixels: list rows don't need the 544 px covers,
 * and smaller images download and decode much faster.
 */
fun thumbSized(url: String?, px: Int): String? {
    if (url == null) return null
    val size = px.coerceIn(60, 544)
    return when {
        Regex("""=w\d+-h\d+[^/]*$""").containsMatchIn(url) ->
            url.replace(Regex("""=w\d+-h\d+[^/]*$"""), "=w$size-h$size-l90-rj")
        Regex("""=s\d+[^/]*$""").containsMatchIn(url) -> url.replace(Regex("""=s\d+[^/]*$"""), "=s$size")
        size <= 200 && url.contains("i.ytimg.com") && url.contains("/hqdefault") ->
            url.replace("/hqdefault", "/mqdefault")
        else -> url
    }
}

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** YouTube video ids are exactly 11 characters from this alphabet; anything else is rejected. */
fun isValidVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)

/**
 * A small thread-safe map that drops the least recently used entries beyond [maxSize], for
 * session caches that would otherwise grow with every song touched.
 */
class BoundedCache<K : Any, V : Any>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    @Synchronized
    fun containsKey(key: K): Boolean = map.containsKey(key)

    @Synchronized
    fun getOrPut(key: K, create: () -> V): V = map[key] ?: create().also { map[key] = it }

    @Synchronized
    fun clear() = map.clear()
}

private val NON_MUSIC_REGEX = Regex(
    """(?i)\b(full movie|film|trailer|teaser|scene|clip|full scene|climax|podcast|episode|interview|documentary|audiobook|vlog|gameplay|funny moments|roast|reaction|unboxing|talk show|behind the scenes|making of|bloopers|status|whatsapp status|reels?|tiktok|short edit|bgm edit|dialogue|part \d+|fan made|fan video|dance cover|cover song|karaoke|slowed \+ reverb|slowed and reverb|8d audio|mashup edit|amv|guitar cover|piano cover|drum cover|parody)\b"""
)

/**
 * Strict validator for pure YouTube Music tracks.
 * Rejects movie scenes, interviews, podcasts, vlogs, meme edits, and abnormally long/short non-music tracks.
 */
fun Song.isPureMusic(): Boolean {
    if (durationSec in 1..44) return false // Too short to be a real track (soundbite/meme)
    if (durationSec > 780) return false // Exceeds 13 minutes (likely podcast, full movie scene, or dj set)
    val text = "$title $artist"
    if (NON_MUSIC_REGEX.containsMatchIn(text)) return false
    return true
}

/**
 * Ensures track is from native producer/singer/original source, filtering out
 * unofficial random YouTube video uploads, amateur covers, and meme re-uploads.
 */
fun Song.isOriginalMusicAudio(): Boolean {
    if (!isPureMusic()) return false
    val lower = "$title $artist".lowercase()
    if (lower.contains("cover") && !lower.contains("discover") && !lower.contains("under cover") && !lower.contains("album cover")) return false
    if (lower.contains("slowed") || lower.contains("reverb") || lower.contains("status")) return false
    if (lower.contains("8d audio") || lower.contains("bass boosted") || lower.contains("nightcore")) return false
    if (lower.contains("official video") && lower.contains("scene")) return false
    return true
}

