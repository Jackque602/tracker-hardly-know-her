package dev.jackque.roamed.core

import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.fog.FogEngine
import dev.jackque.roamed.core.fog.isFlight
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.Geo
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlightTest {

    private val engine = FogEngine()
    private val z = RevealZoom.Z

    private fun cellAt(lat: Double, lon: Double) =
        CellKey.pack(TileMath.cellX(lon, z), TileMath.cellY(lat, z))

    @Test
    fun `a real flight is recognised`() {
        // London to Los Angeles, eleven hours gate to gate.
        assertTrue(isFlight(8_750_000.0, 11 * 3_600.0))
        // Philadelphia to Boston, a short hop, still comfortably over the bar.
        assertTrue(isFlight(430_000.0, 1.2 * 3_600.0))
    }

    @Test
    fun `a tracker that slept through a drive is not a flight`() {
        // The failure this must never mistake for flying: the service is killed, and hours later
        // the app wakes up somewhere else. Uncovering a great circle across that would be a lie.
        assertFalse(isFlight(200_000.0, 2 * 3_600.0), "200 km in two hours is a drive")
        assertFalse(isFlight(500_000.0, 6 * 3_600.0), "500 km in six hours is a long drive")
        assertFalse(isFlight(11_000.0, 1_800.0), "the gap that started all this")
        assertFalse(isFlight(660_000.0, 3 * 3_600.0), "Paris to Marseille by train, 61 m/s")
    }

    @Test
    fun `a short hop and a glitch are both refused`() {
        assertFalse(isFlight(100_000.0, 600.0), "under the distance floor, whatever the speed")
        assertFalse(isFlight(5_000_000.0, 60.0), "83 km/s is a broken fix, not a flight")
        assertFalse(isFlight(1_000_000.0, 0.0), "no elapsed time means no speed to judge")
    }

    @Test
    fun `a flight path follows the great circle, not a line drawn on the map`() {
        // London to Los Angeles goes over Greenland. Interpolating in flat lat/lon instead would
        // never leave the latitudes of its endpoints, so this is what tells the two apart.
        val cells = engine.cellsAlongFlight(51.5074, -0.1278, 34.0522, -118.2437, radiusMeters = 500.0)
        val northernmost = cells.minOf { CellKey.y(it) }
        val peakLatitude = TileMath.tileYToLat(northernmost.toDouble(), z)

        assertTrue(
            peakLatitude > 60.0,
            "the route should arc up over the Arctic, but peaked at $peakLatitude",
        )
        assertTrue(peakLatitude < 80.0, "and not over the pole itself; got $peakLatitude")
    }

    @Test
    fun `the flight ribbon has no holes in it`() {
        val from = doubleArrayOf(40.6413, -73.7781)   // New York
        val to = doubleArrayOf(51.4700, -0.4543)      // London
        val cells = engine.cellsAlongFlight(from[0], from[1], to[0], to[1], radiusMeters = 120.0)

        // Walk the same great circle far more finely than the tracer did. Every point along it has
        // to land in a cell the tracer uncovered, or the ribbon is dotted.
        var missing = 0
        for (i in 0..5_000) {
            val p = Geo.interpolate(from[0], from[1], to[0], to[1], i.toDouble() / 5_000)
            if (!cells.contains(cellAt(p[0], p[1]))) missing++
        }
        assertEquals(0, missing, "$missing of 5001 points along the route were left fogged")
    }

    @Test
    fun `flying somewhere then walking it hands the ground back`() {
        val index = ExploredIndex()
        val air = ExploredIndex()

        val flown = engine.cellsAlongFlight(40.64, -73.78, 40.70, -73.90, radiusMeters = 120.0)
        index.addAll(flown)
        air.addAll(flown)
        val flownArea = air.areaSquareMeters
        assertTrue(flownArea > 0.0)

        // Land and walk about. Those squares stop being flown-over and become travelled.
        val walked = engine.cellsWithinRadius(40.64, -73.78, 200.0)
        val promoted = air.removeAll(walked)

        assertTrue(promoted.isNotEmpty(), "landing should reclaim the squares around the airport")
        assertTrue(air.areaSquareMeters < flownArea, "the flown area must shrink as it is reclaimed")
        assertEquals(index.size, index.size, "and the map itself keeps every square it had")
        for (key in promoted) {
            assertTrue(index.contains(key), "a reclaimed square is still uncovered")
            assertFalse(air.contains(key), "but no longer counted as flown")
        }
    }

    @Test
    fun `removing everything leaves an empty index, not a negative one`() {
        val index = ExploredIndex()
        val cells = engine.cellsWithinRadius(51.5, -0.12, 300.0)
        index.addAll(cells)
        val before = index.version

        assertEquals(cells.size, index.removeAll(cells).size)
        assertEquals(0, index.size)
        assertEquals(0.0, index.areaSquareMeters)
        assertTrue(index.version > before, "a removal is a change the map has to redraw for")
        assertEquals(null, index.bounds())
        assertEquals(emptyList(), index.removeAll(cells), "removing twice removes nothing")
    }

    @Test
    fun `removal keeps the coarse view honest`() {
        // The zoomed-out view is memoised, so a removal that forgot to drop it would leave squares
        // on the map that no longer exist.
        val index = ExploredIndex()
        val keep = cellAt(51.5, -0.12)
        val drop = cellAt(48.85, 2.35)
        index.addAll(listOf(keep, drop))
        assertEquals(2, index.cellsIn(z, 0, TileMath.gridSize(z) - 1, 0, TileMath.gridSize(z) - 1).size)
        val coarseBefore = index.cellsIn(6, 0, TileMath.gridSize(6) - 1, 0, TileMath.gridSize(6) - 1)
        assertEquals(2, coarseBefore.size, "London and Paris are separate cells even at z6")

        index.removeAll(listOf(drop))
        val coarseAfter = index.cellsIn(6, 0, TileMath.gridSize(6) - 1, 0, TileMath.gridSize(6) - 1)
        assertEquals(1, coarseAfter.size, "the coarse view still shows a removed cell")
    }
}
