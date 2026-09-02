package dev.jackque.roamed.core.fog

import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.Geo
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns raw GPS fixes into the set of grid cells they uncover.
 *
 * Two things happen here:
 *  - a fix uncovers every cell that its accuracy circle actually touches, not just the cell the
 *    point falls in, so standing still still clears a believable blob;
 *  - consecutive fixes are joined, so driving at 100 km/h with a fix every 20 s leaves a
 *    continuous ribbon instead of a dotted line.
 */
class FogEngine(private val zoom: Int = RevealZoom.Z) {

    /** Metres per degree of latitude; the equirectangular approximation is exact enough at cell scale. */
    private val metersPerDegreeLat = 111_320.0

    /**
     * Every cell whose square overlaps the circle of [radiusMeters] around the fix.
     *
     * Results are added to [into] so callers can accumulate a whole segment without garbage.
     */
    fun cellsWithinRadius(
        lat: Double,
        lon: Double,
        radiusMeters: Double,
        into: MutableSet<Long> = HashSet(),
    ): MutableSet<Long> {
        val radius = max(1.0, radiusMeters)
        val centerLat = TileMath.clampLatitude(lat)
        val grid = TileMath.gridSize(zoom)

        val dLatDeg = radius / metersPerDegreeLat
        val cosLat = max(cos(TileMath.degToRad(centerLat)), 1e-6)
        val dLonDeg = min(180.0, radius / (metersPerDegreeLat * cosLat))

        // y grows southwards, so the northern edge yields the smaller row index.
        val yFrom = TileMath.cellY(centerLat + dLatDeg, zoom)
        val yTo = TileMath.cellY(centerLat - dLatDeg, zoom)

        val centerX = TileMath.lonToTileX(lon, zoom)
        val spanX = dLonDeg / 360.0 * grid
        var xFrom = floor(centerX - spanX).toInt()
        var xTo = floor(centerX + spanX).toInt()
        // Near the poles a modest radius spans an absurd number of columns; cap it.
        if (xTo - xFrom > MAX_COLUMNS) {
            xFrom = floor(centerX).toInt() - MAX_COLUMNS / 2
            xTo = xFrom + MAX_COLUMNS
        }

        for (y in yFrom..yTo) {
            if (y < 0 || y >= grid) continue
            val latNorth = TileMath.tileYToLat(y.toDouble(), zoom)
            val latSouth = TileMath.tileYToLat((y + 1).toDouble(), zoom)
            val dLatMeters = when {
                lat > latNorth -> (lat - latNorth) * metersPerDegreeLat
                lat < latSouth -> (latSouth - lat) * metersPerDegreeLat
                else -> 0.0
            }
            if (dLatMeters > radius) continue

            val nearestLat = lat.coerceIn(latSouth, latNorth)
            val metersPerDegreeLon = metersPerDegreeLat * max(cos(TileMath.degToRad(nearestLat)), 1e-6)
            val cellWidthDeg = 360.0 / grid

            for (x in xFrom..xTo) {
                val lonWest = TileMath.tileXToLon(x.toDouble(), zoom)
                val offset = signedLonDelta(lonWest, lon)
                val dLonDegOff = when {
                    offset < 0.0 -> -offset
                    offset > cellWidthDeg -> offset - cellWidthDeg
                    else -> 0.0
                }
                val dLonMeters = dLonDegOff * metersPerDegreeLon
                if (dLonMeters > radius) continue
                if (sqrt(dLatMeters * dLatMeters + dLonMeters * dLonMeters) > radius) continue
                into.add(CellKey.pack(TileMath.wrapX(x, zoom), y))
            }
        }
        // A fix always uncovers at least the cell it sits in.
        if (into.isEmpty()) {
            into.add(CellKey.pack(TileMath.cellX(lon, zoom), TileMath.cellY(centerLat, zoom)))
        }
        return into
    }

    /**
     * Cells uncovered by travelling in a straight line between two fixes.
     *
     * Returns an empty set when the gap is longer than [maxGapMeters] - a jump that big is a
     * flight, a tunnel or a GPS glitch, and painting a line across it would be a lie.
     */
    fun cellsAlongSegment(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        radiusMeters: Double,
        maxGapMeters: Double = DEFAULT_MAX_GAP_METERS,
        into: MutableSet<Long> = HashSet(),
    ): MutableSet<Long> {
        val distance = Geo.distanceMeters(lat1, lon1, lat2, lon2)
        if (distance > maxGapMeters) return into

        cellsWithinRadius(lat1, lon1, radiusMeters, into)
        cellsWithinRadius(lat2, lon2, radiusMeters, into)
        if (distance < 1.0) return into

        // Step at half the smaller of the reveal radius and the cell size so nothing is skipped.
        val cellWidth = TileMath.cellWidthMeters(TileMath.cellY(lat1, zoom), zoom)
        val step = max(5.0, min(radiusMeters, cellWidth) / 2.0)
        val steps = min(MAX_INTERPOLATION_STEPS, ceil(distance / step).toInt())
        for (i in 1 until steps) {
            val p = Geo.interpolate(lat1, lon1, lat2, lon2, i.toDouble() / steps)
            cellsWithinRadius(p[0], p[1], radiusMeters, into)
        }
        return into
    }

    /** Signed shortest angular difference `to - from`, in degrees, within (-180, 180]. */
    private fun signedLonDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    companion object {
        /**
         * Furthest apart two fixes can be and still have the road between them filled in.
         *
         * Three kilometres was far too tight: any dropout - a tunnel, a dead zone, a stretch where
         * the fixes were too vague to keep - leaves a bigger hole than that, and refusing to
         * bridge it turns a momentary lapse into a permanent gap in the map. Twenty-five km covers
         * a realistic dropout while staying far below any flight, which shows up as a gap of
         * hundreds of km and is still refused.
         */
        const val DEFAULT_MAX_GAP_METERS = 25_000.0
        private const val MAX_COLUMNS = 4_096
        private const val MAX_INTERPOLATION_STEPS = 4_000
    }
}

/** Speed above which a pair of fixes is treated as a glitch rather than travel (m/s ~ 1100 km/h). */
const val IMPLAUSIBLE_SPEED_MPS = 305.0

/** True when moving between two fixes in [seconds] would be physically implausible. */
fun isImplausibleJump(distanceMeters: Double, seconds: Double): Boolean =
    seconds > 0.0 && distanceMeters / seconds > IMPLAUSIBLE_SPEED_MPS && abs(distanceMeters) > 1_000.0
