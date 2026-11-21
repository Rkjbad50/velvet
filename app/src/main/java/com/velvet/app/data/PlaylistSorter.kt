package com.velvet.app.data

import java.text.Collator
import java.util.Locale

/**
 * Pure Kotlin sorting engine for playlist entries and library collections.
 * Zero Android dependencies.
 */
object PlaylistSorter {

    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply {
        strength = Collator.SECONDARY // Case- and accent-insensitive
    }

    private val LEADING_ARTICLES_REGEX = Regex("""^(?:the|a|an)\s+""", RegexOption.IGNORE_CASE)

    fun stripLeadingArticles(text: String): String =
        LEADING_ARTICLES_REGEX.replace(text.trim(), "").trim()

    /**
     * Pure sorting function:
     * - Returns a sorted List of [PlaylistEntry].
     * - Fully deterministic and STABLE: tie-breaks by title, then stored position.
     * - Never drops or duplicates entries.
     * - Blank/missing values go LAST in both ASC and DESC directions.
     * - Devanagari and international characters sort sensibly without errors.
     */
    fun sort(
        entries: List<PlaylistEntry>,
        key: SortKey,
        direction: SortDirection = SortDirection.ASC,
    ): List<PlaylistEntry> {
        if (entries.size <= 1 || key == SortKey.CUSTOM) {
            return entries
        }

        val indexed = entries.mapIndexed { index, entry -> IndexedEntry(entry, index) }

        val comparator = when (key) {
            SortKey.CUSTOM -> return entries
            SortKey.TITLE -> createStringComparator(direction) { it.entry.song.title }
            SortKey.ARTIST -> createStringComparator(direction) { it.entry.song.artist }
            SortKey.ALBUM -> createStringComparator(direction) { it.entry.song.artistUrl ?: "" }
            SortKey.DATE_ADDED -> createDateComparator(direction)
            SortKey.DURATION -> createDurationComparator(direction)
        }

        return indexed.sortedWith(comparator).map { it.entry }
    }

    private data class IndexedEntry(val entry: PlaylistEntry, val originalIndex: Int)

    private fun createStringComparator(
        direction: SortDirection,
        selector: (IndexedEntry) -> String?,
    ): Comparator<IndexedEntry> = Comparator { a, b ->
        val rawA = selector(a)?.trim().orEmpty()
        val rawB = selector(b)?.trim().orEmpty()

        val isBlankA = rawA.isEmpty()
        val isBlankB = rawB.isEmpty()

        if (isBlankA && isBlankB) return@Comparator tieBreak(a, b)
        if (isBlankA) return@Comparator 1
        if (isBlankB) return@Comparator -1

        val cleanA = stripLeadingArticles(rawA)
        val cleanB = stripLeadingArticles(rawB)

        val cmp = collator.compare(cleanA, cleanB)
        if (cmp != 0) {
            if (direction == SortDirection.ASC) cmp else -cmp
        } else {
            tieBreak(a, b)
        }
    }

    private fun createDateComparator(direction: SortDirection): Comparator<IndexedEntry> = Comparator { a, b ->
        val timeA = a.entry.addedAt
        val timeB = b.entry.addedAt

        if (timeA > 0L && timeB > 0L) {
            val cmp = timeA.compareTo(timeB)
            if (cmp != 0) {
                if (direction == SortDirection.ASC) cmp else -cmp
            } else {
                tieBreak(a, b)
            }
        } else if (timeA > 0L && timeB <= 0L) {
            if (direction == SortDirection.ASC) -1 else 1
        } else if (timeA <= 0L && timeB > 0L) {
            if (direction == SortDirection.ASC) 1 else -1
        } else {
            // Both missing timestamps: fallback to stored position
            val cmp = a.originalIndex.compareTo(b.originalIndex)
            if (direction == SortDirection.ASC) cmp else -cmp
        }
    }

    private fun createDurationComparator(direction: SortDirection): Comparator<IndexedEntry> = Comparator { a, b ->
        val durA = a.entry.song.durationSec
        val durB = b.entry.song.durationSec

        val missingA = durA <= 0
        val missingB = durB <= 0

        if (missingA && missingB) return@Comparator tieBreak(a, b)
        if (missingA) return@Comparator 1
        if (missingB) return@Comparator -1

        val cmp = durA.compareTo(durB)
        if (cmp != 0) {
            if (direction == SortDirection.ASC) cmp else -cmp
        } else {
            tieBreak(a, b)
        }
    }

    private fun tieBreak(a: IndexedEntry, b: IndexedEntry): Int {
        val titleA = stripLeadingArticles(a.entry.song.title.trim())
        val titleB = stripLeadingArticles(b.entry.song.title.trim())
        val titleCmp = collator.compare(titleA, titleB)
        if (titleCmp != 0) return titleCmp
        return a.originalIndex.compareTo(b.originalIndex)
    }

    fun getSortLabel(key: SortKey, direction: SortDirection): String = when (key) {
        SortKey.CUSTOM -> "Custom"
        SortKey.TITLE -> if (direction == SortDirection.ASC) "Title (A-Z)" else "Title (Z-A)"
        SortKey.ARTIST -> if (direction == SortDirection.ASC) "Artist (A-Z)" else "Artist (Z-A)"
        SortKey.ALBUM -> if (direction == SortDirection.ASC) "Album (A-Z)" else "Album (Z-A)"
        SortKey.DATE_ADDED -> if (direction == SortDirection.DESC) "Recently added" else "Oldest added"
        SortKey.DURATION -> if (direction == SortDirection.ASC) "Shortest first" else "Longest first"
    }
}
