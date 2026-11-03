package com.fenyx.jtv.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PastGuideRefetchTest {
    private val hour = 60 * 60_000L

    @Test
    fun firstTimeIsDue() = assertTrue(pastGuideRefetchDue(null, 1_000L))

    @Test
    fun atMostOncePerHour() {
        val t = 10 * hour
        assertFalse(pastGuideRefetchDue(t, t + hour - 1))
        assertTrue(pastGuideRefetchDue(t, t + hour))
    }
}
