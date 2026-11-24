package com.velvet.app.unit

import com.velvet.app.data.LibraryData
import com.velvet.app.data.Song
import com.velvet.app.recommendation.events.EventStorageBackend
import com.velvet.app.recommendation.events.InMemoryEventBackend
import com.velvet.app.recommendation.events.RecommendationEventRepository
import com.velvet.app.recommendation.features.GenreOntology
import com.velvet.app.recommendation.identity.RecordingIdentityResolver
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import com.velvet.app.recommendation.profile.DefaultUserProfileRepository
import com.velvet.app.recommendation.profile.ExplicitLibraryInputs
import com.velvet.app.recommendation.profile.TasteProfileBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Phase3UserProfileTest {

    private val arijitSong = Song(
        id = "arijit_1",
        title = "Kesariya (Official Audio)",
        artist = "Arijit Singh",
        durationSec = 268,
        isTopicRelease = true,
        rawUploaderName = "Arijit Singh - Topic",
    )
    private val badshahSong = Song(
        id = "badshah_1",
        title = "DJ Waley Babu Party Dance",
        artist = "Badshah",
        durationSec = 210,
        isTopicRelease = true,
        rawUploaderName = "Badshah - Topic",
    )
    private val kishoreSong = Song(
        id = "kishore_1",
        title = "Mere Sapno Ki Rani",
        artist = "Kishore Kumar",
        durationSec = 300,
        isTopicRelease = true,
        rawUploaderName = "Kishore Kumar - Topic",
    )

    private val baseInputs = ExplicitLibraryInputs(
        knownSongsByProviderId = listOf(arijitSong, badshahSong, kishoreSong).associateBy { it.id },
    )

    @Test
    fun snapshotIsDeterministicAndMaintainsDistinctTimeHorizons() {
        val now = TimeUnit.DAYS.toMillis(400)
        val ninetyDaysAgo = now - TimeUnit.DAYS.toMillis(90)
        val oneHourAgo = now - TimeUnit.HOURS.toMillis(1)

        val events = listOf(
            RecommendationEvent(
                eventId = "e-old-1",
                eventType = RecommendationEventType.TRACK_COMPLETED,
                occurredAtMillis = ninetyDaysAgo,
                providerItemId = kishoreSong.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(kishoreSong).canonicalRecordingId,
                artistIds = listOf("kishore kumar"),
                recommendationSessionId = "old-sess",
            ),
            RecommendationEvent(
                eventId = "e-now-1",
                eventType = RecommendationEventType.TRACK_COMPLETED,
                occurredAtMillis = oneHourAgo,
                providerItemId = arijitSong.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(arijitSong).canonicalRecordingId,
                artistIds = listOf("arijit singh"),
                recommendationSessionId = "cur-sess",
            ),
        )

        val builder = TasteProfileBuilder()
        val snap1 = builder.build(events, baseInputs, activeSessionId = "cur-sess", nowMillis = now)
        val snap2 = builder.build(events, baseInputs, activeSessionId = "cur-sess", nowMillis = now)
        assertEquals(snap1, snap2)

        val kishoreLong = snap1.longTerm.artistAffinity("Kishore Kumar")
        val arijitLong = snap1.longTerm.artistAffinity("Arijit Singh")
        assertTrue(arijitLong > kishoreLong)
        // 90-day half life keeps long-term signal substantial (~0.5 of today's un-decayed completion)
        assertTrue(kishoreLong > 0.35)
        // Session horizon decays quickly (2h half-life), keeping old 90d artist negligible in session
        assertEquals(0.0, snap1.session.artistAffinity("Kishore Kumar"), 0.001)
        assertTrue(snap1.session.artistAffinity("Arijit Singh") > 0.8)
    }

    @Test
    fun automaticRepeatOneNeverInflatesPreferencesWhileManualReplayDoes() {
        val now = 2_000_000_000L
        val builder = TasteProfileBuilder()

        val autoRepeatEvents = (1..25).map { i ->
            RecommendationEvent(
                eventId = "rep-$i",
                eventType = RecommendationEventType.TRACK_REPEATED_AUTOMATICALLY,
                occurredAtMillis = now - i * 200_000L,
                providerItemId = badshahSong.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(badshahSong).canonicalRecordingId,
                artistIds = listOf("badshah"),
                recommendationSessionId = "s1",
            )
        }

        val snapAutoOnly = builder.build(autoRepeatEvents, baseInputs, activeSessionId = "s1", nowMillis = now)
        assertEquals(0.0, snapAutoOnly.longTerm.artistAffinity("Badshah"), 0.001)
        assertEquals(0.0, snapAutoOnly.mediumTerm.artistAffinity("Badshah"), 0.001)
        assertEquals(0.0, snapAutoOnly.session.artistAffinity("Badshah"), 0.001)
        assertFalse(RecordingIdentityResolver.resolve(badshahSong).canonicalRecordingId in snapAutoOnly.fatigue.dailyOverplayedRecordingIds)

        val manualReplayEvents = listOf(
            RecommendationEvent(
                eventId = "mrep-1",
                eventType = RecommendationEventType.TRACK_REPLAYED_MANUALLY,
                occurredAtMillis = now - 60_000L,
                providerItemId = arijitSong.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(arijitSong).canonicalRecordingId,
                artistIds = listOf("arijit singh"),
                recommendationSessionId = "s1",
            ),
        )
        val snapManual = builder.build(autoRepeatEvents + manualReplayEvents, baseInputs, activeSessionId = "s1", nowMillis = now)
        assertTrue(snapManual.longTerm.artistAffinity("Arijit Singh") > 0.8)
        assertEquals(0.0, snapManual.longTerm.artistAffinity("Badshah"), 0.001)
    }

    @Test
    fun searchOnlyStaysWeakAndSearchToPlayConvertsStrongly() {
        val now = 3_000_000_000L
        val builder = TasteProfileBuilder()

        // Unconverted query does not pollute long-term profile
        val queryOnly = listOf(
            RecommendationEvent(
                eventId = "s-only-1",
                eventType = RecommendationEventType.SEARCH_SUBMITTED,
                occurredAtMillis = now - 60_000L,
                queryId = "q1",
                normalizedQuery = "punjabi party bhangra",
            ),
        )
        val snapQueryOnly = builder.build(queryOnly, baseInputs, nowMillis = now)
        assertTrue(snapQueryOnly.longTerm.genreWeights.isEmpty())
        assertTrue((snapQueryOnly.searchIntent.queryCategoryWeights[GenreOntology.PUNJABI] ?: 0.0) > 0.0)
        assertEquals(1, snapQueryOnly.searchIntent.unconvertedQueriesCount)
        assertEquals(0, snapQueryOnly.searchIntent.convertedQueriesCount)

        // Search -> click -> meaningful listen promotes converted search boost
        val converted = queryOnly + listOf(
            RecommendationEvent(
                eventId = "s-clk-1",
                eventType = RecommendationEventType.SEARCH_RESULT_CLICKED,
                occurredAtMillis = now - 50_000L,
                queryId = "q1",
                providerItemId = badshahSong.id,
            ),
            RecommendationEvent(
                eventId = "s-mfl-1",
                eventType = RecommendationEventType.TRACK_MEANINGFUL_LISTEN,
                occurredAtMillis = now - 10_000L,
                queryId = "q1",
                providerItemId = badshahSong.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(badshahSong).canonicalRecordingId,
                artistIds = listOf("badshah"),
                recommendationSessionId = "s-conv",
            ),
        )
        val snapConverted = builder.build(converted, baseInputs, activeSessionId = "s-conv", nowMillis = now)
        assertEquals(0, snapConverted.searchIntent.unconvertedQueriesCount)
        assertEquals(1, snapConverted.searchIntent.convertedQueriesCount)
        assertTrue((snapConverted.searchIntent.convertedRecordingBoosts[RecordingIdentityResolver.resolve(badshahSong).canonicalRecordingId] ?: 0.0) > 0.5)
        assertTrue(snapConverted.mediumTerm.artistAffinity("Badshah") > 0.5)
    }

    @Test
    fun singleSkipDoesNotBanGenreOrArtistWhileMultipleSkipsAndNotInterestedActProperly() {
        val now = TimeUnit.DAYS.toMillis(50)
        val builder = TasteProfileBuilder()
        val arijitRec = RecordingIdentityResolver.resolve(arijitSong).canonicalRecordingId

        // 1 early skip on Arijit song -> mild temporary penalty only; NO fatigue, NO artist ban
        val singleSkip = listOf(
            RecommendationEvent(
                eventId = "sk-1",
                eventType = RecommendationEventType.TRACK_SKIPPED_EARLY,
                occurredAtMillis = now - 120_000L,
                providerItemId = arijitSong.id,
                canonicalRecordingId = arijitRec,
                artistIds = listOf("arijit singh"),
                recommendationSessionId = "s1",
            ),
        )
        val snapOneSkip = builder.build(singleSkip, baseInputs, activeSessionId = "s1", nowMillis = now)
        assertFalse(snapOneSkip.isFatiguedOrRemovedInSession(arijitSong))
        assertTrue((snapOneSkip.fatigue.singleSkipPenalties[arijitRec] ?: 0.0) > 0.0)
        assertTrue(snapOneSkip.fatigue.repeatedArtistPenalty.isEmpty())
        assertTrue(snapOneSkip.longTerm.genreWeights.isEmpty()) // Did NOT ban genre

        // 2 independent early skips in 14 days trigger 30-day fatigue on that recording
        val twoSkips = singleSkip + listOf(
            RecommendationEvent(
                eventId = "sk-2",
                eventType = RecommendationEventType.TRACK_SKIPPED_EARLY,
                occurredAtMillis = now - TimeUnit.DAYS.toMillis(2),
                providerItemId = arijitSong.id,
                canonicalRecordingId = arijitRec,
                artistIds = listOf("arijit singh"),
                recommendationSessionId = "s0",
            ),
        )
        val snapTwoSkips = builder.build(twoSkips, baseInputs, activeSessionId = "s1", nowMillis = now)
        assertTrue(snapTwoSkips.isFatiguedOrRemovedInSession(arijitSong))

        // Manual Play Next drives session horizon without corrupting long-term profile if followed by Not Interested on another track
        val explicitEvents = listOf(
            RecommendationEvent(
                eventId = "pn-1",
                eventType = RecommendationEventType.QUEUE_PLAY_NEXT,
                occurredAtMillis = now - 30_000L,
                providerItemId = kishoreSong.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(kishoreSong).canonicalRecordingId,
                artistIds = listOf("kishore kumar"),
                recommendationSessionId = "s1",
            ),
            RecommendationEvent(
                eventId = "ni-1",
                eventType = RecommendationEventType.RECOMMENDATION_NOT_INTERESTED,
                occurredAtMillis = now - 20_000L,
                providerItemId = badshahSong.id,
                canonicalRecordingId = RecordingIdentityResolver.resolve(badshahSong).canonicalRecordingId,
                recommendationSessionId = "s1",
            ),
        )
        val snapExplicit = builder.build(explicitEvents, baseInputs, activeSessionId = "s1", nowMillis = now)
        assertTrue(snapExplicit.session.artistAffinity("Kishore Kumar") > 0.7)
        assertTrue(snapExplicit.isExplicitlyDisliked(badshahSong))
        assertFalse(snapExplicit.isExplicitlyDisliked(arijitSong))
    }

    @Test
    fun repositoryCachesByWatermarkAndSupportsResetLocalProfile() = runBlocking {
        val delegate = InMemoryEventBackend()
        val queryCalls = AtomicInteger(0)
        val spyBackend = object : EventStorageBackend by delegate {
            override fun query(
                limit: Int,
                sinceMillis: Long,
                types: Set<RecommendationEventType>?,
            ): List<RecommendationEvent> {
                queryCalls.incrementAndGet()
                return delegate.query(limit, sinceMillis, types)
            }
        }
        val clock = AtomicLong(10_000_000L)
        val eventRepo = RecommendationEventRepository(spyBackend, clock = { clock.get() })
        val libData = LibraryData(liked = listOf(kishoreSong), musicLanguages = listOf("Hindi", "Punjabi"))
        val profileRepo = DefaultUserProfileRepository(
            eventRepository = eventRepo,
            libraryDataProvider = { libData },
            clock = { clock.get() },
        )

        eventRepo.record(
            RecommendationEvent(
                eventId = "ev-1",
                eventType = RecommendationEventType.TRACK_COMPLETED,
                occurredAtMillis = clock.get(),
                providerItemId = arijitSong.id,
                artistIds = listOf("arijit singh"),
                recommendationSessionId = "sess-a",
            ),
        )

        val snap1 = profileRepo.getSnapshot(sessionId = "sess-a")
        val snap2 = profileRepo.getSnapshot(sessionId = "sess-a")
        assertEquals(snap1, snap2)
        // Second call hits watermark cache with ZERO additional event-store queries
        assertEquals(1, queryCalls.get())
        assertTrue("hi" in snap1.explicit.preferredLanguageIds)
        assertTrue("pa" in snap1.explicit.preferredLanguageIds)
        assertTrue(snap1.longTerm.artistAffinity("Arijit Singh") > 0.0)

        // New event invalidates mutationSequence -> rebuilds incrementally
        clock.addAndGet(5_000L)
        eventRepo.record(
            RecommendationEvent(
                eventId = "ev-2",
                eventType = RecommendationEventType.TRACK_LIKED,
                occurredAtMillis = clock.get(),
                providerItemId = badshahSong.id,
                artistIds = listOf("badshah"),
                recommendationSessionId = "sess-a",
            ),
        )
        val snap3 = profileRepo.getSnapshot(sessionId = "sess-a")
        assertEquals(2, queryCalls.get())
        assertTrue(snap3.longTerm.artistAffinity("Badshah") > 0.0)

        // Reset clears learned recommendation events cleanly while explicit Library songs stay readable
        profileRepo.resetLocalProfile()
        assertEquals(0, eventRepo.eventCount())
        val snapReset = profileRepo.getSnapshot(sessionId = "sess-a")
        assertEquals(0, snapReset.processedEventCount)
        assertEquals(0.0, snapReset.longTerm.artistAffinity("Arijit Singh"), 0.001)
    }
}
