package com.fenyx.jtv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvClock
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

private val fadeInFast = fadeIn(tween(150))
private val fadeOutFast = fadeOut(tween(120))

private val BannerHint = listOf(
    "Up / Down" to "change channel", "Left" to "channel list", "Right" to "options", "Hold OK" to "menu",
)
private val BrowseHint = listOf(
    "Left / Right" to "browse", "OK" to "watch", "Up / Down" to "category", "0–9" to "channel number", "Back" to "hide",
)

/** Hides the banner (or touch controls) after [ms] unless something restarts the timer. */
@Composable
internal fun BannerAutoHide(ui: PlayerUi, ms: Long) {
    val ov = ui.overlay
    val token = ui.bannerToken
    LaunchedEffect(ov, token) {
        if (ov == PlayerOverlay.Banner) {
            delay(ms)
            if (ui.overlay == PlayerOverlay.Banner) {
                ui.overlay = PlayerOverlay.None
                ui.pointerChrome = false
            }
        }
    }
}

/** Data the overlays need from the player. Passed as one object so the call sites stay readable. */
internal class OverlayData(
    val playing: Channel?,
    val currentGroup: String?,
    val browseGroups: List<String>,
    val resolveGroup: (String?) -> List<Channel>,
    val findByNumber: (Int) -> Channel?,
    val epg: EpgSource,
    val model: OptionsModel,
    val actions: PlayerActions,
)

private fun neighbours(groups: List<String>, g: String?): Pair<String?, String?> {
    if (groups.size < 2) return null to null
    val i = groups.indexOf(g ?: MainViewModel.GROUP_ALL).coerceAtLeast(0)
    val n = groups.size
    return groupLabel(groups[(i - 1 + n) % n]) to groupLabel(groups[(i + 1) % n])
}

// ───────────────────────── TV ─────────────────────────

@Composable
internal fun TvOverlays(ui: PlayerUi, d: OverlayData) {
    val c = Jtv.colors
    val now = rememberMinuteClock()
    val act by rememberUpdatedState(d.actions)
    CompositionLocalProvider(LocalNow provides now) {
        Box(Modifier.fillMaxSize()) {
            BannerAutoHide(ui, 4_000)
            val ov = ui.overlay
            val browsing = ov == PlayerOverlay.Browse
            val strapVisible = ov == PlayerOverlay.Banner || browsing
            val bGroup = ui.browseGroup
            val browseList = remember(bGroup, browsing) { if (browsing) d.resolveGroup(bGroup) else emptyList() }
            val onRailFocus = remember(ui) { { i: Int -> ui.browseIndex = i } }
            val onRailClick = remember(bGroup) { { i: Int -> act.tune(bGroup, i) } }

            // Top-left: category header while browsing, or the mouse top bar.
            if (browsing) {
                val (prev, next) = neighbours(d.browseGroups, bGroup)
                BrowseHeader(
                    groupLabel(bGroup), browseList.size, prev, next,
                    Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 27.dp),
                )
            } else if (ui.pointerChrome && (ov == PlayerOverlay.None || ov == PlayerOverlay.Banner)) {
                PointerTopBar(d.playing, onBack = { act.leave() }, onOptions = { ui.openOptions() },
                    Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 27.dp))
            }

            // Top-right: number entry replaces the clock.
            val digits = ui.number
            val miss = ui.numberMiss
            if (digits.isNotEmpty() || miss != null) {
                val match = remember(digits) { digits.toIntOrNull()?.let(d.findByNumber) }
                NumberEntry(digits, match, miss, Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 27.dp))
            } else if (strapVisible) {
                Plaque(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 27.dp)) {
                    JtvClock(now, size = 34.sp, dateColor = c.t2)
                }
            }

            AnimatedVisibility(
                visible = strapVisible, enter = fadeInFast, exit = fadeOutFast,
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 27.dp)) {
                    if (browsing && browseList.isNotEmpty()) {
                        ChannelRail(
                            channels = browseList,
                            playingId = d.playing?.id,
                            focusIndex = ui.browseIndex.coerceIn(0, browseList.size - 1),
                            focusToken = ui.browseFocusToken,
                            takeFocus = true,
                            sizes = TvTiles,
                            onFocused = onRailFocus,
                            onClick = onRailClick,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    val strapCh = if (browsing) browseList.getOrNull(ui.browseIndex) ?: d.playing else d.playing
                    if (strapCh != null) EpgStrap(strapCh, d.epg, TvStrap)
                    Spacer(Modifier.height(8.dp))
                    Plaque(Modifier.padding(0.dp)) {
                        KeyHint(if (browsing) BrowseHint else BannerHint, keyColor = c.t2, color = c.t3)
                    }
                }
            }

            OptionsAndMenu(ui, d, touch = false, panelWidth = 420.dp, edge = 48.dp)
        }
    }
}

