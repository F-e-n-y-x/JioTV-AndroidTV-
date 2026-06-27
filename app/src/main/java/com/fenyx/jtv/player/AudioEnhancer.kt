package com.fenyx.jtv.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log

/**
 * Attaches audio post-processing effects to an ExoPlayer audio session so the app can offer audio
 * controls the TV itself lacks:
 *
 *  - **Auto Volume (normalize)**: a [LoudnessEnhancer] makeup gain so quiet channels/dialogue are
 *    lifted to a more consistent level.
 *  - **Voice Boost**: a [DynamicsProcessing] pre-EQ that **cuts bass/treble (where most background
 *    music & effects live) and boosts the speech band (~250 Hz–4 kHz)**, plus a multiband compressor
 *    that ducks the background bands — so dialogue is louder and clearer relative to the background.
 *  - **Reduce Background**: a stronger multiband compressor + limiter ("night mode") that tames loud
 *    music/effects across the whole spectrum.
 *
 * All effect creation is wrapped in try/catch — AudioEffects are device-dependent and may be missing
 * on some TVs; a failure here must never crash playback. Effects are released and rebuilt whenever the
 * audio session id or any setting changes.
 */
class AudioEnhancer {

    private val TAG = "AudioEnhancer"
    private var loudness: LoudnessEnhancer? = null
    private var dynamics: DynamicsProcessing? = null

    /** @param voiceBoost 0=off, 1=low, 2=high */
    fun apply(sessionId: Int, normalize: Boolean, voiceBoost: Int, reduceBackground: Boolean) {
        release()
        if (sessionId <= 0) return // 0 == C.AUDIO_SESSION_ID_UNSET / global mix — skip
        if (!(normalize || voiceBoost > 0 || reduceBackground)) return

        // Overall makeup gain (compression/EQ above can lower perceived loudness; lift it back).
        try {
            var gainMb = 0
            if (normalize) gainMb += 500             // ~+5 dB
            if (voiceBoost == 1) gainMb += 400       // ~+4 dB
            if (voiceBoost == 2) gainMb += 800       // ~+8 dB
            if (reduceBackground) gainMb += 300      // compensate the compression
            if (gainMb > 0) {
                loudness = LoudnessEnhancer(sessionId).apply {
                    setTargetGain(gainMb)
                    enabled = true
                }
                Log.d(TAG, "LoudnessEnhancer on session $sessionId gain=${gainMb}mB")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "LoudnessEnhancer unavailable", e)
        }

        if ((voiceBoost > 0 || reduceBackground) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamics = buildDynamics(sessionId, voiceBoost, reduceBackground)
                Log.d(TAG, "DynamicsProcessing on session $sessionId voice=$voiceBoost night=$reduceBackground")
            } catch (e: Throwable) {
                Log.w(TAG, "DynamicsProcessing unavailable", e)
                dynamics = null
            }
        }
    }

    private fun buildDynamics(sessionId: Int, voiceBoost: Int, reduceBackground: Boolean): DynamicsProcessing {
        val channels = 2
        val preEqInUse = voiceBoost > 0
        val mbcInUse = reduceBackground || voiceBoost > 0
        val bands = 3 // low (<250Hz), speech (250Hz-4kHz), high (>4kHz)

        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channels,
            preEqInUse, bands,
            mbcInUse, bands,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true
        ).build()

        val dp = DynamicsProcessing(0, sessionId, cfg)

        val lowCut = 250f
        val midCut = 4000f
        val highCut = 16000f

        for (ch in 0 until channels) {
            // ── Pre-EQ: lift speech, cut background bass & treble ──
            if (preEqInUse) {
                val pre = dp.getPreEqByChannelIndex(ch)
                pre.isEnabled = true
                val lowGain = if (voiceBoost == 2) -7f else -3f
                val midGain = if (voiceBoost == 2) 9f else 5f
                val highGain = if (voiceBoost == 2) -5f else -2f
                setEqBand(pre.getBand(0), lowCut, lowGain)
                setEqBand(pre.getBand(1), midCut, midGain)
                setEqBand(pre.getBand(2), highCut, highGain)
            }

            // ── Multiband compressor: duck loud background, keep speech dynamic ──
            if (mbcInUse) {
                val mbc = dp.getMbcByChannelIndex(ch)
                mbc.isEnabled = true
                val night = reduceBackground
                val voicing = voiceBoost > 0
                // Background (low/high) bands compressed hard; speech (mid) band compressed lightly
                // and given makeup so dialogue stays forward.
                val bgRatio = if (night) 5f else if (voicing) 3.5f else 1f
                val bgThreshold = if (night) -30f else -26f
                val bgPostGain = 0f
                val midRatio = if (night) 2f else 1.3f
                val midThreshold = -20f
                val midPostGain = if (voicing) 3f else 1f
                setMbcBand(mbc.getBand(0), lowCut, bgRatio, bgThreshold, bgPostGain)
                setMbcBand(mbc.getBand(1), midCut, midRatio, midThreshold, midPostGain)
                setMbcBand(mbc.getBand(2), highCut, bgRatio, bgThreshold, bgPostGain)
            }

            // Safety limiter to prevent clipping from the gains/boosts above.
            val limiter = dp.getLimiterByChannelIndex(ch)
            limiter.isEnabled = true
            limiter.threshold = -1f
            limiter.ratio = 10f
            limiter.postGain = 0f
        }
        dp.enabled = true
        return dp
    }

    private fun setEqBand(band: DynamicsProcessing.EqBand, cutoffHz: Float, gainDb: Float) {
        band.isEnabled = true
        band.cutoffFrequency = cutoffHz
        band.gain = gainDb
    }

    private fun setMbcBand(
        band: DynamicsProcessing.MbcBand,
        cutoffHz: Float,
        ratio: Float,
        threshold: Float,
        postGain: Float
    ) {
        band.isEnabled = true
        band.cutoffFrequency = cutoffHz
        band.ratio = ratio
        band.threshold = threshold
        band.postGain = postGain
        band.attackTime = 5f
        band.releaseTime = 80f
    }

    fun release() {
        try { loudness?.release() } catch (_: Throwable) {}
        try { dynamics?.release() } catch (_: Throwable) {}
        loudness = null
        dynamics = null
    }
}
