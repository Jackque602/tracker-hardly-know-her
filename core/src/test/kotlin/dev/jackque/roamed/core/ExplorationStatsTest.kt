package dev.jackque.roamed.core

import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.core.stats.ExplorationStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplorationStatsTest {

    @Test
    fun `the whole planet is a hundred percent`() {
        assertEquals(100.0, ExplorationStats.percentOfEarthSurface(TileMath.EARTH_SURFACE_AREA_M2), 1e-9)
        assertEquals(100.0, ExplorationStats.percentOfEarthLand(TileMath.EARTH_LAND_AREA_M2), 1e-9)
    }

    @Test
    fun `land percentage is larger than surface percentage for the same area`() {
        val area = 1e10
        assertTrue(
            ExplorationStats.percentOfEarthLand(area) > ExplorationStats.percentOfEarthSurface(area),
        )
    }

    @Test
    fun `tiny percentages keep meaningful digits instead of rounding to zero`() {
        assertEquals("0.00123%", ExplorationStats.formatPercent(0.001234))
        assertEquals("0.0000123%", ExplorationStats.formatPercent(0.0000123))
        assertEquals("<0.000001%", ExplorationStats.formatPercent(0.00000001))
        assertEquals("0%", ExplorationStats.formatPercent(0.0))
        assertEquals("12.3%", ExplorationStats.formatPercent(12.34))
        assertEquals("0.001%", ExplorationStats.formatPercent(0.001), "trailing zeros should be trimmed")
    }

    @Test
    fun `areas are formatted with a sensible unit`() {
        assertEquals("2500 km²", ExplorationStats.formatArea(2.5e9))
        assertEquals("12.5 km²", ExplorationStats.formatArea(1.25e7))
        assertEquals("0.5 ha", ExplorationStats.formatArea(5_000.0))
    }

    @Test
    fun `distances switch from metres to kilometres`() {
        assertEquals("450 m", ExplorationStats.formatDistance(450.0))
        assertEquals("4.5 km", ExplorationStats.formatDistance(4_500.0))
        assertEquals("1234 km", ExplorationStats.formatDistance(1_234_000.0))
    }
}
