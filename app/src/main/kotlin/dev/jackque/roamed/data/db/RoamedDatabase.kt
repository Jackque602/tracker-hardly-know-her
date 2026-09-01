package dev.jackque.roamed.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExploredCellEntity::class,
        TrackPointEntity::class,
        DailyStatEntity::class,
        VisitedPlaceEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class RoamedDatabase : RoomDatabase() {

    abstract fun exploredCellDao(): ExploredCellDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun visitedPlaceDao(): VisitedPlaceDao

    companion object {
        const val NAME = "roamed.db"

        fun build(context: Context): RoamedDatabase =
            Room.databaseBuilder(context.applicationContext, RoamedDatabase::class.java, NAME)
                .build()
    }
}
