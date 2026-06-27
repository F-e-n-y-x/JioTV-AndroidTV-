<div align="center">

# 📺 JTV — Live TV for Android TV

**A fast, lightweight, no-nonsense Live TV client built for Android TV & TV boxes.**

![Platform](https://img.shields.io/badge/Platform-Android%20TV-3DDC84?logo=android&logoColor=white)
![Android](https://img.shields.io/badge/Android-7.0%20%E2%86%92%2016-blue?logo=android&logoColor=white)
[![Latest Release](https://img.shields.io/github/v/release/F-e-n-y-x/JioTV-AndroidTV-?label=Download&color=success)](https://github.com/F-e-n-y-x/JioTV-AndroidTV-/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/F-e-n-y-x/JioTV-AndroidTV-/total?color=orange)](https://github.com/F-e-n-y-x/JioTV-AndroidTV-/releases)

<a href="https://github.com/F-e-n-y-x/JioTV-AndroidTV-/releases/latest">
  <img src="https://img.shields.io/badge/⬇️%20Download%20Latest%20APK-1f6feb?style=for-the-badge" alt="Download latest APK" />
</a>

</div>

---

## ✨ Highlights

- 🎬 **Plays DRM channels** (Star, Sony, Zee, Colors…) with the stream token refreshed in the background, so premium channels don't cut out mid-show.
- ⚡ **Instant startup** — the channel list loads from cache immediately, then refreshes quietly in the background.
- 🛡️ **Built for weak hardware** — hardware decoding, tuned buffers, and smart error recovery keep playback smooth on low-end TVs.
- 🗣️ **Voice Boost** — a built-in dialogue enhancer that lifts speech and lowers background music/effects (great for TVs with poor built-in audio).
- 🎚️ **Real controls** — pick the actual audio track/language, video quality (up to 1080p), aspect ratio, playback buffer, and a sleep timer.
- 📅 **EPG** — optional Electronic Program Guide with a timeline view.
- 🖥️ **Modern TV UI** — Jetpack Compose for TV, on-screen numpad login, and smooth D-pad navigation.

<div align="center">
  <img src="screenshot/ui_screenshot_1.png" width="32%" alt="Home" />
  <img src="screenshot/ui_screenshot_2.png" width="32%" alt="Channels" />
  <img src="screenshot/ui_screenshot_3.png" width="32%" alt="Player" />
  <img src="screenshot/ui_screenshot_4.png" width="32%" alt="EPG" />
  <img src="screenshot/ui_screenshot_5.png" width="32%" alt="Settings" />
  <img src="screenshot/ui_screenshot_6.png" width="32%" alt="Player settings" />
</div>

---

## 📥 Install

1. **Download** the latest `JTV-vX.X.X.apk` from the [**Releases page**](https://github.com/F-e-n-y-x/JioTV-AndroidTV-/releases/latest).
2. **Sideload** it onto your Android TV with ADB:

   ```bash
   adb connect <YOUR_TV_IP>:5555
   adb install -r JTV-vX.X.X.apk
   ```

   > 💡 Don't have ADB set up? You can also copy the APK to a USB drive and install it with a file-manager app like **"File Commander"** or **"X-plore"** on your TV.

3. **Log in** with your Jio mobile number + OTP using the on-screen numpad.

> ⚠️ Updating from an older build that was signed with a different key? Uninstall first: `adb uninstall com.fenyx.jtv`

---

## 🎮 Remote Controls

| Button | In the channel grid | While watching |
|---|---|---|
| **D-pad ↑ / ↓** | Move | Change channel |
| **CH+ / CH−** | — | Change channel |
| **D-pad ←** | — | Open channel list / categories |
| **D-pad →** | — | Open the player side panel (audio, quality, sleep timer…) |
| **OK / Center** | Open channel | Show/hide channel info |
| **0–9** | — | Jump to a channel number |
| **Back** | Exit app | Close overlay / exit player |

---

## 🔊 Voice Boost (Dialogue Enhancer)

Many channels mix dialogue too quietly under loud music and effects — and most TVs have no fix for it. JTV adds one.

Open the **player side panel** (D-pad **→**) → **Voice Boost** and cycle through **Off → Low → Medium → High → Max**.

It uses center-channel processing to **lift the voice and lower the background** while **keeping the bass full** (so it never sounds thin). **Medium** or **High** is the sweet spot for most content. Pair it with **Auto Volume** to even out loudness between channels.

---

## ⚙️ Settings Overview

| Setting | What it does |
|---|---|
| **EPG Mode** | Switch the home screen to a program-guide timeline |
| **EPG Source URL** | Choose your own XMLTV guide source |
| **Autoplay Last Channel** | Jump straight into your last channel on launch |
| **Default Quality** | Auto / 1080p / 720p / 480p |
| **Playback Buffer** | Data Saver → Max (more buffer = fewer interruptions) |
| **Player View Mode** | Fit, Fill, Zoom, Stretch |
| **Default Audio Language** | Preferred language for multi-audio channels |
| **Hardware Decoder** | Keep **on** for low-end TVs |
| **Tunneling** | Keep **off** unless you have audio-sync issues (can cause black screens on some TVs) |

---

## 🛠️ Building from Source

**Requirements:** Android Studio (or the command-line SDK) with JDK 17+.

```bash
git clone https://github.com/F-e-n-y-x/JioTV-AndroidTV-.git
cd JioTV-AndroidTV-
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

<details>
<summary><b>Release builds (signed)</b></summary>

Create a `keystore.properties` file in the project root (it's gitignored):

```properties
storeFile=jtv-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Generate a keystore once:

```bash
keytool -genkeypair -v -keystore jtv-release.keystore -alias jtv -keyalg RSA -keysize 2048 -validity 10000
```

Then build the signed, minified release (~3–4 MB):

```bash
./gradlew assembleRelease
```

</details>

---

## 🧱 Tech Stack

| Area | Technology |
|---|---|
| **UI** | Jetpack Compose for TV (Material 3) |
| **Media** | AndroidX Media3 / ExoPlayer (HLS + DASH/Widevine) |
| **Architecture** | MVVM · Kotlin Coroutines · StateFlow |
| **Navigation** | AndroidX Navigation 3 |
| **Storage** | DataStore Preferences + on-disk cache |
| **Images** | Coil |

---

## 📝 Changelog

### v1.3.2
- Reworked **Voice Boost** into a 5-level dialogue enhancer (center-channel processing) with **bass preserved** and a presence boost for clarity — no more thin/hollow sound. The old "Reduce Background" toggle is merged in.

### v1.3
- In-player **audio controls** (Voice Boost, Auto Volume), a **real audio-track/language selector**, the current channel is kept when opening Settings, and the player side panel now scrolls.

### v1.2
- **DRM channels no longer cut out every ~2 minutes** (transparent stream-token refresh), fixed release-build crashes, off-by-default tunneling, smoother buffering, instant cached startup, app-icon fix, and `targetSdk 36`.

See the [Releases page](https://github.com/F-e-n-y-x/JioTV-AndroidTV-/releases) for full notes and downloads.

---

## 🙏 Credits

Built with reference to and inspiration from:

- [dineshintry/plugin.kodi.jiotv](https://github.com/dineshintry/plugin.kodi.jiotv)
- [JioTV-Go/jiotv_go](https://github.com/JioTV-Go/jiotv_go)

---

## ⚖️ Disclaimer

This is an independent, third-party Android TV client made for **educational purposes**. It is **not** affiliated with, authorized, maintained, or endorsed by JioTV or Reliance Jio Infocomm Ltd. You are responsible for how you use it, and you need a valid Jio account to log in. Use at your own risk.
