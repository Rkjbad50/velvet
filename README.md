<div align="center">

# VELVET

**Next-Gen Material 3 Expressive Ad-Free Music Player for Android**  
*Pure music streams, zero ads, no account required.*

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-6FF3FF?logo=android)](https://github.com/Rkjbad50/velvet)
[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-FF3FB4)](LICENSE)
[![Author: Rkjbad50](https://img.shields.io/badge/Developer-Rkjbad50-B066FE)](https://github.com/Rkjbad50)

</div>

---

## 🌟 Highlights & Features

- 🎨 **Material 3 Expressive Design**: 100% solid & opaque surfaces, extreme AMOLED pitch black theme, crisp light theme, and customizable accent colors (Velvet Purple, Emerald, Cyan, Rose, Amber, Blue).
- ⚡ **3×3 Speed Dial Matrix**: 9 instant-access rounded square tiles right on your home screen for rapid, one-tap playback without scrolling.
- 🎵 **Pure Music Engine**: Built-in intelligent heuristic filter (`isPureMusic`) that automatically purges movie dialogue clips, podcast interviews, trailers, and memes—ensuring only studio-quality audio tracks play.
- 🎤 **Synchronized Lyrics**: Real-time karaoke-style time-synced and plain lyrics powered by LRCLIB.
- 🎚️ **Pro Audio & Equalizer**: 5-band graphic equalizer with presets, UltraTune one-tap enhancement, bass boost, virtualizer 3D surround sound, and loudness enhancer.
- 📥 **Offline Downloads & Cache**: Save individual songs or complete playlists to your device with lossless playback and a persistent 512 MB smart streaming cache.
- 📑 **Playlist Management**: Easy reordering, instant removal with 2-second undo toast, and dynamic multi-attribute sorting (A-Z, artist, duration).
- 🔀 **Smart Queue Control**: Swipe right on any queued song to immediately play next, and lock/unlock queue reordering.
- 🔒 **Privacy First**: No account required, zero telemetry or tracking, and your entire library stays encrypted locally on your phone.

---

## 📱 Installation

1. Go to the [**Releases**](https://github.com/Rkjbad50/velvet/releases) section of this repository.
2. Download the latest **`velvet.apk`**.
3. Open the downloaded file on your Android device and tap **Install** (allow installation from unknown sources if prompted).

---

## 🛠️ Architecture & Tech Stack

```
VELVET UI (Jetpack Compose + Material 3) ──► MediaController ──► PlaybackService (ExoPlayer + MediaSession)
         │                                                            │
         └── search, artists, albums ──► NewPipeExtractor ◄───────────┘ audio stream lookup
```

- **Framework**: Jetpack Compose (BOM 2024.12.01) with Kotlin 2.1
- **Audio Service**: AndroidX Media3 (ExoPlayer 1.5.1) with background `MediaSessionService`
- **Stream Extraction**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- **Image Pipeline**: Coil 2.7
- **Lyrics Engine**: [LRCLIB](https://lrclib.net) API

---

## 🔨 Building from Source

Requirements: JDK 17, Android SDK Platform 35, and Gradle.

```bash
# Clone the repository
git clone https://github.com/Rkjbad50/velvet.git
cd velvet

# Compile release APK
gradle assembleRelease
```

The compiled binary will be located at `app/build/outputs/apk/release/app-release-unsigned.apk`.

---

## 📄 License & Disclaimer

VELVET is free software licensed under the [GNU General Public License v3.0](LICENSE).

*Disclaimer: VELVET is an independent, open-source personal project not affiliated with or endorsed by Google or YouTube.*
