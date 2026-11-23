package com.velvet.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Sound settings: equalizer band levels in millibels, effect strengths 0..1000, loudness gain in millibels. */
@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    val bands: List<Int> = emptyList(),
    val bass: Int = 0,
    val virtualizer: Int = 0,
    val loudness: Int = 0,
    val ultra: Boolean = false,
)

/**
 * Everything saved on the device. Every field has a default, so older files load into newer
 * versions; bump [version] and migrate in [LibraryStore.load] when a field changes meaning.
 */
@Serializable
data class LibraryData(
    val version: Int = CURRENT_VERSION,
    val liked: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val downloads: List<Song> = emptyList(),
    /** Downloads requested but not finished; resumed on the next start if the app was killed. */
    val pendingDownloads: List<Song> = emptyList(),
    val recent: List<Song> = emptyList(),
    val contextRecents: List<RecentItem> = emptyList(),
    val searches: List<String> = emptyList(),
    val autoplay: Boolean = true,
    val dataSaver: Boolean = false,
    /** Look lyrics up on LRCLIB automatically (sends title and artist to lrclib.net). */
    val autoLyrics: Boolean = true,
    /** Keep a copy of the library in Download/VELVET so it survives uninstalling. */
    val autoBackup: Boolean = true,
    val eq: EqSettings = EqSettings(),
    val isDarkTheme: Boolean = true,
    val amoledMode: Boolean = true,
    val accentColor: String = "VELVET",
    val fontPreference: String = "SYSTEM",
    val musicLanguages: List<String> = listOf("Hindi", "English"),
    val seekbarStyle: String = "MINIMAL",
    val showPulseWave: Boolean = true,
    val pulseVisualizerStyle: String = "FLUID_WAVE",
    val developerOptionsUnlocked: Boolean = false,
    val selfHealingEnabled: Boolean = true,
    val includePrereleases: Boolean = false,
    val autoUpdateCheck: Boolean = true,
    val skipNearDuplicatesInShuffle: Boolean = true,
    val playedGroupIds: List<String> = emptyList(),
    val listenEvents: List<ListenEvent> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

/** How the library file was found at startup. */
sealed interface LoadResult {
    /** No file yet: first run. */
    data object Missing : LoadResult

    /** Read fine. [fromBackup] when the main file was damaged and the last good copy was used. */
    data class Loaded(val data: LibraryData, val fromBackup: Boolean) : LoadResult

    /** Unreadable, with no usable backup. The damaged file was kept as [quarantined]. */
    data class Failed(val quarantined: File?, val reason: String) : LoadResult
}

/**
 * Reads and writes `library.json` safely: a damaged file is never treated as an empty library,
 * it is copied aside and the last successfully loaded copy (`library.json.bak`) is used instead.
 * Writes go to a temp file that is synced to disk before it replaces the real one.
 */
class LibraryStore(private val dir: File) {
    private val file = File(dir, "library.json")
    private val backup = File(dir, "library.json.bak")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): LoadResult {
        if (!file.exists()) {
            // a crash between deleting and renaming could leave only the backup
            return parse(backup)?.let { LoadResult.Loaded(it, fromBackup = true) } ?: LoadResult.Missing
        }
        val main = parse(file)
        if (main != null) {
            // remember this as the last known good copy
            runCatching { file.copyTo(backup, overwrite = true) }
            return LoadResult.Loaded(main, fromBackup = false)
        }
        val quarantined = runCatching {
            File(dir, "library.json.corrupt-${System.currentTimeMillis()}").also { file.copyTo(it) }
        }.getOrNull()
        val fromBackup = parse(backup)
        return if (fromBackup != null) LoadResult.Loaded(fromBackup, fromBackup = true)
        else LoadResult.Failed(quarantined, "library.json could not be read")
    }

    /** Writes [data]; throws on failure so the caller can retry and report. */
    fun save(data: LibraryData) {
        val tmp = File(dir, "library.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(LibraryData.serializer(), data).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // some filesystems won't rename over an existing file
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Couldn't replace library.json")
        }
    }

    private fun parse(f: File): LibraryData? = runCatching {
        if (!f.exists()) return null
        migrate(json.decodeFromString<LibraryData>(f.readText()))
    }.getOrNull()

    // version 1 files had no version field (decoded as the default); nothing to convert yet
    private fun migrate(data: LibraryData): LibraryData {
        var current = if (data.version < LibraryData.CURRENT_VERSION) data.copy(version = LibraryData.CURRENT_VERSION) else data
        val coversDir = File(dir, "covers")
        val migratedPlaylists = current.playlists.map { p ->
            var pl = p
            // 1. Ensure entries are populated from legacy songs if missing
            if (pl.entries.isEmpty() && pl.songs.isNotEmpty()) {
                val entries = pl.songs.mapIndexed { i, s ->
                    PlaylistEntry(entryId = "${pl.id}_legacy_${i}_${s.id}", song = s, addedAt = 0L)
                }
                pl = pl.copy(entries = entries, songs = entries.map { it.song })
            } else if (pl.entries.isNotEmpty() && pl.songs.isEmpty()) {
                pl = pl.copy(songs = pl.entries.map { it.song })
            }
            // 2. Ensure createdAt / addedAt consistency
            if (pl.createdAt == 0L && pl.addedAt > 0L) {
                pl = pl.copy(createdAt = pl.addedAt)
            } else if (pl.addedAt == 0L && pl.createdAt > 0L) {
                pl = pl.copy(addedAt = pl.createdAt)
            }
            // 3. Migrate legacy customCover string if coverMode is AUTO and coverRef is null
            if (pl.coverMode == CoverMode.AUTO && pl.coverRef == null && pl.customCover != null) {
                val legacy = pl.customCover
                pl = when {
                    legacy.startsWith("preset:") -> {
                        pl.copy(coverMode = CoverMode.PRESET, coverRef = legacy.removePrefix("preset:"))
                    }
                    legacy.startsWith("file://") || legacy.startsWith("/") -> {
                        val fileName = File(legacy.removePrefix("file://")).name
                        pl.copy(coverMode = CoverMode.CUSTOM_IMAGE, coverRef = fileName)
                    }
                    else -> {
                        pl.copy(coverMode = CoverMode.TRACK_ART, coverRef = legacy)
                    }
                }
            }
            // 4. If custom image file does not exist on disk, silently fall back to AUTO
            if (pl.coverMode == CoverMode.CUSTOM_IMAGE) {
                val fileName = pl.coverRef
                val file = if (fileName != null) File(coversDir, fileName) else null
                if (file == null || !file.exists() || file.length() == 0L) {
                    pl = pl.copy(coverMode = CoverMode.AUTO, coverRef = null, customCover = null)
                }
            }
            pl
        }
        current = current.copy(playlists = migratedPlaylists)

        if (current.contextRecents.isEmpty() && current.recent.isNotEmpty()) {
            val migrated = ContextRecentsManager.migrateOldFormat(current.recent, current.playlists)
            if (migrated.isNotEmpty()) {
                current = current.copy(contextRecents = migrated)
            }
        }
        return current.copy(contextRecents = ContextRecentsManager.filterDeletedPlaylists(current.contextRecents, current.playlists))
    }
}

