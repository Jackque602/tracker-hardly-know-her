package dev.jackque.roamed.core.regions

/** What kind of thing a [Region] is. The three form a strict hierarchy. */
enum class RegionKind { CONTINENT, COUNTRY, SUBDIVISION }

/**
 * One named piece of the world.
 *
 * [areaSquareMeters] is the true geodesic area of the region's boundary, not the area of the
 * squares the mask happens to give it. That distinction matters: the mask is several kilometres
 * across, so measuring a small region against its own squares would call it fully explored the
 * moment you clipped a corner of it.
 */
data class Region(
    val id: Int,
    val kind: RegionKind,
    /** The country a subdivision belongs to, the continent a country belongs to, else [NONE]. */
    val parentId: Int,
    /** ISO code where one exists (`US-PA`, `FR`), otherwise the region's own name. */
    val code: String,
    val name: String,
    val areaSquareMeters: Double,
) {
    companion object {
        const val NONE = -1
    }
}
