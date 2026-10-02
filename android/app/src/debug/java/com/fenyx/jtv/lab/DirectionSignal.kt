package com.fenyx.jtv.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fenyx.jtv.data.Channel

/** A · "Signal": the spec's direction. Flat near-black, one amber accent, system type, 12dp tiles. */
object SignalTokens {
    val bg = Color(0xFF0F1013)
    val surface1 = Color(0xFF17191D)
    val surface2 = Color(0xFF21242A)
    val hairline = Color(0xFF2E323A)
    val text = Color(0xFFECEDEF)
    val text2 = Color(0xFFA2A7B1)
    val text3 = Color(0xFF737883)
    val accent = Color(0xFFF2B544)
    val live = Color(0xFFFF5A4E)
    val type = FontFamily.Default
}

private val T = SignalTokens

@Composable
private fun SignalTabs(state: LabScreenState, padH: androidx.compose.ui.unit.Dp) {
    LazyRow(contentPadding = PaddingValues(horizontal = padH, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.tabs, key = { it }) { g ->
            val selected = g == state.tab
            LabPressable(onClick = { state.onTab(g) }) { h ->
                Text(
                    tabLabel(g),
                    style = TextStyle(color = if (selected) T.accent else T.text2, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = T.type),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) T.surface2 else Color.Transparent)
                        .border(2.dp, if (h.active) T.accent else Color.Transparent, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun SignalLive(state: LabScreenState) {
    val channels = rememberChannels(state.vm, state.tab)
    val favs by state.vm.favoriteChannels.collectAsState()
    val padH = if (state.formFactor == FormFactor.Tv) 48.dp else 16.dp
    Column(Modifier.fillMaxSize().background(T.bg)) {
        SignalTabs(state, padH)
        BoxWithConstraints(Modifier.weight(1f)) {
            val minTile = when (state.formFactor) { FormFactor.Phone -> 104.dp; FormFactor.Tablet -> 140.dp; FormFactor.Tv -> 140.dp }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minTile),
                contentPadding = PaddingValues(start = padH, end = padH, bottom = 24.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(channels, key = { it.id }) { ch ->
                    SignalTile(ch, ch.id in favs, state.formFactor, onClick = { state.onPlay(ch) }, onLong = { state.onMenu(ch) })
                }
            }
        }
    }
}

@Composable
private fun SignalTile(ch: Channel, fav: Boolean, ff: FormFactor, onClick: () -> Unit, onLong: () -> Unit) {
    LabPressable(onClick = onClick, onLongClick = onLong) { h ->
        val scale = if (h.active) 1.05f else 1f
        Column(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(12.dp))
                .background(if (h.pressed) T.surface2 else T.surface1)
                .border(2.dp, if (h.active) T.accent else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ch.channelNumber}", style = TextStyle(color = T.text3, fontSize = 13.sp, fontFamily = T.type, fontFeatureSettings = "tnum"))
                Spacer(Modifier.weight(1f))
                if (fav) Icon(Icons.Filled.Star, null, tint = T.accent, modifier = Modifier.size(14.dp))
            }
            AsyncImage(
                model = ch.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 6.dp)
            )
            Text(
                ch.name, maxLines = if (ff == FormFactor.Phone) 2 else 1, overflow = TextOverflow.Ellipsis, minLines = if (ff == FormFactor.Phone) 2 else 1,
                style = TextStyle(color = T.text, fontSize = if (ff == FormFactor.Phone) 13.sp else 15.sp, fontWeight = FontWeight.Medium, fontFamily = T.type)
            )
        }
    }
}

