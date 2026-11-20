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
)

/**
 * Robust, stateful queue manager for VELVET.
 * Maintains strictly segregated state:
 * - [history]: already played songs (never shown above Now Playing in Queue UI)
 * - [current]: the actively playing song
 * - [userQueue]: songs manually added via "Play next" or "Add to queue"
 * - [upNext]: remaining songs from playlist/album/radio
 * - [sourceList]: original un-shuffled song list
 * - [shuffle]: whether shuffle mode is active
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

    var shuffle: Boolean = false
        private set

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

        shuffle = shuffled
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
     * Toggle shuffle.
     * - Turning ON: Fisher-Yates reshuffle of sourceList excluding current track and userQueue items.
     *   Current track is NOT changed or interrupted. userQueue stays untouched.
     * - Turning OFF: upNext = sourceList items after current track's position in sourceList.
     * Returns the new shuffle state.
     */
    fun toggleShuffle(): Boolean {
        val currentSong = current ?: return shuffle
        shuffle = !shuffle
        val userQueueSongIds = _userQueue.map { it.song.id }.toSet()

        if (shuffle) {
            val pool = _sourceList.filter { it.id != currentSong.id && it.id !in userQueueSongIds }
            val reshuffled = pool.shuffled()
            _upNext.clear()
            _upNext.addAll(reshuffled.map { QueueItem(it) })
        } else {
            val currentPos = _sourceList.indexOfFirst { it.id == currentSong.id }
            val remaining = if (currentPos >= 0) {
                _sourceList.drop(currentPos + 1).filter { it.id !in userQueueSongIds }
            } else {
                _sourceList.filter { it.id != currentSong.id && it.id !in userQueueSongIds }
            }
            _upNext.clear()
            _upNext.addAll(remaining.map { QueueItem(it) })
        }
        return shuffle
    }

    /**
     * Force reshuffle of upNext while maintaining current track and userQueue.
     */
    fun reshuffle() {
        val currentSong = current ?: return
        shuffle = true
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
        shuffle = false
    }
}
