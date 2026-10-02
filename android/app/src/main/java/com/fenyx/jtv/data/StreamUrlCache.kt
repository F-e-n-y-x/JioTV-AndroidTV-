package com.fenyx.jtv.data

/**
 * Small in-memory cache of resolved live stream URLs (`channelId -> StreamData`), filled in the
 * background for the channels above and below the one playing so a zap can skip the geturl round
 * trip.
 *
 * - An entry lives until its `__hdnea__` token's `exp` minus [marginSec]. A URL without a readable
 *   expiry is never cached (we couldn't tell when it goes stale).
 * - At most [maxEntries] entries; the least recently used one is dropped first.
 * - Recorded-schedule (SonyLIV VOD) URLs are never cached: they point at one programme's file, so a
 *   cached copy could outlive the programme.
 * - An entry is tied to the Jio access token it was resolved with, and [take] removes it: a cached
 *   URL is used for exactly one load, so if that load fails (403/410/token error) the retry path's
 *   next getStreamUrl goes to the network instead of replaying the same URL.
 *
 * Thread-safe. Pure Kotlin (no Android types) so it is unit-tested directly.
 */
class StreamUrlCache(
    private val maxEntries: Int = 6,
    private val marginSec: Long = 20,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class Entry(val data: JioApiClient.StreamData, val authToken: String, val expiresAtSec: Long)

    // accessOrder = true: iteration starts at the least recently used entry.
    private val map = LinkedHashMap<String, Entry>(8, 0.75f, true)

    /** Caches [data] for [channelId]. Returns false (and caches nothing) when it isn't cacheable. */
    @Synchronized
    fun put(channelId: String, data: JioApiClient.StreamData, authToken: String): Boolean {
        if (!isCacheable(data.streamUrl)) return false
        val exp = expiryOf(data.streamUrl)
        if (exp - marginSec <= nowSec()) return false
        map.remove(channelId)
        map[channelId] = Entry(data, authToken, exp - marginSec)
        while (map.size > maxEntries) map.remove(map.keys.first())
        return true
    }

    /** Removes and returns a still-valid entry resolved with [authToken], or null. */
    @Synchronized
    fun take(channelId: String, authToken: String): JioApiClient.StreamData? {
        val e = map.remove(channelId) ?: return null
        return if (e.authToken == authToken && nowSec() < e.expiresAtSec) e.data else null
    }

    /** True when a still-valid entry for [authToken] exists (doesn't consume it). */
    @Synchronized
    fun hasValid(channelId: String, authToken: String): Boolean {
        val e = map[channelId] ?: return false
        if (nowSec() >= e.expiresAtSec) { map.remove(channelId); return false }
        return e.authToken == authToken
    }

    @Synchronized fun remove(channelId: String) { map.remove(channelId) }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size

    companion object {
        /** Recorded-schedule programme files (SonyLIV, partner=jiotvvod) are per programme: never cache. */
        fun isCacheable(url: String): Boolean =
            url.startsWith("http") &&
                !url.contains("slivcdn", ignoreCase = true) &&
                !url.contains("partner=jiotvvod", ignoreCase = true)

        fun expiryOf(url: String): Long =
            JioApiClient.extractTokenExpiryEpochSec(JioApiClient.extractHdneaToken(url))
    }
}

/**
 * Indices of the channels a zap up/down from [index] would land on in a list of [size], wrapping the
 * same way the player's zap does. Distinct and never [index] itself (empty for a 0/1-channel list).
 * Order: previous first, then next.
 */
fun zapNeighbourIndices(index: Int, size: Int): List<Int> {
    if (size <= 1 || index !in 0 until size) return emptyList()
    val prev = ((index - 1) % size + size) % size
    val next = (index + 1) % size
    return listOf(prev, next).distinct()
}
