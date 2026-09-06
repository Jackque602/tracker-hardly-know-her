package dev.jackque.roamed.core.regions

import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath

/** How much of one region has been uncovered. */
data class RegionProgress(
    val region: Region,
    val exploredSquareMeters: Double,
    /** Never above 100, however coarsely the mask attributed the edges. */
    val percentExplored: Double,
    /** The country a state sits in, or the continent a country sits in. */
    val parentName: String? = null,
)

/**
 * Everywhere you have been, grouped.
 *
 * Each list holds only regions with ground uncovered in them, ordered by how much - so the top of
 * [countries] is the country you have seen most of, not the largest one.
 */
data class RegionTally(
    val continents: List<RegionProgress> = emptyList(),
    val countries: List<RegionProgress> = emptyList(),
    val subdivisions: List<RegionProgress> = emptyList(),
    /** Total continents, countries and subdivisions the mask knows about. */
    val continentsInAtlas: Int = 0,
    val countriesInAtlas: Int = 0,
    /** Uncovered area the mask could not place - at sea, or a coastline it draws too tightly. */
    val unplacedSquareMeters: Double = 0.0,
)

/**
 * Turns a set of uncovered fog cells into per-continent, per-country and per-state figures.
 *
 * Every cell is attributed to exactly one subdivision or country, and its area then counts towards
 * that region and each of its parents - so the numbers nest: a state's area is part of its
 * country's, which is part of its continent's.
 */
object RegionBreakdown {

    fun of(mask: RegionMask, cells: LongArray, cellZoom: Int = RevealZoom.Z): RegionTally {
        val explored = DoubleArray(mask.regions.size)
        val rowArea = HashMap<Int, Double>()
        val shift = cellZoom - mask.zoom
        require(shift >= 0) { "cells are coarser than the mask: $cellZoom < ${mask.zoom}" }
        var unplaced = 0.0

        for (key in cells) {
            val y = CellKey.y(key)
            val area = rowArea.getOrPut(y) { TileMath.areaOfRow(y, cellZoom) }
            var id = mask.regionAt(CellKey.x(key) shr shift, y shr shift)
            if (id == Region.NONE) {
                unplaced += area
                continue
            }
            // Credit the region the cell fell in and every region containing it.
            while (id != Region.NONE) {
                explored[id] += area
                id = mask.regions[id].parentId
            }
        }

        val byKind = HashMap<RegionKind, MutableList<RegionProgress>>()
        for (region in mask.regions) {
            val area = explored[region.id]
            if (area <= 0.0) continue
            byKind.getOrPut(region.kind) { ArrayList() }.add(
                RegionProgress(
                    region = region,
                    exploredSquareMeters = area,
                    percentExplored = percent(area, region.areaSquareMeters),
                    parentName = mask.region(region.parentId)?.name,
                ),
            )
        }
        byKind.values.forEach { list -> list.sortByDescending { it.exploredSquareMeters } }

        return RegionTally(
            continents = byKind[RegionKind.CONTINENT].orEmpty(),
            countries = byKind[RegionKind.COUNTRY].orEmpty(),
            subdivisions = byKind[RegionKind.SUBDIVISION].orEmpty(),
            continentsInAtlas = mask.regions.count { it.kind == RegionKind.CONTINENT },
            countriesInAtlas = mask.regions.count { it.kind == RegionKind.COUNTRY },
            unplacedSquareMeters = unplaced,
        )
    }

    /**
     * Capped at 100%.
     *
     * The mask draws coastlines a few kilometres coarser than they really are, so a walk along a
     * beach can be credited with a square that is partly sea. On a region small enough that this
     * matters, the excess would otherwise read as more than all of it.
     */
    private fun percent(exploredSquareMeters: Double, totalSquareMeters: Double): Double {
        if (totalSquareMeters <= 0.0) return 0.0
        return (exploredSquareMeters / totalSquareMeters * 100.0).coerceAtMost(100.0)
    }
}
