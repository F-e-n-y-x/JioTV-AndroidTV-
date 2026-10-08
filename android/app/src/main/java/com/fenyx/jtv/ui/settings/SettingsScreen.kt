package com.fenyx.jtv.ui.settings

import com.fenyx.jtv.i18n.AppLocale
import com.fenyx.jtv.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
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
private enum class Sheet { None, Theme, DeviceType, AppLanguage, Accent, StartWith, Quality, Language, PictureSize, Buffer, EpgUrl, Update, ConfirmSignOut, ConfirmChangeMethod }

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

    // Remote buttons (TV, or a keyboard/D-pad attached) and picture-in-picture (phone/tablet).
    val showRemote = remoteButtonsAvailable()
    val remoteMap by settingsManager.remoteKeyMapFlow.collectAsState(initial = com.fenyx.jtv.data.RemoteKeys.Default)
    var remoteScreen by rememberSaveable { mutableStateOf(false) }
    var categoriesScreen by rememberSaveable { mutableStateOf(false) }
    val categoryGroups by mainViewModel.groups.collectAsState()
    val remoteRowFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val showPip = !isTv && com.fenyx.jtv.ui.player.Pip.supported(context)
    val pipOnLeave by settingsManager.pipOnLeaveFlow.collectAsState(initial = true)

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
        30 to stringResource(R.string.settings_buffer_30),
        60 to stringResource(R.string.settings_buffer_60),
        90 to stringResource(R.string.settings_buffer_90),
        120 to stringResource(R.string.settings_buffer_120)
    )
    val languages = listOf(
        "hi" to stringResource(R.string.settings_lang_hi), "en" to stringResource(R.string.settings_lang_en),
        "ta" to stringResource(R.string.settings_lang_ta), "te" to stringResource(R.string.settings_lang_te),
        "kn" to stringResource(R.string.settings_lang_kn), "ml" to stringResource(R.string.settings_lang_ml),
        "bn" to stringResource(R.string.settings_lang_bn), "mr" to stringResource(R.string.settings_lang_mr),
        "gu" to stringResource(R.string.settings_lang_gu), "pa" to stringResource(R.string.settings_lang_pa),
        "or" to stringResource(R.string.settings_lang_or), "as" to stringResource(R.string.settings_lang_as)
    )
    val qualities = listOf(
        "auto" to stringResource(R.string.settings_quality_auto), "high" to stringResource(R.string.settings_quality_high),
        "medium" to stringResource(R.string.settings_quality_medium), "low" to stringResource(R.string.settings_quality_low)
    )
    val resizeModes = listOf(
        0 to stringResource(R.string.settings_size_fit),
        3 to stringResource(R.string.settings_size_fill),
        4 to stringResource(R.string.settings_size_zoom),
        1 to stringResource(R.string.settings_size_stretch_width),
        2 to stringResource(R.string.settings_size_stretch_height)
    )
    val themes = listOf(
        "system" to stringResource(R.string.settings_theme_system),
        "dark" to stringResource(R.string.settings_theme_dark),
        "light" to stringResource(R.string.settings_theme_light),
    )
    // App language: Same as device / English / हिन्दी (English and Hindi are always shown in their own script).
    val appLanguages = listOf(
        AppLocale.SYSTEM to stringResource(R.string.settings_app_language_system),
        "en" to stringResource(R.string.settings_app_language_en),
        "hi" to stringResource(R.string.settings_app_language_hi),
    )
    val appLanguageValue = remember { AppLocale.current(context) }
    val accents = com.fenyx.jtv.theme.ACCENTS.map { it.first to stringResource(it.second) }
    // No stored choice = dark on TV, device setting on phone/tablet (see JioTVGoTVTheme).
    val themeValue = themeMode ?: if (isTv) "dark" else "system"
    val accentMode by settingsManager.accentFlow.collectAsState(initial = null)
    val accentValue = accentMode ?: "amber"
    val startOptions = listOf("list" to stringResource(R.string.settings_start_list), "last" to stringResource(R.string.settings_start_last))

    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val updateAvailable = updateInfo?.isUpdateAvailable == true
    // Newest stored crash / freeze report (redacted), read off the main thread.
    val latestReport by produceState<String?>(initialValue = null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.fenyx.jtv.crash.CrashReports.latestReport(context)
        }
    }
    val reportLabel = androidx.compose.ui.res.stringResource(com.fenyx.jtv.R.string.send_problem_report)
    val reportNone = androidx.compose.ui.res.stringResource(com.fenyx.jtv.R.string.problem_report_none)
    val reportShare = androidx.compose.ui.res.stringResource(com.fenyx.jtv.R.string.problem_report_share)
    val reportDesc = androidx.compose.ui.res.stringResource(com.fenyx.jtv.R.string.problem_report_desc)
    val reportNoApp = androidx.compose.ui.res.stringResource(com.fenyx.jtv.R.string.problem_report_no_app)
    var reportMsg by remember { mutableStateOf<String?>(null) }

    val deviceTypeValue = remember { com.fenyx.jtv.theme.DeviceKind.chosen(context) ?: com.fenyx.jtv.theme.DeviceKind.AUTO }
    val deviceTypes = listOf(
        com.fenyx.jtv.theme.DeviceKind.AUTO to stringResource(R.string.settings_device_type_auto),
        com.fenyx.jtv.theme.DeviceKind.TV to stringResource(R.string.device_tv),
        com.fenyx.jtv.theme.DeviceKind.PHONE to stringResource(R.string.device_phone),
        com.fenyx.jtv.theme.DeviceKind.TABLET to stringResource(R.string.device_tablet),
    )

    // ─── Rows (built as data so the list stays a flat, keyed LazyColumn) ───
    val backup = rememberFavoritesBackup { id -> mainViewModel.getAllChannels().firstOrNull { it.id == id }?.name }
    val rows: List<SRow> = buildList {
        add(SRow.Section(stringResource(R.string.settings_section_general)))
        add(SRow.Item("theme", stringResource(R.string.settings_appearance), value = themes.first { it.first == themeValue }.second) { sheet = Sheet.Theme })
        add(SRow.Item("deviceType", stringResource(R.string.settings_device_type),
            value = deviceTypes.first { it.first == deviceTypeValue }.second,
            description = stringResource(R.string.settings_device_type_desc)) { sheet = Sheet.DeviceType })
        add(SRow.Item("appLanguage", stringResource(R.string.settings_app_language),
            value = appLanguages.firstOrNull { it.first == appLanguageValue }?.second ?: appLanguages[0].second,
            description = stringResource(R.string.settings_app_language_desc)) { sheet = Sheet.AppLanguage })
        add(SRow.Item("accent", stringResource(R.string.settings_accent), value = accents.firstOrNull { it.first == accentValue }?.second ?: accents[0].second,
            description = stringResource(R.string.settings_accent_desc)) { sheet = Sheet.Accent })
        val hiddenCats = mainViewModel.categoryPrefs.collectAsState().value.hidden.count { it in categoryGroups || it.startsWith("__") }
        add(SRow.Item("categories", stringResource(R.string.settings_categories),
            value = if (hiddenCats == 0) stringResource(R.string.categories_all_shown) else stringResource(R.string.categories_n_hidden, hiddenCats),
            description = stringResource(R.string.settings_categories_desc)) { categoriesScreen = true })
        add(SRow.Item("start", stringResource(R.string.settings_start), value = stringResource(if (autoplayLastChannel) R.string.settings_start_last else R.string.settings_start_list),
            description = stringResource(R.string.settings_start_desc)) { sheet = Sheet.StartWith })

        add(SRow.Section(stringResource(R.string.settings_section_picture_sound)))
        add(SRow.Item("quality", stringResource(R.string.settings_quality), value = qualities.find { it.first == quality }?.second ?: qualities[0].second) { sheet = Sheet.Quality })
        add(SRow.Item("language", stringResource(R.string.settings_sound_language), value = languages.find { it.first == language }?.second ?: language,
            description = stringResource(R.string.settings_sound_language_desc)) { sheet = Sheet.Language })
        add(SRow.Item("resize", stringResource(R.string.settings_picture_size), value = resizeModes.find { it.first == playerResizeMode }?.second ?: stringResource(R.string.settings_size_fit_short)) { sheet = Sheet.PictureSize })
        add(SRow.Item("buffer", stringResource(R.string.settings_buffer), value = bufferOptions.find { it.first == playbackBufferSec }?.second
            ?: pluralStringResource(R.plurals.settings_buffer_seconds, playbackBufferSec, playbackBufferSec),
            description = stringResource(R.string.settings_buffer_desc)) { sheet = Sheet.Buffer })
        if (showPip) add(SRow.Item("pip", stringResource(R.string.settings_pip), value = onOff(pipOnLeave),
            description = stringResource(R.string.settings_pip_desc)) { scope.launch { settingsManager.setPipOnLeave(!pipOnLeave) } })
        if (showRemote) add(SRow.Item("remote", stringResource(R.string.settings_remote), value = remoteProfileLabel(remoteMap),
            description = stringResource(R.string.settings_remote_desc)) { remoteScreen = true })

        add(SRow.Section(stringResource(R.string.settings_section_guide)))
        add(SRow.Item("epg", stringResource(R.string.settings_section_guide), value = onOff(epgMode),
            description = stringResource(R.string.settings_guide_desc)) { scope.launch { settingsManager.setEpgMode(!epgMode) } })
        add(SRow.Item("epgUrl", stringResource(R.string.settings_guide_source), value = stringResource(R.string.settings_change), description = epgUrl) { sheet = Sheet.EpgUrl })
        add(SRow.Item("epgRefresh", stringResource(R.string.settings_guide_update), value = stringResource(when (epgSyncStatus) {
            EpgSyncStatus.IDLE -> R.string.settings_guide_status_idle
            EpgSyncStatus.DOWNLOADING -> R.string.settings_guide_status_downloading
            EpgSyncStatus.EXTRACTING -> R.string.settings_guide_status_extracting
            EpgSyncStatus.PARSING -> R.string.settings_guide_status_parsing
            EpgSyncStatus.COMPLETED -> R.string.common_done
            EpgSyncStatus.ERROR -> R.string.settings_guide_status_error
        })) {
            if (epgSyncStatus == EpgSyncStatus.IDLE || epgSyncStatus == EpgSyncStatus.COMPLETED || epgSyncStatus == EpgSyncStatus.ERROR) {
                mainViewModel.fetchEpg(forceRefresh = true)
            }
        })

        add(SRow.Section(stringResource(R.string.settings_section_channels)))
        add(SRow.Item("variants", stringResource(R.string.settings_group_languages), value = onOff(groupLanguageVariants),
            description = stringResource(R.string.settings_group_languages_desc)) {
            scope.launch { settingsManager.setGroupLanguageVariants(!groupLanguageVariants) }
        })

        add(SRow.Section(stringResource(R.string.settings_section_backup)))
        add(SRow.Item("favSave", stringResource(R.string.settings_backup_save), value = stringResource(R.string.common_save),
            description = stringResource(R.string.settings_backup_save_desc)) { backup.save() })
        add(SRow.Item("favRestore", stringResource(R.string.settings_backup_restore), value = stringResource(R.string.settings_backup_restore_value),
            description = stringResource(R.string.settings_backup_restore_desc)) { backup.restore() })

        add(SRow.Section(stringResource(R.string.settings_section_devices)))
        add(SRow.Item("devName", stringResource(R.string.settings_device_name), value = deviceName) { showNameDialog = true })
        add(SRow.Item("devPair", stringResource(R.string.settings_device_pair), value = "",
            description = stringResource(R.string.settings_device_pair_desc)) { showPairFlow = true })
        syncDevices.filter { it.paired }.forEach { d ->
            add(SRow.Item("dev:${d.id}", d.name, value = stringResource(R.string.settings_device_forget), description = pairedDeviceLine(d)) { forgetDevice = d })
        }
        add(SRow.Item("devAuto", stringResource(R.string.settings_device_auto), value = onOff(autoSync),
            description = stringResource(R.string.settings_device_auto_desc)) { com.fenyx.jtv.sync.LanSync.setAutoSync(!autoSync) })
        add(SRow.Item("devNow", stringResource(R.string.settings_device_sync_now), value = syncStatus ?: stringResource(R.string.settings_device_sync)) { com.fenyx.jtv.sync.LanSync.syncNow() })

        add(SRow.Section(stringResource(R.string.settings_section_problems)))
        add(SRow.Item("hw", stringResource(R.string.settings_hw), value = onOff(hwDecoder),
            description = stringResource(R.string.settings_hw_desc)) {
            scope.launch { settingsManager.setHardwareDecoder(!hwDecoder) }
        })
        add(SRow.Item("tunnel", stringResource(R.string.settings_tunnel), value = onOff(tunneling),
            description = stringResource(R.string.settings_tunnel_desc)) {
            scope.launch { settingsManager.setTunneling(!tunneling) }
        })

        add(SRow.Section(stringResource(R.string.settings_section_account)))
        add(SRow.Item("method", stringResource(R.string.settings_method), value = stringResource(when (setupMode) {
            "server" -> R.string.settings_method_server
            "jtv" -> R.string.settings_method_jtv
            else -> R.string.settings_method_jio
        }), description = if (setupMode == "server") serverUrl.ifEmpty { stringResource(R.string.settings_method_no_server) }
            else stringResource(R.string.settings_method_desc)) {
            sheet = Sheet.ConfirmChangeMethod
        })
        if (setupMode == "server" || setupMode == "jtv") {
            add(SRow.Item("refresh", stringResource(R.string.settings_refresh),
                value = if (serverRefreshing) stringResource(R.string.settings_refreshing) else (serverRefreshMsg ?: stringResource(R.string.settings_refresh_value)),
                description = stringResource(R.string.settings_refresh_desc)) { mainViewModel.refreshFromServer() })
        }
        add(SRow.Item("signout", stringResource(R.string.settings_signout), value = "", description = stringResource(R.string.settings_signout_desc)) { sheet = Sheet.ConfirmSignOut })

        add(SRow.Section(stringResource(R.string.settings_section_about)))
        add(SRow.Item("version", stringResource(R.string.settings_version), value = if (versionName.isNotEmpty()) versionName else "") { })
        add(SRow.Item("update", stringResource(R.string.settings_update), value = when {
            isDownloadingUpdate -> "${(updateDownloadProgress * 100).toInt()}%"
            isCheckingUpdate -> stringResource(R.string.settings_update_checking)
            updateAvailable -> stringResource(R.string.settings_update_ready, updateInfo?.versionName ?: "")
            else -> stringResource(R.string.settings_update_check_now)
        }, description = when {
            isDownloadingUpdate -> stringResource(R.string.settings_update_downloading)
            updateAvailable -> stringResource(R.string.settings_update_available_desc)
            updateStatusMessage != null -> updateStatusMessage
            else -> null
        }) {
            if (updateAvailable) sheet = Sheet.Update else mainViewModel.checkForUpdates(manual = true)
        })
        add(SRow.Item("report", reportLabel, value = if (latestReport != null) reportShare else reportNone,
            description = reportMsg ?: if (latestReport != null) reportDesc else null) {
            latestReport?.let { text ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, reportLabel)
                    // Binder transactions cap at ~1 MB; a report is at most ~70 KB, but stay well under.
                    putExtra(android.content.Intent.EXTRA_TEXT, text.take(200_000))
                }
                try {
                    context.startActivity(android.content.Intent.createChooser(send, reportLabel)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    reportMsg = null
                } catch (_: android.content.ActivityNotFoundException) {
                    reportMsg = reportNoApp
                }
            }
        })
    }

    val now = rememberMinuteClock()
    val gutter = when (form) { FormFactor.Tv -> 48.dp; FormFactor.Tablet -> 32.dp; else -> 16.dp }

    // Back from Remote buttons: focus returns to its row.
    var focusRemoteRow by remember { mutableStateOf(false) }
    LaunchedEffect(focusRemoteRow, remoteScreen) {
        if (focusRemoteRow && !remoteScreen) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { remoteRowFocus.requestFocus() }
            focusRemoteRow = false
        }
    }
    if (categoriesScreen) {
        CategoriesScreen(modifier, categoryGroups, onClose = { categoriesScreen = false })
        return
    }
    if (remoteScreen) {
        RemoteButtonsScreen(modifier, onClose = { remoteScreen = false; focusRemoteRow = true })
        return
    }

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
                JText(stringResource(R.string.common_settings), if (isTv) 28.sp else 24.sp, Modifier.weight(1f), weight = FontWeight.Bold)
                if (isTv && onTab != null) com.fenyx.jtv.ui.main.TvTabs(com.fenyx.jtv.ui.main.PhoneTab.Settings, onTab, Modifier.padding(end = 20.dp))
                if (form != FormFactor.Phone) JtvClock(now, size = if (isTv) 22.sp else 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRestorer(),
                state = listState,
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
                            modifier = when (r.key) {
                                "theme" -> Modifier.focusRequester(firstItemFocus)
                                "remote" -> Modifier.focusRequester(remoteRowFocus)
                                else -> Modifier
                            },
                            onClick = r.onClick,
                        )
                    }
                }
            }
            if (isTv) {
                Spacer(Modifier.height(6.dp))
                KeyHint(listOf(stringResource(R.string.settings_hint_ok_key) to stringResource(R.string.settings_hint_change), stringResource(R.string.common_back) to stringResource(R.string.settings_hint_close)))
            }
        }

        // ─── Second level ───
        when (sheet) {
            Sheet.DeviceType -> PickerDialog(stringResource(R.string.settings_device_type), deviceTypes, deviceTypeValue,
                onSelect = { v ->
                    sheet = Sheet.None
                    if (v != deviceTypeValue) {
                        com.fenyx.jtv.theme.DeviceKind.set(context, v)
                        context.findActivity()?.recreate() // the whole layout changes
                    }
                },
                onDismiss = { sheet = Sheet.None })
            Sheet.AppLanguage -> PickerDialog(stringResource(R.string.settings_app_language), appLanguages, appLanguageValue,
                onSelect = { v ->
                    sheet = Sheet.None
                    context.findActivity()?.let { AppLocale.set(it, v) }
                },
                onDismiss = { sheet = Sheet.None })
            Sheet.Accent -> PickerDialog(stringResource(R.string.settings_accent), accents, accentValue,
                onSelect = { v -> scope.launch { settingsManager.setAccent(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None },
                swatches = com.fenyx.jtv.theme.ACCENTS.associate { it.first to (if (c.isDark) it.third.first else it.third.second) })
            Sheet.Theme -> PickerDialog(stringResource(R.string.settings_appearance), themes, themeValue,
                onSelect = { v -> scope.launch { settingsManager.setThemeMode(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.StartWith -> PickerDialog(stringResource(R.string.settings_start), startOptions, if (autoplayLastChannel) "last" else "list",
                onSelect = { v -> scope.launch { settingsManager.setAutoplayLastChannel(v == "last") }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.Quality -> PickerDialog(stringResource(R.string.settings_quality), qualities, quality,
                onSelect = { v -> scope.launch { settingsManager.setDefaultQuality(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.Language -> PickerDialog(stringResource(R.string.settings_sound_language), languages, language,
                onSelect = { v -> scope.launch { settingsManager.setDefaultLanguage(v) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.PictureSize -> PickerDialog(stringResource(R.string.settings_picture_size), resizeModes.map { it.first.toString() to it.second }, playerResizeMode.toString(),
                onSelect = { v -> scope.launch { settingsManager.setPlayerResizeMode(v.toInt()) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.Buffer -> PickerDialog(stringResource(R.string.settings_buffer), bufferOptions.map { it.first.toString() to it.second }, playbackBufferSec.toString(),
                onSelect = { v -> scope.launch { settingsManager.setPlaybackBufferSec(v.toInt()) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.EpgUrl -> EpgUrlDialog(
                initial = epgUrl,
                onSave = { url -> scope.launch { settingsManager.setEpgUrl(url) }; sheet = Sheet.None },
                onDismiss = { sheet = Sheet.None })
            Sheet.ConfirmSignOut -> ConfirmDialog(
                title = stringResource(R.string.settings_signout_title),
                message = stringResource(R.string.settings_signout_message),
                confirm = stringResource(R.string.settings_signout),
                onConfirm = { sheet = Sheet.None; scope.launch { settingsManager.clearAuthData() } },
                onDismiss = { sheet = Sheet.None })
            // Returning to the chooser = clear credentials + reset the chosen mode.
            Sheet.ConfirmChangeMethod -> ConfirmDialog(
                title = stringResource(R.string.settings_change_method_title),
                message = stringResource(R.string.settings_change_method_message),
                confirm = stringResource(R.string.settings_change_method_confirm),
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
                title = stringResource(R.string.settings_forget_title, d.name),
                message = stringResource(R.string.settings_forget_message),
                confirm = stringResource(R.string.settings_device_forget),
                onConfirm = { com.fenyx.jtv.sync.LanSync.forget(d.id); forgetDevice = null },
                onDismiss = { forgetDevice = null })
        }
    }
}

@Composable
private fun onOff(on: Boolean): String = stringResource(if (on) R.string.common_on else R.string.common_off)

/** The Activity behind a Compose context (needed to switch the app language). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
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
internal fun SettingsSection(title: String) {
    Text(
        title,
        style = textStyle(14.sp, FontWeight.SemiBold),
        color = Jtv.colors.t3,
        modifier = Modifier.padding(top = if (Jtv.isTv) 20.dp else 14.dp, bottom = 4.dp, start = 12.dp)
    )
}

/** One first-level row: label (+ optional short explanation) on the left, current value on the right. */
@Composable
internal fun SettingsRow(
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
                        maxLines = 2, overflow = TextOverflow.Ellipsis
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
                Text(title, style = textStyle(18.sp, FontWeight.SemiBold), color = if (focused) c.invTx else c.tx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = textStyle(14.sp), color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val vc = if (focused) c.invTx else if (valueColor != Color.Unspecified) valueColor else c.t2
            if (icon != null) {
                androidx.tv.material3.Icon(icon, contentDescription = null, tint = vc, modifier = Modifier.size(24.dp))
            }
            if (value.isNotEmpty()) {
                Text(value, style = textStyle(16.sp, FontWeight.SemiBold), color = vc, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp))
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
internal fun PickerDialog(
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
                            Text(stringResource(R.string.settings_selected), style = textStyle(14.sp), color = if (focused) c.invTx else c.t2)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        JtvButton(stringResource(R.string.common_cancel), onDismiss)
    }
}

@Composable
internal fun ConfirmDialog(
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
            JtvButton(stringResource(R.string.common_cancel), onDismiss, Modifier.focusRequester(cancelFocus), fontSize = 18.sp)
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
        Text(stringResource(R.string.settings_guide_source), style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_web_address), style = textStyle(16.sp, FontWeight.SemiBold), color = c.t2)
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
            JtvButton(stringResource(R.string.common_cancel), onDismiss, fontSize = 18.sp)
            JtvButton(stringResource(R.string.common_save), { onSave(tempUrl) }, primary = true, fontSize = 18.sp)
        }
    }
}

/**
 * Opens by itself (on Home) once per app start when a newer version is out: Install, Later, or Ignore
 * (no popup for this version again; Settings → About still offers it).
 */
@Composable
internal fun UpdatePrompt(vm: com.fenyx.jtv.ui.main.MainViewModel) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val info by vm.updateInfo.collectAsState()
    val ignored by settings.ignoredUpdateFlow.collectAsState(initial = "")
    val downloading by vm.isDownloadingUpdate.collectAsState()
    val progress by vm.updateDownloadProgress.collectAsState()
    val done by vm.updateDownloadedBytes.collectAsState()
    val total by vm.updateTotalBytes.collectAsState()
    val error by vm.updateError.collectAsState()
    var closed by rememberSaveable { mutableStateOf(false) }
    val i = info
    // ignored == "" while DataStore loads, so an ignored version never flashes up.
    if (i == null || !i.isUpdateAvailable || closed || ignored == "" || ignored == i.versionName) return
    Dialog(
        onDismissRequest = { if (!downloading) closed = true },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        UpdateDialog(
            updateInfo = i, isDownloading = downloading, downloadProgress = progress,
            downloadedBytes = done, totalBytes = total, errorMessage = error,
            onDownloadAndInstall = { vm.downloadAndInstallUpdate(context) },
            onDismiss = { closed = true },
            onIgnore = { scope.launch { settings.setIgnoredUpdate(i.versionName) }; closed = true },
        )
    }
}

/**
 * GitHub release notes → plain text for the dialog: just the "What's new" / "New in …" section when
 * there is one (not the logo, install blurb or older betas), without markdown marks.
 */
internal fun releaseNotesText(md: String): String {
    val lines = md.lines()
    val start = lines.indexOfFirst { it.startsWith("## ") && (it.contains("New", true) || it.contains("What", true)) }
    val section = if (start < 0) lines else lines.drop(start + 1).takeWhile { !it.startsWith("## ") && it.trim() != "---" }
    return section.filterNot { it.trimStart().startsWith("<") }
        .joinToString("\n").replace(Regex("[*`]|^#+ ?|^> ?", RegexOption.MULTILINE), "").trim()
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
    onDismiss: () -> Unit,
    onIgnore: (() -> Unit)? = null,
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
            Text(stringResource(R.string.settings_update_title), style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
            Text(stringResource(R.string.settings_update_version, updateInfo.versionName), style = textStyle(16.sp), color = c.t2)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_update_whats_new), style = textStyle(16.sp, FontWeight.SemiBold), color = c.tx)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = if (Jtv.isTv) 120.dp else 180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.bg)
                    .padding(12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = releaseNotesText(updateInfo.changelog)
                                .ifBlank { stringResource(R.string.settings_update_default_notes) },
                            style = textStyle(16.sp),
                            color = c.t2
                        )
                    }
                }
            }

            if (updateInfo.apkSize > 0) {
                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", updateInfo.apkSize / (1024.0 * 1024.0))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_update_size, sizeMb), style = textStyle(14.sp), color = c.t2)
            }

            if (isDownloading) {
                Spacer(Modifier.height(16.dp))
                val dlMb = String.format(java.util.Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                val totMb = if (totalBytes > 0) String.format(java.util.Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0)) else ""
                Text(
                    stringResource(R.string.settings_update_progress, (downloadProgress * 100).toInt(), dlMb, totMb),
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
                    JtvButton(stringResource(R.string.settings_update_install), onDownloadAndInstall, Modifier.focusRequester(initialFocus), primary = true, fontSize = 18.sp)
                    JtvButton(stringResource(R.string.settings_update_later), onDismiss, fontSize = 18.sp)
                    onIgnore?.let { JtvButton(stringResource(R.string.settings_update_ignore), it, fontSize = 18.sp) }
                }
            } else {
                Text(stringResource(R.string.settings_update_wait), style = textStyle(16.sp), color = c.t2)
            }
        }
    }
}
