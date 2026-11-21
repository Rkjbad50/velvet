package com.velvet.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class RecentType {
    LOCAL_PLAYLIST,
    REMOTE_PLAYLIST,
    ALBUM,
    ARTIST,
    GENRE_MIX,
    UNKNOWN;
}

@Serializable(with = RecentItemSerializer::class)
data class RecentItem(
    val id: String,
    val type: RecentType,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val lastPlayedAt: Long = System.currentTimeMillis(),
)

data class PlaybackContext(
    val id: String,
    val type: RecentType,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
) {
    fun toRecentItem(now: Long = System.currentTimeMillis()): RecentItem =
        RecentItem(
            id = id,
            type = type,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            lastPlayedAt = now,
        )
}

object RecentItemSerializer : KSerializer<RecentItem> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RecentItem") {
        element<String>("id")
        element<String>("type")
        element<String>("title")
        element<String>("subtitle", isOptional = true)
        element<String?>("artworkUrl", isOptional = true)
        element<Long>("lastPlayedAt", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: RecentItem) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeStringElement(descriptor, 0, value.id)
        composite.encodeStringElement(descriptor, 1, value.type.name)
        composite.encodeStringElement(descriptor, 2, value.title)
        composite.encodeStringElement(descriptor, 3, value.subtitle)
        if (value.artworkUrl != null) {
            composite.encodeStringElement(descriptor, 4, value.artworkUrl)
        }
        composite.encodeLongElement(descriptor, 5, value.lastPlayedAt)
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): RecentItem {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Only JsonDecoder supported")
        val root = input.decodeJsonElement()
        if (root !is JsonObject) {
            throw SerializationException("RecentItem must be a JSON object")
        }

        val id = root["id"]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Missing required id field")
        val title = root["title"]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Missing required title field")
        val subtitle = root["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val artworkUrl = root["artworkUrl"]?.jsonPrimitive?.contentOrNull
        val lastPlayedAt = root["lastPlayedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

        val typeStr = root["type"]?.jsonPrimitive?.contentOrNull
        val type = when {
            typeStr == null -> RecentType.LOCAL_PLAYLIST // migrate old stored recents as LOCAL_PLAYLIST
            else -> runCatching { RecentType.valueOf(typeStr) }.getOrElse { RecentType.UNKNOWN }
        }

        return RecentItem(
            id = id,
            type = type,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            lastPlayedAt = lastPlayedAt,
        )
    }
}

/**
 * Pure Kotlin manager for context-based recently played items.
 * Rules:
 * 1. Only record when playback starts from a context
 * 2. Replay moves the item to the front without duplicates
 * 3. Max capacity is 20
 * 4. Deleted local playlists are removed silently
 * 5. Unknown/corrupted entries are filtered out
 */
object ContextRecentsManager {
    const val MAX_RECENTS = 20

    fun record(
        current: List<RecentItem>,
        context: PlaybackContext,
        now: Long = System.currentTimeMillis(),
    ): List<RecentItem> {
        val newItem = context.toRecentItem(now)
        val filtered = current.filterNot { it.id == newItem.id && it.type == newItem.type }
        return (listOf(newItem) + filtered).take(MAX_RECENTS)
    }

    fun remove(current: List<RecentItem>, id: String, type: RecentType): List<RecentItem> =
        current.filterNot { it.id == id && it.type == type }

    fun filterDeletedPlaylists(
        recents: List<RecentItem>,
        existingPlaylists: List<Playlist>,
    ): List<RecentItem> {
        val existingIds = existingPlaylists.map { it.id }.toSet()
        return recents.filter { item ->
            when (item.type) {
                RecentType.LOCAL_PLAYLIST -> item.id in existingIds
                RecentType.UNKNOWN -> false
                else -> true
            }
        }
    }

    fun merge(current: List<RecentItem>, incoming: List<RecentItem>): List<RecentItem> {
        val combined = current + incoming
        return combined
            .filter { it.type != RecentType.UNKNOWN }
            .groupBy { "${it.type}:${it.id}" }
            .map { (_, versions) -> versions.maxBy { it.lastPlayedAt } }
            .sortedByDescending { it.lastPlayedAt }
            .take(MAX_RECENTS)
    }

    fun migrateOldFormat(
        legacySongs: List<Song>,
        playlists: List<Playlist>,
        now: Long = System.currentTimeMillis(),
    ): List<RecentItem> {
        val migrated = mutableListOf<RecentItem>()
        for (song in legacySongs) {
            val matching = playlists.find { p -> p.songs.any { it.id == song.id } }
            if (matching != null && migrated.none { it.id == matching.id && it.type == RecentType.LOCAL_PLAYLIST }) {
                migrated.add(
                    RecentItem(
                        id = matching.id,
                        type = RecentType.LOCAL_PLAYLIST,
                        title = matching.name,
                        subtitle = "${matching.songs.size} tracks",
                        artworkUrl = matching.songs.firstOrNull()?.thumb ?: matching.customCover,
                        lastPlayedAt = now,
                    ),
                )
            }
        }
        return migrated.take(MAX_RECENTS)
    }
}
