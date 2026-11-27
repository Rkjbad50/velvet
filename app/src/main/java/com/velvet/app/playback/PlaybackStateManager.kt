package com.velvet.app.playback

import android.content.Context
import com.velvet.app.data.Song
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable
data class PersistedPlaybackState(
    val song: Song,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val upNext: List<Song> = emptyList(),
    val userQueue: List<Song> = emptyList(),
    val history: List<Song> = emptyList(),
    val sourceList: List<Song> = emptyList(),
    val playlistId: String? = null,
    val isRadio: Boolean = false,
    val isArtistRadio: Boolean = false,
    val radioSeedSong: Song? = null,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val savedAt: Long = System.currentTimeMillis(),
    val removedSongIds: Set<String> = emptySet(),
    val removedGroupIds: Set<String> = emptySet(),
    val removedRecordingIds: Set<String> = emptySet(),
)

/**
 * Schedules off-main 300ms debounced checkpoints on queue/track mutations plus
 * 15s periodic checkpoints while audio is actively playing.
 */
class PlaybackCheckpointScheduler(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 300L,
    private val periodicIntervalMs: Long = 15_000L,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val snapshotProvider: () -> PersistedPlaybackState?,
    private val onPersist: (PersistedPlaybackState) -> Unit,
) {
    private var debounceJob: Job? = null
    private var periodicJob: Job? = null

    fun scheduleDebounced() {
        debounceJob?.cancel()
        debounceJob = scope.launch(ioDispatcher) {
            if (debounceMs > 0L) delay(debounceMs)
            val snap = withContext(mainDispatcher) { snapshotProvider() } ?: return@launch
            onPersist(snap)
        }
    }

    fun flushNow() {
        debounceJob?.cancel()
        debounceJob = null
        scope.launch(ioDispatcher) {
            val snap = withContext(mainDispatcher) { snapshotProvider() } ?: return@launch
            onPersist(snap)
        }
    }

    fun startPeriodic(isPlayingProvider: () -> Boolean) {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch(ioDispatcher) {
            while (isActive) {
                delay(periodicIntervalMs)
                val snap = withContext(mainDispatcher) {
                    if (isPlayingProvider()) snapshotProvider() else null
                }
                if (snap != null) {
                    onPersist(snap)
                }
            }
        }
    }

    fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        periodicJob?.cancel()
        periodicJob = null
    }
}

object PlaybackStateManager {
    private const val FILE_NAME = "playback_state.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val fileLock = Any()

    fun save(
        context: Context,
        song: Song,
        positionMs: Long,
        durationMs: Long,
        upNext: List<Song> = emptyList(),
        userQueue: List<Song> = emptyList(),
        history: List<Song> = emptyList(),
        sourceList: List<Song> = emptyList(),
        playlistId: String? = null,
        isRadio: Boolean = false,
        isArtistRadio: Boolean = false,
        radioSeedSong: Song? = null,
        shuffleMode: ShuffleMode = ShuffleMode.OFF,
        repeatMode: RepeatMode = RepeatMode.OFF,
    ) {
        val state = PersistedPlaybackState(
            song = song,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            upNext = upNext,
            userQueue = userQueue,
            history = history,
            sourceList = sourceList,
            playlistId = playlistId,
            isRadio = isRadio,
            isArtistRadio = isArtistRadio,
            radioSeedSong = radioSeedSong,
            shuffleMode = shuffleMode,
            repeatMode = repeatMode,
        )
        saveToDirectory(context.filesDir, state)
    }

    fun saveToDirectory(dir: File, state: PersistedPlaybackState): Boolean {
        return runCatching {
            synchronized(fileLock) {
                if (!dir.exists()) dir.mkdirs()
                val text = json.encodeToString(state)
                val file = File(dir, FILE_NAME)
                val tmp = File(dir, "$FILE_NAME.tmp")
                FileOutputStream(tmp).use { fos ->
                    fos.write(text.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun load(context: Context): PersistedPlaybackState? = loadFromDirectory(context.filesDir)

    fun loadFromDirectory(dir: File): PersistedPlaybackState? {
        return runCatching {
            synchronized(fileLock) {
                val file = File(dir, FILE_NAME)
                if (!file.exists()) return null
                val text = file.readText(Charsets.UTF_8)
                json.decodeFromString<PersistedPlaybackState>(text)
            }
        }.getOrNull()
    }

    fun clear(context: Context) = clearFromDirectory(context.filesDir)

    fun clearFromDirectory(dir: File) {
        runCatching {
            synchronized(fileLock) {
                File(dir, FILE_NAME).delete()
                File(dir, "$FILE_NAME.tmp").delete()
            }
        }
    }
}
