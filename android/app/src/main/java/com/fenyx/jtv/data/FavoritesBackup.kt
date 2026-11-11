package com.fenyx.jtv.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup: favourites and settings in a small JSON file in the public Downloads folder, which survives
 * uninstalling the app. Restore reads it back (directly when Android allows it, otherwise through the file
 * picker or by opening the file with JTV from a file manager): favourites merge into the current ones,
 * settings replace the current ones. Older favourites-only files still restore.
 */
object FavoritesBackup {
    const val FILE_NAME = "JTV-backup.json"
    /** Name used up to beta 33 (favourites only); still read on restore. */
    private const val OLD_FILE_NAME = "JTV-favourites-backup.json"

    class Backup(val favourites: List<String>, val settings: JSONObject? = null, val appLanguage: String? = null)

    /** API 24–28 write to public Downloads with the legacy storage permission; 29+ use MediaStore. */
    val needsLegacyPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun toJson(ids: List<String>, names: Map<String, String>, settings: JSONObject, appLanguage: String): String = JSONObject().apply {
        put("app", "JTV")
        put("kind", "backup")
        put("version", 2)
        put("created", System.currentTimeMillis())
        put("favourites", JSONArray().apply {
            ids.forEach { id -> put(JSONObject().put("id", id).apply { names[id]?.let { put("name", it) } }) }
        })
        put("settings", settings)
        put("appLanguage", appLanguage)
    }.toString(2)

    /** Accepts the object form above, the older favourites-only object, or a bare JSON array of ids. */
    fun parse(text: String): Backup {
        val t = text.trim()
        if (t.startsWith("[")) return Backup(ids(JSONArray(t)))
        val o = JSONObject(t)
        require(o.has("favourites") || o.has("settings")) { "not a JTV backup" }
        return Backup(
            o.optJSONArray("favourites")?.let(::ids).orEmpty(),
            o.optJSONObject("settings"),
            if (o.has("appLanguage")) o.optString("appLanguage") else null,
        )
    }

    private fun ids(arr: JSONArray): List<String> = (0 until arr.length()).mapNotNull { i ->
        when (val v = arr.get(i)) {
            is JSONObject -> v.optString("id").takeIf { it.isNotBlank() }
            else -> v.toString().takeIf { it.isNotBlank() }
        }
    }.distinct()

    /** Writes the backup; returns a short human description of where it went. */
    suspend fun save(context: Context, nameOf: (String) -> String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val settings = SettingsManager(context)
                val ids = settings.favoriteOrderFlow.first()
                val names = ids.associateWith { nameOf(it) ?: "" }.filterValues { it.isNotEmpty() }
                val json = toJson(ids, names, settings.exportSettings(), com.fenyx.jtv.i18n.AppLocale.current(context)).toByteArray()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    // Replace our previous backup (only our own entries are visible/deletable here).
                    findOwnBackup(context, FILE_NAME)?.let { resolver.delete(it, null, null) }
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Could not create the backup file")
                    resolver.openOutputStream(uri, "w")!!.use { it.write(json) }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, FILE_NAME).writeBytes(json)
                }
                "Downloads/$FILE_NAME"
            }
        }

    /** Reads the backup from Downloads without a picker, when Android lets us (null when it doesn't). */
    suspend fun readFromDownloads(context: Context): Backup? = withContext(Dispatchers.IO) {
        listOf(FILE_NAME, OLD_FILE_NAME).firstNotNullOfOrNull { name ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = findOwnBackup(context, name) ?: return@runCatching null
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { parse(it.readText()) }
                } else {
                    @Suppress("DEPRECATION")
                    val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                    if (f.canRead()) parse(f.readText()) else null
                }
            }.getOrNull()
        }
    }

    suspend fun readUri(context: Context, uri: Uri): Result<Backup> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { parse(it.readText()) }
        }
    }

    /** Backup first (in its order), then any current favourites that the backup doesn't have. */
    fun merge(backup: List<String>, current: List<String>): List<String> =
        (backup + current).distinct()

    class Restored(val favouritesAdded: Int, val settings: Int)

    /**
     * Merges the backup's favourites into the stored ones and applies its settings. The app language
     * needs an [activity] (switching it recreates the screen), so it is applied last, and only with one.
     */
    suspend fun restore(context: Context, backup: Backup, activity: android.app.Activity? = null): Restored {
        val settings = SettingsManager(context)
        val current = settings.favoriteOrderFlow.first()
        val merged = merge(backup.favourites, current)
        settings.setFavoriteOrder(merged)
        val n = backup.settings?.let { settings.importSettings(it) } ?: 0
        if (activity != null) backup.appLanguage?.let { com.fenyx.jtv.i18n.AppLocale.set(activity, it) }
        return Restored(merged.size - current.size, n)
    }

    private fun findOwnBackup(context: Context, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Downloads._ID)
        val sel = "${MediaStore.Downloads.DISPLAY_NAME}=?"
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, sel, arrayOf(name), null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
            }
        return null
    }
}
