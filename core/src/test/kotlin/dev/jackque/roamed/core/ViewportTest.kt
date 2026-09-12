package dev.jackque.roamed.core

import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.core.geo.Viewport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {

    private fun view(
        lat: Double = 40.1295,
        lon: Double = -77.0155,
        zoom: Int = 12,
        width: Double = 800.0,
        height: Double = 480.0,
    ) = Viewport(lat, lon, zoom, width, height)

    @Test
    fun `the centre of the view is the middle of the canvas`() {
        val v = view()
        assertEquals(400.0, v.xOf(v.centerLongitude), 1e-6)
        assertEquals(240.0, v.yOf(v.centerLatitude), 1e-6)
    }

    @Test
    fun `north is up and east is right`() {
        val v = view()
        assertTrue(v.yOf(v.centerLatitude + 0.1) < 240.0, "further north should sit higher")
        assertTrue(v.xOf(v.centerLongitude + 0.1) > 400.0, "further east should sit right")
    }

    @Test
    fun `a point one tile east lands one tile width right`() {
        val v = view()
        val oneTileEast = TileMath.tileXToLon(TileMath.lonToTileX(v.centerLongitude, 12) + 1.0, 12)
        assertEquals(400.0 + v.tileSizePx, v.xOf(oneTileEast), 1e-6)
    }

    @Test
    fun `the tile range covers the canvas and no more`() {
        val v = view(width = 800.0, height = 480.0)
        val tiles = v.tileRange()
        // 800 px of 256 px tiles is 3.125 tiles wide, so four columns at most.
        assertTrue(tiles.columns in 4..5, "got ${tiles.columns} columns")
        assertTrue(tiles.rows in 2..4, "got ${tiles.rows} rows")

        // Every tile in the range must actually touch the canvas.
        for (x in tiles.xFrom..tiles.xTo) {
            val left = v.tileLeft(x)
            assertTrue(left < v.widthPx && left + v.tileSizePx > 0.0, "column $x is off-screen")
        }
        // And the range has to reach the edges: the first column must start at or before zero.
        assertTrue(v.tileLeft(tiles.xFrom) <= 0.0)
        assertTrue(v.tileLeft(tiles.xTo) + v.tileSizePx >= v.widthPx)
    }

    @Test
    fun `cells shrink by half for every level finer than the map`() {
        val v = view(zoom = 12)
        assertEquals(256.0, v.cellSizePx(12), 1e-9)
        assertEquals(128.0, v.cellSizePx(13), 1e-9)
        assertEquals(2.0, v.cellSizePx(19), 1e-9)
        // A z17 fog cell on a z12 map is a thirty-second of a tile.
        assertEquals(8.0, v.cellSizePx(17), 1e-9)
    }

    @Test
    fun `a fog cell lands where its own corner says it should`() {
        val v = view(zoom = 14)
        val z = RevealZoom.Z
        val cellX = TileMath.cellX(v.centerLongitude, z)
        val cellY = TileMath.cellY(v.centerLatitude, z)

        // The cell's west edge as a longitude, put through the point projection, must agree with
        // the cell placement used to draw the rectangles.
        val west = TileMath.tileXToLon(cellX.toDouble(), z)
        val north = TileMath.tileYToLat(cellY.toDouble(), z)
        assertEquals(v.xOf(west), v.cellLeft(cellX, z), 1e-6)
        assertEquals(v.yOf(north), v.cellTop(cellY, z), 1e-6)
    }

    @Test
    fun `the cell range covers every cell touching the canvas`() {
        val v = view(zoom = 14)
        val z = RevealZoom.Z
        val range = v.cellRange(z)
        val size = v.cellSizePx(z)
        for (x in range.xFrom..range.xTo) {
            val left = v.cellLeft(x, z)
            assertTrue(left < v.widthPx && left + size > 0.0, "cell column $x is off-screen")
        }
        assertTrue(v.cellLeft(range.xFrom, z) <= 0.0, "the range must reach the left edge")
        assertTrue(v.cellTop(range.yFrom, z) <= 0.0, "and the top edge")
    }

    @Test
    fun `panning moves the ground under your finger`() {
        val v = view()
        // Dragging the map 256 px to the right brings ground one tile to the west into the middle.
        val panned = v.panBy(256.0, 0.0)
        assertTrue(panned.centerLongitude > v.centerLongitude, "panning east moves the centre east")
        assertEquals(
            v.xOf(v.centerLongitude) - 256.0,
            panned.xOf(v.centerLongitude),
            1e-3,
            "the old centre should now sit 256 px to the left",
        )
    }

    @Test
    fun `panning cannot fall off the top or bottom of the world`() {
        val v = view(lat = 84.0, zoom = 4)
        val up = v.panBy(0.0, -100_000.0)
        assertTrue(up.centerLatitude <= TileMath.MAX_LATITUDE + 1e-9, "got ${up.centerLatitude}")
        assertTrue(up.centerLatitude.isFinite())

        val down = view(lat = -84.0, zoom = 4).panBy(0.0, 100_000.0)
        assertTrue(down.centerLatitude >= -TileMath.MAX_LATITUDE - 1e-9, "got ${down.centerLatitude}")
        assertTrue(down.centerLatitude.isFinite())
    }

    @Test
    fun `a point across the antimeridian stays next door`() {
        // Centred just west of the date line, with a point just east of it. Taken literally the
        // two are a whole world apart in tile coordinates; on screen they are neighbours.
        val v = Viewport(0.0, 179.9, zoom = 8, widthPx = 800.0, heightPx = 480.0)
        val justOver = v.xOf(-179.9)
        assertTrue(abs(justOver - 400.0) < 200.0, "should be near the middle, was $justOver")
    }

    @Test
    fun `a wrapped cell column is drawn next door, not a world away`() {
        // The explored index wraps columns into the grid, so a cell just east of the date line
        // comes back as column zero-something. Drawn literally it would be off the far side.
        val zoom = 8
        val v = Viewport(0.0, 179.9, zoom, 800.0, 480.0)
        val grid = TileMath.gridSize(zoom)
        val justWest = grid - 1
        val justEast = 0

        val westEdge = v.cellLeft(justWest, zoom)
        val eastEdge = v.cellLeft(justEast, zoom)
        assertEquals(
            v.tileSizePx,
            eastEdge - westEdge,
            1e-6,
            "the two columns either side of the date line must be adjacent on screen",
        )
        assertTrue(abs(eastEdge - 400.0) < 400.0, "and both near the middle, was $eastEdge")
    }
}
