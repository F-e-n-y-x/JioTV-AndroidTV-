package com.fenyx.jtv.ui.player

import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvClickable

/** Closer to the live point than this counts as "live" (playlist refreshes move the edge in steps). */
internal const val LiveToleranceMs = 8_000L

/** A DVR window shorter than this isn't worth a bar (some channels only keep a few segments). */
private const val MinSeekableSpanMs = 20_000L

/** Where the focus is inside the controls layer (TV). Plain bookkeeping for the key model. */
enum class ControlFocus { None, Play, Bar }

/**
 * Timeshift state of the live stream, polled from the player (at most every 500 ms, only while the
 * controls or the strap are on screen). Positions are in the current window's coordinates: 0 is the
 * start of the seekable window and [spanMs] is the live point (the right end of the bar).
 *
 * Fields are snapshot state so only the bar, the time label and the "Behind live" tag read them; the
 * bar reads them in its draw phase, so polling never recomposes anything but a small label.
 */
@Stable
internal class Timeshift {
    var live by mutableStateOf(false)
        private set
    var seekable by mutableStateOf(false)
        private set
    var spanMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
        private set
    /** Preview position while a finger drags the thumb or Left/Right is held (null = not scrubbing). */
    var scrubMs by mutableStateOf<Long?>(null)

    /**
     * A recorded-schedule channel ("VOD playout"): Jio hands out the current programme's file. The bar
     * then covers the whole file, and "Back to schedule" returns to where the broadcast is now.
     */
    var vod by mutableStateOf(false)
        private set
    /** Wall-clock start of the programme in the file (set by the player from the guide; 0 = unknown). */
    var scheduleStartMs = 0L
    /** Where the schedule is now, in file coordinates (-1 = unknown). */
    var scheduleMs by mutableLongStateOf(-1L)
        private set

    /**
     * Replaying a past show (Jio catch-up), set by the player per load (not cleared by [reset]). The
     * bar covers the programme; the Live button reads "Go live" and returns to the live channel.
     */
    var replay by mutableStateOf(false)

    /** The user paused (set by the player). */
    var paused by mutableStateOf(false)

    /**
     * Watching live and playing: the bar has nothing to show, so it stays out of the way until the
     * user pauses, rewinds or moves to it (TV). Schedule files and replays always show it.
     */
    val barQuiet: Boolean get() = !vod && !replay && !paused && atLive && scrubMs == null

    val behindMs: Long get() = (spanMs - positionMs).coerceAtLeast(0L)
    /** Live: at the live point. Schedule file: within 30 s of the scheduled position. Replay: never. */
    val atLive: Boolean get() = when {
        replay -> false
        vod -> scheduleMs >= 0 && kotlin.math.abs(positionMs - scheduleMs) < ScheduleToleranceMs
        else -> !live || behindMs < LiveToleranceMs
    }
    /** The Live / Back to schedule / Go live button has something to do. */
    val hasLiveButton: Boolean get() = replay || if (vod) scheduleMs >= 0 else live

    private val window = Timeline.Window()
    private var holdStart = 0L
    private var lastStep = 0L

    fun reset() {
        live = false; seekable = false; spanMs = 0L; positionMs = 0L; bufferedMs = 0L; scrubMs = null
        vod = false; scheduleMs = -1L // scheduleStartMs stays: the player sets it per programme
    }

    fun update(p: Player) {
        val tl = p.currentTimeline
        if (tl.isEmpty) { reset(); return }
        tl.getWindow(p.currentMediaItemIndex, window)
        val dur = window.durationMs
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (!window.isLive && !window.isPlaceholder) {
            // A file, not a live stream: the bar is the whole file.
            vod = true
            live = false
            val known = dur != C.TIME_UNSET && dur > 0
            seekable = window.isSeekable && known
            spanMs = if (known) dur else pos
            positionMs = pos
            bufferedMs = p.bufferedPosition.coerceIn(pos, spanMs.coerceAtLeast(pos))
            val sched = if (scheduleStartMs > 0) System.currentTimeMillis() - scheduleStartMs else -1L
            scheduleMs = if (known && sched in 0 until dur) sched else -1L
            return
        }
        vod = false
        scheduleMs = -1L
        var behind = 0L
        if (window.isLive) {
            // How far the playhead is behind where playback sits when "live" (the target offset).
            val off = p.currentLiveOffset
            val target = window.liveConfiguration?.targetOffsetMs ?: C.TIME_UNSET
            behind = if (off != C.TIME_UNSET && target != C.TIME_UNSET) off - target
                     else window.defaultPositionMs - pos
        }
        behind = behind.coerceAtLeast(0L)
        var span = pos + behind
        if (dur != C.TIME_UNSET && dur > 0) span = span.coerceAtMost(dur).coerceAtLeast(pos)
        live = window.isLive
        seekable = window.isLive && window.isSeekable && dur != C.TIME_UNSET && span >= MinSeekableSpanMs
        spanMs = span
        positionMs = pos
        bufferedMs = p.bufferedPosition.coerceIn(pos, span.coerceAtLeast(pos))
    }

