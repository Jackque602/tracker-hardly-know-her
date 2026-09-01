package dev.jackque.roamed.core.fog

import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.GeoBounds
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath

/**
 * In-memory home of every uncovered cell, shaped for the one query the map overlay asks 60 times
 * a second: "which cells fall inside this rectangle, at this level of detail?".
 *
 * Two structures back that query:
 *  - [buckets] groups cells by their ancestor at [BUCKET_ZOOM], so a zoomed-in viewport only ever
 *    walks the handful of buckets it actually covers;
 *  - [coarseCache] memoises the whole set collapsed to a low zoom, so a zoomed-out viewport draws
 *    a few hundred rectangles instead of a few hundred thousand.
 *
 * Running area is kept up to date on insert, because a cell's area depends only on its row and
 * recomputing it over the whole set on every fix would be wasteful.
 */
class ExploredIndex(private val zoom: Int = RevealZoom.Z) {

    private val lock = Any()
    private val all = HashSet<Long>()
    private val buckets = HashMap<Long, MutableSet<Long>>()
    private val coarseCache = arrayOfNulls<HashSet<Long>>(BUCKET_ZOOM + 1)
    private val rowAreaCache = HashMap<Int, Double>()

    /** Bumped on every change so observers can tell whether a redraw is needed. */
    var version: Long = 0L
        private set

    /** Total uncovered area in square metres. */
    var areaSquareMeters: Double = 0.0
        private set

    val size: Int get() = synchronized(lock) { all.size }

    fun contains(key: Long): Boolean = synchronized(lock) { all.contains(key) }

    /** Adds one cell. Returns true if it was not already uncovered. */
    fun add(key: Long): Boolean = synchronized(lock) { addLocked(key) }

    /** Adds many cells. Returns the keys that were genuinely new. */
    fun addAll(keys: Iterable<Long>): List<Long> = synchronized(lock) {
        val fresh = ArrayList<Long>()
        for (key in keys) if (addLocked(key)) fresh.add(key)
        fresh
    }

    fun clear() = synchronized(lock) {
        all.clear()
        buckets.clear()
        coarseCache.fill(null)
        areaSquareMeters = 0.0
        version++
    }

    fun snapshotKeys(): LongArray = synchronized(lock) { all.toLongArray() }

