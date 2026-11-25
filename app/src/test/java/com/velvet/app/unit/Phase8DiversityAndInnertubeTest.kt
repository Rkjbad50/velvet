package com.velvet.app.unit

import com.grack.nanojson.JsonParser
import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.CandidateSourceRequest
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.SameReleaseCandidateSource
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.MusicType
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationRequest
import com.velvet.app.recommendation.model.TrackFeatureRecord
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.recommendation.profile.UserTasteSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies:
 * 1) Strict suppression of identical song/bhajan titles across different singers/uploaders.
 * 2) Dominant Genre -> Category -> Type -> Mood -> Tempo ranking over surface/artist/popularity signals.
 * 3) YouTube Music InnerTube JSON parsing & Strict Original Studio Audio gating.
 */
class Phase8DiversityAndInnertubeTest {

    private fun topic(
        id: String,
        title: String,
        artist: String,
        durationSec: Long = 240L,
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
    )

    private fun buildEngine(catalog: List<Song>): DefaultRecommendationEngine {
        val pool = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = { catalog }),
                GenreLanguageNeighbourSource(querySearchProvider = { catalog }),
            ),
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { LibraryData() },
        )
        return DefaultRecommendationEngine(pool, profileRepo)
    }

    @Test
    fun compositionIdentityMatchesSameBhajanAcrossDifferentSingersAndHonorifics() {
        val seed = topic("seed1", "Shree Hanuman Chalisa", "Hariharan")
        val sameDifferentSinger = topic("dup1", "Hanuman Chalisa", "Gulshan Kumar")
        val samePrefixVariant = topic("dup2", "Jai Shri Hanuman Chalisa (Audio)", "Anuradha Paudwal")
        val distinctBhajan = topic("ok1", "Shiv Tandav Stotram", "Shankar Mahadevan")

        assertTrue(RecordingIdentityResolver.isSameCompositionTitle(seed, sameDifferentSinger))
        assertTrue(RecordingIdentityResolver.isSameCompositionTitle(seed, samePrefixVariant))
        assertFalse(RecordingIdentityResolver.isSameCompositionTitle(seed, distinctBhajan))
    }

    @Test
    fun devotionalAutoplayRejectsSameTitleBhajansByDifferentSingersAndPrefersSameGenre() = runBlocking {
        val seed = topic("seed_hc", "Shree Hanuman Chalisa", "Hariharan", durationSec = 580L)
        val catalog = listOf(
            topic("dup_gk", "Hanuman Chalisa", "Gulshan Kumar", durationSec = 570L),
            topic("dup_ap", "Shri Hanuman Chalisa", "Anuradha Paudwal", durationSec = 560L),
            topic("dup_lk", "Jai Hanuman Chalisa", "Lakhbir Singh Lakkha", durationSec = 590L),
            topic("ok_1", "Om Jai Jagdish Hare Aarti", "Anuradha Paudwal", durationSec = 320L),
            topic("ok_2", "Shiv Tandav Stotram", "Shankar Mahadevan", durationSec = 410L),
            topic("ok_3", "Achyutam Keshavam Bhajan", "Vikram Hazra", durationSec = 295L),
            // Duplicate pair within candidates themselves: only one Gayatri Mantra should ever survive
            topic("cluster_a", "Om Gayatri Mantra", "Suresh Wadkar", durationSec = 310L),
            topic("cluster_b", "Gayatri Mantra", "Anuradha Paudwal", durationSec = 315L),
        )

        val engine = buildEngine(catalog)
        val result = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 10)
        val ids = result.candidates.map { it.song.id }

        assertFalse("Gulshan Kumar copy must be excluded", "dup_gk" in ids)
        assertFalse("Anuradha Paudwal copy must be excluded", "dup_ap" in ids)
        assertFalse("Lakhbir Singh Lakkha copy must be excluded", "dup_lk" in ids)
        assertTrue("Distinct devotional songs must be recommended", setOf("ok_1", "ok_2", "ok_3").all { it in ids })
        val gayatriCount = ids.count { it == "cluster_a" || it == "cluster_b" }
        assertEquals("At most one copy of any candidate composition may enter queue", 1, gayatriCount)
    }

    @Test
    fun rankingGivesDominantPriorityToGenreCategoryAndTypeOverSameArtistMismatch() = runBlocking {
        val seed = topic("seed_dev", "Achyutam Keshavam Krishna Damodaram Bhajan", "Artist Alpha", durationSec = 300L)
        val sameGenreCategoryTypeDiffArtist = topic(
            id = "fit_genre",
            title = "Om Namo Bhagavate Vasudevaya Stotram",
            artist = "Artist Beta",
            durationSec = 305L,
        )
        val wrongGenreSameArtist = topic(
            id = "clash_party",
            title = "Midnight Club Party Beat Mix",
            artist = "Artist Alpha",
            durationSec = 220L,
        )

        val engine = buildEngine(listOf(wrongGenreSameArtist, sameGenreCategoryTypeDiffArtist))
        val res = engine.nextSimilar(seed, mode = RecommendationMode.SIMILAR_SONGS, limit = 5)

        assertTrue(res.candidates.isNotEmpty())
        assertEquals("fit_genre", res.candidates.first().song.id)
    }

    @Test
    fun innertubeNextRadioParserAcceptsAtvAndRejectsOmvUgcAndUnverifiedVideo() {
        val sampleJson = """
        {
          "contents": {
            "singleColumnMusicWatchNextResultsRenderer": {
              "tabbedRenderer": {
                "watchNextTabbedResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "title": "Up next",
                        "content": {
                          "musicQueueRenderer": {
                            "content": {
                              "playlistPanelRenderer": {
                                "contents": [
                                  {
                                    "playlistPanelVideoRenderer": {
                                      "videoId": "dQw4w9WgXcQ",
                                      "title": { "runs": [{ "text": "Shiv Tandav Stotram" }] },
                                      "longBylineText": {
                                        "runs": [
                                          { "text": "Shankar Mahadevan", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC1234567890" } } },
                                          { "text": " • " },
                                          { "text": "Sacred Chants" }
                                        ]
                                      },
                                      "lengthText": { "runs": [{ "text": "6:45" }] },
                                      "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", "width": 120, "height": 120 }] },
                                      "navigationEndpoint": {
                                        "watchEndpoint": {
                                          "videoId": "dQw4w9WgXcQ",
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": {
                                              "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                            }
                                          }
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "playlistPanelVideoRenderer": {
                                      "videoId": "omv12345678",
                                      "title": { "runs": [{ "text": "Official Video Song" }] },
                                      "longBylineText": { "runs": [{ "text": "Pop Star" }] },
                                      "lengthText": { "runs": [{ "text": "3:40" }] },
                                      "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/def=w120-h120-l90-rj", "width": 120, "height": 120 }] },
                                      "navigationEndpoint": {
                                        "watchEndpoint": {
                                          "videoId": "omv12345678",
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": {
                                              "musicVideoType": "MUSIC_VIDEO_TYPE_OMV"
                                            }
                                          }
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "playlistPanelVideoRenderer": {
                                      "videoId": "ugc12345678",
                                      "title": { "runs": [{ "text": "Fan Upload Song" }] },
                                      "longBylineText": { "runs": [{ "text": "Random User" }] },
                                      "lengthText": { "runs": [{ "text": "4:00" }] },
                                      "thumbnail": { "thumbnails": [{ "url": "https://i.ytimg.com/vi/ugc12345678/hqdefault.jpg", "width": 480, "height": 360 }] },
                                      "navigationEndpoint": {
                                        "watchEndpoint": {
                                          "videoId": "ugc12345678",
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": {
                                              "musicVideoType": "MUSIC_VIDEO_TYPE_UGC"
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      }
                    },
                    {
                      "tabRenderer": {
                        "title": "Related",
                        "endpoint": { "browseEndpoint": { "browseId": "MPTRt_abcd1234" } }
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent()

        val root = JsonParser.`object`().from(sampleJson)
        val (songs, relatedBrowseId) = YouTubeMusicInnertube.parseNextRadioJson(root, excludeVideoId = "seed0000001")

        assertEquals("MPTRt_abcd1234", relatedBrowseId)
        assertEquals(listOf("dQw4w9WgXcQ"), songs.map { it.id })
        val accepted = songs.single()
        assertEquals("Shankar Mahadevan", accepted.artist)
        assertEquals("Sacred Chants", accepted.album)
        assertEquals(405L, accepted.durationSec)
        assertTrue(accepted.isYouTubeMusicCatalog)
        assertTrue(accepted.thumb?.contains("=w544-h544") == true)
    }

    @Test
    fun innertubeSearchParserFiltersMusicVideosStrictly() {
        val searchJson = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "songAtv1234" },
                                    "flexColumns": [
                                      { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Kun Faya Kun" }] } } },
                                      { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                                        { "text": "A.R. Rahman", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC9988776655" } } },
                                        { "text": " • " },
                                        { "text": "Rockstar" },
                                        { "text": " • " },
                                        { "text": "7:52" }
                                      ] } } }
                                    ],
                                    "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/xyz=w120-h120-l90-rj", "width": 120, "height": 120 }] } } },
                                    "overlay": { "musicItemThumbnailOverlayRenderer": { "content": { "musicPlayButtonRenderer": { "playNavigationEndpoint": { "watchEndpoint": { "videoId": "songAtv1234", "watchEndpointMusicSupportedConfigs": { "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" } } } } } } } }
                                  }
                                },
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "vidOmv99999" },
                                    "flexColumns": [
                                      { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Nadaan Parindey (Official Video)" }] } } },
                                      { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Mohit Chauhan" }, { "text": " • " }, { "text": "6:20" }] } } }
                                    ],
                                    "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/vid=w120-h120-l90-rj", "width": 120, "height": 120 }] } } },
                                    "overlay": { "musicItemThumbnailOverlayRenderer": { "content": { "musicPlayButtonRenderer": { "playNavigationEndpoint": { "watchEndpoint": { "videoId": "vidOmv99999", "watchEndpointMusicSupportedConfigs": { "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_OMV" } } } } } } } }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val root = JsonParser.`object`().from(searchJson)
        val parsed = YouTubeMusicInnertube.parseSearchOrBrowseSongsJson(root)

        assertEquals(listOf("songAtv1234"), parsed.map { it.id })
        assertEquals("A.R. Rahman", parsed.single().artist)
        assertEquals("Rockstar", parsed.single().album)
        assertEquals(472L, parsed.single().durationSec)
    }

    @Test
    fun earlyQuorumFinishesWellUnderThreeSecondsEvenWhenSecondarySourceStalls() = runBlocking {
        val seed = topic(
            id = "seed_fast",
            title = "Shree Shiv Tandav Bhajan",
            artist = "Hariharan",
            durationSec = 310L,
            album = "Sacred Echoes",
        )
        val relatedBatch = listOf(
            "Gayatri Mantra", "Ganesh Aarti", "Durga Chalisa", "Mahamrityunjaya Mantra",
            "Krishna Kirtan", "Saraswati Stotram", "Achyutam Bhajan", "Karpur Gauram Aarti",
        ).mapIndexed { i, title ->
            topic("rel_${i + 1}", title, "Singer A$i", durationSec = 240L + i, album = "Devotion $i")
        }
        val genreBatch = listOf(
            "Sai Baba Bhajan", "Kali Chalisa", "Lakshmi Aarti", "Surya Mantra",
            "Aigiri Nandini Stotram", "Radha Kirtan", "Meera Bhajan", "Narasimha Mantra",
        ).mapIndexed { i, title ->
            topic("gen_${i + 1}", title, "Singer B$i", durationSec = 250L + i, album = "Temple Vol $i")
        }
        val pool = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(relatedProvider = {
                    delay(15L)
                    relatedBatch
                }),
                GenreLanguageNeighbourSource(querySearchProvider = {
                    delay(20L)
                    genreBatch
                }),
                SameReleaseCandidateSource(releaseTracksProvider = {
                    delay(10_000L)
                    emptyList()
                }),
            ),
            perSourceTimeoutMs = 1_800L,
            overallDeadlineMs = 2_200L,
            enableEarlyQuorum = true,
            earlyQuorumMinCandidates = 14,
            earlyQuorumGraceMs = 80L,
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { LibraryData() },
        )
        val engine = DefaultRecommendationEngine(pool, profileRepo)

        val t0 = System.nanoTime()
        val res = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 15)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L

        assertTrue("Early quorum must finish well below 2.2s overall deadline (was ${elapsedMs}ms)", elapsedMs < 1_800L)
        assertTrue("Queue must be populated with similar studio tracks", res.candidates.size >= 8)
        assertFalse(res.candidates.any { it.song.id == seed.id })
    }

    @Test
    fun secularGenreNeighbourBuildsNonEmptyQueryAcrossFilmAndMoodTracks() {
        val source = GenreLanguageNeighbourSource(querySearchProvider = { emptyList() })
        val req = CandidateSourceRequest(
            request = RecommendationRequest(requestId = "req_sec", mode = RecommendationMode.AUTOPLAY_REFILL),
            seedFeatures = emptyList(),
            userProfile = UserTasteSnapshot(),
            maxCandidates = 20,
            deadlineMillis = System.currentTimeMillis() + 2_000L,
        )

        val filmFeatures = TrackFeatureRecord(
            canonicalRecordingId = "c_film",
            providerItemId = "s_film",
            title = "Tum Hi Ho",
            durationMs = 262_000L,
            musicTypes = mapOf(MusicType.FILM_SONG to 0.9),
            languageIds = mapOf("hi" to 0.9),
        )
        val filmQuery = source.buildGenreQuery(filmFeatures, req)
        assertNotNull("Secular Hindi film song must yield a non-null genre query", filmQuery)
        assertTrue(filmQuery!!.contains("bollywood", ignoreCase = true))
        assertTrue(filmQuery.contains("Hindi", ignoreCase = true))

        val moodFeatures = TrackFeatureRecord(
            canonicalRecordingId = "c_mood",
            providerItemId = "s_mood",
            title = "Midnight Breeze",
            durationMs = 210_000L,
            moodIds = mapOf("romantic" to 0.85),
            languageIds = mapOf("en" to 0.9),
        )
        val moodQuery = source.buildGenreQuery(moodFeatures, req)
        assertNotNull("Mood-tagged song without explicit genre must still build a G5 query", moodQuery)
        assertTrue(moodQuery!!.contains("romantic", ignoreCase = true))
    }

    @Test
    fun singleWordCompositionAndHonorificVariantsAreDetected() {
        val honorific = topic("single_a", "Shree Shiva", "Singer Alpha")
        val bare = topic("single_b", "Shiva", "Singer Beta")
        val differentA = topic("diff_a", "Kesariya", "Arijit Singh")
        val differentB = topic("diff_b", "Baarish", "Atif Aslam")
        val shortHindiA = topic("short_a", "Tu Hi Hai", "Arijit Singh")
        val shortHindiB = topic("short_b", "Jo Hai", "Atif Aslam")

        assertTrue(RecordingIdentityResolver.isSameCompositionTitle(honorific, bare))
        assertFalse(RecordingIdentityResolver.isSameCompositionTitle(differentA, differentB))
        assertFalse(RecordingIdentityResolver.isSameCompositionTitle(shortHindiA, shortHindiB))
    }

    @Test
    fun instantTrackCatalogMatchesVerifiedStudioGenreAndRejectsDuplicatesUnderTwoMs() {
        InstantTrackCatalog.clear()
        try {
            val devotionalSeed = topic("seed_hc", "Shree Hanuman Chalisa", "Hariharan", durationSec = 580L)
            val bollywoodSeed = topic("seed_bw", "Kesariya", "Arijit Singh", durationSec = 268L, album = "Brahmastra")
            val unverifiedVideo = Song(
                id = "omv_unverified",
                title = "Gayatri Mantra (Official Video)",
                artist = "Random Channel",
                durationSec = 310L,
                isTopicRelease = false,
                rawUploaderName = "Random Channel",
            )
            InstantTrackCatalog.seed(
                listOf(
                    devotionalSeed,
                    topic("dup_hc", "Hanuman Chalisa", "Gulshan Kumar", durationSec = 570L),
                    topic("ok_bhajan", "Shiv Tandav Stotram", "Shankar Mahadevan", durationSec = 410L, album = "Sacred Chants"),
                    topic("ok_aarti", "Om Jai Jagdish Hare Aarti", "Anuradha Paudwal", durationSec = 320L, album = "Evening Aarti"),
                    topic("club_mismatch", "Midnight Club Party Mix", "DJ Electro", durationSec = 215L, album = "Club Nights"),
                    topic("ok_bw_1", "Tum Hi Ho (From Aashiqui 2)", "Mithoon", durationSec = 262L, album = "Aashiqui 2"),
                    topic("ok_bw_2", "Agar Tum Saath Ho (From Tamasha)", "Shreya Ghoshal", durationSec = 341L, album = "Tamasha"),
                    unverifiedVideo,
                ),
            )

            // Unverified OMV/UGC must never enter the catalog or results
            assertFalse(InstantTrackCatalog.snapshot().any { it.id == "omv_unverified" })

            val devMatches = InstantTrackCatalog.matchSimilar(seed = devotionalSeed, limit = 10)
            val devIds = devMatches.map { it.id }.toSet()
            assertTrue("ok_bhajan" in devIds && "ok_aarti" in devIds)
            assertFalse("dup_hc" in devIds)
            assertFalse("club_mismatch" in devIds)
            assertFalse("omv_unverified" in devIds)
            assertFalse("seed_hc" in devIds)

            val bwMatches = InstantTrackCatalog.matchSimilar(seed = bollywoodSeed, limit = 10)
            val bwIds = bwMatches.map { it.id }.toSet()
            assertTrue("ok_bw_1" in bwIds && "ok_bw_2" in bwIds)
            assertFalse("seed_bw" in bwIds)
        } finally {
            InstantTrackCatalog.clear()
        }
    }

    @Test
    fun innertubeAtvTrackWithoutSquareDimensionsStillClassifiesAsStudioOriginal() {
        val radioJson = """
        {
          "contents": {
            "singleColumnMusicWatchNextResultsRenderer": {
              "tabbedRenderer": {
                "watchNextTabbedResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "title": "Up next",
                      "content": {
                        "musicQueueRenderer": {
                          "content": {
                            "playlistPanelRenderer": {
                              "contents": [{
                                "playlistPanelVideoRenderer": {
                                  "videoId": "atvNoDim001",
                                  "title": { "runs": [{ "text": "Namo Namo" }] },
                                  "longBylineText": { "runs": [{ "text": "Amit Trivedi" }, { "text": " • " }, { "text": "Kedarnath" }] },
                                  "lengthText": { "runs": [{ "text": "5:28" }] },
                                  "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/plainart" }] },
                                  "navigationEndpoint": {
                                    "watchEndpoint": {
                                      "videoId": "atvNoDim001",
                                      "watchEndpointMusicSupportedConfigs": {
                                        "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
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
                  }]
                }
              }
            }
          }
        }
        """.trimIndent()

        val (radioSongs, _) = YouTubeMusicInnertube.parseNextRadioJson(JsonParser.`object`().from(radioJson))
        assertEquals(1, radioSongs.size)
        val radioSong = radioSongs.single()
        assertTrue(radioSong.isTopicRelease)
        assertTrue(radioSong.isYouTubeMusicCatalog)
        assertTrue(radioSong.hasSquareArtwork)
        assertTrue(RecordingIdentityResolver.resolve(radioSong).releaseClassification.isVerifiedOriginalAudio)

        val searchJson = """
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
                              "playlistItemData": { "videoId": "atvSearch02" },
                              "flexColumns": [
                                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Shayad" }] } } },
                                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Pritam" }, { "text": " • " }, { "text": "Love Aaj Kal" }, { "text": " • " }, { "text": "4:08" }] } } }
                              ],
                              "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/searchart" }] } } },
                              "overlay": {
                                "musicItemThumbnailOverlayRenderer": {
                                  "content": {
                                    "musicPlayButtonRenderer": {
                                      "playNavigationEndpoint": {
                                        "watchEndpoint": {
                                          "videoId": "atvSearch02",
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
                                          }
                                        }
                                      }
                                    }
                                  }
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

        val searchSongs = YouTubeMusicInnertube.parseSearchOrBrowseSongsJson(JsonParser.`object`().from(searchJson))
        assertEquals(1, searchSongs.size)
        val sSong = searchSongs.single()
        assertTrue(sSong.isTopicRelease)
        assertTrue(sSong.hasSquareArtwork)
        assertTrue(RecordingIdentityResolver.resolve(sSong).releaseClassification.isVerifiedOriginalAudio)
    }
}
