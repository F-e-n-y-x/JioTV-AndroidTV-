# JioTV Go TV - Developer Guide & Architecture

This document provides a comprehensive overview of the application's architecture, authentication flow, stream extraction (plugin) logic, and UI structure. It is intended to help developers (and AI assistants) easily maintain and update the application if JioTV's APIs or IPTV mechanisms change.

## 1. Project Structure

The project is structured around the modern Android recommended architecture using Kotlin, Jetpack Compose, and Material 3 for Android TV.

- `com.example.jiotvgotv.MainActivity`: The main entry point. Sets up the Compose UI surface.
- `com.example.jiotvgotv.Navigation.kt`: Manages screens (Login, Main/Home, Player, Settings) using `androidx.navigation3`.
- `com.example.jiotvgotv.ui`: Contains all Compose UI screens.
- `com.example.jiotvgotv.data`: Contains data classes, API clients, Settings manager, and Plugin logic.

## 2. Authentication & Login Flow

The app authenticates against the Jio API via SMS/OTP login.

- **Files:** `JioApiClient.kt`, `LoginScreen.kt`
- **Mechanism:**
  1. User enters their Jio Mobile Number.
  2. `JioApiClient.generateOTP()` sends a POST request to `https://jiotvapi.media.jio.com/bango/v6/v2/generateotp`. It generates unique device and SSO IDs.
  3. User enters the received OTP.
  4. `JioApiClient.validateOTP()` sends the OTP and number to `https://jiotvapi.media.jio.com/bango/v6/v2/validateotp`.
  5. The API returns an `ssotoken` and `uniqueId`.
  6. `SettingsManager.kt` securely stores these credentials in Android DataStore.

> [!WARNING]
> If JioTV updates their login endpoints or headers in the future, check the Kodi plugin's updated Python files, map the new endpoint URLs, and update the HTTP headers in `JioApiClient.kt`.

## 3. Stream Extraction (The Plugin Logic)

To play a channel, the app must convert a channel number into a playable M3U8/MPD stream URL and extract necessary DRM keys.

- **Files:** `JioApiClient.getStreamUrl()`
- **Mechanism:**
  1. The app requests `https://jiotvapi.media.jio.com/playback/v1/geturl?channel_id={channelNumber}&stream_type=Seek` using the user's `ssotoken`, `uniqueId`, `crmid`, and `deviceId`.
  2. The JSON response contains a bitrates array or a direct URL.
  3. The app parses the JSON and forms the final stream URL.
  4. If the stream is DRM protected (`isMpd = true`), the response includes DRM license URLs and headers. These are bundled into a `StreamData` object.
  5. `TvPlayerScreen.kt` passes these DRM parameters to AndroidX Media3 ExoPlayer.

> [!TIP]
> **Updating the Plugin:** If the stream extraction fails, or if you have a newer Kodi plugin zip file, extract it and look at `plugin.video.jiotv/resources/lib/utils.py` or `api.py`. Compare the headers, payload structures, and endpoint URLs. Update `JioApiClient.getStreamUrl()` to match the Python implementation's logic.

## 4. UI Components

### Main Screen (`MainScreen.kt`)
- Uses `MainViewModel` to manage state.
- Features a two-pane layout: a category sidebar on the left and a grid/list on the right.
- **EPG Mode:** If enabled in settings, uses a custom `LazyColumn` with horizontal `LazyRow` timelines for electronic program guides.
- **Auto-Play:** On startup, `Navigation.kt` intercepts the `allChannels` state and if `autoplayLastChannel` is enabled, automatically redirects to `TvPlayerScreen`.

### Player Screen (`TvPlayerScreen.kt`)
- Uses `ExoPlayer` for playback.
- Has a custom overlay that auto-hides after 5 seconds of inactivity.
- **Right Arrow:** Opens `Player Settings` (quality, language, view mode).
- **Left/Right/Up/Down:** Navigate channels seamlessly.
- Continuously saves the `LAST_CHANNEL_ID` to `SettingsManager` for the Autoplay feature.

## 5. Electronic Program Guide (EPG)

- **Files:** `EpgRepository.kt`, `MainViewModel.kt`
- **Mechanism:**
  1. The app downloads an XMLTV gzip file from the URL specified in Settings (default: `https://avkb.short.gy/epg.xml.gz`).
  2. The file is unzipped and parsed chunk-by-chunk to prevent OutOfMemory errors.
  3. Parsed programs are mapped to `Channel.id`.
  4. To support missing channels, `MainViewModel` fetches native EPG data dynamically using `https://jiotv.data.cdn.jio.com/apis/v1.3/getepg/get`.

## 6. Settings Management

All persistent data is managed by `SettingsManager.kt` using Jetpack DataStore Preferences.
- Preferences include: Auth Tokens, EPG URL, Video Quality, Audio Language, Player Resize Mode, Autoplay flags, and Favorite Channels.
- Stored asynchronously and accessed as Kotlin `Flows`.
