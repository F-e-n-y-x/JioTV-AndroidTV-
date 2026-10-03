package com.fenyx.jtv.ui.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.fenyx.jtv.R
import com.fenyx.jtv.data.SettingsManager
import kotlinx.coroutines.flow.drop

/**
 * Picture-in-picture for phone and tablet (never TV). The player says when it may go into PiP
 * ([PipBridge]: a channel is playing and the setting is on); MainActivity enters it when the user
 * leaves (API 31+: auto-enter; API 26–30: onUserLeaveHint) and reports the mode back in [inPip].
 *
 * While in PiP the player draws only the video. The window offers Previous channel · Pause/Play ·
 * Next channel as plain RemoteActions, delivered by a not-exported broadcast to the activity (no
 * media-session dependency). Closing the PiP window ends the player session.
 */
object Pip {
    private const val ACTION = "com.fenyx.jtv.PIP_CONTROL"
    private const val EXTRA = "c"
    private const val PREV = 1
    private const val TOGGLE = 2
    private const val NEXT = 3

    /** True while the activity is in picture-in-picture mode (Compose state). */
    val inPip = mutableStateOf(false)
    /** Entering was requested (user left the app while eligible): layouts freeze until the mode settles. */
    val entering = mutableStateOf(false)
    /** Bumped when the user closes the PiP window: the player ends its session. */
    val closed = mutableIntStateOf(0)

    internal interface Controls {
        fun previous()
        fun toggle()
        fun next()
    }

    private var eligible = false
    private var playing = false
    private var controls: Controls? = null
    private var videoRect: Rect? = null
    private val main = Handler(Looper.getMainLooper())

    fun supported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    // ── Player side ──

    internal fun setState(activity: Activity?, eligible: Boolean, playing: Boolean, controls: Controls?) {
        this.eligible = eligible
        this.playing = playing
        this.controls = controls
        update(activity)
    }

    private var pendingRect: Runnable? = null

    /** The video's bounds in the window (the PiP animation's source). Applied at most every 250 ms. */
    internal fun setVideoRect(activity: Activity?, r: Rect) {
        if (r == videoRect) return
        videoRect = r
        pendingRect?.let { main.removeCallbacks(it) }
        pendingRect = Runnable { update(activity) }.also { main.postDelayed(it, 250) }
    }

    private fun params(activity: Activity): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions(activity))
        videoRect?.takeIf { it.width() > 0 && it.height() > 0 }?.let { b.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(eligible)
            b.setSeamlessResizeEnabled(false) // video: no cross-fade while resizing
        }
        return b.build()
    }

    private fun actions(activity: Activity): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        fun action(code: Int, icon: Int, title: String): RemoteAction {
            val intent = Intent(ACTION).setPackage(activity.packageName).putExtra(EXTRA, code)
            val pi = PendingIntent.getBroadcast(
                activity, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return RemoteAction(Icon.createWithResource(activity, icon), title, title, pi)
        }
        return listOf(
            action(PREV, R.drawable.ic_pip_previous, activity.getString(R.string.player_previous_channel)),
            if (playing) action(TOGGLE, R.drawable.ic_pip_pause, activity.getString(R.string.player_pause)) else action(TOGGLE, R.drawable.ic_pip_play, activity.getString(R.string.player_play)),
            action(NEXT, R.drawable.ic_pip_next, activity.getString(R.string.player_next_channel)),
        )
    }

    private fun update(activity: Activity?) {
        val a = activity ?: return
        if (!supported(a) || a.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { params(a)?.let { a.setPictureInPictureParams(it) } }
        }
    }

    // ── Activity side ──

    /** API 26–30 (31+ enters by itself): the user pressed Home or switched apps. */
    fun onUserLeaveHint(activity: Activity) {
        if (!eligible || !supported(activity)) return
        entering.value = true
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            val ok = runCatching { params(activity)?.let { activity.enterPictureInPictureMode(it) } ?: false }.getOrDefault(false)
            if (!ok) entering.value = false
        }
    }

    fun onModeChanged(inPictureInPicture: Boolean, stopped: Boolean) {
        entering.value = false
        val was = inPip.value
        inPip.value = inPictureInPicture
        // Leaving PiP while the activity is stopped = the PiP window was closed (not expanded).
        if (was && !inPictureInPicture && stopped) closed.intValue++
    }

    fun onResume() { entering.value = false }

    /** Stopped while in PiP: the window was dismissed (some versions report the mode change later). */
    fun onStop() {
        entering.value = false
        if (inPip.value) {
            inPip.value = false
            closed.intValue++
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = controls ?: return
            when (intent.getIntExtra(EXTRA, 0)) {
                PREV -> c.previous()
                TOGGLE -> c.toggle()
                NEXT -> c.next()
            }
        }
    }

    fun register(activity: Activity) {
        if (!supported(activity)) return
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister(activity: Activity) {
        if (!supported(activity)) return
        runCatching { activity.unregisterReceiver(receiver) }
    }
}

/**
 * The player's half of PiP: tells [Pip] when it may enter (not TV, setting on, a channel playing) and
 * wires the PiP buttons. [onClosed] runs when the PiP window is closed (the session ends).
 */
@Composable
internal fun PipBridge(
    activity: Activity?,
    enabled: Boolean,
    playing: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val supported = remember(context) { Pip.supported(context) }
    if (!supported) return
    val pref by remember(context) { SettingsManager(context).pipOnLeaveFlow }.collectAsState(initial = true)
    val prev by rememberUpdatedState(onPrevious)
    val toggle by rememberUpdatedState(onToggle)
    val next by rememberUpdatedState(onNext)
    val close by rememberUpdatedState(onClosed)
    val controls = remember {
        object : Pip.Controls {
            override fun previous() = prev()
            override fun toggle() = toggle()
            override fun next() = next()
        }
    }
    val eligible = enabled && pref && playing
    LaunchedEffect(activity, eligible, playing, enabled) {
        Pip.setState(activity, eligible = eligible, playing = playing, controls = if (enabled) controls else null)
    }
    DisposableEffect(activity) {
        onDispose { Pip.setState(activity, eligible = false, playing = false, controls = null) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { Pip.closed.intValue }.drop(1).collect { close() }
    }
}

/** Reports the video's window bounds for the PiP enter animation (phone/tablet only). */
internal fun Modifier.pipSourceRect(activity: Activity?, enabled: Boolean): Modifier =
    if (!enabled || activity == null) this else onGloballyPositioned { lc ->
        val b = lc.boundsInWindow()
        Pip.setVideoRect(activity, Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
    }
