package com.fenyx.jtv.ui

import com.fenyx.jtv.ui.main.GridKey
import com.fenyx.jtv.ui.main.gridColumns
import com.fenyx.jtv.ui.main.gridMoveTarget
import com.fenyx.jtv.ui.main.gridScrollTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class GridLogicTest {

    @Test
    fun columns_followWidth_andStayInBounds() {
        // TV: ~654dp left after the categories column, cards >= 112dp, 12dp gap -> 5.
        assertEquals(5, gridColumns(654f, 112f, 12f, 3, 6))
        assertEquals(3, gridColumns(200f, 112f, 12f, 3, 6))
        assertEquals(6, gridColumns(2000f, 112f, 12f, 3, 6))
        // Phone: 2 portrait, 3 landscape.
        assertEquals(2, gridColumns(348f, 150f, 8f, 2, 3))
        assertEquals(3, gridColumns(760f, 150f, 8f, 2, 3))
    }

    @Test
    fun move_leftRight_oneStep_andClampedAtEnds() {
        assertEquals(4, gridMoveTarget(3, GridKey.Right, 5, 12))
        assertEquals(2, gridMoveTarget(3, GridKey.Left, 5, 12))
        assertEquals(0, gridMoveTarget(0, GridKey.Left, 5, 12))
        assertEquals(11, gridMoveTarget(11, GridKey.Right, 5, 12))
    }

    @Test
    fun move_upDown_aWholeRow() {
        assertEquals(8, gridMoveTarget(3, GridKey.Down, 5, 12))
        assertEquals(3, gridMoveTarget(8, GridKey.Up, 5, 12))
        assertEquals(3, gridMoveTarget(3, GridKey.Up, 5, 12)) // first row: stays
    }

    @Test
    fun move_down_intoShortLastRow_landsOnLastItem_andStaysThere() {
        // 12 items, 5 columns: rows are 0-4, 5-9, 10-11.
        assertEquals(11, gridMoveTarget(7, GridKey.Down, 5, 12))
        assertEquals(11, gridMoveTarget(9, GridKey.Down, 5, 12))
        assertEquals(10, gridMoveTarget(10, GridKey.Down, 5, 12))
    }

    @Test
    fun move_inListMode_isUpDownByOne_leftRightIgnored() {
        assertEquals(4, gridMoveTarget(3, GridKey.Down, 1, 10))
        assertEquals(2, gridMoveTarget(3, GridKey.Up, 1, 10))
        assertEquals(3, gridMoveTarget(3, GridKey.Left, 1, 10))
        assertEquals(3, gridMoveTarget(3, GridKey.Right, 1, 10))
        assertEquals(9, gridMoveTarget(9, GridKey.Down, 1, 10))
    }

    @Test
    fun scrollTarget_isFirstItemOfPreviousRow() {
        assertEquals(0, gridScrollTarget(3, 5))
        assertEquals(0, gridScrollTarget(7, 5))
        assertEquals(5, gridScrollTarget(12, 5))
        assertEquals(6, gridScrollTarget(7, 0)) // unknown columns must not divide by zero (treated as 1)
    }
}
