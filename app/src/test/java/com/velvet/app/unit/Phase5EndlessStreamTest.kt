package com.velvet.app.unit

import com.grack.nanojson.JsonParser
import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.data.YouTubeMusicInnertube
import com.velvet.app.diagnostics.QueueHealthMonitor
import com.velvet.app.playback.QueueManager
import com.velvet.app.recommendation.candidates.CandidatePoolBuilder
import com.velvet.app.recommendation.candidates.GenreLanguageNeighbourSource
import com.velvet.app.recommendation.candidates.InstantTrackCatalog
import com.velvet.app.recommendation.candidates.LibraryTrackCandidateSource
import com.velvet.app.recommendation.candidates.RollingCandidatePool
import com.velvet.app.recommendation.candidates.SeedRelatedCandidateSource
import com.velvet.app.recommendation.engine.AutoplayRefillCoordinator
import com.velvet.app.recommendation.engine.DefaultRecommendationEngine
import com.velvet.app.recommendation.engine.QueueRefillSnapshot
import com.velvet.app.recommendation.engine.RefillDecision
import com.velvet.app.recommendation.engine.RefillPolicy
import com.velvet.app.recommendation.engine.RunwayPolicy
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.recommendation.vibe.ArtistLanguageRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase P5 verification (C4):
 * - YTM /next initial & continuation paging JSON parsing (`nextRadioContinuationData`).
 * - Finite paged source + RollingCandidatePool + anchor stepping sustaining 500 consecutive
 *   advances with ZERO repeated compositions and ZERO empty stalls.
 * - Library rediscovery capped <= 25% per batch when discovery tracks exist.
 */
class Phase5EndlessStreamTest {

    @Before
    fun resetState() {
        QueueHealthMonitor.clearForTest()
        InstantTrackCatalog.clear()
        ArtistLanguageRegistry.clearForTest()
    }

    private fun studioSong(
        id: String,
        title: String,
        artist: String,
        album: String = "Hindi Classics",
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        durationSec = 235L,
        album = album,
        isTopicRelease = true,
        rawUploaderName = "$artist - Topic",
        preferredMusicSource = true,
        isYouTubeMusicCatalog = true,
        hasSquareArtwork = true,
    )

    private fun buildUniqueHindiCatalog(count: Int): List<Song> {
        val partA = listOf(
            "Nayi", "Meethi", "Gehri", "Chupke", "Pehli", "Sunehri", "Anjani", "Bheegi",
            "Rang", "Chandni", "Pyaari", "Halke", "Dhalti", "Subah", "Shaam", "Sard",
            "Khwabi", "Uljhi", "Roshni", "Sargam", "Dastaan", "Aashiqui", "Dhadkan", "Muskaan",
        )
        val partB = listOf(
            "Baarish", "Raatein", "Hawayein", "Kahaani", "Parchhai", "Manzil", "Khamoshi",
            "Yaadein", "Sapne", "Duniya", "Safar", "Awaaz", "Tarana", "Geet", "Nazar",
            "Pal", "Lamha", "Wada", "Dil", "Prem", "Ishq", "Saath", "Meet",
        )
        val artists = listOf(
            "Kumar Sanu", "Udit Narayan", "Alka Yagnik", "Sonu Nigam", "Abhijeet",
            "Kavita Krishnamurthy", "Sadhana Sargam", "Babul Supriyo", "Lata Mangeshkar",
            "Kishore Kumar", "Mohammed Rafi", "Asha Bhosle", "Shaan", "KK",
            "Arijit Singh", "Shreya Ghoshal", "Atif Aslam", "Mohit Chauhan", "Sunidhi Chauhan", "Jubin Nautiyal",
        )
        val out = ArrayList<Song>(count)
        var idx = 0
        for (i in partA.indices) {
            for (j in partB.indices) {
                if (out.size >= count) return out
                // Combine 2 words + unique 4-letter code so canonicalCompositionTitleKey stays strictly unique
                val code = "${'A' + (idx / 26) % 26}${'a' + (idx % 26)}${'A' + ((idx * 7) % 26)}${'z' - (idx % 26)}"
                out.add(
                    studioSong(
                        id = "p5_track_$idx",
                        title = "${partA[i]} ${partB[j]} $code",
                        artist = artists[idx % artists.size],
                        album = "Bollywood Anthology",
                    ),
                )
                idx++
            }
        }
        return out
    }

