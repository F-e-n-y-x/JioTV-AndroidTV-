package com.fenyx.jtv.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {

    private fun assertGone(out: String, vararg secrets: String) {
        for (s in secrets) assertFalse("leaked '$s' in:\n$out", out.contains(s))
    }

    @Test
    fun hdneaToken_isStripped_restOfUrlKept() {
        val out = Redact.secrets(
            "Loading https://jiotvmblive.cdn.jio.com/bpk-tv/x/index.m3u8?minrate=80000&__hdnea__=st=1790000000~exp=1790003600~acl=/*~hmac=deadbeef (isMpd: false)"
        )
        assertGone(out, "hmac=deadbeef", "exp=1790003600")
        assertTrue(out.contains("index.m3u8?minrate=80000&__hdnea__=[redacted]"))
        assertTrue(out.contains("(isMpd: false)"))
    }

    @Test
    fun authHeadersAndJioCredentials_areStripped() {
        val out = Redact.secrets(
            """
            Authorization: Bearer abc.def-123
            ssoToken=AQIC5wM2LY4Sfcz; Accesstoken: tok_987
            {"authToken":"r1","refreshToken":"r2","crmid":"cr3","uniqueId":"u4","deviceId":"d5","userId":"us6"}
            Cookie: __hdnea__=cookieval
            """.trimIndent()
        )
        assertGone(out, "abc.def-123", "AQIC5wM2LY4Sfcz", "tok_987", "\"r1\"", "\"r2\"", "cr3", "u4\"", "d5\"", "us6", "cookieval")
        assertTrue(out.contains("Authorization: [redacted]") || out.contains("Authorization: Bearer [redacted]"))
    }

    @Test
    fun jwtAccessCodeAndPhone_areStripped() {
        val out = Redact.secrets(
            "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2ln code=ABC123 phone 9876543210 +91 9123456789"
        )
        assertGone(out, "eyJhbGci", "ABC123", "9876543210", "9123456789")
    }

    @Test
    fun stackTraces_surviveUntouched() {
        val trace = """
            java.lang.IllegalStateException: boom
            	at com.fenyx.jtv.ui.player.TvPlayerScreenKt.foo(TvPlayerScreen.kt:123)
            	at android.os.Handler.dispatchMessage(Handler.java:106)
            Caused by: java.io.IOException: responseCode=403 at 1790000000123
        """.trimIndent()
        assertEquals(trace, Redact.secrets(trace))
    }

    @Test
    fun readCapped_truncatesLongTraces() {
        val big = ByteArray(100_000) { 'a'.code.toByte() }
        val out = CrashReports.readCapped(big.inputStream(), 64 * 1024)
        assertTrue(out.startsWith("a".repeat(1000)))
        assertTrue(out.endsWith("(trace truncated at 64 KB)"))
        assertEquals("short", CrashReports.readCapped("short".byteInputStream(), 64 * 1024))
    }
}
