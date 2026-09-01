package dev.jackque.roamed.core

import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExploredBoundsTest {

    private val z = RevealZoom.Z
    private val grid = TileMath.gridSize(z)

    private fun indexOf(vararg points: Pair<Double, Double>): ExploredIndex {
        val index = ExploredIndex()
        index.addAll(points.map { (lat, lon) -> CellKey.pack(TileMath.cellX(lon, z), TileMath.cellY(lat, z)) })
        return index
    }

    @Test
    fun `an empty map has no bounds to fit`() {
        assertNull(ExploredIndex().bounds())
    }

    @Test
    fun `a single cell yields a box containing that cell`() {
        val lat = 51.5074
        val lon = -0.1278
        val bounds = assertNotNull(indexOf(lat to lon).bounds())

        assertTrue(bounds.north >= lat && bounds.south <= lat, "the point must be inside vertically")
        assertTrue(bounds.west <= lon && bounds.east >= lon, "the point must be inside horizontally")
        assertFalse(bounds.crossesAntimeridian)
        // One z17 cell is a few hundred metres, so well under a hundredth of a degree.
        assertTrue(bounds.latitudeSpan < 0.01, "span was ${bounds.latitudeSpan}")
        assertTrue(bounds.longitudeSpan < 0.01, "span was ${bounds.longitudeSpan}")
    }

    @Test
    fun `a box over two cities contains both`() {
        val london = 51.5074 to -0.1278
        val paris = 48.8566 to 2.3522
        val bounds = assertNotNull(indexOf(london, paris).bounds())

        assertFalse(bounds.crossesAntimeridian, "western Europe does not wrap")
        for ((lat, lon) in listOf(london, paris)) {
            assertTrue(lat in bounds.south..bounds.north, "latitude $lat outside the box")
            assertTrue(lon in bounds.west..bounds.east, "longitude $lon outside the box")
        }
    }

    @Test
    fun `north is always above south`() {
        val bounds = assertNotNull(indexOf(-33.8688 to 151.2093, 64.1466 to -21.9426).bounds())
        assertTrue(bounds.north > bounds.south)
        assertTrue(bounds.latitudeSpan > 0)
    }

    @Test
    fun `travel across the pacific wraps instead of spanning the whole planet`() {
        // Tokyo and San Francisco sit either side of the antimeridian. Taking the smallest and
        // largest columns would produce a box covering almost every longitude on Earth, the long
        // way round through Europe.
        val tokyo = 35.6762 to 139.6503
        val sanFrancisco = 37.7749 to -122.4194
        val bounds = assertNotNull(indexOf(tokyo, sanFrancisco).bounds())

        assertTrue(bounds.crossesAntimeridian, "the box should wrap across the Pacific")
        assertTrue(
            bounds.longitudeSpan < 180.0,
            "the wrapped span should be the short way round, was ${bounds.longitudeSpan}",
        )
        // ~98 degrees the Pacific way, versus ~262 the wrong way.
        assertEquals(98.0, bounds.longitudeSpan, 2.0)

        // Both cities must still fall inside, remembering the box runs west -> +180 -> east.
        for ((_, lon) in listOf(tokyo, sanFrancisco)) {
            val inside = lon >= bounds.west || lon <= bounds.east
            assertTrue(inside, "longitude $lon fell outside the wrapped box")
        }
    }

    @Test
    fun `cells hugging both sides of the antimeridian wrap tightly`() {
        val index = ExploredIndex()
        index.addAll(
            listOf(
                CellKey.pack(grid - 2, 1000),
                CellKey.pack(grid - 1, 1000),
                CellKey.pack(0, 1000),
                CellKey.pack(1, 1000),
            ),
        )
        val bounds = assertNotNull(index.bounds())
        assertTrue(bounds.crossesAntimeridian)
        // Four adjacent cells at z17 are about a kilometre across, nothing like a world span.
        assertTrue(bounds.longitudeSpan < 0.02, "span was ${bounds.longitudeSpan}")
    }

    @Test
    fun `an evenly spread map does not wrap`() {
        // Cells in Europe, the Americas and Asia: the widest gap is a real ocean, but no pair is
        // close enough across the antimeridian to make wrapping the tighter fit.
        val bounds = assertNotNull(
            indexOf(51.5 to -0.12, 40.7 to -74.0, 48.85 to 2.35).bounds(),
        )
        assertFalse(bounds.crossesAntimeridian)
        assertTrue(bounds.west <= -74.0)
        assertTrue(bounds.east >= 2.35)
    }

    @Test
    fun `a full ring of columns spans the world without wrapping`() {
        val index = ExploredIndex()
        // Every column present at a coarse spacing that still leaves the wrap gap smallest.
        index.addAll((0 until grid).map { CellKey.pack(it, 1000) })
        val bounds = assertNotNull(index.bounds())
        assertFalse(bounds.crossesAntimeridian)
        assertEquals(-180.0, bounds.west, 1e-9)
        assertEquals(180.0, bounds.east, 1e-9)
    }

    @Test
    fun `bounds track cells as they are added`() {
        val index = ExploredIndex()
        index.add(CellKey.pack(TileMath.cellX(0.0, z), TileMath.cellY(0.0, z)))
        val first = assertNotNull(index.bounds())
        index.add(CellKey.pack(TileMath.cellX(10.0, z), TileMath.cellY(10.0, z)))
        val second = assertNotNull(index.bounds())
        assertTrue(second.latitudeSpan > first.latitudeSpan)
        assertTrue(second.longitudeSpan > first.longitudeSpan)
    }

    @Test
    fun `clearing removes the bounds`() {
        val index = indexOf(51.5 to -0.12)
        assertNotNull(index.bounds())
        index.clear()
        assertNull(index.bounds())
    }
}
