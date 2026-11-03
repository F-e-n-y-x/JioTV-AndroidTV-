package com.fenyx.jtv.ui.search

import androidx.compose.runtime.Immutable
import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.ui.components.nowNext

/** One "Shows" search hit: [program] is on [channel] now ([live]) or next. */
@Immutable
data class ShowHit(val channel: Channel, val program: EpgProgram, val live: Boolean)

/**
 * Current and next programmes whose title contains [query], from the guide already in memory.
 * Only channels in [channelsById] count (the active search scope). Live shows first, then by start time.
 */
fun matchShows(
    query: String,
    channelsById: Map<String, Channel>,
    epg: Map<String, List<EpgProgram>>,
    now: Long,
    limit: Int = 60,
): List<ShowHit> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    val hits = ArrayList<ShowHit>()
    for ((id, programs) in epg) {
        val ch = channelsById[id] ?: continue
        val nn = nowNext(programs, now)
        nn.now?.takeIf { it.title.contains(q, ignoreCase = true) }?.let { hits += ShowHit(ch, it, live = true) }
        nn.next?.takeIf { it.title.contains(q, ignoreCase = true) }?.let { hits += ShowHit(ch, it, live = false) }
    }
    return hits.sortedWith(compareBy<ShowHit> { !it.live }.thenBy { it.program.startMs }.thenBy { it.channel.channelNumber })
        .take(limit)
}
