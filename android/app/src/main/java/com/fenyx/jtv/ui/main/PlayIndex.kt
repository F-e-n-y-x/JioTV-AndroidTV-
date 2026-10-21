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
