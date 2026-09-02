package dev.jackque.roamed.core

import dev.jackque.roamed.core.importer.GpxParser
import dev.jackque.roamed.core.importer.GoogleTimelineParser
import dev.jackque.roamed.core.importer.ImportException
import dev.jackque.roamed.core.importer.TrackImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackImportTest {

    private fun allPoints(text: String) = TrackImport.parse(text).flatMap { it.points }

    @Test
    fun `reads the on-device export with geo strings`() {
        // The shape Google Maps writes today: segments, each holding an ordered path.
        val text = """
            {"semanticSegments":[
              {"startTime":"2026-09-01T13:00:00.000-04:00",
               "endTime":"2026-09-01T14:00:00.000-04:00",
               "timelinePath":[
                 {"point":"geo:39.7391,-75.5398","time":"2026-09-01T13:00:00.000-04:00"},
                 {"point":"geo:39.8000,-75.7000","time":"2026-09-01T13:20:00.000-04:00"},
                 {"point":"geo:40.0379,-76.3055","time":"2026-09-01T13:50:00.000-04:00"}
               ]}
            ]}
        """.trimIndent()
        val tracks = TrackImport.parse(text)
        assertEquals(1, tracks.size, "one segment should give one path")
        assertEquals(3, tracks[0].points.size)
        assertEquals(39.7391, tracks[0].points[0].latitude, 1e-9)
        assertEquals(-75.5398, tracks[0].points[0].longitude, 1e-9)
        assertTrue(tracks[0].points.all { it.timestamp != null }, "times should be carried across")
    }

    @Test
    fun `reads the old takeout records with E7 integers`() {
        val text = """
            {"locations":[
              {"latitudeE7":397391000,"longitudeE7":-755398000,"timestampMs":"1788267600000","accuracy":12},
              {"latitudeE7":400379000,"longitudeE7":-763055000,"timestampMs":"1788271200000","accuracy":18}
            ]}
        """.trimIndent()
        val points = allPoints(text)
        assertEquals(2, points.size)
        assertEquals(39.7391, points[0].latitude, 1e-6)
        assertEquals(-75.5398, points[0].longitude, 1e-6)
        assertEquals(1788267600000L, points[0].timestamp)
    }

    @Test
    fun `reads semantic history with degree-string lat lngs`() {
        val text = """
            {"timelineObjects":[
              {"activitySegment":{
                 "startLocation":{"latitudeE7":397391000,"longitudeE7":-755398000},
                 "endLocation":{"latitudeE7":400379000,"longitudeE7":-763055000}}},
              {"placeVisit":{"location":{"latLng":"40.2732°, -76.8867°"}}}
            ]}
        """.trimIndent()
        val points = allPoints(text)
        assertTrue(points.size >= 3, "two endpoints and a visit, got ${points.size}")
        assertTrue(points.any { kotlin.math.abs(it.latitude - 40.2732) < 1e-4 }, "the visit should be read")
    }

    @Test
    fun `an activity start and end become one journey`() {
        val text = """
            {"semanticSegments":[
              {"activity":{
                "start":{"latLng":"39.7391°, -75.5398°"},
                "end":{"latLng":"40.2732°, -76.8867°"},
                "distanceMeters":"120000"}}
            ]}
        """.trimIndent()
        val tracks = TrackImport.parse(text)
        assertEquals(1, tracks.size)
        assertEquals(2, tracks[0].points.size, "start and end belong to the same track")
    }

    @Test
    fun `separate segments stay separate so no line is drawn between them`() {
        // Two paths a continent apart. Joining them would invent a drive across the country.
        val text = """
            {"semanticSegments":[
              {"timelinePath":[{"point":"geo:39.73,-75.53"},{"point":"geo:39.74,-75.54"}]},
              {"timelinePath":[{"point":"geo:37.77,-122.41"},{"point":"geo:37.78,-122.42"}]}
            ]}
        """.trimIndent()
        val tracks = TrackImport.parse(text)
        assertEquals(2, tracks.size, "each path must stay its own track")
    }

    @Test
    fun `points are put back in time order`() {
        val text = """
            {"locations":[
              {"latitudeE7":400000000,"longitudeE7":-760000000,"timestamp":"2026-09-01T14:00:00Z"},
              {"latitudeE7":399000000,"longitudeE7":-759000000,"timestamp":"2026-09-01T13:00:00Z"}
            ]}
        """.trimIndent()
        val points = allPoints(text)
        assertEquals(2, points.size)
        assertTrue(points[0].timestamp!! < points[1].timestamp!!, "out-of-order entries should be sorted")
    }

    @Test
    fun `missing and impossible coordinates are dropped`() {
        val text = """
            {"locations":[
              {"latitudeE7":0,"longitudeE7":0},
              {"latitudeE7":1000000000,"longitudeE7":-760000000},
              {"latitudeE7":397391000,"longitudeE7":-755398000}
            ]}
        """.trimIndent()
        val points = allPoints(text)
        assertEquals(1, points.size, "null island and out-of-range points must not be imported")
        assertEquals(39.7391, points[0].latitude, 1e-6)
    }

    @Test
    fun `reads gpx with one track per segment`() {
        val text = """
            <?xml version="1.0"?>
            <gpx version="1.1"><trk><name>Drive</name>
              <trkseg>
                <trkpt lat="39.7391" lon="-75.5398"><ele>30</ele><time>2026-09-01T17:00:00Z</time></trkpt>
                <trkpt lat="40.0379" lon="-76.3055"><time>2026-09-01T17:50:00Z</time></trkpt>
              </trkseg>
              <trkseg>
                <trkpt lat="40.2732" lon="-76.8867"/>
              </trkseg>
            </trk></gpx>
        """.trimIndent()
        val tracks = TrackImport.parse(text)
        assertEquals(2, tracks.size)
        assertEquals(2, tracks[0].points.size)
        assertEquals(1, tracks[1].points.size, "a self-closing trkpt should still be read")
        assertEquals(1788282000000L, tracks[0].points[0].timestamp)
    }

    @Test
    fun `a gpx round trip through our own exporter reads back`() {
        val exported = StringBuilder()
        dev.jackque.roamed.core.backup.GpxWriter.write(
            exported,
            "Roamed track",
            sequenceOf(
                dev.jackque.roamed.core.model.TrackPointRecord(1_788_282_000_000, 39.7391, -75.5398, 30.0, 8f, 1f),
                dev.jackque.roamed.core.model.TrackPointRecord(1_788_285_000_000, 40.0379, -76.3055, null, 6f, 1f),
            ),
        )
        val points = allPoints(exported.toString())
        assertEquals(2, points.size)
        assertEquals(39.7391, points[0].latitude, 1e-9)
        assertEquals(1_788_282_000_000L, points[0].timestamp)
    }

    @Test
    fun `unreadable files are refused clearly`() {
        assertFailsWith<ImportException> { TrackImport.parse("this is not a track") }
        assertFailsWith<ImportException> { GoogleTimelineParser.parse("{ broken") }
        assertFailsWith<ImportException> { GpxParser.parse("<html><body>nope</body></html>") }
    }

    @Test
    fun `a timeline export with nothing locatable yields no tracks`() {
        assertEquals(emptyList(), TrackImport.parse("""{"semanticSegments":[{"startTime":"2026-09-01T13:00:00Z"}]}"""))
    }
}
