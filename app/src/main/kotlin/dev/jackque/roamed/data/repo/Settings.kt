package dev.jackque.roamed.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class MapStyle { STANDARD, TOPOGRAPHIC }

/**
 * Everything the user can tune.
 *
 * Defaults are chosen for "leave it on all day and forget about it": a fix every 25 seconds only
 * when you have actually moved 20 metres costs very little battery, and a 120 m reveal radius is
 * generous enough that a walk clears a satisfying stripe without inventing coverage.
 *
 * [highAccuracyMode] defaults on because the balanced-power mode targets roughly block-level
 * accuracy, which it can only reach where there is WiFi to lean on. Out on a rural road it falls
 * back to cell towers and returns fixes hundreds of metres wide, and an app whose entire job is
 * recording where you went cannot do that job on fixes like those.
 */
data class RoamedSettings(
    val trackingEnabled: Boolean = false,
    val updateIntervalSeconds: Int = 25,
    val minDisplacementMeters: Int = 20,
    val revealRadiusMeters: Int = 120,
    val maxAccuracyMeters: Int = 150,
    val connectTheDots: Boolean = true,
    val highAccuracyMode: Boolean = true,
    val fogOpacity: Float = 0.85f,
    val showTrail: Boolean = false,
    val keepRawFixesDays: Int = 365,
    val resolvePlaces: Boolean = true,
    val mapStyle: MapStyle = MapStyle.STANDARD,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<RoamedSettings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun setTrackingEnabled(enabled: Boolean) = edit { it[Keys.TRACKING] = enabled }

    suspend fun setUpdateInterval(seconds: Int) =
        edit { it[Keys.INTERVAL] = seconds.coerceIn(5, 600) }

    suspend fun setMinDisplacement(meters: Int) =
        edit { it[Keys.DISPLACEMENT] = meters.coerceIn(0, 500) }

    suspend fun setRevealRadius(meters: Int) =
        edit { it[Keys.RADIUS] = meters.coerceIn(25, 1_000) }

    suspend fun setMaxAccuracy(meters: Int) =
        edit { it[Keys.ACCURACY] = meters.coerceIn(10, 500) }

    suspend fun setConnectTheDots(enabled: Boolean) = edit { it[Keys.CONNECT] = enabled }

    suspend fun setHighAccuracyMode(enabled: Boolean) = edit { it[Keys.HIGH_ACCURACY] = enabled }

    suspend fun setFogOpacity(opacity: Float) =
        edit { it[Keys.OPACITY] = opacity.coerceIn(0.2f, 1f) }

    suspend fun setShowTrail(enabled: Boolean) = edit { it[Keys.TRAIL] = enabled }

    suspend fun setKeepRawFixesDays(days: Int) =
        edit { it[Keys.KEEP_DAYS] = days.coerceIn(0, 3_650) }

    suspend fun setResolvePlaces(enabled: Boolean) = edit { it[Keys.PLACES] = enabled }

    suspend fun setMapStyle(style: MapStyle) = edit { it[Keys.MAP_STYLE] = style.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun Preferences.toSettings(): RoamedSettings {
        val defaults = RoamedSettings()
        return RoamedSettings(
            trackingEnabled = this[Keys.TRACKING] ?: defaults.trackingEnabled,
            updateIntervalSeconds = this[Keys.INTERVAL] ?: defaults.updateIntervalSeconds,
            minDisplacementMeters = this[Keys.DISPLACEMENT] ?: defaults.minDisplacementMeters,
            revealRadiusMeters = this[Keys.RADIUS] ?: defaults.revealRadiusMeters,
            maxAccuracyMeters = this[Keys.ACCURACY] ?: defaults.maxAccuracyMeters,
            connectTheDots = this[Keys.CONNECT] ?: defaults.connectTheDots,
            highAccuracyMode = this[Keys.HIGH_ACCURACY] ?: defaults.highAccuracyMode,
            fogOpacity = this[Keys.OPACITY] ?: defaults.fogOpacity,
            showTrail = this[Keys.TRAIL] ?: defaults.showTrail,
            keepRawFixesDays = this[Keys.KEEP_DAYS] ?: defaults.keepRawFixesDays,
            resolvePlaces = this[Keys.PLACES] ?: defaults.resolvePlaces,
            mapStyle = this[Keys.MAP_STYLE]?.let { name ->
                MapStyle.entries.firstOrNull { it.name == name }
            } ?: defaults.mapStyle,
        )
    }

    private object Keys {
        val TRACKING = booleanPreferencesKey("tracking_enabled")
        val INTERVAL = intPreferencesKey("update_interval_seconds")
        val DISPLACEMENT = intPreferencesKey("min_displacement_meters")
        val RADIUS = intPreferencesKey("reveal_radius_meters")
        val ACCURACY = intPreferencesKey("max_accuracy_meters")
        val CONNECT = booleanPreferencesKey("connect_the_dots")
        val HIGH_ACCURACY = booleanPreferencesKey("high_accuracy_mode")
        val OPACITY = floatPreferencesKey("fog_opacity")
        val TRAIL = booleanPreferencesKey("show_trail")
        val KEEP_DAYS = intPreferencesKey("keep_raw_fixes_days")
        val PLACES = booleanPreferencesKey("resolve_places")
        val MAP_STYLE = stringPreferencesKey("map_style")
    }
}