    /**
     * The smallest box containing everything uncovered, or null if nothing is.
     *
     * The east-west edges are not simply the smallest and largest columns. Someone who has been to
     * Tokyo and to San Francisco has cells near both ends of the grid, and taking the min and max
     * would return a box spanning almost the entire planet - the long way round, across Europe
     * they have never visited. So instead the widest empty stretch of longitude is found and the
     * box is everything *except* that stretch, which for that traveller correctly wraps across the
     * Pacific.
     *
     * Latitude needs none of this: the grid does not wrap north to south.
     *
     * The one case this gets wrong is genuinely covering more than half the world's longitudes, at
     * which point the widest gap falls inside the explored area rather than outside it. Fitting the
     * map slightly too wide for someone that well travelled is a good trade for handling the
     * Pacific correctly.
     */
    fun bounds(): GeoBounds? = synchronized(lock) {
        if (all.isEmpty()) return null

        var minRow = Int.MAX_VALUE
        var maxRow = Int.MIN_VALUE
        val columns = HashSet<Int>()
        for (key in all) {
            val row = CellKey.y(key)
            if (row < minRow) minRow = row
            if (row > maxRow) maxRow = row
            columns.add(CellKey.x(key))
        }

        val sorted = columns.toIntArray()
        sorted.sort()
        val grid = TileMath.gridSize(zoom)

        // Start with the gap that wraps from the last column back round to the first. Comparing
        // strictly greater than keeps this one on ties, so an ordinary spread of cells yields an
        // ordinary non-wrapping box.
        var widestGapAt = sorted.size - 1
        var widestGap = (sorted[0] + grid) - sorted[sorted.size - 1]
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1] - sorted[i]
            if (gap > widestGap) {
                widestGap = gap
                widestGapAt = i
            }
        }
        // The box runs from just after the gap, eastward, round to just before it.
        val westColumn = sorted[(widestGapAt + 1) % sorted.size]
        val eastColumn = sorted[widestGapAt]

        return GeoBounds(
            north = TileMath.tileYToLat(minRow.toDouble(), zoom),
            south = TileMath.tileYToLat((maxRow + 1).toDouble(), zoom),
            west = TileMath.tileXToLon(westColumn.toDouble(), zoom),
            east = TileMath.tileXToLon((eastColumn + 1).toDouble(), zoom),
        )
    }

    /**
     * Cells overlapping the given cell-range, re-keyed to [renderZoom].
     *
     * [xFrom]/[xTo] are expressed at [renderZoom] and may run past the grid edge; they are wrapped
     * around the antimeridian so a viewport straddling it still draws correctly.
     */
    fun cellsIn(renderZoom: Int, xFrom: Int, xTo: Int, yFrom: Int, yTo: Int): LongArray {
        require(renderZoom in 0..zoom) { "renderZoom out of range: $renderZoom" }
        synchronized(lock) {
            val grid = TileMath.gridSize(renderZoom)
            val yLo = yFrom.coerceIn(0, grid - 1)
            val yHi = yTo.coerceIn(0, grid - 1)
            if (yLo > yHi || all.isEmpty()) return LongArray(0)

            val wholeWorldX = xTo - xFrom + 1 >= grid
            val result = HashSet<Long>()

            if (renderZoom <= BUCKET_ZOOM) {
                for (key in coarseSetLocked(renderZoom)) {
                    val y = CellKey.y(key)
                    if (y < yLo || y > yHi) continue
                    if (!wholeWorldX && !xInRange(CellKey.x(key), xFrom, xTo, grid)) continue
                    result.add(key)
                }
            } else {
                val shift = renderZoom - BUCKET_ZOOM
                val bucketGrid = TileMath.gridSize(BUCKET_ZOOM)
                val bxFrom = floorShift(xFrom, shift)
                val bxTo = floorShift(xTo, shift)
                val byFrom = floorShift(yLo, shift)
                val byTo = floorShift(yHi, shift)
                for (by in byFrom..byTo) {
                    if (by < 0 || by >= bucketGrid) continue
                    for (bx in bxFrom..bxTo) {
                        val bucket = buckets[CellKey.pack(TileMath.wrapX(bx, BUCKET_ZOOM), by)] ?: continue
                        for (cell in bucket) {
                            val key = CellKey.toZoom(cell, zoom, renderZoom)
                            val y = CellKey.y(key)
                            if (y < yLo || y > yHi) continue
                            if (!wholeWorldX && !xInRange(CellKey.x(key), xFrom, xTo, grid)) continue
                            result.add(key)
                        }
                    }
                }
            }
            return result.toLongArray()
        }
    }

    private fun addLocked(key: Long): Boolean {
        if (!all.add(key)) return false
        val bucketKey = CellKey.toZoom(key, zoom, BUCKET_ZOOM)
        buckets.getOrPut(bucketKey) { HashSet() }.add(key)
        for (z in 0..BUCKET_ZOOM) {
            coarseCache[z]?.add(CellKey.toZoom(key, zoom, z))
        }
        areaSquareMeters += rowArea(CellKey.y(key))
        version++
        return true
    }

    private fun coarseSetLocked(renderZoom: Int): HashSet<Long> {
        coarseCache[renderZoom]?.let { return it }
        val built = HashSet<Long>()
        for (key in all) built.add(CellKey.toZoom(key, zoom, renderZoom))
        coarseCache[renderZoom] = built
        return built
    }

    private fun rowArea(y: Int): Double = rowAreaCache.getOrPut(y) { TileMath.areaOfRow(y, zoom) }

    /** Arithmetic shift right, which floors for negative inputs the way tile indices need. */
    private fun floorShift(value: Int, shift: Int): Int = value shr shift

    private fun xInRange(x: Int, xFrom: Int, xTo: Int, grid: Int): Boolean {
        for (candidate in intArrayOf(x, x + grid, x - grid)) {
            if (candidate in xFrom..xTo) return true
        }
        return false
    }

    companion object {
        /** Cells are bucketed by their z10 ancestor (~39 km squares at the equator). */
        const val BUCKET_ZOOM = 10
    }
}
