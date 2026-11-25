package com.velvet.app.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.velvet.app.VelvetApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Lightweight native YouTube Music InnerTube (WEB_REMIX / ANDROID_VR) client working alongside
 * NewPipeExtractor to provide:
 * 1) Genuine YouTube Music Song Radio / Autoplay ("RDAMVM") & Related Browse recommendations
 *    using authoritative musicVideoType signals (MUSIC_VIDEO_TYPE_ATV accepted; OMV/UGC rejected).
 * 2) Direct YT Music Songs search fallback when NewPipe search fails or rate-limits.
 * 3) Tertiary audio stream player extraction fallback for resilient playback.
 */
object YouTubeMusicInnertube {

    private const val YTM_ORIGIN = "https://music.youtube.com"
    private const val YTM_NEXT_URL = "$YTM_ORIGIN/youtubei/v1/next?prettyPrint=false"
    private const val YTM_SEARCH_URL = "$YTM_ORIGIN/youtubei/v1/search?prettyPrint=false"
    private const val YTM_BROWSE_URL = "$YTM_ORIGIN/youtubei/v1/browse?prettyPrint=false"
    private const val YT_PLAYER_URL = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false"
    private const val SONGS_SEARCH_PARAMS = "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D"
    private const val WEB_REMIX_CLIENT_VERSION = "1.20241120.01.00"
    private const val ANDROID_VR_CLIENT_VERSION = "1.60.19"
    private const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val REJECTED_VIDEO_TYPES = setOf(
        "MUSIC_VIDEO_TYPE_OMV",
        "MUSIC_VIDEO_TYPE_UGC",
        "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
        "MUSIC_VIDEO_TYPE_PRIVATE_VIDEO",
    )

    internal data class FallbackAudioCandidate(
        val url: String,
        val kbps: Int,
        val opus: Boolean,
        val m4a: Boolean,
        val itag: Int,
        val drc: Boolean,
        val headers: Map<String, String>,
    )

