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
import kotlinx.coroutines.launch

/** The two Settings actions for the backup (favourites and settings). */
class FavoritesBackupActions(val save: () -> Unit, val restore: () -> Unit)

/**
 * Save → Downloads/JTV-backup.json. Restore → read it back directly when Android allows,
 * else the system file picker; with no picker (many TV boxes), explain how to open the file with JTV
 * from a file manager (the app accepts it via an "open with" intent).
 */
@Composable
fun rememberFavoritesBackup(nameOf: (String) -> String?): FavoritesBackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    fun toast(@androidx.annotation.StringRes id: Int, vararg args: Any) = toast(context.getString(id, *args))

    fun doSave() = scope.launch {
        FavoritesBackup.save(context, nameOf)
            .onSuccess { toast(R.string.backup_saved_all, it) }
            .onFailure { toast(R.string.backup_save_failed, it.message ?: context.getString(R.string.backup_storage_error)) }
    }

    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doSave() else toast(R.string.backup_need_storage)
    }

    fun applyRestore(backup: FavoritesBackup.Backup) = scope.launch {
        toast(restoredMessage(context, FavoritesBackup.restore(context, backup, context.findActivity())))
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

/** "Restored 12 settings and 3 favourites", or that nothing changed. Shared with "open with JTV". */
internal fun restoredMessage(context: android.content.Context, r: FavoritesBackup.Restored): String = when {
    r.settings > 0 -> context.getString(R.string.backup_restored_all, r.settings, r.favouritesAdded)
    r.favouritesAdded > 0 -> context.resources.getQuantityString(R.plurals.backup_restored, r.favouritesAdded, r.favouritesAdded)
    else -> context.getString(R.string.backup_already_match)
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
