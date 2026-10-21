package com.fenyx.jtv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.R
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.EndsSoonPill
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Two-pane channel browser for the TV player ("Option 2" mockup): categories on the left, channels on
 * the right, a detail strip for the focused channel, over a plain plate. The old horizontal strip stays
 * in PlayerOverlays/PlayerStrap and is chosen by the `player_browser_style` setting.
 *
 * Keys are handled here, in the two columns, not in the player's root handler (which lets arrows through
 * while the panel is open): Up/Down/Left/Right never leave the panel by accident.
 */

// ───────────────────────── Pure logic (unit tested) ─────────────────────────

internal enum class BrowseCol { Categories, Channels }
internal enum class PanelKey { Left, Right, Ok }

/** Which column holds focus after [key]: Left goes to the categories, Right / OK into the channels. */
internal fun panelColumnAfter(col: BrowseCol, key: PanelKey): BrowseCol = when (col) {
    BrowseCol.Channels -> if (key == PanelKey.Left) BrowseCol.Categories else col
    BrowseCol.Categories -> if (key == PanelKey.Right || key == PanelKey.Ok) BrowseCol.Channels else col
}

/** Rows to move per key repeat: 1 for a tap, then faster the longer Up/Down is held. */
internal fun holdStep(repeat: Int): Int = when {
    repeat >= 16 -> 4
    repeat >= 6 -> 2
    else -> 1
}

/**
 * The next index moving [dir] (-1 up, +1 down) in a list of [size], [holdStep] rows at a time. Wraps at
 * the ends; a long jump that would overshoot stops on the end first, the next press wraps.
 */
internal fun stepIndex(cur: Int, dir: Int, size: Int, repeat: Int = 0): Int {
    if (size <= 0) return 0
    val last = size - 1
    val c = cur.coerceIn(0, last)
    val t = c + dir * holdStep(repeat)
    return when {
        t in 0..last -> t
        c == 0 && dir < 0 -> last
        c == last && dir > 0 -> 0
        else -> if (dir < 0) 0 else last
    }
}

/**
 * First item to scroll to so [index] is visible, or null when it already is. [firstFull] / [lastFull]
 * are the first and last fully visible items (-1 when nothing is laid out yet). [open] puts the item one
 * row below the top, so the playing channel sits near the top with a little context above it.
 */
internal fun scrollTarget(index: Int, firstFull: Int, lastFull: Int, open: Boolean): Int? = when {
    open || lastFull < firstFull -> (index - 1).coerceAtLeast(0)
    index < firstFull -> index
    index > lastFull -> firstFull + (index - lastFull)
    else -> null
}

// ───────────────────────── Panel ─────────────────────────

@Immutable
private data class CatItem(val id: String, val count: Int)

private class JobSlot { var job: Job? = null }

private val PanelWidth = 560.dp
private val CatWidth = 156.dp
private val RowH = 56.dp
private val CatH = 30.dp
private val StripH = 104.dp

