package com.fenyx.jtv.ui.settings

import com.fenyx.jtv.i18n.text
import com.fenyx.jtv.i18n.UiText
import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.data.AssignResult
import com.fenyx.jtv.data.KeySpec
import com.fenyx.jtv.data.RemoteAction
import com.fenyx.jtv.data.RemoteKeyMap
import com.fenyx.jtv.data.RemoteKeys
import com.fenyx.jtv.data.RemoteProfile
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.textStyle
import kotlinx.coroutines.launch

/** Who sees "Remote buttons": TV, or a phone/tablet with a hardware keyboard or D-pad attached. */
@Composable
internal fun remoteButtonsAvailable(): Boolean {
    if (Jtv.isTv) return true
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    return cfg.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS ||
        cfg.navigation == android.content.res.Configuration.NAVIGATION_DPAD
}

/** Value shown on the Settings row. */
@Composable
internal fun remoteProfileLabel(map: RemoteKeyMap): String =
    stringResource(map.matchingProfile()?.labelRes ?: R.string.remote_custom)

private sealed interface RemoteSheet {
    data object None : RemoteSheet
    data object Profile : RemoteSheet
    data object Reset : RemoteSheet
    data class Capture(val action: RemoteAction, val note: UiText? = null) : RemoteSheet
    data class Replace(val action: RemoteAction, val spec: KeySpec, val owner: RemoteAction) : RemoteSheet
}

/**
 * Settings → Remote buttons (INTERACTION.md §6). Drawn in place of the settings list; Back returns.
 * Rows: the remote type (one-step profiles), reset, then every action with its current buttons.
 */
@Composable
internal fun RemoteButtonsScreen(modifier: Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val c = Jtv.colors
    val isTv = Jtv.isTv
    val map by settings.remoteKeyMapFlow.collectAsState(initial = RemoteKeys.Default)
    var sheet by remember { mutableStateOf<RemoteSheet>(RemoteSheet.None) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv) runCatching { firstFocus.requestFocus() } }

    BackHandler(enabled = sheet == RemoteSheet.None) { onClose() }

    fun save(m: RemoteKeyMap) = scope.launch {
        if (m.sameAs(RemoteKeys.Default)) settings.resetRemoteKeyMap() else settings.setRemoteKeyMap(m)
    }

    fun tryAssign(action: RemoteAction, spec: KeySpec) {
        when (val r = map.assign(action, spec)) {
            is AssignResult.Done -> { save(r.map); sheet = RemoteSheet.None }
            AssignResult.AlreadySet -> sheet = RemoteSheet.None
            is AssignResult.Conflict -> sheet = RemoteSheet.Replace(action, spec, r.owner)
            AssignResult.Locked -> sheet = RemoteSheet.Capture(action, UiText.of(R.string.remote_locked, spec.label))
            AssignResult.SystemKey -> sheet = RemoteSheet.Capture(action, UiText.of(R.string.remote_system, spec.label))
        }
    }

    val gutter = when (Jtv.form) { FormFactor.Tv -> 48.dp; FormFactor.Tablet -> 32.dp; else -> 16.dp }
    Column(
        modifier.fillMaxSize().background(c.bg).padding(horizontal = gutter, vertical = if (isTv) 27.dp else 8.dp),
    ) {
        JText(stringResource(R.string.settings_remote), if (isTv) 28.sp else 24.sp, weight = FontWeight.Bold)
        Text(
            stringResource(R.string.remote_intro),
            style = textStyle(if (isTv) 16.sp else 15.sp), color = c.t2,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.weight(1f)
                .then(if (Jtv.isPhonePortrait) Modifier.fillMaxWidth() else Modifier.widthIn(max = 820.dp).fillMaxWidth())
                .focusRestorer(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "s:type", contentType = "section") { SettingsSection(stringResource(R.string.remote_type)) }
            item(key = "type", contentType = "row") {
                SettingsRow(
                    label = stringResource(R.string.remote_type), value = remoteProfileLabel(map),
                    description = stringResource(R.string.remote_type_desc), destructive = false,
                    modifier = Modifier.focusRequester(firstFocus),
                ) { sheet = RemoteSheet.Profile }
            }
            item(key = "reset", contentType = "row") {
                SettingsRow(
                    label = stringResource(R.string.remote_reset), value = "",
                    description = stringResource(RemoteProfile.Standard.labelRes), destructive = false, modifier = Modifier,
                ) { sheet = RemoteSheet.Reset }
            }
            item(key = "s:actions", contentType = "section") { SettingsSection(stringResource(R.string.remote_section_actions)) }
            items(RemoteAction.entries, key = { it.id }, contentType = { "row" }) { a ->
                val keys = map.keysFor(a)
                SettingsRow(
                    label = stringResource(a.labelRes),
                    value = if (keys.isEmpty()) stringResource(R.string.remote_not_set) else keys.map { it.label.text() }.joinToString(", "),
                    description = null, destructive = false, modifier = Modifier,
                ) { sheet = RemoteSheet.Capture(a) }
            }
            item(key = "note", contentType = "note") {
                Text(
                    stringResource(R.string.remote_note),
                    style = textStyle(14.sp), color = c.t2,
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, end = 12.dp),
                )
            }
        }
        if (isTv) {
            Spacer(Modifier.height(6.dp))
            KeyHint(listOf(stringResource(R.string.settings_hint_ok_key) to stringResource(R.string.settings_hint_change), stringResource(R.string.common_back) to stringResource(R.string.remote_hint_back)))
        }
    }

    when (val s = sheet) {
        RemoteSheet.None -> Unit
        RemoteSheet.Profile -> PickerDialog(
            stringResource(R.string.remote_type),
            RemoteProfile.entries.map { it.id to stringResource(it.labelRes) },
            map.matchingProfile()?.id ?: "",
            onSelect = { id ->
                RemoteProfile.byId(id)?.let { save(RemoteKeys.profile(it)) }
                sheet = RemoteSheet.None
            },
            onDismiss = { sheet = RemoteSheet.None },
        )
        RemoteSheet.Reset -> ConfirmDialog(
            title = stringResource(R.string.remote_reset_title),
            message = stringResource(R.string.remote_reset_message),
            confirm = stringResource(R.string.remote_reset_confirm),
            onConfirm = { scope.launch { settings.resetRemoteKeyMap() }; sheet = RemoteSheet.None },
            onDismiss = { sheet = RemoteSheet.None },
        )
        is RemoteSheet.Capture -> CaptureDialog(
            action = s.action,
            current = map.keysFor(s.action),
            note = s.note,
            onKey = { spec -> tryAssign(s.action, spec) },
            onClear = { save(map.clear(s.action)); sheet = RemoteSheet.None },
            onDismiss = { sheet = RemoteSheet.None },
        )
        is RemoteSheet.Replace -> ConfirmDialog(
            title = stringResource(R.string.remote_replace_title, stringResource(s.owner.labelRes)),
            message = stringResource(R.string.remote_replace_message, s.spec.label.text(), stringResource(s.action.labelRes)),
            confirm = stringResource(R.string.remote_replace),
            onConfirm = {
                (map.assign(s.action, s.spec, replace = true) as? AssignResult.Done)?.let { save(it.map) }
                sheet = RemoteSheet.None
            },
            onDismiss = { sheet = RemoteSheet.None },
        )
    }
}

