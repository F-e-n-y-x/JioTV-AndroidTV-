package com.fenyx.jtv.ui.settings

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

internal fun kindLabel(kind: String) = when (kind) { "tv" -> "TV"; "tablet" -> "Tablet"; else -> "Phone" }

/** One line under a paired device in Settings, e.g. "TV · On this Wi-Fi · Synced 2:45 PM". */
internal fun pairedDeviceLine(d: LanSync.Device): String = listOfNotNull(
    kindLabel(d.kind),
    if (d.reachable) "On this Wi-Fi now" else "Not in reach",
    d.lastSync.takeIf { it > 0 }?.let { "Synced ${formatTime(it)}" },
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
            Text("Pair with ${p.fromName}?", style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text("Enter this code on ${p.fromName}:", style = textStyle(18.sp), color = c.t2, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(p.code.toCharArray().joinToString(" "), style = numberStyle(48.sp), color = c.acc)
            Spacer(Modifier.height(20.dp))
            JtvButton("Cancel", { LanSync.dismissPairPrompt() }, Modifier.focusRequester(first), fontSize = 18.sp)
        } else {
            Text("Paired with ${p.fromName}", style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text("Favourites now stay the same on both devices.", style = textStyle(18.sp), color = c.t2, maxLines = 2)
            Spacer(Modifier.height(20.dp))
            JtvButton("OK", { LanSync.dismissPairPrompt() }, Modifier.focusRequester(first), primary = true, fontSize = 18.sp)
        }
    }
}

private sealed interface PairStep {
    data object Pick : PairStep
    data class Asking(val d: LanSync.Device) : PairStep
    data class Code(val d: LanSync.Device, val requestId: String, val message: String? = null) : PairStep
    data class Combine(val name: String, val peerId: String) : PairStep
    data class Done(val title: String, val message: String) : PairStep
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
                Text("Sync with another device", style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
                Spacer(Modifier.height(6.dp))
                Text("Open JTV on the other device. Both must be on the same Wi-Fi.", style = textStyle(16.sp), color = c.t2, maxLines = 3)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (candidates.isEmpty()) {
                        Text("Looking for devices…", style = textStyle(18.sp), color = c.tx, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    candidates.forEachIndexed { i, d ->
                        JtvClickable(
                            onClick = {
                                step = PairStep.Asking(d)
                                scope.launch {
                                    val id = LanSync.requestPair(d.id)
                                    step = if (id != null) PairStep.Code(d, id)
                                    else PairStep.Done("Could not reach ${d.name}", "Check that JTV is open on it, then try again.")
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
                JtvButton("Cancel", ::close, if (candidates.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
            is PairStep.Asking -> {
                Text("Asking ${s.d.name}…", style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text("A code will appear on its screen.", style = textStyle(18.sp), color = c.t2)
                Spacer(Modifier.height(20.dp))
                JtvButton("Cancel", ::close, Modifier.focusRequester(first), fontSize = 18.sp)
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
                            is LanSync.PairResult.WrongCode -> s.copy(message = "That code is not right. " +
                                if (r.attemptsLeft == 1) "1 try left." else "${r.attemptsLeft} tries left.")
                            LanSync.PairResult.Cancelled -> PairStep.Done("Pairing stopped", "It was cancelled on ${s.d.name}, or the code was wrong too many times.")
                            LanSync.PairResult.Failed -> PairStep.Done("Could not reach ${s.d.name}", "Check that JTV is open on it, then try again.")
                        }
                        if (r is LanSync.PairResult.WrongCode) code = ""
                    }
                }
                Text("Enter the code", style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
                Spacer(Modifier.height(6.dp))
                Text("It is shown on ${s.d.name}.", style = textStyle(18.sp), color = c.t2, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                CodeField(code, { v -> code = v.filter(Char::isDigit).take(4); if (code.length == 4) submit() }, ::submit, Modifier.focusRequester(first))
                s.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = textStyle(16.sp), color = c.error)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JtvButton(if (busy) "Checking…" else "Pair", ::submit, primary = true, fontSize = 18.sp)
                    JtvButton("Cancel", ::close, fontSize = 18.sp)
                }
            }
            is PairStep.Combine -> {
                Text("Paired with ${s.name}", style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text("Combine both lists? Favourites from both devices are kept, with this device's order first.",
                    style = textStyle(18.sp), color = c.t2, maxLines = 4)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JtvButton(if (busy) "Combining…" else "Combine", {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val ok = LanSync.combineFavorites(s.peerId)
                                busy = false
                                step = if (ok) PairStep.Done("Favourites combined", "${s.name} and this device now have the same favourites.")
                                else PairStep.Done("Could not combine now", "Use Sync now in Settings to try again.")
                            }
                        }
                    }, Modifier.focusRequester(first), primary = true, fontSize = 18.sp)
                    JtvButton("Not now", onDismiss, fontSize = 18.sp)
                }
            }
            is PairStep.Done -> {
                Text(s.title, style = textStyle(24.sp, FontWeight.Bold), color = c.tx, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text(s.message, style = textStyle(18.sp), color = c.t2, maxLines = 3)
                Spacer(Modifier.height(20.dp))
                JtvButton("OK", onDismiss, Modifier.focusRequester(first), primary = true, fontSize = 18.sp)
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
        Text("This device's name", style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
        Spacer(Modifier.height(6.dp))
        Text("Other devices show this name.", style = textStyle(16.sp), color = c.t2)
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
            JtvButton("Save", { onSave(name) }, primary = true, fontSize = 18.sp)
            JtvButton("Cancel", onDismiss, fontSize = 18.sp)
        }
    }
}
