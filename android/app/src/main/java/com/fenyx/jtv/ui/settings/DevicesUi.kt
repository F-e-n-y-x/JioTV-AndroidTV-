package com.fenyx.jtv.ui.settings

import com.fenyx.jtv.i18n.text
import com.fenyx.jtv.i18n.UiText
import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.sync.LanSync
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.textStyle
import kotlinx.coroutines.launch

/*
 * LAN sync screens: the pairing dialog another device triggers on this one, the "Sync with another
 * device" flow, and the device-name editor. Plain words, one question per step, focus on the first button.
 */

@Composable
internal fun kindLabel(kind: String) = stringResource(when (kind) { "tv" -> R.string.devices_kind_tv; "tablet" -> R.string.devices_kind_tablet; else -> R.string.devices_kind_phone })

/** One line under a paired device in Settings, e.g. "TV · On this Wi-Fi · Synced 2:45 PM". */
@Composable
internal fun pairedDeviceLine(d: LanSync.Device): String = listOfNotNull(
    kindLabel(d.kind),
    stringResource(if (d.reachable) R.string.devices_reachable else R.string.devices_not_reachable),
    d.lastSync.takeIf { it > 0 }?.let { stringResource(R.string.devices_synced, formatTime(it)) },
).joinToString(" · ")

/** Asks focus for [r] once the dialog has laid out (TV: the first button is focused). */
@Composable
private fun FocusOnShow(r: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { withFrameNanos { }; runCatching { r.requestFocus() } }
}

/**
 * Shown on THIS device when another one asks to pair: "Pair with <name>? Code 4821" and Cancel.
 * Lives at the app root (MainActivity) so it appears over any screen, the player included.
 */
@Composable
fun PairRequestHost() {
    val prompt by LanSync.pairPrompt.collectAsState()
    val p = prompt ?: return
    val c = Jtv.colors
    val first = remember { FocusRequester() }
    FocusOnShow(first, p.paired)
    DialogPanel(onDismiss = { LanSync.dismissPairPrompt() }) {
        if (!p.paired) {
            Text(stringResource(R.string.devices_pair_with, p.fromName), style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.devices_enter_code_on, p.fromName), style = textStyle(18.sp), color = c.t2, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(p.code.toCharArray().joinToString(" "), style = numberStyle(48.sp), color = c.acc)
            Spacer(Modifier.height(20.dp))
            JtvButton(stringResource(R.string.common_cancel), { LanSync.dismissPairPrompt() }, Modifier.focusRequester(first), fontSize = 18.sp)
        } else {
            Text(stringResource(R.string.devices_paired_with, p.fromName), style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.devices_paired_message), style = textStyle(18.sp), color = c.t2, maxLines = 2)
            Spacer(Modifier.height(20.dp))
            JtvButton(stringResource(R.string.common_ok), { LanSync.dismissPairPrompt() }, Modifier.focusRequester(first), primary = true, fontSize = 18.sp)
        }
    }
}

private sealed interface PairStep {
    data object Pick : PairStep
    data class Asking(val d: LanSync.Device) : PairStep
    data class Code(val d: LanSync.Device, val requestId: String, val message: UiText? = null) : PairStep
    data class Combine(val name: String, val peerId: String) : PairStep
    data class Done(val title: UiText, val message: UiText) : PairStep
}

