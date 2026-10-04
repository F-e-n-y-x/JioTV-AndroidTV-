<p align="center"><img src="https://raw.githubusercontent.com/F-e-n-y-x/JioTV-AndroidTV-/v2-lab/screenshot/jtv-mark.svg" width="110" alt="JTV" /></p>

# JTV 2.0 beta 7

> ⚠️ **Pre-release.** The stable app (1.5.x) will **not** update to it by itself. Install `JTV-v2.0-beta.7.apk` over your current version, and your login and favourites are kept. Only the newest beta is kept here. Earlier betas are listed below.

## New in beta 7
- **हिन्दी में JTV.** The whole app can now be in Hindi: **Settings → General → App language** (Same as device / English / हिन्दी). Menus, buttons, categories, languages, days and months are translated. Channel names and show titles stay as Jio sends them.
- **Send a problem report.** If the app crashes or freezes, **Settings → About → Send problem report** shares what went wrong. Sign-in details are removed first. With a JTV server, reports are uploaded to it automatically.
- **Cleaner channel list.** JTV now uses Jio's newer channel list, so about 100 old or dead channels are gone. A few Zee regional channels that are only in the old list are kept.
- **For server users:**
  - Lighter TV guide download.
  - The server remembers each channel's stream type across restarts.
  - A crash-report inbox in the admin page.
  - The Docker image now runs as a normal user. **Before updating**, run once: `sudo chown -R 1000:1000 <your data folder>`.

## Known limits
- Some long Hindi labels on TV may be shortened with "…". Please send a screenshot if something looks cut off.
- Jio doesn't offer replays on some channels (for example Colors, Nick, many sports channels).

---

## Earlier betas

### Beta 6
- **Replay category** in the guide: only the channels you can watch again. Each has an amber replay mark, and so does every past show you can replay.

### Beta 5
- **Catch-up from the guide:** press OK on a past show to watch it from the start, up to 7 days back, with a seek bar, **Go live** and **Watch next programme**.
- **Picture-in-picture** on phone and tablet.
- **Custom remote buttons on TV** (Settings → Remote buttons), with profiles for Standard, Fire TV, Basic and Air-mouse remotes.
- **Faster:**
  - The APK is about 27% smaller.
  - Near-instant zapping up and down.
  - HTTP/2 streaming.
  - The channel list loads faster.
- Fixed a crash on launch on some devices.

### Beta 4
- **Pause and rewind live TV:** a seek bar and a **Live** button. Controls work like YouTube's.
- **Recorded-schedule channels** (Sony Yay and similar) start at the right show, with a seek bar and **Back to schedule**.
- One smooth fade for every screen change, including the back gesture.
- The status bar stays visible on player pages (except full screen).
- Fixed playback stopping when Voice Boost was Off. Fixed the audio language names.

### Beta 3
- **Accent colour:** pick the highlight colour in Settings, on every device.
- Amber beta icon (stable stays purple).
- Better phone landscape layouts.

### Beta 2
- Back to the original JTV logo.

### Beta 1
- The new **JTV 2.0** design for TV, phone and tablet.
- **YouTube-style mini player** on phone and tablet, plus a tablet navigation rail.
- **Favourites sync over home Wi-Fi** between phone and TV (pair once with a 4-digit code), and **Play on TV**.
- **Recent** channels on the home screen and in Search.
- 12-hour time, favourites backup and restore, and a fix for holding OK on basic remotes.

---

Please report anything odd in [Issues](https://github.com/F-e-n-y-x/JioTV-AndroidTV-/issues), with your device model. 🙏
