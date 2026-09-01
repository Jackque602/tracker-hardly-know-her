package dev.jackque.roamed.core

import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CellKeyTest {

    @Test
    fun `packing round trips across the whole grid`() {
        val max = (1 shl RevealZoom.Z) - 1
        val samples = listOf(0 to 0, 1 to 2, max to max, max to 0, 0 to max, 65_535 to 65_536)
        for ((x, y) in samples) {
            val key = CellKey.pack(x, y)
            assertEquals(x, CellKey.x(key), "x for ($x,$y)")
            assertEquals(y, CellKey.y(key), "y for ($x,$y)")
        }
    }

    @Test
    fun `x and y are not interchangeable`() {
        assertNotEquals(CellKey.pack(3, 7), CellKey.pack(7, 3))
    }

    @Test
    fun `re-keying to a coarser zoom halves coordinates per level`() {
        val key = CellKey.pack(70_000, 45_000)
        val coarse = CellKey.toZoom(key, 17, 15)
        assertEquals(70_000 shr 2, CellKey.x(coarse))
        assertEquals(45_000 shr 2, CellKey.y(coarse))
    }

    @Test
    fun `re-keying to the same zoom is identity`() {
        val key = CellKey.pack(12, 34)
        assertEquals(key, CellKey.toZoom(key, 17, 17))
    }

    @Test
    fun `neighbouring cells collapse together when zoomed out far enough`() {
        val a = CellKey.toZoom(CellKey.pack(1000, 2000), 17, 5)
        val b = CellKey.toZoom(CellKey.pack(1001, 2001), 17, 5)
        assertEquals(a, b)
    }
}
