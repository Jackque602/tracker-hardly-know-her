package dev.jackque.roamed.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web-Mercator ("slippy tile") maths.
 *
 * The fog of war is stored as a set of square cells taken from the standard tile grid at
 * [RevealZoom.Z]. Everything the app knows about "where you have been" is expressed in that
 * grid, so all conversions live here and nowhere else.
 */
object TileMath {

    /** Latitude beyond which Web Mercator is undefined. */
    const val MAX_LATITUDE = 85.05112877980659

    /** Mean Earth radius (IUGG), metres. */
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Total surface of the Earth, m^2. */
    const val EARTH_SURFACE_AREA_M2 = 5.10072e14

    /** Land surface of the Earth (148.94 million km^2), m^2. */
    const val EARTH_LAND_AREA_M2 = 1.4894e14

    fun degToRad(deg: Double): Double = deg * PI / 180.0

    fun radToDeg(rad: Double): Double = rad * 180.0 / PI

    fun clampLatitude(lat: Double): Double = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    /** Wraps a longitude into [-180, 180). */
    fun normalizeLongitude(lon: Double): Double {
        if (lon >= -180.0 && lon < 180.0) return lon
        var l = (lon + 180.0) % 360.0
        if (l < 0) l += 360.0
        return l - 180.0
    }

    /** Number of cells along one axis at [zoom]. */
    fun gridSize(zoom: Int): Int = 1 shl zoom

    fun lonToTileX(lon: Double, zoom: Int): Double =
        (normalizeLongitude(lon) + 180.0) / 360.0 * gridSize(zoom)

    fun latToTileY(lat: Double, zoom: Int): Double {
        val rad = degToRad(clampLatitude(lat))
        val y = ln(tan(rad) + 1.0 / cos(rad))
        return (1.0 - y / PI) / 2.0 * gridSize(zoom)
    }

    fun tileXToLon(x: Double, zoom: Int): Double = x / gridSize(zoom) * 360.0 - 180.0

    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = PI * (1.0 - 2.0 * y / gridSize(zoom))
        return radToDeg(atan(sinh(n)))
    }

    /** Column of the cell containing [lon]; always inside the grid. */
    fun cellX(lon: Double, zoom: Int): Int {
        val max = gridSize(zoom) - 1
        return floor(lonToTileX(lon, zoom)).toInt().coerceIn(0, max)
    }

    /** Row of the cell containing [lat]; always inside the grid. */
    fun cellY(lat: Double, zoom: Int): Int {
        val max = gridSize(zoom) - 1
        return floor(latToTileY(lat, zoom)).toInt().coerceIn(0, max)
    }

    /** Wraps a column index around the antimeridian instead of clamping it. */
    fun wrapX(x: Int, zoom: Int): Int {
        val n = gridSize(zoom)
        val m = x % n
        return if (m < 0) m + n else m
    }

    /**
     * Exact spherical area of a single cell, m^2.
     *
     * A Mercator cell is a lat/lon rectangle, so its area on a sphere is
     * `dLon * R^2 * (sin(latNorth) - sin(latSouth))`. Cells only vary with their row, which is
     * what makes [areaOfRow] cheap to memoise.
     */
    fun areaOfRow(y: Int, zoom: Int): Double {
        val latNorth = degToRad(tileYToLat(y.toDouble(), zoom))
        val latSouth = degToRad(tileYToLat((y + 1).toDouble(), zoom))
        val dLon = 2.0 * PI / gridSize(zoom)
        return abs(dLon * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * (sin(latNorth) - sin(latSouth)))
    }

    /** Approximate east-west size of a cell in that row, metres. */
    fun cellWidthMeters(y: Int, zoom: Int): Double {
        val latCenter = (tileYToLat(y.toDouble(), zoom) + tileYToLat((y + 1).toDouble(), zoom)) / 2.0
        return 2.0 * PI * EARTH_RADIUS_METERS * cos(degToRad(latCenter)) / gridSize(zoom)
    }
}

/**
 * The zoom level the fog grid is stored at.
 *
 * At z17 a cell is roughly 305 m across at the equator and 195 m at 50 deg latitude - fine enough
 * that a walk around a neighbourhood carves a recognisable shape, coarse enough that a decade of
 * tracking stays in the low hundreds of thousands of rows.
 *
 * Changing this invalidates every stored cell, so it is a constant rather than a setting.
 */
object RevealZoom {
    const val Z = 17
}
