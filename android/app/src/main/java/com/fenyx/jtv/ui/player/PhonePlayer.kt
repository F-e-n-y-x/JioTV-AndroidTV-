package com.fenyx.jtv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.EndsSoonPill
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.leadingBar
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.delay

/**
 * Phone, portrait. The jobs, in order: see what's on, change channel, fix sound or picture.
 *
 *  1. 16:9 video flush at the top. Tap it for icon controls (Back, Full screen, Previous / Pause /
 *     Next) that hide after 4 s; they also show for 4 s after every tune so people learn they exist.
 *     The amber number tag stays on the picture.
 *  2. One lazy page below it: what's on now, a compact row of labelled icon actions, what's next on
 *     this channel, then the channel list with sticky category chips. Tap a row to tune.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhonePortraitPlayer(
    ui: PlayerUi,
    d: OverlayData,
    actionsState: State<PlayerActions>,
    buffering: Boolean,
    video: @Composable () -> Unit,
) {
    val c = Jtv.colors
    val now = rememberMinuteClock()
    val act by rememberUpdatedState(d.actions)
    val playing = d.playing
    CompositionLocalProvider(LocalNow provides now) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            BannerAutoHide(ui, 4_000)
            Column(Modifier.fillMaxSize()) {
                PhoneVideo(ui, d, actionsState, buffering, video)

                LaunchedEffect(d.currentGroup) { ui.browseGroup = d.currentGroup ?: MainViewModel.GROUP_ALL }
                val bGroup = ui.browseGroup ?: d.currentGroup ?: MainViewModel.GROUP_ALL
                val list = remember(bGroup, d.browseGroups) { d.resolveGroup(bGroup) }
                // The watched channel always has its guide (one request), whatever the guide setting.
                val nn = if (playing != null) rememberNowNext(d.epg, playing.id, always = true) else null
                val later = nn?.later.orEmpty().take(3)
                val m = d.model
                val language = if (m.langCurrent.startsWith(LANG_TRACK) || m.langChoices.isEmpty()) playing?.language else m.langLabel

                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (playing != null) {
                        item(key = "now", contentType = "now") { NowCard(playing, nn?.now, language) }
                        item(key = "actions", contentType = "actions") {
                            ActionRow(m, onFavourite = { act.toggleFavourite() }, onOpen = { ui.openOptions(it) })
                        }
                        if (later.isNotEmpty()) {
                            item(key = "next-h", contentType = "header") { SectionHeader("Next on ${playing.name}") }
                            items(later, key = { "next:${it.startMs}" }, contentType = { "next" }) { NextRow(it) }
                        }
                    }
                    stickyHeader(key = "channels-h", contentType = "chips") {
                        Column(Modifier.fillMaxWidth().background(c.bg)) {
                            SectionHeader("Channels")
                            CategoryChips(
                                d.browseGroups, bGroup, onPick = { ui.browseGroup = it },
                                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                                overVideo = false,
                            )
                        }
                    }
                    itemsIndexed(list, key = { _, ch -> "ch:${ch.id}" }, contentType = { _, _ -> "row" }) { i, ch ->
                        val isPlaying = ch.id == playing?.id
                        val onTap = remember(bGroup, i) { { act.tune(bGroup, i) } }
                        val onHold = remember(bGroup, i, isPlaying) {
                            {
                                if (!isPlaying) act.tune(bGroup, i)
                                ui.overlay = PlayerOverlay.Menu
                            }
                        }
                        PhoneChannelRow(ch, isPlaying, d.epg, onTap, onHold)
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

// ───────────────────────── 1. Video ─────────────────────────

@Composable
private fun PhoneVideo(
    ui: PlayerUi,
    d: OverlayData,
    actionsState: State<PlayerActions>,
    buffering: Boolean,
    video: @Composable () -> Unit,
) {
    val c = Jtv.colors
    val act by actionsState
    val playing = d.playing
    val paused = d.model.paused
    // Paused keeps the controls up, so Play is always one tap away (no separate paused card).
    val controls = ui.overlay == PlayerOverlay.Banner || paused
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            .touchVideoGestures(ui, actionsState, tapToggles = true),
    ) {
        video()
        if (buffering && !controls) {
            CircularProgressIndicator(color = c.acc, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center))
        }
        if (playing != null) {
            Row(Modifier.align(Alignment.BottomStart)) {
                Box(Modifier.background(c.acc).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(
                        if (playing.channelNumber > 0) playing.channelNumber.toString() else "–",
                        style = numberStyle(18.sp), color = c.accTx, maxLines = 1,
                    )
                }
                Box(Modifier.background(StrapBg).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    JText(playing.name, 15.sp, weight = FontWeight.SemiBold)
                }
            }
        }
        AnimatedVisibility(
            visible = controls, enter = fadeIn(tween(150)), exit = fadeOut(tween(120)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                OverVideoIcon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back", { act.leave() },
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                )
                OverVideoIcon(
                    PlayerIcons.Fullscreen, "Full screen", { act.fullScreen(true) },
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverVideoIcon(PlayerIcons.SkipPrevious, "Previous channel", { act.zap(-1) }, size = 56.dp, iconSize = 30.dp)
                    Box(contentAlignment = Alignment.Center) {
                        if (buffering) {
                            CircularProgressIndicator(color = c.acc, strokeWidth = 3.dp, modifier = Modifier.size(64.dp))
                        }
                        OverVideoIcon(
                            if (paused) Icons.Filled.PlayArrow else PlayerIcons.Pause,
                            if (paused) "Play" else "Pause",
                            { act.togglePause() }, size = 56.dp, iconSize = 32.dp,
                        )
                    }
                    OverVideoIcon(PlayerIcons.SkipNext, "Next channel", { act.zap(1) }, size = 56.dp, iconSize = 30.dp)
                }
            }
        }
    }
}

// ───────────────────────── 2. Now ─────────────────────────

@Composable
private fun NowCard(ch: Channel, cur: EpgProgram?, language: String?) {
    val c = Jtv.colors
    val now = LocalNow.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        // Channel number + name are already on the amber tag over the video, so the card starts with the show.
        if (cur == null) {
            JText(ch.name, 20.sp, weight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            val sub = listOfNotNull(ch.group, language).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            if (sub.isNotEmpty()) JText(sub, 14.sp, color = c.t2, modifier = Modifier.padding(top = 4.dp))
            // Give the one guide request a moment before saying there's nothing.
            var noGuide by remember(ch.id) { mutableStateOf(false) }
            LaunchedEffect(ch.id) { delay(2_500); noGuide = true }
            if (noGuide) {
                JText("No programme details for this channel.", 16.sp, color = c.t2, maxLines = 2,
                    modifier = Modifier.padding(top = 8.dp))
            }
            return@Column
        }

        JText(cur.title, 20.sp, weight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
        val meta = programMeta(cur, language)
        if (meta.isNotEmpty()) JText(meta, 14.sp, color = c.t2, modifier = Modifier.padding(top = 4.dp))

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            JText("${formatTime(cur.startMs)} – ${formatTime(cur.stopMs)}", 14.sp, color = c.t2)
            val left = cur.minutesLeft(now)
            Spacer(Modifier.width(8.dp))
            if (left <= 10) EndsSoonPill(cur.stopMs, fontSize = 14.sp)
            else JText("· $left min left", 14.sp, color = c.t2)
        }
        JtvProgress(cur.progress(now), Modifier.padding(top = 8.dp))

        // Description: two lines, "More" opens everything the guide has.
        val desc = cur.description.ifBlank { cur.episodeDesc.orEmpty() }
        val extraEpisode = cur.episodeDesc?.takeIf { cur.description.isNotBlank() }
        val hasExtras = extraEpisode != null || cur.cast != null || cur.director != null || cur.episodeNum != null
        var expanded by remember(ch.id, cur.startMs) { mutableStateOf(false) }
        var clipped by remember(ch.id, cur.startMs) { mutableStateOf(false) }
        if (desc.isNotBlank()) {
            BasicText(
                desc,
                style = textStyle(16.sp).copy(color = c.tx),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (expanded) {
            val t2 = c.t2
            extraEpisode?.let { JText(it, 16.sp, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(top = 8.dp)) }
            cur.episodeNum?.let { JText("Episode $it", 14.sp, color = t2, modifier = Modifier.padding(top = 8.dp)) }
            cur.cast?.let { JText("Cast: $it", 14.sp, color = t2, maxLines = 3, modifier = Modifier.padding(top = 6.dp)) }
            cur.director?.let { JText("Director: $it", 14.sp, color = t2, maxLines = 2, modifier = Modifier.padding(top = 6.dp)) }
        }
        if (clipped || hasExtras || expanded) {
            JtvClickable(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp),
            ) { focused ->
                JText(
                    if (expanded) "Less" else "More", 16.sp,
                    color = if (focused) c.invTx else c.acc, weight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ───────────────────────── 3. Actions ─────────────────────────

/** One compact row of labelled icons (no boxes). Language only when there is a real choice. */
@Composable
private fun ActionRow(m: OptionsModel, onFavourite: () -> Unit, onOpen: (OptionsPage) -> Unit) {
    val c = Jtv.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val fav = m.favourite
        ActionIcon(
            if (fav) Icons.Filled.Star else PlayerIcons.StarBorder,
            if (fav) "Saved" else "Favourite",
            tint = if (fav) c.acc else c.tx, onClick = onFavourite,
        )
        if (m.langChoices.size > 1) ActionIcon(PlayerIcons.Translate, "Language", onClick = { onOpen(OptionsPage.Language) })
        ActionIcon(PlayerIcons.Hd, "Quality", onClick = { onOpen(OptionsPage.Quality) })
        ActionIcon(
            PlayerIcons.Timer, if (m.sleep > 0) sleepLabel(m.sleep) else "Sleep",
            tint = if (m.sleep > 0) c.acc else c.tx, onClick = { onOpen(OptionsPage.Sleep) },
        )
        ActionIcon(Icons.Filled.MoreVert, "More", onClick = { onOpen(OptionsPage.Main) })
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Jtv.colors.tx,
    onClick: () -> Unit,
) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        modifier = Modifier.weight(1f).widthIn(min = 48.dp).heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { role = Role.Button },
        shape = RoundedCornerShape(8.dp),
    ) { focused ->
        Column(
            Modifier.align(Alignment.Center).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = if (focused) c.invTx else tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            JText(label, 13.sp, color = if (focused) c.invTx else c.tx, weight = FontWeight.Medium)
        }
    }
}

