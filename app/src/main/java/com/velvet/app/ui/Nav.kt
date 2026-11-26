package com.velvet.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.velvet.app.data.AlbumRef
import com.velvet.app.data.ArtistRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface Route {
    @Serializable data object Liked : Route
    @Serializable data object Recent : Route
    @Serializable data object History : Route
    @Serializable data object Downloads : Route
    @Serializable data object Settings : Route
    @Serializable data object Equalizer : Route
    @Serializable data object Import : Route
    @Serializable data object Developer : Route
    @Serializable data object QueueHealth : Route
    @Serializable data class LocalPlaylist(val id: String) : Route
    @Serializable data class Album(val album: AlbumRef) : Route
    @Serializable data class Artist(val artist: ArtistRef) : Route
    @Serializable data class Genre(val name: String) : Route
}

/** What [Navigator] saves across rotation and process recreation. */
@Serializable
data class NavState(
    val tab: Int = 0,
    val stacks: List<List<Route>> = List(Navigator.TAB_COUNT) { emptyList() },
    val nowPlayingOpen: Boolean = false,
)

/** Runtime stack entry binding each open screen to its launching UI origin bounds. */
data class RouteStackEntry(
    val uid: Long,
    val route: Route,
    val origin: NavOrigin? = null,
)

/** Each bottom tab keeps its own back stack, like most music apps. */
class Navigator(initial: NavState = NavState()) {
    var tab by mutableIntStateOf(initial.tab.coerceIn(0, TAB_COUNT - 1))
        private set
    var nowPlayingOpen by mutableStateOf(initial.nowPlayingOpen)
    var originProvider: (() -> NavOrigin?)? = null
    private var uidCounter = 0L
    private val stacks = List(TAB_COUNT) { i ->
        mutableStateListOf<RouteStackEntry>().apply {
            initial.stacks.getOrNull(i).orEmpty().forEach { r ->
                add(RouteStackEntry(uid = ++uidCounter, route = r, origin = null))
            }
        }
    }

    val currentEntries: List<RouteStackEntry> get() = stacks[tab]
    val current: Route? get() = stacks[tab].lastOrNull()?.route
    val canGoBack: Boolean get() = nowPlayingOpen || stacks[tab].isNotEmpty() || tab != 0

    fun open(route: Route, origin: NavOrigin? = null) {
        nowPlayingOpen = false
        val currentTop = stacks[tab].lastOrNull()?.route
        if (currentTop == route && (route is Route.Settings || route is Route.Equalizer || route is Route.History || route is Route.Developer || route is Route.QueueHealth)) {
            return
        }
        val resolvedOrigin = origin ?: originProvider?.invoke()
        stacks[tab].add(RouteStackEntry(uid = ++uidCounter, route = route, origin = resolvedOrigin))
    }

    fun selectTab(i: Int) {
        // Modal / utility routes (Settings, Equalizer, Import, Developer) should never stick as persistent tab destinations
        stacks.forEach { it.removeAll { e -> e.route is Route.Settings || e.route is Route.Equalizer || e.route is Route.Import || e.route is Route.Developer || e.route is Route.QueueHealth } }
        // tapping the current tab again pops back to its root
        if (i == tab) stacks[i].clear() else tab = i
    }

    fun back() {
        when {
            nowPlayingOpen -> nowPlayingOpen = false
            stacks[tab].isNotEmpty() -> stacks[tab].removeAt(stacks[tab].lastIndex)
            else -> {
                stacks.forEach { it.removeAll { e -> e.route is Route.Settings || e.route is Route.Equalizer || e.route is Route.Import || e.route is Route.Developer || e.route is Route.QueueHealth } }
                tab = 0
            }
        }
    }

    fun state() = NavState(tab, stacks.map { list -> list.map { it.route } }, nowPlayingOpen)

    companion object {
        const val TAB_COUNT = 3
        private val json = Json { ignoreUnknownKeys = true }

        /** For rememberSaveable: stored as a JSON string, which a Bundle can hold. */
        val Saver: Saver<Navigator, String> = Saver(
            save = { json.encodeToString(NavState.serializer(), it.state()) },
            restore = { saved -> runCatching { Navigator(json.decodeFromString(NavState.serializer(), saved)) }.getOrNull() },
        )
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("No Navigator") }
