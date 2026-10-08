package com.fenyx.jtv.ui.main

import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.components.closeOnOutsideTap
import com.fenyx.jtv.ui.components.keepTapsInside
import androidx.compose.foundation.layout.fillMaxSize

/** One row in the channel options menu. [confirm] = ask this question before running it. */
data class ChannelAction(val label: String, val hint: String? = null, val confirm: String? = null, val run: () -> Unit)

/**
 * Channel options (hold OK / long-press / ⋮): watch, favourite and, inside Favourites, the reorder
 * tools. Focus lands on the first action. Destructive actions ask first (older users, single press).
 */
@Composable
fun ChannelActionsDialog(channel: Channel, actions: List<ChannelAction>, startWith: ChannelAction? = null, onDismiss: () -> Unit) {
    val c = Jtv.colors
    val firstFocus = remember { FocusRequester() }
    var confirming by remember { mutableStateOf(startWith) }
    // Wait a frame: switching to the "Remove …?" step swaps the focused buttons, and asking for focus
    // before the new ones exist left nothing focused on TV, so OK did nothing.
    LaunchedEffect(confirming) { androidx.compose.runtime.withFrameNanos { }; runCatching { firstFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      androidx.compose.foundation.layout.Box(
          Modifier.fillMaxSize().ignoreHeldOk().then(if (Jtv.isTv) Modifier else Modifier.closeOnOutsideTap(onDismiss)),
          contentAlignment = Alignment.Center,
      ) {
        Column(
            Modifier.then(if (Jtv.isTv) Modifier else Modifier.keepTapsInside())
                .width(if (Jtv.isTv) 440.dp else 360.dp).background(c.s1, RoundedCornerShape(10.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelPlate(channel.logoUrl, 56.dp, 32.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        listOfNotNull(channel.channelNumber.takeIf { it > 0 }?.toString(), channel.name).joinToString("  "),
                        style = textStyle(20.sp, FontWeight.Bold), color = c.tx, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(groupLabel(channel.group), style = textStyle(14.sp), color = c.t2, maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
            val ask = confirming
            if (ask != null) {
                Text(ask.confirm ?: "", style = textStyle(17.sp), color = c.tx)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    JtvButton(stringResource(R.string.home_yes_remove), { onDismiss(); ask.run() }, Modifier.focusRequester(firstFocus), primary = true)
                    JtvButton(stringResource(R.string.common_cancel), { if (startWith != null) onDismiss() else confirming = null })
                }
            } else {
                actions.forEachIndexed { i, action ->
                    JtvClickable(
                        onClick = { if (action.confirm != null) confirming = action else { onDismiss(); action.run() } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (Jtv.isTv) 48.dp else 56.dp)
                            .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    ) { focused ->
                        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(action.label, style = textStyle(17.sp, FontWeight.SemiBold), color = if (focused) c.invTx else c.tx)
                            if (action.hint != null) {
                                Text(action.hint, style = textStyle(13.sp), color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2)
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

/**
 * The menu opens while OK is still held (hold OK = options). Ignore OK until a FRESH press starts
 * inside the menu, so releasing the hold can't trigger "Watch" (or skip past "Move") by itself.
 */
@Composable
internal fun Modifier.ignoreHeldOk(): Modifier {
    val armed = remember { androidx.compose.runtime.mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (!ok) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed.value = true
        !armed.value
    }
}
