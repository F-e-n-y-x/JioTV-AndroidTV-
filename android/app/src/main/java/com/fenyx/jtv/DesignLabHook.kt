package com.fenyx.jtv

import androidx.compose.runtime.Composable
import com.fenyx.jtv.ui.main.MainViewModel

/**
 * Extension point for the v2 Design Lab (prototype screens). The lab lives only in the `debug` source
 * set and registers itself at startup, so release APKs contain no lab code. `content` is null in release.
 */
object DesignLabHook {
    var content: (@Composable (viewModel: MainViewModel, onPlay: (channelIndex: Int, group: String?) -> Unit, onExit: () -> Unit) -> Unit)? = null
}
