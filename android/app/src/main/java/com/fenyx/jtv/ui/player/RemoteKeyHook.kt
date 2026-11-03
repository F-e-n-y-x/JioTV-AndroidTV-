package com.fenyx.jtv.ui.player

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import com.fenyx.jtv.data.KeySpec
import com.fenyx.jtv.data.RemoteAction
import com.fenyx.jtv.data.RemoteKeyMap
import com.fenyx.jtv.data.RemoteKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player's custom remote buttons, run before its own key handling.
 *
 * - A tap with an action runs it on key-down (no repeats).
 * - A button with a hold action waits: held [RemoteKeys.HOLD_MS] runs the hold action; released sooner
 *   runs the tap action, or, when the tap has none, re-sends the key so the player's own handling
 *   (zap, panels, seek) runs exactly as before.
 * - OK is not handled here (the player's hold-OK code asks [holdOkAction]); Back never is.
 *
 * [handle] returns null when the key is not ours, so the default handling runs.
 */
internal class RemoteKeyHook(private val scope: CoroutineScope, private val view: View) {
    var map: RemoteKeyMap = RemoteKeys.Default

    private var pending: KeySpec? = null
    private var pendingDown: KeyEvent? = null
    private var pendingJob: Job? = null
    private var holdFired = false
    private var replaying = false

    fun holdOkAction(): RemoteAction? = map.actionFor(KeyEvent.KEYCODE_DPAD_CENTER, 0, hold = true)

    /**
     * [lockedHoldAllowed]: hold slots of the arrows only apply on the plain picture (clean / banner);
     * in lists, panels and while seeking the arrows just move.
     */
    fun handle(ev: KeyEvent, lockedHoldAllowed: Boolean, perform: (RemoteAction) -> Unit): Boolean? {
        if (replaying) return null
        val code = ev.keyCode
        if (RemoteKeys.isOk(code) || RemoteKeys.isBack(code) || RemoteKeys.isSystem(code)) return null
        val tapSpec = KeySpec.of(code, ev.scanCode)
        val arrow = RemoteKeys.isArrow(code)
        val holdAct = if (arrow && !lockedHoldAllowed) null else map.actionFor(tapSpec.copy(hold = true))
        val tapAct = map.actionFor(tapSpec)
        val mine = pending?.sameButton(tapSpec) == true

        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (mine) return true // repeats while waiting for the hold
                if (holdAct != null && ev.repeatCount == 0) {
                    pendingJob?.cancel()
                    pending = tapSpec
                    pendingDown = KeyEvent(ev)
                    holdFired = false
                    pendingJob = scope.launch {
                        delay(RemoteKeys.HOLD_MS)
                        holdFired = true
                        perform(holdAct)
                    }
                    return true
                }
                if (tapAct != null) {
                    if (ev.repeatCount == 0) perform(tapAct)
                    return true
                }
                return null
            }
            KeyEvent.ACTION_UP -> {
                if (mine) {
                    pendingJob?.cancel()
                    val down = pendingDown
                    val fired = holdFired
                    pending = null
                    pendingDown = null
                    if (!fired) {
                        if (tapAct != null) perform(tapAct)
                        else if (down != null) replay(down)
                    }
                    return true
                }
                return if (tapAct != null) true else null
            }
        }
        return null
    }

    /** A short press of a button that also has a hold slot: hand the key-down back to the player. */
    private fun replay(down: KeyEvent) {
        replaying = true
        try {
            view.dispatchKeyEvent(KeyEvent.changeTimeRepeat(down, SystemClock.uptimeMillis(), 0))
        } finally {
            replaying = false
        }
    }
}
