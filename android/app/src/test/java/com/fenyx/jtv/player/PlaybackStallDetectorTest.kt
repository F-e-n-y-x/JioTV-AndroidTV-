package com.fenyx.jtv.player

import com.fenyx.jtv.player.PlaybackStallDetector.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStallDetectorTest {

    @Test
    fun progressingPlayback_neverActs() {
        val d = PlaybackStallDetector()
        for (t in 0L..60_000L step 2_000) {
            assertEquals(Action.NONE, d.onSample(t, true, positionMs = t, framesRendered = -1))
        }
    }

    @Test
    fun stuckPosition_kicksAfter8s_thenReprepares6sLater() {
        val d = PlaybackStallDetector()
        assertEquals(Action.NONE, d.onSample(0, true, 1000, -1))
        assertEquals(Action.NONE, d.onSample(2_000, true, 1000, -1))
        assertEquals(Action.NONE, d.onSample(6_000, true, 1000, -1))
        assertEquals(Action.KICK_AUDIO, d.onSample(8_000, true, 1000, -1))
        assertEquals(Action.NONE, d.onSample(10_000, true, 1000, -1))
        assertEquals(Action.NONE, d.onSample(12_000, true, 1000, -1))
        assertEquals(Action.REPREPARE, d.onSample(14_000, true, 1000, -1))
        // Nothing more until it is reset by progress or by playback stopping.
        assertEquals(Action.NONE, d.onSample(30_000, true, 1000, -1))
    }

    @Test
    fun kickThatWorks_resetsEscalation() {
        val d = PlaybackStallDetector()
        d.onSample(0, true, 1000, -1)
        assertEquals(Action.KICK_AUDIO, d.onSample(8_000, true, 1000, -1))
        assertEquals(Action.NONE, d.onSample(10_000, true, 3000, -1)) // recovered
        assertEquals(Action.NONE, d.onSample(16_000, true, 3000, -1))
        assertEquals(Action.KICK_AUDIO, d.onSample(18_000, true, 3000, -1))
    }

    @Test
    fun pausedOrBuffering_isIgnoredAndResets() {
        val d = PlaybackStallDetector()
        d.onSample(0, true, 1000, -1)
        d.onSample(6_000, true, 1000, -1)
        assertEquals(Action.NONE, d.onSample(8_000, false, 1000, -1))
        assertEquals(Action.NONE, d.onSample(10_000, true, 1000, -1))
        assertEquals(Action.NONE, d.onSample(16_000, true, 1000, -1))
        assertEquals(Action.KICK_AUDIO, d.onSample(18_000, true, 1000, -1))
    }

    @Test
    fun renderedFramesCountAsProgress() {
        val d = PlaybackStallDetector()
        for (i in 0..10) {
            assertEquals(Action.NONE, d.onSample(i * 2_000L, true, 1000, framesRendered = i * 50L))
        }
    }
}
