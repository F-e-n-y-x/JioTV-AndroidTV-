package com.fenyx.jtv.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the 12 h Jio access token fresh while nobody is watching. Without it, a TV left idle overnight
 * woke up with an expired token and had to refresh after the fact, which is the fragile path. An
 * inexact alarm (no WorkManager, which would pull in Room) wakes us every few hours; the receiver only
 * refreshes when the token is actually close to expiry, under the same single-refresh lock as playback.
 */
object TokenRefreshScheduler {
    private const val INTERVAL_MS = 3 * 60 * 60 * 1000L

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            pendingIntent(context)
        )
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TokenRefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class TokenRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // goAsync gives us ~10 s; a refresh is one HTTP call.
                withTimeoutOrNull(9_000) { JioApiClient.ensureFreshAccessToken(app) }
            } catch (e: Exception) {
                Log.w("TokenRefresh", "Background refresh failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
