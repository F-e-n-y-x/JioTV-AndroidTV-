package com.fenyx.jtv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DialogueAudioProcessorTest {

    private val stereo16 = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    private fun pcm(frames: Int, offset: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(offset + frames * 4).order(ByteOrder.nativeOrder())
        repeat(offset) { b.put(0x55) }
        for (i in 0 until frames) {
            b.putShort((i * 37 % 20000 - 10000).toShort())
            b.putShort((i * 91 % 16000 - 8000).toShort())
        }
        b.flip()
        b.position(offset)
        return b
    }

    private fun bytes(b: ByteBuffer): ByteArray {
        val dup = b.duplicate(); val a = ByteArray(dup.remaining()); dup.get(a); return a
    }

    private fun newProcessor(level: Int) = DialogueAudioProcessor().apply {
        setLevel(level)
        configure(stereo16)
        flush()
    }

    @Test
    fun activeForStereo16_atEveryLevel() {
        for (lvl in 0..4) assertTrue(newProcessor(lvl).isActive)
    }

    @Test
    fun level0_isExactPassThrough_andConsumesInput_evenWithNonZeroPosition() {
        val p = newProcessor(0)
        val input = pcm(512, offset = 6)
        val expected = bytes(input)
        p.queueInput(input)
        assertFalse(input.hasRemaining())
        val out = p.output
        assertEquals(expected.size, out.remaining())
        assertArrayEquals(expected, bytes(out))
    }

    @Test
    fun boostedLevels_keepSizeAndConsumeInput() {
        for (lvl in 1..4) {
            val p = newProcessor(lvl)
            val input = pcm(512, offset = 4)
            val size = input.remaining()
            p.queueInput(input)
            assertFalse(input.hasRemaining())
            assertEquals(size, p.output.remaining())
        }
    }

    @Test
    fun switchingLevelsLive_keepsSizesConsistent() {
        val p = newProcessor(2)
        for (lvl in listOf(2, 0, 3, 0, 0, 4, 1)) {
            p.setLevel(lvl)
            val input = pcm(256)
            val size = input.remaining()
            p.queueInput(input)
            assertEquals(size, p.output.remaining())
        }
    }
}
