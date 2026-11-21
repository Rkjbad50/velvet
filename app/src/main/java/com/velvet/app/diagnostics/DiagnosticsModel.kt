package com.velvet.app.diagnostics

enum class DiagnosticsVerdict(val plainText: String) {
    HEALTHY("Healthy"),
    NETWORK_PROBLEM("Network problem"),
    URL_EXPIRED_OR_BLOCKED("URL expired or blocked (403)"),
    EXTRACTOR_OUTDATED("Extractor outdated or YouTube changed (parse error)"),
    SONG_SPECIFIC("Song-specific (region/age)"),
    UNKNOWN_FAILURE("Unknown failure"),
}

enum class DiagnosticStep(val stepNumber: Int, val title: String) {
    INTERNET(1, "Internet connectivity (generate_204)"),
    DNS(2, "DNS lookup (youtube.com, music.youtube.com)"),
    EXTRACTOR_INIT(3, "Extractor initialization"),
    RESOLVE_STREAM(4, "Resolve stream for fixed known video"),
    STREAM_HEAD_RANGE(5, "Stream URL HEAD/range test"),
    EXOPLAYER_BUFFER(6, "ExoPlayer prepare & 3 s buffer"),
}

data class CheckResult(
    val step: DiagnosticStep,
    val passed: Boolean,
    val latencyMs: Long,
    val detail: String = "",
    val exception: Throwable? = null,
    val httpStatus: Int? = null,
)

data class DiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val results: List<CheckResult>,
    val verdict: DiagnosticsVerdict,
    val resolvedUrl: String? = null,
)

enum class LogLevel {
    INFO, WARN, ERROR
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
)

data class LastErrorInfo(
    val errorCodeName: String,
    val causeChain: List<String>,
    val httpStatus: Int?,
    val urlHost: String?,
    val videoId: String?,
    val clientUsed: String?,
    val resolveDurationMs: Long?,
    val timestamp: Long = System.currentTimeMillis(),
)