@Composable
private fun PointerTopBar(playing: Channel?, onBack: () -> Unit, onOptions: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OverVideoButton("Back", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
        if (playing != null) {
            Plaque {
                JText(
                    if (playing.channelNumber > 0) "${playing.channelNumber}  ${playing.name}" else playing.name,
                    18.sp, weight = FontWeight.SemiBold,
                )
            }
        }
        OverVideoButton("Options", onOptions)
    }
}

/** An outlined button on a solid dark plate, so it reads over any picture. */
@Composable
internal fun OverVideoButton(
    text: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    minHeight: Dp = if (Jtv.isTv) 44.dp else 56.dp,
    modifier: Modifier = Modifier,
) {
    JtvButton(
        text, onClick,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(StrapBg),
        icon = icon, minHeight = minHeight, fontSize = if (Jtv.isTv) 16.sp else 17.sp,
    )
}

/** Options panel (right) and quick menu (centre), shared by TV and touch. */
@Composable
internal fun OptionsAndMenu(ui: PlayerUi, d: OverlayData, touch: Boolean, panelWidth: Dp, edge: Dp) {
    val c = Jtv.colors
    val ov = ui.overlay
    Box(Modifier.fillMaxSize()) {
        // Touch: an invisible tap-catcher closes the panel/menu (no scrim is drawn).
        if (touch && (ov == PlayerOverlay.Options || ov == PlayerOverlay.Menu)) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { ui.overlay = PlayerOverlay.None },
            )
        }
        AnimatedVisibility(
            visible = ov == PlayerOverlay.Options, enter = fadeInFast, exit = fadeOutFast,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            OptionsPanel(
                page = ui.optionsPage,
                entry = ui.optionsEntry,
                model = d.model,
                channel = d.playing,
                actions = d.actions,
                touch = touch,
                onPage = { ui.optionsPage = it },
                onClose = { ui.overlay = PlayerOverlay.None },
                modifier = Modifier.width(panelWidth).fillMaxHeight().background(c.s1)
                    .padding(start = 24.dp, end = edge, top = 27.dp, bottom = 27.dp),
            )
        }
        AnimatedVisibility(
            visible = ov == PlayerOverlay.Menu, enter = fadeInFast, exit = fadeOutFast,
            modifier = Modifier.align(Alignment.Center),
        ) {
            QuickMenu(
                model = d.model, channel = d.playing, actions = d.actions, touch = touch,
                onOpenPage = { ui.openOptions(it) }, onClose = { ui.overlay = PlayerOverlay.None },
            )
        }
    }
}

// ───────────────────────── Touch, full-screen video (tablet, phone landscape) ─────────────────────────

/** Tap toggles controls, long-press opens the quick menu, horizontal swipe zaps. */
internal fun Modifier.touchVideoGestures(ui: PlayerUi, actions: State<PlayerActions>, tapToggles: Boolean): Modifier = this
    .pointerInput(ui) {
        detectTapGestures(
            onTap = {
                if (!tapToggles) return@detectTapGestures
                when (ui.overlay) {
                    PlayerOverlay.None -> ui.showBanner()
                    else -> ui.overlay = PlayerOverlay.None
                }
            },
            onLongPress = { ui.overlay = PlayerOverlay.Menu },
        )
    }
    .pointerInput(ui) {
        var total = 0f
        val threshold = 80.dp.toPx()
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (abs(total) > threshold) actions.value.zap(if (total < 0) 1 else -1) },
            onHorizontalDrag = { change, dx -> total += dx; change.consume() },
        )
    }

