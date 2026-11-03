package com.fenyx.jtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClientTest {

    @Test
    fun normalizeBaseUrl_addsHttpWhenSchemeMissing() {
        assertEquals("http://192.168.1.10:29180", ServerClient.normalizeBaseUrl(" 192.168.1.10:29180/ "))
        assertEquals("http://jtv.local", ServerClient.normalizeBaseUrl("jtv.local"))
    }

    @Test
    fun normalizeBaseUrl_keepsGivenSchemeAndEmpty() {
        assertEquals("https://jtv.example.org", ServerClient.normalizeBaseUrl("https://jtv.example.org/"))
        assertEquals("", ServerClient.normalizeBaseUrl("  "))
    }

    @Test
    fun hasHost_acceptsHostOrHostPort() {
        assertTrue(ServerClient.hasHost("192.168.1.10:29180"))
        assertTrue(ServerClient.hasHost("https://jtv.example.org"))
    }

    @Test
    fun hasHost_rejectsAddressWithoutAHost() {
        assertFalse(ServerClient.hasHost(""))
        assertFalse(ServerClient.hasHost("http://:8080"))
        assertFalse(ServerClient.hasHost("my server"))
        assertFalse(ServerClient.hasHost("http://"))
    }
}
