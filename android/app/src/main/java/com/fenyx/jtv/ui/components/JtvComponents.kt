package com.fenyx.jtv.ui.components

import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.theme.JtvFonts
import com.fenyx.jtv.theme.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── Time ─────────────────────────

/**
 * One wall clock per screen, ticking on the minute boundary (not every second) so a screen full of
 * progress bars recomposes once a minute. Provide it high up with [LocalNow]; rows read it.
 */
@Composable
fun rememberMinuteClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            kotlinx.coroutines.delay(60_000 - t % 60_000 + 50)
        }
    }
    return now
}

val LocalNow = staticCompositionLocalOf { 0L }

// 12-hour clock everywhere (owner's choice): "2:45 PM".
private val hhmm = ThreadLocal.withInitial { SimpleDateFormat("h:mm a", Locale.US) }
private val hm12 = ThreadLocal.withInitial { SimpleDateFormat("h:mm", Locale.US) }
private val ampm = ThreadLocal.withInitial { SimpleDateFormat("a", Locale.US) }
// Day and month names follow the app language ("Fri, 3 Oct" / "शुक्र, 3 अक्टू॰"); re-made when it changes.
private val dayFmt = ThreadLocal<Pair<Locale, SimpleDateFormat>>()
private fun dayFormat(): SimpleDateFormat {
    val loc = Locale.getDefault().let { if (it.language == "en") Locale.UK else it }
    dayFmt.get()?.let { (l, f) -> if (l == loc) return f }
    return SimpleDateFormat("EEE, d MMM", loc).also { dayFmt.set(loc to it) }
}

fun formatTime(ms: Long): String = hhmm.get()!!.format(Date(ms))
/** "2:45" without the AM/PM, for tight columns where the day part is shown once nearby. */
fun formatTimeShort(ms: Long): String = hm12.get()!!.format(Date(ms))
fun formatAmPm(ms: Long): String = ampm.get()!!.format(Date(ms))
fun formatDay(ms: Long): String = dayFormat().format(Date(ms))

/** Now / next / later for one channel's guide at [now]. */
@Immutable
data class NowNext(val now: EpgProgram?, val next: EpgProgram?, val later: List<EpgProgram>)

fun nowNext(programs: List<EpgProgram>, now: Long): NowNext {
    if (programs.isEmpty()) return NowNext(null, null, emptyList())
    val i = programs.indexOfFirst { it.startMs <= now && now < it.stopMs }
    return if (i >= 0) NowNext(programs[i], programs.getOrNull(i + 1), programs.drop(i + 1).take(4))
    else {
        val fut = programs.filter { it.startMs > now }
        NowNext(null, fut.firstOrNull(), fut.take(4))
    }
}

fun EpgProgram.progress(now: Long): Float =
    ((now - startMs).toFloat() / (stopMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)

fun EpgProgram.minutesLeft(now: Long): Int = ((stopMs - now) / 60_000).toInt().coerceAtLeast(0)

// ───────────────────────── Text helpers ─────────────────────────

/** Channel numbers, clock, times: Anek extended cut, tabular figures. */
fun numberStyle(size: TextUnit) = TextStyle(
    fontFamily = JtvFonts.wide, fontWeight = FontWeight.Bold, fontSize = size,
    fontFeatureSettings = "tnum", lineHeight = size * 1.1f,
)

fun textStyle(size: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = JtvFonts.text, fontWeight = weight, fontSize = size, lineHeight = size * 1.3f,
)

@Composable
fun JText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Jtv.colors.tx,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
) {
    Text(
        text = text, modifier = modifier, color = color, style = textStyle(size, weight),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

// ───────────────────────── Clickable surface ─────────────────────────

/**
 * The one focusable building block. Focus = solid inverted fill ([Jtv.colors.inv]) plus an optional
 * scale; never glow or shadow. D-pad, mouse hover-to-focus, touch tap and long-press all work (via the
 * shared [Surface] wrapper). [content] gets `focused` so text can flip to [Jtv.colors.invTx].
 */
@Composable
fun JtvClickable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(6.dp),
    container: Color = Color.Transparent,
    focusedContainer: Color = Jtv.colors.inv,
    focusedScale: Float = 1f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val c = Jtv.colors
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container, contentColor = c.tx,
            focusedContainerColor = focusedContainer, focusedContentColor = c.invTx,
            pressedContainerColor = if (Jtv.isTv) focusedContainer else c.s2, pressedContentColor = if (Jtv.isTv) c.invTx else c.tx,
        ),
        border = ClickableSurfaceDefaults.border(),
        glow = ClickableSurfaceDefaults.glow(),
        interactionSource = interactionSource,
    ) { content(focused) }
}

