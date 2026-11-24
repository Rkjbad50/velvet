package com.velvet.app.recommendation.events

import com.velvet.app.data.ListenContext
import com.velvet.app.data.ListenEndReason
import com.velvet.app.data.ListenEvent
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import com.velvet.app.recommendation.model.RecommendationSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Storage contract for local recommendation events.
 * Designed so JVM unit tests run on [InMemoryEventBackend] / [FileJsonlEventBackend]
 * while Android runtime persists via [AndroidSqliteEventBackend].
 */
interface EventStorageBackend {
    fun insertBatch(events: List<RecommendationEvent>): Int
    fun query(
        limit: Int = Int.MAX_VALUE,
        sinceMillis: Long = 0L,
        types: Set<RecommendationEventType>? = null,
    ): List<RecommendationEvent>
    fun existsIdempotencyKey(key: String): Boolean
    fun prune(cutoffMillis: Long, maxEvents: Int): Int
    fun scrubQueries(): Int
    fun clearAll()
    fun count(): Int
    fun getMeta(key: String): String?
    fun setMeta(key: String, value: String)
}

/**
 * Thread-safe bounded deduplicator preventing rapid Compose recompositions,
 * Activity/Service reconnects, or duplicate callbacks from enqueueing redundant events.
 */
class EventDeduplicator(private val maxEntries: Int = 2048) {
    private val seenIds = LinkedHashMap<String, Unit>(128, 0.75f, true)
    private val seenKeys = LinkedHashMap<String, Unit>(128, 0.75f, true)

    @Synchronized
    fun tryAcquire(eventId: String, idempotencyKey: String?): Boolean {
        if (seenIds.containsKey(eventId)) return false
        if (idempotencyKey != null && seenKeys.containsKey(idempotencyKey)) return false

        seenIds[eventId] = Unit
        if (seenIds.size > maxEntries) {
            val oldest = seenIds.keys.firstOrNull()
            if (oldest != null) seenIds.remove(oldest)
        }
        if (idempotencyKey != null) {
            seenKeys[idempotencyKey] = Unit
            if (seenKeys.size > maxEntries) {
                val oldestKey = seenKeys.keys.firstOrNull()
                if (oldestKey != null) seenKeys.remove(oldestKey)
            }
        }
        return true
    }

    @Synchronized
    fun clear() {
        seenIds.clear()
        seenKeys.clear()
    }
}

/**
 * Pure Kotlin in-memory backend for fast, deterministic unit tests.
 */
class InMemoryEventBackend : EventStorageBackend {
    private val events = mutableListOf<RecommendationEvent>()
    private val eventIds = HashSet<String>()
    private val idempotencyKeys = HashSet<String>()
    private val meta = ConcurrentHashMap<String, String>()

    @Synchronized
    override fun insertBatch(events: List<RecommendationEvent>): Int {
        var inserted = 0
        for (e in events) {
            if (e.eventId in eventIds) continue
            val ik = e.idempotencyKey
            if (ik != null && ik in idempotencyKeys) continue
            this.events.add(e)
            eventIds.add(e.eventId)
            if (ik != null) idempotencyKeys.add(ik)
            inserted++
        }
        return inserted
    }

    @Synchronized
    override fun query(
        limit: Int,
        sinceMillis: Long,
        types: Set<RecommendationEventType>?,
    ): List<RecommendationEvent> {
        return events.asSequence()
            .filter { it.occurredAtMillis >= sinceMillis }
            .filter { types == null || it.eventType in types }
            .sortedBy { it.occurredAtMillis }
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun existsIdempotencyKey(key: String): Boolean = key in idempotencyKeys

    @Synchronized
    override fun prune(cutoffMillis: Long, maxEvents: Int): Int {
        val before = events.size
        val retained = events
            .filter { it.occurredAtMillis >= cutoffMillis }
            .sortedBy { it.occurredAtMillis }
            .let { if (it.size > maxEvents) it.takeLast(maxEvents) else it }
        events.clear()
        events.addAll(retained)
        eventIds.clear()
        idempotencyKeys.clear()
        for (e in events) {
            eventIds.add(e.eventId)
            e.idempotencyKey?.let { idempotencyKeys.add(it) }
        }
        return (before - events.size).coerceAtLeast(0)
    }

    @Synchronized
    override fun scrubQueries(): Int {
        var modified = 0
        for (i in events.indices) {
            val e = events[i]
            if (e.normalizedQuery != null) {
                events[i] = e.copy(normalizedQuery = null)
                modified++
            }
        }
        return modified
    }

    @Synchronized
    override fun clearAll() {
        events.clear()
        eventIds.clear()
        idempotencyKeys.clear()
    }

    @Synchronized
    override fun count(): Int = events.size

    override fun getMeta(key: String): String? = meta[key]

    override fun setMeta(key: String, value: String) {
        meta[key] = value
    }
}

/**
 * Pure Kotlin append-friendly JSONL file backend with crash-safe atomic compaction.
 * Allows durability, migration, and restart verification inside standard JVM tests.
 */
class FileJsonlEventBackend(private val dir: File) : EventStorageBackend {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val eventsFile = File(dir, "recommendation_events.jsonl")
    private val metaFile = File(dir, "recommendation_events_meta.properties")

