package com.fenyx.jtv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.data.FavoriteOrder
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClock
import com.fenyx.jtv.ui.components.JtvProgress
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.LocalNow
import com.fenyx.jtv.ui.components.NumberBlock
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.minutesLeft
import com.fenyx.jtv.ui.components.nowNext
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.progress
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.components.textStyle
import kotlinx.coroutines.delay

/** A category entry shown in the column (TV/tablet) or the chips (phone). */
private data class Category(val key: String, val label: String, val count: Int)

/**
 * v2 "Everyday" home (approved round-2 mockups D-tv-home, D-tablet-home, D-phone-home):
 * categories · channel list · preview on TV and wide tablets; chips · list · bottom bar on phones.
 * Live TV is the first screen; OK / tap plays; hold OK / long-press / the ⋮ button opens channel options.
 */
@Composable
fun MainScreen(
    onChannelClick: (Int, String?) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onGuideClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
) {
    val c = Jtv.colors
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }

    val displayChannels by viewModel.displayChannels.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val filteredChannels by viewModel.filteredChannels.collectAsState()
    val favoriteChannels by viewModel.favoriteChannels.collectAsState()
    val epgMode by settingsManager.epgModeFlow.collectAsState(initial = false)
    val epgData by viewModel.epgData.collectAsState()
    val lastChannelId by settingsManager.lastChannelIdFlow.collectAsState(initial = null)
    // Paired TVs on the same Wi-Fi, for "Play on <TV>" in the channel options.
    val playTargets by com.fenyx.jtv.sync.LanSync.playTargets.collectAsState()

    LaunchedEffect(Unit) { viewModel.fetchChannels() }
    val now = rememberMinuteClock()

    val group = selectedGroup ?: MainViewModel.GROUP_ALL
    val isFavoritesGroup = group == MainViewModel.GROUP_FAVORITES

    val recentIds by viewModel.recentIds.collectAsState()
    val recentCount = remember(recentIds, displayChannels) { val ids = displayChannels.mapTo(HashSet()) { it.id }; recentIds.count { it in ids } }
    val categories = remember(displayChannels, groups, favoriteChannels, recentCount) {
        val counts = displayChannels.groupingBy { it.group }.eachCount()
        buildList {
            if (favoriteChannels.isNotEmpty()) add(Category(MainViewModel.GROUP_FAVORITES, "Favourites", favoriteChannels.size))
            if (recentCount > 0) add(Category(MainViewModel.GROUP_RECENT, "Recent", recentCount))
            add(Category(MainViewModel.GROUP_ALL, "All channels", displayChannels.size))
            groups.forEach { add(Category(it, it, counts[it] ?: 0)) }
        }
    }
    val indexById = remember(displayChannels) { displayChannels.withIndex().associate { (i, ch) -> ch.id to i } }

    // ── Favourites reorder (issue #1): arrows move the lifted channel, OK saves, Back cancels ──
    var movingId by remember { mutableStateOf<String?>(null) }
    var workingOrder by remember { mutableStateOf<List<Channel>?>(null) }
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    val shown = workingOrder ?: filteredChannels
    fun cancelMove() { movingId = null; workingOrder = null }
    fun commitMove() { workingOrder?.let { l -> viewModel.saveFavoriteOrder(l.map { it.id }) }; cancelMove() }
    LaunchedEffect(group) { if (!isFavoritesGroup) cancelMove() }
    androidx.activity.compose.BackHandler(enabled = movingId != null) { cancelMove() }

    fun play(ch: Channel) {
        if (movingId != null) {
            val list = workingOrder ?: return
            val from = list.indexOfFirst { it.id == movingId }
            val to = list.indexOfFirst { it.id == ch.id }
            if (from >= 0 && to >= 0) workingOrder = FavoriteOrder.move(list, from, to)
            commitMove(); return
        }
        onChannelClick(indexById[ch.id] ?: 0, selectedGroup)
    }

    fun actionsFor(ch: Channel): List<ChannelAction> = buildList {
        val isFav = favoriteChannels.contains(ch.id)
        add(ChannelAction("Watch") { play(ch) })
        add(
            if (isFav) ChannelAction("Remove from favourites", confirm = "Remove ${ch.name} from favourites?") { viewModel.toggleFavorite(ch.id) }
            else ChannelAction("Add to favourites") { viewModel.toggleFavorite(ch.id) }
        )
        playTargets.forEach { tv ->
            add(ChannelAction("Play on ${tv.name}") { com.fenyx.jtv.sync.LanSync.playOn(tv.id, ch.id) })
        }
        if (isFavoritesGroup && filteredChannels.size > 1) {
            val i = filteredChannels.indexOfFirst { it.id == ch.id }
            add(ChannelAction("Move", "Use up and down to place it, then press OK") {
                workingOrder = filteredChannels; movingId = ch.id
            })
            if (i > 0) add(ChannelAction("Move to top") {
                viewModel.saveFavoriteOrder(FavoriteOrder.move(filteredChannels, i, 0).map { it.id })
            })
            if (i in 0 until filteredChannels.lastIndex) add(ChannelAction("Move to bottom") {
                viewModel.saveFavoriteOrder(FavoriteOrder.move(filteredChannels, i, filteredChannels.lastIndex).map { it.id })
            })
            add(ChannelAction("Group favourites by category", "Categories keep the order they first appear in") {
                viewModel.groupFavoritesByCategory()
            })
        }
    }
    menuChannel?.let { ch -> ChannelActionsDialog(ch, actionsFor(ch)) { menuChannel = null } }
    var confirmUnfav by remember { mutableStateOf<Channel?>(null) }
    confirmUnfav?.let { ch ->
        ChannelActionsDialog(
            ch, emptyList(),
            startWith = ChannelAction("Remove", confirm = "Remove ${ch.name} from favourites?") { viewModel.toggleFavorite(ch.id) },
        ) { confirmUnfav = null }
    }

    // ── Number entry (0–9 tunes the real Jio channel number) ──
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = digits.toIntOrNull()
        digits = ""
        val target = shown.firstOrNull { it.channelNumber == n } ?: displayChannels.firstOrNull { it.channelNumber == n }
        if (target != null) onChannelClick(indexById[target.id] ?: 0, if (shown.contains(target)) selectedGroup else null)
    }

    val listState = rememberLazyListState()
    var focusedId by remember { mutableStateOf<String?>(null) }

    // Initial focus: the channel that was playing (back from the player), else the first channel.
    val targetFocus = remember { FocusRequester() }
    val focusTargetId = remember(shown, lastChannelId) {
        if (movingId != null) movingId else shown.firstOrNull { it.id == lastChannelId }?.id ?: shown.firstOrNull()?.id
    }
    var initialFocusDone by remember { mutableStateOf(false) }
    // Touch devices get no initial focus highlight (a focus ring there reads as "selected").
    val requestInitialFocus = Jtv.isTv
    LaunchedEffect(focusTargetId) {
        if (!requestInitialFocus || initialFocusDone || focusTargetId == null || movingId != null) return@LaunchedEffect
        val idx = shown.indexOfFirst { it.id == focusTargetId }
        if (idx > 2) listState.scrollToItem(idx - 2)
        androidx.compose.runtime.withFrameNanos { }
        runCatching { targetFocus.requestFocus() }
        initialFocusDone = true
    }
    // Keep the moving row visible and focused after every step.
    LaunchedEffect(workingOrder) {
        val id = movingId ?: return@LaunchedEffect
        val idx = shown.indexOfFirst { it.id == id }
        if (idx < 0) return@LaunchedEffect
        val vis = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (vis.size < 3 || idx <= vis.first() || idx >= vis.last()) listState.scrollToItem((idx - 2).coerceAtLeast(0))
        androidx.compose.runtime.withFrameNanos { }
        runCatching { targetFocus.requestFocus() }
    }

    val keys = Modifier.onPreviewKeyEvent { e ->
        val code = e.nativeKeyEvent.keyCode
        val digit = when (code) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
            in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> code - android.view.KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && movingId == null) {
            if (e.type == KeyEventType.KeyDown && digits.length < 4) digits += digit
            return@onPreviewKeyEvent true
        }
        val id = movingId ?: return@onPreviewKeyEvent false
        val list = workingOrder ?: return@onPreviewKeyEvent false
        if (e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter) {
            if (e.type == KeyEventType.KeyUp) commitMove()
            return@onPreviewKeyEvent true
        }
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val delta = when (e.key) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> 1
            Key.DirectionLeft, Key.DirectionRight -> return@onPreviewKeyEvent true
            else -> return@onPreviewKeyEvent false
        }
        val from = list.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from >= 0 && to != from) workingOrder = FavoriteOrder.move(list, from, to)
        true
    }

    val focusedChannel = shown.firstOrNull { it.id == focusedId } ?: shown.firstOrNull()

    val listContent: @Composable (RowMetrics, Boolean) -> Unit = { m, touch ->
        when {
            isLoading && shown.isEmpty() -> CenterMessage { CircularProgressIndicator(color = c.acc) ; Spacer(Modifier.height(14.dp)); Text("Loading channels…", style = textStyle(16.sp), color = c.t2) }
            error != null && shown.isEmpty() -> CenterMessage {
                Text(error ?: "", style = textStyle(17.sp), color = c.tx, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                JtvButton("Try again", onClick = { viewModel.retry() }, primary = true)
            }
            shown.isEmpty() -> CenterMessage { Text("No channels in this category", style = textStyle(16.sp), color = c.t2) }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(shown, key = { _, ch -> ch.id }, contentType = { _, _ -> "row" }) { _, ch ->
                    val programs = if (epgMode) epgData[ch.id] else null
                    if (epgMode) LaunchedEffect(ch.id) { if (programs.isNullOrEmpty()) viewModel.fetchNativeEpgIfMissing(ch.id) }
                    val nowProg = programs?.let { p -> p.firstOrNull { it.startMs <= now && now < it.stopMs } }
                    ChannelRow(
                        channel = ch, nowProgram = nowProg, now = now, m = m,
                        isFavorite = !isFavoritesGroup && favoriteChannels.contains(ch.id),
                        isMoving = ch.id == movingId,
                        onClick = { play(ch) },
                        onLongClick = { if (movingId == null) menuChannel = ch },
                        
                        modifier = Modifier
                            .then(if (ch.id == focusTargetId) Modifier.focusRequester(targetFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedId = ch.id }
                            .then(if (movingId != null) Modifier.animateItem() else Modifier),
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalNow provides now) {
        BoxWithConstraints(modifier.fillMaxSize().background(c.bg).then(keys)) {
            val wide = Jtv.isTv || maxWidth >= 840.dp
            if (wide) {
                WideHome(
                    categories = categories, selected = group, count = displayChannels.size,
                    onSelect = { viewModel.setSelectedGroup(it) }, selectOnFocus = Jtv.isTv && movingId == null,
                    onSearch = onSearchClick, onGuide = onGuideClick, onSettings = onSettingsClick,
                    movingName = movingId?.let { id -> shown.firstOrNull { it.id == id }?.name },
                    movingPos = movingId?.let { id -> shown.indexOfFirst { it.id == id } + 1 } ?: 0, total = shown.size,
                    list = { listContent(if (Jtv.isTv) TvRow else TouchRow.copy(height = 64.dp), false) },
                    preview = {
                        focusedChannel?.let { ch ->
                            PreviewPane(
                                ch, if (epgMode) epgData[ch.id].orEmpty() else null, now,
                                isFavorite = favoriteChannels.contains(ch.id),
                                onWatch = { play(ch) },
                                onFavorite = { if (favoriteChannels.contains(ch.id)) confirmUnfav = ch else viewModel.toggleFavorite(ch.id) },
                                onOptions = { menuChannel = ch },
                            )
                        }
                    },
                )
            } else {
                PhoneHome(
                    categories = categories, selected = group, count = displayChannels.size,
                    onSelect = { viewModel.setSelectedGroup(it) },
                    onSearch = onSearchClick, onGuide = onGuideClick, onSettings = onSettingsClick,
                    list = { listContent(TouchRow, true) },
                )
            }
            if (digits.isNotEmpty()) {
                NumberBlock(
                    digits.toInt(),
                    Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 48.dp).size(150.dp, 96.dp).clip(RoundedCornerShape(8.dp)),
                    fontSize = 52.sp,
                )
            }
        }
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun WideHome(
    categories: List<Category>,
    selected: String,
    count: Int,
    onSelect: (String) -> Unit,
    selectOnFocus: Boolean,
    onSearch: () -> Unit,
    onGuide: () -> Unit,
    onSettings: () -> Unit,
    movingName: String?,
    movingPos: Int,
    total: Int,
    list: @Composable () -> Unit,
    preview: @Composable () -> Unit,
) {
    val c = Jtv.colors
    val tv = Jtv.isTv
    val now = LocalNow.current
    Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 48.dp else 32.dp, vertical = if (tv) 27.dp else 24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Live TV", style = textStyle(26.sp, FontWeight.Bold), color = c.tx)
                Text(
                    if (movingName != null) "Moving $movingName · $movingPos of $total"
                    else "${categories.firstOrNull { it.key == selected }?.label ?: "All channels"} · $total channels",
                    style = textStyle(14.sp, if (movingName != null) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (movingName != null) c.acc else c.t3,
                )
            }
            // TV: section tabs (Up from the list). Tablet: the left navigation rail does this job.
            if (tv) TvTabs(PhoneTab.Live, { t ->
                when (t) { PhoneTab.Guide -> onGuide(); PhoneTab.Search -> onSearch(); PhoneTab.Settings -> onSettings(); else -> {} }
            }, Modifier.padding(end = 20.dp, top = 2.dp))
            JtvClock(now, size = 32.sp)
        }
        Spacer(Modifier.height(14.dp))
        // Favourites arrive after the channel list; without this the column keeps "All" pinned at the
        // top (key-based scroll anchoring) and the selected category can sit hidden above the fold.
        val catState = rememberLazyListState()
        val selIndex = categories.indexOfFirst { it.key == selected }
        LaunchedEffect(categories.size, selIndex) {
            val vis = catState.layoutInfo.visibleItemsInfo
            if (selIndex >= 0 && (vis.isEmpty() || selIndex <= vis.first().index || selIndex >= vis.last().index)) {
                catState.scrollToItem((selIndex - 2).coerceAtLeast(0))
            }
        }
        Row(Modifier.weight(1f)) {
            LazyColumn(
                state = catState,
                modifier = Modifier.width(if (tv) 190.dp else 210.dp).fillMaxHeight().focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(categories, key = { it.key }) { cat ->
                    CategoryRow(
                        cat.label, cat.count, cat.key == selected, onClick = { onSelect(cat.key) },
                        modifier = if (selectOnFocus) Modifier.onFocusChanged { if (it.isFocused && cat.key != selected) onSelect(cat.key) } else Modifier,
                        height = if (tv) 36.dp else 48.dp, fontSize = if (tv) 15.sp else 16.sp,
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) { list() }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.width(if (tv) 270.dp else 340.dp).fillMaxHeight()) { preview() }
        }
        if (tv) {
            Spacer(Modifier.height(8.dp))
            KeyHint(
                if (movingName != null) listOf("Up / Down" to "move", "OK" to "save", "Back" to "cancel")
                else listOf("OK" to "watch", "Hold OK" to "options", "Left" to "categories", "0–9" to "channel number")
            )
        }
    }
}

@Composable
private fun PhoneHome(
    categories: List<Category>,
    selected: String,
    count: Int,
    onSelect: (String) -> Unit,
    onSearch: () -> Unit,
    onGuide: () -> Unit,
    onSettings: () -> Unit,
    list: @Composable () -> Unit,
) {
    val c = Jtv.colors
    val now = LocalNow.current
    val chipState = rememberLazyListState()
    // Chips that appear later (Favourites, Recent) must not leave the row scrolled mid-chip.
    val selChip = categories.indexOfFirst { it.key == selected }
    LaunchedEffect(categories.size, selChip) { if (selChip >= 0) chipState.scrollToItem(if (selChip <= 3) 0 else selChip - 1) }
    Column(Modifier.fillMaxSize()) {
        // Compact header: the status bar already shows the time on phones.
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", style = textStyle(24.sp, FontWeight.Bold), color = c.tx)
            Spacer(Modifier.width(10.dp))
            Text("$count channels", style = textStyle(14.sp), color = c.t3)
        }
        LazyRow(
            state = chipState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories, key = { it.key }) { cat ->
                CategoryChip(if (cat.key == MainViewModel.GROUP_ALL) "All" else cat.label, cat.count, cat.key == selected) { onSelect(cat.key) }
            }
        }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp)) { list() }
    }
}

