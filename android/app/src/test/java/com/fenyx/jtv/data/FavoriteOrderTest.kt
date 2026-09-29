package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteOrderTest {

    private data class Ch(val id: String, val group: String)

    private val channels = listOf(
        Ch("1", "News"), Ch("2", "Movies"), Ch("3", "News"), Ch("4", "Music"), Ch("5", "Movies")
    )

    @Test
    fun sortedFavorites_followsUserOrder_andDropsNonFavorites() {
        val out = FavoriteOrder.sortedFavorites(channels, listOf("4", "1", "5")) { it.id }
        assertEquals(listOf("4", "1", "5"), out.map { it.id })
    }

    @Test
    fun sortedFavorites_ignoresIdsMissingFromChannelList() {
        val out = FavoriteOrder.sortedFavorites(channels, listOf("99", "2")) { it.id }
        assertEquals(listOf("2"), out.map { it.id })
    }

    @Test
    fun move_forwardBackwardAndClamped() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), FavoriteOrder.move(l, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), FavoriteOrder.move(l, 3, 0))
        assertEquals(listOf("b", "c", "d", "a"), FavoriteOrder.move(l, 0, 99))
        assertEquals(listOf("b", "a", "c", "d"), FavoriteOrder.move(l, 1, -5))
        assertEquals(l, FavoriteOrder.move(l, 7, 0))
    }

    @Test
    fun merge_keepsHiddenIdsAfterVisibleOnes() {
        val stored = listOf("1", "hidden", "2", "3")
        assertEquals(listOf("3", "1", "2", "hidden"), FavoriteOrder.merge(stored, listOf("3", "1", "2")))
    }

    @Test
    fun groupByCategory_usesFirstAppearanceOrder_andIsStable() {
        val favs = listOf(Ch("4", "Music"), Ch("1", "News"), Ch("2", "Movies"), Ch("3", "News"), Ch("5", "Movies"))
        val out = FavoriteOrder.groupByCategory(favs) { it.group }
        assertEquals(listOf("4", "1", "3", "2", "5"), out.map { it.id })
    }
}
