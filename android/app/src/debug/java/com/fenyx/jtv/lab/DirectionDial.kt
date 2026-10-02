package com.fenyx.jtv.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star as StarLine
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * C2 · "Dial" (round 2, from docs/v2/critique-C-round1.md).
 * Identity, in order of importance:
 *  1. The dial: the focused channel's number at display size, like a set-top box readout.
 *  2. The preview is a proportional 3-hour timeline (a mini guide), not an empty logo box.
 *  3. Strict monochrome ink/paper in BOTH themes, inverted selection, red ONLY for "now".
 *  4. A live clock header, the way a printed listing dates itself.
 * Light = cool white paper + black ink. Dark = near-black neutral (no navy, no brown).
 */
class DialPalette(
    val bg: Color, val surface: Color, val line: Color,
    val ink: Color, val text2: Color, val text3: Color,
    val invBg: Color, val invInk: Color,
    val plate: Color, val now: Color
)

object DialTokens {
    val light = DialPalette(
        bg = Color(0xFFF7F7F5), surface = Color(0xFFFFFFFF), line = Color(0xFFE2E2DF),
        ink = Color(0xFF111111), text2 = Color(0xFF55554F), text3 = Color(0xFF6B6B66),
        invBg = Color(0xFF111111), invInk = Color(0xFFF7F7F5),
        plate = Color(0xFF161616), now = Color(0xFFD7301F)
    )
    val dark = DialPalette(
        bg = Color(0xFF0B0B0C), surface = Color(0xFF141415), line = Color(0xFF262628),
        ink = Color(0xFFEDEDED), text2 = Color(0xFFA6A6A6), text3 = Color(0xFF848487),
        invBg = Color(0xFFEDEDED), invInk = Color(0xFF0B0B0C),
        plate = Color(0xFF1A1A1B), now = Color(0xFFFF4B3E)
    )
    // One spacing scale (4·8·12·16·24·32) and one type scale (12·14·16·18·22·28·40·64 sp) — every
    // size in this file comes from these. Text tertiary colours are ≥ 4.5:1 on their background.
    val sans = ListingsTokens.sans
    val mono = ListingsTokens.mono
}

private fun sansStyle(c: Color, size: TextUnit, w: FontWeight = FontWeight.Normal) = TextStyle(color = c, fontSize = size, fontWeight = w, fontFamily = DialTokens.sans)
private fun monoStyle(c: Color, size: TextUnit, w: FontWeight = FontWeight.Medium) = TextStyle(color = c, fontSize = size, fontWeight = w, fontFamily = DialTokens.mono)

private val dayFmt = ThreadLocal.withInitial { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }

/** Live clock + date, set like the masthead of a listings page. */
@Composable
private fun ClockMast(p: DialPalette, now: Long, big: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(timeOf(now), style = monoStyle(p.ink, if (big) 40.sp else 28.sp, FontWeight.Bold))
        Spacer(Modifier.width(12.dp))
        Text(dayFmt.get()!!.format(Date(now)).uppercase(), style = monoStyle(p.text3, if (big) 14.sp else 12.sp), modifier = Modifier.padding(bottom = if (big) 8.dp else 4.dp))
    }
}

@Composable
private fun DialTabs(p: DialPalette, state: LabScreenState, counts: Map<String, Int>, padStart: Dp) {
    val rowState = rememberLazyListState()
    // Keep the current category in view when it changes from the list (LEFT/RIGHT on TV).
    LaunchedEffect(state.tab) {
        val i = state.tabs.indexOf(state.tab)
        if (i >= 0) rowState.animateScrollToItem((i - 1).coerceAtLeast(0))
    }
    LazyRow(state = rowState, contentPadding = PaddingValues(start = padStart, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state.tabs, key = { it }) { g ->
            val selected = g == state.tab
            LabPressable(onClick = { state.onTab(g) }) { h ->
                val inv = h.active
                Row(
                    Modifier.heightIn(min = 48.dp)
                        .background(if (inv) p.invBg else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tabLabel(g), style = sansStyle(if (inv) p.invInk else if (selected) p.ink else p.text2, 16.sp, if (selected) FontWeight.Bold else FontWeight.Normal))
                    counts[g]?.let { Text(" $it", style = monoStyle(if (inv) p.invInk else p.text3, 12.sp)) }
                }
            }
        }
    }
}