    private val cachedEvents = mutableListOf<RecommendationEvent>()
    private val eventIds = HashSet<String>()
    private val idempotencyKeys = HashSet<String>()
    private val metaMap = linkedMapOf<String, String>()
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        dir.mkdirs()
        if (eventsFile.exists()) {
            eventsFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parsed = runCatching { json.decodeFromString(RecommendationEvent.serializer(), line) }.getOrNull()
                if (parsed != null && parsed.eventId !in eventIds) {
                    val ik = parsed.idempotencyKey
                    if (ik == null || ik !in idempotencyKeys) {
                        cachedEvents.add(parsed)
                        eventIds.add(parsed.eventId)
                        if (ik != null) idempotencyKeys.add(ik)
                    }
                }
            }
        }
        if (metaFile.exists()) {
            metaFile.forEachLine { line ->
                val eq = line.indexOf('=')
                if (eq > 0) {
                    metaMap[line.substring(0, eq)] = line.substring(eq + 1)
                }
            }
        }
        loaded = true
    }

    @Synchronized
    override fun insertBatch(events: List<RecommendationEvent>): Int {
        ensureLoaded()
        val toAppend = mutableListOf<RecommendationEvent>()
        for (e in events) {
            if (e.eventId in eventIds) continue
            val ik = e.idempotencyKey
            if (ik != null && ik in idempotencyKeys) continue
            cachedEvents.add(e)
            eventIds.add(e.eventId)
            if (ik != null) idempotencyKeys.add(ik)
            toAppend.add(e)
        }
        if (toAppend.isEmpty()) return 0

        FileOutputStream(eventsFile, true).use { out ->
            for (e in toAppend) {
                val encoded = json.encodeToString(RecommendationEvent.serializer(), e) + "\n"
                out.write(encoded.toByteArray(Charsets.UTF_8))
            }
            out.fd.sync()
        }
        return toAppend.size
    }

    @Synchronized
    override fun query(
        limit: Int,
        sinceMillis: Long,
        types: Set<RecommendationEventType>?,
    ): List<RecommendationEvent> {
        ensureLoaded()
        return cachedEvents.asSequence()
            .filter { it.occurredAtMillis >= sinceMillis }
            .filter { types == null || it.eventType in types }
            .sortedBy { it.occurredAtMillis }
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun existsIdempotencyKey(key: String): Boolean {
        ensureLoaded()
        return key in idempotencyKeys
    }

    @Synchronized
    override fun prune(cutoffMillis: Long, maxEvents: Int): Int {
        ensureLoaded()
        val before = cachedEvents.size
        val retained = cachedEvents
            .filter { it.occurredAtMillis >= cutoffMillis }
            .sortedBy { it.occurredAtMillis }
            .let { if (it.size > maxEvents) it.takeLast(maxEvents) else it }
        if (retained.size == before) return 0
        cachedEvents.clear()
        cachedEvents.addAll(retained)
        rebuildIndex()
        rewriteFileAtomically()
        return (before - cachedEvents.size).coerceAtLeast(0)
    }

    @Synchronized
    override fun scrubQueries(): Int {
        ensureLoaded()
        var modified = 0
        for (i in cachedEvents.indices) {
            val e = cachedEvents[i]
            if (e.normalizedQuery != null) {
                cachedEvents[i] = e.copy(normalizedQuery = null)
                modified++
            }
        }
        if (modified > 0) {
            rewriteFileAtomically()
        }
        return modified
    }

    @Synchronized
    override fun clearAll() {
        ensureLoaded()
        cachedEvents.clear()
        eventIds.clear()
        idempotencyKeys.clear()
        if (eventsFile.exists()) {
            eventsFile.delete()
        }
    }

    @Synchronized
    override fun count(): Int {
        ensureLoaded()
        return cachedEvents.size
    }

    @Synchronized
    override fun getMeta(key: String): String? {
        ensureLoaded()
        return metaMap[key]
    }

    @Synchronized
    override fun setMeta(key: String, value: String) {
        ensureLoaded()
        metaMap[key] = value
        val tmp = File(dir, "recommendation_events_meta.properties.tmp")
        FileOutputStream(tmp).use { out ->
            for ((k, v) in metaMap) {
                out.write("$k=$v\n".toByteArray(Charsets.UTF_8))
            }
            out.fd.sync()
        }
        if (!tmp.renameTo(metaFile)) {
            metaFile.delete()
            tmp.renameTo(metaFile)
        }
    }

    private fun rebuildIndex() {
        eventIds.clear()
        idempotencyKeys.clear()
        for (e in cachedEvents) {
            eventIds.add(e.eventId)
            e.idempotencyKey?.let { idempotencyKeys.add(it) }
        }
    }

    private fun rewriteFileAtomically() {
        val tmp = File(dir, "recommendation_events.jsonl.tmp")
        FileOutputStream(tmp).use { out ->
            for (e in cachedEvents) {
                val encoded = json.encodeToString(RecommendationEvent.serializer(), e) + "\n"
                out.write(encoded.toByteArray(Charsets.UTF_8))
            }
            out.fd.sync()
        }
        if (!tmp.renameTo(eventsFile)) {
            eventsFile.delete()
            tmp.renameTo(eventsFile)
        }
    }
}