    /**
     * D-pad scrubbing: each Left/Right press moves the preview 10 s; holding speeds up to 30 s, then
     * 60 s steps (rate-limited, key repeat is ~20 Hz). The seek itself happens on key-up, once.
     * [yieldAtLive]: Right at the live end isn't consumed, so focus can move on to the Live button.
     */
    fun scrubKey(ev: KeyEvent, yieldAtLive: Boolean, commit: (Long) -> Unit): Boolean {
        val dir = when (ev.key) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> return false
        }
        when (ev.type) {
            KeyEventType.KeyDown -> {
                if (!seekable) return false
                val now = android.os.SystemClock.uptimeMillis()
                if (ev.nativeKeyEvent.repeatCount == 0) {
                    val atEnd = if (vod) positionMs >= spanMs - 1_000 else atLive
                    if (yieldAtLive && dir > 0 && scrubMs == null && atEnd) return false
                    holdStart = now
                } else if (scrubMs == null || now - lastStep < 120) {
                    return scrubMs != null
                }
                lastStep = now
                val held = now - holdStart
                val step = when {
                    held < 1_500 -> 10_000L
                    held < 4_000 -> 30_000L
                    else -> 60_000L
                }
                val base = scrubMs ?: if (!vod && atLive) spanMs else positionMs
                scrubMs = (base + dir * step).coerceIn(0L, spanMs)
                return true
            }
            KeyEventType.KeyUp -> {
                val target = scrubMs ?: return false
                scrubMs = null
                commit(target)
                return true
            }
            else -> return scrubMs != null
        }
    }
}

/** "Back to schedule" counts as there within this distance (the file isn't frame-exact to the guide). */
internal const val ScheduleToleranceMs = 30_000L

/** "12:34" (or "1:02:34"). */
internal fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** "−02:35" (or "−1:02:35"). */
internal fun formatBehind(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "−%d:%02d:%02d".format(h, m, sec) else "−%02d:%02d".format(m, sec)
}

// ───────────────────────── Seek bar ─────────────────────────

/**
 * Thin track · buffered · played · thumb. Left end = start of the seekable window, right end = live.
 * Touch / mouse: press or drag anywhere on it (a time preview floats above the thumb), release seeks.
 * TV: focusable; the player's key handler does Left/Right scrubbing (see [Timeshift.scrubKey]).
 * Everything that changes while playing is read in the draw phase only.
 */
