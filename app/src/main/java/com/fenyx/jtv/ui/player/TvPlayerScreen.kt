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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Saveable so the current channel/group survive leaving the player (e.g. opening full Settings and
    // coming back) instead of resetting to the channel the player was originally launched with.
    var currentGroup by rememberSaveable { mutableStateOf(channels.firstOrNull()?.group) }
    var currentIndex by rememberSaveable { mutableIntStateOf(initialIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    // Derived from the saved group so it rebuilds correctly after a state restore.
    val currentChannels = remember(currentGroup, allChannelsByGroup, channels) {
        val g = currentGroup
        if (g != null) (allChannelsByGroup[g] ?: channels) else channels
    }
    val currentChannel = remember(currentIndex, currentChannels) { currentChannels.getOrNull(currentIndex) }

    var showOverlay by remember { mutableStateOf(true) }
    var showChannelList by remember { mutableStateOf(false) }
    var showCategoryList by remember { mutableStateOf(false) }
    var showSettingsOverlay by remember { mutableStateOf(false) }
    
    val settingsManager = remember { SettingsManager(context) }
    val favoriteChannels by settingsManager.favoriteChannelsFlow.collectAsState(initial = emptySet())

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

    LaunchedEffect(Unit) {
        quality = settingsManager.defaultQualityFlow.first()
        language = settingsManager.defaultLanguageFlow.first()
        resizeMode = settingsManager.playerResizeModeFlow.first()
    }

    // Player state
    var isBuffering by remember { mutableStateOf(true) }
    // Non-null only when playback has failed and auto-recovery has been exhausted.
    var playbackError by remember { mutableStateOf<String?>(null) }

    // Stream auto-recovery: Jio live URLs carry a short-lived Akamai cookie (__hdnea__) that expires
    // after a while, which surfaces as a sudden black screen. On error we re-fetch the stream URL
    // (which regenerates the cookie). The counter resets every time playback recovers (STATE_READY),
    // so a long session can recover indefinitely instead of dying after a fixed number of errors.
    val retryCount = remember { mutableIntStateOf(0) }
    var streamRefreshTrigger by remember { mutableIntStateOf(0) }

    // Player-affecting prefs. Read reactively (NEVER block the main thread here — doing so caused
    // jank/black-screen on entry). Tunneling is applied live via track-selection params below; the
    // hardware-decoder mode can only be set at construction, so the player is keyed on it.
    val tunnelingPref by settingsManager.tunnelingFlow.collectAsState(initial = false)
    val hardwareOnlyPref by settingsManager.hardwareDecoderFlow.collectAsState(initial = true)
    val bufferSecPref by settingsManager.playbackBufferSecFlow.collectAsState(initial = 60)

    // Audio enhancement settings + the effect engine.
    val voiceBoost by settingsManager.voiceBoostFlow.collectAsState(initial = 0)
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
            if (token.isEmpty()) {
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
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(language)
        // Tunneling is applied at construction (see remember key above), not here, because the base
        // TrackSelectionParameters.Builder doesn't expose setTunnelingEnabled in Media3 1.4.

        when (quality) {
            "low" -> builder.setMaxVideoSize(854, 480)
            "medium" -> builder.setMaxVideoSize(1280, 720)
            "high" -> builder.setMaxVideoSize(1920, 1080)
            // "auto": allow up to 1080p (sharp on a 50" panel + wired connection); ABR still scales
            // down automatically if bandwidth/decoder can't keep up.
            else -> builder.setMaxVideoSize(1920, 1080)
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    // Listen for errors (keyed on exoPlayer so a rebuilt player gets its own listener)
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val opts = mutableListOf<AudioOption>()
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
                            opts.add(AudioOption(label, g.mediaTrackGroup, i, g.isTrackSelected(i)))
                        }
                    }
                }
                audioTracks = opts
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    // Playback recovered -> clear any error and reset the recovery budget so the
                    // next expiry (minutes/hours later) gets a fresh set of retries.
                    retryCount.intValue = 0
                    playbackError = null
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

    LaunchedEffect(currentChannel, quality) {
        retryCount.intValue = 0 // Reset retries on intentional channel or quality change
        playbackError = null
    }

    LaunchedEffect(currentChannel, quality, streamRefreshTrigger) {
        val ch = currentChannel
        if (ch != null) {
            settingsManager.setLastChannelId(ch.id)
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
                        .build()
                    mediaItemBuilder.setDrmConfiguration(drmConfig)
                }

                httpDataSourceFactory.setDefaultRequestProperties(streamData.headers)

                val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            } else {
                android.util.Log.e("TvPlayer", "Failed to fetch stream: ${result.exceptionOrNull()?.message}")
                // Let the auto-recovery budget retry transient fetch failures; only show the error
                // once it's exhausted, so a one-off hiccup doesn't flash a message.
                if (retryCount.intValue >= 5) {
                    isBuffering = false
                    playbackError = "Couldn't load this channel. Press OK to retry."
                } else {
                    retryCount.intValue++
                    delay(800L * retryCount.intValue)
                    streamRefreshTrigger++
                }
            }
        }
    }

    // ─── Transparent token refresh ───
    // The Jio `__hdnea__` token expires ~120s after issue. This loop fetches a fresh stream URL a few
    // seconds BEFORE expiry and publishes the new token to tokenHolder, so the ResolvingDataSource
    // keeps rewriting requests with a valid token. Playback never sees a 403 -> no reload, no buffering.
    LaunchedEffect(currentChannel) {
        val ch = currentChannel ?: return@LaunchedEffect
        while (true) {
            val token = tokenHolder.get()
            val expSec = com.fenyx.jtv.data.JioApiClient.extractTokenExpiryEpochSec(token)
            val nowSec = System.currentTimeMillis() / 1000
            // Refresh 15s before expiry; if we can't read an expiry, re-check in 60s.
            val waitMs = if (expSec > 0) ((expSec - nowSec - 15) * 1000).coerceIn(5_000, 110_000)
                         else 60_000L
            delay(waitMs)

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
    LaunchedEffect(audioSessionId, voiceBoost, audioNormalize) {
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

    // Current time
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            delay(30000)
        }
    }

    // ─── Key handler ───
    var numericBuffer by remember { mutableStateOf("") }
    var showNumericOverlay by remember { mutableStateOf(false) }
    var numericJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
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
                                currentIndex = (currentIndex + 1) % currentChannels.size
                                showOverlay = true
                            }
                            true
                        }
                        Key.ChannelDown -> {
                            if (currentChannels.isNotEmpty() && !showSettingsOverlay) {
                                currentIndex = (currentIndex - 1 + currentChannels.size) % currentChannels.size
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
                                    currentIndex = (currentIndex - 1 + currentChannels.size) % currentChannels.size
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
                                    currentIndex = (currentIndex + 1) % currentChannels.size
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

        // ─── Playback Error Overlay ───
        if (playbackError != null && !isBuffering) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        playbackError ?: "",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
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
                    .padding(horizontal = 24.dp, vertical = 16.dp)
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
                    .padding(horizontal = 24.dp, vertical = 12.dp)
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
                                        group,
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
                        currentGroup ?: "Channels",
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
