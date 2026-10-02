package com.fenyx.jtv.ui.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.fenyx.jtv.data.AppUpdateManager
import com.fenyx.jtv.data.EpgSyncStatus
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvClock
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.closeOnOutsideTap
import com.fenyx.jtv.ui.components.keepTapsInside
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.launch

/** Which second-level picker / dialog is open. */
private enum class Sheet { None, Theme, Accent, StartWith, Quality, Language, PictureSize, Buffer, EpgUrl, Update, ConfirmSignOut, ConfirmChangeMethod }

/**
 * Settings, v2 "Everyday": a plain two-level list. Section labels, then rows of *label + current
 * value*; choosing a row opens a short list of choices (second level). Destructive actions ask first.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, mainViewModel: MainViewModel, onBack: (() -> Unit)? = null, onTab: ((com.fenyx.jtv.ui.main.PhoneTab) -> Unit)? = null) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val c = Jtv.colors
    val isTv = Jtv.isTv
    val form = Jtv.form

    val language by settingsManager.defaultLanguageFlow.collectAsState(initial = "hi")
    val quality by settingsManager.defaultQualityFlow.collectAsState(initial = "auto")
    val hwDecoder by settingsManager.hardwareDecoderFlow.collectAsState(initial = true)
    val tunneling by settingsManager.tunnelingFlow.collectAsState(initial = false)
    val playbackBufferSec by settingsManager.playbackBufferSecFlow.collectAsState(initial = 60)
    val playerResizeMode by settingsManager.playerResizeModeFlow.collectAsState(initial = 0)
    val epgMode by settingsManager.epgModeFlow.collectAsState(initial = false)
    val epgUrl by settingsManager.epgUrlFlow.collectAsState(initial = "https://avkb.short.gy/epg.xml.gz")
    val epgSyncStatus by mainViewModel.epgSyncStatus.collectAsState()
    val autoplayLastChannel by settingsManager.autoplayLastChannelFlow.collectAsState(initial = false)
    val groupLanguageVariants by settingsManager.groupLanguageVariantsFlow.collectAsState(initial = true)
    val setupMode by settingsManager.setupModeFlow.collectAsState(initial = null)
    val serverUrl by settingsManager.serverUrlFlow.collectAsState(initial = "")
    val themeMode by settingsManager.themeModeFlow.collectAsState(initial = null)
    val serverRefreshing by mainViewModel.serverRefreshing.collectAsState()
    val serverRefreshMsg by mainViewModel.serverRefreshMsg.collectAsState()

    // Updater states
    val updateInfo by mainViewModel.updateInfo.collectAsState()
    val isCheckingUpdate by mainViewModel.isCheckingUpdate.collectAsState()
    val isDownloadingUpdate by mainViewModel.isDownloadingUpdate.collectAsState()
    val updateDownloadProgress by mainViewModel.updateDownloadProgress.collectAsState()
    val updateDownloadedBytes by mainViewModel.updateDownloadedBytes.collectAsState()
    val updateTotalBytes by mainViewModel.updateTotalBytes.collectAsState()
    val updateStatusMessage by mainViewModel.updateStatusMessage.collectAsState()
    val updateError by mainViewModel.updateError.collectAsState()

    var sheet by remember { mutableStateOf(Sheet.None) }

    // LAN sync ("Devices")
    val syncDevices by com.fenyx.jtv.sync.LanSync.devices.collectAsState()
    val deviceName by com.fenyx.jtv.sync.LanSync.deviceName.collectAsState()
    val autoSync by com.fenyx.jtv.sync.LanSync.autoSync.collectAsState()
    val syncStatus by com.fenyx.jtv.sync.LanSync.status.collectAsState()
    var showPairFlow by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var forgetDevice by remember { mutableStateOf<com.fenyx.jtv.sync.LanSync.Device?>(null) }

    // Initial focus so the first D-pad press works on entry (previously nothing was focused).
    val firstItemFocus = remember { FocusRequester() }
    // Touch screens get no initial focus highlight; TV lands on the first setting.
    LaunchedEffect(Unit) { if (isTv) runCatching { firstItemFocus.requestFocus() } }

    val bufferOptions = listOf(
        30 to "Data saver (30 seconds)",
        60 to "Balanced (60 seconds)",
        90 to "Smooth (90 seconds)",
        120 to "Smoothest (120 seconds)"
    )
    val languages = listOf(
        "hi" to "Hindi", "en" to "English", "ta" to "Tamil", "te" to "Telugu",
        "kn" to "Kannada", "ml" to "Malayalam", "bn" to "Bengali", "mr" to "Marathi",
        "gu" to "Gujarati", "pa" to "Punjabi", "or" to "Odia", "as" to "Assamese"
    )
    val qualities = listOf(
        "auto" to "Automatic", "high" to "High (1080p)", "medium" to "Medium (720p)", "low" to "Low (480p)"
    )
    val resizeModes = listOf(
        0 to "Fit the screen (default)",
        3 to "Fill the screen (crops edges)",
        4 to "Zoom",
        1 to "Stretch to width",
        2 to "Stretch to height"
    )
    val themes = listOf("system" to "Same as device", "dark" to "Dark", "light" to "Light")
    // No stored choice = dark on TV, device setting on phone/tablet (see JioTVGoTVTheme).
    val themeValue = themeMode ?: if (isTv) "dark" else "system"
    val accentMode by settingsManager.accentFlow.collectAsState(initial = null)
    val accentValue = accentMode ?: "amber"
    val startOptions = listOf("list" to "Channel list", "last" to "Last channel")

    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val updateAvailable = updateInfo?.isUpdateAvailable == true

    // ─── Rows (built as data so the list stays a flat, keyed LazyColumn) ───
    val backup = rememberFavoritesBackup { id -> mainViewModel.getAllChannels().firstOrNull { it.id == id }?.name }
    val rows: List<SRow> = buildList {
        add(SRow.Section("General"))
        add(SRow.Item("theme", "Appearance", value = themes.first { it.first == themeValue }.second) { sheet = Sheet.Theme })
        add(SRow.Item("accent", "Accent colour", value = com.fenyx.jtv.theme.ACCENTS.firstOrNull { it.first == accentValue }?.second ?: "Amber",
            description = "Used for highlights, the channel number and progress") { sheet = Sheet.Accent })
        add(SRow.Item("start", "Start with", value = if (autoplayLastChannel) "Last channel" else "Channel list",
            description = "What you see when the app opens") { sheet = Sheet.StartWith })

        add(SRow.Section("Picture and sound"))
        add(SRow.Item("quality", "Picture quality", value = qualities.find { it.first == quality }?.second ?: "Automatic") { sheet = Sheet.Quality })
        add(SRow.Item("language", "Sound language", value = languages.find { it.first == language }?.second ?: language,
            description = "Preferred language when a channel has more than one") { sheet = Sheet.Language })
        add(SRow.Item("resize", "Picture size", value = resizeModes.find { it.first == playerResizeMode }?.second ?: "Fit the screen") { sheet = Sheet.PictureSize })
        add(SRow.Item("buffer", "Smooth playback", value = bufferOptions.find { it.first == playbackBufferSec }?.second ?: "$playbackBufferSec seconds",
            description = "More smoothness uses more memory") { sheet = Sheet.Buffer })

        add(SRow.Section("Programme guide"))
        add(SRow.Item("epg", "Programme guide", value = if (epgMode) "On" else "Off",
            description = "Show what's on now and later") { scope.launch { settingsManager.setEpgMode(!epgMode) } })
        add(SRow.Item("epgUrl", "Guide source", value = "Change", description = epgUrl) { sheet = Sheet.EpgUrl })
        add(SRow.Item("epgRefresh", "Update the guide now", value = when (epgSyncStatus) {
            EpgSyncStatus.IDLE -> "Update"
            EpgSyncStatus.DOWNLOADING -> "Downloading…"
            EpgSyncStatus.EXTRACTING -> "Unpacking…"
            EpgSyncStatus.PARSING -> "Reading…"
            EpgSyncStatus.COMPLETED -> "Done"
            EpgSyncStatus.ERROR -> "Failed, try again"
        }) {
            if (epgSyncStatus == EpgSyncStatus.IDLE || epgSyncStatus == EpgSyncStatus.COMPLETED || epgSyncStatus == EpgSyncStatus.ERROR) {
                mainViewModel.fetchEpg(forceRefresh = true)
            }
        })

        add(SRow.Section("Channels"))
        add(SRow.Item("variants", "Group languages together", value = if (groupLanguageVariants) "On" else "Off",
            description = "One entry per channel; pick the language while watching") {
            scope.launch { settingsManager.setGroupLanguageVariants(!groupLanguageVariants) }
        })

        add(SRow.Section("Favourites backup"))
        add(SRow.Item("favSave", "Back up favourites", value = "Save",
            description = "Saves them to Downloads, to restore after reinstalling") { backup.save() })
        add(SRow.Item("favRestore", "Restore favourites", value = "Restore",
            description = "Adds the favourites from your backup file") { backup.restore() })

        add(SRow.Section("Devices"))
        add(SRow.Item("devName", "This device's name", value = deviceName) { showNameDialog = true })
        add(SRow.Item("devPair", "Sync with another device", value = "",
            description = "Share favourites with a phone, tablet or TV on the same Wi-Fi") { showPairFlow = true })
        syncDevices.filter { it.paired }.forEach { d ->
            add(SRow.Item("dev:${d.id}", d.name, value = "Forget", description = pairedDeviceLine(d)) { forgetDevice = d })
        }
        add(SRow.Item("devAuto", "Sync favourites automatically", value = if (autoSync) "On" else "Off",
            description = "Changes on one device appear on the others") { com.fenyx.jtv.sync.LanSync.setAutoSync(!autoSync) })
        add(SRow.Item("devNow", "Sync now", value = syncStatus ?: "Sync") { com.fenyx.jtv.sync.LanSync.syncNow() })

        add(SRow.Section("If the picture has problems"))
        add(SRow.Item("hw", "Hardware decoder", value = if (hwDecoder) "On" else "Off",
            description = "Keep on for most TVs. Applies to the next channel.") {
            scope.launch { settingsManager.setHardwareDecoder(!hwDecoder) }
        })
        add(SRow.Item("tunnel", "Tunnelling", value = if (tunneling) "On" else "Off",
            description = "Keep off if the picture freezes or goes black. Applies to the next channel.") {
            scope.launch { settingsManager.setTunneling(!tunneling) }
        })

        add(SRow.Section("Account"))
        add(SRow.Item("method", "Sign-in method", value = when (setupMode) {
            "server" -> "Own server"
            "jtv" -> "Access code"
            else -> "Jio number"
        }, description = if (setupMode == "server") serverUrl.ifEmpty { "Server address not set" } else "Change how this device signs in") {
            sheet = Sheet.ConfirmChangeMethod
        })
        if (setupMode == "server" || setupMode == "jtv") {
            add(SRow.Item("refresh", "Refresh from server", value = if (serverRefreshing) "Refreshing…" else (serverRefreshMsg ?: "Refresh"),
                description = "Get the latest sign-in and channel list") { mainViewModel.refreshFromServer() })
        }
        add(SRow.Item("signout", "Sign out", value = "", description = "Removes your sign-in from this device") { sheet = Sheet.ConfirmSignOut })

        add(SRow.Section("About"))
        add(SRow.Item("version", "App version", value = if (versionName.isNotEmpty()) versionName else "") { })
        add(SRow.Item("update", "Check for updates", value = when {
            isDownloadingUpdate -> "${(updateDownloadProgress * 100).toInt()}%"
            isCheckingUpdate -> "Checking…"
            updateAvailable -> "Version ${updateInfo?.versionName} ready"
            else -> "Check now"
        }, description = when {
            isDownloadingUpdate -> "Downloading the update"
            updateAvailable -> "Choose to see what's new and install"
            updateStatusMessage != null -> updateStatusMessage
            else -> null
        }) {
            if (updateAvailable) sheet = Sheet.Update else mainViewModel.checkForUpdates(manual = true)
        })
    }

    val now = rememberMinuteClock()
    val gutter = when (form) { FormFactor.Tv -> 48.dp; FormFactor.Tablet -> 32.dp; else -> 16.dp }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Back && sheet != Sheet.None) {
                    if (!(sheet == Sheet.Update && isDownloadingUpdate)) sheet = Sheet.None
                    true
                } else false
            }
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = gutter, vertical = if (isTv) 27.dp else 8.dp),
            // Tablet: left-aligned under the title (the nav rail sits on the left); TV/phone fill anyway.
            horizontalAlignment = Alignment.Start,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // No Back button: phone has the bottom tab bar, tablet the navigation rail, TV the tabs.
                JText("Settings", if (isTv) 28.sp else 24.sp, Modifier.weight(1f), weight = FontWeight.Bold)
                if (isTv && onTab != null) com.fenyx.jtv.ui.main.TvTabs(com.fenyx.jtv.ui.main.PhoneTab.Settings, onTab, Modifier.padding(end = 20.dp))
                if (form != FormFactor.Phone) JtvClock(now, dateColor = c.t2, size = if (isTv) 34.sp else 28.sp)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .then(if (Jtv.isPhonePortrait) Modifier.fillMaxWidth() else Modifier.widthIn(max = 820.dp).fillMaxWidth())
                    .focusRestorer(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                itemsIndexed(rows, key = { _, r -> r.key }, contentType = { _, r -> r::class }) { _, r ->
                    when (r) {
                        is SRow.Section -> SettingsSection(r.title)
                        is SRow.Item -> SettingsRow(
                            label = r.label,
                            value = r.value,
                            description = r.description,
                            destructive = r.key == "signout",
                            modifier = if (r.key == "theme") Modifier.focusRequester(firstItemFocus) else Modifier,
                            onClick = r.onClick,
                        )
                    }
                }
            }
            if (isTv) {
                Spacer(Modifier.height(6.dp))
                KeyHint(listOf("OK" to "change", "Back" to "close settings"))
            }
        }

        // ─── Second level ───
        when (sheet) {
            Sheet.Accent -> PickerDialog("Accent colour", com.fenyx.jtv.theme.ACCENTS.map { it.first to it.second }, accentValue,
                onSelect = { v -> scope.launch { settingsManager.setAccent(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None },
                swatches = com.fenyx.jtv.theme.ACCENTS.associate { it.first to (if (c.isDark) it.third.first else it.third.second) })
            Sheet.Theme -> PickerDialog("Appearance", themes, themeValue,
                onSelect = { v -> scope.launch { settingsManager.setThemeMode(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.StartWith -> PickerDialog("Start with", startOptions, if (autoplayLastChannel) "last" else "list",
                onSelect = { v -> scope.launch { settingsManager.setAutoplayLastChannel(v == "last") }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.Quality -> PickerDialog("Picture quality", qualities, quality,
                onSelect = { v -> scope.launch { settingsManager.setDefaultQuality(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.Language -> PickerDialog("Sound language", languages, language,
                onSelect = { v -> scope.launch { settingsManager.setDefaultLanguage(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.PictureSize -> PickerDialog("Picture size", resizeModes.map { it.first.toString() to it.second }, playerResizeMode.toString(),
                onSelect = { v -> scope.launch { settingsManager.setPlayerResizeMode(v.toInt()) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.Buffer -> PickerDialog("Smooth playback", bufferOptions.map { it.first.toString() to it.second }, playbackBufferSec.toString(),
                onSelect = { v -> scope.launch { settingsManager.setPlaybackBufferSec(v.toInt()) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.EpgUrl -> EpgUrlDialog(
                initial = epgUrl,
                onSave = { url -> scope.launch { settingsManager.setEpgUrl(url) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.ConfirmSignOut -> ConfirmDialog(
                title = "Sign out?",
                message = "You will need to sign in again to watch.",
                confirm = "Sign out",
                onConfirm = { sheet = Sheet.None; scope.launch { settingsManager.clearAuthData() } },
                onDismiss = { sheet = Sheet.None })
            // Returning to the chooser = clear credentials + reset the chosen mode.
            Sheet.ConfirmChangeMethod -> ConfirmDialog(
                title = "Change sign-in method?",
                message = "This signs you out. You will choose how to sign in again.",
                confirm = "Sign out and change",
                onConfirm = {
                    sheet = Sheet.None
                    scope.launch {
                        settingsManager.setSetupMode(null)
                        settingsManager.clearAuthData()
                    }
                },
                onDismiss = { sheet = Sheet.None })
            Sheet.Update -> updateInfo?.let { info ->
                Dialog(
                    onDismissRequest = { if (!isDownloadingUpdate) sheet = Sheet.None },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    UpdateDialog(
                        updateInfo = info,
                        isDownloading = isDownloadingUpdate,
                        downloadProgress = updateDownloadProgress,
                        downloadedBytes = updateDownloadedBytes,
                        totalBytes = updateTotalBytes,
                        errorMessage = updateError,
                        onDownloadAndInstall = { mainViewModel.downloadAndInstallUpdate(context) },
                        onDismiss = { sheet = Sheet.None }
                    )
                }
            }
            Sheet.None -> Unit
        }

        // ─── Devices (LAN sync) ───
        if (showPairFlow) SyncWithDeviceDialog(onDismiss = { showPairFlow = false })
        if (showNameDialog) DeviceNameDialog(deviceName,
            onSave = { n -> com.fenyx.jtv.sync.LanSync.setDeviceName(n); showNameDialog = false },
            onDismiss = { showNameDialog = false })
        forgetDevice?.let { d ->
            ConfirmDialog(
                title = "Forget ${d.name}?",
                message = "Favourites stop syncing with it. You can pair again later.",
                confirm = "Forget",
                onConfirm = { com.fenyx.jtv.sync.LanSync.forget(d.id); forgetDevice = null },
                onDismiss = { forgetDevice = null })
        }
    }
}

private sealed interface SRow {
    val key: String

    data class Section(val title: String) : SRow {
        override val key get() = "section:$title"
    }

    class Item(
        override val key: String,
        val label: String,
        val value: String,
        val description: String? = null,
        val onClick: () -> Unit,
    ) : SRow
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = textStyle(14.sp, FontWeight.SemiBold),
        color = Jtv.colors.t3,
        modifier = Modifier.padding(top = if (Jtv.isTv) 20.dp else 14.dp, bottom = 4.dp, start = 12.dp)
    )
}

/** One first-level row: label (+ optional short explanation) on the left, current value on the right. */
@Composable
private fun SettingsRow(
    label: String,
    value: String,
    description: String?,
    destructive: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Jtv.colors
    val isTv = Jtv.isTv
    JtvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = if (isTv) 56.dp else 52.dp),
        focusedScale = 1.02f,
    ) { focused ->
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = textStyle(if (isTv) 18.sp else 16.sp, FontWeight.SemiBold),
                    color = when {
                        focused -> c.invTx
                        destructive -> c.error
                        else -> c.tx
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        description,
                        style = textStyle(if (isTv) 14.sp else 13.sp),
                        color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (value.isNotEmpty()) {
                Text(
                    value,
                    style = textStyle(if (isTv) 16.sp else 15.sp),
                    color = if (focused) c.invTx else c.t2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp)
                )
            }
        }
    }
}

