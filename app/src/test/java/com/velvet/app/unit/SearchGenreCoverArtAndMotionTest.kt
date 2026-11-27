package com.velvet.app.unit

import com.grack.nanojson.JsonParser
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.LanguageLocaleConfig
import com.velvet.app.data.SearchLanguageFilter
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.playback.CoverArtResolver
import com.velvet.app.playback.QueueManager
import com.velvet.app.playback.reconcileSongWithMediaArtwork
import com.velvet.app.playback.resolveMediaItemArtworkUrl
import com.velvet.app.ui.GenreRotationStrategy
import com.velvet.app.ui.VelvetMotion
import com.velvet.app.ui.VelvetPopupMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the 5-area Search / Genre / Cover Art / Motion overhaul:
 * 1) Strict Language & Locale Consistency Across Search
 * 2) Track-Level Cover Art Preservation in Pre-Built Albums & Player Metadata
 * 3) Dynamic Genre Discovery & Rotating Recommendations
 * 4) Spotify-Style Language-Bound Genre Tiles Catalog
 * 5) Centralized App-Wide VelvetMotion Constants
 */
class SearchGenreCoverArtAndMotionTest {

    // ---------------------------------------------------------------- 1. Language & Locale Consistency

    @Test
    fun languageLocaleConfigMapsAllLanguagesToAccurateHlGlAndIsoCodes() {
        val tamilOnly = LanguageLocaleConfig.fromSelectedLanguages(listOf("Tamil"))
        assertEquals(setOf("ta"), tamilOnly.isoCodes)
        assertEquals("ta", tamilOnly.hl)
        assertEquals("IN", tamilOnly.gl)
        assertEquals("Tamil", tamilOnly.primaryLanguage)

        val englishOnly = LanguageLocaleConfig.fromSelectedLanguages(listOf("English"))
        assertEquals(setOf("en"), englishOnly.isoCodes)
        assertEquals("en", englishOnly.hl)
        assertEquals("US", englishOnly.gl)

        val multi = LanguageLocaleConfig.fromSelectedLanguages(listOf("Bhojpuri", "Marathi", "Urdu", "Haryanvi"))
        assertEquals(setOf("bho", "mr", "ur", "bgc"), multi.isoCodes)
        assertEquals("IN", multi.gl)
    }

    @Test
    fun searchPreHintsAndGenreCatalogStrictlyBindToSelectedLanguage() {
        val tamilState = LanguageLocaleConfig.fromSelectedLanguages(listOf("Tamil"))
        val tamilHints = LanguageLocaleConfig.preHintQueriesFor(tamilState)
        assertTrue(tamilHints.isNotEmpty())
        assertTrue(tamilHints.all {
            it.contains("Tamil", ignoreCase = true) ||
                it.contains("Kollywood", ignoreCase = true) ||
                it.contains("Anirudh", ignoreCase = true) ||
                it.contains("Rahman", ignoreCase = true) ||
                it.contains("Sid Sriram", ignoreCase = true)
        })
        assertFalse(tamilHints.any { it.contains("Bollywood", ignoreCase = true) })

        val tamilGenres = LanguageLocaleConfig.genreCatalogFor(tamilState)
        val genreIds = tamilGenres.map { it.id }.toSet()
        assertTrue("kollywood" in genreIds)
        assertFalse("bollywood" in genreIds)
        assertFalse("punjabi_beats" in genreIds)
        assertFalse("western_pop" in genreIds)
        val romantic = tamilGenres.first { it.id == "romantic" }
        assertTrue(romantic.subtitle.contains("Tamil"))
    }

    @Test
    fun searchLanguageFilterPreventsCrossLanguageLeaksWhileAllowingMultilingual() {
        val hindiOnly = LanguageLocaleConfig.fromSelectedLanguages(listOf("Hindi"))
        val tracks = listOf(
            Song("1", "Tum Hi Ho", "Arijit Singh", thumb = "https://lh3.googleusercontent.com/1"),
            Song("2", "Shape of You", "Ed Sheeran", thumb = "https://lh3.googleusercontent.com/2"),
            Song("3", "Arabic Kuthu", "Anirudh Ravichander", thumb = "https://lh3.googleusercontent.com/3"),
            Song("4", "Lover", "Diljit Dosanjh", thumb = "https://lh3.googleusercontent.com/4"),
            Song("5", "Pan India Flutes Instrumental", "Various Artists", thumb = "https://lh3.googleusercontent.com/5"),
            Song("6", "Fresh Indie Track", "Emerging Unknown Composer", thumb = "https://lh3.googleusercontent.com/6"),
        )

        val filtered = SearchLanguageFilter.filterSongs(tracks, hindiOnly, explicitQuery = "romantic songs")
        val ids = filtered.map { it.id }
        assertTrue("Hindi track kept", "1" in ids)
        assertFalse("English leak blocked in Hindi-only mode", "2" in ids)
        assertFalse("Tamil leak blocked in Hindi-only mode", "3" in ids)
        assertFalse("Punjabi leak blocked in Hindi-only mode", "4" in ids)
        assertTrue("Inherently multilingual/instrumental allowed", "5" in ids)
        assertTrue("Unlabelled localized query result kept", "6" in ids)

        val rawSuggestions = listOf("romantic songs hindi", "taylor swift romantic songs", "romantic tamil songs", "arijit singh romantic")
        val filteredSuggestions = SearchLanguageFilter.filterSuggestions(rawSuggestions, "romantic", hindiOnly)
        assertFalse("taylor swift romantic songs" in filteredSuggestions)
        assertFalse("romantic tamil songs" in filteredSuggestions)
        assertTrue("romantic songs hindi" in filteredSuggestions)

        assertEquals(
            "Tamil romantic songs",
            SearchLanguageFilter.scopeSearchQuery("romantic songs", LanguageLocaleConfig.fromSelectedLanguages(listOf("Tamil"))),
        )
        assertEquals(
            "Anirudh top hits",
            SearchLanguageFilter.scopeSearchQuery("Anirudh top hits", LanguageLocaleConfig.fromSelectedLanguages(listOf("Tamil"))),
        )
    }

