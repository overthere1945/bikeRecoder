package com.cowork.bikerecoder.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.RecentPlaceStore
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val places: List<Place>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

data class SearchScreenState(
    val query: String = "",
    val result: SearchUiState = SearchUiState.Idle,
    /** The result the user tapped; shown in the destination/waypoint sheet. */
    val selected: Place? = null,
    /** "최근 검색", newest first; the screen shows it while the query is empty. */
    val recent: List<Place> = emptyList(),
)

class SearchViewModel(
    private val placeSearch: PlaceSearch,
    private val recent: RecentPlaceStore,
    private val currentLocation: () -> GeoPoint?,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchScreenState())
    val state: StateFlow<SearchScreenState> = _state.asStateFlow()

    private val trimmedQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            // A broken store only hides the list; searching keeps working.
            recent.places
                .catch { emit(emptyList()) }
                .collect { places -> _state.update { it.copy(recent = places) } }
        }
        viewModelScope.launch {
            // collectLatest cancels the pending/in-flight search on every change; the delay is the debounce.
            trimmedQuery.collectLatest { query ->
                if (query.isEmpty()) {
                    _state.update { it.copy(result = SearchUiState.Idle) }
                    return@collectLatest
                }
                delay(DEBOUNCE_MS)
                _state.update { it.copy(result = SearchUiState.Loading) }
                val result = when (val r = placeSearch.keyword(query, currentLocation())) {
                    is SearchResult.Ok -> SearchUiState.Results(r.value)
                    is SearchResult.Err -> SearchUiState.Error(r.error.messageKo)
                }
                _state.update { it.copy(result = result) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        trimmedQuery.value = query.trim()
    }

    /** A search result or a recent place was tapped: open its sheet and remember it. */
    fun select(place: Place) {
        _state.update { it.copy(selected = place) }
        writeRecent { recent.add(place) }
    }

    fun removeRecent(place: Place) = writeRecent { recent.remove(place) }

    fun clearRecent() = writeRecent { recent.clear() }

    /** Failures are ignored: the recent list must never block searching. */
    private fun writeRecent(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not saved this time.
            }
        }
    }

    fun dismissSelection() {
        _state.update { it.copy(selected = null) }
    }

    companion object {
        const val DEBOUNCE_MS = 400L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SearchViewModel(container.placeSearch, container.recentPlaces) { container.currentLocation.value?.point }
            }
        }
    }
}
