package com.fenyx.jtv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.fenyx.jtv.R
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.NumberBlock
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.nowNext
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.textStyle

/** Sizes for one grid card and how many columns fit, per form factor. */
@Immutable
data class CardMetrics(
    val height: Dp,
    val pad: Dp,
    val numberSize: TextUnit,
    val plateW: Dp,
    val plateH: Dp,
    val nameSize: TextUnit,
    val subSize: TextUnit,
    val minCardDp: Float,
    val gap: Dp,
    val minCols: Int,
    val maxCols: Int,
)

val TvCard = CardMetrics(120.dp, 9.dp, 16.sp, 44.dp, 26.dp, 15.sp, 14.sp, 112f, 12.dp, 3, 6)
val TabletCard = CardMetrics(132.dp, 10.dp, 18.sp, 52.dp, 30.dp, 16.sp, 14.sp, 128f, 12.dp, 3, 4)
val PhoneCard = CardMetrics(128.dp, 10.dp, 16.sp, 48.dp, 28.dp, 16.sp, 14.sp, 150f, 8.dp, 2, 3)

/**
 * One channel as a grid card: big number + logo, name (2 lines), and at the bottom what's on now with a
 * thin progress bar (guide on) or the category (guide off). Focus = inverted fill + a 1.03 scale.
 */
@Composable
fun ChannelCard(
    channel: Channel,
    nowProgram: EpgProgram?,
    now: Long,
    m: CardMetrics,
    isFavorite: Boolean,
    isMoving: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The number shown: the Jio number, or the position (1, 2, 3…) in Favourites. */
    number: Int = channel.channelNumber,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        // Min height, not fixed: a large system font grows the card instead of clipping it.
        modifier = modifier.fillMaxWidth().heightIn(min = m.height),
        shape = RoundedCornerShape(8.dp),
        container = if (isMoving) c.acc else c.s1,
        focusedContainer = if (isMoving) c.acc else c.inv,
        focusedScale = 1.03f,
    ) { focused ->
        val inverted = focused || isMoving
        val fg = if (isMoving) c.accTx else if (focused) c.invTx else c.tx
        val fg2 = if (isMoving) c.accTx else if (focused) c.invTx.copy(alpha = 0.75f) else c.t2
        // Top block (number, logo, name) and bottom block (what's on) pinned apart; works with a min height.
        Column(
            Modifier.fillMaxWidth().heightIn(min = m.height).padding(m.pad),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    number.takeIf { it > 0 }?.toString() ?: "",
                    style = numberStyle(m.numberSize), color = fg, maxLines = 1, softWrap = false,
                )
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Star, contentDescription = stringResource(R.string.home_favourite),
                        tint = if (inverted) fg else c.acc, modifier = Modifier.padding(start = 4.dp).size(14.dp),
                    )
                }
                // The logo gives way before the number does (4-digit numbers in a narrow card).
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    ChannelPlate(channel.logoUrl, m.plateW, m.plateH)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                channel.name, style = textStyle(m.nameSize, FontWeight.SemiBold), color = fg,
                // Always two lines tall, so cards in one grid row stay the same height at large font sizes.
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
            )
          }
          Column {
            Text(
                nowProgram?.title ?: groupLabel(channel.group), style = textStyle(m.subSize), color = fg2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (nowProgram != null) {
                JtvProgress(
                    nowProgram.progress(now), Modifier.padding(top = 4.dp),
                    color = if (inverted) fg else c.t3,
                    track = if (inverted) fg.copy(alpha = 0.22f) else c.line,
                )
            }
          }
        }
    }
}

/**
 * "List | Grid" segmented control for the Live TV header. Both segments carry text; the current one has
 * the s2 fill and the amber underline (like the section tabs); focus is the usual inverted fill.
 */
@Composable
fun ViewToggle(grid: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    Row(
        modifier.focusGroup().clip(RoundedCornerShape(8.dp)).background(c.s1).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to R.string.home_view_list, true to R.string.home_view_grid).forEach { (isGrid, label) ->
            val sel = grid == isGrid
            JtvClickable(
                onClick = { if (!sel) onSelect(isGrid) },
                modifier = Modifier.height(if (Jtv.isTv) 36.dp else 40.dp)
                    .semantics { selected = sel; role = Role.RadioButton }
                    .then(
                        if (sel) Modifier.drawBehind {
                            val w = size.width * 0.6f
                            drawRect(c.acc, topLeft = androidx.compose.ui.geometry.Offset((size.width - w) / 2, size.height - 2.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(w, 2.dp.toPx()))
                        } else Modifier
                    ),
                container = if (sel) c.s2 else Color.Transparent,
            ) { focused ->
                Text(
                    stringResource(label), style = textStyle(16.sp, if (sel) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (focused) c.invTx else if (sel) c.tx else c.t2, maxLines = 1,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
                )
            }
        }
    }
}

/**
 * Grid view's stand-in for the preview pane: the focused channel on one strip — number, name, the show
 * with its time range, a progress bar, and what's next. No guide: name and category/language only.
 */
@Composable
fun GridInfoBar(
    channel: Channel,
    programs: List<EpgProgram>?,
    now: Long,
    number: Int,
    modifier: Modifier = Modifier,
) {
    val c = Jtv.colors
    val nn = remember(programs, now) { programs?.let { nowNext(it, now) } }
    val p = nn?.now
    // Min height + intrinsic height (not a fixed 64dp) so a large font grows the bar; fillMaxHeight children
    // (the number block) then match the content instead of the whole screen.
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(8.dp)).background(c.s1)) {
        NumberBlock(number, Modifier.fillMaxHeight().width(72.dp), fontSize = 20.sp)
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(
                p?.title ?: channel.name, style = textStyle(17.sp, FontWeight.Bold), color = c.tx,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (p != null) "${channel.name} · " + stringResource(
                    R.string.home_time_range_left, formatTime(p.startMs), formatTime(p.stopMs),
                    pluralStringResource(R.plurals.home_min_left, p.minutesLeft(now), p.minutesLeft(now)),
                ) else groupLabel(channel.group) + " · " + languageLabel(channel.language),
                style = textStyle(14.sp), color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (p != null) JtvProgress(p.progress(now), Modifier.padding(top = 4.dp))
        }
        nn?.next?.let { n ->
            Column(Modifier.width(220.dp).fillMaxHeight().padding(end = 14.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.player_next_label), style = textStyle(12.sp, FontWeight.SemiBold), color = c.t3, maxLines = 1)
                Text(formatTime(n.startMs), style = numberStyle(14.sp), color = c.t2, maxLines = 1)
                Text(n.title, style = textStyle(14.sp), color = c.tx, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
