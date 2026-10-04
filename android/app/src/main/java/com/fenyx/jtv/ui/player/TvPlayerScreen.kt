package com.fenyx.jtv.ui.player

import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.fenyx.jtv.theme.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.*
import com.fenyx.jtv.ui.settings.SettingsItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/** One selectable audio track from the currently playing stream. */
@androidx.annotation.OptIn(UnstableApi::class)
private data class AudioOption(
    val label: String,
    val group: androidx.media3.common.TrackGroup,
    val trackIndex: Int,
    val selected: Boolean
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
    val currentChannels = remember(currentGroup, allChannelsByGroup, channels) {
        val g = currentGroup
        if (g != null) (allChannelsByGroup[g] ?: channels) else channels
    }
    val currentChannel = remember(currentIndex, currentChannels) { currentChannels.getOrNull(currentIndex) }

    // Collapsed per-language feeds for the channel on screen (empty when it isn't a language family).
    val currentVariants = remember(currentChannel) { currentChannel?.let { variantsFor(it.id) } ?: emptyList() }
    // When the user picks a different language we play that sibling channel_id without disturbing the
    // visible channel list. Reset whenever the logical channel changes (a zap). `playingChannel` is
    // derived lower down, once `language` (the preferred audio language) is in scope.
    var langOverride by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(currentChannel) { langOverride = null }
    var showLangSelector by remember { mutableStateOf(false) }

    var showOverlay by remember { mutableStateOf(true) }
    var showChannelList by remember { mutableStateOf(false) }
    var showCategoryList by remember { mutableStateOf(false) }
    var showSettingsOverlay by remember { mutableStateOf(false) }
    
    val settingsManager = remember { SettingsManager(context) }
    val favoriteChannels by settingsManager.favoriteChannelsFlow.collectAsState(initial = emptySet())
    val playerSetupMode by settingsManager.setupModeFlow.collectAsState(initial = null)
    // "Refresh Login" (server mode) state shown in the right-side overlay.
    var refreshingCreds by remember { mutableStateOf(false) }

    // Auto-hide overlay
    LaunchedEffect(showOverlay) {
        if (showOverlay && !showChannelList && !showCategoryList) {
            delay(5000)
            showOverlay = false
        }
    }

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
    
    var showAudioSelector by remember { mutableStateOf(false) }
    var showQualitySelector by remember { mutableStateOf(false) }

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
    // "Recorded schedule" channels (e.g. Sony Yay): Jio's geturl returns a SonyLIV VOD file for the
    // programme on air (slivcdn / partner=jiotvvod), not a live stream. We start it at the scheduled
    // point and load the next programme's file when it ends, instead of playing it from 0:00 forever.
    var vodProgramEndMs by remember { mutableStateOf(0L) }
    val vodPendingSeekMs = remember { java.util.concurrent.atomic.AtomicLong(-1L) }
    // True when the user paused via long-press OK.
    var userPaused by remember { mutableStateOf(false) }
    // Non-null only when playback has failed and auto-recovery has been exhausted.
    var playbackError by remember { mutableStateOf<String?>(null) }

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
    val upIsNextChannel by settingsManager.upIsNextChannelFlow.collectAsState(initial = true)
    val reverseChPlusMinus by settingsManager.reverseChPlusMinusFlow.collectAsState(initial = false)

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
                            val label = f.label
                                ?: lang?.takeIf { it.isNotBlank() && it != "und" }?.let {
                                    runCatching { java.util.Locale(it).displayLanguage }
                                        .getOrNull()?.replaceFirstChar { c -> c.uppercase() }
                                }
                                ?: "Audio ${i + 1}"
                            diag.append("\n  [${lang ?: "?"}] label=${f.label} codec=${f.codecs ?: f.sampleMimeType} " +
                                "ch=${f.channelCount} supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}")
                            // Keep offering every track (don't hide unsupported ones — that would look
                            // like the language is "missing"); the log records support so we can tell
                            // whether a silent track is an unsupported codec vs. genuinely absent.
                            opts.add(AudioOption(label, g.mediaTrackGroup, i, g.isTrackSelected(i)))
                        }
                    }
                }
                android.util.Log.d("TvPlayerAudio", "audio tracks (${opts.size} playable):$diag")
                audioTracks = opts

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
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    // Playback recovered -> clear any error and reset the recovery budget so the
                    // next expiry (minutes/hours later) gets a fresh set of retries.
                    retryCount.intValue = 0
                    playbackError = null
                    val seek = vodPendingSeekMs.getAndSet(-1L)
                    val dur = exoPlayer.duration
                    if (seek > 0 && !exoPlayer.isCurrentMediaItemLive && (dur <= 0 || seek < dur - 5_000)) {
                        android.util.Log.d("TvPlayer", "Recorded schedule: start at ${seek / 1000}s of ${dur / 1000}s")
                        exoPlayer.seekTo(seek)
                    }
                }
                if (state == Player.STATE_ENDED && vodProgramEndMs > 0) {
                    // The programme's file finished: ask Jio again, it now returns the next one.
                    android.util.Log.d("TvPlayer", "Recorded schedule: programme ended, loading next")
                    retryCount.intValue = 0
                    streamRefreshTrigger++
                }
            }
            override fun onPlayerError(error: PlaybackException) {
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
                    playbackError = "Playback stopped. Press OK to retry."
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Recorded schedule: when the guide says this programme is over, fetch the next one's file even if
    // the file itself runs longer than its slot (keeps the "channel" in step with Jio's schedule).
    LaunchedEffect(vodProgramEndMs, playingChannel) {
        val end = vodProgramEndMs
        if (end <= 0 || end == Long.MAX_VALUE) return@LaunchedEffect
        val wait = end + 2_000 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (vodProgramEndMs == end) {
            android.util.Log.d("TvPlayer", "Recorded schedule: slot over, loading next programme")
            streamRefreshTrigger++
        }
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
                playbackError = "Not logged in. Please sign in again."
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
                keyHeadersHolder.set(if (streamData.isMpd) emptyMap() else streamData.licenseHeaders)

                android.util.Log.d("TvPlayer", "Loading stream: $finalUrl (isMpd: ${streamData.isMpd})")

                val isRecordedSchedule = !streamData.isMpd &&
                    (finalUrl.contains("slivcdn.com") || finalUrl.contains("partner=jiotvvod"))
                vodProgramEndMs = 0L
                vodPendingSeekMs.set(-1L)
                if (isRecordedSchedule) {
                    val now = System.currentTimeMillis()
                    val prog = runCatching {
                        com.fenyx.jtv.data.EpgRepository(context).getNativeEpgForChannel(ch.id)
                    }.getOrNull()?.firstOrNull { it.startMs <= now && now < it.stopMs }
                    if (prog != null) {
                        vodPendingSeekMs.set(now - prog.startMs)
                        vodProgramEndMs = prog.stopMs
                    } else {
                        vodProgramEndMs = Long.MAX_VALUE // unknown schedule: still advance on STATE_ENDED
                    }
                }

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

                val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

                withContext(kotlinx.coroutines.Dispatchers.Main) {
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
                    playbackError = fetchErr
                } else if (fetchEx is com.fenyx.jtv.data.JioApiClient.ChannelBlockedException ||
                    fetchEx is com.fenyx.jtv.data.JioApiClient.ChannelUnavailableException) {
                    isBuffering = false
                    playbackError = "$fetchErr\n\nPress OK to retry, or CH+/CH− for another channel."
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
                            "Server login expired. Re-login the Jio account on your JTV server, then press OK."
                        authExpired -> "Login expired. Please sign in again."
                        else -> "Couldn't load this channel. Press OK to retry."
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
                        playbackError = "Playback stopped. Press OK to retry."
                    }
                }
            }
        }
    }

    // READY-state stall watchdog (issue #3). The buffering watchdog above only acts in STATE_BUFFERING,
    // but a stream can also just stop in STATE_READY: no error, no spinner, the clock simply stops
    // (e.g. the AudioTrack stops consuming, so the audio clock that drives video stops too). One cheap
    // coroutine samples progress every 2 s while playback should be running; after 8 s with no
    // progress it re-attaches the audio effect (audioKick), and if that hasn't helped 6 s later it
    // soft re-prepares the same channel (streamRefreshTrigger). It reuses the per-channel kickCount and
    // the retryCount budget, so it can't loop faster than the existing recovery.
    LaunchedEffect(exoPlayer, playingChannel) {
        if (playingChannel == null) return@LaunchedEffect
        val detector = com.fenyx.jtv.player.PlaybackStallDetector()
        val window = androidx.media3.common.Timeline.Window()
        while (true) {
            delay(2_000)
            val shouldBePlaying = exoPlayer.playWhenReady &&
                exoPlayer.playbackState == Player.STATE_READY &&
                !userPaused && playbackError == null
            // For live HLS/DASH the position inside the sliding window can stay roughly constant
            // (the window moves with us), so measure the position in the period instead.
            val timeline = exoPlayer.currentTimeline
            val windowOffsetMs = if (!timeline.isEmpty) {
                timeline.getWindow(exoPlayer.currentMediaItemIndex, window).positionInFirstPeriodMs
            } else 0L
            val positionMs = exoPlayer.currentPosition + windowOffsetMs
            val frames = exoPlayer.videoDecoderCounters?.let {
                it.ensureUpdated(); it.renderedOutputBufferCount.toLong()
            } ?: -1L
            when (detector.onSample(android.os.SystemClock.elapsedRealtime(), shouldBePlaying, positionMs, frames)) {
                com.fenyx.jtv.player.PlaybackStallDetector.Action.KICK_AUDIO -> {
                    if (kickCount.intValue < 3) {
                        kickCount.intValue++
                        android.util.Log.w("TvPlayer", "Stall watchdog: no progress in READY at ${positionMs}ms, audio kick ${kickCount.intValue}")
                        audioKick++
                    } else {
                        // Kick budget for this channel is used up: go straight to the re-prepare step.
                        android.util.Log.w("TvPlayer", "Stall watchdog: no progress in READY at ${positionMs}ms, kicks exhausted")
                        if (retryCount.intValue < 5) {
                            retryCount.intValue++
                            android.util.Log.w("TvPlayer", "Stall watchdog: re-prepare ${retryCount.intValue}")
                            streamRefreshTrigger++
                        } else {
                            playbackError = "Playback stopped. Press OK to retry."
                        }
                        detector.reset()
                    }
                }
                com.fenyx.jtv.player.PlaybackStallDetector.Action.REPREPARE -> {
                    if (retryCount.intValue < 5) {
                        retryCount.intValue++
                        android.util.Log.w("TvPlayer", "Stall watchdog: still stuck after audio kick, re-prepare ${retryCount.intValue}")
                        streamRefreshTrigger++
                    } else {
                        android.util.Log.w("TvPlayer", "Stall watchdog: recovery exhausted")
                        playbackError = "Playback stopped. Press OK to retry."
                    }
                    detector.reset()
                }
                com.fenyx.jtv.player.PlaybackStallDetector.Action.NONE -> Unit
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
                val newToken = com.fenyx.jtv.data.JioApiClient.extractHdneaToken(res.getOrNull()!!.streamUrl)
                if (newToken.isNotEmpty()) {
                    tokenHolder.set(newToken)
                    android.util.Log.d("TvPlayer", "Token refreshed for channel ${ch.channelNumber}")
                }
            }
        }
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
                Lifecycle.Event.ON_START -> if (!userPausedState.value) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Current time. Formatter is hoisted (was reallocated on every 30s tick).
    val clockFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = clockFormat.format(Date())
            delay(30000)
        }
    }

    // ─── Key handler ───
    var numericBuffer by remember { mutableStateOf("") }
    var showNumericOverlay by remember { mutableStateOf(false) }
    var numericJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Long-press OK to pause/resume. A short OK still toggles the info overlay (or resumes if paused).
    var centerLongFired by remember { mutableStateOf(false) }
    var centerLongJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var listSelectedIndex by remember { mutableIntStateOf(0) }
    var categorySelectedIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(showChannelList, currentChannels) {
        if (showChannelList) listSelectedIndex = currentIndex
    }
    LaunchedEffect(showCategoryList, groups) {
        if (showCategoryList) categorySelectedIndex = groups.indexOf(currentGroup).coerceAtLeast(0)
    }

    fun commitNumericEntry() {
        val num = numericBuffer.toIntOrNull()
        if (num != null && currentChannels.isNotEmpty()) {
            val idx = (num - 1).coerceIn(0, currentChannels.size - 1)
            currentIndex = idx
        }
        numericBuffer = ""
        showNumericOverlay = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                // ── Long-press OK = pause/resume (only in the normal watching view) ──
                val isCenter = keyEvent.key == Key.Enter ||
                    keyEvent.key == Key.DirectionCenter ||
                    keyEvent.key == Key.NumPadEnter
                val normalWatching = !showCategoryList && !showChannelList &&
                    !showAudioSelector && !showQualitySelector && !showSettingsOverlay &&
                    !showLangSelector && !showNumericOverlay && playbackError == null
                if (isCenter && normalWatching) {
                    when (keyEvent.type) {
                        KeyEventType.KeyDown -> {
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                centerLongFired = false
                                centerLongJob?.cancel()
                                centerLongJob = scope.launch {
                                    delay(450) // long-press threshold
                                    centerLongFired = true
                                    if (userPaused) { exoPlayer.play(); userPaused = false }
                                    else { exoPlayer.pause(); userPaused = true; showOverlay = true }
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                        KeyEventType.KeyUp -> {
                            centerLongJob?.cancel()
                            if (!centerLongFired) {
                                // Short press: resume if paused, else toggle the info overlay.
                                if (userPaused) { exoPlayer.play(); userPaused = false }
                                else showOverlay = !showOverlay
                            }
                            centerLongFired = false
                            return@onPreviewKeyEvent true
                        }
                        else -> return@onPreviewKeyEvent true
                    }
                }

                if (keyEvent.type == KeyEventType.KeyDown) {
                    val digit = when (keyEvent.key) {
                        Key.Zero -> 0; Key.One -> 1; Key.Two -> 2; Key.Three -> 3
                        Key.Four -> 4; Key.Five -> 5; Key.Six -> 6; Key.Seven -> 7
                        Key.Eight -> 8; Key.Nine -> 9
                        else -> null
                    }
                    if (digit != null) {
                        if (!(numericBuffer.isEmpty() && digit == 0) && numericBuffer.length < 4) {
                            numericBuffer += digit.toString()
                            showNumericOverlay = true
                            numericJob?.cancel()
                            numericJob = scope.launch {
                                delay(1200)
                                commitNumericEntry()
                            }
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (keyEvent.key == Key.Back || keyEvent.key == Key.Escape) {
                        if (showAudioSelector) { showAudioSelector = false; return@onPreviewKeyEvent true }
                        if (showLangSelector) { showLangSelector = false; return@onPreviewKeyEvent true }
                        if (showQualitySelector) { showQualitySelector = false; return@onPreviewKeyEvent true }
                        if (showSettingsOverlay) { showSettingsOverlay = false; return@onPreviewKeyEvent true }
                        if (showCategoryList) { showCategoryList = false; return@onPreviewKeyEvent true }
                        if (showChannelList) { showChannelList = false; return@onPreviewKeyEvent true }
                        if (showNumericOverlay) { showNumericOverlay = false; numericBuffer = ""; return@onPreviewKeyEvent true }
                        return@onPreviewKeyEvent false
                    }

                    when (keyEvent.key) {
                        Key.ChannelUp -> {
                            if (currentChannels.isNotEmpty() && !showSettingsOverlay) {
                                val step = if (reverseChPlusMinus) -1 else 1
                                currentIndex = (currentIndex + step + currentChannels.size) % currentChannels.size
                                showOverlay = true
                            }
                            true
                        }
                        Key.ChannelDown -> {
                            if (currentChannels.isNotEmpty() && !showSettingsOverlay) {
                                val step = if (reverseChPlusMinus) 1 else -1
                                currentIndex = (currentIndex + step + currentChannels.size) % currentChannels.size
                                showOverlay = true
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (showSettingsOverlay) {
                                false
                            } else if (showCategoryList) {
                                if (groups.isNotEmpty()) {
                                    categorySelectedIndex = (categorySelectedIndex - 1 + groups.size) % groups.size
                                }
                                true
                            } else if (showChannelList) {
                                if (currentChannels.isNotEmpty()) {
                                    listSelectedIndex = (listSelectedIndex - 1 + currentChannels.size) % currentChannels.size
                                }
                                true
                            } else {
                                if (currentChannels.isNotEmpty()) {
                                    val step = if (upIsNextChannel) 1 else -1
                                    currentIndex = (currentIndex + step + currentChannels.size) % currentChannels.size
                                    showOverlay = true
                                }
                                true
                            }
                        }
                        Key.DirectionDown -> {
                            if (showSettingsOverlay) {
                                false
                            } else if (showCategoryList) {
                                if (groups.isNotEmpty()) {
                                    categorySelectedIndex = (categorySelectedIndex + 1) % groups.size
                                }
                                true
                            } else if (showChannelList) {
                                if (currentChannels.isNotEmpty()) {
                                    listSelectedIndex = (listSelectedIndex + 1) % currentChannels.size
                                }
                                true
                            } else {
                                if (currentChannels.isNotEmpty()) {
                                    val step = if (upIsNextChannel) -1 else 1
                                    currentIndex = (currentIndex + step + currentChannels.size) % currentChannels.size
                                    showOverlay = true
                                }
                                true
                            }
                        }
                        Key.DirectionLeft -> {
                            if (showSettingsOverlay) {
                                showSettingsOverlay = false
                                true
                            } else if (showCategoryList) {
                                false
                            } else if (showChannelList) {
                                showCategoryList = true
                                true
                            } else {
                                showChannelList = true
                                showOverlay = true
                                true
                            }
                        }
                        Key.DirectionRight -> {
                            if (showCategoryList) { showCategoryList = false; true }
                            else if (showChannelList) { showChannelList = false; true }
                            else if (!showSettingsOverlay) { showSettingsOverlay = true; true }
                            else false
                        }
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            if (showCategoryList) {
                                val group = groups.getOrNull(categorySelectedIndex)
                                if (group != null) {
                                    currentGroup = group // currentChannels derives from this
                                    currentIndex = 0
                                    showCategoryList = false
                                }
                                true
                            } else if (showChannelList) {
                                currentIndex = listSelectedIndex
                                showChannelList = false
                                showCategoryList = false
                                true
                            } else if (playbackError != null) {
                                // Manual retry after auto-recovery was exhausted.
                                playbackError = null
                                retryCount.intValue = 0
                                isBuffering = true
                                streamRefreshTrigger++
                                true
                            } else {
                                showOverlay = !showOverlay
                                true
                            }
                        }
                        Key.Back, Key.Escape -> {
                            if (showCategoryList) { showCategoryList = false; true }
                            else if (showChannelList) { showChannelList = false; true }
                            else if (showOverlay) { showOverlay = false; true }
                            else { onBack(); true }
                        }
                        // ── Standard TV media remote keys ──
                        // Play/Pause toggle from the dedicated remote key.
                        Key.MediaPlayPause -> {
                            if (userPaused) { exoPlayer.play(); userPaused = false }
                            else { exoPlayer.pause(); userPaused = true; showOverlay = true }
                            true
                        }
                        Key.MediaPlay -> {
                            if (userPaused) { exoPlayer.play(); userPaused = false }
                            true
                        }
                        Key.MediaPause -> {
                            exoPlayer.pause(); userPaused = true; showOverlay = true
                            true
                        }
                        // MEDIA_NEXT/PREVIOUS zap channels like CH+/-.
                        Key.MediaNext -> {
                            if (currentChannels.isNotEmpty() && !showSettingsOverlay) {
                                currentIndex = (currentIndex + 1) % currentChannels.size
                                showOverlay = true
                            }
                            true
                        }
                        Key.MediaPrevious -> {
                            if (currentChannels.isNotEmpty() && !showSettingsOverlay) {
                                currentIndex = (currentIndex - 1 + currentChannels.size) % currentChannels.size
                                showOverlay = true
                            }
                            true
                        }
                        // GUIDE opens the channel list; INFO toggles the now-playing banner.
                        Key.Guide -> {
                            if (!showSettingsOverlay) { showChannelList = true; showOverlay = true }
                            true
                        }
                        Key.Info -> {
                            if (!showChannelList && !showCategoryList && !showSettingsOverlay) {
                                showOverlay = !showOverlay
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
                // Reattach if the player instance was rebuilt (e.g. hardware-decoder toggle).
                if (view.player !== exoPlayer) view.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )

        // ─── Buffering Indicator ───
        if (isBuffering) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TvPrimary, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    val ch = currentChannel
                    Text(
                        ch?.name ?: "Loading...",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ─── Pause Indicator ───
        if (userPaused) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(width = 12.dp, height = 40.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White)
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 12.dp, height = 40.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White)
                        )
                    }
                }
            }
        }

        // ─── Playback Error Overlay ───
        if (playbackError != null && !isBuffering) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 48.dp)
                ) {
                    Text("⚠", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        playbackError ?: "",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        currentChannel?.name ?: "",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ─── Numeric Channel Entry Overlay ───
        if (showNumericOverlay && numericBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = numericBuffer,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )
            }
        }

        // ─── Channel Info Overlay (Top) ───
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    // Overscan-safe: keep the channel info off the panel edge (gradient still bleeds).
                    .padding(horizontal = TvDimens.OverscanHorizontal, vertical = TvDimens.OverscanVertical)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val ch = currentChannel
                    if (ch?.logoUrl?.isNotEmpty() == true) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(ch.logoUrl)
                                .size(96)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Channel number
                            Text(
                                String.format("%02d", currentIndex + 1),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                ch?.name ?: "",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isFav = favoriteChannels.contains(ch?.id)
                            Box(
                                modifier = Modifier
                                    .background(if (isFav) Color(0xFFFFD700).copy(alpha = 0.2f) else TvPrimary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (isFav) "⭐ FAVORITE" else "LIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isFav) Color(0xFFFFD700) else TvPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                ch?.group ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        currentTime,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Light
                    )
                }
            }
        }
        
        // ─── Player Settings Overlay (Right) ───
        val settingsFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(showSettingsOverlay) {
            if (showSettingsOverlay) {
                try {
                    settingsFocusRequester.requestFocus()
                } catch (e: Exception) { }
            } else {
                try {
                    focusRequester.requestFocus()
                } catch (e: Exception) { }
            }
        }

        AnimatedVisibility(
            visible = showSettingsOverlay,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(TvDarkSurface.copy(alpha = 0.95f))
                    .padding(24.dp)
            ) {
                Column(
                    // Scrollable so all items (incl. Open Settings at the bottom) are reachable; the
                    // focused item auto-scrolls into view as you press Down on the remote.
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Player Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    val chId = currentChannel?.id ?: ""
                    val isFav = favoriteChannels.contains(chId)
                    
                    val qualities = listOf("auto", "high", "medium", "low")
                    val languages = listOf("hi", "en", "ta", "te", "ml", "bn", "mr", "gu", "pa", "or", "as")

                    
                    Box(modifier = Modifier.focusRequester(settingsFocusRequester)) {
                        SettingsItem(
                            title = "Favorite Channel",
                            subtitle = if (isFav) "Remove from favorites" else "Add to favorites",
                            value = if (isFav) "★" else "☆",
                            valueColor = if (isFav) Color(0xFFFFD700) else TvOnSurfaceVariant,
                            onClick = {
                                scope.launch { settingsManager.toggleFavoriteChannel(chId) }
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    SettingsItem(
                        title = "Video Quality",
                        subtitle = "Current: $quality",
                        value = "Change",
                        valueColor = TvPrimary,
                        onClick = {
                            showQualitySelector = true
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Language-feed switch for collapsed channel families (e.g. Star Sports Hindi/Tamil).
                    // Selecting a language reloads that sibling feed. Distinct from in-stream audio tracks.
                    if (currentVariants.size > 1) {
                        val curLang = currentVariants.firstOrNull { it.channel.id == playingChannel?.id }?.langCode
                        SettingsItem(
                            title = "Language",
                            subtitle = "Switch language feed for this channel",
                            value = com.fenyx.jtv.data.ChannelLanguage.displayName(curLang),
                            valueColor = TvPrimary,
                            onClick = { showLangSelector = true }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    SettingsItem(
                        title = "Audio Track / Language",
                        subtitle = audioTracks.firstOrNull { it.selected }?.label?.let { "Current: $it" } ?: "Default",
                        value = if (audioTracks.size > 1) "${audioTracks.size} tracks" else "Change",
                        valueColor = TvPrimary,
                        onClick = {
                            showAudioSelector = true
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsItem(
                        title = "Voice Boost",
                        subtitle = "Suppress background & clear dialogue",
                        value = when (voiceBoost) { 0 -> "Off"; 1 -> "Low"; 2 -> "Medium"; 3 -> "High"; else -> "Max" },
                        valueColor = if (voiceBoost == 0) TvOnSurfaceVariant else TvPrimary,
                        onClick = {
                            val next = (voiceBoost + 1) % 5 // Off -> Low -> Medium -> High -> Max -> Off
                            scope.launch { settingsManager.setVoiceBoost(next) }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsItem(
                        title = "Auto Volume",
                        subtitle = "Normalize loudness across channels",
                        value = if (audioNormalize) "On" else "Off",
                        valueColor = if (audioNormalize) TvPrimary else TvOnSurfaceVariant,
                        onClick = {
                            scope.launch { settingsManager.setAudioNormalize(!audioNormalize) }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsItem(
                        title = "Sleep Timer",
                        subtitle = if (sleepTimerMin == 0) "Off" else "Turns off in $sleepTimerMin min",
                        value = if (sleepTimerMin == 0) "Off" else "$sleepTimerMin min",
                        valueColor = if (sleepTimerMin == 0) TvOnSurfaceVariant else TvPrimary,
                        onClick = {
                            // Cycle Off -> 15 -> 30 -> 60 -> Off
                            sleepTimerMin = when (sleepTimerMin) {
                                0 -> 15
                                15 -> 30
                                30 -> 60
                                else -> 0
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Server mode: force a credential re-pull from the proxy and reload the stream, so a
                    // rotated/expired shared token can be fixed without leaving the player.
                    if (playerSetupMode == "server" || playerSetupMode == "jtv") {
                        SettingsItem(
                            title = "Refresh Login",
                            subtitle = "Fetch fresh credentials from your server",
                            value = if (refreshingCreds) "Refreshing…" else "Refresh",
                            valueColor = TvPrimary,
                            onClick = {
                                if (!refreshingCreds) scope.launch {
                                    refreshingCreds = true
                                    com.fenyx.jtv.data.JioApiClient.refreshCredentials(context)
                                    refreshingCreds = false
                                    // Reload the current stream with the fresh credentials.
                                    retryCount.intValue = 0
                                    playbackError = null
                                    isBuffering = true
                                    streamRefreshTrigger++
                                    showSettingsOverlay = false
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    SettingsItem(
                        title = "Open Settings",
                        subtitle = "Go to full app settings",
                        icon = Icons.Default.Settings,
                        valueColor = TvPrimary,
                        onClick = {
                            showSettingsOverlay = false
                            onSettings()
                        }
                    )
                }
            }
        }

        val qualityOptions = listOf(
            "auto" to "Auto", "high" to "High (1080p)", "medium" to "Medium (720p)", "low" to "Low (480p)"
        )
        val languageOptions = listOf(
            "hi" to "Hindi", "en" to "English", "ta" to "Tamil", "te" to "Telugu", 
            "ml" to "Malayalam", "bn" to "Bengali", "mr" to "Marathi", "gu" to "Gujarati", 
            "pa" to "Punjabi", "or" to "Oriya", "as" to "Assamese"
        )

        if (showQualitySelector) {
            Dialog(onDismissRequest = { showQualitySelector = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                TvPickerDialog(
                    title = "Select Quality",
                    options = qualityOptions,
                    currentValue = quality,
                    onSelect = { value ->
                        quality = value
                        scope.launch { settingsManager.setDefaultQuality(value) }
                        showQualitySelector = false
                    },
                    onDismiss = { showQualitySelector = false }
                )
            }
        }

        if (showAudioSelector) {
            Dialog(onDismissRequest = { showAudioSelector = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                if (audioTracks.isNotEmpty()) {
                    // Real tracks from the stream — reliable even when channels label languages oddly.
                    val opts = audioTracks.mapIndexed { i, t -> i.toString() to t.label }
                    val current = audioTracks.indexOfFirst { it.selected }.let { if (it >= 0) it.toString() else "" }
                    TvPickerDialog(
                        title = "Audio Track / Language",
                        options = opts,
                        currentValue = current,
                        onSelect = { value ->
                            val opt = value.toIntOrNull()?.let { audioTracks.getOrNull(it) }
                            if (opt != null) {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(
                                        androidx.media3.common.TrackSelectionOverride(opt.group, listOf(opt.trackIndex))
                                    )
                                    .build()
                                // Remember the chosen language as the default for other channels too.
                                opt.group.getFormat(opt.trackIndex).language?.let { lang ->
                                    language = lang
                                    scope.launch { settingsManager.setDefaultLanguage(lang) }
                                }
                            }
                            showAudioSelector = false
                        },
                        onDismiss = { showAudioSelector = false }
                    )
                } else {
                    // Fallback before tracks are known: pick a preferred language by code.
                    TvPickerDialog(
                        title = "Preferred Audio Language",
                        options = languageOptions,
                        currentValue = language,
                        onSelect = { value ->
                            language = value
                            scope.launch { settingsManager.setDefaultLanguage(value) }
                            showAudioSelector = false
                        },
                        onDismiss = { showAudioSelector = false }
                    )
                }
            }
        }

        if (showLangSelector) {
            Dialog(onDismissRequest = { showLangSelector = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                val opts = currentVariants.map { it.channel.id to com.fenyx.jtv.data.ChannelLanguage.displayName(it.langCode) }
                TvPickerDialog(
                    title = "Channel Language",
                    options = opts,
                    currentValue = playingChannel?.id ?: "",
                    onSelect = { value ->
                        val v = currentVariants.firstOrNull { it.channel.id == value }
                        if (v != null) {
                            langOverride = v.channel
                            // Remember the chosen language so other channels + this one default to it.
                            v.langCode?.let { lc ->
                                language = lc
                                scope.launch { settingsManager.setDefaultLanguage(lc) }
                            }
                        }
                        showLangSelector = false
                    },
                    onDismiss = { showLangSelector = false }
                )
            }
        }

        // ─── Bottom Hint Bar ───
        AnimatedVisibility(
            visible = showOverlay && !showChannelList && !showCategoryList,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    // Overscan-safe hint bar.
                    .padding(horizontal = TvDimens.OverscanHorizontal, vertical = TvDimens.SpaceMd)
            ) {
                Text(
                    "↑↓ / CH+- Change Channel  •  ← Channel List  •  0-9 Go To  •  OK Info  •  Back Exit",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // ─── Category List Sidebar (Far Left side) ───
        AnimatedVisibility(
            visible = showCategoryList,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            val catState = rememberLazyListState()
            LaunchedEffect(categorySelectedIndex) {
                catState.animateScrollToItem(categorySelectedIndex.coerceAtLeast(0))
            }
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        "Categories",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    LazyColumn(state = catState) {
                        itemsIndexed(items = groups, key = { _, group -> group }) { index: Int, group: String ->
                            val isSelected = index == categorySelectedIndex
                            val isCurrentGroup = group == currentGroup
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                onClick = {
                                    categorySelectedIndex = index
                                    currentGroup = group // currentChannels derives from this
                                    currentIndex = 0
                                    showCategoryList = false
                                },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) TvPrimaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                                    focusedContainerColor = TvDarkSurfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isCurrentGroup) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(18.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(TvPrimary)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        groupLabel(group),
                                        color = if (isSelected || isCurrentGroup) TvPrimary else Color.White,
                                        fontWeight = if (isSelected || isCurrentGroup) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ─── Channel List Sidebar (Left side, pushed by Category list if open) ───
        val channelListOffset by animateDpAsState(if (showCategoryList) 220.dp else 0.dp)
        AnimatedVisibility(
            visible = showChannelList,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = channelListOffset)
        ) {
            val listState = rememberLazyListState()
            LaunchedEffect(listSelectedIndex) {
                listState.animateScrollToItem(listSelectedIndex.coerceAtLeast(0))
            }

            Box(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        currentGroup?.let { groupLabel(it) } ?: "Channels",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    Text(
                        "← Categories  •  ${currentChannels.size} channels",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvOnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(state = listState) {
                        itemsIndexed(items = currentChannels, key = { _, channel -> channel.id }) { index: Int, channel: Channel ->
                            val isSelected = index == listSelectedIndex
                            val isPlaying = index == currentIndex
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                onClick = {
                                    listSelectedIndex = index
                                    currentIndex = index
                                    showChannelList = false
                                    showCategoryList = false
                                    showOverlay = true
                                },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) TvPrimaryContainer.copy(alpha = 0.4f) else Color.Transparent,
                                    focusedContainerColor = TvDarkSurfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Channel number
                                    Text(
                                        String.format("%02d", index + 1),
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (channel.logoUrl.isNotEmpty()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(channel.logoUrl)
                                                .size(64)
                                                .build(),
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp).clip(CircleShape)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.size(32.dp).clip(CircleShape).background(TvDarkSurfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.PlayArrow, null, tint = TvOnSurfaceVariant, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        channel.name,
                                        color = if (isPlaying) TvPrimary else Color.White,
                                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/** AES-128 key URIs of Jio's non-DRM "Fallback" HLS (same rule the companion server uses). */
private val JIO_KEY_HOST = Regex("(^|//)tv\\.media\\.jio\\.com/", RegexOption.IGNORE_CASE)

private fun isHlsKeyUri(uri: String): Boolean =
    uri.contains(".pkey", ignoreCase = true) || uri.contains("aes128.key", ignoreCase = true) ||
        JIO_KEY_HOST.containsMatchIn(uri)

/** Display name for a category, including the Home screen's "All" / "Favorites" pseudo-categories. */
private fun groupLabel(group: String): String = when (group) {
    com.fenyx.jtv.ui.main.MainViewModel.GROUP_ALL -> "All Channels"
    com.fenyx.jtv.ui.main.MainViewModel.GROUP_FAVORITES -> "★ Favorites"
    else -> group
}

@Composable
fun TvPickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    currentValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .background(com.fenyx.jtv.theme.TvDarkSurface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = com.fenyx.jtv.theme.TvOnBackground
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(options.size) { index ->
                    val (value, label) = options[index]
                    val isSelected = value == currentValue

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelect(value) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) com.fenyx.jtv.theme.TvPrimaryContainer.copy(alpha = 0.3f) else Color.Transparent,
                            focusedContainerColor = com.fenyx.jtv.theme.TvDarkSurfaceVariant
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(2.dp, com.fenyx.jtv.theme.TvPrimary),
                                shape = RoundedCornerShape(8.dp)
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                color = if (isSelected) com.fenyx.jtv.theme.TvPrimary else com.fenyx.jtv.theme.TvOnSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}
