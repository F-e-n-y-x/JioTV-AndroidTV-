package com.fenyx.jtv

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.platform.LocalContext
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.ui.login.LoginScreen
import com.fenyx.jtv.ui.main.MainScreen
import com.fenyx.jtv.ui.main.MainViewModel
import com.fenyx.jtv.ui.settings.SettingsScreen
import com.fenyx.jtv.ui.player.TvPlayerScreen

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val settingsManager = androidx.compose.runtime.remember { SettingsManager(context) }
    val authData by settingsManager.authDataFlow.collectAsState(initial = null)

    val backStack = rememberNavBackStack(Main)
    val mainViewModel: MainViewModel = viewModel()

    val autoplayLastChannel by settingsManager.autoplayLastChannelFlow.collectAsState(initial = null)
    val lastChannelId by settingsManager.lastChannelIdFlow.collectAsState(initial = null)
    val lastChannelGroup by settingsManager.lastChannelGroupFlow.collectAsState(initial = null)
    val hasAutoPlayed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val allChannels by mainViewModel.channels.collectAsState()
    val isLoading by mainViewModel.isLoading.collectAsState()

    androidx.compose.runtime.LaunchedEffect(autoplayLastChannel, lastChannelId, allChannels, isLoading) {
        when (autoplayLastChannel) {
            true -> {
                when {
                    hasAutoPlayed.value -> { /* already handled */ }
                    // No channel was ever saved (or auth was cleared) -> don't hang on the spinner,
                    // just show the home screen.
                    lastChannelId == null -> hasAutoPlayed.value = true
                    // Kick off the channel load (served from cache when fresh, so this is fast).
                    allChannels.isEmpty() && !isLoading -> mainViewModel.fetchChannels()
                    allChannels.isNotEmpty() -> {
                        hasAutoPlayed.value = true
                        val channelIndex = allChannels.indexOfFirst { it.id == lastChannelId }
                        if (channelIndex != -1) {
                            backStack.add(Player(channelIndex = channelIndex, group = lastChannelGroup))
                        }
                    }
                    // Load finished but produced no channels (e.g. offline first run) -> fall through
                    // to the home screen instead of spinning forever.
                    !isLoading -> hasAutoPlayed.value = true
                }
            }
            false -> hasAutoPlayed.value = true
            else -> { /* null: setting not loaded yet */ }
        }
    }

    if (authData == null) {
        LoginScreen(modifier = Modifier.safeDrawingPadding())
    } else if (autoplayLastChannel == null || (autoplayLastChannel == true && !hasAutoPlayed.value)) {
        // Show blank loading screen while evaluating autoplay
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        }
    } else {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider =
                entryProvider {
                entry<Main> {
                    MainScreen(
                        onChannelClick = { index, group ->
                            backStack.add(Player(channelIndex = index, group = group))
                        },
                        onSettingsClick = {
                            backStack.add(Settings)
                        },
                        viewModel = mainViewModel,
                        modifier = Modifier.safeDrawingPadding()
                    )
                }
                entry<Settings> {
                    SettingsScreen(
                        modifier = Modifier.safeDrawingPadding(),
                        mainViewModel = mainViewModel
                    )
                }
                entry<Player> { playerArgs ->
                    val groups by mainViewModel.groups.collectAsState()

                    val channels = if (playerArgs.group != null) {
                        mainViewModel.getChannelsByGroup(playerArgs.group)
                    } else {
                        mainViewModel.getAllChannels()
                    }

                    // Build a map of all channels by group for the category switcher
                    val allChannelsByGroup = groups.associateWith { group ->
                        mainViewModel.getChannelsByGroup(group)
                    }

                    // Map channelIndex from allChannels to filtered list
                    val allChannels = mainViewModel.getAllChannels()
                    val targetChannel = allChannels.getOrNull(playerArgs.channelIndex)
                    val filteredIndex = if (targetChannel != null) {
                        channels.indexOf(targetChannel).coerceAtLeast(0)
                    } else {
                        0
                    }

                    TvPlayerScreen(
                        channels = channels,
                        initialIndex = filteredIndex,
                        allChannelsByGroup = allChannelsByGroup,
                        groups = groups,
                        onBack = { backStack.removeLastOrNull() },
                        onSettings = {
                            backStack.add(Settings)
                        }
                    )
                }
            },
        )
    }
}
