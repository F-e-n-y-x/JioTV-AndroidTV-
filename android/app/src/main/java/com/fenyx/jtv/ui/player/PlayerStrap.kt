package com.fenyx.jtv.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Stable
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
    val height: Dp, val numW: Dp, val numSize: TextUnit, val nameSize: TextUnit,
    val titleSize: TextUnit, val metaSize: TextUnit, val nextW: Dp,
)

@Immutable
internal data class TileSizes(val w: Dp, val h: Dp, val plateW: Dp, val plateH: Dp, val numSize: TextUnit, val gap: Dp)

internal val TvStrap = StrapSizes(112.dp, 132.dp, 48.sp, 14.sp, 22.sp, 14.sp, 280.dp)
internal val TabletStrap = StrapSizes(132.dp, 150.dp, 56.sp, 16.sp, 26.sp, 16.sp, 340.dp)
internal val PhoneLandStrap = StrapSizes(96.dp, 104.dp, 36.sp, 14.sp, 18.sp, 14.sp, 220.dp)

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
    return remember(programs, now) { programs?.takeIf { it.isNotEmpty() }?.let { nowNext(it, now) } }
}

internal fun channelSubtitle(ch: Channel): String =
    listOf(ch.group, ch.language).filter { it.isNotBlank() }.distinct().joinToString(" · ")

/** "Series · Sitcom · U · Hindi" (like the official app), skipping what the guide doesn't have. */
internal fun programMeta(p: EpgProgram, language: String?): String {
    val genre = p.genre?.takeUnless { it.equals(p.category, ignoreCase = true) }
    return listOfNotNull(p.category, genre, p.rating, language)
        .filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

// ───────────────────────── Info strap ─────────────────────────

@Composable
internal fun EpgStrap(channel: Channel, epg: EpgSource, s: StrapSizes, modifier: Modifier = Modifier, playing: Boolean = true) {
    val nn = rememberNowNext(epg, channel.id, always = playing)
    InfoStrap(channel, nn, LocalNow.current, s, modifier)
}

/** Amber number block · channel / show / meta / times + progress · NEXT column (D-tv-player-dark). */
@Composable
internal fun InfoStrap(channel: Channel, nn: NowNext?, now: Long, s: StrapSizes, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    val meta = nn?.now?.let { programMeta(it, channel.language) }.orEmpty()
    // The meta line adds one line of height only when there is one, so straps without it are unchanged.
    val extra = if (meta.isNotEmpty()) with(androidx.compose.ui.platform.LocalDensity.current) { (s.metaSize * 1.3f).toDp() } + 2.dp else 0.dp
    Row(
        modifier.fillMaxWidth().height(s.height + extra).clip(RoundedCornerShape(8.dp)).background(StrapBg),
    ) {
        NumberBlock(channel.channelNumber, Modifier.width(s.numW).fillMaxHeight(), s.numSize)
        val cur = nn?.now
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (cur != null) {
                JText(channel.name, s.nameSize, color = c.t2, weight = FontWeight.SemiBold)
                JText(cur.title, s.titleSize, weight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                if (meta.isNotEmpty()) JText(meta, s.metaSize, color = c.t2, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    JText("${formatTime(cur.startMs)} – ${formatTime(cur.stopMs)}", s.metaSize, color = c.t2)
                    Spacer(Modifier.width(12.dp))
                    JtvProgress(cur.progress(now), Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    val left = cur.minutesLeft(now)
                    if (left <= 10) EndsSoonPill(cur.stopMs, fontSize = s.metaSize)
                    else JText("$left min left", s.metaSize, color = c.t2)
                }
            } else {
                JText(channel.name, s.titleSize, weight = FontWeight.Bold)
                JText(channelSubtitle(channel), s.metaSize, color = c.t2, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val later = nn?.later.orEmpty().take(3)
        if (later.isNotEmpty()) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
            Column(
                Modifier.width(s.nextW).fillMaxHeight().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                SectionLabel("NEXT")
                later.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTime(p.startMs),
                            style = textStyle(s.metaSize).copy(fontFeatureSettings = "tnum"),
                            color = c.t2, maxLines = 1, modifier = Modifier.width(52.dp),
                        )
                        JText(p.title, s.metaSize, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ───────────────────────── Tile rail ─────────────────────────

/**
 * Horizontal channel rail (D-tv-live-dark). The focused tile is inverted with a 4dp amber leading bar
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
    playing: Boolean,
    sizes: TileSizes,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = modifier
            .size(sizes.w, sizes.h)
            .onFocusChanged { if (it.isFocused) onFocused() },
        container = StrapBg,
        focusedScale = 1.04f,
    ) { focused ->
        Box(Modifier.fillMaxSize()) {
            if (focused) Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().background(c.acc))
            if (playing) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(7.dp).clip(CircleShape).background(c.acc))
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ChannelPlate(channel.logoUrl, sizes.plateW, sizes.plateH)
                Text(
                    if (channel.channelNumber > 0) channel.channelNumber.toString() else "–",
                    style = numberStyle(sizes.numSize),
                    color = if (focused) c.invTx else c.tx,
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
                JText("Up: $prev  ·  Down: $next", 14.sp, color = c.t2, modifier = Modifier.padding(top = 2.dp))
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
            digits.isNotEmpty() -> match?.name ?: "No channel $digits"
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
        Plaque { JText(name ?: "Loading", 16.sp, weight = FontWeight.SemiBold) }
    }
}

@Composable
internal fun PausedBadge(touch: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(StrapBg).padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JText("Paused", 22.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (touch) {
            JtvButton("Play", onPlay, primary = true, minHeight = 56.dp, fontSize = 18.sp)
        } else {
            JText("Press OK to continue", 16.sp, color = c.t2)
        }
    }
}

internal fun errorActionLabel(a: ErrorAction) = when (a) {
    ErrorAction.Retry -> "Try again"
    ErrorAction.NextChannel -> "Next channel"
    ErrorAction.Settings -> "Open settings"
}

/** One sentence and one or two buttons; focus lands on the first button. */
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
    val h = if (touch) 56.dp else 44.dp
    Column(
        modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(8.dp)).background(StrapBg).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            error.message, style = textStyle(22.sp, FontWeight.SemiBold), color = c.tx, textAlign = TextAlign.Center,
        )
        if (channel != null) {
            Spacer(Modifier.height(6.dp))
            JText(
                if (channel.channelNumber > 0) "${channel.channelNumber}  ${channel.name}" else channel.name,
                16.sp, color = c.t2,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JtvButton(
                errorActionLabel(error.primary), { onAction(error.primary) },
                modifier = Modifier.focusRequester(firstFocus), primary = true, minHeight = h, fontSize = 18.sp,
            )
            error.secondary?.let { a ->
                JtvButton(errorActionLabel(a), { onAction(a) }, minHeight = h, fontSize = 18.sp)
            }
        }
    }
}
