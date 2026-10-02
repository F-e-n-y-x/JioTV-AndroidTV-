package com.fenyx.jtv.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fenyx.jtv.R
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram

/**
 * C · "Listings": an evening TV-listings page crossed with a set-top box's channel banner.
 * List-first (no tiles), big tabular channel numbers, warm graphite ground (not cold black),
 * paper-white inverted focus, and red used ONLY as the "on air" tally. Type: Atkinson Hyperlegible
 * (built for legibility at distance) + Red Hat Mono for numbers and times.
 */
object ListingsTokens {
    val bg = Color(0xFF14120F)
    val surface = Color(0xFF1D1A16)
    val line = Color(0xFF2E2A24)
    val paper = Color(0xFFF3EEE4)
    val ink = Color(0xFF14120F)
    val text2 = Color(0xFFB5AC9C)
    val text3 = Color(0xFF857D70)
    val tally = Color(0xFFE5483A)
    val sans = FontFamily(Font(R.font.lab_atkinson_regular, FontWeight.Normal), Font(R.font.lab_atkinson_bold, FontWeight.Bold))
    val mono = FontFamily(Font(R.font.lab_redhatmono_medium, FontWeight.Medium), Font(R.font.lab_redhatmono_bold, FontWeight.Bold))
}

private val T = ListingsTokens

