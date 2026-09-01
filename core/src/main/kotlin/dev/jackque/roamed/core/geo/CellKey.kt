package dev.jackque.roamed.core.geo

/**
 * Packs a cell's (x, y) grid coordinates into a single Long so the in-memory index can be a
 * primitive-friendly set instead of a set of objects.
 */
object CellKey {

    fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFFL)

    fun x(key: Long): Int = (key ushr 32).toInt()

    fun y(key: Long): Int = (key and 0xFFFF_FFFFL).toInt()

    /** Re-keys a cell from [fromZoom] to the coarser [toZoom]. */
    fun toZoom(key: Long, fromZoom: Int, toZoom: Int): Long {
        require(toZoom <= fromZoom) { "toZoom must be coarser than or equal to fromZoom" }
        val shift = fromZoom - toZoom
        return pack(x(key) shr shift, y(key) shr shift)
    }
}
