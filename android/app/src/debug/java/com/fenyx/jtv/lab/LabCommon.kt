package com.fenyx.jtv.lab

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.platform.LocalConfiguration
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone < 600dp, tablet ≥ 600dp (touch/mouse), TV = leanback/television UI mode (D-pad first). */
enum class FormFactor { Phone, Tablet, Tv }

@Composable
fun rememberFormFactor(): FormFactor {
    val config = LocalConfiguration.current
    val isTv = (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    return when {
        isTv -> FormFactor.Tv
        config.screenWidthDp >= 600 -> FormFactor.Tablet
        else -> FormFactor.Phone
    }
}

/** How an item is being highlighted right now. Touch only ever shows [pressed]; no focus ring. */
data class Highlight(val focused: Boolean, val hovered: Boolean, val pressed: Boolean) {
    /** Keyboard/D-pad focus or mouse hover: both draw the same highlight (one highlight on screen). */
    val active: Boolean get() = focused || hovered
}

/**
 * One interactive surface for every input: D-pad/keyboard (focus + OK/Enter), mouse/air-mouse (hover
 * moves focus, click, right-click = long press), touch (tap, long-press). Draws nothing itself; the
 * direction decides how [Highlight] looks.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LabPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    content: @Composable (Highlight) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val requester = focusRequester ?: remember { FocusRequester() }
    val currentLong by rememberUpdatedState(onLongClick)
    Box(
        modifier
            .focusRequester(requester)
            // Air-mouse: hovering moves the real focus, so the D-pad continues from where the pointer is.
            // Right-click opens the same menu as hold OK / long-press.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when {
                            e.type == PointerEventType.Enter -> runCatching { requester.requestFocus() }
                            e.type == PointerEventType.Press && e.buttons.isSecondaryPressed -> currentLong?.invoke()
                        }
                    }
                }
            }
            .hoverable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        content(Highlight(focused, hovered, pressed))
    }
}

/** One shared 30 s clock for everything time-based on a screen (now-lines, progress). */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private val hhmm = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
fun timeOf(ms: Long): String = hhmm.get()!!.format(Date(ms))

/** The tabs the lab offers: Favourites (when any), All, then Jio's categories. */
@Composable
fun rememberTabs(vm: MainViewModel): List<String> {
    val groups by vm.groups.collectAsState()
    val favs by vm.favoriteOrder.collectAsState()
    return remember(groups, favs.isEmpty()) {
        buildList {
            if (favs.isNotEmpty()) add(MainViewModel.GROUP_FAVORITES)
            add(MainViewModel.GROUP_ALL)
            addAll(groups)
        }
    }
}

fun tabLabel(group: String): String = when (group) {
    MainViewModel.GROUP_ALL -> "All"
    MainViewModel.GROUP_FAVORITES -> "Favourites"
    else -> group
}

/** Channels of one tab, in the same order the player zaps through. */
@Composable
fun rememberChannels(vm: MainViewModel, group: String): List<Channel> {
    val display by vm.displayChannels.collectAsState()
    val favs by vm.favoriteOrder.collectAsState()
    return remember(display, favs, group) { vm.getChannelsByGroup(group) }
}

/** Index the player expects (position in the full display list). */
fun MainViewModel.playIndexOf(channel: Channel): Int =
    displayChannels.value.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)

/**
 * Programmes for one channel, fetched lazily ONLY while [enabled] (the guide is on), as v1 does.
 * Nothing is requested for rows that never become visible.
 */
@Composable
fun programsOf(vm: MainViewModel, epg: Map<String, List<EpgProgram>>, channelId: String, enabled: Boolean): List<EpgProgram> {
    if (enabled) LaunchedEffect(channelId) { vm.fetchNativeEpgIfMissing(channelId) }
    return if (enabled) epg[channelId].orEmpty() else emptyList()
}

fun List<EpgProgram>.nowAndNext(now: Long): Pair<EpgProgram?, EpgProgram?> {
    val i = indexOfFirst { it.startMs <= now && it.stopMs > now }
    return if (i < 0) null to firstOrNull { it.startMs > now } else this[i] to getOrNull(i + 1)
}

fun EpgProgram.progress(now: Long): Float =
    ((now - startMs).toFloat() / (stopMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)

/** Everything a direction's screens need. */
class LabScreenState(
    val vm: MainViewModel,
    val formFactor: FormFactor,
    val tabs: List<String>,
    val tab: String,
    val onTab: (String) -> Unit,
    val guideOn: Boolean,
    val now: Long,
    val onPlay: (Channel) -> Unit,
    val onMenu: (Channel) -> Unit
)