/**
 * Kept public for the player's options panel (TvPlayerScreen imports it). Same look as a settings row.
 * [valueColor] is honoured when given; otherwise the value uses the secondary text colour.
 */
@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    value: String = "",
    valueColor: Color = Color.Unspecified,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = if (Jtv.isTv) 56.dp else 64.dp),
        container = c.s1,
        focusedScale = 1.02f,
    ) { focused ->
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = textStyle(18.sp, FontWeight.SemiBold), color = if (focused) c.invTx else c.tx, maxLines = 1)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = textStyle(14.sp), color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2, maxLines = 2)
                }
            }
            val vc = if (focused) c.invTx else if (valueColor != Color.Unspecified) valueColor else c.t2
            if (icon != null) {
                androidx.tv.material3.Icon(icon, contentDescription = null, tint = vc, modifier = Modifier.size(24.dp))
            }
            if (value.isNotEmpty()) {
                Text(value, style = textStyle(16.sp, FontWeight.SemiBold), color = vc, maxLines = 1)
            }
        }
    }
}

// ───────────────────────── Dialogs ─────────────────────────

@Composable
internal fun DialogPanel(onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp = 480.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Jtv.colors
    val isPhone = Jtv.isPhonePortrait
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.8f))
                .then(if (Jtv.isTv) Modifier else Modifier.closeOnOutsideTap(onDismiss)).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .then(if (Jtv.isTv) Modifier else Modifier.keepTapsInside())
                    .then(if (isPhone) Modifier.fillMaxWidth() else Modifier.width(width))
                    // Short screens (phone landscape): the whole panel scrolls instead of being cut off.
                    .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.s1)
                    .verticalScroll(rememberScrollState())
                    .padding(if (Jtv.isPhoneLandscape) 18.dp else 24.dp),
                content = content
            )
        }
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    currentValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    swatches: Map<String, Color>? = null,
) {
    val c = Jtv.colors
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { selectedFocus.requestFocus() } }
    DialogPanel(onDismiss) {
        Text(title, style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val hasSelected = options.any { it.first == currentValue }
            options.forEachIndexed { i, (value, label) ->
                val isSelected = value == currentValue
                JtvClickable(
                    onClick = { onSelect(value) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (Jtv.isTv) 52.dp else 60.dp)
                        .then(if (isSelected || (!hasSelected && i == 0)) Modifier.focusRequester(selectedFocus) else Modifier),
                    container = if (isSelected) c.s2 else Color.Transparent,
                ) { focused ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp).align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        swatches?.get(value)?.let { sw ->
                            Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(sw))
                            Spacer(Modifier.width(14.dp))
                        }
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            style = textStyle(18.sp, if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (focused) c.invTx else c.tx,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Text("Selected", style = textStyle(14.sp), color = if (focused) c.invTx else c.t2)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        JtvButton("Cancel", onDismiss)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Jtv.colors
    // Focus starts on Cancel: nothing destructive happens on a single accidental OK press.
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    DialogPanel(onDismiss) {
        Text(title, style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
        Spacer(Modifier.height(8.dp))
        Text(message, style = textStyle(18.sp), color = c.t2)
        Spacer(Modifier.height(24.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JtvButton("Cancel", onDismiss, Modifier.focusRequester(cancelFocus), fontSize = 18.sp)
            JtvButton(confirm, onConfirm, primary = true, fontSize = 18.sp)
        }
    }
}

@Composable
private fun EpgUrlDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Jtv.colors
    var tempUrl by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val urlFieldFocus = remember { FocusRequester() }
    val epgKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { urlFieldFocus.requestFocus() }
        kotlinx.coroutines.delay(50)
        epgKeyboard?.show() // TV: focus alone doesn't open the on-screen keyboard
    }
    DialogPanel(onDismiss, width = 560.dp) {
        Text("Guide source", style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
        Spacer(Modifier.height(16.dp))
        Text("Web address", style = textStyle(16.sp, FontWeight.SemiBold), color = c.t2)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(c.bg, RoundedCornerShape(8.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) c.acc else c.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = tempUrl,
                onValueChange = { tempUrl = it },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusRequester(urlFieldFocus),
                textStyle = textStyle(18.sp).copy(color = c.tx),
                cursorBrush = SolidColor(c.acc),
                singleLine = true
            )
        }
        Spacer(Modifier.height(24.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JtvButton("Cancel", onDismiss, fontSize = 18.sp)
            JtvButton("Save", { onSave(tempUrl) }, primary = true, fontSize = 18.sp)
        }
    }
}

@Composable
private fun UpdateDialog(
    updateInfo: AppUpdateManager.UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    errorMessage: String?,
    onDownloadAndInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = Jtv.colors
    val isPhone = Jtv.isPhonePortrait
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.8f)).padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .then(if (isPhone) Modifier.fillMaxWidth() else Modifier.width(560.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(c.s1)
                .padding(24.dp)
        ) {
            Text("Update available", style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
            Text("Version ${updateInfo.versionName}", style = textStyle(16.sp), color = c.t2)
            Spacer(Modifier.height(16.dp))
            Text("What's new", style = textStyle(16.sp, FontWeight.SemiBold), color = c.tx)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.bg)
                    .padding(12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = updateInfo.changelog.ifBlank { "Performance improvements and bug fixes." },
                            style = textStyle(16.sp),
                            color = c.t2
                        )
                    }
                }
            }

            if (updateInfo.apkSize > 0) {
                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", updateInfo.apkSize / (1024.0 * 1024.0))
                Spacer(Modifier.height(8.dp))
                Text("Download size: $sizeMb", style = textStyle(14.sp), color = c.t2)
            }

            if (isDownloading) {
                Spacer(Modifier.height(16.dp))
                val dlMb = String.format(java.util.Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                val totMb = if (totalBytes > 0) String.format(java.util.Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0)) else ""
                Text(
                    "Downloading… ${(downloadProgress * 100).toInt()}% ($dlMb / $totMb)",
                    style = textStyle(16.sp), color = c.tx
                )
                Spacer(Modifier.height(8.dp))
                JtvProgress(downloadProgress.coerceIn(0f, 1f), height = 6.dp)
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(errorMessage, style = textStyle(16.sp), color = c.error)
            }

            Spacer(Modifier.height(24.dp))
            if (!isDownloading) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JtvButton("Download and install", onDownloadAndInstall, Modifier.focusRequester(initialFocus), primary = true, fontSize = 18.sp)
                    JtvButton("Later", onDismiss, fontSize = 18.sp)
                }
            } else {
                Text("Please wait. The installer opens when the download finishes.", style = textStyle(16.sp), color = c.t2)
            }
        }
    }
}
