# VELVET Architecture Guide

A complete technical map of **VELVET** for Android: architecture, audio pipeline, UI layer, caching, and offline support.

Kotlin package root: `com.velvet.app` (`app/src/main/java/com/velvet/app/`).

---

## 1. Architecture Overview

VELVET is a modern, single-activity Android application built with Kotlin, Jetpack Compose, and Material 3 Expressive theming. It interfaces with YouTube Music to provide pure music streams without ads, trackers, or account dependencies.

| Component | Technology |
|---|---|
| minSdk / targetSdk / compileSdk | 31 / 36 / 36 |
| Language & Runtime | Kotlin 2.1.0, Coroutines, Flow |
| UI Framework | Jetpack Compose (BOM 2024.12.01), Material 3 Expressive |
| Media Engine | AndroidX Media3 1.5.1 (ExoPlayer, MediaSession, CacheDataSource) |
| Audio Extraction | NewPipeExtractor (v0.27.1) & native YT Music InnerTube |
| Image Loading | Coil 2.7 |
| Networking | OkHttp 4.12 |
| Persistence | kotlinx.serialization (Encrypted/Local JSON store in internal storage) |
| License | GPL-3.0 |

---

## 2. Key Modules & Subsystems

### UI Layer (`com.velvet.app.ui`)
- **`Theme.kt`**: Material 3 Expressive theming engine featuring 100% solid, opaque surfaces, deep AMOLED black mode, clean light mode, and signature VELVET accents (Velvet Purple, Emerald, Cyan, Rose, Amber, Blue).
- **`HomeScreen.kt`**: Main discovery hub with Quick Shortcuts, Now Playing spotlight, pure music trending, artist mixes, and a 3x3 matrix Speed Dial grid for rapid one-tap playback.
- **`NowPlaying.kt`**: Full-screen player with high-resolution artwork, time-synced lyrics with LRCLIB, 5-band equalizer shortcut, sleep timer, and swipe gestures.
- **`Components.kt`**: Shared design elements including solid `ModalBottomSheet` containers, pure music filtering badges, and adaptive artwork loaders.
- **`CollectionScreens.kt` & `LibraryScreen.kt`**: Local playlist editor with real-time undo toast, multi-mode sorting (A-Z, artist, duration), liked songs hub, and downloads manager.
- **`SettingsScreen.kt`**: Preferences for typography (System, Inter, Outfit, Space Grotesk, Source Serif), accent colors, streaming cache size, auto lyrics, and update checker.

### Audio & Playback Engine (`com.velvet.app.playback`)
- **`PlaybackService.kt`**: Foreground `MediaSessionService` coordinating ExoPlayer, audio focus, lock-screen controls, system media notifications, and crash-safe state persistence across stop/task-removal lifecycle events. Track transitions are direct and gapless-friendly via ahead-of-time prefetching (`StreamResolver.prefetch`); crossfade automix has been completely removed.
- **`PlayerQueueManager.kt`**: Logical single source of truth for the active track, explicit `userQueue`, upcoming tracks, history, and repeat/shuffle modes, restoring full session context atomically across restarts.
- **`PlaybackStateManager.kt`**: Atomic temp-file + `fsync` + atomic rename checkpoint store (`persisted_playback_state.json`) saving queue/radio context on 300ms debounced edits, pause/seek/stop transitions, and 15s periodic ticks during playback (`PlaybackCheckpointScheduler`).
- **`AudioFx.kt`**: Audio processing engine providing 5-band graphic equalizer, Bass Boost, Virtualizer (3D audio stage), and LoudnessEnhancer.
- **`SleepTimer.kt`**: Audio countdown timer with graceful volume fade-out.
- **`PlaybackTrackNormalizers.kt`**: Thread-safe `SessionHistory` ring buffer and diacritic/Hindi-Latin title & artist normalizers shared by dismissal tracking and recommendation identity.

### Endless Runway & Recommendation Engine (`com.velvet.app.recommendation` & `com.velvet.app.diagnostics`)
- **`RunwayController.kt` & `AutoplayRefillCoordinator.kt`**: Spotify-style off-main background Runway queue maintainer targeting `30` upcoming tracks (`REFILL_BELOW = 15`, `URGENT_BELOW = 6`, `MIN..MAX_BATCH = 15..20`), 8 trigger sources (playback start, track transition, queue edit, user-queue consume, Next coalescing via `NextTapCoalescer(150ms)`, prefetch watermark, connectivity recovery, autoplay toggle), `try/finally` (`NonCancellable`) + 8s watchdog invariant (preventing stuck `IN_FLIGHT`), commit-time identity revalidation, and `ExoSyncSerializer`.
- **`RollingCandidatePool.kt` & `DefaultRecommendationEngine`**: Holds up to `200` pre-verified overflow tracks, re-seeds via endless YouTube Music `/next` continuation tokens (`nextRadioContinuationData`) and anchor stepping when a seed runs dry, and caps owner-library tracks at `<= 25%` when external discovery is healthy.
- **`SessionVibe.kt` & `ArtistLanguageMap.kt`**: Locks active session language (`hi`/`en`/`pa`/`ta`/`te`/etc.), genre, mood, and era using learned on-device artist language maps (`<= 500` artists persisted cleanly) plus optional `"Allow mixed languages in Autoplay"` override (`SettingsScreen`).
- **`CandidateRanker.kt`, `DefaultSequenceOptimizer`, `TasteProfileBuilder.kt` & `ArtistStationPolicy.kt`**: Multi-horizon taste + 30+ day unplayed freshness boosts, 1-in-5 vibe-locked discovery novelty slots, deterministic `randomSeed` exploration, session early-skip artist/genre learning, and strict `ARTIST_RADIO` purity (`>= 60%` selected artist without anchor drift, `~25%` collaborators, `~15%` same-era/same-genre neighbours, `0` unrelated artists, scaling down cleanly when artist catalogs are short).
- **`QueueHealthMonitor.kt`, `DiagnosticsLogger.kt` & `QueueHealthScreen`**: Rolling 256 KB on-device diagnostics ring buffer, global crash capture (`filesDir/crash/`), and live `Queue & Runway Health` inspection inside Developer Options.

### Data & Extraction Layer (`com.velvet.app.data`)
- **`YouTubeMusicInnertube.kt` & `StreamResolver.kt`**: Resolves direct Opus/AAC streams, endless `/next` radio panel + chained `playlistPanelContinuation` pages with strict `MUSIC_VIDEO_TYPE_ATV` vs `OMV` classification, and ahead-of-time prefetching.
- **`LibraryIndex.kt` & `InstantTrackCatalog.kt`**: Off-main indexed library & studio catalog similarity lookup with local taste/freshness/skip awareness for sub-millisecond urgent fallback.
- **`Models.kt`**: Domain models including `Song`, `Playlist`, and `isPureMusic()` heuristic filter that blocks movie trailers, podcast interviews, and meme clips from playback.
- **`Downloads.kt`**: Offline media manager with background download worker and track persistence.
- **`Lyrics.kt`**: Synchronized and plain lyrics fetcher integrating LRCLIB API.
