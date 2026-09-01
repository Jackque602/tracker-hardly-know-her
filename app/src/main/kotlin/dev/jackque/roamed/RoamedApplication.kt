package dev.jackque.roamed

import android.app.Application
import android.content.Context
import dev.jackque.roamed.data.db.RoamedDatabase
import dev.jackque.roamed.data.repo.ExplorationRepository
import dev.jackque.roamed.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
