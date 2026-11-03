package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryPrefsTest {
    private val all = listOf("ALL", "Kids", "Movies", "News")

    @Test fun noPrefs_keepsDefault() = assertEquals(all, CategoryPrefs().arrange(all, "ALL"))

    @Test fun savedOrder_andHidden_butAllAlwaysShown() {
        val p = CategoryPrefs(order = listOf("News", "ALL", "Kids", "Movies"), hidden = setOf("Movies", "ALL"))
        assertEquals(listOf("News", "ALL", "Kids"), p.arrange(all, "ALL"))
    }

    @Test fun unknownKeys_keepTheirPlace() {
        // The guide's Replay (not in the saved order) stays right after All.
        val p = CategoryPrefs(order = listOf("News", "ALL", "Kids", "Movies"))
        assertEquals(listOf("News", "REPLAY", "ALL", "Kids", "Movies"), p.arrange(listOf("ALL", "REPLAY", "Kids", "Movies", "News"), "ALL"))
    }

    @Test fun move_upDownTop() {
        var p = CategoryPrefs().move(all, "News", -1)
        assertEquals(listOf("ALL", "Kids", "News", "Movies"), p.arrange(all, "ALL"))
        p = p.move(all, "Kids", null)
        assertEquals(listOf("Kids", "ALL", "News", "Movies"), p.arrange(all, "ALL"))
        p = p.move(all, "Kids", 1)
        assertEquals(listOf("ALL", "Kids", "News", "Movies"), p.arrange(all, "ALL"))
    }
}
