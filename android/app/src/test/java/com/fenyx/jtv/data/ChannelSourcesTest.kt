package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSourcesTest {

    @Test
    fun v31_winsOnSharedIds() {
        val out = ChannelSources.merge(mapOf(143 to "v31"), mapOf(143 to "v14"))
        assertEquals("v31", out[143])
    }

    @Test
    fun v14Only_keptOnlyWhenAllowListed() {
        val out = ChannelSources.merge(
            v31 = mapOf(1 to "a"),
            v14 = mapOf(625 to "Zee Bangla", 2001 to "PlusTest2 HD", 3507 to "Sony YAY")
        )
        assertTrue(625 in out)
        assertFalse(2001 in out)
        assertFalse(3507 in out)
        assertEquals(2, out.size)
    }

    @Test
    fun emptyV31_stillKeepsAllowListedV14() {
        val out = ChannelSources.merge(emptyMap(), mapOf(414 to "Zee Yuva", 167 to "Zee TV HD"))
        assertEquals(setOf(414), out.keys)
    }

    @Test
    fun droppedFavourite_resolvesToNothing() {
        val channels = ChannelSources.merge(mapOf(1 to "1"), mapOf(167 to "167")).values.map { it }
        val out = FavoriteOrder.sortedFavorites(channels, listOf("167", "1")) { it }
        assertEquals(listOf("1"), out)
    }
}
