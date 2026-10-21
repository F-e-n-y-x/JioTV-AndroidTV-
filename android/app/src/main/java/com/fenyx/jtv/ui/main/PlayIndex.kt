package com.fenyx.jtv.ui.main

import com.fenyx.jtv.data.Channel

/**
 * Index to hand to `openPlayer(index, group)` for channel [id]. Favourites / all-global are indexed
 * inside their own list ([groupList]); every other group (and null) indexes `displayChannels`
 * ([displayList]). Null when the channel isn't in the list the index is relative to.
 */
fun playIndex(id: String, group: String?, groupList: List<Channel>, displayList: List<Channel>): Int? {
    val own = group == MainViewModel.GROUP_FAVORITES || group == MainViewModel.GROUP_ALL_GLOBAL
    return (if (own) groupList else displayList).indexOfFirst { it.id == id }.takeIf { it >= 0 }
}

/**
 * True when a longer number in [numbers] still starts with the [typed] digits, so number entry should
 * keep waiting for another digit. Leading zeros never decide ("0" may still become "05" → 5).
 */
fun numberEntryCanGrow(typed: String, numbers: Iterable<Int>): Boolean {
    val t = typed.trimStart('0')
    if (t.isEmpty()) return true
    return numbers.any { n -> n.toString().let { it.length > t.length && it.startsWith(t) } }
}
