package com.tunehub.app.playback

import com.tunehub.app.data.Song
import com.tunehub.app.data.isDuplicateOf
import java.util.UUID

/**
 * A single queue item with a stable unique ID so that swipe/move/remove
 * actions always target the right item even after a shuffle.
 */
data class QueueItem(
    val song: Song,
    val qid: String = UUID.randomUUID().toString(),
    val isSmartRecommended: Boolean = false,
)

enum class ShuffleMode {
    OFF,
    STANDARD,
    SMART,
}

/**
 * Robust, stateful queue manager for VELVET.
 * Maintains strictly segregated state:
 * - [history]: already played songs (never shown above Now Playing in Queue UI)
 * - [current]: the actively playing song
 * - [userQueue]: songs manually added via "Play next" or "Add to queue"
 * - [upNext]: remaining songs from playlist/album/radio
 * - [sourceList]: original un-shuffled song list
 * - [shuffleMode]: whether shuffle mode is OFF, STANDARD, or SMART
 */
class PlayerQueueManager {
    private val _history = mutableListOf<Song>()
    val history: List<Song> get() = _history

    var current: Song? = null
        private set

    private val _userQueue = mutableListOf<QueueItem>()
    val userQueue: List<QueueItem> get() = _userQueue

    private val _upNext = mutableListOf<QueueItem>()
    val upNext: List<QueueItem> get() = _upNext

    private var _sourceList = listOf<Song>()
    val sourceList: List<Song> get() = _sourceList

    var shuffleMode: ShuffleMode = ShuffleMode.OFF
        private set

    val shuffle: Boolean get() = shuffleMode != ShuffleMode.OFF
    val isSmartShuffle: Boolean get() = shuffleMode == ShuffleMode.SMART

    /**
     * Visible Queue for UI:
     * Current song is always at index 0 (qid = "current").
     * Followed by userQueue items, followed by upNext items.
     * History is NEVER included here!
     */
    val visibleQueue: List<QueueItem>
        get() = listOfNotNull(current?.let { QueueItem(it, "current") }) + _userQueue + _upNext

    /**
     * Start playback with a list of songs.
     * If [shuffled] is true:
     * - current becomes the song at [startIndex]
     * - rest of the songs are shuffled with Fisher-Yates and put into [upNext]
     * - [history] and [userQueue] are cleared
     * - Zero tracks appear before current!
     */
    fun start(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean = false) {
        if (songs.isEmpty()) return
        val uniqueSongs = songs.distinctBy { it.id }
        _sourceList = uniqueSongs
        _history.clear()
        _userQueue.clear()

        val safeIndex = startIndex.coerceIn(0, (uniqueSongs.size - 1).coerceAtLeast(0))
        val startSong = uniqueSongs[safeIndex]
        current = startSong

        shuffleMode = if (shuffled) ShuffleMode.STANDARD else ShuffleMode.OFF
        if (shuffled) {
            val rest = uniqueSongs.filter { it.id != startSong.id }.shuffled()
            _upNext.clear()
            _upNext.addAll(rest.map { QueueItem(it) })
        } else {
            _upNext.clear()
            _upNext.addAll(uniqueSongs.drop(safeIndex + 1).map { QueueItem(it) })
        }
    }

    /**
     * Toggle shuffle between OFF and STANDARD.
     * Returns true if shuffle is now active, false otherwise.
     */
    fun toggleShuffle(): Boolean {
        val currentSong = current ?: return shuffle
        val nextMode = if (shuffleMode == ShuffleMode.OFF) ShuffleMode.STANDARD else ShuffleMode.OFF
        setShuffleMode(nextMode)
        return shuffle
    }

    /**
     * Cycle through all shuffle modes: OFF -> STANDARD -> SMART -> OFF.
     */
    fun cycleShuffleMode(): ShuffleMode {
        val currentSong = current ?: return shuffleMode
        val nextMode = when (shuffleMode) {
            ShuffleMode.OFF -> ShuffleMode.STANDARD
            ShuffleMode.STANDARD -> ShuffleMode.SMART
            ShuffleMode.SMART -> ShuffleMode.OFF
        }
        setShuffleMode(nextMode)
        return shuffleMode
    }

