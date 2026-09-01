package dev.jackque.roamed.core

import dev.jackque.roamed.core.fog.Coverage
import dev.jackque.roamed.core.geo.RevealZoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoverageTest {

    @Test
    fun `each coarser level holds four times as many cells`() {
        assertEquals(1L, Coverage.childrenPerCell(17, 17))
        assertEquals(4L, Coverage.childrenPerCell(17, 16))
        assertEquals(16L, Coverage.childrenPerCell(17, 15))
        assertEquals(1_024L, Coverage.childrenPerCell(17, 12))
    }

    @Test
    fun `collapsing all the way to the whole world does not overflow`() {
        // 4^17 needs more than 32 bits; an Int count would have wrapped to nonsense here.
        val whole = Coverage.childrenPerCell(RevealZoom.Z, 0)
        assertEquals(17_179_869_184L, whole)
        assertTrue(whole > Int.MAX_VALUE)
    }

    @Test
    fun `a coarser target zoom than the source is rejected`() {
        assertFailsWith<IllegalArgumentException> { Coverage.childrenPerCell(10, 12) }
    }

    @Test
    fun `fraction is the share of a square that is explored`() {
        assertEquals(1.0, Coverage.fraction(1, 17, 17), 1e-12)
        assertEquals(0.25, Coverage.fraction(1, 17, 16), 1e-12)
        assertEquals(1.0, Coverage.fraction(4, 17, 16), 1e-12)
        assertEquals(3.0 / 16.0, Coverage.fraction(3, 17, 15), 1e-12)
        assertEquals(0.0, Coverage.fraction(0, 17, 15), 1e-12)
    }

    @Test
    fun `a count beyond capacity is clamped rather than exceeding one`() {
        assertEquals(1.0, Coverage.fraction(99, 17, 16), 1e-12)
    }

    @Test
    fun `a fully explored square is erased completely`() {
        assertEquals(1.0, Coverage.alpha(1.0), 1e-12)
    }

    @Test
    fun `an unexplored square is not erased at all`() {
        assertEquals(0.0, Coverage.alpha(0.0), 1e-12)
    }

    @Test
    fun `anywhere visited stays visible no matter how small the fraction`() {
        // A thoroughly explored city is a rounding error of a world-zoom square. Scaling that
        // linearly would hide it entirely, which is the failure this floor exists to prevent.
        val cityAtWorldZoom = Coverage.fraction(2_000, 17, 5)
        assertTrue(cityAtWorldZoom < 1e-3, "sanity: this fraction really is tiny")
        assertTrue(
            Coverage.alpha(cityAtWorldZoom) >= Coverage.DEFAULT_FLOOR,
            "a visited square must never fade below the floor",
        )
    }

    @Test
    fun `more coverage always erases at least as hard`() {
        var previous = 0.0
        for (step in 0..100) {
            val alpha = Coverage.alpha(step / 100.0)
            assertTrue(alpha >= previous, "alpha went backwards at ${step / 100.0}")
            previous = alpha
        }
        assertEquals(1.0, previous, 1e-12)
    }

    @Test
    fun `the curve spreads out the low end instead of flattening it`() {
        // With a linear ramp these would be nearly indistinguishable; the gamma is what makes
        // "passed through once" look different from "spent a week there".
        val sparse = Coverage.alpha(0.01)
        val moderate = Coverage.alpha(0.1)
        val busy = Coverage.alpha(0.5)
        assertTrue(moderate - sparse > 0.05, "0.01 -> 0.1 should be a visible step")
        assertTrue(busy - moderate > 0.05, "0.1 -> 0.5 should be a visible step")
        assertTrue(busy < 1.0)
    }

    @Test
    fun `alpha stays inside the drawable range for every fraction`() {
        for (step in -10..110) {
            val alpha = Coverage.alpha(step / 100.0)
            assertTrue(alpha in 0.0..1.0, "alpha $alpha out of range at ${step / 100.0}")
        }
    }

    @Test
    fun `a nonsensical curve is rejected rather than drawn`() {
        assertFailsWith<IllegalArgumentException> { Coverage.alpha(0.5, floor = 1.5) }
        assertFailsWith<IllegalArgumentException> { Coverage.alpha(0.5, gamma = 0.0) }
    }
}
