package dev.jackque.roamed.core

import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileMathTest {

    @Test
    fun `zoom zero is one tile covering the world`() {
        assertEquals(1, TileMath.gridSize(0))
        assertEquals(0, TileMath.cellX(-179.9, 0))
        assertEquals(0, TileMath.cellX(179.9, 0))
        assertEquals(0, TileMath.cellY(0.0, 0))
    }

    @Test
    fun `null island sits at the centre of the grid`() {
        val z = 10
        assertEquals(TileMath.gridSize(z) / 2, TileMath.cellX(0.0, z))
        assertEquals(TileMath.gridSize(z) / 2, TileMath.cellY(0.0, z))
    }

    @Test
    fun `lat lon round trips through tile coordinates`() {
        val samples = listOf(
            0.0 to 0.0,
            51.5074 to -0.1278,   // London
            -33.8688 to 151.2093, // Sydney
            64.1466 to -21.9426,  // Reykjavik
            -54.8019 to -68.3030, // Ushuaia
        )
        for ((lat, lon) in samples) {
            val x = TileMath.lonToTileX(lon, RevealZoom.Z)
            val y = TileMath.latToTileY(lat, RevealZoom.Z)
            assertEquals(lon, TileMath.tileXToLon(x, RevealZoom.Z), 1e-9, "lon $lon")
            assertEquals(lat, TileMath.tileYToLat(y, RevealZoom.Z), 1e-9, "lat $lat")
        }
    }

    @Test
    fun `longitude normalisation wraps around the antimeridian`() {
        assertEquals(-179.0, TileMath.normalizeLongitude(181.0), 1e-9)
        assertEquals(179.0, TileMath.normalizeLongitude(-181.0), 1e-9)
        assertEquals(0.0, TileMath.normalizeLongitude(360.0), 1e-9)
        assertEquals(-180.0, TileMath.normalizeLongitude(180.0), 1e-9)
    }

    @Test
    fun `latitude is clamped to the mercator limit`() {
        assertEquals(TileMath.MAX_LATITUDE, TileMath.clampLatitude(89.0), 1e-9)
        assertEquals(-TileMath.MAX_LATITUDE, TileMath.clampLatitude(-90.0), 1e-9)
        val z = 8
        assertTrue(TileMath.cellY(90.0, z) in 0 until TileMath.gridSize(z))
        assertTrue(TileMath.cellY(-90.0, z) in 0 until TileMath.gridSize(z))
    }

    @Test
    fun `cells are wider at the equator than near the poles`() {
        val z = RevealZoom.Z
        val equatorRow = TileMath.cellY(0.0, z)
        val highRow = TileMath.cellY(70.0, z)
        val equator = TileMath.cellWidthMeters(equatorRow, z)
        val high = TileMath.cellWidthMeters(highRow, z)
        assertEquals(305.7, equator, 1.0, "z17 cell at the equator should be about 306 m across")
        assertTrue(high < equator / 2, "a cell at 70N should be less than half as wide")
    }

    @Test
    fun `summed cell areas reconstruct the mercator-visible sphere`() {
        // Every cell in the grid, added up, must equal the spherical zone between +-85.05 degrees.
        val z = 6
        val grid = TileMath.gridSize(z)
        var total = 0.0
        for (y in 0 until grid) total += TileMath.areaOfRow(y, z) * grid

        val maxLatRad = TileMath.degToRad(TileMath.MAX_LATITUDE)
        val expected = 4.0 * PI * TileMath.EARTH_RADIUS_METERS * TileMath.EARTH_RADIUS_METERS *
            kotlin.math.sin(maxLatRad)
        assertTrue(
            abs(total - expected) / expected < 1e-9,
            "summed area $total should match the zone area $expected",
        )
        // Sanity against the published figure for the whole planet.
        assertTrue(total < TileMath.EARTH_SURFACE_AREA_M2)
        assertTrue(total > TileMath.EARTH_SURFACE_AREA_M2 * 0.99)
    }

    @Test
    fun `cell area is independent of the column`() {
        val z = RevealZoom.Z
        val row = TileMath.cellY(48.85, z)
        val area = TileMath.areaOfRow(row, z)
        assertTrue(area > 0.0)
        // Mercator cells are square on the ground: ~201 m a side in Paris, so ~0.0405 km2.
        assertEquals(0.0405, area / 1_000_000.0, 0.001)
    }

    @Test
    fun `column wrapping is stable in both directions`() {
        val z = 4
        val n = TileMath.gridSize(z)
        assertEquals(0, TileMath.wrapX(0, z))
        assertEquals(0, TileMath.wrapX(n, z))
        assertEquals(n - 1, TileMath.wrapX(-1, z))
        assertEquals(1, TileMath.wrapX(n + 1, z))
    }
}
