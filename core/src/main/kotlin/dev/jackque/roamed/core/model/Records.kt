package dev.jackque.roamed.core.model

/** One uncovered cell, as it travels between the database, backups and the map. */
data class CellRecord(
    val x: Int,
    val y: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val visits: Int,
    val source: CellSource = CellSource.GROUND,
)

/** One recorded GPS fix. */
data class TrackPointRecord(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
)
