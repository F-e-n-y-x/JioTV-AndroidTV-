package com.fenyx.jtv.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fenyx.jtv.R
import com.fenyx.jtv.data.Channel

/**
 * B · "Broadcast": guide-first, set-top-box character. Navy ground, square 4dp cells, monospaced
 * numbers and times, category colour only as a thin edge, focus = inverted cyan fill (the clearest
 * state from 3 m and free to render).
 */
object BroadcastTokens {
    val bg = Color(0xFF0A1220)
    val surface1 = Color(0xFF111C30)
    val surface2 = Color(0xFF1A2842)
    val line = Color(0xFF24324D)
    val text = Color(0xFFE8EEF8)
    val text2 = Color(0xFF93A3BD)
    val text3 = Color(0xFF66779A)
    val focus = Color(0xFF3CCFEA)
    val onFocus = Color(0xFF06101C)
    val sans = FontFamily(Font(R.font.lab_plexsans_regular, FontWeight.Normal), Font(R.font.lab_plexsans_semibold, FontWeight.SemiBold))
    val mono = FontFamily(Font(R.font.lab_plexmono_medium, FontWeight.Medium))

    fun category(group: String): Color = when {
        group.contains("News", true) -> Color(0xFFF2C14E)
        group.contains("Sport", true) -> Color(0xFF5BD47A)
        group.contains("Kid", true) -> Color(0xFFF07AA8)
        group.contains("Movie", true) -> Color(0xFF9B8CFF)
        group.contains("Music", true) -> Color(0xFF4FC3C9)
        group.contains("Devotional", true) -> Color(0xFFE89A5B)
        else -> Color(0xFF5A7BA8)
    }
}

private val T = BroadcastTokens

@Composable
private fun BroadcastTabs(state: LabScreenState, padH: androidx.compose.ui.unit.Dp) {
    LazyRow(contentPadding = PaddingValues(horizontal = padH), horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.background(T.surface1)) {
        items(state.tabs, key = { it }) { g ->
            val selected = g == state.tab
            LabPressable(onClick = { state.onTab(g) }) { h ->
                Column(Modifier.background(if (h.active) T.focus else Color.Transparent).padding(horizontal = 14.dp)) {
                    Text(
                        tabLabel(g).uppercase(),
                        style = TextStyle(color = if (h.active) T.onFocus else if (selected) T.text else T.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = T.sans, letterSpacing = 1.sp),
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) T.category(g) else Color.Transparent))
                }
            }
        }
    }
}

