package dev.jackque.roamed.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ExploredCellDao {

    @Query("SELECT * FROM explored_cell")
    abstract suspend fun loadAll(): List<ExploredCellEntity>

    @Query("SELECT COUNT(*) FROM explored_cell")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM explored_cell")
    abstract suspend fun count(): Int

    /** New cells only; an existing cell keeps its original firstSeen. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertNew(cells: List<ExploredCellEntity>)

    @Query("UPDATE explored_cell SET lastSeen = :now, visits = visits + 1 WHERE x = :x AND y = :y")
    abstract suspend fun markVisited(x: Int, y: Int, now: Long)

    @Query("SELECT MIN(firstSeen) FROM explored_cell")
    abstract suspend fun firstEverTimestamp(): Long?

    @Query(
        """
        SELECT strftime('%Y', firstSeen / 1000, 'unixepoch', 'localtime') AS year, COUNT(*) AS count
        FROM explored_cell
        WHERE firstSeen > 0
        GROUP BY year
        ORDER BY year DESC
        """
    )
    abstract suspend fun newCellsPerYear(): List<YearCount>

    @Query("SELECT * FROM explored_cell ORDER BY y, x LIMIT :limit OFFSET :offset")
    abstract suspend fun page(limit: Int, offset: Int): List<ExploredCellEntity>

    @Query("DELETE FROM explored_cell")
    abstract suspend fun deleteAll()
}

data class YearCount(val year: String, val count: Int)

@Dao
abstract class TrackPointDao {

    @Insert
    abstract suspend fun insert(point: TrackPointEntity)

    @Query("SELECT * FROM track_point WHERE timestamp >= :since ORDER BY timestamp ASC LIMIT :limit")
    abstract suspend fun since(since: Long, limit: Int): List<TrackPointEntity>

    @Query("SELECT * FROM track_point ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    abstract suspend fun page(limit: Int, offset: Int): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_point")
    abstract suspend fun count(): Int

    @Query("DELETE FROM track_point WHERE timestamp < :cutoff")
    abstract suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM track_point")
    abstract suspend fun deleteAll()
}

@Dao
abstract class DailyStatDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDay(stat: DailyStatEntity)

    @Query(
        """
        UPDATE daily_stat
        SET distanceMeters = distanceMeters + :distanceMeters, newCells = newCells + :newCells
        WHERE date = :date
        """
    )
    abstract suspend fun incrementDay(date: String, distanceMeters: Double, newCells: Int): Int

    /**
     * SQLite on API 26 predates UPSERT, so this is update-then-insert. The transaction is what
     * makes it safe: no other writer can slip a row in between the two statements.
     */
    @Transaction
    open suspend fun addToDay(date: String, distanceMeters: Double, newCells: Int) {
        if (incrementDay(date, distanceMeters, newCells) == 0) {
            insertDay(DailyStatEntity(date, distanceMeters, newCells))
        }
    }

    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM daily_stat")
    abstract suspend fun totalDistance(): Double

    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM daily_stat WHERE date >= :fromDate")
    abstract suspend fun distanceSince(fromDate: String): Double

    @Query("SELECT COUNT(*) FROM daily_stat WHERE distanceMeters > 0 OR newCells > 0")
    abstract suspend fun activeDays(): Int

    @Query("SELECT * FROM daily_stat ORDER BY date DESC LIMIT :limit")
    abstract suspend fun recentDays(limit: Int): List<DailyStatEntity>

    @Query("SELECT MIN(date) FROM daily_stat")
    abstract suspend fun firstDate(): String?

    @Query("DELETE FROM daily_stat")
    abstract suspend fun deleteAll()
}

@Dao
abstract class VisitedPlaceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertNew(place: VisitedPlaceEntity)

    @Query("UPDATE visited_place SET lastSeen = :now WHERE countryCode = :countryCode AND adminArea = :adminArea")
    abstract suspend fun touch(countryCode: String, adminArea: String, now: Long): Int

    @Transaction
    open suspend fun record(place: VisitedPlaceEntity) {
        if (touch(place.countryCode, place.adminArea, place.lastSeen) == 0) {
            insertNew(place)
        }
    }

    @Query("SELECT * FROM visited_place ORDER BY countryName, adminArea")
    abstract suspend fun all(): List<VisitedPlaceEntity>

    @Query("SELECT COUNT(DISTINCT countryCode) FROM visited_place")
    abstract suspend fun countryCount(): Int

    @Query("DELETE FROM visited_place")
    abstract suspend fun deleteAll()
}
