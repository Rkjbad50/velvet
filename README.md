<div align="center">

# 🎵 VELVET

**Minimal Material 3 Audiophile Music Player for Android**  
*Pure original studio audio streams, zero ads, 100% local privacy.*

[![Latest Release](https://img.shields.io/github/v/release/Rkjbad50/velvet?color=A855F7&label=Release&logo=github)](https://github.com/Rkjbad50/velvet/releases/latest)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Rkjbad50/velvet/build.yml?branch=main&label=CI%20Build&logo=githubactions)](https://github.com/Rkjbad50/velvet/actions)
[![Android](https://img.shields.io/badge/Android-12.0%2B-3DDC84?logo=android)](https://github.com/Rkjbad50/velvet)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-EF4444)](LICENSE)

</div>

---

## ✨ What's New in v2.7.6

- **Strict Original Studio Music Audio Only**: Autoplay, Song Radio, Artist Radio, Similar Songs, Smart Shuffle, and Home shelves filter fail-closed to verified `- Topic` audio, official label studio tracks, and OST audio—rejecting official music videos, lyric videos, visualizers, live concerts, and unverified uploads.
- **Latest Extraction Engine (`NewPipeExtractor v0.27.1`)**: Upgraded stream resolution to `NewPipeExtractor v0.27.1` with VisionOS fast-path + Firefox ESR 153 headers and updated n-sig / client compatibility.
- **Local Personalized Recommendation Intelligence**: Multi-source discovery (`G1–G7 + G13`), music/genre/mood/tempo-first mode ranker with zero artist bias outside Artist Radio, multi-horizon taste learning (with safe `"Not interested"` and skip handling), and bounded beam sequencing to prevent back-to-back artist or album dumps.
- **Radio Evolution & Smart Home Filtering**: Persistent Song Radio sessions smoothly adapt from genuinely completed tracks (`45/25/20/10`) without drifting on skips, alongside studio-audio-verified `Quick picks`, `Speed Dial`, `Trending`, and `Made for you`.
- **Queue Safety & On-Device Inspector**: Concurrent `queueVersion`-safe Autoplay refills honoring `RepeatMode.ONE`, plus a local Recommendation Engine Inspector (`Developer options`) with zero query tracking leaks.

---

## 🚀 App Highlights

- **Pure Studio Sound**: Plays verified studio music audio cleanly without video clutter or distractions.
- **Audiophile Playback**: Gapless transitions, stream prefetching, and a 5-band graphic equalizer with Bass Boost & Virtualizer.
- **Synced Lyrics**: Time-synced and plain lyrics powered by LRCLIB.
- **100% Private & On-Device**: Zero ads, zero accounts, zero external telemetry—your library and taste profile stay on your phone.

---

## 💎 Features

- **Material 3 Expressive UI**: Pitch-black AMOLED & light themes, custom accent palettes, and selectable seekbar styles (`Minimal`, `Wide`, `DotMatrix`).
- **Smart Home & Radio**: Quick Picks, Speed Dial matrix, contextual mixes, Song Radio, Artist Radio, and standalone Similar Songs.
- **Precision Queue Controls**: 2-stage expandable queue sheet, smooth inertial scrolling, swipe-to-queue/remove, optional drag-to-reorder, and Smart Shuffle for user playlists.
- **Offline Playback & Backup**: Playlist/song downloads, persistent audio cache, and JSON library backups.

---

## 🛠️ Technologies

- **UI**: Kotlin & Jetpack Compose (Material 3)
- **Audio Engine**: AndroidX Media3 (ExoPlayer) + `MediaSessionService`
- **Source & Lyrics**: [NewPipeExtractor v0.27.1](https://github.com/TeamNewPipe/NewPipeExtractor) & [LRCLIB](https://lrclib.net)
- **Media & Data**: Coil (with Palette colors) & KotlinX Serialization

---

Download the latest APK from [**Releases**](https://github.com/Rkjbad50/velvet/releases/latest) • Licensed under [**GPL v3**](LICENSE)
