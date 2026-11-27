package com.velvet.app.unit

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.playback.CoverArtCache
import com.velvet.app.playback.CoverArtResolver
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.reconcileSongWithMediaArtwork
import com.velvet.app.playback.resolveMediaItemArtworkUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 3 B3 tests driven by real redacted YT Music fixtures:
 * 1) Real compilation album ("90s Evergreen Romantic Songs With Jhankar Beats"): strict song match upgrades distinct soundtrack artwork while never matching wrong tracks/variants.
 * 2) Real single-artist album ("Rockstar"): retains official album art over generic ytimg video frames.
 * 3) Real mixed playlist & Search genre hub playlist: distinct per-track art preserved in rows, queue, and Media3 metadata.
 * 4) Non-blocking progressive resolution across a 100-track album (no 24-track cap; visible + 10 ahead prioritized first).
 * 5) Persistent bounded CoverArtCache hit path, capacity eviction, & disk reload across restarts.
 * 6) Clean coroutine cancellation when user leaves the screen.
 */
class B3CoverArtResolutionTest {

    private lateinit var tempDir: java.io.File

    private fun loadFixture(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/cover_art/$name")
            ?: error("Missing fixture: $name")
        return stream.bufferedReader(Charsets.UTF_8).use {
            JsonParser.`object`().from(it.readText())
        }
    }

    @Before
    fun setUp() {
        CoverArtCache.clearForTest()
        tempDir = Files.createTempDirectory("velvet_cover_cache_test").toFile()
        CoverArtCache.attachDirectory(tempDir)
    }

    @After
    fun tearDown() {
        CoverArtCache.clearForTest()
        tempDir.deleteRecursively()
    }

    @Test
    fun compilationFixture_resolvesExactSoundtrackCoversWithoutFalseMatches_andPropagatesToQueueAndMedia3() = runBlocking {
        val fix = loadFixture("compilation_album_90s_jhankar.json")
        val headerThumb = fix.getString("headerThumb")
        val tracksJson = fix.getArray("tracks")
        val tracksById = linkedMapOf<String, JsonObject>()
        val rawSongs = (0 until tracksJson.size).map { idx ->
            val obj = tracksJson.getObject(idx)!!
            val vid = obj.getString("videoId")!!
            tracksById[vid] = obj
            Song(
                id = vid,
                title = obj.getString("title")!!,
                artist = obj.getString("artist")!!,
                album = "90s Evergreen Romantic Songs (With Jhankar Beats)",
                thumb = headerThumb,
            )
        }
        val album = AlbumRef(
            url = "https://music.youtube.com/browse/${fix.getString("browseId")}",
            title = "90s Evergreen Romantic Songs (With Jhankar Beats)",
            subtitle = "Various Artists",
            thumb = headerThumb,
            isAlbum = true,
        )

        var latestSongs = rawSongs
        val resolvedEvents = mutableListOf<Song>()
        CoverArtResolver.resolveAlbumArtworkProgressively(
            collectionUrl = album.url,
            album = album,
            initialSongs = rawSongs,
            visibleWindowProvider = { 0..8 },
            ioDispatcher = Dispatchers.Unconfined,
            browseProvider = { emptyList() },
            nextArtworkProvider = { s -> tracksById[s.id]?.getString("nextThumb") },
            songSearchArtworkProvider = { s ->
                val obj = tracksById[s.id] ?: return@resolveAlbumArtworkProgressively null
                val candTitle = obj.getString("songSearchMatchTitle") ?: return@resolveAlbumArtworkProgressively null
                val candThumb = obj.getString("songSearchThumb") ?: return@resolveAlbumArtworkProgressively null
                if (CoverArtResolver.isExactStudioSongMatch(s.title, s.artist, candTitle, s.artist)) {
                    candThumb.replace("=w120-h120-l90-rj", "=w544-h544-l90-rj")
                } else {
                    null
                }
            },
            onSongsUpdated = { latestSongs = it },
            onTrackResolved = { resolvedEvents += it },
        )

        assertTrue("Expected distinct track resolution events", resolvedEvents.isNotEmpty())

        // Track #1 ("Main Tujhse Aise Milun") matched strictly -> Judaai OST cover (not compilation cover)
        val first = latestSongs.first { it.id == "DkE-T4AICdw" }
        assertNotEquals(headerThumb, first.thumb)
        assertTrue(first.thumb!!.contains("yt3.googleusercontent.com"))

        // Track #3 ("Tu Cheez Badi Hain Mast Mast" vs typo "Too Cheez Badi Hain") strictly rejected -> stays on safe compilation cover
        val third = latestSongs.first { it.id == "rEc5DzG_H3g" }
        assertEquals(headerThumb, third.thumb)

        // Distinct resolved artwork stays consistent in QueueManager & Media3 resolution
        val qm = QueueManager()
        qm.start(latestSongs, startIndex = 0, shuffled = false)
        assertEquals(first.thumb, qm.current?.thumb)
        val second = latestSongs[1] // Kay Sera Sera
        val mediaThumb = resolveMediaItemArtworkUrl(second, existingCached = null, fallbackAlbumArt = headerThumb)
        assertEquals(second.thumb, mediaThumb)

        // Reconciling from MediaItem preserves resolved track art
        val reconciled = reconcileSongWithMediaArtwork(
            mediaId = first.id,
            cached = null,
            mediaTitle = first.title,
            mediaArtist = first.artist,
            mediaAlbum = first.album,
            mediaDurationSec = 322L,
            mediaThumb = mediaThumb,
            mediaArtistUrl = null,
        )
        assertNotEquals("https://i.ytimg.com/vi/${first.id}/hqdefault.jpg", reconciled.thumb)
    }

