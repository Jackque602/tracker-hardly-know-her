package dev.jackque.roamed.core

import dev.jackque.roamed.core.backup.BackupException
import dev.jackque.roamed.core.backup.BackupReader
import dev.jackque.roamed.core.backup.BackupSink
import dev.jackque.roamed.core.backup.GeoJsonSink
import dev.jackque.roamed.core.backup.GpxSink
import dev.jackque.roamed.core.backup.BackupWriter
import dev.jackque.roamed.core.backup.GeoJsonWriter
import dev.jackque.roamed.core.backup.GpxWriter
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.model.CellRecord
import dev.jackque.roamed.core.model.TrackPointRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupTest {

    private val cells = listOf(
        CellRecord(70_000, 45_000, 1_700_000_000_000, 1_700_000_600_000, 3),
        CellRecord(70_001, 45_000, 1_700_000_100_000, 1_700_000_100_000, 1),
        CellRecord(0, 0, 1L, 2L, 7),
    )

    @Test
    fun `a backup round trips without losing a cell`() {
        val out = StringBuilder()
        BackupWriter.write(out, cells.asSequence(), exportedAt = 42L, appVersion = "1.0", cellCount = cells.size)
        val restored = BackupReader.read(out.toString())
        assertEquals(cells, restored)
    }

    @Test
    fun `an empty backup round trips`() {
        val out = StringBuilder()
        BackupWriter.write(out, emptySequence(), exportedAt = 0L, appVersion = "1.0", cellCount = 0)
        assertEquals(emptyList(), BackupReader.read(out.toString()))
    }

    @Test
    fun `the header records the grid zoom the cells were written at`() {
        val out = StringBuilder()
        BackupWriter.write(out, cells.asSequence(), 42L, "1.2.3", cells.size)
        val text = out.toString()
        assertTrue(text.contains("\"revealZoom\":${RevealZoom.Z}"))
        assertTrue(text.contains("\"cellCount\":3"))
        assertTrue(text.contains("\"appVersion\":\"1.2.3\""))
    }

    @Test
    fun `a backup from a different grid zoom is refused`() {
        val text = """{"format":"roamed-backup","version":1,"revealZoom":12,"cells":[[1,2,3,4,5]]}"""
        val error = assertFailsWith<BackupException> { BackupReader.read(text) }
        assertTrue(error.message!!.contains("grid zoom 12"))
    }

    @Test
    fun `a backup from a newer app version is refused`() {
        val text = """{"format":"roamed-backup","version":99,"revealZoom":${RevealZoom.Z},"cells":[]}"""
        assertFailsWith<BackupException> { BackupReader.read(text) }
    }

    @Test
    fun `an unrelated file is refused`() {
        assertFailsWith<BackupException> { BackupReader.read("""{"hello":"world"}""") }
        assertFailsWith<BackupException> { BackupReader.read("not json at all") }
    }

    @Test
    fun `short cell rows fall back to sensible defaults`() {
        val text = """{"format":"roamed-backup","version":1,"revealZoom":${RevealZoom.Z},"cells":[[5,6]]}"""
        val restored = BackupReader.read(text)
        assertEquals(1, restored.size)
        assertEquals(CellRecord(5, 6, 0L, 0L, 1), restored.first())
    }

    @Test
    fun `app version strings are escaped`() {
        val out = StringBuilder()
        BackupWriter.write(out, emptySequence(), 0L, "quote\" and \\ backslash", 0)
        val restored = BackupReader.read(out.toString())
        assertEquals(emptyList(), restored)
    }

    @Test
    fun `gpx export is well formed`() {
        val out = StringBuilder()
        GpxWriter.write(
            out,
            "Roamed track",
            sequenceOf(
                TrackPointRecord(1_700_000_000_000, 51.5, -0.12, 35.0, 8f, 1.2f),
                TrackPointRecord(1_700_000_020_000, 51.5001, -0.1201, null, 6f, 1.4f),
            ),
        )
        val gpx = out.toString()
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<trkpt lat=\"51.5\" lon=\"-0.12\">"))
        assertTrue(gpx.contains("<ele>35.0</ele>"))
        assertTrue(gpx.contains("2023-11-14T22:13:20Z"))
        assertEquals(2, Regex("<trkpt").findAll(gpx).count())
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun `geojson export closes every ring`() {
        val out = StringBuilder()
        GeoJsonWriter.write(out, cells.asSequence())
        val geojson = out.toString()
        assertTrue(geojson.startsWith("{\"type\":\"Feature\""))
        assertTrue(geojson.contains("\"MultiPolygon\""))
        assertEquals(geojson.count { it == '[' }, geojson.count { it == ']' })
        assertEquals(geojson.count { it == '{' }, geojson.count { it == '}' })
    }

    @Test
    fun `writing in pages matches writing in one go`() {
        val streamed = StringBuilder()
        val sink = BackupSink(streamed)
        sink.begin(exportedAt = 42L, appVersion = "1.0", cellCount = cells.size)
        // Deliberately fed in two batches, the way a paged database read would.
        cells.take(2).forEach(sink::add)
        cells.drop(2).forEach(sink::add)
        sink.end()

        val whole = StringBuilder()
        BackupWriter.write(whole, cells.asSequence(), 42L, "1.0", cells.size)
        assertEquals(whole.toString(), streamed.toString())
        assertEquals(cells, BackupReader.read(streamed.toString()))
    }

    @Test
    fun `a sink refuses to be used out of order`() {
        val sink = BackupSink(StringBuilder())
        assertFailsWith<IllegalStateException> { sink.add(cells.first()) }
        sink.begin(0L, "1.0", 0)
        assertFailsWith<IllegalStateException> { sink.begin(0L, "1.0", 0) }
    }

    @Test
    fun `paged gpx and geojson sinks produce the same bytes as the one-shot writers`() {
        val points = listOf(
            TrackPointRecord(1_700_000_000_000, 51.5, -0.12, 35.0, 8f, 1.2f),
            TrackPointRecord(1_700_000_020_000, 51.5001, -0.1201, null, 6f, 1.4f),
        )
        val streamedGpx = StringBuilder()
        GpxSink(streamedGpx).run { begin("Roamed track"); points.forEach(::add); end() }
        val wholeGpx = StringBuilder()
        GpxWriter.write(wholeGpx, "Roamed track", points.asSequence())
        assertEquals(wholeGpx.toString(), streamedGpx.toString())

        val streamedJson = StringBuilder()
        GeoJsonSink(streamedJson).run { begin(); cells.forEach(::add); end() }
        val wholeJson = StringBuilder()
        GeoJsonWriter.write(wholeJson, cells.asSequence())
        assertEquals(wholeJson.toString(), streamedJson.toString())
    }
}
