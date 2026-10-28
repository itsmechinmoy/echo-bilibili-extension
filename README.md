# Bilibili Music Extension for Echo

A high-performance, full-featured music and audio extension for [Echo](https://github.com/brahmkshatriya/echo), streaming high-fidelity audio, music videos, collections, and synchronized lyrics directly from Bilibili.

---

## ✨ Features

- **High-Fidelity Audio Streams**:
  - **Hi-Res Lossless FLAC** (up to 24-bit / 48kHz+ when available)
  - **High Quality (192kbps / 320kbps)** AAC/MP4
  - **Standard (132kbps)** & **Data Saver (64kbps)** fallback
  - **Dolby Atmos / Panoramic Audio** support
- **Music Video Playback**: Toggle between audio-only mix or full Music Video playback with progressive DASH streams.
- **Comprehensive Search**: Fast search across Bilibili's entire video catalog with keyword sanitization and duration parsing.
- **Home Feed & Charts**:
  - **Top Music Ranking**: Official Bilibili Music partition ranking chart.
  - **Trending & Popular**: Real-time trending videos on Bilibili.
- **Multi-Part Video (P1/P2/...) Support**: Multi-song compilation videos and anime OST collections are automatically recognized and loaded as albums.
- **Artist / UP Loader Pages**: View uploader profile, avatar, biography, and browse their entire video discography.
- **Favorite Collections & Library**: Log in to access your personal Bilibili favorite folders directly inside Echo's Library tab.
- **Synchronized CC Subtitle Lyrics**: Automatically parses Bilibili subtitle streams into timed, synchronized lyrics with millisecond accuracy.
- **Radio & Smart Recommendations**: Discover related music powered by Bilibili's related archives recommendation engine.
- **Dual Login Methods**:
  - **In-App WebView**: Sign in with QR code or credentials.
  - **SESSDATA Token**: Quick manual entry for users who prefer pasting their session cookie.

---

## 🛠️ Build & Installation

### Option 1: GitHub Actions (Recommended)
1. Fork or push this repository to GitHub.
2. Add the following repository secrets under **Settings > Secrets and variables > Actions**:
   - `KEYSTORE_B64`: Base64-encoded Android signing keystore (`.jks`).
   - `PASSWORD`: Keystore and key password.
3. Every push to `main` automatically builds, signs, and releases a `.eapk` package ready to install in Echo.

### Option 2: Local Build
Ensure you have JDK 21 and the Android SDK installed:

```bash
# Clone the repository
git clone https://github.com/itsmechinmoy/echo-bilibili-extension.git
cd echo-bilibili-extension

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing properties)
./gradlew assembleRelease
```

The compiled extension package will be located at:
`app/build/outputs/apk/release/app-release.apk` (or `app-debug.apk`).

---

## 📄 License
This project is licensed under the Apache 2.0 License.