/** Everything the user saves. State changes are immediate; disk writes are batched in the background. */
class Library(dir: File) {
    private val store = LibraryStore(dir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignals = Channel<Unit>(Channel.CONFLATED)

    val loadResult: LoadResult = store.load()

    /** False after a failed load: then nothing may be deleted on the library's word (e.g. downloads). */
    val loadedCleanly: Boolean = loadResult !is LoadResult.Failed

    private val _data = MutableStateFlow(
        when (loadResult) {
            is LoadResult.Loaded -> loadResult.data
            else -> LibraryData()
        }
    )
    val data: StateFlow<LibraryData> = _data

    private val _saveError = MutableStateFlow<String?>(null)

    /** Set when the library couldn't be written after retries. */
    val saveError: StateFlow<String?> = _saveError

    // id sets so long song lists can check "liked?" / "downloaded?" cheaply
    val likedIds: StateFlow<Set<String>> =
        _data.map { d -> d.liked.mapTo(HashSet()) { it.id } }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    val downloadedIds: StateFlow<Set<String>> =
        _data.map { d -> d.downloads.mapTo(HashSet()) { it.id } }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        // one writer: rapid changes (EQ sliders, likes) collapse into a single write of the latest state
        scope.launch {
            for (signal in saveSignals) {
                delay(SAVE_COALESCE_MS)
                writeWithRetry()
            }
        }
    }

