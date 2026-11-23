package com.velvet.app.playback

import com.velvet.app.data.Song
import com.velvet.app.data.isDuplicateOf
import com.velvet.app.data.isSameTrackAs
import java.util.Random
import java.util.UUID

enum class ShuffleMode {
    OFF,
    STANDARD,
    SMART,
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

/**
 * A single queue item with a stable unique ID (UUID) so that swipe/move/remove/reorder
 * actions always target the exact item without depending on shifting list indices.
 */
data class QueueItem(
    val song: Song,
    val qid: String = UUID.randomUUID().toString(),
    val isSmartRecommended: Boolean = false,
)

/**
 * Immutable single source of truth for the playback queue.
 *
 * Rules:
 * - Every QueueItem has a unique qid (UUID). The same song may appear twice with different qids.
 *   ALL mutations use qid, NEVER list index.
 * - Play order = current, then userQueue (all), then upNext.
 * - UI shows exactly: "Now Playing" (current), "Next in Queue" (userQueue),
 *   "Next from <source>" (upNext). history is NEVER rendered above Now Playing.
 */
data class QueueState(
    val sourceList: List<QueueItem> = emptyList(),   // original playlist/album order
    val history: List<QueueItem> = emptyList(),      // already played (NEVER shown above Now Playing)
    val current: QueueItem? = null,
    val userQueue: List<QueueItem> = emptyList(),    // manual "Add to queue" / "Play next"
    val upNext: List<QueueItem> = emptyList(),       // context order (shuffled or not), only items AFTER current
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val autoplayOn: Boolean = true,
) {
    /**
     * Visible Queue for UI:
     * Contains current (if present, tagged with qid = "current"), followed by userQueue items, followed by upNext items.
     * History is NEVER included here.
     */
    val visibleQueue: List<QueueItem>
        get() = listOfNotNull(current?.copy(qid = "current")) + userQueue + upNext

    val shuffle: Boolean get() = shuffleMode != ShuffleMode.OFF
    val isSmartShuffle: Boolean get() = shuffleMode == ShuffleMode.SMART
}

/**
 * Pure-Kotlin QueueManager for VELVET.
 * Exposes pure functions returning a new [QueueState], making it 100% unit-testable
 * without Android framework dependencies.
 */
class QueueManager(initialState: QueueState = QueueState()) {
    var state: QueueState = initialState
        private set

    var queueVersion: Long = 0L
        private set

    /** Update current state via a pure transformation function. */
    fun update(transform: (QueueState) -> QueueState): QueueState {
        val nextState = transform(state)
        if (nextState != state) {
            state = nextState
            queueVersion++
        }
        return state
    }

    // Instance convenience delegates
    fun start(songs: List<Song>, startIndex: Int = 0, shuffled: Boolean = false, rng: Random = Random()) {
        update { Companion.start(it, songs, startIndex, shuffled, rng = rng) }
    }

    fun advance(manual: Boolean = false, rng: Random = Random()): Song? {
        var nextSong: Song? = null
        update {
            val newState = Companion.advance(it, manual, rng)
            nextSong = newState.current?.song
            newState
        }
        return nextSong
    }

    fun previous(positionMs: Long = 0L): Song? {
        var prevSong: Song? = null
        update {
            val (newState, _) = Companion.previous(it, positionMs)
            prevSong = newState.current?.song
            newState
        }
        return prevSong
    }

    fun playNext(song: Song) {
        update { Companion.playNext(it, song) }
    }

    fun playNext(item: QueueItem) {
        update { Companion.playNext(it, item) }
    }

    fun playNext(qid: String): Boolean {
        var found = false
        update {
            val newState = Companion.moveToTop(it, qid)
            found = newState.userQueue.firstOrNull()?.qid == qid
            newState
        }
        return found
    }

    fun enqueue(song: Song): Boolean {
        var added = false
        update {
            val (newState, success) = Companion.enqueue(it, song)
            added = success
            newState
        }
        return added
    }

    fun addToQueue(song: Song) {
        update { Companion.addToQueue(it, song) }
    }

    fun addToQueue(item: QueueItem) {
        update { Companion.addToQueue(it, item) }
    }