    /**
     * Explicitly set the shuffle mode.
     */
    fun setShuffleMode(mode: ShuffleMode) {
        val currentSong = current ?: run {
            shuffleMode = mode
            return
        }
        shuffleMode = mode
        val userQueueSongIds = _userQueue.map { it.song.id }.toSet()

        when (mode) {
            ShuffleMode.OFF -> {
                _upNext.removeAll { it.isSmartRecommended }
                val currentPos = _sourceList.indexOfFirst { it.id == currentSong.id }
                val remaining = if (currentPos >= 0) {
                    _sourceList.drop(currentPos + 1).filter { it.id !in userQueueSongIds }
                } else {
                    _sourceList.filter { it.id != currentSong.id && it.id !in userQueueSongIds }
                }
                _upNext.clear()
                _upNext.addAll(remaining.map { QueueItem(it) })
            }
            ShuffleMode.STANDARD -> {
                _upNext.removeAll { it.isSmartRecommended }
                val pool = _sourceList.filter { it.id != currentSong.id && it.id !in userQueueSongIds }
                val reshuffled = pool.shuffled()
                _upNext.clear()
                _upNext.addAll(reshuffled.map { QueueItem(it) })
            }
            ShuffleMode.SMART -> {
                val nonSmart = _upNext.filter { !it.isSmartRecommended }
                if (nonSmart.isEmpty()) {
                    val pool = _sourceList.filter { it.id != currentSong.id && it.id !in userQueueSongIds }
                    _upNext.clear()
                    _upNext.addAll(pool.shuffled().map { QueueItem(it) })
                }
            }
        }
    }

    /**
     * Injects smart recommended songs in-between upcoming tracks.
     */
    fun injectSmartRecommendations(recommendations: List<Song>) {
        if (shuffleMode != ShuffleMode.SMART || recommendations.isEmpty()) return
        val existingIds = (_history.map { it.id } +
            listOfNotNull(current?.id) +
            _userQueue.map { it.song.id } +
            _upNext.map { it.song.id }).toSet()
        val fresh = recommendations.filter { it.id !in existingIds }
        if (fresh.isEmpty()) return

        val result = mutableListOf<QueueItem>()
        var recIdx = 0
        for (i in _upNext.indices) {
            result.add(_upNext[i])
            if ((i % 2 == 1 || i == _upNext.lastIndex) && recIdx < fresh.size) {
                result.add(QueueItem(song = fresh[recIdx++], isSmartRecommended = true))
            }
        }
        while (recIdx < fresh.size) {
            result.add(QueueItem(song = fresh[recIdx++], isSmartRecommended = true))
        }
        _upNext.clear()
        _upNext.addAll(result)
    }

    /**
     * Force reshuffle of upNext while maintaining current track and userQueue.
     */
    fun reshuffle() {
        val currentSong = current ?: return
        shuffleMode = ShuffleMode.STANDARD
        val userQueueSongIds = _userQueue.map { it.song.id }.toSet()
        val pool = _sourceList.filter { it.id != currentSong.id && it.id !in userQueueSongIds }
        val reshuffled = pool.shuffled()
        _upNext.clear()
        _upNext.addAll(reshuffled.map { QueueItem(it) })
    }

    /**
     * Advance to the next song in the queue (e.g. current song finished or user clicked Next).
     * Moves previous current to history.
     * Pops next from userQueue first, then upNext.
     */
    fun advance(): Song? {
        val oldCurrent = current
        if (oldCurrent != null) {
            _history.add(oldCurrent)
        }
        val nextItem = if (_userQueue.isNotEmpty()) {
            _userQueue.removeAt(0)
        } else if (_upNext.isNotEmpty()) {
            _upNext.removeAt(0)
        } else {
            null
        }
        current = nextItem?.song
        return current
    }