/**
 * Right-hand pane for the focused channel: logo picture with the amber number strap, what's on now
 * (when the guide is on), what's next, and labelled buttons (no hidden-only actions for older users).
 */
@Composable
private fun PreviewPane(
    ch: Channel,
    programs: List<EpgProgram>?,
    now: Long,
    isFavorite: Boolean,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onOptions: () -> Unit,
) {
    val c = Jtv.colors
    val tv = Jtv.isTv
    val nn = programs?.let { nowNext(it, now) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(c.plate)) {
            ChannelPlate(ch.logoUrl, 132.dp, 74.dp, Modifier.align(Alignment.Center).padding(bottom = 20.dp))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(if (tv) 42.dp else 48.dp).background(androidx.compose.ui.graphics.Color(0xFF141416))) {
                NumberBlock(ch.channelNumber, Modifier.fillMaxHeight().width(if (tv) 84.dp else 96.dp), fontSize = if (tv) 26.sp else 30.sp)
                Text(
                    ch.name, style = textStyle(if (tv) 16.sp else 17.sp, FontWeight.SemiBold), color = androidx.compose.ui.graphics.Color(0xFFECECEE),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val p = nn?.now
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(c.s2).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Now", style = textStyle(13.sp, FontWeight.SemiBold), color = c.tx)
                }
                Spacer(Modifier.width(10.dp))
                Text("${formatTime(p.startMs)} – ${formatTime(p.stopMs)} · ${p.minutesLeft(now)} min left", style = textStyle(14.sp), color = c.t2, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text(p.title, style = textStyle(if (tv) 20.sp else 22.sp, FontWeight.Bold), color = c.tx, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(p.description, style = textStyle(14.sp), color = c.t2, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            JtvProgress(p.progress(now))
            Spacer(Modifier.height(8.dp))
            nn.later.take(if (tv) 4 else 3).forEach { n ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(formatTime(n.startMs), style = numberStyle(15.sp).copy(fontWeight = FontWeight.Bold), color = c.t2, modifier = Modifier.width(88.dp))
                    Text(n.title, style = textStyle(15.sp), color = c.tx, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        } else {
            Text(ch.group, style = textStyle(16.sp, FontWeight.SemiBold), color = c.tx)
            Text(ch.language, style = textStyle(15.sp), color = c.t2)
            if (programs == null) {
                Spacer(Modifier.height(8.dp))
                Text("Turn on the programme guide in Settings to see what's on.", style = textStyle(14.sp), color = c.t3, maxLines = 3)
            }
        }
        Spacer(Modifier.weight(1f))
        // TV: OK on the row already plays and hold-OK opens options (shown in the key hint), so no
        // duplicate buttons. Touch: one compact row — Watch + a favourite toggle, lifted above the
        // tablet's floating mini player while it's showing (card ≈ 16:9 video + 66dp row + margin).
        if (!tv) {
            val miniShowing = com.fenyx.jtv.LocalMiniBarInset.current.value > 0.dp

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                JtvButton("Watch", onWatch, Modifier.weight(1f), icon = Icons.Filled.PlayArrow, primary = true, fontSize = 15.sp, minHeight = 44.dp)
                JtvButton(
                    if (isFavorite) "Saved" else "Favourite", onFavorite, Modifier.weight(1f),
                    icon = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star, fontSize = 15.sp, minHeight = 44.dp,
                )
            }
            if (miniShowing) Spacer(Modifier.height(com.fenyx.jtv.ui.player.MiniCardWidth * 9f / 16f + com.fenyx.jtv.ui.player.MiniBarHeight + 16.dp))
        }
    }
}
