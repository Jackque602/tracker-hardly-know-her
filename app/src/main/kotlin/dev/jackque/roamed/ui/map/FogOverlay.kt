package dev.jackque.roamed.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

/** A point on the recorded trail, in the form the overlay wants to draw it. */
data class TrailPoint(val latitude: Double, val longitude: Double)

/**
 * Paints the unexplored world over the map and cuts your travels out of it.
 *
 * The whole effect is one `saveLayer`: fill the layer with fog, then punch the explored cells out
 * with a CLEAR paint. Drawing the holes rather than the fog is what keeps it cheap - there are
 * always far fewer explored cells on screen than there are pixels to cover.
 *
 * Cells are drawn at their true size on the ground, so the cleared area shrinks as you zoom out
 * exactly like every other feature on the map. A city you have walked stays city-shaped at every
 * zoom instead of swelling into a square the size of a county.
 */
class FogOverlay(private val index: ExploredIndex) : Overlay() {

    /** 0f is no fog at all, 1f is opaque. */
    var opacity: Float = 0.85f

    var trail: List<TrailPoint> = emptyList()
    var showTrail: Boolean = false
    var currentPosition: TrailPoint? = null
    var currentAccuracyMeters: Float? = null

    private val fogPaintColor: Int get() = Color.argb((opacity * 255f).toInt().coerceIn(0, 255), 8, 13, 22)

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(120, 127, 209, 193)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(200, 224, 145, 42)
    }
    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(31, 143, 255)
    }
    private val positionHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(48, 31, 143, 255)
    }
    private val positionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }

    /**
     * Every visible cell goes into one path and is erased in a single pass. Overlapping rectangles
     * inside one path fill once, whereas erasing them one at a time would double-erase every
     * shared edge into a visible grid.
     */
    private val fogPath = Path()
    private val trailPath = Path()
    private val scratchPoint = Point()
    private val scratchGeo = GeoPoint(0.0, 0.0)

    /** Remembers which cells were last resolved, so panning does not re-query on every frame. */
    private var cachedCells: LongArray = LongArray(0)
    private var cachedZoom: Int = RevealZoom.Z
    private var cacheSignature: String? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection

        buildFogPath(projection, mapView.zoomLevelDouble)

        // Fog first, in its own layer, so CLEAR only erases the fog and not the map beneath it.
        val layer = canvas.saveLayer(null, null)
        canvas.drawColor(fogPaintColor)
        canvas.drawPath(fogPath, clearPaint)
        canvas.restoreToCount(layer)
        canvas.drawPath(fogPath, edgePaint)

        if (showTrail) drawTrail(canvas, projection)
        drawCurrentPosition(canvas, projection)
    }

    /**
     * The finest zoom worth drawing at, which is the storage zoom until the cells get too small to
     * see.
     *
     * A map tile is 256 px, so a cell at `renderZoom` covers `256 / 2^(renderZoom - mapZoom)`
     * pixels on screen. Seven levels finer than the map is where that reaches about two pixels;
     * past that the cells are smaller than a pixel and collapsing them changes nothing visible.
     * Below map zoom 10 this is what applies, and above it the fog is drawn at full z17 detail.
     */
    private fun idealRenderZoom(mapZoom: Double): Int =
        min(RevealZoom.Z, floor(mapZoom).toInt() + LEVELS_BELOW_MAP).coerceIn(0, RevealZoom.Z)

    /** Screen width of one cell at this render zoom, in pixels. */
    private fun cellPixelSize(mapZoom: Double, renderZoom: Int): Float =
        (TILE_SIZE_PX * 2.0.pow(mapZoom - renderZoom)).toFloat()

    private fun buildFogPath(projection: Projection, mapZoom: Double) {
        val ideal = idealRenderZoom(mapZoom)
        val signature = "${projection.boundingBox}:$ideal:${index.version}"
        if (signature != cacheSignature) {
            resolveCells(projection, ideal)
            cacheSignature = signature
        }

        fogPath.rewind()
        val cells = cachedCells
        if (cells.isEmpty()) return

        val renderZoom = cachedZoom
        // Half a pixel of overlap hides hairline seams between neighbours, but on a two-pixel cell
        // that would be a quarter of its width, so it is scaled down with the cells.
        val overlap = min(SEAM_OVERLAP_PX, cellPixelSize(mapZoom, renderZoom) * 0.15f)

        for (key in cells) {
            val x = CellKey.x(key)
            val y = CellKey.y(key)
            scratchGeo.setCoords(
                TileMath.tileYToLat(y.toDouble(), renderZoom),
                TileMath.tileXToLon(x.toDouble(), renderZoom),
            )
            projection.toPixels(scratchGeo, scratchPoint)
            val left = scratchPoint.x.toFloat()
            val top = scratchPoint.y.toFloat()

            scratchGeo.setCoords(
                TileMath.tileYToLat((y + 1).toDouble(), renderZoom),
                TileMath.tileXToLon((x + 1).toDouble(), renderZoom),
            )
            projection.toPixels(scratchGeo, scratchPoint)
            val right = scratchPoint.x.toFloat() + overlap
            val bottom = scratchPoint.y.toFloat() + overlap

            if (right < left || bottom < top) continue
            fogPath.addRect(left, top, right, bottom, Path.Direction.CW)
        }
    }

    /**
     * Fetches the visible cells, coarsening only if there are more of them than can be drawn in a
     * frame.
     *
     * The budget almost never bites: it takes tens of thousands of cells in one viewport, which
     * means an area so densely covered that a coarser square is nearly full anyway. Coarsening
     * there costs a pixel or two of accuracy and saves the frame rate.
     */
    private fun resolveCells(projection: Projection, idealZoom: Int) {
        var renderZoom = idealZoom
        while (true) {
            val grid = TileMath.gridSize(renderZoom)
            val box = projection.boundingBox
            val yFrom = TileMath.cellY(box.latNorth, renderZoom) - 1
            val yTo = TileMath.cellY(box.latSouth, renderZoom) + 1
            var xFrom = floor(TileMath.lonToTileX(box.lonWest, renderZoom)).toInt() - 1
            var xTo = floor(TileMath.lonToTileX(box.lonEast, renderZoom)).toInt() + 1
            // A viewport straddling the antimeridian reports east < west; unwrap it.
            if (xTo < xFrom) xTo += grid
            if (xTo - xFrom > grid) {
                xFrom = 0
                xTo = grid - 1
            }

            val found = index.cellsIn(renderZoom, xFrom, xTo, yFrom, yTo)
            if (found.size <= MAX_CELLS_PER_FRAME || renderZoom == 0) {
                cachedCells = found
                cachedZoom = renderZoom
                return
            }
            renderZoom--
        }
    }

    private fun drawTrail(canvas: Canvas, projection: Projection) {
        if (trail.size < 2) return
        trailPath.rewind()
        trail.forEachIndexed { i, point ->
            scratchGeo.setCoords(point.latitude, point.longitude)
            projection.toPixels(scratchGeo, scratchPoint)
            if (i == 0) {
                trailPath.moveTo(scratchPoint.x.toFloat(), scratchPoint.y.toFloat())
            } else {
                trailPath.lineTo(scratchPoint.x.toFloat(), scratchPoint.y.toFloat())
            }
        }
        canvas.drawPath(trailPath, trailPaint)
    }

    private fun drawCurrentPosition(canvas: Canvas, projection: Projection) {
        val position = currentPosition ?: return
        scratchGeo.setCoords(position.latitude, position.longitude)
        projection.toPixels(scratchGeo, scratchPoint)
        val cx = scratchPoint.x.toFloat()
        val cy = scratchPoint.y.toFloat()

        currentAccuracyMeters?.let { accuracy ->
            val cellWidthMeters = TileMath.cellWidthMeters(
                TileMath.cellY(position.latitude, RevealZoom.Z), RevealZoom.Z,
            )
            val metersPerPixel = cellWidthMeters / pixelsPerCell(projection, position)
            if (metersPerPixel > 0.0) {
                val radiusPx = (accuracy / metersPerPixel).toFloat()
                if (radiusPx in MIN_HALO_PX..MAX_HALO_PX) {
                    canvas.drawCircle(cx, cy, radiusPx, positionHaloPaint)
                }
            }
        }
        canvas.drawCircle(cx, cy, POSITION_RADIUS_PX, positionPaint)
        canvas.drawCircle(cx, cy, POSITION_RADIUS_PX, positionRingPaint)
    }

    /**
     * Screen width of one storage cell at this position, used to size the accuracy halo.
     *
     * Note this clobbers [scratchPoint], so callers must have read anything they still need out of
     * it first.
     */
    private fun pixelsPerCell(projection: Projection, position: TrailPoint): Double {
        val z = RevealZoom.Z
        val x = TileMath.cellX(position.longitude, z)
        val y = TileMath.cellY(position.latitude, z)
        scratchGeo.setCoords(TileMath.tileYToLat(y.toDouble(), z), TileMath.tileXToLon(x.toDouble(), z))
        projection.toPixels(scratchGeo, scratchPoint)
        val left = scratchPoint.x
        scratchGeo.setCoords(
            TileMath.tileYToLat(y.toDouble(), z),
            TileMath.tileXToLon((x + 1).toDouble(), z),
        )
        projection.toPixels(scratchGeo, scratchPoint)
        val width = (scratchPoint.x - left).toDouble()
        return if (width > 0.01) width else 0.01
    }

    private companion object {
        /** Web Mercator map tiles are 256 px square. */
        const val TILE_SIZE_PX = 256.0

        /** 2^7 = 128, so 256 px / 128 is the ~2 px floor below which cells stop being visible. */
        const val LEVELS_BELOW_MAP = 7

        /** Above this many rectangles in one viewport, drop a level rather than drop frames. */
        const val MAX_CELLS_PER_FRAME = 12_000

        const val SEAM_OVERLAP_PX = 0.5f
        const val POSITION_RADIUS_PX = 11f
        const val MIN_HALO_PX = 1f
        const val MAX_HALO_PX = 2_000f
    }
}
