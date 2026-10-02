package com.fenyx.jtv.sync

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * Pure LAN-sync rules, kept free of Android types so they are unit-tested
 * (see test/.../sync/SyncLogicTest.kt).
 */

/** An ordered favourites list plus when it last changed (wall-clock ms, 0 = never). */
data class FavoritesSnapshot(val ids: List<String>, val updatedAt: Long)

object FavoritesSync {

    /** First sync after pairing: [mine] keeps its order, then [theirs] adds what [mine] lacks. */
    fun union(mine: List<String>, theirs: List<String>): List<String> =
        (mine + theirs).filter { it.isNotBlank() }.distinct()

    /** The newer one wins; on a tie (or both never changed) the local list stays. */
    fun pickNewer(local: FavoritesSnapshot, remote: FavoritesSnapshot): FavoritesSnapshot =
        if (remote.updatedAt > local.updatedAt) remote else local

    /** True when [remote] should replace [local]. */
    fun remoteWins(local: FavoritesSnapshot, remote: FavoritesSnapshot): Boolean =
        remote.updatedAt > local.updatedAt

    /** Timestamp for a local change: now, but always strictly after the previous stamp (clock skew). */
    fun nextStamp(previous: Long, now: Long): Long = maxOf(now, previous + 1)
}

/** Only devices on the same home network may talk to us. */
object LanAddress {

    fun isPrivate(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> {
            val b = addr.address
            val a0 = b[0].toInt() and 0xFF
            val a1 = b[1].toInt() and 0xFF
            when {
                a0 == 10 -> true                                // 10/8
                a0 == 172 && a1 in 16..31 -> true               // 172.16/12
                a0 == 192 && a1 == 168 -> true                  // 192.168/16
                a0 == 169 && a1 == 254 -> true                  // 169.254/16 link-local
                // Loopback: only reachable from this device itself (adb port forwarding for tests).
                a0 == 127 -> true
                else -> false
            }
        }
        is Inet6Address -> {
            val b0 = addr.address[0].toInt() and 0xFF
            addr.isLinkLocalAddress ||                          // fe80::/10
                (b0 and 0xFE) == 0xFC ||                        // fc00::/7 unique local
                addr.isLoopbackAddress
        }
        else -> false
    }
}

/** One pairing attempt on the device that shows the code. */
class PairingSession(
    val requestId: String,
    val fromId: String,
    val fromName: String,
    val fromKind: String,
    val code: String,
    val createdAt: Long,
) {
    sealed interface Check {
        data object Ok : Check
        data class Wrong(val attemptsLeft: Int) : Check
        /** Too many wrong codes, or the user pressed Cancel. */
        data object Cancelled : Check
        data object Expired : Check
    }

    var attempts: Int = 0
        private set
    var cancelled: Boolean = false
        private set

    fun cancel() { cancelled = true }

    fun isExpired(now: Long) = now - createdAt > TTL_MS

    @Synchronized
    fun check(entered: String, now: Long): Check {
        if (cancelled) return Check.Cancelled
        if (isExpired(now)) return Check.Expired
        val ok = MessageDigest.isEqual(entered.trim().toByteArray(), code.toByteArray())
        if (ok) return Check.Ok
        attempts++
        if (attempts >= MAX_ATTEMPTS) { cancelled = true; return Check.Cancelled }
        return Check.Wrong(MAX_ATTEMPTS - attempts)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val TTL_MS = 2 * 60_000L
    }
}

object SyncCrypto {
    private val random = SecureRandom()

    /** 4-digit code shown on the other screen, 0000–9999. */
    fun pairCode(): String = String.format(java.util.Locale.US, "%04d", random.nextInt(10_000))

    /** Random 128-bit value as 32 hex chars (shared keys, device ids, request ids). */
    fun randomHex128(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
    }

    /** Constant-time comparison so the key can't be guessed byte by byte from response timing. */
    fun sameKey(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** "Bearer abc" -> "abc", else null. */
    fun bearer(header: String?): String? {
        if (header == null) return null
        val h = header.trim()
        if (!h.startsWith("Bearer ", ignoreCase = true)) return null
        return h.substring(7).trim().takeIf { it.isNotEmpty() }
    }
}