    fun moveToTop(qid: String): Boolean {
        var moved = false
        update {
            val (newState, success) = Companion.moveToTopWithResult(it, qid)
            moved = success
            newState
        }
        return moved
    }

    fun removeItem(qid: String): Boolean {
        var removed = false
        update {
            val (newState, success) = Companion.removeWithResult(it, qid)
            removed = success
            newState
        }
        return removed
    }

    fun remove(qid: String): Boolean = removeItem(qid)

    @Deprecated("Index-based queue mutations are deprecated. Use removeItem(qid).", ReplaceWith("removeItem(qid)"))
    fun removeAt(index: Int): Boolean {
        val qid = state.upNext.getOrNull(index)?.qid ?: state.userQueue.getOrNull(index)?.qid ?: return false
        return removeItem(qid)
    }

    @Deprecated("Index-based queue mutations are deprecated. Use moveToTop(qid) or reorder methods.", ReplaceWith("reorderUpNext(fromIndex, toIndex)"))
    fun move(fromIndex: Int, toIndex: Int) {
        reorderUpNext(fromIndex, toIndex)
    }

    @Deprecated("Index-based queue jumps are deprecated. Use jumpToQid(qid).", ReplaceWith("jumpToQid(qid)"))
    fun playAtIndex(index: Int): Song? {
        val qid = state.upNext.getOrNull(index)?.qid ?: return null
        return jumpToQid(qid)
    }

    fun reorderUserQueue(fromIndex: Int, toIndex: Int) {
        update { Companion.reorderUserQueue(it, fromIndex, toIndex) }
    }

    fun reorderUpNext(fromIndex: Int, toIndex: Int) {
        update { Companion.reorderUpNext(it, fromIndex, toIndex) }
    }

    /**
     * Reorders an item identified by [qid] to [newPosition] within its own section (userQueue or upNext).
     * Rejects moves if [isLocked] is true or [qid] is current/invalid or position is unchanged.
     */
    fun reorder(qid: String, newPosition: Int, isLocked: Boolean = false): Boolean {
        if (isLocked) return false
        var moved = false
        update {
            val (newState, success) = Companion.reorder(it, qid, newPosition)
            moved = success
            newState
        }
        return moved
    }

    fun enableShuffle(mode: ShuffleMode = ShuffleMode.STANDARD, allowSmartShuffle: Boolean = true, rng: Random = Random()): ShuffleMode {
        update { Companion.enableShuffle(it, mode, allowSmartShuffle, rng) }
        return state.shuffleMode
    }

    fun disableShuffle(): ShuffleMode {
        update { Companion.disableShuffle(it) }
        return state.shuffleMode
    }

    fun setShuffleMode(mode: ShuffleMode, allowSmartShuffle: Boolean = true, rng: Random = Random()): ShuffleMode {
        update { Companion.setShuffleMode(it, mode, allowSmartShuffle, rng) }
        return state.shuffleMode
    }

    fun toggleShuffle(allowSmartShuffle: Boolean = true, rng: Random = Random()): Boolean {
        update { Companion.toggleShuffle(it, allowSmartShuffle, rng) }
        return state.shuffle
    }

    fun cycleShuffleMode(allowSmartShuffle: Boolean = true, rng: Random = Random()): ShuffleMode {
        update { Companion.cycleShuffleMode(it, allowSmartShuffle, rng) }
        return state.shuffleMode
    }

    fun setRepeatMode(mode: RepeatMode): RepeatMode {
        update { Companion.setRepeatMode(it, mode) }
        return state.repeatMode
    }

    fun cycleRepeatMode(): RepeatMode {
        update { Companion.cycleRepeatMode(it) }
        return state.repeatMode
    }

    fun setAutoplay(enabled: Boolean) {
        update { Companion.setAutoplay(it, enabled) }
    }

    fun appendRadioSongs(songs: List<Song>) {
        update { Companion.appendAutoplay(it, songs) }
    }

    fun appendAutoplay(songs: List<Song>) {
        update { Companion.appendAutoplay(it, songs) }
    }

