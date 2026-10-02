package com.fenyx.jtv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.main.MainViewModel

/** Tablet landscape (YouTube tablet): share of the width the video column takes. */
internal const val TabletVideoFraction = 0.64f

/** Window widths from here up get the two-column tablet page; narrower ones get the phone page. */
internal const val TabletTwoColumnMinWidthDp = 840

/** Tablet portrait: the phone page, with its text held to this width under the full-width video. */
internal val TabletPortraitContentWidth = 720.dp

/**
 * Tablet, landscape. Two columns, like YouTube on a tablet:
 *  - left ([TabletVideoFraction] of the width): the 16:9 video at the top with the phone's tap
 *    controls, then (scrolling under it) what's on now, the action row and "Next on <channel>";
 *  - right: "Channels" with sticky category chips and the channel rows, scrolling on its own.
 *
 * Same building blocks as the phone page. The video surface itself is laid out by the player root
 * ([VideoBox.TwoColumn]) exactly under the left column's 16:9 box; nothing opaque is drawn there.
 * The page uses the colours it is given (the app theme); the controls on the picture stay dark.
 */
@Composable
internal fun TabletLandscapePlayer(
    ui: PlayerUi,
    d: OverlayData,
    actionsState: State<PlayerActions>,
    buffering: Boolean,
) {
    val now = rememberMinuteClock()
    val act by rememberUpdatedState(d.actions)
    val playing = d.playing
    CompositionLocalProvider(LocalNow provides now) {
        Box(Modifier.fillMaxSize()) {
            BannerAutoHide(ui, 4_000)
            LaunchedEffect(d.currentGroup) { ui.browseGroup = d.currentGroup ?: MainViewModel.GROUP_ALL }
            val bGroup = ui.browseGroup ?: d.currentGroup ?: MainViewModel.GROUP_ALL

            Row(Modifier.fillMaxSize()) {
                // ── Left: video + now ──
                Column(Modifier.fillMaxWidth(TabletVideoFraction).fillMaxHeight()) {
                    PhoneVideo(ui, d, actionsState, buffering)
                    val nn = if (playing != null) rememberNowNext(d.epg, playing.id, always = true) else null
                    val later = nn?.later.orEmpty().take(3)
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        if (playing != null) nowSection(playing, nn?.now, later, d, ui, act)
                    }
                }

                // ── Right: channels ──
                val list = remember(bGroup, d.browseGroups) { d.resolveGroup(bGroup) }
                val listState = rememberLazyListState()
                // Open on the playing channel (one row of context above it); tuning from the list
                // doesn't move it.
                LaunchedEffect(bGroup, list) {
                    val i = list.indexOfFirst { it.id == playing?.id }
                    if (i > 2) listState.scrollToItem(i)
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    channelsSection(ui, d, bGroup, list, act)
                }
            }
            PlayerSheets(ui, d, sheetMaxWidth = 640.dp)
        }
    }
}
