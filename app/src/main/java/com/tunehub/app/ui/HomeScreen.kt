package com.tunehub.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tunehub.app.R
import com.tunehub.app.data.isPureMusic
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tunehub.app.TuneHubApp
import com.tunehub.app.data.AlbumRef
import com.tunehub.app.data.BrowseItem
import com.tunehub.app.data.Song
import com.tunehub.app.data.YouTube
import com.tunehub.app.data.homePlaylists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    var quickPicks by mutableStateOf<List<Song>>(emptyList())
        private set
    var trending by mutableStateOf<List<Song>>(emptyList())
        private set
    var moreForYou by mutableStateOf<List<AlbumRef>>(emptyList())
        private set
    private var historyIds = emptyList<String>()
    private var quickPicksJob: Job? = null

    init {
        viewModelScope.launch {
            val locale = Locale.getDefault().toLanguageTag()
            val cache = withContext(Dispatchers.IO) {
                application.getSharedPreferences("home_feed", Context.MODE_PRIVATE)
            }
            val (cached, fetchedAt) = withContext(Dispatchers.IO) {
                val snapshot = cache.all
                val songs = if (snapshot["locale"] == locale) {
                    (snapshot["songs"] as? String)?.let { encoded ->
                        runCatching { feedJson.decodeFromString(songList, encoded) }.getOrNull()
                    }.orEmpty()
                } else {
                    emptyList()
                }
                songs to (snapshot["fetched_at"] as? Long ?: 0L)
            }
            trending = cached

            // Fetch YouTube Music pre-made albums and playlists for "More for you"
            loadMoreForYou(emptyList())

            val age = System.currentTimeMillis() - fetchedAt
            if (cached.isNotEmpty() && age in 0 until feedMaxAge) return@launch

            val latest = withContext(Dispatchers.IO) {
                val songs = runCatching { YouTube.trendingMusic().rankMusic().take(20) }.getOrDefault(emptyList())
                if (songs.isNotEmpty()) {
                    cache.edit()
                        .putString("locale", locale)
                        .putString("songs", feedJson.encodeToString(songList, songs))
                        .putLong("fetched_at", System.currentTimeMillis())
                        .apply()
                }
                songs
            }
            if (latest.isNotEmpty()) trending = latest
        }
    }

    /** Build Quick Picks from the YouTube Music song catalog of recently played artists. */
    fun loadFor(recent: List<Song>) {
        val ids = recent.map { it.id }
        if (ids == historyIds) return
        historyIds = ids
        quickPicksJob?.cancel()
        if (recent.isEmpty()) {
            quickPicks = emptyList()
            return
        }

        val played = ids.toSet()
        quickPicks = quickPicks.filterNot { it.id in played }
        val seeds = recent.asSequence()
            .filter { it.artist.isNotBlank() }
            .distinctBy { it.artist.trim().lowercase(Locale.ROOT) }
            .take(3)
            .toList()
        quickPicksJob = viewModelScope.launch {
            val groups = seeds.map { seed ->
                async(Dispatchers.IO) {
                    val artist = seed.artist.trim()
                    runCatching { YouTube.Search(artist, YouTube.FILTER_SONGS).loadNext() }
                        .getOrDefault(emptyList())
                        .filterIsInstance<BrowseItem.SongItem>()
                        .map { it.song }
                        .filter { song ->
                            song.id !in played && (song.artist.equals(artist, ignoreCase = true) ||
                                song.artist.startsWith("$artist and ", ignoreCase = true) ||
                                (seed.artistUrl != null && song.artistUrl == seed.artistUrl))
                        }
                }
            }.awaitAll()
            // Take results by rank across artists before moving deeper into any one catalog.
            quickPicks = buildList {
                repeat(groups.maxOfOrNull { it.size } ?: 0) { rank ->
                    groups.mapNotNullTo(this) { it.getOrNull(rank) }
                }
            }.distinctBy { it.id }.take(10)
        }
    }

    /** Refresh More For You albums and playlists dynamically using chosen languages and periodic rotation */
    fun loadMoreForYou(languages: List<String>) {
        val langs = languages.ifEmpty { listOf("Hindi", "English") }
        viewModelScope.launch(Dispatchers.IO) {
            val variants = listOf("Top Hits", "Trending Now", "Viral Hits", "New Releases", "Essentials", "Party Hits")
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val variant = variants[(hour / 3) % variants.size]

            val collected = mutableListOf<AlbumRef>()
            for (lang in langs.take(3)) {
                val query1 = "$lang $variant"
                val playlists = runCatching {
                    YouTube.Search(query1, YouTube.FILTER_PLAYLISTS).loadNext()
                        .filterIsInstance<BrowseItem.AlbumItem>()
                        .map { it.album }
                }.getOrDefault(emptyList())

                val query2 = "$lang Hits"
                val albums = runCatching {
                    YouTube.Search(query2, YouTube.FILTER_ALBUMS).loadNext()
                        .filterIsInstance<BrowseItem.AlbumItem>()
                        .map { it.album }
                }.getOrDefault(emptyList())

                collected.addAll(playlists.take(4))
                collected.addAll(albums.take(4))
            }
            val finalItems = collected.distinctBy { it.url }
            if (finalItems.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    moreForYou = finalItems
                }
            }
        }
    }
}

