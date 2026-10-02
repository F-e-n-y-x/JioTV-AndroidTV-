package com.fenyx.jtv.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app language: "Same as device", English or Hindi (Settings → App language).
 *
 * No AppCompat (it would add ~1 MB to an app that has none of its widgets). Instead:
 *  - Android 13+: the framework per-app language ([LocaleManager]); the system stores the choice,
 *    lists it in system Settings (res/xml/locales_config.xml) and recreates the activity itself.
 *  - Android 7–12: the choice is kept in a small SharedPreferences file and applied as an override
 *    configuration on the activity ([MainActivity.attachBaseContext]) and on the application
 *    resources ([applyToApp]), so `context.getString` outside the UI follows it as well.
 */
object AppLocale {
    /** Follow the device language. */
    const val SYSTEM = ""
    /** Languages the app ships, in the order the setting lists them. */
    val SUPPORTED = listOf(SYSTEM, "en", "hi")

    private const val PREFS = "jtv_app_locale"
    private const val KEY = "tag"

    /** "" (device), "en" or "hi". */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (list == null || list.isEmpty) SYSTEM else normalise(list[0].language)
        }
        return normalise(prefs(context).getString(KEY, SYSTEM) ?: SYSTEM)
    }

    /** Switches the app language now. On Android 13+ the system recreates the activity; below, we do. */
    fun set(activity: Activity, tag: String) {
        val t = normalise(tag)
        if (t == current(activity)) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (t == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(t)
        } else {
            prefs(activity).edit().putString(KEY, t).commit()
            applyToApp(activity.applicationContext)
            activity.recreate()
        }
    }

    /**
     * Android 7–12: the configuration to pass to `applyOverrideConfiguration` for the chosen language,
     * or null when the device language is used (or on 13+, where the framework does it).
     */
    fun overrideConfiguration(base: Context): Configuration? {
        if (Build.VERSION.SDK_INT >= 33) return null
        val t = current(base)
        if (t == SYSTEM) return null
        return Configuration().apply {
            fontScale = 0f // "undefined": keep the user's font size (Configuration() defaults it to 1)
            setLocale(Locale.forLanguageTag(t))
        }
    }

    /** Android 7–12: points the application resources (and the default Locale) at the chosen language. */
    @Suppress("DEPRECATION")
    fun applyToApp(app: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val t = current(app)
        val locale = if (t == SYSTEM) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(t)
        Locale.setDefault(locale)
        val res = app.resources
        val cfg = Configuration(res.configuration).apply { setLocale(locale) }
        res.updateConfiguration(cfg, res.displayMetrics)
    }

    private fun normalise(tag: String): String = when (tag.substringBefore('-').lowercase()) {
        "en" -> "en"
        "hi" -> "hi"
        else -> SYSTEM
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