/**
 * "Press the button you want to use…". The next key press is recorded (key code, scan code as the
 * fallback); holding it past [RemoteKeys.HOLD_MS] records its hold slot. Back always cancels.
 * Taps on OK and the arrows still move between the dialog's own buttons (they can't be remapped);
 * holding them records "Hold OK" / "Hold Right arrow". Volume, Home and power pass to the system.
 */
@Composable
private fun CaptureDialog(
    action: RemoteAction,
    current: List<KeySpec>,
    note: UiText?,
    onKey: (KeySpec) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Jtv.colors
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    var holding by remember { mutableStateOf<UiText?>(null) }
    // The key-down that started the current press (so a key-up alone, e.g. the OK that opened this, is ignored).
    var downCode by remember { mutableStateOf<Int?>(null) }

    DialogPanel(onDismiss, width = 560.dp) {
        Column(
            Modifier.onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                val code = n.keyCode
                val down = e.type == KeyEventType.KeyDown
                when {
                    RemoteKeys.isBack(code) -> { if (!down) onDismiss(); true }
                    RemoteKeys.isSystem(code) -> false
                    down && n.repeatCount == 0 -> {
                        downCode = code
                        holding = null
                        // OK / arrows: let the tap move or press in this dialog.
                        !RemoteKeys.isLockedTap(code)
                    }
                    down -> {
                        if (n.eventTime - n.downTime >= RemoteKeys.HOLD_MS && downCode == code) {
                            holding = UiText.of(R.string.remote_holding, RemoteKeys.buttonLabel(code, n.scanCode))
                        }
                        // Held OK / arrows don't auto-repeat through the dialog's buttons.
                        true
                    }
                    else -> {
                        if (downCode != code) return@onPreviewKeyEvent !RemoteKeys.isLockedTap(code)
                        downCode = null
                        val hold = n.eventTime - n.downTime >= RemoteKeys.HOLD_MS
                        if (RemoteKeys.isLockedTap(code) && !hold) {
                            holding = null
                            false // a plain tap on OK / an arrow: the dialog's own buttons
                        } else {
                            onKey(KeySpec.of(code, n.scanCode, hold))
                            true
                        }
                    }
                }
            },
        ) {
            Text(stringResource(action.labelRes), style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.remote_press), style = textStyle(20.sp, FontWeight.SemiBold), color = c.acc)
            Spacer(Modifier.height(6.dp))
            Text(
                (holding ?: note)?.text() ?: stringResource(R.string.remote_hold_hint),
                style = textStyle(16.sp), color = if (holding != null) c.tx else c.t2,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (current.isEmpty()) stringResource(R.string.remote_no_button) else stringResource(R.string.remote_now, current.map { it.label.text() }.joinToString(", ")),
                style = textStyle(16.sp), color = c.t2,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                JtvButton(stringResource(R.string.common_cancel), onDismiss, Modifier.focusRequester(cancelFocus))
                if (current.isNotEmpty()) JtvButton(stringResource(R.string.remote_remove), onClear)
            }
        }
    }
}
