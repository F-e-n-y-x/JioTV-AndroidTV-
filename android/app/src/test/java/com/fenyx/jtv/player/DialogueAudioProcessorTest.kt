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
        flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
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

    /** Issue #3: the empty buffer Media3 sends first / after a reset must not crash the Off path. */
    @Test
    fun emptyInput_afterReset_doesNotThrow_atEveryLevel() {
        for (lvl in 0..4) {
            val p = newProcessor(lvl)
            p.reset(); p.configure(stereo16); p.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)        // output buffer is EMPTY_BUFFER again
            p.queueInput(AudioProcessor.EMPTY_BUFFER)          // threw "The source buffer is this buffer"
            p.queueInput(ByteBuffer.allocateDirect(0))
            val input = pcm(64)
            p.queueInput(input)
            assertFalse(input.hasRemaining())
            assertEquals(64 * 4, p.output.remaining())
        }
    }

    // ── Voice Boost must be clearly audible, also on near-mono channels ──

    private fun sineMix(seconds: Double, vararg tones: Pair<Double, Double>): ByteBuffer {
        val n = (48_000 * seconds).toInt()
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            var v = 0.0
            for ((f, amp) in tones) v += amp * kotlin.math.sin(2 * Math.PI * f * i / 48_000)
            val s = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            b.putShort(s); b.putShort(s) // mono content in a stereo stream
        }
        b.flip(); return b
    }

    private fun run(level: Int, input: ByteBuffer): ShortArray {
        val p = newProcessor(level)
        p.queueInput(input)
        val out = p.output
        val a = ShortArray(out.remaining() / 2)
        for (k in a.indices) a[k] = out.getShort(out.position() + k * 2)
        return a
    }

    /** Goertzel magnitude of the left channel at [f], skipping the first [skip] frames (filters settle). */
    private fun mag(pcm: ShortArray, f: Double, skip: Int = 9_600): Double {
        val w = 2 * Math.PI * f / 48_000; val c = 2 * kotlin.math.cos(w)
        var s1 = 0.0; var s2 = 0.0; var n = 0
        var i = skip * 2
        while (i < pcm.size) { val s0 = pcm[i] + c * s1 - s2; s2 = s1; s1 = s0; i += 2; n++ }
        return kotlin.math.sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / n
    }

    private fun db(x: Double) = 20 * kotlin.math.log10(x)

    /** Static tone only (steady tones never trigger the voice lift). */
    @Test
    fun max_liftsSpeechOverBass_byAtLeast6dB_onMonoContent() {
        val tones = arrayOf(100.0 to 0.1, 3000.0 to 0.1)
        val off = run(0, sineMix(1.0, *tones))
        val max = run(4, sineMix(1.0, *tones))
        val before = db(mag(off, 3000.0)) - db(mag(off, 100.0))
        val after = db(mag(max, 3000.0)) - db(mag(max, 100.0))
        assertTrue("speech/bass gain ${after - before} dB", after - before >= 6.0)
    }

    @Test
    fun levels_increaseTheEffect() {
        val tones = arrayOf(100.0 to 0.1, 3000.0 to 0.1)
        var last = -100.0
        for (lvl in 1..4) {
            val o = run(lvl, sineMix(1.0, *tones))
            val d = db(mag(o, 3000.0)) - db(mag(o, 100.0))
            assertTrue("level $lvl ($d dB) must beat level ${lvl - 1} ($last dB)", d > last)
            last = d
        }
    }

    @Test
    fun fullScaleInput_neverHardClips() {
        val out = run(4, sineMix(0.5, 3000.0 to 1.0, 200.0 to 0.9))
        assertTrue(out.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
    }

    // ── Voice detector and loudness ──

    /** Mono stream of [f] (frame index -> sample in -1..1). */
    private fun signal(seconds: Double, f: (Int) -> Double): ByteBuffer {
        val n = (48_000 * seconds).toInt()
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            val s = (f(i) * 32767).toInt().coerceIn(-32768, 32767).toShort()
            b.putShort(s); b.putShort(s)
        }
        b.flip(); return b
    }

    private fun syllables(i: Int) = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 4 * i / 48_000)

    @Test
    fun vad_rises_onSyllableRateModulated1kHz() {
        val p = newProcessor(2)
        p.queueInput(signal(2.0) { i -> 0.3 * syllables(i) * kotlin.math.sin(2 * Math.PI * 1000 * i / 48_000) })
        assertTrue("vad ${p.vad}", p.vad > 0.5f)
    }

    @Test
    fun vad_staysLow_onSteadyBassAndTreble() {
        val p = newProcessor(2)
        p.queueInput(sineMix(2.0, 100.0 to 0.3, 8000.0 to 0.1))
        assertTrue("vad ${p.vad}", p.vad < 0.1f)
    }

    @Test
    fun loudness_isRoughlyPreserved_onSpeechLikeSignal() {
        // 150 Hz voice with harmonics, opening and closing 4 times a second.
        val input = signal(10.0) { i ->
            var v = 0.0
            for (k in 1..20) v += kotlin.math.sin(2 * Math.PI * 150 * k * i / 48_000) / k
            0.12 * syllables(i) * v
        }
        val inPcm = ShortArray(input.remaining() / 2).also { input.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(it) }
        for (lvl in 1..4) {
            val out = run(lvl, input.duplicate().order(ByteOrder.nativeOrder()))
            val from = out.size - 48_000 * 2 * 3 // last 3 s, after the loudness match settled
            val diff = db(rms(out, from) / rms(inPcm, from))
            assertTrue("level $lvl output is $diff dB vs input", kotlin.math.abs(diff) <= 1.5)
        }
    }

    private fun rms(pcm: ShortArray, from: Int): Double {
        var s = 0.0
        for (i in from until pcm.size) s += pcm[i].toDouble() * pcm[i]
        return kotlin.math.sqrt(s / (pcm.size - from))
    }
}
