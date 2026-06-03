package com.fenyx.jtv.ui.player

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import android.os.Build
import android.webkit.CookieManager
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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

    var currentGroup by remember { mutableStateOf<String?>(channels.firstOrNull()?.group) }
    var currentChannels by remember { mutableStateOf<List<Channel>>(channels) }
    var currentIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    val currentChannel by remember(currentIndex, currentChannels) {
        derivedStateOf { currentChannels.getOrNull(currentIndex) }
    }

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

    LaunchedEffect(Unit) {
        quality = settingsManager.defaultQualityFlow.first()
        language = settingsManager.defaultLanguageFlow.first()
        resizeMode = settingsManager.playerResizeModeFlow.first()
    }

    // Player state
    var isBuffering by remember { mutableStateOf(true) }
    // DRM fallback: when ExoPlayer fails twice on a channel, switch to WebView with /mpd/ URL
    var useDrmWebView by remember { mutableStateOf(false) }
    var drmWebViewUrl by remember { mutableStateOf("") }

    // ExoPlayer with automatic DRM fallback (matching reference app logic)
    val retryCount = remember { mutableIntStateOf(0) }
    var streamRefreshTrigger by remember { mutableIntStateOf(0) }

    // ExoPlayer is built using our custom factory to ensure Android TV optimizations
    val exoPlayer = remember {
        com.fenyx.jtv.player.JioExoPlayerFactory.create(context, language)
    }

    // Reuse data source factories to avoid GC pressure on every channel switch
    val httpDataSourceFactory = remember {
        DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    }
    val mediaSourceFactory = remember {
        DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)
    }

    LaunchedEffect(language, quality) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(language)
            
        when (quality) {
            "low" -> builder.setMaxVideoSize(854, 480)
            "medium" -> builder.setMaxVideoSize(1280, 720)
            "high" -> builder.setMaxVideoSize(1920, 1080)
            // CRITICAL for TV performance: "auto" should NOT attempt to play 1080p if it causes lag
            else -> builder.setMaxVideoSize(1280, 720)
        }
        
        exoPlayer.trackSelectionParameters = builder.build()
    }

    // Listen for errors
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
            override fun onPlayerError(error: PlaybackException) {
                // Token expiration (often 403 Forbidden) causes black screen.
                // We MUST re-fetch the stream URL entirely, not just retry the same expired URL.
                if (retryCount.intValue < 3) {
                    retryCount.intValue++
                    streamRefreshTrigger++
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentChannel, quality) {
        retryCount.intValue = 0 // Reset retries on intentional channel or quality change
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
                return@LaunchedEffect
            }
            
            val chNumber = ch.channelNumber.toString()
            android.util.Log.d("TvPlayer", "Fetching stream URL for channel $chNumber")
            
            val result = com.fenyx.jtv.data.JioApiClient.getStreamUrl(context, chNumber, authData)
            
            if (result.isSuccess) {
                val streamData = result.getOrNull()!!
                val finalUrl = streamData.streamUrl
                
                android.util.Log.d("TvPlayer", "Loading stream: $finalUrl (isMpd: ${streamData.isMpd})")

                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(finalUrl)
                    .setMimeType(if (streamData.isMpd) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)

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
                isBuffering = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Current time
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
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
                                    currentGroup = group
                                    currentChannels = allChannelsByGroup[group] ?: emptyList<Channel>()
                                    currentIndex = 0
                                    showCategoryList = false
                                }
                                true
                            } else if (showChannelList) {
                                currentIndex = listSelectedIndex
                                showChannelList = false
                                showCategoryList = false
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
                Column {
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
                        title = "Audio Track",
                        subtitle = "Current: $language",
                        value = "Change",
                        valueColor = TvPrimary,
                        onClick = {
                            showAudioSelector = true
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
                TvPickerDialog(
                    title = "Select Audio Language",
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
                                    currentGroup = group
                                    val newChannels = allChannelsByGroup[group] ?: emptyList<Channel>()
                                    currentChannels = newChannels
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
