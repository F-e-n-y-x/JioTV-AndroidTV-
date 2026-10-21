package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLanguageTest {

    private fun ch(id: String, name: String, group: String = "Sports") =
        Channel(id = id, name = name, logoUrl = "", group = group, streamUrl = "", channelNumber = id.toInt())

    @Test
    fun detect_stripsTrailingLanguage() {
        val info = ChannelLanguage.detect("Star Sports 1 Hindi")
        assertEquals("Star Sports 1", info.base)
        assertEquals("hi", info.langCode)
    }

    @Test
    fun detect_keepsQualityMarkerOnBase() {
        val info = ChannelLanguage.detect("Star Sports 1 Tamil HD")
        assertEquals("Star Sports 1 HD", info.base)
        assertEquals("ta", info.langCode)
    }

    @Test
    fun detect_noLanguageSuffix() {
        val info = ChannelLanguage.detect("Sony SAB")
        assertEquals("Sony SAB", info.base)
        assertNull(info.langCode)
    }

    @Test
    fun collapse_mergesNumberedSimulcastFamily() {
        val channels = listOf(
            ch("1", "Star Sports 1 Hindi"),
            ch("2", "Star Sports 1 Tamil"),
            ch("3", "Star Sports 1 Telugu")
        )
        val (display, variants) = ChannelLanguage.collapse(channels)
        assertEquals(1, display.size)
        // Representative is language-independent: lowest channel number (id 1) when no un-suffixed feed.
        assertEquals("1", display.first().id)
        assertEquals(3, variants[display.first().id]?.size)
    }

    @Test
    fun collapse_mergesDubFamilyByCategory() {
        // Discovery English/Hindi/Tamil in Infotainment are dubs -> collapse even though "Discovery"
        // has no trailing digit (this exercises the category discriminator). Tile name is cleaned.
        val channels = listOf(
            ch("60", "Discovery English", group = "Infotainment"),
            ch("61", "Discovery Hindi", group = "Infotainment"),
            ch("62", "Discovery Tamil", group = "Infotainment")
        )
        val (display, variants) = ChannelLanguage.collapse(channels)
        assertEquals(1, display.size)
        assertEquals("Discovery", display.first().name)
        assertEquals(3, variants[display.first().id]?.size)
    }

    @Test
    fun collapse_doesNotMergeRegionalGecs() {
        // Zee Marathi/Tamil/Telugu are DIFFERENT channels, not language dubs — base "Zee" has no
        // trailing digit, so they must all remain separate.
        val channels = listOf(
            ch("30", "Zee Marathi", group = "Entertainment"),
            ch("31", "Zee Tamil", group = "Entertainment"),
            ch("32", "Zee Telugu", group = "Entertainment"),
            ch("33", "Zee Kannada", group = "Entertainment")
        )
        val (display, _) = ChannelLanguage.collapse(channels)
        assertEquals(4, display.size)
    }

    @Test
    fun collapse_doesNotMergeColorsHdRegionals() {
        val channels = listOf(
            ch("40", "Colors HD", group = "Entertainment"),
            ch("41", "Colors Kannada HD", group = "Entertainment"),
            ch("42", "Colors Marathi HD", group = "Entertainment"),
            ch("43", "Colors Bangla HD", group = "Entertainment")
        )
        val (display, _) = ChannelLanguage.collapse(channels)
        assertEquals(4, display.size)
    }

    @Test
    fun collapse_doesNotMergeDigitBrandRegionalNews() {
        // "TV9"/"News18" end in a digit but are DISTINCT regional news channels (News category), so
        // they must not collapse — the previous digit-based rule wrongly merged them.
        val channels = listOf(
            ch("70", "TV9 Telugu", group = "News"),
            ch("71", "TV9 Marathi", group = "News"),
            ch("72", "TV9 Kannada", group = "News")
        )
        val (display, _) = ChannelLanguage.collapse(channels)
        assertEquals(3, display.size)
    }

    @Test
    fun collapse_isCaseInsensitiveForBase() {
        // "Sony LIV Sports 4" vs "Sony Liv Sports 4" differ only by case -> same family.
        val channels = listOf(
            ch("50", "Sony LIV Sports 4 Tamil", group = "Sports"),
            ch("51", "Sony Liv Sports 4 Telugu", group = "Sports"),
            ch("52", "Sony LIV Sports 4 Kannada", group = "Sports")
        )
        val (display, _) = ChannelLanguage.collapse(channels)
        assertEquals(1, display.size)
    }

    @Test
    fun collapse_leavesLoneChannelUntouched() {
        val channels = listOf(
            ch("10", "Sony SAB", group = "Entertainment"),
            ch("11", "Star Sports 1 Hindi"),
            ch("12", "Star Sports 1 Tamil")
        )
        val (display, _) = ChannelLanguage.collapse(channels)
        assertEquals(2, display.size)
        assertTrue(display.any { it.name == "Sony SAB" })
    }

    private fun langCh(name: String, lang: String) = ch("1", name, "Kids").copy(language = lang)

    @Test
    fun supportedLanguages_hungamaAndDisneyGainTamil() {
        assertTrue("Tamil" in ChannelLanguage.channelSupportedLanguages(langCh("Hungama", "Hindi")))
        assertTrue("Tamil" in ChannelLanguage.channelSupportedLanguages(langCh("Disney Channel HD", "Hindi")))
    }

    @Test
    fun supportedLanguages_discoveryDoesNotGainTamil() {
        assertEquals(setOf("English"), ChannelLanguage.channelSupportedLanguages(langCh("Discovery", "English")))
    }

    @Test
    fun supportedLanguages_shortKeysMatchWholeWordsOnly() {
        assertEquals(setOf("English"), ChannelLanguage.channelSupportedLanguages(langCh("Nickname Network", "English")))
        assertEquals(setOf("English"), ChannelLanguage.channelSupportedLanguages(langCh("ETLC", "English")))
        assertTrue("Tamil" in ChannelLanguage.channelSupportedLanguages(langCh("Nick HD", "Hindi")))
    }

    @Test
    fun supportedLanguages_noMapHitReturnsOwnLanguage() {
        assertEquals(setOf("Tamil"), ChannelLanguage.channelSupportedLanguages(langCh("Zee Tamil", "Tamil")))
    }
}