@Composable
internal fun TouchOverlays(ui: PlayerUi, d: OverlayData, compact: Boolean) {
    val now = rememberMinuteClock()
    val act by rememberUpdatedState(d.actions)
    val pad = if (compact) 16.dp else 32.dp
    val tiles = if (compact) PhoneLandTiles else TabletTiles
    val strap = if (compact) PhoneLandStrap else TabletStrap
    CompositionLocalProvider(LocalNow provides now) {
        Box(Modifier.fillMaxSize()) {
            BannerAutoHide(ui, 6_000)
            val controls = ui.overlay == PlayerOverlay.Banner
            val playingId = d.playing?.id
            LaunchedEffect(controls) {
                if (controls) {
                    ui.browseGroup = d.currentGroup ?: MainViewModel.GROUP_ALL
                    ui.browseIndex = d.resolveGroup(ui.browseGroup).indexOfFirst { it.id == playingId }.coerceAtLeast(0)
                    ui.browseFocusToken++
                }
            }
            val bGroup = ui.browseGroup
            val list = remember(bGroup, controls) { if (controls) d.resolveGroup(bGroup) else emptyList() }
            val onRailClick = remember(bGroup) { { i: Int -> act.tune(bGroup, i) } }
            val noFocus = remember { { _: Int -> } }

            AnimatedVisibility(visible = controls, enter = fadeInFast, exit = fadeOutFast) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = pad, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OverVideoButton("Back", { act.leave() }, icon = Icons.AutoMirrored.Filled.ArrowBack)
                        CategoryChips(
                            d.browseGroups, bGroup,
                            onPick = { g ->
                                ui.browseGroup = g
                                ui.browseIndex = d.resolveGroup(g).indexOfFirst { it.id == playingId }.coerceAtLeast(0)
                                ui.browseFocusToken++
                                ui.bannerToken++
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OverVideoButton("Options", { ui.openOptions() })
                        if (!compact) Plaque { JtvClock(now, size = 32.sp, dateColor = Jtv.colors.t2) }
                    }
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = pad, end = pad, bottom = pad)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (list.isNotEmpty()) {
                                ChannelRail(
                                    channels = list, playingId = playingId,
                                    focusIndex = ui.browseIndex.coerceIn(0, list.size - 1),
                                    focusToken = ui.browseFocusToken, takeFocus = false, sizes = tiles,
                                    onFocused = noFocus, onClick = onRailClick, modifier = Modifier.weight(1f),
                                )
                            } else Spacer(Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            OverVideoButton("Previous", { act.zap(-1) }, icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, minHeight = tiles.h)
                            Spacer(Modifier.width(8.dp))
                            OverVideoButton("Next", { act.zap(1) }, icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, minHeight = tiles.h)
                        }
                        Spacer(Modifier.height(10.dp))
                        d.playing?.let { EpgStrap(it, d.epg, strap) }
                    }
                }
            }

            OptionsAndMenu(ui, d, touch = true, panelWidth = if (compact) 360.dp else 420.dp, edge = pad)
        }
    }
}

/** Category pills; the selected one is inverted. */
@Composable
internal fun CategoryChips(
    groups: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    overVideo: Boolean = true,
) {
    val c = Jtv.colors
    val sel = selected ?: MainViewModel.GROUP_ALL
    val start = remember(groups) { groups.indexOf(sel).coerceAtLeast(0) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
    LazyRow(
        state = state, modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(groups, key = { it }, contentType = { "chip" }) { g ->
            val isSel = g == sel
            JtvClickable(
                onClick = { onPick(g) },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(28.dp),
                container = if (isSel) c.inv else if (overVideo) StrapBg else c.s1,
            ) { focused ->
                JText(
                    groupLabel(g), 16.sp,
                    color = if (isSel || focused) c.invTx else c.tx,
                    weight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                )
            }
        }
    }
}
