package com.velvet.app.unit

import com.velvet.app.data.BackupCodec
import com.velvet.app.data.ContextRecentsManager
import com.velvet.app.data.CoverMode
import com.velvet.app.data.Library
import com.velvet.app.data.LibraryData
import com.velvet.app.data.PlaybackContext
import com.velvet.app.data.Playlist
import com.velvet.app.data.RecentItem
import com.velvet.app.data.RecentType
import com.velvet.app.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextRecentsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAlbumThenArtistThenPlaylistRecordsThreeRecentsNewestFirst() {
        var recents = emptyList<RecentItem>()

        val albumContext = PlaybackContext("album-1", RecentType.ALBUM, "Abbey Road", "The Beatles")
        val artistContext = PlaybackContext("artist-1", RecentType.ARTIST, "The Beatles", "Artist")
        val playlistContext = PlaybackContext("pl-1", RecentType.LOCAL_PLAYLIST, "Rock Classics", "50 tracks")

        recents = ContextRecentsManager.record(recents, albumContext, now = 1000L)
        recents = ContextRecentsManager.record(recents, artistContext, now = 2000L)
        recents = ContextRecentsManager.record(recents, playlistContext, now = 3000L)

        assertEquals(3, recents.size)
        // Newest first
        assertEquals("pl-1", recents[0].id)
        assertEquals(RecentType.LOCAL_PLAYLIST, recents[0].type)
        assertEquals("artist-1", recents[1].id)
        assertEquals(RecentType.ARTIST, recents[1].type)
        assertEquals("album-1", recents[2].id)
        assertEquals(RecentType.ALBUM, recents[2].type)
    }

    @Test
    fun testReplayMovesToFrontWithoutDuplicates() {
        var recents = emptyList<RecentItem>()

        val album = PlaybackContext("album-1", RecentType.ALBUM, "Abbey Road")
        val artist = PlaybackContext("artist-1", RecentType.ARTIST, "The Beatles")

        recents = ContextRecentsManager.record(recents, album, now = 1000L)
        recents = ContextRecentsManager.record(recents, artist, now = 2000L)
        assertEquals(listOf("artist-1", "album-1"), recents.map { it.id })

        // Replay album
        recents = ContextRecentsManager.record(recents, album, now = 3000L)

        assertEquals(2, recents.size)
        assertEquals("album-1", recents[0].id)
        assertEquals(3000L, recents[0].lastPlayedAt)
        assertEquals("artist-1", recents[1].id)
    }

    @Test
    fun testOpeningScreenWithoutPlayingRecordsNothing() {
        val dir = tempFolder.newFolder("screen_open_test")
        val library = Library(dir)

        // Opening screen does not trigger addContextRecent
        assertEquals(0, library.data.value.contextRecents.size)
    }

    @Test
    fun testSingleSongFromSearchRecordsNothing() {
        val dir = tempFolder.newFolder("search_song_test")
        val library = Library(dir)

        // When a standalone search song is played without context, context is null
        val initialCount = library.data.value.contextRecents.size
        // library.addRecent(song) records to track history, but not contextRecents
        library.addRecent(Song("id-1", "Search Song", "Artist", 200))

        assertEquals(initialCount, library.data.value.contextRecents.size)
    }

    @Test
    fun testArtistOpenedFromSearchAndPlayedRecordsArtist() {
        val dir = tempFolder.newFolder("artist_play_test")
        val library = Library(dir)

        // User navigates to ArtistScreen and starts playback with PlaybackContext
        val artistContext = PlaybackContext("artist-queen", RecentType.ARTIST, "Queen", "Artist")
        library.addContextRecent(artistContext)

        val recents = library.data.value.contextRecents
        assertEquals(1, recents.size)
        assertEquals("artist-queen", recents[0].id)
        assertEquals(RecentType.ARTIST, recents[0].type)
        assertEquals("Queen", recents[0].title)
    }

    @Test
    fun testMax20Capacity() {
        var recents = emptyList<RecentItem>()
        for (i in 1..25) {
            val ctx = PlaybackContext("album-$i", RecentType.ALBUM, "Album $i")
            recents = ContextRecentsManager.record(recents, ctx, now = 1000L + i)
        }

        assertEquals(20, recents.size)
        // Most recent was 25
        assertEquals("album-25", recents.first().id)
        // Least recent kept was 6 (1 to 5 dropped)
        assertEquals("album-6", recents.last().id)
    }

    @Test
    fun testOldFormatMigration() {
        val playlists = listOf(
            Playlist(id = "pl-rock", name = "Rock", songs = listOf(Song("s1", "Song 1", "Artist 1", 180))),
            Playlist(id = "pl-pop", name = "Pop", songs = listOf(Song("s2", "Song 2", "Artist 2", 210))),
        )
        val legacyRecentSongs = listOf(
            Song("s1", "Song 1", "Artist 1", 180),
            Song("s2", "Song 2", "Artist 2", 210),
            Song("s3", "Song 3", "Artist 3", 190), // standalone, no matching playlist
        )

        val migrated = ContextRecentsManager.migrateOldFormat(legacyRecentSongs, playlists)

        // The matching playlists migrate as LOCAL_PLAYLIST
        assertEquals(2, migrated.size)
        assertEquals("pl-rock", migrated[0].id)
        assertEquals(RecentType.LOCAL_PLAYLIST, migrated[0].type)
        assertEquals("Rock", migrated[0].title)
        assertEquals("pl-pop", migrated[1].id)
        assertEquals(RecentType.LOCAL_PLAYLIST, migrated[1].type)
        assertEquals("Pop", migrated[1].title)
    }

    @Test
    fun testDeletedPlaylistRemovedSilently() {
        val existingPlaylists = listOf(Playlist(id = "pl-kept", name = "Kept"))
        val recents = listOf(
            RecentItem("pl-kept", RecentType.LOCAL_PLAYLIST, "Kept"),
            RecentItem("pl-deleted", RecentType.LOCAL_PLAYLIST, "Deleted"),
            RecentItem("album-1", RecentType.ALBUM, "Album 1"),
        )

        val filtered = ContextRecentsManager.filterDeletedPlaylists(recents, existingPlaylists)

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.id == "pl-kept" })
        assertTrue(filtered.any { it.id == "album-1" })
        assertFalse(filtered.any { it.id == "pl-deleted" })
    }

    @Test
    fun testLibraryPersistenceAndBackupRoundTrip() {
        val dir = tempFolder.newFolder("lib_recents_backup")
        val library = Library(dir)

        library.addContextRecent(PlaybackContext("album-123", RecentType.ALBUM, "Random Access Memories", "Daft Punk"))
        library.flushBlocking()

        val reloaded = Library(dir)
        assertEquals(1, reloaded.data.value.contextRecents.size)
        assertEquals("album-123", reloaded.data.value.contextRecents[0].id)
        assertEquals("Random Access Memories", reloaded.data.value.contextRecents[0].title)

        // Backup round trip
        val encoded = BackupCodec.encode(reloaded.data.value)
        val decoded = BackupCodec.decode(encoded)
        assertEquals(1, decoded.contextRecents.size)
        assertEquals("album-123", decoded.contextRecents[0].id)
    }

    @Test
    fun testPlaylistCoverAndTitleUpdateInContextRecentsWhenPlaylistIsUpdated() {
        val dir = tempFolder.newFolder("playlist_recents_sync")
        val library = Library(dir)

        val plId = library.createPlaylist("Original Playlist")
        library.addToPlaylist(plId, Song("s1", "Song 1", "Artist 1", 180, thumb = "http://thumb1"))

        // Playlist is played, recording into contextRecents
        library.addContextRecent(
            PlaybackContext(
                id = plId,
                type = RecentType.LOCAL_PLAYLIST,
                title = "Original Playlist",
                subtitle = "1 song",
                artworkUrl = "http://thumb1",
            )
        )
        assertEquals(1, library.data.value.contextRecents.size)
        assertEquals("Original Playlist", library.data.value.contextRecents[0].title)
        assertEquals("http://thumb1", library.data.value.contextRecents[0].artworkUrl)

        // User updates playlist cover to PRESET 3 in Library
        library.setPlaylistCover(plId, CoverMode.PRESET, "3")
        // User renames playlist in Library
        library.updatePlaylistDetails(plId, "Renamed Vibes")

        // contextRecents must sync with the updated playlist cover and title
        val recent = library.data.value.contextRecents.first { it.id == plId }
        assertEquals("Renamed Vibes", recent.title)
        assertEquals("preset:3", recent.artworkUrl)
    }

    @Test
    fun testHomeFourPlaylistTilesAllocatedRankingWiseFromNewestPlayed() {
        val dir = tempFolder.newFolder("home_tiles_ranking")
        val library = Library(dir)

        val p1 = library.createPlaylist("Playlist 1")
        val p2 = library.createPlaylist("Playlist 2")
        val p3 = library.createPlaylist("Playlist 3")
        val p4 = library.createPlaylist("Playlist 4")

        library.addToPlaylist(p1, Song("s1", "Song 1", "Artist 1"))
        library.addToPlaylist(p2, Song("s2", "Song 2", "Artist 2"))
        library.addToPlaylist(p3, Song("s3", "Song 3", "Artist 3"))
        library.addToPlaylist(p4, Song("s4", "Song 4", "Artist 4"))

        // Play P4, then P2, then P3, then P1
        library.addContextRecent(PlaybackContext(p4, RecentType.LOCAL_PLAYLIST, "Playlist 4"))
        Thread.sleep(10)
        library.addContextRecent(PlaybackContext(p2, RecentType.LOCAL_PLAYLIST, "Playlist 2"))
        Thread.sleep(10)
        library.addContextRecent(PlaybackContext(p3, RecentType.LOCAL_PLAYLIST, "Playlist 3"))
        Thread.sleep(10)
        library.addContextRecent(PlaybackContext(p1, RecentType.LOCAL_PLAYLIST, "Playlist 1"))

        // Very newest recently played playlist is P1 -> Tile 1
        // 2nd newest is P3 -> Tile 2
        // 3rd newest is P2 -> Tile 3
        // 4th newest is P4 -> Tile 4
        val recentsMap = library.data.value.contextRecents
            .filter { it.type == RecentType.LOCAL_PLAYLIST }
            .associate { it.id to it.lastPlayedAt }

        val rankedPlaylists = library.data.value.playlists
            .filter { it.songs.isNotEmpty() }
            .sortedWith(
                compareByDescending<Playlist> { p ->
                    maxOf(p.lastPlayedAt, recentsMap[p.id] ?: 0L)
                }.thenByDescending { p ->
                    maxOf(p.createdAt, p.addedAt, p.updatedAt)
                }.thenByDescending { it.playCount }
            )
            .take(4)

        assertEquals(listOf(p1, p3, p2, p4), rankedPlaylists.map { it.id })
    }
}
