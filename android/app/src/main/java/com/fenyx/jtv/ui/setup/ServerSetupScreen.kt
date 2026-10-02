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
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.data.ServerClient
import com.fenyx.jtv.data.SettingsManager
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
    var error by remember { mutableStateOf<String?>(null) }

    // Prefill from any previously saved config (e.g. re-connecting from Settings). In JTV mode the URL
    // is hardcoded, so we only prefill the code.
    LaunchedEffect(Unit) {
        if (!jtvMode) serverUrl = settingsManager.serverUrlFlow.first()
        token = settingsManager.serverTokenFlow.first()
    }

    val urlFocus = remember { FocusRequester() }
    val tokenFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    // Focus the code field first in JTV mode (there's no URL field to focus). Landing focus on a field
    // opens the system keyboard for it.
    LaunchedEffect(Unit) { runCatching { (if (jtvMode) tokenFocus else urlFocus).requestFocus() } }

    fun connect() {
        if (!jtvMode && serverUrl.isBlank()) { error = "Enter the server URL."; return }
        if (token.isBlank()) { error = "Enter your access code."; return }
        isConnecting = true
        error = null
        scope.launch {
            val result = if (jtvMode)
                ServerClient.fetchCredentials(ServerClient.JTV_SERVER_URLS, token)
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
            }.onFailure { error = it.message ?: "Connection failed." }
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
                .padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 27.dp else 16.dp),
            horizontalAlignment = if (isPhone) Alignment.Start else Alignment.CenterHorizontally,
            // Top-anchored (not centered) so the fields stay in the upper area — the keyboard overlays
            // the empty lower area and NOTHING shifts when it opens.
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(if (isPhone) 8.dp else 24.dp))
            Text(
                if (jtvMode) "Connect with a code" else "Connect to your own server",
                style = textStyle(if (isPhone) 28.sp else 32.sp, FontWeight.Bold),
                color = c.tx
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (jtvMode) "Type your access code. The server address is built in."
                else "Type your server's address and access code.",
                style = textStyle(18.sp),
                color = c.t2
            )
            Spacer(Modifier.height(24.dp))

            error?.let {
                Text(it, color = c.error, style = textStyle(18.sp, FontWeight.SemiBold))
                Spacer(Modifier.height(12.dp))
            }

            // Self-hosted mode shows the URL field; JTV mode hides it (URL is hardcoded).
            if (!jtvMode) {
                TvField(
                    label = "Server address",
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = "http://192.168.1.10:8080",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    keyboardActions = KeyboardActions(onNext = { runCatching { tokenFocus.requestFocus() } }),
                    modifier = Modifier.focusRequester(urlFocus)
                )
                Spacer(Modifier.height(16.dp))
            }
            TvField(
                label = "Access code",
                value = token,
                onValueChange = { token = it },
                // Purely illustrative placeholder — must NOT resemble any real/active code.
                placeholder = "e.g. 7XK2Q9",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { runCatching { connectFocus.requestFocus() } }),
                modifier = Modifier.focusRequester(tokenFocus)
            )
            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JtvButton("Back", { onBack() }, fontSize = 18.sp, minHeight = if (isTv) 48.dp else 56.dp)
                JtvButton(
                    if (isConnecting) "Connecting…" else "Connect",
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
        Text(label, style = textStyle(16.sp, FontWeight.SemiBold), color = c.t2)
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
                    if (value.isEmpty()) Text(placeholder, color = c.t2, style = textStyle(20.sp))
                    inner()
                }
            )
        }
    }
}
