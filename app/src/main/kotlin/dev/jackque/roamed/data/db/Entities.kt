package dev.jackque.roamed.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One uncovered square of the world.
 *
 * The primary key is the cell's position in the fixed grid, so re-visiting somewhere can never
 * create a duplicate row no matter how many fixes land inside it.
 */
@Entity(tableName = "explored_cell", primaryKeys = ["x", "y"], indices = [Index("firstSeen")])
data class ExploredCellEntity(
    val x: Int,
    val y: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    /** How many separate times a fix landed inside this cell (not how many fixes). */
    val visits: Int,
)

/** A raw GPS fix. Kept for the trail overlay and GPX export; prunable without losing the fog. */
@Entity(tableName = "track_point", indices = [Index("timestamp")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
)

/**
 * Per-day totals.
 *
 * These are the durable record: raw fixes get pruned, but distance travelled and places
 * discovered are rolled up here first, so the lifetime numbers never quietly shrink.
 */
@Entity(tableName = "daily_stat")
data class DailyStatEntity(
    /** Local date, ISO-8601 (`2026-09-01`). */
    @PrimaryKey val date: String,
    val distanceMeters: Double,
    val newCells: Int,
)

/** A country or region that has been entered at least once. */
@Entity(tableName = "visited_place", primaryKeys = ["countryCode", "adminArea"])
data class VisitedPlaceEntity(
    val countryCode: String,
    val countryName: String,
    val adminArea: String,
    val firstSeen: Long,
    val lastSeen: Long,
)
