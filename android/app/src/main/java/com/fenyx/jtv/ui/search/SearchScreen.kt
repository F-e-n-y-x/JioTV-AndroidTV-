package com.fenyx.jtv.ui.search

import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvClock
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.MainViewModel

/**
 * Simple, D-pad-friendly channel search over the (collapsed) channel list. Focus lands on the input
 * so the leanback IME opens immediately; results filter live by name so the user rarely types a full
 * word. Selecting a result opens it in the player against the full channel list.
 */
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onChannelClick: (Int, String?) -> Unit,
    onTab: ((com.fenyx.jtv.ui.main.PhoneTab) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = Jtv.colors
    val isTv = Jtv.isTv
    val isPhone = Jtv.form == FormFactor.Phone
    val allChannels by viewModel.displayChannels.collectAsState()
    val indexMap = remember(allChannels) { allChannels.withIndex().associate { (i, c) -> c.id to i } }

    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { com.fenyx.jtv.data.SettingsManager(context) }
    val recentIds by settings.recentChannelsFlow.collectAsState(initial = emptyList())
    val favoriteOrder by viewModel.favoriteOrder.collectAsState()
    val byId = remember(allChannels) { allChannels.associateBy { it.id } }
    val recents = remember(recentIds, byId) { recentIds.mapNotNull { byId[it] } }
    val favourites = remember(favoriteOrder, byId) { favoriteOrder.mapNotNull { byId[it] } }

    var query by remember { mutableStateOf("") }
    val results = remember(query, allChannels) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else allChannels.filter { ch ->
            // Match the representative's name, or any collapsed language variant's name, so a hidden
            // feed like "Colors Kannada" is still findable via its "Colors" tile.
            ch.name.contains(q, ignoreCase = true) ||
                viewModel.variantsFor(ch.id).any { it.channel.name.contains(q, ignoreCase = true) }
        }.distinctBy { it.id }.take(150)
    }

    val fieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
        kotlinx.coroutines.delay(50)
        keyboard?.show() // TV: focus alone doesn't open the on-screen keyboard
    }
    val now = rememberMinuteClock()
    var fieldFocused by remember { mutableStateOf(false) }

    // Hold OK / long-press on a result: Watch, and "Replay earlier shows" for catch-up channels.
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    menuChannel?.let { ch ->
        val actions = buildList {
            add(com.fenyx.jtv.ui.main.ChannelAction(context.getString(R.string.home_watch)) { onChannelClick(indexMap[ch.id] ?: 0, null) })
            if (ch.isCatchup && onTab != null) add(com.fenyx.jtv.ui.main.ChannelAction(context.getString(R.string.home_action_replay_earlier), context.getString(R.string.home_action_replay_earlier_note)) {
                viewModel.requestGuideFocus(ch.id)
                onTab(com.fenyx.jtv.ui.main.PhoneTab.Guide)
            })
        }
        com.fenyx.jtv.ui.main.ChannelActionsDialog(ch, actions) { menuChannel = null }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(
                horizontal = if (isTv) 48.dp else 16.dp,
                vertical = if (isTv) 27.dp else 8.dp
            )
    ) {
        // ─── Title ───
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            JText(stringResource(R.string.search_title), if (isTv) 28.sp else 24.sp, modifier = Modifier.weight(1f), weight = FontWeight.Bold)
            if (isTv && onTab != null) com.fenyx.jtv.ui.main.TvTabs(com.fenyx.jtv.ui.main.PhoneTab.Search, onTab, Modifier.padding(end = 20.dp, top = 2.dp))
            if (isTv) JtvClock(now, dateColor = c.t2)
        }
        Spacer(Modifier.height(if (isTv) 12.dp else 8.dp))

        // ─── Search field (visible label above it) ───
        if (!isPhone) {
            Text(stringResource(R.string.search_field_label), style = textStyle(16.sp, FontWeight.SemiBold), color = c.t2)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .then(if (isTv) Modifier.widthIn(max = 720.dp) else Modifier)
                .fillMaxWidth()
                .heightIn(min = if (isTv) 60.dp else 52.dp)
                .background(c.s1, RoundedCornerShape(8.dp))
                .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) c.acc else c.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = c.t2, modifier = Modifier.size(26.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_placeholder), style = textStyle(if (isPhone) 17.sp else 20.sp), color = c.t2, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fieldFocused = it.hasFocus }
                        .focusRequester(fieldFocus)
                        .focusable(),
                    textStyle = textStyle(if (isPhone) 18.sp else 22.sp).copy(color = c.tx),
                    cursorBrush = SolidColor(c.acc),
                    singleLine = true,
                    // "Search" on the keyboard closes it and moves to the first result.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        if (results.isNotEmpty()) runCatching { firstResultFocus.requestFocus() }
                    })
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // One list, in the owner's order: search results, then Recently watched, then Favourites.
        val q = query.trim()
        if (q.isNotEmpty() && results.isEmpty() && recents.isEmpty() && favourites.isEmpty()) {
            CenterNote(stringResource(R.string.search_no_match, q), Modifier.weight(1f))
        } else if (q.isEmpty() && recents.isEmpty() && favourites.isEmpty()) {
            CenterNote(stringResource(R.string.search_type_hint), Modifier.weight(1f))
        } else {
            val firstId = (if (q.isNotEmpty()) results else emptyList()).firstOrNull()?.id
                ?: recents.firstOrNull()?.id ?: favourites.firstOrNull()?.id
            // Start every new query (and the empty state) at the top so section titles are visible.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(q, recents.size, favourites.size) { listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                fun section(tag: String, title: String, list: List<Channel>, firstSection: Boolean) {
                    if (list.isEmpty()) return
                    item(key = "h:$tag", contentType = "header") {
                        Text(title, style = textStyle(14.sp, FontWeight.SemiBold), color = c.t2,
                            modifier = Modifier.padding(start = 12.dp, top = if (firstSection) 4.dp else 14.dp, bottom = 4.dp))
                    }
                    items(items = list, key = { "$tag:${it.id}" }, contentType = { "search-row" }) { channel ->
                        SearchRow(
                            channel = channel,
                            compact = isPhone,
                            modifier = if (firstSection && channel.id == firstId) Modifier.focusRequester(firstResultFocus) else Modifier,
                            onClick = { onChannelClick(indexMap[channel.id] ?: 0, null) },
                            onLongClick = { menuChannel = channel },
                        )
                    }
                }
                var first = true
                if (q.isNotEmpty()) {
                    if (results.isEmpty()) item(key = "none", contentType = "note") {
                        Text(context.getString(R.string.search_no_match, q), style = textStyle(16.sp), color = c.t2, modifier = Modifier.padding(12.dp))
                    }
                    section("r", context.resources.getQuantityString(R.plurals.search_results, results.size, results.size), results, first)
                    first = results.isEmpty()
                }
                section("w", context.getString(R.string.search_recently_watched), recents, first && recents.isNotEmpty())
                if (recents.isNotEmpty()) first = false
                section("f", context.getString(R.string.search_favourites), favourites, first)
            }
        }

        if (isTv) {
            Spacer(Modifier.height(6.dp))
            KeyHint(listOf(
                stringResource(R.string.nav_key_ok) to stringResource(R.string.nav_hint_watch),
                stringResource(R.string.nav_key_hold_ok) to stringResource(R.string.nav_hint_options),
                stringResource(R.string.nav_key_down) to stringResource(R.string.nav_hint_results),
                stringResource(R.string.nav_key_back) to stringResource(R.string.nav_hint_close_search),
            ))
        }
    }
}

@Composable
private fun CenterNote(text: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, style = textStyle(20.sp), color = Jtv.colors.t2, textAlign = TextAlign.Center)
    }
}

/** Same row shape as the home channel list: number, logo plate, name, category underneath. */
@Composable
private fun SearchRow(channel: Channel, compact: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        focusedScale = 1.02f,
    ) { focused ->
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                if (channel.channelNumber > 0) channel.channelNumber.toString() else "",
                modifier = Modifier.width(if (compact) 44.dp else 56.dp),
                style = numberStyle(if (compact) 18.sp else 22.sp),
                color = if (focused) c.invTx else c.tx,
                textAlign = TextAlign.End,
                maxLines = 1
            )
            ChannelPlate(channel.logoUrl, if (compact) 56.dp else 64.dp, if (compact) 32.dp else 36.dp)
            Column(Modifier.weight(1f)) {
                JText(channel.name, 18.sp, color = if (focused) c.invTx else c.tx, weight = FontWeight.SemiBold)
                JText(com.fenyx.jtv.ui.main.groupLabel(channel.group), 14.sp, color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2)
            }
        }
    }
}
