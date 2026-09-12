package dev.jackque.roamed.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.core.geo.Viewport
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import kotlin.math.min

/**
 * Draws the fog map onto the car screen.
 *
 * The phone has it easy: osmdroid owns a `MapView` and the fog is an overlay on top of it. The car
 * hands over a bare [Surface] and nothing else, and a `View` cannot be attached to one - so the
 * map is assembled here by hand. osmdroid is still doing the hard part, because its tile provider
 * works perfectly well without a map view: ask it for a tile and it either returns one from cache
 * or fetches it and calls back. That keeps the disk cache, the OSM usage policy and the user agent
 * exactly as they are on the phone, rather than growing a second tile stack that gets it wrong.
 *
 * Everything visible is drawn here, the status line included. Putting that text on the canvas
 * instead of in a navigation template is deliberate: the template host has opinions about what
 * navigation information may say and when, and this app is not navigating anywhere.
 */
class CarMapRenderer(
    context: Context,
    private val index: ExploredIndex,
    private val airIndex: ExploredIndex,
) {

    private val tileProvider = MapTileProviderBasic(context, TileSourceFactory.MAPNIK)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Tiles arrive one at a time and out of order, so each arrival asks for a redraw - coalesced,
     * or a screenful of tiles landing at once would each repaint the whole map.
     */
    private val tileArrivals = Handler(
        Looper.getMainLooper(),
        Handler.Callback {
            scheduleDraw()
            true
        },
    )

    private var surface: Surface? = null
    private var drawPending = false

    /**
     * Where the view is, kept as plain numbers rather than a [Viewport].
     *
     * The car hands over its surface whenever it feels like it, which may be well after the first
     * fix arrives or well before. Holding the centre and zoom separately means the view can be
     * aimed somewhere sensible at any point and the viewport built from it when there is finally a
     * canvas with a size.
     */
    private var centerLatitude = 0.0
    private var centerLongitude = 0.0
    private var zoom = DEFAULT_ZOOM
    private var widthPx = 0
    private var heightPx = 0
    private var tileSizePx = Viewport.DEFAULT_TILE_SIZE_PX

    /** True until the view has been aimed at anything real, so a fallback only lands once. */
    private var neverAimed = true

    /**
     * The part of the surface the host promises not to cover with its own controls.
     *
     * Without it the status line can end up behind the car's own navigation bar, which is the one
     * thing on this screen that has to be readable at a glance.
     */
    private var stableArea: Rect? = null

    /** Set false the moment the driver pans, so their position stops dragging the view back. */
    private var following = true

    var position: Position? = null
        private set

    /** The line along the bottom. Whatever the screen wants to say about tracking. */
    var status: String = ""
        private set

    private val fogPath = Path()
    private val airPath = Path()

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val airPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(96, 56, 132, 255)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(120, 127, 209, 193)
    }
    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(31, 143, 255)
    }
    private val positionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val stripPaint = Paint().apply { color = Color.argb(214, 10, 14, 22) }
    private val stripTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Where you are, in the only terms this renderer needs. */
    data class Position(val latitude: Double, val longitude: Double, val accuracyMeters: Float?)

    init {
        tileProvider.setTileRequestCompleteHandler(tileArrivals)
    }

    /** The current view, or null until the car has given us a canvas with a size. */
    private fun viewport(): Viewport? {
        if (widthPx <= 0 || heightPx <= 0) return null
        return Viewport(
            centerLatitude = centerLatitude,
            centerLongitude = centerLongitude,
            zoom = zoom,
            widthPx = widthPx.toDouble(),
            heightPx = heightPx.toDouble(),
            tileSizePx = tileSizePx,
        )
    }

    fun attach(surface: Surface, widthPx: Int, heightPx: Int, dpi: Int) {
        this.surface = surface
        this.widthPx = widthPx
        this.heightPx = heightPx
        // A 256 px tile on a 300 dpi car screen is the size of a postage stamp; scale it up so the
        // map reads at a glance, which is the only way anyone should be reading it while driving.
        val scale = (dpi / 160.0).coerceIn(1.0, 3.0)
        tileSizePx = Viewport.DEFAULT_TILE_SIZE_PX * scale
        stripTextPaint.textSize = (29.0 * scale).toFloat()
        scheduleDraw()
    }

    fun detach() {
        surface = null
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        tileArrivals.removeCallbacksAndMessages(null)
        surface = null
        try {
            tileProvider.detach()
        } catch (e: Exception) {
            Log.w(TAG, "tile provider would not let go", e)
        }
    }

    fun setStableArea(area: Rect?) {
        if (area == null || area.isEmpty) return
        stableArea = Rect(area)
        scheduleDraw()
    }

    fun setStatus(text: String) {
        if (text == status) return
        status = text
        scheduleDraw()
    }

    /** A new fix. Recentres only while the driver has not taken the view somewhere themselves. */
    fun setPosition(next: Position?) {
        position = next
        if (next != null && following) aimAt(next.latitude, next.longitude)
        scheduleDraw()
    }

    /**
     * Somewhere to look before the first fix arrives.
     *
     * Without it the car opens on the middle of the Atlantic, because zero degrees by zero degrees
     * is where an uninitialised map points. Applied only once, and never over a real position.
     */
    fun aimAtFallback(latitude: Double, longitude: Double) {
        if (!neverAimed || position != null) return
        aimAt(latitude, longitude)
        scheduleDraw()
    }

    private fun aimAt(latitude: Double, longitude: Double) {
        centerLatitude = TileMath.clampLatitude(latitude)
        centerLongitude = TileMath.normalizeLongitude(longitude)
        neverAimed = false
    }

    fun panBy(dxPx: Float, dyPx: Float) {
        // Dragging the map moves the ground with the finger, so the view goes the other way.
        val panned = viewport()?.panBy(-dxPx.toDouble(), -dyPx.toDouble()) ?: return
        centerLatitude = panned.centerLatitude
        centerLongitude = panned.centerLongitude
        neverAimed = false
        following = false
        scheduleDraw()
    }

    fun zoomBy(levels: Int) {
        zoom = (zoom + levels).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)
        scheduleDraw()
    }

    /** Pinching reports a factor; anything past a half-step is worth a whole zoom level. */
    fun scaleBy(factor: Float) {
        val levels = when {
            factor > 1.4f -> 1
            factor < 0.72f -> -1
            else -> 0
        }
        if (levels != 0) zoomBy(levels)
    }

    fun recenter() {
        following = true
        position?.let { aimAt(it.latitude, it.longitude) }
        scheduleDraw()
    }

    fun scheduleDraw() {
        if (drawPending) return
        drawPending = true
        handler.postDelayed(
            {
                drawPending = false
                draw()
            },
            DRAW_COALESCE_MILLIS,
        )
    }

    private fun draw() {
        val target = surface ?: return
        val view = viewport() ?: return
        if (!target.isValid) return
        val canvas = try {
            target.lockCanvas(null)
        } catch (e: Exception) {
            // The host can take the surface away between the validity check and the lock.
            Log.w(TAG, "could not lock the car surface", e)
            return
        }
        try {
            render(canvas, view)
        } catch (e: Exception) {
            Log.w(TAG, "car frame failed", e)
        } finally {
            try {
                target.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "could not post the car frame", e)
            }
        }
    }

    private fun render(canvas: Canvas, view: Viewport) {
        canvas.drawColor(BACKGROUND_COLOR)
        drawTiles(canvas, view)
        drawFog(canvas, view)
        drawPosition(canvas, view)
        drawStatus(canvas, view)
    }

    private fun drawTiles(canvas: Canvas, view: Viewport) {
        val zoom = view.zoom
        val grid = TileMath.gridSize(zoom)
        val tiles = view.tileRange()
        val size = view.tileSizePx.toInt()
        for (y in tiles.yFrom..tiles.yTo) {
            if (y < 0 || y >= grid) continue
            for (x in tiles.xFrom..tiles.xTo) {
                val key = MapTileIndex.getTileIndex(zoom, TileMath.wrapX(x, zoom), y)
                // Null means "not yet"; asking is what queues the fetch, and the arrival handler
                // brings us back here.
                val tile = tileProvider.getMapTile(key) ?: continue
                val left = view.tileLeft(x).toInt()
                val top = view.tileTop(y).toInt()
                tile.setBounds(left, top, left + size, top + size)
                tile.draw(canvas)
            }
        }
    }

    private fun drawFog(canvas: Canvas, view: Viewport) {
        val renderZoom = min(RevealZoom.Z, view.zoom + LEVELS_BELOW_MAP).coerceIn(0, RevealZoom.Z)
        buildCellPath(fogPath, index, view, renderZoom)
        buildCellPath(airPath, airIndex, view, renderZoom)

        // Same trick as the phone: fill a layer with fog, then punch the explored cells out of it.
        val layer = canvas.saveLayer(null, null)
        canvas.drawColor(FOG_COLOR)
        canvas.drawPath(fogPath, clearPaint)
        canvas.restoreToCount(layer)
        canvas.drawPath(airPath, airPaint)
        canvas.drawPath(fogPath, edgePaint)
    }

    private fun buildCellPath(path: Path, source: ExploredIndex, view: Viewport, renderZoom: Int) {
        path.rewind()
        val range = view.cellRange(renderZoom)
        val cells = source.cellsIn(renderZoom, range.xFrom, range.xTo, range.yFrom, range.yTo)
        if (cells.isEmpty()) return
        val size = view.cellSizePx(renderZoom).toFloat()
        // Hides hairline seams between neighbours, scaled down so it is not a quarter of a
        // two-pixel cell.
        val overlap = min(SEAM_OVERLAP_PX, size * 0.15f)
        for (key in cells) {
            val left = view.cellLeft(CellKey.x(key), renderZoom).toFloat()
            val top = view.cellTop(CellKey.y(key), renderZoom).toFloat()
            path.addRect(left, top, left + size + overlap, top + size + overlap, Path.Direction.CW)
        }
    }

    private fun drawPosition(canvas: Canvas, view: Viewport) {
        val here = position ?: return
        val cx = view.xOf(here.longitude).toFloat()
        val cy = view.yOf(here.latitude).toFloat()
        canvas.drawCircle(cx, cy, POSITION_RADIUS_PX, positionPaint)
        canvas.drawCircle(cx, cy, POSITION_RADIUS_PX, positionRingPaint)
    }

    private fun drawStatus(canvas: Canvas, view: Viewport) {
        if (status.isEmpty()) return
        val height = stripTextPaint.textSize * 2.2f
        val area = stableArea
        val bottom = if (area != null) area.bottom.toFloat() else view.heightPx.toFloat()
        val left = if (area != null) area.left.toFloat() else 0f
        val right = if (area != null) area.right.toFloat() else view.widthPx.toFloat()
        val top = bottom - height
        canvas.drawRect(left, top, right, bottom, stripPaint)
        val baseline = top + height / 2f - (stripTextPaint.ascent() + stripTextPaint.descent()) / 2f
        canvas.drawText(status, left + stripTextPaint.textSize * 0.8f, baseline, stripTextPaint)
    }

    private companion object {
        const val TAG = "RoamedCar"

        /** Close enough to see the street you are on without being lost in it. */
        const val DEFAULT_ZOOM = 14

        /** 2^7 = 128, so a 256 px tile gives a ~2 px floor below which cells stop being visible. */
        const val LEVELS_BELOW_MAP = 7

        const val SEAM_OVERLAP_PX = 0.5f
        const val POSITION_RADIUS_PX = 14f

        /** A screenful of tiles can land in the same instant; repaint once for the lot. */
        const val DRAW_COALESCE_MILLIS = 60L

        val BACKGROUND_COLOR = Color.rgb(18, 22, 28)
        val FOG_COLOR = Color.argb(224, 8, 13, 22)
    }
}
