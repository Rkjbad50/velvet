package com.velvet.app.unit

import com.grack.nanojson.JsonParser
import com.velvet.app.data.ArtistRef
import com.velvet.app.data.Library
import com.velvet.app.data.LibraryData
import com.velvet.app.data.LibraryStore
import com.velvet.app.data.LoadResult
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.data.mergeLibraries
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.home.HomeRecommendationComposer
import com.velvet.app.recommendation.profile.ExplicitLibraryInputs
import com.velvet.app.recommendation.profile.TasteProfileBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnboardingShuffleAndHomePersonalizationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun studioSong(
        id: String,
        title: String,
        artist: String,
        durationSec: Long = 230L,
        album: String? = null,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = durationSec,
        album = album,
        isTopicRelease = true,
        rawUploaderName = "$artist - Topic",
        preferredMusicSource = true,
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    @Test
    fun libraryCompleteOnboardingPersistsPreferencesArtistsAndReopenToggle() {
        val lib = Library(tmp.root)
        assertFalse(lib.data.value.onboardingCompleted)

        val pickedArtists = listOf(
            ArtistRef(url = "https://music.youtube.com/channel/UC111", name = "Arijit Singh"),
            ArtistRef(url = "https://music.youtube.com/channel/UC222", name = "Diljit Dosanjh"),
            ArtistRef(url = "https://music.youtube.com/channel/UC333", name = "Shreya Ghoshal"),
        )
        lib.completeOnboarding(
            languages = listOf("Hindi", "Punjabi"),
            artists = pickedArtists,
            genres = listOf("Bollywood", "Punjabi Beats", "Sufi & Ghazal"),
        )
        lib.flushBlocking()

        val reloaded = (LibraryStore(tmp.root).load() as LoadResult.Loaded).data
        assertTrue(reloaded.onboardingCompleted)
        assertEquals(listOf("Hindi", "Punjabi"), reloaded.musicLanguages)
        assertEquals(listOf("Arijit Singh", "Diljit Dosanjh", "Shreya Ghoshal"), reloaded.preferredArtists)
        assertEquals(listOf("Bollywood", "Punjabi Beats", "Sufi & Ghazal"), reloaded.preferredGenres)
        assertEquals(3, reloaded.artists.size)

        // Merge backup carries onboarding & shuffle fields cleanly
        val merged = mergeLibraries(LibraryData(), reloaded)
        assertTrue(merged.onboardingCompleted)
        assertEquals(reloaded.preferredArtists, merged.preferredArtists)
        assertEquals(reloaded.preferredGenres, merged.preferredGenres)

        // Reopening survey from Settings preserves existing picks while showing survey again
        lib.reopenOnboarding()
        lib.flushBlocking()
        val afterReopen = (LibraryStore(tmp.root).load() as LoadResult.Loaded).data
        assertFalse(afterReopen.onboardingCompleted)
        assertEquals(3, afterReopen.preferredArtists.size)
        assertEquals(listOf("Hindi", "Punjabi"), afterReopen.musicLanguages)
    }

    @Test
    fun shuffleModeAndFirstTimeToastFlagsPersistAcrossAppRestarts() {
        val lib = Library(tmp.root)
        assertEquals("OFF", lib.data.value.persistedShuffleMode)
        assertFalse(lib.data.value.shuffleToastShownOnce)
        assertFalse(lib.data.value.smartShuffleToastShownOnce)

        lib.setPersistedShuffleMode("SMART")
        lib.markShuffleToastShown()
        lib.markSmartShuffleToastShown()
        lib.flushBlocking()

        val reloaded = (LibraryStore(tmp.root).load() as LoadResult.Loaded).data
        assertEquals("SMART", reloaded.persistedShuffleMode)
        assertTrue(reloaded.shuffleToastShownOnce)
        assertTrue(reloaded.smartShuffleToastShownOnce)

        // Invalid strings sanitize safely back to OFF
        lib.setPersistedShuffleMode("UNKNOWN_MODE")
        lib.flushBlocking()
        val sanitized = (LibraryStore(tmp.root).load() as LoadResult.Loaded).data
        assertEquals("OFF", sanitized.persistedShuffleMode)
    }

    @Test
    fun tasteProfileBuilderFoldsOnboardingArtistsAndGenresOnColdStart() {
        val builder = TasteProfileBuilder()
        val coldBlank = builder.build(events = emptyList(), libraryInputs = ExplicitLibraryInputs())
        assertTrue(coldBlank.longTerm.genreWeights.isEmpty())
        assertTrue(coldBlank.longTerm.artistWeights.isEmpty())

        val surveyed = builder.build(
            events = emptyList(),
            libraryInputs = ExplicitLibraryInputs(
                preferredArtistNames = listOf("Diljit Dosanjh", "Arijit Singh", "Anup Jalota"),
                preferredGenres = listOf("Bhakti & Devotional", "Punjabi Beats", "Sufi & Ghazal"),
                preferredLanguageNames = listOf("Hindi", "Punjabi"),
            ),
        )
        assertTrue(GenreOntology.DEVOTIONAL in surveyed.longTerm.genreWeights)
        assertTrue(GenreOntology.PUNJABI in surveyed.longTerm.genreWeights)
        assertTrue(GenreOntology.SUFI_GHAZAL in surveyed.longTerm.genreWeights)
        assertTrue("hi" in surveyed.longTerm.languageWeights)
        assertTrue("pa" in surveyed.longTerm.languageWeights)
        assertTrue("diljit dosanjh" in surveyed.longTerm.artistWeights)
        assertTrue("arijit singh" in surveyed.explicit.followedArtistIds)

        // Verify all UI survey genre labels canonicalize cleanly
        val allSurveyGenres = listOf(
            "Bollywood", "Bhakti & Devotional", "Punjabi Beats", "Sufi & Ghazal",
            "90s & Retro", "Romantic", "Sad & Soulful", "South Indian",
            "Indian Indie", "Western Pop", "Hip-Hop & Rap", "Lo-Fi & Chill",
            "EDM & Party", "Indian Classical", "Rock",
        )
        allSurveyGenres.forEach { label ->
            assertTrue("Missing canonical genre mapping for '$label'", GenreOntology.canonicalize(label) != null)
        }
    }

    @Test
    fun homePersonalizationProducesDistinctTrendingAndSpeedDialPerOnboardingProfile() {
        val composer = HomeRecommendationComposer()
        val punjabiHit = studioSong("p1", "Born To Shine", "Diljit Dosanjh")
        val bhajanHit = studioSong("b1", "Hanuman Chalisa", "Hariharan")
        val bollywoodHit = studioSong("h1", "Tum Hi Ho", "Arijit Singh")
        val englishPopHit = studioSong("e1", "Blinding Lights", "The Weeknd")
        val tamilHit = studioSong("t1", "Why This Kolaveri", "Anirudh Ravichander")
        val s1 = studioSong("s1", "Lover", "Diljit Dosanjh")
        val s2 = studioSong("s2", "Shiv Tandav Stotram", "Shankar Mahadevan")
        val s3 = studioSong("s3", "Kesariya", "Arijit Singh")
        val s4 = studioSong("s4", "Save Your Tears", "The Weeknd")
        val s5 = studioSong("s5", "Chogada", "Darshan Raval")
        val s6 = studioSong("s6", "Kun Faya Kun", "A.R. Rahman")

        val sharedChartPool = listOf(englishPopHit, bollywoodHit, tamilHit, punjabiHit, bhajanHit, s1, s2, s3, s4, s5, s6)

        val punjabiTrending = composer.rankAndFilterTrending(
            rawTrending = sharedChartPool,
            preferredLanguageIds = setOf("pa"),
            preferredArtistNames = setOf("Diljit Dosanjh"),
            preferredGenres = listOf("Punjabi Beats"),
            limit = 9,
        )
        val bhaktiTrending = composer.rankAndFilterTrending(
            rawTrending = sharedChartPool,
            preferredLanguageIds = setOf("sa", "hi"),
            preferredArtistNames = setOf("Hariharan", "Shankar Mahadevan"),
            preferredGenres = listOf("Bhakti & Devotional"),
            limit = 9,
        )

        // Different users choosing different survey answers get distinct personalized orderings
        assertTrue(punjabiTrending.take(3).any { it.artist == "Diljit Dosanjh" })
        assertTrue(bhaktiTrending.take(3).any { it.id in setOf("b1", "s2") })
        assertFalse(punjabiTrending.map { it.id } == bhaktiTrending.map { it.id })

        // Cold-start Speed Dial fills all 9/9 distinct verified songs when catalog has >= 9 tracks
        val speedDial = composer.composeSpeedDial(
            recent = emptyList(),
            liked = emptyList(),
            quickPicks = emptyList(),
            trending = sharedChartPool,
            preferredArtistNames = setOf("Diljit Dosanjh"),
            preferredGenres = listOf("Punjabi Beats"),
            limit = 9,
        )
        assertEquals(9, speedDial.size)
        assertTrue(speedDial.take(3).any { it.artist == "Diljit Dosanjh" })
    }

    @Test
    fun composeQuickPicksPopulatesFromSurveyWhenHistoryEmptyAndStaysEmptyWithoutSurvey() = runBlocking {
        val composer = HomeRecommendationComposer()
        assertTrue(composer.composeQuickPicks(recent = emptyList()).isEmpty())

        val picks = composer.composeQuickPicks(
            recent = emptyList(),
            preferredArtistNames = setOf("Diljit Dosanjh", "Arijit Singh", "The Weeknd"),
            preferredGenres = listOf("Punjabi Beats"),
            directSearchProvider = { q ->
                when {
                    q.contains("Diljit", true) -> listOf(studioSong("d1", "DO YOU KNOW", "Diljit Dosanjh"))
                    else -> listOf(studioSong("a1", "Apna Bana Le", "Arijit Singh"))
                }
            },
            limit = 5,
        )
        assertEquals(2, picks.size)
        assertTrue(picks.any { it.id == "d1" })
    }

    @Test
    fun innertubeParsersExtractLiveChartTracksPlaylistIdsAndArtistsStrictly() {
        val rawChartsJson = """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [
                            {
                              "musicCarouselShelfRenderer": {
                                "contents": [
                                  {
                                    "musicTwoRowItemRenderer": {
                                      "title": { "runs": [{ "text": "Top Songs India" }] },
                                      "navigationEndpoint": { "browseEndpoint": { "browseId": "VLPL4fGSI1pDJn40MEd61" } }
                                    }
                                  },
                                  {
                                    "musicTwoRowItemRenderer": {
                                      "title": { "runs": [{ "text": "Top Music Videos Global" }] },
                                      "navigationEndpoint": { "browseEndpoint": { "browseId": "VLPLVideosOnly999" } }
                                    }
                                  },
                                  {
                                    "musicTwoRowItemRenderer": {
                                      "title": { "runs": [{ "text": "Tauba Tauba" }] },
                                      "subtitle": { "runs": [{ "text": "Song" }, { "text": " • " }, { "text": "Karan Aujla" }] },
                                      "navigationEndpoint": {
                                        "watchEndpoint": {
                                          "videoId": "dQw4w9WgXcQ",
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
                                          }
                                        }
                                      },
                                      "thumbnailRenderer": {
                                        "musicThumbnailRenderer": {
                                          "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/abc=w120-h120", "width": 120, "height": 120 }] }
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "musicTwoRowItemRenderer": {
                                      "title": { "runs": [{ "text": "Official Video Cut" }] },
                                      "subtitle": { "runs": [{ "text": "Video" }, { "text": " • " }, { "text": "Some Channel" }] },
                                      "navigationEndpoint": {
                                        "watchEndpoint": {
                                          "videoId": "abCdEfGhIjK",
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_OMV" }
                                          }
                                        }
                                      },
                                      "thumbnailRenderer": {
                                        "musicThumbnailRenderer": {
                                          "thumbnail": { "thumbnails": [{ "url": "https://i.ytimg.com/vi/abCdEfGhIjK/hqdefault.jpg", "width": 480, "height": 360 }] }
                                        }
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          ]
                        }
                      }
                    }
                  }]
                }
              }
            }
        """.trimIndent()

        val chartsRoot = JsonParser.`object`().from(rawChartsJson)
        val parsedSongs = YouTubeMusicInnertube.parseSearchOrBrowseSongsJson(chartsRoot)
        assertEquals(listOf("dQw4w9WgXcQ"), parsedSongs.map { it.id })
        assertEquals("Karan Aujla", parsedSongs.first().artist)

        val playlistIds = YouTubeMusicInnertube.extractChartPlaylistBrowseIds(chartsRoot, listOf("Hindi", "Punjabi"))
        assertEquals("VLPL4fGSI1pDJn40MEd61", playlistIds.first())

        val artistSearchJson = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "musicShelfRenderer": {
                              "contents": [{
                                "musicResponsiveListItemRenderer": {
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Diljit Dosanjh" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist • 15M subscribers" }] } } }
                                  ],
                                  "navigationEndpoint": {
                                    "browseEndpoint": {
                                      "browseId": "UC1234567890Artist",
                                      "browseEndpointContextSupportedConfigs": {
                                        "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" }
                                      }
                                    }
                                  },
                                  "thumbnail": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/artist=w226-h226", "width": 226, "height": 226 }] }
                                    }
                                  }
                                }
                              }]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }
        """.trimIndent()

        val parsedArtists = YouTubeMusicInnertube.parseArtistsSearchJson(JsonParser.`object`().from(artistSearchJson))
        assertEquals(1, parsedArtists.size)
        assertEquals("Diljit Dosanjh", parsedArtists.first().name)
        assertTrue(parsedArtists.first().url.endsWith("/channel/UC1234567890Artist"))
    }
}