/**
 * Every logo sits on a small near-black "screen" in both themes, like a tiny TV. Jio's logos mix
 * white-on-transparent and coloured marks; a dark screen keeps all of them readable, and the
 * miniature screens are part of the identity.
 */
@Composable
private fun LogoPlate(p: DialPalette, url: String, w: Dp, h: Dp) {
    Box(Modifier.size(w, h).background(p.plate, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp))
    }
}

/** How far through the current show we are: a hairline, not a dot. */
@Composable
private fun Elapsed(p: DialPalette, prog: EpgProgram, now: Long, inverted: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(if (inverted) p.invInk.copy(alpha = 0.25f) else p.line)) {
        Box(Modifier.fillMaxWidth(prog.progress(now)).fillMaxHeight().background(if (inverted) p.invInk else p.ink))
    }
}

/**
 * Proportional timeline from now to +[hours]: one cell per show, 30-min ticks, and a single red
 * "now" marker. This is the mini guide that replaces the empty preview box.
 */
@Composable
private fun Timeline(p: DialPalette, programs: List<EpgProgram>, now: Long, hours: Int, compact: Boolean) {
    val start = now - 15 * 60_000L
    val end = start + hours * 3600_000L
    val span = (end - start).toFloat()
    val visible = programs.filter { it.stopMs > start && it.startMs < end }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        Column {
            // Ticks
            Box(Modifier.fillMaxWidth().height(24.dp)) {
                var t = start - start % (30 * 60_000L) + 30 * 60_000L
                while (t < end) {
                    val x = w * ((t - start) / span)
                    // Skip labels that would be clipped at either edge.
                    if (x > 16.dp && x < w - 24.dp) Text(timeOf(t), style = monoStyle(p.text3, 12.sp), modifier = Modifier.offset(x = x - 16.dp))
                    t += 30 * 60_000L
                }
                // "Now" marker lives in the time row (never across show titles): a red tick + label.
                val nx = w * ((now - start) / span)
                Box(Modifier.offset(x = nx - 1.dp, y = 12.dp).width(2.dp).height(12.dp).background(p.now))
            }
            Box(Modifier.fillMaxWidth().height(if (compact) 48.dp else 56.dp).clipToBounds()) {
                visible.forEach { prog ->
                    val s = maxOf(prog.startMs, start); val e = minOf(prog.stopMs, end)
                    val x = w * ((s - start) / span); val cw = w * ((e - s) / span)
                    val isNow = prog.startMs <= now && prog.stopMs > now
                    Box(
                        Modifier.offset(x = x).width(cw).fillMaxHeight().padding(end = 2.dp)
                            .background(if (isNow) p.ink else p.surface)
                            .border(1.dp, p.line)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        // Too narrow to read → no text at all (never a squeezed, broken word).
                        if (cw >= 64.dp) Text(prog.title, maxLines = if (compact || cw < 120.dp) 1 else 2, overflow = TextOverflow.Ellipsis,
                            style = sansStyle(if (isNow) p.bg else p.ink, 14.sp, if (isNow) FontWeight.Bold else FontWeight.Normal))
                    }
                }
                // Continue the marker only as a hairline under the cells (no text there).
                val nx = w * ((now - start) / span)
                Box(Modifier.align(Alignment.BottomStart).offset(x = nx - 1.dp).width(2.dp).height(4.dp).background(p.now))
            }
        }
    }
}

