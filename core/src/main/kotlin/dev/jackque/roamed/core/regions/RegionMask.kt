package dev.jackque.roamed.core.regions

import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Answers "which country and which state is this square in?" without carrying a single polygon.
 *
 * The world's boundaries are pre-drawn onto the same Web Mercator grid the fog uses, at
 * [MASK_ZOOM], and stored run-length encoded one row at a time. A lookup is therefore a shift to
 * get from a fog square to a mask square and a binary search along one row - fast enough to run
 * over every explored square each time the stats screen opens, and small enough to ship.
 *
 * The price is resolution: a mask square is about 10 km across at the equator, so near a border a
 * place can be attributed to the wrong side of it. Nothing about the fog itself is affected, only
 * which region its area is counted under.
 *
 * Built by `tools/build_region_mask.py` from Natural Earth data (public domain).
 */
class RegionMask internal constructor(
    val zoom: Int,
    private val regionsById: List<Region>,
    /** `rowOffset[y] until rowOffset[y + 1]` are the runs of row `y`; equal means an empty row. */
    private val rowOffset: IntArray,
    private val runStart: ShortArray,
    private val runLength: ShortArray,
    private val runRegion: ShortArray,
) {

    val regions: List<Region> get() = regionsById

    val gridSize: Int = TileMath.gridSize(zoom)

    fun region(id: Int): Region? = regionsById.getOrNull(id)

    fun regionsOf(kind: RegionKind): List<Region> = regionsById.filter { it.kind == kind }

    /** The region owning a mask square, or [Region.NONE] for open sea and unmapped ground. */
    fun regionAt(maskX: Int, maskY: Int): Int {
        if (maskY < 0 || maskY >= gridSize) return Region.NONE
        val column = TileMath.wrapX(maskX, zoom)
        var low = rowOffset[maskY]
        var high = rowOffset[maskY + 1] - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = runStart[mid].toInt() and 0xFFFF
            when {
                column < start -> high = mid - 1
                column >= start + (runLength[mid].toInt() and 0xFFFF) -> low = mid + 1
                else -> return runRegion[mid].toInt() and 0xFFFF
            }
        }
        return Region.NONE
    }

    /** The region owning a fog cell. [cellZoom] must be at least as fine as the mask's own. */
    fun regionOfCell(key: Long, cellZoom: Int = RevealZoom.Z): Int {
        require(cellZoom >= zoom) { "cells are coarser than the mask: $cellZoom < $zoom" }
        val shift = cellZoom - zoom
        return regionAt(CellKey.x(key) shr shift, CellKey.y(key) shr shift)
    }

    fun regionAt(latitude: Double, longitude: Double): Int =
        regionAt(TileMath.cellX(longitude, zoom), TileMath.cellY(latitude, zoom))

    /** Walks a region and then its parents, nearest first. */
    fun lineage(id: Int): List<Region> {
        val chain = ArrayList<Region>(3)
        var current = id
        while (current != Region.NONE) {
            val region = regionsById.getOrNull(current) ?: break
            chain.add(region)
            current = region.parentId
        }
        return chain
    }

    companion object {
        /**
         * The zoom the world's boundaries are drawn at.
         *
         * z12 squares are about 10 km across at the equator and 6 km at 50 degrees. Finer would
         * place borders more precisely, but the file doubles with every level and the countries
         * and states this is used to count are all far larger than that.
         */
        const val MASK_ZOOM = 12

        private const val RESOURCE = "/regions.bin"
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte(), 'G'.code.toByte())
        private const val FORMAT_VERSION = 1

        /** Loads the mask shipped inside the library. */
        fun bundled(): RegionMask {
            val stream = RegionMask::class.java.getResourceAsStream(RESOURCE)
                ?: throw IOException("region mask $RESOURCE is missing from the build")
            return stream.use { read(it) }
        }

        fun read(input: InputStream): RegionMask {
            val data = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(4)
            data.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("not a region mask")
            val version = data.readUnsignedByte()
            if (version != FORMAT_VERSION) throw IOException("unsupported region mask version $version")
            val zoom = data.readUnsignedByte()
            val gridSize = TileMath.gridSize(zoom)

            val regionCount = data.readUnsignedShort()
            val regions = ArrayList<Region>(regionCount)
            val kinds = RegionKind.entries
            for (id in 0 until regionCount) {
                val kind = data.readUnsignedByte()
                if (kind !in kinds.indices) throw IOException("unknown region kind $kind")
                val parent = data.readShort().toInt()
                val area = data.readDouble()
                val code = readText(data)
                val name = readText(data)
                regions.add(Region(id, kinds[kind], parent, code, name, area))
            }

            val rowCount = data.readInt()
            val rowOffset = IntArray(gridSize + 1)
            // Rows are written in order and only where they hold something, so the offsets of the
            // empty rows in between are filled in as each written row arrives.
            val starts = ShortArrayBuilder()
            val lengths = ShortArrayBuilder()
            val values = ShortArrayBuilder()
            var nextRow = 0
            repeat(rowCount) {
                val y = data.readInt()
                if (y < nextRow || y >= gridSize) throw IOException("region mask rows are out of order at $y")
                for (empty in nextRow..y) rowOffset[empty] = starts.size
                val runs = data.readUnsignedShort()
                repeat(runs) {
                    starts.add(data.readShort())
                    lengths.add(data.readShort())
                    values.add(data.readShort())
                }
                nextRow = y + 1
            }
            for (trailing in nextRow..gridSize) rowOffset[trailing] = starts.size

            return RegionMask(zoom, regions, rowOffset, starts.toArray(), lengths.toArray(), values.toArray())
        }

        private fun readText(data: DataInputStream): String {
            val bytes = ByteArray(data.readUnsignedByte())
            data.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/** A grow-as-you-go ShortArray; the run count is not known until the last row has been read. */
private class ShortArrayBuilder {
    private var items = ShortArray(1 shl 14)
    var size: Int = 0
        private set

    fun add(value: Short) {
        if (size == items.size) items = items.copyOf(items.size * 2)
        items[size++] = value
    }

    fun toArray(): ShortArray = items.copyOf(size)
}
