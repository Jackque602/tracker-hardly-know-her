package dev.jackque.roamed.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle helpers shared by the tracker and the stats screen. */
object Geo {

    /** Great-circle distance between two points, metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = TileMath.degToRad(lat2 - lat1)
        val dLon = TileMath.degToRad(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(TileMath.degToRad(lat1)) * cos(TileMath.degToRad(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2.0 * TileMath.EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    /** Point [fraction] of the way along the great circle from point 1 to point 2. */
    fun interpolate(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        fraction: Double,
    ): DoubleArray {
        val phi1 = TileMath.degToRad(lat1)
        val lambda1 = TileMath.degToRad(lon1)
        val phi2 = TileMath.degToRad(lat2)
        val lambda2 = TileMath.degToRad(lon2)

        val d = distanceMeters(lat1, lon1, lat2, lon2) / TileMath.EARTH_RADIUS_METERS
        if (d < 1e-12) return doubleArrayOf(lat1, lon1)

        val a = sin((1 - fraction) * d) / sin(d)
        val b = sin(fraction * d) / sin(d)
        val x = a * cos(phi1) * cos(lambda1) + b * cos(phi2) * cos(lambda2)
        val y = a * cos(phi1) * sin(lambda1) + b * cos(phi2) * sin(lambda2)
        val z = a * sin(phi1) + b * sin(phi2)
        return doubleArrayOf(
            TileMath.radToDeg(atan2(z, sqrt(x * x + y * y))),
            TileMath.radToDeg(atan2(y, x)),
        )
    }
}
