# JioTV Go TV - Developer Guide & Architecture

This document provides a comprehensive overview of the application's architecture, authentication flow, stream extraction (plugin) logic, and UI structure. It is intended to help developers (and AI assistants) easily maintain and update the application if JioTV's APIs or IPTV mechanisms change.

## 1. Project Structure

The project is structured around the modern Android recommended architecture using Kotlin, Jetpack Compose, and Material 3 for Android TV. The base package is `com.fenyx.jtv`.

- `com.fenyx.jtv.MainActivity`: The main entry point. Sets up the Compose UI surface.
- `com.fenyx.jtv.Navigation.kt`: Manages screens (Login, Main/Home, Player, Settings) using `androidx.navigation3`.
- `com.fenyx.jtv.ui`: Contains all Compose UI screens.
- `com.fenyx.jtv.data`: Contains data classes, API clients, Settings manager, and Plugin logic.

## 2. Authentication & Login Flow

The app authenticates against the Jio API via SMS/OTP login.

- **Files:** `JioApiClient.kt`, `LoginScreen.kt`
- **Mechanism:**
  1. User enters their Jio Mobile Number.
  2. `JioApiClient.sendOTP()` sends a POST request to `https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/send` with the base64-encoded `+91`-prefixed number.
  3. User enters the received OTP.
  4. `JioApiClient.verifyOTP()` POSTs the number + OTP + device info to `https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/verify`.
  5. The API returns `ssoToken`, `authToken`, and session attributes (`subscriberId`/crmid, `unique`, `uid`).
  6. `SettingsManager.kt` stores these credentials in Android DataStore.

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
- Preferences include: Auth Tokens, EPG URL, Video Quality, Audio Language, Player Resize Mode, Autoplay flags, Favorite Channels, Tunneling, and Playback Buffer (seconds).
- Stored asynchronously and accessed as Kotlin `Flows`.

## 7. Playback Resilience & Token Refresh

Jio's live stream URLs carry a short-lived Akamai token (`__hdnea__`, `exp - st ≈ 120s`). Both the
manifest URL **and** each segment are authorized by this token — supplied as a URL query parameter
*and* as a `Cookie: __hdnea__=...` header. When it expires the CDN returns HTTP 403/404, which would
otherwise surface as a fatal player error and a full reload (a visible "buffering/black-screen").

The player keeps playback alive transparently (`TvPlayerScreen.kt`):

1. **Token holder** — an `AtomicReference<String>` holding the freshest token.
2. **`ResolvingDataSource`** — wraps the HTTP data source and, on every request, rewrites the URL's
   `__hdnea__` query param **and** the `Cookie` header with the latest token.
3. **Refresh loop** — a coroutine parses `exp` from the current token and, ~15s before expiry, calls
   `JioApiClient.getStreamUrl()` again, extracts the new token (`JioApiClient.extractHdneaToken`),
   and publishes it to the holder. Playback never sees an expired token.
4. **`JioLoadErrorHandlingPolicy`** — a safety net that makes 403/404 retryable for a few attempts
   with a short backoff (the retry then goes out with the refreshed token).

Buffering is tuned in `JioExoPlayerFactory.kt` (large min/max buffers, back-buffer, live speed
control) and the max buffer is user-configurable via the **Playback Buffer** setting. Tunneling is
**off by default** (it causes black screens on many Amlogic/MediaTek TVs) and exposed as a toggle.

> [!TIP]
> If channels start cutting out again after a JioTV change, first log the raw `geturl` response and
> confirm the token still appears as `__hdnea__` in the stream URL. If Jio changes the token name or
> delivery mechanism, update `extractHdneaToken` and the `ResolvingDataSource` resolver accordingly.
