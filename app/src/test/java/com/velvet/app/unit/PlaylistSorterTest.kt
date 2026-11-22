package com.velvet.app.unit

import com.velvet.app.data.PlaylistEntry
import com.velvet.app.data.PlaylistSorter
import com.velvet.app.data.Song
import com.velvet.app.data.SortDirection
import com.velvet.app.data.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class PlaylistSorterTest {

    private fun makeEntry(
        id: String,
        title: String,
        artist: String = "Artist",
        durationSec: Long = 180,
        addedAt: Long = 1000L,
        artistUrl: String? = null,
        entryId: String = id,
        album: String? = null,
    ): PlaylistEntry = PlaylistEntry(
        entryId = entryId,
        song = Song(
            id = id,
            title = title,
            artist = artist,
            durationSec = durationSec,
            artistUrl = artistUrl,
            album = album,
        ),
        addedAt = addedAt,
    )

    @Test
    fun customSortPreservesOriginalOrderAndIgnoresDirection() {
        val original = listOf(
            makeEntry("1", "Zebra"),
            makeEntry("2", "Apple"),
            makeEntry("3", "Mango"),
        )
        val sortedAsc = PlaylistSorter.sort(original, SortKey.CUSTOM, SortDirection.ASC)
        val sortedDesc = PlaylistSorter.sort(original, SortKey.CUSTOM, SortDirection.DESC)

        assertEquals(original, sortedAsc)
        assertEquals(original, sortedDesc)
    }

    @Test
    fun titleSortAscAndDescWithArticleStripping() {
        val sample = listOf(
            makeEntry("1", "The Beatles"),
            makeEntry("2", "A Day in the Life"),
            makeEntry("3", "An Apple"),
            makeEntry("4", "Banana"),
            makeEntry("5", "Coldplay"),
        )
        // Cleaned titles for sorting:
        // "An Apple" -> "Apple"
        // "Banana" -> "Banana"
        // "The Beatles" -> "Beatles"
        // "Coldplay" -> "Coldplay"
        // "A Day in the Life" -> "Day in the Life"

        val asc = PlaylistSorter.sort(sample, SortKey.TITLE, SortDirection.ASC)
        assertEquals(listOf("3", "4", "1", "5", "2"), asc.map { it.entryId })

        val desc = PlaylistSorter.sort(sample, SortKey.TITLE, SortDirection.DESC)
        assertEquals(listOf("2", "5", "1", "4", "3"), desc.map { it.entryId })
    }

    @Test
    fun artistSortAscAndDescWithArticleStripping() {
        val sample = listOf(
            makeEntry("1", "Song 1", artist = "The Who"),
            makeEntry("2", "Song 2", artist = "Adele"),
            makeEntry("3", "Song 3", artist = "A Tribe Called Quest"),
            makeEntry("4", "Song 4", artist = "Coldplay"),
        )
        // Artists stripped:
        // Adele -> Adele
        // Coldplay -> Coldplay
        // A Tribe Called Quest -> Tribe Called Quest
        // The Who -> Who

        val asc = PlaylistSorter.sort(sample, SortKey.ARTIST, SortDirection.ASC)
        assertEquals(listOf("2", "4", "3", "1"), asc.map { it.entryId })

        val desc = PlaylistSorter.sort(sample, SortKey.ARTIST, SortDirection.DESC)
        assertEquals(listOf("1", "3", "4", "2"), desc.map { it.entryId })
    }

    @Test
    fun albumSortAscAndDescWithArticleStrippingAndMissingGoLast() {
        val sample = listOf(
            makeEntry("1", "Song A", album = "The Wall"),
            makeEntry("2", "Song B", album = "Abbey Road"),
            makeEntry("3", "Song C", album = "A Rush of Blood to the Head"),
            makeEntry("4", "Song D", album = null), // missing album
        )
        // Stripped:
        // "Abbey Road" -> "Abbey Road"
        // "A Rush of Blood to the Head" -> "Rush of Blood to the Head"
        // "The Wall" -> "Wall"
        // Missing album -> goes last in both ASC and DESC

        val asc = PlaylistSorter.sort(sample, SortKey.ALBUM, SortDirection.ASC)
        assertEquals(listOf("2", "3", "1", "4"), asc.map { it.entryId })

        val desc = PlaylistSorter.sort(sample, SortKey.ALBUM, SortDirection.DESC)
        assertEquals(listOf("1", "3", "2", "4"), desc.map { it.entryId })
    }

    @Test
    fun durationSortAscAndDescShortestFirstAndLongestFirst() {
        val sample = listOf(
            makeEntry("1", "Medium", durationSec = 180),
            makeEntry("2", "Short", durationSec = 60),
            makeEntry("3", "Long", durationSec = 360),
        )

        val asc = PlaylistSorter.sort(sample, SortKey.DURATION, SortDirection.ASC)
        assertEquals(listOf("2", "1", "3"), asc.map { it.entryId })

        val desc = PlaylistSorter.sort(sample, SortKey.DURATION, SortDirection.DESC)
        assertEquals(listOf("3", "1", "2"), desc.map { it.entryId })
    }

    @Test
    fun dateAddedSortAscAndDesc() {
        val sample = listOf(
            makeEntry("1", "Second", addedAt = 2000L),
            makeEntry("2", "First", addedAt = 1000L),
            makeEntry("3", "Third", addedAt = 3000L),
        )

        val asc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.ASC)
        assertEquals(listOf("2", "1", "3"), asc.map { it.entryId })

        // DESC is "Recently added" (newest first)
        val desc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.DESC)
        assertEquals(listOf("3", "1", "2"), desc.map { it.entryId })
    }

    @Test
    fun dateAddedFallbackToStoredPositionWhenTimestampsMissing() {
        val sample = listOf(
            makeEntry("1", "FirstNoTs", addedAt = 0L),
            makeEntry("2", "SecondNoTs", addedAt = 0L),
            makeEntry("3", "ThirdNoTs", addedAt = -1L),
        )

        val asc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.ASC)
        // Fallback to stored position in ASC (oldest added first)
        assertEquals(listOf("1", "2", "3"), asc.map { it.entryId })

        val desc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.DESC)
        // Fallback to reverse stored position in DESC (recently added first)
        assertEquals(listOf("3", "2", "1"), desc.map { it.entryId })
    }

    @Test
    fun dateAddedWithIdenticalTimestampsPreservesOrderInAscAndReversesInDesc() {
        val sample = listOf(
            makeEntry("1", "Zebra", addedAt = 5000L),
            makeEntry("2", "Apple", addedAt = 5000L),
            makeEntry("3", "Mango", addedAt = 5000L),
        )
        // With identical timestamps:
        // ASC preserves insertion order: 1, 2, 3
        val asc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.ASC)
        assertEquals(listOf("1", "2", "3"), asc.map { it.entryId })

        // DESC reverses insertion order: 3, 2, 1
        val desc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.DESC)
        assertEquals(listOf("3", "2", "1"), desc.map { it.entryId })
    }

    @Test
    fun dateAddedWithMixedTimestampsPutsMissingLastInBothDirections() {
        val sample = listOf(
            makeEntry("1", "Old Track", addedAt = 1000L),
            makeEntry("2", "New Track", addedAt = 3000L),
            makeEntry("3", "No Timestamp Track", addedAt = 0L),
        )
        val asc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.ASC)
        assertEquals(listOf("1", "2", "3"), asc.map { it.entryId })

        val desc = PlaylistSorter.sort(sample, SortKey.DATE_ADDED, SortDirection.DESC)
        assertEquals(listOf("2", "1", "3"), desc.map { it.entryId })
    }

    @Test
    fun missingBlankValuesGoLastInBothAscAndDesc() {
        val sample = listOf(
            makeEntry("1", "Valid Artist", artist = "Taylor Swift", durationSec = 200),
            makeEntry("2", "Blank Artist", artist = "   ", durationSec = 150),
            makeEntry("3", "Another Artist", artist = "Billie Eilish", durationSec = -1),
            makeEntry("4", "Zero Duration", artist = "Coldplay", durationSec = 0),
        )

        // Artist ASC: Billie Eilish, Taylor Swift, then blank artist
        val artistAsc = PlaylistSorter.sort(sample, SortKey.ARTIST, SortDirection.ASC)
        assertEquals("2", artistAsc.last().entryId)

        // Artist DESC: Taylor Swift, Billie Eilish, then blank artist
        val artistDesc = PlaylistSorter.sort(sample, SortKey.ARTIST, SortDirection.DESC)
        assertEquals("2", artistDesc.last().entryId)

        // Duration ASC: missing/zero (-1, 0) go last
        val durAsc = PlaylistSorter.sort(sample, SortKey.DURATION, SortDirection.ASC)
        val validDurIds = durAsc.take(2).map { it.entryId }
        assertEquals(listOf("2", "1"), validDurIds) // 150s, 200s
        assertTrue(durAsc.takeLast(2).map { it.entryId }.containsAll(listOf("3", "4")))

        // Duration DESC: missing/zero (-1, 0) still go last!
        val durDesc = PlaylistSorter.sort(sample, SortKey.DURATION, SortDirection.DESC)
        val validDurDescIds = durDesc.take(2).map { it.entryId }
        assertEquals(listOf("1", "2"), validDurDescIds) // 200s, 150s
        assertTrue(durDesc.takeLast(2).map { it.entryId }.containsAll(listOf("3", "4")))
    }

    @Test
    fun duplicateSongsKeepDistinctEntryIdsAndAreNotDropped() {
        val song1 = Song("songA", "Repeated Song", "Artist")
        val entry1 = PlaylistEntry(entryId = "entry-1", song = song1, addedAt = 100L)
        val entry2 = PlaylistEntry(entryId = "entry-2", song = song1, addedAt = 200L)
        val entry3 = PlaylistEntry(entryId = "entry-3", song = Song("songB", "Other Song", "Artist"), addedAt = 150L)

        val list = listOf(entry1, entry3, entry2)
        val sorted = PlaylistSorter.sort(list, SortKey.TITLE, SortDirection.ASC)

        assertEquals(3, sorted.size)
        assertEquals(listOf("entry-3", "entry-1", "entry-2"), sorted.map { it.entryId })
    }

    @Test
    fun accentsAndMixedCaseCollateSensibly() {
        val sample = listOf(
            makeEntry("1", "café"),
            makeEntry("2", "Cafe"),
            makeEntry("3", "apple"),
            makeEntry("4", "Banana"),
        )
        val sorted = PlaylistSorter.sort(sample, SortKey.TITLE, SortDirection.ASC)
        assertEquals("3", sorted[0].entryId) // apple
        assertEquals("4", sorted[1].entryId) // Banana
        // Cafe and café collate next to each other
        val cafeIds = sorted.takeLast(2).map { it.entryId }
        assertTrue(cafeIds.containsAll(listOf("1", "2")))
    }

    @Test
    fun devanagariTitlesSortSensiblyWithoutCrashing() {
        val sample = listOf(
            makeEntry("1", "तुम ही हो"),
            makeEntry("2", "केसरिया"),
            makeEntry("3", "अपना बना ले"),
            makeEntry("4", "कल हो ना हो"),
        )
        val asc = PlaylistSorter.sort(sample, SortKey.TITLE, SortDirection.ASC)
        val desc = PlaylistSorter.sort(sample, SortKey.TITLE, SortDirection.DESC)

        assertEquals(4, asc.size)
        assertEquals(4, desc.size)
        assertEquals(asc.reversed().map { it.entryId }, desc.map { it.entryId })
    }

    @Test
    fun tieBreakingIsDeterministicAndStable() {
        val sample = listOf(
            makeEntry("1", "Same Title", artist = "Artist B"),
            makeEntry("2", "Same Title", artist = "Artist A"),
            makeEntry("3", "Same Title", artist = "Artist C"),
        )
        // With TITLE sort, titles are equal. Tie-breaker is title then original index.
        val sorted1 = PlaylistSorter.sort(sample, SortKey.TITLE, SortDirection.ASC)
        val sorted2 = PlaylistSorter.sort(sample, SortKey.TITLE, SortDirection.ASC)

        assertEquals(listOf("1", "2", "3"), sorted1.map { it.entryId })
        assertEquals(sorted1.map { it.entryId }, sorted2.map { it.entryId })
    }

    @Test
    fun sortingLargePlaylist5000EntriesUnderTimeLimit() {
        val largeList = (0 until 5000).map { i ->
            makeEntry(
                id = "id-$i",
                title = "Song ${(5000 - i) % 1000}",
                artist = "Artist ${i % 500}",
                durationSec = (i % 300).toLong(),
                addedAt = i.toLong(),
            )
        }

        val elapsedMs = measureTimeMillis {
            val sorted = PlaylistSorter.sort(largeList, SortKey.TITLE, SortDirection.ASC)
            assertEquals(5000, sorted.size)
        }
        assertTrue("5000 entries sort took ${elapsedMs}ms, should be < 1000ms", elapsedMs < 1000L)
    }

    @Test
    fun librarySortingEngineWorksForLibrarySortKeys() {
        data class Item(val id: String, val name: String, val added: Long, val played: Long)

        val items = listOf(
            Item("1", "The Beatles", added = 2000L, played = 500L),
            Item("2", "Coldplay", added = 3000L, played = 800L),
            Item("3", "Aerosmith", added = 1000L, played = 100L),
        )

        // Alphabetical ASC
        val alphaAsc = PlaylistSorter.sortLibrary(
            items = items,
            key = PlaylistSorter.LibrarySortKey.ALPHABETICAL,
            direction = SortDirection.ASC,
            titleSelector = { it.name },
            addedAtSelector = { it.added },
        )
        // Aerosmith, Beatles (The stripped), Coldplay
        assertEquals(listOf("3", "1", "2"), alphaAsc.map { it.id })

        // Recently Added (DESC)
        val recAdded = PlaylistSorter.sortLibrary(
            items = items,
            key = PlaylistSorter.LibrarySortKey.RECENTLY_ADDED,
            direction = SortDirection.DESC,
            titleSelector = { it.name },
            addedAtSelector = { it.added },
        )
        assertEquals(listOf("2", "1", "3"), recAdded.map { it.id })

        // Recently Played (DESC)
        val recPlayed = PlaylistSorter.sortLibrary(
            items = items,
            key = PlaylistSorter.LibrarySortKey.RECENTLY_PLAYED,
            direction = SortDirection.DESC,
            titleSelector = { it.name },
            addedAtSelector = { it.added },
            lastPlayedAtSelector = { it.played },
        )
        assertEquals(listOf("2", "1", "3"), recPlayed.map { it.id })
    }
}
