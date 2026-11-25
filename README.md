<div align="center">

# VELVET

**Minimal Material 3 Audiophile Music Player for Android**  
*Pure original studio audio streams, zero ads, 100% local privacy.*

[![Latest Release](https://img.shields.io/github/v/release/Rkjbad50/velvet?color=A855F7&label=Release&logo=github)](https://github.com/Rkjbad50/velvet/releases/latest)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Rkjbad50/velvet/build.yml?branch=main&label=CI%20Build&logo=githubactions)](https://github.com/Rkjbad50/velvet/actions)
[![Android](https://img.shields.io/badge/Android-12.0%2B-3DDC84?logo=android)](https://github.com/Rkjbad50/velvet)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-EF4444)](LICENSE)

</div>

---

## Highlights in v1.1

- **First-Time Personalization Survey**: 3-step onboarding (Languages, Artist Picker with verified artist profiles, and Genres) that tailors Trending Now, Speed Dial, Quick Picks, Made for You, and More for You from launch.
- **Strict Original Studio Audio Only**: Autoplay, Song Radio, Artist Radio, Similar Songs, Smart Shuffle, and Home shelves filter fail-closed to verified studio audio (`- Topic` releases, YT Music `ATV` catalog art tracks, and OST/label studio audio)—never music videos or unverified uploads.
- **Instant Sub-Millisecond Queue Autoplay & Resilient Live Radio**: Hybrid in-memory `InstantTrackCatalog` seeding paired with YouTube Music InnerTube (`RDAMVM` + live `FEmusic_charts` / `FEmusic_explore`) and `NewPipeExtractor v0.27.1` so queues populate instantaneously without waiting or stalling at track end.
- **Fluid 120Hz Origin Animations**: GPU-driven `graphicsLayer` transitions across route overlays, Now Playing minimize/expand, Queue sheet drag/dismiss, dialogs, and bottom sheets for continuous frame-accurate opening and closing.
- **Persistent Shuffle State & First-Time Toasts**: Preserves Shuffle / Smart Shuffle across app restarts when playing playlists without repetitive toast popups.

---

## Core Features

- **Audiophile Playback**: AndroidX Media3 (ExoPlayer) gapless audio, live resolution readout, and a 5-band equalizer with Bass Boost & Virtualizer.
- **Home & Discovery**: Live regional YouTube Music charts (`Trending now`), 9-cell adaptive `Speed Dial`, `Recently played`, `Quick picks`, `Made for you`, and dynamically rotating `More for you`.
- **Precision Queue Controls**: Expandable peek/full queue sheet, swipe-to-queue/remove, optional drag-to-reorder, queue lock, and user-playlist Smart Shuffle.
- **Synced Lyrics & Offline Support**: Synchronized/plain lyrics via [LRCLIB](https://lrclib.net), track/playlist downloads, and full JSON library backup/restore.
- **100% On-Device Privacy**: No accounts, ads, or external tracking; your taste profile and listening history stay locally on your device.

---

## Stack

- **UI**: Kotlin & Jetpack Compose (Material 3)
- **Audio**: AndroidX Media3 (`ExoPlayer` + `MediaSessionService`)
- **Discovery & Extraction**: [NewPipeExtractor v0.27.1](https://github.com/TeamNewPipe/NewPipeExtractor) + native YT Music InnerTube + [LRCLIB](https://lrclib.net)
- **Imaging & Persistence**: Coil (Palette dynamic theming) & KotlinX Serialization

---

Download the latest APK from [**Releases**](https://github.com/Rkjbad50/velvet/releases/latest) • Licensed under [**GPL v3**](LICENSE)
