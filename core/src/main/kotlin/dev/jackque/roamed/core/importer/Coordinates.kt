package dev.jackque.roamed.core.importer

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Shared coordinate and timestamp parsing for the import formats. */
internal object Coordinates {

    private val GEO_URI = Regex("""geo:\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
    private val DEGREE_PAIR = Regex("""(-?\d+(?:\.\d+)?)\s*°?\s*,\s*(-?\d+(?:\.\d+)?)\s*°?""")

    /**
     * Builds a fix, rejecting anything off the globe.
     *
     * Exactly (0, 0) is refused too: it is in the Atlantic where nobody has been, and it is what
     * every one of these formats leaves behind when a coordinate is missing.
     */
    fun fix(latitude: Double, longitude: Double, timestamp: Long?): ImportedFix? {
        if (latitude.isNaN() || longitude.isNaN()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return ImportedFix(latitude, longitude, timestamp)
    }

    /** Google stores degrees as integers scaled by ten million. */
    fun fromE7(latitudeE7: Long, longitudeE7: Long, timestamp: Long?): ImportedFix? =
        fix(latitudeE7 / 1e7, longitudeE7 / 1e7, timestamp)

    /** Handles both `geo:40.1,-75.2` and `40.1°, -75.2°`, the two shapes Timeline uses. */
    fun fromString(value: String, timestamp: Long?): ImportedFix? {
        GEO_URI.find(value)?.let { m ->
            return fix(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), timestamp)
        }
        DEGREE_PAIR.find(value)?.let { m ->
            return fix(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), timestamp)
        }
        return null
    }

    /**
     * Rescales a bare epoch to milliseconds.
     *
     * Exports disagree about the unit - Life360 and plenty of others count in seconds, Google in
     * milliseconds - and taking seconds at face value dates the whole trip to 1970, which silently
     * lands every imported cell outside the years the stats screen knows about. The magnitudes are
     * far enough apart to tell without ambiguity: a present-day epoch is ~1.8e9 in seconds, ~1.8e12
     * in milliseconds and ~1.8e15 in microseconds.
     */
    private fun normaliseEpoch(raw: Long): Long = when {
        kotlin.math.abs(raw) < 100_000_000_000L -> raw * 1_000
        kotlin.math.abs(raw) > 100_000_000_000_000L -> raw / 1_000
        else -> raw
    }

    /** ISO-8601 in any of its offsets, or a bare epoch in seconds, milliseconds or microseconds. */
    fun timestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.toLongOrNull()?.let { return normaliseEpoch(it) }
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(value).toEpochMilli()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }
}
