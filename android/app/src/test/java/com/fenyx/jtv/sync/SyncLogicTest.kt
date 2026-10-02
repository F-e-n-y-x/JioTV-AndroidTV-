package com.fenyx.jtv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SyncLogicTest {

    // ── Favourites merge ──

    @Test
    fun union_keepsMyOrderFirst_thenTheirExtras() {
        val out = FavoritesSync.union(listOf("3", "1", "7"), listOf("1", "9", "3", "4"))
        assertEquals(listOf("3", "1", "7", "9", "4"), out)
    }

    @Test
    fun union_dropsBlanksAndDuplicates() {
        assertEquals(listOf("a", "b"), FavoritesSync.union(listOf("a", "", "a"), listOf(" ", "b", "b")))
        assertEquals(listOf("x"), FavoritesSync.union(emptyList(), listOf("x")))
    }

    @Test
    fun newerWins_andTieKeepsLocal() {
        val local = FavoritesSnapshot(listOf("1"), 100)
        val newer = FavoritesSnapshot(listOf("2"), 200)
        val older = FavoritesSnapshot(listOf("3"), 50)
        val tie = FavoritesSnapshot(listOf("4"), 100)
        assertEquals(newer, FavoritesSync.pickNewer(local, newer))
        assertEquals(local, FavoritesSync.pickNewer(local, older))
        assertEquals(local, FavoritesSync.pickNewer(local, tie))
        assertTrue(FavoritesSync.remoteWins(local, newer))
        assertFalse(FavoritesSync.remoteWins(local, tie))
    }

    @Test
    fun nextStamp_isAlwaysAfterThePreviousOne() {
        assertEquals(1000L, FavoritesSync.nextStamp(10, 1000))
        // Clock went backwards (or a remote device's clock is ahead): still strictly newer.
        assertEquals(5001L, FavoritesSync.nextStamp(5000, 1000))
    }

    // ── Private-address filter ──

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun privateIpv4_ranges() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.3.4", "127.0.0.1")
            .forEach { assertTrue(it, LanAddress.isPrivate(ip(it))) }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.64.0.1", "1.1.1.1", "169.253.0.1")
            .forEach { assertFalse(it, LanAddress.isPrivate(ip(it))) }
    }

    @Test
    fun ipv6_linkLocalAndUniqueLocalOnly() {
        assertTrue(LanAddress.isPrivate(ip("fe80::1")))
        assertTrue(LanAddress.isPrivate(ip("fd12:3456::1")))
        assertTrue(LanAddress.isPrivate(ip("fc00::1")))
        assertTrue(LanAddress.isPrivate(ip("::ffff:192.168.0.5"))) // IPv4-mapped
        assertFalse(LanAddress.isPrivate(ip("2001:4860:4860::8888")))
        assertFalse(LanAddress.isPrivate(ip("::ffff:8.8.8.8")))
    }

    // ── Pairing code ──

    private fun session(code: String = "4821", at: Long = 0L) = PairingSession("r", "phone", "Phone", "phone", code, at)

    @Test
    fun rightCode_pairs() {
        assertEquals(PairingSession.Check.Ok, session().check("4821", 1000))
        assertEquals(PairingSession.Check.Ok, session().check(" 4821 ", 1000))
    }

    @Test
    fun wrongCodes_countDown_thenCancelAfterFive() {
        val s = session()
        assertEquals(PairingSession.Check.Wrong(4), s.check("0000", 1))
        assertEquals(PairingSession.Check.Wrong(3), s.check("1111", 2))
        assertEquals(PairingSession.Check.Wrong(2), s.check("2222", 3))
        assertEquals(PairingSession.Check.Wrong(1), s.check("3333", 4))
        assertEquals(PairingSession.Check.Cancelled, s.check("4444", 5))
        // Once cancelled, even the right code is refused.
        assertEquals(PairingSession.Check.Cancelled, s.check("4821", 6))
    }

    @Test
    fun cancelledOrExpired_refusesTheRightCode() {
        val s = session()
        s.cancel()
        assertEquals(PairingSession.Check.Cancelled, s.check("4821", 1))
        assertEquals(PairingSession.Check.Expired, session(at = 0).check("4821", PairingSession.TTL_MS + 1))
    }

    @Test
    fun generatedCodes_areFourDigits_andKeysAre128Bit() {
        repeat(200) { assertTrue(SyncCrypto.pairCode().matches(Regex("\\d{4}"))) }
        val k = SyncCrypto.randomHex128()
        assertTrue(k.matches(Regex("[0-9a-f]{32}")))
        assertFalse(k == SyncCrypto.randomHex128())
    }

    @Test
    fun bearerHeader_parsing() {
        assertEquals("abc", SyncCrypto.bearer("Bearer abc"))
        assertEquals("abc", SyncCrypto.bearer("bearer  abc "))
        assertEquals(null, SyncCrypto.bearer("Basic abc"))
        assertEquals(null, SyncCrypto.bearer(null))
        assertEquals(null, SyncCrypto.bearer("Bearer "))
        assertTrue(SyncCrypto.sameKey("k1", "k1"))
        assertFalse(SyncCrypto.sameKey("k1", "k2"))
    }
}