@Composable
fun SignalGuide(state: LabScreenState, turnOn: () -> Unit) {
    if (!state.guideOn) { GuideOff(T.bg, T.text, T.text2, T.accent, T.type, turnOn); return }
    val channels = rememberChannels(state.vm, state.tab)
    val col = if (state.formFactor == FormFactor.Phone) 72.dp else 200.dp
    Column(Modifier.fillMaxSize().background(T.bg)) {
        SignalTabs(state, if (state.formFactor == FormFactor.Tv) 48.dp else 16.dp)
        GuideGrid(
            state, channels, channelColumn = col,
            windowMinutes = if (state.formFactor == FormFactor.Phone) 90 else 150,
            rowHeight = 64.dp, rowGap = 6.dp, background = T.bg, nowLineColor = T.accent,
            header = { start, dpm, earlier, later ->
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(col).padding(start = 4.dp)) {
                        val ph = state.formFactor == FormFactor.Phone
                        TextButtonLike(if (ph) "−30" else "Earlier", T.text2, T.accent, T.type, earlier)
                        TextButtonLike(if (ph) "+30" else "Later", T.text2, T.accent, T.type, later)
                    }
                    Box(Modifier.weight(1f)) {
                        ticks(start, if (state.formFactor == FormFactor.Phone) 90 else 150).forEach { t ->
                            Text(timeOf(t), style = TextStyle(color = T.text3, fontSize = 13.sp, fontFamily = T.type, fontFeatureSettings = "tnum"),
                                modifier = Modifier.offset(x = ((t - start) / 60_000f * dpm).dp + 6.dp))
                        }
                    }
                }
            },
            channelCell = { ch, m ->
                Row(m.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = ch.logoUrl, contentDescription = ch.name, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp, 28.dp))
                    if (state.formFactor != FormFactor.Phone) {
                        Spacer(Modifier.width(8.dp))
                        Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = T.text, fontSize = 14.sp, fontFamily = T.type))
                    }
                }
            },
            programCell = { cell, m ->
                LabPressable(onClick = { if (cell.isNow) state.onPlay(cell.channel) }, onLongClick = { state.onMenu(cell.channel) }, modifier = m.padding(end = 4.dp)) { h ->
                    Column(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                            .background(if (cell.isNow) T.surface2 else T.surface1)
                            .border(2.dp, if (h.active) T.accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (cell.width >= 48.dp) {
                            Text(cell.program.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = if (cell.isPast) T.text3 else T.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = T.type))
                            Text("${timeOf(cell.program.startMs)}–${timeOf(cell.program.stopMs)}", maxLines = 1, softWrap = false,
                                style = TextStyle(color = T.text3, fontSize = 12.sp, fontFamily = T.type, fontFeatureSettings = "tnum"))
                        }
                    }
                }
            },
            emptyCell = { m -> Box(m.padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).background(T.surface1)) }
        )
    }
}

/** Shared "guide is off" state: one sentence, one button. */
@Composable
fun GuideOff(bg: Color, text: Color, sub: Color, accent: Color, font: FontFamily, turnOn: () -> Unit) {
    Column(Modifier.fillMaxSize().background(bg).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("The programme guide is off.", style = TextStyle(color = text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = font))
        Spacer(Modifier.height(6.dp))
        Text("It downloads schedules only while it's on, to keep the app light.", style = TextStyle(color = sub, fontSize = 15.sp, fontFamily = font))
        Spacer(Modifier.height(20.dp))
        TextButtonLike("Turn on programme guide", text, accent, font, turnOn, prominent = true)
    }
}

@Composable
fun TextButtonLike(label: String, color: Color, accent: Color, font: FontFamily, onClick: () -> Unit, prominent: Boolean = false) {
    LabPressable(onClick = onClick) { h ->
        Text(
            label,
            style = TextStyle(color = if (h.active) accent else color, fontSize = if (prominent) 16.sp else 13.sp, fontWeight = FontWeight.Medium, fontFamily = font),
            modifier = Modifier
                .border(if (prominent || h.active) 2.dp else 0.dp, if (h.active) accent else if (prominent) color.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(horizontal = if (prominent) 18.dp else 6.dp, vertical = if (prominent) 10.dp else 4.dp)
        )
    }
}