/**
 * Asynchronous, non-blocking event repository with idempotent ingestion,
 * bounded retention, privacy-preserving search clearing, and legacy ListenEvent migration.
 */
class RecommendationEventRepository(
    private val backend: EventStorageBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private sealed interface WriteOp {
        data class Single(val event: RecommendationEvent) : WriteOp
        data class Batch(val events: List<RecommendationEvent>) : WriteOp
        data class Prune(val cutoffMillis: Long, val maxEvents: Int) : WriteOp
        data object ScrubQueries : WriteOp
        data object ClearAll : WriteOp
        data class Flush(val latch: CountDownLatch) : WriteOp
    }

    private val deduplicator = EventDeduplicator()
    private val queue = Channel<WriteOp>(Channel.UNLIMITED)

    init {
        scope.launch {
            val batch = ArrayList<RecommendationEvent>(32)
            for (op in queue) {
                when (op) {
                    is WriteOp.Single -> {
                        batch.add(op.event)
                        drainPendingBatch(batch)
                        persistBatch(batch)
                    }
                    is WriteOp.Batch -> {
                        batch.addAll(op.events)
                        drainPendingBatch(batch)
                        persistBatch(batch)
                    }
                    is WriteOp.Prune -> {
                        persistBatch(batch)
                        runCatching { backend.prune(op.cutoffMillis, op.maxEvents) }
                    }
                    WriteOp.ScrubQueries -> {
                        persistBatch(batch)
                        runCatching { backend.scrubQueries() }
                    }
                    WriteOp.ClearAll -> {
                        batch.clear()
                        runCatching { backend.clearAll() }
                        deduplicator.clear()
                    }
                    is WriteOp.Flush -> {
                        persistBatch(batch)
                        op.latch.countDown()
                    }
                }
            }
        }
    }

    private fun drainPendingBatch(out: MutableList<RecommendationEvent>) {
        while (out.size < MAX_BATCH_SIZE) {
            val next = queue.tryReceive().getOrNull() ?: break
            when (next) {
                is WriteOp.Single -> out.add(next.event)
                is WriteOp.Batch -> out.addAll(next.events)
                else -> {
                    // Put non-insert op back cleanly after flushing batch
                    persistBatch(out)
                    when (next) {
                        is WriteOp.Prune -> runCatching { backend.prune(next.cutoffMillis, next.maxEvents) }
                        WriteOp.ScrubQueries -> runCatching { backend.scrubQueries() }
                        WriteOp.ClearAll -> {
                            runCatching { backend.clearAll() }
                            deduplicator.clear()
                        }
                        is WriteOp.Flush -> next.latch.countDown()
                        else -> Unit
                    }
                    return
                }
            }
        }
    }

    private fun persistBatch(batch: MutableList<RecommendationEvent>) {
        if (batch.isEmpty()) return
        val copy = batch.toList()
        batch.clear()
        runCatching { backend.insertBatch(copy) }
    }

    /**
     * Non-blocking event ingestion safe to call on Main or player callbacks.
     * Drops synchronous duplicates via [EventDeduplicator]; DB-level dedupe catches across restarts.
     */
    fun record(event: RecommendationEvent): Boolean {
        if (!deduplicator.tryAcquire(event.eventId, event.idempotencyKey)) {
            return false
        }
        return queue.trySend(WriteOp.Single(event)).isSuccess
    }

    fun recordAll(events: List<RecommendationEvent>): Int {
        val accepted = events.filter { deduplicator.tryAcquire(it.eventId, it.idempotencyKey) }
        if (accepted.isEmpty()) return 0
        queue.trySend(WriteOp.Batch(accepted))
        return accepted.size
    }

    fun pruneOldEvents(retentionDays: Long = DEFAULT_RETENTION_DAYS, maxEvents: Int = DEFAULT_MAX_EVENTS) {
        val cutoff = clock() - TimeUnit.DAYS.toMillis(retentionDays)
        queue.trySend(WriteOp.Prune(cutoff, maxEvents))
    }

    fun scrubSearchQueries() {
        queue.trySend(WriteOp.ScrubQueries)
    }

    fun clearAll() {
        deduplicator.clear()
        queue.trySend(WriteOp.ClearAll)
    }

    /** Blocks until all queued writes have reached storage. Primarily for unit tests and shutdown. */
    fun flushBlocking(timeoutMs: Long = 5_000L): Boolean {
        val latch = CountDownLatch(1)
        if (!queue.trySend(WriteOp.Flush(latch)).isSuccess) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun queryRecent(
        limit: Int = 500,
        sinceMillis: Long = 0L,
        types: Set<RecommendationEventType>? = null,
    ): List<RecommendationEvent> = backend.query(limit, sinceMillis, types)

    fun eventCount(): Int = backend.count()

    /**
     * Idempotently imports legacy [ListenEvent] records from LibraryData without altering or deleting them.
     * Unknown fields (query, year, language if null, exact replay intent) are left explicitly unknown/tagged.
     */
    fun migrateLegacyListenEventsIfNeeded(legacyEvents: List<ListenEvent>): Int {
        if (legacyEvents.isEmpty()) {
            if (backend.getMeta(META_LEGACY_MIGRATION_DONE) == null) {
                backend.setMeta(META_LEGACY_MIGRATION_DONE, "0")
            }
            return 0
        }
        val marker = backend.getMeta(META_LEGACY_MIGRATION_COUNT)?.toIntOrNull()
        if (backend.getMeta(META_LEGACY_MIGRATION_DONE) == "true" && marker == legacyEvents.size) {
            return 0
        }

        val mapped = legacyEvents.map { legacy ->
            val mappedType = when (legacy.endReason) {
                ListenEndReason.COMPLETED -> RecommendationEventType.TRACK_COMPLETED
                ListenEndReason.SKIP_EARLY -> RecommendationEventType.TRACK_SKIPPED_EARLY
                ListenEndReason.SKIP_LATE -> RecommendationEventType.TRACK_SKIPPED_LATE
                ListenEndReason.REPLAY -> RecommendationEventType.TRACK_REPLAYED_MANUALLY
            }
            val mappedSurface = when (legacy.context) {
                ListenContext.AUTOPLAY -> RecommendationSurface.AUTOPLAY_QUEUE
                ListenContext.PLAYLIST -> RecommendationSurface.LIBRARY
                ListenContext.ALBUM -> RecommendationSurface.LIBRARY
                ListenContext.ARTIST -> RecommendationSurface.OTHER
                ListenContext.SEARCH -> RecommendationSurface.SEARCH
                ListenContext.QUEUE -> RecommendationSurface.PLAYER_NEXT
            }
            val tags = buildSet {
                add("migrated_legacy_listen_event")
                if (legacy.endReason == ListenEndReason.REPLAY) {
                    add("legacy_replay_unverified_repeat")
                }
                if (legacy.liked) add("legacy_liked_true")
                if (legacy.addedToPlaylist) add("legacy_added_to_playlist")
            }
            val ik = "legacy_listen:${legacy.videoId}:${legacy.startedAt}:${legacy.songGroupId}:${legacy.endReason.name}"
            RecommendationEvent(
                eventId = "legacy_${legacy.startedAt}_${legacy.videoId}_${legacy.endReason.name}",
                eventType = mappedType,
                occurredAtMillis = legacy.startedAt,
                canonicalRecordingId = legacy.songGroupId.takeIf { it.isNotBlank() },
                providerItemId = legacy.videoId.takeIf { it.isNotBlank() },
                artistIds = legacy.artistSet,
                albumOrWorkId = legacy.movie?.takeIf { it.isNotBlank() },
                surface = mappedSurface,
                playedMs = legacy.playedMs,
                durationMs = legacy.durationMs.takeIf { it > 0L },
                completionRatio = legacy.completionPct.toDouble(),
                sessionContextTags = tags,
                idempotencyKey = ik,
            )
        }

        val accepted = mapped.filter { !backend.existsIdempotencyKey(it.idempotencyKey!!) }
        val inserted = if (accepted.isNotEmpty()) backend.insertBatch(accepted) else 0
        backend.setMeta(META_LEGACY_MIGRATION_DONE, "true")
        backend.setMeta(META_LEGACY_MIGRATION_COUNT, legacyEvents.size.toString())
        return inserted
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 180L
        const val DEFAULT_MAX_EVENTS = 50_000
        private const val MAX_BATCH_SIZE = 32
        private const val META_LEGACY_MIGRATION_DONE = "legacy_listen_migration_v1_done"
        private const val META_LEGACY_MIGRATION_COUNT = "legacy_listen_migration_v1_count"
    }
}
