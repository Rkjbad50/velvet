# VELVET Architecture Guide

A complete technical map of **VELVET** for Android: architecture, audio pipeline, UI layer, caching, and offline support.

Kotlin package root: `com.velvet.app` (`app/src/main/java/com/velvet/app/`).

---

## 1. Architecture Overview

VELVET is a modern, single-activity Android application built with Kotlin, Jetpack Compose, and Material 3 Expressive theming. It interfaces with YouTube Music to provide pure music streams without ads, trackers, or account dependencies.

| Component | Technology |
|---|---|
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Language & Runtime | Kotlin 2.1.0, Coroutines, Flow |
| UI Framework | Jetpack Compose (BOM 2024.12.01), Material 3 Expressive |
| Media Engine | AndroidX Media3 1.5.1 (ExoPlayer, MediaSession, CacheDataSource) |
| Audio Extraction | NewPipeExtractor (v0.26.5) |
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
- **`PlaybackService.kt`**: Foreground `MediaSessionService` coordinating ExoPlayer, audio focus, lock-screen controls, and system media notifications. Track transitions are direct and gapless-friendly via ahead-of-time prefetching (`StreamResolver.prefetch`); crossfade automix has been completely removed.
- **`AudioFx.kt`**: Audio processing engine providing 5-band graphic equalizer, Bass Boost, Virtualizer (3D audio stage), and LoudnessEnhancer.
- **`SleepTimer.kt`**: Audio countdown timer with graceful volume fade-out.

### Data & Extraction Layer (`com.velvet.app.data`)
- **`StreamResolver.kt`**: Resolves direct Opus/AAC streams, handles YouTube Music audio endpoints, and applies smart caching.
- **`Models.kt`**: Domain models including `Song`, `Playlist`, and `isPureMusic()` heuristic filter that blocks movie trailers, podcast interviews, and meme clips from playback.
- **`Downloads.kt`**: Offline media manager with background download worker and track persistence.
- **`Lyrics.kt`**: Synchronized and plain lyrics fetcher integrating LRCLIB API.
