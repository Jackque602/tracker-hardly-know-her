package dev.jackque.roamed.core.importer

/** One position from an imported file. Timestamps are optional; plenty of exports omit them. */
data class ImportedFix(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long?,
)

/**
 * An ordered run of positions that belong together.
 *
 * Tracks matter because only points *within* one may be joined up. Two consecutive entries in a
 * year of location history can be a continent apart; drawing a line between them would invent a
 * journey that never happened.
 */
data class ImportedTrack(val points: List<ImportedFix>) {
    val isEmpty: Boolean get() = points.isEmpty()
}
