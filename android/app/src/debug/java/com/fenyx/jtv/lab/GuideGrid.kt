package com.fenyx.jtv.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram

private const val HALF_HOUR = 30 * 60 * 1000L

/** What a timeline cell needs to draw itself; the direction decides the look. */
class GuideCell(val program: EpgProgram, val channel: Channel, val isNow: Boolean, val isPast: Boolean, val width: Dp)

/**
 * A time-window EPG grid shared by directions A and B. The window moves in 30-minute steps (no
 * free horizontal scrolling: cheaper on weak boxes and predictable with a D-pad): pressing → on a
 * cell that reaches the window's end, or ← at its start, shifts the window. Touch/mouse use the
 * header's Earlier/Later controls.
 */
@Composable
fun GuideGrid(
    state: LabScreenState,
    channels: List<Channel>,
    channelColumn: Dp,
    windowMinutes: Int,
    rowHeight: Dp,
    rowGap: Dp,
    background: Color,
    nowLineColor: Color,
    header: @Composable (windowStart: Long, dpPerMin: Float, onEarlier: () -> Unit, onLater: () -> Unit) -> Unit,
    channelCell: @Composable (Channel, Modifier) -> Unit,
    programCell: @Composable (GuideCell, Modifier) -> Unit,
    emptyCell: @Composable (Modifier) -> Unit,
) {
    val epg by state.vm.epgData.collectAsState()
    val windowMs = windowMinutes * 60_000L
    var windowStart by remember { mutableLongStateOf(state.now - state.now % HALF_HOUR) }
    val minStart = state.now - state.now % HALF_HOUR - 6 * 60 * 60_000L

    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val dpPerMin = ((maxWidth - channelColumn).value / windowMinutes).coerceAtLeast(1f)
        val windowEnd = windowStart + windowMs
        val earlier = { windowStart = (windowStart - HALF_HOUR).coerceAtLeast(minStart) }
        val later = { windowStart += HALF_HOUR }
        Column(Modifier.fillMaxSize()) {
            header(windowStart, dpPerMin, earlier, later)
            Box(Modifier.weight(1f)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                    items(channels, key = { it.id }) { ch ->
                        val programs = programsOf(state.vm, epg, ch.id, true)
                        Row(Modifier.fillMaxWidth().height(rowHeight)) {
                            channelCell(ch, Modifier.width(channelColumn).fillMaxHeight())
                            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                                val visible = programs.filter { it.stopMs > windowStart && it.startMs < windowEnd }
                                if (visible.isEmpty()) emptyCell(Modifier.fillMaxSize())
                                visible.forEach { p ->
                                    val s = maxOf(p.startMs, windowStart)
                                    val e = minOf(p.stopMs, windowEnd)
                                    val x = ((s - windowStart) / 60_000f * dpPerMin).dp
                                    val w = ((e - s) / 60_000f * dpPerMin).dp
                                    val reachesEnd = p.stopMs >= windowEnd
                                    val reachesStart = p.startMs <= windowStart
                                    val cell = GuideCell(p, ch, p.startMs <= state.now && p.stopMs > state.now, p.stopMs <= state.now, w)
                                    programCell(
                                        cell,
                                        Modifier.offset(x = x).width(w).fillMaxHeight().onPreviewKeyEvent { ev ->
                                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when {
                                                ev.key == Key.DirectionRight && reachesEnd -> { later(); true }
                                                ev.key == Key.DirectionLeft && reachesStart && windowStart > minStart -> { earlier(); true }
                                                else -> false
                                            }
                                        }
                                    )
                                }
                                // The "now" line, drawn once per row (no animation).
                                if (state.now in windowStart until windowEnd) {
                                    val nx = ((state.now - windowStart) / 60_000f * dpPerMin).dp
                                    Box(Modifier.offset(x = nx).width(2.dp).fillMaxHeight().background(nowLineColor))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Header tick labels: one every 30 minutes across the window. */
fun ticks(windowStart: Long, windowMinutes: Int): List<Long> =
    (0 until windowMinutes step 30).map { windowStart + it * 60_000L }
