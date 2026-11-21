package com.velvet.app.unit

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.velvet.app.diagnostics.CheckResult
import com.velvet.app.diagnostics.DiagnosticStep
import com.velvet.app.diagnostics.DiagnosticsVerdict
import com.velvet.app.diagnostics.DiagnosticsVerdictMapper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DiagnosticsVerdictTest {

    data class TestCase(
        val name: String,
        val results: List<CheckResult>,
        val expectedVerdict: DiagnosticsVerdict,
        val expectedPlainText: String,
    )

    private fun pass(step: DiagnosticStep, latency: Long = 100) =
        CheckResult(step = step, passed = true, latencyMs = latency, detail = "OK")

    private fun fail(
        step: DiagnosticStep,
        exception: Throwable? = null,
        httpStatus: Int? = null,
        detail: String = "Failed",
        latency: Long = 150
    ) = CheckResult(
        step = step,
        passed = false,
        latencyMs = latency,
        detail = detail,
        exception = exception,
        httpStatus = httpStatus
    )

    @Test
    fun tableDrivenVerdictTests() {
        val testCases = listOf(
            TestCase(
                name = "All 6 checks pass -> HEALTHY",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    pass(DiagnosticStep.RESOLVE_STREAM),
                    pass(DiagnosticStep.STREAM_HEAD_RANGE),
                    pass(DiagnosticStep.EXOPLAYER_BUFFER),
                ),
                expectedVerdict = DiagnosticsVerdict.HEALTHY,
                expectedPlainText = "Healthy",
            ),
            TestCase(
                name = "Empty results -> HEALTHY",
                results = emptyList(),
                expectedVerdict = DiagnosticsVerdict.HEALTHY,
                expectedPlainText = "Healthy",
            ),
            TestCase(
                name = "Step 1 Internet fails with UnknownHostException -> NETWORK_PROBLEM",
                results = listOf(
                    fail(DiagnosticStep.INTERNET, exception = UnknownHostException("Unable to resolve host")),
                ),
                expectedVerdict = DiagnosticsVerdict.NETWORK_PROBLEM,
                expectedPlainText = "Network problem",
            ),
            TestCase(
                name = "Step 1 Internet fails with SocketTimeoutException -> NETWORK_PROBLEM",
                results = listOf(
                    fail(DiagnosticStep.INTERNET, exception = SocketTimeoutException("connect timed out")),
                ),
                expectedVerdict = DiagnosticsVerdict.NETWORK_PROBLEM,
                expectedPlainText = "Network problem",
            ),
            TestCase(
                name = "Step 1 Internet returns HTTP 500 -> NETWORK_PROBLEM",
                results = listOf(
                    fail(DiagnosticStep.INTERNET, httpStatus = 500, detail = "HTTP 500"),
                ),
                expectedVerdict = DiagnosticsVerdict.NETWORK_PROBLEM,
                expectedPlainText = "Network problem",
            ),
            TestCase(
                name = "Step 2 DNS fails -> NETWORK_PROBLEM",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    fail(DiagnosticStep.DNS, exception = UnknownHostException("No address associated with hostname")),
                ),
                expectedVerdict = DiagnosticsVerdict.NETWORK_PROBLEM,
                expectedPlainText = "Network problem",
            ),
            TestCase(
                name = "Step 3 Extractor init fails -> EXTRACTOR_OUTDATED",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    fail(DiagnosticStep.EXTRACTOR_INIT, exception = RuntimeException("Downloader was null")),
                ),
                expectedVerdict = DiagnosticsVerdict.EXTRACTOR_OUTDATED,
                expectedPlainText = "Extractor outdated or YouTube changed (parse error)",
            ),
            TestCase(
                name = "Step 4 Stream resolve fails with parsing exception -> EXTRACTOR_OUTDATED",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    fail(DiagnosticStep.RESOLVE_STREAM, exception = IOException("Could not parse streaming data json")),
                ),
                expectedVerdict = DiagnosticsVerdict.EXTRACTOR_OUTDATED,
                expectedPlainText = "Extractor outdated or YouTube changed (parse error)",
            ),
            TestCase(
                name = "Step 4 Stream resolve fails with age restriction -> SONG_SPECIFIC",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    fail(DiagnosticStep.RESOLVE_STREAM, exception = IOException("Age restricted content: sign in required")),
                ),
                expectedVerdict = DiagnosticsVerdict.SONG_SPECIFIC,
                expectedPlainText = "Song-specific (region/age)",
            ),
            TestCase(
                name = "Step 4 Stream resolve fails with country/geographic restriction -> SONG_SPECIFIC",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    fail(DiagnosticStep.RESOLVE_STREAM, exception = IOException("Video not available in your region")),
                ),
                expectedVerdict = DiagnosticsVerdict.SONG_SPECIFIC,
                expectedPlainText = "Song-specific (region/age)",
            ),
            TestCase(
                name = "Step 4 Stream resolve fails with unplayable status -> SONG_SPECIFIC",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    fail(DiagnosticStep.RESOLVE_STREAM, exception = IOException("Not playable: UNPLAYABLE")),
                ),
                expectedVerdict = DiagnosticsVerdict.SONG_SPECIFIC,
                expectedPlainText = "Song-specific (region/age)",
            ),
            TestCase(
                name = "Step 4 Stream resolve fails with connection timeout -> NETWORK_PROBLEM",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    fail(DiagnosticStep.RESOLVE_STREAM, exception = ConnectException("Failed to connect to googlevideo")),
                ),
                expectedVerdict = DiagnosticsVerdict.NETWORK_PROBLEM,
                expectedPlainText = "Network problem",
            ),
            TestCase(
                name = "Step 5 Stream HEAD/Range returns HTTP 403 -> URL_EXPIRED_OR_BLOCKED",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    pass(DiagnosticStep.RESOLVE_STREAM),
                    fail(DiagnosticStep.STREAM_HEAD_RANGE, httpStatus = 403, detail = "HTTP 403 response"),
                ),
                expectedVerdict = DiagnosticsVerdict.URL_EXPIRED_OR_BLOCKED,
                expectedPlainText = "URL expired or blocked (403)",
            ),
            TestCase(
                name = "Step 5 Stream HEAD/Range returns HTTP 410 -> URL_EXPIRED_OR_BLOCKED",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    pass(DiagnosticStep.RESOLVE_STREAM),
                    fail(DiagnosticStep.STREAM_HEAD_RANGE, httpStatus = 410, detail = "HTTP 410 Gone"),
                ),
                expectedVerdict = DiagnosticsVerdict.URL_EXPIRED_OR_BLOCKED,
                expectedPlainText = "URL expired or blocked (403)",
            ),
            TestCase(
                name = "Step 5 Stream HEAD/Range fails with network exception -> NETWORK_PROBLEM",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    pass(DiagnosticStep.RESOLVE_STREAM),
                    fail(DiagnosticStep.STREAM_HEAD_RANGE, exception = SocketTimeoutException("Read timed out")),
                ),
                expectedVerdict = DiagnosticsVerdict.NETWORK_PROBLEM,
                expectedPlainText = "Network problem",
            ),
            TestCase(
                name = "Step 6 ExoPlayer fails with 403 InvalidResponseCodeException -> URL_EXPIRED_OR_BLOCKED",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    pass(DiagnosticStep.RESOLVE_STREAM),
                    pass(DiagnosticStep.STREAM_HEAD_RANGE),
                    fail(
                        DiagnosticStep.EXOPLAYER_BUFFER,
                        exception = HttpDataSource.InvalidResponseCodeException(
                            403,
                            "Forbidden",
                            emptyMap(),
                            DataSpec(android.net.Uri.EMPTY)
                        ),
                        httpStatus = 403
                    ),
                ),
                expectedVerdict = DiagnosticsVerdict.URL_EXPIRED_OR_BLOCKED,
                expectedPlainText = "URL expired or blocked (403)",
            ),
            TestCase(
                name = "Step 6 ExoPlayer fails with network exception -> NETWORK_PROBLEM",
                results = listOf(
                    pass(DiagnosticStep.INTERNET),
                    pass(DiagnosticStep.DNS),
                    pass(DiagnosticStep.EXTRACTOR_INIT),
                    pass(DiagnosticStep.RESOLVE_STREAM),
                    pass(DiagnosticStep.STREAM_HEAD_RANGE),
                    fail(DiagnosticStep.EXOPLAYER_BUFFER, exception = UnknownHostException("Network down")),
                ),
                expectedVerdict = DiagnosticsVerdict.NETWORK_PROBLEM,
                expectedPlainText = "Network problem",
            ),
        )

        testCases.forEach { tc ->
            val verdict = DiagnosticsVerdictMapper.computeVerdict(tc.results)
            assertEquals("Failed for case: ${tc.name}", tc.expectedVerdict, verdict)
            assertEquals("Plain text mismatch for case: ${tc.name}", tc.expectedPlainText, verdict.plainText)
        }
    }
}
