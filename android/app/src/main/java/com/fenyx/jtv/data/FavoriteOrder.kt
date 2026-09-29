package com.fenyx.jtv.data

/**
 * Pure helpers for the user-ordered favorites list (kept free of Android types so they're unit-tested).
 * The persisted order is a list of channel ids; the UI only ever sees the favorites that exist in the
 * current channel list, so saving always merges the visible order back over the full stored order.
 */
object FavoriteOrder {

    /** Favorites from [channels], in the user's [order]. Channels not in [order] are dropped. */
    fun <T> sortedFavorites(channels: List<T>, order: List<String>, id: (T) -> String): List<T> {
        if (order.isEmpty()) return emptyList()
        val rank = order.withIndex().associate { (i, v) -> v to i }
        return channels.filter { id(it) in rank }.sortedBy { rank[id(it)] }
    }

    /** Moves the item at [from] to [to] (both clamped), returning a new list. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (list.isEmpty() || from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        if (target == from) return list
        val out = list.toMutableList()
        out.add(target, out.removeAt(from))
        return out
    }

    /**
     * Writes a new order for the [visible] favorites back into [stored], keeping ids that aren't
     * currently visible (e.g. a collapsed language variant or a channel Jio temporarily dropped)
     * after the visible ones so they aren't lost.
     */
    fun merge(stored: List<String>, visible: List<String>): List<String> {
        val visibleSet = visible.toSet()
        return (visible + stored.filter { it !in visibleSet }).distinct()
    }

    /**
     * Groups favorites by category. Categories keep the order in which they FIRST appear in the
     * current list, and channels keep their relative order inside each category. So moving one News
     * channel to the top and grouping brings every News favorite up with it.
     */
    fun <T> groupByCategory(list: List<T>, category: (T) -> String): List<T> {
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (item in list) buckets.getOrPut(category(item)) { mutableListOf() }.add(item)
        return buckets.values.flatten()
    }
}
