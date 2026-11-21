package com.velvet.app.unit

import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsLoggerTest {

    @Before
    fun setup() {
        DiagnosticsLogger.clear()
    }

    @Test
    fun ringBufferMaintainsMax500Capacity() {
        repeat(550) { i ->
            DiagnosticsLogger.log(LogLevel.INFO, "Test", "Message #$i")
        }

        val entries = DiagnosticsLogger.entries.value
        assertEquals("Ring buffer must cap at 500 entries", 500, entries.size)
        // First entry should be #50 (oldest 50 evicted)
        assertEquals("Message #50", entries.first().message)
        // Last entry should be #549
        assertEquals("Message #549", entries.last().message)
    }

    @Test
    fun redactsCookiesProperly() {
        val input = "Request headers: Cookie: SID=secret_session_token_123; HSID=abc;"
        val redacted = DiagnosticsLogger.redact(input)
        assertFalse("Raw session token must not appear", redacted.contains("secret_session_token_123"))
        assertTrue("Redaction tag must be present", redacted.contains("[REDACTED_COOKIE]"))
    }

    @Test
    fun redactsAuthorizationTokensProperly() {
        val input = "Sending request with authorization: Bearer ya29.a0AfH6SMD_very_secret_token"
        val redacted = DiagnosticsLogger.redact(input)
        assertFalse("Bearer token must not appear", redacted.contains("ya29.a0AfH6SMD"))
        assertTrue("Auth redaction tag must be present", redacted.contains("[REDACTED_AUTH]"))
    }

    @Test
    fun redactsSignedStreamingUrlsToHostAndVideoIdOnly() {
        val signedUrl = "https://rr3---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1711234567&ei=abc&ip=1.2.3.4&id=dQw4w9WgXcQ&itag=251&source=youtube&signature=SECRET_SIG"
        val redacted = DiagnosticsLogger.redact("Playing url: $signedUrl in ExoPlayer")

        // Host must be present
        assertTrue("Host must remain", redacted.contains("rr3---sn-4g5ednkk.googlevideo.com"))
        // videoId must remain
        assertTrue("videoId must remain", redacted.contains("videoId=dQw4w9WgXcQ"))
        // Query signature and secrets must be gone
        assertFalse("Signature parameter must be redacted", redacted.contains("SECRET_SIG"))
        assertFalse("IP parameter must be redacted", redacted.contains("1.2.3.4"))
        assertFalse("Expire parameter must be redacted", redacted.contains("1711234567"))
    }

    @Test
    fun clearWipesAllLogs() {
        DiagnosticsLogger.i("Test", "Entry 1")
        DiagnosticsLogger.w("Test", "Entry 2")
        assertEquals(2, DiagnosticsLogger.entries.value.size)

        DiagnosticsLogger.clear()
        assertTrue(DiagnosticsLogger.entries.value.isEmpty())
    }
}
