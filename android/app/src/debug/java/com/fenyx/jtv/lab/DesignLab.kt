package com.fenyx.jtv.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.flow.first

/** The three directions being compared. Each owns its tokens, layout and focus language. */
enum class Direction(val label: String, val inChrome: Boolean) {
    A("A · Signal", false), B("B · Broadcast", false),   // round 1, rejected by the owner (kept for reference)
    C("C · Listings (round 1)", true),
    D("C2 · Dial", true)                                  // round 2, from docs/v2/critique-C-round1.md
}
enum class LabScreen(val label: String) { Live("Live"), Guide("Guide") }

/**
 * v2 Design Lab: the SAME two screens (Live, Guide) in three visual directions, on real channels and
 * real EPG, adapting to phone / tablet / TV and to touch, mouse and D-pad. Debug builds only.
 */
@Composable
fun DesignLab(vm: MainViewModel, onPlay: (Int, String?) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    var direction by rememberSaveable { mutableStateOf(Direction.D) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(systemDark) }
    var screen by rememberSaveable { mutableStateOf(LabScreen.Live) }
    // Guide data follows the real "EPG mode" setting at start: no EPG work unless the guide is on.
    var guideOn by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { if (guideOn == null) guideOn = settings.epgModeFlow.first() }
    LaunchedEffect(Unit) { vm.fetchChannels() }

    val tabs = rememberTabs(vm)
    var tab by rememberSaveable { mutableStateOf(MainViewModel.GROUP_ALL) }
    LaunchedEffect(tabs) { if (tab !in tabs && tabs.isNotEmpty()) tab = tabs.first() }
    var menuFor by remember { mutableStateOf<Channel?>(null) }

    val state = LabScreenState(
        vm = vm,
        formFactor = rememberFormFactor(),
        tabs = tabs,
        tab = tab,
        onTab = { tab = it },
        guideOn = guideOn == true,
        now = rememberNow(),
        onPlay = { ch -> onPlay(vm.playIndexOf(ch), tab) },
        onMenu = { ch -> menuFor = ch }
    )

    Column(Modifier.fillMaxSize().background(Color(0xFF000000)).safeDrawingPadding()) {
        LabChrome(
            direction = direction, onDirection = { direction = it },
            screen = screen, onScreen = { screen = it },
            guideOn = guideOn == true, onGuide = { guideOn = it },
            dark = dark, onDark = { dark = it },
            onExit = onExit
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (direction) {
                Direction.A -> if (screen == LabScreen.Live) SignalLive(state) else SignalGuide(state) { guideOn = true }
                Direction.B -> if (screen == LabScreen.Live) BroadcastLive(state) else BroadcastGuide(state) { guideOn = true }
                Direction.C -> if (screen == LabScreen.Live) ListingsLive(state) else ListingsGuide(state) { guideOn = true }
                Direction.D -> if (screen == LabScreen.Live) DialLive(state, dark) else DialGuide(state, dark) { guideOn = true }
            }
        }
    }

    menuFor?.let { ch -> QuickMenu(direction, ch, vm, onPlay = { menuFor = null; state.onPlay(ch) }, onDismiss = { menuFor = null }) }
}

// ── Lab chrome: deliberately plain (system font, grey) so it never biases the comparison. ──

private val chromeText = TextStyle(color = Color(0xFFBDBDBD), fontSize = 13.sp, fontFamily = FontFamily.Default)

@Composable
private fun LabChrome(
    direction: Direction, onDirection: (Direction) -> Unit,
    screen: LabScreen, onScreen: (LabScreen) -> Unit,
    guideOn: Boolean, onGuide: (Boolean) -> Unit,
    dark: Boolean, onDark: (Boolean) -> Unit,
    onExit: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B1B1B)).horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("DESIGN LAB", style = chromeText.copy(fontWeight = FontWeight.Bold, color = Color(0xFF8A8A8A)))
        Spacer(Modifier.width(6.dp))
        Direction.entries.filter { it.inChrome }.forEach { d -> ChromeChip(d.label, d == direction) { onDirection(d) } }
        Spacer(Modifier.width(10.dp))
        LabScreen.entries.forEach { s -> ChromeChip(s.label, s == screen) { onScreen(s) } }
        Spacer(Modifier.width(10.dp))
        ChromeChip(if (guideOn) "Guide data: on" else "Guide data: off", guideOn) { onGuide(!guideOn) }
        Spacer(Modifier.width(10.dp))
        ChromeChip(if (dark) "Theme: dark" else "Theme: light", true) { onDark(!dark) }
        Spacer(Modifier.width(10.dp))
        ChromeChip("Exit lab", false, onExit)
    }
}

@Composable
private fun ChromeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    LabPressable(onClick = onClick) { h ->
        Text(
            label,
            style = chromeText.copy(color = if (selected) Color.White else Color(0xFFBDBDBD)),
            modifier = Modifier
                .background(if (selected) Color(0xFF3A3A3A) else Color.Transparent, RoundedCornerShape(4.dp))
                .border(if (h.active) 2.dp else 0.dp, if (h.active) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

// ── Quick menu (hold OK / right-click / long-press), styled by the active direction ──

private data class MenuStyle(val bg: Color, val text: Color, val sub: Color, val focusBg: Color, val focusText: Color, val radius: Int, val font: FontFamily)

private fun menuStyle(d: Direction) = when (d) {
    Direction.A -> MenuStyle(SignalTokens.surface1, SignalTokens.text, SignalTokens.text2, SignalTokens.surface2, SignalTokens.accent, 12, FontFamily.Default)
    Direction.B -> MenuStyle(BroadcastTokens.surface1, BroadcastTokens.text, BroadcastTokens.text2, BroadcastTokens.focus, BroadcastTokens.onFocus, 4, BroadcastTokens.sans)
    Direction.C -> MenuStyle(ListingsTokens.surface, ListingsTokens.paper, ListingsTokens.text2, ListingsTokens.paper, ListingsTokens.ink, 6, ListingsTokens.sans)
    Direction.D -> DialTokens.dark.let { MenuStyle(it.surface, it.ink, it.text2, it.invBg, it.invInk, 4, DialTokens.sans) }
}

@Composable
private fun QuickMenu(d: Direction, ch: Channel, vm: MainViewModel, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val st = menuStyle(d)
    val favs by vm.favoriteChannels.collectAsState()
    val isFav = ch.id in favs
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(min = 280.dp, max = 360.dp).background(st.bg, RoundedCornerShape(st.radius.dp)).padding(8.dp)
        ) {
            Text(ch.name, style = TextStyle(color = st.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = st.font), modifier = Modifier.padding(12.dp, 10.dp, 12.dp, 2.dp))
            Text("${ch.channelNumber} · ${ch.group}", style = TextStyle(color = st.sub, fontSize = 13.sp, fontFamily = st.font), modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            val items = listOf(
                "Watch" to onPlay,
                (if (isFav) "Remove from favourites" else "Add to favourites") to { vm.toggleFavorite(ch.id); onDismiss() },
                "Close" to onDismiss
            )
            items.forEachIndexed { i, (label, action) ->
                LabPressable(onClick = action, focusRequester = if (i == 0) first else null, modifier = Modifier.fillMaxWidth()) { h ->
                    Text(
                        label,
                        style = TextStyle(color = if (h.active) st.focusText else st.text, fontSize = 16.sp, fontFamily = st.font),
                        modifier = Modifier.fillMaxWidth()
                            .background(if (h.active || h.pressed) st.focusBg else Color.Transparent, RoundedCornerShape(st.radius.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
