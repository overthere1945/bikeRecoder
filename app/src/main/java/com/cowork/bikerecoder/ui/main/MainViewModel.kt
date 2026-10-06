package com.cowork.bikerecoder.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** "🚩 {destinationName} · 여러 날 {dayNumber}일차" banner for the active multi-day trip. */
data class TripBanner(val tripId: Long, val destinationName: String, val dayNumber: Int)

/** A trip left idle for 3+ days, asked about once when the main screen first opens. */
data class StaleTrip(val tripId: Long, val destinationName: String)

/** The map point the user long-pressed, with its address lookup. */
data class PressedPlace(val point: GeoPoint, val address: String?, val loading: Boolean) {
    /** Address when known, else the coordinates (also the fallback when the lookup failed). */
    val title: String get() = address ?: String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon)
}

data class MainUiState(
    val banner: TripBanner? = null,
    val staleTrip: StaleTrip? = null,
    val pressed: PressedPlace? = null,
)

class MainViewModel(
    private val tripManager: TripManager,
    private val tripStore: TripStore,
    private val placeSearch: PlaceSearch,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var addressJob: Job? = null

    init {
        viewModelScope.launch {
            val stale = runCatchingNonCancel { tripManager.stalePrompt() }
            _state.update { it.copy(staleTrip = stale?.let { t -> StaleTrip(t.id, destinationName(t.id)) }) }
        }
        refreshBanner()
    }

    /** Re-reads the active multi-day trip (e.g. after returning from navigation or completing a trip). */
    fun refreshBanner() {
        viewModelScope.launch {
            val banner = runCatchingNonCancel {
                tripStore.activeTrip()?.takeIf { it.type == TripType.MULTI_DAY }?.let { trip ->
                    TripBanner(trip.id, destinationName(trip.id), tripManager.dayNumber(trip))
                }
            }
            _state.update { it.copy(banner = banner) }
        }
    }

    /** [완료] on the stale-trip dialog. */
    fun completeStaleTrip() {
        val stale = _state.value.staleTrip ?: return
        _state.update { it.copy(staleTrip = null) }
        viewModelScope.launch {
            runCatchingNonCancel { tripManager.complete(stale.tripId) }
            refreshBanner()
        }
    }

    /** [계속 유지]. */
    fun dismissStaleTrip() {
        _state.update { it.copy(staleTrip = null) }
    }

    fun onLongPress(point: GeoPoint) {
        addressJob?.cancel()
        _state.update { it.copy(pressed = PressedPlace(point, address = null, loading = true)) }
        addressJob = viewModelScope.launch {
            val address = try {
                (placeSearch.addressOf(point) as? SearchResult.Ok)?.value?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { s -> s.copy(pressed = s.pressed?.takeIf { it.point == point }?.copy(address = address, loading = false)) }
        }
    }

    fun dismissPressed() {
        addressJob?.cancel()
        _state.update { it.copy(pressed = null) }
    }

    private suspend fun destinationName(tripId: Long): String {
        val stops = runCatchingNonCancel { tripStore.stops(tripId) }.orEmpty()
        return (stops.firstOrNull { it.isDestination } ?: stops.lastOrNull())?.name ?: "목적지"
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container.tripManager, container.tripStore, container.placeSearch) }
        }
    }
}
