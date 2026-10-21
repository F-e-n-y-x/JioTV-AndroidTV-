# JTV 2.0 beta 26

> ⚠️ **Pre-release.** The stable app (1.5.x) will **not** update to it by itself. Install `JTV-v2.0-beta.26.apk` over your current version, and your login and favourites are kept. Only the newest beta is kept here. Earlier betas are listed below.

## New in beta 26
- **Replay on Sony channels.** Sony Yay (Hindi, Tamil, Telugu), SET, SET HD, Sony SAB, MAX, Pal, Aath, PIX, BBC Earth, Marathi and WAH had replay greyed out or failing with "This show can't be replayed right now". Replay now works on all of them. The first start after updating takes a little longer while the channel list is rebuilt once.
- **Sony Vizha.** Replay works for the shows Jio has made available. Many Vizha shows are still not on Jio's side, and those stay unavailable.

---

## Earlier betas

### Beta 25
- **Grid view for Live TV.** Switch between **List** and **Grid** with the toggle at the top of the Live TV page, or in **Settings → General → Channel view**. Each card shows the logo, number, full channel name and the show on now with a progress bar. A bar under the grid shows what's on and what's next for the channel you're on. Moving favourites around works in the grid too.
- **New channel browser in the player.** Press **←** while watching to open a two-pane list: categories on the left, channels on the right with the show on now. **↑ ↓** move through channels, **←** goes to the categories, **OK** watches. Prefer the old strip? Choose it in **Settings → Channel browser**.
- **No more "Ends 9:00 PM" pill** on the Live TV channel rows.

---

## Earlier betas

### Beta 24
- **Right channel from Favourites and global search.** Tapping a favourite in the Guide, or using **Start with → Last channel** after watching from Favourites or a global search, could open a different channel. Fixed. Favourites in a hidden language now play from the Guide too.
- **Player label.** A channel opened from global search shows "All 1000+ Channels" instead of a raw code.
- **Channel languages.** The in-stream multi-audio list is limited to Hungama, Super Hungama, Disney Channel, Disney Junior, Pogo, Sonic, Nickelodeon, Cartoon Network and Sony Yay, and the counts in **Settings → Channel languages** include them.

---

## Earlier betas

