package com.velvet.app.unit

import com.velvet.app.data.AppUpdater
import com.velvet.app.data.CertificateMismatchException
import com.velvet.app.data.ReleaseCheck
import com.velvet.app.data.SemVer
import com.velvet.app.data.Sha256MismatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class UpdaterTest {

    // --- SemVer Tests ---

    @Test
    fun semverComparesMultiComponentNumerically() {
        // Essential test case: 0.24.9 < 0.24.10 (string comparison would wrongly say "9" > "10")
        assertTrue(SemVer.isNewer("0.24.10", "0.24.9"))
        assertFalse(SemVer.isNewer("0.24.9", "0.24.10"))
        assertEquals(0, SemVer.compare("0.24.10", "0.24.10"))

        assertTrue(SemVer.isNewer("1.2.3.4", "1.2.3.3"))
        assertFalse(SemVer.isNewer("1.2.3.3", "1.2.3.4"))
        assertTrue(SemVer.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun semverHandlesVAndVersionPrefixes() {
        assertTrue(SemVer.isNewer("v2.7", "2.6"))
        assertTrue(SemVer.isNewer("Version 2.7", "v2.6"))
        assertEquals(0, SemVer.compare("v0.26.5", "0.26.5"))
        assertEquals(0, SemVer.compare("Version 2.6", "2.6"))
        assertFalse(SemVer.isNewer("v2.6", "2.6"))
    }

    @Test
    fun semverHandlesPreReleases() {
        // A standard final release has higher precedence than any pre-release of the same version
        assertTrue(SemVer.isNewer("2.7", "2.7-beta1"))
        assertFalse(SemVer.isNewer("2.7-beta1", "2.7"))

        // Between pre-releases: rc > beta > alpha, and numbered stages
        assertTrue(SemVer.isNewer("2.7-rc1", "2.7-beta1"))
        assertTrue(SemVer.isNewer("2.7-beta2", "2.7-beta1"))
        assertTrue(SemVer.isNewer("2.7-beta1", "2.7-alpha2"))
        assertFalse(SemVer.isNewer("2.7-alpha1", "2.7-beta1"))
    }

    @Test
    fun semverHandlesBuildNumbers() {
        assertTrue(SemVer.isNewer("build-100", "build-99"))
        assertFalse(SemVer.isNewer("build-99", "build-100"))
        assertEquals(0, SemVer.compare("build-51", "build-51"))
    }

    // --- ReleaseCheck Tests ---

    @Test
    fun extractSha256ExtractsFromFormattedReleaseNotes() {
        val notes1 = """
            ### VELVET Version 2.7
            **SHA-256:** `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
            Enjoy the new update!
        """.trimIndent()
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ReleaseCheck.extractSha256(notes1)
        )

        val notes2 = "sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ReleaseCheck.extractSha256(notes2)
        )

        val notesNoSha = "No checksum published in this test release."
        assertNull(ReleaseCheck.extractSha256(notesNoSha))
    }

    // --- AppUpdater Tests ---

    @Test
    fun appUpdaterComputesSha256Correctly() {
        val tempFile = File.createTempFile("velvet-test", ".tmp")
        try {
            tempFile.writeText("Hello VELVET")
            val hash = AppUpdater.computeSha256(tempFile)
            // Known SHA-256 of "Hello VELVET" (UTF-8)
            // echo -n "Hello VELVET" | sha256sum -> 38ca15c5e8fa29813b1a8fce7f1ca542c1615f3ec5633a68ea27fdf0980c6575
            assertEquals("38ca15c5e8fa29813b1a8fce7f1ca542c1615f3ec5633a68ea27fdf0980c6575", hash)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun verifySha256PassesOnMatch() {
        val tempFile = File.createTempFile("velvet-test-verify", ".tmp")
        try {
            tempFile.writeText("Hello VELVET")
            val expected = "38ca15c5e8fa29813b1a8fce7f1ca542c1615f3ec5633a68ea27fdf0980c6575"
            assertTrue(AppUpdater.verifySha256(tempFile, expected))
            assertTrue(tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun verifySha256DeletesAndThrowsOnMismatch() {
        val tempFile = File.createTempFile("velvet-test-mismatch", ".tmp")
        try {
            tempFile.writeText("Corrupted Content")
            val expected = "38ca15c5e8fa29813b1a8fce7f1ca542c1615f3ec5633a68ea27fdf0980c6575"
            try {
                AppUpdater.verifySha256(tempFile, expected)
                fail("Expected Sha256MismatchException was not thrown")
            } catch (_: Sha256MismatchException) {
                // Expected: file must be deleted upon mismatch
                assertFalse("File must be deleted after checksum mismatch", tempFile.exists())
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun matchSignaturesComparesByteArrays() {
        val sig1 = byteArrayOf(1, 2, 3, 4, 5)
        val sig2 = byteArrayOf(1, 2, 3, 4, 5)
        val sig3 = byteArrayOf(9, 8, 7, 6, 5)

        assertTrue(AppUpdater.matchSignatures(listOf(sig1), listOf(sig2)))
        assertFalse(AppUpdater.matchSignatures(listOf(sig1), listOf(sig3)))
        assertFalse(AppUpdater.matchSignatures(emptyList(), listOf(sig1)))
        assertFalse(AppUpdater.matchSignatures(listOf(sig1), emptyList()))
    }

    @Test
    fun verifyCertificatesPassesOnMatch() {
        val tempFile = File.createTempFile("velvet-cert-match", ".tmp")
        try {
            val sig = byteArrayOf(10, 20, 30, 40)
            assertTrue(AppUpdater.verifyCertificates(listOf(sig), listOf(sig), tempFile))
            assertTrue(tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun verifyCertificatesDeletesAndThrowsOnMismatch() {
        val tempFile = File.createTempFile("velvet-cert-mismatch", ".tmp")
        try {
            val installedSig = byteArrayOf(1, 2, 3)
            val foreignSig = byteArrayOf(4, 5, 6)
            try {
                AppUpdater.verifyCertificates(listOf(installedSig), listOf(foreignSig), tempFile)
                fail("Expected CertificateMismatchException was not thrown")
            } catch (_: CertificateMismatchException) {
                assertFalse("File must be deleted when signing certificate mismatches", tempFile.exists())
            }
        } finally {
            tempFile.delete()
        }
    }
}
