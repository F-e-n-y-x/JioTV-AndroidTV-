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

        var gainMb = 0
        if (normalize) gainMb += 500              // ~+5 dB
        gainMb += voiceBoost.coerceIn(0, 4) * 150 // makeup for the side attenuation, scales with level
        if (gainMb <= 0) return

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

    fun release() {
        try { loudness?.release() } catch (_: Throwable) {}
        loudness = null
    }
}