    // ---------------------------------------------------------------- liked songs

    fun isLiked(id: String) = _data.value.liked.any { it.id == id }

    fun toggleLike(song: Song) = update { d ->
        if (d.liked.any { it.id == song.id }) d.copy(liked = d.liked.filterNot { it.id == song.id })
        else d.copy(liked = listOf(song) + d.liked)
    }

    // ---------------------------------------------------------------- playlists

    val coversDir: File = File(dir, "covers").apply { mkdirs() }

    fun deleteCoverFile(fileName: String?) {
        if (!fileName.isNullOrBlank()) {
            runCatching {
                val f = File(coversDir, fileName)
                if (f.exists()) f.delete()
            }
        }
    }

    fun createPlaylist(name: String, songs: List<Song> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        val entries = songs.mapIndexed { i, s ->
            PlaylistEntry(entryId = UUID.randomUUID().toString(), song = s, addedAt = now())
        }
        val playlist = Playlist(
            id = id,
            name = name.trim(),
            songs = songs,
            entries = entries,
            createdAt = now(),
            addedAt = now(),
            updatedAt = now(),
            coverMode = CoverMode.AUTO,
            sortKey = SortKey.CUSTOM,
            sortDirection = SortDirection.ASC,
        )
        update { it.copy(playlists = listOf(playlist) + it.playlists) }
        return id
    }

    fun duplicatePlaylist(id: String): String? {
        val original = _data.value.playlists.find { it.id == id } ?: return null
        val newId = UUID.randomUUID().toString()
        val newName = "${original.name} (Copy)"
        val copiedEntries = original.resolvedEntries().map {
            it.copy(entryId = UUID.randomUUID().toString(), addedAt = now())
        }
        var newCoverRef = original.coverRef
        if (original.coverMode == CoverMode.CUSTOM_IMAGE && original.coverRef != null) {
            val origFile = File(coversDir, original.coverRef)
            if (origFile.exists()) {
                val copyFileName = "${newId}_${now()}.jpg"
                origFile.copyTo(File(coversDir, copyFileName), overwrite = true)
                newCoverRef = copyFileName
            }
        }
        val copy = original.copy(
            id = newId,
            name = newName,
            entries = copiedEntries,
            songs = copiedEntries.map { it.song },
            createdAt = now(),
            addedAt = now(),
            updatedAt = now(),
            playCount = 0L,
            lastPlayedAt = 0L,
            coverRef = newCoverRef,
            customCover = if (original.coverMode == CoverMode.CUSTOM_IMAGE && newCoverRef != null) "file://${File(coversDir, newCoverRef).absolutePath}" else original.customCover,
        )
        update { it.copy(playlists = listOf(copy) + it.playlists) }
        return newId
    }

    fun renamePlaylist(id: String, name: String) = updatePlaylist(id) { it.copy(name = name.trim()) }

    fun updatePlaylistDetails(id: String, name: String, description: String? = null) = updatePlaylist(id) {
        it.copy(name = name.trim(), description = description?.trim()?.ifEmpty { null })
    }

    fun setPlaylistCover(id: String, mode: CoverMode, ref: String?) = updatePlaylist(id) { p ->
        if (p.coverMode == CoverMode.CUSTOM_IMAGE && p.coverRef != null && p.coverRef != ref) {
            deleteCoverFile(p.coverRef)
        }
        val legacy = when (mode) {
            CoverMode.PRESET -> "preset:$ref"
            CoverMode.CUSTOM_IMAGE -> ref?.let { "file://${File(coversDir, it).absolutePath}" }
            CoverMode.TRACK_ART -> ref
            CoverMode.AUTO -> null
        }
        p.copy(coverMode = mode, coverRef = ref, customCover = legacy)
    }

    fun revertPlaylistCover(id: String) = updatePlaylist(id) { p ->
        if (p.coverMode == CoverMode.CUSTOM_IMAGE && p.coverRef != null) {
            deleteCoverFile(p.coverRef)
        }
        p.copy(coverMode = CoverMode.AUTO, coverRef = null, customCover = null)
    }

