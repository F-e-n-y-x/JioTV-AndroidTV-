package com.fenyx.jtv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.platform.LocalContext
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.login.LoginScreen
import com.fenyx.jtv.ui.main.MainScreen
import com.fenyx.jtv.ui.main.MainViewModel
import com.fenyx.jtv.ui.main.PhoneTab
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

/**
 * Phone/tablet player session. The player lives OUTSIDE NavDisplay (in [PlayerHost], drawn above it)
 * so it can shrink into the in-app mini player and keep playing while the user browses. TV keeps the
 * [Player] nav entry instead (Back leaves the player there, as before).
 */
@Immutable
private data class PlayerSession(
    val channelIndex: Int,
    val group: String?,
    val mini: Boolean = false,
    /** Bumped on every open, so an already-running player retunes instead of being rebuilt. */
    val token: Int = 0,
)

private val PlayerSessionSaver = Saver<MutableState<PlayerSession?>, Any>(
    save = { st ->
        val s = st.value
        if (s == null) arrayListOf<Any?>() else arrayListOf<Any?>(s.channelIndex, s.group, s.mini, s.token)
    },
    restore = { v ->
        val l = v as List<*>
        mutableStateOf(
            if (l.size < 4) null
            else PlayerSession(l[0] as Int, l[1] as String?, l[2] as Boolean, l[3] as Int)
        )
    },
)

/**
 * Bottom space the phone mini bar takes (0 when there is none). A State read only by [TopLevel], so a
 * minimise / expand re-lays out the tab screen and nothing else.
 */
/** Non-zero while the player is minimised (phone bar height). Screens read it to keep content clear. */
val LocalMiniBarInset = staticCompositionLocalOf<State<Dp>> { mutableStateOf(0.dp) }

/** Phone: a top-level screen above the bottom tab bar. Tablet: beside the nav rail. TV: as-is. */
@Composable
private fun TopLevel(
    tab: PhoneTab,
    onTab: (PhoneTab) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    when (if (Jtv.isPhoneLandscape) FormFactor.Tablet else Jtv.form) {
        FormFactor.Phone -> {
            val inset = LocalMiniBarInset.current
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // Room for the docked mini bar, read in layout only (no recomposition on toggle).
                Box(Modifier.weight(1f).miniInsetPadding(inset)) { content(Modifier) }
                com.fenyx.jtv.ui.main.PhoneBottomBar(tab, onTab)
            }
        }
        FormFactor.Tablet -> Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            com.fenyx.jtv.ui.main.TabletNavRail(tab, onTab)
            Box(Modifier.weight(1f)) { content(Modifier) }
        }
        FormFactor.Tv -> content(Modifier.safeDrawingPadding())
    }
}

/** Bottom padding from a State, applied in the layout phase. */
private fun Modifier.miniInsetPadding(inset: State<Dp>): Modifier = this.layout { measurable, constraints ->
    val pad = inset.value.roundToPx()
    val p = measurable.measure(constraints.offset(vertical = -pad))
    layout(p.width, constraints.constrainHeight(p.height + pad)) { p.place(0, 0) }
}

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val settingsManager = androidx.compose.runtime.remember { SettingsManager(context) }
    val authData by settingsManager.authDataFlow.collectAsState(initial = null)

    val backStack = rememberNavBackStack(Main)
    val mainViewModel: MainViewModel = viewModel()
    // Phone tabs replace each other on top of Live TV (Back from a tab returns to Live TV).
    val onTab: (PhoneTab) -> Unit = { tab ->
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        when (tab) {
            PhoneTab.Live -> {}
            PhoneTab.Guide -> backStack.add(Guide)
            PhoneTab.Search -> backStack.add(Search)
            PhoneTab.Settings -> backStack.add(Settings)
        }
    }

    // Phone/tablet player session (null = no player). Held as a State and never read here, so opening,
    // minimising or expanding recomposes only the host (and TopLevel's inset), not the nav screens.
    val isTv = Jtv.isTv
    val session = rememberSaveable(saver = PlayerSessionSaver) { mutableStateOf<PlayerSession?>(null) }
    val miniBarInset = remember {
        derivedStateOf { if (session.value?.mini == true) com.fenyx.jtv.ui.player.MiniBarHeight else 0.dp }
    }

    /**
     * THE way to start playback of a channel: [index] into `displayChannels`, [group] the list it was
     * picked from (null = all). TV pushes the Player screen; phone/tablet open (or retune) the player
     * host in its expanded state — the same running player is reused if one is already up (mini).
     */
    fun openPlayer(index: Int, group: String?) {
        if (isTv) {
            backStack.add(Player(channelIndex = index, group = group))
        } else {
            val prev = session.value
            session.value = PlayerSession(index, group, mini = false, token = (prev?.token ?: 0) + 1)
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
                        if (channelIndex != -1 && session.value == null) {
                            openPlayer(channelIndex, lastChannelGroup)
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

    // ── LAN sync: "Play on TV" from a paired phone opens the player on that channel. ──
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.fenyx.jtv.sync.LanSync.playRequests.collect { channelId ->
            val index = mainViewModel.displayChannels.value.indexOfFirst { it.id == channelId }
            if (index >= 0) {
                // TV: replace a player already on top instead of stacking a second one.
                if (backStack.lastOrNull() is Player) backStack.removeAt(backStack.lastIndex)
                openPlayer(index, null)
            }
        }
    }

    // Signing out ends any player session (it must not reappear after the next sign-in).
    val hadAuth = remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(authData) {
        if (authData != null) hadAuth.value = true
        else if (hadAuth.value) { session.value = null; hadAuth.value = false }
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
    } else Box(Modifier.fillMaxSize()) {
      androidx.compose.runtime.CompositionLocalProvider(LocalMiniBarInset provides miniBarInset) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            // While the player is expanded it covers everything: skip drawing the screens under it
            // (read in the draw phase only, so this never recomposes them).
            modifier = Modifier.fillMaxSize().drawWithContent { if (session.value?.mini != false) drawContent() },
            entryProvider =
                entryProvider {
                entry<Main> {
                  TopLevel(PhoneTab.Live, onTab) { m ->
                    MainScreen(
                        onChannelClick = { index, group -> openPlayer(index, group) },
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
                entry<Guide> {
                  TopLevel(PhoneTab.Guide, onTab) { m ->
                    com.fenyx.jtv.ui.guide.GuideScreen(
                        viewModel = mainViewModel,
                        onPlay = { index, group -> openPlayer(index, group) },
                        onOpenSettings = { onTab(PhoneTab.Settings) },
                        modifier = m,
                        onTab = onTab
                    )
                  }
                }
                entry<Search> {
                  TopLevel(PhoneTab.Search, onTab) { m ->
                    com.fenyx.jtv.ui.search.SearchScreen(
                        viewModel = mainViewModel,
                        onTab = onTab,
                        onChannelClick = { index, group -> openPlayer(index, group) },
                        modifier = m
                    )
                  }
                }
                entry<Settings> {
                  TopLevel(PhoneTab.Settings, onTab) { m ->
                    SettingsScreen(
                        modifier = m,
                        mainViewModel = mainViewModel,
                        onBack = { backStack.removeLastOrNull() },
                        onTab = onTab
                    )
                  }
                }
                // TV only (phone/tablet use the PlayerHost below). Unchanged from before the mini player.
                entry<Player> { playerArgs ->
                    val view = androidx.compose.ui.platform.LocalView.current
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        val window = (view.context as? android.app.Activity)?.window
                        val ctl = window?.let { androidx.core.view.WindowInsetsControllerCompat(it, view) }
                        ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        onDispose {
                            if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
                                ctl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                            }
                        }
                    }
                    PlayerForChannel(
                        channelIndex = playerArgs.channelIndex,
                        group = playerArgs.group,
                        mainViewModel = mainViewModel,
                        onBack = { backStack.removeLastOrNull() },
                        onSettings = { backStack.add(Settings) },
                    )
                }
            },
        )
      }
        if (!isTv) PlayerHost(
            session = session,
            mainViewModel = mainViewModel,
            onSettings = { onTab(PhoneTab.Settings) },
        )
    }
}

