package com.fenyx.jtv.ui.player

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.fenyx.jtv.theme.JtvDark
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.theme.JtvDarkOnly
import com.fenyx.jtv.theme.LocalJtvColors
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One audio track of the playing stream, raw (de-duplicated later by [buildLanguageChoices]). */
@androidx.annotation.OptIn(UnstableApi::class)
private data class AudioOption(
    val group: androidx.media3.common.TrackGroup,
    val trackIndex: Int,
    val language: String?,
    val name: String?,
    val selected: Boolean,
    val supported: Boolean,
)

@SuppressLint("SetJavaScriptEnabled")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    channels: List<Channel>,
    initialIndex: Int,
    allChannelsByGroup: Map<String, List<Channel>>,
    groups: List<String>,
    onBack: () -> Unit,
    onSettings: () -> Unit = {},
    variantsFor: (String) -> List<com.fenyx.jtv.data.ChannelLanguage.Variant> = { emptyList() },
    initialGroup: String? = null,
    modifier: Modifier = Modifier,
    /** Phone/tablet: drawn as the in-app mini player (bar / card). Overlays, keys and focus are off. */
    mini: Boolean = false,
    /** Phone/tablet: shrink into the mini player (Back / chevron / swipe down). null (TV): Back leaves. */
    onMinimize: (() -> Unit)? = null,
    /** Mini player tapped. */
    onExpand: () -> Unit = {},
    /**
     * Bumped by the host each time a channel is opened while this player is already composed (e.g. from
     * the screen behind the mini player): the SAME player retunes to [initialIndex]/[initialGroup]
     * instead of a second ExoPlayer being built.
     */
    openToken: Int = 0,
) {
    val context = LocalContext.current
    // The app theme (the player itself is dark-only); the mini player matches the app.
    val appColors = Jtv.colors
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Saveable so the current channel/group survive leaving the player (e.g. opening full Settings and
    // coming back) instead of resetting to the channel the player was originally launched with.
    // Init from the launch group (which may be the "All"/"Favorites" pseudo-category) NOT the first
    // channel's real category — otherwise launching from "All" navigated the wrong list and played a
    // random channel.
    var currentGroup by rememberSaveable { mutableStateOf(initialGroup) }
    var currentIndex by rememberSaveable { mutableIntStateOf(initialIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    // Derived from the saved group so it rebuilds correctly after a state restore. For pseudo-categories
    // ("All"/"Favorites") there's no per-group entry, so fall back to the passed `channels` list.
    // The shared MainViewModel (activity-scoped, same instance Navigation uses) for the guide data and
    // categories the player wasn't handed (e.g. "All" when launched from News).
    val vm = remember(context) {
        context.findActivity()?.let { runCatching { ViewModelProvider(it)[MainViewModel::class.java] }.getOrNull() }
    }
    fun resolveGroup(g: String?): List<Channel> = when {
        g == null -> channels
        g == initialGroup -> allChannelsByGroup[g] ?: channels
        else -> allChannelsByGroup[g] ?: vm?.getChannelsByGroup(g) ?: emptyList()
    }
    val currentChannels = remember(currentGroup, allChannelsByGroup, channels) { resolveGroup(currentGroup) }
    val currentChannel = remember(currentIndex, currentChannels) { currentChannels.getOrNull(currentIndex) }

    // Collapsed per-language feeds for the channel on screen (empty when it isn't a language family).
    val currentVariants = remember(currentChannel) { currentChannel?.let { variantsFor(it.id) } ?: emptyList() }
    // When the user picks a different language we play that sibling channel_id without disturbing the
    // visible channel list. Reset whenever the logical channel changes (a zap). `playingChannel` is
    // derived lower down, once `language` (the preferred audio language) is in scope.
    var langOverride by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(currentChannel) { langOverride = null }

    // A channel opened again from outside (mini player host): retune this same player.
    var seenOpenToken by rememberSaveable { mutableIntStateOf(openToken) }
    LaunchedEffect(openToken) {
        if (openToken != seenOpenToken) {
            seenOpenToken = openToken
            currentGroup = initialGroup
            currentIndex = initialIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        }
    }

    // Overlay state (banner / browse / options / menu / number entry) lives apart from playback state.
    val ui = remember { PlayerUi(PlayerOverlay.Banner) }
    // Live timeshift (DVR window) state for the seek bar, the Live button and "Behind live".
    val ts = remember { Timeshift() }
    // Recorded-schedule channels ("VOD playout", e.g. some Sony Yay feeds): Jio's geturl returns the
    // CURRENT programme's file (SonyLIV, partner=jiotvvod), not a live stream. We start it at the
    // scheduled position and load the next file when it ends. Known from the URL at load, and from
    // the timeline (a non-live, non-placeholder window) once it is prepared.
    var vodItem by remember { mutableStateOf(false) }
    // The loaded stream URL without its query: a refreshed token is only applied to the same file.
    val streamBase = remember { java.util.concurrent.atomic.AtomicReference("") }
    
    val settingsManager = remember { SettingsManager(context) }
    val favoriteChannels by settingsManager.favoriteChannelsFlow.collectAsState(initial = emptySet())
    val playerSetupMode by settingsManager.setupModeFlow.collectAsState(initial = null)
    // "Refresh Login" (server mode) state shown in the right-side overlay.
    var refreshingCreds by remember { mutableStateOf(false) }

    // Programme guide: the channel being watched always gets its guide; other rows follow the setting.
    val epgModeState = settingsManager.epgModeFlow.collectAsState(initial = false)
    val epg = remember(vm) { EpgSource(vm, epgModeState) }

    var quality by remember { mutableStateOf("auto") }
    var language by remember { mutableStateOf("hi") }
    var resizeMode by remember { mutableIntStateOf(0) }

    // Follow the user's Default Audio Language: for a collapsed family, auto-select the matching
    // language feed unless the user has manually overridden it for this channel. The feed actually
    // sent to the player is: manual override, else preferred-language feed, else the logical channel.
    val preferredVariant = remember(currentVariants, language) {
        currentVariants.firstOrNull { it.langCode == language }?.channel
    }
    val playingChannel = langOverride ?: preferredVariant ?: currentChannel
    

    // Real audio tracks exposed by the current stream (for reliable language switching).
    var audioTracks by remember { mutableStateOf<List<AudioOption>>(emptyList()) }

    // Sleep timer: 0 = off. When set, the player exits after the chosen number of minutes.
    var sleepTimerMin by remember { mutableIntStateOf(0) }
    LaunchedEffect(sleepTimerMin) {
        if (sleepTimerMin > 0) {
            delay(sleepTimerMin * 60_000L)
            onBack()
        }
    }

    // Gate the first prepare() on the saved prefs being loaded. `quality` starts at "auto" and the real
    // value arrives from DataStore a beat later; because the media-source effect used to key on
    // `quality`, that late arrival re-fetched the stream and re-prepared the player *while it was
    // already playing* — a visible hitch a second into every channel. Quality doesn't affect the stream
    // URL at all (Jio serves one MPD containing every rendition), so it must not drive a reload.
    var prefsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        quality = settingsManager.defaultQualityFlow.first()
        language = settingsManager.defaultLanguageFlow.first()
        resizeMode = settingsManager.playerResizeModeFlow.first()
        prefsLoaded = true
    }

    // Player state
    var isBuffering by remember { mutableStateOf(true) }
    // True when the user paused (quick menu "Pause" or the Play/Pause key).
    var userPaused by remember { mutableStateOf(false) }
    // Non-null only when playback has failed and auto-recovery has been exhausted.
    var playbackError by remember { mutableStateOf<PlayerError?>(null) }

    // Stream auto-recovery: Jio live URLs carry a short-lived Akamai cookie (__hdnea__) that expires
    // after a while, which surfaces as a sudden black screen. On error we re-fetch the stream URL
    // (which regenerates the cookie). The counter resets every time playback recovers (STATE_READY),
    // so a long session can recover indefinitely instead of dying after a fixed number of errors.
    val retryCount = remember { mutableIntStateOf(0) }
    var streamRefreshTrigger by remember { mutableIntStateOf(0) }
    // Bumping this re-attaches the audio effect (see the buffering watchdog + AudioEnhancer) to kick a
    // stalled AudioTrack alive without touching the stream.
    var audioKick by remember { mutableIntStateOf(0) }
    val kickCount = remember { mutableIntStateOf(0) }

    // Player-affecting prefs. Read reactively (NEVER block the main thread here — doing so caused
    // jank/black-screen on entry). Tunneling is applied live via track-selection params below; the
    // hardware-decoder mode can only be set at construction, so the player is keyed on it.
    val tunnelingPref by settingsManager.tunnelingFlow.collectAsState(initial = false)
    val hardwareOnlyPref by settingsManager.hardwareDecoderFlow.collectAsState(initial = true)
    val bufferSecPref by settingsManager.playbackBufferSecFlow.collectAsState(initial = 60)

    // Audio enhancement settings + the effect engine.
    val voiceBoost by settingsManager.voiceBoostFlow.collectAsState(initial = 2)
    val audioNormalize by settingsManager.audioNormalizeFlow.collectAsState(initial = false)
    // LoudnessEnhancer handles makeup loudness; the dialogue processor does center-channel voice
    // isolation. The processor is stable across player rebuilds and reads its level live.
    val audioEnhancer = remember { com.fenyx.jtv.player.AudioEnhancer() }
    val dialogueProcessor = remember { com.fenyx.jtv.player.DialogueAudioProcessor() }

    // ExoPlayer is built using our custom factory to ensure Android TV optimizations. Keyed on the
    // settings that can only be applied at construction so changing any of them rebuilds the player;
    // the DisposableEffect below releases the previous instance when that happens.
    val exoPlayer = remember(hardwareOnlyPref, tunnelingPref, bufferSecPref) {
        com.fenyx.jtv.player.JioExoPlayerFactory.create(
            context,
            language,
            tunneling = tunnelingPref,
            hardwareOnly = hardwareOnlyPref,
            maxBufferSec = bufferSecPref,
            dialogueProcessor = dialogueProcessor
        )
    }

    // Deterministic audio session id: generate one and bind the player to it so audio-effect
    // attachment is reliable (the onAudioSessionIdChanged callback was unreliable on this hardware).
    val audioSessionId = remember(exoPlayer) {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val id = am.generateAudioSessionId()
        runCatching { exoPlayer.setAudioSessionId(id) }
        id
    }

    // Holds the freshest Akamai `__hdnea__` token. Read on the player's loader threads, written by the
    // refresh loop below, so it's an AtomicReference.
    val tokenHolder = remember { java.util.concurrent.atomic.AtomicReference("") }
    // Headers for the AES-128 key of the non-DRM HLS. The key lives on tv.media.jio.com and
    // authenticates like the Widevine license server (ssoToken/Accesstoken/crmid…), NOT like the CDN,
    // so sending the CDN stream headers there gets a 403 and the stream never decrypts.
    val keyHeadersHolder = remember { java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap()) }

    // Reuse data source factories to avoid GC pressure on every channel switch
    val httpDataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
    }
    // Applies the latest Akamai token to EVERY request (manifest + segments) two ways, because Jio
    // authorizes segments via BOTH the URL query token AND a `Cookie: __hdnea__=...` header:
    //   1) rewrite the `__hdnea__` query param in the URL (if present), and
    //   2) override the Cookie header with the fresh token.
    // So the CDN never sees an expired token -> no 403 -> no reload. With no fresh token yet, the
    // original (still-valid) request is used unchanged.
    val resolvingDataSourceFactory = remember {
        androidx.media3.datasource.ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
            val token = tokenHolder.get()
            val keyHeaders = keyHeadersHolder.get()
            if (keyHeaders.isNotEmpty() && isHlsKeyUri(dataSpec.uri.toString())) {
                dataSpec.withRequestHeaders(keyHeaders)
            } else if (token.isEmpty()) {
                dataSpec
            } else {
                val marker = "__hdnea__="
                var spec = dataSpec
                val uriStr = spec.uri.toString()
                val i = uriStr.indexOf(marker)
                if (i >= 0) {
                    spec = spec.withUri(android.net.Uri.parse(uriStr.substring(0, i) + marker + token))
                }
                val headers = HashMap(spec.httpRequestHeaders)
                headers["Cookie"] = marker + token
                spec.withRequestHeaders(headers)
            }
        }
    }
    val mediaSourceFactory = remember {
        DefaultMediaSourceFactory(context)
            .setDataSourceFactory(resolvingDataSourceFactory)
            // Retry transient/expiry errors (incl. 403/404 from an expired token) instead of failing
            // fatally and reloading. Works with the token rewrite above so the retry uses a fresh token.
            .setLoadErrorHandlingPolicy(com.fenyx.jtv.player.JioLoadErrorHandlingPolicy())
    }

    LaunchedEffect(exoPlayer, language, quality) {
        // NOTE: the anti-glitch setAllowVideoNonSeamlessAdaptiveness(false) is applied in
        // JioExoPlayerFactory — it lives on DefaultTrackSelector.Parameters.Builder, not on the base
        // TrackSelectionParameters.Builder that buildUpon() returns here. It persists across these
        // updates because buildUpon() carries the existing parameters forward.
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(language)
        // Tunneling is applied at construction (see remember key above), not here, because the base
        // TrackSelectionParameters.Builder doesn't expose setTunnelingEnabled in Media3 1.4.

        // An explicit quality choice is a FLOOR as well as a ceiling. Previously these were max-only
        // caps, so ABR was free to sit on Jio's ~80 kbps / 480p rendition even when the user had asked
        // for 1080p — the "doesn't follow my quality setting, starts blurry and creeps up" complaint.
        // Pinning min == max keeps the chosen tier locked. This can't black-screen: DefaultTrackSelector
        // has exceedVideoConstraintsIfNecessary=true by default, so a channel whose top rendition is
        // below the floor still falls back to its best available.
        when (quality) {
            // "low" is a deliberate data-saver / weak-link choice, so keep it a ceiling only and let
            // ABR drop further if the network genuinely can't hold 480p.
            "low" -> builder.setMaxVideoSize(854, 480)
            "medium" -> builder.setMinVideoSize(1280, 720).setMaxVideoSize(1280, 720)
            "high" -> builder.setMinVideoSize(1920, 1080).setMaxVideoSize(1920, 1080)
            // "auto": no floor — ABR adapts freely, but it now *starts* high because the bandwidth
            // meter is seeded optimistically in JioExoPlayerFactory instead of ramping from the floor.
            else -> builder.setMaxVideoSize(1920, 1080)
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    // Listen for errors (keyed on exoPlayer so a rebuilt player gets its own listener)
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val opts = mutableListOf<AudioOption>()
                // Diagnostic: log EVERY audio track the loaded manifest exposes, including ones the
                // device can't decode (adaptiveSupported/isSupported), so "missing language" reports can
                // be traced to (a) not present in the stream, or (b) unsupported codec. Filter logcat by
                // tag "TvPlayerAudio".
                val diag = StringBuilder()
                for (g in tracks.groups) {
                    if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            val lang = f.language
                            diag.append("\n  [${lang ?: "?"}] label=${f.label} codec=${f.codecs ?: f.sampleMimeType} " +
                                "ch=${f.channelCount} supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}")
                            opts.add(AudioOption(g.mediaTrackGroup, i, lang, f.label, g.isTrackSelected(i), g.isTrackSupported(i)))
                        }
                    }
                }
                android.util.Log.d("TvPlayerAudio", "audio tracks (${opts.size}):$diag")
                // Offer only tracks the device can play, unless none are (then offer all, so the
                // language never looks "missing"; the log above says which codec failed).
                audioTracks = opts.filter { it.supported }.ifEmpty { opts }

                // Diagnostic: which VIDEO rendition did ABR actually pick? Filter logcat by tag
                // "TvPlayerVideo" to confirm the quality setting is being honoured (the selected line
                // should match the chosen tier, not Jio's ~80 kbps floor).
                val vdiag = StringBuilder()
                for (g in tracks.groups) {
                    if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            vdiag.append("\n  ${f.width}x${f.height} @${f.bitrate}bps " +
                                "codec=${f.codecs} supported=${g.isTrackSupported(i)} " +
                                "SELECTED=${g.isTrackSelected(i)}")
                        }
                    }
                }
                android.util.Log.d("TvPlayerVideo", "video renditions:$vdiag")
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (timeline.isEmpty) return
                val w = timeline.getWindow(exoPlayer.currentMediaItemIndex.coerceIn(0, timeline.windowCount - 1),
                    androidx.media3.common.Timeline.Window())
                if (!w.isPlaceholder && !w.isLive && !vodItem) vodItem = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Schedule file finished: Jio now hands out the next programme's file.
                if (state == Player.STATE_ENDED && vodItem) {
                    android.util.Log.d("TvPlayer", "Schedule file ended: loading the next programme")
                    streamRefreshTrigger++
                }
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    // Playback recovered -> clear any error and reset the recovery budget so the
                    // next expiry (minutes/hours later) gets a fresh set of retries.
                    retryCount.intValue = 0
                    playbackError = null
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Paused (or rewound) past the start of the DVR window: the stream itself is fine, so go
                // back to live on the same source instead of re-fetching the URL.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    android.util.Log.d("TvPlayer", "Behind the live window: back to live")
                    ts.reset()
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    return
                }
                // Token/cookie expiration (often 403 Forbidden) causes a black screen.
                // We MUST re-fetch the stream URL entirely, not just retry the same expired URL.
                if (retryCount.intValue < 5) {
                    retryCount.intValue++
                    // Small backoff so a flapping CDN doesn't get hammered. Driven via the
                    // streamRefreshTrigger LaunchedEffect which re-fetches the stream URL.
                    val backoffMs = 800L * retryCount.intValue
                    scope.launch {
                        delay(backoffMs)
                        streamRefreshTrigger++
                    }
                } else {
                    // Auto-recovery exhausted: stop the spinner and show an actionable message
                    // instead of an indefinite black screen.
                    isBuffering = false
                    playbackError = PlayerError("The picture stopped.", ErrorAction.Retry, ErrorAction.NextChannel)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(playingChannel, quality) {
        retryCount.intValue = 0 // Reset retries on intentional channel/language/quality change
        playbackError = null
    }

    // Keyed on exoPlayer too: if the player is rebuilt (e.g. saved buffer/decoder/tunneling prefs load
    // a moment after open, or the hardware-decoder toggle changes), the NEW instance must be given the
    // media source — otherwise it buffers forever until some other change re-triggers this. That was the
    // "loads until I change a setting" bug.
    LaunchedEffect(exoPlayer, playingChannel, prefsLoaded, streamRefreshTrigger) {
        val ch = playingChannel
        // Wait for the saved prefs before the first prepare(), so the correct quality constraints are
        // already in place and playback never has to switch rendition (and re-init the secure decoder)
        // right after it starts. NOTE: `quality` is deliberately NOT a key here — it does not change
        // the stream URL, only track selection, which the separate effect below applies live.
        if (ch != null && prefsLoaded) {
            // Persist the representative (logical) channel for autoplay, not the language sibling.
            currentChannel?.let { settingsManager.setLastChannelId(it.id) }
            settingsManager.setLastChannelGroup(currentGroup)
            isBuffering = true
            exoPlayer.stop()
            
            val authData = settingsManager.authDataFlow.first()
            
            if (authData == null) {
                android.util.Log.e("TvPlayer", "Missing auth data")
                isBuffering = false
                playbackError = PlayerError("You aren't signed in to Jio.", ErrorAction.Settings)
                return@LaunchedEffect
            }
            
            val chNumber = ch.channelNumber.toString()
            android.util.Log.d("TvPlayer", "Fetching stream URL for channel $chNumber")
            
            val result = com.fenyx.jtv.data.JioApiClient.getStreamUrl(context, chNumber, authData)
            
            if (result.isSuccess) {
                val streamData = result.getOrNull()!!
                val finalUrl = streamData.streamUrl

                // Seed the token holder so the ResolvingDataSource and refresh loop have the current token.
                tokenHolder.set(com.fenyx.jtv.data.JioApiClient.extractHdneaToken(finalUrl))
                streamBase.set(finalUrl.substringBefore('?'))
                vodItem = isScheduleFileUrl(finalUrl)
                keyHeadersHolder.set(if (streamData.isMpd) emptyMap() else streamData.licenseHeaders)

                android.util.Log.d("TvPlayer", "Loading stream: $finalUrl (isMpd: ${streamData.isMpd})")

                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(finalUrl)
                    .setMimeType(if (streamData.isMpd) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
                    // Play ~20s behind the live edge so brief network/CDN jitter is absorbed by the
                    // buffer instead of starving the player and causing a rebuffer (the "loading").
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(20000)
                            .build()
                    )

                if (streamData.isMpd && streamData.licenseUrl.isNotEmpty()) {
                    val drmConfig = MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.WIDEVINE_UUID)
                        .setLicenseUri(streamData.licenseUrl)
                        .setLicenseRequestHeaders(streamData.licenseHeaders)
                        // Don't hard-block the pipeline on the Widevine license round-trip: render any
                        // clear leading segments while the key is still being fetched. Shaves the
                        // license RTT off every channel zap. The device already does secure hardware
                        // (L1) decode, so protected segments still wait for their key as required.
                        .setPlayClearContentWithoutKey(true)
                        // NOTE: multiSession is deliberately left at the default (false). Enabling it
                        // spun up a fresh Widevine session on every key rotation, and logcat showed the
                        // resulting CryptoHal/CDM churn contributing to the mid-playback hitch.
                        .build()
                    mediaItemBuilder.setDrmConfiguration(drmConfig)
                }

                httpDataSourceFactory.setDefaultRequestProperties(streamData.headers)
                ts.reset()

                val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // A track override belongs to one stream; a new stream picks by preferred language.
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO).build()
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    userPaused = false // a new channel always starts playing
                }
            } else {
                val fetchEx = result.exceptionOrNull()
                val fetchErr = fetchEx?.message ?: ""
                android.util.Log.e("TvPlayer", "Failed to fetch stream: $fetchErr")
                // Jio refusing the channel, or its stream being gone from the CDN, won't fix itself in
                // the next few seconds — say so at once instead of retrying 5x and then blaming the login.
                if (fetchEx is com.fenyx.jtv.data.JioApiClient.SessionExpiredException) {
                    isBuffering = false
                    playbackError = if (playerSetupMode == "server" || playerSetupMode == "jtv")
                        PlayerError("The Jio sign-in on your JTV server has expired.", ErrorAction.Retry)
                    else PlayerError("Your Jio sign-in has expired.", ErrorAction.Settings, ErrorAction.Retry)
                } else if (fetchEx is com.fenyx.jtv.data.JioApiClient.ChannelBlockedException) {
                    isBuffering = false
                    playbackError = PlayerError("Jio isn't providing this channel right now.", ErrorAction.NextChannel, ErrorAction.Retry)
                } else if (fetchEx is com.fenyx.jtv.data.JioApiClient.ChannelUnavailableException) {
                    isBuffering = false
                    playbackError = PlayerError("This channel is offline at Jio right now.", ErrorAction.Retry, ErrorAction.NextChannel)
                // Let the auto-recovery budget retry transient fetch failures; only show the error
                // once it's exhausted, so a one-off hiccup doesn't flash a message.
                } else if (retryCount.intValue >= 5) {
                    isBuffering = false
                    // A persistent 401/403 after the built-in credential refresh means the upstream
                    // Jio login is dead — retrying won't help. In server/JTV mode that's fixed by
                    // re-logging-in the Jio account on the server, so say so instead of "press OK".
                    val authExpired = fetchErr.contains("401") || fetchErr.contains("403")
                    playbackError = when {
                        authExpired && (playerSetupMode == "server" || playerSetupMode == "jtv") ->
                            PlayerError("The Jio sign-in on your JTV server has expired.", ErrorAction.Retry)
                        authExpired -> PlayerError("Your Jio sign-in has expired.", ErrorAction.Settings, ErrorAction.Retry)
                        else -> PlayerError("This channel didn't load.", ErrorAction.Retry, ErrorAction.NextChannel)
                    }
                } else {
                    retryCount.intValue++
                    delay(800L * retryCount.intValue)
                    streamRefreshTrigger++
                }
            }
        }
    }

    // Buffering watchdog. A stuck first load (common right after setup: the stream is fetched and
    // prepare()d, but playback never leaves STATE_BUFFERING and no PlaybackException is thrown, so the
    // onPlayerError retry path can't fire) used to sit on the spinner forever until the user changed a
    // player setting by hand. The real cause is the AudioTrack for our explicit audio session not
    // starting until an audio effect is bound (see AudioEnhancer) — so recovery ESCALATES:
    //   1) re-attach the audio effect (audioKick) — the same thing the manual "Voice Boost" toggle did;
    //   2) if that still doesn't help, re-fetch the stream URL (streamRefreshTrigger);
    //   3) finally, surface an actionable error instead of an endless spinner.
    // Reset per channel so every zap gets a fresh recovery budget.
    LaunchedEffect(playingChannel) { kickCount.intValue = 0 }
    LaunchedEffect(isBuffering, playingChannel, streamRefreshTrigger, audioKick) {
        if (isBuffering && playingChannel != null && playbackError == null && !userPaused) {
            delay(6_000)
            if (isBuffering && playbackError == null && !userPaused) {
                when {
                    kickCount.intValue < 3 -> {
                        kickCount.intValue++
                        android.util.Log.d("TvPlayer", "Buffering watchdog: audio kick ${kickCount.intValue}")
                        audioKick++
                    }
                    retryCount.intValue < 5 -> {
                        retryCount.intValue++
                        android.util.Log.d("TvPlayer", "Buffering watchdog: re-fetch ${retryCount.intValue}")
                        streamRefreshTrigger++
                    }
                    else -> {
                        isBuffering = false
                        playbackError = PlayerError("The picture stopped.", ErrorAction.Retry, ErrorAction.NextChannel)
                    }
                }
            }
        }
    }

    // ─── Transparent token refresh ───
    // The Jio `__hdnea__` token expires ~120s after issue. This loop fetches a fresh stream URL a few
    // seconds BEFORE expiry and publishes the new token to tokenHolder, so the ResolvingDataSource
    // keeps rewriting requests with a valid token. Playback never sees a 403 -> no reload, no buffering.
    LaunchedEffect(playingChannel) {
        val ch = playingChannel ?: return@LaunchedEffect
        while (true) {
            val token = tokenHolder.get()
            val expSec = com.fenyx.jtv.data.JioApiClient.extractTokenExpiryEpochSec(token)
            val nowSec = System.currentTimeMillis() / 1000
            // Refresh 15s before expiry; if we can't read an expiry, re-check in 60s.
            val waitMs = if (expSec > 0) ((expSec - nowSec - 15) * 1000).coerceIn(5_000, 110_000)
                         else 60_000L
            delay(waitMs)

            // Nothing is playing while an error is on screen (e.g. "sign-in expired"); polling then
            // only re-runs failing refreshes every minute. The user's OK press reloads the stream.
            if (playbackError != null) continue
            val authData = settingsManager.authDataFlow.first() ?: continue
            val res = com.fenyx.jtv.data.JioApiClient.getStreamUrl(
                context, ch.channelNumber.toString(), authData
            )
            if (res.isSuccess) {
                val newUrl = res.getOrNull()!!.streamUrl
                val newToken = com.fenyx.jtv.data.JioApiClient.extractHdneaToken(newUrl)
                // Schedule file: geturl may already point at the NEXT programme's file; its token is for
                // that file, so only take it when it is the same file (never reload mid-programme).
                if (vodItem && newUrl.substringBefore('?') != streamBase.get()) continue
                if (newToken.isNotEmpty()) {
                    tokenHolder.set(newToken)
                    android.util.Log.d("TvPlayer", "Token refreshed for channel ${ch.channelNumber}")
                }
            }
        }
    }

    // Recorded-schedule channels: start at the scheduled position, move on at the programme end.
    // Keyed on each load (streamRefreshTrigger) so every new file gets its own start seek + end timer.
    LaunchedEffect(vodItem, playingChannel, streamRefreshTrigger) {
        if (!vodItem) return@LaunchedEffect
        val ids = listOfNotNull(playingChannel?.id, currentChannel?.id).distinct()
        if (ids.isEmpty() || vm == null) return@LaunchedEffect
        ids.forEach { vm.fetchNativeEpgIfMissing(it) }
        fun programme(m: Map<String, List<com.fenyx.jtv.data.EpgProgram>>): com.fenyx.jtv.data.EpgProgram? {
            val now = System.currentTimeMillis()
            return ids.firstNotNullOfOrNull { id -> m[id]?.firstOrNull { it.startMs <= now && now < it.stopMs } }
        }
        val p = kotlinx.coroutines.withTimeoutOrNull(15_000) { vm.epgData.first { programme(it) != null } }
            ?.let { programme(it) } ?: return@LaunchedEffect
        // (a) Once the file is ready, jump to where the broadcast is now (only if that's inside the file).
        val ready = kotlinx.coroutines.withTimeoutOrNull(30_000) {
            while (exoPlayer.playbackState != Player.STATE_READY) delay(200)
            true
        } ?: false
        ts.scheduleStartMs = p.startMs
        if (ready) {
            val target = System.currentTimeMillis() - p.startMs
            val dur = exoPlayer.duration
            if (dur != androidx.media3.common.C.TIME_UNSET && target in 5_000 until dur - 5_000 &&
                kotlin.math.abs(exoPlayer.currentPosition - target) > 5_000) {
                android.util.Log.d("TvPlayer", "Schedule file: start at ${target / 1000}s of ${dur / 1000}s")
                exoPlayer.seekTo(target)
            }
            ts.update(exoPlayer)
        }
        // (b) The programme's end (+2 s): fetch the next programme's file.
        val wait = p.stopMs + 2_000 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        android.util.Log.d("TvPlayer", "Schedule: programme over, loading the next one")
        streamRefreshTrigger++
    }

    // Apply audio enhancements whenever the session id or any audio setting changes.
    // - dialogueProcessor: center-channel voice isolation (live, no rebuild needed), level 0..4
    // - audioEnhancer: LoudnessEnhancer makeup/normalize bound to the session id
    LaunchedEffect(voiceBoost) {
        dialogueProcessor.setLevel(voiceBoost)
    }
    LaunchedEffect(audioSessionId, voiceBoost, audioNormalize, audioKick) {
        audioEnhancer.apply(audioSessionId, audioNormalize, voiceBoost)
    }
    DisposableEffect(Unit) {
        onDispose { audioEnhancer.release() }
    }

    // Release the player when it is replaced (hardware-decoder toggle) or the screen leaves.
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Stop playback when the app is backgrounded (Home / app switch) so audio doesn't keep playing in
    // the background, and resume when it returns to the foreground (unless the user had paused).
    val lifecycleOwner = LocalLifecycleOwner.current
    val userPausedState = rememberUpdatedState(userPaused)
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_START -> if (!userPausedState.value) {
                    exoPlayer.play()
                    if (!mini) ui.showBanner()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ─────────────────────────── v2 overlay + key model ───────────────────────────

    val isTv = Jtv.isTv
    val portrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    // Phone portrait has no banner state (its info is always on screen), so Back skips that step.
    val isPhone = Jtv.form == FormFactor.Phone
    val phonePortrait = isPhone && portrait
    // Tablet: the expanded player is a page (two columns when wide, else the phone page) until the
    // user asks for full screen. Saved, so turning the tablet while full screen stays full screen.
    val isTablet = Jtv.form == FormFactor.Tablet
    var tabletFull by rememberSaveable { mutableStateOf(false) }
    val tabletPage = isTablet && !tabletFull
    val tabletTwoColumn = tabletPage && LocalConfiguration.current.screenWidthDp >= TabletTwoColumnMinWidthDp
    // A page with the info always on screen (no banner step for Back).
    val pagePlayer = phonePortrait || tabletPage
    val errorFocus = remember { FocusRequester() }
    // Plain (non-state) bookkeeping for the hold-OK gesture and Back de-duplication.
    val press = remember {
        object {
            var job: kotlinx.coroutines.Job? = null
            var longFired = false
            var swallow = false
            /** OK went down here in clean/banner state (a key-up alone, e.g. after tuning from the rail, is ignored). */
            var downSeen = false
            var lastKeyBack = 0L
        }
    }
    var numberJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Phone "Full screen": lock landscape until the phone is actually turned, then hand rotation back
    // to the sensor (so turning it upright again returns to the portrait page). "Exit full screen" does
    // the same towards portrait. With auto-rotate off the lock simply stays until the other button.
    val activity = remember(context) { context.findActivity() }
    var orientationLock by remember { mutableStateOf<Int?>(null) } // null = not locked by us
    fun setFullScreen(on: Boolean) {
        // Tablet: no rotation games, just the full-screen video and back to the page.
        if (isTablet) {
            tabletFull = on
            ui.showBanner()
            return
        }
        val a = activity ?: return
        val lock = if (on) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                   else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        a.requestedOrientation = lock
        orientationLock = lock
    }
    DisposableEffect(activity, orientationLock) {
        val a = activity
        val lock = orientationLock
        val autoRotate = a != null && runCatching {
            android.provider.Settings.System.getInt(a.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION) == 1
        }.getOrDefault(false)
        val listener = if (a != null && lock != null && autoRotate) {
            object : android.view.OrientationEventListener(a) {
                override fun onOrientationChanged(deg: Int) {
                    if (deg == ORIENTATION_UNKNOWN) return
                    val landscape = deg in 60..120 || deg in 240..300
                    val upright = deg <= 25 || deg >= 335
                    val wantLand = lock == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    if ((wantLand && landscape) || (!wantLand && upright)) {
                        a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        disable()
                    }
                }
            }.also { if (it.canDetectOrientation()) it.enable() }
        } else null
        onDispose { listener?.disable() }
    }
    // Leaving the player always gives rotation back.
    DisposableEffect(activity) {
        onDispose {
            if (orientationLock != null) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // System bars (phone/tablet): hidden only in true full screen (phone landscape, tablet full
    // screen). The portrait phone page and the tablet page keep the status bar, laid out below it.
    // TV has no system bars at all (MainActivity). Mini: the app's own state, so nothing to do.
    val trueFullScreen = !isTv && !mini && !pagePlayer
    val appLight = appColors.bg.luminance() > 0.5f
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(activity, trueFullScreen, mini, phonePortrait, appLight) {
        val window = activity?.window
        val ctl = if (!isTv && window != null) androidx.core.view.WindowInsetsControllerCompat(window, hostView) else null
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (ctl != null && !mini) {
            if (trueFullScreen) {
                ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(bars)
            } else {
                ctl.show(bars)
                // The phone page is always dark: light icons on it, whatever the app theme.
                if (phonePortrait) {
                    ctl.isAppearanceLightStatusBars = false
                    ctl.isAppearanceLightNavigationBars = false
                }
            }
        }
        onDispose {
            ctl?.show(bars)
            ctl?.isAppearanceLightStatusBars = appLight
            ctl?.isAppearanceLightNavigationBars = appLight
        }
    }

    val browseGroups = remember(groups, currentGroup) {
        buildList {
            if (MainViewModel.GROUP_FAVORITES in groups) add(MainViewModel.GROUP_FAVORITES)
            add(MainViewModel.GROUP_ALL)
            groups.forEach { if (it != MainViewModel.GROUP_FAVORITES && it != MainViewModel.GROUP_ALL) add(it) }
            currentGroup?.let { if (it !in this) add(0, it) }
        }
    }

    // Banner on every tune.
    LaunchedEffect(currentChannel?.id) { if (currentChannel != null) ui.showBanner() }

    fun setPaused(p: Boolean) {
        if (p) { exoPlayer.pause(); userPaused = true } else { exoPlayer.play(); userPaused = false }
        ts.update(exoPlayer)
    }

    // Timeshift polling: at most every 500 ms, and only while the controls / strap are up or the user
    // has paused (nothing reads the values otherwise; while playing behind live the offset is constant).
    LaunchedEffect(exoPlayer, mini) {
        if (mini) return@LaunchedEffect
        snapshotFlow {
            val ov = ui.overlay
            ov == PlayerOverlay.Banner || ov == PlayerOverlay.Controls || userPaused
        }.collectLatest { active ->
            if (active) while (true) {
                ts.update(exoPlayer)
                delay(500)
            }
        }
    }

    /** Seek inside the live window; at (or past) the right end it means live. Keeps play/pause as is. */
    fun commitSeek(ms: Long) {
        if (!ts.seekable) return
        if (ts.vod) {
            val t = ms.coerceIn(0L, (ts.spanMs - 1_000).coerceAtLeast(0L))
            exoPlayer.seekTo(t)
            ts.positionMs = t
        } else if (ms >= ts.spanMs - LiveToleranceMs) {
            exoPlayer.seekToDefaultPosition()
            ts.positionMs = ts.spanMs
        } else {
            val t = ms.coerceAtLeast(0L)
            exoPlayer.seekTo(t)
            ts.positionMs = t
        }
        ui.bannerToken++
    }

    fun doGoLive() {
        if (ts.vod) {
            // Back to schedule: where the broadcast is now.
            val t = ts.scheduleMs
            android.util.Log.d("TvPlayer", "Back to schedule: ${t / 1000}s")
            if (t >= 0) { exoPlayer.seekTo(t); ts.positionMs = t }
            setPaused(false)
            ui.bannerToken++
            return
        }
        exoPlayer.seekToDefaultPosition()
        ts.positionMs = ts.spanMs
        setPaused(false)
        ts.positionMs = ts.spanMs
        ui.bannerToken++
    }

    fun doZap(delta: Int) {
        val n = currentChannels.size
        if (n == 0) return
        currentIndex = ((currentIndex + delta) % n + n) % n
        ui.showBanner()
    }

    fun doTune(group: String?, index: Int) {
        val list = if (group == currentGroup) currentChannels else resolveGroup(group)
        if (list.isEmpty()) return
        currentGroup = group
        currentIndex = index.coerceIn(0, list.size - 1)
        ui.overlay = PlayerOverlay.Banner
        ui.bannerToken++
    }

    fun doRetry() {
        playbackError = null
        retryCount.intValue = 0
        isBuffering = true
        streamRefreshTrigger++
    }

    fun openBrowse() {
        val gs = browseGroups
        var g: String = currentGroup ?: MainViewModel.GROUP_ALL
        var list = resolveGroup(g)
        if (list.isEmpty()) {
            g = gs.firstOrNull { resolveGroup(it).isNotEmpty() } ?: return
            list = resolveGroup(g)
        }
        ui.browseGroup = g
        ui.browseIndex = list.indexOfFirst { it.id == currentChannel?.id }.coerceAtLeast(0)
        ui.browseFocusToken++
        ui.overlay = PlayerOverlay.Browse
    }

    fun browseCategory(delta: Int) {
        val gs = browseGroups
        if (gs.isEmpty()) return
        var i = gs.indexOf(ui.browseGroup ?: MainViewModel.GROUP_ALL).coerceAtLeast(0)
        // Skip empty categories so focus always has a tile to land on.
        repeat(gs.size) {
            i = ((i + delta) % gs.size + gs.size) % gs.size
            val list = resolveGroup(gs[i])
            if (list.isNotEmpty()) {
                ui.browseGroup = gs[i]
                ui.browseIndex = list.indexOfFirst { it.id == currentChannel?.id }.takeIf { it >= 0 } ?: 0
                ui.browseFocusToken++
                return
            }
        }
    }

    /** Real Jio channel number → (group, index): the current category first, then all channels. */
    fun findByNumber(num: Int): Pair<String?, Int>? {
        val i = currentChannels.indexOfFirst { it.channelNumber == num }
        if (i >= 0) return currentGroup to i
        val j = resolveGroup(MainViewModel.GROUP_ALL).indexOfFirst { it.channelNumber == num }
        return if (j >= 0) MainViewModel.GROUP_ALL to j else null
    }

    fun commitNumber() {
        numberJob?.cancel()
        val num = ui.number.toIntOrNull()
        ui.number = ""
        if (num == null) return
        val hit = findByNumber(num)
        if (hit != null) {
            doTune(hit.first, hit.second)
        } else {
            ui.numberMiss = "No channel $num"
            scope.launch { delay(2_000); if (ui.number.isEmpty()) ui.numberMiss = null }
        }
    }
    val commitLatest by rememberUpdatedState({ commitNumber() })

    fun restartNumberTimer() {
        numberJob?.cancel()
        numberJob = scope.launch { delay(1_500); commitLatest() }
    }

    /** Into the mini player where the host offers one (rotation is handed back); else leave. */
    fun doMinimize() {
        val m = onMinimize ?: return onBack()
        tabletFull = false // expanding again opens the page
        if (orientationLock != null) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            orientationLock = null
        }
        m()
    }

    // Entering the mini player drops every overlay and any half-typed number, so nothing is left
    // waiting for keys the player no longer receives.
    LaunchedEffect(mini) {
        if (mini) {
            numberJob?.cancel()
            press.job?.cancel()
            ui.number = ""
            ui.numberMiss = null
            ui.pointerChrome = false
            ui.overlay = PlayerOverlay.None
        } else {
            ui.showBanner() // expanded: controls show briefly, as after a tune
        }
    }

    /** Back ladder: entry/menu/panel/browse → close; banner → hide; clean video → leave the player. */
    fun backLadder() {
        when {
            ui.number.isNotEmpty() -> { numberJob?.cancel(); ui.number = "" }
            ui.overlay == PlayerOverlay.Menu || ui.overlay == PlayerOverlay.Browse -> ui.overlay = PlayerOverlay.None
            ui.overlay == PlayerOverlay.Options ->
                if (ui.optionsPage != OptionsPage.Main && ui.optionsEntry == OptionsPage.Main) ui.optionsPage = OptionsPage.Main
                else ui.overlay = PlayerOverlay.None
            ui.overlay == PlayerOverlay.Controls -> { ui.overlay = PlayerOverlay.None; ui.pointerChrome = false }
            ui.overlay == PlayerOverlay.Banner && !pagePlayer -> { ui.overlay = PlayerOverlay.None; ui.pointerChrome = false }
            // Tablet full screen: Back returns to the page first.
            isTablet && tabletFull -> setFullScreen(false)
            // Phone full screen: Back returns to the portrait page first.
            isPhone && !portrait && orientationLock == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                setFullScreen(false)
            // Phone/tablet: Back shrinks into the mini player (YouTube); TV: Back leaves.
            else -> doMinimize()
        }
    }

    val actions = object : PlayerActions {
        override fun toggleFavourite() {
            val id = currentChannel?.id ?: return
            scope.launch { settingsManager.toggleFavoriteChannel(id) }
        }
        override fun pickLanguage(value: String) {
            when {
                value.startsWith(LANG_AUDIO) -> {
                    // In-stream language: a preference, not an override, so ExoPlayer picks the matching
                    // rendition for whichever video variant is playing (HD masters carry one per group).
                    val code = value.removePrefix(LANG_AUDIO)
                    // Stay on this feed: the language preference must not also hop to a sibling channel.
                    if (currentVariants.isNotEmpty()) langOverride = playingChannel
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                        .setPreferredAudioLanguage(code)
                        .build()
                    language = code
                    scope.launch { settingsManager.setDefaultLanguage(code) }
                }
                value.startsWith(LANG_TRACK) -> {
                    // Unnamed track: only an override can tell them apart.
                    val opt = value.removePrefix(LANG_TRACK).toIntOrNull()?.let { audioTracks.getOrNull(it) } ?: return
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(opt.group, listOf(opt.trackIndex)))
                        .build()
                }
                value.startsWith(LANG_CHANNEL) -> {
                    val id = value.removePrefix(LANG_CHANNEL)
                    val v = currentVariants.firstOrNull { it.channel.id == id } ?: return
                    langOverride = v.channel
                    // Remember the chosen language so other channels + this one default to it.
                    v.langCode?.let { lc ->
                        language = lc
                        scope.launch { settingsManager.setDefaultLanguage(lc) }
                    }
                }
            }
        }
        override fun pickQuality(value: String) {
            quality = value
            scope.launch { settingsManager.setDefaultQuality(value) }
        }
        override fun pickAspect(mode: Int) {
            resizeMode = mode
            scope.launch { settingsManager.setPlayerResizeMode(mode) }
        }
        override fun pickVoice(level: Int) { scope.launch { settingsManager.setVoiceBoost(level) } }
        override fun toggleAutoVolume() { scope.launch { settingsManager.setAudioNormalize(!audioNormalize) } }
        override fun pickSleep(minutes: Int) { sleepTimerMin = minutes }
        override fun togglePause() { setPaused(!userPaused) }
        override fun openChannelList() {
            if (isTv) openBrowse() else { ui.overlay = PlayerOverlay.None; ui.showBanner() }
        }
        override fun openSettings() {
            ui.overlay = PlayerOverlay.None
            onSettings()
        }
        override fun refreshLogin() {
            // Server mode: force a credential re-pull from the proxy and reload the stream, so a
            // rotated/expired shared token can be fixed without leaving the player.
            if (!refreshingCreds) scope.launch {
                refreshingCreds = true
                com.fenyx.jtv.data.JioApiClient.refreshCredentials(context)
                refreshingCreds = false
                doRetry()
                ui.overlay = PlayerOverlay.None
            }
        }
        override fun zap(delta: Int) = doZap(delta)
        override fun tune(group: String?, index: Int) = doTune(group, index)
        override fun back() = backLadder()
        override fun leave() = onBack()
        override val canMinimize: Boolean get() = onMinimize != null
        override fun minimize() = doMinimize()
        override fun retry() = doRetry()
        override fun fullScreen(on: Boolean) = setFullScreen(on)
        override fun seekTo(ms: Long) = commitSeek(ms)
        override fun goLive() = doGoLive()
    }
    val actionsState = rememberUpdatedState<PlayerActions>(actions)

    val langChoices = remember(audioTracks, currentVariants, playingChannel) {
        val pid = playingChannel?.id
        buildLanguageChoices(
            tracks = audioTracks.mapIndexed { i, t -> StreamAudio(i, t.language, t.name, t.selected) },
            variants = currentVariants,
            playingId = pid,
            playingLang = currentVariants.firstOrNull { it.channel.id == pid }?.langCode,
            fallbackLabel = playingChannel?.language?.takeIf { it.isNotBlank() } ?: "Original sound",
        )
    }
    val model = OptionsModel(
        favourite = currentChannel?.id?.let { it in favoriteChannels } ?: false,
        langChoices = langChoices.options,
        langCurrent = langChoices.current,
        langLabel = langChoices.label,
        quality = quality,
        aspect = resizeMode,
        voice = voiceBoost,
        autoVolume = audioNormalize,
        sleep = sleepTimerMin,
        paused = userPaused,
        showRefresh = playerSetupMode == "server" || playerSetupMode == "jtv",
        refreshing = refreshingCreds,
    )

    val overlayData = OverlayData(
        playing = currentChannel,
        currentGroup = currentGroup,
        browseGroups = browseGroups,
        resolveGroup = ::resolveGroup,
        findByNumber = { n ->
            findByNumber(n)?.let { (g, i) -> if (g == currentGroup) currentChannels.getOrNull(i) else resolveGroup(g).getOrNull(i) }
        },
        epg = epg,
        model = model,
        actions = actions,
        timeshift = ts,
        buffering = isBuffering && playbackError == null,
    )

    // Keep focus somewhere sensible: on the error's first button (TV), else on the player itself, so
    // keys keep arriving after a panel, rail or pointer bar goes away.
    // Not while mini: the screen behind owns focus then (restarted on expand, which re-takes focus).
    LaunchedEffect(mini) {
        if (mini) return@LaunchedEffect
        snapshotFlow { Triple(ui.overlay, ui.pointerChrome, playbackError != null && !isBuffering) }
            .collect { (ov, _, err) ->
                if (ov == PlayerOverlay.None || ov == PlayerOverlay.Banner) {
                    withFrameNanos { }
                    runCatching { if (err && isTv) errorFocus.requestFocus() else focusRequester.requestFocus() }
                }
            }
    }

    // System back (gesture / predictive back). Key-event Back is handled in the key handler below; skip
    // the duplicate if both arrive for one press.
    // Mini: no handler, so Back works normally for the screen behind. Composed conditionally (not
    // `enabled = !mini`) so expanding re-registers it ON TOP of any handler a screen added meanwhile.
    if (!mini) {
        androidx.activity.compose.BackHandler {
            if (android.os.SystemClock.uptimeMillis() - press.lastKeyBack > 1_000) backLadder()
        }
    }

    // Mini drag offset (swipe to close) and the video's box in each mode.
    val miniDrag = remember { MiniDrag() }
    LaunchedEffect(mini) { miniDrag.reset() }
    val card = Jtv.form == FormFactor.Tablet || Jtv.isPhoneLandscape
    val videoMode = when {
        mini && !card -> VideoBox.MiniBar
        mini -> VideoBox.TopWide
        tabletTwoColumn -> VideoBox.TwoColumn
        pagePlayer -> VideoBox.TopWide
        else -> VideoBox.Fill
    }
    val rootFrame = when {
        !mini -> Modifier.fillMaxSize().background(
            when {
                tabletPage -> appColors.bg // the tablet page follows the app theme
                phonePortrait -> JtvDark.bg
                else -> Color.Black
            },
        )
        card -> Modifier.width(MiniCardWidth)
            .offset { androidx.compose.ui.unit.IntOffset(miniDrag.x.toInt(), miniDrag.y.toInt()) }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(appColors.s1)
            .border(1.dp, appColors.line, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        else -> Modifier.fillMaxWidth().height(MiniBarHeight)
            .offset { androidx.compose.ui.unit.IntOffset(miniDrag.x.toInt(), miniDrag.y.toInt()) }
            .background(appColors.s1)
    }


    JtvDarkOnly {
        Box(
            modifier = modifier
                .then(rootFrame)
                // The phone / tablet page sits below the status bar (and above the navigation bar).
                .then(if (!mini && pagePlayer) Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing) else Modifier)
                .focusRequester(focusRequester)
                .focusable(enabled = !mini)
                .then(if (mini) Modifier else Modifier.pointerInput(ui) {
                    // Mouse / air-mouse: move shows the banner + top bar, wheel zaps, right-click opens the
                    // quick menu. Touch: any touch while controls show restarts their hide timer.
                    awaitPointerEventScope {
                        var lastWheel = 0L
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = e.changes.firstOrNull() ?: continue
                            val mouse = ch.type == PointerType.Mouse
                            when (e.type) {
                                PointerEventType.Move -> if (mouse) {
                                    if (!ui.pointerChrome) ui.pointerChrome = true
                                    val ov = ui.overlay
                                    if (isTv && (ov == PlayerOverlay.None || ov == PlayerOverlay.Banner)) ui.openControls()
                                    else if (ov == PlayerOverlay.None) ui.showBanner() else ui.bumpThrottled()
                                } else if (ui.overlay == PlayerOverlay.Banner) ui.bumpThrottled()
                                PointerEventType.Scroll -> {
                                    val ov = ui.overlay
                                    if (ov == PlayerOverlay.None || ov == PlayerOverlay.Banner) {
                                        val t = android.os.SystemClock.uptimeMillis()
                                        val dy = ch.scrollDelta.y
                                        if (dy != 0f && t - lastWheel > 250) {
                                            lastWheel = t
                                            actionsState.value.zap(if (dy > 0) 1 else -1)
                                        }
                                        e.changes.forEach { it.consume() }
                                    }
                                }
                                PointerEventType.Press -> if (mouse && e.buttons.isSecondaryPressed) {
                                    ui.overlay = PlayerOverlay.Menu
                                    e.changes.forEach { it.consume() }
                                } else if (!mouse && ui.overlay == PlayerOverlay.Banner) ui.bumpThrottled()
                                else -> {}
                            }
                        }
                    }
                })
                .onPreviewKeyEvent { ev ->
                    // Mini: the bar's own buttons may hold focus; never zap / open menus from there.
                    if (mini) return@onPreviewKeyEvent false
                    val k = ev.key
                    val down = ev.type == KeyEventType.KeyDown
                    val isCenter = k == Key.Enter || k == Key.DirectionCenter || k == Key.NumPadEnter
                    val ov = ui.overlay
                    val clean = ov == PlayerOverlay.None || ov == PlayerOverlay.Banner
                    val errShown = playbackError != null && !isBuffering
                    val entering = ui.number.isNotEmpty()
                    val controlsUp = ov == PlayerOverlay.Controls
                    // Any key inside the TV controls layer keeps it up.
                    if (controlsUp && down) ui.bannerToken++

                    // TV timeshift: Left/Right seek (10 s, held: 30 s then 60 s; the seek happens on release)
                    // on the seek bar, or anywhere while paused (except Prev/Next and the top bar).
                    if (isTv && !entering && !errShown && (k == Key.DirectionLeft || k == Key.DirectionRight)) {
                        val onBar = controlsUp && ui.focusedControl == ControlFocus.Bar
                        val scrubHere = ts.scrubMs != null || onBar || when {
                            controlsUp -> userPaused && ui.focusedControl == ControlFocus.Play
                            clean -> userPaused
                            else -> false
                        }
                        if (scrubHere && ts.seekable) {
                            if (clean && down) ui.openControls(ControlFocus.Bar)
                            if (ts.scrubKey(ev, yieldAtLive = onBar, commit = ::commitSeek)) return@onPreviewKeyEvent true
                        }
                    }

                    // The rest of a hold-OK that already opened the menu (repeats + key-up) is swallowed so
                    // it doesn't also press the menu's first item.
                    if (isCenter && press.swallow) {
                        if (!down) press.swallow = false
                        return@onPreviewKeyEvent true
                    }
                    // Number entry: OK goes now.
                    if (isCenter && entering) {
                        if (down) commitNumber()
                        return@onPreviewKeyEvent true
                    }
                    // Clean / banner: short OK toggles the banner (or resumes), hold OK opens the quick menu.
                    if (isCenter && clean && !errShown) {
                        if (down) {
                            if (ev.nativeKeyEvent.repeatCount == 0) {
                                press.downSeen = true
                                press.longFired = false
                                press.job?.cancel()
                                press.job = scope.launch {
                                    delay(450) // long-press threshold
                                    press.longFired = true
                                    press.swallow = true
                                    ui.overlay = PlayerOverlay.Menu
                                }
                            }
                        } else {
                            press.job?.cancel()
                            if (press.downSeen && !press.longFired) {
                                when {
                                    isTv -> {
                                        if (userPaused) setPaused(false)
                                        ui.openControls(ControlFocus.Play)
                                    }
                                    userPaused -> setPaused(false)
                                    ui.overlay == PlayerOverlay.Banner -> { ui.overlay = PlayerOverlay.None; ui.pointerChrome = false }
                                    else -> ui.showBanner()
                                }
                            }
                            press.longFired = false
                            press.downSeen = false
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (!down) return@onPreviewKeyEvent false

                    val digit = when (k) {
                        Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
                        Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
                        Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
                        Key.Nine, Key.NumPad9 -> 9
                        else -> null
                    }
                    if (digit != null) {
                        if (ov == PlayerOverlay.Options || ov == PlayerOverlay.Menu) return@onPreviewKeyEvent false
                        if (!(ui.number.isEmpty() && digit == 0) && ui.number.length < 4) {
                            ui.number += digit.toString()
                            ui.numberMiss = null
                            restartNumberTimer()
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (k == Key.Back || k == Key.Escape) {
                        press.lastKeyBack = android.os.SystemClock.uptimeMillis()
                        backLadder()
                        return@onPreviewKeyEvent true
                    }

                    if (entering) {
                        when (k) {
                            Key.DirectionLeft, Key.Backspace -> {
                                ui.number = ui.number.dropLast(1)
                                if (ui.number.isEmpty()) numberJob?.cancel() else restartNumberTimer()
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionUp, Key.DirectionDown, Key.DirectionRight -> return@onPreviewKeyEvent true
                            else -> {}
                        }
                    }

                    if (!controlsUp && (k == Key.DirectionUp || k == Key.DirectionDown || k == Key.DirectionLeft || k == Key.DirectionRight)) {
                        ui.pointerChrome = false
                    }
                    val panel = ov == PlayerOverlay.Options || ov == PlayerOverlay.Menu

                    when (k) {
                        Key.ChannelUp, Key.MediaNext, Key.PageUp -> { if (!panel) doZap(1); true }
                        Key.ChannelDown, Key.MediaPrevious, Key.PageDown -> { if (!panel) doZap(-1); true }
                        Key.MediaPlayPause -> {
                            setPaused(!userPaused)
                            if (isTv) ui.openControls() else if (userPaused) ui.showBanner()
                            true
                        }
                        Key.MediaPlay -> { setPaused(false); true }
                        Key.MediaPause -> { setPaused(true); if (isTv) ui.openControls() else ui.showBanner(); true }
                        Key.MediaFastForward, Key.MediaRewind -> {
                            if (ts.seekable && !panel) {
                                val base = if (ts.atLive && !ts.vod) ts.spanMs else ts.positionMs
                                commitSeek(base + if (k == Key.MediaFastForward) 10_000 else -10_000)
                                if (isTv) ui.openControls(ControlFocus.Bar) else ui.showBanner()
                            }
                            true
                        }
                        Key.Menu -> { ui.overlay = if (ov == PlayerOverlay.Menu) PlayerOverlay.None else PlayerOverlay.Menu; true }
                        Key.Guide -> { if (ov != PlayerOverlay.Browse) openBrowse(); true }
                        Key.Info -> {
                            if (ov == PlayerOverlay.Banner) ui.overlay = PlayerOverlay.None
                            else if (ov == PlayerOverlay.None) ui.showBanner()
                            true
                        }
                        Key.DirectionUp -> when (ov) {
                            PlayerOverlay.Browse -> { browseCategory(-1); true }
                            PlayerOverlay.Options, PlayerOverlay.Menu, PlayerOverlay.Controls -> false
                            else -> { doZap(-1); true }
                        }
                        Key.DirectionDown -> when (ov) {
                            PlayerOverlay.Browse -> { browseCategory(1); true }
                            PlayerOverlay.Options, PlayerOverlay.Menu, PlayerOverlay.Controls -> false
                            else -> { doZap(1); true }
                        }
                        Key.DirectionLeft -> when (ov) {
                            PlayerOverlay.Browse, PlayerOverlay.Menu, PlayerOverlay.Controls -> false
                            PlayerOverlay.Options -> { backLadder(); true }
                            else -> if (errShown) false else { openBrowse(); true }
                        }
                        Key.DirectionRight -> when (ov) {
                            PlayerOverlay.Browse, PlayerOverlay.Options, PlayerOverlay.Menu, PlayerOverlay.Controls -> false
                            else -> if (errShown) false else { ui.openOptions(); true }
                        }
                        else -> false
                    }
                }
        ) {
            // The ONE video surface: always the first child, in every mode (full, portrait page, mini),
            // so it is never recreated; only its box changes (layout phase). Everything else draws over it.
            VideoSurface(exoPlayer, resizeMode, Modifier.videoBox(videoMode))

            val name = currentChannel?.name
            val buffering = isBuffering && playbackError == null
            val paused = userPaused && !isBuffering
            val status: @Composable BoxScope.() -> Unit = {
                if (buffering) BufferingIndicator(name, Modifier.align(Alignment.Center))
                if (paused && isTv) TvPausedBadge(ui, Modifier.align(Alignment.Center))
            }

            if (mini) {
                MiniPlayerContent(
                    appColors = appColors,
                    card = card,
                    channel = currentChannel,
                    epg = epg,
                    paused = userPaused,
                    buffering = buffering,
                    error = playbackError?.takeIf { !isBuffering }?.message,
                    drag = miniDrag,
                    onExpand = onExpand,
                    onTogglePause = { setPaused(!userPaused) },
                    onClose = onBack,
                )
            } else if (phonePortrait) {
                // Draws its own buffering ring and keeps its Play control up while paused.
                PhonePortraitPlayer(ui = ui, d = overlayData, actionsState = actionsState, buffering = buffering)
            } else if (tabletPage) {
                // The app theme (light or dark); the controls on the picture stay dark.
                CompositionLocalProvider(LocalJtvColors provides appColors) {
                    if (tabletTwoColumn) {
                        TabletLandscapePlayer(ui = ui, d = overlayData, actionsState = actionsState, buffering = buffering)
                    } else {
                        PhonePortraitPlayer(
                            ui = ui, d = overlayData, actionsState = actionsState, buffering = buffering,
                            maxContentWidth = TabletPortraitContentWidth,
                        )
                    }
                }
            } else {
                if (!isTv) Box(Modifier.fillMaxSize().touchVideoGestures(ui, actionsState, tapToggles = true))
                Box(Modifier.fillMaxSize()) { status() }
                if (isTv) TvOverlays(ui, overlayData)
                else TouchOverlays(ui, overlayData, compact = isPhone, exitFullScreen = isPhone || isTablet)
            }

            val err = playbackError
            if (err != null && !isBuffering && !mini) {
                // Tablet page: over the picture, not the middle of the page.
                val errBox = if (tabletPage) Modifier.videoBox(videoMode) else Modifier.matchParentSize()
                Box(errBox, contentAlignment = Alignment.Center) { ErrorPanel(
                    error = err,
                    channel = currentChannel,
                    firstFocus = errorFocus,
                    touch = !isTv,
                    onAction = { a ->
                        when (a) {
                            ErrorAction.Retry -> doRetry()
                            ErrorAction.NextChannel -> doZap(1)
                            ErrorAction.Settings -> onSettings()
                        }
                    },
                    modifier = Modifier.padding(24.dp),
                ) }
            }
        }
    }

    LaunchedEffect(mini) {
        if (!mini) runCatching { focusRequester.requestFocus() }
    }
}

/** The video. Its own composable so overlay changes never recompose (or re-layout) the PlayerView. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, resizeMode: Int, modifier: Modifier) {
    // Debuggable builds only: a "novideo" file in the app's external files dir replaces the picture with
    // a neutral placeholder, so UI screenshots for the README never contain channel video.
    val ctx0 = LocalContext.current
    val noVideo = remember {
        (ctx0.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            java.io.File(ctx0.getExternalFilesDir(null), "novideo").exists()
    }
    androidx.compose.foundation.layout.Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = if (noVideo) null else player
                    useController = false
                    keepScreenOn = true
                    // Letterbox black comes from the view itself: nothing opaque may be drawn over the
                    // surface by Compose (the portrait page sits above it and is transparent there).
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
                // Reattach if the player instance was rebuilt (e.g. hardware-decoder toggle).
                if (!noVideo && view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (noVideo) {
            // Screenshot stand-in for channel video: a frame from "Big Buck Bunny" (CC BY 3.0, Blender
            // Foundation) placed in the app's files dir as novideo.jpg; plain dark if it's missing.
            val frame: androidx.compose.ui.graphics.ImageBitmap? = remember {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(java.io.File(ctx0.getExternalFilesDir(null), "novideo.jpg").path)
                        ?.asImageBitmap()
                }.getOrNull()
            }
            if (frame != null) androidx.compose.foundation.Image(
                frame, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clipToBounds(),
            ) else androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().drawBehind { drawRect(androidx.compose.ui.graphics.Color(0xFF17171A)) })
        }
    }
}

private fun android.content.Context.findActivity(): ComponentActivity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/** AES-128 key URIs of Jio's non-DRM "Fallback" HLS (same rule the companion server uses). */
private val JIO_KEY_HOST = Regex("(^|//)tv\\.media\\.jio\\.com/", RegexOption.IGNORE_CASE)

private fun isHlsKeyUri(uri: String): Boolean =
    uri.contains(".pkey", ignoreCase = true) || uri.contains("aes128.key", ignoreCase = true) ||
        JIO_KEY_HOST.containsMatchIn(uri)

/** TV: "Paused / Press OK to continue" once the controls layer has been closed while paused. */
@Composable
private fun TvPausedBadge(ui: PlayerUi, modifier: Modifier) {
    val ov = ui.overlay
    if (ov == PlayerOverlay.None || ov == PlayerOverlay.Banner) PausedBadge(touch = false, onPlay = {}, modifier = modifier)
}

/** Jio's recorded-schedule channels hand out a SonyLIV programme file instead of a live stream. */
private fun isScheduleFileUrl(url: String): Boolean =
    url.contains("slivcdn.com", ignoreCase = true) || url.contains("partner=jiotvvod", ignoreCase = true)
