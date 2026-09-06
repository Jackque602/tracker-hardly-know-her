package dev.jackque.roamed.core

import dev.jackque.roamed.core.fog.FogEngine
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.core.regions.Region
import dev.jackque.roamed.core.regions.RegionBreakdown
import dev.jackque.roamed.core.regions.RegionKind
import dev.jackque.roamed.core.regions.RegionMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the mask that actually ships, not a fixture.
 *
 * The file is written by a Python script and read by Kotlin, so nothing but reading the real bytes
 * back proves the two agree on the format - or that the boundaries landed where they should.
 */
class RegionMaskTest {

    private val mask = RegionMask.bundled()

    private fun names(latitude: Double, longitude: Double): List<String> =
        mask.lineage(mask.regionAt(latitude, longitude)).map { it.name }

    @Test
    fun `the shipped mask is the zoom the code expects`() {
        assertEquals(RegionMask.MASK_ZOOM, mask.zoom)
        assertTrue(mask.regions.size > 4_000, "only ${mask.regions.size} regions")
    }

    @Test
    fun `places resolve to their state, country and continent`() {
        assertEquals(
            listOf("Pennsylvania", "United States of America", "North America"),
            names(39.9626, -76.7277),
            "York, Pennsylvania",
        )
        assertEquals(
            listOf("Delaware", "United States of America", "North America"),
            names(39.6639, -75.6093),
            "Christiana, Delaware",
        )
        assertEquals("Alaska", names(61.2181, -149.9003).first())
        assertEquals("Hawaii", names(21.3069, -157.8583).first())
        assertEquals(listOf("Japan", "Asia"), names(35.6762, 139.6503).drop(1))
        assertEquals(listOf("Australia", "Oceania"), names(-33.8688, 151.2093).drop(1))
        assertEquals(listOf("Brazil", "South America"), names(-23.5505, -46.6333).drop(1))
        assertEquals(listOf("Kenya", "Africa"), names(-1.2921, 36.8219).drop(1))
        assertEquals(listOf("France", "Europe"), names(48.8566, 2.3522).drop(1))
    }

    @Test
    fun `the open ocean belongs to nobody`() {
        assertEquals(Region.NONE, mask.regionAt(30.0, -40.0), "mid-Atlantic")
        assertEquals(Region.NONE, mask.regionAt(-20.0, -120.0), "south Pacific")
    }

    @Test
    fun `Russia counts as Asia, so Europe is not mostly Siberia`() {
        assertEquals(listOf("Russia", "Asia"), names(55.7558, 37.6173).drop(1))
        val europe = mask.regions.single { it.kind == RegionKind.CONTINENT && it.name == "Europe" }
        // Europe without Russia is about 6 million km2; with it, over 22 million.
        assertTrue(
            europe.areaSquareMeters / 1e12 in 5.0..8.0,
            "Europe came out ${europe.areaSquareMeters / 1e12} million km2",
        )
    }

    @Test
    fun `region areas match the published figures`() {
        fun areaKm2(name: String, kind: RegionKind) =
            mask.regions.first { it.kind == kind && it.name == name }.areaSquareMeters / 1e6

        // Within a few percent: the boundaries are generalised, and coasts are drawn at low tide.
        assertClose(119_280.0, areaKm2("Pennsylvania", RegionKind.SUBDIVISION), 0.05)
        assertClose(423_970.0, areaKm2("California", RegionKind.SUBDIVISION), 0.05)
        assertClose(9_984_670.0, areaKm2("Canada", RegionKind.COUNTRY), 0.08)
        assertClose(377_975.0, areaKm2("Japan", RegionKind.COUNTRY), 0.10)
    }

    @Test
    fun `every region hangs off the right kind of parent`() {
        for (region in mask.regions) {
            val parent = if (region.parentId == Region.NONE) null else mask.region(region.parentId)
            when (region.kind) {
                RegionKind.CONTINENT -> assertEquals(
                    Region.NONE, region.parentId, "${region.name} should sit at the top",
                )
                RegionKind.COUNTRY -> assertEquals(
                    RegionKind.CONTINENT, assertNotNull(parent, region.name).kind,
                    "${region.name} should hang off a continent",
                )
                RegionKind.SUBDIVISION -> assertEquals(
                    RegionKind.COUNTRY, assertNotNull(parent, region.name).kind,
                    "${region.name} should hang off a country",
                )
            }
            assertTrue(region.areaSquareMeters > 0.0, "${region.name} has no area")
            assertTrue(region.name.isNotBlank(), "region ${region.id} has no name")
        }
    }

    @Test
    fun `runs within a row are sorted and never overlap`() {
        // The lookup binary-searches each row, which is only correct if the file says this.
        var checked = 0
        for (y in 0 until mask.gridSize step 7) {
            var previousEnd = -1
            var x = 0
            while (x < mask.gridSize) {
                val id = mask.regionAt(x, y)
                if (id != Region.NONE) {
                    assertTrue(x > previousEnd, "row $y went backwards at $x")
                    previousEnd = x
                    checked++
                }
                x++
            }
        }
        assertTrue(checked > 10_000, "only $checked squares had an owner; the sweep found nothing")
    }

