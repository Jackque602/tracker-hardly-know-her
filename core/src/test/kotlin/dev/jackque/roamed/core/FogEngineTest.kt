package dev.jackque.roamed.core

import dev.jackque.roamed.core.fog.FogEngine
import dev.jackque.roamed.core.fog.isImplausibleJump
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FogEngineTest {

    private val engine = FogEngine()

    @Test
    fun `a fix always uncovers the cell it stands in`() {
        val lat = 51.5074
        val lon = -0.1278
        val cells = engine.cellsWithinRadius(lat, lon, 1.0)
        val expected = CellKey.pack(TileMath.cellX(lon, RevealZoom.Z), TileMath.cellY(lat, RevealZoom.Z))
        assertTrue(cells.contains(expected), "the containing cell must be uncovered")
    }

    @Test
    fun `a larger radius never uncovers fewer cells`() {
        var previous = 0
        for (radius in listOf(10.0, 100.0, 250.0, 600.0, 1500.0)) {
            val count = engine.cellsWithinRadius(48.8566, 2.3522, radius).size
            assertTrue(count >= previous, "radius $radius uncovered $count, was $previous")
            previous = count
        }
        assertTrue(previous > 20, "a 1.5 km radius should cover a good handful of cells, got $previous")
    }

    @Test
    fun `uncovered cells all lie within the radius plus one cell diagonal`() {
        val lat = 40.7128
        val lon = -74.0060
        val radius = 400.0
        val cells = engine.cellsWithinRadius(lat, lon, radius)
        val z = RevealZoom.Z
        for (key in cells) {
            val centreLat = (TileMath.tileYToLat(CellKey.y(key).toDouble(), z) +
                TileMath.tileYToLat((CellKey.y(key) + 1).toDouble(), z)) / 2.0
            val centreLon = (TileMath.tileXToLon(CellKey.x(key).toDouble(), z) +
                TileMath.tileXToLon((CellKey.x(key) + 1).toDouble(), z)) / 2.0
            val d = dev.jackque.roamed.core.geo.Geo.distanceMeters(lat, lon, centreLat, centreLon)
            val diagonal = TileMath.cellWidthMeters(CellKey.y(key), z) * 1.5
            assertTrue(d <= radius + diagonal, "cell centre $d m away exceeds $radius + $diagonal")
        }
    }

    @Test
    fun `a straight drive leaves no gaps in the trail`() {
        // ~2.2 km due east along the equator, with a reveal radius smaller than a cell.
        val cells = engine.cellsAlongSegment(0.0, 0.0, 0.0, 0.02, radiusMeters = 60.0)
        val z = RevealZoom.Z
        val columns = cells.map { CellKey.x(it) }.toSet()
        val from = TileMath.cellX(0.0, z)
        val to = TileMath.cellX(0.02, z)
        for (x in from..to) {
            assertTrue(columns.contains(x), "column $x is missing from the trail")
        }
    }

    @Test
    fun `an implausible gap is not painted in`() {
        // London to New York in one step: nothing between them should be uncovered.
        val cells = engine.cellsAlongSegment(51.5074, -0.1278, 40.7128, -74.0060, radiusMeters = 100.0)
        assertTrue(cells.isEmpty(), "a transatlantic jump must not draw a line")
    }

    @Test
    fun `both endpoints are uncovered for a short segment`() {
        val cells = engine.cellsAlongSegment(51.5, -0.1, 51.5005, -0.1, radiusMeters = 50.0)
        val z = RevealZoom.Z
        assertTrue(cells.contains(CellKey.pack(TileMath.cellX(-0.1, z), TileMath.cellY(51.5, z))))
        assertTrue(cells.contains(CellKey.pack(TileMath.cellX(-0.1, z), TileMath.cellY(51.5005, z))))
    }

    @Test
    fun `a fix on the antimeridian uncovers cells on both sides`() {
        val cells = engine.cellsWithinRadius(0.0, 179.999, 800.0)
        val z = RevealZoom.Z
        val maxX = TileMath.gridSize(z) - 1
        val columns = cells.map { CellKey.x(it) }.toSet()
        assertTrue(columns.any { it > maxX - 10 }, "expected cells just west of the antimeridian")
        assertTrue(columns.any { it < 10 }, "expected cells wrapped to the eastern edge")
        assertTrue(columns.all { it in 0..maxX }, "every column must stay inside the grid")
    }

    @Test
    fun `a fix near the pole stays bounded`() {
        val cells = engine.cellsWithinRadius(84.9, 10.0, 500.0)
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.size < 20_000, "polar reveals must not explode, got ${cells.size}")
    }

    @Test
    fun `accumulating into a shared set deduplicates`() {
        val shared = HashSet<Long>()
        engine.cellsWithinRadius(51.5, -0.1, 200.0, shared)
        val afterFirst = shared.size
        engine.cellsWithinRadius(51.5, -0.1, 200.0, shared)
        assertEquals(afterFirst, shared.size)
    }

    @Test
    fun `glitch detection ignores plausible travel`() {
        assertFalse(isImplausibleJump(30_000.0, 600.0))   // 180 km/h
        assertFalse(isImplausibleJump(900_000.0, 3_600.0)) // a plausible flight leg
        assertTrue(isImplausibleJump(500_000.0, 60.0))     // 30,000 km/h
        assertFalse(isImplausibleJump(50.0, 0.01))         // tiny hops are noise, not jumps
    }
}
