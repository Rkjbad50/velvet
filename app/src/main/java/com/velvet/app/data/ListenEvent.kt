package com.velvet.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class ListenEndReason {
    COMPLETED,
    SKIP_EARLY, // < 30s
    SKIP_LATE,
    REPLAY,
}

@Serializable
enum class ListenContext {
    AUTOPLAY,
    PLAYLIST,
    ALBUM,
    ARTIST,
    SEARCH,
    QUEUE,
}

@Serializable
data class ListenEvent(
    val songGroupId: String,
    val videoId: String,
    val artistSet: List<String> = emptyList(),
    val movie: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val playedMs: Long = 0L,
    val durationMs: Long = 0L,
    val completionPct: Float = 0f,
    val endReason: ListenEndReason = ListenEndReason.COMPLETED,
    val context: ListenContext = ListenContext.AUTOPLAY,
    val liked: Boolean = false,
)
