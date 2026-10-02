package com.fenyx.jtv.player

/**
 * Detects a "silent" stall: the player says it is playing (playWhenReady, STATE_READY, not paused by
 * the user) but the playback position stops moving, with no error and no buffering. The buffering
 * watchdog can't see this case because the state never leaves STATE_READY.
 *
 * Pure logic (no Android types) so it is unit-testable. The caller samples it every couple of seconds
 * and acts on the returned [Action]:
 *  - [Action.KICK_AUDIO] once no progress has been seen for [stallMs];
 *  - [Action.REPREPARE] if there is still no progress [escalateMs] after the kick.
 * Any progress, or any sample where playback isn't expected to be running, resets it.
 */
class PlaybackStallDetector(
    private val stallMs: Long = 8_000,
    private val escalateMs: Long = 6_000,
) {
    enum class Action { NONE, KICK_AUDIO, REPREPARE }

    private var lastPositionMs = Long.MIN_VALUE
    private var lastFrames = Long.MIN_VALUE
    private var lastChangeAtMs = 0L
    private var kickedAtMs = 0L
    private var stage = 0 // 0 = watching, 1 = kicked, 2 = re-prepare requested (wait for a reset)

    fun reset() {
        lastPositionMs = Long.MIN_VALUE
        lastFrames = Long.MIN_VALUE
        stage = 0
    }

    /**
     * @param shouldBePlaying playWhenReady && STATE_READY && not user-paused && no error on screen
     * @param positionMs a position that keeps increasing during normal playback (for live streams use
     *   the position in the period, not in the sliding window, which can stay constant)
     * @param framesRendered rendered video frame count, or -1 if unknown (e.g. tunneling)
     */
    fun onSample(nowMs: Long, shouldBePlaying: Boolean, positionMs: Long, framesRendered: Long): Action {
        if (!shouldBePlaying) { reset(); return Action.NONE }
        if (positionMs != lastPositionMs || framesRendered != lastFrames) {
            lastPositionMs = positionMs
            lastFrames = framesRendered
            lastChangeAtMs = nowMs
            stage = 0
            return Action.NONE
        }
        return when (stage) {
            0 -> if (nowMs - lastChangeAtMs >= stallMs) {
                stage = 1; kickedAtMs = nowMs; Action.KICK_AUDIO
            } else Action.NONE
            1 -> if (nowMs - kickedAtMs >= escalateMs) {
                stage = 2; Action.REPREPARE
            } else Action.NONE
            else -> Action.NONE
        }
    }
}
