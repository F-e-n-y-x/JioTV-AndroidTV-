package com.fenyx.jtv.data

/**
 * The user's category order and hidden categories (Settings → Categories). Keys are the channel
 * groups plus the pseudo-categories (MainViewModel.GROUP_*). "All channels" can never be hidden, so
 * every channel stays reachable.
 */
data class CategoryPrefs(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
    /**
     * [keys] in the screen's default order → the user's order, without hidden ones. Keys the user has
     * ordered take the slots of ordered keys in their saved order; the rest (new Jio categories, the
     * guide's Replay) keep their default places.
     */
    fun arrange(keys: List<String>, alwaysShown: String): List<String> {
        return reorderAll(keys).filter { it == alwaysShown || it !in hidden }
    }

    /** [key] moved by [delta] places (or to the top) within [all], the full list as shown in Settings. */
    fun move(all: List<String>, key: String, delta: Int? /* null = to the top */): CategoryPrefs {
        val list = reorderAll(all).toMutableList()
        val i = list.indexOf(key)
        if (i < 0) return this
        list.removeAt(i)
        list.add(if (delta == null) 0 else (i + delta).coerceIn(0, list.size), key)
        return copy(order = list)
    }

    fun toggle(key: String): CategoryPrefs = copy(hidden = if (key in hidden) hidden - key else hidden + key)

    /** [all] in the user's order, hidden ones included (what the Settings list shows). */
    fun reorderAll(all: List<String>): List<String> {
        val ordered = order.filter { it in all }
        val slots = ordered.toHashSet()
        val next = ordered.iterator()
        return all.map { if (it in slots) next.next() else it }
    }
}
