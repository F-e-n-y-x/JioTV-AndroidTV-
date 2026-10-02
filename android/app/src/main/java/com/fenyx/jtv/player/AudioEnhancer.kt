package com.fenyx.jtv.player

import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Loudness makeup attached to the player's audio session.
 *
 * - **Auto Volume (normalize)** lifts quiet channels/dialogue to a more consistent level.
 * - When **Voice Boost** / **Reduce Background** are on, a little makeup gain compensates for the
 *   energy removed by the [DialogueAudioProcessor]'s background attenuation, so the result is clearer
 *   *and* loud enough.
 *
 * [LoudnessEnhancer] has a built-in target-loudness limiter, so it lifts level without hard clipping.
 * Creation is guarded — it is device-dependent and must never crash playback. The center-channel
 * voice isolation itself is done in software by [DialogueAudioProcessor], not here.
 */
class AudioEnhancer {

    private val TAG = "AudioEnhancer"
    private var loudness: LoudnessEnhancer? = null

    /** @param voiceBoost 0=off … 4=max */
    fun apply(sessionId: Int, normalize: Boolean, voiceBoost: Int) {
        release()
        if (sessionId <= 0) return // 0 == C.AUDIO_SESSION_ID_UNSET / global mix — skip

        val gainMb = targetGainMb(normalize, voiceBoost)

        // ALWAYS attach the effect, even with every audio option off. On several Amlogic/MediaTek TV
        // boxes the AudioTrack backing our explicit audio session id doesn't actually start on the first
        // stream (playback sits in STATE_BUFFERING forever) until an AudioEffect is bound to that
        // session. Attaching a LoudnessEnhancer here kicks it alive. This is exactly why toggling
        // "Voice Boost" by hand used to "fix" a stuck first playback right after setup: it was the first
        // thing that ever bound an effect to the session. See [targetGainMb] for why the gain is never
        // exactly 0.
        try {
            loudness = LoudnessEnhancer(sessionId).apply {
                setTargetGain(gainMb)
                enabled = true
            }
            Log.d(TAG, "LoudnessEnhancer on session $sessionId gain=${gainMb}mB")
        } catch (e: Throwable) {
            Log.w(TAG, "LoudnessEnhancer unavailable", e)
            loudness = null
        }
    }

    companion object {
        /**
         * Smallest gain we ever ask for: 10 mB = +0.1 dB, far below audibility (~1 dB JND).
         *
         * Issue #3 (1.5.7): streams stopped after a while ONLY with Voice Boost off (Auto Volume off),
         * i.e. only when this effect sat on the session at exactly 0 mB. Low..Max all give a positive
         * gain and work. AOSP's LoudnessEnhancer runs the same code for 0 mB as for any other gain, but
         * vendor audio HALs / effect wrappers on TV SoCs may treat a no-op effect as idle and bypass,
         * suspend or stand the session's effect chain down - a different runtime state from the one
         * every working setting uses. A tiny positive gain keeps the effect genuinely active, in the
         * same state that works for Low and above, at no audible cost.
         */
        const val MIN_GAIN_MB = 10

        /** Target gain in millibels for the given settings. Never less than [MIN_GAIN_MB]. */
        fun targetGainMb(normalize: Boolean, voiceBoost: Int): Int {
            var gainMb = 0
            if (normalize) gainMb += 500              // ~+5 dB
            gainMb += voiceBoost.coerceIn(0, 4) * 150 // makeup for the side attenuation, scales with level
            return gainMb.coerceAtLeast(MIN_GAIN_MB)
        }
    }

    fun release() {
        try { loudness?.release() } catch (_: Throwable) {}
        loudness = null
    }
}
