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
import kotlinx.coroutines.launch

// Sentinel for "the persisted setup mode hasn't loaded yet" so we don't flash the chooser on launch.
private const val SETUP_LOADING = "__loading__"

@Composable
private fun LoadingScreen() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize().background(com.fenyx.jtv.theme.Jtv.colors.bg),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = com.fenyx.jtv.theme.Jtv.colors.acc)
    }
}

/** Phone: wraps a top-level screen with the bottom tab bar. Other form factors: content as-is. */
@Composable
private fun TopLevel(
    tab: com.fenyx.jtv.ui.main.PhoneTab,
    onTab: (com.fenyx.jtv.ui.main.PhoneTab) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    if (com.fenyx.jtv.theme.Jtv.form == com.fenyx.jtv.theme.FormFactor.Phone) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { content(Modifier) }
            com.fenyx.jtv.ui.main.PhoneBottomBar(tab, onTab)
        }
    } else content(Modifier.safeDrawingPadding())
}

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val settingsManager = androidx.compose.runtime.remember { SettingsManager(context) }
    val authData by settingsManager.authDataFlow.collectAsState(initial = null)

    // Debug "Design Lab" builds open straight into the lab on phones/tablets (no leanback), where the
    // v1 TV home isn't meant to be used. On TV the lab is an extra sidebar entry instead.
    val isTvDevice = androidx.compose.runtime.remember {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    val backStack = rememberNavBackStack(Main)
    val mainViewModel: MainViewModel = viewModel()
    // Phone tabs replace each other on top of Live TV (Back from a tab returns to Live TV).
    val onTab: (com.fenyx.jtv.ui.main.PhoneTab) -> Unit = { tab ->
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        when (tab) {
            com.fenyx.jtv.ui.main.PhoneTab.Live -> {}
            com.fenyx.jtv.ui.main.PhoneTab.Guide -> backStack.add(Guide)
            com.fenyx.jtv.ui.main.PhoneTab.Search -> backStack.add(Search)
            com.fenyx.jtv.ui.main.PhoneTab.Settings -> backStack.add(Settings)
        }
    }

    val autoplayLastChannel by settingsManager.autoplayLastChannelFlow.collectAsState(initial = null)
    val lastChannelId by settingsManager.lastChannelIdFlow.collectAsState(initial = null)
    val lastChannelGroup by settingsManager.lastChannelGroupFlow.collectAsState(initial = null)
    val hasAutoPlayed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Use the COLLAPSED (display) list the player actually navigates, and wait for it to be populated
    // before autoplaying — otherwise autoplay fired against the raw list (wrong index) or before the
    // collapse ran (0 channels), which is what caused the black "0 channels / Loading…" screen on boot.
    val allChannels by mainViewModel.displayChannels.collectAsState()
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

    // Onboarding router. When not logged in, pick the setup flow from the chosen method:
    //  - not chosen yet (first boot) -> Setup chooser
    //  - "phone" -> OTP LoginScreen (with a way back to the chooser)
    //  - "server" -> ServerSetupScreen (pull shared credentials)
    val setupMode by settingsManager.setupModeFlow.collectAsState(initial = SETUP_LOADING)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    if (authData == null) {
        when (setupMode) {
            SETUP_LOADING -> LoadingScreen()
            null -> com.fenyx.jtv.ui.setup.SetupScreen(
                onChoosePhone = { scope.launch { settingsManager.setSetupMode("phone") } },
                onChooseServer = { scope.launch { settingsManager.setSetupMode("server") } },
                onChooseJtv = { scope.launch { settingsManager.setSetupMode("jtv") } },
                modifier = Modifier.safeDrawingPadding()
            )
            // NOTE: no safeDrawingPadding here — the setup screen paints its own full-bleed background
            // and top-anchors its content, so the page must NOT get the IME inset (that's what pushed
            // the whole screen up when the keyboard opened).
            "server" -> com.fenyx.jtv.ui.setup.ServerSetupScreen(
                onBack = { scope.launch { settingsManager.setSetupMode(null) } }
            )
            "jtv" -> com.fenyx.jtv.ui.setup.ServerSetupScreen(
                jtvMode = true,
                onBack = { scope.launch { settingsManager.setSetupMode(null) } }
            )
            else -> LoginScreen(
                onChangeMethod = { scope.launch { settingsManager.setSetupMode(null) } },
                modifier = Modifier.safeDrawingPadding()
            )
        }
    } else if (autoplayLastChannel == null || (autoplayLastChannel == true && !hasAutoPlayed.value)) {
        // Show blank loading screen while evaluating autoplay
        LoadingScreen()
    } else {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider =
                entryProvider {
                entry<Main> {
                  TopLevel(com.fenyx.jtv.ui.main.PhoneTab.Live, onTab) { m ->
                    MainScreen(
                        onChannelClick = { index, group ->
                            backStack.add(Player(channelIndex = index, group = group))
                        },
                        onSettingsClick = {
                            backStack.add(Settings)
                        },
                        onSearchClick = {
                            backStack.add(Search)
                        },
                        onGuideClick = { backStack.add(Guide) },
                        viewModel = mainViewModel,
                        modifier = m
                    )
                  }
                }
                entry<Lab> {
                    val lab = DesignLabHook.content
                    if (lab != null) {
                        lab(
                            mainViewModel,
                            { index, group -> backStack.add(Player(channelIndex = index, group = group)) },
                            { if (backStack.size > 1) backStack.removeLastOrNull() else backStack.add(Main) }
                        )
                    }
                }
                entry<Guide> {
                  TopLevel(com.fenyx.jtv.ui.main.PhoneTab.Guide, onTab) { m ->
                    com.fenyx.jtv.ui.guide.GuideScreen(
                        viewModel = mainViewModel,
                        onPlay = { index, group -> backStack.add(Player(channelIndex = index, group = group)) },
                        onOpenSettings = { onTab(com.fenyx.jtv.ui.main.PhoneTab.Settings) },
                        modifier = m
                    )
                  }
                }
                entry<Search> {
                  TopLevel(com.fenyx.jtv.ui.main.PhoneTab.Search, onTab) { m ->
                    com.fenyx.jtv.ui.search.SearchScreen(
                        viewModel = mainViewModel,
                        onChannelClick = { index, group ->
                            backStack.add(Player(channelIndex = index, group = group))
                        },
                        modifier = m
                    )
                  }
                }
                entry<Settings> {
                  TopLevel(com.fenyx.jtv.ui.main.PhoneTab.Settings, onTab) { m ->
                    SettingsScreen(
                        modifier = m,
                        mainViewModel = mainViewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                  }
                }
                entry<Player> { playerArgs ->
                    val groups by mainViewModel.groups.collectAsState()
                    // Reactive (not a one-shot snapshot) so if the player is opened while the collapsed
                    // list is still being built, it recomposes and fills in — no more Settings-and-back
                    // workaround to recover from a "0 channels" launch.
                    val allChannels by mainViewModel.displayChannels.collectAsState()

                    // Memoize the expensive per-group grouping + index lookups so they run once per
                    // channel-list change, not on every recomposition (this was a real source of
                    // player-open / settings-open lag: it re-filtered all ~1300 channels for every group).
                    // Favorites is offered as the first category inside the player too, so CH+/CH- and
                    // the category list follow the user's own favorites order. The order is snapshotted
                    // per channel-list change (NOT keyed on favorites): toggling a favorite mid-playback
                    // would otherwise reshuffle the zapping list under the current index and jump channels.
                    val channels = androidx.compose.runtime.remember(playerArgs.group, allChannels) {
                        if (playerArgs.group != null) mainViewModel.getChannelsByGroup(playerArgs.group)
                        else allChannels
                    }
                    val playerGroups = androidx.compose.runtime.remember(groups, allChannels) {
                        if (mainViewModel.favoriteOrder.value.isEmpty()) groups
                        else listOf(MainViewModel.GROUP_FAVORITES) + groups
                    }
                    val allChannelsByGroup = androidx.compose.runtime.remember(playerGroups, allChannels) {
                        playerGroups.associateWith { group -> mainViewModel.getChannelsByGroup(group) }
                    }
                    val filteredIndex = androidx.compose.runtime.remember(channels, allChannels, playerArgs.channelIndex) {
                        val targetChannel = allChannels.getOrNull(playerArgs.channelIndex)
                        if (targetChannel != null) channels.indexOf(targetChannel).coerceAtLeast(0) else 0
                    }

                    TvPlayerScreen(
                        channels = channels,
                        initialIndex = filteredIndex,
                        allChannelsByGroup = allChannelsByGroup,
                        groups = playerGroups,
                        onBack = { backStack.removeLastOrNull() },
                        onSettings = {
                            backStack.add(Settings)
                        },
                        variantsFor = { id -> mainViewModel.variantsFor(id) },
                        initialGroup = playerArgs.group
                    )
                }
            },
        )
    }
}
