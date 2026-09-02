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
data class ImportedTrack(
    val points: List<ImportedFix>,
    /**
     * True when the source states these points form one continuous path, as a GPX track segment
     * or route does. Recorded history carries no such promise - consecutive entries there can be
     * hours and continents apart - so points from those sources are only joined when they are near
     * enough in time and space to have plausibly been travelled straight through.
     *
     * It matters most for a route exported turn-by-turn: two waypoints either end of a long
     * motorway stretch can be fifty kilometres apart and still be one unbroken drive.
     */
    val contiguous: Boolean = false,
) {
    val isEmpty: Boolean get() = points.isEmpty()
}