/** The left overlay plate. Put it at the start edge of the player; the video stays visible on the right. */
@Composable
internal fun BrowsePanel(ui: PlayerUi, d: OverlayData, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    val act by rememberUpdatedState(d.actions)
    val scope = rememberCoroutineScope()
    val playingId = d.playing?.id
    val playingGroup = d.currentGroup ?: MainViewModel.GROUP_ALL

    // Categories with their counts, once per open (empty ones are skipped; the browsed one is always kept).
    val cats = remember(d.browseGroups) {
        val current = Snapshot.withoutReadObservation { ui.browseGroup }
        d.browseGroups.mapNotNull { g ->
            val n = d.resolveGroup(g).size
            if (n > 0 || g == current) CatItem(g, n) else null
        }
    }
    val bGroup = ui.browseGroup ?: MainViewModel.GROUP_ALL
    val list = remember(bGroup) { d.resolveGroup(bGroup) }
    val positional = bGroup == MainViewModel.GROUP_FAVORITES

    // One state per previewed category: the list starts near the playing channel, no flash of the top.
    val listState = remember(bGroup) { LazyListState(Snapshot.withoutReadObservation { ui.browseIndex }.minus(1).coerceAtLeast(0)) }
    val catState = remember(cats) { LazyListState(Snapshot.withoutReadObservation { cats.indexOfFirst { it.id == ui.browseGroup } }.minus(3).coerceAtLeast(0)) }
    val chanReq = remember(bGroup) { HashMap<Int, FocusRequester>() }
    val catReq = remember(cats) { HashMap<String, FocusRequester>() }

    var col by remember { mutableStateOf(BrowseCol.Channels) }
    var enterTok by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<String?>(null) }
    var catIdx by remember { mutableIntStateOf(0) }
    val stepJob = remember { JobSlot() }

    fun commit(g: String) {
        ui.browseGroup = g
        ui.browseIndex = d.resolveGroup(g).indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    }

    /** Scroll [ui.browseIndex] into view (open = near the top) and give that row focus. */
    suspend fun focusChannel(open: Boolean) {
        if (list.isEmpty()) return
        val i = ui.browseIndex.coerceIn(0, list.lastIndex)
        val info = listState.layoutInfo
        val full = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
        scrollTarget(i, full.firstOrNull()?.index ?: -1, full.lastOrNull()?.index ?: -1, open)?.let { listState.scrollToItem(it) }
        // The row may not be composed yet: retry for a few frames.
        repeat(4) {
            withFrameNanos { }
            if (runCatching { chanReq[i]?.requestFocus() ?: false }.getOrDefault(false)) return
        }
    }

    fun route(key: PanelKey) {
        val to = panelColumnAfter(col, key)
        if (to == col) return
        if (to == BrowseCol.Categories) {
            col = to
            runCatching { catReq[bGroup]?.requestFocus() }
        } else if (list.isNotEmpty()) {
            col = to
            enterTok++
        }
    }

    // Opening (and entering the channel column from the categories): focus + scroll the browsed row.
    LaunchedEffect(enterTok, ui.browseFocusToken) { focusChannel(open = true) }
    // Previewing a category: the list follows focus after a short pause, so a quick run of Up/Down
    // rebuilds it once, not on every key.
    LaunchedEffect(pending) {
        val g = pending ?: return@LaunchedEffect
        if (g == ui.browseGroup) return@LaunchedEffect
        delay(120)
        commit(g)
    }

    val onChanFocus = remember(ui) { { i: Int -> ui.browseIndex = i; col = BrowseCol.Channels } }
    val onChanClick = remember(bGroup) { { i: Int -> act.tune(bGroup, i) } }
    val onChanHold = remember(ui) { { ui.overlay = PlayerOverlay.Menu } }
    val onCatFocus = remember(cats) { { id: String -> col = BrowseCol.Categories; pending = id; catIdx = cats.indexOfFirst { it.id == id } } }
    val onCatClick = remember(cats) { { id: String -> if (id != ui.browseGroup) commit(id); route(PanelKey.Ok) } }

    Column(
        modifier.width(PanelWidth).fillMaxHeight().background(StrapBg)
            .padding(start = 48.dp, end = 16.dp, top = 27.dp, bottom = 27.dp),
    ) {
        // ── Header: category + count, and what the dot means ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            JText(groupLabel(bGroup), 28.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Text(
                list.size.toString(), style = textStyle(14.sp).copy(fontFeatureSettings = "tnum"), color = c.t2, maxLines = 1,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (positional) {
                JText(stringResource(R.string.player_panel_fav_note), 14.sp, color = c.t2, modifier = Modifier.padding(bottom = 4.dp))
            } else {
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayingDot()
                    Spacer(Modifier.width(8.dp))
                    JText(stringResource(R.string.player_playing), 14.sp, color = c.t2)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── Two columns ──
        Row(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = catState,
                modifier = Modifier.width(CatWidth).fillMaxHeight().onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        // Native focus moves between rows; the ends are swallowed so focus can't escape sideways.
                        Key.DirectionUp -> catIdx <= 0
                        Key.DirectionDown -> catIdx >= cats.lastIndex
                        Key.DirectionLeft -> true
                        Key.DirectionRight -> { route(PanelKey.Right); true }
                        else -> false
                    }
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(cats, key = { _, it -> it.id }, contentType = { _, _ -> "cat" }) { _, item ->
                    PanelCategoryRow(
                        item, selected = item.id == bGroup, playing = item.id == playingGroup,
                        modifier = Modifier.focusRequester(catReq.getOrPut(item.id) { FocusRequester() }),
                        onFocused = onCatFocus, onClick = onCatClick,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxHeight().onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (list.isNotEmpty()) {
                                ui.browseIndex = stepIndex(
                                    ui.browseIndex, if (ev.key == Key.DirectionUp) -1 else 1, list.size, ev.nativeKeyEvent.repeatCount,
                                )
                                stepJob.job?.cancel()
                                stepJob.job = scope.launch { focusChannel(open = false) }
                            }
                            true
                        }
                        Key.DirectionLeft -> { route(PanelKey.Left); true }
                        Key.DirectionRight -> true
                        else -> false
                    }
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(list, key = { _, ch -> ch.id }, contentType = { _, _ -> "row" }) { i, ch ->
                    PanelChannelRow(
                        channel = ch, index = i, number = if (positional) i + 1 else ch.channelNumber,
                        playing = ch.id == playingId, epg = d.epg,
                        modifier = Modifier.focusRequester(chanReq.getOrPut(i) { FocusRequester() }),
                        onFocused = onChanFocus, onClick = onChanClick, onHold = onChanHold,
                    )
                }
            }
        }

        // ── Detail strip for the focused channel ──
        Spacer(Modifier.height(12.dp))
        DetailStrip(ui, list, playingId, positional, d.epg)

        // ── Key hints for the column that has focus ──
        Spacer(Modifier.height(14.dp))
        PanelHint(col, positional)
    }
}

