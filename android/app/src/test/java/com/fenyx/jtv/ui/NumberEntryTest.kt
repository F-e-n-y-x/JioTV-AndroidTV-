package com.fenyx.jtv.ui

import com.fenyx.jtv.ui.main.numberEntryCanGrow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberEntryTest {
    private val numbers = listOf(1, 15, 143, 1500, 7)

    @Test fun waitsWhileALongerNumberMatches() {
        assertTrue(numberEntryCanGrow("1", numbers))
        assertTrue(numberEntryCanGrow("15", numbers)) // 1500
        assertTrue(numberEntryCanGrow("14", numbers))
        assertTrue(numberEntryCanGrow("0", numbers))
    }

    @Test fun tunesAtOnceWhenNothingLongerMatches() {
        assertFalse(numberEntryCanGrow("7", numbers))
        assertFalse(numberEntryCanGrow("143", numbers))
        assertFalse(numberEntryCanGrow("1500", numbers))
        assertFalse(numberEntryCanGrow("9", numbers))
        assertFalse(numberEntryCanGrow("07", numbers))
    }
}
