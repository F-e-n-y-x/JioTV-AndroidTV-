package com.fenyx.jtv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.EndsSoonPill
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.textStyle

/** Sizes for one channel row, per form factor. */
@Immutable
data class RowMetrics(
    val height: Dp,
    val numberWidth: Dp,
    val numberSize: TextUnit,
    val plateW: Dp,
    val plateH: Dp,
    val nameSize: TextUnit,
    val subSize: TextUnit,
)

val TvRow = RowMetrics(56.dp, 46.dp, 16.sp, 52.dp, 30.dp, 16.sp, 14.sp)
val TouchRow = RowMetrics(64.dp, 42.dp, 16.sp, 52.dp, 30.dp, 16.sp, 14.sp)

/**
 * One channel in the list: number · logo · name / what's on · progress. Focus = inverted fill.
 * [now] is null when the programme guide is off; the subline then shows the category.
 */
@Composable
fun ChannelRow(
    channel: Channel,
    nowProgram: EpgProgram?,
    now: Long,
    m: RowMetrics,
    isFavorite: Boolean,
    isMoving: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(m.height),
        container = if (isMoving) c.acc else Color.Transparent,
        focusedContainer = if (isMoving) c.acc else c.inv,
    ) { focused ->
        val inverted = focused || isMoving
        val fg = if (isMoving) c.accTx else if (focused) c.invTx else c.tx
        val fg2 = if (isMoving) c.accTx else if (focused) c.invTx.copy(alpha = 0.75f) else c.t2
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = 10.dp, end = if (onMore != null) 0.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.channelNumber.takeIf { it > 0 }?.toString() ?: "",
                style = numberStyle(m.numberSize), color = fg, textAlign = TextAlign.End,
                modifier = Modifier.width(m.numberWidth), maxLines = 1,
            )
            Box(Modifier.width(12.dp))
            ChannelPlate(channel.logoUrl, m.plateW, m.plateH)
            Box(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name, style = textStyle(m.nameSize, FontWeight.SemiBold), color = fg,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isFavorite) {
                        Icon(
                            Icons.Filled.Star, contentDescription = "Favourite",
                            tint = if (inverted) fg else c.acc, modifier = Modifier.padding(start = 6.dp).size(14.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        nowProgram?.title ?: channel.group, style = textStyle(m.subSize), color = fg2, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (nowProgram != null && nowProgram.minutesLeft(now) <= 10) {
                        EndsSoonPill(nowProgram.stopMs, Modifier.padding(start = 8.dp), fontSize = 12.sp)
                    }
                }
                if (nowProgram != null) {
                    JtvProgress(
                        nowProgram.progress(now), Modifier.padding(top = 4.dp),
                        color = if (inverted) fg else c.t3,
                        track = if (inverted) fg.copy(alpha = 0.22f) else c.line,
                    )
                }
            }
            if (onMore != null) {
                JtvClickable(onClick = onMore, modifier = Modifier.size(56.dp), focusedContainer = c.s2) {
                    Icon(
                        Icons.Filled.MoreVert, contentDescription = "Options for ${channel.name}",
                        tint = fg2, modifier = Modifier.align(Alignment.Center).size(24.dp),
                    )
                }
            }
        }
    }
}

/** A category in the TV / tablet left column: name + count. Selected = s2 fill; focused = inverted. */
@Composable
fun CategoryRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    fontSize: TextUnit = 16.sp,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        container = if (selected) c.s2 else Color.Transparent,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 12.dp).align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label, style = textStyle(fontSize, if (selected || focused) FontWeight.SemiBold else FontWeight.Normal),
                color = if (focused) c.invTx else if (selected) c.tx else c.t2, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                count.toString(), style = textStyle(fontSize * 0.85f),
                color = if (focused) c.invTx.copy(alpha = 0.7f) else c.t3, maxLines = 1,
            )
        }
    }
}

/** Phone category chip. */
@Composable
fun CategoryChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        container = if (selected) c.inv else c.s1,
        focusedContainer = c.inv,
    ) { focused ->
        val fg = if (selected || focused) c.invTx else c.t2
        Row(Modifier.align(Alignment.Center).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = textStyle(14.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal), color = fg, maxLines = 1)
            Text("  $count", style = textStyle(13.sp), color = fg.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

/** Labelled top action on TV / tablet headers (Search, Guide, Settings). */
@Composable
fun HeaderAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    JtvClickable(onClick = onClick, modifier = modifier.height(if (Jtv.isTv) 38.dp else 48.dp)) { focused ->
        Row(Modifier.align(Alignment.Center).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (focused) c.invTx else c.t2, modifier = Modifier.size(20.dp))
            Box(Modifier.width(8.dp))
            Text(label, style = textStyle(16.sp, FontWeight.SemiBold), color = if (focused) c.invTx else c.tx, maxLines = 1)
        }
    }
}

/** Phone bottom navigation item; current = amber bar on top + bold label. */
@Composable
fun BottomNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Jtv.colors
    JtvClickable(onClick = onClick, modifier = modifier.height(60.dp), shape = RoundedCornerShape(0.dp), focusedContainer = c.s2) {
        if (selected) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.6f).height(3.dp).background(c.acc))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (selected) c.acc else c.t2, modifier = Modifier.size(24.dp))
            Text(label, style = textStyle(12.sp, if (selected) FontWeight.Bold else FontWeight.Normal), color = if (selected) c.tx else c.t2, maxLines = 1)
        }
    }
}

/** Phone tabs. Shown under every top-level screen (Live TV, Guide, Search, Settings), never in the player. */
enum class PhoneTab { Live, Guide, Search, Settings }

@Composable
fun PhoneBottomBar(selected: PhoneTab, onSelect: (PhoneTab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Jtv.colors.s1)) {
        BottomNavItem("Live TV", Icons.Filled.Home, selected == PhoneTab.Live, { onSelect(PhoneTab.Live) }, Modifier.weight(1f))
        BottomNavItem("Guide", Icons.Filled.DateRange, selected == PhoneTab.Guide, { onSelect(PhoneTab.Guide) }, Modifier.weight(1f))
        BottomNavItem("Search", Icons.Filled.Search, selected == PhoneTab.Search, { onSelect(PhoneTab.Search) }, Modifier.weight(1f))
        BottomNavItem("Settings", Icons.Filled.Settings, selected == PhoneTab.Settings, { onSelect(PhoneTab.Settings) }, Modifier.weight(1f))
    }
}
