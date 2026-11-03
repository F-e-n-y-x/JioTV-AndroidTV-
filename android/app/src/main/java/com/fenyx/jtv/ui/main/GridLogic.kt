package com.fenyx.jtv.ui.main

/** Pure helpers for the Live TV grid (no Android types, so they are unit-tested). */

enum class GridKey { Up, Down, Left, Right }

/** Columns that fit [widthDp] when a card is at least [minCardDp] wide, kept inside [minCols]..[maxCols]. */
fun gridColumns(widthDp: Float, minCardDp: Float, gapDp: Float, minCols: Int, maxCols: Int): Int =
    ((widthDp + gapDp) / (minCardDp + gapDp)).toInt().coerceIn(minCols, maxCols)

/**
 * Where the lifted favourite goes for one arrow press in a grid of [cols] columns (list = 1 column,
 * where Left/Right do nothing). Left/Right move one place, Up/Down a whole row; Down from a row above
 * a short last row lands on the last item; at an edge it stays put.
 */
fun gridMoveTarget(from: Int, key: GridKey, cols: Int, size: Int): Int {
    if (from !in 0 until size) return from
    val c = cols.coerceAtLeast(1)
    return when (key) {
        GridKey.Left -> if (c == 1) from else (from - 1).coerceAtLeast(0)
        GridKey.Right -> if (c == 1) from else (from + 1).coerceAtMost(size - 1)
        GridKey.Up -> if (from - c >= 0) from - c else from
        GridKey.Down -> when {
            from + c < size -> from + c
            from / c < (size - 1) / c -> size - 1
            else -> from
        }
    }
}

/** First item to scroll to so the row of [index] shows with one row above it (first item of that row). */
fun gridScrollTarget(index: Int, cols: Int): Int = ((index / cols.coerceAtLeast(1)) - 1).coerceAtLeast(0) * cols.coerceAtLeast(1)
