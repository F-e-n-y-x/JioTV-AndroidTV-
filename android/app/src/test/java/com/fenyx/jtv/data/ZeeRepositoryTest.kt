package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeeRepositoryTest {

    @Test
    fun parseM3u_parsesZeeAndSportsChannelsWithClearKey() {
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="460" tvg-name="Star Sports Select 1 HD" tvg-logo="https://img.media.jio.com/logo.jpg" group-title="Sports",Star Sports Select 1 HD
            #KODIPROP:inputstream=inputstream.adaptive
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=c2c048e439d65316beeda6ef64d5d0f8:e2fdc132cbdee65501b845ce414fce39
            #EXTHTTP:{"cookie": "__hdnea__=st=123~exp=456~acl=/*~hmac=abc"}
            #EXTVLCOPT:http-user-agent="Virat Paglu"
            https://jiotvpllive.cdn.jio.com//bpk-tv/Star_Sports_Select_HD_1_BTS/WDVLive/index.mpd

            #EXTINF:-1 tvg-name="Sony Sports Ten 1 HD" tvg-logo="https://img.media.jio.com/sony.jpg" group-title="Sports", Sony Sports Ten 1 HD
            #KODIPROP:inputstream=inputstream.adaptive
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=8ef8595765be890b424ee5d99ef6f953:adcff05f3aa10995f1efd4b0bb394d49
            #EXTVLCOPT:http-user-agent=@shoebbro
            #EXTVLCOPT:http-referrer=https://www.jiotv.com/
            #EXTVLCOPT:http-extra-headers=Origin: https://www.jiotv.com
            #EXTVLCOPT:http-cookie=__hdnea__=st=789~exp=101~acl=/*~hmac=def
            https://jiotvpllive.cdn.jio.com/bpk-tv/SonySportsTen1HDSTB_BTS/WDVLive/index.mpd
        """.trimIndent()

        val parsed = ZeeRepository.parseM3u(sampleM3u)
        assertEquals(2, parsed.size)

        val ss = parsed[0]
        assertEquals("460", ss.id)
        assertEquals(460, ss.channelNumber)
        assertEquals("Star Sports Select 1 HD", ss.name)
        assertEquals("Sports", ss.group)
        assertEquals("English", ss.language)
        assertEquals("Virat Paglu", ss.userAgent)
        assertTrue(ss.streamUrl.contains("/bpk-tv/Star_Sports_Select_HD_1_BTS"))
        assertTrue(!ss.streamUrl.contains("//bpk-tv/"))
        assertTrue(ss.streamUrl.contains("__hdnea__="))

        val sony = parsed[1]
        assertEquals("162", sony.id)
        assertEquals(162, sony.channelNumber)
        assertEquals("Sony Sports Ten 1 HD", sony.name)
        assertEquals("Sports", sony.group)
        assertEquals("English", sony.language)
        assertEquals("@shoebbro", sony.userAgent)
        assertTrue(sony.streamUrl.contains("/bpk-tv/SonySportsTen1HDSTB_BTS"))
    }

    @Test
    fun clearKeyToJwkJson_generatesValidJwkFormat() {
        val clearKey = "c2c048e439d65316beeda6ef64d5d0f8:e2fdc132cbdee65501b845ce414fce39"
        val jwk = ZeeRepository.clearKeyToJwkJson(clearKey)
        assertTrue(jwk.contains("\"kty\":\"oct\""))
        assertTrue(jwk.contains("\"kid\":"))
        assertTrue(jwk.contains("\"k\":"))
        assertTrue(jwk.contains("\"type\":\"temporary\""))
    }
}
