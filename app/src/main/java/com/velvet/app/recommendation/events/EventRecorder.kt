package com.velvet.app.recommendation.events

import com.velvet.app.data.Song
import com.velvet.app.playback.SongIdentity
import com.velvet.app.recommendation.model.PlaybackAttribution
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import com.velvet.app.recommendation.model.RecommendationMode
import com.velvet.app.recommendation.model.RecommendationSurface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Metadata recorded when Autoplay / recommendation pipelines supply a queued track,
 * preserved by videoId so downstream playback/skip/dismiss events link back to source/request/rank.
 */
data class QueuedCandidateProvenance(
    val requestId: String,
    val rankPosition: Int,
    val sourceId: String?,
    val mode: RecommendationMode,
    val radioSessionId: String?,
    val modelVersion: String,
)

/**
 * Thread-safe behavioral event capture coordinator (Phase 1).
 * Tracks active playback instances across foreground UI and background service transitions,
 * separates manual replays from Repeat-One auto-loops, suppresses error-driven skips,
 * and emits idempotent events to [RecommendationEventRepository] without blocking playback.
 */
class EventRecorder(
    private val repository: RecommendationEventRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class ActiveTrackSession(
        val instanceId: String,
        val song: Song,
        val canonicalRecordingId: String,
        val artistIds: List<String>,
        val albumOrWorkId: String?,
        val startedAtWallMs: Long,
        val attribution: PlaybackAttribution,
        val radioSessionId: String?,
        val provenance: QueuedCandidateProvenance?,
        val previousRecordingId: String?,
        var startedPlayingAudio: Boolean = false,
        var isCurrentlyPlaying: Boolean = false,
        var accumulatedPlayMs: Long = 0L,
        var lastResumeWallMs: Long = 0L,
        var meaningfulEmitted: Boolean = false,
        var failedWithError: Boolean = false,
        var endedOrReplaced: Boolean = false,
    )

    private val lock = Any()
    private val appSessionId: String = UUID.randomUUID().toString()
    private val provenanceByVideoId = ConcurrentHashMap<String, QueuedCandidateProvenance>()

    private var activeSession: ActiveTrackSession? = null
    private var lastEndedRecordingId: String? = null
    private var activeRadioSessionId: String? = null
    private var activeRadioMode: RecommendationMode? = null
    private var radioSeedTrackCount: Int = 0

    // ── Provenance registration ────────────────────────────────────────────────

    fun registerAutoplayCandidates(
        requestId: String,
        candidates: List<Song>,
        mode: RecommendationMode,
        radioSessionId: String? = null,
        sourceId: String = "autoplay_v2",
        modelVersion: String = BASELINE_MODEL_VERSION,
    ) {
        if (provenanceByVideoId.size > 500) {
            provenanceByVideoId.clear()
        }
        candidates.forEachIndexed { index, song ->
            provenanceByVideoId[song.id] = QueuedCandidateProvenance(
                requestId = requestId,
                rankPosition = index,
                sourceId = sourceId,
                mode = mode,
                radioSessionId = radioSessionId ?: activeRadioSessionId,
                modelVersion = modelVersion,
            )
        }
    }

    // ── Search events ──────────────────────────────────────────────────────────

    fun recordSearchSubmitted(
        queryId: String,
        rawQuery: String,
        searchSessionId: String = appSessionId,
    ) {
        val normQuery = rawQuery.trim().lowercase(Locale.ROOT)
        if (normQuery.isBlank()) return
        repository.record(
            RecommendationEvent(
                eventId = "search_sub_$queryId",
                eventType = RecommendationEventType.SEARCH_SUBMITTED,
                occurredAtMillis = clock(),
                queryId = queryId,
                normalizedQuery = normQuery,
                recommendationSessionId = searchSessionId,
                surface = RecommendationSurface.SEARCH,
                idempotencyKey = "search_sub:$queryId",
            )
        )
    }

    fun recordSearchResultImpression(
        queryId: String,
        rawQuery: String,
        requestId: String,
        position: Int,
        song: Song,
        searchSessionId: String = appSessionId,
    ) {
        val normQuery = rawQuery.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
        val key = "search_imp:$requestId:${song.id}:$position"
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.SEARCH_RESULT_IMPRESSION,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                queryId = queryId,
                normalizedQuery = normQuery,
                resultPosition = position,
                requestId = requestId,
                recommendationSessionId = searchSessionId,
                surface = RecommendationSurface.SEARCH,
                idempotencyKey = key,
            )
        )
    }

    fun recordSearchResultClicked(
        queryId: String,
        rawQuery: String,
        requestId: String,
        position: Int,
        song: Song,
        searchSessionId: String = appSessionId,
    ): PlaybackAttribution {
        val normQuery = rawQuery.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.SEARCH_RESULT_CLICKED,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                queryId = queryId,
                normalizedQuery = normQuery,
                resultPosition = position,
                requestId = requestId,
                recommendationSessionId = searchSessionId,
                surface = RecommendationSurface.SEARCH,
                idempotencyKey = "search_clk:$requestId:${song.id}:$position:${clock() / 500L}",
            )
        )
        return PlaybackAttribution(
            surface = RecommendationSurface.SEARCH,
            requestId = requestId,
            queryId = queryId,
            normalizedQuery = normQuery,
            resultPosition = position,
            recommendationSessionId = searchSessionId,
        )
    }

    // ── Home impressions ───────────────────────────────────────────────────────

    fun recordHomeSongImpression(
        homeVisitRequestId: String,
        shelfId: String,
        surface: RecommendationSurface,
        position: Int,
        song: Song,
    ) {
        val key = "home_imp:$homeVisitRequestId:$shelfId:${song.id}:$position"
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.AUTOPLAY_CANDIDATE_IMPRESSION,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                requestId = homeVisitRequestId,
                recommendationSessionId = appSessionId,
                shelfId = shelfId,
                resultPosition = position,
                rankPosition = position,
                surface = surface,
                mode = RecommendationMode.HOME_SONGS,
                idempotencyKey = key,
            )
        )
    }

    fun recordHomeCollectionImpression(
        homeVisitRequestId: String,
        shelfId: String,
        surface: RecommendationSurface,
        mode: RecommendationMode,
        position: Int,
        itemId: String,
    ) {
        val key = "home_col_imp:$homeVisitRequestId:$shelfId:$itemId:$position"
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.AUTOPLAY_CANDIDATE_IMPRESSION,
                occurredAtMillis = clock(),
                providerItemId = itemId,
                requestId = homeVisitRequestId,
                recommendationSessionId = appSessionId,
                shelfId = shelfId,
                resultPosition = position,
                rankPosition = position,
                surface = surface,
                mode = mode,
                idempotencyKey = key,
            )
        )
    }

    // ── Queue & Radio impressions / actions ────────────────────────────────────

    fun recordAutoplayCandidateImpression(
        song: Song,
        position: Int,
    ) {
        val prov = provenanceByVideoId[song.id] ?: return
        val key = "ap_imp:${prov.requestId}:${song.id}:$position"
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.AUTOPLAY_CANDIDATE_IMPRESSION,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                requestId = prov.requestId,
                recommendationSessionId = appSessionId,
                radioSessionId = prov.radioSessionId,
                mode = prov.mode,
                surface = RecommendationSurface.AUTOPLAY_QUEUE,
                sourceId = prov.sourceId,
                rankPosition = prov.rankPosition,
                resultPosition = position,
                modelVersion = prov.modelVersion,
                idempotencyKey = key,
            )
        )
    }

    fun recordRadioStarted(
        seed: Song,
        isArtistRadio: Boolean,
        attribution: PlaybackAttribution = PlaybackAttribution(),
    ): String {
        val sessionId = synchronized(lock) {
            dismissActiveRadioLocked(reason = "REPLACED_BY_NEW_RADIO")
            val newId = UUID.randomUUID().toString()
            activeRadioSessionId = newId
            activeRadioMode = if (isArtistRadio) RecommendationMode.ARTIST_RADIO else RecommendationMode.SONG_RADIO
            radioSeedTrackCount = 0
            newId
        }
        val mode = if (isArtistRadio) RecommendationMode.ARTIST_RADIO else RecommendationMode.SONG_RADIO
        val surface = if (attribution.surface != RecommendationSurface.OTHER) attribution.surface
        else if (isArtistRadio) RecommendationSurface.ARTIST_RADIO else RecommendationSurface.SONG_RADIO

        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.RADIO_STARTED,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(seed),
                providerItemId = seed.id,
                artistIds = SongIdentity.normalizeArtists(seed.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(seed.title).movie ?: seed.album,
                requestId = attribution.requestId,
                recommendationSessionId = appSessionId,
                shelfId = attribution.shelfId,
                radioSessionId = sessionId,
                mode = mode,
                surface = surface,
                modelVersion = BASELINE_MODEL_VERSION,
                idempotencyKey = "radio_start:$sessionId",
            )
        )
        return sessionId
    }

    fun recordRadioDismissed(reason: String = "ENDED_BY_USER_PLAYBACK") {
        synchronized(lock) {
            dismissActiveRadioLocked(reason)
        }
    }

    private fun dismissActiveRadioLocked(reason: String) {
        val currentRadioId = activeRadioSessionId ?: return
        val mode = activeRadioMode ?: RecommendationMode.SONG_RADIO
        val lastRecId = activeSession?.canonicalRecordingId ?: lastEndedRecordingId
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.RADIO_DISMISSED,
                occurredAtMillis = clock(),
                canonicalRecordingId = lastRecId,
                recommendationSessionId = appSessionId,
                radioSessionId = currentRadioId,
                mode = mode,
                surface = if (mode == RecommendationMode.ARTIST_RADIO) RecommendationSurface.ARTIST_RADIO else RecommendationSurface.SONG_RADIO,
                playbackTransitionReason = reason,
                idempotencyKey = "radio_dismiss:$currentRadioId",
            )
        )
        activeRadioSessionId = null
        activeRadioMode = null
        radioSeedTrackCount = 0
    }

    fun recordQueuePlayNext(song: Song, surface: RecommendationSurface = RecommendationSurface.PLAYER_NEXT) {
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.QUEUE_PLAY_NEXT,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                recommendationSessionId = appSessionId,
                radioSessionId = activeRadioSessionId,
                surface = surface,
            )
        )
    }

    fun recordQueueAdded(song: Song, surface: RecommendationSurface = RecommendationSurface.PLAYER_NEXT) {
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.QUEUE_ADDED,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                recommendationSessionId = appSessionId,
                radioSessionId = activeRadioSessionId,
                surface = surface,
            )
        )
    }

    fun recordQueueRemoved(
        song: Song,
        wasModelRecommended: Boolean,
        surface: RecommendationSurface = RecommendationSurface.AUTOPLAY_QUEUE,
    ) {
        val now = clock()
        val prov = provenanceByVideoId[song.id]
        val recId = SongIdentity.canonicalGroupId(song)
        val artists = SongIdentity.normalizeArtists(song.artist).toList()
        val album = SongIdentity.normalizeTitle(song.title).movie ?: song.album

        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.QUEUE_REMOVED,
                occurredAtMillis = now,
                canonicalRecordingId = recId,
                providerItemId = song.id,
                artistIds = artists,
                albumOrWorkId = album,
                requestId = prov?.requestId,
                recommendationSessionId = appSessionId,
                radioSessionId = prov?.radioSessionId ?: activeRadioSessionId,
                mode = prov?.mode,
                surface = surface,
                rankPosition = prov?.rankPosition,
                sourceId = prov?.sourceId,
                modelVersion = prov?.modelVersion,
            )
        )
        if (wasModelRecommended || prov != null) {
            repository.record(
                RecommendationEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventType = RecommendationEventType.RECOMMENDATION_HIDDEN,
                    occurredAtMillis = now,
                    canonicalRecordingId = recId,
                    providerItemId = song.id,
                    artistIds = artists,
                    albumOrWorkId = album,
                    requestId = prov?.requestId,
                    recommendationSessionId = appSessionId,
                    radioSessionId = prov?.radioSessionId ?: activeRadioSessionId,
                    mode = prov?.mode,
                    surface = surface,
                    rankPosition = prov?.rankPosition,
                    sourceId = prov?.sourceId,
                    modelVersion = prov?.modelVersion,
                )
            )
        }
    }

    fun recordNotInterested(
        song: Song,
        surface: RecommendationSurface = RecommendationSurface.OTHER,
        reason: String = "USER_NOT_INTERESTED",
    ) {
        val now = clock()
        val prov = provenanceByVideoId[song.id]
        val recId = SongIdentity.canonicalGroupId(song)
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.RECOMMENDATION_NOT_INTERESTED,
                occurredAtMillis = now,
                canonicalRecordingId = recId,
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                requestId = prov?.requestId,
                recommendationSessionId = appSessionId,
                radioSessionId = prov?.radioSessionId ?: activeRadioSessionId,
                mode = prov?.mode,
                surface = surface,
                rankPosition = prov?.rankPosition,
                sourceId = prov?.sourceId,
                playbackTransitionReason = reason,
                modelVersion = prov?.modelVersion,
                idempotencyKey = "not_interested:${song.id}:${now / 2_000L}",
            )
        )
    }

    fun recordPlaylistStarted(
        playlistId: String,
        surface: RecommendationSurface = RecommendationSurface.LIBRARY,
    ) {
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.PLAYLIST_STARTED,
                occurredAtMillis = clock(),
                playlistContextId = playlistId,
                recommendationSessionId = appSessionId,
                surface = surface,
                idempotencyKey = "pl_start:$appSessionId:$playlistId:${clock() / 2_000L}",
            )
        )
    }

    fun recordLikeChanged(song: Song, isNowLiked: Boolean, surface: RecommendationSurface = RecommendationSurface.OTHER) {
        val type = if (isNowLiked) RecommendationEventType.TRACK_LIKED else RecommendationEventType.TRACK_UNLIKED
        val prov = provenanceByVideoId[song.id]
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = type,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                requestId = prov?.requestId,
                recommendationSessionId = appSessionId,
                radioSessionId = activeRadioSessionId,
                mode = prov?.mode,
                surface = surface,
                modelVersion = prov?.modelVersion,
            )
        )
    }

    fun recordPlaylistMembershipChanged(
        playlistId: String,
        song: Song,
        added: Boolean,
        surface: RecommendationSurface = RecommendationSurface.LIBRARY,
    ) {
        val type = if (added) RecommendationEventType.TRACK_ADDED_TO_PLAYLIST else RecommendationEventType.TRACK_REMOVED_FROM_PLAYLIST
        val prov = provenanceByVideoId[song.id]
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = type,
                occurredAtMillis = clock(),
                canonicalRecordingId = SongIdentity.canonicalGroupId(song),
                providerItemId = song.id,
                artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                playlistContextId = playlistId,
                requestId = prov?.requestId,
                recommendationSessionId = appSessionId,
                radioSessionId = activeRadioSessionId,
                mode = prov?.mode,
                surface = surface,
                modelVersion = prov?.modelVersion,
            )
        )
    }

    // ── Playback Lifecycle Telemetry Tracker ──────────────────────────────────

    fun onPlayRequested(
        song: Song,
        attribution: PlaybackAttribution = PlaybackAttribution(),
        isRadioSession: Boolean = false,
        outgoingPositionMs: Long = 0L,
        outgoingDurationMs: Long = 0L,
        repeatModeLabel: String = "OFF",
    ) {
        synchronized(lock) {
            finalizeOutgoingLocked(
                nextSong = song,
                reportedPosMs = outgoingPositionMs,
                reportedDurMs = outgoingDurationMs,
                transitionReason = "MANUAL_PLAY_REQUEST",
                repeatModeLabel = repeatModeLabel,
                isNaturalCompletion = false,
            )
            if (!isRadioSession && activeRadioSessionId != null) {
                dismissActiveRadioLocked("REPLACED_BY_NON_RADIO_PLAY")
            }

            val session = createSessionLocked(song, attribution)
            activeSession = session

            repository.record(
                RecommendationEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventType = RecommendationEventType.TRACK_PLAY_REQUESTED,
                    occurredAtMillis = session.startedAtWallMs,
                    canonicalRecordingId = session.canonicalRecordingId,
                    providerItemId = song.id,
                    artistIds = session.artistIds,
                    albumOrWorkId = session.albumOrWorkId,
                    queryId = attribution.queryId,
                    normalizedQuery = attribution.normalizedQuery,
                    resultPosition = attribution.resultPosition,
                    requestId = attribution.requestId ?: session.provenance?.requestId,
                    recommendationSessionId = attribution.recommendationSessionId ?: appSessionId,
                    shelfId = attribution.shelfId,
                    radioSessionId = session.radioSessionId,
                    playlistContextId = attribution.playlistContextId,
                    previousRecordingId = session.previousRecordingId,
                    mode = attribution.mode ?: session.provenance?.mode ?: activeRadioMode,
                    surface = resolveEffectiveSurface(attribution.surface, session.provenance, session.radioSessionId),
                    sourceId = attribution.sourceId ?: session.provenance?.sourceId,
                    rankPosition = attribution.rankPosition ?: session.provenance?.rankPosition,
                    durationMs = song.durationSec.takeIf { it > 0L }?.times(1000L),
                    repeatMode = repeatModeLabel,
                    sessionContextTags = attribution.sessionContextTags,
                    modelVersion = session.provenance?.modelVersion,
                    idempotencyKey = "play_req:${session.instanceId}",
                )
            )
        }
    }

    fun onExplicitTrackAdvance(
        outgoingSong: Song?,
        nextSong: Song?,
        positionMs: Long,
        durationMs: Long,
        repeatModeLabel: String,
        transitionReason: String,
        nextAttribution: PlaybackAttribution = PlaybackAttribution(),
    ) {
        synchronized(lock) {
            val active = activeSession
            if (active != null && (outgoingSong == null || active.song.id == outgoingSong.id)) {
                finalizeOutgoingLocked(
                    nextSong = nextSong,
                    reportedPosMs = positionMs,
                    reportedDurMs = durationMs,
                    transitionReason = transitionReason,
                    repeatModeLabel = repeatModeLabel,
                    isNaturalCompletion = false,
                )
            }
            if (nextSong != null) {
                activeSession = createSessionLocked(nextSong, nextAttribution)
            }
        }
    }

    /**
     * Authoritative handler wired to PlaybackService's ExoPlayer Listener so background
     * transitions (when Activity is stopped) and automatic Repeat-One loops are accurately tracked.
     */
    fun onMediaItemTransitionInService(
        newSong: Song,
        transitionReason: String,
        repeatModeLabel: String,
        outgoingPositionMs: Long,
        outgoingDurationMs: Long,
        isPlayingNow: Boolean,
    ) {
        synchronized(lock) {
            val active = activeSession
            val now = clock()

            // Case 1: Automatic Repeat One loop on the exact same song
            if (transitionReason == TRANSITION_REPEAT && active != null && active.song.id == newSong.id) {
                finalizeAutoRepeatLocked(active, outgoingPositionMs, outgoingDurationMs, repeatModeLabel, now)
                val nextLoop = createSessionLocked(newSong, active.attribution)
                activeSession = nextLoop
                if (isPlayingNow) {
                    markPlayingStartedLocked(nextLoop, outgoingDurationMs, now)
                }
                return
            }

            // Case 2: PLAYLIST_CHANGED / SEEK right after PlayerConnection already started this session
            if (active != null && !active.endedOrReplaced && active.song.id == newSong.id &&
                (transitionReason == TRANSITION_PLAYLIST_CHANGED || transitionReason == TRANSITION_SEEK)
            ) {
                if (isPlayingNow && !active.isCurrentlyPlaying) {
                    markPlayingStartedLocked(active, outgoingDurationMs, now)
                }
                return
            }

            // Case 3: Genuine advance to a new song (e.g., AUTO transition in background or foreground)
            if (active != null && !active.endedOrReplaced) {
                val isAuto = transitionReason == TRANSITION_AUTO
                finalizeOutgoingLocked(
                    nextSong = newSong,
                    reportedPosMs = outgoingPositionMs,
                    reportedDurMs = outgoingDurationMs,
                    transitionReason = transitionReason,
                    repeatModeLabel = repeatModeLabel,
                    isNaturalCompletion = isAuto,
                )
            }

            if (activeSession == null || activeSession?.song?.id != newSong.id || activeSession?.endedOrReplaced == true) {
                val created = createSessionLocked(newSong, PlaybackAttribution())
                activeSession = created
                if (isPlayingNow) {
                    markPlayingStartedLocked(created, outgoingDurationMs, now)
                }
            }
        }
    }

    fun onIsPlayingChanged(
        isPlaying: Boolean,
        song: Song?,
        durationMs: Long,
    ) {
        synchronized(lock) {
            val now = clock()
            val active = activeSession ?: run {
                if (song != null && isPlaying) {
                    createSessionLocked(song, PlaybackAttribution()).also { activeSession = it }
                } else return
            }
            if (active.endedOrReplaced) return
            if (song != null && active.song.id != song.id) return

            if (isPlaying) {
                markPlayingStartedLocked(active, durationMs, now)
            } else {
                flushPlayAccumulationLocked(active, now)
                active.isCurrentlyPlaying = false
                maybeEmitMeaningfulListenLocked(active, durationMs, now)
            }
        }
    }

    fun onPlaybackProgress(songId: String, positionMs: Long, durationMs: Long) {
        synchronized(lock) {
            val active = activeSession ?: return
            if (active.endedOrReplaced || active.song.id != songId || !active.startedPlayingAudio) return
            val now = clock()
            if (active.isCurrentlyPlaying) {
                flushPlayAccumulationLocked(active, now)
                active.lastResumeWallMs = now
            }
            maybeEmitMeaningfulListenLocked(active, durationMs, now, fallbackPosMs = positionMs)
        }
    }

    fun onManualReplayCurrent(song: Song, playedMsBeforeRestart: Long, durationMs: Long, repeatModeLabel: String) {
        synchronized(lock) {
            val now = clock()
            val active = activeSession
            if (active != null && active.song.id == song.id && active.isCurrentlyPlaying) {
                flushPlayAccumulationLocked(active, now)
                active.lastResumeWallMs = now
            }
            val effectivePlayed = maxOf(active?.accumulatedPlayMs ?: 0L, playedMsBeforeRestart)
            val dur = resolveDurationMs(song, durationMs)
            val ratio = if (dur > 0L) (effectivePlayed.toDouble() / dur.toDouble()).coerceIn(0.0, 1.0) else null
            val recId = SongIdentity.canonicalGroupId(song)

            repository.record(
                RecommendationEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventType = RecommendationEventType.TRACK_REPLAYED_MANUALLY,
                    occurredAtMillis = now,
                    canonicalRecordingId = recId,
                    providerItemId = song.id,
                    artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
                    albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
                    recommendationSessionId = appSessionId,
                    radioSessionId = active?.radioSessionId ?: activeRadioSessionId,
                    playlistContextId = active?.attribution?.playlistContextId,
                    mode = active?.attribution?.mode ?: active?.provenance?.mode ?: activeRadioMode,
                    surface = resolveEffectiveSurface(active?.attribution?.surface, active?.provenance, active?.radioSessionId),
                    playedMs = effectivePlayed,
                    durationMs = dur.takeIf { it > 0L },
                    completionRatio = ratio,
                    repeatMode = repeatModeLabel,
                    playbackTransitionReason = "MANUAL_RESTART",
                    idempotencyKey = "manual_replay:${active?.instanceId ?: song.id}:${now / 1_000L}",
                )
            )
            // Reset accumulation window for the restarted playback pass
            active?.apply {
                accumulatedPlayMs = 0L
                meaningfulEmitted = false
                if (isCurrentlyPlaying) lastResumeWallMs = now
            }
        }
    }

    fun onTrackPlaybackError(songId: String) {
        synchronized(lock) {
            val active = activeSession ?: return
            if (active.song.id == songId) {
                active.failedWithError = true
            }
        }
    }

    fun clearSearchIntentHistory() {
        repository.scrubSearchQueries()
    }

    fun clearAllEvents() {
        synchronized(lock) {
            provenanceByVideoId.clear()
            activeSession = null
            lastEndedRecordingId = null
            activeRadioSessionId = null
            activeRadioMode = null
            radioSeedTrackCount = 0
        }
        repository.clearAll()
    }

    // ── Internal state helpers (must be called under lock) ────────────────────

    private fun createSessionLocked(song: Song, attribution: PlaybackAttribution): ActiveTrackSession {
        val prov = provenanceByVideoId[song.id]
        val radioId = if (activeRadioSessionId != null && attribution.playlistContextId == null) {
            prov?.radioSessionId ?: activeRadioSessionId
        } else {
            null
        }
        return ActiveTrackSession(
            instanceId = UUID.randomUUID().toString(),
            song = song,
            canonicalRecordingId = SongIdentity.canonicalGroupId(song),
            artistIds = SongIdentity.normalizeArtists(song.artist).toList(),
            albumOrWorkId = SongIdentity.normalizeTitle(song.title).movie ?: song.album,
            startedAtWallMs = clock(),
            attribution = attribution,
            radioSessionId = radioId,
            provenance = prov,
            previousRecordingId = lastEndedRecordingId ?: activeSession?.canonicalRecordingId,
        )
    }

    private fun markPlayingStartedLocked(active: ActiveTrackSession, durationMs: Long, now: Long) {
        active.isCurrentlyPlaying = true
        active.lastResumeWallMs = now
        if (active.startedPlayingAudio) return
        active.startedPlayingAudio = true

        val resolvedDur = resolveDurationMs(active.song, durationMs)
        val surface = resolveEffectiveSurface(active.attribution.surface, active.provenance, active.radioSessionId)
        val mode = active.attribution.mode ?: active.provenance?.mode ?: activeRadioMode

        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.TRACK_PLAY_STARTED,
                occurredAtMillis = now,
                canonicalRecordingId = active.canonicalRecordingId,
                providerItemId = active.song.id,
                artistIds = active.artistIds,
                albumOrWorkId = active.albumOrWorkId,
                queryId = active.attribution.queryId,
                normalizedQuery = active.attribution.normalizedQuery,
                resultPosition = active.attribution.resultPosition,
                requestId = active.attribution.requestId ?: active.provenance?.requestId,
                recommendationSessionId = active.attribution.recommendationSessionId ?: appSessionId,
                shelfId = active.attribution.shelfId,
                radioSessionId = active.radioSessionId,
                playlistContextId = active.attribution.playlistContextId,
                previousRecordingId = active.previousRecordingId,
                mode = mode,
                surface = surface,
                sourceId = active.attribution.sourceId ?: active.provenance?.sourceId,
                rankPosition = active.attribution.rankPosition ?: active.provenance?.rankPosition,
                durationMs = resolvedDur.takeIf { it > 0L },
                modelVersion = active.provenance?.modelVersion,
                idempotencyKey = "play_start:${active.instanceId}",
            )
        )

        if (active.provenance != null) {
            repository.record(
                RecommendationEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventType = RecommendationEventType.AUTOPLAY_TRACK_STARTED,
                    occurredAtMillis = now,
                    canonicalRecordingId = active.canonicalRecordingId,
                    providerItemId = active.song.id,
                    artistIds = active.artistIds,
                    albumOrWorkId = active.albumOrWorkId,
                    requestId = active.provenance.requestId,
                    recommendationSessionId = appSessionId,
                    radioSessionId = active.radioSessionId,
                    previousRecordingId = active.previousRecordingId,
                    mode = active.provenance.mode,
                    surface = RecommendationSurface.AUTOPLAY_QUEUE,
                    sourceId = active.provenance.sourceId,
                    rankPosition = active.provenance.rankPosition,
                    durationMs = resolvedDur.takeIf { it > 0L },
                    modelVersion = active.provenance.modelVersion,
                    idempotencyKey = "ap_start:${active.instanceId}",
                )
            )
        }

        val rSessionId = active.radioSessionId
        if (rSessionId != null) {
            radioSeedTrackCount++
            if (radioSeedTrackCount > 1) {
                repository.record(
                    RecommendationEvent(
                        eventId = UUID.randomUUID().toString(),
                        eventType = RecommendationEventType.RADIO_CONTINUED,
                        occurredAtMillis = now,
                        canonicalRecordingId = active.canonicalRecordingId,
                        providerItemId = active.song.id,
                        artistIds = active.artistIds,
                        albumOrWorkId = active.albumOrWorkId,
                        requestId = active.provenance?.requestId,
                        recommendationSessionId = appSessionId,
                        radioSessionId = rSessionId,
                        previousRecordingId = active.previousRecordingId,
                        mode = activeRadioMode ?: RecommendationMode.SONG_RADIO,
                        surface = if (activeRadioMode == RecommendationMode.ARTIST_RADIO) {
                            RecommendationSurface.ARTIST_RADIO
                        } else {
                            RecommendationSurface.SONG_RADIO
                        },
                        idempotencyKey = "radio_cont:${active.instanceId}",
                    )
                )
            }
        }
    }

    private fun flushPlayAccumulationLocked(active: ActiveTrackSession, now: Long) {
        if (!active.isCurrentlyPlaying || active.lastResumeWallMs <= 0L) return
        val delta = (now - active.lastResumeWallMs).coerceAtLeast(0L)
        active.accumulatedPlayMs += delta
        active.lastResumeWallMs = now
    }

    private fun maybeEmitMeaningfulListenLocked(
        active: ActiveTrackSession,
        durationMs: Long,
        now: Long,
        fallbackPosMs: Long = 0L,
    ) {
        if (active.meaningfulEmitted || active.failedWithError || !active.startedPlayingAudio) return
        val played = maxOf(active.accumulatedPlayMs, fallbackPosMs.takeIf { active.accumulatedPlayMs == 0L } ?: 0L)
        val dur = resolveDurationMs(active.song, durationMs)
        val thresholdMs = if (dur in 1L..49_999L) (dur * 0.60).toLong() else MEANINGFUL_LISTEN_MS
        if (played < thresholdMs) return

        active.meaningfulEmitted = true
        val ratio = if (dur > 0L) (played.toDouble() / dur.toDouble()).coerceIn(0.0, 1.0) else null
        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.TRACK_MEANINGFUL_LISTEN,
                occurredAtMillis = now,
                canonicalRecordingId = active.canonicalRecordingId,
                providerItemId = active.song.id,
                artistIds = active.artistIds,
                albumOrWorkId = active.albumOrWorkId,
                queryId = active.attribution.queryId,
                requestId = active.attribution.requestId ?: active.provenance?.requestId,
                recommendationSessionId = appSessionId,
                radioSessionId = active.radioSessionId,
                playlistContextId = active.attribution.playlistContextId,
                previousRecordingId = active.previousRecordingId,
                mode = active.attribution.mode ?: active.provenance?.mode ?: activeRadioMode,
                surface = resolveEffectiveSurface(active.attribution.surface, active.provenance, active.radioSessionId),
                playedMs = played,
                durationMs = dur.takeIf { it > 0L },
                completionRatio = ratio,
                modelVersion = active.provenance?.modelVersion,
                idempotencyKey = "meaningful:${active.instanceId}",
            )
        )

        val plId = active.attribution.playlistContextId
        if (!plId.isNullOrBlank()) {
            repository.record(
                RecommendationEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventType = RecommendationEventType.PLAYLIST_MEANINGFUL_LISTEN,
                    occurredAtMillis = now,
                    canonicalRecordingId = active.canonicalRecordingId,
                    providerItemId = active.song.id,
                    playlistContextId = plId,
                    recommendationSessionId = appSessionId,
                    surface = RecommendationSurface.LIBRARY,
                    playedMs = played,
                    durationMs = dur.takeIf { it > 0L },
                    idempotencyKey = "pl_meaningful:${active.instanceId}",
                )
            )
        }
    }

    private fun finalizeAutoRepeatLocked(
        active: ActiveTrackSession,
        reportedPosMs: Long,
        reportedDurMs: Long,
        repeatModeLabel: String,
        now: Long,
    ) {
        if (active.endedOrReplaced) return
        flushPlayAccumulationLocked(active, now)
        active.isCurrentlyPlaying = false
        active.endedOrReplaced = true
        lastEndedRecordingId = active.canonicalRecordingId

        if (active.failedWithError || !active.startedPlayingAudio) return
        val dur = resolveDurationMs(active.song, reportedDurMs)
        val played = maxOf(active.accumulatedPlayMs, reportedPosMs)
        val ratio = if (dur > 0L) (played.toDouble() / dur.toDouble()).coerceIn(0.0, 1.0) else null

        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = RecommendationEventType.TRACK_REPEATED_AUTOMATICALLY,
                occurredAtMillis = now,
                canonicalRecordingId = active.canonicalRecordingId,
                providerItemId = active.song.id,
                artistIds = active.artistIds,
                albumOrWorkId = active.albumOrWorkId,
                recommendationSessionId = appSessionId,
                radioSessionId = active.radioSessionId,
                playlistContextId = active.attribution.playlistContextId,
                previousRecordingId = active.previousRecordingId,
                nextRecordingId = active.canonicalRecordingId,
                mode = active.attribution.mode ?: active.provenance?.mode ?: activeRadioMode,
                surface = resolveEffectiveSurface(active.attribution.surface, active.provenance, active.radioSessionId),
                playedMs = played,
                durationMs = dur.takeIf { it > 0L },
                completionRatio = ratio,
                repeatMode = repeatModeLabel,
                playbackTransitionReason = TRANSITION_REPEAT,
                idempotencyKey = "auto_repeat:${active.instanceId}",
            )
        )
    }

    private fun finalizeOutgoingLocked(
        nextSong: Song?,
        reportedPosMs: Long,
        reportedDurMs: Long,
        transitionReason: String,
        repeatModeLabel: String,
        isNaturalCompletion: Boolean,
    ) {
        val active = activeSession ?: return
        if (active.endedOrReplaced) return
        val now = clock()
        flushPlayAccumulationLocked(active, now)
        active.isCurrentlyPlaying = false
        active.endedOrReplaced = true
        lastEndedRecordingId = active.canonicalRecordingId

        // Never treat stream/network failure or unstarted playback as user negative feedback
        if (active.failedWithError || !active.startedPlayingAudio) {
            return
        }

        val dur = resolveDurationMs(active.song, reportedDurMs)
        val effectivePlayed = if (active.accumulatedPlayMs > 0L) {
            active.accumulatedPlayMs
        } else {
            reportedPosMs.coerceAtLeast(0L)
        }
        val ratio = if (dur > 0L) (effectivePlayed.toDouble() / dur.toDouble()).coerceIn(0.0, 1.0) else null

        val completed = isNaturalCompletion ||
            (ratio != null && ratio >= COMPLETED_RATIO_THRESHOLD) ||
            (dur > 0L && reportedPosMs > 0L && (dur - reportedPosMs) < 5_000L)

        val eventType = when {
            completed -> RecommendationEventType.TRACK_COMPLETED
            effectivePlayed < EARLY_SKIP_THRESHOLD_MS -> RecommendationEventType.TRACK_SKIPPED_EARLY
            else -> RecommendationEventType.TRACK_SKIPPED_LATE
        }

        maybeEmitMeaningfulListenLocked(active, dur, now, fallbackPosMs = effectivePlayed)

        val nextRecId = nextSong?.let { SongIdentity.canonicalGroupId(it) }
        val surface = resolveEffectiveSurface(active.attribution.surface, active.provenance, active.radioSessionId)
        val mode = active.attribution.mode ?: active.provenance?.mode ?: activeRadioMode

        repository.record(
            RecommendationEvent(
                eventId = UUID.randomUUID().toString(),
                eventType = eventType,
                occurredAtMillis = now,
                canonicalRecordingId = active.canonicalRecordingId,
                providerItemId = active.song.id,
                artistIds = active.artistIds,
                albumOrWorkId = active.albumOrWorkId,
                queryId = active.attribution.queryId,
                normalizedQuery = active.attribution.normalizedQuery,
                resultPosition = active.attribution.resultPosition,
                requestId = active.attribution.requestId ?: active.provenance?.requestId,
                recommendationSessionId = active.attribution.recommendationSessionId ?: appSessionId,
                shelfId = active.attribution.shelfId,
                radioSessionId = active.radioSessionId,
                playlistContextId = active.attribution.playlistContextId,
                previousRecordingId = active.previousRecordingId,
                nextRecordingId = nextRecId,
                mode = mode,
                surface = surface,
                sourceId = active.attribution.sourceId ?: active.provenance?.sourceId,
                rankPosition = active.attribution.rankPosition ?: active.provenance?.rankPosition,
                playedMs = effectivePlayed,
                durationMs = dur.takeIf { it > 0L },
                completionRatio = ratio,
                repeatMode = repeatModeLabel,
                playbackTransitionReason = transitionReason,
                sessionContextTags = active.attribution.sessionContextTags,
                modelVersion = active.provenance?.modelVersion,
                idempotencyKey = "end:${active.instanceId}",
            )
        )

        if (active.provenance != null) {
            val apType = if (completed) {
                RecommendationEventType.AUTOPLAY_TRACK_COMPLETED
            } else {
                RecommendationEventType.AUTOPLAY_TRACK_SKIPPED
            }
            repository.record(
                RecommendationEvent(
                    eventId = UUID.randomUUID().toString(),
                    eventType = apType,
                    occurredAtMillis = now,
                    canonicalRecordingId = active.canonicalRecordingId,
                    providerItemId = active.song.id,
                    artistIds = active.artistIds,
                    albumOrWorkId = active.albumOrWorkId,
                    requestId = active.provenance.requestId,
                    recommendationSessionId = appSessionId,
                    radioSessionId = active.radioSessionId,
                    previousRecordingId = active.previousRecordingId,
                    nextRecordingId = nextRecId,
                    mode = active.provenance.mode,
                    surface = RecommendationSurface.AUTOPLAY_QUEUE,
                    sourceId = active.provenance.sourceId,
                    rankPosition = active.provenance.rankPosition,
                    playedMs = effectivePlayed,
                    durationMs = dur.takeIf { it > 0L },
                    completionRatio = ratio,
                    repeatMode = repeatModeLabel,
                    playbackTransitionReason = transitionReason,
                    modelVersion = active.provenance.modelVersion,
                    idempotencyKey = "ap_end:${active.instanceId}",
                )
            )
        }
    }

    private fun resolveDurationMs(song: Song, reportedDurMs: Long): Long = when {
        reportedDurMs > 0L -> reportedDurMs
        song.durationSec > 0L -> song.durationSec * 1000L
        else -> 0L
    }

    private fun resolveEffectiveSurface(
        attributedSurface: RecommendationSurface?,
        provenance: QueuedCandidateProvenance?,
        radioSessionId: String?,
    ): RecommendationSurface {
        if (attributedSurface != null && attributedSurface != RecommendationSurface.OTHER) {
            return attributedSurface
        }
        if (provenance != null) {
            return RecommendationSurface.AUTOPLAY_QUEUE
        }
        if (radioSessionId != null) {
            return if (activeRadioMode == RecommendationMode.ARTIST_RADIO) {
                RecommendationSurface.ARTIST_RADIO
            } else {
                RecommendationSurface.SONG_RADIO
            }
        }
        return RecommendationSurface.OTHER
    }

    companion object {
        const val BASELINE_MODEL_VERSION = "v2.7.5-heuristic-baseline"
        const val EARLY_SKIP_THRESHOLD_MS = 30_000L
        const val MEANINGFUL_LISTEN_MS = 30_000L
        const val COMPLETED_RATIO_THRESHOLD = 0.85

        const val TRANSITION_AUTO = "AUTO"
        const val TRANSITION_REPEAT = "REPEAT"
        const val TRANSITION_SEEK = "SEEK"
        const val TRANSITION_PLAYLIST_CHANGED = "PLAYLIST_CHANGED"
    }
}
