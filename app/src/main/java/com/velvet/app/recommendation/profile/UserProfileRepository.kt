package com.velvet.app.recommendation.profile

import com.velvet.app.data.LibraryData
import com.velvet.app.recommendation.events.RecommendationEventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Watermarked incremental repository providing cached multi-horizon [UserTasteSnapshot] instances
 * without scanning full event logs on every playback transition.
 */
interface UserProfileRepository {
    suspend fun getSnapshot(
        userScopeId: String = "local-user",
        sessionId: String? = null,
    ): UserTasteSnapshot

    suspend fun rebuildIncrementally(
        userScopeId: String = "local-user",
        afterEventId: String? = null,
    ): UserTasteSnapshot

    suspend fun invalidateForNewEvents(
        userScopeId: String = "local-user",
        eventIds: List<String> = emptyList(),
    )

    suspend fun resetLocalProfile(userScopeId: String = "local-user")
}

class DefaultUserProfileRepository(
    private val eventRepository: RecommendationEventRepository,
    private val libraryDataProvider: () -> LibraryData,
    private val builder: TasteProfileBuilder = TasteProfileBuilder(),
    private val clock: () -> Long = System::currentTimeMillis,
) : UserProfileRepository {

    private val mutex = Mutex()

    @Volatile
    private var cachedSnapshot: UserTasteSnapshot? = null

    @Volatile
    private var invalidated: Boolean = true

    override suspend fun getSnapshot(
        userScopeId: String,
        sessionId: String?,
    ): UserTasteSnapshot = mutex.withLock {
        val now = clock()
        val lib = libraryDataProvider()
        val mutationSeq = eventRepository.mutationSequence
        val watermark = computeFastWatermark(mutationSeq, lib, sessionId, now)

        val current = cachedSnapshot
        if (!invalidated && current != null && current.sourceEventWatermark == watermark) {
            return@withLock current
        }

        val events = withContext(Dispatchers.IO) {
            eventRepository.flushBlocking()
            eventRepository.queryRecent(limit = MAX_PROFILE_EVENTS)
        }

        val inputs = ExplicitLibraryInputs(
            likedSongs = lib.liked,
            playlistSongs = lib.playlists.flatMap { it.songs },
            followedArtistNames = lib.artists.map { it.name },
            preferredLanguageNames = lib.musicLanguages,
            knownSongsByProviderId = (lib.liked + lib.recent + lib.playlists.flatMap { it.songs }).associateBy { it.id },
        )

        val postFlushWatermark = computeFastWatermark(eventRepository.mutationSequence, lib, sessionId, now)
        val built = builder.build(
            events = events,
            libraryInputs = inputs,
            activeSessionId = sessionId,
            nowMillis = now,
            watermark = postFlushWatermark,
        )
        cachedSnapshot = built
        invalidated = false
        built
    }

    override suspend fun rebuildIncrementally(
        userScopeId: String,
        afterEventId: String?,
    ): UserTasteSnapshot {
        invalidated = true
        return getSnapshot(userScopeId = userScopeId, sessionId = null)
    }

    override suspend fun invalidateForNewEvents(
        userScopeId: String,
        eventIds: List<String>,
    ) {
        invalidated = true
    }

    override suspend fun resetLocalProfile(userScopeId: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                eventRepository.clearAll()
                eventRepository.flushBlocking()
            }
            cachedSnapshot = null
            invalidated = true
        }
    }

    private fun computeFastWatermark(
        mutationSeq: Long,
        lib: LibraryData,
        sessionId: String?,
        nowMillis: Long,
    ): String {
        val timeBucket = nowMillis / TIME_BUCKET_MS
        val libHash = lib.liked.size * 31 +
            lib.playlists.sumOf { it.songs.size } * 17 +
            lib.artists.size * 7 +
            lib.musicLanguages.hashCode()
        return "$mutationSeq|$libHash|${sessionId.orEmpty()}|$timeBucket"
    }

    companion object {
        private const val MAX_PROFILE_EVENTS = 20_000
        private const val TIME_BUCKET_MS = 5 * 60 * 1000L
    }
}
