package dev.jackque.roamed.core

import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploredIndexTest {

    @Test
    fun `adding the same cell twice only counts once`() {
        val index = ExploredIndex()
        val key = CellKey.pack(100, 200)
        assertTrue(index.add(key))
        assertFalse(index.add(key))
        assertEquals(1, index.size)
    }

    @Test
    fun `addAll reports only the genuinely new cells`() {
        val index = ExploredIndex()
        index.add(CellKey.pack(1, 1))
        val fresh = index.addAll(listOf(CellKey.pack(1, 1), CellKey.pack(2, 2), CellKey.pack(3, 3)))
        assertEquals(2, fresh.size)
        assertEquals(3, index.size)
    }

    @Test
    fun `area accumulates as cells are added and resets on clear`() {
        val index = ExploredIndex()
        assertEquals(0.0, index.areaSquareMeters, 1e-9)
        val row = TileMath.cellY(51.5, RevealZoom.Z)
        index.add(CellKey.pack(10, row))
        val one = index.areaSquareMeters
        assertTrue(one > 0.0)
        index.add(CellKey.pack(11, row))
        assertEquals(one * 2, index.areaSquareMeters, one * 1e-9)
        index.clear()
        assertEquals(0.0, index.areaSquareMeters, 1e-9)
        assertEquals(0, index.size)
    }

    @Test
    fun `version changes only when something is actually added`() {
        val index = ExploredIndex()
        val start = index.version
        index.add(CellKey.pack(5, 5))
        val afterAdd = index.version
        assertTrue(afterAdd > start)
        index.add(CellKey.pack(5, 5))
        assertEquals(afterAdd, index.version)
    }

    @Test
    fun `a detailed query returns exactly the cells inside the window`() {
        val index = ExploredIndex()
        val inside = CellKey.pack(70_000, 45_000)
        val outside = CellKey.pack(70_500, 45_000)
        index.addAll(listOf(inside, outside))
        val found = index.cellsIn(RevealZoom.Z, 69_990, 70_010, 44_990, 45_010)
        assertEquals(listOf(inside), found.toList())
    }

    @Test
    fun `a coarse query collapses neighbours into a single cell`() {
        val index = ExploredIndex()
        index.addAll((0 until 64).map { CellKey.pack(70_000 + it, 45_000) })
        val renderZoom = 8
        val shift = RevealZoom.Z - renderZoom
        val found = index.cellsIn(renderZoom, 0, TileMath.gridSize(renderZoom) - 1, 0, TileMath.gridSize(renderZoom) - 1)
        val expected = (0 until 64).map { CellKey.pack((70_000 + it) shr shift, 45_000 shr shift) }.toSet()
        assertEquals(expected, found.toSet())
        assertTrue(found.size < 64, "64 adjacent z17 cells should collapse at z8, got ${found.size}")
    }

    @Test
    fun `coarse queries stay correct when cells are added after the cache is built`() {
        val index = ExploredIndex()
        index.add(CellKey.pack(1000, 1000))
        val renderZoom = 6
        val grid = TileMath.gridSize(renderZoom)
        index.cellsIn(renderZoom, 0, grid - 1, 0, grid - 1) // primes the cache
        index.add(CellKey.pack(120_000, 90_000))
        val found = index.cellsIn(renderZoom, 0, grid - 1, 0, grid - 1)
        assertEquals(2, found.size, "a cell added after the cache was built must still show up")
    }

    @Test
    fun `a window straddling the antimeridian finds cells on both sides`() {
        val index = ExploredIndex()
        val z = RevealZoom.Z
        val maxX = TileMath.gridSize(z) - 1
        val west = CellKey.pack(maxX, 1000)
        val east = CellKey.pack(0, 1000)
        index.addAll(listOf(west, east))
        val found = index.cellsIn(z, maxX - 5, maxX + 5, 990, 1010).toSet()
        assertTrue(found.contains(west), "cell at the eastern grid edge should be found")
        assertTrue(found.contains(east), "wrapped cell should be found")
    }

    @Test
    fun `an empty window returns nothing`() {
        val index = ExploredIndex()
        index.add(CellKey.pack(70_000, 45_000))
        assertEquals(0, index.cellsIn(RevealZoom.Z, 10, 20, 10, 20).size)
        assertEquals(0, ExploredIndex().cellsIn(RevealZoom.Z, 0, 100, 0, 100).size)
    }

    @Test
    fun `rows outside the grid are ignored rather than crashing`() {
        val index = ExploredIndex()
        index.add(CellKey.pack(70_000, 45_000))
        val grid = TileMath.gridSize(RevealZoom.Z)
        assertEquals(0, index.cellsIn(RevealZoom.Z, 0, grid - 1, -500, -1).size)
    }

    @Test
    fun `a whole-world query returns every cell`() {
        val index = ExploredIndex()
        val keys = listOf(
            CellKey.pack(0, 0),
            CellKey.pack(70_000, 45_000),
            CellKey.pack(131_071, 131_071),
        )
        index.addAll(keys)
        val grid = TileMath.gridSize(4)
        val found = index.cellsIn(4, 0, grid - 1, 0, grid - 1)
        assertEquals(3, found.size)
    }
}
