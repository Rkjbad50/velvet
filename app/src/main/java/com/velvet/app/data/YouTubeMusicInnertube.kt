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
import okhttp3.OkHttpClient

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

    private fun webRemixContext(
        glOverride: String? = null,
        hlOverride: String? = null,
    ): JsonObject {
        val locale = Locale.getDefault()
        val hl = hlOverride?.takeIf { it.isNotBlank() } ?: locale.language.ifBlank { "en" }
        val gl = glOverride?.takeIf { it.isNotBlank() } ?: locale.country.ifBlank { "US" }
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

    internal fun countryForLanguages(languages: List<String>): String {
        val norm = languages.map { it.trim().lowercase(Locale.ROOT) }
        val indianLangs = setOf(
            "hindi", "punjabi", "bhojpuri", "haryanvi", "bengali", "bangla",
            "tamil", "telugu", "malayalam", "marathi", "gujarati", "kannada", "sanskrit", "urdu",
        )
        if (norm.any { it in indianLangs }) return "IN"
        return Locale.getDefault().country.ifBlank { "US" }
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

    @Volatile
    private var cachedFastHttp: OkHttpClient? = null

    @Volatile
    private var cachedFastBase: OkHttpClient? = null

    private fun fastHttpClient(): OkHttpClient? {
        val base = runCatching { VelvetApp.http }.getOrNull() ?: return null
        val cached = cachedFastHttp
        if (cached != null && cachedFastBase === base) return cached
        val built = base.newBuilder()
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .writeTimeout(3000, TimeUnit.MILLISECONDS)
            .build()
        cachedFastBase = base
        cachedFastHttp = built
        return built
    }

    private fun postJson(url: String, payload: JsonObject, userAgent: String = YtDownloader.USER_AGENT): JsonObject? {
        val client = fastHttpClient() ?: return null

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

    data class NextRadioPage(
        val songs: List<Song>,
        val continuationToken: String? = null,
        val relatedBrowseId: String? = null,
    )

    data class NextRadioFetchStats(
        val videoId: String,
        val pagesFetched: Int,
        val hasNext: Boolean,
        val lastParsedCount: Int,
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    private val nextFetchStats = java.util.concurrent.ConcurrentHashMap<String, NextRadioFetchStats>()

    fun peekNextStats(videoId: String): NextRadioFetchStats? = nextFetchStats[videoId]

    fun clearNextStatsForTest() {
        nextFetchStats.clear()
    }

    /**
     * Fetches YouTube Music's genuine Up-Next Song Radio ("RDAMVM<videoId>") with continuation
     * paging (C4) and Related browse fallback, strictly accepting studio audio tracks only.
     */
    fun fetchNextRadio(videoId: String): List<Song> = fetchNextRadioPage(videoId).songs

    fun fetchNextRadioPage(
        videoId: String,
        continuationToken: String? = null,
    ): NextRadioPage {
        if (!isValidVideoId(videoId)) return NextRadioPage(emptyList())
        if (!continuationToken.isNullOrBlank()) {
            val contBody = JsonObject().apply {
                put("context", webRemixContext())
                put("continuation", continuationToken)
                put("isAudioOnly", true)
            }
            val contRoot = postJson(YTM_NEXT_URL, contBody) ?: return NextRadioPage(emptyList())
            val page = parseContinuationRadioPageJson(contRoot, excludeVideoId = videoId)
            val prevPages = nextFetchStats[videoId]?.pagesFetched ?: 1
            nextFetchStats[videoId] = NextRadioFetchStats(
                videoId = videoId,
                pagesFetched = prevPages + 1,
                hasNext = !page.continuationToken.isNullOrBlank(),
                lastParsedCount = page.songs.size,
            )
            com.velvet.app.diagnostics.DiagnosticsLogger.i(
                "InnertubeNext",
                "Continuation rootKeys=${contRoot.keys.joinToString(",")} songs=${page.songs.size} hasNext=${!page.continuationToken.isNullOrBlank()}",
            )
            return page
        }

        val body = JsonObject().apply {
            put("context", webRemixContext())
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
        }
        val root = postJson(YTM_NEXT_URL, body) ?: return NextRadioPage(emptyList())
        val firstPage = parseNextRadioPageJson(root, excludeVideoId = videoId)
        nextFetchStats[videoId] = NextRadioFetchStats(
            videoId = videoId,
            pagesFetched = 1,
            hasNext = !firstPage.continuationToken.isNullOrBlank(),
            lastParsedCount = firstPage.songs.size,
        )
        com.velvet.app.diagnostics.DiagnosticsLogger.i(
            "InnertubeNext",
            "Initial rootKeys=${root.keys.joinToString(",")} songs=${firstPage.songs.size} hasNext=${!firstPage.continuationToken.isNullOrBlank()}",
        )

        if (firstPage.songs.size >= 8 || firstPage.relatedBrowseId.isNullOrBlank()) {
            return firstPage
        }

        // Pull "Related" tab shelf if RDAMVM panel was short
        val browseBody = JsonObject().apply {
            put("context", webRemixContext())
            put("browseId", firstPage.relatedBrowseId)
        }
        val browseRoot = postJson(YTM_BROWSE_URL, browseBody)
        val relatedSongs = if (browseRoot != null) {
            parseSearchOrBrowseSongsJson(browseRoot).filter { it.id != videoId }
        } else emptyList()

        return firstPage.copy(songs = (firstPage.songs + relatedSongs).distinctBy { it.id })
    }

    /**
     * Direct YT Music Songs search with explicit gl/hl localization support.
     */
    fun searchSongs(
        query: String,
        limit: Int = 20,
        glOverride: String? = null,
        hlOverride: String? = null,
    ): List<Song> {
        if (query.isBlank()) return emptyList()
        val body = JsonObject().apply {
            put("context", webRemixContext(glOverride = glOverride, hlOverride = hlOverride))
            put("query", query)
            put("params", SONGS_SEARCH_PARAMS)
        }
        val root = postJson(YTM_SEARCH_URL, body) ?: return emptyList()
        return parseSearchOrBrowseSongsJson(root).take(limit)
    }

    /**
     * Direct YT Music Artist search with explicit gl/hl localization support.
     */
    fun searchArtists(
        query: String,
        limit: Int = 20,
        glOverride: String? = null,
        hlOverride: String? = null,
    ): List<ArtistRef> {
        if (query.isBlank()) return emptyList()
        val body = JsonObject().apply {
            put("context", webRemixContext(glOverride = glOverride, hlOverride = hlOverride))
            put("query", query)
        }
        val root = postJson(YTM_SEARCH_URL, body) ?: return emptyList()
        return parseArtistsSearchJson(root)
            .sortedByDescending { artistMatchScore(query, it.name) }
            .take(limit)
    }

    /**
     * Fetches genuine live YouTube Music Trending / Top Charts (`FEmusic_charts` + `FEmusic_explore`
     * + top regional/global chart `VL` playlists), strictly returning verified studio tracks only.
     */
    fun fetchTrendingCharts(
        preferredLanguages: List<String> = emptyList(),
        limit: Int = 30,
    ): List<Song> {
        val state = LanguageLocaleConfig.fromSelectedLanguages(preferredLanguages)
        val gl = state.gl
        val hl = state.hl
        val ctx = webRemixContext(glOverride = gl, hlOverride = hl)

        val chartsRoot = postJson(
            YTM_BROWSE_URL,
            JsonObject().apply {
                put("context", ctx)
                put("browseId", "FEmusic_charts")
            },
        )
        val collected = mutableListOf<Song>()
        if (chartsRoot != null) {
            collected += parseSearchOrBrowseSongsJson(chartsRoot)
            if (collected.size < limit) {
                val playlistIds = extractChartPlaylistBrowseIds(chartsRoot, preferredLanguages)
                for (browseId in playlistIds.take(2)) {
                    if (collected.size >= limit + 10) break
                    collected += browsePlaylistSongs(browseId, glOverride = gl, limit = limit)
                }
            }
        }

        if (collected.size < limit) {
            val exploreRoot = postJson(
                YTM_BROWSE_URL,
                JsonObject().apply {
                    put("context", ctx)
                    put("browseId", "FEmusic_explore")
                },
            )
            if (exploreRoot != null) {
                collected += parseSearchOrBrowseSongsJson(exploreRoot)
                if (collected.size < limit) {
                    val exploreIds = extractChartPlaylistBrowseIds(exploreRoot, preferredLanguages)
                    exploreIds.firstOrNull()?.let { browseId ->
                        collected += browsePlaylistSongs(browseId, glOverride = gl, limit = limit)
                    }
                }
            }
        }

        return collected.distinctBy { it.id }.take(limit)
    }

    fun browsePlaylistSongs(
        browseId: String,
        glOverride: String? = null,
        hlOverride: String? = null,
        limit: Int = 30,
    ): List<Song> {
        if (browseId.isBlank()) return emptyList()
        val normalizedId = when {
            browseId.startsWith("VL") || browseId.startsWith("FE") || browseId.startsWith("MP") -> browseId
            else -> "VL$browseId"
        }
        val body = JsonObject().apply {
            put("context", webRemixContext(glOverride = glOverride, hlOverride = hlOverride))
            put("browseId", normalizedId)
        }
        val root = postJson(YTM_BROWSE_URL, body) ?: return emptyList()
        return parseSearchOrBrowseSongsJson(root).take(limit)
    }

    /**
     * Extracts a YouTube / YouTube Music playlist or album browseId from a URL or raw ID and fetches
     * track-level metadata (including per-track square artwork when present in InnerTube browse).
     */
    fun browseCollectionTracks(
        urlOrBrowseId: String,
        glOverride: String? = null,
        hlOverride: String? = null,
        limit: Int = 100,
    ): List<Song> {
        val browseId = extractBrowseId(urlOrBrowseId) ?: return emptyList()
        return browsePlaylistSongs(browseId, glOverride = glOverride, hlOverride = hlOverride, limit = limit)
    }

    /**
     * Fetches the individual track's official square cover art and metadata from InnerTube `next`
     * for [videoId], used when an album/compilation playlist returned only parent album artwork.
     */
    fun fetchTrackArtworkUrl(
        videoId: String,
        glOverride: String? = null,
        hlOverride: String? = null,
    ): String? {
        if (!isValidVideoId(videoId)) return null
        val body = JsonObject().apply {
            put("context", webRemixContext(glOverride = glOverride, hlOverride = hlOverride))
            put("videoId", videoId)
            put("isAudioOnly", true)
        }
        val root = postJson(YTM_NEXT_URL, body) ?: return null
        return parseSelectedTrackArtworkJson(root, videoId)
    }

    internal fun extractBrowseId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.isBlank()) return null
        val listParam = Regex("""[?&]list=([^&#]+)""").find(trimmed)?.groupValues?.getOrNull(1)
        if (!listParam.isNullOrBlank()) {
            return if (listParam.startsWith("VL") || listParam.startsWith("MP")) listParam else "VL$listParam"
        }
        val browseMatch = Regex("""/browse/([^/?#]+)""").find(trimmed)?.groupValues?.getOrNull(1)
        if (!browseMatch.isNullOrBlank()) return browseMatch
        if (!trimmed.contains('/')) {
            return when {
                trimmed.startsWith("VL") || trimmed.startsWith("FE") || trimmed.startsWith("MP") -> trimmed
                trimmed.startsWith("OL") || trimmed.startsWith("PL") || trimmed.startsWith("RD") -> "VL$trimmed"
                else -> trimmed
            }
        }
        return null
    }

    internal fun parseSelectedTrackArtworkJson(root: JsonObject, targetVideoId: String): String? {
        val panelRenderers = mutableListOf<JsonObject>()
        collectRenderersRecursive(root, "playlistPanelVideoRenderer", panelRenderers)
        for (renderer in panelRenderers) {
            if (renderer.getString("videoId") != targetVideoId) continue
            val thumbs = renderer.getObject("thumbnail").getArray("thumbnails")
            val (url, hasSquare) = parseThumbnails(thumbs, targetVideoId)
            if (url.isNotBlank() && (hasSquare || !url.contains("i.ytimg.com/vi/"))) {
                return url
            }
        }
        return null
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
        val page = parseNextRadioPageJson(root, excludeVideoId)
        return page.songs to page.relatedBrowseId
    }

    internal fun parseNextRadioPageJson(root: JsonObject, excludeVideoId: String = ""): NextRadioPage {
        val watchNext = root.getObject("contents")
            .getObject("singleColumnMusicWatchNextResultsRenderer")
            .getObject("tabbedRenderer")
            .getObject("watchNextTabbedResultsRenderer")

        val tabs = watchNext.getArray("tabs")
        val panelSongs = mutableListOf<Song>()
        var relatedBrowseId: String? = null
        var continuationToken: String? = null

        for (i in 0 until tabs.size) {
            val tabRenderer = tabs.getObject(i)?.getObject("tabRenderer") ?: continue
            val tabTitle = tabRenderer.getString("title", "").lowercase(Locale.ROOT)
            val browseId = tabRenderer.getObject("endpoint").getObject("browseEndpoint").getString("browseId")
            if (!browseId.isNullOrBlank() && tabTitle.contains("related")) {
                relatedBrowseId = browseId
            }
            val panel = tabRenderer.getObject("content")
                .getObject("musicQueueRenderer")
                .getObject("content")
                .getObject("playlistPanelRenderer")
            collectPlaylistPanelSongs(panel.getArray("contents"), excludeVideoId, panelSongs)
            if (continuationToken == null) {
                continuationToken = extractPanelContinuationToken(panel)
            }
        }

        return NextRadioPage(
            songs = panelSongs.distinctBy { it.id },
            continuationToken = continuationToken,
            relatedBrowseId = relatedBrowseId,
        )
    }

    internal fun parseContinuationRadioPageJson(root: JsonObject, excludeVideoId: String = ""): NextRadioPage {
        val panel = root.getObject("continuationContents")
            .getObject("playlistPanelContinuation")
        val panelSongs = mutableListOf<Song>()
        collectPlaylistPanelSongs(panel.getArray("contents"), excludeVideoId, panelSongs)
        val nextToken = extractPanelContinuationToken(panel)
        return NextRadioPage(
            songs = panelSongs.distinctBy { it.id },
            continuationToken = nextToken,
            relatedBrowseId = null,
        )
    }

    private fun extractPanelContinuationToken(panelObj: JsonObject?): String? {
        val conts = panelObj?.getArray("continuations") ?: return null
        for (i in 0 until conts.size) {
            val entry = conts.getObject(i) ?: continue
            val token = entry.getObject("nextRadioContinuationData").getString("continuation")
                ?.takeIf { it.isNotBlank() }
                ?: entry.getObject("nextContinuationData").getString("continuation")
                    ?.takeIf { it.isNotBlank() }
            if (token != null) return token
        }
        return null
    }

    internal fun parseSearchOrBrowseSongsJson(root: JsonObject): List<Song> {
        val songs = mutableListOf<Song>()
        val responsiveItems = mutableListOf<JsonObject>()
        collectRenderersRecursive(root, "musicResponsiveListItemRenderer", responsiveItems)
        for (renderer in responsiveItems) {
            parseResponsiveListItem(renderer)?.let { songs.add(it) }
        }
        val twoRowItems = mutableListOf<JsonObject>()
        collectRenderersRecursive(root, "musicTwoRowItemRenderer", twoRowItems)
        for (renderer in twoRowItems) {
            parseTwoRowItemSong(renderer)?.let { songs.add(it) }
        }
        val panelItems = mutableListOf<JsonObject>()
        collectRenderersRecursive(root, "playlistPanelVideoRenderer", panelItems)
        for (renderer in panelItems) {
            parsePlaylistPanelVideo(renderer, excludeVideoId = "")?.let { songs.add(it) }
        }
        return songs.distinctBy { it.id }
    }

    internal fun extractChartPlaylistBrowseIds(
        root: JsonObject,
        preferredLanguages: List<String> = emptyList(),
    ): List<String> {
        val langHints = preferredLanguages.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }
        val indianSelected = langHints.any {
            it in setOf("hindi", "punjabi", "bhojpuri", "haryanvi", "bengali", "tamil", "telugu", "malayalam", "marathi", "gujarati", "kannada")
        }
        val candidates = mutableListOf<Pair<String, Int>>()
        val twoRowItems = mutableListOf<JsonObject>()
        collectRenderersRecursive(root, "musicTwoRowItemRenderer", twoRowItems)
        for (renderer in twoRowItems) {
            val browseId = renderer.getObject("navigationEndpoint")
                .getObject("browseEndpoint")
                .getString("browseId")
                ?.takeIf { it.startsWith("VL") || it.startsWith("RDCLAK") }
                ?: continue
            val title = extractRunsText(renderer.getObject("title").getArray("runs"))?.lowercase(Locale.ROOT).orEmpty()
            var score = 0
            if (langHints.any { title.contains(it) }) score += 5
            if (indianSelected && title.contains("india")) score += 4
            if (title.contains("top songs") || title.contains("top 100") || title.contains("trending") || title.contains("chart")) score += 3
            if (title.contains("viral") || title.contains("daily") || title.contains("hits")) score += 1
            if (title.contains("music videos") || title.endsWith("videos")) score -= 4
            candidates.add(browseId to score)
        }
        return candidates
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
    }

    internal fun artistNamesMatch(expected: String, actual: String): Boolean {
        return artistMatchScore(expected, actual) >= 60
    }

    internal fun artistMatchScore(query: String, candidate: String): Int {
        fun clean(s: String): String = s.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val q = clean(query)
        val c = clean(candidate)
        if (q.isBlank() || c.isBlank()) return 0
        if (q == c) return 100
        val qCompact = q.replace(" ", "")
        val cCompact = c.replace(" ", "")
        if (qCompact == cCompact) return 96
        val qTokens = q.split(' ').filter { it.isNotBlank() }
        val cTokens = c.split(' ').filter { it.isNotBlank() }
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0
        if (qTokens.all { qt -> cTokens.any { it == qt } }) return 85
        val overlap = qTokens.count { qt -> cTokens.any { it == qt } }
        val maxTokens = maxOf(qTokens.size, cTokens.size)
        return if (overlap > 0 && overlap * 2 >= maxTokens) 65 + overlap * 5 else 0
    }

    internal fun parseArtistsSearchJson(root: JsonObject): List<ArtistRef> {
        val artists = mutableListOf<ArtistRef>()
        // Walk shelf renderers first to exclude "Songs", "Videos", "Albums", "Playlists" shelves cleanly
        val shelves = mutableListOf<JsonObject>()
        collectRenderersRecursive(root, "musicShelfRenderer", shelves)
        val candidateRenderers = mutableListOf<JsonObject>()
        if (shelves.isNotEmpty()) {
            for (shelf in shelves) {
                val shelfTitle = extractRunsText(
                    shelf.getObject("title").getArray("runs"),
                )?.lowercase(Locale.ROOT).orEmpty()
                if (shelfTitle.isNotBlank() &&
                    !shelfTitle.contains("artist") &&
                    !shelfTitle.contains("top result")
                ) {
                    continue
                }
                collectRenderersRecursive(shelf, "musicResponsiveListItemRenderer", candidateRenderers)
            }
        }
        if (candidateRenderers.isEmpty()) {
            collectRenderersRecursive(root, "musicResponsiveListItemRenderer", candidateRenderers)
        }

        for (renderer in candidateRenderers) {
            val navBrowse = renderer.getObject("navigationEndpoint").getObject("browseEndpoint")
            val browseId = navBrowse.getString("browseId")?.takeIf { it.startsWith("UC") } ?: continue
            val pageType = navBrowse.getObject("browseEndpointContextSupportedConfigs")
                .getObject("browseEndpointContextMusicConfig")
                .getString("pageType", "")
            val flexColumns = renderer.getArray("flexColumns") ?: continue
            val nameRuns = flexColumns.getObject(0)
                ?.getObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getObject("text")
                ?.getArray("runs")
            val name = extractRunsText(nameRuns) ?: continue
            val subRuns = flexColumns.getObject(1)
                ?.getObject("musicResponsiveListItemFlexColumnRenderer")
                ?.getObject("text")
                ?.getArray("runs")
            val subText = extractRunsText(subRuns).orEmpty()
            val subLower = subText.lowercase(Locale.ROOT)
            // Strictly require explicit MUSIC_PAGE_TYPE_ARTIST OR explicit "Artist" subtitle label (never song/video/album)
            val isExplicitArtistPage = pageType == "MUSIC_PAGE_TYPE_ARTIST"
            val isArtistLabelOnly = pageType.isBlank() &&
                subLower.contains("artist") &&
                !subLower.contains("song") &&
                !subLower.contains("video") &&
                !subLower.contains("album") &&
                !subLower.contains("single")
            if (!isExplicitArtistPage && !isArtistLabelOnly) continue

            val thumbs = renderer.getObject("thumbnail")
                .getObject("musicThumbnailRenderer")
                .getObject("thumbnail")
                .getArray("thumbnails")
            val (thumbUrl, _) = parseThumbnails(thumbs, browseId)
            artists.add(
                ArtistRef(
                    url = "$YTM_ORIGIN/channel/$browseId",
                    name = name,
                    thumb = thumbUrl.takeIf { !it.contains("ytimg.com") },
                ),
            )
        }
        return artists.distinctBy { it.url }
    }

    private fun parseTwoRowItemSong(renderer: JsonObject): Song? {
        val navWatch = renderer.getObject("navigationEndpoint").getObject("watchEndpoint")
        val overlayWatch = renderer.getObject("thumbnailOverlay")
            .getObject("musicItemThumbnailOverlayRenderer")
            .getObject("content")
            .getObject("musicPlayButtonRenderer")
            .getObject("playNavigationEndpoint")
            .getObject("watchEndpoint")
        val watchEndpoint = if (navWatch.has("videoId")) navWatch else overlayWatch
        val videoId = watchEndpoint.getString("videoId")?.takeIf(::isValidVideoId) ?: return null

        val musicVideoType = watchEndpoint.getObject("watchEndpointMusicSupportedConfigs")
            .getObject("watchEndpointMusicConfig")
            .getString("musicVideoType", "")
        if (musicVideoType in REJECTED_VIDEO_TYPES) return null

        val title = extractRunsText(renderer.getObject("title").getArray("runs")) ?: return null
        val subRuns = renderer.getObject("subtitle").getArray("runs")
        val (artist, artistUrl, parsedAlbum, parsedDuration) = parseResponsiveSubRuns(subRuns)

        val thumbs = renderer.getObject("thumbnailRenderer")
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
            isTopicRelease = isAtv,
            isUploaderVerified = isAtv,
            rawUploaderName = artist,
            hasSquareArtwork = hasSquareArt || isAtv,
            isYouTubeMusicCatalog = isAtv || hasSquareArt,
        )
        return if (song.isPureMusic() && song.isOriginalMusicAudio()) song else null
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
            isTopicRelease = isAtv,
            isUploaderVerified = isAtv,
            rawUploaderName = artist,
            hasSquareArtwork = hasSquareArt || isAtv,
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
            isTopicRelease = isAtv,
            isUploaderVerified = isAtv,
            rawUploaderName = artist,
            hasSquareArtwork = hasSquareArt || isAtv,
            isYouTubeMusicCatalog = isAtv || hasSquareArt,
        )
        return if (song.isPureMusic() && song.isOriginalMusicAudio()) song else null
    }

    private fun collectRenderersRecursive(node: Any?, targetKey: String, out: MutableList<JsonObject>) {
        when (node) {
            is JsonObject -> {
                if (node.has(targetKey)) {
                    out.add(node.getObject(targetKey))
                }
                for (v in node.values) {
                    collectRenderersRecursive(v, targetKey, out)
                }
            }
            is JsonArray -> {
                for (v in node) collectRenderersRecursive(v, targetKey, out)
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
