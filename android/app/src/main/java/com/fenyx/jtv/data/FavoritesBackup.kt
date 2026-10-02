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
 * Favourites backup: a small JSON file in the public Downloads folder, which survives uninstalling the
 * app. Restore reads it back (directly when Android allows it, otherwise through the file picker or by
 * opening the file with JTV from a file manager) and merges it into the current favourites.
 */
object FavoritesBackup {
    const val FILE_NAME = "JTV-favourites-backup.json"

    /** API 24–28 write to public Downloads with the legacy storage permission; 29+ use MediaStore. */
    val needsLegacyPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun toJson(ids: List<String>, names: Map<String, String>): String = JSONObject().apply {
        put("app", "JTV")
        put("kind", "favourites")
        put("version", 1)
        put("created", System.currentTimeMillis())
        put("favourites", JSONArray().apply {
            ids.forEach { id -> put(JSONObject().put("id", id).apply { names[id]?.let { put("name", it) } }) }
        })
    }.toString(2)

    /** Channel ids in backup order. Accepts the object form above or a bare JSON array of ids. */
    fun parse(text: String): List<String> {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("favourites")
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.get(i)) {
                is JSONObject -> v.optString("id").takeIf { it.isNotBlank() }
                else -> v.toString().takeIf { it.isNotBlank() }
            }
        }.distinct()
    }

    /** Writes the backup; returns a short human description of where it went. */
    suspend fun save(context: Context, ids: List<String>, names: Map<String, String>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = toJson(ids, names).toByteArray()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    // Replace our previous backup (only our own entries are visible/deletable here).
                    findOwnBackup(context)?.let { resolver.delete(it, null, null) }
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
    suspend fun readFromDownloads(context: Context): List<String>? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findOwnBackup(context) ?: return@runCatching null
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { parse(it.readText()) }
            } else {
                @Suppress("DEPRECATION")
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
                if (f.canRead()) parse(f.readText()) else null
            }
        }.getOrNull()
    }

    suspend fun readUri(context: Context, uri: Uri): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { parse(it.readText()) }
        }
    }

    /** Backup first (in its order), then any current favourites that the backup doesn't have. */
    fun merge(backup: List<String>, current: List<String>): List<String> =
        (backup + current).distinct()

    /** Merges [ids] into the stored favourites; returns how many were added. */
    suspend fun restore(context: Context, ids: List<String>): Int {
        val settings = SettingsManager(context)
        val current = settings.favoriteOrderFlow.first()
        val merged = merge(ids, current)
        settings.setFavoriteOrder(merged)
        return merged.size - current.size
    }

    private fun findOwnBackup(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Downloads._ID)
        val sel = "${MediaStore.Downloads.DISPLAY_NAME}=?"
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, sel, arrayOf(FILE_NAME), null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
            }
        return null
    }
}
