package com.fenyx.jtv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Software dialogue enhancer ("Voice Boost") — center-channel emphasis with bass preservation.
 *
 * Based on standard dialogue-enhancement practice (extract the center → speech-shape it → mix back
 * with the original to retain the rest), tuned to avoid the two failure modes of naive center
 * extraction: lost bass and a thin/mono sound.
 *
 * Per stereo frame:
 *     mid  = (L + R) / 2     // center  -> dialogue (and most of the bass, which is usually mono)
 *     side = (L - R) / 2     // stereo  -> music / effects / ambience
 *
 * Then:
 *  1. **Bass stays full** — the mid is kept FULL-RANGE (no high-pass), so all the bass/warmth that
 *     lives in the center is preserved.
 *  2. **Clean the sides** — only the SIDE is high-passed (~120 Hz) so low frequencies stay centered;
 *     this de-muds the background without touching the bass.
 *  3. **Lift the voice** — a presence boost (~2.6 kHz peaking EQ) is applied to the mid for clarity.
 *  4. **Lower the background** — the side is attenuated *moderately* (not erased), so the mix keeps
 *     its width and body instead of collapsing to a thin mono sound.
 *  5. Recombine:  L' = midBoosted ± sideReduced.
 *
 * Levels 0–4 (Off/Low/Medium/High/Max) scale the presence boost and side reduction together. Pure PCM
 * math + two biquads → works on any TV and is light on CPU. Pass-through when off or for
 * non-stereo / non-16-bit audio.
 */
@UnstableApi
class DialogueAudioProcessor : BaseAudioProcessor() {

    @Volatile private var level = 0          // 0..4
    private var appliedLevel = -1
    private var sampleRate = 48000

    private var midGain = 1.0f
    private var sideGain = 1.0f

    private val hpSide = Biquad()            // high-pass the SIDE only (keep bass centered)
    private val presenceMid = Biquad()       // presence boost on the MID (voice clarity)

    /** @param level 0=off,1=low,2=medium,3=high,4=max */
    fun setLevel(level: Int) {
        this.level = level.coerceIn(0, 4)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            sampleRate = inputAudioFormat.sampleRate
            appliedLevel = -1 // recompute coefficients for the new sample rate
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun recompute() {
        val lvl = level
        when (lvl) {
            1 -> { sideGain = 0.80f; midGain = 1.00f; presenceMid.setPeaking(2600f, 0.9f, 3.0f, sampleRate) }
            2 -> { sideGain = 0.65f; midGain = 1.00f; presenceMid.setPeaking(2600f, 0.9f, 4.5f, sampleRate) }
            3 -> { sideGain = 0.50f; midGain = 1.03f; presenceMid.setPeaking(2600f, 0.9f, 6.0f, sampleRate) }
            4 -> { sideGain = 0.38f; midGain = 1.06f; presenceMid.setPeaking(2600f, 0.9f, 7.5f, sampleRate) }
            else -> { sideGain = 1.0f; midGain = 1.0f }
        }
        // De-mud the sides only; bass in the center is untouched.
        hpSide.setHighPass(120f, 0.707f, sampleRate)
        appliedLevel = lvl
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        val output = replaceOutputBuffer(size)

        if (level == 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        if (level != appliedLevel) recompute()

        val mg = midGain
        val sg = sideGain
        var i = position
        while (i < limit) {
            val l = inputBuffer.getShort(i).toInt()
            val r = inputBuffer.getShort(i + 2).toInt()
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            val midOut = presenceMid.process(mid) * mg   // full-range mid (bass kept) + presence
            val sideOut = hpSide.process(side) * sg       // de-mudded, reduced background
            output.putShort(clamp16(midOut + sideOut))
            output.putShort(clamp16(midOut - sideOut))
            i += 4
        }
        inputBuffer.position(limit)
        output.flip()
    }

    override fun onFlush() {
        hpSide.reset(); presenceMid.reset()
    }

    private fun clamp16(v: Float): Short {
        val i = v.toInt()
        return when {
            i > 32767 -> 32767
            i < -32768 -> -32768
            else -> i.toShort()
        }
    }

    /** Transposed-direct-form-II biquad (RBJ cookbook coefficients). */
    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun reset() { z1 = 0f; z2 = 0f }

        fun setHighPass(f0: Float, q: Float, fs: Int) {
            val w0 = 2.0 * Math.PI * f0 / fs
            val c = cos(w0); val s = sin(w0)
            val alpha = s / (2 * q)
            val a0 = 1 + alpha
            b0 = ((1 + c) / 2 / a0).toFloat()
            b1 = (-(1 + c) / a0).toFloat()
            b2 = ((1 + c) / 2 / a0).toFloat()
            a1 = (-2 * c / a0).toFloat()
            a2 = ((1 - alpha) / a0).toFloat()
        }

        fun setPeaking(f0: Float, q: Float, gainDb: Float, fs: Int) {
            val A = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * f0 / fs
            val c = cos(w0); val s = sin(w0)
            val alpha = s / (2 * q)
            val a0 = 1 + alpha / A
            b0 = ((1 + alpha * A) / a0).toFloat()
            b1 = (-2 * c / a0).toFloat()
            b2 = ((1 - alpha * A) / a0).toFloat()
            a1 = (-2 * c / a0).toFloat()
            a2 = ((1 - alpha / A) / a0).toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}