    fun setPlaylistCover(id: String, cover: String?) = updatePlaylist(id) { p ->
        if (cover == null) {
            if (p.coverMode == CoverMode.CUSTOM_IMAGE && p.coverRef != null) {
                deleteCoverFile(p.coverRef)
            }
            p.copy(coverMode = CoverMode.AUTO, coverRef = null, customCover = null)
        } else if (cover.startsWith("preset:")) {
            if (p.coverMode == CoverMode.CUSTOM_IMAGE && p.coverRef != null) {
                deleteCoverFile(p.coverRef)
            }
            val presetId = cover.removePrefix("preset:")
            p.copy(coverMode = CoverMode.PRESET, coverRef = presetId, customCover = cover)
        } else if (cover.startsWith("file://") || cover.startsWith("/")) {
            val fileName = File(cover.removePrefix("file://")).name
            p.copy(coverMode = CoverMode.CUSTOM_IMAGE, coverRef = fileName, customCover = cover)
        } else {
            p.copy(coverMode = CoverMode.TRACK_ART, coverRef = cover, customCover = cover)
        }
    }

    fun setPlaylistSort(id: String, key: SortKey, direction: SortDirection) = updatePlaylist(id) { p ->
        p.copy(sortKey = key, sortDirection = direction)
    }

    fun savePlaylistOrder(id: String, reordered: List<PlaylistEntry>) = updatePlaylist(id) { p ->
        p.copy(
            entries = reordered,
            songs = reordered.map { it.song },
            sortKey = SortKey.CUSTOM,
            sortDirection = SortDirection.ASC,
        )
    }

    fun deletePlaylist(id: String) {
        val target = _data.value.playlists.find { it.id == id }
        if (target != null && target.coverMode == CoverMode.CUSTOM_IMAGE && target.coverRef != null) {
            deleteCoverFile(target.coverRef)
        }
        update { d ->
            d.copy(
                playlists = d.playlists.filterNot { it.id == id },
                contextRecents = d.contextRecents.filterNot { it.type == RecentType.LOCAL_PLAYLIST && it.id == id },
            )
        }
    }

