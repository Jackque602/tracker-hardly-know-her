package dev.jackque.roamed.data.repo

import dev.jackque.roamed.core.backup.BackupReader
import dev.jackque.roamed.core.backup.BackupSink
import dev.jackque.roamed.core.backup.GeoJsonSink
import dev.jackque.roamed.core.backup.GpxSink
import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.fog.FogEngine
import dev.jackque.roamed.core.fog.isFlight
import dev.jackque.roamed.core.fog.isImplausibleJump
import dev.jackque.roamed.core.fog.isOneLeg
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.Geo
import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.core.importer.ImportedFix
import dev.jackque.roamed.core.importer.ImportedTrack
import dev.jackque.roamed.core.model.CellRecord
import dev.jackque.roamed.core.model.CellSource
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

/** What an import of someone else's track file actually added. */
data class TrackImportResult(
    val trackCount: Int,
    val pointCount: Int,
    val newCells: Int,
)

sealed interface RecordOutcome {
    /** The fix was too vague to trust. */
    data class Rejected(val reason: String) : RecordOutcome
    data class Recorded(val newCells: Int, val distanceMeters: Double) : RecordOutcome
}

data class ExplorationSummary(
    val cellCount: Int = 0,
    val areaSquareMeters: Double = 0.0,
    /** Of [areaSquareMeters], the part only ever flown over. */
    val flownSquareMeters: Double = 0.0,
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

    /**
     * The subset of [index] that has only ever been flown over.
     *
     * A separate index rather than a flag on each cell, because everything that reads the fog -
     * the overlay's viewport query, the running area, the fit-to-explored box - already works on
     * an ExploredIndex, and a second one gets all of that for free. Flown cells live in both: the
     * map still uncovers them, this just knows which ones to tint.
     */
    val airIndex = ExploredIndex()

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
            airIndex.addAll(
                cells.filter { it.source == CellSource.AIR.id }.map { CellKey.pack(it.x, it.y) },
            )
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
            var flew = false

            if (previous != null) {
                val moved = Geo.distanceMeters(
                    previous.latitude, previous.longitude, fix.latitude, fix.longitude,
                )
                val elapsedSeconds = (fix.timestamp - previous.timestamp) / 1000.0
                when {
                    isImplausibleJump(moved, elapsedSeconds) -> Unit // teleport: reveal, don't join
                    settings.uncoverFlightPaths && isFlight(moved, elapsedSeconds) -> {
                        distance = moved
                        flew = true
                    }
                    moved < jitterThreshold(accuracy) -> {
                        // Standing still. Keep the old anchor so GPS noise cannot fake a walk.
                        return@withLock recordStationary(fix, settings)
                    }
                    else -> {
                        distance = moved
                        joinToPrevious = settings.connectTheDots && isOneLeg(moved, elapsedSeconds)
                    }
                }
            }

            val radius = settings.revealRadiusMeters.toDouble()
            val cells = when {
                flew && previous != null -> fog.cellsAlongFlight(
                    previous.latitude, previous.longitude,
                    fix.latitude, fix.longitude,
                    radiusMeters = radius,
                )
                joinToPrevious && previous != null -> fog.cellsAlongSegment(
                    previous.latitude, previous.longitude,
                    fix.latitude, fix.longitude,
                    radiusMeters = radius,
                )
                else -> fog.cellsWithinRadius(fix.latitude, fix.longitude, radius)
            }

            val source = if (flew) CellSource.AIR else CellSource.GROUND
            val fresh = index.addAll(cells)
            if (flew) airIndex.addAll(fresh) else promoteToGround(cells)
            persist(fix, fresh, distance, settings, source)
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
        promoteToGround(cells)
        persist(fix, fresh, distanceMeters = 0.0, settings = settings, source = CellSource.GROUND)
        publish(fix)
        return RecordOutcome.Recorded(fresh.size, 0.0)
    }

    /**
     * Reclassifies squares that were only ever flown over and have now actually been visited.
     *
     * Every landing does this: the flight ribbon covers the airport it ends at, and the first fix
     * on the ground there is standing in squares currently marked as flown. Usually a handful of
     * cells, so the per-cell update is not worth batching.
     */
    private suspend fun promoteToGround(cells: Collection<Long>) {
        if (airIndex.size == 0) return
        val promoted = airIndex.removeAll(cells)
        for (key in promoted) {
            database.exploredCellDao()
                .setSource(CellKey.x(key), CellKey.y(key), CellSource.GROUND.id)
        }
    }

    private suspend fun persist(
        fix: Fix,
        freshKeys: List<Long>,
        distanceMeters: Double,
        settings: RoamedSettings,
        source: CellSource,
    ) {
        val now = fix.timestamp
        if (freshKeys.isNotEmpty()) {
            freshKeys.chunked(IMPORT_CHUNK).forEach { chunk ->
                database.exploredCellDao().insertNew(
                    chunk.map { key ->
                        ExploredCellEntity(
                            CellKey.x(key), CellKey.y(key), now, now, visits = 1, source = source.id,
                        )
                    },
                )
            }
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

    /**
     * Uncovers the ground covered by tracks imported from elsewhere - a Google Timeline export, a
     * GPX from another app - so a trip the tracker missed can still be put on the map.
     *
     * The revealing runs before the lock is taken. A year of location history is a lot of points,
     * and holding the mutex through all of it would stall live tracking for the whole import.
     */
    suspend fun importTracks(
        tracks: List<ImportedTrack>,
        settings: RoamedSettings,
    ): TrackImportResult = withContext(io) {
        val radius = settings.revealRadiusMeters.toDouble()
        var pointCount = 0
        // Earliest timestamp wins, so an imported cell is dated when it was actually first crossed.
        val discovered = HashMap<Long, Long>()
        // A cell reached both ways is ground: having flown over somewhere you also walked adds
        // nothing to what you know of it, and must not take the credit away.
        val walked = HashSet<Long>()
        val flown = HashSet<Long>()

        for (track in tracks) {
            var previous: ImportedFix? = null
            for (point in track.points) {
                pointCount++
                val cells = HashSet<Long>()
                val from = previous
                val flightLeg = from != null && settings.uncoverFlightPaths && flownBetween(from, point)
                when {
                    from != null && flightLeg -> fog.cellsAlongFlight(
                        from.latitude, from.longitude,
                        point.latitude, point.longitude,
                        radiusMeters = radius,
                        into = cells,
                    )
                    from != null && joinable(from, point, track.contiguous) -> fog.cellsAlongSegment(
                        from.latitude, from.longitude,
                        point.latitude, point.longitude,
                        radiusMeters = radius,
                        into = cells,
                    )
                    else -> fog.cellsWithinRadius(point.latitude, point.longitude, radius, cells)
                }
                val stamp = point.timestamp ?: 0L
                for (cell in cells) {
                    val existing = discovered[cell]
                    if (existing == null || (stamp in 1 until existing)) discovered[cell] = stamp
                }
                (if (flightLeg) flown else walked).addAll(cells)
                previous = point
            }
        }

        mutex.withLock {
            val fresh = discovered.filterKeys { !index.contains(it) }
            fresh.entries.chunked(IMPORT_CHUNK).forEach { chunk ->
                database.exploredCellDao().insertNew(
                    chunk.map { (key, stamp) ->
                        val source =
                            if (key in flown && key !in walked) CellSource.AIR else CellSource.GROUND
                        ExploredCellEntity(
                            CellKey.x(key), CellKey.y(key), stamp, stamp, visits = 1,
                            source = source.id,
                        )
                    },
                )
            }
            index.addAll(fresh.keys)
            airIndex.addAll(fresh.keys.filter { it in flown && it !in walked })
            // Ground in this import beats a flight recorded over the same square earlier.
            promoteToGround(walked)
            publish(_state.value.lastFix)
            TrackImportResult(tracks.size, pointCount, fresh.size)
        }
    }

    /**
     * Whether an imported leg was flown.
     *
     * Timestamps are required rather than guessed at: the only evidence separating a flight from a
     * tracker that slept through a drive is the speed, and without two times there is no speed.
     */
    private fun flownBetween(from: ImportedFix, to: ImportedFix): Boolean {
        val start = from.timestamp ?: return false
        val end = to.timestamp ?: return false
        val moved = Geo.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        return isFlight(moved, (end - start) / 1000.0)
    }

    /**
     * Whether the road between two imported points may be filled in.
     *
     * For recorded history this is the same rule live tracking uses: near enough, and soon enough,
     * to be one continuous leg. A file that declares its points to be a single path is taken at its
     * word instead, subject only to a sanity limit, because a turn-by-turn route can put fifty
     * kilometres of motorway between two waypoints and still be one unbroken drive.
     */
    private fun joinable(from: ImportedFix, to: ImportedFix, contiguous: Boolean): Boolean {
        val moved = Geo.distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        if (contiguous) return moved <= CONTIGUOUS_SANITY_LIMIT_METERS
        if (moved > FogEngine.DEFAULT_MAX_GAP_METERS) return false
        val start = from.timestamp
        val end = to.timestamp
        // Without timestamps the file's own ordering is the only evidence there is, so distance decides.
        if (start == null || end == null) return true
        return isOneLeg(moved, (end - start) / 1000.0)
    }

    /** Drops raw fixes older than the retention window. The fog itself is never pruned. */
    suspend fun pruneRawFixes(keepDays: Int): Int = withContext(io) {
        if (keepDays <= 0) return@withContext 0
        val cutoff = clock() - keepDays * MILLIS_PER_DAY
        database.trackPointDao().deleteOlderThan(cutoff)
    }

    /**
     * The most recent fixes within the window, oldest first.
     *
     * The limit takes the *newest* rows and they are turned back round here. Taking the oldest
     * instead would mean that on a busy day the trail stopped partway through it and never showed
     * where you had just been, which is the half anyone looking at a trail actually wants.
     */
    suspend fun recentTrail(sinceMillis: Long, limit: Int = 2_000): List<TrackPointEntity> =
        withContext(io) { database.trackPointDao().newestSince(sinceMillis, limit).asReversed() }

    suspend fun recordPlace(place: VisitedPlaceEntity) = withContext(io) {
        database.visitedPlaceDao().record(place)
    }

    /**
     * Every uncovered cell that was actually travelled through.
     *
     * What the region breakdown counts, so that overflying a country at ten kilometres does not
     * tick it off the list of countries you have been to.
     */
    fun groundKeys(): LongArray {
        val everything = index.snapshotKeys()
        if (airIndex.size == 0) return everything
        val ground = LongArray(everything.size)
        var n = 0
        for (key in everything) if (!airIndex.contains(key)) ground[n++] = key
        return ground.copyOf(n)
    }

    suspend fun summary(): ExplorationSummary = withContext(io) {
        val area = index.areaSquareMeters
        val yearStart = LocalDate.now().withDayOfYear(1).toString()
        ExplorationSummary(
            cellCount = index.size,
            areaSquareMeters = area,
            flownSquareMeters = airIndex.areaSquareMeters,
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
            airIndex.clear()
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
                            ExploredCellEntity(
                                it.x, it.y, it.firstSeen, it.lastSeen, it.visits, it.source.id,
                            )
                        },
                    )
                    index.addAll(fresh.map { CellKey.pack(it.x, it.y) })
                    airIndex.addAll(
                        fresh.filter { it.source == CellSource.AIR }
                            .map { CellKey.pack(it.x, it.y) },
                    )
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
            page.forEach {
                action(
                    CellRecord(
                        it.x, it.y, it.firstSeen, it.lastSeen, it.visits, CellSource.of(it.source),
                    ),
                )
            }
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

        /** Only a guard against a corrupt file; a declared path is otherwise trusted. */
        const val CONTIGUOUS_SANITY_LIMIT_METERS = 500_000.0
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}
