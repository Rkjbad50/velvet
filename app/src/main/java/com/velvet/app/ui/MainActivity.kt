package com.velvet.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velvet.app.VelvetApp
import com.velvet.app.data.AppUpdater
import com.velvet.app.data.LoadResult
import com.velvet.app.data.YouTube
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configurePreferredRefreshRate()
        setContent {
            val data by VelvetApp.library.data.collectAsStateWithLifecycle()
            val dark = data.isDarkTheme
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            VelvetTheme { AppRoot() }
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        PlayerConnection.connect(this)
    }

    override fun onResume() {
        super.onResume()
        configurePreferredRefreshRate()
        AppUpdater.checkInstallResult(this) { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Request the highest supported panel refresh rate (90/120Hz+) without altering screen resolution. */
    private fun configurePreferredRefreshRate() {
        runCatching {
            val currentDisplay = display ?: return
            val active = currentDisplay.mode ?: return
            val bestMode = currentDisplay.supportedModes
                .filter { it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight }
                .maxByOrNull { it.refreshRate }
                ?: currentDisplay.supportedModes.maxByOrNull { it.refreshRate }
            if (bestMode != null && bestMode.modeId != window.attributes.preferredDisplayModeId) {
                window.attributes = window.attributes.also { attrs ->
                    attrs.preferredDisplayModeId = bestMode.modeId
                }
            }
        }
    }

    override fun onStop() {
        PlayerConnection.disconnect()
        super.onStop()
    }

    /** "Share → VELVET" from the YouTube app plays that video's audio. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val id = intent.getStringExtra(Intent.EXTRA_TEXT)?.let(YouTube::videoIdFromText) ?: return
        lifecycleScope.launch {
            val song = withContext(Dispatchers.IO) {
                runCatching { YouTube.songFrom(YouTube.streamInfo(id)) }.getOrNull()
            }
            if (song == null) {
                Toast.makeText(this@MainActivity, "Couldn't open that link", Toast.LENGTH_SHORT).show()
            } else {
                PlayerConnection.startRadio(song)
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Home", Icons.Default.Home),
    Tab("Search", Icons.Default.Search),
    Tab("Library", AppIcons.Library),
)

@Composable
private fun AppRoot() {
    // survives rotation and the app being recreated
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    var menu by remember { mutableStateOf<SongMenuRequest?>(null) }
    val homeVm: HomeViewModel = viewModel(key = "home")
    val searchVm: SearchViewModel = viewModel(key = "search")
    val context = LocalContext.current
    val hasPlayer = PlayerConnection.current != null
    // Reserve the actual dock height so large navigation labels never overlap the screen.
    var dockHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val isImeVisible = imeBottom > 0
    val targetBottomRoom = if (isImeVisible) 0.dp else dockHeight
    val bottomRoom by animateDpAsState(targetBottomRoom, label = "bottomRoom")

    // tell the user once if their library file had to be recovered
    LaunchedEffect(Unit) {
        when (val result = VelvetApp.library.loadResult) {
            is LoadResult.Loaded -> if (result.fromBackup) {
                Toast.makeText(context, "Your library file was damaged, so the last good copy was restored", Toast.LENGTH_LONG).show()
            }
            is LoadResult.Failed -> Toast.makeText(
                context, "Your library couldn't be read. A copy was kept and your downloads are untouched", Toast.LENGTH_LONG,
            ).show()
            else -> Unit
        }
    }
    val saveError by VelvetApp.library.saveError.collectAsStateWithLifecycle()
    LaunchedEffect(saveError) {
        saveError?.let { Toast.makeText(context, "Couldn't save your library: $it", Toast.LENGTH_LONG).show() }
    }

    LaunchedEffect(PlayerConnection.error) {
        PlayerConnection.error?.let {
            Toast.makeText(context, "Playback error: $it", Toast.LENGTH_SHORT).show()
            PlayerConnection.error = null
        }
    }

    BackHandler(enabled = nav.canGoBack) { nav.back() }

    // no Scaffold/Surface any more, so set the default text and icon color for every screen here
    CompositionLocalProvider(
        LocalNav provides nav,
        LocalSongMenu provides { menu = it },
        LocalContentColor provides velvetColors.text,
    ) {
        val isPlayerOpen = nav.nowPlayingOpen && hasPlayer
        val playerOpenAnim = remember { Animatable(if (isPlayerOpen) 1f else 0f) }
        var renderNowPlaying by remember { mutableStateOf(isPlayerOpen) }
        var interactiveMinimize by remember { mutableFloatStateOf(0f) }
        var rootHeightPx by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(isPlayerOpen) {
            if (isPlayerOpen) {
                renderNowPlaying = true
                interactiveMinimize = 0f
                playerOpenAnim.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 500f))
            } else {
                if (interactiveMinimize > 0f) {
                    val remaining = (1f - interactiveMinimize).coerceIn(0f, 1f)
                    playerOpenAnim.snapTo(remaining * playerOpenAnim.value)
                    interactiveMinimize = 0f
                }
                playerOpenAnim.animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 560f))
                renderNowPlaying = false
            }
        }

        val playerExpandFraction = (playerOpenAnim.value * (1f - interactiveMinimize)).coerceIn(0f, 1f)

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { rootHeightPx = it.height.toFloat() },
        ) {
            PaperBackground()

            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val depthScale = 1f - 0.025f * playerExpandFraction
                        scaleX = depthScale
                        scaleY = depthScale
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = bottomRoom),
            ) {
                PlayerConnection.brokenStreamingBanner?.let { bannerText ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = bannerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    PlayerConnection.dismissStreamingBrokenBanner()
                                    nav.open(Route.Developer)
                                },
                            ) {
                                Text("Developer options")
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val detailTransition = updateTransition(targetState = nav.current, label = "detailScreenTransition")
                    val keepRootMeasured = detailTransition.currentState == null || detailTransition.targetState == null

                    // Keep Home/Search/Library hot in the Compose slot table so switching back to Home
                    // takes <1ms with zero remount delay/jerk, while gliding with crisp jelly spring physics.
                    PersistentRootTabs(
                        activeTab = nav.tab,
                        isRootVisible = keepRootMeasured,
                        homeVm = homeVm,
                        searchVm = searchVm,
                    )

                    @OptIn(ExperimentalAnimationApi::class)
                    detailTransition.AnimatedContent(
                        transitionSpec = {
                            if (targetState != null && initialState == null) {
                                // Detail push: crisp jelly-smooth horizontal slide + quick fade
                                (slideInHorizontally(
                                    initialOffsetX = { (it * 0.28f).toInt() },
                                    animationSpec = spring(dampingRatio = 0.80f, stiffness = 580f),
                                ) + fadeIn(animationSpec = tween(140, easing = FastOutSlowInEasing)))
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(110, easing = FastOutSlowInEasing)) +
                                            slideOutHorizontally(
                                                targetOffsetX = { -(it * 0.08f).toInt() },
                                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 620f),
                                            ),
                                    )
                            } else if (targetState == null && initialState != null) {
                                // Detail pop back to root tabs: fast jelly glide retreat
                                fadeIn(animationSpec = tween(120, easing = FastOutSlowInEasing))
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(130, easing = FastOutSlowInEasing)) +
                                            slideOutHorizontally(
                                                targetOffsetX = { (it * 0.30f).toInt() },
                                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 620f),
                                            ),
                                    )
                            } else {
                                // Detail-to-detail transition
                                (slideInHorizontally(
                                    initialOffsetX = { (it * 0.24f).toInt() },
                                    animationSpec = spring(dampingRatio = 0.80f, stiffness = 600f),
                                ) + fadeIn(animationSpec = tween(140)))
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(110)) +
                                            slideOutHorizontally(
                                                targetOffsetX = { -(it * 0.08f).toInt() },
                                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 620f),
                                            ),
                                    )
                            }
                        },
                    ) { currentRoute ->
                        if (currentRoute != null) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(velvetColors.background)
                                    .pointerInput(Unit) { detectTapGestures { } },
                            ) {
                                when (currentRoute) {
                                    Route.Liked -> LikedScreen(onBack = nav::back)
                                    Route.Recent -> RecentScreen(onBack = nav::back)
                                    Route.History -> HistoryScreen(onBack = nav::back)
                                    Route.Downloads -> DownloadsScreen(onBack = nav::back)
                                    Route.Settings -> SettingsScreen(onBack = nav::back)
                                    Route.Equalizer -> EqualizerScreen(onBack = nav::back)
                                    Route.Import -> ImportScreen(onBack = nav::back)
                                    Route.Developer -> DeveloperScreen(onBack = nav::back)
                                    is Route.LocalPlaylist -> LocalPlaylistScreen(currentRoute.id, onBack = nav::back)
                                    is Route.Album -> AlbumScreen(currentRoute.album, onBack = nav::back)
                                    is Route.Artist -> ArtistScreen(currentRoute.artist, onBack = nav::back)
                                    is Route.Genre -> GenreScreen(currentRoute.name, onBack = nav::back)
                                }
                            }
                        }
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        val p = playerExpandFraction
                        val scale = 1f - 0.05f * p
                        scaleX = scale
                        scaleY = scale
                        translationY = -12.dp.toPx() * p
                        alpha = (1f - p * 1.55f).coerceIn(0f, 1f)
                    }
                    .background(velvetColors.background)
                    .navigationBarsPadding(),
            ) {
                Column(
                    Modifier.onSizeChanged { size -> dockHeight = with(density) { size.height.toDp() } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiniPlayer(onOpen = { nav.nowPlayingOpen = true })
                    BottomTabs(
                        selected = nav.tab,
                        onSelect = { i ->
                            if (i == 1 && nav.tab == 1) searchVm.clear()
                            nav.selectTab(i)
                        },
                    )
                }
            }

            if (renderNowPlaying && hasPlayer) {
                if (playerExpandFraction > 0.01f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.44f * playerExpandFraction)),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = playerExpandFraction
                            val travel = (if (rootHeightPx > 0f) rootHeightPx else size.height) * 0.86f
                            translationY = (1f - p) * travel
                            val sX = 0.93f + 0.07f * p
                            val sY = 0.95f + 0.05f * p
                            scaleX = sX
                            scaleY = sY
                            alpha = (p * 3.2f).coerceIn(0f, 1f)
                            val radius = ((1f - p) * 28f).dp
                            if (radius > 0.dp) {
                                shape = RoundedCornerShape(radius)
                                clip = true
                            }
                        },
                ) {
                    NowPlayingScreen(
                        onMinimizeDrag = { fraction -> interactiveMinimize = fraction },
                        onClose = { nav.nowPlayingOpen = false },
                    )
                }
            }

            RadioNotificationBanner(
                bannerState = PlayerConnection.radioBannerState,
                modifier = Modifier.align(Alignment.TopCenter),
                onDismiss = { PlayerConnection.dismissRadioBanner() },
            )
        }

        menu?.let { request -> SongActionsSheet(request) { menu = null } }
    }
}

@Composable
private fun PersistentRootTabs(
    activeTab: Int,
    isRootVisible: Boolean,
    homeVm: HomeViewModel,
    searchVm: SearchViewModel,
) {
    var settledTab by remember { mutableIntStateOf(activeTab) }
    var outgoingTab by remember { mutableIntStateOf(activeTab) }
    val glideAnim = remember { Animatable(1f) }
    var directionSign by remember { mutableIntStateOf(1) }

    LaunchedEffect(activeTab) {
        if (activeTab != settledTab) {
            outgoingTab = settledTab
            directionSign = if (activeTab > settledTab) 1 else -1
            settledTab = activeTab
            glideAnim.snapTo(0f)
            glideAnim.animateTo(
                targetValue = 1f,
                // Crisp jelly-smooth gliding spring: snappy settle (~180ms), silky motion, zero sluggishness
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
            )
            outgoingTab = activeTab
        }
    }

    val p = glideAnim.value
    val isTransitioning = outgoingTab != settledTab && p < 0.998f

    Layout(
        content = {
            @Composable
            fun tabSlot(tabIndex: Int, content: @Composable () -> Unit) {
                val isIncoming = tabIndex == settledTab
                val isOutgoing = isTransitioning && tabIndex == outgoingTab
                val slotAlpha = when {
                    isIncoming && isTransitioning -> (0.25f + 0.75f * p).coerceIn(0f, 1f)
                    isIncoming -> 1f
                    isOutgoing -> (1f - p * 1.15f).coerceIn(0f, 1f)
                    else -> 0f
                }
                val slotScale = when {
                    isIncoming && isTransitioning -> 0.968f + 0.032f * p
                    isIncoming -> 1f
                    isOutgoing -> 1f - 0.032f * p
                    else -> 1f
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val travelPx = size.width * 0.32f
                            translationX = when {
                                isIncoming && isTransitioning -> directionSign * travelPx * (1f - p)
                                isOutgoing -> -directionSign * travelPx * 0.55f * p
                                else -> 0f
                            }
                            scaleX = slotScale
                            scaleY = slotScale
                            alpha = slotAlpha
                        },
                ) {
                    content()
                }
            }

            tabSlot(0) { HomeScreen(homeVm) }
            tabSlot(1) { SearchScreen(searchVm) }
            tabSlot(2) { LibraryScreen() }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        // Only measure & place active / transitioning tabs; unplaced tabs remain hot in composition
        // with zero draw cost, zero layout cost, and zero touch or semantics interference.
        val placeables = measurables.mapIndexed { idx, measurable ->
            val shouldPlace = (isRootVisible && (idx == settledTab || (isTransitioning && idx == outgoingTab)))
            if (shouldPlace) measurable.measure(constraints) else null
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Draw outgoing first so incoming tab glides cleanly over it
            if (isTransitioning) {
                placeables.getOrNull(outgoingTab)?.placeRelative(0, 0)
            }
            placeables.getOrNull(settledTab)?.placeRelative(0, 0)
        }
    }
}

@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    HorizontalDivider(color = velvetColors.rule)
    Row(
        Modifier
            .fillMaxWidth()
            .background(velvetColors.background)
            .selectableGroup()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val active = i == selected
            val background by animateColorAsState(
                if (active) velvetColors.surface else velvetColors.background,
                tween(160),
                label = "tabBackground",
            )
            val tint by animateColorAsState(
                if (active) velvetColors.accentText else velvetColors.muted,
                tween(160),
                label = "tabContent",
            )
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .selectable(selected = active, role = Role.Tab) { onSelect(i) }
                    .semantics { contentDescription = tab.label }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                Text(tab.label, style = MaterialTheme.typography.labelMedium, color = tint)
            }
        }
    }
}

