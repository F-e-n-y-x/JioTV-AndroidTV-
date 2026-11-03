package com.fenyx.jtv.player

import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Loudness makeup attached to the player's audio session.
 *
 * - **Auto Volume (normalize)** lifts quiet channels/dialogue to a more consistent level.
 * - Voice Boost adds no gain here: [DialogueAudioProcessor] matches its own output loudness to its input.
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
        // No Voice Boost gain here: DialogueAudioProcessor keeps its output as loud as its input, so
        // lifting here too would only raise the background again.

        // ALWAYS attach the effect (even at 0 gain / everything off). Two reasons:
        //  1) 0 mB is inaudible, so there's no downside.
        //  2) On several Amlogic/MediaTek TV boxes the AudioTrack backing our explicit audio session id
        //     doesn't actually start on the first stream — playback sits in STATE_BUFFERING forever —
        //     until an AudioEffect is bound to that session. Attaching a LoudnessEnhancer here kicks it
        //     alive. This is exactly why toggling "Voice Boost" by hand used to "fix" a stuck first
        //     playback right after setup: it was the first thing that ever bound an effect to the
        //     session. Doing it unconditionally at startup means the user never has to.
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
