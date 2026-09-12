package dev.jackque.roamed.core.geo

/**
 * A lat/lon rectangle.
 *
 * [east] is allowed to be numerically smaller than [west]: that is how a box which runs off the
 * eastern edge of the map and back in from the west is expressed, and it is the correct shape for
 * anyone who has been to both sides of the Pacific.
 */
data class GeoBounds(
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
) {
    /** True when the box wraps past +180 and continues from -180. */
    val crossesAntimeridian: Boolean get() = east < west

    /** Width in degrees, measured the way the box actually runs. */
    val longitudeSpan: Double
        get() = if (crossesAntimeridian) (east + 360.0) - west else east - west

    val latitudeSpan: Double get() = north - south

    val centerLatitude: Double get() = (north + south) / 2.0

    /** Middle of the box the way the box actually runs, so a wrapped box centres on the Pacific. */
    val centerLongitude: Double
        get() = TileMath.normalizeLongitude(west + longitudeSpan / 2.0)
}
