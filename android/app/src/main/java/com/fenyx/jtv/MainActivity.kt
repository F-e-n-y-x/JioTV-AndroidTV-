package com.fenyx.jtv

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            val sw = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp
            val form = when {
                isLeanback -> com.fenyx.jtv.theme.FormFactor.Tv
                sw < 600 -> com.fenyx.jtv.theme.FormFactor.Phone
                else -> com.fenyx.jtv.theme.FormFactor.Tablet
            }
            val themeMode by settings.themeModeFlow.collectAsState(initial = null)
            JioTVGoTVTheme(form = form, themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
                ) {
                    MainNavigation()
                }
            }
        }
    }
}
