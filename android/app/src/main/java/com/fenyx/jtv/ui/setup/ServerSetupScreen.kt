package com.fenyx.jtv.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.fenyx.jtv.R
import com.fenyx.jtv.i18n.UiText
import com.fenyx.jtv.i18n.text
import com.fenyx.jtv.data.userText
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.data.ServerClient
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.data.ServerDiscovery
import com.fenyx.jtv.ui.components.JtvClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Server-mode setup: enter the JTV proxy server URL + access code, then "Connect" pulls the shared
 * credentials. On success we persist the server config + credentials; Navigation then flips to the app
 * automatically (authData becomes non-null).
 *
 * Text entry uses the system on-screen keyboard: each field is a plain focusable [TvField]; landing
 * focus on it opens the IME.
 */
@Composable
fun ServerSetupScreen(
    onBack: () -> Unit,
    jtvMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }

    // Prefill from any previously saved config (e.g. re-connecting from Settings). In JTV mode the URL
    // is hardcoded, so we only prefill the code.
    LaunchedEffect(Unit) {
        if (!jtvMode) serverUrl = settingsManager.serverUrlFlow.first()
        token = settingsManager.serverTokenFlow.first()
    }

    val tokenFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    val scanFocus = remember { FocusRequester() }
    val firstServerFocus = remember { FocusRequester() }
    val scanInteraction = remember { MutableInteractionSource() }
    val scanFocused by scanInteraction.collectIsFocusedAsState()

    // LAN scan for JTV servers. JTV mode scans too (without showing the list) so Connect tries them first.
    var servers by remember { mutableStateOf(emptyList<ServerDiscovery.Server>()) }
    var scanning by remember { mutableStateOf(true) }
    var scanRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(scanRun) {
        scanning = true
        servers = emptyList()
        ServerDiscovery.scan(context).collect { servers = it }
        scanning = false
    }
    // The first server found takes focus from "Scan again" (only if the user hasn't moved on).
    LaunchedEffect(servers.isNotEmpty()) {
        if (servers.isNotEmpty() && scanFocused) runCatching { firstServerFocus.requestFocus() }
    }

    // JTV mode starts on the code field (landing focus on a field opens the keyboard). Self-hosted mode
    // starts on "Scan again" on TV, above the server list, so no keyboard pops up over it.
    // On touch devices nothing is pre-focused (a focused row would look picked).
    val tv = Jtv.isTv
    LaunchedEffect(Unit) { runCatching { if (jtvMode) tokenFocus.requestFocus() else if (tv) scanFocus.requestFocus() } }

    fun connect() {
        if (!jtvMode && serverUrl.isBlank()) { error = UiText.of(R.string.setup_error_enter_url); return }
        if (token.isBlank()) { error = UiText.of(R.string.setup_error_enter_code); return }
        isConnecting = true
        error = null
        scope.launch {
            val result = if (jtvMode)
                ServerClient.fetchCredentials(ServerClient.candidateUrls("jtv", ""), token)
            else
                ServerClient.fetchCredentials(serverUrl, token)
            isConnecting = false
            result.onSuccess { authData ->
                if (jtvMode) {
                    // URL is hardcoded (tried LAN then internet) — store just the code.
                    settingsManager.setServerConfig("", token)
                    settingsManager.setSetupMode("jtv")
                } else {
                    settingsManager.setServerConfig(ServerClient.normalizeBaseUrl(serverUrl), token)
                    settingsManager.setSetupMode("server")
                }
                settingsManager.saveAuthData(authData) // flips Navigation into the app
            }.onFailure { error = it.userText(UiText.of(R.string.setup_error_connection_failed)) }
        }
    }

    // Hardware BACK returns to the setup chooser instead of exiting the app.
    androidx.activity.compose.BackHandler { onBack() }

    val c = Jtv.colors
    val isTv = Jtv.isTv
    val isPhone = Jtv.form == FormFactor.Phone

    // Full-bleed background across the WHOLE window (behind any keyboard/inset area) so the IME
    // opening doesn't reveal a black bar where safeDrawingPadding pushes the content up.
    Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 27.dp else 16.dp),
            horizontalAlignment = if (isPhone) Alignment.Start else Alignment.CenterHorizontally,
            // Top-anchored (not centered) so the fields stay in the upper area — the keyboard overlays
            // the empty lower area and NOTHING shifts when it opens.
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(if (isPhone) 8.dp else 24.dp))
            Text(
                stringResource(if (jtvMode) R.string.setup_code_title else R.string.setup_server_title),
                style = textStyle(if (isPhone) 28.sp else 32.sp, FontWeight.Bold),
                color = c.tx
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (jtvMode) R.string.setup_code_subtitle else R.string.setup_server_subtitle),
                style = textStyle(18.sp),
                color = c.t2
            )
            Spacer(Modifier.height(24.dp))

            error?.let {
                Text(it.text(), color = c.error, style = textStyle(18.sp, FontWeight.SemiBold))
                Spacer(Modifier.height(12.dp))
            }

            // Self-hosted mode shows found servers + the URL field; JTV mode hides both (URL is built in).
            if (!jtvMode) {
                ServerList(
                    servers = servers,
                    scanning = scanning,
                    onPick = { serverUrl = it.url; error = null; runCatching { tokenFocus.requestFocus() } },
                    onScanAgain = { scanRun++ },
                    scanModifier = Modifier.focusRequester(scanFocus),
                    scanInteraction = scanInteraction,
                    firstServerFocus = firstServerFocus,
                )
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.setup_custom_server))
                Spacer(Modifier.height(8.dp))
                TvField(
                    label = stringResource(R.string.setup_server_address),
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = "http://192.168.1.10:8080",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    keyboardActions = KeyboardActions(onNext = { runCatching { tokenFocus.requestFocus() } }),
                )
                Spacer(Modifier.height(16.dp))
            }
            TvField(
                label = stringResource(R.string.setup_access_code),
                value = token,
                onValueChange = { token = it },
                // Purely illustrative placeholder — must NOT resemble any real/active code.
                placeholder = stringResource(R.string.setup_access_code_hint),
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { runCatching { connectFocus.requestFocus() } }),
                modifier = Modifier.focusRequester(tokenFocus)
            )
            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JtvButton(stringResource(R.string.common_back), { onBack() }, fontSize = 18.sp, minHeight = if (isTv) 48.dp else 56.dp)
                JtvButton(
                    stringResource(if (isConnecting) R.string.setup_connecting else R.string.setup_connect),
                    { if (!isConnecting) connect() },
                    Modifier.focusRequester(connectFocus),
                    primary = true,
                    fontSize = 18.sp,
                    minHeight = if (isTv) 48.dp else 56.dp
                )
            }
        }
    }
}

