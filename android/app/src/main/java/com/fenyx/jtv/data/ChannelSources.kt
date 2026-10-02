package com.fenyx.jtv.data

/**
 * Which of Jio's two public channel lists we trust, and how they merge.
 *
 * Jio's current list is **v3.1** `getMobileChannelList` (~1200 channels; it also carries
 * `stbChannelNumber`, `is_premium` and `plan_type`). The older **v1.4** list has ~100 extra ids, but
 * nearly all of them are dead: Zee GEC/movie channels Jio dropped (geturl 403 / manifest 404), STB
 * duplicates, test feeds (PlusTest2, RedBull Cam01–03), the old "Sony YAY" 3507 and so on.
 *
 * So v3.1 wins on every shared id, and a v1.4-only id is kept ONLY when it is on
 * [V14_ONLY_ALLOWLIST]. Favourites / recents that point at a dropped id simply don't show (every list
 * that resolves stored ids does a `mapNotNull` lookup), nothing crashes.
 *
 * The server keeps the same list in `server/src/jio/channels.ts` — keep the two in sync.
 */
object ChannelSources {

    const val V31_URL =
        "https://jiotvapi.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?langId=6&os=android&devicetype=phone&usertype=JIO&version=422"
    const val V14_URL =
        "https://jiotvapi.cdn.jio.com/apis/v1.4/getMobileChannelList/get/?langId=6&devicetype=phone&os=android&usertype=JIO&version=422"

    /**
     * v1.4-only channels that still play. These are the Zee regional channels v1.5.5 brought back:
     * their DASH link 404s but the non-DRM HLS works, and JioApiClient falls back to it.
     * (README changelog v1.5.5 / commit f5e8a53.) Add an id here only after it has been seen playing.
     */
    val V14_ONLY_ALLOWLIST: Map<Int, String> = mapOf(
        413 to "Zee Cinemalu",
        414 to "Zee Yuva",
        625 to "Zee Bangla",
        628 to "Zee Tamil",
        722 to "Zee Sarthak",
        1691 to "Zee Classic",
        3476 to "Zee Bangla Sonar",
    )

    /** v3.1 first (it wins on shared ids), then only the allow-listed v1.4-only ids. Sorted by number. */
    fun <T> merge(v31: Map<Int, T>, v14: Map<Int, T>): Map<Int, T> {
        val out = LinkedHashMap<Int, T>(v31.size + V14_ONLY_ALLOWLIST.size)
        out.putAll(v31)
        for ((id, c) in v14) {
            if (id !in out && id in V14_ONLY_ALLOWLIST) out[id] = c
        }
        return out
    }
}
