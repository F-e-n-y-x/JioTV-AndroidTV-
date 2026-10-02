package com.fenyx.jtv.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import com.fenyx.jtv.ui.components.NumberBlock
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.progress
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Icon
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
private val ControlsHint = listOf(
    "Left / Right" to "move", "Up / Down" to "row", "OK" to "select", "Back" to "hide",
)
private val ControlsPausedHint = listOf(
    "Left / Right" to "rewind / forward", "Hold" to "faster", "OK" to "play", "Back" to "hide",
)
private val BrowseHint = listOf(
    "Left / Right" to "browse", "OK" to "watch", "Up / Down" to "category", "0–9" to "channel number", "Back" to "hide",
)

/**
 * Hides the banner / touch controls (and the TV controls layer) after [ms] unless something restarts
 * the timer. The controls stay up while [paused], and while a finger is still dragging the seek bar.
 */
@Composable
internal fun BannerAutoHide(ui: PlayerUi, ms: Long, paused: Boolean = false, ts: Timeshift? = null) {
    val ov = ui.overlay
    val token = ui.bannerToken
    LaunchedEffect(ov, token, paused) {
        if (ov == PlayerOverlay.Banner || (ov == PlayerOverlay.Controls && !paused)) {
            delay(ms)
            while (ts?.scrubMs != null) delay(500)
            if (ui.overlay == ov) {
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
    val timeshift: Timeshift,
    /** A buffering spinner is up (drawn round Play/Pause in the controls). */
    val buffering: Boolean = false,
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
            BannerAutoHide(ui, 4_000, paused = d.model.paused, ts = d.timeshift)
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
            } else if (ui.pointerChrome && ov == PlayerOverlay.Banner) {
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
                    if (strapCh != null) {
                        val isPlaying = strapCh.id == d.playing?.id
                        EpgStrap(strapCh, d.epg, TvStrap, playing = isPlaying, timeshift = if (isPlaying) d.timeshift else null)
                    }
                    Spacer(Modifier.height(8.dp))
                    Plaque(Modifier.padding(0.dp)) {
                        KeyHint(if (browsing) BrowseHint else BannerHint, keyColor = c.t2, color = c.t3)
                    }
                }
            }

            AnimatedVisibility(visible = ov == PlayerOverlay.Controls, enter = fadeInFast, exit = fadeOutFast) {
                TvControls(ui, d)
            }

            OptionsAndMenu(ui, d, touch = false, panelWidth = 420.dp, edge = 48.dp)
        }
    }
}

/**
 * TV controls layer (OK on the picture): top bar (Back with a mouse, channel, Channels, Settings,
 * clock), Previous / Play-Pause / Next, then seek bar, time behind and Live, the slim show line and
 * the key hint. Focus starts on Play/Pause (or the bar when opened by seeking). Up from the centre
 * row goes to Settings, Down to the bar, Down again to Live.
 */
