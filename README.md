<div align="center">

# 🎵 VELVET

**Next-Gen Material 3 Expressive Audiophile Music Player for Android**  
*Pure studio-grade music streams, zero ads, no account required.*

[![Latest Release](https://img.shields.io/github/v/release/Rkjbad50/velvet?color=A855F7&label=Release&logo=github)](https://github.com/Rkjbad50/velvet/releases/latest)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Rkjbad50/velvet/build.yml?branch=main&label=CI%20Build&logo=githubactions)](https://github.com/Rkjbad50/velvet/actions)
[![Android](https://img.shields.io/badge/Android-12.0%2B%20%28SDK%2031%E2%80%9336%29-3DDC84?logo=android)](https://github.com/Rkjbad50/velvet)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-EF4444)](LICENSE)
[![Google Play Protect](https://img.shields.io/badge/Play%20Protect-Verified-10B981?logo=googleplay)](https://github.com/Rkjbad50/velvet)
[![Developer: Narayan](https://img.shields.io/badge/Developer-Narayan-8B5CF6)](https://github.com/Rkjbad50)

<p align="center">
  <a href="#-whats-new-in-v275">What's New</a> •
  <a href="#-app-highlights">App Highlights</a> •
  <a href="#-key-features">Key Features</a> •
  <a href="#%EF%B8%8F-technologies--architecture">Tech Stack</a> •
  <a href="#-installation">Installation</a> •
  <a href="#-license">License</a>
</p>

</div>

---

## 🌟 What's New in v2.7.5

- 📻 **Music-First Song Radio Overhaul**:
  - **Acoustic & Style Alignment**: Tapping *"Start song radio"* immediately shifts recommendations and upcoming queue toward songs matching the exact **Genre, Mood, Tempo, Language/Script, and Era** of the seed track.
  - **Zero Artist Bias on Standalone Songs**: Artist identity receives zero positive similarity bonus on standalone song radio and searched songs (`w3 = 0.0`), preventing artist flooding while keeping artists spaced at least 4 tracks apart.
  - **Indicative Radio Playing Banner**: An animated floating pill banner (`"{SONG_TITLE}" Radio Playing`) drops down from the status bar with an active live equalizer visualizer to clearly indicate the session transition.
  - **Now Playing Header Tag**: Displays `"{SONG_TITLE} Radio"` in an elegant subtitle right below *"Now Playing"*.

- 📑 **2-Stage Ground-Anchored Queue Sheet (40% ➔ 90%)**:
  - **Dual Stage Gestures**: Opens smoothly to a 40% ergonomic peek on tap, and stretches fluidly to 90% screen height on drag, preserving status bar clearance.
  - **Buttery Smooth 120Hz Fling Physics**: Decoupled nested list scrolling ensures queued tracks glide seamlessly with native inertial momentum without accidental sheet closures during fast downward scrolling.
  - **Queue Drag-to-Reorder {BETA}**: Added a dedicated user toggle in Settings under Playback to enable or disable drag-handle track reordering.

- 🔍 **Real-Time Tokenized Library & Playlist Search**:
  - Real-time token matching across local playlists and individual tracks simultaneously, displaying the source playlist hint for every track match.
  - Single-line vertically centered search input with keyboard-safe bottom window insets.

- ⚙️ **Settings Symmetry & UI Refinement**:
  - Restored symmetry and alignment in the *About* section with clean title rendering and standard action button heights.

---

## 🚀 App Highlights

- **Pure Music, Zero Distractions**: Automatic heuristic filtering (`isPureMusic`) removes interviews, dialogue clips, and trailers so you only hear pure audio.
- **Uncompromised Audiophile Sound**: Ahead-of-time stream prefetching, gapless track transitions, and a 5-band graphic equalizer with UltraTune and Bass Boost.
- **Real-Time Karaoke Lyrics**: Synchronized time-stamped and plain lyrics fetched on-demand from the LRCLIB API.
- **100% Privacy & Local Storage**: No login, zero analytics, zero telemetry. All playlists and history reside securely encrypted on your device.

---

## 💎 Key Features

### 🎨 Material 3 Expressive Interface
- Extreme AMOLED pitch-black and clean light themes with dynamic accents (Velvet Purple, Emerald Green, Crimson Red, Ocean Blue, Sunset Orange).
- Selectable audiophile seekbar styles: **Minimal**, **Wide**, and **DotMatrix**.

### ⚡ 3×3 Speed Dial Matrix & Smart Home
- 9 instant-access square tiles on your Home screen for rapid, one-tap playback.
- Time-of-day contextual recents (Morning, Afternoon, Evening, Night).

### 🎚️ Pro Audio Suite
- 5-band EQ with custom presets, bass boost, 3D virtualizer, and loudness enhancer.

### 📥 Lossless Offline Playback & Smart Cache
- Download complete playlists or individual songs with lossless offline playback and a persistent 512 MB audio cache.

### 📑 Comprehensive Queue & Playlist Tools
- Multi-attribute playlist sorting (Title, Artist, Album, Duration, Recently Added).
- Spotify-style swipe gestures: Swipe Right to play next, Swipe Left to remove.

---

## 🛠️ Technologies & Architecture

```
VELVET UI (Jetpack Compose + Material 3) ──► MediaController ──► PlaybackService (ExoPlayer + MediaSession)
         │                                                            │
         └── Search, Artists, Albums ──► NewPipeExtractor ◄───────────┘ Audio Stream Resolution
```

- **UI & Design**: Jetpack Compose (BOM 2024.12.01) with Kotlin 2.1 & Material 3 Expressive
- **Media Engine**: AndroidX Media3 (ExoPlayer 1.5.1) with background `MediaSessionService`
- **Stream Extraction**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- **Image Pipeline**: Coil 2.7 with Palette color extraction
- **Lyrics Service**: [LRCLIB](https://lrclib.net) API
- **Persistence**: KotlinX Serialization with atomic file I/O and automatic backup

---

## 📱 Installation

1. Go to the [**Releases**](https://github.com/Rkjbad50/velvet/releases/latest) section of this repository.
2. Download the latest **`velvet.apk`**.
3. Install the APK on your Android 12+ device.

---

## 🔨 Building from Source

Requirements: **JDK 17**, **Android SDK Platform 35**, and **Gradle**.

```bash
git clone https://github.com/Rkjbad50/velvet.git
cd velvet
gradle testReleaseUnitTest assembleRelease
```

The signed release APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

---

## 📄 License

VELVET is free and open-source software licensed under the [GNU General Public License v3.0](LICENSE).
