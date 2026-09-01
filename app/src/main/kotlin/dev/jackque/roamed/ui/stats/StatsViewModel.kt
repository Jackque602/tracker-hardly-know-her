package dev.jackque.roamed.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jackque.roamed.AppContainer
import dev.jackque.roamed.data.repo.ExplorationRepository
import dev.jackque.roamed.data.repo.ExplorationSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class StatsUiState(
    val loading: Boolean = true,
    val summary: ExplorationSummary = ExplorationSummary(),
)

class StatsViewModel(private val exploration: ExplorationRepository) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            exploration.load()
            // Recompute whenever the fog actually changes rather than on a timer.
            exploration.state.map { it.version }.collectLatest {
                _state.value = StatsUiState(loading = false, summary = exploration.summary())
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(summary = exploration.summary(), loading = false)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { StatsViewModel(container.exploration) }
        }
    }
}