@Composable
private fun TvControls(ui: PlayerUi, d: OverlayData) {
    val c = Jtv.colors
    val act by rememberUpdatedState(d.actions)
    val ts = d.timeshift
    val paused = d.model.paused
    val playFr = remember { FocusRequester() }
    val barFr = remember { FocusRequester() }
    val liveFr = remember { FocusRequester() }
    val settingsFr = remember { FocusRequester() }
    val token = ui.controlsFocusToken
    LaunchedEffect(token) {
        withFrameNanos { }
        runCatching {
            if (ui.controlsFocusTarget == ControlFocus.Bar && ts.seekable) barFr.requestFocus() else playFr.requestFocus()
        }
    }
    val bump = remember(ui) { { ui.bannerToken++; Unit } }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 48.dp, vertical = 27.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val toCentre = Modifier.focusProperties { down = playFr }
            if (ui.pointerChrome) OverVideoButton("Back", { act.leave() }, icon = Icons.AutoMirrored.Filled.ArrowBack, modifier = toCentre)
            ControlsTitle(d.playing, 18.sp, 520.dp)
            Spacer(Modifier.weight(1f))
            OverVideoButton("Channels", { act.openChannelList() }, icon = Icons.AutoMirrored.Filled.List, modifier = toCentre)
            OverVideoButton(
                "Settings", { ui.openOptions() }, icon = Icons.Filled.Settings,
                modifier = toCentre.focusRequester(settingsFr),
            )
            Plaque { JtvClock(LocalNow.current, size = 28.sp, dateColor = c.t2) }
        }
        CentreControls(
            paused = paused, buffering = d.buffering, actions = act,
            modifier = Modifier.align(Alignment.Center),
            side = 56.dp, main = 72.dp, gap = 48.dp,
            playFocus = playFr, upFocus = settingsFr,
            downFocus = if (ts.seekable) barFr else if (ts.live) liveFr else null,
            onPlayFocus = { f ->
                if (f) ui.focusedControl = ControlFocus.Play
                else if (ui.focusedControl == ControlFocus.Play) ui.focusedControl = ControlFocus.None
            },
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 48.dp, vertical = 27.dp)) {
            SeekRow(
                ts, act, onInteract = bump,
                barFocus = barFr, liveFocus = liveFr, upFocus = playFr,
                onBarFocus = { f ->
                    if (f) ui.focusedControl = ControlFocus.Bar
                    else if (ui.focusedControl == ControlFocus.Bar) ui.focusedControl = ControlFocus.None
                },
            )
            Spacer(Modifier.height(10.dp))
            d.playing?.let { MiniInfoLine(it, d.epg, null, Modifier.widthIn(max = 900.dp)) }
            Spacer(Modifier.height(8.dp))
            Plaque { KeyHint(if (paused && ts.seekable) ControlsPausedHint else ControlsHint, keyColor = c.t2, color = c.t3) }
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

/**
 * Icon-only control over the video (touch): a solid #141416 plate at 80% alpha, never a scrim.
 * Always carries a contentDescription.
 */
@Composable
internal fun OverVideoIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = modifier.size(size).semantics { contentDescription = description; role = Role.Button },
        shape = CircleShape,
        container = StrapBg.copy(alpha = 0.8f),
    ) { focused ->
        Icon(
            icon, contentDescription = null, tint = if (focused) c.invTx else c.tx,
            modifier = Modifier.align(Alignment.Center).size(iconSize),
        )
    }
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

/** Portrait phone video: a downward swipe shrinks the player into the mini player. */
internal fun Modifier.swipeDownToMinimize(actions: State<PlayerActions>): Modifier = this
    .pointerInput(actions) {
        var total = 0f
        val threshold = 72.dp.toPx()
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total > threshold && actions.value.canMinimize) actions.value.minimize() },
            onVerticalDrag = { change, dy -> total += dy; change.consume() },
        )
    }

/**
 * Touch, full-screen (phone landscape + tablet), YouTube-style: the picture stays visible. Small round
 * icons at the edges, Previous / Play-Pause / Next in the centre, one slim info line at the bottom.
 * Channel tiles + categories are one tap away behind "Channels" instead of always covering the video.
 */
