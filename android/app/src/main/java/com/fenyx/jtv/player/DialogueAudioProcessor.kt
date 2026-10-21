package com.fenyx.jtv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software dialogue enhancer ("Voice Boost" v3). Built for Indian TV, which is close to mono, so
 * everything that matters works on the mid channel alone.
 *
 * Per stereo frame, mid = (L + R) / 2, side = (L - R) / 2:
 *  1. **Side** — high-passed at 150 Hz and turned down (only helps on the rare real-stereo channel).
 *  2. **Speech tone** — static EQ on the mid: high-pass (rumble/music body out), a small dip at
 *     350 Hz (boxiness), a lift at 1.5 kHz and a high shelf from 2.2 kHz (clarity), and at High/Max a
 *     cut at 7.5 kHz so the lift doesn't turn into sibilance.
 *  3. **Voice-gated lift** — every 5 ms a tiny voice detector compares speech-band (300 Hz–3.4 kHz)
 *     energy with the whole mid and looks for syllable-rate flicker in it. While someone talks, the
 *     mid is ducked a little and a band-passed copy (centre 1.5 kHz) is added on top; between words it
 *     holds (300 ms fall), so the background doesn't pump up in every pause. With no voice it's off.
 *  4. **Gentle compressor** — block RMS above the running level is compressed (ratio 1.5–3,
 *     15 ms attack, 400 ms release). No fixed makeup gain.
 *  5. **Loudness match** — a slow gain keeps the output's 3 s level equal to the input's, so turning
 *     Voice Boost on never makes the channel quieter (≤ +1 dB/s up, ≤ −6 dB/s down, ±6 dB, frozen in
 *     silence).
 *  6. **Soft limiter** — anything above −3 dBFS is rounded off below −1 dBFS, so nothing clips.
 *
 * All gains are computed once per 5 ms block and ramped linearly across the next block (no clicks,
 * no per-sample log/pow). Levels 0–4 (Off/Low/Medium/High/Max); Off is an exact pass-through.
 * Only 16-bit stereo PCM is processed; anything else passes through untouched.
 */
@UnstableApi
class DialogueAudioProcessor : BaseAudioProcessor() {

    @Volatile private var level = 0          // 0..4
    private var appliedLevel = -1
    private var sampleRate = 48000
    private var block = 240                  // frames per 5 ms control block

    // Per-level settings.
    private var sideGain = 1f
    private var depth = 0f                   // how much the mid ducks under voice
    private var boost = 0f                   // how much speech band is added under voice
    private var slope = 0f                   // compressor 1 - 1/ratio

    private val hpSide = Biquad()
    private val hpMid = Biquad()
    private val boxMid = Biquad()            // 350 Hz dip
    private val bodyMid = Biquad()           // 1.5 kHz lift
    private val shelfMid = Biquad()          // 2.2 kHz high shelf
    private val sibMid = Biquad()            // 7.5 kHz cut
    private val hpVoice = Biquad()           // detector band 300 Hz…
    private val lpVoice = Biquad()           // …3.4 kHz
    private val liftBand = Biquad()          // band-pass for the lift (positive real: no phase dips)
    // K-weighting (BS.1770-style) for the loudness match. The output is already high-passed above the
    // 38 Hz RLB filter, so it only needs the shelf.
    private val kHpIn = Biquad(); private val kShelfIn = Biquad(); private val kShelfOut = Biquad()

    // Block accumulators.
    private var n = 0
    private var eMid = 0f; private var eVoice = 0f; private var eIn = 0f; private var eMix = 0f

    // Detector state.
    private var eMidS = 0f; private var eVoiceS = 0f; private var flux = 0f
    /** Voice probability 0..1, updated every block. */
    internal var vad = 0f
        private set

    // Gains, ramped per sample towards the per-block targets.
    private var duck = 1f; private var lift = 0f; private var gain = 1f
    private var dDuck = 0f; private var dLift = 0f; private var dGain = 0f

    // Compressor and loudness match.
    private var envDb = -100f; private var comp = 1f
    private var inLong = 0f; private var mixLong = 0f; private var outLong = 0f
    private var matchDb = 0f