// ───────────────────────── Pieces ─────────────────────────

@Composable
private fun PlayingDot() {
    Box(Modifier.size(7.dp).clip(CircleShape).background(Jtv.colors.acc))
}

@Composable
private fun PanelCategoryRow(
    item: CatItem,
    selected: Boolean,
    playing: Boolean,
    modifier: Modifier,
    onFocused: (String) -> Unit,
    onClick: (String) -> Unit,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = { onClick(item.id) },
        modifier = modifier.fillMaxWidth().height(CatH).onFocusChanged { if (it.hasFocus) onFocused(item.id) },
        container = if (selected) c.s2 else Color.Transparent,
    ) { focused ->
        Row(Modifier.padding(horizontal = 10.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Text(
                groupLabel(item.id), style = textStyle(16.sp, if (selected || focused) FontWeight.SemiBold else FontWeight.Normal),
                color = if (focused) c.invTx else if (selected) c.tx else c.t2,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (playing) {
                PlayingDot()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                item.count.toString(), style = textStyle(14.sp).copy(fontFeatureSettings = "tnum"),
                color = if (focused) c.invTx.copy(alpha = 0.7f) else if (selected) c.t2 else c.t3, maxLines = 1,
            )
        }
    }
}

/** One channel: number · logo · full name (+ amber dot when playing) / what's on + thin progress. */
@Composable
private fun PanelChannelRow(
    channel: Channel,
    index: Int,
    number: Int,
    playing: Boolean,
    epg: EpgSource,
    modifier: Modifier,
    onFocused: (Int) -> Unit,
    onClick: (Int) -> Unit,
    onHold: () -> Unit,
) {
    val c = Jtv.colors
    val cur = rememberNowNext(epg, channel.id)?.now
    val now = LocalNow.current
    JtvClickable(
        onClick = { onClick(index) },
        onLongClick = onHold,
        modifier = modifier.fillMaxWidth().height(RowH).onFocusChanged { if (it.hasFocus) onFocused(index) },
    ) { focused ->
        val fg = if (focused) c.invTx else c.tx
        val fg2 = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2
        if (focused) Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(c.acc))
        Row(Modifier.align(Alignment.CenterStart).padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (number > 0) number.toString() else "", style = numberStyle(20.sp), color = fg,
                textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(40.dp),
            )
            Spacer(Modifier.width(8.dp))
            ChannelPlate(channel.logoUrl, 46.dp, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name, style = textStyle(16.sp, FontWeight.SemiBold), color = fg,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (playing) {
                        Spacer(Modifier.width(8.dp))
                        PlayingDot()
                    }
                }
                // EPG on: the show on now + progress. EPG off (or no data): category / language.
                Text(
                    cur?.title ?: channelSubtitle(channel), style = textStyle(14.sp), color = fg2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (cur != null) {
                    JtvProgress(
                        cur.progress(now), Modifier.padding(top = 3.dp),
                        color = if (focused) fg else c.acc,
                        track = if (focused) fg.copy(alpha = 0.22f) else c.line,
                    )
                }
            }
        }
    }
}

