package com.velvet.app.unit

import com.velvet.app.data.ListenContext
import com.velvet.app.data.ListenEndReason
import com.velvet.app.data.ListenEvent
import com.velvet.app.data.Song
import com.velvet.app.playback.AutoplayPipeline
import com.velvet.app.playback.CandidateSource
import com.velvet.app.playback.SeedProfile
import com.velvet.app.playback.SessionHistory
import com.velvet.app.playback.SongIdentity
import com.velvet.app.playback.TasteEngine
import com.velvet.app.playback.TasteProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TasteProfileTest {

    private fun sampleSong(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Long = 240L,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        thumb = "",
    )

    @Test
    fun testDecayHalfLife_thirtyDaysHalfWeight() {
        val now = 1_700_000_000_000L
        val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000L)

        val eventNow = ListenEvent(
            songGroupId = "g1",
            videoId = "v1",
            artistSet = listOf("Arijit Singh"),
            startedAt = now,
            endReason = ListenEndReason.COMPLETED,
        )
        val eventOld = ListenEvent(
            songGroupId = "g2",
            videoId = "v2",
            artistSet = listOf("Sonu Nigam"),
            startedAt = thirtyDaysAgo,
            endReason = ListenEndReason.COMPLETED,
        )

        val profile = TasteEngine.computeProfile(listOf(eventNow, eventOld), nowMillis = now)
        val arijitScore = profile.artistAffinity["arijit singh"] ?: 0.0
        val sonuScore = profile.artistAffinity["sonu nigam"] ?: 0.0

        // Since arijit is the max (1.0 weight), normalized score is 1.0
        // Sonu should be ~0.5 (half-life of 30 days)
        assertEquals(1.0, arijitScore, 0.01)
        assertEquals(0.5, sonuScore, 0.05)
    }

    @Test
    fun testEventWeights_rewardsCompletionsAndLikesAndPenalizesSkips() {
        val now = 1_700_000_000_000L
        val events = listOf(
            ListenEvent(
                songGroupId = "g1",
                videoId = "v1",
                artistSet = listOf("Kumar Sanu"),
                startedAt = now,
                endReason = ListenEndReason.COMPLETED,
                liked = true, // +1.0 + 2.0 = +3.0
            ),
            ListenEvent(
                songGroupId = "g2",
                videoId = "v2",
                artistSet = listOf("Badshah"),
                startedAt = now,
                endReason = ListenEndReason.SKIP_EARLY, // -1.5
            ),
        )

        val profile = TasteEngine.computeProfile(events, nowMillis = now)
        val kumarScore = profile.artistAffinity["kumar sanu"] ?: 0.0
        val badshahScore = profile.artistAffinity["badshah"] ?: 0.0

        assertTrue("Kumar Sanu should have positive affinity", kumarScore > 0.0)
        assertTrue("Badshah should have negative affinity", badshahScore < 0.0)
        assertEquals(1.0, kumarScore, 0.01)
        assertEquals(-0.5, badshahScore, 0.05) // -1.5 / 3.0 = -0.5
    }

    @Test
    fun testFatigue_twoSkipsIn14DaysBlocksFor30Days() {
        val now = 1_700_000_000_000L
        val twoDaysAgo = now - (2L * 24 * 60 * 60 * 1000L)
        val fiveDaysAgo = now - (5L * 24 * 60 * 60 * 1000L)
        val twentyDaysAgo = now - (20L * 24 * 60 * 60 * 1000L)

        // Group 1: 2 early skips within 14 days -> fatigued!
        val eventsFatigued = listOf(
            ListenEvent(songGroupId = "fatigued_group", videoId = "v1", startedAt = fiveDaysAgo, endReason = ListenEndReason.SKIP_EARLY),
            ListenEvent(songGroupId = "fatigued_group", videoId = "v2", startedAt = twoDaysAgo, endReason = ListenEndReason.SKIP_EARLY),
        )

        // Group 2: 1 early skip within 14 days, 1 from 20 days ago -> NOT fatigued (needs 2 in 14 days)
        val eventsNotFatigued = listOf(
            ListenEvent(songGroupId = "ok_group", videoId = "v3", startedAt = twentyDaysAgo, endReason = ListenEndReason.SKIP_EARLY),
            ListenEvent(songGroupId = "ok_group", videoId = "v4", startedAt = twoDaysAgo, endReason = ListenEndReason.SKIP_EARLY),
        )

        val profile = TasteEngine.computeProfile(eventsFatigued + eventsNotFatigued, nowMillis = now)
        assertTrue(profile.fatiguedGroupIds.contains("fatigued_group"))
        assertFalse(profile.fatiguedGroupIds.contains("ok_group"))
    }

    @Test
    fun testDailyOverplay_threePlaysIn24HoursDownWeighted() {
        val now = 1_700_000_000_000L
        val oneHourAgo = now - (3600 * 1000L)
        val twoHoursAgo = now - (7200 * 1000L)
        val threeHoursAgo = now - (10800 * 1000L)

        val events = listOf(
            ListenEvent(songGroupId = "overplayed_group", videoId = "v1", startedAt = threeHoursAgo, endReason = ListenEndReason.COMPLETED),
            ListenEvent(songGroupId = "overplayed_group", videoId = "v2", startedAt = twoHoursAgo, endReason = ListenEndReason.COMPLETED),
            ListenEvent(songGroupId = "overplayed_group", videoId = "v3", startedAt = oneHourAgo, endReason = ListenEndReason.COMPLETED),
            ListenEvent(songGroupId = "normal_group", videoId = "v4", startedAt = oneHourAgo, endReason = ListenEndReason.COMPLETED),
        )

        val profile = TasteEngine.computeProfile(events, nowMillis = now)
        assertTrue(profile.dailyOverplayedGroupIds.contains("overplayed_group"))
        assertFalse(profile.dailyOverplayedGroupIds.contains("normal_group"))
    }

    @Test
    fun testNoveltyRatio_ratioOfCompletedNewSongs() {
        val now = 1_700_000_000_000L
        val events = listOf(
            ListenEvent(songGroupId = "g1", videoId = "v1", startedAt = now - 4000, endReason = ListenEndReason.COMPLETED),
            ListenEvent(songGroupId = "g1", videoId = "v1", startedAt = now - 3000, endReason = ListenEndReason.COMPLETED), // repeat
            ListenEvent(songGroupId = "g2", videoId = "v2", startedAt = now - 2000, endReason = ListenEndReason.COMPLETED),
            ListenEvent(songGroupId = "g3", videoId = "v3", startedAt = now - 1000, endReason = ListenEndReason.COMPLETED),
        )

        val profile = TasteEngine.computeProfile(events, nowMillis = now)
        // 4 total completed: g1 (new), g1 (repeat), g2 (new), g3 (new) => 3 new / 4 total = 0.75
        assertEquals(0.75, profile.noveltyPreferenceRatio, 0.01)
    }

    @Test
    fun testAutoplayPipelineDedupe_dropsPersistedPlayedGroupIds() = runBlocking {
        val seed = sampleSong("seed_id", "Aankhon Ki Gustakhiyan", "Kumar Sanu - Topic")
        val candidate1 = sampleSong("c1", "Dil Ne Yeh Kaha Hai Dil Se", "Udit Narayan - Topic")
        val candidate2 = sampleSong("c2", "Chand Chupa Badal Mein", "Udit Narayan - Topic")

        val c1Group = SongIdentity.canonicalGroupId(candidate1)

        val source = CandidateSource { listOf(candidate1, candidate2) }

        val pipeline = AutoplayPipeline(
            sources = listOf(source),
            history = SessionHistory(),
            persistedPlayedGroupIdsProvider = { listOf(c1Group) }, // c1 was played previously across sessions
        )

        val result = pipeline.next(SeedProfile(current = seed), emptyList(), n = 5)
        assertEquals(1, result.size)
        assertEquals("c2", result.first().id) // candidate1 was dropped due to persistedPlayedGroupIds
    }

    @Test
    fun testAutoplayPipelineDedupe_dropsFatiguedGroupIds() = runBlocking {
        val seed = sampleSong("seed_id", "Aankhon Ki Gustakhiyan", "Kumar Sanu - Topic")
        val candidate1 = sampleSong("c1", "Tujhe Dekha To", "Kumar Sanu - Topic")
        val candidate2 = sampleSong("c2", "Chand Chupa Badal Mein", "Udit Narayan - Topic")

        val c1Group = SongIdentity.canonicalGroupId(candidate1)

        val source = CandidateSource { listOf(candidate1, candidate2) }

        val pipeline = AutoplayPipeline(
            sources = listOf(source),
            history = SessionHistory(),
            tasteProfileProvider = {
                TasteProfile(fatiguedGroupIds = setOf(c1Group))
            },
        )

        val result = pipeline.next(SeedProfile(current = seed), emptyList(), n = 5)
        assertEquals(1, result.size)
        assertEquals("c2", result.first().id) // candidate1 was dropped due to fatigue
    }
}
