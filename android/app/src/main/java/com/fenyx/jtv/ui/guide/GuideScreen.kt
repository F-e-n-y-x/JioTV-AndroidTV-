package com.fenyx.jtv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.ui.components.closeOnOutsideTap
import com.fenyx.jtv.ui.components.keepTapsInside
import com.fenyx.jtv.ui.components.ChannelPlate
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.JtvClickable
import com.fenyx.jtv.ui.components.JtvClock
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.formatDay
import com.fenyx.jtv.ui.components.formatTime
import com.fenyx.jtv.ui.components.numberStyle
import com.fenyx.jtv.ui.components.rememberMinuteClock
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Calendar

private const val MIN = 60_000L
private const val HALF_HOUR = 30 * MIN
private const val DAY = 24 * 60 * MIN

/** How far the guide may travel from now (catch-up data rarely goes further back than a day). */
private const val MAX_BACK = DAY
private const val MAX_AHEAD = 7 * DAY

/**
 * Programme guide ("Guide" in the v2 Everyday design, mockup D-tv-guide).
 *
 * Layout: title + day/category + clock, the focused programme line, a time ruler with an amber "now"
 * pill, then a grid: frozen channel column on the left, programmes laid out proportional to their
 * duration inside a sliding time window.
 *
 * Focus on TV is *virtual*: the whole grid is one focusable and a (row, anchor time) pair says which
 * cell is focused. That keeps the grid cheap on weak boxes (no focus node per cell) and makes Up/Down
 * keep the same point in time, like every broadcaster guide. Touch/mouse: drag the timeline sideways,
 * tap a programme for its details, tap a channel to watch it.
 *
 * @param onPlay called with the channel's index in [MainViewModel.getAllChannels] and the category
 *   (null for all channels) so the player zaps inside the same list.
 */
