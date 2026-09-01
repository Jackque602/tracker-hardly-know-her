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

/** A point on the recorded trail, in the form the overlay wants to draw it. */
data class TrailPoint(val latitude: Double, val longitude: Double)

/**
 * Paints the unexplored world over the map and cuts your travels out of it.
 *
 * The whole effect is one `saveLayer`: fill the layer with fog, then punch the explored cells out
 * with a CLEAR paint. Drawing the holes rather than the fog is what keeps it cheap - there are
 * always far fewer explored cells on screen than there are pixels to cover.
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

    private val fogPath = Path()
    private val trailPath = Path()
    private val scratchPoint = Point()
    private val scratchGeo = GeoPoint(0.0, 0.0)

    /** Remembers which cells were last resolved, so panning does not re-query on every frame. */
    private var cachedCells: LongArray = LongArray(0)
    private var cacheSignature: String? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        val renderZoom = renderZoomFor(mapView.zoomLevelDouble)

        buildFogPath(projection, renderZoom)

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
     * How finely to draw the fog at this map zoom.
     *
     * Cells are stored at z17. Drawing every one of them while looking at a whole continent would
     * mean hundreds of thousands of one-pixel rectangles, so the index collapses them to a coarser
     * grid as you zoom out. Three levels finer than the map keeps each drawn square around 32 px:
     * detailed enough to read as a shape, cheap enough to keep panning smooth.
     */
    private fun renderZoomFor(mapZoom: Double): Int =
        min(RevealZoom.Z, floor(mapZoom).toInt() + DETAIL_LEVELS).coerceIn(0, RevealZoom.Z)

    private fun buildFogPath(projection: Projection, renderZoom: Int) {
        val box = projection.boundingBox
        val grid = TileMath.gridSize(renderZoom)

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

        val signature = "$renderZoom:$xFrom:$xTo:$yFrom:$yTo:${index.version}"
        if (signature != cacheSignature) {
            cachedCells = index.cellsIn(renderZoom, xFrom, xTo, yFrom, yTo)
            cacheSignature = signature
        }

        fogPath.rewind()
        if (cachedCells.isEmpty()) return

        for (key in cachedCells) {
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
            // Half a pixel of overlap hides the hairline seams between neighbouring cells.
            val right = scratchPoint.x.toFloat() + SEAM_OVERLAP_PX
            val bottom = scratchPoint.y.toFloat() + SEAM_OVERLAP_PX

            if (right < left || bottom < top) continue
            fogPath.addRect(left, top, right, bottom, Path.Direction.CW)
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
            val metersPerPixel = TileMath.cellWidthMeters(
                TileMath.cellY(position.latitude, RevealZoom.Z), RevealZoom.Z,
            ) / pixelsPerCell(projection, position)
            if (metersPerPixel > 0f) {
                val radiusPx = accuracy / metersPerPixel
                if (radiusPx in 1f..2_000f) canvas.drawCircle(cx, cy, radiusPx, positionHaloPaint)
            }
        }
        canvas.drawCircle(cx, cy, POSITION_RADIUS_PX, positionPaint)
        canvas.drawCircle(cx, cy, POSITION_RADIUS_PX, positionRingPaint)
    }

    /** Screen width of one storage cell at this position, used to size the accuracy halo. */
    private fun pixelsPerCell(projection: Projection, position: TrailPoint): Float {
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
        val width = (scratchPoint.x - left).toFloat()
        return if (width > 0.01f) width else 0.01f
    }

    private companion object {
        const val DETAIL_LEVELS = 3
        const val SEAM_OVERLAP_PX = 0.5f
        const val POSITION_RADIUS_PX = 11f
    }
}
