package com.velvet.app.unit

import com.velvet.app.data.BackupCodec
import com.velvet.app.data.CoverMode
import com.velvet.app.data.Library
import com.velvet.app.data.LibraryData
import com.velvet.app.data.LibraryStore
import com.velvet.app.data.LoadResult
import com.velvet.app.data.Playlist
import com.velvet.app.data.PlaylistCoverManager
import com.velvet.app.data.PlaylistEntry
import com.velvet.app.data.Song
import com.velvet.app.data.SortDirection
import com.velvet.app.data.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistModelAndCoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testSong = Song("song-1", "Test Title", "Test Artist", 200)

    @Test
    fun legacyDataWithoutNewFieldsMigratesWithDefaults() {
        // Old JSON format before coverMode, coverRef, sortKey, sortDirection, and entries existed
        val legacyJson = """
            {
              "playlists": [
                {
                  "id": "pl-1",
                  "name": "My Old Playlist",
                  "description": "Old description",
                  "songs": [
                    { "id": "s1", "title": "Song 1", "artist": "Artist 1" },
                    { "id": "s2", "title": "Song 2", "artist": "Artist 2" }
                  ]
                }
              ]
            }
        """.trimIndent()

        File(tmp.root, "library.json").writeText(legacyJson)
        val result = LibraryStore(tmp.root).load()
        assertTrue(result is LoadResult.Loaded)
        val data = (result as LoadResult.Loaded).data

        assertEquals(1, data.playlists.size)
        val pl = data.playlists.first()

        assertEquals("My Old Playlist", pl.name)
        assertEquals(CoverMode.AUTO, pl.coverMode)
        assertNull(pl.coverRef)
        assertEquals(SortKey.CUSTOM, pl.sortKey)
        assertEquals(SortDirection.ASC, pl.sortDirection)
        assertEquals(2, pl.entries.size)
        assertEquals("Song 1", pl.entries[0].song.title)
        assertEquals("Song 2", pl.entries[1].song.title)
        assertTrue(pl.entries[0].entryId.isNotBlank())
        assertTrue(pl.entries[1].entryId.isNotBlank())
        // Stored positions as fallback addedAt
        assertEquals(0L, pl.entries[0].addedAt)
        assertEquals(1L, pl.entries[1].addedAt)
    }

    @Test
    fun backupCodecHandlesLegacyAndNewCoverFields() {
        val pl = Playlist(
            id = "pl-backup",
            name = "Backup Playlist",
            description = "Some description",
            songs = listOf(testSong),
            coverMode = CoverMode.PRESET,
            coverRef = "preset_sunset",
            sortKey = SortKey.TITLE,
            sortDirection = SortDirection.DESC,
        )
        val data = LibraryData(playlists = listOf(pl))
        val encoded = BackupCodec.encode(data)
        val decoded = BackupCodec.decode(encoded)

        assertEquals(1, decoded.playlists.size)
        val restored = decoded.playlists.first()
        assertEquals("Backup Playlist", restored.name)
        assertEquals(CoverMode.PRESET, restored.coverMode)
        assertEquals("preset_sunset", restored.coverRef)
        assertEquals(SortKey.TITLE, restored.sortKey)
        assertEquals(SortDirection.DESC, restored.sortDirection)
    }

    @Test
    fun missingCustomCoverFileFallsBackToAuto() {
        val nonExistentCover = "missing_cover_file_123.jpg"
        val pl = Playlist(
            id = "pl-missing-cover",
            name = "Missing Cover Playlist",
            songs = listOf(testSong),
            coverMode = CoverMode.CUSTOM_IMAGE,
            coverRef = nonExistentCover,
        )

        // When custom cover file does not exist on disk, customCover getter falls back to null,
        // and coverMode can be treated as AUTO in rendering.
        val resolvedCover = pl.customCover
        assertNull("Missing custom cover file should return null customCover", resolvedCover)
    }

    @Test
    fun coverFileLifecycleOnRevertAndReplaceAndDelete() {
        val rootDir = tmp.root
        val library = Library(rootDir)
        val coversDir = File(rootDir, "covers").apply { mkdirs() }

        val plId = library.createPlaylist("Art Playlist", listOf(testSong))

        // 1. Simulate saving a custom cover file
        val customFile1 = File(coversDir, "${plId}_1000.jpg").apply { writeText("fake-image-1") }
        assertTrue(customFile1.exists())
        library.setPlaylistCover(plId, CoverMode.CUSTOM_IMAGE, customFile1.name)

        var pl = library.data.value.playlists.single { it.id == plId }
        assertEquals(CoverMode.CUSTOM_IMAGE, pl.coverMode)
        assertEquals(customFile1.name, pl.coverRef)

        // 2. Replace cover with new custom cover -> old file is deleted
        val customFile2 = File(coversDir, "${plId}_2000.jpg").apply { writeText("fake-image-2") }
        library.setPlaylistCover(plId, CoverMode.CUSTOM_IMAGE, customFile2.name)

        assertFalse("Previous custom cover file should be deleted on replacement", customFile1.exists())
        assertTrue("New custom cover file should exist", customFile2.exists())

        // 3. Revert to default (AUTO) -> customFile2 is deleted
        library.setPlaylistCover(plId, CoverMode.AUTO, null)
        pl = library.data.value.playlists.single { it.id == plId }
        assertEquals(CoverMode.AUTO, pl.coverMode)
        assertNull(pl.coverRef)
        assertFalse("Custom cover file should be deleted on revert to AUTO", customFile2.exists())

        // 4. Set another custom cover then delete playlist -> cover file deleted
        val customFile3 = File(coversDir, "${plId}_3000.jpg").apply { writeText("fake-image-3") }
        library.setPlaylistCover(plId, CoverMode.CUSTOM_IMAGE, customFile3.name)
        assertTrue(customFile3.exists())

        library.deletePlaylist(plId)
        assertFalse("Custom cover file must be deleted when playlist is deleted", customFile3.exists())
    }

    @Test
    fun pureMathCalculateSquareCrop() {
        // Landscape: 1920x1080 -> offset left = (1920-1080)/2 = 420
        val cropLandscape = PlaylistCoverManager.calculateSquareCrop(1920, 1080)
        assertEquals(420, cropLandscape.left)
        assertEquals(0, cropLandscape.top)
        assertEquals(1500, cropLandscape.right)
        assertEquals(1080, cropLandscape.bottom)
        assertEquals(1080, cropLandscape.width)
        assertEquals(1080, cropLandscape.height)

        // Portrait: 1080x1920 -> offset top = (1920-1080)/2 = 420
        val cropPortrait = PlaylistCoverManager.calculateSquareCrop(1080, 1920)
        assertEquals(0, cropPortrait.left)
        assertEquals(420, cropPortrait.top)
        assertEquals(1080, cropPortrait.right)
        assertEquals(1500, cropPortrait.bottom)
        assertEquals(1080, cropPortrait.width)
        assertEquals(1080, cropPortrait.height)

        // Square: 500x500
        val cropSquare = PlaylistCoverManager.calculateSquareCrop(500, 500)
        assertEquals(0, cropSquare.left)
        assertEquals(0, cropSquare.top)
        assertEquals(500, cropSquare.right)
        assertEquals(500, cropSquare.bottom)

        // Invalid: 0 or negative
        val cropZero = PlaylistCoverManager.calculateSquareCrop(0, 100)
        assertEquals(0, cropZero.width)
    }

    @Test
    fun pureMathCalculateInSampleSize() {
        assertEquals(1, PlaylistCoverManager.calculateInSampleSize(800, 600, 1024, 1024))
        assertEquals(2, PlaylistCoverManager.calculateInSampleSize(4000, 3000, 1024, 1024))
        assertEquals(4, PlaylistCoverManager.calculateInSampleSize(8000, 8000, 1024, 1024))
    }

    @Test
    fun pureMathCalculateTargetSize() {
        // Over max limit -> capped at 1024
        assertEquals(Pair(1024, 1024), PlaylistCoverManager.calculateTargetSize(2048, 2048, 1024))
        // Under max limit -> unchanged
        assertEquals(Pair(512, 512), PlaylistCoverManager.calculateTargetSize(512, 512, 1024))
        // Invalid
        assertEquals(Pair(0, 0), PlaylistCoverManager.calculateTargetSize(0, 0, 1024))
    }

    @Test
    fun pureMathExifOrientationToDegrees() {
        assertEquals(90f, PlaylistCoverManager.exifOrientationToDegrees(6)) // ORIENTATION_ROTATE_90 = 6
        assertEquals(180f, PlaylistCoverManager.exifOrientationToDegrees(3)) // ORIENTATION_ROTATE_180 = 3
        assertEquals(270f, PlaylistCoverManager.exifOrientationToDegrees(8)) // ORIENTATION_ROTATE_270 = 8
        assertEquals(0f, PlaylistCoverManager.exifOrientationToDegrees(1)) // ORIENTATION_NORMAL = 1
        assertEquals(0f, PlaylistCoverManager.exifOrientationToDegrees(0)) // Unknown
    }
}
