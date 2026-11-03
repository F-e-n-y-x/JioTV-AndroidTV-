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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.Color
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
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClock
import com.fenyx.jtv.ui.components.formatTime
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
    val displayChannels by viewModel.displayChannels.collectAsState()
    val rawAllChannels by viewModel.allChannels.collectAsState()

    var globalSearch by rememberSaveable { mutableStateOf(true) }

    val activeChannels = remember(globalSearch, rawAllChannels, displayChannels) {
        if (globalSearch && rawAllChannels.isNotEmpty()) rawAllChannels.sortedBy { it.channelNumber } else displayChannels
    }
    val indexMap = remember(activeChannels) { activeChannels.withIndex().associate { (i, c) -> c.id to i } }

    val allSorted = remember(rawAllChannels, displayChannels) {
        (if (rawAllChannels.isNotEmpty()) rawAllChannels else displayChannels).sortedBy { it.channelNumber }
    }
    val globalIndexMap = remember(allSorted) { allSorted.withIndex().associate { (i, c) -> c.id to i } }

    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { com.fenyx.jtv.data.SettingsManager(context) }
    val recentIds by settings.recentChannelsFlow.collectAsState(initial = emptyList())
    val favoriteOrder by viewModel.favoriteOrder.collectAsState()

    // Persistent favorites & recents: resolve against all channels so channels in other languages are preserved
    val globalPool = remember(rawAllChannels, displayChannels) {
        if (rawAllChannels.isNotEmpty()) rawAllChannels else displayChannels
    }
    val byId = remember(globalPool) { globalPool.associateBy { it.id } }
    val recents = remember(recentIds, byId) { recentIds.mapNotNull { byId[it] } }
    val favourites = remember(favoriteOrder, byId) { favoriteOrder.mapNotNull { byId[it] } }

    fun playChannel(ch: Channel) {
        if (globalSearch) {
            val idx = indexMap[ch.id] ?: globalIndexMap[ch.id] ?: 0
            onChannelClick(idx, MainViewModel.GROUP_ALL_GLOBAL)
        } else {
            val idx = indexMap[ch.id]
            if (idx != null) {
                onChannelClick(idx, null)
            } else {
                val gIdx = globalIndexMap[ch.id] ?: 0
                onChannelClick(gIdx, MainViewModel.GROUP_ALL_GLOBAL)
            }
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, activeChannels) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else activeChannels.filter { ch ->
            // Match the representative's name, or any collapsed language variant's name, so a hidden
            // feed like "Colors Kannada" is still findable via its "Colors" tile.
            ch.name.contains(q, ignoreCase = true) ||
                viewModel.variantsFor(ch.id).any { it.channel.name.contains(q, ignoreCase = true) }
        }.distinctBy { it.id }.take(150)
    }

    // "Shows": current/next programme titles from the guide already loaded (EPG mode only), debounced
    // and matched off the main thread so typing stays smooth on weak boxes.
    val epgMode by settings.epgModeFlow.collectAsState(initial = false)
    val epgData by viewModel.epgData.collectAsState()
    val scopeById = remember(activeChannels) { activeChannels.associateBy { it.id } }
    var shows by remember { mutableStateOf(emptyList<ShowHit>()) }
    val now = rememberMinuteClock()
    LaunchedEffect(query, epgMode, epgData, scopeById, now) {
        if (!epgMode || query.isBlank()) { shows = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        shows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            matchShows(query, scopeById, epgData, System.currentTimeMillis())
        }
    }

    // Voice search: the system speech recogniser, Indian English with Hindi (primary when the app is in Hindi).
    val speakPrompt = stringResource(R.string.search_speak_prompt)
    val voiceIntent = remember(speakPrompt) {
        val hi = java.util.Locale.getDefault().language == "hi"
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, if (hi) "hi-IN" else "en-IN")
            // RecognizerIntent.EXTRA_EXTRA_ADDITIONAL_LANGUAGES (API 33); older recognisers ignore it.
            .putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(if (hi) "en-IN" else "hi-IN"))
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, speakPrompt)
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val canSpeak = remember(voiceIntent) {
        runCatching { context.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false)
    }
    val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }?.let { query = it }
    }
    fun speak() { runCatching { voiceLauncher.launch(voiceIntent) } }

    val fieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
        kotlinx.coroutines.delay(50)
        keyboard?.show() // TV: focus alone doesn't open the on-screen keyboard
    }
    var fieldFocused by remember { mutableStateOf(false) }

    // Hold OK / long-press on a result: Watch, and "Replay earlier shows" for catch-up channels.
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    menuChannel?.let { ch ->
        val actions = buildList {
            add(com.fenyx.jtv.ui.main.ChannelAction(context.getString(R.string.home_watch)) { playChannel(ch) })
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
            // The remote's Search / mic key while on this screen: start voice search (or go to the field).
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                if (code != android.view.KeyEvent.KEYCODE_SEARCH && code != android.view.KeyEvent.KEYCODE_VOICE_ASSIST) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    if (canSpeak) speak() else runCatching { fieldFocus.requestFocus() }
                }
                true
            }
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
            if (isTv) JtvClock(now)
        }
        Spacer(Modifier.height(if (isTv) 12.dp else 8.dp))

        // ─── Search field (visible label above it) ───
        if (!isPhone) {
            Text(stringResource(R.string.search_field_label), style = textStyle(16.sp, FontWeight.SemiBold), color = c.t2)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
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
                        if (results.isNotEmpty() || shows.isNotEmpty()) runCatching { firstResultFocus.requestFocus() }
                    })
                )
            }
        }
        if (canSpeak) {
            Spacer(Modifier.width(10.dp))
            JtvButton(
                text = stringResource(R.string.search_speak),
                onClick = { speak() },
                icon = ImageVector.vectorResource(R.drawable.ic_mic),
                fontSize = if (isPhone) 16.sp else 18.sp,
                minHeight = if (isTv) 60.dp else 56.dp,
            )
        }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ─── Scope toggle: Global (all 1000+ channels) vs Filtered (active languages) ───
        Row(
            modifier = Modifier.padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            JtvClickable(
                onClick = { globalSearch = true },
                shape = RoundedCornerShape(16.dp),
                container = Color.Transparent,
                focusedContainer = Color.Transparent
            ) { focused ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (globalSearch) c.acc else if (focused) c.s2 else c.s1)
                        .border(1.dp, if (focused) c.invTx else Color.Transparent, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(R.string.search_scope_global),
                        style = textStyle(14.sp, if (globalSearch) FontWeight.Bold else FontWeight.Normal),
                        color = if (globalSearch) c.invTx else if (focused) c.tx else c.t2
                    )
                }
            }

            JtvClickable(
                onClick = { globalSearch = false },
                shape = RoundedCornerShape(16.dp),
                container = Color.Transparent,
                focusedContainer = Color.Transparent
            ) { focused ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (!globalSearch) c.acc else if (focused) c.s2 else c.s1)
                        .border(1.dp, if (focused) c.invTx else Color.Transparent, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(R.string.search_scope_filtered),
                        style = textStyle(14.sp, if (!globalSearch) FontWeight.Bold else FontWeight.Normal),
                        color = if (!globalSearch) c.invTx else if (focused) c.tx else c.t2
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // One list, in the owner's order: search results, then Recently watched, then Favourites.
        val q = query.trim()
        if (q.isNotEmpty() && results.isEmpty() && shows.isEmpty() && recents.isEmpty() && favourites.isEmpty()) {
            CenterNote(stringResource(R.string.search_no_match, q), Modifier.weight(1f))
        } else if (q.isEmpty() && recents.isEmpty() && favourites.isEmpty()) {
            CenterNote(stringResource(R.string.search_type_hint), Modifier.weight(1f))
        } else {
            // The row the keyboard's "Search" key jumps to: the first row of the first section shown.
            val firstKey = when {
                q.isNotEmpty() && results.isNotEmpty() -> "r:${results[0].id}"
                q.isNotEmpty() && shows.isNotEmpty() -> showKey(shows[0])
                recents.isNotEmpty() -> "w:${recents[0].id}"
                else -> favourites.firstOrNull()?.let { "f:${it.id}" }
            }
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
                            modifier = if ("$tag:${channel.id}" == firstKey) Modifier.focusRequester(firstResultFocus) else Modifier,
                            onClick = { playChannel(channel) },
                            onLongClick = { menuChannel = channel },
                        )
                    }
                }
                var first = true
                if (q.isNotEmpty()) {
                    if (results.isEmpty() && shows.isEmpty()) item(key = "none", contentType = "note") {
                        Text(context.getString(R.string.search_no_match, q), style = textStyle(16.sp), color = c.t2, modifier = Modifier.padding(12.dp))
                    }
                    section("r", context.resources.getQuantityString(R.plurals.search_results, results.size, results.size), results, first)
                    if (shows.isNotEmpty()) {
                        item(key = "h:s", contentType = "header") {
                            Text(stringResource(R.string.search_shows), style = textStyle(14.sp, FontWeight.SemiBold), color = c.t2,
                                modifier = Modifier.padding(start = 12.dp, top = if (results.isEmpty()) 4.dp else 14.dp, bottom = 4.dp))
                        }
                        items(items = shows, key = { showKey(it) }, contentType = { "show-row" }) { hit ->
                            ShowRow(
                                hit = hit,
                                compact = isPhone,
                                modifier = if (showKey(hit) == firstKey) Modifier.focusRequester(firstResultFocus) else Modifier,
                                onClick = { playChannel(hit.channel) },
                                onLongClick = { menuChannel = hit.channel },
                            )
                        }
                    }
                    first = results.isEmpty() && shows.isEmpty()
                }
                section("w", context.getString(R.string.search_recently_watched), recents, first && recents.isNotEmpty())
                if (recents.isNotEmpty()) first = false
                section("f", context.getString(R.string.search_favourites), favourites, first)
            }
        }

        if (isTv) {
            Spacer(Modifier.height(6.dp))
            KeyHint(listOfNotNull(
                stringResource(R.string.nav_key_ok) to stringResource(R.string.nav_hint_watch),
                stringResource(R.string.nav_key_hold_ok) to stringResource(R.string.nav_hint_options),
                stringResource(R.string.nav_key_down) to stringResource(R.string.nav_hint_results),
                if (canSpeak) stringResource(R.string.search_key_search) to stringResource(R.string.search_hint_speak) else null,
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

private fun showKey(h: ShowHit) = "s:${h.channel.id}:${h.program.startMs}"

/** A "Shows" result: logo plate, show title, then "Channel, on till 9:00 PM" / "Channel, starts 9:00 PM". */
@Composable
private fun ShowRow(hit: ShowHit, compact: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Jtv.colors
    JtvClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        focusedScale = 1.02f,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.width(if (compact) 44.dp else 56.dp))
            ChannelPlate(hit.channel.logoUrl, if (compact) 56.dp else 64.dp, if (compact) 32.dp else 36.dp)
            Column(Modifier.weight(1f)) {
                JText(hit.program.title, 18.sp, color = if (focused) c.invTx else c.tx, weight = FontWeight.SemiBold)
                JText(
                    if (hit.live) stringResource(R.string.search_show_now, hit.channel.name, formatTime(hit.program.stopMs))
                    else stringResource(R.string.search_show_next, hit.channel.name, formatTime(hit.program.startMs)),
                    14.sp, color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2
                )
            }
        }
    }
}
