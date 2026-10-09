package com.fenyx.jtv.ui.player

import com.fenyx.jtv.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.EndsSoonPill
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.NowNext
import com.fenyx.jtv.ui.components.NumberBlock
import com.fenyx.jtv.ui.components.SectionLabel
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.nowNext
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.delay

// ───────────────────────── Sizes per form factor ─────────────────────────

@Immutable
internal data class StrapSizes(
    val plateW: Dp, val plateH: Dp, val nameSize: TextUnit,
    val titleSize: TextUnit, val metaSize: TextUnit, val maxW: Dp,
)

@Immutable
internal data class TileSizes(val w: Dp, val h: Dp, val plateW: Dp, val plateH: Dp, val numSize: TextUnit, val gap: Dp)

/** maxW = width of the times / Next column on the right. */
internal val TvStrap = StrapSizes(84.dp, 56.dp, 16.sp, 22.sp, 15.sp, 380.dp)

internal val TvTiles = TileSizes(100.dp, 72.dp, 62.dp, 34.dp, 15.sp, 10.dp)
internal val TabletTiles = TileSizes(108.dp, 80.dp, 68.dp, 38.dp, 16.sp, 12.dp)
internal val PhoneLandTiles = TileSizes(88.dp, 64.dp, 54.dp, 30.dp, 14.sp, 8.dp)

// ───────────────────────── EPG ─────────────────────────

/**
 * Programme data for the player, read from the shared MainViewModel. The channel being watched
 * ([always] = true) always gets its guide: one small native request, debounced so fast zapping doesn't
 * fire a request per channel. Other channels (list rows, rail tiles) follow the programme-guide setting.
 */
@Stable
internal class EpgSource(private val vm: MainViewModel?, private val enabled: State<Boolean>) {
    @Composable
    fun programs(channelId: String, always: Boolean = false): List<EpgProgram>? {
        if (vm == null || !(always || enabled.value)) return null
        val map by vm.epgData.collectAsState()
        LaunchedEffect(channelId) {
            delay(350)
            vm.fetchNativeEpgIfMissing(channelId)
        }
        return map[channelId]
    }
}

@Composable
internal fun rememberNowNext(epg: EpgSource, channelId: String, always: Boolean = false): NowNext? {
    val programs = epg.programs(channelId, always)
    val now = LocalNow.current
    // Replaying a show on this (the playing) channel: that show is "now"; nothing is "next".
    val replay = LocalReplay.current?.takeIf { always && it.channelId == channelId }
    if (replay != null) {
        return remember(programs, replay) {
            NowNext(programs?.firstOrNull { replay.isFor(it) } ?: replay.asProgram(), null, emptyList())
        }
    }
    return remember(programs, now) { programs?.takeIf { it.isNotEmpty() }?.let { nowNext(it, now) } }
}

@androidx.compose.runtime.Composable
internal fun channelSubtitle(ch: Channel): String =
    listOf(com.fenyx.jtv.ui.main.groupLabel(ch.group), com.fenyx.jtv.ui.main.languageLabel(ch.language)).filter { it.isNotBlank() }.distinct().joinToString(" · ")

