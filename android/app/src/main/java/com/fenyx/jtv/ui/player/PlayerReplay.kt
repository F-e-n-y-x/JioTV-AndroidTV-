package com.fenyx.jtv.ui.player

import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.data.CatchupRequest
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.formatDay
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.textStyle

// ───────────────────────── Catch-up (replay) in the player ─────────────────────────

/**
 * The programme the player is replaying (Jio catch-up), or null while live. Provided by the player
 * around its overlays, so the strap, the phone page and the mini player show "Replay · …" instead of
 * the live show and its progress.
 */
internal val LocalReplay = compositionLocalOf<CatchupRequest?> { null }

/** [p] is the programme being replayed right now. */
@Composable
internal fun isReplaying(p: EpgProgram?): Boolean {
    val r = LocalReplay.current ?: return false
    return p != null && r.isFor(p)
}

/** "Wed, 1 Oct · 8:00 PM – 9:00 PM". */
internal fun replayWhen(startMs: Long, stopMs: Long): String =
    "${formatDay(startMs)} · ${formatTime(startMs)} – ${formatTime(stopMs)}"

/** "Replay · Wed, 1 Oct · 8:00 PM – 9:00 PM" (under the show title). */
@Composable
internal fun replayStatus(p: EpgProgram): String = stringResource(R.string.player_replay_status, replayWhen(p.startMs, p.stopMs))

/** "Replay · Morning news · Wed, 1 Oct · 8:00 PM" (one line, where the title isn't shown separately). */
@Composable
internal fun replayLine(p: EpgProgram): String =
    stringResource(R.string.player_replay_line, p.title, formatDay(p.startMs), formatTime(p.startMs))

/** The guide entry for a request when the guide doesn't have it (any more). */
internal fun CatchupRequest.asProgram(): EpgProgram =
    EpgProgram(title, "", startMs, stopMs, srno, showId, showtime, catchup = true)

/** Saves a [CatchupRequest] as primitives (player state survives leaving for Settings / rotation). */
internal val CatchupRequestSaver = Saver<CatchupRequest?, Any>(
    save = { r -> ArrayList(catchupToList(r)) },
    restore = { v -> catchupFromList(v as List<*>) },
)

/** The inverse of the saver's list (also used by the phone/tablet player session). */
internal fun catchupToList(r: CatchupRequest?): List<Any?> =
    if (r == null) emptyList() else listOf(r.channelId, r.channelNumber, r.srno, r.showId, r.startMs, r.stopMs, r.showtime, r.title)

internal fun catchupFromList(l: List<*>): CatchupRequest? {
    if (l.size < 8) return null
    return runCatching {
        CatchupRequest(
            channelId = l[0] as String, channelNumber = l[1] as Int, srno = l[2] as String, showId = l[3] as String,
            startMs = l[4] as Long, stopMs = l[5] as Long, showtime = l[6] as String, title = l[7] as String,
        )
    }.getOrNull()
}

/**
 * Shown when a replayed programme reaches its end: one sentence and up to two buttons
 * ("Watch next programme" when Jio can replay it, and "Go live"). Same look as the error panel.
 */
@Composable
internal fun ReplayEndPanel(
    finished: String,
    next: EpgProgram?,
    firstFocus: FocusRequester,
    touch: Boolean,
    onNext: () -> Unit,
    onLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Jtv.colors
    val h = if (touch) 56.dp else 44.dp
    Column(
        modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(8.dp)).background(StrapBg).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.player_replay_ended, finished), style = textStyle(22.sp, FontWeight.SemiBold), color = c.tx,
            textAlign = TextAlign.Center, maxLines = 2,
        )
        if (next != null) {
            Spacer(Modifier.height(6.dp))
            JText(stringResource(R.string.player_replay_next, next.title, formatTime(next.startMs)), 16.sp, color = c.t2, maxLines = 2)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (next != null) {
                JtvButton(stringResource(R.string.player_watch_next), onNext, Modifier.focusRequester(firstFocus), primary = true, minHeight = h, fontSize = 18.sp)
                JtvButton(stringResource(R.string.player_go_live), onLive, minHeight = h, fontSize = 18.sp)
            } else {
                JtvButton(stringResource(R.string.player_go_live), onLive, Modifier.focusRequester(firstFocus), primary = true, minHeight = h, fontSize = 18.sp)
            }
        }
    }
}
