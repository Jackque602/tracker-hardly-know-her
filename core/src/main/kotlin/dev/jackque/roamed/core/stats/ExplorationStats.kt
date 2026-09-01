package dev.jackque.roamed.core.stats

import dev.jackque.roamed.core.geo.TileMath
import java.util.Locale

/** Derived numbers for the stats screen. Pure functions, so they are easy to trust and to test. */
object ExplorationStats {

    fun percentOfEarthSurface(areaSquareMeters: Double): Double =
        areaSquareMeters / TileMath.EARTH_SURFACE_AREA_M2 * 100.0

    fun percentOfEarthLand(areaSquareMeters: Double): Double =
        areaSquareMeters / TileMath.EARTH_LAND_AREA_M2 * 100.0

    fun squareKilometers(areaSquareMeters: Double): Double = areaSquareMeters / 1_000_000.0

    /**
     * Renders a percentage that is usually tiny without collapsing it to "0%".
     *
     * A lifetime of travel covers a rounding error of the planet, and showing that as zero is the
     * fastest way to make the number feel broken - so the precision grows as the value shrinks.
     */
    fun formatPercent(percent: Double): String = when {
        percent <= 0.0 -> "0%"
        percent >= 10.0 -> fixed(percent, 1) + "%"
        percent >= 1.0 -> fixed(percent, 2) + "%"
        percent >= 0.01 -> fixed(percent, 3) + "%"
        percent >= 0.0001 -> fixed(percent, 5) + "%"
        percent >= 0.000001 -> fixed(percent, 7) + "%"
        else -> "<0.000001%"
    }

    fun formatArea(areaSquareMeters: Double): String {
        val km2 = squareKilometers(areaSquareMeters)
        return when {
            km2 >= 1_000.0 -> fixed(km2, 0) + " km²"
            km2 >= 10.0 -> fixed(km2, 1) + " km²"
            km2 >= 0.01 -> fixed(km2, 2) + " km²"
            else -> fixed(areaSquareMeters / 10_000.0, 2) + " ha"
        }
    }

    fun formatDistance(meters: Double): String = when {
        meters >= 100_000 -> fixed(meters / 1_000.0, 0) + " km"
        meters >= 1_000 -> fixed(meters / 1_000.0, 1) + " km"
        else -> fixed(meters, 0) + " m"
    }

    /** Fixed-point with trailing zeros trimmed, so "0.00100" reads as "0.001". */
    private fun fixed(value: Double, decimals: Int): String {
        val text = String.format(Locale.US, "%.${decimals}f", value)
        if (!text.contains('.')) return text
        return text.trimEnd('0').trimEnd('.')
    }
}