@Composable
fun GuideScreen(
    viewModel: MainViewModel,
    onPlay: (displayIndex: Int, group: String?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onTab: ((com.fenyx.jtv.ui.main.PhoneTab) -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val c = Jtv.colors
    val form = Jtv.form
    val isTv = form == FormFactor.Tv
    val isPhone = form == FormFactor.Phone

    val epgMode by settings.epgModeFlow.collectAsState(initial = null)
    val allChannels by viewModel.displayChannels.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val favoriteOrder by viewModel.favoriteOrder.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val now = rememberMinuteClock()

    LaunchedEffect(Unit) { viewModel.fetchChannels() }

    // ── Categories ──
    var category by rememberSaveable { mutableStateOf(MainViewModel.GROUP_ALL) }
    val categories = remember(groups, favoriteOrder.isEmpty()) {
        buildList {
            add(MainViewModel.GROUP_ALL)
            if (favoriteOrder.isNotEmpty()) add(MainViewModel.GROUP_FAVORITES)
            addAll(groups)
        }
    }
    LaunchedEffect(categories) { if (category !in categories) category = MainViewModel.GROUP_ALL }
    val channels = remember(category, allChannels, favoriteOrder) {
        viewModel.getChannelsByGroup(category).distinctBy { it.id }
    }
    val indexOf = remember(allChannels) { allChannels.withIndex().associate { (i, ch) -> ch.id to i } }
    val playGroup = category.takeIf { it != MainViewModel.GROUP_ALL }

    // ── Time window + virtual focus ──
    var viewStart by rememberSaveable { mutableLongStateOf(initialWindowStart(System.currentTimeMillis())) }
    var anchor by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var focusRow by rememberSaveable { mutableIntStateOf(0) }
    var gridFocused by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<DetailsRequest?>(null) }
    LaunchedEffect(channels.size) { if (focusRow >= channels.size) focusRow = (channels.size - 1).coerceAtLeast(0) }

    val epgData by viewModel.epgData.collectAsState()
    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }

    val gutter = if (isTv) 48.dp else 16.dp
    val topPad = if (isTv) 27.dp else 12.dp

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = gutter, vertical = topPad),
    ) {
        // ── Header: title · day · category · clock ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                JText("Guide", if (isTv) 28.sp else 24.sp, weight = FontWeight.Bold)
                JText(
                    "${dayLabel(viewStart + HALF_HOUR, now)} · ${categoryLabel(category)}",
                    if (isTv) 16.sp else 14.sp, color = c.t2,
                )
            }
            if (isTv && onTab != null) com.fenyx.jtv.ui.main.TvTabs(com.fenyx.jtv.ui.main.PhoneTab.Guide, onTab, Modifier.padding(end = 20.dp, top = 2.dp))
            // Phones show the time in the status bar.
            if (Jtv.form != com.fenyx.jtv.theme.FormFactor.Phone) JtvClock(now, size = if (isTv) 34.sp else 26.sp, dateColor = c.t2)
        }

        if (epgMode == null) return@Column // setting still loading; draw nothing rather than flash
        if (epgMode == false) {
            GuideOff(
                onTurnOn = { scope.launch { settings.setEpgMode(true) } },
                onOpenSettings = onOpenSettings,
            )
            return@Column
        }

        Spacer(Modifier.height(if (isTv) 10.dp else 8.dp))
        CategoryChips(
            categories = categories,
            selected = category,
            onSelect = {
                if (it != category) {
                    category = it
                    focusRow = 0
                    scope.launch { listState.scrollToItem(0) }
                }
            },
            selectedFocus = chipFocus,
            downTarget = gridFocus,
        )

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val frozen = if (isPhone) 104.dp else 200.dp
            val timelineDp = (maxWidth - frozen).coerceAtLeast(120.dp)
            val span = remember(timelineDp, isTv) { windowSpan(timelineDp, isTv) }
            val density = LocalDensity.current
            val timelinePx = with(density) { timelineDp.toPx() }
            val pxPerMs = timelinePx / span
            val rowH = if (isPhone) 64.dp else if (isTv) 56.dp else 64.dp

            fun clampStart(t: Long): Long = t.coerceIn(now - MAX_BACK, now + MAX_AHEAD - span)

            // Keep the anchor inside the window when the window is dragged (touch) or paged.
            fun showTime(t: Long) {
                var vs = viewStart
                val margin = span / 6
                while (t >= vs + span - margin) vs += HALF_HOUR
                while (t < vs + margin / 2 && t < vs) vs -= HALF_HOUR
                viewStart = clampStart(vs)
            }

            val rowPrograms: (Int) -> List<EpgProgram> = { r -> channels.getOrNull(r)?.let { epgData[it.id] } ?: emptyList() }
            val focusedChannel = channels.getOrNull(focusRow)
            val focusedProg = focusedChannel?.let { programAt(rowPrograms(focusRow), anchor) }

            fun activate(row: Int, prog: EpgProgram?) {
                val ch = channels.getOrNull(row) ?: return
                // There is no catch-up player path yet, so past and current shows both open the channel live.
                // Future shows can't be watched yet: show their details (with a "watch channel" button).
                if (prog != null && prog.startMs > now) details = DetailsRequest(row, prog)
                else onPlay(indexOf[ch.id] ?: return, playGroup)
            }

            Column(Modifier.fillMaxSize()) {
                // ── Focused programme line ── TV/tablet only. On touch the grid is the UI: drag the
                // timeline to move in time, tap a show for its details (owner: no extra header/buttons).
                if (Jtv.form != com.fenyx.jtv.theme.FormFactor.Phone) {
                    FocusLine(
                        channel = focusedChannel,
                        prog = focusedProg,
                        showButtons = false,
                        onWatch = { activate(focusRow, focusedProg?.takeIf { it.startMs <= now }) },
                        onDetails = { details = DetailsRequest(focusRow, focusedProg) },
                    )
                }

                // ── Time ruler ──
                TimeRuler(viewStart, span, now, frozen, timelineDp)

                if (channels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        JText(
                            if (isLoading || allChannels.isEmpty()) "Loading channels…" else "No channels in this category.",
                            20.sp, color = c.t2,
                        )
                    }
                    return@Column
                }

                // Fetch the native guide only for rows on screen (the view model caps parallel requests).
                LaunchedEffect(listState, channels) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }
                        .distinctUntilChanged()
                        .collectLatest { keys ->
                            kotlinx.coroutines.delay(120)
                            keys.forEach { (it as? String)?.let(viewModel::fetchNativeEpgIfMissing) }
                        }
                }

                // Keep the focused row on screen with one row of context (TV / keyboard).
                LaunchedEffect(focusRow, channels) {
                    val info = listState.layoutInfo
                    val vis = info.visibleItemsInfo
                    if (vis.isEmpty()) return@LaunchedEffect
                    val first = vis.first().index
                    val lastFull = vis.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: first
                    val count = (lastFull - first + 1).coerceAtLeast(1)
                    val target = when {
                        focusRow < first + 1 -> (focusRow - 1).coerceAtLeast(0)
                        focusRow > lastFull - 1 -> (focusRow - count + 2).coerceAtLeast(0)
                        else -> -1
                    }
                    if (target >= 0 && target != first) listState.scrollToItem(target)
                }

                var okLongFired by remember { mutableStateOf(false) }
                val nowLineColor = c.acc.copy(alpha = 0.35f)
                val nowX = (now - viewStart) * pxPerMs
                val frozenPx = with(density) { frozen.toPx() }
                val dragState = rememberDraggableState { delta ->
                    viewStart = clampStart(viewStart - (delta / pxPerMs).toLong())
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        // Now-line drawn OVER the cells (owner's choice), kept at low opacity so names stay readable.
                        .drawWithContent {
                            drawContent()
                            if (nowX in 0f..timelinePx) {
                                val w = 2.dp.toPx()
                                drawRect(nowLineColor, Offset(frozenPx + nowX - w / 2, 0f), Size(w, size.height))
                            }
                        }
                        .draggable(dragState, Orientation.Horizontal)
                        .focusRequester(gridFocus)
                        .onFocusChanged { gridFocused = it.hasFocus }
                        .onPreviewKeyEvent { e ->
                            val down = e.type == KeyEventType.KeyDown
                            val repeat = e.nativeKeyEvent.repeatCount
                            when (e.key) {
                                Key.DirectionRight -> {
                                    if (!down) return@onPreviewKeyEvent true
                                    val progs = rowPrograms(focusRow)
                                    val cur = programAt(progs, anchor)
                                    val next = progs.firstOrNull { it.startMs >= (cur?.stopMs ?: (anchor + 1)) && it.stopMs > anchor }
                                    val t = next?.startMs?.coerceAtLeast(viewStart) ?: (anchor + HALF_HOUR)
                                    if (t <= now + MAX_AHEAD - MIN) { anchor = t; showTime(t) }
                                    true
                                }
                                Key.DirectionLeft -> {
                                    if (!down) return@onPreviewKeyEvent true
                                    val progs = rowPrograms(focusRow)
                                    val cur = programAt(progs, anchor)
                                    val edge = cur?.startMs ?: anchor
                                    val prev = progs.lastOrNull { it.stopMs <= edge }
                                    if (prev != null) {
                                        // Page the window back until the previous show is comfortably visible.
                                        var vs = viewStart
                                        while (prev.stopMs <= vs + span / 6 && vs > prev.startMs) vs -= HALF_HOUR
                                        viewStart = clampStart(vs)
                                        anchor = prev.startMs.coerceAtLeast(viewStart)
                                    } else if (anchor - HALF_HOUR >= now - MAX_BACK) {
                                        anchor -= HALF_HOUR
                                        if (anchor < viewStart) viewStart = clampStart(viewStart - HALF_HOUR)
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (down && focusRow < channels.size - 1) focusRow++
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (down) {
                                        if (focusRow > 0) focusRow--
                                        else runCatching { chipFocus.requestFocus() }
                                    }
                                    true
                                }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    // Tap = watch; hold = details.
                                    if (down) {
                                        if (repeat == 0) okLongFired = false
                                        else if (!okLongFired) {
                                            okLongFired = true
                                            details = DetailsRequest(focusRow, programAt(rowPrograms(focusRow), anchor))
                                        }
                                    } else {
                                        if (!okLongFired) activate(focusRow, programAt(rowPrograms(focusRow), anchor))
                                        okLongFired = false
                                    }
                                    true
                                }
                                Key.Menu -> {
                                    if (down) details = DetailsRequest(focusRow, programAt(rowPrograms(focusRow), anchor))
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusable(),
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(channels, key = { _, ch -> ch.id }, contentType = { _, _ -> "guide-row" }) { row, ch ->
                            val progs = epgData[ch.id]
                            val isRowFocused = row == focusRow
                            GuideRow(
                                channel = ch,
                                programs = progs,
                                viewStart = viewStart,
                                span = span,
                                now = now,
                                pxPerMs = pxPerMs,
                                focusedStart = if (isRowFocused && (gridFocused || !isTv)) (programAt(progs ?: emptyList(), anchor)?.startMs ?: NO_PROG) else null,
                                rowFocused = isRowFocused,
                                frozen = frozen,
                                rowHeight = rowH,
                                compact = isPhone,
                                onTapChannel = {
                                    focusRow = row
                                    activate(row, null)
                                },
                                onTapTime = { t, long ->
                                    focusRow = row
                                    val p = programAt(progs ?: emptyList(), t)
                                    anchor = p?.startMs?.coerceAtLeast(viewStart) ?: t
                                    details = DetailsRequest(row, p)
                                },
                            )
                        }
                    }
                }

                if (isTv) {
                    Spacer(Modifier.height(6.dp))
                    KeyHint(
                        listOf("OK" to "watch", "Hold OK" to "details", "Up" to "categories", "Back" to "close guide"),
                    )
                }

                LaunchedEffect(Unit) { if (isTv) runCatching { gridFocus.requestFocus() } }
            }
        }
    }

    // Back on the grid after the details dialog closes (TV): focus doesn't always return by itself.
    var detailsWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(details == null) {
        if (details != null) detailsWasOpen = true
        else if (detailsWasOpen && isTv) runCatching { gridFocus.requestFocus() }
    }

    details?.let { req ->
        val ch = channels.getOrNull(req.row)
        if (ch == null) { details = null; return@let }
        GuideDetails(
            channel = ch,
            prog = req.prog,
            now = now,
            onWatch = {
                details = null
                indexOf[ch.id]?.let { onPlay(it, playGroup) }
            },
            onClose = { details = null },
        )
    }
}

