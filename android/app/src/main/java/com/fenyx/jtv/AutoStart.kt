package com.fenyx.jtv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fenyx.jtv.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings → "Open when the TV turns on" (issue #8), so JTV works like a TV for anyone.
 *
 * - [BOOT]: after the TV or box starts up (BOOT_COMPLETED, or QUICKBOOT_POWERON on some boxes).
 * - [WAKE]: also when it wakes from standby. Many TVs never restart when switched off with the
 *   remote, they only turn the screen off, so a small service listens for "screen on".
 *
 * Android 10+ only lets an app open itself like this with "Display over other apps" allowed;
 * older boxes need nothing.
 */
object AutoStart {
    const val OFF = "off"
    const val BOOT = "boot"
    const val WAKE = "wake"

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)

    /** Opens the "Display over other apps" screen; false when this TV has none (then: adb, see the setting). */
    fun askPermission(ctx: Context): Boolean = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    ).any { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }

    fun open(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    /** Runs the standby watcher only in [WAKE] mode. Call from the foreground or at boot. */
    fun sync(ctx: Context, mode: String) {
        val svc = Intent(ctx, WakeWatchService::class.java)
        runCatching { if (mode == WAKE) ContextCompat.startForegroundService(ctx, svc) else ctx.stopService(svc) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val mode = SettingsManager(app).openOnStartFlow.first()
                if (mode == AutoStart.OFF) return@launch
                AutoStart.sync(app, mode)
                // Some launchers come up a moment after boot and would cover JTV.
                delay(3_000)
                AutoStart.open(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Listens for the screen coming back on (standby → on, including HDMI-CEC power-on) and opens JTV. */
class WakeWatchService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ AutoStart.open(applicationContext) }, 1_500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Open when the TV turns on", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Open when the TV turns on")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 8, n, type)
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(screenOn)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private companion object { const val CHANNEL = "autostart" }
}
