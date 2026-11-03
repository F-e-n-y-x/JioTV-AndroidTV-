package com.fenyx.jtv.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android TV home screen "Watch Next" row: one "continue watching" entry for the last live channel.
 * TV boxes only (leanback, Android 8+). Many boxes have no TV provider, so every provider call is
 * wrapped and a failure is silently ignored.
 */
object WatchNext {
    /** Launch action of the entry's intent; Navigation plays [EXTRA_CHANNEL_ID] when it sees it. */
    const val ACTION_PLAY = "com.fenyx.jtv.action.PLAY_CHANNEL"
    const val EXTRA_CHANNEL_ID = "com.fenyx.jtv.extra.CHANNEL_ID"

    private const val PREFS = "watch_next"
    private const val KEY_ID = "program_id"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun supported(ctx: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun playIntent(ctx: Context, channelId: String): Intent =
        Intent(ACTION_PLAY).setClassName(ctx.packageName, "com.fenyx.jtv.MainActivity")
            .putExtra(EXTRA_CHANNEL_ID, channelId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Publishes (or refreshes) the entry for [channel]. Safe on any device; runs on IO. */
    // tvprovider 1.1.0 marks the inherited builder setters @RestrictTo by mistake (false positive).
    @android.annotation.SuppressLint("RestrictedApi")
    suspend fun publish(ctx: Context, channel: Channel) = withContext(Dispatchers.IO) {
        if (!supported(ctx)) return@withContext
        runCatching {
            val program = WatchNextProgram.Builder()
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setLive(true)
                .setTitle(channel.name)
                .setInternalProviderId(channel.id)
                .setIntentUri(Uri.parse(playIntent(ctx, channel.id).toUri(Intent.URI_INTENT_SCHEME)))
                .apply {
                    if (channel.logoUrl.isNotBlank()) {
                        setPosterArtUri(Uri.parse(channel.logoUrl))
                        setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    }
                }
                .build()
            val values = program.toContentValues()
            val cr = ctx.contentResolver
            val old = prefs(ctx).getLong(KEY_ID, -1L)
            val updated = old >= 0 && cr.update(TvContractCompat.buildWatchNextProgramUri(old), values, null, null) > 0
            if (!updated) {
                cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)?.let {
                    prefs(ctx).edit().putLong(KEY_ID, ContentUris.parseId(it)).apply()
                }
            }
        }
    }

    /** Removes the entry (sign-out). */
    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        val old = prefs(ctx).getLong(KEY_ID, -1L)
        if (old < 0) return@withContext
        if (supported(ctx)) runCatching { ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(old), null, null) }
        prefs(ctx).edit().remove(KEY_ID).apply()
    }
}
