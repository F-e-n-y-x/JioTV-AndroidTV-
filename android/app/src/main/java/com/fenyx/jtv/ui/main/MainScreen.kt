package com.fenyx.jtv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.fenyx.jtv.theme.Surface
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onChannelClick: (Int, String?) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onDesignLabClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val channels by viewModel.channels.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()

    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val epgMode by settingsManager.epgModeFlow.collectAsState(initial = false)
    val epgData by viewModel.epgData.collectAsState()
    val favoriteChannels by viewModel.favoriteChannels.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.fetchChannels()
    }
    
    // EPG is driven by Jio's NATIVE per-channel guide (reliable, keyed by channel_id, correct ms
    // epochs) — filled per visible row below via fetchNativeEpgIfMissing. We intentionally do NOT
    // auto-download/parse the XMLTV source here: the default source's IDs (ts…/sun…) don't map to Jio
    // channel_ids so it shows nothing, and parsing its ~19 MB file on every EPG entry hammered weak TVs.
    // The XMLTV path stays available only via the manual "Refresh EPG Data" button in Settings, for
    // users who point EPG Source URL at a Jio-ID-keyed feed.

    // Single shared 30s clock for every EPG row. Previously each visible row ran its own
    // `while(true){ delay(30s) }` ticker and recomposed independently — on a full EPG screen that was
    // ~20 coroutines + 20 separate recomposition passes. One hoisted clock is far lighter on weak CPUs.
    var epgNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(epgMode) {
        if (epgMode) {
            while (true) {
                epgNow = System.currentTimeMillis()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    Row(modifier = modifier.fillMaxSize().background(TvDarkBackground)) {

        // ─── Left Sidebar (Category Navigation) ───
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(210.dp)
                .background(TvDarkSurface)
                // focusGroup so D-pad Left/Right treats the sidebar as one cluster (predictable
                // traversal to/from the grid instead of geometry-based zig-zag).
                .focusGroup()
                // Overscan-safe top/bottom + a small left inset so focused labels stay in the safe area.
                .padding(start = 12.dp, top = TvDimens.OverscanVertical, bottom = TvDimens.OverscanVertical)
        ) {


            // Search entry — a D-pad-friendly way to find one of ~1300 channels by name.
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                onClick = onSearchClick,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = TvDarkSurfaceVariant
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, TvFocusBorder),
                        shape = RoundedCornerShape(8.dp)
                    )
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = TvPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Search", color = TvOnSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            // "All" + "Favorites" pseudo-categories precede the real Jio categories.
            val sidebarGroups = remember(groups, favoriteChannels) {
                buildList {
                    add(MainViewModel.GROUP_ALL)
                    if (favoriteChannels.isNotEmpty()) add(MainViewModel.GROUP_FAVORITES)
                    addAll(groups)
                }
            }

            // Category list. focusRestorer remembers the last-focused category so returning to the
            // sidebar lands where you left it, not back at the top.
            LazyColumn(modifier = Modifier.weight(1f).focusRestorer()) {
                items(sidebarGroups) { group ->
                    val isSelected = selectedGroup == group ||
                        (selectedGroup == null && group == MainViewModel.GROUP_ALL)
                    val label = when (group) {
                        MainViewModel.GROUP_ALL -> "All"
                        MainViewModel.GROUP_FAVORITES -> "★ Favorites (${favoriteChannels.size})"
                        else -> group
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        onClick = { viewModel.setSelectedGroup(group) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        // Full-width sidebar rows can't scale without clipping, so the focus cue is a
                        // bright border + fill (clearly visible at 10 feet).
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) TvPrimaryContainer.copy(alpha = 0.3f) else Color.Transparent,
                            focusedContainerColor = TvDarkSurfaceVariant
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(2.dp, TvFocusBorder),
                                shape = RoundedCornerShape(8.dp)
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                text = label,
                                color = if (isSelected) TvPrimary else TvOnSurface,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Bottom actions
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 16.dp)
                    .background(TvDarkSurfaceVariant)
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Refresh
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                onClick = { viewModel.retry() },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = TvDarkSurfaceVariant
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, TvFocusBorder),
                        shape = RoundedCornerShape(8.dp)
                    )
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TvOnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Refresh", color = TvOnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Debug builds only: entry to the v2 Design Lab prototypes.
            if (onDesignLabClick != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    onClick = onDesignLabClick,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = TvDarkSurfaceVariant)
                ) {
                    Text("Design lab (v2)", color = TvOnSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }

            // Settings
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                onClick = onSettingsClick,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = TvDarkSurfaceVariant
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, TvFocusBorder),
                        shape = RoundedCornerShape(8.dp)
                    )
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TvOnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Settings", color = TvOnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ─── Right Content Area (Channel Grid) ───
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = TvPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading channels...", color = TvOnSurfaceVariant)
                }
            } else if (error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⚠", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error!!,
                        color = TvOnSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Surface(
                        onClick = { viewModel.retry() },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = TvPrimaryContainer,
                            focusedContainerColor = TvPrimary
                        )
                    ) {
                        Text(
                            "Retry",
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                val filteredChannels by viewModel.filteredChannels.collectAsState()
                val isFavoritesGroup = selectedGroup == MainViewModel.GROUP_FAVORITES
                val scope = rememberCoroutineScope()

                // ── Favorites reorder state ──
                // While moving, the grid shows `workingOrder` (a local copy) so every arrow press is
                // instant; it's written to DataStore once on OK. Back discards it.
                var movingId by remember { mutableStateOf<String?>(null) }
                var workingOrder by remember { mutableStateOf<List<com.fenyx.jtv.data.Channel>?>(null) }
                var menuChannel by remember { mutableStateOf<com.fenyx.jtv.data.Channel?>(null) }
                val movingFocus = remember { FocusRequester() }
                val shown = workingOrder ?: filteredChannels

                fun cancelMove() { movingId = null; workingOrder = null }
                fun commitMove() {
                    workingOrder?.let { list -> viewModel.saveFavoriteOrder(list.map { it.id }) }
                    cancelMove()
                }
                fun startMove(channel: com.fenyx.jtv.data.Channel) {
                    workingOrder = filteredChannels
                    movingId = channel.id
                }
                // Leaving Favorites (or the list changing underneath) ends a move without saving.
                LaunchedEffect(selectedGroup) { if (!isFavoritesGroup) cancelMove() }
                androidx.activity.compose.BackHandler(enabled = movingId != null) { cancelMove() }

                // Pre-compute channel index map once (O(n)) instead of indexOf per item (O(n²))
                val allChannels = viewModel.getAllChannels()
                val channelIndexMap = remember(allChannels) {
                    allChannels.withIndex().associate { (i, ch) -> ch.id to i }
                }

                // Initial focus: drop focus onto the first channel once per screen entry after the list
                // appears, so the first D-pad press works — and so returning from the player (which
                // recomposes Home fresh) re-establishes focus instead of leaving the remote dead.
                // Uses plain `remember` (not rememberSaveable) so each fresh entry re-requests; the guard
                // stops category switches within one entry from yanking focus back to the grid.
                val firstItemFocus = remember { FocusRequester() }
                var initialFocusDone by remember { mutableStateOf(false) }
                LaunchedEffect(filteredChannels.isNotEmpty()) {
                    if (!initialFocusDone && filteredChannels.isNotEmpty()) {
                        runCatching { firstItemFocus.requestFocus() }
                        initialFocusDone = true
                    }
                }

                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()

                // Keep the moving card on screen and focused after each step.
                LaunchedEffect(workingOrder) {
                    val id = movingId ?: return@LaunchedEffect
                    val index = shown.indexOfFirst { it.id == id }
                    if (index < 0) return@LaunchedEffect
                    val visible = if (epgMode) listState.layoutInfo.visibleItemsInfo.map { it.index }
                                  else gridState.layoutInfo.visibleItemsInfo.map { it.index }
                    val fullyInside = visible.size > 2 && index > visible.first() && index < visible.last()
                    if (!fullyInside) {
                        if (epgMode) listState.scrollToItem((index - 1).coerceAtLeast(0))
                        else gridState.scrollToItem(index)
                    }
                    androidx.compose.runtime.withFrameNanos { }
                    runCatching { movingFocus.requestFocus() }
                }

                // D-pad handling while moving. Columns come from the live grid layout so ▲/▼ jump a
                // whole row whatever the screen size; the EPG list is a single column.
                val moveKeys = Modifier.onPreviewKeyEvent { event ->
                    val id = movingId ?: return@onPreviewKeyEvent false
                    val list = workingOrder ?: return@onPreviewKeyEvent false
                    val isOk = event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter
                    if (isOk) {
                        if (event.type == KeyEventType.KeyUp) commitMove()
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val columns = if (epgMode) 1
                        else (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1
                    val delta = when (event.key) {
                        Key.DirectionLeft -> if (epgMode) return@onPreviewKeyEvent true else -1
                        Key.DirectionRight -> if (epgMode) return@onPreviewKeyEvent true else 1
                        Key.DirectionUp -> -columns
                        Key.DirectionDown -> columns
                        else -> return@onPreviewKeyEvent false
                    }
                    val from = list.indexOfFirst { it.id == id }
                    val to = (from + delta).coerceIn(0, list.lastIndex)
                    if (from >= 0 && to != from) workingOrder = com.fenyx.jtv.data.FavoriteOrder.move(list, from, to)
                    true
                }

                // Card click: normally opens the player; while moving, a mouse/touch tap on another
                // card drops the moving channel into that slot (remote OK is handled in moveKeys).
                fun onCardClick(channel: com.fenyx.jtv.data.Channel) {
                    val id = movingId
                    if (id != null) {
                        val list = workingOrder ?: return
                        val from = list.indexOfFirst { it.id == id }
                        val to = list.indexOfFirst { it.id == channel.id }
                        if (from >= 0 && to >= 0) workingOrder = com.fenyx.jtv.data.FavoriteOrder.move(list, from, to)
                        commitMove()
                        return
                    }
                    onChannelClick(channelIndexMap[channel.id] ?: 0, selectedGroup)
                }

                fun actionsFor(channel: com.fenyx.jtv.data.Channel): List<ChannelAction> = buildList {
                    val isFav = favoriteChannels.contains(channel.id)
                    add(ChannelAction("▶  Watch") { onCardClick(channel) })
                    if (isFavoritesGroup && filteredChannels.size > 1) {
                        val index = filteredChannels.indexOfFirst { it.id == channel.id }
                        add(ChannelAction("⇅  Move", "Use the arrow keys to place it, then press OK") { startMove(channel) })
                        if (index > 0) add(ChannelAction("⤒  Move to top") {
                            viewModel.saveFavoriteOrder(com.fenyx.jtv.data.FavoriteOrder.move(filteredChannels, index, 0).map { it.id })
                        })
                        if (index in 0 until filteredChannels.lastIndex) add(ChannelAction("⤓  Move to bottom") {
                            viewModel.saveFavoriteOrder(com.fenyx.jtv.data.FavoriteOrder.move(filteredChannels, index, filteredChannels.lastIndex).map { it.id })
                        })
                        add(ChannelAction("▦  Group favorites by category", "Categories keep the order they first appear in") {
                            viewModel.groupFavoritesByCategory()
                        })
                    }
                    add(
                        if (isFav) ChannelAction("☆  Remove from Favorites") { viewModel.toggleFavorite(channel.id) }
                        else ChannelAction("★  Add to Favorites") { viewModel.toggleFavorite(channel.id) }
                    )
                }

                menuChannel?.let { ch ->
                    ChannelActionsDialog(channel = ch, actions = actionsFor(ch), onDismiss = { menuChannel = null })
                }

                fun itemModifier(index: Int, channel: com.fenyx.jtv.data.Channel): Modifier = when {
                    channel.id == movingId -> Modifier.focusRequester(movingFocus)
                    index == 0 -> Modifier.focusRequester(firstItemFocus)
                    else -> Modifier
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    val movingChannel = movingId?.let { id -> shown.firstOrNull { it.id == id } }
                    if (movingChannel != null) {
                        MoveBanner(
                            channelName = movingChannel.name,
                            position = shown.indexOf(movingChannel) + 1,
                            total = shown.size,
                            isGrid = !epgMode,
                            modifier = Modifier.padding(start = TvDimens.SpaceMd, end = TvDimens.OverscanHorizontal, top = TvDimens.OverscanVertical)
                        )
                    } else if (isFavoritesGroup && shown.isNotEmpty()) {
                        FavoritesHint(
                            modifier = Modifier.padding(start = TvDimens.SpaceMd + 4.dp, end = TvDimens.OverscanHorizontal, top = TvDimens.OverscanVertical)
                        )
                    }
                    val topPad = if (movingChannel != null || isFavoritesGroup) 12.dp else TvDimens.OverscanVertical

                    if (shown.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No channels in this category", color = TvOnSurfaceVariant)
                        }
                    } else if (epgMode) {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.focusRestorer().then(moveKeys),
                            contentPadding = PaddingValues(
                                start = TvDimens.SpaceMd, end = TvDimens.OverscanHorizontal,
                                top = topPad, bottom = TvDimens.OverscanVertical
                            )
                        ) {
                            itemsIndexed(items = shown, key = { _, ch -> ch.id }) { index, channel ->
                                val programs = epgData[channel.id] ?: emptyList()
                                LaunchedEffect(channel.id) {
                                    if (programs.isEmpty()) {
                                        viewModel.fetchNativeEpgIfMissing(channel.id)
                                    }
                                }

                                EpgChannelRow(
                                    channel = channel,
                                    epgPrograms = programs,
                                    now = epgNow,
                                    onClick = { onCardClick(channel) },
                                    onLongClick = { if (movingId == null) menuChannel = channel },
                                    isMoving = channel.id == movingId,
                                    modifier = itemModifier(index, channel).then(
                                        if (movingId != null) Modifier.animateItem() else Modifier
                                    )
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(150.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            // focusRestorer keeps your place in the grid when you leave and come back
                            // (e.g. return from the player), instead of snapping to the first card.
                            modifier = Modifier.focusRestorer().then(moveKeys),
                            // Overscan-safe: extra room on the right/top/bottom so focused cards (which
                            // scale up) and the last column aren't clipped by the panel edge.
                            contentPadding = PaddingValues(
                                start = TvDimens.SpaceMd, end = TvDimens.OverscanHorizontal,
                                top = topPad, bottom = TvDimens.OverscanVertical
                            )
                        ) {
                            itemsIndexed(items = shown, key = { _, ch -> ch.id }) { index, channel ->
                                ChannelCard(
                                    channel = channel,
                                    onClick = { onCardClick(channel) },
                                    onLongClick = { if (movingId == null) menuChannel = channel },
                                    isFavorite = !isFavoritesGroup && favoriteChannels.contains(channel.id),
                                    isMoving = channel.id == movingId,
                                    modifier = itemModifier(index, channel).then(
                                        if (movingId != null) Modifier.animateItem() else Modifier
                                    )
                                )
                            }
                        }
                    }
                }
            }


        }
    }
}

@Composable
fun ChannelCard(
    channel: com.fenyx.jtv.data.Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    isMoving: Boolean = false
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        // Clear 10-foot focus cue: the card scales up (was disabled at 1.0f) plus the focus border.
        // The card being moved is "lifted": bigger, accent-filled and always outlined.
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (isMoving) 1.12f else TvDimens.FocusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isMoving) TvPrimaryContainer.copy(alpha = 0.5f) else TvDarkSurface,
            focusedContainerColor = if (isMoving) TvPrimaryContainer.copy(alpha = 0.6f) else TvDarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (isMoving) androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, TvPrimary),
                shape = RoundedCornerShape(12.dp)
            ) else androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(if (isMoving) 3.dp else 2.dp, if (isMoving) Color.White else TvFocusBorder),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        if (isMoving || isFavorite) {
            Text(
                if (isMoving) "⇅" else "★",
                color = if (isMoving) Color.White else androidx.compose.ui.graphics.Color(0xFFFFC107),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Channel logo
            if (channel.logoUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(channel.logoUrl)
                        .size(112) // Downsample to 2x display size (56dp) to save memory
                        .build(),
                    contentDescription = channel.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TvDarkSurfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TvDarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = TvOnSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Channel name
            Text(
                channel.name,
                style = MaterialTheme.typography.bodySmall,
                color = TvOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )

            // LIVE badge
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(TvLiveRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "LIVE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvLiveRed,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun EpgChannelRow(
    channel: com.fenyx.jtv.data.Channel,
    epgPrograms: List<com.fenyx.jtv.data.EpgProgram>,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isMoving: Boolean = false
) {
    val context = LocalContext.current
    // Reuse a single formatter instance instead of allocating per-recomposition
    val timeFormat = remember { java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()) }
    // `now` is a single shared 30s clock hoisted to MainScreen (one ticker for the whole list).
    val currentProgram = remember(epgPrograms, now) { epgPrograms.find { it.startMs <= now && it.stopMs > now } }
    val nextPrograms = remember(epgPrograms, now) { epgPrograms.filter { it.startMs > now }.take(3) }

    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 100.dp),
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isMoving) TvPrimaryContainer.copy(alpha = 0.45f) else TvDarkSurface,
            focusedContainerColor = if (isMoving) TvPrimaryContainer.copy(alpha = 0.55f) else TvDarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(if (isMoving) 3.dp else 2.dp, if (isMoving) Color.White else TvFocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo and Name
            Column(
                modifier = Modifier.width(100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (channel.logoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(channel.logoUrl).size(96).build(),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(TvDarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = channel.name.take(1),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    channel.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = TvOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Timeline
            Row(modifier = Modifier.weight(1f)) {
                if (currentProgram != null) {
                    val progress = ((now - currentProgram.startMs).toFloat() / (currentProgram.stopMs - currentProgram.startMs)).coerceIn(0f, 1f)
                    
                    Box(
                        modifier = Modifier
                            .weight(0.45f)
                            .background(TvPrimaryContainer.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .border(1.dp, TvPrimary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TvLiveRed))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("NOW PLAYING", color = TvLiveRed, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(currentProgram.title, color = TvOnBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
                                color = TvPrimary,
                                trackColor = TvDarkSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${timeFormat.format(java.util.Date(currentProgram.startMs))} - ${timeFormat.format(java.util.Date(currentProgram.stopMs))}", color = TvOnSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                } else if (epgPrograms.isEmpty()) {
                    Text("No EPG Data Available", color = TvOnSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                }
                
                nextPrograms.forEach { prog ->
                    Box(
                        modifier = Modifier
                            .weight(0.25f)
                            .padding(end = 8.dp)
                            .background(TvDarkBackground.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(prog.title, color = TvOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(timeFormat.format(java.util.Date(prog.startMs)), color = TvOnSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
