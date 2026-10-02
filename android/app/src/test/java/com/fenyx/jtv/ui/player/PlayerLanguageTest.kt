package com.fenyx.jtv.ui.player

import com.fenyx.jtv.data.Channel
import com.fenyx.jtv.data.ChannelLanguage
import com.fenyx.jtv.data.EpgRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLanguageTest {
    private fun ch(id: String) = Channel(id, "Ch $id", "", "Movies", "")

    @Test fun hdMasterSameLanguageTwiceShowsOnce() {
        val r = buildLanguageChoices(
            listOf(StreamAudio(0, "hi", "Hindi", false), StreamAudio(1, "hi", "Hindi", true)),
            emptyList(), "1", null, "Hindi",
        )
        assertEquals(listOf("a:hi" to "Hindi"), r.options)
        assertEquals("a:hi", r.current)
    }

    @Test fun threeLetterCodesNormalise() {
        val r = buildLanguageChoices(
            listOf(StreamAudio(0, "hin", null, true), StreamAudio(1, "hi-IN", null, false), StreamAudio(2, "eng", null, false)),
            emptyList(), "1", null, "Hindi",
        )
        assertEquals(listOf("a:hi" to "Hindi", "a:en" to "English"), r.options)
    }

    @Test fun singleUnnamedIsOriginalSound() {
        val r = buildLanguageChoices(listOf(StreamAudio(0, null, null, true)), emptyList(), "1", null, "Hindi")
        assertEquals(listOf("t:0" to "Original sound"), r.options)
        assertEquals("Original sound", r.label)
    }

    @Test fun severalUnnamedAreNumbered() {
        val r = buildLanguageChoices(
            listOf(StreamAudio(0, "und", null, true), StreamAudio(1, null, "", false)), emptyList(), "1", null, "x",
        )
        assertEquals(listOf("t:0" to "Sound 1", "t:1" to "Sound 2"), r.options)
    }

    @Test fun siblingsDedupedAgainstStream() {
        val variants = listOf(
            ChannelLanguage.Variant("hi", ch("1")),
            ChannelLanguage.Variant("ta", ch("2")),
            ChannelLanguage.Variant("en", ch("3")),
        )
        val r = buildLanguageChoices(
            listOf(StreamAudio(0, "hi", "Hindi", true), StreamAudio(1, "en", "English", false)),
            variants, "1", "hi", "Hindi",
        )
        assertEquals(listOf("a:hi" to "Hindi", "a:en" to "English", "c:2" to "Tamil"), r.options)
    }

    @Test fun unnamedTrackOfLanguageFeedTakesItsLanguage() {
        val variants = listOf(ChannelLanguage.Variant("hi", ch("1")), ChannelLanguage.Variant("ta", ch("2")))
        val r = buildLanguageChoices(listOf(StreamAudio(0, null, null, true)), variants, "1", "hi", "Hindi")
        assertEquals(listOf("t:0" to "Hindi", "c:2" to "Tamil"), r.options)
        assertEquals("Hindi", r.label)
    }

    @Test fun noTracksYetUsesSiblings() {
        val variants = listOf(ChannelLanguage.Variant("hi", ch("1")), ChannelLanguage.Variant("ta", ch("2")))
        val r = buildLanguageChoices(emptyList(), variants, "2", "ta", "Tamil")
        assertEquals("c:2", r.current)
        assertEquals(2, r.options.size)
    }

    @Test fun nothingKnownFallsBack() {
        val r = buildLanguageChoices(emptyList(), emptyList(), "1", null, "Hindi")
        assertEquals(emptyList<Pair<String, String>>(), r.options)
        assertEquals("Hindi", r.label)
    }

    @Test fun castIsShortAndRoleless() {
        assertEquals(
            "Dilip Joshi, Nitish Bhaluni, Amit Bhatt, Sachin Shroff",
            EpgRepository.shortCast(
                "Dilip Joshi (Jethalal Champaklal Gada),Nitish Bhaluni (Tipendra 'Tapu' Jethalal Gada)," +
                    "Amit Bhatt (Champaklal Jayantilal Gada),Sachin Shroff (Taarak Mehta),Sunayana Fozdar (Anjali)",
            ),
        )
        assertEquals("Dharmesh Mehta, Harshad Joshi", EpgRepository.shortNames("Dharmesh Mehta,Harshad Joshi", 3))
    }
}