    fun injectSmartRecommendations(recommendations: List<Song>, rng: Random = Random()) {
        update { Companion.injectSmartRecommendations(it, recommendations, rng) }
    }

    fun jumpToQid(qid: String): Song? {
        var targetSong: Song? = null
        update {
            val newState = Companion.jumpToQid(it, qid)
            if (newState.current?.qid == qid || newState.current?.song?.id == it.upNext.find { item -> item.qid == qid }?.song?.id) {
                targetSong = newState.current?.song
            }
            newState
        }
        return targetSong
    }

    fun clearUpcoming() {
        update { Companion.clearUpcoming(it) }
    }

    fun clear() {
        update { Companion.clear(it) }
    }

    // Properties for UI and test callers
    val current: Song? get() = state.current?.song
    val history: List<Song> get() = state.history.map { it.song }
    val userQueue: List<QueueItem> get() = state.userQueue
    val upNext: List<QueueItem> get() = state.upNext
    val sourceList: List<Song> get() = state.sourceList.map { it.song }
    val shuffleMode: ShuffleMode get() = state.shuffleMode
    val repeatMode: RepeatMode get() = state.repeatMode
    val shuffle: Boolean get() = state.shuffle
    val isSmartShuffle: Boolean get() = state.isSmartShuffle
    val visibleQueue: List<QueueItem> get() = state.visibleQueue

