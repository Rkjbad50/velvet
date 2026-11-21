package com.velvet.app.diagnostics

import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pure-function mapper from diagnostic check results to a plain-language verdict.
 */
object DiagnosticsVerdictMapper {

    fun computeVerdict(results: List<CheckResult>): DiagnosticsVerdict {
        if (results.isEmpty()) return DiagnosticsVerdict.HEALTHY
        val failed = results.firstOrNull { !it.passed } ?: return DiagnosticsVerdict.HEALTHY

        val ex = failed.exception
        val status = failed.httpStatus

        // Step 1: Internet or Step 2: DNS failure
        if (failed.step == DiagnosticStep.INTERNET || failed.step == DiagnosticStep.DNS) {
            return DiagnosticsVerdict.NETWORK_PROBLEM
        }

        // If any step failed with network connectivity / timeout / DNS
        if (isNetworkException(ex)) {
            return DiagnosticsVerdict.NETWORK_PROBLEM
        }

        // Song-specific issues (region restrictions, age gate, content unavailable)
        if (isSongSpecificException(ex)) {
            return DiagnosticsVerdict.SONG_SPECIFIC
        }

        // Expired or 403/410 blocked URLs
        if (isForbiddenOrExpired(ex, status)) {
            return DiagnosticsVerdict.URL_EXPIRED_OR_BLOCKED
        }

        // Step 3: Extractor init or Step 4: Stream resolve parsing/extraction failure
        if (failed.step == DiagnosticStep.EXTRACTOR_INIT || failed.step == DiagnosticStep.RESOLVE_STREAM) {
            return DiagnosticsVerdict.EXTRACTOR_OUTDATED
        }

        // Step 5: HEAD/Range test or Step 6: ExoPlayer buffer
        if (failed.step == DiagnosticStep.STREAM_HEAD_RANGE) {
            return DiagnosticsVerdict.URL_EXPIRED_OR_BLOCKED
        }

        if (failed.step == DiagnosticStep.EXOPLAYER_BUFFER) {
            return if (isForbiddenOrExpired(ex, status)) {
                DiagnosticsVerdict.URL_EXPIRED_OR_BLOCKED
            } else {
                DiagnosticsVerdict.UNKNOWN_FAILURE
            }
        }

        return DiagnosticsVerdict.UNKNOWN_FAILURE
    }

    fun isNetworkException(ex: Throwable?): Boolean {
        if (ex == null) return false
        val chain = generateSequence(ex) { it.cause }.toList()
        return chain.any {
            it is UnknownHostException ||
                it is SocketTimeoutException ||
                it is ConnectException ||
                it is NoRouteToHostException ||
                it.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                it.message?.contains("Failed to connect", ignoreCase = true) == true ||
                it.message?.contains("Network is unreachable", ignoreCase = true) == true ||
                it.message?.contains("timed out", ignoreCase = true) == true
        }
    }

    fun isSongSpecificException(ex: Throwable?): Boolean {
        if (ex == null) return false
        val chain = generateSequence(ex) { it.cause }.toList()
        return chain.any {
            val name = it.javaClass.simpleName
            val msg = it.message.orEmpty()
            name.contains("ContentNotAvailable", ignoreCase = true) ||
                name.contains("AgeRestricted", ignoreCase = true) ||
                name.contains("GeographicRestriction", ignoreCase = true) ||
                msg.contains("not available", ignoreCase = true) ||
                msg.contains("age restricted", ignoreCase = true) ||
                msg.contains("geographic", ignoreCase = true) ||
                msg.contains("country", ignoreCase = true) ||
                msg.contains("region", ignoreCase = true) ||
                msg.contains("unplayable", ignoreCase = true)
        }
    }

    fun isForbiddenOrExpired(ex: Throwable?, status: Int?): Boolean {
        if (status == 403 || status == 410) return true
        if (ex == null) return false
        val chain = generateSequence(ex) { it.cause }.toList()
        return chain.any {
            val msg = it.message.orEmpty()
            msg.contains("403", ignoreCase = true) ||
                msg.contains("410", ignoreCase = true) ||
                msg.contains("forbidden", ignoreCase = true) ||
                msg.contains("expired", ignoreCase = true) ||
                (it is HttpDataSource.InvalidResponseCodeException && (it.responseCode == 403 || it.responseCode == 410))
        }
    }
}
