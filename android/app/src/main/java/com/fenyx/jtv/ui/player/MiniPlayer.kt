package com.fenyx.jtv.ui.player

import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.JtvColors
import com.fenyx.jtv.theme.LocalJtvColors
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.rememberMinuteClock
import kotlin.math.abs
import kotlin.math.roundToInt

/** Phone mini bar: 64dp row (video + text + two buttons) and a 2dp show-progress line. */
val MiniBarHeight = 66.dp
/** Tablet mini card width (video on top, the same row below). */
val MiniCardWidth = 360.dp
private val MiniRowHeight = 64.dp
private val MiniVideoHeight = 64.dp

/** Where the one video surface sits inside the player's root box. Changing it only re-lays it out. */
internal enum class VideoBox { Fill, TopWide, TwoColumn, MiniBar }

/**
 * Sizes the video for [mode] in the layout phase. The surface stays the same node in the same tree
 * position in every mode, so expanding, minimising and rotating never recreate the SurfaceView.
 */
internal fun Modifier.videoBox(mode: VideoBox): Modifier = layout { m, c ->
    val w = c.maxWidth
    val (vw, vh) = when (mode) {
        VideoBox.Fill -> w to c.maxHeight
        VideoBox.TopWide -> w to (w * 9 / 16).coerceAtMost(c.maxHeight)
        // Tablet landscape: the top of the left column (same rounding as fillMaxWidth(fraction)).
        VideoBox.TwoColumn -> (w * TabletVideoFraction).roundToInt().let { it to (it * 9 / 16).coerceAtMost(c.maxHeight) }
        VideoBox.MiniBar -> {
            val h = MiniVideoHeight.roundToPx().coerceAtMost(c.maxHeight)
            (h * 16 / 9).coerceAtMost(w) to h
        }
    }
    val p = m.measure(Constraints.fixed(vw.coerceAtLeast(0), vh.coerceAtLeast(0)))
    layout(p.width, p.height) { p.place(0, 0) }
}

/** Swipe-to-close offset of the mini player, read only in placement (no recomposition while dragging). */
@Stable
internal class MiniDrag {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    fun reset() { x = 0f; y = 0f }
}

/**
 * The mini player's own UI, drawn over the (already placed) video surface. Uses the APP theme colours
 * ([appColors]), not the player's dark-only ones. Tap expands; swipe down or sideways closes.
 */
@Composable
internal fun MiniPlayerContent(
    appColors: JtvColors,
    card: Boolean,
    channel: Channel?,
    epg: EpgSource,
    paused: Boolean,
    buffering: Boolean,
    error: String?,
    drag: MiniDrag,
    onExpand: () -> Unit,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
) {
    val now = rememberMinuteClock()
    val miniDescription = stringResource(R.string.player_mini_description)
    val openLabel = stringResource(R.string.player_open_player)
    CompositionLocalProvider(LocalJtvColors provides appColors, LocalNow provides now) {
        val c = appColors
        val expand by rememberUpdatedState(onExpand)
        val close by rememberUpdatedState(onClose)
        val cur = if (channel != null) rememberNowNext(epg, channel.id, always = true)?.now else null
        Column(
            Modifier.fillMaxWidth()
                .pointerInput(drag) {
                    val closeX = 96.dp.toPx()
                    val closeY = 40.dp.toPx()
                    detectDragGestures(
                        onDragEnd = {
                            val shut = abs(drag.x) > closeX || drag.y > closeY
                            if (shut) close() else drag.reset()
                        },
                        onDragCancel = { drag.reset() },
                        onDrag = { change, d ->
                            change.consume()
                            // Lock to the dominant direction; upwards is not a gesture.
                            val nx = drag.x + d.x
                            val ny = (drag.y + d.y).coerceAtLeast(0f)
                            if (abs(nx) >= ny) { drag.x = nx; drag.y = 0f } else { drag.y = ny; drag.x = 0f }
                        },
                    )
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expand() }
                .semantics(mergeDescendants = false) {
                    contentDescription = miniDescription
                    onClick(label = openLabel) { expand(); true }
                },
        ) {
            // Card (tablet): the video fills the 16:9 area on top.
            if (card) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                if (buffering) Spinner(Modifier.align(Alignment.Center), 32)
            }
            Row(Modifier.fillMaxWidth().height(MiniRowHeight), verticalAlignment = Alignment.CenterVertically) {
                if (!card) {
                    Box(Modifier.height(MiniVideoHeight).aspectRatio(16f / 9f)) {
                        if (buffering) Spinner(Modifier.align(Alignment.Center), 24)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (channel != null) {
                        val title = shownNumber(channel).let { if (it > 0) "$it ${channel.name}" else channel.name }
                        JText(title, 14.sp, color = c.tx, weight = FontWeight.SemiBold)
                        val sub = error ?: cur?.let { if (isReplaying(it)) replayLine(it) else it.title }
                            ?: channel.group.takeIf { it.isNotBlank() }?.let { com.fenyx.jtv.ui.main.groupLabel(it) } ?: com.fenyx.jtv.ui.main.languageLabel(channel.language)
                        if (sub.isNotBlank()) JText(sub, 13.sp, color = c.t2)
                    }
                }
                MiniIcon(
                    if (paused) Icons.Filled.PlayArrow else PlayerIcons.Pause,
                    stringResource(if (paused) R.string.player_play else R.string.player_pause), c, onTogglePause,
                )
                MiniIcon(Icons.Filled.Close, stringResource(R.string.player_close_player), c, onClose)
                Spacer(Modifier.width(4.dp))
            }
            // The show's progress (2dp); an empty line keeps the bar height steady without a guide.
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (cur != null && !isReplaying(cur)) JtvProgress(cur.progress(now), height = 2.dp, color = c.acc, track = c.line)
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier, sizeDp: Int) {
    CircularProgressIndicator(
        color = LocalJtvColors.current.acc, strokeWidth = 2.dp, modifier = modifier.size(sizeDp.dp),
    )
}

@Composable
private fun MiniIcon(icon: ImageVector, description: String, c: JtvColors, onClick: () -> Unit) {
    JtvClickable(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description; role = Role.Button },
        shape = CircleShape,
    ) { focused ->
        Icon(
            icon, contentDescription = null, tint = if (focused) c.invTx else c.tx,
            modifier = Modifier.align(Alignment.Center).size(26.dp),
        )
    }
}