    /** @param level 0=off,1=low,2=medium,3=high,4=max */
    fun setLevel(level: Int) {
        this.level = level.coerceIn(0, 4)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            sampleRate = inputAudioFormat.sampleRate
            block = sampleRate / 200
            appliedLevel = -1 // recompute coefficients for the new sample rate
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun recompute() {
        val lvl = level
        val i = (lvl - 1).coerceIn(0, 3)
        val fs = sampleRate
        sideGain = floatArrayOf(0.70f, 0.50f, 0.35f, 0.25f)[i]
        depth = floatArrayOf(0.15f, 0.22f, 0.28f, 0.33f)[i]
        boost = floatArrayOf(0.4f, 0.6f, 0.75f, 0.85f)[i]
        slope = 1f - 1f / floatArrayOf(1.5f, 2f, 2.5f, 3f)[i]
        hpSide.setHighPass(150f, 0.707f, fs)
        hpMid.setHighPass(floatArrayOf(80f, 95f, 105f, 115f)[i], 0.707f, fs)
        boxMid.setPeaking(350f, 1f, floatArrayOf(-1.5f, -2.5f, -3f, -3.5f)[i], fs)
        bodyMid.setPeaking(1500f, 1f, floatArrayOf(1f, 1.5f, 2f, 2f)[i], fs)
        shelfMid.setHighShelf(2200f, 0.7, floatArrayOf(2f, 3.5f, 3.75f, 4f)[i], fs)
        sibMid.setPeaking(7500f, 2f, floatArrayOf(0f, 0f, -1.5f, -3f)[i], fs)
        hpVoice.setHighPass(300f, 0.707f, fs)
        lpVoice.setLowPass(3400f, 0.707f, fs)
        liftBand.setBandPass(1500f, 0.5f, fs)
        kHpIn.setHighPass(38f, 0.5f, fs)
        kShelfIn.setHighShelf(1680f, 1.0, 4f, fs)
        kShelfOut.setHighShelf(1680f, 1.0, 4f, fs)
        appliedLevel = lvl
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 feeds an EMPTY buffer first (and after every stop()/re-prepare the output buffer is
        // that same shared EMPTY_BUFFER). Copying it into replaceOutputBuffer(0) then copies a buffer
        // into itself -> IllegalArgumentException "The source buffer is this buffer" -> player error
        // loop -> "Playback stopped". That was issue #3's "only with Voice Boost Off". Nothing to do.
        if (!inputBuffer.hasRemaining()) return
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

        val sg = sideGain
        var i = position
        while (i < limit) {
            val l = inputBuffer.getShort(i) / 32768f
            val r = inputBuffer.getShort(i + 2) / 32768f
            val m = (l + r) * 0.5f
            val v = lpVoice.process(hpVoice.process(m))
            val e = sibMid.process(shelfMid.process(bodyMid.process(boxMid.process(hpMid.process(m)))))
            val mid = duck * e + lift * liftBand.process(e)
            val side = hpSide.process((l - r) * 0.5f) * sg
            val outL = mid + side
            val outR = mid - side
            val kIn = kShelfIn.process(kHpIn.process(m))
            val kOut = kShelfOut.process(mid)
            eMid += m * m; eVoice += v * v; eIn += kIn * kIn; eMix += kOut * kOut

            output.putShort(toPcm(softLimit(outL * gain)))
            output.putShort(toPcm(softLimit(outR * gain)))
            duck += dDuck; lift += dLift; gain += dGain
            if (++n == block) endBlock()
            i += 4
        }
        inputBuffer.position(limit)
        output.flip()
    }

    /** Once per 5 ms: update the voice detector, compressor and loudness match; set the next ramps. */
    private fun endBlock() {
        val k = n.toFloat()
        // Voice detector: speech-band share of the mid, times syllable-rate flicker of the speech band.
        val prevVoice = eVoiceS
        eMidS += SMOOTH * (eMid / k - eMidS)
        eVoiceS += SMOOTH * (eVoice / k - eVoiceS)
        val share = eVoiceS / (eMidS + 1e-9f)
        flux += FLUX_AVG * (abs(eVoiceS - prevVoice) / (eVoiceS + prevVoice + 1e-9f) - flux)
        val raw = ((share - SHARE0) * SHARE_K).coerceIn(0f, 1f) * ((flux - FLUX0) * FLUX_K).coerceIn(0f, 1f)
        vad += (if (raw > vad) VAD_UP else VAD_DOWN) * (raw - vad)
        dDuck = (1f - depth * vad - duck) / block
        dLift = (boost * vad - lift) / block

        // Compressor on the block RMS (relative to the running level) + loudness match.
        val inMs = eIn / k
        val mixMs = eMix / k
        if (inMs > SILENCE) {
            inLong += LONG * (inMs - inLong)
            mixLong += LONG * (mixMs - mixLong)
            outLong += LONG * (mixMs * comp * comp - outLong)
            val x = 10f * log10(mixMs + 1e-10f)
            envDb += (if (x > envDb) ATTACK else RELEASE) * (x - envDb)
            val over = envDb - 10f * log10(mixLong + 1e-10f) - THRESHOLD_OVER_LEVEL
            comp = if (over > 0f) 10f.pow(-slope * over / 20f) else 1f
            val target = (10f * log10((inLong + 1e-12f) / (outLong + 1e-12f))).coerceIn(-6f, 6f)
            matchDb += (target - matchDb).coerceIn(-MATCH_DOWN, MATCH_UP)
        }
        dGain = (comp * 10f.pow(matchDb / 20f) - gain) / block

        n = 0; eMid = 0f; eVoice = 0f; eIn = 0f; eMix = 0f
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        for (b in arrayOf(hpSide, hpMid, boxMid, bodyMid, shelfMid, sibMid, hpVoice, lpVoice, liftBand, kHpIn, kShelfIn, kShelfOut)) {
            b.reset()
        }
    }

    /**
     * Linear up to 0.7 (−3 dBFS), then rounds off smoothly towards 0.89 (−1 dBFS), never above.
     * ponytail: a soft clipper is the only peak control; add a short look-ahead peak limiter if loud,
     * already-clipping channels sound gritty with Voice Boost on.
     */
    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (CEILING - KNEE)
        val y = KNEE + (CEILING - KNEE) * (over / (1f + over))
        return if (x < 0) -y else y
    }

    private fun toPcm(v: Float): Short = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()

    private companion object {
        // Per-block (5 ms) one-pole coefficients: 1 - exp(-5 ms / τ).
        fun coef(tauMs: Double) = (1 - exp(-5.0 / tauMs)).toFloat()
        val SMOOTH = coef(20.0)
        val FLUX_AVG = coef(250.0)
        val VAD_UP = coef(30.0)
        val VAD_DOWN = coef(300.0)
        val ATTACK = coef(15.0)
        val RELEASE = coef(400.0)
        val LONG = coef(3000.0)
        const val MATCH_UP = 0.005f          // dB per block = 1 dB/s
        const val MATCH_DOWN = 0.03f         // 6 dB/s
        const val SILENCE = 1e-5f            // −50 dBFS mean square
        const val THRESHOLD_OVER_LEVEL = 2f  // dB above the 3 s level
        const val KNEE = 0.7f
        const val CEILING = 0.89f
        // Voice detector, tuned on real channel clips (news, serial, movie, commentary).
        const val SHARE0 = 0.25f; const val SHARE_K = 6f   // full at 42 % speech-band share
        const val FLUX0 = 0.04f; const val FLUX_K = 50f    // full at 0.06 flux
    }

    /** Transposed-direct-form-II biquad (RBJ cookbook coefficients). */
    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun reset() { z1 = 0f; z2 = 0f }

        fun setHighPass(f0: Float, q: Float, fs: Int) {
            val (c, alpha) = cosAlpha(f0, q.toDouble(), fs)
            val a0 = 1 + alpha
            set((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun setLowPass(f0: Float, q: Float, fs: Int) {
            val (c, alpha) = cosAlpha(f0, q.toDouble(), fs)
            val a0 = 1 + alpha
            set((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        /** 0 dB peak gain. */
        fun setBandPass(f0: Float, q: Float, fs: Int) {
            val (c, alpha) = cosAlpha(f0, q.toDouble(), fs)
            val a0 = 1 + alpha
            set(alpha / a0, 0.0, -alpha / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun setPeaking(f0: Float, q: Float, gainDb: Float, fs: Int) {
            val a = 10.0.pow(gainDb / 40.0)
            val (c, alpha) = cosAlpha(f0, q.toDouble(), fs)
            val a0 = 1 + alpha / a
            set((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
        }

        /** Shelf slope [s] (1 = steepest without overshoot). */
        fun setHighShelf(f0: Float, s: Double, gainDb: Float, fs: Int) {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * f0 / fs
            val c = cos(w0)
            val k = 2 * sqrt(a) * sin(w0) / 2 * sqrt((a + 1 / a) * (1 / s - 1) + 2)
            val a0 = (a + 1) - (a - 1) * c + k
            set(
                a * ((a + 1) + (a - 1) * c + k) / a0,
                -2 * a * ((a - 1) + (a + 1) * c) / a0,
                a * ((a + 1) + (a - 1) * c - k) / a0,
                2 * ((a - 1) - (a + 1) * c) / a0,
                ((a + 1) - (a - 1) * c - k) / a0,
            )
        }

        private fun cosAlpha(f0: Float, q: Double, fs: Int): Pair<Double, Double> {
            val w0 = 2.0 * Math.PI * f0 / fs
            return cos(w0) to sin(w0) / (2 * q)
        }

        private fun set(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double) {
            b0 = nb0.toFloat(); b1 = nb1.toFloat(); b2 = nb2.toFloat(); a1 = na1.toFloat(); a2 = na2.toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}