/** "Series · Sitcom · U · Hindi" (like the official app), skipping what the guide doesn't have. */
internal fun programMeta(p: EpgProgram, language: String?): String {
    val genre = p.genre?.takeUnless { it.equals(p.category, ignoreCase = true) }
    return listOfNotNull(p.category, genre, p.rating, language)
        .filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

// ───────────────────────── Info strap ─────────────────────────

@Composable
internal fun EpgStrap(
    channel: Channel, epg: EpgSource, s: StrapSizes, modifier: Modifier = Modifier, playing: Boolean = true,
    timeshift: Timeshift? = null,
) {
    val nn = rememberNowNext(epg, channel.id, always = playing)
    InfoStrap(channel, nn, LocalNow.current, s, modifier, timeshift)
}

/**
 * The channel card on zap / Info, full width and two lines tall: logo · "151 Movies Now HD" over the
 * show · times, progress and "Next" on the right. Low and wide so it covers as little picture as possible.
 */
/**
 * Favourites are numbered 1, 2, 3 in the user's order (issue #7): channel id → position while the
 * player is in Favourites, empty elsewhere. Provided by the player around its overlays.
 */
internal val LocalFavNumbers = compositionLocalOf<Map<String, Int>> { emptyMap() }

/** The number to show for [ch]: its Favourites position there, else Jio's number (0 = none). */
@Composable
@ReadOnlyComposable
internal fun shownNumber(ch: Channel): Int = LocalFavNumbers.current[ch.id] ?: ch.channelNumber

/** Channel id → 1-based position in [list]. */
internal fun positions(list: List<Channel>): Map<String, Int> = list.withIndex().associate { (i, c) -> c.id to i + 1 }

@Composable
internal fun InfoStrap(
    channel: Channel, nn: NowNext?, now: Long, s: StrapSizes, modifier: Modifier = Modifier,
    /** The playing channel's timeshift: "Behind live −02:35" next to the name while behind. */
    timeshift: Timeshift? = null,
) {
    val c = Jtv.colors
    val cur = nn?.now
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(StrapBg).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelPlate(channel.logoUrl, s.plateW, s.plateH)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val num = shownNumber(channel)
                if (num > 0) {
                    JText("$num", s.nameSize, color = c.acc, weight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                JText(channel.name, s.nameSize, color = if (cur != null) c.t2 else c.tx, weight = FontWeight.SemiBold)
                if (timeshift != null) BehindLiveTag(timeshift, s.metaSize, Modifier.padding(start = 12.dp))
            }
            JText(
                cur?.title ?: channelSubtitle(channel), if (cur != null) s.titleSize else s.metaSize,
                weight = if (cur != null) FontWeight.Bold else FontWeight.Normal,
                color = if (cur != null) c.tx else c.t2, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (cur != null) {
            Spacer(Modifier.width(24.dp))
            Column(Modifier.width(s.maxW)) {
                if (isReplaying(cur)) {
                    JText(replayStatus(cur), s.metaSize, color = c.tx, weight = FontWeight.SemiBold)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        JText("${formatTime(cur.startMs)} – ${formatTime(cur.stopMs)}", s.metaSize, color = c.t2, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(10.dp))
                        val left = cur.minutesLeft(now)
                        if (left <= 10) EndsSoonPill(cur.stopMs, fontSize = s.metaSize)
                        else JText(pluralStringResource(R.plurals.player_min_left, left, left), s.metaSize, color = c.t2)
                    }
                    JtvProgress(cur.progress(now), Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                nn.later.firstOrNull()?.let { p ->
                    JText(
                        "${stringResource(R.string.player_next_label)}  ${formatTime(p.startMs)}  ${p.title}",
                        s.metaSize, color = c.t2,
                    )
                }
            }
        }
    }
}

// ───────────────────────── Tile rail ─────────────────────────

/**
 * Horizontal channel rail (D-tv-live-dark). The focused tile is inverted and slightly larger, and
 * shows the channel name (also on mouse hover);
 * and a slight scale; the playing channel has an amber dot. [focusToken] changes move focus (TV) and
 * scroll to [focusIndex].
 */
@Composable
internal fun ChannelRail(
    channels: List<Channel>,
    playingId: String?,
    focusIndex: Int,
    focusToken: Int,
    takeFocus: Boolean,
    sizes: TileSizes,
    onFocused: (Int) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Favourites: tiles show their position (1, 2, 3…) instead of the Jio number (#7). */
    positional: Boolean = false,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 2).coerceAtLeast(0))
    val target = remember { FocusRequester() }
    LaunchedEffect(focusToken, channels) {
        if (channels.isEmpty()) return@LaunchedEffect
        state.scrollToItem((focusIndex - 2).coerceAtLeast(0))
        if (takeFocus) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(sizes.gap),
        // Room for the focused tile's scale so it isn't clipped.
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        itemsIndexed(channels, key = { _, ch -> ch.id }, contentType = { _, _ -> "tile" }) { i, ch ->
            RailTile(
                channel = ch,
                number = if (positional) i + 1 else ch.channelNumber,
                playing = ch.id == playingId,
                sizes = sizes,
                modifier = if (i == focusIndex) Modifier.focusRequester(target) else Modifier,
                onFocused = { onFocused(i) },
                onClick = { onClick(i) },
            )
        }
    }
}

@Composable
private fun RailTile(
    channel: Channel,
    number: Int,
    playing: Boolean,
    sizes: TileSizes,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val c = Jtv.colors
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    JtvClickable(
        onClick = onClick,
        modifier = modifier
            .size(sizes.w, sizes.h)
            .hoverable(hover)
            .onFocusChanged { if (it.isFocused) onFocused() },
        container = StrapBg,
        focusedScale = 1.04f,
    ) { focused ->
        Box(Modifier.fillMaxSize()) {
            if (playing) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(7.dp).clip(CircleShape).background(c.acc))
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ChannelPlate(channel.logoUrl, sizes.plateW, sizes.plateH)
                if (focused || hovered) Text(
                    channel.name, style = textStyle(13.sp, FontWeight.SemiBold),
                    color = if (focused) c.invTx else c.tx, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) else Text(
                    if (number > 0) number.toString() else "–",
                    style = numberStyle(sizes.numSize),
                    color = c.tx,
                    maxLines = 1,
                )
            }
        }
    }
}