    internal fun recordPlaylistPlay(id: String) = update { d ->
        d.copy(playlists = d.playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(playCount = playlist.playCount + 1, lastPlayedAt = now()) else playlist
        })
    }

    fun addToPlaylist(playlistId: String, song: Song) = updatePlaylist(playlistId) { p ->
        val newEntry = PlaylistEntry(entryId = UUID.randomUUID().toString(), song = song, addedAt = now())
        val entries = p.resolvedEntries() + newEntry
        p.copy(entries = entries, songs = entries.map { it.song })
    }

    fun removeEntryFromPlaylist(playlistId: String, entryId: String) = updatePlaylist(playlistId) { p ->
        val entries = p.resolvedEntries().filterNot { it.entryId == entryId }
        p.copy(entries = entries, songs = entries.map { it.song })
    }

    fun removeFromPlaylist(playlistId: String, songId: String) = updatePlaylist(playlistId) { p ->
        val entries = p.resolvedEntries().filterNot { it.song.id == songId }
        p.copy(entries = entries, songs = entries.map { it.song })
    }

    fun moveInPlaylist(playlistId: String, from: Int, to: Int) = updatePlaylist(playlistId) { p ->
        val entries = p.resolvedEntries().toMutableList()
        if (from !in entries.indices || to !in entries.indices) return@updatePlaylist p
        entries.add(to, entries.removeAt(from))
        p.copy(entries = entries, songs = entries.map { it.song })
    }

    private fun updatePlaylist(id: String, change: (Playlist) -> Playlist) = update { d ->
        var updatedPlaylist: Playlist? = null
        val updatedPlaylists = d.playlists.map {
            if (it.id == id) {
                val updated = change(it).copy(updatedAt = now())
                updatedPlaylist = updated
                updated
            } else it
        }
        val updatedRecents = if (updatedPlaylist != null) {
            d.contextRecents.map { item ->
                if (item.type == RecentType.LOCAL_PLAYLIST && item.id == id) {
                    val p = updatedPlaylist!!
                    val coverUrl = when (p.coverMode) {
                        CoverMode.CUSTOM_IMAGE -> p.coverRef?.let { "file://${File(coversDir, it).absolutePath}" }
                        CoverMode.PRESET -> p.customCover ?: "preset:${p.coverRef}"
                        CoverMode.TRACK_ART -> p.songs.find { it.id == p.coverRef }?.thumb ?: p.songs.firstOrNull()?.thumb
                        CoverMode.AUTO -> p.songs.firstOrNull()?.thumb
                    } ?: p.customCover ?: p.songs.firstOrNull()?.thumb
                    item.copy(
                        title = p.name,
                        artworkUrl = coverUrl,
                    )
                } else item
            }
        } else d.contextRecents
        d.copy(playlists = updatedPlaylists, contextRecents = updatedRecents)
    }

    // ---------------------------------------------------------------- albums & artists

    fun isAlbumSaved(url: String) = _data.value.albums.any { it.url == url }

    fun toggleAlbum(album: AlbumRef) = update { d ->
        if (d.albums.any { it.url == album.url }) d.copy(albums = d.albums.filterNot { it.url == album.url })
        else d.copy(albums = listOf(album.copy(addedAt = now())) + d.albums)
    }

    fun isFollowing(url: String) = _data.value.artists.any { it.url == url }

    fun toggleFollow(artist: ArtistRef) = update { d ->
        if (d.artists.any { it.url == artist.url }) d.copy(artists = d.artists.filterNot { it.url == artist.url })
        else d.copy(artists = listOf(artist.copy(addedAt = now())) + d.artists)
    }

    // ---------------------------------------------------------------- history & downloads

    fun addRecent(song: Song) = update { d ->
        d.copy(recent = (listOf(song) + d.recent.filterNot { it.id == song.id }).take(MAX_RECENT))
    }

    fun clearRecent() = update { it.copy(recent = emptyList()) }

    fun addContextRecent(context: PlaybackContext) = update { d ->
        val updatedContextRecents = ContextRecentsManager.record(d.contextRecents, context, now())
        if (context.type == RecentType.LOCAL_PLAYLIST) {
            d.copy(
                contextRecents = updatedContextRecents,
                playlists = d.playlists.map { p ->
                    if (p.id == context.id) p.copy(lastPlayedAt = now(), playCount = p.playCount + 1) else p
                },
            )
        } else {
            d.copy(contextRecents = updatedContextRecents)
        }
    }

    fun removeContextRecent(id: String, type: RecentType) = update { d ->
        d.copy(contextRecents = ContextRecentsManager.remove(d.contextRecents, id, type))
    }

    fun clearContextRecents() = update { it.copy(contextRecents = emptyList()) }

    fun addSearch(query: String) = update { d ->
        val q = query.trim()
        d.copy(searches = (listOf(q) + d.searches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES))
    }

    fun removeSearch(query: String) = update { d -> d.copy(searches = d.searches - query) }

    fun clearSearches() = update { it.copy(searches = emptyList()) }

    fun addPendingDownload(song: Song) = update { d ->
        if (d.pendingDownloads.any { it.id == song.id }) d else d.copy(pendingDownloads = d.pendingDownloads + song)
    }

    fun removePendingDownload(id: String) = update { d -> d.copy(pendingDownloads = d.pendingDownloads.filterNot { it.id == id }) }

    /** Marks a download finished: one state change, so it can't be both pending and done. */
    fun completeDownload(song: Song) = update { d ->
        d.copy(
            downloads = listOf(song) + d.downloads.filterNot { it.id == song.id },
            pendingDownloads = d.pendingDownloads.filterNot { it.id == song.id },
        )
    }

    fun removeDownload(id: String) = update { d ->
        d.copy(downloads = d.downloads.filterNot { it.id == id }, pendingDownloads = d.pendingDownloads.filterNot { it.id == id })
    }

    // ---------------------------------------------------------------- settings

    fun setAutoplay(enabled: Boolean) = update { it.copy(autoplay = enabled) }

    fun setDarkTheme(dark: Boolean) = update { it.copy(isDarkTheme = dark) }

    fun setAmoledMode(amoled: Boolean) = update { it.copy(amoledMode = amoled) }

    fun setAccentColor(accent: String) = update { it.copy(accentColor = accent) }

    fun setFontPreference(font: String) = update { it.copy(fontPreference = font) }

    fun setMusicLanguages(languages: List<String>) = update { it.copy(musicLanguages = languages) }

    fun setSeekbarStyle(style: String) = update { it.copy(seekbarStyle = style) }

    fun setShowPulseWave(enabled: Boolean) = update { it.copy(showPulseWave = enabled) }

    fun setPulseVisualizerStyle(style: String) = update { it.copy(pulseVisualizerStyle = style) }

    fun setDeveloperOptions(unlocked: Boolean) = update { it.copy(developerOptionsUnlocked = unlocked) }

    fun setSelfHealing(enabled: Boolean) = update { it.copy(selfHealingEnabled = enabled) }

    fun setIncludePrereleases(include: Boolean) = update { it.copy(includePrereleases = include) }

    fun setAutoUpdateCheck(enabled: Boolean) = update { it.copy(autoUpdateCheck = enabled) }

    fun setSkipNearDuplicatesInShuffle(enabled: Boolean) = update { it.copy(skipNearDuplicatesInShuffle = enabled) }

    fun recordListenEvent(event: ListenEvent) = update { current ->
        val now = now()
        val retentionCutoff = now - (180L * 24 * 60 * 60 * 1000L) // 180 days
        val prunedEvents = (current.listenEvents.filter { it.startedAt >= retentionCutoff } + event)
            .takeLast(20_000)

        val updatedGroupIds = (listOf(event.songGroupId) + current.playedGroupIds.filter { it != event.songGroupId })
            .take(200)

        current.copy(
            listenEvents = prunedEvents,
            playedGroupIds = updatedGroupIds,
        )
    }

    fun clearListenEvents() = update { it.copy(listenEvents = emptyList(), playedGroupIds = emptyList()) }

    fun setPlaylistSongs(playlistId: String, songs: List<Song>) = updatePlaylist(playlistId) { p ->
        val entries = songs.mapIndexed { i, s ->
            PlaylistEntry(entryId = UUID.randomUUID().toString(), song = s, addedAt = now())
        }
        p.copy(entries = entries, songs = songs)
    }

    fun setPlaylistEntries(playlistId: String, entries: List<PlaylistEntry>) = updatePlaylist(playlistId) { p ->
        p.copy(entries = entries, songs = entries.map { it.song })
    }

    fun setDataSaver(enabled: Boolean) = update { it.copy(dataSaver = enabled) }

    fun setAutoLyrics(enabled: Boolean) = update { it.copy(autoLyrics = enabled) }

    fun updateEq(change: (EqSettings) -> EqSettings) = update { it.copy(eq = change(it.eq)) }

    fun setAutoBackup(enabled: Boolean) = update { it.copy(autoBackup = enabled) }

    /** Merges a restored backup into the library (nothing already here is lost). */
    fun restore(incoming: LibraryData) = update { mergeLibraries(it, incoming) }

    /** Saves an imported playlist; returns its id. */
    fun importPlaylist(name: String, songs: List<Song>): String = createPlaylist(name, songs.distinctBy { it.id })

    // ---------------------------------------------------------------- storage

    private fun update(change: (LibraryData) -> LibraryData) {
        _data.updateAndGet(change)
        saveSignals.trySend(Unit)
    }

    /** Writes the latest state now (blocking). For tests and for shutdown paths. */
    fun flushBlocking() = writeOnce()

    private suspend fun writeWithRetry() {
        repeat(SAVE_ATTEMPTS) { attempt ->
            if (writeOnce()) return
            delay(200L shl attempt)
        }
    }

    private fun writeOnce(): Boolean = try {
        store.save(_data.value)
        _saveError.value = null
        true
    } catch (e: Exception) {
        _saveError.value = e.message ?: "Couldn't save your library"
        false
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val MAX_RECENT = 100
        private const val MAX_SEARCHES = 15
        private const val SAVE_COALESCE_MS = 150L
        private const val SAVE_ATTEMPTS = 3
    }
}
