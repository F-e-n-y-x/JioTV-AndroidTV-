<p align="center"><img src="https://raw.githubusercontent.com/F-e-n-y-x/JioTV-AndroidTV-/v2-lab/screenshot/jtv-mark.svg" width="110" alt="JTV" /></p>

# JTV 2.0 beta 15

> ⚠️ **Pre-release.** The stable app (1.5.x) will **not** update to it by itself. Install `JTV-v2.0-beta.15.apk` over your current version, and your login and favourites are kept. Only the newest beta is kept here. Earlier betas are listed below.

## New in beta 15
- **Working Zee channels & ClearKey DRM playback.** Restored working playback for Zee channels (Zee TV HD, Zee Cinema HD, &pictures HD, Zee News, Zee Marathi, Zing, Zee Talkies, and regional feeds) with ClearKey DRM support and official JioTV channel numbers and categories.
- **Fixed category reordering.** Reordering categories in Settings → Categories preserves any unlisted or custom channels without disrupting navigation order.
- **Background channel cache loading.** Asynchronous cache reading avoids IO pool stalls and thread deadlocks during app launch on Android TV and mobile devices.

## Known limits
- Some long Hindi labels on TV may be shortened with "…". Please send a screenshot if something looks cut off.

---

## Earlier betas

### Beta 14
- **Find your server automatically.** Sign-in → *Self-hosted server* now lists the JTV servers on your home network (name, address, *Full* or *Lite*). Pick one and enter your access code. *Custom server* below still takes any address.
- Works with the full Docker server and the new tiny **JTV lite** server for routers (OpenWrt, Raspberry Pi, any PC). Both use one port, **29180**.

### Beta 13
- **Open when the TV turns on** (requested in #8). Settings → General → *Open when the TV turns on*: **After the box starts up**, or **Also after standby** for TVs that only sleep when switched off. With *Start with → Last channel*, it plays like a normal TV. On Android 10 and newer, a popup explains the one permission (*Display over other apps*) and opens that setting for you.
- **Guide fixed after midnight.** For a few hours after midnight Jio's guide still sends the previous day, so the Guide showed "Nothing scheduled". JTV now also loads the next day.
- **One loading sign on phone and tablet** in full screen (the spinner in the middle no longer shows while the controls' ring does).

### Beta 12
- **Favourites numbered everywhere.** In the Favourites category, the numbers 1, 2, 3 (your own order) now also show in the Guide, the player's channel card, the controls, the error card, the options panel, the mini player and the Home side panel. Other categories keep Jio's channel numbers.

### Beta 11
- **One loading sign.** While a channel loads with the controls open, only the ring around Play/Pause shows (there used to be a second spinner in the middle).
- **Tidier phone and tablet player.** In full screen, the show bar at the bottom spans the width with even gaps on all sides. On the portrait player, the channel tag is a small rounded label set in from the corner.

### Beta 10
- **Pictures of what's on.** On TV and tablet Home, the side panel shows a picture of the show playing on the channel you rest on, with its time and description, even with the programme guide setting off. The channels just above and below are loaded ahead, so moving down is quick.
- **Choose your categories.** **Settings → Categories**: show or hide any category and change their order (Move up, Move down, Move to top). Your choice applies to Home, the Guide and the player's channel list. All channels always stays.
- **Volume up / Volume down** can now be put on any button (Settings → Remote buttons), handy for game controllers.
- **Fixes for TV remotes:**
  - **Remove buttons** in Remote buttons works with remotes connected over HDMI-CEC (it used to record OK instead).
  - **Remove from favourites** now asks and then removes correctly with a remote.

### Beta 9
- **Works on more TV boxes.** Some boxes (for example MXQ Pro) were shown the phone layout. JTV now asks once at first start: **TV, Phone or Tablet**. You can change it any time in **Settings → Device type** (#6).
- **Favourites are numbered 1, 2, 3…** in your own order. Typing a number in Favourites picks that favourite (#7).
- **A calmer player:**
  - The channel card is now one wide, see-through bar with the channel logo, at the bottom of the screen.
  - The seek bar shows only when it's useful (paused, rewound, replays).
  - When a channel can't play, a small centred card shows the logo and two clear buttons.
  - ↑ goes to the next channel, the same as CH+. You can switch this back in Settings → Remote buttons.
- **Remote and game controller:** volume buttons and controller buttons (A, B, X, Y, L1, R1…) can now be given actions. There are new **Channel up / Channel down** actions too. A volume button with no action still changes the volume.
- **New version popup:** when an update is out, JTV offers it once with **Download and install**, **Later** or **Ignore this version**.
- **Mouse fix:** with an air mouse, lists no longer scroll by themselves under a still pointer.
- Smaller clock, channel names on focused tiles, Settings uses the full screen width.

### Beta 8
- **Clearer message when no channel plays.** When Jio refuses every channel, the problem is the connection or the account, not the channels. JTV now refreshes the sign-in once by itself. If Jio still refuses, it tells you what to check: an active Jio plan with JioTV, no VPN (Jio only streams to Indian networks), or signing in again. Before, every channel said "Jio isn't providing this channel" (#4).
- **No leftover picture.** When a channel can't load, the previous channel's video no longer stays on screen behind the message.

### Beta 7
- **हिन्दी में JTV.** The whole app can now be in Hindi: **Settings → General → App language** (Same as device / English / हिन्दी). Menus, buttons, categories, languages, days and months are translated. Channel names and show titles stay as Jio sends them.
- **Send a problem report.** If the app crashes or freezes, **Settings → About → Send problem report** shares what went wrong. Sign-in details are removed first. With a JTV server, reports are uploaded to it automatically.
- **Cleaner channel list.** JTV now uses Jio's newer channel list, so about 100 old or dead channels are gone. A few Zee regional channels that are only in the old list are kept.
- **For server users:**
  - Lighter TV guide download.
  - The server remembers each channel's stream type across restarts.
  - A crash-report inbox in the admin page.
  - The Docker image now runs as a normal user. **Before updating**, run once: `sudo chown -R 1000:1000 <your data folder>`.

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