    @Test
    fun `a drive through two states is counted under both, and under one country`() {
        val fog = FogEngine()
        val cells = HashSet<Long>()
        // Roughly the drive up I-95 and I-83: Delaware, then Pennsylvania.
        fog.cellsWithinRadius(39.6639, -75.6093, 200.0, cells)
        fog.cellsWithinRadius(39.9626, -76.7277, 200.0, cells)
        fog.cellsWithinRadius(40.1295, -77.0155, 200.0, cells)

        val tally = RegionBreakdown.of(mask, cells.toLongArray())
        assertEquals(
            setOf("Pennsylvania", "Delaware"),
            tally.subdivisions.map { it.region.name }.toSet(),
            "both states should be counted",
        )
        // Two of the three circles are in Pennsylvania, but Pennsylvania is twenty-three times the
        // size of Delaware, so the smaller state is the one further along - and that is the order.
        assertEquals("Delaware", tally.subdivisions.first().region.name)
        assertTrue(
            tally.subdivisions[0].percentExplored > tally.subdivisions[1].percentExplored,
            "the list must run in the same direction as the percentages it shows",
        )
        assertTrue(
            tally.subdivisions[1].exploredSquareMeters > tally.subdivisions[0].exploredSquareMeters,
            "and that is deliberately not the same as ranking by area covered",
        )
        assertEquals(listOf("United States of America"), tally.countries.map { it.region.name })
        assertEquals(listOf("North America"), tally.continents.map { it.region.name })
        assertEquals(0.0, tally.unplacedSquareMeters, "none of this is at sea")
    }

    @Test
    fun `a state's area is part of its country's, which is part of its continent's`() {
        val fog = FogEngine()
        val cells = HashSet<Long>()
        fog.cellsWithinRadius(39.9626, -76.7277, 300.0, cells)  // Pennsylvania
        fog.cellsWithinRadius(48.8566, 2.3522, 300.0, cells)    // Paris
        fog.cellsWithinRadius(-33.8688, 151.2093, 300.0, cells) // Sydney

        val tally = RegionBreakdown.of(mask, cells.toLongArray())
        val states = tally.subdivisions.sumOf { it.exploredSquareMeters }
        val countries = tally.countries.sumOf { it.exploredSquareMeters }
        val continents = tally.continents.sumOf { it.exploredSquareMeters }

        assertEquals(3, tally.countries.size)
        assertEquals(3, tally.continents.size)
        assertTrue(states <= countries + 1e-6, "$states km2 of states beat $countries of countries")
        assertEquals(countries, continents, 1e-6)
        assertTrue(tally.countriesInAtlas > 200, "the atlas should know the whole world")
        assertTrue(tally.continentsInAtlas in 6..8, "got ${tally.continentsInAtlas} continents")
    }

    @Test
    fun `covering a whole region never reads as more than all of it`() {
        // Every square of Delaware, mask and all, plus the coastal squares that are really sea.
        val delaware = mask.regions.first {
            it.kind == RegionKind.SUBDIVISION && it.name == "Delaware"
        }
        val cells = ArrayList<Long>()
        val shift = RevealZoom.Z - mask.zoom
        for (maskY in TileMath.cellY(39.9, mask.zoom)..TileMath.cellY(38.4, mask.zoom)) {
            for (maskX in TileMath.cellX(-75.9, mask.zoom)..TileMath.cellX(-75.0, mask.zoom)) {
                if (mask.regionAt(maskX, maskY) != delaware.id) continue
                // One fog cell in each corner is enough to stand for the whole mask square.
                cells.add(CellKey.pack(maskX shl shift, maskY shl shift))
                cells.add(CellKey.pack((maskX shl shift) + 1, (maskY shl shift) + 1))
            }
        }
        assertTrue(cells.isNotEmpty(), "Delaware should own some mask squares")

        val tally = RegionBreakdown.of(mask, cells.toLongArray())
        val progress = tally.subdivisions.single { it.region.name == "Delaware" }
        assertTrue(progress.percentExplored <= 100.0, "got ${progress.percentExplored}%")
        assertTrue(progress.percentExplored > 0.0)
    }

    @Test
    fun `cells at sea are reported rather than quietly dropped`() {
        val fog = FogEngine()
        val cells = fog.cellsWithinRadius(30.0, -40.0, 500.0)
        val tally = RegionBreakdown.of(mask, cells.toLongArray())
        assertTrue(tally.countries.isEmpty())
        assertTrue(tally.unplacedSquareMeters > 0.0, "mid-ocean cells should count as unplaced")
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        val error = kotlin.math.abs(actual - expected) / expected
        assertTrue(error <= tolerance, "expected ~$expected, got $actual (${(error * 100).toInt()}% out)")
    }
}
