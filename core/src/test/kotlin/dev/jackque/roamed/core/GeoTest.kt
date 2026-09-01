package dev.jackque.roamed.core

import dev.jackque.roamed.core.geo.Geo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 100.0)
    }

    @Test
    fun `london to paris is about 344 km`() {
        val d = Geo.distanceMeters(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343_500.0, d, 3_000.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, Geo.distanceMeters(12.3, 45.6, 12.3, 45.6), 1e-6)
    }

    @Test
    fun `midpoint is halfway along the great circle`() {
        val a = doubleArrayOf(51.5, 0.0)
        val b = doubleArrayOf(51.5, 1.0)
        val mid = Geo.interpolate(a[0], a[1], b[0], b[1], 0.5)
        assertEquals(0.5, mid[1], 1e-6)
        val toMid = Geo.distanceMeters(a[0], a[1], mid[0], mid[1])
        val fromMid = Geo.distanceMeters(mid[0], mid[1], b[0], b[1])
        assertEquals(toMid, fromMid, 1.0)
    }

    @Test
    fun `interpolating a degenerate segment returns the start`() {
        val p = Geo.interpolate(10.0, 20.0, 10.0, 20.0, 0.7)
        assertEquals(10.0, p[0], 1e-9)
        assertEquals(20.0, p[1], 1e-9)
    }

    @Test
    fun `fractions stay ordered along the segment`() {
        var previous = 0.0
        for (i in 1..10) {
            val p = Geo.interpolate(0.0, 0.0, 10.0, 0.0, i / 10.0)
            assertTrue(p[0] > previous)
            previous = p[0]
        }
    }
}
