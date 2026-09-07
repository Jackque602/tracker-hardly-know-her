package dev.jackque.roamed.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ExploredCellEntity::class,
        TrackPointEntity::class,
        DailyStatEntity::class,
        VisitedPlaceEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class RoamedDatabase : RoomDatabase() {

    abstract fun exploredCellDao(): ExploredCellDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun visitedPlaceDao(): VisitedPlaceDao

    companion object {
        const val NAME = "roamed.db"

        /**
         * Adds the column that says whether a cell was travelled or flown over.
         *
         * Written out rather than falling back to a destructive migration, because the fog is the
         * whole of what this app is: dropping the table to add a column would delete every square
         * the user has ever uncovered.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE explored_cell ADD COLUMN source INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_explored_cell_source ON explored_cell (source)",
                )
            }
        }

        fun build(context: Context): RoamedDatabase =
            Room.databaseBuilder(context.applicationContext, RoamedDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
