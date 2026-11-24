package com.velvet.app.unit

import com.velvet.app.data.Library
import com.velvet.app.data.LibraryActionHook
import com.velvet.app.data.ListenContext
import com.velvet.app.data.ListenEndReason
import com.velvet.app.data.ListenEvent
import com.velvet.app.data.Song
import com.velvet.app.recommendation.events.EventDeduplicator
import com.velvet.app.recommendation.events.EventRecorder
import com.velvet.app.recommendation.events.FileJsonlEventBackend
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.model.PlaybackAttribution
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RecommendationEventTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val songA = Song("vidA", "Kesariya", "Arijit Singh", 268, album = "Brahmastra")
    private val songB = Song("vidB", "Tum Hi Ho", "Arijit Singh", 262, album = "Aashiqui 2")
    private val songShort = Song("vidS", "Short Interlude", "Pritam", 30)

    private fun newRepo(
        backend: com.velvet.app.recommendation.events.EventStorageBackend = InMemoryEventBackend(),
        clock: AtomicLong = AtomicLong(1_000_000L),
    ): RecommendationEventRepository =
        RecommendationEventRepository(backend = backend, clock = { clock.get() })

    @Test
    fun deduplicatorAndRepositoryEnforceIdempotencyAcrossRestarts() {
        val dedupe = EventDeduplicator(maxEntries = 2)
        assertTrue(dedupe.tryAcquire("e1", "k1"))
        assertFalse(dedupe.tryAcquire("e1", "k_other"))
        assertFalse(dedupe.tryAcquire("e2", "k1"))
        assertTrue(dedupe.tryAcquire("e2", "k2"))
        // Adding 3rd entry evicts e1/k1 from bounded LRU
        assertTrue(dedupe.tryAcquire("e3", "k3"))
        assertTrue(dedupe.tryAcquire("e1", "k1"))

        val dir = tmp.newFolder("idempotency_db")
        val repo1 = newRepo(FileJsonlEventBackend(dir))
        val ev1 = RecommendationEvent(
            eventId = "evt-1",
            eventType = RecommendationEventType.SEARCH_SUBMITTED,
            occurredAtMillis = 10_000L,
            normalizedQuery = "arijit",
            idempotencyKey = "search:1",
        )
        assertTrue(repo1.record(ev1))
        assertFalse(repo1.record(ev1.copy(eventId = "evt-2"))) // same idempotencyKey blocked in memory
        assertTrue(repo1.flushBlocking())
        assertEquals(1, repo1.eventCount())

        // Recreate repo from disk — backend-level idempotency check prevents duplicate write
        val repo2 = newRepo(FileJsonlEventBackend(dir))
        assertTrue(repo2.record(ev1.copy(eventId = "evt-3"))) // new in-memory deduplicator accepts
        assertTrue(repo2.flushBlocking())
        assertEquals(1, repo2.eventCount())
    }

    @Test
    fun fileBackendToleratesMalformedLinePrunesAndScrubsQueries() {
        val dir = tmp.newFolder("jsonl_store")
        val clock = AtomicLong(TimeUnit.DAYS.toMillis(200))
        val repo = newRepo(FileJsonlEventBackend(dir), clock)

        val dayMillis = TimeUnit.DAYS.toMillis(1)
        val eOld = RecommendationEvent(
            eventId = "old",
            eventType = RecommendationEventType.SEARCH_SUBMITTED,
            occurredAtMillis = clock.get() - 190 * dayMillis,
            queryId = "q-old",
            normalizedQuery = "secret query 1",
        )
        val eMid = RecommendationEvent(
            eventId = "mid",
            eventType = RecommendationEventType.SEARCH_RESULT_CLICKED,
            occurredAtMillis = clock.get() - 2 * dayMillis,
            queryId = "q-mid",
            normalizedQuery = "secret query 2",
            providerItemId = "vidA",
        )
        val eNew = RecommendationEvent(
            eventId = "new",
            eventType = RecommendationEventType.TRACK_LIKED,
            occurredAtMillis = clock.get() - 1_000L,
            providerItemId = "vidB",
        )
        assertEquals(3, repo.recordAll(listOf(eOld, eMid, eNew)))
        assertTrue(repo.flushBlocking())

        // Append truncated crash line to verify tolerance
        File(dir, "recommendation_events.jsonl").appendText("{\"eventId\":\"truncated_crash")

        val reloaded = newRepo(FileJsonlEventBackend(dir), clock)
        assertEquals(3, reloaded.eventCount())
        assertTrue(reloaded.queryRecent().all { it.schemaVersion == RecommendationEvent.CURRENT_SCHEMA_VERSION })

        reloaded.pruneOldEvents(retentionDays = 180L, maxEvents = 10)
        assertTrue(reloaded.flushBlocking())
        val afterPrune = reloaded.queryRecent()
        assertEquals(listOf("mid", "new"), afterPrune.map { it.eventId })

        reloaded.scrubSearchQueries()
        assertTrue(reloaded.flushBlocking())
        val afterScrub = reloaded.queryRecent()
        assertTrue(afterScrub.all { it.normalizedQuery == null })
        assertEquals("q-mid", afterScrub.first { it.eventId == "mid" }.queryId)
    }

    @Test
    fun legacyListenEventsMigrateIdempotentlyWithoutMutatingSource() {
        val dir = tmp.newFolder("legacy_migration")
        val legacy = listOf(
            ListenEvent(
                songGroupId = "grp_1",
                videoId = "v1",
                artistSet = listOf("arijit singh"),
                movie = "aashiqui 2",
                year = null,
                language = null,
                startedAt = 1000L,
                playedMs = 200_000L,
                durationMs = 220_000L,
                completionPct = 0.91f,
                endReason = ListenEndReason.COMPLETED,
                context = ListenContext.AUTOPLAY,
            ),
            ListenEvent(
                songGroupId = "grp_2",
                videoId = "v2",
                startedAt = 2000L,
                playedMs = 8_000L,
                durationMs = 200_000L,
                completionPct = 0.04f,
                endReason = ListenEndReason.SKIP_EARLY,
                context = ListenContext.SEARCH,
            ),
            ListenEvent(
                songGroupId = "grp_3",
                videoId = "v3",
                startedAt = 3000L,
                playedMs = 150_000L,
                durationMs = 150_000L,
                completionPct = 1.0f,
                endReason = ListenEndReason.REPLAY,
                context = ListenContext.PLAYLIST,
                liked = true,
            ),
        )

        val repo1 = newRepo(FileJsonlEventBackend(dir))
        assertEquals(3, repo1.migrateLegacyListenEventsIfNeeded(legacy))
        // Second call in same instance is a no-op
        assertEquals(0, repo1.migrateLegacyListenEventsIfNeeded(legacy))

        // Fresh repo pointing to same disk store also remains idempotent
        val repo2 = newRepo(FileJsonlEventBackend(dir))
        assertEquals(0, repo2.migrateLegacyListenEventsIfNeeded(legacy))

        val events = repo2.queryRecent()
        assertEquals(3, events.size)
        val replayEvt = events.single { it.providerItemId == "v3" }
        assertEquals(RecommendationEventType.TRACK_REPLAYED_MANUALLY, replayEvt.eventType)
        assertEquals(RecommendationSurface.LIBRARY, replayEvt.surface)
        assertTrue("legacy_replay_unverified_repeat" in replayEvt.sessionContextTags)
        assertTrue("legacy_liked_true" in replayEvt.sessionContextTags)
        assertNull(replayEvt.normalizedQuery)
        assertNull(replayEvt.repeatMode)
    }

    @Test
    fun playbackLifecycleEmitsMeaningfulListenAndNaturalCompletion() {
        val clock = AtomicLong(1_000_000L)
        val repo = newRepo(clock = clock)
        val recorder = EventRecorder(repo) { clock.get() }

        recorder.onPlayRequested(songA)
        clock.addAndGet(500L)
        recorder.onIsPlayingChanged(isPlaying = true, song = songA, durationMs = 268_000L)

        // Advance 35s (>30s meaningful listen threshold)
        clock.addAndGet(35_000L)
        recorder.onPlaybackProgress(songA.id, positionMs = 35_000L, durationMs = 268_000L)

        // Finish naturally
        clock.addAndGet(230_000L)
        recorder.onMediaItemTransitionInService(
            newSong = songB,
            transitionReason = EventRecorder.TRANSITION_AUTO,
            repeatModeLabel = "OFF",
            outgoingPositionMs = 0L,
            outgoingDurationMs = 262_000L,
            isPlayingNow = true,
        )
        assertTrue(repo.flushBlocking())

        val types = repo.queryRecent().map { it.eventType }
        assertTrue(RecommendationEventType.TRACK_PLAY_REQUESTED in types)
        assertTrue(RecommendationEventType.TRACK_PLAY_STARTED in types)
        assertTrue(RecommendationEventType.TRACK_MEANINGFUL_LISTEN in types)
        assertTrue(RecommendationEventType.TRACK_COMPLETED in types)
        assertFalse(RecommendationEventType.TRACK_SKIPPED_EARLY in types)

        val completed = repo.queryRecent().single { it.eventType == RecommendationEventType.TRACK_COMPLETED }
        assertEquals(songA.id, completed.providerItemId)
        assertTrue((completed.playedMs ?: 0L) >= 265_000L)
        assertNotNull(completed.nextRecordingId)

        // Short track meaningful listen threshold: 60% of 30s = 18s
        recorder.onPlayRequested(songShort)
        recorder.onIsPlayingChanged(isPlaying = true, song = songShort, durationMs = 30_000L)
        clock.addAndGet(19_000L)
        recorder.onPlaybackProgress(songShort.id, positionMs = 19_000L, durationMs = 30_000L)
        assertTrue(repo.flushBlocking())
        val meaningfulEvents = repo.queryRecent(types = setOf(RecommendationEventType.TRACK_MEANINGFUL_LISTEN))
        assertEquals(2, meaningfulEvents.size)
        assertEquals(songShort.id, meaningfulEvents.last().providerItemId)
    }

    @Test
    fun earlySkipLateSkipAndBackgroundAutoAdvanceAreDistinguished() {
        val clock = AtomicLong(2_000_000L)
        val repo = newRepo(clock = clock)
        val recorder = EventRecorder(repo) { clock.get() }

        // Early skip (<30s)
        recorder.onPlayRequested(songA)
        recorder.onIsPlayingChanged(isPlaying = true, song = songA, durationMs = 268_000L)
        clock.addAndGet(12_000L)
        recorder.onExplicitTrackAdvance(
            outgoingSong = songA,
            nextSong = songB,
            positionMs = 12_000L,
            durationMs = 268_000L,
            repeatModeLabel = "OFF",
            transitionReason = "NEXT",
        )

        // Late skip (>=30s, <85%) followed by background service transition
        recorder.onIsPlayingChanged(isPlaying = true, song = songB, durationMs = 262_000L)
        clock.addAndGet(45_000L)
        recorder.onExplicitTrackAdvance(
            outgoingSong = songB,
            nextSong = songShort,
            positionMs = 45_000L,
            durationMs = 262_000L,
            repeatModeLabel = "ALL",
            transitionReason = "NEXT",
        )
        assertTrue(repo.flushBlocking())

        val events = repo.queryRecent()
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_SKIPPED_EARLY && it.providerItemId == songA.id })
        assertEquals(0, events.count { it.eventType == RecommendationEventType.TRACK_MEANINGFUL_LISTEN && it.providerItemId == songA.id })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_SKIPPED_LATE && it.providerItemId == songB.id })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_MEANINGFUL_LISTEN && it.providerItemId == songB.id })
    }

    @Test
    fun autoRepeatOneIsSeparatedFromManualReplay() {
        val clock = AtomicLong(3_000_000L)
        val repo = newRepo(clock = clock)
        val recorder = EventRecorder(repo) { clock.get() }

        recorder.onPlayRequested(songA)
        recorder.onIsPlayingChanged(isPlaying = true, song = songA, durationMs = 268_000L)
        clock.addAndGet(268_000L)
        recorder.onMediaItemTransitionInService(
            newSong = songA,
            transitionReason = EventRecorder.TRANSITION_REPEAT,
            repeatModeLabel = "ONE",
            outgoingPositionMs = 268_000L,
            outgoingDurationMs = 268_000L,
            isPlayingNow = true,
        )

        // Now user manually presses previous to restart
        clock.addAndGet(40_000L)
        recorder.onManualReplayCurrent(
            song = songA,
            playedMsBeforeRestart = 40_000L,
            durationMs = 268_000L,
            repeatModeLabel = "ONE",
        )
        assertTrue(repo.flushBlocking())

        val events = repo.queryRecent()
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_REPEATED_AUTOMATICALLY })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_REPLAYED_MANUALLY })
        assertEquals(0, events.count { it.eventType == RecommendationEventType.TRACK_COMPLETED })
    }

    @Test
    fun playbackErrorSuppressesSkipFeedback() {
        val clock = AtomicLong(4_000_000L)
        val repo = newRepo(clock = clock)
        val recorder = EventRecorder(repo) { clock.get() }

        recorder.onPlayRequested(songA)
        recorder.onIsPlayingChanged(isPlaying = true, song = songA, durationMs = 268_000L)
        clock.addAndGet(3_000L)
        recorder.onTrackPlaybackError(songA.id)
        recorder.onExplicitTrackAdvance(
            outgoingSong = songA,
            nextSong = songB,
            positionMs = 3_000L,
            durationMs = 268_000L,
            repeatModeLabel = "OFF",
            transitionReason = "ERROR_RECOVERY",
        )
        assertTrue(repo.flushBlocking())

        val events = repo.queryRecent()
        assertEquals(0, events.count { it.eventType == RecommendationEventType.TRACK_SKIPPED_EARLY })
        assertEquals(0, events.count { it.eventType == RecommendationEventType.TRACK_SKIPPED_LATE })
        assertEquals(0, events.count { it.eventType == RecommendationEventType.TRACK_COMPLETED })
        assertEquals(0, events.count { it.eventType == RecommendationEventType.TRACK_MEANINGFUL_LISTEN })
    }

    @Test
    fun searchAndHomeAttributionPropagatesIntoPlayback() {
        val clock = AtomicLong(5_000_000L)
        val repo = newRepo(clock = clock)
        val recorder = EventRecorder(repo) { clock.get() }

        recorder.recordSearchSubmitted(queryId = "q-1", rawQuery = "  Kesariya Brahmastra  ", searchSessionId = "s-sess-1")
        recorder.recordSearchResultImpression("q-1", "Kesariya Brahmastra", "req-1", 0, songA, "s-sess-1")
        val attr = recorder.recordSearchResultClicked("q-1", "Kesariya Brahmastra", "req-1", 0, songA, "s-sess-1")
        recorder.onPlayRequested(songA, attribution = attr)
        recorder.onIsPlayingChanged(true, songA, 268_000L)

        recorder.recordHomeSongImpression("home-req-1", "quick_picks", RecommendationSurface.HOME_SONG_RAIL, 2, songB)
        recorder.recordHomeCollectionImpression(
            homeVisitRequestId = "home-req-1",
            shelfId = "made_for_you",
            surface = RecommendationSurface.HOME_RADIO_RAIL,
            mode = RecommendationMode.HOME_RADIOS,
            position = 1,
            itemId = songA.id,
        )
        assertTrue(repo.flushBlocking())

        val events = repo.queryRecent()
        val playStarted = events.single { it.eventType == RecommendationEventType.TRACK_PLAY_STARTED }
        assertEquals("q-1", playStarted.queryId)
        assertEquals("kesariya brahmastra", playStarted.normalizedQuery)
        assertEquals("req-1", playStarted.requestId)
        assertEquals(0, playStarted.resultPosition)
        assertEquals("s-sess-1", playStarted.recommendationSessionId)
        assertEquals(RecommendationSurface.SEARCH, playStarted.surface)

        val homeImps = events.filter { it.eventType == RecommendationEventType.AUTOPLAY_CANDIDATE_IMPRESSION }
        assertEquals(2, homeImps.size)
        assertEquals("quick_picks", homeImps[0].shelfId)
        assertEquals(2, homeImps[0].rankPosition)
        assertEquals("made_for_you", homeImps[1].shelfId)
        assertEquals(RecommendationMode.HOME_RADIOS, homeImps[1].mode)
    }

    @Test
    fun autoplayRadioAndQueueDismissalEventsRecordProvenance() {
        val clock = AtomicLong(6_000_000L)
        val repo = newRepo(clock = clock)
        val recorder = EventRecorder(repo) { clock.get() }

        val radioId = recorder.recordRadioStarted(songA, isArtistRadio = false)
        recorder.registerAutoplayCandidates(
            requestId = "ap-req-1",
            candidates = listOf(songB, songShort),
            mode = RecommendationMode.SONG_RADIO,
            radioSessionId = radioId,
        )
        // Only displayed items with provenance log AUTOPLAY_CANDIDATE_IMPRESSION
        recorder.recordAutoplayCandidateImpression(songB, position = 0)
        recorder.recordAutoplayCandidateImpression(songA, position = 1) // not registered candidate -> ignored

        // First radio seed plays -> does not log RADIO_CONTINUED
        recorder.onPlayRequested(songA, isRadioSession = true)
        recorder.onIsPlayingChanged(true, songA, 268_000L)
        clock.addAndGet(268_000L)

        // Autoplay candidate plays -> logs AUTOPLAY_TRACK_STARTED + RADIO_CONTINUED
        recorder.onMediaItemTransitionInService(songB, EventRecorder.TRANSITION_AUTO, "OFF", 0L, 262_000L, true)
        clock.addAndGet(15_000L)
        recorder.onExplicitTrackAdvance(songB, songShort, 15_000L, 262_000L, "OFF", "NEXT")

        recorder.recordQueuePlayNext(songShort)
        recorder.recordQueueAdded(songA)
        recorder.recordQueueRemoved(songShort, wasModelRecommended = true)
        recorder.recordRadioDismissed("USER_CLOSED")
        assertTrue(repo.flushBlocking())

        val events = repo.queryRecent()
        assertEquals(1, events.count { it.eventType == RecommendationEventType.RADIO_STARTED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.RADIO_CONTINUED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.RADIO_DISMISSED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.AUTOPLAY_CANDIDATE_IMPRESSION })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.AUTOPLAY_TRACK_STARTED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.AUTOPLAY_TRACK_SKIPPED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.QUEUE_PLAY_NEXT })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.QUEUE_ADDED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.QUEUE_REMOVED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.RECOMMENDATION_HIDDEN })

        val hidden = events.single { it.eventType == RecommendationEventType.RECOMMENDATION_HIDDEN }
        assertEquals("ap-req-1", hidden.requestId)
        assertEquals(1, hidden.rankPosition)
    }

    @Test
    fun libraryActionHooksRecordLikesPlaylistMutationsAndScrubSearches() {
        val dir = tmp.newFolder("lib_hooks")
        val clock = AtomicLong(7_000_000L)
        val repo = newRepo(FileJsonlEventBackend(dir), clock)
        val recorder = EventRecorder(repo) { clock.get() }
        val lib = Library(tmp.newFolder("lib_store")).apply {
            onRecommendationAction = { action ->
                when (action) {
                    is LibraryActionHook.LikeToggled ->
                        recorder.recordLikeChanged(action.song, action.isNowLiked)
                    is LibraryActionHook.PlaylistSongChanged ->
                        recorder.recordPlaylistMembershipChanged(action.playlistId, action.song, action.added)
                    LibraryActionHook.SearchesCleared ->
                        recorder.clearSearchIntentHistory()
                    LibraryActionHook.ListenEventsCleared ->
                        recorder.clearAllEvents()
                }
            }
        }

        recorder.recordSearchSubmitted("q-priv", "private query")
        lib.toggleLike(songA)
        lib.toggleLike(songA)
        val plId = lib.createPlaylist("My Favs", listOf(songA))
        lib.addToPlaylist(plId, songB)
        lib.removeFromPlaylist(plId, songB.id)
        lib.clearSearches()
        lib.flushBlocking()
        assertTrue(repo.flushBlocking())

        val events = repo.queryRecent()
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_LIKED })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_UNLIKED })
        assertEquals(2, events.count { it.eventType == RecommendationEventType.TRACK_ADDED_TO_PLAYLIST })
        assertEquals(1, events.count { it.eventType == RecommendationEventType.TRACK_REMOVED_FROM_PLAYLIST })
        val searchEvt = events.single { it.eventType == RecommendationEventType.SEARCH_SUBMITTED }
        assertNull(searchEvt.normalizedQuery)
    }
}
