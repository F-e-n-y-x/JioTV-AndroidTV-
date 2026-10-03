package com.fenyx.jtv

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.fenyx.jtv.theme.JioTVGoTVTheme

class MainActivity : ComponentActivity() {

    // Android 7–12: the in-app language choice (Settings → App language). 13+ uses the system per-app language.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.fenyx.jtv.i18n.AppLocale.overrideConfiguration(newBase)?.let { applyOverrideConfiguration(it) }
    }

    /** "Open with JTV" on a favourites backup file: restore it (merged into the current favourites). */
    private fun handleBackupIntent(intent: android.content.Intent?) {
        val uri = intent?.takeIf { it.action == android.content.Intent.ACTION_VIEW }?.data ?: return
        intent.data = null
        lifecycleScope.launch {
            com.fenyx.jtv.data.FavoritesBackup.readUri(this@MainActivity, uri)
                .onSuccess { ids ->
                    val added = com.fenyx.jtv.data.FavoritesBackup.restore(this@MainActivity, ids)
                    android.widget.Toast.makeText(this@MainActivity,
                        if (added > 0) resources.getQuantityString(R.plurals.backup_restore_restored, added, added)
                        else getString(R.string.backup_restore_already_match),
                        android.widget.Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.backup_restore_not_backup), android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    // ── Picture-in-picture (phone/tablet; see ui/player/Pip.kt) ──
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        com.fenyx.jtv.ui.player.Pip.onUserLeaveHint(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.fenyx.jtv.ui.player.Pip.onModeChanged(
            isInPictureInPictureMode,
            stopped = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),
        )
    }

    override fun onResume() {
        super.onResume()
        com.fenyx.jtv.ui.player.Pip.onResume()
    }

    override fun onStop() {
        // Stopped while in PiP = the PiP window was closed: the player session ends (Pip.closed).
        com.fenyx.jtv.ui.player.Pip.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        com.fenyx.jtv.ui.player.Pip.unregister(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleBackupIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleBackupIntent(intent)
        com.fenyx.jtv.ui.player.Pip.register(this)

        // Edge-to-edge + hidden system bars so the app's navy background fills the ENTIRE screen (incl.
        // any area the keyboard leaves) instead of the OS painting black at the edges. Removing this
        // made the black area at the bottom larger, so it's kept on.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // TV: no system bars at all. Phone/tablet: normal status + navigation bars (the app draws
        // edge-to-edge behind them and pads with safeDrawingPadding); the player hides them itself.
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        // No runtime storage-permission request: the app uses only app-scoped storage, so the prompt
        // was unnecessary and awkward to dismiss with a TV remote.
        val isLeanback = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val settings = com.fenyx.jtv.data.SettingsManager(applicationContext)
        setContent {
            // In picture-in-picture the window is tiny: keep the layouts of the full window (the player
            // draws only the video then), so nothing behind it switches to a phone layout and back.
            val liveConfig = androidx.compose.ui.platform.LocalConfiguration.current
            val fullConfig = androidx.compose.runtime.remember { arrayOfNulls<android.content.res.Configuration>(1) }
            val pipFrozen = com.fenyx.jtv.ui.player.Pip.inPip.value || com.fenyx.jtv.ui.player.Pip.entering.value
            val config = if (pipFrozen) fullConfig[0] ?: liveConfig
                         else liveConfig.also { fullConfig[0] = android.content.res.Configuration(it) }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides config) {
            val sw = config.smallestScreenWidthDp
            val form = when {
                isLeanback -> com.fenyx.jtv.theme.FormFactor.Tv
                sw < 600 -> com.fenyx.jtv.theme.FormFactor.Phone
                else -> com.fenyx.jtv.theme.FormFactor.Tablet
            }
            val themeMode by settings.themeModeFlow.collectAsState(initial = null)
            val accent by settings.accentFlow.collectAsState(initial = null)
            JioTVGoTVTheme(form = form, themeMode = themeMode, accent = accent) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
                ) {
                    MainNavigation()
                    // LAN sync: "Pair with <device>? Code 1234" when another device asks, on any screen.
                    com.fenyx.jtv.ui.settings.PairRequestHost()
                }
            }
            }
        }
    }
}
