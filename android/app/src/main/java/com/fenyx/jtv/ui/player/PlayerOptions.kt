package com.fenyx.jtv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvClickable

/** Everything the options panel and quick menu show. Built by the player from its playback state. */
@Immutable
internal data class OptionsModel(
    val favourite: Boolean,
    /** The one Language choice: in-stream sound tracks plus sibling-language channels. */
    val langChoices: List<Pair<String, String>>,
    val langCurrent: String,
    /** The playing language, for display. */
    val langLabel: String,
    val quality: String,
    val aspect: Int,
    val voice: Int,
    val autoVolume: Boolean,
    val sleep: Int,
    val paused: Boolean,
    val showRefresh: Boolean,
    val refreshing: Boolean,
)

/** Player actions the overlays can trigger. */
internal interface PlayerActions {
    fun toggleFavourite()
    /** A value from [OptionsModel.langChoices]. */
    fun pickLanguage(value: String)
    fun pickQuality(value: String)
    fun pickAspect(mode: Int)
    fun pickVoice(level: Int)
    fun toggleAutoVolume()
    fun pickSleep(minutes: Int)
    fun togglePause()
    fun openChannelList()
    fun openSettings()
    fun refreshLogin()
    fun zap(delta: Int)
    fun tune(group: String?, index: Int)
    fun back()
    /** Leave the player (on-screen Back button). */
    fun leave()
    /** Phone/tablet: the player can shrink into the in-app mini player (TV: false, Back leaves). */
    val canMinimize: Boolean get() = false
    /** Shrink into the mini player; where that isn't offered it leaves, like Back. */
    fun minimize() = leave()
    fun retry()
    /** Phone: go to landscape full screen, or back. */
    fun fullScreen(on: Boolean) {}
    /** Timeshift: seek within the live window (window coordinates; at the right end = live). */
    fun seekTo(ms: Long) {}
    /** Back to the live point, and play. */
    fun goLive() {}
}

/**
 * The options panel: labelled rows on the main page, each opening an inline list where focus lands on
 * the current value. [entry] is the page it was opened on: picking a value there closes the panel
 * (opened from the quick menu or a phone button), otherwise it returns to the main page.
 */