    /**
     * Return to previous song from history.
     */
    fun previous(): Song? {
        if (_history.isEmpty()) return null
        val prevSong = _history.removeAt(_history.size - 1)
        val oldCurrent = current
        if (oldCurrent != null) {
            _upNext.add(0, QueueItem(oldCurrent))
        }
        current = prevSong
        return current
    }

    /**
     * Add a song to play immediately after current ("Play next").
     * Inserts at index 0 of userQueue.
     * Also removes any duplicate from upNext or userQueue.
     */
    fun playNext(song: Song) {
        _upNext.removeAll { it.song.isDuplicateOf(song) }
        _userQueue.removeAll { it.song.isDuplicateOf(song) }
        _userQueue.add(0, QueueItem(song))
    }

    /**
     * Add a song to the end of userQueue ("Add to queue").
     * Rejects if already in queue or currently playing.
     */
    fun enqueue(song: Song): Boolean {
        if (current?.isDuplicateOf(song) == true ||
            _userQueue.any { it.song.isDuplicateOf(song) } ||
            _upNext.any { it.song.isDuplicateOf(song) }
        ) {
            return false
        }
        _userQueue.add(QueueItem(song))
        return true
    }

    /**
     * Move an upcoming item by unique qid to the top of the upcoming list.
     */
    fun moveToTop(qid: String): Boolean {
        if (qid == "current") return false
        val uIdx = _userQueue.indexOfFirst { it.qid == qid }
        if (uIdx >= 0) {
            val item = _userQueue.removeAt(uIdx)
            _userQueue.add(0, item)
            return true
        }
        val nIdx = _upNext.indexOfFirst { it.qid == qid }
        if (nIdx >= 0) {
            val item = _upNext.removeAt(nIdx)
            _userQueue.add(0, item)
            return true
        }
        return false
    }

    /**
     * Remove an upcoming item by unique qid.
     */
    fun removeItem(qid: String): Boolean {
        if (qid == "current") return false
        val uIdx = _userQueue.indexOfFirst { it.qid == qid }
        if (uIdx >= 0) {
            _userQueue.removeAt(uIdx)
            return true
        }
        val nIdx = _upNext.indexOfFirst { it.qid == qid }
        if (nIdx >= 0) {
            _upNext.removeAt(nIdx)
            return true
        }
        return false
    }

    /**
     * Jump directly to a track in the queue by unique qid.
     */
    fun jumpToQid(qid: String): Song? {
        if (qid == "current") return current
        val uIdx = _userQueue.indexOfFirst { it.qid == qid }
        if (uIdx >= 0) {
            val target = _userQueue.removeAt(uIdx)
            val oldCurrent = current
            if (oldCurrent != null) _history.add(oldCurrent)
            current = target.song
            return current
        }
        val nIdx = _upNext.indexOfFirst { it.qid == qid }
        if (nIdx >= 0) {
            val target = _upNext.removeAt(nIdx)
            val oldCurrent = current
            if (oldCurrent != null) _history.add(oldCurrent)
            current = target.song
            return current
        }
        return null
    }

    /**
     * Append related songs (from autoplay / radio).
     */
    fun appendRadioSongs(songs: List<Song>) {
        val existingIds = (_history.map { it.id } +
            listOfNotNull(current?.id) +
            _userQueue.map { it.song.id } +
            _upNext.map { it.song.id }).toSet()

        val fresh = songs.filter { it.id !in existingIds }
        _upNext.addAll(fresh.map { QueueItem(it) })
    }

    fun clearUpcoming() {
        _userQueue.clear()
        _upNext.clear()
    }

    fun clear() {
        _history.clear()
        current = null
        _userQueue.clear()
        _upNext.clear()
        _sourceList = emptyList()
        shuffleMode = ShuffleMode.OFF
    }
}
