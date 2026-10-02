package com.fenyx.jtv.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.*

/** One row in the long-press channel menu. */
data class ChannelAction(val label: String, val hint: String? = null, val run: () -> Unit)

/**
 * Long-press (hold OK) menu for a channel card: watch, add/remove favorite and, inside the Favorites
 * category, the reorder tools. Focus lands on the first action so a remote user can act immediately.
 */
@Composable
fun ChannelActionsDialog(
    channel: Channel,
    actions: List<ChannelAction>,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .ignoreHeldOk()
                .width(420.dp)
                .background(TvDarkSurface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                channel.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TvOnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(channel.group, style = MaterialTheme.typography.bodySmall, color = TvOnSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            actions.forEachIndexed { i, action ->
                Surface(
                    onClick = { onDismiss(); action.run() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = TvDarkSurfaceVariant
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = BorderStroke(2.dp, TvFocusBorder),
                            shape = RoundedCornerShape(8.dp)
                        )
                    )
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        Text(action.label, color = TvOnSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (action.hint != null) {
                            Text(action.hint, color = TvOnSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Banner shown above the grid while a favorite is being moved. */
@Composable
fun MoveBanner(channelName: String, position: Int, total: Int, isGrid: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TvPrimaryContainer.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⇅", color = TvPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Moving $channelName  •  $position of $total",
                color = TvOnBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (isGrid) "◀ ▶ ▲ ▼ move  •  OK save  •  BACK cancel"
                else "▲ ▼ move  •  OK save  •  BACK cancel",
                color = TvOnSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

/** Small tip shown at the top of the Favorites category so the reorder feature is discoverable. */
@Composable
fun FavoritesHint(modifier: Modifier = Modifier) {
    Text(
        "Tip: hold OK on a channel to move it, remove it, or group favorites by category",
        color = TvOnSurfaceVariant,
        fontSize = 12.sp,
        modifier = modifier
    )
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