// ───────────────────────── Small solid plaques ─────────────────────────

/** A small solid background so text stays readable over bright video (never a full-screen scrim). */
@Composable
internal fun Plaque(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(StrapBg).padding(horizontal = 14.dp, vertical = 8.dp)) {
        content()
    }
}

/** Top-left of browse: "All channels 1307" and "Up: Favourites · Down: News". */
@Composable
internal fun BrowseHeader(label: String, count: Int, prev: String?, next: String?, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    Plaque(modifier) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                JText(label, 24.sp, weight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(count.toString(), style = textStyle(14.sp).copy(fontFeatureSettings = "tnum"), color = c.t2, maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
            if (prev != null && next != null && prev != label) {
                JText(stringResource(R.string.player_up_down_groups, prev, next), 14.sp, color = c.t2, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Big amber number while typing a channel number, top-right, with the channel it will tune to. */
@Composable
internal fun NumberEntry(digits: String, match: Channel?, miss: String?, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (digits.isNotEmpty()) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(c.acc).padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(digits, style = numberStyle(64.sp), color = c.accTx, maxLines = 1)
            }
        }
        val line = when {
            miss != null -> miss
            digits.isNotEmpty() -> match?.name ?: stringResource(R.string.player_no_channel, digits)
            else -> null
        }
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            Plaque { JText(line, 18.sp, weight = FontWeight.SemiBold) }
        }
    }
}

// ───────────────────────── Status: buffering, paused, error ─────────────────────────

@Composable
internal fun BufferingIndicator(name: String?, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = c.acc, strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Plaque { JText(name ?: stringResource(R.string.player_loading), 16.sp, weight = FontWeight.SemiBold) }
    }
}

@Composable
internal fun PausedBadge(touch: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(StrapBg).padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JText(stringResource(R.string.player_paused), 22.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (touch) {
            JtvButton(stringResource(R.string.player_play), onPlay, primary = true, minHeight = 56.dp, fontSize = 18.sp)
        } else {
            JText(stringResource(R.string.player_press_ok), 16.sp, color = c.t2, maxLines = 2)
        }
    }
}

@Composable
internal fun errorActionLabel(a: ErrorAction) = stringResource(when (a) {
    ErrorAction.Retry -> R.string.common_try_again
    ErrorAction.NextChannel -> R.string.player_next_channel
    ErrorAction.Settings -> R.string.player_open_settings
    ErrorAction.GoLive -> R.string.player_go_live
})

/**
 * Centred, see-through card when a channel can't play: the channel's logo and name, one plain
 * sentence, then one or two buttons (focus on the first, so OK does the likely thing).
 */
@Composable
internal fun ErrorPanel(
    error: PlayerError,
    channel: Channel?,
    firstFocus: FocusRequester,
    touch: Boolean,
    onAction: (ErrorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Jtv.colors
    val h = if (touch) 48.dp else 44.dp
    Column(
        modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(12.dp)).background(StrapBg)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (channel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelPlate(channel.logoUrl, 56.dp, 36.dp)
                Spacer(Modifier.width(12.dp))
                JText(
                    shownNumber(channel).let { if (it > 0) "$it  ${channel.name}" else channel.name },
                    16.sp, color = c.t2, weight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(
            stringResource(error.message), style = textStyle(18.sp, FontWeight.SemiBold), color = c.tx,
            textAlign = TextAlign.Center, maxLines = 3,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JtvButton(
                errorActionLabel(error.primary), { onAction(error.primary) },
                modifier = Modifier.focusRequester(firstFocus), primary = true, minHeight = h, fontSize = 16.sp,
            )
            error.secondary?.let { a ->
                JtvButton(errorActionLabel(a), { onAction(a) }, minHeight = h, fontSize = 16.sp)
            }
        }
    }
}
