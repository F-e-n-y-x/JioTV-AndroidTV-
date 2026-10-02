package com.fenyx.jtv.ui.settings

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

    fun doSave() = scope.launch {
        val ids = settings.favoriteOrderFlow.first()
        if (ids.isEmpty()) { toast("You have no favourites to back up yet."); return@launch }
        FavoritesBackup.save(context, ids, ids.associateWith { nameOf(it) ?: "" }.filterValues { it.isNotEmpty() })
            .onSuccess { toast("Saved ${ids.size} ${if (ids.size == 1) "favourite" else "favourites"} to $it") }
            .onFailure { toast("Could not save the backup: ${it.message ?: "storage error"}") }
    }

    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doSave() else toast("JTV needs storage access to save the backup in Downloads.")
    }

    fun applyRestore(ids: List<String>) = scope.launch {
        if (ids.isEmpty()) { toast("That backup has no favourites."); return@launch }
        val added = FavoritesBackup.restore(context, ids)
        toast(if (added > 0) "Restored $added ${if (added == 1) "favourite" else "favourites"}." else "Your favourites already match the backup.")
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            FavoritesBackup.readUri(context, uri)
                .onSuccess { applyRestore(it) }
                .onFailure { toast("That file isn't a JTV favourites backup.") }
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
                        toast("Open Downloads/${FavoritesBackup.FILE_NAME} with JTV from a file manager to restore.")
                    }
                }
            },
        )
    }
}