// ───────────────────────── 4. Next ─────────────────────────

@Composable
private fun SectionHeader(text: String) {
    JText(
        text, 14.sp, color = Jtv.colors.t2, weight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Information only: time, title, category. */
@Composable
private fun NextRow(p: EpgProgram) {
    val c = Jtv.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(p.startMs), style = numberStyle(16.sp), color = c.tx, maxLines = 1, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            JText(p.title, 16.sp)
            val sub = listOfNotNull(p.category, p.genre?.takeUnless { it.equals(p.category, true) }).joinToString(" · ")
            if (sub.isNotEmpty()) JText(sub, 14.sp, color = c.t2)
        }
    }
}

// ───────────────────────── 5. Channels ─────────────────────────

/** 64dp row. The playing one has a 4dp amber leading bar and a "Playing" label (no inverted fill). */
@Composable
private fun PhoneChannelRow(ch: Channel, playing: Boolean, epg: EpgSource, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Jtv.colors
    val now = LocalNow.current
    val nn = rememberNowNext(epg, ch.id, always = playing)
    val cur = nn?.now
    JtvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(64.dp).leadingBar(c.acc, playing),
        shape = RoundedCornerShape(0.dp),
    ) { focused ->
        val fg = if (focused) c.invTx else c.tx
        val fg2 = if (focused) c.invTx else c.t2
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ch.channelNumber > 0) ch.channelNumber.toString() else "–",
                style = numberStyle(16.sp), color = fg, maxLines = 1, modifier = Modifier.width(48.dp),
            )
            ChannelPlate(ch.logoUrl, 48.dp, 28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                JText(ch.name, 16.sp, color = fg, weight = FontWeight.SemiBold)
                JText(cur?.title ?: ch.group.ifBlank { ch.language }, 14.sp, color = fg2)
                if (cur != null) {
                    JtvProgress(
                        cur.progress(now), Modifier.padding(top = 4.dp),
                        color = if (focused) c.invTx else c.t3, track = if (focused) c.invTx.copy(alpha = 0.22f) else c.line,
                    )
                }
            }
            if (playing) {
                Spacer(Modifier.width(8.dp))
                JText("Playing", 14.sp, color = if (focused) c.invTx else c.acc, weight = FontWeight.SemiBold)
            }
        }
    }
}
