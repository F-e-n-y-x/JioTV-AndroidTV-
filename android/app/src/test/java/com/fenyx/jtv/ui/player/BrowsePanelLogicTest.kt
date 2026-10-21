package com.fenyx.jtv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowsePanelLogicTest {

    @Test
    fun columns_leftGoesToCategories_rightAndOkEnterChannels() {
        assertEquals(BrowseCol.Categories, panelColumnAfter(BrowseCol.Channels, PanelKey.Left))
        assertEquals(BrowseCol.Channels, panelColumnAfter(BrowseCol.Channels, PanelKey.Right))
        assertEquals(BrowseCol.Channels, panelColumnAfter(BrowseCol.Channels, PanelKey.Ok))
        assertEquals(BrowseCol.Channels, panelColumnAfter(BrowseCol.Categories, PanelKey.Right))
        assertEquals(BrowseCol.Channels, panelColumnAfter(BrowseCol.Categories, PanelKey.Ok))
        assertEquals(BrowseCol.Categories, panelColumnAfter(BrowseCol.Categories, PanelKey.Left))
    }

    @Test
    fun step_movesOneAndWrapsAtEnds() {
        assertEquals(3, stepIndex(2, 1, 10))
        assertEquals(1, stepIndex(2, -1, 10))
        assertEquals(9, stepIndex(0, -1, 10))
        assertEquals(0, stepIndex(9, 1, 10))
        assertEquals(0, stepIndex(0, 1, 1))
        assertEquals(0, stepIndex(5, 1, 0))
    }

    @Test
    fun step_heldKeyAcceleratesButStopsOnTheEndBeforeWrapping() {
        assertEquals(1, holdStep(0))
        assertEquals(2, holdStep(6))
        assertEquals(4, holdStep(16))
        assertEquals(14, stepIndex(10, 1, 100, repeat = 16))
        assertEquals(99, stepIndex(97, 1, 100, repeat = 16)) // overshoot stops on the last row
        assertEquals(0, stepIndex(99, 1, 100, repeat = 16))  // then wraps
        assertEquals(0, stepIndex(2, -1, 100, repeat = 16))
    }

    @Test
    fun scroll_openPutsTheItemOneRowBelowTheTop() {
        assertEquals(41, scrollTarget(42, 0, 4, open = true))
        assertEquals(0, scrollTarget(0, 0, 4, open = true))
        assertEquals(0, scrollTarget(0, -1, -1, open = false)) // nothing laid out yet
    }

    @Test
    fun scroll_stepOnlyScrollsWhenOffScreen() {
        assertNull(scrollTarget(3, 0, 4, open = false))
        assertEquals(5, scrollTarget(5, 6, 10, open = false))   // above: item becomes the first row
        assertEquals(7, scrollTarget(11, 6, 10, open = false))  // below: just far enough
        assertEquals(0, scrollTarget(0, 90, 94, open = false))  // wrapped to the top
        assertEquals(95, scrollTarget(99, 0, 4, open = false)) // wrapped to the bottom
    }
}
