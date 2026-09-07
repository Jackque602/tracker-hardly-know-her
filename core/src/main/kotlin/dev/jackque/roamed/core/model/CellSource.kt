package dev.jackque.roamed.core.model

/**
 * How the ground under a cell came to be uncovered.
 *
 * Flying over somewhere is not the same as having been there, and a map that cannot tell the two
 * apart is worth less than one that can: a single transatlantic flight uncovers well over a
 * thousand square kilometres, which would otherwise swamp a lifetime of walking.
 */
enum class CellSource(val id: Int) {
    /** Travelled through at surface level - walked, driven, cycled, sailed. */
    GROUND(0),

    /** Flown over. Uncovered, but seen from ten kilometres up. */
    AIR(1),
    ;

    companion object {
        /** Anything unrecognised is ground, so a file from a newer build still reads sensibly. */
        fun of(id: Int): CellSource = entries.firstOrNull { it.id == id } ?: GROUND
    }
}
