package com.fenyx.jtv.ui.settings

import com.fenyx.jtv.R
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.fenyx.jtv.data.FavoritesBackup
import com.fenyx.jtv.data.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The two Settings actions for the favourites backup. */
class FavoritesBackupActions(val save: () -> Unit, val restore: () -> Unit)

/**
 * Save → Downloads/JTV-favourites-backup.json. Restore → read it back directly when Android allows,
 * else the system file picker; with no picker (many TV boxes), explain how to open the file with JTV
 * from a file manager (the app accepts it via an "open with" intent).
 */
@Composable
fun rememberFavoritesBackup(nameOf: (String) -> String?): FavoritesBackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    fun toast(@androidx.annotation.StringRes id: Int, vararg args: Any) = toast(context.getString(id, *args))

    fun doSave() = scope.launch {
        val ids = settings.favoriteOrderFlow.first()
        if (ids.isEmpty()) { toast(R.string.backup_none); return@launch }
        FavoritesBackup.save(context, ids, ids.associateWith { nameOf(it) ?: "" }.filterValues { it.isNotEmpty() })
            .onSuccess { toast(context.resources.getQuantityString(R.plurals.backup_saved, ids.size, ids.size, it)) }
            .onFailure { toast(R.string.backup_save_failed, it.message ?: context.getString(R.string.backup_storage_error)) }
    }

    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doSave() else toast(R.string.backup_need_storage)
    }

    fun applyRestore(ids: List<String>) = scope.launch {
        if (ids.isEmpty()) { toast(R.string.backup_empty); return@launch }
        val added = FavoritesBackup.restore(context, ids)
        toast(if (added > 0) context.resources.getQuantityString(R.plurals.backup_restored, added, added) else context.getString(R.string.backup_already_match))
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            FavoritesBackup.readUri(context, uri)
                .onSuccess { applyRestore(it) }
                .onFailure { toast(R.string.backup_not_a_backup) }
        }
    }

    return remember {
        FavoritesBackupActions(
            save = {
                val granted = !FavoritesBackup.needsLegacyPermission ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                if (granted) doSave() else askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
            restore = {
                scope.launch {
                    val direct = FavoritesBackup.readFromDownloads(context)
                    if (direct != null) { applyRestore(direct); return@launch }
                    try {
                        pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    } catch (_: ActivityNotFoundException) {
                        toast(R.string.backup_open_with, FavoritesBackup.FILE_NAME)
                    }
                }
            },
        )
    }
}
