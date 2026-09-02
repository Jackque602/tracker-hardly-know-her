package dev.jackque.roamed.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jackque.roamed.AppContainer
import dev.jackque.roamed.BuildConfig
import dev.jackque.roamed.core.importer.TrackImport
import dev.jackque.roamed.data.repo.ExplorationRepository
import dev.jackque.roamed.data.repo.MapStyle
import dev.jackque.roamed.data.repo.RoamedSettings
import dev.jackque.roamed.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val appContext: Context,
    private val exploration: ExplorationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<RoamedSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoamedSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun setUpdateInterval(seconds: Int) = update { settingsRepository.setUpdateInterval(seconds) }
    fun setMinDisplacement(meters: Int) = update { settingsRepository.setMinDisplacement(meters) }
    fun setRevealRadius(meters: Int) = update { settingsRepository.setRevealRadius(meters) }
    fun setMaxAccuracy(meters: Int) = update { settingsRepository.setMaxAccuracy(meters) }
    fun setConnectTheDots(on: Boolean) = update { settingsRepository.setConnectTheDots(on) }
    fun setHighAccuracy(on: Boolean) = update { settingsRepository.setHighAccuracyMode(on) }
    fun setFogOpacity(value: Float) = update { settingsRepository.setFogOpacity(value) }
    fun setShowTrail(on: Boolean) = update { settingsRepository.setShowTrail(on) }
    fun setKeepRawFixesDays(days: Int) = update { settingsRepository.setKeepRawFixesDays(days) }
    fun setResolvePlaces(on: Boolean) = update { settingsRepository.setResolvePlaces(on) }
    fun setMapStyle(style: MapStyle) = update { settingsRepository.setMapStyle(style) }

    fun exportBackup(uri: Uri) = runFileTask("Backup saved") { resolver ->
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            exploration.writeBackup(writer, BuildConfig.VERSION_NAME)
        } ?: error("Could not open that file for writing")
    }

    fun exportGeoJson(uri: Uri) = runFileTask("GeoJSON saved") { resolver ->
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            exploration.writeGeoJson(writer)
        } ?: error("Could not open that file for writing")
    }

    fun exportGpx(uri: Uri) = runFileTask("GPX saved") { resolver ->
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            exploration.writeGpx(writer)
        } ?: error("Could not open that file for writing")
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open that file for reading")
                }
                exploration.importBackup(text)
            }
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { added ->
                    if (added == 0) "Nothing new in that backup - the map already had all of it."
                    else "Added $added squares from the backup."
                },
                onFailure = { "Import failed: ${it.message}" },
            )
        }
    }

    /**
     * Uncovers a trip recorded by something else - a Google Timeline export, or a GPX from any
     * other tracker - so a journey this app missed is not lost for good.
     */
    fun importTracks(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open that file for reading")
                }
                val tracks = TrackImport.parse(text)
                if (tracks.isEmpty()) error("No positions found in that file")
                exploration.importTracks(tracks, settings.value)
            }
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { imported ->
                    if (imported.newCells == 0) {
                        "Read ${imported.pointCount} positions, but that ground was already uncovered."
                    } else {
                        "Uncovered ${imported.newCells} new squares from ${imported.pointCount} " +
                            "positions across ${imported.trackCount} trips."
                    }
                },
                onFailure = { "Import failed: ${it.message}" },
            )
        }
    }

    fun clearEverything() {
        viewModelScope.launch {
            _busy.value = true
            runCatching { exploration.clearEverything() }
            _busy.value = false
            _message.value = "Everything erased. The map is fogged over again."
        }
    }

    private fun runFileTask(successMessage: String, block: suspend (android.content.ContentResolver) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching { block(appContext.contentResolver) }
            _busy.value = false
            _message.value = result.fold(
                onSuccess = { successMessage },
                onFailure = { "Export failed: ${it.message}" },
            )
        }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(context: Context, container: AppContainer) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    context.applicationContext,
                    container.exploration,
                    container.settings,
                )
            }
        }
    }
}