/** Settings → Devices → "Sync with another device": pick a device, type its code, combine favourites. */
@Composable
internal fun SyncWithDeviceDialog(onDismiss: () -> Unit) {
    val c = Jtv.colors
    val scope = rememberCoroutineScope()
    val devices by LanSync.devices.collectAsState()
    var step by remember { mutableStateOf<PairStep>(PairStep.Pick) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    FocusOnShow(first, step::class)

    fun close() {
        (step as? PairStep.Code)?.let { LanSync.cancelPair(it.d.id, it.requestId) }
        onDismiss()
    }

    DialogPanel(onDismiss = ::close, width = 520.dp) {
        when (val s = step) {
            PairStep.Pick -> {
                val candidates = devices.filter { !it.paired }
                Text(stringResource(R.string.settings_device_pair), style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.devices_open_other), style = textStyle(16.sp), color = c.t2, maxLines = 3)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (candidates.isEmpty()) {
                        Text(stringResource(R.string.devices_looking), style = textStyle(18.sp), color = c.tx, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    candidates.forEachIndexed { i, d ->
                        JtvClickable(
                            onClick = {
                                step = PairStep.Asking(d)
                                scope.launch {
                                    val id = LanSync.requestPair(d.id)
                                    step = if (id != null) PairStep.Code(d, id)
                                    else PairStep.Done(UiText.of(R.string.devices_cannot_reach, d.name), UiText.of(R.string.devices_cannot_reach_message))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = if (Jtv.isTv) 52.dp else 60.dp)
                                .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        ) { focused ->
                            Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp, vertical = 6.dp)) {
                                Text(d.name, style = textStyle(18.sp, FontWeight.SemiBold), color = if (focused) c.invTx else c.tx,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(kindLabel(d.kind), style = textStyle(14.sp), color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                JtvButton(stringResource(R.string.common_cancel), ::close, if (candidates.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
            is PairStep.Asking -> {
                Text(stringResource(R.string.devices_asking, s.d.name), style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.devices_code_appears), style = textStyle(18.sp), color = c.t2)
                Spacer(Modifier.height(20.dp))
                JtvButton(stringResource(R.string.common_cancel), ::close, Modifier.focusRequester(first), fontSize = 18.sp)
            }
            is PairStep.Code -> {
                var code by remember(s.requestId) { mutableStateOf("") }
                fun submit() {
                    if (code.length != 4 || busy) return
                    busy = true
                    scope.launch {
                        val r = LanSync.confirmPair(s.d.id, s.requestId, code)
                        busy = false
                        step = when (r) {
                            is LanSync.PairResult.Paired -> PairStep.Combine(r.peerName, r.peerId)
                            is LanSync.PairResult.WrongCode -> s.copy(message = UiText.of(R.string.devices_wrong_code_tries, UiText.of(R.string.devices_wrong_code),
                                UiText.plural(R.plurals.devices_tries_left, r.attemptsLeft)))
                            LanSync.PairResult.Cancelled -> PairStep.Done(UiText.of(R.string.devices_pairing_stopped), UiText.of(R.string.devices_pairing_stopped_message, s.d.name))
                            LanSync.PairResult.Failed -> PairStep.Done(UiText.of(R.string.devices_cannot_reach, s.d.name), UiText.of(R.string.devices_cannot_reach_message))
                        }
                        if (r is LanSync.PairResult.WrongCode) code = ""
                    }
                }
                Text(stringResource(R.string.devices_enter_code), style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.devices_shown_on, s.d.name), style = textStyle(18.sp), color = c.t2, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                CodeField(code, { v -> code = v.filter(Char::isDigit).take(4); if (code.length == 4) submit() }, ::submit, Modifier.focusRequester(first))
                s.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it.text(), style = textStyle(16.sp), color = c.error)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JtvButton(stringResource(if (busy) R.string.devices_checking else R.string.devices_pair), ::submit, primary = true, fontSize = 18.sp)
                    JtvButton(stringResource(R.string.common_cancel), ::close, fontSize = 18.sp)
                }
            }
            is PairStep.Combine -> {
                Text(stringResource(R.string.devices_paired_with, s.name), style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.devices_combine_question),
                    style = textStyle(18.sp), color = c.t2, maxLines = 4)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JtvButton(stringResource(if (busy) R.string.devices_combining else R.string.devices_combine), {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val ok = LanSync.combineFavorites(s.peerId)
                                busy = false
                                step = if (ok) PairStep.Done(UiText.of(R.string.devices_combined), UiText.of(R.string.devices_combined_message, s.name))
                                else PairStep.Done(UiText.of(R.string.devices_combine_failed), UiText.of(R.string.devices_combine_failed_message))
                            }
                        }
                    }, Modifier.focusRequester(first), primary = true, fontSize = 18.sp)
                    JtvButton(stringResource(R.string.devices_not_now), onDismiss, fontSize = 18.sp)
                }
            }
            is PairStep.Done -> {
                Text(s.title.text(), style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(s.message.text(), style = textStyle(18.sp), color = c.t2, maxLines = 3)
                Spacer(Modifier.height(20.dp))
                JtvButton(stringResource(R.string.common_ok), onDismiss, Modifier.focusRequester(first), primary = true, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val c = Jtv.colors
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focused) { if (focused) keyboard?.show() }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(c.bg, RoundedCornerShape(8.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) c.acc else c.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            textStyle = numberStyle(28.sp).copy(color = c.tx),
            cursorBrush = SolidColor(c.acc),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
        )
    }
}

/** "This device's name": what other devices see in their lists. */
@Composable
internal fun DeviceNameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Jtv.colors
    var name by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    DialogPanel(onDismiss) {
        Text(stringResource(R.string.settings_device_name), style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.devices_name_hint), style = textStyle(16.sp), color = c.t2)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .background(c.bg, RoundedCornerShape(8.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) c.acc else c.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusRequester(field),
                textStyle = textStyle(18.sp).copy(color = c.tx),
                cursorBrush = SolidColor(c.acc),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(name) }),
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JtvButton(stringResource(R.string.common_save), { onSave(name) }, primary = true, fontSize = 18.sp)
            JtvButton(stringResource(R.string.common_cancel), onDismiss, fontSize = 18.sp)
        }
    }
}