/** Section width: full width on phones, the form column (560dp) elsewhere. */
@Composable
private fun sectionWidth() = if (Jtv.form == FormFactor.Phone) Modifier.fillMaxWidth() else Modifier.width(560.dp)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = textStyle(20.sp, FontWeight.SemiBold), color = Jtv.colors.tx, modifier = sectionWidth())
}

/** "Servers on your network": scanning line, one row per server, empty state, and "Scan again". */
@Composable
private fun ServerList(
    servers: List<ServerDiscovery.Server>,
    scanning: Boolean,
    onPick: (ServerDiscovery.Server) -> Unit,
    onScanAgain: () -> Unit,
    scanModifier: Modifier,
    scanInteraction: MutableInteractionSource,
    firstServerFocus: FocusRequester,
) {
    val c = Jtv.colors
    Column(sectionWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.setup_servers_nearby), style = textStyle(20.sp, FontWeight.SemiBold), color = c.tx, modifier = Modifier.weight(1f))
            JtvClickable(
                onClick = onScanAgain,
                modifier = scanModifier.heightIn(min = 48.dp),
                interactionSource = scanInteraction,
                focusedScale = 1.03f,
            ) { focused ->
                Row(
                    Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val fg = if (focused) c.invTx else c.tx
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.setup_scan_again), style = textStyle(16.sp, FontWeight.Medium), color = fg)
                }
            }
        }
        servers.forEachIndexed { i, s ->
            JtvClickable(
                onClick = { onPick(s) },
                modifier = Modifier.fillMaxWidth().height(64.dp)
                    .then(if (i == 0) Modifier.focusRequester(firstServerFocus) else Modifier),
                container = c.s1,
                focusedScale = 1.02f,
            ) { focused ->
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = textStyle(18.sp, FontWeight.SemiBold), color = if (focused) c.invTx else c.tx,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.url, style = textStyle(14.sp), color = if (focused) c.invTx else c.t2,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        stringResource(if (s.lite) R.string.setup_server_lite else R.string.setup_server_full),
                        style = textStyle(14.sp, FontWeight.Medium),
                        color = if (focused) c.invTx else c.t2,
                        modifier = Modifier
                            .border(1.dp, if (focused) c.invTx else c.line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = c.acc, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.setup_scanning), style = textStyle(16.sp), color = c.t2)
            }
        } else if (servers.isEmpty()) {
            Text(stringResource(R.string.setup_no_server_found), style = textStyle(18.sp, FontWeight.Medium), color = c.tx)
            Text(stringResource(R.string.setup_no_server_hint, ServerDiscovery.PROBE_PORT), style = textStyle(14.sp), color = c.t2)
        }
    }
}

@Composable
private fun TvField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    modifier: Modifier = Modifier
) {
    // Plain focusable field (no click-to-edit, no Surface → no focus-scale). D-pad onto it and the
    // system keyboard opens; press Back to close it, then D-pad away.
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var focused by remember { mutableStateOf(false) }
    val c = Jtv.colors
    Column(modifier = if (Jtv.form == FormFactor.Phone) Modifier.fillMaxWidth() else Modifier.width(560.dp)) {
        Text(label, style = textStyle(16.sp, FontWeight.SemiBold), color = c.t2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.s1)
                .border(
                    BorderStroke(if (focused) 2.dp else 1.dp, if (focused) c.acc else c.line),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle(20.sp).copy(color = c.tx),
                cursorBrush = SolidColor(c.acc),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = keyboardActions,
                // The caller's focusRequester (initial focus / Next-Done chaining) rides on the field.
                modifier = modifier
                    .fillMaxWidth()
                    .onFocusChanged { st ->
                        focused = st.isFocused
                        if (st.isFocused) {
                            // Focus alone doesn't reliably pop the TV IME. Ask via the Compose
                            // controller AND poke the platform InputMethodManager as a fallback for
                            // boxes where the controller is a no-op.
                            keyboard?.show()
                            runCatching {
                                val imm = view.context.getSystemService(
                                    android.content.Context.INPUT_METHOD_SERVICE
                                ) as? android.view.inputmethod.InputMethodManager
                                imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    },
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, color = c.t2, style = textStyle(20.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    inner()
                }
            )
        }
    }
}