@Composable
fun BroadcastLive(state: LabScreenState) {
    val channels = rememberChannels(state.vm, state.tab)
    val epg by state.vm.epgData.collectAsState()
    val favs by state.vm.favoriteChannels.collectAsState()
    val tv = state.formFactor == FormFactor.Tv
    val phone = state.formFactor == FormFactor.Phone
    val padH = if (tv) 48.dp else if (phone) 0.dp else 16.dp
    Column(Modifier.fillMaxSize().background(T.bg)) {
        BroadcastTabs(state, padH)
        LazyColumn(contentPadding = PaddingValues(start = padH, end = padH, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(channels, key = { it.id }) { ch ->
                val programs = programsOf(state.vm, epg, ch.id, state.guideOn)
                val (now, next) = programs.nowAndNext(state.now)
                LabPressable(onClick = { state.onPlay(ch) }, onLongClick = { state.onMenu(ch) }) { h ->
                    val fg = if (h.active) T.onFocus else T.text
                    val fg2 = if (h.active) T.onFocus.copy(alpha = 0.75f) else T.text2
                    Row(
                        Modifier.fillMaxWidth().height(if (phone) 60.dp else 56.dp)
                            .background(if (h.active) T.focus else if (h.pressed) T.surface2 else T.surface1),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(T.category(ch.group)))
                        Text(
                            ch.channelNumber.toString().padStart(3, ' '),
                            style = TextStyle(color = fg2, fontSize = 15.sp, fontFamily = T.mono),
                            modifier = Modifier.width(52.dp).padding(start = 10.dp)
                        )
                        Box(Modifier.size(60.dp, 34.dp).background(if (h.active) Color.White.copy(alpha = 0.85f) else T.surface2), contentAlignment = Alignment.Center) {
                            AsyncImage(model = ch.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(3.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(if (phone) Modifier.weight(1f) else Modifier.width(220.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                    style = TextStyle(color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = T.sans))
                                if (ch.id in favs) Icon(Icons.Filled.Star, "Favourite", tint = if (h.active) T.onFocus else T.focus, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                            }
                            if (phone) Text(now?.title ?: ch.group, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = fg2, fontSize = 13.sp, fontFamily = T.sans))
                        }
                        if (!phone) {
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(now?.title ?: (if (state.guideOn) "—" else "${ch.group} · ${ch.language}"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = fg, fontSize = 15.sp, fontFamily = T.sans))
                                if (now != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (h.active) T.onFocus.copy(alpha = 0.25f) else T.line)) {
                                        Box(Modifier.fillMaxWidth(now.progress(state.now)).fillMaxHeight().background(if (h.active) T.onFocus else T.focus))
                                    }
                                }
                            }
                            if (next != null) {
                                Text("${timeOf(next.startMs)}  ${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = fg2, fontSize = 13.sp, fontFamily = T.sans), modifier = Modifier.width(220.dp).padding(end = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BroadcastGuide(state: LabScreenState, turnOn: () -> Unit) {
    if (!state.guideOn) { GuideOff(T.bg, T.text, T.text2, T.focus, T.sans, turnOn); return }
    val channels = rememberChannels(state.vm, state.tab)
    val phone = state.formFactor == FormFactor.Phone
    val col = if (phone) 64.dp else 180.dp
    val window = if (phone) 90 else 150
    Column(Modifier.fillMaxSize().background(T.bg)) {
        BroadcastTabs(state, if (state.formFactor == FormFactor.Tv) 48.dp else 0.dp)
        GuideGrid(
            state, channels, channelColumn = col, windowMinutes = window,
            rowHeight = 52.dp, rowGap = 2.dp, background = T.bg, nowLineColor = T.focus,
            header = { start, dpm, earlier, later ->
                Row(Modifier.fillMaxWidth().height(30.dp).background(T.surface1), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(col)) {
                        TextButtonLike(if (phone) "−30" else "EARLIER", T.text2, T.focus, T.mono, earlier)
                        TextButtonLike(if (phone) "+30" else "LATER", T.text2, T.focus, T.mono, later)
                    }
                    Box(Modifier.weight(1f)) {
                        ticks(start, window).forEach { t ->
                            Text(timeOf(t), style = TextStyle(color = T.text2, fontSize = 13.sp, fontFamily = T.mono),
                                modifier = Modifier.offset(x = ((t - start) / 60_000f * dpm).dp + 4.dp))
                        }
                    }
                }
            },
            channelCell = { ch, m ->
                Row(m.background(T.surface1), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(T.category(ch.group)))
                    if (!phone) Text(ch.channelNumber.toString(), style = TextStyle(color = T.text2, fontSize = 14.sp, fontFamily = T.mono), modifier = Modifier.width(44.dp).padding(start = 8.dp))
                    AsyncImage(model = ch.logoUrl, contentDescription = ch.name, contentScale = ContentScale.Fit, modifier = Modifier.size(52.dp, 30.dp).padding(start = 4.dp))
                    if (!phone) Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = T.text, fontSize = 13.sp, fontFamily = T.sans), modifier = Modifier.padding(start = 6.dp, end = 4.dp))
                }
            },
            programCell = { cell, m ->
                LabPressable(onClick = { if (cell.isNow) state.onPlay(cell.channel) }, onLongClick = { state.onMenu(cell.channel) }, modifier = m.padding(start = 2.dp)) { h ->
                    Column(
                        Modifier.fillMaxSize().background(if (h.active) T.focus else if (cell.isNow) T.surface2 else T.surface1).padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        if (cell.width >= 48.dp) {
                            Text(cell.program.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = if (h.active) T.onFocus else if (cell.isPast) T.text3 else T.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = T.sans))
                            Text(timeOf(cell.program.startMs), softWrap = false, style = TextStyle(color = if (h.active) T.onFocus else T.text3, fontSize = 12.sp, fontFamily = T.mono))
                        }
                    }
                }
            },
            emptyCell = { m -> Box(m.padding(start = 2.dp).background(T.surface1)) }
        )
    }
}
