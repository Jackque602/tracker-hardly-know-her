package dev.jackque.roamed.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jackque.roamed.AppContainer
import dev.jackque.roamed.core.regions.RegionBreakdown
import dev.jackque.roamed.core.regions.RegionMask
import dev.jackque.roamed.core.regions.RegionTally
import dev.jackque.roamed.data.repo.ExplorationRepository
import dev.jackque.roamed.data.repo.ExplorationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatsUiState(
    val loading: Boolean = true,
    val summary: ExplorationSummary = ExplorationSummary(),
    /** Null until the region mask has been read, or for good if it could not be. */
    val regions: RegionTally? = null,
)

class StatsViewModel(
    private val exploration: ExplorationRepository,
    private val regionMask: suspend () -> RegionMask?,
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            exploration.load()
            // Recompute whenever the fog actually changes rather than on a timer.
            exploration.state.map { it.version }.collectLatest { recompute() }
        }
    }

    fun refresh() {
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val summary = exploration.summary()
        // Show the cheap numbers straight away; the region breakdown walks every uncovered square
        // and reads a megabyte of borders the first time, which is not worth blocking the screen on.
        _state.value = StatsUiState(loading = false, summary = summary, regions = _state.value.regions)
        _state.value = _state.value.copy(regions = breakdown())
    }

    /**
     * Ground cells only.
     *
     * Flying over a country at ten kilometres is not being there, and counting it would put every
     * country on a long-haul route onto the list of places visited.
     */
    private suspend fun breakdown(): RegionTally? {
        val mask = regionMask() ?: return null
        val cells = exploration.groundKeys()
        return withContext(Dispatchers.Default) { RegionBreakdown.of(mask, cells) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { StatsViewModel(container.exploration, container::regionMask) }
        }
    }
}