### Beta 23
- **Global Search across all 1000+ Channels (#12):**
  - Added a search scope toggle in the Search screen: toggle between searching all 1000+ channels globally or searching within your active language filters.
  - Channels selected from global search launch directly with seamless navigation.
- **Persistent Favorites across Language Filters (#11, #12):**
  - Favorited channels in any language remain permanently visible and accessible in the Favourites category and Search, even when that language is filtered out in Live TV settings.
  - Fixed the favorite channel count discrepancy: the category tab badge and the Live TV list header count now match 100% accurately.
- **Aspect Ratio "Fit" Framing for SD Channels (#11):**
  - Fixed PAL standard definition channels (e.g. Kalaignar TV, 720x576) squishing horizontally in "Fit" mode due to missing SAR metadata in HLS streams.
  - The player now recognizes Jio's declared 4:3 aspect ratio and standard PAL SD dimensions, accurately framing the picture in true 4:3 proportions without distortion.
- **Sony YAY Tamil Catch-up & Multi-Audio Channel Filtering (#10):**
  - Fixed catch-up replay for Sony YAY and other Sony channels by automatically falling back to Widevine DRM DASH streams when clear HLS catch-up manifests are missing or 404 on Jio's CDN.
  - Channels broadcast with multi-language in-stream audio (Hungama, Super Hungama, Disney Channel, Disney Junior, Pogo, Sonic, Nickelodeon, Cartoon Network, Sony Yay) remain visible when filtering by Tamil, Telugu, or other supported audio languages.

---

## Earlier betas

### Beta 22
- **Hardware-Accelerated Scaling & Full Aspect Ratio Fix for Zee Talkies & SD Channels:**
  - After examining and decoding the authentic live broadcast frames of Zee Talkies (`1024x576`), resolved the windowboxing (postage-stamp) and aspect ratio limitations on Android TV:
  - **Zoom (Crop Black Bars)**: Eliminates both the top/bottom letterbox bars and left/right pillarbox bars on SD movie channels like Zee Talkies, scaling the picture symmetrically to fill the 16:9 screen with 100% natural, undistorted proportions.
  - **Stretch (Full Screen)**: Anamorphically stretches 4:3 broadcast content horizontally to completely fill 16:9 widescreen with zero black bars.
  - **4:3 (Classic Box)**: Accurately squeezes wide streams into a centered 4:3 box ($1440 \times 1080$) for legacy content broadcast wide.
  - **Fit (Original View)**: Clean, uncropped 1.0x native presentation.
  - **16:9 (Widescreen)**: Forces full widescreen format.
  - **Unified Picture Size Controls**: Settings and Player Sidebar are now 100% synchronized with identical options and immediate visual feedback.

---

## Earlier betas

### Beta 21
- **Accurate Original View & Standard Aspect Presets.** Initial aspect ratio adjustments for Zee Talkies and removed recursive layout listeners.

### Beta 20
- **Aspect Ratio Fixed for Zee Talkies & SD Channels.** Direct `contentFrame` and hardware `SurfaceView` layout parameters for aspect ratio adjustments.

### Beta 19
- **Aspect Ratio & Picture Size Controls Fixed.** Restored functional aspect ratio handling across both Settings and the Player Sidebar.
- **Server Extra Channels & M3U Sharing.** The companion `server/` now automatically fetches, plays, and shares extra channels (Zee, Star Sports, Sony Sports & Entertainment) with automatic playlist updates from GitHub, token auto-renewal from live Akamai cookies, and built-in offline fallbacks. Serves M3U playlists with all extra channels directly to external IPTV players (VLC, TiviMate, OTT Navigator, Kodi) and in the web player.

### Beta 18
- **Full Sony Sports & Entertainment Channels.** Restored high-availability live HLS streams for Sony Sports Ten 1, 2, 3, 4, 5 (HD/SD), SET HD/SD, Sony SAB HD/SD, Sony MAX HD/SD, Sony MAX 2, Sony WAH, Sony PIX HD, Sony BBC Earth HD, Sony Yay, and DD Sports. Dynamic auto-token rotation and native AES-128 HLS decrypt ensure reliable streaming on all login methods without depending on Jio SIM authentication.
- **Auto-renewing tokens for Zee & Star Sports.** Integrated automated background token refresh from the live Akamai cookie source whenever tokens approach expiration, eliminating 48–72h playback cutoffs.
- **Accurate JioTV categorization & logos.** All Zee, Star Sports, and Sony channels are mapped into their official JioTV categories (Sports, Entertainment, Movies, News, Kids, Infotainment) with official channel numbers, STB numbers, and official high-resolution Jio CDN logos.

### Beta 17
- **Channel language filter.** Added a dedicated **Channel languages** setting (**Settings → Channels → Channel languages**): multi-select which languages you want to watch with channel counts, and show or hide channels language-wise across the entire app (Home, Categories, Guide, Search, and Player).
- **Fixed double audio & decoder stability.** Fixed ghost audio and channel playback stops caused by multiple background player instances and preference flow re-triggers when switching preview/picture settings (display fit, stretch, aspect ratio); background playback is now cleanly paused when entering Settings or other screens.
- **Live Star Sports & Sony Sports playback.** Updated sports streams upstream to an auto-renewing live feed (`mjelo`), resolving expired token errors (HTTP 403) across all Star Sports (Star Sports 1, 2, 3, Select 1/2 in English, Hindi, Tamil, Telugu, Kannada) and Sony Sports channels.
- **Added Zee Thirai & Zee Thirai HD.** Added Tamil movie channel Zee Thirai and Zee Thirai HD with ClearKey DRM playback.
- **Credits & Acknowledgments.** Huge thanks to [rathee-ashish/zee-playlist](https://github.com/rathee-ashish/zee-playlist) and community playlist contributors for channel stream metadata and Zee/sports playlist feeds.

### Beta 16
- **Added Star Sports & Sony Sports channels.** Added all 23 Star Sports channels (Star Sports 1, 2, 3, Select 1, Select 2 in English, Hindi, Tamil, Telugu, Kannada) and Sony Sports channels (Sony Sports Ten 1, 2, 3, 4, 5 HD/SD, Eurosport, DD Sports) with ClearKey DRM streams.
- **Added Zee Thirai & Zee Thirai HD.** Added Tamil movie channel Zee Thirai and Zee Thirai HD with ClearKey DRM playback and offline fallbacks.


### Beta 15
- **Working Zee channels & ClearKey DRM playback.** Restored working playback for Zee channels (Zee TV HD, Zee Cinema HD, &pictures HD, Zee News, Zee Marathi, Zing, Zee Talkies, and regional feeds) with ClearKey DRM support and official JioTV channel numbers and categories.
- **Fixed category reordering.** Reordering categories in Settings → Categories preserves any unlisted or custom channels without disrupting navigation order.
- **Background channel cache loading.** Asynchronous cache reading avoids IO pool stalls and thread deadlocks during app launch on Android TV and mobile devices.

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
