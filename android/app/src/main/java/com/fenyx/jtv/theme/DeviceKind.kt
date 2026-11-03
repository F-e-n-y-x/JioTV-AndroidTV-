package com.fenyx.jtv.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * TV, phone or tablet: the user's choice (Settings → Device type, or the first-start question), else
 * a guess. Plain SharedPreferences so it can be read synchronously before the first frame.
 *
 * Many cheap Android TV boxes (e.g. MXQ Pro, Allwinner) don't declare the Android TV "leanback"
 * feature, so the guess also counts TV UI mode and no touchscreen (issue #6).
 */
object DeviceKind {
    const val AUTO = "auto"
    const val TV = "tv"
    const val PHONE = "phone"
    const val TABLET = "tablet"

    private const val PREFS = "device"
    private const val KEY = "device_type"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored choice, or null when the user hasn't been asked yet. */
    fun chosen(ctx: Context): String? = prefs(ctx).getString(KEY, null)

    fun set(ctx: Context, type: String) = prefs(ctx).edit().putString(KEY, type).apply()

    /** Android TV for sure: the first-start question isn't needed. */
    fun certainTv(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun guessTv(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return certainTv(ctx) ||
            ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** TV layout and behaviour (no system bars, no picture-in-picture, remote-first). */
    fun isTv(ctx: Context): Boolean = when (chosen(ctx)) {
        TV -> true
        PHONE, TABLET -> false
        else -> guessTv(ctx)
    }

    fun form(ctx: Context, smallestWidthDp: Int): FormFactor = when (chosen(ctx)) {
        TV -> FormFactor.Tv
        PHONE -> FormFactor.Phone
        TABLET -> FormFactor.Tablet
        else -> if (guessTv(ctx)) FormFactor.Tv else if (smallestWidthDp < 600) FormFactor.Phone else FormFactor.Tablet
    }
}