    // ---------------------------------------------------------------- 2. Cover Art Preservation

    @Test
    fun coverArtResolverPreservesIndividualTrackArtworkAndEnforcesFallbackOrder() {
        val trackArt = "https://lh3.googleusercontent.com/track_cover_unique=w544-h544"
        val albumArt = "https://lh3.googleusercontent.com/parent_album_cover=w544-h544"
        val placeholder = "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg"

        assertEquals(trackArt, CoverArtResolver.resolveTrackArtworkUrl(trackArt, albumArt, placeholder))
        assertEquals(albumArt, CoverArtResolver.resolveTrackArtworkUrl(null, albumArt, placeholder))
        assertEquals(albumArt, CoverArtResolver.resolveTrackArtworkUrl("   ", albumArt, placeholder))
        assertEquals(placeholder, CoverArtResolver.resolveTrackArtworkUrl(null, "", placeholder))
        assertNull(CoverArtResolver.resolveTrackArtworkUrl(null, null, null))

        // Generic ytimg placeholder upgrades cleanly to distinct YTM track artwork without falling back to album
        assertEquals(
            trackArt,
            CoverArtResolver.mergeTrackArtwork(
                existingTrackThumb = placeholder,
                candidateTrackThumb = trackArt,
                albumThumb = albumArt,
                videoId = "abc12345678",
            ),
        )
        // Non-generic unique existing track cover is NEVER overwritten by parent album cover
        assertEquals(
            trackArt,
            CoverArtResolver.mergeTrackArtwork(
                existingTrackThumb = trackArt,
                candidateTrackThumb = albumArt,
                albumThumb = albumArt,
                videoId = "abc12345678",
            ),
        )
    }

    @Test
    fun albumFallbackAndMediaItemReconciliationPreservePerTrackArtworkAcrossSkips() {
        val albumThumb = "https://lh3.googleusercontent.com/album_art=w544-h544"
        val track1 = Song("track000001", "Song One", "Artist A", thumb = "https://lh3.googleusercontent.com/art1=w544-h544")
        val track2 = Song("track000002", "Song Two", "Artist B", thumb = null)
        val track3 = Song("track000003", "Song Three", "Artist C", thumb = "https://lh3.googleusercontent.com/art3=w544-h544")

        val prepared = CoverArtResolver.applyAlbumFallback(listOf(track1, track2, track3), albumThumb)
        assertEquals("https://lh3.googleusercontent.com/art1=w544-h544", prepared[0].thumb)
        assertEquals(albumThumb, prepared[1].thumb)
        assertEquals("https://lh3.googleusercontent.com/art3=w544-h544", prepared[2].thumb)

        // Skipping updates queueManager artwork cleanly when async lookup resolves track2's own cover
        val qm = QueueManager()
        qm.start(prepared, startIndex = 0, shuffled = false)
        assertEquals(prepared[0].thumb, qm.current?.thumb)

        val resolvedTrack2Art = "https://lh3.googleusercontent.com/art2_unique=w544-h544"
        val updated = qm.updateSongArtwork("track000002", resolvedTrack2Art, albumArtFallback = albumThumb)
        assertTrue(updated)
        assertEquals(resolvedTrack2Art, qm.upNext.first().song.thumb)

        val afterSkip = qm.advance(manual = true)
        assertEquals(resolvedTrack2Art, afterSkip?.thumb)

        // MediaItem / knownSongs reconciliation never lets stale ytimg/album override distinct track art
        val mediaThumbOut = resolveMediaItemArtworkUrl(
            song = track2.copy(thumb = resolvedTrack2Art),
            existingCached = track2.copy(thumb = albumThumb),
            fallbackAlbumArt = albumThumb,
        )
        assertEquals(resolvedTrack2Art, mediaThumbOut)

        val restored = reconcileSongWithMediaArtwork(
            mediaId = "track000002",
            cached = track2.copy(thumb = "https://i.ytimg.com/vi/track000002/hqdefault.jpg"),
            mediaTitle = "Song Two",
            mediaArtist = "Artist B",
            mediaAlbum = "Parent Compilation",
            mediaDurationSec = 200L,
            mediaThumb = resolvedTrack2Art,
            mediaArtistUrl = null,
        )
        assertEquals(resolvedTrack2Art, restored.thumb)
    }