@Composable
private fun ListingsTabs(state: LabScreenState, padH: Dp) {
    LazyRow(contentPadding = PaddingValues(horizontal = padH, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(state.tabs, key = { it }) { g ->
            val selected = g == state.tab
            LabPressable(onClick = { state.onTab(g) }) { h ->
                Column(
                    Modifier.background(if (h.active) T.paper else Color.Transparent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(tabLabel(g), style = TextStyle(color = if (h.active) T.ink else if (selected) T.paper else T.text2, fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontFamily = T.sans))
                    Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(2.dp).background(if (selected && !h.active) T.paper else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun OnAirDot(size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(T.tally))

@Composable
private fun ListingRow(
    ch: Channel, now: EpgProgram?, fav: Boolean, guideOn: Boolean, big: Boolean, selected: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onFocused: () -> Unit
) {
    LabPressable(onClick = onClick, onLongClick = onLong) { h ->
        LaunchedEffect(h.active) { if (h.active) onFocused() }
        val fg = if (h.active) T.ink else T.paper
        val fg2 = if (h.active) T.ink.copy(alpha = 0.7f) else T.text2
        Row(
            Modifier.fillMaxWidth().height(if (big) 68.dp else 64.dp)
                .background(if (h.active) T.paper else if (h.pressed || selected) T.surface else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(ch.channelNumber.toString(), style = TextStyle(color = fg, fontSize = if (big) 28.sp else 22.sp, fontWeight = FontWeight.Bold, fontFamily = T.mono),
                modifier = Modifier.width(if (big) 76.dp else 60.dp))
            // Small logo plate: recognition at a glance, without turning the list into tiles.
            Box(Modifier.padding(end = 12.dp).size(44.dp, 28.dp).background(if (h.active) T.ink.copy(alpha = 0.08f) else T.surface, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                AsyncImage(model = ch.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(3.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        style = TextStyle(color = fg, fontSize = if (big) 19.sp else 17.sp, fontWeight = FontWeight.Bold, fontFamily = T.sans))
                    if (fav) Icon(Icons.Filled.Star, "Favourite", tint = fg2, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (now != null) { OnAirDot(7.dp); Spacer(Modifier.width(6.dp)) }
                    Text(now?.title ?: "${ch.group} · ${ch.language}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = fg2, fontSize = 14.sp, fontFamily = T.sans))
                }
            }
        }
    }
}

@Composable
fun ListingsLive(state: LabScreenState) {
    val channels = rememberChannels(state.vm, state.tab)
    val epg by state.vm.epgData.collectAsState()
    val favs by state.vm.favoriteChannels.collectAsState()
    val split = state.formFactor != FormFactor.Phone
    val padH = if (state.formFactor == FormFactor.Tv) 48.dp else 16.dp
    var focused by remember(state.tab) { mutableStateOf<Channel?>(null) }
    Column(Modifier.fillMaxSize().background(T.bg)) {
        ListingsTabs(state, padH)
        Row(Modifier.weight(1f).padding(horizontal = padH)) {
            LazyColumn(Modifier.weight(if (split) 0.56f else 1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(channels, key = { it.id }) { ch ->
                    val now = programsOf(state.vm, epg, ch.id, state.guideOn).nowAndNext(state.now).first
                    ListingRow(ch, now, ch.id in favs, state.guideOn, big = state.formFactor == FormFactor.Tv,
                        selected = split && (focused ?: channels.firstOrNull())?.id == ch.id,
                        // Split layout + touch: the first tap previews, the second (or Watch) plays.
                        // Remote/mouse already preview on focus/hover, so their OK/click plays.
                        onClick = { if (split && focused?.id != ch.id) focused = ch else state.onPlay(ch) },
                        onLong = { state.onMenu(ch) }, onFocused = { focused = ch })
                }
            }
            if (split) {
                Spacer(Modifier.width(24.dp))
                Preview(state, focused ?: channels.firstOrNull(), epg, Modifier.weight(0.44f).fillMaxHeight())
            }
        }
    }
}

/** The focused channel, banner-style: what's on, until when, what's next. Updates on focus. */
@Composable
private fun Preview(state: LabScreenState, ch: Channel?, epg: Map<String, List<EpgProgram>>, modifier: Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 24.dp).background(T.surface, RoundedCornerShape(8.dp)).padding(20.dp)) {
        if (ch == null) return@Column
        val (now, next) = programsOf(state.vm, epg, ch.id, state.guideOn).nowAndNext(state.now)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(T.bg, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            AsyncImage(model = ch.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.45f))
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ch.channelNumber.toString(), style = TextStyle(color = T.text2, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = T.mono))
            Spacer(Modifier.width(10.dp))
            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = T.paper, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = T.sans))
        }
        Text("${ch.group} · ${ch.language}", style = TextStyle(color = T.text3, fontSize = 14.sp, fontFamily = T.sans), modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(16.dp))
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnAirDot(); Spacer(Modifier.width(8.dp))
                Text("ON AIR  ${timeOf(now.startMs)}–${timeOf(now.stopMs)}", style = TextStyle(color = T.tally, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = T.mono, letterSpacing = 1.sp))
            }
            Text(now.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = T.paper, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = T.sans), modifier = Modifier.padding(top = 4.dp))
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(2.dp).background(T.line)) {
                Box(Modifier.fillMaxWidth(now.progress(state.now)).fillMaxHeight().background(T.paper))
            }
            if (next != null) Text("Next  ${timeOf(next.startMs)}  ${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = T.text2, fontSize = 14.sp, fontFamily = T.sans), modifier = Modifier.padding(top = 10.dp))
        } else {
            Text(if (state.guideOn) "No schedule for this channel." else "Turn on the programme guide to see what's on.",
                style = TextStyle(color = T.text3, fontSize = 14.sp, fontFamily = T.sans))
        }
        Spacer(Modifier.weight(1f))
        if (state.formFactor != FormFactor.Tv) {
            LabPressable(onClick = { state.onPlay(ch) }) { h ->
                Text("Watch", style = TextStyle(color = T.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = T.sans),
                    modifier = Modifier.background(if (h.pressed || h.active) T.paper.copy(alpha = 0.85f) else T.paper, RoundedCornerShape(6.dp)).padding(horizontal = 28.dp, vertical = 12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(if (state.formFactor == FormFactor.Tv) "OK to watch · hold OK for options" else "Tap again to watch · hold for options",
            style = TextStyle(color = T.text3, fontSize = 13.sp, fontFamily = T.sans))
    }
}

@Composable
fun ListingsGuide(state: LabScreenState, turnOn: () -> Unit) {
    if (!state.guideOn) { GuideOff(T.bg, T.paper, T.text2, T.paper, T.sans, turnOn); return }
    val channels = rememberChannels(state.vm, state.tab)
    val epg by state.vm.epgData.collectAsState()
    val split = state.formFactor != FormFactor.Phone
    val padH = if (state.formFactor == FormFactor.Tv) 48.dp else 16.dp
    var focused by remember(state.tab) { mutableStateOf<Channel?>(null) }
    Column(Modifier.fillMaxSize().background(T.bg)) {
        ListingsTabs(state, padH)
        Row(Modifier.weight(1f).padding(horizontal = padH)) {
            // Left: channels with now + next (on phone this IS the guide: a listings page).
            LazyColumn(Modifier.weight(if (split) 0.42f else 1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(channels, key = { it.id }) { ch ->
                    val (now, next) = programsOf(state.vm, epg, ch.id, true).nowAndNext(state.now)
                    LabPressable(onClick = { state.onPlay(ch) }, onLongClick = { state.onMenu(ch) }) { h ->
                        LaunchedEffect(h.active) { if (h.active) focused = ch }
                        val fg = if (h.active) T.ink else T.paper
                        val fg2 = if (h.active) T.ink.copy(alpha = 0.7f) else T.text2
                        Row(Modifier.fillMaxWidth().background(if (h.active) T.paper else Color.Transparent, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(ch.channelNumber.toString(), style = TextStyle(color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = T.mono), modifier = Modifier.width(56.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = T.sans))
                                if (now != null) Text("${timeOf(now.startMs)}  ${now.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = fg, fontSize = 14.sp, fontFamily = T.sans))
                                if (next != null) Text("${timeOf(next.startMs)}  ${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = fg2, fontSize = 14.sp, fontFamily = T.sans))
                            }
                        }
                    }
                }
            }
            if (split) {
                Spacer(Modifier.width(24.dp))
                Schedule(state, focused ?: channels.firstOrNull(), epg, Modifier.weight(0.58f).fillMaxHeight())
            }
        }
    }
}

/** The day's schedule for one channel, like a printed listing: time column, titles, the live one marked. */
@Composable
private fun Schedule(state: LabScreenState, ch: Channel?, epg: Map<String, List<EpgProgram>>, modifier: Modifier) {
    Column(modifier.padding(top = 8.dp)) {
        if (ch == null) return@Column
        val programs = programsOf(state.vm, epg, ch.id, true).filter { it.stopMs > state.now - 3 * 3600_000L }
        Text("${ch.channelNumber}  ${ch.name}", style = TextStyle(color = T.paper, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = T.sans), modifier = Modifier.padding(bottom = 10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(T.line))
        // Open at what's on now (one earlier show above it for context), not at the oldest entry.
        val nowIdx = programs.indexOfFirst { it.stopMs > state.now }.coerceAtLeast(0)
        val listState = key(ch.id, programs.size) { androidx.compose.foundation.lazy.rememberLazyListState((nowIdx - 1).coerceAtLeast(0)) }
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)) {
            itemsIndexed(programs, key = { _, p -> p.startMs }) { _, p ->
                val isNow = p.startMs <= state.now && p.stopMs > state.now
                val past = p.stopMs <= state.now
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                    Text(timeOf(p.startMs), style = TextStyle(color = if (isNow) T.tally else if (past) T.text3 else T.text2, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = T.mono), modifier = Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isNow) { OnAirDot(7.dp); Spacer(Modifier.width(6.dp)) }
                            Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = if (past) T.text3 else T.paper, fontSize = 16.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal, fontFamily = T.sans))
                        }
                        if (isNow && p.description.isNotBlank()) Text(p.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = T.text2, fontSize = 13.sp, fontFamily = T.sans), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