private const val NO_PROG = Long.MIN_VALUE

private data class DetailsRequest(val row: Int, val prog: EpgProgram?)

/** Programme covering [t], or null for a gap / no guide data. */
private fun programAt(programs: List<EpgProgram>, t: Long): EpgProgram? =
    programs.firstOrNull { it.startMs <= t && t < it.stopMs }

/** Window opens 30 min before now (rounded to 5 min), like the mockup: you see what just started. */
private fun initialWindowStart(now: Long): Long {
    val t = now - HALF_HOUR
    return t - t % (5 * MIN)
}

/** 2.5 h on a TV; narrower screens show less time so cells stay readable (≥ ~3 dp per minute). */
private fun windowSpan(timeline: Dp, isTv: Boolean): Long {
    val dpPerMin = if (isTv) 4.4f else 3.2f
    val minutes = (timeline.value / dpPerMin).toInt().coerceIn(60, 150)
    return (minutes - minutes % 15) * MIN
}

private fun dayLabel(t: Long, now: Long): String {
    fun dayNo(ms: Long) = Calendar.getInstance().run { timeInMillis = ms; get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR) }
    return when (dayNo(t) - dayNo(now)) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> formatDay(t)
    }
}

private fun categoryLabel(group: String): String = when (group) {
    MainViewModel.GROUP_ALL -> "All channels"
    MainViewModel.GROUP_FAVORITES -> "Favourites"
    MainViewModel.GROUP_RECENT -> "Recent"
    else -> group
}

