package com.fenyx.jtv.ui

import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.ui.main.MainViewModel
import com.fenyx.jtv.ui.main.playIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayIndexTest {
    private fun ch(id: String) = Channel(id, id, "", "g", "")
    private val display = listOf(ch("a"), ch("b"), ch("c"))
    // "h" is a favourite hidden by the language filter: absent from display.
    private val favs = listOf(ch("c"), ch("h"), ch("a"))

    @Test fun favouritesIndexInsideFavourites() {
        assertEquals(0, playIndex("c", MainViewModel.GROUP_FAVORITES, favs, display))
        assertEquals(1, playIndex("h", MainViewModel.GROUP_FAVORITES, favs, display))
    }

    @Test fun normalGroupAndNullIndexDisplay() {
        assertEquals(1, playIndex("b", "Sports", favs, display))
        assertEquals(2, playIndex("c", null, favs, display))
    }

    @Test fun missingIdIsNull() {
        assertNull(playIndex("zz", MainViewModel.GROUP_FAVORITES, favs, display))
        assertNull(playIndex("h", null, favs, display))
    }
}
