package dev.jackque.roamed.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jackque.roamed.AppContainer
import dev.jackque.roamed.core.fog.ExploredIndex
import dev.jackque.roamed.core.geo.GeoBounds
import dev.jackque.roamed.data.repo.ExplorationRepository
import dev.jackque.roamed.data.repo.FogState
import dev.jackque.roamed.data.repo.RoamedSettings
import dev.jackque.roamed.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(
    private val exploration: ExplorationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** The overlay reads this directly; it is the single authoritative copy of the fog. */
    val index: ExploredIndex get() = exploration.index

    val fogState: StateFlow<FogState> = exploration.state

    val settings: StateFlow<RoamedSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoamedSettings())

    private val _trail = MutableStateFlow<List<TrailPoint>>(emptyList())
    val trail: StateFlow<List<TrailPoint>> = _trail.asStateFlow()

    init {
        viewModelScope.launch { exploration.load() }
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.showTrail }
                .distinctUntilChanged()
                .collectLatest { show ->
                    if (!show) {
                        _trail.value = emptyList()
                        return@collectLatest
                    }
                    // Refresh periodically rather than per fix: the trail is context, not data.
                    while (true) {
                        _trail.value = loadTrail()
                        delay(TRAIL_REFRESH_MILLIS)
                    }
                }
        }
    }

    fun setTracking(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTrackingEnabled(enabled) }
    }

    /**
     * The box enclosing everything uncovered, for the fit-to-explored button.
     *
     * Off the main thread because it walks every stored cell, and someone with years of tracking
     * has hundreds of thousands of them - not slow, but not worth a dropped frame either.
     */
    suspend fun exploredBounds(): GeoBounds? = withContext(Dispatchers.Default) { index.bounds() }

    private suspend fun loadTrail(): List<TrailPoint> {
        val since = System.currentTimeMillis() - TRAIL_WINDOW_MILLIS
        return exploration.recentTrail(since).map { TrailPoint(it.latitude, it.longitude) }
    }

    companion object {
        private const val TRAIL_REFRESH_MILLIS = 30_000L
        private const val TRAIL_WINDOW_MILLIS = 24L * 60L * 60L * 1_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MapViewModel(container.exploration, container.settings) }
        }
    }
}
