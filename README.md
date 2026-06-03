# JioTV (Android TV Client)

A highly optimized, lightweight Live TV streaming application specifically designed for Android TV and Android boxes (including low-end Amlogic chipsets).

## Features

- **Hardware Accelerated Playback**: Custom ExoPlayer configuration with MediaCodec hardware acceleration prioritized and software fallback disabled. This prevents the CPU from maxing out on low-end hardware.
- **Amlogic Audio Sync Fix**: Tunneling and specialized audio sink parameters to prevent audio/video desync and stuttering on Amlogic TVs.
- **Fast Channel Loading**: Highly optimized `Live` stream API requests prevent downloading massive 7-day catchup manifests, enabling instant channel zapping.
- **EPG Integration**: Automatic Electronic Program Guide parsing and extraction, with an optimized sliding-window memory model so low-end devices don't crash when parsing XML.
- **Modern TV UI**: Built entirely using Jetpack Compose for TV, featuring a native Numpad login experience and smooth TV-optimized navigation.
- **Resilient Connectivity**: Built-in logic to gracefully handle token expiration (HTTP 401/403) and cross-domain URL redirects for EPG files.

## Technical Stack

- **UI**: Jetpack Compose (Material TV)
- **Media**: AndroidX Media3 (ExoPlayer)
- **Architecture**: MVVM with Kotlin Coroutines and StateFlow
- **Data Persistence**: DataStore Preferences & internal file storage mapping (to prevent aggressive OS cache wipes).

## Building from Source

1. Clone the repository: `git clone https://github.com/your-username/JTV.git`
2. Open the project in Android Studio.
3. Build the APK: `./gradlew assembleDebug`
4. The output APK will be in `app/build/outputs/apk/debug/app-debug.apk`.

## Installation

You can sideload the app on your Android TV:
```bash
adb connect <YOUR_TV_IP>
adb install -r app-debug.apk
```

## Credits & Acknowledgements

This project was built with reference to and inspiration from the following open-source projects:
- [dineshintry/plugin.kodi.jiotv](https://github.com/dineshintry/plugin.kodi.jiotv)
- [JioTV-Go/jiotv_go](https://github.com/JioTV-Go/jiotv_go)

## Disclaimer

This is a custom, third-party Android TV client wrapper for personal and educational use. It is not affiliated with, maintained, authorized, or endorsed by JioTV or Reliance Jio Infocomm Ltd in any way. Use of this software is at your own risk.