    companion object {
        /**
         * Initialize playback with a list of songs from a playlist or album context.
         * If [shuffled] is true:
         * - current becomes the tapped song at [startIndex]
         * - history is empty
         * - upNext is a shuffled rest of the songs (excluding current)
         * - NOTHING appears above Now Playing!
         */
        fun start(
            state: QueueState,
            songs: List<Song>,
            startIndex: Int = 0,
            shuffled: Boolean = false,
            autoplayOn: Boolean = state.autoplayOn,
            repeatMode: RepeatMode = state.repeatMode,
            rng: Random = Random(),
        ): QueueState {
            if (songs.isEmpty()) return state
            val items = songs.map { QueueItem(it) }
            val safeIndex = startIndex.coerceIn(0, items.size - 1)
            val currentItem = items[safeIndex]

            val targetShuffleMode = if (shuffled) ShuffleMode.STANDARD else ShuffleMode.OFF
            val upNextItems = if (shuffled) {
                val rest = items.filter { it.qid != currentItem.qid }
                fisherYatesShuffle(rest, rng)
            } else {
                items.drop(safeIndex + 1)
            }

            return QueueState(
                sourceList = items,
                history = emptyList(),
                current = currentItem,
                userQueue = emptyList(),
                upNext = upNextItems,
                shuffleMode = targetShuffleMode,
                repeatMode = repeatMode,
                autoplayOn = autoplayOn,
            )
        }

        /**
         * Advance to the next song in playback order:
         * Order: current -> userQueue (in order) -> upNext (in order).
         * Old current moves to history.
         * If repeat ALL and queue ends: rebuild upNext from sourceList (reshuffled if shuffle on).
         * If repeat ONE: auto-advance replays current; manual Next advances to next song.
         */
        fun advance(state: QueueState, manual: Boolean = false, rng: Random = Random()): QueueState {
            val oldCurrent = state.current ?: return state

            // Repeat ONE: auto-advance replays current; manual Next still advances
            if (state.repeatMode == RepeatMode.ONE && !manual) {
                return state
            }

            val newHistory = state.history + oldCurrent

            // 1. Check userQueue first
            if (state.userQueue.isNotEmpty()) {
                val next = state.userQueue.first()
                return state.copy(
                    history = newHistory,
                    current = next,
                    userQueue = state.userQueue.drop(1),
                )
            }

            // 2. Check upNext next
            if (state.upNext.isNotEmpty()) {
                val next = state.upNext.first()
                return state.copy(
                    history = newHistory,
                    current = next,
                    upNext = state.upNext.drop(1),
                )
            }

            // 3. Queue is exhausted
            if (state.repeatMode == RepeatMode.ALL && state.sourceList.isNotEmpty()) {
                val items = state.sourceList.map { QueueItem(it.song) }
                return if (state.shuffleMode != ShuffleMode.OFF) {
                    val shuffled = fisherYatesShuffle(items, rng)
                    val next = shuffled.first()
                    state.copy(
                        history = emptyList(),
                        current = next,
                        userQueue = emptyList(),
                        upNext = shuffled.drop(1),
                    )
                } else {
                    val next = items.first()
                    state.copy(
                        history = emptyList(),
                        current = next,
                        userQueue = emptyList(),
                        upNext = items.drop(1),
                    )
                }
            }

            // Ended, no more tracks in queue
            return state.copy(
                history = newHistory,
                current = null,
                userQueue = emptyList(),
                upNext = emptyList(),
            )
        }

        /**
         * Previous song action:
         * If position > 3 seconds: restart current song (returned as Pair(state, restarted = true)).
         * If history is empty: restart current song.
         * Else: pop last from history as new current, and push old current to front of upNext.
         */
        fun previous(state: QueueState, positionMs: Long = 0L): Pair<QueueState, Boolean> {
            val currentItem = state.current
            if (positionMs > 3000L || state.history.isEmpty() || currentItem == null) {
                return Pair(state, true) // Restart current track
            }

            val newCurrent = state.history.last()
            val newHistory = state.history.dropLast(1)
            val newUpNext = listOf(currentItem) + state.upNext

            val newState = state.copy(
                history = newHistory,
                current = newCurrent,
                upNext = newUpNext,
            )
            return Pair(newState, false)
        }

        /**
         * Add an item to play immediately after current ("Play next").
         * Inserted at userQueue index 0. User queue is NEVER shuffled.
         */
        fun playNext(state: QueueState, item: QueueItem): QueueState =
            state.copy(userQueue = listOf(item) + state.userQueue)

        fun playNext(state: QueueState, song: Song): QueueState =
            playNext(state, QueueItem(song))

        /**
         * Append an item to the end of userQueue ("Add to queue").
         */
        fun addToQueue(state: QueueState, item: QueueItem): QueueState =
            state.copy(userQueue = state.userQueue + item)

        fun addToQueue(state: QueueState, song: Song): QueueState =
            addToQueue(state, QueueItem(song))

        /**
         * Move item by qid to the top of userQueue (index 0).
         */
        fun moveToTop(state: QueueState, qid: String): QueueState = moveToTopWithResult(state, qid).first

        fun moveToTopWithResult(state: QueueState, qid: String): Pair<QueueState, Boolean> {
            if (qid == "current" || qid == state.current?.qid) return Pair(state, false)

            val inUserQueue = state.userQueue.find { it.qid == qid }
            if (inUserQueue != null) {
                val remaining = state.userQueue.filter { it.qid != qid }
                return Pair(state.copy(userQueue = listOf(inUserQueue) + remaining), true)
            }

            val inUpNext = state.upNext.find { it.qid == qid }
            if (inUpNext != null) {
                val remainingUpNext = state.upNext.filter { it.qid != qid }
                return Pair(
                    state.copy(
                        userQueue = listOf(inUpNext) + state.userQueue,
                        upNext = remainingUpNext,
                    ),
                    true,
                )
            }

            return Pair(state, false)
        }

        /**
         * Enqueue a song to userQueue, rejecting duplicates of current or existing upcoming items.
         */
        fun enqueue(state: QueueState, song: Song): Pair<QueueState, Boolean> {
            if (state.current?.song?.isDuplicateOf(song) == true) return Pair(state, false)
            if (state.userQueue.any { it.song.isDuplicateOf(song) }) return Pair(state, false)
            if (state.upNext.any { it.song.isDuplicateOf(song) }) return Pair(state, false)

            val newItem = QueueItem(song)
            return Pair(state.copy(userQueue = state.userQueue + newItem), true)
        }

        /**
         * Remove an item by qid.
         * Spec: removing current = skip to next track.
         */
        fun remove(state: QueueState, qid: String): QueueState = removeWithResult(state, qid).first

        fun removeWithResult(state: QueueState, qid: String): Pair<QueueState, Boolean> {
            if (qid == "current" || qid == state.current?.qid) {
                return Pair(advance(state), true)
            }
            val inUserQueue = state.userQueue.any { it.qid == qid }
            val inUpNext = state.upNext.any { it.qid == qid }
            if (!inUserQueue && !inUpNext) {
                return Pair(state, false)
            }
            val newState = state.copy(
                userQueue = state.userQueue.filter { it.qid != qid },
                upNext = state.upNext.filter { it.qid != qid },
            )
            return Pair(newState, true)
        }

        /**
         * Reorder items within userQueue.
         */
        fun reorderUserQueue(state: QueueState, fromIndex: Int, toIndex: Int): QueueState {
            if (fromIndex !in state.userQueue.indices || toIndex !in state.userQueue.indices || fromIndex == toIndex) {
                return state
            }
            val list = state.userQueue.toMutableList()
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            return state.copy(userQueue = list)
        }

        /**
         * Reorder items within upNext.
         */
        fun reorderUpNext(state: QueueState, fromIndex: Int, toIndex: Int): QueueState {
            if (fromIndex !in state.upNext.indices || toIndex !in state.upNext.indices || fromIndex == toIndex) {
                return state
            }
            val list = state.upNext.toMutableList()
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            return state.copy(upNext = list)
        }

        /**
         * Reorder item by [qid] within its own section (userQueue or upNext) to [newPosition].
         * Bounds are clamped to the section's indices.
         * Returns (newState, true) if moved, or (state, false) if no-op or invalid qid.
         */
        fun reorder(state: QueueState, qid: String, newPosition: Int): Pair<QueueState, Boolean> {
            if (qid == "current" || qid == state.current?.qid) {
                return Pair(state, false)
            }
            val userIdx = state.userQueue.indexOfFirst { it.qid == qid }
            if (userIdx >= 0) {
                val target = newPosition.coerceIn(0, state.userQueue.size - 1)
                if (userIdx == target) return Pair(state, false)
                val list = state.userQueue.toMutableList()
                val item = list.removeAt(userIdx)
                list.add(target, item)
                return Pair(state.copy(userQueue = list), true)
            }
            val upNextIdx = state.upNext.indexOfFirst { it.qid == qid }
            if (upNextIdx >= 0) {
                val target = newPosition.coerceIn(0, state.upNext.size - 1)
                if (upNextIdx == target) return Pair(state, false)
                val list = state.upNext.toMutableList()
                val item = list.removeAt(upNextIdx)
                list.add(target, item)
                return Pair(state.copy(upNext = list), true)
            }
            return Pair(state, false)
        }

        /**
         * Enable shuffle mode according to Spotify behavior:
         * - Current song is NEVER interrupted (pinned and keeps playing).
         * - userQueue is untouched.
         * - upNext = Fisher-Yates(sourceList minus current minus history minus userQueue).
         * - In standalone/autoplay playback: complete sourceList is guaranteed to track all upcoming items,
         *   preventing queue wipeout and ensuring upcoming songs are preserved and shuffled.
         * - Re-tap (OFF->ON): If pool.size >= 2 and result equals previous order, re-roll up to 5 times.
         * - In SMART mode: after Fisher-Yates, spread same-artist tracks apart.
         */
        fun enableShuffle(
            state: QueueState,
            mode: ShuffleMode = ShuffleMode.STANDARD,
            allowSmartShuffle: Boolean = true,
            rng: Random = Random(),
            skipNearDuplicates: Boolean = true,
        ): QueueState {
            val targetMode = if (mode == ShuffleMode.SMART && !allowSmartShuffle) ShuffleMode.STANDARD else mode
            val currentItem = state.current ?: return state.copy(shuffleMode = targetMode)
            val userQueueSongIds = state.userQueue.map { it.song.id }.toSet()
            val historySongIds = state.history.map { it.song.id }.toSet()

            // Backfill any items currently in upNext that are missing from sourceList (e.g. standalone/autoplay sessions)
            val knownSourceIds = state.sourceList.map { it.song.id }.toSet()
            val missingFromSource = state.upNext.filter { it.song.id !in knownSourceIds }
            val completeSourceList = if (missingFromSource.isNotEmpty()) {
                state.sourceList + missingFromSource
            } else {
                state.sourceList
            }

            val rawPool = completeSourceList.filter {
                it.song.id != currentItem.song.id &&
                    it.song.id !in historySongIds &&
                    it.song.id !in userQueueSongIds
            }

            // Fallback: If rawPool is empty but upNext has items, use upNext items to avoid queue wipeout
            val candidatePool = if (rawPool.isEmpty() && state.upNext.isNotEmpty()) {
                state.upNext.filter {
                    it.song.id != currentItem.song.id &&
                        it.song.id !in historySongIds &&
                        it.song.id !in userQueueSongIds
                }
            } else {
                rawPool
            }

            // Treat duplicate group as one slot per shuffle cycle if skipNearDuplicates is enabled
            val pool = if (skipNearDuplicates && candidatePool.size > 1) {
                val groupMap = SongIdentity.canonicalGroupId(candidatePool.map { it.song })
                val seenGroups = mutableSetOf<String>()
                val filteredPool = mutableListOf<QueueItem>()
                for (item in candidatePool) {
                    val gid = groupMap[item.song.id] ?: item.song.id
                    if (seenGroups.add(gid)) {
                        filteredPool.add(item)
                    }
                }
                filteredPool
            } else {
                candidatePool
            }

            val previousIds = state.upNext.map { it.song.id }
            var reshuffled = fisherYatesShuffle(pool, rng)

            // Guarantee the new order differs from previous when size >= 2
            if (pool.size >= 2) {
                var attempts = 0
                while (attempts < 5 && reshuffled.map { it.song.id } == previousIds) {
                    reshuffled = fisherYatesShuffle(pool, rng)
                    attempts++
                }
                if (reshuffled.map { it.song.id } == previousIds && pool.size == 2) {
                    reshuffled = reshuffled.reversed()
                }
            }

            val finalUpNext = if (targetMode == ShuffleMode.SMART) {
                spreadSameArtistTracks(reshuffled)
            } else {
                reshuffled
            }

            return state.copy(
                sourceList = completeSourceList,
                shuffleMode = targetMode,
                upNext = finalUpNext.map { QueueItem(it.song) },
            )
        }

        /**
         * Disable shuffle mode according to Spotify behavior:
         * - Current song keeps playing uninterrupted.
         * - userQueue is untouched.
         * - upNext = sourceList items positioned AFTER current's position in sourceList.
         * - In standalone/autoplay playback: complete sourceList preserves discovery order,
         *   preventing queue wipeout and ensuring upcoming songs are restored in chronological order.
         */
        fun disableShuffle(state: QueueState): QueueState {
            val currentItem = state.current ?: return state.copy(shuffleMode = ShuffleMode.OFF)
            val userQueueSongIds = state.userQueue.map { it.song.id }.toSet()
            val historySongIds = state.history.map { it.song.id }.toSet()

            // Backfill any items currently in upNext that are missing from sourceList
            val knownSourceIds = state.sourceList.map { it.song.id }.toSet()
            val missingFromSource = state.upNext.filter { it.song.id !in knownSourceIds }
            val completeSourceList = if (missingFromSource.isNotEmpty()) {
                state.sourceList + missingFromSource
            } else {
                state.sourceList
            }

            val currentPos = completeSourceList.indexOfFirst { it.song.id == currentItem.song.id }
            val remaining = if (currentPos >= 0) {
                completeSourceList.drop(currentPos + 1).filter {
                    it.song.id !in userQueueSongIds && it.song.id !in historySongIds
                }
            } else {
                completeSourceList.filter {
                    it.song.id != currentItem.song.id &&
                        it.song.id !in userQueueSongIds &&
                        it.song.id !in historySongIds
                }
            }

            // Defensive safeguard: never drop existing upNext into an empty list if sourceList was deficient
            val finalUpNext = if (remaining.isEmpty() && state.upNext.isNotEmpty()) {
                state.upNext
            } else {
                remaining.map { QueueItem(it.song) }
            }

            return state.copy(
                sourceList = completeSourceList,
                shuffleMode = ShuffleMode.OFF,
                upNext = finalUpNext,
            )
        }

        fun setShuffleMode(
            state: QueueState,
            mode: ShuffleMode,
            allowSmartShuffle: Boolean = true,
            rng: Random = Random(),
        ): QueueState = when (mode) {
            ShuffleMode.OFF -> disableShuffle(state)
            ShuffleMode.STANDARD -> enableShuffle(state, ShuffleMode.STANDARD, allowSmartShuffle, rng)
            ShuffleMode.SMART -> enableShuffle(state, ShuffleMode.SMART, allowSmartShuffle, rng)
        }

        fun toggleShuffle(state: QueueState, allowSmartShuffle: Boolean = true, rng: Random = Random()): QueueState {
            val nextMode = if (state.shuffleMode == ShuffleMode.OFF) ShuffleMode.STANDARD else ShuffleMode.OFF
            return setShuffleMode(state, nextMode, allowSmartShuffle, rng)
        }

        fun cycleShuffleMode(state: QueueState, allowSmartShuffle: Boolean = true, rng: Random = Random()): QueueState {
            val nextMode = when (state.shuffleMode) {
                ShuffleMode.OFF -> ShuffleMode.STANDARD
                ShuffleMode.STANDARD -> if (allowSmartShuffle) ShuffleMode.SMART else ShuffleMode.OFF
                ShuffleMode.SMART -> ShuffleMode.OFF
            }
            return setShuffleMode(state, nextMode, allowSmartShuffle, rng)
        }

        fun setRepeatMode(state: QueueState, mode: RepeatMode): QueueState =
            state.copy(repeatMode = mode)

        fun cycleRepeatMode(state: QueueState): QueueState {
            val next = when (state.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            return state.copy(repeatMode = next)
        }

        fun setAutoplay(state: QueueState, enabled: Boolean): QueueState =
            state.copy(autoplayOn = enabled)

        /**
         * Append verified autoplay songs to the END of upNext.
         * Appended items get QueueItem(isSmartRecommended = false) and NEVER go into userQueue.
         */
        fun appendAutoplay(state: QueueState, songs: List<Song>): QueueState {
            val allExisting = (
                state.sourceList.map { it.song } +
                    state.history.map { it.song } +
                    listOfNotNull(state.current?.song) +
                    state.userQueue.map { it.song } +
                    state.upNext.map { it.song }
            )

            val fresh = mutableListOf<Song>()
            for (s in songs) {
                val exists = allExisting.any { it.isSameTrackAs(s) }
                val alreadyAdded = fresh.any { it.isSameTrackAs(s) }
                if (!exists && !alreadyAdded) {
                    fresh.add(s)
                }
            }
            if (fresh.isEmpty()) return state

            val newItems = fresh.map { QueueItem(it, isSmartRecommended = false) }
            val updatedSourceList = state.sourceList + newItems
            return state.copy(
                sourceList = updatedSourceList,
                upNext = state.upNext + newItems,
            )
        }

        /**
         * Injects algorithmic smart recommendations matching genre/tempo/style.
         * Enforces:
         * - Zero duplicates with playlist, queue, or history.
         * - 2-4 recommended songs per 5-10 playlist songs ratio.
         * - Disperses recommendations across randomized positions between playlist songs.
         */
        fun injectSmartRecommendations(
            state: QueueState,
            recommendations: List<Song>,
            rng: Random = Random(),
        ): QueueState {
            if (state.shuffleMode != ShuffleMode.SMART || recommendations.isEmpty()) return state

            val existingSongs = (
                state.sourceList.map { it.song } +
                    state.history.map { it.song } +
                    listOfNotNull(state.current?.song) +
                    state.userQueue.map { it.song }
            )

            val fresh = mutableListOf<Song>()
            for (candidate in recommendations) {
                val existsInPlaylistOrQueue = existingSongs.any { it.isSameTrackAs(candidate) }
                val alreadyInFresh = fresh.any { it.isSameTrackAs(candidate) }
                if (!existsInPlaylistOrQueue && !alreadyInFresh) {
                    fresh.add(candidate)
                }
            }
            if (fresh.isEmpty()) return state

            val baseItems = state.upNext.filter { !it.isSmartRecommended }
            if (baseItems.isEmpty()) {
                val injected = fresh.take(3).map { QueueItem(it, isSmartRecommended = true) }
                return state.copy(upNext = injected)
            }

            val n = baseItems.size
            val targetCount = when {
                n <= 2 -> minOf(1, fresh.size)
                n in 3..5 -> minOf(2, fresh.size)
                n in 6..8 -> minOf(3, fresh.size)
                n in 9..12 -> minOf(4, fresh.size)
                else -> minOf(((n * 35) / 100).coerceAtLeast(2), fresh.size)
            }

            val toInject = fresh.take(targetCount)
            if (toInject.isEmpty()) return state

            val result = baseItems.toMutableList()
            val step = result.size.toFloat() / toInject.size.toFloat()
            val insertPositions = mutableListOf<Int>()
            for (i in toInject.indices) {
                val minPos = (i * step).toInt() + 1
                val maxPos = minOf(((i + 1) * step).toInt(), result.size)
                val pos = if (maxPos > minPos) {
                    minPos + rng.nextInt(maxPos - minPos + 1)
                } else {
                    minOf(minPos, result.size)
                }
                insertPositions.add(pos)
            }

            val sortedDesc = insertPositions.sortedDescending()
            for (i in sortedDesc.indices) {
                val targetPos = sortedDesc[i].coerceIn(0, result.size)
                val song = toInject[toInject.size - 1 - i]
                result.add(targetPos, QueueItem(song = song, isSmartRecommended = true))
            }

            return state.copy(upNext = result)
        }

        /**
         * Jump directly to a track with qid.
         * Old current moves to history. Target becomes current.
         */
        fun jumpToQid(state: QueueState, qid: String): QueueState {
            if (qid == "current" || qid == state.current?.qid) return state
            val oldCurrent = state.current
            val newHistory = if (oldCurrent != null) state.history + oldCurrent else state.history

            val userIdx = state.userQueue.indexOfFirst { it.qid == qid }
            if (userIdx >= 0) {
                val target = state.userQueue[userIdx]
                return state.copy(
                    history = newHistory,
                    current = target,
                    userQueue = state.userQueue.filter { it.qid != qid },
                )
            }

            val upNextIdx = state.upNext.indexOfFirst { it.qid == qid }
            if (upNextIdx >= 0) {
                val target = state.upNext[upNextIdx]
                return state.copy(
                    history = newHistory,
                    current = target,
                    upNext = state.upNext.filter { it.qid != qid },
                )
            }

            return state
        }

        fun clearUpcoming(state: QueueState): QueueState = state.copy(
            userQueue = emptyList(),
            upNext = emptyList(),
        )

        fun clear(state: QueueState): QueueState = QueueState(
            autoplayOn = state.autoplayOn,
        )

        /**
         * Fisher-Yates random shuffle algorithm.
         */
        private fun <T> fisherYatesShuffle(list: List<T>, rng: Random): List<T> {
            val result = list.toMutableList()
            for (i in result.size - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val temp = result[i]
                result[i] = result[j]
                result[j] = temp
            }
            return result
        }

        /**
         * Spreads same-artist tracks apart so no two consecutive songs have the same primary artist
         * whenever mathematically possible.
         */
        private fun spreadSameArtistTracks(items: List<QueueItem>): List<QueueItem> {
            if (items.size <= 2) return items
            val pool = items.toMutableList()
            val result = mutableListOf<QueueItem>()

            while (pool.isNotEmpty()) {
                val lastArtist = result.lastOrNull()?.song?.primaryArtistKey()
                val candidateIdx = if (lastArtist == null) {
                    0
                } else {
                    val diffArtistIdx = pool.indexOfFirst { it.song.primaryArtistKey() != lastArtist }
                    if (diffArtistIdx >= 0) diffArtistIdx else 0
                }
                result.add(pool.removeAt(candidateIdx))
            }
            return result
        }

        private fun Song.primaryArtistKey(): String =
            this.artist.split(",", "&", " x ", "feat", "ft", ignoreCase = true)
                .firstOrNull()?.trim()?.lowercase() ?: ""
    }
}

/** Typealias for backward compatibility with existing code calling PlayerQueueManager. */
typealias PlayerQueueManager = QueueManager