    @Test
    fun parseNextRadioAndContinuationPagesExtractTokensAndRejectOmvs() {
        val initialJson = """
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
                                  "videoId": "atvSong0001",
                                  "title": { "runs": [{"text": "Pehla Nasha"}] },
                                  "longBylineText": { "runs": [{"text": "Udit Narayan"}] },
                                  "lengthText": { "runs": [{"text": "4:50"}] },
                                  "thumbnail": { "thumbnails": [{"url": "https://lh3.googleusercontent.com/a=w120-h120", "width": 120, "height": 120}] },
                                  "navigationEndpoint": {
                                    "watchEndpoint": {
                                      "videoId": "atvSong0001",
                                      "watchEndpointMusicSupportedConfigs": {
                                        "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
                                      }
                                    }
                                  }
                                }
                              }],
                              "continuations": [{
                                "nextRadioContinuationData": { "continuation": "TOKEN_PAGE_2" }
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

        val page1 = YouTubeMusicInnertube.parseNextRadioPageJson(JsonParser.`object`().from(initialJson), excludeVideoId = "seed0000001")
        assertEquals(listOf("atvSong0001"), page1.songs.map { it.id })
        assertEquals("TOKEN_PAGE_2", page1.continuationToken)

        val contJson = """
        {
          "continuationContents": {
            "playlistPanelContinuation": {
              "contents": [
                {
                  "playlistPanelVideoRenderer": {
                    "videoId": "atvSong0002",
                    "title": { "runs": [{"text": "Chura Liya Hai Tumne"}] },
                    "longBylineText": { "runs": [{"text": "Asha Bhosle"}] },
                    "lengthText": { "runs": [{"text": "4:45"}] },
                    "thumbnail": { "thumbnails": [{"url": "https://lh3.googleusercontent.com/b=w120-h120", "width": 120, "height": 120}] },
                    "navigationEndpoint": {
                      "watchEndpoint": {
                        "videoId": "atvSong0002",
                        "watchEndpointMusicSupportedConfigs": {
                          "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
                        }
                      }
                    }
                  }
                },
                {
                  "playlistPanelVideoRenderer": {
                    "videoId": "omvReject01",
                    "title": { "runs": [{"text": "Random Video"}] },
                    "longBylineText": { "runs": [{"text": "Channel"}] },
                    "lengthText": { "runs": [{"text": "3:30"}] },
                    "thumbnail": { "thumbnails": [{"url": "https://i.ytimg.com/vi/omvReject01/hqdefault.jpg", "width": 480, "height": 360}] },
                    "navigationEndpoint": {
                      "watchEndpoint": {
                        "videoId": "omvReject01",
                        "watchEndpointMusicSupportedConfigs": {
                          "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_OMV" }
                        }
                      }
                    }
                  }
                }
              ],
              "continuations": [{
                "nextRadioContinuationData": { "continuation": "TOKEN_PAGE_3" }
              }]
            }
          }
        }
        """.trimIndent()

        val page2 = YouTubeMusicInnertube.parseContinuationRadioPageJson(JsonParser.`object`().from(contJson), excludeVideoId = "seed0000001")
        assertEquals(listOf("atvSong0002"), page2.songs.map { it.id })
        assertEquals("TOKEN_PAGE_3", page2.continuationToken)
    }

    @Test
    fun finitePagedFakeSourceSustains500ConsecutiveAdvancesWithZeroRepeatedCompositions() = runBlocking {
        // 552 unique compositions delivered in finite chunks of 14 items per page
        val catalog = buildUniqueHindiCatalog(552)
        val pages = catalog.chunked(14)
        val pageCursor = AtomicInteger(0)
        val seed = studioSong("p5_seed_0", "Tum Hi Ho Initial", "Arijit Singh")

        val poolBuilder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(
                    relatedProvider = {
                        // Return 2 finite continuation pages at a time so queue always gets enough diversity without blowing quota
                        val pIdx = pageCursor.getAndUpdate { (it + 2).coerceAtMost(pages.size) }
                        (pages.getOrElse(pIdx) { emptyList() } + pages.getOrElse(pIdx + 1) { emptyList() })
                    },
                ),
            ),
            cacheTtlMs = 0L,
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { LibraryData() },
        )
        val engine = DefaultRecommendationEngine(poolBuilder, profileRepo)
        val rollingPool = RollingCandidatePool(maxPoolSize = RollingCandidatePool.MAX_POOL_SIZE)
        val coordinator = AutoplayRefillCoordinator(
            engine = engine,
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 0L,
            ),
            rollingPool = rollingPool,
        )

        val qm = QueueManager()
        qm.start(listOf(seed), startIndex = 0)
        val played = ArrayList<Song>(500)

        for (step in 1..500) {
            if (qm.upNext.size < RunwayPolicy.REFILL_BELOW) {
                val snap = QueueRefillSnapshot(
                    currentSong = qm.current,
                    upcomingSongs = qm.upNext.map { it.song },
                    historySongs = qm.history,
                    queueVersion = qm.queueVersion,
                    isAutoplayEnabled = true,
                    fallbackAnchorPool = catalog.take(10),
                )
                val decision = coordinator.requestRefill(snap, { snap }, force = qm.upNext.isEmpty())
                if (decision is RefillDecision.AppendSongs && decision.songs.isNotEmpty()) {
                    qm.appendAutoplay(decision.songs)
                }
            }
            assertTrue("Queue ran empty before advance #$step", qm.upNext.isNotEmpty())
            val next = qm.advance(manual = false)
            assertNotNull("Advance #$step returned null", next)
            played.add(next!!)
        }

        val compositionKeys = played.map { RecordingIdentityResolver.canonicalCompositionTitleKey(it) }.toSet()
        assertEquals("Must complete all 500 advances without running dry", 500, played.size)
        assertEquals("Zero composition repeats allowed across 500 consecutive advances", 500, compositionKeys.size)
        assertTrue("Rolling pool must remain <= 200 entries", rollingPool.size() <= RollingCandidatePool.MAX_POOL_SIZE)
    }

    @Test
    fun anchorSteppingRecoversFromDryPrimarySeedWhileHoldingSessionVibeLock() = runBlocking {
        val drySeed = studioSong("dry_seed", "Tujhe Dekha To", "Kumar Sanu", album = "DDLJ")
        val fallbackAnchor = studioSong("backup_anchor", "Pehla Nasha", "Udit Narayan", album = "90s Classics")
        val backupResults = (0 until 10).map { i ->
            studioSong("stepped_$i", "Meethi Baatein Alpha$i", "Alka Yagnik", album = "90s Classics")
        }
        val alienEnglish = studioSong("en_clash", "English Neon Lights", "Taylor Swift", album = "Pop Hits")

        val poolBuilder = CandidatePoolBuilder(
            sources = listOf(
                SeedRelatedCandidateSource(
                    relatedProvider = { anchor ->
                        when (anchor.id) {
                            drySeed.id -> emptyList() // primary seed exhausted!
                            fallbackAnchor.id -> backupResults + alienEnglish
                            else -> emptyList()
                        }
                    },
                ),
            ),
            cacheTtlMs = 0L,
        )
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
            libraryDataProvider = { LibraryData() },
        )
        val coordinator = AutoplayRefillCoordinator(
            engine = DefaultRecommendationEngine(poolBuilder, profileRepo),
            policy = RefillPolicy(
                targetUpcomingBuffer = RunwayPolicy.TARGET_AHEAD,
                lowWatermarkTrigger = RunwayPolicy.REFILL_BELOW,
                refillBatchSize = RunwayPolicy.MAX_BATCH,
                minCooldownMs = 0L,
            ),
        )

        val snap = QueueRefillSnapshot(
            currentSong = drySeed,
            upcomingSongs = emptyList(),
            historySongs = listOf(fallbackAnchor),
            queueVersion = 1L,
            isAutoplayEnabled = true,
            allowMixedLanguages = false,
            fallbackAnchorPool = listOf(fallbackAnchor),
        )
        val decision = coordinator.requestRefill(snap, { snap }, force = true)
        assertTrue("Anchor stepping should recover AppendSongs, got $decision", decision is RefillDecision.AppendSongs)
        val songs = (decision as RefillDecision.AppendSongs).songs
        assertTrue(songs.isNotEmpty())
        assertTrue(songs.all { it.id.startsWith("stepped_") })
        assertFalse("Stepped anchor still enforces locked Hindi SessionVibe", songs.any { it.id == alienEnglish.id })
    }

    @Test
    fun libraryRediscoveryCapLimitsLibraryOnlyTracksTo25PercentWhenDiscoveryExists() = runBlocking {
        val seed = studioSong("lib_seed", "Agar Tum Saath Ho", "Arijit Singh")
        val discovery = buildUniqueHindiCatalog(20).map { it.copy(id = "disc_${it.id}") }
        val libraryTracks = buildUniqueHindiCatalog(20).mapIndexed { idx, s ->
            s.copy(id = "lib_$idx", title = "Purane Geet Melody${'A' + idx}a")
        }

        val engine = DefaultRecommendationEngine(
            candidatePoolBuilder = CandidatePoolBuilder(
                sources = listOf(
                    SeedRelatedCandidateSource(relatedProvider = { discovery }),
                    LibraryTrackCandidateSource(libraryTracksProvider = { libraryTracks }),
                ),
                cacheTtlMs = 0L,
            ),
            userProfileRepository = DefaultUserProfileRepository(
                eventRepository = RecommendationEventRepository(backend = InMemoryEventBackend()),
                libraryDataProvider = { LibraryData(liked = libraryTracks) },
            ),
        )

        val res = engine.nextSimilar(seed, mode = RecommendationMode.AUTOPLAY_REFILL, limit = 16)
        assertEquals(16, res.candidates.size)
        val libOnlyCount = res.candidates.count { cand ->
            cand.sourceIds.isNotEmpty() && cand.sourceIds.all { it == "g6_library" || it == "g0_instant_catalog" }
        }
        assertTrue("Expected <= 4 library-only rediscovery tracks out of 16 (<=25%), got $libOnlyCount", libOnlyCount <= 4)
    }
}
