package com.fenyx.jtv.ui.settings

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.*
import kotlinx.coroutines.launch

import com.fenyx.jtv.data.EpgSyncStatus
import com.fenyx.jtv.ui.main.MainViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    val language by settingsManager.defaultLanguageFlow.collectAsState(initial = "hi")
    val quality by settingsManager.defaultQualityFlow.collectAsState(initial = "auto")
    val hwDecoder by settingsManager.hardwareDecoderFlow.collectAsState(initial = true)
    val playerResizeMode by settingsManager.playerResizeModeFlow.collectAsState(initial = 0)
    val epgMode by settingsManager.epgModeFlow.collectAsState(initial = false)
    val epgUrl by settingsManager.epgUrlFlow.collectAsState(initial = "https://avkb.short.gy/epg.xml.gz")
    val epgSyncStatus by mainViewModel.epgSyncStatus.collectAsState()
    val autoplayLastChannel by settingsManager.autoplayLastChannelFlow.collectAsState(initial = false)

    var showLanguagePicker by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    var showPlayerResizeModePicker by remember { mutableStateOf(false) }
    var showEpgUrlDialog by remember { mutableStateOf(false) }

    val languages = listOf(
        "hi" to "Hindi", "en" to "English", "ta" to "Tamil", "te" to "Telugu",
        "kn" to "Kannada", "ml" to "Malayalam", "bn" to "Bengali", "mr" to "Marathi",
        "gu" to "Gujarati", "pa" to "Punjabi", "or" to "Odia", "as" to "Assamese"
    )

    val qualities = listOf(
        "auto" to "Auto", "high" to "High (1080p)", "medium" to "Medium (720p)", "low" to "Low (480p)"
    )

    val resizeModes = listOf(
        0 to "Fit (Default)", 
        3 to "Fill (Crop)", 
        4 to "Zoom", 
        1 to "Stretch Width", 
        2 to "Stretch Height"
    )

    // ─── Root Box ───
    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Back) {
                    when {
                        showLanguagePicker -> { showLanguagePicker = false; true }
                        showQualityPicker -> { showQualityPicker = false; true }
                        showPlayerResizeModePicker -> { showPlayerResizeModePicker = false; true }
                        else -> false
                    }
                } else false
            }
    ) {
        // ─── Main Settings Layout ───
        Row(modifier = Modifier.fillMaxSize().background(TvDarkBackground)) {
            // Left: Title panel
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(TvDarkSurface)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = TvOnBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Configure your JTV experience",
                    style = MaterialTheme.typography.titleMedium,
                    color = TvOnSurfaceVariant
                )
            }

            // Right: Settings items
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { SectionHeader("Account") }

                item {
                    SettingsItem(
                        title = "Logout from JTV",
                        subtitle = "Clear your credentials and exit",
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        valueColor = Color(0xFFFF5252),
                        onClick = { scope.launch { settingsManager.clearAuthData() } }
                    )
                }

                item { SectionHeader("EPG (Electronic Program Guide)") }

                item {
                    SettingsToggle(
                        title = "EPG Mode",
                        subtitle = "Use a timeline view for channels instead of grid",
                        isEnabled = epgMode,
                        onClick = { scope.launch { settingsManager.setEpgMode(!epgMode) } }
                    )
                }

                item {
                    SettingsItem(
                        title = "EPG Source URL",
                        subtitle = epgUrl,
                        value = "Edit",
                        valueColor = TvPrimary,
                        onClick = { showEpgUrlDialog = true }
                    )
                }

                item {
                    SettingsItem(
                        title = "Refresh EPG Data",
                        subtitle = "Force download and parse the latest EPG",
                        value = when (epgSyncStatus) {
                            EpgSyncStatus.IDLE -> "Sync Now"
                            EpgSyncStatus.DOWNLOADING -> "Downloading..."
                            EpgSyncStatus.EXTRACTING -> "Extracting..."
                            EpgSyncStatus.PARSING -> "Parsing..."
                            EpgSyncStatus.COMPLETED -> "Done"
                            EpgSyncStatus.ERROR -> "Error"
                        },
                        valueColor = when (epgSyncStatus) {
                            EpgSyncStatus.ERROR -> Color(0xFFFF5252)
                            EpgSyncStatus.COMPLETED -> Color(0xFF4CAF50)
                            EpgSyncStatus.IDLE -> TvPrimary
                            else -> TvOnSurfaceVariant
                        },
                        onClick = { 
                            if (epgSyncStatus == EpgSyncStatus.IDLE || epgSyncStatus == EpgSyncStatus.COMPLETED || epgSyncStatus == EpgSyncStatus.ERROR) {
                                mainViewModel.fetchEpg(forceRefresh = true) 
                            }
                        }
                    )
                }

                item { SectionHeader("Playback") }

                item {
                    SettingsToggle(
                        title = "Autoplay Last Channel",
                        subtitle = "Automatically resume your last watched channel when app opens",
                        isEnabled = autoplayLastChannel,
                        onClick = { scope.launch { settingsManager.setAutoplayLastChannel(!autoplayLastChannel) } }
                    )
                }

                item {
                    SettingsItem(
                        title = "Default Quality",
                        subtitle = "Video quality for all channels",
                        value = qualities.find { it.first == quality }?.second ?: "Auto",
                        valueColor = TvPrimary,
                        onClick = { showQualityPicker = true }
                    )
                }

                item {
                    SettingsItem(
                        title = "Player View Mode",
                        subtitle = "Default video scaling (Fit, Fill, Zoom...)",
                        value = resizeModes.find { it.first == playerResizeMode }?.second ?: "Fit",
                        valueColor = TvPrimary,
                        onClick = { showPlayerResizeModePicker = true }
                    )
                }

                item {
                    SettingsItem(
                        title = "Default Audio Language",
                        subtitle = "Primary audio track language",
                        value = languages.find { it.first == language }?.second ?: language,
                        valueColor = TvPrimary,
                        onClick = { showLanguagePicker = true }
                    )
                }

                item {
                    SettingsToggle(
                        title = "Hardware Decoder",
                        subtitle = "Use hardware decoding for better performance",
                        isEnabled = hwDecoder,
                        onClick = { scope.launch { settingsManager.setHardwareDecoder(!hwDecoder) } }
                    )
                }



                item { SectionHeader("About") }

                item {
                    SettingsItem(
                        title = "About",
                        subtitle = "JTV",
                        value = "v1.0.0",
                        valueColor = TvOnSurfaceVariant,
                        onClick = { }
                    )
                }


            }
        }



        // ─── Dialogs ───
        if (showLanguagePicker) {
            Dialog(onDismissRequest = { showLanguagePicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                PickerDialog(
                    title = "Select Language",
                    options = languages,
                    currentValue = language,
                    onSelect = { value ->
                        scope.launch { settingsManager.setDefaultLanguage(value) }
                        showLanguagePicker = false
                    },
                    onDismiss = { showLanguagePicker = false }
                )
            }
        }

        if (showQualityPicker) {
            Dialog(onDismissRequest = { showQualityPicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                PickerDialog(
                    title = "Select Quality",
                    options = qualities,
                    currentValue = quality,
                    onSelect = { value ->
                        scope.launch { settingsManager.setDefaultQuality(value) }
                        showQualityPicker = false
                    },
                    onDismiss = { showQualityPicker = false }
                )
            }
        }

        if (showPlayerResizeModePicker) {
            Dialog(onDismissRequest = { showPlayerResizeModePicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                PickerDialog(
                    title = "Player View Mode",
                    options = resizeModes.map { it.first.toString() to it.second },
                    currentValue = playerResizeMode.toString(),
                    onSelect = { value ->
                        scope.launch { settingsManager.setPlayerResizeMode(value.toInt()) }
                        showPlayerResizeModePicker = false
                    },
                    onDismiss = { showPlayerResizeModePicker = false }
                )
            }
        }

        if (showEpgUrlDialog) {
            Dialog(onDismissRequest = { showEpgUrlDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                var tempUrl by remember { mutableStateOf(epgUrl) }
                Box(
                    modifier = Modifier.fillMaxSize().background(TvDarkBackground.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.width(500.dp).background(TvDarkSurface, RoundedCornerShape(16.dp)).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Edit EPG URL", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TvOnBackground)
                        Spacer(modifier = Modifier.height(24.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(56.dp).background(TvDarkSurfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = tempUrl,
                                onValueChange = { tempUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(color = TvOnSurface, fontSize = 16.sp),
                                cursorBrush = SolidColor(TvPrimary),
                                singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(
                                onClick = { showEpgUrlDialog = false },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(containerColor = TvDarkSurfaceVariant, focusedContainerColor = TvDarkSurface)
                            ) { Text("Cancel", modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), color = TvOnSurface) }
                            Spacer(modifier = Modifier.width(16.dp))
                            Surface(
                                onClick = {
                                    scope.launch { settingsManager.setEpgUrl(tempUrl) }
                                    showEpgUrlDialog = false
                                },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(containerColor = TvPrimaryContainer, focusedContainerColor = TvPrimary)
                            ) { Text("Save", modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = TvPrimary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp)
    )
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    value: String = "",
    valueColor: Color = com.fenyx.jtv.theme.TvPrimary,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TvDarkSurface,
            focusedContainerColor = TvDarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.5.dp, TvPrimary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TvOnSurface, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TvOnSurfaceVariant)
            }
            if (icon != null) {
                androidx.tv.material3.Icon(icon, contentDescription = null, tint = valueColor, modifier = Modifier.size(24.dp))
            } else if (value.isNotEmpty()) {
                Text(value, color = valueColor, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TvDarkSurface,
            focusedContainerColor = TvDarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.5.dp, TvPrimary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TvOnSurface, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TvOnSurfaceVariant)
            }
            // Custom toggle
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (isEnabled) TvPrimary.copy(alpha = 0.3f) else TvDarkSurfaceVariant)
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isEnabled) TvPrimary else TvOnSurfaceVariant)
                        .align(if (isEnabled) Alignment.CenterEnd else Alignment.CenterStart)
                )
            }
        }
    }
}

@Composable
private fun PickerDialog(
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
                .background(TvDarkSurface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TvOnBackground
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
                            containerColor = if (isSelected) TvPrimaryContainer.copy(alpha = 0.3f) else Color.Transparent,
                            focusedContainerColor = TvDarkSurfaceVariant
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(1.dp, TvPrimary.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(8.dp)
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(TvPrimary)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Text(
                                label,
                                color = if (isSelected) TvPrimary else TvOnSurface,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                onClick = onDismiss,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = TvDarkSurfaceVariant,
                    focusedContainerColor = TvPrimaryContainer
                )
            ) {
                Text(
                    "Cancel",
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                    color = TvOnSurface
                )
            }
        }
    }
}
