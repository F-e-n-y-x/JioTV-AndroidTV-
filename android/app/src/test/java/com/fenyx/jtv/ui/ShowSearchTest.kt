package com.fenyx.jtv.ui

import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.EpgProgram
import com.fenyx.jtv.ui.search.matchShows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowSearchTest {
    private fun ch(id: String, n: Int) = Channel(id, "Ch $id", "", "g", "", channelNumber = n)
    private fun p(title: String, start: Long, stop: Long) = EpgProgram(title, "", start, stop)

    private val channels = mapOf("a" to ch("a", 1), "b" to ch("b", 2))
    private val epg = mapOf(
        "a" to listOf(p("Old News", 0, 100), p("Cricket Live", 100, 200), p("Evening news", 200, 300), p("Movie", 300, 400)),
        "b" to listOf(p("Morning News", 50, 150), p("Cartoons", 150, 250)),
        "x" to listOf(p("News hidden", 0, 1000)), // not in scope
    )

    @Test fun matchesNowAndNextOnlyLiveFirst() {
        val hits = matchShows("news", channels, epg, now = 120)
        assertEquals(listOf("Morning News" to true, "Evening news" to false), hits.map { it.program.title to it.live })
        assertEquals("b", hits[0].channel.id)
    }

    @Test fun pastAndLaterShowsAndOutOfScopeIgnored() {
        assertTrue(matchShows("old", channels, epg, now = 120).isEmpty())
        assertTrue(matchShows("movie", channels, epg, now = 120).isEmpty())
        assertTrue(matchShows("hidden", channels, epg, now = 120).isEmpty())
    }

    @Test fun shortQueryMatchesNothing() {
        assertTrue(matchShows(" c ", channels, epg, now = 120).isEmpty())
    }
}
