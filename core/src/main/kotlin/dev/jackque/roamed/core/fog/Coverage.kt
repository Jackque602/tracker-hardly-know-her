package dev.jackque.roamed.core.fog

import kotlin.math.pow

/**
 * How thoroughly a drawn square has actually been explored.
 *
 * Zoomed out, one square on screen stands for thousands of stored cells. Painting it fully clear
 * because a single cell inside it was visited overstates coverage badly - at world view one street
 * would claim a block a thousand kilometres across. Instead each square is erased in proportion to
 * how much of it is genuinely uncovered, so a passed-through region reads as a smudge and a
 * lived-in one reads as solid.
 */
object Coverage {

    /**
     * Fine cells contained in one coarse cell.
     *
     * Long rather than Int: collapsing z17 to z0 is 4^17, which overflows a 32-bit count.
     */
    fun childrenPerCell(fromZoom: Int, toZoom: Int): Long {
        require(toZoom <= fromZoom) { "toZoom must be coarser than or equal to fromZoom" }
        return 1L shl (2 * (fromZoom - toZoom))
    }

    /** Fraction of a coarse cell that is uncovered, in 0..1. */
    fun fraction(childCount: Int, fromZoom: Int, toZoom: Int): Double {
        if (childCount <= 0) return 0.0
        val total = childrenPerCell(fromZoom, toZoom)
        return (childCount.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * How hard to erase a square holding this fraction of explored ground.
     *
     * Two deliberate departures from a straight linear mapping:
     *
     *  - a [floor], because coverage fractions at world zoom are absurd (a thoroughly explored city
     *    is about 0.0001 of a z5 square). Scaling linearly would make a lifetime of travel
     *    invisible the moment you zoom out, which is worse than overstating it. Anywhere you have
     *    been stays legible.
     *  - a [gamma] curve, because the interesting range is the low end. Without it almost
     *    everything below "half explored" would look identical.
     *
     * At the storage zoom every square holds exactly one cell, so this returns 1.0 and the fog is
     * punched fully clear - the detailed view is untouched by any of this.
     */
    fun alpha(
        fraction: Double,
        floor: Double = DEFAULT_FLOOR,
        gamma: Double = DEFAULT_GAMMA,
    ): Double {
        require(floor in 0.0..1.0) { "floor must be a fraction" }
        require(gamma > 0.0) { "gamma must be positive" }
        val clamped = fraction.coerceIn(0.0, 1.0)
        if (clamped <= 0.0) return 0.0
        if (clamped >= 1.0) return 1.0
        return (floor + (1.0 - floor) * clamped.pow(1.0 / gamma)).coerceIn(0.0, 1.0)
    }

    /** Faintest a visited square is ever drawn, so travel never disappears entirely. */
    const val DEFAULT_FLOOR = 0.35

    /** Above 1 this expands the low end of the range, where real coverage fractions live. */
    const val DEFAULT_GAMMA = 2.2
}
