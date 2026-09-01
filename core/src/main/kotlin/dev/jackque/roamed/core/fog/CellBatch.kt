package dev.jackque.roamed.core.fog

/**
 * A window's worth of cells ready to draw: the squares, and how much of each one is real.
 *
 * Parallel arrays rather than a list of objects because this is rebuilt whenever the map moves and
 * walked once per frame; a few thousand boxed pairs per pan is exactly the allocation churn the
 * overlay cannot afford.
 */
class CellBatch(
    val keys: LongArray,
    /** Stored cells sitting inside each key, always at least 1. */
    val childCounts: IntArray,
    /** Zoom [keys] are expressed at. */
    val renderZoom: Int,
    /** Zoom the counted cells are stored at. */
    val sourceZoom: Int,
) {
    init {
        require(keys.size == childCounts.size) { "keys and childCounts must line up" }
    }

    val size: Int get() = keys.size

    val isEmpty: Boolean get() = keys.isEmpty()

    /** Portion of the square at [index] that is genuinely explored, in 0..1. */
    fun coverageAt(index: Int): Double =
        Coverage.fraction(childCounts[index], sourceZoom, renderZoom)

    companion object {
        val EMPTY = CellBatch(LongArray(0), IntArray(0), 0, 0)
    }
}
