package com.velvet.app.recommendation.events

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.velvet.app.recommendation.model.RecommendationEvent
import com.velvet.app.recommendation.model.RecommendationEventType
import kotlinx.serialization.json.Json

/**
 * Indexed SQLite store built directly on Android's platform SQLiteOpenHelper
 * so Phase 1 avoids external ORM/KSP build dependencies and JSON full-file rewrites.
 */
class AndroidSqliteEventBackend(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION),
    EventStorageBackend {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                event_id TEXT PRIMARY KEY,
                idempotency_key TEXT UNIQUE,
                event_type TEXT NOT NULL,
                occurred_at_ms INTEGER NOT NULL,
                canonical_recording_id TEXT,
                provider_item_id TEXT,
                request_id TEXT,
                payload_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_events_time ON $TABLE_EVENTS(occurred_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_events_type ON $TABLE_EVENTS(event_type, occurred_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_events_rec ON $TABLE_EVENTS(canonical_recording_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rec_events_req ON $TABLE_EVENTS(request_id)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                meta_key TEXT PRIMARY KEY,
                meta_value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    override fun insertBatch(events: List<RecommendationEvent>): Int {
        if (events.isEmpty()) return 0
        synchronized(lock) {
            val db = writableDatabase
            var inserted = 0
            db.beginTransaction()
            try {
                for (event in events) {
                    val values = ContentValues().apply {
                        put("event_id", event.eventId)
                        if (event.idempotencyKey != null) {
                            put("idempotency_key", event.idempotencyKey)
                        } else {
                            putNull("idempotency_key")
                        }
                        put("event_type", event.eventType.name)
                        put("occurred_at_ms", event.occurredAtMillis)
                        if (event.canonicalRecordingId != null) {
                            put("canonical_recording_id", event.canonicalRecordingId)
                        } else {
                            putNull("canonical_recording_id")
                        }
                        if (event.providerItemId != null) {
                            put("provider_item_id", event.providerItemId)
                        } else {
                            putNull("provider_item_id")
                        }
                        if (event.requestId != null) {
                            put("request_id", event.requestId)
                        } else {
                            putNull("request_id")
                        }
                        put("payload_json", json.encodeToString(RecommendationEvent.serializer(), event))
                    }
                    val rowId = db.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (rowId != -1L) {
                        inserted++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return inserted
        }
    }

    override fun query(
        limit: Int,
        sinceMillis: Long,
        types: Set<RecommendationEventType>?,
    ): List<RecommendationEvent> {
        synchronized(lock) {
            val db = readableDatabase
            val selectionParts = mutableListOf("occurred_at_ms >= ?")
            val args = mutableListOf(sinceMillis.toString())

            if (!types.isNullOrEmpty()) {
                val placeholders = types.joinToString(",") { "?" }
                selectionParts.add("event_type IN ($placeholders)")
                types.forEach { args.add(it.name) }
            }

            val limitClause = if (limit == Int.MAX_VALUE) null else limit.coerceAtLeast(0).toString()
            val out = mutableListOf<RecommendationEvent>()
            db.query(
                TABLE_EVENTS,
                arrayOf("payload_json"),
                selectionParts.joinToString(" AND "),
                args.toTypedArray(),
                null,
                null,
                "occurred_at_ms ASC",
                limitClause,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(0)
                    runCatching { json.decodeFromString(RecommendationEvent.serializer(), payload) }
                        .getOrNull()
                        ?.let { out.add(it) }
                }
            }
            return out
        }
    }

    override fun existsIdempotencyKey(key: String): Boolean {
        synchronized(lock) {
            val db = readableDatabase
            db.rawQuery(
                "SELECT 1 FROM $TABLE_EVENTS WHERE idempotency_key = ? LIMIT 1",
                arrayOf(key),
            ).use { cursor ->
                return cursor.moveToFirst()
            }
        }
    }

    override fun prune(cutoffMillis: Long, maxEvents: Int): Int {
        synchronized(lock) {
            val db = writableDatabase
            var removed = 0
            db.beginTransaction()
            try {
                removed += db.delete(TABLE_EVENTS, "occurred_at_ms < ?", arrayOf(cutoffMillis.toString()))
                val total = countInternal(db)
                if (total > maxEvents) {
                    val excess = total - maxEvents
                    db.execSQL(
                        """
                        DELETE FROM $TABLE_EVENTS WHERE event_id IN (
                            SELECT event_id FROM $TABLE_EVENTS ORDER BY occurred_at_ms ASC LIMIT ?
                        )
                        """.trimIndent(),
                        arrayOf(excess),
                    )
                    removed += excess
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return removed
        }
    }

    override fun scrubQueries(): Int {
        synchronized(lock) {
            val db = writableDatabase
            var updated = 0
            db.beginTransaction()
            try {
                val updates = mutableListOf<Pair<String, String>>()
                db.query(
                    TABLE_EVENTS,
                    arrayOf("event_id", "payload_json"),
                    "payload_json LIKE ?",
                    arrayOf("%normalizedQuery%"),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val eventId = cursor.getString(0)
                        val payload = cursor.getString(1)
                        val parsed = runCatching {
                            json.decodeFromString(RecommendationEvent.serializer(), payload)
                        }.getOrNull()
                        if (parsed != null && parsed.normalizedQuery != null) {
                            val scrubbed = json.encodeToString(
                                RecommendationEvent.serializer(),
                                parsed.copy(normalizedQuery = null),
                            )
                            updates.add(eventId to scrubbed)
                        }
                    }
                }
                for ((eventId, scrubbedJson) in updates) {
                    val cv = ContentValues().apply { put("payload_json", scrubbedJson) }
                    updated += db.update(TABLE_EVENTS, cv, "event_id = ?", arrayOf(eventId))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return updated
        }
    }

    override fun clearAll() {
        synchronized(lock) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE_EVENTS, null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun count(): Int {
        synchronized(lock) {
            return countInternal(readableDatabase)
        }
    }

    private fun countInternal(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override fun getMeta(key: String): String? {
        synchronized(lock) {
            val db = readableDatabase
            db.query(
                TABLE_META,
                arrayOf("meta_value"),
                "meta_key = ?",
                arrayOf(key),
                null,
                null,
                null,
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }

    override fun setMeta(key: String, value: String) {
        synchronized(lock) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("meta_key", key)
                put("meta_value", value)
            }
            db.insertWithOnConflict(TABLE_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    companion object {
        private const val DB_NAME = "velvet_rec_events.db"
        private const val DB_VERSION = 1
        private const val TABLE_EVENTS = "recommendation_events"
        private const val TABLE_META = "recommendation_meta"
    }
}
