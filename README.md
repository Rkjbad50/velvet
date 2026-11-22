<div align="center">

# 🎵 VELVET

**Next-Gen Material 3 Expressive Ad-Free Audiophile Music Player for Android**  
*Pure music streams, zero ads, no account required.*

[![Latest Release](https://img.shields.io/github/v/release/Rkjbad50/velvet?color=A855F7&label=Release&logo=github)](https://github.com/Rkjbad50/velvet/releases/latest)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Rkjbad50/velvet/build.yml?branch=main&label=CI%20Build&logo=githubactions)](https://github.com/Rkjbad50/velvet/actions)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20%28SDK%2026%E2%80%9335%29-3DDC84?logo=android)](https://github.com/Rkjbad50/velvet)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-EF4444)](LICENSE)
[![Google Play Protect](https://img.shields.io/badge/Play%20Protect-Verified-10B981?logo=googleplay)](https://github.com/Rkjbad50/velvet)
[![Developer: Rkjbad50](https://img.shields.io/badge/Developer-Rkjbad50-8B5CF6)](https://github.com/Rkjbad50)

<p align="center">
  <a href="#-whats-new-in-v271">What's New</a> •
  <a href="#-key-features">Key Features</a> •
  <a href="#-installation">Installation</a> •
  <a href="#%EF%B8%8F-architecture--tech-stack">Architecture</a> •
  <a href="#-google-play-protect--security">Security</a> •
  <a href="#-building-from-source">Building</a> •
  <a href="#-license">License</a>
</p>

</div>

---

## 🌟 What's New in v2.7.1

- 🔍 **In-Playlist Track Search**:
  - Instantly search and filter tracks inside saved playlists by song title or artist.
  - Sleek top header toggle button with responsive search panel.
  - Zero disruption to playlist sorting, drag-reorder, duplicate, delete, or undo toast actions.

- 🌊 **Fluid Minimal Wavy Pulse Visualizer Redesign**:
  - Re-engineered with smooth, undulating traveling harmonic sine waves with radiant center ambient bloom.
  - Replaced rigid vertical bars with continuous fluid acoustic crests and luminous micro-filaments.
  - Accented with toned-down theme colors for a refined, modern audiophile aesthetic.

- ⚡ **Real-Time Streaming Resolution Badge & Jitter Elimination**:
  - Rapid 150ms resolution detection reflecting exact streaming bitrate (`OPUS 160K`, `OPUS 50K`, `OFFLINE`).
  - Anchored mathematically to true screen center (`Modifier.align(Alignment.Center)`)—100% elimination of horizontal position jitter during track transitions.
  - Tap to toggle audio streaming quality in real time.

- ❤️ **Interactive Notification Panel Like Button**:
  - Heart icon (`ic_heart_filled` / `ic_heart_outline`) added directly to the system media notification mini player.
  - Tap to like/unlike songs instantly from the lockscreen or notification tray without opening the app.

- ⏭️ **Continuous Notification Controls**:
  - MediaSession explicitly grants `SEEK_TO_NEXT` and `SEEK_TO_PREVIOUS` commands, ensuring Next and Previous buttons remain visible and functional even on first standalone track playback.

- 💫 **App-Wide Tactile Button Responsiveness**:
  - Spring-scale press bounce (`0.88f` bounce) and crisp haptics across all buttons app-wide.
  - Playlist Play/Pause/Shuffle buttons reflect live playback state with active Pause icon when playing.

---

## 🌟 What's New in v2.7

- 🎚️ **Poweramp Equalizer-Style Beat-Synchronized Pulse Wave Visualizer**:
  - Dynamically animated multi-frequency waveform with authentic kick-drum bass thump on low frequencies, melodic mid-range ripple, and crisp treble shimmer.
  - Symmetrical Gaussian diamond/bell envelope radiating outward with a warm luminous center ambient bloom.
  - Positioned seamlessly between Cover Art and Song Title for an ultra-clean, expressive layout.
  - Fully toggleable in Settings (**Settings > Pulse wave visualizer**).

- 🎨 **All-New Modern Now Playing Experience**:
  - **Dynamic Cover Art Palette Breathing Gradient**: Top-to-bottom vertical ambient gradient driven by album art palette that rhythmically pulses during playback and gracefully pauses when stopped.
  - **Mathematically Centered Header**: "Now Playing" title and theme accent indicator are dead-center horizontally on all screen sizes.
  - **Fuller & Seamlessly Blended Artwork**: Expanded cover art with soft rounded corners (`24.dp`), ambient elevation bloom, bottom gradient fade, and edge vignette blending into the dark backdrop.
  - **Streaming Resolution Indicator**: Clean plain-text resolution tag (`OPUS 161K`) elegantly centered between Seekbar timestamps—zero cluttered boxes or borders.
  - **Tactile Spring-Click Play/Pause**: Snappy `0.86f` spring scale press animation with bouncy release, haptic feedback, and dynamic high-contrast icon styling.
  - **100% Theme Accent Integration**: Entire interface dynamically syncs with system accent color (Velvet Purple, Emerald, Crimson, Ocean, Sunset, etc.)—zero hardcoded greens.

- 🧠 **Autoplay Recommendation Engine V2**:
  - Multi-tier machine intelligence algorithm: Genre matching > BPM / Tempo alignment > Mood detection > Historical listening affinity > Era / Timeline coherence > Artist categorization.
  - Suppressed redundant "Recommended" tags in normal autoplay queue; reserved strictly for Smart Shuffle extra recommendations.

- 📜 **Dedicated Listening History**:
  - Decoupled Home Header Clock button to open dedicated `HistoryScreen` displaying individual played songs, keeping Home Recents focused on Playlists, Albums, and Artists.

- 📑 **Spotify-Grade Smooth Queue Swipes**:
  - Full-length fluid gestures: Swipe Right to play next, Swipe Left to dismiss.
  - Single-action long-press drag-and-drop track reordering with dedicated 3-dot track options menu.
  - Modal queue bottom sheet opens at ergonomic 60% height and expands to full screen on drag.

---

## 🚀 Key Features

### 🎨 Material 3 Expressive Interface
- 100% solid & opaque surfaces with extreme AMOLED pitch black theme and crisp light theme.
- Dynamic system accent themes: Velvet Purple, Emerald Green, Crimson Red, Ocean Blue, Sunset Orange, and more.
- Dynamic seekbar styling with three selectable audiophile aesthetics: **Minimal**, **Wide**, and **DotMatrix**.

### ⚡ 3×3 Speed Dial Matrix & Smart Home
- 9 instant-access rounded square tiles right on your home screen for rapid, one-tap playback without scrolling.
- Context recents with time-of-day listening history (Morning, Afternoon, Evening, Night).

### 🎵 Pure Studio-Grade Music Engine
- Built-in intelligent heuristic filter (`isPureMusic`) that automatically purges movie dialogue clips, podcast interviews, trailers, and memes—ensuring only studio-quality audio tracks play.
- Ahead-of-time stream prefetching and direct gapless playback transitions with zero volume dips.

### 🎤 Synchronized Karaoke Lyrics
- Real-time karaoke-style time-synced and plain lyrics powered by the LRCLIB API.

### 🎚️ Pro Audio & Equalizer
- 5-band graphic equalizer with customizable presets, UltraTune one-tap enhancement, bass boost, virtualizer 3D surround sound, and loudness enhancer.

### 📥 Lossless Offline Downloads & Smart Cache
- Save individual songs or complete playlists to your device with lossless playback and a persistent 512 MB smart streaming cache.

### 📑 Advanced Playlist Management
- Multi-attribute sorting (Title Ascending/Descending, Artist, Album, Recently Added, Duration).
- Easy drag reordering and instant removal with undo toast.

### 🔒 Privacy First & Offline Autonomy
- No account required, zero tracking or telemetry.
- Entire library encrypted and stored locally on your device with automatic backup to your storage.

---

## 📱 Installation

1. Go to the [**Releases**](https://github.com/Rkjbad50/velvet/releases/latest) section of this repository.
2. Download the latest **`velvet.apk`**.
3. Open the downloaded file on your Android device and tap **Install**.
4. Enjoy unlimited, ad-free studio-grade music streaming!

---

## 🔒 Google Play Protect & Security

VELVET is built strictly in accordance with Google Play Protect standards:
- **Zero Telemetry / No Tracking**: No analytics SDKs, trackers, or hidden background services.
- **Modern Target SDK**: Built targeting **Android 15 (API 35)** with modern AndroidX Media3 architecture.
- **Strict Permission Scoping**: Requests only documented, necessary permissions for media playback (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `MODIFY_AUDIO_SETTINGS`).
- **No Dynamic Code Loading**: Zero use of `DexClassLoader` or unverified reflection.
- **Unified Permanent Release Keystore**: Signed with a standard RSA-2048 certificate (`keystore/velvet.jks`), guaranteeing consistent certificate fingerprints and conflict-free sideload updates.

---

## 🛠️ Architecture & Tech Stack

```
VELVET UI (Jetpack Compose + Material 3) ──► MediaController ──► PlaybackService (ExoPlayer + MediaSession)
         │                                                            │
         └── search, artists, albums ──► NewPipeExtractor ◄───────────┘ audio stream lookup
```

- **UI Framework**: Jetpack Compose (BOM 2024.12.01) with Kotlin 2.1
- **Audio Service**: AndroidX Media3 (ExoPlayer 1.5.1) with background `MediaSessionService`
- **Stream Extraction**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- **Image Pipeline**: Coil 2.7 with Palette extraction
- **Lyrics Engine**: [LRCLIB](https://lrclib.net) API
- **Local Storage**: KotlinX Serialization with atomic file transactions and auto-backup

---

## 🔨 Building from Source

Requirements: JDK 17, Android SDK Platform 35, and Gradle.

```bash
# Clone the repository
git clone https://github.com/Rkjbad50/velvet.git
cd velvet

# Compile release APK
gradle testReleaseUnitTest assembleRelease
```

The compiled signed binary will be located at `app/build/outputs/apk/release/app-release.apk`.

---

## 📄 License & Disclaimer

VELVET is free software licensed under the [GNU General Public License v3.0](LICENSE).

*Disclaimer: VELVET is an independent, open-source personal project not affiliated with or endorsed by Google or YouTube.*
