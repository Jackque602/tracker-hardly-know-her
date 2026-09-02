package dev.jackque.roamed.data.repo

import dev.jackque.roamed.core.backup.BackupReader
import dev.jackque.roamed.core.backup.BackupSink
import dev.jackque.roamed.core.backup.GeoJsonSink
import dev.jackque.roamed.core.backup.GpxSink
import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.fog.FogEngine
import dev.jackque.roamed.core.fog.isImplausibleJump
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.Geo
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.core.model.CellRecord
import dev.jackque.roamed.core.model.TrackPointRecord
import dev.jackque.roamed.core.stats.ExplorationStats
import dev.jackque.roamed.data.db.DailyStatEntity
import dev.jackque.roamed.data.db.ExploredCellEntity
import dev.jackque.roamed.data.db.RoamedDatabase
import dev.jackque.roamed.data.db.TrackPointEntity
import dev.jackque.roamed.data.db.VisitedPlaceEntity
import dev.jackque.roamed.data.db.YearCount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** A single GPS fix, stripped of Android types so the pipeline stays testable. */
data class Fix(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val altitude: Double?,
    val speed: Float?,
)

/** What the map needs to know, recomputed whenever the fog changes. */
data class FogState(
    val loaded: Boolean = false,
    val version: Long = 0L,
    val cellCount: Int = 0,
    val areaSquareMeters: Double = 0.0,
    val lastFix: Fix? = null,
)

sealed interface RecordOutcome {
    /** The fix was too vague to trust. */
    data class Rejected(val reason: String) : RecordOutcome
    data class Recorded(val newCells: Int, val distanceMeters: Double) : RecordOutcome
}

data class ExplorationSummary(
    val cellCount: Int = 0,
    val areaSquareMeters: Double = 0.0,
    val percentOfSurface: Double = 0.0,
    val percentOfLand: Double = 0.0,
    val totalDistanceMeters: Double = 0.0,
    val distanceThisYearMeters: Double = 0.0,
    val activeDays: Int = 0,
    val firstDate: String? = null,
    val countryCount: Int = 0,
    val places: List<VisitedPlaceEntity> = emptyList(),
    val newCellsPerYear: List<YearCount> = emptyList(),
    val recentDays: List<DailyStatEntity> = emptyList(),
    val rawFixCount: Int = 0,
)

/**
 * The single place where "a GPS fix arrived" turns into "more of the world is uncovered".
 *
 * Holds the authoritative in-memory [ExploredIndex] and keeps the database in step with it. Every
 * mutation goes through [mutex], because fixes arrive on the service's thread while the map reads
 * the index on the UI thread.
 */