    private fun webRemixContext(): JsonObject {
        val locale = Locale.getDefault()
        val hl = locale.language.ifBlank { "en" }
        val gl = locale.country.ifBlank { "US" }
        return JsonObject().apply {
            put(
                "client",
                JsonObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", WEB_REMIX_CLIENT_VERSION)
                    put("hl", hl)
                    put("gl", gl)
                    put("platform", "DESKTOP")
                },
            )
            put(
                "user",
                JsonObject().apply {
                    put("lockedSafetyMode", false)
                },
            )
        }
    }

    private fun androidVrContext(): JsonObject {
        val locale = Locale.getDefault()
        val hl = locale.language.ifBlank { "en" }
        val gl = locale.country.ifBlank { "US" }
        return JsonObject().apply {
            put(
                "client",
                JsonObject().apply {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", ANDROID_VR_CLIENT_VERSION)
                    put("hl", hl)
                    put("gl", gl)
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("androidSdkVersion", 32)
                    put("osName", "Android")
                    put("osVersion", "12L")
                },
            )
        }
    }

    private fun postJson(url: String, payload: JsonObject, userAgent: String = YtDownloader.USER_AGENT): JsonObject? {
        val client = runCatching {
            VelvetApp.http.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }.getOrNull() ?: return null

        val bodyText = JsonWriter.string().`object`(payload).done()
        val req = Request.Builder()
            .url(url)
            .post(bodyText.toRequestBody(JSON_MEDIA))
            .header("User-Agent", userAgent)
            .header("Origin", YTM_ORIGIN)
            .header("Referer", "$YTM_ORIGIN/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
            .header("Accept-Language", Locale.getDefault().toLanguageTag())
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                JsonParser.`object`().from(raw)
            }
        }.getOrNull()
    }

    /**
     * Fetches YouTube Music's genuine Up-Next Song Radio ("RDAMVM<videoId>") plus Related browse
     * continuation when available, strictly accepting studio audio tracks and rejecting OMV/UGC videos.
     */
    fun fetchNextRadio(videoId: String): List<Song> {
        if (!isValidVideoId(videoId)) return emptyList()
        val body = JsonObject().apply {
            put("context", webRemixContext())
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
        }
        val root = postJson(YTM_NEXT_URL, body) ?: return emptyList()
        val (panelSongs, relatedBrowseId) = parseNextRadioJson(root, excludeVideoId = videoId)

        if (panelSongs.size >= 8 || relatedBrowseId.isNullOrBlank()) {
            return panelSongs
        }

        // Pull "Related" tab shelf if RDAMVM panel was short
        val browseBody = JsonObject().apply {
            put("context", webRemixContext())
            put("browseId", relatedBrowseId)
        }
        val browseRoot = postJson(YTM_BROWSE_URL, browseBody)
        val relatedSongs = if (browseRoot != null) {
            parseSearchOrBrowseSongsJson(browseRoot).filter { it.id != videoId }
        } else emptyList()

        return (panelSongs + relatedSongs).distinctBy { it.id }
    }

    /**
     * Direct YT Music Songs search fallback using InnerTube WEB_REMIX.
     */
    fun searchSongs(query: String, limit: Int = 20): List<Song> {
        if (query.isBlank()) return emptyList()
        val body = JsonObject().apply {
            put("context", webRemixContext())
            put("query", query)
            put("params", SONGS_SEARCH_PARAMS)
        }
        val root = postJson(YTM_SEARCH_URL, body) ?: return emptyList()
        return parseSearchOrBrowseSongsJson(root).take(limit)
    }

    /**
     * Tertiary resilient player audio resolution using Android VR client via InnerTube,
     * only invoked if NewPipeExtractor VisionOS & full Watch extractors both fail.
     */
    internal fun fetchPlayerAudioFallback(videoId: String): List<FallbackAudioCandidate> {
        if (!isValidVideoId(videoId)) return emptyList()
        val client = runCatching {
            VelvetApp.http.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }.getOrNull() ?: return emptyList()

        val cpn = runCatching { YoutubeParsingHelper.generateContentPlaybackNonce() }.getOrElse { "" }
        val payload = JsonObject().apply {
            put("context", androidVrContext())
            put("videoId", videoId)
            if (cpn.isNotBlank()) put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val bodyText = JsonWriter.string().`object`(payload).done()
        val req = Request.Builder()
            .url(YT_PLAYER_URL)
            .post(bodyText.toRequestBody(JSON_MEDIA))
            .header("User-Agent", ANDROID_VR_USER_AGENT)
            .header("X-YouTube-Client-Name", "28")
            .header("X-YouTube-Client-Version", ANDROID_VR_CLIENT_VERSION)
            .build()

        val root = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val raw = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return emptyList()
                JsonParser.`object`().from(raw)
            }
        }.getOrNull() ?: return emptyList()

        val status = root.getObject("playabilityStatus").getString("status", "")
        if (!status.equals("OK", ignoreCase = true)) return emptyList()
        if (root.getObject("videoDetails").getString("videoId") != videoId) return emptyList()

        val formats = root.getObject("streamingData").getArray("adaptiveFormats") ?: return emptyList()
        val headers = mapOf("User-Agent" to ANDROID_VR_USER_AGENT)
        val out = mutableListOf<FallbackAudioCandidate>()
        for (i in 0 until formats.size) {
            val f = formats.getObject(i) ?: continue
            val mime = f.getString("mimeType", "")
            val url = f.getString("url", "")
            if (!mime.startsWith("audio/") || url.isBlank()) continue
            val audioTrack = f.getObject("audioTrack")
            if (audioTrack != null && !audioTrack.getBoolean("audioIsDefault", true)) continue
            val bitrate = maxOf(f.getInt("averageBitrate", 0), f.getInt("bitrate", 0))
            val isDrc = f.getBoolean("isDrc", false) || url.contains("drc%3D1") || url.contains("drc=1")
            out.add(
                FallbackAudioCandidate(
                    url = if (cpn.isNotBlank()) "$url&cpn=$cpn" else url,
                    kbps = bitrate / 1000,
                    opus = mime.contains("opus", ignoreCase = true),
                    m4a = mime.startsWith("audio/mp4", ignoreCase = true),
                    itag = f.getInt("itag", 0),
                    drc = isDrc,
                    headers = headers,
                ),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- pure JSON parsers (testable offline)

    internal fun parseNextRadioJson(root: JsonObject, excludeVideoId: String = ""): Pair<List<Song>, String?> {
        val watchNext = root.getObject("contents")
            .getObject("singleColumnMusicWatchNextResultsRenderer")
            .getObject("tabbedRenderer")
            .getObject("watchNextTabbedResultsRenderer")

        val tabs = watchNext.getArray("tabs")
        val panelSongs = mutableListOf<Song>()
        var relatedBrowseId: String? = null

        for (i in 0 until tabs.size) {
            val tabRenderer = tabs.getObject(i)?.getObject("tabRenderer") ?: continue
            val tabTitle = tabRenderer.getString("title", "").lowercase(Locale.ROOT)
            val browseId = tabRenderer.getObject("endpoint").getObject("browseEndpoint").getString("browseId")
            if (!browseId.isNullOrBlank() && tabTitle.contains("related")) {
                relatedBrowseId = browseId
            }
            val panelContents = tabRenderer.getObject("content")
                .getObject("musicQueueRenderer")
                .getObject("content")
                .getObject("playlistPanelRenderer")
                .getArray("contents")
            collectPlaylistPanelSongs(panelContents, excludeVideoId, panelSongs)
        }

        return panelSongs.distinctBy { it.id } to relatedBrowseId
    }

    internal fun parseSearchOrBrowseSongsJson(root: JsonObject): List<Song> {
        val songs = mutableListOf<Song>()
        val items = mutableListOf<JsonObject>()
        collectResponsiveItemsRecursive(root, items)
        for (renderer in items) {
            parseResponsiveListItem(renderer)?.let { songs.add(it) }
        }
        return songs.distinctBy { it.id }
    }

    private fun collectPlaylistPanelSongs(contents: JsonArray?, excludeVideoId: String, out: MutableList<Song>) {
        if (contents == null) return
        for (i in 0 until contents.size) {
            val wrapper = contents.getObject(i) ?: continue
            val renderer = wrapper.getObject("playlistPanelVideoRenderer") ?: continue
            parsePlaylistPanelVideo(renderer, excludeVideoId)?.let { out.add(it) }
        }
    }

    private fun parsePlaylistPanelVideo(renderer: JsonObject, excludeVideoId: String): Song? {
        val videoId = renderer.getString("videoId")?.takeIf(::isValidVideoId) ?: return null
        if (videoId == excludeVideoId) return null

        val musicVideoType = renderer.getObject("navigationEndpoint")
            .getObject("watchEndpoint")
            .getObject("watchEndpointMusicSupportedConfigs")
            .getObject("watchEndpointMusicConfig")
            .getString("musicVideoType", "")
        if (musicVideoType in REJECTED_VIDEO_TYPES) return null

        val title = extractRunsText(renderer.getObject("title").getArray("runs")) ?: return null
        val bylineRuns = renderer.getObject("longBylineText").getArray("runs")
        val (artist, artistUrl, albumTitle) = parseBylineRuns(bylineRuns)
        val durationSec = parseLengthText(extractRunsText(renderer.getObject("lengthText").getArray("runs")))
        val thumbs = renderer.getObject("thumbnail").getArray("thumbnails")
        val (thumbUrl, hasSquareArt) = parseThumbnails(thumbs, videoId)

        // Require either explicit MUSIC_VIDEO_TYPE_ATV OR verified square YT Music cover art
        val isAtv = musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
        if (!isAtv && !hasSquareArt) return null

        val song = Song(
            id = videoId,
            title = title,
            artist = artist,
            durationSec = durationSec,
            thumb = thumbUrl,
            artistUrl = artistUrl,
            preferredMusicSource = hasSquareArt || isAtv,
            album = albumTitle,
            isTopicRelease = false,
            isUploaderVerified = isAtv,
            rawUploaderName = artist,
            hasSquareArtwork = hasSquareArt,
            isYouTubeMusicCatalog = isAtv || hasSquareArt,
        )
        return if (song.isPureMusic() && song.isOriginalMusicAudio()) song else null
    }

    private fun parseResponsiveListItem(renderer: JsonObject): Song? {
        val playlistItemData = renderer.getObject("playlistItemData")
        val videoId = playlistItemData.getString("videoId")?.takeIf(::isValidVideoId)
            ?: renderer.getObject("overlay")
                .getObject("musicItemThumbnailOverlayRenderer")
                .getObject("content")
                .getObject("musicPlayButtonRenderer")
                .getObject("playNavigationEndpoint")
                .getObject("watchEndpoint")
                .getString("videoId")?.takeIf(::isValidVideoId)
            ?: return null

        val musicVideoType = renderer.getObject("overlay")
            .getObject("musicItemThumbnailOverlayRenderer")
            .getObject("content")
            .getObject("musicPlayButtonRenderer")
            .getObject("playNavigationEndpoint")
            .getObject("watchEndpoint")
            .getObject("watchEndpointMusicSupportedConfigs")
            .getObject("watchEndpointMusicConfig")
            .getString("musicVideoType", "")
        if (musicVideoType in REJECTED_VIDEO_TYPES) return null

        val flexColumns = renderer.getArray("flexColumns") ?: return null
        if (flexColumns.size < 2) return null

        val titleRuns = flexColumns.getObject(0)
            ?.getObject("musicResponsiveListItemFlexColumnRenderer")
            ?.getObject("text")
            ?.getArray("runs")
        val title = extractRunsText(titleRuns) ?: return null

        val subRuns = flexColumns.getObject(1)
            ?.getObject("musicResponsiveListItemFlexColumnRenderer")
            ?.getObject("text")
            ?.getArray("runs")
        val (artist, artistUrl, parsedAlbum, parsedDuration) = parseResponsiveSubRuns(subRuns)

        val thumbs = renderer.getObject("thumbnail")
            .getObject("musicThumbnailRenderer")
            .getObject("thumbnail")
            .getArray("thumbnails")
        val (thumbUrl, hasSquareArt) = parseThumbnails(thumbs, videoId)

        val isAtv = musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
        if (!isAtv && !hasSquareArt) return null

        val song = Song(
            id = videoId,
            title = title,
            artist = artist,
            durationSec = parsedDuration,
            thumb = thumbUrl,
            artistUrl = artistUrl,
            preferredMusicSource = hasSquareArt || isAtv,
            album = parsedAlbum,
            isTopicRelease = false,
            isUploaderVerified = isAtv,
            rawUploaderName = artist,
            hasSquareArtwork = hasSquareArt,
            isYouTubeMusicCatalog = isAtv || hasSquareArt,
        )
        return if (song.isPureMusic() && song.isOriginalMusicAudio()) song else null
    }

    private fun collectResponsiveItemsRecursive(node: Any?, out: MutableList<JsonObject>) {
        when (node) {
            is JsonObject -> {
                if (node.has("musicResponsiveListItemRenderer")) {
                    out.add(node.getObject("musicResponsiveListItemRenderer"))
                } else {
                    for (v in node.values) collectResponsiveItemsRecursive(v, out)
                }
            }
            is JsonArray -> {
                for (v in node) collectResponsiveItemsRecursive(v, out)
            }
        }
    }

    private fun extractRunsText(runs: JsonArray?): String? {
        if (runs == null || runs.size == 0) return null
        val sb = StringBuilder()
        for (i in 0 until runs.size) {
            sb.append(runs.getObject(i)?.getString("text", "").orEmpty())
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun parseBylineRuns(runs: JsonArray?): Triple<String, String?, String?> {
        if (runs == null || runs.size == 0) return Triple("Unknown Artist", null, null)
        val artists = mutableListOf<String>()
        var artistUrl: String? = null
        var album: String? = null
        var sectionIdx = 0

        for (i in 0 until runs.size) {
            val run = runs.getObject(i) ?: continue
            val text = run.getString("text", "")
            val trimmed = text.trim()
            if (trimmed == "•" || trimmed == "·") {
                sectionIdx++
                continue
            }
            if (sectionIdx == 0) {
                if (trimmed.isNotBlank() && trimmed != "," && trimmed != "&") {
                    artists.add(trimmed)
                    if (artistUrl == null) {
                        val browseId = run.getObject("navigationEndpoint").getObject("browseEndpoint").getString("browseId")
                        if (!browseId.isNullOrBlank()) {
                            artistUrl = "$YTM_ORIGIN/channel/$browseId"
                        }
                    }
                }
            } else if (sectionIdx == 1) {
                if (trimmed.isNotBlank() && !trimmed.contains("views", ignoreCase = true) && !trimmed.contains("plays", ignoreCase = true)) {
                    album = trimmed
                }
            }
        }
        val joinedArtists = artists.joinToString(", ").ifBlank {
            runs.getObject(0)?.getString("text", "").orEmpty().ifBlank { "Unknown Artist" }
        }
        return Triple(joinedArtists, artistUrl, album)
    }

    private data class SubMeta(val artist: String, val artistUrl: String?, val album: String?, val durationSec: Long)

    private fun parseResponsiveSubRuns(runs: JsonArray?): SubMeta {
        if (runs == null || runs.size == 0) return SubMeta("Unknown Artist", null, null, -1L)
        val rawSegments = mutableListOf<MutableList<JsonObject>>()
        var current = mutableListOf<JsonObject>()
        for (i in 0 until runs.size) {
            val run = runs.getObject(i) ?: continue
            val text = run.getString("text", "").trim()
            if (text == "•" || text == "·") {
                if (current.isNotEmpty()) rawSegments.add(current)
                current = mutableListOf()
            } else {
                current.add(run)
            }
        }
        if (current.isNotEmpty()) rawSegments.add(current)

        val nonTypeSegments = rawSegments.filter { seg ->
            val t = seg.joinToString("") { it.getString("text", "") }.trim().lowercase(Locale.ROOT)
            t !in setOf("song", "video", "single", "ep", "album")
        }

        val artistSeg = nonTypeSegments.getOrNull(0)
        val artist = artistSeg?.joinToString("") { it.getString("text", "") }?.trim().orEmpty().ifBlank { "Unknown Artist" }
        val artistBrowseId = artistSeg?.firstNotNullOfOrNull { run ->
            run.getObject("navigationEndpoint").getObject("browseEndpoint").getString("browseId")?.takeIf { it.startsWith("UC") }
        }
        val artistUrl = artistBrowseId?.let { "$YTM_ORIGIN/channel/$it" }

        var durationSec = -1L
        var album: String? = null
        for (idx in 1 until nonTypeSegments.size) {
            val text = nonTypeSegments[idx].joinToString("") { it.getString("text", "") }.trim()
            val parsedDur = parseLengthText(text)
            if (parsedDur > 0L) {
                durationSec = parsedDur
            } else if (album == null && !text.contains("plays", ignoreCase = true) && !text.contains("views", ignoreCase = true)) {
                album = text
            }
        }
        return SubMeta(artist, artistUrl, album, durationSec)
    }

    private fun parseLengthText(raw: String?): Long {
        if (raw.isNullOrBlank()) return -1L
        val parts = raw.trim().split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> -1L
        }
    }

    private fun parseThumbnails(thumbs: JsonArray?, videoId: String): Pair<String, Boolean> {
        var bestUrl: String? = null
        var bestHeight = -1
        var hasSquare = false
        if (thumbs != null) {
            for (i in 0 until thumbs.size) {
                val obj = thumbs.getObject(i) ?: continue
                val url = obj.getString("url", "")
                if (url.isBlank()) continue
                val w = obj.getInt("width", 0)
                val h = obj.getInt("height", 0)
                if ((w > 0 && h > 0 && w == h) || url.contains("lh3.googleusercontent.com", ignoreCase = true)) {
                    hasSquare = true
                }
                if (h >= bestHeight) {
                    bestHeight = h
                    bestUrl = url
                }
            }
        }
        val upscaled = bestUrl?.let {
            it.replace(Regex("""=w\d+-h\d+[^/]*$"""), "=w544-h544-l90-rj")
                .replace(Regex("""=s\d+[^/]*$"""), "=s544")
        } ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return upscaled to hasSquare
    }
}