    @Test
    fun innertubeBrowseIdAndTrackArtworkParsersHandleAlbumAndPlaylistUrls() {
        assertEquals(
            "VLOLAK5uy_abc123",
            YouTubeMusicInnertube.extractBrowseId("https://music.youtube.com/playlist?list=OLAK5uy_abc123"),
        )
        assertEquals(
            "MPREb_xyz789",
            YouTubeMusicInnertube.extractBrowseId("https://music.youtube.com/browse/MPREb_xyz789"),
        )

        val nextJson = """
        {
          "contents": {
            "singleColumnMusicWatchNextResultsRenderer": {
              "tabbedRenderer": {
                "watchNextTabbedResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "musicQueueRenderer": {
                          "content": {
                            "playlistPanelRenderer": {
                              "contents": [{
                                "playlistPanelVideoRenderer": {
                                  "videoId": "vidTrack111",
                                  "thumbnail": {
                                    "thumbnails": [
                                      { "url": "https://lh3.googleusercontent.com/track111=w120-h120-l90-rj", "width": 120, "height": 120 },
                                      { "url": "https://lh3.googleusercontent.com/track111=w544-h544-l90-rj", "width": 544, "height": 544 }
                                    ]
                                  }
                                }
                              }]
                            }
                          }
                        }
                      }
                    }
                  }]
                }
              }
            }
          }
        }
        """.trimIndent()
        val art = YouTubeMusicInnertube.parseSelectedTrackArtworkJson(JsonParser.`object`().from(nextJson), "vidTrack111")
        assertEquals("https://lh3.googleusercontent.com/track111=w544-h544-l90-rj", art)
    }

    // ---------------------------------------------------------------- 3. Dynamic Genre Discovery

    @Test
    fun genreRotationStrategyChangesAcrossWindowsAndExcludesTrendingTracks() {
        val state = LanguageLocaleConfig.fromSelectedLanguages(listOf("Hindi", "English"))
        val spec = GenreRotationStrategy.resolveSpec("Romantic", state)

        val batchSpin0 = GenreRotationStrategy.selectRotatingSeeds(spec, spin = 0, nowMs = 0L, maxSeeds = 3)
        val batchSpin1 = GenreRotationStrategy.selectRotatingSeeds(spec, spin = 1, nowMs = 0L, maxSeeds = 3)
        assertEquals(3, batchSpin0.size)
        assertNotEquals("Rotating spin must vary seed selection order", batchSpin0, batchSpin1)

        val trending = listOf(
            Song("t1", "Kesariya", "Arijit Singh"),
            Song("t2", "Apna Bana Le", "Arijit Singh"),
        )
        val candidates = listOf(
            Song("t1", "Kesariya", "Arijit Singh"),
            Song("c1", "Raabta", "Arijit Singh"),
            Song("c2", "Tera Ban Jaunga", "Akhil Sachdeva"),
            Song("c3", "Pee Loon", "Pritam"),
        )
        val rotating = GenreRotationStrategy.filterRotatingExcludingTrending(candidates, trending, spin = 2, limit = 10)
        assertFalse(rotating.any { it.id == "t1" })
        assertEquals(setOf("c1", "c2", "c3"), rotating.map { it.id }.toSet())
    }

    // ---------------------------------------------------------------- 4. Centralized Motion System

    @Test
    fun velvetMotionConstantsAndDelegationPreserveConsistentCohesiveSpecs() {
        assertTrue(VelvetMotion.Duration.Micro < VelvetMotion.Duration.Fast)
        assertTrue(VelvetMotion.Duration.Fast < VelvetMotion.Duration.Standard)
        assertTrue(VelvetMotion.Duration.Standard < VelvetMotion.Duration.Emphasized)

        assertEquals(VelvetMotion.Shape.Dialog, VelvetPopupMotion.DialogShape)
        assertEquals(VelvetMotion.Shape.Sheet, VelvetPopupMotion.SheetShape)
        assertEquals(VelvetMotion.Shape.Menu, VelvetPopupMotion.MenuShape)
        assertEquals(VelvetMotion.Springs.PopupEnter, VelvetPopupMotion.enterSpring)
        assertEquals(VelvetMotion.Springs.PopupExit, VelvetPopupMotion.exitSpring)
        assertEquals(VelvetMotion.Springs.Interactive, VelvetPopupMotion.sliderSpring)
    }
}