// ───────────────────────── Pieces ─────────────────────────

@Composable
private fun GuideOff(onTurnOn: () -> Unit, onOpenSettings: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(
                "Programme guide is off. Turn it on to see what's on later.",
                style = textStyle(22.sp, FontWeight.SemiBold),
                color = Jtv.colors.tx,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (Jtv.form == com.fenyx.jtv.theme.FormFactor.Phone) {
                // Narrow screen: stack full-width so neither label is cut off.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    JtvButton("Turn on programme guide", onTurnOn, Modifier.fillMaxWidth().focusRequester(first), primary = true, fontSize = 18.sp)
                    JtvButton("Open settings", onOpenSettings, Modifier.fillMaxWidth(), fontSize = 18.sp)
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JtvButton("Turn on programme guide", onTurnOn, Modifier.focusRequester(first), primary = true, fontSize = 18.sp)
                JtvButton("Open settings", onOpenSettings, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    selectedFocus: FocusRequester,
    downTarget: FocusRequester,
) {
    val c = Jtv.colors
    val isTv = Jtv.isTv
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { cat ->
            val isSel = cat == selected
            JtvClickable(
                onClick = { onSelect(cat) },
                modifier = Modifier
                    .heightIn(min = if (isTv) 40.dp else 56.dp)
                    .then(if (isSel) Modifier.focusRequester(selectedFocus) else Modifier)
                    .focusProperties { down = downTarget },
                container = if (isSel) c.s2 else androidx.compose.ui.graphics.Color.Transparent,
                focusedScale = 1.03f,
            ) { focused ->
                Text(
                    categoryLabel(cat),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 6.dp),
                    style = textStyle(16.sp, if (isSel) FontWeight.SemiBold else FontWeight.Normal),
                    color = when {
                        focused -> c.invTx
                        isSel -> c.tx
                        else -> c.t2
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FocusLine(
    channel: Channel?,
    prog: EpgProgram?,
    showButtons: Boolean,
    onWatch: () -> Unit,
    onDetails: () -> Unit,
) {
    val c = Jtv.colors
    val isPhone = Jtv.form == FormFactor.Phone
    val meta = buildString {
        if (channel != null) {
            if (channel.channelNumber > 0) append("${channel.channelNumber} ")
            append(channel.name)
        }
        if (prog != null) append(" · ${formatTime(prog.startMs)} – ${formatTime(prog.stopMs)}")
    }
    val title = prog?.title ?: if (channel != null) "No guide for this channel" else ""
    if (isPhone) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
            JText(title, 20.sp, weight = FontWeight.Bold)
            JText(meta, 16.sp, color = c.t2)
            if (showButtons) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JtvButton("Watch", onWatch, primary = true)
                    JtvButton("Details", onDetails)
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = textStyle(22.sp, FontWeight.Bold), color = c.tx, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                JText(meta, 16.sp, color = c.t2)
            }
            if (showButtons) {
                JtvButton("Watch", onWatch, primary = true)
                JtvButton("Details", onDetails)
            }
        }
    }
}

@Composable
private fun TimeButtons(onEarlier: () -> Unit, onNow: () -> Unit, onLater: () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JtvButton("Earlier", onEarlier, minHeight = 56.dp)
        JtvButton("Now", onNow, minHeight = 56.dp)
        JtvButton("Later", onLater, minHeight = 56.dp)
    }
}

@Composable
private fun TimeRuler(viewStart: Long, span: Long, now: Long, frozen: Dp, timeline: Dp) {
    val c = Jtv.colors
    val dpPerMs = timeline.value / span
    Row(Modifier.fillMaxWidth().height(30.dp)) {
        Spacer(Modifier.width(frozen))
        Box(Modifier.width(timeline).fillMaxHeight().clipToBounds()) {
            val nowX = (now - viewStart) * dpPerMs
            val showPill = now in viewStart until viewStart + span
            var k = viewStart - viewStart % HALF_HOUR
            if (k < viewStart) k += HALF_HOUR
            while (k < viewStart + span) {
                val x = (k - viewStart) * dpPerMs
                // Skip a tick label that would sit under the now pill.
                if (!showPill || kotlin.math.abs(x - nowX) > 52f) {
                    Text(
                        formatTime(k),
                        modifier = Modifier.offset(x = x.dp - 2.dp).align(Alignment.CenterStart),
                        style = textStyle(14.sp),
                        color = c.t2,
                        maxLines = 1,
                    )
                }
                k += HALF_HOUR
            }
            if (showPill) {
                Box(
                    Modifier
                        .offset(x = (nowX - 26f).coerceAtLeast(0f).dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(4.dp))
                        .background(c.acc)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(formatTime(now), style = numberStyle(14.sp), color = c.accTx, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<EpgProgram>?,
    viewStart: Long,
    span: Long,
    now: Long,
    pxPerMs: Float,
    /** Start of the focused programme in this row, [NO_PROG] for a focused gap/empty row, null = not focused. */
    focusedStart: Long?,
    rowFocused: Boolean,
    frozen: Dp,
    rowHeight: Dp,
    compact: Boolean,
    onTapChannel: () -> Unit,
    onTapTime: (t: Long, long: Boolean) -> Unit,
) {
    val c = Jtv.colors
    val density = LocalDensity.current
    val viewEnd = viewStart + span
    val tapTime by rememberUpdatedState(onTapTime)
    val tapChannel by rememberUpdatedState(onTapChannel)
    val toTime by rememberUpdatedState { x: Float -> viewStart + (x / pxPerMs).toLong() }

    Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        // ── Frozen channel column ──
        Row(
            Modifier
                .width(frozen)
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures(onTap = { tapChannel() }) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        ) {
            Text(
                if (channel.channelNumber > 0) channel.channelNumber.toString() else "",
                modifier = Modifier.width(if (compact) 40.dp else 44.dp),
                style = numberStyle(if (compact) 16.sp else 18.sp),
                color = c.tx,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            ChannelPlate(channel.logoUrl, if (compact) 48.dp else 46.dp, if (compact) 28.dp else 27.dp)
            if (!compact) {
                JText(channel.name, 16.sp, color = if (rowFocused) c.tx else c.t2, weight = if (rowFocused) FontWeight.SemiBold else FontWeight.Normal)
            }
        }

        // ── Programme cells (absolute offsets, only inside the window) ──
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tapTime(toTime(it.x), false) },
                        onLongPress = { tapTime(toTime(it.x), true) },
                    )
                },
        ) {
            val visible = programs?.filter { it.stopMs > viewStart && it.startMs < viewEnd }.orEmpty()
            if (visible.isEmpty()) {
                val focused = focusedStart != null
                Cell(
                    title = if (programs.isNullOrEmpty()) "No guide for this channel" else "Nothing scheduled",
                    time = null,
                    xDp = 0.dp,
                    widthDp = with(density) { (span * pxPerMs).toDp() },
                    focused = focused,
                    onNow = false,
                    past = false,
                    muted = true,
                )
            } else {
                visible.forEach { p ->
                    val s = maxOf(p.startMs, viewStart)
                    val e = minOf(p.stopMs, viewEnd)
                    val x = with(density) { ((s - viewStart) * pxPerMs).toDp() }
                    val w = with(density) { ((e - s) * pxPerMs).toDp() }
                    Cell(
                        title = p.title,
                        time = formatTime(p.startMs),
                        xDp = x,
                        widthDp = w,
                        focused = focusedStart != null && focusedStart == p.startMs,
                        onNow = p.startMs <= now && now < p.stopMs,
                        past = p.stopMs <= now,
                        muted = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(
    title: String,
    time: String?,
    xDp: Dp,
    widthDp: Dp,
    focused: Boolean,
    onNow: Boolean,
    past: Boolean,
    muted: Boolean,
) {
    val c = Jtv.colors
    val w = (widthDp - 3.dp).coerceAtLeast(2.dp)
    val bg = when {
        focused -> c.inv
        onNow -> c.s2
        else -> c.s1
    }
    val fg = if (focused) c.invTx else if (muted) c.t2 else c.tx
    Box(
        Modifier
            .offset(x = xDp)
            .width(w)
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .then(if (past && !focused) Modifier.alpha(0.5f) else Modifier)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (w > 36.dp) {
            Column {
                JText(title, 16.sp, color = fg, weight = if (muted) FontWeight.Normal else FontWeight.SemiBold)
                if (time != null) JText(time, 14.sp, color = if (focused) c.invTx.copy(alpha = 0.75f) else c.t2)
            }
        }
    }
}

@Composable
private fun GuideDetails(
    channel: Channel,
    prog: EpgProgram?,
    now: Long,
    onWatch: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Jtv.colors
    val isPhone = Jtv.form == FormFactor.Phone
    val watchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { watchFocus.requestFocus() } }
    // Opened by holding OK: the release of that same press must not "click" Watch. Swallow OK events
    // until a fresh OK press starts inside the dialog.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed = true
                        !armed
                    } else false
                }
                .background(c.bg.copy(alpha = 0.8f))
                .then(if (Jtv.isTv) Modifier else Modifier.closeOnOutsideTap(onClose))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .then(if (Jtv.isTv) Modifier else Modifier.keepTapsInside())
                    .then(if (isPhone) Modifier.fillMaxWidth() else Modifier.width(600.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.s1)
                    .padding(24.dp),
            ) {
                Text(
                    prog?.title ?: channel.name,
                    style = textStyle(26.sp, FontWeight.Bold), color = c.tx, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                val chLine = (if (channel.channelNumber > 0) "${channel.channelNumber} " else "") + channel.name
                JText(chLine, 18.sp, color = c.t2)
                if (prog != null) {
                    val state = when {
                        prog.stopMs <= now -> "Already shown"
                        prog.startMs <= now -> "On now"
                        else -> "Starts at ${formatTime(prog.startMs)}"
                    }
                    JText(
                        "${dayLabel(prog.startMs, now)} · ${formatTime(prog.startMs)} – ${formatTime(prog.stopMs)} · $state",
                        18.sp, color = c.t2,
                    )
                    if (prog.description.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            Text(prog.description, style = textStyle(18.sp), color = c.t2)
                        }
                    }
                } else {
                    JText("No programme information.", 18.sp, color = c.t2)
                }
                Spacer(Modifier.height(22.dp))
                val onNow = prog != null && prog.startMs <= now && now < prog.stopMs
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JtvButton(
                        if (onNow || prog == null) "Watch" else "Watch channel live",
                        onWatch, Modifier.focusRequester(watchFocus), primary = true, fontSize = 18.sp,
                    )
                    JtvButton("Close", onClose, fontSize = 18.sp)
                }
            }
        }
    }
}
