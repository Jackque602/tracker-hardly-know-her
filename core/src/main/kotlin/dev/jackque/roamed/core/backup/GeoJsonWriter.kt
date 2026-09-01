package dev.jackque.roamed.core.backup

import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.core.model.CellRecord

/**
 * Streams the uncovered cells out as a GeoJSON MultiPolygon, so the same fog can be dropped into
 * QGIS, geojson.io or anything else without re-implementing the tile maths.
 */
class GeoJsonSink(private val out: Appendable, private val zoom: Int = RevealZoom.Z) {

    private var firstCell = true

    fun begin() {
        out.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"Roamed explored area\",\"zoom\":")
            .append(zoom.toString())
            .append("},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[")
    }

    fun add(cell: CellRecord) {
        if (!firstCell) out.append(',')
        firstCell = false
        val west = TileMath.tileXToLon(cell.x.toDouble(), zoom)
        val east = TileMath.tileXToLon((cell.x + 1).toDouble(), zoom)
        val north = TileMath.tileYToLat(cell.y.toDouble(), zoom)
        val south = TileMath.tileYToLat((cell.y + 1).toDouble(), zoom)
        out.append("[[")
        appendPosition(west, north); out.append(',')
        appendPosition(east, north); out.append(',')
        appendPosition(east, south); out.append(',')
        appendPosition(west, south); out.append(',')
        appendPosition(west, north)
        out.append("]]")
    }

    fun end() {
        out.append("]}}")
    }

    private fun appendPosition(lon: Double, lat: Double) {
        out.append('[').append(format(lon)).append(',').append(format(lat)).append(']')
    }

    /** Six decimals is ~11 cm; more would just bloat the file. */
    private fun format(value: Double): String {
        val scaled = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        return scaled.toString()
    }
}

object GeoJsonWriter {
    fun write(out: Appendable, cells: Sequence<CellRecord>, zoom: Int = RevealZoom.Z) {
        val sink = GeoJsonSink(out, zoom)
        sink.begin()
        cells.forEach(sink::add)
        sink.end()
    }
}
