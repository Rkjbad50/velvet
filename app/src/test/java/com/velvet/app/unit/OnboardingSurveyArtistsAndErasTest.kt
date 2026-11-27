package com.velvet.app.unit

import com.velvet.app.data.ArtistRef
import com.velvet.app.data.LanguageLocaleConfig
import com.velvet.app.data.LibraryData
import com.velvet.app.data.Playlist
import com.velvet.app.data.Song
import com.velvet.app.recommendation.vibe.ArtistLanguageMap
import com.velvet.app.recommendation.vibe.ArtistLanguageRegistry
import com.velvet.app.ui.SurveyArtistCatalog
import com.velvet.app.ui.SurveyArtistEra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class OnboardingSurveyArtistsAndErasTest {

    @Before
    fun clearRegistry() {
        ArtistLanguageRegistry.clearForTest()
    }

    @Test
    fun everyConfiguredLanguageHasArtistsAcrossAllFourSpecificEras() {
        val specificEras = listOf(
            SurveyArtistEra.CLASSICS_50_70,
            SurveyArtistEra.GOLDEN_80_90,
            SurveyArtistEra.ERA_2000S,
            SurveyArtistEra.MODERN_2010_NOW,
        )

        LanguageLocaleConfig.allLanguages.forEach { spec ->
            val entries = SurveyArtistCatalog.artistsByLanguage[spec.id]
            assertTrue("Missing catalog entries for ${spec.id}", !entries.isNullOrEmpty())
            val list = entries!!
            assertTrue("Blank artist name in ${spec.id}", list.none { it.name.isBlank() })
            val uniqueCount = list.map { it.name.trim().lowercase(Locale.ROOT) }.distinct().size
            assertEquals("Duplicate artist found inside ${spec.id}", list.size, uniqueCount)

            specificEras.forEach { era ->
                val countForEra = list.count { it.era == era }
                assertTrue(
                    "Expected at least 1 artist in ${spec.id} for era ${era.name}",
                    countForEra > 0,
                )
            }
        }
    }

    @Test
    fun curatedEntriesRespectsExplicitLanguageSelectionWithoutInjectingHindiOrEnglish() {
        val tamilAll = SurveyArtistCatalog.curatedEntriesFor(
            selectedLanguages = listOf("Tamil"),
            era = SurveyArtistEra.ALL,
        )
        assertTrue(tamilAll.isNotEmpty())
        val names = tamilAll.map { it.name }.toSet()

        assertFalse("Must not force-inject English artists", "The Weeknd" in names)
        assertFalse("Must not force-inject Hindi-only artists", "Arijit Singh" in names)
        assertFalse("Must not force-inject Hindi-only artists", "Mohammed Rafi" in names)

        assertTrue("T.M. Soundararajan" in names)
        assertTrue("Ilaiyaraaja" in names)
        assertTrue("Harris Jayaraj" in names)
        assertTrue("Anirudh Ravichander" in names)

        // All four eras are represented in the returned Tamil entries
        val erasPresent = tamilAll.map { it.era }.toSet()
        assertTrue(SurveyArtistEra.CLASSICS_50_70 in erasPresent)
        assertTrue(SurveyArtistEra.GOLDEN_80_90 in erasPresent)
        assertTrue(SurveyArtistEra.ERA_2000S in erasPresent)
        assertTrue(SurveyArtistEra.MODERN_2010_NOW in erasPresent)

        // Top rows showcase multiple eras rather than burying classics at the very end
        val topFourEras = tamilAll.take(4).map { it.era }.toSet()
        assertEquals(4, topFourEras.size)

        // Empty selection falls back cleanly to starter languages
        val fallback = SurveyArtistCatalog.curatedEntriesFor(emptyList(), SurveyArtistEra.ALL)
        assertTrue(fallback.isNotEmpty())
        assertTrue(fallback.any { it.name == "Arijit Singh" })
        assertTrue(fallback.any { it.name == "The Weeknd" })
        assertTrue(fallback.any { it.name == "Diljit Dosanjh" })
    }

    @Test
    fun filteringBySpecificEraReturnsOnlyEntriesFromThatEra() {
        val specificEras = listOf(
            SurveyArtistEra.CLASSICS_50_70,
            SurveyArtistEra.GOLDEN_80_90,
            SurveyArtistEra.ERA_2000S,
            SurveyArtistEra.MODERN_2010_NOW,
        )

        specificEras.forEach { targetEra ->
            val hindiFiltered = SurveyArtistCatalog.curatedEntriesFor(listOf("Hindi"), targetEra)
            assertTrue("Expected entries for Hindi/$targetEra", hindiFiltered.isNotEmpty())
            assertTrue(
                "Expected only $targetEra entries for Hindi",
                hindiFiltered.all { it.era == targetEra },
            )

            val multiFiltered = SurveyArtistCatalog.curatedEntriesFor(listOf("English", "Urdu"), targetEra)
            assertTrue("Expected entries for English+Urdu/$targetEra", multiFiltered.isNotEmpty())
            assertTrue(
                "Expected only $targetEra entries for multi-language",
                multiFiltered.all { it.era == targetEra },
            )
        }

        val hindi50s = SurveyArtistCatalog.curatedEntriesFor(listOf("Hindi"), SurveyArtistEra.CLASSICS_50_70)
            .map { it.name }
        assertTrue("Mohammed Rafi" in hindi50s)
        assertTrue("Kishore Kumar" in hindi50s)
        assertFalse("Arijit Singh" in hindi50s)
    }

    @Test
    fun filterArtistsForQuerySurfacesLocalClassicsImmediatelyAndDeduplicatesWithRemote() {
        val curated = SurveyArtistCatalog.curatedEntriesFor(listOf("Hindi", "English"), SurveyArtistEra.ALL)
            .map { ArtistRef(url = "", name = it.name, thumb = null) }

        // Queries shorter than 2 chars return curated list unchanged
        assertEquals(curated, SurveyArtistCatalog.filterArtistsForQuery(curated, emptyList(), ""))
        assertEquals(curated, SurveyArtistCatalog.filterArtistsForQuery(curated, emptyList(), "k"))

        // Instant local matches for classics before remote results return
        val kishoreInstant = SurveyArtistCatalog.filterArtistsForQuery(curated, emptyList(), "Kishore")
        assertTrue(kishoreInstant.any { it.name == "Kishore Kumar" })

        val sanuInstant = SurveyArtistCatalog.filterArtistsForQuery(curated, emptyList(), "Sanu")
        assertTrue(sanuInstant.any { it.name == "Kumar Sanu" })

        val beatlesInstant = SurveyArtistCatalog.filterArtistsForQuery(curated, emptyList(), "Beatles")
        assertTrue(beatlesInstant.any { it.name == "The Beatles" })

        // Case-insensitive deduplication with remote results, upgrading thumb when remote provides one
        val remote = listOf(
            ArtistRef(url = "https://music.youtube.com/channel/UC_KISHORE", name = "kishore kumar", thumb = "https://lh3.googleusercontent.com/kishore"),
            ArtistRef(url = "https://music.youtube.com/channel/UC_EXTRA", name = "Kishore Sodha", thumb = "https://lh3.googleusercontent.com/sodha"),
        )
        val merged = SurveyArtistCatalog.filterArtistsForQuery(curated, remote, "Kishore")
        val kishoreHits = merged.filter { it.name.equals("Kishore Kumar", ignoreCase = true) }
        assertEquals(1, kishoreHits.size)
        assertEquals("https://lh3.googleusercontent.com/kishore", kishoreHits.single().thumb)
        assertTrue(merged.any { it.name == "Kishore Sodha" })
    }

    @Test
    fun artistLanguageMapCleanupsAndMultiLanguageCoverageResolveAccurately() {
        assertNull(ArtistLanguageMap.curatedLanguageForArtist("vinyl"))

        assertEquals("ml", ArtistLanguageMap.curatedLanguageForArtist("Hesham Abdul Wahab")?.first)
        assertEquals("hi", ArtistLanguageMap.curatedLanguageForArtist("Mohammed Rafi")?.first)
        assertEquals("hi", ArtistLanguageMap.curatedLanguageForArtist("Kumar Sanu")?.first)
        assertEquals("ur", ArtistLanguageMap.curatedLanguageForArtist("Noor Jehan")?.first)
        assertEquals("mr", ArtistLanguageMap.curatedLanguageForArtist("Ajay-Atul")?.first)

        // Regional & classical expansions
        assertEquals("gu", ArtistLanguageMap.curatedLanguageForArtist("Aditya Gadhvi")?.first)
        assertEquals("bho", ArtistLanguageMap.curatedLanguageForArtist("Pawan Singh")?.first)
        assertEquals("bgc", ArtistLanguageMap.curatedLanguageForArtist("Masoom Sharma")?.first)
        assertEquals("bn", ArtistLanguageMap.curatedLanguageForArtist("Hemanta Mukherjee")?.first)
        assertEquals("sa", ArtistLanguageMap.curatedLanguageForArtist("M.S. Subbulakshmi")?.first)
        assertEquals("ta", ArtistLanguageMap.curatedLanguageForArtist("S.P. Balasubrahmanyam")?.first)
        assertEquals("en", ArtistLanguageMap.curatedLanguageForArtist("Fleetwood Mac")?.first)
        assertEquals("pa", ArtistLanguageMap.curatedLanguageForArtist("Gurdas Maan")?.first)

        // Playlist language tokens cover newly added regional languages
        val lib = LibraryData(
            playlists = listOf(
                Playlist(id = "p_mr", name = "Marathi Hits", songs = listOf(Song("s1", "Track One", "ArtistAlpha"))),
                Playlist(id = "p_gu", name = "Gujarati Garba", songs = listOf(Song("s2", "Track Two", "ArtistBeta"))),
                Playlist(id = "p_bho", name = "Bhojpuri Mix", songs = listOf(Song("s3", "Track Three", "ArtistGamma"))),
                Playlist(id = "p_bgc", name = "Haryanvi Gym", songs = listOf(Song("s4", "Track Four", "ArtistDelta"))),
                Playlist(id = "p_ur", name = "Urdu Ghazals", songs = listOf(Song("s5", "Track Five", "ArtistEpsilon"))),
            ),
        )
        val learned = ArtistLanguageMap.learnFromLibrary(lib, nowMillis = 1_000_000L)
        assertEquals("mr", ArtistLanguageMap.inferArtistLanguage("ArtistAlpha", learned)?.first)
        assertEquals("gu", ArtistLanguageMap.inferArtistLanguage("ArtistBeta", learned)?.first)
        assertEquals("bho", ArtistLanguageMap.inferArtistLanguage("ArtistGamma", learned)?.first)
        assertEquals("bgc", ArtistLanguageMap.inferArtistLanguage("ArtistDelta", learned)?.first)
        assertEquals("ur", ArtistLanguageMap.inferArtistLanguage("ArtistEpsilon", learned)?.first)
    }
}