@Composable
private fun DialRow(
    p: DialPalette, ch: Channel, now: EpgProgram?, nowMs: Long, fav: Boolean, selected: Boolean, tv: Boolean, compact: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onFocused: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    LabPressable(onClick = onClick, onLongClick = onLong, focusRequester = focusRequester) { h ->
        LaunchedEffect(h.active) { if (h.active) onFocused() }
        val inv = h.active || selected
        val fg = if (inv) p.invInk else p.ink
        val fg2 = if (inv) p.invInk.copy(alpha = 0.72f) else p.text2
        Row(
            Modifier.fillMaxWidth().height(if (tv) 72.dp else 68.dp)
                .background(if (inv) p.invBg else if (h.pressed) p.surface else Color.Transparent)
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The dial: the focused/selected number jumps to display size. Numbers are RIGHT-aligned
            // in their column (as numeric columns should be), so small and large numbers both sit
            // a fixed 16dp from the logo instead of leaving a gap in unselected rows.
            Box(Modifier.width(if (compact) 80.dp else 112.dp).padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) {
                Text(ch.channelNumber.toString(), maxLines = 1, softWrap = false,
                    style = monoStyle(fg, if (inv) (if (compact) 28.sp else 40.sp) else 22.sp, FontWeight.Bold))
            }
            LogoPlate(p, ch.logoUrl, 60.dp, 36.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), style = sansStyle(fg, 18.sp, FontWeight.Bold))
                    if (fav) Icon(Icons.Filled.Star, "Favourite", tint = fg2, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                }
                Text(now?.title ?: "${ch.group} · ${ch.language}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(fg2, 14.sp))
                if (now != null) Elapsed(p, now, nowMs, inv, Modifier.padding(top = 4.dp))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
}

@Composable
fun DialLive(state: LabScreenState, dark: Boolean) {
    val p = if (dark) DialTokens.dark else DialTokens.light
    val channels = rememberChannels(state.vm, state.tab)
    val epg by state.vm.epgData.collectAsState()
    val favs by state.vm.favoriteChannels.collectAsState()
    val tv = state.formFactor == FormFactor.Tv
    val split = state.formFactor != FormFactor.Phone
    val pad = if (tv) 48.dp else 16.dp
    var selected by remember(state.tab) { mutableStateOf<Channel?>(null) }
    val display by state.vm.displayChannels.collectAsState()
    val counts = remember(state.tabs, display) { state.tabs.associateWith { state.vm.getChannelsByGroup(it).size } }

    // TV: LEFT/RIGHT in the channel list switch category directly (one press each), instead of
    // UP to the tab strip and stepping across it. Focus lands on the new category's first channel.
    val listState = rememberLazyListState()
    val firstRow = remember { androidx.compose.ui.focus.FocusRequester() }
    var keySwitches by remember { mutableIntStateOf(0) }
    fun shiftTab(delta: Int) {
        val i = state.tabs.indexOf(state.tab)
        val j = (i + delta).coerceIn(0, state.tabs.lastIndex)
        if (i >= 0 && j != i) { state.onTab(state.tabs[j]); keySwitches++ }
    }
    LaunchedEffect(keySwitches) {
        if (keySwitches > 0) {
            listState.scrollToItem(0)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { firstRow.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        // Masthead: clock, then the tabs.
        if (split) {
            Row(Modifier.fillMaxWidth().padding(start = pad, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ClockMast(p, state.now, big = true)
                Spacer(Modifier.width(24.dp))
                Box(Modifier.weight(1f)) { DialTabs(p, state, counts, 0.dp) }
            }
        } else {
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                Box(Modifier.padding(start = pad, bottom = 8.dp)) { ClockMast(p, state.now, big = false) }
                DialTabs(p, state, counts, pad - 12.dp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink))

        Row(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.weight(if (split) 0.5f else 1f).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { shiftTab(-1); true }
                        Key.DirectionRight -> { shiftTab(1); true }
                        else -> false
                    }
                },
                state = listState,
                contentPadding = PaddingValues(start = if (split) pad - 12.dp else 0.dp, bottom = 24.dp)
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, ch ->
                    val programs = programsOf(state.vm, epg, ch.id, state.guideOn)
                    val now = programs.nowAndNext(state.now).first
                    val isSel = selected?.id == ch.id
                    DialRow(
                        p, ch, now, state.now, ch.id in favs, selected = isSel && !tv, tv = tv, compact = !split,
                        // Tablet/phone touch: first tap selects (preview / expand), second plays.
                        onClick = { if (!tv && !isSel) selected = ch else state.onPlay(ch) },
                        onLong = { state.onMenu(ch) },
                        onFocused = { if (split) selected = ch },
                        focusRequester = if (index == 0) firstRow else null
                    )
                    if (!split && isSel) PhoneExpanded(p, state, ch, programs, ch.id in favs)
                }
            }
            if (split) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(p.line))
                DialPreview(p, state, selected ?: channels.firstOrNull(), epg, favs, tv, Modifier.weight(0.5f).fillMaxHeight().padding(start = 32.dp, end = pad, top = 24.dp, bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun DialPreview(p: DialPalette, state: LabScreenState, ch: Channel?, epg: Map<String, List<EpgProgram>>, favs: Set<String>, tv: Boolean, modifier: Modifier) {
    Column(modifier) {
        if (ch == null) return@Column
        val programs = programsOf(state.vm, epg, ch.id, state.guideOn)
        val (now, next) = programs.nowAndNext(state.now)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.channelNumber.toString(), style = monoStyle(p.ink, 64.sp, FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            LogoPlate(p, ch.logoUrl, 112.dp, 64.dp)
        }
        Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(p.ink, 28.sp, FontWeight.Bold))
        Text("${ch.group} · ${ch.language}".uppercase(), style = monoStyle(p.text3, 12.sp), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(24.dp))
        if (now != null) {
            Text("NOW  ${timeOf(now.startMs)}–${timeOf(now.stopMs)}", style = monoStyle(p.now, 14.sp, FontWeight.Bold))
            Text(now.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = sansStyle(p.ink, 22.sp, FontWeight.Bold), modifier = Modifier.padding(top = 4.dp))
            if (now.description.isNotBlank()) Text(now.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = sansStyle(p.text2, 14.sp), modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Timeline(p, programs, state.now, hours = 3, compact = false)
            if (next != null) {
                Spacer(Modifier.height(16.dp))
                programs.filter { it.startMs > state.now }.take(3).forEach { prog ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(timeOf(prog.startMs), style = monoStyle(p.text2, 16.sp), modifier = Modifier.width(64.dp))
                        Text(prog.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(p.ink, 16.sp))
                    }
                }
            }
        } else {
            Text(if (state.guideOn) "No schedule for this channel." else "Programme guide is off. Turn it on to see what's on and what's next.",
                style = sansStyle(p.text2, 16.sp))
        }
        // Proximity: actions sit right under what they act on, not pinned to the far bottom.
        Spacer(Modifier.height(32.dp))
        DialActions(p, state, ch, ch.id in favs, tv)
    }
}

@Composable
private fun DialActions(p: DialPalette, state: LabScreenState, ch: Channel, fav: Boolean, tv: Boolean) {
    if (tv) {
        Text("LEFT / RIGHT  category      OK  watch      HOLD OK  options", style = monoStyle(p.text3, 12.sp))
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LabPressable(onClick = { state.onPlay(ch) }) { h ->
            Text("Watch", style = sansStyle(p.invInk, 16.sp, FontWeight.Bold),
                modifier = Modifier.heightIn(min = 48.dp).background(if (h.pressed) p.invBg.copy(alpha = 0.85f) else p.invBg, RoundedCornerShape(4.dp)).padding(horizontal = 32.dp, vertical = 12.dp))
        }
        LabPressable(onClick = { state.vm.toggleFavorite(ch.id) }) { h ->
            Row(Modifier.heightIn(min = 48.dp).border(1.dp, if (h.pressed) p.ink else p.line, RoundedCornerShape(4.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarLine, null, tint = p.ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (fav) "Favourite" else "Add to favourites", style = sansStyle(p.ink, 16.sp))
            }
        }
    }
}

/** Phone: the tablet preview, folded into the list as an expanded row. */
@Composable
private fun PhoneExpanded(p: DialPalette, state: LabScreenState, ch: Channel, programs: List<EpgProgram>, fav: Boolean) {
    Column(Modifier.fillMaxWidth().background(p.surface).padding(16.dp)) {
        val (now, _) = programs.nowAndNext(state.now)
        if (now != null) {
            Text("NOW  ${timeOf(now.startMs)}–${timeOf(now.stopMs)}", style = monoStyle(p.now, 12.sp, FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            Timeline(p, programs, state.now, hours = 2, compact = true)
            Spacer(Modifier.height(16.dp))
        } else if (!state.guideOn) {
            Text("Turn on the programme guide to see what's on.", style = sansStyle(p.text2, 14.sp))
            Spacer(Modifier.height(16.dp))
        }
        DialActions(p, state, ch, fav, tv = false)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
}

@Composable
fun DialGuide(state: LabScreenState, dark: Boolean, turnOn: () -> Unit) {
    val p = if (dark) DialTokens.dark else DialTokens.light
    if (!state.guideOn) { GuideOff(p.bg, p.ink, p.text2, p.ink, DialTokens.sans, turnOn); return }
    val channels = rememberChannels(state.vm, state.tab)
    val epg by state.vm.epgData.collectAsState()
    val tv = state.formFactor == FormFactor.Tv
    val split = state.formFactor != FormFactor.Phone
    val pad = if (tv) 48.dp else 16.dp
    var selected by remember(state.tab) { mutableStateOf<Channel?>(null) }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = pad, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ClockMast(p, state.now, big = split)
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f)) { DialTabs(p, state, emptyMap(), 0.dp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink))
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(if (split) 0.42f else 1f), contentPadding = PaddingValues(start = if (split) pad - 12.dp else 0.dp, bottom = 24.dp)) {
                items(channels, key = { it.id }) { ch ->
                    val (now, next) = programsOf(state.vm, epg, ch.id, true).nowAndNext(state.now)
                    val isSel = (selected ?: if (split) channels.firstOrNull() else null)?.id == ch.id
                    LabPressable(onClick = { if (split && !isSel && !tv) selected = ch else state.onPlay(ch) }, onLongClick = { state.onMenu(ch) }) { h ->
                        LaunchedEffect(h.active) { if (h.active) selected = ch }
                        val inv = h.active || (isSel && split && !tv)
                        val fg = if (inv) p.invInk else p.ink
                        val fg2 = if (inv) p.invInk.copy(alpha = 0.72f) else p.text2
                        Row(Modifier.fillMaxWidth().background(if (inv) p.invBg else Color.Transparent).padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                            Box(Modifier.width(72.dp).padding(end = 16.dp), contentAlignment = Alignment.TopEnd) {
                                Text(ch.channelNumber.toString(), style = monoStyle(fg, 18.sp, FontWeight.Bold))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(fg, 16.sp, FontWeight.Bold))
                                if (now != null) Text("${timeOf(now.startMs)}  ${now.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(fg, 14.sp))
                                if (next != null) Text("${timeOf(next.startMs)}  ${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(fg2, 14.sp))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                }
            }
            if (split) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(p.line))
                DialSchedule(p, state, selected ?: channels.firstOrNull(), epg, Modifier.weight(0.58f).fillMaxHeight().padding(start = 32.dp, end = pad, top = 16.dp))
            }
        }
    }
}

/** A printed day listing for one channel, with hairline rules; the live show is the only red. */
@Composable
private fun DialSchedule(p: DialPalette, state: LabScreenState, ch: Channel?, epg: Map<String, List<EpgProgram>>, modifier: Modifier) {
    Column(modifier) {
        if (ch == null) return@Column
        val programs = programsOf(state.vm, epg, ch.id, true).filter { it.stopMs > state.now - 3 * 3600_000L }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.channelNumber.toString(), style = monoStyle(p.ink, 28.sp, FontWeight.Bold))
            Spacer(Modifier.width(12.dp))
            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(p.ink, 22.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
            LogoPlate(p, ch.logoUrl, 72.dp, 42.dp)
        }
        Spacer(Modifier.height(12.dp))
        val nowIdx = programs.indexOfFirst { it.stopMs > state.now }.coerceAtLeast(0)
        val listState = key(ch.id, programs.size) { rememberLazyListState((nowIdx - 1).coerceAtLeast(0)) }
        LazyColumn(state = listState) {
            items(programs, key = { it.startMs }) { prog ->
                val isNow = prog.startMs <= state.now && prog.stopMs > state.now
                val past = prog.stopMs <= state.now
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(timeOf(prog.startMs), style = monoStyle(if (isNow) p.now else if (past) p.text3 else p.text2, 16.sp, if (isNow) FontWeight.Bold else FontWeight.Medium), modifier = Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(prog.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = sansStyle(if (past) p.text3 else p.ink, 16.sp, if (isNow) FontWeight.Bold else FontWeight.Normal))
                        if (isNow) {
                            if (prog.description.isNotBlank()) Text(prog.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = sansStyle(p.text2, 14.sp), modifier = Modifier.padding(top = 4.dp))
                            Elapsed(p, prog, state.now, false, Modifier.padding(top = 8.dp))
                        }
                    }
                    Text("${((prog.stopMs - prog.startMs) / 60_000)}m", style = monoStyle(p.text3, 12.sp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            }
        }
    }
}