class ExplorationRepository(
    private val database: RoamedDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val index = ExploredIndex()
    private val fog = FogEngine()
    private val mutex = Mutex()

    private val _state = MutableStateFlow(FogState())
    val state: StateFlow<FogState> = _state.asStateFlow()

    /** The last fix used as the anchor for distance and for joining up the trail. */
    private var anchor: Fix? = null

    /** Loads the stored fog into memory. Safe to call more than once. */
    suspend fun load() = withContext(io) {
        mutex.withLock {
            if (_state.value.loaded) return@withLock
            val cells = database.exploredCellDao().loadAll()
            index.addAll(cells.map { CellKey.pack(it.x, it.y) })
            publish()
        }
    }

    suspend fun recordFix(fix: Fix, settings: RoamedSettings): RecordOutcome = withContext(io) {
        val accuracy = fix.accuracy
        if (accuracy != null && accuracy > settings.maxAccuracyMeters) {
            return@withContext RecordOutcome.Rejected("accuracy ${accuracy.toInt()} m")
        }

        mutex.withLock {
            val previous = anchor
            var distance = 0.0
            var joinToPrevious = false

            if (previous != null) {
                val moved = Geo.distanceMeters(
                    previous.latitude, previous.longitude, fix.latitude, fix.longitude,
                )
                val elapsedSeconds = (fix.timestamp - previous.timestamp) / 1000.0
                when {
                    isImplausibleJump(moved, elapsedSeconds) -> Unit // teleport: reveal, don't join
                    moved < jitterThreshold(accuracy) -> {
                        // Standing still. Keep the old anchor so GPS noise cannot fake a walk.
                        return@withLock recordStationary(fix, settings)
                    }
                    else -> {
                        distance = moved
                        // Distance alone is not enough: a gap can be short in km but hours long,
                        // and the road taken over those hours is anyone's guess. Bridge only what
                        // could plausibly have been driven straight through.
                        joinToPrevious = settings.connectTheDots &&
                            moved <= FogEngine.DEFAULT_MAX_GAP_METERS &&
                            elapsedSeconds <= MAX_GAP_SECONDS
                    }
                }
            }

            val radius = settings.revealRadiusMeters.toDouble()
            val cells = if (joinToPrevious && previous != null) {
                fog.cellsAlongSegment(
                    previous.latitude, previous.longitude,
                    fix.latitude, fix.longitude,
                    radiusMeters = radius,
                )
            } else {
                fog.cellsWithinRadius(fix.latitude, fix.longitude, radius)
            }

            val fresh = index.addAll(cells)
            persist(fix, fresh, distance, settings)
            anchor = fix
            publish(fix)
            RecordOutcome.Recorded(fresh.size, distance)
        }
    }

    /** A fix that did not move far enough to count as travel still refreshes the fog around you. */
    private suspend fun recordStationary(fix: Fix, settings: RoamedSettings): RecordOutcome {
        val cells = fog.cellsWithinRadius(
            fix.latitude, fix.longitude, settings.revealRadiusMeters.toDouble(),
        )
        val fresh = index.addAll(cells)
        persist(fix, fresh, distanceMeters = 0.0, settings = settings)
        publish(fix)
        return RecordOutcome.Recorded(fresh.size, 0.0)
    }

    private suspend fun persist(
        fix: Fix,
        freshKeys: List<Long>,
        distanceMeters: Double,
        settings: RoamedSettings,
    ) {
        val now = fix.timestamp
        if (freshKeys.isNotEmpty()) {
            database.exploredCellDao().insertNew(
                freshKeys.map { key ->
                    ExploredCellEntity(CellKey.x(key), CellKey.y(key), now, now, visits = 1)
                },
            )
        }
        // Bump the visit counter for the cell you are actually standing in - but not if it was
        // only just created, which already counts as visit one.
        val hereX = TileMath.cellX(fix.longitude, RevealZoom.Z)
        val hereY = TileMath.cellY(fix.latitude, RevealZoom.Z)
        if (!freshKeys.contains(CellKey.pack(hereX, hereY))) {
            database.exploredCellDao().markVisited(hereX, hereY, now)
        }

        if (settings.keepRawFixesDays > 0) {
            database.trackPointDao().insert(
                TrackPointEntity(
                    timestamp = now,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    altitude = fix.altitude,
                    accuracy = fix.accuracy,
                    speed = fix.speed,
                ),
            )
        }
        database.dailyStatDao().addToDay(localDate(now), distanceMeters, freshKeys.size)
    }

    /** Drops raw fixes older than the retention window. The fog itself is never pruned. */
    suspend fun pruneRawFixes(keepDays: Int): Int = withContext(io) {
        if (keepDays <= 0) return@withContext 0
        val cutoff = clock() - keepDays * MILLIS_PER_DAY
        database.trackPointDao().deleteOlderThan(cutoff)
    }

    suspend fun recentTrail(sinceMillis: Long, limit: Int = 2_000): List<TrackPointEntity> =
        withContext(io) { database.trackPointDao().since(sinceMillis, limit) }

    suspend fun recordPlace(place: VisitedPlaceEntity) = withContext(io) {
        database.visitedPlaceDao().record(place)
    }

    suspend fun summary(): ExplorationSummary = withContext(io) {
        val area = index.areaSquareMeters
        val yearStart = LocalDate.now().withDayOfYear(1).toString()
        ExplorationSummary(
            cellCount = index.size,
            areaSquareMeters = area,
            percentOfSurface = ExplorationStats.percentOfEarthSurface(area),
            percentOfLand = ExplorationStats.percentOfEarthLand(area),
            totalDistanceMeters = database.dailyStatDao().totalDistance(),
            distanceThisYearMeters = database.dailyStatDao().distanceSince(yearStart),
            activeDays = database.dailyStatDao().activeDays(),
            firstDate = database.dailyStatDao().firstDate(),
            countryCount = database.visitedPlaceDao().countryCount(),
            places = database.visitedPlaceDao().all(),
            newCellsPerYear = database.exploredCellDao().newCellsPerYear(),
            recentDays = database.dailyStatDao().recentDays(RECENT_DAYS),
            rawFixCount = database.trackPointDao().count(),
        )
    }

    suspend fun clearEverything() = withContext(io) {
        mutex.withLock {
            database.exploredCellDao().deleteAll()
            database.trackPointDao().deleteAll()
            database.dailyStatDao().deleteAll()
            database.visitedPlaceDao().deleteAll()
            index.clear()
            anchor = null
            publish()
        }
    }

    suspend fun writeBackup(out: Appendable, appVersion: String) = withContext(io) {
        val sink = BackupSink(out)
        sink.begin(clock(), appVersion, database.exploredCellDao().count())
        forEachCellPage { cell -> sink.add(cell) }
        sink.end()
    }

    suspend fun writeGeoJson(out: Appendable) = withContext(io) {
        val sink = GeoJsonSink(out)
        sink.begin()
        forEachCellPage { cell -> sink.add(cell) }
        sink.end()
    }

    suspend fun writeGpx(out: Appendable) = withContext(io) {
        val sink = GpxSink(out)
        sink.begin("Roamed track")
        var offset = 0
        while (true) {
            val page = database.trackPointDao().page(PAGE_SIZE, offset)
            page.forEach {
                sink.add(
                    TrackPointRecord(
                        it.timestamp, it.latitude, it.longitude, it.altitude, it.accuracy, it.speed,
                    ),
                )
            }
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        sink.end()
    }

    /**
     * Merges a backup into whatever is already recorded.
     *
     * Merging rather than replacing is deliberate: importing a backup from an old phone should add
     * to the map, not wipe the last month off it.
     */
    suspend fun importBackup(text: String): Int = withContext(io) {
        val records = BackupReader.read(text)
        mutex.withLock {
            var added = 0
            records.chunked(IMPORT_CHUNK).forEach { chunk ->
                val fresh = chunk.filterNot { index.contains(CellKey.pack(it.x, it.y)) }
                if (fresh.isNotEmpty()) {
                    database.exploredCellDao().insertNew(
                        fresh.map {
                            ExploredCellEntity(it.x, it.y, it.firstSeen, it.lastSeen, it.visits)
                        },
                    )
                    index.addAll(fresh.map { CellKey.pack(it.x, it.y) })
                    added += fresh.size
                }
            }
            publish(_state.value.lastFix)
            added
        }
    }

    /** Walks every stored cell a page at a time, so an export never holds the lot in memory. */
    private suspend fun forEachCellPage(action: (CellRecord) -> Unit) {
        var offset = 0
        while (true) {
            val page = database.exploredCellDao().page(PAGE_SIZE, offset)
            page.forEach { action(CellRecord(it.x, it.y, it.firstSeen, it.lastSeen, it.visits)) }
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
    }

    private fun publish(fix: Fix? = _state.value.lastFix) {
        _state.value = FogState(
            loaded = true,
            version = index.version,
            cellCount = index.size,
            areaSquareMeters = index.areaSquareMeters,
            lastFix = fix,
        )
    }

    private fun localDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /**
     * How far a fix has to move before it counts as movement rather than noise. A stationary phone
     * with a 30 m fix will wander tens of metres between readings; without this the odometer would
     * climb all night.
     */
    private fun jitterThreshold(accuracy: Float?): Double =
        max(MIN_JITTER_METERS, (accuracy ?: 0f).toDouble() * 0.6)

    private companion object {
        const val PAGE_SIZE = 5_000
        const val IMPORT_CHUNK = 2_000
        const val RECENT_DAYS = 30
        const val MIN_JITTER_METERS = 10.0

        /** Ten minutes: long enough for a tunnel or a dead zone, short enough to still be one leg. */
        const val MAX_GAP_SECONDS = 600.0
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}
