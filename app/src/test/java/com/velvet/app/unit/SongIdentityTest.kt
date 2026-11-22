package com.velvet.app.unit

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class GoldenPair(
    val id: String,
    val expected: String, // "SAME" or "DIFFERENT"
    val songA: Song,
    val songB: Song,
)

class SongIdentityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testGoldenSet_precisionAndRecall() {
        val stream = javaClass.classLoader?.getResourceAsStream("identity_golden.json")
            ?: error("identity_golden.json not found in test resources")
        val content = stream.bufferedReader().use { it.readText() }
        val pairs = json.decodeFromString<List<GoldenPair>>(content)

        assertTrue("Golden set must have at least 80 pairs", pairs.size >= 80)

        var tp = 0 // Expected SAME, score >= STRICT
        var fn = 0 // Expected SAME, score < STRICT
        var fp = 0 // Expected DIFFERENT, score >= STRICT
        var tn = 0 // Expected DIFFERENT, score < STRICT

        for (pair in pairs) {
            val score = SongIdentity.sameSong(pair.songA, pair.songB)
            val isMatch = score >= SongIdentity.STRICT_THRESHOLD

            if (pair.expected == "SAME") {
                if (isMatch) tp++ else fn++
            } else {
                if (isMatch) {
                    fp++
                    System.err.println("FALSE POSITIVE: ${pair.id}: '${pair.songA.title}' vs '${pair.songB.title}', score=$score")
                } else {
                    tn++
                }
            }
        }

        val totalSame = tp + fn
        val totalDiff = tn + fp
        val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp) else 0.0
        val recall = if (totalSame > 0) tp.toDouble() / totalSame else 0.0

        println("================ CONFUSION TABLE (STRICT >= ${SongIdentity.STRICT_THRESHOLD}) ================")
        println("Total Pairs: ${pairs.size} (SAME: $totalSame, DIFFERENT: $totalDiff)")
        println("True Positives  (TP): $tp")
        println("False Positives (FP): $fp")
        println("True Negatives  (TN): $tn")
        println("False Negatives (FN): $fn")
        println("Precision: %.4f".format(precision))
        println("Recall:    %.4f".format(recall))
        println("==========================================================================")

        // Strict Requirement: ZERO false merges on DIFFERENT set
        assertEquals("Zero false merges allowed on the DIFFERENT set at STRICT threshold", 0, fp)

        // Strict Requirement: At least 90% recall on the SAME set
        assertTrue("Recall on SAME set must be >= 0.90 (was $recall)", recall >= 0.90)
    }

    @Test
    fun testTitleNormalization_stripsNoiseAndPreservesVersionTags() {
        val parsed = SongIdentity.normalizeTitle("Aankhon Ki Gustakhiyan (From \"Hum Dil De Chuke Sanam\") [Official 4K Video]")
        assertEquals("Hum Dil De Chuke Sanam", parsed.movie)
        assertFalse(parsed.cleanTitle.contains("4k"))
        assertFalse(parsed.cleanTitle.contains("official"))
        assertTrue(parsed.cleanTitle.contains("gustakhiyan"))
    }

    @Test
    fun testHindiLatinTransliteration_collapsesSpellingVariants() {
        val key1 = SongIdentity.collapseHindiLatinVariants("aankhon ki gustakhiyan")
        val key2 = SongIdentity.collapseHindiLatinVariants("ankhon ki gustakhiyaan")
        assertEquals(key1, key2)

        val key3 = SongIdentity.collapseHindiLatinVariants("tujhe dekha toh yeh jaana sanam")
        val key4 = SongIdentity.collapseHindiLatinVariants("tujhe dekha to ye jana sanam")
        assertEquals(key3, key4)
    }

    @Test
    fun testDevanagariToLatin_enablesCrossScriptMatching() {
        val songDevanagari = Song(id = "dev1", title = "श्री हनुमान चालीसा", artist = "Hariharan", durationSec = 580)
        val songLatin = Song(id = "lat1", title = "Shree Hanuman Chalisa", artist = "Hariharan", durationSec = 582)
        val score = SongIdentity.sameSong(songDevanagari, songLatin)
        assertTrue("Devanagari vs Latin song should match strictly: score=$score", score >= SongIdentity.STRICT_THRESHOLD)
    }

    @Test
    fun testSemanticVersionTags_preventsRemixFromMatchingOriginal() {
        val orig = Song(id = "orig", title = "Tum Hi Ho", artist = "Arijit Singh", durationSec = 262)
        val remix = Song(id = "remix", title = "Tum Hi Ho (Remix)", artist = "Arijit Singh", durationSec = 240)
        val score = SongIdentity.sameSong(orig, remix)
        assertEquals("Remix must score 0 against original", 0.0, score, 0.001)
        assertFalse("Remix should never match original", SongIdentity.isStrictMatch(orig, remix))
    }

    @Test
    fun testCanonicalGrouping_clustersDuplicateUploads() {
        val s1 = Song(id = "1", title = "Kesariya (From \"Brahmastra\")", artist = "Arijit Singh", durationSec = 268)
        val s2 = Song(id = "2", title = "Kesariya - Full Video Song", artist = "Pritam, Arijit Singh", durationSec = 270)
        val s3 = Song(id = "3", title = "Channa Mereya", artist = "Arijit Singh", durationSec = 289)

        val groups = SongIdentity.canonicalGroupId(listOf(s1, s2, s3))
        assertEquals(groups[s1.id], groups[s2.id])
        assertTrue(groups[s1.id] != groups[s3.id])
    }

    @Test
    fun testRepresentativePicker_prefersPlayedOrLikedThenAtv() {
        val ugc = Song(id = "ugc", title = "Tum Hi Ho Lyrical", artist = "User Channel", durationSec = 262, preferredMusicSource = false)
        val atv = Song(id = "atv", title = "Tum Hi Ho", artist = "Arijit Singh - Topic", durationSec = 262, preferredMusicSource = true)

        val winner1 = SongIdentity.selectRepresentative(listOf(ugc, atv))
        assertEquals("ATV should be selected over UGC", atv.id, winner1.id)

        val winner2 = SongIdentity.selectRepresentative(listOf(ugc, atv), playedOrLikedIds = setOf("ugc"))
        assertEquals("User previously played/liked ID must be preserved for history continuity", ugc.id, winner2.id)
    }
}