private fun List<Song>.rankMusic() = distinctBy { it.id }.sortedByDescending { it.preferredMusicSource }

private val feedJson = Json { ignoreUnknownKeys = true }
private val songList = ListSerializer(Song.serializer())
private val feedMaxAge = TimeUnit.MINUTES.toMillis(30)

/** A tile in the top shortcuts grid. */
private class Shortcut(val title: String, val art: @Composable () -> Unit, val open: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    val nav = LocalNav.current
    val data by TuneHubApp.library.data.collectAsStateWithLifecycle()
    val recent = data.recent
    val quickPicks = vm.quickPicks
    val trending = vm.trending
    val moreForYou = vm.moreForYou
    val languages = data.musicLanguages
    val openMenu = LocalSongMenu.current

    LaunchedEffect(recent) { vm.loadFor(recent) }
    LaunchedEffect(languages) { vm.loadMoreForYou(languages) }

    // User's playlists for top shortcuts (Liked Songs is now exclusively in Library, Recently Played in History button)
    val userPlaylists = data.playlists.homePlaylists()
    val shortcuts = buildList {
        userPlaylists.forEach { p ->
            add(Shortcut(p.name, { PlaylistCover(p.songs, 58.dp, corner = 16.dp, customCover = p.customCover) }) { nav.open(Route.LocalPlaylist(p.id)) })
        }
    }

    // one radio per artist you actually listen to, each shown with that artist's song cover
    val mixes = (recent + data.liked).distinctBy { it.artist.lowercase() }.take(8)

    // Speed Dial songs: 2-3 tracks from recent/favorites + 6-7 fresh discovery tracks closely related to listening habits
    val speedDialSongs = remember(recent, data.liked, quickPicks, trending) {
        val userPicks = (recent + data.liked).distinctBy { it.id }.filter { it.isPureMusic() }.take(3)
        val freshDiscovery = (quickPicks + trending).distinctBy { it.id }
            .filter { fresh -> fresh.isPureMusic() && userPicks.none { it.id == fresh.id } }
            .take(6)
        (userPicks + freshDiscovery).take(9)
    }

    val greeting = remember {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Night vibes"
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "velvet-heading") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_velvet_logo),
                    contentDescription = "VELVET Logo",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(46.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "VELVET",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = SacrificeFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = tuneColors.text,
                    modifier = Modifier.weight(1f),
                )
                // History clock icon button immediately to the left of Settings
                PanelIconButton(AppIcons.History, "History") { nav.open(Route.Recent) }
                Spacer(Modifier.width(8.dp))
                PanelIconButton(Icons.Default.Settings, "Settings") { nav.open(Route.Settings) }
            }
        }

        item(key = "greeting") {
            Text(
                greeting,
                style = MaterialTheme.typography.titleMedium,
                color = tuneColors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        if (shortcuts.isNotEmpty()) {
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shortcuts.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { s -> ShortcutTile(s, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        val featured = recent.isEmpty() && PlayerConnection.current == null
        val spotlightQueue = if (featured) trending else recent
        val hero = PlayerConnection.current ?: spotlightQueue.firstOrNull()
        if (hero != null) {
            item(key = "spotlight") {
                Box(Modifier.padding(top = 10.dp)) {
                    SpotlightCard(hero, spotlightQueue, featured)
                }
            }
        }

        // YouTube Music Speed Dial section right between Now Playing / Spotlight and Made for you
        if (speedDialSongs.isNotEmpty()) {
            item(key = "speed-dial") {
                SpeedDialSection(speedDialSongs)
            }
        }

        if (mixes.isNotEmpty()) {
            item { BrowseSection("Made for you") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(mixes, key = { "mix-" + it.id }) { seed ->
                        MixCard("${seed.artist} Radio", seed.thumb) {
                            PlayerConnection.startRadio(seed)
                        }
                    }
                }
            }
        }

        if (quickPicks.isNotEmpty()) {
            item {
                QuickPicksRail(quickPicks) { index ->
                    PlayerConnection.startRadio(quickPicks[index])
                }
            }
        }

        if (trending.isNotEmpty()) {
            item {
                TrendingRail(trending)
            }
        }

        if (data.artists.isNotEmpty()) {
            item { BrowseSection("Your artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(data.artists, key = { it.url }) { a -> ArtistCircle(a, 112.dp) { nav.open(Route.Artist(a)) } }
                }
            }
        }

        // YouTube Music pre-made albums and playlists recommendation section
        if (moreForYou.isNotEmpty()) {
            item { BrowseSection("More for you") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(moreForYou, key = { it.url }) { album ->
                        AlbumCard(album) { nav.open(Route.Album(album)) }
                    }
                }
            }
        }
    }
}

/** YouTube Music style Speed Dial grid section */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialSection(songs: List<Song>) {
    val openMenu = LocalSongMenu.current

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Speed Dial",
                style = MaterialTheme.typography.titleLarge,
                color = tuneColors.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Quick play",
                style = MaterialTheme.typography.bodySmall,
                color = tuneColors.accentText,
            )
        }

        // 3x3 Matrix layout: 3 rows of 3 columns, perfectly fitting screen width with no horizontal scroll
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val rows = songs.take(9).chunked(3)
            rows.forEach { rowSongs ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowSongs.forEach { song ->
                        SpeedDialSquareTile(
                            song = song,
                            modifier = Modifier.weight(1f),
                            onClick = { PlayerConnection.play(listOf(song)) },
                            onLongClick = { openMenu(SongMenuRequest(song)) },
                        )
                    }
                    repeat(3 - rowSongs.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialSquareTile(
    song: Song,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(tuneColors.raised)
            .border(1.dp, tuneColors.glassBorder, RoundedCornerShape(16.dp))
            .prefetchOnTouch(song.id)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (song.thumb != null) {
            AsyncImage(
                model = song.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.MusicNote,
                    contentDescription = null,
                    tint = tuneColors.muted,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // Solid dark gradient scrim for crisp legible typography
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x33000000),
                            Color(0xCC000000),
                            Color(0xF5000000),
                        ),
                        startY = 35f,
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0x80000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Play,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }

            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFFD1D5DB),
                )
            }
        }
    }
}

