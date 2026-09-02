package dev.jackque.roamed.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a Google Maps Timeline export.
 *
 * Google has changed this file's shape several times - `locations` full of `latitudeE7` in the old
 * Takeout dumps, `timelineObjects` in Semantic Location History, `semanticSegments` with
 * `geo:` strings in the on-device export - and will change it again. So rather than binding to one
 * schema this walks the tree looking for anything shaped like a coordinate, and treats any array
 * holding two or more of them as a path. That reads all the known layouts and stands a fair chance
 * with the next one.
 */
object GoogleTimelineParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val LATITUDE_KEYS = listOf("latitudeE7", "latE7")
    private val LONGITUDE_KEYS = listOf("longitudeE7", "lngE7", "lonE7")
    private val PLAIN_LATITUDE_KEYS = listOf("latitude", "lat")
    private val PLAIN_LONGITUDE_KEYS = listOf("longitude", "lng", "lon")
    private val COORDINATE_STRING_KEYS = listOf("latLng", "latlng", "point", "location", "geo")
    private val TIME_KEYS = listOf("timestamp", "timestampMs", "time", "startTime", "startTimestamp")

    fun parse(text: String): List<ImportedTrack> {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ImportException("This does not look like a Timeline export (${e.message ?: "unreadable JSON"}).")
        }
        val tracks = mutableListOf<ImportedTrack>()
        walk(root, tracks)
        return tracks.filterNot { it.isEmpty }
    }

    private fun walk(element: JsonElement, tracks: MutableList<ImportedTrack>) {
        when (element) {
            is JsonArray -> {
                val points = element.mapNotNull { pointOf(it) }
                if (points.size >= 2) {
                    tracks += ImportedTrack(ordered(points))
                    // Children that were points are accounted for; anything else may still nest a path.
                    element.forEach { child -> if (pointOf(child) == null) walk(child, tracks) }
                } else {
                    element.forEach { walk(it, tracks) }
                }
            }

            is JsonObject -> {
                val startEnd = startEndPair(element)
                val here = pointOf(element)
                when {
                    // An activity records only where it began and ended; that is still a journey.
                    startEnd != null -> tracks += ImportedTrack(startEnd)
                    // A visit is a single place, which uncovers where you stood.
                    here != null -> tracks += ImportedTrack(listOf(here))
                    else -> element.values.forEach { walk(it, tracks) }
                }
            }

            else -> Unit
        }
    }

    /** Points sort by time where every one of them has a time; otherwise file order is all there is. */
    private fun ordered(points: List<ImportedFix>): List<ImportedFix> =
        if (points.all { it.timestamp != null }) points.sortedBy { it.timestamp } else points

    private fun startEndPair(obj: JsonObject): List<ImportedFix>? {
        val start = obj["start"]?.let { pointOf(it) } ?: return null
        val end = obj["end"]?.let { pointOf(it) } ?: return null
        return listOf(start, end)
    }

    private fun pointOf(element: JsonElement): ImportedFix? {
        if (element is JsonPrimitive && element.isString) {
            return Coordinates.fromString(element.content, null)
        }
        val obj = element as? JsonObject ?: return null
        val time = Coordinates.timestamp(TIME_KEYS.firstNotNullOfOrNull { obj[it]?.stringOrNull() })

        val latE7 = LATITUDE_KEYS.firstNotNullOfOrNull { obj[it]?.longOrNull() }
        val lonE7 = LONGITUDE_KEYS.firstNotNullOfOrNull { obj[it]?.longOrNull() }
        if (latE7 != null && lonE7 != null) return Coordinates.fromE7(latE7, lonE7, time)

        val lat = PLAIN_LATITUDE_KEYS.firstNotNullOfOrNull { obj[it]?.doubleOrNull() }
        val lon = PLAIN_LONGITUDE_KEYS.firstNotNullOfOrNull { obj[it]?.doubleOrNull() }
        if (lat != null && lon != null) return Coordinates.fix(lat, lon, time)

        // A nested coordinate string, which is how the on-device export writes every position.
        COORDINATE_STRING_KEYS.firstNotNullOfOrNull { key ->
            obj[key]?.let { nested ->
                (nested as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { Coordinates.fromString(it, time) }
                    ?: (nested as? JsonObject)?.let { pointOf(it) }
            }
        }?.let { return it }

        return null
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.content

    private fun JsonElement.longOrNull(): Long? =
        (this as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonElement.doubleOrNull(): Double? =
        (this as? JsonPrimitive)?.content?.toDoubleOrNull()
}

class ImportException(message: String) : Exception(message)