// ───────────────────────── Channel bits ─────────────────────────

/** Logo on a fixed dark plate (logos are made for dark backgrounds, so the plate stays dark in light mode). */
@Composable
fun ChannelPlate(logoUrl: String, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { width.roundToPx() to height.roundToPx() }
    val request = remember(logoUrl, px) {
        ImageRequest.Builder(ctx).data(logoUrl).size(px.first, px.second).crossfade(false).build()
    }
    Box(
        modifier.size(width, height).clip(RoundedCornerShape(4.dp)).background(Jtv.colors.plate),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(0.84f).fillMaxHeight(0.78f),
        )
    }
}

/** The amber channel-number block (from the round-1 "Desk" strap the owner liked). */
@Composable
fun NumberBlock(number: Int, modifier: Modifier = Modifier, fontSize: TextUnit = 48.sp) {
    Box(modifier.background(Jtv.colors.acc), contentAlignment = Alignment.Center) {
        Text(if (number > 0) number.toString() else "–", style = numberStyle(fontSize), color = Jtv.colors.accTx, maxLines = 1)
    }
}

/** Thin progress bar. Draws in one pass (no nested layout). */
@Composable
fun JtvProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Jtv.colors.acc,
    track: Color = Jtv.colors.line,
    height: Dp = 3.dp,
) {
    Box(
        modifier.fillMaxWidth().height(height).drawBehind {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (fraction > 0f) drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = r)
        }
    )
}

@Composable
fun EndsSoonPill(stopMs: Long, modifier: Modifier = Modifier, fontSize: TextUnit = 13.sp) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Jtv.colors.acc).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(stringResource(R.string.time_ends_at, formatTime(stopMs)), style = textStyle(fontSize, FontWeight.SemiBold), color = Jtv.colors.accTx, maxLines = 1)
    }
}

/** Big clock + date, top-right of every TV screen. */
@Composable
fun JtvClock(now: Long, modifier: Modifier = Modifier, size: TextUnit = 22.sp, color: Color = Jtv.colors.tx, dateColor: Color = Jtv.colors.t2) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text("${formatTimeShort(now)} ${formatAmPm(now)}", style = textStyle(size, FontWeight.SemiBold), color = color, maxLines = 1)
        Text(formatDay(now), style = textStyle(size * 0.64f), color = dateColor, maxLines = 1)
    }
}

/** "**OK** watch · **Hold OK** options" line at the bottom of TV screens. */
@Composable
fun KeyHint(items: List<Pair<String, String>>, modifier: Modifier = Modifier, keyColor: Color = Jtv.colors.t2, color: Color = Jtv.colors.t3) {
    val text = remember(items, keyColor, color) {
        buildAnnotatedString {
            items.forEachIndexed { i, (k, v) ->
                if (i > 0) withStyle(SpanStyle(color = color)) { append("  ·  ") }
                withStyle(SpanStyle(color = keyColor, fontWeight = FontWeight.SemiBold)) { append(k) }
                withStyle(SpanStyle(color = color)) { append(" $v") }
            }
        }
    }
    Text(text, modifier = modifier, style = textStyle(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Labelled button. [primary] = inverted fill; otherwise outlined. Icons always carry a text label. */
@Composable
fun JtvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    fontSize: TextUnit = 16.sp,
    minHeight: Dp = if (Jtv.isTv) 40.dp else 52.dp,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = minHeight)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(6.dp))),
        container = if (primary) c.inv else Color.Transparent,
        focusedContainer = if (primary) c.acc else c.inv,
        focusedScale = 1.03f,
    ) { focused ->
        val fg = when {
            primary && focused -> c.accTx
            primary -> c.invTx
            focused -> c.invTx
            else -> c.tx
        }
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size((fontSize.value + 4).dp))
            Text(text, style = textStyle(fontSize, FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Leading 4dp amber bar, drawn behind content (focused tiles / current category). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = textStyle(12.sp, FontWeight.SemiBold), color = Jtv.colors.t3, maxLines = 1)
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

// ───────────────────────── Popups ─────────────────────────

/** Full-screen popup backdrop: a tap anywhere outside the panel closes it (touch + mouse). */
fun Modifier.closeOnOutsideTap(onClose: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
}

/** Put on the panel itself so taps inside it never reach [closeOnOutsideTap]. */
fun Modifier.keepTapsInside(): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        .focusProperties { canFocus = false }
}