@Composable
internal fun TouchOverlays(ui: PlayerUi, d: OverlayData, compact: Boolean, exitFullScreen: Boolean = compact) {
    val now = rememberMinuteClock()
    val act by rememberUpdatedState(d.actions)
    val c = Jtv.colors
    val pad = if (compact) 12.dp else 24.dp
    val tiles = if (compact) PhoneLandTiles else TabletTiles
    CompositionLocalProvider(LocalNow provides now) {
        Box(Modifier.fillMaxSize()) {
            BannerAutoHide(ui, 4_000, ts = d.timeshift)
            val paused = d.model.paused
            val bump = remember(ui) { { ui.bannerToken++; Unit } }
            val controls = ui.overlay == PlayerOverlay.Banner || paused
            var showChannels by remember { mutableStateOf(false) }
            LaunchedEffect(controls) { if (!controls) showChannels = false }
            val playingId = d.playing?.id
            LaunchedEffect(showChannels) {
                if (showChannels) {
                    ui.browseGroup = d.currentGroup ?: MainViewModel.GROUP_ALL
                    ui.browseIndex = d.resolveGroup(ui.browseGroup).indexOfFirst { it.id == playingId }.coerceAtLeast(0)
                    ui.browseFocusToken++
                }
            }
            val bGroup = ui.browseGroup
            val list = remember(bGroup, showChannels) { if (showChannels) d.resolveGroup(bGroup) else emptyList() }
            val onRailClick = remember(bGroup) { { i: Int -> act.tune(bGroup, i) } }
            val noFocus = remember { { _: Int -> } }

            AnimatedVisibility(visible = controls, enter = fadeInFast, exit = fadeOutFast) {
                Box(Modifier.fillMaxSize()) {
                    // ── Top: minimise · channel · (channels, settings, exit full screen, clock) ──
                    Row(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = pad, vertical = pad),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (act.canMinimize) OverVideoIcon(PlayerIcons.ExpandMore, "Minimise player", { act.minimize() }, iconSize = 30.dp)
                        else OverVideoIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", { act.leave() })
                        ControlsTitle(d.playing, 16.sp, 360.dp)
                        Spacer(Modifier.weight(1f))
                        OverVideoIcon(Icons.AutoMirrored.Filled.List, if (showChannels) "Hide channels" else "Channels", {
                            showChannels = !showChannels; ui.bannerToken++
                        })
                        OverVideoIcon(Icons.Filled.Settings, "Settings", { ui.openOptions() })
                        if (exitFullScreen) OverVideoIcon(PlayerIcons.FullscreenExit, "Exit full screen", { act.fullScreen(false) })
                        if (!compact) Plaque { JtvClock(now, size = 24.sp, dateColor = c.t2) }
                    }
                    // ── Centre: previous · play/pause · next ──
                    if (!showChannels) CentreControls(
                        paused = paused, buffering = d.buffering, actions = act,
                        modifier = Modifier.align(Alignment.Center),
                        gap = if (compact) 40.dp else 56.dp,
                    )
                    // ── Bottom: seek bar + Live, or the channel rail; then the slim info line ──
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = pad, end = pad, bottom = pad)) {
                        if (showChannels) {
                            CategoryChips(
                                d.browseGroups, bGroup,
                                onPick = { g ->
                                    ui.browseGroup = g
                                    ui.browseIndex = d.resolveGroup(g).indexOfFirst { it.id == playingId }.coerceAtLeast(0)
                                    ui.browseFocusToken++
                                    ui.bannerToken++
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            if (list.isNotEmpty()) ChannelRail(
                                channels = list, playingId = playingId,
                                focusIndex = ui.browseIndex.coerceIn(0, list.size - 1),
                                focusToken = ui.browseFocusToken, takeFocus = false, sizes = tiles,
                                onFocused = noFocus, onClick = onRailClick,
                            )
                            Spacer(Modifier.height(8.dp))
                        } else {
                            SeekRow(d.timeshift, act, onInteract = bump)
                            Spacer(Modifier.height(4.dp))
                        }
                        d.playing?.let { MiniInfoLine(it, d.epg, d.timeshift, Modifier.widthIn(max = 720.dp)) }
                    }
                }
            }

            OptionsAndMenu(ui, d, touch = true, panelWidth = if (compact) 340.dp else 400.dp, edge = pad)
        }
    }
}

/** One slim line over the video: small number block · show title · time left · thin progress. */
@Composable
private fun MiniInfoLine(ch: Channel, epg: EpgSource, ts: Timeshift?, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    val tv = Jtv.isTv
    val now = LocalNow.current
    val cur = rememberNowNext(epg, ch.id, always = true)?.now
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(StrapBg.copy(alpha = 0.85f))) {
        Row(Modifier.height(if (tv) 56.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberBlock(ch.channelNumber, Modifier.width(64.dp).fillMaxHeight(), 22.sp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                JText(cur?.title ?: ch.name, if (tv) 18.sp else 16.sp, color = c.tx, weight = FontWeight.SemiBold)
                JText(
                    if (cur != null) "${formatTime(cur.startMs)} – ${formatTime(cur.stopMs)} · ${cur.minutesLeft(now)} min left"
                    else listOfNotNull(ch.group, ch.language).joinToString(" · "),
                    14.sp, color = c.t2,
                )
            }
            if (ts != null) BehindLiveTag(ts, 14.sp, Modifier.padding(end = 12.dp))
        }
        if (cur != null) JtvProgress(cur.progress(now), height = 2.dp, track = Color(0x33FFFFFF))
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
                modifier = Modifier.height(if (Jtv.isTv) 56.dp else 40.dp),
                shape = RoundedCornerShape(28.dp),
                container = if (isSel) c.inv else if (overVideo) StrapBg else c.s1,
            ) { focused ->
                JText(
                    groupLabel(g), if (Jtv.isTv) 16.sp else 14.sp,
                    color = if (isSel || focused) c.invTx else c.tx,
                    weight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = if (Jtv.isTv) 18.dp else 14.dp),
                )
            }
        }
    }
}
