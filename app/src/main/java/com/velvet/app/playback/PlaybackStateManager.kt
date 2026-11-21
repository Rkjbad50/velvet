package com.velvet.app.playback

import android.content.Context
import com.velvet.app.data.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PersistedPlaybackState(
    val song: Song,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val upNext: List<Song> = emptyList(),
    val userQueue: List<Song> = emptyList(),
    val history: List<Song> = emptyList(),
    val playlistId: String? = null,
    val isRadio: Boolean = false,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val savedAt: Long = System.currentTimeMillis(),
)

object PlaybackStateManager {
    private const val FILE_NAME = "playback_state.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun save(
        context: Context,
        song: Song,
        positionMs: Long,
        durationMs: Long,
        upNext: List<Song> = emptyList(),
        userQueue: List<Song> = emptyList(),
        history: List<Song> = emptyList(),
        playlistId: String? = null,
        isRadio: Boolean = false,
        shuffleMode: ShuffleMode = ShuffleMode.OFF,
    ) {
        runCatching {
            val state = PersistedPlaybackState(
                song = song,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                upNext = upNext,
                userQueue = userQueue,
                history = history,
                playlistId = playlistId,
                isRadio = isRadio,
                shuffleMode = shuffleMode,
            )
            val text = json.encodeToString(state)
            val file = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    fun load(context: Context): PersistedPlaybackState? {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val text = file.readText()
            json.decodeFromString<PersistedPlaybackState>(text)
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
        }
    }
}
