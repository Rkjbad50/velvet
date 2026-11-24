package com.velvet.app.recommendation.model

import kotlinx.serialization.Serializable

/**
 * Distinct recommendation entry-point modes across VELVET.
 * Allows the core engine (future phases) and Phase 1 telemetry to distinguish surfaces clearly.
 */
@Serializable
enum class RecommendationMode {
    SIMILAR_SONGS,
    SONG_RADIO,
    ARTIST_RADIO,
    NEXT_SONG,
    AUTOPLAY_REFILL,
    HOME_SONGS,
    HOME_RADIOS,
    HOME_PLAYLISTS,
    PERSONALIZED_PLAYLIST,
}

/**
 * UI or playback surface that originated an impression, click, queue action, or playback session.
 */
@Serializable
enum class RecommendationSurface {
    PLAYER_NEXT,
    SIMILAR_SONGS,
    SONG_RADIO,
    ARTIST_RADIO,
    AUTOPLAY_QUEUE,
    HOME_SONG_RAIL,
    HOME_RADIO_RAIL,
    HOME_PLAYLIST_RAIL,
    GENERATED_PLAYLIST,
    SEARCH,
    LIBRARY,
    OTHER,
}

/**
 * Typed behavioural event vocabulary for local learning and recommendation evaluation.
 */
@Serializable
enum class RecommendationEventType {
    SEARCH_SUBMITTED,
    SEARCH_RESULT_IMPRESSION,
    SEARCH_RESULT_CLICKED,
    TRACK_DETAIL_OPENED,
    TRACK_PLAY_REQUESTED,
    TRACK_PLAY_STARTED,
    TRACK_MEANINGFUL_LISTEN,
    TRACK_COMPLETED,
    TRACK_SKIPPED_EARLY,
    TRACK_SKIPPED_LATE,
    TRACK_REPLAYED_MANUALLY,
    TRACK_REPEATED_AUTOMATICALLY,
    TRACK_LIKED,
    TRACK_UNLIKED,
    TRACK_ADDED_TO_PLAYLIST,
    TRACK_REMOVED_FROM_PLAYLIST,
    PLAYLIST_STARTED,
    PLAYLIST_MEANINGFUL_LISTEN,
    RADIO_STARTED,
    RADIO_CONTINUED,
    RADIO_DISMISSED,
    AUTOPLAY_CANDIDATE_IMPRESSION,
    AUTOPLAY_TRACK_STARTED,
    AUTOPLAY_TRACK_COMPLETED,
    AUTOPLAY_TRACK_SKIPPED,
    QUEUE_PLAY_NEXT,
    QUEUE_ADDED,
    QUEUE_REMOVED,
    RECOMMENDATION_NOT_INTERESTED,
    RECOMMENDATION_HIDDEN,
}

/**
 * Versioned, idempotent recommendation and listening signal event.
 * Raw queries remain local-only by default and can be cleared independently.
 */
@Serializable
data class RecommendationEvent(
    val eventId: String,
    val eventType: RecommendationEventType,
    val occurredAtMillis: Long,
    val canonicalRecordingId: String? = null,
    val providerItemId: String? = null,
    val artistIds: List<String> = emptyList(),
    val albumOrWorkId: String? = null,
    val queryId: String? = null,
    val normalizedQuery: String? = null,
    val resultPosition: Int? = null,
    val requestId: String? = null,
    val recommendationSessionId: String? = null,
    val shelfId: String? = null,
    val radioSessionId: String? = null,
    val playlistContextId: String? = null,
    val previousRecordingId: String? = null,
    val nextRecordingId: String? = null,
    val mode: RecommendationMode? = null,
    val surface: RecommendationSurface? = null,
    val sourceId: String? = null,
    val rankPosition: Int? = null,
    val playedMs: Long? = null,
    val durationMs: Long? = null,
    val completionRatio: Double? = null,
    val repeatMode: String? = null,
    val playbackTransitionReason: String? = null,
    val sessionContextTags: Set<String> = emptySet(),
    val modelVersion: String? = null,
    val idempotencyKey: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Lightweight attribution payload carried from UI click sites into PlayerConnection
 * so playback events record exact origin (Search query/position, Home shelf, Library, etc.).
 */
data class PlaybackAttribution(
    val surface: RecommendationSurface = RecommendationSurface.OTHER,
    val mode: RecommendationMode? = null,
    val requestId: String? = null,
    val queryId: String? = null,
    val normalizedQuery: String? = null,
    val shelfId: String? = null,
    val resultPosition: Int? = null,
    val rankPosition: Int? = null,
    val sourceId: String? = null,
    val recommendationSessionId: String? = null,
    val playlistContextId: String? = null,
    val sessionContextTags: Set<String> = emptySet(),
)