    @Test
    fun singleArtistAlbumFixture_keepsOfficialAlbumArtworkInsteadOfGenericVideoThumb() = runBlocking {
        val fix = loadFixture("single_artist_album_rockstar.json")
        val headerThumb = fix.getString("headerThumb")
        val tracksJson = fix.getArray("tracks")
        val byId = linkedMapOf<String, JsonObject>()
        val rawSongs = (0 until tracksJson.size).map { idx ->
            val obj = tracksJson.getObject(idx)!!
            val vid = obj.getString("videoId")!!
            byId[vid] = obj
            Song(id = vid, title = obj.getString("title")!!, artist = obj.getString("artist")!!, album = "Rockstar", thumb = headerThumb)
        }
        val album = AlbumRef(
            url = "https://music.youtube.com/browse/${fix.getString("browseId")}",
            title = "Rockstar",
            subtitle = "A. R. Rahman",
            thumb = headerThumb,
            isAlbum = true,
        )

        var latestSongs = rawSongs
        CoverArtResolver.resolveAlbumArtworkProgressively(
            collectionUrl = album.url,
            album = album,
            initialSongs = rawSongs,
            ioDispatcher = Dispatchers.Unconfined,
            browseProvider = { emptyList() },
            nextArtworkProvider = { s -> byId[s.id]?.getString("nextThumb") },
            songSearchArtworkProvider = { null },
            onSongsUpdated = { latestSongs = it },
        )

        // Album cover wins cleanly over generic i.ytimg.com video thumbnails (`track art > album art > video thumb`)
        assertTrue(latestSongs.all { it.thumb == headerThumb })
    }

    @Test
    fun mixedPlaylistAndGenreHubFixtures_preserveDistinctPerTrackArtworkAcrossBrowseAndQueue() = runBlocking {
        for (fixtureName in listOf("mixed_playlist_90s_bollywood.json", "genre_page_album_romance.json")) {
            val fix = loadFixture(fixtureName)
            val headerThumb = fix.getString("headerThumb")
            val tracksJson = fix.getArray("tracks")
            val initialSongs = (0 until tracksJson.size).map { idx ->
                val obj = tracksJson.getObject(idx)!!
                Song(
                    id = obj.getString("videoId")!!,
                    title = obj.getString("title")!!,
                    artist = obj.getString("artist")!!,
                    thumb = obj.getString("browseThumb"),
                )
            }
            val album = AlbumRef(
                url = "https://music.youtube.com/playlist?list=${fix.getString("browseId").removePrefix("VL")}",
                title = fix.getString("label")!!,
                subtitle = "YouTube Music",
                thumb = headerThumb,
                isAlbum = false,
            )

            var updated = CoverArtResolver.applyInitialKnownArtwork(initialSongs, headerThumb)
            assertEquals(initialSongs.size, updated.size)
            assertTrue("Rows in $fixtureName should preserve distinct per-track art", updated.all { it.thumb != null && it.thumb != headerThumb })

            val qm = QueueManager()
            qm.start(updated, startIndex = 0, shuffled = false)
            assertEquals(updated[0].thumb, qm.current?.thumb)
            assertEquals(updated[1].thumb, qm.upNext.first().song.thumb)
        }
    }