/** Logo, number, name, show title, times, progress, minutes left and what's next, for the focused row. */
@Composable
private fun DetailStrip(ui: PlayerUi, list: List<Channel>, playingId: String?, positional: Boolean, epg: EpgSource) {
    val c = Jtv.colors
    val i = ui.browseIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    val ch = list.getOrNull(i)
    Box(Modifier.fillMaxWidth().height(StripH).clip(RoundedCornerShape(8.dp)).background(c.s1)) {
        if (ch == null) return@Box
        val nn = rememberNowNext(epg, ch.id, always = ch.id == playingId)
        val cur = nn?.now
        val now = LocalNow.current
        val number = if (positional) i + 1 else ch.channelNumber
        Row(Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelPlate(ch.logoUrl, 66.dp, 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (number > 0) {
                        Text(number.toString(), style = numberStyle(16.sp), color = c.acc, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                    }
                    JText(ch.name, 14.sp, color = c.t2)
                }
                JText(cur?.title ?: channelSubtitle(ch), 20.sp, weight = FontWeight.Bold)
                if (cur != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        JText("${formatTime(cur.startMs)} – ${formatTime(cur.stopMs)}", 14.sp, color = c.t2)
                        JtvProgress(cur.progress(now), Modifier.weight(1f).padding(horizontal = 12.dp))
                        val left = cur.minutesLeft(now)
                        if (left <= 10) EndsSoonPill(cur.stopMs, fontSize = 14.sp)
                        else JText(pluralStringResource(R.plurals.player_min_left, left, left), 14.sp, color = c.t2)
                    }
                    nn.later.firstOrNull()?.let { p ->
                        JText("${stringResource(R.string.player_next_label)}  ${formatTime(p.startMs)}  ${p.title}", 14.sp, color = c.t2)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHint(col: BrowseCol, favourites: Boolean) {
    val c = Jtv.colors
    val items: List<Pair<String, String>> = if (col == BrowseCol.Categories) listOf(
        stringResource(R.string.player_key_up_down) to stringResource(R.string.player_hint_category),
        stringResource(R.string.player_key_ok_right) to stringResource(R.string.player_hint_channels),
        stringResource(R.string.player_key_back) to stringResource(R.string.player_hint_close),
    ) else listOf(
        stringResource(R.string.player_key_left) to stringResource(R.string.player_hint_categories),
        stringResource(R.string.player_key_up_down) to stringResource(R.string.player_hint_channel),
        stringResource(R.string.player_key_ok) to stringResource(R.string.player_hint_watch),
        if (favourites) stringResource(R.string.player_key_hold_ok) to stringResource(R.string.player_hint_menu)
        else stringResource(R.string.player_key_digits) to stringResource(R.string.player_hint_channel_number),
        stringResource(R.string.player_key_back) to stringResource(R.string.player_hint_close),
    )
    KeyHint(items, keyColor = c.t2, color = c.t3)
}
