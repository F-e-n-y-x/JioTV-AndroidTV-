package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The catch-up geturl request must match what the companion server sends (server/src/jio/stream.ts,
 * fed by server/src/api/playlist.ts /live/:id?utc=), which is the replay path known to work:
 *
 *   stream_type=Catchup&channel_id=<enc id>&srno=<enc srno>&programId=<enc showId>
 *     &begin=<startMs>&end=<stopMs>&showtime=<enc showtime>
 *
 * begin/end are epoch MILLISECONDS (the guide's startEpoch/endEpoch), not jiotv_go's
 * YYYYMMDDTHHMMSS; values go through encodeURIComponent.
 */
class CatchupRequestTest {

    private val ch = Channel(
        id = "143", name = "Aaj Tak", logoUrl = "", group = "News", streamUrl = "",
        channelNumber = 143, isCatchup = true,
    )

    // A programme as the native guide parses it (startEpoch / endEpoch are ms).
    private val start = 1_759_386_600_000L // 2025-10-02 06:30 UTC
    private val stop = 1_759_390_200_000L
    private val prog = EpgProgram(
        title = "Morning news", description = "", startMs = start, stopMs = stop,
        srno = "251002143000", showId = "SH12345", showtime = "12:00:00", catchup = true,
    )

    /** What server/src/jio/stream.ts builds for the same programme (written out by hand). */
    private fun serverBody(id: String, srno: String, programId: String, b: Long, e: Long, showtime: String): String {
        fun enc(s: String) = jsEncodeURIComponent(s)
        return "stream_type=Catchup&channel_id=${enc(id)}&srno=${enc(srno)}" +
            "&programId=${enc(programId)}&begin=$b&end=$e&showtime=${enc(showtime)}"
    }

    /** Reference encodeURIComponent: unreserved A-Z a-z 0-9 - _ . ! ~ * ' ( ) are kept, UTF-8 %XX otherwise. */
    private fun jsEncodeURIComponent(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.!~*'()") append(c)
            else append("%%%02X".format(b.toInt() and 0xFF))
        }
    }

    @Test
    fun catchupBody_matchesTheServer() {
        val req = Catchup.request(ch, prog, now = stop + 60_000)
        assertNotNull(req)
        val body = JioApiClient.geturlBody(req!!.channelNumber.toString(), req.toParams())
        assertEquals(serverBody("143", "251002143000", "SH12345", start, stop, "12:00:00"), body)
        assertEquals(
            "stream_type=Catchup&channel_id=143&srno=251002143000&programId=SH12345" +
                "&begin=1759386600000&end=1759390200000&showtime=12%3A00%3A00",
            body,
        )
    }

    @Test
    fun beginEnd_areEpochMilliseconds() {
        val body = JioApiClient.geturlBody("143", Catchup.request(ch, prog, now = stop + 1)!!.toParams())
        val params = body.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(start.toString(), params["begin"])
        assertEquals(stop.toString(), params["end"])
        assertEquals(13, params["begin"]!!.length) // ms, not seconds or YYYYMMDDTHHMMSS
    }

    @Test
    fun missingShowIdAndShowtime_sendEmptyValues_likeTheServer() {
        val p = prog.copy(showId = null, showtime = null)
        val body = JioApiClient.geturlBody("143", Catchup.request(ch, p, now = stop + 1)!!.toParams())
        assertEquals(serverBody("143", "251002143000", "", start, stop, ""), body)
    }

    @Test
    fun encoding_isEncodeURIComponent() {
        listOf("a b", "x+y", "it's (ok)!", "~*._-", "a/b?c=d&e", "हिन्दी").forEach {
            assertEquals(it, jsEncodeURIComponent(it), JioApiClient.encodeUriComponent(it))
        }
    }

    @Test
    fun liveBody_unchanged() {
        assertEquals("stream_type=Live&channel_id=143", JioApiClient.geturlBody("143", null))
    }

    @Test
    fun replayable_needsBothFlagsSrnoAndSevenDays() {
        val now = stop + 60_000
        assertTrue(Catchup.isReplayable(ch, prog, now))
        assertFalse("channel without catch-up", Catchup.isReplayable(ch.copy(isCatchup = false), prog, now))
        assertFalse("programme without catch-up", Catchup.isReplayable(ch, prog.copy(catchup = false), now))
        assertFalse("no srno", Catchup.isReplayable(ch, prog.copy(srno = null), now))
        assertFalse("blank srno", Catchup.isReplayable(ch, prog.copy(srno = ""), now))
        assertFalse("future", Catchup.isReplayable(ch, prog, start - 1))
        assertTrue("airing now (watch from start)", Catchup.isReplayable(ch, prog, start + 60_000))
        assertTrue("6 days ago", Catchup.isReplayable(ch, prog, start + 6 * 24 * 3_600_000L))
        assertFalse("8 days ago", Catchup.isReplayable(ch, prog, start + 8 * 24 * 3_600_000L))
        assertNull(Catchup.request(ch.copy(isCatchup = false), prog, now))
    }

    @Test
    fun pastNotReplayable() {
        val now = stop + 60_000
        assertFalse(Catchup.isPastNotReplayable(ch, prog, now))
        assertTrue(Catchup.isPastNotReplayable(ch, prog.copy(catchup = false), now))
        assertFalse("airing, not past", Catchup.isPastNotReplayable(ch, prog.copy(catchup = false), start + 1))
    }

    @Test
    fun request_carriesTheGuideFields() {
        val r = Catchup.request(ch, prog, now = stop + 1)!!
        assertEquals("143", r.channelId)
        assertEquals(143, r.channelNumber)
        assertEquals("251002143000", r.srno)
        assertEquals("SH12345", r.showId)
        assertEquals("12:00:00", r.showtime)
        assertEquals("Morning news", r.title)
        assertTrue(r.isFor(prog))
        val params = r.toParams()
        assertEquals("SH12345", params.programId)
        assertEquals(start, params.beginMs)
        assertEquals(stop, params.endMs)
    }

    @Test
    fun nextProgramme_isTheOneAfter() {
        val next = prog.copy(title = "Afternoon", startMs = stop, stopMs = stop + 1_800_000, srno = "2")
        val r = Catchup.request(ch, prog, now = next.stopMs + 1)!!
        assertEquals(next, Catchup.nextProgramme(listOf(prog, next), r))
        assertNull(Catchup.nextProgramme(listOf(prog), r))
    }

    @Test
    fun dayOffset_inLocalDays_clampedToJioRange() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val day = 24 * 3_600_000L
        // 2025-10-02 12:00 IST
        val now = 1_759_386_600_000L
        assertEquals(0, Catchup.dayOffset(now, now, ist))
        assertEquals(-1, Catchup.dayOffset(now - day, now, ist))
        assertEquals(-6, Catchup.dayOffset(now - 6 * day, now, ist))
        assertEquals(-6, Catchup.dayOffset(now - 9 * day, now, ist))
        assertEquals(0, Catchup.dayOffset(now + day, now, ist))
    }
}