@Composable
internal fun LiveSeekBar(
    ts: Timeshift,
    onCommit: (Long) -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
) {
    val c = Jtv.colors
    val tv = Jtv.isTv
    val seekDescription = stringResource(R.string.player_seek_bar_description)
    val liveLabel = stringResource(R.string.common_live)
    var focused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val widthPx = remember { intArrayOf(1) }
    val accent = c.acc
    val ring = c.inv
    Box(
        modifier
            .fillMaxWidth()
            .height(if (tv) 32.dp else 44.dp)
            .onSizeChanged { widthPx[0] = it.width.coerceAtLeast(1) }
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .focusable(enabled = tv)
            .semantics { contentDescription = seekDescription; role = Role.Button }
            .pointerInput(ts) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun msAt(x: Float) = ((x / size.width).coerceIn(0f, 1f) * ts.spanMs).toLong()
                    dragging = true
                    ts.scrubMs = msAt(down.position.x)
                    onInteract()
                    var cancelled = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null) { cancelled = true; break }
                        if (!ch.pressed) { ch.consume(); break }
                        ts.scrubMs = msAt(ch.position.x)
                        ch.consume()
                    }
                    dragging = false
                    val target = ts.scrubMs
                    ts.scrubMs = null
                    if (!cancelled && target != null) onCommit(target)
                }
            }
            .drawBehind {
                val w = size.width
                val cy = size.height / 2f
                val big = focused || dragging
                val th = (if (big) 6.dp else 4.dp).toPx()
                val span = ts.spanMs.coerceAtLeast(1L).toFloat()
                val scrub = ts.scrubMs
                val frac = if (scrub == null && !ts.vod && ts.atLive) 1f else ((scrub ?: ts.positionMs) / span).coerceIn(0f, 1f)
                val bfrac = (ts.bufferedMs / span).coerceIn(frac, 1f)
                val top = cy - th / 2f
                val r = CornerRadius(th / 2f, th / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, top), Size(w, th), r)
                if (bfrac > frac) drawRect(Color.White.copy(alpha = 0.5f), Offset(w * frac, top), Size(w * (bfrac - frac), th))
                drawRoundRect(accent, Offset(0f, top), Size(w * frac, th), r)
                // Schedule file: a tick where the broadcast is now.
                val sched = ts.scheduleMs
                if (ts.vod && sched >= 0) {
                    val sx = w * (sched / span).coerceIn(0f, 1f)
                    drawRect(Color.White, Offset(sx - 1.dp.toPx(), cy - th * 1.5f), Size(2.dp.toPx(), th * 3f))
                }
                val tr = when {
                    dragging -> 11.dp
                    focused -> 10.dp
                    tv -> 6.dp
                    else -> 8.dp
                }.toPx()
                val x = (w * frac).coerceIn(tr, w - tr)
                if (focused) drawCircle(ring, tr + 3.dp.toPx(), Offset(x, cy))
                drawCircle(accent, tr, Offset(x, cy))
            },
    ) {
        // Time preview over the thumb while scrubbing (drag or held key).
        val scrub = ts.scrubMs
        if (scrub != null) {
            val span = ts.spanMs.coerceAtLeast(1L)
            val label = when {
                ts.vod -> formatClock(scrub)
                span - scrub < LiveToleranceMs -> liveLabel
                else -> formatBehind(span - scrub)
            }
            val labelW = remember { intArrayOf(0) }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { labelW[0] = it.width }
                    .offset {
                        val x = (widthPx[0] * (scrub.toFloat() / span)).toInt() - labelW[0] / 2
                        IntOffset(x.coerceIn(0, (widthPx[0] - labelW[0]).coerceAtLeast(0)), -(40.dp.roundToPx()))
                    }
                    .clip(RoundedCornerShape(6.dp))
                    .background(StrapBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                JText(label, 16.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** "−02:35" next to the bar; nothing while live. Recomposes at most once a second. */
@Composable
internal fun BehindTime(ts: Timeshift, modifier: Modifier = Modifier) {
    if (ts.vod) {
        // Elapsed / remaining in the programme file.
        val text by remember(ts) {
            derivedStateOf { "${formatClock(ts.positionMs)} / ${formatBehind(ts.spanMs - ts.positionMs)}" }
        }
        JText(text, if (Jtv.isTv) 16.sp else 14.sp, color = Jtv.colors.tx, weight = FontWeight.SemiBold, modifier = modifier)
        return
    }
    val secs by remember(ts) { derivedStateOf { if (ts.atLive) -1L else ts.behindMs / 1000 } }
    if (secs >= 0) {
        JText(formatBehind(secs * 1000), if (Jtv.isTv) 16.sp else 14.sp, color = Jtv.colors.tx,
            weight = FontWeight.SemiBold, modifier = modifier)
    }
}

/** "Behind live · −02:35" for the info strap / video tag, so a returning viewer knows what they see. */
@Composable
internal fun BehindLiveTag(ts: Timeshift, size: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val secs by remember(ts) { derivedStateOf { if (ts.replay || ts.vod || ts.atLive) -1L else ts.behindMs / 1000 } }
    if (secs >= 0) {
        val c = Jtv.colors
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).border(1.5.dp, c.acc, CircleShape))
            Spacer(Modifier.width(6.dp))
            JText(stringResource(R.string.player_behind_live, formatBehind(secs * 1000)), size, color = c.tx, weight = FontWeight.SemiBold)
        }
    }
}

/**
 * The Live pill (red-dot style, in the accent colour): filled when at live, outlined when behind.
 * Visual 34–36 dp, touch target ≥ 48 dp. Focus (TV) is the usual inverted fill.
 */
@Composable
internal fun LiveButton(ts: Timeshift, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Jtv.colors
    val atLive by remember(ts) { derivedStateOf { ts.atLive } }
    val vod = ts.vod
    val replay = ts.replay
    val label = when {
        replay -> stringResource(R.string.player_go_live)
        vod -> stringResource(R.string.player_back_to_schedule)
        else -> stringResource(R.string.common_live)
    }
    val descGoLive = stringResource(R.string.player_go_live_description)
    val descOnSchedule = stringResource(R.string.player_on_schedule)
    val descBackToSchedule = stringResource(R.string.player_back_to_schedule)
    val descLive = stringResource(R.string.player_live_description)
    val descGoToLive = stringResource(R.string.player_go_to_live)
    JtvClickable(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .semantics {
                contentDescription = when {
                    replay -> descGoLive
                    vod -> if (atLive) descOnSchedule else descBackToSchedule
                    atLive -> descLive
                    else -> descGoToLive
                }
                role = Role.Button
            },
        shape = RoundedCornerShape(24.dp),
        container = Color.Transparent,
        focusedContainer = Color.Transparent,
    ) { focused ->
        val shape = RoundedCornerShape(18.dp)
        val pill = when {
            focused -> Modifier.background(c.inv, shape)
            atLive -> Modifier.background(c.acc, shape)
            else -> Modifier.background(StrapBg, shape).border(1.5.dp, c.acc, shape)
        }
        val fg = when {
            focused -> c.invTx
            atLive -> c.accTx
            else -> c.tx
        }
        val dot = when {
            focused -> c.invTx
            atLive -> c.accTx
            else -> c.acc
        }
        Row(
            Modifier.align(Alignment.Center).then(pill).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            JText(label, if (Jtv.isTv) 16.sp else 14.sp, color = fg, weight = FontWeight.SemiBold)
        }
    }
}

/**
 * Bottom row of the controls: bar · "−02:35" · Live · [trailing] (e.g. full screen on the page).
 * Not seekable: only Live. Not live at all (no timeline yet): only [trailing].
 */
@Composable
internal fun SeekRow(
    ts: Timeshift,
    actions: PlayerActions,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
    barFocus: FocusRequester? = null,
    liveFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    onBarFocus: (Boolean) -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var barFocused by remember { mutableStateOf(false) }
    // TV: a quiet bar stays focusable (Down still reaches it) but invisible until focused.
    // Touch: a quiet bar isn't there at all, so a stray tap can't seek.
    val quiet = ts.barQuiet && !barFocused
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (ts.seekable && !(quiet && !Jtv.isTv)) {
            LiveSeekBar(
                ts, onCommit = { actions.seekTo(it) }, onInteract = onInteract,
                modifier = Modifier.weight(1f).alpha(if (quiet) 0f else 1f).then(
                    if (upFocus != null || liveFocus != null) Modifier.focusProperties {
                        upFocus?.let { up = it }
                        liveFocus?.let { down = it }
                    } else Modifier,
                ),
                focus = barFocus, onFocus = { barFocused = it; onBarFocus(it) },
            )
            Spacer(Modifier.width(10.dp))
            if (!quiet) BehindTime(ts)
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (ts.hasLiveButton) {
            LiveButton(
                ts, onClick = { actions.goLive() },
                modifier = Modifier
                    .then(if (liveFocus != null) Modifier.focusRequester(liveFocus) else Modifier)
                    .then(if (upFocus != null) Modifier.focusProperties { up = upFocus } else Modifier),
            )
        }
        trailing()
    }
}

/**
 * Previous channel · Play/Pause · Next channel, big round buttons. [playFocus] etc. are for TV.
 * While buffering, a ring runs round Play/Pause.
 */
@Composable
internal fun CentreControls(
    paused: Boolean,
    buffering: Boolean,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    side: Dp = 52.dp,
    main: Dp = 64.dp,
    gap: Dp = 40.dp,
    playFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onPlayFocus: (Boolean) -> Unit = {},
) {
    val c = Jtv.colors
    val vert = if (upFocus != null || downFocus != null) Modifier.focusProperties {
        upFocus?.let { up = it }
        downFocus?.let { down = it }
    } else Modifier
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        OverVideoIcon(PlayerIcons.SkipPrevious, stringResource(R.string.player_previous_channel), { actions.zap(-1) }, vert,
            size = side, iconSize = side * 0.54f)
        Box(contentAlignment = Alignment.Center) {
            if (buffering) CircularProgressIndicator(color = c.acc, strokeWidth = 3.dp, modifier = Modifier.size(main + 6.dp))
            OverVideoIcon(
                if (paused) Icons.Filled.PlayArrow else PlayerIcons.Pause, stringResource(if (paused) R.string.player_play else R.string.player_pause),
                { actions.togglePause() },
                Modifier
                    .then(if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                    .onFocusChanged { onPlayFocus(it.isFocused) }
                    .then(vert),
                size = main, iconSize = main * 0.53f,
            )
        }
        OverVideoIcon(PlayerIcons.SkipNext, stringResource(R.string.player_next_channel), { actions.zap(1) }, vert,
            size = side, iconSize = side * 0.54f)
    }
}

/** The channel number + name on a solid plate (top bar of the controls). */
@Composable
internal fun ControlsTitle(ch: com.fenyx.jtv.data.Channel?, size: androidx.compose.ui.unit.TextUnit, maxWidth: Dp, modifier: Modifier = Modifier) {
    if (ch == null) return
    JText(
        listOfNotNull(shownNumber(ch).takeIf { it > 0 }?.toString(), ch.name).joinToString("  "),
        size, color = Jtv.colors.tx, weight = FontWeight.SemiBold,
        modifier = modifier.widthIn(max = maxWidth).clip(RoundedCornerShape(6.dp))
            .background(StrapBg).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
