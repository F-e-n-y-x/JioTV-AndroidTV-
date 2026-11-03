package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlCacheTest {

    private var now = 1_000_000L
    private fun cache(max: Int = 6) = StreamUrlCache(maxEntries = max, marginSec = 20, nowSec = { now })

    private fun data(url: String) = JioApiClient.StreamData(
        streamUrl = url, licenseUrl = "", isMpd = true, headers = emptyMap(), licenseHeaders = emptyMap()
    )
    private fun live(id: String, exp: Long) =
        data("https://jiotvmblive.cdn.jio.com/$id/index.mpd?__hdnea__=st=${now}~exp=$exp~acl=/*~hmac=ab")

    @Test
    fun entryIsServedUntilExpiryMinusMargin() {
        val c = cache()
        assertTrue(c.put("1", live("1", now + 120), "tok"))
        now += 99                                   // 120 - 20 margin = valid until +100
        assertNotNull(c.take("1", "tok"))
    }

    @Test
    fun entryPastExpiryMarginIsNotServed() {
        val c = cache()
        assertTrue(c.put("1", live("1", now + 120), "tok"))
        now += 100
        assertFalse(c.hasValid("1", "tok"))
        assertNull(c.take("1", "tok"))
    }

    @Test
    fun alreadyNearlyExpiredOrNoExpiryIsNotCached() {
        val c = cache()
        assertFalse(c.put("1", live("1", now + 20), "tok"))
        assertFalse(c.put("2", data("https://cdn.example/2/index.m3u8?foo=bar"), "tok"))
        assertEquals(0, c.size())
    }

    @Test
    fun takeConsumesTheEntrySoARetryGoesLive() {
        val c = cache()
        c.put("1", live("1", now + 120), "tok")
        assertNotNull(c.take("1", "tok"))
        assertNull(c.take("1", "tok"))
    }

    @Test
    fun entryFromAnOlderAccessTokenIsNotServed() {
        val c = cache()
        c.put("1", live("1", now + 120), "old")
        assertFalse(c.hasValid("1", "new"))
        assertNull(c.take("1", "new"))
    }

    @Test
    fun keepsAtMostMaxEntriesDroppingLeastRecentlyUsed() {
        val c = cache(max = 6)
        for (i in 1..6) c.put("$i", live("$i", now + 120), "tok")
        assertTrue(c.hasValid("1", "tok"))         // touch 1: now 2 is the oldest
        c.put("7", live("7", now + 120), "tok")
        assertEquals(6, c.size())
        assertFalse(c.hasValid("2", "tok"))
        assertTrue(c.hasValid("1", "tok"))
        assertTrue(c.hasValid("7", "tok"))
    }

    @Test
    fun recordedScheduleVodUrlsAreNeverCached() {
        val c = cache()
        val exp = now + 3600
        assertFalse(c.put("872", data("https://sonyliv.slivcdn.com/x/y.mpd?__hdnea__=exp=$exp~hmac=1"), "tok"))
        assertFalse(c.put("873", data("https://cdn.example/a.m3u8?partner=jiotvvod&__hdnea__=exp=$exp~hmac=1"), "tok"))
        assertEquals(0, c.size())
        assertFalse(StreamUrlCache.isCacheable("https://x.SLIVCDN.com/a.mpd"))
        assertTrue(StreamUrlCache.isCacheable("https://jiotvmblive.cdn.jio.com/a.mpd"))
    }

    @Test
    fun neighbourIndicesWrapLikeZap() {
        assertEquals(listOf(4, 1), zapNeighbourIndices(0, 5))
        assertEquals(listOf(3, 0), zapNeighbourIndices(4, 5))
        assertEquals(listOf(1, 3), zapNeighbourIndices(2, 5))
    }

    @Test
    fun neighbourIndicesForTinyOrInvalidLists() {
        assertEquals(listOf(1), zapNeighbourIndices(0, 2))   // up and down land on the same channel
        assertEquals(listOf(0), zapNeighbourIndices(1, 2))
        assertEquals(emptyList<Int>(), zapNeighbourIndices(0, 1))
        assertEquals(emptyList<Int>(), zapNeighbourIndices(0, 0))
        assertEquals(emptyList<Int>(), zapNeighbourIndices(5, 3))
    }
}
