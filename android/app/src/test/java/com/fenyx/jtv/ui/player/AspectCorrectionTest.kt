package com.fenyx.jtv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AspectCorrectionTest {

    private val d = 0.001f
    private val f43 = 4f / 3f
    private val f169 = 16f / 9f

    @Test
    fun pal576NoSar_undeclared_becomes43() {
        assertEquals(f43, correctedStreamAspect(720, 576, 1f, null), d)
        assertEquals(f43, correctedStreamAspect(720, 576, 0f, null), d)
    }

    @Test
    fun pal576With43Par_unchanged() {
        assertEquals(720 * 1.0667f / 576f, correctedStreamAspect(720, 576, 1.0667f, null), d)
    }

    @Test
    fun pal576Anamorphic169_unchanged() {
        assertEquals(720 * 1.4222f / 576f, correctedStreamAspect(720, 576, 1.4222f, null), d)
    }

    @Test
    fun pal576NoSar_declared169_becomes169() {
        assertEquals(f169, correctedStreamAspect(720, 576, 1f, "16x9"), d)
    }

    @Test
    fun declared43_on125Stream_becomes43() {
        assertEquals(f43, correctedStreamAspect(720, 576, 1f, "4x3"), d)
    }

    @Test
    fun declaredFormats_areTolerant() {
        assertEquals(f43, correctedStreamAspect(720, 576, 1f, " 4:3 "), d)
        assertEquals(f169, correctedStreamAspect(720, 576, 1f, "16:9"), d)
        assertEquals(f169, correctedStreamAspect(720, 576, 1f, "16X9"), d)
    }

    @Test
    fun hd_undeclared_untouched() {
        assertEquals(f169, correctedStreamAspect(1920, 1080, 1f, null), d)
    }

    @Test
    fun hd_declared43_forces43_currentBehaviour() {
        // Questionable but identical to the original inline logic: declared 4x3 wins at any size.
        assertEquals(f43, correctedStreamAspect(1920, 1080, 1f, "4x3"), d)
    }

    @Test
    fun unknownSize_is169() {
        assertEquals(f169, correctedStreamAspect(0, 0, 1f, null), d)
    }

    @Test
    fun fillWidensOnlyWideFrames() {
        // 16:9 SD frame with burned-in 4:3 bars (Zee Talkies): widen.
        assertEquals(true, isWideFrame(1024, 576, 1f))
        assertEquals(true, isWideFrame(720, 576, 1.4222f))
        // A frame that is itself 4:3: FILL already stretches it, widening would crop.
        assertEquals(false, isWideFrame(720, 576, 1f))
        assertEquals(false, isWideFrame(720, 576, 1.0667f))
        assertEquals(false, isWideFrame(640, 480, 0f))
        assertEquals(true, isWideFrame(0, 0, 1f))
    }
}