/**
 * Phone/tablet player layer, drawn above NavDisplay. Composes [TvPlayerScreen] ONCE per session and
 * keeps it composed while minimised, so the ExoPlayer and its effects keep running (no rebuffer).
 * Expanded = the player page or full screen (the player handles the system bars); mini = phone bar docked above the tab bar,
 * or a 360dp card at the bottom-right on tablet (system bars shown).
 */
@Composable
private fun BoxScope.PlayerHost(
    session: MutableState<PlayerSession?>,
    mainViewModel: MainViewModel,
    onSettings: () -> Unit,
) {
    val s = session.value ?: return
    val mini = s.mini
    val phone = Jtv.isPhonePortrait

    // System bars: the player itself hides them, and only in true full screen (phone landscape, tablet
    // full screen). The portrait phone page and the tablet page keep them (see TvPlayerScreen).

    val frame = when {
        !mini -> Modifier
        phone -> Modifier.align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(bottom = com.fenyx.jtv.ui.main.PhoneBottomBarHeight)
        else -> Modifier.align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
    }
    PlayerForChannel(
        channelIndex = s.channelIndex,
        group = s.group,
        mainViewModel = mainViewModel,
        onBack = { session.value = null },
        onSettings = {
            session.value = session.value?.copy(mini = true)
            onSettings()
        },
        mini = mini,
        onMinimize = { session.value = session.value?.copy(mini = true) },
        onExpand = { session.value = session.value?.copy(mini = false) },
        openToken = s.token,
        modifier = frame,
    )
}

/** Builds the player's channel lists for one channel/group and shows [TvPlayerScreen]. */
@Composable
private fun PlayerForChannel(
    channelIndex: Int,
    group: String?,
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    mini: Boolean = false,
    onMinimize: (() -> Unit)? = null,
    onExpand: () -> Unit = {},
    openToken: Int = 0,
    modifier: Modifier = Modifier,
) {
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
    val channels = remember(group, allChannels) {
        if (group != null) mainViewModel.getChannelsByGroup(group)
        else allChannels
    }
    val playerGroups = remember(groups, allChannels) {
        if (mainViewModel.favoriteOrder.value.isEmpty()) groups
        else listOf(MainViewModel.GROUP_FAVORITES) + groups
    }
    val allChannelsByGroup = remember(playerGroups, allChannels) {
        playerGroups.associateWith { g -> mainViewModel.getChannelsByGroup(g) }
    }
    val filteredIndex = remember(channels, allChannels, channelIndex) {
        val targetChannel = allChannels.getOrNull(channelIndex)
        if (targetChannel != null) channels.indexOf(targetChannel).coerceAtLeast(0) else 0
    }
    val variantsFor = remember(mainViewModel) { { id: String -> mainViewModel.variantsFor(id) } }

    TvPlayerScreen(
        channels = channels,
        initialIndex = filteredIndex,
        allChannelsByGroup = allChannelsByGroup,
        groups = playerGroups,
        onBack = onBack,
        onSettings = onSettings,
        variantsFor = variantsFor,
        initialGroup = group,
        modifier = modifier,
        mini = mini,
        onMinimize = onMinimize,
        onExpand = onExpand,
        openToken = openToken,
    )
}
