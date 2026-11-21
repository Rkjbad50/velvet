package com.velvet.app.unit

import com.velvet.app.data.BackupCodec
import com.velvet.app.data.Library
import com.velvet.app.data.LibraryData
import com.velvet.app.data.Playlist
import com.velvet.app.data.PlaylistNameValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistRenameTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEmptyNameRejected() {
        val result = PlaylistNameValidator.validate("", null, emptyList())
        assertTrue(result is PlaylistNameValidator.ValidationResult.Error)
        assertEquals("Playlist name cannot be empty", (result as PlaylistNameValidator.ValidationResult.Error).message)
    }

    @Test
    fun testWhitespaceOnlyRejected() {
        val result = PlaylistNameValidator.validate("   \t  \n ", null, emptyList())
        assertTrue(result is PlaylistNameValidator.ValidationResult.Error)
        assertEquals("Playlist name cannot be empty", (result as PlaylistNameValidator.ValidationResult.Error).message)
    }

    @Test
    fun testMaxLength60() {
        val exactly60 = "a".repeat(60)
        val result60 = PlaylistNameValidator.validate(exactly60, null, emptyList())
        assertTrue(result60 is PlaylistNameValidator.ValidationResult.Valid)

        val sixtyOne = "a".repeat(61)
        val result61 = PlaylistNameValidator.validate(sixtyOne, null, emptyList())
        assertTrue(result61 is PlaylistNameValidator.ValidationResult.Error)
        assertEquals(
            "Playlist name cannot exceed 60 characters",
            (result61 as PlaylistNameValidator.ValidationResult.Error).message,
        )
    }

    @Test
    fun testCaseInsensitiveDuplicateRejected() {
        val existing = listOf(
            Playlist(id = "p1", name = "Chill Vibes"),
            Playlist(id = "p2", name = "Workout Hits"),
        )

        // Exact match
        val res1 = PlaylistNameValidator.validate("Chill Vibes", "p3", existing)
        assertTrue(res1 is PlaylistNameValidator.ValidationResult.Error)

        // Case-insensitive match with extra spaces
        val res2 = PlaylistNameValidator.validate("  chill vibes  ", "p3", existing)
        assertTrue(res2 is PlaylistNameValidator.ValidationResult.Error)
        assertEquals(
            "A playlist with this name already exists",
            (res2 as PlaylistNameValidator.ValidationResult.Error).message,
        )

        // Non-duplicate passes
        val res3 = PlaylistNameValidator.validate("Chill Evening", "p3", existing)
        assertTrue(res3 is PlaylistNameValidator.ValidationResult.Valid)
    }

    @Test
    fun testCurrentPlaylistAllowsSameName() {
        val existing = listOf(
            Playlist(id = "p1", name = "Chill Vibes"),
            Playlist(id = "p2", name = "Workout Hits"),
        )

        // Same name on current playlist (p1) is valid (e.g. only description changed or re-saved)
        val result = PlaylistNameValidator.validate("chill vibes", "p1", existing)
        assertTrue(result is PlaylistNameValidator.ValidationResult.Valid)
    }

    @Test
    fun testIdUnchangedAndPersistedInLibrary() {
        val dir = tempFolder.newFolder("lib_test")
        val library = Library(dir)

        val id = library.createPlaylist("Original Name")
        val created = library.data.value.playlists.first { it.id == id }
        assertEquals("Original Name", created.name)

        library.updatePlaylistDetails(id, "  Updated Name  ", "  New description  ")
        val updated = library.data.value.playlists.first { it.id == id }

        // id MUST NOT change
        assertEquals(id, updated.id)
        // Whitespace trimmed
        assertEquals("Updated Name", updated.name)
        assertEquals("New description", updated.description)

        // Verify persistence across new Library instance on same dir
        val reloaded = Library(dir)
        val fromDisk = reloaded.data.value.playlists.first { it.id == id }
        assertEquals(id, fromDisk.id)
        assertEquals("Updated Name", fromDisk.name)
        assertEquals("New description", fromDisk.description)
    }

    @Test
    fun testBackupRoundTripPreservesNameAndDescription() {
        val original = LibraryData(
            playlists = listOf(
                Playlist(
                    id = "pl-123",
                    name = "Midnight Jazz",
                    description = "Late night chill & lo-fi sounds",
                    createdAt = 1000L,
                    updatedAt = 2000L,
                ),
            ),
        )

        val encoded = BackupCodec.encode(original)
        val decoded = BackupCodec.decode(encoded)

        assertEquals(1, decoded.playlists.size)
        val playlist = decoded.playlists[0]
        assertEquals("pl-123", playlist.id)
        assertEquals("Midnight Jazz", playlist.name)
        assertEquals("Late night chill & lo-fi sounds", playlist.description)
    }
}
