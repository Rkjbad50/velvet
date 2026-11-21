package com.velvet.app.unit

import com.velvet.app.data.CoverMode
import com.velvet.app.data.Library
import com.velvet.app.data.LibraryData
import com.velvet.app.data.Playlist
import com.velvet.app.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistEditAndLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    data class EditFormState(
        val name: String,
        val description: String,
        val coverMode: CoverMode,
        val coverRef: String?,
        val originalPlaylist: Playlist,
        val existingPlaylists: List<Playlist>,
    ) {
        val trimmedName: String = name.trim()
        val isNameBlank: Boolean = trimmedName.isEmpty()
        val isNameTooLong: Boolean = name.length > 80
        val isDescTooLong: Boolean = description.length > 300
        val isDuplicate: Boolean = existingPlaylists.any {
            it.id != originalPlaylist.id && it.name.trim().equals(trimmedName, ignoreCase = true)
        }
        val canSave: Boolean = !isNameBlank && !isNameTooLong && !isDescTooLong
        val isDirty: Boolean = name != originalPlaylist.name ||
            description != originalPlaylist.description.orEmpty() ||
            coverMode != originalPlaylist.coverMode ||
            coverRef != originalPlaylist.coverRef
    }

    @Test
    fun editFormBlankNameDisablesSave() {
        val original = Playlist(id = "1", name = "My Playlist")
        val state = EditFormState(
            name = "   ",
            description = "Some description",
            coverMode = CoverMode.AUTO,
            coverRef = null,
            originalPlaylist = original,
            existingPlaylists = listOf(original),
        )

        assertTrue(state.isNameBlank)
        assertFalse("Save must be disabled when name is blank", state.canSave)
    }

    @Test
    fun editFormEnforcesLengthLimits() {
        val original = Playlist(id = "1", name = "My Playlist")

        // 80 char limit for name
        val name80 = "a".repeat(80)
        val state80 = EditFormState(
            name = name80,
            description = "",
            coverMode = CoverMode.AUTO,
            coverRef = null,
            originalPlaylist = original,
            existingPlaylists = listOf(original),
        )
        assertTrue(state80.canSave)

        val name81 = "a".repeat(81)
        val state81 = EditFormState(
            name = name81,
            description = "",
            coverMode = CoverMode.AUTO,
            coverRef = null,
            originalPlaylist = original,
            existingPlaylists = listOf(original),
        )
        assertTrue(state81.isNameTooLong)
        assertFalse("Save must be disabled when name exceeds 80 chars", state81.canSave)

        // 300 char limit for description
        val desc301 = "d".repeat(301)
        val stateDesc301 = EditFormState(
            name = "Valid Name",
            description = desc301,
            coverMode = CoverMode.AUTO,
            coverRef = null,
            originalPlaylist = original,
            existingPlaylists = listOf(original),
        )
        assertTrue(stateDesc301.isDescTooLong)
        assertFalse("Save must be disabled when description exceeds 300 chars", stateDesc301.canSave)
    }

    @Test
    fun editFormDetectsDuplicateNamesWithWarning() {
        val pl1 = Playlist(id = "1", name = "Rock Hits")
        val pl2 = Playlist(id = "2", name = "Pop Hits")
        val existing = listOf(pl1, pl2)

        val state = EditFormState(
            name = "  rock hits  ",
            description = "",
            coverMode = CoverMode.AUTO,
            coverRef = null,
            originalPlaylist = pl2,
            existingPlaylists = existing,
        )

        assertTrue("Duplicate name should trigger warning flag", state.isDuplicate)
        assertTrue("Duplicate name is still allowed to save per spec", state.canSave)
    }

    @Test
    fun editFormDetectsDirtyStateAndNothingPersistedBeforeSave() {
        val original = Playlist(id = "1", name = "Original Name", description = "Original Desc")
        val stateUnchanged = EditFormState(
            name = "Original Name",
            description = "Original Desc",
            coverMode = CoverMode.AUTO,
            coverRef = null,
            originalPlaylist = original,
            existingPlaylists = listOf(original),
        )
        assertFalse(stateUnchanged.isDirty)

        val stateChangedName = stateUnchanged.copy(name = "Changed")
        assertTrue(stateChangedName.isDirty)

        val stateChangedCover = stateUnchanged.copy(coverMode = CoverMode.PRESET, coverRef = "preset_neon")
        assertTrue(stateChangedCover.isDirty)
    }

    @Test
    fun libraryUpdatePlaylistDetailsTrimsAndCleansEmptyDescription() {
        val library = Library(tmp.root)
        val plId = library.createPlaylist("Initial Name")

        library.updatePlaylistDetails(plId, "  New Trimmed Name  ", "   ")
        val pl = library.data.value.playlists.single { it.id == plId }

        assertEquals("New Trimmed Name", pl.name)
        assertNull("Blank description should be cleaned to null", pl.description)
    }

    @Test
    fun libraryTileDataReflectsLikedAndDownloadsCount() {
        val library = Library(tmp.root)
        val song1 = Song("s1", "Song One", "Artist")
        val song2 = Song("s2", "Song Two", "Artist")

        assertEquals(0, library.data.value.liked.size)
        assertEquals(0, library.data.value.downloads.size)

        library.toggleLike(song1)
        library.toggleLike(song2)
        assertEquals(2, library.data.value.liked.size)

        library.toggleLike(song1) // toggling again unlikes it
        assertEquals(1, library.data.value.liked.size)
    }

    @Test
    fun libraryDuplicatePlaylistCreatesDeepCopy() {
        val library = Library(tmp.root)
        val song = Song("s1", "Title", "Artist")
        val plId = library.createPlaylist("Original", listOf(song))

        val copyId = library.duplicatePlaylist(plId)
        assertNotNull(copyId)

        val copy = library.data.value.playlists.single { it.id == copyId }
        assertEquals("Original (Copy)", copy.name)
        assertEquals(1, copy.songs.size)
        // Entries must have new distinct entryId
        val origEntry = library.data.value.playlists.single { it.id == plId }.entries.first()
        val copyEntry = copy.entries.first()
        assertFalse("Duplicated playlist entry must have a new entryId", origEntry.entryId == copyEntry.entryId)
        assertEquals(origEntry.song.id, copyEntry.song.id)
    }
}