@Composable
internal fun OptionsPanel(
    page: OptionsPage,
    entry: OptionsPage,
    model: OptionsModel,
    channel: Channel?,
    actions: PlayerActions,
    touch: Boolean,
    onPage: (OptionsPage) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowH = if (touch) 60.dp else 52.dp
    val first = remember { FocusRequester() }
    val current = remember { FocusRequester() }
    var lastSub by remember { mutableStateOf<OptionsPage?>(null) }
    val subFocus = remember { OptionsPage.entries.associateWith { FocusRequester() } }

    LaunchedEffect(page) {
        if (touch) return@LaunchedEffect // touch shows a press effect only, never a focus highlight
        withFrameNanos { }
        runCatching {
            when {
                page != OptionsPage.Main -> current.requestFocus()
                lastSub != null -> subFocus.getValue(lastSub!!).requestFocus()
                else -> first.requestFocus()
            }
        }
    }

    fun done() {
        if (entry == OptionsPage.Main) onPage(OptionsPage.Main) else onClose()
    }
    fun open(p: OptionsPage) { lastSub = p; onPage(p) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        val title = when (page) {
            OptionsPage.Main -> "Options"
            OptionsPage.Language -> "Language"
            OptionsPage.Quality -> "Picture quality"
            OptionsPage.Aspect -> "Aspect"
            OptionsPage.Voice -> "Voice boost"
            OptionsPage.Sleep -> "Sleep timer"
        }
        JText(title, 22.sp, weight = FontWeight.Bold)
        if (channel != null) {
            JText(
                if (channel.channelNumber > 0) "${channel.channelNumber}  ${channel.name}" else channel.name,
                16.sp, color = Jtv.colors.t2, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (page == OptionsPage.Voice) {
            JText("Makes speech clearer over music and noise", 14.sp, color = Jtv.colors.t2, maxLines = 2,
                modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))

        when (page) {
            OptionsPage.Main -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val pickable = model.langChoices.size > 1
                    if (pickable) {
                        OptionRow("Language", model.langLabel, rowH, Modifier.focusRequester(first).focusRequester(subFocus.getValue(OptionsPage.Language))) { open(OptionsPage.Language) }
                    } else {
                        InfoRow("Language", model.langLabel, rowH)
                    }
                    OptionRow(
                        "Picture quality", qualityLabel(model.quality), rowH,
                        (if (pickable) Modifier else Modifier.focusRequester(first)).focusRequester(subFocus.getValue(OptionsPage.Quality)),
                    ) { open(OptionsPage.Quality) }
                    OptionRow("Aspect", aspectLabel(model.aspect), rowH, Modifier.focusRequester(subFocus.getValue(OptionsPage.Aspect))) { open(OptionsPage.Aspect) }
                    OptionRow("Voice boost", voiceLabel(model.voice), rowH, Modifier.focusRequester(subFocus.getValue(OptionsPage.Voice))) { open(OptionsPage.Voice) }
                    OptionRow("Auto volume", if (model.autoVolume) "On" else "Off", rowH) { actions.toggleAutoVolume() }
                    OptionRow("Sleep timer", sleepLabel(model.sleep), rowH, Modifier.focusRequester(subFocus.getValue(OptionsPage.Sleep))) { open(OptionsPage.Sleep) }
                    OptionRow("Favourite", if (model.favourite) "On" else "Off", rowH) { actions.toggleFavourite() }
                    if (model.showRefresh) {
                        OptionRow("Refresh sign-in", if (model.refreshing) "Refreshing" else "Refresh", rowH) { actions.refreshLogin() }
                    }
                    OptionRow("Settings", "", rowH) { actions.openSettings() }
                    // LAN sync: send this channel to a paired TV on the same Wi-Fi (phone/tablet only).
                    if (touch && channel != null) {
                        val tvs by com.fenyx.jtv.sync.LanSync.playTargets.collectAsState()
                        tvs.forEach { tv ->
                            OptionRow("Play on ${tv.name}", "", rowH) {
                                com.fenyx.jtv.sync.LanSync.playOn(tv.id, channel.id); onClose()
                            }
                        }
                    }
                    if (touch) OptionRow("Close", "", rowH) { onClose() }
                }
            }
            else -> {
                val (choices, cur) = when (page) {
                    OptionsPage.Language -> model.langChoices to model.langCurrent
                    OptionsPage.Quality -> QUALITY_OPTIONS to model.quality
                    OptionsPage.Aspect -> ASPECT_OPTIONS.map { it.first.toString() to it.second } to model.aspect.toString()
                    OptionsPage.Voice -> VOICE_OPTIONS.map { it.first.toString() to it.second } to model.voice.toString()
                    OptionsPage.Sleep -> SLEEP_OPTIONS.map { it.first.toString() to it.second } to model.sleep.toString()
                    OptionsPage.Main -> emptyList<Pair<String, String>>() to ""
                }
                val curIndex = choices.indexOfFirst { it.first == cur }.coerceAtLeast(0)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEachIndexed { i, (value, label) ->
                        ChoiceRow(
                            label, value == cur, rowH,
                            if (i == curIndex) Modifier.focusRequester(current) else Modifier,
                        ) {
                            when (page) {
                                OptionsPage.Language -> actions.pickLanguage(value)
                                OptionsPage.Quality -> actions.pickQuality(value)
                                OptionsPage.Aspect -> actions.pickAspect(value.toInt())
                                OptionsPage.Voice -> actions.pickVoice(value.toInt())
                                OptionsPage.Sleep -> actions.pickSleep(value.toInt())
                                OptionsPage.Main -> {}
                            }
                            done()
                        }
                    }
                    if (touch) OptionRow("Back", "", rowH) { done() }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, value: String, h: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Jtv.colors
    JtvClickable(onClick = onClick, modifier = modifier.fillMaxWidth().height(h)) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            JText(label, 18.sp, color = if (focused) c.invTx else c.tx, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (value.isNotEmpty()) JText(value, 16.sp, color = if (focused) c.invTx else c.t2)
        }
    }
}

/** A fact, not a choice (one language only): never focusable, so the D-pad skips it. */
@Composable
private fun InfoRow(label: String, value: String, h: Dp) {
    val c = Jtv.colors
    Row(Modifier.fillMaxWidth().height(h).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        JText(label, 18.sp, color = c.tx, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        JText(value, 16.sp, color = c.t2)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, h: Dp, modifier: Modifier, onClick: () -> Unit) {
    val c = Jtv.colors
    JtvClickable(onClick = onClick, modifier = modifier.fillMaxWidth().height(h)) { focused ->
        val fg = if (focused) c.invTx else c.tx
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            JText(label, 18.sp, color = fg, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = if (focused) c.invTx else c.acc, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                JText("Current", 14.sp, color = if (focused) c.invTx else c.t2)
            }
        }
    }
}

/** Hold OK / Menu key / right-click / long-press: a small menu over the video. Focus on the first item. */
@Composable
internal fun QuickMenu(
    model: OptionsModel,
    channel: Channel?,
    actions: PlayerActions,
    touch: Boolean,
    onOpenPage: (OptionsPage) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Jtv.colors
    val rowH = if (touch) 56.dp else 48.dp
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (touch) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        modifier.width(380.dp).clip(RoundedCornerShape(8.dp)).background(c.s1).padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (channel != null) {
            JText(
                if (channel.channelNumber > 0) "${channel.channelNumber}  ${channel.name}" else channel.name,
                18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 10.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OptionRow("Favourite", if (model.favourite) "On" else "Off", rowH, Modifier.focusRequester(first)) { actions.toggleFavourite() }
            if (model.langChoices.size > 1) OptionRow("Language", model.langLabel, rowH) { onOpenPage(OptionsPage.Language) }
            OptionRow("Picture quality", qualityLabel(model.quality), rowH) { onOpenPage(OptionsPage.Quality) }
            OptionRow("Aspect", aspectLabel(model.aspect), rowH) { onOpenPage(OptionsPage.Aspect) }
            OptionRow(if (model.paused) "Play" else "Pause", "", rowH) { actions.togglePause(); onClose() }
            OptionRow("Channel list", "", rowH) { actions.openChannelList() }
            OptionRow("Settings", "", rowH) { actions.openSettings() }
            if (touch) OptionRow("Close", "", rowH) { onClose() }
        }
    }
}
