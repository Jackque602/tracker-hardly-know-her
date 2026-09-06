package dev.jackque.roamed

import android.app.Application
import android.content.Context
import android.util.Log
import dev.jackque.roamed.core.regions.RegionMask
import dev.jackque.roamed.data.db.RoamedDatabase
import dev.jackque.roamed.data.repo.ExplorationRepository
import dev.jackque.roamed.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Hand-rolled dependency container.
 *
 * The graph is four objects deep, so a DI framework would cost more in build complexity than it
 * would save. Everything is lazy, so opening the app does not touch the database until something
 * actually asks for it.
 */
class AppContainer(private val context: Context) {

    val database: RoamedDatabase by lazy { RoamedDatabase.build(context) }
    val exploration: ExplorationRepository by lazy { ExplorationRepository(database) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The world's borders, read from the packaged mask the first time the stats screen asks and
     * shared from then on. It is about a megabyte, so it is decoded off the main thread and only
     * if something actually wants it - the map and the tracker never do.
     */
    private val regionMaskOnce: Deferred<RegionMask?> by lazy {
        applicationScope.async(Dispatchers.IO) {
            try {
                RegionMask.bundled()
            } catch (e: Throwable) {
                // Worth knowing about, but every other stat still works without it.
                Log.w("Roamed", "region mask unavailable; per-country stats will be hidden", e)
                null
            }
        }
    }

    suspend fun regionMask(): RegionMask? = regionMaskOnce.await()
}

class RoamedApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        configureOsmdroid()
        // Warm the fog into memory so the map has something to draw the moment it appears.
        container.applicationScope.launch { container.exploration.load() }
    }

    /**
     * osmdroid defaults to a cache directory on shared storage, which needs a permission this app
     * has no business asking for. Point it at our own cache instead, and identify ourselves
     * properly to the OpenStreetMap tile servers as their usage policy requires.
     */
    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        config.userAgentValue = "Roamed/${BuildConfig.VERSION_NAME} (${packageName})"
        val base = File(cacheDir, "osmdroid")
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles")
    }
}

/** Reaches the container from anywhere holding a Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as RoamedApplication).container
