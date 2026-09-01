package dev.jackque.roamed.core.backup

import dev.jackque.roamed.core.model.TrackPointRecord
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Streams recorded fixes out as GPX 1.1, readable by every mapping tool worth the name. */
class GpxSink(private val out: Appendable) {

    fun begin(trackName: String) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"Roamed\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.append("  <trk>\n    <name>").append(escapeXml(trackName)).append("</name>\n    <trkseg>\n")
    }

    fun add(point: TrackPointRecord) {
        out.append("      <trkpt lat=\"").append(point.latitude.toString())
            .append("\" lon=\"").append(point.longitude.toString()).append("\">")
        point.altitude?.let { out.append("<ele>").append(it.toString()).append("</ele>") }
        out.append("<time>").append(formatTime(point.timestamp)).append("</time>")
        out.append("</trkpt>\n")
    }

    fun end() {
        out.append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun formatTime(epochMillis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

object GpxWriter {
    fun write(out: Appendable, trackName: String, points: Sequence<TrackPointRecord>) {
        val sink = GpxSink(out)
        sink.begin(trackName)
        points.forEach(sink::add)
        sink.end()
    }
}
