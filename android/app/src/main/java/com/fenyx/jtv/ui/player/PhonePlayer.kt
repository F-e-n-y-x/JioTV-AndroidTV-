package com.fenyx.jtv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.main.MainViewModel

/**
 * Phone, portrait (D-phone-player-dark): 16:9 video on top with a number tag, then the show, a row of
 * labelled buttons and the channel list of the current category. Tap a row to tune.
 */
@Composable
internal fun PhonePortraitPlayer(
    ui: PlayerUi,
    d: OverlayData,
    actionsState: State<PlayerActions>,
    video: @Composable () -> Unit,
    videoStatus: @Composable BoxScope.() -> Unit,
) {
    val c = Jtv.colors
    val now = rememberMinuteClock()
    val act by rememberUpdatedState(d.actions)
    val playing = d.playing
    CompositionLocalProvider(LocalNow provides now) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    JtvButton("Back", { act.leave() }, icon = Icons.AutoMirrored.Filled.ArrowBack, minHeight = 56.dp)
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
                        .touchVideoGestures(ui, actionsState, tapToggles = false),
                ) {
                    video()
                    videoStatus()
                    if (playing != null) {
                        Row(Modifier.align(Alignment.BottomStart)) {
                            Box(Modifier.background(c.acc).padding(horizontal = 12.dp, vertical = 5.dp)) {
                                Text(
                                    if (playing.channelNumber > 0) playing.channelNumber.toString() else "–",
                                    style = numberStyle(20.sp), color = c.accTx, maxLines = 1,
                                )
                            }
                            Box(Modifier.background(StrapBg).padding(horizontal = 12.dp, vertical = 7.dp)) {
                                JText(playing.name, 16.sp, weight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (playing != null) PhoneNowInfo(playing, d, onOpen = { ui.openOptions(it) })

                // Category pills + the channel list of the chosen category.
                LaunchedEffect(d.currentGroup) { ui.browseGroup = d.currentGroup ?: MainViewModel.GROUP_ALL }
                val bGroup = ui.browseGroup ?: d.currentGroup ?: MainViewModel.GROUP_ALL
                val list = remember(bGroup, d.browseGroups) { d.resolveGroup(bGroup) }
                CategoryChips(
                    d.browseGroups, bGroup, onPick = { ui.browseGroup = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    overVideo = false,
                )
                val start = remember(bGroup) { list.indexOfFirst { it.id == playing?.id }.coerceAtLeast(0) }
                val listState = remember(bGroup) { androidx.compose.foundation.lazy.LazyListState((start - 1).coerceAtLeast(0)) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(list, key = { _, ch -> ch.id }, contentType = { _, _ -> "row" }) { i, ch ->
                        PhoneChannelRow(ch, ch.id == playing?.id, d.epg) { act.tune(bGroup, i) }
                    }
                }
            }

            // Options as a bottom sheet; the quick menu in the centre.
            val ov = ui.overlay
            if (ov == PlayerOverlay.Options || ov == PlayerOverlay.Menu) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { ui.overlay = PlayerOverlay.None },
                )
            }
            if (ov == PlayerOverlay.Options) {
                val maxH = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
                OptionsPanel(
                    page = ui.optionsPage, entry = ui.optionsEntry, model = d.model, channel = playing,
                    actions = d.actions, touch = true,
                    onPage = { ui.optionsPage = it }, onClose = { ui.overlay = PlayerOverlay.None },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxH)
                        .background(c.s1, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .padding(16.dp),
                )
            }
            if (ov == PlayerOverlay.Menu) {
                QuickMenu(
                    model = d.model, channel = playing, actions = d.actions, touch = true,
                    onOpenPage = { ui.openOptions(it) }, onClose = { ui.overlay = PlayerOverlay.None },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun PhoneNowInfo(playing: Channel, d: OverlayData, onOpen: (OptionsPage) -> Unit) {
    val c = Jtv.colors
    val now = LocalNow.current
    val nn = rememberNowNext(d.epg, playing.id)
    val cur = nn?.now
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
        JText(cur?.title ?: playing.name, 20.sp, weight = FontWeight.Bold, maxLines = 2)
        val line = if (cur != null) {
            buildString {
                append("${formatTime(cur.startMs)} – ${formatTime(cur.stopMs)} · ${cur.minutesLeft(now)} min left")
                nn.next?.let { append(" · Next ${formatTime(it.startMs)} ${it.title}") }
            }
        } else channelSubtitle(playing)
        JText(line, 16.sp, color = c.t2, maxLines = 2, modifier = Modifier.padding(top = 3.dp))
        if (cur != null) JtvProgress(cur.progress(now), Modifier.padding(top = 10.dp))
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fav = d.model.favourite
            JtvButton(
                "Favourite", { d.actions.toggleFavourite() },
                icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, minHeight = 56.dp,
            )
            JtvButton("Sound", { onOpen(OptionsPage.Sound) }, minHeight = 56.dp)
            JtvButton("Quality", { onOpen(OptionsPage.Quality) }, minHeight = 56.dp)
            JtvButton("Sleep", { onOpen(OptionsPage.Sleep) }, minHeight = 56.dp)
            JtvButton("More", { onOpen(OptionsPage.Main) }, minHeight = 56.dp)
        }
    }
}

@Composable
private fun PhoneChannelRow(ch: Channel, playing: Boolean, epg: EpgSource, onClick: () -> Unit) {
    val c = Jtv.colors
    val now = LocalNow.current
    val nn = rememberNowNext(epg, ch.id)
    val cur = nn?.now
    JtvClickable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        container = if (playing) c.inv else Color.Transparent,
    ) { focused ->
        val inv = playing || focused
        val fg = if (inv) c.invTx else c.tx
        val fg2 = if (inv) c.invTx else c.t2
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ch.channelNumber > 0) ch.channelNumber.toString() else "–",
                style = numberStyle(16.sp), color = fg, maxLines = 1, modifier = Modifier.width(52.dp),
            )
            ChannelPlate(ch.logoUrl, 48.dp, 28.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                JText(ch.name, 16.sp, color = fg, weight = FontWeight.SemiBold)
                JText(cur?.title ?: channelSubtitle(ch), 14.sp, color = fg2)
                if (cur != null) {
                    JtvProgress(
                        cur.progress(now), Modifier.padding(top = 5.dp),
                        color = if (inv) c.invTx else c.t3, track = if (inv) c.invTx.copy(alpha = 0.22f) else c.line,
                    )
                }
            }
        }
    }
}