/** The first card shows a real track from playback, history or the trending feed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotlightCard(song: Song, queue: List<Song>, featured: Boolean) {
    val isCurrent = PlayerConnection.current?.id == song.id
    val openMenu = LocalSongMenu.current
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isCurrent) {
        while (isCurrent) {
            position = PlayerConnection.positionMs()
            delay(1000)
        }
    }
    val duration = PlayerConnection.durationMs
    val progress = if (isCurrent && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    GlassSurface(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isCurrent) PlayerConnection.togglePlay()
                    else PlayerConnection.play(queue.take(50), queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
                },
                onLongClick = { openMenu(SongMenuRequest(song)) },
            ),
        shape = RoundedCornerShape(22.dp),
        color = tuneColors.surface,
        borderColor = tuneColors.glassBorder,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    isCurrent -> "NOW PLAYING"
                    featured -> "FROM TRENDING"
                    else -> "CONTINUE LISTENING"
                },
                style = MaterialTheme.typography.labelSmall,
                color = tuneColors.accentText,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.thumb, 84.dp, corner = 12.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = tuneColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(tuneColors.rule),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(tuneColors.accentText),
                        )
                    }
                }
                AccentCircleButton(
                    if (isCurrent && PlayerConnection.isPlaying) AppIcons.Pause else AppIcons.Play,
                    if (isCurrent && PlayerConnection.isPlaying) "Pause" else "Play",
                    size = 56.dp,
                ) {
                    if (isCurrent) PlayerConnection.togglePlay()
                    else PlayerConnection.play(queue.take(50), queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
                }
            }
        }
    }
}

@Composable
private fun ShortcutTile(s: Shortcut, modifier: Modifier) {
    GlassSurface(
        modifier = modifier
            .height(70.dp)
            .clickable(onClick = s.open),
        shape = RoundedCornerShape(20.dp),
        color = tuneColors.surface,
        borderColor = tuneColors.glassBorder,
    ) {
        Row(
            Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            s.art()
            Text(
                s.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/** A radio mix: the seed song's cover with the mix name over a dark fade. */
@Composable
private fun MixCard(title: String, thumb: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .width(204.dp)
            .height(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Artwork(thumb, 204.dp, Modifier.matchParentSize(), corner = 18.dp)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(AppIcons.Radio, null, tint = tuneColors.accentText, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickPicksRail(songs: List<Song>, onPlay: (Int) -> Unit) {
    BrowseSection("Quick picks")
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongCard(song) { onPlay(index) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    val openMenu = LocalSongMenu.current
    Column(
        Modifier
            .width(144.dp)
            .prefetchOnTouch(song.id)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { openMenu(SongMenuRequest(song)) },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(song.thumb, 144.dp, corner = 16.dp)
        Text(
            song.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = tuneColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrendingRail(songs: List<Song>) {
    val openMenu = LocalSongMenu.current
    BrowseSection("Trending now")
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(songs.chunked(3), key = { _, group -> group.first().id }) { page, group ->
            GlassSurface(
                modifier = Modifier.width(280.dp),
                shape = RoundedCornerShape(18.dp),
                color = tuneColors.surface,
                borderColor = tuneColors.glassBorder,
            ) {
                Column {
                    group.forEachIndexed { offset, song ->
                        val index = page * 3 + offset
                        if (offset > 0) HorizontalDivider(Modifier.padding(start = 64.dp, end = 12.dp), color = tuneColors.rule)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .prefetchOnTouch(song.id)
                                .combinedClickable(
                                    onClick = { PlayerConnection.play(songs, index) },
                                    onLongClick = { openMenu(SongMenuRequest(song)) },
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Artwork(song.thumb, 44.dp, corner = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = tuneColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(AppIcons.Play, null, tint = tuneColors.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
