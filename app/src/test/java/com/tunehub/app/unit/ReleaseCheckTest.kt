package com.tunehub.app.unit

import com.tunehub.app.data.ReleaseCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReleaseCheckTest {
    @Test
    fun comparesUpstreamBuildTagsNumerically() {
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"build-100","id":12}""", 99))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"build-51"}""", 51))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"build-9"}""", 51))
    }

    @Test
    fun supportsLegacyLatestTag() {
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"latest","name":"TuneHub build 27"}""", 26))
    }

    @Test
    fun supportsSemanticVersioning() {
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.1","name":"Version 2.1"}""", 0, "2.0"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v3.0","name":"Version 3.0"}""", 0, "2.0"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.0","name":"Version 2.0"}""", 0, "2.0"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v1.9","name":"Version 1.9"}""", 0, "2.0"))

        // Version 2.1 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.1","name":"Version 2.1"}""", 0, "2.1"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.2","name":"Version 2.2"}""", 0, "2.1"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.0","name":"Version 2.0"}""", 0, "2.1"))

        // Version 2.2 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.2","name":"Version 2.2"}""", 0, "2.2"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.3","name":"Version 2.3"}""", 0, "2.2"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.1","name":"Version 2.1"}""", 0, "2.2"))

        // Version 2.3 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.3","name":"Version 2.3"}""", 0, "2.3"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.4","name":"Version 2.4"}""", 0, "2.3"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.2","name":"Version 2.2"}""", 0, "2.3"))
    }

    @Test(expected = IOException::class)
    fun unrecognizedReleaseIsAnError() {
        ReleaseCheck.isNewer("""{"tag_name":"unknown","name":"invalid"}""", 0)
    }
}
