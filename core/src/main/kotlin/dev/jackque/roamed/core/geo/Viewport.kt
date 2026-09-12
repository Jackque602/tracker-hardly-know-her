package dev.jackque.roamed.core.geo

import kotlin.math.floor
import kotlin.math.pow

/** An inclusive block of grid cells, in cell coordinates at some zoom. */
data class CellRange(val xFrom: Int, val xTo: Int, val yFrom: Int, val yTo: Int) {
    val columns: Int get() = xTo - xFrom + 1
    val rows: Int get() = yTo - yFrom + 1
}

/**
 * A rectangular view of the world, and the pixel arithmetic that goes with it.
 *
 * The phone gets this for free: osmdroid owns the map view and hands out a `Projection`. The car
 * screen hands over a bare `Surface` and nothing else, so the same sums have to be done by hand -
 * and doing them here, in a module with no Android in it, is what makes them testable rather than
 * something only a head unit can check.
 *
 * Immutable: [panBy] and [zoomedBy] return a new viewport, so the render thread can never see a
 * half-applied gesture.
 */
class Viewport(
    val centerLatitude: Double,
    val centerLongitude: Double,
    val zoom: Int,
    val widthPx: Double,
    val heightPx: Double,
    /** Screen size of one map tile. Larger than 256 on a dense screen, or the map looks like ants. */
    val tileSizePx: Double = DEFAULT_TILE_SIZE_PX,
) {

    /** Fractional tile coordinates of the centre of the view. */
    private val centerTileX = TileMath.lonToTileX(centerLongitude, zoom)
    private val centerTileY = TileMath.latToTileY(centerLatitude, zoom)

    private val halfWidthTiles = widthPx / 2.0 / tileSizePx
    private val halfHeightTiles = heightPx / 2.0 / tileSizePx

    /** Screen x of the western edge of tile column [tileX]. May be off-screen. */
    fun tileLeft(tileX: Int): Double = (tileX - centerTileX) * tileSizePx + widthPx / 2.0

    /** Screen y of the northern edge of tile row [tileY]. */
    fun tileTop(tileY: Int): Double = (tileY - centerTileY) * tileSizePx + heightPx / 2.0

    /** The tiles overlapping the view. Indices may fall outside the grid and need wrapping. */
    fun tileRange(): CellRange = CellRange(
        xFrom = floor(centerTileX - halfWidthTiles).toInt(),
        xTo = floor(centerTileX + halfWidthTiles).toInt(),
        yFrom = floor(centerTileY - halfHeightTiles).toInt(),
        yTo = floor(centerTileY + halfHeightTiles).toInt(),
    )

    /** Screen size of one cell at [cellZoom]; finer zooms give smaller cells. */
    fun cellSizePx(cellZoom: Int): Double = tileSizePx * 2.0.pow((zoom - cellZoom).toDouble())

    /**
     * Screen x of the western edge of cell column [cellX] at [cellZoom].
     *
     * Unwrapped towards the centre, because the explored index hands back columns already wrapped
     * into the grid: a cell just east of the antimeridian comes back as column 1, and taken
     * literally that would be drawn a whole world away from a view centred at 179 degrees.
     */
    fun cellLeft(cellX: Int, cellZoom: Int): Double =
        (unwrappedTowardsCenter(cellX * tileScale(cellZoom)) - centerTileX) * tileSizePx +
            widthPx / 2.0

    fun cellTop(cellY: Int, cellZoom: Int): Double =
        (cellY * tileScale(cellZoom) - centerTileY) * tileSizePx + heightPx / 2.0

    /** The cells at [cellZoom] overlapping the view. */
    fun cellRange(cellZoom: Int): CellRange {
        val scale = tileScale(cellZoom)
        return CellRange(
            xFrom = floor((centerTileX - halfWidthTiles) / scale).toInt(),
            xTo = floor((centerTileX + halfWidthTiles) / scale).toInt(),
            yFrom = floor((centerTileY - halfHeightTiles) / scale).toInt(),
            yTo = floor((centerTileY + halfHeightTiles) / scale).toInt(),
        )
    }

    /**
     * Screen x of a longitude.
     *
     * Unwrapped towards the centre of the view, so a point just the other side of the
     * antimeridian lands just off the edge of the screen rather than a whole world away.
     */
    fun xOf(longitude: Double): Double =
        (unwrappedTowardsCenter(TileMath.lonToTileX(longitude, zoom)) - centerTileX) * tileSizePx +
            widthPx / 2.0

    /** The representation of a tile coordinate nearest the centre of the view. */
    private fun unwrappedTowardsCenter(tileX: Double): Double {
        val grid = TileMath.gridSize(zoom).toDouble()
        var tile = tileX
        while (tile - centerTileX > grid / 2.0) tile -= grid
        while (centerTileX - tile > grid / 2.0) tile += grid
        return tile
    }

    fun yOf(latitude: Double): Double =
        (TileMath.latToTileY(latitude, zoom) - centerTileY) * tileSizePx + heightPx / 2.0

    /** Moves the view by a screen offset. Dragging the map right means looking further west. */
    fun panBy(dxPx: Double, dyPx: Double): Viewport {
        val tileX = centerTileX + dxPx / tileSizePx
        val tileY = (centerTileY + dyPx / tileSizePx)
            .coerceIn(0.0, TileMath.gridSize(zoom).toDouble())
        return Viewport(
            centerLatitude = TileMath.tileYToLat(tileY, zoom),
            centerLongitude = TileMath.tileXToLon(tileX, zoom),
            zoom = zoom,
            widthPx = widthPx,
            heightPx = heightPx,
            tileSizePx = tileSizePx,
        )
    }

    /** How many tiles wide one cell at [cellZoom] is. */
    private fun tileScale(cellZoom: Int): Double = 2.0.pow((zoom - cellZoom).toDouble())

    companion object {
        const val DEFAULT_TILE_SIZE_PX = 256.0

        /** Below z3 the whole world is a postage stamp; above z19 OpenStreetMap has no tiles. */
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 19
    }
}
