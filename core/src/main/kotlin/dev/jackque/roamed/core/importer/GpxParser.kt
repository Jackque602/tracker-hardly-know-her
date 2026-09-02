package dev.jackque.roamed.core.importer

/**
 * Reads GPX, which is what every other tracker in the world exports.
 *
 * A regex rather than a full XML parser: the shape being looked for is exactly one attribute pair
 * on one element name, GPX files run to hundreds of thousands of points, and this avoids pulling a
 * parser into a module that otherwise has no dependencies.
 */
object GpxParser {

    private val SEGMENT = Regex("""<trkseg[^>]*>(.*?)</trkseg>""", RegexOption.DOT_MATCHES_ALL)
    private val TRACK_POINT = Regex(
        """<(?:trkpt|rtept|wpt)[^>]*?\blat\s*=\s*"(-?\d+(?:\.\d+)?)"[^>]*?\blon\s*=\s*"(-?\d+(?:\.\d+)?)"[^>]*?(?:/>|>(.*?)</(?:trkpt|rtept|wpt)>)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val TIME = Regex("""<time>\s*([^<]+?)\s*</time>""")

    fun parse(text: String): List<ImportedTrack> {
        if (!text.contains("<gpx", ignoreCase = true)) {
            throw ImportException("This does not look like a GPX file.")
        }
        val segments = SEGMENT.findAll(text).map { it.groupValues[1] }.toList()
        // A file with no <trkseg> can still hold loose waypoints; treat the whole thing as one run.
        val bodies = segments.ifEmpty { listOf(text) }
        return bodies
            .map { body -> ImportedTrack(pointsIn(body)) }
            .filterNot { it.isEmpty }
    }

    private fun pointsIn(body: String): List<ImportedFix> =
        TRACK_POINT.findAll(body).mapNotNull { match ->
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val inner = match.groupValues.getOrNull(3).orEmpty()
            Coordinates.fix(latitude, longitude, Coordinates.timestamp(TIME.find(inner)?.groupValues?.get(1)))
        }.toList()
}

/** Picks the reader by looking at the file rather than trusting its name. */
object TrackImport {
    fun parse(text: String): List<ImportedTrack> {
        val head = text.trimStart().take(200)
        return when {
            head.startsWith("{") || head.startsWith("[") -> GoogleTimelineParser.parse(text)
            head.contains("<") -> GpxParser.parse(text)
            else -> throw ImportException("Unrecognised file: expected a Timeline export or a GPX file.")
        }
    }
}