    @Test
    fun strictSongSearchParser_handlesYt3AndRejectsVariantCandidates() {
        val searchJson = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [{
                "musicShelfRenderer": {
                  "contents": [
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": { "videoId": "remx0000001" },
                        "flexColumns": [
                          { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Main Tujhse Aise Milun (DJ Remix)" }] } } },
                          { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Alka Yagnik" }, { "text": " • " }, { "text": "5:00" }] } } }
                        ],
                        "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://yt3.googleusercontent.com/wrong_remix=w120-h120-l90-rj", "width": 120, "height": 120 }] } } }
                      }
                    },
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": { "videoId": "orig0000002" },
                        "flexColumns": [
                          { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Main Tujhse Aise Milun" }] } } },
                          { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Alka Yagnik & Abhijeet" }, { "text": " • " }, { "text": "5:02" }] } } }
                        ],
                        "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://yt3.googleusercontent.com/exact_judaai=w120-h120-l90-rj", "width": 120, "height": 120 }] } } }
                      }
                    }
                  ]
                }
              }]
            }
          }
        }
        """.trimIndent()
        val root = JsonParser.`object`().from(searchJson)
        val picked = YouTubeMusicInnertube.parseStrictSongArtworkSearchJson(
            root = root,
            sourceTitle = "Main Tujhse Aise Milun (With Jhankar Beats) [From \"Judaai\"]",
            sourceArtist = "Alka Yagnik & Abhijeet",
        )
        assertEquals("https://yt3.googleusercontent.com/exact_judaai=w544-h544-l90-rj", picked)

        val noMatch = YouTubeMusicInnertube.parseStrictSongArtworkSearchJson(
            root = root,
            sourceTitle = "Some Completely Different Song",
            sourceArtist = "Alka Yagnik",
        )
        assertNull(noMatch)
    }

    @Test
    fun equalToAlbumCoverIsAllowedToStay_whileDistinctCoverAlwaysWins() {
        val albumArt = "https://yt3.googleusercontent.com/album_main=w544-h544-l90-rj"
        val distinctArt = "https://yt3.googleusercontent.com/track_unique=w544-h544-l90-rj"
        val ytimg = "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg"

        assertEquals(
            albumArt,
            CoverArtResolver.mergeTrackArtwork(
                existingTrackThumb = ytimg,
                candidateTrackThumb = albumArt,
                albumThumb = albumArt,
                videoId = "abc12345678",
            ),
        )
        assertEquals(
            distinctArt,
            CoverArtResolver.mergeTrackArtwork(
                existingTrackThumb = albumArt,
                candidateTrackThumb = distinctArt,
                albumThumb = albumArt,
                videoId = "abc12345678",
            ),
        )
    }

    @Test
    fun exactStudioSongMatch_rejectsRemixesCoversLiveAndMismatchedTitles() {
        assertTrue(
            CoverArtResolver.isExactStudioSongMatch(
                sourceTitle = "Main Tujhse Aise Milun (With Jhankar Beats) [From \"Judaai\"]",
                sourceArtist = "Alka Yagnik & Abhijeet",
                candidateTitle = "Main Tujhse Aise Milun",
                candidateArtist = "Alka Yagnik & Abhijeet",
            ),
        )
        assertFalse(
            CoverArtResolver.isExactStudioSongMatch(
                sourceTitle = "Tum Hi Ho",
                sourceArtist = "Arijit Singh",
                candidateTitle = "Tum Hi Ho (Unplugged Cover)",
                candidateArtist = "Arijit Singh",
            ),
        )
        assertFalse(
            CoverArtResolver.isExactStudioSongMatch(
                sourceTitle = "Chaiyya Chaiyya",
                sourceArtist = "Sukhwinder Singh",
                candidateTitle = "Chaiyya Chaiyya - DJ Remix",
                candidateArtist = "Sukhwinder Singh",
            ),
        )
        assertFalse(
            CoverArtResolver.isExactStudioSongMatch(
                sourceTitle = "Gulabi Aankhen",
                sourceArtist = "Mohammed Rafi",
                candidateTitle = "Gulabi Aankhen",
                candidateArtist = "Sanam",
            ),
        )
    }

    @Test
    fun progressiveResolution_handles100TrackAlbumWithoutBlocking_andPrioritizesVisiblePlus10Ahead() = runBlocking {
        val header = "https://yt3.googleusercontent.com/comp_hdr=w544-h544-l90-rj"
        val songs100 = (0 until 100).map { i ->
            Song(id = "trk_${i.toString().padStart(3, '0')}A", title = "Song $i", artist = "Artist ${i % 5}", thumb = header)
        }
        val album = AlbumRef("https://music.youtube.com/browse/MPREb_100", "100 Hits", "Various Artists", header, isAlbum = true)

        val resolvedOrder = Collections.synchronizedList(mutableListOf<Int>())
        val emissions = mutableListOf<List<Song>>()
        val visibleWindow = 10..15 // Visible 10..15 + lookahead 10 -> 8..25 prioritized first

        CoverArtResolver.resolveAlbumArtworkProgressively(
            collectionUrl = album.url,
            album = album,
            initialSongs = songs100,
            visibleWindowProvider = { visibleWindow },
            lookaheadCount = 10,
            batchSize = 6,
            ioDispatcher = Dispatchers.Unconfined,
            browseProvider = { emptyList() },
            nextArtworkProvider = { track ->
                val num = track.id.substringAfter("trk_").substring(0, 3).toInt()
                resolvedOrder.add(num)
                "https://lh3.googleusercontent.com/unique_$num=w544-h544-l90-rj"
            },
            songSearchArtworkProvider = { null },
            onSongsUpdated = { emissions += it },
        )

        val finalSongs = emissions.last()
        assertEquals(100, resolvedOrder.size)
        assertEquals("https://lh3.googleusercontent.com/unique_99=w544-h544-l90-rj", finalSongs[99].thumb)
        assertTrue("Expected progressive incremental batches", emissions.size >= 10)
        val firstSix = resolvedOrder.take(6)
        assertTrue("Expected visible/lookahead window first, got $firstSix", firstSix.all { it in 8..25 })
    }

    @Test
    fun coverArtCache_hitsAcrossReloads_andRespectsBoundedSize() {
        val url = "https://yt3.googleusercontent.com/cached_art_1=w544-h544-l90-rj"
        CoverArtCache.put("DkE-T4AICdw", url, isSquareArtwork = true)
        assertEquals(url, CoverArtCache.get("DkE-T4AICdw"))

        // Generic ytimg frame cannot overwrite square cover in cache
        assertFalse(CoverArtCache.put("DkE-T4AICdw", "https://i.ytimg.com/vi/DkE-T4AICdw/hqdefault.jpg"))
        assertEquals(url, CoverArtCache.get("DkE-T4AICdw"))

        // Bounded LRU eviction keeps size <= MAX_ENTRIES
        for (i in 0 until CoverArtCache.MAX_ENTRIES + 25) {
            CoverArtCache.put("cap_${i.toString().padStart(7, '0')}", "https://yt3.googleusercontent.com/c_$i=w544-h544", persist = false)
        }
        assertEquals(CoverArtCache.MAX_ENTRIES, CoverArtCache.size())

        // Re-insert real key, flush, and reload from disk to simulate cold restart -> cache hit in 0ms
        CoverArtCache.put("DkE-T4AICdw", url, isSquareArtwork = true, persist = true)
        CoverArtCache.clearForTest(deleteFile = false)
        CoverArtCache.attachDirectory(tempDir)
        assertEquals(url, CoverArtCache.get("DkE-T4AICdw"))

        val initial = CoverArtResolver.applyInitialKnownArtwork(
            listOf(Song("DkE-T4AICdw", "Main Tujhse", "Alka Yagnik", thumb = null)),
            albumArtworkUrl = "https://yt3.googleusercontent.com/album=w544-h544-l90-rj",
        )
        assertEquals(url, initial[0].thumb)
    }

    @Test
    fun cancellingScreenJob_stopsProgressiveArtLookupsPromptly() = runBlocking {
        val header = "https://yt3.googleusercontent.com/comp_hdr=w544-h544-l90-rj"
        val songs = (0 until 50).map { i -> Song("vid_$i", "Song $i", "Artist $i", thumb = header) }
        val album = AlbumRef("https://music.youtube.com/browse/MPREb_cancel", "Mix", "Various Artists", header, isAlbum = true)
        val calls = AtomicInteger(0)
        val firstBatchStarted = CompletableDeferred<Unit>()

        val job = launch {
            CoverArtResolver.resolveAlbumArtworkProgressively(
                collectionUrl = album.url,
                album = album,
                initialSongs = songs,
                ioDispatcher = Dispatchers.Default,
                browseProvider = { emptyList() },
                nextArtworkProvider = {
                    calls.incrementAndGet()
                    firstBatchStarted.complete(Unit)
                    delay(100)
                    null
                },
                songSearchArtworkProvider = { null },
                onSongsUpdated = {},
            )
        }

        firstBatchStarted.await()
        job.cancelAndJoin()
        val countAtCancel = calls.get()
        assertTrue("Expected partial lookups before cancel, got $countAtCancel", countAtCancel < 50)
    }
}
