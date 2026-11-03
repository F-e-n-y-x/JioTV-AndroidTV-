package com.fenyx.jtv.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One past (or airing) programme to replay from Jio's catch-up CDN: the channel plus the guide
 * identifiers Jio's geturl needs (stream_type=Catchup). Built from the native guide only; XMLTV
 * programmes have no srno and can't be replayed.
 *
 * Serializable + primitive-only so it can ride on the TV [com.fenyx.jtv.Player] nav key and in the
 * phone/tablet player session's saved state.
 */
@Immutable
@Serializable
data class CatchupRequest(
    /** The logical channel id the guide used (the key into the guide data). */
    val channelId: String,
    /** Jio channel number: the `channel_id` geturl is asked for. */
    val channelNumber: Int,
    val srno: String,
    /** The programme's showId (sent to Jio as `programId`). */
    val showId: String,
    val startMs: Long,
    val stopMs: Long,
    val showtime: String,
    val title: String,
    /**
     * "Watch from the start" of a show that is still on: the end sent to geturl (Jio rejects a
     * future end, so it's "now - 1 min" when the request was made). 0 = a finished show (end = [stopMs]).
     */
    val untilMs: Long = 0L,
) {
    /** A start-over of the airing show: when the recording ends, the player goes back to live. */
    val isStartOver: Boolean get() = untilMs > 0

    fun toParams(): JioApiClient.CatchupParams = JioApiClient.CatchupParams(
        srno = srno, programId = showId, beginMs = startMs, endMs = if (isStartOver) untilMs else stopMs, showtime = showtime,
    )

    /** True when this request is for [p] (same channel guide entry). */
    fun isFor(p: EpgProgram): Boolean = p.startMs == startMs && (p.srno ?: "") == srno
}

object Catchup {
    /** Jio keeps catch-up for 7 days (the guide's offset=-1..-6 plus today). */
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** Oldest day offset Jio's getepg serves for catch-up. */
    const val OLDEST_OFFSET = -6

    /**
     * Jio can replay [p] on [ch]: both the channel and the programme carry Jio's catch-up flag
     * (jiotv_go: isCatchupAvailable=false channels never play), the guide has its srno, it has
     * started, and it began within the last 7 days.
     */
    fun isReplayable(ch: Channel, p: EpgProgram, now: Long): Boolean =
        ch.isCatchup && p.catchup && !p.srno.isNullOrBlank() &&
            p.startMs <= now && p.stopMs > p.startMs && now - p.startMs <= WINDOW_MS

    /** A finished programme that can't be replayed (shown dimmed, "Not available to replay"). */
    fun isPastNotReplayable(ch: Channel, p: EpgProgram, now: Long): Boolean =
        p.stopMs <= now && !isReplayable(ch, p, now)

    fun request(ch: Channel, p: EpgProgram, now: Long = System.currentTimeMillis()): CatchupRequest? {
        if (!isReplayable(ch, p, now)) return null
        return CatchupRequest(
            channelId = ch.id,
            channelNumber = ch.channelNumber,
            srno = p.srno.orEmpty(),
            showId = p.showId.orEmpty(),
            startMs = p.startMs,
            stopMs = p.stopMs,
            showtime = p.showtime.orEmpty(),
            title = p.title,
        )
    }

    /** Jio needs the show to have run a little before it has a recording to serve. */
    const val START_OVER_MIN_RUN_MS = 2L * 60 * 1000

    /** Jio rejects a geturl end in the future; it serves the recording up to a minute ago. */
    const val START_OVER_LAG_MS = 60_000L

    /**
     * "Watch from the start" for [p] while it is on: a replay from its start up to a minute ago, or
     * null when Jio can't replay it or it began less than [START_OVER_MIN_RUN_MS] ago.
     */
    fun startOver(ch: Channel, p: EpgProgram, now: Long = JioClock.nowMs()): CatchupRequest? {
        if (now >= p.stopMs || now - p.startMs < START_OVER_MIN_RUN_MS) return null
        return request(ch, p, now)?.copy(untilMs = now - START_OVER_LAG_MS)
    }

    /** The programme right after [after] in [programs] (sorted by start), or null. */
    fun nextProgramme(programs: List<EpgProgram>, after: CatchupRequest): EpgProgram? =
        programs.firstOrNull { it.startMs >= after.stopMs - 60_000 && it.startMs > after.startMs }

    /**
     * Day offset (as Jio's getepg `offset`) of [t] relative to [now] in the device's time zone:
     * 0 = today, -1 = yesterday… Clamped to Jio's catch-up range.
     */
    fun dayOffset(t: Long, now: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        fun day(ms: Long): Long = Math.floorDiv(ms + tz.getOffset(ms), 24L * 60 * 60 * 1000)
        return (day(t) - day(now)).toInt().coerceIn(OLDEST_OFFSET, 0)
    }
}
