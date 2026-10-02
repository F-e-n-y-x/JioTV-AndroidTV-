package com.fenyx.jtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEnhancerGainTest {

    @Test
    fun gain_isNeverZero_forAnySetting() {
        for (normalize in listOf(false, true)) {
            for (level in -2..6) {
                val g = AudioEnhancer.targetGainMb(normalize, level)
                assertTrue("normalize=$normalize level=$level gave $g", g >= AudioEnhancer.MIN_GAIN_MB)
                assertTrue(g > 0)
            }
        }
    }

    @Test
    fun allOff_usesTinyInaudibleGain() {
        assertEquals(10, AudioEnhancer.targetGainMb(normalize = false, voiceBoost = 0))
    }

    @Test
    fun nonZeroSettings_unchanged() {
        assertEquals(150, AudioEnhancer.targetGainMb(false, 1))
        assertEquals(600, AudioEnhancer.targetGainMb(false, 4))
        assertEquals(500, AudioEnhancer.targetGainMb(true, 0))
        assertEquals(800, AudioEnhancer.targetGainMb(true, 2))
    }
}
